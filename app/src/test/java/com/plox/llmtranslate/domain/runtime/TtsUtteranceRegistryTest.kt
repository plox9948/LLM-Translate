package com.plox.llmtranslate.domain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-тести [TtsUtteranceRegistry] — реестра незавершённых озвучиваний TTS
 * (issue #40: при QUEUE_FLUSH-перехвате колбэк прежней фразы не приходил,
 * и вызывающий экран оставался с залипшим флагом «озвучиваем»).
 *
 * Сам [TtsRuntime] без Android не тестируется (TextToSpeech не создаётся на JVM),
 * поэтому проверяется чистая логика реестра: перехват прежнего колбэка при
 * старте новой фразы, однократная выдача по utteranceId, игнорирование
 * устаревших id и очистка при выгрузке движка.
 */
class TtsUtteranceRegistryTest {

    @Test
    fun `starting new utterance returns pending callback of intercepted phrase`() {
        val registry = TtsUtteranceRegistry()
        var oldInvoked = 0
        registry.start("a") { oldInvoked++ }

        val flushed = registry.start("b", null)

        // Перехваченная QUEUE_FLUSH фраза "a" отдаёт свой колбэк сразу.
        assertEquals(1, flushed.size)
        flushed.forEach { it.invoke() }
        assertEquals(1, oldInvoked)
    }

    @Test
    fun `intercepted utterance finish is null and current callback is kept`() {
        val registry = TtsUtteranceRegistry()
        val doneB = { }
        registry.start("a") { }
        registry.start("b", doneB)

        // Запаздывающий onDone старой фразы "a" не должен трогать текущую.
        assertNull(registry.finish("a"))
        assertSame(doneB, registry.finish("b"))
    }

    @Test
    fun `callback issued exactly once per utterance`() {
        val registry = TtsUtteranceRegistry()
        val doneA = { }
        registry.start("a", doneA)

        assertSame(doneA, registry.finish("a"))
        assertNull(registry.finish("a"))

        // Повторный старт той же фразы не возвращает уже выданный колбэк.
        assertTrue(registry.start("a", null).isEmpty())
    }

    @Test
    fun `finish of unknown or null utterance id is null`() {
        val registry = TtsUtteranceRegistry()
        val doneA = { }
        registry.start("a", doneA)

        assertNull(registry.finish(null))
        assertNull(registry.finish("unknown"))
        // Зарегистрированная фраза от промахов не пострадала.
        assertSame(doneA, registry.finish("a"))
    }

    @Test
    fun `start without callback still intercepts previous pending`() {
        val registry = TtsUtteranceRegistry()
        var oldInvoked = 0
        registry.start("a") { oldInvoked++ }

        val flushed = registry.start("b", null)
        flushed.forEach { it.invoke() }
        assertEquals(1, oldInvoked)
        assertNull(registry.finish("b"))
    }

    @Test
    fun `drain returns pending callbacks and empties the registry`() {
        val registry = TtsUtteranceRegistry()
        val doneA = { }
        registry.start("a", doneA)

        // Выгрузка движка: pending-колбэки изымаются атомарно и вызываются
        // вызывающим кодом (issue #40 — залипший флаг «озвучиваем»).
        assertSame(doneA, registry.drain().single())

        assertNull(registry.finish("a"))
        assertTrue(registry.start("b", null).isEmpty())
    }

    @Test
    fun `start always leaves at most one pending entry`() {
        val registry = TtsUtteranceRegistry()

        // Pending не накапливаются: каждый старт изымает прежний.
        assertTrue(registry.start("a", {}).isEmpty())
        assertEquals(1, registry.start("b", {}).size)
        assertEquals(1, registry.start("c", null).size)
        assertNull(registry.finish("a"))
        assertNull(registry.finish("b"))
        assertNull(registry.finish("c"))
    }
}
