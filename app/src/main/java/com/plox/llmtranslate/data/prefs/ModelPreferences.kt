package com.plox.llmtranslate.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.plox.llmtranslate.domain.model.ModelCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "model_prefs")

/**
 * Настройки выбора моделей и приложения (DataStore).
 */
@Singleton
class ModelPreferences @Inject constructor(
    private val context: Context
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

    // --- Настройки приложения ---

    val ttsEngine: Flow<String> =
        context.dataStore.data.map { it[stringPreferencesKey("tts_engine")] ?: "system" }

    suspend fun setTtsEngine(engine: String) {
        context.dataStore.edit { it[stringPreferencesKey("tts_engine")] = engine }
    }

    val uiLanguage: Flow<String> =
        context.dataStore.data.map { it[stringPreferencesKey("ui_language")] ?: "system" }

    suspend fun setUiLanguage(lang: String) {
        context.dataStore.edit { it[stringPreferencesKey("ui_language")] = lang }
    }

    val theme: Flow<String> =
        context.dataStore.data.map { it[stringPreferencesKey("theme")] ?: "system" }

    suspend fun setTheme(theme: String) {
        context.dataStore.edit { it[stringPreferencesKey("theme")] = theme }
    }

    val lastRemoteCatalogVersion: Flow<Int> =
        context.dataStore.data.map { it[intPreferencesKey("remote_catalog_version")] ?: 0 }

    suspend fun setLastRemoteCatalogVersion(version: Int) {
        context.dataStore.edit { it[intPreferencesKey("remote_catalog_version")] = version }
    }
}
