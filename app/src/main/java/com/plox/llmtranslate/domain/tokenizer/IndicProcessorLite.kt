package com.plox.llmtranslate.domain.tokenizer

import java.text.Normalizer

/**
 * Минимальный порт IndicProcessor для направлений npi_Deva ⇄ eng_Latn.
 *
 * Препроцессинг (порядок операций фиксирован):
 * 1. NFC-нормализация + схлопывание пробелов + trim;
 * 2. punc_norm для обоих исходных языков: кавычки/тире к ASCII;
 * 3. только для исходника eng_Latn — защищённая расстановка пробелов
 *    вокруг пунктуации (Moses-формат обучения модели: в ONNX-экспорте токены
 *    голой пунктуации шире эмбеддингов и превращаются в <s> — циклы декодера);
 * 4. вставка языкового префикса "src tgt " (после punc_norm — префикс
 *    пунктуацией не затрагивается).
 * Для en→npi выход уже в Деванагари (трансслитерация не нужна).
 *
 * Детокенизация [postprocess] — trivial_detokenize по образцу
 * IndicTransToolkit: убирает лишние пробелы вокруг пунктуации на выходе
 * модели в зависимости от целевого языка. Идемпотентна.
 *
 * Используется только для направлений с ne ⇄ en (IndicTrans2 не поддерживает ru).
 */
object IndicProcessorLite {

    /** Flores-код непальского (Деванагари). */
    const val NEPALI = "npi_Deva"

    /** Flores-код английского. */
    const val ENGLISH = "eng_Latn"

    /**
     * Одиночные символы, которые в английском исходнике отделяются пробелами
     * (Moses pnorm). Двойные кавычки включены, одиночные не трогаются.
     */
    private val spacedPunct = setOf(',', '.', '!', '?', ';', ':', '(', ')', '[', ']', '"', '…')

    /** Кавычки, заменяемые на ASCII-двойную кавычку (апостроф U+2019 НЕ трогается). */
    private val quoteChars = setOf('«', '»', '„', '“', '”', '`', '´')

    /** Тире (en/em dash), заменяемые на ASCII-дефис. */
    private val dashChars = setOf('–', '—')

    /**
     * URL-подобные токены (со схемой или www.): внутренность не разрывается,
     * круглые/квадратные скобки и кавычки в класс не входят — они остаются
     * снаружи токена и разделяются пробелами.
     */
    private val urlLikeRegex = Regex("""(?:(?:https?|ftp)://|www\.)[A-Za-z0-9._~:/?#%@${'$'}&+=-]*""")

    /** Email-токены: внутренность (в т.ч. точки и @) не разрывается. */
    private val emailRegex = Regex("""[A-Za-z][A-Za-z0-9._%+-]*@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+""")

    /** Хвостовые пунктуационные символы, отсекаемые от URL-токена («example.com.»). */
    private val trailingUrlPunct = setOf('.', ',', ';', ':', '!', '?')

    /**
     * Препроцессинг текста перед переводом.
     * @param text исходный текст
     * @param srcLang flores-код исходного языка
     * @param tgtLang flores-код целевого языка
     * @return текст с языковым префиксом "src tgt " и punc_norm-обработкой
     *         (для eng_Latn — с разделённой пунктуацией)
     */
    fun preprocess(text: String, srcLang: String, tgtLang: String): String {
        var prepared = normalize(text)
        if (prepared.isEmpty()) return ""
        // Шаг 2: кавычки/тире — для обоих исходных языков.
        prepared = normalizeQuotesAndDashes(prepared)
        // Шаг 3: расстановка пробелов вокруг пунктуации — только английский исходник.
        // Для npi_Deva пробелы вокруг ।/, не расставляются: замерено, что на
        // выходе модели не меняет.
        if (srcLang == ENGLISH) {
            prepared = punctuateEnglish(prepared)
        }
        if (prepared.isEmpty()) return ""
        // Шаг 4: префикс вставляется последним — punc_norm его не затрагивает.
        return "$srcLang $tgtLang $prepared"
    }

    /**
     * Детокенизация (trivial_detokenize по образцу IndicTransToolkit):
     * убирает пробелы, оставленные Moses-раздельной пунктуацией на выходе модели.
     * - eng_Latn: пробелы перед `.` `,` `?` `!` `;` `:` `)` `%`, после `(` `[`;
     *   склейка пробелов внутри кавычек; пробел после `.` `,` `:` сохраняется,
     *   если за ним цифра («3 . 5» → «3.5», «5 : 30» → «5:30»);
     * - npi_Deva: пробелы перед `।` `॥` `,` `?` `!` `;` `:` `)`; после `(`.
     *   Пробел ПОСЛЕ данды сохраняется — как и после точки в латинице, он
     *   разделяет предложения (эталонный trivial_detokenize убирает пробелы
     *   только перед пунктуацией).
     * Операция идемпотентна: повторный вызов результата не меняет.
     * @param text текст на выходе модели
     * @param tgtLang flores-код целевого языка (определяет набор правил)
     */
    fun postprocess(text: String, tgtLang: String): String =
        if (tgtLang == NEPALI) detokenizeDevanagari(text) else detokenizeLatin(text)

