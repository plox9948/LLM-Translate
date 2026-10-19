package com.plox.llmtranslate.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Тесты [observeResilient] (ревью-тест.retry): перезапуск подписки после сбоя
 * источника, обработка штатного завершения flow и сброс сообщения сбоя при
 * первой штатной эмиссии после ретрая (ревью #11).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResilientObservationTest {

    @Test
    fun `resubscribes after source failure and clears error on first emission`() = runTest {
        val errorState = MutableStateFlow<Int?>(null)
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
            errorState = errorState
        ) { value -> received.add(value) }

        runCurrent()
        // Первая эмиссия получена, источник упал — установлено сообщение сбоя.
        assertEquals(listOf(1), received)
        assertEquals(42, errorState.value)

        advanceUntilIdle()
        // Переподписка состоялась, значение получено, баннер сброшен.
        assertEquals(2, calls)
        assertEquals(listOf(1, 2), received)
        assertNull(errorState.value)
    }

    @Test
    fun `normal completion of source exits without resubscribe`() = runTest {
        val errorState = MutableStateFlow<Int?>(null)
        var calls = 0
        observeResilient(
            scope = this,
            source = {
                calls++
                flow { emit(1) } // штатное завершение без исключения
            },
            messageRes = 42,
            errorState = errorState
        ) { }

        advanceUntilIdle()

        assertEquals(1, calls)
        assertNull(errorState.value)
    }
}
