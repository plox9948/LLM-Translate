package com.plox.llmtranslate.data.model

import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты устойчивого маппинга [ModelEntity] -> domain и фильтрации битых
 * enum-значений через фейковый [ModelDao] (без Room).
 */
class ModelRepositoryTest {

    /** In-memory DAO: тот же контракт, что у Room, без Android. */
    private class FakeDao : ModelDao {
        val store = LinkedHashMap<String, ModelEntity>()

        override fun observeAll(): Flow<List<ModelEntity>> = flow { emit(store.values.toList()) }

        override fun observeByCategory(category: String): Flow<List<ModelEntity>> =
            flow { emit(store.values.filter { it.category == category }) }

        override suspend fun getById(id: String): ModelEntity? = store[id]

        override suspend fun getFirstReady(category: String): ModelEntity? =
            store.values.firstOrNull {
                it.category == category && it.status == ModelStatus.READY.name &&
                    !it.filePath.isNullOrEmpty()
            }

        override suspend fun upsert(entity: ModelEntity) {
            store[entity.id] = entity
        }

        override suspend fun syncRamFields(
            id: String,
            ramRequiredMb: Int,
            recommendedRamMb: Int,
            updatedAt: Long
        ): Int {
            val existing = store[id] ?: return 0
            if (!existing.isBuiltin) return 0
            store[id] = existing.copy(
                ramRequiredMb = ramRequiredMb,
                recommendedRamMb = recommendedRamMb,
                updatedAt = updatedAt
            )
            return 1
        }

        override suspend fun updateStatusById(id: String, status: String, updatedAt: Long): Int {
            val existing = store[id] ?: return 0
            store[id] = existing.copy(status = status, updatedAt = updatedAt)
            return 1
        }

        override suspend fun updateProgressById(
            id: String,
            status: String,
            progress: Int,
            updatedAt: Long
        ): Int {
            val existing = store[id] ?: return 0
            store[id] = existing.copy(status = status, progress = progress, updatedAt = updatedAt)
            return 1
        }

        override suspend fun finalizeDownloadById(
            id: String,
            status: String,
            progress: Int,
            filePath: String,
            bytesDone: Long,
            updatedAt: Long
        ): Int {
            val existing = store[id] ?: return 0
            // Гейт того же SQL-запроса Room: UPDATE ... WHERE status='DOWNLOADING'.
            if (existing.status != ModelStatus.DOWNLOADING.name) return 0
            store[id] = existing.copy(
                status = status,
                progress = progress,
                filePath = filePath,
                bytesDone = bytesDone,
                updatedAt = updatedAt
            )
            return 1
        }

        override suspend fun deleteById(id: String) {
            store.remove(id)
        }
    }

    private fun entity(
        id: String = "m1",
        category: String = ModelCategory.TRANSLATION.name,
        runtime: String = RuntimeType.MARIAN_ONNX.name,
        status: String = ModelStatus.READY.name,
        languages: String = "ru,en",
        urls: String = "",
        sha256s: String = "",
        filePath: String? = "/files/m1"
    ) = ModelEntity(
        id = id,
        category = category,
        displayName = "Тест $id",
        description = "",
        runtime = runtime,
        sizeBytes = 1000,
        ramRequiredMb = 100,
        languages = languages,
        quality = 3,
        speed = 3,
        url = "https://example.invalid/m1",
        urls = urls,
        sha256 = null,
        sha256s = sha256s,
        license = "MIT",
        minAndroidSdk = 26,
        recommendedRamMb = 200,
        status = status,
        progress = 100,
        filePath = filePath,
        bytesDone = 1000,
        isBuiltin = true,
        createdAt = 1,
        updatedAt = 2
    )

    @Test
    fun `valid entity maps to domain with csv split`() {
        val model = entity(
            languages = "ru,en,",
            urls = "https://a.invalid/x,,https://b.invalid/y"
        ).toDomainOrNull()
        requireNotNull(model)
        assertEquals("m1", model.id)
        assertEquals(ModelCategory.TRANSLATION, model.category)
        assertEquals(RuntimeType.MARIAN_ONNX, model.runtime)
        assertEquals(ModelStatus.READY, model.status)
        assertEquals(listOf("ru", "en"), model.languages)
        assertEquals(listOf("https://a.invalid/x", "https://b.invalid/y"), model.urls)
    }

