package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.RuntimeError

/**
 * Общий интерфейс рантаймов перевода.
 * Реализации: MarianOnnxRuntime (Marian MT), IndicTransOnnxRuntime (IndicTrans2).
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
 * Реализация: SherpaSttEngine (sherpa-onnx).
 */
interface SttEngine {
    /** Убедиться, что загружена модель [model]. */
    suspend fun ensureLoaded(model: InstalledModel): RuntimeError?

    /**
     * Распознать речь в WAV-файле.
     * @param wavPath путь к WAV-файлу (16 kHz, mono, 16-bit).
     * @param language подсказка языка (ru/en/ne); обязательна для whisper-моделей,
     *                 для CTC/transducer-моделей игнорируется.
     */
    suspend fun transcribe(wavPath: String, language: String? = null): Result<String>

    /** Разгрузить модель. */
    fun release()
}
