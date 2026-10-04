package com.plox.llmtranslate.data.model

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO для моделей.
 */
@Dao
interface ModelDao {

    @Query("SELECT * FROM models")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE category = :category")
    fun observeByCategory(category: String): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE id = :id")
    fun observeById(id: String): Flow<ModelEntity?>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun getById(id: String): ModelEntity?

    @Query(
        "SELECT * FROM models WHERE category = :category AND status = 'READY' " +
            "AND file_path IS NOT NULL AND file_path != '' ORDER BY created_at ASC LIMIT 1"
    )
    suspend fun getFirstReady(category: String): ModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ModelEntity)

    @Delete
    suspend fun delete(entity: ModelEntity)

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun deleteById(id: String)
}
