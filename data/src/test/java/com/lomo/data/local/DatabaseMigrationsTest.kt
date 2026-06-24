/*
 * Behavior Contract:
 * - Unit under test: DatabaseMigrations
 * - Behavior focus: SQL schema migration logic and table transformations.
 * - Observable outcomes: table existence, column schema validity, data migration integrity.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: file system I/O, Room internals, repository logic.
 */
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



import androidx.room3.migration.Migration
import com.lomo.data.local.entity.MemoFileOutboxIdentityPolicy
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/*
 * Behavior Contract:
 * - Unit under test: DatabaseMigrations
 * - Behavior focus: schema evolution preserves active memo-version tables, adds the indexes required by
 *   revision dedupe and paging, retires the removed legacy workspace-history schema, compacts
 *   memo-revision rows down to lightweight previews, normalizes sync metadata / protocol-state tables
 *   for supported remotes, persists shard-level remote reconcile state plus telemetry for S3,
 *   upgrades WebDAV metadata to carry a stable local fingerprint baseline, and migrates memo search
 *   to the application-managed FTS5 external-content index used by the current schema with the FTS
 *   column carrying tokenized search text while the main memo column stays as user-visible plaintext.
 * - Observable outcomes: emitted migration SQL for surviving version-to-version and retained stable-baseline
 *   direct migrations to the current target version, plus FTS index population that emits
 *   per-row INSERTs whose bound values are the tokenized form of each memo's content.
 * - TDD proof: Fails before the fix because the schema target assertion and FTS migration coverage drifted
 *   onto an unsupported FTS4 fork instead of the real FTS5 contract; and (for the FTS-tokenization
 *   refactor) because the migration emitted `UPDATE Lomo SET content = tokens` to the main memo column
 *   and used a bulk `INSERT INTO lomo_fts SELECT id, content FROM Lomo` that copied raw content into the
 *   index without per-row tokenization.
 * - Excludes: real Room open/validation, filesystem side effects, and unrelated query behavior after migration.
 */
/*
 * Test Change Justification (FTS tokenization refactor):
 * - Reason category: product/domain contract changed (separating user-visible memo content from search
 *   index tokens was the diagnosed defect being fixed).
 * - Old behavior/assertion being replaced: `UPDATE Lomo SET content = ? WHERE id = ?` was expected with the
 *   tokenized form ("你 你好 好 memo"), and the FTS index was expected to be populated by a single SQL
 *   `INSERT INTO lomo_fts SELECT id, content FROM Lomo`.
 * - Why old assertion is no longer correct: the UPDATE polluted the main memo column with token strings,
 *   forcing the UI to recover plaintext from rawContent and breaking any consumer that relied on
 *   Memo.content being the original text. The bulk SELECT-INSERT then copied that polluted text into the
 *   index. Both encoded the bug.
 * - Coverage preserved by: the new assertions verify that (a) the migration never UPDATEs Memo.content,
 *   (b) the FTS index is rebuilt with the correct schema, and (c) the per-row INSERT into lomo_fts binds
 *   the SearchTokenizer.tokenize(content) form, locking in the new "main column plaintext, FTS column
 *   tokenized" contract.
 * - Why this is not fitting the test to the implementation: the old SQL is the encoded bug; preserving it
 *   in tests would lock in the diagnosed defect.
 */
/*
 * Test Change Justification (release-window migration policy):
 * - Reason category: migration support contract changed.
 * - Old behavior/assertion being replaced: the effective contract allowed universal direct-to-current
 *   migrations from nearly every historical schema version via generated consolidation migrations.
 * - Why old assertion is no longer correct: the supported upgrade surface is now intentionally limited to
 *   retained stable release DB baselines plus adjacent internal schema hops, so universal direct coverage
 *   would preserve the dead mechanism we are removing.
 * - Coverage preserved by: direct source-baseline assertions, adjacent 52->53 coverage, and explicit absence
 *   checks for unsupported universal direct migrations.
 * - Why this is not fitting the test to the implementation: the support-window reduction is the product
 *   decision being implemented; keeping the old universal contract would reject the intended simplification.
 */
