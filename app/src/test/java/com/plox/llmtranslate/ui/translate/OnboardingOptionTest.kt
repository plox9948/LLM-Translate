package com.plox.llmtranslate.ui.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Тесты чистого отображения индекса варианта онбординга в направление перевода.
 */
class OnboardingOptionTest {

    @Test
    fun `index zero is russian english`() {
        assertDirection(0, TranslateLanguage.RU, TranslateLanguage.EN)
    }

    @Test
    fun `index one is english nepali`() {
        assertDirection(1, TranslateLanguage.EN, TranslateLanguage.NE)
    }

    @Test
    fun `index two is russian nepali`() {
        assertDirection(2, TranslateLanguage.RU, TranslateLanguage.NE)
    }

    @Test
    fun `out of range index has no direction`() {
        assertNull(OnboardingOption.byIndex(-1))
        assertNull(OnboardingOption.byIndex(OnboardingOption.entries.size))
    }

    @Test
    fun `auto language never used in onboarding`() {
        for (option in OnboardingOption.entries) {
            assertEquals(
                "AUTO недопустим в онбординге: $option",
                true,
                option.sourceLang != TranslateLanguage.AUTO &&
                    option.targetLang != TranslateLanguage.AUTO
            )
        }
    }

    private fun assertDirection(index: Int, source: TranslateLanguage, target: TranslateLanguage) {
        val option = OnboardingOption.byIndex(index)
        assertEquals(source, requireNotNull(option).sourceLang)
        assertEquals(target, option.targetLang)
    }
}
