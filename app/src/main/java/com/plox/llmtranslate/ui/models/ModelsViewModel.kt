package com.plox.llmtranslate.ui.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plox.llmtranslate.data.backup.ModelBackupManager
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.DiskUsage
import com.plox.llmtranslate.domain.ModelManager
import com.plox.llmtranslate.domain.model.DeviceInfo
import com.plox.llmtranslate.domain.model.InstallCheck
import com.plox.llmtranslate.domain.model.InstallCheckLogic
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ttsEngineId
import com.plox.llmtranslate.domain.runtime.LoadedModelInfo
import com.plox.llmtranslate.domain.runtime.ProcessMemoryReader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
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
    val loadedModels: List<LoadedModelInfo> = emptyList(),
    val deviceInfo: DeviceInfo? = null,
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
    private val backupManager: ModelBackupManager,
    private val processMemoryReader: ProcessMemoryReader,
    private val preferences: ModelPreferences
) : ViewModel() {

    companion object {
        /** Интервал опроса PSS процесса, пока в памяти есть загруженные модели. */
        private const val PSS_POLL_INTERVAL_MS = 3_000L
    }

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo

    /**
     * Фактический PSS процесса, МБ (AMS, учитывает native-память инференса).
     * Опрашивается, только пока в ОЗУ есть загруженные модели; иначе null.
     */
    private val _processPssMb = MutableStateFlow<Int?>(null)
    val processPssMb: StateFlow<Int?> = _processPssMb

    /**
     * Выбранный движок синтеза речи (настройка ttsEngine):
     * "system" | "rhvoice" | "espeak". Реактивно обновляется при смене
     * на экране «Настройки».
     */
    private val _ttsEngine = MutableStateFlow("system")
    val ttsEngine: StateFlow<String> = _ttsEngine

    private val activeIdsFlow: Flow<Map<ModelCategory, String?>> =
        combine(
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

    // Наружные обновления (statusMessage, exportRequest) пишутся в _uiState напрямую;
    // обновления из потоков моделей перезаписывают их — как при stateIn.
    private val _uiState = MutableStateFlow(ModelsUiState())
    val uiState: StateFlow<ModelsUiState> = _uiState

    init {
        viewModelScope.launch {
            combine(
                modelManager.catalog,
                modelManager.installedModels(null),
                activeIdsFlow,
                modelManager.diskUsage,
                modelManager.loadedModels
            ) { catalog, installed, activeIds, disk, loaded ->
                ModelsUiState(
                    catalog = catalog,
                    installed = installed,
                    activeIds = activeIds,
                    diskUsage = disk,
                    loadedModels = loaded
                )
            }.collect { derived ->
                // Сохраняем транзиентные поля (статус, экспорт, busy),
                // чтобы они не терялись при обновлении Room/DataStore.
                val current = _uiState.value
                _uiState.value = derived.copy(
                    exportRequest = current.exportRequest,
                    backupExportRequest = current.backupExportRequest,
                    isBusy = current.isBusy,
                    statusMessage = current.statusMessage
                )
            }
        }
        viewModelScope.launch {
            // Устройство для проверки совместимости.
            _deviceInfo.value = modelManager.deviceInfo()
        }
        viewModelScope.launch {
            // Движок TTS — та же настройка, что на экране «Настройки».
            preferences.ttsEngine.collect { engine ->
                _ttsEngine.value = engine
            }
        }
        viewModelScope.launch {
            // Пока в ОЗУ есть модели — периодически читаем фактический PSS
            // процесса; при разгрузке обнуляем и останавливаем опрос.
            // collectLatest отменяет цикл опроса при каждом изменении списка
            // и при уничтожении ViewModel (viewModelScope) — утечек нет.
            modelManager.loadedModels.collectLatest { loaded ->
                if (loaded.isEmpty()) {
                    _processPssMb.value = null
                } else {
                    while (true) {
                        _processPssMb.value = processMemoryReader.processPssMb()
                        delay(PSS_POLL_INTERVAL_MS)
                    }
                }
            }
        }
    }

    val licenseConfirmModel = MutableStateFlow<ModelCard?>(null)

    /** Причина последней блокировки установки (баннер в UI). */
    private val _blockedReason = MutableStateFlow<String?>(null)
    val blockedReason: StateFlow<String?> = _blockedReason

    /** Проверка возможности установки. */
    fun checkInstall(card: ModelCard, deviceInfo: DeviceInfo): InstallCheck =
        modelManager.checkInstall(card, deviceInfo)

    /** Проверить, можно ли установить, и при необходимости попросить подтверждение лицензии. */
    fun onDownloadClick(card: ModelCard) {
        val device = _deviceInfo.value ?: return
        when (val check = modelManager.checkInstall(card, device)) {
            is InstallCheck.Blocked -> {
                // Блокировка: причина показывается баннером на экране.
                _blockedReason.value = check.reason
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

    /**
     * Выбрать TTS-движок по карточке секции TTS: запись в настройку ttsEngine
     * (то же значение, что выбирается на экране «Настройки»).
     */
    fun onTtsEngineSelect(card: ModelCard) {
        val engine = card.runtime.ttsEngineId() ?: return
        viewModelScope.launch { preferences.setTtsEngine(engine) }
    }

    fun onCancelDownload(model: InstalledModel) {
        modelManager.cancelDownload(model.id)
    }

    fun onImportResult(uri: Uri) {
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
        _uiState.value = _uiState.value.copy(
            exportRequest = ExportRequest(model.id, "${model.displayName}.$finalExt")
        )
    }

    /**
     * Завершить экспорт: скопировать модель в выбранный SAF-uri.
     */
    fun onExportResult(uri: Uri?) {
        val request = uiState.value.exportRequest
        _uiState.value = _uiState.value.copy(exportRequest = null)
        if (uri == null || request == null) return
        viewModelScope.launch {
            modelManager.exportToUri(request.modelId, uri)
        }
    }

    /**
     * Запросить экспорт резервной копии (все модели + настройки).
     */
    fun onBackupExportClick() {
        _uiState.value = _uiState.value.copy(
            backupExportRequest = BackupExportRequest(
                "llm_translate_backup_${System.currentTimeMillis()}.zip"
            )
        )
    }

    /**
     * Завершить экспорт бэкапа: записать ZIP в выбранный SAF-uri.
     */
    fun onBackupExportResult(uri: Uri?) {
        _uiState.value = _uiState.value.copy(backupExportRequest = null)
        if (uri == null) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true, statusMessage = "Экспорт…")
            val ok = backupManager.exportToUri(uri)
            _uiState.value = _uiState.value.copy(
                isBusy = false,
                statusMessage = if (ok) "Бэкап сохранён" else "Ошибка экспорта бэкапа"
            )
        }
    }

    /**
     * Импортировать резервную копию из SAF-uri.
     */
    fun onBackupImportResult(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true, statusMessage = "Импорт…")
            val result = backupManager.importFromUri(uri)
            val msg = when {
                result.error != null -> "Ошибка: ${result.error}"
                else -> "Импортировано: ${result.importedCount}, пропущено: ${result.skippedCount}" +
                    (if (result.settingsRestored) ", настройки восстановлены" else "")
            }
            _uiState.value = _uiState.value.copy(isBusy = false, statusMessage = msg)
        }
    }

    fun clearStatusMessage() {
        _uiState.value = _uiState.value.copy(statusMessage = null)
    }

    /** Скрыть баннер причины блокировки установки. */
    fun clearBlockedReason() {
        _blockedReason.value = null
    }

    /**
     * Проверить предупреждение о памяти при одновременной активации LLM и STT.
     * Логика и текст — в [InstallCheckLogic.ramWarning].
     */
    fun checkRamWarning(): String? {
        val device = _deviceInfo.value ?: return null
        val state = uiState.value
        val llm = state.installed.firstOrNull { it.id == state.activeIds[ModelCategory.TRANSLATION] }
        val stt = state.installed.firstOrNull { it.id == state.activeIds[ModelCategory.STT] }
        return InstallCheckLogic.ramWarning(llm?.ramRequiredMb, stt?.ramRequiredMb, device)
    }
}