class DatabaseMigrationsTest : DataFunSpec() {
    init {
        test("database version tracks current Report 10 S3 sync schema") {
            `database version tracks current Report 10 S3 sync schema`()
        }

        test("migration list includes direct 55 to 56 upgrade path") { `migration list includes direct 55 to 56 upgrade path`() }

        test("migration list includes direct 56 to 57 pending review split path") {
            `migration list includes direct 56 to 57 pending review split path`()
        }

        test("migration list includes direct 57 to 58 content flag projection path") {
            `migration list includes direct 57 to 58 content flag projection path`()
        }

        test("migration list includes direct 58 to 59 workspace scoped sync state path") {
            `migration list includes direct 58 to 59 workspace scoped sync state path`()
        }

        test("migration list includes direct 59 to 60 statistics projection path") {
            `migration list includes direct 59 to 60 statistics projection path`()
        }

        test("search content backfill rejects schema-required null memo id and content") {
            `search content backfill rejects schema-required null memo id and content`()
        }

        test("stable baseline direct migrations only cover retained released source versions") { `stable baseline direct migrations only cover retained released source versions`() }

        test("migration list no longer includes universal direct current migration from unsupported legacy versions") { `migration list no longer includes universal direct current migration from unsupported legacy versions`() }

        test("stable baseline direct migration builds only final external content fts infrastructure") { `stable baseline direct migration builds only final external content fts infrastructure`() }

        test("migration 48 to 49 creates exact memo image attachment index table") { `migration 48 to 49 creates exact memo image attachment index table`() }

        test("migration 49 to 50 rebuilds fts4 with tokenized content while leaving memo content as plaintext") { `migration 49 to 50 rebuilds fts4 with tokenized content while leaving memo content as plaintext`() }

        test("migration 50 to 51 replaces legacy fts4 with simple application managed fts5") { `migration 50 to 51 replaces legacy fts4 with simple application managed fts5`() }

        test("migration 51 to 52 upgrades to trigger managed external content fts") { `migration 51 to 52 upgrades to trigger managed external content fts`() }

        test("migration 52 to 53 rebuilds memo outbox operation column as integer enum") { `migration 52 to 53 rebuilds memo outbox operation column as integer enum`() }

        test("migration 54 to 55 is an explicit no-op release baseline marker") { `migration 54 to 55 is an explicit no-op release baseline marker`() }

        test("migration 55 to 56 backfills durable memo outbox operation identity") {
            `migration 55 to 56 backfills durable memo outbox operation identity`()
        }

        test("migration 56 to 57 splits pending review sessions out of pending conflict table") {
            `migration 56 to 57 splits pending review sessions out of pending conflict table`()
        }

        test("migration 57 to 58 adds and backfills active memo content flags") {
            `migration 57 to 58 adds and backfills active memo content flags`()
        }

        test("migration 58 to 59 clears unscoped sync state and recreates generation scoped tables") {
            `migration 58 to 59 clears unscoped sync state and recreates generation scoped tables`()
        }

        test("migration 59 to 60 adds and backfills memo statistics projection columns") {
            `migration 59 to 60 adds and backfills memo statistics projection columns`()
        }

        test("local file state schema includes missing confirmation columns") { `local file state schema includes missing confirmation columns`() }

        test("migration 37 to 38 creates s3 incremental protocol and journal tables") { `migration 37 to 38 creates s3 incremental protocol and journal tables`() }

        test("migration 38 to 39 adds memo revision asset fingerprint column and rebuilt index") { `migration 38 to 39 adds memo revision asset fingerprint column and rebuilt index`() }

        test("migration 40 to 41 clears unscoped s3 protocol state") { `migration 40 to 41 clears unscoped s3 protocol state`() }

        test("migration 41 to 42 creates s3 remote shard state table") { `migration 41 to 42 creates s3 remote shard state table`() }

        test("migration 42 to 43 adds s3 remote shard telemetry columns") { `migration 42 to 43 adds s3 remote shard telemetry columns`() }

        test("migration 43 to 44 creates pending sync conflict table") { `migration 43 to 44 creates pending sync conflict table`() }

        test("migration 44 to 45 adds persisted s3 metadata size and fingerprint columns") { `migration 44 to 45 adds persisted s3 metadata size and fingerprint columns`() }

        test("migration 45 to 46 clears unscoped webdav metadata") { `migration 45 to 46 clears unscoped webdav metadata`() }

        test("migration 29 to 30 drops retired workspace history tables") { `migration 29 to 30 drops retired workspace history tables`() }

        test("migration 30 to 31 adds memo revision dedupe indexes") { `migration 30 to 31 adds memo revision dedupe indexes`() }

        test("migration 34 to 35 compacts memo revision rows to previews") { `migration 34 to 35 compacts memo revision rows to previews`() }

        test("migration 35 to 36 creates s3 metadata table") { `migration 35 to 36 creates s3 metadata table`() }

        test("migration 26 to 27 creates webdav metadata table") { `migration 26 to 27 creates webdav metadata table`() }

        test("migration 22 to 23 drops legacy Lomo content index") { `migration 22 to 23 drops legacy Lomo content index`() }

        test("migration 23 to 24 adds and backfills updatedAt") { `migration 23 to 24 adds and backfills updatedAt`() }

        test("migration 24 to 25 adds outbox claim columns") { `migration 24 to 25 adds outbox claim columns`() }

        test("migration 25 to 26 creates memo pin table") { `migration 25 to 26 creates memo pin table`() }
    }


    private fun `database version tracks current Report 10 S3 sync schema`() {
        MEMO_DATABASE_VERSION shouldBe 63
    }

    private fun `migration list includes direct 55 to 56 upgrade path`() {
        (ALL_DATABASE_MIGRATIONS.any { it.startVersion == 55 && it.endVersion == 56 }).shouldBeTrue()
    }

    private fun `migration list includes direct 56 to 57 pending review split path`() {
        (ALL_DATABASE_MIGRATIONS.any { it.startVersion == 56 && it.endVersion == 57 }).shouldBeTrue()
    }

    private fun `migration list includes direct 57 to 58 content flag projection path`() {
        (ALL_DATABASE_MIGRATIONS.any { it.startVersion == 57 && it.endVersion == 58 }).shouldBeTrue()
    }

    private fun `migration list includes direct 58 to 59 workspace scoped sync state path`() {
        (ALL_DATABASE_MIGRATIONS.any { it.startVersion == 58 && it.endVersion == 59 }).shouldBeTrue()
    }

    private fun `migration list includes direct 59 to 60 statistics projection path`() {
        (ALL_DATABASE_MIGRATIONS.any { it.startVersion == 59 && it.endVersion == 60 }).shouldBeTrue()
    }

    private fun `search content backfill rejects schema-required null memo id and content`() {
        val columns = linkedSetOf("id", COLUMN_CONTENT, COLUMN_SEARCH_CONTENT)
        val nullIdDb =
            RecordingSQLiteConnection(
                queryHandler = { sql, _ ->
                    when {
                        sql.contains("sqlite_master") -> mockCursor(true)
                        sql.contains("PRAGMA table_info(`$MEMO_TABLE`)") -> mockColumnsCursor(columns)
                        sql.contains("SELECT `id`, `$COLUMN_CONTENT`, `$COLUMN_SEARCH_CONTENT`") ->
                            queryResult(
                                "id",
                                COLUMN_CONTENT,
                                COLUMN_SEARCH_CONTENT,
                                rows = listOf(rowOf(null, "visible content", "visible content")),
                            )
                        else -> SQLiteQueryResult.EMPTY
                    }
                },
            )
        val nullContentDb =
            RecordingSQLiteConnection(
                queryHandler = { sql, _ ->
                    when {
                        sql.contains("sqlite_master") -> mockCursor(true)
                        sql.contains("PRAGMA table_info(`$MEMO_TABLE`)") -> mockColumnsCursor(columns)
                        sql.contains("SELECT `id`, `$COLUMN_CONTENT`, `$COLUMN_SEARCH_CONTENT`") ->
                            queryResult(
                                "id",
                                COLUMN_CONTENT,
                                COLUMN_SEARCH_CONTENT,
                                rows = listOf(rowOf("memo-null-content", null, "stale")),
                            )
                        else -> SQLiteQueryResult.EMPTY
                    }
                },
            )

        shouldThrow<IllegalArgumentException> {
            backfillMemoSearchContentColumn(nullIdDb)
        }.message shouldContain "Legacy memo id missing during search content backfill"
        shouldThrow<IllegalArgumentException> {
            backfillMemoSearchContentColumn(nullContentDb)
        }.message shouldContain "Legacy memo content missing during search content backfill"
    }

    private fun `stable baseline direct migrations only cover retained released source versions`() {
        STABLE_BASELINE_DIRECT_MIGRATIONS.map(Migration::startVersion) shouldBe StableDatabaseBaselineCatalog.supportedSourceDatabaseVersions()
        (STABLE_BASELINE_DIRECT_MIGRATIONS.all { it.endVersion == MEMO_DATABASE_VERSION }).shouldBeTrue()
    }

