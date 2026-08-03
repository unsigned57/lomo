//! Local data-loop owner for stage-3 dark-build (`lomo-store`).
//!
//! Owns `SQLite` query projections, FTS5 + pure-Rust tokenizer, memo transaction recovery,
//! `.lomo/` durable format, rebuild (packages P3-01..P3-06), reminder business state (P3-07),
//! and archive v2 orchestration (stage-4 P4-06..P4-08).
//!
//! Production dual-stack with Room is forbidden; dark-build until atomic cutover (P3-10).

#![deny(unsafe_code)]

mod archive;
mod content_facts;
mod cursor;
mod error;
mod history_refs;
mod lomo_format;
mod open;
mod query;
mod rebuild;
mod reminder;
mod schema;
mod sync_local;
mod tokenizer;
mod transaction;

pub use archive::{
    ARCHIVE_MANIFEST_ENTRY, ARCHIVE_MANIFEST_SCHEMA_V2, ArchiveEntryKind, ArchiveExportResult,
    ArchiveInspectResult, ArchiveManifestEntry, ArchiveManifestV2, MAX_COMPRESSION_RATIO,
    MAX_ENTRY_UNCOMPRESSED_BYTES, archive_activate, archive_activate_with_rename, archive_export,
    archive_import, archive_import_activate_rebuild, archive_inspect,
};
pub use content_facts::{
    ContentFacts, aggregate_memo_digest, fingerprint_content, merge_tags, project_content_facts,
};
pub use cursor::{PageCursor, fingerprint_plan, fingerprint_query};
pub use history_refs::{
    DEFAULT_HISTORY_MEDIA_RETENTION_REVISIONS, HistoryAttachmentRef, list_history_attachment_refs,
    list_history_attachment_refs_with_retention,
};
pub use lomo_format::{
    HistoryBody, LOMO_CODEC_SCHEMA, LOMO_MAGIC, LomoLayoutVersion, LomoPaths, LomoPayload,
    LomoRecord, LomoRecordKind, MemoCommandKind, OperationIntent, OperationStatus, StateBody,
    decode_record, encode_record, isolate_corrupt_record, read_record, write_record_atomic,
};
pub use open::{OpenedStore, SQLITE_DIR_NAME, SQLITE_FILE_NAME, database_path, open_store};
pub use query::{
    MemoFilters, MemoPage, MemoQuery, MemoSnapshot, MemoSummary, StoreStats, get_memo,
    get_memo_projection, query_memos, query_stats,
};
pub use rebuild::{
    RebuildCheckpoint, RebuildPhase, RebuildResult, ScannedMemoProjection, ensure_writable,
    rebuild_scanned_projection, run_rebuild, write_gate_for_checkpoint,
};
pub use reminder::{
    PlannedAlarm, ReminderCommand, ReminderCommandResult, ReminderPlan, ReminderQuery,
    ReminderSessionInput, SnoozeStore, TimeZoneContext, ZoneTransition, apply_reminder_command,
    query_reminder_plan, resolve_floating_local_to_utc_ms, session_base_trigger_utc_ms,
};
pub use schema::{BUSY_TIMEOUT_MS, STORE_SCHEMA_VERSION, TOKENIZER_VERSION, tables};
pub use sync_local::{
    LocalSyncCommitResult, LocalSyncMutation, LocalSyncMutationBatch, LocalSyncMutationResult,
    PreparedSyncApply, SafProjectionBinding, SyncLocalPathFact, SyncLocalSnapshot,
    SyncPlatformAction, SyncPlatformActionResult, apply_local_sync_batch_direct, commit_sync_apply,
    memo_content_revision, prepare_sync_apply, snapshot_sync_view, sync_local_write_authority,
    verify_platform_results,
};
pub use tokenizer::{
    QueryPlan, QueryTerm, Tokenizer, UnicodeTokenizer, index_tokens, is_cjk, is_emoji_char,
    query_plan, tokenizer_version,
};
pub use transaction::{
    CrashPoint, MemoCommand, MemoCommitResult, WriteGate, apply_memo_command,
    cleanup_expired_operations, create_received_memo, refuse_v1_writers_on_layout_v2,
};

use std::path::{Path, PathBuf};

use rusqlite::params;

use lomo_core::{ErrorCategory, LomoError, PageSize, RetryDisposition};

use crate::error::from_sqlite;

