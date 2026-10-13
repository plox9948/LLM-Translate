package com.plox.llmtranslate.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-тесты чистой логики контроля целостности докачки ([DownloadResumePolicy])
 * и выравнивания SHA-256 по URL — без сети и файлов.
 */
class DownloadResumePolicyTest {

    // --- Content-Range -----------------------------------------------------

    @Test
    fun `parses standard content-range`() {
        val range = DownloadResumePolicy.parseContentRange("bytes 5000-9999/12345")
        requireNotNull(range)
        assertEquals(5000L, range.start)
        assertEquals(9999L, range.end)
        assertEquals(12345L, range.total)
    }

    @Test
    fun `content-range with unknown total maps to minus-one`() {
        val range = DownloadResumePolicy.parseContentRange("bytes 0-99/*")
        requireNotNull(range)
        assertEquals(-1L, range.total)
    }

    @Test
    fun `invalid or missing content-range returns null`() {
        assertNull(DownloadResumePolicy.parseContentRange(null))
        assertNull(DownloadResumePolicy.parseContentRange(""))
        assertNull(DownloadResumePolicy.parseContentRange("bytes abc-def/10"))
        assertNull(DownloadResumePolicy.parseContentRange("range 0-9/10"))
    }

    // --- решение «продолжать или перечитать» ------------------------------

    @Test
    fun `resume confirmed when range matches part size and saved total`() {
        val meta = DownloadResumePolicy.PartMeta("W/\"abc\"", 12345L)
        val range = DownloadResumePolicy.ContentRange(5000, 12344, 12345)
        assertEquals(
            DownloadResumePolicy.Decision.CONTINUE,
            DownloadResumePolicy.decideResume(5000L, range, meta)
        )
    }

    @Test
    fun `resume discarded when content-range start shifted`() {
        val meta = DownloadResumePolicy.PartMeta("W/\"abc\"", 12345L)
        val shifted = DownloadResumePolicy.ContentRange(0, 12344, 12345)
        assertEquals(
            DownloadResumePolicy.Decision.DISCARD_RESTART,
            DownloadResumePolicy.decideResume(5000L, shifted, meta)
        )
    }

    @Test
    fun `resume discarded when server total size changed`() {
        val meta = DownloadResumePolicy.PartMeta("W/\"abc\"", 12345L)
        val resized = DownloadResumePolicy.ContentRange(5000, 9999, 99999)
        assertEquals(
            DownloadResumePolicy.Decision.DISCARD_RESTART,
            DownloadResumePolicy.decideResume(5000L, resized, meta)
        )
    }

    @Test
    fun `resume discarded when content-range header missing`() {
        val meta = DownloadResumePolicy.PartMeta("W/\"abc\"", 12345L)
        assertEquals(
            DownloadResumePolicy.Decision.DISCARD_RESTART,
            DownloadResumePolicy.decideResume(5000L, null, meta)
        )
    }

    @Test
    fun `resume allowed without saved total when start matches`() {
        val range = DownloadResumePolicy.ContentRange(5000, 9999, 12345)
        assertEquals(
            DownloadResumePolicy.Decision.CONTINUE,
            DownloadResumePolicy.decideResume(5000L, range, null)
        )
    }

    // --- метаданные .part.meta ---------------------------------------------

    @Test
    fun `meta roundtrip preserves etag and total`() {
        val meta = DownloadResumePolicy.PartMeta("W/\"xyz\"", 42L)
        assertEquals(meta, DownloadResumePolicy.decodeMeta(DownloadResumePolicy.encodeMeta(meta)))
    }

    @Test
    fun `meta without etag roundtrips as null etag`() {
        val meta = DownloadResumePolicy.PartMeta(null, 100L)
        val decoded = DownloadResumePolicy.decodeMeta(DownloadResumePolicy.encodeMeta(meta))
        assertEquals(100L, decoded?.totalSizeBytes)
        assertNull(decoded?.etag)
    }

    @Test
    fun `broken meta decodes to null`() {
        assertNull(DownloadResumePolicy.decodeMeta(""))
        assertNull(DownloadResumePolicy.decodeMeta("garbage"))
        assertNull(DownloadResumePolicy.decodeMeta("etag=abc\ntotal=not-a-number"))
        assertNull(DownloadResumePolicy.decodeMeta("etag=abc\ntotal=-5"))
    }

    // --- финальная сверка размера -------------------------------------------

    @Test
    fun `size check skipped when expectation unknown`() {
        assertTrue(DownloadResumePolicy.sizeMatches(123L, 0L))
        assertTrue(DownloadResumePolicy.sizeMatches(123L, -1L))
    }

    @Test
    fun `size check fails on truncated download`() {
        assertFalse(DownloadResumePolicy.sizeMatches(999L, 1000L))
        assertTrue(DownloadResumePolicy.sizeMatches(1000L, 1000L))
    }

    // --- SHA-256 по индексам URL --------------------------------------------

    @Test
    fun `sha256s list aligned with urls missing entries become null`() {
        val aligned = perUrlSha256s(3, listOf("h1", "h2"), null)
        assertEquals(listOf("h1", "h2", null), aligned)
    }

    @Test
    fun `empty sha256s list falls back to legacy first hash`() {
        val aligned = perUrlSha256s(2, emptyList(), "legacy")
        assertEquals(listOf("legacy", null), aligned)
    }

    @Test
    fun `blank legacy hash becomes null`() {
        val aligned = perUrlSha256s(1, emptyList(), "  ")
        assertEquals(listOf<String?>(null), aligned)
    }
}
