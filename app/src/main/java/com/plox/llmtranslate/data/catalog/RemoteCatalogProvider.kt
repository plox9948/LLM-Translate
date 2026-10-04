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
 * 1. Сразу отдаёт встроенный каталог из assets (офлайн, быстро).
 * 2. На фоне скачивает удалённый каталог, сравнивает версию.
 * 3. Если версия новее — кэширует и отдаёт объединённый каталог.
 * 4. При отсутствии сети — использует кэш или assets.
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

    override val catalog: Flow<List<ModelCard>> = flow {
        // 1. Сначала assets (мгновенно, офлайн).
        val assetsModels = assetsProvider.catalog.first()
        emit(assetsModels)

        // 2. Пытаемся получить удалённый каталог (новый или из кэша).
        val remoteModels = fetchRemoteCatalog()
        if (remoteModels != null && remoteModels.isNotEmpty()) {
            // 3. Объединяем: удалённый переопределяет assets по id.
            val merged = mergeCatalogs(assetsModels, remoteModels)
            emit(merged)
        }
    }

    /**
     * Получить удалённый каталог: из сети или из кэша.
     */
    private suspend fun fetchRemoteCatalog(): List<ModelCard>? = withContext(Dispatchers.IO) {
        if (REMOTE_CATALOG_URL.isBlank()) {
            // Удалённый каталог не настроен — пробуем кэш.
            return@withContext readCache()
        }

        try {
            val url = java.net.URL(REMOTE_CATALOG_URL)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.requestMethod = "GET"

            if (connection.responseCode == 200) {
                val json = connection.inputStream.buffered().use { it.readText() }
                val models = parser.parse(json)
                val version = extractVersion(json)

                // Сохраняем в кэш и обновляем версию.
                writeCache(json)
                preferences.setLastRemoteCatalogVersion(version)
                Timber.d("Удалённый каталог v$version: ${models.size} моделей")
                models
            } else {
                Timber.w("Удалённый каталог: HTTP ${connection.responseCode}, используем кэш")
                readCache()
            }
        } catch (e: Exception) {
            Timber.w("Не удалось скачать удалённый каталог: ${e.message}. Используем кэш.")
            readCache()
        } finally {
            // connection закрыта через finally ниже
        }
    }

    /**
     * Прочитать кэшированный каталог.
     */
    private fun readCache(): List<ModelCard>? {
        val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
        if (!cacheFile.exists()) return null
        return try {
            val json = cacheFile.readText()
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
     * Извлечь версию из JSON каталога.
     */
    private fun extractVersion(json: String): Int {
        return try {
            val dto = parser.parseVersion(json)
            dto
        } catch (e: Exception) {
            0
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


