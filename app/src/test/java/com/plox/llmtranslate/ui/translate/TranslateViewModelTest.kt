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
import com.plox.llmtranslate.domain.model.RuntimeType
import com.plox.llmtranslate.domain.model.TranslationHistoryEntry
import com.plox.llmtranslate.domain.runtime.AudioRecorder
import com.plox.llmtranslate.domain.runtime.RuntimeRouter
import com.plox.llmtranslate.domain.runtime.TtsPlaybackCoordinator
import com.plox.llmtranslate.domain.runtime.TtsRuntime
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
}
