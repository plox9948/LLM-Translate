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
     * Файл не совпал с ожидаемым SHA-256 — источник повреждён.
     * @param wasResumed true — в финальный файл вошли байты докачанной части:
     *        часть могла быть испорчена после обрыва, `.part` уже отброшен,
     *        поэтому повторное скачивание с нуля поможет и повтор WorkManager
     *        имеет смысл (permanent = false);
     *        false — mismatch после свежей полной загрузки, повтор не поможет.
     */
    data class Mismatch(val message: String, val wasResumed: Boolean = false) : DownloadOutcome()
}

/**
 * Базовая логика скачивания с докачкой (Range) и проверкой SHA-256.
 * Наследуется инсталляторами, различающимися только обработкой после загрузки.
 *
 * Целостность докачки ([DownloadResumePolicy]): рядом с `<file>.part` держится
 * `<file>.part.meta` (ETag + полный размер файла по данным сервера). При
 * возобновлении Range уходит с If-Match (кроме слабого ETag `W/...` — он
 * в If-Match не отправляется), а ответ 206 дополнительно сверяется
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
                is TransferOutcome.Complete -> {
                    // Докачанные байты реально вошли в итоговый файл, только если
                    // сервер подтвердил Range (206) и файл открывался на append;
                    // при 200 часть перезаписывалась с нуля — байты не переиспользовались.
                    val reusedPartBytes = result.reusedPartBytes
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
                        // Часть отбрасываем в любом случае: повтор (если он имеет
                        // смысл) обязан качать файл заново с нуля.
                        discardPart(partFile, metaFile)
                        outcome = DownloadOutcome.Mismatch(
                            "SHA-256 не совпадает: $fileName",
                            wasResumed = reusedPartBytes
                        )
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
        /**
         * Передача завершилась в .part.
         * @param reusedPartBytes true — ответ 206 append подтверждён и байты
         *        существующей части реально вошли в файл; false — файл
         *        перезаписывался с нуля (ответ 200).
         */
        data class Complete(val reusedPartBytes: Boolean) : TransferOutcome()
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
                meta?.etag?.let { etag ->
                    // Слабый ETag (W/...) в строгом If-Match никогда не совпадёт —
                    // сервер ответил бы 412 и докачка потерялась бы зря.
                    if (DownloadResumePolicy.isWeakETag(etag)) {
                        Timber.d(
                            "Слабый ETag не отправляем в If-Match — " +
                                "целостность проверим по Content-Range, размеру и SHA-256"
                        )
                    } else {
                        urlConn.setRequestProperty("If-Match", etag)
                    }
                }
            }

            var bytesDone: Long
            var announcedTotal: Long
            // Байты существующей части реально переиспользуются только при
            // подтверждённом 206 (append); при 200 файл перезаписывается с нуля.
            var reusedPartBytes = false
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
                    reusedPartBytes = true
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
            TransferOutcome.Complete(reusedPartBytes)
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
     * Удалить файлы, созданные распаковкой/установкой до отмены или сбоя —
     * полу-распакованные файлы не должны оставаться мусором. Уже скачанные
     * файлы и `.part` в [files] не передаются (докачка сохраняется).
     * Общий для инсталляторов (ArchiveInstaller, SlimtInstaller).
     */
    protected fun discardPartial(files: List<File>) {
        for (file in files) {
            if (file.isFile && !file.delete()) {
                Timber.w("Не удалось удалить частично созданный ${file.name}")
            }
        }
    }

    /**
     * Масштабировать прогресс одного файла (0..100) в общий прогресс
     * последовательности из [fileCount] файлов: каждый файл занимает свою
     * равную долю диапозона (размеры файлов до загрузки неизвестны).
     * Общий прирост монотонен — «прыжка» 0..100 на каждый файл нет.
     */
    protected fun scaleFileProgress(index: Int, fileCount: Int, fileProgress: Int): Int {
        if (fileCount <= 1) return fileProgress.coerceIn(0, 100)
        return ((index * 100 + fileProgress) / fileCount).coerceIn(0, 100)
    }

    /**
     * Скачать несколько файлов в каталог модели (прогресс суммируется).
     * Каждый файл сверяется со своим SHA-256 из [sha256s] (null — пропуск).
     * Уже скачанные целевые файлы предыдущих попыток не перекачиваются:
     * при WorkManager-повторе (issue #40) завершённые файлы пакета
     * пересчитываются по SHA-256 (дорого, но дешевле повторной загрузки GB-файла)
     * и пропускаются; без контрольной суммы доверяемся целому целевому файлу
     * (он появляется только после полной загрузки, переименованием из `.part`)
     * при отсутствии не завершённой докачки рядом.
     * @return результат: [DownloadOutcome.Success] с путём к каталогу.
     */
    protected suspend fun downloadFiles(
        urls: List<String>,
        targetDir: File,
        modelId: String,
        sha256s: List<String?> = emptyList(),
        onProgress: suspend (Int) -> Unit
    ): DownloadOutcome {
        val modelDir = DownloadLayout.modelDir(targetDir, modelId)
        modelDir.mkdirs()

        urls.forEachIndexed { index, url ->
            val fileName = url.substringAfterLast('/').ifBlank { "file_$index" }
            val expectedSha = sha256s.getOrNull(index)
            val doneFile = File(modelDir, fileName)
            if (doneFile.isFile && canReuseDownloaded(doneFile, expectedSha)) {
                Timber.d("Файл $fileName уже скачан ранее — пропуск загрузки")
                // Мусор докачки от прерванной попытки рядом с целым целевым
                // файлом больше не нужен.
                discardPart(File(modelDir, "$fileName.part"), File(modelDir, "$fileName.part.meta"))
                onProgress(scaleFileProgress(index, urls.size, 100))
                return@forEachIndexed
            }
            val fileOutcome = downloadFile(url, modelDir, fileName, expectedSha) { fileProgress ->
                // Прогресс по всем файлам: вес каждого файла одинаков.
                onProgress(scaleFileProgress(index, urls.size, fileProgress))
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
     * Можно ли переиспользовать уже существующий целевой файл вместо загрузки.
     * С известной контрольной суммой — сверяем SHA-256 (пересчёт дешевле
     * перекачки); без неё — доверяем факту существования файла, если рядом
     * нет незавершённой `.part` докачки (она означает прерванную попытку,
     * а целевой файл мог остаться от другого содержимого источника).
     */
    private suspend fun canReuseDownloaded(doneFile: File, expectedSha: String?): Boolean {
        if (!expectedSha.isNullOrBlank()) return verifySha256(doneFile, expectedSha)
        return File(doneFile.parentFile, "${doneFile.name}.part").length() == 0L
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
