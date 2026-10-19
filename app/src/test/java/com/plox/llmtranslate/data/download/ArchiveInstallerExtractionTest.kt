package com.plox.llmtranslate.data.download

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Юнит-тесты распаковки [ArchiveInstaller] (ревью, п.1): при обычном сбое
 * и при zip-slip полу-распакованные файлы обязаны удаляться — иначе осколки
 * смешиваются со следующим распакованным деревом той же модели.
 */
class ArchiveInstallerExtractionTest {

    private val installer = ArchiveInstaller()

    private fun tempDir(): File =
        File.createTempFile("archtest", "").apply {
            delete()
            mkdirs()
        }

    private fun zipBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `failed entry write discards partially unpacked files`() = runBlocking {
        val root = tempDir()
        try {
            // «f.bin» скачивается файлом, а следующая запись пишет внутрь
            // «f.bin/» — FileNotFoundException посреди распаковки: детерминированный
            // обычный сбой (не отмена и не zip-slip).
            val archive = File(root, "model.zip")
            archive.writeBytes(
                zipBytes(
                    listOf(
                        "good.txt" to "good".toByteArray(),
                        "f.bin" to "bin".toByteArray(),
                        "f.bin/inner.txt" to "inner".toByteArray()
                    )
                )
            )
            val outDir = File(root, "model")
            outDir.mkdirs()

            val extracted = installer.extractZip(archive, outDir)

            // Сбой распаковки — false, а записанные до сбоя файлы удалены,
            // следующий заход распаковки стартует с чистого каталога.
            assertFalse(extracted)
            assertFalse(File(outDir, "good.txt").exists())
            assertFalse(File(outDir, "f.bin").exists())
            assertFalse(File(outDir, "inner.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `zip-slip throws security exception and cleans created files`() = runBlocking {
        val root = tempDir()
        try {
            val archive = File(root, "model.zip")
            archive.writeBytes(
                zipBytes(
                    listOf(
                        "ok.txt" to "ok".toByteArray(),
                        "../evil.txt" to "evil".toByteArray()
                    )
                )
            )
            val outDir = File(root, "model")
            outDir.mkdirs()

            // SecurityException всплывает выше — install() переводит её
            // в permanent-отказ установки.
            assertThrows(SecurityException::class.java) {
                runBlocking { installer.extractZip(archive, outDir) }
            }
            assertFalse(File(outDir, "ok.txt").exists())
            assertFalse(File(root, "evil.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `corrupted gzip archive returns false without leftovers`() = runBlocking {
        val root = tempDir()
        try {
            val archive = File(root, "model.tar.gz")
            archive.writeBytes("not a gzip stream at all".toByteArray())
            val outDir = File(root, "model")
            outDir.mkdirs()

            assertFalse(installer.extractTar(archive, outDir, bzip2 = false))
            assertEquals(0, outDir.walkTopDown().filter { it.isFile }.count())
        } finally {
            root.deleteRecursively()
        }
    }
}
