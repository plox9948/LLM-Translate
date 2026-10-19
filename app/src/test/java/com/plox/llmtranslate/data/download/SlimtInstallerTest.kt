package com.plox.llmtranslate.data.download

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * Юнит-тесты решения о gunzip в [SlimtInstaller] (ревью, п.3 и п.5):
 * суффикс `.gz` без учёта регистра и магические байты 0x1F 0x8B как
 * дополнительное основание распаковки. Сеть не поднимается: целевые файлы
 * предыдущей попытки совпадают с SHA-256 и переиспользуются, URL — закрытый
 * порт (любая фактическая перекачка дала бы NetworkError).
 */
class SlimtInstallerTest {

    private val installer = SlimtInstaller()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun tempDir(): File =
        File.createTempFile("slimttest", "").apply {
            delete()
            mkdirs()
        }

    @Test
    fun `gzip without gz suffix unpacked by magic bytes`() = runBlocking {
        val root = tempDir()
        try {
            // Имя без суффикса .gz, содержимое — gzip-поток: распаковка
            // определяется по сигнатуре 0x1F 0x8B, результат занимает имя
            // источника, осколков (временного файла) не остаётся.
            val payload = "vocab sentencepieces".toByteArray()
            val packed = gzip(payload)
            val url = "http://127.0.0.1:1/vocab.spm"
            val modelDir = File(root, "m1")
            modelDir.mkdirs()
            File(modelDir, "vocab.spm").writeBytes(packed)

            val result = installer.install(
                listOf(url), sha256Hex(packed), listOf(sha256Hex(packed)), root, "m1"
            ) {}

            assertTrue(result is InstallResult.Success)
            assertArrayEquals(payload, File(modelDir, "vocab.spm").readBytes())
            assertFalse(File(modelDir, "vocab.spm.gunzip.tmp").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `uppercase gz suffix unpacked case-insensitively`() = runBlocking {
        val root = tempDir()
        try {
            // «MODEL.BIN.GZ» раньше не распознавался суффиксом .gz и ложился
            // в каталог сжатым под именем .GZ (ревью, п.3).
            val payload = "intgemm weights".toByteArray()
            val packed = gzip(payload)
            val url = "http://127.0.0.1:1/model.bin.GZ"
            val modelDir = File(root, "m1")
            modelDir.mkdirs()
            File(modelDir, "model.bin.GZ").writeBytes(packed)

            val result = installer.install(
                listOf(url), sha256Hex(packed), listOf(sha256Hex(packed)), root, "m1"
            ) {}

            assertTrue(result is InstallResult.Success)
            assertArrayEquals(payload, File(modelDir, "model.bin").readBytes())
            assertFalse(File(modelDir, "model.bin.GZ").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
