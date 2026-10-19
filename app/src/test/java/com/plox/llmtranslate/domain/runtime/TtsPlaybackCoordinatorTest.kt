package com.plox.llmtranslate.domain.runtime

import android.content.Context
import com.plox.llmtranslate.domain.ModelManager
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeError
import com.plox.llmtranslate.domain.model.RuntimeType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-тесты [TtsPlaybackCoordinator] (issues #50, #57): выбор готовой
 * назначенной Piper-модели, фолбэк при любой недоступности системного TTS
 * (LanguageNotSupported, сбой движка/инициализации) с автоназначением готовой
 * карточки по умолчанию, автоскачивание с ОДНИМ повторным озвучиванием после
 * READY, стадия ошибки скачивания, фолбэк без сети и ошибка без карточки
 * по умолчанию.
 *
 * Реальный координатор + фейковые рантаймы и [ModelManager]; потоки —
 * [MutableStateFlow] с синхронными эмиссиями на unconfined-диспетчере.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsPlaybackCoordinatorTest {

    private val ttsRuntime = mockk<TtsRuntime>(relaxed = true)
    private val piperRuntime = mockk<PiperTtsRuntime>(relaxed = true)
    private val modelManager = mockk<ModelManager>(relaxed = true)

    private val assignmentsFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    private val installedFlow = MutableStateFlow<List<InstalledModel>>(emptyList())
    private val catalogFlow = MutableStateFlow<List<ModelCard>>(emptyList())

    /**
     * Фиктивные часы grace-окна наблюдения (ревью раунд 4): время стоит
     * на нуле и двигается только явным присваиванием в тестах.
     */
    private var nowMs = 0L

    /**
     * Хук на каждое обращение боевого координатора к часам (ревью #83):
     * позволяет эмулировать конкурентный stop() ровно в окне между
     * присваиваниями watch и повторной сверкой токена в startFallback.
     */
    private var clockHook: (() -> Unit)? = null

    private val coordinator: TtsPlaybackCoordinator

    init {
        every { modelManager.ttsByLanguage } returns assignmentsFlow
        every { modelManager.installedModels(ModelCategory.TTS) } returns installedFlow
        every { modelManager.catalog } returns catalogFlow
        coEvery { piperRuntime.load(any(), any()) } returns null
        coEvery { piperRuntime.speak(any(), any(), any(), any()) } returns null
        coordinator = TtsPlaybackCoordinator(
            ttsRuntime,
            piperRuntime,
            modelManager,
            CoroutineScope(UnconfinedTestDispatcher()),
            DownloadWatchClock {
                clockHook?.invoke()
                nowMs
            },
            contextWithNetwork(true)
        )
    }

    /** Контекст с активным (или отсутствующим) сетевым подключением. */
    private fun contextWithNetwork(online: Boolean): Context {
        val connectivity = mockk<android.net.ConnectivityManager>(relaxed = true)
        if (online) {
            val capabilities = mockk<android.net.NetworkCapabilities>(relaxed = true)
            every { capabilities.hasCapability(any()) } returns true
            every { connectivity.activeNetwork } returns mockk()
            every { connectivity.getNetworkCapabilities(any()) } returns capabilities
        } else {
            every { connectivity.activeNetwork } returns null
        }
        val context = mockk<Context>()
        every {
            context.getSystemService(Context.CONNECTIVITY_SERVICE)
        } returns connectivity
        return context
    }

    /** Готовая PIPER-модель TTS с путём каталога. */
    private fun readyPiper(id: String) = InstalledModel(
        id = id,
        category = ModelCategory.TTS,
        displayName = id,
        description = "",
        runtime = RuntimeType.PIPER_ONNX,
        sizeBytes = 1L,
        ramRequiredMb = 0,
        languages = listOf("ru"),
        quality = 4,
        speed = 4,
        url = "",
        urls = emptyList(),
        sha256 = null,
        license = "",
        minAndroidSdk = 24,
        recommendedRamMb = 0,
        status = ModelStatus.READY,
        progress = 100,
        filePath = "/models/PIPER_ONNX/$id",
        bytesDone = 1L,
        isBuiltin = true
    )

    /** Карточка каталога Piper-модели. */
    private fun piperCard(id: String) = ModelCard(
        id = id,
        category = ModelCategory.TTS,
        displayName = id,
        description = "",
        runtime = RuntimeType.PIPER_ONNX,
        sizeBytes = 1L,
        ramRequiredMb = 0,
        languages = listOf("ru"),
        quality = 4,
        speed = 4,
        url = "",
        urls = listOf("https://example/$id.tar.bz2"),
        sha256 = null,
        license = "",
        minAndroidSdk = 24,
        recommendedRamMb = 0
    )

    /** Системный движок «не поддерживает язык». */
    private fun stubLanguageNotSupported(language: String) {
        coEvery {
            ttsRuntime.speak(any(), any(), any())
        } returns RuntimeError.LanguageNotSupported("system", language)
    }

    @Test
    fun `ready assigned piper model is used directly without system tts`() {
        assignmentsFlow.value = mapOf("ru" to "tts-ru-piper-ruslan")
        installedFlow.value = listOf(readyPiper("tts-ru-piper-ruslan"))

        coordinator.speak("привет мир", "ru")

        coVerify(exactly = 1) { piperRuntime.load(any(), any()) }
        coVerify(exactly = 1) { piperRuntime.speak("привет мир", any(), any(), any()) }
        coVerify(exactly = 0) { ttsRuntime.speak(any(), any(), any()) }
        // Piper-ветка останавливает системный TTS: незавершённая системная
        // фраза не должна накладываться на piper-синтез (ревью раунд 9).
        verify(exactly = 1) { ttsRuntime.stop() }
        assertTrue(coordinator.status.value.isSpeaking)
        assertEquals("ru", coordinator.status.value.language)
    }

    @Test
    fun `language not supported with ready default card auto-assigns and speaks piper`() {
        stubLanguageNotSupported("ru")
        // Ревью #83: резервная константа действует только при наличии
        // карточки в каталоге — каталог обязан содержать резервную карту.
        catalogFlow.value = listOf(piperCard("tts-ru-piper-ruslan"))
        installedFlow.value = listOf(readyPiper("tts-ru-piper-ruslan"))

        coordinator.speak("привет", "ru")

        coVerify(exactly = 1) {
            modelManager.setTtsForLanguage("ru", "tts-ru-piper-ruslan")
        }
        coVerify(exactly = 1) { piperRuntime.speak("привет", any(), any(), any()) }
        assertEquals(TtsDownloadStatus.NONE, coordinator.status.value.downloadStatus)
        assertNull(coordinator.status.value.error)
    }

    @Test
    fun `language not supported starts download and re-speaks once after ready`() {
        stubLanguageNotSupported("ru")
        val card = piperCard("tts-ru-piper-ruslan")
        catalogFlow.value = listOf(card)

        coordinator.speak("повторить меня", "ru")

        // Скачивание запущено, идёт автоскачивание с прогрессом.
        verify(exactly = 1) { modelManager.startDownload(card) }
        assertEquals(TtsDownloadStatus.DOWNLOADING, coordinator.status.value.downloadStatus)
        assertEquals("tts-ru-piper-ruslan", coordinator.status.value.downloadModelId)
        coVerify(exactly = 0) { piperRuntime.speak(any(), any(), any(), any()) }

        // Прогресс прозрачно проходит в статус.
        installedFlow.value = listOf(
            readyPiper("tts-ru-piper-ruslan").copy(
                status = ModelStatus.DOWNLOADING,
                progress = 42
            )
        )
        assertEquals(42, coordinator.status.value.downloadProgress)

        // Модель стала READY → автоназначение + ОДНО повторное озвучивание.
        installedFlow.value = listOf(readyPiper("tts-ru-piper-ruslan"))

        coVerify(exactly = 1) {
            modelManager.setTtsForLanguage("ru", "tts-ru-piper-ruslan")
        }
        coVerify(exactly = 1) {
            piperRuntime.speak("повторить меня", any(), any(), any())
        }
        assertEquals(TtsDownloadStatus.NONE, coordinator.status.value.downloadStatus)
        assertTrue(coordinator.status.value.isSpeaking)
    }

    @Test
    fun `watched download error moves status to error without piper speak`() {
        stubLanguageNotSupported("ru")
        catalogFlow.value = listOf(piperCard("tts-ru-piper-ruslan"))

        coordinator.speak("текст", "ru")
        installedFlow.value = listOf(
            readyPiper("tts-ru-piper-ruslan").copy(
                status = ModelStatus.ERROR,
                progress = 0,
                filePath = null
            )
        )

        assertEquals(TtsDownloadStatus.ERROR, coordinator.status.value.downloadStatus)
        assertNull(coordinator.status.value.error)
        coVerify(exactly = 0) { piperRuntime.speak(any(), any(), any(), any()) }
    }

    @Test
    fun `language without default card exposes LanguageNotSupported error`() {
        stubLanguageNotSupported("de")

        coordinator.speak("text", "de")

        assertEquals(
            RuntimeError.LanguageNotSupported("system", "de"),
            coordinator.status.value.error
        )
        assertEquals(TtsDownloadStatus.NONE, coordinator.status.value.downloadStatus)
        verify(exactly = 0) { modelManager.startDownload(any()) }
        coVerify(exactly = 0) { piperRuntime.speak(any(), any(), any(), any()) }
        assertTrue(coordinator.status.value.isSpeaking.not())
    }

    // ── issue #57: любая недоступность синтеза ведёт к фолбэку, а не к generic-ошибке ──

    @Test
    fun `engine not ready (init failure) routes to default card fallback`() {
        // Сбой/отсутствие инициализации движка (напр. Huawei без голоса языка):
        // TtsRuntime.speak отдаёт LoadFailed, а не LanguageNotSupported.
        coEvery {
            ttsRuntime.speak(any(), any(), any())
        } returns RuntimeError.LoadFailed("tts", "TTS не готов")
        val card = piperCard("tts-ru-piper-ruslan")
        catalogFlow.value = listOf(card)

        coordinator.speak("привет", "ru")

        verify(exactly = 1) { modelManager.startDownload(card) }
        assertEquals(TtsDownloadStatus.DOWNLOADING, coordinator.status.value.downloadStatus)
        assertEquals("tts-ru-piper-ruslan", coordinator.status.value.downloadModelId)
        assertNull(coordinator.status.value.error)
    }

    @Test
    fun `engine unavailable and ready default card auto-assigns and speaks piper`() {
        coEvery {
            ttsRuntime.speak(any(), any(), any())
        } returns RuntimeError.LoadFailed("tts", "TTS не инициализирован")
        // Ревью #83: резерв константы только при карточке в каталоге.
        catalogFlow.value = listOf(piperCard("tts-ru-piper-ruslan"))
        installedFlow.value = listOf(readyPiper("tts-ru-piper-ruslan"))

        coordinator.speak("озвучь", "ru")

        coVerify(exactly = 1) {
            modelManager.setTtsForLanguage("ru", "tts-ru-piper-ruslan")
        }
        coVerify(exactly = 1) { piperRuntime.speak("озвучь", any(), any(), any()) }
        assertNull(coordinator.status.value.error)
        assertTrue(coordinator.status.value.isSpeaking)
    }

    @Test
    fun `speak start failure routes to fallback instead of generic error`() {
        coEvery {
            ttsRuntime.speak(any(), any(), any())
        } returns RuntimeError.InferenceFailed("tts", "Не удалось озвучить текст")
        val card = piperCard("tts-ru-piper-ruslan")
        catalogFlow.value = listOf(card)

        coordinator.speak("текст", "ru")

        verify(exactly = 1) { modelManager.startDownload(card) }
        assertEquals(TtsDownloadStatus.DOWNLOADING, coordinator.status.value.downloadStatus)
        assertNull(coordinator.status.value.error)
    }

    @Test
    fun `offline fallback sets download error status without starting download`() {
        stubLanguageNotSupported("ru")
        catalogFlow.value = listOf(piperCard("tts-ru-piper-ruslan"))
        val offline = TtsPlaybackCoordinator(
            ttsRuntime,
            piperRuntime,
            modelManager,
            CoroutineScope(UnconfinedTestDispatcher()),
            DownloadWatchClock { nowMs },
            contextWithNetwork(false)
        )

        offline.speak("текст", "ru")

        // Сети нет → скачивание не стартует, UI получает подсказку скачать
        // модель на экране «Модели» (stadium ERROR), без сообщения про микрофон.
        verify(exactly = 0) { modelManager.startDownload(any()) }
        assertEquals(TtsDownloadStatus.ERROR, offline.status.value.downloadStatus)
        assertEquals("tts-ru-piper-ruslan", offline.status.value.downloadModelId)
        assertNull(offline.status.value.error)
    }

    @Test
    fun `grace timer resets stale downloading when room flow stays silent`() = runTest {
        stubLanguageNotSupported("ru")
        catalogFlow.value = listOf(piperCard("tts-ru-piper-ruslan"))
        val timerCoordinator = TtsPlaybackCoordinator(
            ttsRuntime,
            piperRuntime,
            modelManager,
            backgroundScope,
            DownloadWatchClock { nowMs },
            contextWithNetwork(true)
        )

        timerCoordinator.speak("текст", "ru")
        runCurrent()
        assertEquals(
            TtsDownloadStatus.DOWNLOADING,
            timerCoordinator.status.value.downloadStatus
        )

        // Скачивание снято (запись удалена), но Room Flow больше не эмиттирует —
        // DOWNLOADING не должен залипать: проверка срабатывает по таймеру.
        val timerAt =
            TtsPlaybackCoordinator.WATCH_GRACE_MS +
            TtsPlaybackCoordinator.WATCH_CHECK_BUFFER_MS + 100
        nowMs = timerAt + 400
        advanceTimeBy(timerAt)
        runCurrent()

        assertEquals(TtsDownloadStatus.NONE, timerCoordinator.status.value.downloadStatus)
        assertNull(timerCoordinator.status.value.downloadModelId)
    }

    @Test
    fun `stale grace timer from previous watch scene does not reset new watch`() = runTest {
        stubLanguageNotSupported("ru")
        catalogFlow.value = listOf(piperCard("tts-ru-piper-ruslan"))
        val timerCoordinator = TtsPlaybackCoordinator(
            ttsRuntime,
            piperRuntime,
            modelManager,
            backgroundScope,
            DownloadWatchClock { nowMs },
            contextWithNetwork(true)
        )

        // Первая watch-сцена (gen=1).
        timerCoordinator.speak("фраза один", "ru")
        runCurrent()
        assertEquals(
            TtsDownloadStatus.DOWNLOADING,
            timerCoordinator.status.value.downloadStatus
        )

        // Повторный фолбэк по той же карте переставляет watch: gen=2,
        // watchGraceUntil обновляется (общее поле). Старый таймер при срабатывании
        // не проходит гейт «now > watchGraceUntil» по НОВОМУ дедлайну, а
        // watchGeneration — второй defensive-слой (в текущей проводке часы
        // гасят его раньше; гард страхует проводку с раздельными дедлайнами).
        nowMs = TtsPlaybackCoordinator.WATCH_GRACE_MS / 2
        timerCoordinator.speak("фраза два", "ru")
        runCurrent()
        assertEquals(
            TtsDownloadStatus.DOWNLOADING,
            timerCoordinator.status.value.downloadStatus
        )

        // Виртуальное время доходит до старого дедлайна, nowMs — внутри
        // актуального grace-окна: статус DOWNLOADING новой сцены сохраняется.
        nowMs = TtsPlaybackCoordinator.WATCH_GRACE_MS / 2 + 500
        advanceTimeBy(
            TtsPlaybackCoordinator.WATCH_GRACE_MS +
                TtsPlaybackCoordinator.WATCH_CHECK_BUFFER_MS + 100
        )
        runCurrent()

        assertEquals(
            TtsDownloadStatus.DOWNLOADING,
            timerCoordinator.status.value.downloadStatus
        )
        assertEquals(
            "tts-ru-piper-ruslan",
            timerCoordinator.status.value.downloadModelId
        )
    }

    @Test
    fun `engine error without default card exposes original engine error`() {
        coEvery {
            ttsRuntime.speak(any(), any(), any())
        } returns RuntimeError.LoadFailed("tts", "Таймаут инициализации TTS")

        coordinator.speak("text", "de")

        // Для языка без заготовки показывается исходная ошибка движка,
        // не подменённая на LanguageNotSupported.
        assertEquals(
            RuntimeError.LoadFailed("tts", "Таймаут инициализации TTS"),
            coordinator.status.value.error
        )
        assertEquals(TtsDownloadStatus.NONE, coordinator.status.value.downloadStatus)
    }

    // ── ревью #54: stop() снимает pendingRetry, авто-повтор после READY гасится ──

    @Test
    fun `stop during downloading cancels pending retry and ready model does not speak`() {
        stubLanguageNotSupported("ru")
        catalogFlow.value = listOf(piperCard("tts-ru-piper-ruslan"))

        coordinator.speak("устаревшая фраза", "ru")
        assertEquals(TtsDownloadStatus.DOWNLOADING, coordinator.status.value.downloadStatus)

        coordinator.stop()

        // Скачивание продолжается и доходит до READY — устаревшая фраза
        // после отмены озвучки не проигрывается.
        installedFlow.value = listOf(readyPiper("tts-ru-piper-ruslan"))

        coVerify(exactly = 0) { piperRuntime.speak(any(), any(), any(), any()) }
        coVerify(exactly = 0) { piperRuntime.load(any(), any()) }
        assertEquals(TtsDownloadStatus.NONE, coordinator.status.value.downloadStatus)
        assertTrue(coordinator.status.value.isSpeaking.not())
    }

    @Test
    fun `without stop ready model still re-speaks — pendingRetry contract intact`() {
        stubLanguageNotSupported("ru")
        catalogFlow.value = listOf(piperCard("tts-ru-piper-ruslan"))

        coordinator.speak("нужный повтор", "ru")
        installedFlow.value = listOf(readyPiper("tts-ru-piper-ruslan"))

        coVerify(exactly = 1) { piperRuntime.speak("нужный повтор", any(), any(), any()) }
    }

    @Test
    fun `speak of new phrase drops pending retry of previous fallback`() {
        // Issue #69: старт новой фразы снимает pendingRetry по образцу stop() —
        // завершившееся после неё автоскачивание Piper не должно авто-ретраем
        // устаревшей фразы обрывать актуальное озвучивание.
        coEvery {
            ttsRuntime.speak(any(), any(), any())
        } answers {
            if (secondArg<String>() == "ru") {
                RuntimeError.LanguageNotSupported("system", "ru")
            } else null
        }
        catalogFlow.value = listOf(piperCard("tts-ru-piper-ruslan"))

        coordinator.speak("устаревшая фраза", "ru")
        assertEquals(TtsDownloadStatus.DOWNLOADING, coordinator.status.value.downloadStatus)

        // Новая фраза озвучивается системным движком успешно.
        coordinator.speak("актуальная фраза", "en")
        assertTrue(coordinator.status.value.isSpeaking)

        // Автоскачивание доходит до READY — устаревшая русская фраза не
        // проигрывается, озвучивание английской не прервано.
        installedFlow.value = listOf(readyPiper("tts-ru-piper-ruslan"))

        coVerify(exactly = 0) { piperRuntime.speak(any(), any(), any(), any()) }
        coVerify(exactly = 0) {
            modelManager.setTtsForLanguage(any(), any())
        }
        assertTrue(coordinator.status.value.isSpeaking)
        assertEquals("en", coordinator.status.value.language)
        assertEquals("актуальная фраза", coordinator.status.value.text)
    }

    @Test
    fun `auto retry after ready stops system tts before piper speak`() {
        // Ревью раунд 2: авто-ретрай Piper после READY обязан погасить
        // незавершённую системную фразу (тот же класс, что закрыт для
        // пользовательской ветки в cc4d0ac) — синтез не должен накладываться.
        stubLanguageNotSupported("ru")
        catalogFlow.value = listOf(piperCard("tts-ru-piper-ruslan"))

        coordinator.speak("текст", "ru")
        assertEquals(TtsDownloadStatus.DOWNLOADING, coordinator.status.value.downloadStatus)
        coVerify(exactly = 0) { ttsRuntime.stop() }

        // Модель дошла до READY → авто-ретрай Piper с явной остановкой системного TTS.
        installedFlow.value = listOf(readyPiper("tts-ru-piper-ruslan"))

        coVerify(exactly = 1) { piperRuntime.speak("текст", any(), any(), any()) }
        coVerify(exactly = 1) { ttsRuntime.stop() }
    }

    // ── ревью раунд 2 (Н-1): удаление/отмена скачиваемой карточки ──

    @Test
    fun `removed watched card resets download status to none without late retry`() {
        stubLanguageNotSupported("ru")
        catalogFlow.value = listOf(piperCard("tts-ru-piper-ruslan"))

        coordinator.speak("текст", "ru")
        assertEquals(TtsDownloadStatus.DOWNLOADING, coordinator.status.value.downloadStatus)

        // Прогресс доходит, затем запись карточки удалена/отменена —
        // за окном grace (ревью раунд 4) достаточно ОДНОЙ эмиссии
        // установленного списка БЕЗ записи наблюдаемой модели.
        installedFlow.value = listOf(
            readyPiper("tts-ru-piper-ruslan").copy(
                status = ModelStatus.DOWNLOADING,
                progress = 17,
                filePath = null
            )
        )
        assertEquals(17, coordinator.status.value.downloadProgress)
        // Список обязан меняться между эмиссиями (StateFlow дедуплицирует
        // равные значения): окно grace истекло — одинокая эмиссия без строки
        // снимает наблюдение.
        nowMs = TtsPlaybackCoordinator.WATCH_GRACE_MS + 1
        installedFlow.value = emptyList()

        // Залипший DOWNLOADING-баннер снят, статус NONE, id сброшен.
        assertEquals(TtsDownloadStatus.NONE, coordinator.status.value.downloadStatus)
        assertNull(coordinator.status.value.downloadModelId)
        assertEquals(0, coordinator.status.value.downloadProgress)

        // Поздний авто-ретрай не срабатывает даже при повторной READY-установке.
        installedFlow.value = listOf(readyPiper("tts-ru-piper-ruslan"))
        coVerify(exactly = 0) { piperRuntime.speak(any(), any(), any(), any()) }
        coVerify(exactly = 0) {
            modelManager.setTtsForLanguage("ru", "tts-ru-piper-ruslan")
        }
        assertEquals(TtsDownloadStatus.NONE, coordinator.status.value.downloadStatus)
    }

    // ── ревью раунд 3/4: гонка watch-установки (асинхронный upsert записи),
    //    допуск — grace-окно по времени, а не счётчик пропусков ──

    @Test
    fun `watch tolerates first emissions without row before async download upsert`() {
        stubLanguageNotSupported("ru")
        catalogFlow.value = listOf(piperCard("tts-ru-piper-ruslan"))

        coordinator.speak("текст", "ru")
        assertEquals(TtsDownloadStatus.DOWNLOADING, coordinator.status.value.downloadStatus)
        assertEquals("tts-ru-piper-ruslan", coordinator.status.value.downloadModelId)

        // startDownload создаёт запись в Room асинхронно: ранние эмиссии
        // списка без строки наблюдаемой карточки в окне grace статус не
        // сбрасывают (список заведомо отличается от предыдущего — StateFlow
        // не дедуплицирует эмиссию).
        installedFlow.value = listOf(readyPiper("tts-en-piper-other"))
        assertEquals(TtsDownloadStatus.DOWNLOADING, coordinator.status.value.downloadStatus)
        assertEquals("tts-ru-piper-ruslan", coordinator.status.value.downloadModelId)

        // Асинхронный upsert появился — прогресс проходит в статус.
        nowMs = 1_500
        installedFlow.value = listOf(
            readyPiper("tts-ru-piper-ruslan").copy(
                status = ModelStatus.DOWNLOADING,
                progress = 8,
                filePath = null
            )
        )
        assertEquals(8, coordinator.status.value.downloadProgress)

        // В окне grace одинокая эмиссия без записи снова не сбрасывает
        // watch-статус.
        nowMs = 2_000
        installedFlow.value = emptyList()
        assertEquals(TtsDownloadStatus.DOWNLOADING, coordinator.status.value.downloadStatus)
        assertEquals("tts-ru-piper-ruslan", coordinator.status.value.downloadModelId)
    }

    @Test
    fun `emission without row inside grace window is tolerated`() {
        stubLanguageNotSupported("ru")
        catalogFlow.value = listOf(piperCard("tts-ru-piper-ruslan"))

        coordinator.speak("текст", "ru")

        // Конец окна grace (граница включительно) — отсутствие ещё терпится.
        nowMs = TtsPlaybackCoordinator.WATCH_GRACE_MS
        installedFlow.value = listOf(readyPiper("tts-en-piper-other"))

        assertEquals(TtsDownloadStatus.DOWNLOADING, coordinator.status.value.downloadStatus)
        assertEquals("tts-ru-piper-ruslan", coordinator.status.value.downloadModelId)
    }

    @Test
    fun `single emission without row after grace window resets watch status`() {
        stubLanguageNotSupported("ru")
        catalogFlow.value = listOf(piperCard("tts-ru-piper-ruslan"))

        coordinator.speak("текст", "ru")
        assertEquals(TtsDownloadStatus.DOWNLOADING, coordinator.status.value.downloadStatus)

        // Окно grace истекло, а записи так и нет (upsert не произошёл или
        // скачивание снято) — ОДНА эмиссия без строки сбрасывает watch-статус.
        nowMs = TtsPlaybackCoordinator.WATCH_GRACE_MS + 1
        installedFlow.value = listOf(readyPiper("tts-en-piper-other"))

        assertEquals(TtsDownloadStatus.NONE, coordinator.status.value.downloadStatus)
        assertNull(coordinator.status.value.downloadModelId)

        // Поздний авто-ретрай после сброса не срабатывает.
        installedFlow.value = listOf(readyPiper("tts-ru-piper-ruslan"))
        coVerify(exactly = 0) { piperRuntime.speak(any(), any(), any(), any()) }
        coVerify(exactly = 0) {
            modelManager.setTtsForLanguage("ru", "tts-ru-piper-ruslan")
        }
    }

    // ── ревью #55: монотонные id ошибок и ERROR-стадии для анти-повтора в UI ──

    @Test
    fun `repeated speak failures get strictly increasing error ids`() {
        stubLanguageNotSupported("de")

        coordinator.speak("первая", "de")
        val first = coordinator.status.value.errorId

        coordinator.clearError()
        coordinator.speak("вторая", "de")
        val second = coordinator.status.value.errorId

        assertTrue("id ошибки монотонно растут: $first -> $second", second > first && first > 0)
        // Промежуточные эмиссии того же экземпляра ошибки id не меняют.
        val same = coordinator.status.value.copy(isSpeaking = true).errorId
        assertEquals(second, same)
    }

    // ── ревью #83: check-then-act гейта watch — повторная сверка токена ──

    @Test
    fun `stop inside watch installation window is caught by token recheck`() {
        // Инъекция по часам: stop() срабатывает сразу после установки
        // pendingRetry/watchedDownloadId (watchGraceUntil считается часами) и
        // до повторной сверки токена в конце startFallback. Первая проверка
        // токена в начале уже пройдена и stop не увидит.
        stubLanguageNotSupported("ru")
        catalogFlow.value = listOf(piperCard("tts-ru-piper-ruslan"))

        var stopInjected = false
        clockHook = {
            if (!stopInjected) {
                stopInjected = true
                coordinator.stop()
            }
        }
        try {
            coordinator.speak("текст", "ru")
        } finally {
            clockHook = null
        }

        // Watch-сцена снята повторной проверкой: DOWNLOADING не залипает.
        assertTrue(stopInjected)
        assertEquals(TtsDownloadStatus.NONE, coordinator.status.value.downloadStatus)
        assertNull(coordinator.status.value.downloadModelId)
        assertTrue(coordinator.status.value.isSpeaking.not())

        // pendingRetry тоже снят: поздний READY не даёт авто-ретрай и
        // автоназначение.
        installedFlow.value = listOf(readyPiper("tts-ru-piper-ruslan"))
        coVerify(exactly = 0) { piperRuntime.speak(any(), any(), any(), any()) }
        coVerify(exactly = 0) { modelManager.setTtsForLanguage(any(), any()) }
    }

    @Test
    fun `download failure stage stamped with non-zero download error id`() {
        stubLanguageNotSupported("ru")
        catalogFlow.value = listOf(piperCard("tts-ru-piper-ruslan"))

        coordinator.speak("текст", "ru")
        assertEquals(0L, coordinator.status.value.downloadErrorId)

        installedFlow.value = listOf(
            readyPiper("tts-ru-piper-ruslan").copy(
                status = ModelStatus.ERROR,
                progress = 0,
                filePath = null
            )
        )

        assertEquals(TtsDownloadStatus.ERROR, coordinator.status.value.downloadStatus)
        assertTrue(coordinator.status.value.downloadErrorId > 0)
    }
}
