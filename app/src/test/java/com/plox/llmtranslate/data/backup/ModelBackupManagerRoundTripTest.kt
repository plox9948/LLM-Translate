package com.plox.llmtranslate.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.plox.llmtranslate.data.model.ModelRepository
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import com.plox.llmtranslate.domain.model.TtsModelSelector
import com.plox.llmtranslate.domain.runtime.RuntimeRouter
import com.plox.llmtranslate.testutil.sha256HexOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Сквозной тест roundtrip [ModelBackupManager]: экспорт N готовых моделей
 * в ZIP-архив и импорт обратно — импортироваться должны все N.
 *
 * Ловит баг issue #31: при импорте путь временного файла строился как
 * MODELS_PREFIX + entry.relativePath, хотя relativePath уже начинается с
 * "models/" (см. toRelativePath) → иск "models/models/..." → «Файл модели
 * не найден в архиве», импорт 0 из N.
 *
 * Context/ContentResolver/Uri — mockk, файловые операции — на реальном FS
 * во временном каталогеJUnit TemporaryFolder.
 */
class ModelBackupManagerRoundTripTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var modelsRoot: File
    private lateinit var cacheDir: File
    private lateinit var zipFile: File

    private val context = mockk<Context>(relaxed = true)
    private val contentResolver = mockk<ContentResolver>(relaxed = true)
    private val repository = mockk<ModelRepository>(relaxed = true)
    private val runtimeRouter = mockk<RuntimeRouter>(relaxed = true)
    private val preferences = mockk<ModelPreferences>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        modelsRoot = tempFolder.newFolder("external", "models")
        cacheDir = tempFolder.newFolder("cache")
        zipFile = File(tempFolder.root, "backup.zip")

        every { context.cacheDir } returns cacheDir
        every { context.getExternalFilesDir("models") } returns modelsRoot
        every { context.contentResolver } returns contentResolver
        every { contentResolver.openOutputStream(any()) } answers { FileOutputStream(zipFile) }
        every { contentResolver.openInputStream(any()) } answers { FileInputStream(zipFile) }

        every { preferences.ttsEngine } returns flowOf("system")
        every { preferences.uiLanguage } returns flowOf("system")
        every { preferences.theme } returns flowOf("system")
        // Настройки, добавленные в бэкап по ревью #53 — потоки для buildSettings.
        every { preferences.sttByLanguage } returns flowOf(mapOf("ru" to "s1"))
        every { preferences.ttsByLanguage } returns flowOf(mapOf("ru" to "system"))
        every { preferences.unloadTimeoutMinutes } returns flowOf(5)
        every { preferences.modelDirectionRetention } returns flowOf("keep")
        every { preferences.lastSourceLang } returns flowOf("ru")
        every { preferences.lastTargetLang } returns flowOf("en")
        coEvery { preferences.getActiveModelId(any()) } returns null
    }

    private fun backupManager() =
        ModelBackupManager(context, repository, runtimeRouter, preferences, json)

    private fun readyModel(
        id: String,
        category: ModelCategory,
        runtime: RuntimeType,
        filePath: String
    ) = InstalledModel(
        id = id,
        category = category,
        displayName = "Model $id",
        description = "desc",
        runtime = runtime,
        sizeBytes = 10,
        ramRequiredMb = 100,
        languages = listOf("ru", "en"),
        quality = 3,
        speed = 4,
        url = "",
        urls = emptyList(),
        sha256 = null,
        license = "Apache-2.0",
        minAndroidSdk = 26,
        recommendedRamMb = 0,
        status = ModelStatus.READY,
        progress = 100,
        filePath = filePath,
        bytesDone = 10,
        isBuiltin = true
    )

    /** Смоделировать установленные модели: файл, файл в подкаталоге, каталог модели. */
    private fun prepareModels(): List<InstalledModel> {
        val single = File(modelsRoot, "marian-ru-en.gguf").apply { writeText("single-bytes") }
        val nested = File(modelsRoot, "nested/stt-model.onnx").apply {
            parentFile!!.mkdirs()
            writeText("nested-bytes")
        }
        val dirModel = File(modelsRoot, "dir-model").apply {
            mkdirs()
            resolve("encoder.onnx").writeText("encoder-bytes")
            resolve("sub").mkdirs()
            resolve("sub/tokens.txt").writeText("tokens-bytes")
        }
        return listOf(
            readyModel("t1", ModelCategory.TRANSLATION, RuntimeType.MARIAN_ONNX, single.absolutePath),
            readyModel("s1", ModelCategory.STT, RuntimeType.SHERPA, nested.absolutePath),
            readyModel("t2", ModelCategory.TRANSLATION, RuntimeType.INDICTRANS_ONNX, dirModel.absolutePath)
        )
    }

    @Test
    fun `backup export then import restores all N of N models`() {
        val models = prepareModels()
        coEvery { repository.observeAll() } returns flowOf(models)
        val upserted = mutableListOf<InstalledModel>()
        coEvery { repository.upsert(capture(upserted)) } returns Unit

        val manager = backupManager()
        val uri = mockk<Uri>()

        runBlocking {
            assertTrue("Экспорт должен завершиться успешно", manager.exportToUri(uri))
            val result = manager.importFromUri(uri)
            assertNull("Импорт не должен возвращать ошибку: ${result.error}", result.error)
            assertEquals(
                "Импортироваться должны все ${models.size} моделей, пропущено: ${result.skippedCount}",
                models.size,
                result.importedCount
            )
            assertEquals(0, result.skippedCount)

            // В репозиторий записаны все модели со статусом READY.
            assertEquals(models.size, upserted.size)
            assertTrue(upserted.all { it.status == ModelStatus.READY })

            // Файлы на местах: одиночный файл, файл в подкаталоге, каталог модели.
            assertEquals("single-bytes", File(modelsRoot, "marian-ru-en.gguf").readText())
            assertEquals("nested-bytes", File(modelsRoot, "nested/stt-model.onnx").readText())
            assertEquals("encoder-bytes", File(modelsRoot, "dir-model/encoder.onnx").readText())
            assertEquals("tokens-bytes", File(modelsRoot, "dir-model/sub/tokens.txt").readText())
        }
    }

    /**
     * Issue #70: повторный импорт из бэкапа подменяет файлы модели той же id —
     * движок обязан отпустить старый пучок ДО подмены файлов и upsert
     * (LRU-кэш движков ключуется по id и иначе держит stale-нативный handle).
     */
    @Test
    fun `import releases cached engine bundle for replaced id before upsert`() {
        val models = prepareModels()
        coEvery { repository.observeAll() } returns flowOf(models)
        // Прежняя запись в БД только у t1 (для остальных id — явный null:
        // relaxed-мок иначе возвращает chained-мок вместо null).
        coEvery { repository.getById(any()) } returns null
        val previousT1 = readyModel(
            "t1", ModelCategory.TRANSLATION, RuntimeType.MARIAN_ONNX,
            File(modelsRoot, "marian-ru-en.gguf").absolutePath
        )
        coEvery { repository.getById("t1") } returns previousT1

        runBlocking {
            val manager = backupManager()
            assertTrue("Экспорт должен завершиться успешно", manager.exportToUri(mockk<Uri>()))
            val result = manager.importFromUri(mockk<Uri>())
            assertNull("Импорт не должен возвращать ошибку: ${result.error}", result.error)
            assertEquals(models.size, result.importedCount)

            // release ровно один раз и только по id с прежней записью в БД…
            coVerify(exactly = 1) { runtimeRouter.releaseModel(previousT1) }
            coVerify(exactly = 1) { runtimeRouter.releaseModel(any()) }
            // …и строго до upsert (подмена файлов происходит между ними).
            coVerifyOrder {
                runtimeRouter.releaseModel(previousT1)
                repository.upsert(any())
            }
        }
    }

    @Test
    fun `import accepts manifest entry relativePath without models prefix`() {
        // Разомкнутый вариант манифеста (compat): relativePath без "models/",
        // файлы архива — под "models/". Импорт обязан найти запись.
        coEvery { repository.observeAll() } returns flowOf(prepareModels())
        val upserted = mutableListOf<InstalledModel>()
        coEvery { repository.upsert(capture(upserted)) } returns Unit

        val manifestOnly = manifestEntry(
            id = "legacy1",
            category = ModelCategory.TRANSLATION,
            runtime = RuntimeType.MARIAN_ONNX,
            // Compat-формат: relativePath без "models/".
            relativePath = "legacy1.gguf"
        )
        writeZipWithModel(
            manifestOnly,
            "legacy-bytes",
            modelEntryPath = MODELS_PREFIX + "legacy1.gguf"
        )

        runBlocking {
            val result = backupManager().importFromUri(mockk<Uri>())
            assertNull("Импорт не должен возвращать ошибку: ${result.error}", result.error)
            assertEquals(1, result.importedCount)
            assertEquals(0, result.skippedCount)
            assertEquals("legacy-bytes", File(modelsRoot, "legacy1.gguf").readText())
        }
    }

    /**
     * Запись манифеста с полями-заглушками (displayName/description/качество),
     * не значимыми для сценариев импорта.
     */
    private fun manifestEntry(
        id: String,
        category: ModelCategory,
        runtime: RuntimeType,
        relativePath: String,
        sha256: String? = null,
        isBuiltin: Boolean = false,
        sizeBytes: Int = 5,
        ramRequiredMb: Int = 100,
        urls: List<String> = emptyList(),
        sha256s: List<String?> = emptyList()
    ) = BackupModelEntry(
        id = id,
        category = category.name,
        displayName = id,
        description = "d",
        runtime = runtime.name,
        sizeBytes = sizeBytes.toLong(),
        ramRequiredMb = ramRequiredMb,
        languages = listOf("ru"),
        quality = 1,
        speed = 1,
        url = "",
        urls = urls,
        sha256 = sha256,
        sha256s = sha256s,
        license = "MIT",
        minAndroidSdk = 26,
        recommendedRamMb = 0,
        relativePath = relativePath,
        isBuiltin = isBuiltin
    )

    /** Entry манифеста одиночного файла с указанным sha256. */
    private fun shaEntry(filePathRel: String, sha: String?) = manifestEntry(
        id = "shamod",
        category = ModelCategory.TRANSLATION,
        runtime = RuntimeType.MARIAN_ONNX,
        relativePath = MODELS_PREFIX + filePathRel,
        sha256 = sha
    )

    /** ZIP с манифестом (одна entry), опциональными settings и файлом модели. */
    private fun writeZipWithModel(
        entry: BackupModelEntry,
        fileBytes: String,
        settings: BackupSettings? = null,
        modelEntryPath: String = entry.relativePath
    ) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putNextEntry(ZipEntry(ModelBackupManager.MANIFEST_NAME))
            zip.write(
                json.encodeToString(
                    BackupManifest.serializer(),
                    BackupManifest(version = 1, exportedAt = 0L, models = listOf(entry))
                ).toByteArray()
            )
            zip.closeEntry()
            settings?.let {
                zip.putNextEntry(ZipEntry(ModelBackupManager.SETTINGS_NAME))
                zip.write(json.encodeToString(BackupSettings.serializer(), it).toByteArray())
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry(modelEntryPath))
            zip.write(fileBytes.toByteArray())
            zip.closeEntry()
        }
    }

    private companion object {
        const val MODELS_PREFIX = "models/"
    }

    @Test
    fun `import skips single-file model whose sha256 mismatches manifest`() {
        // Манифест обещает один хэш, файл в архиве битый — модель не должна
        // регистрироваться READY (ревью раунд 9), остальные импортируются.
        val entry = shaEntry("bad-model.onnx", "0".repeat(64))
        writeZipWithModel(entry, "legacy-bytes")

        runBlocking {
            val result = backupManager().importFromUri(mockk<Uri>())
            assertNull(result.error)
            assertEquals(0, result.importedCount)
            assertEquals(1, result.skippedCount)
            coVerify(exactly = 0) { repository.upsert(any()) }
        }
    }

    @Test
    fun `import accepts single-file model whose sha256 matches manifest`() {
        val expected = sha256HexOf(
            File(tempFolder.root, "expected-bytes.tmp").apply { writeText("legacy-bytes") }
        )
        val entry = shaEntry("good-model.onnx", expected)
        writeZipWithModel(entry, "legacy-bytes")

        runBlocking {
            val result = backupManager().importFromUri(mockk<Uri>())
            assertNull(result.error)
            assertEquals(1, result.importedCount)
            assertEquals(0, result.skippedCount)
            assertEquals("legacy-bytes", File(modelsRoot, "good-model.onnx").readText())
        }
    }

    @Test
    fun `import applies settings model ids only for restored models`() {
        // Настройки ссылаются на id из архива (s1 — восстанавливается) и на
        // отсутствующий ghost — висячие id не должны попадать в настройки;
        // сентинель системного TTS назначается без модели.
        val entry = manifestEntry(
            id = "s1",
            category = ModelCategory.STT,
            runtime = RuntimeType.SHERPA,
            relativePath = MODELS_PREFIX + "stt1.onnx",
            sizeBytes = 4,
            ramRequiredMb = 10,
            isBuiltin = true
        )
        val settings = BackupSettings(
            activeStt = "s1",
            activeTranslation = "ghost",
            sttByLanguage = mapOf("ru" to "s1", "ne" to "ghost"),
            ttsByLanguage = mapOf("ru" to TtsModelSelector.SYSTEM_SENTINEL)
        )
        writeZipWithModel(entry, "stt-bytes", settings = settings)

        runBlocking {
            val result = backupManager().importFromUri(mockk<Uri>())
            assertNull(result.error)
            assertEquals(1, result.importedCount)
            assertTrue(result.settingsRestored)

            coVerify { preferences.setActiveModelId(ModelCategory.STT, "s1") }
            coVerify(exactly = 0) { preferences.setActiveModelId(ModelCategory.TRANSLATION, any()) }
            coVerify { preferences.setSttForLanguage("ru", "s1") }
            coVerify(exactly = 0) { preferences.setSttForLanguage("ne", "ghost") }
            coVerify { preferences.setTtsForLanguage("ru", TtsModelSelector.SYSTEM_SENTINEL) }
        }
    }

    /** Чистая проверка [backupShaOk] без Android. */
    @Test
    fun `backupShaOk tolerates missing hash and directories but rejects corrupt files`() {
        val good = File(tempFolder.newFolder("sha"), "m.onnx").apply { writeText("legacy-bytes") }
        val expected = sha256HexOf(good)
        val entry = shaEntry("m.onnx", expected)
        assertTrue(backupShaOk(entry, good))
        // Битые байты — несовпадение.
        val bad = File(good.parentFile, "bad.onnx").apply { writeText("corrupted!") }
        assertTrue(!backupShaOk(entry, bad))
        // Без хэша (импортированная модель) — сверки нет, ok.
        assertTrue(backupShaOk(entry.copy(sha256 = null), bad))
        // Каталог без urls/sha256s: сверять нечем — ok (пучки с sha256s
        // сверяются пофайлово, см. тесты bundleShaOk ниже).
        assertTrue(backupShaOk(entry, checkNotNull(good.parentFile)))
    }

    // ---------------------------------------------------------------------
    // Ревью #1: единая точка корня моделей (ModelStorage) — фолбэк filesDir.
    // ---------------------------------------------------------------------

    @Test
    fun `export and import use filesDir fallback when external storage unavailable`() {
        val filesRoot = tempFolder.newFolder("files")
        every { context.getExternalFilesDir("models") } returns null
        every { context.filesDir } returns filesRoot
        val single = File(filesRoot, "marian-ru-en.gguf").apply { writeText("single-bytes") }
        coEvery { repository.observeAll() } returns flowOf(
            listOf(readyModel("t1", ModelCategory.TRANSLATION, RuntimeType.MARIAN_ONNX, single.absolutePath))
        )
        val upserted = mutableListOf<InstalledModel>()
        coEvery { repository.upsert(capture(upserted)) } returns Unit

        runBlocking {
            val manager = backupManager()
            val uri = mockk<Uri>()
            assertTrue("Экспорт должен завершиться успешно", manager.exportToUri(uri))
            val result = manager.importFromUri(uri)
            assertNull("Импорт не должен возвращать ошибку: ${result.error}", result.error)
            assertEquals(1, result.importedCount)
            assertEquals(0, result.skippedCount)
            assertEquals("single-bytes", File(filesRoot, "marian-ru-en.gguf").readText())
        }
    }

    // ---------------------------------------------------------------------
    // Ревью #2: коллизия одноимённых файлов вне корня моделей → уникальный
    // путь с id модели в манифесте.
    // ---------------------------------------------------------------------

    @Test
    fun `export dedupes identical filenames outside models root by model id`() {
        val legacyA = tempFolder.newFolder("legacy", "A")
        val legacyB = tempFolder.newFolder("legacy", "B")
        val fileA = File(legacyA, "model.onnx").apply { writeText("bytes-a") }
        val fileB = File(legacyB, "model.onnx").apply { writeText("bytes-b") }
        coEvery { repository.observeAll() } returns flowOf(
            listOf(
                readyModel("m1", ModelCategory.TRANSLATION, RuntimeType.INDICTRANS_ONNX, fileA.absolutePath),
                readyModel("m2", ModelCategory.TRANSLATION, RuntimeType.INDICTRANS_ONNX, fileB.absolutePath)
            )
        )
        val upserted = mutableListOf<InstalledModel>()
        coEvery { repository.upsert(capture(upserted)) } returns Unit

        runBlocking {
            val manager = backupManager()
            val uri = mockk<Uri>()
            assertTrue("Экспорт должен завершиться успешно", manager.exportToUri(uri))
            val result = manager.importFromUri(uri)
            assertNull("Импорт не должен возвращать ошибку: ${result.error}", result.error)
            assertEquals(2, result.importedCount)
            // Оба файла на местах: коллизант записан под путём с id модели.
            assertEquals("bytes-a", File(modelsRoot, "model.onnx").readText())
            assertEquals("bytes-b", File(modelsRoot, "m2/model.onnx").readText())
        }
    }

    // ---------------------------------------------------------------------
    // Ревью #3: пофайловая сверка пучков по sha256s; skip — только архивы/gz.
    // ---------------------------------------------------------------------

    /** Запись манифеста каталога-пучка с urls и пофайловыми sha256s. */
    private fun bundleEntry(dirName: String, urls: List<String>, sha256s: List<String?>) =
        manifestEntry(
            id = "bundle",
            category = ModelCategory.TRANSLATION,
            runtime = RuntimeType.INDICTRANS_ONNX,
            relativePath = MODELS_PREFIX + dirName,
            urls = urls,
            sha256s = sha256s
        )

    /** ZIP с манифестом (одна entry-каталог) и набором файлов по путям в архиве. */
    private fun writeZipWithFiles(entry: BackupModelEntry, files: Map<String, String>) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putNextEntry(ZipEntry(ModelBackupManager.MANIFEST_NAME))
            zip.write(
                json.encodeToString(
                    BackupManifest.serializer(),
                    BackupManifest(version = 1, exportedAt = 0L, models = listOf(entry))
                ).toByteArray()
            )
            zip.closeEntry()
            files.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes.toByteArray())
                zip.closeEntry()
            }
        }
    }

    @Test
    fun `import skips directory bundle whose per-file sha256 mismatch`() {
        val encSha = sha256HexOf(tempFolder.newFile("enc.tmp").apply { writeText("enc-bytes") })
        val entry = bundleEntry(
            dirName = "bundle",
            urls = listOf("http://host/enc.onnx", "http://host/dec.onnx"),
            sha256s = listOf(encSha, "1".repeat(64))
        )
        writeZipWithFiles(
            entry,
            mapOf(
                MODELS_PREFIX + "bundle/enc.onnx" to "enc-bytes",
                MODELS_PREFIX + "bundle/dec.onnx" to "dec-bytes"
            )
        )

        runBlocking {
            val result = backupManager().importFromUri(mockk<Uri>())
            assertNull(result.error)
            assertEquals(0, result.importedCount)
            assertEquals(1, result.skippedCount)
            coVerify(exactly = 0) { repository.upsert(any()) }
        }
    }

    @Test
    fun `import accepts directory bundle with matching per-file sha256`() {
        val encSha = sha256HexOf(tempFolder.newFile("enc2.tmp").apply { writeText("enc-bytes") })
        val decSha = sha256HexOf(tempFolder.newFile("dec2.tmp").apply { writeText("dec-bytes") })
        val entry = bundleEntry(
            dirName = "bundle",
            urls = listOf("http://host/enc.onnx", "http://host/dec.onnx"),
            sha256s = listOf(encSha, decSha)
        )
        writeZipWithFiles(
            entry,
            mapOf(
                MODELS_PREFIX + "bundle/enc.onnx" to "enc-bytes",
                MODELS_PREFIX + "bundle/dec.onnx" to "dec-bytes"
            )
        )

        runBlocking {
            val result = backupManager().importFromUri(mockk<Uri>())
            assertNull(result.error)
            assertEquals(1, result.importedCount)
            assertEquals(0, result.skippedCount)
            assertEquals("enc-bytes", File(modelsRoot, "bundle/enc.onnx").readText())
        }
    }

    @Test
    fun `import skips sha check for gunzipped bundle artifacts`() {
        // slimt-пучок: sha256s — хэши скачанных .gz-артефактов, на диске
        // распакованные файлы без .gz — формальная сверка невозможна, импорт
        // не должен отклоняться (контрольный аудит, п.1).
        val entry = bundleEntry(
            dirName = "slimt",
            urls = listOf("http://host/model.bin.gz"),
            sha256s = listOf("0".repeat(64))
        )
        writeZipWithFiles(entry, mapOf(MODELS_PREFIX + "slimt/model.bin" to "unpacked-bytes"))

        runBlocking {
            val result = backupManager().importFromUri(mockk<Uri>())
            assertNull(result.error)
            assertEquals(1, result.importedCount)
            assertEquals(0, result.skippedCount)
            assertEquals("unpacked-bytes", File(modelsRoot, "slimt/model.bin").readText())
        }
    }

    // ---------------------------------------------------------------------
    // Ревью #4: экспорт сначала во временный файл cacheDir — неудача копирования
    // не оставляет temp-архив.
    // ---------------------------------------------------------------------

    @Test
    fun `export failure leaves no temporary archive in cache dir`() {
        coEvery { repository.observeAll() } returns flowOf(prepareModels())
        every { contentResolver.openOutputStream(any()) } throws java.io.IOException("no space")

        runBlocking {
            assertTrue(!backupManager().exportToUri(mockk<Uri>()))
        }
        assertTrue(
            "Temp-архивы экспорта не должны оставаться в cacheDir",
            cacheDir.listFiles()?.none { it.name.startsWith("backup_export") } ?: true
        )
    }

    // Independent SHA-256 for the test is provided by
    // com.plox.llmtranslate.testutil.sha256HexOf.
}
