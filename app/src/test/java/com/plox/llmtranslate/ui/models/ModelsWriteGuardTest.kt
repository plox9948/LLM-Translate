package com.plox.llmtranslate.ui.models

import android.content.Context
import android.net.Uri
import com.plox.llmtranslate.R
import com.plox.llmtranslate.data.backup.ModelBackupManager
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.DiskUsage
import com.plox.llmtranslate.domain.ModelManager
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.InstallCheck
import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
 * Тесты guard-операций экрана «Модели» (ревью #83): launchGuarded для
 * назначений (активная модель, языковые чипы STT/TTS, движок TTS), снятие
 * pending предыдущей карточки при перезаписи license-диалога и request-in-state
 * для пикеров восстановления/импорта.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelsWriteGuardTest {

    private val modelManager = mockk<ModelManager>(relaxed = true)
    private val backupManager = mockk<ModelBackupManager>(relaxed = true)
    private val preferences = mockk<ModelPreferences>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { modelManager.catalog } returns flowOf(emptyList())
        every { modelManager.installedModels(null) } returns flowOf(emptyList())
        every { modelManager.activeModelId(any()) } returns flowOf(null)
        every { modelManager.diskUsage } returns flowOf(DiskUsage(1_000L, "/models"))
        every { modelManager.loadedModels } returns flowOf(emptyList())
        every { modelManager.sttByLanguage } returns flowOf(emptyMap())
        every { modelManager.ttsByLanguage } returns flowOf(emptyMap())
        coEvery { modelManager.deviceInfo() } returns mockk(relaxed = true)
        every { preferences.lastSourceLang } returns flowOf(null)
        every { preferences.lastTargetLang } returns flowOf(null)
        every { preferences.ttsEngine } returns flowOf("system")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): ModelsViewModel = ModelsViewModel(
        modelManager,
        backupManager,
        mockk(relaxed = true),
        preferences,
        context,
        CoroutineScope(UnconfinedTestDispatcher())
    )

    /**
     * Карточка каталога; [nonCommercial] — лицензия с NC-токеном, по которой
     * [ModelCard.requiresLicenseConfirmation] истинен (вычисляемое поле).
     */
    private fun sttCard(id: String, nonCommercial: Boolean = false) = ModelCard(
        id = id,
        category = ModelCategory.TTS,
        displayName = id,
        description = "",
        runtime = RuntimeType.PIPER_ONNX,
        sizeBytes = 100,
        ramRequiredMb = 100,
        languages = listOf("ru"),
        quality = 4,
        speed = 4,
        url = "",
        urls = emptyList(),
        sha256 = null,
        license = if (nonCommercial) "CC-BY-NC-4.0" else "MIT",
        minAndroidSdk = 26,
        recommendedRamMb = 0,
        hidden = false
    )

    private val installedPiper = InstalledModel(
        id = "piper-ru",
        category = ModelCategory.TTS,
        displayName = "Piper RU",
        description = "",
        runtime = RuntimeType.PIPER_ONNX,
        sizeBytes = 100,
        ramRequiredMb = 100,
        languages = listOf("ru"),
        quality = 4,
        speed = 4,
        url = "",
        urls = emptyList(),
        sha256 = null,
        license = "MIT",
        minAndroidSdk = 26,
        recommendedRamMb = 0,
        status = ModelStatus.READY,
        progress = 100,
        filePath = "/models/PIPER_ONNX/piper-ru",
        bytesDone = 100,
        isBuiltin = true
    )

    /** Ждём асинхронного (IO→Main) продолжения без произвольного сна. */
    private fun awaitUntil(
        timeoutMs: Long = 3_000,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(condition())
    }

    @Test
    fun `set active model failure reports banner instead of crash`() {
        coEvery { modelManager.setActive(any(), any()) } throws IOException("room down")
        val vm = newViewModel()

        vm.onActiveClick(installedPiper)

        assertEquals(R.string.models_assignment_failed, vm.observeErrorRes.value)
    }

    @Test
    fun `language assignment failures report banner for stt and tts toggles`() {
        coEvery { modelManager.setSttForLanguage(any(), any()) } throws IOException("room down")
        coEvery { modelManager.setTtsForLanguage(any(), any()) } throws IOException("room down")
        val vm = newViewModel()

        vm.onSttLanguageToggle(sttCard("stt-x"), "ru")
        assertEquals(R.string.models_assignment_failed, vm.observeErrorRes.value)

        vm.clearObserveError()
        assertNull(vm.observeErrorRes.value)

        vm.onTtsLanguageToggle(sttCard("piper-ru"), "ru")
        assertEquals(R.string.models_assignment_failed, vm.observeErrorRes.value)
    }

    @Test
    fun `piper engine select write failure reported via shared banner`() {
        coEvery { preferences.setTtsEngine(any()) } throws IOException("journal")
        val vm = newViewModel()

        // Движок-ярлык (не Piper-модель): ttsEngineId() непустой только у
        // SYSTEM_TTS/RHVOICE/ESPEAK.
        vm.onTtsEngineSelect(sttCard("engine-rhvoice").copy(runtime = RuntimeType.RHVOICE))

        assertEquals(R.string.settings_write_failed, vm.observeErrorRes.value)
    }

    @Test
    fun `license dialog rewrite clears previous card pending`() {
        every { modelManager.checkInstall(any(), any()) } returns InstallCheck.Ready
        val cardA = sttCard("card-a", nonCommercial = true)
        val cardB = sttCard("card-b", nonCommercial = true)
        val vm = newViewModel()

        // Тап по карточке A: pending поднят, диалог лицензии открыт (после
        // IO-чтения устройства).
        vm.onDownloadClick(cardA)
        awaitUntil { vm.licenseConfirmModel.value?.id == "card-a" }
        assertTrue("card-a" in vm.uiState.value.pendingDownloadIds)

        // Перезапись диалога карточкой B: скачивание A больше не будет
        // запущено — его pending обязан сняться (ревью #83).
        vm.onDownloadClick(cardB)
        awaitUntil { vm.licenseConfirmModel.value?.id == "card-b" }
        assertFalse("card-a" in vm.uiState.value.pendingDownloadIds)
        assertTrue("card-b" in vm.uiState.value.pendingDownloadIds)
    }

    @Test
    fun `blocked install clears pending and reports reason`() {
        every { modelManager.checkInstall(any(), any()) } returns
            InstallCheck.Blocked("NOT_ENOUGH_SPACE")
        val vm = newViewModel()

        vm.onDownloadClick(sttCard("card-blocked"))
        awaitUntil { vm.blockedReason.value != null }

        assertEquals("NOT_ENOUGH_SPACE", vm.blockedReason.value)
        assertFalse("card-blocked" in vm.uiState.value.pendingDownloadIds)
    }

    @Test
    fun `restore and import picker requests are request-in-state and survive room emissions`() {
        val installedFlow = kotlinx.coroutines.flow.MutableStateFlow<List<InstalledModel>>(emptyList())
        every { modelManager.installedModels(null) } returns installedFlow
        val vm = newViewModel()

        vm.onBackupRestoreClick()
        vm.onModelImportClick()
        assertTrue(vm.uiState.value.backupRestoreRequest)
        assertTrue(vm.uiState.value.modelImportRequest)

        // Новая эмиссия Room пересобирает ModelsUiState — pending-запросы
        // пикеров обязаны пережить её (transient white-list).
        installedFlow.value = listOf(installedPiper)
        assertTrue(vm.uiState.value.backupRestoreRequest)
        assertTrue(vm.uiState.value.modelImportRequest)

        // Consume после launch() пикера (паттерн экспорта).
        vm.consumeBackupRestoreRequest()
        vm.consumeModelImportRequest()
        assertFalse(vm.uiState.value.backupRestoreRequest)
        assertFalse(vm.uiState.value.modelImportRequest)
    }

    // --- SAF-колбэки: guard вместо падения процесса (ревью аудита, п.1) ---

    @Test
    fun `model import io failure reports banner instead of crash`() {
        coEvery { modelManager.importFromUri(any()) } throws IOException("storage io")
        val vm = newViewModel()

        vm.onImportResult(mockk<Uri>(relaxed = true))

        assertEquals(R.string.models_import_failed, vm.observeErrorRes.value)
    }

    @Test
    fun `model export io failure unlocks card and reports banner`() {
        coEvery { modelManager.exportToUri(any(), any()) } throws IOException("saf io")
        val vm = newViewModel()

        vm.onExportClick(installedPiper)
        assertEquals(installedPiper.id, vm.uiState.value.exportingModelId)
        vm.onExportResult(mockk<Uri>(relaxed = true))

        assertEquals(R.string.models_export_failed, vm.observeErrorRes.value)
        assertNull(vm.uiState.value.exportingModelId)
    }

    @Test
    fun `backup export io failure clears busy and reports banner`() {
        coEvery { backupManager.exportToUri(any()) } throws IOException("zip io")
        val vm = newViewModel()

        vm.onBackupExportResult(mockk<Uri>(relaxed = true))

        assertEquals(R.string.models_backup_export_failed, vm.observeErrorRes.value)
        assertFalse(vm.uiState.value.isBusy)
    }

    @Test
    fun `backup import io failure clears busy and reports banner`() {
        coEvery { backupManager.importFromUri(any()) } throws IOException("zip io")
        val vm = newViewModel()

        vm.onBackupImportResult(mockk<Uri>(relaxed = true))

        assertEquals(R.string.models_backup_import_failed, vm.observeErrorRes.value)
        assertFalse(vm.uiState.value.isBusy)
    }

    // --- Pending-гейты SAF-колбэков (re-review): занятая операция одна ---

    @Test
    fun `double backup import callback while busy starts only one import`() {
        val gate = kotlinx.coroutines.CompletableDeferred<ModelBackupManager.ImportResult>()
        coEvery { backupManager.importFromUri(any()) } coAnswers { gate.await() }
        val vm = newViewModel()

        vm.onBackupImportResult(mockk<Uri>(relaxed = true))
        // Синхронный гейт (re-review): занятость поднята ДО запуска корутины —
        // повторный/задвоенный колбэк пикера не запускает второй импорт.
        assertTrue(vm.uiState.value.isBusy)
        vm.onBackupImportResult(mockk<Uri>(relaxed = true))
        coVerify(exactly = 1) { backupManager.importFromUri(any()) }

        // Завершение «своей» операции снимает занятость (счётчик владельцев
        // обнуляется) — следующая операция снова проходит гейт.
        gate.complete(ModelBackupManager.ImportResult(1, 0, false))
        awaitUntil { !vm.uiState.value.isBusy }

        vm.onBackupImportResult(mockk<Uri>(relaxed = true))
        coVerify(exactly = 2) { backupManager.importFromUri(any()) }
    }

    @Test
    fun `younger backup export finishing keeps busy while older import runs`() {
        val importGate = kotlinx.coroutines.CompletableDeferred<ModelBackupManager.ImportResult>()
        coEvery { backupManager.importFromUri(any()) } coAnswers { importGate.await() }
        coEvery { backupManager.exportToUri(any()) } returns true
        val vm = newViewModel()

        // Старшая операция — импорт бэкапа живой (занятость поднята синхронно).
        vm.onBackupImportResult(mockk<Uri>(relaxed = true))
        assertTrue(vm.uiState.value.isBusy)

        // Младшая операция — экспорт бэкапа поверх живой занятости. Он
        // завершается сразу (unconfined-скоуп), снимая только «свою» единицу
        // счётчика: isBusy обязан остаться поднятым, пока живая старшая.
        vm.onBackupExportResult(mockk<Uri>(relaxed = true))
        assertTrue(vm.uiState.value.isBusy)

        // Завершение старшей операции — последний владелец, занятость снимается.
        importGate.complete(ModelBackupManager.ImportResult(1, 0, false))
        awaitUntil { !vm.uiState.value.isBusy }
    }

    @Test
    fun `double model import callback while in flight starts only one import`() {
        val gate = kotlinx.coroutines.CompletableDeferred<String?>()
        coEvery { modelManager.importFromUri(any()) } coAnswers { gate.await() }
        val vm = newViewModel()

        // Pending-гейт по образцу onBackupExportClick (re-review): живой
        // импорт в полёте — повторный колбэк пикера не запускает второй.
        vm.onImportResult(mockk<Uri>(relaxed = true))
        vm.onImportResult(mockk<Uri>(relaxed = true))
        coVerify(exactly = 1) { modelManager.importFromUri(any()) }

        // После завершения гейт снимается — следующая операция проходит.
        gate.complete("imported-model")
        awaitUntil { vm.uiState.value.statusMessage != null }
        vm.onImportResult(mockk<Uri>(relaxed = true))
        coVerify(exactly = 2) { modelManager.importFromUri(any()) }
    }

    // --- Гейт подтверждения удаления (решение пользователя, ревью аудита) ---

    @Test
    fun `delete tap only arms pending state and does not delete`() {
        val vm = newViewModel()

        vm.onDeleteClick(installedPiper)

        assertEquals(installedPiper.id, vm.pendingDeleteModelId.value)
        verify(exactly = 0) { modelManager.delete(any()) }
    }

    @Test
    fun `confirmed delete removes the armed model and disarms pending`() {
        val vm = newViewModel()
        vm.onDeleteClick(installedPiper)

        vm.onDeleteConfirmed(installedPiper.id)

        verify(exactly = 1) { modelManager.delete(installedPiper.id) }
        assertNull(vm.pendingDeleteModelId.value)
    }

    @Test
    fun `cancelled delete confirmation does not delete`() {
        val vm = newViewModel()
        vm.onDeleteClick(installedPiper)

        vm.cancelDeleteConfirm()

        assertNull(vm.pendingDeleteModelId.value)
        verify(exactly = 0) { modelManager.delete(any()) }
    }
}
