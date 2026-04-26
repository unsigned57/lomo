package com.lomo.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

/*
 * Test Contract:
 * - Unit under test: rebuildMemoFtsExternalContentInfrastructure (DatabaseFtsInfrastructure)
 * - Behavior focus: external-content FTS5 schema, trigger creation, and rebuild command execution.
 * - Observable outcomes: emitted DDL includes external-content clauses, trigger SQL is present,
 *   and rebuilt index returns rows from the Lomo content table.
 * - Red phase: Fails before the fix because rebuildMemoFtsExternalContentInfrastructure needs external-content
 *   schema and trigger infrastructure to be created and rebuild command issued.
 * - Excludes: Room validation and app-layer repository wiring.
 */
class DatabaseFtsInfrastructureCallbackTest {
    @Test
    fun `rebuildMemoFtsExternalContentInfrastructure creates external content fts5 table`() {
        val db = RecordingSQLiteConnection { _, _ -> SQLiteQueryResult.EMPTY }

        rebuildMemoFtsExternalContentInfrastructure(db)

        assertTrue(
            db.executedStatements.any { statement ->
                statement.sql.contains("USING fts5", ignoreCase = true) &&
                    statement.sql.contains("content='Lomo'", ignoreCase = true) &&
                    statement.sql.contains("content_rowid='rowid'", ignoreCase = true) &&
                    statement.sql.contains(COLUMN_SEARCH_CONTENT)
            },
        )
        assertFalse(db.executedStatements.any { it.sql.contains("USING FTS4", ignoreCase = true) })
    }

    @Test
    fun `rebuildMemoFtsExternalContentInfrastructure creates insert update delete triggers`() {
        val db = RecordingSQLiteConnection { _, _ -> SQLiteQueryResult.EMPTY }

        rebuildMemoFtsExternalContentInfrastructure(db)

        assertTrue(db.executedStatements.any { it.sql.contains("CREATE TRIGGER IF NOT EXISTS `lomo_fts_ai`") })
        assertTrue(db.executedStatements.any { it.sql.contains("CREATE TRIGGER IF NOT EXISTS `lomo_fts_au`") })
        assertTrue(db.executedStatements.any { it.sql.contains("CREATE TRIGGER IF NOT EXISTS `lomo_fts_ad`") })
    }

    @Test
    fun `rebuildMemoFtsExternalContentInfrastructure runs rebuild command after creating infrastructure`() {
        val db = RecordingSQLiteConnection { _, _ -> SQLiteQueryResult.EMPTY }

        rebuildMemoFtsExternalContentInfrastructure(db)

        assertTrue(
            db.executedStatements.any { statement ->
                statement.sql == "INSERT INTO `lomo_fts`(`lomo_fts`) VALUES ('rebuild')"
            },
        )
    }

    @Test
    fun `rebuildMemoFtsExternalContentInfrastructure rebuilds external content index from lomo rows`() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().execute(
                """
                CREATE TABLE `$MEMO_TABLE` (
                    `id` TEXT NOT NULL PRIMARY KEY,
                    `$COLUMN_CONTENT` TEXT NOT NULL,
                    `$COLUMN_SEARCH_CONTENT` TEXT NOT NULL
                )
                """.trimIndent(),
            )
            connection.prepareStatement(
                "INSERT INTO `$MEMO_TABLE`(`id`, `$COLUMN_CONTENT`, `$COLUMN_SEARCH_CONTENT`) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, "memo-1")
                statement.setString(2, "hello world")
                statement.setString(3, "hello world")
                statement.executeUpdate()
            }

            rebuildMemoFtsExternalContentInfrastructure(JdbcSQLiteConnection(connection))

            connection.createStatement().executeQuery("SELECT COUNT(*) FROM `$FTS_TABLE`").use { resultSet ->
                assertTrue(resultSet.next())
                assertEquals(1, resultSet.getInt(1))
            }
        }
    }
}
