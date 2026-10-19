package com.plox.llmtranslate.ui.settings

import com.plox.llmtranslate.R
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.ModelManager
import com.plox.llmtranslate.domain.model.ModelCard
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Тесты guard-записей настроек в [SettingsViewModel] (ревью #83): сбой
 * коммита DataStore (IOException) не роняет процесс, а уходит в общий баннер
 * [SettingsViewModel.observeErrorRes]; успешная запись сбоя не показывает;
 * скрытие баннера снимает ошибку.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val preferences = mockk<ModelPreferences>(relaxed = true)
    private val modelManager = mockk<ModelManager>(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { modelManager.catalog } returns flowOf(emptyList<ModelCard>())
        every { preferences.ttsEngine } returns flowOf("system")
        every { preferences.uiLanguage } returns flowOf("system")
        every { preferences.theme } returns flowOf("system")
        every { preferences.unloadTimeoutMinutes } returns
            flowOf(ModelPreferences.DEFAULT_UNLOAD_TIMEOUT_MINUTES)
        every { preferences.modelDirectionRetention } returns
            flowOf(ModelPreferences.RETENTION_KEEP)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `successful settings writes report no error`() {
        val vm = SettingsViewModel(preferences, modelManager)

        vm.onTtsEngineChange("rhvoice")
        vm.onUiLanguageChange("en")
        vm.onThemeChange("dark")
        vm.onUnloadTimeoutChange("15")
        vm.onDirectionRetentionChange("unload")

        coVerify(exactly = 1) { preferences.setTtsEngine("rhvoice") }
        coVerify(exactly = 1) { preferences.setUnloadTimeoutMinutes(15) }
        assertNull(vm.observeErrorRes.value)
    }

    @Test
    fun `failed setting write reported in banner instead of process crash`() {
        val vm = SettingsViewModel(preferences, modelManager)

        coEvery { preferences.setTheme(any()) } throws IOException("journal full")
        vm.onThemeChange("dark")
        assertEquals(R.string.settings_write_failed, vm.observeErrorRes.value)

        // Остальные сеттеры под тем же guard — сбой каждого не роняет VM.
        coEvery { preferences.setTtsEngine(any()) } throws IOException()
        vm.onTtsEngineChange("rhvoice")
        coEvery { preferences.setUiLanguage(any()) } throws IOException()
        vm.onUiLanguageChange("en")
        coEvery { preferences.setUnloadTimeoutMinutes(any()) } throws IOException()
        vm.onUnloadTimeoutChange("-1")
        coEvery { preferences.setModelDirectionRetention(any()) } throws IOException()
        vm.onDirectionRetentionChange("unload")
        assertEquals(R.string.settings_write_failed, vm.observeErrorRes.value)

        // Тап по баннеру снимает ошибку.
        vm.clearObserveError()
        assertNull(vm.observeErrorRes.value)
    }

    @Test
    fun `non numeric unload timeout value falls back to default before write`() {
        val vm = SettingsViewModel(preferences, modelManager)

        vm.onUnloadTimeoutChange("abc")

        coVerify(exactly = 1) {
            preferences.setUnloadTimeoutMinutes(ModelPreferences.DEFAULT_UNLOAD_TIMEOUT_MINUTES)
        }
        assertNull(vm.observeErrorRes.value)
    }
}