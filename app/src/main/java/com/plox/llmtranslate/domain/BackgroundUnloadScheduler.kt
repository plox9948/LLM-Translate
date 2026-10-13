package com.plox.llmtranslate.domain

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * Потокобезопасен: планирование и отмена синхронизированы по общему замку.
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
            pendingJob = appScope.launch {
                val minutes = timeoutProvider.timeoutMinutes()
                if (minutes < 0) {
                    Timber.d("Выгрузка при сворачивании отключена настройкой")
                    return@launch
                }
                if (minutes > 0) {
                    Timber.d("Выгрузка рантаймов отложена на %d мин", minutes)
                    delay(TimeUnit.MINUTES.toMillis(minutes.toLong()))
                }
                Timber.d("Истёк тайм-аут фонового простоя: выгружаем рантаймы")
                try {
                    onUnload()
                } catch (e: Exception) {
                    // Коллбэк выполняется в app-scope: ошибка не должна
                    // убивать корутину планирования без записи в лог.
                    Timber.e(e, "Ошибка при отложенной выгрузке рантаймов")
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
