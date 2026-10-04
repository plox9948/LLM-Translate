package com.plox.llmtranslate.data.model

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * База данных приложения.
 */
@Database(
    entities = [ModelEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao
}
