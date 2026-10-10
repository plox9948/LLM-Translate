package com.plox.llmtranslate.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Сущность Room: запись истории переводов.
 *
 * Используется как вспомогательный материал для тестирования: последние
 * переводы можно быстро подставить обратно в поле ввода.
 */
@Entity(
    tableName = "translation_history",
    indices = [Index(name = "idx_history_created_at", value = ["created_at"])]
)
data class TranslationHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_lang") val sourceLang: String,
    @ColumnInfo(name = "target_lang") val targetLang: String,
    @ColumnInfo(name = "input_text") val inputText: String,
    @ColumnInfo(name = "result_text") val resultText: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
