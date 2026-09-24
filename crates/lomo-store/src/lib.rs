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
mod publication;
mod query;
mod reader;
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
    archive_import, archive_inspect,
};
pub use content_facts::{
    BODY_PREVIEW_MAX_CHARS, ContentFacts, aggregate_memo_digest, body_preview, count_characters,
    count_words, fingerprint_content, merge_tags, project_content_facts,
    project_reminder_references,
};
pub use cursor::{PageCursor, fingerprint_plan, fingerprint_query};
pub use history_refs::{
    DEFAULT_HISTORY_MEDIA_RETENTION_REVISIONS, HistoryRevisionBody, MemoHistoryPage,
    MemoHistoryRevision, list_history_revision_bodies, list_memo_history,
};
pub use lomo_format::{
    BatchDeleteReminderSet, BatchDeleteTarget, LOMO_CODEC_SCHEMA, LOMO_MAGIC, LomoLayoutVersion,
    LomoPaths, LomoPayload, LomoRecord, LomoRecordKind, MemoCommandKind, OperationIntent,
    OperationStatus, decode_record, encode_record, isolate_corrupt_record, read_record,
    write_record_atomic,
};
pub use open::{OpenedStore, SQLITE_DIR_NAME, SQLITE_FILE_NAME, database_path, open_store};
pub use publication::{DocumentPublication, ProjectionClock};
pub use query::{
    MemoFilters, MemoPage, MemoQuery, MemoQueryBoundary, MemoQueryStart, MemoSnapshot, MemoSort,
    MemoSortField, MemoStatisticsRow, MemoSummary, ProjectedAttachmentRef,
    SIDEBAR_PROJECTION_SCHEMA, SidebarDateCount, SidebarProjection, SidebarTagCount, SortDirection,
    StoreStats, TagSelectionMode, active_memo_ids_for_source_path, get_memo, get_memo_projection,
    get_projected_memo, get_projected_memos, list_projected_attachment_refs, query_count,
    query_memo_statistics_rows, query_memos, query_memos_starting_at, query_memos_with_boundary,
    query_sidebar_projection, query_stats, source_document_fingerprint,
};
pub use reader::StoreReader;
mod reader_pool;
pub use reader_pool::{ReaderPoolOptions, StoreReaderLease, StoreReaderPool};
pub use rebuild::{
    RebuildCheckpoint, RebuildPhase, RebuildResult, SafMemoCreateBegin, SafMemoCreateBeginResult,
    SafMemoPublication, SafPermanentDeleteMemoResult, SafPermanentDeleteTarget,
    SafProjectionCommitResult, SafProjectionMutation, SafProjectionMutationKind,
    SafProjectionRebuild, ScannedHistoryProjection, ScannedMemoProjection, ScannedPinProjection,
    ScannedTrashProjection, ensure_writable, rebuild_scanned_projection, run_rebuild,
    write_gate_for_checkpoint,
};
pub use reminder::{
    PlannedAlarm, REMINDER_ROLLING_WINDOW, ReminderCommand, ReminderCommandResult, ReminderPlan,
    ReminderQuery, ReminderSessionInput, SnoozeStore, TimeZoneContext, ZoneTransition,
    apply_reminder_command, naive_local_epoch_ms, query_reminder_plan,
    resolve_floating_local_to_utc_ms, session_base_trigger_utc_ms,
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
    select_pending_promotes,
};

use crate::rebuild::{
    begin_saf_memo_create_on_connection, commit_saf_projection_mutation_on_connection,
    rollback_saf_memo_create_on_connection,
};

use std::path::{Path, PathBuf};

use rusqlite::{Error as SqliteError, params};

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

