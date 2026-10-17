package com.plox.llmtranslate.domain.runtime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.plox.llmtranslate.domain.ModelRetentionProvider
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.RuntimeError
import com.plox.llmtranslate.domain.tokenizer.TokenizerJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
class MarianOnnxRuntime @Inject constructor(
    private val retentionProvider: ModelRetentionProvider
) : TranslationEngine {

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

        /** Ёмкость LRU-кэша: два направления перевода + активная модель. */
        private const val MODEL_CACHE_CAPACITY = 3
    }

    /**
     * Пучок Marian-модели: сессии ORT, токенизатор и параметры генерации
     * одного направления. Освобождается [release].
     */
    private class ModelBundle(
        val encoderSession: OrtSession,
        val decoderSession: OrtSession,
        val decoderPastSession: OrtSession,
        val tokenizer: TokenizerJson,
        val numLayers: Int,
        val decoderStartTokenId: Int,
        val eosTokenId: Int,
        val badTokenIds: Set<Int>,
        val info: LoadedModelInfo
    ) {
        /** Закрыть нативные сессии пучка (ошибки — только в лог). */
        fun release() {
            try {
                encoderSession.close()
                decoderSession.close()
                decoderPastSession.close()
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при разгрузке Marian")
            }
        }
    }

    /**
     * Защита нативных сессий: инференс, загрузка и разгрузка сериализуются,
     * поэтому release() ждёт окончания активного инференса (use-after-free ORT
     * исключён). Инференс может идти десятки секунд — для onStop-разгрузки
     * это приемлемо, release() вызывается вне главного потока.
     * Вся работа с [cache] и [activeId] — только под этим мьютексом.
     */
    private val sessionMutex = Mutex()

    /** LRU-кэш загруженных пучков моделей (issue #36). */
    private val cache = EngineModelCache<ModelBundle>(MODEL_CACHE_CAPACITY)

    /**
     * Id пучка, на котором работает [translate]; пишется и читается только
     * под [sessionMutex].
     */
    private var activeId: String? = null

    private val _loadedModels = MutableStateFlow<List<LoadedModelInfo>>(emptyList())

    /** Состояние загруженных моделей; пишется только под [sessionMutex]. */
    override val loadedModels: StateFlow<List<LoadedModelInfo>> = _loadedModels

    /**
     * Загрузить Marian-модель (3 графа + токенизатор + generation config),
     * поместив пучок в LRU-кэш. При настройке keep пучки других направлений
     * сохраняются, при unload — движок держит ТОЛЬКО текущую модель: чужие
     * пучки выгружаются и перед загрузкой, и при попадании в кэш
     * (прежнее поведение).
     */
    override suspend fun ensureLoaded(modelInfo: InstalledModel): RuntimeError? =
        withContext(Dispatchers.IO) {
            val path = modelInfo.filePath
            if (path.isNullOrBlank()) return@withContext RuntimeError.ModelNotReady

            sessionMutex.withLock {
                val cached = cache.get(modelInfo.id)
                if (cached != null) {
                    // Быстрый путь под мьютексом: пучок уже в кэше — делать
                    // активным без повторной загрузки (смена направления, issue #36).
                    // Политика применяется и здесь: в unload-режиме движок
                    // обязан держать только текущую модель.
                    if (!retentionProvider.keepModelsOnDirectionChange()) {
                        unloadOtherBundlesUnlocked(modelInfo.id)
                    }
                    activeId = modelInfo.id
                    publishLoadedUnlocked()
                    return@withContext null
                }

                // Политика выгрузки при смене направления читается на каждую
                // загрузку — смена настройки применяется сразу.
                if (!retentionProvider.keepModelsOnDirectionChange()) {
                    releaseUnlocked()
                }

                var encoder: OrtSession? = null
                var decoder: OrtSession? = null
                var decoderPast: OrtSession? = null
                try {
                    val ortEnv = OrtEnvironment.getEnvironment()
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
                    // SessionOptions нужны только на создание сессий — закрываем
                    // нативный ресурс после всех createSession, в том числе при
                    // исключении на любом из них (иначе утечка native-памяти ORT).
                    try {
                        // Границы отмены: само createSession (десятки секунд)
                        // не прерываемо, но между ними отмена обязана срабатывать,
                        // иначе «Отмена» не действует на загрузку модели.
                        currentCoroutineContext().ensureActive()
                        encoder = ortEnv.createSession(encoderFile.absolutePath, sessionOpts)
                        currentCoroutineContext().ensureActive()
                        decoder = ortEnv.createSession(decoderFile.absolutePath, sessionOpts)
                        currentCoroutineContext().ensureActive()
                        decoderPast = ortEnv.createSession(decoderPastFile.absolutePath, sessionOpts)
                    } finally {
                        sessionOpts.close()
                    }

                    // numLayers = (число выходов декодера - 1) / 4
                    // (выходы: logits + present.{layer}.{decoder,encoder}.{key,value}).
                    val numLayers = (decoder.outputNames.size - 1) / 4
                    Timber.d("Marian: numLayers=$numLayers")

                    val tok = TokenizerJson()
                    tok.load(tokenizerFile.absolutePath)
                    if (!tok.isLoaded) {
                        // Без проверки токенizer молча деградировал: encode()
                        // возвращал пустой список и инференс падал с
                        // «Пустой ввод» вместо явной ошибки загрузки.
                        Timber.e("Marian: не удалось загрузить tokenizer.json в $path")
                        closeCreated(encoder, decoder, decoderPast)
                        return@withContext RuntimeError.LoadFailed(
                            "marian", "Не удалось загрузить tokenizer"
                        )
                    }

                    // Парсинг generation config читает файл — граница отмены
                    // после токенизатора.
                    currentCoroutineContext().ensureActive()
                    val gen = parseGenerationConfig(dir.resolve("generation_config.json"))

                    cache.put(
                        modelInfo.id,
                        ModelBundle(
                            encoderSession = encoder,
                            decoderSession = decoder,
                            decoderPastSession = decoderPast,
                            tokenizer = tok,
                            numLayers = numLayers,
                            decoderStartTokenId = gen.decoderStartTokenId,
                            eosTokenId = gen.eosTokenId,
                            badTokenIds = gen.badTokenIds,
                            info = LoadedModelInfo(
                                modelId = modelInfo.id,
                                displayName = modelInfo.displayName,
                                category = ModelCategory.TRANSLATION,
                                ramRequiredMb = modelInfo.ramRequiredMb
                            )
                        )
                    )
                    activeId = modelInfo.id
                    cache.evictOverCapacity().forEach { (evictedId, bundle) ->
                        Timber.d("Marian: вытеснена из кэша модель $evictedId")
                        bundle.release()
                    }
                    publishLoadedUnlocked()
                    null
                } catch (e: CancellationException) {
                    // Отмена загрузки кнопкой «Отмена» — не ошибка: освобождаем
                    // уже созданные сессии этого пучка (пучки других направлений
                    // остаются в кэше) и всплываем выше, не конвертируя отмену
                    // в LoadFailed.
                    Timber.d("Загрузка Marian-модели отменена: $path")
                    closeCreated(encoder, decoder, decoderPast)
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Не удалось загрузить Marian-модель $path")
                    closeCreated(encoder, decoder, decoderPast)
                    RuntimeError.LoadFailed("marian", e.message ?: "Неизвестная ошибка")
                }
            }
        }

    /**
     * Перевести текст (Marian encoder-decoder, greedy + repetition penalty).
     * Инференс идёт по активному пучку кэша — [ensureLoaded] вызывают перед
     * переводом, поэтому активный пучок соответствует модели направления;
     * при отсутствии активного — [RuntimeError.ModelNotSelected].
     */
    override suspend fun translate(text: String, from: String, to: String): Result<String> =
        withContext(Dispatchers.IO) {
            sessionMutex.withLock {
                val bundle = activeId?.let { cache.get(it) }
                if (bundle == null) {
                    return@withContext Result.failure(RuntimeError.ModelNotSelected)
                }
                val ortEnv = OrtEnvironment.getEnvironment()
                val enc = bundle.encoderSession
                val dec = bundle.decoderSession
                val decPast = bundle.decoderPastSession
                val tok = bundle.tokenizer
                val eos = bundle.eosTokenId

                try {
                    // Вход — как HF tokenizer(text): куски + EOS в конце.
                    val textIds = tok.encode(text)
                    if (textIds.isEmpty()) {
                        return@withContext Result.failure(
                            RuntimeError.InferenceFailed("marian", "Пустой ввод")
                        )
                    }
                    val inputIds = (textIds + eos).map { it.toLong() }.toLongArray()
                    val attentionMask = LongArray(inputIds.size) { 1L }

                    // Encoder. Входные тензоры закрываем сразу после run — на любом пути.
                    // Если второй createTensor упадёт — первый закрываем сразу,
                    // иначе его нативная память утечёт мимо внешнего finally.
                    val encInputIds = OnnxTensor.createTensor(
                        ortEnv, LongBuffer.wrap(inputIds), longArrayOf(1L, inputIds.size.toLong())
                    )
                    val encAttnMask = try {
                        OnnxTensor.createTensor(
                            ortEnv, LongBuffer.wrap(attentionMask),
                            longArrayOf(1L, attentionMask.size.toLong())
                        )
                    } catch (e: Exception) {
                        encInputIds.close()
                        throw e
                    }
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
                            startTokenId = bundle.decoderStartTokenId,
                            maxNewTokens = MAX_NEW_TOKENS,
                            eosTokenId = eos,
                            pickToken = { result, generated -> pickToken(bundle, result, generated) }
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
                                for (i in 0 until bundle.numLayers) {
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
                } catch (e: CancellationException) {
                    // Отмена инференса (кнопка «Отмена»/смена экрана) — не ошибка:
                    // всплываем выше, чтобы UI различал отмену и сбой.
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Ошибка инференса Marian")
                    Result.failure(RuntimeError.InferenceFailed("marian", e.message ?: "Неизвестная ошибка"))
                }
            }
        }

    /**
     * Выбрать следующий токен: argmax по logits последнего шага с учётом
     * bad-слов из generation config пучка и повторного штрафа (аналог HF
     * `repetition_penalty`). Маска — чистая функция [applyGenerationMask],
     * вынесена для unit-тестов.
     */
    private fun pickToken(
        bundle: ModelBundle,
        result: OrtSession.Result,
        generated: List<Int>
    ): Int {
        val last = logitsLastPosition(result)
        applyGenerationMask(last, generated, bundle.badTokenIds, REPETITION_PENALTY)
        return argMax(last)
    }

    /**
     * Разгрузить ВСЕ удерживаемые движком модели. Ждёт окончания активного
     * инференса ([sessionMutex]).
     */
    override suspend fun release() {
        sessionMutex.withLock { releaseUnlocked() }
    }

    /**
     * Извлечь и освободить пучок модели [modelId] (удаление модели, issue #40).
     * Ждёт окончания активного инференса под [sessionMutex]; если пучок был
     * активным — сбрасывает [activeId] и публикует новый список
     * [loadedModels]. Отсутствующий в кэше id — no-op.
     */
    override suspend fun releaseModel(modelId: String) {
        sessionMutex.withLock {
            cache.remove(modelId)?.let { bundle ->
                bundle.release()
                Timber.d("Marian: пучок удалённой модели $modelId освобождён")
            }
            if (activeId == modelId) {
                activeId = null
            }
            publishLoadedUnlocked()
        }
    }

    /**
     * Непосредственная разгрузка всех пучков. Вызывать только под [sessionMutex],
     * чтобы не пересекаться с активным инференсом.
     */
    private fun releaseUnlocked() {
        cache.clear().forEach { (_, bundle) -> bundle.release() }
        activeId = null
        _loadedModels.value = emptyList()
    }

    /**
     * Выгрузить все пучки кэша, кроме [keepId] (unload-режим при попадании
     * в кэш — движок держит только текущую модель). Вызывать только под
     * [sessionMutex].
     */
    private fun unloadOtherBundlesUnlocked(keepId: String) {
        cache.keysNotIn(setOf(keepId)).forEach { staleId ->
            cache.remove(staleId)?.release()
            Timber.d("Marian: выгружена модель другого направления $staleId")
        }
    }

    /**
     * Опубликовать список загруженных моделей (активная — первой).
     * Вызывать только под [sessionMutex].
     */
    private fun publishLoadedUnlocked() {
        val active = activeId
        _loadedModels.value = cache.all()
            .sortedByDescending { (id, _) -> id == active }
            .map { (_, bundle) -> bundle.info }
    }

    /**
     * Закрыть сессии незавершённой загрузки (отмена/ошибка) — пучок в кэш
     * ещё не положен, другие пучки кэша не затрагиваются.
     */
    private fun closeCreated(vararg sessions: OrtSession?) {
        try {
            sessions.filterNotNull().forEach { it.close() }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при освобождении частично созданных Marian-сессий")
        }
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

    /** Параметры генерации из generation_config.json (значения одного пучка). */
    private data class GenerationParams(
        val decoderStartTokenId: Int,
        val eosTokenId: Int,
        val badTokenIds: Set<Int>
    )

    /**
     * Прочитать параметры генерации из generation_config.json модели.
     */
    private fun parseGenerationConfig(file: File): GenerationParams {
        if (!file.isFile) {
            Timber.w("generation_config.json не найден, defaults: start=0, eos=0")
            return GenerationParams(decoderStartTokenId = 0, eosTokenId = 0, badTokenIds = emptySet())
        }
        return try {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val start = (root["decoder_start_token_id"] as? JsonPrimitive)?.intOrNull ?: 0
            val eos = (root["eos_token_id"] as? JsonPrimitive)?.intOrNull ?: 0
            val bad = mutableSetOf<Int>()
            (root["bad_words_ids"] as? JsonArray)?.forEach { w ->
                w.jsonArray.forEach { t ->
                    t.jsonPrimitive.intOrNull?.let { bad.add(it) }
                }
            }
            Timber.d(
                "Marian gen-config: start=$start, eos=$eos, bad=${bad.toList()}"
            )
            GenerationParams(
                decoderStartTokenId = start,
                eosTokenId = eos,
                badTokenIds = bad
            )
        } catch (e: Exception) {
            Timber.w("Не удалось прочитать generation_config.json: ${e.message}")
            GenerationParams(decoderStartTokenId = 0, eosTokenId = 0, badTokenIds = emptySet())
        }
    }
}
