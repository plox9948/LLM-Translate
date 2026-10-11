package com.plox.llmtranslate.domain.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private const val KB_IN_MB = 1024

/**
 * Фактическое потребление памяти процесса.
 *
 * Используется binder-запрос к AMS ([ActivityManager.getProcessMemoryInfo]),
 * а не Debug.getMemoryInfo: внутри процесса Debug не видит native-память
 * инференс-движков (ONNX/sherpa), а AMS возвращает полный PSS процесса.
 */
@Singleton
class ProcessMemoryReader @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Текущий PSS процесса, МБ, или null, если запрос к AMS не удался
     * (AMS недоступен, binder-вызов бросил исключение или вернул пустой
     * массив — 0 при этом не считается валидным значением). Binder-запрос
     * к AMS, поэтому выполняется на IO-диспетчере — не вызывать на главном
     * потоке.
     */
    suspend fun processPssMb(): Int? = withContext(Dispatchers.IO) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return@withContext null
            val info = am.getProcessMemoryInfo(intArrayOf(Process.myPid()))?.firstOrNull()
                ?: return@withContext null
            info.totalPss / KB_IN_MB
        } catch (e: Exception) {
            Timber.w(e, "Не удалось прочитать PSS процесса")
            null
        }
    }
}
