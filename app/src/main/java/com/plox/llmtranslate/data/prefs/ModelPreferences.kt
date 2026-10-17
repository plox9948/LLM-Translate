package com.plox.llmtranslate.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.plox.llmtranslate.domain.model.ModelCategory
import com.plox.llmtranslate.domain.model.TtsEngines
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "model_prefs")

/**
 * Настройки выбора моделей и приложения (DataStore).
 */
@Singleton
class ModelPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) {

    private val activeModelKeys = mapOf(
        ModelCategory.TRANSLATION to stringPreferencesKey("active_translation"),
        ModelCategory.STT to stringPreferencesKey("active_stt"),
        ModelCategory.TTS to stringPreferencesKey("active_tts")
    )

    val activeModelId: (ModelCategory) -> Flow<String?> = { category ->
        val key = activeModelKeys[category]!!
        context.dataStore.data.map { prefs -> prefs[key] }
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
    val sttByLanguage: Flow<Map<String, String>> =
        context.dataStore.data.map { prefs -> decodeSttByLanguage(prefs[STT_BY_LANGUAGE_KEY]) }

    /**
     * Назначить STT-модель языку; null — снять назначение.
     */
    suspend fun setSttForLanguage(language: String, modelId: String?) {
        context.dataStore.edit { prefs ->
            val map = decodeSttByLanguage(prefs[STT_BY_LANGUAGE_KEY]).toMutableMap()
            if (modelId == null) map.remove(language) else map[language] = modelId
            if (map.isEmpty()) {
                prefs.remove(STT_BY_LANGUAGE_KEY)
            } else {
                prefs[STT_BY_LANGUAGE_KEY] = json.encodeToString(STT_MAP_SERIALIZER, map)
            }
        }
    }

    private fun decodeSttByLanguage(raw: String?): Map<String, String> =
        if (raw.isNullOrBlank()) emptyMap() else try {
            json.decodeFromString(STT_MAP_SERIALIZER, raw)
        } catch (e: Exception) {
            Timber.w(e, "Повреждённое значение stt_by_language, сброшено")
            clearCorruptedSttByLanguage()
            emptyMap()
        }

    /**
     * Удалить повреждённый ключ из DataStore: без этого битое значение
     * остаётся в хранилище и каждая следующая его запись пересобирается
     * из пусто-карты вместо явного сброса.
     */
    private fun clearCorruptedSttByLanguage() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                context.dataStore.edit { prefs -> prefs.remove(STT_BY_LANGUAGE_KEY) }
            } catch (e: Exception) {
                Timber.w(e, "Не удалось удалить повреждённый ключ stt_by_language")
            }
        }
    }

    // --- Последнее направление перевода (issue #33) ---

    /**
     * Сохранённые коды языков последнего направления перевода ("ru"/"en"/"ne");
     * null — направление ещё не выбиралось (AUTO не сохраняется).
     */
    val lastSourceLang: Flow<String?> =
        context.dataStore.data.map { it[LAST_SOURCE_LANG_KEY] }

    val lastTargetLang: Flow<String?> =
        context.dataStore.data.map { it[LAST_TARGET_LANG_KEY] }

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
     */
    val onboardingDone: Flow<Boolean> =
        context.dataStore.data.map { it[ONBOARDING_DONE_KEY] ?: false }

    /**
     * Отметить онбординг пройденным (больше не показывать).
     */
    suspend fun setOnboardingDone() {
        context.dataStore.edit { prefs -> prefs[ONBOARDING_DONE_KEY] = true }
    }

    // --- Настройки приложения ---

    val ttsEngine: Flow<String> =
        context.dataStore.data.map { it[TTS_ENGINE_KEY] ?: TtsEngines.SYSTEM }

    suspend fun setTtsEngine(engine: String) {
        context.dataStore.edit { it[TTS_ENGINE_KEY] = engine }
    }

    val uiLanguage: Flow<String> =
        context.dataStore.data.map { it[UI_LANGUAGE_KEY] ?: "system" }

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
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val value = uiLanguage.first()
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
    suspend fun awaitUiLanguageCache(): String = uiLanguageCacheRef.get() ?: uiLanguage.first()

    suspend fun setUiLanguage(lang: String) {
        uiLanguageCacheRef.set(lang)
        context.dataStore.edit { it[UI_LANGUAGE_KEY] = lang }
    }

    val theme: Flow<String> =
        context.dataStore.data.map { it[THEME_KEY] ?: "system" }

    suspend fun setTheme(theme: String) {
        context.dataStore.edit { it[THEME_KEY] = theme }
    }

    /**
     * Тайм-аут отложенной выгрузки рантаймов при сворачивании приложения, минуты:
     * 0 — выгружать сразу, -1 — не выгружать. По умолчанию 5.
     */
    val unloadTimeoutMinutes: Flow<Int> =
        context.dataStore.data.map {
            it[UNLOAD_TIMEOUT_MINUTES_KEY] ?: DEFAULT_UNLOAD_TIMEOUT_MINUTES
        }

    suspend fun setUnloadTimeoutMinutes(minutes: Int) {
        context.dataStore.edit { it[UNLOAD_TIMEOUT_MINUTES_KEY] = minutes }
    }

    /**
     * Поведение движков при смене направления перевода (issue #36):
     * [RETENTION_KEEP] — не выгружать модели другого направления по умолчанию,
     * [RETENTION_UNLOAD] — выгружать перед загрузкой новой модели.
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

        /** Сериализатор карты языков для [STT_BY_LANGUAGE_KEY]. */
        private val STT_MAP_SERIALIZER = MapSerializer(String.serializer(), String.serializer())

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
