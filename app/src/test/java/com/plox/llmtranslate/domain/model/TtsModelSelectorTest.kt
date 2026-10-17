package com.plox.llmtranslate.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `default card ids for ru en ne`() {
        assertEquals("tts-ru-piper-ruslan", TtsModelSelector.defaultCardId("ru"))
        assertEquals("tts-en-piper-ryan", TtsModelSelector.defaultCardId("en"))
        assertEquals("tts-ne-piper-google", TtsModelSelector.defaultCardId("ne"))
    }

    @Test
    fun `no default card for other languages`() {
        assertNull(TtsModelSelector.defaultCardId("de"))
        assertNull(TtsModelSelector.defaultCardId(""))
    }
}
