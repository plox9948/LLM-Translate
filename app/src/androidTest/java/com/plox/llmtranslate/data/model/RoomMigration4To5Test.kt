package com.plox.llmtranslate.data.model

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumentation-тест миграции Room 4 → 5 ([AppDatabase.MIGRATION_4_5] —
 * таблица favorites + уникальный индекс idx_favorites_unique).
 *
 * Как в RoomMigration3To4Test (room-testing в проекте нет):
 * 1) схема версии 4 создаётся вручную через SupportSQLiteOpenHelper
 *    (models с sha256s как после MIGRATION_3_4 + translation_history),
 *    таблицы favorites нет;
 * 2) миграция вызывается напрямую ([AppDatabase.MIGRATION_4_5].migrate) и
 *    отдельно сквозным прогоном через Room.databaseBuilder (Room сам выполнит
 *    миграцию при открытии v4-базы и валидирует результат);
 * 3) проверяются: создание таблицы favorites, уникальный индекс по
 *    (source_lang, target_lang, input_text), отказ дубликата вставки,
 *    работа FavoritesDao на мигрированной таблице.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigration4To5Test {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Колбэк пустой базы фиксированной версии — схема создаётся вручную. */
    private class LegacySchemaCallback(version: Int) : SupportSQLiteOpenHelper.Callback(version) {
        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
        override fun onUpgrade(
            db: androidx.sqlite.db.SupportSQLiteDatabase,
            oldVersion: Int,
            newVersion: Int
        ) = Unit
        override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
    }

    /** Открыть пустую базу фиксированной версии по абсолютному пути. */
    private fun openHelperAt(file: File, version: Int): SupportSQLiteOpenHelper {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.absolutePath)
            .callback(LegacySchemaCallback(version))
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration)
    }

    /**
     * Схема версии 4: models с sha256s (как после MIGRATION_3_4) +
     * translation_history с индексом; favorites отсутствует.
     */
    private fun createV4Schema(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `models` (" +
                "`id` TEXT NOT NULL, " +
                "`category` TEXT NOT NULL, " +
                "`display_name` TEXT NOT NULL, " +
                "`description` TEXT NOT NULL, " +
                "`runtime` TEXT NOT NULL, " +
                "`size_bytes` INTEGER NOT NULL, " +
                "`ram_required_mb` INTEGER NOT NULL, " +
                "`languages` TEXT NOT NULL, " +
                "`quality` INTEGER NOT NULL, " +
                "`speed` INTEGER NOT NULL, " +
                "`url` TEXT NOT NULL, " +
                "`urls` TEXT NOT NULL DEFAULT '', " +
                "`sha256` TEXT, " +
                "`license` TEXT NOT NULL, " +
                "`min_android_sdk` INTEGER NOT NULL, " +
                "`recommended_ram_mb` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`progress` INTEGER NOT NULL, " +
                "`file_path` TEXT, " +
                "`bytes_done` INTEGER NOT NULL, " +
                "`is_builtin` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, " +
                "`sha256s` TEXT NOT NULL DEFAULT '', " +
                "PRIMARY KEY(`id`))"
        )
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

    private fun deleteDbFiles(file: File) {
        file.delete()
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
    }

    private fun columnNames(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Set<String> {
        val names = mutableSetOf<String>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                names.add(cursor.getString(nameIndex))
            }
        }
        return names
    }

    /** Индексы таблицы (имя → "UNIQUE" ли он). */
    private fun indexInfo(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String
    ): Map<String, Boolean> {
        val info = mutableMapOf<String, Boolean>()
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val uniqueIndex = cursor.getColumnIndex("unique")
            while (cursor.moveToNext()) {
                info[cursor.getString(nameIndex)] = cursor.getInt(uniqueIndex) == 1
            }
        }
        return info
    }

    private fun insertFavorite(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        sourceLang: String,
        targetLang: String,
        inputText: String
    ) {
        db.execSQL(
            "INSERT INTO favorites (source_lang, target_lang, input_text, result_text, created_at) " +
                "VALUES (?, ?, ?, 'res', 1700000000)",
            arrayOf(sourceLang, targetLang, inputText)
        )
    }

    @After
    fun tearDown() {
        context.deleteDatabase("v4_direct_migration.db")
        context.deleteDatabase("v4_room_migration.db")
    }

    @Test
    fun directMigrationCreatesFavoritesTableAndUniqueIndex() {
        val file = File(context.getDatabasePath("v4_direct_migration.db").absolutePath)
        deleteDbFiles(file)
        val helper = openHelperAt(file, 4)
        val db = helper.writableDatabase
        createV4Schema(db)

        // До миграции таблицы favorites нет.
        assertTrue(
            "favorites появилась до миграции",
            columnNames(db, "favorites").isEmpty()
        )

        // Собственно миграция 4 → 5 из AppDatabase.
        AppDatabase.MIGRATION_4_5.migrate(db)

        // Таблица favorites со всеми колонками сущности.
        assertEquals(
            setOf("id", "source_lang", "target_lang", "input_text", "result_text", "created_at"),
            columnNames(db, "favorites")
        )

        // Уникальный индекс по (source_lang, target_lang, input_text).
        val indexes = indexInfo(db, "favorites")
        assertTrue(
            "idx_favorites_unique не создан: $indexes",
            indexes["idx_favorites_unique"] == true
        )

        // Дубликат по ключу индекса отклоняется.
        insertFavorite(db, "ru", "en", "privet")
        val duplicateRejected = try {
            insertFavorite(db, "ru", "en", "privet")
            false
        } catch (e: Exception) {
            true
        }
        assertTrue("Дубликат избранного не отклонён уникальным индексом", duplicateRejected)

        // Другое направление/текст — не дубликат.
        insertFavorite(db, "en", "ru", "privet")
        insertFavorite(db, "ru", "en", "poka")
        db.query("SELECT COUNT(*) FROM favorites").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(3, cursor.getInt(0))
        }

        helper.close()
        deleteDbFiles(file)
    }

    @Test
    fun roomEndToEndUpgradeFromV4FileRunsMigrationAndDaoWorks() {
        val dbName = "v4_room_migration.db"
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        deleteDbFiles(file)

        // Готовим файл версии 4 (user_version=4 через колбэк); схема без favorites.
        val helper = openHelperAt(file, 4)
        createV4Schema(helper.writableDatabase)
        helper.close()

        // Room открывает v4-базу и сам прогоняет миграцию до v5; при расхождении
        // схемы миграции со схемой сущностей build()/открытие упадёт.
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_4_5)
            .build()
        try {
            // Версия БД после апгрейда — 5.
            db.openHelper.readableDatabase.query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(5, cursor.getInt(0))
            }

            runBlocking {
                // FavoritesDao работает на мигрированной таблице.
                db.favoritesDao().insert(
                    FavoriteEntity(
                        sourceLang = "ru",
                        targetLang = "en",
                        inputText = "привет",
                        resultText = "hello",
                        createdAt = 1700000000L
                    )
                )
                // Повторное переключение той же пары удаляет запись (toggle → false),
                // CHECK-then-act внутри транзакции не ловит UNIQUE-конфликт.
                val added = db.favoritesDao().toggleEntry(
                    "ru", "en", "привет",
                    FavoriteEntity(
                        sourceLang = "ru",
                        targetLang = "en",
                        inputText = "привет",
                        resultText = "hello",
                        createdAt = 1700000001L
                    )
                )
                assertEquals(false, added)
                assertEquals(0, db.favoritesDao().deleteMatching("ru", "en", "привет"))
            }
        } finally {
            db.close()
        }
    }
}
