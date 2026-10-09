package com.plox.llmtranslate.ui.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты формата меток языков «Родное имя (English Name)».
 */
class TranslateLanguageTest {

    @Test
    fun `auto title has english name`() {
        assertTitleFormat(TranslateLanguage.AUTO, "Auto")
    }

    @Test
    fun `russian title has english name`() {
        assertTitleFormat(TranslateLanguage.RU, "Russian")
    }

    @Test
    fun `english title has english name`() {
        assertTitleFormat(TranslateLanguage.EN, "English")
    }

    @Test
    fun `nepali title has english name`() {
        assertTitleFormat(TranslateLanguage.NE, "Nepali")
    }

    @Test
    fun `codes are stable`() {
        assertEquals("auto", TranslateLanguage.AUTO.code)
        assertEquals("ru", TranslateLanguage.RU.code)
        assertEquals("en", TranslateLanguage.EN.code)
        assertEquals("ne", TranslateLanguage.NE.code)
    }

    // Каждый title содержит разделитель " (" и английское имя в скобках.
    private fun assertTitleFormat(lang: TranslateLanguage, englishName: String) {
        val title = lang.title
        assertTrue("title должен содержать \" (\": $title", title.contains(" ("))
        assertTrue(
            "title должен содержать английское имя: $title",
            title.endsWith("($englishName)")
        )
    }
}
