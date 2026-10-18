package com.plox.llmtranslate.ui.translate

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.plox.llmtranslate.R
import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.ui.FavoriteStarIcon
import com.plox.llmtranslate.ui.models.formatBytes

/**
 * Экран «Перевод»: текст или аудио → перевод → озвучивание.
 */
@Composable
fun TranslateScreen(
    viewModel: TranslateViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Буфер обмена для кнопки копирования результата.
    val clipboard = LocalClipboardManager.current

    // Разрешение на запись с микрофона.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startRecording()
        } else {
            viewModel.onPermissionDenied()
        }
    }

    fun onMicClick() {
        if (state.isRecording) {
            viewModel.stopRecordingAndTranscribe()
        } else if (!state.hasSttModel) {
            // Отдельное сообщение про отсутствующую STT-модель (issue #57):
            // раньше оно смешивалось с текстом про разрешение микрофона.
            viewModel.onSttModelMissing()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Явный исходный язык: направление определено — доступны обмен языков
    // и звезда избранного результата (для AUTO язык результата неизвестен).
    val explicitSource = state.sourceLang != TranslateLanguage.AUTO

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Выбор языков.
        Row(verticalAlignment = Alignment.CenterVertically) {
            LanguageChip(
                selected = state.sourceLang,
                onSelect = viewModel::onSourceLangChange,
                allowAuto = true
            )
            IconButton(
                onClick = viewModel::swapDirections,
                enabled = explicitSource
            ) {
                Icon(
                    Icons.Filled.SwapHoriz,
                    contentDescription = stringResource(R.string.translate_swap_cd),
                    tint = if (explicitSource) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            LanguageChip(
                selected = state.targetLang,
                onSelect = viewModel::onTargetLangChange
            )
        }

        // Поле ввода.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.sourceLang.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // Кнопка записи аудио (STT).
                    IconButton(
                        onClick = { onMicClick() },
                        enabled = !state.isTranslating
                    ) {
                        Icon(
                            imageVector = if (state.isRecording) Icons.Filled.MicOff
                            else Icons.Filled.Mic,
                            contentDescription = stringResource(R.string.translate_record_cd),
                            tint = if (state.hasSttModel) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                OutlinedTextField(
                    value = state.inputText,
                    onValueChange = viewModel::onInputTextChanged,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    placeholder = { Text(stringResource(R.string.translate_input_placeholder)) }
                )
            }
        }

        // Кнопки перевода и очистки поля.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::translate,
                modifier = Modifier.weight(1f),
                enabled = !state.isTranslating && state.hasTranslationModel
            ) {
                Icon(Icons.Filled.Translate, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (state.isTranslating) R.string.translate_in_progress
                        else R.string.translate_action
                    )
                )
            }
            OutlinedButton(
                onClick = viewModel::clearInput,
                enabled = !state.isTranslating &&
                    (state.inputText.isNotEmpty() || state.resultText.isNotEmpty())
            ) {
                Text(stringResource(R.string.clear))
            }
            // Во время длинного перевода — кнопка отмены инференса.
            if (state.canCancelTranslation) {
                OutlinedButton(
                    onClick = viewModel::cancelTranslation
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.cancel))
                }
            }
        }

        // Памятка недостающих моделей (issues #33, #56): звенья перевода
        // и/или STT исходного языка — показываем при наличии любых
        // недостающих; текст-заглушка — только когда перевод не готов
        // и каталог не предлагает звеньев перевода.
        if (!state.hasTranslationModel || state.missingSttModels.isNotEmpty()) {
            if (state.missingModels.isNotEmpty() || state.missingSttModels.isNotEmpty()) {
                MissingModelsCard(
                    models = state.missingModels,
                    sttModels = state.missingSttModels,
                    downloadingProgress = state.downloadingProgress,
                    onDownload = viewModel::downloadMissingModel
                )
            } else {
                Text(
                    text = stringResource(R.string.translate_no_model),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // Результат: показываем только привязанный к текущему направлению
        // (issue #59) — при смене направления или возврате на вкладку чужой
        // кэш-перевод под новыми языками не отображается и не озвучивается.
        val visibleResult = state.visibleResult
        if (visibleResult.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = state.targetLang.title,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        // Копирование перевода в буфер обмена.
                        IconButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(visibleResult))
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = stringResource(R.string.translate_copy_cd)
                            )
                        }
                        // Озвучивание.
                        IconButton(onClick = {
                            if (state.isSpeaking) viewModel.stopSpeaking()
                            else viewModel.speakResult()
                        }) {
                            Icon(
                                imageVector = if (state.isSpeaking) Icons.Filled.Stop
                                else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = stringResource(R.string.translate_speak_cd)
                            )
                        }
                        // Избранное перевода (issue #38): для AUTO-источника
                        // направление результата не определено — звезда выключена.
                        IconButton(
                            onClick = viewModel::toggleResultFavorite,
                            enabled = explicitSource
                        ) {
                            FavoriteStarIcon(state.resultFavorite)
                        }
                    }
                    SelectionContainer {
                        Text(
                            text = visibleResult,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Автоскачивание Piper-модели озвучки при фолбэке (issue #50):
        // баннер на уровне экрана, а не внутри карточки результата — не
        // исчезает при очистке ввода, прогресс/ошибка видны всегда (ревью).
        state.ttsDownloadProgress?.let { progress ->
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(R.string.translate_tts_downloading, progress),
                style = MaterialTheme.typography.labelSmall
            )
        }
        if (state.ttsDownloadFailed) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.translate_tts_download_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // Индикатор записи.
        if (state.isRecording) {
            // таймер длительности записи (issue #68) — подсказка о потолке
            // 3 минуты, после которого запись остановится автоматически.
            val totalSec = (state.recordingElapsedMs / 1000L).toInt()
            Text(
                text = stringResource(R.string.translate_recording_hint) +
                    " " + "%d:%02d".format(totalSec / 60, totalSec % 60),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        // Ошибка.
        state.error?.let { error ->
            AlertDialog(
                onDismissRequest = viewModel::clearError,
                title = { Text(stringResource(R.string.error_title)) },
                text = { Text(error) },
                confirmButton = {
                    TextButton(onClick = viewModel::clearError) {
                        Text(stringResource(R.string.ok))
                    }
                }
            )
        }
    }

    // Одноразовый онбординг первого запуска (issue #33): выбор направления;
    // закрытие свайпом/тапом вне sheet считается пропуском.
    if (state.showOnboarding) {
        OnboardingSheet(
            onOption = viewModel::onOnboardingOption,
            onSkip = viewModel::onOnboardingSkip
        )
    }
}

/**
 * Онбординг bottom sheet первого запуска (issue #33): три варианта направления
 * перевода и «Пропустить». Выбор варианта применяет направление и закрывает
 * sheet; после этого на экране сразу работает подсказка недостающих моделей.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingSheet(
    onOption: (Int) -> Unit,
    onSkip: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onSkip,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OnboardingOption.entries.forEach { option ->
                TextButton(
                    onClick = { onOption(option.ordinal) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(option.labelRes))
                }
            }
            TextButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.onboarding_skip),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Карточка подсказки недостающих моделей (issues #33, #56): звенья цепочки
 * перевода текущего направления и STT-модель исходного языка для ввода
 * голосом (отдельная секция с подписью «Распознавание»); кнопка «Скачать»
 * запускает скачивание только своего элемента, для уже скачиваемых моделей
 * показывается прогресс вместо кнопки.
 */
@Composable
private fun MissingModelsCard(
    models: List<ModelCard>,
    sttModels: List<ModelCard>,
    downloadingProgress: Map<String, Int>,
    onDownload: (ModelCard) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.translate_missing_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            models.forEach { card ->
                MissingModelRow(card, downloadingProgress[card.id], onDownload)
            }
            if (sttModels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.translate_missing_stt_caption),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                sttModels.forEach { card ->
                    MissingModelRow(card, downloadingProgress[card.id], onDownload)
                }
            }
        }
    }
}

