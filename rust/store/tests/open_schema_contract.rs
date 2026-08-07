//! Behavior Contract (P3-01)
//!
//! Capability: open the rebuildable `SQLite` projection with WAL/FK/busy/version/integrity and
//! reject unknown/higher schema versions without destructive downgrade.
//!
//! Scenarios:
//! - Given a new workspace root, when `Store::open` runs, then `foreign_keys=ON`, `journal_mode=wal`,
//!   `busy` timeout is applied, `user_version` is the live schema, and quick integrity is ok.
//! - Given a v1 projection, when it is opened, then the SAF ledger and reminder projection are
//!   added atomically and the schema advances to v3 without discarding projection rows.
//! - Given a database with a higher unknown `user_version`, when open is attempted, then open fails
//!   closed with `unknown_schema_version` and does not downgrade.
//!
//! Observable outcomes: `OpenInfo` fields and structured open errors.
//! Excludes: tokenizer, query, transactions, rebuild (later packages).

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use std::path::Path;

    use lomo_core::ErrorCategory;
    use lomo_store::{STORE_SCHEMA_VERSION, Store, open_store};
    use rusqlite::Connection;
    use tempfile::tempdir;

    #[test]
    fn open_applies_wal_fk_busy_version_and_integrity() {
        let dir = tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open store");
        let info = store.open_info();
        assert!(info.foreign_keys, "foreign_keys must be ON");
        assert_eq!(info.journal_mode, "wal");
        assert_eq!(info.user_version, STORE_SCHEMA_VERSION);
        assert_eq!(info.user_version, 3);
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
        let reopened = Store::open(dir.path()).expect("reopen");
        assert_eq!(reopened.open_info().user_version, 3);
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
            conn.execute(
                "INSERT INTO memo( \
                 memo_id,source_path,file_fingerprint,created_at_ms,updated_at_ms,content_revision \
                 ) VALUES('v1-memo','2026-08-04.md',?1,1,1,1)",
                ["0".repeat(64)],
            )
            .expect("seed v1 row");
            conn.execute("DROP TABLE saf_mutation_operation", [])
                .expect("remove v2 table");
            conn.execute("ALTER TABLE memo DROP COLUMN reminders_json", [])
                .expect("remove v3 column");
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
}
