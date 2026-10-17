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
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val uiLanguage: String = "system",
    val theme: String = "system",
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

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    init {
        viewModelScope.launch {
            modelManager.catalog.collect { cards ->
                _uiState.value = _uiState.value.copy(catalog = cards)
            }
        }
        viewModelScope.launch {
            preferences.ttsEngine.collect { engine ->
                _uiState.value = _uiState.value.copy(ttsEngine = engine)
            }
        }
        viewModelScope.launch {
            preferences.uiLanguage.collect { lang ->
                _uiState.value = _uiState.value.copy(uiLanguage = lang)
            }
        }
        viewModelScope.launch {
            preferences.theme.collect { theme ->
                _uiState.value = _uiState.value.copy(theme = theme)
            }
        }
        viewModelScope.launch {
            preferences.unloadTimeoutMinutes.collect { minutes ->
                _uiState.value = _uiState.value.copy(unloadTimeoutMinutes = minutes)
            }
        }
        viewModelScope.launch {
            preferences.modelDirectionRetention.collect { retention ->
                _uiState.value = _uiState.value.copy(directionRetention = retention)
            }
        }
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

/**
 * Экран «Настройки»: TTS-движок, язык интерфейса, тема, выгрузка моделей,
 * поведение при смене направления перевода.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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

        // TTS-движок: скрытые карточки каталога (hidden, issue #55) движки
        // не предлагают; уже выбранный движок остаётся в списке всегда.
        val ttsLabelRes = mapOf(
            TtsEngines.SYSTEM to R.string.settings_tts_system,
            TtsEngines.RHVOICE to R.string.settings_tts_rhvoice,
            TtsEngines.ESPEAK to R.string.settings_tts_espeak
        )
        OptionCard(
            titleRes = R.string.settings_tts_engine,
            selected = state.ttsEngine,
            onSelect = viewModel::onTtsEngineChange,
            options = visibleTtsEngineIds(state.catalog, state.ttsEngine)
                .map { engine -> engine to stringResource(ttsLabelRes.getValue(engine)) }
        )

        // Язык интерфейса.
        OptionCard(
            titleRes = R.string.settings_ui_language,
            selected = state.uiLanguage,
            onSelect = viewModel::onUiLanguageChange,
            options = listOf(
                "system" to stringResource(R.string.option_system),
                "ru" to stringResource(R.string.option_ru),
                "en" to stringResource(R.string.option_en),
                "ne" to stringResource(R.string.option_ne)
            )
        )

        // Тема.
        OptionCard(
            titleRes = R.string.settings_theme,
            selected = state.theme,
            onSelect = viewModel::onThemeChange,
            options = listOf(
                "system" to stringResource(R.string.option_system),
                "light" to stringResource(R.string.settings_theme_light),
                "dark" to stringResource(R.string.settings_theme_dark)
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
                    ),
                "15" to stringResource(R.string.settings_unload_after_15),
                "30" to stringResource(R.string.settings_unload_after_30),
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
