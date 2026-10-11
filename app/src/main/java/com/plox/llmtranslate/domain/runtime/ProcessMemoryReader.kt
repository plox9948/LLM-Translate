package com.plox.llmtranslate.domain.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
     * Текущий PSS процесса, МБ. Binder-запрос к AMS, поэтому выполняется
     * на IO-диспетчере — не вызывать на главном потоке.
     */
    suspend fun processPssMb(): Int = withContext(Dispatchers.IO) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = am?.getProcessMemoryInfo(intArrayOf(Process.myPid()))?.firstOrNull()
        (info?.totalPss ?: 0) / KB_IN_MB
    }
}
