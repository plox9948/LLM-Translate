package com.plox.llmtranslate.domain.update

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.plox.llmtranslate.BuildConfig
import com.plox.llmtranslate.data.prefs.ModelPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Состояние самообновления для UI (issue #95).
 *
 * Диалог показывается на [UpdateUiState.Available] при [showDialog]=true;
 * при [showDialog]=false (версия скрыта «больше не показывать» или активен
 * snooze) диалог не показывается, но секция «Обновление» в настройках видит
 * состояние Available и предлагает скачать релиз вручную.
 */
sealed class UpdateUiState {
    /** Ничего не происходит (покой / автоскрытие преходящих состояний). */
    object Idle : UpdateUiState()

    /** Идёт проверка обновлений. */
    object Checking : UpdateUiState()

    /**
     * Найден релиз новее установленного.
     * @param showDialog показывать ли диалог (false — подавлено «больше не
     *        показывать» или snooze: доступен только ручной запуск из настроек).
     */
    data class Available(
        val release: ReleaseInfo,
        val showDialog: Boolean
    ) : UpdateUiState()

    /** Ручная проверка: обновлений нет (показывается только в настройках). */
    object ManualUpToDate : UpdateUiState()

    /** Ручная проверка не удалась (показывается только в настройках). */
    data class ManualCheckFailed(val reason: UpdateCheckError) : UpdateUiState()

    /** Скачивание APK: [progress] — целые проценты 0..100. */
    data class Downloading(val progress: Int, val release: ReleaseInfo) : UpdateUiState()

    /** APK скачан в [file]; наблюдатель в MainActivity запускает установщик. */
    data class Downloaded(val release: ReleaseInfo, val file: File) : UpdateUiState()

    /** Скачивание не удалось; [message] — готовый текст для показа в UI. */
    data class DownloadFailed(val message: String) : UpdateUiState()
}

/**
 * Человекочитаемое сообщение сбоя проверки/скачивания для UI по образцу
 * `RuntimeError.userMessage()`.
 */
fun UpdateCheckError.userMessage(): String = when (this) {
    UpdateCheckError.Network -> "Не удалось связаться с сервером обновлений"
    UpdateCheckError.NotFound -> "Релиз приложения не найден"
    UpdateCheckError.Parse -> "Не удалось разобрать ответ сервера обновлений"
    UpdateCheckError.NoAsset -> "У релиза нет установочного файла APK"
}

/**
 * Синглтон-хост состояния самообновления (issue #95): единая точка для
 * MainActivity (автопроверка на старте процесса + запуск установщика по
 * [UpdateUiState.Downloaded]), глобального диалога в MainScreen и секции
 * «Обновление» в настройках.
 */
@Singleton
class UpdateStateHost @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: UpdateReleaseRepository,
    private val preferences: ModelPreferences
) {

    companion object {
        /** Duration snooze «напомнить позже», мс (24 часа). */
        private const val SNOOZE_DURATION_MS = 24L * 60L * 60L * 1000L
    }

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state

    /**
     * Гейт доступа в сеть перед автопроверкой (internal — unit-тесты
     * подменяют без ConnectivityManager).
     */
    internal var internetProbe: () -> Boolean = ::hasInternet

    /**
     * Автопроверка при старте/возобновлении процесса: гейты — настройка
     * автопроверок и доступ в сеть. Snooze («напомнить позже») и скрытая
     * версия («больше не показывать») НЕ блокируют саму проверку: состояние
     * [UpdateUiState.Available] выставляется и для них (скачивание доступно
     * из настроек), но с showDialog=false.
     * У авто-проверки UpToDate/сбой состояние не оставляем — [Idle]
     * (преходящее состояние показывать негде).
     */
    suspend fun checkOnAppStart() {
        try {
            if (!preferences.autoUpdateChecksEnabled.first()) return
            if (!internetProbe()) return

            _state.value = UpdateUiState.Checking
            when (val result = repo.checkForUpdate(BuildConfig.VERSION_NAME)) {
                is UpdateCheckResult.UpdateAvailable -> {
                    val suppressed = preferences.updatePromptSuppressedVersion.first()
                    val snoozed = System.currentTimeMillis() < preferences.updateSnoozeUntil.first()
                    _state.value = UpdateUiState.Available(
                        release = result.release,
                        showDialog = !snoozed && suppressed != result.release.version
                    )
                }
                UpdateCheckResult.UpToDate -> _state.value = UpdateUiState.Idle
                is UpdateCheckResult.Failed -> {
                    Timber.w("Самообновление: автопроверка не удалась (%s)", result.reason)
                    _state.value = UpdateUiState.Idle
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Самообновление: сбой автопроверки")
            _state.value = UpdateUiState.Idle
        }
    }

    /**
     * Ручная проверка из настроек: без гейтов автопроверки; результат
     * показывается в секции «Обновление» ([UpdateUiState.ManualUpToDate] /
     * [UpdateUiState.ManualCheckFailed]); при найденном релизе диалог
     * показывается независимо от snooze и suppressed ([Available].showDialog=true).
     */
    suspend fun checkManually() {
        _state.value = UpdateUiState.Checking
        try {
            when (val result = repo.checkForUpdate(BuildConfig.VERSION_NAME)) {
                is UpdateCheckResult.UpdateAvailable ->
                    _state.value = UpdateUiState.Available(result.release, showDialog = true)
                UpdateCheckResult.UpToDate -> _state.value = UpdateUiState.ManualUpToDate
                is UpdateCheckResult.Failed ->
                    _state.value = UpdateUiState.ManualCheckFailed(result.reason)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Самообновление: сбой ручной проверки")
            _state.value = UpdateUiState.ManualCheckFailed(UpdateCheckError.Network)
        }
    }

    /** «Напомнить позже»: snooze на 24 часа + скрытие диалога. */
    suspend fun snooze() {
        preferences.setUpdateSnoozeUntil(System.currentTimeMillis() + SNOOZE_DURATION_MS)
        hideDialog()
    }

    /** «Больше не показывать»: скрытие этой версии + скрытие диалога. */
    suspend fun suppress(version: String) {
        preferences.setUpdatePromptSuppressedVersion(version)
        hideDialog()
    }

    /** Скрыть диалог (тап вне/назад). */
    fun dismissDialog() {
        hideDialog()
    }

    /**
     * Скачивание APK [release]: транзит [UpdateUiState.Downloading] с
     * процентами → [UpdateUiState.Downloaded] (файл для установщика) либо
     * [UpdateUiState.DownloadFailed] с user-facing текстом.
     */
    suspend fun startDownload(release: ReleaseInfo) {
        _state.value = UpdateUiState.Downloading(0, release)
        try {
            when (val result = repo.downloadApk(release) { percent ->
                _state.value = UpdateUiState.Downloading(percent, release)
            }) {
                is DownloadApkResult.Success ->
                    _state.value = UpdateUiState.Downloaded(release, result.file)
                is DownloadApkResult.Failed -> {
                    Timber.w("Самообновление: скачивание APK не удалось (%s)", result.error)
                    _state.value = UpdateUiState.DownloadFailed(result.error.userMessage())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Самообновление: нештатный сбой скачивания APK")
            _state.value = UpdateUiState.DownloadFailed(UpdateCheckError.Network.userMessage())
        }
    }

    /**
     * Сброс преходящих результатов в [UpdateUiState.Idle]:
     * [UpdateUiState.Downloaded] — после запуска установщика из MainActivity
     * (иначе пересозданная активность повторно Collect-ит Downloaded и
     * дважды открывает установщик, а пере-скачивание и так очищает кэш);
     * ручные результаты и сбой скачивания — когда экран их уже показал.
     */
    fun consumeFinished() {
        val current = _state.value
        if (current is UpdateUiState.Downloaded ||
            current is UpdateUiState.DownloadFailed ||
            current == UpdateUiState.ManualUpToDate ||
            current is UpdateUiState.ManualCheckFailed
        ) {
            _state.value = UpdateUiState.Idle
        }
    }

    /** Скрыть сообщение о сбое скачивания (кнопка «ОК» в диалоге). */
    fun dismissDownloadError() {
        if (_state.value is UpdateUiState.DownloadFailed) {
            _state.value = UpdateUiState.Idle
        }
    }

    /** Available → тот же Available, но без показа диалога. */
    private fun hideDialog() {
        val current = _state.value
        if (current is UpdateUiState.Available && current.showDialog) {
            _state.value = current.copy(showDialog = false)
        }
    }

    /** Есть ли активное сетевое подключение (ACCESS_NETWORK_STATE). */
    @SuppressLint("MissingPermission")
    private fun hasInternet(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
