package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-тесты освобождения Piper-пучка при удалении/перескачивании (ревью #51):
 * [PiperTtsRuntime.releaseModel] не падает и оставляет пустым состояние
 * «загружено» без активной загрузки (чужой/отсутствующий id — no-op), а
 * [RuntimeRouter.releaseModel] для TTS-модели дёргает Piper-движок по id
 * вместо прежнего no-op.
 *
 * Полный цикл загрузки/освобождения OfflineTts требует нативную библиотеку
 * sherpa-onnx — здесь проверяется контракт разгрузки без инференса.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeModelReleaseTest {

    /** Piper-движок без загрузки: Context не используется до load(). */
    private fun piperRuntime() = PiperTtsRuntime(
        mockk(relaxed = true),
        CoroutineScope(UnconfinedTestDispatcher())
    )

    /** TTS-запись установленной Piper-модели. */
    private fun ttsModel(id: String) = InstalledModel(
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
        status = ModelStatus.READY,
        progress = 100,
        filePath = "/models/PIPER_ONNX/$id",
        bytesDone = 1L,
        isBuiltin = true
    )

    @Test
    fun `releaseModel without active load keeps loaded flow empty and does not crash`() =
        kotlinx.coroutines.runBlocking {
            val runtime = piperRuntime()
            assertTrue(runtime.loadedModels.value.isEmpty())

            runtime.releaseModel("tts-ru-piper-ruslan")
            // Повторный вызов (уже пусто) — тоже no-op.
            runtime.releaseModel("tts-ru-piper-ruslan")

            assertTrue(runtime.loadedModels.value.isEmpty())
        }

    @Test
    fun `router releaseModel for piper tts calls piper engine release by id`() =
        kotlinx.coroutines.runBlocking {
            val marian = mockk<MarianOnnxRuntime>(relaxed = true)
            val indic = mockk<IndicTransOnnxRuntime>(relaxed = true)
            val slimt = mockk<SlimtRuntime>(relaxed = true)
            val sherpa = mockk<SherpaSttEngine>(relaxed = true)
            val piper = mockk<PiperTtsRuntime>(relaxed = true)
            coEvery { piper.releaseModel(any()) } returns Unit

            val router = RuntimeRouter(marian, indic, slimt, sherpa, piper)
            router.releaseModel(ttsModel("tts-ru-piper-ruslan"))

            coVerify(exactly = 1) {
                piper.releaseModel("tts-ru-piper-ruslan")
            }
            coVerify(exactly = 0) { sherpa.releaseModel(any()) }
            coVerify(exactly = 0) { marian.releaseModel(any()) }
            coVerify(exactly = 0) { indic.releaseModel(any()) }
            coVerify(exactly = 0) { slimt.releaseModel(any()) }
        }

    @Test
    fun `router releaseModel keeps routing translation and stt to their engines`() =
        kotlinx.coroutines.runBlocking {
            val marian = mockk<MarianOnnxRuntime>(relaxed = true)
            val indic = mockk<IndicTransOnnxRuntime>(relaxed = true)
            val slimt = mockk<SlimtRuntime>(relaxed = true)
            val sherpa = mockk<SherpaSttEngine>(relaxed = true)
            val piper = mockk<PiperTtsRuntime>(relaxed = true)

            val router = RuntimeRouter(marian, indic, slimt, sherpa, piper)
            router.releaseModel(ttsModel("trans-ru-en").copy(
                category = ModelCategory.TRANSLATION,
                runtime = RuntimeType.MARIAN_SLIMT
            ))
            router.releaseModel(ttsModel("stt-ru-gigaam").copy(
                category = ModelCategory.STT,
                runtime = RuntimeType.SHERPA
            ))

            coVerify(exactly = 1) { slimt.releaseModel("trans-ru-en") }
            coVerify(exactly = 1) { sherpa.releaseModel("stt-ru-gigaam") }
            coVerify(exactly = 0) { piper.releaseModel(any()) }
        }
}
