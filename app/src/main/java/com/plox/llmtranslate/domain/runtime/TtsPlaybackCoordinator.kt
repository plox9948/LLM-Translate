package com.plox.llmtranslate.domain.runtime

import com.plox.llmtranslate.domain.ModelManager
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeError
import com.plox.llmtranslate.domain.model.TtsModelSelector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context

/**
 * Статус автоскачивания Piper-модели при фолбэке озвучивания (issue #50).
 */
enum class TtsDownloadStatus {
    /** Автоскачивание не активна. */
    NONE,

    /** Модель карточки качается, [.progress] актуален. */
    DOWNLOADING,

    /** Скачивание завершилось ошибкой (UI показывает подсказку). */
    ERROR
}

/**
 * Реактивный статус плейбека TTS для экранов «Перевод» и «Избранное».
 *
 * @param isSpeaking идёт ли озвучивание сейчас.
 * @param language язык активной фразы (null — озвучивания нет).
 * @param text текст активной фразы (null — озвучивания нет).
 * @param error ошибка последнего озвучивания, не покрытая автофолбэком
 *   (null — ошибки нет); UI показывает её через [RuntimeError.userMessage].
 * @param downloadModelId id карточки, которую координатор автоскачивает (issue #50).
 * @param downloadProgress прогресс автоскачивания, %.
 * @param downloadStatus стадия автоскачивания.
 */
data class TtsPlaybackStatus(
    val isSpeaking: Boolean = false,
    val language: String? = null,
    val text: String? = null,
    val error: RuntimeError? = null,
    val downloadModelId: String? = null,
    val downloadProgress: Int = 0,
    val downloadStatus: TtsDownloadStatus = TtsDownloadStatus.NONE
)

/**
 * Координатор TTS-плейбека (issue #50): общий для экранов «Перевод» и
 * «Избранное», чтобы логика выбора движка и фолбэка на Piper не дублировалась
 * в двух ViewModel.
 *
 * Выбор движка на язык — [TtsModelSelector]:
 * - назначенная и готовая Piper-модель → [PiperTtsRuntime] (каталог модели —
 *   [InstalledModel.filePath] пачки ArchiveInstaller: `.onnx` + `tokens.txt` +
 *   `espeak-ng-data/`, поиск раскладывает сам рантайм);
 * - иначе → системный [TtsRuntime].
 *
 * Фолбэк (issues #50, #57): при ЛЮБОЙ ошибке системного TTS
 * (неподдерживаемый язык [RuntimeError.LanguageNotSupported], LANG_MISSING_DATA,
 * сбой или недоступность движка инициализации) берётся карточка по умолчанию
 * для языка ([TtsModelSelector.defaultCardId]):
 * - готова → автоназначение ([ModelManager.setTtsForLanguage]) и немедленная
 *   озвучка Piper-моделью;
 * - не установлена → запуск скачивания через [ModelManager.startDownload]
 *   (идемпотентен для уже качаемой модели) с показом прогресса в статусе;
 *   при переходе записи в READY — автоназначение и ОДНО повторное
 *   озвучивание исходного текста; при ModelStatus.ERROR — стадия
 *   [TtsDownloadStatus.ERROR] (UI покажет подсказку).
 */
