package com.plox.llmtranslate.data.update

import android.content.Context
import com.plox.llmtranslate.domain.update.DownloadApkResult
import com.plox.llmtranslate.domain.update.ReleaseInfo
import com.plox.llmtranslate.domain.update.UpdateCheckError
import com.plox.llmtranslate.domain.update.UpdateCheckResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Тесты маппинга проверки обновлений и скачивания APK (issue #95).
 *
 * Сеть подменяется на уровне seamless-функции [GitHubReleaseRepository.fetchReleaseJson]
 * (spyk — по образцу internal-швов RemoteCatalogProvider), скачивание APK
 * проверяется на локальном петлевом HTTP-сервере (как instrumentation-фикстуры
 * LocalTestHttpServer, но на JVM).
 */
class GitHubReleaseRepositoryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private val releaseNew = ReleaseInfo(
        tagName = "v0.6.0",
        version = "0.6.0",
        releaseUrl = "https://github.com/plox9948/LLM-Translate/releases/tag/v0.6.0",
        apkUrl = "",
        assetName = "app-release.apk"
    )

    private fun repositoryFor(cacheDir: File) = GitHubReleaseRepository(
        mockk<Context> { every { this@mockk.cacheDir } returns cacheDir },
        json
    )

    @Test
    fun `newer stable release becomes UpdateAvailable`() = runTest {
        val repo = spyk(repositoryFor(tmpFolder.newFolder()))
        coEvery { repo.fetchReleaseJson(any()) } returns FetchJsonResult.Ok(
            """{"tag_name":"v0.6.0","draft":false,"prerelease":false,
               "html_url":"https://example.com/r",
               "assets":[{"name":"a.apk","content_type":"application/vnd.android.package-archive",
                          "browser_download_url":"https://example.com/a.apk"}]}"""
        )
        val result = repo.checkForUpdate("0.5.18")
        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        assertEquals("0.6.0", (result as UpdateCheckResult.UpdateAvailable).release.version)
    }

    @Test
    fun `same or older release is UpToDate`() = runTest {
        val repo = spyk(repositoryFor(tmpFolder.newFolder()))
        coEvery { repo.fetchReleaseJson(any()) } returns FetchJsonResult.Ok(
            """{"tag_name":"v0.5.18","draft":false,"prerelease":false,"html_url":"",
               "assets":[{"name":"a.apk","content_type":"application/vnd.android.package-archive",
                          "browser_download_url":"https://example.com/a.apk"}]}"""
        )
        assertEquals(UpdateCheckResult.UpToDate, repo.checkForUpdate("0.5.18"))
    }

    @Test
    fun `http 404 maps to NotFound`() = runTest {
        val repo = spyk(repositoryFor(tmpFolder.newFolder()))
        coEvery { repo.fetchReleaseJson(any()) } returns FetchJsonResult.NotFound
        assertEquals(
            UpdateCheckResult.Failed(UpdateCheckError.NotFound),
            repo.checkForUpdate("0.5.18")
        )
    }

    @Test
    fun `network failure maps to Network`() = runTest {
        val repo = spyk(repositoryFor(tmpFolder.newFolder()))
        coEvery { repo.fetchReleaseJson(any()) } returns FetchJsonResult.Network("timeout")
        assertEquals(
            UpdateCheckResult.Failed(UpdateCheckError.Network),
            repo.checkForUpdate("0.5.18")
        )
    }

    @Test
    fun `draft latest and broken bodies map through evaluate`() = runTest {
        val repo = repositoryFor(tmpFolder.newFolder())
        val draft = """{"tag_name":"v9.9.9","draft":true,"prerelease":false,"html_url":"","assets":[]}"""
        assertEquals(UpdateCheckResult.UpToDate, repo.evaluateReleaseJson(draft, "0.5.18"))
        val noAsset = """{"tag_name":"v9.9.9","draft":false,"prerelease":false,"html_url":"","assets":[]}"""
        assertEquals(
            UpdateCheckResult.Failed(UpdateCheckError.NoAsset),
            repo.evaluateReleaseJson(noAsset, "0.5.18")
        )
        assertEquals(
            UpdateCheckResult.Failed(UpdateCheckError.Parse),
            repo.evaluateReleaseJson("broken", "0.5.18")
        )
    }

    /**
     * Одноразовый HTTP-сервер на сыром ServerSocket (com.sun.net.httpserver в
     * unit-test classpath Android недоступен — прецедент BaseDownloaderHttpOutcomeTest):
     * читает запрос до пустой строки и отдаёт [header] + [body], закрывает соединение.
     */
    private fun serveOnce(header: String, body: ByteArray): Int {
        val server = java.net.ServerSocket(0)
        val thread = Thread {
            try {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader(java.nio.charset.StandardCharsets.UTF_8)
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    socket.getOutputStream().use { out ->
                        out.write(header.replace("\n", "\r\n").toByteArray(java.nio.charset.StandardCharsets.US_ASCII))
                        out.write(body)
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
    fun `downloadApk writes final file, cleans old dir and emits integer progress`() = runTest {
        val body = ByteArray(4096) { (it % 251).toByte() }
        val port = serveOnce(
            "HTTP/1.1 200 OK\n" +
                "Content-Type: application/vnd.android.package-archive\n" +
                "Content-Length: ${body.size}\n" +
                "Connection: close\n" +
                "\n",
            body
        )
        run {
            val cacheDir = tmpFolder.newFolder()
            val updatesDir = File(cacheDir, "updates")
            updatesDir.mkdirs()
            val stale = File(updatesDir, "old-update.apk").apply { writeBytes(byteArrayOf(1)) }

            val repo = repositoryFor(cacheDir)
            val progress = mutableListOf<Int>()
            val result = repo.downloadApk(
                releaseNew.copy(apkUrl = "http://127.0.0.1:$port/update.apk")
            ) { progress.add(it) }

            assertTrue(result is DownloadApkResult.Success)
            val apk = (result as DownloadApkResult.Success).file
            assertEquals("update.apk", apk.name)
            assertTrue("содержимое APK не совпало", apk.readBytes().contentEquals(body))
            assertFalse("старый файл не удалён", stale.exists())
            assertFalse("update.apk.tmp не переименован в финальный файл",
                File(updatesDir, "update.apk.tmp").exists())
            assertTrue("нет финального 100%", progress.lastOrNull() == 100)
            // Дебаунс: целые проценты без повторов подряд.
            assertEquals(progress.distinct(), progress)
        }
    }

    @Test
    fun `downloadApk http 404 maps to NotFound`() = runTest {
        val port = serveOnce(
            "HTTP/1.1 404 Not Found\n" +
                "Content-Length: 0\n" +
                "Connection: close\n" +
                "\n",
            ByteArray(0)
        )
        val result = repositoryFor(tmpFolder.newFolder()).downloadApk(
            releaseNew.copy(apkUrl = "http://127.0.0.1:$port/missing.apk")
        ) {}
        assertEquals(DownloadApkResult.Failed(UpdateCheckError.NotFound), result)
    }
}
