package com.plox.llmtranslate.data.catalog

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import com.plox.llmtranslate.domain.model.ModelCard
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.RuntimeType

/**
 * Сырая запись каталога из JSON.
 */
@Serializable
data class CatalogModelDto(
    val id: String,
    val category: String,
    val displayName: String,
    val description: String = "",
    val runtime: String,
    val sizeBytes: Long,
    val ramRequiredMb: Int = 0,
    val languages: List<String> = emptyList(),
    val quality: Int = 3,
    val speed: Int = 3,
    val url: String = "",
    val urls: List<String>? = null,
    val sha256: String? = null,
    val license: String = "Apache-2.0",
    val minAndroidSdk: Int = 26,
    val recommendedRamMb: Int = 0
)

@Serializable
data class ModelCatalogDto(
    val version: Int = 1,
    val models: List<CatalogModelDto> = emptyList()
)

/**
 * Парсер JSON-каталога. Вынесен для unit-тестов.
 */
class ModelCatalogParser(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
) {
    /**
     * Разобрать JSON-каталог в карточки.
     * @throws IllegalStateException при некорректном JSON или неизвестных значениях полей.
     */
    fun parse(raw: String): List<ModelCard> {
        val dto = json.decodeFromString(ModelCatalogDto.serializer(), raw)
        return dto.models.map { it.toCard() }
    }

    /**
     * Извлечь версию каталога из JSON.
     * @return версия или 0 при ошибке.
     */
    fun parseVersion(raw: String): Int {
        return try {
            val dto = json.decodeFromString(ModelCatalogDto.serializer(), raw)
            dto.version
        } catch (e: Exception) {
            0
        }
    }
}

private fun CatalogModelDto.toCard(): ModelCard {
    val category = ModelCategory.entries.firstOrNull { it.name == category.uppercase() }
        ?: throw IllegalStateException("Неизвестная категория: $category")
    val runtime = RuntimeType.entries.firstOrNull { it.name == runtime.uppercase() }
        ?: throw IllegalStateException("Неизвестный рантайм: $runtime")
    return ModelCard(
        id = id,
        category = category,
        displayName = displayName,
        description = description,
        runtime = runtime,
        sizeBytes = sizeBytes,
        ramRequiredMb = ramRequiredMb,
        languages = languages,
        quality = quality,
        speed = speed,
        url = url,
        urls = urls ?: emptyList(),
        sha256 = sha256,
        license = license,
        minAndroidSdk = minAndroidSdk,
        recommendedRamMb = recommendedRamMb
    )
}

/**
 * Провайдер каталога моделей.
 */
interface ModelCatalogProvider {
    val catalog: Flow<List<ModelCard>>
}

/**
 * Каталог из assets: обновляется вместе с APK.
 */
class AssetsModelCatalogProvider @JvmOverloads constructor(
    private val context: Context,
    private val parser: ModelCatalogParser = ModelCatalogParser(),
    private val assetName: String = DEFAULT_ASSET
) : ModelCatalogProvider {

    override val catalog: Flow<List<ModelCard>> = flow {
        val models = withContext(Dispatchers.IO) {
            context.assets.open(assetName).buffered().use { input ->
                parser.parse(input.readText())
            }
        }
        Timber.d("Каталог из assets: ${models.size} моделей")
        emit(models)
    }

    companion object {
        const val DEFAULT_ASSET = "models_catalog.json"
    }
}
