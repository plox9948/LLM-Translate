package com.plox.llmtranslate.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты автовыбора модели перевода по направлению и fallback через английский.
 */
class TranslationModelSelectorTest {

    // Модели, повторяющие каталог (models_catalog.json).
    private val ruEn = model("firefox-bergamot-ru-en", "Firefox Translations (RU→EN)", listOf("ru", "en"))
    private val enRu = model("firefox-bergamot-en-ru", "Firefox Translations (EN→RU)", listOf("ru", "en"))
    private val neEn = model("indictrans2-indic-en-int8", "IndicTrans2 (Indic→EN, int8)", listOf("ne", "en"))
    private val enNe = model("indictrans2-en-indic-int8", "IndicTrans2 (EN→Indic, int8)", listOf("ne", "en"))
    private val all = listOf(ruEn, enRu, neEn, enNe)

    @Test
    fun `direction from arrow in display name`() {
        assertEquals("ru" to "en", TranslationModelSelector.directionOf(ruEn))
        assertEquals("en" to "ru", TranslationModelSelector.directionOf(enRu))
    }

    @Test
    fun `direction for indictrans via id`() {
        assertEquals("ne" to "en", TranslationModelSelector.directionOf(neEn))
        assertEquals("en" to "ne", TranslationModelSelector.directionOf(enNe))
    }

    @Test
    fun `direction from id pair adjacency without arrow in name`() {
        val noArrow = model("firefox-bergamot-ru-en", "Firefox Translations pack", listOf("ru", "en"))
        assertEquals("ru" to "en", TranslationModelSelector.directionOf(noArrow))
        val noArrowEn = model("firefox-bergamot-en-ru", "Firefox Translations pack", listOf("ru", "en"))
        assertEquals("en" to "ru", TranslationModelSelector.directionOf(noArrowEn))
    }

    @Test
    fun `direct chain for supported direction`() {
        assertEquals(listOf(ruEn), TranslationModelSelector.selectChain(all, "ru", "en"))
        assertEquals(listOf(enRu), TranslationModelSelector.selectChain(all, "en", "ru"))
        assertEquals(listOf(neEn), TranslationModelSelector.selectChain(all, "ne", "en"))
        assertEquals(listOf(enNe), TranslationModelSelector.selectChain(all, "en", "ne"))
    }

    @Test
    fun `fallback via english for nepali to russian`() {
        assertEquals(listOf(neEn, enRu), TranslationModelSelector.selectChain(all, "ne", "ru"))
    }

    @Test
    fun `fallback via english for russian to nepali`() {
        assertEquals(listOf(ruEn, enNe), TranslationModelSelector.selectChain(all, "ru", "ne"))
    }

    @Test
    fun `returns null for same language`() {
        assertNull(TranslationModelSelector.selectChain(all, "ru", "ru"))
    }

    @Test
    fun `returns null when no model matches`() {
        val onlyRuEn = listOf(ruEn)
        assertNull(TranslationModelSelector.selectChain(onlyRuEn, "ne", "ru"))
    }

    // --- issue #33: подбор недостающих звеньев цепочки из каталога ---

    private val catalogAll = listOf(
        card("firefox-bergamot-ru-en", "Firefox Translations (RU→EN)", listOf("ru", "en"), 50_000_000L),
        card("firefox-bergamot-en-ru", "Firefox Translations (EN→RU)", listOf("ru", "en"), 50_000_000L),
        card("indictrans2-indic-en-int8", "IndicTrans2 (Indic→EN, int8)", listOf("ne", "en"), 300_000_000L),
        card("indictrans2-en-indic-int8", "IndicTrans2 (EN→Indic, int8)", listOf("ne", "en"), 300_000_000L)
    )

    @Test
    fun `direction of catalog card`() {
        assertEquals("ru" to "en", TranslationModelSelector.directionOf(catalogAll[0]))
        assertEquals("en" to "ne", TranslationModelSelector.directionOf(catalogAll[3]))
    }

    @Test
    fun `missing chain ru to en is one slimt card`() {
        val missing = TranslationModelSelector.missingChain(catalogAll, emptyList(), "ru", "en")
        assertEquals(listOf(catalogAll[0]), missing)
    }

    @Test
    fun `missing chain ru to ne is two cards via hub`() {
        val missing = TranslationModelSelector.missingChain(catalogAll, emptyList(), "ru", "ne")
        assertEquals(listOf(catalogAll[0], catalogAll[3]), missing)
    }

    @Test
    fun `missing chain excludes installed links`() {
        val missing = TranslationModelSelector.missingChain(catalogAll, listOf(ruEn), "ru", "ne")
        assertEquals(listOf(catalogAll[3]), missing)
    }

    @Test
    fun `missing chain empty when chain fully installed`() {
        val installed = listOf(ruEn, enRu, neEn, enNe)
        assertTrue(TranslationModelSelector.missingChain(catalogAll, installed, "ru", "ne").isEmpty())
        assertTrue(TranslationModelSelector.missingChain(catalogAll, installed, "ru", "en").isEmpty())
    }

    @Test
    fun `missing chain empty when catalog does not cover direction`() {
        val noIndic = listOf(catalogAll[0], catalogAll[1])
        assertTrue(TranslationModelSelector.missingChain(noIndic, emptyList(), "ru", "ne").isEmpty())
    }

    @Test
    fun `missing chain offers link when other link covered by imported model`() {
        // Ревью: звено ru→en закрыто ИМПОРТИРОВАННОЙ моделью (её id нет в
        // каталоге) — каталог не покрывает это звено карточкой, но раньше
        // отсутствие карточки делало всю цепочку «непокрываемой» и реально
        // недостающая карточка en→ne не предлагалась.
        val importedRuEn = model("imported_ru_en", "Русский → английский", listOf("ru", "en"))
            .copy(isBuiltin = false)
        val noRuEnCards = listOf(catalogAll[1], catalogAll[2], catalogAll[3])
        val missing = TranslationModelSelector.missingChain(noRuEnCards, listOf(importedRuEn), "ru", "ne")
        assertEquals(listOf(catalogAll[3]), missing)
    }

    @Test
    fun `missing chain skips hidden catalog cards`() {
        // Ревью: скрытые (hidden) карточки не предлагаются к скачиванию.
        val hiddenIndic = catalogAll.map {
            if (it.id == "indictrans2-en-indic-int8") it.copy(hidden = true) else it
        }
        val missing = TranslationModelSelector.missingChain(hiddenIndic, listOf(ruEn), "ru", "ne")
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `missing chain empty for same language`() {
        assertTrue(TranslationModelSelector.missingChain(catalogAll, emptyList(), "ru", "ru").isEmpty())
    }

    private fun card(
        id: String,
        displayName: String,
        languages: List<String>,
        sizeBytes: Long
    ): ModelCard = ModelCard(
        id = id,
        category = ModelCategory.TRANSLATION,
        displayName = displayName,
        description = "",
        runtime = RuntimeType.MARIAN_SLIMT,
        sizeBytes = sizeBytes,
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

    private fun model(
        id: String,
        displayName: String,
        languages: List<String>
    ): InstalledModel = InstalledModel(
        id = id,
        category = ModelCategory.TRANSLATION,
        displayName = displayName,
        description = "",
        runtime = RuntimeType.MARIAN_ONNX,
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
