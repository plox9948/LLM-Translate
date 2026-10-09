package com.plox.llmtranslate.domain.runtime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.RuntimeError
import com.plox.llmtranslate.domain.tokenizer.IndicProcessorLite
import com.plox.llmtranslate.domain.tokenizer.TokenizerJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Рантайм перевода на ONNX Runtime: IndicTrans2 (200M, 22 языка, вкл. ne).
 *
 * Три сессии:
 * - encoder_model.onnx — encoder;
 * - decoder_model.onnx — первый шаг декодера (без past);
 * - decoder_with_past_model.onnx — авторегрессионные шаги (с past_key_values).
 *
 * Greedy-цикл по translate.py репозитория модели. Язык задаётся flores-префиксом
 * в тексте по схеме IndicTransToolkit ("npi_Deva eng_Latn <текст>").
 * External data (*.onnx.data) ORT подхватывает по соседству файлов.
 */
@Singleton
class IndicTransOnnxRuntime @Inject constructor() : TranslationEngine {

    companion object {
        private const val MAX_NEW_TOKENS = 128
        private const val INTRA_OP_THREADS = 4
        private const val INTER_OP_THREADS = 1
        private val PAST_SUFFIXES = listOf(
            "decoder.key", "decoder.value", "encoder.key", "encoder.value"
        )
    }

    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var decoderPastSession: OrtSession? = null
    private var srcTokenizer: TokenizerJson? = null
    private var tgtTokenizer: TokenizerJson? = null

    private var numLayers: Int = 0
    private var decoderStartTokenId: Int = 2
    private var eosTokenId: Int = 2
    private var srcDictSize: Int = Int.MAX_VALUE
    private var tgtDictSize: Int = Int.MAX_VALUE
    private var loadedModelId: String? = null

    val isLoaded: Boolean
        get() = encoderSession != null && decoderSession != null && decoderPastSession != null

