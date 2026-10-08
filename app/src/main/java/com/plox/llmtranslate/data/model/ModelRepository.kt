package com.plox.llmtranslate.data.model

import com.plox.llmtranslate.domain.model.InstalledModel
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
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeByCategory(category: ModelCategory): Flow<List<InstalledModel>> =
        dao.observeByCategory(category.name).map { list -> list.map { it.toDomain() } }

    fun observeById(id: String): Flow<InstalledModel?> =
        dao.observeById(id).map { it?.toDomain() }

    suspend fun getById(id: String): InstalledModel? =
        dao.getById(id)?.toDomain()

    /**
     * Первая готовая модель категории (для автопереключения при удалении активной).
     */
    suspend fun getFirstReady(category: ModelCategory): InstalledModel? =
        dao.getFirstReady(category.name)?.toDomain()

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
}

private fun ModelEntity.toDomain(): InstalledModel = InstalledModel(
    id = id,
    category = ModelCategory.valueOf(category),
    displayName = displayName,
    description = description,
    runtime = RuntimeType.valueOf(runtime),
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
        status = ModelStatus.valueOf(status),
    progress = progress,
    filePath = filePath,
    bytesDone = bytesDone,
    isBuiltin = isBuiltin
)

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
