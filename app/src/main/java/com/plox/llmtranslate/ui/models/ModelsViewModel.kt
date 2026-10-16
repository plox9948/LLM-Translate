package com.plox.llmtranslate.ui.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plox.llmtranslate.data.backup.ModelBackupManager
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.DiskUsage
import com.plox.llmtranslate.domain.ModelManager
import com.plox.llmtranslate.domain.model.DeviceInfo
import com.plox.llmtranslate.domain.model.CatalogPriority
import com.plox.llmtranslate.domain.model.InstallCheck
import com.plox.llmtranslate.domain.model.InstallCheckLogic
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.TtsEngines
import com.plox.llmtranslate.domain.model.ttsEngineId
import com.plox.llmtranslate.domain.runtime.LoadedModelInfo
import com.plox.llmtranslate.domain.runtime.ProcessMemoryReader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
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
    /** Id модели, для которой идёт запись экспорта (SAF-копирование/zip). */
    val exportingModelId: String? = null,
    val statusMessage: String? = null,
    /**
     * Карточки каталога, недостающие для последнего направления перевода (issue #33):
     * id карточки → подпись чипа («RU→EN»); каталог в [catalog] уже отсортирован
     * с их приоритетом.
     */
    val requiredChips: Map<String, String> = emptyMap()
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

    internal companion object {
        /** Интервал опроса PSS процесса, пока экран видим и в памяти есть загруженные модели. */
        internal const val PSS_POLL_INTERVAL_MS = 3_000L
    }

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo

    /**
     * Фактический PSS процесса, МБ (AMS, учитывает native-память инференса);
     * null — нет загруженных моделей, экран скрыт или чтение AMS не удалось.
     * Цикл опроса живёт в Compose (LaunchedEffect на экране «Модели»):
     * UI вызывает [pollProcessPss] по таймеру, пока экран в composition,
     * и [resetProcessPss] при уходе с экрана.
     */
    private val _processPssMb = MutableStateFlow<Int?>(null)
    val processPssMb: StateFlow<Int?> = _processPssMb

    /**
     * Выбранный движок синтеза речи (настройка ttsEngine): идентификаторы
     * из [TtsEngines]. Реактивно обновляется при смене на экране «Настройки»
     * или выбором на экране «Модели».
     */
    private val _ttsEngine = MutableStateFlow(TtsEngines.SYSTEM)
    val ttsEngine: StateFlow<String> = _ttsEngine

    /**
     * Назначения «язык → id STT-модели» (issue #28): реактивно из DataStore,
     * используется чипами языков на STT-карточках.
     */
    private val _sttByLanguage = MutableStateFlow<Map<String, String>>(emptyMap())
    val sttByLanguage: StateFlow<Map<String, String>> = _sttByLanguage

    // Глобальной активной STT-модели больше нет (issue #29): выбор по языкам,
    // в activeIds только TRANSLATION (подсветка карточки) и TTS (движок).
    private val activeIdsFlow: Flow<Map<ModelCategory, String?>> =
        combine(
            modelManager.activeModelId(ModelCategory.TRANSLATION),
            modelManager.activeModelId(ModelCategory.TTS)
        ) { t, tt ->
            mapOf(
                ModelCategory.TRANSLATION to t,
                ModelCategory.TTS to tt
            )
        }

    // Последнее направление перевода (issue #33): null — не сохранено.
    private val lastDirectionFlow: Flow<Pair<String, String>?> =
        combine(preferences.lastSourceLang, preferences.lastTargetLang) { source, target ->
            if (source != null && target != null) source to target else null
        }

    // Наружные обновления (statusMessage, exportRequest) пишутся в _uiState напрямую;
    // обновления из потоков моделей перезаписывают их — как при stateIn.
    private val _uiState = MutableStateFlow(ModelsUiState())
    val uiState: StateFlow<ModelsUiState> = _uiState

    init {
        viewModelScope.launch {
            combine(
                // Каталог с направлением: приоритизация карточек выполняется
                // вместе, чтобы сортировка не разъезжалась с потоком каталога.
                combine(modelManager.catalog, lastDirectionFlow) { cards, direction ->
                    cards to direction
                },
                modelManager.installedModels(null),
                activeIdsFlow,
                modelManager.diskUsage,
                modelManager.loadedModels
            ) { (catalog, direction), installed, activeIds, disk, loaded ->
                // Чипы «нужно для направления» — по недостающим звеньям
                // цепочки текущего направления (issue #33).
                val chips = if (direction != null && direction.first != direction.second) {
                    val installedTranslation =
                        installed.filter { it.category == ModelCategory.TRANSLATION }
                    CatalogPriority.requiredChips(
                        catalog, installedTranslation, direction.first, direction.second
                    )
                } else {
                    emptyMap()
                }
                ModelsUiState(
                    catalog = CatalogPriority.prioritize(catalog, chips),
                    requiredChips = chips,
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
                    exportingModelId = current.exportingModelId,
                    statusMessage = current.statusMessage
                )
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Устройство для проверки совместимости: чтение ActivityManager/StatFs
            // блокирует, выполняется на IO-диспетчере.
            _deviceInfo.value = modelManager.deviceInfo()
        }
        viewModelScope.launch {
            // Движок TTS — та же настройка, что на экране «Настройки».
            preferences.ttsEngine.collect { engine ->
                _ttsEngine.value = engine
            }
        }
        viewModelScope.launch {
            // Назначения STT по языкам — для подсветки чипов на карточках.
            modelManager.sttByLanguage.collect { map ->
                _sttByLanguage.value = map
            }
        }
    }

    /**
     * Один замер фактического PSS процесса и запись в [processPssMb].
     * Вызывается циклом опроса на экране «Модели» (LaunchedEffect):
     * чтение AMS идёт на IO-диспетчере, при сбое — null.
     */
    suspend fun pollProcessPss() {
        _processPssMb.value = processMemoryReader.processPssMb()
    }

    /**
     * Обнулить PSS: цикл опроса остановлен (экран «Модели» убран из
     * composition или модели выгружены из ОЗУ).
     */
    fun resetProcessPss() {
        _processPssMb.value = null
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
     * Тап по чипу языка на STT-карточке (issue #28): назначить модель языку,
     * повторный тап по активному чипу — снять назначение.
     * Если язык уже назначен другой модели — переназначение (одна модель на язык).
     */
    fun onSttLanguageToggle(card: ModelCard, language: String) {
        val current = _sttByLanguage.value[language]
        val next = if (current == card.id) null else card.id
        viewModelScope.launch {
            modelManager.setSttForLanguage(language, next)
        }
    }

    /**
     * Подготовить запрос экспорта модели (UI запустит SAF-лаунчер).
     */
    fun onExportClick(model: InstalledModel) {
        if (!model.isReady || model.filePath.isNullOrBlank()) return
        val source = File(model.filePath!!)
        val ext = model.filePath!!.substringAfterLast('.', "").lowercase()
        // Каталог модели упаковывается в zip — расширение должно быть .zip,
        // чтобы повторный импорт распознал ZIP-экспорт.
        val finalExt = when {
            ext in listOf("gguf", "onnx", "zip") -> ext
            source.isDirectory -> "zip"
            else -> "gguf"
        }
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
        // Пока идёт запись ZIP, карточка этой модели блокируется (issue #31):
        // удаление mid-экспорта обрезает архив, и импорт даёт неполную модель.
        _uiState.value = _uiState.value.copy(exportingModelId = request.modelId)
        viewModelScope.launch {
            modelManager.exportToUri(request.modelId, uri)
            _uiState.value = _uiState.value.copy(exportingModelId = null)
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
     * Проверить предупреждение о памяти при одновременном нахождении в ОЗУ
     * LLM и STT-моделей (issue #29: активной STT-модели нет — считаем по
     * фактически загруженным в память моделям).
     * Логика и текст — в [InstallCheckLogic.ramWarning].
     */
    fun checkRamWarning(): String? {
        val device = _deviceInfo.value ?: return null
        val loaded = uiState.value.loadedModels
        val llmMb = loaded.filter { it.category == ModelCategory.TRANSLATION }
            .maxOfOrNull { it.ramRequiredMb }
        val sttMb = loaded.filter { it.category == ModelCategory.STT }
            .maxOfOrNull { it.ramRequiredMb }
        return InstallCheckLogic.ramWarning(llmMb, sttMb, device)
    }
}
