package com.plox.llmtranslate.domain.update

import java.io.File

/**
 * Публичный стабильный релиз GitHub с APK-ассетом (issue #95).
 */
data class ReleaseInfo(
    /** Исходный тег релиза ("v0.5.19"). */
    val tagName: String,
    /** Версия без префикса тега ("0.5.19") — для сравнения с установленной. */
    val version: String,
    /** Страница релиза на GitHub (html_url) — для перехода «что нового». */
    val releaseUrl: String,
    /** Прямая ссылка скачивания APK (browser_download_url ассета). */
    val apkUrl: String,
    /** Имя APK-ассета. */
    val assetName: String
)

/** Причина сбоя проверки обновлений (issue #95). */
enum class UpdateCheckError {
    /** Сеть недоступна или HTTP-ошибка, не 404. */
    Network,

    /** Релиз не найден (HTTP 404 — у репозитория ещё нет ни одного релиза). */
    NotFound,

    /** Ответ API не разобрался (битый/неожиданный JSON). */
    Parse,

    /** У актуального релиза нет APK-ассета. */
    NoAsset
}

/** Результат проверки обновлений (issue #95). */
sealed class UpdateCheckResult {
    /** Обновлений нет: актуальный стабильный релиз не новее установленной версии. */
    object UpToDate : UpdateCheckResult()

    /** Найден стабильный релиз новее установленной версии. */
    data class UpdateAvailable(val release: ReleaseInfo) : UpdateCheckResult()

    /** Проверка не удалась. */
    data class Failed(val reason: UpdateCheckError) : UpdateCheckResult()
}

/** Результат скачивания APK обновления (issue #95). */
sealed class DownloadApkResult {
    /** APK полностью скачан и лежит в [file] (кэш-каталог `updates`). */
    data class Success(val file: File) : DownloadApkResult()

    /**
     * Скачивание не удалось. Отдельного FileError в контракте обновления нет,
     * поэтому сетевые и файловые сбои классифицируются как
     * [UpdateCheckError.Network] (повтор скачивания имеет смысл в обоих случаях).
     */
    data class Failed(val error: UpdateCheckError) : DownloadApkResult()
}
