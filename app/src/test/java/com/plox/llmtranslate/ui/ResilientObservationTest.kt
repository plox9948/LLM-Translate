package com.plox.llmtranslate.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Тесты [observeResilient] и [ObserveErrorTracker] (ревью-тест.retry):
 * перезапуск подписки после сбоя источника, обработка штатного завершения
 * flow и сброс сообщения сбоя при первой штатной эмиссии после ретрая
 * (ревью #11), а также учёт множества активных ошибок (ревью): восстановление
 * одного источника не гасит баннер при ещё мёртвом другом.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResilientObservationTest {

    @Test
    fun `resubscribes after source failure and clears error on first emission`() = runTest {
        val errors = ObserveErrorTracker()
        val received = mutableListOf<Int>()
        var calls = 0

        observeResilient(
            scope = this,
            source = {
                calls++
                flow {
                    emit(calls)
                    if (calls == 1) throw IllegalStateException("сбой источника")
                }
            },
            messageRes = 42,
            errors = errors,
            marker = "source-a"
        ) { value -> received.add(value) }

        runCurrent()
        // Первая эмиссия получена, источник упал — установлено сообщение сбоя.
        assertEquals(listOf(1), received)
        assertEquals(42, errors.state.value)

        advanceUntilIdle()
        // Переподписка состоялась, значение получено, баннер сброшен.
        assertEquals(2, calls)
        assertEquals(listOf(1, 2), received)
        assertNull(errors.state.value)
    }

    @Test
    fun `normal completion of source exits without resubscribe`() = runTest {
        val errors = ObserveErrorTracker()
        var calls = 0
        observeResilient(
            scope = this,
            source = {
                calls++
                flow { emit(1) } // штатное завершение без исключения
            },
            messageRes = 42,
            errors = errors,
            marker = "source-a"
        ) { }

        advanceUntilIdle()

        assertEquals(1, calls)
        assertNull(errors.state.value)
    }

    @Test
    fun `recovery of one source keeps banner while another is still failing`() = runTest {
        val errors = ObserveErrorTracker()
        // B падает на первых двух попытках и оживает на третьей (конечное
        // число ретраев — advanceTimeBy вместо бесконечного догона).
        var attemptsB = 0
        var callsA = 0
        val receivedA = mutableListOf<Int>()
        val receivedB = mutableListOf<Int>()

        observeResilient(
            scope = this,
            source = {
                callsA++
                flow {
                    emit(1)
                    if (callsA == 1) throw IllegalStateException("сбой A")
                }
            },
            messageRes = 41,
            errors = errors,
            marker = "source-a"
        ) { value -> receivedA.add(value) }
        observeResilient(
            scope = this,
            source = {
                attemptsB++
                flow {
                    if (attemptsB <= 2) throw IllegalStateException("сбой B")
                    emit(2)
                }
            },
            messageRes = 42,
            errors = errors,
            marker = "source-b"
        ) { value -> receivedB.add(value) }

        runCurrent()
        // При t=0 оба источника упали — активны две ошибки, баннер несёт
        // последнюю зарегистрированную.
        assertEquals(listOf(1), receivedA)
        assertEquals(42, errors.state.value)

        advanceTimeBy(2_000L)
        runCurrent()
        // A ожил (его ошибка снята), B провалился повторно — баннер остаётся:
        // восстановление одного источника не снимает сообщение другого (ревью).
        assertEquals(listOf(1, 1), receivedA)
        assertEquals(42, errors.state.value)

        advanceTimeBy(2_000L)
        runCurrent()
        // B ожил — множество пусто, баннер снят.
        assertEquals(listOf(2), receivedB)
        assertNull(errors.state.value)
    }

    @Test
    fun `dismissAll clears tracker and does not republish on later recovery`() = runTest {
        val errors = ObserveErrorTracker()
        errors.reportFailure("source-a", 41)
        errors.reportFailure("source-b", 42)
        assertEquals(42, errors.state.value)

        errors.dismissAll()
        assertNull(errors.state.value)

        // Пользователь скрыл баннер — восстановление зарегистрированного
        // ранее источника не должно возвращать сообщение.
        errors.recover("source-a")
        errors.recover("source-b")
        assertNull(errors.state.value)
    }
}
