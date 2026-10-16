package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.ModelRetentionProvider

/**
 * Фейковый поставщик политики удержания моделей для unit-тестов движков:
 * отдаёт фиксированное значение и считает обращения (issue #36).
 */
class FakeModelRetentionProvider(
    var keep: Boolean
) : ModelRetentionProvider {

    /** Число обращений движка к настройке. */
    var calls = 0
        private set

    override suspend fun keepModelsOnDirectionChange(): Boolean {
        calls++
        return keep
    }
}
