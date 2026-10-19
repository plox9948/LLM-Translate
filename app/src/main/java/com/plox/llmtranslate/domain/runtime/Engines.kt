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
     * Перевести текст по АКТИВНОМУ пучку движка (предварительно загруженному
     * [ensureLoaded]). Инференс и смена активного пучка сериализованы мьютексом
     * движка, но между [ensureLoaded] и [translate] другой вызов может сменить
     * активный пучок — движки проверяют соответствие направления и при
     * несовпадении возвращают [RuntimeError.DirectionMismatch] вместо перевода
     * чужой моделью (для моделей без определяемого направления — импортированных
     * с пустым списком языков — проверка пропускается).
     * @param from код исходного языка (ru, en, ne); у IndicTrans задаёт
     *        flores-префикс входа, у Marian/slimt — только проверяется.
     * @param to код целевого языка
     */
    suspend fun translate(text: String, from: String, to: String): Result<String>

    /**
     * Режет ли UI вход на предложения перед вызовом [translate] (issue #63).
     * Marian/slimt — `true`: tiny-модели теряют хвост длинного многосоставного
     * входа, перевод по предложениям обязателен. IndicTrans — `false`: модель
     * кодирует вход целиком в ограниченное окно позиционных эмбеддингов и
     * сама чанкует его по бюджету энкодера — разбиение на предложения на
     * уровне UI ломало бы этот чанкинг, поэтому движку передаётся весь текст.
     */
    val splitsIntoSentences: Boolean get() = true

    /**
     * Разгрузить модель. Подвешивается до окончания активного инференса,
     * поэтому разгрузка не может выполнить use-after-free нативных сессий.
     */
    suspend fun release()

    /**
     * Извлечь и освободить пучок конкретной модели [modelId] из LRU-кэша
     * движка (удаление модели, issue #40): без этого кэш держит нативный
     * handle по уже удалённым файлам, а [loadedModels] продолжает показывать
     * модель «загруженной». Если пучок активен — сбрасывается активный id.
     * Подвешивается до окончания активного инференса (как [release]).
     */
    suspend fun releaseModel(modelId: String)
}

/**
 * Общий интерфейс рантаймов распознавания речи (STT).
 * Реализация: SherpaSttEngine (sherpa-onnx).
 */
interface SttEngine {

    /**
     * Реактивное состояние: модели, загруженные в ОЗУ этим движком
     * (активная — первой; пуст список, если движок разгружен). Движок
     * может держать несколько моделей, удерживаемых LRU-кэшем (issue #37).
     * Обновляется под мьютексом движка при загрузке/разгрузке.
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
     * Разгрузить все удерживаемые движком модели. Подвешивается до окончания
     * активного распознавания, поэтому разгрузка не может выполнить
     * use-after-free нативных ресурсов.
     */
    suspend fun release()

    /**
     * Извлечь и освободить пучок конкретной модели [modelId] из LRU-кэша
     * движка (удаление модели, issue #40). Если пучок активен — сбрасывается
     * активный id. Подвешивается до окончания активного распознавания
     * (как [release]).
     */
    suspend fun releaseModel(modelId: String)
}
