package com.plox.llmtranslate.data.download

import com.plox.llmtranslate.domain.model.RuntimeType
import java.io.File

/**
 * Инсталлятор ONNX-моделей перевода (onnx-runtime).
 * Поддерживает Marian MT и IndicTrans2: мультифайловые модели
 * (encoder + decoder + токенизаторы) скачиваются в каталог модели.
 * Каждый файл сверяется со своим SHA-256 из [sha256s].
 */
class OnnxInstaller : ModelInstaller, BaseDownloader() {

    override fun supports(runtime: RuntimeType): Boolean =
        runtime == RuntimeType.MARIAN_ONNX || runtime == RuntimeType.INDICTRANS_ONNX

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

        if (urls.size > 1) {
            // Мультифайловая модель: каждый файл проверяется по своему SHA-256.
            val outcome = downloadFiles(urls, targetDir, modelId, hashes, onProgress)
            return when (outcome) {
                is DownloadOutcome.Success -> InstallResult.Success(outcome.file.absolutePath)
                is DownloadOutcome.NetworkError ->
                    InstallResult.Failure(outcome.message)
                is DownloadOutcome.Mismatch ->
                    // Файл повреждён на сервере — повторное скачивание не поможет.
                    InstallResult.Failure(outcome.message, permanent = true)
            }
        }

        val fileName = urls[0].substringAfterLast('/').ifBlank { "$modelId.onnx" }
        val outcome = downloadFile(urls[0], targetDir, fileName, hashes[0], onProgress = onProgress)
        return when (outcome) {
            is DownloadOutcome.Success -> InstallResult.Success(outcome.file.absolutePath)
            is DownloadOutcome.NetworkError -> InstallResult.Failure(outcome.message)
            is DownloadOutcome.Mismatch ->
                // Файл повреждён на сервере — повторное скачивание не поможет.
                InstallResult.Failure(outcome.message, permanent = true)
        }
    }
}
