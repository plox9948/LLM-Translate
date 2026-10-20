package com.plox.llmtranslate.data.model

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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
    suspend fun getById(id: String): ModelEntity?

    @Query(
        "SELECT * FROM models WHERE category = :category AND status = 'READY' " +
            "AND file_path IS NOT NULL AND file_path != '' ORDER BY created_at ASC LIMIT 1"
    )
    suspend fun getFirstReady(category: String): ModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ModelEntity)

    /**
     * Атомарный upsert с сохранением createdAt: read-modify-write
     * (чтение прежней строки -> INSERT OR REPLACE) выполняется в одном
     * [Transaction] (по образцу [TranslationHistoryDao.addEntry]) — раньше
     * getById и upsert шли раздельно, и параллельные upsert одной id могли
     * прочитать общий снимок и обнулить createdAt. [createdAtFallback] и
     * [updatedAt] — времена, переданные вызывающим (см.
     * [ModelRepository.upsert]); точные UPDATE-методы статуса
     * ([updateStatusById] и др.) этой обёртки не требуют.
     */
    @Transaction
    suspend fun upsertPreservingCreatedAt(
        entity: ModelEntity,
        createdAtFallback: Long,
        updatedAt: Long
    ) {
        val existing = getById(entity.id)
        upsert(
            entity.copy(
                createdAt = existing?.createdAt ?: createdAtFallback,
                updatedAt = updatedAt
            )
        )
    }

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
    @Deprecated(
        "Продакшн-вызывающих не осталось: используйте гейтированные " +
            "updateStatusWhileDownloadingById/finalizeDownloadById/markStaleDownloadingById (issue #83)"
    )
    @Query("UPDATE models SET status = :status, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateStatusById(id: String, status: String, updatedAt: Long): Int

    /**
     * Точечный перевод залипшей DOWNLOADING-записи в ERROR (лечение при
     * старте процесса, issue #83, п.2). Гейт `AND status = 'DOWNLOADING'`
     * по образцу [finalizeDownloadById] (регресс-ревью #83): финализирующий
     * воркер, успевший выставить READY между снимком списка и этим UPDATE,
     * не затирается — в отличие от [updateStatusById] без гейта.
     *
     * @return число обновлённых строк (0 — запись удалена или уже не в
     *         DOWNLOADING).
     */
    @Query(
        "UPDATE models SET status = :status, updated_at = :updatedAt " +
            "WHERE id = :id AND status = 'DOWNLOADING'"
    )
    suspend fun markStaleDownloadingById(id: String, status: String, updatedAt: Long): Int

    /**
     * Точечный перевод битой READY-записи в ERROR (сверка с дисками при
     * старте процесса). Гейт `AND status = 'READY'` — единая политика
     * статусных переходов с [finalizeDownloadById] и
     * [markStaleDownloadingById]: если между снимком READY-списка и этим
     * UPDATE модель переустановили (READY→DOWNLOADING) или иначе вывели из
     * READY, живая запись ERROR не затирается — в отличие от
     * [updateStatusById] без гейта.
     *
     * @return число обновлённых строк (0 — запись удалена или уже не в
     *         READY).
     */
    @Query(
        "UPDATE models SET status = :status, updated_at = :updatedAt " +
            "WHERE id = :id AND status = 'READY'"
    )
    suspend fun markBrokenReadyById(id: String, status: String, updatedAt: Long): Int

    /**
     * Финальная статусная запись терминальных веток воркера скачивания
     * (permanent-отказ, исчерпание попыток, повтор): тот же гейт
     * `AND status = 'DOWNLOADING'`, что у [finalizeDownloadById]
     * (единая политика переходов). Импорт той же id, промоутнувший запись
     * в READY в окне между фейлом установки и этим UPDATE, не затирается
     * ERROR/DOWNLOADING; удалённая при отмене запись не воскресает.
     *
     * @return число обновлённых строк (0 — запись удалена или не в
     *         DOWNLOADING).
     */
    @Query(
        "UPDATE models SET status = :status, updated_at = :updatedAt " +
            "WHERE id = :id AND status = 'DOWNLOADING'"
    )
    suspend fun updateStatusWhileDownloadingById(
        id: String,
        status: String,
        updatedAt: Long
    ): Int

    /**
     * Точечное обновление статуса и процента скачивания (без file_path/
     * bytes_done — они пишутся только при завершении). Каталогные поля не
     * затрагиваются, см. [updateStatusById].
     *
     * Условие `status = 'DOWNLOADING'` (issue #83, п.3, по образцу
     * [finalizeDownloadById]): запоздалый прогресс отменённого/терминально
     * завершённого воркера не перетирает READY/ERROR и не возвращает
     * готовую запись в DOWNLOADING.
     *
     * @return число обновлённых строк (0 — запись удалена или не в DOWNLOADING).
     */
    @Query(
        "UPDATE models SET status = :status, progress = :progress, " +
            "updated_at = :updatedAt WHERE id = :id AND status = 'DOWNLOADING'"
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
     * Условие `status = 'DOWNLOADING'` — защита от гонки отмены: если к
     * моменту завершения записи нет (удалена при cancel/delete) или она уже
     * выведена из DOWNLOADING (ошибка/импорт с другой стороны), READY не
     * выставляется — воркер получает 0 строк и чистит установленные файлы
     * как осиротевшие.
     *
     * @return число обновлённых строк (0 — запись удалена или не в DOWNLOADING).
     */
    @Query(
        "UPDATE models SET status = :status, progress = :progress, " +
            "file_path = :filePath, bytes_done = :bytesDone, " +
            "updated_at = :updatedAt WHERE id = :id AND status = 'DOWNLOADING'"
    )
    suspend fun finalizeDownloadById(
        id: String,
        status: String,
        progress: Int,
        filePath: String,
        bytesDone: Long,
        updatedAt: Long
    ): Int

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun deleteById(id: String)
}
