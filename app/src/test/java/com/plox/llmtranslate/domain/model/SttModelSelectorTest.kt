package com.plox.llmtranslate.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты выбора STT-модели по языку (issues #28, #29):
 * цепочка «назначение → индик-фолбэк для ne → поддерживающая язык модель»,
 * для AUTO — мультаязычная (whisper в приоритете) либо первая Ready.
 * Глобальной активной STT-модели больше нет.
 */
class SttModelSelectorTest {

    private fun sttModel(
        id: String,
        languages: List<String>,
        runtime: RuntimeType = RuntimeType.SHERPA,
        ready: Boolean = true
    ) = InstalledModel(
        id = id,
        category = ModelCategory.STT,
        displayName = id,
        description = "",
        runtime = runtime,
        sizeBytes = 100,
        ramRequiredMb = 100,
        languages = languages,
        quality = 4,
        speed = 4,
        url = "",
        urls = emptyList(),
        sha256 = null,
        license = "Apache-2.0",
        minAndroidSdk = 26,
        recommendedRamMb = 0,
        status = if (ready) ModelStatus.READY else ModelStatus.DOWNLOADING,
        progress = 100,
        filePath = if (ready) "/models/SHERPA/$id" else null,
        bytesDone = 100,
        isBuiltin = true
    )

    private val whisper = sttModel(
        "stt-whisper-small-multilingual",
        languages = listOf("ru", "en", "ne")
    )
    private val indic = sttModel("stt-ne-indicconformer", languages = listOf("ne"))
    private val nv = sttModel("stt-nemo-parakeet-en", languages = listOf("en"))

    @Test
    fun `assigned model wins for its language`() {
        val result = SttModelSelector.select(
            language = "en",
            assignments = mapOf("en" to "stt-nemo-parakeet-en"),
            installed = listOf(whisper, indic, nv)
        )
        assertEquals(nv, result)
    }

    @Test
    fun `language without assignment falls back to supporting model`() {
        // Языку «en» ничего не назначено — подходит первая Ready-модель,
        // у которой languages содержит «en».
        val result = SttModelSelector.select(
            language = "en",
            assignments = emptyMap(),
            installed = listOf(indic, nv, whisper)
        )
        assertEquals(nv, result)
    }

    @Test
    fun `language supported only by a later model selects it`() {
        val result = SttModelSelector.select(
            language = "ru",
            assignments = emptyMap(),
            installed = listOf(indic, nv, whisper)
        )
        assertEquals(whisper, result)
    }

    @Test
    fun `ne default prefers installed indicconformer over other support`() {
        val result = SttModelSelector.select(
            language = "ne",
            assignments = emptyMap(),
            installed = listOf(whisper, indic)
        )
        assertEquals(indic, result)
    }

    @Test
    fun `explicit ne assignment overrides indicconformer default`() {
        val result = SttModelSelector.select(
            language = "ne",
            assignments = mapOf("ne" to "stt-whisper-small-multilingual"),
            installed = listOf(whisper, indic)
        )
        assertEquals(whisper, result)
    }

    @Test
    fun `assigned but not installed model falls through the chain`() {
        // Назначена удалённая (не установленная) модель: для ne срабатывает
        // индик-фолбэк, для ru — поддерживающая язык модель.
        val neResult = SttModelSelector.select(
            language = "ne",
            assignments = mapOf("ne" to "missing-model"),
            installed = listOf(whisper, indic)
        )
        assertEquals(indic, neResult)

        val ruResult = SttModelSelector.select(
            language = "ru",
            assignments = mapOf("ru" to "missing-model"),
            installed = listOf(whisper, indic)
        )
        assertEquals(whisper, ruResult)
    }

    @Test
    fun `assigned but not ready model is not selected`() {
        val downloading = sttModel("stt-nemo-parakeet-en", languages = listOf("en"), ready = false)
        val result = SttModelSelector.select(
            language = "en",
            assignments = mapOf("en" to "stt-nemo-parakeet-en"),
            installed = listOf(whisper, downloading)
        )
        assertEquals(whisper, result)
    }

    @Test
    fun `no model supporting the language returns null`() {
        val result = SttModelSelector.select(
            language = "ru",
            assignments = emptyMap(),
            installed = listOf(nv, indic)
        )
        assertNull(result)
    }

    @Test
    fun `auto prefers multilingual whisper model`() {
        // «ne» — монопалкая модель с languages из двух кодов (не whisper):
        // whisper всё равно приоритетнее.
        val otherMultilingual = sttModel(
            "stt-other-multilingual",
            languages = listOf("ru", "en")
        )
        val result = SttModelSelector.select(
            language = null,
            assignments = mapOf("ru" to "stt-nemo-parakeet-en"),
            installed = listOf(otherMultilingual, whisper, indic)
        )
        assertEquals(whisper, result)
    }

    @Test
    fun `auto without whisper picks first multilingual`() {
        val result = SttModelSelector.select(
            language = null,
            assignments = emptyMap(),
            installed = listOf(nv, indic, whisper)
        )
        assertEquals(whisper, result)
    }

    @Test
    fun `auto without multilingual picks first ready`() {
        val mono1 = sttModel("stt-mono-one", languages = listOf("en"))
        val mono2 = sttModel("stt-mono-two", languages = listOf("ne"))
        val result = SttModelSelector.select(
            language = null,
            assignments = emptyMap(),
            installed = listOf(mono1, mono2)
        )
        assertEquals(mono1, result)
    }

    @Test
    fun `auto skips models that are not ready`() {
        val downloadingWhisper = sttModel(
            "stt-whisper-large-multilingual",
            languages = listOf("ru", "en"),
            ready = false
        )
        val result = SttModelSelector.select(
            language = null,
            assignments = emptyMap(),
            installed = listOf(downloadingWhisper, nv)
        )
        assertEquals(nv, result)
    }

    @Test
    fun `empty installed list returns null`() {
        assertNull(
            SttModelSelector.select(
                language = "ru",
                assignments = mapOf("ru" to "stt-nemo-parakeet-en"),
                installed = emptyList()
            )
        )
        assertNull(
            SttModelSelector.select(
                language = null,
                assignments = emptyMap(),
                installed = emptyList()
            )
        )
    }

    @Test
    fun `indicconformer detection requires sherpa runtime and indic marker`() {
        assertTrue(SttModelSelector.isIndicConformer(indic))
        assertTrue(
            SttModelSelector.isIndicConformer(
                sttModel("custom_indicconformer_v2", languages = listOf("ne"))
            )
        )
        assertTrue(!SttModelSelector.isIndicConformer(nv))
        assertTrue(
            !SttModelSelector.isIndicConformer(
                indic.copy(runtime = RuntimeType.MARIAN_ONNX)
            )
        )
    }

    // ── Подбор недостающей карточки для памятки (issue #56) ──

    private fun sttCard(
        id: String,
        languages: List<String>,
        hidden: Boolean = false,
        runtime: RuntimeType = RuntimeType.SHERPA
    ) = ModelCard(
        id = id,
        category = ModelCategory.STT,
        displayName = id,
        description = "",
        runtime = runtime,
        sizeBytes = 100,
        ramRequiredMb = 100,
        languages = languages,
        quality = 4,
        speed = 4,
        url = "",
        urls = emptyList(),
        sha256 = null,
        license = "Apache-2.0",
        minAndroidSdk = 26,
        recommendedRamMb = 0,
        hidden = hidden
    )

    private val catalog = listOf(
        sttCard("stt-ru-gigaam", listOf("ru")),
        sttCard("stt-en-parakeet-110m", listOf("en")),
        sttCard("stt-ne-indicconformer", listOf("ne")),
        sttCard("stt-whisper-small-multilingual", listOf("ru", "en", "ne"), hidden = true)
    )

    @Test
    fun `missing card for language without any installed stt`() {
        val card = SttModelSelector.missingCard(
            catalog = catalog,
            assignments = emptyMap(),
            installed = emptyList(),
            language = "ru"
        )
        assertEquals("stt-ru-gigaam", card?.id)
    }

    @Test
    fun `missing card skips hidden catalog cards`() {
        // Язык поддерживают только скрытые карточки — в памятку ничего не добавляем.
        val hiddenOnly = listOf(
            sttCard("stt-whisper-small-multilingual", listOf("ru", "en", "ne"), hidden = true)
        )
        assertNull(
            SttModelSelector.missingCard(hiddenOnly, emptyMap(), emptyList(), "ru")
        )
    }

    @Test
    fun `missing card prefers indicconformer default for ne`() {
        val mixed = listOf(
            sttCard("stt-ne-other", listOf("ne")),
            sttCard("stt-ne-indicconformer", listOf("ne"))
        )
        val card = SttModelSelector.missingCard(mixed, emptyMap(), emptyList(), "ne")
        assertEquals("stt-ne-indicconformer", card?.id)
    }

    @Test
    fun `missing card prefers card assigned to the language`() {
        val card = SttModelSelector.missingCard(
            catalog = catalog,
            assignments = mapOf("ru" to "stt-en-parakeet-110m"),
            installed = emptyList(),
            language = "ru"
        )
        assertEquals("stt-en-parakeet-110m", card?.id)
    }

    @Test
    fun `no missing card when ready stt already covers the language`() {
        assertNull(SttModelSelector.missingCard(catalog, emptyMap(), listOf(whisper), "ru"))
        assertNull(SttModelSelector.missingCard(catalog, emptyMap(), listOf(indic), "ne"))
    }

    @Test
    fun `no missing card for auto language`() {
        assertNull(SttModelSelector.missingCard(catalog, emptyMap(), emptyList(), null))
    }
}
