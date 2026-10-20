package com.plox.llmtranslate.domain.runtime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Optional

/**
 * Общий greedy-цикл генерации для encoder-decoder ONNX-движков перевода
 * (Marian, IndicTrans2). Различия движков вынесены в коллбэки:
 * - [runStep] — сборка входов шага (источник past_key_values у движков разный)
 *   и запуск сессии декодера;
 * - [pickToken] — выбор токена из logits (у Marian — со штрафом повторения).
 *
 * Влаждение нативными ресурсами:
 * - тензоры `input_ids`/`encoder_attention_mask` текущего шага создаются здесь
 *   и закрываются сразу после [runStep];
 * - результат первого шага держится открытым до конца цикла (у Marian из него
 *   берутся неизменные encoder-past), результат предыдущего шага закрывается
 *   при замещении новым; всё освобождается и при раннем выходе, и при
 *   исключении из коллбэков.
 *
 * Остановка — по [maxNewTokens] либо когда [pickToken] вернёт [eosTokenId]
 * (EOS включается в результат, как в HF `generate`); флаги остановки —
 * в [DecodeOutcome].
 *
 * Контракт подавления повторов (ревью #64): общий цикл генерирует строго
 * greedy — без штрафов и блокировок в логитах. Повторы декодера IndicTrans2
 * подавляет ВЫЗЫВАЮЩИЙ пост-процессингом [RepetitionTrimmer]
 * (IndicTransOnnxRuntime); штраф в логитах в общий цикл добавлять НЕЛЬЗЯ —
 * он ухудшает качество IndicTrans2. У [MarianOnnxRuntime] намеренно
 * сохранён repetition_penalty=1.2 как ЧАСТНОЕ решение против зацикливания
 * opus-mt_tiny — он реализован в его коллбэке [pickToken], а не здесь.
 *
 * @param ortEnv ONNX-окружение.
 * @param attentionMask маска входа энкодера (единицы) — подаётся как
 *        `encoder_attention_mask` на каждом шаге.
 * @param startTokenId первый входной токен декодера (decoder_start_token_id).
 * @param maxNewTokens максимум генерируемых токенов.
 * @param eosTokenId токен конца последовательности.
 * @param initialIds начальное содержимое списка генерации (у IndicTrans —
 *        стартовый токен входит в итоговый список, у Marian — нет).
 * @param pickToken выбор следующего токена по результату шага и уже
 *        сгенерированной части.
 * @param runStep сборка входов и запуск декодера: шаг, тензоры input_ids и
 *        encoder_attention_mask, результат первого шага и предыдущего шага
 *        (null до выполнения соответствующего шага).
 * Отмена: на каждой итерации цикла проверяется отмена вызывающей корутины
 * ([currentCoroutineContext] + ensureActive); при отмене нативные ресурсы
 * закрываются в finally так же, как при исключении, и
 * [kotlinx.coroutines.CancellationException] всплывает выше неглотаемой.
 *
 * @return результат цикла [DecodeOutcome]: сгенерированные id (вместе с
 *         [initialIds] и, при достижении, EOS) плюс флаги остановки.
 * @throws любое исключение из коллбэков пробрасывается вызывающему движку.
 */
internal suspend fun greedyDecode(
    ortEnv: OrtEnvironment,
    attentionMask: LongArray,
    startTokenId: Int,
    maxNewTokens: Int,
    eosTokenId: Int,
    initialIds: List<Int> = emptyList(),
    pickToken: (result: OrtSession.Result, generated: List<Int>) -> Int,
    runStep: (
        step: Int,
        inputIds: OnnxTensor,
        encoderAttentionMask: OnnxTensor,
        firstResult: OrtSession.Result?,
        prevResult: OrtSession.Result?
    ) -> OrtSession.Result
): DecodeOutcome = greedyDecodeCore(
    initialIds = initialIds,
    startTokenId = startTokenId,
    maxNewTokens = maxNewTokens,
    eosTokenId = eosTokenId,
    newStepInputs = { feedToken ->
        // Первый тензор закрываем, если создание второго упадёт:
        // иначе его нативная память утечёт (ядроPair ещё не получило).
        val inputIds = longTensor(ortEnv, longArrayOf(feedToken.toLong()))
        try {
            Pair(inputIds, longTensor(ortEnv, attentionMask))
        } catch (e: Exception) {
            inputIds.close()
            throw e
        }
    },
    pickToken = pickToken,
    runStep = runStep
)

/**
 * Результат greedy-цикла: список сгенерированных id (включая [initialIds] и,
 * при достижении, EOS) и флаги остановки:
 * - [eosReached] — pickToken вернул EOS (штатная остановка);
 * - [hitMaxLength] — цикл выполнил все maxNewTokens шагов без EOS и при этом
 *   был минимум один шаг декода без EOS — вероятное вырождение декодера
 *   (зацикливание), см. isDegenerateOutput.
 */
internal data class DecodeOutcome(
    val generated: List<Int>,
    val eosReached: Boolean,
    val hitMaxLength: Boolean
)
/**
 * Ядро greedy-цикла, не зависящее от ONNX Runtime: типы входа шага и
 * результата шага обобщены ([I]/[R]) и обязаны быть [AutoCloseable].
 * [greedyDecode] — production-обёртка с ORT-типами; юнит-тесты прогоняют
 * ядро с фейковыми ресурсами, поскольку классы ai.onnxruntime на JVM
 * инициализируют нативную библиотеку (вне Android это невозможно).
 *
 * Вся логика владения ресурсами и проверок отмены — здесь.
 *
 * Флаги результата:
 * - `eosReached` — [pickToken] вернул [eosTokenId] (штатная остановка);
 * - `hitMaxLength` — цикл выполнил все [maxNewTokens] шагов без EOS и был
 *   минимум один шаг без EOS (потенциальное вырождение декодера — см.
 *   isDegenerateOutput).
 */
