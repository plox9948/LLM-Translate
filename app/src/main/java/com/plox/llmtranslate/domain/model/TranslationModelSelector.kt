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
     * Определяет направление установленной модели (from→to).
     * @return пара (исходный, целевой) или null, если направление не определено.
     */
    fun directionOf(model: InstalledModel): Pair<String, String>? =
        directionOfFields(model.id, model.displayName, model.description, model.languages)

    /**
     * Определяет направление модели из каталога (from→to) по тем же признакам,
     * что и для установленных моделей (issue #33).
     * @return пара (исходный, целевой) или null, если направление не определено.
     */
    fun directionOf(card: ModelCard): Pair<String, String>? =
        directionOfFields(card.id, card.displayName, card.description, card.languages)

    /**
     * Общая логика определения направления по полям модели
     * (id, имя, описание, языки) — общая для каталога и установленных моделей.
     */
    private fun directionOfFields(
        rawId: String,
        displayName: String,
        description: String,
        languages: List<String>
    ): Pair<String, String>? {
        val langs = languages.toSet()

        // 1) Явная стрелка в имени/описании: "RU→EN", "ru→en".
        val text = (displayName + " " + description).lowercase()
        for (m in DIRECTION_REGEX.findAll(text)) {
            val a = m.groupValues[1]
            val b = m.groupValues[2]
            if (a in langs && b in langs && a != b) return a to b
        }

        // 2) Пара кодов языков через разделитель в id: "ru-en", "en-ru".
        val id = rawId.lowercase()
        val parts = id.split('-', '_', ' ')
        for (i in 0 until parts.size - 1) {
            val a = parts[i]
            val b = parts[i + 1]
            if (a in langs && b in langs && a != b) return a to b
        }

        // 3) IndicTrans2: "indic-en" (из индийских в en) / "en-indic" (из en в индийские).
        if (id.contains("indic-en")) {
            val src = languages.firstOrNull { it != HUB_LANG } ?: return null
            return src to HUB_LANG
        }
        if (id.contains("en-indic")) {
            val tgt = languages.firstOrNull { it != HUB_LANG } ?: return null
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

    /**
     * Подбирает недостающие звенья цепочки from→to из каталога (issue #33):
     * карточки категории TRANSLATION, образующие прямую цепочку или цепочку
     * через английский, за исключением звеньев, уже покрытых установленными
     * (готовыми) моделями.
     *
     * @return карточки для скачивания; пустой список, если цепочка уже
     *         установлена или каталог её не покрывает.
     */
    fun missingChain(
        catalog: List<ModelCard>,
        installed: List<InstalledModel>,
        from: String,
        to: String
    ): List<ModelCard> {
        if (from == to) return emptyList()

        // Цепочка уже закрыта готовыми установленными моделями.
        val ready = installed.filter { it.isReady }
        if (selectChain(ready, from, to) != null) return emptyList()

        val translationCatalog = catalog.filter { it.category == ModelCategory.TRANSLATION }

        // Карточка каталога для звена нужна, если это звено не закрыто
        // готовой моделью (по id каталога или по направлению).
        fun neededCard(direction: Pair<String, String>): ModelCard? {
            val card = translationCatalog.firstOrNull { directionOf(it) == direction } ?: return null
            val covered = ready.any { it.id == card.id || directionOf(it) == direction }
            return if (covered) null else card
        }

        // Прямая цепочка имеет приоритет, как в selectChain. Карточка ищется
        // один раз и переиспользуется для проверки покрытия.
        val direct = translationCatalog.firstOrNull { directionOf(it) == (from to to) }
        if (direct != null) {
            val covered = ready.any { it.id == direct.id || directionOf(it) == (from to to) }
            return if (covered) emptyList() else listOf(direct)
        }

        // Цепочка через хаб: каталог должен покрывать оба звена.
        val toHub = translationCatalog.firstOrNull { directionOf(it) == (from to HUB_LANG) }
        val fromHub = translationCatalog.firstOrNull { directionOf(it) == (HUB_LANG to to) }
        if (toHub == null || fromHub == null) return emptyList()

        return listOfNotNull(neededCard(from to HUB_LANG), neededCard(HUB_LANG to to))
    }
}
