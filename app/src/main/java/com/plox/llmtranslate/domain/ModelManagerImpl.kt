package com.plox.llmtranslate.domain

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.plox.llmtranslate.data.catalog.ModelCatalogProvider
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
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import com.plox.llmtranslate.domain.runtime.LoadedModelInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.zip.ZipEntry
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
    private val scope: CoroutineScope
) : ModelManager {

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
            } catch (e: Exception) {
                Timber.e(e, "Не удалось отменить скачивание модели $modelId")
            }
        }
    }

    /**
     * Удалить недокачанные .part-файлы отменённой модели и их метаданные
     * докачки (конвенция BaseDownloader: рядом с целевым файлом `<имя>.part`
     * и `<имя>.part.meta`).
     * Размещение повторяет логику инсталляторов (см. [ModelDownloadWorker]):
     * ONNX мультифайл и обычные файлы sherpa — в каталог модели,
     * одиночные ONNX-файлы и архивы — в корень каталога рантайма.
     */
    private fun deleteOrphanParts(model: InstalledModel) {
        val urls = model.downloadUrls()
        if (urls.isEmpty()) return
        val runtimeDir = File(modelsDir, model.runtime.name)
        urls.forEachIndexed { index, url ->
            val fileName = url.substringAfterLast('/').ifBlank { "file_$index" }
            val isArchive = fileName.endsWith(".tar.bz2") ||
                fileName.endsWith(".tar.gz") ||
                fileName.endsWith(".zip")
            val inModelDir = if (model.runtime == RuntimeType.SHERPA) {
                !isArchive
            } else {
                urls.size > 1
            }
            val dir = if (inModelDir) File(runtimeDir, model.id) else runtimeDir
            listOf(File(dir, "$fileName.part"), File(dir, "$fileName.part.meta")).forEach { part ->
                if (part.isFile && !part.delete()) {
                    Timber.w("Не удалось удалить осиротевший ${part.name}")
                }
            }
        }
    }

    override suspend fun importFromUri(uri: Uri): String? =
        withContext(Dispatchers.IO) {
            try {
                val fileName = queryFileName(uri) ?: return@withContext null
                val targetFile = copyFromUri(uri) ?: return@withContext null

                val modelId = fileName.removeSuffix(".gguf").removeSuffix(".onnx").removeSuffix(".zip")
                val model = InstalledModel(
                    id = modelId,
                    category = guessCategory(fileName),
                    displayName = fileName,
                    description = "Импортированная модель",
                    runtime = guessRuntime(fileName),
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
                        ZipOutputStream(out.buffered()).use { zip ->
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
                }
                repository.deleteById(modelId)
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
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при разгрузке рантаймов")
            }
        }
    }

    private suspend fun copyFromUri(uri: Uri): File? {
        val targetFile = File(modelsDir, "imported_${System.currentTimeMillis()}")
        context.contentResolver.openInputStream(uri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return null
        return targetFile
    }

    private fun queryFileName(uri: Uri): String? {
        uri.getQueryParameter("name")?.let { return it }
        val last = uri.lastPathSegment
        if (!last.isNullOrBlank()) return Uri.decode(last)
        return null
    }

    private fun guessCategory(fileName: String): ModelCategory {
        val lower = fileName.lowercase()
        return when {
            lower.contains("whisper") || lower.contains("vosk") -> ModelCategory.STT
            else -> ModelCategory.TRANSLATION
        }
    }

    private fun guessRuntime(fileName: String): RuntimeType {
        val lower = fileName.lowercase()
        return when {
            lower.contains("indictrans") -> RuntimeType.INDICTRANS_ONNX
            lower.endsWith(".zip") || lower.contains("sherpa") -> RuntimeType.SHERPA
            else -> RuntimeType.MARIAN_ONNX // .onnx и прочее — по умолчанию Marian
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
