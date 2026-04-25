package com.lomo.data.local

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: rebuildMemoFtsTable (DatabaseMigrationTableBuilders)
 * - Behavior focus: FTS module version used to create the lomo_fts virtual table, DROP-before-rebuild
 *   idempotency, and Lomo backfill when the memo table is present.
 * - Observable outcomes: execSQL call content (must contain "fts5"), presence or absence of INSERT
 *   backfill, and guaranteed DROP before CREATE.
 * - Red phase: Fails before the fix because rebuildMemoFtsTable currently generates
 *   "USING FTS4(...)" DDL. The assertion for "fts5" will not match "FTS4", causing a verification
 *   failure in every test in this file.
 * - Excludes: Room schema validation, query behavior, tokenizer configuration semantics, and
 *   migration sequencing beyond this function.
 */
class DatabaseFtsInfrastructureCallbackTest {
    @Test
    fun `rebuildMemoFtsTable creates virtual table using fts5 module`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        every { db.query(any<String>()) } answers { noRowsCursor() }

        rebuildMemoFtsTable(db)

        verify {
            db.execSQL(
                match { sql ->
                    sql.contains("USING fts5", ignoreCase = true) &&
                        sql.contains(FTS_TABLE)
                },
            )
        }
    }

    @Test
    fun `rebuildMemoFtsTable does not use deprecated fts4 module`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        every { db.query(any<String>()) } answers { noRowsCursor() }

        rebuildMemoFtsTable(db)

        verify(exactly = 0) {
            db.execSQL(
                match { sql ->
                    sql.contains("USING FTS4", ignoreCase = true)
                },
            )
        }
    }

    @Test
    fun `rebuildMemoFtsTable drops existing lomo_fts table before creating the new one`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        every { db.query(any<String>()) } answers { noRowsCursor() }

        rebuildMemoFtsTable(db)

        verify(exactly = 1) {
            db.execSQL("$DROP_TABLE_IF_EXISTS `$FTS_TABLE`")
        }
    }

    @Test
    fun `rebuildMemoFtsTable does not insert backfill when lomo table does not exist`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        // sqlite_master query for tableExists("Lomo") returns false
        every { db.query(any<String>()) } answers { noRowsCursor() }

        rebuildMemoFtsTable(db)

        verify(exactly = 0) {
            db.execSQL(
                match { sql ->
                    sql.contains("INSERT INTO") && sql.contains(FTS_TABLE)
                },
            )
        }
    }

    @Test
    fun `rebuildMemoFtsTable inserts backfill from lomo when memo table exists`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        every { db.query(any<String>()) } answers { call ->
            val sql = call.invocation.args[0] as String
            if (sql.contains("name='$MEMO_TABLE'")) oneRowCursor() else noRowsCursor()
        }

        rebuildMemoFtsTable(db)

        verify {
            db.execSQL(
                match { sql ->
                    sql.contains("INSERT INTO") &&
                        sql.contains("`$FTS_TABLE`") &&
                        sql.contains("`$MEMO_TABLE`")
                },
            )
        }
    }

    @Test
    fun `rebuildMemoFtsTable fts5 table includes memoId and content columns`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        every { db.query(any<String>()) } answers { noRowsCursor() }

        rebuildMemoFtsTable(db)

        verify {
            db.execSQL(
                match { sql ->
                    sql.contains("memoId") && sql.contains(COLUMN_CONTENT) &&
                        sql.contains("USING fts5", ignoreCase = true)
                },
            )
        }
    }

    @Test
    fun `rebuildMemoFtsTable emitted create statement targets fts5 instead of fts4`() {
        val capturedSql = mutableListOf<String>()
        val capturedStatement = slot<String>()
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        every { db.query(any<String>()) } answers { call ->
            val sql = call.invocation.args[0] as String
            if (sql.contains("name='$MEMO_TABLE'")) oneRowCursor() else noRowsCursor()
        }
        every { db.execSQL(capture(capturedStatement)) } answers {
            capturedSql.add(capturedStatement.captured)
        }

        rebuildMemoFtsTable(db)

        val createTableSql = capturedSql.firstOrNull { sql -> sql.contains(FTS_TABLE) && sql.contains("CREATE") }
        assertFalse(
            "Production rebuildMemoFtsTable must not generate FTS4 DDL. Got: $createTableSql",
            createTableSql?.contains("FTS4", ignoreCase = true) == true,
        )
        assertTrue(
            "Production rebuildMemoFtsTable must generate FTS5 DDL. Got: $createTableSql",
            createTableSql?.contains("fts5", ignoreCase = true) == true,
        )
    }

    private fun noRowsCursor(): Cursor =
        mockk<Cursor>(relaxed = true).also { cursor ->
            every { cursor.moveToFirst() } returns false
        }

    private fun oneRowCursor(): Cursor =
        mockk<Cursor>(relaxed = true).also { cursor ->
            every { cursor.moveToFirst() } returns true
        }
}
