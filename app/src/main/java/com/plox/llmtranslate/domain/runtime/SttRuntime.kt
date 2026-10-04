package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.RuntimeError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Рантайм STT на whisper.cpp: загрузка модели и транскрипция WAV.
 */
@Singleton
class SttRuntime @Inject constructor() : SttEngine {

    /** Идентификатор загруженной модели. */
    var loadedModelId: String? = null
        private set

    val isLoaded: Boolean
        get() = NativeBridge.whisperIsLoaded()

    /**
     * Убедиться, что загружена модель [model].
     * @return null при успехе или [RuntimeError] при ошибке.
     */
    override suspend fun ensureLoaded(model: InstalledModel): RuntimeError? =
        withContext(Dispatchers.IO) {
            val path = model.filePath
            if (path.isNullOrBlank()) return@withContext RuntimeError.ModelNotReady

            if (loadedModelId == model.id && NativeBridge.whisperIsLoaded()) {
                return@withContext null
            }

            if (NativeBridge.whisperIsLoaded()) {
                Timber.d("Разгружаю предыдущую STT-модель $loadedModelId")
                NativeBridge.whisperUnloadModel()
                loadedModelId = null
            }

            val code = NativeBridge.whisperLoadModel(path, nThreads = 4)
            if (code != 0) {
                Timber.e("Не удалось загрузить STT-модель $path (код $code)")
                return@withContext RuntimeError.LoadFailed("stt", "Код ошибки: $code")
            }
            loadedModelId = model.id
            null
        }

    /**
     * Распознать речь в WAV-файле [wavPath].
     * @return текст или [RuntimeError].
     */
    override suspend fun transcribe(wavPath: String): Result<String> =
        withContext(Dispatchers.IO) {
            if (!NativeBridge.whisperIsLoaded() || loadedModelId == null) {
                return@withContext Result.failure(RuntimeError.ModelNotSelected)
            }
            val text = NativeBridge.whisperTranscribe(wavPath, "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (text == null) {
                Result.failure(RuntimeError.InferenceFailed("stt", "Распознавание не выполнено"))
            } else {
                Result.success(text)
            }
        }

    /**
     * Разгрузить модель и освободить память.
     */
    override fun release() {
        if (NativeBridge.whisperIsLoaded()) {
            NativeBridge.whisperUnloadModel()
            loadedModelId = null
            Timber.d("STT-модель разгружена")
        }
    }
}
