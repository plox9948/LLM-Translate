package com.plox.llmtranslate.data.download

import com.plox.llmtranslate.domain.model.ArchiveFormats
import com.plox.llmtranslate.domain.model.ModelFileNaming
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
    fun isArchive(fileName: String): Boolean = ArchiveFormats.isArchive(fileName)

    /**
     * Санитизированное имя целевого файла по URL — делегирование единому
     * чистому источнику [ModelFileNaming.sanitizedBasename] (ревью аудита:
     * хелпер перенесён в domain, чтобы domain-логика
     * [com.plox.llmtranslate.domain.model.InstallCheckLogic] не
     * импортировала data-слой; кандидаты установленных файлов —
     * [installedFileCandidates], не дублируются).
     */
    fun sanitizedBasename(url: String, index: Int): String =
        ModelFileNaming.sanitizedBasename(url, index)

    /** Имя модели в составе имени файла корня: всё вне [A-Za-z0-9_.-] → '_'. */
    private fun sanitizeId(modelId: String): String =
        modelId.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("")

    /**
     * Разделитель склейки корневого имени «<id><разделитель><basename>». «~» не
     * встречается ни в результате [sanitizeId], ни в [sanitizedBasename], поэтому
     * имя однозначно разбирается и разные пары (id, basename) не могут столкнуться
     * (issue #83, п.7: прежний разделитель «__» допускал коллизию «a»+«__b.bin»
     * против «a__»+«_b.bin», из-за чего очистка осиротевших файлов могла снести
     * файл соседней READY-модели).
     */
    private const val PREFIX_SEPARATOR = "~"

    /**
     * Имя прежней схемы склейки «<sanitizeId(modelId)>__<basename>» (до issue #83).
     * Установленные по ней файлы не переименовываются — они обязаны находиться
     * сверками и очистками как legacy-кандидаты.
     */
    private fun legacyPrefixedName(modelId: String, base: String): String =
        "${sanitizeId(modelId)}__${base}"

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
        return if (inModelDir) base else "${sanitizeId(modelId)}$PREFIX_SEPARATOR$base"
    }

    /**
     * Кандидаты имени УСТАНОВЛЕННОГО файла модели для сверки SHA-256
     * (импорт ZIP в ModelManagerImpl.verifyImportChecksums, восстановление
     * бэкапа в ModelBackupManager.bundleShaOk) И очистки остатков в корне
     * каталога рантайма ([deletePendingParts], [deleteOrphanedFiles]) —
     * единый список обеих раскладок, чтобы сверка и очистка находили
     * файлы всех схем одинаково (расхождение наборов молча скипало
     * legacy-раскладку «<modelId>.onnx» при восстановлении бэкапа):
     * имя новой схемы с префиксом id, legacy-склейка «id__basename»,
     * санитизированный basename без префикса, legacy-сырой basename без
     * query, сырой basename вместе с query (старые загрузки писали
     * «?raw=true» прямо в имя файла) и legacy-фолбэк старого одиночного
     * ONNX «<modelId>.onnx» при пустом basename URL.
     */
    fun installedFileCandidates(modelId: String, url: String, index: Int): List<String> {
        val sanitized = sanitizedBasename(url, index)
        val raw = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        val legacyRootName = if (raw.isBlank() || raw.all { it == '.' }) "file_$index" else raw
        return buildList {
            add(targetFileName(modelId, url, index, inModelDir = false))
            // Legacy-имя прежней схемы склейки «id__basename» (issue #83):
            // установленные по ней файлы должны находиться сверкой.
            val legacyPrefixed = legacyPrefixedName(modelId, sanitized)
            if (legacyPrefixed !in this) add(legacyPrefixed)
            if (sanitized !in this) add(sanitized)
            if (legacyRootName !in this) add(legacyRootName)
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

    /** Разрешённые файловые символы modelId для каталога записи. */
    private val SAFE_MODEL_ID = Regex("[A-Za-z0-9._-]+")

    /**
     * Проверка modelId как имени каталога записи `<runtimeDir>/<modelId>`
     * (контрольный аудит): id используется в пути напрямую ([modelDir]),
     * а [deleteOrphanedFiles] сносит этот каталог через deleteRecursively —
     * id из внешнего манифеста бэкапа с «/», «\», «..» дал бы удаление или
     * подмену файлов вне каталога рантайма. Разрешены только
     * [A-Za-z0-9._-], «.» и «..» отклоняются.
     */
    fun isSafeModelId(modelId: String): Boolean =
        SAFE_MODEL_ID.matches(modelId) && modelId != "." && modelId != ".."

    /**
     * Каталог мультифайловой модели: `<каталог рантайма>/<modelId>`.
     * НЕБЕЗОПАСНЫЙ id (см. [isSafeModelId]) — [IllegalArgumentException]:
     * явная ошибка вместо тихой подстановки, вызывающий (воркер — через
     * общий перехват установки, импорт — через предварительную валидацию
     * записи манифеста) фиксирует её как отказ.
     */
    fun modelDir(runtimeDir: File, modelId: String): File {
        require(isSafeModelId(modelId)) { "Небезопасный id модели для каталога записи: $modelId" }
        return File(runtimeDir, modelId)
    }

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
     * Удалить в каталоге [dir] часть докачки `<name>.part` и её метаданные
     * `<name>.part.meta` (целые целевые файлы не касаются). Общий для очисток
     * [deletePendingParts] и [deleteOrphanedFiles].
     */
    private fun deletePartVariants(dir: File, name: String) {
        listOf(File(dir, "$name.part"), File(dir, "$name.part.meta")).forEach { part ->
            if (part.isFile && !part.delete()) {
                Timber.w("Не удалось удалить часть докачки ${part.name}")
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
        if (!isSafeModelId(modelId)) {
            Timber.w("Небезопасный id модели «$modelId» — части докачки не удаляются")
            return
        }
        val modelDir = modelDir(runtimeDir, modelId)
        urls.forEachIndexed { index, url ->
            val fileName = sanitizedBasename(url, index)
            val dir = fileDir(runtimeDir, runtime, modelId, urls, fileName)
            val names = if (dir == modelDir) listOf(fileName)
            else installedFileCandidates(modelId, url, index)
            for (name in names) {
                deletePartVariants(dir, name)
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
     * Имена в корне каталога перебираются по единому списку кандидатов
     * [installedFileCandidates] (все схемы нейминга, ревью, п.15) —
     * legacy-остатки не должны переживать очистку, а сверки (импорт,
     * бэкап) видят тот же набор, что и очистка.
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
        // Небезопасный id (например, из внешнего манифеста бэкапа) дал бы
        // File(runtimeDir, "a/../x") и deleteRecursively вне каталога
        // рантайма — такую запись очистки пропускаем целиком.
        if (!isSafeModelId(modelId)) {
            Timber.w("Небезопасный id модели «$modelId» — остатки не удаляются")
            return
        }
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
            for (name in installedFileCandidates(modelId, url, index)) {
                deletePartVariants(dir, name)
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
