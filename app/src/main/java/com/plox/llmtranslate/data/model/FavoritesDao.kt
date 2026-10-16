package com.plox.llmtranslate.data.model

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
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

    @Query("DELETE FROM favorites WHERE source_lang = :sourceLang AND target_lang = :targetLang AND input_text = :inputText")
    suspend fun deleteMatching(sourceLang: String, targetLang: String, inputText: String)
}
