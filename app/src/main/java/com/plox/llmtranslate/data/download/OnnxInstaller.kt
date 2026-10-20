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
            return toInstallResult(
                downloadFiles(urls, targetDir, modelId, hashes, onProgress)
            )
        }

        // Имя цели — единая схема DownloadLayout: префикс modelId в общем
        // корне каталога рантайма (защита от коллизий одинаковых basename).
        val fileName = DownloadLayout.targetFileName(modelId, urls[0], 0, inModelDir = false)
        return toInstallResult(
            downloadFile(urls[0], targetDir, fileName, hashes[0], onProgress = onProgress)
        )
    }

    /**
     * Общий перевод результата скачивания в результат установки: ошибка —
     * единый маппинг [installFailureOrNull] (тот же, что у остальных
     * инсталляторов), успех — путь файла/каталога из [DownloadOutcome.Success]
     * (null маппинг возвращает только для Success, каст безопасен).
     */
    private fun toInstallResult(outcome: DownloadOutcome): InstallResult =
        outcome.installFailureOrNull()
            ?: InstallResult.Success((outcome as DownloadOutcome.Success).file.absolutePath)
}
