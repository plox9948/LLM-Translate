package com.plox.llmtranslate.domain.runtime

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.RuntimeError
import com.plox.llmtranslate.util.WavReader
import kotlinx.coroutines.Dispatchers
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

            if (loadedModelId == model.id && recognizer != null) {
                return@withContext null
            }
            release()

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
                recognizer = buildRecognizer(info, whisperLanguage)
                loadedModelId = model.id
                Timber.d("Sherpa-модель загружена (${info.type}): $path")
                null
            } catch (e: Exception) {
                Timber.e(e, "Не удалось загрузить sherpa-модель $path")
                release()
                RuntimeError.LoadFailed("sherpa", e.message ?: "Неизвестная ошибка")
            }
        }

    /**
     * Распознать речь в WAV-файле.
     * @param language подсказка языка (ru/en/ne); для whisper переключает распознавание.
     * @return текст или [RuntimeError].
     */
    override suspend fun transcribe(wavPath: String, language: String?): Result<String> =
        withContext(Dispatchers.IO) {
            val info = this@SherpaSttEngine.modelInfo
            if (info == null || recognizer == null) {
                return@withContext Result.failure(RuntimeError.ModelNotSelected)
            }

            // Для whisper при смене языка пересоздаём распознаватель.
            if (info.type == TYPE_WHISPER && language != null && language != whisperLanguage) {
                whisperLanguage = language
                recognizer = buildRecognizer(info, language)
                Timber.d("Whisper переключён на язык: $language")
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

                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    rec.decode(stream)
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
            } catch (e: Exception) {
                Timber.e(e, "Ошибка распознавания sherpa")
                Result.failure(
                    RuntimeError.InferenceFailed("sherpa", e.message ?: "Неизвестная ошибка")
                )
            }
        }

    /**
     * Разгрузить модель.
     */
    override fun release() {
        recognizer?.release()
        recognizer = null
        modelInfo = null
        whisperLanguage = null
        loadedModelId = null
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
        val onnxFiles = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".onnx") }
            ?: return null
        val tokens = dir.listFiles()?.firstOrNull { it.isFile && it.name == "tokens.txt" }
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
