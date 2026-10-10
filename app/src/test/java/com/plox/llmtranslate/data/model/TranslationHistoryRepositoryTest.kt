package com.plox.llmtranslate.data.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты [TranslationHistoryRepository] поверх фейкового [TranslationHistoryDao]:
 * дедупликация повторов, обрезка по размеру, пропуск пустых, маппинг в домен.
 */
class TranslationHistoryRepositoryTest {

    /** In-memory DAO: контракт как у Room, без Android. */
    private class FakeDao : TranslationHistoryDao {
        private var nextId = 1L
        val store = LinkedHashMap<Long, TranslationHistoryEntity>()

        // Порядок как в реальном ORDER BY created_at DESC; ties по id DESC,
        // чтобы поведение было детерминированным при равных createdAt.
        private val descending =
            compareByDescending<TranslationHistoryEntity> { it.createdAt }
                .thenByDescending { it.id }

        override fun observeRecent(limit: Int): Flow<List<TranslationHistoryEntity>> =
            flow {
                emit(store.values.sortedWith(descending).take(limit))
            }

        override suspend fun insert(entity: TranslationHistoryEntity): Long {
            val withId = entity.copy(id = nextId++)
            store[withId.id] = withId
            return withId.id
        }

        override suspend fun deleteById(id: Long) {
            store.remove(id)
        }

        override suspend fun clear() {
            store.clear()
        }

        override suspend fun trim(keep: Int) {
            val stale = store.values.sortedWith(descending).drop(keep).map { it.id }
            stale.forEach { store.remove(it) }
        }

        override suspend fun deleteMatching(
            sourceLang: String,
            targetLang: String,
            inputText: String
        ) {
            val match = store.values.filter {
                it.sourceLang == sourceLang && it.targetLang == targetLang &&
                    it.inputText == inputText
            }.map { it.id }
            match.forEach { store.remove(it) }
        }
    }

    @Test
    fun `add stores mapped domain entry newest first`() = runTest {
        val dao = FakeDao()
        val repo = TranslationHistoryRepository(dao)
        repo.add("en", "ru", "hello", "привет")
        repo.add("ru", "en", "мир", "world")

        val history = repo.observeRecent().first()
        assertEquals(2, history.size)
        // Новые сверху: добавленные позже идут первыми (ids растут вместе с createdAt-порядком).
        assertEquals("world", history.first().resultText)
        assertEquals("en", history.first().targetLang)
        assertEquals("hello", history.last().inputText)
    }

    @Test
    fun `add deduplicates identical translation`() = runTest {
        val dao = FakeDao()
        val repo = TranslationHistoryRepository(dao)
        repo.add("en", "ru", "hello", "привет")
        repo.add("en", "ru", "hello", "привет")

        val history = repo.observeRecent().first()
        assertEquals(1, history.size)
        assertEquals(1, dao.store.size)
    }

    @Test
    fun `add skips blank text`() = runTest {
        val dao = FakeDao()
        val repo = TranslationHistoryRepository(dao)
        repo.add("en", "ru", "   ", "привет")
        repo.add("en", "ru", "hello", "   ")

        assertTrue(dao.store.isEmpty())
    }

    @Test
    fun `trim keeps only max entries`() = runTest {
        val dao = FakeDao()
        val repo = TranslationHistoryRepository(dao)
        repeat(TranslationHistoryRepository.MAX_ENTRIES + 5) { i ->
            repo.add("en", "ru", "text-$i", "перевод-$i")
        }

        assertEquals(TranslationHistoryRepository.MAX_ENTRIES, dao.store.size)
        val history = repo.observeRecent().first()
        assertEquals(TranslationHistoryRepository.MAX_ENTRIES, history.size)
        // Самый старый (text-0) вытеснен.
        assertTrue(history.none { it.inputText == "text-0" })
    }

    @Test
    fun `delete and clear remove entries`() = runTest {
        val dao = FakeDao()
        val repo = TranslationHistoryRepository(dao)
        repo.add("en", "ru", "a", "а")
        repo.add("en", "ru", "b", "б")

        val target = repo.observeRecent().first().first { it.inputText == "a" }
        repo.delete(target.id)
        assertEquals(listOf("b"), repo.observeRecent().first().map { it.inputText })

        repo.clear()
        assertTrue(repo.observeRecent().first().isEmpty())
    }
}
