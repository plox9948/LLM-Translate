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
 * Токенизация — NllbTokenizer (SentencePiece BPE).
 */
@Singleton
class OnnxLlmRuntime @Inject constructor(
    private val tokenizer: NllbTokenizer
) : TranslationEngine {

    companion object {
        const val MAX_LENGTH = 256
    }

    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var loadedModelId: String? = null

    val isLoaded: Boolean
        get() = encoderSession != null && decoderSession != null

    /**
     * Загрузить ONNX-модель (encoder + decoder).
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
                Timber.d("ONNX-модель NLLB загружена: $path")
                null
            } catch (e: Exception) {
                Timber.e(e, "Не удалось загрузить ONNX-модель $path")
                release()
                RuntimeError.LoadFailed("onnx", e.message ?: "Неизвестная ошибка")
            }
        }

    /**
     * Перевести текст (NLLB encoder-decoder).
     */
    override suspend fun translate(text: String, from: String, to: String): Result<String> =
        withContext(Dispatchers.IO) {
            val encoder = encoderSession
            val decoder = decoderSession
            if (encoder == null || decoder == null) {
                return@withContext Result.failure(RuntimeError.ModelNotSelected)
            }

            try {
                tokenizer.ensureLoaded()

                val fromLangId = tokenizer.langTokenId(from)
                val toLangId = tokenizer.langTokenId(to)
                val eosId = tokenizer.eosId()
                if (fromLangId == null || toLangId == null || eosId == null) {
                    return@withContext Result.failure(
                        RuntimeError.InferenceFailed("onnx", "Не найдены языковые токены")
                    )
                }

                // 1. Токенизация: [from_lang_id, ...tokens, eos]
                val srcTokens = mutableListOf(fromLangId)
                srcTokens.addAll(tokenizer.encode(text))
                srcTokens.add(eosId)

                // 2. Инференс encoder.
                val encoderOutput = runEncoder(encoder, srcTokens)
                    ?: return@withContext Result.failure(
                        RuntimeError.InferenceFailed("onnx", "Ошибка encoder")
                    )

                // 3. Генерация decoder.
                val generated = generateDecoder(
                    decoder, toLangId, eosId, encoderOutput, srcTokens.size
                )
                if (generated.isEmpty()) {
                    return@withContext Result.failure(
                        RuntimeError.InferenceFailed("onnx", "Пустой результат")
                    )
                }

                // 4. Детокенизация.
                val result = tokenizer.decode(generated)
                if (result.isBlank()) {
                    Result.failure(
                        RuntimeError.InferenceFailed("onnx", "Пустой перевод")
                    )
                } else {
                    Result.success(result)
                }
            } catch (e: Exception) {
                Timber.e(e, "Ошибка инференса NLLB")
                Result.failure(
                    RuntimeError.InferenceFailed("onnx", e.message ?: "Неизвестная ошибка")
                )
            }
        }

    /**
     * Загрузить модель.
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
     * Инференс encoder.
     * @return hidden states [seqLen, hiddenSize]
     */
    private fun runEncoder(
        session: OrtSession,
        inputIds: List<Int>
    ): Array<FloatArray>? {
        val ortEnv = env ?: return null
        val seqLen = inputIds.size
        val inputArray = inputIds.toLongArray()

        val inputTensor = OnnxTensor.createTensor(
            ortEnv, inputArray, longArrayOf(1L, seqLen.toLong())
        )
        val attentionMask = OnnxTensor.createTensor(
            ortEnv, LongArray(seqLen) { 1L }, longArrayOf(1L, seqLen.toLong())
        )

        val inputs = mapOf(
            "input_ids" to inputTensor,
            "attention_mask" to attentionMask
        )

        val output = session.run(inputs)
        val hiddenStates = output.get(0) as OnnxTensor

        val data = hiddenStates.getValue() as Array<LongArray>
        val hiddenSize = data[0].size
        val result = Array(seqLen) { FloatArray(hiddenSize) }
        for (i in 0 until seqLen) {
            for (j in 0 until hiddenSize) {
                result[i][j] = data[i][j].toFloat()
            }
        }

        inputTensor.close()
        attentionMask.close()
        hiddenStates.close()
        output.close()
        return result
    }

    /**
     * Генерация decoder (greedy search).
     */
    private fun generateDecoder(
        session: OrtSession,
        toLangId: Int,
        eosId: Int,
        encoderOutput: Array<FloatArray>,
        srcLen: Int
    ): List<Int> {
        val ortEnv = env ?: return emptyList()
        val hiddenSize = encoderOutput[0].size

        val generated = mutableListOf(toLangId)

        for (step in 0 until MAX_LENGTH) {
            val decLen = generated.size
            val inputIds = generated.toLongArray()

            val inputTensor = OnnxTensor.createTensor(
                ortEnv, inputIds, longArrayOf(1L, decLen.toLong())
            )
            val attentionMask = OnnxTensor.createTensor(
                ortEnv, LongArray(decLen) { 1L }, longArrayOf(1L, decLen.toLong())
            )
            val encHidden = OnnxTensor.createTensor(
                ortEnv,
                encoderOutput.flatten().toFloatArray(),
                longArrayOf(1L, srcLen.toLong(), hiddenSize.toLong())
            )
            val encMask = OnnxTensor.createTensor(
                ortEnv, LongArray(srcLen) { 1L }, longArrayOf(1L, srcLen.toLong())
            )

            val inputs = mapOf(
                "input_ids" to inputTensor,
                "attention_mask" to attentionMask,
                "encoder_hidden_states" to encHidden,
                "encoder_attention_mask" to encMask
            )

            val output = session.run(inputs)

            // logits — последний элемент.
            val logitsTensor = output.get(output.size() - 1) as OnnxTensor
            val logits = logitsTensor.getValue() as Array<Array<FloatArray>>

            // Берём токен с максимальным логитом.
            val lastLogits = logits[0][decLen - 1]
            var maxToken = 0
            var maxVal = Float.NEGATIVE_INFINITY
            for (i in lastLogits.indices) {
                if (lastLogits[i] > maxVal) {
                    maxVal = lastLogits[i]
                    maxToken = i
                }
            }

            inputTensor.close()
            attentionMask.close()
            encHidden.close()
            encMask.close()
            logitsTensor.close()
            output.close()

            if (maxToken == eosId) break
            generated.add(maxToken)
        }

        return generated
    }

    private fun findModelFile(dir: File, namePart: String): File? {
        if (!dir.exists()) return null
        return dir.listFiles()?.firstOrNull {
            it.isFile && it.name.lowercase().contains(namePart) &&
                it.name.endsWith(".onnx")
        }
    }
}
