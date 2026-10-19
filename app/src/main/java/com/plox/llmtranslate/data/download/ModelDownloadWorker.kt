package com.plox.llmtranslate.data.download

import android.content.Context
import android.os.StatFs
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.plox.llmtranslate.data.model.ModelRepository
import com.plox.llmtranslate.data.storage.ModelStorage
import com.plox.llmtranslate.domain.model.InstallCheckLogic
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import com.plox.llmtranslate.domain.model.decodeDelimitedList
import com.plox.llmtranslate.domain.model.encodeDelimitedList
import com.plox.llmtranslate.domain.model.sha256sFromCsv
import com.plox.llmtranslate.domain.model.sha256sToCsv
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Воркер скачивания модели: докачка, SHA-256, обновление Room.
 * SHA-256 для больших файлов занимает 10–20 с, поэтому только здесь, не в UI.
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: ModelRepository,
    private val installerRegistry: InstallerRegistry
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val urlsCsv = inputData.getString(KEY_URLS)
        val sha256 = inputData.getString(KEY_SHA256)
        val sha256s = sha256sFromCsv(inputData.getString(KEY_SHA256S) ?: "")
        val runtimeName = inputData.getString(KEY_RUNTIME)
        val sizeBytes = inputData.getLong(KEY_SIZE_BYTES, 0L)

        val runtime = runtimeName?.let { name ->
            RuntimeType.entries.firstOrNull { it.name == name }
        }
        val installer = runtime?.let { installerRegistry.forRuntime(it) }

        // Ранние отказы (нет url/рантайма, неизвестный рантайм): install
        // уже не обновит статус — переводим запись из DOWNLOADING в ERROR,
        // иначе модель навсегда останется «скачивающейся» в UI.
        if (urlsCsv == null || installer == null) {
            Timber.e("Модель $modelId: некорректные входные данные скачивания")
            markError(modelId)
            return Result.failure()
        }

        val modelsRoot = ModelStorage.modelsRoot(applicationContext)
        val modelsDir = File(modelsRoot, runtime.name)
        // Список URL — с версионным префиксом «v1:» (encodeDelimitedList);
        // legacy-постановки без префикса («\n» или «,») разбирает фолбэк
        // decodeDelimitedList.
        val urls = decodeDelimitedList(urlsCsv).filter { it.isNotBlank() }

        // Ревью, п.3: гейт места до начала скачивания — пик установки по той
        // же формуле, что и подсказка в UI (InstallCheckLogic): архивная
        // распаковка ~2x, одиночный файл 1.2x. Нехватка — permanent-отказ
        // (повтор без освобождения места бессилен), модель сразу в ERROR.
        val isArchiveLayout = urls
            .mapIndexed { index, url -> DownloadLayout.sanitizedBasename(url, index) }
            .any { DownloadLayout.isArchive(it) }
        val requiredFreeBytes = InstallCheckLogic.requiredDownloadFreeBytes(sizeBytes, isArchiveLayout)
        if (requiredFreeBytes > 0L) {
            val stat = StatFs(modelsRoot.absolutePath)
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            if (freeBytes < requiredFreeBytes) {
                Timber.e(
                    "Модель $modelId: недостаточно места — требуется $requiredFreeBytes байт, " +
                        "доступно $freeBytes"
                )
                markError(modelId)
                return Result.failure(workDataOf(KEY_ERROR to "Недостаточно свободного места"))
            }
        }

        Timber.d("Запуск скачивания $modelId из ${urls.size} URL")

        // Троттлинг записи в Room: пишем только при изменении процента.
        // Все записи прогресса — точечные UPDATE только полей статуса/прогресса
        // (не upsert всей строки): свежий read-modify-write не может затереть
        // каталожные поля (ram_required_mb и др.), обновлённые
        // syncCatalogRamFields параллельно на старте процесса.
        var lastSavedPercent = -1
        val result = try {
            installer.install(urls, sha256, sha256s, modelsDir, modelId) { progress ->
                setProgress(workDataOf(KEY_PROGRESS to progress))
                if (progress != lastSavedPercent) {
                    lastSavedPercent = progress
                    repository.updateDownloadProgress(modelId, ModelStatus.DOWNLOADING, progress)
                }
            }
        } catch (e: CancellationException) {
            // Явная остановка (cancelUniqueWork / удаление записи): инсталляторы
            // всплывают отменой, не удаляя .part — чистим остатки здесь, пока
            // контекст воркера ещё жив (см. cleanupCancelledModel, issue #40).
            cleanupCancelledModel(modelId, modelsDir, runtime, urls)
            throw e
        }

        return when (result) {
            is InstallResult.Success -> {
                // bytesDone — по ФАКТУ установленных файлов (обход пути и сумма
                // length()), а не по каталожному sizeBytes: у архивных установок
                // размер распакованного содержимого отличается от размера
                // архива/пучка в каталоге.
                val installedBytes = File(result.path).walkTopDown()
                    .filter { it.isFile }
                    .sumOf { it.length() }
                val saved = try {
                    repository.finalizeDownload(modelId, result.path, installedBytes)
                } catch (e: CancellationException) {
                    // Отмена воркера между завершением установки и записью READY —
                    // файлы уже на диске, убираем их как осиротевшие.
                    cleanupCancelledModel(modelId, modelsDir, runtime, urls)
                    throw e
                }
                if (!saved) {
                    // Гонка отмены (issue #40 + ревью п.3): к моменту finalize
                    // запись удалена или уже не в DOWNLOADING (DAO-гейт
                    // finalizeDownloadById) — READY не выставляем, иначе
                    // отменённая/пересозданная запись воскресла бы с чужими
                    // файлами. Установленные файлы результата удаляем как
                    // осиротевшие: файловая очистка на стороне
                    // ModelManagerImpl.cancelDownload/delete могла выполниться
                    // до завершившегося здесь transfer+rename — повторяем
                    // очистку по той же раскладке после гонки.
                    Timber.d("Запись $modelId удалена или выведена из DOWNLOADING — READY не ставим")
                    cleanupCancelledModel(modelId, modelsDir, runtime, urls)
                }
                Result.success()
            }
            is InstallResult.Failure -> {
                if (result.permanent) {
                    // Повторная попытка не поможет — файл повреждён на сервере.
                    repository.updateDownloadStatus(modelId, ModelStatus.ERROR)
                    return Result.failure(workDataOf(KEY_ERROR to result.message))
                }
                if (runAttemptCount >= MAX_ATTEMPTS) {
                    Timber.e(
                        "Модель $modelId: исчерпаны попытки скачивания " +
                            "(попытка $runAttemptCount): ${result.message}"
                    )
                    repository.updateDownloadStatus(modelId, ModelStatus.ERROR)
                    return Result.failure(workDataOf(KEY_ERROR to result.message))
                }
                // Сетевая ошибка или mismatch после докачки (часть уже отброшена) —
                // повторяем: докачка продолжит с места обрыва либо начнётся с нуля.
                repository.updateDownloadStatus(modelId, ModelStatus.DOWNLOADING)
                Result.retry()
            }
        }
    }

    /**
     * Перевести запись модели из DOWNLOADING в ERROR (ранний отказ воркера).
     * Запись может быть удалена во время работы — тогда обновление пропускается.
     */
    private suspend fun markError(modelId: String) {
        repository.updateDownloadStatus(modelId, ModelStatus.ERROR)
    }

    /**
     * Очистить остатки незавершённой установки модели по раскладке
     * [DownloadLayout] после остановки (отмены) воркера (issue #40):
     * файловая очистка на стороне UI выполняется сразу после
     * cancelUniqueWork и могла опередить дописанный/переименованный здесь
     * файл — осиротевшие `.part`, `.part.meta` и целевые файлы остаются
     * навсегда без этой страховки.
     *
     * Если запись в БД уже READY (скачивание успело завершиться параллельно
     * с отменой, либо под этой id промоутен импорт) — файлы не трогаем.
     * Обычная докачка (сетевые повторы `Result.retry()`) этот метод не
     * вызывает — `.part`-файлы для докачки сохраняются.
     *
     * Остаточная гонка (контрольный аудит, п.3 — честно): READY-гейт закрывает
     * только случай «запись уже READY». Если импорт отменил загрузку и уже
     * промоутнул пучок, а запись ещё не upsert'нута READY, воркер может
     * прерваться на границе следующей распаковки/переименования (грубые
     * границы ensureActive в gunzip/tar — блоки по 64 КиБ) и снести часть
     * файлов уже после deletePendingParts импорта. Окно конечено и
     * самовосстанавливается: битая READY-запись выявляется сверкой
     * checkReadyRecordsAgainstDisk при старте приложения (READY→ERROR,
     * переустановка кнопкой в UI).
     *
     * Тело выполняется на [NonCancellable]: вызывается из перехваченного
     * [CancellationException], где обычная suspend-операция сразу упала бы
     * отменой.
     */
    private suspend fun cleanupCancelledModel(
        modelId: String,
        runtimeDir: File,
        runtime: RuntimeType,
        urls: List<String>
    ) = withContext(NonCancellable + Dispatchers.IO) {
        val row = repository.getById(modelId)
        if (row?.status == ModelStatus.READY) {
            Timber.d("Модель $modelId стала READY при отмене — файлы не удаляем")
            return@withContext
        }
        DownloadLayout.deleteOrphanedFiles(runtimeDir, runtime, modelId, urls)
    }

    companion object {
        const val KEY_MODEL_ID = "key_model_id"
        const val KEY_URLS = "key_urls"
        const val KEY_SHA256 = "key_sha256"
        const val KEY_SHA256S = "key_sha256s"
        const val KEY_RUNTIME = "key_runtime"
        const val KEY_SIZE_BYTES = "key_size_bytes"
        const val KEY_PROGRESS = "key_progress"
        const val KEY_ERROR = "key_error"

        private const val WORK_NAME_PREFIX = "download_model_"

        // Максимум автоматических повторов сетевых ошибок (backoff экспоненциальный).
        private const val MAX_ATTEMPTS = 5

        fun workName(modelId: String) = "${WORK_NAME_PREFIX}$modelId"

        /**
         * Запланировать скачивание модели. Повторный вызов не дублирует работу.
         */
        fun schedule(context: Context, model: InstalledModel) {
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_MODEL_ID to model.id,
                        // URL-список — с версионным префиксом «v1:»
                        // (encodeDelimitedList): URL может содержать запятые
                        // в query — «,» разъедал такие URL (аудит п.4).
                        KEY_URLS to encodeDelimitedList(model.downloadUrls()),
                        KEY_SHA256 to (model.sha256 ?: ""),
                        KEY_SHA256S to sha256sToCsv(model.sha256s),
                        KEY_RUNTIME to model.runtime.name,
                        KEY_SIZE_BYTES to model.sizeBytes
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(model.id),
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context, modelId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(modelId))
        }
    }
}
