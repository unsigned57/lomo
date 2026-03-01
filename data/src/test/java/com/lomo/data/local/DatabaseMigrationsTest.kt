package com.lomo.data.local

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.mockk
import io.mockk.verify
import io.mockk.every
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseMigrationsTest {
    @Test
    fun `migration 22 to 23 drops legacy Lomo content index`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)

        MIGRATION_22_23.migrate(db)

        verify(exactly = 1) {
            db.execSQL("DROP INDEX IF EXISTS `index_Lomo_content`")
        }
    }

    @Test
    fun `migration 23 to 24 adds and backfills updatedAt`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        every { db.query(any<String>()) } answers {
            val sql = args[0] as String
            when {
                sql.contains("sqlite_master") -> mockCursor(true)
                sql.contains("PRAGMA table_info(`Lomo`)") -> mockColumnsCursor(setOf("id", "timestamp"))
                sql.contains("PRAGMA table_info(`LomoTrash`)") -> mockColumnsCursor(setOf("id", "timestamp"))
                else -> mockCursor(false)
            }
        }

        MIGRATION_23_24.migrate(db)

        verify(exactly = 1) {
            db.execSQL("ALTER TABLE `Lomo` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
        }
        verify(exactly = 1) {
            db.execSQL("ALTER TABLE `LomoTrash` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
        }
        verify(exactly = 1) {
            db.execSQL("UPDATE `Lomo` SET `updatedAt` = `timestamp` WHERE `updatedAt` <= 0")
        }
        verify(exactly = 1) {
            db.execSQL("UPDATE `LomoTrash` SET `updatedAt` = `timestamp` WHERE `updatedAt` <= 0")
        }
    }

    @Test
    fun `consolidation migrations cover every version from 1 to target-1`() {
        val target = MEMO_DATABASE_VERSION
        val coveredVersions =
            ALL_DATABASE_MIGRATIONS
                .filter { it.endVersion == target }
                .map { it.startVersion }
                .toSet()

        assertEquals(
            "Every version 1..(target-1) should have a direct migration to target",
            (1 until target).toSet(),
            coveredVersions,
        )
    }

    @Test
    fun `consolidation from v7 splits memos table and applies all phases`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val v7MemoColumns = setOf("id", "timestamp", "content", "rawContent", "date", "tags", "imageUrls", "isDeleted")
        val v22MemoColumns = setOf("id", "timestamp", "content", "rawContent", "date", "tags", "imageUrls")

        // Track table existence as migration creates/drops tables.
        val existingTables = mutableSetOf("memos")
        val tableNamePattern = Regex("""`(\w+)`""")

        every { db.execSQL(any()) } answers {
            val sql = args[0] as String
            val tableName = tableNamePattern.find(sql)?.groupValues?.get(1)
            if (tableName != null) {
                when {
                    sql.trimStart().startsWith("CREATE TABLE") ||
                        sql.trimStart().startsWith("CREATE VIRTUAL TABLE") -> existingTables.add(tableName)
                    sql.trimStart().startsWith("DROP TABLE") -> existingTables.remove(tableName)
                }
            }
        }

        every { db.query(any<String>()) } answers {
            val sql = args[0] as String
            when {
                sql.contains("sqlite_master") -> {
                    val name = Regex("""name='(\w+)'""").find(sql)?.groupValues?.get(1)
                    mockCursor(name != null && name in existingTables)
                }
                sql.contains("PRAGMA table_info") -> {
                    val name = tableNamePattern.find(sql)?.groupValues?.get(1)
                    when (name) {
                        "memos" -> mockColumnsCursor(v7MemoColumns)
                        "Lomo", "LomoTrash" -> mockColumnsCursor(v22MemoColumns)
                        else -> mockColumnsCursor(emptySet())
                    }
                }
                else -> mockCursor(false)
            }
        }

        val migration = ALL_DATABASE_MIGRATIONS.first {
            it.startVersion == 7 && it.endVersion == MEMO_DATABASE_VERSION
        }
        migration.migrate(db)

        // Phase A: memos split into Lomo + LomoTrash
        verify { db.execSQL(match { it.contains("INSERT OR REPLACE INTO `Lomo`") && it.contains("isDeleted") }) }
        verify { db.execSQL(match { it.contains("INSERT OR REPLACE INTO `LomoTrash`") && it.contains("isDeleted") }) }

        // Phase B: content index dropped
        verify { db.execSQL("DROP INDEX IF EXISTS `index_Lomo_content`") }

        // Phase C: updatedAt added
        verify { db.execSQL("ALTER TABLE `Lomo` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0") }
        verify { db.execSQL("ALTER TABLE `LomoTrash` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0") }
    }

    @Test
    fun `consolidation from v21 normalizes tables and applies all phases`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val v21MemoColumns = setOf("id", "timestamp", "content", "rawContent", "date", "tags", "imageUrls")
        val localFileStateColumns = setOf("filename", "isTrash", "saf_uri", "last_known_modified_time")
        val outboxColumns = setOf("id", "operation", "memoId", "memoDate", "memoTimestamp", "memoRawContent", "newContent", "createRawContent", "createdAt", "updatedAt", "retryCount", "lastError")

        val existingTables = mutableSetOf("Lomo", "LomoTrash", "local_file_state", "MemoFileOutbox")
        val tableNamePattern = Regex("""`(\w+)`""")

        every { db.execSQL(any()) } answers {
            val sql = args[0] as String
            val tableName = tableNamePattern.find(sql)?.groupValues?.get(1)
            if (tableName != null) {
                when {
                    sql.trimStart().startsWith("CREATE TABLE") ||
                        sql.trimStart().startsWith("CREATE VIRTUAL TABLE") -> existingTables.add(tableName)
                    sql.trimStart().startsWith("DROP TABLE") -> existingTables.remove(tableName)
                    sql.trimStart().startsWith("ALTER TABLE") && sql.contains("RENAME TO") -> {
                        val newName = sql.substringAfterLast("`").let {
                            sql.substringBeforeLast("`").substringAfterLast("`")
                        }
                        existingTables.remove(tableName)
                        if (newName.isNotBlank()) existingTables.add(newName)
                    }
                }
            }
        }

        every { db.query(any<String>()) } answers {
            val sql = args[0] as String
            when {
                sql.contains("sqlite_master") -> {
                    val name = Regex("""name='(\w+)'""").find(sql)?.groupValues?.get(1)
                    mockCursor(name != null && name in existingTables)
                }
                sql.contains("PRAGMA table_info") -> {
                    val name = tableNamePattern.find(sql)?.groupValues?.get(1)
                    when {
                        name == "Lomo" || name == "LomoTrash" ||
                            name == "Lomo_legacy_v22" || name == "LomoTrash_legacy_v22" -> mockColumnsCursor(v21MemoColumns)
                        name == "local_file_state" || name == "local_file_state_legacy_v22" -> mockColumnsCursor(localFileStateColumns)
                        name == "MemoFileOutbox" || name == "MemoFileOutbox_legacy_v22" -> mockColumnsCursor(outboxColumns)
                        else -> mockColumnsCursor(emptySet())
                    }
                }
                else -> mockCursor(false)
            }
        }

        val migration = ALL_DATABASE_MIGRATIONS.first {
            it.startVersion == 21 && it.endVersion == MEMO_DATABASE_VERSION
        }
        migration.migrate(db)

        // Phase A: tables normalized (rename + rebuild)
        verify { db.execSQL(match { it.contains("Lomo") && it.contains("RENAME TO") }) }

        // Phase B: content index dropped
        verify { db.execSQL("DROP INDEX IF EXISTS `index_Lomo_content`") }

        // Phase C: updatedAt added
        verify { db.execSQL("ALTER TABLE `Lomo` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0") }
        verify { db.execSQL("ALTER TABLE `LomoTrash` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0") }
    }

    @Test
    fun `consolidation from v7 migrates file_sync_metadata to local_file_state`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val v7MemoColumns = setOf("id", "timestamp", "content", "rawContent", "date", "tags", "imageUrls", "isDeleted")
        val fileSyncColumns = setOf("filename", "lastModified", "isTrash")

        val existingTables = mutableSetOf("memos", "file_sync_metadata")
        val tableNamePattern = Regex("""`(\w+)`""")

        every { db.execSQL(any()) } answers {
            val sql = args[0] as String
            val tableName = tableNamePattern.find(sql)?.groupValues?.get(1)
            if (tableName != null) {
                when {
                    sql.trimStart().startsWith("CREATE TABLE") ||
                        sql.trimStart().startsWith("CREATE VIRTUAL TABLE") -> existingTables.add(tableName)
                    sql.trimStart().startsWith("DROP TABLE") -> existingTables.remove(tableName)
                }
            }
        }

        every { db.query(any<String>()) } answers {
            val sql = args[0] as String
            when {
                sql.contains("sqlite_master") -> {
                    val name = Regex("""name='(\w+)'""").find(sql)?.groupValues?.get(1)
                    mockCursor(name != null && name in existingTables)
                }
                sql.contains("PRAGMA table_info") -> {
                    val name = tableNamePattern.find(sql)?.groupValues?.get(1)
                    when (name) {
                        "memos" -> mockColumnsCursor(v7MemoColumns)
                        "file_sync_metadata" -> mockColumnsCursor(fileSyncColumns)
                        else -> mockColumnsCursor(emptySet())
                    }
                }
                else -> mockCursor(false)
            }
        }

        val migration = ALL_DATABASE_MIGRATIONS.first {
            it.startVersion == 7 && it.endVersion == MEMO_DATABASE_VERSION
        }
        migration.migrate(db)

        verify { db.execSQL(match { it.contains("INSERT OR REPLACE INTO `local_file_state`") && it.contains("file_sync_metadata") }) }
    }

    @Test
    fun `consolidation drops all legacy tables`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)

        every { db.query(any<String>()) } answers {
            val sql = args[0] as String
            when {
                sql.contains("sqlite_master") -> mockCursor(false)
                else -> mockCursor(false)
            }
        }

        val migration = ALL_DATABASE_MIGRATIONS.first {
            it.startVersion == 1 && it.endVersion == MEMO_DATABASE_VERSION
        }
        migration.migrate(db)

        val legacyTables = listOf("memos", "image_cache", "tags", "memo_tag_cross_ref", "memos_fts", "file_sync_metadata")
        for (table in legacyTables) {
            verify { db.execSQL("DROP TABLE IF EXISTS `$table`") }
        }
    }

    private fun mockCursor(hasRow: Boolean): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        every { cursor.moveToFirst() } returns hasRow
        every { cursor.close() } returns Unit
        return cursor
    }

    private fun mockColumnsCursor(columns: Set<String>): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        val rows = columns.toList()
        var index = -1
        every { cursor.getColumnIndex("name") } returns 0
        every { cursor.moveToNext() } answers {
            index += 1
            index < rows.size
        }
        every { cursor.getString(0) } answers { rows[index] }
        every { cursor.close() } returns Unit
        return cursor
    }
}
