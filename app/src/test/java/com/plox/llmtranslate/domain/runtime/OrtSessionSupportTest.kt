package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.model.RuntimeError
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Юнит-тесты общего ORT-каркаса загрузки/разгрузки (issue #83) без
 * нативного ONNX: поштучное закрытие сессий фейковыми [AutoCloseable],
 * перехват ошибок нативного слоя ([Error]) в [guardModelLoad], fail-fast
 * отсутствующих past-входов в [collectPastInputs] и верхняя оценка резерва
 * байтового бюджета [reserveIncomingBytes] (ревью аудита, итерация 2).
 */
class OrtSessionSupportTest {

    /** Фейковая сессия: считает close() и умеет бросать на нём. */
    private class FakeSession(private val failOnClose: Boolean = false) : AutoCloseable {
        var closed = false
            private set

        override fun close() {
            closed = true
            if (failOnClose) throw RuntimeException("close failed")
        }
    }

    // ---- п.1: поштучное закрытие ----

    @Test
    fun `closePartiallyCreated closes every session even when one close throws`() {
        val first = FakeSession(failOnClose = true)
        val second = FakeSession()
        val third = FakeSession()
        closePartiallyCreated("Test", first, null, second, third)
        assertTrue("бой первой сессии не должен терять вторую", second.closed)
        assertTrue("бой первой сессии не должен терять третью", third.closed)
    }

    @Test
    fun `closeBundleSessions closes every session even when one close throws`() {
        val sessions = listOf(FakeSession(), FakeSession(failOnClose = true), FakeSession())
        closeBundleSessions("Test", *sessions.toTypedArray())
        assertTrue(sessions.all { it.closed })
    }

    // ---- п.2: guardModelLoad ----

    @Test
    fun `guardModelLoad passes success through without closing partial`() {
        var closed = false
        val result = guardModelLoad("Test", "test", "/p", { closed = true }) { null }
        assertNull(result)
        assertTrue(!closed)
    }

    @Test
    fun `guardModelLoad converts exception to LoadFailed and closes partial`() {
        var closed = false
        val error = guardModelLoad("Test", "test", "/p", { closed = true }) {
            throw IllegalStateException("graph broken")
        }
        assertTrue(closed)
        error as RuntimeError.LoadFailed
        assertEquals("test", error.runtime)
        assertEquals("graph broken", error.reason)
    }

    @Test
    fun `guardModelLoad converts native Error to LoadFailed instead of crashing`() {
        // Ревью #83: UnsatisfiedLinkError/NoClassDefFoundError из статик-
        // инициализации OrtEnvironment — Error, а не Exception: без
        // перехвата они роняли процесс.
        var closed = false
        val error = guardModelLoad("Test", "test", "/p", { closed = true }) {
            throw UnsatisfiedLinkError("native lib missing")
        }
        assertTrue(closed)
        error as RuntimeError.LoadFailed
        assertEquals("test", error.runtime)
        assertEquals("native lib missing", error.reason)
    }

    @Test
    fun `guardModelLoad rethrows cancellation without converting to LoadFailed`() {
        var closed = false
        try {
            guardModelLoad("Test", "test", "/p", { closed = true }) {
                throw CancellationException("user cancel")
            }
            fail("ожидался CancellationException")
        } catch (e: CancellationException) {
            // так и должно быть: отмена не конвертируется в ошибку загрузки
        }
        assertTrue("ресурсы частично созданные при отмене обязаны освобождаться", closed)
    }

    // ---- п.3: fail-fast past-входов ----

    @Test
    fun `collectPastInputs maps all layer suffix pairs`() {
        val inputs = collectPastInputs(2, listOf("decoder.key", "encoder.value")) { name ->
            "t:$name"
        }
        assertEquals(
            mapOf(
                "past_key_values.0.decoder.key" to "t:present.0.decoder.key",
                "past_key_values.0.encoder.value" to "t:present.0.encoder.value",
                "past_key_values.1.decoder.key" to "t:present.1.decoder.key",
                "past_key_values.1.encoder.value" to "t:present.1.encoder.value",
            ),
            inputs
        )
    }

    @Test
    fun `collectPastInputs fails fast naming the missing input`() {
        try {
            collectPastInputs(2, listOf("decoder.key")) { name ->
                if (name == "present.1.decoder.key") null else "t:$name"
            }
            fail("ожидался IllegalStateException у отсутствующего входа")
        } catch (e: IllegalStateException) {
            assertNotNull(e.message)
            assertTrue(
                "сообщение должно называть отсутствующий вход: ${e.message}",
                e.message!!.contains("present.1.decoder.key")
            )
        }
    }

    // ---- п.4: верхняя оценка резерва байтового бюджета ----

    @Test
    fun `reserveIncomingBytes applies headroom when ram not declared`() {
        // ramRequiredMb не задан (импортируемые пучки): запас 1.4x на
        // arena/активации ORT вместо одних файлов графов.
        assertEquals(350L, reserveIncomingBytes(graphBytes = 250L, ramRequiredMb = 0))
        assertEquals(1400L, reserveIncomingBytes(graphBytes = 1000L, ramRequiredMb = 0))
        // extraBytes (файлы tokenizer.json) добавляются поверх большей оценки:
        // пик readText+DOM входит в резерв загрузки пучка.
        assertEquals(400L, reserveIncomingBytes(250L, 0, extraBytes = 50L))
    }

    @Test
    fun `reserveIncomingBytes prefers declared ramRequiredMb when larger`() {
        val mb = 1024L * 1024L
        // Заявленный каталогом профиль ОЗУ больше оценки файлов — берётся он.
        assertEquals(300 * mb, reserveIncomingBytes(graphBytes = 100L, ramRequiredMb = 300))
        // Заявленный меньше оценки с запасом — побеждает консервативная оценка.
        assertEquals(2_800_000L, reserveIncomingBytes(graphBytes = 2_000_000L, ramRequiredMb = 1))
        assertEquals(
            300 * mb + 64L,
            reserveIncomingBytes(graphBytes = 100L, ramRequiredMb = 300, extraBytes = 64L)
        )
    }
}