    /**
     * Загрузить IndicTrans-модель (3 сессии + токенизаторы).
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
                val ortEnv = OrtEnvironment.getEnvironment()
                env = ortEnv
                val dir = File(path)

                val encoderFile = findFile(dir, "encoder_model")
                val decoderFile = findFile(dir, "decoder_model")
                val decoderPastFile = findFile(dir, "decoder_with_past_model")
                val srcTokFile = findFile(dir, "tokenizer_src")
                val tgtTokFile = findFile(dir, "tokenizer_tgt")
                val genConfigFile = findFile(dir, "generation_config")
                val metaFile = findFile(dir, "tokenizer_meta")

                if (encoderFile == null || decoderFile == null || decoderPastFile == null ||
                    srcTokFile == null || tgtTokFile == null || genConfigFile == null
                ) {
                    Timber.e("Не найдены файлы IndicTrans-модели в $path")
                    return@withContext RuntimeError.LoadFailed(
                        "indictrans", "Не найдены файлы модели"
                    )
                }

                val sessionOpts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(INTRA_OP_THREADS)
                    setInterOpNumThreads(INTER_OP_THREADS)
                }
                encoderSession = ortEnv.createSession(encoderFile.absolutePath, sessionOpts)
                decoderSession = ortEnv.createSession(decoderFile.absolutePath, sessionOpts)
                decoderPastSession = ortEnv.createSession(decoderPastFile.absolutePath, sessionOpts)

                // numLayers = (число выходов декодера - 1) / 4.
                val decOutputs = decoderSession!!.outputNames.size
                numLayers = (decOutputs - 1) / 4
                Timber.d(
                    "IndicTrans: numLayers=$numLayers, " +
                        "decoder входы: ${decoderSession!!.inputNames}"
                )

                val srcTok = TokenizerJson()
                srcTok.load(srcTokFile.absolutePath)
                srcTokenizer = srcTok
                val tgtTok = TokenizerJson()
                tgtTok.load(tgtTokFile.absolutePath)
                tgtTokenizer = tgtTok

                parseGenerationConfig(genConfigFile)
                parseTokenizerMeta(metaFile)

                loadedModelId = modelInfo.id
                null
            } catch (e: Exception) {
                Timber.e(e, "Не удалось загрузить IndicTrans-модель $path")
                release()
                RuntimeError.LoadFailed("indictrans", e.message ?: "Неизвестная ошибка")
            }
        }

    /**
     * Перевести текст (IndicTrans2 encoder-decoder, greedy).
     *
     * Язык задаётся flores-префиксом в тексте по схеме IndicTransToolkit:
     * строится prompt "srcLang tgtLang <текст>", нормализуется (NFC) и
     * токенизируется целиком одним вызовом.
     */
    override suspend fun translate(text: String, from: String, to: String): Result<String> =
        withContext(Dispatchers.IO) {
            val ortEnv = env
            val enc = encoderSession
            val dec = decoderSession
            val decPast = decoderPastSession
            val srcTok = srcTokenizer
            val tgtTok = tgtTokenizer
            if (ortEnv == null || enc == null || dec == null || decPast == null ||
                srcTok == null || tgtTok == null
            ) {
                return@withContext Result.failure(RuntimeError.ModelNotSelected)
            }

            try {
                // Flores-коды языков (по схеме IndicTransToolkit).
                val srcLang = if (from == "ne") IndicProcessorLite.NEPALI else IndicProcessorLite.ENGLISH
                val tgtLang = if (to == "ne") IndicProcessorLite.NEPALI else IndicProcessorLite.ENGLISH

                // Языковой префикс вставляется в текст до токенизации.
                val prompt = IndicProcessorLite.preprocess(text, srcLang, tgtLang)
                val inputIds = srcTok.encode(prompt)
                    .map { clampId(it, srcDictSize).toLong() }
                    .toLongArray()
                if (inputIds.isEmpty()) {
                    return@withContext Result.failure(
                        RuntimeError.InferenceFailed("indictrans", "Пустой вход")
                    )
                }
                val attentionMask = LongArray(inputIds.size) { 1L }

                // Encoder.
                val encInputIds = OnnxTensor.createTensor(
                    ortEnv, LongBuffer.wrap(inputIds), longArrayOf(1L, inputIds.size.toLong())
                )
                val encAttnMask = OnnxTensor.createTensor(
                    ortEnv, LongBuffer.wrap(attentionMask), longArrayOf(1L, attentionMask.size.toLong())
                )
                val encOutput = enc.run(
                    mapOf("input_ids" to encInputIds, "attention_mask" to encAttnMask)
                )
                val encHiddenState = ortTensor(encOutput, "last_hidden_state")
                    ?: run {
                        encOutput.close(); encInputIds.close(); encAttnMask.close()
                        return@withContext Result.failure(
                            RuntimeError.InferenceFailed("indictrans", "Нет last_hidden_state")
                        )
                    }
                encInputIds.close()
                encAttnMask.close()

                // Greedy-генерация.
                val generatedIds = mutableListOf(decoderStartTokenId)
                var pastOutputs: OrtSession.Result? = null
                var lastDecInput: OnnxTensor? = null
                var lastEncMask: OnnxTensor? = null

                for (step in 0 until MAX_NEW_TOKENS) {
                    lastDecInput?.close()
                    lastEncMask?.close()

                    val decInputIds = OnnxTensor.createTensor(
                        ortEnv,
                        LongBuffer.wrap(longArrayOf(generatedIds.last().toLong())),
                        longArrayOf(1L, 1L)
                    )
                    lastDecInput = decInputIds

                    val decOutput = if (step == 0) {
                        val encMaskTensor = OnnxTensor.createTensor(
                            ortEnv, LongBuffer.wrap(attentionMask),
                            longArrayOf(1L, attentionMask.size.toLong())
                        )
                        lastEncMask = encMaskTensor
                        dec.run(
                            mapOf(
                                "input_ids" to decInputIds,
                                "encoder_hidden_states" to encHiddenState,
                                "encoder_attention_mask" to encMaskTensor
                            )
                        )
                    } else {
                        val past = pastOutputs
                            ?: return@withContext Result.failure(
                                RuntimeError.InferenceFailed("indictrans", "Нет past_key_values")
                            )
                        val encMaskTensor = OnnxTensor.createTensor(
                            ortEnv, LongBuffer.wrap(attentionMask),
                            longArrayOf(1L, attentionMask.size.toLong())
                        )
                        lastEncMask = encMaskTensor
                        val inputs = HashMap<String, OnnxTensorLike>()
                        inputs["input_ids"] = decInputIds
                        inputs["encoder_attention_mask"] = encMaskTensor
                        for (i in 0 until numLayers) {
                            for (suffix in PAST_SUFFIXES) {
                                val tensor = ortTensor(past, "present.$i.$suffix")
                                    ?: continue
                                inputs["past_key_values.$i.$suffix"] = tensor
                            }
                        }
                        decPast.run(inputs)
                    }

                    // Encoder больше не нужен после первого шага.
                    if (step == 0) encOutput.close()

                    // Argmax по logits.
                    val logitsTensor = ortTensor(decOutput, "logits")
                    if (logitsTensor == null) {
                        decOutput.close()
                        return@withContext Result.failure(
                            RuntimeError.InferenceFailed("indictrans", "Нет logits")
                        )
                    }
                    @Suppress("UNCHECKED_CAST")
                    val logitsData = logitsTensor.getValue() as Array<Array<FloatArray>>
                    val lastLogits = logitsData[0][logitsData[0].size - 1]
                    var maxToken = 0
                    var maxVal = Float.NEGATIVE_INFINITY
                    for (i in lastLogits.indices) {
                        if (lastLogits[i] > maxVal) {
                            maxVal = lastLogits[i]
                            maxToken = i
                        }
                    }

                    generatedIds.add(maxToken)
                    pastOutputs?.close()
                    pastOutputs = decOutput

                    if (maxToken == eosTokenId) break
                }

                // Cleanup.
                lastDecInput?.close()
                lastEncMask?.close()
                pastOutputs?.close()

                // Декодирование (skip-специальные).
                val safeIds = generatedIds.map { clampId(it, tgtDictSize) }
                val resultText = tgtTok.decode(safeIds)

                if (resultText.isBlank()) {
                    Result.failure(RuntimeError.InferenceFailed("indictrans", "Пустой перевод"))
                } else {
                    Result.success(resultText)
                }
            } catch (e: Exception) {
                Timber.e(e, "Ошибка инференса IndicTrans")
                Result.failure(RuntimeError.InferenceFailed("indictrans", e.message ?: "Неизвестная ошибка"))
            }
        }