/** Строка одной недостающей модели: имя, размер и кнопка «Скачать» или прогресс. */
@Composable
private fun MissingModelRow(
    card: ModelCard,
    progress: Int?,
    onDownload: (ModelCard) -> Unit
) {
    Spacer(modifier = Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = card.displayName,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = formatBytes(card.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (progress != null) {
            Text(
                text = stringResource(R.string.translate_download_progress, progress),
                style = MaterialTheme.typography.labelSmall
            )
        } else {
            OutlinedButton(onClick = { onDownload(card) }) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.download))
            }
        }
    }
}

/**
 * Чип выбора языка.
 * @param allowAuto разрешать ли выбор автоопределения (только для исходного языка).
 */
@Composable
private fun LanguageChip(
    selected: TranslateLanguage,
    onSelect: (TranslateLanguage) -> Unit,
    allowAuto: Boolean = false
) {
    // Простой выбор языка через чипы.
    var expanded by remember { mutableStateOf(false) }

    AssistChip(
        onClick = { expanded = true },
        label = { Text(selected.title) }
    )

    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text(stringResource(R.string.translate_select_language)) },
            text = {
                Column {
                    // Для целевого языка автоопределение недоступно.
                    TranslateLanguage.entries
                        .filter { allowAuto || it != TranslateLanguage.AUTO }
                        .forEach { lang ->
                            TextButton(
                                onClick = {
                                    onSelect(lang)
                                    expanded = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(lang.title)
                            }
                        }
                }
            },
            confirmButton = {}
        )
    }
}
