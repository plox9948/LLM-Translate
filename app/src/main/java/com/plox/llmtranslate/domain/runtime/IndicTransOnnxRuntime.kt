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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import timber.log.Timber
import java.io.File
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

    /**
     * Защита нативных сессий: инференс, загрузка и разгрузка сериализуются,
     * поэтому release() ждёт окончания активного инференса (use-after-free ORT
     * исключён). Инференс может идти десятки секунд — для onStop-разгрузки
     * это приемлемо, release() вызывается вне главного потока.
     */
    private val sessionMutex = Mutex()

    val isLoaded: Boolean
        get() = encoderSession != null && decoderSession != null && decoderPastSession != null

    /**
     * Загрузить IndicTrans-модель (3 сессии + токенизаторы).
     */
    override suspend fun ensureLoaded(modelInfo: InstalledModel): RuntimeError? =
        withContext(Dispatchers.IO) {
            val path = modelInfo.filePath
            if (path.isNullOrBlank()) return@withContext RuntimeError.ModelNotReady

            sessionMutex.withLock {
                // Быстрый путь под мьютексом: вне него поле loadedModelId могло
                // быть очищено параллельным release() — проверка дала бы ложное
                // «уже загружено» и инференс упал бы на разряженных сессиях.
                if (loadedModelId == modelInfo.id && isLoaded) {
                    return@withContext null
                }

                releaseUnlocked()

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
                    releaseUnlocked()
                    RuntimeError.LoadFailed("indictrans", e.message ?: "Неизвестная ошибка")
                }
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
            sessionMutex.withLock {
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
                    val srcLang =
                        if (from == "ne") IndicProcessorLite.NEPALI else IndicProcessorLite.ENGLISH
                    val tgtLang =
                        if (to == "ne") IndicProcessorLite.NEPALI else IndicProcessorLite.ENGLISH

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

                    // Encoder. Входные тензоры закрываем сразу после run — на любом пути.
                    val encInputIds = OnnxTensor.createTensor(
                        ortEnv, LongBuffer.wrap(inputIds), longArrayOf(1L, inputIds.size.toLong())
                    )
                    val encAttnMask = OnnxTensor.createTensor(
                        ortEnv, LongBuffer.wrap(attentionMask),
                        longArrayOf(1L, attentionMask.size.toLong())
                    )
                    val encOutput = try {
                        enc.run(mapOf("input_ids" to encInputIds, "attention_mask" to encAttnMask))
                    } finally {
                        encInputIds.close()
                        encAttnMask.close()
                    }

                    // Greedy-генерация общим драйвером. Все нативные ресурсы
                    // закрывает greedyDecode — в т.ч. при раннем выходе и при
                    // исключении. Стартовый токен входит в итоговый список,
                    // past_key_values (и encoder-, и decoder-) — из результата
                    // предыдущего шага: encoder-past неизменен между шагами.
                    val resultText: String
                    try {
                        val generatedIds = greedyDecode(
                            ortEnv = ortEnv,
                            attentionMask = attentionMask,
                            startTokenId = decoderStartTokenId,
                            maxNewTokens = MAX_NEW_TOKENS,
                            eosTokenId = eosTokenId,
                            initialIds = listOf(decoderStartTokenId),
                            pickToken = { result, _ -> argMax(logitsLastPosition(result)) }
                        ) { step, decInputIds, decAttnMask, _, prev ->
                            if (step == 0) {
                                val encHidden = ortTensor(encOutput, "last_hidden_state")
                                    ?: error("Нет last_hidden_state")
                                dec.run(
                                    mapOf(
                                        "input_ids" to decInputIds,
                                        "encoder_hidden_states" to encHidden,
                                        "encoder_attention_mask" to decAttnMask
                                    )
                                )
                            } else {
                                val past = prev ?: error("Нет past_key_values")
                                val inputs = HashMap<String, OnnxTensorLike>()
                                inputs["input_ids"] = decInputIds
                                inputs["encoder_attention_mask"] = decAttnMask
                                for (i in 0 until numLayers) {
                                    for (suffix in PAST_SUFFIXES) {
                                        ortTensor(past, "present.$i.$suffix")?.let {
                                            inputs["past_key_values.$i.$suffix"] = it
                                        }
                                    }
                                }
                                decPast.run(inputs)
                            }
                        }

                        // Декодирование (skip-специальные), id зажимаем размером словаря.
                        resultText = tgtTok.decode(generatedIds.map { clampId(it, tgtDictSize) })
                    } finally {
                        encOutput.close()
                    }

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
        }

    /**
     * Разгрузить модель. Ждёт окончания активного инференса (sessionMutex).
     */
    override suspend fun release() {
        sessionMutex.withLock { releaseUnlocked() }
    }

    /**
     * Непосредственная разгрузка сессий. Вызывать только под `sessionMutex`,
     * чтобы не пересекаться с активным инференсом.
     */
    private fun releaseUnlocked() {
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
