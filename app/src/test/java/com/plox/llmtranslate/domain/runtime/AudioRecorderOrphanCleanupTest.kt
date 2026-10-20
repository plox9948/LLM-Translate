package com.plox.llmtranslate.domain.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Unit-тесты очистки osиротевших временных WAV в кэше (чистая файловая
 * логика [AudioRecorder.cleanupOrphanRecordings] — единственная часть старта
 * записи, покрытая без AudioRecord/JVM-стибов android.media, аудит #83):
 * удаляются только recording_*.wav старше порога, живые и чужие файлы — нет.
 */
class AudioRecorderOrphanCleanupTest {

    private val oldMs = System.currentTimeMillis() - 48 * 60 * 60 * 1000L
    private val freshMs = System.currentTimeMillis()

    private fun tempCacheDir(): File =
        Files.createTempDirectory("orphan_cache").toFile().apply { deleteOnExit() }

    private fun File.touch(lastModified: Long): File = apply {
        createNewFile()
        setLastModified(lastModified)
    }

    @Test
    fun `deletes only stale recording wav files`() {
        val dir = tempCacheDir()
        val stale = File(dir, "recording_1.wav").touch(oldMs)
        val fresh = File(dir, "recording_3.wav").touch(freshMs)
        val otherExt = File(dir, "recording_4.txt").touch(oldMs)
        val otherPrefix = File(dir, "model_backup.wav").touch(oldMs)

        AudioRecorder.cleanupOrphanRecordings(dir)

        assertFalse(stale.exists())
        assertTrue(fresh.exists())
        assertTrue(otherExt.exists())
        assertTrue(otherPrefix.exists())
    }

    @Test
    fun `missing cache dir listing is tolerated`() {
        val missing = File(tempCacheDir(), "no_such_dir")
        AudioRecorder.cleanupOrphanRecordings(missing)
    }
}
