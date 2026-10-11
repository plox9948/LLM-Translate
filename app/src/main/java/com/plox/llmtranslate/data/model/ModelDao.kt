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

    /**
     * Синхронизация оценок ОЗУ существующей встроенной записи с актуальным
     * каталогом (значения в БД копируются из каталога один раз при старте
     * скачивания и устаревают после его обновления).
     *
     * UPDATE по id: отсутствующая запись не создаётся; затрагиваются только
     * каталожные записи (is_builtin = 1) — импортированные модели каталогу
     * не принадлежат. Схема не меняется, миграция не требуется.
     *
     * @return число обновлённых строк (0 или 1).
     */
    @Query(
        "UPDATE models SET ram_required_mb = :ramRequiredMb, " +
            "recommended_ram_mb = :recommendedRamMb, updated_at = :updatedAt " +
            "WHERE id = :id AND is_builtin = 1"
    )
    suspend fun syncRamFields(
        id: String,
        ramRequiredMb: Int,
        recommendedRamMb: Int,
        updatedAt: Long
    ): Int

    /**
     * Точечное обновление только статуса и времени изменения.
     *
     * В отличие от upsert (INSERT OR REPLACE всей строки) не затрагивает
     * каталожные поля (ram_required_mb, recommended_ram_mb, description и
     * прочие), поэтому не может затереть свежие значения, записанные
     * параллельно [syncRamFields].
     *
     * @return число обновлённых строк (0 — запись удалена/не найдена).
     */
    @Query("UPDATE models SET status = :status, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateStatusById(id: String, status: String, updatedAt: Long): Int

    /**
     * Точечное обновление статуса и процента скачивания (без file_path/
     * bytes_done — они пишутся только при завершении). Каталогные поля не
     * затрагиваются, см. [updateStatusById].
     *
     * @return число обновлённых строк (0 или 1).
     */
    @Query(
        "UPDATE models SET status = :status, progress = :progress, " +
            "updated_at = :updatedAt WHERE id = :id"
    )
    suspend fun updateProgressById(
        id: String,
        status: String,
        progress: Int,
        updatedAt: Long
    ): Int

    /**
     * Точечное завершение скачивания: статус, прогресс 100, путь и байты.
     * Каталогные поля не затрагиваются, см. [updateStatusById].
     *
     * @return число обновлённых строк (0 или 1).
     */
    @Query(
        "UPDATE models SET status = :status, progress = :progress, " +
            "file_path = :filePath, bytes_done = :bytesDone, " +
            "updated_at = :updatedAt WHERE id = :id"
    )
    suspend fun finalizeDownloadById(
        id: String,
        status: String,
        progress: Int,
        filePath: String,
        bytesDone: Long,
        updatedAt: Long
    ): Int

    @Delete
    suspend fun delete(entity: ModelEntity)

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun deleteById(id: String)
}
