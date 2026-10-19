package com.plox.llmtranslate.data.backup

import android.content.Context
import android.net.Uri
import com.plox.llmtranslate.data.model.ModelRepository
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.data.storage.ModelStorage
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import com.plox.llmtranslate.domain.model.TtsModelSelector
import com.plox.llmtranslate.domain.runtime.RuntimeRouter
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
 * Чистый маппинг восстановительных операций поязыкового назначения (ревью #53):
 * [saved] == null (ключа нет в старом архиве) — операций нет, настройку не
 * трогаем; иначе — по каждому языку union(текущие, сохранённые) установка
 * сохранённого id либо null (снять). Пустая [saved] снимает все текущие
 * назначения. Вынесено для unit-теста без Android-специфики.
 */
internal fun assignmentOps(
    current: Map<String, String>,
    saved: Map<String, String>?
): List<Pair<String, String?>> {
    if (saved == null) return emptyList()
    return (current.keys + saved.keys).map { lang -> lang to saved[lang] }
}

/**
 * Сверка контрольной суммы распакованного файла модели с хэшем манифеста
 * (ревью раунд 9). ok=true когда сверка невозможна корректно: [BackupModelEntry.sha256]
 * пуст (импортированная модель без хэша) либо модель — каталог (хэш каталожных
 * установок относится к скачанному архиву/пачке URL, а не к отдельным файлам
 * содержимого). Для одиночного файла с непустым хэшем — строгое сравнение
 * SHA-256 байт файла с hex-хэшем манифеста (регистронезависимо).
 */
internal fun backupShaOk(entry: BackupModelEntry, file: File): Boolean {
    val expected = entry.sha256?.takeIf { it.isNotBlank() } ?: return true
    if (file.isDirectory) return true
    val actual = runCatching { sha256Hex(file) }.getOrNull() ?: return false
    return actual.equals(expected, ignoreCase = true)
}

/** SHA-256 файла в hex (нижний регистр). */
private fun sha256Hex(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
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
     * @return true при успехе.
     */
    suspend fun exportToUri(targetUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val models = repository.observeAll().first().filter { it.isReady }
            if (models.isEmpty()) {
                Timber.w("Нет моделей для экспорта")
                return@withContext false
            }

            val output = context.contentResolver.openOutputStream(targetUri)
                ?: return@withContext false

            output.use { out ->
                ZipOutputStream(out.buffered()).use { zip ->
                    // 1. Манифест.
                    val manifest = buildManifest(models)
                    zip.putNextEntry(ZipEntry(MANIFEST_NAME))
                    zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
                    zip.closeEntry()

                    // 2. Настройки.
                    val settings = buildSettings()
                    zip.putNextEntry(ZipEntry(SETTINGS_NAME))
                    zip.write(json.encodeToString(BackupSettings.serializer(), settings).toByteArray())
                    zip.closeEntry()

                    // 3. Файлы моделей.
                    models.forEach { model ->
                        val file = model.filePath?.let(::File) ?: return@forEach
                        if (!file.exists()) return@forEach
                        val relativePath = toRelativePath(model)
                        if (file.isDirectory) {
                            writeDirectoryToZip(zip, file, relativePath)
                        } else {
                            writeFileToZip(zip, file, relativePath)
                        }
                    }
                }
            }
            Timber.d("Экспортировано ${models.size} моделей")
            true
        } catch (e: Exception) {
            Timber.e(e, "Ошибка экспорта бэкапа")
            false
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
                                if (!isInsideDir(tempDir, outFile)) {
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

                // Контрольная сумма из манифеста (ревью раунд 9): битый файл
                // модели в архиве не должен регистрироваться READY. Каталоги
                // не проверяем — [BackupModelEntry.sha256] у archive/мультифайловых
                // установок это хэш скачанного файла (архива), а не распакованного
                // содержимого; одиночные файлы без хэша (импортированные модели)
                // сверить нечем.
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
     */
    private fun buildManifest(models: List<InstalledModel>): BackupManifest {
        val entries = models.map { model ->
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
                relativePath = toRelativePath(model),
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
     * Восстановить настройки. Presence-семантика (ревью #53): ключ в JSON =
     * установить (пустая карта = снять все назначения), отсутствие ключа
     * (null из старого архива) = не трогать.
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
        // Поязыковые назначения: поверх union(текущие, сохранённые) языков —
        // сохранённое значение либо null (снять); так пустая сохранённая карта
        // снимает все текущие назначения. Id не восстановленных в этом импорте
        // моделей пропускаем (сентинель «system» назначается всегда).
        settings.sttByLanguage?.let { saved ->
            assignmentOps(preferences.sttByLanguage.first(), saved).forEach { (lang, id) ->
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
            assignmentOps(preferences.ttsByLanguage.first(), saved).forEach { (lang, id) ->
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
     */
    private fun toRelativePath(model: InstalledModel): String {
        val path = model.filePath ?: return ""
        val file = File(path)
        val modelsRoot = context.getExternalFilesDir("models")
        return if (modelsRoot != null && file.absolutePath.startsWith(modelsRoot.absolutePath)) {
            MODELS_PREFIX + file.absolutePath.removePrefix(modelsRoot.absolutePath).removePrefix(File.separator)
        } else {
            // Используем имя файла (с расширением), чтобы путь не менялся после импорта.
            MODELS_PREFIX + file.name
        }
    }

    /**
     * Целевой путь модели при импорте.
     * @return абсолютный путь внутри каталога моделей или null,
     *   если путь из манифеста выходит за его пределы (zip-slip).
     */
    private fun resolveTargetPath(entry: BackupModelEntry): String? {
        val modelsRoot = context.getExternalFilesDir("models") ?: return null
        val relative = entry.relativePath.removePrefix(MODELS_PREFIX)
        val target = File(modelsRoot, relative)
        return try {
            if (isInsideDir(modelsRoot, target)) target.absolutePath else null
        } catch (e: Exception) {
            Timber.w(e, "Не удалось канонализировать путь: ${target.absolutePath}")
            null
        }
    }

    /**
     * Защита от zip-slip: файл лежит внутри каталога [dir]
     * (сравнение по каноническим путям, сам каталог тоже считается «внутри»).
     */
    private fun isInsideDir(dir: File, file: File): Boolean {
        val dirCanonical = dir.canonicalPath
        val fileCanonical = file.canonicalPath
        return fileCanonical == dirCanonical ||
            fileCanonical.startsWith(dirCanonical + File.separator)
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


