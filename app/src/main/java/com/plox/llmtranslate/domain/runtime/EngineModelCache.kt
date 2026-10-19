package com.plox.llmtranslate.domain.runtime

/**
 * LRU-кэш пучков моделей одного движка (issue #36).
 *
 * Движок может держать несколько моделей (например, оба направления
 * перевода), вытесняя наименее давно используемые сверх [capacity].
 * Порядок обхода LinkedHashMap — accessOrder: [get] и [put] существующего
 * ключа переставляют запись в конец, поэтому [evictOverCapacity] удаляет
 * именно наименее давно использованные записи.
 *
 * Потокобезопасности нет: используется только под мьютексом движка.
 * Освобождение ресурсов вытесненных значений — за самим вызывающим:
 * методы возвращают вытеснённые/удалённые значения наружу.
 *
 * Осознанный долг (контрольный аудит, п.6): ёмкость кэша считается по
 * ЧИСЛУ пучков ([capacity]), байтовый лимит ОЗУ не учитывается — при
 * крупных моделях суммарное resident-потребление кэша может превысить
 * разумный бюджет до срабатывания LRU. Митигация сейчас: RAM-предупреждения
 * [InstallCheck.Warning] при установке (InstallCheckLogic) и баннер о
 * нехватке ОЗУ в UI; полный байтовый лимит кэша — не реализован.
 *
 * @param T тип пучка модели (сессии/токенизаторы/нативный handle + метаданные).
 * @param capacity максимальное число хранимых пучков.
 */
class EngineModelCache<T>(private val capacity: Int) {

    init {
        require(capacity > 0) { "capacity должен быть положительным" }
    }

    private val map = LinkedHashMap<String, T>(capacity, 0.75f, true)

    /** Пучок по id с отметкой использования (fast path ensureLoaded). */
    fun get(id: String): T? = map[id]

    /**
     * Положить (или заменить) пучок; существующий ключ отмечается как
     * использованный. Возвращает ПРЕЖНЕЕ значение по id (null если ключа
     * не было) — вызывающий обязан освободить его ресурсы, иначе прежнее
     * нативное значение теряется без закрытия (ревью #79).
     */
    fun put(id: String, value: T): T? = map.put(id, value)

    /** Извлечь и удалить пучок по id. */
    fun remove(id: String): T? = map.remove(id)

    /**
     * Вытеснить записи сверх [capacity] (наименее давно использованные — первыми).
     * @return вытесненные пары (id, значение) в порядке вытеснения.
     */
    fun evictOverCapacity(): List<Pair<String, T>> {
        if (map.size <= capacity) return emptyList()
        // Один итератор с начала (наименее давно использованные — первые):
        // прежняя версия создавала новый итератор на каждую итерацию (ревью).
        val evicted = ArrayList<Pair<String, T>>(map.size - capacity)
        val iterator = map.entries.iterator()
        while (map.size > capacity && iterator.hasNext()) {
            val eldest = iterator.next()
            evicted += eldest.key to eldest.value
            iterator.remove()
        }
        return evicted
    }

    /** Все пучки от наименее давно использованного к наиболее недавнему. */
    fun all(): List<Pair<String, T>> = map.entries.map { it.key to it.value }

    /** Ключи хранимых пучков, которых нет в [keep]. */
    fun keysNotIn(keep: Set<String>): List<String> = map.keys.filter { it !in keep }

    /** Очистить кэш; вернуть все удалённые пучки. */
    fun clear(): List<Pair<String, T>> {
        val snapshot = all()
        map.clear()
        return snapshot
    }

    /** Число хранимых пучков. */
    val size: Int
        get() = map.size
}
