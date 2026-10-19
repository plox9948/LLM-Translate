package com.plox.llmtranslate.domain

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.plox.llmtranslate.data.backup.ModelImportLock
import com.plox.llmtranslate.data.catalog.ModelCatalogProvider
import com.plox.llmtranslate.data.download.DownloadLayout
import com.plox.llmtranslate.data.download.ModelDownloadWorker
import com.plox.llmtranslate.data.model.ModelRepository
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.data.storage.ModelStorage
import com.plox.llmtranslate.domain.model.ArchiveFormats
import com.plox.llmtranslate.domain.model.DeviceInfo
import com.plox.llmtranslate.domain.runtime.RuntimeRouter
import com.plox.llmtranslate.domain.model.InstallCheck
import com.plox.llmtranslate.domain.model.InstallCheckLogic
import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelFileNaming
import com.plox.llmtranslate.domain.model.ImportedModelDefaults
import com.plox.llmtranslate.domain.model.ModelImportMeta
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import com.plox.llmtranslate.domain.model.TtsModelSelector
import com.plox.llmtranslate.domain.runtime.LoadedModelInfo
import com.plox.llmtranslate.util.sha256Hex
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Реализация [ModelManager]: каталог, установка, выбор, память.
 */
@Singleton
class ModelManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalogProvider: ModelCatalogProvider,
    private val repository: ModelRepository,
    private val preferences: ModelPreferences,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val runtimeRouter: RuntimeRouter,
    private val json: Json,
    private val scope: CoroutineScope
) : ModelManager {

    private companion object {
        /** Пауза debounce для diskUsage (мс) — частые эмиссии Room-flow сворачиваются в один обход дерева. */
        const val DISK_USAGE_DEBOUNCE_MS = 2_000L

        /** Таймаут ожидания WorkManager-фьючерса при проверке живой загрузки. */
        const val WORK_INFO_TIMEOUT_SECONDS = 5L

        /** Интервал опроса статуса воркера после отмены перед файловыми операциями. */
        const val CANCEL_POLL_INTERVAL_MS = 250L

        /** Предельное время ожидания терминального статуса отменённого воркера. */
        const val CANCEL_TERMINAL_TIMEOUT_MS = 10_000L

        /** Имя записи с метаданными в ZIP-экспорте мультифайловой модели. */
        const val META_ENTRY_NAME = "model_meta.json"

        /** Префикс файлов одиночного импорта в корне каталога моделей. */
        const val IMPORT_FILE_PREFIX = "imported_"

        /** Лимит размера записи model_meta.json в импортируемом ZIP. */
        const val META_MAX_BYTES = 1 shl 20
    }

    /** Единый корень моделей ([ModelStorage]) — без дублирующего разрешения. */
    private val modelsDir: File
        get() = ModelStorage.modelsRoot(context)

    /**
     * Issue #75: сериализация импортов — promoteExtractedDir работает с общим
     * staging-каталогом `<id>.promoting`, и два параллельных импорта одной id
     * стартовой уборкой deleteRecursively рвали staging друг друга (в цель
     * попадала смесь файлов двух версий при READY-записи в БД). Взаимной
     * блокировки с мьютексами движков нет: importFromUri вызывается только из
     * UI, под engineMutex импорт не выполняется.
     * Issue #76: удаление (delete) сериализуется тем же мьютексом — гонка
     * import↔delete одной id давала висячий READY на удалённый каталог или
     * осиротевшие файлы без записи.
     * Issue #83, п.1: лок вынесен в разделяемый [ModelImportLock] — импорт
     * бэкапа (ModelBackupManager.importFromUri) иначе гонял подмену файлов той
     * же id с живым WorkManager-воркером и параллельными импортами вне
     * сериализации (порча восстановленной READY-записи).
     */
    private val importMutex = ModelImportLock.mutex

    override val catalog: Flow<List<ModelCard>> = catalogProvider.catalog

    override fun installedModels(category: ModelCategory?): Flow<List<InstalledModel>> =
        if (category == null) {
            repository.observeAll()
        } else {
            repository.observeByCategory(category)
        }

    override fun activeModelId(category: ModelCategory): Flow<String?> =
        preferences.activeModelId(category)

    override fun activeModel(category: ModelCategory): Flow<InstalledModel?> =
        combine(activeModelId(category), repository.observeAll()) { id, models ->
            models.firstOrNull { it.id == id && it.isReady }
        }

    override val sttByLanguage: Flow<Map<String, String>> = preferences.sttByLanguage

    override suspend fun setSttForLanguage(language: String, modelId: String?) =
        withContext(Dispatchers.IO) {
            if (modelId != null) {
                val model = repository.getById(modelId)
                if (model == null || model.category != ModelCategory.STT || !model.isReady) {
                    Timber.w(
                        "Назначение STT языку $language пропущено: модель $modelId недоступна"
                    )
                    return@withContext
                }
            }
            preferences.setSttForLanguage(language, modelId)
        }

    override val ttsByLanguage: Flow<Map<String, String>> = preferences.ttsByLanguage

    override suspend fun setTtsForLanguage(language: String, modelId: String?) =
        withContext(Dispatchers.IO) {
            // Сентинель «system» (issue #53) — явное назначение системного
            // TTS, карточка модели не требуется.
            if (modelId != null && modelId != TtsModelSelector.SYSTEM_SENTINEL) {
                // карточка существует (запись синхронизирована с каталогом),
                // категория TTS, рантайм скачиваемой Piper-модели, готова.
                val model = repository.getById(modelId)
                if (model == null ||
                    model.category != ModelCategory.TTS ||
                    model.runtime != RuntimeType.PIPER_ONNX ||
                    !model.isReady
                ) {
                    Timber.w(
                        "Назначение TTS языку $language пропущено: модель $modelId недоступна"
                    )
                    return@withContext
                }
            }
            preferences.setTtsForLanguage(language, modelId)
        }

    override val loadedModels: Flow<List<LoadedModelInfo>> = runtimeRouter.loadedModels

    // Debounce: каждое эмиссия Room-flow (статусы моделей меняются часто,
    // особенно при скачивании) вызывала полный обход дерева каталога моделей —
    // слишком затратно. Пачка частых обновлений сворачивается в один обход;
    // первая эмиссия задерживается на DISK_USAGE_DEBOUNCE_MS — приемлемо.
    override val diskUsage: Flow<DiskUsage> = repository.observeAll()
        .debounce(DISK_USAGE_DEBOUNCE_MS)
        .map { _ ->
            withContext(Dispatchers.IO) {
                DiskUsage(
                    totalBytes = modelsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                    modelsDir = modelsDir.absolutePath
                )
            }
        }

    override suspend fun setActive(category: ModelCategory, modelId: String): Boolean =
        withContext(Dispatchers.IO) {
            val model = repository.getById(modelId)
            if (model == null || model.category != category) {
                Timber.w("Модель $modelId не найдена или не из категории $category")
                return@withContext false
            }
            if (!model.isReady) {
                Timber.w("Модель $modelId не готова к активации")
                return@withContext false
            }
            preferences.setActiveModelId(category, modelId)
            true
        }

    override fun checkInstall(card: ModelCard, device: DeviceInfo): InstallCheck =
        InstallCheckLogic.evaluate(card, device)

    override fun startDownload(card: ModelCard) {
        val model = InstalledModel(
            id = card.id,
            category = card.category,
            displayName = card.displayName,
            description = card.description,
            runtime = card.runtime,
            sizeBytes = card.sizeBytes,
            ramRequiredMb = card.ramRequiredMb,
            languages = card.languages,
            quality = card.quality,
            speed = card.speed,
            url = card.url,
            urls = card.urls,
            sha256 = card.sha256,
            sha256s = card.sha256s,
            license = card.license,
            minAndroidSdk = card.minAndroidSdk,
            recommendedRamMb = card.recommendedRamMb,
            status = ModelStatus.DOWNLOADING,
            progress = 0,
            filePath = null,
            bytesDone = 0,
            isBuiltin = true
        )
        scope.launch {
            // Ревью, п.4: сначала постановка в WorkManager, затем upsert
            // DOWNLOADING — при отказе schedule запись помечается ERROR
            // (висячего «скачивания» без воркера не остаётся). Воркер
            // получает все входные данные из inputData, а не из БД,
            // поэтому upsert после schedule не ломает скачивание.
            //
            // ExistingWorkPolicy.KEEP — сознательная политика: повторный
            // тап «Скачать» при живой загрузке НЕ пересоздаёт unique work,
            // живой воркер сохраняет исходные inputData (URL, SHA-256).
            // При этом upsert(DOWNLOADING) перезаписал бы строку нулевым
            // прогрессом и визуально сбросил прогресс живой загрузки,
            // поэтому при живом unique work upsert не выполняется (строка
            // уже DOWNLOADING); если же строку успели удалить — upsert всё
            // равно создаёт её.
            //
            // Ревью повт. аудита, п.7: гонка «отмена → скачать» в окне
            // работы умирающего unique work — существующая работа уже
            // CANCELLED/FAILED/BLOCKED (cancelDownload отменил её и
            // пересохранить строку), а KEEP дропает новый enqueue в её
            // пользу → залипший DOWNLOADING без воркера. Такая работа не
            // живая, но и не освобождает unique-имя — переставляем её с
            // REPLACE.
            val states = uniqueWorkStates(model.id)
            val liveWork = states.any {
                it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.RUNNING
            }
            val staleWork = states.any {
                it == WorkInfo.State.CANCELLED || it == WorkInfo.State.FAILED ||
                    it == WorkInfo.State.BLOCKED
            }
            try {
                ModelDownloadWorker.schedule(
                    context,
                    model,
                    if (staleWork) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
                )
                if (!staleWork) {
                    // Отмена могла случиться МЕЖДУ чтением состояний и
                    // enqueue: KEEP оставил умирающую работу, наш запрос
                    // дропнут. Одноразовая сверка после постановки: если
                    // живой работы так и нет, а терминальная/умирающая
                    // есть — перестановка с REPLACE.
                    val after = uniqueWorkStates(model.id)
                    if (after.isNotEmpty() &&
                        after.none {
                            it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.RUNNING
                        }
                    ) {
                        Timber.w(
                            "Загрузка ${model.id}: enqueue под KEEP не дал живой работы " +
                                "(${after.joinToString()}) — перестановка с REPLACE"
                        )
                        ModelDownloadWorker.schedule(context, model, ExistingWorkPolicy.REPLACE)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Не удалось поставить на скачивание модель ${card.id}")
                // Ревью: воркер не встал в очередь — возможная строка
                // DOWNLOADING от прошлой попытки осталась бы «скачивающейся»
                // вечно (pending-гард UI, карточка без кнопки). Помечаем её
                // ERROR: карточка покажет «Повторить». Для новой модели без
                // строки в БД обновление — no-op (0 строк).
                try {
                    repository.updateDownloadStatus(model.id, ModelStatus.ERROR)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Не удалось перевести модель ${card.id} в ERROR после отказа schedule")
                }
                return@launch
            }
            if (!liveWork || repository.getById(model.id) == null) {
                // Ревью: готовую (READY) запись не перетираем DOWNLOADING без
                // живой работы — upsert обнулил бы прогресс/статус установленной
                // модели до пересоздания воркера (переход READY→DOWNLOADING
                // лишает finalize-гейт ориентира, а UI показывает «скачивание»).
                if (repository.getById(model.id)?.status == ModelStatus.READY) {
                    Timber.d(
                        "Модель ${model.id} уже установлена (READY) — статус скачивания не перезаписывается"
                    )
                } else {
                    try {
                        repository.upsert(model)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Контрольное ревью, п.3: воркер уже в очереди — статус
                        // НЕ меняем (READY→ERROR при живой загрузке потерял бы
                        // установленные файлы на finalize-гейте). Строка
                        // останется в прежнем статусе, докачка перезапустится.
                        Timber.e(e, "Не удалось обновить запись модели ${card.id} после schedule")
                    }
                }
            }
        }
    }

    /**
     * Сырая проверка живости unique-работы [workName] в WorkManager
     * (ENQUEUED/RUNNING/BLOCKED). Блокирующий [.get] будущего допустим:
     * вызывается только с [Dispatchers.IO], фьючерс WorkManager читает
     * локальную БД. При недоступности WorkManager бросает — вызывающий
     * сам решает fallback-семантику и логирует её.
     */
    private fun isWorkLive(workName: String): Boolean =        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(workName)
            .get(WORK_INFO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .any { info ->
                info.state == WorkInfo.State.ENQUEUED ||
                    info.state == WorkInfo.State.RUNNING ||
                    info.state == WorkInfo.State.BLOCKED
            }

    /**
     * Состояния unique-работы загрузки модели (пусто при недоступном
     * WorkManager) — см. [startDownload], ревью повт. аудита п.7.
     */
    private suspend fun uniqueWorkStates(modelId: String): List<WorkInfo.State> = try {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ModelDownloadWorker.workName(modelId))
            .get(WORK_INFO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .map { info -> info.state }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Не удалось получить состояния unique-загрузки $modelId — считаю, что работы нет")
        emptyList()
    }

    /**
     * Есть ли живая unique-загрузка модели в WorkManager (ENQUEUED/RUNNING/
     * BLOCKED) — см. [startDownload]. Неинициализированный/недоступный
     * WorkManager считается «живой работы нет» (обычный путь schedule).
     */
    private suspend fun hasLiveDownloadWork(modelId: String): Boolean = try {
        isWorkLive(ModelDownloadWorker.workName(modelId))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(
            e,
            "Не удалось проверить живую загрузку $modelId — считаю, что живой работы нет"
        )
        false
    }

    override fun cancelDownload(modelId: String) {
        ModelDownloadWorker.cancel(context, modelId)
        scope.launch {
            try {
                // Ревью: тело отмены под importMutex (тот же лок, что у delete
                // и импортов) — иначе cancelDownload гоняет deleteById и файловую
                // очистку с параллельным импортом/удалением той же id.
                importMutex.withLock {
                    val model = repository.getById(modelId) ?: return@launch
                    if (model.status != ModelStatus.READY) {
                        // Незавершённую запись удаляем, чтобы UI снова показал «Скачать».
                        // При явной отмене пользователем удаляем и осиротевшие
                        // .part-файлы: частичная загрузка этой модели не нужна,
                        // при повторном скачивании начнётся заново.
                        deleteOrphanParts(model)
                        repository.deleteById(modelId)
                        // Ревью: по образцу delete() — назначения «язык → модель»
                        // (STT/TTS) с удалённым id снимаются, иначе отменённая
                        // загрузка оставляет в настройках висячий id.
                        if (model.category == ModelCategory.STT || model.category == ModelCategory.TTS) {
                            preferences.removeModelFromLanguageAssignments(modelId)
                        }
                        // Ревью: по образцу delete() — если удалённая запись была
                        // активной для категории (осколок настройки), переключаемся
                        // на первую доступную, иначе активный id повиснет на неё.
                        if (preferences.getActiveModelId(model.category) == modelId) {
                            val fallback = repository.getFirstReady(model.category)
                            preferences.setActiveModelId(model.category, fallback?.id)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Не удалось отменить скачивание модели $modelId")
            }
        }
    }

    /**
     * Удалить осиротевшие файлы незавершённой установки модели (issue #2, #40).
     * Вызывается только после [ModelDownloadWorker.cancel] (явная отмена в UI
     * или удаление записи), поэтому живой WorkManager-загрузки по этому id нет
     * и все файлы по URL записи — остатки её же прерванной установки: и
     * недокачанные `.part` с метаданными докачки (конвенция BaseDownloader:
     * рядом с целевым файлом `<имя>.part` и `<имя>.part.meta`), и уже
     * переименованные целевые файлы, и остаток архива в корне каталога
     * рантайма (ArchiveInstaller распаковывает в каталог модели). Пока
     * загрузка жива (сетевые повторы `Result.retry()`), метод не вызывается —
     * докачка по `.part`-файлам сохраняется.
     *
     * Размещение файлов задаёт общий с инсталляторами [DownloadLayout]
     * (см. [ModelDownloadWorker]): мультифайловые пачки, одиночные файлы
     * sherpa и распакованное содержимое архивов — в каталог модели
     * `models/<RUNTIME>/<id>`; каталог принадлежит только этой записи
     * (ключ — id), поэтому сносится целиком — иначе переименованные
     * целевые файлы прерванной мультифайловой установки остаются навсегда.
     * Одиночные ONNX-файлы и архивы — в корень каталога рантайма. Модель не
     * READY (проверяется вызывающим кодом), поэтому файлы по её URL относятся
     * только к её незавершённой загрузке — файлы READY-моделей не затрагиваются.
     * Узкая гонка (после cancel воркер дописал/переименовал файлы уже после
     * этой очистки) закрывается повторной очисткой на стороне воркера
     * (см. `ModelDownloadWorker.cleanupCancelledModel`).
     */
    private fun deleteOrphanParts(model: InstalledModel) {
        DownloadLayout.deleteOrphanedFiles(
            runtimeDir = File(modelsDir, model.runtime.name),
            runtime = model.runtime,
            modelId = model.id,
            urls = model.downloadUrls()
        )
    }

    /**
     * Отменить живую скачивающую загрузку модели перед импортом под тем же id:
     * живой WorkManager-воркер после upsert продолжил бы дописывать `.part` и
     * переименовывать целевые файлы раскладки этой id поверх импортированной
     * модели (гейт `finalizeDownload` по status='DOWNLOADING' защищает только
     * запись БД, не файлы). Файловая очистка — только частей докачки
     * [.part/.part.meta]: целевые файлы и каталог модели не трогаем, при
     * ZIP-импорте в них уже лежит промоутенный пучок. Логика раскладки общая
     * с очисткой отменённой загрузки воркера ([DownloadLayout]).
     *
     * После [ModelDownloadWorker.cancel] до файловых операций ждём
     * терминального статуса воркера ([awaitWorkCancellation]): отменённый,
     * но ещё не прерванный воркер дописывал `.part`/переименовывал цели
     * уже после очистки частей импортом (границы `ensureActive` в
     * gunzip/tar — coarse, блоки по 64 КиБ).
     */
    private suspend fun cancelSupersededDownload(modelId: String, previous: InstalledModel?) {
        if (!hasLiveDownloadWork(modelId)) return
        ModelDownloadWorker.cancel(context, modelId)
        awaitWorkCancellation(modelId)
        val superseded = previous?.takeIf { it.status != ModelStatus.READY } ?: return
        DownloadLayout.deletePendingParts(
            runtimeDir = File(modelsDir, superseded.runtime.name),
            runtime = superseded.runtime,
            modelId = modelId,
            urls = superseded.downloadUrls()
        )
    }

    /**
     * Дождаться терминального статуса (SUCCEEDED/FAILED/CANCELED либо
     * исчезновения unique-работы) после [ModelDownloadWorker.cancel]. Опрос
     * каждые [CANCEL_POLL_INTERVAL_MS] с общим пределом
     * [CANCEL_TERMINAL_TIMEOUT_MS] на [Dispatchers.IO]. Превышение предела —
     * только [Timber.w]: импорт продолжается, остаточная гонка «воркер ещё
     * не прерван» ограничена грубыми границами отмены в распаковщиках и
     * закрывается самовосстановлением сверки READY-записей с диском
     * (checkReadyRecordsAgainstDisk при старте приложения).
     */
    private suspend fun awaitWorkCancellation(modelId: String) = withContext(Dispatchers.IO) {
        val workName = ModelDownloadWorker.workName(modelId)
        val deadline = System.currentTimeMillis() + CANCEL_TERMINAL_TIMEOUT_MS
        while (true) {
            val live = try {
                isWorkLive(workName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Статус отменённой загрузки $modelId недоступен — считаю воркер завершённым")
                false
            }
            if (!live) return@withContext
            if (System.currentTimeMillis() >= deadline) {
                Timber.w(
                    "Воркер загрузки $modelId не достиг терминального статуса " +
                        "через $CANCEL_TERMINAL_TIMEOUT_MS мс после отмены"
                )
                return@withContext
            }
            delay(CANCEL_POLL_INTERVAL_MS)
        }
    }

    override suspend fun importFromUri(uri: Uri): String? =
        withContext(Dispatchers.IO) {
            try {
                val fileName = queryFileName(uri) ?: return@withContext null

                // ZIP (в т.ч. экспорт мультифайловой модели этой же программой):
                // распаковываем в каталог модели, id и структуру берём из
                // вложенной метадати model_meta.json (см. exportToUri).
                if (looksLikeZip(uri)) {
                    return@withContext importZip(uri, fileName)
                }

                // Issue #75: исполняемое тело одиночного импорта под importMutex —
                // общая точка входа с импортом ZIP (общий staging по id, upsert
                // и чистка прежних файлов одной модели).
                importMutex.withLock {
                    val targetFile = copyFromUri(uri) ?: return@withContext null

                    val runtime = guessRuntime(fileName)
                    if (runtime == null) {
                        Timber.w("Неизвестный формат файла модели $fileName — импорт отклонён")
                        targetFile.delete()
                        return@withContext null
                    }

                    val modelId = sanitizeModelIdFromName(
                        fileName.removeSuffix(".gguf").removeSuffix(".onnx")
                    ).takeIf { it.isNotBlank() }
                    // Ревью: как в ZIP-ветке — пустое имя (только расширения/
                    // спецсимволы) даёт пустой id; отказ с логом, а не запись
                    // с пустым id, по которой нельзя ни удалить, ни назначить.
                    if (modelId == null) {
                        Timber.w("Имя файла $fileName не даёт корректного id модели — импорт отклонён")
                        targetFile.delete()
                        return@withContext null
                    }
                    // Старая запись под этим id (до upsert) — нужна для удаления
                    // осиротевшего файла предыдущего импорта (issue #40).
                    val previousRecord = repository.getById(modelId)
                    // Импорт по id живой загрузки: воркер отменяем ДО upsert,
                    // иначе его финализация/переименования накладываются на
                    // импортированные файлы (гейт finalizeDownload по
                    // status='DOWNLOADING' защищает только запись БД).
                    cancelSupersededDownload(modelId, previousRecord)
                    val model = guessedImportedModel(
                        modelId = modelId,
                        sourceName = fileName,
                        runtime = runtime,
                        size = targetFile.length(),
                        filePath = targetFile.absolutePath
                    )
                    repository.upsert(model)
                    // Issue #68: повторный импорт подменяет бинарник той же id —
                    // движки обязаны забыть старый пучок (тот же eviction, что в
                    // delete()): иначе LRU-кэш по id молча продолжает инференс
                    // старым нативным handle, а удаление осиротевшего файла ниже
                    // снимает живой mmap. Порядок: сначала releaseModel (движок
                    // под своим мьютексом дожидается окончания активного инференса
                    // и отпускает старый файл), затем удаление файла.
                    previousRecord?.let { runtimeRouter.releaseModel(it) }
                    // Повторный импорт той же модели: copyFromUri каждый раз пишет
                    // новый imported_* файл, старый осиротел (issue #40); если же
                    // прежняя запись была каталожной моделью — осиротел её пучок
                    // рантайма (issue #69).
                    deleteSupersededImportFiles(previousRecord, targetFile)
                    Timber.d("Импортирована модель $modelId из $uri")
                    modelId
                }
            } catch (e: CancellationException) {
                // Ревью: штатная отмена корутины (scope) не «ошибка импорта» —
                // не глотаем, иначе importFromUri вернёт null вместо стека отмены.
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Ошибка импорта модели из $uri")
                null
            }
        }

    override suspend fun exportToUri(modelId: String, targetUri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            val model = repository.getById(modelId) ?: return@withContext false
            val source = model.filePath?.let(::File) ?: return@withContext false
            if (!source.exists()) {
                Timber.w("Файл модели $modelId не найден: ${source.path}")
                return@withContext false
            }
            try {
                val output = context.contentResolver.openOutputStream(targetUri)
                    ?: return@withContext false
                output.use { out ->
                    if (source.isDirectory) {
                        // Каталог модели упаковываем в zip потоково.
                        // Первой записью — метаданные: повторный импорт этого
                        // zip регистрирует модель под её каталожным id.
                        ZipOutputStream(out.buffered()).use { zip ->
                            zip.putNextEntry(ZipEntry(META_ENTRY_NAME))
                            zip.write(
                                json.encodeToString(
                                    ModelImportMeta.serializer(), importMetaOf(model)
                                ).toByteArray()
                            )
                            zip.closeEntry()
                            source.walkTopDown().filter { it.isFile }.forEach { f ->
                                zip.putNextEntry(ZipEntry(f.relativeTo(source).path))
                                f.inputStream().buffered().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    } else {
                        source.inputStream().buffered().use { it.copyTo(out) }
                    }
                }
                Timber.d("Модель $modelId экспортирована")
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Ошибка экспорта модели $modelId")
                false
            }
        }

    override fun delete(modelId: String) {
        // Отменяем возможную загрузку, чтобы воркер не воскресил удалённую запись.
        ModelDownloadWorker.cancel(context, modelId)
        scope.launch {
            try {
                // Issue #76: тело удаления под importMutex (тот же мьютекс, что у
                // импортов) — иначе delete и импорт одной id гонят файловые операции
                // с upsert/deleteById: висячий READY на удалённый каталог либо
                // осиротевшие imported_* файлы без записи. Порядок локов тот же
                // (import/delete → engineMutex via releaseModel), реверса нет.
                importMutex.withLock {
                    val model = repository.getById(modelId) ?: return@launch
                    val path = model.filePath
                    // Кэш движков по id модели (issue #40): без eviction
                    // удалённая модель остаётся «загруженной» в loadedModels,
                    // а повторная загрузка того же id берёт из LRU-кэша старый
                    // нативный handle по уже удалённым файлам. Движок под своим
                    // мьютексом ждёт окончания активного инференса —
                    // use-after-free исключён.
                    // Порядок шагов (ревью #79, согласован с promoteExtractedDir):
                    // releaseModel → deleteById → удаление файлов. Файлы нельзя
                    // стирать, пока slimt mmap / ORT-сессии держат их занятыми:
                    // сначала освобождение движка, затем запись БД, и только
                    // потом диск.
                    runtimeRouter.releaseModel(model)
                    repository.deleteById(modelId)
                    // Ревью: назначения «язык → модель» (STT/TTS) с удалённым id
                    // снимаются — иначе настройка ссылается на несуществующую
                    // модель до первого переназначения.
                    if (model.category == ModelCategory.STT || model.category == ModelCategory.TTS) {
                        preferences.removeModelFromLanguageAssignments(modelId)
                    }
                    if (!path.isNullOrBlank()) {
                        val file = File(path)
                        // Ревью: отказ удаления файлов логируется (по образцу
                        // остальных мест) — иначе осиротевшие байты тихие.
                        val removed =
                            if (file.isDirectory) file.deleteRecursively() else file.delete()
                        if (!removed && file.exists()) {
                            Timber.w("Не удалось удалить файлы модели $modelId: ${file.path}")
                        }
                    } else {
                        // У записи нет пути при прерванной установке — чистим
                        // известные места раскладки DownloadLayout (issue #40:
                        // иначе переименованные целевые файлы установки, снятой
                        // до finalizeDownload, остаются на диске навсегда).
                        deleteOrphanParts(model)
                    }
                    // Если удалена активная модель — переключаемся на первую доступную.
                    if (preferences.getActiveModelId(model.category) == modelId) {
                        val fallback = repository.getFirstReady(model.category)
                        if (fallback != null) {
                            Timber.d("Переключаемся на ${fallback.id} (категория ${model.category})")
                        } else {
                            Timber.d("Нет доступных моделей категории ${model.category}")
                        }
                        preferences.setActiveModelId(model.category, fallback?.id)
                    }
                    Timber.d("Модель $modelId удалена")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Не удалось удалить модель $modelId")
            }
        }
    }

    override fun releaseAllRuntimes() {
        // release() внутри каждого движка ждёт окончания активного инференса
        // (десятки секунд), поэтому уходим с главного потока в app-scope.
        scope.launch(Dispatchers.IO) {
            try {
                runtimeRouter.releaseAll()
                Timber.d("Рантаймы разгружены (ON_STOP)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Ошибка при разгрузке рантаймов")
            }
        }
    }

    private suspend fun copyFromUri(uri: Uri): File? {
        val targetFile = File(modelsDir, "$IMPORT_FILE_PREFIX${System.currentTimeMillis()}")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null
        } catch (e: Exception) {
            // Обрыв потокопередачи SAF: частичный imported_* не должен
            // оставаться на диске без регистрации в БД.
            if (!targetFile.delete()) {
                Timber.w("Не удалось удалить частичный файл импорта: ${targetFile.name}")
            }
            throw e
        }
        return targetFile
    }

    /**
     * Удалить файлы старой записи, вытесненные повторным импортом (одиночным
     * файлом или ZIP — issue #72) той же модели. Одиночный импорт каждый раз
     * пишет новый путь `imported_<ts>`, и без
     * очистки старые файлы остаются мусором без записи в БД. Два случая:
     *  - прежний файл зоны импорта (`imported_*` в корне каталога моделей) —
     *    удаляется файл (issue #40);
     *  - прежняя запись была каталожной моделью: её filePath — каталог пучка
     *    рантайма внутри `models/<RUNTIME>/` — удаляется каталог целиком
     *    (issue #69: иначе после замещения одиночным файлом пучок рантайма
     *    остаётся осиротевшим на диске).
     * Удаляем только пути внутри каталога моделей, отличные от нового файла
     * (ZIP-промоут той же id в тот же путь — иначе удалили бы новые файлы),
     * и только после [runtimeRouter.releaseModel] вызывающего кода: движок
     * сначала отпускает старый файл.
     */
    private fun deleteSupersededImportFiles(previous: InstalledModel?, newFile: File) {
        val oldPath = previous?.filePath ?: return
        if (oldPath == newFile.absolutePath) return
        val oldFile = File(oldPath)
        val modelsRoot = modelsDir.absolutePath
        if (!oldFile.absolutePath.startsWith(modelsRoot + File.separator)) return
        if (oldFile.parentFile?.absolutePath == modelsRoot &&
            oldFile.name.startsWith(IMPORT_FILE_PREFIX) &&
            oldFile.isFile
        ) {
            if (!oldFile.delete()) {
                Timber.w("Не удалось удалить осиротевший файл импорта: $oldPath")
            }
            return
        }
        // Каталог пучка рантайма каталожной модели (models/<RUNTIME>/...);
        // одиночные imported_* файлы обработаны выше.
        if (oldFile.isDirectory) {
            if (!oldFile.deleteRecursively()) {
                Timber.w("Не удалось удалить осиротевший пучок модели: $oldPath")
            }
        }
    }

    /** Проверка сигнатуры ZIP (PK\x03\x04) по первым байтам документа SAF. */
    private fun looksLikeZip(uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val magic = ByteArray(4)
            var read = 0
            while (read < 4) {
                val n = input.read(magic, read, 4 - read)
                if (n < 0) break
                read += n
            }
            read == 4 &&
                magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
                magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()
        } ?: false
    } catch (e: Exception) {
        Timber.w(e, "Не удалось прочитать сигнатуру $uri")
        false
    }

    /**
     * Импорт ZIP-экспорта: распаковка в каталог модели
     * `models/<RUNTIME>/<id>` и регистрация записи. Если внутри есть
     * запись [META_ENTRY_NAME] (ZIP сделан экспортом этой программы),
     * id, категория и поля модели берутся из неё — такая модель
     * совпадает с карточкой каталога. Иначе параметры угадываются по имени.
     */
    private suspend fun importZip(uri: Uri, sourceName: String): String? {
        // Issue #75: всё тело ZIP-импорта под importMutex — общий с одиночным
        // импортом (staging `<id>.promoting`, upsert и чистка прежних файлов
        // одной модели; параллельные импорты одной id иначе рвут staging).
        importMutex.withLock {
            val tempDir = File(context.cacheDir, "model_import_${System.currentTimeMillis()}")
            try {
                tempDir.mkdirs()
                var meta: ModelImportMeta? = null
                var fileCount = 0
                context.contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input.buffered()).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            if (entry.isDirectory) {
                                zip.closeEntry()
                                entry = zip.nextEntry
                                continue
                            }
                            if (name == META_ENTRY_NAME) {
                                // Ограничение размера метаданных: ZIP с патологически
                                // большой model_meta.json не должен выедать память
                                // readBytes() — читаем с лимитом и отклоняем импорт.
                                val content = readEntryBounded(zip, META_MAX_BYTES, sourceName)
                                meta = try {
                                    json.decodeFromString(ModelImportMeta.serializer(), content)
                                } catch (e: Exception) {
                                    Timber.w(e, "Некорректная метаданная в ZIP $sourceName")
                                    null
                                }
                            } else {
                                val outFile = File(tempDir, name)
                                // Защита от zip-slip: файл не должен выходить за пределы временного каталога.
                                val canonicalTemp = tempDir.canonicalPath
                                if (!outFile.canonicalPath.startsWith(canonicalTemp + File.separator)) {
                                    Timber.w("ZIP-запись вне каталога, пропущена: $name")
                                } else {
                                    outFile.parentFile?.mkdirs()
                                    outFile.outputStream().use { zip.copyTo(it) }
                                    fileCount++
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }
                if (fileCount == 0) {
                    Timber.w("ZIP не содержит файлов модели: $sourceName")
                    return null
                }

                // Issue #69: eviction кэша движков выполняется ВНУТРИ
                // promoteExtractedDir до перезаписи файлов каталога (см. его KDoc),
                // здесь остаётся только регистрация новой записи.

                // Ревью: живая загрузка той же id отменяется ДО modelFromMeta/
                // modelFromZipName (как в одиночном импорте), а не после промоута:
                // финализация/переименования отменённого воркера и его
                // cleanupCancelledModel обязаны случиться раньше промоута, иначе
                // снесут только что промоутенный пучок (гейт finalizeDownload
                // по status='DOWNLOADING' защищает только запись БД, не файлы).
                // id известна до промоута: из метаданных или из имени ZIP.
                val supersededId = meta?.id ?: modelIdFromZipName(sourceName)
                if (supersededId != null) {
                    cancelSupersededDownload(supersededId, repository.getById(supersededId))
                }

                val model = meta?.let { modelFromMeta(it, tempDir) }
                    ?: modelFromZipName(sourceName, tempDir)
                    ?: return null

                // Issue #72: прежняя запись той же id читается ДО upsert —
                // ZIP-импорт, замещающий одиночный импорт, осиротевший прежний
                // imported_* файл (или пучок по другому пути). Eviction движка
                // уже выполнен внутри promoteExtractedDir (releaseModel до
                // copyRecursively), здесь остаётся чистка прежнего пути; guard
                // oldPath == newPath внутри deleteSupersededImportFiles не даст
                // удалить только что промоутенный целевой каталог.
                val previousRecord = repository.getById(model.id)
                repository.upsert(model)
                model.filePath?.let { deleteSupersededImportFiles(previousRecord, File(it)) }
                Timber.d("Импортирована модель ${model.id} (ZIP $sourceName)")
                return model.id
            } catch (e: CancellationException) {
                // Ревью: штатная отмена корутины импорта не «ошибка импорта».
                throw e
            } catch (e: ImportRejectedException) {
                Timber.w("Импорт ZIP $sourceName отклонён: ${e.message}")
                return null
            } catch (e: Exception) {
                Timber.e(e, "Ошибка импорта ZIP-модели из $uri")
                return null
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    /**
     * Каталог модели на диске для мультифайловой установки:
     * `models/<RUNTIME>/<id>` — та же раскладка, что у загрузчика.
     */
    private fun modelTargetDir(runtime: RuntimeType, modelId: String): File =
        File(File(modelsDir, runtime.name), modelId.replace('/', '_'))

    /**
     * Перенести распакованный временный каталог в каталог модели
     * ([modelTargetDir]) и посчитать суммарный размер его файлов. Общий шаг
     * импорта ZIP из метаданных (modelFromMeta) и из имени (modelFromZipName).
     * @return пара (каталог модели, размер в байтах); null при любом сбое —
     *   прежний пучок нетронут, staging удалён, извлечённый temp чистится
     *   в finally [importZip].
     */
    private suspend fun promoteExtractedDir(
        extracted: File,
        runtime: RuntimeType,
        modelId: String
    ): Pair<File, Long>? {
        val targetDir = modelTargetDir(runtime, modelId)
        // Issue #69: eviction кэша движков по образцу delete() — ДО подмены
        // каталога. Живой mmap активного инференса в кэше движка читает файлы
        // старого пучка (обрезанные данные → мусор или SIGBUS). releaseModel под
        // своим мьютексом дожидается окончания активного инференса и отпускает
        // старый каталог; следующий ensureLoaded построит движок по новым файлам.
        // Старая запись читается из БД до upsert, поэтому доступна здесь.
        repository.getById(modelId)?.let { runtimeRouter.releaseModel(it) }

        // Issue #74: staging + rename-подмена вместо wipe-then-copy. Раньше
        // targetDir.deleteRecursively() уничтожал рабочий пучок ДО копирования,
        // и отказ copyRecursively (например нехватка места на внешнем разделе)
        // оставлял БД READY на несуществующие файлы — падение на ensureLoaded;
        // возврат deleteRecursively игнорировался, частичное удаление превращало
        // копирование в merge-остатки (возврат #73). Копируем в соседний staging
        // того же тома и меняем каталоги renameTo: при любом отказе прежний
        // пучок остаётся на месте, замена цели — целиком, без слияния (#73).
        val runtimeDir = targetDir.parentFile ?: modelsDir
        val stagingDir = File(runtimeDir, "${targetDir.name}.promoting")
        val swappedOutDir = File(runtimeDir, "${targetDir.name}.old")
        stagingDir.deleteRecursively()   // мусор от прошлой неудачной промоции
        swappedOutDir.deleteRecursively()
        if (!copyExtractedToStaging(extracted, stagingDir)) {
            Timber.w("Не удалось скопировать модель $modelId в staging ${stagingDir.path}")
            stagingDir.deleteRecursively()
            return null
        }
        val hadTarget = targetDir.exists()
        if (hadTarget && !targetDir.renameTo(swappedOutDir)) {
            Timber.w("Не удалось отодвинуть старый каталог ${targetDir.path} — промоция отменена")
            stagingDir.deleteRecursively()
            return null
        }
        if (!stagingDir.renameTo(targetDir)) {
            Timber.w("Не удалось подменить каталог ${targetDir.path} — промоция отменена")
            if (hadTarget && !swappedOutDir.renameTo(targetDir)) {
                Timber.w("Не удалось вернуть старый каталог ${swappedOutDir.path} на место")
            }
            stagingDir.deleteRecursively()
            return null
        }
        // Вытесненный каталог чистим best-effort: файлы под новым именем уже
        // на месте, остатки старого тома лишь тратят место.
        if (swappedOutDir.exists() && !swappedOutDir.deleteRecursively()) {
            Timber.w("Не удалось удалить вытесненный каталог ${swappedOutDir.path}")
        }
        // Размер считаем один раз: каталог между этими полями не меняется
        // (sizeBytes и bytesDone всегда равны сумме файлов каталога).
        val size = targetDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return targetDir to size
    }

    /**
     * Seam (issue #74): перенос extracted → staging вынесён в internal-метод,
     * чтобы unit-тест мог инъектировать отказ копирования (нехватку места на FS
     * смоделировать сложно) и проверить сохранность прежнего пучка при провале
     * промоции. suspend (issue #76): тестовый seam может вызвать delay —
     * настоящее переключение корутин для проверки сериализации импортов под
     * importMutex (Thread.sleep блокирует поток и не даёт детеминированной
     * interleaving-картины).
     *
     * Ревью (п.6): пик места при ZIP-импорте — cacheDir(извлечённое) + staging +
     * оригинал (2–3x). Первый шанс — renameTo: при одном томе это перемещение
     * записи ФС без выделения второго экземпляра (плюс извлечённые байты уже на
     * этом томе). Не вышло (cross-volume / недоступен перенос) — рекурсивное
     * копирование, НО с предварительной оценкой StatFs свободных байт по
     * суммарному размеру распакованного: не хватит — явный отказ импорта
     * ([ImportRejectedException]), а не частичная запись в staging.
     */
    internal suspend fun copyExtractedToStaging(extracted: File, stagingDir: File): Boolean {
        if (extracted.renameTo(stagingDir)) return true
        val incomingBytes = extracted.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        if (!hasEnoughFreeSpace(stagingDir.parentFile ?: modelsDir, incomingBytes)) {
            throw ImportRejectedException(
                "недостаточно свободного места для копирования импорта " +
                    "(требуется ~$incomingBytes байт)"
            )
        }
        return extracted.copyRecursively(stagingDir, overwrite = true)
    }

    /**
     * Достаточно ли свободного места на томе [volume] под [incomingBytes].
     * Неготовность оценки (StatFs исключение — в т.ч. unit-test JVM без
     * android.os) не блокирует импорт (возвращает true): на реальном
     * устройстве StatFs работает и отказ наступает по фактической нехватке.
     */
    private fun hasEnoughFreeSpace(volume: File, incomingBytes: Long): Boolean = try {
        val stat = StatFs(volume.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong >= incomingBytes
    } catch (e: Exception) {
        Timber.w(e, "Не удалось оценить свободное место перед копированием импорта")
        true
    }

    /**
     * Прочитать запись ZIP с лимитом размера; превышение — явный отказ импорта
     * ([ImportRejectedException]) вместо неограниченного readBytes().
     */
    private fun readEntryBounded(zip: ZipInputStream, limitBytes: Int, sourceName: String): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (total <= limitBytes) {
            val n = zip.read(buffer, 0, minOf(buffer.size, limitBytes + 1 - total))
            if (n <= 0) break
            out.write(buffer, 0, n)
            total += n
        }
        if (total > limitBytes) {
            throw ImportRejectedException(
                "метаданные слишком большие (> $limitBytes байт в ZIP $sourceName)"
            )
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** Id модели из имени файла: всё вне [A-Za-z0-9_.-] → '_' (как в [DownloadLayout]). */
    private fun sanitizeModelIdFromName(raw: String): String =
        raw.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("")

    /**
     * Сверить фактические SHA-256 распакованных файлов импорта с ожидаемыми
     * хэшами (из model_meta.json, иначе — из существующей каталожной записи
     * той же id, см. вызов в [modelFromMeta]). Ожидаемые хэши привязаны к
     * URL каталога: file[i] ищется по всем кандидатам именования
     * [DownloadLayout] (новая схема `modelId__basename`, санитизированный
     * basename и legacy-сырой basename — установка, из которой сделан экспорт,
     * могла быть названа по любой из схем); архивные URL пропускаются (их хэш
     * относится к архиву, а не к распакованному содержимому), как и сжатые
     * артефакты `.gz` (slimt-пачки: инсталлятор gunzip-ит, в пучке
     * распакованные имена, ожидаемый хеш — сжатых байт, см.
     * [ArchiveFormats.isCompressedArtifact]). Несовпадение или
     * отсутствующий файл — [ImportRejectedException] до промоута: прежний
     * пучок нетронут, temp чистится в finally [importZip].
     */
    private suspend fun verifyImportChecksums(
        modelId: String,
        urls: List<String>,
        expected: List<String?>,
        extracted: File
    ) {
        if (urls.isEmpty() || expected.none { !it.isNullOrBlank() }) return
        urls.forEachIndexed { index, url ->
            // Ревью: sha256 больших файлов считается долго — корутина импорта
            // должна отменяться между файлами, а не только на границах копирования.
            currentCoroutineContext().ensureActive()
            val sha = expected.getOrNull(index)?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            val fileName = DownloadLayout.sanitizedBasename(url, index)
            if (DownloadLayout.isArchive(fileName)) return@forEachIndexed
            // Сжатые артефакты (slimt `*.bin.gz`/`*.spm.gz`): инсталлятор
            // gunzip-ит их при установке, в пучке лежат распакованные имена,
            // а ожидаемый sha из meta/каталога — хеш сжатых байт. Сверка была
            // бы формально недостижима — round-trip собственного slimt-экспорта
            // отваливался (контрольный аудит, п.1).
            if (ArchiveFormats.isCompressedArtifact(fileName)) return@forEachIndexed
            // Те же кандидаты имён, что у раскладки загрузок (п.15) —
            // единая точка [DownloadLayout.installedFileCandidates].
            val candidates = DownloadLayout.installedFileCandidates(modelId, url, index)
            val file = candidates.asSequence()
                .map { File(extracted, it) }
                .firstOrNull { it.isFile }
            val actual = if (file != null) sha256Hex(file) else null
            if (actual == null || !actual.equals(sha, ignoreCase = true)) {
                throw ImportRejectedException(
                    "SHA-256 файла $fileName модели $modelId не совпал с ожидаемым"
                )
            }
        }
    }

    /**
     * Распакованный ZIP → модель из вложенных метаданных (id из каталога).
     * @throws ImportRejectedException — контрольные суммы импорта не сошлись.
     * @return null, если category/runtime из метаданных не распознаются.
     */
    private suspend fun modelFromMeta(meta: ModelImportMeta, extracted: File): InstalledModel? {
        val runtime = try {
            RuntimeType.valueOf(meta.runtime)
        } catch (e: IllegalArgumentException) {
            Timber.w("Неизвестный runtime в метаданных ZIP: ${meta.runtime}")
            return null
        }
        val category = try {
            ModelCategory.valueOf(meta.category)
        } catch (e: IllegalArgumentException) {
            Timber.w("Неизвестная категория в метаданных ZIP: ${meta.category}")
            return null
        }
        // Сверка хэшей — ДО промоута: при несовпадении импорт отклоняется,
        // прежний пучок и staging остаются нетронутыми. Ожидаемые хэши —
        // из метаданных, иначе из существующей каталожной записи той же id
        // (импорт даёт id из каталога — карточка обязана соответствовать файлам).
        val importUrls = if (meta.urls.isNotEmpty()) meta.urls
        else listOfNotNull(meta.url.takeIf { it.isNotBlank() })
        // Ревью: одиночный sha256 из метаданных подставляется в общую сверку,
        // когда hashes-списка нет (round-trip собственного экспорта одиночного
        // файла восстанавливает контрольную сумму, а не пропускает сверку).
        val expectedSingleSha =
            if (importUrls.size == 1) meta.sha256?.takeIf { it.isNotBlank() } else null
        val expectedSha256s =
            meta.sha256s.takeIf { list -> list.any { !it.isNullOrBlank() } }
                ?: expectedSingleSha?.let { listOf(it) }
                ?: repository.getById(meta.id)?.sha256s
                ?: emptyList()
        verifyImportChecksums(meta.id, importUrls, expectedSha256s, extracted)
        val (targetDir, size) = promoteExtractedDir(extracted, runtime, meta.id)
            ?: return null
        return InstalledModel(
            id = meta.id,
            category = category,
            displayName = meta.displayName,
            description = meta.description,
            runtime = runtime,
            sizeBytes = size,
            ramRequiredMb = meta.ramRequiredMb,
            languages = meta.languages,
            quality = meta.quality,
            speed = meta.speed,
        url = meta.url,
        urls = meta.urls,
        // Ревью: round-trip — восстанавливаем одиночный sha256 из метаданных
        // (старые meta.json без поля дают null — прежняя семантика).
        sha256 = meta.sha256,
        sha256s = meta.sha256s,
            license = meta.license,
            minAndroidSdk = meta.minAndroidSdk,
            recommendedRamMb = meta.recommendedRamMb,
            status = ModelStatus.READY,
            progress = 100,
            filePath = targetDir.absolutePath,
            bytesDone = size,
            // Метаданные ZIP не могут объявлять встроенность модели: импорт —
            // всегда пользовательская запись (isBuiltin=false), иначе
            // cleanRemovedCatalogModels удалит импортированную модель при
            // исчезновении её id из каталога.
            isBuiltin = false
        )
    }

    /**
     * Запись импортированной модели с параметрами-заглушками, угаданными по
     * имени: пустые языки/URL, нейтральные качество и скорость, неизвестная
     * лицензия. Общий для одиночного файла (importFromUri) и ZIP без
     * метаданных (modelFromZipName).
     */
    private fun guessedImportedModel(
        modelId: String,
        sourceName: String,
        runtime: RuntimeType,
        size: Long,
        filePath: String
    ) = InstalledModel(
        id = modelId,
        category = guessCategory(sourceName),
        displayName = sourceName,
        description = "Импортированная модель",
        runtime = runtime,
        sizeBytes = size,
        ramRequiredMb = 0,
        languages = emptyList(),
        // Общие дефолты импортов (ревью) — единый источник с ModelImportMeta.
        quality = ImportedModelDefaults.QUALITY,
        speed = ImportedModelDefaults.SPEED,
        url = "",
        urls = emptyList(),
        sha256 = null,
        license = ImportedModelDefaults.LICENSE,
        minAndroidSdk = ImportedModelDefaults.MIN_ANDROID_SDK,
        recommendedRamMb = 0,
        status = ModelStatus.READY,
        progress = 100,
        filePath = filePath,
        bytesDone = size,
        isBuiltin = false
    )

    /**
     * Id модели из имени ZIP-файла без метаданных (общий расчёт для отмены
     * живой загрузки до промоута и для [modelFromZipName]); пустое — null.
     */
    private fun modelIdFromZipName(sourceName: String): String? =
        sanitizeModelIdFromName(
            sourceName
                .removeSuffix(".zip")
                .removeSuffix(".gguf")
                .removeSuffix(".onnx")
        ).takeIf { it.isNotBlank() }

    /**
     * Распакованный ZIP без метаданных → модель с параметрами,
     * угаданными по имени файла.
     */
    private suspend fun modelFromZipName(sourceName: String, extracted: File): InstalledModel? {
        val modelId = modelIdFromZipName(sourceName)
            ?: return null
        val runtime = guessRuntime(sourceName)
            ?: run {
                Timber.w("Неизвестный формат ZIP-модели $sourceName — импорт отклонён")
                return null
            }
        val (targetDir, size) = promoteExtractedDir(extracted, runtime, modelId)
            ?: return null
        return guessedImportedModel(
            modelId = modelId,
            sourceName = sourceName,
            runtime = runtime,
            size = size,
            filePath = targetDir.absolutePath
        )
    }

    /** Метаданные экспортируемой модели для ZIP. */
    private fun importMetaOf(model: InstalledModel): ModelImportMeta = ModelImportMeta(
        id = model.id,
        category = model.category.name,
        runtime = model.runtime.name,
        displayName = model.displayName,
        description = model.description,
        languages = model.languages,
        sizeBytes = model.sizeBytes,
        ramRequiredMb = model.ramRequiredMb,
        quality = model.quality,
        speed = model.speed,
        url = model.url,
        urls = model.urls,
        // Ревью: сохраняем одиночный sha256 — round-trip собственного экспорта
        // терял контрольную сумму, и повторный импорт пропускал сверку.
        sha256 = model.sha256,
        sha256s = model.sha256s,
        license = model.license,
        minAndroidSdk = model.minAndroidSdk,
        recommendedRamMb = model.recommendedRamMb,
        isBuiltin = model.isBuiltin
    )

    /**
     * Имя импортируемого документа. Для SAF-документов авторитетное имя —
     * OpenableColumns.DISPLAY_NAME из ContentResolver; кастомные провайдеры
     * без DISPLAY_NAME — fallback на query-параметр name и последний сегмент URI.
     */
    private fun queryFileName(uri: Uri): String? {
        displayNameFromResolver(uri)?.let { return it }
        uri.getQueryParameter("name")?.let { return it }
        val last = uri.lastPathSegment
        if (!last.isNullOrBlank()) return Uri.decode(last)
        return null
    }

    /** OpenableColumns.DISPLAY_NAME документа; null — недоступен/пусто/ошибка. */
    private fun displayNameFromResolver(uri: Uri): String? = try {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0) null else cursor.getString(index)?.takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        Timber.w(e, "Не удалось прочитать DISPLAY_NAME для $uri")
        null
    }

    private fun guessCategory(fileName: String): ModelCategory {
        val lower = fileName.lowercase()
        return when {
            lower.contains("whisper") || lower.contains("vosk") ||
                lower.contains("sherpa") || DownloadLayout.isArchive(lower) ->
                // sherpa-маркер и безмаркерный архив — STT-пачка (ArchiveInstaller).
                ModelCategory.STT
            else -> ModelCategory.TRANSLATION
        }
    }

    /**
     * Угадать рантайм по имени файла (реализация — [ModelFileNaming.guessRuntime],
     * общая с round-trip тестом именования экспорта). null — формат не распознан:
     * импорт отклоняется (с предупреждением в лог) вместо записи с битым
     * умолчанием MARIAN_ONNX.
     */
    private fun guessRuntime(fileName: String): RuntimeType? =
        ModelFileNaming.guessRuntime(fileName)

    override suspend fun deviceInfo(): DeviceInfo = deviceInfoProvider.get()
}

/**
 * Явный отказ импорта по содержимому архива (метаданные слишком большие,
 * несовпадение SHA-256): отличается от технических сбоев (IO/SAF) тем, что
 * повтор с тем же файлом бессилен — причину логирует [ModelManagerImpl.importZip].
 */
internal class ImportRejectedException(message: String) : Exception(message)

/**
 * Провайдер информации об устройстве (ОЗУ, диск, SDK).
 */
interface DeviceInfoProvider {

    /** Сбор данных блокирует (binder-вызовы AMS, StatFs) — реализация на IO. */
    suspend fun get(): DeviceInfo
}

/**
 * Реализация на ActivityManager + StatFs.
 */
@Singleton
class AndroidDeviceInfoProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : DeviceInfoProvider {

    override suspend fun get(): DeviceInfo = withContext(Dispatchers.IO) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val modelsDir = ModelStorage.modelsRoot(context)
        val stat = StatFs(modelsDir.absolutePath)
        val freeBytes = stat.availableBlocksLong * stat.blockSizeLong

        DeviceInfo(
            sdkInt = android.os.Build.VERSION.SDK_INT,
            totalRamMb = memInfo.totalMem / 1024 / 1024,
            availableRamMb = memInfo.availMem / 1024 / 1024,
            freeDiskBytes = freeBytes
        )
    }
}
