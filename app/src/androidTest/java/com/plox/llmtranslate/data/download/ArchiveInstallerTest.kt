package com.plox.llmtranslate.data.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Instrumentation-тесты [ArchiveInstaller] на устройстве без внешней сети:
 * zip-фикстуры собираются на устройстве [ZipOutputStream], tar.bz2 —
 * TarArchiveOutputStream + BZip2CompressorOutputStream (Apache Commons
 * Compress уже в зависимостях распаковщика), архивы раздаёт
 * [LocalTestHttpServer] на loopback.
 *
 * Проверяются: успех распаковки (zip и tar.bz2 c подтягиванием подкаталога),
 * zip-slip (выход за каталог модели), очистка частично распакованных файлов
 * при отмене корутины (discardPartial) с сохранением ранее скачанных файлов,
 * и удаление архива-сироты из корня каталога при CancellationException (С4).
 */
@RunWith(AndroidJUnit4::class)
class ArchiveInstallerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var targetDir: File
    private var server: LocalTestHttpServer? = null

    @Before
    fun setUp() {
        targetDir = File(context.cacheDir, "archive_installer_test")
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

    /** Собрать zip в памяти (STORED — байты как есть, без компрессии). */
    private fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, data) ->
                val entry = ZipEntry(name)
                entry.method = ZipEntry.STORED
                entry.size = data.size.toLong()
                val crc = CRC32()
                crc.update(data)
                entry.crc = crc.value
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * tar.bz2 фикстура — собранная на ПК (python tarfile, ustar+bzip2) и
     * лежащая в androidTest assets; тест берёт её байты как есть и проверяет
     * bzip2-магический заголовок.
     */
    private fun tarBz2Asset(): ByteArray {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("sherpa_dummy.tar.bz2").use { it.readBytes() }
        assertTrue(
            "Фикстура не похожа на bzip2 (магический заголовок BZh)",
            bytes.size > 3 && bytes[0] == 'B'.code.toByte() &&
                bytes[1] == 'Z'.code.toByte() && bytes[2] == 'h'.code.toByte()
        )
        return bytes
    }

    /** Проверить, что zip-байты читаются ZipInputStream (валидность фикстуры). */
    private fun assertZipReadable(bytes: ByteArray) {
        ZipInputStream(bytes.inputStream()).use { zip ->
            assertNotNull("У zip-фикстуры нет ни одной записи", zip.nextEntry)
        }
    }

    // --- успех распаковки ----------------------------------------------------

    @Test
    fun zipArchiveUnpacksIntoModelDirAndArchiveIsRemoved() {
        val zip = zipBytes(
            mapOf(
                "tokens.txt" to "a b c\n".toByteArray(),
                "model.onnx" to "pseudo-zip-model-bytes".toByteArray()
            )
        )
        assertZipReadable(zip)
        val http = startServer(mapOf("/model.zip" to zip))

        val result = runBlocking {
            ArchiveInstaller().install(
                listOf("${http.baseUrl}/model.zip"),
                null,
                listOf(sha256(zip)),
                targetDir,
                "stt-fixture"
            ) {}
        }

        assertTrue("Ожидался Success, получено: $result", result is InstallResult.Success)
        val modelDir = File(targetDir, "stt-fixture")
        assertEquals(modelDir.absolutePath, (result as InstallResult.Success).path)
        assertEquals("a b c\n", File(modelDir, "tokens.txt").readText())
        assertEquals(
            "pseudo-zip-model-bytes",
            File(modelDir, "model.onnx").readText()
        )
        // Скачанный архив удалён из корня каталога рантайма (не сирота, С4).
        // Имя архива — новая схема с префиксом id (DownloadLayout, ревью п.15).
        assertFalse(File(targetDir, "stt-fixture__model.zip").exists())
        assertFalse(File(targetDir, "stt-fixture__model.zip.part").exists())
    }

    @Test
    fun tarbz2FixtureUnpacksAndSingleSubdirIsFlattened() {
        val tarBz2 = tarBz2Asset()
        val http = startServer(mapOf("/dummy.tar.bz2" to tarBz2))

        val result = runBlocking {
            ArchiveInstaller().install(
                listOf("${http.baseUrl}/dummy.tar.bz2"),
                null,
                listOf(sha256(tarBz2)),
                targetDir,
                "stt-tarbz"
            ) {}
        }

        assertTrue("Ожидался Success, получено: $result", result is InstallResult.Success)
        val modelDir = File(targetDir, "stt-tarbz")
        // flattenModelDir: файлы из единственного подкаталога подняты наверх.
        // Содержимое совпадает с фикстурой из assets/sherpa_dummy.tar.bz2.
        assertEquals("<blk>\n<s>, .\n", File(modelDir, "tokens.txt").readText())
        assertEquals(
            "pseudo-tarbz-encoder",
            File(modelDir, "encoder-model.onnx").readText()
        )
        assertFalse("Подкаталог после flatten должен быть удалён", File(modelDir, "dummy-sherpa").exists())
        assertFalse(File(targetDir, "stt-tarbz__dummy.tar.bz2").exists())
    }

    @Test
    fun archiveShaMismatchFailsPermanent() {
        val zip = zipBytes(mapOf("tokens.txt" to "x\n".toByteArray()))
        val http = startServer(mapOf("/badhash.zip" to zip))

        val result = runBlocking {
            ArchiveInstaller().install(
                listOf("${http.baseUrl}/badhash.zip"),
                null,
                listOf("ff".repeat(32)),
                targetDir,
                "stt-badhash"
            ) {}
        }

        val failure = result as? InstallResult.Failure
        assertNotNull("Ожидался Failure по SHA архива, получено: $result", failure)
        assertTrue("Свежий mismatch — permanent", failure!!.permanent)
    }

    // --- zip-slip ------------------------------------------------------------

    @Test
    fun zipSlipEntryEscapingModelDirIsRejected() {
        // Запись ../escaped.txt при destDir=каталог модели выходит в targetDir.
        val zip = zipBytes(
            mapOf(
                "safe.txt" to "safe".toByteArray(),
                "../escaped.txt" to "evil".toByteArray(),
                "deep/../../escaped2.txt" to "evil2".toByteArray()
            )
        )
        val http = startServer(mapOf("/slip.zip" to zip))

        val result = runBlocking {
            ArchiveInstaller().install(
                listOf("${http.baseUrl}/slip.zip"),
                null,
                listOf(sha256(zip)),
                targetDir,
                "stt-slip"
            ) {}
        }

        val failure = result as? InstallResult.Failure
        assertNotNull("Ожидался Failure по zip-slip, получено: $result", failure)
        assertTrue(
            "Сообщение должно указывать на ошибку распаковки: ${failure!!.message}",
            failure.message.contains("Ошибка распаковки")
        )
        // Записи за пределами каталога модели не созданы.
        assertFalse("zip-slip: файл вышел за каталог модели", File(targetDir, "escaped.txt").exists())
        assertFalse("zip-slip: вложенный путь вышел за каталог модели", File(targetDir, "escaped2.txt").exists())
    }

    // --- отмена распаковки -----------------------------------------------------

    @Test
    fun cancelDuringExtractionDiscardsPartialFilesAndRemovesOrphanArchive() {
        val extraBytes = "prior-url-file".repeat(100).toByteArray()

        // Большой хвостовой файл: распаковка идёт десятки блоков, отмена
        // гарантированно попадает внутрь второй записи архива.
        val tail = ByteArray(8 * 1024 * 1024) { (it % 251).toByte() }
        val zip = zipBytes(
            linkedMapOf(
                "first.txt" to "first entry".toByteArray(),
                "second.bin" to tail
            )
        )
        val http = startServer(mapOf("/extra.bin" to extraBytes, "/cancel_archive.zip" to zip))
        val urls = listOf("${http.baseUrl}/extra.bin", "${http.baseUrl}/cancel_archive.zip")
        val hashes = listOf(sha256(extraBytes), sha256(zip))

        val modelDir = File(targetDir, "stt-cancel")
        val firstFile = File(modelDir, "first.txt")

        val scope = CoroutineScope(Dispatchers.IO + Job())
        val deferred = scope.async {
            ArchiveInstaller().install(urls, null, hashes, targetDir, "stt-cancel") {}
        }

        // Ждём появления ПЕРВОГО файла именно этой распаковки, затем отменяем.
        val deadline = System.currentTimeMillis() + 30_000
        while (!firstFile.exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(2)
        }
        assertTrue("Первый распакованный файл так и не появился", firstFile.exists())
        scope.cancel()

        var cancelled = false
        try {
            runBlocking { deferred.await() }
        } catch (e: CancellationException) {
            cancelled = true
        }
        assertTrue("install обязан всплеснуть CancellationException", cancelled)

        // discardPartial: файлы этой распаковки удалены полностью.
        assertFalse("first.txt этой распаковки не удалён", firstFile.exists())
        assertFalse("second.bin этой распаковки не удалён", File(modelDir, "second.bin").exists())
        // С4: архив-сирота из корня каталога удалён при отмене (имя — новая
        // схема с префиксом id, DownloadLayout/targetFileName).
        assertFalse("Архив остался сиротой в корне каталога", File(targetDir, "stt-cancel__cancel_archive.zip").exists())
        // Ранее скачанный файл другой записи НЕ удалён.
        assertEquals(
            "Ранее скачанный файл потерян",
            extraBytes.size,
            File(modelDir, "extra.bin").length().toInt()
        )
        // .part архива (если был) не обязан остаться — архив был докачан до конца.
        assertFalse(File(targetDir, "stt-cancel__cancel_archive.zip.part").exists())
    }
}
