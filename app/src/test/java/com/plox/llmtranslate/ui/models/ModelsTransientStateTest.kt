package com.plox.llmtranslate.ui.models

import android.content.Context
import android.net.Uri
import com.plox.llmtranslate.R
import com.plox.llmtranslate.data.backup.ModelBackupManager
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.DiskUsage
import com.plox.llmtranslate.domain.ModelManager
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.RuntimeType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Тест-страховка «минного поля» (ревью #7): транзиентные поля [ModelsUiState]
 * (statusMessage, pendingDownloadIds, exportRequest/backupExportRequest)
 * должны переживать каждую новую эмиссию Room/DataStore — сборка
 * [ModelsUiState] из потоков не имеет права их теряеть.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelsTransientStateTest {

    private val catalogFlow = MutableStateFlow<List<ModelCard>>(emptyList())
    private val installedFlow = MutableStateFlow<List<InstalledModel>>(emptyList())

    private val modelManager = mockk<ModelManager>(relaxed = true)
    private val backupManager = mockk<ModelBackupManager>(relaxed = true)
    private val preferences = mockk<ModelPreferences>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { modelManager.catalog } returns catalogFlow
        every { modelManager.installedModels(null) } returns installedFlow
        every { modelManager.activeModelId(any()) } returns flowOf(null)
        every { modelManager.diskUsage } returns flowOf(DiskUsage(1_000L, "/models"))
        every { modelManager.loadedModels } returns flowOf(emptyList())
        every { modelManager.sttByLanguage } returns flowOf(emptyMap())
        every { modelManager.ttsByLanguage } returns flowOf(emptyMap())
        coEvery { modelManager.deviceInfo() } returns mockk(relaxed = true)
        // Relaxed-мок String? возвращает "" (не null) — ветка импорта ушла бы
        // в «успех»; фиксируем явный провал импорта.
        coEvery { modelManager.importFromUri(any()) } returns null
        every { preferences.lastSourceLang } returns flowOf(null)
        every { preferences.lastTargetLang } returns flowOf(null)
        every { preferences.ttsEngine } returns flowOf("system")
        every { context.getString(R.string.models_import_failed) } returns "IMPORT_FAIL"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun card(id: String) = ModelCard(
        id = id,
        category = ModelCategory.STT,
        displayName = id,
        description = "",
        runtime = RuntimeType.SHERPA,
        sizeBytes = 100,
        ramRequiredMb = 100,
        languages = listOf("ru"),
        quality = 4,
        speed = 4,
        url = "",
        urls = emptyList(),
        sha256 = null,
        license = "Apache-2.0",
        minAndroidSdk = 26,
        recommendedRamMb = 0,
        hidden = false
    )

    @Test
    fun `transient fields survive a new emission from room sources`() {
        val viewModel = ModelsViewModel(
            modelManager,
            backupManager,
            mockk(relaxed = true),
            preferences,
            context
        )

        // Поднимаем все транзиенты: статус, pending-sкачивание, запрос бэкапа.
        viewModel.onImportResult(mockk<Uri>(relaxed = true))
        assertEquals("IMPORT_FAIL", viewModel.uiState.value.statusMessage)

        val pendingCard = card("stt-pending")
        every { modelManager.checkInstall(any(), any()) } returns
            com.plox.llmtranslate.domain.model.InstallCheck.Ready
        viewModel.onDownloadClick(pendingCard)

        viewModel.onBackupExportClick()
        assertNotNull(viewModel.uiState.value.backupExportRequest)

        // Новая эмиссия Room: производный ModelsUiState пересобирается —
        // транзиенты обязаны сохраниться (ручной white-list в подписке init).
        catalogFlow.value = listOf(pendingCard)
        installedFlow.value = emptyList()

        assertEquals("IMPORT_FAIL", viewModel.uiState.value.statusMessage)
        assertTrue(pendingCard.id in viewModel.uiState.value.pendingDownloadIds)
        assertNotNull(viewModel.uiState.value.backupExportRequest)
        // Данные из потока при этом обновлены.
        assertEquals(listOf(pendingCard.id), viewModel.uiState.value.catalog.map { it.id })
    }
}
