package com.plox.llmtranslate.domain.model

/**
 * Разбиение текста на предложения для перевода по частям.
 *
 * Marian-модели (opus-mt) обучены на одиночных предложениях: на длинном
 * многосоставном входе tiny-модель выдаёт ранний EOS и теряет хвост текста.
 * Штатный режим использования — перевод каждого предложения отдельно со
 * склейкой результата.
 *
 * Граница предложения — `.`/`!`/`?`/`…` за которыми следует пробел или конец
 * текста, либо перевод строки; либо данда `।`/`॥` (U+0964/U+0965) — безусловная
 * граница без требования пробела (issue #63). Не считаются границами:
 * - точка без пробела после (десятичная «3.14», «Mr.Smith»);
 * - точка после известного сокращения ([ABBREVIATIONS]) или одиночной
 *   заглавной буквы-инициала («А. С. Пушкин», «Mr. Smith») — не резать
 *   обломок отдельно. Эвристика «короткое слово 1–3 буквы» (issue #40)
 *   глушила настоящие границы («Thank you. Good night.») — вместо неё
 *   точечный список.
 */
object SentenceSplitter {

    /** Символы, заканчивающие предложение. */
    private val terminators = charArrayOf('.', '!', '?', '…')

    /** Данда — завершитель предложения деванагари (U+0964). */
    private const val DANDA = '\u0964'

    /** Двойная данда (U+0965) — тоже terminator; как одиночный символ. */
    private const val DOUBLE_DANDA = '\u0965'

    /**
     * Сокращения, точка в которых не заканчивает предложение
     * (слово перед точкой в нижнем регистре, без завершающей точки).
     * Однобуквенные русские сокращения («г.», «т.д.», «т.п.», «д.», «с.»)
     * перечислены явно: строчная одиночная буква — обычное слово («я.»),
     * инициалом считается только заглавная.
     */
    private val ABBREVIATIONS = setOf(
        // Русские распространённые.
        "г", "т", "д", "с", "п", "в", "пр", "ул", "см", "бул", "пер", "проф", "доц", "им",
        // Английские распространённые. «No.» и «etc.» сюда НЕ входят:
        // «No.» — полноценная граница предложения, «etc.» почти всегда
        // конец предложения (issue #40).
        "mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "vs",
        "inc", "ltd", "co", "fig", "eg", "ie", "e", "i",
    )

    /**
     * Разбить текст на предложения.
     * @return список непустых сегментов в исходном порядке; пустой список для
     *         пустого ввода.
     */
    fun split(text: String): List<String> = splitWithSeparators(text).first

    /**
     * Разбить текст на предложения вместе с разделителями между ними
     * (ревью раунд 9: склейка перевода через пробел теряла переводы строк
     * исходника).
     * @return пара (сегменты, разделители); разделителей ровно
     *         `сегменты.size - 1`: "\n" если граница между сегментами в [text]
     *         содержала перевод строки (сам закрывающий символ или зазор),
     *         иначе " ".
     */
    fun splitWithSeparators(text: String): Pair<List<String>, List<String>> {
        val segments = ArrayList<String>()
        val separators = ArrayList<String>()
        val current = StringBuilder()
        // Позиции сырых фрагментов в исходном тексте: по зазору между
        // закрытым сегментом и началом следующего определяется разделитель.
        var rawStart = 0
        var prevRawEnd = -1
        // Граница-перевод строки без пунктуации закрывает сырой сегмент СВОИМ
        // символом, поэтому '\n' остаётся внутри закрытого фрагмента, а зазор
        // пуст — без этого флага одиночный перевод строки терялся (дефект:
        // «строка1\nстрока2» склеивался пробелом).
        var prevClosedByNewline = false

        fun closeRawSegment(rawEnd: Int) {
            val segment = current.toString().trim()
            if (segment.isEmpty()) return
            if (segments.isNotEmpty()) {
                val gap = text.substring(prevRawEnd, rawStart)
                val hasNewline = prevClosedByNewline || gap.contains('\n')
                separators.add(if (hasNewline) "\n" else " ")
            }
            segments.add(segment)
            prevRawEnd = rawEnd
            prevClosedByNewline = rawEnd > rawStart && text[rawEnd - 1] == '\n'
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (current.isEmpty()) rawStart = i
            current.append(c)
            val boundary = c == '\n' || isDanda(c) ||
                (
                    c in terminators && (i + 1 == text.length || text[i + 1].isWhitespace()) &&
                        !(c == '.' && isAbbreviation(text, i))
                    )
            if (boundary) {
                var rawEnd = i + 1
                if (isDanda(c)) {
                    // Последовательности ।/॥ схлопываются в одну границу:
                    // идущие подряд данда принадлежат закрытому фрагменту
                    // и не открывают новый сегмент (issue #63).
                    while (rawEnd < text.length && isDanda(text[rawEnd])) rawEnd++
                }
                closeRawSegment(rawEnd)
                current.setLength(0)
                i = rawEnd
            } else {
                i++
            }
        }
        closeRawSegment(text.length)
        return segments to separators
    }

    /**
     * Данда (।/॥) — безусловная граница предложения: в отличие от `.` не
     * требует пробела или конца текста после себя и не участвует в проверке
     * сокращений.
     */
    private fun isDanda(c: Char): Boolean = c == DANDA || c == DOUBLE_DANDA

    /**
     * Точка в позиции [dotIndex] стоит внутри сокращения: после одиночной
     * заглавной буквы-инициала («A.», «Г.») или после слова из явного списка
     * [ABBREVIATIONS] — границей предложения не является.
     */
    private fun isAbbreviation(text: String, dotIndex: Int): Boolean {
        var j = dotIndex - 1
        while (j >= 0 && text[j].isLetter()) {
            j--
        }
        // Одиночная заглавная буква — инициал («А. С. Пушкин»); строчная —
        // обычное слово («это я. ты где?») и границей как раз является.
        if (dotIndex - j - 1 == 1 && text[j + 1].isUpperCase()) return true
        return text.substring(j + 1, dotIndex).lowercase() in ABBREVIATIONS
    }
}
