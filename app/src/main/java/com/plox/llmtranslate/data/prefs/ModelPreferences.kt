package com.plox.llmtranslate.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.TtsEngines
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** Имя preference-файла DataStore. */
private const val DATASTORE_NAME = "model_prefs"

/**
 * Общий на процесс инстанс DataStore (делегат preferencesDataStore не даёт
 * передать corruptionHandler). Повреждённый .preferences_pb файл при чтении
 * бросает IOException/CorruptionException и вешает ВСЕ Flow-читатели настроек;
 * [ReplaceFileCorruptionHandler] при сбое чтения удаляет файл и продолжает с
 * пустыми preferences (значения по умолчанию).
 */
private val dataStoreLock = Any()
private var dataStoreInstance: DataStore<Preferences>? = null

private val Context.dataStore: DataStore<Preferences>
    get() = dataStoreInstance ?: synchronized(dataStoreLock) {
        dataStoreInstance ?: PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { File(filesDir, "$DATASTORE_NAME.preferences_pb") }
        ).also { dataStoreInstance = it }
    }

/** Пауза перед переподпиской потока настройки после сбоя чтения DataStore, мс. */
private const val PREFERENCE_RETRY_DELAY_MS = 2_000L

/**
 * Каркас отказоустойчивых потоков настроек (ревью контрольного раунда 4):
 * исключение источника DataStore в `launch/collect` без обработчика роняло
 * процесс (тот же сценарий битого journal, что и для Room-потоков). ВСЕ
 * публичные потоки [ModelPreferences] построены через этот хелпер, поэтому
 * любая подписка на `preferences.*` (MainActivity, TranslateViewModel,
 * SettingsViewModel, ModelsViewModel, TtsRuntime.trackEnginePreference)
 * безопасна по построению — сбой источника не доходит до коллектора.
 *
 * При сбое чтения эмитится последнее полученное значение; если значение ещё
 * не приходило — ленивый [fallbackProvider] (обращение в момент сбоя), иначе
 * константный [fallback]. Затем пауза и переподписка на [source] (shared
 * DataStore flow: после само-восстановления corruptionHandler'а подписка
 * получает актуальные данные). Штатное завершение источника — выход без
 * переподписки; [CancellationException] пробрасывается коллектору.
 *
 * Регресс (исправление): [channelFlow] вместо `flow` — защищается ТОЛЬКО
 * чтение источника; в прежней версии `emit(value)` стоял внутри try и
 * исключение коллектора (подписчика) попадало в catch как «сбой источника»,
 * запуская ложные ретраи и повторные переподписки. В channelFlow эмиссия
 * идёт в канал, а исключение потребитель получает уже снаружи защищаемого
 * блока.
 *
 * Регресс (исправление): [fallbackProvider] — ленивый фолбэк, запрашиваемый
 * в момент сбоя. uiLanguage не может иметь константный дефолт: при сбое
 * записи («persist» не дошёл, кэш процесса уже обновлён) обёртка эмитила бы
 * «system» ≠ применённому значению и MainActivity зацикливался на recreate()
 * каждые retryDelayMs. Фолбэк uiLanguage — процессный кэш (см. [uiLanguage]).
 *
 * Режим без фолбэка (оба — null, [fallback] по умолчанию null): до первого
 * реального значения ничего не эмитится, только пауза и переподписка —
 * подписчик не видит правдоподобный дефолт вместо настоящих настроек
 * (onboardingDone: холодный сбой чтения не должен показывать онбординг;
 * lastSourceLang/lastTargetLang — не должен сбрасывать выбранное направление).
 *
 * `observeResilient` в ViewModel остаётся второй линией защиты (Room-потоки).
 * Исключения без обёртки — только потоки, читаемые ОДНИМ `first()` с
 * консервативной обработкой сбоя у самого вызывающего (провайдер удержания
 * DomainModule: при ошибке — unload, а не дефолт keep; версия каталога для
 * очистки устаревших моделей; сырое чтение `uiLanguage` для prefetch/await,
 * где фолбэк нельзя прогревать в кэш как настоящее значение; экспорт бэкапа
 * читает сырые raw*-потоки — сбой чтения обязан быть ошибкой экспорта, а не
 * дефолтом в архиве). Их подписок в UI нет либо подписки идут через
 * `observeResilient` (SettingsScreen).
 */
