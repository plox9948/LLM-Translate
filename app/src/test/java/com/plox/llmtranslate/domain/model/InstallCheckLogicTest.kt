package com.plox.llmtranslate.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты логики проверки установки моделей.
 */
class InstallCheckLogicTest {

    private val goodDevice = DeviceInfo(
        sdkInt = 34,
        totalRamMb = 8192,
        availableRamMb = 6000,
        freeDiskBytes = 10L * 1024 * 1024 * 1024
    )

    private fun card(
        sizeBytes: Long = 500L * 1024 * 1024,
        ramRequiredMb: Int = 1000,
        minAndroidSdk: Int = 26
    ) = ModelCard(
        id = "test",
        category = ModelCategory.TRANSLATION,
        displayName = "Test",
        description = "",
        runtime = RuntimeType.MARIAN_ONNX,
        sizeBytes = sizeBytes,
        ramRequiredMb = ramRequiredMb,
        languages = listOf("ru"),
        quality = 4,
        speed = 4,
        url = "",
        urls = emptyList(),
        sha256 = null,
        license = "Apache-2.0",
        minAndroidSdk = minAndroidSdk,
        recommendedRamMb = 0
    )

    @Test
    fun `ready when device is powerful enough`() {
        val result = InstallCheckLogic.evaluate(card(), goodDevice)
        assertEquals(InstallCheck.Ready, result)
    }

    @Test
    fun `blocked when sdk too low`() {
        val device = goodDevice.copy(sdkInt = 24)
        val result = InstallCheckLogic.evaluate(card(minAndroidSdk = 26), device)
        assertTrue(result is InstallCheck.Blocked)
    }

    @Test
    fun `warning when not enough disk space`() {
        val device = goodDevice.copy(freeDiskBytes = 100L * 1024 * 1024)
        val result = InstallCheckLogic.evaluate(card(sizeBytes = 500L * 1024 * 1024), device)
        assertTrue(result is InstallCheck.Warning)
        assertTrue((result as InstallCheck.Warning).message.contains("Мало места"))
    }

    @Test
    fun `warning when free space covers size but not unpack peak of 2x`() {
        // Пик archive-распаковки ~2x размера: 1.5x места — уже предупреждение
        // (аудит: прежний порог x1.2 занижен).
        val size = 500L * 1024 * 1024
        val device = goodDevice.copy(freeDiskBytes = size * 3 / 2)
        val result = InstallCheckLogic.evaluate(card(sizeBytes = size), device)
        assertTrue(result is InstallCheck.Warning)
        assertTrue((result as InstallCheck.Warning).message.contains("Мало места"))
    }

    @Test
    fun `ready when free space covers the 2x unpack peak`() {
        val size = 500L * 1024 * 1024
        val device = goodDevice.copy(freeDiskBytes = size * 2)
        val result = InstallCheckLogic.evaluate(card(sizeBytes = size), device)
        assertEquals(InstallCheck.Ready, result)
    }

    @Test
    fun `warning when not enough ram`() {
        val device = goodDevice.copy(totalRamMb = 2048)
        val result = InstallCheckLogic.evaluate(card(ramRequiredMb = 1500), device)
        assertTrue(result is InstallCheck.Warning)
        assertTrue((result as InstallCheck.Warning).message.contains("ОЗУ"))
    }

    @Test
    fun `warning when model exceeds available ram though total is enough`() {
        // Порог 80% от фактически доступной ОЗУ: от общего объёма место есть,
        // но свободной памяти под модель не хватает.
        val device = goodDevice.copy(totalRamMb = 65536, availableRamMb = 1500)
        val result = InstallCheckLogic.evaluate(card(ramRequiredMb = 1300), device)
        assertTrue(result is InstallCheck.Warning)
        assertTrue((result as InstallCheck.Warning).message.contains("ОЗУ"))
    }

    @Test
    fun `available ram threshold skipped when unavailable value is zero`() {
        // availableRam == 0 (неизвестна) — порог по доступной ОЗУ не применяется;
        // от общего объёма 8192 МБ модель 4000 МБ укладывается (ниже 60%).
        val device = goodDevice.copy(availableRamMb = 0)
        val result = InstallCheckLogic.evaluate(card(ramRequiredMb = 4000), device)
        assertEquals(InstallCheck.Ready, result)
    }

    @Test
    fun `no ram warning when model fits both total and available thresholds`() {
        // 3000 МБ: ниже 60% от 8192 общего и ниже 80% от 6000 доступной.
        val result = InstallCheckLogic.evaluate(card(ramRequiredMb = 3000), goodDevice)
        assertEquals(InstallCheck.Ready, result)
    }

    @Test
    fun `ram warning when combined models near limit`() {
        val warning = InstallCheckLogic.ramWarning(
            llmMb = 4000,
            sttMb = 2000,
            device = goodDevice
        )
        assertTrue(warning != null)
    }

    @Test
    fun `no ram warning when models fit`() {
        val warning = InstallCheckLogic.ramWarning(
            llmMb = 1000,
            sttMb = 500,
            device = goodDevice
        )
        assertNull(warning)
    }

    @Test
    fun `no ram warning when one model missing`() {
        val warning = InstallCheckLogic.ramWarning(
            llmMb = 4000,
            sttMb = null,
            device = goodDevice
        )
        assertNull(warning)
    }

    @Test
    fun `required download free bytes by layout`() {
        // Ревью, п.3 (гейт воркера): архивная раскладка — пик 2x,
        // одиночный файл — 1.2x, неизвестный размер — пропуск проверки.
        assertEquals(2_000_000L, InstallCheckLogic.requiredDownloadFreeBytes(1_000_000L, true))
        assertEquals(1_200_000L, InstallCheckLogic.requiredDownloadFreeBytes(1_000_000L, false))
        assertEquals(0L, InstallCheckLogic.requiredDownloadFreeBytes(0L, true))
        assertEquals(0L, InstallCheckLogic.requiredDownloadFreeBytes(-5L, false))
    }
}
