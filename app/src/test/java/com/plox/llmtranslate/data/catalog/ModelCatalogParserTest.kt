package com.plox.llmtranslate.data.catalog

import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.RuntimeType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты парсера JSON-каталога.
 */
class ModelCatalogParserTest {

    private val parser = ModelCatalogParser()

    /** Каталог assets: unit-тесты запускаются из модуля app или его корня. */
    private fun catalogFile(): java.io.File = listOf(
        java.io.File("src/main/assets/models_catalog.json"),
        java.io.File("app/src/main/assets/models_catalog.json")
    ).firstOrNull { it.isFile }
        ?: error("models_catalog.json не найден относительно рабочих каталогов теста")

    /** Манифест моделей (корень репозитория) — источник карточек каталога. */
    private fun manifestFile(): java.io.File = listOf(
        java.io.File("models/manifest.json"),
        java.io.File("../models/manifest.json")
    ).firstOrNull { it.isFile }
        ?: error("models/manifest.json не найден относительно рабочих каталогов теста")

    private fun readManifestRoot(): JsonObject = Json.parseToJsonElement(
        manifestFile().readText(Charsets.UTF_8)
    ).jsonObject

    private fun manifestVersion(root: JsonObject): Int =
        (root["version"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: error("manifest.json не содержит числового поля version")

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
        val raw = catalogFile().readText(Charsets.UTF_8)
        // Версия и состав — сверяются с models/manifest.json (без хардкода:
        // каталог генерируется из манифеста, синхронность фиксируется тестом
        // assets catalog version matches models manifest version).
        val manifestRoot = readManifestRoot()
        assertEquals(
            "Версия каталога assets отстала от models/manifest.json",
            manifestVersion(manifestRoot),
            parser.parseVersion(raw)
        )

        val cards = parser.parse(raw)
        // Число карточек = составу каталога в манифесте (скрытые hidden-карточки
        // issue #55 парсятся и остаются в каталоге).
        assertEquals(manifestRoot["models"]!!.jsonArray.size, cards.size)
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
            // Каталог задаёт default_for_language для каждой Piper-карточки.
            assertEquals(language, card.defaultForLanguage)
        }
        // Остальные карточки default не имеют.
        for (card in cards.filter { it.id !in expectedPiper.keys }) {
            assertEquals(
                "${card.id}: не должна быть default-карточкой языка",
                null,
                card.defaultForLanguage
            )
        }
    }

    @Test
    fun `assets catalog version matches models manifest version`() {
        // Issue #68: version в assets/models_catalog.json обязан быть синхронен
        // с version манифеста models/manifest.json (единственный источник
        // правды о моделях — см. комментарий самого манифеста).
        assertEquals(
            "Версия каталога assets отстала от models/manifest.json",
            manifestVersion(readManifestRoot()),
            parser.parseVersion(catalogFile().readText(Charsets.UTF_8))
        )
    }

