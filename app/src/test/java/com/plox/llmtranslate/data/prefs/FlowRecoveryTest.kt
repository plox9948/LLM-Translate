package com.plox.llmtranslate.data.prefs

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Тесты [flowWithRecovery] (ревью контрольного раунда 4): сбой источника не
 * уронивает подписку — эмитится последнее значение (или фолбэк при отсутствии),
 * затем переподписка; штатное завершение источника — выход без переподписки.
 */
class FlowRecoveryTest {

    @Test
    fun `failure before any value emits fallback then resubscribes`() = runTest {
        var calls = 0
        val values = flowWithRecovery("setting", fallback = -1, retryDelayMs = 50L) {
            calls++
            flow {
                if (calls == 1) throw IllegalStateException("сбой источника")
                emit(7)
            }
        }.toList()

        assertEquals(2, calls)
        assertEquals(listOf(-1, 7), values)
    }

    @Test
    fun `failure after a value re-emits last value not default`() = runTest {
        var calls = 0
        val values = flowWithRecovery("setting", fallback = "fb", retryDelayMs = 50L) {
            calls++
            flow {
                emit("a")
                if (calls == 1) throw IllegalStateException("сбой источника")
                emit("b")
            }
        }.toList()

        assertEquals(2, calls)
        // «a» из источника, «a» фолбэком после сбоя, «a» и «b» из повторной
        // подписки к восстановленному источнику.
        assertEquals(listOf("a", "a", "a", "b"), values)
    }

    @Test
    fun `normal completion exits without resubscribe`() = runTest {
        var calls = 0
        val values = flowWithRecovery("setting", fallback = 0, retryDelayMs = 50L) {
            calls++
            flow { emit(1) }
        }.toList()

        assertEquals(1, calls)
        assertEquals(listOf(1), values)
    }
}
