package com.plox.llmtranslate.ui.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plox.llmtranslate.R
import com.plox.llmtranslate.domain.model.InstallCheck
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.ttsEngineId
import com.plox.llmtranslate.domain.runtime.LoadedModelInfo
import java.util.Locale

/**
 * Экран «Модели»: три секции по категориям, карточки, скачивание, импорт.
 */
@Composable
fun ModelsScreen(
    viewModel: ModelsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val deviceInfo by viewModel.deviceInfo.collectAsState()
    val licenseModel by viewModel.licenseConfirmModel.collectAsState()
    val blockedReason by viewModel.blockedReason.collectAsState()
    val processPssMb by viewModel.processPssMb.collectAsState()
    val ttsEngine by viewModel.ttsEngine.collectAsState()

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

    // Предупреждение о нехватке ОЗУ при одновременной активации LLM и STT.
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

            // Статусное сообщение.
            if (uiState.statusMessage != null || uiState.isBusy) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
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

                ModelCardItem(
                    card = card,
                    installedModel = installedModel,
                    isActive = isActive,
                    check = check,
                    onDownload = { viewModel.onDownloadClick(card) },
                    onDelete = { installedModel?.let { viewModel.onDeleteClick(it) } },
                    onActive = {
                        if (card.category == ModelCategory.TTS) {
                            viewModel.onTtsEngineSelect(card)
                        } else {
                            installedModel?.let { viewModel.onActiveClick(it) }
                        }
                    },
                    onCancel = { installedModel?.let { viewModel.onCancelDownload(it) } },
                    onExport = { installedModel?.let { viewModel.onExportClick(it) } }
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
                Row {
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

@Composable
private fun ModelCardItem(
    card: ModelCard,
    installedModel: InstalledModel?,
    isActive: Boolean,
    check: InstallCheck?,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onActive: () -> Unit,
    onCancel: () -> Unit,
    onExport: () -> Unit
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
                // Радиокнопка активной модели / выбранного TTS-движка.
                IconButton(
                    onClick = {
                        if (isTtsEngine || installedModel?.isReady == true) onActive()
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = if (isActive) Icons.Filled.RadioButtonChecked
                        else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = stringResource(R.string.models_active_cd),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
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
                    }
                    installedModel.status == ModelStatus.DOWNLOADING -> {
                        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.models_cancel_download))
                        }
                    }
                    else -> {
                        Button(onClick = onActive, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.models_choose))
                        }
                        if (card.sizeBytes > 0L) {
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.FileUpload, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.models_export))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Delete, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.models_delete))
                            }
                        }
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
                imageVector = if (i < count) Icons.Filled.Star
                else Icons.AutoMirrored.Filled.StarHalf,
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
private const val BYTES_IN_MB = 1_048_576L
private const val BYTES_IN_GB = 1_073_741_824L

@Composable
private fun formatBytes(bytes: Long): String {
    val unitGb = stringResource(R.string.unit_gb)
    val unitMb = stringResource(R.string.unit_mb)
    val unitKb = stringResource(R.string.unit_kb)
    return when {
        bytes >= BYTES_IN_GB -> String.format(Locale.US, "%.1f %s", bytes / BYTES_IN_GB.toDouble(), unitGb)
        bytes >= BYTES_IN_MB -> String.format(Locale.US, "%.0f %s", bytes / BYTES_IN_MB.toDouble(), unitMb)
        else -> String.format(Locale.US, "%.0f %s", bytes / BYTES_IN_KB.toDouble(), unitKb)
    }
}
