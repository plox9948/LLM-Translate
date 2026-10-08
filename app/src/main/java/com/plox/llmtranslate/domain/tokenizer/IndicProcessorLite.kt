package com.plox.llmtranslate.domain.tokenizer

import java.text.Normalizer

/**
 * Минимальный порт IndicProcessor для направлений npi_Deva ⇄ eng_Latn.
 *
 * Препроцессинг: NFC-нормализация + вставка языкового префикса "src tgt ".
 * Постпроцессинг: извлечение текста перевода.
 * Для en→npi выход уже в Деванагари (трансслитерация не нужна).
 *
 * Используется только для направлений с ne ⇄ en (IndicTrans2 не поддерживает ru).
 */
object IndicProcessorLite {

    /** Flores-код непальского (Деванагари). */
    const val NEPALI = "npi_Deva"

    /** Flores-код английского. */
    const val ENGLISH = "eng_Latn"

    /**
     * Препроцессинг текста перед переводом.
     * @param text исходный текст
     * @param srcLang flores-код исходного языка
     * @param tgtLang flores-код целевого языка
     * @return текст с языковым префиксом "src tgt "
     */
    fun preprocess(text: String, srcLang: String, tgtLang: String): String {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return ""
        return "$srcLang $tgtLang $normalized"
    }

    /**
     * Постпроцессинг результата перевода (извлечение текста).
     */
    fun postprocess(text: String): String = text.trim()

    /**
     * NFC-нормализация и минимальная очистка текста.
     */
    private fun normalize(text: String): String {
        val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
        // Схлопываем множественные пробелы и убираем краевые.
        return nfc.replace(Regex("\\s+"), " ").trim()
    }
}
