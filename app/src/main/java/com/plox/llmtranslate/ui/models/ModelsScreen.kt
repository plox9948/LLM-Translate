package com.plox.llmtranslate.ui.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.plox.llmtranslate.R
import com.plox.llmtranslate.domain.model.InstallCheck
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.ttsEngineId
import com.plox.llmtranslate.domain.runtime.LoadedModelInfo
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Экран «Модели»: три секции по категориям, карточки, скачивание, импорт.
 */
@Composable
fun ModelsScreen(
    viewModel: ModelsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceInfo by viewModel.deviceInfo.collectAsStateWithLifecycle()
    val licenseModel by viewModel.licenseConfirmModel.collectAsStateWithLifecycle()
    val blockedReason by viewModel.blockedReason.collectAsStateWithLifecycle()
    val processPssMb by viewModel.processPssMb.collectAsStateWithLifecycle()
    val ttsEngine by viewModel.ttsEngine.collectAsStateWithLifecycle()
    val sttByLanguage by viewModel.sttByLanguage.collectAsStateWithLifecycle()

    // Опрос PSS процесса живёт здесь, а не в ViewModel: цикл остановлен,
    // пока экран убран из composition (навигация с backstack) ИЛИ приложение
    // в фоне (<STARTED, activity STOP) — composition при этом жива, поэтому
    // одного LaunchedEffect мало: repeatOnLifecycle гасит опрос до возврата
    // на передний план, без обращений к AMS в фоне.
    // Первый замер — сразу при входе/возврате на передний план, далее раз в
    // PSS_POLL_INTERVAL_MS; при уходе в фон, с экрана или при разгрузке
    // моделей значение обнуляется.
    val hasLoadedModels = uiState.loadedModels.isNotEmpty()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(hasLoadedModels) {
        if (!hasLoadedModels) {
            viewModel.resetProcessPss()
            return@LaunchedEffect
        }
        try {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    while (true) {
                        viewModel.pollProcessPss()
                        delay(ModelsViewModel.PSS_POLL_INTERVAL_MS)
                    }
                } finally {
                    // Уход в фон (<STARTED): опрос остановлен — сбрасываем показ.
                    viewModel.resetProcessPss()
                }
            }
        } finally {
            // Отмена при выбросе composition — сбрасываем показ PSS.
            viewModel.resetProcessPss()
        }
    }

    // SAF: импорт своего GGUF.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.onImportResult(uri)
        }
    }

    // SAF: импорт резервной копии (ZIP с моделями и настройками).
    val backupImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.onBackupImportResult(uri)
        }
    }

    // SAF: экспорт резервной копии.
    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        viewModel.onBackupExportResult(uri)
    }

    // Запуск экспорта бэкапа при появлении запроса.
    LaunchedEffect(uiState.backupExportRequest) {
        uiState.backupExportRequest?.let { request ->
            backupExportLauncher.launch(request.fileName)
        }
    }

    // SAF: экспорт модели на другое устройство.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        viewModel.onExportResult(uri)
    }

    // Запуск экспорта при появлении запроса.
    LaunchedEffect(uiState.exportRequest) {
        uiState.exportRequest?.let { request ->
            exportLauncher.launch(request.fileName)
        }
    }

    // Предупреждение о нехватке ОЗУ при одновременном нахождении в памяти LLM и STT.
    val ramWarning = viewModel.checkRamWarning()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.nav_models),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Баннер причины блокировки установки (тап — скрыть).
            blockedReason?.let { reason ->
                ErrorBanner(text = reason, onClick = { viewModel.clearBlockedReason() })
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Баннер предупреждения об ОЗУ.
            ramWarning?.let { warning ->
                ErrorBanner(text = warning)
                Spacer(modifier = Modifier.height(8.dp))
            }
            OutlinedButton(
                onClick = {
                    importLauncher.launch(
                        arrayOf("application/octet-stream", "*/*")
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.models_import_custom))
            }
            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { viewModel.onBackupExportClick() },
                    modifier = Modifier.weight(1f),
                    enabled = !uiState.isBusy
                ) {
                    Icon(Icons.Filled.FileUpload, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.models_backup))
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        backupImportLauncher.launch(
                            arrayOf("application/zip", "application/x-zip-compressed", "*/*")
                        )
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !uiState.isBusy
                ) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.models_restore))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Статусное сообщение. Тап по баннеру скрывает его (кроме режима занятости).
            if (uiState.statusMessage != null || uiState.isBusy) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = uiState.statusMessage != null,
                            onClickLabel = stringResource(R.string.models_status_dismiss),
                            role = Role.Button
                        ) { viewModel.clearStatusMessage() }
                ) {
                    Text(
                        text = uiState.statusMessage ?: "…",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Блок загруженных в ОЗУ моделей (пуст — блок скрыт).
        if (uiState.loadedModels.isNotEmpty()) {
            item(key = "loaded_ram") {
                LoadedRamCard(models = uiState.loadedModels, processPssMb = processPssMb)
            }
        }

        ModelCategory.entries.forEach { category ->
            val cards = uiState.catalog.filter { it.category == category }
            val installed = uiState.installed.filter { it.category == category }
            val activeId = uiState.activeIds[category]

            item(key = "header_$category") {
                Text(
                    text = categoryTitle(category),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }

            items(cards, key = { "card_${it.id}" }) { card ->
                val installedModel = installed.firstOrNull { it.id == card.id }
                // Для TTS активная карточка — движок из настройки ttsEngine,
                // а не activeModelId (выбор живёт в настройках).
                val isActive = if (card.category == ModelCategory.TTS) {
                    card.runtime.ttsEngineId() == ttsEngine
                } else {
                    activeId == card.id
                }
                val check = deviceInfo?.let { viewModel.checkInstall(card, it) }
                // Чипы «язык распознавания» — только на установленных STT-карточках
                // по языкам самой модели (issue #28).
                val sttChips = if (card.category == ModelCategory.STT &&
                    installedModel?.isReady == true
                ) {
                    STT_CHIP_LANGUAGES.filter { it in card.languages }
                } else {
                    emptyList()
                }
                val sttAssignedLanguages =
                    sttChips.filter { sttByLanguage[it] == card.id }.toSet()
                // Чип «Нужно для RU→EN»: карточка не хватает для последнего
                // направления перевода (issue #33).
                val requiredChipLabel = uiState.requiredChips[card.id]?.let { direction ->
                    stringResource(R.string.models_required_for, direction)
                }
                // Issue #31: на время экспорта модели кнопки «Экспорт»/«Удалить»
                // этой карточки заблокированы (иначе удаление mid-экспорта даёт
                // обрезанный ZIP). Общий экспорт/импорт бэкапа (isBusy)
                // блокирует действия всех карточек.
                val exportingId = uiState.exportRequest?.modelId ?: uiState.exportingModelId
                val actionsLocked = uiState.isBusy || exportingId == card.id

                ModelCardItem(
                    card = card,
                    installedModel = installedModel,
                    isActive = isActive,
                    check = check,
                    sttChips = sttChips,
                    sttAssignedLanguages = sttAssignedLanguages,
                    requiredChipLabel = requiredChipLabel,
                    actionsLocked = actionsLocked,
                    onDownload = { viewModel.onDownloadClick(card) },
                    onImport = {
                        importLauncher.launch(
                            arrayOf("application/zip", "application/octet-stream", "*/*")
                        )
                    },
                    onDelete = { installedModel?.let { viewModel.onDeleteClick(it) } },
                    onActive = {
                        if (card.category == ModelCategory.TTS) {
                            viewModel.onTtsEngineSelect(card)
                        } else {
                            installedModel?.let { viewModel.onActiveClick(it) }
                        }
                    },
                    onCancel = { installedModel?.let { viewModel.onCancelDownload(it) } },
                    onExport = { installedModel?.let { viewModel.onExportClick(it) } },
                    onSttLanguageToggle = { lang -> viewModel.onSttLanguageToggle(card, lang) }
                )
            }
        }

        // Индикатор занятого места.
        item(key = "disk_usage") {
            uiState.diskUsage?.let { usage ->
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(
                            R.string.models_disk_used,
                            formatBytes(usage.totalBytes)
                        ),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    // Диалог подтверждения некоммерческой лицензии.
    licenseModel?.let { card ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelLicenseConfirm() },
            title = { Text(stringResource(R.string.models_license_title)) },
            text = {
                Text(stringResource(R.string.models_license_text, card.displayName, card.license))
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmLicenseAndDownload() }) {
                    Text(stringResource(R.string.download))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelLicenseConfirm() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * Баннер ошибки/предупреждения на весь экран.
 * @param onClick необязательный обработчик тапа (скрытие баннера).
 */
@Composable
private fun ErrorBanner(text: String, onClick: (() -> Unit)? = null) {
    val content: @Composable () -> Unit = {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp)
        )
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) { content() }
    } else {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) { content() }
    }
}

/**
 * Блок «Загружено в память»: модели, находящиеся в ОЗУ сейчас,
 * суммарная оценка потребления и фактический PSS процесса.
 */
@Composable
private fun LoadedRamCard(models: List<LoadedModelInfo>, processPssMb: Int?) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.models_loaded_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            models.forEach { model ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Краткая категория модели как префикс строки — различение
                    // «Перевод» / «Речь» в списке загруженного.
                    Text(
                        text = stringResource(loadedCategoryLabelRes(model.category)),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    if (model.ramRequiredMb > 0) {
                        Text(
                            text = stringResource(R.string.models_ram_mb, model.ramRequiredMb),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
            val totalMb = models.sumOf { it.ramRequiredMb }
            if (totalMb > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.models_loaded_estimate, totalMb),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            if (processPssMb != null) {
                Text(
                    text = stringResource(R.string.models_loaded_process, processPssMb),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

/**
 * Краткая подпись категории для строк карточки «Загружено в память».
 */
@StringRes
private fun loadedCategoryLabelRes(category: ModelCategory): Int = when (category) {
    ModelCategory.TRANSLATION -> R.string.models_loaded_cat_translation
    ModelCategory.STT -> R.string.models_loaded_cat_stt
    ModelCategory.TTS -> R.string.models_category_tts
}

@Composable
private fun ModelCardItem(
    card: ModelCard,
    installedModel: InstalledModel?,
    isActive: Boolean,
    check: InstallCheck?,
    sttChips: List<String>,
    sttAssignedLanguages: Set<String>,
    requiredChipLabel: String?,
    actionsLocked: Boolean,
    onDownload: () -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit,
    onActive: () -> Unit,
    onCancel: () -> Unit,
    onExport: () -> Unit,
    onSttLanguageToggle: (String) -> Unit
) {
    // TTS-карточки — встроенные движки: индикатор кликабелен без установки
    // и выбирает движок (запись в настройку ttsEngine).
    val isTtsEngine = card.runtime.ttsEngineId() != null
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Радиокнопка выбранного TTS-движка: selectable с ролью
                // RadioButton — доступный переключатель (TalkBack объявляет
                // состояние). Строка карточки не кликабельна (прошлый фикс
                // радиокнопок), выбор — только этой кнопкой или в настройках.
                // Радиокнопок перевода и STT нет: модель перевода выбирается
                // автоматически по направлению, STT — по языкам (issues #28, #29).
                val canSelect = isTtsEngine || installedModel?.isReady == true
                if (card.category == ModelCategory.TTS) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .selectable(
                                selected = isActive,
                                enabled = canSelect,
                                role = Role.RadioButton,
                                onClick = onActive
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isActive) Icons.Filled.RadioButtonChecked
                            else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = stringResource(R.string.models_active_cd),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = card.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = card.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isTtsEngine && isActive) {
                        Text(
                            text = stringResource(R.string.models_selected),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Звёзды качества.
                Stars(count = card.quality)
                Text(
                    text = stringResource(R.string.models_quality),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Stars(count = card.speed)
                Text(
                    text = stringResource(R.string.models_speed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatBytes(card.sizeBytes),
                    style = MaterialTheme.typography.labelSmall
                )
                if (card.ramRequiredMb > 0) {
                    Text(
                        text = stringResource(R.string.models_ram_mb, card.ramRequiredMb),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Text(
                    text = " · ${card.license}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Чип «Нужно для RU→EN»: карточка недостаёт для последнего
            // направления перевода (issue #33); те же чипы, что язык STT.
            if (requiredChipLabel != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        onClick = {},
                        label = { Text(requiredChipLabel) }
                    )
                }
            }

            // Бейджи совместимости.
            if (check is InstallCheck.Warning) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = check.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (check is InstallCheck.Blocked) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = check.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Прогресс-бар скачивания.
            installedModel?.let { model ->
                if (model.status == ModelStatus.DOWNLOADING) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { model.progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "${model.progress}%",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row {
                when {
                    installedModel == null && card.sizeBytes == 0L -> {
                        // Встроенные модели (движки TTS) не скачиваются: кнопка
                        // «Выбрать» не нужна — движок выбирается радиокнопкой
                        // слева или в настройках.
                        Text(
                            text = stringResource(R.string.models_builtin),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    installedModel == null -> {
                        Button(onClick = onDownload, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.download))
                        }
                        // Импорт уже готового файла модели (zip экспорта этой же
                        // программы или одиночный файл) вместо скачивания (issue #28).
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.FileDownload, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.models_import))
                        }
                    }
                    installedModel.status == ModelStatus.DOWNLOADING -> {
                        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.models_cancel_download))
                        }
                    }
                    else -> {
                        // Кнопка «Выбрать» осталась только у TTS (движок):
                        // модель перевода выбирается по направлению, STT — по
                        // языкам через чипы (issues #28, #29).
                        if (card.category == ModelCategory.TTS) {
                            Button(onClick = onActive, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.models_choose))
                            }
                        }
                        if (card.sizeBytes > 0L) {
                            if (card.category == ModelCategory.TTS) {
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            OutlinedButton(
                                onClick = onExport,
                                enabled = !actionsLocked,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.FileUpload, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.models_export))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = onDelete,
                                enabled = !actionsLocked,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.models_delete))
                            }
                        }
                    }
                }
            }

            // Чипы «язык распознавания»: назначение STT-модели на каждый язык
            // (issue #28). Активный чип — язык назначен этой модели; повторный
            // тап снимает назначение; тап по языку другой модели — переназначение.
            if (sttChips.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.models_stt_lang_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    sttChips.forEachIndexed { index, lang ->
                        if (index > 0) {
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        FilterChip(
                            selected = lang in sttAssignedLanguages,
                            onClick = { onSttLanguageToggle(lang) },
                            label = { Text(lang.uppercase(Locale.US)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Stars(count: Int) {
    Row {
        repeat(5) { i ->
            Icon(
                imageVector = if (i < count) Icons.Filled.Star else Icons.Outlined.Star,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (i < count) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun categoryTitle(category: ModelCategory): String = stringResource(
    when (category) {
        ModelCategory.TRANSLATION -> R.string.models_category_translation
        ModelCategory.STT -> R.string.models_category_stt
        ModelCategory.TTS -> R.string.models_category_tts
    }
)

// Пороги форматирования размера (КиБ/МиБ/ГиБ в байтах).
private const val BYTES_IN_KB = 1_024L

/** Поддерживаемые языки для чипов назначения STT-модели (issue #28). */
private val STT_CHIP_LANGUAGES = listOf("ru", "en", "ne")
private const val BYTES_IN_MB = 1_048_576L
private const val BYTES_IN_GB = 1_073_741_824L

@Composable
internal fun formatBytes(bytes: Long): String {
    val unitGb = stringResource(R.string.unit_gb)
    val unitMb = stringResource(R.string.unit_mb)
    val unitKb = stringResource(R.string.unit_kb)
    return when {
        bytes >= BYTES_IN_GB -> String.format(Locale.US, "%.1f %s", bytes / BYTES_IN_GB.toDouble(), unitGb)
        bytes >= BYTES_IN_MB -> String.format(Locale.US, "%.0f %s", bytes / BYTES_IN_MB.toDouble(), unitMb)
        else -> String.format(Locale.US, "%.0f %s", bytes / BYTES_IN_KB.toDouble(), unitKb)
    }
}
