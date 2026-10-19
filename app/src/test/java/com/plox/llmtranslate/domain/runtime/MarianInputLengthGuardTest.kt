package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.model.RuntimeError
import com.plox.llmtranslate.domain.model.userMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-тесты guard'а длины входа Marian (ревью #79): вход энкодера длиннее
 * лимита позиционных эмбеддингов opus-mt (512) должен давать явную
 * [RuntimeError.InferenceFailed] с русским сообщением, а не падение
 * ONNX Runtime внутри run.
 */
class MarianInputLengthGuardTest {

    private val runtime = MarianOnnxRuntime(FakeModelRetentionProvider(keep = true))

    @Test
    fun `input up to limit passes`() {
        assertNull(runtime.inputLengthError(0))
        assertNull(runtime.inputLengthError(1))
        assertNull(runtime.inputLengthError(512))
    }

    @Test
    fun `input over limit gives explicit inference error with russian message`() {
        val error = runtime.inputLengthError(513)
        assertNotNull(error)
        error as RuntimeError.InferenceFailed
        assertEquals("marian", error.runtime)
        assertTrue(
            "сообщение должно описывать слишком длинное предложение: ${error.reason}",
            error.reason.contains("слишком длинное", ignoreCase = true)
        )
        assertTrue(
            "userMessage обязан оставаться русским: ${error.userMessage()}",
            error.userMessage().contains("слишком длинное", ignoreCase = true)
        )
    }
}
