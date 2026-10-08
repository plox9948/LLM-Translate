package com.plox.llmtranslate.domain.tokenizer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import timber.log.Timber
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Base64

/**
 * Чистый Kotlin-парсер HF tokenizer.json.
 *
 * Поддерживает модели:
 * - Unigram (Marian / opus-mt_tiny): vocab — список пар [token, score].
 * - BPE (IndicTrans2): vocab — объект {token: id}, merges — список пар.
 *
 * Пре-токенизаторы: Metaspace (основной), Whitespace, ByteLevel.
 * Нормализация: null, NFC/NFD, Sequence (вкл. Precompiled charsmap).
 *
 * Ленивая загрузка, encode/decode/eosId.
 * Читает из файла модели, а не из assets.
 */
class TokenizerJson {

    // Словарь.
    private var vocab: Map<String, Int> = emptyMap()
    private var invVocab: Map<Int, String> = emptyMap()
    private var addedTokenIdsByContent: Map<String, Int> = emptyMap()
    private var addedSpecialIds: Set<Int> = emptySet()
    private var unkId: Int? = null
    private var eosId: Int? = null
    private var loaded = false

    // Модель.
    private var modelType: String = ""
    private var merges: List<Pair<String, String>> = emptyList()
    private var mergeRanks: Map<Pair<String, String>, Int> = emptyMap()
    private var unigramScores: Map<String, Float> = emptyMap()

    // Пре-токенизатор (Metaspace).
    private var preTokenizerType: String = ""
    private var metaspaceReplacement: String = "▁"
    private var metaspacePrependAlways: Boolean = true

    // Декодер.
    private var decoderType: String = ""
    private var decoderReplacement: String = "▁"

    // Нормализатор.
    private var normalizer: TextNormalizer? = null

    /**
     * Загрузить tokenizer.json из файла.
     */
    fun load(tokenizerJsonPath: String) {
        if (loaded) return
        try {
            val file = File(tokenizerJsonPath)
            val root = Json.parseToJsonElement(file.readText(StandardCharsets.UTF_8)).jsonObject
            parseAddedTokens(root)
            parseNormalizer(root)
            parsePreTokenizer(root)
            parseDecoder(root)
            parseModel(root)
            loaded = true
            Timber.d("TokenizerJson загружен: ${vocab.size} токенов, модель=$modelType")
        } catch (e: Exception) {
            Timber.e(e, "Не удалось загрузить tokenizer.json: $tokenizerJsonPath")
        }
    }

    /**
     * Токенизировать текст в token-id.
     */
    fun encode(text: String): List<Int> {
        if (!loaded) return emptyList()
        val normalized = normalizer?.normalize(text) ?: text
        val words = normalized.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val ids = ArrayList<Int>(words.size * 2)
        for (word in words) {
            // Added-токены (языковые коды, спец-токены) — целиком по id.
            val specialId = addedTokenIdsByContent[word]
            if (specialId != null) {
                ids.add(specialId)
                continue
            }
            val piece = metaspaceReplacement + word
            for (token in tokenizePiece(piece)) {
                val id = vocab[token] ?: unkId
                if (id != null) ids.add(id)
            }
        }
        return ids
    }

    /**
     * Детокенизировать token-id в текст (skip-специальные).
     */
    fun decode(ids: List<Int>): String {
        if (!loaded) return ""
        val tokens = ids.mapNotNull { id ->
            if (addedSpecialIds.contains(id)) return@mapNotNull null
            val tok = invVocab[id] ?: return@mapNotNull null
            if (tok in SPECIAL_TOKEN_CONTENTS) return@mapNotNull null
            tok
        }
        val joined = tokens.joinToString("")
        val decoded = if (decoderType == "Metaspace") {
            joined.replace(decoderReplacement, " ")
        } else {
            joined
        }
        return decoded.trim()
    }

    /**
     * ID токена конца последовательности.
     */
    fun eosId(): Int? = eosId

    /**
     * ID токена по имени (языковые коды, спец-токены).
     */
    fun specialTokenId(name: String): Int? =
        addedTokenIdsByContent[name] ?: vocab[name]

    /**
     * Токенизировать один пре-токенизированный фрагмент (Unigram или BPE).
     */
    private fun tokenizePiece(piece: String): List<String> = when (modelType) {
        "Unigram" -> unigramTokenize(piece)
        "BPE" -> bpeTokenize(piece)
        else -> piece.map { it.toString() }
    }

    // --- Разбор JSON ---

    private fun parseAddedTokens(root: JsonObject) {
        val byContent = HashMap<String, Int>()
        val specialIds = HashSet<Int>()
        root["added_tokens"]?.jsonArray?.forEach { el ->
            val obj = el.jsonObject
            val id = (obj["id"] as? JsonPrimitive)?.intOrNull ?: return@forEach
            val content = (obj["content"] as? JsonPrimitive)?.contentOrNull ?: ""
            val isSpecial = (obj["special"] as? JsonPrimitive)?.booleanOrNull ?: true
            if (content.isNotEmpty()) {
                byContent[content] = id
                when (content) {
                    "</s>" -> eosId = id
                    "<unk>" -> unkId = id
                }
            }
            if (isSpecial) specialIds.add(id)
        }
        addedTokenIdsByContent = byContent
        addedSpecialIds = specialIds
    }

