package com.plox.llmtranslate.data.model

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
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

    @Query("DELETE FROM translation_history WHERE id NOT IN (SELECT id FROM translation_history ORDER BY created_at DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM translation_history WHERE source_lang = :sourceLang AND target_lang = :targetLang AND input_text = :inputText")
    suspend fun deleteMatching(sourceLang: String, targetLang: String, inputText: String)
}
