package com.plox.llmtranslate.data.model

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * База данных приложения.
 */
@Database(
    entities = [ModelEntity::class, TranslationHistoryEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao
    abstract fun translationHistoryDao(): TranslationHistoryDao

    companion object {
        /**
         * Миграция 1 → 2: добавление колонки urls (мультифайловые модели).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE models ADD COLUMN urls TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Миграция 2 → 3: таблица истории переводов.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `translation_history` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`source_lang` TEXT NOT NULL, " +
                        "`target_lang` TEXT NOT NULL, " +
                        "`input_text` TEXT NOT NULL, " +
                        "`result_text` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_history_created_at` " +
                        "ON `translation_history` (`created_at`)"
                )
            }
        }
    }
}
