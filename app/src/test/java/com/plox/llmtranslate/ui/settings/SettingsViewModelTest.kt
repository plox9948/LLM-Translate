package com.plox.llmtranslate.ui.settings

import com.plox.llmtranslate.R
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.ModelManager
import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.update.UpdateStateHost
import com.plox.llmtranslate.domain.update.UpdateUiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Тесты guard-записей настроек в [SettingsViewModel] (ревью #83): сбой
 * коммита DataStore (IOException) не роняет процесс, а уходит в общий баннер
 * [SettingsViewModel.observeErrorRes]; успешная запись сбоя не показывает;
 * скрытие баннера снимает ошибку. Плюс секция «Обновление» (issue #95):
 * переключатель автопроверок пишется в DataStore, срез состояния
 * [UpdateStateHost] попадает в [SettingsUiState.updateState].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val preferences = mockk<ModelPreferences>(relaxed = true)
    private val modelManager = mockk<ModelManager>(relaxed = true)
    private val updateStateHost = mockk<UpdateStateHost>(relaxed = true)

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
        every { preferences.autoUpdateChecksEnabled } returns flowOf(true)
        every { updateStateHost.state } returns MutableStateFlow(UpdateUiState.Idle)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `successful settings writes report no error`() {
        val vm = SettingsViewModel(preferences, modelManager, updateStateHost)

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
        val vm = SettingsViewModel(preferences, modelManager, updateStateHost)

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
        val vm = SettingsViewModel(preferences, modelManager, updateStateHost)

        vm.onUnloadTimeoutChange("abc")

        coVerify(exactly = 1) {
            preferences.setUnloadTimeoutMinutes(ModelPreferences.DEFAULT_UNLOAD_TIMEOUT_MINUTES)
        }
        assertNull(vm.observeErrorRes.value)
    }

    @Test
    fun `auto update check switch writes preference and reflects it in ui state`() = runTest {
        val autoChecks = MutableStateFlow(true)
        every { preferences.autoUpdateChecksEnabled } returns autoChecks
        val vm = SettingsViewModel(preferences, modelManager, updateStateHost)

        assertTrue(vm.uiState.value.autoUpdateChecksEnabled)
        autoChecks.value = false
        assertFalse(vm.uiState.value.autoUpdateChecksEnabled)

        vm.onAutoUpdateChecksChange(false)
        coVerify(exactly = 1) { preferences.setAutoUpdateChecksEnabled(false) }
        assertNull(vm.observeErrorRes.value)

        // Сбой записи — общий баннер, как у остальных настроек.
        coEvery { preferences.setAutoUpdateChecksEnabled(any()) } throws IOException()
        vm.onAutoUpdateChecksChange(true)
        assertEquals(R.string.settings_write_failed, vm.observeErrorRes.value)
    }

    @Test
    fun `manual update check delegates to host and mirrors state slice`() = runTest {
        val hostState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
        every { updateStateHost.state } returns hostState
        val vm = SettingsViewModel(preferences, modelManager, updateStateHost)

        assertEquals(UpdateUiState.Idle, vm.uiState.value.updateState)
        vm.onCheckUpdateClick()
        coVerify(exactly = 1) { updateStateHost.checkManually() }
        assertNull(vm.observeErrorRes.value)

        hostState.value = UpdateUiState.ManualUpToDate
        assertEquals(UpdateUiState.ManualUpToDate, vm.uiState.value.updateState)
    }
}
