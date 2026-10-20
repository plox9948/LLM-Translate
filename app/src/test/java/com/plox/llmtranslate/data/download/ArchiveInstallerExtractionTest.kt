package com.plox.llmtranslate.data.download

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.io.File
import java.security.MessageDigest
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

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** Закрытый порт: любое фактическое скачивание даёт немедленный отказ. */
    private fun deadUrl(name: String) = "http://127.0.0.1:1/$name"

    @Test
    fun `corrupted archive matching sha fails permanent without re-download`() = runBlocking {
        val root = tempDir()
        try {
            // Целевой архив предыдущей попытки лежит в корне каталога рантайма
            // и совпадает с SHA-256: downloadFile обязан переиспользовать его
            // (URL на закрытый порт — любая перекачка дала бы NetworkError),
            // а сбой формата после верификации — детерминирован, permanent.
            val bytes = "not a gzip stream at all".toByteArray()
            val url = deadUrl("pack.tar.gz")
            val name = DownloadLayout.targetFileName("m1", url, 0, inModelDir = false)
            File(root, name).writeBytes(bytes)
            val sha = sha256Hex(bytes)

            val result = installer.install(
                listOf(url), sha, listOf(sha), root, "m1"
            ) {}

            assertTrue(result is InstallResult.Failure)
            assertTrue((result as InstallResult.Failure).permanent)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `uppercase archive suffix is dispatched case-insensitively`() = runBlocking {
        val root = tempDir()
        try {
            // Классификация isArchive регистронезависима, а dispatch по
            // суффиксу раньше падал в else для «PACK.ZIP» (ревью, п.3).
            val bytes = zipBytes(listOf("tokens.txt" to "ok".toByteArray()))
            val url = deadUrl("PACK.ZIP")
            val name = DownloadLayout.targetFileName("m1", url, 0, inModelDir = false)
            File(root, name).writeBytes(bytes)
            val sha = sha256Hex(bytes)

            val result = installer.install(
                listOf(url), sha, listOf(sha), root, "m1"
            ) {}

            assertTrue(result is InstallResult.Success)
            assertEquals("ok", File(DownloadLayout.modelDir(root, "m1"), "tokens.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun tarGzBytes(entries: List<TarArchiveEntry>, payloads: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                entries.forEach { entry ->
                    tar.putArchiveEntry(entry)
                    payloads[entry.name]?.let { tar.write(it) }
                    tar.closeArchiveEntry()
                }
            }
        }
        return out.toByteArray()
    }

    // -----------------------------------------------------------------
    // Ревью повт. аудита, п.5: flattenModelDir — нечитаемый список и
    // откат частичного переноса.
    // -----------------------------------------------------------------

    @Test
    fun `flatten rejects unreadable model dir instead of silent success`() {
        val root = tempDir()
        try {
            // destDir — обычный файл: listFiles() == null (нечитаемый
            // список) — раньше был тихий «успех» и неполный пучок
            // регистрировался READY.
            val notAdir = File(root, "model").apply { writeText("x") }
            assertFalse(installer.flattenModelDir(notAdir))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `flatten moves bundle subdir to root`() {
        val root = tempDir()
        try {
            val modelDir = File(root, "m1").apply { mkdirs() }
            val subdir = File(modelDir, "pack").apply { mkdirs() }
            File(subdir, "tokens.txt").writeText("tokens")
            File(subdir, "model.onnx").writeText("weights")
            assertTrue(installer.flattenModelDir(modelDir))
            assertEquals("tokens", File(modelDir, "tokens.txt").readText())
            assertEquals("weights", File(modelDir, "model.onnx").readText())
            assertFalse(subdir.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rollback returns already moved entries back into subdir`() {
        val root = tempDir()
        try {
            val modelDir = File(root, "m1").apply { mkdirs() }
            val subdir = File(modelDir, "pack").apply { mkdirs() }
            File(subdir, "tokens.txt").writeText("tokens")
            File(subdir, "model.onnx").writeText("weights")
            // Симулируем частичный перенос до отказа среднего entry:
            // tokens.txt и model.onnx уже в корне модели.
            val moved = listOf("tokens.txt", "model.onnx").map { name ->
                val target = File(modelDir, name)
                assertTrue(File(subdir, name).renameTo(target))
                target
            }
            installer.rollbackFlattenMoves(subdir, moved)
            assertFalse("tokens.txt не должен остаться в корне", File(modelDir, "tokens.txt").exists())
            assertFalse("model.onnx не должен остаться в корне", File(modelDir, "model.onnx").exists())
            assertEquals("tokens", File(subdir, "tokens.txt").readText())
            assertEquals("weights", File(subdir, "model.onnx").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `tar symlink entry is skipped and not written through as regular file`() = runBlocking {
        // Issue #83, п.4 (CVE-2022-41405): symlink-запись нельзя писать как
        // обычный файл с путём цели - следующая запись канонализуется ЧЕРЕЗ
        // неё и уйдёт за пределы destDir.
        val root = tempDir()
        try {
            val good = TarArchiveEntry("model.bin")
                .also { it.size = 7L }
            val link = TarArchiveEntry("passage", TarConstants.LF_SYMLINK)
                .also { it.setLinkName("/var/lib/outside.bin") }
            val hardlink = TarArchiveEntry("model.lnk", TarConstants.LF_LINK)
                .also { it.setLinkName("model.bin") }
            val after = TarArchiveEntry("passage/nested.bin")
                .also { it.size = 5L }
            val archive = File(root, "model.tar.gz")
            archive.writeBytes(
                tarGzBytes(
                    listOf(good, link, hardlink, after),
                    mapOf(
                        "model.bin" to "payload".toByteArray(),
                        "passage/nested.bin" to "other".toByteArray()
                    )
                )
            )
            val outDir = File(root, "model")
            outDir.mkdirs()

            val extracted = installer.extractTar(archive, outDir, bzip2 = false)

            // Распаковка продолжается: обычная запись извлечена, ссылочная -
            // пропущена (не стала файлом с содержимым-путём), запись ПОСЛЕ
            // ссылки разворачивается штатно в destDir.
            assertTrue(extracted)
            assertEquals("payload", File(outDir, "model.bin").readText())
            assertFalse("symlink записан как обычный файл", File(outDir, "passage").isFile)
            assertFalse("hardlink записан как обычный файл", File(outDir, "model.lnk").exists())
            assertEquals("other", File(outDir, "passage/nested.bin").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
