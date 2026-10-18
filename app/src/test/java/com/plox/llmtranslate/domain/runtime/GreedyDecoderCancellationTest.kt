package com.plox.llmtranslate.domain.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты отмены и владения ресурсами greedy-цикла [greedyDecodeCore] —
 * ядра, которое используют MarianOnnxRuntime и IndicTransOnnxRuntime через
 * обёртку [greedyDecode].
 *
 * Классы ai.onnxruntime при инициализации на JVM грузят нативную библиотеку,
 * недоступную в юнит-тестах, поэтому цикл прогоняется напрямую с фейковыми
 * [AutoCloseable]-ресурсами: они считают close(), чем проверяется, что
 * каждый созданный тензор и результат шага закрыт ровно один раз —
 * и при отмене, и при штатном завершении по EOS.
 */
class GreedyDecoderCancellationTest {

    /** Фейковый входной тензор шага: считает закрытия. */
    private class FakeTensor : AutoCloseable {
        var closed = 0
        override fun close() {
            closed++
        }
    }

    /** Фейковый результат шага: считает закрытия. */
    private class FakeResult : AutoCloseable {
        var closed = 0
        override fun close() {
            closed++
        }
    }

    private val tensors = mutableListOf<FakeTensor>()
    private val results = mutableListOf<FakeResult>()
    private var steps = 0
    private var lastOutcome: DecodeOutcome? = null

    /**
     * Прогон ядра в отдельной корутине.
     * @param cancelAtStep шаг, на котором [runStep] отменяет собственную job
     *        корутины (null — без отмены).
     * @param startCancelled запускать ли корутину уже отменённой.
     * @param pick выбор токена по числу уже выбранных токенов.
     * @return брошенное исключение либо null при штатном завершении.
     */
    private fun runCore(
        maxNewTokens: Int,
        cancelAtStep: Int?,
        startCancelled: Boolean,
        pick: (generatedCount: Int) -> Int
    ): Throwable? = runBlocking {
        var thrown: Throwable? = null
        steps = 0
        lastOutcome = null
        val parent = if (startCancelled) Job().apply { cancel() } else Job()
        val child = launch(parent, start = CoroutineStart.LAZY) {
            thrown = try {
                lastOutcome = greedyDecodeCore<FakeTensor, FakeResult>(
                    initialIds = emptyList(),
                    startTokenId = 0,
                    maxNewTokens = maxNewTokens,
                    eosTokenId = EOS,
                    newStepInputs = {
                        Pair(
                            FakeTensor().also { tensors.add(it) },
                            FakeTensor().also { tensors.add(it) }
                        )
                    },
                    pickToken = { _, generated -> pick(generated.size) }
                ) { step, _, _, _, _ ->
                    steps++
                    if (cancelAtStep != null && step == cancelAtStep) {
                        // Отмена из глубокого коллбэка эквивалентна cancel()
                        // снаружи: всплывёт на ensureActive() след. итерации.
                        this.cancelScope()
                    }
                    FakeResult().also { results.add(it) }
                }
                null
            } catch (e: Throwable) {
                e
            }
        }
        child.start()
        child.join()
        thrown
    }

    /** Отмена собственной области запуска (расширение видно только в lambda). */
    private fun CoroutineScope.cancelScope() = cancel()

    /** Каждый созданный ресурс закрыт ровно один раз. */
    private fun assertAllClosedExactlyOnce() {
        assertTrue("тензоры созданы, но цикл не выполнялся?", tensors.isNotEmpty())
        tensors.forEach { assertEquals("тензор закрыт повторно", 1, it.closed) }
        results.forEach { assertEquals("result закрыт повторно", 1, it.closed) }
    }

    @Test
    fun `cancel aborts the loop and closes all results`() {
        val thrown = runCore(
            maxNewTokens = 1000,
            cancelAtStep = 1,
            startCancelled = false,
            pick = { NOT_EOS }
        )
        assertTrue(
            "ожидался CancellationException, получен: $thrown",
            thrown is CancellationException
        )
        // cancel на шаге 1 → цикл обрывается ensureActive() на шаге 2.
        assertEquals("генерация должна оборваться сразу после cancel", 2, steps)
        assertEquals(2, results.size)
        assertAllClosedExactlyOnce()
    }

