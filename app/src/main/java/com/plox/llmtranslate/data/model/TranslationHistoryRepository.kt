package com.plox.llmtranslate.data.model

import com.plox.llmtranslate.domain.model.TranslationHistoryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Репозиторий истории переводов поверх Room.
 *
 * Хранит последние [MAX_ENTRIES] записей (новые поверх старых), дедуплицируя
 * повторы одного и того же перевода.
 */
@Singleton
class TranslationHistoryRepository @Inject constructor(
    private val dao: TranslationHistoryDao
) {

    companion object {
        /** Максимальное число хранимых записей истории. */
        const val MAX_ENTRIES = 100
    }

    /** Последние записи истории, новые сверху. */
    fun observeRecent(limit: Int = MAX_ENTRIES): Flow<List<TranslationHistoryEntry>> =
        dao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    /**
     * Добавить успешный перевод. Предыдущая идентичная запись (то же направление
     * и текст) удаляется, чтобы повторы не плодились.
     */
    suspend fun add(sourceLang: String, targetLang: String, inputText: String, resultText: String) {
        if (inputText.isBlank() || resultText.isBlank()) return
        dao.deleteMatching(sourceLang, targetLang, inputText)
        dao.insert(
            TranslationHistoryEntity(
                sourceLang = sourceLang,
                targetLang = targetLang,
                inputText = inputText,
                resultText = resultText,
                createdAt = System.currentTimeMillis()
            )
        )
        dao.trim(MAX_ENTRIES)
    }

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun clear() = dao.clear()
}

private fun TranslationHistoryEntity.toDomain() = TranslationHistoryEntry(
    id = id,
    sourceLang = sourceLang,
    targetLang = targetLang,
    inputText = inputText,
    resultText = resultText,
    createdAt = createdAt
)
