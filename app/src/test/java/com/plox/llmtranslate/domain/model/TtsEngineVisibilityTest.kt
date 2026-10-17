package com.plox.llmtranslate.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Тесты видимости движков TTS в блоке «Движок синтеза речи» настроек (issue #55):
 * скрытые карточки каталога (hidden) движки не предлагают, возврат — правка каталога.
 */
class TtsEngineVisibilityTest {

    private val systemCard = card("tts-system", RuntimeType.SYSTEM_TTS)
    private val rhvoiceCard = card("tts-rhvoice", RuntimeType.RHVOICE, hidden = true)
    private val espeakCard = card("tts-espeak", RuntimeType.ESPEAK, hidden = true)

    private val catalog = listOf(systemCard, rhvoiceCard, espeakCard)

    @Test
    fun `hidden engine cards are not offered`() {
        assertEquals(
            listOf(TtsEngines.SYSTEM),
            visibleTtsEngineIds(catalog, TtsEngines.SYSTEM)
        )
    }

    @Test
    fun `selected hidden engine stays visible`() {
        assertEquals(
            listOf(TtsEngines.SYSTEM, TtsEngines.RHVOICE),
            visibleTtsEngineIds(catalog, TtsEngines.RHVOICE)
        )
    }

    @Test
    fun `visible cards keep all engines offered`() {
        val visibleCatalog = catalog.map { it.copy(hidden = false) }
        assertEquals(
            listOf(TtsEngines.SYSTEM, TtsEngines.RHVOICE, TtsEngines.ESPEAK),
            visibleTtsEngineIds(visibleCatalog, TtsEngines.SYSTEM)
        )
    }

    @Test
    fun `catalog without engine cards offers all engines`() {
        assertEquals(
            listOf(TtsEngines.SYSTEM, TtsEngines.RHVOICE, TtsEngines.ESPEAK),
            visibleTtsEngineIds(listOf(card("translation-1", RuntimeType.MARIAN_SLIMT)), TtsEngines.SYSTEM)
        )
    }

    @Test
    fun `system engine is never hidden`() {
        val result = visibleTtsEngineIds(
            listOf(systemCard.copy(hidden = true), rhvoiceCard, espeakCard),
            TtsEngines.SYSTEM
        )
        assertEquals(listOf(TtsEngines.SYSTEM), result)
        assertFalse(TtsEngines.SYSTEM !in result)
    }

    private fun card(
        id: String,
        runtime: RuntimeType,
        hidden: Boolean = false
    ): ModelCard =
        ModelCard(
            id = id,
            category = ModelCategory.TTS,
            displayName = id,
            description = "",
            runtime = runtime,
            sizeBytes = 0,
            ramRequiredMb = 0,
            languages = emptyList(),
            quality = 0,
            speed = 0,
            url = "",
            urls = emptyList(),
            sha256 = null,
            license = "MIT",
            minAndroidSdk = 26,
            recommendedRamMb = 0,
            hidden = hidden
        )
}
