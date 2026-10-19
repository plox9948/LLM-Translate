package com.plox.llmtranslate.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plox.llmtranslate.R
import com.plox.llmtranslate.data.model.FavoritesRepository
import com.plox.llmtranslate.data.model.TranslationHistoryRepository
import com.plox.llmtranslate.domain.model.FavoriteEntry
import com.plox.llmtranslate.domain.model.PendingTranslationHolder
import com.plox.llmtranslate.domain.model.TranslationHistoryEntry
import com.plox.llmtranslate.ui.ObserveErrorTracker
import com.plox.llmtranslate.ui.launchGuarded
import com.plox.llmtranslate.ui.observeResilient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    // Сообщение сбоя репозиторных операций/подписок — id строкового ресурса;
    // sticky до закрытия UI (ревью #65): сбой Room не должен ронять процесс.
    // Трекер множества ошибок (ревью): баннер снимается только когда все
    // активные сбои сняты, восстановление одного источника не гасит другой.
    private val observeErrors = ObserveErrorTracker()
    val errorMessageRes: StateFlow<Int?> = observeErrors.state

    init {
        // issue #67: Room-flow наблюдателся без обработки исключений — сбой Room
        // ронял процесс; observeResilient логирует, показывает ошибку в UI и
        // перезапускает подписку (обоснование выбора — в его KDoc).
        observeResilient(
            scope = viewModelScope,
            source = { repository.observeRecent() },
            messageRes = R.string.history_observe_failed,
            errors = observeErrors,
            marker = "history_recent",
        ) { _history.value = it }
        observeResilient(
            scope = viewModelScope,
            source = { favoritesRepository.observeAll() },
            messageRes = R.string.favorites_observe_failed,
            errors = observeErrors,
            marker = "favorites_all",
        ) { favorites ->
            _favoriteKeys.value = favorites.map {
                FavoriteEntry.key(it.sourceLang, it.targetLang, it.inputText)
            }.toSet()
        }
    }

    /** Переключить избранность записи истории (звезда в карточке). */
    fun toggleFavorite(entry: TranslationHistoryEntry) {
        launchGuarded(
            scope = viewModelScope,
            messageRes = R.string.favorites_update_failed,
            errors = observeErrors,
            logMessage = "Не удалось переключить избранность записи истории",
            marker = "toggle_favorite"
        ) {
            favoritesRepository.toggle(
                entry.sourceLang, entry.targetLang, entry.inputText, entry.resultText
            )
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
        launchGuarded(
            scope = viewModelScope,
            messageRes = R.string.history_delete_failed,
            errors = observeErrors,
            logMessage = "Не удалось удалить запись истории id=$id",
            marker = "delete_entry"
        ) {
            repository.delete(id)
        }
    }

    fun clearAll() {
        launchGuarded(
            scope = viewModelScope,
            messageRes = R.string.history_clear_failed,
            errors = observeErrors,
            logMessage = "Не удалось очистить историю",
            marker = "clear_all"
        ) {
            repository.clear()
        }
    }

    /** Скрыть сообщение об ошибке (закрытие диалога в UI). */
    fun clearError() {
        observeErrors.dismissAll()
    }
}
