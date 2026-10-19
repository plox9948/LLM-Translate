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
 * Реально поддержана только Metaspace-претокенизация: [encode] разбивает
 * текст по пробельным границам и добавляет каждому слову префикс
 * [metaspaceReplacement];
 * типы Whitespace, ByteLevel, Split и прочие в [parsePreTokenizer] дают
 * явную ошибку загрузки (молчаливая подмена молча ломала бы id, ревью #79).
 * Отсутствие секции pre_tokenizer трактуется как Metaspace-поведение
 * (значения по умолчанию).
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

    /** true — tokenizer.json успешно разобран; false — загрузка не удалась (см. лог). */
    var isLoaded: Boolean = false
        private set

    // Модель.
    private var modelType: String = ""
    private var mergeRanks: Map<Pair<String, String>, Int> = emptyMap()
    private var unigramScores: Map<String, Float> = emptyMap()

    // Пре-токенизатор (Metaspace).
    private var metaspaceReplacement: String = "▁"

    // Декодер.
    private var decoderType: String = ""
    private var decoderReplacement: String = "▁"

    // Нормализатор.
    private var normalizer: TextNormalizer? = null

    /**
     * Загрузить tokenizer.json из файла.
     * При неудаче [isLoaded] остаётся false — вызывающий обязан проверить статус.
     */
    fun load(tokenizerJsonPath: String) {
        if (isLoaded) return
        try {
            val file = File(tokenizerJsonPath)
            val root = Json.parseToJsonElement(file.readText(StandardCharsets.UTF_8)).jsonObject
            parseAddedTokens(root)
            parseNormalizer(root)
            parsePreTokenizer(root)
            parseDecoder(root)
            parseModel(root)
            isLoaded = true
            Timber.d("TokenizerJson загружен: ${vocab.size} токенов, модель=$modelType")
        } catch (e: Exception) {
            Timber.e(e, "Не удалось загрузить tokenizer.json: $tokenizerJsonPath")
        }
    }

    /**
     * Токенизировать текст в token-id.
     */
    fun encode(text: String): List<Int> {
        if (!isLoaded) return emptyList()
        val normalized = normalizer?.normalize(text) ?: text
        val words = normalized.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        val ids = ArrayList<Int>(words.size * 2)
        var unknownWarned = false
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
                if (id != null) {
                    ids.add(id)
                } else if (!unknownWarned) {
                    // unkId отсутствует: неизвестный токен выпадает из
                    // последовательности — это не должно быть молчаливым
                    // (при наличии <unk> вывод не меняется). Предупреждение
                    // одно на encode, иначе лог тонет на мусорном входе.
                    unknownWarned = true
                    Timber.w("TokenizerJson: токен \"%s\" отсутствует в vocab, <unk> не задан — пропускается", token)
                }
            }
        }
        return ids
    }

    /**
     * Детокенизировать token-id в текст (skip-специальные).
     */
    fun decode(ids: List<Int>): String {
        if (!isLoaded) return ""
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
        // Эталон HF/sentencepiece Metaspace-декодера — lstrip: снимается только
        // ведущий пробел от префикса replacement; концевые пробелы значимы.
        return decoded.trimStart()
    }

    /**
     * ID токена конца последовательности.
     */
    fun eosId(): Int? = eosId

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
            // Дефолт special — false, как в HF: added-токен без поля
            // "special" не считается спец-токеном.
            val isSpecial = (obj["special"] as? JsonPrimitive)?.booleanOrNull ?: false
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
        val type = (pt["type"] as? JsonPrimitive)?.contentOrNull ?: ""
        if (type != "Metaspace") {
            // encode() реализует только Metaspace (разбор по пробелам +
            // префикс replacement): Whitespace/ByteLevel/Split дали бы
            // молчаливо неверные id — явная ошибка загрузки токенизатора
            // (isLoaded останется false, движок сообщит LoadFailed).
            throw IllegalStateException(
                "Пре-токенизатор типа \"$type\" не поддерживается: " +
                    "реализован только Metaspace"
            )
        }
        metaspaceReplacement = (pt["replacement"] as? JsonPrimitive)?.contentOrNull ?: "▁"
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
                val ranks = HashMap<Pair<String, String>, Int>()
                model["merges"]?.jsonArray?.forEachIndexed { idx, el ->
                    val arr = el.jsonArray
                    if (arr.size >= 2) {
                        val a = (arr[0] as? JsonPrimitive)?.contentOrNull ?: return@forEachIndexed
                        val b = (arr[1] as? JsonPrimitive)?.contentOrNull ?: return@forEachIndexed
                        ranks[a to b] = idx
                    }
                }
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

    /**
     * Юниты сегментации — code points, а не UTF-16 блоки: символы вне BMP
     * (суррогатные пары) иначе расщепляются пополам и теряются при поиске в
     * vocab (HF-токенизаторы работают по code points). Для BMP-входа — что и
     * раньше (паритетные тесты не затрагиваются).
     */
    private fun unigramTokenize(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        // offsets[k] — UTF-16-позиция начала k-го code point; offsets[n] — конец строки.
        val offsets = ArrayList<Int>()
        run {
            var i = 0
            while (i < text.length) {
                offsets.add(i)
                i += Character.charCount(text.codePointAt(i))
            }
        }
        val n = offsets.size
        fun cpEnd(k: Int): Int = if (k == n) text.length else offsets[k]

        val NEG_INF = -Float.MAX_VALUE
        val bestScore = FloatArray(n + 1) { NEG_INF }
        // Мёртвый маркер bestId удалён (ревью): признак покрытия — bestStart != -1.
        val bestStart = IntArray(n + 1) { -1 }
        bestScore[0] = 0f

        for (i in 0 until n) {
            if (bestScore[i] == NEG_INF) continue
            for (j in i + 1..n) {
                val token = text.substring(offsets[i], cpEnd(j))
                val score = unigramScores[token] ?: continue
                val newScore = bestScore[i] + score
                if (newScore > bestScore[j]) {
                    bestScore[j] = newScore
                    bestStart[j] = i
                }
            }
        }

        val tokens = ArrayList<String>()
        var i = n
        while (i > 0) {
            if (bestStart[i] == -1) {
                // Не покрыто моделью — code point целиком.
                tokens.add(text.substring(offsets[i - 1], cpEnd(i)))
                i -= 1
            } else {
                tokens.add(text.substring(offsets[bestStart[i]], cpEnd(i)))
                i = bestStart[i]
            }
        }
        tokens.reverse()
        return tokens
    }

    // --- BPE ---

    private fun bpeTokenize(piece: String): List<String> {
        if (piece.isEmpty()) return emptyList()
        // Начальные символы — по code points (см. комментарий к unigramTokenize):
        // не-BMP символ должен оставаться одним юнитом, а не двумя суррогатами.
        val symbols = ArrayList<String>()
        var pos = 0
        while (pos < piece.length) {
            val cp = piece.codePointAt(pos)
            symbols.add(String(Character.toChars(cp)))
            pos += Character.charCount(cp)
        }
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
        /** Компилируется один раз (ревью): Regex на каждый вызов encode(). */
        val WHITESPACE_REGEX = Regex("\\s+")

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
     *
     * Бинарный формат (sentencepiece `Normalizer::DecodePrecompiledCharsMap`):
     * `[u32 trie_size][trie: u32-юниты darts-clone][normalized]`, где блок
     * `normalized` — замены в UTF-8, разделённые 0x00. `value` из листа три —
     * это БАЙТОВОЕ смещение начала записи в `normalized`, а не индекс символа.
     */
    internal class CharsmapNormalizer(charsmap: ByteArray) {

        private val trie: IntArray
        private val normalized: ByteArray

        init {
            require(charsmap.size >= 8) { "charsmap слишком мал" }
            // trie_size — размер три в байтах; darts требует кратности 1024.
            val trieBytes = (readU32(charsmap, 0).toLong() and 0xFFFFFFFFL)
            require(trieBytes >= 1024 && trieBytes <= charsmap.size - 4L && (trieBytes and 0x3FFL) == 0L) {
                "некорректный размер три в charsmap"
            }
            trie = IntArray((trieBytes / 4).toInt()) { readU32(charsmap, 4 + it * 4) }
            normalized = charsmap.copyOfRange(trieBytes.toInt() + 4, charsmap.size)
            require(normalized.isEmpty() || normalized[normalized.size - 1] == 0.toByte()) {
                "блок normalized в charsmap должен завершаться 0x00"
            }
        }

        fun normalize(original: String): String {
            val result = StringBuilder(original.length)
            val bi = BreakIterator.getCharacterInstance()
            bi.setText(original)
            var start = bi.first()
            var end = bi.next()
            while (end != -1) {
                val grapheme = original.substring(start, end)
                // Как в spm_precompiled: матч всей графемой ищем только для
                // коротких графем (порог — BYTES длины, не символы).
                if (grapheme.toByteArray(StandardCharsets.UTF_8).size < 6) {
                    val norm = transform(grapheme)
                    if (norm != null) {
                        result.append(norm)
                        start = end
                        end = bi.next()
                        continue
                    }
                }
                var i = 0
                while (i < grapheme.length) {
                    val cp = grapheme.codePointAt(i)
                    val charCount = Character.charCount(cp)
                    val part = grapheme.substring(i, i + charCount)
                    result.append(transform(part) ?: part)
                    i += charCount
                }
                start = end
                end = bi.next()
            }
            return result.toString()
        }

        /**
         * Самое длинное совпадение байтового ключа в три (как
         * `Normalizer::NormalizePrefix` в sentencepiece); затем чтение записи
         * замены из `normalized` по байтовому смещению до завершающего 0x00.
         */
        private fun transform(chunk: String): String? {
            val results = commonPrefixSearch(chunk.toByteArray(StandardCharsets.UTF_8))
            if (results.isEmpty()) return null
            val value = results.last()
            if (value >= normalized.size) return null
            var end = value
            while (end < normalized.size && normalized[end] != 0.toByte()) end++
            return String(normalized, value, end - value, StandardCharsets.UTF_8)
        }

        /**
         * Обход darts-clone по байтам ключа; возвращает значения leaf-юнитов
         * по возрастанию длины совпавшего префикса (последнее — самое длинное).
         * Границы массива проверяются перед КАЖДЫМ чтением trie — некорректный
         * offset выводит_index за пределы (ревью).
         */
        private fun commonPrefixSearch(key: ByteArray): List<Int> {
            var nodePos = 0
            val results = ArrayList<Int>()
            var unit = trie[nodePos]
            nodePos = nodePos xor unitOffset(unit)
            for (c in key) {
                if (nodePos < 0 || nodePos >= trie.size) return results
                val b = c.toInt() and 0xFF
                if (b == 0) break
                nodePos = nodePos xor b
                if (nodePos < 0 || nodePos >= trie.size) return results
                unit = trie[nodePos]
                if (unitLabel(unit) != b) return results
                nodePos = nodePos xor unitOffset(unit)
                if (unitHasLeaf(unit) && nodePos in trie.indices) {
                    results.add(unitValue(trie[nodePos]))
                }
            }
            return results
        }

        private fun unitHasLeaf(unit: Int): Boolean = (unit ushr 8) and 1 == 1
        private fun unitValue(unit: Int): Int = unit and 0x7FFFFFFF
        private fun unitLabel(unit: Int): Int = unit and 0x800000FF.toInt()

        // Смещение юнита darts-lite: сдвиг 0 или ×2 в зависимости от флага
        // (бит 9). Логический сдвиг и маска поля offset (22 бита, 0x3FFFFF):
        // при установленном бите 31 арифметический shr давал отрицательное
        // смещение и ArrayIndexOutOfBoundsException при encode (ревью).
        private fun unitOffset(unit: Int): Int =
            ((unit ushr 10) shl ((unit ushr 9) and 1)) and 0x3FFFFF

        private fun readU32(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)
    }
}
