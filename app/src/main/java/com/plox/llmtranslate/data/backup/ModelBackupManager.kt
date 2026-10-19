package com.plox.llmtranslate.data.backup

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.plox.llmtranslate.data.download.DownloadLayout
import com.plox.llmtranslate.data.download.ModelDownloadWorker
import com.plox.llmtranslate.data.model.ModelRepository
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.data.storage.ModelStorage
import com.plox.llmtranslate.domain.model.ArchiveFormats
import com.plox.llmtranslate.domain.model.InstallCheckLogic
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import com.plox.llmtranslate.domain.model.TtsModelSelector
import com.plox.llmtranslate.domain.runtime.RuntimeRouter
import com.plox.llmtranslate.util.isFileInsideDir
import com.plox.llmtranslate.util.sha256Hex
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Чистый маппинг восстановительных операций поязыкового назначения
 * (ревью #53, уточнение ревью #5): операции строятся только по языкам
 * секции назначений бэкапа [saved] — язык, отсутствующий в частичном
 * архиве, остаётся как есть (снять назначение можно только для языка,
 * явно присутствующего в манифесте). [saved] == null (секции нет в старом
 * архиве) — операций нет, настройку не трогаем. Вынесено для unit-теста
 * без Android-специфики.
 */
internal fun assignmentOps(
    saved: Map<String, String?>?
): List<Pair<String, String?>> {
    if (saved == null) return emptyList()
    return saved.map { (lang, id) -> lang to id }
}

/**
 * Сверка контрольных сумм распакованной записи модели с хэшами манифеста
 * (ревью раунд 9 + ревью #3). Одиночный файл с непустым
 * [BackupModelEntry.sha256] — строгое сравнение SHA-256 байт файла с
 * hex-хэшем манифеста (регистронезависимо); пустой хэш (импортированная
 * модель) — сверки нет. Каталог-пучок — пофайловая сверка по
 * [BackupModelEntry.sha256s] (см. [bundleShaOk]); skip остаётся только
 * там, где сверка формально невозможна: archive-установки и gzip-артефакты.
 */
internal fun backupShaOk(entry: BackupModelEntry, file: File): Boolean {
    if (file.isDirectory) return bundleShaOk(entry, file)
    val expected = entry.sha256?.takeIf { it.isNotBlank() } ?: return true
    val actual = runCatching { sha256Hex(file) }.getOrNull() ?: return false
    return actual.equals(expected, ignoreCase = true)
}

/**
 * Пофайловая сверка каталога-пучка по [BackupModelEntry.sha256s]
 * (ревью #3: раньше каталоги не сверялись вовсе, хотя для пучков без
 * gz-артефактов хэши соответствуют байтам установленных файлов).
 * Индексы [BackupModelEntry.sha256s] соответствуют
 * [BackupModelEntry.urls], имена файлов ищутся по кандидатам раскладки
 * загрузок [DownloadLayout] (как при проверке импорта ZIP в
 * ModelManagerImpl.verifyImportChecksums). Пропуск сверки — только для
 * архивных URL (хэш относится к архиву до распаковки) и сжатых
 * gzip-артефактов slimt (хэш сжатых байт, на диске — распакованные
 * файлы, см. [ArchiveFormats.isCompressedArtifact]). Пустой sha у элемента
 * или отсутствие непустых sha в списке — сверять нечем, ok.
 */
private fun bundleShaOk(entry: BackupModelEntry, dir: File): Boolean {
    if (entry.urls.isEmpty() || entry.sha256s.none { hash -> !hash.isNullOrBlank() }) return true
    entry.urls.forEachIndexed { index, url ->
        val expected = entry.sha256s.getOrNull(index)?.takeIf { it.isNotBlank() }
            ?: return@forEachIndexed
        val fileName = DownloadLayout.sanitizedBasename(url, index)
        if (ArchiveFormats.isArchive(fileName) || ArchiveFormats.isCompressedArtifact(fileName)) {
            return@forEachIndexed
        }
        val candidates = DownloadLayout.installedFileCandidates(entry.id, url, index)
        val installed = candidates.asSequence()
            .map { name -> File(dir, name) }
            .firstOrNull { it.isFile }
        val actual = installed?.let { f -> runCatching { sha256Hex(f) }.getOrNull() }
        if (actual == null || !actual.equals(expected, ignoreCase = true)) return false
    }
    return true
}

/**
 * Оценка места под установку записи бэкапа (ревью раунда 3): sizeBytes
 * манифеста — размер скачиваемых артефактов, без раздувания распаковки
 * archive (.tar.gz/.zip/.tar.bz2 -> x2) и gz-артефактов slimt (gunzip -> x1.8).
 * Тип раскладки определяется единой классификацией гейта скачивания
 * [InstallCheckLogic.urlLayoutFlags] (те же имена URL, что в UI-подсказке и
 * гейте ModelDownloadWorker; archive/gz-имена — [ArchiveFormats]), требуемый
 * объём — единой функцией [InstallCheckLogic.requiredDownloadFreeBytes]
 * (для одиночных файлов её запас 1.2x заменяет прежний x1.1 гейта импорта).
 * Пустые urls (импортированные модели) — одиночная раскладка.
 */
internal fun backupInstallRequiredBytes(entry: BackupModelEntry): Long {
    val (isArchiveLayout, isGzipLayout) = InstallCheckLogic.urlLayoutFlags(entry.urls)
    return InstallCheckLogic.requiredDownloadFreeBytes(
        entry.sizeBytes, isArchiveLayout, isGzipLayout
    )
}

/**
 * Префикс temp-каталога импорта бэкапа (соседствует с корнем моделей) и
 * temp-файла экспорта (кэш). Используются и генерацией, и стартовым GC
 * [cleanupOrphanBackupTemp] (issue #83, п.6).
 */
internal const val BACKUP_IMPORT_TEMP_PREFIX = "backup_import_"
internal const val BACKUP_EXPORT_TEMP_PREFIX = "backup_export_"

/**
 * Возраст осиротевших temp-артефактов бэкапа, удаляемых при старте
 * приложения (issue #83, п.6, по образцу осиротевших записей
 * AudioRecorder.cleanupOrphanRecordings): моложе порога — возможная живая
 * работа текущей сессии, не трогаем.
 */
internal const val BACKUP_TEMP_TTL_MS = 24 * 60 * 60 * 1000L

/**
 * Стартовый GC осиротевших temp-артефактов бэкапа (issue #83, п.6): каталоги
 * backup_import_* и файлы backup_export_*.zip, оставленные процессами
 * импорта/экспорта, убитыми до finally-очистки. [dirs] — каталоги-родители
 * (сосед корня моделей и кэш экспорта); отсутствующий/недоступный каталог
 * пропускается.
 * @return число удалённых артефактов. internal — покрыто unit-тестом на
 * временных файлах без Android.
 */
internal fun cleanupOrphanBackupTemp(
    dirs: List<File?>,
    ttlMs: Long = BACKUP_TEMP_TTL_MS,
    now: Long = System.currentTimeMillis()
): Int {
    var removed = 0
    for (dir in dirs) {
        val stale = dir?.listFiles()?.filter { f ->
            val name = f.name
            (name.startsWith(BACKUP_IMPORT_TEMP_PREFIX) ||
                name.startsWith(BACKUP_EXPORT_TEMP_PREFIX)) &&
                f.lastModified() < now - ttlMs
        } ?: continue
        for (f in stale) {
            if (f.deleteRecursively()) removed++
            else Timber.w("Не удалось удалить осиротевший temp-артефакт бэкапа: ${f.path}")
        }
    }
    if (removed > 0) {
        Timber.d("Удалено осиротевших temp-артефактов бэкапа: $removed")
    }
    return removed
}

/**
 * Best-effort каталог файловой системы, содержащий целевой SAF-документ,
 * по его document id — для StatFs-гейта места ДО усечения существующего
 * документа (issue #83, п.4). Поддержаны форматы ids провайдера внешнего
 * хранилища: "raw:<абсолютный путь>" и "primary:<путь относительно корня
 * тома>". Прочие провайдеры/форматы — null: оценить том произвольного
 * SAF-провайдера нельзя, но и отказывать экспорту из-за этого нельзя
 * (гейт пропускается с логом). internal — unit-тест без Android.
 */
internal fun dirHintFromDocumentId(docId: String?, externalRoot: File?): File? {
    docId ?: return null
    return when {
        docId.startsWith("raw:") ->
            File(docId.removePrefix("raw:")).takeIf { it.isAbsolute }?.parentFile
        docId.startsWith("primary:") ->
            externalRoot?.let { File(it, docId.removePrefix("primary:")).parentFile }
        else -> null
    }
}
/**
 * Менеджер резервного копирования моделей и настроек.
 *
 * Формат архива:
 * - manifest.json — метаданные моделей
 * - settings.json — настройки (опционально)
 * - models/... — файлы моделей
 */
@Singleton
class ModelBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ModelRepository,
    private val runtimeRouter: RuntimeRouter,
    private val preferences: ModelPreferences,
    private val json: Json
) {

    companion object {
        const val MANIFEST_NAME = "manifest.json"
        const val SETTINGS_NAME = "settings.json"
        const val MODELS_PREFIX = "models/"

        /** Таймаут запроса состояний unique-работы WorkManager, с. */
        private const val WORK_QUERY_TIMEOUT_SECONDS = 5L

        /** Интервал опроса терминального статуса после отмены загрузки, мс. */
        private const val CANCEL_POLL_INTERVAL_MS = 250L

        /** Предел ожидания терминального статуса отменённого воркера, мс. */
        private const val CANCEL_TERMINAL_TIMEOUT_MS = 10_000L
    }

    /**
     * Результат импорта.
     */
    data class ImportResult(
        val importedCount: Int,
        val skippedCount: Int,
        val settingsRestored: Boolean,
        val error: String? = null
    )

    /**
     * Экспортировать все модели и настройки в ZIP по Uri.
     *
     * Ревью #4: архив сначала собирается целиком во временный файл, и только
     * готовый копируется в выходной Uri ОДНИМ потоком copy — ошибка сериализации
     * ZIP не оставляет в целевом документе полусжатый поток, и обрыв SAF-потока
     * или ENOSPC не оставляет пользователю «полусжатый» архив от потоковой
     * сборки. Ревью (раунд повторный): temp пишется в getExternalCacheDir()
     * (фолбэк cacheDir) — пик «модели + ZIP» не должен целиком ложиться на
     * /data; перед сборкой — StatFs-гейт тома temp (суммарный размер
     * включаемых файлов x1.1). Временный файл удаляется в любом случае.
     *
     * Issue #83, п.4 (ограничение SAF — фиксируем поведение явно):
     * openOutputStream УСЕКАЕТ уже существующий документ до записи — SAF не
     * даёт атомарно подменить документ целиком, поэтому «старый рабочий
     * архив» при обрыве копирования полностью сохранить нельзя. Минимум
     * надёжности ДО усечения: StatFs-гейт свободного места на целевом volume
     * ([targetVolumeDir]/[dirHintFromDocumentId], аналог [hasFreeSpaceFor]) —
     * если место заведомо кончится, экспорт отказывает, не трогая старый
     * архив; для провайдера, чей том оценить нельзя, гейт пропускается с
     * предупреждением. При обрыве уже начатого копирования недописанный
     * документ удаляется ПОСЛЕ попытки удаления с логом результата
     * ([deleteFailedDocument]) — не молча.
     * @return true при успехе.
     */
    suspend fun exportToUri(targetUri: Uri): Boolean = withContext(Dispatchers.IO) {
        // Внешний кэш не выделяется (отсутствует примонтованный volume) —
        // фолбэк на внутренний cacheDir.
        val tempRoot = context.externalCacheDir?.takeIf { it.exists() || it.mkdirs() }
            ?: context.cacheDir
        // Уникальный суффикс: параллельные экспорты не затирают общий temp.
        val tempZip = File(tempRoot, BACKUP_EXPORT_TEMP_PREFIX + UUID.randomUUID() + ".zip")
        // Копирование в Uri уже началось — по исключению документ обрезан.
        var outputOpened = false
        try {
            val models = repository.observeAll().first().filter { it.isReady }
            if (models.isEmpty()) {
                Timber.w("Нет моделей для экспорта")
                return@withContext false
            }

            // Гейт свободного объёма под temp-ZIP до сборки: ZIP почти не
            // сжимает бинарные модели, требуется ~суммарный размер файлов
            // (x1.1 запас); нехватка — понятный отказ до начала записи.
            val neededBytes = includedContentBytes(models) * 11 / 10
            if (!hasFreeSpaceFor(tempRoot, neededBytes)) {
                Timber.e(
                    "Недостаточно места под временный архив бэкапа: требуется ~$neededBytes " +
                        "байт на томе ${tempRoot.absolutePath}"
                )
                return@withContext false
            }

            val sources = buildManifestEntries(models)
            val manifest = BackupManifest(
                version = 1,
                exportedAt = System.currentTimeMillis(),
                models = sources.map { it.entry }
            )

            tempZip.outputStream().buffered().use { out ->
                ZipOutputStream(out).use { zip ->
                    // 1. Манифест.
                    zip.putNextEntry(ZipEntry(MANIFEST_NAME))
                    zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
                    zip.closeEntry()

                    // 2. Настройки.
                    val settings = buildSettings()
                    zip.putNextEntry(ZipEntry(SETTINGS_NAME))
                    zip.write(json.encodeToString(BackupSettings.serializer(), settings).toByteArray())
                    zip.closeEntry()

                    // 3. Файлы моделей — по парам «запись БД <-> запись
                    // манифеста» (issue #83, п.8: раньше связь держалась
                    // индексом forEachIndexed; после дедупликации путей,
                    // ревью #2) — иначе ZIP-запись и манифест могут разойтись.
                    sources.forEach { source ->
                        val file = source.model.filePath?.let(::File) ?: return@forEach
                        if (!file.exists()) return@forEach
                        if (file.isDirectory) {
                            writeDirectoryToZip(zip, file, source.entry.relativePath)
                        } else {
                            writeFileToZip(zip, file, source.entry.relativePath)
                        }
                    }
                }
            }

            // Гейт целевого volume ДО открытия выходного потока (issue #83,
            // п.4): openOutputStream усечёт существующий SAF-документ — если
            // на целевом томе не хватит места даже для готового архива,
            // старый рабочий архив был бы уничтожен зря.
            val archiveBytes = tempZip.length()
            val targetDir = targetVolumeDir(targetUri)
            if (targetDir == null) {
                Timber.w(
                    "Том целевого документа бэкапа не определим — гейт места перед " +
                        "усечением существующего документа пропущен: $targetUri"
                )
            } else if (!hasFreeSpaceFor(targetDir, archiveBytes)) {
                Timber.e(
                    "Недостаточно места на томе целевого документа: требуется ~$archiveBytes " +
                        "байт на ${targetDir.absolutePath} — отказ до усечения документа"
                )
                return@withContext false
            }

            val output = context.contentResolver.openOutputStream(targetUri)
                ?: return@withContext false
            outputOpened = true
            try {
                output.use { out ->
                    tempZip.inputStream().use { it.copyTo(out) }
                }
            } catch (e: Exception) {
                // Обрыв copyTo: в Uri остался усечённый документ — удаляем его
                // с логом (не молча), иначе пользователь получит «битый архив»
                // вместо отсутствия файла. Прежний архив при этом уже потерян:
                // SAF усекает документ при открытии потока, см. KDoc метода.
                Timber.e(e, "Обрыв копирования бэкапа в $targetUri — удаляем недописанный документ")
                if (outputOpened) deleteFailedDocument(targetUri)
                throw e
            }

            Timber.d("Экспортировано ${models.size} моделей")
            true
        } catch (e: Exception) {
            Timber.e(e, "Ошибка экспорта бэкапа")
            false
        } finally {
            tempZip.delete()
        }
    }

    /**
     * Каталог тома, на котором лежит целевой документ (для гейта места до
     * усечения, issue #83, п.4). file:// — путь напрямую; content:// —
     * best-effort разбор document id внешнего хранилища
     * ([dirHintFromDocumentId]); прочие провайдеры и сбои — null (гейт
     * пропускается с логом в вызывающем).
     */
    private fun targetVolumeDir(uri: Uri): File? = try {
        when (uri.scheme) {
            "file" -> uri.path?.let { File(it).parentFile }
            "content" -> dirHintFromDocumentId(
                DocumentsContract.getDocumentId(uri),
                Environment.getExternalStorageDirectory()
            )
            else -> null
        }
    } catch (e: Exception) {
        Timber.w(e, "Не удалось определить том целевого документа бэкапа: $uri")
        null
    }

    /** Суммарный размер файлов, попадающих в ZIP (кандидат гейта temp-объёма). */
    private fun includedContentBytes(models: List<InstalledModel>): Long =
        models.sumOf { model ->
            val file = model.filePath?.let(::File) ?: return@sumOf 0L
            when {
                file.isFile -> file.length()
                file.isDirectory -> file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                else -> 0L
            }
        }

    /**
     * Достаточно ли свободного места на томе [dir] под [neededBytes].
     * Неготовность оценки (StatFs-исключение — в т.ч. unit-test JVM без
     * android.os) не блокирует экспорт (true), по образцу
     * ModelManagerImpl.hasEnoughFreeSpace.
     */
    private fun hasFreeSpaceFor(dir: File, neededBytes: Long): Boolean = try {
        val stat = StatFs(dir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong >= neededBytes
    } catch (e: Exception) {
        Timber.w(e, "Не удалось оценить свободное место под архив бэкапа — гейт пропущен")
        true
    }

    /**
     * Удалить SAF-документ, оставшийся недописанным после обрыва копирования.
     * Работает для document Uri из SAF-пикера; для прочих Uri (или при
     * отказе самого провайдера) — только лог.
     */
    private fun deleteFailedDocument(targetUri: Uri) {
        try {
            if (!DocumentsContract.deleteDocument(context.contentResolver, targetUri)) {
                Timber.w("Недописанный SAF-документ бэкапа не удалён: $targetUri")
            }
        } catch (e: Exception) {
            Timber.w(e, "Не удалось удалить недописанный SAF-документ бэкапа: $targetUri")
        }
    }
    /**
     * Импортировать модели и настройки из ZIP по Uri.
     *
     * Issue #83, п.1: всё тело импорта под общим [ModelImportLock] — тем же,
     * под которым ModelManagerImpl выполняет одиночный/ZIP-импорт, удаление и
     * отмену скачивания. Без него импорт бэкапа гонял moveToTarget/upsert с
     * живым [ModelDownloadWorker] или параллельным импортом той же id, порча
     * восстановленную READY-запись. Перед подменой файлов каждой id из
     * манифеста её живая загрузка отменяется и ожидается терминальный статус
     * воркера ([cancelLiveDownloadForImport], по образцу
     * ModelManagerImpl.cancelSupersededDownload).
     *
     * Issue #83, п.5: распаковка ДВУХПРОХОДНАЯ. Первый проход читает из архива
     * только manifest.json/settings.json, НИЧЕГО не записывая, поэтому
     * StatFs-гейт места по оценке манифеста гарантированно выполняется ДО
     * появления файлов models/ в temp (раньше при манифесте в конце архива
     * файлы уже писались, и при отказе места оставались до finally; а при
     * манифесте с путями внутри models/ фолбэк [findExtractedEntry] мог
     * резолвить пучок в сырой файл архивной раскладки). Второй проход
     * (повторное открытие Uri — SAF это разрешает) извлекает только записи
     * models/.
     *
     * Ревью повт. аудита, п.6: temp-распаковка пишется на тот же volume,
     * что и каталог моделей ([ModelStorage.modelsRoot]) — раньше шла в
     * context.cacheDir (внутренний /data) при моделях на внешнем разделе,
     * поэтому [moveToTarget] всегда падал к cross-volume copy, а StatFs-гейт
     * объёма вообще не проверялся на импорте. Temp кладётся соседом корня
     * моделей (по образцу staging в ModelManagerImpl.promoteExtractedDir);
     * оценка гейта учитывает раздувание распаковки archive/gz-раскладок
     * той же формулой, что гейт скачивания (ревью раунда 3),
     * [backupInstallRequiredBytes]. Осиротевшие от убитого процесса
     * temp-каталоги чистит стартовый GC [cleanupOrphanBackupTemp].
     */
    suspend fun importFromUri(sourceUri: Uri): ImportResult =
        ModelImportLock.mutex.withLock {
            withContext(Dispatchers.IO) { importLocked(sourceUri) }
        }

    /** Тело импорта под [ModelImportLock] — см. [importFromUri]. */
    private suspend fun importLocked(sourceUri: Uri): ImportResult {
        var imported = 0
        var skipped = 0
        var settingsRestored = false
        // Уникальный суффикс temp-каталога: два параллельных импорта бэкапа
        // не должны делить один каталог (каждый чистит в finally свой).
        // Temp — сосед каталога моделей (тот же volume), а не cacheDir:
        // иначе moveToTarget всегда уходит в cross-volume copy, а
        // StatFs-гейт оценивает не тот том (ревью повт. аудита, п.6).
        val modelsRoot = ModelStorage.modelsRoot(context)
        val tempRoot = modelsRoot.parentFile ?: modelsRoot
        val tempDir = File(tempRoot, BACKUP_IMPORT_TEMP_PREFIX + UUID.randomUUID())

        try {
            // --- Проход 1: только метаданные (manifest + settings), файлов
            // models/ не касаемся (issue #83, п.5). ---
            var manifest: BackupManifest? = null
            var settings: BackupSettings? = null
            val metaInput = context.contentResolver.openInputStream(sourceUri)
                ?: return ImportResult(0, 0, false, "Не удалось открыть архив")
            metaInput.use { ins ->
                ZipInputStream(ins.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        when {
                            entry.isDirectory -> Unit
                            entry.name == MANIFEST_NAME ->
                                manifest = json.decodeFromString(
                                    BackupManifest.serializer(), readEntry(zip)
                                )
                            entry.name == SETTINGS_NAME ->
                                settings = json.decodeFromString(
                                    BackupSettings.serializer(), readEntry(zip)
                                )
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }

            val manifestData = manifest
                ?: return ImportResult(0, 0, false, "Нет манифеста в архиве")

            // StatFs-гейт ПОСЛЕ чтения манифеста и ДО записи файлов моделей
            // (двухпроходность, issue #83, п.5): sizeBytes манифеста — размер
            // СКАЧИВАЕМЫХ артефактов, а на диск ложится распакованное
            // содержимое archive (x2) и gz (x1.8) раскладок. Оценка — единой
            // формулой гейта скачивания InstallCheckLogic
            // ([backupInstallRequiredBytes]; архивные/gz имена — те же
            // ArchiveFormats и DownloadLayout, что в bundleShaOk); том — тот,
            // где лежит temp (он же том каталога моделей). Нехватка — явный
            // отказ до записи файла модели.
            val neededBytes = manifestData.models.sumOf { entry ->
                backupInstallRequiredBytes(entry)
            }
            if (!hasFreeSpaceFor(tempDir, neededBytes)) {
                Timber.e(
                    "Недостаточно места под распаковку бэкапа: " +
                        "требуется ~$neededBytes байт на томе " +
                        "${tempDir.absolutePath}"
                )
                return ImportResult(
                    0, 0, false,
                    "Недостаточно свободного места для распаковки бэкапа"
                )
            }
            tempDir.mkdirs()

            // --- Проход 2: только файлы моделей. ---
            val filesInput = context.contentResolver.openInputStream(sourceUri)
                ?: return ImportResult(0, 0, false, "Не удалось повторно открыть архив для файлов моделей")
            filesInput.use { ins ->
                ZipInputStream(ins.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (!entry.isDirectory && name.startsWith(MODELS_PREFIX)) {
                            // Сохраняем файл во временный каталог.
                            val outFile = File(tempDir, name)
                            // Защита от zip-slip: файл не должен выходить
                            // за пределы временного каталога.
                            if (!isFileInsideDir(tempDir, outFile)) {
                                Timber.w("ZIP-запись вне каталога, пропущена: $name")
                            } else {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { zip.copyTo(it) }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }

            // Создаём записи моделей.
            val restoredIds = mutableSetOf<String>()
            manifestData.models.forEach { entry ->
                // Путь из манифеста может выйти за пределы каталога моделей.
                val targetPath = resolveTargetPath(entry)
                if (targetPath == null) {
                    Timber.w("Некорректный путь модели в архиве: ${entry.relativePath}")
                    skipped++
                    return@forEach
                }
                // relativePath в манифестах текущей версии уже начинается с
                // MODELS_PREFIX (см. toRelativePath); вариант с префиксом —
                // fallback для манифестов, где путь записан без него.
                val tempFile = findExtractedEntry(tempDir, entry.relativePath)

                if (tempFile == null) {
                    Timber.w("Файл модели не найден в архиве: ${entry.relativePath}")
                    skipped++
                    return@forEach
                }

                // Контрольная сумма из манифеста (ревью раунд 9, ревью #3):
                // битый файл модели в архиве не должен регистрироваться READY.
                // Одиночные файлы сверяются с sha256, каталоги-пучки —
                // пофайлово с sha256s; без сверки остаются только archive-
                // установки и gzip-артефакты (хэш относится к скачанному
                // архиву/сжатым байтам, а не к содержимому), см. [backupShaOk].
                if (!backupShaOk(entry, tempFile)) {
                    Timber.w(
                        "SHA-256 файла модели ${entry.id} в архиве не совпала с манифестом — пропуск"
                    )
                    skipped++
                    return@forEach
                }

                // Одна битая запись (например, category/runtime из будущей версии
                // приложения) не должна прерывать весь импорт — пропускаем её.
                val model = try {
                    InstalledModel(
                        id = entry.id,
                        category = ModelCategory.valueOf(entry.category),
                        displayName = entry.displayName,
                        description = entry.description,
                        runtime = RuntimeType.valueOf(entry.runtime),
                        sizeBytes = entry.sizeBytes,
                        ramRequiredMb = entry.ramRequiredMb,
                        languages = entry.languages,
                        quality = entry.quality,
                        speed = entry.speed,
                        url = entry.url,
                        urls = entry.urls,
                        sha256 = entry.sha256,
                        sha256s = entry.sha256s,
                        license = entry.license,
                        minAndroidSdk = entry.minAndroidSdk,
                        recommendedRamMb = entry.recommendedRamMb,
                        status = ModelStatus.READY,
                        progress = 100,
                        filePath = targetPath,
                        bytesDone = entry.sizeBytes,
                        isBuiltin = entry.isBuiltin
                    )
                } catch (e: IllegalArgumentException) {
                    Timber.w("Запись ${entry.id} пропущена: неизвестные данные архива (${e.message})")
                    skipped++
                    return@forEach
                }

                // Issue #83, п.1: перед подменой файлов отменяем живую загрузку
                // той же id — иначе WorkManager-воркер дописывает .part и
                // переименовывает цели раскладки поверх восстановленного пучка
                // (гейт finalizeDownload по статусу защищает только запись БД).
                cancelLiveDownloadForImport(entry.id)

                // Issue #70: импорт подменяет файлы модели той же id — eviction
                // кэша движков по образцу promoteExtractedDir (#69) ДО подмены:
                // иначе LRU-кэш по id продолжает инференс старым нативным
                // handle до перезапуска процесса, а удаление прежнего файла
                // снимает живой mmap. Порядок: release -> подмена -> upsert.
                repository.getById(entry.id)?.let { runtimeRouter.releaseModel(it) }

                // Переименовываем временный файл в целевой.
                if (moveToTarget(tempFile, targetPath)) {
                    repository.upsert(model)
                    restoredIds += entry.id
                    imported++
                } else {
                    skipped++
                }
            }

            // Восстанавливаем настройки.
            val settingsData = settings
            if (settingsData != null) {
                restoreSettings(settingsData, restoredIds)
                settingsRestored = true
            }

            return ImportResult(imported, skipped, settingsRestored)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка импорта бэкапа")
            return ImportResult(imported, skipped, settingsRestored, e.message ?: "Ошибка импорта")
        } finally {
            // Временный каталог удаляется в любом случае; осиротевшие от
            // убитого процесса каталоги чистит стартовый GC (issue #83, п.6).
            tempDir.deleteRecursively()
        }
    }

    /**
     * Отменить живую загрузку модели [modelId] перед подменой её файлов из
     * бэкапа (issue #83, п.1, по образцу
     * ModelManagerImpl.cancelSupersededDownload): живой unique-воркер после
     * upsert продолжил бы дописывать .part и переименовывать целевые файлы
     * раскладки этой id поверх восстановленного пучка. После cancel ждём
     * терминального статуса — отменённый, но ещё не прерванный воркер иначе
     * дописывает файлы уже после moveToTarget. Части докачки (.part/.part.meta)
     * чистит сам воркер в cleanupCancelledModel при отмене. Неинициализированный
     * WorkManager (unit-test JVM) — живой работы нет.
     */
    private suspend fun cancelLiveDownloadForImport(modelId: String) {
        if (!isImportWorkLive(modelId)) return
        ModelDownloadWorker.cancel(context, modelId)
        val deadline = System.currentTimeMillis() + CANCEL_TERMINAL_TIMEOUT_MS
        while (isImportWorkLive(modelId)) {
            if (System.currentTimeMillis() >= deadline) {
                Timber.w(
                    "Воркер загрузки $modelId не достиг терминального статуса " +
                        "через $CANCEL_TERMINAL_TIMEOUT_MS мс после отмены"
                )
                return
            }
            delay(CANCEL_POLL_INTERVAL_MS)
        }
        Timber.d("Живая загрузка $modelId отменена перед импортом бэкапа")
    }

    /**
     * Живая unique-работа [ModelDownloadWorker] модели (ENQUEUED/RUNNING/
     * BLOCKED; см. ModelManagerImpl.isWorkLive). При сбое запроса WorkManager —
     * false («живой работы нет»»: ложная приостановка импорта хуже остаточной
     * гонки, закрываемой самовосстановлением сверки READY-записей с диском).
     */
    private fun isImportWorkLive(modelId: String): Boolean = try {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ModelDownloadWorker.workName(modelId))
            .get(WORK_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .any { info ->
                info.state == WorkInfo.State.ENQUEUED ||
                    info.state == WorkInfo.State.RUNNING ||
                    info.state == WorkInfo.State.BLOCKED
            }
    } catch (e: Exception) {
        Timber.w(e, "WorkManager недоступен при проверке живой загрузки $modelId — живой работы нет")
        false
    }
    /**
     * Пары «запись БД <-> запись манифеста» (issue #83, п.8): запись файлов
     * в ZIP идёт по этим парам, а не по индексу forEachIndexed — раньше
     * параллельный сдвиг составов manifest.models и models молча разъезжался.
     *
     * Ревью #2: фолбэк [toRelativePath] для файлов вне корня моделей теряет
     * структуру каталога — одноимённые файлы разных моделей (например
     * model.onnx у двух установок) получают одинаковый relativePath и
     * затирают друг друга в ZIP, а при импорте оба upsert резолвятся в
     * один файл. Коллизию разрешаем уникальным путём с id модели (id
     * уникален в БД), о чём пишем в лог.
     */
    private fun buildManifestEntries(models: List<InstalledModel>): List<ManifestSource> {
        val usedPaths = mutableSetOf<String>()
        return models.map { model ->
            val basePath = toRelativePath(model)
            val relativePath = when {
                basePath.isBlank() || usedPaths.add(basePath) -> basePath
                else -> {
                    val unique = MODELS_PREFIX + model.id + "/" + File(model.filePath.orEmpty()).name
                    Timber.w("Путь $basePath в манифесте уже занят — модель ${model.id} пишется как $unique")
                    usedPaths += unique
                    unique
                }
            }
            ManifestSource(
                model = model,
                entry = BackupModelEntry(
                    id = model.id,
                    category = model.category.name,
                    displayName = model.displayName,
                    description = model.description,
                    runtime = model.runtime.name,
                    sizeBytes = model.sizeBytes,
                    ramRequiredMb = model.ramRequiredMb,
                    languages = model.languages,
                    quality = model.quality,
                    speed = model.speed,
                    url = model.url,
                    urls = model.urls,
                    sha256 = model.sha256,
                    sha256s = model.sha256s,
                    license = model.license,
                    minAndroidSdk = model.minAndroidSdk,
                    recommendedRamMb = model.recommendedRamMb,
                    relativePath = relativePath,
                    isBuiltin = model.isBuiltin
                )
            )
        }
    }

    /** Пара «запись БД <-> запись манифеста» (issue #83, п.8). */
    private data class ManifestSource(val model: InstalledModel, val entry: BackupModelEntry)

    /**
     * Построить настройки (ревью #53: полные поязыковые назначения, тайм-аут
     * выгрузки, удержание моделей при смене направления, последнее направление).
     *
     * Регресс (исправление): чтение — СЫРЫМИ raw*-потоками, как prefetch
     * uiLanguage: recover-обёртка flowWithRecovery при сбое DataStore гасит
     * исключение и эмитит дефолт, поэтому экспорт писал бы в архив дефолтные
     * настройки вместо отказа, а restore затем затирал бы реальные значения
     * дефолтами. Здесь сбой чтения — исключение и общая ошибка экспорта
     * (как было до введения обёртки).
     */
    private suspend fun buildSettings(): BackupSettings {
        return BackupSettings(
            activeTranslation = preferences.getActiveModelId(ModelCategory.TRANSLATION),
            activeStt = preferences.getActiveModelId(ModelCategory.STT),
            activeTts = preferences.getActiveModelId(ModelCategory.TTS),
            ttsEngine = preferences.rawTtsEngine.first(),
            uiLanguage = preferences.rawUiLanguage.first(),
            theme = preferences.rawTheme.first(),
            sttByLanguage = preferences.rawSttByLanguage.first(),
            ttsByLanguage = preferences.rawTtsByLanguage.first(),
            unloadTimeoutMinutes = preferences.rawUnloadTimeoutMinutes.first(),
            modelDirectionRetention = preferences.modelDirectionRetention.first(),
            lastSourceLang = preferences.rawLastSourceLang.first(),
            lastTargetLang = preferences.rawLastTargetLang.first()
        )
    }

    /**
     * Восстановить настройки. Presence-семантика (ревью #53, уточнение
     * ревью #5): ключ секции в JSON = применять по языкам самой секции;
     * отсутствие ключа (null из старого архива) = не трогать; язык,
     * отсутствующий в секции частично-языкового бэкапа, остаётся как есть.
     * Модельные id (активные модели и поязыковые назначения) применяются
     * только для моделей, реально восстановленных в этом импорте
     * ([restoredIds]): иначе настройка осталась бы висячей на отсутствующую
     * модель (кроме сентинеля системного TTS — он не требует модели).
     *
     * Все применения — через `edit`-сеттеры [ModelPreferences] (без
     * recover-обёрток): сбой записи DataStore бросает исключение и
     * превращается в общую ошибку импорта внешним catch (проверено — путь
     * restore не читает обёрнутые потоки, регресса фолбэков здесь нет).
     */
    private suspend fun restoreSettings(settings: BackupSettings, restoredIds: Set<String>) {
        restoreActiveIfRestored(ModelCategory.TRANSLATION, settings.activeTranslation, restoredIds)
        restoreActiveIfRestored(ModelCategory.STT, settings.activeStt, restoredIds)
        restoreActiveIfRestored(ModelCategory.TTS, settings.activeTts, restoredIds)
        preferences.setTtsEngine(settings.ttsEngine)
        preferences.setUiLanguage(settings.uiLanguage)
        preferences.setTheme(settings.theme)
        // Поязыковые назначения: только языки из секции назначений архива
        // (ревью #5: язык, отсутствующий в частичном бэкапе, не снимается).
        // Id не восстановленных в этом импорте моделей пропускаем
        // (сентинель «system» назначается всегда).
        restoreLanguageAssignments(
            settings.sttByLanguage, "STT", null, restoredIds,
            preferences::setSttForLanguage
        )
        restoreLanguageAssignments(
            settings.ttsByLanguage, "TTS", TtsModelSelector.SYSTEM_SENTINEL, restoredIds,
            preferences::setTtsForLanguage
        )
        settings.unloadTimeoutMinutes?.let { preferences.setUnloadTimeoutMinutes(it) }
        settings.modelDirectionRetention?.let { preferences.setModelDirectionRetention(it) }
        val lastSource = settings.lastSourceLang
        val lastTarget = settings.lastTargetLang
        if (lastSource != null && lastTarget != null) {
            preferences.setLastDirection(lastSource, lastTarget)
        }
    }

    /** Применить активный id категории только если модель восстановлена в этом импорте. */
    private suspend fun restoreActiveIfRestored(
        category: ModelCategory,
        modelId: String?,
        restoredIds: Set<String>
    ) {
        modelId ?: return
        if (modelId !in restoredIds) {
            Timber.w(
                "Активная модель $category $modelId пропущена: не восстановлена в этом импорте"
            )
            return
        }
        preferences.setActiveModelId(category, modelId)
    }

    /**
     * Применить одну секцию поязыковых назначений (STT/TTS — общий цикл,
     * см. presence-семантику в [restoreSettings]): [label] — для логов,
     * [sentinel] — id, не требующий восстановленной модели (TTS «system»).
     */
    private suspend fun restoreLanguageAssignments(
        saved: Map<String, String?>?,
        label: String,
        sentinel: String?,
        restoredIds: Set<String>,
        setter: suspend (language: String, modelId: String?) -> Unit
    ) {
        saved?.let {
            assignmentOps(it).forEach { (lang, id) ->
                if (!isRestoredOrSentinel(id, restoredIds, sentinel)) {
                    Timber.w(
                        "Назначение $label $id языку $lang пропущено: модель не восстановлена в этом импорте"
                    )
                    return@forEach
                }
                setter(lang, id)
            }
        }
    }

    /** null-операция (снять) / sentinel, не требующий модели / восстановленная модель. */
    private fun isRestoredOrSentinel(id: String?, restoredIds: Set<String>, sentinel: String?): Boolean =
        id == null || id == sentinel || id in restoredIds

    /**
     * Найти распакованную запись модели во временном каталоге: сначала по
     * точному relativePath из манифеста (текущий формат уже содержит
     * "models/"), затем — с префиксом MODELS_PREFIX (обратная совместимость
     * с манифестами, где путь записан без префикса).
     * @return файл/каталог во временном каталоге или null, если записи нет.
     */
    private fun findExtractedEntry(tempDir: File, relativePath: String): File? {
        val direct = File(tempDir, relativePath)
        if (direct.exists()) return direct
        if (!relativePath.startsWith(MODELS_PREFIX)) {
            val legacy = File(tempDir, MODELS_PREFIX + relativePath)
            if (legacy.exists()) return legacy
        }
        return null
    }

    /**
     * Относительный путь модели в архиве.
     *
     * Ревью #1: корень моделей считается через единую точку
     * [ModelStorage.modelsRoot] (с фолбэком на filesDir), а не прямым
     * вызовом getExternalFilesDir — иначе при недоступном external storage
     * экспорт теряет подпуть, а импорт возвращает null для всех записей.
     * Issue #83, п.7: принадлежность корню проверяется ГРАНИЦЕЙ РАЗДЕЛИТЕЛЯ
     * ([isFileInsideDir], канонические пути) — раньше startsWith по
     * absolutePath без границы матчил соседний каталог /data/models_x как
     * внутренний /data/models, и модель получала изуродованный путь вида
     * "_x/файл". Файл вне корня — путь по имени, чтобы путь не менялся
     * после импорта.
     */
    private fun toRelativePath(model: InstalledModel): String {
        val path = model.filePath ?: return ""
        val file = File(path)
        val modelsRoot = ModelStorage.modelsRoot(context)
        val inside = try {
            isFileInsideDir(modelsRoot, file)
        } catch (e: Exception) {
            Timber.w(e, "Не удалось канонализировать путь модели: $path")
            false
        }
        return if (inside) {
            MODELS_PREFIX + file.canonicalPath
                .removePrefix(modelsRoot.canonicalPath)
                .removePrefix(File.separator)
        } else {
            // Используем имя файла (с расширением), чтобы путь не менялся после импорта.
            MODELS_PREFIX + file.name
        }
    }

    /**
     * Целевой путь модели при импорте.
     *
     * Ревью #1: единая точка расчёта корня [ModelStorage.modelsRoot] вместо
     * прямого getExternalFilesDir — при недоступном external storage
     * фолбэк на filesDir, импорт не возвращает null для всех записей.
     * @return абсолютный путь внутри каталога моделей или null,
     *   если путь из манифеста выходит за его пределы (zip-slip).
     */
    private fun resolveTargetPath(entry: BackupModelEntry): String? {
        val modelsRoot = ModelStorage.modelsRoot(context)
        val relative = entry.relativePath.removePrefix(MODELS_PREFIX)
        val target = File(modelsRoot, relative)
        return try {
            if (isFileInsideDir(modelsRoot, target)) target.absolutePath else null
        } catch (e: Exception) {
            Timber.w(e, "Не удалось канонализировать путь: ${target.absolutePath}")
            null
        }
    }

    /**
     * Переместить временный файл в целевой путь.
     *
     * Существующая цель удаляется только после успешного переноса нового
     * содержимого: между томами (cacheDir и getExternalFilesDir) rename
     * почти всегда падает, поэтому копируем в `<target>.tmp` рядом с целью,
     * затем удаляем старый файл и переименовываем tmp на его место. При
     * провале копирования tmp удаляется, старый файл остаётся нетронутым.
     */
    private fun moveToTarget(tempFile: File, targetPath: String): Boolean {
        return try {
            val targetFile = File(targetPath)
            targetFile.parentFile?.mkdirs()
            if (tempFile.renameTo(targetFile)) return true
            val staged = File(targetFile.parentFile, targetFile.name + ".tmp")
            staged.deleteRecursively()
            val copied = tempFile.copyRecursively(staged, overwrite = true)
            if (!copied) {
                staged.deleteRecursively()
                Timber.w("Не удалось скопировать ${tempFile.name} в ${staged.name} — оставляем прежний файл")
                return false
            }
            tempFile.deleteRecursively()
            if (targetFile.exists() && !targetFile.deleteRecursively()) {
                Timber.w("Не удалось удалить прежний $targetPath перед подменой")
                staged.deleteRecursively()
                return false
            }
            if (staged.renameTo(targetFile)) return true
            // rename по каким-то причинам не прошёл (тот же том — редкость):
            // копируем staging в цель напрямую и подчищаем staging.
            val moved = staged.copyRecursively(targetFile, overwrite = true)
            staged.deleteRecursively()
            moved
        } catch (e: Exception) {
            Timber.e(e, "Ошибка перемещения файла в $targetPath")
            false
        }
    }

    /**
     * Записать файл в ZIP.
     */
    private fun writeFileToZip(zip: ZipOutputStream, file: File, entryName: String) {
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().buffered().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    /**
     * Записать каталог в ZIP рекурсивно.
     */
    private fun writeDirectoryToZip(zip: ZipOutputStream, dir: File, prefix: String) {
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(dir).path
            writeFileToZip(zip, file, "$prefix/$relative")
        }
    }

    /**
     * Прочитать содержимое записи ZIP целиком (UTF-8).
     */
    private fun readEntry(zip: ZipInputStream): String =
        String(zip.readBytes(), Charsets.UTF_8)
}