package com.plox.llmtranslate.data.model

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation-тест SQL-гейтов статусных переходов моделей
 * ([ModelDao.markBrokenReadyById] и [ModelDao.updateStatusWhileDownloadingById])
 * на реальном Room/SQLite: unit-фейки DAO лишь повторяют логику в памяти,
 * условие `WHERE ... AND status = ...` здесь проверяется целиком
 * (по образцу [TranslationHistoryTrimTest]).
 */
@RunWith(AndroidJUnit4::class)
class ModelStatusGatesTest {

    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun entity(id: String, status: String) = ModelEntity(
        id = id,
        category = "TRANSLATION",
        displayName = "m-$id",
        description = "d",
        runtime = "MARIAN_ONNX",
        sizeBytes = 10,
        ramRequiredMb = 100,
        languages = "v1:ru\nen",
        quality = 3,
        speed = 3,
        url = "",
        urls = "",
        sha256 = null,
        license = "MIT",
        minAndroidSdk = 26,
        recommendedRamMb = 0,
        status = status,
        progress = 100,
        filePath = "/files/models/$id",
        bytesDone = 10,
        isBuiltin = true,
        createdAt = 1L,
        updatedAt = 1L
    )

    @Test
    fun markBrokenReadyByIdOnlyTouchesReadyRows() = runBlocking {
        val dao = db.modelDao()
        dao.upsert(entity("ready1", "READY"))
        dao.upsert(entity("dl1", "DOWNLOADING"))

        // READY-строка переводится в ERROR ровно одной операцией.
        assertEquals(1, dao.markBrokenReadyById("ready1", "ERROR", 2L))
        assertEquals("ERROR", dao.getById("ready1")?.status)

        // DOWNLOADING-живая загрузка гейтом не затрагивается.
        assertEquals(0, dao.markBrokenReadyById("dl1", "ERROR", 3L))
        assertEquals("DOWNLOADING", dao.getById("dl1")?.status)

        // Удалённая запись не воскрешается.
        dao.deleteById("ready1")
        assertEquals(0, dao.markBrokenReadyById("ready1", "ERROR", 4L))
        assertNull(dao.getById("ready1"))
    }

    @Test
    fun updateStatusWhileDownloadingOnlyTouchesDownloadingRows() = runBlocking {
        val dao = db.modelDao()
        dao.upsert(entity("dl2", "DOWNLOADING"))
        dao.upsert(entity("ready2", "READY"))

        // Терминальная запись воркера проходит по живой DOWNLOADING-строке.
        assertEquals(1, dao.updateStatusWhileDownloadingById("dl2", "ERROR", 5L))
        assertEquals("ERROR", dao.getById("dl2")?.status)

        // Импорт той же id, промоутнувший запись в READY, ERROR не затирается.
        dao.upsert(entity("ready2", "READY"))
        assertEquals(0, dao.updateStatusWhileDownloadingById("ready2", "ERROR", 6L))
        assertEquals("READY", dao.getById("ready2")?.status)

        dao.deleteById("ready2")
        assertEquals(0, dao.updateStatusWhileDownloadingById("ready2", "ERROR", 7L))
        assertNull(dao.getById("ready2"))
    }
}
