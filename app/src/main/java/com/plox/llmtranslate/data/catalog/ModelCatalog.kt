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
    /** Контрольные суммы по индексам urls (null-элемент — проверка пропускается). */
    val sha256s: List<String?>? = null,
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
     * Модели с неизвестными category/runtime отбрасываются с предупреждением.
     * @throws IllegalStateException при некорректном JSON.
     */
    fun parse(raw: String): List<ModelCard> {
        val dto = json.decodeFromString(ModelCatalogDto.serializer(), raw)
        return dto.models.mapNotNull { modelDto ->
            try {
                modelDto.toCard()
            } catch (e: IllegalStateException) {
                Timber.w("Модель ${modelDto.id} пропущена: ${e.message}")
                null
            }
        }
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
    val categoryValue = ModelCategory.entries.firstOrNull { it.name == category.uppercase() }
        ?: throw IllegalStateException("Неизвестная категория: ${this.category}")
    val runtimeValue = RuntimeType.entries.firstOrNull { it.name == runtime.uppercase() }
        ?: throw IllegalStateException("Неизвестный рантайм: ${this.runtime}")
    return ModelCard(
        id = id,
        category = categoryValue,
        displayName = displayName,
        description = description,
        runtime = runtimeValue,
        sizeBytes = sizeBytes,
        ramRequiredMb = ramRequiredMb,
        languages = languages,
        quality = quality,
        speed = speed,
        url = url,
        urls = urls ?: emptyList(),
        sha256 = sha256,
        sha256s = resolveSha256s(sha256, sha256s),
        license = license,
        minAndroidSdk = minAndroidSdk,
        recommendedRamMb = recommendedRamMb
    )
}

/**
 * Контрольные суммы по URL: явный список [rawList] как есть; если он пуст —
 * единственный [single] как хеш первого (одиночного) файла.
 */
private fun resolveSha256s(single: String?, rawList: List<String?>?): List<String?> =
    if (!rawList.isNullOrEmpty()) {
        rawList
    } else {
        listOfNotNull(single?.takeIf { it.isNotBlank() })
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
            context.assets.open(assetName).use { input ->
                parser.parse(input.readBytes().decodeToString())
            }
        }
        Timber.d("Каталог из assets: ${models.size} моделей")
        emit(models)
    }

    companion object {
        const val DEFAULT_ASSET = "models_catalog.json"
    }
}
