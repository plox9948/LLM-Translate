package com.plox.llmtranslate.data.backup

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
 * Nullable-поля (ревью #53) различают «в старом бэкапе нет» (null — при
 * восстановлении настройку не трогаем) и «значение задано» (применять).
 * Обратная совместимость: добавляемые поля опциональны со значением по
 * умолчанию null; старые архивы содержат поля явно и восстанавливаются
 * как раньше. Обратное — НЕ версионируемое поле с НЕ-null дефолтом
 * («system», TtsEngines.SYSTEM) молча затирало бы реальные настройки при
 * импорте старого архива без секции, поэтому скалярные настройки тоже
 * nullable: null = «ключa нет, не трогать».
 */
@Serializable
data class BackupSettings(
    val activeTranslation: String? = null,
    val activeStt: String? = null,
    val activeTts: String? = null,
    /** TTS-движок ([com.plox.llmtranslate.domain.model.TtsEngines]); null — не восстанавливать. */
    val ttsEngine: String? = null,
    /** Язык UI ("system"/"ru"/"en"/...); null — не восстанавливать. */
    val uiLanguage: String? = null,
    /** Тема ("system"/"light"/"dark"); null — не восстанавливать. */
    val theme: String? = null,
    /**
     * Карта «язык → id STT-модели»; null — в архиве нет, не восстанавливать;
     * null-значение элемента — явно снять назначение языка (декод
     * `"язык": null`; снятие возможно только для явно присутствующего языка).
     */
    val sttByLanguage: Map<String, String?>? = null,
    /**
     * Карта «язык → id Piper TTS-модели либо сентинель "system"»
     * ([com.plox.llmtranslate.domain.model.TtsModelSelector.SYSTEM_SENTINEL],
     * issue #53); null — в архиве нет, не восстанавливать; null-значение
     * элемента — снять назначение языка.
     */
    val ttsByLanguage: Map<String, String?>? = null,
    /** Тайм-аут выгрузки рантаймов, минуты; null — не восстанавливать. */
    val unloadTimeoutMinutes: Int? = null,
    /** keep/unload — поведение движков при смене направления (issue #36). */
    val modelDirectionRetention: String? = null,
    /** Направление последнего перевода (issue #33); пара применяется целиком. */
    val lastSourceLang: String? = null,
    val lastTargetLang: String? = null
)
