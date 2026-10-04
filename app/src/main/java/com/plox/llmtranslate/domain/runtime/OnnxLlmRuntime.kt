package com.plox.llmtranslate.domain.runtime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.RuntimeError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Рантайм LLM на ONNX Runtime: NLLB-200 (encoder-decoder).
 *
 * Модель состоит из двух сессий: encoder и decoder.
 * Токенизация NLLB использует SentencePiece — словарь подгружается из assets.
 */
@Singleton
class OnnxLlmRuntime @Inject constructor() : TranslationEngine {

    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var loadedModelId: String? = null

    val isLoaded: Boolean
        get() = encoderSession != null && decoderSession != null

    /**
     * Загрузить ONNX-модель (encoder + decoder).
     * @return null при успехе или [RuntimeError] при ошибке.
     */
    override suspend fun ensureLoaded(modelInfo: InstalledModel): RuntimeError? =
        withContext(Dispatchers.IO) {
            val path = modelInfo.filePath
            if (path.isNullOrBlank()) return@withContext RuntimeError.ModelNotReady

            if (loadedModelId == modelInfo.id && isLoaded) {
                return@withContext null
            }
            release()

            try {
                env = OrtEnvironment.getEnvironment()
                val ortEnv = env!!

                val dir = File(path)
                val encoderFile = findModelFile(dir, "encoder")
                val decoderFile = findModelFile(dir, "decoder")

                if (encoderFile == null || decoderFile == null) {
                    Timber.e("Не найдены encoder/decoder в $path")
                    return@withContext RuntimeError.LoadFailed(
                        "onnx", "Не найдены encoder/decoder модели"
                    )
                }

                val sessionOpts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                    setInterOpNumThreads(1)
                }

                encoderSession = ortEnv.createSession(encoderFile.absolutePath, sessionOpts)
                decoderSession = ortEnv.createSession(decoderFile.absolutePath, sessionOpts)
                loadedModelId = modelInfo.id
                Timber.d("ONNX-модель загружена: $path")
                null
            } catch (e: Exception) {
                Timber.e(e, "Не удалось загрузить ONNX-модель $path")
                release()
                RuntimeError.LoadFailed("onnx", e.message ?: "Неизвестная ошибка")
            }
        }

    /**
     * Перевести текст.
     *
     * Полная реализация NLLB требует:
     * 1. Токенизации SentencePiece (словарь из assets)
     * 2. Инференса encoder
     * 3. Петли генерации decoder с cross-attention
     *
     * @return текст перевода или [RuntimeError].
     */
    override suspend fun translate(text: String, from: String, to: String): Result<String> =
        withContext(Dispatchers.IO) {
            val encoder = encoderSession
            val decoder = decoderSession
            if (encoder == null || decoder == null) {
                return@withContext Result.failure(RuntimeError.ModelNotSelected)
            }

            try {
                // TODO: Реализовать полный конвейер NLLB:
                // 1. Токенизация входного текста (SentencePiece)
                // 2. Инференс encoder -> attention key/value
                // 3. Петля генерации decoder (greedy/beam search)
                // 4. Детокенизация результата
                //
                // Пока возвращаем заглушку, чтобы не ломать интерфейс.
                Timber.w("NLLB ONNX: конвейер генерации ещё не реализован")
                Result.failure(
                    RuntimeError.InferenceFailed(
                        "onnx",
                        "Конвейер NLLB ещё не реализован"
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "Ошибка инференса ONNX")
                Result.failure(
                    RuntimeError.InferenceFailed("onnx", e.message ?: "Неизвестная ошибка")
                )
            }
        }

    /**
     * Разгрузить модель.
     */
    fun release() {
        try {
            encoderSession?.close()
            decoderSession?.close()
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при разгрузке ONNX")
        }
        encoderSession = null
        decoderSession = null
        loadedModelId = null
    }

    /**
     * Найти файл модели по части имени.
     */
    private fun findModelFile(dir: File, namePart: String): File? {
        if (!dir.exists()) return null
        return dir.listFiles()?.firstOrNull {
            it.isFile && it.name.lowercase().contains(namePart) &&
                it.name.endsWith(".onnx")
        }
    }

    /**
     * Утилита для создания тензора из float-массива.
     */
    private fun floatTensor(data: FloatArray, shape: LongArray): OnnxTensor? {
        return try {
            OnnxTensor.createTensor(env, data, shape)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка создания тензора")
            null
        }
    }
}
