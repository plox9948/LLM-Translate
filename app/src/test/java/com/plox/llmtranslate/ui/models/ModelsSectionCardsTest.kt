package com.plox.llmtranslate.ui.models

import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-тесты чистой логики [sectionCards] (ревью раунд 2, Н-5): синтетические
 * карточки для установленных вне каталога (импортных) моделей и сохранение
 * фильтра hidden для каталожных карточек.
 */
class ModelsSectionCardsTest {

    private fun card(id: String, hidden: Boolean = false) = ModelCard(
        id = id,
        category = ModelCategory.TTS,
        displayName = "Каталог $id",
        description = "",
        runtime = RuntimeType.PIPER_ONNX,
        sizeBytes = 1000L,
        ramRequiredMb = 10,
        languages = listOf("ru"),
        quality = 4,
        speed = 4,
        url = "",
        urls = listOf("https://example/$id.tar.bz2"),
        sha256 = null,
        license = "MIT",
        minAndroidSdk = 24,
        recommendedRamMb = 0,
        hidden = hidden
    )

    private fun installed(id: String, displayName: String, sizeBytes: Long) = InstalledModel(
        id = id,
        category = ModelCategory.TTS,
        displayName = displayName,
        description = "",
        runtime = RuntimeType.PIPER_ONNX,
        sizeBytes = sizeBytes,
        ramRequiredMb = 20,
        languages = listOf("ru"),
        quality = 3,
        speed = 3,
        url = "",
        urls = emptyList(),
        sha256 = null,
        license = "MIT",
        minAndroidSdk = 24,
        recommendedRamMb = 0,
        status = ModelStatus.READY,
        progress = 100,
        filePath = "/models/PIPER_ONNX/$id",
        bytesDone = sizeBytes,
        isBuiltin = false
    )

    @Test
    fun `installed model outside catalog gets synthetic card with fields from record`() {
        val result = sectionCards(
            catalog = listOf(card("tts-catalog")),
            installed = listOf(installed("imported-voice", "Импортный голос", 4321L))
        )

        val synthetic = result.firstOrNull { it.id == "imported-voice" }
        require(synthetic != null) { "Синтетическая карточка импортной модели не добавлена: $result" }
        assertEquals("Импортный голос", synthetic.displayName)
        assertEquals(4321L, synthetic.sizeBytes)
        assertFalse(synthetic.hidden)
        // Каталог остаётся на месте, дублей нет.
        assertEquals(
            listOf("tts-catalog", "imported-voice"),
            result.map { it.id }
        )
    }

    @Test
    fun `catalog card of installed model does not duplicate synthetic card`() {
        val result = sectionCards(
            catalog = listOf(card("tts-catalog")),
            installed = listOf(installed("tts-catalog", "Установленная из каталога", 999L))
        )
        assertEquals(1, result.count { it.id == "tts-catalog" })
    }

    @Test
    fun `hidden filter intact - hidden card shown only when installed`() {
        val withoutInstall = sectionCards(
            catalog = listOf(card("tts-hidden", hidden = true), card("tts-visible")),
            installed = emptyList()
        )
        assertEquals(listOf("tts-visible"), withoutInstall.map { it.id })

        val withInstall = sectionCards(
            catalog = listOf(card("tts-hidden", hidden = true), card("tts-visible")),
            installed = listOf(installed("tts-hidden", "Hidden", 1L))
        )
        assertTrue(withInstall.any { it.id == "tts-hidden" })
        // Синтетическая карточка для hidden-модели ИЗ каталога не создаётся.
        assertEquals(1, withInstall.count { it.id == "tts-hidden" })
    }
}
