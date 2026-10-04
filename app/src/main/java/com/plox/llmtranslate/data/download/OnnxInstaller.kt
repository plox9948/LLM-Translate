package com.plox.llmtranslate.data.download

import com.plox.llmtranslate.domain.model.RuntimeType
import java.io.File

/**
 * Инсталлятор ONNX-моделей (onnx-runtime).
 * Формат: один .onnx-файл или zip с ним.
 */
class OnnxInstaller : ModelInstaller, BaseDownloader() {

    override fun supports(runtime: RuntimeType): Boolean =
        runtime == RuntimeType.ONNX

    override suspend fun install(
        url: String,
        sha256: String?,
        targetDir: File,
        modelId: String,
        onProgress: (Int) -> Unit
    ): InstallResult {
        val fileName = if (url.endsWith(".zip", ignoreCase = true)) "$modelId.zip" else "$modelId.onnx"
        val file = downloadFile(url, targetDir, fileName, onProgress)
            ?: return InstallResult.Failure("Не удалось скачать файл")

        if (!verifySha256(file, sha256)) {
            file.delete()
            return InstallResult.Failure("SHA-256 не совпадает")
        }
        return InstallResult.Success(file.absolutePath)
    }
}
