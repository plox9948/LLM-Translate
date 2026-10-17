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
    val sha256s: List<String?> = emptyList(),
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
 *
 * Nullable-поля карт/чисел (ревью #53) различают «в старом бэкапе нет»
 * (null — при восстановлении настройку не трогаем) и «пустая карта»
 * (применять — снять все поязыковые назначения). Обратная совместимость:
 * добавляемые поля опциональны со значением по умолчанию null.
 */
@Serializable
data class BackupSettings(
    val activeTranslation: String? = null,
    val activeStt: String? = null,
    val activeTts: String? = null,
    val ttsEngine: String = TtsEngines.SYSTEM,
    val uiLanguage: String = "system",
    val theme: String = "system",
    /** Карта «язык → id STT-модели»; null — в архиве нет, не восстанавливать. */
    val sttByLanguage: Map<String, String>? = null,
    /**
     * Карта «язык → id Piper TTS-модели либо сентинель "system"»
     * ([com.plox.llmtranslate.domain.model.TtsModelSelector.SYSTEM_SENTINEL],
     * issue #53); null — в архиве нет, не восстанавливать.
     */
    val ttsByLanguage: Map<String, String>? = null,
    /** Тайм-аут выгрузки рантаймов, минуты; null — не восстанавливать. */
    val unloadTimeoutMinutes: Int? = null,
    /** keep/unload — поведение движков при смене направления (issue #36). */
    val modelDirectionRetention: String? = null,
    /** Направление последнего перевода (issue #33); пара применяется целиком. */
    val lastSourceLang: String? = null,
    val lastTargetLang: String? = null
)
