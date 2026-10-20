package com.plox.llmtranslate.data.backup

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Проверка манифеста бэкапа на дубликаты id/relativePath
 * ([findManifestDuplicate]): импорт Such архивов отклоняется явно до
 * любых записей (иначе поздняя запись тихо затирает раннюю), и round-trip
 * null-значений карт назначений («снять назначение языка»).
 */
class ModelBackupManifestDuplicateTest {

    private fun entry(id: String, relativePath: String) = BackupModelEntry(
        id = id,
        category = "TRANSLATION",
        displayName = id,
        description = "",
        runtime = "MARIAN_SLIMT",
        sizeBytes = 1L,
        ramRequiredMb = 1,
        languages = emptyList(),
        quality = 0,
        speed = 0,
        url = "",
        urls = emptyList(),
        sha256 = null,
        license = "MIT",
        minAndroidSdk = 26,
        recommendedRamMb = 0,
        relativePath = relativePath,
        isBuiltin = false
    )

    @Test
    fun `clean manifest has no duplicates`() {
        assertNull(
            findManifestDuplicate(
                listOf(entry("a", "models/a.onnx"), entry("b", "models/b.onnx"))
            )
        )
        assertNull(findManifestDuplicate(emptyList()))
    }

    @Test
    fun `duplicate id is reported`() {
        val found = findManifestDuplicate(
            listOf(entry("a", "models/a1.onnx"), entry("a", "models/a2.onnx"))
        )
        assertEquals("дубликат id модели «a» в манифесте", found)
    }

    @Test
    fun `duplicate relativePath is reported`() {
        val found = findManifestDuplicate(
            listOf(entry("a", "models/shared.onnx"), entry("b", "models/shared.onnx"))
        )
        assertEquals(
            "дубликат пути модели «models/shared.onnx» в манифесте (id b)",
            found
        )
    }

    @Test
    fun `null map values round trip decodes as unassign operations`() {
        // Значение null в карте назначений декодируется (не роняет импорт)
        // и различается с отсутствием ключа секции.
        val json = Json { ignoreUnknownKeys = true }
        val settings = json.decodeFromString(
            BackupSettings.serializer(),
            """{"sttByLanguage":{"ru":null,"en":"stt-en"},"ttsEngine":null,"uiLanguage":"ru"}"""
        )
        assertEquals(mapOf("ru" to null, "en" to "stt-en"), settings.sttByLanguage)
        assertEquals(listOf("ru" to null, "en" to "stt-en"), assignmentOps(settings.sttByLanguage))
        assertNull(settings.ttsEngine)
        assertEquals("ru", settings.uiLanguage)

        val encoded = json.encodeToString(BackupSettings.serializer(), settings)
        assertEquals(settings, json.decodeFromString(BackupSettings.serializer(), encoded))
    }
}
