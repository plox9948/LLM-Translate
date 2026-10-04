package com.plox.llmtranslate.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Тесты определения языка по письменности.
 */
class LanguageDetectorTest {

    @Test
    fun `detects russian`() {
        assertEquals("ru", LanguageDetector.detect("Привет, как дела?"))
    }

    @Test
    fun `detects nepali`() {
        assertEquals("ne", LanguageDetector.detect("नमस्ते, तपाईं कस्तो हुनुहुन्छ?"))
    }

    @Test
    fun `detects english`() {
        assertEquals("en", LanguageDetector.detect("Hello, how are you?"))
    }

    @Test
    fun `returns null for short text`() {
        assertNull(LanguageDetector.detect("Hi"))
    }

    @Test
    fun `returns null for empty text`() {
        assertNull(LanguageDetector.detect(""))
    }

    @Test
    fun `returns null for mixed scripts`() {
        assertNull(LanguageDetector.detect("Привет hello नमस्ते"))
    }
}
