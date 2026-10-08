package com.plox.llmtranslate.domain

import android.content.Context
import androidx.work.WorkManager
import com.plox.llmtranslate.data.catalog.ModelCatalogProvider
import com.plox.llmtranslate.data.model.ModelRepository
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.model.DeviceInfo
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import com.plox.llmtranslate.domain.runtime.RuntimeRouter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Тесты ModelManager: выбор и удаление моделей.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelManagerTest {

    private val context = mockk<Context>(relaxed = true)
    private val catalogProvider = mockk<ModelCatalogProvider>(relaxed = true)
    private val repository = mockk<ModelRepository>(relaxed = true)
    private val preferences = mockk<ModelPreferences>(relaxed = true)
    private val deviceInfoProvider = mockk<DeviceInfoProvider>(relaxed = true)
    private val runtimeRouter = mockk<RuntimeRouter>(relaxed = true)

    private lateinit var manager: ModelManagerImpl

    @Before
    fun setup() {
        // WorkManager в юнит-тесте не инициализирован — мокаем getInstance().
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns mockk(relaxed = true)
        every { catalogProvider.catalog } returns flowOf(emptyList())
        every { deviceInfoProvider.get() } returns DeviceInfo(34, 8192, 6000, 10_000_000_000L)
        manager = ModelManagerImpl(
            context,
            catalogProvider,
            repository,
            preferences,
            deviceInfoProvider,
            runtimeRouter,
            CoroutineScope(UnconfinedTestDispatcher())
        )
    }

    private fun readyModel(id: String, category: ModelCategory) = InstalledModel(
        id = id,
        category = category,
        displayName = "Test",
        description = "",
        runtime = RuntimeType.MARIAN_ONNX,
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
        status = ModelStatus.READY,
        progress = 100,
        filePath = "/models/$id.gguf",
        bytesDone = 100,
        isBuiltin = true
    )

    @Test
    fun `setActive returns false when model not found`() = runTest {
        coEvery { repository.getById("missing") } returns null
        val result = manager.setActive(ModelCategory.TRANSLATION, "missing")
        assertFalse(result)
    }

    @Test
    fun `setActive returns false when model not ready`() = runTest {
        val downloading = readyModel("m1", ModelCategory.TRANSLATION).copy(
            status = ModelStatus.DOWNLOADING,
            filePath = null
        )
        coEvery { repository.getById("m1") } returns downloading
        val result = manager.setActive(ModelCategory.TRANSLATION, "m1")
        assertFalse(result)
    }

    @Test
    fun `setActive saves id when model is ready`() = runTest {
        coEvery { repository.getById("m1") } returns readyModel("m1", ModelCategory.TRANSLATION)
        val result = manager.setActive(ModelCategory.TRANSLATION, "m1")
        assertTrue(result)
        coVerify { preferences.setActiveModelId(ModelCategory.TRANSLATION, "m1") }
    }

    @Test
    fun `delete switches to first ready model when deleting active model`() = runTest {
        val deleted = readyModel("m1", ModelCategory.STT)
        val fallback = readyModel("m2", ModelCategory.STT)
        coEvery { repository.getById("m1") } returns deleted
        coEvery { preferences.getActiveModelId(ModelCategory.STT) } returns "m1"
        coEvery { repository.getFirstReady(ModelCategory.STT) } returns fallback

        manager.delete("m1")

        coVerify { repository.deleteById("m1") }
        coVerify { preferences.setActiveModelId(ModelCategory.STT, "m2") }
    }

    @Test
    fun `delete resets active id when no fallback model exists`() = runTest {
        val model = readyModel("m1", ModelCategory.STT)
        coEvery { repository.getById("m1") } returns model
        coEvery { preferences.getActiveModelId(ModelCategory.STT) } returns "m1"
        coEvery { repository.getFirstReady(ModelCategory.STT) } returns null

        manager.delete("m1")

        coVerify { repository.deleteById("m1") }
        coVerify { preferences.setActiveModelId(ModelCategory.STT, null) }
    }
}
