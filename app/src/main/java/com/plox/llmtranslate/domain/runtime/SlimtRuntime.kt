package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.data.native_engine.SlimtNative
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
class SlimtRuntime @Inject constructor() : TranslationEngine {

    companion object {
        private const val POLL_INTERVAL_MS = 10L
        private const val STATUS_PENDING = 0
        private const val STATUS_DONE = 1
        private const val STATUS_ERROR = 2
        private const val STATUS_CANCELLED = 3
    }

    /**
     * Нативная сессия защищена мьютексом: запуск, опрос и разгрузка
     * сериализованы — use-after-free нативного slimt::Model исключён.
     */
    private val engineMutex = Mutex()
    private var handle: Long = 0L
    private var loadedModelId: String? = null

    private val _loadedModel = MutableStateFlow<LoadedModelInfo?>(null)

    /** Состояние загруленной модели; пишется только под [engineMutex]. */
    override val loadedModel: StateFlow<LoadedModelInfo?> = _loadedModel

    val isLoaded: Boolean
        get() = handle != 0L

    /**
     * Загрузить пачку Firefox-модели (marian .bin + shortlist + словари).
     */
    override suspend fun ensureLoaded(modelInfo: InstalledModel): RuntimeError? =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val path = modelInfo.filePath
            if (path.isNullOrBlank()) return@withContext RuntimeError.ModelNotReady

            engineMutex.withLock {
                if (loadedModelId == modelInfo.id && isLoaded) {
                    return@withContext null
                }

                releaseUnlocked()

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
                    handle = newHandle
                    loadedModelId = modelInfo.id
                    _loadedModel.value = LoadedModelInfo(
                        modelId = modelInfo.id,
                        displayName = modelInfo.displayName,
                        category = ModelCategory.TRANSLATION,
                        ramRequiredMb = modelInfo.ramRequiredMb
                    )
                    Timber.d("slimt: модель ${modelInfo.id} загружена (mmap)")
                    null
                } catch (e: CancellationException) {
                    releaseUnlocked()
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Не удалось загрузить slimt-модель $path")
                    releaseUnlocked()
                    RuntimeError.LoadFailed("slimt", e.message ?: "Неизвестная ошибка")
                }
            }
        }

    /**
     * Перевести текст через slimt (Blocking-фронтенд с атомарным флагом
     * отмены; опрос с границами отмены корутины между итерациями).
     */
    override suspend fun translate(text: String, from: String, to: String): Result<String> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            engineMutex.withLock {
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
     * Разгрузить нативную сессию. Деструктор slimt::Async дожидается
     * воркера — вызов вне главного потока.
     */
    override suspend fun release() = engineMutex.withLock { releaseUnlocked() }

    private fun releaseUnlocked() {
        if (handle != 0L) {
            SlimtNative.nativeDestroy(handle)
            handle = 0L
        }
        loadedModelId = null
        _loadedModel.value = null
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
