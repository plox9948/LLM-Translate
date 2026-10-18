package com.plox.llmtranslate

import java.text.Normalizer

/**
 * Character n-gram F-score (chrF, бета=2, символьные n-граммы n=1..6) — issue #61.
 *
 * Нормализация перед сравнением: NFC + схлопывание пробелов + trim.
 * Реализация упрощённая (без сент-разбиения и order-0 слов): F-скор считается
 * для одной пары предложений и усредняется по порядкам n-грамм; этого
 * достаточно для пороговой регрессии golden-корпуса. Возвращает 0..1.
 */
internal object ChrF {

    private const val N_MAX = 6
    private const val BETA_SQ = 4.0 // бета=2 — recall весомее precision, как в каноне chrF

    /** NFC-нормализация + схлопывание пробелов + trim. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * chrF для одной пары (гипотеза, референс). Пустая строка — 0.0;
     * порядки n, где у одной из сторон нет n-грамм, в среднее не входят.
     */
    fun score(hypothesis: String, reference: String): Double {
        val hyp = normalize(hypothesis)
        val ref = normalize(reference)
        if (hyp.isEmpty() || ref.isEmpty()) return 0.0
        var sum = 0.0
        var orders = 0
        for (n in 1..N_MAX) {
            val hgrams = charNgrams(hyp, n)
            val rgrams = charNgrams(ref, n)
            if (hgrams.isEmpty() || rgrams.isEmpty()) continue
            val matches = overlapCount(hgrams, rgrams).toDouble()
            val precision = matches / hgrams.values.sum().toDouble()
            val recall = matches / rgrams.values.sum().toDouble()
            if (precision == 0.0 || recall == 0.0) continue
            sum += (1.0 + BETA_SQ) * precision * recall / (BETA_SQ * precision + recall)
            orders++
        }
        return if (orders == 0) 0.0 else sum / orders
    }

    private fun charNgrams(text: String, n: Int): Map<String, Int> {
        val grams = HashMap<String, Int>()
        if (text.length < n) return grams
        for (i in 0..text.length - n) {
            grams.merge(text.substring(i, i + n), 1) { a, b -> a + b }
        }
        return grams
    }

    /** Пересечение мультимножеств: сколько n-грамм общие с учетом кратности. */
    private fun overlapCount(h: Map<String, Int>, r: Map<String, Int>): Int {
        var total = 0
        for ((gram, count) in h) total += minOf(count, r[gram] ?: 0)
        return total
    }
}
