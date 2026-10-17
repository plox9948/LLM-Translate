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
import timber.log.Timber

/**
 * Инсталлятор языковых паков Firefox Translations (рантайм MARIAN_SLIMT).
 * Пачка состоит из трёх файлов: `model.*.intgemm.alphas.bin`,
 * `lex.*.s2t.bin`, `vocab.*.spm`. Официальный CDN Mozilla отдаёт их
 * сжатыми gzip (`.gz`), поэтому каждый файл скачивается с проверкой
 * SHA-256 по байтам архива, затем распаковывается в каталог модели.
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
            val archiveName = url.substringAfterLast('/').ifBlank { "file_$index" }
            val outcome = downloadFile(
                url = url,
                targetDir = modelDir,
                fileName = archiveName,
                expectedSha256 = hashes[index],
                onProgress = { p -> onProgress(scaleFileProgress(index, urls.size, p)) }
            )
            when (outcome) {
                is DownloadOutcome.Success -> Unit
                is DownloadOutcome.NetworkError ->
                    return InstallResult.Failure(outcome.message)

                is DownloadOutcome.Mismatch ->
                    return InstallResult.Failure(
                        outcome.message,
                        permanent = DownloadResumePolicy.mismatchIsPermanent(outcome.wasResumed)
                    )
            }
            val downloaded = File(modelDir, archiveName)
            try {
                if (archiveName.endsWith(".gz")) {
                    val unpacked = File(modelDir, archiveName.removeSuffix(".gz"))
                    // Регистрация в множестве отбраковки ДО распаковки:
                    // при отмене/сбое полу-распакованный файл не должен
                    // оставаться в каталоге.
                    created += unpacked
                    gunzip(unpack = downloaded, to = unpacked)
                    currentCoroutineContext().ensureActive()
                    downloaded.delete()
                } else {
                    created += downloaded
                }
            } catch (e: CancellationException) {
                discardPartial(created + downloaded)
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Не удалось распаковать $archiveName")
                discardPartial(created + downloaded)
                return InstallResult.Failure("Не удалось распаковать $archiveName")
            }
        }
        return InstallResult.Success(modelDir.absolutePath)
    }

    /** Распаковка одного gzip-файла с границами отмены на каждом блоке. */
    private suspend fun gunzip(unpack: File, to: File) = withContext(
        kotlinx.coroutines.Dispatchers.IO
    ) {
        GZIPInputStream(unpack.inputStream().buffered()).use { gz ->
            FileOutputStream(to).use { out ->
                BufferedOutputStream(out).use buffered@{ bo ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = gz.read(buffer)
                        if (read <= 0) break
                        bo.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private fun discardPartial(files: List<File>) {
        files.forEach { file ->
            if (file.isFile) {
                file.delete()
            }
        }
    }
}
