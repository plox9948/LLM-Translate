package com.plox.llmtranslate.data.backup

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Оценка гейта свободного места при импорте бэкапа (ревью раунда 3):
 * sizeBytes манифеста — размер скачиваемых артефактов; для archive/gz
 * раскладок требуется учёт раздувания распаковки (×2 / ×1.8) той же
 * формулой, что гейт скачивания.
 */
class BackupImportGateEstimateTest {

    private fun entry(urls: List<String>, sizeBytes: Long) = BackupModelEntry(
        id = "m1",
        category = "TRANSLATION",
        displayName = "M",
        description = "d",
        runtime = "SLIMT",
        sizeBytes = sizeBytes,
        ramRequiredMb = 0,
        languages = listOf("ru", "en"),
        quality = 3,
        speed = 3,
        url = urls.firstOrNull().orEmpty(),
        urls = urls,
        sha256 = null,
        license = "MIT",
        minAndroidSdk = 26,
        recommendedRamMb = 0,
        relativePath = "models/m1",
        isBuiltin = false
    )

    @Test
    fun `archive layout - двойной запас на распаковку`() {
        val e = entry(listOf("https://cdn/releases/model_core.tar.gz"), 1000L)
        assertEquals(2000L, backupInstallRequiredBytes(e))
    }

    @Test
    fun `gz artifacts - запас 1_8x на gunzip`() {
        val e = entry(
            listOf(
                "https://cdn/model.ruen.intgemm.alphas.bin.gz",
                "https://cdn/lex.50.50.ruen.s2t.bin.gz",
                "https://cdn/vocab.ruen.spm.gz"
            ),
            1000L
        )
        assertEquals(1800L, backupInstallRequiredBytes(e))
    }

    @Test
    fun `одиночный файл - запас 1_2x`() {
        val e = entry(listOf("https://cdn/encoder_model.onnx"), 1000L)
        assertEquals(1200L, backupInstallRequiredBytes(e))
    }

    @Test
    fun `импортированная модель без urls - одиночная раскладка`() {
        val e = entry(emptyList(), 1000L)
        assertEquals(1200L, backupInstallRequiredBytes(e))
    }

    @Test
    fun `смешанная раскладка с архивом - приоритет архивной 2x`() {
        val e = entry(listOf("https://cdn/pack.tar.bz2", "https://cdn/vocab.spm"), 1000L)
        assertEquals(2000L, backupInstallRequiredBytes(e))
    }

    @Test
    fun `неизвестный размер - проверка пропускается`() {
        val e = entry(listOf("https://cdn/releases/model_core.zip"), 0L)
        assertEquals(0L, backupInstallRequiredBytes(e))
    }
}
