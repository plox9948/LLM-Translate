package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.RuntimeError
import kotlinx.coroutines.flow.StateFlow

/**
 * Общий интерфейс рантаймов перевода.
 * Реализации: MarianOnnxRuntime (Marian MT), IndicTransOnnxRuntime (IndicTrans2).
 */
interface TranslationEngine {

    /**
     * Реактивное состояние: модели, загруженные в ОЗУ этим движком
     * (активная — первой; пуст список, если движок разгружен).
     * Движок может держать несколько моделей направлений, удерживаемых
     * LRU-кэшем (issue #36). Обновляется под мьютексом движка
     * при загрузке/разгрузке.
     */
    val loadedModels: StateFlow<List<LoadedModelInfo>>

    /** Убедиться, что загружена модель [model]. */
    suspend fun ensureLoaded(model: InstalledModel): RuntimeError?

    /**
     * Перевести текст.
     * @param from код исходного языка (ru, en, ne)
     * @param to код целевого языка
     */
    suspend fun translate(text: String, from: String, to: String): Result<String>

    /**
     * Разгрузить модель. Подвешивается до окончания активного инференса,
     * поэтому разгрузка не может выполнить use-after-free нативных сессий.
     */
    suspend fun release()
}

/**
 * Общий интерфейс рантаймов распознавания речи (STT).
 * Реализация: SherpaSttEngine (sherpa-onnx).
 */
interface SttEngine {

    /**
     * Реактивное состояние: модель, загруженная в ОЗУ этим движком
     * (список из 0 или 1 элемента — STT-движок держит одну модель;
     * пуст список, если движок разгружен). Обновляется под мьютексом
     * движка при загрузке/разгрузке.
     */
    val loadedModels: StateFlow<List<LoadedModelInfo>>

    /** Убедиться, что загружена модель [model]. */
    suspend fun ensureLoaded(model: InstalledModel): RuntimeError?

    /**
     * Распознать речь в WAV-файле.
     * @param wavPath путь к WAV-файлу (16 kHz, mono, 16-bit).
     * @param language подсказка языка (ru/en/ne); обязательна для whisper-моделей,
     *                 для CTC/transducer-моделей игнорируется.
     */
    suspend fun transcribe(wavPath: String, language: String? = null): Result<String>

    /**
     * Разгрузить модель. Подвешивается до окончания активного распознавания,
     * поэтому разгрузка не может выполнить use-after-free нативных ресурсов.
     */
    suspend fun release()
}