    @Test
    fun `pre-cancelled coroutine performs no steps`() {
        val thrown = runCore(
            maxNewTokens = 1000,
            cancelAtStep = null,
            startCancelled = true,
            pick = { NOT_EOS }
        )
        // Тело заранее отменённой ленивой корутины может не запуститься вовсе
        // (thrown == null) или получить CancellationException на ensureActive();
        // в обоих случаях шаг не выполняется и ресурсы не создаются.
        assertTrue(
            "неожиданное исключение: $thrown",
            thrown == null || thrown is CancellationException
        )
        assertEquals(0, steps)
        assertEquals(0, results.size)
        assertEquals(0, tensors.size)
    }

    @Test
    fun `natural finish on eos closes all results exactly once`() {
        val thrown = runCore(
            maxNewTokens = 1000,
            cancelAtStep = null,
            startCancelled = false,
            // Третий выбранный токен — EOS: цикл завершается штатно.
            pick = { generatedCount -> if (generatedCount == 2) EOS else NOT_EOS }
        )
        assertNull(thrown)
        assertEquals(3, steps)
        assertEquals(3, results.size)
        // Первый result держится до конца цикла и закрывается в finally,
        // промежуточный — при замещении; дубли close() исключены.
        assertAllClosedExactlyOnce()
    }

    @Test
    fun `eosReached flag set when pick returns eos before limit`() {
        val thrown = runCore(
            maxNewTokens = 10,
            cancelAtStep = null,
            startCancelled = false,
            // Четвёртый выбранный токен — EOS при лимите 10: остановка штатная.
            pick = { generatedCount -> if (generatedCount == 3) EOS else NOT_EOS }
        )
        assertNull(thrown)
        val outcome = requireNotNull(lastOutcome)
        assertTrue(outcome.eosReached)
        assertFalse(outcome.hitMaxLength)
        // EOS включается в результат (как в HF generate).
        assertEquals(listOf(NOT_EOS, NOT_EOS, NOT_EOS, EOS), outcome.generated)
    }

    @Test
    fun `hitMaxLength flag set when no eos within limit`() {
        val thrown = runCore(
            maxNewTokens = 4,
            cancelAtStep = null,
            startCancelled = false,
            pick = { NOT_EOS }
        )
        assertNull(thrown)
        val outcome = requireNotNull(lastOutcome)
        assertFalse(outcome.eosReached)
        assertTrue(outcome.hitMaxLength)
        assertEquals(4, outcome.generated.size)
        assertEquals(4, steps)
        assertAllClosedExactlyOnce()
    }

    @Test
    fun `argMaxSafe throws when every logit is NaN or infinite`() {
        val arrays = listOf(
            floatArrayOf(Float.NaN, Float.NaN, Float.NaN),
            floatArrayOf(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY),
            FloatArray(0)
        )
        for (values in arrays) {
            val thrown = try {
                argMaxSafe(values, "тест все некорректные")
                null
            } catch (e: IllegalStateException) {
                e
            }
            assertNotNull(
                "ожидался IllegalStateException для logits=${values.toList()}",
                thrown
            )
            assertTrue(thrown!!.message!!.startsWith("Некорректные logits"))
        }
    }

    @Test
    fun `argMaxSafe skips isolated NaN and infinite entries`() {
        // Максимум на NaN-позиции не выбирается — выигрывает лучший конечный.
        assertEquals(
            3,
            argMaxSafe(
                floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN, 2.5f, 1f),
                "тест пропуск NaN"
            )
        )
        assertEquals(1, argMaxSafe(floatArrayOf(Float.NEGATIVE_INFINITY, -3f, Float.NaN), "тест"))
    }

    companion object {
        private const val EOS = 9999
        private const val NOT_EOS = 7
    }
}
