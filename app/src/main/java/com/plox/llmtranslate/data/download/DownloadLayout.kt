package com.plox.llmtranslate.data.download

import com.plox.llmtranslate.domain.model.RuntimeType
import timber.log.Timber
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
     * - PIPER_ONNX: та же раскладка, что SHERPA (одиночный tar.bz2 — корень, распаковка в каталог модели);
     * - MARIAN_SLIMT: все файлы (пучок .bin/.spm/.lex) — всегда каталог модели;
     * - ONNX-рантаймы: мультифайловые модели — каталог модели, одиночный файл — корень.
     */
    fun fileDir(
        runtimeDir: File,
        runtime: RuntimeType,
        modelId: String,
        urls: List<String>,
        fileName: String
    ): File {
        val inModelDir = when (runtime) {
            RuntimeType.SHERPA, RuntimeType.PIPER_ONNX -> !isArchive(fileName)
            RuntimeType.MARIAN_SLIMT -> true
            else -> urls.size > 1
        }
        return if (inModelDir) modelDir(runtimeDir, modelId) else runtimeDir
    }

    /**
     * Удалить остатки незавершённой установки модели по раскладке этого же
     * [fileDir] (общая логика для ModelManagerImpl.deleteOrphanParts и очистки
     * отменённой загрузки в ModelDownloadWorker): части докачки `.part` и
     * метаданные `.part.meta` с целыми целевыми файлами в корне каталога
     * рантайма, а для моделей с каталогом — каталог модели целиком
     * (переименованные целевые файлы мультифайловой установки и распакованное
     * содержимое архива). Каталог модели принадлежит только этой записи
     * (ключ — id), поэтому сносится без разбора отдельных файлов.
     *
     * Вызывается только когда живой загрузки по этому id нет (после
     * cancelUniqueWork) или запись удалена/не READY — иначе удаляются
     * файлы завершённой установки READY-модели.
     */
    fun deleteOrphanedFiles(
        runtimeDir: File,
        runtime: RuntimeType,
        modelId: String,
        urls: List<String>
    ) {
        if (urls.isEmpty()) return
        val modelDir = modelDir(runtimeDir, modelId)

        var usesModelDir = false
        urls.forEachIndexed { index, url ->
            val fileName = url.substringAfterLast('/').ifBlank { "file_$index" }
            val dir = fileDir(runtimeDir, runtime, modelId, urls, fileName)
            if (dir == modelDir) {
                usesModelDir = true
                return@forEachIndexed
            }
            // Корень каталога рантайма: части докачки, метаданные докачки и
            // осиротевший целый целевой файл — одиночный ONNX или архив до
            // распаковки (для не READY-модели это остаток её же загрузки).
            listOf(File(dir, "$fileName.part"), File(dir, "$fileName.part.meta")).forEach { part ->
                if (part.isFile && !part.delete()) {
                    Timber.w("Не удалось удалить осиротевший ${part.name}")
                }
            }
            val doneFile = File(dir, fileName)
            if (doneFile.isFile && !doneFile.delete()) {
                Timber.w("Не удалось удалить осиротевший ${doneFile.name}")
            }
            // Архив лежит в корне каталога рантайма, но ArchiveInstaller
            // распаковывает его (и создаёт каталог модели ещё до распаковки)
            // в modelDir: с purely-archive раскладкой (все URL — архивы)
            // каталог модели тоже принадлежит этой записи (ключ — id),
            // поэтому помечаем его для полного сноса наравне с
            // мультифайловой установкой.
            if (isArchive(fileName)) {
                usesModelDir = true
            }
        }
        // Каталог осиротевшей модели: переименованные целевые файлы
        // мультифайловой установки, её .part/.part.meta и распакованное
        // содержимое архива.
        if (usesModelDir && modelDir.isDirectory && !modelDir.deleteRecursively()) {
            Timber.w("Не удалось удалить каталог осиротевшей модели ${modelDir.name}")
        }
    }
}
