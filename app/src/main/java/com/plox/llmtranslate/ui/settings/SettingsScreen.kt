package com.plox.llmtranslate.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.plox.llmtranslate.R
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.BackgroundUnloadScheduler
import com.plox.llmtranslate.domain.ModelManager
import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.model.TtsEngines
import com.plox.llmtranslate.domain.model.visibleTtsEngineIds
import com.plox.llmtranslate.ui.ErrorBanner
import com.plox.llmtranslate.ui.observeResilient
import com.plox.llmtranslate.ui.translate.TranslateLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Состояние настроек.
 */
data class SettingsUiState(
    val ttsEngine: String = TtsEngines.SYSTEM,
    val catalog: List<ModelCard> = emptyList(),
    val uiLanguage: String = ModelPreferences.DEFAULT_UI_LANGUAGE,
    val theme: String = ModelPreferences.DEFAULT_THEME,
    val unloadTimeoutMinutes: Int = ModelPreferences.DEFAULT_UNLOAD_TIMEOUT_MINUTES,
    val directionRetention: String = ModelPreferences.DEFAULT_MODEL_DIRECTION_RETENTION
)

/**
 * ViewModel настроек.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: ModelPreferences,
    modelManager: ModelManager
) : ViewModel() {

    internal companion object {
        /** Пресет тайм-аута выгрузки «через 15 минут» (минуты). */
        internal const val UNLOAD_PRESET_15_MINUTES = 15

        /** Пресет тайм-аута выгрузки «через 30 минут» (минуты). */
        internal const val UNLOAD_PRESET_30_MINUTES = 30

        /** Пресеты тайм-аута выгрузки в порядке показа (кроме дефолтной опции). */
        internal val UNLOAD_PRESET_MINUTES = listOf(
            UNLOAD_PRESET_15_MINUTES,
            UNLOAD_PRESET_30_MINUTES
        )
    }

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    // Сообщение сбоя наблюдения потоков (ревью #3): id строкового ресурса,
    // sticky до скрытия баннером — по образцу экранов «Модели»/«История».
    private val _observeErrorRes = MutableStateFlow<Int?>(null)
    val observeErrorRes: StateFlow<Int?> = _observeErrorRes

    init {
        // Все настройки наблюдаются одинаково: значение потока пишется в
        // соответствующее поле состояния (общий каркас — [watch]). Room/DataStore
        // поток без guard ронял процесс при сбое — observeResilient логирует,
        // показывает ошибку и перезапускает подписку (ревью #3).
        watch(modelManager.catalog) { state, cards -> state.copy(catalog = cards) }
        watch(preferences.ttsEngine) { state, engine -> state.copy(ttsEngine = engine) }
        watch(preferences.uiLanguage) { state, lang -> state.copy(uiLanguage = lang) }
        watch(preferences.theme) { state, theme -> state.copy(theme = theme) }
        watch(preferences.unloadTimeoutMinutes) { state, minutes ->
            state.copy(unloadTimeoutMinutes = minutes)
        }
        watch(preferences.modelDirectionRetention) { state, retention ->
            state.copy(directionRetention = retention)
        }
    }

    /** Подписка потока настройки на запись в [uiState] без потери остальных полей. */
    private fun <T> watch(source: Flow<T>, assign: (SettingsUiState, T) -> SettingsUiState) {
        observeResilient(
            scope = viewModelScope,
            source = { source },
            messageRes = R.string.settings_observe_failed,
            errorState = _observeErrorRes
        ) { value ->
            _uiState.value = assign(_uiState.value, value)
        }
    }

    /** Скрыть баннер сбоя наблюдения (тап по баннеру в UI). */
    fun clearObserveError() {
        _observeErrorRes.value = null
    }

    fun onTtsEngineChange(engine: String) {
        viewModelScope.launch { preferences.setTtsEngine(engine) }
    }

    fun onUiLanguageChange(lang: String) {
        viewModelScope.launch { preferences.setUiLanguage(lang) }
    }

    fun onThemeChange(theme: String) {
        viewModelScope.launch { preferences.setTheme(theme) }
    }

    /**
     * Смена тайм-аута выгрузки: значение опции — минуты в строковом виде
     * («-1» — не выгружать, «0» — сразу).
     */
    fun onUnloadTimeoutChange(value: String) {
        val minutes = value.toIntOrNull()
            ?: ModelPreferences.DEFAULT_UNLOAD_TIMEOUT_MINUTES
        viewModelScope.launch { preferences.setUnloadTimeoutMinutes(minutes) }
    }

    /**
     * Смена поведения при смене направления перевода (issue #36):
     * «keep» — не выгружать модели других направлений, «unload» — выгружать.
     * Неизвестное значение нормализуется к значению по умолчанию (keep).
     */
    fun onDirectionRetentionChange(value: String) {
        val retention = if (value == ModelPreferences.RETENTION_UNLOAD) {
            ModelPreferences.RETENTION_UNLOAD
        } else {
            ModelPreferences.RETENTION_KEEP
        }
        viewModelScope.launch { preferences.setModelDirectionRetention(retention) }
    }
}

/** Подписи TTS-движков в карточке настройки (идентификатор → строковый ресурс). */
private val TTS_ENGINE_LABELS = mapOf(
    TtsEngines.SYSTEM to R.string.settings_tts_system,
    TtsEngines.RHVOICE to R.string.settings_tts_rhvoice,
    TtsEngines.ESPEAK to R.string.settings_tts_espeak
)

