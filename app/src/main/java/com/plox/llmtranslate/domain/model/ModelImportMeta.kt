package com.plox.llmtranslate.domain.model

import kotlinx.serialization.Serializable

/**
 * Общие дефолты параметров угаданных импортируемых моделей (ревью):
 * единый источник для [ModelImportMeta] (значения по умолчанию JSON) и
 * ModelManagerImpl.guessedImportedModel — прежние литералы дублировались
 * в двух местах и могли разойтись.
 */
object ImportedModelDefaults {
    /** Нейтральное качество (середина шкалы). */
    const val QUALITY = 3

    /** Нейтральная скорость (середина шкалы). */
    const val SPEED = 3

    /** Минимальный Android API: нижняя граница поддержки приложения. */
    const val MIN_ANDROID_SDK = 26

    /** Маркер неизвестной лицензии импортируемой модели. */
    const val LICENSE = "Неизвестна"
}

/**
 * Метаданные модели, вкладываемые в ZIP-экспорт мультифайловой модели
 * (запись `model_meta.json`). При повторном импорте этого ZIP позволяют
 * зарегистрировать модель под её исходным каталожным id и структурой,
 * чтобы карточка каталога отобразилась установленной.
 */
@Serializable
data class ModelImportMeta(
    val id: String,
    val category: String,
    val runtime: String,
    val displayName: String,
    val description: String = "",
    val languages: List<String> = emptyList(),
    val sizeBytes: Long = 0,
    val ramRequiredMb: Int = 0,
    val quality: Int = ImportedModelDefaults.QUALITY,
    val speed: Int = ImportedModelDefaults.SPEED,
    val url: String = "",
    val urls: List<String> = emptyList(),
    /**
     * Контрольная сумма одиночного url (ревью): round-trip собственного
     * экспорта модели с одиночным URL терял sha256 (сверка пропускалась).
     * null — не задана; старые `model_meta.json` без поля читаются как null
     * (обратная совместимость).
     */
    val sha256: String? = null,
    val sha256s: List<String?> = emptyList(),
    val license: String = ImportedModelDefaults.LICENSE,
    val minAndroidSdk: Int = ImportedModelDefaults.MIN_ANDROID_SDK,
    val recommendedRamMb: Int = 0,
    val isBuiltin: Boolean = false
)
