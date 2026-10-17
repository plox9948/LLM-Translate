package com.plox.llmtranslate.domain.model

/**
 * Выбор TTS-движка на каждый язык (issue #50): по умолчанию — системный
 * Android TTS; скачиваемая Piper-модель используется, только если явно
 * назначена этому языку и установлена/готова.
 *
 * Без Android-зависимостей — чистая логика для unit-тестов (по образцу
 * [SttModelSelector]).
 */
object TtsModelSelector {

    /**
     * Сентинель-значение настройки tts_by_language (issue #53): языку явно
     * назначен Системный Android TTS. Отличает явный выбор системного движка
     * от отсутствия назначения; по смыслу равно отсутствующему значению
     * (значение по умолчанию — тоже системный TTS).
     */
    const val SYSTEM_SENTINEL = TtsEngines.SYSTEM

    /** Выбор: движок по умолчанию или конкретная Piper-модель. */
    sealed class TtsChoice {
        /** Системный Android TTS (настройка ttsEngine / движок по умолчанию). */
        object SystemEngine : TtsChoice()

        /** Озвучивать скачиваемой моделью с id [id]. */
        data class Model(val id: String) : TtsChoice()
    }

    /** Карта «язык → карточка Piper по умолчанию» (для пресета настроек). */
    val DEFAULT_LANGUAGE_CARDS = mapOf(
        "ru" to "tts-ru-piper-ruslan",
        "en" to "tts-en-piper-ryan",
        "ne" to "tts-ne-piper-google"
    )

    /**
     * Подобрать способ озвучивания языка [language].
     * Явное назначение имеет приоритет: сентинель [SYSTEM_SENTINEL] —
     * Системный Android TTS; id Piper-модели — она же при готовности.
     * Без назначения — [TtsChoice.SystemEngine] (значения по умолчанию),
     * дефолтная карточка языка ([DEFAULT_LANGUAGE_CARDS]) автоматически
     * не выбирается — она используется только фолбэком
     * TtsPlaybackCoordinator при неподдерживаемом системном TTS.
     * @param assignments карта «язык → id» из настроек (ttsByLanguage).
     * @param piperInstalled установленные модели категории TTS (включая
     *   движки-ярлыки SYSTEM_TTS/RHVOICE/ESPEAK — они не участвуют в выборе).
     * @return [TtsChoice.Model] при готовой назначенной модели, иначе
     *   [TtsChoice.SystemEngine].
     */
    fun select(
        language: String,
        assignments: Map<String, String>,
        piperInstalled: List<InstalledModel>
    ): TtsChoice {
        val assignedId = assignments[language] ?: return TtsChoice.SystemEngine
        if (assignedId == SYSTEM_SENTINEL) return TtsChoice.SystemEngine
        val ready = piperInstalled.firstOrNull {
            it.id == assignedId &&
                it.runtime == RuntimeType.PIPER_ONNX &&
                it.isReady
        } ?: return TtsChoice.SystemEngine
        return TtsChoice.Model(ready.id)
    }

    /**
     * Id карточки Piper, предлагаемой по умолчанию для языка, или null
     * для языка без заготовки.
     */
    fun defaultCardId(language: String): String? = DEFAULT_LANGUAGE_CARDS[language]
}
