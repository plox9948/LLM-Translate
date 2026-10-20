package com.plox.llmtranslate.data.catalog

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
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
    val recommendedRamMb: Int = 0,
    /** Скрыта из интерфейса каталога (issue #55), остаётся в каталоге и коде. */
    val hidden: Boolean = false
)

@Serializable
data class ModelCatalogDto(
    val version: Int = 1,
    /**
     * Карта «язык → id карточки TTS по умолчанию» (ревью): карточка,
     * используемая фолбэком озвучивания для языка. Пуста — применяется
     * резервная константа [com.plox.llmtranslate.domain.model.TtsModelSelector.DEFAULT_LANGUAGE_CARDS].
     */
    @SerialName("default_for_language")
    val defaultForLanguage: Map<String, String> = emptyMap(),
    val models: List<CatalogModelDto> = emptyList()
)

/**
 * Парсер JSON-каталога. Вынесен для unit-тестов.
 */
class ModelCatalogParser(
    private val json: Json = Json {
        // Формат каталога собственный и строгий: isLenient допускал мусор
        // вроде кавычек-чисел и нецитированных ключей (ревью).
        ignoreUnknownKeys = true
    }
) {
    /**
     * Разобрать JSON-каталог в карточки.
     * Модели с неизвестными category/runtime отбрасываются с предупреждением.
     * @throws IllegalStateException при некорректном JSON.
     */
    fun parse(raw: String): List<ModelCard> {
        val dto = json.decodeFromString(ModelCatalogDto.serializer(), raw)
        // Реверс карты defaults: id карточки → язык, для которого она назначена.
        // Конфликт битого каталога — одна модель назначена дефолтом нескольким
        // языкам: молча побеждает алфавитно последний язык; случай фиксируется
        // в логе (ревью), семантику выбора не меняем.
        dto.defaultForLanguage.entries
            .groupBy { it.value }
            .filterValues { entries -> entries.size > 1 }
            .forEach { (modelId, entries) ->
                Timber.w(
                    "Каталог default_for_language: модель $modelId значится дефолтом " +
                        "для нескольких языков (${entries.joinToString { it.key }}) — " +
                        "применяется алфавитно последний"
                )
            }
        val defaultByModel = dto.defaultForLanguage.entries
            .sortedBy { it.key }
            .associate { (language, modelId) -> modelId to language }
        // Issue #83, п.10: дубли id внутри каталога (особенно удалённого)
        // давали дубли карточек в UI — оставляем первое вхождение, остальные
        // отбрасываем с предупреждением.
        val seenIds = HashSet<String>()
        return dto.models.mapNotNull { modelDto ->
            if (!seenIds.add(modelDto.id)) {
                Timber.w("Каталог: повторный id ${modelDto.id} — оставлено первое вхождение")
                return@mapNotNull null
            }
            try {
                modelDto.toCard(defaultByModel[modelDto.id])
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

private fun CatalogModelDto.toCard(defaultLanguage: String?): ModelCard {
    val categoryValue = ModelCategory.entries.firstOrNull { it.name == category.uppercase() }
        ?: throw IllegalStateException("Неизвестная категория: ${this.category}")
    val runtimeValue = RuntimeType.entries.firstOrNull { it.name == runtime.uppercase() }
        ?: throw IllegalStateException("Неизвестный рантайм: ${this.runtime}")
    val resolvedUrls = urls ?: emptyList()
    val resolvedSha256s = resolveSha256s(sha256, sha256s)
    // Битый каталог (ревью): элементов sha256s больше/меньше, чем urls —
    // perUrlSha256s недостающие позиции получает как null и молча пропускает
    // сверку скачивания; рассогласование фиксируем в логе.
    if (sha256s != null && sha256s.isNotEmpty() && resolvedUrls.isNotEmpty() &&
        sha256s.size != resolvedUrls.size
    ) {
        Timber.w(
            "Каталог $id: длина sha256s (${sha256s.size}) не равна числу urls " +
                "(${resolvedUrls.size}) — для недостающих файлов проверка будет пропущена"
        )
    }
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
        urls = resolvedUrls,
        sha256 = sha256,
        sha256s = resolvedSha256s,
        license = license,
        minAndroidSdk = minAndroidSdk,
        recommendedRamMb = recommendedRamMb,
        hidden = hidden,
        defaultForLanguage = defaultLanguage
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
class AssetsModelCatalogProvider(
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
