package com.plox.llmtranslate.domain.model

/**
 * Чистые правила именования файлов моделей для экспорта и распознавания
 * формата при импорте одиночного файла (ревью раунд 9: экспорт готовой
 * модели с путём без расширения получал имя с выдуманным «.gguf», который
 * импорт отвергал — цикл экспорт→импорт own-моделей был сломан).
 * Вынесено в объект для unit-теста round-trip без Android-специфики.
 * Расширения архивов — общий источник [ArchiveFormats] (ревью, п.13:
 * импорт data-слоя `DownloadLayout` из domain устранён).
 */
object ModelFileNaming {

    /** Расширения, которые осмысленно нести в имени файла (GGUF в приложении мёртв). */
    private val KNOWN_EXTENSIONS = setOf("onnx", "bin", "zip", "spm")

    /**
     * Имя файла экспорта одиночного файла или ZIP-архива каталога модели.
     *
     * - каталог → ZIP (расширение .zip — повторный импорт распознаёт архив
     *   по сигнатуре и метаданным model_meta.json);
     * - у [displayName] уже есть осмысленное расширение (.onnx/.bin/архив —
     *   так выглядят displayName импортированных файлов) — имя берётся как есть,
     *   без двойного расширения;
     * - иначе берется фактическое расширение [filePath] (например .onnx у
     *   скачанной ONNX-модели);
     * - расширения нет нигде — имя сохраняется без расширения (расширение
     *   «.gguf» не подставляется: формат не поддерживается ни одним рантаймом,
     *   импорт отверг бы такой файл).
     */
    fun exportFileName(filePath: String, displayName: String, isDirectory: Boolean): String {
        if (isDirectory) return "$displayName.zip"
        if (isKnownExtension(displayName)) return displayName
        val pathExt = filePath.substringAfterLast('.', missingDelimiterValue = "")
        if (pathExt.isNotEmpty()) return "$displayName.$pathExt"
        return displayName
    }

    /**
     * Угадать рантайм по имени файла импорта. null — формат не распознан:
     * импорт отклоняется вызывающим кодом (с предупреждением в лог и ошибкой
     * в UI) вместо записи с битым умолчанием MARIAN_ONNX.
     */
    fun guessRuntime(fileName: String): RuntimeType? {
        val lower = fileName.lowercase()
        return when {
            lower.contains("indictrans") -> RuntimeType.INDICTRANS_ONNX
            lower.contains("sherpa") || ArchiveFormats.isArchive(lower) ->
                // Архивы (.zip/.tar.bz2/.tar.gz) без явных признаков ONNX/Marian
                // — пачки sherpa.
                RuntimeType.SHERPA
            lower.endsWith(".onnx") -> RuntimeType.MARIAN_ONNX // явный ONNX — Marian fallback
            else -> null
        }
    }

    /** Имя заканчивается известным расширением модели или архива. */
    private fun isKnownExtension(fileName: String): Boolean {
        val lower = fileName.lowercase()
        if (ArchiveFormats.isArchive(lower)) return true
        return lower.substringAfterLast('.', "") in KNOWN_EXTENSIONS
    }
}
