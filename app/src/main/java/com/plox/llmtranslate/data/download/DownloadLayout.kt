package com.plox.llmtranslate.data.download

import com.plox.llmtranslate.domain.model.ArchiveFormats
import com.plox.llmtranslate.domain.model.RuntimeType
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

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
    fun isArchive(fileName: String): Boolean = ArchiveFormats.isArchive(fileName)

    /** Сжатый gzip-артефакт (.gz — slimt-пачки с CDN), gunzip-ится при установке. */
    fun isCompressedArtifact(fileName: String): Boolean =
        ArchiveFormats.isCompressedArtifact(fileName)

    /**
     * Санитизированное имя целевого файла по URL (ревью, п.15): basename без
     * query/fragment; пустое, «.», «..» или имя после очистки — детерминированное
     * имя из SHA-256 URL, чтобы небезопасное/пустое имя не попадало на диск.
     */
    fun sanitizedBasename(url: String, index: Int): String {
        val raw = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        val cleaned = raw.filter { it.isLetterOrDigit() || it in "._-" }
        return if (cleaned.isEmpty() || cleaned.all { it == '.' }) {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(url.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(16)
            "file_${index}_$hash"
        } else {
            cleaned
        }
    }

    /** Имя модели в составе имени файла корня: всё вне [A-Za-z0-9_.-] → '_'. */
    private fun sanitizeId(modelId: String): String =
        modelId.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("")

    /**
     * Имя целевого файла модели по URL. Для файлов, скачиваемых в общий корень
     * каталога рантайма (одиночный ONNX/архив), добавляется префикс modelId —
     * защита от коллизий одинаковых basename разных моделей в одном каталоге
     * (ревью, п.15); в персональном каталоге модели префикс не нужен.
     * Пути существующих установок в БД не переименовываются — новая схема
     * действует только для новых загрузок.
     */
    fun targetFileName(modelId: String, url: String, index: Int, inModelDir: Boolean): String {
        val base = sanitizedBasename(url, index)
        return if (inModelDir) base else "${sanitizeId(modelId)}__$base"
    }

    /**
     * Кандидаты имени УСТАНОВЛЕННОГО файла модели для сверки SHA-256
     * (импорт ZIP в ModelManagerImpl и восстановление бэкапа): имя новой
     * схемы с префиксом id, санитизированный basename без префикса и
     * legacy-сырое имя URL вместе с query (старые загрузки писали
     * «?raw=true» прямо в имя файла). Единая точка, чтобы сверки
     * находили файлы всех схем одинаково.
     */
    fun installedFileCandidates(modelId: String, url: String, index: Int): List<String> {
        val sanitized = sanitizedBasename(url, index)
        return buildList {
            add(targetFileName(modelId, url, index, inModelDir = false))
            if (sanitized !in this) add(sanitized)
            val raw = url.substringAfterLast('/')
            if (raw.isNotBlank() && raw !in this) add(raw)
        }
    }

    /** Каталог мультифайловой модели: `<каталог рантайма>/<modelId>`. */
    fun modelDir(runtimeDir: File, modelId: String): File = File(runtimeDir, modelId)

    /** Признак «файл скачивается в персональный каталог модели» для раскладки [fileDir]. */
    private fun inModelDirFor(
        runtime: RuntimeType,
        urls: List<String>,
        fileName: String
    ): Boolean = when (runtime) {
        RuntimeType.SHERPA, RuntimeType.PIPER_ONNX -> !isArchive(fileName)
        RuntimeType.MARIAN_SLIMT -> true
        else -> urls.size > 1
    }

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
    ): File =
        if (inModelDirFor(runtime, urls, fileName)) {
            modelDir(runtimeDir, modelId)
        } else {
            runtimeDir
        }

    /**
     * Кандидаты имён целевого файла в корне каталога рантайма: новое имя
     * (префикс modelId + санитизированный basename, п.15), санитизированный
     * basename без префикса и legacy-имена загрузок, начатых до смены схемы:
     * сырой basename без query/fragment, сырой basename ВМЕСТЕ с query
     * (старый `substringAfterLast('/')` не отрезал «?raw=true» — он попадал
     * в имя файла и в `.part`), fallback старого одиночного ONNX
     * `<modelId>.onnx` при пустом basename — очистка обязана находить
     * остатки всех схем.
     */
    private fun rootNameCandidates(modelId: String, url: String, index: Int): List<String> {
        val raw = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        val legacy = if (raw.isBlank() || raw.all { it == '.' }) "file_$index" else raw
        return buildList {
            add(targetFileName(modelId, url, index, inModelDir = false))
            val sanitized = sanitizedBasename(url, index)
            if (sanitized !in this) add(sanitized)
            if (legacy !in this) add(legacy)
            val rawWithQuery = url.substringAfterLast('/')
            if (rawWithQuery.isNotBlank() && !rawWithQuery.all { it == '.' } &&
                rawWithQuery !in this
            ) {
                add(rawWithQuery)
            }
            if (raw.isBlank() || raw.all { it == '.' }) {
                val legacySingle = "$modelId.onnx"
                if (legacySingle !in this) add(legacySingle)
            }
        }
    }

    /**
     * Удалить только части докачки `.part` и метаданные `.part.meta` по
     * раскладке [fileDir] (обе схемы имён корневого каталога) — целые целевые
     * файлы и каталог модели не трогаются: вызывается при импорте той же id,
     * когда в каталоге модели уже лежит промоутенный пучок импорта.
     */
    fun deletePendingParts(
        runtimeDir: File,
        runtime: RuntimeType,
        modelId: String,
        urls: List<String>
    ) {
        if (urls.isEmpty()) return
        val modelDir = modelDir(runtimeDir, modelId)
        urls.forEachIndexed { index, url ->
            val fileName = sanitizedBasename(url, index)
            val dir = fileDir(runtimeDir, runtime, modelId, urls, fileName)
            val names = if (dir == modelDir) listOf(fileName)
            else rootNameCandidates(modelId, url, index)
            for (name in names) {
                listOf(File(dir, "$name.part"), File(dir, "$name.part.meta")).forEach { part ->
                    if (part.isFile && !part.delete()) {
                        Timber.w("Не удалось удалить часть докачки ${part.name}")
                    }
                }
            }
        }
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
     * Имена в корне каталога перебираются по всем кандидатам обеих схем
     * нейминга (ревью, п.15) — legacy-остатки не должны переживать очистку.
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
            val fileName = sanitizedBasename(url, index)
            val dir = fileDir(runtimeDir, runtime, modelId, urls, fileName)
            if (dir == modelDir) {
                usesModelDir = true
                return@forEachIndexed
            }
            // Корень каталога рантайма: части докачки, метаданные докачки и
            // осиротевший целый целевой файл — одиночный ONNX или архив до
            // распаковки (для не READY-модели это остаток её же загрузки).
            for (name in rootNameCandidates(modelId, url, index)) {
                listOf(File(dir, "$name.part"), File(dir, "$name.part.meta")).forEach { part ->
                    if (part.isFile && !part.delete()) {
                        Timber.w("Не удалось удалить осиротевший ${part.name}")
                    }
                }
                val doneFile = File(dir, name)
                if (doneFile.isFile && !doneFile.delete()) {
                    Timber.w("Не удалось удалить осиротевший ${doneFile.name}")
                }
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
