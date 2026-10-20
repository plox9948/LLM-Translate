package com.plox.llmtranslate.data.model

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation-тест обрезки истории ([TranslationHistoryDao.trim]) на
 * реальном Room/SQLite: unit-фейк DAO только повторяет порядок в памяти,
 * SQL `ORDER BY created_at DESC, id DESC` здесь проверяется целиком.
 *
 * Критерий детерминизма: при одинаковых created_at (одинаковые мс) trim
 * обязан вытеснять самые ранние вставки (наименьшие автоинкрементные id) и
 * оставлять самые поздние — без вторичной сортировки по id выбор SQLite
 * недетерминирован.
 */
@RunWith(AndroidJUnit4::class)
class TranslationHistoryTrimTest {

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

    private fun entry(text: String, createdAt: Long) = TranslationHistoryEntity(
        sourceLang = "en",
        targetLang = "ru",
        inputText = text,
        resultText = "перевод-$text",
        createdAt = createdAt
    )

    @Test
    fun trimOnIdenticalTimestampsRemovesOldestInserts() = runBlocking {
        val dao = db.translationHistoryDao()
        // Одинаковые created_at: детерминированный порядок даёт только id.
        repeat(6) { i ->
            dao.insert(entry("text-$i", createdAt = 1000L))
        }

        dao.trim(3)

        val kept = dao.observeRecent(limit = 10).first()
        assertEquals(3, kept.size)
        // Остаются три самых поздних вставки (наибольшие id), text-0..text-2 вытеснены.
        assertEquals(
            setOf("text-3", "text-4", "text-5"),
            kept.map { it.inputText }.toSet()
        )
    }

    @Test
    fun trimRespectsCreatedAtOrderOverId() = runBlocking {
        val dao = db.translationHistoryDao()
        // Ранняя вставка с более поздним created_at должна пережить trim,
        // несмотря на наименьший id.
        dao.insert(entry("old-id-new-time", createdAt = 2000L))
        dao.insert(entry("new-id-old-time", createdAt = 1000L))

        dao.trim(1)

        val kept = dao.observeRecent(limit = 10).first()
        assertEquals(1, kept.size)
        assertEquals("old-id-new-time", kept.first().inputText)
    }
}