    private fun `migration list no longer includes universal direct current migration from unsupported legacy versions`() {
        (ALL_DATABASE_MIGRATIONS.none { it.startVersion == 1 && it.endVersion == MEMO_DATABASE_VERSION }).shouldBeTrue()
        (ALL_DATABASE_MIGRATIONS.none { it.startVersion == 43 && it.endVersion == MEMO_DATABASE_VERSION }).shouldBeTrue()
    }

    private fun `stable baseline direct migration builds only final external content fts infrastructure`() {
        val db = RecordingSQLiteConnection()
        db.queryHandler = { sql, _ ->
            when {
                sql.contains("sqlite_master") -> mockCursor(false)
                sql.contains("PRAGMA table_info") -> mockColumnsCursor(emptySet())
                else -> mockCursor(false)
            }
        }

        STABLE_BASELINE_DIRECT_MIGRATIONS
            .first { it.startVersion == 44 && it.endVersion == MEMO_DATABASE_VERSION }
            .migrateForTest(db)

        db.assertDidNotExecuteSql { sql ->
            sql.contains("USING FTS4", ignoreCase = true) ||
                (sql.contains("USING fts5", ignoreCase = true) &&
                    !sql.contains("content='Lomo'", ignoreCase = true))
        }
        (db.executedStatements.any { statement ->
                statement.sql.contains("USING fts5", ignoreCase = true) &&
                    statement.sql.contains("content='Lomo'", ignoreCase = true) &&
                    statement.sql.contains("content_rowid='rowid'", ignoreCase = true)
            }).shouldBeTrue()
    }

    private fun `migration 48 to 49 creates exact memo image attachment index table`() {
        val db = RecordingSQLiteConnection()

        MIGRATION_48_49.migrateForTest(db)

        verify {
            db.execSQL(
                match {
                    it.contains("CREATE TABLE IF NOT EXISTS `MemoImageAttachment`") &&
                        it.contains("PRIMARY KEY(`memoId`, `imagePath`)")
                },
            )
        }
        verify {
            db.execSQL(match { it.contains("index_MemoImageAttachment_imagePath") })
        }
        verify {
            db.execSQL(
                match {
                    it.contains("INSERT OR IGNORE INTO `MemoImageAttachment`") &&
                        it.contains("FROM `Lomo`") &&
                        it.contains("FROM `LomoTrash`")
                },
            )
        }
    }

    private fun `migration 49 to 50 rebuilds fts4 with tokenized content while leaving memo content as plaintext`() {
        val db = RecordingSQLiteConnection()

        db.queryHandler = { sql, _ ->
            when {
                sql.contains("sqlite_master") -> mockCursor(true)
                sql.contains("SELECT `id`, `content`") && sql.contains("FROM `Lomo`") ->
                    mockMemoContentCursor(
                        rows =
                            listOf(
                                MemoContentRow(
                                    id = "memo-1",
                                    timestamp = 1L,
                                    content = "你好 memo",
                                    rawContent = "- 10:00 你好 memo",
                                    date = "2026_04_19",
                                ),
                            ),
                    )
                else -> mockCursor(false)
            }
        }

        MIGRATION_49_50.migrateForTest(db)

        // Memo.content must NOT be rewritten by the migration. The previous implementation pushed
        // SearchTokenizer.tokenize(content) into the main column, polluting the source-of-truth memo
        // text and forcing the UI to recover plaintext from rawContent. The new contract keeps the
        // main column as user-visible plaintext and only tokenizes content when populating the
        // search index.
        verify(exactly = 0) {
            db.execSQL(match { sql -> sql.contains("UPDATE `Lomo` SET `content`") })
        }
        verify {
            db.execSQL(
                match {
                    it.contains("CREATE VIRTUAL TABLE IF NOT EXISTS `lomo_fts`") &&
                        it.contains("USING FTS4", ignoreCase = true) &&
                        it.contains("memoId") &&
                        it.contains("notindexed=`memoId`")
                },
            )
        }
        verify {
            db.execSQL(
                "INSERT INTO `lomo_fts` (`memoId`, `content`) VALUES (?, ?)",
                match { args -> args.contentEquals(arrayOf("memo-1", "你 你好 好 memo")) },
            )
        }
    }

    private fun `migration 50 to 51 replaces legacy fts4 with simple application managed fts5`() {
        val db = RecordingSQLiteConnection()
        db.queryHandler = { sql, _ ->
            when {
                sql.contains("SELECT 1 FROM sqlite_master") -> {
                    val tableName = Regex("""name='([^']+)'""").find(sql)?.groupValues?.get(1)
                    mockCursor(tableName == MEMO_TABLE)
                }
                sql.contains("SELECT `id`, `content`") && sql.contains("FROM `Lomo`") ->
                    mockMemoContentCursor(
                        rows =
                            listOf(
                                MemoContentRow(
                                    id = "memo-1",
                                    timestamp = 1L,
                                    content = "你好 memo",
                                    rawContent = "- 10:00 你好 memo",
                                    date = "2026_04_19",
                                ),
                            ),
                    )
                else -> mockCursor(false)
            }
        }

        MIGRATION_50_51.migrateForTest(db)

        verify {
            db.execSQL(
                match {
                    it.contains("CREATE VIRTUAL TABLE IF NOT EXISTS `lomo_fts`") &&
                        it.contains("USING fts5") &&
                        it.contains("memoId") &&
                        it.contains("tokenize='unicode61'")
                },
            )
        }
        verify {
            db.execSQL(
                "INSERT INTO `lomo_fts` (`memoId`, `content`) VALUES (?, ?)",
                match { args -> args.contentEquals(arrayOf("memo-1", "你 你好 好 memo")) },
            )
        }
    }

    private fun `migration 51 to 52 upgrades to trigger managed external content fts`() {
        val db = RecordingSQLiteConnection()

        MIGRATION_51_52.migrateForTest(db)

        verify {
            db.execSQL("ALTER TABLE `Lomo` ADD COLUMN `searchContent` TEXT NOT NULL DEFAULT ''")
        }
        verify {
            db.execSQL(
                match {
                    it.contains("CREATE VIRTUAL TABLE IF NOT EXISTS `lomo_fts`") &&
                        it.contains("USING fts5") &&
                        it.contains("`searchContent`") &&
                        it.contains("content='Lomo'") &&
                        it.contains("content_rowid='rowid'")
                },
            )
        }
        verify {
            db.execSQL(
                match {
                    it.contains("CREATE TRIGGER IF NOT EXISTS `lomo_fts_ai`") &&
                        it.contains("INSERT INTO `lomo_fts`") &&
                        it.contains("new.`searchContent`")
                },
            )
        }
        verify {
            db.execSQL(
                match {
                    it.contains("CREATE TRIGGER IF NOT EXISTS `lomo_fts_au`") &&
                        it.contains("'delete'") &&
                        it.contains("new.`searchContent`")
                },
            )
        }
        verify {
            db.execSQL(
                match {
                    it.contains("CREATE TRIGGER IF NOT EXISTS `lomo_fts_ad`") &&
                        it.contains("'delete'")
                },
            )
        }
        verify { db.execSQL("INSERT INTO `lomo_fts`(`lomo_fts`) VALUES ('rebuild')") }
    }

