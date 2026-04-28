package com.lomo.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: DatabaseMigrations
 * - Behavior focus: schema evolution preserves active memo-version tables, adds the indexes required by
 *   revision dedupe and paging, retires the removed legacy workspace-history schema, compacts
 *   memo-revision rows down to lightweight previews, normalizes sync metadata / protocol-state tables
 *   for supported remotes, persists shard-level remote reconcile state plus telemetry for S3,
 *   upgrades WebDAV metadata to carry a stable local fingerprint baseline, and migrates memo search
 *   to the application-managed FTS5 external-content index used by the current schema with the FTS
 *   column carrying tokenized search text while the main memo column stays as user-visible plaintext.
 * - Observable outcomes: emitted migration SQL for surviving version-to-version and consolidation paths,
 *   plus direct-migration coverage to the current target version, and FTS index population that emits
 *   per-row INSERTs whose bound values are the tokenized form of each memo's content.
 * - Red phase: Fails before the fix because the schema target assertion and FTS migration coverage drifted
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
class DatabaseMigrationsTest {
    @Test
    fun `database version remains 53 for memo file outbox enum persistence`() {
        assertEquals(53, MEMO_DATABASE_VERSION)
    }

    @Test
    fun `migration list includes direct 52 to 53 upgrade path`() {
        assertTrue(
            ALL_DATABASE_MIGRATIONS.any { it.startVersion == 52 && it.endVersion == 53 },
        )
    }

