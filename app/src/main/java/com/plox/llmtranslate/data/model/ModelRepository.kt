package com.plox.llmtranslate.data.model

import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
import com.plox.llmtranslate.domain.model.decodeDelimitedList
import com.plox.llmtranslate.domain.model.encodeDelimitedList
import com.plox.llmtranslate.domain.model.joinDelimitedList
import com.plox.llmtranslate.domain.model.sha256sFromCsv
import com.plox.llmtranslate.domain.model.sha256sToCsv
import com.plox.llmtranslate.domain.model.splitDelimitedList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Репозиторий моделей поверх Room.
 */
@Singleton
class ModelRepository @Inject constructor(
    private val dao: ModelDao
) {

    fun observeAll(): Flow<List<InstalledModel>> =
        dao.observeAll().map { list -> list.mapNotNull { it.toDomainOrNull() } }

    fun observeByCategory(category: ModelCategory): Flow<List<InstalledModel>> =
        dao.observeByCategory(category.name)
            .map { list -> list.mapNotNull { it.toDomainOrNull() } }

    /**
     * Модель по id. Возвращает null, если записи нет или она содержит
     * неизвестные enum-значения (устаревшая строка БД).
     */
    suspend fun getById(id: String): InstalledModel? =
        dao.getById(id)?.toDomainOrNull()

    /**
     * Первая готовая модель категории (для автопереключения при удалении активной).
     */
    suspend fun getFirstReady(category: ModelCategory): InstalledModel? =
        dao.getFirstReady(category.name)?.toDomainOrNull()

    /**
     * Сохранить модель. Timestamp'ы createdAt/updatedAt поддерживаются
     * автоматически: read-modify-write прежней createdAt выполняется в одном
     * Room-[@Transaction] ([ModelDao.upsertPreservingCreatedAt]) — параллельные
     * upsert одной id не затирают createdAt взаимно.
     *
     * Требование к вызывающему: файловые операции с той же моделью
     * (подмена файлов, импорт, удаление/отмена скачивания) обязаны идти
     * под общим [com.plox.llmtranslate.data.backup.ModelImportLock] —
     * атомарность транзакции защищает только строку БД от перемешивания
     * upsert'ов, но не согласует её с заменой файлов на диске; под локом
     * параллельный upsert одной id невозможен вовсе.
     */
    suspend fun upsert(model: InstalledModel) {
        val now = System.currentTimeMillis()
        dao.upsertPreservingCreatedAt(
            entity = model.toEntity(createdAt = now, updatedAt = now),
            createdAtFallback = now,
            updatedAt = now
        )
    }

    /**
     * Точечное обновление только статуса скачивания (ошибки, повторы).
     * Каталогные поля не перезаписываются — безопаснее read-modify-write
     * upsert'а при гонке с [syncCatalogRamFields].
     *
     * @return false, если запись удалена/не найдена (защита от гонки с
     * cancel/delete — удалённая запись не восстанавливается).
     */
    @Deprecated(
        "Продакшн-вызывающих не осталось: используйте гейтированные " +
            "updateDownloadStatusWhileDownloading/finalizeDownload (status-гейт, issue #83)"
    )
    suspend fun updateDownloadStatus(id: String, status: ModelStatus): Boolean =
        dao.updateStatusById(id, status.name, System.currentTimeMillis()) > 0

    /**
     * Точечное обновление статуса и процента скачивания, каталожные поля
     * не затрагиваются, см. [updateDownloadStatus].
     */
    suspend fun updateDownloadProgress(id: String, status: ModelStatus, progress: Int): Boolean =
        dao.updateProgressById(id, status.name, progress, System.currentTimeMillis()) > 0

    /**
     * Точечная терминальная запись статуса воркером (permanent-отказ,
     * исчерпание попыток, повтор) только пока запись остаётся в
     * DOWNLOADING — единый статусный гейт с [finalizeDownload]
     * (см. [ModelDao.updateStatusWhileDownloadingById]): запись, промоутнутую
     * параллельным импортом в READY в окне между фейлом установки и этой
     * записью, ERROR/DOWNLOADING не затираем.
     *
     * @return false, если запись удалена или уже не в DOWNLOADING.
     */
    suspend fun updateDownloadStatusWhileDownloading(id: String, status: ModelStatus): Boolean =
        dao.updateStatusWhileDownloadingById(id, status.name, System.currentTimeMillis()) > 0

    /**
     * Точечное завершение скачивания: READY, прогресс 100, filePath, bytesDone
     * (фактический объём установленных файлов). Каталогные поля не
     * затрагиваются, см. [updateDownloadStatus]. Готовит только запись в
     * DOWNLOADING: удалённая или выведенная из DOWNLOADING строка не
     * обновляется (см. [ModelDao.finalizeDownloadById]).
     *
     * @return false, если запись удалена или уже не в DOWNLOADING.
     */
    suspend fun finalizeDownload(id: String, filePath: String, bytesDone: Long): Boolean =
        dao.finalizeDownloadById(
            id,
            ModelStatus.READY.name,
            100,
            filePath,
            bytesDone,
            System.currentTimeMillis()
        ) > 0

    suspend fun deleteById(id: String) {
        dao.deleteById(id)
    }

    /**
     * Сверка READY-записей с фактическим наличием файлов на диске (ревью, п.1):
     * одиночный файл должен существовать и быть непустым, мультифайловый
     * пучок/распакованный архив — непустым каталогом. Битые записи переводятся
     * в ERROR без удаления (пользователь увидит ошибку и сможет переустановить).
     *
     * [snapshot] возвращает снимок пути или null, когда путь недоступен для
     * verdict (родительский каталог не виден — внешний storage временно
     * отмонтирован): такие записи не трогаются ни для каталожных, ни для
     * IMPORTED-моделей.
     *
     * @return id записей, реально переведённых в ERROR (гейтированный
     * UPDATE — запись, выведенную из READY между снимком и обновлением,
     * не трогаем и в список не включаем).
     */
    internal suspend fun markBrokenReadyRecords(snapshot: (String) -> FileSnapshot?): List<String> {
        val now = System.currentTimeMillis()
        val broken = dao.observeAll().first()
            .filter { it.status == ModelStatus.READY.name }
            .filter { !readyRecordIsConsistent(it.filePath, snapshot) }
        // Регресс-аудит: UPDATE с гейтом status='READY'
        // (см. ModelDao.markBrokenReadyById) — иначе гонка с переустановкой
        // (READY→DOWNLOADING) между снимком списка и UPDATE затирает живую
        // загрузку в ERROR. Возвращаются только реально обновлённые id.
        val repaired = broken.filter {
            dao.markBrokenReadyById(it.id, ModelStatus.ERROR.name, now) > 0
        }
        if (repaired.isNotEmpty()) {
            Timber.w(
                "READY-записи без файлов на диске переведены в ERROR: " +
                    repaired.joinToString { "${it.id} (${it.filePath})" }
            )
        }
        return repaired.map { it.id }
    }

    /**
     * Сверка DOWNLOADING-записей с WorkManager при старте процесса
     * (issue #83, п.2): «залипший» status=DOWNLOADING — запись без живой
     * unique-работы скачивания (терминальное падение воркера до фиксации
     * статуса, гонка отмены/постановки). UI держит карточку «скачивающейся»
     * без кнопки повтора; такие записи переводятся в ERROR — пользователь
     * сможет повторить. Законное возобновление скачиваний не затрагивается:
     * WorkManager переживает перезапуск процесса и сам возобновляет
     * поставленную работу — такие id [hasLiveWork] признаёт живыми
     * (ENQUEUED/RUNNING/BLOCKED).
     *
     * @param hasLiveWork признак живой unique-работы скачивания по id;
     *        при недоступном WorkManager вызывающий обязан вернуть true
     *        (ложный ERROR хуже, чем перепроверка на следующем старте).
     * @return id записей, переведённых в ERROR.
     */
    internal suspend fun markStaleDownloadingRecords(hasLiveWork: (String) -> Boolean): List<String> {
        val now = System.currentTimeMillis()
        val stale = dao.observeAll().first()
            .filter { it.status == ModelStatus.DOWNLOADING.name }
            .filter { !hasLiveWork(it.id) }
        // Регресс-ревью issue #83: UPDATE с гейтом status='DOWNLOADING'
        // (см. ModelDao.markStaleDownloadingById) — иначе гонка с
        // финализирующим воркером между снимком списка и UPDATE переводит
        // уже READY-запись в ERROR. Возвращаются только реально
        // обновлённые id.
        val repaired = stale.filter {
            dao.markStaleDownloadingById(it.id, ModelStatus.ERROR.name, now) > 0
        }
        if (repaired.isNotEmpty()) {
            Timber.w(
                "DOWNLOADING-записи без живой работы скачивания переведены в ERROR: " +
                    repaired.joinToString { it.id }
            )
        }
        return repaired.map { it.id }
    }
    /**
     * Синхронизация полей ram_required_mb/recommended_ram_mb у существующих
     * записей БД с актуальным каталогом по id.
     *
     * Поля копируются из каталога один раз при старте скачивания, поэтому
     * ранее установленные модели хранят устаревшие оценки (карточка
     * «Загружено в память» и предупреждение о ОЗУ показали бы старые значения).
     * Вызывается при загрузке каталога на старте процесса (см. OfflineTranslatorApp).
     *
     * Обновляются только реально существующие встроенные записи, значение
     * которых отличается от каталожного; импортированные модели не затрагиваются.
     *
     * @param catalog карточки актуального (объединённого) каталога.
     * @return число обновлённых записей.
     */
    suspend fun syncCatalogRamFields(catalog: List<ModelCard>): Int {
        var updated = 0
        for (card in catalog) {
            val existing = dao.getById(card.id) ?: continue
            if (existing.ramRequiredMb == card.ramRequiredMb &&
                existing.recommendedRamMb == card.recommendedRamMb
            ) {
                continue
            }
            val rows = dao.syncRamFields(
                id = card.id,
                ramRequiredMb = card.ramRequiredMb,
                recommendedRamMb = card.recommendedRamMb,
                updatedAt = System.currentTimeMillis()
            )
            updated += rows
        }
        if (updated > 0) {
            Timber.d("Синхронизировано оценок ОЗУ с каталогом: $updated")
        }
        return updated
    }

    /**
     * Очистка устаревших записей: модели с рантаймами, которых больше нет в [RuntimeType]
     * (например, GGUF/ONNX/VOSK после миграции инференс-слоя).
     *
     * Читает сырые сущности [ModelEntity] (runtime как строка), чтобы не падать на
     * [RuntimeType.valueOf] для неизвестных значений.
     *
     * @return список filePath удалённых моделей, чтобы удалить файлы с диска.
     */
    suspend fun cleanObsoleteRuntimes(): List<String> = deleteCatalogRecords(
        matching = { entity -> RuntimeType.entries.none { it.name == entity.runtime } },
        removedLabel = "устаревших моделей из БД"
    )

    /**
     * Очистка каталожных моделей, которых больше нет в каталоге (например,
     * после смены id или формата модели: opus-mt с Teradata-графа на Optimum).
     * Импортированные пользователем модели (isBuiltin=false) не затрагиваются.
     *
     * @param knownIds id моделей текущего каталога.
     * @return список filePath удалённых записей для удаления файлов с диска.
     */
    suspend fun cleanRemovedCatalogModels(knownIds: Set<String>): List<String> = deleteCatalogRecords(
        matching = { it.isBuiltin && it.id !in knownIds },
        removedLabel = "отсутствующих в каталоге моделей из БД"
    ).distinct()

    /**
     * Общий обход каталога чисток: удалить из БД записи, помеченные [matching],
     * с логом «Удалено <removedLabel>: N».
     * @return filePath удалённых записей (пропускают null-пути).
     */
    private suspend fun deleteCatalogRecords(
        matching: (ModelEntity) -> Boolean,
        removedLabel: String
    ): List<String> {
        val removed = dao.observeAll().first().filter(matching)
        if (removed.isEmpty()) return emptyList()
        removed.forEach { dao.deleteById(it.id) }
        Timber.d("Удалено $removedLabel: ${removed.size}")
        return removed.mapNotNull { it.filePath }
    }
}