/// Crate package identity for architecture ownership locks.
pub const CRATE_NAME: &str = "lomo-store";

/// Owner identity document for stage-3 ownership locks.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct StoreOwnerIdentity {
    /// Package name of the store owner crate.
    pub crate_name: &'static str,
    /// Declared schema version (v1 for P3-01+).
    pub schema_version: u32,
}

impl StoreOwnerIdentity {
    /// Returns the current owner identity constants.
    #[must_use]
    pub const fn current() -> Self {
        Self {
            crate_name: CRATE_NAME,
            schema_version: STORE_SCHEMA_VERSION,
        }
    }

    /// Validates that this identity matches the shipped owner crate constants.
    ///
    /// # Errors
    ///
    /// Returns a structured validation error when crate name or schema version diverges.
    pub fn validate(self) -> Result<(), LomoError> {
        if self.crate_name != CRATE_NAME {
            return Err(store_validation(
                "invalid_store_owner",
                "store owner crate_name must be lomo-store",
            ));
        }
        if self.schema_version != STORE_SCHEMA_VERSION {
            return Err(store_validation(
                "invalid_store_schema_version",
                "store schema_version must match STORE_SCHEMA_VERSION",
            ));
        }
        Ok(())
    }
}

/// Open local store handle for a workspace root.
pub struct Store {
    workspace_root: PathBuf,
    opened: OpenedStore,
    high_water_revision: u64,
    event_sequence: u64,
}

impl Store {
    /// Opens (or creates) the store for `workspace_root`.
    ///
    /// # Errors
    ///
    /// Propagates open/schema/integrity failures from [`open_store`].
    /// Fails closed with `layout_v2_requires_v2_writers` when the workspace layout head is already
    /// V2 while this crate still only writes v1-shaped history/state (dual-layout fence until
    /// store v2 writers cut over).
    pub fn open(workspace_root: impl AsRef<Path>) -> Result<Self, LomoError> {
        let workspace_root = workspace_root.as_ref().to_path_buf();
        // Dual-layout fence at the store open boundary (generation fence + layout authority).
        let paths = LomoPaths::for_workspace(&workspace_root);
        refuse_v1_writers_on_layout_v2(&paths)?;
        let opened = open_store(&workspace_root)?;
        let high_water_revision = read_meta_u64(&opened.connection, "high_water_revision")?;
        let event_sequence = read_meta_u64(&opened.connection, "event_sequence")?;
        Ok(Self {
            workspace_root,
            opened,
            high_water_revision,
            event_sequence,
        })
    }

    /// Opens an app-private query projection without creating workspace durable facts.
    ///
    /// SAF user bytes and `.lomo` authority remain behind platform actions; this handle can query
    /// only the rebuildable `SQLite` projection published by [`rebuild_scanned_projection`].
    ///
    /// # Errors
    ///
    /// Propagates open/schema/integrity failures from [`open_store`].
    pub fn open_projection(projection_root: impl AsRef<Path>) -> Result<Self, LomoError> {
        let workspace_root = projection_root.as_ref().to_path_buf();
        let opened = open_store(&workspace_root)?;
        let high_water_revision = read_meta_u64(&opened.connection, "high_water_revision")?;
        let event_sequence = read_meta_u64(&opened.connection, "event_sequence")?;
        Ok(Self {
            workspace_root,
            opened,
            high_water_revision,
            event_sequence,
        })
    }

    /// Workspace root this store is bound to.
    #[must_use]
    pub fn workspace_root(&self) -> &Path {
        &self.workspace_root
    }

    /// Observed open diagnostics.
    #[must_use]
    pub fn open_info(&self) -> OpenInfo {
        OpenInfo {
            foreign_keys: self.opened.foreign_keys,
            journal_mode: self.opened.journal_mode.clone(),
            user_version: self.opened.user_version,
            busy_timeout_ms: self.opened.busy_timeout_ms,
            integrity_ok: self.opened.integrity_ok,
            database_path: self.opened.database_path.clone(),
        }
    }

    /// Current high-water core revision counter.
    #[must_use]
    pub const fn high_water_revision(&self) -> u64 {
        self.high_water_revision
    }

    /// Current event sequence counter.
    #[must_use]
    pub const fn event_sequence(&self) -> u64 {
        self.event_sequence
    }

    /// Write gate (ready vs rebuild read-only).
    #[must_use]
    pub fn write_gate(&self) -> WriteGate {
        write_gate_for_checkpoint(&self.workspace_root)
    }

