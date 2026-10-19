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
    fun `bare newline boundary yields newline separator`() {
        // Дефект устройства: «hello world\nsecond line» — граница закрыта
        // самим переводом строки (без пунктуации), разделитель обязан быть «\n»,
        // иначе ViewModel склеивает сегменты пробелом.
        val (segments, separators) = SentenceSplitter.splitWithSeparators("hello world\nsecond line")
        assertEquals(listOf("hello world", "second line"), segments)
        assertEquals(listOf("\n"), separators)
    }

    @Test
    fun `punctuation boundary yields space separator`() {
        val (_, separators) = SentenceSplitter.splitWithSeparators("First one. Second one.")
        assertEquals(listOf(" "), separators)
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
    fun `no and etc are sentence boundaries not abbreviations`() {
        // Issue #40: «No.» — полноценная граница предложения, «etc.» почти
        // всегда конец предложения — убраны из ABBREVIATIONS.
        assertEquals(
            listOf("Do you like it?", "No.", "I do."),
            SentenceSplitter.split("Do you like it? No. I do.")
        )
        assertEquals(
            listOf("No.", "I do."),
            SentenceSplitter.split("No. I do.")
        )
        assertEquals(
            listOf("I like tea, etc.", "He left."),
            SentenceSplitter.split("I like tea, etc. He left.")
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

    // ── Данда деванагари (issue #63) ──

    @Test
    fun `danda splits without following space`() {
        // । — безусловная граница: после неё не нужен пробел/конец текста.
        assertEquals(
            listOf("नमस्ते।", "मस्कोबाट"),
            SentenceSplitter.split("नमस्ते।मस्कोबाट")
        )
    }

    @Test
    fun `double danda char is a boundary`() {
        assertEquals(
            listOf("AB॥", "CD"),
            SentenceSplitter.split("AB॥CD")
        )
    }

    @Test
    fun `consecutive dandas collapse into one boundary`() {
        // ।। после закрытия сегмента не создаёт пустой сегмент.
        assertEquals(
            listOf("A।", "B"),
            SentenceSplitter.split("A।।B")
        )
        // Смешанная последовательность ।॥ — одна граница, без пустых сегментов.
        assertEquals(
            listOf("A।", "B"),
            SentenceSplitter.split("A।॥B")
        )
    }

    @Test
    fun `trailing danda sequence yields no empty tail segment`() {
        assertEquals(
            listOf("A।"),
            SentenceSplitter.split("A।।")
        )
        assertEquals(
            listOf("A॥"),
            SentenceSplitter.split("A॥॥")
        )
    }

    @Test
    fun `mixed devanagari and latin boundary splits correctly`() {
        assertEquals(
            listOf("Привет।", "How are you?"),
            SentenceSplitter.split("Привет।How are you?")
        )
    }

    @Test
    fun `danda boundary uses space separator without newline`() {
        val (segments, separators) = SentenceSplitter.splitWithSeparators("नमस्ते।मस्कोबाट")
        assertEquals(listOf("नमस्ते।", "मस्कोबाट"), segments)
        assertEquals(listOf(" "), separators)
    }

    @Test
    fun `bare word gets period appended`() {
        assertEquals("Озеро.", SentenceSplitter.ensureTrailingTerminator("Озеро"))
        assertEquals("Hello.", SentenceSplitter.ensureTrailingTerminator("Hello"))
        assertEquals("ताल.", SentenceSplitter.ensureTrailingTerminator("ताल"))
    }

    @Test
    fun `already terminated text unchanged`() {
        assertEquals("Озеро.", SentenceSplitter.ensureTrailingTerminator("Озеро."))
        assertEquals("Стой!", SentenceSplitter.ensureTrailingTerminator("Стой!"))
        assertEquals("Куда?", SentenceSplitter.ensureTrailingTerminator("Куда?"))
        assertEquals("Дойдём…", SentenceSplitter.ensureTrailingTerminator("Дойдём…"))
        assertEquals("काठमाडौं।", SentenceSplitter.ensureTrailingTerminator("काठमाडौं।"))
        assertEquals("ताल॥", SentenceSplitter.ensureTrailingTerminator("ताल॥"))
    }

    @Test
    fun `trailing whitespace normalized with terminator`() {
        assertEquals("Озеро.", SentenceSplitter.ensureTrailingTerminator("Озеро  "))
        assertEquals("Озеро.", SentenceSplitter.ensureTrailingTerminator("Озеро\n"))
    }

    @Test
    fun `closing quotes brackets and mid-punctuation not extended`() {
        assertEquals("«Озеро»", SentenceSplitter.ensureTrailingTerminator("«Озеро»"))
        assertEquals("озеро)", SentenceSplitter.ensureTrailingTerminator("озеро)"))
        assertEquals("озеро,", SentenceSplitter.ensureTrailingTerminator("озеро,"))
        assertEquals("озеро -", SentenceSplitter.ensureTrailingTerminator("озеро -"))
    }

    @Test
    fun `blank input returned unchanged`() {
        assertEquals("", SentenceSplitter.ensureTrailingTerminator(""))
        assertEquals("   ", SentenceSplitter.ensureTrailingTerminator("   "))
    }

    @Test
    fun `digits at end get terminator too`() {
        assertEquals("2026.", SentenceSplitter.ensureTrailingTerminator("2026"))
    }
}
