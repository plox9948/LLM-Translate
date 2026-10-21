package com.plox.llmtranslate.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты сравнения версий самообновления (issue #95).
 */
class UpdateVersionLogicTest {

    @Test
    fun `equal versions compare equal`() {
        assertEquals(0, UpdateVersionLogic.compareVersionNames("1.2.3", "1.2.3"))
    }

    @Test
    fun `patch bump is newer`() {
        assertEquals(1, UpdateVersionLogic.compareVersionNames("0.5.19", "0.5.18"))
        assertEquals(-1, UpdateVersionLogic.compareVersionNames("0.5.18", "0.5.19"))
    }

    @Test
    fun `minor bump is newer regardless of patch`() {
        assertEquals(1, UpdateVersionLogic.compareVersionNames("0.6.0", "0.5.18"))
    }

    @Test
    fun `v-prefix stripped from tag`() {
        assertEquals("0.5.19", UpdateVersionLogic.tagToVersion("v0.5.19"))
        assertEquals("0.5.19", UpdateVersionLogic.tagToVersion("V0.5.19"))
        assertEquals("0.5.19", UpdateVersionLogic.tagToVersion("0.5.19"))
        assertTrue(UpdateVersionLogic.isNewer("v0.6.0", "0.5.18"))
        assertFalse(UpdateVersionLogic.isNewer("v0.5.18", "0.5.18"))
    }

    @Test
    fun `missing segments count as zero`() {
        assertEquals(0, UpdateVersionLogic.compareVersionNames("1.2", "1.2.0"))
        assertEquals(0, UpdateVersionLogic.compareVersionNames("1.2.0.0", "1.2"))
        assertEquals(1, UpdateVersionLogic.compareVersionNames("1.2.1", "1.2"))
    }

    @Test
    fun `numeric compare not lexicographic`() {
        assertEquals(1, UpdateVersionLogic.compareVersionNames("1.10", "1.9"))
        assertEquals(1, UpdateVersionLogic.compareVersionNames("10.0", "9.9"))
    }

    @Test
    fun `non-numeric segment tail ignored`() {
        assertEquals(0, UpdateVersionLogic.compareVersionNames("1.2.3-beta", "1.2.3"))
        assertEquals(1, UpdateVersionLogic.compareVersionNames("1.2.4-rc1", "1.2.3"))
    }

    @Test
    fun `garbage input does not crash`() {
        // Сегменты без цифр равны нулю: битые строки не роняют сравнение.
        assertEquals(0, UpdateVersionLogic.compareVersionNames("", ""))
        assertEquals(0, UpdateVersionLogic.compareVersionNames("abc", "xyz"))
        assertEquals(-1, UpdateVersionLogic.compareVersionNames("abc", "1"))
        assertEquals(1, UpdateVersionLogic.compareVersionNames("2!", "1!"))
        assertFalse(UpdateVersionLogic.isNewer("", "1.2.3"))
        assertFalse(UpdateVersionLogic.isNewer("мусор", "0.5.18"))
    }

    @Test
    fun `huge segments do not overflow`() {
        // Числа длиннее Long сравниваются по длине цифры без переполнения.
        assertEquals(
            1,
            UpdateVersionLogic.compareVersionNames(
                "1.99999999999999999999",
                "1.99999999999999999998"
            )
        )
    }
}
