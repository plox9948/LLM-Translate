package com.plox.llmtranslate.ui.models

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plox.llmtranslate.R
import com.plox.llmtranslate.data.backup.ModelBackupManager
import com.plox.llmtranslate.data.backup.ModelImportLock
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
import com.plox.llmtranslate.domain.model.ModelFileNaming
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import com.plox.llmtranslate.domain.model.TranslationModelSelector
import com.plox.llmtranslate.domain.model.TtsEngines
import com.plox.llmtranslate.domain.model.TtsModelSelector
import com.plox.llmtranslate.domain.model.ttsEngineId
import com.plox.llmtranslate.domain.runtime.LoadedModelInfo
import com.plox.llmtranslate.domain.runtime.ProcessMemoryReader
import com.plox.llmtranslate.ui.ObserveErrorTracker
import com.plox.llmtranslate.ui.launchGuarded
import com.plox.llmtranslate.ui.observeResilient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
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
    /**
     * Pending-запрос запуска SAF-пикера восстановления резервной копии
     * (ревью #83): live-значение только до запуска пикера — пикер запускается
     * эффектом ПОСЛЕ снятия запроса (паттерн экспорта, issue #40), двойной
     * тап не поднимает второй пикер и не запускает второй импорт.
     */
    val backupRestoreRequest: Boolean = false,
    /**
     * Pending-запрос запуска SAF-пикера точечного импорта модели
     * (ревью #83), та же семантика, что у [backupRestoreRequest].
     */
    val modelImportRequest: Boolean = false,
    val isBusy: Boolean = false,
    /** Id модели, для которой идёт запись экспорта (SAF-копирование/zip). */
    val exportingModelId: String? = null,
    val statusMessage: String? = null,
    /**
     * Id карточек с уже запущенным (но ещё не подтверждённым Room-статусом)
     * скачиванием — guard от двойного тапа «Скачать»/«Повторить»: кнопки для
     * этих id неактивны, пока статус не получен из Room или запуск не снят
     * (блокировка установки, отказ от лицензии).
     */
    val pendingDownloadIds: Set<String> = emptySet(),
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
    private val preferences: ModelPreferences,
    // Прикладной контекст — только для локализованных статусных сообщений
    // импорта/экспорта (значения из ресурсов, утечки нет).
    @ApplicationContext private val context: Context,
    // App-scope (поставщик DomainModule, без квалификатора): долгая SAF-работа
    // (импорт/экспорт модели и бэкапа) запускается здесь, а не в viewModelScope —
    // уход с вкладки «Модели» (popUpTo(saveState) в MainScreen) уничтожает
    // ViewModel, и viewModelScope отменил бы операцию посреди подмены файлов
    // пучка (data-слой теперь пробрасывает CancellationException, а не
    // дорабатывает под NonCancellable). Операция, начатая до уничтожения VM,
    // завершается атомарно в app-скоупе; collect статусов остаётся в VM.
    // Ограничение (минимальная мера, осознанно): статусное сообщение операции
    // не переживает recreation — после возвращения на вкладку виден только
    // терминальный результат из Room-потоков (установленная модель/ошибка
    // записи), SavedStateHandle для transient-статуса избыточен.
    private val appScope: CoroutineScope
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

    /**
     * Назначения «язык → id Piper TTS-модели» (issue #50): реактивно из
     * DataStore, используется чипами языков на PIPER-карточках секции TTS.
     */
    private val _ttsByLanguage = MutableStateFlow<Map<String, String>>(emptyMap())
    val ttsByLanguage: StateFlow<Map<String, String>> = _ttsByLanguage

    /**
     * Назначения «направление → id модели перевода» (issue #96): реактивно из
     * DataStore, используется чипами направлений на TRANSLATION-карточках.
     */
    private val _translationByDirection = MutableStateFlow<Map<String, String>>(emptyMap())
    val translationByDirection: StateFlow<Map<String, String>> = _translationByDirection

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

    // Сообщение сбоя наблюдения Room-потоков — id строкового ресурса (issue #67):
    // sticky до скрытия баннером в UI. Трекер множества ошибок (ревью):
    // восстановление одного источника не гасит баннер при мёртвом другом.
    private val observeErrors = ObserveErrorTracker()
    val observeErrorRes: StateFlow<Int?> = observeErrors.state

    init {
        // issue #67: combine-цепочка с Room-источниками (каталог, installed,
        // diskUsage) наблюдалась без обработки исключений — сбой Room ронял
        // процесс; observeResilient логирует, показывает ошибку и перезапускает
        // всю цепочку combine после сбоя (обоснование — в его KDoc).
        observeResilient(
            scope = viewModelScope,
            source = {
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
                }
            },
            messageRes = R.string.models_observe_failed,
            errors = observeErrors,
            marker = "ui_state_chain",
        ) { derived ->
            // Сохраняем транзиентные поля (статус, экспорт, busy),
            // чтобы они не терялись при обновлении Room/DataStore.
            val current = _uiState.value
            // Pending-скачивания снимаются, когда Room подтвердил запуск
            // (DOWNLOADING) или отказ (ERROR) — карточка переключилась на
            // «Отменить»/«Повторить», guard больше не нужен.
            val pending = current.pendingDownloadIds.filter { id ->
                derived.installed.none {
                    it.id == id &&
                        (it.status == ModelStatus.DOWNLOADING || it.status == ModelStatus.ERROR)
                }
            }.toSet()
            _uiState.value = derived.copy(
                pendingDownloadIds = pending,
                exportRequest = current.exportRequest,
                backupExportRequest = current.backupExportRequest,
                backupRestoreRequest = current.backupRestoreRequest,
                modelImportRequest = current.modelImportRequest,
                isBusy = current.isBusy,
                exportingModelId = current.exportingModelId,
                statusMessage = current.statusMessage
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Устройство для проверки совместимости: чтение ActivityManager/StatFs
            // блокирует, выполняется на IO-диспетчере.
            _deviceInfo.value = modelManager.deviceInfo()
        }
        // Прямые подписки «поток → StateFlow» (ревью #4): назначения STT/TTS по
        // языкам — те же Room/DataStore-источники, что на экране «Перевод», и
        // наблюдаются устойчиво (сбой источника не роняет процесс); движок TTS —
        // та же настройка, что на экране «Настройки».
        observeResilient(
            scope = viewModelScope,
            source = { preferences.ttsEngine },
            messageRes = R.string.models_tts_engine_observe_failed,
            errors = observeErrors,
            marker = "tts_engine"
        ) { value -> _ttsEngine.value = value }
        observeResilient(
            scope = viewModelScope,
            source = { modelManager.sttByLanguage },
            messageRes = R.string.models_observe_failed,
            errors = observeErrors,
            marker = "stt_by_language"
        ) { value -> _sttByLanguage.value = value }
        observeResilient(
            scope = viewModelScope,
            source = { modelManager.ttsByLanguage },
            messageRes = R.string.models_observe_failed,
            errors = observeErrors,
            marker = "tts_by_language"
        ) { value -> _ttsByLanguage.value = value }
        observeResilient(
            scope = viewModelScope,
            source = { modelManager.translationByDirection },
            messageRes = R.string.models_observe_failed,
            errors = observeErrors,
            marker = "translation_by_direction"
        ) { value -> _translationByDirection.value = value }
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

    /** Карточка модели, ожидающая подтверждения лицензии (диалог в UI). */
    private val _licenseConfirmModel = MutableStateFlow<ModelCard?>(null)
    val licenseConfirmModel: StateFlow<ModelCard?> = _licenseConfirmModel

    /** Причина последней блокировки установки (баннер в UI). */
    private val _blockedReason = MutableStateFlow<String?>(null)
    val blockedReason: StateFlow<String?> = _blockedReason

    /** Проверка возможности установки. */
    fun checkInstall(card: ModelCard, deviceInfo: DeviceInfo): InstallCheck =
        modelManager.checkInstall(card, deviceInfo)

    /** Проверить, можно ли установить, и при необходимости попросить подтверждение лицензии. */
    fun onDownloadClick(card: ModelCard) {
        // Guard от двойного тапа: id добавляется в pending до проверок и запуска
        // скачивания, кнопка для pending-id неактивна; ExistingWorkPolicy в
        // домене защищает и без этого, но UI не должен дёргать applyInstallCheck
        // повторно. Pending снимается при статусе из Room (см. подписку init),
        // при блокировке установки или отказе от лицензии.
        if (card.id in _uiState.value.pendingDownloadIds) return
        _uiState.update { it.copy(pendingDownloadIds = it.pendingDownloadIds + card.id) }
        // Устройство для проверки совместимости: перечитывается при каждом тапе —
        // свободное место и память меняются, пока жив процесс, кэшированное
        // значение устаревает. Чтение ActivityManager/StatFs блокирует — только
        // ОН выполнятся на IO; правки состояния (pending, license-диалог,
        // blockedReason) — на Main.immediate: RMW _uiState из IO-корутины
        // гонялся с Main-коллектором Room-цепочки и терял transient-поля,
        // оставляя залипшую кнопку «Скачать» (ревью #83).
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                modelManager.deviceInfo().also { _deviceInfo.value = it }
            }
            applyInstallCheck(card, loaded)
        }
    }

    /** Снять guard «скачивание запущено» с карточки. */
    private fun clearDownloadPending(cardId: String) {
        _uiState.update { it.copy(pendingDownloadIds = it.pendingDownloadIds - cardId) }
    }

    private fun applyInstallCheck(card: ModelCard, device: DeviceInfo) {
        when (val check = modelManager.checkInstall(card, device)) {
            is InstallCheck.Blocked -> {
                // Блокировка: причина показывается баннером на экране;
                // скачивание не запускалось — guard снимаем сразу.
                _blockedReason.value = check.reason
                clearDownloadPending(card.id)
            }
            else -> {
                if (card.requiresLicenseConfirmation) {
                    // Перезапись диалога лицензирования другой карточкой:
                    // скачивание прежней не запускалось и уже не запустится —
                    // снимаем её pending, иначе кнопка «Скачать» залипнет
                    // заблокированной навсегда (ревью #83).
                    _licenseConfirmModel.value?.takeIf { it.id != card.id }?.let { previous ->
                        clearDownloadPending(previous.id)
                    }
                    _licenseConfirmModel.value = card
                } else {
                    doDownload(card)
                }
            }
        }
    }

    fun confirmLicenseAndDownload() {
        _licenseConfirmModel.value?.let { card ->
            _licenseConfirmModel.value = null
            doDownload(card)
        }
    }

    fun cancelLicenseConfirm() {
        // Подтверждение отменено — скачивание не запущено, guard снимаем.
        _licenseConfirmModel.value?.let { card -> clearDownloadPending(card.id) }
        _licenseConfirmModel.value = null
    }

    private fun doDownload(card: ModelCard) {
        modelManager.startDownload(card)
    }

    /**
     * Id установленного пучка, ожидающего подтверждения удаления (решение
     * пользователя: удаление необратимо — один тап по «Удалить» больше не
     * стирает файлы). Тап по «Удалить» только поднимает pending; удаление
     * выполняет [onDeleteConfirmed] из диалога (гейт проверяется unit-тестом).
     */
    private val _pendingDeleteModelId = MutableStateFlow<String?>(null)
    val pendingDeleteModelId: StateFlow<String?> = _pendingDeleteModelId

    /**
     * Счётчик владельцев занятого флага isBusy (финальное ревью): каждая
     * живая операция (экспорт/импорт бэкапа) владеет своей единицей —
     * синхронно (главный поток SAF-колбэка) инкрементируется при захвате
     * занятости и декрементируется в finally только «своей» операцией;
     * isBusy сбрасывается лишь при исчерпании владельцев (иначе младшая
     * операция снимала бы флаг при живой старшей). Все обращения — с главного
     * потока (колбэки SAF); app-скоуп корутины обращаются к счётчику только
     * из withContext(Main.immediate).
     */
    private var busyOwners = 0

    /** Тап «Удалить»: пометить модель ожидающей подтверждения (без удаления). */
    fun onDeleteClick(model: InstalledModel) {
        _pendingDeleteModelId.value = model.id
    }

    /** Скрыть диалог подтверждения удаления без удаления. */
    fun cancelDeleteConfirm() {
        _pendingDeleteModelId.value = null
    }

    /**
     * Подтверждение удаления в диалоге: удалить пучок (файл + запись).
     * pending снимается, если он относится к этой же id.
     */
    fun onDeleteConfirmed(modelId: String) {
        if (_pendingDeleteModelId.value == modelId) _pendingDeleteModelId.value = null
        modelManager.delete(modelId)
    }

    fun onActiveClick(model: InstalledModel) {
        // Сбой Room/DataStore в операции назначения (getById + edit) не роняет
        // процесс: лог + sticky-баннер трекера (ревью #83).
        launchGuarded(
            scope = viewModelScope,
            messageRes = R.string.models_assignment_failed,
            errors = observeErrors,
            logMessage = "Не удалось назначить активную модель ${model.id}",
            marker = "set_active_model"
        ) { modelManager.setActive(model.category, model.id) }
    }

    /**
     * Выбрать TTS-движок по карточке секции TTS: запись в настройку ttsEngine
     * (то же значение, что выбирается на экране «Настройки»).
     */
    fun onTtsEngineSelect(card: ModelCard) {
        val engine = card.runtime.ttsEngineId() ?: return
        launchGuarded(
            scope = viewModelScope,
            messageRes = R.string.settings_write_failed,
            errors = observeErrors,
            logMessage = "Не удалось сохранить выбор движка синтеза речи",
            marker = "select_tts_engine"
        ) { preferences.setTtsEngine(engine) }
    }

    fun onCancelDownload(model: InstalledModel) {
        modelManager.cancelDownload(model.id)
    }

    /** Pending-гейт импорта модели (re-review): живой SAF-колбэк уже принят. */
    private var modelImportInFlight = false

    /**
     * Точечный импорт модели из SAF-файла. Работает в [appScope] под
     * [launchGuarded] (ревью аудита): неизвестный сбой IO не роняет процесс —
     * banner трекера; начатый импорт не отменяется уходом с вкладки
     * (см. KDoc [appScope]). Pending-гейт по образцу [onBackupExportClick]
     * (re-review, одинаковая защитная семантика): повторный/задвоенный
     * колбэк пикера не запускает второй импорт поверх живого; гейт
     * ставится/снимается только с главного потока.
     */
    fun onImportResult(uri: Uri) {
        if (modelImportInFlight) return
        modelImportInFlight = true
        launchGuarded(
            scope = appScope,
            messageRes = R.string.models_import_failed,
            errors = observeErrors,
            logMessage = "Не удалось импортировать модель из SAF",
            marker = "import_model"
        ) {
            try {
                // Результат импорта показываем статусным сообщением, как в
                // бэкап-ветках: молчаливый null при ошибке не оставлял UI без ответа.
                // Правки UI-состояния — обратно на Main (app-скоуп работает на IO;
                // гонка RMW с Main-коллектором Room-цепочки — см. onDownloadClick).
                val ok = modelManager.importFromUri(uri) != null
                withContext(Dispatchers.Main.immediate) {
                    _uiState.update {
                        it.copy(
                            statusMessage = statusResult(
                                ok = ok,
                                doneRes = R.string.models_import_done,
                                failedRes = R.string.models_import_failed
                            )
                        )
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    modelImportInFlight = false
                }
            }
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
        launchGuarded(
            scope = viewModelScope,
            messageRes = R.string.models_assignment_failed,
            errors = observeErrors,
            logMessage = "Не удалось обновить STT-назначение языка $language",
            marker = "set_stt_language"
        ) { modelManager.setSttForLanguage(language, next) }
    }

    /**
     * Тап по чипу языка на TTS-карточке (issues #50, #53).
     * PIPER-карточка: назначить модель озвучивания языку, повторный тап по
     * активному чипу — снять назначение (язык возвращается на Системный TTS).
     * Если язык уже назначен другой модели — переназначение (одна модель на язык).
     * Карточка Системного Android TTS: тап назначает языку явный сентинель
     * [TtsModelSelector.SYSTEM_SENTINEL] (идемпотентно — повторный тап чип
     * не «выключает»).
     * Назначение готовой модели проверяет [ModelManager.setTtsForLanguage].
     */
    fun onTtsLanguageToggle(card: ModelCard, language: String) {
        val next = if (card.runtime == RuntimeType.SYSTEM_TTS) {
            TtsModelSelector.SYSTEM_SENTINEL
        } else {
            val current = _ttsByLanguage.value[language]
            if (current == card.id) null else card.id
        }
        launchGuarded(
            scope = viewModelScope,
            messageRes = R.string.models_assignment_failed,
            errors = observeErrors,
            logMessage = "Не удалось обновить TTS-назначение языка $language",
            marker = "set_tts_language"
        ) { modelManager.setTtsForLanguage(language, next) }
    }

    /**
     * Тап по чипу направления на TRANSLATION-карточке (issue #96): назначить
     * модель перевода направлению source→target, повторный тап по чипу с
     * ЯВНЫМ назначением этой модели — снять назначение (направление
     * возвращается на автоподбор по направлению). Если направление уже
     * назначено другой модели — переназначение (одна модель на направление).
     * Установку, категорию и готовность модели проверяет
     * [ModelManager.setTranslationForDirection].
     */
    fun onTranslationDirectionToggle(card: ModelCard, source: String, target: String) {
        val key = TranslationModelSelector.directionKey(source, target)
        val current = _translationByDirection.value[key]
        val next = if (current == card.id) null else card.id
        launchGuarded(
            scope = viewModelScope,
            messageRes = R.string.models_assignment_failed,
            errors = observeErrors,
            logMessage = "Не удалось обновить переводное назначение $source→$target",
            marker = "set_translation_direction"
        ) { modelManager.setTranslationForDirection(source, target, next) }
    }

    /**
     * Pending-запрос экспорта модели: live-копия хранится в
     * [ModelsUiState.exportRequest] только до запуска SAF-пикера, далее —
     * здесь (до колбэка результата), чтобы recreation активности с открытым
     * пикером не перезапускала эффект и второй пикер не вставал поверх
     * первого (issue #40).
     */
    private var pendingExportRequest: ExportRequest? = null

    /**
     * Подготовить запрос экспорта модели (UI запустит SAF-лаунчер).
     */
    fun onExportClick(model: InstalledModel) {
        if (!model.isReady) return
        val path = model.filePath?.takeIf { it.isNotBlank() } ?: return
        val source = File(path)
        // Имя экспорта — из фактического расширения файла/ displayName
        // (каталог → .zip): выдуманное «.gguf» для путей без расширения
        // импорт отвергал (формат мёртв), round-trip экспорт→импорт own-моделей
        // был сломан (ревью раунд 9). Правила — [ModelFileNaming.exportFileName].
        val request = ExportRequest(
            model.id,
            ModelFileNaming.exportFileName(path, model.displayName, source.isDirectory)
        )
        pendingExportRequest = request
        // exportingModelId ставится сразу: карточка заблокирована на всё время
        // выбора цели в пикере и записи ZIP (issue #31), даже когда
        // exportRequest уже обнулён после запуска пикера.
        _uiState.value = _uiState.value.copy(
            exportRequest = request,
            exportingModelId = model.id
        )
    }

    /**
     * Обнучить показанный запрос экспорта — вызывается UI сразу после launch()
     * пикера; содержимое запроса остаётся в [pendingExportRequest] до колбэка.
     */
    fun consumeExportRequest() {
        _uiState.value = _uiState.value.copy(exportRequest = null)
    }

    /**
     * Обнучить показанный запрос экспорта бэкапа — сразу после launch()
     * пикера (см. [consumeExportRequest]).
     */
    fun consumeBackupExportRequest() {
        _uiState.value = _uiState.value.copy(backupExportRequest = null)
    }

    /**
     * Тап «Восстановить»: пометить запрос выбора ZIP в пикере (ревью #83).
     * Пикер запускает эффект на [ModelsUiState.backupRestoreRequest] и снимает
     * запрос ДО launch() — двойной тап не поднимает второй пикер и не
     * запускает повторный импорт архива.
     */
    fun onBackupRestoreClick() {
        _uiState.update { it.copy(backupRestoreRequest = true) }
    }

    /** Снять показанный запрос пикера восстановления (сразу после launch()). */
    fun consumeBackupRestoreRequest() {
        _uiState.update { it.copy(backupRestoreRequest = false) }
    }

    /**
     * Тап «Импорт» на карточке: пометить запрос выбора файла модели в пикере
     * (ревью #83), тот же паттерн request-in-state, что у
     * [onBackupRestoreClick].
     */
    fun onModelImportClick() {
        _uiState.update { it.copy(modelImportRequest = true) }
    }

    /** Снять показанный запрос пикера импорта (сразу после launch()). */
    fun consumeModelImportRequest() {
        _uiState.update { it.copy(modelImportRequest = false) }
    }

    /**
     * Завершить экспорт: скопировать модель в выбранный SAF-uri.
     */
    fun onExportResult(uri: Uri?) {
        val request = pendingExportRequest
        pendingExportRequest = null
        // Отмена пикера (uri == null) тоже чистит состояние: флаг блокировки
        // карточки снимается, запрос сброшен.
        _uiState.value = _uiState.value.copy(exportRequest = null, exportingModelId = null)
        if (uri == null) return
        if (request == null) {
            // Цель выбрана, но live-запрос потерян (смерть процесса с открытым
            // пикером) — копировать нечего: сообщаем об отмене вместо тихого
            // игнора.
            _uiState.value = _uiState.value.copy(
                statusMessage = context.getString(R.string.models_export_cancelled)
            )
            return
        }
        // Пока идёт запись ZIP, карточка этой модели блокируется (issue #31):
        // удаление mid-экспорта обрезает архив, и импорт даёт неполную модель.
        // Флаг блокировки ставится синхронно (колбэк SAF — на Main), запись
        // ZIP — в [appScope] под guard'ом (ревью аудита): сбой IO не роняет
        // процесс, начатый экспорт не отменяется уничтожением VM (KDoc [appScope]).
        _uiState.update { it.copy(exportingModelId = request.modelId) }
        launchGuarded(
            scope = appScope,
            messageRes = R.string.models_export_failed,
            errors = observeErrors,
            logMessage = "Не удалось экспортировать модель ${request.modelId} в SAF",
            marker = "export_model"
        ) {
            try {
                val ok = modelManager.exportToUri(request.modelId, uri)
                withContext(Dispatchers.Main.immediate) {
                    _uiState.update {
                        it.copy(
                            exportingModelId = null,
                            statusMessage = statusResult(
                                ok = ok,
                                doneRes = R.string.models_export_done,
                                failedRes = R.string.models_export_failed
                            )
                        )
                    }
                }
            } finally {
                // Разблокировка карточки при любом исходе (ошибка/отмена) —
                // иначе залипшая блокировка кнопки «Удалить» до перезахода.
                // Правки — только под Main.immediate (re-review): RMW из
                // app-скоупа (IO) гонялся с Main-коллектором Room-цепочки.
                withContext(Dispatchers.Main.immediate) {
                    if (_uiState.value.exportingModelId == request.modelId) {
                        _uiState.update { it.copy(exportingModelId = null) }
                    }
                }
            }
        }
    }

    /**
     * Запросить экспорт резервной копии (все модели + настройки).
     * Pending-гейт по образцу [onBackupRestoreClick] (request-in-state):
     * пока незапущенный запрос активен, повторный тап его не перетирает —
     * двойной тап не поднимает второй CreateDocument-пикер.
     */
    fun onBackupExportClick() {
        if (_uiState.value.backupExportRequest != null) return
        _uiState.value = _uiState.value.copy(
            backupExportRequest = BackupExportRequest(
                "llm_translate_backup_${System.currentTimeMillis()}.zip"
            )
        )
    }

    /**
     * Завершить экспорт бэкапа: записать ZIP в выбранный SAF-uri.
     * Работа — в [appScope] под [launchGuarded] (ревью аудита): неизвестный
     * сбой IO не роняет процесс; начатый экспорт не отменяется уходом с
     * вкладки (KDoc [appScope]). Занятость захватывается СИНХРОННО на
     * главном потоке (re-review) и снимается в finally только «своей»
     * единицей владельца ([busyOwners]) — иначе сбой оставил бы все карточки
     * заблокированными, а гонка с другой занятой операцией сняла бы её
     * флаг. Все мутации _uiState из app-скоуп — под Main.immediate
     * (RMW-гонка с Main-коллектором Room-цепочки, см. onDownloadClick).
     */
    fun onBackupExportResult(uri: Uri?) {
        _uiState.update { it.copy(backupExportRequest = null) }
        if (uri == null) return
        busyOwners++
        _uiState.update {
            it.copy(
                isBusy = true,
                statusMessage = context.getString(R.string.models_backup_export_in_progress)
            )
        }
        launchGuarded(
            scope = appScope,
            messageRes = R.string.models_backup_export_failed,
            errors = observeErrors,
            logMessage = "Не удалось экспортировать бэкап в SAF",
            marker = "backup_export"
        ) {
            try {
                val ok = backupManager.exportToUri(uri)
                withContext(Dispatchers.Main.immediate) {
                    _uiState.update {
                        it.copy(
                            statusMessage = statusResult(
                                ok = ok,
                                doneRes = R.string.models_backup_export_done,
                                failedRes = R.string.models_backup_export_failed
                            )
                        )
                    }
                }
            } finally {
                withContext(Dispatchers.Main.immediate) {
                    busyOwners = max(0, busyOwners - 1)
                    if (busyOwners == 0) {
                        _uiState.update { it.copy(isBusy = false) }
                    }
                }
            }
        }
    }

    /**
     * Импортировать резервную копию из SAF-uri. Работа — в [appScope] под
     * [launchGuarded] (ревью аудита): unknown-сбой не роняет процесс, а
     * начатый импорт завершает подмену файлов пучков атомарно даже после
     * уничтожения ViewModel (KDoc [appScope], [ModelImportLock] в data-слое).
     * Re-review: гейт занятости проверяется и выставляется СИНХРОННО на
     * главном потоке до запуска корутины — повторный/задвоенный колбэк
     * пикера не запускает второй импорт поверх живого; finally снимает
     * занятость только «своей» единицей владельца ([busyOwners]). Мутации
     * _uiState из app-скоуп — под Main.immediate (RMW-гонка с
     * Main-коллектором Room-цепочки).
     */
    fun onBackupImportResult(uri: Uri?) {
        if (uri == null) return
        // Синхронный гейт (главный поток колбэка): live-импорт/экспорт уже
        // занят — второй импорт не поднимается.
        if (_uiState.value.isBusy) return
        busyOwners++
        _uiState.update {
            it.copy(
                isBusy = true,
                statusMessage = context.getString(R.string.models_backup_import_in_progress)
            )
        }
        launchGuarded(
            scope = appScope,
            messageRes = R.string.models_backup_import_failed,
            errors = observeErrors,
            logMessage = "Не удалось импортировать бэкап из SAF",
            marker = "backup_import"
        ) {
            try {
                val result = backupManager.importFromUri(uri)
                val msg = (
                    when {
                        result.error != null ->
                            context.getString(R.string.models_backup_import_error, result.error)
                        result.settingsRestored -> context.getString(
                            R.string.models_backup_import_done_settings,
                            result.importedCount,
                            result.skippedCount
                        )
                        else -> context.getString(
                            R.string.models_backup_import_done,
                            result.importedCount,
                            result.skippedCount
                        )
                    }
                    ) + if (result.downloadsInterrupted > 0) {
                    // Ревью повт. аудита, п.7: импорт отменил живые загрузки и
                    // не заменил их извлечёнными моделями — потери не должны
                    // быть молчаливыми.
                    "\n" + context.getString(R.string.models_backup_import_download_interrupted)
                } else {
                    ""
                }
                withContext(Dispatchers.Main.immediate) {
                    _uiState.update { it.copy(statusMessage = msg) }
                }
            } finally {
                withContext(Dispatchers.Main.immediate) {
                    busyOwners = max(0, busyOwners - 1)
                    if (busyOwners == 0) {
                        _uiState.update { it.copy(isBusy = false) }
                    }
                }
            }
        }
    }

    fun clearStatusMessage() {
        _uiState.value = _uiState.value.copy(statusMessage = null)
    }

    /** Локализованная статусная строка «успех/отказ» операции импорта/экспорта. */
    private fun statusResult(
        ok: Boolean,
        @StringRes doneRes: Int,
        @StringRes failedRes: Int
    ): String = context.getString(if (ok) doneRes else failedRes)

    /** Скрыть баннер причины блокировки установки. */
    fun clearBlockedReason() {
        _blockedReason.value = null
    }

    /** Скрыть баннер сбоя наблюдения Room-потоков. */
    fun clearObserveError() {
        observeErrors.dismissAll()
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
