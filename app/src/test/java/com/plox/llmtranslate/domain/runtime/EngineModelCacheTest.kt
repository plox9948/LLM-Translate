package com.plox.llmtranslate.domain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Юнит-тесты LRU-кэша пучков моделей движка (issue #36):
 * вытеснение сверх ёмкости, обновление порядка при доступе, remove/clear.
 */
class EngineModelCacheTest {

    @Test
    fun `evicts least recently used over capacity`() {
        val cache = EngineModelCache<String>(capacity = 2)
        cache.put("a", "A")
        cache.put("b", "B")
        cache.put("c", "C")

        val evicted = cache.evictOverCapacity()

        assertEquals(listOf("a" to "A"), evicted)
        assertEquals(listOf("b" to "B", "c" to "C"), cache.all())
    }

    @Test
    fun `get refreshes usage order`() {
        val cache = EngineModelCache<String>(capacity = 2)
        cache.put("a", "A")
        cache.put("b", "B")
        assertEquals("A", cache.get("a"))
        cache.put("c", "C")

        val evicted = cache.evictOverCapacity()

        assertEquals(listOf("b" to "B"), evicted)
        assertEquals(listOf("a", "c"), cache.all().map { it.first })
    }

    @Test
    fun `put of existing key refreshes usage order`() {
        val cache = EngineModelCache<String>(capacity = 2)
        cache.put("a", "A")
        cache.put("b", "B")
        cache.put("a", "A2")
        cache.put("c", "C")

        val evicted = cache.evictOverCapacity()

        assertEquals(listOf("b" to "B"), evicted)
        assertEquals("A2", cache.get("a"))
    }

    @Test
    fun `get returns stored value`() {
        val cache = EngineModelCache<String>(capacity = 2)
        cache.put("a", "A")

        assertEquals("A", cache.get("a"))
        assertNull(cache.get("zzz"))
    }

    @Test
    fun `remove returns and drops entry`() {
        val cache = EngineModelCache<String>(capacity = 2)
        cache.put("a", "A")
        cache.put("b", "B")

        assertEquals("A", cache.remove("a"))
        assertNull(cache.remove("a"))
        assertEquals(listOf("b" to "B"), cache.all())
    }

    @Test
    fun `clear returns all entries and empties cache`() {
        val cache = EngineModelCache<String>(capacity = 2)
        cache.put("a", "A")
        cache.put("b", "B")

        val cleared = cache.clear()

        assertEquals(setOf("a" to "A", "b" to "B"), cleared.toSet())
        assertTrue(cache.all().isEmpty())
        assertEquals(0, cache.size)
    }

    @Test
    fun `keysNotIn lists keys outside keep set`() {
        val cache = EngineModelCache<String>(capacity = 3)
        cache.put("ru-en", "A")
        cache.put("en-ru", "B")

        assertEquals(listOf("en-ru"), cache.keysNotIn(setOf("ru-en")))
        assertTrue(cache.keysNotIn(setOf("ru-en", "en-ru")).isEmpty())
    }

    @Test
    fun `evict within capacity removes nothing`() {
        val cache = EngineModelCache<String>(capacity = 2)
        cache.put("a", "A")
        cache.put("b", "B")

        assertTrue(cache.evictOverCapacity().isEmpty())
    }

    @Test
    fun `values are returned by identity`() {
        val cache = EngineModelCache<Any>(capacity = 2)
        val value = Any()
        cache.put("a", value)

        assertSame(value, cache.get("a"))
        assertSame(value, cache.clear().single().second)
    }
}