    @Test
    fun `migration 48 to 49 creates exact memo image attachment index table`() {
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

    @Test
    fun `migration 49 to 50 rebuilds fts4 with tokenized content while leaving memo content as plaintext`() {
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

    @Test
    fun `migration 50 to 51 replaces legacy fts4 with simple application managed fts5`() {
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

    @Test
    fun `migration 51 to 52 upgrades to trigger managed external content fts`() {
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

    @Test
    fun `migration 52 to 53 rebuilds memo outbox operation column as integer enum`() {
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

        MIGRATION_52_53.migrateForTest(db)

        verify {
            db.execSQL(
                match {
                    it.contains("CREATE TABLE IF NOT EXISTS `MemoFileOutbox`") &&
                        it.contains("`operation` INTEGER NOT NULL")
                },
            )
        }
        verify {
            db.execSQL(
                match {
                    it.contains("INSERT OR REPLACE INTO `MemoFileOutbox`") &&
                        it.contains("CASE") &&
                        it.contains("WHEN CAST(`operation` AS TEXT) = 'CREATE' THEN 0") &&
                        it.contains("WHEN CAST(`operation` AS TEXT) = 'UPDATE' THEN 1") &&
                        it.contains("WHEN CAST(`operation` AS TEXT) = 'DELETE' THEN 2") &&
                        it.contains("WHEN CAST(`operation` AS TEXT) = 'RESTORE' THEN 3")
                },
            )
        }
    }


    @Test
    fun `local file state schema includes missing confirmation columns`() {
        val db = RecordingSQLiteConnection()

        val migration =
            ALL_DATABASE_MIGRATIONS.first {
                it.startVersion == 36 && it.endVersion == MEMO_DATABASE_VERSION
            }

        migration.migrateForTest(db)

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

    @Test
    fun `migration 37 to 38 creates s3 incremental protocol and journal tables`() {
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

    @Test
    fun `migration 38 to 39 adds memo revision asset fingerprint column and rebuilt index`() {
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

    @Test
    fun `migration 40 to 41 rebuilds s3 protocol state without manifest column`() {
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
            db.execSQL("ALTER TABLE `s3_sync_protocol_state` RENAME TO `s3_sync_protocol_state_legacy_v41`")
        }
        verify {
            db.execSQL(
                match {
                    it.contains("CREATE TABLE IF NOT EXISTS `s3_sync_protocol_state`") &&
                        !it.contains("last_manifest_revision") &&
                        it.contains("`remote_scan_cursor` TEXT") &&
                        it.contains("`scan_epoch` INTEGER NOT NULL DEFAULT 0")
                },
            )
        }
        verify {
            db.execSQL(
                match {
                    it.contains("INSERT OR REPLACE INTO `s3_sync_protocol_state`") &&
                        !it.contains("last_manifest_revision") &&
                        it.contains("`remote_scan_cursor`") &&
                        it.contains("`scan_epoch`") &&
                        it.contains("FROM `s3_sync_protocol_state_legacy_v41`")
                },
            )
        }
    }

    @Test
    fun `migration 41 to 42 creates s3 remote shard state table`() {
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

    @Test
    fun `migration 42 to 43 adds s3 remote shard telemetry columns`() {
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

    @Test
    fun `migration 43 to 44 creates pending sync conflict table`() {
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

    @Test
    fun `migration 44 to 45 adds persisted s3 metadata size and fingerprint columns`() {
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

    @Test
    fun `migration 45 to 46 normalizes webdav metadata with local fingerprint column`() {
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
            db.execSQL("ALTER TABLE `webdav_sync_metadata` RENAME TO `webdav_sync_metadata_legacy_v46`")
        }
        verify {
            db.execSQL(
                match {
                    it.contains("CREATE TABLE IF NOT EXISTS `webdav_sync_metadata`") &&
                        it.contains("`local_fingerprint` TEXT")
                },
            )
        }
        verify {
            db.execSQL(
                match {
                    it.contains("INSERT OR REPLACE INTO `webdav_sync_metadata`") &&
                        it.contains("NULL") &&
                        it.contains("FROM `webdav_sync_metadata_legacy_v46`")
                },
            )
        }
        verify(exactly = 2) {
            db.execSQL("DROP TABLE IF EXISTS `webdav_sync_metadata_legacy_v46`")
        }
    }

    @Test
    fun `migration 29 to 30 drops retired workspace history tables`() {
        val db = RecordingSQLiteConnection()

        val migration =
            ALL_DATABASE_MIGRATIONS.first {
                it.startVersion == 29 && it.endVersion == MEMO_DATABASE_VERSION
            }
        migration.migrateForTest(db)

        verify(exactly = 1) { db.execSQL("DROP TABLE IF EXISTS `workspace_mutation`") }
        verify(exactly = 1) { db.execSQL("DROP TABLE IF EXISTS `workspace_head`") }
        verify(exactly = 1) { db.execSQL("DROP TABLE IF EXISTS `workspace_snapshot_entry`") }
        verify(exactly = 1) { db.execSQL("DROP TABLE IF EXISTS `workspace_snapshot`") }
        verify(exactly = 1) { db.execSQL("DROP TABLE IF EXISTS `snapshot_blob`") }
        verify(exactly = 0) { db.execSQL(match { it.contains("DROP TABLE IF EXISTS `memo_revision`") }) }
        verify(exactly = 0) { db.execSQL(match { it.contains("DROP TABLE IF EXISTS `memo_revision_asset`") }) }
    }

    @Test
    fun `migration 30 to 31 adds memo revision dedupe indexes`() {
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

    @Test
    fun `migration 34 to 35 compacts memo revision rows to previews`() {
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

    @Test
    fun `migration 35 to 36 creates s3 metadata table`() {
        val db = RecordingSQLiteConnection()

        MIGRATION_35_36.migrateForTest(db)

        verify {
            db.execSQL(match { it.contains("CREATE TABLE IF NOT EXISTS `s3_sync_metadata`") })
        }
    }

    @Test
    fun `migration 26 to 27 creates webdav metadata table`() {
        val db = RecordingSQLiteConnection()

        MIGRATION_26_27.migrateForTest(db)

        verify {
            db.execSQL(match { it.contains("CREATE TABLE IF NOT EXISTS `webdav_sync_metadata`") })
        }
    }

    @Test
    fun `migration 22 to 23 drops legacy Lomo content index`() {
        val db = RecordingSQLiteConnection()

        MIGRATION_22_23.migrateForTest(db)

        verify(exactly = 1) {
            db.execSQL("DROP INDEX IF EXISTS `index_Lomo_content`")
        }
    }

    @Test
    fun `migration 23 to 24 adds and backfills updatedAt`() {
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

    @Test
    fun `migration 24 to 25 adds outbox claim columns`() {
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

    @Test
    fun `migration 25 to 26 creates memo pin table`() {
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
        val db = RecordingSQLiteConnection()
        val v7MemoColumns = setOf("id", "timestamp", "content", "rawContent", "date", "tags", "imageUrls", "isDeleted")
        val v22MemoColumns = setOf("id", "timestamp", "content", "rawContent", "date", "tags", "imageUrls")

        // Track table existence as migration creates/drops tables.
        val existingTables = mutableSetOf("memos")
        val tableNamePattern = Regex("""`(\w+)`""")

        db.onExec = { sql, _ ->
            val tableName = tableNamePattern.find(sql)?.groupValues?.get(1)
            if (tableName != null) {
                when {
                    sql.trimStart().startsWith("CREATE TABLE") ||
                        sql.trimStart().startsWith("CREATE VIRTUAL TABLE") -> existingTables.add(tableName)

                    sql.trimStart().startsWith("DROP TABLE") -> existingTables.remove(tableName)
                }
            }
        }

        db.queryHandler = { sql, _ ->
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

                else -> {
                    mockCursor(false)
                }
            }
        }

        val migration =
            ALL_DATABASE_MIGRATIONS.first {
                it.startVersion == 7 && it.endVersion == MEMO_DATABASE_VERSION
            }
        migration.migrateForTest(db)

        // Phase A: memos split into Lomo + LomoTrash
        verify { db.execSQL(match { it.contains("INSERT OR REPLACE INTO `Lomo`") && it.contains("isDeleted") }) }
        verify { db.execSQL(match { it.contains("INSERT OR REPLACE INTO `LomoTrash`") && it.contains("isDeleted") }) }

        // Phase B: content index dropped
        verify { db.execSQL("DROP INDEX IF EXISTS `index_Lomo_content`") }

        // Phase C: updatedAt added
        verify { db.execSQL("ALTER TABLE `Lomo` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0") }
        verify { db.execSQL("ALTER TABLE `LomoTrash` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0") }

        // Phase E: memo pin table created
        verify { db.execSQL(match { it.contains("CREATE TABLE IF NOT EXISTS `MemoPin`") }) }

        // Phase I: memo revision indexes tightened
        verify {
            db.execSQL(
                match {
                    it.contains("CREATE INDEX IF NOT EXISTS `index_memo_revision_memoId_lifecycleState_contentHash_rawMarkdownBlobHash_assetFingerprint`") &&
                        it.contains(
                            "ON `memo_revision` (`memoId`, `lifecycleState`, `contentHash`, `rawMarkdownBlobHash`, `assetFingerprint`)",
                        )
                },
            )
        }
        verify {
            db.execSQL(
                match {
                    it.contains("CREATE INDEX IF NOT EXISTS `index_memo_revision_asset_revisionId_logicalPath`") &&
                        it.contains("ON `memo_revision_asset` (`revisionId`, `logicalPath`)")
                },
            )
        }
    }

    @Test
    fun `consolidation from v21 normalizes tables and applies all phases`() {
        val db = RecordingSQLiteConnection()
        val v21MemoColumns = setOf("id", "timestamp", "content", "rawContent", "date", "tags", "imageUrls")
        val localFileStateColumns = setOf("filename", "isTrash", "saf_uri", "last_known_modified_time")
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

        val existingTables = mutableSetOf("Lomo", "LomoTrash", "local_file_state", "MemoFileOutbox")
        val tableNamePattern = Regex("""`(\w+)`""")

        db.onExec = { sql, _ ->
            val tableName = tableNamePattern.find(sql)?.groupValues?.get(1)
            if (tableName != null) {
                when {
                    sql.trimStart().startsWith("CREATE TABLE") ||
                        sql.trimStart().startsWith("CREATE VIRTUAL TABLE") -> {
                        existingTables.add(tableName)
                    }

                    sql.trimStart().startsWith("DROP TABLE") -> {
                        existingTables.remove(tableName)
                    }

                    sql.trimStart().startsWith("ALTER TABLE") && sql.contains("RENAME TO") -> {
                        val newName =
                            sql.substringAfterLast("`").let {
                                sql.substringBeforeLast("`").substringAfterLast("`")
                            }
                        existingTables.remove(tableName)
                        if (newName.isNotBlank()) existingTables.add(newName)
                    }
                }
            }
        }

        db.queryHandler = { sql, _ ->
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

                else -> {
                    mockCursor(false)
                }
            }
        }

        val migration =
            ALL_DATABASE_MIGRATIONS.first {
                it.startVersion == 21 && it.endVersion == MEMO_DATABASE_VERSION
            }
        migration.migrateForTest(db)

        // Phase A: tables normalized (rename + rebuild)
        verify { db.execSQL(match { it.contains("Lomo") && it.contains("RENAME TO") }) }

        // Phase B: content index dropped
        verify { db.execSQL("DROP INDEX IF EXISTS `index_Lomo_content`") }

        // Phase C: updatedAt added
        verify { db.execSQL("ALTER TABLE `Lomo` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0") }
        verify { db.execSQL("ALTER TABLE `LomoTrash` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0") }

        // Phase E: memo pin table created
        verify { db.execSQL(match { it.contains("CREATE TABLE IF NOT EXISTS `MemoPin`") }) }

        // Phase I: memo revision indexes tightened
        verify {
            db.execSQL(
                match {
                    it.contains("CREATE INDEX IF NOT EXISTS `index_memo_revision_memoId_lifecycleState_contentHash_rawMarkdownBlobHash_assetFingerprint`") &&
                        it.contains(
                            "ON `memo_revision` (`memoId`, `lifecycleState`, `contentHash`, `rawMarkdownBlobHash`, `assetFingerprint`)",
                        )
                },
            )
        }
        verify {
            db.execSQL(
                match {
                    it.contains("CREATE INDEX IF NOT EXISTS `index_memo_revision_asset_revisionId_logicalPath`") &&
                        it.contains("ON `memo_revision_asset` (`revisionId`, `logicalPath`)")
                },
            )
        }
    }

    @Test
    fun `consolidation to current schema does not recreate retired workspace history tables`() {
        val db = RecordingSQLiteConnection()

        db.queryHandler = { sql, _ ->
            when {
                sql.contains("sqlite_master") -> mockCursor(false)
                else -> mockCursor(false)
            }
        }

        val migration =
            ALL_DATABASE_MIGRATIONS.first {
                it.startVersion == 1 && it.endVersion == MEMO_DATABASE_VERSION
            }
        migration.migrateForTest(db)

        verify(exactly = 0) { db.execSQL(match { it.contains("CREATE TABLE IF NOT EXISTS `workspace_snapshot`") }) }
        verify(exactly = 0) { db.execSQL(match { it.contains("CREATE TABLE IF NOT EXISTS `workspace_snapshot_entry`") }) }
        verify(exactly = 0) { db.execSQL(match { it.contains("CREATE TABLE IF NOT EXISTS `workspace_head`") }) }
        verify(exactly = 0) { db.execSQL(match { it.contains("CREATE TABLE IF NOT EXISTS `workspace_mutation`") }) }
        verify(exactly = 0) { db.execSQL(match { it.contains("CREATE TABLE IF NOT EXISTS `snapshot_blob`") }) }
    }

    @Test
    fun `consolidation from v7 migrates file_sync_metadata to local_file_state`() {
        val db = RecordingSQLiteConnection()
        val v7MemoColumns = setOf("id", "timestamp", "content", "rawContent", "date", "tags", "imageUrls", "isDeleted")
        val fileSyncColumns = setOf("filename", "lastModified", "isTrash")

        val existingTables = mutableSetOf("memos", "file_sync_metadata")
        val tableNamePattern = Regex("""`(\w+)`""")

        db.onExec = { sql, _ ->
            val tableName = tableNamePattern.find(sql)?.groupValues?.get(1)
            if (tableName != null) {
                when {
                    sql.trimStart().startsWith("CREATE TABLE") ||
                        sql.trimStart().startsWith("CREATE VIRTUAL TABLE") -> existingTables.add(tableName)

                    sql.trimStart().startsWith("DROP TABLE") -> existingTables.remove(tableName)
                }
            }
        }

        db.queryHandler = { sql, _ ->
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

                else -> {
                    mockCursor(false)
                }
            }
        }

        val migration =
            ALL_DATABASE_MIGRATIONS.first {
                it.startVersion == 7 && it.endVersion == MEMO_DATABASE_VERSION
            }
        migration.migrateForTest(db)

        verify { db.execSQL(match { it.contains("INSERT OR REPLACE INTO `local_file_state`") && it.contains("file_sync_metadata") }) }
    }

    @Test
    fun `consolidation drops all legacy tables`() {
        val db = RecordingSQLiteConnection()

        db.queryHandler = { sql, _ ->
            when {
                sql.contains("sqlite_master") -> mockCursor(false)
                else -> mockCursor(false)
            }
        }

        val migration =
            ALL_DATABASE_MIGRATIONS.first {
                it.startVersion == 1 && it.endVersion == MEMO_DATABASE_VERSION
            }
        migration.migrateForTest(db)

        val legacyTables = listOf("memos", "image_cache", "tags", "memo_tag_cross_ref", "memos_fts", "file_sync_metadata")
        for (table in legacyTables) {
            verify { db.execSQL("DROP TABLE IF EXISTS `$table`") }
        }
    }

    private fun mockCursor(hasRow: Boolean): SQLiteQueryResult =
        if (hasRow) {
            queryResult("present", rows = listOf(rowOf(1)))
        } else {
            SQLiteQueryResult.EMPTY
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

    private data class MemoContentRow(
        val id: String,
        val timestamp: Long,
        val content: String,
        val rawContent: String,
        val date: String,
    )
}
