package com.plox.llmtranslate.ui.translate

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plox.llmtranslate.R
import com.plox.llmtranslate.data.model.FavoritesRepository
import com.plox.llmtranslate.data.model.TranslationHistoryRepository
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.ModelManager
import com.plox.llmtranslate.domain.model.FavoriteEntry
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.LanguageDetector
import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.PendingTranslationHolder
import com.plox.llmtranslate.domain.model.RuntimeError
import com.plox.llmtranslate.domain.model.SentenceSplitter
import com.plox.llmtranslate.domain.model.SttModelSelector
import com.plox.llmtranslate.domain.model.TranslationHistoryEntry
import com.plox.llmtranslate.domain.model.TranslationModelSelector
import com.plox.llmtranslate.domain.model.userMessage
import com.plox.llmtranslate.domain.runtime.AudioRecorder
import com.plox.llmtranslate.domain.runtime.RuntimeRouter
import com.plox.llmtranslate.domain.runtime.TtsDownloadStatus
import com.plox.llmtranslate.domain.runtime.TtsPlaybackCoordinator
import com.plox.llmtranslate.domain.runtime.TranslationEngine
import com.plox.llmtranslate.domain.runtime.TtsRuntime
import com.plox.llmtranslate.domain.runtime.trackEnginePreference
import com.plox.llmtranslate.ui.ObserveErrorTracker
import com.plox.llmtranslate.ui.launchGuarded
import com.plox.llmtranslate.ui.observeResilient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Поддерживаемые языки перевода.
 */
enum class TranslateLanguage(val code: String, val titleRes: Int) {
    AUTO("auto", R.string.lang_title_auto),
    RU("ru", R.string.lang_title_ru),
    EN("en", R.string.lang_title_en),
    NE("ne", R.string.lang_title_ne);

    companion object {
        /**
         * Поддерживаемые коды языков перевода без AUTO — общий список чипов
         * назначения STT/TTS-моделей (ревью: не дублировать литералы).
         */
        val SUPPORTED_CODES: List<String> = entries.filter { it != AUTO }.map { it.code }
    }
}

/**
 * Варианты онбординга первого запуска: индекс списка → направление перевода
 * (issue #33). Направление применяется через обычные механизмы смены языков.
 */
enum class OnboardingOption(
    val sourceLang: TranslateLanguage,
    val targetLang: TranslateLanguage,
    val labelRes: Int
) {
    RU_EN(TranslateLanguage.RU, TranslateLanguage.EN, R.string.onboarding_option_ru_en),
    EN_NE(TranslateLanguage.EN, TranslateLanguage.NE, R.string.onboarding_option_en_ne),
    RU_NE(TranslateLanguage.RU, TranslateLanguage.NE, R.string.onboarding_option_ru_ne);

    companion object {
        /** Вариант по индексу списка на sheet; null — индекса нет. */
        fun byIndex(index: Int): OnboardingOption? = entries.getOrNull(index)
    }
}

/**
 * Состояние UI экрана «Перевод».
 */
data class TranslateUiState(
    val sourceLang: TranslateLanguage = TranslateLanguage.RU,
    val targetLang: TranslateLanguage = TranslateLanguage.EN,
    val inputText: String = "",
    val resultText: String = "",
    /**
     * Направление, к которому был переведён [resultText] (issue #59):
     * null — результат не привязан. Показывается и озвучивается только
     * при совпадении с текущими [sourceLang]/[targetLang] — см. [visibleResult].
     */
    val resultSourceLang: TranslateLanguage? = null,
    val resultTargetLang: TranslateLanguage? = null,
    /**
     * Снапшот [inputText] на момент, когда получен [resultText] (перевод
     * или подстановка записи из истории): пара «направление|исходный текст»
     * избранного строится по нему, а не по текущему полю ввода — правка
     * поля после перевода не рассогласовывает звезду с результатом.
     */
    val resultInputText: String = "",
    /** Текущий перевод добавлен в избранное (звёздочка результата, issue #38). */
    val resultFavorite: Boolean = false,
    val isTranslating: Boolean = false,
    val canCancelTranslation: Boolean = false,
    val isRecording: Boolean = false,
    val isSpeaking: Boolean = false,
    /** Прогресс автоскачивания Piper-модели озвучки, % (issue #50; null — нет). */
    val ttsDownloadProgress: Int? = null,
    /** Автоскачивание модели озвучки завершилось ошибкой (issue #50). */
    val ttsDownloadFailed: Boolean = false,
    val error: String? = null,
    val hasTranslationModel: Boolean = false,
    /** Карточки каталога для недостающего направления перевода (issue #33). */
    val missingModels: List<ModelCard> = emptyList(),
    /**
     * Карточки каталога для недостающей STT-модели исходного языка
     * (issue #56); отдельно от [missingModels], чтобы не ломать гейт
     * [hasTranslationModel].
     */
    val missingSttModels: List<ModelCard> = emptyList(),
    /** Прогресс скачивания по id модели (для карточки недостающих моделей). */
    val downloadingProgress: Map<String, Int> = emptyMap(),
    val hasSttModel: Boolean = false,
    /** Показывать одноразовый онбординг первого запуска (issue #33). */
    val showOnboarding: Boolean = false
) {
    /**
     * Результат для показа и озвучивания (issue #59): непустой только когда
     * привязан к текущему направлению; при несовпадении направления — пусто
     * (карточка результата скрывается, «Озвучить» чужой текст не берёт).
     */
    val visibleResult: String
        get() = if (
            resultText.isNotEmpty() &&
            resultSourceLang == sourceLang &&
            resultTargetLang == targetLang
        ) {
            resultText
        } else {
            ""
        }
}

/**
 * ViewModel экрана «Перевод»: конвейер текст/аудио → STT → LLM → TTS.
 *
 * TODO(объём, ревью #25): покрывает несколько несвязанных сценариев
 * (направление, STT-запись, TTS-плейбек, избранное, восстановление состояния)
 * и выросла до ~1100 строк; декомпозиция сознательно отложена — общий каркас
 * устойчивых подписок и guard'ов вынесен в observeResilient/launchGuarded.
 */