internal fun <T> flowWithRecovery(
    name: String,
    fallback: T? = null,
    retryDelayMs: Long = PREFERENCE_RETRY_DELAY_MS,
    fallbackProvider: (() -> T)? = null,
    source: () -> Flow<T>
): Flow<T> = channelFlow {
    var last: T? = null
    var received = false
    while (true) {
        try {
            source().collect { value ->
                last = value
                received = true
                send(value)
            }
            break
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Сбой чтения настройки $name — фолбэк и переподписка")
            // Фолбэк выбирается в момент сбоя: последнее реально полученное
            // значение, иначе ленивый provider (процессный кэш), иначе
            // константа; при отсутствии всех — эмиссии нет (null-режим).
            val value: T? = if (received) last else (fallbackProvider?.invoke() ?: fallback)
            if (received || value != null) {
                @Suppress("UNCHECKED_CAST")
                send(value as T)
            }
            delay(retryDelayMs)
        }
    }
}

/**
 * Настройки выбора моделей и приложения (DataStore).
 */
@Singleton
class ModelPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
    // App-scope из DomainModule (SupervisorJob + Dispatchers.IO + handler):
    // фоновые правки DataStore живут со всем процессом, а не с разовой
    // ad-hoc корутиной (ревью).
    private val scope: CoroutineScope
) {

    private val activeModelKeys = mapOf(
        ModelCategory.TRANSLATION to stringPreferencesKey("active_translation"),
        ModelCategory.STT to stringPreferencesKey("active_stt"),
        ModelCategory.TTS to stringPreferencesKey("active_tts")
    )

    val activeModelId: (ModelCategory) -> Flow<String?> = { category ->
        val key = activeModelKeys[category]!!
        flowWithRecovery("active_model_${category.name}") {
            context.dataStore.data.map { prefs -> prefs[key] }
        }
    }

    suspend fun setActiveModelId(category: ModelCategory, modelId: String?) {
        val key = activeModelKeys[category]!!
        context.dataStore.edit { prefs ->
            if (modelId == null) prefs.remove(key) else prefs[key] = modelId
        }
    }

    suspend fun getActiveModelId(category: ModelCategory): String? =
        context.dataStore.data.first()[activeModelKeys[category]!!]

    // --- STT-модель на каждый язык (issue #28) ---

    /**
     * Карта «код языка → id STT-модели» (языки: "ru"/"en"/"ne").
     * Сериализация значения — JSON-строка в preference `stt_by_language`.
     */
    /**
     * Сырой поток карты STT-назначений (без recover-обёртки) — только для
     * экспорта бэкапа: сбой чтения DataStore обязан стать ошибкой экспорта,
     * а не молча записанным в архив пустым значением (регресс: обёртка
     * «съедала» сбой и экспорт с дефолтами затем затирал реальные настройки).
     */
    internal val rawSttByLanguage: Flow<Map<String, String>> =
        context.dataStore.data.map { prefs ->
            decodeLanguageMap(prefs[STT_BY_LANGUAGE_KEY], STT_BY_LANGUAGE_KEY, STT_BY_LANGUAGE_NAME)
        }

    val sttByLanguage: Flow<Map<String, String>> =
        flowWithRecovery(STT_BY_LANGUAGE_NAME, emptyMap()) { rawSttByLanguage }

    /**
     * Назначить STT-модель языку; null — снять назначение.
     */
    suspend fun setSttForLanguage(language: String, modelId: String?) =
        setForLanguage(STT_BY_LANGUAGE_KEY, STT_BY_LANGUAGE_NAME, language, modelId)

    // --- TTS-модель на каждый язык (issue #50) ---

    /**
     * Карта «код языка → id Piper TTS-модели или сентинель "system"»
     * (языки: "ru"/"en"/"ne"; issue #53 — явное назначение системного TTS).
     * Значение отсутствует — тоже системный TTS (по умолчанию).
     * Сериализация значения — JSON-строка в preference `tts_by_language`.
     */
    /** Сырой поток карты TTS-назначений — только для экспорта бэкапа (см. [rawSttByLanguage]). */
    internal val rawTtsByLanguage: Flow<Map<String, String>> =
        context.dataStore.data.map { prefs -> decodeTtsByLanguage(prefs[TTS_BY_LANGUAGE_KEY]) }

    val ttsByLanguage: Flow<Map<String, String>> =
        flowWithRecovery(TTS_BY_LANGUAGE_NAME, emptyMap()) { rawTtsByLanguage }

    /**
     * Назначить языку Piper TTS-модель по id или Системный Android TTS
     * ([com.plox.llmtranslate.domain.model.TtsModelSelector.SYSTEM_SENTINEL]);
     * null — снять назначение.
     */
    suspend fun setTtsForLanguage(language: String, modelId: String?) =
        setForLanguage(TTS_BY_LANGUAGE_KEY, TTS_BY_LANGUAGE_NAME, language, modelId)

    /** Декод JSON-карты «язык → id» для TTS-ключа. */
    private fun decodeTtsByLanguage(raw: String?): Map<String, String> =
        decodeLanguageMap(raw, TTS_BY_LANGUAGE_KEY, TTS_BY_LANGUAGE_NAME)

    /**
     * Общая запись языковой карты (STT/TTS): снять назначение при null,
     * удалить ключ при опустевшей карте.
     */
    private suspend fun setForLanguage(
        key: Preferences.Key<String>,
        keyName: String,
        language: String,
        modelId: String?
    ) {
        context.dataStore.edit { prefs ->
            val map = decodeLanguageMap(prefs[key], key, keyName).toMutableMap()
            if (modelId == null) map.remove(language) else map[language] = modelId
            if (map.isEmpty()) {
                prefs.remove(key)
            } else {
                prefs[key] = json.encodeToString(LANGUAGE_MAP_SERIALIZER, map)
            }
        }
    }

    /**
     * Декод JSON-карты «язык → id»; повреждённое значение — сброс и удаление
     * ключа из DataStore. Без удаления битое значение остаётся в хранилище
     * и каждая следующая запись пересобирается из пусто-карты вместо явного сброса.
     */
    private fun decodeLanguageMap(
        raw: String?,
        key: Preferences.Key<String>,
        keyName: String
    ): Map<String, String> =
        if (raw.isNullOrBlank()) emptyMap() else try {
            json.decodeFromString(LANGUAGE_MAP_SERIALIZER, raw)
        } catch (e: Exception) {
            Timber.w(e, "Повреждённое значение $keyName, сброшено")
            clearCorruptedLanguageMap(key, keyName)
            emptyMap()
        }

    /** Удалить повреждённый языковой ключ из DataStore (см. [decodeLanguageMap]). */
    private fun clearCorruptedLanguageMap(key: Preferences.Key<String>, keyName: String) {
        scope.launch {
            try {
                context.dataStore.edit { prefs -> prefs.remove(key) }
            } catch (e: Exception) {
                Timber.w(e, "Не удалось удалить повреждённый ключ $keyName")
            }
        }
    }

    /**
     * Снять все назначения «язык → модель» с languages-картами, ссылающиеся
     * на удалённую модель [modelId] (ревью: delete() не должен оставлять в
     * настройках висячие id). Ключ удаляется при опустевшей карте.
     */
    suspend fun removeModelFromLanguageAssignments(modelId: String) {
        context.dataStore.edit { prefs ->
            stripModelFromMap(prefs, STT_BY_LANGUAGE_KEY, STT_BY_LANGUAGE_NAME, modelId)
            stripModelFromMap(prefs, TTS_BY_LANGUAGE_KEY, TTS_BY_LANGUAGE_NAME, modelId)
        }
    }

    /** Убрать из языковой карты pref-значения, равные [modelId] (без записи, если изменений нет). */
    private fun stripModelFromMap(
        prefs: MutablePreferences,
        key: Preferences.Key<String>,
        keyName: String,
        modelId: String
    ) {
        val map = decodeLanguageMap(prefs[key], key, keyName).toMutableMap()
        val removedLanguages = map.filterValues { it == modelId }.keys
        if (removedLanguages.isEmpty()) return
        removedLanguages.forEach { map.remove(it) }
        if (map.isEmpty()) {
            prefs.remove(key)
        } else {
            prefs[key] = json.encodeToString(LANGUAGE_MAP_SERIALIZER, map)
        }
    }

    // --- Последнее направление перевода (issue #33) ---

    /**
     * Сохранённые коды языков последнего направления перевода ("ru"/"en"/"ne");
     * null — направление ещё не выбиралось (AUTO не сохраняется).
     */
    /** Сырые потоки последнего направления — только для экспорта бэкапа (см. [rawSttByLanguage]). */
    internal val rawLastSourceLang: Flow<String?> =
        context.dataStore.data.map { it[LAST_SOURCE_LANG_KEY] }

    internal val rawLastTargetLang: Flow<String?> =
        context.dataStore.data.map { it[LAST_TARGET_LANG_KEY] }

    // Null-режим обёртки (без фолбэка): при сбое чтения до первого значения
    // эмиссии нет — коллекторы (combine в TranslateViewModel/ModelsViewModel)
    // не получают «направление сброшено» вместо реального сбоя чтения.
    val lastSourceLang: Flow<String?> =
        flowWithRecovery("last_source_lang") { rawLastSourceLang }

    val lastTargetLang: Flow<String?> =
        flowWithRecovery("last_target_lang") { rawLastTargetLang }

    /**
     * Сохранить последнее выбранное направление перевода.
     */
    suspend fun setLastDirection(sourceLang: String, targetLang: String) {
        context.dataStore.edit { prefs ->
            prefs[LAST_SOURCE_LANG_KEY] = sourceLang
            prefs[LAST_TARGET_LANG_KEY] = targetLang
        }
    }

    // --- Одноразовый онбординг (issue #33) ---

    /**
     * Завершен ли онбординг первого запуска; false до первого выбора/пропуска.
     *
     * Null-режим обёртки (без фолбэка false): холодный сбой чтения не должен
     * эмитить ложный «онбординг не пройден» — sheet не показывается до
     * прилёта реального значения (регресс: фолбэк false включал онбординг).
     */
    val onboardingDone: Flow<Boolean> =
        flowWithRecovery("onboarding_done") {
            context.dataStore.data.map { it[ONBOARDING_DONE_KEY] ?: false }
        }

    /**
     * Отметить онбординг пройденным (больше не показывать).
     */
    suspend fun setOnboardingDone() {
        context.dataStore.edit { prefs -> prefs[ONBOARDING_DONE_KEY] = true }
    }

    // --- Настройки приложения ---

    /** Сырой поток TTS-движка — только для экспорта бэкапа (см. [rawSttByLanguage]). */
    internal val rawTtsEngine: Flow<String> =
        context.dataStore.data.map { it[TTS_ENGINE_KEY] ?: TtsEngines.SYSTEM }

    val ttsEngine: Flow<String> =
        flowWithRecovery("tts_engine", TtsEngines.SYSTEM) { rawTtsEngine }

    suspend fun setTtsEngine(engine: String) {
        context.dataStore.edit { it[TTS_ENGINE_KEY] = engine }
    }

    /**
     * Сырое (без recover-обёртки) чтение uiLanguage — только для prefetch,
     * [awaitUiLanguageCache] и экспорта бэкапа: фолбэк по умолчанию нельзя
     * прогревать в кэш как настоящее значение, иначе он залипает при
     * последующих recreate() и коллектор бесконечно чинит расхождение языка
     * пересозданием активности; в бэкапе сбой чтения = ошибка экспорта.
     */
    internal val rawUiLanguage: Flow<String> =
        context.dataStore.data.map { it[UI_LANGUAGE_KEY] ?: DEFAULT_UI_LANGUAGE }

    /**
     * Поток языка UI. Фолбэк при сбое чтения — ленивый ([fallbackProvider]):
     * процессный кэш [uiLanguageCacheRef], если прогреваем, иначе дефолт
     * (регресс: константный «system» при persist-сбое расходился с
     * уже применённым языком и MainActivity зацикливался на recreate()
     * каждые PREFERENCE_RETRY_DELAY_MS).
     */
    val uiLanguage: Flow<String> = flowWithRecovery(
        "ui_language",
        fallbackProvider = { uiLanguageCacheRef.get() ?: DEFAULT_UI_LANGUAGE }
    ) { rawUiLanguage }

    /**
     * Кэш uiLanguage в памяти процесса: прогревается [prefetchUiLanguage]
     * и обновляется при записи. Позволяет применять локаль синхронно на старте
     * и при recreate() без блокировки главной нити на чтение DataStore.
     */
    private val uiLanguageCacheRef = AtomicReference<String?>(null)

    /**
     * Прогреть кэш uiLanguage в памяти (вызывать как можно раньше — из
     * Application.onCreate). Повторные вызовы идемпотентны.
     */
    fun prefetchUiLanguage() {
        if (uiLanguageCacheRef.get() != null) return
        scope.launch {
            try {
                val value = rawUiLanguage.first()
                // CAS: не перетирать кэш, обновлённый setUiLanguage() во время чтения DataStore.
                uiLanguageCacheRef.compareAndSet(null, value)
            } catch (e: Exception) {
                Timber.w(e, "Не удалось прогреть кэш uiLanguage")
            }
        }
    }

    /**
     * Значение uiLanguage из памяти: прогретый кэш или первое чтение DataStore
     * (оно единственный раз может занять время IO).
     */
    suspend fun awaitUiLanguageCache(): String =
        uiLanguageCacheRef.get() ?: rawUiLanguage.first()

    suspend fun setUiLanguage(lang: String) {
        uiLanguageCacheRef.set(lang)
        context.dataStore.edit { it[UI_LANGUAGE_KEY] = lang }
    }

    /** Сырой поток темы — только для экспорта бэкапа (см. [rawSttByLanguage]). */
    internal val rawTheme: Flow<String> =
        context.dataStore.data.map { it[THEME_KEY] ?: DEFAULT_THEME }

    val theme: Flow<String> =
        flowWithRecovery("theme", DEFAULT_THEME) { rawTheme }

    suspend fun setTheme(theme: String) {
        context.dataStore.edit { it[THEME_KEY] = theme }
    }

    /**
     * Тайм-аут отложенной выгрузки рантаймов при сворачивании приложения, минуты:
     * 0 — выгружать сразу, -1 — не выгружать. По умолчанию 5.
     */
    /** Сырой поток тайм-аута выгрузки — только для экспорта бэкапа (см. [rawSttByLanguage]). */
    internal val rawUnloadTimeoutMinutes: Flow<Int> =
        context.dataStore.data.map {
            it[UNLOAD_TIMEOUT_MINUTES_KEY] ?: DEFAULT_UNLOAD_TIMEOUT_MINUTES
        }

    val unloadTimeoutMinutes: Flow<Int> =
        flowWithRecovery("unload_timeout_minutes", DEFAULT_UNLOAD_TIMEOUT_MINUTES) { rawUnloadTimeoutMinutes }

    suspend fun setUnloadTimeoutMinutes(minutes: Int) {
        context.dataStore.edit { it[UNLOAD_TIMEOUT_MINUTES_KEY] = minutes }
    }

    /**
     * Поведение движков при смене направления перевода (issue #36):
     * [RETENTION_KEEP] — не выгружать модели другого направления по умолчанию,
     * [RETENTION_UNLOAD] — выгружать перед загрузкой новой модели.
     *
     * Без recover-обёртки ([flowWithRecovery]): единственный подписчик —
     * `watch` в SettingsScreen поверх `observeResilient`, а одноразовое
     * `first()` в провайдере DomainModule на сбой чтения обязано отвечать
     * conservative unload, а не дефолтом keep (ревью).
     */
    val modelDirectionRetention: Flow<String> =
        context.dataStore.data.map {
            it[MODEL_DIRECTION_RETENTION_KEY] ?: DEFAULT_MODEL_DIRECTION_RETENTION
        }

    suspend fun setModelDirectionRetention(value: String) {
        context.dataStore.edit { prefs ->
            prefs[MODEL_DIRECTION_RETENTION_KEY] =
                if (value == RETENTION_UNLOAD) RETENTION_UNLOAD else RETENTION_KEEP
        }
    }

    val lastRemoteCatalogVersion: Flow<Int> =
        context.dataStore.data.map { it[REMOTE_CATALOG_VERSION_KEY] ?: 0 }

    suspend fun setLastRemoteCatalogVersion(version: Int) {
        context.dataStore.edit { it[REMOTE_CATALOG_VERSION_KEY] = version }
    }

    companion object {
        /** Тайм-аут выгрузки рантаймов по умолчанию, минуты. */
        const val DEFAULT_UNLOAD_TIMEOUT_MINUTES = 5

        /**
         * Язык интерфейса по умолчанию («по системе») — единственная точка
         * фолбэка для потока [uiLanguage] (значение при непрогретом процессном
         * кэше) и для таймаутного фолбэка MainActivity (ревью: не дублировать
         * литерал).
         */
        const val DEFAULT_UI_LANGUAGE = "system"

        /**
         * Значения настройки темы интерфейса (ревью #20): общие константы
         * вместо литералов в UI и навигации.
         */
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        /** Значение темы по умолчанию («по системе»). */
        const val DEFAULT_THEME = THEME_SYSTEM

        /** Значение настройки «не выгружать модели при смене направления» (issue #36). */
        const val RETENTION_KEEP = "keep"

        /** Значение настройки «выгружать модели при смене направления» (issue #36). */
        const val RETENTION_UNLOAD = "unload"

        /** Значение по умолчанию для настройки удержания моделей (issue #36). */
        const val DEFAULT_MODEL_DIRECTION_RETENTION = RETENTION_KEEP

        /** Preference поведения движков при смене направления перевода (issue #36). */
        private val MODEL_DIRECTION_RETENTION_KEY =
            stringPreferencesKey("model_direction_retention")

        /** Preference с JSON-картой «язык → id STT-модели». */
        private val STT_BY_LANGUAGE_KEY = stringPreferencesKey("stt_by_language")

        /** Имя STT-ключа для логов. */
        private const val STT_BY_LANGUAGE_NAME = "stt_by_language"

        /** Preference с JSON-картой «язык → id Piper TTS-модели» (issue #50). */
        private val TTS_BY_LANGUAGE_KEY = stringPreferencesKey("tts_by_language")

        /** Имя TTS-ключа для логов. */
        private const val TTS_BY_LANGUAGE_NAME = "tts_by_language"

        /** Сериализатор карты языков для языковых preference-ключей. */
        private val LANGUAGE_MAP_SERIALIZER = MapSerializer(String.serializer(), String.serializer())

        /** Preference кода исходного языка последнего направления (issue #33). */
        private val LAST_SOURCE_LANG_KEY = stringPreferencesKey("last_source_lang")

        /** Preference кода целевого языка последнего направления (issue #33). */
        private val LAST_TARGET_LANG_KEY = stringPreferencesKey("last_target_lang")

        /** Preference флага пройденного онбординга (issue #33). */
        private val ONBOARDING_DONE_KEY = booleanPreferencesKey("onboarding_done")

        /** Preference выбранного TTS-движка. */
        private val TTS_ENGINE_KEY = stringPreferencesKey("tts_engine")

        /** Preference языка интерфейса. */
        private val UI_LANGUAGE_KEY = stringPreferencesKey("ui_language")

        /** Preference темы интерфейса. */
        private val THEME_KEY = stringPreferencesKey("theme")

        /** Preference тайм-аута отложенной выгрузки рантаймов, минуты. */
        private val UNLOAD_TIMEOUT_MINUTES_KEY = intPreferencesKey("unload_timeout_minutes")

        /** Preference версии применённого удалённого каталога. */
        private val REMOTE_CATALOG_VERSION_KEY = intPreferencesKey("remote_catalog_version")
    }
}