    /**
     * NFC-нормализация и минимальная очистка текста.
     */
    private fun normalize(text: String): String {
        val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
        // Схлопываем множественные пробелы и убираем краевые.
        return nfc.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Шаг punc_norm A (оба исходных языка): «« » „ “ ” ` ´» → `"`,
     * «– —» → `-`. Апостроф `’` и дефисы внутри слов не трогаются.
     */
    private fun normalizeQuotesAndDashes(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch in quoteChars -> sb.append('"')
                ch in dashChars -> sb.append('-')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Шаг punc_norm B (только eng_Latn). Порядок операций:
     * 1) `...` (3+ точек) схлопнуть в `…`;
     * 2) пометить защищённые ranges (URL/похожие на URL и email-токены);
     * 3) посимвольный проход: перед `ch` вставлять пробел, если предыдущий
     *    выведенный символ не пробел, и после `ch`, если следующий символ не
     *    пробел/конец; внутри защищённых ranges и в защитах ([needsSpacing])
     *    пробелы не вставляются;
     * 4) финальное схлопывание пробелов + trim.
     */
    private fun punctuateEnglish(text: String): String {
        val collapsed = text.replace(Regex("\\.{3,}"), "…")
        val protectedPos = BooleanArray(collapsed.length)
        markProtectedRanges(collapsed, urlLikeRegex, protectedPos)
        markProtectedRanges(collapsed, emailRegex, protectedPos)

        val sb = StringBuilder(collapsed.length + 16)
        for (i in collapsed.indices) {
            val ch = collapsed[i]
            if (ch in spacedPunct && !protectedPos[i] && needsSpacing(collapsed, i)) {
                if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                sb.append(ch)
                if (i + 1 < collapsed.length && collapsed[i + 1] != ' ') sb.append(' ')
            } else {
                sb.append(ch)
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Пометить позиции защищённых токенов. У URL-совпадений отсекаются
     * хвостовые пунктуационные символы («Visit example.com.» — финальная точка
     * остаётся незащищённой и отделяется, как конец предложения).
     */
    private fun markProtectedRanges(text: String, regex: Regex, flags: BooleanArray) {
        for (match in regex.findAll(text)) {
            val start = match.range.first
            var end = match.range.last + 1
            while (end - 1 > start && text[end - 1] in trailingUrlPunct) end--
            for (j in start until end) flags[j] = true
        }
    }

    /**
     * Защиты расстановки пробелов (true — пробелы вставлять нужно):
     * - точка между цифрами (десятичные «3.5», даты «22.10.2026»);
     * - точка между буквами (домены/URL «example.com», «host/path.a» при букве
     *   слева и справа не встречается — слэш вне набора разделения);
     * - точка после одиночной буквы-инициала («A. S. Pushkin», «J.») с пробелом
     *   или концом строки справа;
     * - двоеточие между цифрами («5:30», «12:34:56»);
     * - запятая между цифрами (тысячные «1,000»).
     */
    private fun needsSpacing(text: String, index: Int): Boolean {
        val ch = text[index]
        val prev = if (index > 0) text[index - 1] else null
        val next = if (index + 1 < text.length) text[index + 1] else null
        when (ch) {
            '.' -> {
                if (prev != null && next != null && prev.isDigit() && next.isDigit()) return false
                if (prev != null && next != null && prev.isLetter() && next.isLetter()) return false
                val prevIsolated = prev != null && prev.isLetter() &&
                    (index < 2 || !text[index - 2].isLetterOrDigit())
                if (prevIsolated && (next == null || next == ' ')) return false
            }
            ':', ',' -> {
                if (prev != null && next != null && prev.isDigit() && next.isDigit()) return false
            }
        }
        return true
    }

    /** Пробел перед пунктуацией (английский выход). */
    private val spaceBeforePunctEn = Regex("[ \t]+([.,?!;:)%\\]])")

    /** Пробел после открывающей скобки. */
    private val spaceAfterOpenParen = Regex("""([(\[])[ \t]+""")

    /** Склейка «цифра пробел пунктуация пробел цифра» внутри чисел. */
    private val joinNumericPunct = Regex("(?<=\\d)([.,:;])[ \t]+(?=\\d)")

    /**
     * Пара кавычек с внутренними пробелами: `«" text "»` (кавычки разделены
     * Moses-пробелами) → склейка внутренних пробелов. Парное правило вместо
     * двух одиночных: одиночное не различает открывающую/закрывающую кавычку
     * и резало бы межсловный пробел перед открывающей.
     */
    private val quotePair = Regex("""(?<![^\s])"[ \t]+([^"]+?)[ \t]+"(?![^\s])""")

    /**
     * Детокенизация латиницы (eng_Latn). Кавычки обрабатываются раньше правил
     * пунктуации: фраза `" text " .` сначала склеивает кавычки, потом точку.
     */
    private fun detokenizeLatin(text: String): String {
        var out = quotePair.replace(text, "\"$1\"")
        out = spaceBeforePunctEn.replace(out, "$1")
        out = spaceAfterOpenParen.replace(out, "$1")
        out = joinNumericPunct.replace(out, "$1")
        return out
    }

    /** Пробел перед пунктуацией деванагари (эталонный trivial_detokenize: только перед). */
    private val spaceBeforePunctNe = Regex("[ \t]+(?=[।॥,?!;:)])")

    /**
     * Детокенизация Деванагари (npi_Deva): «नमस्ते । धन्यवाद ।» →
     * «नमस्ते। धन्यवाद।». Пробелы после `।` `?` `!` `,` сохраняются —
     * межсловные границы/границы предложений.
     */
    private fun detokenizeDevanagari(text: String): String {
        var out = spaceBeforePunctNe.replace(text, "")
        out = spaceAfterOpenParen.replace(out, "$1")
        return out
    }
}
