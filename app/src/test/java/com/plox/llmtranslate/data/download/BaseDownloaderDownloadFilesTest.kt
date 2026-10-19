package com.plox.llmtranslate.data.download

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Юнит-тесты пропуска уже скачанных файлов мультифайлового пакета в
 * [BaseDownloader.downloadFiles] (issue #40): WorkManager-повтор не должен
 * перекачивать завершённые файлы пакета заново. Сеть не поднимается —
 * URL указывают на закрытый порт, любое фактическое скачивание даёт
 * NetworkError с именем файла, из-за чего «пропуск» отличим от «загрузки».
 */
class BaseDownloaderDownloadFilesTest {

    private class TestDownloader : BaseDownloader() {
        suspend fun fetch(
            urls: List<String>,
            targetDir: File,
            modelId: String,
            sha256s: List<String?> = emptyList()
        ): DownloadOutcome = downloadFiles(urls, targetDir, modelId, sha256s) {}

        suspend fun fetchSingle(
            url: String,
            targetDir: File,
            fileName: String,
            expectedSha: String?
        ): DownloadOutcome = downloadFile(url, targetDir, fileName, expectedSha) {}
    }

    private val downloader = TestDownloader()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun tempDir(): File =
        File.createTempFile("dltest", "").apply {
            delete()
            mkdirs()
        }

    /** Закрытый порт: любое обращение даёт немедленный отказ в соединении. */
    private fun deadUrl(name: String) = "http://127.0.0.1:1/$name"

    @Test
    fun `completes without network when all done files match sha`() = runBlocking {
        val root = tempDir()
        try {
            val modelDir = File(root, "model-1")
            modelDir.mkdirs()
            val a = "aaa".toByteArray()
            val b = "bbbb".toByteArray()
            File(modelDir, "a.onnx").writeBytes(a)
            File(modelDir, "b.onnx").writeBytes(b)
            // Осиротевшие части прерванной попытки рядом — тоже чистятся.
            File(modelDir, "a.onnx.part").writeBytes(byteArrayOf(0))
            File(modelDir, "a.onnx.part.meta").writeText("meta")

            val outcome = downloader.fetch(
                urls = listOf(deadUrl("a.onnx"), deadUrl("b.onnx")),
                targetDir = root,
                modelId = "model-1",
                sha256s = listOf(sha256(a), sha256(b))
            )

            assertTrue(outcome is DownloadOutcome.Success)
            assertSameBytes(a, File(modelDir, "a.onnx").readBytes())
            assertSameBytes(b, File(modelDir, "b.onnx").readBytes())
            assertFalse(File(modelDir, "a.onnx.part").exists())
            assertFalse(File(modelDir, "a.onnx.part.meta").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `missing file fails on that file when previous files were skipped`() = runBlocking {
        val root = tempDir()
        try {
            val modelDir = File(root, "model-1")
            modelDir.mkdirs()
            val a = "aaa".toByteArray()
            File(modelDir, "a.onnx").writeBytes(a)

            val outcome = downloader.fetch(
                urls = listOf(deadUrl("a.onnx"), deadUrl("b.onnx")),
                targetDir = root,
                modelId = "model-1",
                sha256s = listOf(sha256(a), null)
            )

            // Ошибка относится ко второму файлу: первый пропущен, не перекачивался.
            assertTrue(outcome is DownloadOutcome.NetworkError)
            assertTrue(
                (outcome as DownloadOutcome.NetworkError).message.contains("b.onnx")
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `done file with mismatching sha is re-downloaded`() = runBlocking {
        val root = tempDir()
        try {
            val modelDir = File(root, "model-1")
            modelDir.mkdirs()
            File(modelDir, "a.onnx").writeBytes("aaa".toByteArray())

            val outcome = downloader.fetch(
                urls = listOf(deadUrl("a.onnx")),
                targetDir = root,
                modelId = "model-1",
                sha256s = listOf(sha256("other".toByteArray()))
            )

            // SHA не совпал — файл не пропущен, предпринята загрузка (упала в сеть).
            assertTrue(outcome is DownloadOutcome.NetworkError)
            assertTrue(
                (outcome as DownloadOutcome.NetworkError).message.contains("a.onnx")
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `done files skipped without sha when no unfinished part exists`() = runBlocking {
        val root = tempDir()
        try {
            val modelDir = File(root, "model-1")
            modelDir.mkdirs()
            File(modelDir, "a.onnx").writeBytes("aaa".toByteArray())
            File(modelDir, "b.onnx").writeBytes("bbb".toByteArray())

            val outcome = downloader.fetch(
                urls = listOf(deadUrl("a.onnx"), deadUrl("b.onnx")),
                targetDir = root,
                modelId = "model-1"
            )

            assertTrue(outcome is DownloadOutcome.Success)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `single downloadFile reuses done file matching sha without network`() = runBlocking {
        val root = tempDir()
        try {
            // Ревью, п.4: одиночная загрузка (архив/одиночный ONNX) обязана
            // переиспользовать целый целевой файл предыдущей попытки —
            // URL на закрытый порт: любой факт перекачки дал бы NetworkError.
            val payload = "payload".toByteArray()
            File(root, "model.onnx").writeBytes(payload)
            File(root, "model.onnx.part").writeBytes(byteArrayOf(7))
            File(root, "model.onnx.part.meta").writeText("stale")

            val outcome = downloader.fetchSingle(
                url = deadUrl("model.onnx"),
                targetDir = root,
                fileName = "model.onnx",
                expectedSha = sha256(payload)
            )

            assertTrue(outcome is DownloadOutcome.Success)
            assertSameBytes(payload, File(root, "model.onnx").readBytes())
            // Осиротевшие части докачки рядом с целым целевым файлом убраны.
            assertFalse(File(root, "model.onnx.part").exists())
            assertFalse(File(root, "model.onnx.part.meta").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertSameBytes(expected: ByteArray, actual: ByteArray) =
        assertEquals(expected.joinToString(), actual.joinToString())
}
