package com.plox.llmtranslate.data.update

import android.content.Context
import com.plox.llmtranslate.data.download.HTTP_CONNECT_TIMEOUT_MS
import com.plox.llmtranslate.data.download.HTTP_READ_TIMEOUT_MS
import com.plox.llmtranslate.data.download.TRANSFER_BUFFER_BYTES
import com.plox.llmtranslate.domain.update.DownloadApkResult
import com.plox.llmtranslate.domain.update.ReleaseInfo
import com.plox.llmtranslate.domain.update.ReleaseParseResult
import com.plox.llmtranslate.domain.update.UpdateCheckError
import com.plox.llmtranslate.domain.update.UpdateCheckResult
import com.plox.llmtranslate.domain.update.UpdateReleaseParser
import com.plox.llmtranslate.domain.update.UpdateReleaseRepository
import com.plox.llmtranslate.domain.update.UpdateVersionLogic
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Исход одного HTTP-GET релизного JSON (internal — разделение сети и
 * маппинга для unit-тестов: [GitHubReleaseRepository.evaluateReleaseJson]
 * тестируется фикстурами JSON без сети).
 */
internal sealed class FetchJsonResult {
    data class Ok(val body: String) : FetchJsonResult()
    object NotFound : FetchJsonResult()
    data class Network(val message: String) : FetchJsonResult()
}

/**
 * Репозиторий самообновления через GitHub Releases (issue #95).
 *
 * GitHub Releases API: GET `releases/latest` — только публичные стабильные
 * релизы; User-Agent обязателен (GitHub отвечает 403 без него). SHA-256
 * APK-ассета не сверяется: GitHub API не отдаёт гарантированного sha256,
 * целостность обеспечивает HTTPS-транспорт.
 */
