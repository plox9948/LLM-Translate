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

    /**
     * Сжатый gzip-артефакт URL (slimt-пачки `*.bin.gz`/`*.spm.gz` с CDN):
     * инсталлятор gunzip-ит их при установке, в каталоге модели лежат
     * РАСПАКОВАННЫЕ имена, а SHA-256 в каталоге/метаданных относится к
     * сжатым байтам. Сверять хеш распакованного файла с хешем артефакта
     * нельзя — round-trip собственного slimt-экспорта отклонялся бы
     * формальным несовпадением (контрольный аудит, п.1).
     */
    fun isCompressedArtifact(fileName: String): Boolean =
        fileName.lowercase().endsWith(".gz")
}
