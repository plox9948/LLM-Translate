package com.plox.llmtranslate.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Issue #83, п.6/п.4: стартовый GC osиротевших temp-артефактов бэкапа и
 * разбор document id SAF для гейта целевого volume — чистые функции на
 * реальных временных файлах без Android.
 */
class ModelBackupTempGcTest {

    @get:Rule
    val folder = TemporaryFolder()

    // ---- GC ----

    @Test
    fun `stale backup temp dirs and files removed, fresh and foreign kept`() {
        val now = System.currentTimeMillis()
        val modelsParent = folder.newFolder("models-parent")
        val cache = folder.newFolder("cache")

        val oldImport = File(modelsParent, "backup_import_old").apply {
            mkdirs()
            File(this, "part.bin").writeText("x")
        }
        oldImport.setLastModified(now - BACKUP_TEMP_TTL_MS - 60_000)

        val freshImport = File(modelsParent, "backup_import_fresh").apply { mkdirs() }
        val foreign = File(modelsParent, "SLIMT").apply {
            mkdirs()
            File(this, "keep.bin").writeText("y")
        }
        foreign.setLastModified(now - BACKUP_TEMP_TTL_MS - 60_000)

        val oldExport = File(cache, "backup_export_old.zip").apply { writeText("zip") }
        oldExport.setLastModified(now - BACKUP_TEMP_TTL_MS - 60_000)

        val freshExport = File(cache, "backup_export_fresh.zip").apply { writeText("zip") }
        val otherStale = File(cache, "recording_x.wav").apply { writeText("w") }
        otherStale.setLastModified(now - BACKUP_TEMP_TTL_MS - 60_000)

        val removed = cleanupOrphanBackupTemp(
            dirs = listOf(File(folder.root, "does-not-exist"), modelsParent, cache),
            ttlMs = BACKUP_TEMP_TTL_MS,
            now = now
        )
        assertEquals(2, removed)
        assertFalse("Старый backup_import_* должен быть удалён", oldImport.exists())
        assertFalse("Старый backup_export_* должен быть удалён", oldExport.exists())
        assertTrue(freshImport.exists())
        assertTrue(freshExport.exists())
        assertTrue("Чужие каталоги трогать нельзя", foreign.exists())
        assertTrue("Чужие файлы трогать нельзя", otherStale.exists())
    }

    @Test
    fun `gc tolerates missing dirs and returns zero`() {
        assertEquals(0, cleanupOrphanBackupTemp(listOf(null, File(folder.root, "nope"))))
    }

    // ---- dirHintFromDocumentId ----

    @Test
    fun `raw document id resolves containing directory`() {
        // Абсолютный путь — от platform-реального temp-пути (Android/Linux —
        // /storage-подобные, Windows — с диском); ожидаем тот же каталог.
        val zip = File(folder.root, "Documents/backup.zip")
        val hint = dirHintFromDocumentId("raw:" + zip.absolutePath, null)
        assertEquals(zip.parentFile, hint)
    }

    @Test
    fun `primary document id resolves against external root`() {
        val root = folder.root
        val hint = dirHintFromDocumentId("primary:Download/backup.zip", root)
        assertEquals(File(root, "Download"), hint)
    }

    @Test
    fun `unknown provider document ids and nulls resolve to null`() {
        assertNull(dirHintFromDocumentId(null, null))
        assertNull(dirHintFromDocumentId("com.other.provider%2Fdoc/123", folder.root))
        // Относительный raw-путь — не абсолют, каталог неизвестен.
        assertNull(dirHintFromDocumentId("raw:backup.zip", folder.root))
    }
}
