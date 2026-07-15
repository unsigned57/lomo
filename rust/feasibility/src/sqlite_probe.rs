//! Bundled `SQLite` feasibility probe for migration evidence.

use std::path::Path;

use rusqlite::{Connection, OpenFlags};
use thiserror::Error;

/// Failures from the `SQLite` feasibility probe.
#[derive(Clone, Debug, Eq, Error, PartialEq)]
pub enum SqliteProbeError {
    #[error("sqlite error: {detail}")]
    Sqlite { detail: String },
    #[error("missing expected FTS5 capability")]
    Fts5Unavailable,
    #[error("integrity check failed: {detail}")]
    Integrity { detail: String },
}

/// Observable result of one probe run.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SqliteProbeReport {
    pub foreign_keys: bool,
    pub journal_mode: String,
    pub fts5_query_count: i64,
    pub integrity_ok: bool,
    pub backup_row_count: i64,
}

/// Open a bundled `SQLite` database and exercise WAL, FK, FTS5, backup, and integrity.
///
/// # Errors
///
/// Returns [`SqliteProbeError`] when `SQLite` operations fail or FTS5 is unavailable.
pub fn run_sqlite_probe(database_path: &Path) -> Result<SqliteProbeReport, SqliteProbeError> {
    let connection = Connection::open_with_flags(
        database_path,
        OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_CREATE,
    )
    .map_err(sqlite_error)?;

    connection
        .pragma_update(None, "foreign_keys", "ON")
        .map_err(sqlite_error)?;
    let journal_mode: String = connection
        .pragma_update_and_check(None, "journal_mode", "WAL", |row| row.get(0))
        .map_err(sqlite_error)?;
    let foreign_keys: i64 = connection
        .query_row("PRAGMA foreign_keys", [], |row| row.get(0))
        .map_err(sqlite_error)?;

    connection
        .execute_batch(
            "
            CREATE TABLE IF NOT EXISTS memo (
                id INTEGER PRIMARY KEY NOT NULL,
                body TEXT NOT NULL
            );
            CREATE VIRTUAL TABLE IF NOT EXISTS memo_fts USING fts5(body, content='memo', content_rowid='id');
            DELETE FROM memo;
            INSERT INTO memo(id, body) VALUES (1, 'hello lomo fts5 中文');
            INSERT INTO memo_fts(rowid, body) VALUES (1, 'hello lomo fts5 中文');
            ",
        )
        .map_err(sqlite_error)?;

    let fts5_query_count: i64 = connection
        .query_row(
            "SELECT count(*) FROM memo_fts WHERE memo_fts MATCH 'lomo'",
            [],
            |row| row.get(0),
        )
        .map_err(|error| {
            if error.to_string().contains("no such module") {
                SqliteProbeError::Fts5Unavailable
            } else {
                sqlite_error(error)
            }
        })?;

    let integrity: String = connection
        .query_row("PRAGMA quick_check", [], |row| row.get(0))
        .map_err(sqlite_error)?;
    if integrity != "ok" {
        return Err(SqliteProbeError::Integrity { detail: integrity });
    }

    let backup_path = database_path.with_extension("backup.db");
    {
        let mut destination = Connection::open(&backup_path).map_err(sqlite_error)?;
        let backup =
            rusqlite::backup::Backup::new(&connection, &mut destination).map_err(sqlite_error)?;
        backup
            .run_to_completion(5, std::time::Duration::from_millis(0), None)
            .map_err(sqlite_error)?;
    }
    // Simulate abnormal close by dropping the primary connection before reopening.
    drop(connection);

    let reopened = Connection::open(database_path).map_err(sqlite_error)?;
    let integrity_after: String = reopened
        .query_row("PRAGMA integrity_check", [], |row| row.get(0))
        .map_err(sqlite_error)?;
    if integrity_after != "ok" {
        return Err(SqliteProbeError::Integrity {
            detail: integrity_after,
        });
    }

    let backup_connection = Connection::open(&backup_path).map_err(sqlite_error)?;
    let backup_row_count: i64 = backup_connection
        .query_row("SELECT count(*) FROM memo", [], |row| row.get(0))
        .map_err(sqlite_error)?;

    Ok(SqliteProbeReport {
        foreign_keys: foreign_keys == 1,
        journal_mode: journal_mode.to_ascii_lowercase(),
        fts5_query_count,
        integrity_ok: true,
        backup_row_count,
    })
}

fn sqlite_error(error: impl std::fmt::Display) -> SqliteProbeError {
    SqliteProbeError::Sqlite {
        detail: error.to_string(),
    }
}
