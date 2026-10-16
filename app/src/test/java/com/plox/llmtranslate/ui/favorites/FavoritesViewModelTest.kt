package com.plox.llmtranslate.ui.favorites

import com.plox.llmtranslate.data.model.FavoritesRepository
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.model.FavoriteEntry
import com.plox.llmtranslate.domain.model.PendingTranslationHolder
import com.plox.llmtranslate.domain.runtime.TtsRuntime
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Тесты флага озвучивания [FavoritesViewModel]: выбор «запись + поле»,
 * сброс по завершении фразы, переключение поля и остановка.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModelTest {

    private val entry = FavoriteEntry(
        id = 7L,
        sourceLang = "ru",
        targetLang = "en",
        inputText = "privet",
        resultText = "hello",
        createdAt = 1000L
    )

    private val favoritesRepository = mockk<FavoritesRepository>(relaxed = true)
    private val ttsRuntime = mockk<TtsRuntime>(relaxed = true)
    private val preferences = mockk<ModelPreferences>(relaxed = true)
    private val pendingHolder = mockk<PendingTranslationHolder>(relaxed = true)

    // Колбэки onDone по языку фразы — вызываются тестом как «озвучивание завершено».
    private val doneCallbacks = mutableMapOf<String, () -> Unit>()

    private lateinit var viewModel: FavoritesViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { favoritesRepository.observeAll() } returns flowOf(listOf(entry))
        every { preferences.ttsEngine } returns flowOf("system")
        coEvery { ttsRuntime.speak(any(), any(), captureLambda()) } answers {
            doneCallbacks[secondArg()] = thirdArg()
            null
        }
        viewModel = FavoritesViewModel(favoritesRepository, ttsRuntime, preferences, pendingHolder)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `speak input sets selection and onDone resets it`() {
        viewModel.speak(entry, FavoriteSpeakField.INPUT)

        assertEquals(SpeakingSelection(7L, FavoriteSpeakField.INPUT), viewModel.speaking.value)

        doneCallbacks.getValue("ru").invoke()
        assertNull(viewModel.speaking.value)
    }

    @Test
    fun `switching field retargets selection and stale onDone keeps new one`() {
        viewModel.speak(entry, FavoriteSpeakField.INPUT)
        viewModel.speak(entry, FavoriteSpeakField.RESULT)

        assertEquals(SpeakingSelection(7L, FavoriteSpeakField.RESULT), viewModel.speaking.value)

        // Завершение более ранней фразы оригинала не сбрасывает текущую.
        doneCallbacks.getValue("ru").invoke()
        assertEquals(SpeakingSelection(7L, FavoriteSpeakField.RESULT), viewModel.speaking.value)

        doneCallbacks.getValue("en").invoke()
        assertNull(viewModel.speaking.value)
    }

    @Test
    fun `stopSpeaking clears flag and stops tts`() {
        viewModel.speak(entry, FavoriteSpeakField.RESULT)

        viewModel.stopSpeaking()

        assertNull(viewModel.speaking.value)
        verify { ttsRuntime.stop() }
    }

    @Test
    fun `speak of blank field does nothing`() {
        val blankResult = entry.copy(resultText = "   ")

        viewModel.speak(blankResult, FavoriteSpeakField.RESULT)

        assertNull(viewModel.speaking.value)
        coVerify(exactly = 0) { ttsRuntime.speak(any(), any(), any()) }
    }
}
