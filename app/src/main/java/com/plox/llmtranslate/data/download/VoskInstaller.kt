package com.plox.llmtranslate.data.download

import com.plox.llmtranslate.domain.model.RuntimeType
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Инсталлятор Vosk-моделей: zip-архив с папкой модели, распаковывается на месте.
 */
class VoskInstaller : ModelInstaller, BaseDownloader() {

    override fun supports(runtime: RuntimeType): Boolean =
        runtime == RuntimeType.VOSK

    override suspend fun install(
        urls: List<String>,
        sha256: String?,
        targetDir: File,
        modelId: String,
        onProgress: (Int) -> Unit
    ): InstallResult {
        val url = urls.firstOrNull()
            ?: return InstallResult.Failure("Нет URL для скачивания")

        val zipFile = downloadFile(url, targetDir, "$modelId.zip", onProgress)
            ?: return InstallResult.Failure("Не удалось скачать архив")

        if (!verifySha256(zipFile, sha256)) {
            zipFile.delete()
            return InstallResult.Failure("SHA-256 не совпадает")
        }

        // Распаковка в каталог модели.
        val modelDir = File(targetDir, modelId)
        modelDir.mkdirs()
        try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val outFile = File(modelDir, entry.name)
                    if (!outFile.absolutePath.startsWith(modelDir.absolutePath)) {
                        throw SecurityException("Небезопасный путь в архиве: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { zip.transferTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            zipFile.delete()
            return InstallResult.Failure("Ошибка распаковки: ${e.message}")
        }

        zipFile.delete()
        return InstallResult.Success(modelDir.absolutePath)
    }
}
