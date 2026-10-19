package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.ModelRetentionProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Общий каркас LRU-кэша пучков моделей движка перевода (issue #36):
 * мьютекс сериализации, кэш, активный id, публикация [loadedModels],
 * unload-политика при смене направления и операции release/releaseModel.
 *
 * Вынесен из идентичных копий (MarianOnnxRuntime, IndicTransOnnxRuntime,
 * SlimtRuntime, SherpaSttEngine) без изменения поведения: публичные методы
 * движков остаются на местах и делегируют сюда; тексты логов строятся из
 * [logLabel] байт-в-байт как раньше.
 *
 * Все методы с суффиксом `Unlocked` вызывать только под [sessionMutex].
 * Освобождение ресурсов пучка делегировано [releaseBundle] (сессии ORT
 * либо нативный handle slimt), метаданные для UI — [infoOf].
 *
 * @param T тип пучка модели движка.
 * @param capacity ёмкость LRU-кэша.
 * @param retentionProvider настройка keep/unload при смене направления.
 * @param logLabel метка движка в логах ("Marian", "IndicTrans", "slimt").
 * @param releaseBundle освободить нативные ресурсы одного пучка.
 * @param infoOf метаданные пучка для публикации в [loadedModels].
 * @param byteBudget байтовый бюджет resident-памяти кэша;
 *   [Long.MAX_VALUE] — только count-LRU (без байтового гейта).
 * @param sizeOfBundle размер пучка для байтового бюджета.
 */
internal class EngineCacheScaffold<T>(
    capacity: Int,
    private val retentionProvider: ModelRetentionProvider,
    private val logLabel: String,
    private val releaseBundle: (T) -> Unit,
    private val infoOf: (T) -> LoadedModelInfo,
    byteBudget: Long = Long.MAX_VALUE,
    sizeOfBundle: (T) -> Long = { 0L },
) {

    companion object {
        /**
         * Общая ёмкость LRU-кэша пучков для всех движков (ревью — была
         * дублирована в четырёх): два направления перевода + активная модель
         * (аналогично несколько STT-моделей + активная). Байтовый бюджет
         * resident-памяти дополняет count-LRU у ORT-движков
         * ([ORT_CACHE_BYTE_BUDGET], ревью #83).
         */
        const val DEFAULT_MODEL_CACHE_CAPACITY = 3

        /**
         * Байтовый бюджет resident-памяти ORT-кэшей переводческих движков
         * (ревью #83): пучок IndicTrans стоит сотни МБ, и три пучка по
         * count-LRU могли суммарно превысить разумный бюджет ОЗУ до
         * первой эвикции (риск ОЗМ). При превышении вытесняется eldest,
         * новый активный пучок защищён. slimt не ограничивается осознанно:
         * его модели отображены mmap, чистые страницы изымаются ОС под
         * давлением без выгрузки пучка.
         */
        const val ORT_CACHE_BYTE_BUDGET = 1_024L * 1024 * 1024
    }

    /**
     * Защита нативных ресурсов: инференс, загрузка и разгрузка
     * сериализуются, поэтому release() ждёт окончания активного инференса
     * (use-after-free исключён). Вся работа с [cache] и [activeId] — только
     * под этим мьютексом.
     */
    internal val sessionMutex = Mutex()

    /**
     * LRU-кэш загруженных пучков моделей (issue #36). Наружу не отдаётся:
     * кладка пучков идёт через [putBundleUnlocked] (ревью #79 — прежнее
     * значение при повторном put не должно теряться без освобождения).
     * internal — для юнит-тестов каркаса и специфичных путей движков
     * (снятие неконсистентного пучка без освобождения в SherpaSttEngine).
     */
    internal val cache = EngineModelCache<T>(capacity, byteBudget, sizeOfBundle)

    /**
     * Id пучка, на котором работает translate движка; пишется и читается
     * только под [sessionMutex]. Наружу не отдаётся (ревью #79).
     * internal — только для юнит-тестов каркаса.
     */
    internal var activeId: String? = null

    private val _loadedModels = MutableStateFlow<List<LoadedModelInfo>>(emptyList())

    /** Состояние загруженных моделей; пишется только под [sessionMutex]. */
    val loadedModels: StateFlow<List<LoadedModelInfo>> = _loadedModels

    /**
     * Активный пучок по [activeId] с отметкой использования, или null,
     * если активного/загруженного пучка нет. Вызывать только под [sessionMutex].
     */
    fun activeBundle(): T? = activeId?.let { cache.get(it) }

    /**
     * Быстрый путь загрузки: если пучок [modelId] уже в кэше — сделать его
     * активным (с unload-политикой: в unload-режиме движок обязан держать
     * только текущую модель) и вернуть true. Вызывать только под [sessionMutex].
     */
    suspend fun reuseCachedUnlocked(modelId: String): Boolean {
        if (cache.get(modelId) == null) return false
        if (!retentionProvider.keepModelsOnDirectionChange()) {
            unloadOtherBundlesUnlocked(modelId)
        }
        activeId = modelId
        publishLoadedUnlocked()
        return true
    }

    /**
     * Применить политику выгрузки перед загрузкой нового пучка: она читается
     * на каждую загрузку — смена настройки применяется сразу. Вызывать
     * только под [sessionMutex].
     */
    suspend fun applyRetentionUnlocked() {
        if (!retentionProvider.keepModelsOnDirectionChange()) {
            releaseUnlocked()
        }
    }

    /**
     * Положить пучок [bundle] в кэш под [sessionMutex]. Если по этому id уже
     * был пучок — он изымается и освобождается через [releaseBundle]: прежний
     * нативный ресурс не должен переживать замену (ревью #79).
     */
    fun putBundleUnlocked(modelId: String, bundle: T) {
        cache.put(modelId, bundle)?.let { previous ->
            Timber.w("$logLabel: пучок $modelId уже был в кэше — прежний освобождён")
            releaseBundle(previous)
        }
    }

    /**
     * Зафиксировать только что положенный в кэш пучок [modelId] как активный:
     * вытеснить пучки сверх ёмкости и опубликовать список. Вызывать только
     * под [sessionMutex] (пучок уже в [cache]).
     */
    fun publishActiveUnlocked(modelId: String) {
        // Вытеснение сверх ёмкости — ДО фиксации активного id (ревью):
        // иначе [activeId] может остаться, ссылаясь на уже освобождённый
        // (вытесненный) пучок.
        cache.evictOverCapacity().forEach { (evictedId, bundle) ->
            Timber.d("$logLabel: вытеснена из кэша модель $evictedId")
            releaseBundle(bundle)
        }
        // Байтовый бюджет (ревью #83): после размещения нового пучка
        // выгружаем eldest до возврата в бюджет; сам новый активный
        // пучок [modelId] от вытеснения защищён.
        cache.evictOverByteBudget(protect = setOf(modelId)).forEach { (evictedId, bundle) ->
            Timber.d("$logLabel: вытеснена по байтовому бюджету модель $evictedId")
            releaseBundle(bundle)
        }
        activeId = modelId
        publishLoadedUnlocked()
    }

    /**
     * Разгрузить ВСЕ удерживаемые движком модели. Ждёт окончания активного
     * инференса ([sessionMutex]).
     */
    suspend fun release() {
        sessionMutex.withLock { releaseUnlocked() }
    }

    /**
     * Извлечь и освободить пучок модели [modelId] (удаление модели, issue #40).
     * Ждёт окончания активного инференса под [sessionMutex]; если пучок был
     * активным — сбрасывает [activeId] и публикует новый список
     * [loadedModels]. Отсутствующий в кэше id — no-op.
     */
    suspend fun releaseModel(modelId: String) {
        sessionMutex.withLock {
            cache.remove(modelId)?.let { bundle ->
                releaseBundle(bundle)
                Timber.d("$logLabel: пучок удалённой модели $modelId освобождён")
            }
            if (activeId == modelId) {
                activeId = null
            }
            publishLoadedUnlocked()
        }
    }

    /**
     * Непосредственная разгрузка всех пучков. Вызывать только под [sessionMutex],
     * чтобы не пересекаться с активным инференсом.
     */
    fun releaseUnlocked() {
        cache.clear().forEach { (_, bundle) -> releaseBundle(bundle) }
        activeId = null
        _loadedModels.value = emptyList()
    }

    /**
     * Изъять из кэша ВСЕ пучки БЕЗ освобождения их нативных ресурсов
     * (ревью повт. аудита): освобождающий колбэк может зависнуть (nativeDestroy
     * джойнит зависший воркер) и не должен выполняться под [sessionMutex].
     * Кэш при этом сразу пуст и активный id сброшен — [reuseCachedUnlocked]
     * не переиспользует битый пучок, а повторные [release]/[releaseModel]
     * на изъятых пучках — no-op. Освобождение возвращённых пучков — обязанность
     * вызывающего (вне мьютекса). Вызывать только под [sessionMutex].
     */
    fun detachAllUnlocked(): List<T> {
        val detached = cache.clear().map { (_, bundle) -> bundle }
        activeId = null
        _loadedModels.value = emptyList()
        return detached
    }

    /**
     * Опубликовать список загруженных моделей (активная — первой).
     * Вызывать только под [sessionMutex].
     */
    fun publishLoadedUnlocked() {
        val active = activeId
        _loadedModels.value = cache.all()
            .sortedByDescending { (id, _) -> id == active }
            .map { (_, bundle) -> infoOf(bundle) }
    }

    /**
     * Выгрузить все пучки кэша, кроме [keepId] (unload-режим при попадании
     * в кэш — движок держит только текущую модель). Вызывать только под
     * [sessionMutex].
     */
    private fun unloadOtherBundlesUnlocked(keepId: String) {
        cache.keysNotIn(setOf(keepId)).forEach { staleId ->
            cache.remove(staleId)?.let(releaseBundle)
            Timber.d("$logLabel: выгружена модель другого направления $staleId")
        }
    }
}
