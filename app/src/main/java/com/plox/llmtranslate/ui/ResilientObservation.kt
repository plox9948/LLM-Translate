package com.plox.llmtranslate.ui

import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/** Пауза перед возобновлением подписки после сбоя источника наблюдения. */
private const val OBSERVE_RETRY_DELAY_MS = 2_000L

/**
 * Учёт множества активных сбоев наблюдения/guard-операций одного ViewModel
 * (повторное ревью): общий баннер показывает id строки последней зарегистрированной
 * ошибки и снимается только когда множество активных ошибок пусто — раньше
 * восстановление одного источника гасило баннер при ещё мёртвом другом.
 * Ключом служит маркер подписки/операции (у каждого источника свой).
 */
internal class ObserveErrorTracker {
    private val _state = MutableStateFlow<Int?>(null)
    val state: StateFlow<Int?> = _state

    /** marker → resId; порядок вставки — баннер несёт последнюю ошибку. */
    private val active = LinkedHashMap<String, Int>()

    /** Зарегистрировать сбой источника/операции [marker] с текстом [messageRes]. */
    fun reportFailure(marker: String, @StringRes messageRes: Int) {
        active[marker] = messageRes
        publish()
    }

    /** Снять сбой источника/операции [marker] (источник ожил). */
    fun recover(marker: String) {
        if (active.remove(marker) != null) publish()
    }

    /** Скрыть баннер в UI (тап по баннеру/диалогу): снимает все ошибки. */
    fun dismissAll() {
        if (active.isNotEmpty()) {
            active.clear()
            publish()
        } else {
            _state.value = null
        }
    }

    private fun publish() {
        _state.value = active.values.lastOrNull()
    }
}

/**
 * Наблюдение репозиторного (Room) flow без риска уронить процесс (issue #67):
 * `viewModelScope.launch` без CoroutineExceptionHandler роняет приложение при
 * любом исключении источника. Выбрана перезапускаемая подписка в try/catch
 * вокруг всего collect-цикла (штатное завершение flow — выход без
 * переподписки), а не `.catch` на источнике: catch подменяет поток,
 * исчерпанный ошибкой, завершившимся — список остался бы без будущих
 * обновлений.
 *
 * Сбой и восстановление регистрируются в [errors] под маркером [marker] —
 * общий баннер живёт, пока хоть один источник остаётся сломанным (ревью).
 */
internal fun <T> observeResilient(
    scope: CoroutineScope,
    source: () -> Flow<T>,
    @StringRes messageRes: Int,
    errors: ObserveErrorTracker,
    marker: String,
    onValue: suspend (T) -> Unit
) {
    scope.launch {
        // Первая штатная эмиссия после ретрая снимает сообщение сбоя (ревью #11):
        // источник ожил — его ошибка больше не актуальна (баннер остаётся,
        // если есть другие активные сбои, re-review).
        var retrying = false
        while (isActive) {
            try {
                source().collect { value ->
                    if (retrying) {
                        retrying = false
                        errors.recover(marker)
                    }
                    onValue(value)
                }
                break
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Сбой наблюдения репозитория")
                retrying = true
                errors.reportFailure(marker, messageRes)
                delay(OBSERVE_RETRY_DELAY_MS)
            }
        }
    }
}

/**
 * Запуск репозиторной операции с guard (ревью #65): сбой Room не роняет
 * процесс — ошибка логируется и регистрируется в [errors] под маркером
 * [marker] (sticky до скрытия в UI). [CancellationException] пробрасывается
 * как есть. Общий каркас try/catch+Timber операций ViewModel («История»,
 * «Избранное»).
 */
internal fun launchGuarded(
    scope: CoroutineScope,
    @StringRes messageRes: Int,
    errors: ObserveErrorTracker,
    logMessage: String,
    marker: String = "operation:$messageRes",
    block: suspend () -> Unit
) {
    scope.launch {
        try {
            block()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, logMessage)
            errors.reportFailure(marker, messageRes)
        }
    }
}
