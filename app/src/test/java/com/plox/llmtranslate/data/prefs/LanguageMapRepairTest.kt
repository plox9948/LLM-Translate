package com.plox.llmtranslate.data.prefs

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #83, п.2: чистый декод и правки JSON-карт «язык → id» ВНУТРИ одной
 * edit-транзакции. Прежний путь «decode ловит битый JSON -> fire-and-forget
 * scope.launch { edit { remove(key) } }» терял обновление (lost update):
 * фоновый remove применялся после коммита и удалял только что записанное
 * назначение. Здесь битое значение перезаписывается/снимается тем же
 * результатом транзакции — отдельной чистки нет.
 */
class LanguageMapRepairTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    @Test
    fun `broken json decodes to empty map with corrupted flag, without storage access`() {
        val broken = decodeLanguageMapValue(json, "stt_by_language", "{definitely not json")
        assertTrue(broken.corrupted)
        assertTrue(broken.map.isEmpty())

        val valid = decodeLanguageMapValue(json, "stt_by_language", """{"ru":"s1"}""")
        assertFalse(valid.corrupted)
        assertEquals(mapOf("ru" to "s1"), valid.map)

        val blank = decodeLanguageMapValue(json, "stt_by_language", " ")
        assertFalse(blank.corrupted)
        assertTrue(blank.map.isEmpty())
    }

    @Test
    fun `assignment over broken map fully replaces corrupted value`() {
        val updated = languageMapSetRaw(json, "stt_by_language", "{broken", "ru", "s1")
        assertEquals(mapOf("ru" to "s1"), json.decodeFromString(serializer, checkNotNull(updated)))

        // Снятие назначения при битом значении — ключ удаляется (починка).
        assertNull(languageMapSetRaw(json, "tts_by_language", "{broken", "ru", null))
    }

    @Test
    fun `valid map assignment merges and last removal removes the key`() {
        val updated = languageMapSetRaw(json, "stt_by_language", """{"en":"e1"}""", "ru", "s1")
        assertEquals(
            mapOf("en" to "e1", "ru" to "s1"),
            json.decodeFromString(serializer, checkNotNull(updated))
        )
        assertNull(languageMapSetRaw(json, "stt_by_language", """{"ru":"s1"}""", "ru", null))
    }

    @Test
    fun `strip touches only matching ids and reports unchanged when nothing matches`() {
        val updated = languageMapStripEdit(json, "stt_by_language", """{"ru":"m1","en":"m2"}""", "m1")
        assertTrue(updated is LanguageMapEdit.SetValue)
        assertEquals(
            mapOf("en" to "m2"),
            json.decodeFromString(serializer, (updated as LanguageMapEdit.SetValue).raw)
        )

        assertEquals(LanguageMapEdit.Unchanged, languageMapStripEdit(json, "stt_by_language", """{"ru":"m1"}""", "ghost"))
        assertEquals(LanguageMapEdit.RemoveKey, languageMapStripEdit(json, "stt_by_language", """{"ru":"m1"}""", "m1"))
        // Битое значение чинится снятием ключа (старые висячие данные не сохраняются).
        assertEquals(LanguageMapEdit.RemoveKey, languageMapStripEdit(json, "stt_by_language", "{broken", "m1"))
    }
}
