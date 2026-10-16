package com.plox.llmtranslate.domain

/**
 * Поставщик политики удержания моделей движками при смене направления
 * перевода (issue #36). Реализация читает настройку `model_direction_retention`
 * из DataStore в момент загрузки модели, поэтому смена настройки применяется
 * сразу, без перезапуска процесса.
 */
fun interface ModelRetentionProvider {

    /**
     * true — не выгружать модели других направлений (движок держит их в
     * LRU-кэше), false — выгружать все модели движка перед загрузкой новой.
     * При ошибке чтения или неизвестном значении — true (безопасный дефолт keep).
     */
    suspend fun keepModelsOnDirectionChange(): Boolean
}
