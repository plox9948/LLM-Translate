package com.plox.llmtranslate.data.catalog

import android.content.Context
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.model.ModelCard
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Провайдер удалённого каталога моделей.
 *
 * Логика:
 * 1. Результаты объединения кэшируются в памяти: сеть опрашивается один раз
 *    на процесс, повторные подписки получают готовый список.
 * 2. Сначала отдаётся встроенный каталог из assets (офлайн, быстро).
 * 3. Удалённый каталог из сети применяется, только если его версия новее
 *    уже применённой ([ModelPreferences.lastRemoteCatalogVersion]).
 * 4. При сетевой ошибке применяется кэш, если его версия не старше применённой
 *    (равная — это уже применённый каталог, повторное применение идемпотентно
 *    и сохраняет удалённые модели после перезапуска процесса).
 */
@Singleton
class RemoteCatalogProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val assetsProvider: AssetsModelCatalogProvider,
    private val parser: ModelCatalogParser,
    private val preferences: ModelPreferences
) : ModelCatalogProvider {

    companion object {
        // URL удалённого каталога. Пустая строка — только assets.
        const val REMOTE_CATALOG_URL = ""
        private const val CACHE_FILE_NAME = "remote_catalog.json"
    }

    /** Итоговый объединённый каталог: собирается один раз за процесс. */
    @Volatile
    private var mergedCache: List<ModelCard>? = null

    override val catalog: Flow<List<ModelCard>> = flow {
        // Повторная подписка — без сети, из памяти.
        mergedCache?.let {
            emit(it)
            return@flow
        }

        // 1. Сначала assets (мгновенно, офлайн).
        val assetsModels = assetsProvider.catalog.first()
        emit(assetsModels)

        // 2. Пытаемся получить удалённый каталог (новый или из кэша).
        val remoteModels = fetchRemoteCatalog()
        if (remoteModels != null && remoteModels.isNotEmpty()) {
            // 3. Объединяем: удалённый переопределяет assets по id.
            val merged = mergeCatalogs(assetsModels, remoteModels)
            mergedCache = merged
            emit(merged)
        } else {
            // Удалённой части нет — кэшируем assets, чтобы не ходить в сеть.
            mergedCache = assetsModels
        }
    }

    /**
     * Получить удалённый каталог: из сети или из кэша.
     * Применяется только тот, что не старше уже применённой версии.
     */
    private suspend fun fetchRemoteCatalog(): List<ModelCard>? = withContext(Dispatchers.IO) {
        val appliedVersion = preferences.lastRemoteCatalogVersion.first()

        if (REMOTE_CATALOG_URL.isBlank()) {
            // Удалённый каталог не настроен — пробуем кэш.
            return@withContext readCacheIfApplicable(appliedVersion)
        }

        var connection: java.net.HttpURLConnection? = null
        try {
            val url = java.net.URL(REMOTE_CATALOG_URL)
            val urlConn = url.openConnection() as java.net.HttpURLConnection
            connection = urlConn
            urlConn.connectTimeout = 10_000
            urlConn.readTimeout = 15_000
            urlConn.requestMethod = "GET"

            if (urlConn.responseCode == 200) {
                val json = urlConn.inputStream.buffered().use { it.readText() }
                val fetchedVersion = parser.parseVersion(json)

                if (fetchedVersion > appliedVersion) {
                    val models = parser.parse(json)
                    // Применяем: сохраняем в кэш и фиксируем применённую версию.
                    writeCache(json)
                    preferences.setLastRemoteCatalogVersion(fetchedVersion)
                    Timber.d("Удалённый каталог v$fetchedVersion: ${models.size} моделей")
                    models
                } else {
                    // Не новее применённого — используем кэш.
                    Timber.d(
                        "Удалённый каталог v$fetchedVersion не новее применённого " +
                            "v$appliedVersion"
                    )
                    readCacheIfApplicable(appliedVersion)
                }
            } else {
                Timber.w("Удалённый каталог: HTTP ${urlConn.responseCode}, используем кэш")
                readCacheIfApplicable(appliedVersion)
            }
        } catch (e: Exception) {
            Timber.w("Не удалось скачать удалённый каталог: ${e.message}. Используем кэш.")
            readCacheIfApplicable(appliedVersion)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Прочитать кэшированный каталог и применить, если его версия не старше
     * уже применённой (равная — это он же, повторное применение идемпотентно
     * и нужно после перезапуска процесса).
     */
    private fun readCacheIfApplicable(appliedVersion: Int): List<ModelCard>? {
        return try {
            val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
            if (!cacheFile.exists()) return null
            val json = cacheFile.readText()
            val cacheVersion = parser.parseVersion(json)
            if (cacheVersion < appliedVersion) {
                Timber.d("Кэш каталога v$cacheVersion старее применённого v$appliedVersion")
                return null
            }
            parser.parse(json)
        } catch (e: Exception) {
            Timber.w("Ошибка чтения кэша каталога: ${e.message}")
            null
        }
    }

    /**
     * Записать каталог в кэш.
     */
    private fun writeCache(json: String) {
        try {
            File(context.filesDir, CACHE_FILE_NAME).writeText(json)
        } catch (e: Exception) {
            Timber.w("Ошибка записи кэша каталога: ${e.message}")
        }
    }

    /**
     * Объединить каталоги: удалённый переопределяет assets по id.
     */
    private fun mergeCatalogs(assets: List<ModelCard>, remote: List<ModelCard>): List<ModelCard> {
        val remoteById = remote.associateBy { it.id }
        val merged = assets.map { card ->
            remoteById[card.id] ?: card
        }.toMutableList()

        // Добавляем модели, которых нет в assets.
        val assetIds = assets.map { it.id }.toSet()
        remote.filter { it.id !in assetIds }.forEach { merged.add(it) }

        return merged
    }
}


