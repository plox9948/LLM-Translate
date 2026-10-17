package com.plox.llmtranslate.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты приоритизации каталога под направление перевода и подписей чипов (issue #33).
 */
class CatalogPriorityTest {

    // Каталог, повторяющий models_catalog.json (порядок — исходный).
    private val ruEn = card("firefox-bergamot-ru-en", "Firefox Translations (RU→EN)", listOf("ru", "en"))
    private val enRu = card("firefox-bergamot-en-ru", "Firefox Translations (EN→RU)", listOf("ru", "en"))
    private val neEn = card("indictrans2-indic-en-int8", "IndicTrans2 (Indic→EN, int8)", listOf("ne", "en"))
    private val enNe = card("indictrans2-en-indic-int8", "IndicTrans2 (EN→Indic, int8)", listOf("ne", "en"))

    private val stt = card("whisper-base", "Whisper (multilingual)", listOf("ru", "en", "ne"))
        .copy(category = ModelCategory.STT)

    private val catalog = listOf(enRu, ruEn, stt, neEn, enNe)

    private val installedAll = listOf(
        installed("firefox-bergamot-ru-en", "Firefox Translations (RU→EN)", listOf("ru", "en")),
        installed("firefox-bergamot-en-ru", "Firefox Translations (EN→RU)", listOf("ru", "en")),
        installed("indictrans2-indic-en-int8", "IndicTrans2 (Indic→EN, int8)", listOf("ne", "en")),
        installed("indictrans2-en-indic-int8", "IndicTrans2 (EN→Indic, int8)", listOf("ne", "en"))
    )

    @Test
    fun `ru to en without models maps ru-en card with label`() {
        val chips = CatalogPriority.requiredChips(catalog, emptyList(), "ru", "en")
        assertEquals(mapOf("firefox-bergamot-ru-en" to "RU→EN"), chips)

        val sorted = CatalogPriority.prioritize(catalog, chips)
        assertEquals("firefox-bergamot-ru-en", sorted.first().id)
        // Порядок остальных карточек не меняется.
        assertEquals(listOf(enRu, stt, neEn, enNe), sorted.drop(1))
    }

    @Test
    fun `ru to ne maps two cards in chain order`() {
        val chips = CatalogPriority.requiredChips(catalog, emptyList(), "ru", "ne")
        assertEquals(
            listOf("firefox-bergamot-ru-en", "indictrans2-en-indic-int8"),
            chips.keys.toList()
        )
        assertEquals(listOf("RU→NE", "RU→NE"), chips.values.toList())

        val sorted = CatalogPriority.prioritize(catalog, chips)
        assertEquals(
            listOf("firefox-bergamot-ru-en", "indictrans2-en-indic-int8"),
            sorted.take(2).map { it.id }
        )
        assertEquals(listOf(enRu, stt, neEn), sorted.drop(2))
    }

    @Test
    fun `fully installed chain gives empty map and keeps catalog order`() {
        val chips = CatalogPriority.requiredChips(catalog, installedAll, "ru", "ne")
        assertTrue(chips.isEmpty())
        assertEquals(catalog, CatalogPriority.prioritize(catalog, chips))
    }

    @Test
    fun `invalid same-language direction gives empty map`() {
        assertTrue(CatalogPriority.requiredChips(catalog, emptyList(), "ru", "ru").isEmpty())
    }

    @Test
    fun `hidden cards are not offered as chips`() {
        // issue #55: скрытая карточка остаётся в каталоге, но чипы её не предлагают.
        val hiddenCatalog = catalog.map {
            if (it.id == ruEn.id) it.copy(hidden = true) else it
        }
        assertTrue(CatalogPriority.requiredChips(hiddenCatalog, emptyList(), "ru", "en").isEmpty())

        // Скрыто одно из звеньев цепочки ru→ne (en→ne): каталог цепочку
        // не покрывает — чипов нет.
        val hiddenFromHub = catalog.map {
            if (it.id == enNe.id) it.copy(hidden = true) else it
        }
        assertTrue(
            CatalogPriority.requiredChips(hiddenFromHub, emptyList(), "ru", "ne").isEmpty()
        )
    }

    private fun card(id: String, displayName: String, languages: List<String>): ModelCard =
        ModelCard(
            id = id,
            category = ModelCategory.TRANSLATION,
            displayName = displayName,
            description = "",
            runtime = RuntimeType.MARIAN_SLIMT,
            sizeBytes = 50_000_000L,
            ramRequiredMb = 0,
            languages = languages,
            quality = 4,
            speed = 5,
            url = "",
            urls = emptyList(),
            sha256 = null,
            license = "MIT",
            minAndroidSdk = 26,
            recommendedRamMb = 0
        )

    private fun installed(id: String, displayName: String, languages: List<String>): InstalledModel =
        InstalledModel(
            id = id,
            category = ModelCategory.TRANSLATION,
            displayName = displayName,
            description = "",
            runtime = RuntimeType.MARIAN_SLIMT,
            sizeBytes = 0,
            ramRequiredMb = 0,
            languages = languages,
            quality = 4,
            speed = 5,
            url = "",
            urls = emptyList(),
            sha256 = null,
            license = "MIT",
            minAndroidSdk = 26,
            recommendedRamMb = 0,
            status = ModelStatus.READY,
            progress = 100,
            filePath = "/tmp/$id",
            bytesDone = 0,
            isBuiltin = true
        )
}
