package com.plox.llmtranslate.ui.favorites

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plox.llmtranslate.R
import com.plox.llmtranslate.data.model.FavoritesRepository
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.model.FavoriteEntry
import com.plox.llmtranslate.domain.model.PendingTranslationHolder
import com.plox.llmtranslate.domain.model.RuntimeError
import com.plox.llmtranslate.domain.model.TranslationHistoryEntry
import com.plox.llmtranslate.domain.model.userMessage
import com.plox.llmtranslate.domain.runtime.TtsPlaybackCoordinator
import com.plox.llmtranslate.domain.runtime.TtsRuntime
import com.plox.llmtranslate.domain.runtime.trackEnginePreference
import com.plox.llmtranslate.ui.ObserveErrorTracker
import com.plox.llmtranslate.ui.launchGuarded
import com.plox.llmtranslate.ui.observeResilient
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
    private val ttsCoordinator: TtsPlaybackCoordinator,
    private val preferences: ModelPreferences,
    private val pendingHolder: PendingTranslationHolder,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    // Текущий поисковый запрос вкладки; хранится в SavedStateHandle —
    // переживает уничтожение ViewModel (смена конфигурации, гибель процесса).
    private val _searchQuery =
        MutableStateFlow(savedStateHandle[KEY_SEARCH_QUERY] ?: "")
    val searchQuery: StateFlow<String> = _searchQuery

    // Список с учётом поиска: пустой/blank запрос — все записи.
    private val _favorites = MutableStateFlow<List<FavoriteEntry>>(emptyList())
    val favorites: StateFlow<List<FavoriteEntry>> = _favorites

    // Что сейчас озвучивается (запись + поле); null — озвучивания нет.
    private val _speaking = MutableStateFlow<SpeakingSelection?>(null)
    val speaking: StateFlow<SpeakingSelection?> = _speaking

    // Сообщение сбоя репозиторной операции — id строкового ресурса; sticky до
    // закрытия UI (ревью #65): сбой Room не должен ронять процесс. Трекер
    // множества ошибок (ревью): баннер снимается только при пустом множестве.
    private val observeErrors = ObserveErrorTracker()
    val errorMessageRes: StateFlow<Int?> = observeErrors.state

    /** Последняя залогированная ошибка координатора (однократность лога). */
    private var lastLoggedTtsError: RuntimeError? = null

    init {
        // Фильтрация на устройстве: избранных записей мало, сортировка
        // репозитория сохраняется (filter порядок не меняет).
        // issue #67: combine-цепочка с Room-источником наблюдалась без
        // обработки исключений — сбой Room ронял процесс; observeResilient
        // перезапускает всю цепочку combine после сбоя, сохраняя семантику
        // combine (обоснование выбора — в его KDoc).
        observeResilient(
            scope = viewModelScope,
            source = {
                combine(
                    favoritesRepository.observeAll(),
                    _searchQuery
                ) { entries, query -> filterByQuery(entries, query) }
            },
            messageRes = R.string.favorites_observe_failed,
            errors = observeErrors,
            marker = "favorites_combined",
        ) { entries ->
            _favorites.value = entries
            // Список мог дойти позже статуса живой фразы — повторная попытка
            // привязки выбора «что говорим» (ревью #83).
            restoreSpeakingFromCoordinator()
        }
        // Инициализация TTS текущим движком (как на экране «Перевод»):
        // идемпотентная регистрация (re-review) — одновременный вызов из двух
        // ViewModel не создаёт вторую подписку, collect живёт в app-скоупе
        // рантайма.
        ttsRuntime.trackEnginePreference(preferences.ttsEngine)
        viewModelScope.launch {
            // Статус общего координатора TTS-плейбека (issue #50): сброс
            // выбора «что говорим» по завершении активной фразы (токен-
            // страховка прежних фраз — внутри координатора); ошибки
            // озвучивания здесь только логируются: автоскачивание модели
            // на экране «Избранное» идёт тихо.
            ttsCoordinator.status.collect { s ->
                // Логируем каждое новое значение ошибки один раз (StatusFlow
                // перевыпускается и при других обновлениях статуса).
                val err = s.error
                if (err != null && err != lastLoggedTtsError) {
                    Timber.w("TTS: ${err.userMessage()}")
                    lastLoggedTtsError = err
                } else if (err == null) {
                    lastLoggedTtsError = null
                }
                if (!s.isSpeaking) {
                    _speaking.value = null
                } else if (_speaking.value == null) {
                    // ViewModel пересоздана при живой фразе координатора
                    // (смена вкладки, recreation) — выбор не восстанавливался,
                    // и кнопка говорящего поля не показывала «стоп» (ревью #83).
                    restoreSpeakingFromCoordinator()
                }
            }
        }
    }

    /**
     * Привязать выбор «что говорим» к живой фразе координатора при
     * пересоздании ViewModel (ревью #83): [TtsPlaybackCoordinator] — синглтон
     * процесса, его статус переживает гибель ViewModel; запись находится
     * совпадением текста активной фразы с оригиналом или переводом. Совпадения
     * нет (фраза с экрана «Перевод», удалённая запись) — выбор остаётся null.
     * Перезапуск фразы не выполняется — достаточно стоп-состояния кнопки.
     *
     * Ограничение (ревью): [com.plox.llmtranslate.domain.runtime.TtsPlaybackStatus]
     * хранит текст активной фразы, но не идентификатор записи/фразы — привязка
     * возможна только по тексту. При дубликатах («тот же текст в двух
     * записях» или «оригинал одной записи равен переводу другой») подсветка
     * «стоп» может лечь на первую совпавшую запись не озвучиваемой — на
     * функциональность остановки (сама она идёт через координатор) это не
     * влияет. Полная привязка потребовала бы id фразы в статусе координатора.
     */
    private fun restoreSpeakingFromCoordinator() {
        if (_speaking.value != null) return
        val status = ttsCoordinator.status.value
        if (!status.isSpeaking) return
        val text = status.text ?: return
        val match = _favorites.value.firstOrNull {
            it.inputText == text || it.resultText == text
        } ?: return
        _speaking.value = SpeakingSelection(
            match.id,
            if (match.inputText == text) FavoriteSpeakField.INPUT else FavoriteSpeakField.RESULT
        )
    }

    /** Обновить поисковый запрос (поиск над списком избранного). */
    fun onQueryChange(query: String) {
        _searchQuery.value = query
        savedStateHandle[KEY_SEARCH_QUERY] = query
    }

    companion object {
        /** Ключ поискового запроса в SavedStateHandle. */
        const val KEY_SEARCH_QUERY = "favorites_search_query"

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
     * Озвучить поле [field] записи [entry] (оригинал или перевод) через общий
     * координатор TTS-плейбека (issue #50): INPUT → язык оригинала,
     * RESULT → язык перевода; выбор движка и фолбэк — в координаторе.
     * Во время озвучивания [speaking] содержит пару (id записи, поле).
     */
    fun speak(entry: FavoriteEntry, field: FavoriteSpeakField) {
        val (text, lang) = when (field) {
            FavoriteSpeakField.INPUT -> entry.inputText to entry.sourceLang
            FavoriteSpeakField.RESULT -> entry.resultText to entry.targetLang
        }
        if (text.isBlank()) return
        // speaking выставляется ДО speak(): onDone короткой фразы может
        // сработать раньше возврата speak() и иначе оставил бы флаг залипшим.
        _speaking.value = SpeakingSelection(entry.id, field)
        ttsCoordinator.speak(text, lang)
    }

    /** Остановить озвучивание (координатор гасит оба рантайма). */
    fun stopSpeaking() {
        ttsCoordinator.stop()
        _speaking.value = null
    }

    /** Убрать запись из избранного (звезда в карточке). */
    fun removeFavorite(entry: FavoriteEntry) {
        launchGuarded(
            scope = viewModelScope,
            messageRes = R.string.favorites_update_failed,
            errors = observeErrors,
            logMessage = "Не удалось убрать запись из избранного id=${entry.id}",
            marker = "remove_favorite"
        ) {
            favoritesRepository.remove(entry.sourceLang, entry.targetLang, entry.inputText)
        }
    }

    /** Скрыть сообщение об ошибке (закрытие диалога в UI). */
    fun clearError() {
        observeErrors.dismissAll()
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
