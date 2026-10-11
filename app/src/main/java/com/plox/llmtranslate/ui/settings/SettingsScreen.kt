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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plox.llmtranslate.R
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.BackgroundUnloadScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Состояние настроек.
 */
data class SettingsUiState(
    val ttsEngine: String = "system",
    val uiLanguage: String = "system",
    val theme: String = "system",
    val unloadTimeoutMinutes: Int = ModelPreferences.DEFAULT_UNLOAD_TIMEOUT_MINUTES
)

/**
 * ViewModel настроек.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: ModelPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    init {
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
}

/**
 * Экран «Настройки»: TTS-движок, язык интерфейса, тема, выгрузка моделей.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

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

        // TTS-движок.
        OptionCard(
            titleRes = R.string.settings_tts_engine,
            selected = state.ttsEngine,
            onSelect = viewModel::onTtsEngineChange,
            options = listOf(
                "system" to stringResource(R.string.settings_tts_system),
                "rhvoice" to stringResource(R.string.settings_tts_rhvoice),
                "espeak" to stringResource(R.string.settings_tts_espeak)
            )
        )

        // Язык интерфейса.
        OptionCard(
            titleRes = R.string.settings_ui_language,
            selected = state.uiLanguage,
            onSelect = viewModel::onUiLanguageChange,
            options = listOf(
                "system" to stringResource(R.string.option_system),
                "ru" to stringResource(R.string.option_ru),
                "en" to stringResource(R.string.option_en)
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
        OptionCard(
            titleRes = R.string.settings_unload_models,
            selected = state.unloadTimeoutMinutes.toString(),
            onSelect = viewModel::onUnloadTimeoutChange,
            options = listOf(
                BackgroundUnloadScheduler.UNLOAD_IMMEDIATELY.toString() to
                    stringResource(R.string.settings_unload_now),
                ModelPreferences.DEFAULT_UNLOAD_TIMEOUT_MINUTES.toString() to
                    stringResource(R.string.settings_unload_after_5),
                "15" to stringResource(R.string.settings_unload_after_15),
                "30" to stringResource(R.string.settings_unload_after_30),
                BackgroundUnloadScheduler.NEVER_UNLOAD.toString() to
                    stringResource(R.string.settings_unload_never)
            )
        )
    }
}

/**
 * Карточка настройки с заголовком и списком radio-опций.
 * @param options пары (значение, подпись).
 */
@Composable
private fun OptionCard(
    titleRes: Int,
    selected: String,
    onSelect: (String) -> Unit,
    options: List<Pair<String, String>>
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            options.forEach { (value, label) ->
                OptionRow(value, label, selected, onSelect)
            }
        }
    }
}

/**
 * Строка radio-опции: кликабельна по всей ширине.
 * Обработчик навешан только на строку (role = RadioButton), сам RadioButton
 * не кликабелен (onClick = null) — это исключает двойное переключение.
 */
@Composable
private fun OptionRow(
    value: String,
    label: String,
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
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}
