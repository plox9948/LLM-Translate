package com.plox.llmtranslate.domain.runtime

import com.alphacephei.vosk.KaldiRecognizer
import com.alphacephei.vosk.Model
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.RuntimeError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Рантайм STT на Vosk: загрузка модели и распознавание WAV.
 * Альтернатива whisper.cpp для лёгких моделей (русская речь).
 */
@Singleton
class VoskSttRuntime @Inject constructor() : SttEngine {

    companion object {
        const val SAMPLE_RATE = 16000
    }

    private var model: Model? = null
    private var loadedModelId: String? = null

    val isLoaded: Boolean
        get() = model != null

    /**
     * Загрузить Vosk-модель.
     * @return null при успехе или [RuntimeError] при ошибке.
     */
    override suspend fun ensureLoaded(modelInfo: InstalledModel): RuntimeError? =
        withContext(Dispatchers.IO) {
            val path = modelInfo.filePath
            if (path.isNullOrBlank()) return@withContext RuntimeError.ModelNotReady

            if (loadedModelId == modelInfo.id && model != null) {
                return@withContext null
            }
            release()

            try {
                model = Model(path)
                loadedModelId = modelInfo.id
                Timber.d("Vosk-модель загружена: $path")
                null
            } catch (e: Exception) {
                Timber.e(e, "Не удалось загрузить Vosk-модель $path")
                RuntimeError.LoadFailed("vosk", e.message ?: "Неизвестная ошибка")
            }
        }

    /**
     * Распознать речь в WAV-файле.
     * @return текст или [RuntimeError].
     */
    override suspend fun transcribe(wavPath: String): Result<String> =
        withContext(Dispatchers.IO) {
            val loadedModel = model
                ?: return@withContext Result.failure(RuntimeError.ModelNotSelected)

            val file = File(wavPath)
            if (!file.exists()) {
                return@withContext Result.failure(
                    RuntimeError.InferenceFailed("vosk", "Файл не найден: $wavPath")
                )
            }

            try {
                val pcm = readPcmFromWav(file)
                if (pcm.isEmpty()) {
                    return@withContext Result.failure(
                        RuntimeError.InferenceFailed("vosk", "Пустой аудиофайл")
                    )
                }

                val recognizer = KaldiRecognizer(loadedModel, SAMPLE_RATE)
                recognizer.setWords(true)

                // Кормим PCM чанками по 4096 байт.
                val chunkSize = 4096
                var offset = 0
                while (offset < pcm.size) {
                    val len = minOf(chunkSize, pcm.size - offset)
                    recognizer.WaveForm(pcm, offset, len)
                    offset += len
                }

                val resultJson = recognizer.FinalResult()
                val text = JSONObject(resultJson).optString("text", "").trim()

                if (text.isEmpty()) {
                    Result.failure(
                        RuntimeError.InferenceFailed("vosk", "Распознавание не выполнено")
                    )
                } else {
                    Result.success(text)
                }
            } catch (e: Exception) {
                Timber.e(e, "Ошибка распознавания Vosk")
                Result.failure(
                    RuntimeError.InferenceFailed("vosk", e.message ?: "Неизвестная ошибка")
                )
            }
        }

    /**
     * Разгрузить модель.
     */
    fun release() {
        model?.close()
        model = null
        loadedModelId = null
    }

    /**
     * Прочитать PCM из WAV-файла (16-bit, mono).
     */
    private fun readPcmFromWav(file: File): ByteArray {
        val bytes = file.readBytes()
        if (bytes.size < 44 ||
            bytes[0].toInt() != 'R'.code ||
            bytes[1].toInt() != 'I'.code ||
            bytes[2].toInt() != 'F'.code ||
            bytes[3].toInt() != 'F'.code
        ) {
            Timber.w("Некорректный WAV-заголовок")
            return ByteArray(0)
        }

        // Ищем чанк "data".
        var pos = 12
        while (pos + 8 <= bytes.size) {
            if (bytes[pos].toInt() == 'd'.code &&
                bytes[pos + 1].toInt() == 'a'.code &&
                bytes[pos + 2].toInt() == 't'.code &&
                bytes[pos + 3].toInt() == 'a'.code
            ) {
                val dataSize = ((bytes[pos + 4].toInt() and 0xFF)) or
                    ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[pos + 6].toInt() and 0xFF) shl 16) or
                    ((bytes[pos + 7].toInt() and 0xFF) shl 24)
                val pcmStart = pos + 8
                val pcmEnd = minOf(pcmStart + dataSize, bytes.size)
                if (pcmEnd > pcmStart) {
                    return bytes.copyOfRange(pcmStart, pcmEnd)
                }
                break
            }
            // Переходим к следующему чанку.
            val chunkSize = ((bytes[pos + 4].toInt() and 0xFF)) or
                ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 6].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 7].toInt() and 0xFF) shl 24)
            pos += 8 + chunkSize + (chunkSize and 1)
        }
        return ByteArray(0)
    }
}
