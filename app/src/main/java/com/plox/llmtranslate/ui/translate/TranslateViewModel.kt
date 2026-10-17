package com.plox.llmtranslate.ui.translate

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
import com.plox.llmtranslate.domain.runtime.TranslationEngine
import com.plox.llmtranslate.domain.runtime.TtsRuntime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Поддерживаемые языки перевода.
 */
enum class TranslateLanguage(val code: String, val title: String) {
    AUTO("auto", "Авто (Auto)"),
    RU("ru", "Русский (Russian)"),
    EN("en", "English (English)"),
    NE("ne", "नेपाली (Nepali)")
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
    /** Текущий перевод добавлен в избранное (звёздочка результата, issue #38). */
    val resultFavorite: Boolean = false,
    val isTranslating: Boolean = false,
    val canCancelTranslation: Boolean = false,
    val isRecording: Boolean = false,
    val isSpeaking: Boolean = false,
    val error: String? = null,
    val hasTranslationModel: Boolean = false,
    /** Карточки каталога для недостающего направления перевода (issue #33). */
    val missingModels: List<ModelCard> = emptyList(),
    /** Прогресс скачивания по id модели (для карточки недостающих моделей). */
    val downloadingProgress: Map<String, Int> = emptyMap(),
    val hasSttModel: Boolean = false,
    /** Показывать одноразовый онбординг первого запуска (issue #33). */
    val showOnboarding: Boolean = false
)

/**
 * ViewModel экрана «Перевод»: конвейер текст/аудио → STT → LLM → TTS.
 */
