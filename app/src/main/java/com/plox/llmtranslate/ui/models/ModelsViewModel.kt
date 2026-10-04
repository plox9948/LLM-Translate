package com.plox.llmtranslate.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 * Состояние UI экрана «Модели».
 */
data class ModelsUiState(
    val catalog: List<ModelCard> = emptyList(),
    val installed: List<InstalledModel> = emptyList(),
    val activeIds: Map<ModelCategory, String?> = emptyMap(),
    val diskUsage: DiskUsage? = null,
    val deviceInfo: DeviceInfo? = null,
    val licenseConfirmModel: ModelCard? = null
)

/**
 * ViewModel экрана «Модели».
 */
@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val modelManager: ModelManager
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