    /// Applies a memo command through the nine-step machine.
    ///
    /// # Errors
    ///
    /// See [`apply_memo_command`].
    pub fn apply_memo_command(
        &mut self,
        command: &MemoCommand,
        crash_point: Option<CrashPoint>,
    ) -> Result<MemoCommitResult, LomoError> {
        let gate = self.write_gate();
        let result = apply_memo_command(
            &self.workspace_root,
            &self.opened.connection,
            gate,
            command,
            &mut self.high_water_revision,
            &mut self.event_sequence,
            crash_point,
        )?;
        Ok(result)
    }

    /// Allocates and creates one received memo using its original timestamp and next ordinal.
    ///
    /// # Errors
    ///
    /// See [`create_received_memo`].
    pub fn create_received_memo(
        &mut self,
        operation_id: lomo_core::OperationId,
        expected_workspace_generation: &str,
        timestamp_ms: i64,
        content: String,
        pending_promotes: Vec<lomo_media::PromotePlan>,
    ) -> Result<MemoCommitResult, LomoError> {
        let gate = self.write_gate();
        create_received_memo(
            &self.workspace_root,
            &self.opened.connection,
            gate,
            operation_id,
            expected_workspace_generation,
            timestamp_ms,
            content,
            pending_promotes,
            &mut self.high_water_revision,
            &mut self.event_sequence,
        )
    }

    /// Bounded memo query.
    ///
    /// # Errors
    ///
    /// See [`query_memos`].
    pub fn query_memos(
        &self,
        query: &MemoQuery,
        cursor: Option<&PageCursor>,
        page_size: PageSize,
    ) -> Result<MemoPage, LomoError> {
        query_memos(
            &self.opened.connection,
            query,
            cursor,
            page_size,
            self.high_water_revision,
        )
    }

    /// Single memo snapshot (projection + Markdown body under the workspace root).
    ///
    /// # Errors
    ///
    /// See [`get_memo`].
    pub fn get_memo(&self, memo_id: &str) -> Result<Option<MemoSnapshot>, LomoError> {
        get_memo(&self.opened.connection, &self.workspace_root, memo_id)
    }

    /// Single memo projection without reading workspace bytes.
    ///
    /// # Errors
    ///
    /// See [`get_memo_projection`].
    pub fn get_memo_projection(&self, memo_id: &str) -> Result<Option<MemoSummary>, LomoError> {
        get_memo_projection(&self.opened.connection, memo_id)
    }

    /// Attachment paths still referenced by durable history revision bodies (D6 orphan keep-set).
    ///
    /// # Errors
    ///
    /// See [`list_history_attachment_refs`].
    pub fn list_history_attachment_refs(&self) -> Result<Vec<HistoryAttachmentRef>, LomoError> {
        list_history_attachment_refs(&self.workspace_root)
    }

    /// Aggregate stats.
    ///
    /// # Errors
    ///
    /// See [`query_stats`].
    pub fn stats(&self) -> Result<StoreStats, LomoError> {
        query_stats(&self.opened.connection)
    }

    /// Coarse local sync snapshot (path/digest/revision/media; no full-text bulk).
    ///
    /// # Errors
    ///
    /// See [`snapshot_sync_view`].
    pub fn snapshot_sync_view(&self) -> Result<SyncLocalSnapshot, LomoError> {
        snapshot_sync_view(
            &self.opened.connection,
            &self.workspace_root,
            self.high_water_revision,
        )
    }

    /// Applies a local sync mutation batch on the Direct host through prepare → verify → commit.
    ///
    /// Same expected-revision memo machine as user edits; media under generation fence.
    ///
    /// # Errors
    ///
    /// See [`apply_local_sync_batch_direct`].
    pub fn apply_local_sync_batch(
        &mut self,
        batch: &LocalSyncMutationBatch,
    ) -> Result<LocalSyncCommitResult, LomoError> {
        let gate = self.write_gate();
        apply_local_sync_batch_direct(
            &self.workspace_root,
            &self.opened.connection,
            gate,
            &mut self.high_water_revision,
            &mut self.event_sequence,
            batch,
        )
    }

    /// Prepares a sync apply (platform actions + deferred commit mutations).
    ///
    /// # Errors
    ///
    /// See [`prepare_sync_apply`].
    pub fn prepare_sync_apply(
        &self,
        batch: &LocalSyncMutationBatch,
    ) -> Result<PreparedSyncApply, LomoError> {
        prepare_sync_apply(&self.workspace_root, batch)
    }

