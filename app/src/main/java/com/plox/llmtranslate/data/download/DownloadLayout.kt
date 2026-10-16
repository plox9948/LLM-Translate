package com.plox.llmtranslate.data.download

import com.plox.llmtranslate.domain.model.RuntimeType
import java.io.File

/**
 * Единая раскладка файлов загрузки инсталляторов.
 *
 * [BaseDownloader] пишет `<file>.part` и `<file>.part.meta` в тот же каталог,
 * куда скачивается целевой файл, поэтому расчёт каталога цели задан здесь
 * один раз и используется инсталляторами и чисткой осиротевших частей
 * (ModelManagerImpl.deleteOrphanParts), а не дублируется в них.
 */
object DownloadLayout {

    /** Файл-архив (.tar.bz2/.tar.gz/.zip) — единственный, который распаковывает ArchiveInstaller. */
    fun isArchive(fileName: String): Boolean =
        fileName.endsWith(".tar.bz2") ||
            fileName.endsWith(".tar.gz") ||
            fileName.endsWith(".zip")

    /** Каталог мультифайловой модели: `<каталог рантайма>/<modelId>`. */
    fun modelDir(runtimeDir: File, modelId: String): File = File(runtimeDir, modelId)

    /**
     * Каталог, в который инсталлятор скачивает файл по URL (и в котором
     * лежит его `<имя>.part`):
     * - SHERPA: архивы — корень каталога рантайма, одиночные файлы — каталог модели;
     * - ONNX-рантаймы: мультифайловые модели — каталог модели, одиночный файл — корень.
     */
    fun fileDir(
        runtimeDir: File,
        runtime: RuntimeType,
        modelId: String,
        urls: List<String>,
        fileName: String
    ): File {
        val inModelDir = if (runtime == RuntimeType.SHERPA) {
            !isArchive(fileName)
        } else {
            urls.size > 1
        }
        return if (inModelDir) modelDir(runtimeDir, modelId) else runtimeDir
    }
}
