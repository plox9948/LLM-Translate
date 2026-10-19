package com.plox.llmtranslate.ui.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Тесты формата двуязычных меток языков «Родное имя (English Name)» (ревью #21):
 * подписи вынесены в ресурсы ([TranslateLanguage.titleRes]) — проверяются
 * стабильность кодов, уникальность id ресурсов и содержимое значения по
 * умолчанию из values/strings.xml (JVM-тест не резолвит Android-ресурсы,
 * поэтому текст читается из файла ресурсов модуля).
 */
class TranslateLanguageTest {

    @Test
    fun `auto title has english name`() {
        assertTitleFormat(TranslateLanguage.AUTO, "lang_title_auto", "Auto")
    }

    @Test
    fun `russian title has english name`() {
        assertTitleFormat(TranslateLanguage.RU, "lang_title_ru", "Russian")
    }

    @Test
    fun `english title has english name`() {
        assertTitleFormat(TranslateLanguage.EN, "lang_title_en", "English")
    }

    @Test
    fun `nepali title has english name`() {
        assertTitleFormat(TranslateLanguage.NE, "lang_title_ne", "Nepali")
    }

    @Test
    fun `codes are stable`() {
        assertEquals("auto", TranslateLanguage.AUTO.code)
        assertEquals("ru", TranslateLanguage.RU.code)
        assertEquals("en", TranslateLanguage.EN.code)
        assertEquals("ne", TranslateLanguage.NE.code)
    }

    @Test
    fun `title resources are distinct`() {
        val ids = TranslateLanguage.entries.map { it.titleRes }.toSet()
        assertEquals(TranslateLanguage.entries.size, ids.size)
        assertTrue(
            "titleRes не должен быть пустым",
            TranslateLanguage.entries.all { it.titleRes != 0 }
        )
        assertNotEquals(0, TranslateLanguage.EN.titleRes)
    }

    @Test
    fun `supported codes exclude auto`() {
        assertEquals(listOf("ru", "en", "ne"), TranslateLanguage.SUPPORTED_CODES)
    }

    // Значение по умолчанию из values/strings.xml содержит разделитель " (" и
    // английское имя в скобках (двуязычная подпись чипа).
    private fun assertTitleFormat(lang: TranslateLanguage, name: String, englishName: String) {
        val title = defaultStringValue(name)
        assertTrue("title должен содержать \" (\": $title", title.contains(" ("))
        assertTrue(
            "title должен содержать английское имя: $title",
            title.endsWith("($englishName)")
        )
    }

    /** Извлечь значение строкового ресурса из файла значений по умолчанию. */
    private fun defaultStringValue(name: String): String {
        // Рабочий каталог JVM-теста Gradle — каталог модуля; с запасом —
        // корень репозитория.
        val candidates = listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("values/strings.xml не найден: $candidates")
        val regex = Regex("<string name=\"$name\">([^<]*)</string>")
        return regex.find(file.readText())?.groupValues?.get(1)
            ?: throw IllegalStateException("ресурс $name не найден в ${file.path}")
    }
}
