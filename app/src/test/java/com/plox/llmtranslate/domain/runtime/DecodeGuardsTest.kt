package com.plox.llmtranslate.domain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Юнит-тесты чистых функций decode-hygiene (issue #62): детектора
 * вырожденного вывода [isDegenerateOutput] и маски no_repeat_ngram
 * [maskRepeatingNgrams]. OBRT-часть [pickGreedyTokenSafe] на JVM не
 * тестируется (OrtSession грузит нативную библиотеку).
 */
class DecodeGuardsTest {

    // === Детектор вырождения ===

    @Test
    fun `single word loop twelve times is degenerate`() {
        val text = "ताल ".repeat(12).trim()
        assertTrue(isDegenerateOutput(text, eosReached = true, hitMaxLength = false))
    }

    @Test
    fun `short correct output is not degenerate`() {
        assertFalse(isDegenerateOutput("धेरै धेरै धन्यवाद", eosReached = false, hitMaxLength = false))
    }

    @Test
    fun `two bigram cycles are not degenerate`() {
        assertFalse(isDegenerateOutput("hello world hello world hello", eosReached = true, hitMaxLength = false))
    }

    @Test
    fun `unique short text is not degenerate`() {
        assertFalse(isDegenerateOutput("Привет мир как дела", eosReached = true, hitMaxLength = false))
    }

    @Test
    fun `hit max length is always degenerate`() {
        assertTrue(isDegenerateOutput("любые нормальные уникальные слова", eosReached = false, hitMaxLength = true))
    }

    @Test
    fun `low unique ratio is degenerate`() {
        // 10 слов, 2 уникальных (доля 0.2 < 0.3); хвостовой цикл отсутствует,
        // признак — именно бедность словаря.
        val text = "a b a a b a a b a a"
        assertTrue(isDegenerateOutput(text, eosReached = true, hitMaxLength = false))
    }

    @Test
    fun `missing eos alone is not degenerate`() {
        val text = "один два три четыре пять шесть семь восемь девять десять"
        assertFalse(isDegenerateOutput(text, eosReached = false, hitMaxLength = false))
    }

    @Test
    fun `tail cycle with period two is degenerate`() {
        // Период из 2 слов повторён 4 раза подряд в хвосте.
        val text = "start words here ta da ta da ta da ta da"
        assertTrue(isDegenerateOutput(text, eosReached = true, hitMaxLength = false))
    }

    @Test
    fun `punctuation-only tokens are not counted as words`() {
        // Пунктуация не помогает и не мешает: слов <8, цикл по словам отсутствует.
        assertFalse(isDegenerateOutput("спасибо . . । !", eosReached = false, hitMaxLength = false))
    }

    // === Маска no_repeat_ngram ===

    @Test
    fun `trigram repetition masks its continuation`() {
        // Контекст [10, 20] повторяется; предшествующее вхождение продолжалось id 30.
        val logits = FloatArray(50) { 0f }
        logits[30] = 5f
        logits[40] = 1f
        val generated = listOf(1, 10, 20, 30, 10, 20)
        maskRepeatingNgrams(logits, generated, n = 3)
        assertEquals(-Float.MAX_VALUE, logits[30], 0f)
        // Выбор без замаскированного id.
        assertEquals(40, argMaxSafe(logits, "тест trigram"))
    }

    @Test
    fun `no mask when ngram has not occurred before`() {
        val logits = FloatArray(10) { it.toFloat() }
        val expected = logits.copyOf()
        // Контекст [8, 9], вхождений раньше с продолжением нет.
        maskRepeatingNgrams(logits, listOf(8, 9), n = 3)
        assertArrayEqualsCompact(expected, logits)
        maskRepeatingNgrams(logits, listOf(1, 2, 3, 8, 9), n = 4)
        assertArrayEqualsCompact(expected, logits)
    }

    @Test
    fun `bigram repetition masks continuation`() {
        val logits = FloatArray(10) { 0f }
        logits[7] = 9f
        // Контекст [6]; ранее 6 встречалось в позиции 1 с продолжением 7.
        maskRepeatingNgrams(logits, listOf(5, 6, 7, 8, 6), n = 2)
        assertEquals(-Float.MAX_VALUE, logits[7], 0f)
    }

    @Test
    fun `unigram mask forbids every already generated id`() {
        val logits = FloatArray(10) { 0f }
        logits[4] = 8f
        logits[5] = 7f
        maskRepeatingNgrams(logits, listOf(4, 5), n = 1)
        assertEquals(-Float.MAX_VALUE, logits[4], 0f)
        assertEquals(-Float.MAX_VALUE, logits[5], 0f)
    }

    @Test
    fun `zero ngram size masks nothing`() {
        val logits = FloatArray(10) { it.toFloat() }
        val expected = logits.copyOf()
        maskRepeatingNgrams(logits, listOf(1, 1, 1), n = 0)
        assertArrayEqualsCompact(expected, logits)
    }

    @Test
    fun `context occurrence without continuation is not masked`() {
        val logits = FloatArray(10) { 0f }
        logits[9] = 5f
        // [2, 3] встречается только как сам контекст в конце — продолжения нет.
        val generated = listOf(4, 5, 2, 3)
        maskRepeatingNgrams(logits, generated, n = 3)
        assertEquals(9, argMaxSafe(logits, "тест без продолжения"))
    }

    /** Поккомпанентное сравнение массивов (без import varags-_asserj). */
    private fun assertArrayEqualsCompact(expected: FloatArray, actual: FloatArray) {
        assertTrue(
            "logits изменены неожиданно: ${actual.toList()}",
            expected.contentEquals(actual)
        )
    }
}
