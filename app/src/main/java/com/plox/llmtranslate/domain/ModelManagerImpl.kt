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
import com.plox.llmtranslate.domain.model.ModelFileNaming
import com.plox.llmtranslate.domain.model.ModelImportMeta
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import com.plox.llmtranslate.domain.model.TtsModelSelector
import com.plox.llmtranslate.domain.runtime.LoadedModelInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * Issue #75: сериализация импортов — promoteExtractedDir работает с общим
     * staging-каталогом `<id>.promoting`, и два параллельных импорта одной id
     * стартовой уборкой deleteRecursively рвали staging друг друга (в цель
     * попадала смесь файлов двух версий при READY-записи в БД). Взаимной
     * блокировки с мьютексами движков нет: importFromUri вызывается только из
     * UI, под engineMutex импорт не выполняется.
     * Issue #76: удаление (delete) сериализуется тем же мьютексом — гонка
     * import↔delete одной id давала висячий READY на удалённый каталог или
     * осиротевшие файлы без записи.
     */
    private val importMutex = Mutex()

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

    override val ttsByLanguage: Flow<Map<String, String>> = preferences.ttsByLanguage

    override suspend fun setTtsForLanguage(language: String, modelId: String?) =
        withContext(Dispatchers.IO) {
            // Сентинель «system» (issue #53) — явное назначение системного
            // TTS, карточка модели не требуется.
            if (modelId != null && modelId != TtsModelSelector.SYSTEM_SENTINEL) {
                // карточка существует (запись синхронизирована с каталогом),
                // категория TTS, рантайм скачиваемой Piper-модели, готова.
                val model = repository.getById(modelId)
                if (model == null ||
                    model.category != ModelCategory.TTS ||
                    model.runtime != RuntimeType.PIPER_ONNX ||
                    !model.isReady
                ) {
                    Timber.w(
                        "Назначение TTS языку $language пропущено: модель $modelId недоступна"
                    )
                    return@withContext
                }
            }
            preferences.setTtsForLanguage(language, modelId)
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
     * Одиночные ONNX-файлы и архивы — в корень каталога рантайма. Модель не
     * READY (проверяется вызывающим кодом), поэтому файлы по её URL относятся
     * только к её незавершённой загрузке — файлы READY-моделей не затрагиваются.
     * Узкая гонка (после cancel воркер дописал/переименовал файлы уже после
     * этой очистки) закрывается повторной очисткой на стороне воркера
     * (см. `ModelDownloadWorker.cleanupCancelledModel`).
     */
    private fun deleteOrphanParts(model: InstalledModel) {
        DownloadLayout.deleteOrphanedFiles(
            runtimeDir = File(modelsDir, model.runtime.name),
            runtime = model.runtime,
            modelId = model.id,
            urls = model.downloadUrls()
        )
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

                // Issue #75: исполняемое тело одиночного импорта под importMutex —
                // общая точка входа с импортом ZIP (общий staging по id, upsert
                // и чистка прежних файлов одной модели).
                importMutex.withLock {
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
                    val model = guessedImportedModel(
                        modelId = modelId,
                        sourceName = fileName,
                        runtime = runtime,
                        size = targetFile.length(),
                        filePath = targetFile.absolutePath
                    )
                    repository.upsert(model)
                    // Issue #68: повторный импорт подменяет бинарник той же id —
                    // движки обязаны забыть старый пучок (тот же eviction, что в
                    // delete()): иначе LRU-кэш по id молча продолжает инференс
                    // старым нативным handle, а удаление осиротевшего файла ниже
                    // снимает живой mmap. Порядок: сначала releaseModel (движок
                    // под своим мьютексом дожидается окончания активного инференса
                    // и отпускает старый файл), затем удаление файла.
                    previousRecord?.let { runtimeRouter.releaseModel(it) }
                    // Повторный импорт той же модели: copyFromUri каждый раз пишет
                    // новый imported_* файл, старый осиротел (issue #40); если же
                    // прежняя запись была каталожной моделью — осиротел её пучок
                    // рантайма (issue #69).
                    deleteSupersededImportFiles(previousRecord, targetFile)
                    Timber.d("Импортирована модель $modelId из $uri")
                    modelId
                }
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
                // Issue #76: тело удаления под importMutex (тот же мьютекс, что у
                // импортов) — иначе delete и импорт одной id гонят файловые операции
                // с upsert/deleteById: висячий READY на удалённый каталог либо
                // осиротевшие imported_* файлы без записи. Порядок локов тот же
                // (import/delete → engineMutex via releaseModel), реверса нет.
                importMutex.withLock {
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
                }
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
     * Удалить файлы старой записи, вытесненные повторным импортом (одиночным
     * файлом или ZIP — issue #72) той же модели. Одиночный импорт каждый раз
     * пишет новый путь `imported_<ts>`, и без
     * очистки старые файлы остаются мусором без записи в БД. Два случая:
     *  - прежний файл зоны импорта (`imported_*` в корне каталога моделей) —
     *    удаляется файл (issue #40);
     *  - прежняя запись была каталожной моделью: её filePath — каталог пучка
     *    рантайма внутри `models/<RUNTIME>/` — удаляется каталог целиком
     *    (issue #69: иначе после замещения одиночным файлом пучок рантайма
     *    остаётся осиротевшим на диске).
     * Удаляем только пути внутри каталога моделей, отличные от нового файла
     * (ZIP-промоут той же id в тот же путь — иначе удалили бы новые файлы),
     * и только после [runtimeRouter.releaseModel] вызывающего кода: движок
     * сначала отпускает старый файл.
     */
    private fun deleteSupersededImportFiles(previous: InstalledModel?, newFile: File) {
        val oldPath = previous?.filePath ?: return
        if (oldPath == newFile.absolutePath) return
        val oldFile = File(oldPath)
        val modelsRoot = modelsDir.absolutePath
        if (!oldFile.absolutePath.startsWith(modelsRoot + File.separator)) return
        if (oldFile.parentFile?.absolutePath == modelsRoot &&
            oldFile.name.startsWith(IMPORT_FILE_PREFIX) &&
            oldFile.isFile
        ) {
            if (!oldFile.delete()) {
                Timber.w("Не удалось удалить осиротевший файл импорта: $oldPath")
            }
            return
        }
        // Каталог пучка рантайма каталожной модели (models/<RUNTIME>/...);
        // одиночные imported_* файлы обработаны выше.
        if (oldFile.isDirectory) {
            if (!oldFile.deleteRecursively()) {
                Timber.w("Не удалось удалить осиротевший пучок модели: $oldPath")
            }
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
        // Issue #75: всё тело ZIP-импорта под importMutex — общий с одиночным
        // импортом (staging `<id>.promoting`, upsert и чистка прежних файлов
        // одной модели; параллельные импорты одной id иначе рвут staging).
        importMutex.withLock {
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

                // Issue #69: eviction кэша движков выполняется ВНУТРИ
                // promoteExtractedDir до перезаписи файлов каталога (см. его KDoc),
                // здесь остаётся только регистрация новой записи.
                val model = meta?.let { modelFromMeta(it, tempDir) }
                    ?: modelFromZipName(sourceName, tempDir)
                    ?: return null

                // Issue #72: прежняя запись той же id читается ДО upsert —
                // ZIP-импорт, замещающий одиночный импорт, осиротевший прежний
                // imported_* файл (или пучок по другому пути). Eviction движка
                // уже выполнен внутри promoteExtractedDir (releaseModel до
                // copyRecursively), здесь остаётся чистка прежнего пути; guard
                // oldPath == newPath внутри deleteSupersededImportFiles не даст
                // удалить только что промоутенный целевой каталог.
                val previousRecord = repository.getById(model.id)
                repository.upsert(model)
                model.filePath?.let { deleteSupersededImportFiles(previousRecord, File(it)) }
                Timber.d("Импортирована модель ${model.id} (ZIP $sourceName)")
                return model.id
            } catch (e: Exception) {
                Timber.e(e, "Ошибка импорта ZIP-модели из $uri")
                return null
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    /**
     * Каталог модели на диске для мультифайловой установки:
     * `models/<RUNTIME>/<id>` — та же раскладка, что у загрузчика.
     */
    private fun modelTargetDir(runtime: RuntimeType, modelId: String): File =
        File(File(modelsDir, runtime.name), modelId.replace('/', '_'))

    /**
     * Перенести распакованный временный каталог в каталог модели
     * ([modelTargetDir]) и посчитать суммарный размер его файлов. Общий шаг
     * импорта ZIP из метаданных (modelFromMeta) и из имени (modelFromZipName).
     * @return пара (каталог модели, размер в байтах); null при любом сбое —
     *   прежний пучок нетронут, staging удалён, извлечённый temp чистится
     *   в finally [importZip].
     */
    private suspend fun promoteExtractedDir(
        extracted: File,
        runtime: RuntimeType,
        modelId: String
    ): Pair<File, Long>? {
        val targetDir = modelTargetDir(runtime, modelId)
        // Issue #69: eviction кэша движков по образцу delete() — ДО подмены
        // каталога. Живой mmap активного инференса в кэше движка читает файлы
        // старого пучка (обрезанные данные → мусор или SIGBUS). releaseModel под
        // своим мьютексом дожидается окончания активного инференса и отпускает
        // старый каталог; следующий ensureLoaded построит движок по новым файлам.
        // Старая запись читается из БД до upsert, поэтому доступна здесь.
        repository.getById(modelId)?.let { runtimeRouter.releaseModel(it) }

        // Issue #74: staging + rename-подмена вместо wipe-then-copy. Раньше
        // targetDir.deleteRecursively() уничтожал рабочий пучок ДО копирования,
        // и отказ copyRecursively (например нехватка места на внешнем разделе)
        // оставлял БД READY на несуществующие файлы — падение на ensureLoaded;
        // возврат deleteRecursively игнорировался, частичное удаление превращало
        // копирование в merge-остатки (возврат #73). Копируем в соседний staging
        // того же тома и меняем каталоги renameTo: при любом отказе прежний
        // пучок остаётся на месте, замена цели — целиком, без слияния (#73).
        val runtimeDir = targetDir.parentFile ?: modelsDir
        val stagingDir = File(runtimeDir, "${targetDir.name}.promoting")
        val swappedOutDir = File(runtimeDir, "${targetDir.name}.old")
        stagingDir.deleteRecursively()   // мусор от прошлой неудачной промоции
        swappedOutDir.deleteRecursively()
        if (!copyExtractedToStaging(extracted, stagingDir)) {
            Timber.w("Не удалось скопировать модель $modelId в staging ${stagingDir.path}")
            stagingDir.deleteRecursively()
            return null
        }
        val hadTarget = targetDir.exists()
        if (hadTarget && !targetDir.renameTo(swappedOutDir)) {
            Timber.w("Не удалось отодвинуть старый каталог ${targetDir.path} — промоция отменена")
            stagingDir.deleteRecursively()
            return null
        }
        if (!stagingDir.renameTo(targetDir)) {
            Timber.w("Не удалось подменить каталог ${targetDir.path} — промоция отменена")
            if (hadTarget && !swappedOutDir.renameTo(targetDir)) {
                Timber.w("Не удалось вернуть старый каталог ${swappedOutDir.path} на место")
            }
            stagingDir.deleteRecursively()
            return null
        }
        // Вытесненный каталог чистим best-effort: файлы под новым именем уже
        // на месте, остатки старого тома лишь тратят место.
        if (swappedOutDir.exists() && !swappedOutDir.deleteRecursively()) {
            Timber.w("Не удалось удалить вытесненный каталог ${swappedOutDir.path}")
        }
        // Размер считаем один раз: каталог между этими полями не меняется
        // (sizeBytes и bytesDone всегда равны сумме файлов каталога).
        val size = targetDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return targetDir to size
    }

    /**
     * Seam (issue #74): чистое копирование extracted → staging вынесено в
     * internal-метод, чтобы unit-тест мог инъектировать отказ copyRecursively
     * (нехватку места на FS смоделировать сложно) и проверить сохранность
     * прежнего пучка при провале промоции. suspend (issue #76): тестовый
     * seam может вызвать delay — настоящее переключение корутин для проверки
     * сериализации импортов под importMutex (Thread.sleep блокирует поток
     * и не даёт детеминированной interleaving-картины).
     */
    internal suspend fun copyExtractedToStaging(extracted: File, stagingDir: File): Boolean =
        extracted.copyRecursively(stagingDir, overwrite = true)

    /**
     * Распакованный ZIP → модель из вложенных метаданных (id из каталога).
     * @return null, если category/runtime из метаданных не распознаются.
     */
    private suspend fun modelFromMeta(meta: ModelImportMeta, extracted: File): InstalledModel? {
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
        val (targetDir, size) = promoteExtractedDir(extracted, runtime, meta.id)
            ?: return null
        return InstalledModel(
            id = meta.id,
            category = category,
            displayName = meta.displayName,
            description = meta.description,
            runtime = runtime,
            sizeBytes = size,
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
            bytesDone = size,
            isBuiltin = meta.isBuiltin
        )
    }

    /**
     * Запись импортированной модели с параметрами-заглушками, угаданными по
     * имени: пустые языки/URL, нейтральные качество и скорость, неизвестная
     * лицензия. Общий для одиночного файла (importFromUri) и ZIP без
     * метаданных (modelFromZipName).
     */
    private fun guessedImportedModel(
        modelId: String,
        sourceName: String,
        runtime: RuntimeType,
        size: Long,
        filePath: String
    ) = InstalledModel(
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
        filePath = filePath,
        bytesDone = size,
        isBuiltin = false
    )

    /**
     * Распакованный ZIP без метаданных → модель с параметрами,
     * угаданными по имени файла.
     */
    private suspend fun modelFromZipName(sourceName: String, extracted: File): InstalledModel? {
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
        val (targetDir, size) = promoteExtractedDir(extracted, runtime, modelId)
            ?: return null
        return guessedImportedModel(
            modelId = modelId,
            sourceName = sourceName,
            runtime = runtime,
            size = size,
            filePath = targetDir.absolutePath
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

    private fun guessCategory(fileName: String): ModelCategory {
        val lower = fileName.lowercase()
        return when {
            lower.contains("whisper") || lower.contains("vosk") ||
                lower.contains("sherpa") || DownloadLayout.isArchive(lower) ->
                // sherpa-маркер и безмаркерный архив — STT-пачка (ArchiveInstaller).
                ModelCategory.STT
            else -> ModelCategory.TRANSLATION
        }
    }

    /**
     * Угадать рантайм по имени файла (реализация — [ModelFileNaming.guessRuntime],
     * общая с round-trip тестом именования экспорта). null — формат не распознан:
     * импорт отклоняется (с предупреждением в лог) вместо записи с битым
     * умолчанием MARIAN_ONNX.
     */
    private fun guessRuntime(fileName: String): RuntimeType? =
        ModelFileNaming.guessRuntime(fileName)

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
