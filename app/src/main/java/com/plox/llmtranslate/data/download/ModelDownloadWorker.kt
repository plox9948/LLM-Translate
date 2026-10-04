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
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Воркер скачивания модели: докачка, SHA-256, обновление Room.
 * SHA-256 для больших файлов занимает 10–20 с, поэтому только здесь, не в UI.
 */
@HiltWorker
class ModelDownloadWorker @Inject constructor(
    appContext: Context,
    workerParams: WorkerParameters,
    private val repository: ModelRepository,
    private val installers: List<ModelInstaller>
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val sha256 = inputData.getString(KEY_SHA256)
        val runtimeName = inputData.getString(KEY_RUNTIME) ?: return Result.failure()
        val sizeBytes = inputData.getLong(KEY_SIZE_BYTES, 0L)

        val runtime = RuntimeType.entries.firstOrNull { it.name == runtimeName }
            ?: return Result.failure()
        val installer = installers.firstOrNull { it.supports(runtime) }
            ?: return Result.failure()

        val modelsDir = File(applicationContext.getExternalFilesDir("models"), runtime.name)
        val model = repository.getById(modelId)

        Timber.d("Запуск скачивания $modelId из $url")

        val result = installer.install(url, sha256, modelsDir, modelId) { progress ->
            setProgress(workDataOf(KEY_PROGRESS to progress))
            repository.upsert(
                model?.copy(status = ModelStatus.DOWNLOADING, progress = progress)
            )
        }

        return when (result) {
            is InstallResult.Success -> {
                repository.upsert(
                    model?.copy(
                        status = ModelStatus.READY,
                        progress = 100,
                        filePath = result.path,
                        bytesDone = sizeBytes
                    )
                )
                Result.success()
            }
            is InstallResult.Failure -> {
                if (result.message.contains("SHA-256")) {
                    // Повторная попытка не поможет — файл повреждён на сервере.
                    repository.upsert(
                        model?.copy(status = ModelStatus.ERROR)
                    )
                    Result.failure(workDataOf(KEY_ERROR to result.message))
                }
                // Сетевая ошибка — повторяем (докачка продолжит с места обрыва).
                repository.upsert(
                    model?.copy(status = ModelStatus.DOWNLOADING)
                )
                Result.retry()
            }
        }
    }

    companion object {
        const val KEY_MODEL_ID = "key_model_id"
        const val KEY_URL = "key_url"
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
                        KEY_URL to model.url,
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