/**
 * Устойчивое маппинг-преобразование сущности в домен.
 *
 * Записи с устаревшими значениями enum (результат даунгрейда или смены
 * рантаймов) пропускаются с предупреждением, а не роняют подписчиков Flow
 * исключением из [valueOf]. Очисткой таких строк занимается
 * [ModelRepository.cleanObsoleteRuntimes].
 */
// internal — покрыто unit-тестом устойчивого маппинга (без Room).
internal fun ModelEntity.toDomainOrNull(): InstalledModel? {
    val categoryValue = runCatching { ModelCategory.valueOf(category) }.getOrNull()
    val runtimeValue = runCatching { RuntimeType.valueOf(runtime) }.getOrNull()
    val statusValue = runCatching { ModelStatus.valueOf(status) }.getOrNull()
    if (categoryValue == null || runtimeValue == null || statusValue == null) {
        Timber.w(
            "Пропущена запись модели $id: неизвестные category=$category, " +
                "runtime=$runtime, status=$status"
        )
        return null
    }
    // Список languages — legacy-разделители ('\n' / ','): языковые коды
    // заведомо без разделителей. urls — однозначно с префиксом «v1:»
    // (decodeDelimitedList), без префикса — legacy-строки (см. helpers).
    val languagesValue = splitDelimitedList(languages).filter { it.isNotBlank() }
    val urlsValue = decodeDelimitedList(urls).filter { it.isNotBlank() }
    return InstalledModel(
        id = id,
        category = categoryValue,
        displayName = displayName,
        description = description,
        runtime = runtimeValue,
        sizeBytes = sizeBytes,
        ramRequiredMb = ramRequiredMb,
        languages = languagesValue,
        quality = quality,
        speed = speed,
        url = url,
        urls = urlsValue,
        sha256 = sha256,
        sha256s = sha256sFromCsv(sha256s),
        license = license,
        minAndroidSdk = minAndroidSdk,
        recommendedRamMb = recommendedRamMb,
        status = statusValue,
        progress = progress,
        filePath = filePath,
        bytesDone = bytesDone,
        isBuiltin = isBuiltin
    )
}

