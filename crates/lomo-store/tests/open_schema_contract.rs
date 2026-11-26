//! Behavior Contract (P3-01)
//!
//! Capability: open the rebuildable `SQLite` projection with WAL/FK/busy/version/integrity and
//! reject unknown/higher schema versions without destructive downgrade.
//!
//! Scenarios:
//! - Given a new workspace root, when `Store::open` runs, then `foreign_keys=ON`, `journal_mode=wal`,
//!   `busy` timeout is applied, `user_version` is the live schema, and quick integrity is ok.
//! - Given a v1 projection, when it is opened, then the SAF ledger, reminder projection, durable
//!   body/history columns, and indexed lifecycle projection are added atomically without discarding
//!   rows or inventing content that the old projection never persisted.
//! - Given a database with a higher unknown `user_version`, when open is attempted, then open fails
//!   closed with `unknown_schema_version` and does not downgrade.
//! - Given a v9 cache, upgrading creates a readable pre-upgrade backup and retains every history
//!   row while admitting distinct content-addressed records at the same generation.
//! - Given a v13 cache whose renderer dropped loose-list task markers, when open migrates to v14,
//!   then `has_todo` is re-derived from the committed `body` (0 -> 1 repairs only) so task
//!   queries stop reading stale false negatives.
//!
//! Test Change Justification:
//! Reason category: schema fixture maintenance.
//! Old behavior/assertion being replaced: historical fixtures retained current-schema triggers.
//! Why old assertion is no longer correct: v16 row triggers reference lifecycle columns absent in v1.
//! Coverage preserved by: all row preservation, backup, migration and unknown-schema assertions.
//! Why this is not fitting the test to the implementation: removing v16 triggers makes each
//! downgraded fixture match the historical schema being tested.
//! Observable outcomes: `OpenInfo` fields and structured open errors.
//! TDD proof: RED on 2026-08-17 because schema v3 had no durable complete SAF body column.
//! Excludes: tokenizer, query, transactions, rebuild (later packages).
//! Test Change Justification: the live schema advances to v11 so reconcile-scoping derived
//! tables (`file_listing`, `history_attachment_ref`, `purged_memo`) are created atomically on
//! upgrade; fixtures that fake an older schema must drop those objects first, and old
//! projection rows must survive the atomic upgrade.

mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use std::path::Path;

    use super::support::use_external_content_fts;

    use lomo_core::ErrorCategory;
    use lomo_store::{STORE_SCHEMA_VERSION, Store, open_store};
    use rusqlite::Connection;
    use tempfile::tempdir;

    fn remove_v16_triggers(connection: &Connection) {
        use_external_content_fts(connection);
        connection.execute_batch("DROP TRIGGER memo_stats_insert; DROP TRIGGER memo_stats_delete;
            DROP TRIGGER memo_stats_update; DROP TRIGGER tag_stats_insert; DROP TRIGGER tag_stats_delete; DROP INDEX idx_memo_pending_operation; DROP INDEX idx_memo_reminders; DROP INDEX idx_memo_gallery; DROP INDEX idx_memo_empty_time_part; ALTER TABLE memo DROP COLUMN image_urls_json; ALTER TABLE memo DROP COLUMN time_part;")
            .expect("historical schemas have no v16 triggers or v18 columns");
    }

    #[test]
    fn open_applies_wal_fk_busy_version_and_integrity() {
        let dir = tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open store");
        let info = store.open_info();
        assert!(info.foreign_keys, "foreign_keys must be ON");
        assert_eq!(info.journal_mode, "wal");
        assert_eq!(info.user_version, STORE_SCHEMA_VERSION);
        assert!(info.busy_timeout_ms >= 1000);
        assert!(info.integrity_ok);
        assert!(info.database_path.ends_with("store.db"));
        assert!(
            info.database_path
                .to_string_lossy()
                .contains(".lomo-sqlite"),
            "sqlite must not live under .lomo/"
        );
        drop(store);
        // Regression: an existing database already at the live schema version must be
        // admitted untouched — the migration suffix for v11 would otherwise be empty.
        let reopened = Store::open(dir.path()).expect("reopen");
        assert_eq!(reopened.open_info().user_version, STORE_SCHEMA_VERSION);
    }

    #[test]
    fn checkpointed_wal_releases_excess_disk_space_when_reused() {
        use std::fs;

        let dir = tempdir().expect("tempdir");
        let opened = open_store(dir.path()).expect("open store");
        let connection = &opened.connection;
        let wal = opened.database_path.with_file_name("store.db-wal");
        let max_retained_bytes = 1024 * 1024;
        connection
            .execute_batch(
                "CREATE TABLE wal_probe (payload BLOB);
                 INSERT INTO wal_probe VALUES (zeroblob(3145728));
                 PRAGMA wal_checkpoint(PASSIVE);",
            )
            .expect("write and checkpoint large WAL");
        assert!(fs::metadata(&wal).expect("large WAL").len() > max_retained_bytes);

        connection
            .execute("INSERT INTO wal_probe VALUES (X'00')", [])
            .expect("reuse checkpointed WAL");

        assert!(fs::metadata(&wal).expect("reused WAL").len() <= max_retained_bytes);
    }

    /*
     * Test Change Justification:
     * Reason category: Schema v22 cutover (SQLite FTS5 `memo_fts` retired in favor of lazy Tantivy).
     * Old behavior/assertion being replaced: Queried raw `bm25(memo_fts)` SQL after upgrading a v20 DB.
     * Why old assertion is no longer correct: Schema v22 drops `memo_fts` and `memo.search_content`,
     *   installs `search_changelog` triggers + `search_generation`, and serves fulltext search from
     *   Tantivy rebuilt lazily from `memo`.
     * Coverage preserved by: Verifying `memo_fts` and `memo.search_content` are removed on upgrade,
     *   `search_changelog` + `search_generation` exist, and Tantivy returns/ranks all surviving rows
     *   including after `memo` deletion.
     * Why this is not fitting the test to the implementation: Proves real legacy v20 upgrade and search
     *   continuity rather than retaining deleted SQLite FTS5 tables.
     */
    fn seed_legacy_v20_fts_db(database: &Path) {
        std::fs::create_dir_all(database.parent().expect("database directory"))
            .expect("create legacy directory");
        let legacy = Connection::open(database).expect("open legacy database");
        legacy
            .execute_batch(
                "CREATE TABLE memo(
                    rowid INTEGER PRIMARY KEY, memo_id TEXT NOT NULL UNIQUE,
                    source_path TEXT NOT NULL DEFAULT '2026_09_01.md',
                    time_part TEXT NOT NULL DEFAULT '10:00:00',
                    file_fingerprint TEXT NOT NULL DEFAULT 'fp',
                    has_todo INTEGER NOT NULL DEFAULT 0,
                    has_url INTEGER NOT NULL DEFAULT 0,
                    has_attachment INTEGER NOT NULL DEFAULT 0,
                    is_pinned INTEGER NOT NULL DEFAULT 0,
                    is_trashed INTEGER NOT NULL DEFAULT 0,
                    created_at_ms INTEGER NOT NULL DEFAULT 1000,
                    updated_at_ms INTEGER NOT NULL DEFAULT 1000,
                    body_preview TEXT NOT NULL DEFAULT '',
                    body TEXT,
                    word_count INTEGER NOT NULL DEFAULT 0,
                    char_count INTEGER NOT NULL DEFAULT 0,
                    reminders_json TEXT NOT NULL DEFAULT '[]',
                    image_urls_json TEXT NOT NULL DEFAULT '[]',
                    content_revision INTEGER NOT NULL DEFAULT 1,
                    search_content TEXT NOT NULL DEFAULT '',
                    pending_operation_id TEXT
                 );
                 CREATE TABLE tag(id INTEGER PRIMARY KEY NOT NULL, name TEXT NOT NULL UNIQUE);
                 CREATE TABLE memo_tag(
                    memo_id TEXT NOT NULL REFERENCES memo(memo_id) ON DELETE CASCADE,
                    tag_id INTEGER NOT NULL REFERENCES tag(id) ON DELETE CASCADE,
                    PRIMARY KEY (memo_id, tag_id)
                 );
                 CREATE TABLE store_meta(key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
                 INSERT INTO store_meta(key, value) VALUES
                    ('high_water_revision', '3'),
                    ('event_sequence', '3');
                 CREATE VIRTUAL TABLE memo_fts USING fts5(
                    search_content, content='memo', content_rowid='rowid', tokenize='unicode61'
                 );
                 INSERT INTO memo(rowid, memo_id, body_preview, body, search_content, pending_operation_id) VALUES
                    (1,'both','你好 needle needle needle','你好 needle needle needle','你 好 你好 needle needle needle',NULL),
                    (2,'other','one needle','one needle','one needle',NULL),
                    (3,'unindexed','alpha beta needle','alpha beta needle','alpha beta needle',NULL);
                 INSERT INTO memo_fts(rowid, search_content)
                    SELECT rowid, search_content FROM memo WHERE rowid < 3;
                 PRAGMA user_version = 20;",
            )
            .expect("seed v20 projection and FTS");
    }

    #[test]
    fn v20_upgrade_removes_duplicate_search_text_and_migrates_search_to_tantivy() {
        let root = tempdir().expect("projection root");
        let database = lomo_store::database_path(root.path());
        seed_legacy_v20_fts_db(&database);

        let upgraded = open_store(root.path()).expect("upgrade projection");
        let duplicated_columns: i64 = upgraded
            .connection
            .query_row(
                "SELECT COUNT(*) FROM pragma_table_info('memo') WHERE name='search_content'",
                [],
                |row| row.get(0),
            )
            .expect("inspect migrated memo table");
        assert_eq!(duplicated_columns, 0);
        let fts_tables: i64 = upgraded
            .connection
            .query_row(
                "SELECT COUNT(*) FROM sqlite_master WHERE name='memo_fts'",
                [],
                |row| row.get(0),
            )
            .expect("inspect sqlite_master for memo_fts");
        assert_eq!(fts_tables, 0);
        let changelog_tables: i64 = upgraded
            .connection
            .query_row(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='search_changelog'",
                [],
                |row| row.get(0),
            )
            .expect("inspect sqlite_master for search_changelog");
        assert_eq!(changelog_tables, 1);
        drop(upgraded);

        let store = Store::open(root.path()).expect("open upgraded store");
        let page = store
            .query_memos(
                &lomo_store::MemoQuery {
                    search_text: Some("needle".to_owned()),
                    filters: lomo_store::MemoFilters::default(),
                    sort: lomo_store::MemoSort::default(),
                },
                None,
                lomo_core::PageSize::new(10).expect("page"),
            )
            .expect("tantivy search after v20->v22 upgrade");
        assert_eq!(page.items.len(), 3);
        assert_eq!(
            page.items.first().map(|item| item.memo_id.as_str()),
            Some("both")
        );

        drop(store);
        let raw = open_store(root.path()).expect("reopen raw connection");
        raw.connection
            .execute("DELETE FROM memo WHERE memo_id='both'", [])
            .expect("delete ranked memo via changelog trigger");
        drop(raw);

        let reopened = Store::open(root.path()).expect("reopen upgraded store");
        let remaining = reopened
            .query_memos(
                &lomo_store::MemoQuery {
                    search_text: Some("needle".to_owned()),
                    filters: lomo_store::MemoFilters::default(),
                    sort: lomo_store::MemoSort::default(),
                },
                None,
                lomo_core::PageSize::new(10).expect("page"),
            )
            .expect("tantivy incremental sync after delete");
        let remaining_ids: Vec<&str> = remaining
            .items
            .iter()
            .map(|item| item.memo_id.as_str())
            .collect();
        assert_eq!(remaining_ids, vec!["other", "unindexed"]);
    }

    #[test]
    fn unknown_higher_schema_version_fails_closed_without_downgrade() {
        let dir = tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("create v1");
        let db_path = store.open_info().database_path;
        drop(store);

        {
            let conn = Connection::open(&db_path).expect("open raw");
            conn.pragma_update(None, "user_version", 99u32)
                .expect("bump version");
        }

        let err = open_store(Path::new(dir.path())).expect_err("higher schema must fail");
        assert_eq!(err.category(), ErrorCategory::Validation);
        assert_eq!(err.code(), "unknown_schema_version");

        let conn = Connection::open(&db_path).expect("reopen raw");
        let version: i64 = conn
            .query_row("PRAGMA user_version", [], |row| row.get(0))
            .expect("read version");
        assert_eq!(version, 99);
    }

    #[test]
    fn v1_projection_migrates_to_v2_without_losing_rows() {
        let dir = tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("create current schema");
        let db_path = store.open_info().database_path;
        drop(store);

        {
            let conn = Connection::open(&db_path).expect("open raw");
            remove_v16_triggers(&conn);
            conn.execute(
                "INSERT INTO memo( \
                 memo_id,source_path,file_fingerprint,created_at_ms,updated_at_ms,content_revision \
                 ) VALUES('v1-memo','2026-08-04.md',?1,1,1,1)",
                ["0".repeat(64)],
            )
            .expect("seed v1 row");
            conn.execute("DROP TABLE saf_mutation_operation", [])
                .expect("remove v2 table");
            conn.execute("DROP TABLE file_listing", [])
                .expect("remove v11 listing snapshot");
            conn.execute("DROP TABLE history_attachment_ref", [])
                .expect("remove v11 history attachment index");
            conn.execute("DROP TABLE purged_memo", [])
                .expect("remove v11 purge tombstone index");
            conn.execute("DROP INDEX idx_revision_record", [])
                .expect("remove v11 record lookup index");
            conn.execute("DROP INDEX idx_memo_active_pinned_created", [])
                .expect("remove v8 pinned-created index");
            conn.execute("DROP INDEX idx_memo_active_pinned_updated", [])
                .expect("remove v8 pinned-updated index");
            conn.execute("DROP INDEX idx_memo_source_path", [])
                .expect("remove v8 source index");
            conn.execute("DROP INDEX idx_memo_created", [])
                .expect("remove v8 created index");
            conn.execute("DROP INDEX idx_memo_updated", [])
                .expect("remove v8 updated index");
            conn.execute("ALTER TABLE memo DROP COLUMN reminders_json", [])
                .expect("remove v3 column");
            conn.execute("ALTER TABLE memo DROP COLUMN body", [])
                .expect("remove v4 column");
            conn.execute("ALTER TABLE memo DROP COLUMN pending_operation_id", [])
                .expect("remove v6 column");
            conn.execute("ALTER TABLE memo DROP COLUMN word_count", [])
                .expect("remove v7 word count column");
            conn.execute("ALTER TABLE memo DROP COLUMN char_count", [])
                .expect("remove v7 char count column");
            conn.execute("ALTER TABLE memo DROP COLUMN is_pinned", [])
                .expect("remove v8 pin projection column");
            conn.execute("ALTER TABLE memo DROP COLUMN is_trashed", [])
                .expect("remove v8 trash projection column");
            conn.execute("ALTER TABLE memo_trash DROP COLUMN record_digest", [])
                .expect("remove v12 trash attestation column");
            conn.execute("ALTER TABLE revision_index DROP COLUMN content", [])
                .expect("remove v5 content column");
            conn.execute(
                "ALTER TABLE revision_index DROP COLUMN file_fingerprint",
                [],
            )
            .expect("remove v5 fingerprint column");
            conn.execute(
                "UPDATE store_meta SET value='41' WHERE key='high_water_revision'",
                [],
            )
            .expect("seed previously readable projection revision");
            conn.pragma_update(None, "user_version", 1u32)
                .expect("mark v1");
        }

        let migrated = Store::open(dir.path()).expect("migrate v1");
        assert_eq!(migrated.open_info().user_version, STORE_SCHEMA_VERSION);
        let conn = Connection::open(migrated.open_info().database_path).expect("inspect migrated");
        let memo_count: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM memo WHERE memo_id='v1-memo'",
                [],
                |row| row.get(0),
            )
            .expect("read memo");
        let ledger_exists: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM sqlite_master \
                 WHERE type='table' AND name='saf_mutation_operation'",
                [],
                |row| row.get(0),
            )
            .expect("read schema");
        assert_eq!(memo_count, 1);
        assert_eq!(ledger_exists, 1);
        let migrated_body: Option<String> = conn
            .query_row("SELECT body FROM memo WHERE memo_id='v1-memo'", [], |row| {
                row.get(0)
            })
            .expect("read migrated body state");
        assert_eq!(
            migrated_body, None,
            "migration must not invent source bytes"
        );
        assert_eq!(
            migrated.high_water_revision(),
            0,
            "an incomplete migrated body projection must not remain read-admissible",
        );
    }

    #[test]
    fn open_info_and_workspace_root_are_stable_after_reopen() {
        let dir = tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        let root = store.workspace_root().to_path_buf();
        assert_eq!(root, dir.path());
        assert_eq!(store.high_water_revision(), 0);
        assert_eq!(store.event_sequence(), 0);
        let info = store.open_info();
        assert!(info.integrity_ok);
        drop(store);
        let again = Store::open(dir.path()).expect("reopen");
        assert_eq!(again.workspace_root(), dir.path());
        assert_eq!(again.open_info().user_version, STORE_SCHEMA_VERSION);
    }

    #[test]
    fn v9_upgrade_preserves_a_readable_backup_and_allows_concurrent_history_generations() {
        let dir = tempdir().expect("private projection directory");
        let opened = open_store(dir.path()).expect("initial schema");
        let database = opened.database_path.clone();
        drop(opened);
        let connection = Connection::open(&database).expect("construct historical cache");
        remove_v16_triggers(&connection);
        connection
            .execute_batch(
                "DROP TABLE revision_index;
             CREATE TABLE revision_index(memo_id TEXT NOT NULL, revision INTEGER NOT NULL,
                 history_record_id TEXT NOT NULL, created_at_ms INTEGER NOT NULL,
                 content TEXT, file_fingerprint TEXT, PRIMARY KEY(memo_id,revision));
             INSERT INTO revision_index VALUES('memo',2,'legacy-r2',1000,'original','digest');
             DROP TABLE file_listing;
             DROP TABLE history_attachment_ref;
             DROP TABLE purged_memo;
             ALTER TABLE memo_trash DROP COLUMN record_digest;
             PRAGMA user_version=9;",
            )
            .expect("v9 history shape");
        drop(connection);
        let upgraded = open_store(dir.path()).expect("upgrade v9");
        assert_eq!(upgraded.user_version, STORE_SCHEMA_VERSION);
        let inspect = Connection::open(&database).expect("inspect upgraded projection");
        inspect
            .execute(
                "INSERT INTO revision_index VALUES('memo',2,'other-branch',2000,'branch','digest')",
                [],
            )
            .expect("equal generation with a different record ID is distinct");
        let count: i64 = inspect
            .query_row("SELECT COUNT(*) FROM revision_index", [], |row| row.get(0))
            .expect("history count");
        assert_eq!(count, 2);
        let backups = std::fs::read_dir(
            database
                .parent()
                .expect("database parent")
                .join("upgrade-backups"),
        )
        .expect("pre-upgrade backup directory");
        let backup_path = backups
            .map(|entry| entry.expect("backup entry").path())
            .next()
            .expect("backup file");
        let backup = Connection::open(&backup_path).expect("readable backup");
        let version: u32 = backup
            .pragma_query_value(None, "user_version", |row| row.get(0))
            .expect("backup schema");
        assert_eq!(version, 9);
        let original: String = backup
            .query_row("SELECT content FROM revision_index", [], |row| row.get(0))
            .expect("original snapshot row");
        assert_eq!(original, "original");
    }

    #[test]
    fn v13_upgrade_heals_has_todo_false_negatives_from_committed_body() {
        let dir = tempdir().expect("private projection directory");
        let opened = open_store(dir.path()).expect("initial schema");
        let database = opened.database_path.clone();
        drop(opened);
        let connection = Connection::open(&database).expect("construct v13 cache");
        remove_v16_triggers(&connection);
        connection
            .execute_batch(
                "INSERT INTO memo(memo_id, source_path, file_fingerprint, has_todo, has_url, \
                 has_attachment, created_at_ms, updated_at_ms, body_preview, body, \
                 search_content, word_count, char_count, content_revision, reminders_json, \
                 is_pinned, is_trashed) \
                 VALUES('task-memo','2026_10_12.md','fp1',0,0,0,1,1,'p',\
                 'plan\n- [ ] open task\n- [x] done task\n#t','s',1,1,1,'[]',0,0);\
                 INSERT INTO memo(memo_id, source_path, file_fingerprint, has_todo, has_url, \
                 has_attachment, created_at_ms, updated_at_ms, body_preview, body, \
                 search_content, word_count, char_count, content_revision, reminders_json, \
                 is_pinned, is_trashed) \
                 VALUES('plain-memo','2026_10_12.md','fp1',0,0,0,2,2,'p',\
                 'plain prose','s',1,1,1,'[]',0,0);\
                 INSERT INTO memo(memo_id, source_path, file_fingerprint, has_todo, has_url, \
                 has_attachment, created_at_ms, updated_at_ms, body_preview, body, \
                 search_content, word_count, char_count, content_revision, reminders_json, \
                 is_pinned, is_trashed) \
                 VALUES('no-body-memo','2026_10_12.md','fp1',0,0,0,3,3,'p',\
                 NULL,'s',1,1,1,'[]',0,0);\
                 PRAGMA user_version=13;",
            )
            .expect("v13 stale projection rows");
        drop(connection);
        let upgraded = open_store(dir.path()).expect("upgrade v13");
        assert_eq!(upgraded.user_version, STORE_SCHEMA_VERSION);
        let inspect = Connection::open(&database).expect("inspect upgraded projection");
        let task_flag: i64 = inspect
            .query_row(
                "SELECT has_todo FROM memo WHERE memo_id='task-memo'",
                [],
                |row| row.get(0),
            )
            .expect("healed flag");
        assert_eq!(task_flag, 1, "task-bearing body must flip has_todo to 1");
        let plain_flag: i64 = inspect
            .query_row(
                "SELECT has_todo FROM memo WHERE memo_id='plain-memo'",
                [],
                |row| row.get(0),
            )
            .expect("plain flag");
        assert_eq!(plain_flag, 0, "unmarked body must stay 0");
        let null_body_flag: i64 = inspect
            .query_row(
                "SELECT has_todo FROM memo WHERE memo_id='no-body-memo'",
                [],
                |row| row.get(0),
            )
            .expect("null body flag");
        assert_eq!(null_body_flag, 0, "a NULL body must not invent facts");
    }

    #[test]
    fn v17_upgrade_adds_time_part_and_invalidates_listing_digest_for_rescan() {
        let dir = tempdir().expect("private projection directory");
        let opened = open_store(dir.path()).expect("initial schema");
        let database = opened.database_path.clone();
        drop(opened);
        let connection = Connection::open(&database).expect("construct v17 cache");
        use_external_content_fts(&connection);
        connection
            .execute_batch(
                "DROP INDEX idx_memo_empty_time_part;\
                 DROP INDEX idx_memo_gallery;\
                 ALTER TABLE memo DROP COLUMN image_urls_json;\
                 ALTER TABLE memo DROP COLUMN time_part;\
                 INSERT INTO memo(memo_id, source_path, file_fingerprint, has_todo, has_url, \
                 has_attachment, created_at_ms, updated_at_ms, body_preview, body, \
                 search_content, word_count, char_count, content_revision, reminders_json, \
                 is_pinned, is_trashed) \
                 VALUES('v17-memo','2026_10_12.md','fp1',0,0,0,1,1,'p','body','s',1,1,1,'[]',0,0);\
                 UPDATE store_meta SET value='5' WHERE key='high_water_revision';\
                 INSERT OR REPLACE INTO store_meta(key, value) VALUES('workspace_listing_digest', 'stale');\
                 PRAGMA user_version=17;",
            )
            .expect("v17 projection row");
        drop(connection);
        let upgraded = open_store(dir.path()).expect("upgrade v17");
        assert_eq!(upgraded.user_version, STORE_SCHEMA_VERSION);
        let inspect = Connection::open(&database).expect("inspect upgraded projection");
        let time_part: String = inspect
            .query_row(
                "SELECT time_part FROM memo WHERE memo_id='v17-memo'",
                [],
                |row| row.get(0),
            )
            .expect("migrated time_part column");
        assert_eq!(time_part, "");
        let revision: String = inspect
            .query_row(
                "SELECT value FROM store_meta WHERE key='high_water_revision'",
                [],
                |row| row.get(0),
            )
            .expect("high_water_revision");
        assert_eq!(revision, "0");
        let digest_rows: i64 = inspect
            .query_row(
                "SELECT COUNT(*) FROM store_meta WHERE key='workspace_listing_digest'",
                [],
                |row| row.get(0),
            )
            .expect("listing digest count");
        assert_eq!(digest_rows, 0);
    }

    #[test]
    fn v18_upgrade_backfills_ordered_image_urls_without_forcing_rescan() {
        let dir = tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open v19 store");
        let db_path = store.open_info().database_path;
        drop(store);

        let connection = Connection::open(&db_path).expect("raw sqlite");
        use_external_content_fts(&connection);
        connection
            .execute_batch(
                "DROP INDEX idx_memo_empty_time_part;\
                 DROP INDEX idx_memo_gallery;\
                 ALTER TABLE memo DROP COLUMN image_urls_json;\
                 INSERT INTO memo(memo_id, source_path, time_part, file_fingerprint, has_todo, has_url, \
                 has_attachment, created_at_ms, updated_at_ms, body_preview, body, \
                 search_content, word_count, char_count, content_revision, reminders_json, \
                 is_pinned, is_trashed) VALUES\
                 ('v18-active','2026_10_12.md','09:30:00','fp1',0,1,1,300,300,'p',\
                  '![second](images/z.jpg)\n![ext](https://example.com/pic.png)\n![first](images/a.webp)\n![voice](voice/clip.m4a)',\
                  's',1,1,1,'[]',0,0),\
                 ('v18-trashed','2026_10_13.md','09:31:00','fp2',0,0,1,200,200,'p',\
                  '![trashed](images/trashed.png)','s',1,1,1,'[]',0,1),\
                 ('v18-plain','2026_10_14.md','09:32:00','fp3',0,0,0,100,100,'p','no attachments','s',1,1,1,'[]',0,0);\
                 UPDATE store_meta SET value='7' WHERE key='high_water_revision';\
                 INSERT OR REPLACE INTO store_meta(key, value) VALUES('workspace_listing_digest', 'keep-v18-digest');\
                 PRAGMA user_version=18;",
            )
            .expect("seed v18 schema");
        drop(connection);

        let opened = open_store(dir.path()).expect("upgrade v18 to v19");
        assert_eq!(opened.user_version, STORE_SCHEMA_VERSION);
        drop(opened);

        let inspect = Connection::open(&db_path).expect("inspect upgraded db");
        let revision: String = inspect
            .query_row(
                "SELECT value FROM store_meta WHERE key='high_water_revision'",
                [],
                |row| row.get(0),
            )
            .expect("revision");
        assert_eq!(revision, "7");
        let digest: String = inspect
            .query_row(
                "SELECT value FROM store_meta WHERE key='workspace_listing_digest'",
                [],
                |row| row.get(0),
            )
            .expect("listing digest");
        assert_eq!(digest, "keep-v18-digest");
        let gallery_index_count: i64 = inspect
            .query_row(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_memo_gallery'",
                [],
                |row| row.get(0),
            )
            .expect("gallery index count");
        assert_eq!(gallery_index_count, 1);
        drop(inspect);

        let upgraded = Store::open(dir.path()).expect("open upgraded store");
        let page = upgraded
            .query_memos(
                &lomo_store::MemoQuery {
                    search_text: None,
                    filters: lomo_store::MemoFilters {
                        include_trash: true,
                        ..lomo_store::MemoFilters::default()
                    },
                    sort: lomo_store::MemoSort::default(),
                },
                None,
                lomo_core::PageSize::new(10).expect("page size"),
            )
            .expect("query upgraded memos");
        let [active, trashed, plain] = page.items.as_slice() else {
            panic!("expected three upgraded rows, got {}", page.items.len());
        };
        assert_eq!(active.memo_id, "v18-active");
        assert_eq!(
            active.image_urls,
            vec![
                "images/z.jpg".to_owned(),
                "https://example.com/pic.png".to_owned(),
                "images/a.webp".to_owned(),
            ]
        );
        assert_eq!(trashed.memo_id, "v18-trashed");
        assert_eq!(trashed.image_urls, vec!["images/trashed.png".to_owned()]);
        assert_eq!(plain.memo_id, "v18-plain");
        assert_eq!(plain.image_urls, Vec::<String>::new());
    }

    #[test]
    fn corrupt_image_urls_json_fails_closed() {
        let dir = tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open store");
        let db_path = store.open_info().database_path;
        drop(store);

        let connection = Connection::open(&db_path).expect("raw sqlite");
        connection
            .execute_batch(
                "INSERT INTO memo(memo_id, source_path, file_fingerprint, has_todo, has_url, \
                 has_attachment, created_at_ms, updated_at_ms, body_preview, body, \
                 word_count, char_count, content_revision, reminders_json, \
                 is_pinned, is_trashed, image_urls_json) \
                 VALUES('corrupt-img','2026_10_12.md','fp1',0,0,1,100,100,'p','body',1,1,1,'[]',0,0,'{not-json');",
            )
            .expect("insert corrupt row");
        drop(connection);

        let reopened = Store::open(dir.path()).expect("reopen store");
        let err = reopened
            .query_memos(
                &lomo_store::MemoQuery {
                    search_text: None,
                    filters: lomo_store::MemoFilters::default(),
                    sort: lomo_store::MemoSort::default(),
                },
                None,
                lomo_core::PageSize::new(10).expect("page size"),
            )
            .expect_err("corrupt image_urls_json must fail closed");
        assert_eq!(err.category(), ErrorCategory::Corruption);
        assert_eq!(err.code(), "invalid_image_urls_projection");
    }

    #[test]
    fn v19_upgrade_invalidates_revision_and_digest_when_any_memo_has_empty_time_part() {
        let dir = tempdir().expect("tempdir");
        let opened = open_store(dir.path()).expect("initial schema");
        let db_path = opened.database_path.clone();
        drop(opened);

        let connection = Connection::open(&db_path).expect("raw sqlite");
        use_external_content_fts(&connection);
        connection
            .execute_batch(
                "DROP INDEX idx_memo_empty_time_part;\
                 INSERT INTO memo(memo_id, source_path, time_part, file_fingerprint, has_todo, has_url, \
                 has_attachment, created_at_ms, updated_at_ms, body_preview, body, \
                 search_content, word_count, char_count, content_revision, reminders_json, \
                 is_pinned, is_trashed, image_urls_json) VALUES\
                 ('v19-empty','2026_10_12.md','','fp1',0,0,0,100,100,'p','body','s',1,1,1,'[]',0,0,'[]');\
                 UPDATE store_meta SET value='9' WHERE key='high_water_revision';\
                 INSERT OR REPLACE INTO store_meta(key, value) VALUES('workspace_listing_digest', 'stale-v19-digest');\
                 PRAGMA user_version=19;",
            )
            .expect("seed v19 row with empty time_part");
        drop(connection);

        let upgraded = Store::open(dir.path()).expect("upgrade v19 to v20");
        assert_eq!(upgraded.open_info().user_version, STORE_SCHEMA_VERSION);
        assert_eq!(upgraded.high_water_revision(), 0);
        assert_eq!(
            upgraded
                .workspace_listing_digest()
                .expect("read listing digest"),
            None
        );
        assert!(upgraded.has_empty_time_part().expect("empty time_part"));
    }

    #[test]
    fn v19_upgrade_preserves_revision_and_digest_when_all_memos_have_time_part() {
        let dir = tempdir().expect("tempdir");
        let opened = open_store(dir.path()).expect("initial schema");
        let db_path = opened.database_path.clone();
        drop(opened);

        let connection = Connection::open(&db_path).expect("raw sqlite");
        use_external_content_fts(&connection);
        connection
            .execute_batch(
                "DROP INDEX idx_memo_empty_time_part;\
                 INSERT INTO memo(memo_id, source_path, time_part, file_fingerprint, has_todo, has_url, \
                 has_attachment, created_at_ms, updated_at_ms, body_preview, body, \
                 search_content, word_count, char_count, content_revision, reminders_json, \
                 is_pinned, is_trashed, image_urls_json) VALUES\
                 ('v19-valid','2026_10_12.md','09:30:00','fp1',0,0,0,100,100,'p','body','s',1,1,1,'[]',0,0,'[]');\
                 UPDATE store_meta SET value='9' WHERE key='high_water_revision';\
                 INSERT OR REPLACE INTO store_meta(key, value) VALUES('workspace_listing_digest', 'keep-v19-digest');\
                 PRAGMA user_version=19;",
            )
            .expect("seed healthy v19 row");
        drop(connection);

        let upgraded = Store::open(dir.path()).expect("upgrade v19 to v20");
        assert_eq!(upgraded.open_info().user_version, STORE_SCHEMA_VERSION);
        assert_eq!(upgraded.high_water_revision(), 9);
        assert_eq!(
            upgraded
                .workspace_listing_digest()
                .expect("read listing digest")
                .as_deref(),
            Some("keep-v19-digest")
        );
        assert!(!upgraded.has_empty_time_part().expect("no empty time_part"));
    }
}