    /**
     * Разгрузить модель.
     */
    override fun release() {
        try {
            encoderSession?.close()
            decoderSession?.close()
            decoderPastSession?.close()
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при разгрузке IndicTrans")
        }
        encoderSession = null
        decoderSession = null
        decoderPastSession = null
        srcTokenizer = null
        tgtTokenizer = null
        loadedModelId = null
    }

    private fun parseGenerationConfig(file: File) {
        try {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            decoderStartTokenId = (root["decoder_start_token_id"] as? JsonPrimitive)?.intOrNull ?: 2
            eosTokenId = (root["eos_token_id"] as? JsonPrimitive)?.intOrNull ?: 2
        } catch (e: Exception) {
            Timber.w("Не удалось прочитать generation_config.json: ${e.message}")
        }
    }

    private fun parseTokenizerMeta(file: File?) {
        if (file == null) return
        try {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            srcDictSize = (root["src_dict_size"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE
            tgtDictSize = (root["tgt_dict_size"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE
        } catch (e: Exception) {
            Timber.w("Не удалось прочитать tokenizer_meta.json: ${e.message}")
        }
    }

    private fun clampId(id: Int, dictSize: Int): Int =
        if (id < dictSize) id else 0

    /**
     * Найти файл модели по части имени.
     *
     * Приоритет у точного совпадения `<namePart>.onnx` (без учёта регистра):
     * иначе listFiles() может отдать *.onnx.data (external data весов) вместо
     * модели, и ORT упадёт с ORT_INVALID_PROTOBUF. Для токенизаторов и конфигов
     * (без .onnx) срабатывает fallback по подстроке.
     */
    internal fun findFile(dir: File, namePart: String): File? {
        if (!dir.exists()) return null
        val files = dir.listFiles() ?: return null
        val exact = files.firstOrNull {
            it.isFile && it.name.lowercase() == "${namePart.lowercase()}.onnx"
        }
        if (exact != null) return exact
        return files.firstOrNull {
            it.isFile && it.name.lowercase().contains(namePart.lowercase())
        }
    }
}
