package com.plox.llmtranslate.domain.runtime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.RuntimeError
import com.plox.llmtranslate.domain.tokenizer.TokenizerJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.io.File
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Рантайм перевода на ONNX Runtime: Marian MT (Helsinki-NLP opus-mt_tiny).
 *
 * Модель — стандартный экспорт Optimum (задача text2text-generation-with-past),
 * квантованный динамически в int8 (per-channel):
 * - encoder_model.onnx — энкодер;
 * - decoder_model.onnx — первый шаг декодера (без past);
 * - decoder_with_past_model.onnx — авторегрессионные шаги.
 *
 * Генерация greedy с параметрами из generation_config.json
 * (decoder_start_token_id, eos_token_id, bad_words_ids) плюс
 * repetition_penalty=1.2 — стандартный параметр HF `generate`,
 * подавляющий зацикливание крошечных Marian-моделей на коротких вводах.
 * Токенизатор — единый (общий словарь source/target у opus-mt_tiny),
 * вход дополняется EOS-токеном, как в HF `tokenizer(text)`.
 */
@Singleton
class MarianOnnxRuntime @Inject constructor() : TranslationEngine {

    companion object {
        // Лимит генерации на одно предложение: текст разбивается по предложениям
        // до инференса (SentenceSplitter в ViewModel), 64 токена на крошечных
        // моделях обрезал длинные одиночные предложения.
        private const val MAX_NEW_TOKENS = 128
        private const val INTRA_OP_THREADS = 4
        private const val INTER_OP_THREADS = 1

        /**
         * Штраф повторения (аналог `repetition_penalty` в HF generate):
         * без него opus-mt_tiny на коротких вводах («да», «yes») зацикливается
         * даже в fp32 на сервере (ограничение модели). 1.2 убивает цикл и
         * не меняет выводы на нормальных предложениях (проверено на battery).
         */
        private const val REPETITION_PENALTY = 1.2f
    }

    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var decoderPastSession: OrtSession? = null
    private var tokenizer: TokenizerJson? = null

    private var numLayers: Int = 0
    private var decoderStartTokenId: Int = 0
    private var eosTokenId: Int = 0
    private var badTokenIds: Set<Int> = emptySet()
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
     * Загрузить Marian-модель (3 графа + токенизатор + generation config).
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

                    val encoderFile = dir.resolve("encoder_model.onnx")
                    val decoderFile = dir.resolve("decoder_model.onnx")
                    val decoderPastFile = dir.resolve("decoder_with_past_model.onnx")
                    val tokenizerFile = dir.resolve("tokenizer.json")

                    if (!encoderFile.isFile || !decoderFile.isFile || !decoderPastFile.isFile ||
                        !tokenizerFile.isFile
                    ) {
                        Timber.e("Не найдены файлы Marian-модели в $path")
                        return@withContext RuntimeError.LoadFailed(
                            "marian", "Не найдены файлы модели (нужны encoder/decoder/with_past + tokenizer)"
                        )
                    }

