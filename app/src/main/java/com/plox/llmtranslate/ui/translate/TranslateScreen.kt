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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.plox.llmtranslate.R

/**
 * Экран «Перевод»: текст или аудио → перевод → озвучивание.
 */
@Composable
fun TranslateScreen(
    viewModel: TranslateViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
            viewModel.onPermissionDenied()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

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
                onClick = {
                    viewModel.onSourceLangChange(state.targetLang)
                    viewModel.onTargetLangChange(state.sourceLang)
                },
                enabled = state.sourceLang != TranslateLanguage.AUTO
            ) {
                Icon(
                    Icons.Filled.SwapHoriz,
                    contentDescription = stringResource(R.string.translate_swap_cd),
                    tint = if (state.sourceLang != TranslateLanguage.AUTO) {
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
                Text(stringResource(R.string.translate_clear))
            }
            // Во время длинного перевода — кнопка отмены инференса.
            if (state.canCancelTranslation) {
                OutlinedButton(
                    onClick = viewModel::cancelTranslation
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.translate_cancel))
                }
            }
        }

        if (!state.hasTranslationModel) {
            Text(
                text = stringResource(R.string.translate_no_model),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // Результат.
        if (state.resultText.isNotEmpty()) {
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
                    }
                    SelectionContainer {
                        Text(
                            text = state.resultText,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Индикатор записи.
        if (state.isRecording) {
            Text(
                text = stringResource(R.string.translate_recording_hint),
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