    /// Commits after platform results are verified (SAF executor or Direct).
    ///
    /// # Errors
    ///
    /// See [`commit_sync_apply`].
    pub fn commit_sync_apply(
        &mut self,
        prepared: &PreparedSyncApply,
        platform_results: &[SyncPlatformActionResult],
    ) -> Result<LocalSyncCommitResult, LomoError> {
        let gate = self.write_gate();
        commit_sync_apply(
            &self.workspace_root,
            &self.opened.connection,
            gate,
            &mut self.high_water_revision,
            &mut self.event_sequence,
            prepared,
            platform_results,
        )
    }

    /// Builds a reminder plan using app-private snooze state.
    ///
    /// # Errors
    ///
    /// See [`query_reminder_plan`].
    pub fn query_reminder_plan(
        &self,
        query: &ReminderQuery,
        snooze: &SnoozeStore,
    ) -> Result<ReminderPlan, LomoError> {
        query_reminder_plan(query, snooze)
    }

    /// Applies a reminder command (token plan and/or snooze mutate).
    ///
    /// # Errors
    ///
    /// See [`apply_reminder_command`].
    pub fn apply_reminder_command(
        &self,
        command: &ReminderCommand,
        snooze: &mut SnoozeStore,
    ) -> Result<ReminderCommandResult, LomoError> {
        apply_reminder_command(command, snooze)
    }

    /// Runs rebuild (process-death resumable). Drops the live connection first so the file can be
    /// replaced, then reopens.
    ///
    /// # Errors
    ///
    /// See [`run_rebuild`].
    pub fn rebuild(self, batch_size: usize) -> Result<(Self, RebuildResult), LomoError> {
        let root = self.workspace_root.clone();
        drop(self);
        let result = run_rebuild(&root, batch_size)?;
        let mut store = Self::open(&root)?;
        // Publish one full revision after successful rebuild.
        store.high_water_revision = store
            .high_water_revision
            .checked_add(1)
            .ok_or_else(|| store_validation("revision_overflow", "core revision overflow"))?;
        store.event_sequence = store.event_sequence.checked_add(1).ok_or_else(|| {
            store_validation("event_sequence_overflow", "event sequence overflow")
        })?;
        write_meta_u64(
            &store.opened.connection,
            "high_water_revision",
            store.high_water_revision,
        )?;
        write_meta_u64(
            &store.opened.connection,
            "event_sequence",
            store.event_sequence,
        )?;
        let high_water_revision = store.high_water_revision;
        Ok((
            store,
            RebuildResult {
                memos_indexed: result.memos_indexed,
                file_count: result.file_count,
                attachment_count: result.attachment_count,
                workspace_digest: result.workspace_digest,
                store_digest: result.store_digest,
                corrupt_lomo_isolated: result.corrupt_lomo_isolated,
                high_water_revision,
            },
        ))
    }
}

/// Open diagnostics snapshot.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OpenInfo {
    pub foreign_keys: bool,
    pub journal_mode: String,
    pub user_version: u32,
    pub busy_timeout_ms: u32,
    pub integrity_ok: bool,
    pub database_path: PathBuf,
}

fn read_meta_u64(connection: &rusqlite::Connection, key: &str) -> Result<u64, LomoError> {
    let value: String = connection
        .query_row(
            "SELECT value FROM store_meta WHERE key = ?1",
            params![key],
            |row| row.get(0),
        )
        .map_err(|err| from_sqlite(&err))?;
    value
        .parse::<u64>()
        .map_err(|_parse| store_validation("invalid_meta_u64", "store_meta value is not u64"))
}

fn write_meta_u64(
    connection: &rusqlite::Connection,
    key: &str,
    value: u64,
) -> Result<(), LomoError> {
    connection
        .execute(
            "INSERT INTO store_meta(key, value) VALUES(?1, ?2) \
             ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            params![key, value.to_string()],
        )
        .map_err(|err| from_sqlite(&err))?;
    Ok(())
}

fn store_validation(code: &str, diagnostic: &str) -> LomoError {
    match LomoError::from_platform_boundary(
        ErrorCategory::Validation,
        code,
        RetryDisposition::Never,
        None,
        None,
        diagnostic,
    ) {
        Ok(error) | Err(error) => error,
    }
}
