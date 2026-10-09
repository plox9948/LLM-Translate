package com.plox.llmtranslate.data.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.plox.llmtranslate.data.model.ModelRepository
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
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
        val urlsCsv = inputData.getString(KEY_URLS) ?: return Result.failure()
        val sha256 = inputData.getString(KEY_SHA256)
        val runtimeName = inputData.getString(KEY_RUNTIME) ?: return Result.failure()
        val sizeBytes = inputData.getLong(KEY_SIZE_BYTES, 0L)

        val runtime = RuntimeType.entries.firstOrNull { it.name == runtimeName }
            ?: return Result.failure()
        val installer = installerRegistry.forRuntime(runtime)
            ?: return Result.failure()

        val modelsRoot = applicationContext.getExternalFilesDir("models")
            ?: applicationContext.filesDir
        val modelsDir = File(modelsRoot, runtime.name)
        val urls = urlsCsv.split(',').filter { it.isNotBlank() }

        Timber.d("Запуск скачивания $modelId из ${urls.size} URL")

        // Троттлинг записи в Room: пишем только при изменении процента.
        var lastSavedPercent = -1
        val result = installer.install(urls, sha256, modelsDir, modelId) { progress ->
            setProgress(workDataOf(KEY_PROGRESS to progress))
            if (progress != lastSavedPercent) {
                lastSavedPercent = progress
                updateIfPresent(modelId) {
                    it.copy(status = ModelStatus.DOWNLOADING, progress = progress)
                }
            }
        }

        return when (result) {
            is InstallResult.Success -> {
                val saved = updateIfPresent(modelId) { current ->
                    current.copy(
                        status = ModelStatus.READY,
                        progress = 100,
                        filePath = result.path,
                        bytesDone = sizeBytes
                    )
                }
                if (!saved) {
                    // Запись удалена/отменена во время скачивания — не восстанавливаем,
                    // скачанный файл чистит delete(); при отмене остаётся на диске.
                    Timber.d("Запись $modelId удалена во время скачивания — состояние не сохраняем")
                }
                Result.success()
            }
            is InstallResult.Failure -> {
                if (result.permanent) {
                    // Повторная попытка не поможет — файл повреждён на сервере.
                    updateIfPresent(modelId) { it.copy(status = ModelStatus.ERROR) }
                    return Result.failure(workDataOf(KEY_ERROR to result.message))
                }
                if (runAttemptCount >= MAX_ATTEMPTS) {
                    Timber.e(
                        "Модель $modelId: исчерпаны попытки скачивания " +
                            "(попытка $runAttemptCount): ${result.message}"
                    )
                    updateIfPresent(modelId) { it.copy(status = ModelStatus.ERROR) }
                    return Result.failure(workDataOf(KEY_ERROR to result.message))
                }
                // Сетевая ошибка — повторяем (докачка продолжит с места обрыва).
                updateIfPresent(modelId) { it.copy(status = ModelStatus.DOWNLOADING) }
                Result.retry()
            }
        }
    }

    /**
     * Обновить запись только если она ещё существует (перечитывание из DAO
     * сразу перед записью): защита от гонки с cancel/delete — удалённая во
     * время скачивания запись не восстанавливается.
     *
     * @return true, если запись найдена и обновлена.
     */
    private suspend fun updateIfPresent(
        modelId: String,
        transform: (InstalledModel) -> InstalledModel
    ): Boolean {
        val current = repository.getById(modelId) ?: return false
        repository.upsert(transform(current))
        return true
    }

    companion object {
        const val KEY_MODEL_ID = "key_model_id"
        const val KEY_URLS = "key_urls"
        const val KEY_SHA256 = "key_sha256"
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
                        KEY_URLS to model.downloadUrls().joinToString(","),
                        KEY_SHA256 to (model.sha256 ?: ""),
                        KEY_RUNTIME to model.runtime.name,
                        KEY_SIZE_BYTES to model.sizeBytes
                    )
                )
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
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
