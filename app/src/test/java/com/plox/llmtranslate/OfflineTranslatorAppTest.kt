package com.plox.llmtranslate

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Тесты снапшота файловой системы для сверки READY-записей при старте
 * (ревью, п.1) — сам вызов markBrokenReadyRecords покрыт тестами
 * ModelRepositoryTest, здесь — только маппинг пути в FileSnapshot.
 */
class OfflineTranslatorAppTest {

    private fun tempDir(): File = File.createTempFile("snap", "").apply {
        delete()
        mkdirs()
    }

    @Test
    fun `non-empty file snapshot reports its size`() {
        val dir = tempDir()
        val file = File(dir, "model.onnx").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val snapshot = fileSnapshotOf(file.absolutePath)

        assertEquals(false, snapshot?.isDirectory)
        assertEquals(3L, snapshot?.size)
        dir.deleteRecursively()
    }

    @Test
    fun `empty file snapshot is inconsistent`() {
        val dir = tempDir()
        val file = File(dir, "empty.bin").apply { writeBytes(byteArrayOf()) }

        val snapshot = fileSnapshotOf(file.absolutePath)

        assertEquals(false, snapshot?.isDirectory)
        assertEquals(0L, snapshot?.size)
        dir.deleteRecursively()
    }

    @Test
    fun `directory snapshot counts children`() {
        val dir = tempDir()
        val bundle = File(dir, "bundle").apply { mkdirs() }
        File(bundle, "a.bin").writeBytes(byteArrayOf(1))
        File(bundle, "b.bin").writeBytes(byteArrayOf(2))

        val snapshot = fileSnapshotOf(bundle.absolutePath)

        assertEquals(true, snapshot?.isDirectory)
        assertEquals(2, snapshot?.childCount)
        dir.deleteRecursively()
    }

    @Test
    fun `missing path snapshot is empty`() {
        val dir = tempDir()

        val snapshot = fileSnapshotOf(File(dir, "gone.bin").absolutePath)

        assertEquals(false, snapshot?.isDirectory)
        assertEquals(0L, snapshot?.size)
        assertEquals(0, snapshot?.childCount)
        dir.deleteRecursively()
    }
}
