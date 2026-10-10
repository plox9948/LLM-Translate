package com.plox.llmtranslate.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plox.llmtranslate.data.model.TranslationHistoryRepository
import com.plox.llmtranslate.domain.model.PendingTranslationHolder
import com.plox.llmtranslate.domain.model.TranslationHistoryEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel вкладки «История»: просмотр переводов, подстановка в «Перевод» и
 * управление записями.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: TranslationHistoryRepository,
    private val pendingHolder: PendingTranslationHolder
) : ViewModel() {

    private val _history = MutableStateFlow<List<TranslationHistoryEntry>>(emptyList())
    val history: StateFlow<List<TranslationHistoryEntry>> = _history

    init {
        viewModelScope.launch {
            repository.observeRecent().collect { _history.value = it }
        }
    }

    /**
     * Подставить запись на экран «Перевод». Возвращает true, чтобы экран мог
     * выполнить переход на вкладку перевода.
     */
    fun useEntry(entry: TranslationHistoryEntry) {
        pendingHolder.request(entry)
    }

    fun deleteEntry(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun clearAll() {
        viewModelScope.launch { repository.clear() }
    }
}
