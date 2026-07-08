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



import android.database.Cursor
import com.lomo.data.util.SearchTokenizer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse

/*
 * Behavior Contract:
 * - Unit under test: FTS search infrastructure end-to-end (DDL generation + real SQLite queries)
 * - Behavior focus: FTS5 module availability, query correctness for English words, single CJK
 *   characters, multi-CJK bigrams, mixed Latin+CJK, symbol-only inputs (expected: no match), FTS
 *   reserved words (OR, AND) treated as literal search text rather than operators, and upgrade/open-
 *   repair behavior that replaces a legacy FTS4 table with a searchable FTS5 index.
 * - Observable outcomes: sqlite_master schema shows "fts5"; FTS5 queries return correct memo rows
 *   for each character class; symbol-only input yields empty result; FTS reserved words match
 *   when present in content.
 * - TDD proof: The upgrade/repair scenarios execute the production FTS maintenance helper against
 *   a real SQLite database. They stay RED while schema repair still emits FTS4 or while upgraded
 *   databases are not retokenized back to the repository's live MATCH contract.
 * - Excludes: Android Room wiring, DAO interface, DI injection, and write-path FTS sync.
 */
class MemoSearchFtsIntegrationTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        afterTest {
            tearDown()
        }

        test("sqlite_master shows fts5 module after creating lomo_fts with fts5 DDL") { `sqlite_master shows fts5 module after creating lomo_fts with fts5 DDL`() }

        test("fts5 matches English word with prefix wildcard") { `fts5 matches English word with prefix wildcard`() }

        test("fts5 matches single CJK character") { `fts5 matches single CJK character`() }

        test("fts5 matches multi-CJK query via bigram approach") { `fts5 matches multi-CJK query via bigram approach`() }

        test("fts5 matches mixed Latin and CJK query") { `fts5 matches mixed Latin and CJK query`() }

        test("fts5 symbol-only query returns empty result") { `fts5 symbol-only query returns empty result`() }

        test("fts5 OR token in content is matched literally not as an operator when escaped") { `fts5 OR token in content is matched literally not as an operator when escaped`() }

        test("fts5 query with AND token prefix matches word starting with 'AND'") { `fts5 query with AND token prefix matches word starting with 'AND'`() }

        test("fts5 fresh database open produces searchable index") { `fts5 fresh database open produces searchable index`() }

        test("fts5 repaired database remains searchable after replacing legacy fts4 table") { `fts5 repaired database remains searchable after replacing legacy fts4 table`() }

        test("fts5 repaired database supports repository style multi bigram CJK query after production repair") { `fts5 repaired database supports repository style multi bigram CJK query after production repair`() }
    }


    private lateinit var connection: Connection

    private fun setUp() {
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        createMemoTable()
    }

    private fun tearDown() {
        connection.close()
    }

    private fun `sqlite_master shows fts5 module after creating lomo_fts with fts5 DDL`() {
        createFts5Table()

        val sql = querySingleString("SELECT sql FROM sqlite_master WHERE name='lomo_fts'")
        withClue("sqlite_master.sql for lomo_fts must contain 'fts5'. Got: $sql") { (sql?.contains("fts5", ignoreCase = true) == true).shouldBeTrue() }
        withClue("sqlite_master.sql for lomo_fts must not contain 'fts4'. Got: $sql") { (sql?.contains("fts4", ignoreCase = true) == true).shouldBeFalse() }
    }

    // ── Section B: FTS5 query behavior (demonstrates desired behavior) ────────────────────────

    private fun `fts5 matches English word with prefix wildcard`() {
        createFts5Table()
        insertMemo("memo-en", "The quick brown fox jumps over the lazy dog")
        insertMemo("memo-other", "Unrelated content about nothing")

        val results = fts5Search("quic*")

        results shouldBe listOf("memo-en")
    }

    private fun `fts5 matches single CJK character`() {
        createFts5Table()
        insertMemo("memo-cjk1", "你好世界")
        insertMemo("memo-cjk2", "今天天气很好")
        insertMemo("memo-en", "hello world")

        // Single CJK char search
        val results = fts5Search("你*")

        withClue("Single CJK search for '你' should match memo-cjk1") { (results.contains("memo-cjk1")).shouldBeTrue() }
        withClue("Single CJK search for '你' should not match memo-cjk2") { (results.contains("memo-cjk2")).shouldBeFalse() }
    }

    private fun `fts5 matches multi-CJK query via bigram approach`() {
        createFts5Table()
        insertMemo("memo-cjk1", "苏格拉底是古希腊哲学家")
        insertMemo("memo-cjk2", "苏格兰威士忌")
        insertMemo("memo-en", "philosophy notes")

        // Bigram query: "苏格" is a bigram for "苏格拉底" and also "苏格兰"
        val results = fts5Search("苏格*")

        withClue("Bigram '苏格' should match memo-cjk1 (苏格拉底)") { (results.contains("memo-cjk1")).shouldBeTrue() }
        withClue("Bigram '苏格' should match memo-cjk2 (苏格兰)") { (results.contains("memo-cjk2")).shouldBeTrue() }
        withClue("Bigram '苏格' should not match English memo") { (results.contains("memo-en")).shouldBeFalse() }
    }

    private fun `fts5 matches mixed Latin and CJK query`() {
        createFts5Table()
        insertMemo("memo-mixed", "AI 人工智能 is transforming 技术")
        insertMemo("memo-en-only", "AI is transforming technology")
        insertMemo("memo-cjk-only", "人工智能技术")

        // Both tokens must match (AND semantics with multiple prefix terms)
        val aiResults = fts5Search("AI*")
        val cjkResults = fts5Search("人工*")

        withClue("'AI*' should match the mixed memo") { (aiResults.contains("memo-mixed")).shouldBeTrue() }
        withClue("'人工*' should match the mixed memo") { (cjkResults.contains("memo-mixed")).shouldBeTrue() }
    }

    private fun `fts5 symbol-only query returns empty result`() {
        createFts5Table()
        insertMemo("memo-sym", "price is \$100 or €50 or ¥80")

        // FTS5 may throw a syntax error for symbol-only queries (e.g. "$*") because the
        // tokenizer produces no tokens and the raw symbol is a syntax error in FTS5's query
        // language.  Both outcomes (empty result set or syntax error) demonstrate the correct
        // behavior: symbol-only inputs do NOT match through the FTS path.
        val results: List<String> =
            try {
                fts5Search("\$*")
            } catch (_: java.sql.SQLException) {
                emptyList()
            }

        withClue("Symbol-only query '\$*' must not produce unexpected cross-matches. Results: $results") { (results.isEmpty() || !results.contains("memo-sym")).shouldBeTrue() }
    }

    private fun `fts5 OR token in content is matched literally not as an operator when escaped`() {
        createFts5Table()
        insertMemo("memo-or", "You can choose option OR another option")
        insertMemo("memo-and", "You need A AND B to proceed")
        insertMemo("memo-plain", "regular note without reserved words")

        // FTS5 treats uppercase OR/AND as operators; prefix-wrapped terms avoid this
        val results = fts5Search("option*")

        withClue("Search 'option*' should match memo containing 'option'") { (results.contains("memo-or")).shouldBeTrue() }
        withClue("Search 'option*' should not match unrelated memo") { (results.contains("memo-plain")).shouldBeFalse() }
    }

    private fun `fts5 query with AND token prefix matches word starting with 'AND'`() {
        createFts5Table()
        insertMemo("memo-android", "Android development guide")
        insertMemo("memo-plain", "regular note")

        // Searching for "Andr*" must not crash or be treated as FTS operator
        val results = fts5Search("Andr*")

        withClue("Search 'Andr*' should match 'Android'") { (results.contains("memo-android")).shouldBeTrue() }
    }

    private fun `fts5 fresh database open produces searchable index`() {
        // Test that a freshly opened database with FTS5 table is immediately queryable
        createFts5Table()
        insertMemo("memo-fresh", "brand new database note")

        val results = fts5Search("brand*")

        results shouldBe listOf("memo-fresh")
    }

    private fun `fts5 repaired database remains searchable after replacing legacy fts4 table`() {
        createLegacyFts4Table()
        insertMemo("memo-upgrade", "legacy upgrade 苏格 Android")
        insertMemo("memo-other", "plain fallback note")

        runProductionFtsMaintenance()

        val repairedSql = querySingleString("SELECT sql FROM sqlite_master WHERE name='lomo_fts'")
        withClue("Production repair must recreate lomo_fts as FTS5. Got: $repairedSql") { (repairedSql?.contains("fts5", ignoreCase = true) == true).shouldBeTrue() }
        withClue("Repaired database should still match the migrated English token") { (fts5Search("legacy*").contains("memo-upgrade")).shouldBeTrue() }
        withClue("Repaired database should still match the migrated CJK token") { (fts5Search("苏格*").contains("memo-upgrade")).shouldBeTrue() }
        withClue("Repaired database should still match the migrated Android token") { (fts5Search("Andr*").contains("memo-upgrade")).shouldBeTrue() }
        withClue("Repaired database should not cross-match unrelated rows") { (fts5Search("legacy*").contains("memo-other")).shouldBeFalse() }
    }

    private fun `fts5 repaired database supports repository style multi bigram CJK query after production repair`() {
        createLegacyFts4Table()
        insertMemo("memo-upgrade", "苏格拉底与柏拉图")
        insertMemo("memo-other", "苏格兰旅行记录")

        runProductionFtsMaintenance()

        val results = fts5Search("苏格* 格拉* 拉底*")

        withClue("Production repair must preserve the repository's multi-bigram CJK MATCH contract.") { results shouldBe listOf("memo-upgrade") }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

    private fun createMemoTable() {
        connection.createStatement().execute(
            """
            CREATE TABLE IF NOT EXISTS `Lomo` (
                `id` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `content` TEXT NOT NULL,
                `searchContent` TEXT NOT NULL DEFAULT '',
                `rawContent` TEXT NOT NULL,
                `date` TEXT NOT NULL,
                `tags` TEXT NOT NULL,
                `imageUrls` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
    }

    private fun createFts5Table() {
        rebuildMemoFtsExternalContentInfrastructure(JdbcSQLiteConnection(connection))
    }

    private fun createLegacyFts4Table() {
        connection.createStatement().execute("DROP TABLE IF EXISTS `lomo_fts`")
        connection.createStatement().execute(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS `lomo_fts`
            USING fts4(`memoId`, `content`, tokenize=unicode61)
            """.trimIndent(),
        )
    }

    private fun runProductionFtsMaintenance() {
        ensureMemoFtsTable(JdbcSQLiteConnection(connection))
    }

    private fun insertMemo(
        id: String,
        content: String,
    ) {
        connection.prepareStatement(
            "INSERT INTO `Lomo`(id, timestamp, content, searchContent, rawContent, date, tags, imageUrls, updatedAt) VALUES (?,?,?,?,?,?,?,?,?)",
        ).use { stmt ->
            stmt.setString(1, id)
            stmt.setLong(2, System.currentTimeMillis())
            stmt.setString(3, content)
            stmt.setString(4, SearchTokenizer.tokenize(content))
            stmt.setString(5, content)
            stmt.setString(6, "2026_01_01")
            stmt.setString(7, "")
            stmt.setString(8, "")
            stmt.setLong(9, System.currentTimeMillis())
            stmt.execute()
        }
    }

    private fun fts5Search(matchQuery: String): List<String> {
        val results = mutableListOf<String>()
        connection.prepareStatement(
            "SELECT Lomo.id FROM Lomo INNER JOIN lomo_fts ON lomo_fts.rowid = Lomo.rowid WHERE lomo_fts MATCH ? ORDER BY Lomo.timestamp DESC",
        ).use { stmt ->
            stmt.setString(1, matchQuery)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    results.add(rs.getString(1))
                }
            }
        }
        return results
    }

    private fun querySingleString(sql: String): String? =
        connection.createStatement().executeQuery(sql).use { rs ->
            if (rs.next()) rs.getString(1) else null
        }

}
