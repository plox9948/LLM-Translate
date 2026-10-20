package com.plox.llmtranslate.data.model

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * DAO истории переводов.
 */
@Dao
interface TranslationHistoryDao {

    @Query("SELECT * FROM translation_history ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TranslationHistoryEntity>>

    @Insert
    suspend fun insert(entity: TranslationHistoryEntity): Long

    @Query("DELETE FROM translation_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM translation_history")
    suspend fun clear()

    /**
     * Обрезка истории до [keep] самых свежих записей. Вторичная сортировка
     * по id (автоинкремент = порядок вставки): при одинаковых created_at
     * (мс) без неё `LIMIT` выберал бы удаляемые строки недетерминированно —
     * с ней всегда вытесняются самые ранние вставки. Детерминизм проверяется
     * instrumentation-тестом TranslationHistoryTrimTest на реальном Room
     * (unit-фейк DAO лишь повторяет этот порядок в памяти).
     */
    @Query(
        "DELETE FROM translation_history WHERE id NOT IN (" +
            "SELECT id FROM translation_history " +
            "ORDER BY created_at DESC, id DESC LIMIT :keep)"
    )
    suspend fun trim(keep: Int)

    @Query("DELETE FROM translation_history WHERE source_lang = :sourceLang AND target_lang = :targetLang AND input_text = :inputText")
    suspend fun deleteMatching(sourceLang: String, targetLang: String, inputText: String)

    /**
     * Атомарное добавление записи: удалить предыдущий идентичный перевод,
     * вставить [entity], обрезать историю до [keep]. Один @Transaction —
     * конкурентные добавления не плодят дубликаты (по образцу
     * [FavoritesDao.toggleEntry]).
     */
    @Transaction
    suspend fun addEntry(entity: TranslationHistoryEntity, keep: Int) {
        deleteMatching(entity.sourceLang, entity.targetLang, entity.inputText)
        insert(entity)
        trim(keep)
    }
}
