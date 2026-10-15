package com.plox.llmtranslate.data.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * Instrumentation-тесты [SlimtInstaller]: скачивание gzip-паков Bergamot с
 * проверкой SHA-256 по байтам архива и распаковкой в каталог модели.
 * Сервер — [LocalTestHttpServer] на loopback, фикстуры — реальные gzip
 * (GZIPOutputStream), содержимое произвольно.
 */
@RunWith(AndroidJUnit4::class)
class SlimtInstallerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private val modelBytes = gzip("fake-marian-alphas-model-bytes-".repeat(40).toByteArray())
    private val lexBytes = gzip("fake-shortlist-lex-bytes".toByteArray())
    private val vocabBytes = gzip("fake-sentencepiece-vocab".toByteArray())

    private lateinit var targetDir: File
    private var server: LocalTestHttpServer? = null

    @Before
    fun setUp() {
        targetDir = File(context.cacheDir, "slimt_installer_test")
        targetDir.deleteRecursively()
        targetDir.mkdirs()
    }

    @After
    fun tearDown() {
        server?.close()
        server = null
        targetDir.deleteRecursively()
    }

    private fun startServer(files: Map<String, ByteArray>): LocalTestHttpServer {
        val s = LocalTestHttpServer(files)
        server = s
        return s
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun multiPackGzFilesUnpackIntoModelDirWithPerIndexSha256s() {
        val http = startServer(
            mapOf(
                "/model.ruen.intgemm.alphas.bin.gz" to modelBytes,
                "/lex.50.50.ruen.s2t.bin.gz" to lexBytes,
                "/vocab.ruen.spm.gz" to vocabBytes
            )
        )
        val urls = listOf(
            "${http.baseUrl}/model.ruen.intgemm.alphas.bin.gz",
            "${http.baseUrl}/lex.50.50.ruen.s2t.bin.gz",
            "${http.baseUrl}/vocab.ruen.spm.gz"
        )
        val sha256s = listOf(sha256(modelBytes), sha256(lexBytes), sha256(vocabBytes))

        val result = runBlocking {
            SlimtInstaller().install(urls, null, sha256s, targetDir, "firefox-bergamot-ru-en") {}
        }

        assertTrue("Ожидался Success, получено: $result", result is InstallResult.Success)
        val modelDir = File(targetDir, "firefox-bergamot-ru-en")
        assertEquals(modelDir.absolutePath, (result as InstallResult.Success).path)

        // Распакованные файлы без .gz с исходным содержимым.
        val model = File(modelDir, "model.ruen.intgemm.alphas.bin")
        val lex = File(modelDir, "lex.50.50.ruen.s2t.bin")
        val vocab = File(modelDir, "vocab.ruen.spm")
        assertTrue(model.isFile)
        assertTrue(lex.isFile)
        assertTrue(vocab.isFile)
        assertEquals(
            "fake-marian-alphas-model-bytes-".repeat(40).toByteArray().toList(),
            model.readBytes().toList()
        )
        // Промежуточные .gz удалены.
        assertFalse(File(modelDir, "model.ruen.intgemm.alphas.bin.gz").exists())
        assertFalse(File(modelDir, "lex.50.50.ruen.s2t.bin.gz").exists())
        assertFalse(File(modelDir, "vocab.ruen.spm.gz").exists())
    }

    @Test
    fun mismatchOnOneUrlIsPermanentAfterFreshDownload() {
        val http = startServer(
            mapOf(
                "/model.ruen.intgemm.alphas.bin.gz" to modelBytes,
                "/lex.50.50.ruen.s2t.bin.gz" to lexBytes
            )
        )
        val urls = listOf(
            "${http.baseUrl}/model.ruen.intgemm.alphas.bin.gz",
            "${http.baseUrl}/lex.50.50.ruen.s2t.bin.gz"
        )
        // Неверный хеш для lex — mismatch на свежей загрузке.
        val sha256s = listOf(sha256(modelBytes), sha256("wrong".toByteArray()))

        val result = runBlocking {
            SlimtInstaller().install(urls, null, sha256s, targetDir, "m") {}
        }
        assertTrue("Ожидался Failure, получено: $result", result is InstallResult.Failure)
        assertTrue(
            "mismatch после свежей загрузки обязан быть permanent",
            (result as InstallResult.Failure).permanent
        )
    }

    @Test
    fun nullSha256EntrySkipsCheckForThatFileOnly() {
        val http = startServer(
            mapOf(
                "/vocab.ruen.spm.gz" to vocabBytes,
                "/lex.50.50.ruen.s2t.bin.gz" to lexBytes
            )
        )
        val urls = listOf(
            "${http.baseUrl}/vocab.ruen.spm.gz",
            "${http.baseUrl}/lex.50.50.ruen.s2t.bin.gz"
        )
        // Первый файл — без проверки.
        val r = runBlocking {
            SlimtInstaller().install(urls, null, listOf(null, sha256(lexBytes)), targetDir, "npl") {}
        }
        assertTrue("Ожидался Success, получено: $r", r is InstallResult.Success)
        assertTrue(File(File(targetDir, "npl"), "vocab.ruen.spm").isFile)
        assertTrue(File(File(targetDir, "npl"), "lex.50.50.ruen.s2t.bin").isFile)
    }

    @Test
    fun nonGzFileKeptAsIs() {
        val plain = "already-unpacked-vocab-bytes".toByteArray()
        val http = startServer(mapOf("/vocab.enru.spm" to plain))
        val r = runBlocking {
            SlimtInstaller().install(
                listOf("${http.baseUrl}/vocab.enru.spm"), null,
                listOf(sha256(plain)), targetDir, "plain", {}
            )
        }
        assertTrue("Ожидался Success, получено: $r", r is InstallResult.Success)
        assertTrue(File(File(targetDir, "plain"), "vocab.enru.spm").isFile)
    }
}
