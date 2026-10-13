package com.plox.llmtranslate.domain.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Юнит-тесты чистой функции [argMax] — выбор индекса максимума logits,
 * используемый greedy-циклами MarianOnnxRuntime и IndicTransOnnxRuntime
 * (у Marian — внутри pickToken со штрафом повторения).
 */
class GreedyDecoderArgMaxTest {

    @Test
    fun `returns index of maximum`() {
        assertEquals(2, argMax(floatArrayOf(0.1f, 0.5f, 3.0f, -2.0f)))
    }

    @Test
    fun `maximum at boundaries`() {
        assertEquals(0, argMax(floatArrayOf(9f, 1f, 2f)))
        assertEquals(3, argMax(floatArrayOf(1f, 2f, 3f, 9f)))
    }

    @Test
    fun `first index wins on ties`() {
        // Строгое сравнение (>): при равенстве берётся первое вхождение —
        // детерминированная привязка токенов с одинаковым логом.
        assertEquals(1, argMax(floatArrayOf(2f, 5f, 5f, 2f)))
    }

    @Test
    fun `all negative picks least negative`() {
        assertEquals(2, argMax(floatArrayOf(-7f, -3f, -1f, -4f)))
    }

    @Test
    fun `negative infinity values lose to any finite`() {
        assertEquals(
            1,
            argMax(
                floatArrayOf(
                    Float.NEGATIVE_INFINITY,
                    -1000f,
                    Float.NEGATIVE_INFINITY
                )
            )
        )
    }

    @Test
    fun `single element returns zero`() {
        assertEquals(0, argMax(floatArrayOf(42f)))
    }

    @Test
    fun `empty array returns zero by contract`() {
        // best инициализирован нулём: для пустого массива функция
        // возвращает 0 (вызывающий движок не передаёт пустые logits).
        assertEquals(0, argMax(FloatArray(0)))
    }

    @Test
    fun `maximum in large vocab at tail`() {
        val logits = FloatArray(64_000) { i -> (i % 7) - 3f }
        logits[63_999] = 12.5f
        logits[10] = 12.5f // такое же значение раньше — выигрывает первое
        assertEquals(10, argMax(logits))
    }
}
