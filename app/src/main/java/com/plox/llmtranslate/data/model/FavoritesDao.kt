package com.plox.llmtranslate.data.model

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * DAO избранного переводов.
 */
@Dao
interface FavoritesDao {

    @Query("SELECT * FROM favorites ORDER BY created_at DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Insert
    suspend fun insert(entity: FavoriteEntity): Long

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Удалить совпадающие записи; @return число удалённых строк. */
    @Query("DELETE FROM favorites WHERE source_lang = :sourceLang AND target_lang = :targetLang AND input_text = :inputText")
    suspend fun deleteMatching(sourceLang: String, targetLang: String, inputText: String): Int

    /**
     * Удалить совпадающую запись и вставить новую одной транзакцией:
     * check-then-act без транзакции даёт UNIQUE constraint violation
     * на idx_favorites_unique при параллельных добавлениях.
     */
    @Transaction
    suspend fun replaceMatching(entity: FavoriteEntity) {
        deleteMatching(entity.sourceLang, entity.targetLang, entity.inputText)
        insert(entity)
    }

    /**
     * Атомарное переключение избранности: удалить совпадение; если удалили —
     * вернуть false, иначе вставить [entity] и вернуть true.
     */
    @Transaction
    suspend fun toggleEntry(
        sourceLang: String,
        targetLang: String,
        inputText: String,
        entity: FavoriteEntity
    ): Boolean {
        return if (deleteMatching(sourceLang, targetLang, inputText) > 0) {
            false
        } else {
            insert(entity)
            true
        }
    }
}
