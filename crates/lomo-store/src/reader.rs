//! Bounded, read-only projection connection for concurrent WAL queries.
//!
//! A [`StoreReader`] never creates or migrates a database.  The writable [`crate::Store`] owns
//! schema lifecycle and publication clocks; a reader only opens an already-admitted projection,
//! starts a short SQLite snapshot, and refreshes the high-water revision inside that snapshot.

use std::path::{Path, PathBuf};

use rusqlite::{Connection, OpenFlags};

use crate::error::{from_sqlite, storage, validation};
use crate::open::{SQLITE_DIR_NAME, SQLITE_FILE_NAME, database_path};
use crate::{
    HistoryRevisionBody, MemoHistoryPage, MemoPage, MemoQuery, MemoQueryBoundary, MemoQueryStart,
    MemoSnapshot, MemoStatisticsRow, MemoSummary, PageCursor, ProjectedAttachmentRef,
    SidebarProjection, StoreStats, active_memo_ids_for_source_path, get_memo, get_memo_projection,
    get_projected_memo, get_projected_memos, list_history_revision_bodies, list_memo_history,
    list_projected_attachment_refs, query_count, query_memo_statistics_rows,
    query_memos_starting_at, query_memos_with_boundary, query_sidebar_projection, query_stats,
    source_document_fingerprint,
};
use lomo_core::{LomoError, PageSize};

/// A read-only connection bound to one admitted projection.
///
/// The connection is deliberately single-use at a time; `StoreHandle` supplies bounded pooling
/// above this type.  Every public read runs inside one deferred SQLite transaction so the page,
/// its attachments/tags, and its high-water revision are one snapshot.
pub struct StoreReader {
    workspace_root: PathBuf,
    connection: Connection,
}

impl std::fmt::Debug for StoreReader {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("StoreReader")
            .field("workspace_root", &self.workspace_root)
            .field("database_path", &database_path(&self.workspace_root))
            .finish_non_exhaustive()
    }
}

impl StoreReader {
    /// Opens an existing projection without creating or migrating it.
    ///
    /// # Errors
    ///
    /// Returns a storage/validation error when the projection is missing, has an unsupported
    /// schema, or fails integrity checks.
    pub fn open(workspace_root: impl AsRef<Path>) -> Result<Self, LomoError> {
        let workspace_root = workspace_root.as_ref().to_path_buf();
        let path = workspace_root.join(SQLITE_DIR_NAME).join(SQLITE_FILE_NAME);
        let connection = Connection::open_with_flags(&path, OpenFlags::SQLITE_OPEN_READ_ONLY)
            .map_err(|error| from_sqlite(&error))?;
        connection
            .pragma_update(None, "foreign_keys", "ON")
            .map_err(|error| from_sqlite(&error))?;
        connection
            .busy_timeout(std::time::Duration::from_millis(u64::from(
                crate::BUSY_TIMEOUT_MS,
            )))
            .map_err(|error| from_sqlite(&error))?;

        let user_version: i64 = connection
            .query_row("PRAGMA user_version", [], |row| row.get(0))
            .map_err(|error| from_sqlite(&error))?;
        let user_version = u32::try_from(user_version)
            .map_err(|_error| validation("invalid_user_version", "user_version out of u32"))?;
        if user_version != crate::STORE_SCHEMA_VERSION {
            return Err(validation(
                "reader_schema_not_admitted",
                "readers require the current schema admitted by the writable store",
            ));
        }
        let integrity: String = connection
            .query_row("PRAGMA quick_check", [], |row| row.get(0))
            .map_err(|error| from_sqlite(&error))?;
        if !integrity.eq_ignore_ascii_case("ok") {
            return Err(storage(
                "reader_integrity_failed",
                "read-only projection quick_check did not return ok",
            ));
        }
        Ok(Self {
            workspace_root,
            connection,
        })
    }

    /// Runs one projection read in a consistent SQLite snapshot.
    fn snapshot<R>(
        &self,
        f: impl FnOnce(&Connection, u64) -> Result<R, LomoError>,
    ) -> Result<R, LomoError> {
        let transaction = self
            .connection
            .unchecked_transaction()
            .map_err(|error| from_sqlite(&error))?;
        let high_water_revision = crate::read_meta_u64(&transaction, "high_water_revision")?;
        let result = f(&transaction, high_water_revision)?;
        transaction.commit().map_err(|error| from_sqlite(&error))?;
        Ok(result)
    }

    /// Bounded memo query with a revision-coupled cursor.
    ///
    /// # Errors
    ///
    /// Returns cursor validation or projection storage errors.
    pub fn query_memos(
        &self,
        query: &MemoQuery,
        cursor: Option<&PageCursor>,
        page_size: PageSize,
    ) -> Result<MemoPage, LomoError> {
        self.snapshot(|connection, revision| {
            query_memos_with_boundary(connection, query, None, cursor, page_size, revision)
        })
    }

    /// Bounded memo query with an inclusive ordering boundary.
    ///
    /// # Errors
    ///
    /// Returns cursor/boundary validation or projection storage errors.
    pub fn query_memos_with_boundary(
        &self,
        query: &MemoQuery,
        boundary: Option<&MemoQueryBoundary>,
        cursor: Option<&PageCursor>,
        page_size: PageSize,
    ) -> Result<MemoPage, LomoError> {
        self.snapshot(|connection, revision| {
            query_memos_with_boundary(connection, query, boundary, cursor, page_size, revision)
        })
    }