@HiltViewModel
class TranslateViewModel @Inject constructor(
    private val modelManager: ModelManager,
    private val runtimeRouter: RuntimeRouter,
    private val ttsRuntime: TtsRuntime,
    private val audioRecorder: AudioRecorder,
    private val preferences: ModelPreferences,
    private val historyRepository: TranslationHistoryRepository,
    private val pendingHolder: PendingTranslationHolder,
    private val favoritesRepository: FavoritesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TranslateUiState())
    val uiState: StateFlow<TranslateUiState> = _uiState

    // Установленные модели перевода для автовыбора по направлению.
    private var translationModels: List<InstalledModel> = emptyList()

    // Каталог моделей для подбора недостающих звеньев цепочки (issue #33).
    private var catalogCards: List<ModelCard> = emptyList()

    // Установленные STT-модели и назначения «язык → модель» (issue #28).
    private var sttModels: List<InstalledModel> = emptyList()
    private var sttAssignments: Map<String, String> = emptyMap()

    // Ключи избранного «направление|исходный текст» для звёздочки результата
    // (issue #38).
    private var favoriteKeys: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            modelManager.installedModels(ModelCategory.TRANSLATION)
                .collect { models ->
                    translationModels = models
                    refreshTranslationAvailability()
                }
        }
        viewModelScope.launch {
            modelManager.catalog.collect { cards ->
                catalogCards = cards
                refreshTranslationAvailability()
            }
        }
        viewModelScope.launch {
            modelManager.installedModels(ModelCategory.STT)
                .collect { models ->
                    sttModels = models
                    // Доступность распознавания — любая готовая STT-модель:
                    // глобальной активной больше нет, выбор по языкам (issue #29).
                    _uiState.value = _uiState.value.copy(
                        hasSttModel = models.any { it.isReady }
                    )
                }
        }
        viewModelScope.launch {
            modelManager.sttByLanguage.collect { assignments ->
                sttAssignments = assignments
            }
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
                        update { it.copy(sourceLang = src, targetLang = tgt) }
                        refreshTranslationAvailability()
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
                    pendingHolder.consume()
                }
            }
        }
        viewModelScope.launch {
            // Избранное (issue #38): актуальный набор ключей для звёздочки
            // результата; пересчёт флага при любых изменениях избранного.
            favoritesRepository.observeAll().collect { favorites ->
                favoriteKeys = favorites.map {
                    FavoriteEntry.key(it.sourceLang, it.targetLang, it.inputText)
                }.toSet()
                refreshResultFavorite()
            }
        }
        viewModelScope.launch {
            // Инициализация TTS текущим движком и переконфигурация при смене настройки.
            preferences.ttsEngine.collect { engine ->
                val error = ttsRuntime.init(engine)
                if (error != null) {
                    Timber.w("TTS недоступен: ${error.userMessage()}")
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
     * Пересчитать флаг избранности текущего результата (issue #38).
     * Ключ строится по текущему явному направлению; для AUTO-источника
     * фактический язык результата неизвестен UI — звезда неактивна (false).
     */
    private fun refreshResultFavorite() {
        val st = _uiState.value
        val favorite = st.sourceLang != TranslateLanguage.AUTO &&
            st.resultText.isNotEmpty() &&
            favoriteKeys.contains(
                FavoriteEntry.key(st.sourceLang.code, st.targetLang.code, st.inputText)
            )
        if (_uiState.value.resultFavorite != favorite) {
            update { it.copy(resultFavorite = favorite) }
        }
    }

    /**
     * Переключить избрание текущего перевода (звезда в карточке результата).
     * Для AUTO-источника недоступно: направление результата не определено.
     */
    fun toggleResultFavorite() {
        val st = _uiState.value
        if (st.sourceLang == TranslateLanguage.AUTO || st.resultText.isBlank()) return
        viewModelScope.launch {
            val added = favoritesRepository.toggle(
                st.sourceLang.code, st.targetLang.code, st.inputText, st.resultText
            )
            update { it.copy(resultFavorite = added) }
        }
    }

    /**
     * Сохранить текущее направление перевода в настройки (issue #33).
     * AUTO-источник не сохраняется — сохранённое направление остаётся прежним.
     */
    private fun saveLastDirection() {
        val state = _uiState.value
        if (state.sourceLang == TranslateLanguage.AUTO) return
        viewModelScope.launch {
            preferences.setLastDirection(state.sourceLang.code, state.targetLang.code)
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
        refreshResultFavorite()
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
            update { it.copy(error = "Не удалось определить язык. Выберите его вручную.") }
            return
        }
        if (sourceLang == state.targetLang) {
            update { it.copy(error = "Выберите разные языки") }
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
                    update {
                        it.copy(
                            isTranslating = false,
                            canCancelTranslation = false,
                            error = "Нет модели для направления ${sourceLang.code.uppercase()}→${state.targetLang.code.uppercase()}. Скачайте её на экране «Модели»."
                        )
                    }
                    return@launch
                }

                // Последовательно прогоняем текст через каждую модель цепочки.
                var text = state.inputText
                var from = sourceLang.code
                var failed: String? = null
                for (model in chain) {
                    val dir = TranslationModelSelector.directionOf(model)
                    if (dir == null) {
                        failed = "Не удалось определить направление модели ${model.displayName}"
                        break
                    }
                    val engine = runtimeRouter.translationEngine(model.runtime)
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
                                else -> "Ошибка перевода"
                            }
                        }
                    )
                    if (failed != null) break
                }

                if (failed != null) {
                    update {
                        it.copy(isTranslating = false, canCancelTranslation = false, error = failed)
                    }
                } else {
                    update {
                        it.copy(isTranslating = false, canCancelTranslation = false, resultText = text)
                    }
                    refreshResultFavorite()
                    historyRepository.add(
                        sourceLang = sourceLang.code,
                        targetLang = state.targetLang.code,
                        inputText = state.inputText,
                        resultText = text
                    )
                }
            } catch (e: CancellationException) {
                // Отмена пользователем (кнопка «Отмена») — не ошибка:
                // состояние возвращается в idle без показа ошибки.
                Timber.d("Перевод отменён пользователем")
                update { it.copy(isTranslating = false, canCancelTranslation = false) }
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
            // Распознавание не начинаем — WAV этой записи уже не нужен.
            File(wavPath).delete()
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
                    update {
                        it.copy(
                            isTranslating = false,
                            canCancelTranslation = false,
                            error = "Нет подходящей модели распознавания. Скачайте её на экране «Модели»."
                        )
                    }
                    return@launch
                }

                val engine = runtimeRouter.sttEngine()
                val loadError = engine.ensureLoaded(model)
                if (loadError != null) {
                    update {
                        it.copy(
                            isTranslating = false,
                            canCancelTranslation = false,
                            error = loadError.userMessage()
                        )
                    }
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
                        update {
                            it.copy(
                                isTranslating = false,
                                canCancelTranslation = false,
                                error = (e as? RuntimeError)?.userMessage() ?: "Ошибка распознавания"
                            )
                        }
                    }
                )
            } catch (e: CancellationException) {
                // Отмена пользователем (кнопка «Отмена») — тихий возврат в idle.
                Timber.d("Распознавание отменено пользователем")
                update { it.copy(isTranslating = false, canCancelTranslation = false) }
            } finally {
                translationJob = null
                // WAV нужен движку только на время transcribe() — удаляем сразу
                // после её завершения (успех, ошибка или отмена), иначе каталог
                // «audio» растёт с каждой записью.
                if (!File(wavPath).delete()) {
                    Timber.w("Не удалось удалить WAV после распознавания: $wavPath")
                }
            }
        }
    }

    /** Озвучить результат активной TTS. */
    fun speakResult() {
        val state = _uiState.value
        if (state.resultText.isBlank()) return
        viewModelScope.launch {
            // Флаг «говорим» выставляется ДО speak(): onDone короткой фразы
            // может сработать раньше возврата speak() и иначе оставил бы
            // флаг залипшим. При ошибке — откат.
            update { it.copy(isSpeaking = true, error = null) }
            val error = ttsRuntime.speak(state.resultText, state.targetLang.code) {
                update { it.copy(isSpeaking = false) }
            }
            if (error != null) {
                update { it.copy(isSpeaking = false, error = error.userMessage()) }
            }
        }
    }

    fun stopSpeaking() {
        ttsRuntime.stop()
        update { it.copy(isSpeaking = false) }
    }

    /**
     * Начать запись с микрофона.
     * @return true при успешном старте.
     */
    fun startRecording(): Boolean {
        if (!audioRecorder.startRecording()) {
            update { it.copy(error = "Не удалось запустить запись с микрофона") }
            return false
        }
        update { it.copy(isRecording = true, error = null) }
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
     * Остановить запись и сразу распознать речь.
     */
    fun stopRecordingAndTranscribe() {
        if (stopRecordingInProgress) return
        stopRecordingInProgress = true
        viewModelScope.launch {
            try {
                val wavPath = audioRecorder.stopRecording()
                update { it.copy(isRecording = false) }
                if (wavPath == null) {
                    update { it.copy(error = "Запись пуста или не удалась") }
                    return@launch
                }
                transcribeAudio(wavPath)
            } finally {
                stopRecordingInProgress = false
            }
        }
    }

    /** Показывает ошибку при отсутствии разрешения на микрофон или STT-модели. */
    fun onPermissionDenied() {
        update {
            it.copy(
                error = "Нужно разрешение на микрофон и установленная модель распознавания"
            )
        }
    }

    fun clearError() {
        update { it.copy(error = null) }
    }

    /**
     * Очистить поля ввода и результата (кнопка «Очистить» в поле ввода).
     */
    fun clearInput() {
        update { it.copy(inputText = "", resultText = "", error = null) }
        refreshResultFavorite()
    }

    /**
     * Применить выбранную в истории запись к полям экрана.
     */
    private fun useHistoryEntry(entry: TranslationHistoryEntry) {
        val source = TranslateLanguage.entries.firstOrNull { it.code == entry.sourceLang }
        val target = TranslateLanguage.entries.firstOrNull { it.code == entry.targetLang }
        update {
            it.copy(
                sourceLang = source ?: it.sourceLang,
                targetLang = target ?: it.targetLang,
                inputText = entry.inputText,
                resultText = entry.resultText,
                error = null
            )
        }
        refreshResultFavorite()
    }

    /**
     * Перевести текст движком по предложениям: tiny-модели Marian теряют хвост
     * на длинном многосоставном входе (ранний EOS после первой фразы), поэтому
     * вход разбивается [SentenceSplitter], сегменты переводятся по отдельности
     * и склеиваются через пробел.
     */
    private suspend fun translateSegments(
        engine: TranslationEngine,
        text: String,
        from: String,
        to: String
    ): Result<String> {
        val segments = SentenceSplitter.split(text).ifEmpty { listOf(text) }
        if (segments.size == 1) return engine.translate(segments.first(), from, to)

        val translated = StringBuilder()
        for (segment in segments) {
            engine.translate(segment, from, to).fold(
                onSuccess = { translated.append(it).append(' ') },
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
     * готовой цепочки моделей и список недостающих карточек каталога (issue #33).
     * Для AUTO-источника рассматриваются все ненулевые языки, кроме целевого.
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

        _uiState.value = state.copy(
            hasTranslationModel = hasChain,
            missingModels = missing,
            downloadingProgress = translationModels
                .filter { it.status == ModelStatus.DOWNLOADING }
                .associate { it.id to it.progress }
        )
    }

    /**
     * Начать скачивание конкретной недостающей модели [card]
     * (кнопка в строке карточки подсказки на экране «Перевод», issue #33).
     * Уже скачиваемая модель пропускается.
     */
    fun downloadMissingModel(card: ModelCard) {
        if (_uiState.value.downloadingProgress.containsKey(card.id)) return
        modelManager.startDownload(card)
    }

    private fun update(transform: (TranslateUiState) -> TranslateUiState) {
        _uiState.value = transform(_uiState.value)
    }
}
