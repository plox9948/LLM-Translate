package com.plox.llmtranslate.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import com.plox.llmtranslate.BuildConfig
import com.plox.llmtranslate.R
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.BackgroundUnloadScheduler
import com.plox.llmtranslate.domain.ModelManager
import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.model.TtsEngines
import com.plox.llmtranslate.domain.model.visibleTtsEngineIds
import com.plox.llmtranslate.domain.update.ReleaseInfo
import com.plox.llmtranslate.domain.update.UpdateStateHost
import com.plox.llmtranslate.domain.update.UpdateUiState
import com.plox.llmtranslate.domain.update.userMessage
import com.plox.llmtranslate.ui.ErrorBanner
import com.plox.llmtranslate.ui.ObserveErrorTracker
import com.plox.llmtranslate.ui.launchGuarded
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
    val directionRetention: String = ModelPreferences.DEFAULT_MODEL_DIRECTION_RETENTION,
    /** Автопроверки обновлений при старте (issue #95). */
    val autoUpdateChecksEnabled: Boolean = true,
    /** Срез состояния хоста обновления для секции «Обновление» (issue #95). */
    val updateState: UpdateUiState = UpdateUiState.Idle
)

/**
 * ViewModel настроек.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: ModelPreferences,
    modelManager: ModelManager,
    private val updateStateHost: UpdateStateHost
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
    // Трекер множества ошибок (ревью): баннер снимается только когда все
    // подписки живы — восстановление одной не гасит сбой другой.
    private val observeErrors = ObserveErrorTracker()
    val observeErrorRes: StateFlow<Int?> = observeErrors.state

    init {
        // Все настройки наблюдаются одинаково: значение потока пишется в
        // соответствующее поле состояния (общий каркас — [watch]). Room/DataStore
        // поток без guard ронял процесс при сбое — observeResilient логирует,
        // показывает ошибку и перезапускает подписку (ревью #3). Маркер —
        // имя наблюдаемой настройки (уникален на подписку, re-review).
        watch(modelManager.catalog, "catalog") { state, cards -> state.copy(catalog = cards) }
        watch(preferences.ttsEngine, "tts_engine") { state, engine -> state.copy(ttsEngine = engine) }
        watch(preferences.uiLanguage, "ui_language") { state, lang -> state.copy(uiLanguage = lang) }
        watch(preferences.theme, "theme") { state, theme -> state.copy(theme = theme) }
        watch(preferences.unloadTimeoutMinutes, "unload_timeout") { state, minutes ->
            state.copy(unloadTimeoutMinutes = minutes)
        }
        watch(preferences.modelDirectionRetention, "direction_retention") { state, retention ->
            state.copy(directionRetention = retention)
        }
        // Самообновление (issue #95): настройка автопроверок + срез состояния
        // общего хоста UpdateStateHost для секции «Обновление».
        watch(preferences.autoUpdateChecksEnabled, "auto_update_checks") { state, enabled ->
            state.copy(autoUpdateChecksEnabled = enabled)
        }
        watch(updateStateHost.state, "update_state") { state, updateState ->
            state.copy(updateState = updateState)
        }
    }

    /** Подписка потока настройки на запись в [uiState] без потери остальных полей. */
    private fun <T> watch(
        source: Flow<T>,
        marker: String,
        assign: (SettingsUiState, T) -> SettingsUiState
    ) {
        observeResilient(
            scope = viewModelScope,
            source = { source },
            messageRes = R.string.settings_observe_failed,
            errors = observeErrors,
            marker = marker
        ) { value ->
            _uiState.value = assign(_uiState.value, value)
        }
    }

    /** Скрыть баннер сбоя наблюдения (тап по баннеру в UI). */
    fun clearObserveError() {
        observeErrors.dismissAll()
    }

    /**
     * Запись настройки с guard (ревью #83):IOException коммита DataStore в
     * launch без обработчика роняла процесс; общий каркас launchGuarded
     * логирует сбой и регистрирует его в том же трекере, что подписки
     * (маркер уникален на операцию, баннер общий).
     */
    private fun writeSetting(marker: String, block: suspend () -> Unit) {
        launchGuarded(
            scope = viewModelScope,
            messageRes = R.string.settings_write_failed,
            errors = observeErrors,
            logMessage = "Не удалось сохранить настройку $marker",
            marker = "write_$marker",
            block = block
        )
    }

    fun onTtsEngineChange(engine: String) {
        writeSetting("tts_engine") { preferences.setTtsEngine(engine) }
    }

    fun onUiLanguageChange(lang: String) {
        writeSetting("ui_language") { preferences.setUiLanguage(lang) }
    }

    fun onThemeChange(theme: String) {
        writeSetting("theme") { preferences.setTheme(theme) }
    }

    /**
     * Смена тайм-аута выгрузки: значение опции — минуты в строковом виде
     * («-1» — не выгружать, «0» — сразу).
     */
    fun onUnloadTimeoutChange(value: String) {
        val minutes = value.toIntOrNull()
            ?: ModelPreferences.DEFAULT_UNLOAD_TIMEOUT_MINUTES
        writeSetting("unload_timeout") { preferences.setUnloadTimeoutMinutes(minutes) }
    }

    /**
     * Смена поведения при смене направления перевода (issue #36):
     * «keep» — не выгружать модели других направлений, «unload» — выгружать.
     * Нормализация неизвестного значения к значению по умолчанию (keep) —
     * в единственной точке [ModelPreferences.setModelDirectionRetention].
     */
    fun onDirectionRetentionChange(value: String) {
        writeSetting("direction_retention") { preferences.setModelDirectionRetention(value) }
    }

    /**
     * Переключатель автопроверок обновлений (issue #95).
     */
    fun onAutoUpdateChecksChange(enabled: Boolean) {
        writeSetting("auto_update_checks") { preferences.setAutoUpdateChecksEnabled(enabled) }
    }

    /**
     * Ручная проверка обновления из секции «Обновление» (issue #95):
     * результат транслирует в uiState.updateState сам UpdateStateHost
     * (ManualUpToDate / ManualCheckFailed / Available с showDialog=true).
     */
    fun onCheckUpdateClick() {
        launchGuarded(
            scope = viewModelScope,
            messageRes = R.string.update_check_failed,
            errors = observeErrors,
            logMessage = "Самообновление: сбой ручной проверки из настроек",
            marker = "update_check"
        ) {
            updateStateHost.checkManually()
        }
    }

    /** «Скачать» в секции «Обновление» — загрузка APK найденного релиза. */
    fun onDownloadUpdateClick(release: ReleaseInfo) {
        launchGuarded(
            scope = viewModelScope,
            messageRes = R.string.update_download_failed,
            errors = observeErrors,
            logMessage = "Самообновление: сбой скачивания APK из настроек",
            marker = "update_download"
        ) {
            updateStateHost.startDownload(release)
        }
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

        // Самообновление (issue #95): версия, автопроверки, ручная проверка
        // и скачивание найденного релиза; транзиты состояния показывает
        // срез UpdateStateHost (тот же хост, что у глобального диалога).
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.update_settings_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.update_current_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.update_auto_checks),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = state.autoUpdateChecksEnabled,
                        onCheckedChange = viewModel::onAutoUpdateChecksChange
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = viewModel::onCheckUpdateClick,
                    enabled = state.updateState !is UpdateUiState.Checking &&
                        state.updateState !is UpdateUiState.Downloading
                ) {
                    Text(
                        stringResource(
                            if (state.updateState is UpdateUiState.Checking) {
                                R.string.update_checking
                            } else {
                                R.string.update_check_button
                            }
                        )
                    )
                }
                when (val updateState = state.updateState) {
                    is UpdateUiState.Available -> Column {
                        Text(
                            text = stringResource(R.string.update_new_version, updateState.release.version),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(onClick = { viewModel.onDownloadUpdateClick(updateState.release) }) {
                            Text(stringResource(R.string.download))
                        }
                    }

                    UpdateUiState.ManualUpToDate -> Text(
                        text = stringResource(R.string.update_not_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    is UpdateUiState.ManualCheckFailed -> Text(
                        // Готовый user-facing текст домена (pattern userMessage).
                        text = updateState.reason.userMessage(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )

                    is UpdateUiState.Downloading -> Column {
                        Text(
                            text = stringResource(R.string.update_downloading_text, updateState.progress),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { updateState.progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    is UpdateUiState.Downloaded -> Text(
                        text = stringResource(R.string.update_downloaded),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    is UpdateUiState.DownloadFailed -> Text(
                        text = updateState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )

                    else -> Unit
                }
            }
        }
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