                    val sessionOpts = OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(INTRA_OP_THREADS)
                        setInterOpNumThreads(INTER_OP_THREADS)
                    }
                    encoderSession = ortEnv.createSession(encoderFile.absolutePath, sessionOpts)
                    decoderSession = ortEnv.createSession(decoderFile.absolutePath, sessionOpts)
                    decoderPastSession = ortEnv.createSession(decoderPastFile.absolutePath, sessionOpts)

                    // numLayers = (число выходов декодера - 1) / 4
                    // (выходы: logits + present.{layer}.{decoder,encoder}.{key,value}).
                    numLayers = (decoderSession!!.outputNames.size - 1) / 4
                    Timber.d("Marian: numLayers=$numLayers")

                    val tok = TokenizerJson()
                    tok.load(tokenizerFile.absolutePath)
                    tokenizer = tok

                    parseGenerationConfig(dir.resolve("generation_config.json"))

                    loadedModelId = modelInfo.id
                    null
                } catch (e: Exception) {
                    Timber.e(e, "Не удалось загрузить Marian-модель $path")
                    releaseUnlocked()
                    RuntimeError.LoadFailed("marian", e.message ?: "Неизвестная ошибка")
                }
            }
        }

    /**
     * Перевести текст (Marian encoder-decoder, greedy + repetition penalty).
     */
    override suspend fun translate(text: String, from: String, to: String): Result<String> =
        withContext(Dispatchers.IO) {
            sessionMutex.withLock {
                val ortEnv = env
                val enc = encoderSession
                val dec = decoderSession
                val decPast = decoderPastSession
                val tok = tokenizer
                if (ortEnv == null || enc == null || dec == null || decPast == null || tok == null) {
                    return@withContext Result.failure(RuntimeError.ModelNotSelected)
                }

                try {
                    // Вход — как HF tokenizer(text): куски + EOS в конце.
                    val textIds = tok.encode(text)
                    if (textIds.isEmpty()) {
                        return@withContext Result.failure(
                            RuntimeError.InferenceFailed("marian", "Пустой ввод")
                        )
                    }
                    val eos = eosTokenId
                    val inputIds = (textIds + eos).map { it.toLong() }.toLongArray()
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
                        enc.run(
                            mapOf("input_ids" to encInputIds, "attention_mask" to encAttnMask)
                        )
                    } finally {
                        encInputIds.close()
                        encAttnMask.close()
                    }

                    // Все нативные ресурсы greedy-цикла закрывает драйвер
                    // greedyDecode — в т.ч. при раннем выходе и при исключении.
                    try {
                        val encHiddenState = ortTensor(encOutput, "last_hidden_state")
                            ?: return@withContext Result.failure(
                                RuntimeError.InferenceFailed("marian", "Нет last_hidden_state")
                            )

                        // Greedy-генерация. Encoder-past неизменен — берём его из
                        // результата первого шага, decoder-past — из предыдущего.
                        val generatedIds = greedyDecode(
                            ortEnv = ortEnv,
                            attentionMask = attentionMask,
                            startTokenId = decoderStartTokenId,
                            maxNewTokens = MAX_NEW_TOKENS,
                            eosTokenId = eos,
                            pickToken = ::pickToken
                        ) { step, decInputIds, decAttnMask, first, prev ->
                            if (step == 0) {
                                dec.run(
                                    mapOf(
                                        "input_ids" to decInputIds,
                                        "encoder_hidden_states" to encHiddenState,
                                        "encoder_attention_mask" to decAttnMask
                                    )
                                )
                            } else {
                                val inputs = HashMap<String, OnnxTensorLike>()
                                inputs["input_ids"] = decInputIds
                                inputs["encoder_attention_mask"] = decAttnMask
                                // Results уже существующих шагов — отсутствие значит
                                // внутреннюю ошибку драйвера.
                                val s0 = first ?: error("Нет past_key_values")
                                val p = prev ?: error("Нет past_key_values")
                                for (i in 0 until numLayers) {
                                    // Encoder-past: из первого шага (не меняется).
                                    ortTensor(s0, "present.$i.encoder.key")?.let {
                                        inputs["past_key_values.$i.encoder.key"] = it
                                    }
                                    ortTensor(s0, "present.$i.encoder.value")?.let {
                                        inputs["past_key_values.$i.encoder.value"] = it
                                    }
                                    // Decoder-past: из предыдущего шага.
                                    ortTensor(p, "present.$i.decoder.key")?.let {
                                        inputs["past_key_values.$i.decoder.key"] = it
                                    }
                                    ortTensor(p, "present.$i.decoder.value")?.let {
                                        inputs["past_key_values.$i.decoder.value"] = it
                                    }
                                }
                                decPast.run(inputs)
                            }
                        }

                        // Декодирование сгенерированных id (decode пропускает спец-токены).
                        val resultText = tok.decode(generatedIds)
                        if (resultText.isBlank()) {
                            Result.failure(RuntimeError.InferenceFailed("marian", "Пустой перевод"))
                        } else {
                            Result.success(resultText)
                        }
                    } finally {
                        encOutput.close()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Ошибка инференса Marian")
                    Result.failure(RuntimeError.InferenceFailed("marian", e.message ?: "Неизвестная ошибка"))
                }
            }
        }

    /**
     * Выбрать следующий токен: argmax по logits последнего шага с учётом
     * bad-слов из generation config и повторного штрафа (аналог HF
     * `repetition_penalty`). Чистая функция — вынесена для unit-тестов.
     */
    private fun pickToken(result: OrtSession.Result, generated: List<Int>): Int {
        val last = logitsLastPosition(result)
        applyGenerationMask(last, generated, badTokenIds, REPETITION_PENALTY)
        return argMax(last)
    }

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
            Timber.e(e, "Ошибка при разгрузке Marian")
        }
        encoderSession = null
        decoderSession = null
        decoderPastSession = null
        tokenizer = null
        loadedModelId = null
    }

    /**
     * Применить к logits bad-слова и штраф повторения (формула HF).
     * @param logits вектор logits последнего шага (мутируется).
     * @param generated уже сгенерированные id.
     * @param badIds id, которые запрещены к выдаче (bad_words_ids из конфига).
     * @param penalty множитель штрафа (>1 — сильнее подавляет повторы).
     */
    internal fun applyGenerationMask(
        logits: FloatArray,
        generated: List<Int>,
        badIds: Set<Int>,
        penalty: Float
    ) {
        for (id in badIds) {
            if (id in logits.indices) logits[id] = Float.NEGATIVE_INFINITY
        }
        if (penalty <= 1.0f) return
        for (id in generated.distinct()) {
            if (id !in logits.indices) continue
            val v = logits[id]
            logits[id] = if (v > 0f) v / penalty else v * penalty
        }
    }

    /**
     * Прочитать параметры генерации из generation_config.json модели.
     */
    private fun parseGenerationConfig(file: File) {
        decoderStartTokenId = 0
        eosTokenId = 0
        badTokenIds = emptySet()
        if (!file.isFile) {
            Timber.w("generation_config.json не найден, defaults: start=0, eos=0")
            return
        }
        try {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            decoderStartTokenId =
                (root["decoder_start_token_id"] as? JsonPrimitive)?.intOrNull ?: 0
            eosTokenId =
                (root["eos_token_id"] as? JsonPrimitive)?.intOrNull ?: 0
            val bad = mutableSetOf<Int>()
            (root["bad_words_ids"] as? JsonArray)?.forEach { w ->
                w.jsonArray.forEach { t ->
                    t.jsonPrimitive.intOrNull?.let { bad.add(it) }
                }
            }
            badTokenIds = bad
            Timber.d(
                "Marian gen-config: start=$decoderStartTokenId, eos=$eosTokenId, " +
                    "bad=${bad.toList()}"
            )
        } catch (e: Exception) {
            Timber.w("Не удалось прочитать generation_config.json: ${e.message}")
        }
    }
}
