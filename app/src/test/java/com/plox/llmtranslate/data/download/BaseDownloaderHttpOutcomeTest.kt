package com.plox.llmtranslate.data.download

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import timber.log.Timber
import java.nio.charset.StandardCharsets

/**
 * Юнит-тесты классификации HTTP-исходов [BaseDownloader] (issue #83, п.5-6):
 * редирект между протоколами (http -> https) HttpURLConnection не следует и
 * обязан давать различимую ошибку про редирект, а не generic Network; сбой
 * финального rename - файловая ошибка FileError, не Network-retry.
 *
 * Сервер - сырой ServerSocket (com.sun.net.httpserver в unit-test classpath
 * Android недоступен): один ответ на одно соединение, порт от системы.
 */
class BaseDownloaderHttpOutcomeTest {

    init {
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                println("LOG $priority [$tag] $message ${t?.message}")
            }
        })
    }

    private class TestDownloader : BaseDownloader() {
        suspend fun fetchSingle(
            url: String,
            targetDir: File,
            fileName: String,
            expectedSha: String?
        ): DownloadOutcome = downloadFile(url, targetDir, fileName, expectedSha) {}
    }

    private val downloader = TestDownloader()

    private fun tempDir(): File =
        File.createTempFile("httpoutcome", "").apply {
            delete()
            mkdirs()
        }

    /**
     * Одноразовый HTTP-сервер: читает запрос до пустой строки (иначе
     * HttpURLConnection ждёт тело) и отдаёт [response], после чего закрывает
     * соединение. Возвращает порт.
     */
    private fun serveOnce(response: String): Int {
        val server = ServerSocket(0)
        val thread = Thread {
            try {
                server.accept().use { socket ->
                    // НЕ закрываем reader сокета: в JDK17 close() потока ввода
                    // закрывает сокет целиком и ответ не доходит до клиента.
                    val input = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    socket.getOutputStream().use { out ->
                        out.write(response.replace("\n", "\r\n").toByteArray(StandardCharsets.US_ASCII))
                        out.flush()
                    }
                }
            } catch (_: Exception) {
                // Клиент мог закрыть соединение раньше - тесту это не мешает.
            }
        }
        thread.isDaemon = true
        thread.start()
        return server.localPort
    }

    @Test
    fun `cross-protocol redirect is classified as redirect error not generic network`() = runBlocking {
        val port = serveOnce(
            "HTTP/1.1 302 Found\n" +
                "Location: https://redirect.invalid/new\n" +
                "Content-Length: 0\n" +
                "Connection: close\n" +
                "\n"
        )
        val root = tempDir()
        try {
            val outcome = downloader.fetchSingle(
                url = "http://127.0.0.1:$port/old",
                targetDir = root,
                fileName = "model.onnx",
                expectedSha = null
            )
            assertTrue(
                "ожидалась сетевая ошибка, получено: $outcome",
                outcome is DownloadOutcome.NetworkError
            )
            val message = (outcome as DownloadOutcome.NetworkError).message
            assertTrue("текст не различает редирект: $message", message.contains("редирект"))
            assertTrue("текст не содержит код 302: $message", message.contains("302"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rename failure to occupied target is FileError not NetworkError`() = runBlocking {
        val payload = "payload"
        val port = serveOnce(
            "HTTP/1.1 200 OK\n" +
                "Content-Length: ${'$'}{payload.length}\n" +
                "Connection: close\n" +
                "\n" +
                payload
        )
        val root = tempDir()
        try {
            // Цель rename - непустой каталог: удаление цели не succeeds, а
            // rename файла на несуществующий в ФС путь-каталог возвращает
            // false детерминированно (и на Windows, и на POSIX).
            val done = File(root, "model.onnx")
            File(done, "occupied").mkdirs()
            File(done, "occupied/x").writeText("x")

            val outcome = downloader.fetchSingle(
                url = "http://127.0.0.1:$port/f",
                targetDir = root,
                fileName = "model.onnx",
                expectedSha = null
            )
            assertTrue(
                "ожидалась файловая ошибка, получено: $outcome",
                outcome is DownloadOutcome.FileError
            )
            assertTrue(
                (outcome as DownloadOutcome.FileError).message.contains("model.onnx")
            )
            // .part сохранён для ручного повтора, содержимое цели цело.
            assertEquals("x", File(done, "occupied/x").readText())
            assertTrue(File(root, "model.onnx.part").isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
