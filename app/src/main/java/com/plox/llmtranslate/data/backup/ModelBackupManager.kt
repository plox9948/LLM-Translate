package com.plox.llmtranslate.data.backup

import android.content.Context
import android.net.Uri
import com.plox.llmtranslate.data.download.DownloadLayout
import com.plox.llmtranslate.data.model.ModelRepository
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.data.storage.ModelStorage
import com.plox.llmtranslate.domain.model.ArchiveFormats
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.UUID
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
        val candidates = buildList {
            add(DownloadLayout.targetFileName(entry.id, url, index, inModelDir = false))
            if (fileName !in this) add(fileName)
            val raw = url.substringAfterLast('/')
            if (raw.isNotBlank() && raw !in this) add(raw)
        }
        val installed = candidates.asSequence()
            .map { name -> File(dir, name) }
            .firstOrNull { it.isFile }
        val actual = installed?.let { f -> runCatching { sha256Hex(f) }.getOrNull() }
        if (actual == null || !actual.equals(expected, ignoreCase = true)) return false
    }
    return true
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
     * Ревью #4: архив сначала собирается целиком во временный файл в
     * cacheDir, и только готовый копируется в выходной Uri — обрыв SAF-
     * потока или ENOSPC при записи ZIP не оставляет пользователю битый
     * архив (неполный документ). Временный файл удаляется в любом случае.
     * @return true при успехе.
     */
    suspend fun exportToUri(targetUri: Uri): Boolean = withContext(Dispatchers.IO) {
        // Уникальный суффикс: параллельные экспорты не затирают общий temp.
        val tempZip = File(context.cacheDir, "backup_export_${UUID.randomUUID()}.zip")
        try {
            val models = repository.observeAll().first().filter { it.isReady }
            if (models.isEmpty()) {
                Timber.w("Нет моделей для экспорта")
                return@withContext false
            }

            val manifest = buildManifest(models)

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

                    // 3. Файлы моделей — по относительным путям из манифеста
                    // (после дедуппликации путей, ревью #2) — иначе ZIP-запись
                    // и манифест могут разойтись.
                    manifest.models.forEachIndexed { index, entry ->
                        val file = models[index].filePath?.let(::File) ?: return@forEachIndexed
                        if (!file.exists()) return@forEachIndexed
                        if (file.isDirectory) {
                            writeDirectoryToZip(zip, file, entry.relativePath)
                        } else {
                            writeFileToZip(zip, file, entry.relativePath)
                        }
                    }
                }
            }

            val output = context.contentResolver.openOutputStream(targetUri)
                ?: return@withContext false
            output.use { out ->
                tempZip.inputStream().use { it.copyTo(out) }
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
     * Импортировать модели и настройки из ZIP по Uri.
     */
    suspend fun importFromUri(sourceUri: Uri): ImportResult = withContext(Dispatchers.IO) {
        var imported = 0
        var skipped = 0
        var settingsRestored = false
        // Уникальный суффикс temp-каталога: два параллельных импорта бэкапа
        // не должны делить один каталог (каждый чистит в finally свой).
        val tempDir = File(context.cacheDir, "backup_import_${UUID.randomUUID()}")

        try {
            val input = context.contentResolver.openInputStream(sourceUri)
                ?: return@withContext ImportResult(0, 0, false, "Не удалось открыть архив")

            var manifest: BackupManifest? = null
            var settings: BackupSettings? = null
            tempDir.mkdirs()

            input.use { ins ->
                ZipInputStream(ins.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (entry.isDirectory) {
                            zip.closeEntry()
                            entry = zip.nextEntry
                            continue
                        }

                        when {
                            name == MANIFEST_NAME -> {
                                val content = readEntry(zip)
                                manifest = json.decodeFromString(
                                    BackupManifest.serializer(), content
                                )
                            }
                            name == SETTINGS_NAME -> {
                                val content = readEntry(zip)
                                settings = json.decodeFromString(
                                    BackupSettings.serializer(), content
                                )
                            }
                            name.startsWith(MODELS_PREFIX) -> {
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
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }

            val manifestData = manifest
                ?: return@withContext ImportResult(0, 0, false, "Нет манифеста в архиве")

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

            ImportResult(imported, skipped, settingsRestored)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка импорта бэкапа")
            ImportResult(imported, skipped, settingsRestored, e.message ?: "Ошибка импорта")
        } finally {
            // Временный каталог удаляется в любом случае.
            tempDir.deleteRecursively()
        }
    }

    /**
     * Построить манифест из моделей.
     *
     * Ревью #2: фолбэк [toRelativePath] для файлов вне корня моделей теряет
     * структуру каталога — одноимённые файлы разных моделей (например
     * model.onnx у двух установок) получают одинаковый relativePath и
     * затирают друг друга в ZIP, а при импорте оба upsert резолвятся в
     * один файл. Коллизию разрешаем уникальным путём с id модели (id
     * уникален в БД), о чём пишем в лог.
     */
    private fun buildManifest(models: List<InstalledModel>): BackupManifest {
        val usedPaths = mutableSetOf<String>()
        val entries = models.map { model ->
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
            BackupModelEntry(
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
        }
        return BackupManifest(
            version = 1,
            exportedAt = System.currentTimeMillis(),
            models = entries
        )
    }

    /**
     * Построить настройки (ревью #53: полные поязыковые назначения, тайм-аут
     * выгрузки, удержание моделей при смене направления, последнее направление).
     */
    private suspend fun buildSettings(): BackupSettings {
        return BackupSettings(
            activeTranslation = preferences.getActiveModelId(ModelCategory.TRANSLATION),
            activeStt = preferences.getActiveModelId(ModelCategory.STT),
            activeTts = preferences.getActiveModelId(ModelCategory.TTS),
            ttsEngine = preferences.ttsEngine.first(),
            uiLanguage = preferences.uiLanguage.first(),
            theme = preferences.theme.first(),
            sttByLanguage = preferences.sttByLanguage.first(),
            ttsByLanguage = preferences.ttsByLanguage.first(),
            unloadTimeoutMinutes = preferences.unloadTimeoutMinutes.first(),
            modelDirectionRetention = preferences.modelDirectionRetention.first(),
            lastSourceLang = preferences.lastSourceLang.first(),
            lastTargetLang = preferences.lastTargetLang.first()
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
        settings.sttByLanguage?.let { saved ->
            assignmentOps(saved).forEach { (lang, id) ->
                if (!isRestoredOrSentinel(id, restoredIds, null)) {
                    Timber.w(
                        "Назначение STT $id языку $lang пропущено: модель не восстановлена в этом импорте"
                    )
                    return@forEach
                }
                preferences.setSttForLanguage(lang, id)
            }
        }
        settings.ttsByLanguage?.let { saved ->
            assignmentOps(saved).forEach { (lang, id) ->
                if (!isRestoredOrSentinel(id, restoredIds, TtsModelSelector.SYSTEM_SENTINEL)) {
                    Timber.w(
                        "Назначение TTS $id языку $lang пропущено: модель не восстановлена в этом импорте"
                    )
                    return@forEach
                }
                preferences.setTtsForLanguage(lang, id)
            }
        }
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
     */
    private fun toRelativePath(model: InstalledModel): String {
        val path = model.filePath ?: return ""
        val file = File(path)
        val modelsRoot = ModelStorage.modelsRoot(context)
        return if (file.absolutePath.startsWith(modelsRoot.absolutePath)) {
            MODELS_PREFIX + file.absolutePath.removePrefix(modelsRoot.absolutePath).removePrefix(File.separator)
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


