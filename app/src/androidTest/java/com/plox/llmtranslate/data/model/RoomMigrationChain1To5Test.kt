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
 * Сквозной instrumentation-тест цепочки миграций Room 1 → 5 (issue #66):
 * [AppDatabase.MIGRATION_1_2] + [AppDatabase.MIGRATION_2_3] +
 * [AppDatabase.MIGRATION_3_4] + [AppDatabase.MIGRATION_4_5].
 *
 * Экспортированные схемы в app/schemas есть только для версий 4 и 5
 * (экспорт включён уже на v4), поэтому MigrationTestHelper из
 * androidx.room:room-testing для старта с v1 неприменим (плюс room-testing
 * в проекте нет); тест идёт
 * рабочим путём соседних RoomMigration3To4Test/RoomMigration4To5Test:
 * 1) схема версии 1 (models без urls/sha256s, таблиц history/favorites нет)
 *    создаётся вручную, вставляется «старая» строка модели;
 * 2) все четыре миграции прогоняются напрямую ([Migration].migrate) с
 *    проверкой промежуточных состояний после каждого шага;
 * 3) отдельно — сквозной прогон через Room.databaseBuilder: Room сам
 *    выполняет цепочку при открытии v1-базы и валидирует финальную схему
 *    (при расхождении со схемой сущностей build() упадёт);
 * 4) проверяются: сохранность данных старой строки, DEFAULT '' для urls и
 *    sha256s, появление translation_history и favorites с уникальным
 *    индексом, работа всех трёх DAO, user_version = 5.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationChain1To5Test {

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
     * Схема версии 1: models без urls/sha256s (колонки добавляются
     * миграциями 1→2 и 3→4); таблиц translation_history и favorites нет.
     */
    private fun createV1Schema(db: androidx.sqlite.db.SupportSQLiteDatabase) {
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
    }

    /** Вставка строки models схемы v1 (без urls и sha256s). */
    private fun insertV1ModelRow(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: String,
        sha256: String?
    ) {
        db.execSQL(
            "INSERT INTO models (id, category, display_name, description, runtime, " +
                "size_bytes, ram_required_mb, languages, quality, speed, url, " +
                "sha256, license, min_android_sdk, recommended_ram_mb, status, progress, " +
                "file_path, bytes_done, is_builtin, created_at, updated_at) " +
                "VALUES (?, 'TRANSLATION', 'Legacy Model ' || ?, 'desc', 'MARIAN_ONNX', " +
                "1000, 256, 'ru,en', 3, 4, ?, ?, 'Apache-2.0', 26, 3072, " +
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
        context.deleteDatabase("v1_chain_direct.db")
        context.deleteDatabase("v1_chain_room.db")
    }

    @Test
    fun directChainKeepsRowsAndBuildsFinalV5Schema() {
        val file = File(context.getDatabasePath("v1_chain_direct.db").absolutePath)
        deleteDbFiles(file)
        val helper = openHelperAt(file, 1)
        val db = helper.writableDatabase
        createV1Schema(db)
        insertV1ModelRow(db, "chain-a", "abc123")
        insertV1ModelRow(db, "chain-b", null)

        // 1 → 2: колонка urls с DEFAULT ''.
        AppDatabase.MIGRATION_1_2.migrate(db)
        assertTrue("urls не добавлена миграцией 1→2", columnNames(db, "models").contains("urls"))
        db.query("SELECT urls FROM models WHERE id = 'chain-a'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("старая строка обязана получить DEFAULT ''", "", cursor.getString(0))
        }

        // 2 → 3: таблица истории с индексом.
        AppDatabase.MIGRATION_2_3.migrate(db)
        assertTrue(
            "translation_history не создана миграцией 2→3",
            columnNames(db, "translation_history").isNotEmpty()
        )
        // DML — через execSQL: query() возвращает ленивый курсор, который не
        // исполняет запрос до первого чтения (moveToFirst); без чтения INSERT
        // не выполняется и счётчик ниже видит пустую таблицу. В соседних
        // RoomMigration3To4Test/4To5Test вставки уже идут через execSQL.
        db.execSQL(
            "INSERT INTO translation_history (source_lang, target_lang, input_text, result_text, created_at) " +
                "VALUES ('ru', 'en', 'привет', 'hello', 1700000000)"
        )
        db.query("SELECT COUNT(*) FROM translation_history").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        // 3 → 4: колонка sha256s с DEFAULT '', старые строки сохранены.
        AppDatabase.MIGRATION_3_4.migrate(db)
        assertTrue("sha256s не добавлена миграцией 3→4", columnNames(db, "models").contains("sha256s"))
        db.query(
            "SELECT id, display_name, url, sha256, status, urls, sha256s FROM models " +
                "WHERE id = 'chain-a'"
        ).use { cursor ->
            assertTrue("строка chain-a потеряна в цепочке", cursor.moveToFirst())
            assertEquals("chain-a", cursor.getString(0))
            assertEquals("Legacy Model chain-a", cursor.getString(1))
            assertEquals("https://example/chain-a.onnx", cursor.getString(2))
            assertEquals("abc123", cursor.getString(3))
            assertEquals("READY", cursor.getString(4))
            assertEquals("", cursor.getString(5))
            assertEquals("", cursor.getString(6))
        }

        // 4 → 5: favorites + уникальный индекс.
        AppDatabase.MIGRATION_4_5.migrate(db)
        assertEquals(
            setOf("id", "source_lang", "target_lang", "input_text", "result_text", "created_at"),
            columnNames(db, "favorites")
        )
        db.query("PRAGMA index_list(`favorites`)").use { cursor ->
            var hasUnique = false
            val nameIndex = cursor.getColumnIndex("name")
            val uniqueIndex = cursor.getColumnIndex("unique")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "idx_favorites_unique" && cursor.getInt(uniqueIndex) == 1) {
                    hasUnique = true
                }
            }
            assertTrue("idx_favorites_unique не создан миграцией 4→5", hasUnique)
        }

        helper.close()
        deleteDbFiles(file)
    }

    @Test
    fun roomEndToEndUpgradeFromV1FileRunsFullChainAndDaosWork() {
        val dbName = "v1_chain_room.db"
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        deleteDbFiles(file)

        // Готовим файл версии 1 (user_version=1 через колбэк) со старой строкой.
        val helper = openHelperAt(file, 1)
        val legacyDb = helper.writableDatabase
        createV1Schema(legacyDb)
        insertV1ModelRow(legacyDb, "room-chain", null)
        helper.close()

        // Room открывает v1-базу и сам выполняет всю цепочку 1→2→3→4→5,
        // валидируя финальную схему против сущностей (при расхождении
        // build()/первый запрос упадут).
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
            )
            .build()
        try {
            runBlocking {
                val entity = db.modelDao().getById("room-chain")
                assertNotNull("Старая строка не дошла до DAO через всю цепочку", entity)
                entity!!
                assertEquals("room-chain", entity.id)
                assertEquals("Legacy Model room-chain", entity.displayName)
                assertNull(entity.sha256)
                assertEquals("urls должен быть заполнен DEFAULT ''", "", entity.urls)
                assertEquals("sha256s должен быть заполнен DEFAULT ''", "", entity.sha256s)

                // DAO всех трёх таблиц работают на мигрированной базе.
                db.modelDao().upsert(entity.copy(sha256s = "hash-1"))
                assertEquals("hash-1", db.modelDao().getById("room-chain")?.sha256s)

                db.translationHistoryDao().insert(
                    TranslationHistoryEntity(
                        sourceLang = "ru",
                        targetLang = "en",
                        inputText = "привет",
                        resultText = "hello",
                        createdAt = 1700000000L,
                    )
                )
                db.favoritesDao().insert(
                    FavoriteEntity(
                        sourceLang = "ru",
                        targetLang = "en",
                        inputText = "привет",
                        resultText = "hello",
                        createdAt = 1700000000L,
                    )
                )
            }

            // Версия БД после сквозного апгрейда — 5.
            db.openHelper.readableDatabase.query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(5, cursor.getInt(0))
            }
        } finally {
            db.close()
        }
    }
}
