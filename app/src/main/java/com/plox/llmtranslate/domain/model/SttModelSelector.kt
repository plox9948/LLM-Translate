package com.plox.llmtranslate.domain.model

/**
 * Выбор STT-модели с учётом назначений «язык → модель» (issue #28).
 *
 * Цепочка выбора для конкретного языка:
 * 1) модель, назначенная этому языку (если установлена и готова);
 * 2) для «ne» — фолбэк по умолчанию: установленная IndicConformer
 *    (runtime SHERPA, id содержит маркер indic) — без записи в настройки;
 * 3) глобальная активная STT-модель.
 */
object SttModelSelector {

    /** Язык, для которого применяется дефолтный фолбэк на IndicConformer. */
    const val INDIC_DEFAULT_LANGUAGE = "ne"

    /** Маркеры id IndicConformer-моделей (sherpa-onnx). */
    private val INDIC_ID_MARKERS = listOf("indicconformer", "indic")

    /**
     * Подобрать STT-модель.
     * @param language код языка исходного языка экрана (null — AUTO,
     *   назначается только глобальная активная модель).
     * @param assignments карта «язык → id» из настроек.
     * @param installed установленные модели категории STT.
     * @param globalActive глобальная активная STT-модель (или null).
     * @return выбранная модель или null, если подходить нечему.
     */
    fun select(
        language: String?,
        assignments: Map<String, String>,
        installed: List<InstalledModel>,
        globalActive: InstalledModel?
    ): InstalledModel? {
        if (language == null) return globalActive

        // 1) Явное назначение языка (переназначение — одна модель на язык).
        assignments[language]?.let { assignedId ->
            installed.firstOrNull { it.id == assignedId && it.isReady }
                ?.let { return it }
        }

        // 2) Дефолт для непальского: IndicConformer без записи в DataStore.
        if (language == INDIC_DEFAULT_LANGUAGE) {
            installed.firstOrNull { it.isReady && isIndicConformer(it) }
                ?.let { return it }
        }

        // 3) Глобальная активная модель.
        return globalActive
    }

    /**
     * true, если модель — IndicConformer (runtime SHERPA, id содержит маркер).
     */
    fun isIndicConformer(model: InstalledModel): Boolean {
        if (model.runtime != RuntimeType.SHERPA) return false
        val id = model.id.lowercase()
        return INDIC_ID_MARKERS.any { id.contains(it) }
    }
}
