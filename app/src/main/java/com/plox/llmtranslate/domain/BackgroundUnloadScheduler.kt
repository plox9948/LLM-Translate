package com.plox.llmtranslate.domain

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Поставщик тайм-аута отложенной выгрузки рантаймов, минуты.
 * Реализация читает настройку на момент сворачивания приложения.
 */
fun interface UnloadTimeoutProvider {

    /**
     * Тайм-аут в минутах: 0 — выгружать сразу,
     * [BackgroundUnloadScheduler.NEVER_UNLOAD] — не выгружать.
     */
    suspend fun timeoutMinutes(): Int
}

/**
 * Планировщик отложенной выгрузки рантаймов при сворачивании приложения.
 *
 * [schedule] ставит отложенный вызов коллбэка выгрузки в процессный
 * [CoroutineScope] (Hilt-синглтон, живёт дольше активностей), а [cancel]
 * снимает его при возврате процесса в foreground до истечения тайм-аута.
 * Тайм-аут читается через [UnloadTimeoutProvider] на момент каждого [schedule],
 * поэтому смена настройки применяется сразу, без перезапуска процесса.
 *
 * Потокобезопасен: вход [schedule] и [cancel] сериализованы общим замком;
 * сам коллбэк [onUnload] выполняется вне замка (после долгого ожидания),
 * поэтому длительная выгрузка не блокирует планирование и отмену.
 */
@Singleton
class BackgroundUnloadScheduler @Inject constructor(
    private val appScope: CoroutineScope,
    private val timeoutProvider: UnloadTimeoutProvider
) {

    private val lock = Any()

    /** Текущая отложенная выгрузка (null — ничего не запланировано). */
    private var pendingJob: Job? = null

    /**
     * Запланировать выгрузку: отменить предыдущее ожидание и начать новое
     * с актуальным тайм-аутом. Отрицательный тайм-аут отключает выгрузку,
     * ноль — выгружает сразу без ожидания.
     */
    fun schedule(onUnload: () -> Unit) {
        synchronized(lock) {
            pendingJob?.cancel()
            val job = appScope.launch {
                val minutes = try {
                    timeoutProvider.timeoutMinutes()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Ревью: сбой чтения настройки (DataStore повреждён/IO) не
                    // должен выгружать рантаймы внезапно — безопасно считаем
                    // «не выгружать», настройка прочитается при следующем schedule.
                    Timber.e(e, "Не удалось прочитать тайм-аут выгрузки — считаю NEVER_UNLOAD")
                    NEVER_UNLOAD
                }
                if (minutes < 0) {
                    Timber.d("Выгрузка при сворачивании отключена настройкой")
                    return@launch
                }
                if (minutes > 0) {
                    Timber.d("Выгрузка рантаймов отложена на %d мин", minutes)
                    delay(TimeUnit.MINUTES.toMillis(minutes.toLong()))
                }
                // Ревью: граница отмены после долгого delay — job мог быть
                // снят (возврат в foreground) до входа в коллбэк выгрузки.
                currentCoroutineContext().ensureActive()
                Timber.d("Истёк тайм-аут фонового простоя: выгружаем рантаймы")
                try {
                    onUnload()
                } catch (e: CancellationException) {
                    // Отмена корутины планирования (возврат в foreground) —
                    // не ошибка, всплываем выше.
                    throw e
                } catch (e: Exception) {
                    // Коллбэк выполняется в app-scope: ошибка не должна
                    // убивать корутину планирования без записи в лог.
                    Timber.e(e, "Ошибка при отложенной выгрузке рантаймов")
                }
            }
            pendingJob = job
            // Рецензия: завершившийся job (выгрузка отработала, тайм-аут
            // отключён настройкой или job отменён) не должен переживать в
            // поле — иначе оно удерживает Job и замыкание с onUnload до
            // следующего schedule()/cancel().
            job.invokeOnCompletion {
                synchronized(lock) {
                    if (pendingJob === job) pendingJob = null
                }
            }
        }
    }

    /**
     * Отменить отложенную выгрузку (возврат процесса в foreground).
     * Повторные вызовы и вызовы без запланированной выгрузки безопасны.
     */
    fun cancel() {
        synchronized(lock) {
            pendingJob?.cancel()
            pendingJob = null
        }
    }

    companion object {
        /** Значение настройки «не выгружать». */
        const val NEVER_UNLOAD = -1

        /** Значение настройки «выгружать сразу». */
        const val UNLOAD_IMMEDIATELY = 0
    }
}
