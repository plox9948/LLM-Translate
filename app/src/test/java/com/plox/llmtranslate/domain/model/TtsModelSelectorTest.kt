package com.plox.llmtranslate.domain.model

import com.plox.llmtranslate.data.catalog.ModelCatalogParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты выбора TTS-движка по языку (issue #50).
 */
class TtsModelSelectorTest {

    private fun piperModel(id: String, language: String, ready: Boolean) = InstalledModel(
        id = id,
        category = ModelCategory.TTS,
        displayName = "Piper $id",
        description = "",
        runtime = RuntimeType.PIPER_ONNX,
        sizeBytes = 67_000_000,
        ramRequiredMb = 260,
        languages = listOf(language),
        quality = 4,
        speed = 5,
        url = "",
        urls = listOf("https://host/$id.tar.bz2"),
        sha256 = null,
        license = "GPL-3.0",
        minAndroidSdk = 26,
        recommendedRamMb = 2048,
        status = if (ready) ModelStatus.READY else ModelStatus.DOWNLOADING,
        progress = if (ready) 100 else 40,
        filePath = if (ready) "/models/PIPER_ONNX/$id" else null,
        bytesDone = 0,
        isBuiltin = true
    )

    private fun engineCard(id: String, runtime: RuntimeType) = InstalledModel(
        id = id,
        category = ModelCategory.TTS,
        displayName = id,
        description = "",
        runtime = runtime,
        sizeBytes = 0,
        ramRequiredMb = 0,
        languages = listOf("ru"),
        quality = 3,
        speed = 5,
        url = "",
        urls = emptyList(),
        sha256 = null,
        license = "GPL-3.0",
        minAndroidSdk = 26,
        recommendedRamMb = 0,
        status = ModelStatus.READY,
        progress = 100,
        filePath = null,
        bytesDone = 0,
        isBuiltin = true
    )

    @Test
    fun `assigned and ready model is selected`() {
        val installed = listOf(piperModel("tts-ru-piper-ruslan", "ru", ready = true))
        val choice = TtsModelSelector.select(
            "ru",
            mapOf("ru" to "tts-ru-piper-ruslan"),
            installed
        )
        assertEquals(TtsModelSelector.TtsChoice.Model("tts-ru-piper-ruslan"), choice)
    }

    @Test
    fun `assigned but not ready model falls back to system engine`() {
        val installed = listOf(piperModel("tts-ru-piper-ruslan", "ru", ready = false))
        val choice = TtsModelSelector.select(
            "ru",
            mapOf("ru" to "tts-ru-piper-ruslan"),
            installed
        )
        assertEquals(TtsModelSelector.TtsChoice.SystemEngine, choice)
    }

    @Test
    fun `assigned but missing model falls back to system engine`() {
        val choice = TtsModelSelector.select(
            "en",
            mapOf("en" to "tts-en-piper-ryan"),
            emptyList()
        )
        assertEquals(TtsModelSelector.TtsChoice.SystemEngine, choice)
    }

    @Test
    fun `no assignment gives system engine`() {
        val installed = listOf(piperModel("tts-en-piper-ryan", "en", ready = true))
        val choice = TtsModelSelector.select("en", emptyMap(), installed)
        assertEquals(TtsModelSelector.TtsChoice.SystemEngine, choice)
    }

    @Test
    fun `system sentinel gives system engine even with ready piper`() {
        // Issue #53: явное назначение системного TTS перебивает готовую Piper.
        val installed = listOf(piperModel("tts-ru-piper-ruslan", "ru", ready = true))
        val choice = TtsModelSelector.select(
            "ru",
            mapOf("ru" to TtsModelSelector.SYSTEM_SENTINEL),
            installed
        )
        assertEquals(TtsModelSelector.TtsChoice.SystemEngine, choice)
    }

    @Test
    fun `system sentinel value is literal system`() {
        assertEquals("system", TtsModelSelector.SYSTEM_SENTINEL)
    }

    @Test
    fun `sentinel of other language does not affect assigned model`() {
        // Сентинель другого языка не мешает выбору назначенной модели (ru).
        val installed = listOf(
            piperModel("tts-ru-piper-ruslan", "ru", ready = true),
            piperModel("tts-en-piper-ryan", "en", ready = true)
        )
        val choice = TtsModelSelector.select(
            "ru",
            mapOf("ru" to "tts-ru-piper-ruslan", "en" to TtsModelSelector.SYSTEM_SENTINEL),
            installed
        )
        assertEquals(TtsModelSelector.TtsChoice.Model("tts-ru-piper-ruslan"), choice)
    }

    @Test
    fun `engine shortcut cards are never selected as model`() {
        // Ярлык SYSTEM_TTS с id из assignments не должен давать Model-выбор.
        val installed = listOf(engineCard("tts-system", RuntimeType.SYSTEM_TTS))
        val choice = TtsModelSelector.select(
            "ru",
            mapOf("ru" to "tts-system"),
            installed
        )
        assertEquals(TtsModelSelector.TtsChoice.SystemEngine, choice)
    }

    /** Карточки поставляемого assets-каталога (проверки резервной константы). */
    private fun shippingCatalogCards(): List<ModelCard> {
        val catalogFile = listOf(
            java.io.File("src/main/assets/models_catalog.json"),
            java.io.File("app/src/main/assets/models_catalog.json")
        ).firstOrNull { it.isFile }
            ?: error("models_catalog.json не найден относительно рабочих каталогов теста")
        return ModelCatalogParser().parse(catalogFile.readText(Charsets.UTF_8))
    }

    @Test
    fun `default card ids for ru en ne`() {
        // Резервные id применяются только когда карточка есть в каталоге
        // (ревью #83) — тест опирается на поставляемый каталог.
        val cards = shippingCatalogCards()
        assertEquals("tts-ru-piper-ruslan", TtsModelSelector.defaultCardId("ru", cards))
        assertEquals("tts-en-piper-ryan", TtsModelSelector.defaultCardId("en", cards))
        assertEquals("tts-ne-piper-google", TtsModelSelector.defaultCardId("ne", cards))
    }

    @Test
    fun `no default card for other languages`() {
        assertNull(TtsModelSelector.defaultCardId("de"))
        assertNull(TtsModelSelector.defaultCardId(""))
    }

    /** Карточка каталога TTS с возможным default-языком. */
    private fun catalogCard(id: String, defaultFor: String?) = ModelCard(
        id = id,
        category = ModelCategory.TTS,
        displayName = id,
        description = "",
        runtime = RuntimeType.PIPER_ONNX,
        sizeBytes = 1L,
        ramRequiredMb = 0,
        languages = listOf("ru"),
        quality = 4,
        speed = 4,
        url = "",
        urls = emptyList(),
        sha256 = null,
        license = "",
        minAndroidSdk = 24,
        recommendedRamMb = 0,
        defaultForLanguage = defaultFor
    )

    @Test
    fun `catalog default overrides fallback constant`() {
        // default_for_language из каталога приоритетнее константы
        // DEFAULT_LANGUAGE_CARDS (ревью).
        val cards = listOf(catalogCard("tts-custom-ru", "ru"))
        assertEquals("tts-custom-ru", TtsModelSelector.defaultCardId("ru", cards))
    }

    @Test
    fun `fallback constant used when catalog has no default for language`() {
        // В каталоге default только для de — для ru применяется резерв,
        // но только потому, что карточка резервного id в каталоге есть.
        val cards = listOf(
            catalogCard("tts-custom-de", "de"),
            catalogCard("tts-ru-piper-ruslan", null),
            catalogCard("tts-en-piper-ryan", null),
            catalogCard("tts-ne-piper-google", null)
        )
        assertEquals("tts-ru-piper-ruslan", TtsModelSelector.defaultCardId("ru", cards))
        assertEquals("tts-custom-de", TtsModelSelector.defaultCardId("de", cards))
        assertNull(TtsModelSelector.defaultCardId("fr", cards))
    }

    @Test
    fun `fallback ignored when reserve card absent from catalog`() {
        // Ревью #83: резерв не должен ссылаться на id, которого нет среди
        // карточек переданного каталога (урезанный/пустой каталог — null).
        val cards = listOf(catalogCard("tts-custom-de", "de"))
        assertNull(TtsModelSelector.defaultCardId("ru", cards))
        assertNull(TtsModelSelector.defaultCardId("ru", emptyList()))
    }

    @Test
    fun `fallback ignores hidden or non-piper card with reserve id`() {
        // Карточка-резерв должна проходить те же фильтры доступности, что и
        // поиск по default_for_language (hidden и не-piper рантаймы — мимо).
        val hidden = listOf(catalogCard("tts-ru-piper-ruslan", null).copy(hidden = true))
        assertNull(TtsModelSelector.defaultCardId("ru", hidden))
        val wrongRuntime = listOf(
            catalogCard("tts-ru-piper-ruslan", null).copy(runtime = RuntimeType.SYSTEM_TTS)
        )
        assertNull(TtsModelSelector.defaultCardId("ru", wrongRuntime))
    }

    @Test
    fun `default language cards exist in shipping assets catalog as piper models`() {
        // Резервная карта DEFAULT_LANGUAGE_CARDS обязана ссылаться на реальные
        // Piper-карточки поставляемого каталога assets: иначе фолбэк
        // TtsPlaybackCoordinator предложит несуществующую модель.
        val cards = shippingCatalogCards()
        val piperIds = cards
            .filter { it.category == ModelCategory.TTS && it.runtime == RuntimeType.PIPER_ONNX }
            .map { it.id }
            .toSet()
        for ((language, id) in TtsModelSelector.DEFAULT_LANGUAGE_CARDS) {
            assertTrue(
                "Карточка $id (default для «$language») отсутствует среди Piper-карточек каталога",
                piperIds.contains(id)
            )
        }
    }
}