    private fun parseNormalizer(root: JsonObject) {
        val normEl = root["normalizer"] ?: run { normalizer = null; return }
        if (normEl !is JsonObject) {
            normalizer = null
            return
        }
        when ((normEl["type"] as? JsonPrimitive)?.contentOrNull) {
            "NFC" -> normalizer = TextNormalizer { Normalizer.normalize(it, Normalizer.Form.NFC) }
            "NFD" -> normalizer = TextNormalizer { Normalizer.normalize(it, Normalizer.Form.NFD) }
            "Sequence" -> {
                val parts = mutableListOf<TextNormalizer>()
                normEl["normalizers"]?.jsonArray?.forEach { el ->
                    val obj = el.jsonObject
                    when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
                        "NFC" -> parts.add(TextNormalizer { Normalizer.normalize(it, Normalizer.Form.NFC) })
                        "NFD" -> parts.add(TextNormalizer { Normalizer.normalize(it, Normalizer.Form.NFD) })
                        "Precompiled" -> {
                            val cn = parseCharsmap((obj["precompiled_charsmap"] as? JsonPrimitive)?.contentOrNull)
                            if (cn != null) parts.add(TextNormalizer { cn.normalize(it) })
                        }
                    }
                }
                normalizer = if (parts.isEmpty()) null else TextNormalizer { text ->
                    parts.fold(text) { acc, n -> n.normalize(acc) }
                }
            }
            "Precompiled" -> {
                normalizer = parseCharsmap((normEl["precompiled_charsmap"] as? JsonPrimitive)?.contentOrNull)
                    ?.let { cn -> TextNormalizer { cn.normalize(it) } }
            }
            else -> normalizer = null
        }
    }

    private fun parseCharsmap(b64: String?): CharsmapNormalizer? {
        if (b64.isNullOrBlank()) return null
        return try {
            CharsmapNormalizer(Base64.getDecoder().decode(b64))
        } catch (e: Exception) {
            Timber.w("Не удалось разобрать precompiled_charsmap")
            null
        }
    }

    private fun parsePreTokenizer(root: JsonObject) {
        val pt = root["pre_tokenizer"] as? JsonObject ?: return
        preTokenizerType = (pt["type"] as? JsonPrimitive)?.contentOrNull ?: ""
        if (preTokenizerType == "Metaspace") {
            metaspaceReplacement = (pt["replacement"] as? JsonPrimitive)?.contentOrNull ?: "▁"
            metaspacePrependAlways = (pt["prepend_scheme"] as? JsonPrimitive)?.contentOrNull == "always"
        }
    }

    private fun parseDecoder(root: JsonObject) {
        val dec = root["decoder"] as? JsonObject ?: return
        decoderType = (dec["type"] as? JsonPrimitive)?.contentOrNull ?: ""
        if (decoderType == "Metaspace") {
            decoderReplacement = (dec["replacement"] as? JsonPrimitive)?.contentOrNull ?: "▁"
        }
    }

    private fun parseModel(root: JsonObject) {
        val model = root["model"] as? JsonObject ?: return
        modelType = (model["type"] as? JsonPrimitive)?.contentOrNull ?: ""
        when (modelType) {
            "Unigram" -> {
                val scores = HashMap<String, Float>()
                val v = HashMap<String, Int>()
                // id = позиция токена в vocab-списке.
                model["vocab"]?.jsonArray?.forEachIndexed { index, el ->
                    val arr = el.jsonArray
                    if (arr.size >= 2) {
                        val token = (arr[0] as? JsonPrimitive)?.contentOrNull ?: return@forEachIndexed
                        scores[token] = (arr[1] as? JsonPrimitive)?.floatOrNull ?: 0f
                        v[token] = index
                    }
                }
                unigramScores = scores
                vocab = v
                rebuildInvVocab()
            }
            "BPE" -> {
                val v = HashMap<String, Int>()
                model["vocab"]?.jsonObject?.forEach { (k, value) ->
                    val id = (value as? JsonPrimitive)?.intOrNull ?: return@forEach
                    v[k] = id
                }
                vocab = v
                val m = ArrayList<Pair<String, String>>()
                model["merges"]?.jsonArray?.forEach { el ->
                    val arr = el.jsonArray
                    if (arr.size >= 2) {
                        val a = (arr[0] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                        val b = (arr[1] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                        m.add(a to b)
                    }
                }
                merges = m
                val ranks = HashMap<Pair<String, String>, Int>()
                m.forEachIndexed { idx, pair -> ranks[pair] = idx }
                mergeRanks = ranks
                rebuildInvVocab()
            }
        }
        // Спец-токены из vocab (Marian: <s>, <unk>, <pad>).
        vocab.forEach { (token, id) ->
            when (token) {
                "</s>" -> if (eosId == null) eosId = id
                "<unk>" -> if (unkId == null) unkId = id
            }
        }
    }

    private fun rebuildInvVocab() {
        invVocab = vocab.filterValues { it >= 0 }.entries.associate { (k, v) -> v to k }
    }

    // --- Unigram (Viterbi) ---

    private fun unigramTokenize(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val n = text.length
        val NEG_INF = -Float.MAX_VALUE
        val bestScore = FloatArray(n + 1) { NEG_INF }
        val bestId = IntArray(n + 1) { -1 }
        val bestStart = IntArray(n + 1) { -1 }
        bestScore[0] = 0f

        for (i in 0 until n) {
            if (bestScore[i] == NEG_INF) continue
            for (j in i + 1..n) {
                val token = text.substring(i, j)
                val score = unigramScores[token] ?: continue
                val newScore = bestScore[i] + score
                if (newScore > bestScore[j]) {
                    bestScore[j] = newScore
                    bestId[j] = 1
                    bestStart[j] = i
                }
            }
        }

        val tokens = ArrayList<String>()
        var i = n
        while (i > 0) {
            if (bestId[i] == -1) {
                // Не покрыто моделью — символ как есть.
                tokens.add(text.substring(i - 1, i))
                i -= 1
            } else {
                tokens.add(text.substring(bestStart[i], i))
                i = bestStart[i]
            }
        }
        tokens.reverse()
        return tokens
    }

    // --- BPE ---

    private fun bpeTokenize(piece: String): List<String> {
        if (piece.isEmpty()) return emptyList()
        var symbols = piece.map { it.toString() }.toMutableList()
        if (symbols.size <= 1) return symbols

        while (symbols.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestIdx = -1
            for (i in 0 until symbols.size - 1) {
                val rank = mergeRanks[symbols[i] to symbols[i + 1]] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestIdx = i
                }
            }
            if (bestIdx == -1) break
            symbols[bestIdx] = symbols[bestIdx] + symbols[bestIdx + 1]
            symbols.removeAt(bestIdx + 1)
        }
        return symbols
    }

    private companion object {
        val SPECIAL_TOKEN_CONTENTS = setOf(
            "<s>", "</s>", "<pad>", "<unk>", "<eos>", "<bos>", "<sep>", "<cls>"
        )
    }

    /**
     * Интерфейс нормализатора текста.
     */
    private fun interface TextNormalizer {
        fun normalize(text: String): String
    }

    /**
     * Нормализатор по precompiled_charsmap (SentencePiece / NFKC).
     * Бинарный формат: [u32 trie_size, trie: [u32], normalized: utf8].
     */
    private class CharsmapNormalizer(charsmap: ByteArray) {

        private val normalized: String
        private val trie: List<Int>

        init {
            require(charsmap.size >= 4) { "charsmap слишком мал" }
            val trieSize = readU32(charsmap, 0)
            val trieCount = trieSize / 4
            val trieList = ArrayList<Int>(trieCount)
            var pos = 4
            repeat(trieCount) {
                trieList.add(readU32(charsmap, pos))
                pos += 4
            }
            trie = trieList
            normalized = String(charsmap, pos, charsmap.size - pos, StandardCharsets.UTF_8)
        }

        fun normalize(original: String): String {
            val result = StringBuilder(original.length)
            val bi = BreakIterator.getCharacterInstance()
            bi.setText(original)
            var start = bi.first()
            var end = bi.next()
            while (end != -1) {
                val grapheme = original.substring(start, end)
                if (grapheme.length < 6) {
                    val norm = transform(grapheme)
                    if (norm != null) {
                        result.append(norm)
                        start = end
                        end = bi.next()
                        continue
                    }
                }
                for (c in grapheme) {
                    val norm = transform(c.toString())
                    if (norm != null) result.append(norm) else result.append(c)
                }
                start = end
                end = bi.next()
            }
            return result.toString()
        }

        private fun transform(chunk: String): String? {
            val results = commonPrefixSearch(chunk.toByteArray(StandardCharsets.UTF_8))
            if (results.isEmpty()) return null
            val index = results[0]
            var index2 = index
            while (index2 < normalized.length && normalized[index2] != '\u0000') index2++
            return normalized.substring(index, index2)
        }

        private fun commonPrefixSearch(key: ByteArray): List<Int> {
            var nodePos = 0
            val results = ArrayList<Int>()
            var unit = trie[nodePos]
            nodePos = nodePos xor unitOffset(unit)
            for (c in key) {
                val b = c.toInt() and 0xFF
                if (b == 0) break
                nodePos = nodePos xor b
                unit = trie[nodePos]
                if (unitLabel(unit) != b) return results
                nodePos = nodePos xor unitOffset(unit)
                if (unitHasLeaf(unit)) results.add(unitValue(trie[nodePos]))
            }
            return results
        }

        private fun unitHasLeaf(unit: Int): Boolean = (unit shr 8) and 1 == 1
        private fun unitValue(unit: Int): Int = unit and 0x7FFFFFFF
        private fun unitLabel(unit: Int): Int = unit and 0x800000FF.toInt()
        private fun unitOffset(unit: Int): Int = (unit shr 10) shl ((unit and (1 shl 9)) shr 6)

        private fun readU32(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)
    }
}
