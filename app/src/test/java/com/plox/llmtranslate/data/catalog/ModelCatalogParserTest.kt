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
                   "runtime": "MARIAN_ONNX",
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
        assertEquals(RuntimeType.MARIAN_ONNX, card.runtime)
        assertEquals(461373440L, card.sizeBytes)
        assertEquals(listOf("ru", "en", "ne"), card.languages)
        assertEquals(4, card.quality)
        assertEquals(5, card.speed)
        assertEquals("abc123", card.sha256)
        assertFalse(card.requiresLicenseConfirmation)
        assertEquals(listOf("https://example.com/model.gguf"), card.downloadUrls())
    }

    @Test
    fun `parses multi-file urls`() {
        val json = """
            {
              "version": 2,
              "models": [
                {
                  "id": "nllb",
                  "category": "TRANSLATION",
                  "displayName": "NLLB",
                   "runtime": "MARIAN_ONNX",
                  "sizeBytes": 100,
                  "url": "",
                  "urls": [
                    "https://example.com/encoder.onnx",
                    "https://example.com/decoder.onnx"
                  ]
                }
              ]
            }
        """.trimIndent()

        val cards = parser.parse(json)
        val card = cards[0]
        assertEquals(2, card.urls.size)
        assertEquals(
            listOf("https://example.com/encoder.onnx", "https://example.com/decoder.onnx"),
            card.downloadUrls()
        )
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
                   "runtime": "MARIAN_ONNX",
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
                   "runtime": "SHERPA",
                   "sizeBytes": 42
                }
              ]
            }
        """.trimIndent()

        val cards = parser.parse(json)
        val card = cards[0]
        assertEquals(ModelCategory.STT, card.category)
        assertEquals(RuntimeType.SHERPA, card.runtime)
        assertEquals(0, card.ramRequiredMb)
        assertEquals(emptyList<String>(), card.languages)
        assertEquals(3, card.quality)
        assertEquals("Apache-2.0", card.license)
        assertEquals(26, card.minAndroidSdk)
    }

    @Test
    fun `skips model with unknown category`() {
        val json = """
            {
              "version": 1,
              "models": [
                {
                  "id": "x",
                  "category": "UNKNOWN",
                  "displayName": "X",
                   "runtime": "MARIAN_ONNX",
                  "sizeBytes": 1
                },
                {
                  "id": "ok",
                  "category": "TRANSLATION",
                  "displayName": "OK",
                   "runtime": "MARIAN_ONNX",
                  "sizeBytes": 2
                }
              ]
            }
        """.trimIndent()
        val cards = parser.parse(json)
        assertEquals(1, cards.size)
        assertEquals("ok", cards[0].id)
    }

    @Test
    fun `skips model with unknown runtime`() {
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
                },
                {
                   "id": "ok",
                   "category": "STT",
                   "displayName": "OK",
                   "runtime": "SHERPA",
                   "sizeBytes": 2
                }
              ]
            }
        """.trimIndent()
        val cards = parser.parse(json)
        assertEquals(1, cards.size)
        assertEquals("ok", cards[0].id)
    }

    @Test(expected = Exception::class)
    fun `throws on invalid json`() {
        parser.parse("{ invalid json")
    }

    @Test
    fun `parses catalog version`() {
        val json = """
            {
              "version": 5,
              "models": []
            }
        """.trimIndent()
        assertEquals(5, parser.parseVersion(json))
    }

    @Test
    fun `returns 0 for invalid version json`() {
        assertEquals(0, parser.parseVersion("{ invalid"))
    }
}
