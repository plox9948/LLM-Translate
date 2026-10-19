package com.plox.llmtranslate.domain.runtime

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Unit-тесты [RuntimeRouter.releaseAll] (ревью #83): исключение из
 * release() одного движка (включая Piper TTS, чей release стал suspend и
 * идёт через общий releaseSafely) не должно обрывать разгрузку остальных.
 */
class RuntimeRouterReleaseAllTest {

    private class Fixture {
        val marian = mockk<MarianOnnxRuntime>(relaxed = true)
        val indic = mockk<IndicTransOnnxRuntime>(relaxed = true)
        val slimt = mockk<SlimtRuntime>(relaxed = true)
        val sherpa = mockk<SherpaSttEngine>(relaxed = true)
        val piper = mockk<PiperTtsRuntime>(relaxed = true)
        val recorder = mockk<AudioRecorder>(relaxed = true)
        val router = RuntimeRouter(marian, indic, slimt, sherpa, piper, recorder)

        fun verifyAllReleased() {
            coVerify(exactly = 1) { marian.release() }
            coVerify(exactly = 1) { indic.release() }
            coVerify(exactly = 1) { slimt.release() }
            coVerify(exactly = 1) { sherpa.release() }
            coVerify(exactly = 1) { piper.release() }
            verify(exactly = 1) { recorder.cancel() }
        }
    }

    @Test
    fun `releaseAll continues after a throwing translation engine`() = runBlocking {
        val f = Fixture()
        coEvery { f.marian.release() } throws IllegalStateException("зависший мьютекс движка")

        f.router.releaseAll()

        f.verifyAllReleased()
    }

    @Test
    fun `releaseAll survives a throwing Piper release`() = runBlocking {
        val f = Fixture()
        // Piper — последний в обходе; ревью #83: release() стал suspend и
        // проходит через releaseSafely — его исключение обрабатывается в самом
        // releaseAll, обход не обрывается.
        coEvery { f.piper.release() } throws RuntimeException("ozvuchka stuck")

        f.router.releaseAll()

        f.verifyAllReleased()
    }

    @Test
    fun `releaseAll releases all engines on the normal path`() = runBlocking {
        val f = Fixture()
        f.router.releaseAll()
        f.verifyAllReleased()
    }
}
