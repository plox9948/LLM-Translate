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
 * Чистое отсутствие EOS ([eosReached] == false) признаком НЕ является:
 * IndicTrans2 корректно завершает перевод точкой (`.`/`।`) перед EOS, но
 * легитимные выводы бывают и без EOS — по одному только флагу ретрай
 * срабатывал бы на корректных переводах. Ретрай запускается только на
 * реальные вырождения: degenerate = hitMaxLength || бедный словарь ||
 * хвостовой цикл.
 *
 * Проверять по ТРИММЕННОМУ тексту (после RepetitionTrimmer, до postprocess).
 */
internal fun isDegenerateOutput(
    text: String,
    eosReached: Boolean,
    hitMaxLength: Boolean
): Boolean {
    if (hitMaxLength) return true
    // Слова: split по пробельным, отбрасываем чисто пунктуационные токены.
    val words = text.split(Regex("\\s+"))
        .filter { w -> w.any { it.isLetterOrDigit() } }
    if (words.size >= MIN_WORDS_FOR_RATIO) {
        val uniqueRatio = words.distinct().size.toFloat() / words.size
        if (uniqueRatio < MIN_UNIQUE_RATIO) return true
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
