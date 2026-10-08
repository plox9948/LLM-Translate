package com.plox.llmtranslate.domain.runtime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.RuntimeError
import com.plox.llmtranslate.domain.tokenizer.TokenizerJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Рантайм перевода на ONNX Runtime: Marian MT (opus-mt_tiny, Teradata).
 *
 * Модель — единый ONNX-граф encoder+decoder+BeamSearch (num_return_sequences=1).
 * При загрузке инспектируются входы/выходы сессии, вызов строится под них
 * (вход input_ids int64). Выход декодируется через TokenizerJson.
 */
@Singleton
class MarianOnnxRuntime @Inject constructor() : TranslationEngine {

    companion object {
        private const val INTRA_OP_THREADS = 4
        private const val INTER_OP_THREADS = 1
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: TokenizerJson? = null
    private var loadedModelId: String? = null

    val isLoaded: Boolean
        get() = session != null

    /**
     * Загрузить Marian-модель и токенизатор.
     */
    override suspend fun ensureLoaded(modelInfo: InstalledModel): RuntimeError? =
        withContext(Dispatchers.IO) {
            val path = modelInfo.filePath
            if (path.isNullOrBlank()) return@withContext RuntimeError.ModelNotReady

            if (loadedModelId == modelInfo.id && session != null) {
                return@withContext null
            }
            release()

            try {
                val ortEnv = OrtEnvironment.getEnvironment()
                env = ortEnv

                val dir = File(path)
                val modelFile = findFile(dir, "model")
                    ?: return@withContext RuntimeError.LoadFailed(
                        "marian", "Не найдена модель model-*.onnx в $path"
                    )
                val tokenizerFile = findFile(dir, "tokenizer")
                    ?: return@withContext RuntimeError.LoadFailed(
                        "marian", "Не найден tokenizer.json в $path"
                    )

                val tok = TokenizerJson()
                tok.load(tokenizerFile.absolutePath)
                tokenizer = tok

                val sessionOpts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(INTRA_OP_THREADS)
                    setInterOpNumThreads(INTER_OP_THREADS)
                }
                val sess = ortEnv.createSession(modelFile.absolutePath, sessionOpts)
                // Интроспекция: фиксируем реальные входы/выходы графа.
                Timber.d("Marian входы: ${sess.inputNames}")
                Timber.d("Marian выходы: ${sess.outputNames}")
                session = sess
                loadedModelId = modelInfo.id
                null
            } catch (e: Exception) {
                Timber.e(e, "Не удалось загрузить Marian-модель $path")
                release()
                RuntimeError.LoadFailed("marian", e.message ?: "Неизвестная ошибка")
            }
        }

    /**
     * Перевести текст (Marian encoder-decoder).
     */
    override suspend fun translate(text: String, from: String, to: String): Result<String> =
        withContext(Dispatchers.IO) {
            val sess = session
            val tok = tokenizer
            if (sess == null || tok == null) {
                return@withContext Result.failure(RuntimeError.ModelNotSelected)
            }

            try {
                val inputIds = tok.encode(text)
                if (inputIds.isEmpty()) {
                    return@withContext Result.failure(
                        RuntimeError.InferenceFailed("marian", "Пустой ввод")
                    )
                }

                val ortEnv = env ?: return@withContext Result.failure(
                    RuntimeError.InferenceFailed("marian", "Нет окружения ORT")
                )
                val inputTensor = OnnxTensor.createTensor(
                    ortEnv,
                    LongBuffer.wrap(inputIds.map { it.toLong() }.toLongArray()),
                    longArrayOf(1L, inputIds.size.toLong())
                )

                val output = sess.run(mapOf("input_ids" to inputTensor))
                val resultText = decodeOutput(sess, output, tok)
                output.close()
                inputTensor.close()

                if (resultText.isBlank()) {
                    Result.failure(RuntimeError.InferenceFailed("marian", "Пустой перевод"))
                } else {
                    Result.success(resultText)
                }
            } catch (e: Exception) {
                Timber.e(e, "Ошибка инференса Marian")
                Result.failure(RuntimeError.InferenceFailed("marian", e.message ?: "Неизвестная ошибка"))
            }
        }

    /**
     * Разгрузить модель.
     */
    override fun release() {
        try {
            session?.close()
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при разгрузке Marian")
        }
        session = null
        tokenizer = null
        loadedModelId = null
    }

    /**
     * Декодировать выходы графа: найти токены (sequences) или текст.
     */
    private fun decodeOutput(
        sess: OrtSession,
        output: OrtSession.Result,
        tok: TokenizerJson
    ): String {
        // Ищем выход с токенами (sequences) или текстом по имени.
        for (entry in output) {
            val name = entry.key
            val lowerName = name.lowercase()
            val tensor = entry.value as? OnnxTensor ?: continue
            val value = tensor.getValue()

            // Выход с токенами (sequences / generated tokens).
            if (lowerName.contains("sequence") || lowerName.contains("token")) {
                val ids = extractIds(value)
                if (ids.isNotEmpty()) return tok.decode(ids)
            }
            // Выход уже в виде текста.
            when (value) {
                is String -> return value
                is Array<*>? -> if (value?.firstOrNull() is String) {
                    @Suppress("UNCHECKED_CAST")
                    return (value as Array<String>).joinToString("").trim()
                }
            }
        }
        // Fallback: первый выход трактуем как токены.
        val first = output.get(0) as? OnnxTensor
        if (first != null) {
            val ids = extractIds(first.getValue())
            if (ids.isNotEmpty()) return tok.decode(ids)
        }
        return ""
    }

    /**
     * Извлечь список int-id из значения тензора (1D/2D, int32/int64).
     */
    private fun extractIds(value: Any?): List<Int> {
        val ids = ArrayList<Int>()
        when (value) {
            is LongArray -> value.forEach { ids.add(it.toInt()) }
            is IntArray -> value.forEach { ids.add(it) }
            is Array<*>? -> value?.forEach { row ->
                when (row) {
                    is LongArray -> row.forEach { ids.add(it.toInt()) }
                    is IntArray -> row.forEach { ids.add(it) }
                    is Array<*>? -> row?.forEach {
                        when (it) {
                            is Long -> ids.add(it.toInt())
                            is Int -> ids.add(it)
                        }
                    }
                }
            }
        }
        return ids
    }

    private fun findFile(dir: File, namePart: String): File? {
        if (!dir.exists()) return null
        return dir.listFiles()?.firstOrNull {
            it.isFile && it.name.lowercase().contains(namePart)
        }
    }
}