    @Test
    fun `sha256s csv keeps positional nulls and roundtrips via upsert`() = runTest {
        // Позиционные пустые элементы — «хеш неизвестен», не склеиваются.
        val model = entity(sha256s = "aa,,cc").toDomainOrNull()
        requireNotNull(model)
        assertEquals(listOf("aa", null, "cc"), model.sha256s)

        val dao = FakeDao()
        val repo = ModelRepository(dao)
        repo.upsert(model.copy(sha256s = listOf("dd", "ee")))
        val restored = repo.getById("m1")
        assertEquals(listOf("dd", "ee"), restored?.sha256s)
        // Разделитель колонки — «\n» (URL/хэши не содержат переносов).
        assertEquals("dd\nee", dao.store.getValue("m1").sha256s)

        // Пустая колонка — пустой список (старые строки после миграции).
        assertEquals(emptyList<String?>(), entity(sha256s = "").toDomainOrNull()?.sha256s)
    }

    @Test
    fun `unknown category returns null`() {        // Символ-заглушка не допускает случайного совпадения с реальным enum.
        assertNull(entity(category = "\u041d\u0415\u0412\u0415\u0421\u0422\u0418").toDomainOrNull())
    }

    @Test
    fun `obsolete runtime returns null`() {
        // Удалённые при миграции рантаймы из строк БД.
        assertNull(entity(runtime = "GGUF").toDomainOrNull())
        assertNull(entity(runtime = "VOSK").toDomainOrNull())
    }

    @Test
    fun `unknown status returns null`() {
        assertNull(entity(status = "\u041d\u0415\u0412\u0415\u0421\u0422\u0418").toDomainOrNull())
    }

    @Test
    fun `repository skips broken records from flows`() = runTest {
        val dao = FakeDao()
        dao.upsert(entity(id = "ok"))
        dao.upsert(entity(id = "bad-runtime", runtime = "GGUF"))
        dao.upsert(entity(id = "bad-status", status = "QUEUED"))
        val repo = ModelRepository(dao)

        val models = repo.observeAll().first()
        assertEquals(listOf("ok"), models.map { it.id })

        assertNull(repo.getById("bad-runtime"))
        assertNull(repo.getById("nope"))
        assertEquals("ok", repo.getById("ok")?.id)
    }

    @Test
    fun `repository deletes obsolete runtime records and returns file paths`() = runTest {
        val dao = FakeDao()
        dao.upsert(entity(id = "good"))
        dao.upsert(entity(id = "legacy1", runtime = "GGUF", filePath = "/models/GGUF/legacy1"))
        dao.upsert(entity(id = "legacy2", runtime = "VOSK", filePath = "/models/VOSK/legacy2"))
        dao.upsert(entity(id = "legacy-nofile", runtime = "WASMRUNT", filePath = null))
        val repo = ModelRepository(dao)

        val deleted = repo.cleanObsoleteRuntimes()

        // Устаревшие записи удаляются все (включая запись без filePath),
        // filePath возвращаются только для реально сохранённых файлов.
        assertEquals(listOf("/models/GGUF/legacy1", "/models/VOSK/legacy2"), deleted)
        assertEquals(listOf("good"), dao.store.keys.toList())
    }

    private fun card(id: String, ramRequiredMb: Int, recommendedRamMb: Int) = ModelCard(
        id = id,
        category = ModelCategory.TRANSLATION,
        displayName = "Карточка $id",
        description = "",
        runtime = RuntimeType.MARIAN_ONNX,
        sizeBytes = 1000,
        ramRequiredMb = ramRequiredMb,
        languages = listOf("ru", "en"),
        quality = 3,
        speed = 3,
        url = "https://example.invalid/$id",
        urls = emptyList(),
        sha256 = null,
        license = "MIT",
        minAndroidSdk = 26,
        recommendedRamMb = recommendedRamMb
    )

