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
    fun `short word before period is a real boundary`() {
        // Issue #40: эвристика «слово 1-3 буквы = сокращение» глушила
        // границу после «you.» — теперь режет корректно.
        assertEquals(
            listOf("Thank you.", "Good night."),
            SentenceSplitter.split("Thank you. Good night.")
        )
    }

    @Test
    fun `english abbreviation keeps sentence together`() {
        assertEquals(
            listOf("Mr. Smith went home.", "He locked the door."),
            SentenceSplitter.split("Mr. Smith went home. He locked the door.")
        )
    }

    @Test
    fun `capital city abbreviation with trailing dot is not a boundary`() {
        assertEquals(
            listOf("Мы приехали в Г. Москве.", "Погода ясная."),
            SentenceSplitter.split("Мы приехали в Г. Москве. Погода ясная.")
        )
    }

    @Test
    fun `initials are not sentence boundaries`() {
        assertEquals(
            listOf("А. С. Пушкин писал стихи.", "Он жил в Михайловском."),
            SentenceSplitter.split("А. С. Пушкин писал стихи. Он жил в Михайловском.")
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
