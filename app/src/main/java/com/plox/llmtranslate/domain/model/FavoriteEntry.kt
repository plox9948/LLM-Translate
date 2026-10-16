package com.plox.llmtranslate.domain.model

/**
 * Запись избранного перевода (домен-модель).
 *
 * @param id идентификатор записи.
 * @param sourceLang код исходного языка (ru/en/ne).
 * @param targetLang код целевого языка (ru/en/ne).
 * @param inputText исходный текст.
 * @param resultText переведённый текст.
 * @param createdAt время создания (epoch millis).
 */
data class FavoriteEntry(
    val id: Long,
    val sourceLang: String,
    val targetLang: String,
    val inputText: String,
    val resultText: String,
    val createdAt: Long
) {
    companion object {
        /**
         * Ключ уникальности избранного: направление перевода + исходный текст.
         * Используется UI для проверки, добавлен ли перевод в избранное.
         */
        fun key(sourceLang: String, targetLang: String, inputText: String): String =
            "$sourceLang|$targetLang|$inputText"
    }
}
