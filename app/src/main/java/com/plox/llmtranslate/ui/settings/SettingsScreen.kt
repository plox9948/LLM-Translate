package com.plox.llmtranslate.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plox.llmtranslate.R
import com.plox.llmtranslate.data.prefs.ModelPreferences
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
    val theme: String = "system"
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
}

/**
 * Экран «Настройки»: TTS-движок, язык интерфейса, тема.
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
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.settings_tts_engine),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                TtsOption(
                    "system",
                    stringResource(R.string.settings_tts_system),
                    state.ttsEngine,
                    viewModel::onTtsEngineChange
                )
                TtsOption(
                    "rhvoice",
                    stringResource(R.string.settings_tts_rhvoice),
                    state.ttsEngine,
                    viewModel::onTtsEngineChange
                )
                TtsOption(
                    "espeak",
                    stringResource(R.string.settings_tts_espeak),
                    state.ttsEngine,
                    viewModel::onTtsEngineChange
                )
            }
        }

        // Язык интерфейса.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.settings_ui_language),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                LangOption(
                    "system",
                    stringResource(R.string.option_system),
                    state.uiLanguage,
                    viewModel::onUiLanguageChange
                )
                LangOption(
                    "ru",
                    stringResource(R.string.option_ru),
                    state.uiLanguage,
                    viewModel::onUiLanguageChange
                )
                LangOption(
                    "en",
                    stringResource(R.string.option_en),
                    state.uiLanguage,
                    viewModel::onUiLanguageChange
                )
            }
        }

        // Тема.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.settings_theme),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                ThemeOption(
                    "system",
                    stringResource(R.string.option_system),
                    state.theme,
                    viewModel::onThemeChange
                )
                ThemeOption(
                    "light",
                    stringResource(R.string.settings_theme_light),
                    state.theme,
                    viewModel::onThemeChange
                )
                ThemeOption(
                    "dark",
                    stringResource(R.string.settings_theme_dark),
                    state.theme,
                    viewModel::onThemeChange
                )
            }
        }
    }
}

@Composable
private fun TtsOption(
    value: String,
    label: String,
    selected: String,
    onSelect: (String) -> Unit
) {
    OptionRow(value, label, selected, onSelect)
}

@Composable
private fun LangOption(
    value: String,
    label: String,
    selected: String,
    onSelect: (String) -> Unit
) {
    OptionRow(value, label, selected, onSelect)
}

@Composable
private fun ThemeOption(
    value: String,
    label: String,
    selected: String,
    onSelect: (String) -> Unit
) {
    OptionRow(value, label, selected, onSelect)
}

@Composable
private fun OptionRow(
    value: String,
    label: String,
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected == value,
            onClick = { onSelect(value) }
        )
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}
