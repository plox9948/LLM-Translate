package com.plox.llmtranslate.data.download

import com.plox.llmtranslate.domain.model.RuntimeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Базовая логика скачивания с докачкой (Range) и проверкой SHA-256.
 * Наследуется инсталляторами, различающимися только обработкой после загрузки.
 */
abstract class BaseDownloader {

    /**
     * Скачать файл в [targetDir] с поддержкой докачки.
     * @return путь к скачанному файлу или null при ошибке.
     */
    protected suspend fun downloadFile(
        url: String,
        targetDir: File,
        fileName: String,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val partFile = File(targetDir, "$fileName.part")
        val doneFile = File(targetDir, fileName)
        targetDir.mkdirs()

        var bytesDone = partFile.length()
        var connection: HttpURLConnection? = null
        try {
            val urlConn = URL(url).openConnection() as HttpURLConnection
            connection = urlConn
            urlConn.connectTimeout = 30_000
            urlConn.readTimeout = 60_000
            if (bytesDone > 0) {
                urlConn.setRequestProperty("Range", "bytes=$bytesDone-")
            }

            when (val code = urlConn.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    // Сервер не поддерживает Range — начинаем заново.
                    bytesDone = 0
                    partFile.writeBytes(ByteArray(0))
                }
                HttpURLConnection.HTTP_PARTIAL -> {
                    Timber.d("Докачка с байта $bytesDone")
                }
                else -> {
                    Timber.e("Ошибка HTTP $code при загрузке $url")
                    return@withContext null
                }
            }

            val totalBytes = bytesDone + (urlConn.contentLengthLong.takeIf { it > 0 } ?: 0L)
            partFile.outputStream().use { out ->
                if (bytesDone > 0) {
                    // Докачка: режим append.
                    partFile.setLength(partFile.length())
                }
                urlConn.inputStream.buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        received += read
                        if (totalBytes > 0) {
                            val percent = ((bytesDone + received) * 100 / totalBytes).toInt()
                            onProgress(percent.coerceIn(0, 100))
                        }
                    }
                }
            }

            // Финальный rename.
            if (doneFile.exists()) doneFile.delete()
            if (!partFile.renameTo(doneFile)) {
                Timber.e("Не удалось переименовать ${partFile.name} в ${doneFile.name}")
                return@withContext null
            }
            doneFile
        } catch (e: Exception) {
            Timber.e(e, "Ошибка скачивания $url")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Проверить SHA-256 файла. Длительная операция — только в IO-потоке.
     */
    protected suspend fun verifySha256(file: File, expected: String): Boolean =
        withContext(Dispatchers.IO) {
            if (expected.isBlank()) return@withContext true
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            actual.equals(expected, ignoreCase = true)
        }
}
