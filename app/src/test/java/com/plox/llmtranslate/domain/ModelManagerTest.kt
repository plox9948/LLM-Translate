package com.plox.llmtranslate.domain

import android.content.Context
import android.net.Uri
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.plox.llmtranslate.data.catalog.ModelCatalogProvider
import com.plox.llmtranslate.data.model.ModelRepository
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.model.DeviceInfo
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import com.plox.llmtranslate.domain.model.TtsModelSelector
import com.plox.llmtranslate.domain.runtime.RuntimeRouter
import com.plox.llmtranslate.testutil.gzip
import com.plox.llmtranslate.testutil.sha256HexOf
import com.plox.llmtranslate.testutil.zipOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private val workManager = mockk<WorkManager>(relaxed = true)

    private lateinit var manager: ModelManagerImpl

    @Before
    fun setup() {
        // WorkManager в юнит-тесте не инициализирован — мокаем getInstance().
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager
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

    @Test
    fun `single file import replacing catalog model removes orphan runtime bundle`() = runTest {
        // Issue #69: замещение каталожной модели (пучок models/<RUNTIME>/<id>)
        // одиночным файлом — старый пучок удаляется целиком после releaseModel.
        val modelsDir = tempModelsDir()
        every { context.getExternalFilesDir("models") } returns modelsDir
        val bundleDir = File(File(modelsDir, "MARIAN_ONNX"), "model")
        bundleDir.mkdirs()
        File(bundleDir, "encoder.onnx").writeBytes(byteArrayOf(1, 2))
        val previous = readyModel("model", ModelCategory.TRANSLATION).copy(
            filePath = bundleDir.absolutePath
        )
        coEvery { repository.getById("model") } returns previous
        val newContent = byteArrayOf(7)
        every { context.contentResolver.openInputStream(any()) } returnsMany listOf(
            ByteArrayInputStream(newContent),
            ByteArrayInputStream(newContent)
        )
        val uri = mockk<Uri>()
        every { uri.getQueryParameter("name") } returns "model.onnx"

        val importedId = manager.importFromUri(uri)

        assertEquals("model", importedId)
        assertFalse(bundleDir.exists())
        coVerify { runtimeRouter.releaseModel(previous) }
        val imports = modelsDir.listFiles()!!.filter { it.name.startsWith("imported_") }
        assertEquals(1, imports.size)
        assertArrayEquals(newContent, imports.first().readBytes())
        modelsDir.deleteRecursively()
    }

    @Test
    fun `zip import releases engine before overwriting model files`() = runTest {
        // Issue #69: старая запись той же id должна отпускать движок ДО
        // in-place перезаписи файлов пучка (промоут живого mmap), иначе
        // активный инференс читает обрезанные данные.
        val modelsDir = tempModelsDir()
        every { context.getExternalFilesDir("models") } returns modelsDir
        // Временный каталог распаковки ZIP берётся из cacheDir — мокаем реальным каталогом.
        every { context.cacheDir } returns tempModelsDir()
        val bundleDir = File(File(modelsDir, "SHERPA"), "zip-model")
        bundleDir.mkdirs()
        val sharedFile = File(bundleDir, "model.bin").apply { writeBytes(byteArrayOf(1)) }
        val previous = readyModel("zip-model", ModelCategory.STT).copy(
            runtime = RuntimeType.SHERPA,
            filePath = bundleDir.absolutePath
        )
        coEvery { repository.getById("zip-model") } returns previous
        // releaseModel обязан наступить, пока старый файл ещё не перетёрт:
        // снимок содержимого ловим на момент eviction.
        var releasedContent: ByteArray? = null
        coEvery { runtimeRouter.releaseModel(any()) } answers {
            releasedContent = sharedFile.readBytes()
            Unit
        }

        val newContent = byteArrayOf(9, 9)
        val zip = zipOf(mapOf("model.bin" to newContent))
        every { context.contentResolver.openInputStream(any()) } returnsMany listOf(
            ByteArrayInputStream(zip),
            ByteArrayInputStream(zip)
        )
        val uri = mockk<Uri>()
        every { uri.getQueryParameter("name") } returns "zip-model.zip"

        val importedId = manager.importFromUri(uri)
        assertEquals("zip-model", importedId)
        coVerify(exactly = 1) { runtimeRouter.releaseModel(previous) }
        assertArrayEquals(byteArrayOf(1), releasedContent)
        assertArrayEquals(newContent, sharedFile.readBytes())
        modelsDir.deleteRecursively()
    }

    @Test
    fun `zip import replacing single imported file removes orphan imported file`() = runTest {
        // Issue #72: прежний одиночный импорт (imported_* в корне models/),
        // замещённый ZIP-импортом той же id, обязан быть удалён после промоута
        // пучка — иначе файл сиротеет на диске навсегда.
        val modelsDir = tempModelsDir()
        every { context.getExternalFilesDir("models") } returns modelsDir
        every { context.cacheDir } returns tempModelsDir()
        val oldFile = File(modelsDir, "imported_1000").apply { writeBytes(byteArrayOf(9)) }
        val previous = readyModel("zip-model", ModelCategory.STT).copy(
            runtime = RuntimeType.SHERPA,
            filePath = oldFile.absolutePath,
            isBuiltin = false
        )
        coEvery { repository.getById("zip-model") } returns previous

        val newContent = byteArrayOf(9, 9)
        val zip = zipOf(mapOf("model.bin" to newContent))
        every { context.contentResolver.openInputStream(any()) } returnsMany listOf(
            ByteArrayInputStream(zip),
            ByteArrayInputStream(zip)
        )
        val uri = mockk<Uri>()
        every { uri.getQueryParameter("name") } returns "zip-model.zip"

        val importedId = manager.importFromUri(uri)

        assertEquals("zip-model", importedId)
        assertFalse(oldFile.exists())
        assertTrue(modelsDir.listFiles()!!.none { it.name.startsWith("imported_") })
        val bundled = File(File(File(modelsDir, "SHERPA"), "zip-model"), "model.bin")
        assertTrue(bundled.exists())
        assertArrayEquals(newContent, bundled.readBytes())
        coVerify(exactly = 1) { runtimeRouter.releaseModel(previous) }
        modelsDir.deleteRecursively()
    }

    @Test
    fun `repeated zip import replaces catalog without leftover old files`() = runTest {
        // Issue #73: промоут слиянием (copyRecursively overwrite=true) оставлял
        // в каталоге файлы прежнего ZIP, отсутствующие в новом архиве, —
        // движок мог молча выбрать старый бинарник. Цель заменяется целиком.
        val modelsDir = tempModelsDir()
        every { context.getExternalFilesDir("models") } returns modelsDir
        every { context.cacheDir } returns tempModelsDir()
        var current: InstalledModel? = null
        coEvery { repository.getById("zip-model") } answers { current }
        coEvery { repository.upsert(any()) } answers {
            current = firstArg()
            Unit
        }

        val zip1 = zipOf(mapOf("a.bin" to byteArrayOf(1), "b.bin" to byteArrayOf(2)))
        val zip2 = zipOf(mapOf("a.bin" to byteArrayOf(3), "c.bin" to byteArrayOf(4)))
        every { context.contentResolver.openInputStream(any()) } returnsMany listOf(
            ByteArrayInputStream(zip1),
            ByteArrayInputStream(zip1),
            ByteArrayInputStream(zip2),
            ByteArrayInputStream(zip2)
        )
        val uri = mockk<Uri>()
        every { uri.getQueryParameter("name") } returns "zip-model.zip"

        assertEquals("zip-model", manager.importFromUri(uri))
        assertEquals("zip-model", manager.importFromUri(uri))

        val bundleDir = File(File(modelsDir, "SHERPA"), "zip-model")
        val names = bundleDir.walkTopDown().filter { it.isFile }.map { it.name }.toSet()
        assertEquals(setOf("a.bin", "c.bin"), names)
        assertArrayEquals(byteArrayOf(3), File(bundleDir, "a.bin").readBytes())
        assertArrayEquals(byteArrayOf(4), File(bundleDir, "c.bin").readBytes())
        // Issue #74: staging-подмена не должна оставлять служебных каталогов
        // (.promoting / .old) после успешного свопа.
        val runtimeDir = File(modelsDir, "SHERPA")
        assertTrue(runtimeDir.listFiles()!!.none { it.name.endsWith(".promoting") })
        assertTrue(runtimeDir.listFiles()!!.none { it.name.endsWith(".old") })
        modelsDir.deleteRecursively()
    }

    @Test
    fun `failed zip promotion keeps previous bundle intact`() = runTest {
        // Issue #74: прежний wipe-then-copy уничтошал рабочий пучок до
        // копирования — отказ copyRecursively (нехватка места) оставлял БД
        // READY на несуществующие файлы (падение на ensureLoaded). Отказ
        // копирования инъектирован через seam copyExtractedToStaging
        // (смоделировать ENOSPC на FS сложно): прежний пучок обязан выжить,
        // upsert в БД не должен наступить, staging — удалён.
        val modelsDir = tempModelsDir()
        every { context.getExternalFilesDir("models") } returns modelsDir
        every { context.cacheDir } returns tempModelsDir()
        val bundleDir = File(File(modelsDir, "SHERPA"), "zip-model")
        bundleDir.mkdirs()
        File(bundleDir, "old.bin").writeBytes(byteArrayOf(1))
        val previous = readyModel("zip-model", ModelCategory.STT).copy(
            runtime = RuntimeType.SHERPA,
            filePath = bundleDir.absolutePath
        )
        coEvery { repository.getById("zip-model") } returns previous
        val managerSpy = spyk(manager)
        coEvery { managerSpy.copyExtractedToStaging(any(), any()) } returns false

        val zip = zipOf(mapOf("new.bin" to byteArrayOf(9, 9)))
        every { context.contentResolver.openInputStream(any()) } returnsMany listOf(
            ByteArrayInputStream(zip),
            ByteArrayInputStream(zip)
        )
        val uri = mockk<Uri>()
        every { uri.getQueryParameter("name") } returns "zip-model.zip"

        val importedId = managerSpy.importFromUri(uri)

        assertNull(importedId)
        coVerify(exactly = 0) { repository.upsert(any()) }
        // Прежний пучок цел и нетронут.
        assertTrue(bundleDir.exists())
        assertArrayEquals(
            byteArrayOf(1),
            File(bundleDir, "old.bin").readBytes()
        )
        assertFalse(File(bundleDir, "new.bin").exists())
        // Staging удалён, записей-остатков в каталоге рантайма нет.
        assertFalse(File(File(modelsDir, "SHERPA"), "zip-model.promoting").exists())
        assertFalse(File(File(modelsDir, "SHERPA"), "zip-model.old").exists())
        modelsDir.deleteRecursively()
    }

    @Test
    fun `concurrent zip imports of the same id are serialized`() = runBlocking<Unit> {
        // Issue #75: два параллельных ZIP-импорта одной id работали с общим
        // staging `<id>.promoting` — вторая стартовая уборка deleteRecursively
        // рвала staging первого посреди копирования (смесь версий при READY).
        // Issue #76: seam copyExtractedToStaging теперь suspend и вместо
        // Thread.sleep(блокирующего поток) делает delay(80) — настоящее
        // переключение корутин: без importMutex оба импорта гарантированно
        // оказываются внутри seam одновременно (maxActive=2, тест красный).
        // Со счётчиком активных копирований; целевой каталог — ровно из одной
        // версии без перемешивания и без остатков .promoting/.old.
        val modelsDir = tempModelsDir()
        every { context.getExternalFilesDir("models") } returns modelsDir
        every { context.cacheDir } returns tempModelsDir()
        var current: InstalledModel? = null
        coEvery { repository.getById("zip-model") } answers { current }
        coEvery { repository.upsert(any()) } answers {
            current = firstArg()
            Unit
        }

        val managerSpy = spyk(manager)
        val active = java.util.concurrent.atomic.AtomicInteger(0)
        val maxActive = java.util.concurrent.atomic.AtomicInteger(0)
        coEvery { managerSpy.copyExtractedToStaging(any(), any()) } coAnswers {
            val now = active.incrementAndGet()
            maxActive.updateAndGet { m -> maxOf(m, now) }
            delay(80)
            val copied = callOriginal()
            active.decrementAndGet()
            copied
        }

        val zip = zipOf(mapOf("a.bin" to byteArrayOf(1), "b.bin" to byteArrayOf(2)))
        every { context.contentResolver.openInputStream(any()) } answers {
            ByteArrayInputStream(zip)
        }
        val uri = mockk<Uri>()
        every { uri.getQueryParameter("name") } returns "zip-model.zip"

        val first = async { managerSpy.importFromUri(uri) }
        val second = async { managerSpy.importFromUri(uri) }
        joinAll(first, second)

        assertEquals("zip-model", first.await())
        assertEquals("zip-model", second.await())
        assertEquals(
            "копирования не должны перекрываться",
            1,
            maxActive.get()
        )
        val bundleDir = File(File(modelsDir, "SHERPA"), "zip-model")
        val names = bundleDir.walkTopDown().filter { it.isFile }.map { it.name }.toSet()
        assertEquals(setOf("a.bin", "b.bin"), names)
        assertArrayEquals(byteArrayOf(1), File(bundleDir, "a.bin").readBytes())
        assertArrayEquals(byteArrayOf(2), File(bundleDir, "b.bin").readBytes())
        val runtimeDir = File(modelsDir, "SHERPA")
        assertTrue(runtimeDir.listFiles()!!.none { it.name.endsWith(".promoting") })
        assertTrue(runtimeDir.listFiles()!!.none { it.name.endsWith(".old") })
        modelsDir.deleteRecursively()
    }

    @Test
    fun `repeated zip import of same id keeps promoted bundle`() = runTest {
        // Issue #72 (guard): ZIP -> ZIP той же id промоутит пучок в тот же
        // путь — прежний filePath совпадает с новым, чистка не должна удалить
        // актуальный пучок.
        val modelsDir = tempModelsDir()
        every { context.getExternalFilesDir("models") } returns modelsDir
        every { context.cacheDir } returns tempModelsDir()
        val bundleDir = File(File(modelsDir, "SHERPA"), "zip-model")
        bundleDir.mkdirs()
        File(bundleDir, "model.bin").writeBytes(byteArrayOf(1))
        val previous = readyModel("zip-model", ModelCategory.STT).copy(
            runtime = RuntimeType.SHERPA,
            filePath = bundleDir.absolutePath
        )
        coEvery { repository.getById("zip-model") } returns previous

        val newContent = byteArrayOf(7, 7)
        val zip = zipOf(mapOf("model.bin" to newContent))
        every { context.contentResolver.openInputStream(any()) } returnsMany listOf(
            ByteArrayInputStream(zip),
            ByteArrayInputStream(zip)
        )
        val uri = mockk<Uri>()
        every { uri.getQueryParameter("name") } returns "zip-model.zip"

        val importedId = manager.importFromUri(uri)

        assertEquals("zip-model", importedId)
        val bundled = File(bundleDir, "model.bin")
        assertTrue(bundleDir.exists())
        assertTrue(bundled.exists())
        assertArrayEquals(newContent, bundled.readBytes())
        modelsDir.deleteRecursively()
    }

    // --- ZIP-импорт из метаданных model_meta.json (issue #80, пачка 3) ---

    /** Метаданные ZIP-экспорта: id каталога "zip-model", файл model.bin по URL. */
    private fun metaJson(
        sha256s: List<String?>? = null,
        isBuiltin: Boolean = true,
        urls: List<String> = listOf("http://host/model.bin"),
        runtime: String = "SHERPA",
        category: String = "STT"
    ): String {
        val shaField = sha256s?.joinToString(",") { s -> s?.let { "\"$it\"" } ?: "null" }
            ?.let { ",\"sha256s\":[$it]" }
            .orEmpty()
        val urlsField = urls.joinToString(",") { url -> "\"$url\"" }
        return """{"id":"zip-model","category":"$category","runtime":"$runtime",""" +
            """"displayName":"Zip model","urls":[$urlsField]""" +
            """$shaField,"isBuiltin":$isBuiltin}"""
    }

    @Test
    fun `zip import with meta registers model as not builtin`() = runTest {
        // Метаданные не могут объявлять встроенность: импортированная под
        // каталожным id модель всегда isBuiltin=false — иначе
        // cleanRemovedCatalogModels удалит её при исчезновении id из каталога.
        val modelsDir = tempModelsDir()
        every { context.getExternalFilesDir("models") } returns modelsDir
        every { context.cacheDir } returns tempModelsDir()
        coEvery { repository.getById("zip-model") } returns null
        val newContent = byteArrayOf(9, 9)
        val zip = zipOf(
            linkedMapOf(
                "model_meta.json" to metaJson(
                    sha256s = listOf(sha256HexOf(newContent)), isBuiltin = true
                ).toByteArray(),
                "model.bin" to newContent
            )
        )
        every { context.contentResolver.openInputStream(any()) } returnsMany listOf(
            ByteArrayInputStream(zip),
            ByteArrayInputStream(zip)
        )
        val imported = mutableListOf<InstalledModel>()
        coEvery { repository.upsert(capture(imported)) } returns Unit
        val uri = mockk<Uri>()
        every { uri.getQueryParameter("name") } returns "exported.zip"

        assertEquals("zip-model", manager.importFromUri(uri))

        assertEquals(1, imported.size)
        assertFalse("импорт обязан force isBuiltin=false", imported.first().isBuiltin)
        assertEquals(ModelStatus.READY, imported.first().status)
        assertArrayEquals(
            newContent,
            File(File(File(modelsDir, "SHERPA"), "zip-model"), "model.bin").readBytes()
        )
        modelsDir.deleteRecursively()
    }

    @Test
    fun `zip import with meta rejects sha256 mismatch before promotion`() = runTest {
        // Импортируемая модель получает каталожный id — фактические хеши
        // файлов сверяются с ожидаемыми из метаданных; несовпадение — отказ
        // импорта ДО промоута (прежний пучок и staging нетронуты, upsert нет).
        val modelsDir = tempModelsDir()
        every { context.getExternalFilesDir("models") } returns modelsDir
        every { context.cacheDir } returns tempModelsDir()
        coEvery { repository.getById("zip-model") } returns null
        val zip = zipOf(
            linkedMapOf(
                "model_meta.json" to metaJson(sha256s = listOf("0".repeat(64))).toByteArray(),
                "model.bin" to byteArrayOf(9, 9)
            )
        )
        every { context.contentResolver.openInputStream(any()) } returnsMany listOf(
            ByteArrayInputStream(zip),
            ByteArrayInputStream(zip)
        )
        val uri = mockk<Uri>()
        every { uri.getQueryParameter("name") } returns "exported.zip"

        assertNull(manager.importFromUri(uri))

        coVerify(exactly = 0) { repository.upsert(any()) }
        assertFalse(File(File(modelsDir, "SHERPA"), "zip-model").exists())
        modelsDir.deleteRecursively()
    }

    @Test
    fun `zip import with meta verifies sha256 from existing catalog record`() = runTest {
        // В метаданных хэшей нет — ожидаемые берутся из каталожной записи;
        // несовпадение — отказ, прежний пучок цел.
        val modelsDir = tempModelsDir()
        every { context.getExternalFilesDir("models") } returns modelsDir
        every { context.cacheDir } returns tempModelsDir()
        val bundleDir = File(File(modelsDir, "SHERPA"), "zip-model").apply { mkdirs() }
        File(bundleDir, "old.bin").writeBytes(byteArrayOf(1))
        val previous = readyModel("zip-model", ModelCategory.STT).copy(
            runtime = RuntimeType.SHERPA,
            filePath = bundleDir.absolutePath,
            sha256s = listOf("f".repeat(64))
        )
        coEvery { repository.getById("zip-model") } returns previous
        val zip = zipOf(
            linkedMapOf(
                "model_meta.json" to metaJson(sha256s = null).toByteArray(),
                "model.bin" to byteArrayOf(9, 9)
            )
        )
        every { context.contentResolver.openInputStream(any()) } returnsMany listOf(
            ByteArrayInputStream(zip),
            ByteArrayInputStream(zip)
        )
        val uri = mockk<Uri>()
        every { uri.getQueryParameter("name") } returns "exported.zip"

        assertNull(manager.importFromUri(uri))

        coVerify(exactly = 0) { repository.upsert(any()) }
        assertArrayEquals(byteArrayOf(1), File(bundleDir, "old.bin").readBytes())
        assertFalse(File(bundleDir, "model.bin").exists())
        modelsDir.deleteRecursively()
    }

    @Test
    fun `zip import with meta accepts slimt gz urls round-trip`() = runTest {
        // Контрольный аудит, п.1: slimt-пачка скачивается сжатыми артефактами
        // (*.bin.gz/*.spm.gz), в пучке и в ZIP-экспорте — РАСПАКОВАННЫЕ имена,
        // ожидаемые sha в meta — хеши сжатых байт. Сверка распакованных файлов
        // с ими/хешами сжатых URL формально недостижима — проверка для таких
        // URL пропускается, round-trip собственного slimt-экспорта принимается.
        val modelsDir = tempModelsDir()
        every { context.getExternalFilesDir("models") } returns modelsDir
        every { context.cacheDir } returns tempModelsDir()
        coEvery { repository.getById("zip-model") } returns null
        val bin = byteArrayOf(1, 2, 3)
        val spm = byteArrayOf(4, 5)
        val zip = zipOf(
            linkedMapOf(
                "model_meta.json" to metaJson(
                    sha256s = listOf(sha256HexOf(gzip(bin)), sha256HexOf(gzip(spm))),
                    urls = listOf(
                        "http://host/en-ru.model.bin.gz",
                        "http://host/en-ru.model.spm.gz"
                    ),
                    runtime = "MARIAN_SLIMT",
                    category = "TRANSLATION"
                ).toByteArray(),
                "en-ru.model.bin" to bin,
                "en-ru.model.spm" to spm
            )
        )
        every { context.contentResolver.openInputStream(any()) } returnsMany listOf(
            ByteArrayInputStream(zip),
            ByteArrayInputStream(zip)
        )
        val uri = mockk<Uri>()
        every { uri.getQueryParameter("name") } returns "exported.zip"

        assertEquals("zip-model", manager.importFromUri(uri))

        val bundled = File(File(modelsDir, "MARIAN_SLIMT"), "zip-model")
        assertArrayEquals(bin, File(bundled, "en-ru.model.bin").readBytes())
        assertArrayEquals(spm, File(bundled, "en-ru.model.spm").readBytes())
        modelsDir.deleteRecursively()
    }

    @Test
    fun `zip import rejects oversized meta entry`() = runTest {
        // model_meta.json больше лимита 1 МиБ — явная ошибка импорта,
        // а не бесконечное readBytes() в память.
        val modelsDir = tempModelsDir()
        every { context.getExternalFilesDir("models") } returns modelsDir
        every { context.cacheDir } returns tempModelsDir()
        val zip = zipOf(
            linkedMapOf(
                "model_meta.json" to ByteArray((1 shl 20) + 16) { ' '.code.toByte() },
                "model.bin" to byteArrayOf(5)
            )
        )
        every { context.contentResolver.openInputStream(any()) } returnsMany listOf(
            ByteArrayInputStream(zip),
            ByteArrayInputStream(zip)
        )
        val uri = mockk<Uri>()
        every { uri.getQueryParameter("name") } returns "exported.zip"

        assertNull(manager.importFromUri(uri))

        coVerify(exactly = 0) { repository.upsert(any()) }
        modelsDir.deleteRecursively()
    }

    @Test
    fun `import cancels live download of same id and removes pending parts`() = runTest {
        val modelsDir = tempModelsDir()
        every { context.getExternalFilesDir("models") } returns modelsDir
        val runtimeDir = File(modelsDir, "MARIAN_ONNX").apply { mkdirs() }
        File(runtimeDir, "live1__opus.onnx.part").writeText("p")
        File(runtimeDir, "live1__opus.onnx.part.meta").writeText("m")
        File(runtimeDir, "opus.onnx.part").writeText("legacy-part")
        File(runtimeDir, "other_model__x.onnx.part").writeText("untouched")
        val previous = partialModel("live1", listOf("http://host/opus.onnx"))
        coEvery { repository.getById("live1") } returns previous
        // Живая unique-загрузка live1 в WorkManager: первая проверка — ENQUEUED,
        // после cancelUniqueWork — терминальный CANCELED (awaitWorkCancellation
        // не должен ждать реальных таймаутов WorkManager в юнит-тесте).
        val liveInfo = mockk<WorkInfo>()
        every { liveInfo.state } returns WorkInfo.State.ENQUEUED
        val cancelledInfo = mockk<WorkInfo>()
        every { cancelledInfo.state } returns WorkInfo.State.CANCELLED
        val liveFuture = mockk<com.google.common.util.concurrent.ListenableFuture<List<WorkInfo>>>()
        every { liveFuture.get(any(), any()) } returns listOf(liveInfo)
        val cancelledFuture = mockk<com.google.common.util.concurrent.ListenableFuture<List<WorkInfo>>>()
        every { cancelledFuture.get(any(), any()) } returns listOf(cancelledInfo)
        every {
            workManager.getWorkInfosForUniqueWork("download_model_live1")
        } returnsMany listOf(liveFuture, cancelledFuture)
        val newContent = byteArrayOf(4)
        every { context.contentResolver.openInputStream(any()) } returnsMany listOf(
            ByteArrayInputStream(newContent),
            ByteArrayInputStream(newContent)
        )
        val uri = mockk<Uri>()
        every { uri.getQueryParameter("name") } returns "live1.onnx"

        assertEquals("live1", manager.importFromUri(uri))

        verify { workManager.cancelUniqueWork("download_model_live1") }
        // Части докачки live1 (обе схемы имён) удалены, чужие — целы.
        assertFalse(File(runtimeDir, "live1__opus.onnx.part").exists())
        assertFalse(File(runtimeDir, "live1__opus.onnx.part.meta").exists())
        assertFalse(File(runtimeDir, "opus.onnx.part").exists())
        assertTrue(File(runtimeDir, "other_model__x.onnx.part").exists())
        modelsDir.deleteRecursively()
    }

    @Test
    fun `zip import cancels live download of same id before promotion`() = runTest {
        // Ревью: отмена живой загрузки той же id обязана произойти ДО промоута
        // пучка (как в одиночном импорте) — cleanupCancelledModel отменённого
        // воркера не должен зайти на уже промоутенные файлы.
        val modelsDir = tempModelsDir()
        every { context.getExternalFilesDir("models") } returns modelsDir
        every { context.cacheDir } returns tempModelsDir()
        coEvery { repository.getById("zip-model") } returns null
        // Первая проверка живой загрузки — ENQUEUED; после cancelUniqueWork
        // опрос статуса возвращает терминальный CANCELED (без реальных задержек).
        val liveInfo = mockk<WorkInfo>()
        every { liveInfo.state } returns WorkInfo.State.ENQUEUED
        val cancelledInfo = mockk<WorkInfo>()
        every { cancelledInfo.state } returns WorkInfo.State.CANCELLED
        val liveFuture = mockk<com.google.common.util.concurrent.ListenableFuture<List<WorkInfo>>>()
        every { liveFuture.get(any(), any()) } returns listOf(liveInfo)
        val cancelledFuture = mockk<com.google.common.util.concurrent.ListenableFuture<List<WorkInfo>>>()
        every { cancelledFuture.get(any(), any()) } returns listOf(cancelledInfo)
        every {
            workManager.getWorkInfosForUniqueWork("download_model_zip-model")
        } returnsMany listOf(liveFuture, cancelledFuture)

        var step = 0
        var cancelledAt = -1
        var promotedAt = -1
        every { workManager.cancelUniqueWork("download_model_zip-model") } answers {
            cancelledAt = step++
            mockk<androidx.work.Operation>(relaxed = true)
        }
        val managerSpy = spyk(manager)
        coEvery { managerSpy.copyExtractedToStaging(any(), any()) } coAnswers {
            promotedAt = step++
            callOriginal()
        }

        val newContent = byteArrayOf(9, 9)
        val zip = zipOf(mapOf("model.bin" to newContent))
        every { context.contentResolver.openInputStream(any()) } answers {
            ByteArrayInputStream(zip)
        }
        val uri = mockk<Uri>()
        every { uri.getQueryParameter("name") } returns "zip-model.zip"

        assertEquals("zip-model", managerSpy.importFromUri(uri))

        assertTrue("Живая загрузка не отменена", cancelledAt >= 0)
        assertTrue(
            "Отмена живой загрузки обязана предшествовать промоуту пучка",
            cancelledAt < promotedAt
        )
        // Промоутенный пучок на месте и не задет очисткой отменённой загрузки.
        val bundled = File(File(modelsDir, "SHERPA"), "zip-model/model.bin")
        assertArrayEquals(newContent, bundled.readBytes())
        modelsDir.deleteRecursively()
    }

    private fun cardForDownload(id: String) = ModelCard(
        id = id,
        category = ModelCategory.TRANSLATION,
        displayName = "Card",
        description = "",
        runtime = RuntimeType.MARIAN_ONNX,
        sizeBytes = 1000,
        ramRequiredMb = 10,
        languages = listOf("ru"),
        quality = 3,
        speed = 3,
        url = "http://host/model.onnx",
        urls = emptyList(),
        sha256 = null,
        license = "MIT",
        minAndroidSdk = 26,
        recommendedRamMb = 100
    )

    @Test
    fun `startDownload updates DOWNLOADING after scheduling the worker`() = runTest {
        // Ревью, п.4: upsert DOWNLOADING только после успешной постановки
        // воркера — порядок фиксируется счётчиком вызовов.
        val calls = mutableListOf<String>()
        every {
            workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>())
        } answers {
            calls.add("schedule")
            mockk<androidx.work.Operation>(relaxed = true)
        }
        var upserted: InstalledModel? = null
        coEvery { repository.upsert(any()) } coAnswers {
            calls.add("upsert")
            upserted = firstArg()
        }

        manager.startDownload(cardForDownload("dl1"))

        assertEquals(listOf("schedule", "upsert"), calls)
        assertEquals(ModelStatus.DOWNLOADING, upserted?.status)
    }

    @Test
    fun `startDownload marks error when scheduling fails`() = runTest {
        every {
            workManager.enqueueUniqueWork(
                any(),
                any(),
                any<OneTimeWorkRequest>()
            )
        } throws IllegalStateException("WorkManager недоступен")

        manager.startDownload(cardForDownload("dl2"))

        // Отказ schedule — upsert (DOWNLOADING) не выполняется вовсе, а
        // уже возможная строка DOWNLOADING переводится в ERROR, иначе UI
        // навсегда остался бы в pending-гарде без кнопки «Повторить».
        coVerify(exactly = 0) { repository.upsert(any()) }
        coVerify(exactly = 1) { repository.updateDownloadStatus("dl2", ModelStatus.ERROR) }
    }

    @Test
    fun `startDownload keeps status when upsert fails after successful scheduling`() = runTest {
        // Контрольное ревью, п.3: при отказе upsert ПОСЛЕ успешной постановки
        // воркера статус записи не меняется в ERROR — живой воркер на
        // finalize-гейте (WHERE status='DOWNLOADING') иначе потерял бы
        // установленные файлы готовой модели.
        every {
            workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>())
        } returns mockk<androidx.work.Operation>(relaxed = true)
        coEvery { repository.upsert(any()) } throws RuntimeException("db write failed")

        manager.startDownload(cardForDownload("dl3"))

        coVerify(exactly = 0) { repository.updateDownloadStatus(any(), any()) }
    }

    @Test
    fun `delete during running import leaves consistent state`() = runBlocking<Unit> {
        // Issue #76: delete() был fire-and-forget вне importMutex — гонка
        // import↔delete одной id: delete удалял промоутенный каталог, и
        // deleteById коммитился раньше upsert импорта → висячий READY на
        // несуществующие файлы; в обратном порядке upsert затирал удалённую
        // запись и на диске навечно оставались файлы без записи. Теперь
        // delete под тем же importMutex: финал согласован — либо READY-запись
        // с существующим каталогом, либо ни записи, ни файлов.
        val modelsDir = tempModelsDir()
        every { context.getExternalFilesDir("models") } returns modelsDir
        every { context.cacheDir } returns tempModelsDir()

        // Фейковый репозиторий: одна запись, upsert/deleteById мутируют её;
        // upsert имитирует транзакцию Room с задержкой — в это окно без локов
        // и попадает гонящий delete (удаляет уже промоутенный, но ещё не
        // зарегистрированный каталог → READY на пустоту).
        val bundleDir = File(File(modelsDir, "SHERPA"), "zip-model")
        bundleDir.mkdirs()
        File(bundleDir, "old.bin").writeBytes(byteArrayOf(1))
        var current: InstalledModel? = readyModel("zip-model", ModelCategory.STT).copy(
            runtime = RuntimeType.SHERPA,
            filePath = bundleDir.absolutePath
        )
        val upsertStarted = CompletableDeferred<Unit>()
        coEvery { repository.getById("zip-model") } answers { current }
        coEvery { repository.upsert(any()) } coAnswers {
            upsertStarted.complete(Unit)
            delay(200)
            current = firstArg()
            Unit
        }
        coEvery { repository.deleteById(any()) } answers {
            current = null
            Unit
        }

        val managerSpy = spyk(manager)
        coEvery { managerSpy.copyExtractedToStaging(any(), any()) } coAnswers {
            delay(80)
            callOriginal()
        }

        val zip = zipOf(mapOf("new.bin" to byteArrayOf(9)))
        every { context.contentResolver.openInputStream(any()) } answers {
            ByteArrayInputStream(zip)
        }
        val uri = mockk<Uri>()
        every { uri.getQueryParameter("name") } returns "zip-model.zip"

        val imported = async { managerSpy.importFromUri(uri) }
        // Импорт уже промоутнул каталог (staging заменён) и вошёл в upsert —
        // окно, в которое раньше delete влезал посреди транзакции.
        upsertStarted.await()
        managerSpy.delete("zip-model")
        assertEquals("zip-model", imported.await())
        delay(400) // ждём до_completion delete'а, отпускающего importMutex

        val record = current
        val bundleReady = bundleDir.exists() && bundleDir.walkTopDown().any { it.isFile }
        val leftoverFiles = modelsDir.walkTopDown().any { it.isFile }
        if (record != null && record.status == ModelStatus.READY) {
            // Вариант «delete отработал ДО импорта»: импортированная модель
            // обязана иметь на диске свой непустой каталог.
            assertTrue("READY-запись без файлов на диске", bundleReady)
            assertFalse("Осиротевшие файлы вне каталога модели", leftoverFiles && !bundleReady)
        } else {
            // Вариант «delete отработал ПОСЛЕ импорта» (детеминирован под
            // importMutex): записи и файлов не должно остаться вовсе.
            assertNull("Запись осталась без каталога", record)
            assertFalse("Удалённая модель оставила файлы", leftoverFiles)
        }
        modelsDir.deleteRecursively()
    }
}
