package com.plox.llmtranslate.data.backup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory

/**
 * Политика подмены файлов при импорте бэкапа (регресс-ревью issue #83,
 * п.4): сбой/таймаут запроса состояния WorkManager не должен приводить к
 * слепой подмене файлов ДО отмены — неподтверждённая работа считается
 * живой, иначе поздний cleanupCancelledModel воркера удаляет восстановленный
 * бандл. Ревью #83 (итерация 3, п.4/п.7): после отправленного cancel
 * QUERY_FAILED — терминальный исход; порядок решения покрыт тестами
 * инъективного каркаса [decideImportWorkSwap] (WorkManager недоступен на
 * тестовом JVM), порядок «mkdirs ДО StatFs-гейта» — предиктом
 * [preparedTempDirBeforeSpaceGate].
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

    // ── ревью #83 (итерация 3, п.4/п.7): инъективный каркас решения ──

    @Test
    fun `decision skips swap when pre-cancel observation is QUERY_FAILED`() = runBlocking {
        var cancelled = false
        assertFalse(
            decideImportWorkSwap(
                modelId = "m",
                firstObservation = ImportWorkObservation.QUERY_FAILED,
                query = { ImportWorkObservation.NOT_LIVE },
                cancelLive = { cancelled = true },
                nowMs = System::currentTimeMillis,
                pollIntervalMs = 5L,
                terminalTimeoutMs = 500L
            )
        )
        // Cancel не отправлялся: состояние живой работы не подтверждено.
        assertFalse(cancelled)
    }

    @Test
    fun `live work is cancelled then first recheck is delayed`() = runBlocking {
        // Первый ре-чек после cancel — не мгновенный (п.4): первый же опрос
        // ещё видит LIVE (воркер не отреагировал), терминал — только со
        // второго наблюдения после паузы.
        val queries = AtomicInteger(0)
        var cancelled = false
        assertTrue(
            decideImportWorkSwap(
                modelId = "m",
                firstObservation = ImportWorkObservation.LIVE,
                query = {
                    if (queries.incrementAndGet() == 1) {
                        ImportWorkObservation.LIVE
                    } else {
                        ImportWorkObservation.NOT_LIVE
                    }
                },
                cancelLive = { cancelled = true },
                nowMs = System::currentTimeMillis,
                pollIntervalMs = 5L,
                terminalTimeoutMs = 5_000L
            )
        )
        assertTrue(cancelled)
        assertEquals(2, queries.get())
    }

    @Test
    fun `QUERY_FAILED after cancel is terminal and allows swap`() = runBlocking {
        // Отмена уже отправлена — не подтвержденное состояние после неё не
        // запрещает подмену (п.4 итерации 3).
        assertTrue(
            decideImportWorkSwap(
                modelId = "m",
                firstObservation = ImportWorkObservation.LIVE,
                query = { ImportWorkObservation.QUERY_FAILED },
                cancelLive = { },
                nowMs = System::currentTimeMillis,
                pollIntervalMs = 5L,
                terminalTimeoutMs = 5_000L
            )
        )
    }

    @Test
    fun `no terminal status within timeout keeps previous swap path`() = runBlocking {
        val clock = java.util.concurrent.atomic.AtomicLong(0L)
        assertTrue(
            decideImportWorkSwap(
                modelId = "m",
                firstObservation = ImportWorkObservation.LIVE,
                query = { ImportWorkObservation.LIVE },
                cancelLive = { },
                nowMs = { clock.addAndGet(1_000L) },
                pollIntervalMs = 1L,
                terminalTimeoutMs = 50L
            )
        )
    }

    @Test
    fun `post-cancel policy settles on any non-live observation`() {
        assertFalse(postCancelImportSettled(ImportWorkObservation.LIVE))
        assertTrue(postCancelImportSettled(ImportWorkObservation.NOT_LIVE))
        assertTrue(postCancelImportSettled(ImportWorkObservation.QUERY_FAILED))
    }

    // ── ревью #83 (итерация 3, п.7): фиксация порядка mkdirs ДО StatFs ──

    @Test
    fun `temp dir predicate guarantees dir exists before space gate`() {
        val root = createTempDirectory("backup_gate").toFile()
        try {
            val fresh = File(root, "temp_import")
            assertTrue(TEMP_MUST_EXIST_BEFORE_SPACE_GATE.isNotEmpty())
            // Несуществующий каталог — создан (дальнейший StatFs-гейт
            // видит реальный том, а не всегда «пропускается»).
            assertTrue(preparedTempDirBeforeSpaceGate(fresh))
            assertTrue(fresh.isDirectory)
            assertTrue(preparedTempDirBeforeSpaceGate(fresh))
            // Занятый файлом путь — недоступен как каталог.
            val occupied = File(root, "occupied")
            occupied.writeText("x")
            assertFalse(preparedTempDirBeforeSpaceGate(occupied))
        } finally {
            root.deleteRecursively()
        }
    }
}
