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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context

/**
 * Статус автоскачивания Piper-модели при фолбэке озвучивания (issue #50).
 */
enum class TtsDownloadStatus {
    /** Автоскачивание не активно. */
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
 * @param errorId монотонный номер [error] (ревью #55): 0 — ошибки никогда не
 *   было; UI-экраны запоминают показанный id и не показывают ту же ошибку
 *   повторно при пересоздании ViewModel.
 * @param downloadModelId id карточки, которую координатор автоскачивает (issue #50).
 * @param downloadProgress прогресс автоскачивания, %.
 * @param downloadStatus стадия автоскачивания.
 * @param downloadErrorId монотонный номер перехода в [TtsDownloadStatus.ERROR]
 *   (тот же механизм анти-повтора, что у [errorId]).
 */
data class TtsPlaybackStatus(
    val isSpeaking: Boolean = false,
    val language: String? = null,
    val text: String? = null,
    val error: RuntimeError? = null,
    val errorId: Long = 0,
    val downloadModelId: String? = null,
    val downloadProgress: Int = 0,
    val downloadStatus: TtsDownloadStatus = TtsDownloadStatus.NONE,
    val downloadErrorId: Long = 0
)

/**
 * Поставщик текущего времени, миллисекунды (test-seam grace-окна
 * наблюдения за автоскачиванием в [TtsPlaybackCoordinator], ревью раунд 4):
 * боевая реализация — монотонные часы, тест подменяет часы, не засыпая реально.
 */
fun interface DownloadWatchClock {

    /** Текущее время, миллисекунды. */
    fun nowMs(): Long
}

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
    // Часы grace-окна наблюдения за автоскачиванием (reвью раунд 4).
    private val clock: DownloadWatchClock,
    // Контекст — проверка доступности сети перед автоскачиванием Piper-модели.
    @ApplicationContext private val context: Context
) {

    private val _status = MutableStateFlow(TtsPlaybackStatus())
    val status: StateFlow<TtsPlaybackStatus> = _status

    // Снимки настроек/каталога для выбора движка (обновляются коллекторами init;
    // @Volatile — видимость между потоком-коллектором и корутинами озвучивания).
    @Volatile
    private var ttsAssignments: Map<String, String> = emptyMap()
    @Volatile
    private var piperModels: List<InstalledModel> = emptyList()
    @Volatile
    private var catalogCards: List<ModelCard> = emptyList()

    // Мутируемое состояние синглтона читается и пишется из разных потоков
    // (app-scope на Dispatchers.IO: коллекторы flow, speak()/stop() из UI,
    // колбэки рантаймов): @Volatile гарантирует междупоточную видимость
    // happens-before (запись volatile-поля происходит-before чтения того же
    // поля другим потоком). Токен фразы — AtomicLong (ревью раунд 3):
    // одинаковый токен из двух потоков ломает фильтрацию старых колбэков;
    // все мутации статуса — через CAS-мутацию MutableStateFlow.update.

    internal companion object {
        /**
         * Grace-окно отсутствия записи наблюдаемой карточки (ревью раунд 4):
         * сразу после startFallback запись ещё не создана
         * ([ModelManager.startDownload] асинхронен и выполняет upsert не
         * мгновенно), поэтому эмиссии без строки в течение окна терпятся;
         * за окном отсутствие — запись удалена/снят запрос скачивания.
         */
        const val WATCH_GRACE_MS = 3_000L

        /** Запас к grace-окну для одноразовой проверки по таймеру (ревью раунд 5). */
        const val WATCH_CHECK_BUFFER_MS = 500L
    }

    /** Job текущего озвучивания (piper-синтез или подготовка фолбэка). */
    @Volatile
    private var speakJob: Job? = null

    /**
     * Монотонный токен активной фразы. Колбэк завершения прежней фразы
     * (QUEUE_FLUSH-перехват в [TtsRuntime], отмена piper-джоба) не должен
     * снимать флаг «озвучиваем» новой фразы — same-паттерн issue #40.
     * AtomicLong (ревью раунд 3): два потока не должны получить одинаковый
     * токен — иначе колбэк прежней фразы пройдёт фильтрацию и снимет флаг
     * активной фразы.
     */
    private val utteranceToken = AtomicLong(0)

    /** Наблюдаемая координатором автоскачиваемая карточка (null — нет). */
    @Volatile
    private var watchedDownloadId: String? = null

    /**
     * Замок сериализации [handleWatchedDownload] (ревью раунд 6): обработчик
     * вызывают коллектор Flow и grace-таймер; тело не блокирующее (статусы
     * через StateFlow.update, авто-озвучка — scope.launch), deadlock-цепочек
     * с другими мьютексами нет.
     */
    private val watchLock = Any()

    /**
     * Поколение watch-сцены (ревью раунд 6): одноразовый grace-таймер гасится,
     * если за время его ожидания наблюдение было переставлено новым fallback-ом.
     */
    private val watchGeneration = AtomicLong(0L)

    /**
     * Дедлайн grace-окна наблюдения (ревью раунд 4): фиксируется при установке
     * [watchedDownloadId] как `часы + [WATCH_GRACE_MS]`. [ModelManager.startDownload]
     * создаёт запись в Room асинхронно, поэтому первые эмиссии списка вправе
     * прийти раньше upsert-а — в окне отсутствие терпится; за окном absence —
     * запись удалена/снят запрос скачивания (ревью раунд 2).
     */
    @Volatile
    private var watchGraceUntil = 0L

    /** Исходная фраза, которую нужно озвучить один раз после READY модели. */
    private data class PendingRetry(val text: String, val language: String)

    @Volatile
    private var pendingRetry: PendingRetry? = null

    /**
     * Монотонные счётчики id для анти-повтора показа (ревью #55): каждый новый
     * экземпляр ошибки/стадии ERROR получает новый id, экран запоминает
     * показанный и не показывает тот же повторно. AtomicLong (ревью раунд 4) —
     * как [utteranceToken]: id выдаются из разных потоков (collect-коллэки,
     * корутины озвучивания), обычный инкремент volatile-поля их потерял бы.
     */
    private val errorSeq = AtomicLong(0)
    private val downloadErrorSeq = AtomicLong(0)

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
        // Issue #69: новая фраза снимает pendingRetry по образцу stop() —
        // завершившееся после её старта автоскачивание Piper не должно
        // авто-ретраем старой фразы обрывать актуальное озвучивание.
        pendingRetry = null
        // Прежняя piper-озвучка координатора снимается; в ветке системного
        // TTS новую фразу перехватывает сам движок (QUEUE_FLUSH), в Piper-ветке
        // незавершённая системная озвучка останавливается явно (runSpeak).
        speakJob?.cancel()
        val token = utteranceToken.incrementAndGet()
        val watching = watchedDownloadId != null
        _status.update { s ->
            s.copy(
                isSpeaking = true,
                language = language,
                text = text,
                error = null,
                // Активное автоскачивание сохраняется, зависшее — снимается.
                downloadModelId = if (watching) s.downloadModelId else null,
                downloadStatus = if (watching) s.downloadStatus else TtsDownloadStatus.NONE,
                downloadProgress = if (watching) s.downloadProgress else 0
            )
        }
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
     * Активное автоскачивание модели продолжается, но pendingRetry снимается
     * (ревью #54): после отмены озвучки дошедшая до READY модель не должна
     * проигрывать устаревшую фразу — авто-ОЗВУЧКА после READY только пока
     * pendingRetry активен.
     */
    fun stop() {
        speakJob?.cancel()
        speakJob = null
        pendingRetry = null
        ttsRuntime.stop()
        piperRuntime.stop()
        markNotSpeaking()
    }

    /** Снять показанную ошибку озвучивания (закрыта UI-диалогом/баннером). */
    fun clearError() {
        _status.update { it.copy(error = null) }
    }

    /** Основной путь озвучивания: выбор движка по языку, вызов рантайма. */
    private suspend fun runSpeak(text: String, language: String, token: Long) {
        when (val choice = TtsModelSelector.select(language, ttsAssignments, piperModels)) {
            is TtsModelSelector.TtsChoice.Model -> {
                // Смена движка на Piper: прежняя системная фраза не должна
                // накладываться на piper-синтез — отмена speakJob снимает
                // только piper-озвучку, системный TTS останавливаем явно
                // (pending-колбэки изымаются stop()-ом, флаг новой фразы
                // не трогает — фильтр по токену).
                ttsRuntime.stop()
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
        // Default-карточка языка: сначала поле каталога default_for_language,
        // резервная константа селектора — только если каталог default не задаёт.
        val cardId = TtsModelSelector.defaultCardId(language, catalogCards)
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
            val errorId = downloadErrorSeq.incrementAndGet()
            showWatchStatus(cardId, TtsDownloadStatus.ERROR, progress = 0, errorId = errorId)
            Timber.w("Автоскачивание Piper-модели $cardId отменено: нет сети")
            return
        }
        pendingRetry = PendingRetry(text, language)
        watchedDownloadId = cardId
        val watchGen = watchGeneration.incrementAndGet()
        // Наблюдение ставится до асинхронного upsert-а записи (startDownload
        // ниже): окно grace ([WATCH_GRACE_MS] от текущего момента)
        // терпит отсутствие строки, за окном — сброс watch-статуса.
        watchGraceUntil = clock.nowMs() + WATCH_GRACE_MS
        showWatchStatus(cardId, TtsDownloadStatus.DOWNLOADING, progress = installed?.progress ?: 0)
        when (installed?.status) {
            // Уже качается — только наблюдаем; null/ERROR — ставим на скачивание
            // (startDownload идемпотентен для отсутствующей записи и повторной
            // установки ERROR-модели).
            null, ModelStatus.ERROR -> modelManager.startDownload(card)
            else -> Unit
        }
        // Ревью раунд 5 (info): если после отмены/удаления записи Room Flow больше
        // не эмиттирует, grace-дедлайн проверит одноразовый таймер по актуальному
        // снимку [piperModels]; иначе DOWNLOADING-статус может залипнуть.
        scope.launch {
            delay(WATCH_GRACE_MS + WATCH_CHECK_BUFFER_MS)
            if (watchGeneration.get() == watchGen &&
                watchedDownloadId == cardId && clock.nowMs() > watchGraceUntil
            ) {
                handleWatchedDownload(piperModels)
            }
        }
    }

    /**
     * Статус «озвучивание снято, наблюдаем за автоскачиванием модели [cardId]»:
     * одним обновлением гасятся флаг и текст активной фразы (как в
     * [markNotSpeaking]) и выставляется watch-стадия [status] с прогрессом.
     * Для ERROR передаётся новый экземпляр [errorId]; без него прежнее
     * значение ошибки в статусе не трогается.
     */
    private fun showWatchStatus(
        cardId: String,
        status: TtsDownloadStatus,
        progress: Int,
        errorId: Long? = null
    ) {
        _status.update {
            it.copy(
                isSpeaking = false,
                language = null,
                text = null,
                downloadModelId = cardId,
                downloadStatus = status,
                downloadProgress = progress,
                downloadErrorId = errorId ?: it.downloadErrorId
            )
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
        _status.update {
            it.copy(
                isSpeaking = true,
                language = language,
                text = text,
                downloadModelId = null,
                downloadStatus = TtsDownloadStatus.NONE,
                downloadProgress = 0
            )
        }
        speakJob = scope.launch {
            try {
                // Авто-ретрай Piper (путь READY) — незавершённая системная фраза
                // не должна накладываться на piper-синтез: системный TTS
                // останавливаем явно, как в piper-ветке runSpeak (ревью раунд 2).
                ttsRuntime.stop()
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
     *
     * Сериализация под `watchLock` (ревью раунд 6): обработчик вызывают
     * коллектор Room Flow и одноразовый grace-таймер — без замка возможна
     * гонка двойного входа (двойной авто-ретрай/двойной id ошибки).
     */
    private fun handleWatchedDownload(models: List<InstalledModel>) = synchronized(watchLock) {
        val cardId = watchedDownloadId ?: return
        val model = models.firstOrNull { it.id == cardId }
        if (model == null) {
            // Гонка установки наблюдения (ревью раунд 4): [ModelManager.startDownload]
            // выполняет upsert записи асинхронно, поэтому первые эмиссии списка
            // вправе прийти без строки наблюдаемой карточки. В пределах
            // grace-окна ([watchGraceUntil]) — продолжаем ждать; за окном
            // отсутствие — запись удалена/снят запрос скачивания
            // (ревью раунд 2): без сброса залипал DOWNLOADING баннер и
            // оставался поздний авто-ретрай озвучки при последующей
            // установке модели с тем же id.
            if (clock.nowMs() <= watchGraceUntil) {
                Timber.d(
                    "Piper-модель $cardId: запись ещё не появилась (в окне grace)"
                )
                return
            }
            watchedDownloadId = null
            pendingRetry = null
            _status.update {
                it.copy(
                    downloadModelId = null,
                    downloadStatus = TtsDownloadStatus.NONE,
                    downloadProgress = 0
                )
            }
            Timber.w("Автоскачивание Piper-модели $cardId прервано: запись удалена")
            return
        }
        when (model.status) {
            ModelStatus.DOWNLOADING ->
                _status.update { it.copy(downloadProgress = model.progress) }

            ModelStatus.ERROR -> {
                watchedDownloadId = null
                pendingRetry = null
                val errorId = downloadErrorSeq.incrementAndGet()
                _status.update {
                    it.copy(
                        downloadStatus = TtsDownloadStatus.ERROR,
                        downloadErrorId = errorId
                    )
                }
                Timber.w("Автоскачивание Piper-модели $cardId завершилось ошибкой")
            }

            ModelStatus.READY -> {
                watchedDownloadId = null
                val retry = pendingRetry
                pendingRetry = null
                _status.update {
                    it.copy(
                        downloadModelId = null,
                        downloadStatus = TtsDownloadStatus.NONE,
                        downloadProgress = 0
                    )
                }
                if (retry != null && model.isReady) {
                    autoAssignAndSpeakPiper(
                        cardId,
                        retry.text,
                        retry.language,
                        utteranceToken.incrementAndGet()
                    )
                }
            }
        }
    }

    /** Колбэк завершения активной фразы (токен-страховка от прежних фраз). */
    private fun onSpeakDone(token: Long) {
        if (token != utteranceToken.get()) return
        markNotSpeaking()
    }

    /** Снять флаг озвучивания и язык/текст активной фразы из статуса. */
    private fun markNotSpeaking() {
        _status.update {
            it.copy(isSpeaking = false, language = null, text = null)
        }
    }

    /** Финализация неуспешной попытки озвучивания текущей фразы-токена. */
    private fun finishWithError(error: RuntimeError, token: Long) {
        if (token != utteranceToken.get()) return
        // Id снимается до CAS-мутации: лямбда update может выполниться
        // повторно при гонке, инкремент внутри неё удвоил бы id.
        val errorId = errorSeq.incrementAndGet()
        _status.update {
            it.copy(
                error = error,
                errorId = errorId,
                isSpeaking = false,
                language = null,
                text = null
            )
        }
    }
}
