package com.plox.llmtranslate

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.plox.llmtranslate.BuildConfig
import com.plox.llmtranslate.data.catalog.RemoteCatalogProvider
import com.plox.llmtranslate.data.model.ModelRepository
import com.plox.llmtranslate.data.prefs.ModelPreferences
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
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

    @Inject
    lateinit var catalogProvider: RemoteCatalogProvider

    @Inject
    lateinit var preferences: ModelPreferences

    // Инъекционный app-scope домена (SupervisorJob + IO + обработчик ошибок,
    // ревью раунд 2) — тот же, что у ModelPreferences; ad-hoc CoroutineScope
    // не имел ни SupervisorJob, ни обработки необработанных ошибок.
    @Inject
    lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // Прогрев кэша языка в памяти: MainActivity применит локаль
        // без блокировки главной нити на чтение DataStore.
        preferences.prefetchUiLanguage()
        syncDatabaseWithCatalog()
    }

    /**
     * Синхронизация БД с каталогом при старте: очистка устаревших моделей
     * (записи с рантаймами, которых нет в enum, и встроенные записи, отсутствующие
     * в текущем каталоге — например, после смены формата модели opus-mt на
     * Optimum ONNX; их файлы удаляются с диска) и синхронизация оценок ОЗУ
     * (ram_required_mb/recommended_ram_mb) у существующих записей с актуальным
     * каталогом — иначе ранее установленные модели показывают устаревшие значения
     * в карточке «Загружено в память» и в предупреждении о нехватке ОЗУ.
     *
     * Используются id и поля из объединённого каталога (assets + удалённый):
     * модели удалённого каталога не должны считаться устаревшими.
     */
    private fun syncDatabaseWithCatalog() {
        appScope.launch {
            try {
                cleanupAudioRecords()
                val obsoletePaths = modelRepository.cleanObsoleteRuntimes().toMutableList()
                // last(): flow сначала отдаёт assets, затем (при наличии) объединение
                // с удалённым каталогом — берём итоговый список.
                val catalogModels = catalogProvider.catalog.last()
                // Оценка ОЗУ копируется в запись один раз при старте скачивания —
                // обновляем её у существующих записей актуальными значениями
                // каталога (безопасно даже при неполном каталоге: обновляются
                // только id, присутствующие в списке).
                modelRepository.syncCatalogRamFields(catalogModels)
                val catalogIds = catalogModels.map { it.id }.toSet()
                // Защита от неполного каталога: если удалённая часть не применилась
                // (кэш потерян/сетевой недоступен при уже применённой версии),
                // список содержит только assets — очистка удалила бы модели
                // удалённого каталога. Без удалённой настройки (URL пуст) и при
                // неприменённой версии (appliedVersion == 0) каталожных моделей
                // удалённого каталога в БД быть не может — очистка безопасна.
                val appliedVersion = preferences.lastRemoteCatalogVersion.first()
                val cleaningSafe =
                    catalogProvider.remotePartApplied ||
                        RemoteCatalogProvider.REMOTE_CATALOG_URL.isBlank() ||
                        appliedVersion == 0
                if (cleaningSafe) {
                    obsoletePaths += modelRepository.cleanRemovedCatalogModels(catalogIds)
                } else {
                    Timber.w(
                        "Удалённая часть каталога недоступна (appliedVersion=$appliedVersion) — " +
                            "очистка отсутствующих моделей пропущена"
                    )
                }
                obsoletePaths.distinct().forEach { path ->
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

    /**
     * Очистка остатков WAV-записей распознавания в каталоге «audio»:
     * обычно файл удаляется сразу после распознавания (TranslateViewModel),
     * но остатки от процесса, убитого во время записи/распознавания,
     * удаляются здесь при старте.
     */
    private fun cleanupAudioRecords() {
        val files = getExternalFilesDir("audio")?.listFiles() ?: return
        files.forEach { file ->
            if (file.isFile && !file.delete()) {
                Timber.w("Не удалось удалить остаток записи: ${file.name}")
            }
        }
    }
}