    @Test
    fun `syncCatalogRamFields updates only existing builtin records with differing values`() =
        runTest {
            val dao = FakeDao()
            dao.upsert(entity(id = "known")) // ramRequiredMb=100, recommendedRamMb=200
            dao.upsert(entity(id = "same"))
            dao.upsert(entity(id = "imported").copy(isBuiltin = false))
            val repo = ModelRepository(dao)

            val updated = repo.syncCatalogRamFields(
                listOf(
                    card("known", 480, 800),   // значения отличаются — обновится
                    card("same", 100, 200),    // совпадают — пропускаем запись
                    card("missing", 300, 500), // в БД нет — не создаём
                    card("imported", 999, 999) // импортированная — не каталожная
                )
            )

            assertEquals(1, updated)
            assertEquals(480, dao.store.getValue("known").ramRequiredMb)
            assertEquals(800, dao.store.getValue("known").recommendedRamMb)
            assertEquals(100, dao.store.getValue("same").ramRequiredMb)
            assertEquals(200, dao.store.getValue("same").recommendedRamMb)
            assertEquals(100, dao.store.getValue("imported").ramRequiredMb)
            assertNull(dao.getById("missing"))
        }

    @Test
    fun `partial download updates do not overwrite catalog ram fields`() = runTest {
        val dao = FakeDao()
        dao.upsert(
            entity(id = "m1", status = ModelStatus.DOWNLOADING.name)
                .copy(progress = 0, filePath = null, bytesDone = 0)
        )
        val repo = ModelRepository(dao)

        // Синхронизация каталога обновила оценки ОЗУ (свежие RAM-значения).
        repo.syncCatalogRamFields(listOf(card("m1", 480, 800)))

        // Прогресс воркера — точечный UPDATE: RAM-поля и описание не тронуты.
        assertTrue(repo.updateDownloadProgress("m1", ModelStatus.DOWNLOADING, 42))
        assertEquals(42, dao.store.getValue("m1").progress)
        assertEquals(ModelStatus.DOWNLOADING.name, dao.store.getValue("m1").status)
        assertEquals(480, dao.store.getValue("m1").ramRequiredMb)
        assertEquals(800, dao.store.getValue("m1").recommendedRamMb)

        // Ошибочный статус — тоже точечно, RAM-поля целиком сохранены.
        assertTrue(repo.updateDownloadStatus("m1", ModelStatus.ERROR))
        assertEquals(ModelStatus.ERROR.name, dao.store.getValue("m1").status)
        assertEquals(480, dao.store.getValue("m1").ramRequiredMb)
        assertEquals(800, dao.store.getValue("m1").recommendedRamMb)
        assertEquals(42, dao.store.getValue("m1").progress)

        // Гейт finalize (WHERE status='DOWNLOADING'): по ERROR-записи
        // завершение не пишется — запоздалая финализация воркера не
        // перетирает выставленный статус.
        assertFalse(repo.finalizeDownload("m1", "/files/m1-final", 1000))
        assertEquals(ModelStatus.ERROR.name, dao.store.getValue("m1").status)

        // Завершение DOWNLOADING-записи: READY/100/filePath/bytesDone —
        // RAM-поля снова не тронуты.
        assertTrue(repo.updateDownloadStatus("m1", ModelStatus.DOWNLOADING))
        assertTrue(repo.finalizeDownload("m1", "/files/m1-final", 1000))
        val final = dao.store.getValue("m1")
        assertEquals(ModelStatus.READY.name, final.status)
        assertEquals(100, final.progress)
        assertEquals("/files/m1-final", final.filePath)
        assertEquals(1000L, final.bytesDone)
        assertEquals(480, final.ramRequiredMb)
        assertEquals(800, final.recommendedRamMb)
        assertEquals("Тест m1", final.displayName)
    }

    @Test
    fun `partial updates on missing record return false`() = runTest {
        val dao = FakeDao()
        dao.upsert(entity(id = "keep"))
        val repo = ModelRepository(dao)
        dao.deleteById("keep")

        assertFalse(repo.updateDownloadStatus("keep", ModelStatus.ERROR))
        assertFalse(repo.updateDownloadProgress("keep", ModelStatus.DOWNLOADING, 10))
        assertFalse(repo.finalizeDownload("keep", "/files/keep", 5))
        assertNull(dao.getById("keep"))
    }

