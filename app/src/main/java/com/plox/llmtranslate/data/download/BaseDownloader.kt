package com.plox.llmtranslate.data.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Результат скачивания файла.
 */
sealed class DownloadOutcome {
    /** Файл скачан и прошёл проверки контрольных данных. */
    data class Success(val file: File) : DownloadOutcome()

    /** Сетевая/HTTP ошибка — повтор имеет смысл, докачка продолжит с места обрыва. */
    data class NetworkError(val message: String) : DownloadOutcome()

    /**
     * Файл не совпал с ожидаемым SHA-256 — источник повреждён,
     * повторное скачивание не поможет.
     */
    data class Mismatch(val message: String) : DownloadOutcome()
}

/**
 * Базовая логика скачивания с докачкой (Range) и проверкой SHA-256.
 * Наследуется инсталляторами, различающимися только обработкой после загрузки.
 *
 * Целостность докачки ([DownloadResumePolicy]): рядом с `<file>.part` держится
 * `<file>.part.meta` (ETag + полный размер файла по данным сервера). При
 * возобновлении Range уходит с If-Match, а ответ 206 дополнительно сверяется
 * по Content-Range; при любом несоответствии .part отбрасывается и файл
 * качается заново — старые байты к перезаписанному на сервере файлу не дописываются.
 * Итоговый размер сверяется с полным размером, объявленным сервером.
 */
abstract class BaseDownloader {

    /**
     * Скачать файл в [targetDir] с поддержкой докачки и проверкой целостности.
     * @param expectedSha256 ожидаемый SHA-256 (null/пусто — проверка пропускается).
     * @param expectedSizeBytes точный ожидаемый размер файла (<=0 — неизвестен;
     *        каталожный sizeBytes модели для архивов не подходит — это размер
     *        после распаковки, поэтому от инсталляторов обычно не передаётся).
     * @return результат скачивания.
     */
    protected suspend fun downloadFile(
        url: String,
        targetDir: File,
        fileName: String,
        expectedSha256: String? = null,
        expectedSizeBytes: Long = 0L,
        onProgress: suspend (Int) -> Unit
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        val partFile = File(targetDir, "$fileName.part")
        val metaFile = File(targetDir, "$fileName.part.meta")
        val doneFile = File(targetDir, fileName)
        targetDir.mkdirs()

        // Первая попытка — с докачкой, если у .part есть доверенные метаданные;
        // после отбраковки части — всегда одна попытка с нуля.
        var outcome: DownloadOutcome? = null
        for (attempt in 0..1) {
            val meta = readTrustedMeta(partFile, metaFile)
            val result = try {
                transfer(url, partFile, metaFile, meta, onProgress)
            } catch (e: CancellationException) {
                // Отмена воркера — не сетевая ошибка: всплываем в doWork,
                // частичный .part для докачки не удаляем.
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Ошибка скачивания $url")
                DownloadOutcome.NetworkError("Ошибка скачивания $fileName: ${e.message}")
            }
            when (result) {
                is TransferOutcome.Restart -> {
                    Timber.w("Докачка $fileName недоверена — начинаем загрузку заново")
                    discardPart(partFile, metaFile)
                    continue
                }
                is TransferOutcome.Network -> {
                    outcome = DownloadOutcome.NetworkError(result.message)
                    break
                }
                TransferOutcome.Complete -> {
                    // Итоговый размер сверяется с полным размером, объявленным
                    // сервером (и с expectedSizeBytes, если он известен).
                    val expected = maxOf(
                        expectedSizeBytes,
                        readTrustedMeta(partFile, metaFile)?.totalSizeBytes ?: 0L
                    )
                    if (!DownloadResumePolicy.sizeMatches(partFile.length(), expected)) {
                        Timber.e(
                            "Размер $fileName (${partFile.length()}) не совпал с " +
                                "ожидаемым ($expected)"
                        )
                        // Обрыв на уровне байтов — повтор продолжит докачку.
                        outcome = DownloadOutcome.NetworkError(
                            "Недокачано: $fileName ${partFile.length()}/$expected байт"
                        )
                        break
                    }
                    if (!verifySha256(partFile, expectedSha256)) {
                        discardPart(partFile, metaFile)
                        outcome = DownloadOutcome.Mismatch("SHA-256 не совпадает: $fileName")
                        break
                    }
                    metaFile.delete()
                    // Финальный rename.
                    if (doneFile.exists()) doneFile.delete()
                    outcome = if (partFile.renameTo(doneFile)) {
                        DownloadOutcome.Success(doneFile)
                    } else {
                        Timber.e("Не удалось переименовать ${partFile.name} в ${doneFile.name}")
                        DownloadOutcome.NetworkError("Не удалось завершить запись $fileName")
                    }
                    break
                }
            }
        }
        outcome ?: DownloadOutcome.NetworkError("Докачка $fileName отброшена повторно")
    }

    /** Результат одной HTTP-попытки передачи. */
    private sealed class TransferOutcome {
        object Complete : TransferOutcome()
        object Restart : TransferOutcome()
        data class Network(val message: String) : TransferOutcome()
    }

