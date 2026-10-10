package com.plox.llmtranslate.data.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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
        onProgress: suspend (Int) -> Unit
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
            urlConn.readTimeout = 120_000
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
            // При докачке открываем файл в режиме append, иначе — с нуля.
            val out: OutputStream = if (bytesDone > 0) {
                FileOutputStream(partFile, true)
            } else {
                partFile.outputStream()
            }
            out.use { os ->
                urlConn.inputStream.buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        os.write(buffer, 0, read)
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
     * Скачать несколько файлов в каталог модели (прогресс суммируется).
     * @return путь к каталогу или null при ошибке.
     */
    protected suspend fun downloadFiles(
        urls: List<String>,
        targetDir: File,
        modelId: String,
        onProgress: suspend (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val modelDir = File(targetDir, modelId)
        modelDir.mkdirs()

        urls.forEachIndexed { index, url ->
            val fileName = url.substringAfterLast('/').ifBlank { "file_$index" }
            val file = downloadFile(url, modelDir, fileName) { fileProgress ->
                // Прогресс по всем файлам: вес каждого файла одинаков.
                val total = ((index * 100 + fileProgress) / urls.size).toInt()
                onProgress(total.coerceIn(0, 100))
            }
            if (file == null) {
                return@withContext null
            }
        }
        onProgress(100)
        modelDir
    }

    /**
     * Проверить SHA-256 файла. Длительная операция — только в IO-потоке.
     */
    protected suspend fun verifySha256(file: File, expected: String?): Boolean =
        withContext(Dispatchers.IO) {
            if (expected.isNullOrBlank()) return@withContext true
            val digest = MessageDigest.getInstance("SHA-256")
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
