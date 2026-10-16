package com.plox.llmtranslate.domain

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.plox.llmtranslate.data.catalog.ModelCatalogProvider
import com.plox.llmtranslate.data.download.DownloadLayout
import com.plox.llmtranslate.data.download.ModelDownloadWorker
import com.plox.llmtranslate.data.model.ModelRepository
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.model.DeviceInfo
import com.plox.llmtranslate.domain.runtime.RuntimeRouter
import com.plox.llmtranslate.domain.model.InstallCheck
import com.plox.llmtranslate.domain.model.InstallCheckLogic
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelImportMeta
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import com.plox.llmtranslate.domain.runtime.LoadedModelInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
 * Реализация [ModelManager]: каталог, установка, выбор, память.
 */
@Singleton
class ModelManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalogProvider: ModelCatalogProvider,
    private val repository: ModelRepository,
    private val preferences: ModelPreferences,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val runtimeRouter: RuntimeRouter,
    private val json: Json,
    private val scope: CoroutineScope
) : ModelManager {

    private companion object {
        /** Имя записи с метаданными в ZIP-экспорте мультифайловой модели. */
        const val META_ENTRY_NAME = "model_meta.json"

        /** Префикс файлов одиночного импорта в корне каталога моделей. */
        const val IMPORT_FILE_PREFIX = "imported_"
    }

    private val modelsDir: File
        get() = context.getExternalFilesDir("models") ?: context.filesDir

    override val catalog: Flow<List<ModelCard>> = catalogProvider.catalog

    override fun installedModels(category: ModelCategory?): Flow<List<InstalledModel>> =
        if (category == null) {
            repository.observeAll()
        } else {
            repository.observeByCategory(category)
        }

    override fun activeModelId(category: ModelCategory): Flow<String?> =
        preferences.activeModelId(category)

    override fun activeModel(category: ModelCategory): Flow<InstalledModel?> =
        combine(activeModelId(category), repository.observeAll()) { id, models ->
            models.firstOrNull { it.id == id && it.isReady }
        }

    override val sttByLanguage: Flow<Map<String, String>> = preferences.sttByLanguage

    override suspend fun setSttForLanguage(language: String, modelId: String?) =
        withContext(Dispatchers.IO) {
            if (modelId != null) {
                val model = repository.getById(modelId)
                if (model == null || model.category != ModelCategory.STT || !model.isReady) {
                    Timber.w(
                        "Назначение STT языку $language пропущено: модель $modelId недоступна"
                    )
                    return@withContext
                }
            }
            preferences.setSttForLanguage(language, modelId)
        }

    override val loadedModels: Flow<List<LoadedModelInfo>> = runtimeRouter.loadedModels

    override val diskUsage: Flow<DiskUsage> = repository.observeAll().map { _ ->
        withContext(Dispatchers.IO) {
            DiskUsage(
                totalBytes = modelsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                modelsDir = modelsDir.absolutePath
            )
        }
    }

    override suspend fun setActive(category: ModelCategory, modelId: String): Boolean =
        withContext(Dispatchers.IO) {
            val model = repository.getById(modelId)
            if (model == null || model.category != category) {
                Timber.w("Модель $modelId не найдена или не из категории $category")
                return@withContext false
            }
            if (!model.isReady) {
                Timber.w("Модель $modelId не готова к активации")
                return@withContext false
            }
            preferences.setActiveModelId(category, modelId)
            true
        }

    override fun checkInstall(card: ModelCard, device: DeviceInfo): InstallCheck =
        InstallCheckLogic.evaluate(card, device)

    override fun startDownload(card: ModelCard) {
        val model = InstalledModel(
            id = card.id,
            category = card.category,
            displayName = card.displayName,
            description = card.description,
            runtime = card.runtime,
            sizeBytes = card.sizeBytes,
            ramRequiredMb = card.ramRequiredMb,
            languages = card.languages,
            quality = card.quality,
            speed = card.speed,
            url = card.url,
            urls = card.urls,
            sha256 = card.sha256,
            sha256s = card.sha256s,
            license = card.license,
            minAndroidSdk = card.minAndroidSdk,
            recommendedRamMb = card.recommendedRamMb,
            status = ModelStatus.DOWNLOADING,
            progress = 0,
            filePath = null,
            bytesDone = 0,
            isBuiltin = true
        )
        scope.launch {
            try {
                repository.upsert(model)
                ModelDownloadWorker.schedule(context, model)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Не удалось поставить на скачивание модель ${card.id}")
            }
        }
    }

    override fun cancelDownload(modelId: String) {
        ModelDownloadWorker.cancel(context, modelId)
        scope.launch {
            try {
                val model = repository.getById(modelId) ?: return@launch
                if (model.status != ModelStatus.READY) {
                    // Незавершённую запись удаляем, чтобы UI снова показал «Скачать».
                    // При явной отмене пользователем удаляем и осиротевшие
                    // .part-файлы: частичная загрузка этой модели не нужна,
                    // при повторном скачивании начнётся заново.
                    deleteOrphanParts(model)
                    repository.deleteById(modelId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Не удалось отменить скачивание модели $modelId")
            }
        }
    }

    /**
     * Удалить осиротевшие файлы незавершённой установки модели (issue #2, #40).
     * Вызывается только после [ModelDownloadWorker.cancel] (явная отмена в UI
     * или удаление записи), поэтому живой WorkManager-загрузки по этому id нет
     * и все файлы по URL записи — остатки её же прерванной установки: и
     * недокачанные `.part` с метаданными докачки (конвенция BaseDownloader:
     * рядом с целевым файлом `<имя>.part` и `<имя>.part.meta`), и уже
     * переименованные целевые файлы, и остаток архива в корне каталога
     * рантайма (ArchiveInstaller распаковывает в каталог модели). Пока
     * загрузка жива (сетевые повторы `Result.retry()`), метод не вызывается —
     * докачка по `.part`-файлам сохраняется.
     *
     * Размещение файлов задаёт общий с инсталляторами [DownloadLayout]
     * (см. [ModelDownloadWorker]): мультифайловые пачки, одиночные файлы
     * sherpa и распакованное содержимое архивов — в каталог модели
     * `models/<RUNTIME>/<id>`; каталог принадлежит только этой записи
     * (ключ — id), поэтому сносится целиком — иначе переименованные
     * целевые файлы прерванной мультифайловой установки остаются навсегда.
     * Одиночные ONNX-файлы и архивы — в корень каталога рантайма.
     * Модель не READY (проверяется вызывающим кодом), поэтому файлы по её URL
     * относятся только к её незавершённой загрузке — файлы READY-моделей
     * не затрагиваются.
     */
    private fun deleteOrphanParts(model: InstalledModel) {
        val urls = model.downloadUrls()
        if (urls.isEmpty()) return
        val runtimeDir = File(modelsDir, model.runtime.name)
        val modelDir = DownloadLayout.modelDir(runtimeDir, model.id)

        var usesModelDir = false
        urls.forEachIndexed { index, url ->
            val fileName = url.substringAfterLast('/').ifBlank { "file_$index" }
            val dir = DownloadLayout.fileDir(runtimeDir, model.runtime, model.id, urls, fileName)
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
        }
        // Каталог осиротевшей модели: переименованные целевые файлы
        // мультифайловой установки, её .part/.part.meta и распакованное
        // содержимое архива.
        if (usesModelDir && modelDir.isDirectory && !modelDir.deleteRecursively()) {
            Timber.w("Не удалось удалить каталог осиротевшей модели ${modelDir.name}")
        }
    }

    override suspend fun importFromUri(uri: Uri): String? =
        withContext(Dispatchers.IO) {
            try {
                val fileName = queryFileName(uri) ?: return@withContext null

                // ZIP (в т.ч. экспорт мультифайловой модели этой же программой):
                // распаковываем в каталог модели, id и структуру берём из
                // вложенной метадати model_meta.json (см. exportToUri).
                if (looksLikeZip(uri)) {
                    return@withContext importZip(uri, fileName)
                }

                val targetFile = copyFromUri(uri) ?: return@withContext null

                val runtime = guessRuntime(fileName)
                if (runtime == null) {
                    Timber.w("Неизвестный формат файла модели $fileName — импорт отклонён")
                    targetFile.delete()
                    return@withContext null
                }

                val modelId = fileName.removeSuffix(".gguf").removeSuffix(".onnx")
                // Старая запись под этим id (до upsert) — нужна для удаления
                // осиротевшего файла предыдущего импорта (issue #40).
                val previousRecord = repository.getById(modelId)
                val model = InstalledModel(
                    id = modelId,
                    category = guessCategory(fileName),
                    displayName = fileName,
                    description = "Импортированная модель",
                    runtime = runtime,
                    sizeBytes = targetFile.length(),
                    ramRequiredMb = 0,
                    languages = emptyList(),
                    quality = 3,
                    speed = 3,
                    url = "",
                    urls = emptyList(),
                    sha256 = null,
                    license = "Неизвестна",
                    minAndroidSdk = 26,
                    recommendedRamMb = 0,
                    status = ModelStatus.READY,
                    progress = 100,
                    filePath = targetFile.absolutePath,
                    bytesDone = targetFile.length(),
                    isBuiltin = false
                )
                repository.upsert(model)
                // Повторный импорт той же модели: copyFromUri каждый раз пишет
                // новый imported_* файл, старый осиротел (issue #40).
                deleteSupersededImportFile(previousRecord, targetFile)
                Timber.d("Импортирована модель $modelId из $uri")
                modelId
            } catch (e: Exception) {
                Timber.e(e, "Ошибка импорта модели из $uri")
                null
            }
        }

    override suspend fun exportToUri(modelId: String, targetUri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            val model = repository.getById(modelId) ?: return@withContext false
            val source = model.filePath?.let(::File) ?: return@withContext false
            if (!source.exists()) {
                Timber.w("Файл модели $modelId не найден: ${source.path}")
                return@withContext false
            }
            try {
                val output = context.contentResolver.openOutputStream(targetUri)
                    ?: return@withContext false
                output.use { out ->
                    if (source.isDirectory) {
                        // Каталог модели упаковываем в zip потоково.
                        // Первой записью — метаданные: повторный импорт этого
                        // zip регистрирует модель под её каталожным id.
                        ZipOutputStream(out.buffered()).use { zip ->
                            zip.putNextEntry(ZipEntry(META_ENTRY_NAME))
                            zip.write(
                                json.encodeToString(
                                    ModelImportMeta.serializer(), importMetaOf(model)
                                ).toByteArray()
                            )
                            zip.closeEntry()
                            source.walkTopDown().filter { it.isFile }.forEach { f ->
                                zip.putNextEntry(ZipEntry(f.relativeTo(source).path))
                                f.inputStream().buffered().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    } else {
                        source.inputStream().buffered().use { it.copyTo(out) }
                    }
                }
                Timber.d("Модель $modelId экспортирована")
                true
            } catch (e: Exception) {
                Timber.e(e, "Ошибка экспорта модели $modelId")
                false
            }
        }

    override fun delete(modelId: String) {
        // Отменяем возможную загрузку, чтобы воркер не воскресил удалённую запись.
        ModelDownloadWorker.cancel(context, modelId)
        scope.launch {
            try {
                val model = repository.getById(modelId) ?: return@launch
                val path = model.filePath
                if (!path.isNullOrBlank()) {
                    val file = File(path)
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                } else {
                    // У записи нет пути при прерванной установке — чистим
                    // известные места раскладки DownloadLayout (issue #40:
                    // иначе переименованные целевые файлы установки, снятой
                    // до finalizeDownload, остаются на диске навсегда).
                    deleteOrphanParts(model)
                }
                repository.deleteById(modelId)
                // Кэш движков по id модели (issue #40): без eviction удалённая
                // модель остаётся «загруженной» в loadedModels, а повторная
                // загрузка того же id берёт из LRU-кэша старый нативный handle
                // по уже удалённым файлам. Движок под своим мьютексом ждёт
                // окончания активного инференса — use-after-free исключён.
                runtimeRouter.releaseModel(model)
                // Если удалена активная модель — переключаемся на первую доступную.
                if (preferences.getActiveModelId(model.category) == modelId) {
                    val fallback = repository.getFirstReady(model.category)
                    if (fallback != null) {
                        Timber.d("Переключаемся на ${fallback.id} (категория ${model.category})")
                    } else {
                        Timber.d("Нет доступных моделей категории ${model.category}")
                    }
                    preferences.setActiveModelId(model.category, fallback?.id)
                }
                Timber.d("Модель $modelId удалена")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Не удалось удалить модель $modelId")
            }
        }
    }

    override fun releaseAllRuntimes() {
        // release() внутри каждого движка ждёт окончания активного инференса
        // (десятки секунд), поэтому уходим с главного потока в app-scope.
        scope.launch(Dispatchers.IO) {
            try {
                runtimeRouter.releaseAll()
                Timber.d("Рантаймы разгружены (ON_STOP)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при разгрузке рантаймов")
            }
        }
    }

    private suspend fun copyFromUri(uri: Uri): File? {
        val targetFile = File(modelsDir, "$IMPORT_FILE_PREFIX${System.currentTimeMillis()}")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null
        } catch (e: Exception) {
            // Обрыв потокопередачи SAF: частичный imported_* не должен
            // оставаться на диске без регистрации в БД.
            if (!targetFile.delete()) {
                Timber.w("Не удалось удалить частичный файл импорта: ${targetFile.name}")
            }
            throw e
        }
        return targetFile
    }

    /**
     * Удалить файл предыдущего импорта, вытеснённый повторным импортом той же
     * модели (issue #40): copyFromUri создаёт каждый раз новый путь
     * `imported_<ts>`, и без очистки старый файл остаётся мусором без записи
     * в БД. Удаляем только при отличии путей и только файлы зоны импорта
     * (`imported_*` в корне каталога моделей) — файлы каталожных моделей по
     * путям рантаймов не затрагиваются.
     */
    private fun deleteSupersededImportFile(previous: InstalledModel?, newFile: File) {
        val oldPath = previous?.filePath ?: return
        if (oldPath == newFile.absolutePath) return
        val oldFile = File(oldPath)
        if (oldFile.parentFile?.absolutePath != modelsDir.absolutePath) return
        if (!oldFile.name.startsWith(IMPORT_FILE_PREFIX) || !oldFile.isFile) return
        if (!oldFile.delete()) {
            Timber.w("Не удалось удалить осиротевший файл импорта: $oldPath")
        }
    }

    /** Проверка сигнатуры ZIP (PK\x03\x04) по первым байтам документа SAF. */
    private fun looksLikeZip(uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val magic = ByteArray(4)
            var read = 0
            while (read < 4) {
                val n = input.read(magic, read, 4 - read)
                if (n < 0) break
                read += n
            }
            read == 4 &&
                magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
                magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()
        } ?: false
    } catch (e: Exception) {
        Timber.w(e, "Не удалось прочитать сигнатуру $uri")
        false
    }

    /**
     * Импорт ZIP-экспорта: распаковка в каталог модели
     * `models/<RUNTIME>/<id>` и регистрация записи. Если внутри есть
     * запись [META_ENTRY_NAME] (ZIP сделан экспортом этой программы),
     * id, категория и поля модели берутся из неё — такая модель
     * совпадает с карточкой каталога. Иначе параметры угадываются по имени.
     */
    private suspend fun importZip(uri: Uri, sourceName: String): String? {
        val tempDir = File(context.cacheDir, "model_import_${System.currentTimeMillis()}")
        try {
            tempDir.mkdirs()
            var meta: ModelImportMeta? = null
            var fileCount = 0
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (entry.isDirectory) {
                            zip.closeEntry()
                            entry = zip.nextEntry
                            continue
                        }
                        if (name == META_ENTRY_NAME) {
                            val content = String(zip.readBytes(), Charsets.UTF_8)
                            meta = try {
                                json.decodeFromString(ModelImportMeta.serializer(), content)
                            } catch (e: Exception) {
                                Timber.w(e, "Некорректная метаданная в ZIP $sourceName")
                                null
                            }
                        } else {
                            val outFile = File(tempDir, name)
                            // Защита от zip-slip: файл не должен выходить за пределы временного каталога.
                            val canonicalTemp = tempDir.canonicalPath
                            if (!outFile.canonicalPath.startsWith(canonicalTemp + File.separator)) {
                                Timber.w("ZIP-запись вне каталога, пропущена: $name")
                            } else {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { zip.copyTo(it) }
                                fileCount++
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            if (fileCount == 0) {
                Timber.w("ZIP не содержит файлов модели: $sourceName")
                return null
            }

            val model = meta?.let { modelFromMeta(it, tempDir) }
                ?: modelFromZipName(sourceName, tempDir)
                ?: return null

            repository.upsert(model)
            Timber.d("Импортирована модель ${model.id} (ZIP $sourceName)")
            return model.id
        } catch (e: Exception) {
            Timber.e(e, "Ошибка импорта ZIP-модели из $uri")
            return null
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Каталог модели на диске для мультифайловой установки:
     * `models/<RUNTIME>/<id>` — та же раскладка, что у загрузчика.
     */
    private fun modelTargetDir(runtime: RuntimeType, modelId: String): File =
        File(File(modelsDir, runtime.name), modelId.replace('/', '_'))

    /**
     * Распакованный ZIP → модель из вложенных метаданных (id из каталога).
     * @return null, если category/runtime из метаданных не распознаются.
     */
    private fun modelFromMeta(meta: ModelImportMeta, extracted: File): InstalledModel? {
        val runtime = try {
            RuntimeType.valueOf(meta.runtime)
        } catch (e: IllegalArgumentException) {
            Timber.w("Неизвестный runtime в метаданных ZIP: ${meta.runtime}")
            return null
        }
        val category = try {
            ModelCategory.valueOf(meta.category)
        } catch (e: IllegalArgumentException) {
            Timber.w("Неизвестная категория в метаданных ZIP: ${meta.category}")
            return null
        }
        val targetDir = modelTargetDir(runtime, meta.id)
        if (!extracted.copyRecursively(targetDir, overwrite = true)) {
            Timber.w("Не удалось распаковать модель ${meta.id} в ${targetDir.path}")
            // Полу-скопированный каталог — мусор; извлечённый temp чистится
            // в finally importZip.
            targetDir.deleteRecursively()
            return null
        }
        return InstalledModel(
            id = meta.id,
            category = category,
            displayName = meta.displayName,
            description = meta.description,
            runtime = runtime,
            sizeBytes = targetDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            ramRequiredMb = meta.ramRequiredMb,
            languages = meta.languages,
            quality = meta.quality,
            speed = meta.speed,
            url = meta.url,
            urls = meta.urls,
            sha256 = null,
            sha256s = meta.sha256s,
            license = meta.license,
            minAndroidSdk = meta.minAndroidSdk,
            recommendedRamMb = meta.recommendedRamMb,
            status = ModelStatus.READY,
            progress = 100,
            filePath = targetDir.absolutePath,
            bytesDone = targetDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            isBuiltin = meta.isBuiltin
        )
    }

    /**
     * Распакованный ZIP без метаданных → модель с параметрами,
     * угаданными по имени файла.
     */
    private fun modelFromZipName(sourceName: String, extracted: File): InstalledModel? {
        val modelId = sourceName
            .removeSuffix(".zip")
            .removeSuffix(".gguf")
            .removeSuffix(".onnx")
        if (modelId.isBlank()) return null
        val runtime = guessRuntime(sourceName)
            ?: run {
                Timber.w("Неизвестный формат ZIP-модели $sourceName — импорт отклонён")
                return null
            }
        val targetDir = modelTargetDir(runtime, modelId)
        if (!extracted.copyRecursively(targetDir, overwrite = true)) {
            Timber.w("Не удалось распаковать модель $modelId в ${targetDir.path}")
            // Полу-скопированный каталог — мусор; извлечённый temp чистится
            // в finally importZip.
            targetDir.deleteRecursively()
            return null
        }
        val size = targetDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return InstalledModel(
            id = modelId,
            category = guessCategory(sourceName),
            displayName = sourceName,
            description = "Импортированная модель",
            runtime = runtime,
            sizeBytes = size,
            ramRequiredMb = 0,
            languages = emptyList(),
            quality = 3,
            speed = 3,
            url = "",
            urls = emptyList(),
            sha256 = null,
            license = "Неизвестна",
            minAndroidSdk = 26,
            recommendedRamMb = 0,
            status = ModelStatus.READY,
            progress = 100,
            filePath = targetDir.absolutePath,
            bytesDone = size,
            isBuiltin = false
        )
    }

    /** Метаданные экспортируемой модели для ZIP. */
    private fun importMetaOf(model: InstalledModel): ModelImportMeta = ModelImportMeta(
        id = model.id,
        category = model.category.name,
        runtime = model.runtime.name,
        displayName = model.displayName,
        description = model.description,
        languages = model.languages,
        sizeBytes = model.sizeBytes,
        ramRequiredMb = model.ramRequiredMb,
        quality = model.quality,
        speed = model.speed,
        url = model.url,
        urls = model.urls,
        sha256s = model.sha256s,
        license = model.license,
        minAndroidSdk = model.minAndroidSdk,
        recommendedRamMb = model.recommendedRamMb,
        isBuiltin = model.isBuiltin
    )

    private fun queryFileName(uri: Uri): String? {
        uri.getQueryParameter("name")?.let { return it }
        val last = uri.lastPathSegment
        if (!last.isNullOrBlank()) return Uri.decode(last)
        return null
    }

    /**
     * Признак архива: архивные расширения скачивает только sherpa-инсталлятор
     * (см. ArchiveInstaller), языковые паки перевода — это .bin/.spm файлы.
     */
    private fun isArchiveName(lower: String): Boolean =
        lower.endsWith(".zip") || lower.endsWith(".tar.bz2") || lower.endsWith(".tar.gz")

    private fun guessCategory(fileName: String): ModelCategory {
        val lower = fileName.lowercase()
        return when {
            lower.contains("whisper") || lower.contains("vosk") ||
                lower.contains("sherpa") || isArchiveName(lower) ->
                // sherpa-маркер и безмаркерный архив — STT-пачка (ArchiveInstaller).
                ModelCategory.STT
            else -> ModelCategory.TRANSLATION
        }
    }

    /**
     * Угадать рантайм по имени файла. null — формат не распознан: импорт
     * отклоняется вместо записи с битым умолчанием MARIAN_ONNX.
     */
    private fun guessRuntime(fileName: String): RuntimeType? {
        val lower = fileName.lowercase()
        return when {
            lower.contains("indictrans") -> RuntimeType.INDICTRANS_ONNX
            lower.contains("sherpa") || isArchiveName(lower) ->
                // Архивы (.zip/.tar.bz2/.tar.gz) без явных признаков ONNX/Marian
                // — пачки sherpa.
                RuntimeType.SHERPA
            lower.endsWith(".onnx") -> RuntimeType.MARIAN_ONNX // явный ONNX — Marian fallback
            else -> null
        }
    }

    override suspend fun deviceInfo(): DeviceInfo = deviceInfoProvider.get()
}

/**
 * Провайдер информации об устройстве (ОЗУ, диск, SDK).
 */
interface DeviceInfoProvider {

    /** Сбор данных блокирует (binder-вызовы AMS, StatFs) — реализация на IO. */
    suspend fun get(): DeviceInfo
}

/**
 * Реализация на ActivityManager + StatFs.
 */
@Singleton
class AndroidDeviceInfoProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : DeviceInfoProvider {

    override suspend fun get(): DeviceInfo = withContext(Dispatchers.IO) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val modelsDir = context.getExternalFilesDir("models")
            ?: context.filesDir
        val stat = StatFs(modelsDir.absolutePath)
        val freeBytes = stat.availableBlocksLong * stat.blockSizeLong

        DeviceInfo(
            sdkInt = android.os.Build.VERSION.SDK_INT,
            totalRamMb = memInfo.totalMem / 1024 / 1024,
            availableRamMb = memInfo.availMem / 1024 / 1024,
            freeDiskBytes = freeBytes
        )
    }
}
