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
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.TranslationModelSelector
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
 *
 * Issue #83, п.3: [dataStoreInstance] помечен [Volatile] — double-checked
 * locking без гарантии видимости иначе позволял двум потокам создать по
 * инстансу DataStore (два писателя одного файла).
 */
private val dataStoreLock = Any()

@Volatile
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
 * публичные потоки [ModelPreferences] — кроме перечисленных ниже raw-
 * исключений — построены через этот хелпер, поэтому
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
 * Единственное публичное raw-исключение без обёртки — [modelDirectionRetention]:
 * он читается ОДНИМ `first()` с консервативной обработкой сбоя у самого
 * вызывающего (провайдер удержания DomainModule: при ошибке — unload, а не
 * дефолт keep; экспорт бэкапа: сбой чтения обязан быть ошибкой экспорта, а не
 * дефолтом в архиве). Подписка UI идёт через `observeResilient` (SettingsScreen).
 * Сырые `raw*`-потоки (internal) читаются экспортом бэкапа — сбой чтения обязан
 * быть ошибкой экспорта, а не дефолтом в архиве; сырое чтение `uiLanguage` для
 * prefetch/await, где фолбэк нельзя прогревать в кэш как настоящее значение.
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

/** Сериализатор JSON-карты «язык → id» для языковых preference-ключей. */
internal val LANGUAGE_MAP_SERIALIZER = MapSerializer(String.serializer(), String.serializer())

/**
 * Исход чистого декода JSON-карты «язык -> id»: карта + флаг, что исходная
 * строка повреждена (issue #83, п.2). Декод БЕЗ побочных эффектов — никакой
 * фоновой правки DataStore из decode (см. [decodeLanguageMapValue]).
 */
internal class LanguageMapDecode(
    val map: Map<String, String>,
    val corrupted: Boolean
)

/**
 * Результат правки языковой карты ВНУТРИ одной `edit`-транзакции
 * (issue #83, п.2): чем заменить/снять ключ.
 */
internal sealed interface LanguageMapEdit {
    /** Перезаписать ключ значением [raw] (включая перезапись повреждённого). */
    data class SetValue(val raw: String) : LanguageMapEdit

    /** Удалить ключ (пустая карта после операции либо починка битого значения). */
    object RemoveKey : LanguageMapEdit

    /** Ничего не менять (изменений не было — не перетирать значение). */
    object Unchanged : LanguageMapEdit
}

/**
 * Чистый декод JSON-карты «язык → id» (STT/TTS) без обращения к DataStore
 * (issue #83, п.2). Повреждённое значение — ПУСТАЯ карта с флагом
 * [LanguageMapDecode.corrupted]; сам ключ чинит вызывающий ВНУТРИ той же
 * `edit`-транзакции, в которой читает значение. Раньше Decode ловил исключение
 * и запускал fire-and-forget `scope.launch { edit { remove(key) } }`: фоновый
 * edit применялся ПОСЛЕ коммита текущей записи и удалял только что
 * записанное назначение пользователя (lost update), а из-под `collect`
 * сырых потоков edit вызывался вообще рекурсивно.
 */
internal fun decodeLanguageMapValue(
    json: Json,
    keyName: String,
    raw: String?
): LanguageMapDecode =
    if (raw.isNullOrBlank()) LanguageMapDecode(emptyMap(), false)
    else try {
        LanguageMapDecode(json.decodeFromString(LANGUAGE_MAP_SERIALIZER, raw), false)
    } catch (e: Exception) {
        Timber.w(e, "Повреждённое значение $keyName — карта считается пустой, ключ чинится в той же транзакции записи")
        LanguageMapDecode(emptyMap(), true)
    }

/**
 * Новое СЫРОЕ значение языкового ключа при назначении языка (issue #83, п.2):
 * повреждённое прежнее значение НЕ сливается с новым — карта стартует пусто и
 * перезаписывается целиком в той же транзакции, поэтому назначение не может
 * «потеряться» из-за отдельной чистки ключа.
 * @return null — ключ удалить (карта опустела).
 */
