package com.plox.llmtranslate.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Тесты детерминированного выбора языка UI по языку системы (issue #35):
 * ru→ru, ne→ne, любой другой язык, пустая строка и null→en; регистр не значим.
 */
class UiLanguageDefaultsTest {

    @Test
    fun `system russian maps to ru`() {
        assertEquals("ru", defaultUiLanguageForSystemLocale("ru"))
    }

    @Test
    fun `system nepali maps to ne`() {
        assertEquals("ne", defaultUiLanguageForSystemLocale("ne"))
    }

    @Test
    fun `null system language falls back to english`() {
        assertEquals("en", defaultUiLanguageForSystemLocale(null))
    }

    @Test
    fun `empty system language falls back to english`() {
        assertEquals("en", defaultUiLanguageForSystemLocale(""))
    }

    @Test
    fun `unsupported system languages fall back to english`() {
        for (language in listOf("de", "hi", "fr", "npi")) {
            assertEquals("en", defaultUiLanguageForSystemLocale(language))
        }
    }

    @Test
    fun `matching is case insensitive`() {
        assertEquals("ru", defaultUiLanguageForSystemLocale("RU"))
        assertEquals("ne", defaultUiLanguageForSystemLocale("Ne"))
    }
}
