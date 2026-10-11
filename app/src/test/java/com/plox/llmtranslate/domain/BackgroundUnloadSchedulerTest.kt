package com.plox.llmtranslate.domain

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Тесты планировщика отложенной выгрузки: запуск по тайм-ауту, отмена
 * при возврате, перезапланирование и специальные значения настройки
 * (0 — сразу, -1 — не выгружать).
 */
class BackgroundUnloadSchedulerTest {

    /** Фейковый поставщик тайм-аута с управляемым значением и счётчиком обращений. */
    private class FakeTimeoutProvider(var minutes: Int) : UnloadTimeoutProvider {
        var calls = 0
            private set

        override suspend fun timeoutMinutes(): Int {
            calls++
            return minutes
        }
    }

    @Test
    fun `unload runs after timeout elapses`() = runTest {
        val scheduler = BackgroundUnloadScheduler(backgroundScope, FakeTimeoutProvider(5))
        var unloads = 0

        scheduler.schedule { unloads++ }

        advanceTimeBy(5 * 60_000L - 1)
        runCurrent()
        assertEquals(0, unloads)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, unloads)
    }

    @Test
    fun `cancel before timeout skips unload`() = runTest {
        val scheduler = BackgroundUnloadScheduler(backgroundScope, FakeTimeoutProvider(5))
        var unloads = 0

        scheduler.schedule { unloads++ }
        advanceTimeBy(60_000)
        scheduler.cancel()

        advanceTimeBy(60 * 60_000L)
        runCurrent()
        assertEquals(0, unloads)
    }

    @Test
    fun `reschedule restarts the countdown from zero`() = runTest {
        val scheduler = BackgroundUnloadScheduler(backgroundScope, FakeTimeoutProvider(1))
        var unloads = 0

        scheduler.schedule { unloads++ }
        advanceTimeBy(30_000)
        scheduler.schedule { unloads++ }

        // Первая задача сгорела бы ровно на 60-й секунде — она отменена.
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(0, unloads)

        // Отсчёт второй задачи: 60 с от момента перезапланирования.
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, unloads)
    }

    @Test
    fun `zero timeout unloads immediately`() = runTest {
        val scheduler = BackgroundUnloadScheduler(backgroundScope, FakeTimeoutProvider(0))
        var unloads = 0

        scheduler.schedule { unloads++ }
        runCurrent()

        assertEquals(1, unloads)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `negative timeout disables unload`() = runTest {
        val provider = FakeTimeoutProvider(BackgroundUnloadScheduler.NEVER_UNLOAD)
        val scheduler = BackgroundUnloadScheduler(backgroundScope, provider)
        var unloads = 0

        scheduler.schedule { unloads++ }
        advanceTimeBy(7 * 24 * 60 * 60_000L)
        runCurrent()

        assertEquals(0, unloads)
        assertEquals(1, provider.calls)
    }

    @Test
    fun `timeout is re-read on every schedule`() = runTest {
        val provider = FakeTimeoutProvider(1)
        val scheduler = BackgroundUnloadScheduler(backgroundScope, provider)
        var unloads = 0

        scheduler.schedule { unloads++ }
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, unloads)

        // Смена настройки применяется со следующего планирования без перезапуска.
        provider.minutes = 2
        scheduler.schedule { unloads++ }
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, unloads)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, unloads)
        assertEquals(2, provider.calls)
    }

    @Test
    fun `repeated schedules keep only the last unload`() = runTest {
        val scheduler = BackgroundUnloadScheduler(backgroundScope, FakeTimeoutProvider(1))
        var unloads = 0

        scheduler.schedule { unloads++ }
        scheduler.schedule { unloads++ }
        scheduler.schedule { unloads++ }

        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, unloads)
    }

    @Test
    fun `cancel after unload already fired is safe`() = runTest {
        val scheduler = BackgroundUnloadScheduler(backgroundScope, FakeTimeoutProvider(1))
        var unloads = 0

        scheduler.schedule { unloads++ }
        advanceTimeBy(60_000)
        runCurrent()

        scheduler.cancel()

        assertEquals(1, unloads)
    }
}
