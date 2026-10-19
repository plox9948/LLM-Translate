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

    /**
     * Резервная карта «язык → карточка Piper по умолчанию» (для пресета
     * настроек): применяется, только если в каталоге для языка нет поля
     * default_for_language (ревью) И карточка с таким id присутствует среди
     * карточек каталога — иначе резерв мог ссылаться на несуществующую
     * модель (ревью #83).
     */
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
     * для языка без заготовки. Приоритет — поле каталога
     * default_for_language ([ModelCard.defaultForLanguage]):
     * карточка, назначенная языку в каталоге; константа
     * [DEFAULT_LANGUAGE_CARDS] — только резерв, когда в переданном
     * каталоге default для языка отсутствует (пустой каталог, удалённый
     * каталог без поля — ревью), и карточка с резервным id присутствует в
     * переданном каталоге (ревью #83): каталог без такой карточки — null,
     * а не битый id. Из поиска исключены скрытые карточки
     * (hidden) и карточки не-piper рантаймов (ревью) — предложить нельзя
     * то, что недоступно для скачивания.
     */
    fun defaultCardId(language: String, catalogCards: List<ModelCard> = emptyList()): String? {
        catalogCards.firstOrNull {
            it.defaultForLanguage == language &&
                it.runtime == RuntimeType.PIPER_ONNX &&
                !it.hidden
        }?.let { return it.id }
        // Константный резерв — только если карточка с этим id есть среди
        // карточек каталога (ревью #83).
        val fallbackId = DEFAULT_LANGUAGE_CARDS[language] ?: return null
        return fallbackId.takeIf { id ->
            catalogCards.any { it.id == id && it.runtime == RuntimeType.PIPER_ONNX && !it.hidden }
        }
    }
}
