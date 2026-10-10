package com.plox.llmtranslate.domain.model

/**
 * Автовыбор модели перевода по направлению (from→to) и fallback через английский.
 *
 * Направление модели определяется по id, имени и описанию (например, "ru→en",
 * "indic-en"). Это позволяет не менять схему БД и каталог моделей.
 */
object TranslationModelSelector {

    /** Язык-хаб для цепочек fallback (перевод через английский). */
    const val HUB_LANG = "en"

    /** Стрелка направления в имени/описании модели: "RU→EN", "ru→en". */
    private val DIRECTION_REGEX = Regex("""([a-z]+)\s*→\s*([a-z]+)""")

    /**
     * Определяет направление модели (from→to).
     * @return пара (исходный, целевой) или null, если направление не определено.
     */
    fun directionOf(model: InstalledModel): Pair<String, String>? {
        val langs = model.languages.toSet()

        // 1) Явная стрелка в имени/описании: "RU→EN", "ru→en".
        val text = (model.displayName + " " + model.description).lowercase()
        for (m in DIRECTION_REGEX.findAll(text)) {
            val a = m.groupValues[1]
            val b = m.groupValues[2]
            if (a in langs && b in langs && a != b) return a to b
        }

        // 2) Пара кодов языков через разделитель в id: "ru-en", "en-ru".
        val id = model.id.lowercase()
        val parts = id.split('-', '_', ' ')
        for (i in 0 until parts.size - 1) {
            val a = parts[i]
            val b = parts[i + 1]
            if (a in langs && b in langs && a != b) return a to b
        }

        // 3) IndicTrans2: "indic-en" (из индийских в en) / "en-indic" (из en в индийские).
        if (id.contains("indic-en")) {
            val src = model.languages.firstOrNull { it != HUB_LANG } ?: return null
            return src to HUB_LANG
        }
        if (id.contains("en-indic")) {
            val tgt = model.languages.firstOrNull { it != HUB_LANG } ?: return null
            return HUB_LANG to tgt
        }

        return null
    }

    /**
     * Подбирает цепочку моделей для направления from→to.
     * @return список из 1 модели (прямая) или 2 (через английский), либо null.
     */
    fun selectChain(models: List<InstalledModel>, from: String, to: String): List<InstalledModel>? {
        if (from == to) return null

        // Прямая модель для направления.
        models.firstOrNull { directionOf(it) == (from to to) }
            ?.let { return listOf(it) }

        // Fallback через английский: from→en, затем en→to.
        val toHub = models.firstOrNull { directionOf(it) == (from to HUB_LANG) }
        val fromHub = models.firstOrNull { directionOf(it) == (HUB_LANG to to) }
        if (toHub != null && fromHub != null) return listOf(toHub, fromHub)

        return null
    }
}
