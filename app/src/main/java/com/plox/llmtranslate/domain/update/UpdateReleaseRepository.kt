package com.plox.llmtranslate.domain.update

/**
 * Репозиторий самообновления приложения через GitHub Releases (issue #95).
 *
 * Контракт для UI-слоя: проверка наличия стабильного релиза новее
 * установленной версии и скачивание APK-ассета в кэш-каталог `updates`.
 */
interface UpdateReleaseRepository {

    /**
     * Проверить наличие обновления: latest stable релиз GitHub сравнивается
     * с [currentVersion] (BuildConfig.VERSION_NAME у вызывающего).
     */
    suspend fun checkForUpdate(currentVersion: String): UpdateCheckResult

    /**
     * Скачать APK релиза [release] в кэш-каталог `updates`.
     * @param onProgress целые проценты 0..100 (эмиттся только при изменении);
     *        при неизвестном Content-Length промежуточные проценты не эмиттся.
     */
    suspend fun downloadApk(release: ReleaseInfo, onProgress: (Int) -> Unit): DownloadApkResult
}
