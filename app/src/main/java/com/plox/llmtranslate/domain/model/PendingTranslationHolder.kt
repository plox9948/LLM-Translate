package com.plox.llmtranslate.domain.model

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Одиночное (per-process) хранилище записи истории, выбранной на одноимённой
 * вкладке. Используется как сигнал для экрана «Перевод»: при появлении записи
 * он подставляет её в поля ввода/результата и очищает хранилище.
 *
 * Нужен потому, что экраны работают в отдельных записях навигационного
 * backstack и не имеют общего ViewModel.
 */
@Singleton
class PendingTranslationHolder @Inject constructor() {

    private val _pending = MutableStateFlow<TranslationHistoryEntry?>(null)
    val pending: StateFlow<TranslationHistoryEntry?> = _pending.asStateFlow()

    /** Поставить запись на перенос на экран «Перевод». */
    fun request(entry: TranslationHistoryEntry) {
        _pending.value = entry
    }

    /** Забрать и одновременно очистить текущий запрос. */
    fun consume(): TranslationHistoryEntry? {
        val value = _pending.value
        _pending.value = null
        return value
    }
}
