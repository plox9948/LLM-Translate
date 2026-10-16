package com.plox.llmtranslate.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plox.llmtranslate.data.model.FavoritesRepository
import com.plox.llmtranslate.data.model.TranslationHistoryRepository
import com.plox.llmtranslate.domain.model.FavoriteEntry
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
    private val pendingHolder: PendingTranslationHolder,
    private val favoritesRepository: FavoritesRepository
) : ViewModel() {

    private val _history = MutableStateFlow<List<TranslationHistoryEntry>>(emptyList())
    val history: StateFlow<List<TranslationHistoryEntry>> = _history

    // Ключи избранного «направление|исходный текст» для звёздочки записей (issue #38).
    private val _favoriteKeys = MutableStateFlow<Set<String>>(emptySet())
    val favoriteKeys: StateFlow<Set<String>> = _favoriteKeys

    init {
        viewModelScope.launch {
            repository.observeRecent().collect { _history.value = it }
        }
        viewModelScope.launch {
            favoritesRepository.observeAll().collect { favorites ->
                _favoriteKeys.value = favorites.map {
                    FavoriteEntry.key(it.sourceLang, it.targetLang, it.inputText)
                }.toSet()
            }
        }
    }

    /** Переключить избранность записи истории (звезда в карточке). */
    fun toggleFavorite(entry: TranslationHistoryEntry) {
        viewModelScope.launch {
            favoritesRepository.toggle(
                entry.sourceLang, entry.targetLang, entry.inputText, entry.resultText
            )
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
