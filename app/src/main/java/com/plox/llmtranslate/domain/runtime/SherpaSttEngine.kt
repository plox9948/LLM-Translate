package com.plox.llmtranslate.domain.runtime

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.RuntimeError
import com.plox.llmtranslate.util.WavReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Рантайм STT на sherpa-onnx: загрузка модели и распознавание WAV.
 *
 * Тип модели определяется по файлам каталога:
 * - есть "joiner" → transducer (encoder/decoder/joiner);
 * - есть encoder и decoder, но нет joiner → whisper;
 * - один файл вида model*.onnx → nemo_ctc.
 *
 * Для whisper-моделей язык распознавания задаётся в [transcribe]; при его смене
 * распознаватель пересоздаётся.
 */
@Singleton
class SherpaSttEngine @Inject constructor() : SttEngine {

    companion object {
        const val SAMPLE_RATE = 16000
        private const val TYPE_TRANSDUCER = "transducer"
        private const val TYPE_NEMO_CTC = "nemo_ctc"
        private const val TYPE_WHISPER = "whisper"
    }

    private var recognizer: OfflineRecognizer? = null
    private var modelInfo: ModelInfo? = null
    private var whisperLanguage: String? = null
    private var loadedModelId: String? = null

    /**
     * Защита нативного recognizer: распознавание, загрузка и разгрузка
     * сериализуются, поэтому release() ждёт окончания активного decode
     * (use-after-free исключён). Распознавание может идти десятки секунд —
     * для onStop-разгрузки это приемлемо, release() вызывается вне главного потока.
     */
    private val recognizerMutex = Mutex()

    private val _loadedModel = MutableStateFlow<LoadedModelInfo?>(null)

    /** Состояние загруженной модели; пишется только под [recognizerMutex]. */
    override val loadedModel: StateFlow<LoadedModelInfo?> = _loadedModel

    val isLoaded: Boolean
        get() = recognizer != null

    /**
     * Загрузить sherpa-модель.
     * @return null при успехе или [RuntimeError] при ошибке.
     */
    override suspend fun ensureLoaded(model: InstalledModel): RuntimeError? =
        withContext(Dispatchers.IO) {
            val path = model.filePath
            if (path.isNullOrBlank()) return@withContext RuntimeError.ModelNotReady

            recognizerMutex.withLock {
                // Быстрый путь под мьютексом: вне него поле loadedModelId могло
                // быть очищено параллельным release() — проверка дала бы ложное
                // «уже загружено» и распознавание упало бы на null-распознавателе.
                if (loadedModelId == model.id && recognizer != null) {
                    return@withContext null
                }

                releaseUnlocked()

                try {
                    val info = detectModel(File(path))
                    if (info == null) {
                        Timber.e("Не удалось определить тип sherpa-модели: $path")
                        return@withContext RuntimeError.LoadFailed(
                            "sherpa", "Не удалось определить тип модели по файлам"
                        )
                    }
                    this@SherpaSttEngine.modelInfo = info
                    whisperLanguage = if (info.type == TYPE_WHISPER) "auto" else null
                    // Граница отмены перед долгим нативным построением
                    // распознавателя (секунды-десятки секунд, не прерываемо).
                    currentCoroutineContext().ensureActive()
                    recognizer = buildRecognizer(info, whisperLanguage)
                    loadedModelId = model.id
                    _loadedModel.value = LoadedModelInfo(
                        modelId = model.id,
                        displayName = model.displayName,
                        category = ModelCategory.STT,
                        ramRequiredMb = model.ramRequiredMb
                    )
                    Timber.d("Sherpa-модель загружена (${info.type}): $path")
                    null
                } catch (e: CancellationException) {
                    // Отмена загрузки кнопкой «Отмена» — не ошибка: освобождаем
                    // частично созданные ресурсы и всплываем выше.
                    Timber.d("Загрузка sherpa-модели отменена: $path")
                    releaseUnlocked()
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Не удалось загрузить sherpa-модель $path")
                    releaseUnlocked()
                    RuntimeError.LoadFailed("sherpa", e.message ?: "Неизвестная ошибка")
                }
            }
        }