    /// Bounded memo query from an explicit start in the current query order.
    ///
    /// # Errors
    ///
    /// Returns cursor/boundary validation or projection storage errors.
    pub fn query_memos_starting_at(
        &self,
        query: &MemoQuery,
        boundary: Option<&MemoQueryBoundary>,
        start: MemoQueryStart<'_>,
        page_size: PageSize,
    ) -> Result<MemoPage, LomoError> {
        self.snapshot(|connection, revision| {
            query_memos_starting_at(connection, query, boundary, start, page_size, revision)
        })
    }

    /// Loads a complete Direct memo snapshot (projection plus workspace body).
    ///
    /// # Errors
    ///
    /// Returns projection or workspace body storage errors.
    pub fn get_memo(&self, memo_id: &str) -> Result<Option<MemoSnapshot>, LomoError> {
        self.snapshot(|connection, _revision| get_memo(connection, &self.workspace_root, memo_id))
    }

    /// Loads one memo projection without body bytes.
    ///
    /// # Errors
    ///
    /// Returns projection storage errors.
    pub fn get_memo_projection(&self, memo_id: &str) -> Result<Option<MemoSummary>, LomoError> {
        self.snapshot(|connection, _revision| get_memo_projection(connection, memo_id))
    }

    /// Loads a complete memo snapshot from the app-private projection body column.
    ///
    /// # Errors
    ///
    /// Returns projection storage or incomplete-projection validation errors.
    pub fn get_projected_memo(&self, memo_id: &str) -> Result<Option<MemoSnapshot>, LomoError> {
        self.snapshot(|connection, _revision| get_projected_memo(connection, memo_id))
    }

    /// Loads complete memo snapshots for a candidate set inside one consistent snapshot.
    ///
    /// # Errors
    ///
    /// Returns projection storage or incomplete-projection validation errors.
    pub fn get_projected_memos(&self, memo_ids: &[String]) -> Result<Vec<MemoSnapshot>, LomoError> {
        self.snapshot(|connection, _revision| get_projected_memos(connection, memo_ids))
    }

    /// Counts rows matching a query without transferring summaries.
    ///
    /// # Errors
    ///
    /// Returns query validation or projection storage errors.
    pub fn query_count(&self, query: &MemoQuery) -> Result<u64, LomoError> {
        self.snapshot(|connection, _revision| query_count(connection, query))
    }

    /// Reads compact materialized statistics rows.
    ///
    /// # Errors
    ///
    /// Returns projection storage errors.
    pub fn memo_statistics_rows(&self) -> Result<Vec<MemoStatisticsRow>, LomoError> {
        self.snapshot(|connection, _revision| query_memo_statistics_rows(connection))
    }

    /// Reads the complete active sidebar projection.
    ///
    /// # Errors
    ///
    /// Returns projection storage errors.
    pub fn sidebar_projection(&self) -> Result<SidebarProjection, LomoError> {
        self.snapshot(|connection, _revision| query_sidebar_projection(connection))
    }

    /// Reads aggregate store stats.
    ///
    /// # Errors
    ///
    /// Returns projection storage or missing-stat validation errors.
    pub fn stats(&self) -> Result<StoreStats, LomoError> {
        self.snapshot(|connection, _revision| query_stats(connection))
    }

    /// Reads the canonical source-document fingerprint.
    ///
    /// # Errors
    ///
    /// Returns source-path validation, projection corruption, or storage errors.
    pub fn source_document_fingerprint(
        &self,
        source_path: &str,
    ) -> Result<Option<String>, LomoError> {
        self.snapshot(|connection, _revision| source_document_fingerprint(connection, source_path))
    }

    /// Active memo ids for one source document path.
    ///
    /// # Errors
    ///
    /// Returns source-path validation or projection storage errors.
    pub fn active_memo_ids_for_source_path(
        &self,
        source_path: &str,
    ) -> Result<Vec<String>, LomoError> {
        self.snapshot(|connection, _revision| {
            active_memo_ids_for_source_path(connection, source_path)
        })
    }

    /// Reads one bounded memo history page.
    ///
    /// # Errors
    ///
    /// Returns history cursor validation or projection storage errors.
    pub fn list_memo_history(
        &self,
        memo_id: &str,
        cursor: Option<&str>,
        limit: usize,
    ) -> Result<MemoHistoryPage, LomoError> {
        self.snapshot(|connection, _revision| list_memo_history(connection, memo_id, cursor, limit))
    }

    /// Lists every projected attachment reference (all types, live and trashed owners) in one scan.
    ///
    /// # Errors
    ///
    /// Returns projection storage errors.
    pub fn list_projected_attachment_refs(&self) -> Result<Vec<ProjectedAttachmentRef>, LomoError> {
        self.snapshot(|connection, _revision| list_projected_attachment_refs(connection))
    }

    /// Reads the in-window history revision bodies for the media keep-set in one query.
    ///
    /// # Errors
    ///
    /// Returns validation for a zero window and corruption when an in-window revision has no
    /// projected body.
    pub fn list_history_revision_bodies(
        &self,
        retention_revisions: usize,
    ) -> Result<Vec<HistoryRevisionBody>, LomoError> {
        self.snapshot(|connection, _revision| {
            list_history_revision_bodies(connection, retention_revisions)
        })
    }
}
