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
 * текста, либо перевод строки. Не считаются границами:
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
        // Английские распространённые.
        "mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "vs", "etc",
        "inc", "ltd", "co", "no", "fig", "eg", "ie", "e", "i",
    )

    /**
     * Разбить текст на предложения.
     * @return список непустых сегментов в исходном порядке; пустой список для
     *         пустого ввода.
     */
    fun split(text: String): List<String> {
        val segments = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            current.append(c)
            val boundary = c == '\n' ||
                (
                    c in terminators && (i + 1 == text.length || text[i + 1].isWhitespace()) &&
                        !(c == '.' && isAbbreviation(text, i))
                    )
            if (boundary) {
                val segment = current.toString().trim()
                if (segment.isNotEmpty()) segments.add(segment)
                current.setLength(0)
            }
            i++
        }
        current.toString().trim().let { if (it.isNotEmpty()) segments.add(it) }
        return segments
    }

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
