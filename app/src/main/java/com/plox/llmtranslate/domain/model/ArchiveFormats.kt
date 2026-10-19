package com.plox.llmtranslate.domain.model

/**
 * Канонический список расширений архивов, которые распаковывает приложение
 * (tar.bz2/tar.gz/zip), и проверка имени файла (ревью, п.13: прежде список
 * был зашит в data-слой `DownloadLayout`, и domain-модуль `ModelFileNaming`
 * был вынужден импортировать data — зависимость domain→data устранена).
 * Оба слоя (data.download.DownloadLayout и domain.model.ModelFileNaming)
 * используют этот единственный источник.
 */
object ArchiveFormats {

    /** Нижнерегистровые суффиксы распознаваемых архивов. */
    val ARCHIVE_SUFFIXES: List<String> = listOf(".tar.bz2", ".tar.gz", ".zip")

    /** Файл-архив — единственный формат, который распаковывает ArchiveInstaller. */
    fun isArchive(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return ARCHIVE_SUFFIXES.any { lower.endsWith(it) }
    }
}
