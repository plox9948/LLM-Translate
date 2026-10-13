package com.plox.llmtranslate.data.download

import android.content.Context
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
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import com.plox.llmtranslate.domain.model.sha256sFromCsv
import com.plox.llmtranslate.domain.model.sha256sToCsv
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

        val modelsRoot = applicationContext.getExternalFilesDir("models")
            ?: applicationContext.filesDir
        val modelsDir = File(modelsRoot, runtime.name)
        val urls = urlsCsv.split(',').filter { it.isNotBlank() }

        Timber.d("Запуск скачивания $modelId из ${urls.size} URL")

        // Троттлинг записи в Room: пишем только при изменении процента.
        // Все записи прогресса — точечные UPDATE только полей статуса/прогресса
        // (не upsert всей строки): свежий read-modify-write не может затереть
        // каталожные поля (ram_required_mb и др.), обновлённые
        // syncCatalogRamFields параллельно на старте процесса.
        var lastSavedPercent = -1
        val result = installer.install(urls, sha256, sha256s, modelsDir, modelId) { progress ->
            setProgress(workDataOf(KEY_PROGRESS to progress))
            if (progress != lastSavedPercent) {
                lastSavedPercent = progress
                repository.updateDownloadProgress(modelId, ModelStatus.DOWNLOADING, progress)
            }
        }

        return when (result) {
            is InstallResult.Success -> {
                val saved = repository.finalizeDownload(modelId, result.path, sizeBytes)
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
                        KEY_URLS to model.downloadUrls().joinToString(","),
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