/**
 * Снимок файлового пути для проверки согласованности READY-записи
 * (ревью, п.1) — позволяет подменить ФС в unit-тесте.
 */
internal data class FileSnapshot(
    val isDirectory: Boolean,
    val size: Long,
    val childCount: Int
)

/**
 * Чистая проверка: READY-запись с [filePath] согласована с диски-снимком из
 * [snapshot]. filePath == null — проверять нечем (не считается ошибкой);
 * snapshot == null — путь недоступен (storage отмонтирован/не читается),
 * вердикт не выносится. Файл обязан существовать и быть непустым; каталог —
 * непустым. internal — покрыто unit-тестом с фейковым DAO без Android.
 */
internal fun readyRecordIsConsistent(
    filePath: String?,
    snapshot: (String) -> FileSnapshot?
): Boolean {
    val path = filePath ?: return true
    val snap = snapshot(path) ?: return true
    return if (snap.isDirectory) snap.childCount > 0 else snap.size > 0
}

private fun InstalledModel.toEntity(createdAt: Long, updatedAt: Long): ModelEntity =
    ModelEntity(
        id = id,
        category = category.name,
        displayName = displayName,
        description = description,
        runtime = runtime.name,
        sizeBytes = sizeBytes,
        ramRequiredMb = ramRequiredMb,
        languages = joinDelimitedList(languages),
        quality = quality,
        speed = speed,
        // url — основная ссылка (первая), urls — все зеркала/файлы; список
        // urls пишется однозначно с префиксом «v1:» (encodeDelimitedList),
        // languages — plain-склейка «\n» (коды без разделителей).
        url = url,
        urls = encodeDelimitedList(urls),
        sha256 = sha256,
        sha256s = sha256sToCsv(sha256s),
        license = license,
        minAndroidSdk = minAndroidSdk,
        recommendedRamMb = recommendedRamMb,
        status = status.name,
        progress = progress,
        filePath = filePath,
        bytesDone = bytesDone,
        isBuiltin = isBuiltin,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
