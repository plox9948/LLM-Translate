package com.plox.llmtranslate.data.model

import com.plox.llmtranslate.domain.model.FavoriteEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Репозиторий избранного переводов поверх Room.
 *
 * Дедуплицирует записи по ключу (направление + исходный текст):
 * [toggle] переключает избранность атомарно, дубликаты по ключу удаляются.
 */
@Singleton
class FavoritesRepository @Inject constructor(
    private val dao: FavoritesDao
) {

    /** Все избранные записи, новые сверху. */
    fun observeAll(): Flow<List<FavoriteEntry>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    /** Удалить запись избранного по ключу (направление + исходный текст). */
    suspend fun remove(sourceLang: String, targetLang: String, inputText: String) =
        dao.deleteMatching(sourceLang, targetLang, inputText)

    /**
     * Переключить избранность: если запись с таким направлением и текстом
     * уже есть — удалить и вернуть false; иначе — добавить и вернуть true.
     */
    suspend fun toggle(sourceLang: String, targetLang: String, inputText: String, resultText: String): Boolean =
        // exists + delete/insert — одной транзакцией DAO: check-then-act вне
        // транзакции гонит двойной тап в SQLiteConstraintException на
        // уникальном idx_favorites_unique.
        dao.toggleEntry(
            sourceLang,
            targetLang,
            inputText,
            FavoriteEntity(
                sourceLang = sourceLang,
                targetLang = targetLang,
                inputText = inputText,
                resultText = resultText,
                createdAt = System.currentTimeMillis()
            )
        )

    suspend fun delete(id: Long) = dao.deleteById(id)
}

private fun FavoriteEntity.toDomain() = FavoriteEntry(
    id = id,
    sourceLang = sourceLang,
    targetLang = targetLang,
    inputText = inputText,
    resultText = resultText,
    createdAt = createdAt
)
