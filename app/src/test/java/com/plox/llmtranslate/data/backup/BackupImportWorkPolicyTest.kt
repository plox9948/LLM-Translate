package com.plox.llmtranslate.data.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Политика подмены файлов при импорте бэкапа (регресс-ревью issue #83,
 * п.4): сбой/таймаут запроса состояния WorkManager не должен приводить к
 * слепой подмене файлов — неподтверждённая работа считается живой, иначе
 * поздний cleanupCancelledModel воркера удаляет восстановленный бандл.
 */
class BackupImportWorkPolicyTest {

    @Test
    fun `query failure treated as live work - swap skipped`() {
        assertFalse(importSwapAllowed(ImportWorkObservation.QUERY_FAILED))
    }

    @Test
    fun `confirmed terminal work allows swap`() {
        assertTrue(importSwapAllowed(ImportWorkObservation.NOT_LIVE))
    }

    @Test
    fun `confirmed live work blocks swap`() {
        assertFalse(importSwapAllowed(ImportWorkObservation.LIVE))
    }
}