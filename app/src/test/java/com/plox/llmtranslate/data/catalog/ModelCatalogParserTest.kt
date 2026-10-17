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

    @Test
    fun `parses per-url sha256s list`() {
        val json = """
            {
              "version": 7,
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
                    "https://example.com/decoder.onnx",
                    "https://example.com/tokenizer.json"
                  ],
                  "sha256s": ["aa", null, "cc"]
                }
              ]
            }
        """.trimIndent()

        val card = parser.parse(json)[0]
        assertEquals(listOf("aa", null, "cc"), card.sha256s)
        assertEquals(3, card.downloadUrls().size)
    }

    @Test
    fun `legacy single sha256 becomes single-element list`() {
        val json = """
            {
              "version": 1,
              "models": [
                {
                  "id": "x",
                  "category": "STT",
                  "displayName": "X",
                  "runtime": "SHERPA",
                  "sizeBytes": 10,
                  "url": "https://example.com/x.tar.bz2",
                  "sha256": "beef"
                }
              ]
            }
        """.trimIndent()

        val card = parser.parse(json)[0]
        assertEquals("beef", card.sha256)
        assertEquals(listOf("beef"), card.sha256s)
    }

    @Test
    fun `shipping assets catalog parses with per-url hashes for every download url`() {
        // Реальный каталог из assets: парсер не должен его отбраковать,
        // и каждое downloadUrls() должно иметь непустой SHA-256.
        val candidates = listOf(
            java.io.File("src/main/assets/models_catalog.json"),
            java.io.File("app/src/main/assets/models_catalog.json")
        )
        val file = candidates.firstOrNull { it.isFile }
        assertTrue("models_catalog.json не найден относительно $candidates", file != null)

        val raw = file!!.readText(Charsets.UTF_8)
        // Версия каталога assets (после #55 — 12: Whisper/RHVoice/eSpeak NG
        // помечены hidden).
        assertEquals(12, parser.parseVersion(raw))

        val cards = parser.parse(raw)
        // Число карточек = текущему составу каталога assets (после #50 —
        // 14 моделей: 11 из #34 + 3 скачиваемых Piper TTS; скрытые hidden
        // карточки issue #55 парсятся и остаются в каталоге).
        assertEquals(14, cards.size)
        // Скрытые из UI карточки (issue #55): в каталоге есть, hidden=true.
        val expectedHidden = setOf(
            "stt-whisper-small-multilingual",
            "tts-rhvoice",
            "tts-espeak"
        )
        for (id in expectedHidden) {
            val card = cards.firstOrNull { it.id == id }
            assertTrue("Скрытая карточка $id не распарсилась", card != null)
            assertTrue("$id должна быть hidden", card!!.hidden)
        }
        for (card in cards.filter { it.id !in expectedHidden }) {
            assertFalse("${card.id} не должна быть hidden", card.hidden)
        }
        for (card in cards.filter { it.downloadUrls().isNotEmpty() }) {
            val urls = card.downloadUrls()
            assertEquals("${card.id}: sha256s != urls", urls.size, card.sha256s.size)
            for (hash in card.sha256s) {
                assertTrue(
                    "${card.id}: пустой SHA-256",
                    hash != null && hash.matches(Regex("[0-9a-f]{64}"))
                )
            }
        }
        // TTS-движки-ярлыки файлов не имеют; скачиваемые Piper-карточки (issue #50) —
        // один tar.bz2 с SHA-256 и своим языком.
        for (card in cards.filter {
            it.category == ModelCategory.TTS && it.runtime != RuntimeType.PIPER_ONNX
        }
        ) {
            assertTrue(card.downloadUrls().isEmpty())
        }
        val expectedPiper = mapOf(
            "tts-ru-piper-ruslan" to "ru",
            "tts-en-piper-ryan" to "en",
            "tts-ne-piper-google" to "ne"
        )
        for ((id, language) in expectedPiper) {
            val card = cards.firstOrNull { it.id == id }
            assertTrue("Карточка $id не распарсилась", card != null)
            assertEquals(ModelCategory.TTS, card!!.category)
            assertEquals(RuntimeType.PIPER_ONNX, card.runtime)
            assertEquals(listOf(language), card.languages)
            assertEquals(1, card.downloadUrls().size)
            assertTrue(
                card.downloadUrls().single().endsWith(".tar.bz2")
            )
        }
    }

    @Test
    fun `parses hidden flag`() {
        val json = """
            {
              "version": 12,
              "models": [
                {
                  "id": "tts-espeak",
                  "category": "TTS",
                  "displayName": "eSpeak NG",
                  "runtime": "ESPEAK",
                  "sizeBytes": 0,
                  "hidden": true
                },
                {
                  "id": "tts-system",
                  "category": "TTS",
                  "displayName": "System",
                  "runtime": "SYSTEM_TTS",
                  "sizeBytes": 0
                }
              ]
            }
        """.trimIndent()

        val cards = parser.parse(json)
        assertEquals(2, cards.size)
        assertTrue(cards.first { it.id == "tts-espeak" }.hidden)
        // Поле отсутствует в JSON — значение по умолчанию false.
        assertFalse(cards.first { it.id == "tts-system" }.hidden)
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
