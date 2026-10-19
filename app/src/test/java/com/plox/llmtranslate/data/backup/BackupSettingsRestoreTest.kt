package com.plox.llmtranslate.data.backup

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты чистого маппинга поязыковых назначений ([assignmentOps]) и обратной
 * совместимости [BackupSettings] (ревью #53, уточнение ревью #5):
 * presence-семантика «ключа нет — не трогаем; операции только по языкам
 * секции назначений архива, отсутствующий в бэкапе язык не снимается»,
 * декод старого JSON без новых полей и roundtrip новых полей (сенты TTS
 * «system», тайм-аут 0, retention).
 */
class BackupSettingsRestoreTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `absent saved map yields no ops and settings stay untouched`() {
        assertTrue(assignmentOps(null).isEmpty())
    }

    @Test
    fun `saved map yields ops only for its own languages`() {
        // Явные null-значения секции снимаются, но язык, отсутствующий в
        // частичном бэкапе (например «ne»), операции не получает.
        val ops = assignmentOps(
            mapOf("en" to "new-en", "ru" to "new-ru", "ne" to null)
        )
        assertEquals(
            setOf("en" to "new-en", "ru" to "new-ru", "ne" to null),
            ops.toSet()
        )
    }

    @Test
    fun `empty saved map yields no ops and current assignments stay`() {
        // Пустая секция — ни одна операция: снятие возможно только для
        // языка, явно присутствующего в манифесте бэкапа (ревью #5).
        assertTrue(assignmentOps(emptyMap()).isEmpty())
    }

    @Test
    fun `old backup json without new fields decodes to null maps`() {
        val legacy = """
            {"activeTranslation":"t1","activeStt":null,
             "ttsEngine":"system","uiLanguage":"ru","theme":"dark"}
        """.trimIndent()
        val settings = json.decodeFromString(BackupSettings.serializer(), legacy)

        assertEquals("t1", settings.activeTranslation)
        assertNull(settings.sttByLanguage)
        assertNull(settings.ttsByLanguage)
        assertNull(settings.unloadTimeoutMinutes)
        assertNull(settings.modelDirectionRetention)
        assertNull(settings.lastSourceLang)
        assertNull(settings.lastTargetLang)
    }

    @Test
    fun `new settings round trip preserves maps sentinel and numeric zeroes`() {
        val settings = BackupSettings(
            activeTts = "tts-en-piper",
            sttByLanguage = mapOf("ru" to "stt-ru-gigaam"),
            ttsByLanguage = mapOf("ru" to "system", "en" to "tts-en-piper"),
            unloadTimeoutMinutes = 0,
            modelDirectionRetention = "unload",
            lastSourceLang = "ru",
            lastTargetLang = "ne"
        )
        val encoded = json.encodeToString(BackupSettings.serializer(), settings)
        val decoded = json.decodeFromString(BackupSettings.serializer(), encoded)

        assertEquals(settings, decoded)
        assertEquals(0, decoded.unloadTimeoutMinutes)
        assertEquals(
            mapOf("ru" to "system", "en" to "tts-en-piper"),
            decoded.ttsByLanguage
        )
    }
}
