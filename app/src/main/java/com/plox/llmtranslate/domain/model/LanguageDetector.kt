package com.plox.llmtranslate.domain.model

/**
 * Эвристика определения языка по письменности текста.
 * Поддерживает ru (кириллица), ne (деванагари), en (латиница).
 */
object LanguageDetector {

    /**
     * Определить язык текста.
     * @return язык или null, если определить не удалось (мало символов, микс).
     */
    fun detect(text: String): String? {
        var cyrillic = 0
        var devanagari = 0
        var latin = 0

        for (c in text) {
            when {
                c in '\u0400'..'\u04FF' -> cyrillic++
                c in '\u0900'..'\u097F' -> devanagari++
                (c in 'a'..'z') || (c in 'A'..'Z') -> latin++
            }
        }

        val total = cyrillic + devanagari + latin
        if (total < 3) return null

        // Язык определяется, если его письменность составляет более половины.
        return when {
            cyrillic * 2 > total -> "ru"
            devanagari * 2 > total -> "ne"
            latin * 2 > total -> "en"
            else -> null
        }
    }
}
