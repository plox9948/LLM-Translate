package com.plox.llmtranslate.data.download

import com.plox.llmtranslate.domain.model.RuntimeType
import java.io.File
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.IOException
import timber.log.Timber

/**
 * Инсталлятор языковых паков Firefox Translations (рантайм MARIAN_SLIMT).
 * Пачка состоит из трёх файлов: `model.*.intgemm.alphas.bin`,
 * `lex.*.s2t.bin`, `vocab.*.spm`. Официальный CDN Mozilla отдаёт их
 * сжатыми gzip (`.gz`), поэтому каждый файл скачивается с проверкой
 * SHA-256 по байтам архива, затем распаковывается в каталог модели.
 * Решение о gunzip — по суффиксу `.gz` (без учёта регистра) либо по
 * магической сигнатуре gzip 0x1F 0x8B в начале файла (ревью, п.3, п.5).
 * Каждый URL сверяется со своим SHA-256 из [sha256s].
 */
class SlimtInstaller : ModelInstaller, BaseDownloader() {

    override fun supports(runtime: RuntimeType): Boolean =
        runtime == RuntimeType.MARIAN_SLIMT

    override suspend fun install(
        urls: List<String>,
        sha256: String?,
        sha256s: List<String?>,
        targetDir: File,
        modelId: String,
        onProgress: suspend (Int) -> Unit
    ): InstallResult {
        if (urls.isEmpty()) {
            return InstallResult.Failure("Нет URL для скачивания")
        }
        val hashes = perUrlSha256s(urls.size, sha256s, sha256)
        val modelDir = DownloadLayout.modelDir(targetDir, modelId)
        val created = mutableListOf<File>()

        for ((index, url) in urls.withIndex()) {
            // Имя цели — единая схема DownloadLayout (пучок лежит в
            // персональном каталоге модели — префикс modelId не нужен).
            val archiveName = DownloadLayout.targetFileName(modelId, url, index, inModelDir = true)
            val outcome = downloadFile(
                url = url,
                targetDir = modelDir,
                fileName = archiveName,
                expectedSha256 = hashes[index],
                onProgress = { p -> onProgress(scaleFileProgress(index, urls.size, p)) }
            )
            // Любой исход кроме Success — терминальная ошибка установки
            // (общий маппинг installFailureOrNull).
            outcome.installFailureOrNull()?.let { return it }
            val downloaded = File(modelDir, archiveName)
            // Суффикс — без учёта регистра (классификация ArchiveFormats.
            // isCompressedArtifact тоже регистронезависима, ревью, п.3);
            // решение о gunzip дополнительно по магическим байтам 0x1F 0x8B:
            // суффикс .gz мог отвалиться при редиректе URL (ревью, п.5).
            val gzipSuffixBytes = if (archiveName.endsWith(".gz", ignoreCase = true)) 3 else 0
            val needsGunzip = gzipSuffixBytes > 0 || hasGzipMagic(downloaded)
            try {
                if (needsGunzip) {
                    val unpacked = File(modelDir, archiveName.dropLast(gzipSuffixBytes))
                    // Gzip без суффикса .gz: имя распакованного совпадает с
                    // источником — распаковываем во временный файл и заменяем
                    // источник после успеха (иначе FileOutputStream затрёт
                    // читаемый источник).
                    val out = if (unpacked == downloaded) {
                        File(modelDir, "$archiveName.gunzip.tmp")
                    } else {
                        unpacked
                    }
                    // Регистрация в множестве отбраковки ДО распаковки:
                    // при отмене/сбое полу-распакованный файл не должен
                    // оставаться в каталоге.
                    created += out
                    gunzip(unpack = downloaded, to = out)
                    currentCoroutineContext().ensureActive()
                    // Результат удаления не игнорируем: осиротевший .gz останется
                    // лежать рядом с распакованным файлом в каталоге модели
                    // (съедает место, попадает в обход bytesDone и в экспорт
                    // бэкапа) — минимум, который можно сделать здесь, — лог.
                    if (!downloaded.delete()) {
                        Timber.w("Не удалось удалить осиротевший gzip-источник: ${downloaded.name}")
                    }
                    if (out != unpacked) {
                        // Бросок имени на место: осколков после rename не остаётся.
                        if (!out.renameTo(unpacked)) {
                            throw IOException("Не удалось переименовать ${out.name} в ${unpacked.name}")
                        }
                        // Временного имени после rename нет, а итоговый файл
                        // обязан остаться в множестве отбраковки — при более
                        // позднем сбое цикла осколок распаковки чистится (ревью).
                        created -= out
                        created += unpacked
                    }
                } else {
                    created += downloaded
                }
            } catch (e: CancellationException) {
                discardPartial(created + downloaded)
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Не удалось распаковать $archiveName")
                discardPartial(created + downloaded)
                // ENOSPC при gunzip-распаковке: повтор без освобождения места
                // бессилен — permanent-отказ (контрольный аудит, п.5),
                // без циклов перекачки.
                if (isOutOfSpaceError(e)) {
                    return InstallResult.Failure(
                        "Недостаточно места на диске при распаковке: $archiveName",
                        permanent = true
                    )
                }
                // Байты файла уже совпали с SHA-256 — неgzip-содержимое
                // детерминировано, повтор его перекачает к тому же сбою
                // (ревью, п.4). Без контрольной суммы искажение могло
                // прийти при передаче — повтор имеет смысл.
                return InstallResult.Failure(
                    "Не удалось распаковать $archiveName",
                    permanent = !hashes[index].isNullOrBlank()
                )
            }
        }
        return InstallResult.Success(modelDir.absolutePath)
    }

    /**
     * Признак gzip-потока по сигнатуре формата: первые два байта 0x1F 0x8B
     * (RFC 1952). Решения о распаковке по одному суффиксу имени файла
     * недостаточно — ревью, п.5.
     */
    private fun hasGzipMagic(file: File): Boolean = try {
        file.inputStream().use { input ->
            val b0 = input.read()
            val b1 = input.read()
            b0 == 0x1F && b1 == 0x8B
        }
    } catch (e: IOException) {
        Timber.w("Не удалось прочитать заголовок ${file.name}: ${e.message}")
        false
    }

    /** Распаковка одного gzip-файла с границами отмены на каждом блоке. */
    private suspend fun gunzip(unpack: File, to: File) = withContext(
        kotlinx.coroutines.Dispatchers.IO
    ) {
        GZIPInputStream(unpack.inputStream().buffered()).use { gz ->
            FileOutputStream(to).use { out ->
                BufferedOutputStream(out).use buffered@{ bo ->
                    val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = gz.read(buffer)
                        // EOF для InputStream.read(buffer) — строго < 0;
                        // 0 для ненулевого буфера не возвращается (ревью, п.8).
                        if (read < 0) break
                        bo.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    // discardPartial (очистка полу-распакованных файлов) — общий в BaseDownloader.
}
