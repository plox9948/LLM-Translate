package com.plox.llmtranslate.data.download

import com.plox.llmtranslate.domain.model.RuntimeType
import timber.log.Timber
import java.io.File

/**
 * Инсталлятор GGUF-моделей (llama.cpp, whisper.cpp).
 * Один файл, скачивание с докачкой + проверка SHA-256.
 */
class GgufInstaller : ModelInstaller, BaseDownloader() {

    override fun supports(runtime: RuntimeType): Boolean =
        runtime == RuntimeType.GGUF

    override suspend fun install(
        urls: List<String>,
        sha256: String?,
        targetDir: File,
        modelId: String,
        onProgress: suspend (Int) -> Unit
    ): InstallResult {
        val url = urls.firstOrNull()
            ?: return InstallResult.Failure("Нет URL для скачивания")

        val file = downloadFile(url, targetDir, "$modelId.gguf", onProgress)
            ?: return InstallResult.Failure("Не удалось скачать файл")

        if (!verifySha256(file, sha256)) {
            file.delete()
            Timber.e("SHA-256 не совпадает для $modelId")
            return InstallResult.Failure("SHA-256 не совпадает")
        }
        return InstallResult.Success(file.absolutePath)
    }
}