@Singleton
class GitHubReleaseRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    json: Json
) : UpdateReleaseRepository {

    companion object {
        /** Endpoint latest stable релиза публичного репозитория релизов. */
        const val LATEST_RELEASE_API_URL =
            "https://api.github.com/repos/plox9948/LLM-Translate/releases/latest"

        /** GitHub API отклоняет запросы без User-Agent (HTTP 403). */
        private const val USER_AGENT = "LLM-Translate-updater"

        /** Версионированный Accept релизного API. */
        private const val ACCEPT_HEADER = "application/vnd.github+json"

        /** HTTP-таймауты проверочного GET — как у удалённого каталога. */
        private const val CHECK_CONNECT_TIMEOUT_MS = 10_000
        private const val CHECK_READ_TIMEOUT_MS = 15_000

        /** Каталог APK-обновлений в кэше (отдаётся FileProvider по update_file_paths.xml). */
        private const val UPDATE_DIR_NAME = "updates"

        /** Имя итогового APK в [UPDATE_DIR_NAME]. */
        private const val UPDATE_APK_NAME = "update.apk"
    }

    private val parser = UpdateReleaseParser(json)

    override suspend fun checkForUpdate(currentVersion: String): UpdateCheckResult {
        return when (val fetch = fetchReleaseJson(LATEST_RELEASE_API_URL)) {
            is FetchJsonResult.NotFound -> UpdateCheckResult.Failed(UpdateCheckError.NotFound)
            is FetchJsonResult.Network -> {
                Timber.w("Самообновление: проверка не удалась — %s", fetch.message)
                UpdateCheckResult.Failed(UpdateCheckError.Network)
            }
            is FetchJsonResult.Ok -> evaluateReleaseJson(fetch.body, currentVersion)
        }
    }

    override suspend fun downloadApk(
        release: ReleaseInfo,
        onProgress: (Int) -> Unit
    ): DownloadApkResult = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, UPDATE_DIR_NAME)
        clearUpdateDir(dir)
        if (!dir.exists() && !dir.mkdirs()) {
            Timber.e("Не удалось создать каталог %s", dir.path)
            return@withContext DownloadApkResult.Failed(UpdateCheckError.Network)
        }
        val tmpFile = File(dir, "$UPDATE_APK_NAME.tmp")
        val targetFile = File(dir, UPDATE_APK_NAME)

        var connection: HttpURLConnection? = null
        try {
            val urlConn = URL(release.apkUrl).openConnection() as HttpURLConnection
            connection = urlConn
            urlConn.connectTimeout = HTTP_CONNECT_TIMEOUT_MS
            urlConn.readTimeout = HTTP_READ_TIMEOUT_MS
            urlConn.requestMethod = "GET"
            urlConn.setRequestProperty("User-Agent", USER_AGENT)

            when (val code = urlConn.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_FOUND -> {
                    Timber.e("Самообновление: APK не найден (HTTP 404) %s", release.apkUrl)
                    return@withContext DownloadApkResult.Failed(UpdateCheckError.NotFound)
                }
                // Как в BaseDownloader: межпротокольный/длинный редирект не
                // прослежен — повтор по той же ссылке бессилен, но кэш-файл
                // следующий заход пересоздаст, классифицируем как Network.
                in 300..399 -> {
                    Timber.e("Самообновление: HTTP %s — редирект не прослежен %s", code, release.apkUrl)
                    return@withContext DownloadApkResult.Failed(UpdateCheckError.Network)
                }
                else -> {
                    Timber.e("Самообновление: HTTP %s при скачивании APK", code)
                    return@withContext DownloadApkResult.Failed(UpdateCheckError.Network)
                }
            }

            val totalBytes = urlConn.contentLengthLong
            var lastPercent = -1
            tmpFile.outputStream().use { output ->
                urlConn.inputStream.buffered().use { input ->
                    val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
                    var received = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        if (totalBytes > 0) {
                            val percent = ((received * 100) / totalBytes).toInt().coerceIn(0, 100)
                            // Дебаунс: эмиссия только при изменении целого процента.
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            // Обрыв на уровне байтов (сервер закрыл поток раньше Content-Length).
            if (totalBytes > 0 && tmpFile.length() != totalBytes) {
                tmpFile.delete()
                return@withContext DownloadApkResult.Failed(UpdateCheckError.Network)
            }
            // Атомарная подмена по образцу BaseDownloader: tmp -> финальный файл.
            if (targetFile.exists() && !targetFile.delete()) {
                Timber.e("Не удалось удалить старый %s", targetFile.name)
                return@withContext DownloadApkResult.Failed(UpdateCheckError.Network)
            }
            if (!tmpFile.renameTo(targetFile)) {
                Timber.e("Не удалось переименовать %s в %s", tmpFile.name, targetFile.name)
                return@withContext DownloadApkResult.Failed(UpdateCheckError.Network)
            }
            if (lastPercent != 100) {
                onProgress(100)
            }
            DownloadApkResult.Success(targetFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnknownHostException) {
            Timber.w(e, "Самообновление: хост APK недоступен")
            DownloadApkResult.Failed(UpdateCheckError.Network)
        } catch (e: IOException) {
            Timber.e(e, "Самообновление: сбой скачивания APK")
            DownloadApkResult.Failed(UpdateCheckError.Network)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Сетевой этап GET релизного JSON (internal — тесты подменяют весь
     * маппинг через [evaluateReleaseJson] без сети; сама функция — тонкая
     * обёртка HttpURLConnection по образцу RemoteCatalogProvider).
     */
    internal suspend fun fetchReleaseJson(url: String): FetchJsonResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val urlConn = URL(url).openConnection() as HttpURLConnection
            connection = urlConn
            urlConn.connectTimeout = CHECK_CONNECT_TIMEOUT_MS
            urlConn.readTimeout = CHECK_READ_TIMEOUT_MS
            urlConn.requestMethod = "GET"
            urlConn.setRequestProperty("User-Agent", USER_AGENT)
            urlConn.setRequestProperty("Accept", ACCEPT_HEADER)

            when (urlConn.responseCode) {
                HttpURLConnection.HTTP_OK ->
                    FetchJsonResult.Ok(urlConn.inputStream.use { it.readBytes().decodeToString() })
                HttpURLConnection.HTTP_NOT_FOUND -> {
                    Timber.w("Самообновление: релизы не найдены (HTTP 404) — у репозитория нет релизов?")
                    FetchJsonResult.NotFound
                }
                else -> FetchJsonResult.Network("HTTP ${urlConn.responseCode}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnknownHostException) {
            Timber.w(e, "Самообновление: хост GitHub недоступен")
            FetchJsonResult.Network("Хост недоступен: ${e.message}")
        } catch (e: IOException) {
            FetchJsonResult.Network("Сбой сети: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Чистый маппинг JSON релиза в результат проверки для [currentVersion]
     * (internal — покрыто unit-тестами фикстурами JSON).
     */
    internal fun evaluateReleaseJson(rawJson: String, currentVersion: String): UpdateCheckResult {
        return when (val parsed = parser.parse(rawJson)) {
            is ReleaseParseResult.Release ->
                if (UpdateVersionLogic.isNewer(parsed.info.version, currentVersion)) {
                    UpdateCheckResult.UpdateAvailable(parsed.info)
                } else {
                    UpdateCheckResult.UpToDate
                }
            ReleaseParseResult.NotStable -> UpdateCheckResult.UpToDate
            ReleaseParseResult.NoAsset -> UpdateCheckResult.Failed(UpdateCheckError.NoAsset)
            ReleaseParseResult.ParseError -> UpdateCheckResult.Failed(UpdateCheckError.Parse)
        }
    }

    /**
     * Очистить каталог обновлений перед загрузкой: старый скачанный APK и
     * осколки `.tmp` предыдущих попыток больше не нужны.
     */
    private fun clearUpdateDir(dir: File) {
        dir.listFiles()?.forEach { file ->
            if (file.isFile && !file.delete()) {
                Timber.w("Не удалось удалить старый %s", file.name)
            }
        }
    }
}
