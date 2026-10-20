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
        assertEquals(
            ImportSwapDecision.DENIED_WITHOUT_CANCEL,
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
        assertEquals(
            ImportSwapDecision.SWAP_ALLOWED,
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
        assertEquals(
            ImportSwapDecision.SWAP_ALLOWED,
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
    fun `no terminal status within timeout denies swap after cancel`() = runBlocking {
        // Ревью аудита, п.3: LIVE по истечении дедлайна — живой воркер ещё
        // работает, подмену НЕ разрешаем (fail-closed, соответствует KDoc
        // importSwapAllowed); запись будет пропущена, повтор импорта позже
        // увидит завершённую (отменённую) работу. Ревью повт. аудита, п.7:
        // исход различается как DENIED_AFTER_CANCEL — cancel УЖЕ отправлен,
        // загрузка пользователя потеряна без замещения и попадает в
        // ImportResult.downloadsInterrupted.
        val clock = java.util.concurrent.atomic.AtomicLong(0L)
        var cancelled = false
        assertEquals(
            ImportSwapDecision.DENIED_AFTER_CANCEL,
            decideImportWorkSwap(
                modelId = "m",
                firstObservation = ImportWorkObservation.LIVE,
                query = { ImportWorkObservation.LIVE },
                cancelLive = { cancelled = true },
                nowMs = { clock.addAndGet(1_000L) },
                pollIntervalMs = 1L,
                terminalTimeoutMs = 50L
            )
        )
        assertTrue(cancelled)
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

    // ── ревью аудита, п.4 + ревью повт. аудита, п.1/п.2: лимиты распаковки ──

    @Test
    fun `extract budget is max of manifest and zip declared estimates`() {
        // Известный манифест: оценка гейта (уже с раздуванием) + 50 % запас.
        assertEquals(1500L, backupExtractLimitBytes(1000L, 0L))
        // Старый архив (sizeBytes=0) с объявленными размерами в заголовках ZIP:
        // бюджет по ZIP-оценке — легитимные пучки в сотни МБ проходят
        // (прежний floor 64 МБ их отклонял).
        assertEquals(150_000L, backupExtractLimitBytes(0L, 100_000L))
        // Обе оценки — берётся максимум с тем же запасом.
        assertEquals(3000L, backupExtractLimitBytes(1000L, 2000L))
        // Обе оценки недоступны (манифест 0 + ZIP без объявленных размеров) —
        // повышенный предельный бюджет для легитимных старых архивов.
        assertEquals(BACKUP_EXTRACT_UNKNOWN_BUDGET_BYTES, backupExtractLimitBytes(0L, 0L))
    }

    /** ZipInputStream с одной записью фиксированного размера. */
    private fun zipWithEntry(bytes: Int): java.util.zip.ZipInputStream {
        val data = java.io.ByteArrayOutputStream().apply {
            java.util.zip.ZipOutputStream(this).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("f"))
                zip.write(ByteArray(bytes) { 7 })
                zip.closeEntry()
            }
        }.toByteArray()
        return java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(data)).apply { nextEntry }
    }

    @Test
    fun `copyEntryCapped copies within budget and returns remainder`() {
        val source = zipWithEntry(100)
        val sink = java.io.ByteArrayOutputStream()
        val remaining = copyEntryCapped(source, sink, 150L, 150L)
        assertEquals(100, sink.size())
        assertEquals(50L, remaining)
        source.close()
    }

    @Test
    fun `copyEntryCapped rejects content beyond remaining budget`() {
        val source = zipWithEntry(100)
        val overflow = java.io.ByteArrayOutputStream()
        val error = runCatching { copyEntryCapped(source, overflow, 50L, 50L) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("превысил расчётный лимит"))
        source.close()
    }

    @Test
    fun `budget is cumulative across entries of one pass`() {
        // Ревью повт. аудита, п.1: остаток бюджета перетекает между записями
        // прохода — суммарно N записей не пишут N×лимит (прежний баг: счётчик
        // был локален на запись). Две по 100 байт при бюджете 150 байт: первая
        // оставляет остаток 50, вторая выходит за суммарный лимит.
        val first = zipWithEntry(100)
        val second = zipWithEntry(100)
        try {
            val remaining = copyEntryCapped(first, java.io.ByteArrayOutputStream(), 150L, 150L)
            assertEquals(50L, remaining)
            val error = runCatching {
                copyEntryCapped(second, java.io.ByteArrayOutputStream(), remaining, 150L)
            }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertTrue(error!!.message!!.contains("суммарный объём распаковки"))
            assertTrue(error.message!!.contains("150"))
        } finally {
            first.close()
            second.close()
        }
    }
}
