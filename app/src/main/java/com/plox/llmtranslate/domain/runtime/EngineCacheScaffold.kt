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
 * Вынесен из трёх идентичных копий (MarianOnnxRuntime, IndicTransOnnxRuntime,
 * SlimtRuntime) без изменения поведения: публичные методы движков остаются
 * на местах и делегируют сюда; тексты логов строятся из [logLabel]
 * байт-в-байт как раньше.
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
 */
internal class EngineCacheScaffold<T>(
    capacity: Int,
    private val retentionProvider: ModelRetentionProvider,
    private val logLabel: String,
    private val releaseBundle: (T) -> Unit,
    private val infoOf: (T) -> LoadedModelInfo,
) {

    /**
     * Защита нативных ресурсов: инференс, загрузка и разгрузка
     * сериализуются, поэтому release() ждёт окончания активного инференса
     * (use-after-free исключён). Вся работа с [cache] и [activeId] — только
     * под этим мьютексом.
     */
    val sessionMutex = Mutex()

    /** LRU-кэш загруженных пучков моделей (issue #36). */
    val cache = EngineModelCache<T>(capacity)

    /**
     * Id пучка, на котором работает translate движка; пишется и читается
     * только под [sessionMutex].
     */
    var activeId: String? = null

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
     * Зафиксировать только что положенный в кэш пучок [modelId] как активный:
     * вытеснить пучки сверх ёмкости и опубликовать список. Вызывать только
     * под [sessionMutex] (пучок уже в [cache]).
     */
    fun publishActiveUnlocked(modelId: String) {
        activeId = modelId
        cache.evictOverCapacity().forEach { (evictedId, bundle) ->
            Timber.d("$logLabel: вытеснена из кэша модель $evictedId")
            releaseBundle(bundle)
        }
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
