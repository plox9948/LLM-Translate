package com.plox.llmtranslate.domain.model

/**
 * Запись истории переводов (домен-модель).
 *
 * @param id идентификатор записи.
 * @param sourceLang код исходного языка (ru/en/ne).
 * @param targetLang код целевого языка (ru/en/ne).
 * @param inputText исходный текст.
 * @param resultText переведённый текст.
 * @param createdAt время создания (epoch millis).
 */
data class TranslationHistoryEntry(
    val id: Long,
    val sourceLang: String,
    val targetLang: String,
    val inputText: String,
    val resultText: String,
    val createdAt: Long
)
