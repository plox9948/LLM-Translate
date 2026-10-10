package com.plox.llmtranslate.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Тесты разбиения текста на предложения для перевода по частям.
 */
class SentenceSplitterTest {

    @Test
    fun `two sentences split on period plus space`() {
        val text = "The boots shine against the snow. A man attaches crampons."
        assertEquals(
            listOf(
                "The boots shine against the snow.",
                "A man attaches crampons."
            ),
            SentenceSplitter.split(text)
        )
    }

    @Test
    fun `question exclamation and ellipsis are boundaries`() {
        val text = "Куда идёшь? Стой! Дойдём… вместе."
        assertEquals(
            listOf("Куда идёшь?", "Стой!", "Дойдём…", "вместе."),
            SentenceSplitter.split(text)
        )
    }

    @Test
    fun `newline splits paragraphs regardless of punctuation`() {
        assertEquals(
            listOf("первая строка", "вторая строка"),
            SentenceSplitter.split("первая строка\nвторая строка")
        )
    }

    @Test
    fun `decimal point and abbreviation without space are not boundaries`() {
        assertEquals(
            listOf("Температура минус 3.14 градуса устойчива."),
            SentenceSplitter.split("Температура минус 3.14 градуса устойчива.")
        )
        assertEquals(
            listOf("Mr.Smith is here."),
            SentenceSplitter.split("Mr.Smith is here.")
        )
    }

    @Test
    fun `abbreviation with short word is not a boundary`() {
        // «г.» — сокращение, предложение не режется на нём.
        assertEquals(
            listOf("Мы жили в г. Киров."),
            SentenceSplitter.split("Мы жили в г. Киров.")
        )
    }

    @Test
    fun `single sentence returned as is`() {
        assertEquals(
            listOf("A man in a knitted fleece smiles."),
            SentenceSplitter.split("A man in a knitted fleece smiles.")
        )
    }

    @Test
    fun `blank text yields empty list`() {
        assertEquals(emptyList<String>(), SentenceSplitter.split("   \n  "))
    }

    @Test
    fun `extra whitespace between sentences collapsed`() {
        assertEquals(
            listOf("Первое предложение.", "Второе предложение."),
            SentenceSplitter.split("Первое предложение.   Второе предложение.  ")
        )
    }
}
