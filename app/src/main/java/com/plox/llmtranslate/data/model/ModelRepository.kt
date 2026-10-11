package com.plox.llmtranslate.data.model

import com.plox.llmtranslate.domain.model.InstalledModel
import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.ModelStatus
import com.plox.llmtranslate.domain.model.RuntimeType
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

    fun observeById(id: String): Flow<InstalledModel?> =
        dao.observeById(id).map { it?.toDomainOrNull() }

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
     * Сохранить модель. Timestamp'ы createdAt/updatedAt поддерживаются автоматически.
     */
    suspend fun upsert(model: InstalledModel) {
        val existing = dao.getById(model.id)
        val now = System.currentTimeMillis()
        dao.upsert(
            model.toEntity(
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
        )
    }

    suspend fun deleteById(id: String) {
        dao.deleteById(id)
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
    suspend fun cleanObsoleteRuntimes(): List<String> {
        val all = dao.observeAll().first()
        val obsolete = all.filter { entity ->
            RuntimeType.entries.none { it.name == entity.runtime }
        }
        if (obsolete.isEmpty()) return emptyList()
        obsolete.forEach { dao.deleteById(it.id) }
        Timber.d("Удалено устаревших моделей из БД: ${obsolete.size}")
        return obsolete.mapNotNull { it.filePath }
    }

    /**
     * Очистка каталожных моделей, которых больше нет в каталоге (например,
     * после смены id или формата модели: opus-mt с Teradata-графа на Optimum).
     * Импортированные пользователем модели (isBuiltin=false) не затрагиваются.
     *
     * @param knownIds id моделей текущего каталога.
     * @return список filePath удалённых записей для удаления файлов с диска.
     */
    suspend fun cleanRemovedCatalogModels(knownIds: Set<String>): List<String> {
        val all = dao.observeAll().first()
        val removed = all.filter { it.isBuiltin && it.id !in knownIds }
        if (removed.isEmpty()) return emptyList()
        removed.forEach { dao.deleteById(it.id) }
        Timber.d("Удалено отсутствующих в каталоге моделей из БД: ${removed.size}")
        return removed.mapNotNull { it.filePath }.distinct()
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
    return InstalledModel(
        id = id,
        category = categoryValue,
        displayName = displayName,
        description = description,
        runtime = runtimeValue,
        sizeBytes = sizeBytes,
        ramRequiredMb = ramRequiredMb,
        languages = languages.split(',').filter { it.isNotBlank() },
        quality = quality,
        speed = speed,
        url = url,
        urls = urls.split(',').filter { it.isNotBlank() },
        sha256 = sha256,
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

private fun InstalledModel.toEntity(createdAt: Long, updatedAt: Long): ModelEntity =
    ModelEntity(
        id = id,
        category = category.name,
        displayName = displayName,
        description = description,
        runtime = runtime.name,
        sizeBytes = sizeBytes,
        ramRequiredMb = ramRequiredMb,
        languages = languages.joinToString(","),
        quality = quality,
        speed = speed,
        url = url,
        urls = urls.joinToString(","),
        sha256 = sha256,
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
