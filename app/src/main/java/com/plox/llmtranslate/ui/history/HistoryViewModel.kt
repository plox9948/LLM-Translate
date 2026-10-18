package com.plox.llmtranslate.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plox.llmtranslate.R
import com.plox.llmtranslate.data.model.FavoritesRepository
import com.plox.llmtranslate.data.model.TranslationHistoryRepository
import com.plox.llmtranslate.domain.model.FavoriteEntry
import com.plox.llmtranslate.domain.model.PendingTranslationHolder
import com.plox.llmtranslate.domain.model.TranslationHistoryEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
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

    // Сообщение сбоя репозиторных операций — id строкового ресурса; sticky до
    // закрытия UI (ревью #65): сбой Room не должен ронять процесс.
    private val _errorMessageRes = MutableStateFlow<Int?>(null)
    val errorMessageRes: StateFlow<Int?> = _errorMessageRes

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
            try {
                favoritesRepository.toggle(
                    entry.sourceLang, entry.targetLang, entry.inputText, entry.resultText
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Не удалось переключить избранность записи истории")
                _errorMessageRes.value = R.string.favorites_update_failed
            }
        }
    }

    /**
     * Подставить запись на экран «Перевод» через [PendingTranslationHolder];
     * переход на вкладку выполняет экран.
     */
    fun useEntry(entry: TranslationHistoryEntry) {
        pendingHolder.request(entry)
    }

    fun deleteEntry(id: Long) {
        viewModelScope.launch {
            try {
                repository.delete(id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Не удалось удалить запись истории id=$id")
                _errorMessageRes.value = R.string.history_delete_failed
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            try {
                repository.clear()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Не удалось очистить историю")
                _errorMessageRes.value = R.string.history_clear_failed
            }
        }
    }

    /** Скрыть сообщение об ошибке (закрытие диалога в UI). */
    fun clearError() {
        _errorMessageRes.value = null
    }
}
