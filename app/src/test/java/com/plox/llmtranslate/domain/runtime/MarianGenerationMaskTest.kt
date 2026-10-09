package com.plox.llmtranslate.domain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты маски генерации Marian: bad-слова и штраф повторения (формула HF).
 *
 * Чистая функция applyGenerationMask инстанцируется напрямую, модель не нужна.
 */
class MarianGenerationMaskTest {

    private val runtime = MarianOnnxRuntime()
    private val tol = 1e-4f

    @Test
    fun `bad id set to negative infinity`() {
        val logits = floatArrayOf(1f, 2f, 3f)
        runtime.applyGenerationMask(logits, emptyList(), setOf(1), 1f)
        assertEquals(1f, logits[0], tol)
        assertTrue("bad id должен стать -Inf", logits[1] == Float.NEGATIVE_INFINITY)
        assertEquals(3f, logits[2], tol)
    }

    @Test
    fun `penalty greater than one divides positive and multiplies negative`() {
        val logits = floatArrayOf(2.4f, -2.4f, 0f)
        runtime.applyGenerationMask(logits, listOf(0, 1, 2), emptySet(), 1.2f)
        // 2.4 / 1.2 = 2.0
        assertEquals(2.0f, logits[0], tol)
        // -2.4 * 1.2 = -2.88
        assertEquals(-2.88f, logits[1], tol)
        // 0 * 1.2 = 0
        assertEquals(0f, logits[2], tol)
    }

    @Test
    fun `penalty at most one leaves logits unchanged`() {
        val logits = floatArrayOf(2.4f, -2.4f, 3f)
        runtime.applyGenerationMask(logits, listOf(0, 1, 2), emptySet(), 1f)
        assertEquals(2.4f, logits[0], tol)
        assertEquals(-2.4f, logits[1], tol)
        assertEquals(3f, logits[2], tol)

        val logits2 = floatArrayOf(2.4f, -2.4f)
        runtime.applyGenerationMask(logits2, listOf(0, 1), emptySet(), 0.5f)
        assertEquals(2.4f, logits2[0], tol)
        assertEquals(-2.4f, logits2[1], tol)
    }

    @Test
    fun `out of range ids ignored`() {
        val logits = floatArrayOf(1f, 2f, 3f)
        runtime.applyGenerationMask(logits, listOf(10, 5), setOf(10, 99), 1.2f)
        assertEquals(1f, logits[0], tol)
        assertEquals(2f, logits[1], tol)
        assertEquals(3f, logits[2], tol)
    }

    @Test
    fun `duplicate generated ids penalized once`() {
        val logits = floatArrayOf(2.4f, 1f)
        runtime.applyGenerationMask(logits, listOf(0, 0, 0), emptySet(), 1.2f)
        // 2.4 / 1.2 = 2.0 (один раз), а не 2.4 / 1.2 / 1.2
        assertEquals(2.0f, logits[0], tol)
        assertEquals(1f, logits[1], tol)
    }
}
