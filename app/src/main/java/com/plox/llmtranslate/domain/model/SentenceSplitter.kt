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
 * - точка после короткого слова (1–3 буквы: инициалы, «г.», «т.д.» — не
 *   переводить обломок отдельно).
 */
object SentenceSplitter {

    /** Символы, заканчивающие предложение. */
    private val terminators = charArrayOf('.', '!', '?', '…')

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
     * Точка в позиции [dotIndex] стоит после короткого слова-сокращения
     * (1–3 буквы подряд) — границей предложения не является.
     */
    private fun isAbbreviation(text: String, dotIndex: Int): Boolean {
        var len = 0
        var j = dotIndex - 1
        while (j >= 0 && (text[j].isLetter() || text[j].isDigit())) {
            len++
            j--
        }
        return len in 1..3
    }
}