    /**
     * Выполнить одну попытку скачивания (с Range, если [meta] доверены).
     * Вызывается внутри IO-контекста.
     */
    private suspend fun transfer(
        url: String,
        partFile: File,
        metaFile: File,
        meta: DownloadResumePolicy.PartMeta?,
        onProgress: suspend (Int) -> Unit
    ): TransferOutcome {
        var connection: HttpURLConnection? = null
        return try {
            val urlConn = URL(url).openConnection() as HttpURLConnection
            connection = urlConn
            urlConn.connectTimeout = 30_000
            urlConn.readTimeout = 120_000

            val resumeBytes = if (meta != null) partFile.length() else 0L
            if (resumeBytes > 0) {
                urlConn.setRequestProperty("Range", "bytes=$resumeBytes-")
                meta?.etag?.let { urlConn.setRequestProperty("If-Match", it) }
            }

            var bytesDone: Long
            var announcedTotal: Long
            when (val code = urlConn.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    // Сервер не поддержал Range — начинаем заново.
                    bytesDone = 0
                    partFile.writeBytes(ByteArray(0))
                    announcedTotal = urlConn.contentLengthLong.takeIf { it > 0 } ?: -1L
                }
                HttpURLConnection.HTTP_PARTIAL -> {
                    val range = DownloadResumePolicy.parseContentRange(
                        urlConn.getHeaderField("Content-Range")
                    )
                    if (DownloadResumePolicy.decideResume(resumeBytes, range, meta) ==
                        DownloadResumePolicy.Decision.DISCARD_RESTART
                    ) {
                        Timber.d("Content-Range не подтвердил .part $resumeBytes — перезагрузка")
                        return TransferOutcome.Restart
                    }
                    Timber.d("Докачка с байта $resumeBytes")
                    bytesDone = resumeBytes
                    announcedTotal = if (range != null && range.total > 0) {
                        range.total
                    } else {
                        val rest = urlConn.contentLengthLong
                        if (rest > 0) resumeBytes + rest else -1L
                    }
                }
                // If-Match не совпал (412) или Range за концом изменившегося
                // файла (416) — старая часть не годится.
                412, 416 -> {
                    Timber.d("Сервер отклонил докачку $url (HTTP $code) — перезагрузка")
                    return TransferOutcome.Restart
                }
                else -> {
                    Timber.e("Ошибка HTTP $code при загрузке $url")
                    return TransferOutcome.Network("Ошибка HTTP $code при загрузке $url")
                }
            }

            // Фиксируем метаданные текущего ответа: они потребуются, если
            // передача прервётся и скачивание возобновится позже.
            writePartMeta(
                metaFile,
                DownloadResumePolicy.PartMeta(
                    etag = urlConn.getHeaderField("ETag"),
                    totalSizeBytes = announcedTotal
                )
            )

            val totalBytes = if (announcedTotal > 0) announcedTotal else 0L
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
                        // Отмена WorkManager (cancelUniqueWork) прерывает цикл
                        // на следующей итерации; частичный .part остаётся для
                        // докачки — его чистит отмена скачивания в UI.
                        currentCoroutineContext().ensureActive()
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
            TransferOutcome.Complete
        } finally {
            connection?.disconnect()
        }
    }

    /** Метаданные докачки, только если есть и .part, и читаемый .part.meta. */
    private fun readTrustedMeta(partFile: File, metaFile: File): DownloadResumePolicy.PartMeta? {
        if (partFile.length() == 0L || !metaFile.isFile) return null
        return try {
            DownloadResumePolicy.decodeMeta(metaFile.readText())
        } catch (e: Exception) {
            Timber.w("Не удалось прочитать ${metaFile.name}: ${e.message}")
            null
        }
    }

    private fun writePartMeta(metaFile: File, meta: DownloadResumePolicy.PartMeta) {
        try {
            metaFile.writeText(DownloadResumePolicy.encodeMeta(meta))
        } catch (e: Exception) {
            Timber.w("Не удалось записать ${metaFile.name}: ${e.message}")
        }
    }

    /** Удалить отбракованную часть файла вместе с метаданными докачки. */
    private fun discardPart(partFile: File, metaFile: File) {
        if (partFile.exists() && !partFile.delete()) {
            Timber.w("Не удалось удалить ${partFile.name}")
        }
        if (metaFile.exists() && !metaFile.delete()) {
            Timber.w("Не удалось удалить ${metaFile.name}")
        }
    }

    /**
     * Скачать несколько файлов в каталог модели (прогресс суммируется).
     * Каждый файл сверяется со своим SHA-256 из [sha256s] (null — пропуск).
     * @return результат: [DownloadOutcome.Success] с путём к каталогу.
     */
    protected suspend fun downloadFiles(
        urls: List<String>,
        targetDir: File,
        modelId: String,
        sha256s: List<String?> = emptyList(),
        onProgress: suspend (Int) -> Unit
    ): DownloadOutcome {
        val modelDir = File(targetDir, modelId)
        modelDir.mkdirs()

        urls.forEachIndexed { index, url ->
            val fileName = url.substringAfterLast('/').ifBlank { "file_$index" }
            val fileOutcome = downloadFile(url, modelDir, fileName, sha256s.getOrNull(index)) { fileProgress ->
                // Прогресс по всем файлам: вес каждого файла одинаков.
                val total = ((index * 100 + fileProgress) / urls.size).toInt()
                onProgress(total.coerceIn(0, 100))
            }
            when (fileOutcome) {
                is DownloadOutcome.Success -> Unit
                is DownloadOutcome.NetworkError -> return fileOutcome
                is DownloadOutcome.Mismatch -> return fileOutcome
            }
        }
        onProgress(100)
        return DownloadOutcome.Success(modelDir)
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
                    // Хеширование больших файлов занимает десятки секунд —
                    // отмена воркера должна прерывать и этот цикл.
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            actual.equals(expected, ignoreCase = true)
        }
}
