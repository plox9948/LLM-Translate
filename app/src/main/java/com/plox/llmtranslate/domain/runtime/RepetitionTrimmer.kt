package com.plox.llmtranslate.domain.runtime

/**
 * Пост-обработка повторов декодера IndicTrans2 (issue #28 п.5, issue #30).
 *
 * Дефект IndicTrans2: декодер может полностью повторять сгенерированный перевод
 * («Welcome» → «स्वागत छ स्वागत छ ।»; «Hello» → «नमस्कार , नमस्कार ।»;
 * «नमस्कार» → «Hello , Hello .»), дальше модель корректно останавливается
 * на «.»/«।» + EOS. Штраф в логитах (repetition_penalty, как у Marian)
 * IndicTrans2 меняет качество переводов, поэтому дедупликация выполняется
 * post-processing по финальному тексту.
 *
 * Семантика (зафиксирована тестами RepetitionTrimmerTest).
 *
 * Правило 1 — многословный блок (issue #28, неизменно):
 * - оперирует словами (разделение по пробельным символам): повторы внутри
 *   слова («частичный повтор» части слова) не затрагиваются;
 * - схлопываются только подряд идущие повторы блоков от 2 РАЗЛИЧНЫХ слов
 *   с ТОЧНЫМ совпадением токенов (прилипшая пунктуация различает слова);
 * - подряд идущее повторение блока k раз (k >= 2) схлопывается в одно
 *   вхождение, остаток текста (хвостовая «।», продолжение фразы) остаётся на месте;
 * - при нескольких кандидатах выбирается блок с максимальным числом
 *   дублируемых токенов, при равенстве — ближайший к хвосту (циклы декодера
 *   сидят в хвосте вывода);
 * - известный компромисс: легитимное подряд идущее повторение многословной
 *   фразы («thank you thank you») тоже схлопнется — редкий случай для вывода
 *   MT, перекрытый надёжным удалением артефактов декодера.
 *
 * Правило 2 — хвостовой дубль ОДНОГО слова (issue #30):
 * - сравнение слов нормализованное: без ведущей/замыкающей пунктуации
 *   (буквы/цифры по Unicode) и БЕЗ учёта регистра;
 * - срабатывает ровно на ДВА вхождения одного слова, где второе вхождение —
 *   последний «словесный» токен текста: после него идут только пунктуационные
 *   токены, а между вхождениями — только пунктуационные токены (второе
 *   вхождение может быть отделено запятой: «Hello , Hello .»);
 * - обязателен хотя бы один знак препинания между вхождениями (разделитель
 *   или пунктуация при первом вхождении) или у второго вхождения/после него —
 *   без пунктуации это легитимная эмфаза и правило НЕ трогает её:
 *   «very very good» (после второго вхождения идёт продолжение фразы),
 *   «да да», «да да да», «X X X» — остаются как есть;
 * - при схлопывании остаются первое вхождение (с его пунктуацией как есть),
 *   пунктуация снятая со второго вхождения (если была) и хвостовые
 *   пунктуационные токены; разделительные токены между вхождениями удаляются:
 *   «Hello , Hello .» → «Hello .», «नमस्कार , नमस्कार ।» → «नमस्कार ।»,
 *   «Привет, привет .» → «Привет, .».
 *
 * Общие свойства:
 * - правила применяются итеративно до фиксации (цепные дубли схлопываются
 *   целиком), поэтому триммер идемпотентен: trim(trim(x)) == trim(x);
 * - текст без повторов возвращается дословно; после схлопывания слова
 *   соединяются одинарными пробелами.
 */
internal object RepetitionTrimmer {

    /** Разделитель слов: компилируется один раз (вызов после каждого декада). */
    private val WHITESPACE = Regex("\\s+")

    /**
     * TODO(стоимость): [findDuplicate] — перебор start×period, O(n²) по числу
     * слов вывода (итеративно, цепные дубли). Для декодов ≤256 токенов это
     * доли миллисекунд; при росте лимита генерации — переписать на
     * линейный/суффиксный поиск повторов.
     */

    /** Минимальная длина дублируемого блока в словах (правило 1). */
    private const val MIN_BLOCK_WORDS = 2

