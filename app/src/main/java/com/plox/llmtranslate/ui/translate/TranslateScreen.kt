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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Экран «Перевод»: текст или аудио → перевод → озвучивание.
 */
@Composable
fun TranslateScreen(
    viewModel: TranslateViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

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
                onSelect = viewModel::onSourceLangChange
            )
            IconButton(onClick = {
                viewModel.onSourceLangChange(state.targetLang)
                viewModel.onTargetLangChange(state.sourceLang)
            }) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = "Поменять языки")
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
                            contentDescription = "Записать аудио",
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
                    placeholder = { Text("Введите текст для перевода…") }
                )
            }
        }

        // Кнопка перевода.
        Button(
            onClick = viewModel::translate,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isTranslating && state.hasTranslationModel
        ) {
            Icon(Icons.Filled.Translate, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (state.isTranslating) "Перевод…" else "Перевести")
        }

        if (!state.hasTranslationModel) {
            Text(
                text = "Скачайте модель перевода на экране «Модели»",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // Результат.
        if (state.resultText.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(
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
                                else Icons.Filled.VolumeUp,
                                contentDescription = "Озвучить"
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
                text = "● Идёт запись… Нажмите на микрофон, чтобы остановить",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        // Ошибка.
        state.error?.let { error ->
            AlertDialog(
                onDismissRequest = viewModel::clearError,
                title = { Text("Ошибка") },
                text = { Text(error) },
                confirmButton = {
                    TextButton(onClick = viewModel::clearError) { Text("ОК") }
                }
            )
        }
    }
}

@Composable
private fun LanguageChip(
    selected: TranslateLanguage,
    onSelect: (TranslateLanguage) -> Unit
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
            title = { Text("Выберите язык") },
            text = {
                Column {
                    TranslateLanguage.entries.forEach { lang ->
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
