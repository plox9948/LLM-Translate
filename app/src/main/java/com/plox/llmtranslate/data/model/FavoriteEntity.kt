package com.plox.llmtranslate.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Сущность Room: запись избранного (избранные переводы).
 *
 * Уникальный индекс по (направление, исходный текст) гарантирует,
 * что один и тот же перевод не может быть добавлен в избранное дважды.
 */
@Entity(
    tableName = "favorites",
    indices = [
        Index(
            name = "idx_favorites_unique",
            value = ["source_lang", "target_lang", "input_text"],
            unique = true
        )
    ]
)
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_lang") val sourceLang: String,
    @ColumnInfo(name = "target_lang") val targetLang: String,
    @ColumnInfo(name = "input_text") val inputText: String,
    @ColumnInfo(name = "result_text") val resultText: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
