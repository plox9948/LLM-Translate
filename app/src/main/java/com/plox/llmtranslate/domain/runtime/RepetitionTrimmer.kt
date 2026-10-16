package com.plox.llmtranslate.domain.runtime

/**
 * Пост-обработка повторов декодера IndicTrans2 (issue #28, пункт 5).
 *
 * Дефект IndicTrans2: декодер может полностью повторять сгенерированную фразу
 * («Welcome» → «स्वागत छ स्वागत छ ।»): в последовательности слов периодический
 * блок, дальше модель корректно останавливается на «।» + EOS. Штраф в логитах
 * (repetition_penalty, как у Marian) IndicTrans2 меняет качество переводов,
 * поэтому дедупликация выполняется post-processing по финальному тексту.
 *
 * Семантика (зафиксирована тестами RepetitionTrimmerTest):
 * - оперирует словами (разделение по пробельным символам): повторы внутри
 *   слова («частичный повтор» части слова) не затрагиваются;
 * - схлопываются только подряд идущие повторы блоков от 2 РАЗЛИЧНЫХ слов;
 *   однословные повторы («very very good», «hi hi», «да да да») всегда
 *   сохраняются — это легитимная эмфаза, и полное дублирование одного слова
 *   тоже не трогается;
 * - подряд идущее повторение блока k раз (k >= 2) схлопывается в одно
 *   вхождение, остаток текста (хвостовая «।», продолжение фразы) остаётся на месте;
 * - при нескольких кандидатах выбирается блок с максимальным числом
 *   дублируемых токенов, при равенстве — ближайший к хвосту (циклы декодера
 *   сидят в хвосте вывода);
 * - известный компромисс: легитимное подряд идущее повторение многословной
 *   фразы («thank you thank you») тоже схлопнется — редкий случай для вывода
 *   MT, перекрытый надёжным удалением артефактов декодера;
 * - текст без повторов возвращается дословно; после схлопывания слова
 *   соединяются одинарными пробелами.
 */
internal object RepetitionTrimmer {

    /** Минимальная длина дублируемого блока в словах (1 слово — не трогается). */
    private const val MIN_BLOCK_WORDS = 2

    /**
     * Убрать подряд идущие повторения многословных блоков.
     *
     * @param text финальный декодированный текст перевода.
     * @return текст без дублирующихся блоков; без повторов — исходная строка.
     */
    fun trim(text: String): String {
        // Минимум для дублирования блока из 2 слов — 4 слова.
        val words = text.trim().split(Regex("\\s+"))
        if (words.size < 2 * MIN_BLOCK_WORDS) return text

        var current = words
        var changed = false
        while (true) {
            val dup = findDuplicate(current) ?: break
            current =
                current.subList(0, dup.start + dup.period) +
                    current.subList(dup.start + dup.period * dup.times, current.size)
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
}