    /**
     * Убрать повторения декодера: подряд идущие повторы многословных блоков
     * (правило 1) и хвостовой дубль одного слова с пунктуацией (правило 2).
     *
     * @param text финальный декодированный текст перевода.
     * @return текст без дублирующихся блоков; без повторов — исходная строка.
     */
    fun trim(text: String): String {
        // Минимум для любого правила — два словесных токена
        // («Hello, Hello.» — даже два токена с прилипшей пунктуацией).
        val words = text.trim().split(WHITESPACE)
        if (words.size < 2) return text

        var current = words
        var changed = false
        while (true) {
            val dup = findDuplicate(current)
            if (dup != null) {
                current =
                    current.subList(0, dup.start + dup.period) +
                        current.subList(dup.start + dup.period * dup.times, current.size)
                changed = true
                continue
            }
            val single = findSingleDuplicate(current) ?: break
            current =
                current.subList(0, single.first + 1) +
                    (if (single.punctTail.isNotEmpty()) listOf(single.punctTail) else emptyList()) +
                    current.subList(single.second + 1, current.size)
            changed = true
        }
        return if (changed) current.joinToString(" ") else text
    }

    /**
     * Найденное подряд идущее повторение: блок words[start .. start+period)
     * повторяется [times] раз подряд (times >= 2).
     */
    private class Duplicate(val start: Int, val period: Int, val times: Int) {
        val removedWords: Int get() = (times - 1) * period
    }

    /**
     * Хвостовой дубль одного слова (правило 2): вхождения
     * words[first] и words[second]; между ними и после [second] — только
     * пунктуационные токены. [punctTail] — замыкающая пунктуация токена
     * второго вхождения, переносимая отдельным токеном («Hello.» → «.»).
     */
    private class SingleDuplicate(val first: Int, val second: Int, val punctTail: String)

    /**
     * Поиск кандидата с максимумом удаляемых слов (при равенстве — к хвосту).
     */
    private fun findDuplicate(words: List<String>): Duplicate? {
        var best: Duplicate? = null
        for (start in words.indices) {
            val maxPeriod = (words.size - start) / 2
            for (period in MIN_BLOCK_WORDS..maxPeriod) {
                // Блок из одного и того же слова в любом количестве —
                // однословная эмфаза, не артефакт декодера: не трогаем.
                val block = words.subList(start, start + period)
                if (block.all { it == block.first() }) continue
                // Максимальный матчинг слов со сдвигом period от позиции start:
                // длина L подряд совпадающих слов даёт полных копий 1 + L/period.
                var len = 0
                while (start + period + len < words.size &&
                    words[start + len] == words[start + period + len]
                ) {
                    len++
                }
                val times = 1 + len / period
                if (times >= 2) {
                    val candidate = Duplicate(start, period, times)
                    val cur = best
                    if (cur == null || candidate.removedWords > cur.removedWords ||
                        (candidate.removedWords == cur.removedWords && candidate.start > cur.start)
                    ) {
                        best = candidate
                    }
                }
            }
        }
        return best
    }

    /**
     * Поиск хвостового дубля одного слова (правило 2): второе вхождение —
     * последний словесный токен, первое — ближайшее словесное вхождение
     * слева, отделённое только пунктуацией. Требование пунктуации отличает
     * артефакт декодера от легитимной эмфазы («да да», «X X X»).
     */
    private fun findSingleDuplicate(words: List<String>): SingleDuplicate? {
        val second = words.indexOfLast { bare(it).isNotEmpty() }
        if (second < 1) return null
        var first = second - 1
        while (first >= 0 && bare(words[first]).isEmpty()) first--
        if (first < 0) return null

        val bare1 = bare(words[first])
        if (bare1.isEmpty() || bare1.lowercase() != bare(words[second]).lowercase()) {
            return null
        }
        // Пунктуация обязательна: при первом вхождении, разделителем между
        // вхождениями или у второго вхождения/после него.
        val hasPunctuation =
            tailPunctuation(words[first]).isNotEmpty() ||
                words.subList(first + 1, words.size)
                    .any { word -> word != bare(word) }
        if (!hasPunctuation) return null
        return SingleDuplicate(first, second, tailPunctuation(words[second]))
    }

    /** Слово без ведущей/замыкающей пунктуации (пусто у чисто пунктуационного токена). */
    private fun bare(word: String): String {
        var a = 0
        var b = word.length
        while (a < b && !word[a].isLetterOrDigit()) a++
        while (b > a && !word[b - 1].isLetterOrDigit()) b--
        return word.substring(a, b)
    }

    /** Замыкающая пунктуация токена (после последнего буквы/цифры; пусто если букв нет). */
    private fun tailPunctuation(word: String): String {
        var b = word.length
        while (b > 0 && !word[b - 1].isLetterOrDigit()) b--
        return word.substring(b)
    }
}
