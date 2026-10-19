package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.model.RuntimeError
import com.plox.llmtranslate.domain.model.userMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-тесты guard'а длины входа энкодера IndicTrans (ревью #83): вход
 * длиннее таблицы позиционных эмбеддингов модели (258, чанкинг
 * [planChunks] не гарантирует лимит для слов длиннее бюджета) должен
 * давать явную [RuntimeError.InferenceFailed], а не падение ONNX Runtime
 * внутри run.
 */
class IndicTransInputLengthGuardTest {

    private val runtime = IndicTransOnnxRuntime(FakeModelRetentionProvider(keep = true))

    @Test
    fun `input up to positional embedding limit passes`() {
        assertNull(runtime.inputLengthError(0))
        assertNull(runtime.inputLengthError(1))
        assertNull(runtime.inputLengthError(257))
        assertNull(runtime.inputLengthError(258))
    }

    @Test
    fun `input over limit gives explicit inference error with russian message`() {
        val error = runtime.inputLengthError(259)
        assertNotNull(error)
        error as RuntimeError.InferenceFailed
        assertEquals("indictrans", error.runtime)
        assertTrue(
            "сообщение должно описывать слишком длинный вход: ${error.reason}",
            error.reason.contains("слишком длинн", ignoreCase = true)
        )
        assertTrue(
            "userMessage обязан оставаться русским: ${error.userMessage()}",
            error.userMessage().contains("слишком длинн", ignoreCase = true)
        )
    }
}
