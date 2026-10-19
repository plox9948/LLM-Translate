package com.plox.llmtranslate.data.prefs

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Тесты [flowWithRecovery] (ревью контрольного раунда 4 + регресс-раунд):
 * сбой источника не роняет подписку — эмитится последнее значение (или
 * фолбэк/ленивый provider при отсутствии), затем переподписка; штатное
 * завершение источника — выход без переподписки. Исключение коллектора —
 * НЕ сбой источника (ретраев нет); в null-режиме (без фолбэка) до первого
 * значения эмиссии нет.
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

    @Test
    fun `collector exception is not source failure and does not retry`() = runTest {
        var calls = 0
        var thrown: Throwable? = null
        try {
            flowWithRecovery("setting", fallback = -1, retryDelayMs = 50L) {
                calls++
                flow { emit(1); emit(2); emit(3) }
            }.collect { value ->
                // Исключение коллектора в channelFlow-модели возникает на
                // стороне подписчика и не должно попадать в защиту источника.
                if (value == 1) throw IllegalStateException("сбой коллектора")
            }
        } catch (e: Throwable) {
            thrown = e
        }

        assertEquals("сбой коллектора", thrown?.message)
        assertEquals(1, calls)
    }

    @Test
    fun `null fallback mode emits nothing until first real value`() = runTest {
        var calls = 0
        val values = flowWithRecovery<Boolean>("onboarding_done", retryDelayMs = 50L) {
            calls++
            flow {
                if (calls == 1) throw IllegalStateException("холодный сбой чтения")
                emit(false)
            }
        }.toList()

        // Без фолбэка: сбой только с паузой, ложный «false» не эмитится.
        assertEquals(2, calls)
        assertEquals(listOf(false), values)
    }

    @Test
    fun `lazy provider gives default while cache cold`() = runTest {
        var calls = 0
        val cache = AtomicReference<String?>(null)
        val values = flowWithRecovery(
            "ui_language",
            fallbackProvider = { cache.get() ?: "system" },
            retryDelayMs = 50L
        ) {
            calls++
            flow {
                if (calls == 1) throw IllegalStateException("сбой чтения")
                emit("ru")
            }
        }.toList()

        assertEquals(2, calls)
        assertEquals(listOf("system", "ru"), values)
    }

    @Test
    fun `warm cache provider matches applied value so no recreate loop`() = runTest {
        var calls = 0
        // Кэш процесса прогрет значением «ru» (persist не дошёл, кэш обновлён):
        // фолбэк совпадает с применённым языком — distinctUntilChanged гасит
        // эмиссию, цикла recreate в MainActivity нет.
        val cache = AtomicReference("ru")
        val values = flowWithRecovery(
            "ui_language",
            fallbackProvider = { cache.get() ?: "system" },
            retryDelayMs = 50L
        ) {
            calls++
            flow<String> {
                if (calls == 1) throw IllegalStateException("сбой чтения после persist")
                emit("ru")
            }
        }.distinctUntilChanged().toList()

        assertEquals(2, calls)
        assertEquals(listOf("ru"), values)
    }
}
