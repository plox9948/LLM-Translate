package com.plox.llmtranslate.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plox.llmtranslate.data.model.FavoritesRepository
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.model.FavoriteEntry
import com.plox.llmtranslate.domain.model.PendingTranslationHolder
import com.plox.llmtranslate.domain.model.TranslationHistoryEntry
import com.plox.llmtranslate.domain.model.userMessage
import com.plox.llmtranslate.domain.runtime.TtsRuntime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject

/** Поле записи избранного, озвучиваемое TTS: оригинал или перевод. */
enum class FavoriteSpeakField { INPUT, RESULT }

/**
 * Какой экран/поле сейчас озвучиваются: id записи + поле ([FavoriteSpeakField]).
 * UI показывает «Стоп» только на кнопке того поля, которое говорится сейчас.
 */
data class SpeakingSelection(val entryId: Long, val field: FavoriteSpeakField)

/**
 * ViewModel вкладки «Избранное»: просмотр избранных переводов, озвучивание
 * оригинала/перевода TTS и подстановка записи на экран «Перевод».
 */
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    private val ttsRuntime: TtsRuntime,
    private val preferences: ModelPreferences,
    private val pendingHolder: PendingTranslationHolder
) : ViewModel() {

    // Текущий поисковый запрос вкладки.
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    // Список с учётом поиска: пустой/blank запрос — все записи.
    private val _favorites = MutableStateFlow<List<FavoriteEntry>>(emptyList())
    val favorites: StateFlow<List<FavoriteEntry>> = _favorites

    // Что сейчас озвучивается (запись + поле); null — озвучивания нет.
    private val _speaking = MutableStateFlow<SpeakingSelection?>(null)
    val speaking: StateFlow<SpeakingSelection?> = _speaking

    init {
        // Фильтрация на устройстве: избранных записей мало, сортировка
        // репозитория сохраняется (filter порядок не меняет).
        viewModelScope.launch {
            combine(
                favoritesRepository.observeAll(),
                _searchQuery
            ) { entries, query -> filterByQuery(entries, query) }
                .collect { _favorites.value = it }
        }
        viewModelScope.launch {
            // Инициализация TTS текущим движком (как на экране «Перевод»).
            preferences.ttsEngine.collect { engine ->
                val error = ttsRuntime.init(engine)
                if (error != null) {
                    Timber.w("TTS недоступен: ${error.userMessage()}")
                }
            }
        }
    }

    /** Обновить поисковый запрос (поиск над списком избранного). */
    fun onQueryChange(query: String) {
        _searchQuery.value = query
    }

    companion object {
        /**
         * Оставить записи, у которых оригинал ИЛИ перевод содержит [query]
         * без учёта регистра. Пустой/blank запрос — весь список [entries].
         */
        internal fun filterByQuery(
            entries: List<FavoriteEntry>,
            query: String
        ): List<FavoriteEntry> {
            val needle = query.trim().lowercase(Locale.ROOT)
            if (needle.isEmpty()) return entries
            return entries.filter {
                it.inputText.lowercase(Locale.ROOT).contains(needle) ||
                    it.resultText.lowercase(Locale.ROOT).contains(needle)
            }
        }
    }

    /**
     * Озвучить поле [field] записи [entry] (оригинал или перевод) текущей TTS.
     * Во время озвучивания [speaking] содержит пару (id записи, поле).
     */
    fun speak(entry: FavoriteEntry, field: FavoriteSpeakField) {
        val (text, lang) = when (field) {
            FavoriteSpeakField.INPUT -> entry.inputText to entry.sourceLang
            FavoriteSpeakField.RESULT -> entry.resultText to entry.targetLang
        }
        if (text.isBlank()) return
        val selection = SpeakingSelection(entry.id, field)
        // speaking выставляется ДО speak(): onDone короткой фразы может
        // сработать раньше возврата speak() и иначе оставил бы флаг залипшим.
        _speaking.value = selection
        viewModelScope.launch {
            val error = ttsRuntime.speak(text, lang) {
                // Завершение ранее запущенной фразы не должно сбрасывать
                // флаг, если уже озвучивается другое поле/запись.
                if (_speaking.value == selection) _speaking.value = null
            }
            if (error != null) {
                Timber.w("TTS: ${error.userMessage()}")
                if (_speaking.value == selection) _speaking.value = null
            }
        }
    }

    /** Остановить озвучивание. */
    fun stopSpeaking() {
        ttsRuntime.stop()
        _speaking.value = null
    }

    /** Убрать запись из избранного (звезда в карточке). */
    fun removeFavorite(entry: FavoriteEntry) {
        viewModelScope.launch {
            favoritesRepository.remove(entry.sourceLang, entry.targetLang, entry.inputText)
        }
    }

    /** Подставить запись на экран «Перевод» (конвертация в формат истории). */
    fun useEntry(entry: FavoriteEntry) {
        pendingHolder.request(
            TranslationHistoryEntry(
                id = entry.id,
                sourceLang = entry.sourceLang,
                targetLang = entry.targetLang,
                inputText = entry.inputText,
                resultText = entry.resultText,
                createdAt = entry.createdAt
            )
        )
    }
}