    @Test
    fun `finalize download on ready or error record leaves it untouched`() = runTest {
        val dao = FakeDao()
        dao.upsert(entity(id = "ready", filePath = "/files/m1"))
        dao.upsert(entity(id = "failed", status = ModelStatus.ERROR.name, filePath = null))
        val repo = ModelRepository(dao)

        // Гейт DAO (status='DOWNLOADING'): READY и ERROR не финализуются,
        // поля записей не меняются.
        assertFalse(repo.finalizeDownload("ready", "/files/other", 5))
        assertFalse(repo.finalizeDownload("failed", "/files/other", 5))
        assertEquals(ModelStatus.READY.name, dao.store.getValue("ready").status)
        assertEquals("/files/m1", dao.store.getValue("ready").filePath)
        assertEquals(ModelStatus.ERROR.name, dao.store.getValue("failed").status)
        assertNull(dao.store.getValue("failed").filePath)
    }

    @Test
    fun `ready record consistency pure checks`() {
        // Файл непустой — согласовано; пустой или отсутствует — нет.
        assertTrue(readyRecordIsConsistent("/f/model.onnx") {
            FileSnapshot(isDirectory = false, size = 10, childCount = 0)
        })
        assertFalse(readyRecordIsConsistent("/f/model.onnx") {
            FileSnapshot(isDirectory = false, size = 0, childCount = 0)
        })
        // Каталог мультифайловой модели: непустой — ок, пустой — битая запись.
        assertTrue(readyRecordIsConsistent("/f/model") {
            FileSnapshot(isDirectory = true, size = 0, childCount = 3)
        })
        assertFalse(readyRecordIsConsistent("/f/model") {
            FileSnapshot(isDirectory = true, size = 0, childCount = 0)
        })
        // filePath == null — проверять нечем; snapshot == null — путь
        // недоступен (storage отмонтирован): вердикт не выносится.
        assertTrue(readyRecordIsConsistent(null) { null })
        assertTrue(readyRecordIsConsistent("/f/model") { null })
    }

    @Test
    fun `markBrokenReadyRecords moves disk-inconsistent ready records to error`() = runTest {
        val dao = FakeDao()
        dao.upsert(entity(id = "ok-file")) // /files/m1 — непустой файл
        dao.upsert(entity(id = "empty-file", filePath = "/files/empty"))
        dao.upsert(entity(id = "missing-dir", filePath = "/files/dir"))
        dao.upsert(entity(id = "no-path", filePath = null))
        dao.upsert(
            entity(id = "downloading", status = ModelStatus.DOWNLOADING.name,
                filePath = "/files/partial")
        )
        dao.upsert(entity(id = "offline", filePath = "/files/external"))
        val repo = ModelRepository(dao)

        val broken = repo.markBrokenReadyRecords { path ->
            when (path) {
                "/files/m1" -> FileSnapshot(isDirectory = false, size = 1000, childCount = 0)
                "/files/empty" -> FileSnapshot(isDirectory = false, size = 0, childCount = 0)
                "/files/dir" -> FileSnapshot(isDirectory = true, size = 0, childCount = 0)
                "/files/partial" -> FileSnapshot(isDirectory = false, size = 1, childCount = 0)
                "/files/external" -> null // недоступный снимок — не трогаем
                else -> FileSnapshot(isDirectory = false, size = 0, childCount = 0)
            }
        }

        assertEquals(listOf("empty-file", "missing-dir"), broken)
        assertEquals(ModelStatus.ERROR.name, dao.store.getValue("empty-file").status)
        assertEquals(ModelStatus.ERROR.name, dao.store.getValue("missing-dir").status)
        // Целые, без-path, не-READY и недоступные для verdict — не тронуты.
        assertEquals(ModelStatus.READY.name, dao.store.getValue("ok-file").status)
        assertEquals(ModelStatus.READY.name, dao.store.getValue("no-path").status)
        assertEquals(ModelStatus.READY.name, dao.store.getValue("offline").status)
        assertEquals(ModelStatus.DOWNLOADING.name, dao.store.getValue("downloading").status)
    }
}
