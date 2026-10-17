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
 * Тесты [FavoritesViewModel]: флаг озвучивания (выбор «запись + поле»,
 * сброс по завершении фразы, переключение поля, остановка) и поиск по
 * избранному (регистронезависимость, совпадение по оригиналу/переводу,
 * пустой/blank запрос, отсутствие совпадений, сброс запроса).
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

    // --- Поиск по избранному ---

    /** Новый VM со списком [entries] — для тестов фильтрации поиска. */
    private fun newViewModelWith(entries: List<FavoriteEntry>): FavoritesViewModel {
        every { favoritesRepository.observeAll() } returns flowOf(entries)
        return FavoritesViewModel(favoritesRepository, ttsRuntime, preferences, pendingHolder)
    }

    private val searchEntries = listOf(
        FavoriteEntry(1L, "ru", "en", "привет мир", "Hello WORLD", 1000L),
        FavoriteEntry(2L, "en", "ru", "Good morning", "доброе утро", 2000L),
        FavoriteEntry(3L, "ru", "en", "спасибо", "thank you", 3000L)
    )

    @Test
    fun `search matches input case-insensitively ru`() {
        val vm = newViewModelWith(searchEntries)

        vm.onQueryChange("ПРИВЕТ")

        assertEquals(listOf(searchEntries[0]), vm.favorites.value)
    }

    @Test
    fun `search matches input case-insensitively en`() {
        val vm = newViewModelWith(searchEntries)

        vm.onQueryChange("good MORNING")

        assertEquals(listOf(searchEntries[1]), vm.favorites.value)
    }

    @Test
    fun `search matches translated text not only input`() {
        val vm = newViewModelWith(searchEntries)

        vm.onQueryChange("world")

        assertEquals(listOf(searchEntries[0]), vm.favorites.value)
    }

    @Test
    fun `empty query keeps all entries`() {
        val vm = newViewModelWith(searchEntries)

        assertEquals(searchEntries, vm.favorites.value)
    }

    @Test
    fun `blank query keeps all entries`() {
        val vm = newViewModelWith(searchEntries)

        vm.onQueryChange("   ")

        assertEquals(searchEntries, vm.favorites.value)
    }

    @Test
    fun `query without matches yields empty list`() {
        val vm = newViewModelWith(searchEntries)

        vm.onQueryChange("абракадабра")

        assertEquals(emptyList<FavoriteEntry>(), vm.favorites.value)
    }

    @Test
    fun `resetting query restores full list`() {
        val vm = newViewModelWith(searchEntries)

        vm.onQueryChange("асибо")
        assertEquals(listOf(searchEntries[2]), vm.favorites.value)

        vm.onQueryChange("")
        assertEquals(searchEntries, vm.favorites.value)
    }
}
