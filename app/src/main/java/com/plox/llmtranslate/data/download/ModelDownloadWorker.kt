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
        val model = repository.getById(modelId)
        val urls = urlsCsv.split(',').filter { it.isNotBlank() }

        Timber.d("Запуск скачивания $modelId из ${urls.size} URL")

        // Троттлинг записи в Room: пишем только при изменении процента.
        var lastSavedPercent = -1
        val result = installer.install(urls, sha256, modelsDir, modelId) { progress ->
            setProgress(workDataOf(KEY_PROGRESS to progress))
            if (progress != lastSavedPercent) {
                lastSavedPercent = progress
                model?.let {
                    repository.upsert(
                        it.copy(status = ModelStatus.DOWNLOADING, progress = progress)
                    )
                }
            }
        }

        return when (result) {
            is InstallResult.Success -> {
                model?.let {
                    repository.upsert(
                        it.copy(
                            status = ModelStatus.READY,
                            progress = 100,
                            filePath = result.path,
                            bytesDone = sizeBytes
                        )
                    )
                }
                Result.success()
            }
            is InstallResult.Failure -> {
                if (result.message.contains("SHA-256")) {
                    // Повторная попытка не поможет — файл повреждён на сервере.
                    model?.let { repository.upsert(it.copy(status = ModelStatus.ERROR)) }
                    return Result.failure(workDataOf(KEY_ERROR to result.message))
                }
                // Сетевая ошибка — повторяем (докачка продолжит с места обрыва).
                model?.let { repository.upsert(it.copy(status = ModelStatus.DOWNLOADING)) }
                Result.retry()
            }
        }
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
