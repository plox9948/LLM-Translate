package com.plox.llmtranslate.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Тесты автовыбора модели перевода по направлению и fallback через английский.
 */
class TranslationModelSelectorTest {

    // Модели, повторяющие каталог (models_catalog.json).
    private val ruEn = model("opus-mt-tiny-ru-en-int8", "Opus-MT Tiny (RU→EN, int8)", listOf("ru", "en"))
    private val enRu = model("opus-mt-tiny-en-ru-int8", "Opus-MT Tiny (EN→RU, int8)", listOf("ru", "en"))
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
