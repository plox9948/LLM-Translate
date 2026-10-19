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
        // Первая штатная эмиссия после ретрая снимает сообщение сбоя (ревью #11):
        // источник ожил — баннер «не удалось загрузить» больше не актуален.
        var retrying = false
        while (isActive) {
            try {
                source().collect { value ->
                    if (retrying) {
                        retrying = false
                        errorState.value = null
                    }
                    onValue(value)
                }
                break
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Сбой наблюдения репозитория")
                retrying = true
                errorState.value = messageRes
                delay(OBSERVE_RETRY_DELAY_MS)
            }
        }
    }
}

/**
 * Запуск репозиторной операции с guard (ревью #65): сбой Room не роняет
 * процесс — ошибка логируется и пишется в [errorState] как id строкового
 * ресурса (sticky до скрытия в UI). [CancellationException] пробрасывается
 * как есть. Общий каркас try/catch+Timber операций ViewModel («История»,
 * «Избранное»).
 */
internal fun launchGuarded(
    scope: CoroutineScope,
    @StringRes messageRes: Int,
    errorState: MutableStateFlow<Int?>,
    logMessage: String,
    block: suspend () -> Unit
) {
    scope.launch {
        try {
            block()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, logMessage)
            errorState.value = messageRes
        }
    }
}
