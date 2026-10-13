package com.plox.llmtranslate.data.download

import com.plox.llmtranslate.domain.model.RuntimeType
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
 * Инсталлятор sherpa-onnx STT-моделей.
 *
 * Для каждого URL:
 * - файл-архив (.tar.bz2/.tar.gz/.zip) — скачивается и распаковывается в
 *   targetDir/<modelId> (tar.bz2 — Apache Commons Compress + XZ; защита от zip-slip);
 * - обычный файл — скачивается как есть в targetDir/<modelId>.
 *
 * Возвращает путь к каталогу модели.
 */
class ArchiveInstaller : ModelInstaller, BaseDownloader() {

    override fun supports(runtime: RuntimeType): Boolean =
        runtime == RuntimeType.SHERPA

    override suspend fun install(
        urls: List<String>,
        sha256: String?,
        targetDir: File,
        modelId: String,
        onProgress: suspend (Int) -> Unit
    ): InstallResult {
        if (urls.isEmpty()) {
            return InstallResult.Failure("Нет URL для скачивания")
        }

        val modelDir = File(targetDir, modelId)
        modelDir.mkdirs()

        urls.forEachIndexed { index, url ->
            val fileName = url.substringAfterLast('/').ifBlank { "file_$index" }
            val isArchive = fileName.endsWith(".tar.bz2") ||
                fileName.endsWith(".tar.gz") ||
                fileName.endsWith(".zip")

            if (isArchive) {
                val archiveFile = downloadFile(url, targetDir, fileName, onProgress)
                    ?: return InstallResult.Failure("Не удалось скачать архив: $fileName")

                if (index == 0 && !verifySha256(archiveFile, sha256)) {
                    archiveFile.delete()
                    // Файл повреждён на сервере — повторное скачивание не поможет.
                    return InstallResult.Failure("SHA-256 не совпадает", permanent = true)
                }

                val extracted = extractArchive(archiveFile, modelDir, fileName)
                archiveFile.delete()
                if (!extracted) {
                    return InstallResult.Failure("Ошибка распаковки: $fileName")
                }
                // Архивы sherpa содержат единственный подкаталог — подтягиваем его наверх.
                flattenModelDir(modelDir)
            } else {
                if (downloadFile(url, modelDir, fileName, onProgress) == null) {
                    return InstallResult.Failure("Не удалось скачать: $fileName")
                }
            }
        }

        onProgress(100)
        return InstallResult.Success(modelDir.absolutePath)
    }

    private suspend fun extractArchive(archiveFile: File, destDir: File, fileName: String): Boolean =
        when {
            fileName.endsWith(".tar.bz2") -> extractTar(archiveFile, destDir, bzip2 = true)
            fileName.endsWith(".tar.gz") -> extractTar(archiveFile, destDir, bzip2 = false)
            fileName.endsWith(".zip") -> extractZip(archiveFile, destDir)
            else -> false
        }

    /**
     * Копирование записи архива в файл с проверкой отмены корутины на каждом
     * блоке — длинная распаковка прерывается сразу по cancel() воркера.
     */
    private suspend fun copyEntry(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
    }

    /**
     * Удалить файлы, созданные распаковкой до отмены — полу-распакованные
     * файлы не должны оставаться мусором. Ранее скачанные файлы и .part
     * архива не трогаются (докачка сохраняется).
     */
    private fun discardPartial(createdFiles: List<File>) {
        for (file in createdFiles) {
            if (!file.delete()) {
                Timber.w("Не удалось удалить частично распакованный ${file.name}")
            }
        }
    }

    private suspend fun extractTar(archiveFile: File, destDir: File, bzip2: Boolean): Boolean {
        val createdFiles = mutableListOf<File>()
        return try {
            archiveFile.inputStream().buffered().use { fileInput ->
                val tarInput = if (bzip2) {
                    TarArchiveInputStream(BZip2CompressorInputStream(fileInput))
                } else {
                    TarArchiveInputStream(GZIPInputStream(fileInput))
                }
                tarInput.use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        currentCoroutineContext().ensureActive()
                        val outFile = File(destDir, entry.name)
                        if (!isSecurePath(destDir, outFile)) {
                            throw SecurityException("Небезопасный путь в архиве: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            createdFiles.add(outFile)
                            outFile.outputStream().use { copyEntry(tar, it) }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
            true
        } catch (e: CancellationException) {
            // Отмена воркера: убираем полу-распакованные файлы и всплываем выше.
            discardPartial(createdFiles)
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Ошибка распаковки tar: ${archiveFile.name}")
            false
        }
    }

    private suspend fun extractZip(archiveFile: File, destDir: File): Boolean {
        val createdFiles = mutableListOf<File>()
        return try {
            ZipInputStream(archiveFile.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    currentCoroutineContext().ensureActive()
                    val outFile = File(destDir, entry.name)
                    if (!isSecurePath(destDir, outFile)) {
                        throw SecurityException("Небезопасный путь в архиве: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        createdFiles.add(outFile)
                        outFile.outputStream().use { copyEntry(zip, it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            true
        } catch (e: CancellationException) {
            // Отмена воркера: убираем полу-распакованные файлы и всплываем выше.
            discardPartial(createdFiles)
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Ошибка распаковки zip: ${archiveFile.name}")
            false
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

    /**
     * Защита от zip-slip: путь внутри архива не должен выходить за destDir.
     */
    private fun isSecurePath(destDir: File, outFile: File): Boolean {
        val destCanonical = destDir.canonicalPath
        val outCanonical = outFile.canonicalPath
        return outCanonical == destCanonical ||
            outCanonical.startsWith(destCanonical + File.separator)
    }
}
