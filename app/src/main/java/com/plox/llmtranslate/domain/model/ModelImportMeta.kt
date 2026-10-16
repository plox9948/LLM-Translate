package com.plox.llmtranslate.domain.model

import kotlinx.serialization.Serializable

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
    val quality: Int = 3,
    val speed: Int = 3,
    val url: String = "",
    val urls: List<String> = emptyList(),
    val sha256s: List<String?> = emptyList(),
    val license: String = "Неизвестна",
    val minAndroidSdk: Int = 26,
    val recommendedRamMb: Int = 0,
    val isBuiltin: Boolean = false
)