    private fun `migration 52 to 53 rebuilds memo outbox operation column as integer enum`() {
        val db = RecordingSQLiteConnection()
        var observedOperationEnumProjection = false
        val outboxColumns =
            setOf(
                "id",
                "operation",
                "memoId",
                "memoDate",
                "memoTimestamp",
                "memoRawContent",
                "newContent",
                "createRawContent",
                "createdAt",
                "updatedAt",
                "retryCount",
                "lastError",
                "claimToken",
                "claimUpdatedAt",
            )

        db.queryHandler = { sql, _ ->
            when {
                sql.contains("sqlite_master") -> {
                    val tableName = Regex("""name='(\w+)'""").find(sql)?.groupValues?.get(1)
                    mockCursor(tableName == "MemoFileOutbox")
                }

                sql.contains("PRAGMA table_info(`MemoFileOutbox`)") -> {
                    mockColumnsCursor(outboxColumns)
                }

                sql.contains("FROM `MemoFileOutbox_legacy_v22`") -> {
                    observedOperationEnumProjection =
                        sql.contains("CASE") &&
                            sql.contains("WHEN CAST(`operation` AS TEXT) = 'CREATE' THEN 0") &&
                            sql.contains("WHEN CAST(`operation` AS TEXT) = 'UPDATE' THEN 1") &&
                            sql.contains("WHEN CAST(`operation` AS TEXT) = 'DELETE' THEN 2") &&
                            sql.contains("WHEN CAST(`operation` AS TEXT) = 'RESTORE' THEN 3")
                    SQLiteQueryResult.EMPTY
                }

                else -> {
                    mockCursor(false)
                }
            }
        }

        MIGRATION_52_53.migrateForTest(db)

        verify {
            db.execSQL(
                match {
                    it.contains("CREATE TABLE IF NOT EXISTS `MemoFileOutbox`") &&
                        it.contains("`operation` INTEGER NOT NULL") &&
                        it.contains("`operationId` TEXT NOT NULL") &&
                        it.contains("`idempotencyKey` TEXT NOT NULL")
                },
            )
        }
        verify {
            db.execSQL(
                match {
                    it.contains("CREATE UNIQUE INDEX IF NOT EXISTS `index_MemoFileOutbox_idempotencyKey`") &&
                        it.contains("`idempotencyKey`")
                },
            )
        }
        observedOperationEnumProjection shouldBe true
    }

    private fun `migration 54 to 55 is an explicit no-op release baseline marker`() {
        val db = RecordingSQLiteConnection()

        MIGRATION_54_55.migrateForTest(db)

        db.executedStatements shouldBe emptyList()
    }

