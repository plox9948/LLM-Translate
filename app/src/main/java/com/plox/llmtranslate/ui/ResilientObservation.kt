package com.plox.llmtranslate.ui

import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/** Пауза перед возобновлением подписки после сбоя источника наблюдения. */
private const val OBSERVE_RETRY_DELAY_MS = 2_000L

/**
 * Наблюдение репозиторного (Room) flow без риска уронить процесс (issue #67):
 * `viewModelScope.launch` без CoroutineExceptionHandler роняет приложение при
 * любом исключении источника. Выбрана перезапускаемая подписка в try/catch
 * вокруг всего collect-цикла (штатное завершение flow — выход без
 * переподписки), а не `.catch` на источнике: catch подменяет поток,
 * исчерпанный ошибкой, завершившимся — список остался бы без будущих
 * обновлений.
 */
internal fun <T> observeResilient(
    scope: CoroutineScope,
    source: () -> Flow<T>,
    @StringRes messageRes: Int,
    errorState: MutableStateFlow<Int?>,
    onValue: suspend (T) -> Unit
) {
    scope.launch {
        while (isActive) {
            try {
                source().collect { value -> onValue(value) }
                break
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Сбой наблюдения репозитория")
                errorState.value = messageRes
                delay(OBSERVE_RETRY_DELAY_MS)
            }
        }
    }
}
