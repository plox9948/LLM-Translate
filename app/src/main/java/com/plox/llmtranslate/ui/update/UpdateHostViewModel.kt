package com.plox.llmtranslate.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plox.llmtranslate.R
import com.plox.llmtranslate.domain.update.ReleaseInfo
import com.plox.llmtranslate.domain.update.UpdateStateHost
import com.plox.llmtranslate.domain.update.UpdateUiState
import com.plox.llmtranslate.ui.ObserveErrorTracker
import com.plox.llmtranslate.ui.launchGuarded
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * ViewModel глобального диалога обновления (issue #95): состояние берётся
 * из синглтона [UpdateStateHost], действия (скачивание/снуз/скрытие)
 * запускаются с guard по образцу [com.plox.llmtranslate.ui.settings.SettingsViewModel].
 */
@HiltViewModel
class UpdateHostViewModel @Inject constructor(
    private val updateStateHost: UpdateStateHost
) : ViewModel() {

    val state: StateFlow<UpdateUiState> = updateStateHost.state

    // Сбой записи snooze/suppressed в DataStore — общий баннер (sticky до
    // скрытия), как у остальных guard-операций.
    private val errors = ObserveErrorTracker()
    val observeErrorRes: StateFlow<Int?> = errors.state

    /** «Обновить сейчас» / «Скачать» из секции настроек. */
    fun download(release: ReleaseInfo) {
        launchGuarded(
            scope = viewModelScope,
            messageRes = R.string.update_download_failed,
            errors = errors,
            logMessage = "Самообновление: сбой запуска скачивания APK",
            marker = "update_download"
        ) {
            // startDownload сам классифицирует сбои в DownloadFailed —
            // guard закрывает только нештатные исключения запуска.
            updateStateHost.startDownload(release)
        }
    }

    /** «Напомнить позже» — snooze 24 ч. */
    fun snooze() {
        launchGuarded(
            scope = viewModelScope,
            messageRes = R.string.settings_write_failed,
            errors = errors,
            logMessage = "Самообновление: не удалось сохранить snooze",
            marker = "update_snooze"
        ) {
            updateStateHost.snooze()
        }
    }

    /** «Больше не показывать» — скрыть диалог для [version]. */
    fun suppress(version: String) {
        launchGuarded(
            scope = viewModelScope,
            messageRes = R.string.settings_write_failed,
            errors = errors,
            logMessage = "Самообновление: не удалось сохранить скрытие версии",
            marker = "update_suppress"
        ) {
            updateStateHost.suppress(version)
        }
    }

    /** Закрытие диалога (кнопка «ОК» или dismissed по тапу вне). */
    fun dismissDialog() {
        updateStateHost.dismissDialog()
    }

    /** Скрыть сообщение о сбое скачивания. */
    fun dismissDownloadError() {
        updateStateHost.dismissDownloadError()
    }

    /** Скрыть баннер сбоя (тап по баннеру). */
    fun clearObserveError() {
        errors.dismissAll()
    }
}
