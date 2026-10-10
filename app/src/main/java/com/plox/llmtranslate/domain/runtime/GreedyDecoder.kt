package com.plox.llmtranslate.domain.runtime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer
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
 * (EOS включается в результат, как в HF `generate`).
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
 * @return список сгенерированных id (вместе с [initialIds] и, при достижении,
 *         EOS).
 * @throws любое исключение из коллбэков пробрасывается вызывающему движку.
 */
internal fun greedyDecode(
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
): List<Int> {
    val generated = initialIds.toMutableList()
    var firstResult: OrtSession.Result? = null
    var prevResult: OrtSession.Result? = null
    try {
        for (step in 0 until maxNewTokens) {
            val feedToken = if (step == 0) startTokenId else generated.last()
            val inputIds = OnnxTensor.createTensor(
                ortEnv, LongBuffer.wrap(longArrayOf(feedToken.toLong())), longArrayOf(1L, 1L)
            )
            val encoderAttentionMask = OnnxTensor.createTensor(
                ortEnv, LongBuffer.wrap(attentionMask),
                longArrayOf(1L, attentionMask.size.toLong())
            )
            val result: OrtSession.Result
            try {
                result = runStep(step, inputIds, encoderAttentionMask, firstResult, prevResult)
            } finally {
                inputIds.close()
                encoderAttentionMask.close()
            }

            if (step == 0) firstResult = result
            // Результат предыдущего шага замещён текущим и больше не нужен
            // (первого — держим до конца цикла).
            val stale = prevResult
            if (stale != null && stale !== firstResult) stale.close()
            prevResult = result

            val token = pickToken(result, generated)
            generated.add(token)
            if (token == eosTokenId) break
        }
    } finally {
        // Освобождаем results первого и последнего шага на любом пути.
        firstResult?.close()
        if (prevResult !== firstResult) prevResult?.close()
    }
    return generated
}

/**
 * Индекс максимального значения (первое вхождение при равенстве).
 * Общий для greedy-циклов движков перевода.
 */
internal fun argMax(values: FloatArray): Int {
    var best = 0
    var bestVal = Float.NEGATIVE_INFINITY
    for (i in values.indices) {
        if (values[i] > bestVal) {
            bestVal = values[i]
            best = i
        }
    }
    return best
}

/**
 * Логи последней позиции последовательности из результата шага декодера
 * (shape [batch, seq, vocab]) — копия, можно мутировать.
 * @throws IllegalStateException если тензор отсутствует или имеет иной тип.
 */
internal fun logitsLastPosition(result: OrtSession.Result): FloatArray {
    val logitsTensor = ortTensor(result, "logits")
        ?: throw IllegalStateException("Нет logits")
    @Suppress("UNCHECKED_CAST")
    val logitsData = logitsTensor.getValue() as? Array<Array<FloatArray>>
        ?: throw IllegalStateException("Неверный тип logits")
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
