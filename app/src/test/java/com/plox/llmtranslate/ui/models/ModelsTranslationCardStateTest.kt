package com.plox.llmtranslate.ui.models

import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-тесты чистой функции [translationCardState] (issue #96): чип
 * собственного направления на TRANSLATION-карточках (модели односторонние,
 * обратного чипа нет) и подсветка активного чипа по фактическому подбору
 * рантайма — автоподбор по направлению и явные назначения
 * «направление → модель».
 */
class ModelsTranslationCardStateTest {

    private fun card(
        id: String,
        displayName: String,
        languages: List<String>,
        category: ModelCategory = ModelCategory.TRANSLATION
    ) = ModelCard(
        id = id,
        category = category,
        displayName = displayName,
        description = "",
        runtime = RuntimeType.MARIAN_SLIMT,
        sizeBytes = 1000L,
        ramRequiredMb = 100,
        languages = languages,
        quality = 4,
        speed = 5,
        url = "",
        urls = emptyList(),
        sha256 = null,
        license = "MIT",
        minAndroidSdk = 26,
        recommendedRamMb = 0,
        hidden = false
    )

    private fun installed(id: String, displayName: String, languages: List<String>) =
        InstalledModel(
            id = id,
            category = ModelCategory.TRANSLATION,
            displayName = displayName,
            description = "",
            runtime = RuntimeType.MARIAN_SLIMT,
            sizeBytes = 100,
            ramRequiredMb = 100,
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
            filePath = "/models/$id",
            bytesDone = 100,
            isBuiltin = true
        )

    private val ruEnCard = card("slimt-ru-en", "Firefox Translations (RU→EN)", listOf("ru", "en"))
    private val ruEnModel = installed("slimt-ru-en", "Firefox Translations (RU→EN)", listOf("ru", "en"))
    private val enRuModel = installed("slimt-en-ru", "Firefox Translations (EN→RU)", listOf("ru", "en"))
    private val readyModels = listOf(ruEnModel, enRuModel)
    private val ruEnAltCard = card("slimt-ru-en-alt", "Firefox Translations (RU→EN) Alt", listOf("ru", "en"))
    private val ruEnAltModel = installed("slimt-ru-en-alt", "Firefox Translations (RU→EN) Alt", listOf("ru", "en"))

    @Test
    fun `translation card exposes only its own direction as chip`() {
        val state = translationCardState(
            card = ruEnCard,
            installedReady = true,
            readyTranslationModels = readyModels,
            assignments = emptyMap()
        )
        assertEquals(
            listOf("ru" to "en"),
            state.chips
        )
    }

    @Test
    fun `auto-selected card has its own direction chip active`() {
        // Без назначений RU→EN обслуживает эта модель — её чип RU→EN активен.
        val state = translationCardState(
            card = ruEnCard,
            installedReady = true,
            readyTranslationModels = readyModels,
            assignments = emptyMap()
        )
        assertEquals(setOf("ru" to "en"), state.activeDirections)
    }

    @Test
    fun `assignment to another direction does not affect the own chip`() {
        // Явное назначение EN→RU этой модели не относится к её чипу RU→EN:
        // чип собственного направления остаётся активен по автоподбору.
        val state = translationCardState(
            card = ruEnCard,
            installedReady = true,
            readyTranslationModels = readyModels,
            assignments = mapOf("en>ru" to "slimt-ru-en")
        )
        assertEquals(setOf("ru" to "en"), state.activeDirections)
    }

    @Test
    fun `assignment to another model removes the chip highlight`() {
        // Назначение RU→EN другой модели снимает подсветку с автокарточки:
        // эффективная цепочка RU→EN больше не проходит через эту модель.
        val state = translationCardState(
            card = ruEnCard,
            installedReady = true,
            readyTranslationModels = readyModels,
            assignments = mapOf("ru>en" to "slimt-en-ru")
        )
        assertTrue(state.activeDirections.isEmpty())
    }

    @Test
    fun `second card for the same direction wins only when explicitly assigned`() {
        // Две карты одного направления RU→EN: автоподбор выбирает первую,
        // её чип активен, у второй — нет.
        val readyWithAlt = listOf(ruEnModel, ruEnAltModel)
        val auto = translationCardState(
            card = ruEnCard,
            installedReady = true,
            readyTranslationModels = readyWithAlt,
            assignments = emptyMap()
        )
        assertEquals(setOf("ru" to "en"), auto.activeDirections)
        val autoAlt = translationCardState(
            card = ruEnAltCard,
            installedReady = true,
            readyTranslationModels = readyWithAlt,
            assignments = emptyMap()
        )
        assertTrue(autoAlt.activeDirections.isEmpty())
        // Явное назначение второй модели переносит подсветку на её чип,
        // чип первой становится неактивен (эффективная цепочка — назначение).
        val assigned = translationCardState(
            card = ruEnAltCard,
            installedReady = true,
            readyTranslationModels = readyWithAlt,
            assignments = mapOf("ru>en" to "slimt-ru-en-alt")
        )
        assertEquals(setOf("ru" to "en"), assigned.activeDirections)
        val demoted = translationCardState(
            card = ruEnCard,
            installedReady = true,
            readyTranslationModels = readyWithAlt,
            assignments = mapOf("ru>en" to "slimt-ru-en-alt")
        )
        assertTrue(demoted.activeDirections.isEmpty())
    }

    @Test
    fun `assignment to uninstalled model keeps auto highlight`() {
        val state = translationCardState(
            card = ruEnCard,
            installedReady = true,
            readyTranslationModels = readyModels,
            assignments = mapOf("ru>en" to "ghost-model")
        )
        assertEquals(setOf("ru" to "en"), state.activeDirections)
    }

    @Test
    fun `no chips when direction is not derivable`() {
        // Имя/описание без стрелки и без пары кодов в id — направление
        // не определено, чипов нет.
        val state = translationCardState(
            card = card("generic-pack", "Универсальный пакет", listOf("ru", "en")),
            installedReady = true,
            readyTranslationModels = readyModels,
            assignments = emptyMap()
        )
        assertTrue(state.chips.isEmpty())
        assertTrue(state.activeDirections.isEmpty())
    }

    @Test
    fun `no chips when languages are not exactly the direction pair`() {
        val state = translationCardState(
            card = card("slimt-ru-en-3", "Firefox Translations (RU→EN)", listOf("ru", "en", "ne")),
            installedReady = true,
            readyTranslationModels = readyModels,
            assignments = emptyMap()
        )
        assertTrue(state.chips.isEmpty())
    }

    @Test
    fun `not ready installed card has no chips`() {
        val state = translationCardState(
            card = ruEnCard,
            installedReady = false,
            readyTranslationModels = readyModels,
            assignments = emptyMap()
        )
        assertTrue(state.chips.isEmpty())
        assertTrue(state.activeDirections.isEmpty())
    }

    @Test
    fun `non-translation card returns empty state`() {
        val state = translationCardState(
            card = card("piper-ru", "Piper (RU)", listOf("ru"), ModelCategory.TTS),
            installedReady = true,
            readyTranslationModels = readyModels,
            assignments = emptyMap()
        )
        assertTrue(state.chips.isEmpty())
        assertTrue(state.activeDirections.isEmpty())
    }
}
