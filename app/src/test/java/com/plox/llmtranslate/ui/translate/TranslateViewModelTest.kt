package com.plox.llmtranslate.ui.translate

import android.content.Context
import com.plox.llmtranslate.R
import com.plox.llmtranslate.data.model.FavoritesRepository
import com.plox.llmtranslate.data.model.TranslationHistoryRepository
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.ModelManager
import com.plox.llmtranslate.domain.model.FavoriteEntry
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.PendingTranslationHolder
import com.plox.llmtranslate.domain.model.RuntimeError
import com.plox.llmtranslate.domain.model.RuntimeType
import com.plox.llmtranslate.domain.model.TranslationHistoryEntry
import com.plox.llmtranslate.domain.runtime.AudioRecorder
import com.plox.llmtranslate.domain.runtime.RuntimeRouter
import com.plox.llmtranslate.domain.runtime.TtsDownloadStatus
import com.plox.llmtranslate.domain.runtime.TtsPlaybackCoordinator
import com.plox.llmtranslate.domain.runtime.TtsPlaybackStatus
import com.plox.llmtranslate.domain.runtime.TtsRuntime
import com.plox.llmtranslate.domain.runtime.TranslationEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Тесты звёздочки избранного результата в [TranslateViewModel]:
 * переключение избранности и пересчёт флага при смене направления.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranslateViewModelTest {

    private val modelManager = mockk<ModelManager>(relaxed = true)
    private val runtimeRouter = mockk<RuntimeRouter>(relaxed = true)
    private val ttsRuntime = mockk<TtsRuntime>(relaxed = true)
    // Координатор TTS-плейбека: тесты звёздочки не затрагивают озвучивание,
    // relaxed-мока достаточно (поток status в init не эмитит).
    private val ttsCoordinator = mockk<TtsPlaybackCoordinator>(relaxed = true)
    private val audioRecorder = mockk<AudioRecorder>(relaxed = true)
    private val preferences = mockk<ModelPreferences>(relaxed = true)
    private val historyRepository = mockk<TranslationHistoryRepository>(relaxed = true)
    private val favoritesRepository = mockk<FavoritesRepository>(relaxed = true)
    private val pendingHolder = PendingTranslationHolder()
    // Контекст ресурсов (issue #57): getString возвращает различимые заглушки.
    private val appContext = mockk<Context>(relaxed = true)

    private lateinit var viewModel: TranslateViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Создать ViewModel (коллекторы init стартуют здесь — стабы должны быть готовы). */
    private fun createViewModel() {
        every { appContext.getString(R.string.translate_mic_permission_denied) } returns "MIC_PERM"
        every { appContext.getString(R.string.translate_mic_missing_stt_model) } returns "MIC_STT"
        viewModel = TranslateViewModel(
            modelManager,
            runtimeRouter,
            ttsRuntime,
            ttsCoordinator,
            audioRecorder,
            preferences,
            historyRepository,
            pendingHolder,
            favoritesRepository,
            appContext
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Подставить запись «как из истории» — заполняет языки, ввод и результат. */
    private fun applyPendingEntry() {
        pendingHolder.request(
            TranslationHistoryEntry(
                id = 1L,
                sourceLang = "ru",
                targetLang = "en",
                inputText = "privet",
                resultText = "hello",
                createdAt = 100L
            )
        )
    }

    @Test
    fun `toggleResultFavorite adds favorite via repository and sets flag`() {
        every { favoritesRepository.observeAll() } returns flowOf(emptyList())
        createViewModel()
        applyPendingEntry()
        assertFalse(viewModel.uiState.value.resultFavorite)

        coEvery {
            favoritesRepository.toggle("ru", "en", "privet", "hello")
        } returns true
        viewModel.toggleResultFavorite()

        assertTrue(viewModel.uiState.value.resultFavorite)
        coVerify(exactly = 1) { favoritesRepository.toggle("ru", "en", "privet", "hello") }
    }

    @Test
    fun `result favorite flag recomputed on target language change`() {
        // В избранном — запись направления ru→ne на тот же исходный текст.
        every { favoritesRepository.observeAll() } returns flowOf(
            listOf(
                FavoriteEntry(
                    id = 5L,
                    sourceLang = "ru",
                    targetLang = "ne",
                    inputText = "privet",
                    resultText = "namaste",
                    createdAt = 10L
                )
            )
        )
        createViewModel()
        applyPendingEntry()
        // Направление ru→en: такой записи в избранном нет.
        assertFalse(viewModel.uiState.value.resultFavorite)

        viewModel.onTargetLangChange(TranslateLanguage.NE)
        assertTrue(viewModel.uiState.value.resultFavorite)

        viewModel.onTargetLangChange(TranslateLanguage.EN)
        assertFalse(viewModel.uiState.value.resultFavorite)
    }

    // ── Привязка результата к направлению (issue #59) ──

    @Test
    fun `cached result is not shown or spoken under a different target language`() {
        every { favoritesRepository.observeAll() } returns flowOf(emptyList())
        createViewModel()
        applyPendingEntry() // ru→en, результат «hello»
        assertEquals("hello", viewModel.uiState.value.visibleResult)

        // Смена направления — карточка результата под чужим языком исчезает…
        viewModel.onTargetLangChange(TranslateLanguage.NE)
        assertEquals("", viewModel.uiState.value.visibleResult)
        // …и «Озвучить» не вызывает координатор чужим языком.
        viewModel.speakResult()
        io.mockk.verify(exactly = 0) { ttsCoordinator.speak(any(), any()) }

        // Возврат прежнего направления — результат снова согласован и озвучим.
        viewModel.onTargetLangChange(TranslateLanguage.EN)
        assertEquals("hello", viewModel.uiState.value.visibleResult)
        viewModel.speakResult()
        io.mockk.verify(exactly = 1) { ttsCoordinator.speak("hello", "en") }
    }

    @Test
    fun `direction swap hides result of the opposite direction`() {
        every { favoritesRepository.observeAll() } returns flowOf(emptyList())
        createViewModel()
        applyPendingEntry()

        // Тот же обмен, что делает кнопка-стрелка на экране.
        val before = viewModel.uiState.value
        viewModel.onSourceLangChange(before.targetLang)
        viewModel.onTargetLangChange(before.sourceLang)

        assertEquals("", viewModel.uiState.value.visibleResult)
        viewModel.speakResult()
        io.mockk.verify(exactly = 0) { ttsCoordinator.speak(any(), any()) }
    }

    @Test
    fun `recreated view model restores direction without foreign result`() {
        // Первое создание: подставлена запись ru→en с результатом.
        every { favoritesRepository.observeAll() } returns flowOf(emptyList())
        createViewModel()
        applyPendingEntry()
        assertEquals("hello", viewModel.uiState.value.visibleResult)

        // «Возврат на вкладку»: VM пересоздан, сохранённое направление — en→ru,
        // pending-запись уже потреблена первым VM.
        every { preferences.lastSourceLang } returns flowOf("en")
        every { preferences.lastTargetLang } returns flowOf("ru")
        createViewModel()

        assertEquals(TranslateLanguage.EN, viewModel.uiState.value.sourceLang)
        assertEquals(TranslateLanguage.RU, viewModel.uiState.value.targetLang)
        // Результат при несогласованном направлении отсутствует.
        assertEquals("", viewModel.uiState.value.visibleResult)
    }

    @Test
    fun `saved direction emission hides result bound to another direction`() {
        every { favoritesRepository.observeAll() } returns flowOf(emptyList())
        val lastSource = kotlinx.coroutines.flow.MutableStateFlow<String?>("ru")
        val lastTarget = kotlinx.coroutines.flow.MutableStateFlow<String?>("en")
        every { preferences.lastSourceLang } returns lastSource
        every { preferences.lastTargetLang } returns lastTarget
        createViewModel()
        applyPendingEntry() // ru→en, «hello» — привязан к ru→en
        assertEquals("hello", viewModel.uiState.value.visibleResult)

        // Любая правка DataStore переизлучает сохранённое направление: оно
        // применяется к живому экрану — результат обязан скрыться.
        lastSource.value = "en"
        lastTarget.value = "ru"
        assertEquals(TranslateLanguage.EN, viewModel.uiState.value.sourceLang)
        assertEquals(TranslateLanguage.RU, viewModel.uiState.value.targetLang)
        assertEquals("", viewModel.uiState.value.visibleResult)
        viewModel.speakResult()
        io.mockk.verify(exactly = 0) { ttsCoordinator.speak(any(), any()) }
    }

    // ── Памятка недостающих STT-моделей исходного языка (issue #56) ──

    /** Готовая модель перевода ru→en (закрывает цепочку направления по умолчанию). */
    private fun readyTranslationRuEn() = InstalledModel(
        id = "trans-ru-en",
        category = ModelCategory.TRANSLATION,
        displayName = "ru→en",
        description = "",
        runtime = RuntimeType.MARIAN_SLIMT,
        sizeBytes = 100,
        ramRequiredMb = 100,
        languages = listOf("ru", "en"),
        quality = 4,
        speed = 4,
        url = "",
        urls = emptyList(),
        sha256 = null,
        license = "Apache-2.0",
        minAndroidSdk = 26,
        recommendedRamMb = 0,
        status = ModelStatus.READY,
        progress = 100,
        filePath = "/models/MARIAN_SLIMT/trans-ru-en",
        bytesDone = 100,
        isBuiltin = true
    )

    /** Готовая STT-модель, поддерживающая указанный язык. */
    private fun readyStt(id: String, languages: List<String>) = InstalledModel(
        id = id,
        category = ModelCategory.STT,
        displayName = id,
        description = "",
        runtime = RuntimeType.SHERPA,
        sizeBytes = 100,
        ramRequiredMb = 100,
        languages = languages,
        quality = 4,
        speed = 4,
        url = "",
        urls = emptyList(),
        sha256 = null,
        license = "Apache-2.0",
        minAndroidSdk = 26,
        recommendedRamMb = 0,
        status = ModelStatus.READY,
        progress = 100,
        filePath = "/models/SHERPA/$id",
        bytesDone = 100,
        isBuiltin = true
    )

    /** Карточка каталога STT для языка (hidden — скрытая, напр. whisper). */
    private fun sttCard(id: String, languages: List<String>, hidden: Boolean = false) = ModelCard(
        id = id,
        category = ModelCategory.STT,
        displayName = id,
        description = "",
        runtime = RuntimeType.SHERPA,
        sizeBytes = 100,
        ramRequiredMb = 100,
        languages = languages,
        quality = 4,
        speed = 4,
        url = "",
        urls = emptyList(),
        sha256 = null,
        license = "Apache-2.0",
        minAndroidSdk = 26,
        recommendedRamMb = 0,
        hidden = hidden
    )

    /** Каталог: видимая RU-карточка + скрытая мультаязычная (whisper). */
    private fun sttCatalog() = listOf(
        sttCard("stt-ru-gigaam", listOf("ru")),
        sttCard("stt-whisper-small-multilingual", listOf("ru", "en", "ne"), hidden = true)
    )

    @Test
    fun `stt card for source language suggested when translation ready but stt missing`() {
        every { favoritesRepository.observeAll() } returns flowOf(emptyList())
        every { modelManager.installedModels(ModelCategory.TRANSLATION) } returns
            flowOf(listOf(readyTranslationRuEn()))
        every { modelManager.installedModels(ModelCategory.STT) } returns flowOf(emptyList())
        every { modelManager.catalog } returns flowOf(sttCatalog())
        every { modelManager.sttByLanguage } returns flowOf(emptyMap())
        createViewModel()

        // Перевод ru→en готов — гейт hasTranslationModel истинен, пусто по переводу.
        assertTrue(viewModel.uiState.value.hasTranslationModel)
        assertTrue(viewModel.uiState.value.missingModels.isEmpty())
        // STT для исходного «ru» нет → каталожная видимая карточка в памятку;
        // скрытая whisper-карточка не предлагается.
        assertEquals(listOf("stt-ru-gigaam"), viewModel.uiState.value.missingSttModels.map { it.id })
    }

    @Test
    fun `stt hint empty when ready stt model supports source language`() {
        every { favoritesRepository.observeAll() } returns flowOf(emptyList())
        every { modelManager.installedModels(ModelCategory.TRANSLATION) } returns
            flowOf(listOf(readyTranslationRuEn()))
        every { modelManager.installedModels(ModelCategory.STT) } returns
            flowOf(listOf(readyStt("stt-ru-gigaam", listOf("ru"))))
        every { modelManager.catalog } returns flowOf(sttCatalog())
        every { modelManager.sttByLanguage } returns flowOf(emptyMap())
        createViewModel()

        assertTrue(viewModel.uiState.value.hasTranslationModel)
        assertTrue(viewModel.uiState.value.missingSttModels.isEmpty())
    }

    @Test
    fun `stt hint empty for auto source language`() {
        every { favoritesRepository.observeAll() } returns flowOf(emptyList())
        every { modelManager.installedModels(ModelCategory.TRANSLATION) } returns
            flowOf(listOf(readyTranslationRuEn()))
        every { modelManager.installedModels(ModelCategory.STT) } returns flowOf(emptyList())
        every { modelManager.catalog } returns flowOf(sttCatalog())
        every { modelManager.sttByLanguage } returns flowOf(emptyMap())
        createViewModel()

        viewModel.onSourceLangChange(TranslateLanguage.AUTO)
        assertTrue(viewModel.uiState.value.missingSttModels.isEmpty())
    }

    @Test
    fun `downloadMissingModel starts stt download via model manager`() {
        every { favoritesRepository.observeAll() } returns flowOf(emptyList())
        every { modelManager.installedModels(ModelCategory.TRANSLATION) } returns
            flowOf(listOf(readyTranslationRuEn()))
        every { modelManager.installedModels(ModelCategory.STT) } returns flowOf(emptyList())
        every { modelManager.catalog } returns flowOf(sttCatalog())
        every { modelManager.sttByLanguage } returns flowOf(emptyMap())
        createViewModel()

        val sttHint = viewModel.uiState.value.missingSttModels.first()
        viewModel.downloadMissingModel(sttHint)
        io.mockk.verify(exactly = 1) { modelManager.startDownload(sttCard("stt-ru-gigaam", listOf("ru"))) }
    }

    // ── Раздельные сообщения микрофона из ресурсов (issue #57) ──

    @Test
    fun `permission denied shows resource message only about microphone permission`() {
        every { favoritesRepository.observeAll() } returns flowOf(emptyList())
        createViewModel()

        viewModel.onPermissionDenied()

        assertEquals("MIC_PERM", viewModel.uiState.value.error)
    }

    @Test
    fun `missing stt model shows resource message only about recognition model`() {
        every { favoritesRepository.observeAll() } returns flowOf(emptyList())
        createViewModel()

        viewModel.onSttModelMissing()

        assertEquals("MIC_STT", viewModel.uiState.value.error)
    }

    // ── Индикация записи из потокового состояния рекордера (ревью раунд 2, Н-7) ──

    @Test
    fun `external recorder cancel resets isRecording indicator in ui state`() {
        every { favoritesRepository.observeAll() } returns flowOf(emptyList())
        val recording = kotlinx.coroutines.flow.MutableStateFlow(false)
        every { audioRecorder.recordingFlow } returns recording
        createViewModel()

        // Старт записи (эмит рекордера) — индикатор поднят.
        recording.value = true
        assertTrue(viewModel.uiState.value.isRecording)

        // Внешний AudioRecorder.cancel() (разгрузка рантаймов при ON_STOP) —
        // эмит false: индикатор гаснет, не залипая.
        recording.value = false
        assertFalse(viewModel.uiState.value.isRecording)
    }

    // ── Анти-повтор sticky-статусов координатора (ревью #55) ──

    @Test
    fun `stale coordinator tts error not reshown but new error id shown once`() {
        every { favoritesRepository.observeAll() } returns flowOf(emptyList())
        val status = kotlinx.coroutines.flow.MutableStateFlow(
            TtsPlaybackStatus(
                error = RuntimeError.LanguageNotSupported("system", "ru"),
                errorId = 4
            )
        )
        every { ttsCoordinator.status } returns status
        createViewModel()

        // Ошибка случилась до создания экрана — повторно не показывается.
        org.junit.Assert.assertNull(viewModel.uiState.value.error)

        // Новый экземпляр ошибки (id вырос) — показывается.
        status.value = status.value.copy(
            error = RuntimeError.ModelNotReady,
            errorId = 5
        )
        assertEquals("Модель не готова", viewModel.uiState.value.error)

        // Локальное снятие баннера + новая эмиссия со СТАРЫМ id — не показывается.
        viewModel.onInputTextChanged("новый текст")
        status.value = status.value.copy(downloadProgress = 10)
        org.junit.Assert.assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `stale download failure not shown in new view model, new failure shown`() {
        every { favoritesRepository.observeAll() } returns flowOf(emptyList())
        val status = kotlinx.coroutines.flow.MutableStateFlow(
            TtsPlaybackStatus(
                downloadStatus = TtsDownloadStatus.ERROR,
                downloadErrorId = 2
            )
        )
        every { ttsCoordinator.status } returns status
        createViewModel()

        // ERROR скачивания из прошлого визита — флаг не поднимается.
        assertFalse(viewModel.uiState.value.ttsDownloadFailed)

        // Новая ERROR-стадия — показывается и сохраняется при промежуточных
        // эмиссиях того же экземпляра.
        status.value = status.value.copy(
            downloadStatus = TtsDownloadStatus.ERROR,
            downloadErrorId = 3
        )
        assertTrue(viewModel.uiState.value.ttsDownloadFailed)
        status.value = status.value.copy(isSpeaking = true)
        assertTrue(viewModel.uiState.value.ttsDownloadFailed)

        // Выход из ERROR — флаг снимается.
        status.value = status.value.copy(downloadStatus = TtsDownloadStatus.NONE)
        assertFalse(viewModel.uiState.value.ttsDownloadFailed)
    }

    // ── Разделители склейки сегментов сохраняют переносы строк (ревью раунд 9) ──

    /** Echo-движок: segment → «segment» через [runtimeRouter] для MARIAN_SLIMT. */
    private fun stubEchoEngine(): TranslationEngine {
        every { modelManager.installedModels(ModelCategory.TRANSLATION) } returns
            flowOf(listOf(readyTranslationRuEn()))
        val engine = mockk<TranslationEngine>(relaxed = true)
        coEvery { engine.ensureLoaded(any()) } returns null
        coEvery { engine.translate(any(), any(), any()) } answers {
            Result.success("«${firstArg<String>()}»")
        }
        every { runtimeRouter.translationEngine(RuntimeType.MARIAN_SLIMT) } returns engine
        return engine
    }

    @Test
    fun `translate joins segments preserving newline boundary of source`() {
        every { favoritesRepository.observeAll() } returns flowOf(emptyList())
        val engine = stubEchoEngine()
        createViewModel()
        viewModel.onSourceLangChange(TranslateLanguage.RU)
        viewModel.onTargetLangChange(TranslateLanguage.EN)
        viewModel.onInputTextChanged("Первый абзац.\nВторой абзац.")

        viewModel.translate()

        assertEquals("«Первый абзац.»\n«Второй абзац.»", viewModel.uiState.value.resultText)
        coVerify(exactly = 2) { engine.translate(any(), "ru", "en") }
    }

    /** Готовая модель перевода указанного направления (для цепочек и direct-тестов). */
    private fun readyTranslation(id: String, display: String, langs: List<String>) =
        InstalledModel(
            id = id,
            category = ModelCategory.TRANSLATION,
            displayName = display,
            description = "",
            runtime = RuntimeType.MARIAN_SLIMT,
            sizeBytes = 100,
            ramRequiredMb = 100,
            languages = langs,
            quality = 4,
            speed = 4,
            url = "",
            urls = emptyList(),
            sha256 = null,
            license = "Apache-2.0",
            minAndroidSdk = 26,
            recommendedRamMb = 0,
            status = ModelStatus.READY,
            progress = 100,
            filePath = "/models/MARIAN_SLIMT/$id",
            bytesDone = 100,
            isBuiltin = true
        )

    /** Echo-движок под произвольный набор готовых моделей перевода. */
    private fun stubEchoEngine(models: List<InstalledModel>): TranslationEngine {
        every { modelManager.installedModels(ModelCategory.TRANSLATION) } returns flowOf(models)
        val engine = mockk<TranslationEngine>(relaxed = true)
        coEvery { engine.ensureLoaded(any()) } returns null
        coEvery { engine.translate(any(), any(), any()) } answers {
            Result.success("«${firstArg<String>()}»")
        }
        every { runtimeRouter.translationEngine(RuntimeType.MARIAN_SLIMT) } returns engine
        return engine
    }

    @Test
    fun `translate direct en ru keeps bare newline from two line input`() {
        // Дефект устройства: «hello world\nsecond line» EN→RU — граница закрыта
        // самим переводом строки (без пунктуации); склейка обязана сохранить «\n».
        every { favoritesRepository.observeAll() } returns flowOf(emptyList())
        val engine = stubEchoEngine(listOf(readyTranslation("trans-en-ru", "en→ru", listOf("en", "ru"))))
        createViewModel()
        viewModel.onSourceLangChange(TranslateLanguage.EN)
        viewModel.onTargetLangChange(TranslateLanguage.RU)
        viewModel.onInputTextChanged("hello world\nsecond line")

        viewModel.translate()

        assertEquals("«hello world»\n«second line»", viewModel.uiState.value.resultText)
        coVerify(exactly = 2) { engine.translate(any(), "en", "ru") }
    }

    @Test
    fun `translate chain via en keeps bare newline on both hops`() {
        // Цепочка ru→en→ne: перенос строки исходника должен пережить оба звена.
        every { favoritesRepository.observeAll() } returns flowOf(emptyList())
        val engine = stubEchoEngine(
            listOf(
                readyTranslation("trans-ru-en", "ru→en", listOf("ru", "en")),
                readyTranslation("trans-en-ne", "en→ne", listOf("en", "ne"))
            )
        )
        createViewModel()
        viewModel.onSourceLangChange(TranslateLanguage.RU)
        viewModel.onTargetLangChange(TranslateLanguage.NE)
        viewModel.onInputTextChanged("первая строка\nвторая строка")

        viewModel.translate()

        assertEquals(
            "««первая строка»»\n««вторая строка»»",
            viewModel.uiState.value.resultText
        )
        // 2 сегмента × 2 звена цепочки.
        coVerify(exactly = 4) { engine.translate(any(), any(), any()) }
    }

    @Test
    fun `translate joins sentence-boundary segments with a space`() {
        every { favoritesRepository.observeAll() } returns flowOf(emptyList())
        stubEchoEngine()
        createViewModel()
        viewModel.onSourceLangChange(TranslateLanguage.RU)
        viewModel.onTargetLangChange(TranslateLanguage.EN)
        viewModel.onInputTextChanged("Первое предложение. Второе предложение.")

        viewModel.translate()

        assertEquals(
            "«Первое предложение.» «Второе предложение.»",
            viewModel.uiState.value.resultText
        )
    }
}
