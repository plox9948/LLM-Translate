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
import com.plox.llmtranslate.domain.model.RuntimeType
import com.plox.llmtranslate.domain.model.TtsModelSelector
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
    val observeErrorRes by viewModel.observeErrorRes.collectAsStateWithLifecycle()
    val processPssMb by viewModel.processPssMb.collectAsStateWithLifecycle()
    val ttsEngine by viewModel.ttsEngine.collectAsStateWithLifecycle()
    val sttByLanguage by viewModel.sttByLanguage.collectAsStateWithLifecycle()
    val ttsByLanguage by viewModel.ttsByLanguage.collectAsStateWithLifecycle()

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

    // SAF: точечный импорт модели по карточке каталога (GGUF/ZIP).
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
            // Сброс ДО launch(): при recreation активности с открытым пикером
            // эффект не поднимет второй пикер поверх первого — запрос живёт
            // только в лаунчере; отмена (uri == null) чистится колбэком
            // результата onBackupExportResult (issue #40).
            viewModel.consumeBackupExportRequest()
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
            // Как с бэкапом: запрос обнуляется до launch(), содержимое
            // остаётся в VM до колбэка результата (issue #40).
            viewModel.consumeExportRequest()
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

            // Баннер сбоя наблюдения Room-потоков (issue #67, тап — скрыть).
            observeErrorRes?.let { res ->
                ErrorBanner(text = stringResource(res), onClick = { viewModel.clearObserveError() })
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Баннер предупреждения об ОЗУ.
            ramWarning?.let { warning ->
                ErrorBanner(text = warning)
                Spacer(modifier = Modifier.height(8.dp))
            }
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
            val installed = uiState.installed.filter { it.category == category }
            // Карточки секции: каталог (с фильтром hidden) + синтетические
            // карточки для установленных вне каталога записей (см. helper).
            val cards = sectionCards(
                uiState.catalog.filter { it.category == category },
                installed
            )
            val activeId = uiState.activeIds[category]

            item(key = "header_$category") {
                Text(
                    text = categoryTitle(category),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
                // Подсказка про чипы языков в секции TTS (issue #54).
                if (category == ModelCategory.TTS) {
                    Text(
                        text = stringResource(R.string.models_tts_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            items(cards, key = { "card_${it.id}" }) { card ->
                val installedModel = installed.firstOrNull { it.id == card.id }
                // Подсветка TTS-карточки — по поязыковому выбору (issue #53):
                // Piper подсвечена, если назначена хотя бы одному языку своего
                // чипа; Системный Android TTS — если хотя бы один язык без
                // назначения или с сентинелем "system". Радиокнопка глобального
                // движка (настройка ttsEngine: пакет SYSTEM/RHVOICE/eSpeak)
                // живёт отдельным состоянием ttsEngineSelected и не меняется.
                val isPiperTts = card.category == ModelCategory.TTS &&
                    card.runtime == RuntimeType.PIPER_ONNX
                val isSystemTts = card.category == ModelCategory.TTS &&
                    card.runtime == RuntimeType.SYSTEM_TTS
                val ttsEngineSelected = card.runtime.ttsEngineId() != null &&
                    card.runtime.ttsEngineId() == ttsEngine
                val ttsAssignedAny = ttsChipLanguages(card).any {
                    ttsByLanguage[it] == card.id
                }
                val systemTtsActive = isSystemTts &&
                    ttsChipLanguages(card).any { lang ->
                        val assigned = ttsByLanguage[lang]
                        assigned == null || assigned == TtsModelSelector.SYSTEM_SENTINEL
                    }
                val isActive = when (card.category) {
                    // Явные скобки (ревью): && связывается сильнее ||, но
                    // группировка улучшает читаемость и безопаснее при правках.
                    ModelCategory.TTS -> (isPiperTts && ttsAssignedAny) || systemTtsActive
                    else -> activeId == card.id
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
                // Чипы «язык озвучивания» — на скачиваемых PIPER-карточках
                // и на карточке Системного Android TTS (issues #50, #53):
                // RHVOICE/eSpeak-ярлыки чипов не имеют, STT-чипы в TTS-секцию
                // не попадают (условие выше — только STT-карточки).
                val ttsChips = ttsChipLanguages(card)
                val ttsModelReady = isPiperTts && installedModel?.isReady == true
                // Чипы системной карточки кликабельны всегда — скачивать нечего.
                val ttsChipsEnabled = ttsModelReady || isSystemTts
                val ttsAssignedLanguages = if (isSystemTts) {
                    // «Выбран» для Системного TTS: язык без назначения
                    // (значение по умолчанию) или явный сентинель "system".
                    ttsChips.filter { lang ->
                        val assigned = ttsByLanguage[lang]
                        assigned == null || assigned == TtsModelSelector.SYSTEM_SENTINEL
                    }.toSet()
                } else {
                    ttsChips.filter {
                        ttsModelReady && ttsByLanguage[it] == card.id
                    }.toSet()
                }
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
                // Guard от двойного тапа «Скачать»: id уже в pending в VM —
                // кнопка неактивна, пока статус не получен из Room.
                val downloadPending = card.id in uiState.pendingDownloadIds

                ModelCardItem(
                    card = card,
                    installedModel = installedModel,
                    isActive = isActive,
                    check = check,
                    sttChips = sttChips,
                    sttAssignedLanguages = sttAssignedLanguages,
                    ttsChips = ttsChips,
                    ttsAssignedLanguages = ttsAssignedLanguages,
                    ttsChipsEnabled = ttsChipsEnabled,
                    ttsEngineSelected = ttsEngineSelected,
                    requiredChipLabel = requiredChipLabel,
                    actionsLocked = actionsLocked,
                    downloadPending = downloadPending,
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
                    onSttLanguageToggle = { lang -> viewModel.onSttLanguageToggle(card, lang) },
                    onTtsLanguageToggle = { lang -> viewModel.onTtsLanguageToggle(card, lang) }
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
 * Карточки секции категории: каталожные карточки (скрытые hidden-карточки,
 * issue #55, показываются только при наличии установленной записи — ревью #52)
 * плюс синтетические карточки для установленных записей, отсутствующих в
 * каталоге (импортные модели, ревью раунд 2): без них импортированная модель
 * не видна на экране и её нельзя удалить/экспортировать. Поля синтетической
 * карточки берутся из записи, каталожные поля — значения по умолчанию.
 * Чистая логика — вынесена для unit-теста.
 */
internal fun sectionCards(
    catalog: List<ModelCard>,
    installed: List<InstalledModel>
): List<ModelCard> {
    val installedIds = installed.map { it.id }.toSet()
    val cards = catalog.filter { !it.hidden || it.id in installedIds }.toMutableList()
    val catalogIds = catalog.map { it.id }.toSet()
    installed.forEach { model ->
        if (model.id !in catalogIds) cards.add(syntheticInstalledCard(model))
    }
    return cards
}

/** Синтетическая карточка установленной модели вне каталога (ревью раунд 2). */
private fun syntheticInstalledCard(model: InstalledModel): ModelCard = ModelCard(
    id = model.id,
    category = model.category,
    displayName = model.displayName,
    description = model.description,
    runtime = model.runtime,
    sizeBytes = model.sizeBytes,
    ramRequiredMb = model.ramRequiredMb,
    languages = model.languages,
    quality = model.quality,
    speed = model.speed,
    url = model.url,
    urls = model.urls,
    sha256 = model.sha256,
    sha256s = model.sha256s,
    license = model.license,
    minAndroidSdk = model.minAndroidSdk,
    recommendedRamMb = model.recommendedRamMb,
    hidden = false
)

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
    ttsChips: List<String>,
    ttsAssignedLanguages: Set<String>,
    ttsChipsEnabled: Boolean,
    ttsEngineSelected: Boolean,
    requiredChipLabel: String?,
    actionsLocked: Boolean,
    downloadPending: Boolean,
    onDownload: () -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit,
    onActive: () -> Unit,
    onCancel: () -> Unit,
    onExport: () -> Unit,
    onSttLanguageToggle: (String) -> Unit,
    onTtsLanguageToggle: (String) -> Unit
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
                // Радиокнопка выбранного TTS-движка (только движки-ярлыки):
                // selectable с ролью RadioButton — доступный переключатель
                // (TalkBack объявляет состояние). Скачиваемые PIPER-карточки
                // радиокнопки не имеют (issue #50): они назначаются языкам
                // чипами ниже и выбираются координатором TTS. Строка карточки
                // не кликабельна (прошлый фикс радиокнопок), выбор — только
                // этой кнопкой или в настройках. Радиокнопок перевода и STT нет:
                // модель перевода выбирается автоматически по направлению,
                // STT — по языкам (issues #28, #29).
                val canSelect = isTtsEngine || installedModel?.isReady == true
                if (isTtsEngine) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .selectable(
                                selected = ttsEngineSelected,
                                enabled = canSelect,
                                role = Role.RadioButton,
                                onClick = onActive
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (ttsEngineSelected) Icons.Filled.RadioButtonChecked
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
                    // «Выбрано» — карточка TTS активна по поязыковому выбору:
                    // Piper назначена какому-то языку, Системный TTS работает
                    // по умолчанию/явно хотя бы для одного языка (issue #53).
                    if (card.category == ModelCategory.TTS && isActive) {
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

            // Бейджи совместимости: предупреждение и блокировка рисуются
            // одинаково, различается только источник текста.
            val checkMessage = when (check) {
                is InstallCheck.Warning -> check.message
                is InstallCheck.Blocked -> check.reason
                else -> null
            }
            if (checkMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = checkMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Скачивание завершилось ошибкой: подпись ошибки на карточке
            // (без неё модель выглядела установленной), повтор — кнопка
            // «Повторить»; прогресс-бар при ERROR скрыт (не скачивается).
            if (installedModel?.status == ModelStatus.ERROR) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.models_status_error),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
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
                        Button(
                            onClick = onDownload,
                            enabled = !downloadPending,
                            modifier = Modifier.weight(1f)
                        ) {
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
                    installedModel.status == ModelStatus.ERROR -> {
                        // Повторное скачивание после ошибки — тот же запуск,
                        // что у обычного «Скачать» (без предварительного удаления).
                        Button(
                            onClick = onDownload,
                            enabled = !downloadPending,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.models_retry_download))
                        }
                        // Удаление остаётся доступным: «Экспорт» для ERROR
                        // модели бесполезен (не готова), «Удалить» — способ
                        // убрать битую запись, если повтор снова не помог.
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
                    else -> {
                        // Кнопка «Выбрать» осталась только у TTS-движков (ярлыков):
                        // модель перевода выбирается по направлению, STT и PIPER
                        // TTS — по языкам через чипы (issues #28, #29, #50).
                        if (isTtsEngine) {
                            Button(onClick = onActive, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.models_choose))
                            }
                        }
                        if (card.sizeBytes > 0L) {
                            if (isTtsEngine) {
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

            // Чипы «язык озвучивания»: назначение Piper TTS-модели на каждый
            // язык (issue #50), у карточки Системного Android TTS — явное
            // назначение системного движка (issue #53): кликабельны всегда,
            // повторный тап не снимает назначение. У Piper кликабельны только
            // у готовой модели; иначе показывается подсказка скачать.
            if (ttsChips.isNotEmpty()) {
                LanguageChipRow(
                    labelRes = R.string.models_tts_lang_label,
                    languages = ttsChips,
                    assignedLanguages = ttsAssignedLanguages,
                    enabled = ttsChipsEnabled,
                    onToggle = onTtsLanguageToggle
                )
                if (!ttsChipsEnabled) {
                    Text(
                        text = stringResource(R.string.models_tts_chip_download_first),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Чипы «язык распознавания»: назначение STT-модели на каждый язык
            // (issue #28). Активный чип — язык назначен этой модели; повторный
            // тап снимает назначение; тап по языку другой модели — переназначение.
            if (sttChips.isNotEmpty()) {
                LanguageChipRow(
                    labelRes = R.string.models_stt_lang_label,
                    languages = sttChips,
                    assignedLanguages = sttAssignedLanguages,
                    onToggle = onSttLanguageToggle
                )
            }
        }
    }
}

/**
 * Строка чипов «язык назначения» (общий каркас чипов STT и TTS): подпись,
 * FilterChip на каждый язык [languages], активный — входящий в
 * [assignedLanguages]; [enabled] — доступность чипов (по умолчанию кликабельны).
 */
@Composable
private fun LanguageChipRow(
    labelRes: Int,
    languages: List<String>,
    assignedLanguages: Set<String>,
    enabled: Boolean = true,
    onToggle: (String) -> Unit
) {
    Spacer(modifier = Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        languages.forEachIndexed { index, lang ->
            if (index > 0) {
                Spacer(modifier = Modifier.width(8.dp))
            }
            FilterChip(
                selected = lang in assignedLanguages,
                enabled = enabled,
                onClick = { onToggle(lang) },
                label = { Text(lang.uppercase(Locale.US)) }
            )
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
private const val BYTES_IN_MB = 1_048_576L
private const val BYTES_IN_GB = 1_073_741_824L

/** Поддерживаемые языки для чипов назначения STT-модели (issue #28). */
private val STT_CHIP_LANGUAGES = listOf("ru", "en", "ne")

/** Поддерживаемые языки для чипов назначения Piper TTS-модели (issue #50). */
private val TTS_CHIP_LANGUAGES = listOf("ru", "en", "ne")

/**
 * Языки чипов «язык озвучивания» карточки TTS: покрываемые карточкой RU/EN/NE.
 * Чипы есть у скачиваемых Piper-карточек (issue #50) и у карточки Системного
 * Android TTS (issue #53); у RHVOICE/eSpeak-ярлыков чипов нет.
 */
private fun ttsChipLanguages(card: ModelCard): List<String> =
    if (card.category == ModelCategory.TTS &&
        (card.runtime == RuntimeType.PIPER_ONNX || card.runtime == RuntimeType.SYSTEM_TTS)
    ) {
        TTS_CHIP_LANGUAGES.filter { it in card.languages }
    } else {
        emptyList()
    }

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