internal fun languageMapSetRaw(
    json: Json,
    keyName: String,
    raw: String?,
    language: String,
    modelId: String?
): String? {
    val decode = decodeLanguageMapValue(json, keyName, raw)
    val updated = decode.map.toMutableMap()
    if (modelId == null) updated.remove(language) else updated[language] = modelId
    return if (updated.isEmpty()) null
    else json.encodeToString(LANGUAGE_MAP_SERIALIZER, updated)
}

/**
 * Правка языкового ключа при снятии всех назначений «язык -> [modelId]»
 * (issue #83, п.2): [LanguageMapEdit.Unchanged] — если id не встречался в
 * intact-карте (перезапись не нужна); повреждённое значение чинится сразу —
 * [LanguageMapEdit.RemoveKey].
 */
internal fun languageMapStripEdit(
    json: Json,
    keyName: String,
    raw: String?,
    modelId: String
): LanguageMapEdit {
    val decode = decodeLanguageMapValue(json, keyName, raw)
    if (decode.corrupted) return LanguageMapEdit.RemoveKey
    val removed = decode.map.filterValues { it == modelId }.keys
    if (removed.isEmpty()) return LanguageMapEdit.Unchanged
    val updated = decode.map.toMutableMap()
    removed.forEach { updated.remove(it) }
    return if (updated.isEmpty()) LanguageMapEdit.RemoveKey
    else LanguageMapEdit.SetValue(json.encodeToString(LANGUAGE_MAP_SERIALIZER, updated))
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
        val key = requireNotNull(activeModelKeys[category]) {
            "Нет preference-ключа активной модели для нового ModelCategory $category"
        }
        flowWithRecovery("active_model_${category.name}") {
            context.dataStore.data.map { prefs -> prefs[key] }
        }
    }

    suspend fun setActiveModelId(category: ModelCategory, modelId: String?) {
        val key = requireNotNull(activeModelKeys[category]) {
            "Нет preference-ключа активной модели для нового ModelCategory $category"
        }
        context.dataStore.edit { prefs ->
            if (modelId == null) prefs.remove(key) else prefs[key] = modelId
        }
    }

    suspend fun getActiveModelId(category: ModelCategory): String? =
        context.dataStore.data.first()[
            requireNotNull(activeModelKeys[category]) {
                "Нет preference-ключа активной модели для нового ModelCategory $category"
            }
        ]

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
     * Декод битой карты — чистый [decodeLanguageMapValue], без edit-побочек
     * из-под collect (issue #83, п.2).
     */
    internal val rawSttByLanguage: Flow<Map<String, String>> =
        context.dataStore.data.map { prefs ->
            decodeLanguageMapValue(json, STT_BY_LANGUAGE_NAME, prefs[STT_BY_LANGUAGE_KEY]).map
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
        context.dataStore.data.map { prefs ->
            decodeLanguageMapValue(json, TTS_BY_LANGUAGE_NAME, prefs[TTS_BY_LANGUAGE_KEY]).map
        }

    val ttsByLanguage: Flow<Map<String, String>> =
        flowWithRecovery(TTS_BY_LANGUAGE_NAME, emptyMap()) { rawTtsByLanguage }

    /**
     * Назначить языку Piper TTS-модель по id или Системный Android TTS
     * ([com.plox.llmtranslate.domain.model.TtsModelSelector.SYSTEM_SENTINEL]);
     * null — снять назначение.
     */
    suspend fun setTtsForLanguage(language: String, modelId: String?) =
        setForLanguage(TTS_BY_LANGUAGE_KEY, TTS_BY_LANGUAGE_NAME, language, modelId)

    // --- Модель перевода на каждое направление (issue #96) ---

    /**
     * Карта «направление → id модели перевода» (issue #96): ключ —
     * "исходный>целевой" (например "ru>en", формат —
     * [TranslationModelSelector.directionKey]), значение — id модели.
     * Явное назначение применяется поверх автоподбора по направлению.
     * Сериализация значения — JSON-строка в preference
     * `translation_by_direction`.
     */
    val translationByDirection: Flow<Map<String, String>> =
        flowWithRecovery(TRANSLATION_BY_DIRECTION_NAME, emptyMap()) {
            context.dataStore.data.map { prefs ->
                decodeLanguageMapValue(
                    json, TRANSLATION_BY_DIRECTION_NAME, prefs[TRANSLATION_BY_DIRECTION_KEY]
                ).map
            }
        }

    /**
     * Назначить модель перевода направлению source→target; null — снять
     * назначение.
     */
    suspend fun setTranslationForDirection(
        sourceLang: String,
        targetLang: String,
        modelId: String?
    ) = setForLanguage(
        TRANSLATION_BY_DIRECTION_KEY, TRANSLATION_BY_DIRECTION_NAME,
        TranslationModelSelector.directionKey(sourceLang, targetLang), modelId
    )

    /**
     * Общая запись языковой карты (STT/TTS/перевод по направлениям): снять назначение при null,
     * удалить ключ при опустевшей карте. Issue #83, п.2: вся логика —
     * [languageMapSetRaw] в ОДНОЙ edit-транзакции; битый прежний JSON
     * перезаписывается результатом этой же транзакции, фоновый remove-редакт
     * больше не затирает только что записанное назначение.
     */
    private suspend fun setForLanguage(
        key: Preferences.Key<String>,
        keyName: String,
        language: String,
        modelId: String?
    ) {
        context.dataStore.edit { prefs ->
            val updated = languageMapSetRaw(json, keyName, prefs[key], language, modelId)
            if (updated == null) prefs.remove(key) else prefs[key] = updated
        }
    }

    /**
     * Снять все назначения «язык → модель» с languages-картами, ссылающиеся
     * на удалённую модель [modelId] (ревью: delete() не должен оставлять в
     * настройках висячие id). Ключ удаляется при опустевшей карте; повреждённое
     * значение снимается той же транзакцией (issue #83, п.2 — раньше починка
     * шла отдельным фоновым edit из-под decode и конфликтовала с записями).
     */
    suspend fun removeModelFromLanguageAssignments(modelId: String) {
        context.dataStore.edit { prefs ->
            applyLanguageMapEdit(
                prefs, STT_BY_LANGUAGE_KEY,
                languageMapStripEdit(json, STT_BY_LANGUAGE_NAME, prefs[STT_BY_LANGUAGE_KEY], modelId)
            )
            applyLanguageMapEdit(
                prefs, TTS_BY_LANGUAGE_KEY,
                languageMapStripEdit(json, TTS_BY_LANGUAGE_NAME, prefs[TTS_BY_LANGUAGE_KEY], modelId)
            )
            applyLanguageMapEdit(
                prefs, TRANSLATION_BY_DIRECTION_KEY,
                languageMapStripEdit(
                    json, TRANSLATION_BY_DIRECTION_NAME,
                    prefs[TRANSLATION_BY_DIRECTION_KEY], modelId
                )
            )
        }
    }

    /** Применить результат правки языковой карты к preferences транзакции. */
    private fun applyLanguageMapEdit(
        prefs: MutablePreferences,
        key: Preferences.Key<String>,
        edit: LanguageMapEdit
    ) {
        when (edit) {
            LanguageMapEdit.RemoveKey -> prefs.remove(key)
            is LanguageMapEdit.SetValue -> prefs[key] = edit.raw
            LanguageMapEdit.Unchanged -> Unit
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
     * Единственное осознанное публичное raw-исключение из [flowWithRecovery]
     * (ревью обещания «все потоки через хелпер»): единственный подписчик UI —
     * `watch` в SettingsScreen поверх `observeResilient`, а одноразовые `first()`
     * обязаны получать СБОЙ чтения, а не дефолт: провайдер DomainModule отвечает
     * conservative unload, а экспорт бэкапа — ошибкой экспорта, а не дефолтом
     * «keep» в архиве.
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

    /**
     * Версия применённого удалённого каталога (ревью: публичный поток без
     * recover-обёртки противоречил контракту хелпера — обёрнут). Режим без
     * константного фолбэка: холодный сбой чтения НЕ эмитит ложный 0 — иначе
     * очистка устаревших моделей (OfflineTranslatorApp) сочла бы версию
     * неприменённой и удалила READY-модели удалённого каталога; тёплый сбой —
     * прежнее полученное значение, затем переподписка.
     */
    val lastRemoteCatalogVersion: Flow<Int> =
        flowWithRecovery(REMOTE_CATALOG_VERSION_NAME) {
            context.dataStore.data.map { it[REMOTE_CATALOG_VERSION_KEY] ?: 0 }
        }

    suspend fun setLastRemoteCatalogVersion(version: Int) {
        context.dataStore.edit { it[REMOTE_CATALOG_VERSION_KEY] = version }
    }

    // --- Самообновление приложения (issue #95) ---

    /**
     * Включены ли автоматические проверки обновлений при запуске.
     * По умолчанию true (пользователь может отключить в настройках).
     */
    val autoUpdateChecksEnabled: Flow<Boolean> =
        flowWithRecovery(AUTO_UPDATE_CHECKS_NAME, true) {
            context.dataStore.data.map { prefs ->
                prefs[AUTO_UPDATE_CHECKS_KEY] ?: true
            }
        }

    suspend fun setAutoUpdateChecksEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[AUTO_UPDATE_CHECKS_KEY] = enabled }
    }

    /**
     * До какого момента отложено напоминание об обновлении
     * («напомнить позже»), epoch millis; 0 — snooze неактивен.
     */
    val updateSnoozeUntil: Flow<Long> =
        flowWithRecovery(UPDATE_SNOOZE_UNTIL_NAME, 0L) {
            context.dataStore.data.map { prefs ->
                prefs[UPDATE_SNOOZE_UNTIL_KEY] ?: 0L
            }
        }

    suspend fun setUpdateSnoozeUntil(millis: Long) {
        context.dataStore.edit { prefs -> prefs[UPDATE_SNOOZE_UNTIL_KEY] = millis }
    }

    /**
     * Версия, для которой пользователь выбрал «больше не показывать»
     * (пустая строка — не скрывается ничего; сравнение с версией
     * доступного релиза выполняет вызывающий).
     */
    val updatePromptSuppressedVersion: Flow<String> =
        flowWithRecovery(UPDATE_PROMPT_SUPPRESSED_VERSION_NAME, "") {
            context.dataStore.data.map { prefs ->
                prefs[UPDATE_PROMPT_SUPPRESSED_VERSION_KEY] ?: ""
            }
        }

    suspend fun setUpdatePromptSuppressedVersion(version: String) {
        context.dataStore.edit { prefs ->
            prefs[UPDATE_PROMPT_SUPPRESSED_VERSION_KEY] = version
        }
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

        /** Preference с JSON-картой «направление → id модели перевода» (issue #96). */
        private val TRANSLATION_BY_DIRECTION_KEY =
            stringPreferencesKey("translation_by_direction")

        /** Имя ключа переводных назначений для логов. */
        private const val TRANSLATION_BY_DIRECTION_NAME = "translation_by_direction"

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

        /** Имя preference версии удалённого каталога для логов. */
        private const val REMOTE_CATALOG_VERSION_NAME = "remote_catalog_version"

        /** Preference флага автопроверок обновлений (issue #95). */
        private val AUTO_UPDATE_CHECKS_KEY = booleanPreferencesKey("auto_update_checks")

        /** Имя ключа автопроверок для логов. */
        private const val AUTO_UPDATE_CHECKS_NAME = "auto_update_checks"

        /** Preference момента snooze напоминания об обновлении, epoch millis (issue #95). */
        private val UPDATE_SNOOZE_UNTIL_KEY = longPreferencesKey("update_snooze_until")

        /** Имя snooze-ключа для логов. */
        private const val UPDATE_SNOOZE_UNTIL_NAME = "update_snooze_until"

        /** Preference версии релиза, скрытой настройкой «больше не показывать» (issue #95). */
        private val UPDATE_PROMPT_SUPPRESSED_VERSION_KEY =
            stringPreferencesKey("update_prompt_suppressed_version")

        /** Имя suppressed-ключа для логов. */
        private const val UPDATE_PROMPT_SUPPRESSED_VERSION_NAME =
            "update_prompt_suppressed_version"
    }
}
