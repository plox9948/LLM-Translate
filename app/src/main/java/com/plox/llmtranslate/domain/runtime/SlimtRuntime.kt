package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.data.native_engine.SlimtNative
import com.plox.llmtranslate.domain.ModelRetentionProvider
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.RuntimeError
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import timber.log.Timber

/**
 * Рантайм переводов на языковых паках Firefox Translations (Bergamot):
 * `model.*.intgemm.alphas.bin` + `lex.*.s2t.bin` + `vocab.*.spm`.
 * Инференс выполняет нативная библиотека slimt (тот же C++-движок, что
 * Firefox Translations и проект offline-translator): жадный декод с
 * повторным beam-проходом для ненадёжных предложений (two-pass robust
 * decoder внутри slimt), токенизация SentencePiece — на C++-стороне.
 *
 * Каталог файлов модели: каталог пачки из [InstalledModel.filePath]
 * (мультифайловая установка через SlimtInstaller).
 */
@Singleton
class SlimtRuntime @Inject constructor(
    private val retentionProvider: ModelRetentionProvider
) : TranslationEngine {

    companion object {
        private const val POLL_INTERVAL_MS = 10L
        private const val STATUS_PENDING = 0
        private const val STATUS_DONE = 1
        private const val STATUS_ERROR = 2
        private const val STATUS_CANCELLED = 3

        /** Ёмкость LRU-кэша: два направления перевода + активная модель. */
        private const val MODEL_CACHE_CAPACITY = 3
    }

    /**
     * Пучок модели slimt: нативный handle и метаданные для UI.
     * Пучки движка хранятся в LRU-кэше [cache].
     */
    private class ModelBundle(val handle: Long, val info: LoadedModelInfo)

    /**
     * Нативные сессии защищены мьютексом: запуск, опрос и разгрузка
     * сериализованы — use-after-free нативного slimt::Model исключён.
     * Вся работа с [cache] и [activeId] — только под этим мьютексом.
     */
    private val engineMutex = Mutex()

    /** LRU-кэш загруженных пучков моделей (issue #36). */
    private val cache = EngineModelCache<ModelBundle>(MODEL_CACHE_CAPACITY)

    /**
     * Id пучка, на котором работает [translate]; пишется только под [engineMutex],
     * читается и вне него ([isLoaded]) — отсюда @Volatile для корректной видимости.
     */
    @Volatile
    private var activeId: String? = null

    private val _loadedModels = MutableStateFlow<List<LoadedModelInfo>>(emptyList())

    /** Состояние загруженных моделей; пишется только под [engineMutex]. */
    override val loadedModels: StateFlow<List<LoadedModelInfo>> = _loadedModels

    val isLoaded: Boolean
        get() = activeId != null

    /**
     * Загрузить пачку Firefox-модели (marian .bin + shortlist + словари).
     * При настройке keep другие направления остаются в LRU-кэше движка,
     * при unload — движок держит ТОЛЬКО текущую модель: чужие пучки
     * выгружаются и перед загрузкой, и при попадании в кэш (прежнее поведение).
     */
    override suspend fun ensureLoaded(modelInfo: InstalledModel): RuntimeError? =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val path = modelInfo.filePath
            if (path.isNullOrBlank()) return@withContext RuntimeError.ModelNotReady

            engineMutex.withLock {
                val cached = cache.get(modelInfo.id)
                if (cached != null) {
                    // Быстрый путь под мьютексом: пучок уже в кэше — делать
                    // активным без повторной загрузки (смена направления, issue #36).
                    // Политика применяется и здесь: в unload-режиме движок
                    // обязан держать только текущую модель.
                    if (!retentionProvider.keepModelsOnDirectionChange()) {
                        unloadOtherBundlesUnlocked(modelInfo.id)
                    }
                    activeId = modelInfo.id
                    publishLoadedUnlocked()
                    return@withContext null
                }

                // Политика выгрузки при смене направления читается на каждую
                // загрузку — смена настройки применяется сразу.
                if (!retentionProvider.keepModelsOnDirectionChange()) {
                    releaseUnlocked()
                }

                val dir = File(path)
                val files = resolvePackageFiles(dir)
                if (files == null) {
                    Timber.e("Не найдены файлы slimt-пачки в $path")
                    return@withContext RuntimeError.LoadFailed(
                        "slimt",
                        "Не найдены файлы модели (нужны model.*.intgemm.alphas.bin, lex.*.s2t.bin, vocab.*.spm)"
                    )
                }

                try {
                    currentCoroutineContext().ensureActive()
                    val newHandle = SlimtNative.nativeCreate(
                        modelPath = files.model.absolutePath,
                        vocabPath = files.vocab.absolutePath,
                        shortlistPath = files.shortlist.absolutePath,
                        targetVocabPath = files.targetVocab?.absolutePath
                    )
                    if (newHandle == 0L) {
                        return@withContext RuntimeError.LoadFailed(
                            "slimt", "Не удалось создать модель slimt"
                        )
                    }
                    cache.put(
                        modelInfo.id,
                        ModelBundle(
                            handle = newHandle,
                            info = LoadedModelInfo(
                                modelId = modelInfo.id,
                                displayName = modelInfo.displayName,
                                category = ModelCategory.TRANSLATION,
                                ramRequiredMb = modelInfo.ramRequiredMb
                            )
                        )
                    )
                    activeId = modelInfo.id
                    cache.evictOverCapacity().forEach { (evictedId, bundle) ->
                        Timber.d("slimt: вытеснена из кэша модель $evictedId")
                        SlimtNative.nativeDestroy(bundle.handle)
                    }
                    publishLoadedUnlocked()
                    Timber.d("slimt: модель ${modelInfo.id} загружена (mmap)")
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Не удалось загрузить slimt-модель $path")
                    RuntimeError.LoadFailed("slimt", e.message ?: "Неизвестная ошибка")
                }
            }
        }

    /**
     * Перевести текст через slimt (Blocking-фронтенд с атомарным флагом
     * отмены; опрос с границами отмены корутины между итерациями).
     * Инференс идёт по активному пучку кэша — [ensureLoaded] вызывает
     * перед переводом, поэтому активный пучок соответствует модели
     * направления; при отсутствии активного — [RuntimeError.ModelNotSelected].
     */
    override suspend fun translate(text: String, from: String, to: String): Result<String> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            engineMutex.withLock {
                val bundle = activeId?.let { cache.get(it) }
                val handle = bundle?.handle ?: 0L
                if (handle == 0L) {
                    return@withContext Result.failure(RuntimeError.ModelNotSelected)
                }
                try {
                    SlimtNative.nativeStart(handle, text, false)
                    var outcome: Result<String>? = null
                    while (outcome == null) {
                        currentCoroutineContext().ensureActive()
                        when (SlimtNative.nativePoll(handle)) {
                            STATUS_DONE -> {
                                outcome = Result.success(SlimtNative.nativeTakeResult(handle))
                            }

                            STATUS_ERROR -> {
                                val reason = SlimtNative.nativeTakeError(handle)
                                outcome = Result.failure(
                                    RuntimeError.InferenceFailed("slimt", reason)
                                )
                            }

                            STATUS_CANCELLED -> {
                                outcome = Result.failure(
                                    RuntimeError.InferenceFailed("slimt", "Перевод отменён")
                                )
                            }

                            else -> delay(POLL_INTERVAL_MS)
                        }
                    }
                    outcome
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        if (handle != 0L) {
                            SlimtNative.nativeCancel(handle)
                            // С-drain до терминального статуса: воркер slimt
                            // прерывает пакет по флагу отмены; иначе следующий
                            // nativeStart упрётся в «already running».
                            while (SlimtNative.nativePoll(handle) == STATUS_PENDING) {
                                delay(POLL_INTERVAL_MS)
                            }
                        }
                    }
                    Timber.d("slimt: перевод отменён пользователем")
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "slimt: ошибка запуска перевода")
                    Result.failure(
                        RuntimeError.InferenceFailed("slimt", e.message ?: "Ошибка инференса")
                    )
                }
            }
        }

    /**
     * Разгрузить ВСЕ удерживаемые движком модели. Деструктор slimt::Async
     * дожидается воркера — вызов вне главного потока.
     */
    override suspend fun release() = engineMutex.withLock { releaseUnlocked() }

    private fun releaseUnlocked() {
        cache.clear().forEach { (_, bundle) ->
            if (bundle.handle != 0L) {
                SlimtNative.nativeDestroy(bundle.handle)
            }
        }
        activeId = null
        _loadedModels.value = emptyList()
    }

    /**
     * Выгрузить все пучки кэша, кроме [keepId] (unload-режим при попадании
     * в кэш — движок держит только текущую модель). Вызывать только под
     * [engineMutex].
     */
    private fun unloadOtherBundlesUnlocked(keepId: String) {
        cache.keysNotIn(setOf(keepId)).forEach { staleId ->
            cache.remove(staleId)?.let { bundle ->
                if (bundle.handle != 0L) {
                    SlimtNative.nativeDestroy(bundle.handle)
                }
            }
            Timber.d("slimt: выгружена модель другого направления $staleId")
        }
    }

    /**
     * Опубликовать список загруженных моделей (активная — первой).
     * Вызывать только под [engineMutex].
     */
    private fun publishLoadedUnlocked() {
        val active = activeId
        _loadedModels.value = cache.all()
            .sortedByDescending { (id, _) -> id == active }
            .map { (_, bundle) -> bundle.info }
    }

    /**
     * Файлы пачки Bergamot: модель, shortlist, словарь; для двух-словарных
     * паков (srcvocab/trgvocab) — второй словарь отдельно.
     */
    internal data class PackageFiles(
        val model: File,
        val vocab: File,
        val shortlist: File,
        val targetVocab: File?
    )

    internal fun resolvePackageFiles(dir: File): PackageFiles? {
        if (!dir.isDirectory) return null
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        val model = files.firstOrNull { it.name.endsWith("alphas.bin") } ?: return null
        val shortlist = files.firstOrNull { it.name.endsWith("s2t.bin") } ?: return null
        val sharedVocab = files.firstOrNull { it.name.startsWith("vocab") && it.name.endsWith(".spm") }
        val srcVocab = files.firstOrNull { it.name.startsWith("srcvocab") && it.name.endsWith(".spm") }
        val trgVocab = files.firstOrNull { it.name.startsWith("trgvocab") && it.name.endsWith(".spm") }
        val vocab = srcVocab ?: sharedVocab ?: return null
        return PackageFiles(
            model = model,
            vocab = vocab,
            shortlist = shortlist,
            targetVocab = if (srcVocab != null) trgVocab else null
        )
    }
}
