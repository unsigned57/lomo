//! Behavior Contract
//!
//! Capability: prove bundled `SQLite` via `rusqlite` is viable for stage-0 (WAL, FK, FTS5,
//! backup, integrity, reopen after close).
//!
//! Scenarios:
//! - Given a temporary database path, when the probe runs, then FTS5 queries succeed and
//!   journal mode is WAL with foreign keys enabled.
//! - Given a completed probe, when the database is reopened, then `integrity_check` is ok and
//!   backup contains the inserted rows.
//!
//! Observable outcomes: `SqliteProbeReport` fields and absence of FTS5-unavailable errors.
//! Excludes: Android load of `liblomo_native.so`, Room, production schema ownership.

#[cfg(test)]
mod tests {
    use std::time::{SystemTime, UNIX_EPOCH};

    use lomo_feasibility::run_sqlite_probe;

    #[test]
    fn bundled_sqlite_supports_wal_fk_fts5_backup_and_reopen() {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("time")
            .as_nanos();
        let path = std::env::temp_dir().join(format!("lomo-sqlite-probe-{nanos}.db"));
        let report = run_sqlite_probe(&path).expect("sqlite probe");
        assert!(report.foreign_keys);
        assert_eq!(report.journal_mode, "wal");
        assert_eq!(report.fts5_query_count, 1);
        assert!(report.integrity_ok);
        assert_eq!(report.backup_row_count, 1);
        drop(std::fs::remove_file(path.with_extension("backup.db")));
        drop(std::fs::remove_file(path));
    }
}
