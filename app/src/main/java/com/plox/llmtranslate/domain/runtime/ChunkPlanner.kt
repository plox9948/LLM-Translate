package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.model.SentenceSplitter
import timber.log.Timber

/**
 * План чанков для движков, кодирующих вход целиком в ограниченное окно
 * энбеддингов (IndicTrans2, issue #63). Чанки переводятся по очереди,
 * [separators] (размер = `chunks.size - 1`) — разделители границ исходника,
 * на которых произошёл разрыв: они вставляются при склейке переводов.
 */
internal class ChunkPlan(
    val chunks: List<String>,
    val separators: List<String>,
)

/**
 * Разбить [text] на чанки, каждый не длиннее [budget] по счётчику [tokenCount].
 *
 * Конвенция бюджета задаёт вызывающий движок (см. KDoc
 * `IndicTransOnnxRuntime.translate`); здесь [tokenCount] — абстрактная мера
 * длины чанка, сравнение строго `<= budget` (ровно бюджет — без нарезки).
 *
 * 1. Границы — [SentenceSplitter.splitWithSeparators]; сегментов не больше
 *    одного — [text] возвращается как есть (единственный чанк).
 * 2. Жадная упаковка последовательных сегментов в чанк, пока [tokenCount]
 *    склеенного (через исходные разделители) чанка не превысит [budget];
 *    переполнение — закрытие чанка, разделитель разрыва = разделитель той
 *    границы исходника, на которой произошёл разрыв.
 * 3. Одиночный сегмент сам больше бюджета — дорезка по пробелам: слова
 *    накапливаются, пока укладываемся в бюджет; хвост слов уходит в чанк
 *    всегда, даже при превышении (слово длиннее бюджета — [Timber.w]);
 *    разделители внутри дорезки — обычный пробел.
 */
internal fun planChunks(text: String, tokenCount: (String) -> Int, budget: Int): ChunkPlan {
    val (segments, segSeps) = SentenceSplitter.splitWithSeparators(text)
    if (segments.size <= 1) return ChunkPlan(listOf(text), emptyList())

    val chunks = ArrayList<String>()
    val separators = ArrayList<String>()
    // Разделитель ПЕРЕД следующим открываемым чанком: им помечается граница
    // исходника (или внутренний пробел дорезки), на которой закрыт предыдущий.
    var sepBeforeNext = " "
    // Накопленный незакрытый контент текущего чанка.
    var current = ""

    fun openChunk(content: String) {
        if (chunks.isNotEmpty()) {
            separators.add(sepBeforeNext)
        }
        chunks.add(content)
    }

    for (index in segments.indices) {
        val seg = segments[index]
        val boundarySep = if (index > 0) segSeps[index - 1] else " "

        // Доупаковка сегмента в текущий чанк, пока целиком помещается.
        if (current.isNotEmpty() && tokenCount(current + boundarySep + seg) <= budget) {
            current += boundarySep + seg
            continue
        }
        if (current.isNotEmpty()) {
            openChunk(current)
            current = ""
            // Следующий чанк склеивается с закрытым разделителем границы разрыва.
            sepBeforeNext = boundarySep
        }

        if (tokenCount(seg) <= budget) {
            current = seg
            continue
        }

        // Сегмент сам больше бюджета — дорезка по словам.
        var piece = ""
        for (word in seg.split(Regex("\\s+")).filter { it.isNotBlank() }) {
            // Хвост слов всегда уходит в чанк, даже если слово длиннее бюджета.
            if (tokenCount(word) > budget) {
                Timber.w("ChunkPlanner: слово длиннее бюджета чанка: \"$word\"")
            }
            if (piece.isNotEmpty() && tokenCount("$piece $word") <= budget) {
                piece = "$piece $word"
            } else {
                if (piece.isNotEmpty()) {
                    openChunk(piece)
                    // Разделители внутри дорезки одного сегмента — пробел.
                    sepBeforeNext = " "
                }
                piece = word
            }
        }
        current = piece
    }
    if (current.isNotEmpty()) {
        openChunk(current)
    }
    return ChunkPlan(chunks, separators)
}
