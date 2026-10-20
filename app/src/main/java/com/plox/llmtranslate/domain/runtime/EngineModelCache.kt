package com.plox.llmtranslate.domain.runtime

import timber.log.Timber

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
 * Байтовый бюджет (ревью #83): помимо count-LRU ([evictOverCapacity]) кэш
 * может учитывать суммарный размер пучков ([sizeOf] при [byteBudget]) —
 * [evictOverByteBudget] выгружает наименее давно использованные до возврата
 * в бюджет. Без байтового бюджета ([Long.MAX_VALUE]) учёт нулевой: так
 * работают slimt (модели mmap — страницы изымаются ОС под давлением) и
 * STT/TTS-движки.
 *
 * @param T тип пучка модели (сессии/токенизаторы/нативный handle + метаданные).
 * @param capacity максимальное число хранимых пучков.
 * @param byteBudget бюджет суммарного размера пучков ([currentBytes]);
 *   [Long.MAX_VALUE] — байтовый учёт не применяется.
 * @param sizeOf размер одного пучка для бюджета (0 — вес не учитывается).
 */
class EngineModelCache<T>(
    private val capacity: Int,
    private val byteBudget: Long = Long.MAX_VALUE,
    private val sizeOf: (T) -> Long = { 0L },
) {

    init {
        require(capacity > 0) { "capacity должен быть положительным" }
    }

    private val map = LinkedHashMap<String, T>(capacity, 0.75f, true)

    /** Суммарный учитываемый размер хранимых пучков (по [sizeOf]). */
    private var storedBytes = 0L

    /** Суммарный учитываемый размер хранимых пучков (по [sizeOf]). */
    val currentBytes: Long
        get() = storedBytes

    /** Пучок по id с отметкой использования (fast path ensureLoaded). */
    fun get(id: String): T? = map[id]

    /**
     * Положить (или заменить) пучок; существующий ключ отмечается как
     * использованный. Возвращает ПРЕЖНЕЕ значение по id (null если ключа
     * не было) — вызывающий обязан освободить его ресурсы, иначе прежнее
     * нативное значение теряется без закрытия (ревью #79).
     */
    fun put(id: String, value: T): T? {
        val previous = map.put(id, value)
        storedBytes += sizeOf(value)
        if (previous != null) storedBytes -= sizeOf(previous)
        return previous
    }

    /** Извлечь и удалить пучок по id. */
    fun remove(id: String): T? = map.remove(id)?.also { storedBytes -= sizeOf(it) }

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
            storedBytes -= sizeOf(eldest.value)
        }
        return evicted
    }

    /**
     * Вытеснять наименее давно использованные записи, пока суммарный
     * размер не вернётся в [byteBudget] с учётом резерва [reserveBytes]
     * (ревью #83 — ORT-пучки стоят сотни МБ, одного count-LRU мало).
     * Регресс-ревью #83: [reserveBytes] = размер ЕЩЁ не созданного пучка —
     * eldest выгружается заранее, до аллокации его native-ресурсов, а не
     * только постфактум после put. При [reserveBytes] == 0 — прежнее
     * поведение возврата в бюджет. Ключи из [protect] (активный
     * пучок) не вытесняются; если после обхода бюджет всё ещё превышен —
     * остались только защищённые/единственный массивный пучок, это
     * допустимо (иначе кэш было бы нельзя использовать вовсе), но
     * логируется (ревью аудита): молчаливое вечное превышение
     * бюджета на защищённом массивном пучке должно быть видно в логе.
     * @return вытесненные пары (id, значение) в порядке вытеснения.
     */
    fun evictOverByteBudget(
        protect: Set<String> = emptySet(),
        reserveBytes: Long = 0L,
    ): List<Pair<String, T>> {
        val limitBytes = byteBudget - reserveBytes
        // Ревью аудита: при reserveBytes > byteBudget лимит
        // отрицательный — вытесняются ВСЕ незащищённые пучки; это штатная
        // реакция («хотя бы один пучок всегда пускаем»), но молчаливая.
        if (limitBytes < 0) {
            Timber.w(
                "Байтовый бюджет кэша ($byteBudget) меньше резерва нового пучка " +
                    "($reserveBytes) — выгружаются все незащищённые пучки"
            )
        }
        if (storedBytes <= limitBytes) return emptyList()
        val evicted = ArrayList<Pair<String, T>>()
        val iterator = map.entries.iterator()
        while (storedBytes > limitBytes && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key in protect) continue
            evicted += entry.key to entry.value
            iterator.remove()
            storedBytes -= sizeOf(entry.value)
        }
        // Остались только защищённые, а лимит всё ещё превышен —
        // превышение бюджета переживёт загрузку; загрузка продолжается,
        // но она должна быть наблюдаема.
        if (storedBytes > limitBytes) {
            Timber.w(
                "Байтовый бюджет превышен даже после выгрузки всех незащищённых " +
                    "пучков: resident=$storedBytes, limit=$limitBytes " +
                    "(защищённые: $protect)"
            )
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
        storedBytes = 0
        return snapshot
    }

    /** Число хранимых пучков. */
    val size: Int
        get() = map.size
}
