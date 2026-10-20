package com.plox.llmtranslate.ui.models

import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.RuntimeType
import com.plox.llmtranslate.domain.model.TtsEngines
import com.plox.llmtranslate.domain.model.TtsModelSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-тесты чистой функции [ttsCardState] (issues #50, #53): подсветка
 * «выбрана» для Piper/Системного TTS, чипы языков озвучивания и их
 * доступность, подсветка радиокнопки глобального движка.
 */
class ModelsTtsCardStateTest {

    private fun card(
        id: String,
        category: ModelCategory,
        runtime: RuntimeType,
        languages: List<String> = listOf("ru")
    ) = ModelCard(
        id = id,
        category = category,
        displayName = id,
        description = "",
        runtime = runtime,
        sizeBytes = 1000L,
        ramRequiredMb = 10,
        languages = languages,
        quality = 4,
        speed = 4,
        url = "",
        urls = emptyList(),
        sha256 = null,
        license = "MIT",
        minAndroidSdk = 24,
        recommendedRamMb = 0,
        hidden = false
    )

    private val piper = card("piper-ru", ModelCategory.TTS, RuntimeType.PIPER_ONNX)
    private val system =
        card("tts-system", ModelCategory.TTS, RuntimeType.SYSTEM_TTS, listOf("ru", "en"))

    @Test
    fun `non-tts card returns default state`() {
        val state = ttsCardState(
            card = card("stt-1", ModelCategory.STT, RuntimeType.SHERPA),
            installedReady = true,
            ttsEngine = TtsEngines.SYSTEM,
            ttsByLanguage = mapOf("ru" to "stt-1")
        )
        assertFalse(state.selected)
        assertEquals(emptyList<String>(), state.chips)
        assertFalse(state.chipsEnabled)
        assertFalse(state.engineSelected)
        assertTrue(state.assignedLanguages.isEmpty())
    }

    @Test
    fun `piper ready and assigned to own chip language is selected`() {
        val state = ttsCardState(
            card = piper,
            installedReady = true,
            ttsEngine = TtsEngines.SYSTEM,
            ttsByLanguage = mapOf("ru" to "piper-ru")
        )
        assertTrue(state.selected)
        assertEquals(listOf("ru"), state.chips)
        assertTrue(state.chipsEnabled)
        assertFalse(state.engineSelected)
        assertEquals(setOf("ru"), state.assignedLanguages)
    }

    @Test
    fun `piper ready but not assigned is not selected`() {
        val state = ttsCardState(
            card = piper,
            installedReady = true,
            ttsEngine = TtsEngines.RHVOICE,
            ttsByLanguage = mapOf("en" to "other-piper")
        )
        assertFalse(state.selected)
        assertTrue(state.chipsEnabled)
        assertTrue(state.assignedLanguages.isEmpty())
    }

    @Test
    fun `piper not installed - chips disabled and no assigned highlight`() {
        // Осознанная семантика: selected считается по назначению без учёта
        // готовности (устаревшее назначение из DataStore), но чипы и
        // подсветка языков требуют installedReady.
        val state = ttsCardState(
            card = piper,
            installedReady = false,
            ttsEngine = TtsEngines.SYSTEM,
            ttsByLanguage = mapOf("ru" to "piper-ru")
        )
        assertTrue(state.selected)
        assertFalse(state.chipsEnabled)
        assertTrue(state.assignedLanguages.isEmpty())
    }

    @Test
    fun `system tts selected via unassigned languages regardless of engine`() {
        val state = ttsCardState(
            card = system,
            installedReady = false,
            ttsEngine = TtsEngines.RHVOICE,
            ttsByLanguage = emptyMap()
        )
        assertTrue(state.selected)
        assertEquals(listOf("ru", "en"), state.chips)
        // Чипы системной карточки кликабельны всегда — скачивать нечего.
        assertTrue(state.chipsEnabled)
        assertFalse(state.engineSelected)
        assertEquals(setOf("ru", "en"), state.assignedLanguages)
    }

    @Test
    fun `system tts selected via explicit sentinel`() {
        val state = ttsCardState(
            card = system,
            installedReady = false,
            ttsEngine = TtsEngines.RHVOICE,
            ttsByLanguage = mapOf("ru" to TtsModelSelector.SYSTEM_SENTINEL, "en" to "piper-ru")
        )
        assertTrue(state.selected)
        assertEquals(setOf("ru"), state.assignedLanguages)
    }

    @Test
    fun `system tts not selected when all chip languages assigned to piper`() {
        val state = ttsCardState(
            card = system,
            installedReady = false,
            ttsEngine = TtsEngines.RHVOICE,
            ttsByLanguage = mapOf("ru" to "piper-ru", "en" to "piper-en")
        )
        assertFalse(state.selected)
        assertTrue(state.chipsEnabled)
        assertTrue(state.assignedLanguages.isEmpty())
    }

    @Test
    fun `engine radio highlight follows runtime engine id`() {
        val systemState = ttsCardState(
            card = system,
            installedReady = false,
            ttsEngine = TtsEngines.SYSTEM,
            ttsByLanguage = emptyMap()
        )
        assertTrue(systemState.engineSelected)

        // RHVOICE-ярлык: чипов нет, selected нет, но радиокнопка движка
        // подсвечена при выбранном в настройках RHVOICE.
        val rhvoiceState = ttsCardState(
            card = card("tts-rhvoice", ModelCategory.TTS, RuntimeType.RHVOICE),
            installedReady = false,
            ttsEngine = TtsEngines.RHVOICE,
            ttsByLanguage = emptyMap()
        )
        assertTrue(rhvoiceState.engineSelected)
        assertFalse(rhvoiceState.selected)
        assertEquals(emptyList<String>(), rhvoiceState.chips)
        assertFalse(rhvoiceState.chipsEnabled)
    }

    @Test
    fun `piper card never highlights engine radio`() {
        val state = ttsCardState(
            card = piper,
            installedReady = true,
            ttsEngine = TtsEngines.SYSTEM,
            ttsByLanguage = emptyMap()
        )
        assertFalse(state.engineSelected)
    }
}
