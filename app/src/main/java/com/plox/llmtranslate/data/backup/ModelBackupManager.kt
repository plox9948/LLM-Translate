package com.plox.llmtranslate.data.backup

import android.content.Context
import android.net.Uri
import com.plox.llmtranslate.data.model.ModelRepository
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

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

        try {
            val input = context.contentResolver.openInputStream(sourceUri)
                ?: return@withContext ImportResult(0, 0, false, "Не удалось открыть архив")

            var manifest: BackupManifest? = null
            var settings: BackupSettings? = null
            val tempDir = File(context.cacheDir, "backup_import").apply { mkdirs() }

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
                                val canonicalTemp = tempDir.canonicalPath
                                val canonicalOut = outFile.canonicalPath
                                if (!canonicalOut.startsWith(canonicalTemp + File.separator)) {
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
            manifestData.models.forEach { entry ->
                // Путь из манифеста может выйти за пределы каталога моделей.
                val targetPath = resolveTargetPath(entry)
                if (targetPath == null) {
                    Timber.w("Некорректный путь модели в архиве: ${entry.relativePath}")
                    skipped++
                    return@forEach
                }
                val tempPath = MODELS_PREFIX + entry.relativePath
                val tempFile = File(tempDir, tempPath)

                if (!tempFile.exists()) {
                    Timber.w("Файл модели не найден в архиве: ${entry.relativePath}")
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

                // Переименовываем временный файл в целевой.
                if (moveToTarget(tempFile, targetPath)) {
                    repository.upsert(model)
                    imported++
                } else {
                    skipped++
                }
            }

            // Восстанавливаем настройки.
            val settingsData = settings
            if (settingsData != null) {
                restoreSettings(settingsData)
                settingsRestored = true
            }

            ImportResult(imported, skipped, settingsRestored)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка импорта бэкапа")
            ImportResult(imported, skipped, settingsRestored, e.message ?: "Ошибка импорта")
        } finally {
            // Временный каталог удаляется в любом случае.
            File(context.cacheDir, "backup_import").deleteRecursively()
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
     * Построить настройки.
     */
    private suspend fun buildSettings(): BackupSettings {
        return BackupSettings(
            activeTranslation = preferences.getActiveModelId(ModelCategory.TRANSLATION),
            activeStt = preferences.getActiveModelId(ModelCategory.STT),
            activeTts = preferences.getActiveModelId(ModelCategory.TTS),
            ttsEngine = preferences.ttsEngine.first(),
            uiLanguage = preferences.uiLanguage.first(),
            theme = preferences.theme.first()
        )
    }

    /**
     * Восстановить настройки.
     */
    private suspend fun restoreSettings(settings: BackupSettings) {
        settings.activeTranslation?.let {
            preferences.setActiveModelId(ModelCategory.TRANSLATION, it)
        }
        settings.activeStt?.let {
            preferences.setActiveModelId(ModelCategory.STT, it)
        }
        settings.activeTts?.let {
            preferences.setActiveModelId(ModelCategory.TTS, it)
        }
        preferences.setTtsEngine(settings.ttsEngine)
        preferences.setUiLanguage(settings.uiLanguage)
        preferences.setTheme(settings.theme)
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
            val rootCanonical = modelsRoot.canonicalPath
            val targetCanonical = target.canonicalPath
            if (targetCanonical == rootCanonical ||
                targetCanonical.startsWith(rootCanonical + File.separator)
            ) {
                target.absolutePath
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.w(e, "Не удалось канонализировать путь: ${target.absolutePath}")
            null
        }
    }

    /**
     * Переместить временный файл в целевой путь.
     */
    private fun moveToTarget(tempFile: File, targetPath: String): Boolean {
        return try {
            val targetFile = File(targetPath)
            targetFile.parentFile?.mkdirs()
            if (targetFile.exists()) targetFile.delete()
            if (tempFile.renameTo(targetFile)) return true
            // rename не работает между томами (cacheDir и getExternalFilesDir):
            // копируем и удаляем временный файл.
            val copied = tempFile.copyRecursively(targetFile, overwrite = true)
            tempFile.deleteRecursively()
            copied
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