@HiltViewModel
class TranslateViewModel @Inject constructor(
    private val modelManager: ModelManager,
    private val runtimeRouter: RuntimeRouter,
    private val ttsRuntime: TtsRuntime,
    private val ttsCoordinator: TtsPlaybackCoordinator,
    private val audioRecorder: AudioRecorder,
    private val preferences: ModelPreferences,
    private val historyRepository: TranslationHistoryRepository,
    private val pendingHolder: PendingTranslationHolder,
    private val favoritesRepository: FavoritesRepository,
    // Контекст — человекочитаемые тексты ошибок из ресурсов (issue #57).
    @dagger.hilt.android.qualifiers.ApplicationContext
    private val appContext: Context,
    // Тексты ввода/результата и привязка результата к направлению переживают
    // гибель процесса (восстановление экрана «Перевод»); чипы направления —
    // как и раньше, из настроек (issue #33, issue #81).
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        /** Ключ восстанавливаемого текста ввода в SavedStateHandle. */
        internal const val KEY_INPUT_TEXT = "translate_input_text"

        /** Ключ восстанавливаемого текста результата в SavedStateHandle. */
        internal const val KEY_RESULT_TEXT = "translate_result_text"

        /** Ключи восстанавливаемого направления результата (issue #81). */
        internal const val KEY_RESULT_SOURCE_LANG = "translate_result_source_lang"

        internal const val KEY_RESULT_TARGET_LANG = "translate_result_target_lang"

        /**
         * Потолок сохраняемого текста: бюджет SavedStateHandle/safeBundle на
         * Binder-транзакцию мал (~50 КБ лимит SAF-документов), рабочие переводы
         * заведомо короче — обрезка только защита от аномально длинного ввода.
         */
        internal const val MAX_RESTORED_TEXT_CHARS = 10_000

        /**
         * Снятое из SavedStateHandle направление результата (issue #81):
         * код языка → [TranslateLanguage]; при отсутствии/неизвестном коде —
         * null (дальше работает прежний фолбэк на направление по умолчанию).
         */
        private fun savedResultLang(
            savedStateHandle: SavedStateHandle,
            key: String
        ): TranslateLanguage? {
            val code = savedStateHandle.get<String>(key) ?: return null
            return TranslateLanguage.entries.firstOrNull { it.code == code }
        }
    }

    private val _uiState = MutableStateFlow(
        TranslateUiState(
            inputText = savedStateHandle[KEY_INPUT_TEXT] ?: "",
            resultText = savedStateHandle[KEY_RESULT_TEXT] ?: "",
            // Восстановленный результат привязывается к снятому направлению
            // перевода (issue #81): заголовок и метки карточки соответствуют
            // снимку; при несовпадении с направлением из настроек гейт
            // visibleResult скрывает результат (семантика issue #59). Если
            // направление не снято (старый снапшот), фолбэк — прежнее
            // направление по умолчанию (ru→en).
            resultSourceLang = savedResultLang(savedStateHandle, KEY_RESULT_SOURCE_LANG)
                ?: TranslateLanguage.RU,
            resultTargetLang = savedResultLang(savedStateHandle, KEY_RESULT_TARGET_LANG)
                ?: TranslateLanguage.EN,
            // Снимок ввода для пары избранного: восстановленный ввод и есть
            // снимок на момент перевода (latest-пись); при правке поля после
            // перевода звезда просто не подсветится — ключ не совпадёт.
            resultInputText = savedStateHandle[KEY_INPUT_TEXT] ?: ""
        )
    )
    val uiState: StateFlow<TranslateUiState> = _uiState

    // Истёкшая длительность записи, мс (таймер в индикаторе, issue #68):
    // отдельный поток (ревью) — секундные тики не гонят перерисовку всего
    // UiState, UI забирает его точечно в RecordingIndicator.
    private val _recordingElapsed = MutableStateFlow(0L)
    val recordingElapsed: StateFlow<Long> = _recordingElapsed

    // Установленные модели перевода для автовыбора по направлению.
    private var translationModels: List<InstalledModel> = emptyList()

    // Каталог моделей для подбора недостающих звеньев цепочки (issue #33).
    private var catalogCards: List<ModelCard> = emptyList()

    // Установленные STT-модели и назначения «язык → модель» (issue #28).
    private var sttModels: List<InstalledModel> = emptyList()
    private var sttAssignments: Map<String, String> = emptyMap()

    // Ключи избранного «направление|исходный текст» для звёздочки результата
    // (issue #38).
    // TODO(review): хранить полный ключ избранного («направление» + полные
    //  тексты ввода и перевода) вместо усечённых текстов в паре ключа.
    private var favoriteKeys: Set<String> = emptySet()

    // Направление применённой записи истории, «защищённое» от отката
    // эмиссией настроек (ревью #7); снимается завершением saveLastDirection
    // или совпадающей эмиссией — см. коллектор настроек в init и
    // useHistoryEntry. Обращения только из viewModelScope (главный поток).
    private var pendingHistoryDirection: Pair<TranslateLanguage, TranslateLanguage>? = null

    // Счётчик generation для saveLastDirection: поздняя отложенная запись
    // настроек не снимает pending более позднего изменения направления.
    private var directionSaveGeneration = 0L

    // Анти-повтор sticky-состояний синглтон-координатора (ревью #55, правка #8):
    // якоря показанных id хранятся в самом TtsPlaybackCoordinator (monotonic id),
    // а не в базовой линии ViewModel: ошибка/сбой скачивания, слученный, когда
    // экран был на другой вкладке, показывается новому ViewModel, а уже показанный
    // экземпляр не воспроизводится при пересоздании ViewModel (возврат на вкладку).

    // Ресурс сообщения сбоя наблюдения Room-потоков (issue #67): observeResilient
    // пишет сюда id текста, коллектор ниже переводит его в человекочитаемую
    // ошибку uiState.error — семантика поля error не меняется. Трекер множества
    // ошибок (ревью): баннер живёт, пока хоть один источник observation мёртв.
    private val observeErrors = ObserveErrorTracker()

    init {
        // Мост: id текста сбоя наблюдения → ошибка UI (без изменения семантики).
        viewModelScope.launch {
            observeErrors.state.collect { res ->
                if (res != null) {
                    update { it.copy(error = appContext.getString(res)) }
                }
            }
        }
        // issue #67: подписки на Room-потоки (каталог, установленные модели,
        // избранное) наблюдаются через observeResilient — сбой Room ронял
        // процесс; observeResilient логирует, показывает ошибку и перезапускает
        // подписку (обоснование выбора — в его KDoc).
        observeResilient(
            scope = viewModelScope,
            source = { modelManager.installedModels(ModelCategory.TRANSLATION) },
            messageRes = R.string.models_observe_failed,
            errors = observeErrors,
            marker = "installed_translations",
        ) { models ->
            translationModels = models
            refreshTranslationAvailability()
        }
        observeResilient(
            scope = viewModelScope,
            source = { modelManager.catalog },
            messageRes = R.string.models_observe_failed,
            errors = observeErrors,
            marker = "catalog",
        ) { cards ->
            catalogCards = cards
            refreshTranslationAvailability()
        }
        observeResilient(
            scope = viewModelScope,
            source = { modelManager.installedModels(ModelCategory.STT) },
            messageRes = R.string.models_observe_failed,
            errors = observeErrors,
            marker = "installed_stt",
        ) { models ->
            sttModels = models
            // Доступность распознавания — любая готовая STT-модель:
            // глобальной активной больше нет, выбор по языкам (issue #29).
            _uiState.value = _uiState.value.copy(
                hasSttModel = models.any { it.isReady }
            )
            // Пересчёт STT-подсказки памятки при установке/скачивании (issue #56).
            refreshTranslationAvailability()
        }
        observeResilient(
            scope = viewModelScope,
            source = { modelManager.sttByLanguage },
            messageRes = R.string.models_observe_failed,
            errors = observeErrors,
            marker = "stt_by_language",
        ) { assignments ->
            sttAssignments = assignments
            // Назначения «язык → модель» влияют на подбор STT-подсказки (issue #56).
            refreshTranslationAvailability()
        }
        viewModelScope.launch {
            // Сохранённое направление перевода (issue #33):ru→en и т.п.;
            // невалидные значения и AUTO-источник игнорируются — остаются
            // значения по умолчанию.
            combine(
                preferences.lastSourceLang,
                preferences.lastTargetLang
            ) { source, target -> source to target }
                .collect { (source, target) ->
                    val src = source?.let { code ->
                        TranslateLanguage.entries.firstOrNull { it.code == code && it != TranslateLanguage.AUTO }
                    }
                    val tgt = target?.let { code ->
                        TranslateLanguage.entries.firstOrNull { it.code == code }
                    }
                    if (src != null && tgt != null) {
                        // Гонка направления (ревью): пока активно pending-
                        // направление применённой записи истории, эмиссии
                        // настроек не откатывают его (мерцание карточки
                        // результата). Флаг снимается по завершении сохране-
                        // ния направления (saveLastDirection) или при эмиссии,
                        // совпавшей с pending (настройки догнали экран).
                        val pending = pendingHistoryDirection
                        if (pending != null) {
                            if (pending.first == src && pending.second == tgt) {
                                pendingHistoryDirection = null
                            }
                        } else {
                            update { it.copy(sourceLang = src, targetLang = tgt) }
                            refreshTranslationAvailability()
                        }
                    }
                }
        }
        viewModelScope.launch {
            // Флаг онбординга (issue #33): sheet показывается только пока флаг
            // не установлен; значение по умолчанию false в состоянии — при уже
            // пройденном онбординге sheet не мелькает до прилета значения из DataStore.
            preferences.onboardingDone.collect { done ->
                update { it.copy(showOnboarding = !done) }
            }
        }
        viewModelScope.launch {
            pendingHolder.pending.collect { entry ->
                if (entry != null) {
                    useHistoryEntry(entry)
                    // Атомарная очистка обработанного значения (compareAndSet):
                    // запрос, поставленный во время useHistoryEntry, не теряется.
                    pendingHolder.consume(entry)
                }
            }
        }
        // Избранное (issue #38): актуальный набор ключей для звёздочки
        // результата; пересчёт флага при любых изменениях избранного.
        // Room-источник — устойчивая подписка (issue #67).
        observeResilient(
            scope = viewModelScope,
            source = { favoritesRepository.observeAll() },
            messageRes = R.string.favorites_observe_failed,
            errors = observeErrors,
            marker = "favorites_observed",
        ) { favorites ->
            favoriteKeys = favorites.map {
                FavoriteEntry.key(it.sourceLang, it.targetLang, it.inputText)
            }.toSet()
            refreshResultFavorite()
        }
        viewModelScope.launch {
            // Состояние записи из рекордера — потоком (ревью раунд 2): внешний
            // AudioRecorder.cancel() (разгрузка рантаймов при ON_STOP) гасит
            // индикатор «идёт запись», не давая ему залипться в UI экрана.
            // Эмит начального состояния переживает смену вкладки (ревью #9):
            // ViewModel пересоздан при живой записи — коллектор поднимает
            // индикатор и перезапускает потолок длительности с учётом уже
            // отсчитанных рекордером секунд.
            audioRecorder.recordingFlow.collect { recording ->
                if (recording) {
                    scheduleRecordingLimit()
                    update { it.copy(isRecording = true) }
                } else {
                    // Запись снята извне — авто-остановка по потолку не нужна.
                    recordingLimitJob?.cancel()
                    recordingLimitJob = null
                    val wasRecording = _uiState.value.isRecording
                    update { it.copy(isRecording = false) }
                    // Таймер обнуляется только на реальном переходе
                    // true→false (ревью #13): повторная эмиссия «false»
                    // не стирает восстановленный из рекордера elapsed.
                    if (wasRecording) _recordingElapsed.value = 0L
                }
            }
        }
        viewModelScope.launch {
            // Таймер длительности записи для индикатора (issue #68): в
            // отдельный поток, не через общий UiState (ревью).
            audioRecorder.recordingElapsedMs.collect { elapsedMs ->
                _recordingElapsed.value = elapsedMs
            }
        }
        viewModelScope.launch {
            // Инициализация TTS текущим движком и переконфигурация при смене настройки.
            ttsRuntime.trackEnginePreference(preferences.ttsEngine)
        }
        viewModelScope.launch {
            // Статус общего TTS-плейбека (issue #50): флаг озвучивания,
            // автоскачивание Piper-модели и ошибки, не покрытые фолбэком.
            // Анти-повтор sticky-значений (ревью #55, правка #8): экземпляр
            // ошибки/ERROR-стадии (monotonic id в статусе) показывается ровно
            // один раз — якорь показанных id живёт в синглтон-координаторе,
            // поэтому ошибка, случившаяся на другой вкладке, не теряется.
            ttsCoordinator.status.collect { s ->
                val newTtsError = s.error != null && !ttsCoordinator.isTtsErrorShown(s.errorId)
                if (newTtsError) ttsCoordinator.markTtsErrorShown(s.errorId)
                val newDownloadError =
                    s.downloadStatus == TtsDownloadStatus.ERROR &&
                        !ttsCoordinator.isTtsDownloadErrorShown(s.downloadErrorId)
                if (newDownloadError) ttsCoordinator.markTtsDownloadErrorShown(s.downloadErrorId)
                update {
                    it.copy(
                        isSpeaking = s.isSpeaking,
                        ttsDownloadProgress =
                            if (s.downloadStatus == TtsDownloadStatus.DOWNLOADING) s.downloadProgress else null,
                        ttsDownloadFailed = s.downloadStatus == TtsDownloadStatus.ERROR &&
                            (newDownloadError || it.ttsDownloadFailed),
                        error = if (newTtsError) s.error?.userMessage() else it.error
                    )
                }
            }
        }
    }

    fun onSourceLangChange(lang: TranslateLanguage) {
        update { it.copy(sourceLang = lang, error = null) }
        saveLastDirection()
        refreshTranslationAvailability()
        refreshResultFavorite()
    }

    fun onTargetLangChange(lang: TranslateLanguage) {
        update { it.copy(targetLang = lang, error = null) }
        saveLastDirection()
        refreshTranslationAvailability()
        refreshResultFavorite()
    }

    /**
     * Обменять направления перевода местами (кнопка-стрелка, issue #60):
     * sourceLang/targetLang меняются значениями; если под текущим направлением
     * есть видимый результат и он отличается от поля ввода, он подставляется
     * в [TranslateUiState.inputText] для обратного перевода. Сам результат
     * при этом скрывается — остаётся привязан к прежнему направлению
     * (issue #59, см. [TranslateUiState.visibleResult]).
     * Обмен делегирован [onSourceLangChange]/[onTargetLangChange], чтобы
     * побочные эффекты смены языка не разносились с выбором чипами.
     */
    fun swapDirections() {
        val state = _uiState.value
        // Подставляем только реально показанный результат текущего направления.
        if (state.visibleResult.isNotEmpty() && state.visibleResult != state.inputText) {
            onInputTextChanged(state.visibleResult)
        }
        onSourceLangChange(state.targetLang)
        onTargetLangChange(state.sourceLang)
    }

    /**
     * Пересчитать флаг избранности текущего результата (issue #38).
     * Ключ строится по текущему явному направлению и снапшоту ввода
     * [TranslateUiState.resultInputText] — правка поля ввода не гасит
     * звезду уже показанной пары. Для AUTO-источника фактический язык
     * результата неизвестен UI — звезда неактивна (false).
     */
    private fun refreshResultFavorite() {
        val st = _uiState.value
        val favorite = st.sourceLang != TranslateLanguage.AUTO &&
            st.resultText.isNotEmpty() &&
            favoriteKeys.contains(
                FavoriteEntry.key(st.sourceLang.code, st.targetLang.code, st.resultInputText)
            )
        if (_uiState.value.resultFavorite != favorite) {
            update { it.copy(resultFavorite = favorite) }
        }
    }

    /**
     * Переключить избрание текущего перевода (звезда в карточке результата).
     * Пара строится по снапшоту ввода результата ([TranslateUiState.resultInputText]),
     * не по текущему полю. Для AUTO-источника недоступно: направление
     * результата не определено.
     */
    fun toggleResultFavorite() {
        val st = _uiState.value
        if (st.sourceLang == TranslateLanguage.AUTO || st.resultText.isBlank()) return
        // Сбой Room: процесс не роняем, лог + sticky-ошибка в UI (ревью #65);
        // общий каркас guard + мост id ресурса в uiState.error (ревью #23).
        launchGuarded(
            scope = viewModelScope,
            messageRes = R.string.favorites_update_failed,
            errors = observeErrors,
            logMessage = "Не удалось переключить избранность перевода",
            marker = "toggle_result_favorite"
        ) {
            val added = favoritesRepository.toggle(
                st.sourceLang.code, st.targetLang.code, st.resultInputText, st.resultText
            )
            update { it.copy(resultFavorite = added) }
        }
    }

    /**
     * Сохранить текущее направление перевода в настройки (issue #33).
     * AUTO-источник не сохраняется — сохранённое направление остаётся прежним.
     * Побочная запись (направление записи истории тоже попадает в настройки
     * через [useHistoryEntry]) — намеренная: настройки обязаны сойтись с
     * экраном, иначе следующая эмиссия откатит применённое направление (ревью).
     */
    private fun saveLastDirection() {
        val state = _uiState.value
        if (state.sourceLang == TranslateLanguage.AUTO) return
        val saved = state.sourceLang to state.targetLang
        // Generation-токен: отложенная запись снимает pending-направление
        // только если это последний сохранённый запрос (более поздняя смена
        // направления перевешивает более раннюю). Все обращения — из
        // viewModelScope (главный поток), атомарности не требуется.
        val generation = ++directionSaveGeneration
        viewModelScope.launch {
            preferences.setLastDirection(saved.first.code, saved.second.code)
            if (generation == directionSaveGeneration &&
                pendingHistoryDirection == saved
            ) {
                pendingHistoryDirection = null
            }
        }
    }

    /**
     * Выбрать направление из онбординга (issue #33): применить языки через
     * обычные обработчики смены (сохраняют направление в настройки и
     * пересчитывают подсказку недостающих моделей) и закрыть sheet.
     */
    fun onOnboardingOption(index: Int) {
        val option = OnboardingOption.byIndex(index) ?: return
        onSourceLangChange(option.sourceLang)
        onTargetLangChange(option.targetLang)
        markOnboardingDone()
    }

    /** Пропустить онбординг (кнопка «Пропустить» или закрытие sheet). */
    fun onOnboardingSkip() {
        markOnboardingDone()
    }

    private fun markOnboardingDone() {
        update { it.copy(showOnboarding = false) }
        viewModelScope.launch {
            preferences.setOnboardingDone()
        }
    }

    fun onInputTextChanged(text: String) {
        update { it.copy(inputText = text, error = null) }
        persistRestorableTexts()
        refreshResultFavorite()
    }

    /**
     * Сохранить актуальные тексты ввода и результата в SavedStateHandle
     * (latest без debounce — значения небольшие) для восстановления после
     * гибели процесса; сверх [MAX_RESTORED_TEXT_CHARS] обрезается, полный
     * текст перевода и так остаётся в истории.
     * [resolvedSourceLang] — фактический разрешённый язык источника AUTO-перевода:
     * в снимок направления пишется он, а не raw AUTO (ревью #2).
     */
    private fun persistRestorableTexts(resolvedSourceLang: TranslateLanguage? = null) {
        val state = _uiState.value
        savedStateHandle[KEY_INPUT_TEXT] = state.inputText.take(MAX_RESTORED_TEXT_CHARS)
        savedStateHandle[KEY_RESULT_TEXT] = state.resultText.take(MAX_RESTORED_TEXT_CHARS)
        // Направление результата сохраняется вместе с текстами (issue #81):
        // без него восстановленный снимок ложно привязывается к направлению
        // по умолчанию и несогласованная пара проходит гейт visibleResult.
        savedStateHandle[KEY_RESULT_SOURCE_LANG] =
            (resolvedSourceLang ?: state.resultSourceLang)?.code
        savedStateHandle[KEY_RESULT_TARGET_LANG] = state.resultTargetLang?.code
    }

    // Job текущей длительной операции (перевод ИЛИ распознавание) —
    // отменяется кнопкой «Отмена»; обнуляется по завершении в finally.
    private var translationJob: Job? = null

    /**
     * Отменить текущую операцию (перевод либо распознавание): долгий инференс
     * прерывается на ближайшей проверке отмены (greedyDecode / границы STT),
     * загрузка модели — на границах ensureLoaded; состояние возвращается
     * в idle без ошибки.
     */
    fun cancelTranslation() {
        translationJob?.cancel()
    }

    /**
     * Завершение перевода/распознавания с ошибкой: сброс рабочих флагов
     * [translationJob] и текст ошибки в UI.
     */
    private fun endTranslationJobWithError(error: String) {
        update {
            it.copy(
                isTranslating = false,
                canCancelTranslation = false,
                error = error
            )
        }
    }

    /**
     * Перевести введённый текст.
     * Модель выбирается автоматически по направлению (sourceLang→targetLang);
     * при отсутствии прямой модели используется цепочка через английский.
     */
    fun translate() {
        val state = _uiState.value
        if (state.inputText.isBlank() || state.isTranslating) return

        // Определяем исходный язык (авто или явный).
        val sourceLang = resolveSourceLanguage(state)
        if (sourceLang == null) {
            update { it.copy(error = appContext.getString(R.string.translate_detect_language_failed)) }
            return
        }
        if (sourceLang == state.targetLang) {
            update { it.copy(error = appContext.getString(R.string.translate_same_languages)) }
            return
        }

        translationJob = viewModelScope.launch {
            try {
                update {
                    it.copy(
                        isTranslating = true,
                        canCancelTranslation = true,
                        error = null,
                        resultText = ""
                    )
                }

                // Автовыбор цепочки моделей для направления — только готовые
                // модели (симметрично refreshTranslationAvailability): скачиваемая
                // модель дала бы цепочку с заведомо нерабочим файлом.
                val chain = TranslationModelSelector.selectChain(
                    translationModels.filter { it.isReady }, sourceLang.code, state.targetLang.code
                )
                if (chain.isNullOrEmpty()) {
                    endTranslationJobWithError(
                        appContext.getString(
                            R.string.translate_missing_direction_model,
                            sourceLang.code.uppercase(),
                            state.targetLang.code.uppercase()
                        )
                    )
                    return@launch
                }

                // Последовательно прогоняем текст через каждую модель цепочки.
                // В движок идет копия с гарантированным terminator в конце
                // (issue #78): ввод без пунктуации искажает короткие фразы;
                // в историю ниже сохраняется исходный state.inputText.
                var text = SentenceSplitter.ensureTrailingTerminator(state.inputText)
                var from = sourceLang.code
                var failed: String? = null
                for (model in chain) {
                    val dir = TranslationModelSelector.directionOf(model)
                    if (dir == null) {
                        failed = appContext.getString(
                            R.string.translate_model_direction_unknown,
                            model.displayName
                        )
                        break
                    }
                    // Явная sealed-ошибка для TRANSLATION-записи с типом
                    // рантайма без движка перевода (роутер больше не даёт
                    // молчаливый fallback на Marian, ревью #79).
                    val engine = try {
                        runtimeRouter.translationEngine(model.runtime)
                    } catch (e: RuntimeError.UnsupportedRuntime) {
                        failed = e.userMessage()
                        break
                    }
                    val loadError = engine.ensureLoaded(model)
                    if (loadError != null) {
                        failed = loadError.userMessage()
                        break
                    }
                    translateSegments(engine, text, from, dir.second).fold(
                        onSuccess = { translated ->
                            text = translated
                            from = dir.second
                        },
                        onFailure = { e ->
                            failed = when (e) {
                                is RuntimeError -> e.userMessage()
                                else -> appContext.getString(R.string.translate_failed)
                            }
                        }
                    )
                    if (failed != null) break
                }

                if (failed != null) {
                    endTranslationJobWithError(failed)
                } else {
                    update {
                        // Привязка результата к направлению перевода на момент
                        // старта (issue #59): смена направления при живом ViewModel
                        // (возврат на вкладку) не оставляет чужой результат под
                        // новыми языками.
                        it.copy(
                            isTranslating = false,
                            canCancelTranslation = false,
                            resultText = text,
                            resultSourceLang = state.sourceLang,
                            resultTargetLang = state.targetLang,
                            // Снапшот ввода на момент перевода (пара избранного
                            // не рассогласовывается с правкой поля).
                            resultInputText = state.inputText
                        )
                    }
                    // В снимок направления пишем РАЗРЕШЁННЫЙ язык источника
                    // (ревью #2): для AUTO-перевода сохраняется фактический
                    // определённый язык (prefs AUTO не хранят, и восстановленный
                    // «auto» не совпал бы ни с каким направлением из настроек —
                    // гейт visibleResult прятал бы результат после гибели
                    // процесса). Живому ViewModel остаётся raw направление
                    // чипов — гейт issue #59 для AUTO-сессии работает как раньше.
                    persistRestorableTexts(resolvedSourceLang = sourceLang)
                    refreshResultFavorite()
                    // Сбой сохранения в историю (Room) не должен терять уже
                    // успешный перевод и ронять приложение: ошибка только
                    // логируется и показывается в UI (ревью #65).
                    try {
                        historyRepository.add(
                            sourceLang = sourceLang.code,
                            targetLang = state.targetLang.code,
                            inputText = state.inputText,
                            resultText = text
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Timber.e(e, "Не удалось сохранить перевод в историю")
                        update {
                            it.copy(error = appContext.getString(R.string.history_add_failed))
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Отмена пользователем (кнопка «Отмена») — не ошибка:
                // состояние возвращается в idle без показа ошибки.
                Timber.d("Перевод отменён пользователем")
                update { it.copy(isTranslating = false, canCancelTranslation = false) }
            } catch (e: Exception) {
                // Неотмеченное исключение движка/селектора иначе роняет процесс
                // (ревью #5): лог + ошибка в UI, рабочие флаги сбрасывает
                // endTranslationJobWithError.
                Timber.e(e, "Непредвиденная ошибка перевода")
                endTranslationJobWithError(appContext.getString(R.string.translate_failed))
            } finally {
                translationJob = null
            }
        }
    }

    /**
     * Распознать записанный аудио-файл (WAV) STT-моделью, подобранной по языку.
     * Операция идёт через тот же [translationJob], что и перевод: кнопка
     * «Отмена» активна и во время распознавания; отмена — тихий возврат
     * в idle без ошибки.
     */
    fun transcribeAudio(wavPath: String) {
        val state = _uiState.value
        // Симметрично guard в translate(): пока идёт перевод или распознавание,
        // не перезаписываем translationJob — иначе быстрый тап «Перевести» в окне
        // stopRecording() порождает гонку двух задач на один Job.
        if (state.isTranslating) {
            // Распознавание не начинаем — WAV этой записи уже не нужен
            // (удаление файла — на IO, ревью #12).
            viewModelScope.launch {
                withContext(Dispatchers.IO) { File(wavPath).delete() }
            }
            return
        }
        translationJob = viewModelScope.launch {
            try {
                update {
                    it.copy(
                        isTranslating = true,
                        canCancelTranslation = true,
                        error = null
                    )
                }

                // Выбор STT-модели и подсказка языка для STT (issues #28, #29):
                // для явно выбранного исходного языка — его код (назначенная
                // языку модель с фолбэком IndicConformer для ne; whisper
                // определит язык сам), для AUTO — null (мультаязычная модель
                // в приоритете либо любая установленная).
                val languageHint = if (state.sourceLang != TranslateLanguage.AUTO) {
                    state.sourceLang.code
                } else {
                    null
                }
                val model = SttModelSelector.select(
                    language = languageHint,
                    assignments = sttAssignments,
                    installed = sttModels
                )
                if (model == null) {
                    endTranslationJobWithError(
                        appContext.getString(R.string.translate_stt_model_missing)
                    )
                    return@launch
                }

                val engine = runtimeRouter.sttEngine()
                val loadError = engine.ensureLoaded(model)
                if (loadError != null) {
                    endTranslationJobWithError(loadError.userMessage())
                    return@launch
                }

                engine.transcribe(wavPath, language = languageHint).fold(
                    onSuccess = { text ->
                        update {
                            it.copy(
                                isTranslating = false,
                                canCancelTranslation = false,
                                inputText = text
                            )
                        }
                    },
                    onFailure = { e ->
                        endTranslationJobWithError(
                            (e as? RuntimeError)?.userMessage()
                                ?: appContext.getString(R.string.translate_stt_failed)
                        )
                    }
                )
            } catch (e: CancellationException) {
                // Отмена пользователем (кнопка «Отмена») — тихий возврат в idle.
                Timber.d("Распознавание отменено пользователем")
                update { it.copy(isTranslating = false, canCancelTranslation = false) }
            } catch (e: Exception) {
                // Неотмеченное исключение движка иначе роняет процесс (ревью #5).
                Timber.e(e, "Непредвиденная ошибка распознавания")
                endTranslationJobWithError(
                    (e as? RuntimeError)?.userMessage()
                        ?: appContext.getString(R.string.translate_stt_failed)
                )
            } finally {
                translationJob = null
                // WAV нужен движку только на время transcribe() — удаляем сразу
                // после её завершения (успех, ошибка или отмена), иначе
                // recording_*.wav накапливаются в каталоге кэша. Файловая
                // операция — на IO, в NonCancellable чтобы удаление состоялось
                // и на пути отмены (ревью #12).
                withContext(NonCancellable + Dispatchers.IO) {
                    if (!File(wavPath).delete()) {
                        Timber.w("Не удалось удалить WAV после распознавания: $wavPath")
                    }
                }
            }
        }
    }

    /**
     * Озвучить результат через общий координатор TTS-плейбека (issue #50):
     * выбор движка (назначенная Piper-модель / системный TTS) и фолбэк с
     * автоскачиванием — в [TtsPlaybackCoordinator]; состояние isSpeaking и
     * прогресс приходят из статуса координатора.
     */
    fun speakResult() {
        val state = _uiState.value
        // Озвучиваем только результат, привязанный к текущему направлению
        // (issue #59): язык озвучки (targetLang) всегда относится к произносимому
        // тексту, чужой язык после возврата на вкладку невозможен.
        val text = state.visibleResult
        if (text.isBlank()) return
        update { it.copy(error = null) }
        ttsCoordinator.speak(text, state.targetLang.code)
    }

    /** Остановить озвучивание (координатор гасит оба рантайма). */
    fun stopSpeaking() {
        ttsCoordinator.stop()
    }

    /**
     * Начать запись с микрофона.
     * @return true при успешном старте.
     */
    fun startRecording(): Boolean {
        if (!audioRecorder.startRecording()) {
            update { it.copy(error = appContext.getString(R.string.translate_record_start_failed)) }
            return false
        }
        update { it.copy(isRecording = true, error = null) }
        _recordingElapsed.value = 0L
        // Потолок записи ставится коллектором recordingFlow (ревью #9) —
        // он же восстанавливает таймер при пересоздании ViewModel на живой
        // записи (уход на другой таб не оставляет микрофон без потолка).
        return true
    }

    /**
     * Признак идущей остановки записи: повторный тап по микрофону в окне
     * stopRecording() (uiState.isRecording ещё true, audioRecorder.isRecording
     * уже false) игнорируется — иначе второй stopRecording() вернёт null и
     * покажет ложную «Запись пуста или не удалась» (issue #40). Флаг трогается
     * только из viewModelScope (главный поток), атомарности не требуется.
     */
    private var stopRecordingInProgress = false

    /**
     * Таймер авто-остановки записи по потолку [AudioRecorder.MAX_RECORDING_MS]
     * (issue #68); снимается при остановке/снятии записи.
     */
    private var recordingLimitJob: Job? = null

    /**
     * Поставить авто-остановку по потолку длительности (issue #68, ревью #9):
     * отсчёт от фактической длительности рекордера — при пересоздании ViewModel
     * на живой записи (возврат на вкладку) оставшиеся секунды не теряются.
     */
    private fun scheduleRecordingLimit() {
        recordingLimitJob?.cancel()
        val remainingMs =
            AudioRecorder.MAX_RECORDING_MS - audioRecorder.recordingElapsedMs.value
        recordingLimitJob = viewModelScope.launch {
            delay(remainingMs.coerceAtLeast(0L))
            if (audioRecorder.isRecording && !stopRecordingInProgress) {
                Timber.d("Достигнут потолок длительности записи — авто-остановка")
                stopRecordingAndTranscribe()
            }
        }
    }

    /**
     * Остановить запись и сразу распознать речь.
     */
    fun stopRecordingAndTranscribe() {
        if (stopRecordingInProgress) return
        stopRecordingInProgress = true
        recordingLimitJob?.cancel()
        recordingLimitJob = null
        viewModelScope.launch {
            try {
                val wavPath = audioRecorder.stopRecording()
                update { it.copy(isRecording = false) }
                _recordingElapsed.value = 0L
                if (wavPath == null) {
                    update { it.copy(error = appContext.getString(R.string.translate_record_empty)) }
                    return@launch
                }
                transcribeAudio(wavPath)
            } finally {
                stopRecordingInProgress = false
            }
        }
    }

    /**
     * Ошибка отказа в разрешении на запись с микрофона (issue #57: текст
     * отделён от сообщения про отсутствующую STT-модель и вынесен в ресурсы).
     */
    fun onPermissionDenied() {
        update {
            it.copy(error = appContext.getString(R.string.translate_mic_permission_denied))
        }
    }

    /**
     * Ошибка тапа по микрофону без установленной STT-модели распознавания
     * (issue #57 — отдельное сообщение с подсказкой скачать модель).
     */
    fun onSttModelMissing() {
        update {
            it.copy(error = appContext.getString(R.string.translate_mic_missing_stt_model))
        }
    }

    fun clearError() {
        update { it.copy(error = null) }
        // Сброс и множества сбоев наблюдения (ревью #6, re-review): трекер
        // очищается целиком — без этого следующий сбой источника с тем же
        // текстом не показался бы в UI, а восстановление другого источника
        // вновь подняло бы уже скрытое сообщение.
        observeErrors.dismissAll()
        // Синхронно снимаем ошибку и в координаторе, чтобы следующий
        // эмит статуса не воспроизвёл диалог (issue #50).
        ttsCoordinator.clearError()
    }

    /**
     * Очистить поля ввода и результата (кнопка «Очистить» в поле ввода).
     */
    fun clearInput() {
        update { it.copy(inputText = "", resultText = "", error = null) }
        // Пустые поля тоже должны пережить гибель процесса — иначе после
        // «Очистить» и рестарта старые тексты воскреснут из SavedStateHandle.
        persistRestorableTexts()
        refreshResultFavorite()
    }

    /**
     * Применить выбранную в истории запись к полям экрана.
     */
    private fun useHistoryEntry(entry: TranslationHistoryEntry) {
        // Фактически применяемое направление записи (при неизвестном коде языка
        // в записи — прежнее направление экрана).
        val state = _uiState.value
        val source = TranslateLanguage.entries.firstOrNull { it.code == entry.sourceLang }
            ?: state.sourceLang
        val target = TranslateLanguage.entries.firstOrNull { it.code == entry.targetLang }
            ?: state.targetLang
        update {
            it.copy(
                sourceLang = source,
                targetLang = target,
                inputText = entry.inputText,
                resultText = entry.resultText,
                // Привязка результата ровно к применённому направлению самой
                // записи (issue #59): если направление затем применится другое
                // (конкурс с сохранённым направлением из настроек), результат
                // под ним не показывается.
                resultSourceLang = source,
                resultTargetLang = target,
                // Снапшот ввода записи для пары избранного (не заимствуются
                // правки текущего поля).
                resultInputText = entry.inputText,
                error = null
            )
        }
        persistRestorableTexts()
        refreshResultFavorite()
        // Гонка направления (ревью #1, критично; ревью повторно — гонка не
        // до конца закрыта): применённое направление записи пишется в настройки
        // (побочная запись намеренная — настройки должны сойтись с экраном),
        // но асинхронный коллектор lastSourceLang/lastTargetLang мог прийти с
        // ПРЕЖНИМ сохранённым направлением после применения и откатить его
        // (мерцание карточки результата). Pending-маркер: коллектор не
        // применяет эмиссии настроек, пока активно направление записи;
        // снимается после завершения saveLastDirection (настройки дописаны)
        // или при эмиссии, совпавшей с pending (см. коллектор в init).
        if (source != TranslateLanguage.AUTO && target != TranslateLanguage.AUTO) {
            pendingHistoryDirection = source to target
        }
        saveLastDirection()
    }

    /**
     * Перевести текст движком по предложениям: tiny-модели Marian теряют хвост
     * на длинном многосоставном входе (ранний EOS после первой фразы), поэтому
     * вход разбивается [SentenceSplitter], сегменты переводятся по отдельности
     * и склеиваются разделителями границ исходника: где граница содержала
     * перевод строки — сохраняем «\n», прочие границы — пробел
     * (ревью раунд 9: прежняя склейка пробелом теряла переносы строк ввода).
     *
     * Движок с [TranslationEngine.splitsIntoSentences] = false (IndicTrans)
     * сам чанкует вход по лимиту позиций энкодера — ему передаём весь текст
     * целиком, разбиение на предложения ломало бы чанкинг (issue #63).
     */
    private suspend fun translateSegments(
        engine: TranslationEngine,
        text: String,
        from: String,
        to: String
    ): Result<String> {
        if (!engine.splitsIntoSentences) return engine.translate(text, from, to)
        val (segments, separators) = SentenceSplitter.splitWithSeparators(text)
        if (segments.isEmpty()) return engine.translate(text, from, to)
        if (segments.size == 1) return engine.translate(segments.first(), from, to)

        val translated = StringBuilder()
        for (index in segments.indices) {
            engine.translate(segments[index], from, to).fold(
                onSuccess = { out ->
                    // Разделитель перед сегментом — каким он был в исходнике.
                    if (index > 0) translated.append(separators[index - 1])
                    translated.append(out.trim())
                },
                onFailure = { return Result.failure(it) }
            )
        }
        return Result.success(translated.toString().trim())
    }

    /**
     * Определить исходный язык: автоопределение или явный выбор.
     * @return язык или null, если автоопределение не удалось.
     */
    private fun resolveSourceLanguage(state: TranslateUiState): TranslateLanguage? {
        if (state.sourceLang != TranslateLanguage.AUTO) {
            return state.sourceLang
        }
        val detected = LanguageDetector.detect(state.inputText)
        return TranslateLanguage.entries.firstOrNull { it.code == detected }
    }

    /**
     * Пересчитать доступность перевода для текущего направления: наличие
     * готовой цепочки моделей и список недостающих карточек каталога (issue #33),
     * а также недостающую STT-карточку исходного языка (issue #56).
     * Для AUTO-источника перевод рассматривается по всем ненулевым языкам,
     * кроме целевого, а STT не подсказывается (язык определяет детект).
     */
    private fun refreshTranslationAvailability() {
        val state = _uiState.value
        val ready = translationModels.filter { it.isReady }
        val target = state.targetLang.code
        val sources = if (state.sourceLang != TranslateLanguage.AUTO) {
            listOf(state.sourceLang.code)
        } else {
            TranslateLanguage.entries
                .filter { it != TranslateLanguage.AUTO && it.code != target }
                .map { it.code }
        }.filter { it != target }

        // Совпадение языков: цепочка не строится — считаем доступность по наличию
        // готовых моделей (translate() сам подскажет «Выберите разные языки»).
        val hasChain = if (sources.isEmpty()) {
            ready.isNotEmpty()
        } else {
            sources.any { source ->
                TranslationModelSelector.selectChain(ready, source, target) != null
            }
        }
        val missing = if (hasChain || sources.isEmpty()) {
            emptyList()
        } else {
            val cards = LinkedHashSet<ModelCard>()
            for (source in sources) {
                cards.addAll(
                    TranslationModelSelector.missingChain(catalogCards, translationModels, source, target)
                )
            }
            cards.toList()
        }

        // Недостающая STT-модель исходного языка (issue #56): для явного языка
        // применяем цепочку доступности SttModelSelector (назначение языка →
        // дефолт IndicConformer для ne → поддерживающая язык модель); если
        // готовой STT нет — видимая карточка из STT-секции каталога (скрытые,
        // например whisper, в памятку не добавляются). Для AUTO — не подсказываем.
        val sttLanguage =
            if (state.sourceLang != TranslateLanguage.AUTO) state.sourceLang.code else null
        val missingStt = listOfNotNull(
            SttModelSelector.missingCard(catalogCards, sttAssignments, sttModels, sttLanguage)
        )

        _uiState.value = state.copy(
            hasTranslationModel = hasChain,
            missingModels = missing,
            missingSttModels = missingStt,
            downloadingProgress = (translationModels + sttModels)
                .filter { it.status == ModelStatus.DOWNLOADING }
                .associate { it.id to it.progress }
        )
    }

    /**
     * Начать скачивание конкретной недостающей модели [card] — перевода
     * (issue #33) или STT (issue #56): путь универсален, категория карточки
     * не влияет на [ModelManager.startDownload].
     * Уже скачиваемая модель пропускается.
     */
    fun downloadMissingModel(card: ModelCard) {
        if (_uiState.value.downloadingProgress.containsKey(card.id)) return
        modelManager.startDownload(card)
    }

    private fun update(transform: (TranslateUiState) -> TranslateUiState) {
        _uiState.value = transform(_uiState.value)
    }

    /**
     * Перевод/распознавание привязаны к viewModelScope и отменяются вместе с
     * ViewModel (смена вкладки, закрытие экрана) — осознанный компромисс
     * (ревью #10): длинный инференс не переживает уход с экрана, но и не
     * роняет его поздним результатом. TODO:translation-lifecycle: перенос
     * инференса в app-scope синглтон с реаттачем статуса при пересоздании VM.
     */
    override fun onCleared() {
        if (_uiState.value.isTranslating) {
            Timber.w("ViewModel закрыта во время перевода/распознавания — операция отменена")
        }
        super.onCleared()
    }
}
