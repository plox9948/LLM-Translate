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
import kotlinx.coroutines.flow.StateFlow
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
     * Пучки движка хранятся в LRU-кэше [engineCache].
     */
    private class ModelBundle(val handle: Long, val info: LoadedModelInfo)

    /**
     * Каркас LRU-кэша пучков: нативные модели защищены внутренним мьютексом —
     * запуск, опрос и разгрузка сериализованы, use-after-free нативного
     * slimt::Model исключён (деструктор slimt::Async дожидается воркера,
     * release() вызывается вне главного потока).
     */
    private val engineCache = EngineCacheScaffold<ModelBundle>(
        capacity = MODEL_CACHE_CAPACITY,
        retentionProvider = retentionProvider,
        logLabel = "slimt",
        releaseBundle = { bundle ->
            if (bundle.handle != 0L) {
                SlimtNative.nativeDestroy(bundle.handle)
            }
        },
        infoOf = { it.info },
    )

    /** Состояние загруженных моделей; пишется только под мьютексом [engineCache]. */
    override val loadedModels: StateFlow<List<LoadedModelInfo>> = engineCache.loadedModels

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

            engineCache.sessionMutex.withLock {
                // Быстрый путь под мьютексом: пучок уже в кэше — делать
                // активным без повторной загрузки (смена направления, issue #36).
                if (engineCache.reuseCachedUnlocked(modelInfo.id)) {
                    return@withContext null
                }

                // Политика выгрузки при смене направления читается на каждую
                // загрузку — смена настройки применяется сразу.
                engineCache.applyRetentionUnlocked()

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
                    engineCache.cache.put(
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
                    engineCache.publishActiveUnlocked(modelInfo.id)
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
            engineCache.sessionMutex.withLock {
                val handle = engineCache.activeBundle()?.handle ?: 0L
                if (handle == 0L) {
                    return@withContext Result.failure(RuntimeError.ModelNotSelected)
                }
                try {
                    SlimtNative.nativeStart(handle, text)
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
                        SlimtNative.nativeCancel(handle)
                        // С-drain до терминального статуса: воркер slimt
                        // прерывает пакет по флагу отмены; иначе следующий
                        // nativeStart упрётся в «already running».
                        while (SlimtNative.nativePoll(handle) == STATUS_PENDING) {
                            delay(POLL_INTERVAL_MS)
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
    override suspend fun release() = engineCache.release()

    /**
     * Извлечь и уничтожить пучок модели [modelId] (удаление модели, issue #40).
     * Ждёт окончания активного перевода под мьютексом [engineCache]; если
     * пучок был активным — сбрасывается активный id и публикуется новый
     * список [loadedModels]. Отсутствующий в кэше id — no-op.
     */
    override suspend fun releaseModel(modelId: String) = engineCache.releaseModel(modelId)

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
