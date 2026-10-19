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
    fun `put returns previous value so caller can release it`() {
        // Ревью #79: прежний пучок при замене не должен теряться — put
        // возвращает прежнее значение для освобождения его ресурсов.
        val cache = EngineModelCache<String>(capacity = 3)
        assertNull(cache.put("a", "A"))
        assertEquals("A", cache.put("a", "A2"))
        assertEquals("A2", cache.put("a", "A3"))
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
    fun `remove drops only deleted model and keeps others`() {
        // Issue #40: путь удаления модели — remove(id) возвращает пучок для
        // освобождения, остальные записи и их порядок использования целы.
        val cache = EngineModelCache<String>(capacity = 3)
        cache.put("ru-en", "A")
        cache.put("en-ru", "B")
        cache.get("ru-en")

        assertEquals("B", cache.remove("en-ru"))
        assertNull(cache.get("en-ru"))
        assertEquals(1, cache.size)
        // Оставшийся пучок — самый недавно использованный, при эвикции
        // сверх ёмкости он не вытесняется.
        cache.put("ru-ne", "C")
        cache.put("ne-ru", "D")
        assertTrue(cache.evictOverCapacity().isEmpty())
        assertEquals(listOf("ru-en", "ru-ne", "ne-ru"), cache.all().map { it.first })
    }

    @Test
    fun `cache empty after removing the only entry`() {
        val cache = EngineModelCache<String>(capacity = 3)
        cache.put("only", "A")

        assertEquals("A", cache.remove("only"))

        assertTrue(cache.all().isEmpty())
        assertEquals(0, cache.size)
        assertNull(cache.get("only"))
    }

    @Test
    fun `values are returned by identity`() {
        val cache = EngineModelCache<Any>(capacity = 2)
        val value = Any()
        cache.put("a", value)

        assertSame(value, cache.get("a"))
        assertSame(value, cache.clear().single().second)
    }

    // ---- байтовый бюджет (ревью #83) ----

    @Test
    fun `byte budget evicts eldest until within budget`() {
        val cache = EngineModelCache<String>(
            capacity = 5, byteBudget = 10, sizeOf = { it.length.toLong() }
        )
        cache.put("a", "AAAAAA")
        cache.put("b", "BBBBBB")
        assertEquals(12, cache.currentBytes)

        val evicted = cache.evictOverByteBudget()

        assertEquals(listOf("a" to "AAAAAA"), evicted)
        assertEquals(6, cache.currentBytes)
        assertEquals(listOf("b"), cache.all().map { it.first })
    }

    @Test
    fun `byte budget never evicts protected active bundle`() {
        val cache = EngineModelCache<String>(
            capacity = 5, byteBudget = 8, sizeOf = { it.length.toLong() }
        )
        cache.put("a", "AAAA")
        cache.put("b", "BBBB")
        cache.put("c", "CCCC")

        val evicted = cache.evictOverByteBudget(protect = setOf("b", "c"))

        assertEquals(listOf("a" to "AAAA"), evicted)
        assertEquals(listOf("b", "c"), cache.all().map { it.first })
        assertEquals(8, cache.currentBytes)
    }

    @Test
    fun `single oversized protected bundle stays in cache`() {
        // Единственный массивный пучок активен — выгружать нечего,
        // кэш остаётся сверх бюджета, но работоспособен.
        val cache = EngineModelCache<String>(
            capacity = 3, byteBudget = 4, sizeOf = { it.length.toLong() }
        )
        cache.put("big", "BBBBBBBBBB")

        assertTrue(cache.evictOverByteBudget(protect = setOf("big")).isEmpty())
        assertEquals(10, cache.currentBytes)
    }

    @Test
    fun `put replacement, remove and count eviction keep byte accounting`() {
        val cache = EngineModelCache<String>(
            capacity = 2, byteBudget = 100, sizeOf = { it.length.toLong() }
        )
        cache.put("a", "AAA")
        cache.put("a", "AA")
        assertEquals(2, cache.currentBytes)
        cache.remove("a")
        assertEquals(0, cache.currentBytes)

        cache.put("x", "XXXX")
        cache.put("y", "YYYY")
        cache.put("z", "ZZZZ")
        assertEquals(listOf("x" to "XXXX"), cache.evictOverCapacity())
        assertEquals(8, cache.currentBytes)
        cache.clear()
        assertEquals(0, cache.currentBytes)
    }

    @Test
    fun `default cache has no byte gate`() {
        val cache = EngineModelCache<String>(capacity = 2)
        cache.put("a", "AAAA")
        assertEquals(0, cache.currentBytes)
        assertTrue(cache.evictOverByteBudget().isEmpty())
    }

    @Test
    fun `reserveBytes evicts eldest ahead of the incoming bundle`() {
        // Регресс-ревью #83: eldest выгружается ДО создания native-ресурсов
        // нового пучка — бюджет учитывает размер ещё не положенного пучка.
        val cache = EngineModelCache<String>(
            capacity = 3, byteBudget = 12, sizeOf = { it.length.toLong() }
        )
        cache.put("a", "AAAAAA")
        cache.put("b", "BBBBBB")
        // Без резерва бюджет ровно достигнут — эвикции нет.
        assertTrue(cache.evictOverByteBudget().isEmpty())
        // С резервом под будущий пучок 6 — eldest "a" выгружается заранее.
        assertEquals(
            listOf("a" to "AAAAAA"),
            cache.evictOverByteBudget(protect = setOf("incoming"), reserveBytes = 6L)
        )
        assertEquals(6, cache.currentBytes)
    }
}
