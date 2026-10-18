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
import io.mockk.coEvery
import io.mockk.coVerify
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
    private lateinit var zipFile: File

    private val context = mockk<Context>(relaxed = true)
    private val contentResolver = mockk<ContentResolver>(relaxed = true)
    private val repository = mockk<ModelRepository>(relaxed = true)
    private val preferences = mockk<ModelPreferences>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        modelsRoot = tempFolder.newFolder("external", "models")
        val cacheDir = tempFolder.newFolder("cache")
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

    private fun backupManager() = ModelBackupManager(context, repository, preferences, json)

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

    @Test
    fun `import accepts manifest entry relativePath without models prefix`() {
        // Разомкнутый вариант манифеста (compat): relativePath без "models/",
        // файлы архива — под "models/". Импорт обязан найти запись.
        coEvery { repository.observeAll() } returns flowOf(prepareModels())
        val upserted = mutableListOf<InstalledModel>()
        coEvery { repository.upsert(capture(upserted)) } returns Unit

        val manifest = BackupManifest(
            version = 1,
            exportedAt = 0L,
            models = listOf(
                BackupModelEntry(
                    id = "legacy1",
                    category = ModelCategory.TRANSLATION.name,
                    displayName = "Legacy",
                    description = "d",
                    runtime = RuntimeType.MARIAN_ONNX.name,
                    sizeBytes = 5,
                    ramRequiredMb = 100,
                    languages = listOf("ru"),
                    quality = 1,
                    speed = 1,
                    url = "",
                    urls = emptyList(),
                    sha256 = null,
                    license = "MIT",
                    minAndroidSdk = 26,
                    recommendedRamMb = 0,
                    relativePath = "legacy1.gguf",
                    isBuiltin = false
                )
            )
        )
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putNextEntry(ZipEntry(ModelBackupManager.MANIFEST_NAME))
            zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(ModelBackupManager.MODELS_PREFIX + "legacy1.gguf"))
            zip.write("legacy-bytes".toByteArray())
            zip.closeEntry()
        }

        runBlocking {
            val result = backupManager().importFromUri(mockk<Uri>())
            assertNull("Импорт не должен возвращать ошибку: ${result.error}", result.error)
            assertEquals(1, result.importedCount)
            assertEquals(0, result.skippedCount)
            assertEquals("legacy-bytes", File(modelsRoot, "legacy1.gguf").readText())
        }
    }

    /** Entry манифеста одиночного файла с указанным sha256. */
    private fun shaEntry(filePathRel: String, sha: String?) = BackupModelEntry(
        id = "shamod",
        category = ModelCategory.TRANSLATION.name,
        displayName = "Sha",
        description = "d",
        runtime = RuntimeType.MARIAN_ONNX.name,
        sizeBytes = 5,
        ramRequiredMb = 100,
        languages = listOf("ru"),
        quality = 1,
        speed = 1,
        url = "",
        urls = emptyList(),
        sha256 = sha,
        license = "MIT",
        minAndroidSdk = 26,
        recommendedRamMb = 0,
        relativePath = "$MODELS_PREFIX$filePathRel",
        isBuiltin = false
    )

    private fun writeZipWithModel(entry: BackupModelEntry, fileBytes: String) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putNextEntry(ZipEntry(ModelBackupManager.MANIFEST_NAME))
            zip.write(
                json.encodeToString(
                    BackupManifest.serializer(),
                    BackupManifest(version = 1, exportedAt = 0L, models = listOf(entry))
                ).toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(entry.relativePath))
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
        // Каталог: хэш манифеста относится к архиву, не к содержимому — сверка пропускается.
        assertTrue(backupShaOk(entry, good.parentFile))
    }

    /** SHA-256 файла в hex — независимая реализация для теста. */
    private fun sha256HexOf(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return file.readBytes().let(digest::digest).joinToString("") { "%02x".format(it) }
    }
}
