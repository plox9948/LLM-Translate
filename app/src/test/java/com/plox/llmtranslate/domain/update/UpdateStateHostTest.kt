package com.plox.llmtranslate.domain.update

import app.cash.turbine.test
import android.content.Context
import com.plox.llmtranslate.data.prefs.ModelPreferences
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Тесты гейтов и транзитов [UpdateStateHost] (issue #95): выключенная
 * автопроверка, отсутствие сети и snooze в будущем не запускают сетевую
 * проверку; suppressed-версия оставляет иконку (Available) без диалога
 * (showDialog=false); ручная проверка идёт без гейтов; скачивание — транзит
 * Downloading→Downloaded; сбои — ManualCheckFailed / DownloadFailed с
 * user-facing текстом.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateStateHostTest {

    private val release = ReleaseInfo(
        tagName = "v0.9.9",
        version = "0.9.9",
        releaseUrl = "https://github.com/plox9948/LLM-Translate/releases/tag/v0.9.9",
        apkUrl = "https://example.com/app.apk",
        assetName = "app-release.apk"
    )

    /** Рукописный фиксированный репозиторий: скриптованный результат + счётчик проверок. */
    private class FakeRepo(
        var checkResult: UpdateCheckResult = UpdateCheckResult.UpToDate,
        var downloadResult: DownloadApkResult =
            DownloadApkResult.Success(File("update.apk")),
        var progressSteps: List<Int> = emptyList(),
        var throwOnCheck: Boolean = false
    ) : UpdateReleaseRepository {
        var checkCalls = 0
        var lastDownloadedRelease: ReleaseInfo? = null

        override suspend fun checkForUpdate(currentVersion: String): UpdateCheckResult {
            checkCalls++
            if (throwOnCheck) throw IOException("offline")
            return checkResult
        }

        override suspend fun downloadApk(
            release: ReleaseInfo,
            onProgress: (Int) -> Unit
        ): DownloadApkResult {
            lastDownloadedRelease = release
            progressSteps.forEach(onProgress)
            return downloadResult
        }
    }

    private val preferences = mockk<ModelPreferences>(relaxed = true)
    private lateinit var repo: FakeRepo
    private lateinit var host: UpdateStateHost

    @Before
    fun setup() {
        repo = FakeRepo()
        val context = mockk<Context>(relaxed = true)
        host = UpdateStateHost(context, repo, preferences)
        // Сеть «есть» по умолчанию: фиктивная проверка не зависит от
        // фиктивного ConnectivityManager mockk.
        host.internetProbe = { true }
        // По умолчанию: автопроверки включены, сеть есть, snooze/suppressed пусты.
        every { preferences.autoUpdateChecksEnabled } returns flowOf(true)
        every { preferences.updateSnoozeUntil } returns flowOf(0L)
        every { preferences.updatePromptSuppressedVersion } returns flowOf("")
    }

    @Test
    fun `disabled auto checks skip the network check entirely`() = runTest {
        every { preferences.autoUpdateChecksEnabled } returns flowOf(false)
        repo.checkResult = UpdateCheckResult.UpdateAvailable(release)

        host.checkOnAppStart()

        assertEquals(0, repo.checkCalls)
        assertEquals(UpdateUiState.Idle, host.state.value)
    }

    @Test
    fun `snooze in the future blocks the auto check`() = runTest {
        every { preferences.updateSnoozeUntil } returns
            flowOf(System.currentTimeMillis() + 60_000L)
        repo.checkResult = UpdateCheckResult.UpdateAvailable(release)

        host.checkOnAppStart()

        assertEquals(0, repo.checkCalls)
        assertEquals(UpdateUiState.Idle, host.state.value)
    }

    @Test
    fun `no internet skips the auto check`() = runTest {
        host.internetProbe = { false }
        repo.checkResult = UpdateCheckResult.UpdateAvailable(release)

        host.checkOnAppStart()

        assertEquals(0, repo.checkCalls)
        assertEquals(UpdateUiState.Idle, host.state.value)
    }

    @Test
    fun `suppressed version keeps Available state but hides the dialog`() = runTest {
        every { preferences.updatePromptSuppressedVersion } returns flowOf("0.9.9")
        repo.checkResult = UpdateCheckResult.UpdateAvailable(release)

        host.checkOnAppStart()

        val state = host.state.value
        assertTrue(state is UpdateUiState.Available)
        assertEquals(release, (state as UpdateUiState.Available).release)
        assertEquals(false, state.showDialog)
    }

    @Test
    fun `not suppressed version shows the dialog`() = runTest {
        repo.checkResult = UpdateCheckResult.UpdateAvailable(release)

        host.checkOnAppStart()

        val state = host.state.value as UpdateUiState.Available
        assertTrue(state.showDialog)
    }

    @Test
    fun `auto upToDate and failed check leave the state idle`() = runTest {
        repo.checkResult = UpdateCheckResult.UpToDate
        host.checkOnAppStart()
        assertEquals(UpdateUiState.Idle, host.state.value)

        repo.checkResult = UpdateCheckResult.Failed(UpdateCheckError.Network)
        host.checkOnAppStart()
        assertEquals(UpdateUiState.Idle, host.state.value)
    }

    @Test
    fun `manual check ignores gates and forces the dialog`() = runTest {
        // Автогейты «закрыты», но ручная проверка обязана проверить и показать.
        every { preferences.autoUpdateChecksEnabled } returns flowOf(false)
        every { preferences.updateSnoozeUntil } returns
            flowOf(System.currentTimeMillis() + 3_600_000L)
        every { preferences.updatePromptSuppressedVersion } returns flowOf("0.9.9")
        repo.checkResult = UpdateCheckResult.UpdateAvailable(release)

        host.checkManually()

        assertEquals(1, repo.checkCalls)
        val state = host.state.value as UpdateUiState.Available
        assertTrue(state.showDialog)
        assertEquals(release, state.release)
    }

    @Test
    fun `manual upToDate and failure surface in settings states`() = runTest {
        repo.checkResult = UpdateCheckResult.UpToDate
        host.checkManually()
        assertEquals(UpdateUiState.ManualUpToDate, host.state.value)

        repo.checkResult = UpdateCheckResult.Failed(UpdateCheckError.Parse)
        host.checkManually()
        assertEquals(UpdateUiState.ManualCheckFailed(UpdateCheckError.Parse), host.state.value)
    }

    @Test
    fun `manual check crash maps to failed state instead of throwing`() = runTest {
        repo.throwOnCheck = true

        host.checkManually()

        assertEquals(UpdateUiState.ManualCheckFailed(UpdateCheckError.Network), host.state.value)
    }

    @Test
    fun `start download transits progress to downloaded`() = runTest {
        val file = File("cached-update.apk")
        repo.downloadResult = DownloadApkResult.Success(file)
        repo.progressSteps = listOf(40, 80)

        host.state.test {
            assertEquals(UpdateUiState.Idle, awaitItem())
            host.startDownload(release)
            assertEquals(UpdateUiState.Downloading(0, release), awaitItem())
            assertEquals(UpdateUiState.Downloading(40, release), awaitItem())
            assertEquals(UpdateUiState.Downloading(80, release), awaitItem())
            assertEquals(UpdateUiState.Downloaded(release, file), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(release, repo.lastDownloadedRelease)
    }

    @Test
    fun `download failure maps to user-facing message state`() = runTest {
        repo.downloadResult = DownloadApkResult.Failed(UpdateCheckError.Network)

        host.startDownload(release)

        val state = host.state.value as UpdateUiState.DownloadFailed
        assertEquals(UpdateCheckError.Network.userMessage(), state.message)
        host.dismissDownloadError()
        assertEquals(UpdateUiState.Idle, host.state.value)
    }

    @Test
    fun `snooze writes 24h deadline and hides the dialog keeping the icon`() = runTest {
        repo.checkResult = UpdateCheckResult.UpdateAvailable(release)
        host.checkManually()
        assertTrue((host.state.value as UpdateUiState.Available).showDialog)

        val before = System.currentTimeMillis()
        host.snooze()

        val state = host.state.value as UpdateUiState.Available
        assertEquals(false, state.showDialog)
        val untilSlot = slot<Long>()
        coVerify(exactly = 1) {
            preferences.setUpdateSnoozeUntil(capture(untilSlot))
        }
        assertTrue(
            untilSlot.captured >= before + 24L * 60L * 60L * 1000L &&
                untilSlot.captured <= System.currentTimeMillis() + 24L * 60L * 60L * 1000L
        )
    }

    @Test
    fun `suppress writes the version and hides the dialog keeping the icon`() = runTest {
        repo.checkResult = UpdateCheckResult.UpdateAvailable(release)
        host.checkManually()

        host.suppress(release.version)

        val state = host.state.value as UpdateUiState.Available
        assertEquals(false, state.showDialog)
        assertEquals(release, state.release)
        coVerify(exactly = 1) { preferences.setUpdatePromptSuppressedVersion("0.9.9") }
    }

    @Test
    fun `consumeFinished resets transient results to idle`() = runTest {
        repo.checkResult = UpdateCheckResult.UpToDate
        host.checkManually()
        assertEquals(UpdateUiState.ManualUpToDate, host.state.value)

        host.consumeFinished()
        assertEquals(UpdateUiState.Idle, host.state.value)
    }
}
