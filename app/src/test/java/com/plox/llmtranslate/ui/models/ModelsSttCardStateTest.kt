package com.plox.llmtranslate.ui.models

import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit-тесты чистой функции [sttAssignedChipLanguages] (issue #96):
 * подсветка чипов «язык распознавания» следует цепочке выбора рантайма
 * [com.plox.llmtranslate.domain.model.SttModelSelector.select] —
 * автоподбор поддерживающей модели без назначения, перенос подсветки при
 * явном назначении, дефолт IndicConformer для «ne».
 */
class ModelsSttCardStateTest {

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
    fun `language without assignment highlights auto-selected supporting model`() {
        // «en» ничему не назначено — рантайм берёт первую готовую
        // поддерживающую модель; её чип подсвечен, чужой — нет.
        val ready = listOf(nv, whisper)
        assertEquals(
            setOf("en"),
            sttAssignedChipLanguages("stt-nemo-parakeet-en", listOf("en"), emptyMap(), ready)
        )
        assertEquals(
            emptySet<String>(),
            sttAssignedChipLanguages(
                "stt-whisper-small-multilingual",
                listOf("en"),
                emptyMap(),
                ready
            )
        )
    }

    @Test
    fun `explicit assignment to another model moves highlight`() {
        val ready = listOf(nv, whisper)
        val assignments = mapOf("en" to "stt-whisper-small-multilingual")
        assertEquals(
            setOf("en"),
            sttAssignedChipLanguages(
                "stt-whisper-small-multilingual",
                listOf("en"),
                assignments,
                ready
            )
        )
        assertEquals(
            emptySet<String>(),
            sttAssignedChipLanguages("stt-nemo-parakeet-en", listOf("en"), assignments, ready)
        )
    }

    @Test
    fun `ne default highlights indicconformer over other supporting model`() {
        // Без назначения для «ne» дефолт — IndicConformer, даже когда
        // поддерживающая язык модель стоит в списке раньше.
        val ready = listOf(whisper, indic)
        assertEquals(
            setOf("ne"),
            sttAssignedChipLanguages("stt-ne-indicconformer", listOf("ne"), emptyMap(), ready)
        )
        assertEquals(
            emptySet<String>(),
            sttAssignedChipLanguages(
                "stt-whisper-small-multilingual",
                listOf("ne"),
                emptyMap(),
                ready
            )
        )
    }

    @Test
    fun `explicit ne assignment overrides indicconformer highlight`() {
        val ready = listOf(whisper, indic)
        val assignments = mapOf("ne" to "stt-whisper-small-multilingual")
        assertEquals(
            setOf("ne"),
            sttAssignedChipLanguages(
                "stt-whisper-small-multilingual",
                listOf("ne"),
                assignments,
                ready
            )
        )
        assertEquals(
            emptySet<String>(),
            sttAssignedChipLanguages("stt-ne-indicconformer", listOf("ne"), assignments, ready)
        )
    }

    @Test
    fun `card outside ready models gets no highlight`() {
        // Карточка не установлена/не готова — её нет в readySttModels,
        // select подбирает другую модель.
        assertEquals(
            emptySet<String>(),
            sttAssignedChipLanguages("stt-gigaam-ru", listOf("ru"), emptyMap(), listOf(whisper))
        )
    }

    @Test
    fun `empty chips list returns empty set`() {
        assertEquals(
            emptySet<String>(),
            sttAssignedChipLanguages("stt-nemo-parakeet-en", emptyList(), emptyMap(), listOf(nv))
        )
    }
}
