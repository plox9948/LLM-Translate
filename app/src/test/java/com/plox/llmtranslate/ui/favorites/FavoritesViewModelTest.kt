package com.plox.llmtranslate.ui.favorites

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.plox.llmtranslate.data.model.FavoritesRepository
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.ModelManager
import com.plox.llmtranslate.domain.model.FavoriteEntry
import com.plox.llmtranslate.domain.model.PendingTranslationHolder
import com.plox.llmtranslate.domain.runtime.DownloadWatchClock
import com.plox.llmtranslate.domain.runtime.PiperTtsRuntime
import com.plox.llmtranslate.domain.runtime.TtsPlaybackCoordinator
import com.plox.llmtranslate.domain.runtime.TtsRuntime
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
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

    // Реальный координатор TTS-плейбека на фейках рантаймов и ModelManager
    // (назначений TTS нет → всегда системный движок, поведение как раньше).
    private val modelManager = mockk<ModelManager>(relaxed = true)
    private val piperRuntime = mockk<PiperTtsRuntime>(relaxed = true)
    private val ttsCoordinator = TtsPlaybackCoordinator(
        ttsRuntime,
        piperRuntime,
        modelManager,
        CoroutineScope(UnconfinedTestDispatcher()),
        DownloadWatchClock { System.currentTimeMillis() },
        mockk<Context>()
    )

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
        viewModel = FavoritesViewModel(
            favoritesRepository, ttsRuntime, ttsCoordinator, preferences, pendingHolder,
            SavedStateHandle()
        )
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

    // ── Восстановление «стоп»-состояния при пересоздании ViewModel (ревью #83) ──

    @Test
    fun `recreated view model restores stop state for live phrase`() {
        // Первая VM запускает озвучивание; «гибель» VM при живой фразе
        // координатора-синглтона.
        viewModel.speak(entry, FavoriteSpeakField.INPUT)

        val recreated = FavoritesViewModel(
            favoritesRepository, ttsRuntime, ttsCoordinator, preferences, pendingHolder,
            SavedStateHandle()
        )
        // Выбор восстановлен привязкой текста живой фразы к записи.
        assertEquals(SpeakingSelection(7L, FavoriteSpeakField.INPUT), recreated.speaking.value)

        // Завершение фразы — стоп-состояние снимается как обычно.
        doneCallbacks.getValue("ru").invoke()
        assertNull(recreated.speaking.value)
    }

    @Test
    fun `recreated view model keeps null selection for foreign live phrase`() {
        // Живая фраза не из избранного (текст без совпадения с записями).
        ttsCoordinator.speak("не из избранного", "ru")

        val recreated = FavoritesViewModel(
            favoritesRepository, ttsRuntime, ttsCoordinator, preferences, pendingHolder,
            SavedStateHandle()
        )
        assertNull(recreated.speaking.value)
    }

    @Test
    fun `recreated view model restores stop state when favorites resolve after status`() {
        // Каталог избранного доходит асинхронно: попытка при эмиссии статуса
        // не находит список — привязка выполняется при эмиссии избранного.
        val favoritesFlow = kotlinx.coroutines.flow.MutableStateFlow<List<FavoriteEntry>>(emptyList())
        every { favoritesRepository.observeAll() } returns favoritesFlow
        viewModel.speak(entry, FavoriteSpeakField.RESULT)

        // «Пересоздание»: список пуст, статус живой.
        val recreated = FavoritesViewModel(
            favoritesRepository, ttsRuntime, ttsCoordinator, preferences, pendingHolder,
            SavedStateHandle()
        )
        assertNull(recreated.speaking.value)

        // Избранное дошло — привязка восстановлена.
        favoritesFlow.value = listOf(entry)
        assertEquals(SpeakingSelection(7L, FavoriteSpeakField.RESULT), recreated.speaking.value)
    }

    @Test
    fun `regression doc - restore highlight binds by first text match of live phrase`() {
        // Регресс-док текущей семантики restoreSpeakingFromCoordinator (ревью #83):
        // TtsPlaybackCoordinator-статус хранит только ТЕКСТ живой фразы (без id
        // записи), поэтому привязка «стоп»-подсветки идёт совпадением текста с
        // первой записью в списке избранного. Живая фраза «hello» — это и
        // перевод записи 7, и оригинал записи 9; подсветка ложится на первую
        // совпавшую запись (7, поле перевода), а не на «более точную» по типу
        // текста. Полная привязка потребовала бы id фразы в статусе координатора.
        val duplicate = entry.copy(id = 9L, inputText = "hello", resultText = "hi")
        every { favoritesRepository.observeAll() } returns flowOf(listOf(entry, duplicate))

        // Фраза запущена координатором напрямую (не через эту VM) — сценарий
        // recreation: VM гибнет при живой фразе синглтона-координатора.
        ttsCoordinator.speak("hello", "en")

        val recreated = FavoritesViewModel(
            favoritesRepository, ttsRuntime, ttsCoordinator, preferences, pendingHolder,
            SavedStateHandle()
        )
        assertEquals(SpeakingSelection(7L, FavoriteSpeakField.RESULT), recreated.speaking.value)
    }

    /** Новый VM со списком [entries] — для тестов фильтрации поиска. */
    private fun newViewModelWith(entries: List<FavoriteEntry>): FavoritesViewModel {
        every { favoritesRepository.observeAll() } returns flowOf(entries)
        return FavoritesViewModel(
            favoritesRepository, ttsRuntime, ttsCoordinator, preferences, pendingHolder,
            SavedStateHandle()
        )
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

    @Test
    fun `search query survives view model recreation via saved state handle`() {
        // Поиск хранится в SavedStateHandle: пересозданный ViewModel (гибель
        // процесса, смена конфигурации) восстанавливает и запрос, и фильтр.
        val handle = SavedStateHandle()
        every { favoritesRepository.observeAll() } returns flowOf(searchEntries)
        val first = FavoritesViewModel(
            favoritesRepository, ttsRuntime, ttsCoordinator, preferences, pendingHolder, handle
        )
        first.onQueryChange("мир")
        assertEquals(listOf(searchEntries[0]), first.favorites.value)

        val recreated = FavoritesViewModel(
            favoritesRepository, ttsRuntime, ttsCoordinator, preferences, pendingHolder, handle
        )
        assertEquals("мир", recreated.searchQuery.value)
        assertEquals(listOf(searchEntries[0]), recreated.favorites.value)
    }
}
