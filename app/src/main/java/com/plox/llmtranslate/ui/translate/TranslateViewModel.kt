package com.plox.llmtranslate.ui.translate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.ModelManager
import com.plox.llmtranslate.domain.model.LanguageDetector
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.RuntimeError
import com.plox.llmtranslate.domain.runtime.AudioRecorder
import com.plox.llmtranslate.domain.runtime.RuntimeRouter
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
    AUTO("auto", "Авто"),
    RU("ru", "Русский"),
    EN("en", "English"),
    NE("ne", "नेपाली")
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
    private val preferences: ModelPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(TranslateUiState())
    val uiState: StateFlow<TranslateUiState> = _uiState

    init {
        viewModelScope.launch {
            modelManager.activeModel(ModelCategory.TRANSLATION)
                .collect { model ->
                    _uiState.value = _uiState.value.copy(hasTranslationModel = model != null)
                }
        }
        viewModelScope.launch {
            modelManager.activeModel(ModelCategory.STT)
                .collect { model ->
                    _uiState.value = _uiState.value.copy(hasSttModel = model != null)
                }
        }
        viewModelScope.launch {
            // Инициализация TTS текущим движком и переконфигурация при смене настройки.
            preferences.ttsEngine.collect { engine ->
                val error = ttsRuntime.init(engine)
                if (error != null) {
                    Timber.w("TTS недоступен: ${error.message()}")
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
     * Перевести введённый текст активной LLM-моделью.
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

            val model = modelManager.activeModel(ModelCategory.TRANSLATION)
                .firstOrNull()
            if (model == null) {
                update {
                    it.copy(
                        isTranslating = false,
                        error = "Нет активной модели перевода. Скачайте её на экране «Модели»."
                    )
                }
                return@launch
            }

            val engine = runtimeRouter.translationEngine(model.runtime)
            val loadError = engine.ensureLoaded(model)
            if (loadError != null) {
                update { it.copy(isTranslating = false, error = loadError.message()) }
                return@launch
            }

            engine.translate(
                text = state.inputText,
                from = sourceLang.code,
                to = state.targetLang.code
            ).fold(
                onSuccess = { translation ->
                    update { it.copy(isTranslating = false, resultText = translation) }
                },
                onFailure = { e ->
                    update {
                        it.copy(
                            isTranslating = false,
                            error = when (e) {
                                is RuntimeError -> e.message()
                                else -> "Ошибка перевода"
                            }
                        )
                    }
                }
            )
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

            val engine = runtimeRouter.sttEngine(model.runtime)
            val loadError = engine.ensureLoaded(model)
            if (loadError != null) {
                update { it.copy(isTranslating = false, error = loadError.message()) }
                return@launch
            }

            engine.transcribe(wavPath).fold(
                onSuccess = { text ->
                    update { it.copy(isTranslating = false, inputText = text) }
                },
                onFailure = { e ->
                    update {
                        it.copy(
                            isTranslating = false,
                            error = (e as? RuntimeError)?.message() ?: "Ошибка распознавания"
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
                update { it.copy(error = error.message()) }
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

    private fun RuntimeError.message(): String = when (this) {
        is RuntimeError.LoadFailed -> "Не удалось загрузить модель: $reason"
        is RuntimeError.InferenceFailed -> "Ошибка: $reason"
        RuntimeError.ModelNotSelected -> "Модель не выбрана"
        RuntimeError.ModelNotReady -> "Модель не готова"
    }
}
