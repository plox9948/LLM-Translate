package com.plox.llmtranslate.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plox.llmtranslate.data.backup.ModelBackupManager
import com.plox.llmtranslate.domain.DiskUsage
import com.plox.llmtranslate.domain.ModelManager
import com.plox.llmtranslate.domain.model.DeviceInfo
import com.plox.llmtranslate.domain.model.InstallCheck
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.model.ModelCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Запрос экспорта модели через SAF.
 */
data class ExportRequest(
    val modelId: String,
    val fileName: String
)

/**
 * Запрос экспорта резервной копии (все модели + настройки).
 */
data class BackupExportRequest(
    val fileName: String
)

/**
 * Состояние UI экрана «Модели».
 */
data class ModelsUiState(
    val catalog: List<ModelCard> = emptyList(),
    val installed: List<InstalledModel> = emptyList(),
    val activeIds: Map<ModelCategory, String?> = emptyMap(),
    val diskUsage: DiskUsage? = null,
    val deviceInfo: DeviceInfo? = null,
    val licenseConfirmModel: ModelCard? = null,
    val exportRequest: ExportRequest? = null,
    val backupExportRequest: BackupExportRequest? = null,
    val isBusy: Boolean = false,
    val statusMessage: String? = null
)

/**
 * ViewModel экрана «Модели».
 */
@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val modelManager: ModelManager,
    private val backupManager: ModelBackupManager
) : ViewModel() {

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo

    private val activeIdsFlow: kotlinx.coroutines.flow.Flow<Map<ModelCategory, String?>> =
        kotlinx.coroutines.flow.combine(
            modelManager.activeModelId(ModelCategory.TRANSLATION),
            modelManager.activeModelId(ModelCategory.STT),
            modelManager.activeModelId(ModelCategory.TTS)
        ) { t, s, tt ->
            mapOf(
                ModelCategory.TRANSLATION to t,
                ModelCategory.STT to s,
                ModelCategory.TTS to tt
            )
        }

    val uiState: StateFlow<ModelsUiState> = kotlinx.coroutines.flow.combine(
        modelManager.catalog,
        modelManager.installedModels(null),
        activeIdsFlow,
        modelManager.diskUsage
    ) { catalog, installed, activeIds, disk ->
        ModelsUiState(
            catalog = catalog,
            installed = installed,
            activeIds = activeIds,
            diskUsage = disk
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ModelsUiState())

    val licenseConfirmModel = MutableStateFlow<ModelCard?>(null)

    init {
        viewModelScope.launch {
            // Устройство для проверки совместимости.
            _deviceInfo.value = modelManager.deviceInfo()
        }
    }

    /** Проверить, можно ли установить, и при необходимости попросить подтверждение лицензии. */
    fun onDownloadClick(card: ModelCard) {
        val device = _deviceInfo.value ?: return
        when (val check = modelManager.checkInstall(card, device)) {
            is InstallCheck.Blocked -> {
                // Блокировка: показываем причину через snackbar в UI.
            }
            else -> {
                if (card.requiresLicenseConfirmation) {
                    licenseConfirmModel.value = card
                } else {
                    doDownload(card)
                }
            }
        }
    }

    fun confirmLicenseAndDownload() {
        licenseConfirmModel.value?.let { card ->
            licenseConfirmModel.value = null
            doDownload(card)
        }
    }

    fun cancelLicenseConfirm() {
        licenseConfirmModel.value = null
    }

    private fun doDownload(card: ModelCard) {
        modelManager.startDownload(card)
    }

    fun onDeleteClick(model: InstalledModel) {
        modelManager.delete(model.id)
    }

    fun onActiveClick(model: InstalledModel) {
        viewModelScope.launch {
            modelManager.setActive(model.category, model.id)
        }
    }

    fun onCancelDownload(model: InstalledModel) {
        modelManager.cancelDownload(model.id)
    }

    fun onImportResult(uri: android.net.Uri) {
        viewModelScope.launch {
            modelManager.importFromUri(uri)
        }
    }

    /**
     * Подготовить запрос экспорта модели (UI запустит SAF-лаунчер).
     */
    fun onExportClick(model: InstalledModel) {
        if (!model.isReady || model.filePath.isNullOrBlank()) return
        val ext = model.filePath!!.substringAfterLast('.', "").lowercase()
        val finalExt = if (ext in listOf("gguf", "onnx", "zip")) ext else "gguf"
        uiState.value = uiState.value.copy(
            exportRequest = ExportRequest(model.id, "${model.displayName}.$finalExt")
        )
    }

    /**
     * Завершить экспорт: скопировать модель в выбранный SAF-uri.
     */
    fun onExportResult(uri: android.net.Uri?) {
        val request = uiState.value.exportRequest
        uiState.value = uiState.value.copy(exportRequest = null)
        if (uri == null || request == null) return
        viewModelScope.launch {
            modelManager.exportToUri(request.modelId, uri)
        }
    }

    /**
     * Запросить экспорт резервной копии (все модели + настройки).
     */
    fun onBackupExportClick() {
        uiState.value = uiState.value.copy(
            backupExportRequest = BackupExportRequest(
                "llm_translate_backup_${System.currentTimeMillis()}.zip"
            )
        )
    }

    /**
     * Завершить экспорт бэкапа: записать ZIP в выбранный SAF-uri.
     */
    fun onBackupExportResult(uri: android.net.Uri?) {
        uiState.value = uiState.value.copy(backupExportRequest = null)
        if (uri == null) return
        viewModelScope.launch {
            uiState.value = uiState.value.copy(isBusy = true, statusMessage = "Экспорт…")
            val ok = backupManager.exportToUri(uri)
            uiState.value = uiState.value.copy(
                isBusy = false,
                statusMessage = if (ok) "Бэкап сохранён" else "Ошибка экспорта бэкапа"
            )
        }
    }

    /**
     * Импортировать резервную копию из SAF-uri.
     */
    fun onBackupImportResult(uri: android.net.Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            uiState.value = uiState.value.copy(isBusy = true, statusMessage = "Импорт…")
            val result = backupManager.importFromUri(uri)
            val msg = when {
                result.error != null -> "Ошибка: ${result.error}"
                else -> "Импортировано: ${result.importedCount}, пропущено: ${result.skippedCount}" +
                    (if (result.settingsRestored) ", настройки восстановлены" else "")
            }
            uiState.value = uiState.value.copy(isBusy = false, statusMessage = msg)
        }
    }

    fun clearStatusMessage() {
        uiState.value = uiState.value.copy(statusMessage = null)
    }

    /**
     * Проверить предупреждение о памяти при одновременной активации LLM и STT.
     */
    fun checkRamWarning(): String? {
        val device = _deviceInfo.value ?: return null
        val state = uiState.value
        val llm = state.installed.firstOrNull { it.id == state.activeIds[ModelCategory.TRANSLATION] }
        val stt = state.installed.firstOrNull { it.id == state.activeIds[ModelCategory.STT] }
        if (llm == null || stt == null) return null
        val total = (llm.ramRequiredMb + stt.ramRequiredMb).toLong()
        return if (device.isNearRamLimit(total)) {
            "Активные модели требуют ~${total} МБ ОЗУ — близко к лимиту устройства"
        } else null
    }
}
