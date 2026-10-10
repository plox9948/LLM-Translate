package com.plox.llmtranslate.ui.translate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plox.llmtranslate.data.model.TranslationHistoryRepository
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.ModelManager
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.LanguageDetector
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.PendingTranslationHolder
import com.plox.llmtranslate.domain.model.RuntimeError
import com.plox.llmtranslate.domain.model.SentenceSplitter
import com.plox.llmtranslate.domain.model.TranslationHistoryEntry
import com.plox.llmtranslate.domain.model.TranslationModelSelector
import com.plox.llmtranslate.domain.model.userMessage
import com.plox.llmtranslate.domain.runtime.AudioRecorder
import com.plox.llmtranslate.domain.runtime.RuntimeRouter
import com.plox.llmtranslate.domain.runtime.TranslationEngine
import com.plox.llmtranslate.domain.runtime.TtsRuntime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import timber.log.Timber
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
 * Состояние UI экрана «Перевод».
 */
data class TranslateUiState(
    val sourceLang: TranslateLanguage = TranslateLanguage.RU,
    val targetLang: TranslateLanguage = TranslateLanguage.EN,
    val inputText: String = "",
    val resultText: String = "",
    val isTranslating: Boolean = false,
    val isRecording: Boolean = false,
    val isSpeaking: Boolean = false,
    val error: String? = null,
    val hasTranslationModel: Boolean = false,
    val hasSttModel: Boolean = false
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
    private val pendingHolder: PendingTranslationHolder
) : ViewModel() {

    private val _uiState = MutableStateFlow(TranslateUiState())
    val uiState: StateFlow<TranslateUiState> = _uiState

    // Установленные модели перевода для автовыбора по направлению.
    private var translationModels: List<InstalledModel> = emptyList()

    init {
        viewModelScope.launch {
            modelManager.installedModels(ModelCategory.TRANSLATION)
                .collect { models ->
                    translationModels = models
                    _uiState.value = _uiState.value.copy(hasTranslationModel = models.isNotEmpty())
                }
        }
        viewModelScope.launch {
            modelManager.activeModel(ModelCategory.STT)
                .collect { model ->
                    _uiState.value = _uiState.value.copy(hasSttModel = model != null)
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
    }

    fun onTargetLangChange(lang: TranslateLanguage) {
        update { it.copy(targetLang = lang, error = null) }
    }

    fun onInputTextChanged(text: String) {
        update { it.copy(inputText = text, error = null) }
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

        viewModelScope.launch {
            update { it.copy(isTranslating = true, error = null, resultText = "") }

            // Автовыбор цепочки моделей для направления.
            val chain = TranslationModelSelector.selectChain(
                translationModels, sourceLang.code, state.targetLang.code
            )
            if (chain.isNullOrEmpty()) {
                update {
                    it.copy(
                        isTranslating = false,
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
                update { it.copy(isTranslating = false, error = failed) }
            } else {
                update { it.copy(isTranslating = false, resultText = text) }
                historyRepository.add(
                    sourceLang = sourceLang.code,
                    targetLang = state.targetLang.code,
                    inputText = state.inputText,
                    resultText = text
                )
            }
        }
    }

    /**
     * Распознать записанный аудио-файл (WAV) активной STT-моделью.
     */
    fun transcribeAudio(wavPath: String) {
        val state = _uiState.value
        viewModelScope.launch {
            update { it.copy(isTranslating = true, error = null) }

            val model = modelManager.activeModel(ModelCategory.STT)
                .firstOrNull()
            if (model == null) {
                update {
                    it.copy(
                        isTranslating = false,
                        error = "Нет активной модели распознавания. Скачайте её на экране «Модели»."
                    )
                }
                return@launch
            }

            val engine = runtimeRouter.sttEngine()
            val loadError = engine.ensureLoaded(model)
            if (loadError != null) {
                update { it.copy(isTranslating = false, error = loadError.userMessage()) }
                return@launch
            }

            // Подсказка языка для STT: явный выбор или null (whisper определит сам).
            val langHint = if (state.sourceLang != TranslateLanguage.AUTO) {
                state.sourceLang.code
            } else {
                null
            }

            engine.transcribe(wavPath, language = langHint).fold(
                onSuccess = { text ->
                    update { it.copy(isTranslating = false, inputText = text) }
                },
                onFailure = { e ->
                    update {
                        it.copy(
                            isTranslating = false,
                            error = (e as? RuntimeError)?.userMessage() ?: "Ошибка распознавания"
                        )
                    }
                }
            )
        }
    }

    /** Озвучить результат активной TTS. */
    fun speakResult() {
        val state = _uiState.value
        if (state.resultText.isBlank()) return
        viewModelScope.launch {
            val error = ttsRuntime.speak(state.resultText, state.targetLang.code) {
                update { it.copy(isSpeaking = false) }
            }
            if (error != null) {
                update { it.copy(error = error.userMessage()) }
            } else {
                update { it.copy(isSpeaking = true, error = null) }
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
     * Остановить запись и сразу распознать речь.
     */
    fun stopRecordingAndTranscribe() {
        viewModelScope.launch {
            val wavPath = audioRecorder.stopRecording()
            update { it.copy(isRecording = false) }
            if (wavPath == null) {
                update { it.copy(error = "Запись пуста или не удалась") }
                return@launch
            }
            transcribeAudio(wavPath)
        }
    }

    /** Показывает ошибку при отсутствии разрешения на микрофон или STT-модели. */
    fun onPermissionDenied() {
        update {
            it.copy(
                error = "Нужно разрешение на микрофон и активная модель распознавания"
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

    private fun update(transform: (TranslateUiState) -> TranslateUiState) {
        _uiState.value = transform(_uiState.value)
    }
}
