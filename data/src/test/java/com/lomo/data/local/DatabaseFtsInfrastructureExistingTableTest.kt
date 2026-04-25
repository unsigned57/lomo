package com.lomo.data.local

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: rebuildMemoFtsTable (DatabaseMigrationTableBuilders) in upgrade/migration paths
 * - Behavior focus: FTS module version in migration sequences that invoke rebuildMemoFtsTable,
 *   ensuring an already-existing lomo_fts table is replaced with an FTS5 variant and not silently
 *   left as FTS4 after an upgrade.
 * - Observable outcomes: execSQL DDL contains "fts5"; FTS5 is used even when lomo table is non-empty;
 *   no "fts4" DDL is emitted during migration paths touching FTS rebuild.
 * - Red phase: Fails before the fix because all paths through rebuildMemoFtsTable currently emit
 *   "USING FTS4(...)". The fts5 assertions will not match, making every test in this file RED.
 * - Excludes: Room schema validation, actual SQLite query execution, tokenizer semantics, and
 *   migration correctness beyond the FTS rebuild step.
 */
class DatabaseFtsInfrastructureExistingTableTest {
    @Test
    fun `rebuildMemoFtsTable upgrades existing fts4 table to fts5 via drop-and-recreate`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        // Simulate that Lomo table exists but lomo_fts also exists (upgrade from FTS4)
        every { db.query(any<String>()) } answers { call ->
            val sql = call.invocation.args[0] as String
            when {
                sql.contains("name='$MEMO_TABLE'") -> oneRowCursor()
                else -> noRowsCursor()
            }
        }

        rebuildMemoFtsTable(db)

        // Must DROP first to remove any stale FTS4 table
        verify(exactly = 1) { db.execSQL("$DROP_TABLE_IF_EXISTS `$FTS_TABLE`") }
        // Must recreate as FTS5
        verify {
            db.execSQL(match { sql -> sql.contains("USING fts5", ignoreCase = true) })
        }
        // Must NOT recreate as FTS4
        verify(exactly = 0) {
            db.execSQL(match { sql -> sql.contains("USING FTS4", ignoreCase = true) })
        }
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
