package com.plox.llmtranslate.ui.translate

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.plox.llmtranslate.ui.ErrorDialog
import com.plox.llmtranslate.ui.FavoriteStarIcon
import com.plox.llmtranslate.ui.formatBytes
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

/**
 * Экран «Перевод»: текст или аудио → перевод → озвучивание.
 *
 * @param showUpdateIcon доступ к глобальному диалогу обновления (issue #95):
 *        акцентная иконка в верхнем ряду при наличии нового релиза.
 * @param onUpdateClick открытие диалога обновления (состояние держит
 *        UpdateStateHost в MainScreen, экран только дёргает лямбду).
 */
@Composable
fun TranslateScreen(
    showUpdateIcon: Boolean = false,
    onUpdateClick: () -> Unit = {},
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
            // Прокрутка всего экрана (issue #79): при длинном вводе или
            // переводе карточки не обрезаются за нижней границей экрана.
            .verticalScroll(rememberScrollState())
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
            // Иконка доступного обновления (issue #95): акцентная, открывает
            // глобальный диалог UpdateStateHost; раскладку чипов не меняет —
            // разделитель только доживает до правого края.
            if (showUpdateIcon) {
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onUpdateClick) {
                    Icon(
                        Icons.Filled.SystemUpdate,
                        contentDescription = stringResource(R.string.update_icon_cd),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Поле ввода.
        InputCard(
            sourceLang = state.sourceLang,
            inputText = state.inputText,
            isTranslating = state.isTranslating,
            isRecording = state.isRecording,
            hasSttModel = state.hasSttModel,
            onInputTextChanged = viewModel::onInputTextChanged,
            onMicClick = { onMicClick() }
        )

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
            ResultCard(
                targetLang = state.targetLang,
                resultText = visibleResult,
                isSpeaking = state.isSpeaking,
                isFavorite = state.resultFavorite,
                favoriteEnabled = explicitSource,
                onSpeakToggle = {
                    if (state.isSpeaking) viewModel.stopSpeaking()
                    else viewModel.speakResult()
                },
                onToggleFavorite = viewModel::toggleResultFavorite
            )
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

        // Индикатор записи: таймер изолирован в дочерний composable (ревью #14) —
        // рекомпозиция по тикам elapsed не затрагивает весь экран; сам elapsed
        // — отдельный поток ViewModel (ревью), в общий UiState не попадает.
        if (state.isRecording) {
            RecordingIndicator(viewModel.recordingElapsed)
        }

        // Ошибка.
        ErrorDialog(state.error, viewModel::clearError)
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
 * Индикатор записи: подпись с плейсхолдером таймера из ресурса (ревью #14) —
 * подсказка о потолке длительности, после которого запись остановится
 * автоматически (issue #68). Вынесен в отдельный composable, чтобы тики
 * длительности приводили к рекомпозиции только этой строки.
 */
@Composable
private fun RecordingIndicator(elapsed: StateFlow<Long>) {
    // Точечная подписка на таймер (ревью): секундные тики рекомпозируют
    // только эту строку, не весь экран; параметр — сам поток таймера,
    // а не ViewModel (тестируемость composable).
    val elapsedMs by elapsed.collectAsStateWithLifecycle()
    val totalSec = (elapsedMs / 1000L).toInt()
    Text(
        text = stringResource(
            R.string.translate_recording_hint,
            String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error
    )
}

/**
 * Карточка поля ввода: подпись исходного языка, кнопки копирования и записи,
 * многострочное поле.
 */
@Composable
private fun InputCard(
    sourceLang: TranslateLanguage,
    inputText: String,
    isTranslating: Boolean,
    isRecording: Boolean,
    hasSttModel: Boolean,
    onInputTextChanged: (String) -> Unit,
    onMicClick: () -> Unit
) {
    // Буфер обмена для кнопки копирования ввода.
    val clipboard = LocalClipboardManager.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(sourceLang.titleRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.weight(1f))
                // Копирование исходного текста в буфер обмена.
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(inputText)) },
                    enabled = inputText.isNotEmpty() && !isTranslating
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.translate_copy_input_cd)
                    )
                }
                // Кнопка записи аудио (STT).
                IconButton(
                    onClick = onMicClick,
                    enabled = !isTranslating
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Filled.MicOff
                        else Icons.Filled.Mic,
                        contentDescription = stringResource(R.string.translate_record_cd),
                        tint = if (hasSttModel) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            // Поле растёт по содержимому (issue #79): без maxLines-капса
            // весь исходный текст доступен прокруткой экрана.
            // Во время перевода поле не редактируется — иначе правка
            // рассогласует ввод с уже считаемым результатом.
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputTextChanged,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                readOnly = isTranslating,
                placeholder = { Text(stringResource(R.string.translate_input_placeholder)) }
            )
        }
    }
}

/**
 * Карточка результата: подпись целевого языка, кнопки копирования,
 * озвучивания и звезды избранного, выделяемый текст перевода.
 */
@Composable
private fun ResultCard(
    targetLang: TranslateLanguage,
    resultText: String,
    isSpeaking: Boolean,
    isFavorite: Boolean,
    favoriteEnabled: Boolean,
    onSpeakToggle: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    // Буфер обмена для кнопки копирования результата.
    val clipboard = LocalClipboardManager.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(targetLang.titleRes),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.weight(1f))
                // Копирование перевода в буфер обмена.
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(resultText)) }
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.translate_copy_cd)
                    )
                }
                // Озвучивание.
                IconButton(onClick = onSpeakToggle) {
                    Icon(
                        imageVector = if (isSpeaking) Icons.Filled.Stop
                        else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = stringResource(R.string.translate_speak_cd)
                    )
                }
                // Избранное перевода (issue #38): для AUTO-источника
                // направление результата не определено — звезда выключена.
                IconButton(
                    onClick = onToggleFavorite,
                    enabled = favoriteEnabled
                ) {
                    FavoriteStarIcon(isFavorite)
                }
            }
            SelectionContainer {
                Text(
                    text = resultText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            }
        }
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
 * Чип выбора языка со штатным M3-меню (ревью #15): DropdownMenu вместо
 * самодельного AlertDialog со списком TextButton.
 * @param allowAuto разрешать ли выбор автоопределения (только для исходного языка).
 */
@Composable
private fun LanguageChip(
    selected: TranslateLanguage,
    onSelect: (TranslateLanguage) -> Unit,
    allowAuto: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(stringResource(selected.titleRes)) }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            // Для целевого языка автоопределение недоступно.
            TranslateLanguage.entries
                .filter { allowAuto || it != TranslateLanguage.AUTO }
                .forEach { lang ->
                    DropdownMenuItem(
                        text = { Text(stringResource(lang.titleRes)) },
                        onClick = {
                            onSelect(lang)
                            expanded = false
                        }
                    )
                }
        }
    }
}
