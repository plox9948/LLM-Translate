package com.plox.llmtranslate.data.model

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * База данных приложения.
 */
@Database(
    entities = [ModelEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao

    companion object {
        /**
         * Миграция 1 → 2: добавление колонки urls (мультифайловые модели).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE models ADD COLUMN urls TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
