package com.plox.llmtranslate.data.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты [FavoritesRepository] поверх фейкового [FavoritesDao]:
 * маппинг в домен, дедупликация повторов, переключение избранного.
 */
class FavoritesRepositoryTest {

    /** In-memory DAO: контракт как у Room, без Android. */
    private class FakeDao : FavoritesDao {
        private var nextId = 1L
        val store = LinkedHashMap<Long, FavoriteEntity>()

        // Порядок как в реальном ORDER BY created_at DESC; ties по id DESC,
        // чтобы поведение было детерминированным при равных createdAt.
        private val descending =
            compareByDescending<FavoriteEntity> { it.createdAt }
                .thenByDescending { it.id }

        override fun observeAll(): Flow<List<FavoriteEntity>> =
            flow {
                emit(store.values.sortedWith(descending))
            }

        override suspend fun insert(entity: FavoriteEntity): Long {
            val withId = entity.copy(id = nextId++)
            store[withId.id] = withId
            return withId.id
        }

        override suspend fun deleteById(id: Long) {
            store.remove(id)
        }

        override suspend fun deleteMatching(
            sourceLang: String,
            targetLang: String,
            inputText: String
        ): Int {
            val match = store.values.filter {
                it.sourceLang == sourceLang && it.targetLang == targetLang &&
                    it.inputText == inputText
            }.map { it.id }
            match.forEach { store.remove(it) }
            return match.size
        }
    }

    @Test
    fun `add stores mapped domain entry newest first`() = runTest {
        val dao = FakeDao()
        val repo = FavoritesRepository(dao)
        repo.add("en", "ru", "hello", "привет")
        repo.add("ru", "en", "мир", "world")

        val favorites = repo.observeAll().first()
        assertEquals(2, favorites.size)
        // Новые сверху: добавленные позже идут первыми (ids растут вместе с createdAt-порядком).
        assertEquals("world", favorites.first().resultText)
        assertEquals("en", favorites.first().targetLang)
        assertEquals("hello", favorites.last().inputText)
    }

    @Test
    fun `add deduplicates identical favorite`() = runTest {
        val dao = FakeDao()
        val repo = FavoritesRepository(dao)
        repo.add("en", "ru", "hello", "привет")
        repo.add("en", "ru", "hello", "привет")

        val favorites = repo.observeAll().first()
        assertEquals(1, favorites.size)
        assertEquals(1, dao.store.size)
    }

    @Test
    fun `add skips blank text`() = runTest {
        val dao = FakeDao()
        val repo = FavoritesRepository(dao)
        repo.add("en", "ru", "   ", "привет")
        repo.add("en", "ru", "hello", "   ")

        assertTrue(dao.store.isEmpty())
    }

    @Test
    fun `toggle adds then removes favorite`() = runTest {
        val dao = FakeDao()
        val repo = FavoritesRepository(dao)

        assertTrue(repo.toggle("en", "ru", "hello", "привет"))
        assertEquals(1, dao.store.size)

        // Повторный toggle на той же записи: remove и false, запись удалена.
        assertFalse(repo.toggle("en", "ru", "hello", "привет"))
        assertTrue(dao.store.isEmpty())
        assertTrue(repo.observeAll().first().isEmpty())
    }

    @Test
    fun `remove and delete clear entries`() = runTest {
        val dao = FakeDao()
        val repo = FavoritesRepository(dao)
        repo.add("en", "ru", "a", "а")
        repo.add("en", "ru", "b", "б")

        repo.remove("en", "ru", "a")
        assertEquals(listOf("b"), repo.observeAll().first().map { it.inputText })

        val target = repo.observeAll().first().first { it.inputText == "b" }
        repo.delete(target.id)
        assertTrue(repo.observeAll().first().isEmpty())
    }
}
