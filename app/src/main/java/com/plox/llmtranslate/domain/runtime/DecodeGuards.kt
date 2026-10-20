package com.plox.llmtranslate.domain.runtime

import ai.onnxruntime.OrtSession

/**
 * Decode-hygiene greedy-декодера IndicTrans2 (issue #62): жёсткая маска
 * повторяющихся n-грамм, безопасный выбор токена и детектор вырожденного
 * вывода для решения о ретрае.
 */

/** Значение маски для запрещённых id (argmax по нему никогда не выберет). */
private const val FORBIDDEN_LOGIT = -Float.MAX_VALUE

/** Минимальное число слов, при котором считается доля уникальных. */
private const val MIN_WORDS_FOR_RATIO = 8

/** Порог доли уникальных слов ниже которого вывод считается вырожденным. */
private const val MIN_UNIQUE_RATIO = 0.3f

/** Максимальный период хвостового цикла (в словах), искомый в выводе. */
private const val MAX_CYCLE_PERIOD = 4

/** Сколько повращений периода в хвосте считается зацикливанием. */
private const val CYCLE_REPEATS = 4

/** Разделитель слов: скомпилирован один раз (горячий путь детектора, ревью). */
private val WHITESPACE = Regex("\\s+")

/**
 * Жёсткая маска `no_repeat_ngram` (аналог HF forbid-ngram-idioms, НЕ штраф):
 * мутирует [logits] на месте. Если последние (n-1) токенов из [generated]
 * уже встречались в [generated] с продолжением, то id продолжений ставятся
 * в [-Float.MAX_VALUE] — argmax по замаскированным логитам не завершит
 * второй раз тот же n-грамм.
 *
 * Чистая функция на [FloatArray] — тестируется на JVM без ORT.
 *
 * @param logits logits последней позиции (мутируются на месте).
 * @param generated уже сгенерированные id (включая decoder-start).
 * @param n размер запрещаемого n-грамма (n >= 1; при n <= 0 — no-op).
 * @param eosTokenId токен конца последовательности — стоп-токены всегда
 *        разрешены и под маску НЕ попадают (иначе запрет последнего
 *        n-грамма заблокировал бы завершение перевода, ревью #79);
 *        -1 — стоп-токен не задавать.
 */
internal fun maskRepeatingNgrams(
    logits: FloatArray,
    generated: List<Int>,
    n: Int,
    eosTokenId: Int = -1
) {
    if (n <= 0 || generated.isEmpty() || logits.isEmpty()) return
    val ctxSize = n - 1
    val size = generated.size
    // Ищем ВСЕ предшествующие вхождения контекста (последних ctxSize id),
    // у которых есть продолжение (позиция i+ctxSize существовала).
    // Собственное вхождение контекста в конце без продолжения исключается.
    for (i in 0 until size) {
        if (i + ctxSize >= size) break
        var matches = true
        for (j in 0 until ctxSize) {
            if (generated[i + j] != generated[size - ctxSize + j]) {
                matches = false
                break
            }
        }
        if (matches) {
            val forbidden = generated[i + ctxSize]
            if (forbidden != eosTokenId && forbidden in logits.indices) {
                logits[forbidden] = FORBIDDEN_LOGIT
            }
        }
    }
}

/**
 * Выбор следующего токена greedy-цикла IndicTrans с NaN/Inf-защитой
 * ([argMaxSafe]) и маской no_repeat_ngram при [noRepeatNgramSize] > 0
 * (жёсткий запрет повтора n-граммы, не штраф).
 *
 * @param result результат шага декодера (ORT).
 * @param generated уже сгенерированные id.
 * @param noRepeatNgramSize размер запрещаемого n-грамма (0 — без маски).
 * @param eosTokenId стоп-токен — всегда разрешён к выбору при маске
 *        (см. [maskRepeatingNgrams]).
 * @throws IllegalStateException при полностью некорректных logits (шаг
 *         описывается числом уже сгенерированных токенов).
 */
internal fun pickGreedyTokenSafe(
    result: OrtSession.Result,
    generated: List<Int>,
    noRepeatNgramSize: Int,
    eosTokenId: Int
): Int {
    val logits = logitsLastPosition(result)
    if (noRepeatNgramSize > 0) {
        maskRepeatingNgrams(logits, generated, noRepeatNgramSize, eosTokenId)
    }
    return argMaxSafe(logits, "после ${generated.size} сгенерированных токенов")
}

