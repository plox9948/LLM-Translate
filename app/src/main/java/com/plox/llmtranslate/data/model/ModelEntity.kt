package com.plox.llmtranslate.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Сущность Room: установленная/скачиваемая модель.
 */
@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "category") val category: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "description") val description: String,
    @ColumnInfo(name = "runtime") val runtime: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "ram_required_mb") val ramRequiredMb: Int,
    @ColumnInfo(name = "languages") val languages: String,
    @ColumnInfo(name = "quality") val quality: Int,
    @ColumnInfo(name = "speed") val speed: Int,
    @ColumnInfo(name = "url") val url: String,
    // defaultValue совпадает с DEFAULT '' в MIGRATION_1_2, иначе identity hash
    // схемы не сойдётся при апгрейде с версии 1.
    @ColumnInfo(name = "urls", defaultValue = "")
    val urls: String,
    @ColumnInfo(name = "sha256") val sha256: String?,
    @ColumnInfo(name = "license") val license: String,
    @ColumnInfo(name = "min_android_sdk") val minAndroidSdk: Int,
    @ColumnInfo(name = "recommended_ram_mb") val recommendedRamMb: Int,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "progress") val progress: Int,
    @ColumnInfo(name = "file_path") val filePath: String?,
    @ColumnInfo(name = "bytes_done") val bytesDone: Long,
    @ColumnInfo(name = "is_builtin") val isBuiltin: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