internal suspend fun <I : AutoCloseable, R : AutoCloseable> greedyDecodeCore(
    initialIds: List<Int>,
    startTokenId: Int,
    maxNewTokens: Int,
    eosTokenId: Int,
    newStepInputs: (feedToken: Int) -> Pair<I, I>,
    pickToken: (result: R, generated: List<Int>) -> Int,
    runStep: (
        step: Int,
        inputIds: I,
        encoderAttentionMask: I,
        firstResult: R?,
        prevResult: R?
    ) -> R
): DecodeOutcome {
    val generated = initialIds.toMutableList()
    var firstResult: R? = null
    var prevResult: R? = null
    var eosReached = false
    var stepsDone = 0
    try {
        for (step in 0 until maxNewTokens) {
            // Отмена длинного инференса: проверка на каждой итерации,
            // ресурсы открытых шагов закрывает внешний finally.
            currentCoroutineContext().ensureActive()
            val feedToken = if (step == 0) startTokenId else generated.last()
            val (inputIds, encoderAttentionMask) = newStepInputs(feedToken)
            val result: R
            try {
                result = runStep(step, inputIds, encoderAttentionMask, firstResult, prevResult)
            } finally {
                inputIds.close()
                encoderAttentionMask.close()
            }
            stepsDone++

            // TODO(осознанный долг, ревью): retention первого результата — общий
            // контракт; IndicTrans из него past не читает и держит один лишний
            // результат шага до конца цикла. Персональный keepFirstResult не
            // вводим намеренно (см. абзац ниже).
            // Первый результат держится до конца цикла ОБЩИМ контрактом
            // владения: у Marian из него берут неизменные encoder-past.
            // У IndicTrans encoder-past подаётся из prevResult (граф
            // decoder_with_past получает все четыре past предыдущего шага),
            // поэтому первый результат его коллбэком не читается — но
            // retention намеренно оставлен общим: персональный параметр
            // keepFirstResult добавлял бы ветвление в контракт закрытия
            // ресурсов ради экономии одного лишнего результата шага, с
            // риском рассинхронизации владения (минимальный общий цикл
            // сохранён сознательно, ревью #79).
            if (step == 0) firstResult = result
            // Результат предыдущего шага замещён текущим и больше не нужен
            // (первого — держим до конца цикла).
            val stale = prevResult
            if (stale != null && stale !== firstResult) stale.close()
            prevResult = result

            val token = pickToken(result, generated)
            generated.add(token)
            if (token == eosTokenId) {
                eosReached = true
                break
            }
        }
    } finally {
        // Освобождаем results первого и последнего шага на любом пути.
        firstResult?.close()
        if (prevResult !== firstResult) prevResult?.close()
    }
    // EOS не достигнут за все maxNewTokens шагов — декодер упёрся в лимит.
    // Без выполненного шага (maxNewTokens == 0) упора не было: флаг только
    // при минимум одном шаге декода без EOS (ревью #79).
    return DecodeOutcome(generated, eosReached, hitMaxLength = !eosReached && stepsDone > 0)
}

/**
 * Индекс максимального значения с защитой от NaN/Inf (decode-hygiene, issue #62).
 * Отдельные NaN/±Inf игнорируются при выборе (не могут выиграть); если же
 * ВСЕ значения NaN или бесконечные — выбирать токен не из чего, бросается
 * [IllegalStateException] с описанием шага. Пустой массив тоже считается
 * некорректными logits.
 *
 * @param values логи последней позиции.
 * @param stepDesc описание шага для сообщения об ошибке.
 */
internal fun argMaxSafe(values: FloatArray, stepDesc: String): Int {
    var best = -1
    var bestVal = 0f
    for (i in values.indices) {
        val v = values[i]
        if (v.isNaN() || v.isInfinite()) continue
        if (best == -1 || v > bestVal) {
            bestVal = v
            best = i
        }
    }
    if (best == -1) {
        throw IllegalStateException("Некорректные logits на шаге: $stepDesc")
    }
    return best
}

/**
 * Логи последней позиции последовательности из результата шага декодера
 * (shape [batch, seq, vocab]) — копия, можно мутировать.
 * @throws IllegalStateException если тензор отсутствует, имеет иной тип
 *         или пустой/некорректной формы (нет позиции для чтения).
 */
internal fun logitsLastPosition(result: OrtSession.Result): FloatArray {
    val logitsTensor = ortTensor(result, "logits")
        ?: throw IllegalStateException("Нет logits")
    @Suppress("UNCHECKED_CAST")
    val logitsData = logitsTensor.getValue() as? Array<Array<FloatArray>>
        ?: throw IllegalStateException("Неверный тип logits")
    // Пустой либо некорректной формы ([0][] или [][]) тензор: обращение
    // logitsData[0][...] бросило бы ArrayIndexOutOfBoundsException —
    // приводим к той же явной ошибке, что и отсутствующий тензор (ревью #79).
    if (logitsData.isEmpty() || logitsData[0].isEmpty()) {
        throw IllegalStateException("Нет logits")
    }
    return logitsData[0][logitsData[0].size - 1].copyOf()
}

/**
 * Безопасное извлечение тензора из результата ORT-сессии: в onnxruntime-java
 * [OrtSession.Result.get] возвращает Optional<OnnxValue>, обычный as?каст к
 * OnnxTensor всегда даёт null.
 */
internal fun ortTensor(result: OrtSession.Result, name: String): OnnxTensor? {
    val raw = result.get(name)
    val value = if (raw is Optional<*>) raw.orElse(null) else raw
    return value as? OnnxTensor
}
