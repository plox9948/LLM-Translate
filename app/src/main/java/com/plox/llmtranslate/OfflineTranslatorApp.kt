package com.plox.llmtranslate

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import com.plox.llmtranslate.BuildConfig
import com.plox.llmtranslate.data.backup.cleanupOrphanBackupTemp
import com.plox.llmtranslate.data.catalog.RemoteCatalogProvider
import com.plox.llmtranslate.data.storage.ModelStorage
import com.plox.llmtranslate.data.model.FileSnapshot
import com.plox.llmtranslate.data.download.ModelDownloadWorker
import com.plox.llmtranslate.data.model.ModelRepository
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.runtime.AudioRecorder
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
                cleanupBackupTempArtifacts()
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
                repairStaleDownloadingRecords()
                checkReadyRecordsAgainstDisk()
            } catch (e: Exception) {
                Timber.e(e, "Ошибка очистки устаревших моделей")
            }
        }
    }

    /**
     * Лечение «залипшего» status=DOWNLOADING (issue #83, п.2) до сверки READY:
     * запись со статусом DOWNLOADING без живой unique-работы WorkManager
     * (терминальное падение воркера до фиксации статуса, гонка отмены/
     * постановки) навсегда оставалась «скачивающейся» в UI без возможности
     * повтора. Такие записи переводятся в ERROR.
     *
     * Законное возобновление скачивания не ломается: WorkManager переживает
     * перезапуск процесса и сам держит поставленную работу (ENQUEUED/
     * RUNNING/BLOCKED) — для живых id запись остаётся DOWNLOADING. При
     * недоступном WorkManager (исключение/пустой ответ без данных) запись
     * не трогаем — ложный ERROR хуже перепроверки на следующем старте.
     */
    private suspend fun repairStaleDownloadingRecords() {
        try {
            // Регресс-ревью issue #83: суммарный дедлайн на всю сверку
            // вместо блокирующего get по 5 с на каждую запись (N×5 с на
            // старте при подвешенном WorkManager). Записи после исчерпания
            // бюджета считаются живыми — ложный ERROR хуже перепроверки на
            // следующем старте.
            val deadline = System.currentTimeMillis() + WORK_REPAIR_TOTAL_BUDGET_MS
            val repaired = modelRepository.markStaleDownloadingRecords { modelId ->
                hasLiveDownloadWork(modelId, deadline)
            }
            if (repaired.isNotEmpty()) {
                Timber.w("Залипших DOWNLOADING-записей без работы переведено в ERROR: ${repaired.size}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка сверки DOWNLOADING-записей с WorkManager")
        }
    }

    /**
     * Живая скачивающая unique-работа модели (ENQUEUED/RUNNING/BLOCKED).
     * false — работы нет (завершена/отменена/не создана); при сбое запроса
     * WorkManager возвращаем true — запись считается живой и не трогается
     * (см. [repairStaleDownloadingRecords]). [deadlineMs] — суммарный дедлайн
     * всех запросов сверки (регресс-ревью issue #83): при его исчерпании
     * запрос не выполняется и запись также считается живой.
     */
    private fun hasLiveDownloadWork(modelId: String, deadlineMs: Long): Boolean {
        val remainingMs = deadlineMs - System.currentTimeMillis()
        if (remainingMs <= 0L) {
            Timber.w("Бюджет сверки WorkManager исчерпан — DOWNLOADING $modelId не трогаем")
            return true
        }
        return try {
            val states = WorkManager.getInstance(this)
                .getWorkInfosForUniqueWork(ModelDownloadWorker.workName(modelId))
                .get(remainingMs, TimeUnit.MILLISECONDS)
                .map { it.state }
            states.any {
                it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.RUNNING ||
                    it == WorkInfo.State.BLOCKED
            }
        } catch (e: Exception) {
            Timber.w(e, "WorkManager недоступен для $modelId — DOWNLOADING не трогаем")
            true
        }
    }

    /**
     * Сверка READY-записей с фактическим наличием файлов на диске ПОСЛЕ
     * синхронизации с каталогом (ревью, п.1): битые записи (файл удалён/пуст)
     * переводятся в ERROR — пользователь увидит ошибку и сможет переустановить.
     * Выполняется только когда внешний storage доступен: при отмонтованном
     * разделе вердикт выносить нельзя, записи не трогаются.
     */
    private suspend fun checkReadyRecordsAgainstDisk() {
        if (getExternalFilesDir("models") == null) {
            Timber.w("Внешний storage недоступен — сверка READY-записей пропущена")
            return
        }
        try {
            val broken = modelRepository.markBrokenReadyRecords(::fileSnapshotOf)
            if (broken.isNotEmpty()) {
                Timber.w("Помечено ошибкой битых READY-записей: ${broken.size}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка сверки READY-записей с диском")
        }
    }

    /**
     * Очистка osиротевших WAV-записей распознавания: рекордер пишет
     * recording_*.wav в каталог кэша (cacheDir) и удаляет живой файл после
     * распознавания; остатки от процесса, убитого во время записи, чистит
     * общий хелпер [AudioRecorder.cleanupOrphanRecordings] — вызывается и
     * здесь при старте (файлы старше суточного порога), и при старте самой
     * записи. Логика одна, каталога «audio» в приложении нет (ревью) —
     * каталог getExternalFilesDir("audio") relic ранних версий: удаляется
     * целиком однократно при обнаружении (ревью повторно).
     */
    private fun cleanupAudioRecords() {
        AudioRecorder.cleanupOrphanRecordings(cacheDir)
        val legacyAudioDir = getExternalFilesDir("audio") ?: return
        if (legacyAudioDir.exists()) {
            legacyAudioDir.deleteRecursively()
            Timber.i("Удалён relic-каталог записей ранних версий: ${legacyAudioDir.absolutePath}")
        }
    }

    /**
     * Стартовая очистка osиротевших temp-артефактов бэкапа (issue #83, п.6):
     * каталоги backup_import_* (сосед корня моделей) и файлы backup_export_*
     * (внешний кэш/cacheDir), оставленные процессами импорта/экспорта, убитыми
     * до finally-очистки. Порог — суточный (моложе — возможная живая работа);
     * логика в одном месте — [cleanupOrphanBackupTemp], по образцу очистки
     * osиротевших записей AudioRecorder.
     */
    private fun cleanupBackupTempArtifacts() {
        val importTempRoot = ModelStorage.modelsRoot(this).parentFile
        val exportTempRoot = externalCacheDir?.takeIf { it.exists() || it.mkdirs() } ?: cacheDir
        cleanupOrphanBackupTemp(listOf(importTempRoot, exportTempRoot))
    }
}

/**
 * Снимок файловой системы для сверки READY-записи (ревью, п.1):
 * каталог — число непосредственных записей, файл — размер; отсутствующий
 * путь — пустой снимок (запись битая). null — путь существует, но тип
 * недоступен (спец-узел/читаемость), вердикт не выносится.
 * internal — покрыто unit-тестом на временных файлах.
 */
internal fun fileSnapshotOf(path: String): FileSnapshot? {
    val file = File(path)
    return when {
        file.isDirectory -> {
            val children = file.listFiles() ?: return null
            FileSnapshot(isDirectory = true, size = 0, childCount = children.size)
        }
        file.isFile -> FileSnapshot(isDirectory = false, size = file.length(), childCount = 0)
        !file.exists() -> FileSnapshot(isDirectory = false, size = 0, childCount = 0)
        else -> null
    }
}

/**
 * Суммарный бюджет блокирующих запросов WorkManager на одну сверку
 * DOWNLOADING-записей при старте (регресс-ревью issue #83): раньше —
 * по 5 с на каждую запись (N×5 с при подвешенном WorkManager).
 */
private const val WORK_REPAIR_TOTAL_BUDGET_MS = 5_000L
