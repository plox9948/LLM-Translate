package com.plox.llmtranslate.domain.model

import java.util.Locale

/**
 * Приоритизация каталога моделей под текущее направление перевода (issue #33):
 * карточки недостающих звеньев цепочки поднимаются наверх списка и помечаются
 * чипом направления («RU→EN»). Логика чистая, без Android-зависимостей.
 */
object CatalogPriority {

    /**
     * Подписи чипов для карточек, недостающих для направления from→to.
     *
     * @return карта id карточки каталога → подпись («RU→EN»); пустая, если
     *         направление невалидно (from == to) или цепочка уже установлена.
     */
    fun requiredChips(
        catalog: List<ModelCard>,
        installed: List<InstalledModel>,
        from: String,
        to: String
    ): Map<String, String> {
        if (from == to) return emptyMap()
        // Скрытые карточки (hidden, issue #55) чипами не предлагаются,
        // хотя остаются в каталоге.
        val visible = catalog.filterNot { it.hidden }
        return TranslationModelSelector.missingChain(visible, installed, from, to)
            .associate { card ->
                card.id to chipLabel(from, to)
            }
    }

    /**
     * Подпись чипа направления: коды заглавными через стрелку («RU→EN»).
     */
    fun chipLabel(from: String, to: String): String =
        "${from.uppercase(Locale.ROOT)}→${to.uppercase(Locale.ROOT)}"

    /**
     * Переставляет карточки из [required] в начало списка в порядке звеньев
     * цепочки; порядок остальных карточек не меняется (сортировка стабильна).
     */
    fun prioritize(catalog: List<ModelCard>, required: Map<String, String>): List<ModelCard> {
        if (required.isEmpty()) return catalog
        val rank = HashMap<String, Int>(required.size)
        var index = 0
        for (id in required.keys) {
            rank[id] = index++
        }
        return catalog.sortedBy { card -> rank[card.id] ?: Int.MAX_VALUE }
    }
}
