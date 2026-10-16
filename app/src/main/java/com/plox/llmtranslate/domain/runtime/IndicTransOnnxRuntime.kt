package com.plox.llmtranslate.domain.runtime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.plox.llmtranslate.domain.ModelRetentionProvider
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.RuntimeError
import com.plox.llmtranslate.domain.tokenizer.IndicProcessorLite
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
 *
 * Модели направлений хранятся LRU-кэшем пучков (issue #36): при настройке
 * keep смена направления не выгружает модель старого направления —
 * повторная загрузка не выполняется (быстрый путь [ensureLoaded]).
 */
@Singleton
class IndicTransOnnxRuntime @Inject constructor(
    private val retentionProvider: ModelRetentionProvider
) : TranslationEngine {

    companion object {
        private const val MAX_NEW_TOKENS = 128
        private const val INTRA_OP_THREADS = 4
        private const val INTER_OP_THREADS = 1
        private val PAST_SUFFIXES = listOf(
            "decoder.key", "decoder.value", "encoder.key", "encoder.value"
        )

        /** Ёмкость LRU-кэша: два направления перевода + активная модель. */
        private const val MODEL_CACHE_CAPACITY = 3
    }

    /**
     * Пучок IndicTrans-модели: сессии ORT, токенизаторы и параметры генерации
     * одного направления. Все поля — finals, освобождаются [release].
     */
    private class ModelBundle(
        val encoderSession: OrtSession,
        val decoderSession: OrtSession,
        val decoderPastSession: OrtSession,
        val srcTokenizer: TokenizerJson,
        val tgtTokenizer: TokenizerJson,
        val numLayers: Int,
        val decoderStartTokenId: Int,
        val eosTokenId: Int,
        val srcDictSize: Int,
        val tgtDictSize: Int,
        val info: LoadedModelInfo
    ) {
        /** Закрыть нативные сессии пучка (ошибки — только в лог). */
        fun release() {
            try {
                encoderSession.close()
                decoderSession.close()
                decoderPastSession.close()
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при разгрузке IndicTrans")
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
     * Id пучка, на котором работает [translate]; пишется только под [sessionMutex],
     * читается и вне него ([isLoaded]) — отсюда @Volatile для корректной видимости.
     */
    @Volatile
    private var activeId: String? = null

    private val _loadedModels = MutableStateFlow<List<LoadedModelInfo>>(emptyList())

    /** Состояние загруженных моделей; пишется только под [sessionMutex]. */
    override val loadedModels: StateFlow<List<LoadedModelInfo>> = _loadedModels

    val isLoaded: Boolean
        get() = activeId != null

    /**
     * Загрузить IndicTrans-модель (3 сессии + токенизаторы), поместив пучок
     * в LRU-кэш. При настройке keep пучки других направлений сохраняются
     * (сверх ёмкости вытесняются наименее давно используемые), при unload —
     * движок держит ТОЛЬКО текущую модель: чужие пучки выгружаются и перед
     * загрузкой, и при попадании в кэш (прежнее поведение).
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

                    // numLayers = (число выходов декодера - 1) / 4.
                    val numLayers = (decoder.outputNames.size - 1) / 4
                    Timber.d(
                        "IndicTrans: numLayers=$numLayers, " +
                            "decoder входы: ${decoder.inputNames}"
                    )

                    val srcTok = TokenizerJson()
                    srcTok.load(srcTokFile.absolutePath)
                    val tgtTok = TokenizerJson()
                    tgtTok.load(tgtTokFile.absolutePath)
                    if (!srcTok.isLoaded || !tgtTok.isLoaded) {
                        // Без проверки токенизаторы молча деградировали:
                        // encode() возвращал пустой список и инференс падал
                        // с «Пустой вход» вместо явной ошибки загрузки.
                        Timber.e("IndicTrans: не удалось загрузить токенизаторы в $path")
                        closeCreated(encoder, decoder, decoderPast)
                        return@withContext RuntimeError.LoadFailed(
                            "indictrans", "Не удалось загрузить токенизаторы"
                        )
                    }

                    // Граница отмены после парсинга обоих токенизаторов (МБ JSON),
                    // перед чтением конфигов.
                    currentCoroutineContext().ensureActive()
                    val gen = parseGenerationConfig(genConfigFile)
                    val dict = parseTokenizerMeta(metaFile)

                    cache.put(
                        modelInfo.id,
                        ModelBundle(
                            encoderSession = encoder,
                            decoderSession = decoder,
                            decoderPastSession = decoderPast,
                            srcTokenizer = srcTok,
                            tgtTokenizer = tgtTok,
                            numLayers = numLayers,
                            decoderStartTokenId = gen.decoderStartTokenId,
                            eosTokenId = gen.eosTokenId,
                            srcDictSize = dict.srcDictSize,
                            tgtDictSize = dict.tgtDictSize,
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
                        Timber.d("IndicTrans: вытеснена из кэша модель $evictedId")
                        bundle.release()
                    }
                    publishLoadedUnlocked()
                    null
                } catch (e: CancellationException) {
                    // Отмена загрузки кнопкой «Отмена» — не ошибка: освобождаем
                    // уже созданные сессии этого пучка (пучки других направлений
                    // остаются в кэше) и всплываем выше, не конвертируя отмену
                    // в LoadFailed.
                    Timber.d("Загрузка IndicTrans-модели отменена: $path")
                    closeCreated(encoder, decoder, decoderPast)
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Не удалось загрузить IndicTrans-модель $path")
                    closeCreated(encoder, decoder, decoderPast)
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
                val srcTok = bundle.srcTokenizer
                val tgtTok = bundle.tgtTokenizer

                try {
                    // Flores-коды языков (по схеме IndicTransToolkit).
                    // Неизвестный язык подменяется английским — подмена явна в логе,
                    // чтобы тихая подстановка не выглядела как корректный перевод.
                    val srcLang = when (from) {
                        "ne" -> IndicProcessorLite.NEPALI
                        "en" -> IndicProcessorLite.ENGLISH
                        else -> {
                            Timber.w("IndicTrans: неизвестный язык источника \"$from\" — подмена на английский")
                            IndicProcessorLite.ENGLISH
                        }
                    }
                    val tgtLang = when (to) {
                        "ne" -> IndicProcessorLite.NEPALI
                        "en" -> IndicProcessorLite.ENGLISH
                        else -> {
                            Timber.w("IndicTrans: неизвестный язык цели \"$to\" — подмена на английский")
                            IndicProcessorLite.ENGLISH
                        }
                    }

                    // Языковой префикс вставляется в текст до токенизации.
                    val prompt = IndicProcessorLite.preprocess(text, srcLang, tgtLang)
                    val inputIds = srcTok.encode(prompt)
                        .map { clampId(it, bundle.srcDictSize).toLong() }
                        .toLongArray()
                    if (inputIds.isEmpty()) {
                        return@withContext Result.failure(
                            RuntimeError.InferenceFailed("indictrans", "Пустой вход")
                        )
                    }
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
                            startTokenId = bundle.decoderStartTokenId,
                            maxNewTokens = MAX_NEW_TOKENS,
                            eosTokenId = bundle.eosTokenId,
                            initialIds = listOf(bundle.decoderStartTokenId),
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
                                for (i in 0 until bundle.numLayers) {
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
                        val rawText = tgtTok.decode(
                            generatedIds.map { clampId(it, bundle.tgtDictSize) }
                        )
                        Timber.d(
                            "IndicTrans: сгенерировано ${generatedIds.size} токенов, " +
                                "сырой вывод: «$rawText»"
                        )
                        // Дедупликация полного повтора фразы декодером (issue #28):
                        // post-processing без штрафов в логитах — penalty менял
                        // бы качество IndicTrans2-переводов (у Marian свой penalty).
                        val trimmed = RepetitionTrimmer.trim(rawText)
                        if (trimmed != rawText) {
                            Timber.d("IndicTrans: схлопнут повтор: «$rawText» => «$trimmed»")
                        }
                        resultText = trimmed
                    } finally {
                        encOutput.close()
                    }

                    if (resultText.isBlank()) {
                        Result.failure(RuntimeError.InferenceFailed("indictrans", "Пустой перевод"))
                    } else {
                        Result.success(resultText)
                    }
                } catch (e: CancellationException) {
                    // Отмена инференса (кнопка «Отмена»/смена экрана) — не ошибка:
                    // всплываем выше, чтобы UI различал отмену и сбой.
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Ошибка инференса IndicTrans")
                    Result.failure(RuntimeError.InferenceFailed("indictrans", e.message ?: "Неизвестная ошибка"))
                }
            }
        }

    /**
     * Разгрузить ВСЕ удерживаемые движком модели. Ждёт окончания активного
     * инференса (sessionMutex).
     */
    override suspend fun release() {
        sessionMutex.withLock { releaseUnlocked() }
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
            Timber.d("IndicTrans: выгружена модель другого направления $staleId")
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
            Timber.e(e, "Ошибка при освобождении частично созданных IndicTrans-сессий")
        }
    }

    /** Параметры генерации из generation_config.json. */
    private data class GenerationParams(
        val decoderStartTokenId: Int,
        val eosTokenId: Int
    )

    /** Размеры словарей токенизаторов из tokenizer_meta.json. */
    private data class DictSizes(
        val srcDictSize: Int,
        val tgtDictSize: Int
    )

    private fun parseGenerationConfig(file: File): GenerationParams {
        return try {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            GenerationParams(
                decoderStartTokenId =
                    (root["decoder_start_token_id"] as? JsonPrimitive)?.intOrNull ?: 2,
                eosTokenId = (root["eos_token_id"] as? JsonPrimitive)?.intOrNull ?: 2
            )
        } catch (e: Exception) {
            Timber.w("Не удалось прочитать generation_config.json: ${e.message}")
            GenerationParams(decoderStartTokenId = 2, eosTokenId = 2)
        }
    }

    private fun parseTokenizerMeta(file: File?): DictSizes {
        if (file == null) return DictSizes(srcDictSize = Int.MAX_VALUE, tgtDictSize = Int.MAX_VALUE)
        return try {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            DictSizes(
                srcDictSize = (root["src_dict_size"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE,
                tgtDictSize = (root["tgt_dict_size"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE
            )
        } catch (e: Exception) {
            Timber.w("Не удалось прочитать tokenizer_meta.json: ${e.message}")
            DictSizes(srcDictSize = Int.MAX_VALUE, tgtDictSize = Int.MAX_VALUE)
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