/**
 * Подписи языков интерфейса (код → строковый ресурс); список языков —
 * общий со списком чипов перевода [TranslateLanguage.SUPPORTED_CODES]
 * (ревью #20).
 */
private val UI_LANGUAGE_LABELS = mapOf(
    "ru" to R.string.option_ru,
    "en" to R.string.option_en,
    "ne" to R.string.option_ne
)

/**
 * Экран «Настройки»: TTS-движок, язык интерфейса, тема, выгрузка моделей,
 * поведение при смене направления перевода.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val observeErrorRes by viewModel.observeErrorRes.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.nav_settings),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // Баннер сбоя наблюдения потоков настроек (ревью #3, тап — скрыть).
        observeErrorRes?.let { res ->
            ErrorBanner(text = stringResource(res), onClick = { viewModel.clearObserveError() })
        }

        // TTS-движок: скрытые карточки каталога (hidden, issue #55) движки
        // не предлагают; уже выбранный движок остаётся в списке всегда.
        OptionCard(
            titleRes = R.string.settings_tts_engine,
            selected = state.ttsEngine,
            onSelect = viewModel::onTtsEngineChange,
            options = visibleTtsEngineIds(state.catalog, state.ttsEngine)
                .map { engine -> engine to stringResource(TTS_ENGINE_LABELS.getValue(engine)) }
        )

        // Язык интерфейса: общий список поддерживаемых языков с чипами
        // перевода (ревью #20).
        OptionCard(
            titleRes = R.string.settings_ui_language,
            selected = state.uiLanguage,
            onSelect = viewModel::onUiLanguageChange,
            options = listOf(
                ModelPreferences.DEFAULT_UI_LANGUAGE to stringResource(R.string.option_system)
            ) + TranslateLanguage.SUPPORTED_CODES.map { code ->
                code to stringResource(UI_LANGUAGE_LABELS.getValue(code))
            }
        )

        // Тема.
        OptionCard(
            titleRes = R.string.settings_theme,
            selected = state.theme,
            onSelect = viewModel::onThemeChange,
            options = listOf(
                ModelPreferences.THEME_SYSTEM to stringResource(R.string.option_system),
                ModelPreferences.THEME_LIGHT to stringResource(R.string.settings_theme_light),
                ModelPreferences.THEME_DARK to stringResource(R.string.settings_theme_dark)
            )
        )

        // Выгрузка моделей при сворачивании.
        // Опция «по умолчанию» собирает и значение, и подпись из одной
        // константы, чтобы минуты в тексте не разъезжались с реальным значением.
        val defaultUnloadMinutes = ModelPreferences.DEFAULT_UNLOAD_TIMEOUT_MINUTES
        OptionCard(
            titleRes = R.string.settings_unload_models,
            selected = state.unloadTimeoutMinutes.toString(),
            onSelect = viewModel::onUnloadTimeoutChange,
            options = listOf(
                BackgroundUnloadScheduler.UNLOAD_IMMEDIATELY.toString() to
                    stringResource(R.string.settings_unload_now),
                defaultUnloadMinutes.toString() to
                    pluralStringResource(
                        R.plurals.settings_unload_after_default,
                        defaultUnloadMinutes,
                        defaultUnloadMinutes
                    )
            ) + SettingsViewModel.UNLOAD_PRESET_MINUTES.map { minutes ->
                // Значение опции и подпись — из одной константы (ревью #65):
                // минуты в тексте не разъезжаются с реальным значением.
                minutes.toString() to pluralStringResource(
                    R.plurals.settings_unload_after,
                    minutes,
                    minutes
                )
            } + listOf(
                BackgroundUnloadScheduler.NEVER_UNLOAD.toString() to
                    stringResource(R.string.settings_unload_never)
            )
        )

        // Поведение движков при смене направления перевода (issue #36).
        OptionCard(
            titleRes = R.string.settings_direction_retention,
            selected = state.directionRetention,
            onSelect = viewModel::onDirectionRetentionChange,
            options = listOf(
                ModelPreferences.RETENTION_KEEP to
                    stringResource(R.string.settings_retention_keep),
                ModelPreferences.RETENTION_UNLOAD to
                    stringResource(R.string.settings_retention_unload)
            ),
            optionHints = mapOf(
                ModelPreferences.RETENTION_KEEP to
                    stringResource(R.string.settings_retention_keep_hint)
            )
        )
    }
}

/**
 * Карточка настройки с заголовком и списком radio-опций.
 * @param options пары (значение, подпись).
 * @param optionHints необязательные подсказки под опциями (значение → текст).
 */
@Composable
private fun OptionCard(
    titleRes: Int,
    selected: String,
    onSelect: (String) -> Unit,
    options: List<Pair<String, String>>,
    optionHints: Map<String, String> = emptyMap()
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            options.forEach { (value, label) ->
                OptionRow(value, label, optionHints[value], selected, onSelect)
            }
        }
    }
}

/**
 * Строка radio-опции: кликабельна по всей ширине.
 * Обработчик навешан только на строку (role = RadioButton), сам RadioButton
 * не кликабелен (onClick = null) — это исключает двойное переключение.
 * @param hint необязательная подсказка под строкой.
 */
@Composable
private fun OptionRow(
    value: String,
    label: String,
    hint: String?,
    selected: String,
    onSelect: (String) -> Unit
) {
    val isSelected = selected == value
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .selectable(
                selected = isSelected,
                role = Role.RadioButton,
                onClick = { onSelect(value) }
            )
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = null
        )
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
