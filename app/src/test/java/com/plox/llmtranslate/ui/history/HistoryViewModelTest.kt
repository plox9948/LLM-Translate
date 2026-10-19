package com.plox.llmtranslate.ui.history

import com.plox.llmtranslate.R
import com.plox.llmtranslate.data.model.FavoritesRepository
import com.plox.llmtranslate.data.model.TranslationHistoryRepository
import com.plox.llmtranslate.domain.model.PendingTranslationHolder
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Тесты guard-путей [HistoryViewModel] (ревью): сбой Room в deleteEntry/clearAll
 * не роняет процесс — ошибка логируется и публикуется как id строкового
 * ресурса; сбой Room-flow подписки также гасится (observeResilient); при
 * успехе error-состояние не поднимается; clearError сбрасывает его.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val repository = mockk<TranslationHistoryRepository>(relaxed = true)
    private val favoritesRepository = mockk<FavoritesRepository>(relaxed = true)
    private val pendingHolder = mockk<PendingTranslationHolder>(relaxed = true)

    private lateinit var viewModel: HistoryViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { repository.observeRecent() } returns flowOf(emptyList())
        every { favoritesRepository.observeAll() } returns flowOf(emptyList())
        viewModel = HistoryViewModel(repository, pendingHolder, favoritesRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        viewModel.clearError()
    }

    @Test
    fun `deleteEntry on room failure publishes delete error without crash`() {
        coEvery { repository.delete(any()) } throws IllegalStateException("Room down")

        viewModel.deleteEntry(7L)

        assertEquals(R.string.history_delete_failed, viewModel.errorMessageRes.value)
        coVerify(exactly = 1) { repository.delete(7L) }
    }

    @Test
    fun `clearAll on room failure publishes clear error without crash`() {
        coEvery { repository.clear() } throws IllegalStateException("Room down")

        viewModel.clearAll()

        assertEquals(R.string.history_clear_failed, viewModel.errorMessageRes.value)
        coVerify(exactly = 1) { repository.clear() }
    }

    @Test
    fun `successful operations keep error state empty and clearError resets it`() {
        viewModel.deleteEntry(1L)
        assertNull(viewModel.errorMessageRes.value)

        viewModel.clearAll()
        assertNull(viewModel.errorMessageRes.value)

        coEvery { repository.clear() } throws IllegalStateException("Room down")
        viewModel.clearAll()
        assertEquals(R.string.history_clear_failed, viewModel.errorMessageRes.value)

        viewModel.clearError()
        assertNull(viewModel.errorMessageRes.value)
    }

    @Test
    fun `failing history subscription publishes observe error instead of crashing`() {
        every { repository.observeRecent() } throws IllegalStateException("Room flow down")
        val vm = HistoryViewModel(repository, pendingHolder, favoritesRepository)

        // Подписка через observeResilient: сбой источника не роняет процесс,
        // а публикует id сообщения сбоя.
        assertEquals(R.string.history_observe_failed, vm.errorMessageRes.value)
    }
}
