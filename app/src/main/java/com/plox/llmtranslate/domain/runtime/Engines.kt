package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.RuntimeError

/**
 * Общий интерфейс рантаймов перевода (LLM).
 * Реализации: LlmRuntime (GGUF/llama.cpp), OnnxLlmRuntime (ONNX/NLLB).
 */
interface TranslationEngine {
    /** Убедиться, что загружена модель [model]. */
    suspend fun ensureLoaded(model: InstalledModel): RuntimeError?

    /**
     * Перевести текст.
     * @param from код исходного языка (ru, en, ne)
     * @param to код целевого языка
     */
    suspend fun translate(text: String, from: String, to: String): Result<String>

    /** Разгрузить модель. */
    fun release()
}

/**
 * Общий интерфейс рантаймов распознавания речи (STT).
 * Реализации: SttRuntime (whisper.cpp), VoskSttRuntime (Vosk).
 */
interface SttEngine {
    /** Убедиться, что загружена модель [model]. */
    suspend fun ensureLoaded(model: InstalledModel): RuntimeError?

    /** Распознать речь в WAV-файле. */
    suspend fun transcribe(wavPath: String): Result<String>

    /** Разгрузить модель. */
    fun release()
}
