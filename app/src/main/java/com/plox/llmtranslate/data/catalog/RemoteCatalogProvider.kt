package com.plox.llmtranslate.data.catalog

import android.content.Context
import com.plox.llmtranslate.data.prefs.ModelPreferences
import com.plox.llmtranslate.domain.model.ModelCard
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Провайдер удалённого каталога моделей.
 *
 * Логика:
 * 1. Результаты объединения кэшируются в памяти: сеть опрашивается один раз
 *    на процесс, повторные подписки получают готовый список.
 * 2. При каждой подписке СРАЗУ и без замка эмиттируется встроенный каталог
 *    из assets (офлайн, быстро) — не дожидаясь сетевого этапа (ревью повт.
 *    аудита, п.4: батчинг эмиссий под мьютексом оставлял UI пустым на
 *    десятки секунд при включённом удалённом URL).
 * 3. Удалённый каталог из сети применяется, только если его версия новее
 *    уже применённой ([ModelPreferences.lastRemoteCatalogVersion]).
 *    Чтение версии защищено consumer-таймаутом [APPLIED_VERSION_READ_TIMEOUT_MS]
 *    (ревью повт. аудита, п.3): null-mode обёртки при холодном сбое DataStore
 *    не эмитит вовсе, и `first()` зависал бы под [catalogMutex] навсегда.
 *    Версия неизвестна — сравнение пропускается консервативно: кэш
 *    (по инварианту «кэш ≥ применённая версия») принимается без сверки,
 *    свежая загрузка — только заведомо лучше кэша; применённая версия в
 *    настройках при этом НЕ перезаписывается наугад.
 * 4. При сетевой ошибке применяется кэш, если его версия не старше применённой
 *    (равная — это уже применённый каталог, повторное применение идемпотентно
 *    и сохраняет удалённые модели после перезапуска процесса).
 * 5. Любой сбой этапа ПОСЛЕ эмиссии assets (сбой настроек, записи кэша,
 *    неожиданные исключения сети) не роняет поток [catalog]: подписчик
 *    остаётся на assets-списке — graceful fallback, обещанный контрактом
 *    [ModelCatalogProvider] (ревью).
 * 6. Сетевой этап (fetch + commit) выполняется под [catalogMutex], его
 *    финальная эмиссия — вне замка: медленный коллектор не удерживает
 *    общий мьютекс (ревью повт. аудита, п.4).
 *
 * Осознанный долг (контрольный аудит, п.6): удалённый каталог принимается
 * БЕЗ криптографической подписи/целостности самого JSON — защита только
 * косвенная: монотонная версия, HTTPS-транспорт и SHA-256 каждой модели при
 * скачивании. Подпись каталога — не реализована, долг до её появления.
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
        // Непустой — обязателен HTTPS: иная схема отклоняется в fetchRemoteCatalog
        // с логом и фолбэком на кэш/assets (ревью).
        const val REMOTE_CATALOG_URL = ""
        private const val CACHE_FILE_NAME = "remote_catalog.json"

        /**
         * Consumer-таймаут чтения применённой версии каталога (ревью повт.
         * аудита, п.3): lastRemoteCatalogVersion — обёртка в null-режиме,
         * при холодном сбое DataStore эмиссии нет вообще, и first() зависал
         * бы под каталожным мьютексом навсегда (весь поток catalog).
         */
        private const val APPLIED_VERSION_READ_TIMEOUT_MS = 5_000L
    }

    /** Итоговый объединённый каталог: собирается один раз за процесс. */
    @Volatile
    private var mergedCache: List<ModelCard>? = null

    /**
     * Замок сетевого этапа каталога (fetch + commit + merge): проверяет кэш
     * и публикует результат атомарно, чтобы параллельные подписки не гоняли
     * check-then-act и не ходили в сеть несколько раз (запись в кэш под
     * замком идемпотентна). Эмиссии потока [catalog] выполняются вне замка
     * (ревью повт. аудита, п.4).
     */
    private val catalogMutex = Mutex()

    /**
     * Применялась ли удалённая часть каталога в итоговой эмиссии [catalog].
     * false при первом чтении означает «удалённых данных нет» (пустой URL без
     * кэша, сеть недоступна и кэш потерян/стар). Признак используется очисткой
     * устаревших моделей, чтобы не удалить каталожные модели удалённого
     * каталога при неполном списке.
     */
    @Volatile
    var remotePartApplied: Boolean = false
        private set

    override val catalog: Flow<List<ModelCard>> = flow {
        val cached = mergedCache
        if (cached != null) {
            // Повторная подписка — без сети и без assets-промежутка.
            emit(cached)
            return@flow
        }
        // 1. Assets — СРАЗУ и вне замка, для каждого подписчика отдельно
        //    (ревью повт. аудита, п.4: эмиссия assets под замком после
        //    fetch оставляла UI пустым на десятки секунд при включённом
        //    удалённом URL).
        val assetsModels = assetsProvider.catalog.first()
        emit(assetsModels)
        // 2. Пытаемся получить удалённый каталог (новый или из кэша) —
        //    сетевой этап под замком, его эмиссия — после withLock:
        //    медленный коллектор не держит catalogMutex. Сбой этапа после
        //    готовности assets не роняет поток: обрабатывается как
        //    «удалённой части нет» (KDoc, п.5).
        val finalList = catalogMutex.withLock {
            mergedCache?.let { return@withLock it }
            val remoteModels = try {
                fetchRemoteCatalog()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Сбой применения удалённого каталога — остаёмся на assets")
                null
            }
            // Пустой удалённый каталог легитимен и мержится наравне с
            // непустым: важно отличать null («удалённой части нет») от
            // пустого списка — иначе remotePartApplied ложно блокирует
            // очистку ранее добавленных удалённых моделей.
            if (remoteModels != null) {
                // 3. Объединяем: удалённый переопределяет assets по id.
                val merged = mergeCatalogs(assetsModels, remoteModels)
                mergedCache = merged
                remotePartApplied = true
                merged
            } else {
                // Удалённой части нет — кэшируем assets, чтобы не ходить в сеть.
                mergedCache = assetsModels
                assetsModels
            }
        }
        emit(finalList)
    }

    /**
     * Получить удалённый каталог: из сети или из кэша.
     * Применяется только тот, что не старше уже применённой версии.
     *
     * Ревью повт. аудита, п.3 (consumer-защита): применённая версия
     * читается с таймаутом [APPLIED_VERSION_READ_TIMEOUT_MS] — обёртка
     * [ModelPreferences.lastRemoteCatalogVersion] в null-режиме при
     * холодном сбое DataStore не эмитит вовсе, и `first()` вешал весь
     * поток каталога под мьютексом. Версия неизвестна (null) — сравнение
     * консервативно пропускается: границей служит версия кэша (по
     * инварианту «кэш ≥ применённая версия», см. [commitRemoteCatalog]),
     * при отсутствии кэша — 0; применённая версия в настройках наугад
     * НЕ перезаписывается. Кэш при неизвестной версии принимается без
     * сверки — иначе remotePartApplied=false и очистка устаревших моделей
     * (OfflineTranslatorApp) потеряла бы READY-модели удалённого каталога.
     */
    private suspend fun fetchRemoteCatalog(): List<ModelCard>? = withContext(Dispatchers.IO) {
        val appliedVersion = withTimeoutOrNull(APPLIED_VERSION_READ_TIMEOUT_MS) {
            preferences.lastRemoteCatalogVersion.first()
        }
        if (appliedVersion == null) {
            Timber.w(
                "Применённая версия удалённого каталога не прочитана (таймаут/сбой " +
                    "DataStore) — сравнение версий пропускаем консервативно"
            )
        }

        if (REMOTE_CATALOG_URL.isBlank()) {
            // Удалённый каталог не настроен — пробуем кэш.
            return@withContext readCacheIfApplicable(appliedVersion)
        }

        var connection: HttpURLConnection? = null
        try {
            val url = URL(REMOTE_CATALOG_URL)
            // Явное требование HTTPS (ревью): незащищённая или иная схема —
            // отказ от сети с логом, фолбэк на кэш/assets.
            if (!url.protocol.equals("https", ignoreCase = true)) {
                Timber.w(
                    "REMOTE_CATALOG_URL задан со схемой «${url.protocol}» — " +
                        "ожидается https, удалённый каталог пропущен"
                )
                return@withContext readCacheIfApplicable(appliedVersion)
            }
            val urlConn = url.openConnection() as HttpURLConnection
            connection = urlConn
            urlConn.connectTimeout = 10_000
            urlConn.readTimeout = 15_000
            urlConn.requestMethod = "GET"

            if (urlConn.responseCode == 200) {
                val json = urlConn.inputStream.use { it.readBytes().decodeToString() }
                val fetchedVersion = parser.parseVersion(json)
                // Issue #83, п.9: битый JSON при HTTP 200 даёт нулевую версию и
                // молча игнорируется навсегда — фиксируем в логе (семантику
                // молчаливой отмены обновления не меняем).
                if (fetchedVersion <= 0) {
                    Timber.w(
                        "Удалённый каталог отдал HTTP 200, но версия не считана " +
                            "или нулевая (битый JSON?) — обновление проигнорировано"
                    )
                }

                // Порог актуальности (ревью повт. аудита, п.3): при
                // неизвестной применённой версии — версия кэша (по
                // инварианту «кэш ≥ применённая»); если ни версии, ни
                // кэша нет — граница заведомо недостижима: свежий каталог
                // консервативно НЕ принимается (иначе откат версии на
                // сервере удалил бы READY-модели применённой версии).
                val versionBound = appliedVersion ?: cacheVersion() ?: Int.MAX_VALUE
                if (fetchedVersion > versionBound) {
                    val models = parser.parse(json)
                    // Инвариант «кэш ≥ применённая версия» (ревью): версия
                    // фиксируется только при успешной записи кэша. Иначе при
                    // следующем старте без сети кэш отсутствовал бы/старел,
                    // отдавались только assets (remotePartApplied=false), а
                    // осиротевшие READY-модели версии не чистились никогда.
                    if (commitRemoteCatalog(json, fetchedVersion)) {
                        Timber.d("Удалённый каталог v$fetchedVersion: ${models.size} моделей")
                        models
                    } else {
                        Timber.w(
                            "Кэш удалённого каталога не записан — версия v$fetchedVersion " +
                                "не фиксируется, используем прежний кэш"
                        )
                        readCacheIfApplicable(appliedVersion)
                    }
                } else {
                    // Не новее применённого — используем кэш.
                    Timber.d(
                        "Удалённый каталог v$fetchedVersion не новее применённого " +
                            "v$versionBound"
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
     * и нужно после перезапуска процесса). [appliedVersion] = null —
     * применённая версия неизвестна (consumer-таймаут чтения): сравнение
     * пропускается, кэш принимается (он не старше применённой по инварианту
     * «кэш ≥ применённая версия», см. [commitRemoteCatalog]).
     */
    private fun readCacheIfApplicable(appliedVersion: Int?): List<ModelCard>? {
        return try {
            val cacheFile = cacheFile()
            if (!cacheFile.exists()) return null
            val json = cacheFile.readText()
            val cacheVersion = parser.parseVersion(json)
            val applied = appliedVersion
            if (applied != null && cacheVersion < applied) {
                Timber.d("Кэш каталога v$cacheVersion старее применённого v$applied")
                return null
            }
            parser.parse(json)
        } catch (e: Exception) {
            Timber.w("Ошибка чтения кэша каталога: ${e.message}")
            null
        }
    }

    /** Файл кэша удалённого каталога. */
    private fun cacheFile(): File = File(context.filesDir, CACHE_FILE_NAME)

    /**
     * Версия каталога в кэше на диске (для порога сравнения при неизвестной
     * применённой версии, ревью повт. аудита, п.3); null — кэша нет/не читается.
     */
    private fun cacheVersion(): Int? = try {
        val file = cacheFile()
        if (!file.exists()) null else parser.parseVersion(file.readText())
    } catch (e: Exception) {
        Timber.w("Не удалось прочитать версию кэша каталога: ${e.message}")
        null
    }

    /**
     * Зафиксировать загруженный удалённый каталог: сначала запись кэша, и только
     * при её успехе — применённая версия ([ModelPreferences.setLastRemoteCatalogVersion]).
     * Инвариант «кэш ≥ применённая версия» (ревью): при провале кэша версия не
     * фиксируется — пропущенное обновление повторится при следующем обращении,
     * а применённая когда-то версия не останется без подкреплённого кэша.
     * internal — покрыто unit-тестом.
     * @return true — кэш записан и версия зафиксирована.
     */
    internal suspend fun commitRemoteCatalog(json: String, version: Int): Boolean {
        if (!writeCache(json)) return false
        preferences.setLastRemoteCatalogVersion(version)
        return true
    }

    /**
     * Записать каталог в кэш.
     * @return true — файл кэша записан; false — ошибка (версия не должна
     *         фиксироваться, см. [commitRemoteCatalog]).
     */
    private fun writeCache(json: String): Boolean = try {
        File(context.filesDir, CACHE_FILE_NAME).writeText(json)
        true
    } catch (e: Exception) {
        Timber.w("Ошибка записи кэша каталога: ${e.message}")
        false
    }

    /**
     * Объединить каталоги: удалённый переопределяет assets по id.
     * Issue #83, п.10: дубли id внутри удалённого каталога давали дубли
     * карточек в UI — дедупликация по id, первое вхождение побеждает.
     * internal — покрыто unit-тестом.
     */
    internal fun mergeCatalogs(assets: List<ModelCard>, remote: List<ModelCard>): List<ModelCard> {
        // Первые вхождения удалённого каталога побеждают повторы.
        val remoteById = LinkedHashMap<String, ModelCard>()
        remote.forEach { card ->
            if (!remoteById.containsKey(card.id)) {
                remoteById[card.id] = card
            } else {
                Timber.w("Удалённый каталог: повторный id ${card.id} — пропущен")
            }
        }
        val merged = assets.map { card ->
            remoteById[card.id] ?: card
        }.toMutableList()

        // Добавляем модели, которых нет в assets (уже без дублей по id).
        val assetIds = assets.map { it.id }.toSet()
        remoteById.values.filter { it.id !in assetIds }.forEach { merged.add(it) }

        // Финальная гарантия единственного id на весь merged-список
        // (первые позиции побеждают).
        return merged.distinctBy { it.id }
    }
}


