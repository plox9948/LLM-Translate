package com.plox.llmtranslate.data.model

import com.plox.llmtranslate.domain.model.FavoriteEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Репозиторий избранного переводов поверх Room.
 *
 * Дедуплицирует записи по ключу (направление + исходный текст):
 * повторное добавление заменяет предыдущую запись и не создаёт дубликат.
 */
@Singleton
class FavoritesRepository @Inject constructor(
    private val dao: FavoritesDao
) {

    /** Все избранные записи, новые сверху. */
    fun observeAll(): Flow<List<FavoriteEntry>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    /**
     * Добавить перевод в избранное. Предыдущая идентичная запись (то же
     * направление и текст) удаляется, чтобы повторы не плодились.
     */
    suspend fun add(sourceLang: String, targetLang: String, inputText: String, resultText: String) {
        if (inputText.isBlank() || resultText.isBlank()) return
        dao.deleteMatching(sourceLang, targetLang, inputText)
        dao.insert(
            FavoriteEntity(
                sourceLang = sourceLang,
                targetLang = targetLang,
                inputText = inputText,
                resultText = resultText,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    /** Удалить запись избранного по ключу (направление + исходный текст). */
    suspend fun remove(sourceLang: String, targetLang: String, inputText: String) =
        dao.deleteMatching(sourceLang, targetLang, inputText)

    /**
     * Переключить избранность: если запись с таким направлением и текстом
     * уже есть — удалить и вернуть false; иначе — добавить и вернуть true.
     */
    suspend fun toggle(sourceLang: String, targetLang: String, inputText: String, resultText: String): Boolean {
        val exists = dao.observeAll().first().any {
            it.sourceLang == sourceLang && it.targetLang == targetLang &&
                it.inputText == inputText
        }
        return if (exists) {
            dao.deleteMatching(sourceLang, targetLang, inputText)
            false
        } else {
            add(sourceLang, targetLang, inputText, resultText)
            true
        }
    }

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
