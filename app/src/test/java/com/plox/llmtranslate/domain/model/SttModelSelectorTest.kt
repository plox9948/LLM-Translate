package com.plox.llmtranslate.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты выбора STT-модели по языку (issue #28):
 * цепочка «назначение → индик-фолбэк для ne → глобальная активная».
 */
class SttModelSelectorTest {

    private fun sttModel(
        id: String,
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
        languages = listOf("ru", "en", "ne"),
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

    private val global = sttModel("stt-whisper-small-multilingual")
    private val indic = sttModel("stt-ne-indicconformer")
    private val nv = sttModel("stt-nemo-parakeet-en")

    @Test
    fun `assigned model wins for its language`() {
        val result = SttModelSelector.select(
            language = "en",
            assignments = mapOf("en" to "stt-nemo-parakeet-en"),
            installed = listOf(global, indic, nv),
            globalActive = global
        )
        assertEquals(nv, result)
    }

    @Test
    fun `not assigned language falls back to global active`() {
        val result = SttModelSelector.select(
            language = "ru",
            assignments = emptyMap(),
            installed = listOf(global, indic),
            globalActive = global
        )
        assertEquals(global, result)
    }

    @Test
    fun `ne default prefers installed indicconformer over global`() {
        val result = SttModelSelector.select(
            language = "ne",
            assignments = emptyMap(),
            installed = listOf(global, indic),
            globalActive = global
        )
        assertEquals(indic, result)
    }

    @Test
    fun `explicit ne assignment overrides indicconformer default`() {
        val result = SttModelSelector.select(
            language = "ne",
            assignments = mapOf("ne" to "stt-whisper-small-multilingual"),
            installed = listOf(global, indic),
            globalActive = global
        )
        assertEquals(global, result)
    }

    @Test
    fun `assigned but not installed model falls through the chain`() {
        // Назначена удалённая (не установленная) модель — для ne срабатывает
        // индик-фолбэк, для других языков — глобальная.
        val neResult = SttModelSelector.select(
            language = "ne",
            assignments = mapOf("ne" to "missing-model"),
            installed = listOf(global, indic),
            globalActive = global
        )
        assertEquals(indic, neResult)

        val ruResult = SttModelSelector.select(
            language = "ru",
            assignments = mapOf("ru" to "missing-model"),
            installed = listOf(global, indic),
            globalActive = global
        )
        assertEquals(global, ruResult)
    }

    @Test
    fun `assigned but not ready model is not selected`() {
        val downloading = sttModel("stt-nemo-parakeet-en", ready = false)
        val result = SttModelSelector.select(
            language = "en",
            assignments = mapOf("en" to "stt-nemo-parakeet-en"),
            installed = listOf(global, downloading),
            globalActive = global
        )
        assertEquals(global, result)
    }

    @Test
    fun `auto language uses only global active`() {
        val result = SttModelSelector.select(
            language = null,
            assignments = mapOf("ru" to "stt-nemo-parakeet-en"),
            installed = listOf(global, indic, nv),
            globalActive = global
        )
        assertEquals(global, result)
    }

    @Test
    fun `nothing assigned and no global returns null`() {
        val result = SttModelSelector.select(
            language = "ru",
            assignments = emptyMap(),
            installed = listOf(nv),
            globalActive = null
        )
        assertNull(result)
    }

    @Test
    fun `indicconformer detection requires sherpa runtime and indic marker`() {
        assertTrue(SttModelSelector.isIndicConformer(indic))
        assertTrue(
            SttModelSelector.isIndicConformer(
                sttModel("custom_indicconformer_v2").copy(runtime = RuntimeType.SHERPA)
            )
        )
        assertTrue(!SttModelSelector.isIndicConformer(nv))
        assertTrue(
            !SttModelSelector.isIndicConformer(
                indic.copy(runtime = RuntimeType.MARIAN_ONNX)
            )
        )
    }
}
