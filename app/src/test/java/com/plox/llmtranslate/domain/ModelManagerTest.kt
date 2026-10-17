package com.plox.llmtranslate.domain

import android.content.Context
import android.net.Uri
import androidx.work.WorkManager
import com.plox.llmtranslate.data.catalog.ModelCatalogProvider
import com.plox.llmtranslate.data.model.ModelRepository
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.model.DeviceInfo
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import com.plox.llmtranslate.domain.model.TtsModelSelector
import com.plox.llmtranslate.domain.runtime.RuntimeRouter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

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
        coEvery { deviceInfoProvider.get() } returns DeviceInfo(34, 8192, 6000, 10_000_000_000L)
        manager = ModelManagerImpl(
            context,
            catalogProvider,
            repository,
            preferences,
            deviceInfoProvider,
            runtimeRouter,
            Json { ignoreUnknownKeys = true },
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

    private fun tempModelsDir(): File =
        File.createTempFile("models", "").apply {
            delete()
            mkdirs()
        }

    /** Запись прерванной установки: статус DOWNLOADING, filePath ещё нет. */
    private fun partialModel(id: String, urls: List<String>) = InstalledModel(
        id = id,
        category = ModelCategory.TRANSLATION,
        displayName = "Test",
        description = "",
        runtime = RuntimeType.MARIAN_ONNX,
        sizeBytes = 10,
        ramRequiredMb = 10,
        languages = emptyList(),
        quality = 3,
        speed = 3,
        url = urls.firstOrNull().orEmpty(),
        urls = urls,
        sha256 = null,
        license = "MIT",
        minAndroidSdk = 26,
        recommendedRamMb = 0,
        status = ModelStatus.DOWNLOADING,
        progress = 50,
        filePath = null,
        bytesDone = 5,
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
    fun `setTtsForLanguage accepts system sentinel without model lookup`() = runTest {
        // Issue #53: сентинель «system» — явное назначение системного TTS,
        // карточка модели не проверяется.
        manager.setTtsForLanguage("ru", TtsModelSelector.SYSTEM_SENTINEL)
        coVerify(exactly = 0) { repository.getById(TtsModelSelector.SYSTEM_SENTINEL) }
        coVerify { preferences.setTtsForLanguage("ru", TtsModelSelector.SYSTEM_SENTINEL) }
    }

    @Test
    fun `setTtsForLanguage rejects unknown model id`() = runTest {
        coEvery { repository.getById("garbage") } returns null
        manager.setTtsForLanguage("ru", "garbage")
        coVerify(exactly = 0) { preferences.setTtsForLanguage(any(), any()) }
    }

    @Test
    fun `setTtsForLanguage accepts ready piper model`() = runTest {
        val piper = readyModel("tts-ru-piper-ruslan", ModelCategory.TTS)
            .copy(runtime = RuntimeType.PIPER_ONNX)
        coEvery { repository.getById("tts-ru-piper-ruslan") } returns piper
        manager.setTtsForLanguage("ru", "tts-ru-piper-ruslan")
        coVerify { preferences.setTtsForLanguage("ru", "tts-ru-piper-ruslan") }
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

    @Test
    fun `delete frees runtime cache bundle of the deleted model`() = runTest {
        // Issue #40: после удаления записи пучок модели изымается из LRU-кэшей
        // движков — loadedModels больше не показывает её «загруженной».
        val model = readyModel("m1", ModelCategory.TRANSLATION)
        coEvery { repository.getById("m1") } returns model

        manager.delete("m1")

        coVerify { repository.deleteById("m1") }
        coVerify { runtimeRouter.releaseModel(model) }
    }

    @Test
    fun `delete with null file path removes model dir leftovers`() = runTest {
        // Issue #40: у записи прерванной мультифайловой установки filePath нет,
        // но уже переименованные целевые файлы лежат в каталоге модели.
        val modelsDir = tempModelsDir()
        every { context.getExternalFilesDir("models") } returns modelsDir
        val model = partialModel("m3", listOf("http://host/a.onnx", "http://host/b.onnx"))
        val modelDir = File(File(modelsDir, "MARIAN_ONNX"), "m3")
        modelDir.mkdirs()
        File(modelDir, "a.onnx").writeBytes(byteArrayOf(1, 2))
        File(modelDir, "b.onnx.part").writeBytes(byteArrayOf(3))
        coEvery { repository.getById("m3") } returns model

        manager.delete("m3")

        assertFalse(modelDir.exists())
        coVerify { repository.deleteById("m3") }
        coVerify { runtimeRouter.releaseModel(model) }
        modelsDir.deleteRecursively()
    }

    @Test
    fun `cancel download deletes model dir with renamed target files`() = runTest {
        // Issue #40: отмена после скачивания части мультифайлового пакета
        // не должна оставлять переименованные целевые файлы в models/.
        val modelsDir = tempModelsDir()
        every { context.getExternalFilesDir("models") } returns modelsDir
        val model = partialModel("m1", listOf("http://host/encoder.onnx", "http://host/decoder.onnx"))
        coEvery { repository.getById("m1") } returns model
        val modelDir = File(File(modelsDir, "MARIAN_ONNX"), "m1")
        modelDir.mkdirs()
        File(modelDir, "encoder.onnx").writeBytes(byteArrayOf(1, 2))
        File(modelDir, "decoder.onnx.part").writeBytes(byteArrayOf(3))
        File(modelDir, "decoder.onnx.part.meta").writeText("meta")

        manager.cancelDownload("m1")

        coVerify { repository.deleteById("m1") }
        assertFalse(modelDir.exists())
        modelsDir.deleteRecursively()
    }

    @Test
    fun `cancel download removes orphan single-file target from runtime root`() = runTest {
        // Одиночный ONNX скачивается в корень каталога рантайма — при отмене
        // удаляется и целевой файл, и части докачки.
        val modelsDir = tempModelsDir()
        every { context.getExternalFilesDir("models") } returns modelsDir
        val model = partialModel("m2", listOf("http://host/opus.onnx"))
        coEvery { repository.getById("m2") } returns model
        val runtimeDir = File(modelsDir, "MARIAN_ONNX").apply { mkdirs() }
        File(runtimeDir, "opus.onnx").writeBytes(byteArrayOf(1))
        File(runtimeDir, "opus.onnx.part").writeBytes(byteArrayOf(2))
        File(runtimeDir, "opus.onnx.part.meta").writeText("meta")

        manager.cancelDownload("m2")

        coVerify { repository.deleteById("m2") }
        assertFalse(File(runtimeDir, "opus.onnx").exists())
        assertFalse(File(runtimeDir, "opus.onnx.part").exists())
        assertFalse(File(runtimeDir, "opus.onnx.part.meta").exists())
        modelsDir.deleteRecursively()
    }

    @Test
    fun `repeated import removes superseded imported file`() = runTest {
        // Issue #40: повторный импорт пишет новый imported_* файл — старый
        // после upsert должен быть удалён.
        val modelsDir = tempModelsDir()
        every { context.getExternalFilesDir("models") } returns modelsDir
        val oldFile = File(modelsDir, "imported_1000").apply { writeBytes(byteArrayOf(9)) }
        val previous = readyModel("model", ModelCategory.TRANSLATION).copy(
            filePath = oldFile.absolutePath,
            isBuiltin = false
        )
        coEvery { repository.getById("model") } returns previous
        val newContent = byteArrayOf(7, 7)
        every { context.contentResolver.openInputStream(any()) } returnsMany listOf(
            ByteArrayInputStream(newContent),
            ByteArrayInputStream(newContent)
        )
        val uri = mockk<Uri>()
        every { uri.getQueryParameter("name") } returns "model.onnx"

        val importedId = manager.importFromUri(uri)

        assertEquals("model", importedId)
        assertFalse(oldFile.exists())
        val imports = modelsDir.listFiles()!!.filter { it.name.startsWith("imported_") }
        assertEquals(1, imports.size)
        assertArrayEquals(newContent, imports.first().readBytes())
        modelsDir.deleteRecursively()
    }
}
