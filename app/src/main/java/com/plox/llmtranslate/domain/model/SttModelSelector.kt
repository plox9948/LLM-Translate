package com.plox.llmtranslate.domain.model

/**
 * Выбор STT-модели с учётом назначений «язык → модель» (issues #28, #29):
 * глобальной активной STT-модели больше нет — выбор полностью по языкам.
 *
 * Цепочка выбора для конкретного языка:
 * 1) модель, назначенная этому языку (если установлена и готова);
 * 2) для «ne» — фолбэк по умолчанию: установленная IndicConformer
 *    (runtime SHERPA, id содержит маркер indic) — без записи в настройки;
 * 3) первая установленная готовая модель, поддерживающая язык
 *    (languages содержит код языка).
 *
 * Для AUTO (language == null) — мультаязычная модель (languages из двух
 * и более кодов, фактически Whisper; при нескольких — «whisper» приоритетом
 * раньше прочих), иначе первая установленная готовая модель.
 */
object SttModelSelector {

    /** Язык, для которого применяется дефолтный фолбэк на IndicConformer. */
    const val INDIC_DEFAULT_LANGUAGE = "ne"

    /** Маркеры id IndicConformer-моделей (sherpa-onnx). */
    private val INDIC_ID_MARKERS = listOf("indicconformer", "indic")

    /** Маркер id мультаязычных Whisper-моделей — приоритет для AUTO. */
    private const val WHISPER_ID_MARKER = "whisper"

    /**
     * Подобрать STT-модель.
     * @param language код языка исходного языка экрана (null — AUTO:
     *   мультаязычная модель, иначе любая установленная).
     * @param assignments карта «язык → id» из настроек.
     * @param installed установленные модели категории STT.
     * @return выбранная модель или null, если подходить нечему.
     */
    fun select(
        language: String?,
        assignments: Map<String, String>,
        installed: List<InstalledModel>
    ): InstalledModel? {
        val ready = installed.filter { it.isReady }

        // AUTO: мультаязычная модель (whisper в приоритете), иначе любая.
        if (language == null) {
            val multilingual = ready.filter { it.languages.size >= 2 }
            return multilingual.firstOrNull { isWhisper(it) }
                ?: multilingual.firstOrNull()
                ?: ready.firstOrNull()
        }

        // 1) Явное назначение языка (переназначение — одна модель на язык).
        assignments[language]?.let { assignedId ->
            ready.firstOrNull { it.id == assignedId }?.let { return it }
        }

        // 2) Дефолт для непальского: IndicConformer без записи в DataStore.
        if (language == INDIC_DEFAULT_LANGUAGE) {
            ready.firstOrNull { isIndicConformer(it) }?.let { return it }
        }

        // 3) Первая установленная готовая модель, поддерживающая язык.
        return ready.firstOrNull { it.languages.contains(language) }
    }

    /**
     * Недостающая STT-карточка для памятки на экране «Перевод» (issue #56):
     * если [select] не подобрал готовую STT-модель для [language], подбирается
     * карточка из STT-секции каталога для скачивания. Скрытые карточки
     * (whisper, hidden, issue #55) в памятку не добавляются.
     *
     * Подбор следует цепочке [select]: сначала карточка с id, назначенным
     * языку, затем для «ne» карточка IndicConformer (дефолт), затем первая
     * видимая карточка, поддерживающая язык.
     *
     * @return карточка для скачивания; null — для AUTO, если готовая STT-модель
     *         для языка уже есть, или каталог не покрывает язык видимой
     *         карточкой.
     */
    fun missingCard(
        catalog: List<ModelCard>,
        assignments: Map<String, String>,
        installed: List<InstalledModel>,
        language: String?
    ): ModelCard? {
        // AUTO: мультаязычная/любая модель, язык не задан — не подсказываем.
        if (language == null) return null
        if (select(language, assignments, installed) != null) return null

        val visible = catalog.filter { it.category == ModelCategory.STT && !it.hidden }

        assignments[language]?.let { assignedId ->
            visible.firstOrNull { it.id == assignedId }?.let { return it }
        }
        if (language == INDIC_DEFAULT_LANGUAGE) {
            visible.firstOrNull { isIndicConformerCard(it) }?.let { return it }
        }
        return visible.firstOrNull { it.languages.contains(language) }
    }

    /**
     * true, если карточка каталога — IndicConformer (runtime SHERPA,
     * id содержит маркер).
     */
    private fun isIndicConformerCard(card: ModelCard): Boolean {
        if (card.runtime != RuntimeType.SHERPA) return false
        val id = card.id.lowercase()
        return INDIC_ID_MARKERS.any { id.contains(it) }
    }

    /**
     * true, если модель — IndicConformer (runtime SHERPA, id содержит маркер).
     */
    fun isIndicConformer(model: InstalledModel): Boolean {
        if (model.runtime != RuntimeType.SHERPA) return false
        val id = model.id.lowercase()
        return INDIC_ID_MARKERS.any { id.contains(it) }
    }

    /**
     * true, если id модели содержит маркер Whisper — приоритет выбора для AUTO.
     */
    private fun isWhisper(model: InstalledModel): Boolean =
        model.id.lowercase().contains(WHISPER_ID_MARKER)
}
