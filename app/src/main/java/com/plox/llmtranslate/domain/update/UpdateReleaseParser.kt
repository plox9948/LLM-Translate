package com.plox.llmtranslate.domain.update

import timber.log.Timber
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Результат разбора JSON релиза GitHub (issue #95).
 * Битый JSON отличается от «релиза нет» и от «APK-ассета нет» — вызывающий
 * маппит исходы в [UpdateCheckResult] (Parse / UpToDate / NoAsset).
 */
sealed class ReleaseParseResult {
    /** Разобран годный релиз с APK-ассетом. */
    data class Release(val info: ReleaseInfo) : ReleaseParseResult()

    /**
     * Актуального публичного релиза нет: latest stable не может быть
     * draft/prerelease, фильтр защитный (endpoint `releases/latest` их и так
     * исключает) — трактуется как «обновления нет».
     */
    object NotStable : ReleaseParseResult()

    /** У релиза нет APK-ассета (ни по content_type, ни по расширению имени). */
    object NoAsset : ReleaseParseResult()

    /** JSON не разобрался вовсе. */
    object ParseError : ReleaseParseResult()
}

/** JSON-DTO релиза GitHub Releases API (.unknown keys допускаются средой Json). */
@Serializable
private data class ReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<AssetDto> = emptyList()
)

/** JSON-DTO ассета релиза. */
@Serializable
private data class AssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    @SerialName("content_type") val contentType: String = ""
)

/**
 * Парсер JSON релиза GitHub Releases API (issue #95).
 *
 * Чистая функция разбора строки JSON — без сети и без DI (Json передаётся,
 * как в [com.plox.llmtranslate.data.catalog.ModelCatalogParser]).
 * Отбор APK-ассета: ПЕРВЫЙ с content_type
 * "application/vnd.android.package-archive"; если такого нет — fallback
 * первый с именем на ".apk". draft/prerelease — защитный фильтр:
 * актуальным публичным релизом считается только stable latest
 * (endpoint `releases/latest` стабильный и так, флаг оставлен как страховка
 * от битого/подменённого ответа).
 */
class UpdateReleaseParser(
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    companion object {
        /** MIME-тип APK-ассета, по которому он опознаётся в первую очередь. */
        const val APK_CONTENT_TYPE = "application/vnd.android.package-archive"
    }

    /**
     * Разобрать JSON одного релиза (ответ `releases/latest`).
     * @return исход разбора; исключений не бросает — битый JSON это
     *         [ReleaseParseResult.ParseError].
     */
    fun parse(raw: String): ReleaseParseResult {
        val dto = try {
            json.decodeFromString(ReleaseDto.serializer(), raw)
        } catch (e: Exception) {
            Timber.w(e, "Не удалось разобрать JSON релиза GitHub")
            return ReleaseParseResult.ParseError
        }
        if (dto.tagName.isBlank()) {
            Timber.w("JSON релиза GitHub без tag_name — считается неразобранным")
            return ReleaseParseResult.ParseError
        }
        if (dto.draft || dto.prerelease) {
            return ReleaseParseResult.NotStable
        }
        val asset = dto.assets.firstOrNull {
                it.contentType.equals(APK_CONTENT_TYPE, ignoreCase = true)
            }
            ?: dto.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: return ReleaseParseResult.NoAsset
        return ReleaseParseResult.Release(
            ReleaseInfo(
                tagName = dto.tagName,
                version = UpdateVersionLogic.tagToVersion(dto.tagName),
                releaseUrl = dto.htmlUrl,
                apkUrl = asset.browserDownloadUrl,
                assetName = asset.name
            )
        )
    }
}
