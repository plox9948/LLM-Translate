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
 * Погрешность склейки в токенах: субсловная токенизация concatenated-строки
 * отличается от суммы токенов частей только у стыков склейки (единицы
 * токенов). Пока оценка накопленного чанка отделена от бюджета не меньше
 * чем на [JOIN_MARGIN], решение о помещаемости принимается по оценке без
 * полной перемотки склейки; вблизи бюджета — по точному [tokenCount].
 */
private const val JOIN_MARGIN = 4

/**
 * Разбить [text] на чанки, каждый не длиннее [budget] по счётчику [tokenCount].
 *
 * Конвенция бюджета задаёт вызывающий движок (см. KDoc
 * `IndicTransOnnxRuntime.translate`); здесь [tokenCount] — абстрактная мера
 * длины чанка, сравнение строго `<= budget` (ровно бюджет — без нарезки).
 *
 * 1. Границы — [SentenceSplitter.splitWithSeparators]; сегментов нет
 *    (пробельный ввод) — [text] возвращается как есть (единственный чанк);
 *    одиночный сегмент проходит общий цикл и, если превышает бюджет,
 *    дорезается по словам наравне с прочими (ревью #79: прежний ранний
 *    возврат «один сегмент — как есть» оставлял сверхбюджетный чанк
 *    нетронутым).
 * 2. Жадная упаковка последовательных сегментов в чанк, пока [tokenCount]
 *    склеенного (через исходные разделители) чанка не превысит [budget];
 *    переполнение — закрытие чанка, разделитель разрыва = разделитель той
 *    границы исходника, на которой произошёл разрыв.
 * 3. Одиночный сегмент сам больше бюджета — дорезка по пробелам: слова
 *    накапливаются, пока укладываемся в бюджет; хвост слов уходит в чанк
 *    всегда, даже при превышении (слово длиннее бюджета — [Timber.w]);
 *    разделители внутри дорезки — обычный пробел.
 *
 * Стоимость — линейна по входу: каждый сегмент и каждый кусок дорезки
 * считается [tokenCount] ровно один раз, длина склейки накапливается суммой
 * частей (погрешность склейки ограничена стыками и покрывается
 * [JOIN_MARGIN]); точный [tokenCount] склейки считается только когда оценка
 * близко к бюджету.
 *
 * TODO(ревью): оценка склейки аддитивна ([JOIN_MARGIN] покрывает только
 * стыки) — полная переработка точной оценки накопленного чанка (пересчёт
 * целиком на каждом шаге) затратна и отложена. Условие пересмотра margin:
 * смена токенизатора модели (сильная/слабая субсловная склейка на стыках)
 * ИЛИ смена окна энкодера/бюджета [budget] (при малом бюджете относительная
 * погрешность стыков растёт). Константу заранее не менять.
 */
internal fun planChunks(text: String, tokenCount: (String) -> Int, budget: Int): ChunkPlan {
    val (segments, segSeps) = SentenceSplitter.splitWithSeparators(text)
    // Пустой/пробельный ввод — единственный чанк как есть; одиночный
    // сегмент длиннее бюджета дорезается общим циклом (ревью #79).
    if (segments.isEmpty()) return ChunkPlan(listOf(text), emptyList())

    val chunks = ArrayList<String>()
    val separators = ArrayList<String>()
    // Разделитель ПЕРЕД следующим открываемым чанком: им помечается граница
    // исходника (или внутренний пробел дорезки), на которой закрыт предыдущий.
    var sepBeforeNext = " "
    // Накопленный незакрытый контент текущего чанка и его оценка в токенах
    // (сумма токенов составляющих; см. [JOIN_MARGIN]).
    var current = ""
    var currentTokens = 0

    fun openChunk(content: String) {
        if (chunks.isNotEmpty()) {
            separators.add(sepBeforeNext)
        }
        chunks.add(content)
    }

    /**
     * Помещается ли склейка [left] + [sep] + [right] в бюджет: вдали от
     * бюджета — по аддитивной оценке [leftTokens]+[sepTokens]+[rightTokens],
     * вблизи бюджета — по точному [tokenCount] склейки.
     */
    fun fitsJoined(
        left: String,
        leftTokens: Int,
        sep: String,
        sepTokens: Int,
        right: String,
        rightTokens: Int,
    ): Boolean {
        val estimate = leftTokens + sepTokens + rightTokens
        return when {
            estimate <= budget - JOIN_MARGIN -> true
            estimate > budget + JOIN_MARGIN -> false
            else -> tokenCount(left + sep + right) <= budget
        }
    }

    for (index in segments.indices) {
        val seg = segments[index]
        val segTokens = tokenCount(seg)
        val boundarySep = if (index > 0) segSeps[index - 1] else " "
        val sepTokens = tokenCount(boundarySep)

        // Доупаковка сегмента в текущий чанк, пока целиком помещается.
        if (current.isNotEmpty() &&
            fitsJoined(current, currentTokens, boundarySep, sepTokens, seg, segTokens)
        ) {
            current += boundarySep + seg
            currentTokens += sepTokens + segTokens
            continue
        }
        if (current.isNotEmpty()) {
            openChunk(current)
            current = ""
            currentTokens = 0
            // Следующий чанк склеивается с закрытым разделителем границы разрыва.
            sepBeforeNext = boundarySep
        }

        if (segTokens <= budget) {
            current = seg
            currentTokens = segTokens
            continue
        }

        // Сегмент сам больше бюджета — дорезка по словам.
        var piece = ""
        var pieceTokens = 0
        val spaceTokens = tokenCount(" ")
        for (word in seg.split(Regex("\\s+")).filter { it.isNotBlank() }) {
            val wordTokens = tokenCount(word)
            // Хвост слов всегда уходит в чанк, даже если слово длиннее бюджета.
            if (wordTokens > budget) {
                Timber.w("ChunkPlanner: слово длиннее бюджета чанка: \"$word\"")
            }
            if (piece.isNotEmpty() &&
                fitsJoined(piece, pieceTokens, " ", spaceTokens, word, wordTokens)
            ) {
                piece = "$piece $word"
                pieceTokens += spaceTokens + wordTokens
                continue
            }
            if (piece.isNotEmpty()) {
                openChunk(piece)
                // Разделители внутри дорезки одного сегмента — пробел.
                sepBeforeNext = " "
            }
            piece = word
            pieceTokens = wordTokens
        }
        current = piece
        currentTokens = pieceTokens
    }
    if (current.isNotEmpty()) {
        openChunk(current)
    }
    return ChunkPlan(chunks, separators)
}
