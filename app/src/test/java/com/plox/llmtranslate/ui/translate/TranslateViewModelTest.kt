package com.plox.llmtranslate.ui.translate

import com.plox.llmtranslate.data.model.FavoritesRepository
import com.plox.llmtranslate.data.model.TranslationHistoryRepository
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.ModelManager
import com.plox.llmtranslate.domain.model.FavoriteEntry
import com.plox.llmtranslate.domain.model.PendingTranslationHolder
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

    private lateinit var viewModel: TranslateViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Создать ViewModel (коллекторы init стартуют здесь — стабы должны быть готовы). */
    private fun createViewModel() {
        viewModel = TranslateViewModel(
            modelManager,
            runtimeRouter,
            ttsRuntime,
            ttsCoordinator,
            audioRecorder,
            preferences,
            historyRepository,
            pendingHolder,
            favoritesRepository
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
}
