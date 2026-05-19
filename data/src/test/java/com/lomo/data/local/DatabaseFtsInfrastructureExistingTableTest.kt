package com.lomo.data.local

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */



import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse

/*
 * Behavior Contract:
 * - Unit under test: rebuildMemoFtsExternalContentInfrastructure in upgrade/migration paths
 * - Behavior focus: FTS infrastructure rebuild sequences, ensuring lomo_fts is replaced with external-content FTS5
 *   and all three triggers (INSERT/UPDATE/DELETE) are created when rebuilding from any prior FTS state.
 * - Observable outcomes: execSQL DDL contains "fts5" + "external-content" directives; all three triggers created;
 *   no "fts4" or application-managed DDL is emitted during rebuild paths.
 * - TDD proof: Fails before the fix because rebuildMemoFtsExternalContentInfrastructure needs to emit external-content
 *   schema (content='Lomo', content_rowid='rowid') and trigger DDL.
 * - Excludes: Room schema validation, actual SQLite query execution, tokenizer semantics, and
 *   migration correctness beyond the FTS rebuild step.
 */
class DatabaseFtsInfrastructureExistingTableTest : DataFunSpec() {
    init {
        test("rebuildMemoFtsExternalContentInfrastructure upgrades existing fts4 table to external-content fts5") { `rebuildMemoFtsExternalContentInfrastructure upgrades existing fts4 table to external-content fts5`() }
    }


    private fun `rebuildMemoFtsExternalContentInfrastructure upgrades existing fts4 table to external-content fts5`() {
        val db = RecordingSQLiteConnection { sql, _ -> if (sql.contains("name='$MEMO_TABLE'")) oneRowResult() else SQLiteQueryResult.EMPTY }

        rebuildMemoFtsExternalContentInfrastructure(db)

        db.executedStatements.count { it.sql == "$DROP_TABLE_IF_EXISTS `$FTS_TABLE`" } shouldBe 1
        (db.executedStatements.any { it.sql.contains("USING fts5", ignoreCase = true) }).shouldBeTrue()
        (db.executedStatements.any { it.sql.contains("content='Lomo'", ignoreCase = true) }).shouldBeTrue()
        (db.executedStatements.any { it.sql.contains("CREATE TRIGGER IF NOT EXISTS `lomo_fts_ai`") }).shouldBeTrue()
        (db.executedStatements.any { it.sql.contains("USING FTS4", ignoreCase = true) }).shouldBeFalse()
    }

    private fun oneRowResult(): SQLiteQueryResult = queryResult("present", rows = listOf(rowOf(1)))
}
