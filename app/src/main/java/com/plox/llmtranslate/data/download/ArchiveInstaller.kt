package com.plox.llmtranslate.data.download

import com.plox.llmtranslate.domain.model.RuntimeType
import com.plox.llmtranslate.util.isFileInsideDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Инсталлятор sherpa-onnx STT-моделей и Piper TTS-моделей (issue #50).
 *
 * Для каждого URL:
 * - файл-архив (.tar.bz2/.tar.gz/.zip) — скачивается в корень [targetDir] и
 *   распаковывается в targetDir/<modelId> (tar.bz2 — Apache Commons Compress + XZ;
 *   защита от zip-slip); скачанный архив удаляется сразу после распаковки
 *   (в том числе при отмене), чтобы не оставлять сирот в корне каталога;
 * - обычный файл — скачивается как есть в targetDir/<modelId>.
 * Каждый файл сверяется со своим SHA-256 из [sha256s] (архив — хеш архива
 * до распаковки).
 *
 * Архивы Piper содержат единственный подкаталог с tokens.txt — он
 * подтягивается наверх тем же [flattenModelDir], что и архивы sherpa STT.
 *
 * Возвращает путь к каталогу модели.
 */
class ArchiveInstaller : ModelInstaller, BaseDownloader() {

    override fun supports(runtime: RuntimeType): Boolean =
        runtime == RuntimeType.SHERPA || runtime == RuntimeType.PIPER_ONNX

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
        modelDir.mkdirs()

        urls.forEachIndexed { index, url ->
            // Имя и каталог цели — единая схема DownloadLayout: архив
            // скачивается в общий корень каталога с префиксом modelId,
            // одиночный файл — в персональный каталог модели без префикса.
            val isArchive = DownloadLayout.isArchive(DownloadLayout.sanitizedBasename(url, index))
            val fileName = DownloadLayout.targetFileName(modelId, url, index, !isArchive)
            // Прогресс одного файла масштабируется в общий прогресс пачки.
            val onFileProgress: suspend (Int) -> Unit = { p ->
                onProgress(scaleFileProgress(index, urls.size, p))
            }
            val failure = if (isArchive) {
                installArchiveEntry(url, targetDir, modelDir, fileName, hashes[index], onFileProgress)
            } else {
                installPlainEntry(url, modelDir, fileName, hashes[index], onFileProgress)
            }
            if (failure != null) return failure
        }

        onProgress(100)
        return InstallResult.Success(modelDir.absolutePath)
    }

    /**
     * Скачать один URL-архив (в корень [targetDir]) и распаковать в [modelDir].
     * @return null при успехе, терминальный [InstallResult.Failure] при отказе.
     */
    private suspend fun installArchiveEntry(
        url: String,
        targetDir: File,
        modelDir: File,
        fileName: String,
        expectedSha: String?,
        onFileProgress: suspend (Int) -> Unit
    ): InstallResult? = when (
        val outcome = downloadFile(url, targetDir, fileName, expectedSha, onProgress = onFileProgress)
    ) {
        is DownloadOutcome.Success -> run {
            val archiveFile = outcome.file
            val extracted = try {
                extractArchive(archiveFile, modelDir, fileName)
            } catch (e: CancellationException) {
                // Отмена распаковки: скачанный архив лежит в КОРНЕ
                // каталога рантайма — без удаления он остался бы
                // сиротой навсегда; освобождаем место сразу
                // (deleteOrphanParts — страховка для старых установок).
                if (!archiveFile.delete()) {
                    Timber.w("Не удалось удалить архив после отмены: ${archiveFile.name}")
                }
                throw e
            } catch (e: SecurityException) {
                // zip-slip: содержимое архива того же источника не
                // изменится — повтор бессмысленен, permanent=true
                // (маппинг toInstallFailure этот случай не покрывает).
                Timber.e(e, "Небезопасный путь в архиве: $fileName")
                if (!archiveFile.delete()) {
                    Timber.w("Не удалось удалить архив после zip-slip: ${archiveFile.name}")
                }
                return@run InstallResult.Failure(
                    "Небезопасный путь в архиве: $fileName",
                    permanent = true
                )
            } catch (e: Exception) {
                if (isOutOfSpaceError(e)) {
                    // ENOSPC при распаковке (rethrow из extractTar/extractZip):
                    // повтор без освобождения места бессилен — permanent
                    // (контрольный аудит, п.5: без 5 перекачек),
                    // осколки распаковки extract* уже убраны.
                    Timber.e(e, "Место на диске закончилось при распаковке: $fileName")
                    if (!archiveFile.delete()) {
                        Timber.w("Не удалось удалить архив после ENOSPC: ${archiveFile.name}")
                    }
                    return@run InstallResult.Failure(
                        "Недостаточно места на диске при распаковке: $fileName",
                        permanent = true
                    )
                }
                // Прочие сбои распаковки — обычно повторные.
                Timber.e(e, "Сбой распаковки: $fileName")
                return@run InstallResult.Failure("Ошибка распаковки: $fileName")
            }
            archiveFile.delete()
            if (!extracted) {
                // Содержимое архива уже прошло SHA-256 — сбой формата
                // детерминирован: повтор лишь 5 раз перекачает гигабайтный
                // архив к тому же результату (ревью, п.4). Без контрольной
                // суммы повреждение могло возникать при передаче — повтор
                // имеет смысл.
                return@run InstallResult.Failure(
                    "Ошибка распаковки: $fileName",
                    permanent = !expectedSha.isNullOrBlank()
                )
            }
            // Архивы sherpa содержат единственный подкаталог — подтягиваем его наверх.
            flattenModelDir(modelDir)
            null
        }
        is DownloadOutcome.NetworkError ->
            InstallResult.Failure("Не удалось скачать архив: $fileName")
        is DownloadOutcome.Mismatch -> outcome.toInstallFailure()
        is DownloadOutcome.InsufficientStorage -> outcome.toInstallFailure()
        is DownloadOutcome.FileError -> outcome.toInstallFailure()
    }

    /**
     * Скачать одиночный (не архив) файл в персональный каталог модели.
     * @return null при успехе, терминальный [InstallResult.Failure] при отказе.
     */
    private suspend fun installPlainEntry(
        url: String,
        modelDir: File,
        fileName: String,
        expectedSha: String?,
        onFileProgress: suspend (Int) -> Unit
    ): InstallResult? = when (
        val outcome = downloadFile(url, modelDir, fileName, expectedSha, onProgress = onFileProgress)
    ) {
        is DownloadOutcome.Success -> null
        is DownloadOutcome.NetworkError ->
            InstallResult.Failure("Не удалось скачать: $fileName")
        is DownloadOutcome.Mismatch -> outcome.toInstallFailure()
        is DownloadOutcome.InsufficientStorage -> outcome.toInstallFailure()
        is DownloadOutcome.FileError -> outcome.toInstallFailure()
    }

    private suspend fun extractArchive(archiveFile: File, destDir: File, fileName: String): Boolean {
        // Суффикс сравнивается без учёта регистра: классификация
        // DownloadLayout.isArchive/ArchiveFormats регистронезависима, и архив
        // «X.TAR.GZ» иначе доходил до dispatch и проваливался в else (ревью, п.3).
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".tar.bz2") -> extractTar(archiveFile, destDir, bzip2 = true)
            lower.endsWith(".tar.gz") -> extractTar(archiveFile, destDir, bzip2 = false)
            lower.endsWith(".zip") -> extractZip(archiveFile, destDir)
            else -> false
        }
    }

    /**
     * Копирование записи архива в файл с проверкой отмены корутины на каждом
     * блоке — длинная распаковка прерывается сразу по cancel() воркера.
     */
    private suspend fun copyEntry(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
    }

    // discardPartial (очистка полу-распакованных файлов) — общий в BaseDownloader.

    /**
     * Общий каркас распаковки tar и zip: [walk] обходит записи (открытый поток
     * архива — внутри), осколки складываются в создаваемый [walk] список и
     * обрабатываются единообразно: отмена и zip-slip чистят осколки и
     * всплывают выше, ENOSPC всплывает после чистки (install() переведёт в
     * permanent-отказ), прочий сбой — чистка и false.
     */
    private suspend fun extractWithCleanup(
        archiveFile: File,
        label: String,
        walk: suspend (createdFiles: MutableList<File>) -> Unit
    ): Boolean {
        val createdFiles = mutableListOf<File>()
        return try {
            walk(createdFiles)
            true
        } catch (e: CancellationException) {
            // Отмена воркера: убираем полу-распакованные файлы и всплываем выше.
            discardPartial(createdFiles)
            throw e
        } catch (e: SecurityException) {
            // zip-slip тоже чистит осколки и всплывает — install() переведёт
            // его в permanent-отказ.
            discardPartial(createdFiles)
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Ошибка распаковки $label: ${archiveFile.name}")
            // Обычный сбой распаковки без очистки оставил бы полу-распакованные
            // файлы, которые смешиваются со следующим распакованным деревом.
            discardPartial(createdFiles)
            // ENOSPC — различимый фатальный сбой: всплываем выше, install()
            // переведёт в permanent-отказ (контрольный аудит, п.5).
            if (isOutOfSpaceError(e)) throw e
            false
        }
    }

    /**
     * Записать одну запись архива в [destDir]: проверка отмены, защита от
     * zip-slip, каталоги — mkdirs, файлы — копирование с учётом в
     * [createdFiles] (для очистки при отмене). Общий для tar и zip.
     */
    private suspend fun writeArchiveEntry(
        input: InputStream,
        destDir: File,
        entryName: String,
        isDirectory: Boolean,
        createdFiles: MutableList<File>,
    ) {
        currentCoroutineContext().ensureActive()
        val outFile = File(destDir, entryName)
        if (!isFileInsideDir(destDir, outFile)) {
            throw SecurityException("Небезопасный путь в архиве: $entryName")
        }
        if (isDirectory) {
            outFile.mkdirs()
        } else {
            outFile.parentFile?.mkdirs()
            createdFiles.add(outFile)
            outFile.outputStream().use { copyEntry(input, it) }
        }
    }

    /**
     * Распаковать tar(.gz/.bz2). internal — покрыто unit-тестом без сети.
     */
    internal suspend fun extractTar(archiveFile: File, destDir: File, bzip2: Boolean): Boolean =
        extractWithCleanup(archiveFile, "tar") { createdFiles ->
            archiveFile.inputStream().buffered().use { fileInput ->
                val tarInput = if (bzip2) {
                    TarArchiveInputStream(BZip2CompressorInputStream(fileInput))
                } else {
                    TarArchiveInputStream(GZIPInputStream(fileInput))
                }
                tarInput.use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        // Issue #83 (CVE-2022-41405): symlink/hardlink-записи tar
                        // записались бы как обычный файл с путём цели в содержимом,
                        // а последующие записи канонализуются ЧЕРЕЗ этот файл-ссылку
                        // и пишутся мимо destDir. Ссылочные записи архивов моделей
                        // не используются - пропускаем с предупреждением
                        // (ZIP-распаковку не затрагиваем).
                        if (entry.isSymbolicLink || entry.isLink) {
                            Timber.w(
                                "Пропущена ссылочная запись tar-архива: " +
                                    "${entry.name} -> ${entry.linkName}"
                            )
                            entry = tar.nextEntry
                            continue
                        }
                        writeArchiveEntry(tar, destDir, entry.name, entry.isDirectory, createdFiles)
                        entry = tar.nextEntry
                    }
                }
            }
        }

    /**
     * Распаковать zip. internal — покрыто unit-тестом без сети.
     */
    internal suspend fun extractZip(archiveFile: File, destDir: File): Boolean =
        extractWithCleanup(archiveFile, "zip") { createdFiles ->
            ZipInputStream(archiveFile.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    writeArchiveEntry(zip, destDir, entry.name, entry.isDirectory, createdFiles)
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

    /**
     * Если файлы модели (tokens.txt) оказались в подкаталоге — переносим их наверх.
     */
    private fun flattenModelDir(destDir: File) {
        if (File(destDir, "tokens.txt").exists()) return
        val subdirs = destDir.listFiles()?.filter { it.isDirectory } ?: return
        for (subdir in subdirs) {
            if (!File(subdir, "tokens.txt").exists()) continue
            subdir.listFiles()?.forEach { entry ->
                val target = File(destDir, entry.name)
                if (!entry.renameTo(target)) {
                    entry.copyRecursively(target, overwrite = true)
                }
            }
            subdir.deleteRecursively()
            return
        }
    }
}
