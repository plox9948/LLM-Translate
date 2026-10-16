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
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

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

    private val _favorites = MutableStateFlow<List<FavoriteEntry>>(emptyList())
    val favorites: StateFlow<List<FavoriteEntry>> = _favorites

    // Id записи, которую сейчас озвучивают (null — озвучивания нет).
    private val _speakingId = MutableStateFlow<Long?>(null)
    val speakingId: StateFlow<Long?> = _speakingId

    init {
        viewModelScope.launch {
            favoritesRepository.observeAll().collect { _favorites.value = it }
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

    /**
     * Озвучить текст записи ([text] на языке [lang]) текущей TTS.
     * Во время озвучивания [speakingId] содержит id записи.
     */
    fun speak(entry: FavoriteEntry, text: String, lang: String) {
        if (text.isBlank()) return
        _speakingId.value = null
        viewModelScope.launch {
            val error = ttsRuntime.speak(text, lang) { _speakingId.value = null }
            if (error != null) {
                Timber.w("TTS: ${error.userMessage()}")
            } else {
                _speakingId.value = entry.id
            }
        }
    }

    /** Остановить озвучивание. */
    fun stopSpeaking() {
        ttsRuntime.stop()
        _speakingId.value = null
    }

    /** Убрать запись из избранного (звезда в карточке). */
    fun removeFavorite(entry: FavoriteEntry) {
        viewModelScope.launch {
            favoritesRepository.remove(entry.sourceLang, entry.targetLang, entry.inputText)
        }
    }

    /** Удалить запись избранного по id. */
    fun deleteFavorite(id: Long) {
        viewModelScope.launch { favoritesRepository.delete(id) }
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
