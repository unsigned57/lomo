package com.lomo.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: rebuildMemoFtsExternalContentInfrastructure in upgrade/migration paths
 * - Behavior focus: FTS infrastructure rebuild sequences, ensuring lomo_fts is replaced with external-content FTS5
 *   and all three triggers (INSERT/UPDATE/DELETE) are created when rebuilding from any prior FTS state.
 * - Observable outcomes: execSQL DDL contains "fts5" + "external-content" directives; all three triggers created;
 *   no "fts4" or application-managed DDL is emitted during rebuild paths.
 * - Red phase: Fails before the fix because rebuildMemoFtsExternalContentInfrastructure needs to emit external-content
 *   schema (content='Lomo', content_rowid='rowid') and trigger DDL.
 * - Excludes: Room schema validation, actual SQLite query execution, tokenizer semantics, and
 *   migration correctness beyond the FTS rebuild step.
 */
class DatabaseFtsInfrastructureExistingTableTest {
    @Test
    fun `rebuildMemoFtsExternalContentInfrastructure upgrades existing fts4 table to external-content fts5`() {
        val db = RecordingSQLiteConnection { sql, _ -> if (sql.contains("name='$MEMO_TABLE'")) oneRowResult() else SQLiteQueryResult.EMPTY }

        rebuildMemoFtsExternalContentInfrastructure(db)

        assertEquals(1, db.executedStatements.count { it.sql == "$DROP_TABLE_IF_EXISTS `$FTS_TABLE`" })
        assertTrue(db.executedStatements.any { it.sql.contains("USING fts5", ignoreCase = true) })
        assertTrue(db.executedStatements.any { it.sql.contains("content='Lomo'", ignoreCase = true) })
        assertTrue(db.executedStatements.any { it.sql.contains("CREATE TRIGGER IF NOT EXISTS `lomo_fts_ai`") })
        assertFalse(db.executedStatements.any { it.sql.contains("USING FTS4", ignoreCase = true) })
    }

    private fun oneRowResult(): SQLiteQueryResult = queryResult("present", rows = listOf(rowOf(1)))
}
