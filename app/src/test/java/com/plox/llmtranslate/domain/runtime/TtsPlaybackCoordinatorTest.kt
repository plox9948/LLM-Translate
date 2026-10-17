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
        assertTrue(coordinator.status.value.isSpeaking)
        assertEquals("ru", coordinator.status.value.language)
    }

    @Test
    fun `language not supported with ready default card auto-assigns and speaks piper`() {
        stubLanguageNotSupported("ru")
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
