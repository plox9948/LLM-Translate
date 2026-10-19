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

        // 3) IndicTrans2: соседство токенов "indic" и кода хаба в id
        // («indic-en» — из индийских в en; «en-indic» — из en в индийские).
        // Сверка по токенам (ревью): прежняя подстрока «indic-en» ловилась
        // внутри произвольных идентификаторов.
        val tokens = id.split('-', '_')
        for (i in 0 until tokens.size - 1) {
            if (tokens[i] == "indic" && tokens[i + 1] == HUB_LANG) {
                val src = languages.firstOrNull { it != HUB_LANG } ?: return null
                return src to HUB_LANG
            }
            if (tokens[i] == HUB_LANG && tokens[i + 1] == "indic") {
                val tgt = languages.firstOrNull { it != HUB_LANG } ?: return null
                return HUB_LANG to tgt
            }
        }

        return null
    }

    /**
     * Подбирает цепочку моделей для направления from→to.
     * @return список из 1 модели (прямая) или 2 (через английский), либо null.
     */
    fun selectChain(models: List<InstalledModel>, from: String, to: String): List<InstalledModel>? {
        if (from == to) return null

        // Направление каждой модели считаем один раз (ревью): прежние
        // firstOrNull+directionOf пересчитывали направление на каждое сравнение.
        val directions = models.map { it to directionOf(it) }

        // Прямая модель для направления.
        directions.firstOrNull { it.second == (from to to) }?.first
            ?.let { return listOf(it) }

        // Fallback через английский: from→en, затем en→to.
        val toHub = directions.firstOrNull { it.second == (from to HUB_LANG) }?.first
        val fromHub = directions.firstOrNull { it.second == (HUB_LANG to to) }?.first
        if (toHub != null && fromHub != null) return listOf(toHub, fromHub)

        return null
    }

    /**
     * Подбирает недостающие звенья цепочки from→to из каталога (issue #33):
     * карточки категории TRANSLATION, образующие прямую цепочку или цепочку
     * через английский, за исключением звеньев, уже покрытых установленными
     * (готовыми) моделями. Скрытые карточки (hidden) не предлагаются (ревью).
     *
     * Покрытие звена проверяется по установленным моделям ДО требования
     * карточки каталога (ревью): звено, закрытое импортированной моделью
     * (её id отсутствует в каталоге), не должно делать всю цепочку
     * «непокрываемой» — реально недостающее звено предлагается к установке.
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

        val translationCatalog = catalog.filter {
            it.category == ModelCategory.TRANSLATION && !it.hidden
        }

        // Направления считаем по одному разу на модель/карточку (ревью).
        val readyDirections = ready.mapNotNull { directionOf(it) }.toSet()
        val readyIds = ready.map { it.id }.toSet()
        val cardDirections = translationCatalog.map { it to directionOf(it) }

        fun cardFor(direction: Pair<String, String>): ModelCard? =
            cardDirections.firstOrNull { it.second == direction }?.first

        // Прямая цепочка имеет приоритет, как в selectChain: карточка-звено
        // ищется один раз и переиспользуется для проверки покрытия.
        val direct = cardFor(from to to)
        if (direct != null) {
            val covered = (from to to) in readyDirections || direct.id in readyIds
            return if (covered) emptyList() else listOf(direct)
        }

        // Цепочка через хаб: каждое звено либо закрыто готовой моделью
        // (по направлению или id карточки), либо требует карточку из каталога.
        // Звено без покрытия и без карточки — цепочка не строится.
        val result = ArrayList<ModelCard>(2)
        for (direction in listOf(from to HUB_LANG, HUB_LANG to to)) {
            if (direction in readyDirections) continue
            val card = cardFor(direction) ?: return emptyList()
            if (card.id in readyIds) continue
            result.add(card)
        }
        return result
    }
}
