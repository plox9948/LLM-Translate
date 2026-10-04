package com.plox.llmtranslate.domain.runtime

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.BufferedReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Токенизатор SentencePiece BPE для NLLB.
 *
 * Загружает словарь и правила слияния из assets, токенизирует текст
 * в последовательность token-id и обратно.
 *
 * Файлы в assets:
 * - nllb_vocab.txt: строки "piece_id piece"
 * - nllb_merges.txt: строки "rank piece1 piece2"
 */
@Singleton
class NllbTokenizer @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var vocab: Map<String, Int> = emptyMap()
    private var invVocab: Map<Int, String> = emptyMap()

    // Ранг слияния по паре pieces: поиск O(1) вместо линейного по ~514k записей.
    private var mergeRanks: Map<Pair<String, String>, Int> = emptyMap()
    private var loaded = false

    // Специальные токены NLLB.
    private val langTokens = mapOf(
        "ru" to "<|ru|>",
        "en" to "<|en|>",
        "ne" to "<|ne|>"
    )
    private val eosToken = "</s>"
    private val padToken = "<s>"

    /**
     * Загрузить словарь и правила слияния из assets.
     */
    fun ensureLoaded() {
        if (loaded) return
        try {
            loadVocab()
            loadMerges()
            loaded = true
            Timber.d("NLLB токенизатор загружен: ${vocab.size} токенов")
        } catch (e: Exception) {
            Timber.e(e, "Не удалось загрузить NLLB токенизатор")
        }
    }

    /**
     * Токенизировать текст в token-id.
     */
    fun encode(text: String): List<Int> {
        ensureLoaded()
        if (vocab.isEmpty()) return emptyList()

        val pieces = bpeTokenize(text)
        return pieces.mapNotNull { vocab[it] }
    }

    /**
     * Детокенизировать token-id в текст.
     */
    fun decode(tokenIds: List<Int>): String {
        ensureLoaded()
        if (invVocab.isEmpty()) return ""

        val pieces = tokenIds.mapNotNull { invVocab[it] }
        return pieces.joinToString("") { unescapePiece(it) }
            .replace(Regex("<\\|[^|]+\\|>"), "")
            .replace(eosToken, "")
            .replace(padToken, "")
            .trim()
    }

    /**
     * ID языкового токена.
     */
    fun langTokenId(lang: String): Int? {
        ensureLoaded()
        val token = langTokens[lang] ?: return null
        return vocab[token]
    }

    fun eosId(): Int? = vocab[eosToken]

    /**
     * BPE-токенизация текста.
     */
    private fun bpeTokenize(text: String): List<String> {
        // Разбиваем на символы, сохраняя пробелы как "▁".
        val chars = text.map { c ->
            if (c.isWhitespace()) "▁" else c.toString()
        }.toMutableList()

        if (chars.isEmpty()) return emptyList()

        // Итеративно применяем слияния.
        var current = chars.toMutableList()
        while (current.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestIdx = -1

            for (i in 0 until current.size - 1) {
                val pair = current[i] to current[i + 1]
                val rank = mergeRank(pair.first, pair.second)
                if (rank != -1 && rank < bestRank) {
                    bestRank = rank
                    bestIdx = i
                }
            }

            if (bestIdx == -1) break

            // Применяем слияние.
            val merged = current[bestIdx] + current[bestIdx + 1]
            current.removeAt(bestIdx + 1)
            current[bestIdx] = merged
        }

        return current
    }

    private fun mergeRank(piece1: String, piece2: String): Int =
        mergeRanks[piece1 to piece2] ?: -1

    /**
     * Обработка спецсимволов SentencePiece (▁ -> пробел).
     */
    private fun unescapePiece(piece: String): String {
        return piece.replace("▁", " ")
    }

    private fun loadVocab() {
        val map = HashMap<String, Int>()
        context.assets.open("nllb_vocab.txt").bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val id = parts[0].toIntOrNull()
                    val piece = parts[1]
                    if (id != null && piece.isNotEmpty()) {
                        map[piece] = id
                    }
                }
                line = reader.readLine()
            }
        }
        vocab = map
        invVocab = map.entries.associate { (k, v) -> v to k }
    }

    private fun loadMerges() {
        // Ранг = позиция первой строки слияния в файле (как в прежнем списке).
        val ranks = HashMap<Pair<String, String>, Int>()
        context.assets.open("nllb_merges.txt").bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 3) {
                    ranks.putIfAbsent(parts[1] to parts[2], ranks.size)
                }
                line = reader.readLine()
            }
        }
        mergeRanks = ranks
    }
}
