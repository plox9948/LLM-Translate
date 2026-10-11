package com.plox.llmtranslate.data.backup

import com.plox.llmtranslate.domain.model.TtsEngines
import kotlinx.serialization.Serializable

/**
 * Запись модели в манифесте резервной копии.
 */
@Serializable
data class BackupModelEntry(
    val id: String,
    val category: String,
    val displayName: String,
    val description: String,
    val runtime: String,
    val sizeBytes: Long,
    val ramRequiredMb: Int,
    val languages: List<String>,
    val quality: Int,
    val speed: Int,
    val url: String,
    val urls: List<String>,
    val sha256: String?,
    val license: String,
    val minAndroidSdk: Int,
    val recommendedRamMb: Int,
    val relativePath: String,
    val isBuiltin: Boolean
)

/**
 * Манифест резервной копии.
 */
@Serializable
data class BackupManifest(
    val version: Int = 1,
    val exportedAt: Long = 0,
    val models: List<BackupModelEntry> = emptyList()
)

/**
 * Настройки приложения для резервной копии.
 */
@Serializable
data class BackupSettings(
    val activeTranslation: String? = null,
    val activeStt: String? = null,
    val activeTts: String? = null,
    val ttsEngine: String = TtsEngines.SYSTEM,
    val uiLanguage: String = "system",
    val theme: String = "system"
)
