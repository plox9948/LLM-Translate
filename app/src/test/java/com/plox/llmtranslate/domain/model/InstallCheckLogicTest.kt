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
        runtime = RuntimeType.GGUF,
        sizeBytes = sizeBytes,
        ramRequiredMb = ramRequiredMb,
        languages = listOf("ru"),
        quality = 4,
        speed = 4,
        url = "",
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
        assertTrue((result as InstallCheck.Warning).message.contains("Место"))
    }

    @Test
    fun `warning when not enough ram`() {
        val device = goodDevice.copy(totalRamMb = 2048)
        val result = InstallCheckLogic.evaluate(card(ramRequiredMb = 1500), device)
        assertTrue(result is InstallCheck.Warning)
        assertTrue((result as InstallCheck.Warning).message.contains("ОЗУ"))
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
}
