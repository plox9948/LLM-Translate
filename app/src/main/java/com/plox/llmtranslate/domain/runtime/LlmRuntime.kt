package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.RuntimeError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Рантайм LLM на llama.cpp: загрузка GGUF-модели и генерация перевода.
 * В ОЗУ держится только одна модель; повторный [ensureLoaded] разгружает предыдущую.
 */
@Singleton
class LlmRuntime @Inject constructor() : TranslationEngine {

    /** Идентификатор загруженной модели. */
    var loadedModelId: String? = null
        private set

    val isLoaded: Boolean
        get() = NativeBridge.llamaIsLoaded()

    /**
     * Убедиться, что загружена модель [model].
     * @return null при успехе или [RuntimeError] при ошибке.
     */
    override suspend fun ensureLoaded(model: InstalledModel): RuntimeError? =
        withContext(Dispatchers.IO) {
            val path = model.filePath
            if (path.isNullOrBlank()) return@withContext RuntimeError.ModelNotReady

            if (loadedModelId == model.id && NativeBridge.llamaIsLoaded()) {
                return@withContext null
            }

            if (NativeBridge.llamaIsLoaded()) {
                Timber.d("Разгружаю предыдущую LLM-модель $loadedModelId")
                NativeBridge.llamaUnloadModel()
                loadedModelId = null
            }

            val code = NativeBridge.llamaLoadModel(path, nThreads = 4, nCtx = 4096)
            if (code != 0) {
                Timber.e("Не удалось загрузить LLM-модель $path (код $code)")
                return@withContext RuntimeError.LoadFailed("llm", "Код ошибки: $code")
            }
            loadedModelId = model.id
            null
        }

    /**
     * Перевести текст с языка [from] на язык [to] (коды: ru, en, ne).
     * @return текст перевода или [RuntimeError].
     */
    override suspend fun translate(text: String, from: String, to: String): Result<String> =
        withContext(Dispatchers.IO) {
            if (!NativeBridge.llamaIsLoaded() || loadedModelId == null) {
                return@withContext Result.failure(RuntimeError.ModelNotSelected)
            }
            val prompt = buildPrompt(text, langTitle(from), langTitle(to))
            val output = NativeBridge.llamaGenerate(prompt, maxTokens = 1024)
                ?: return@withContext Result.failure(
                    RuntimeError.InferenceFailed("llm", "Генерация не выполнена")
                )
            val cleaned = output.trim()
            if (cleaned.isEmpty()) {
                Result.failure(RuntimeError.InferenceFailed("llm", "Пустой результат"))
            } else {
                Result.success(cleaned)
            }
        }

    /** Отменить текущую генерацию. */
    fun cancel() {
        NativeBridge.llamaCancel()
    }

    /**
     * Разгрузить модель и освободить память.
     * Вызывается при смене модели и ON_STOP процесса.
     */
    override fun release() {
        if (NativeBridge.llamaIsLoaded()) {
            NativeBridge.llamaUnloadModel()
            loadedModelId = null
            Timber.d("LLM-модель разгружена")
        }
    }

    private fun buildPrompt(text: String, from: String, to: String): String =
        "Translate the following text from $from to $to. " +
            "Output only the translation, no explanations:\n$text"

    /**
     * Название языка для промпта по коду.
     */
    private fun langTitle(code: String): String = when (code) {
        "ru" -> "Russian"
        "en" -> "English"
        "ne" -> "Nepali"
        else -> code
    }
}
