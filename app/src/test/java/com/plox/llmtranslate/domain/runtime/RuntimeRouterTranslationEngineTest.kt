package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.model.ModelFileNaming
import com.plox.llmtranslate.domain.model.RuntimeType
import io.mockk.mockk
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Unit-тесты выбора движка перевода в [RuntimeRouter.translationEngine]
 * (ревью #64): тип MARIAN_ONNX удалён из каталога, но остаётся маршрутом
 * для ИМПОРТИРУЕМЫХ ONNX-моделей Marian — импорт одиночного .onnx
 * ([ModelFileNaming.guessRuntime]) проставляет именно его, и роутер обязан
 * направлять такие модели в [MarianOnnxRuntime] явным кейсом, а не
 * «случайным» fallback-ответвлением.
 */
class RuntimeRouterTranslationEngineTest {

    private fun router(): Pair<RuntimeRouter, MarianOnnxRuntime> {
        val marian = mockk<MarianOnnxRuntime>(relaxed = true)
        val router = RuntimeRouter(
            marian,
            mockk<IndicTransOnnxRuntime>(relaxed = true),
            mockk<SlimtRuntime>(relaxed = true),
            mockk<SherpaSttEngine>(relaxed = true),
            mockk<PiperTtsRuntime>(relaxed = true),
            mockk(relaxed = true)
        )
        return router to marian
    }

    @Test
    fun `imported onnx model gets MARIAN_ONNX type and routes to marian engine`() {
        // Цепочка импорта: имя файла → guesses runtime → маршрут движка.
        val runtime = ModelFileNaming.guessRuntime("opus-mt-ru-en.onnx")
        assertSame(RuntimeType.MARIAN_ONNX, runtime)

        val (router, marian) = router()
        assertSame(marian, router.translationEngine(RuntimeType.MARIAN_ONNX))
    }

    @Test
    fun `catalog runtime types route to their engines`() {
        val marian = mockk<MarianOnnxRuntime>(relaxed = true)
        val indic = mockk<IndicTransOnnxRuntime>(relaxed = true)
        val slimt = mockk<SlimtRuntime>(relaxed = true)
        val router = RuntimeRouter(
            marian,
            indic,
            slimt,
            mockk<SherpaSttEngine>(relaxed = true),
            mockk<PiperTtsRuntime>(relaxed = true),
            mockk(relaxed = true)
        )
        assertSame(marian, router.translationEngine(RuntimeType.MARIAN_ONNX))
        assertSame(indic, router.translationEngine(RuntimeType.INDICTRANS_ONNX))
        assertSame(slimt, router.translationEngine(RuntimeType.MARIAN_SLIMT))
    }

    @Test
    fun `unknown runtime type falls back to marian engine`() {
        val (router, marian) = router()
        assertSame(marian, router.translationEngine(RuntimeType.SHERPA))
        assertSame(marian, router.translationEngine(RuntimeType.PIPER_ONNX))
    }
}
