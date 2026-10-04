package com.plox.llmtranslate.ui.models

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileExport
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarHalf
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.plox.llmtranslate.domain.model.InstallCheck
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import javax.inject.Inject

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

    // SAF: импорт своего GGUF.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.onImportResult(uri)
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        item {
            Text(
                text = "Модели",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
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
                Text("+ Импортировать свою модель")
            }
            Spacer(modifier = Modifier.height(8.dp))
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
                val isActive = activeId == card.id
                val check = deviceInfo?.let { viewModel.checkInstall(card, it) }

                ModelCardItem(
                    card = card,
                    installedModel = installedModel,
                    isActive = isActive,
                    check = check,
                    onDownload = { viewModel.onDownloadClick(card) },
                    onDelete = { installedModel?.let { viewModel.onDeleteClick(it) } },
                    onActive = { installedModel?.let { viewModel.onActiveClick(it) } },
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
                        text = "Занято моделями: ${formatBytes(usage.totalBytes)}",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    // Диалог подтверждения некоммерческой лицензии.
    if (licenseModel != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelLicenseConfirm() },
            title = { Text("Лицензия модели") },
            text = {
                Text(
                    "Модель «${licenseModel?.displayName}» распространяется по лицензии " +
                        "${licenseModel?.license}. Подтвердите, что вы используете её " +
                        "только в соответствии с условиями лицензии."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmLicenseAndDownload() }) {
                    Text("Скачать")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelLicenseConfirm() }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun ModelCardItem(
    card: com.plox.llmtranslate.domain.model.ModelCard,
    installedModel: InstalledModel?,
    isActive: Boolean,
    check: InstallCheck?,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onActive: () -> Unit,
    onCancel: () -> Unit,
    onExport: () -> Unit
) {
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
                // Радиокнопка активной модели.
                IconButton(
                    onClick = { if (installedModel?.isReady == true) onActive() },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = if (isActive) Icons.Filled.RadioButtonChecked
                        else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = "Активная",
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
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Звёзды качества.
                Stars(count = card.quality)
                Text(
                    text = "Качество",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Stars(count = card.speed)
                Text(
                    text = "Скорость",
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
                        text = " · ~${card.ramRequiredMb} МБ ОЗУ",
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
                    installedModel == null -> {
                        Button(onClick = onDownload, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Скачать")
                        }
                    }
                    installedModel.status == ModelStatus.DOWNLOADING -> {
                        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                            Text("Отменить")
                        }
                    }
                    else -> {
                        Button(onClick = onActive, modifier = Modifier.weight(1f)) {
                            Text("Выбрать")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.FileExport, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Экспорт")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Удалить")
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
                else Icons.Filled.StarHalf,
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

private fun categoryTitle(category: ModelCategory): String = when (category) {
    ModelCategory.TRANSLATION -> "Перевод (LLM)"
    ModelCategory.STT -> "Распознавание речи (STT)"
    ModelCategory.TTS -> "Синтез речи (TTS)"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> String.format("%.1f ГБ", bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> String.format("%.0f МБ", bytes / 1_048_576.0)
    else -> String.format("%.0f КБ", bytes / 1024.0)
}
