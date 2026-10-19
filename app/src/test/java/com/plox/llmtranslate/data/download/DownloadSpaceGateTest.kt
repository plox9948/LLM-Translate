package com.plox.llmtranslate.data.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Юнит-тест решения гейта места перед скачиванием (issue #83, п.1):
 * при недоступной оценке свободного места гейт пропускается, а не роняет
 * воркер - нехватка всё равно классифицируется isOutOfSpaceError при записи.
 */
class DownloadSpaceGateTest {

    @Test
    fun `missing space decision table`() {
        // Порог не задан - гейт не срабатывает ни при какой оценке.
        assertFalse(isDownloadSpaceMissing(0L, 0L))
        assertFalse(isDownloadSpaceMissing(0L, null))
        assertFalse(isDownloadSpaceMissing(0L, Long.MAX_VALUE))
        // Оценка недоступна - гейт пропускается (StatFs бросил/том offlined).
        assertFalse(isDownloadSpaceMissing(1000L, null))
        // Ровно по порогу - хватает.
        assertFalse(isDownloadSpaceMissing(1000L, 1000L))
        // Ниже порога - permanent-отказ.
        assertTrue(isDownloadSpaceMissing(1000L, 999L))
        assertTrue(isDownloadSpaceMissing(1000L, 0L))
    }
}