//! `SQLite` open path: WAL / FK / busy / version / integrity / reject unknown schema.

use std::path::{Path, PathBuf};

use rusqlite::{Connection, OpenFlags};

use crate::error::{corruption, from_sqlite, storage, validation};
use crate::schema::{
    BUSY_TIMEOUT_MS, MIGRATE_V1_TO_V2_DDL, MIGRATE_V2_TO_V3_DDL, STORE_SCHEMA_VERSION,
    schema_v1_ddl,
};

/// Relative directory for rebuildable `SQLite` files (must never live under `.lomo/`).
pub const SQLITE_DIR_NAME: &str = ".lomo-sqlite";

/// Primary database file name inside the `SQLite` directory.
pub const SQLITE_FILE_NAME: &str = "store.db";

/// Opened store connection with observed pragma state (for contract assertions).
#[derive(Debug)]
pub struct OpenedStore {
    pub connection: Connection,
    pub database_path: PathBuf,
    pub foreign_keys: bool,
    pub journal_mode: String,
    pub user_version: u32,
    pub busy_timeout_ms: u32,
    pub integrity_ok: bool,
}

/// Resolves the canonical `SQLite` path for a workspace root.
#[must_use]
pub fn database_path(workspace_root: &Path) -> PathBuf {
    workspace_root.join(SQLITE_DIR_NAME).join(SQLITE_FILE_NAME)
}

/// Opens or creates the store database with the live schema contract.
///
/// # Errors
///
/// - `unknown_schema_version` when `user_version` is higher than this crate's schema (fail closed;
///   no destructive downgrade).
/// - migration/storage errors when an older supported schema cannot be upgraded atomically.
/// - storage/corruption errors for I/O or integrity failures.
pub fn open_store(workspace_root: &Path) -> Result<OpenedStore, lomo_core::LomoError> {
    let db_path = database_path(workspace_root);
    if let Some(parent) = db_path.parent() {
        std::fs::create_dir_all(parent).map_err(|err| {
            storage(
                "sqlite_dir_create_failed",
                &format!("cannot create sqlite directory: {err}"),
            )
        })?;
    }

    let created = !db_path.exists();
    let connection = Connection::open_with_flags(
        &db_path,
        OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_CREATE,
    )
    .map_err(|err| from_sqlite(&err))?;

    configure_connection(&connection)?;

    let user_version = read_user_version(&connection)?;
    if user_version > STORE_SCHEMA_VERSION {
        return Err(validation(
            "unknown_schema_version",
            &format!(
                "database schema {user_version} is newer than supported {STORE_SCHEMA_VERSION}; refusing destructive downgrade"
            ),
        ));
    }
    if created || user_version == 0 {
        connection
            .execute_batch(&schema_v1_ddl())
            .map_err(|err| from_sqlite(&err))?;
        connection
            .pragma_update(None, "user_version", STORE_SCHEMA_VERSION)
            .map_err(|err| from_sqlite(&err))?;
    } else if user_version == 1 {
        connection
            .execute_batch(&format!(
                "BEGIN IMMEDIATE;{MIGRATE_V1_TO_V2_DDL}{MIGRATE_V2_TO_V3_DDL}COMMIT;"
            ))
            .map_err(|err| from_sqlite(&err))?;
    } else if user_version == 2 {
        connection
            .execute_batch(&format!("BEGIN IMMEDIATE;{MIGRATE_V2_TO_V3_DDL}COMMIT;"))
            .map_err(|err| from_sqlite(&err))?;
    }

    let integrity = quick_integrity(&connection)?;
    if !integrity {
        return Err(corruption(
            "sqlite_integrity_failed",
            "PRAGMA quick_check did not return ok",
        ));
    }

    let foreign_keys = read_foreign_keys(&connection)?;
    let journal_mode = read_journal_mode(&connection)?;
    let final_version = read_user_version(&connection)?;

    Ok(OpenedStore {
        connection,
        database_path: db_path,
        foreign_keys,
        journal_mode,
        user_version: final_version,
        busy_timeout_ms: BUSY_TIMEOUT_MS,
        integrity_ok: true,
    })
}

/// Creates a fresh empty database file with schema v1 (rebuild temp DB).
pub fn create_schema_db(path: &Path) -> Result<Connection, lomo_core::LomoError> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|err| {
            storage(
                "sqlite_dir_create_failed",
                &format!("cannot create sqlite directory: {err}"),
            )
        })?;
    }
    if path.exists() {
        std::fs::remove_file(path).map_err(|err| {
            storage(
                "sqlite_temp_remove_failed",
                &format!("cannot remove temp db: {err}"),
            )
        })?;
    }
    let connection = Connection::open_with_flags(
        path,
        OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_CREATE,
    )
    .map_err(|err| from_sqlite(&err))?;
    configure_connection(&connection)?;
    connection
        .execute_batch(&schema_v1_ddl())
        .map_err(|err| from_sqlite(&err))?;
    connection
        .pragma_update(None, "user_version", STORE_SCHEMA_VERSION)
        .map_err(|err| from_sqlite(&err))?;
    Ok(connection)
}

fn configure_connection(connection: &Connection) -> Result<(), lomo_core::LomoError> {
    connection
        .pragma_update(None, "foreign_keys", "ON")
        .map_err(|err| from_sqlite(&err))?;
    connection
        .busy_timeout(std::time::Duration::from_millis(u64::from(BUSY_TIMEOUT_MS)))
        .map_err(|err| from_sqlite(&err))?;
    let _mode: String = connection
        .pragma_update_and_check(None, "journal_mode", "WAL", |row| row.get(0))
        .map_err(|err| from_sqlite(&err))?;
    Ok(())
}

fn read_user_version(connection: &Connection) -> Result<u32, lomo_core::LomoError> {
    let version: i64 = connection
        .query_row("PRAGMA user_version", [], |row| row.get(0))
        .map_err(|err| from_sqlite(&err))?;
    u32::try_from(version)
        .map_err(|_overflow| validation("invalid_user_version", "user_version out of u32"))
}

fn read_foreign_keys(connection: &Connection) -> Result<bool, lomo_core::LomoError> {
    let value: i64 = connection
        .query_row("PRAGMA foreign_keys", [], |row| row.get(0))
        .map_err(|err| from_sqlite(&err))?;
    Ok(value == 1)
}

fn read_journal_mode(connection: &Connection) -> Result<String, lomo_core::LomoError> {
    let mode: String = connection
        .query_row("PRAGMA journal_mode", [], |row| row.get(0))
        .map_err(|err| from_sqlite(&err))?;
    Ok(mode.to_ascii_lowercase())
}

fn quick_integrity(connection: &Connection) -> Result<bool, lomo_core::LomoError> {
    let result: String = connection
        .query_row("PRAGMA quick_check", [], |row| row.get(0))
        .map_err(|err| from_sqlite(&err))?;
    Ok(result.eq_ignore_ascii_case("ok"))
}
