package com.plox.llmtranslate

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.plox.llmtranslate.BuildConfig
import com.plox.llmtranslate.data.model.ModelRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Точка входа приложения.
 */
@HiltAndroidApp
class OfflineTranslatorApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var modelRepository: ModelRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        cleanObsoleteModels()
    }

    /**
     * Очистка устаревших моделей при старте: записи с рантаймами, которых нет в enum,
     * и их файлы на диске.
     */
    private fun cleanObsoleteModels() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val obsoletePaths = modelRepository.cleanObsoleteRuntimes()
                obsoletePaths.forEach { path ->
                    val file = File(path)
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                }
                if (obsoletePaths.isNotEmpty()) {
                    Timber.d("Удалено файлов устаревших моделей: ${obsoletePaths.size}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Ошибка очистки устаревших моделей")
            }
        }
    }
}