/**
 * Детектор вырожденного вывода декодера: зацикливание или бедный словарь.
 * Возвращает true, если:
 * - [hitMaxLength] — генерация упёрлась в лимит без EOS (почти всегда цикл);
 * - при [MIN_WORDS_FOR_RATIO] словах и более доля уникальных слов ниже
 *   [MIN_UNIQUE_RATIO];
 * - в хвосте любой период длиной 1..[MAX_CYCLE_PERIOD] слов повторён
 *   [CYCLE_REPEATS] и более раз подряд.
 *
 * Проверять по ТРИММЕННОМУ тексту (после RepetitionTrimmer, до postprocess).
 *
 * Отсутствие EOS в генерации признаком вырождения НЕ является и в детектор
 * не передаётся: IndicTrans2 корректно завершает перевод точкой (`.`/`।`)
 * перед EOS, но легитимные выводы бывают и без EOS — по одному только этому
 * факту ретрай срабатывал бы на корректных переводах.
 *
 * [sourceText] (текст чанка входа, если передан): повторяющийся паттерн
 * входа переносится декодером в вывод легитимно («no no no…» → «no no no…») —
 * если блок, повторяющийся в выводе, повторяется подряд и в нормализованном
 * входе, вырождением НЕ считается (в т.ч. при упоре в лимит), иначе ретрай
 * с no_repeat_ngram возвращал ошибку на корректном повторении входного
 * паттерна (ревью).
 */
internal fun isDegenerateOutput(
    text: String,
    hitMaxLength: Boolean,
    sourceText: String? = null
): Boolean {
    val words = wordsOf(text)
    // Повтор пришёл из входа — не вырождение.
    if (sourceText != null && repeatsComeFromInput(words, wordsOf(sourceText))) {
        return false
    }
    if (hitMaxLength) return true
    // Слова: split по пробельным, отбрасываем чисто пунктуационные токены.
    if (words.size >= MIN_WORDS_FOR_RATIO) {
        if (uniqueWordRatio(words) < MIN_UNIQUE_RATIO) return true
    }
    for (period in 1..MAX_CYCLE_PERIOD) {
        val needed = period * CYCLE_REPEATS
        if (words.size < needed) continue
        val tailStart = words.size - needed
        var cyclic = true
        for (i in 0 until needed) {
            if (words[tailStart + i] != words[tailStart + (i % period)]) {
                cyclic = false
                break
            }
        }
        if (cyclic) return true
    }
    return false
}

/** Доля уникальных слов в тексте (0 при отсутствии слов) — выбор best-of-two. */
internal fun uniqueWordRatio(text: String): Float = uniqueWordRatio(wordsOf(text))

private fun uniqueWordRatio(words: List<String>): Float =
    if (words.isEmpty()) 0f else words.distinct().size.toFloat() / words.size

/** Слова текста: по пробельным, без чисто пунктуационных токенов. */
private fun wordsOf(text: String): List<String> =
    text.split(WHITESPACE).filter { w -> w.any { it.isLetterOrDigit() } }

/**
 * Блоки, повторённые подряд >= 2 раз где-либо в [words]: множество
 * нормализованных (в нижний регистр) последовательностей слов периодами
 * 1..[MAX_CYCLE_PERIOD]. Чисто пунктуационные токены из блоков исключены
 * ([wordsOf]).
 */
private fun repeatingBlocks(words: List<String>): Set<List<String>> {
    val blocks = mutableSetOf<List<String>>()
    if (words.isEmpty()) return blocks
    for (start in words.indices) {
        for (period in 1..MAX_CYCLE_PERIOD) {
            val end = start + period
            if (end > words.size) break
            val block = words.subList(start, end).map { it.lowercase() }
            var repeats = 1
            while (end + repeats * period <= words.size &&
                words.subList(end + (repeats - 1) * period, end + repeats * period)
                    .map { it.lowercase() } == block
            ) {
                repeats++
            }
            if (repeats >= 2) blocks += block
        }
    }
    return blocks
}

/**
 * «Повтор пришёл из входа»: любое повторённое подряд слово/блок вывода
 * встречается повторяется и в нормализованном входе (ревью — легитимные
 * повторы входа вроде «no no no…»).
 */
private fun repeatsComeFromInput(outputWords: List<String>, sourceWords: List<String>): Boolean {
    if (sourceWords.isEmpty() || outputWords.isEmpty()) return false
    val sourceBlocks = repeatingBlocks(sourceWords)
    if (sourceBlocks.isEmpty()) return false
    return repeatingBlocks(outputWords).any { it in sourceBlocks }
}