    /**
     * Распознать речь в WAV-файле.
     * @param language подсказка языка (ru/en/ne); для whisper переключает распознавание.
     * @return текст или [RuntimeError].
     */
    override suspend fun transcribe(wavPath: String, language: String?): Result<String> =
        withContext(Dispatchers.IO) {
            recognizerMutex.withLock {
                val info = this@SherpaSttEngine.modelInfo
                if (info == null || recognizer == null) {
                    return@withContext Result.failure(RuntimeError.ModelNotSelected)
                }

                // Для whisper при смене языка пересоздаём распознаватель.
                if (info.type == TYPE_WHISPER && language != null && language != whisperLanguage) {
                    try {
                        // Обязательно освобождаем старый распознаватель — иначе
                        // утечка сотен МБ нативной памяти при каждой смене языка.
                        recognizer?.release()
                        recognizer = null
                        recognizer = buildRecognizer(info, language)
                        whisperLanguage = language
                        Timber.d("Whisper переключён на язык: $language")
                    } catch (e: Exception) {
                        Timber.e(e, "Не удалось пересоздать whisper для языка $language")
                        recognizer = null
                        whisperLanguage = null
                        loadedModelId = null
                        _loadedModel.value = null
                        return@withContext Result.failure(
                            RuntimeError.LoadFailed(
                                "sherpa",
                                "Не удалось переключить язык: ${e.message ?: "Неизвестная ошибка"}"
                            )
                        )
                    }
                }

                val rec = recognizer
                    ?: return@withContext Result.failure(RuntimeError.ModelNotSelected)

                val file = File(wavPath)
                if (!file.exists()) {
                    return@withContext Result.failure(
                        RuntimeError.InferenceFailed("sherpa", "Файл не найден: $wavPath")
                    )
                }

                try {
                    val samples = WavReader.readPcmAsFloat(file)
                    if (samples.isEmpty()) {
                        return@withContext Result.failure(
                            RuntimeError.InferenceFailed("sherpa", "Пустой аудиофайл")
                        )
                    }

                    // Граница отмены между чтением WAV и нативным decode —
                    // сам decode (десятки секунд) не прерываем, отмена срабатывает
                    // на фазах вокруг него.
                    currentCoroutineContext().ensureActive()
                    val stream = rec.createStream()
                    try {
                        stream.acceptWaveform(samples, SAMPLE_RATE)
                        rec.decode(stream)
                        // Граница отмены после decode: отменённая операция
                        // не отдаёт результат, stream освобождается в finally.
                        currentCoroutineContext().ensureActive()
                        val text = rec.getResult(stream).text.trim()
                        if (text.isEmpty()) {
                            Result.failure(
                                RuntimeError.InferenceFailed("sherpa", "Распознавание не выполнено")
                            )
                        } else {
                            Result.success(text)
                        }
                    } finally {
                        stream.release()
                    }
                } catch (e: CancellationException) {
                    // Отмена распознавания кнопкой «Отмена» — не ошибка:
                    // всплываем выше, чтобы UI возвращался в idle без ошибки.
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Ошибка распознавания sherpa")
                    Result.failure(
                        RuntimeError.InferenceFailed("sherpa", e.message ?: "Неизвестная ошибка")
                    )
                }
            }
        }

    /**
     * Разгрузить модель. Ждёт окончания активного распознавания (recognizerMutex).
     */
    override suspend fun release() {
        recognizerMutex.withLock { releaseUnlocked() }
    }

    /**
     * Непосредственная разгрузка распознавателя. Вызывать только под
     * `recognizerMutex`, чтобы не пересекаться с активным decode.
     */
    private fun releaseUnlocked() {
        recognizer?.release()
        recognizer = null
        modelInfo = null
        whisperLanguage = null
        loadedModelId = null
        _loadedModel.value = null
    }

    /**
     * Метаданные загруженной модели (тип и пути к файлам).
     */
    private data class ModelInfo(
        val type: String,
        val tokensPath: String,
        val encoderPath: String?,
        val decoderPath: String?,
        val joinerPath: String?,
        val nemoModelPath: String?
    )

    /**
     * Определить тип модели по файлам каталога.
     */
    private fun detectModel(dir: File): ModelInfo? {
        val files = dir.listFiles() ?: return null
        val onnxFiles = files.filter { it.isFile && it.name.endsWith(".onnx") }
        val tokens = files.firstOrNull { it.isFile && it.name == "tokens.txt" }
            ?: return null

        fun byContains(part: String): String? =
            onnxFiles.firstOrNull { it.name.lowercase().contains(part) }?.absolutePath

        val joiner = byContains("joiner")
        val encoder = byContains("encoder")
        val decoder = byContains("decoder")

        return when {
            joiner != null && encoder != null && decoder != null -> ModelInfo(
                type = TYPE_TRANSDUCER,
                tokensPath = tokens.absolutePath,
                encoderPath = encoder,
                decoderPath = decoder,
                joinerPath = joiner,
                nemoModelPath = null
            )
            encoder != null && decoder != null -> ModelInfo(
                type = TYPE_WHISPER,
                tokensPath = tokens.absolutePath,
                encoderPath = encoder,
                decoderPath = decoder,
                joinerPath = null,
                nemoModelPath = null
            )
            else -> {
                val model = onnxFiles.firstOrNull { it.name.lowercase().startsWith("model") }
                if (model != null) {
                    ModelInfo(
                        type = TYPE_NEMO_CTC,
                        tokensPath = tokens.absolutePath,
                        encoderPath = null,
                        decoderPath = null,
                        joinerPath = null,
                        nemoModelPath = model.absolutePath
                    )
                } else {
                    null
                }
            }
        }
    }

    /**
     * Собрать OfflineRecognizer по метаданным модели.
     */
    private fun buildRecognizer(info: ModelInfo, language: String?): OfflineRecognizer {
        val modelConfig = when (info.type) {
            TYPE_TRANSDUCER -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = info.encoderPath!!,
                    decoder = info.decoderPath!!,
                    joiner = info.joinerPath!!
                ),
                tokens = info.tokensPath
            )
            TYPE_WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = info.encoderPath!!,
                    decoder = info.decoderPath!!,
                    language = language ?: "auto",
                    task = "transcribe"
                ),
                tokens = info.tokensPath
            )
            else -> OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = info.nemoModelPath!!),
                tokens = info.tokensPath
            )
        }
        return OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = modelConfig))
    }
}