    private fun `migration 55 to 56 backfills durable memo outbox operation identity`() {
        val db = RecordingSQLiteConnection()
        val outboxColumns =
            setOf(
                "id",
                "operation",
                "memoId",
                "memoDate",
                "memoTimestamp",
                "memoRawContent",
                "newContent",
                "createRawContent",
                "createdAt",
                "updatedAt",
                "retryCount",
                "lastError",
                "claimToken",
                "claimUpdatedAt",
            )
        val rawContent = "- 10:00 delete me"
        val expectedIdentity =
            MemoFileOutboxIdentityPolicy.forDeleteToTrash(
                memoId = "memo_1",
                memoDate = "2026_05_25",
                memoRawContent = rawContent,
            )

        db.queryHandler = { sql, _ ->
            when {
                sql.contains("sqlite_master") -> {
                    val tableName = Regex("""name='(\w+)'""").find(sql)?.groupValues?.get(1)
                    mockCursor(tableName == "MemoFileOutbox")
                }

                sql.contains("PRAGMA table_info(`MemoFileOutbox`)") -> {
                    mockColumnsCursor(outboxColumns)
                }

                sql.contains("PRAGMA index_list(`MemoFileOutbox_legacy_v22`)") -> SQLiteQueryResult.EMPTY

                sql.contains("FROM `MemoFileOutbox_legacy_v22`") -> {
                    queryResult(
                        "id",
                        "operation",
                        "operationId",
                        "idempotencyKey",
                        "memoId",
                        "memoDate",
                        "memoTimestamp",
                        "memoRawContent",
                        "newContent",
                        "createRawContent",
                        "createdAt",
                        "updatedAt",
                        "retryCount",
                        "lastError",
                        "claimToken",
                        "claimUpdatedAt",
                        rows =
                            listOf(
                                rowOf(
                                    7L,
                                    2,
                                    null,
                                    null,
                                    "memo_1",
                                    "2026_05_25",
                                    1_795_478_400_000L,
                                    rawContent,
                                    null,
                                    null,
                                    100L,
                                    200L,
                                    3,
                                    "file failed",
                                    "stale-claim",
                                    300L,
                                ),
                            ),
                    )
                }

                else -> SQLiteQueryResult.EMPTY
            }
        }

        MIGRATION_55_56.migrateForTest(db)

        verify {
            db.execSQL(
                match {
                    it.contains("CREATE TABLE IF NOT EXISTS `MemoFileOutbox`") &&
                        it.contains("`operationId` TEXT NOT NULL") &&
                        it.contains("`idempotencyKey` TEXT NOT NULL")
                },
            )
        }
        db.assertExecutedSqlWithArgs(
            sql =
                """
                INSERT OR IGNORE INTO `MemoFileOutbox` (
                    `id`,
                    `operation`,
                    `operationId`,
                    `idempotencyKey`,
                    `memoId`,
                    `memoDate`,
                    `memoTimestamp`,
                    `memoRawContent`,
                    `newContent`,
                    `createRawContent`,
                    `createdAt`,
                    `updatedAt`,
                    `retryCount`,
                    `lastError`,
                    `claimToken`,
                    `claimUpdatedAt`
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            bindArgs =
                listOf(
                    7L,
                    2,
                    expectedIdentity.operationId,
                    expectedIdentity.idempotencyKey,
                    "memo_1",
                    "2026_05_25",
                    1_795_478_400_000L,
                    rawContent,
                    null,
                    null,
                    100L,
                    200L,
                    3,
                    "file failed",
                    "stale-claim",
                    300L,
                ),
        )
    }

    private fun `migration 56 to 57 splits pending review sessions out of pending conflict table`() {
        val db =
            RecordingSQLiteConnection(
                queryHandler = { sql, _ ->
                    when {
                        sql.contains("sqlite_master") -> mockCursor(sql.contains("pending_sync_conflict"))
                        sql.contains("PRAGMA table_info(`pending_sync_conflict`)") ->
                            mockColumnsCursor(setOf("backend", "session_kind", "timestamp", "payload_json"))
                        else -> SQLiteQueryResult.EMPTY
                    }
                },
            )

        MIGRATION_56_57.migrateForTest(db)

        verify {
            db.execSQL(
                match {
                    it.contains("CREATE TABLE IF NOT EXISTS `pending_sync_review`") &&
                        it.contains("`review_kind` TEXT NOT NULL")
                },
            )
        }
        verify {
            db.execSQL(
                match {
                    it.contains("INSERT OR REPLACE INTO `pending_sync_review`") &&
                        it.contains("SELECT `backend`, `session_kind`, `timestamp`, `payload_json`") &&
                        it.contains("WHERE `session_kind` != 'CONFLICT'")
                },
            )
        }
        verify {
            db.execSQL("ALTER TABLE `pending_sync_conflict` RENAME TO `pending_sync_conflict_legacy_v56`")
        }
        verify {
            db.execSQL(
                match {
                    it.contains("CREATE TABLE IF NOT EXISTS `pending_sync_conflict`") &&
                        it.contains("`payload_json` TEXT NOT NULL") &&
                        !it.contains("`session_kind`")
                },
            )
        }
        verify {
            db.execSQL(
                match {
                    it.contains("INSERT OR REPLACE INTO `pending_sync_conflict`") &&
                        it.contains("FROM `pending_sync_conflict_legacy_v56`") &&
                        it.contains("WHERE `session_kind` = 'CONFLICT'")
                },
            )
        }
        verify {
            db.execSQL("DROP TABLE IF EXISTS `pending_sync_conflict_legacy_v56`")
        }
    }

    private fun `migration 57 to 58 adds and backfills active memo content flags`() {
        val updateSql =
            """
            UPDATE `Lomo`
            SET `hasTodo` = ?, `hasAttachment` = ?, `hasUrl` = ?
            WHERE `id` = ?
            """.trimIndent()
        val db =
            RecordingSQLiteConnection(
                queryHandler = { sql, _ ->
                    when {
                        sql.contains("sqlite_master") -> mockCursor(true)
                        sql.contains("PRAGMA table_info(`$MEMO_TABLE`)") ->
                            mockColumnsCursor(setOf("id", COLUMN_CONTENT))

                        sql.contains("SELECT `id`, `$COLUMN_CONTENT`, `$COLUMN_HAS_TODO`") ->
                            mockMemoContentFlagCursor(
                                rows =
                                    listOf(
                                        MemoContentFlagRow(id = "todo", content = "  -\t[x] indented task"),
                                        MemoContentFlagRow(id = "audio", content = "[voice](voice_001.m4a)"),
                                        MemoContentFlagRow(id = "wiki", content = "![[diagram.png]]"),
                                        MemoContentFlagRow(id = "geo", content = "geo:31.2304,121.4737"),
                                        MemoContentFlagRow(id = "mailto", content = "mailto:hello@example.com"),
                                        MemoContentFlagRow(id = "email", content = "hello@example.com"),
                                    ),
                            )

                        else -> SQLiteQueryResult.EMPTY
                    }
                },
            )

        MIGRATION_57_58.migrateForTest(db)

        verify {
            db.execSQL("ALTER TABLE `Lomo` ADD COLUMN `hasTodo` INTEGER NOT NULL DEFAULT 0")
        }
        verify {
            db.execSQL("ALTER TABLE `Lomo` ADD COLUMN `hasAttachment` INTEGER NOT NULL DEFAULT 0")
        }
        verify {
            db.execSQL("ALTER TABLE `Lomo` ADD COLUMN `hasUrl` INTEGER NOT NULL DEFAULT 0")
        }
        db.assertExecutedSqlWithArgs(updateSql, listOf(true, false, false, "todo"))
        db.assertExecutedSqlWithArgs(updateSql, listOf(false, true, false, "audio"))
        db.assertExecutedSqlWithArgs(updateSql, listOf(false, true, false, "wiki"))
        listOf("geo", "mailto", "email").forEach { memoId ->
            db.assertExecutedSqlWithArgs(updateSql, listOf(false, false, true, memoId))
        }
    }

    private fun `migration 58 to 59 clears unscoped sync state and recreates generation scoped tables`() {
        val db = RecordingSQLiteConnection()

        MIGRATION_58_59.migrateForTest(db)

        listOf(
            "webdav_sync_metadata",
            "webdav_local_fingerprint",
            "webdav_local_change_journal",
            "s3_sync_metadata",
            "s3_remote_index",
            "s3_remote_shard_state",
            "s3_sync_protocol_state",
            "s3_local_change_journal",
            "pending_sync_conflict",
            "pending_sync_review",
        ).forEach { tableName ->
            verify(exactly = 1) {
                db.execSQL("DROP TABLE IF EXISTS `$tableName`")
            }
        }
        assertGenerationScopedCreate(
            db = db,
            tableName = "webdav_sync_metadata",
            keyColumn = "relative_path",
        )
        assertGenerationScopedCreate(
            db = db,
            tableName = "s3_sync_metadata",
            keyColumn = "relative_path",
        )
        assertGenerationScopedCreate(
            db = db,
            tableName = "s3_remote_index",
            keyColumn = "relative_path",
        )
        assertGenerationScopedCreate(
            db = db,
            tableName = "s3_sync_protocol_state",
            keyColumn = "id",
        )
        assertGenerationScopedCreate(
            db = db,
            tableName = "pending_sync_conflict",
            keyColumn = "backend",
        )
        assertGenerationScopedCreate(
            db = db,
            tableName = "pending_sync_review",
            keyColumn = "backend",
        )
        db.assertDidNotExecuteSql { sql ->
            sql.contains("INSERT OR REPLACE INTO `webdav_sync_metadata`") ||
                sql.contains("INSERT OR REPLACE INTO `s3_sync_metadata`") ||
                sql.contains("INSERT OR REPLACE INTO `pending_sync_conflict`") ||
                sql.contains("INSERT OR REPLACE INTO `pending_sync_review`")
        }
    }

    private fun `migration 59 to 60 adds and backfills memo statistics projection columns`() {
        val updateSql =
            """
            UPDATE `$MEMO_TABLE`
            SET `$COLUMN_STATISTICS_WORD_COUNT` = ?, `$COLUMN_STATISTICS_CHARACTER_COUNT` = ?
            WHERE `id` = ?
            """.trimIndent()
        val db =
            RecordingSQLiteConnection(
                queryHandler = { sql, _ ->
                    when {
                        sql.contains("sqlite_master") -> mockCursor(true)
                        sql.contains("PRAGMA table_info(`$MEMO_TABLE`)") ->
                            mockColumnsCursor(setOf("id", COLUMN_TIMESTAMP, COLUMN_CONTENT))

                        sql.contains("SELECT `id`, `$COLUMN_TIMESTAMP`, `$COLUMN_CONTENT`") ->
                            mockMemoStatisticsProjectionCursor(
                                rows =
                                    listOf(
                                        MemoStatisticsProjectionRow(
                                            id = "memo-words",
                                            timestamp = 1_795_478_400_000L,
                                            content = "alpha beta",
                                        ),
                                        MemoStatisticsProjectionRow(
                                            id = "memo-cjk",
                                            timestamp = 1_795_478_401_000L,
                                            content = "苏格拉底 memo",
                                        ),
                                    ),
                            )

                        else -> SQLiteQueryResult.EMPTY
                    }
                },
            )

        MIGRATION_59_60.migrateForTest(db)

        verify {
            db.execSQL("ALTER TABLE `$MEMO_TABLE` ADD COLUMN `$COLUMN_STATISTICS_WORD_COUNT` INTEGER NOT NULL DEFAULT 0")
        }
        verify {
            db.execSQL(
                "ALTER TABLE `$MEMO_TABLE` ADD COLUMN `$COLUMN_STATISTICS_CHARACTER_COUNT` INTEGER NOT NULL DEFAULT 0",
            )
        }
        db.assertExecutedSqlWithArgs(updateSql, listOf(2, 10, "memo-words"))
        db.assertExecutedSqlWithArgs(updateSql, listOf(2, 9, "memo-cjk"))
    }

    private fun `local file state schema includes missing confirmation columns`() {
        val db = RecordingSQLiteConnection()

        MIGRATION_36_37.migrateForTest(db)

        verify {
            db.execSQL(
                match {
                    it.contains("CREATE TABLE IF NOT EXISTS `local_file_state`") &&
                        it.contains("`missing_since` INTEGER") &&
                        it.contains("`missing_count` INTEGER NOT NULL") &&
                        it.contains("`last_seen_at` INTEGER NOT NULL")
                },
            )
        }
    }

    private fun `migration 37 to 38 creates s3 incremental protocol and journal tables`() {
        val db = RecordingSQLiteConnection()

        MIGRATION_37_38.migrateForTest(db)

        verify {
            db.execSQL(match { it.contains("CREATE TABLE IF NOT EXISTS `s3_sync_protocol_state`") })
        }
        verify {
            db.execSQL(match { it.contains("CREATE TABLE IF NOT EXISTS `s3_local_change_journal`") })
        }
        verify {
            db.execSQL(match { it.contains("index_s3_local_change_journal_updated_at") })
        }
    }

    private fun `migration 38 to 39 adds memo revision asset fingerprint column and rebuilt index`() {
        val db = RecordingSQLiteConnection()
        db.queryHandler = { sql, _ ->
            when {
                sql.contains("PRAGMA table_info(`memo_revision`)") -> {
                    mockColumnsCursor(
                        setOf(
                            "revisionId",
                            "memoId",
                            "createdAt",
                            "lifecycleState",
                            "contentHash",
                            "rawMarkdownBlobHash",
                        ),
                    )
                }

                else -> {
                    mockCursor(false)
                }
            }
        }

        MIGRATION_38_39.migrateForTest(db)

        verify(exactly = 1) {
            db.execSQL("ALTER TABLE `memo_revision` ADD COLUMN `assetFingerprint` TEXT")
        }
        verify(exactly = 1) {
            db.execSQL("DROP INDEX IF EXISTS `index_memo_revision_memoId_lifecycleState_contentHash_rawMarkdownBlobHash`")
        }
        verify(exactly = 1) {
            db.execSQL(
                match {
                    it.contains("CREATE INDEX IF NOT EXISTS `index_memo_revision_memoId_lifecycleState_contentHash_rawMarkdownBlobHash_assetFingerprint`") &&
                        it.contains(
                            "ON `memo_revision` (`memoId`, `lifecycleState`, `contentHash`, `rawMarkdownBlobHash`, `assetFingerprint`)",
                        )
                },
            )
        }
    }

    private fun `migration 40 to 41 clears unscoped s3 protocol state`() {
        val db = RecordingSQLiteConnection()
        val legacyColumns =
            setOf(
                "id",
                "protocol_version",
                "last_successful_sync_at",
                "last_manifest_revision",
                "last_fast_sync_at",
                "last_reconcile_at",
                "last_full_remote_scan_at",
                "indexed_local_file_count",
                "indexed_remote_file_count",
                "local_mode_fingerprint",
                "remote_scan_cursor",
                "scan_epoch",
            )

        db.queryHandler = { sql, _ ->
            when {
                sql.contains("sqlite_master") -> {
                    val name = Regex("""name='(\w+)'""").find(sql)?.groupValues?.get(1)
                    mockCursor(name == "s3_sync_protocol_state")
                }

                sql.contains("PRAGMA table_info(`s3_sync_protocol_state_legacy_v41`)") -> {
                    mockColumnsCursor(legacyColumns)
                }

                sql.contains("PRAGMA table_info(`s3_sync_protocol_state`)") -> {
                    mockColumnsCursor(legacyColumns)
                }

                else -> {
                    mockCursor(false)
                }
            }
        }

        MIGRATION_40_41.migrateForTest(db)

        verify(exactly = 1) {
            db.execSQL("DROP TABLE IF EXISTS `s3_sync_protocol_state`")
        }
        verify {
            db.execSQL(
                match {
                    it.contains("CREATE TABLE IF NOT EXISTS `s3_sync_protocol_state`") &&
                        !it.contains("last_manifest_revision") &&
                        it.contains("`workspace_generation` TEXT NOT NULL") &&
                        it.contains("`remote_scan_cursor` TEXT") &&
                        it.contains("`scan_epoch` INTEGER NOT NULL DEFAULT 0") &&
                        it.contains("PRIMARY KEY(`workspace_generation`, `id`)")
                },
            )
        }
        db.assertDidNotExecuteSql { sql ->
            sql.contains("INSERT OR REPLACE INTO `s3_sync_protocol_state`")
        }
    }

    private fun `migration 41 to 42 creates s3 remote shard state table`() {
        val db = RecordingSQLiteConnection()

        MIGRATION_41_42.migrateForTest(db)

        verify {
            db.execSQL(
                match {
                    it.contains("CREATE TABLE IF NOT EXISTS `s3_remote_shard_state`") &&
                        it.contains("`bucket_id` TEXT NOT NULL") &&
                        it.contains("`relative_prefix` TEXT") &&
                        it.contains("`last_scanned_at` INTEGER NOT NULL") &&
                        it.contains("`last_object_count` INTEGER NOT NULL") &&
                        it.contains("`last_duration_ms` INTEGER NOT NULL") &&
                        it.contains("`last_change_count` INTEGER NOT NULL")
                },
            )
        }
    }

    private fun `migration 42 to 43 adds s3 remote shard telemetry columns`() {
        val db = RecordingSQLiteConnection()

        MIGRATION_42_43.migrateForTest(db)

        verify(exactly = 1) {
            db.execSQL("ALTER TABLE `s3_remote_shard_state` ADD COLUMN `idle_scan_streak` INTEGER NOT NULL DEFAULT 0")
        }
        verify {
            db.execSQL(
                match {
                    it.contains("ALTER TABLE `s3_remote_shard_state`") &&
                        it.contains("`last_verification_attempt_count` INTEGER NOT NULL DEFAULT 0")
                },
            )
        }
        verify {
            db.execSQL(
                match {
                    it.contains("ALTER TABLE `s3_remote_shard_state`") &&
                        it.contains("`last_verification_failure_count` INTEGER NOT NULL DEFAULT 0")
                },
            )
        }
    }

    private fun `migration 43 to 44 creates pending sync conflict table`() {
        val db = RecordingSQLiteConnection()

        MIGRATION_43_44.migrateForTest(db)

        verify {
            db.execSQL(
                match {
                    it.contains("CREATE TABLE IF NOT EXISTS `pending_sync_conflict`") &&
                        it.contains("`backend` TEXT NOT NULL") &&
                        it.contains("`session_kind` TEXT NOT NULL") &&
                        it.contains("`timestamp` INTEGER NOT NULL") &&
                        it.contains("`payload_json` TEXT NOT NULL")
                },
            )
        }
    }

    private fun `migration 44 to 45 adds persisted s3 metadata size and fingerprint columns`() {
        val db = RecordingSQLiteConnection()

        MIGRATION_44_45.migrateForTest(db)

        verify(exactly = 1) {
            db.execSQL("ALTER TABLE `s3_sync_metadata` ADD COLUMN `local_size` INTEGER")
        }
        verify(exactly = 1) {
            db.execSQL("ALTER TABLE `s3_sync_metadata` ADD COLUMN `remote_size` INTEGER")
        }
        verify(exactly = 1) {
            db.execSQL("ALTER TABLE `s3_sync_metadata` ADD COLUMN `local_fingerprint` TEXT")
        }
    }

    private fun `migration 45 to 46 clears unscoped webdav metadata`() {
        val db = RecordingSQLiteConnection()
        val legacyColumns =
            setOf(
                "relative_path",
                "remote_path",
                "etag",
                "remote_last_modified",
                "local_last_modified",
                "last_synced_at",
                "last_resolved_direction",
                "last_resolved_reason",
            )
        db.queryHandler = { sql, _ ->
            when {
                sql.contains("sqlite_master") -> mockCursor(true)
                sql.contains("PRAGMA table_info(`webdav_sync_metadata_legacy_v46`)") -> mockColumnsCursor(legacyColumns)
                sql.contains("PRAGMA table_info(`webdav_sync_metadata`)") -> mockColumnsCursor(legacyColumns)
                else -> mockCursor(false)
            }
        }

        MIGRATION_45_46.migrateForTest(db)

        verify(exactly = 1) {
            db.execSQL("DROP TABLE IF EXISTS `webdav_sync_metadata`")
        }
        verify {
            db.execSQL(
                match {
                    it.contains("CREATE TABLE IF NOT EXISTS `webdav_sync_metadata`") &&
                        it.contains("`workspace_generation` TEXT NOT NULL") &&
                        it.contains("`local_fingerprint` TEXT") &&
                        it.contains("PRIMARY KEY(`workspace_generation`, `relative_path`)")
                },
            )
        }
        db.assertDidNotExecuteSql { sql ->
            sql.contains("INSERT OR REPLACE INTO `webdav_sync_metadata`")
        }
    }

    private fun `migration 29 to 30 drops retired workspace history tables`() {
        val db = RecordingSQLiteConnection()

        MIGRATION_29_30.migrateForTest(db)

        verify(exactly = 1) { db.execSQL("DROP TABLE IF EXISTS `workspace_mutation`") }
        verify(exactly = 1) { db.execSQL("DROP TABLE IF EXISTS `workspace_head`") }
        verify(exactly = 1) { db.execSQL("DROP TABLE IF EXISTS `workspace_snapshot_entry`") }
        verify(exactly = 1) { db.execSQL("DROP TABLE IF EXISTS `workspace_snapshot`") }
        verify(exactly = 1) { db.execSQL("DROP TABLE IF EXISTS `snapshot_blob`") }
        verify(exactly = 0) { db.execSQL(match { it.contains("DROP TABLE IF EXISTS `memo_revision`") }) }
        verify(exactly = 0) { db.execSQL(match { it.contains("DROP TABLE IF EXISTS `memo_revision_asset`") }) }
    }

    private fun `migration 30 to 31 adds memo revision dedupe indexes`() {
        val db = RecordingSQLiteConnection()

        MIGRATION_30_31.migrateForTest(db)

        verify(exactly = 1) {
            db.execSQL(
                match {
                    it.contains("CREATE INDEX IF NOT EXISTS `index_memo_revision_memoId_lifecycleState_contentHash_rawMarkdownBlobHash_assetFingerprint`") &&
                        it.contains(
                            "ON `memo_revision` (`memoId`, `lifecycleState`, `contentHash`, `rawMarkdownBlobHash`, `assetFingerprint`)",
                        )
                },
            )
        }
        verify(exactly = 1) {
            db.execSQL(
                match {
                    it.contains("CREATE INDEX IF NOT EXISTS `index_memo_revision_memoId_createdAt_revisionId`") &&
                        it.contains("ON `memo_revision` (`memoId`, `createdAt`, `revisionId`)")
                },
            )
        }
        verify(exactly = 1) {
            db.execSQL(
                match {
                    it.contains("CREATE INDEX IF NOT EXISTS `index_memo_revision_asset_revisionId_logicalPath`") &&
                        it.contains("ON `memo_revision_asset` (`revisionId`, `logicalPath`)")
                },
            )
        }
    }

    private fun `migration 34 to 35 compacts memo revision rows to previews`() {
        val db = RecordingSQLiteConnection()

        val migration =
            ALL_DATABASE_MIGRATIONS.first {
                it.startVersion == 34 && it.endVersion == 35
            }

        migration.migrateForTest(db)

        verify(exactly = 1) {
            db.execSQL(
                match {
                    it.contains("UPDATE `memo_revision`") &&
                        it.contains("substr(`memoContent`, 1, 277) || '...'") &&
                        it.contains("WHERE length(`memoContent`) > 280")
                },
            )
        }
    }

    private fun `migration 35 to 36 creates s3 metadata table`() {
        val db = RecordingSQLiteConnection()

        MIGRATION_35_36.migrateForTest(db)

        verify {
            db.execSQL(match { it.contains("CREATE TABLE IF NOT EXISTS `s3_sync_metadata`") })
        }
    }

    private fun `migration 26 to 27 creates webdav metadata table`() {
        val db = RecordingSQLiteConnection()

        MIGRATION_26_27.migrateForTest(db)

        verify {
            db.execSQL(match { it.contains("CREATE TABLE IF NOT EXISTS `webdav_sync_metadata`") })
        }
    }

    private fun `migration 22 to 23 drops legacy Lomo content index`() {
        val db = RecordingSQLiteConnection()

        MIGRATION_22_23.migrateForTest(db)

        verify(exactly = 1) {
            db.execSQL("DROP INDEX IF EXISTS `index_Lomo_content`")
        }
    }

    private fun `migration 23 to 24 adds and backfills updatedAt`() {
        val db = RecordingSQLiteConnection()
        db.queryHandler = { sql, _ ->
            when {
                sql.contains("sqlite_master") -> mockCursor(true)
                sql.contains("PRAGMA table_info(`Lomo`)") -> mockColumnsCursor(setOf("id", "timestamp"))
                sql.contains("PRAGMA table_info(`LomoTrash`)") -> mockColumnsCursor(setOf("id", "timestamp"))
                else -> mockCursor(false)
            }
        }

        MIGRATION_23_24.migrateForTest(db)

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

    private fun `migration 24 to 25 adds outbox claim columns`() {
        val db = RecordingSQLiteConnection()
        val outboxColumns =
            setOf(
                "id",
                "operation",
                "memoId",
                "memoDate",
                "memoTimestamp",
                "memoRawContent",
                "newContent",
                "createRawContent",
                "createdAt",
                "updatedAt",
                "retryCount",
                "lastError",
            )

        db.queryHandler = { sql, _ ->
            when {
                sql.contains("sqlite_master") -> {
                    val tableName = Regex("""name='(\w+)'""").find(sql)?.groupValues?.get(1)
                    mockCursor(tableName == "MemoFileOutbox")
                }

                sql.contains("PRAGMA table_info(`MemoFileOutbox`)") -> {
                    mockColumnsCursor(outboxColumns)
                }

                else -> {
                    mockCursor(false)
                }
            }
        }

        MIGRATION_24_25.migrateForTest(db)

        verify {
            db.execSQL(
                match {
                    it.contains("CREATE TABLE IF NOT EXISTS `MemoFileOutbox`") &&
                        it.contains("`claimToken` TEXT") &&
                        it.contains("`claimUpdatedAt` INTEGER")
                },
            )
        }
    }

    private fun `migration 25 to 26 creates memo pin table`() {
        val db = RecordingSQLiteConnection()

        MIGRATION_25_26.migrateForTest(db)

        verify {
            db.execSQL(
                match {
                    it.contains("CREATE TABLE IF NOT EXISTS `MemoPin`") &&
                        it.contains("`memoId` TEXT NOT NULL") &&
                        it.contains("`pinnedAt` INTEGER NOT NULL")
                },
            )
        }
        verify(exactly = 1) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_MemoPin_pinnedAt` ON `MemoPin` (`pinnedAt`)")
        }
    }

    private fun mockCursor(hasRow: Boolean): SQLiteQueryResult =
        if (hasRow) {
            queryResult("present", rows = listOf(rowOf(1)))
        } else {
            SQLiteQueryResult.EMPTY
        }

    private fun assertGenerationScopedCreate(
        db: RecordingSQLiteConnection,
        tableName: String,
        keyColumn: String,
    ) {
        db.assertExecutedSql { sql ->
            sql.contains("CREATE TABLE IF NOT EXISTS `$tableName`") &&
                sql.contains("`workspace_generation` TEXT NOT NULL") &&
                sql.contains("PRIMARY KEY(`workspace_generation`, `$keyColumn`)")
        }
    }

    private fun mockColumnsCursor(columns: Set<String>): SQLiteQueryResult =
        queryResult(
            "name",
            rows = columns.map { columnName -> rowOf(columnName) },
        )

    private fun mockMemoContentCursor(rows: List<MemoContentRow>): SQLiteQueryResult =
        queryResult(
            "id",
            "timestamp",
            "content",
            "rawContent",
            "date",
            rows =
                rows.map { row ->
                    rowOf(
                        row.id,
                        row.timestamp,
                        row.content,
                        row.rawContent,
                        row.date,
                    )
                },
        )

    private fun mockMemoContentFlagCursor(rows: List<MemoContentFlagRow>): SQLiteQueryResult =
        queryResult(
            "id",
            COLUMN_CONTENT,
            COLUMN_HAS_TODO,
            COLUMN_HAS_ATTACHMENT,
            COLUMN_HAS_URL,
            rows =
                rows.map { row ->
                    rowOf(
                        row.id,
                        row.content,
                        row.hasTodo,
                        row.hasAttachment,
                        row.hasUrl,
                    )
                },
        )

    private fun mockMemoStatisticsProjectionCursor(rows: List<MemoStatisticsProjectionRow>): SQLiteQueryResult =
        queryResult(
            "id",
            COLUMN_TIMESTAMP,
            COLUMN_CONTENT,
            COLUMN_STATISTICS_WORD_COUNT,
            COLUMN_STATISTICS_CHARACTER_COUNT,
            rows =
                rows.map { row ->
                    rowOf(
                        row.id,
                        row.timestamp,
                        row.content,
                        row.wordCount,
                        row.characterCount,
                    )
                },
        )

    private data class MemoContentRow(
        val id: String,
        val timestamp: Long,
        val content: String,
        val rawContent: String,
        val date: String,
    )

    private data class MemoContentFlagRow(
        val id: String,
        val content: String,
        val hasTodo: Int = 0,
        val hasAttachment: Int = 0,
        val hasUrl: Int = 0,
    )

    private data class MemoStatisticsProjectionRow(
        val id: String,
        val timestamp: Long,
        val content: String,
        val wordCount: Int = 0,
        val characterCount: Int = 0,
    )
}
