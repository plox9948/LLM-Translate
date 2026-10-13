package com.plox.llmtranslate.data.download

/**
 * Чистая логика контроля целостности докачки (без сети и файлов) —
 * выделена для unit-тестов. Используется [BaseDownloader].
 *
 * Схема: рядом с `<file>.part` пишется `<file>.part.meta` с ETag/полным
 * размером ответа сервера на момент начала передачи. При возобновлении:
 * - запрос Range отправляется с If-Match по сохранённому ETag — сервер
 *   сам отбраковал несвежую часть кодом 412;
 * - у ответа 206 сверяется начало/полный размер Content-Range с размером
 *   .part и сохранённым полным размером — при несовпадении часть отбраковывается;
 * - отсутствие метаданных или unparsable Content-Range — докачка недоверена,
 *   файл качается заново.
 */
object DownloadResumePolicy {

    /** Сохранённые метаданные начала докачки (файл `<file>.part.meta`). */
    data class PartMeta(val etag: String?, val totalSizeBytes: Long)

    /** Диапазон из заголовка Content-Range вида `bytes 5000-9999/12345`. */
    data class ContentRange(val start: Long, val end: Long, val total: Long)

    /** Решение по ответу 206 при возобновлении докачки. */
    enum class Decision {
        /** Часть файла подтверждена сервером — продолжать append. */
        CONTINUE,

        /** Часть недоверена (сдвиг начала, изменившийся размер, кривый заголовок). */
        DISCARD_RESTART
    }

    private val contentRangeRegex = Regex("""^bytes\s+(\d+)-(\d+)/(\d+|\*)$""")

    /**
     * Разобрать заголовок Content-Range.
     * @return null, если заголовок отсутствует или не разбирается;
     *         total = -1, если полный размер помечен звёздочкой.
     */
    fun parseContentRange(header: String?): ContentRange? {
        val match = header?.trim()?.let { contentRangeRegex.find(it) } ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: -1L
        return ContentRange(start, end, total)
    }

    /**
     * Проверить, что ответ 206 относится к той же версии файла, что и .part:
     * начало диапазона должно совпадать с размером части, полный размер —
     * с сохранённым в метаданных.
     */
    fun decideResume(partSizeBytes: Long, range: ContentRange?, meta: PartMeta?): Decision {
        if (range == null) return Decision.DISCARD_RESTART
        if (range.start != partSizeBytes) return Decision.DISCARD_RESTART
        val savedTotal = meta?.totalSizeBytes ?: -1L
        if (savedTotal > 0 && range.total > 0 && savedTotal != range.total) {
            return Decision.DISCARD_RESTART
        }
        return Decision.CONTINUE
    }

    /**
     * Сверить итоговый размер скачанного с ожидаемым, если ожидаемый известен
     * (expectedBytes <= 0 — ожидание неизвестно, проверка пропускается).
     */
    fun sizeMatches(actualBytes: Long, expectedBytes: Long): Boolean =
        expectedBytes <= 0L || actualBytes == expectedBytes

    /** Сериализовать метаданные докачки в текст файла .part.meta. */
    fun encodeMeta(meta: PartMeta): String =
        "etag=${meta.etag ?: "-"}\ntotal=${meta.totalSizeBytes}\n"

    /**
     * Прочитать метаданные докачки.
     * @return null при битом/неполном содержимом (докачка недоверена).
     */
    fun decodeMeta(raw: String): PartMeta? {
        val fields = raw.lineSequence()
            .map { it.trim() }
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val total = fields["total"]?.toLongOrNull() ?: return null
        if (total < 0) return null
        val etag = fields["etag"]?.takeIf { it.isNotEmpty() && it != "-" }
        return PartMeta(etag, total)
    }
}

/**
 * Контрольные суммы по индексам URL: список [sha256s] из каталога выравнивается
 * по числу URL (отсутствующие элементы — null, проверка пропускается); если
 * список пуст (старая строка БД, импорт), используется единственный
 * [legacyFirst] — только для первого URL.
 */
internal fun perUrlSha256s(urlCount: Int, sha256s: List<String?>, legacyFirst: String?): List<String?> =
    if (sha256s.isNotEmpty()) {
        List(urlCount) { index -> sha256s.getOrNull(index) }
    } else {
        val first = legacyFirst?.takeIf { it.isNotBlank() }
        List(urlCount) { index -> if (index == 0) first else null }
    }