/// Committed operation records are replay guards, not an unbounded event log.
///
/// Retain them long enough to cover normal retry/restart windows, then remove them at the next
/// store-open boundary. Incomplete records are deliberately left untouched for recovery and
/// diagnostics.
pub const OPERATION_LOG_RETENTION_MS: u64 = 30 * 24 * 60 * 60 * 1_000;

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
        // Lifecycle wiring for the transaction journal: successful replay guards are bounded at
        // one explicit engine-open boundary, while non-committed intents remain recoverable.
        cleanup_expired_operations(&workspace_root, OPERATION_LOG_RETENTION_MS)?;
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
        // A SAF projection is still an engine-owned operation boundary.  Its durable operation
        // directory is normally empty (provider jobs keep their replay ledger in the projection
        // root), but when it is present it must obey exactly the same bounded committed-record
        // policy as a Direct workspace.  Leaving this lifecycle step to `Store::open` would make
        // replay-guard retention depend on the storage driver and let one mode grow without
        // bound.
        cleanup_expired_operations(&workspace_root, OPERATION_LOG_RETENTION_MS)?;
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

    #[must_use]
    pub const fn projection_clock(&self) -> ProjectionClock {
        ProjectionClock {
            core_revision: self.high_water_revision,
            event_sequence: self.event_sequence,
        }
    }

    /// Content listing digest persisted after the last successful mount reconcile.
    ///
    /// # Errors
    /// Propagates SQLite read failures.
    pub fn workspace_listing_digest(&self) -> Result<Option<String>, LomoError> {
        read_meta_optional(&self.opened.connection, "workspace_listing_digest")
    }

    /// Stores the workspace file listing digest used by the next mount short-circuit.
    ///
    /// # Errors
    /// Propagates SQLite write failures.
    pub fn set_workspace_listing_digest(&mut self, digest: &str) -> Result<(), LomoError> {
        write_meta_string(&self.opened.connection, "workspace_listing_digest", digest)
    }

    /// Rebuild result for a live projection that already matches workspace facts.
    ///
    /// # Errors
    /// Propagates SQLite read failures.
    pub fn reconciled_live_result(&self) -> Result<RebuildResult, LomoError> {
        rebuild::live_reconciled_result(&self.opened.connection, self.high_water_revision)
    }

    /// Restores the private clock floor before rebuilding a lost query database.
    ///
    /// # Errors
    /// Propagates SQLite or counter decoding errors; counters never move backwards.
    pub fn restore_clock_floor(&mut self, floor: ProjectionClock) -> Result<(), LomoError> {
        let transaction = self
            .opened
            .connection
            .unchecked_transaction()
            .map_err(|error| from_sqlite(&error))?;
        let current = ProjectionClock {
            core_revision: read_meta_u64(&transaction, "high_water_revision")?,
            event_sequence: read_meta_u64(&transaction, "event_sequence")?,
        }
        .max(floor);
        write_meta_u64(&transaction, "high_water_revision", current.core_revision)?;
        write_meta_u64(&transaction, "event_sequence", current.event_sequence)?;
        transaction.commit().map_err(|error| from_sqlite(&error))?;
        self.high_water_revision = current.core_revision;
        self.event_sequence = current.event_sequence;
        Ok(())
    }

    /// Write gate (ready vs rebuild read-only).
    #[must_use]
    pub fn write_gate(&self) -> WriteGate {
        write_gate_for_checkpoint(&self.workspace_root)
    }

    /// Fails closed for Store Direct document commands; replays committed operation ids.
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

    /// Bounded memo query with an inclusive ordering boundary.
    ///
    /// This is the session-shaped counterpart to [`Self::query_memos`].
    ///
    /// # Errors
    ///
    /// Returns validation for an invalid boundary or page size, stale-cursor errors when the
    /// cursor belongs to another publication, and projection storage errors.
    pub fn query_memos_with_boundary(
        &self,
        query: &MemoQuery,
        boundary: Option<&MemoQueryBoundary>,
        cursor: Option<&PageCursor>,
        page_size: PageSize,
    ) -> Result<MemoPage, LomoError> {
        query_memos_with_boundary(
            &self.opened.connection,
            query,
            boundary,
            cursor,
            page_size,
            self.high_water_revision,
        )
    }

    /// Bounded memo query from an explicit start in the current query order.
    ///
    /// # Errors
    ///
    /// See [`query_memos_starting_at`].
    pub fn query_memos_starting_at(
        &self,
        query: &MemoQuery,
        boundary: Option<&MemoQueryBoundary>,
        start: MemoQueryStart<'_>,
        page_size: PageSize,
    ) -> Result<MemoPage, LomoError> {
        query_memos_starting_at(
            &self.opened.connection,
            query,
            boundary,
            start,
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

    /// Complete memo snapshot from the rebuildable app-private projection.
    ///
    /// # Errors
    ///
    /// See [`get_projected_memo`].
    pub fn get_projected_memo(&self, memo_id: &str) -> Result<Option<MemoSnapshot>, LomoError> {
        get_projected_memo(&self.opened.connection, memo_id)
    }

    /// Complete memo snapshots for a candidate set in bounded batched statements.
    ///
    /// # Errors
    ///
    /// See [`get_projected_memos`].
    pub fn get_projected_memos(&self, memo_ids: &[String]) -> Result<Vec<MemoSnapshot>, LomoError> {
        get_projected_memos(&self.opened.connection, memo_ids)
    }

    /// Counts rows matching this query without transferring them (O(1) materialized count).
    ///
    /// # Errors
    ///
    /// See [`query_count`].
    pub fn query_count(&self, query: &MemoQuery) -> Result<u64, LomoError> {
        query_count(&self.opened.connection, query)
    }

    /// Reads materialized word/character statistics rows for every active memo.
    ///
    /// # Errors
    ///
    /// See [`query_memo_statistics_rows`].
    pub fn memo_statistics_rows(&self) -> Result<Vec<MemoStatisticsRow>, LomoError> {
        query_memo_statistics_rows(&self.opened.connection)
    }

    /// Canonical fingerprint for one source document, shared by all projected memo siblings.
    ///
    /// # Errors
    ///
    /// Returns validation/corruption/storage errors from [`source_document_fingerprint`].
    pub fn source_document_fingerprint(
        &self,
        source_path: &str,
    ) -> Result<Option<String>, LomoError> {
        source_document_fingerprint(&self.opened.connection, source_path)
    }

    /// Active memo ids for one source document path.
    ///
    /// # Errors
    ///
    /// See [`active_memo_ids_for_source_path`].
    pub fn active_memo_ids_for_source_path(
        &self,
        source_path: &str,
    ) -> Result<Vec<String>, LomoError> {
        active_memo_ids_for_source_path(&self.opened.connection, source_path)
    }

    /// Lists durable memo revisions in a bounded page.
    ///
    /// # Errors
    ///
    /// Propagates history validation and storage errors.
    pub fn list_memo_history(
        &self,
        memo_id: &str,
        cursor: Option<&str>,
        limit: usize,
    ) -> Result<MemoHistoryPage, LomoError> {
        list_memo_history(&self.opened.connection, memo_id, cursor, limit)
    }

    /// Commits a verified SAF mutation into this app-private projection only.
    ///
    /// User Markdown must already have been changed by the platform-action executor; this method
    /// never receives a workspace path and cannot write user bytes.
    ///
    /// # Errors
    ///
    /// Returns validation, conflict, corruption, or storage errors from the projection commit.
    pub fn commit_saf_projection_mutation(
        &mut self,
        mutation: &SafProjectionMutation,
    ) -> Result<SafProjectionCommitResult, LomoError> {
        let result =
            commit_saf_projection_mutation_on_connection(&self.opened.connection, mutation)?;
        self.high_water_revision = result.core_revision;
        self.event_sequence = result.event_sequence;
        Ok(result)
    }

    /// Commits facts returned by a completed Rust workspace document command.
    ///
    /// The document bytes have already been durably written by the workspace owner. This method
    /// only converges the rebuildable projection and advances its publication clocks; it never
    /// writes Markdown. It intentionally shares the same CAS/idempotency transaction as the SAF
    /// projection path so Direct and SAF mutations cannot diverge.
    ///
    /// # Errors
    ///
    /// Returns validation, conflict, corruption, or storage errors from the projection commit.
    pub fn commit_workspace_document_facts(
        &mut self,
        mutation: &SafProjectionMutation,
    ) -> Result<SafProjectionCommitResult, LomoError> {
        let result =
            commit_saf_projection_mutation_on_connection(&self.opened.connection, mutation)?;
        self.high_water_revision = result.core_revision;
        self.event_sequence = result.event_sequence;
        Ok(result)
    }

    /// Publishes memo and exact durable history facts in one SQLite transaction.
    ///
    /// # Errors
    /// Rejects invalid publications, stale projections and conflicting operation replays.
    pub fn publish_document(
        &mut self,
        publication: &DocumentPublication,
    ) -> Result<SafProjectionCommitResult, LomoError> {
        let result = rebuild::commit_document_publication_on_connection(
            &self.opened.connection,
            publication,
        )?;
        self.high_water_revision = result.core_revision;
        self.event_sequence = result.event_sequence;
        Ok(result)
    }

    /// Acknowledges a frozen operation whose complete physical result has been reindexed.
    ///
    /// # Errors
    /// Rejects any mismatch between the frozen publication and the rebuilt projection.
    pub fn acknowledge_rebuilt_publication(
        &mut self,
        publication: &DocumentPublication,
        clock: ProjectionClock,
    ) -> Result<SafProjectionCommitResult, LomoError> {
        rebuild::acknowledge_rebuilt_publication(&self.opened.connection, publication, clock)
    }

    /// Publishes a pending SAF memo create projection before durable platform I/O.
    ///
    /// # Errors
    ///
    /// Returns validation, conflict, corruption, or storage errors from the pending-create transaction.
    pub fn begin_saf_memo_create(
        &mut self,
        begin: &SafMemoCreateBegin,
    ) -> Result<SafMemoCreateBeginResult, LomoError> {
        let result = begin_saf_memo_create_on_connection(&self.opened.connection, begin)?;
        self.high_water_revision = result.core_revision;
        self.event_sequence = result.event_sequence;
        Ok(result)
    }

    /// Removes a begun SAF memo create's pending projection when the pipeline fails.
    ///
    /// # Errors
    ///
    /// Returns validation or storage errors while removing the pending create.
    pub fn rollback_saf_memo_create(
        &mut self,
        operation_id: &str,
        memo_id: &str,
    ) -> Result<Option<SafMemoPublication>, LomoError> {
        let result =
            rollback_saf_memo_create_on_connection(&self.opened.connection, operation_id, memo_id)?;
        if let Some(publication) = &result {
            self.high_water_revision = publication.core_revision;
            self.event_sequence = publication.event_sequence;
        }
        Ok(result)
    }

    /// Aggregate stats.
    ///
    /// # Errors
    ///
    /// See [`query_stats`].
    pub fn stats(&self) -> Result<StoreStats, LomoError> {
        query_stats(&self.opened.connection)
    }

    /// Complete active sidebar aggregate without memo pagination.
    ///
    /// # Errors
    ///
    /// See [`query_sidebar_projection`].
    pub fn sidebar_projection(&self) -> Result<SidebarProjection, LomoError> {
        query_sidebar_projection(&self.opened.connection)
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
    /// replaced, then reopens. Matching workspace fingerprints skip the rewrite and leave the
    /// publication clock unchanged.
    ///
    /// # Errors
    ///
    /// See [`run_rebuild`].
    pub fn rebuild(self, batch_size: usize) -> Result<(Self, RebuildResult), LomoError> {
        if let Ok(Some(result)) = rebuild::try_reconcile_direct(
            &self.workspace_root,
            &self.opened.connection,
            self.high_water_revision,
        ) {
            return Ok((self, result));
        }
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
                rewritten: true,
            },
        ))
    }

    /// Returns a non-rewriting rebuild result when live memo, pin, and history facts already match.
    ///
    /// `None` means the projection diverges and a rewrite is required. Compare failures are
    /// returned to the caller so session rebuild can fail-open into a full rewrite.
    ///
    /// # Errors
    ///
    /// Propagates SQLite read failures while inspecting the live projection.
    pub fn reconcile_scanned_projection(
        &self,
        workspace_pairs: &mut [(String, String)],
        attachment_count: u64,
        pins: &[ScannedPinProjection],
        history: &[ScannedHistoryProjection],
    ) -> Result<Option<RebuildResult>, LomoError> {
        rebuild::try_reconcile_scanned(
            &self.opened.connection,
            workspace_pairs,
            attachment_count,
            self.high_water_revision,
            Some(pins),
            Some(history),
        )
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

pub(crate) fn read_meta_u64(
    connection: &rusqlite::Connection,
    key: &str,
) -> Result<u64, LomoError> {
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
    write_meta_string(connection, key, &value.to_string())
}

fn write_meta_string(
    connection: &rusqlite::Connection,
    key: &str,
    value: &str,
) -> Result<(), LomoError> {
    connection
        .execute(
            "INSERT INTO store_meta(key, value) VALUES(?1, ?2) \
             ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            params![key, value],
        )
        .map_err(|err| from_sqlite(&err))?;
    Ok(())
}

fn read_meta_optional(
    connection: &rusqlite::Connection,
    key: &str,
) -> Result<Option<String>, LomoError> {
    match connection.query_row(
        "SELECT value FROM store_meta WHERE key = ?1",
        params![key],
        |row| row.get(0),
    ) {
        Ok(value) => Ok(Some(value)),
        Err(SqliteError::QueryReturnedNoRows) => Ok(None),
        Err(error) => Err(from_sqlite(&error)),
    }
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
