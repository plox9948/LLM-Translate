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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumentation-тест миграции Room 3 → 4 ([AppDatabase.MIGRATION_3_4] —
 * колонка sha256s TEXT NOT NULL DEFAULT '').
 *
 * androidx.room:room-testing в проекте нет (новая зависимость без разрешения
 * запрещена AGENTS.md), поэтому тест идёт рабочим минимальным путём:
 * 1) схема версии 3 создаётся вручную через SupportSQLiteDatabase
 *    (ровно то, что оставили миграции 1→2 и 2→3), в таблицу вставляется
 *    «старая» строка модели без колонки sha256s;
 * 2) миграция вызывается напрямую ([AppDatabase.MIGRATION_3_4].migrate) и
 *    отдельно сквозным прогоном через Room.databaseBuilder (Room сам
 *    выполняет миграцию при открытии v3-базы и валидирует результат);
 * 3) проверяются: сохранность данных старой строки, sha256s='' у неё,
 *    DEFAULT '' при вставке без колонки и работа DAO на мигрированной таблице.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigration3To4Test {

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
     * Схема версии 3: models без sha256s (urls с DEFAULT '' — как в
     * MIGRATION_1_2) + translation_history + индекс (как в MIGRATION_2_3).
     */
    private fun createV3Schema(db: androidx.sqlite.db.SupportSQLiteDatabase) {
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

    /** Вставка строки models БЕЗ колонки sha256s (как умели до миграции). */
    private fun insertLegacyModelRow(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: String,
        sha256: String?
    ) {
        db.execSQL(
            "INSERT INTO models (id, category, display_name, description, runtime, " +
                "size_bytes, ram_required_mb, languages, quality, speed, url, urls, " +
                "sha256, license, min_android_sdk, recommended_ram_mb, status, progress, " +
                "file_path, bytes_done, is_builtin, created_at, updated_at) " +
                "VALUES (?, 'TRANSLATION', 'Legacy Model ' || ?, 'desc', 'MARIAN_ONNX', " +
                "1000, 256, 'ru,en', 3, 4, ?, 'u1|u2', ?, 'Apache-2.0', 26, 3072, " +
                "'READY', 100, '/tmp/x.onnx', 1000, 1, 1700000000, 1700000001)",
            arrayOf(id, id, "https://example/$id.onnx", sha256)
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

    @After
    fun tearDown() {
        context.deleteDatabase("h2_direct_migration.db")
        context.deleteDatabase("h2_room_migration.db")
    }

    @Test
    fun directMigrationKeepsOldRowsAndAddsSha256sWithEmptyDefault() {
        val file = File(context.getDatabasePath("h2_direct_migration.db").absolutePath)
        deleteDbFiles(file)
        val helper = openHelperAt(file, 3)
        val db = helper.writableDatabase
        createV3Schema(db)
        insertLegacyModelRow(db, "legacy-a", "abc123")
        insertLegacyModelRow(db, "legacy-b", null)

        // Собственно миграция 3 → 4 из AppDatabase.
        AppDatabase.MIGRATION_3_4.migrate(db)

        // Колонка появилась.
        assertTrue(
            "sha256s не добавлена: ${columnNames(db, "models")}",
            columnNames(db, "models").contains("sha256s")
        )

        // Старые строки сохранены, sha256s у них — пустая строка.
        db.query(
            "SELECT id, display_name, urls, sha256, status, sha256s FROM models " +
                "WHERE id = 'legacy-a'"
        ).use { cursor ->
            assertTrue("Строка legacy-a пропала после миграции", cursor.moveToFirst())
            assertEquals("legacy-a", cursor.getString(0))
            assertEquals("Legacy Model legacy-a", cursor.getString(1))
            assertEquals("u1|u2", cursor.getString(2))
            assertEquals("abc123", cursor.getString(3))
            assertEquals("READY", cursor.getString(4))
            assertEquals("sha256s старой строки обязан быть ''", "", cursor.getString(5))
        }
        db.query("SELECT sha256s FROM models WHERE id = 'legacy-b'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("", cursor.getString(0))
        }

        // Вставка БЕЗ колонки sha256s работает, DEFAULT ''.
        insertLegacyModelRow(db, "after-migration", "def456")
        db.query("SELECT sha256s FROM models WHERE id = 'after-migration'").use { cursor ->
            assertTrue("Вставка без sha256s после миграции не прошла", cursor.moveToFirst())
            assertEquals("", cursor.getString(0))
        }

        helper.close()
        deleteDbFiles(file)
    }

    @Test
    fun roomEndToEndUpgradeFromV3FileRunsMigrationAndDaoWorks() {
        val dbName = "h2_room_migration.db"
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        deleteDbFiles(file)

        // Готовим файл версии 3 (user_version=3 через колбэк) со старой строкой.
        val helper = openHelperAt(file, 3)
        val legacyDb = helper.writableDatabase
        createV3Schema(legacyDb)
        insertLegacyModelRow(legacyDb, "room-legacy", null)
        helper.close()

        // Room открывает v3-базу и сам прогоняет цепочку миграций 3→4→5
        // (текущая версия схемы — 5); при расхождении схемы миграции со
        // схемой сущностей build() упадёт.
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
            .build()
        try {
            runBlocking {
                val entity = db.modelDao().getById("room-legacy")
                assertNotNull("Старая строка не дошла до DAO", entity)
                entity!!
                assertEquals("room-legacy", entity.id)
                assertEquals("Legacy Model room-legacy", entity.displayName)
                assertEquals("u1|u2", entity.urls)
                assertNull(entity.sha256)
                assertEquals("sha256s должен быть заполнен DEFAULT ''", "", entity.sha256s)

                // DAO-инсерты на мигрированной таблице работают.
                db.modelDao().upsert(entity.copy(sha256s = "hash-1,hash-2"))
                assertEquals(
                    "hash-1,hash-2",
                    db.modelDao().getById("room-legacy")?.sha256s
                )
            }

            // RAW-вставка без колонки — DEFAULT '' и после Room-апгрейда.
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO models (id, category, display_name, description, runtime, " +
                    "size_bytes, ram_required_mb, languages, quality, speed, url, urls, " +
                    "sha256, license, min_android_sdk, recommended_ram_mb, status, progress, " +
                    "file_path, bytes_done, is_builtin, created_at, updated_at) " +
                    "VALUES ('raw-row', 'TRANSLATION', 'Raw', 'd', 'SHERPA', 1, 1, 'ru', " +
                    "1, 1, '', '', NULL, 'MIT', 26, 1, 'DOWNLOADING', 0, NULL, 0, 0, 1, 1)"
            )
            db.openHelper.readableDatabase.query(
                "SELECT sha256s FROM models WHERE id = 'raw-row'"
            ).use { cursor ->
                assertTrue("RAW-вставка без sha256s не прошла", cursor.moveToFirst())
                assertEquals("", cursor.getString(0))
            }

            // Сквозная цепочка 3→4→5 достраивает и таблицу favorites.
            assertTrue(
                "favorites не появилась после цепочки 3→4→5",
                columnNames(db.openHelper.readableDatabase, "favorites").isNotEmpty()
            )

            // Версия БД после апгрейда — 5 (цепочка 3→4→5).
            db.openHelper.readableDatabase.query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(5, cursor.getInt(0))
            }
        } finally {
            db.close()
        }
    }
}