@Singleton
class TtsPlaybackCoordinator @Inject constructor(
    private val ttsRuntime: TtsRuntime,
    private val piperRuntime: PiperTtsRuntime,
    private val modelManager: ModelManager,
    private val scope: CoroutineScope,
    // Контекст — проверка доступности сети перед автоскачиванием Piper-модели.
    @ApplicationContext private val context: Context
) {

    private val _status = MutableStateFlow(TtsPlaybackStatus())
    val status: StateFlow<TtsPlaybackStatus> = _status

    // Снимки настроек/каталога для выбора движка (обновляются коллекторами init).
    private var ttsAssignments: Map<String, String> = emptyMap()
    private var piperModels: List<InstalledModel> = emptyList()
    private var catalogCards: List<ModelCard> = emptyList()

    /** Job текущего озвучивания (piper-синтез или подготовка фолбэка). */
    private var speakJob: Job? = null

    /**
     * Монотонный токен активной фразы. Колбэк завершения прежней фразы
     * (QUEUE_FLUSH-перехват в [TtsRuntime], отмена piper-джоба) не должен
     * снимать флаг «озвучиваем» новой фразы — same-паттерн issue #40.
     */
    private var utteranceToken = 0L

    /** Наблюдаемая координатором автоскачиваемая карточка (null — нет). */
    private var watchedDownloadId: String? = null

    /** Исходная фраза, которую нужно озвучить один раз после READY модели. */
    private data class PendingRetry(val text: String, val language: String)

    private var pendingRetry: PendingRetry? = null

    init {
        scope.launch {
            modelManager.ttsByLanguage.collect { assignments ->
                ttsAssignments = assignments
            }
        }
        scope.launch {
            modelManager.catalog.collect { cards ->
                catalogCards = cards
            }
        }
        scope.launch {
            modelManager.installedModels(ModelCategory.TTS).collect { models ->
                piperModels = models
                handleWatchedDownload(models)
            }
        }
    }

    /**
     * Озвучить [text] на языке [language] (выбор движка и фолбэк — внутри).
     * Флаг «озвучиваем» выставляется до обращения к рантаймам: короткая фраза
     * может завершиться раньше возврата speak() (контракт [TtsRuntime]).
     */
    fun speak(text: String, language: String) {
        if (text.isBlank()) return
        // Прежняя piper-озвучка координатора снимается; системный TTS
        // перехватывает новую фразу сам (QUEUE_FLUSH).
        speakJob?.cancel()
        val token = ++utteranceToken
        val watching = watchedDownloadId != null
        _status.value = _status.value.copy(
            isSpeaking = true,
            language = language,
            text = text,
            error = null,
            // Активное автоскачивание сохраняется, зависшее — снимается.
            downloadModelId = if (watching) _status.value.downloadModelId else null,
            downloadStatus = if (watching) _status.value.downloadStatus else TtsDownloadStatus.NONE,
            downloadProgress = if (watching) _status.value.downloadProgress else 0
        )
        speakJob = scope.launch {
            try {
                runSpeak(text, language, token)
            } catch (e: CancellationException) {
                Timber.d("Озвучивание отменено сменой фразы или stop()")
            }
        }
    }

    /**
     * Остановить озвучивание обоих рантаймов и снять флаг «озвучиваем».
     * Активное автоскачивание модели продолжается.
     */
    fun stop() {
        speakJob?.cancel()
        speakJob = null
        ttsRuntime.stop()
        piperRuntime.stop()
        _status.value = _status.value.copy(isSpeaking = false, language = null, text = null)
    }

    /** Снять показанную ошибку озвучивания (закрыта UI-диалогом/баннером). */
    fun clearError() {
        _status.value = _status.value.copy(error = null)
    }

    /** Основной путь озвучивания: выбор движка по языку, вызов рантайма. */
    private suspend fun runSpeak(text: String, language: String, token: Long) {
        when (val choice = TtsModelSelector.select(language, ttsAssignments, piperModels)) {
            is TtsModelSelector.TtsChoice.Model -> {
                val error = speakWithPiper(choice.id, text, token)
                if (error != null) finishWithError(error, token)
            }

            TtsModelSelector.TtsChoice.SystemEngine -> {
                val error = ttsRuntime.speak(text, language, onDone = { onSpeakDone(token) })
                if (error == null) return
                // Фолбэк на Piper-модель языка при ЛЮБОЙ недоступности системного
                // синтеза (issue #57): LANG_MISSING_DATA / LANG_NOT_SUPPORTED
                // [RuntimeError.LanguageNotSupported], сбой/таймаут инициализации
                // и недоступный движок [RuntimeError.LoadFailed], ошибка старта
                // озвучки [RuntimeError.InferenceFailed]. Прежняя ветвка только по
                // LanguageNotSupported оставляла устройства без движка (напр.
                // Huawei без голоса языка) с generic-ошибкой вместо предложения
                // скачать модель озвучки (issue #50 покрывал лишь этот один тип).
                startFallback(text, language, token, error)
            }
        }
    }

    /**
     * Озвучить [text] Piper-моделью [modelId]: загрузка каталога модели из
     * [InstalledModel.filePath] и синтез с воспроизведением.
     */
    private suspend fun speakWithPiper(modelId: String, text: String, token: Long): RuntimeError? {
        val model = piperModels.firstOrNull { it.id == modelId }
            ?: return RuntimeError.ModelNotReady
        val path = model.filePath?.takeIf { it.isNotBlank() } ?: return RuntimeError.ModelNotReady
        // Метаданные записи — для карточки «Загружено в память» (issue #52).
        val loadError = piperRuntime.load(File(path), model)
        if (loadError != null) return loadError
        return piperRuntime.speak(text, onDone = { onSpeakDone(token) })
    }

    /**
     * Фолбэк при любой недоступности системного синтеза (issues #50, #57):
     * карточка Piper по умолчанию для языка → автоназначение/скачивание
     * (см. класс-док). Если для языка нет заготовки — показывается исходная
     * ошибка системного движка [engineError], а не подменённая.
     */
    private fun startFallback(
        text: String,
        language: String,
        token: Long,
        engineError: RuntimeError
    ) {
        val cardId = TtsModelSelector.defaultCardId(language)
        if (cardId == null) {
            finishWithError(engineError, token)
            return
        }
        val installed = piperModels.firstOrNull { it.id == cardId }
        if (installed != null && installed.isReady) {
            autoAssignAndSpeakPiper(cardId, text, language, token)
            return
        }
        val card = catalogCards.firstOrNull { it.id == cardId }
        if (card == null) {
            finishWithError(engineError, token)
            return
        }
        // Не установлена (или в ERROR) — автоскачивание с показом прогресса.
        // Без сети WorkManager просто waited на constraint CONNECTED и никогда
        // не придёт в ERROR — сообщаем о необходимости сети сразу (issue #50).
        if (installed?.status != ModelStatus.DOWNLOADING && !isNetworkAvailable()) {
            _status.value = _status.value.copy(
                isSpeaking = false,
                language = null,
                text = null,
                downloadModelId = cardId,
                downloadStatus = TtsDownloadStatus.ERROR,
                downloadProgress = 0
            )
            Timber.w("Автоскачивание Piper-модели $cardId отменено: нет сети")
            return
        }
        pendingRetry = PendingRetry(text, language)
        watchedDownloadId = cardId
        _status.value = _status.value.copy(
            isSpeaking = false,
            language = null,
            text = null,
            downloadModelId = cardId,
            downloadStatus = TtsDownloadStatus.DOWNLOADING,
            downloadProgress = installed?.progress ?: 0
        )
        when (installed?.status) {
            // Уже качается — только наблюдаем; null/ERROR — ставим на скачивание
            // (startDownload идемпотентен для отсутствующей записи и повторной
            // установки ERROR-модели).
            null, ModelStatus.ERROR -> modelManager.startDownload(card)
            else -> Unit
        }
    }

    /** Есть ли активное сетевое подключение (для запуска автоскачивания). */
    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Готовая карточка: автоназначение языку и немедленная Piper-озвучка. */
    private fun autoAssignAndSpeakPiper(cardId: String, text: String, language: String, token: Long) {
        scope.launch { modelManager.setTtsForLanguage(language, cardId) }
        _status.value = _status.value.copy(
            isSpeaking = true,
            language = language,
            text = text,
            downloadModelId = null,
            downloadStatus = TtsDownloadStatus.NONE,
            downloadProgress = 0
        )
        speakJob = scope.launch {
            try {
                val error = speakWithPiper(cardId, text, token)
                if (error != null) finishWithError(error, token)
            } catch (e: CancellationException) {
                Timber.d("Piper-озвучивание отменено")
            }
        }
    }

    /**
     * Реакция на изменения записи автоскачиваемой модели: прогресс, READY →
     * автоназначение + ОДНО повторное озвучивание исходной фразы,
     * ERROR → стадия [TtsDownloadStatus.ERROR].
     */
    private fun handleWatchedDownload(models: List<InstalledModel>) {
        val cardId = watchedDownloadId ?: return
        val model = models.firstOrNull { it.id == cardId } ?: return
        when (model.status) {
            ModelStatus.DOWNLOADING ->
                _status.value = _status.value.copy(downloadProgress = model.progress)

            ModelStatus.ERROR -> {
                watchedDownloadId = null
                pendingRetry = null
                _status.value = _status.value.copy(downloadStatus = TtsDownloadStatus.ERROR)
                Timber.w("Автоскачивание Piper-модели $cardId завершилось ошибкой")
            }

            ModelStatus.READY -> {
                watchedDownloadId = null
                val retry = pendingRetry
                pendingRetry = null
                _status.value = _status.value.copy(
                    downloadModelId = null,
                    downloadStatus = TtsDownloadStatus.NONE,
                    downloadProgress = 0
                )
                if (retry != null && model.isReady) {
                    autoAssignAndSpeakPiper(cardId, retry.text, retry.language, ++utteranceToken)
                }
            }
        }
    }

    /** Колбэк завершения активной фразы (токен-страховка от прежних фраз). */
    private fun onSpeakDone(token: Long) {
        if (token != utteranceToken) return
        _status.value = _status.value.copy(isSpeaking = false, language = null, text = null)
    }

    /** Финализация неуспешной попытки озвучивания текущей фразы-токена. */
    private fun finishWithError(error: RuntimeError, token: Long) {
        if (token != utteranceToken) return
        _status.value = _status.value.copy(
            error = error,
            isSpeaking = false,
            language = null,
            text = null
        )
    }
}
