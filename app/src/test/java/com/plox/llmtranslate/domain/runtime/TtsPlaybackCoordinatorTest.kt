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
 * Unit-тесты [TtsPlaybackCoordinator] (issue #50): выбор готовой назначенной
 * Piper-модели, фолбэк при LanguageNotSupported с автоназначением готовой
 * карточки по умолчанию, автоскачивание с ОДНИМ повторным озвучиванием после
 * READY, стадия ошибки скачивания и ошибка без карточки по умолчанию.
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
        // Контекст с «сетью»: connectivity-проверка перед автоскачиванием (issue #50).
        val connectivity = mockk<android.net.ConnectivityManager>(relaxed = true)
        val capabilities = mockk<android.net.NetworkCapabilities>(relaxed = true)
        every { capabilities.hasCapability(any()) } returns true
        every { connectivity.activeNetwork } returns mockk()
        every { connectivity.getNetworkCapabilities(any()) } returns capabilities
        val context = mockk<Context>()
        every {
            context.getSystemService(Context.CONNECTIVITY_SERVICE)
        } returns connectivity
        coordinator = TtsPlaybackCoordinator(
            ttsRuntime,
            piperRuntime,
            modelManager,
            CoroutineScope(UnconfinedTestDispatcher()),
            context
        )
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
}