    @Test
    fun `assets catalog fully matches models manifest`() {
        // Полная поштучная сверка assets/models_catalog.json с
        // models/manifest.json: набор id, urls[i] ↔ files[i].url,
        // sha256s[i] ↔ files[i].sha256, sizeBytes, hidden,
        // default_for_language. Каталог генерируется из манифеста — любое
        // расхождение (правкa одного файла без другого) валит этот тест.
        val cards = parser.parse(catalogFile().readText(Charsets.UTF_8))
        val manifestRoot = readManifestRoot()

        val manifestById = manifestRoot["models"]!!.jsonArray.associate { element ->
            val model = element.jsonObject
            ((model["id"] as JsonPrimitive).content) to model
        }
        assertEquals(
            "Число карточек каталога не совпадает с манифестом",
            manifestById.size,
            cards.size
        )
        assertEquals(
            "Набор id в каталоге не совпадает с манифестом",
            manifestById.keys.toSet(),
            cards.map { it.id }.toSet()
        )

        for (card in cards) {
            val model = manifestById.getValue(card.id)
            val files = (model["files"] ?: error("${card.id}: в манифесте нет files"))
                .jsonArray.map { it.jsonObject }

            assertEquals(
                "${card.id}: sizeBytes рассинхронизирован",
                (model["sizeBytes"] as JsonPrimitive).content.toLong(),
                card.sizeBytes
            )
            val expectedHidden =
                (model["hidden"] as? JsonPrimitive)?.content?.toBoolean() ?: false
            assertEquals("${card.id}: hidden рассинхронизирован", expectedHidden, card.hidden)

            val manifestUrls = files.map { (it["url"] as JsonPrimitive).content }
            assertEquals("${card.id}: urls рассинхронизированы", manifestUrls, card.downloadUrls())

            val manifestHashes = files.map { file ->
                (file["sha256"] as? JsonPrimitive)?.content
            }
            assertEquals("${card.id}: sha256 рассинхронизированы", manifestHashes, card.sha256s)
        }

        // default_for_language: карта язык → id модели в каталоге совпадает
        // с манифестом (карточка несёт язык, остальные — без default).
        val manifestDefaults = manifestRoot["default_for_language"]?.jsonObject
            ?.mapValues { (_, id) -> (id as JsonPrimitive).content }
            ?: emptyMap()
        val cardDefaults = cards.mapNotNull { card ->
            card.defaultForLanguage?.let { language -> language to card.id }
        }.toMap()
        assertEquals("default_for_language рассинхронизирован", manifestDefaults, cardDefaults)
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

    @Test
    fun `parses default_for_language map onto cards`() {
        val json = """
            {
              "version": 13,
              "default_for_language": { "ru": "tts-custom-ru" },
              "models": [
                {
                  "id": "tts-custom-ru",
                  "category": "TTS",
                  "displayName": "Custom RU",
                  "runtime": "PIPER_ONNX",
                  "sizeBytes": 10
                },
                {
                  "id": "tts-other",
                  "category": "TTS",
                  "displayName": "Other",
                  "runtime": "PIPER_ONNX",
                  "sizeBytes": 10
                }
              ]
            }
        """.trimIndent()

        val cards = parser.parse(json)
        assertEquals("ru", cards.first { it.id == "tts-custom-ru" }.defaultForLanguage)
        // Поле не заполнено для карточки — null (применится резерв селектора).
        assertEquals(null, cards.first { it.id == "tts-other" }.defaultForLanguage)

        // Поле отсутствует в JSON каталога — defaultForLanguage null у всех.
        val withoutDefaults = parser.parse("""
            {
              "version": 1,
              "models": [
                {
                  "id": "tts-x", "category": "TTS", "displayName": "X",
                  "runtime": "PIPER_ONNX", "sizeBytes": 1
                }
              ]
            }
        """.trimIndent())
        assertEquals(null, withoutDefaults[0].defaultForLanguage)
    }

    @Test(expected = Exception::class)
    fun `throws on invalid json`() {
        parser.parse("{ invalid json")
    }

    // ------------------------------------------------------------------
    // Ревью: isLenient убран — собственный формат каталога строгий
    // (lenient допускал нецитированные значения вроде category: TRANSLATION).
    // ------------------------------------------------------------------

    @Test
    fun `strict parser rejects unquoted values`() {
        val json = """
            {
              "version": 1,
              "models": [
                {
                  "id": "x",
                  "category": TRANSLATION,
                  "displayName": "X",
                  "runtime": "SHERPA",
                  "sizeBytes": 10
                }
              ]
            }
        """.trimIndent()
        // parseVersion глотает ошибку и отдаёт 0 (штатный фолбэк версии).
        assertEquals(0, parser.parseVersion(json))
        var thrown = false
        try {
            parser.parse(json)
        } catch (e: Exception) {
            thrown = true
        }
        assertTrue("строгий парсер обязан отклонить нецитированное значение", thrown)
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

    // ------------------------------------------------------------------
    // Issue #83, п.10: дубли id внутри каталога не должны порождать дубли
    // карточек — оставляется первое вхождение.
    // ------------------------------------------------------------------

    @Test
    fun `duplicate ids in catalog keep first occurrence and drop rest`() {
        val json = """
            {
              "version": 1,
              "models": [
                {"id":"dup","displayName":"A1","category":"TRANSLATION","runtime":"MARIAN_ONNX","sizeBytes":1},
                {"id":"dup","displayName":"A2","category":"TRANSLATION","runtime":"MARIAN_ONNX","sizeBytes":2},
                {"id":"uniq","displayName":"B","category":"TRANSLATION","runtime":"MARIAN_ONNX","sizeBytes":3}
              ]
            }
        """.trimIndent()
        val cards = parser.parse(json)
        assertEquals(2, cards.size)
        assertEquals("dup", cards[0].id)
        assertEquals("A1", cards[0].displayName)
        assertEquals("uniq", cards[1].id)
    }

    // ------------------------------------------------------------------
    // Ревью: битый каталог (конфликт default_for_language, рассогласование
    // sha256s/urls) должен фиксироваться в логе; поведение разбора фиксируется
    // тестом, сам лог Timber в unit-тестах — no-op.
    // ------------------------------------------------------------------

    @Test
    fun `default_for_language conflict on one model keeps alphabetically last language`() {
        val json = """
            {
              "version": 1,
              "default_for_language": { "en": "m1", "ru": "m1" },
              "models": [
                {"id":"m1","displayName":"M1","category":"TTS","runtime":"PIPER_ONNX","sizeBytes":1}
              ]
            }
        """.trimIndent()
        // Конфликт логируется; закреплённая семантика — алфавитно последний язык.
        assertEquals("ru", parser.parse(json).single().defaultForLanguage)
    }

    @Test
    fun `mismatched sha256s length is kept as-is beside urls`() {
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
                  "urls": [
                    "https://example.com/encoder.onnx",
                    "https://example.com/decoder.onnx"
                  ],
                  "sha256s": ["aa"]
                }
              ]
            }
        """.trimIndent()
        // Битый каталог (ревью) фиксируется Timber.w; карточка парсится,
        // список сумм остаётся как есть — недостающая проверка пропустится
        // уже на этапе скачивания (perUrlSha256s).
        val card = parser.parse(json).single()
        assertEquals(2, card.downloadUrls().size)
        assertEquals(listOf("aa"), card.sha256s)
    }
}
