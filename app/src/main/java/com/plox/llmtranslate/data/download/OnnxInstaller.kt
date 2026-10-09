package com.plox.llmtranslate.data.download

import com.plox.llmtranslate.domain.model.RuntimeType
import timber.log.Timber
import java.io.File

/**
 * Инсталлятор ONNX-моделей перевода (onnx-runtime).
 * Поддерживает Marian MT и IndicTrans2: мультифайловые модели
 * (encoder + decoder + токенизаторы) скачиваются в каталог модели.
 */
class OnnxInstaller : ModelInstaller, BaseDownloader() {

    override fun supports(runtime: RuntimeType): Boolean =
        runtime == RuntimeType.MARIAN_ONNX || runtime == RuntimeType.INDICTRANS_ONNX

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

        if (urls.size > 1) {
            // Мультифайловая модель: SHA-256 не проверяется.
            Timber.w("Мультифайловая ONNX-модель $modelId: проверка SHA-256 пропущена")
            val dir = downloadFiles(urls, targetDir, modelId, onProgress)
                ?: return InstallResult.Failure("Не удалось скачать файлы")
            return InstallResult.Success(dir.absolutePath)
        }

        val fileName = urls[0].substringAfterLast('/').ifBlank { "$modelId.onnx" }
        val file = downloadFile(urls[0], targetDir, fileName, onProgress)
            ?: return InstallResult.Failure("Не удалось скачать файл")

        if (!verifySha256(file, sha256)) {
            file.delete()
            // Файл повреждён на сервере — повторное скачивание не поможет.
            return InstallResult.Failure("SHA-256 не совпадает", permanent = true)
        }
        return InstallResult.Success(file.absolutePath)
    }
}
