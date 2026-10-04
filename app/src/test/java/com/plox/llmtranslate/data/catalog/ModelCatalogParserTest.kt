package com.plox.llmtranslate.data.catalog

import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.RuntimeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты парсера JSON-каталога.
 */
class ModelCatalogParserTest {

    private val parser = ModelCatalogParser()

    @Test
    fun `parses valid catalog`() {
        val json = """
            {
              "version": 1,
              "models": [
                {
                  "id": "hy-mt1.5-1.8b-q1_25",
                  "category": "TRANSLATION",
                  "displayName": "Hy-MT 1.8B",
                  "description": "Tencent Hy-MT",
                  "runtime": "GGUF",
                  "sizeBytes": 461373440,
                  "ramRequiredMb": 2500,
                  "languages": ["ru", "en", "ne"],
                  "quality": 4,
                  "speed": 5,
                  "url": "https://example.com/model.gguf",
                  "sha256": "abc123",
                  "license": "Apache-2.0",
                  "minAndroidSdk": 26,
                  "recommendedRamMb": 4096
                }
              ]
            }
        """.trimIndent()

        val cards = parser.parse(json)

        assertEquals(1, cards.size)
        val card = cards[0]
        assertEquals("hy-mt1.5-1.8b-q1_25", card.id)
        assertEquals(ModelCategory.TRANSLATION, card.category)
        assertEquals(RuntimeType.GGUF, card.runtime)
        assertEquals(461373440L, card.sizeBytes)
        assertEquals(listOf("ru", "en", "ne"), card.languages)
        assertEquals(4, card.quality)
        assertEquals(5, card.speed)
        assertEquals("abc123", card.sha256)
        assertFalse(card.requiresLicenseConfirmation)
    }

    @Test
    fun `detects non-commercial license`() {
        val json = """
            {
              "version": 1,
              "models": [
                {
                  "id": "nllb",
                  "category": "TRANSLATION",
                  "displayName": "NLLB",
                  "runtime": "ONNX",
                  "sizeBytes": 100,
                  "license": "CC-BY-NC-4.0"
                }
              ]
            }
        """.trimIndent()

        val cards = parser.parse(json)
        assertTrue(cards[0].requiresLicenseConfirmation)
    }

    @Test
    fun `uses defaults for missing optional fields`() {
        val json = """
            {
              "version": 1,
              "models": [
                {
                  "id": "minimal",
                  "category": "STT",
                  "displayName": "Minimal",
                  "runtime": "VOSK",
                  "sizeBytes": 42
                }
              ]
            }
        """.trimIndent()

        val cards = parser.parse(json)
        val card = cards[0]
        assertEquals(ModelCategory.STT, card.category)
        assertEquals(RuntimeType.VOSK, card.runtime)
        assertEquals(0, card.ramRequiredMb)
        assertEquals(emptyList(), card.languages)
        assertEquals(3, card.quality)
        assertEquals("Apache-2.0", card.license)
        assertEquals(26, card.minAndroidSdk)
    }

    @Test(expected = IllegalStateException::class)
    fun `throws on unknown category`() {
        val json = """
            {
              "version": 1,
              "models": [
                {
                  "id": "x",
                  "category": "UNKNOWN",
                  "displayName": "X",
                  "runtime": "GGUF",
                  "sizeBytes": 1
                }
              ]
            }
        """.trimIndent()
        parser.parse(json)
    }

    @Test(expected = IllegalStateException::class)
    fun `throws on unknown runtime`() {
        val json = """
            {
              "version": 1,
              "models": [
                {
                  "id": "x",
                  "category": "STT",
                  "displayName": "X",
                  "runtime": "UNKNOWN",
                  "sizeBytes": 1
                }
              ]
            }
        """.trimIndent()
        parser.parse(json)
    }

    @Test(expected = Exception::class)
    fun `throws on invalid json`() {
        parser.parse("{ invalid json")
    }
}
