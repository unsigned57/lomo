//! Rebuild state machine: read-only → temp DB → batched checkpoint → integrity → atomic replace.

use std::collections::BTreeSet;
use std::fs;
use std::path::{Path, PathBuf};

use rusqlite::{Connection, OpenFlags, OptionalExtension, params};
use serde::Serialize;

use crate::content_facts::{aggregate_memo_digest, fingerprint_content, project_content_facts};
use crate::error::{busy, conflict, corruption, from_sqlite, storage, validation};
use crate::lomo_format::{
    HistoryBody, LomoPaths, LomoRecordKind, StateBody, isolate_corrupt_record, read_record,
};
use crate::open::{SQLITE_DIR_NAME, create_schema_db, database_path};
use crate::query::recompute_stats;
use crate::tokenizer::index_tokens;
use crate::transaction::WriteGate;

/// Sidecar basename for the previous live DB during crash-safe replace.
const LIVE_BAK_NAME: &str = "store.db.bak";

/// Rebuild progress checkpoint.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RebuildCheckpoint {
    pub phase: RebuildPhase,
    pub scanned: u64,
    pub total_hint: u64,
    /// Isolated corrupt `.lomo` records observed during this rebuild run.
    pub isolated: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RebuildPhase {
    Starting,
    Scanning,
    Indexing,
    Integrity,
    Compare,
    Replacing,
    Complete,
}

impl RebuildPhase {
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Starting => "starting",
            Self::Scanning => "scanning",
            Self::Indexing => "indexing",
            Self::Integrity => "integrity",
            Self::Compare => "compare",
            Self::Replacing => "replacing",
            Self::Complete => "complete",
        }
    }

    fn parse(raw: &str) -> Result<Self, lomo_core::LomoError> {
        match raw {
            "starting" => Ok(Self::Starting),
            "scanning" => Ok(Self::Scanning),
            "indexing" => Ok(Self::Indexing),
            "integrity" => Ok(Self::Integrity),
            "compare" => Ok(Self::Compare),
            "replacing" => Ok(Self::Replacing),
            "complete" => Ok(Self::Complete),
            _ => Err(validation(
                "invalid_rebuild_phase",
                "unknown rebuild checkpoint phase",
            )),
        }
    }
}

/// Result of a completed rebuild (includes cutover compare evidence).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RebuildResult {
    pub memos_indexed: u64,
    /// Workspace memo file count scanned during compare (`memos/` + `trash/`).
    pub file_count: u64,
    /// Attachment ref count projected after index (must match workspace-derived count).
    pub attachment_count: u64,
    /// Aggregate digest of workspace memo file fingerprints (sorted `memo_id` + fingerprint).
    pub workspace_digest: String,
    /// Aggregate digest of store projection fingerprints (sorted `memo_id` + fingerprint).
    pub store_digest: String,
    pub corrupt_lomo_isolated: u64,
    pub high_water_revision: u64,
}

/// One memo projection already parsed by the Rust workspace owner through a SAF scan.
///
/// `chronology_epoch_ms` is required source chronology resolved before this store boundary.
/// `body` is an in-memory indexing input only. The projection persists bounded preview/search
/// facts, never a Markdown file mirror.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ScannedMemoProjection {
    pub memo_id: String,
    pub source_path: String,
    pub file_fingerprint: String,
    pub chronology_epoch_ms: i64,
    pub body: String,
    pub tags: Vec<String>,
    pub attachment_paths: Vec<String>,
    pub has_todo: bool,
    pub has_url: bool,
    pub reminders: Vec<lomo_workspace::ReminderReference>,
}

/// SAF mutation kind after the Android platform action has been verified.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub enum SafProjectionMutationKind {
    Create,
    Update,
    Delete,
    Pin,
    Unpin,
}

/// Facts supplied by the Rust workspace scan for a projection-only SAF commit.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct SafProjectionMutation {
    pub operation_id: String,
    pub kind: SafProjectionMutationKind,
    pub memo_id: String,
    pub expected_revision: u64,
    pub expected_fingerprint: Option<String>,
    pub projection: Option<ScannedMemoProjection>,
}

/// Commit facts returned after a verified SAF projection mutation.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SafProjectionCommitResult {
    pub operation_id: String,
    pub memo_id: String,
    pub core_revision: u64,
    pub event_sequence: u64,
    pub content_revision: u64,
    pub file_fingerprint: String,
    pub idempotent_replay: bool,
}

/// Commits a verified SAF mutation into the app-private projection only.
///
/// This boundary deliberately has no workspace path and cannot write user Markdown, trash files,
/// history records, media, or `.lomo` state. User bytes must already have been committed by the
/// platform executor and represented by `projection` facts from a fresh Rust-owned scan.
///
/// # Errors
///
/// Returns validation/corruption/storage errors when the mutation is malformed, stale, conflicts
/// with a prior operation, or cannot be committed atomically.
#[expect(
    clippy::too_many_lines,
    reason = "the SAF mutation matrix is one atomic transaction boundary"
)]
pub fn commit_saf_projection_mutation(
    projection_root: &Path,
    mutation: &SafProjectionMutation,
) -> Result<SafProjectionCommitResult, lomo_core::LomoError> {
    if mutation.operation_id.trim().is_empty() || mutation.operation_id.len() > 256 {
        return Err(validation(
            "invalid_saf_operation_id",
            "SAF projection operation id must be non-empty and bounded",
        ));
    }
    if mutation.memo_id.trim().is_empty() || mutation.memo_id.len() > 512 {
        return Err(validation(
            "invalid_memo_id",
            "SAF projection memo id must be non-empty and bounded",
        ));
    }
    let database = database_path(projection_root);
    let connection = Connection::open(&database).map_err(|error| from_sqlite(&error))?;
    let transaction = connection
        .unchecked_transaction()
        .map_err(|error| from_sqlite(&error))?;
    let mutation_json = serde_json::to_string(mutation).map_err(|error| {
        corruption(
            "saf_mutation_digest_failed",
            &format!("cannot encode SAF mutation identity: {error}"),
        )
    })?;
    let mutation_digest = fingerprint_content(&mutation_json);
    let prior = transaction
        .query_row(
            "SELECT mutation_digest,memo_id,core_revision,event_sequence,content_revision,file_fingerprint \
             FROM saf_mutation_operation WHERE operation_id = ?1",
            params![&mutation.operation_id],
            |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, i64>(2)?,
                    row.get::<_, i64>(3)?,
                    row.get::<_, i64>(4)?,
                    row.get::<_, String>(5)?,
                ))
            },
        )
        .optional()
        .map_err(|error| from_sqlite(&error))?;
    if let Some((digest, memo_id, core_revision, event_sequence, content_revision, fingerprint)) =
        prior
    {
        if digest != mutation_digest {
            return Err(validation(
                "saf_operation_conflict",
                "SAF operation id is already bound to a different mutation",
            ));
        }
        return Ok(SafProjectionCommitResult {
            operation_id: mutation.operation_id.clone(),
            memo_id,
            core_revision: stored_revision(core_revision)?,
            event_sequence: stored_revision(event_sequence)?,
            content_revision: stored_revision(content_revision)?,
            file_fingerprint: fingerprint,
            idempotent_replay: true,
        });
    }
    let current = transaction
        .query_row(
            "SELECT content_revision, file_fingerprint FROM memo WHERE memo_id = ?1",
            params![&mutation.memo_id],
            |row| Ok((row.get::<_, i64>(0)?, row.get::<_, String>(1)?)),
        )
        .optional()
        .map_err(|error| from_sqlite(&error))?;

    let (content_revision, file_fingerprint) = match mutation.kind {
        SafProjectionMutationKind::Create => {
            let projection = mutation.projection.as_ref().ok_or_else(|| {
                validation(
                    "saf_projection_facts_missing",
                    "create/update SAF projection commit requires scanned facts",
                )
            })?;
            if projection.memo_id != mutation.memo_id {
                return Err(validation(
                    "saf_projection_memo_id_mismatch",
                    "scanned projection memo id does not match mutation",
                ));
            }
            if current.is_some() || mutation.expected_revision != 0 {
                return Err(validation(
                    "saf_projection_create_conflict",
                    "SAF projection create target already exists",
                ));
            }
            validate_scanned_projection(projection)?;
            let revision = mutation
                .expected_revision
                .checked_add(1)
                .ok_or_else(|| validation("revision_overflow", "content revision overflow"))?;
            upsert_saf_projection(&transaction, projection, revision)?;
            (revision, projection.file_fingerprint.clone())
        }
        SafProjectionMutationKind::Update => {
            let projection = mutation.projection.as_ref().ok_or_else(|| {
                validation(
                    "saf_projection_facts_missing",
                    "create/update SAF projection commit requires scanned facts",
                )
            })?;
            if projection.memo_id != mutation.memo_id {
                return Err(validation(
                    "saf_projection_memo_id_mismatch",
                    "scanned projection memo id does not match mutation",
                ));
            }
            let (revision, fingerprint) = current.as_ref().ok_or_else(|| {
                validation("memo_not_found", "SAF projection update target is absent")
            })?;
            let expected_revision = i64::try_from(mutation.expected_revision)
                .map_err(|_error| validation("revision_overflow", "revision overflow"))?;
            if *revision != expected_revision
                || mutation.expected_fingerprint.as_deref() != Some(fingerprint)
            {
                return Err(validation(
                    "stale_snapshot",
                    "SAF projection update snapshot is stale",
                ));
            }
            validate_scanned_projection(projection)?;
            let next_revision = mutation
                .expected_revision
                .checked_add(1)
                .ok_or_else(|| validation("revision_overflow", "content revision overflow"))?;
            upsert_saf_projection(&transaction, projection, next_revision)?;
            (next_revision, projection.file_fingerprint.clone())
        }
        SafProjectionMutationKind::Delete => {
            let (revision, fingerprint) = current.ok_or_else(|| {
                validation("memo_not_found", "SAF projection delete target is absent")
            })?;
            let expected_revision = i64::try_from(mutation.expected_revision)
                .map_err(|_error| validation("revision_overflow", "revision overflow"))?;
            if revision != expected_revision
                || mutation.expected_fingerprint.as_deref() != Some(fingerprint.as_str())
            {
                return Err(validation(
                    "stale_snapshot",
                    "SAF projection delete snapshot is stale",
                ));
            }
            transaction
                .execute(
                    "INSERT OR REPLACE INTO memo_trash(memo_id, trashed_at_ms) VALUES(?1, ?2)",
                    params![&mutation.memo_id, current_time_ms()?],
                )
                .map_err(|error| from_sqlite(&error))?;
            (
                u64::try_from(revision)
                    .map_err(|_error| validation("revision_overflow", "negative revision"))?,
                fingerprint,
            )
        }
        SafProjectionMutationKind::Pin | SafProjectionMutationKind::Unpin => {
            let (revision, fingerprint) = current.ok_or_else(|| {
                validation("memo_not_found", "SAF projection pin target is absent")
            })?;
            let expected_revision = i64::try_from(mutation.expected_revision)
                .map_err(|_error| validation("revision_overflow", "revision overflow"))?;
            if revision != expected_revision
                || mutation.expected_fingerprint.as_deref() != Some(fingerprint.as_str())
            {
                return Err(validation(
                    "stale_snapshot",
                    "SAF projection pin snapshot is stale",
                ));
            }
            if matches!(mutation.kind, SafProjectionMutationKind::Pin) {
                transaction
                    .execute(
                        "INSERT OR REPLACE INTO memo_pin(memo_id, pinned_at_ms) VALUES(?1, ?2)",
                        params![&mutation.memo_id, current_time_ms()?],
                    )
                    .map_err(|error| from_sqlite(&error))?;
            } else {
                transaction
                    .execute(
                        "DELETE FROM memo_pin WHERE memo_id = ?1",
                        params![&mutation.memo_id],
                    )
                    .map_err(|error| from_sqlite(&error))?;
            }
            (
                u64::try_from(revision)
                    .map_err(|_error| validation("revision_overflow", "negative revision"))?,
                fingerprint,
            )
        }
    };
    recompute_stats(&transaction)?;
    let core_revision = crate::read_meta_u64(&transaction, "high_water_revision")?
        .checked_add(1)
        .ok_or_else(|| validation("revision_overflow", "core revision overflow"))?;
    let event_sequence = crate::read_meta_u64(&transaction, "event_sequence")?
        .checked_add(1)
        .ok_or_else(|| validation("event_sequence_overflow", "event sequence overflow"))?;
    crate::write_meta_u64(&transaction, "high_water_revision", core_revision)?;
    crate::write_meta_u64(&transaction, "event_sequence", event_sequence)?;
    transaction
        .execute(
            "INSERT INTO saf_mutation_operation( \
             operation_id,mutation_digest,memo_id,core_revision,event_sequence,content_revision,file_fingerprint \
             ) VALUES(?1,?2,?3,?4,?5,?6,?7)",
            params![
                &mutation.operation_id,
                mutation_digest,
                &mutation.memo_id,
                persisted_revision(core_revision)?,
                persisted_revision(event_sequence)?,
                persisted_revision(content_revision)?,
                &file_fingerprint,
            ],
        )
        .map_err(|error| from_sqlite(&error))?;
    transaction.commit().map_err(|error| from_sqlite(&error))?;
    Ok(SafProjectionCommitResult {
        operation_id: mutation.operation_id.clone(),
        memo_id: mutation.memo_id.clone(),
        core_revision,
        event_sequence,
        content_revision,
        file_fingerprint,
        idempotent_replay: false,
    })
}

fn stored_revision(value: i64) -> Result<u64, lomo_core::LomoError> {
    u64::try_from(value)
        .map_err(|_error| corruption("invalid_saf_operation_result", "negative revision"))
}

fn persisted_revision(value: u64) -> Result<i64, lomo_core::LomoError> {
    i64::try_from(value)
        .map_err(|_error| validation("revision_overflow", "revision exceeds SQLite"))
}

#[expect(
    clippy::too_many_lines,
    reason = "projection upsert keeps memo, FTS, tags, and attachments in one transaction"
)]
fn upsert_saf_projection(
    connection: &rusqlite::Transaction<'_>,
    projection: &ScannedMemoProjection,
    revision: u64,
) -> Result<(), lomo_core::LomoError> {
    let existing: Option<i64> = connection
        .query_row(
            "SELECT rowid FROM memo WHERE memo_id = ?1",
            params![&projection.memo_id],
            |row| row.get(0),
        )
        .optional()
        .map_err(|error| from_sqlite(&error))?;
    let search_content = index_tokens(&projection.body);
    let preview: String = projection.body.chars().take(200).collect();
    let revision_i64 = i64::try_from(revision)
        .map_err(|_error| validation("revision_overflow", "content revision overflow"))?;
    let reminders_json = serde_json::to_string(&projection.reminders)
        .map_err(|error| validation("invalid_reminder_projection", &error.to_string()))?;
    if let Some(rowid) = existing {
        let old_search: String = connection
            .query_row(
                "SELECT search_content FROM memo WHERE rowid = ?1",
                params![rowid],
                |row| row.get(0),
            )
            .map_err(|error| from_sqlite(&error))?;
        connection
            .execute(
                "INSERT INTO memo_fts(memo_fts, rowid, search_content) VALUES('delete', ?1, ?2)",
                params![rowid, old_search],
            )
            .map_err(|error| from_sqlite(&error))?;
        connection
            .execute(
                "UPDATE memo SET source_path=?1,file_fingerprint=?2,has_todo=?3,has_url=?4,has_attachment=?5,created_at_ms=created_at_ms,updated_at_ms=?6,body_preview=?7,search_content=?8,content_revision=?9,reminders_json=?10 WHERE memo_id=?11",
                params![
                    &projection.source_path,
                    &projection.file_fingerprint,
                    i64::from(projection.has_todo),
                    i64::from(projection.has_url),
                    i64::from(!projection.attachment_paths.is_empty()),
                    projection.chronology_epoch_ms,
                    preview,
                    search_content,
                    revision_i64,
                    reminders_json,
                    &projection.memo_id,
                ],
            )
            .map_err(|error| from_sqlite(&error))?;
        connection
            .execute(
                "INSERT INTO memo_fts(rowid, search_content) VALUES(?1, ?2)",
                params![rowid, search_content],
            )
            .map_err(|error| from_sqlite(&error))?;
    } else {
        connection
            .execute(
                "INSERT INTO memo(memo_id,source_path,file_fingerprint,has_todo,has_url,has_attachment,created_at_ms,updated_at_ms,body_preview,search_content,content_revision,reminders_json) VALUES(?1,?2,?3,?4,?5,?6,?7,?7,?8,?9,?10,?11)",
                params![
                    &projection.memo_id,
                    &projection.source_path,
                    &projection.file_fingerprint,
                    i64::from(projection.has_todo),
                    i64::from(projection.has_url),
                    i64::from(!projection.attachment_paths.is_empty()),
                    projection.chronology_epoch_ms,
                    preview,
                    search_content,
                    revision_i64,
                    reminders_json,
                ],
            )
            .map_err(|error| from_sqlite(&error))?;
        let rowid: i64 = connection
            .query_row(
                "SELECT rowid FROM memo WHERE memo_id = ?1",
                params![&projection.memo_id],
                |row| row.get(0),
            )
            .map_err(|error| from_sqlite(&error))?;
        connection
            .execute(
                "INSERT INTO memo_fts(rowid, search_content) VALUES(?1, ?2)",
                params![rowid, search_content],
            )
            .map_err(|error| from_sqlite(&error))?;
    }
    connection
        .execute(
            "DELETE FROM memo_tag WHERE memo_id = ?1",
            params![&projection.memo_id],
        )
        .map_err(|error| from_sqlite(&error))?;
    for tag in &projection.tags {
        rehydrate_tag(connection, &projection.memo_id, tag)?;
    }
    connection
        .execute(
            "DELETE FROM attachment_ref WHERE memo_id = ?1",
            params![&projection.memo_id],
        )
        .map_err(|error| from_sqlite(&error))?;
    for path in &projection.attachment_paths {
        connection
            .execute(
                "INSERT OR IGNORE INTO attachment_ref(memo_id, relative_path) VALUES(?1, ?2)",
                params![&projection.memo_id, path],
            )
            .map_err(|error| from_sqlite(&error))?;
    }
    Ok(())
}

fn current_time_ms() -> Result<i64, lomo_core::LomoError> {
    let duration = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(|error| storage("system_clock_before_epoch", &error.to_string()))?;
    i64::try_from(duration.as_millis())
        .map_err(|_error| validation("timestamp_overflow", "system time exceeds i64 epoch millis"))
}

const MAX_SAF_PROJECTION_PAGE_SIZE: usize = 256;

/// Incremental SAF projection rebuild. Pages are indexed into a temporary database and become
/// visible only after [`Self::finish`] atomically replaces the live projection.
pub struct SafProjectionRebuild {
    projection_root: PathBuf,
    live_db: PathBuf,
    temp_db: PathBuf,
    live_bak: PathBuf,
    connection: Option<Connection>,
    base_high_water_revision: u64,
    high_water_revision: u64,
    event_sequence: u64,
    memos_indexed: u64,
    attachment_count: u64,
    workspace_pairs: Vec<(String, String)>,
}

impl SafProjectionRebuild {
    /// Starts a new rebuild, replacing any abandoned temporary rebuild artifact.
    ///
    /// # Errors
    ///
    /// Returns storage/SQLite errors when the temporary projection cannot be created.
    pub fn begin(projection_root: &Path) -> Result<Self, lomo_core::LomoError> {
        let sqlite_dir = projection_root.join(SQLITE_DIR_NAME);
        fs::create_dir_all(&sqlite_dir).map_err(|error| {
            storage(
                "sqlite_dir_create_failed",
                &format!("cannot create SAF projection sqlite directory: {error}"),
            )
        })?;
        let live_db = database_path(projection_root);
        let temp_db = sqlite_dir.join("store.saf.rebuild.db");
        let live_bak = sqlite_dir.join(LIVE_BAK_NAME);
        let (base_high_water_revision, high_water_revision, event_sequence) =
            projection_rebuild_counters(&live_db)?;
        remove_file_if_exists(&temp_db, "stale SAF projection rebuild")?;
        remove_wal_shm(&temp_db);
        let connection = create_schema_db(&temp_db)?;
        Ok(Self {
            projection_root: projection_root.to_path_buf(),
            live_db,
            temp_db,
            live_bak,
            connection: Some(connection),
            base_high_water_revision,
            high_water_revision,
            event_sequence,
            memos_indexed: 0,
            attachment_count: 0,
            workspace_pairs: Vec::new(),
        })
    }

    /// Appends one bounded scan page in a single `SQLite` transaction.
    ///
    /// # Errors
    ///
    /// Returns validation for oversized/duplicate pages or malformed memo facts, and storage errors
    /// when the page cannot be committed.
    pub fn append_page(
        &mut self,
        memos: &[ScannedMemoProjection],
    ) -> Result<(), lomo_core::LomoError> {
        if memos.len() > MAX_SAF_PROJECTION_PAGE_SIZE {
            return Err(validation(
                "saf_projection_page_too_large",
                "SAF projection rebuild page exceeds 256 memos",
            ));
        }
        let connection = self.connection.as_mut().ok_or_else(|| {
            validation(
                "saf_projection_rebuild_closed",
                "SAF projection rebuild is already finished or aborted",
            )
        })?;
        let transaction = connection
            .unchecked_transaction()
            .map_err(|error| from_sqlite(&error))?;
        let mut page_ids = BTreeSet::new();
        let mut pairs = Vec::with_capacity(memos.len());
        let mut page_attachment_count = 0_u64;
        for memo in memos {
            validate_scanned_projection(memo)?;
            if !page_ids.insert(memo.memo_id.as_str()) {
                return Err(validation(
                    "duplicate_saf_projection_memo",
                    "SAF projection page contains a duplicate memo identity",
                ));
            }
            let exists: i64 = transaction
                .query_row(
                    "SELECT COUNT(*) FROM memo WHERE memo_id=?1",
                    params![&memo.memo_id],
                    |row| row.get(0),
                )
                .map_err(|error| from_sqlite(&error))?;
            if exists != 0 {
                return Err(validation(
                    "duplicate_saf_projection_memo",
                    "SAF projection rebuild received a duplicate memo identity",
                ));
            }
            index_scanned_memo(&transaction, memo)?;
            pairs.push((memo.memo_id.clone(), memo.file_fingerprint.clone()));
            page_attachment_count = page_attachment_count
                .checked_add(
                    u64::try_from(memo.attachment_paths.len()).map_err(|_error| {
                        validation("attachment_count_overflow", "attachment count exceeds u64")
                    })?,
                )
                .ok_or_else(|| {
                    validation("attachment_count_overflow", "attachment count exceeds u64")
                })?;
        }
        transaction.commit().map_err(|error| from_sqlite(&error))?;
        self.memos_indexed =
            self.memos_indexed
                .checked_add(u64::try_from(memos.len()).map_err(|_error| {
                    validation("memo_count_overflow", "memo count exceeds u64")
                })?)
                .ok_or_else(|| validation("memo_count_overflow", "memo count exceeds u64"))?;
        self.attachment_count = self
            .attachment_count
            .checked_add(page_attachment_count)
            .ok_or_else(|| {
                validation("attachment_count_overflow", "attachment count exceeds u64")
            })?;
        self.workspace_pairs.extend(pairs);
        Ok(())
    }

    /// Verifies and atomically publishes the completed projection.
    ///
    /// # Errors
    ///
    /// Returns corruption/storage errors when input evidence diverges, integrity fails, or the
    /// verified temporary database cannot replace the live projection.
    pub fn finish(mut self) -> Result<RebuildResult, lomo_core::LomoError> {
        require_projection_base_revision(&self.live_db, self.base_high_water_revision)?;
        let connection = self.connection.take().ok_or_else(|| {
            validation(
                "saf_projection_rebuild_closed",
                "SAF projection rebuild is already finished or aborted",
            )
        })?;
        copy_saf_private_state(&self.live_db, &connection)?;
        recompute_stats(&connection)?;
        crate::write_meta_u64(&connection, "high_water_revision", self.high_water_revision)?;
        crate::write_meta_u64(&connection, "event_sequence", self.event_sequence)?;
        ensure_quick_check(&connection, "SAF projection temp")?;
        let evidence = compare_scanned_pairs_to_store(
            &mut self.workspace_pairs,
            self.attachment_count,
            &connection,
        )?;
        drop(connection);
        finish_atomic_replace(&self.live_db, &self.temp_db, &self.live_bak)?;
        Ok(RebuildResult {
            memos_indexed: self.memos_indexed,
            file_count: evidence.file_count,
            attachment_count: evidence.attachment_count,
            workspace_digest: evidence.workspace_digest,
            store_digest: evidence.store_digest,
            corrupt_lomo_isolated: 0,
            high_water_revision: self.high_water_revision,
        })
    }

    /// Aborts the temporary rebuild without modifying the live projection.
    ///
    /// # Errors
    ///
    /// Returns storage errors when the temporary artifact cannot be removed.
    pub fn abort(mut self) -> Result<(), lomo_core::LomoError> {
        drop(self.connection.take());
        remove_wal_shm(&self.temp_db);
        remove_file_if_exists(&self.temp_db, "aborted SAF projection rebuild")
    }

    #[must_use]
    pub fn projection_root(&self) -> &Path {
        &self.projection_root
    }
}

/// Atomically replaces an app-private query projection from bounded SAF scan facts.
///
/// # Errors
///
/// Returns validation for malformed scan facts, storage/SQLite failures, or corruption when the
/// rebuilt projection does not match the supplied memo fingerprints and attachment count.
pub fn rebuild_scanned_projection(
    projection_root: &Path,
    memos: &[ScannedMemoProjection],
) -> Result<RebuildResult, lomo_core::LomoError> {
    let mut rebuild = SafProjectionRebuild::begin(projection_root)?;
    for page in memos.chunks(MAX_SAF_PROJECTION_PAGE_SIZE) {
        rebuild.append_page(page)?;
    }
    rebuild.finish()
}

fn projection_rebuild_counters(live_db: &Path) -> Result<(u64, u64, u64), lomo_core::LomoError> {
    let (current_revision, current_sequence) = if live_db.exists() {
        let connection = Connection::open_with_flags(live_db, OpenFlags::SQLITE_OPEN_READ_ONLY)
            .map_err(|error| from_sqlite(&error))?;
        (
            crate::read_meta_u64(&connection, "high_water_revision")?,
            crate::read_meta_u64(&connection, "event_sequence")?,
        )
    } else {
        (0, 0)
    };
    let next_revision = current_revision
        .checked_add(1)
        .ok_or_else(|| validation("revision_overflow", "core revision overflow"))?;
    let next_sequence = current_sequence
        .checked_add(1)
        .ok_or_else(|| validation("event_sequence_overflow", "event sequence overflow"))?;
    Ok((current_revision, next_revision, next_sequence))
}

fn require_projection_base_revision(
    live_db: &Path,
    expected_revision: u64,
) -> Result<(), lomo_core::LomoError> {
    let current_revision = if live_db.exists() {
        let connection = Connection::open_with_flags(live_db, OpenFlags::SQLITE_OPEN_READ_ONLY)
            .map_err(|error| from_sqlite(&error))?;
        crate::read_meta_u64(&connection, "high_water_revision")?
    } else {
        0
    };
    if current_revision != expected_revision {
        return Err(conflict(
            "stale_saf_projection_rebuild",
            "SAF projection changed after refresh began; stale staging cannot replace live data",
        ));
    }
    Ok(())
}

/// Runs or resumes rebuild. Never deletes `.lomo/` because `SQLite` is damaged.
///
/// # Errors
///
/// Storage/corruption errors. Mutations remain rejected via `WriteGate::RebuildingReadOnly`
/// for the caller while this runs.
#[expect(
    clippy::too_many_lines,
    reason = "rebuild state machine is one coherent phase sequence"
)]
pub fn run_rebuild(
    workspace_root: &Path,
    batch_size: usize,
) -> Result<RebuildResult, lomo_core::LomoError> {
    if batch_size == 0 {
        return Err(validation(
            "invalid_rebuild_batch_size",
            "rebuild batch_size must be >= 1",
        ));
    }

    let paths = LomoPaths::for_workspace(workspace_root);
    paths.ensure_layout()?;

    // Durable facts stay put even when SQLite is corrupt — we only touch rebuildable files.
    let live_db = database_path(workspace_root);
    let sqlite_dir = workspace_root.join(SQLITE_DIR_NAME);
    let temp_db = sqlite_dir.join("store.rebuild.db");
    let live_bak = sqlite_dir.join(LIVE_BAK_NAME);
    let checkpoint_path = sqlite_dir.join("rebuild.checkpoint.json");

    let mut checkpoint = load_or_init_checkpoint(&checkpoint_path)?;

    // Phase: create temp DB if starting or resuming before replace.
    if matches!(
        checkpoint.phase,
        RebuildPhase::Starting | RebuildPhase::Scanning | RebuildPhase::Indexing
    ) {
        if checkpoint.phase == RebuildPhase::Starting {
            // Fresh rebuild: drop any leftover temp/bak from a previous interrupted run.
            drop(fs::remove_file(&temp_db));
            drop(fs::remove_file(&live_bak));
            let _conn = create_schema_db(&temp_db)?;
            checkpoint.phase = RebuildPhase::Scanning;
            checkpoint.scanned = 0;
            checkpoint.isolated = 0;
            save_checkpoint(&checkpoint_path, &checkpoint)?;
        }

        // If temp is gone mid-indexing, progress is not durable — restart scan from zero.
        if matches!(
            checkpoint.phase,
            RebuildPhase::Scanning | RebuildPhase::Indexing
        ) && !temp_db.exists()
        {
            let _conn = create_schema_db(&temp_db)?;
            checkpoint.scanned = 0;
            save_checkpoint(&checkpoint_path, &checkpoint)?;
        }

        let conn = create_or_open_temp(&temp_db, &checkpoint)?;
        let memo_files = list_memo_files(workspace_root)?;
        checkpoint.total_hint = memo_files.len() as u64;
        checkpoint.phase = RebuildPhase::Indexing;
        save_checkpoint(&checkpoint_path, &checkpoint)?;

        let start = usize::try_from(checkpoint.scanned).unwrap_or(0);
        for (idx, memo_path) in memo_files.iter().enumerate().skip(start) {
            index_memo_file(&conn, memo_path)?;
            checkpoint.scanned = (idx + 1) as u64;
            if (idx + 1) % batch_size == 0 {
                save_checkpoint(&checkpoint_path, &checkpoint)?;
            }
        }

        // Apply durable .lomo state (pin/trash/tags) and history projections.
        // apply_lomo_state is idempotent (INSERT OR REPLACE / OR IGNORE).
        checkpoint.isolated = apply_lomo_state(&conn, &paths)?;
        recompute_stats(&conn)?;
        drop(conn);

        checkpoint.phase = RebuildPhase::Integrity;
        save_checkpoint(&checkpoint_path, &checkpoint)?;
    }

    if checkpoint.phase == RebuildPhase::Integrity {
        let conn = open_temp_existing(&temp_db)?;
        ensure_quick_check(&conn, "temp")?;
        // FTS count should not exceed memo count.
        let memo_count: i64 = conn
            .query_row("SELECT COUNT(*) FROM memo", [], |row| row.get(0))
            .map_err(|err| from_sqlite(&err))?;
        let fts_count: i64 = conn
            .query_row("SELECT COUNT(*) FROM memo_fts", [], |row| row.get(0))
            .map_err(|err| from_sqlite(&err))?;
        if fts_count > memo_count {
            return Err(corruption(
                "rebuild_fts_mismatch",
                "FTS row count exceeds memo count",
            ));
        }
        drop(conn);
        checkpoint.phase = RebuildPhase::Compare;
        save_checkpoint(&checkpoint_path, &checkpoint)?;
    }

    // Compare evidence is computed once in Compare and re-read after Complete for the result.
    let mut compare_file_count = 0u64;
    let mut compare_attachment_count = 0u64;
    let mut compare_workspace_digest = String::new();
    let mut compare_store_digest = String::new();

    if checkpoint.phase == RebuildPhase::Compare {
        let conn = open_temp_existing(&temp_db)?;
        let evidence = compare_workspace_to_store(workspace_root, &conn)?;
        compare_file_count = evidence.file_count;
        compare_attachment_count = evidence.attachment_count;
        compare_workspace_digest = evidence.workspace_digest;
        compare_store_digest = evidence.store_digest;
        drop(conn);
        checkpoint.phase = RebuildPhase::Replacing;
        save_checkpoint(&checkpoint_path, &checkpoint)?;
    }

    if checkpoint.phase == RebuildPhase::Replacing {
        finish_atomic_replace(&live_db, &temp_db, &live_bak)?;
        checkpoint.phase = RebuildPhase::Complete;
        save_checkpoint(&checkpoint_path, &checkpoint)?;
        drop(fs::remove_file(&checkpoint_path));
    }

    // Resume path that jumps past Compare (already Complete/replaced): recompute evidence from live.
    if compare_workspace_digest.is_empty() {
        let live = Connection::open(&live_db).map_err(|err| from_sqlite(&err))?;
        live.pragma_update(None, "foreign_keys", "ON")
            .map_err(|err| from_sqlite(&err))?;
        let evidence = compare_workspace_to_store(workspace_root, &live)?;
        compare_file_count = evidence.file_count;
        compare_attachment_count = evidence.attachment_count;
        compare_workspace_digest = evidence.workspace_digest;
        compare_store_digest = evidence.store_digest;
    }

    let memos_indexed = checkpoint.scanned;
    Ok(RebuildResult {
        memos_indexed,
        file_count: compare_file_count,
        attachment_count: compare_attachment_count,
        workspace_digest: compare_workspace_digest,
        store_digest: compare_store_digest,
        corrupt_lomo_isolated: checkpoint.isolated,
        high_water_revision: 0,
    })
}

#[derive(Debug, Clone)]
struct CompareEvidence {
    file_count: u64,
    attachment_count: u64,
    workspace_digest: String,
    store_digest: String,
}

/// Fail-closed compare: workspace memo files vs store projection counts + digests.
fn compare_workspace_to_store(
    workspace_root: &Path,
    conn: &Connection,
) -> Result<CompareEvidence, lomo_core::LomoError> {
    let memo_files = list_memo_files(workspace_root)?;
    let mut workspace_pairs: Vec<(String, String)> = Vec::with_capacity(memo_files.len());
    let mut workspace_attachments = 0u64;
    for path in &memo_files {
        let content = fs::read_to_string(path).map_err(|err| {
            storage(
                "memo_read_failed",
                &format!("cannot read {} for compare: {err}", path.display()),
            )
        })?;
        let memo_id = path
            .file_stem()
            .and_then(|s| s.to_str())
            .ok_or_else(|| validation("invalid_memo_filename", "memo file stem must be utf-8"))?
            .to_owned();
        workspace_pairs.push((memo_id, fingerprint_content(&content)));
        let facts = project_content_facts(&content)?;
        workspace_attachments = workspace_attachments
            .checked_add(u64::try_from(facts.attachment_paths.len()).unwrap_or(u64::MAX))
            .ok_or_else(|| corruption("rebuild_compare_failed", "attachment count overflow"))?;
    }
    workspace_pairs.sort_by(|a, b| a.0.cmp(&b.0));
    let workspace_digest = aggregate_memo_digest(&workspace_pairs);
    let file_count = u64::try_from(workspace_pairs.len()).unwrap_or(u64::MAX);

    let memo_count: i64 = conn
        .query_row("SELECT COUNT(*) FROM memo", [], |row| row.get(0))
        .map_err(|err| from_sqlite(&err))?;
    let memo_count_u = u64::try_from(memo_count).unwrap_or(u64::MAX);
    if memo_count_u != file_count {
        return Err(corruption(
            "rebuild_compare_failed",
            &format!("memo count {memo_count_u} does not match workspace file count {file_count}"),
        ));
    }

    let mut store_pairs: Vec<(String, String)> = Vec::new();
    {
        let mut stmt = conn
            .prepare("SELECT memo_id, file_fingerprint FROM memo ORDER BY memo_id")
            .map_err(|err| from_sqlite(&err))?;
        let rows = stmt
            .query_map([], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            })
            .map_err(|err| from_sqlite(&err))?;
        for row in rows {
            store_pairs.push(row.map_err(|err| from_sqlite(&err))?);
        }
    }
    let store_digest = aggregate_memo_digest(&store_pairs);
    if workspace_digest != store_digest {
        return Err(corruption(
            "rebuild_compare_failed",
            "workspace and store content digests diverge",
        ));
    }

    let store_attachments: i64 = conn
        .query_row("SELECT COUNT(*) FROM attachment_ref", [], |row| row.get(0))
        .map_err(|err| from_sqlite(&err))?;
    let store_attachments_u = u64::try_from(store_attachments).unwrap_or(u64::MAX);
    if store_attachments_u != workspace_attachments {
        return Err(corruption(
            "rebuild_compare_failed",
            &format!(
                "attachment count store={store_attachments_u} workspace={workspace_attachments}"
            ),
        ));
    }

    Ok(CompareEvidence {
        file_count,
        attachment_count: store_attachments_u,
        workspace_digest,
        store_digest,
    })
}

fn compare_scanned_pairs_to_store(
    workspace_pairs: &mut [(String, String)],
    attachment_count: u64,
    connection: &Connection,
) -> Result<CompareEvidence, lomo_core::LomoError> {
    workspace_pairs.sort();
    let workspace_digest = aggregate_memo_digest(workspace_pairs);
    let file_count = u64::try_from(workspace_pairs.len())
        .map_err(|_error| validation("memo_count_overflow", "memo count exceeds u64"))?;
    let memo_count: i64 = connection
        .query_row("SELECT COUNT(*) FROM memo", [], |row| row.get(0))
        .map_err(|error| from_sqlite(&error))?;
    let store_count = u64::try_from(memo_count)
        .map_err(|_error| corruption("rebuild_compare_failed", "negative memo count"))?;
    if store_count != file_count {
        return Err(corruption(
            "rebuild_compare_failed",
            "SAF page memo count does not match rebuilt projection",
        ));
    }
    let mut store_pairs = Vec::with_capacity(workspace_pairs.len());
    let mut statement = connection
        .prepare("SELECT memo_id,file_fingerprint FROM memo ORDER BY memo_id")
        .map_err(|error| from_sqlite(&error))?;
    let rows = statement
        .query_map([], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
        })
        .map_err(|error| from_sqlite(&error))?;
    for row in rows {
        store_pairs.push(row.map_err(|error| from_sqlite(&error))?);
    }
    let store_digest = aggregate_memo_digest(&store_pairs);
    if workspace_pairs != store_pairs {
        return Err(corruption(
            "rebuild_compare_failed",
            "SAF page facts and rebuilt projection diverge",
        ));
    }
    let store_attachments: i64 = connection
        .query_row("SELECT COUNT(*) FROM attachment_ref", [], |row| row.get(0))
        .map_err(|error| from_sqlite(&error))?;
    let store_attachment_count = u64::try_from(store_attachments)
        .map_err(|_error| corruption("rebuild_compare_failed", "negative attachment count"))?;
    if store_attachment_count != attachment_count {
        return Err(corruption(
            "rebuild_compare_failed",
            "SAF page attachment count does not match rebuilt projection",
        ));
    }
    Ok(CompareEvidence {
        file_count,
        attachment_count: store_attachment_count,
        workspace_digest,
        store_digest,
    })
}

fn copy_saf_private_state(live_db: &Path, target: &Connection) -> Result<(), lomo_core::LomoError> {
    if !live_db.exists() {
        return Ok(());
    }
    let live = Connection::open_with_flags(live_db, OpenFlags::SQLITE_OPEN_READ_ONLY)
        .map_err(|error| from_sqlite(&error))?;
    for table in ["memo_pin", "memo_trash"] {
        let mut statement = live
            .prepare(&format!(
                "SELECT memo_id,{} FROM {table}",
                if table == "memo_pin" {
                    "pinned_at_ms"
                } else {
                    "trashed_at_ms"
                }
            ))
            .map_err(|error| from_sqlite(&error))?;
        let rows = statement
            .query_map([], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?))
            })
            .map_err(|error| from_sqlite(&error))?;
        for row in rows {
            let (memo_id, timestamp) = row.map_err(|error| from_sqlite(&error))?;
            let sql = if table == "memo_pin" {
                "INSERT OR REPLACE INTO memo_pin(memo_id,pinned_at_ms) VALUES(?1,?2)"
            } else {
                "INSERT OR REPLACE INTO memo_trash(memo_id,trashed_at_ms) VALUES(?1,?2)"
            };
            target
                .execute(sql, params![memo_id, timestamp])
                .map_err(|error| from_sqlite(&error))?;
        }
    }
    if table_exists(&live, "saf_mutation_operation")? {
        let mut statement = live
            .prepare(
                "SELECT operation_id,mutation_digest,memo_id,core_revision,event_sequence,content_revision,file_fingerprint \
                 FROM saf_mutation_operation",
            )
            .map_err(|error| from_sqlite(&error))?;
        let rows = statement
            .query_map([], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, i64>(3)?,
                    row.get::<_, i64>(4)?,
                    row.get::<_, i64>(5)?,
                    row.get::<_, String>(6)?,
                ))
            })
            .map_err(|error| from_sqlite(&error))?;
        for row in rows {
            let values = row.map_err(|error| from_sqlite(&error))?;
            target
                .execute(
                    "INSERT INTO saf_mutation_operation(operation_id,mutation_digest,memo_id,core_revision,event_sequence,content_revision,file_fingerprint) VALUES(?1,?2,?3,?4,?5,?6,?7)",
                    params![values.0, values.1, values.2, values.3, values.4, values.5, values.6],
                )
                .map_err(|error| from_sqlite(&error))?;
        }
    }
    Ok(())
}

fn table_exists(connection: &Connection, name: &str) -> Result<bool, lomo_core::LomoError> {
    let count: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?1",
            params![name],
            |row| row.get(0),
        )
        .map_err(|error| from_sqlite(&error))?;
    Ok(count == 1)
}

fn remove_file_if_exists(path: &Path, context: &str) -> Result<(), lomo_core::LomoError> {
    match fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(storage(
            "sqlite_temp_remove_failed",
            &format!("cannot remove {context} {}: {error}", path.display()),
        )),
    }
}

/// Crash-safe live DB replace.
///
/// Strategy:
/// 1. Never delete the sole good live DB without a verified temp replacement.
/// 2. `live → bak`, then `temp → live`, then delete bak (and WAL/SHM).
/// 3. Resume rules when phase=`replacing`:
///    - temp missing + live exists + integrity OK → rename already completed → success
///    - temp exists → finish replace from temp
///    - temp missing + live missing + bak exists → restore bak then fail closed if no temp
///    - temp missing + live bad/missing + no bak → storage error (cannot invent a DB)
fn finish_atomic_replace(
    live_db: &Path,
    temp_db: &Path,
    live_bak: &Path,
) -> Result<(), lomo_core::LomoError> {
    if let Some(parent) = live_db.parent() {
        fs::create_dir_all(parent).map_err(|err| {
            storage(
                "sqlite_dir_create_failed",
                &format!("cannot create sqlite dir: {err}"),
            )
        })?;
    }

    remove_wal_shm(live_db);
    remove_wal_shm(temp_db);
    remove_wal_shm(live_bak);

    let temp_ok = temp_db.exists() && db_quick_check_ok(temp_db)?;
    let live_ok = live_db.exists() && db_quick_check_ok(live_db)?;

    if !temp_ok {
        if live_ok {
            // Rename temp→live already happened (or temp was never needed): complete as success.
            drop(fs::remove_file(live_bak));
            return Ok(());
        }
        // Live is missing/corrupt; try bak as last resort only if it integrity-checks.
        if live_bak.exists() && db_quick_check_ok(live_bak)? {
            fs::rename(live_bak, live_db).map_err(|err| {
                storage(
                    "sqlite_replace_bak_restore_failed",
                    &format!("cannot restore bak to live: {err}"),
                )
            })?;
            // Still no temp to install — surface that rebuild replace cannot complete without temp.
            // But a good live is restored so the store is not destroyed.
            return Ok(());
        }
        return Err(storage(
            "sqlite_replace_no_good_db",
            "rebuild replace cannot complete: temp missing and live not integrity-ok",
        ));
    }

    // Temp is good. Promote it without deleting live first.
    if live_db.exists() {
        // Replace any prior bak, then move live aside.
        drop(fs::remove_file(live_bak));
        fs::rename(live_db, live_bak).map_err(|err| {
            storage(
                "sqlite_replace_live_to_bak_failed",
                &format!("cannot rename live sqlite to bak: {err}"),
            )
        })?;
    }
    fs::rename(temp_db, live_db).map_err(|err| {
        // Best-effort: put live back if rename failed and bak is present.
        if live_bak.exists() && !live_db.exists() {
            drop(fs::rename(live_bak, live_db));
        }
        storage(
            "sqlite_replace_rename_failed",
            &format!("cannot rename temp sqlite into place: {err}"),
        )
    })?;
    drop(fs::remove_file(live_bak));
    remove_wal_shm(live_db);
    Ok(())
}

fn remove_wal_shm(db: &Path) {
    drop(fs::remove_file(PathBuf::from(format!(
        "{}-wal",
        db.display()
    ))));
    drop(fs::remove_file(PathBuf::from(format!(
        "{}-shm",
        db.display()
    ))));
    drop(fs::remove_file(db.with_extension("db-wal")));
    drop(fs::remove_file(db.with_extension("db-shm")));
}

fn db_quick_check_ok(path: &Path) -> Result<bool, lomo_core::LomoError> {
    let conn = Connection::open(path).map_err(|err| from_sqlite(&err))?;
    let ok: String = conn
        .query_row("PRAGMA quick_check", [], |row| row.get(0))
        .map_err(|err| from_sqlite(&err))?;
    Ok(ok.eq_ignore_ascii_case("ok"))
}

fn ensure_quick_check(conn: &Connection, label: &str) -> Result<(), lomo_core::LomoError> {
    let ok: String = conn
        .query_row("PRAGMA quick_check", [], |row| row.get(0))
        .map_err(|err| from_sqlite(&err))?;
    if !ok.eq_ignore_ascii_case("ok") {
        return Err(corruption(
            "rebuild_integrity_failed",
            &format!("{label} database failed quick_check"),
        ));
    }
    Ok(())
}

/// Returns the write gate for a store that may be mid-rebuild.
#[must_use]
pub fn write_gate_for_checkpoint(workspace_root: &Path) -> WriteGate {
    let checkpoint_path = workspace_root
        .join(SQLITE_DIR_NAME)
        .join("rebuild.checkpoint.json");
    if !checkpoint_path.exists() {
        return WriteGate::Ready;
    }
    match load_or_init_checkpoint(&checkpoint_path) {
        Ok(cp) if cp.phase != RebuildPhase::Complete => WriteGate::RebuildingReadOnly,
        _ => WriteGate::Ready,
    }
}

/// Rejects mutations while rebuilding (helper for callers).
///
/// # Errors
///
/// Returns `store_rebuilding` when the gate is read-only.
pub fn ensure_writable(gate: WriteGate) -> Result<(), lomo_core::LomoError> {
    if gate == WriteGate::RebuildingReadOnly {
        return Err(busy(
            "store_rebuilding",
            "write and sync are rejected during rebuild",
        ));
    }
    Ok(())
}

fn load_or_init_checkpoint(path: &Path) -> Result<RebuildCheckpoint, lomo_core::LomoError> {
    if path.exists() {
        let text = fs::read_to_string(path).map_err(|err| {
            storage(
                "rebuild_checkpoint_read_failed",
                &format!("cannot read checkpoint: {err}"),
            )
        })?;
        let value: serde_json::Value = serde_json::from_str(&text).map_err(|err| {
            corruption(
                "rebuild_checkpoint_corrupt",
                &format!("cannot parse checkpoint: {err}"),
            )
        })?;
        let phase = value
            .get("phase")
            .and_then(|v| v.as_str())
            .ok_or_else(|| corruption("rebuild_checkpoint_corrupt", "missing phase"))?;
        let scanned = value
            .get("scanned")
            .and_then(serde_json::Value::as_u64)
            .unwrap_or(0);
        let total_hint = value
            .get("total_hint")
            .and_then(serde_json::Value::as_u64)
            .unwrap_or(0);
        let isolated = value
            .get("isolated")
            .and_then(serde_json::Value::as_u64)
            .unwrap_or(0);
        return Ok(RebuildCheckpoint {
            phase: RebuildPhase::parse(phase)?,
            scanned,
            total_hint,
            isolated,
        });
    }
    Ok(RebuildCheckpoint {
        phase: RebuildPhase::Starting,
        scanned: 0,
        total_hint: 0,
        isolated: 0,
    })
}

fn save_checkpoint(
    path: &Path,
    checkpoint: &RebuildCheckpoint,
) -> Result<(), lomo_core::LomoError> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|err| {
            storage(
                "sqlite_dir_create_failed",
                &format!("cannot create sqlite dir: {err}"),
            )
        })?;
    }
    let json = serde_json::json!({
        "phase": checkpoint.phase.as_str(),
        "scanned": checkpoint.scanned,
        "total_hint": checkpoint.total_hint,
        "isolated": checkpoint.isolated,
    });
    let tmp = path.with_extension("json.tmp");
    fs::write(&tmp, json.to_string()).map_err(|err| {
        storage(
            "rebuild_checkpoint_write_failed",
            &format!("cannot write checkpoint: {err}"),
        )
    })?;
    fs::rename(&tmp, path).map_err(|err| {
        storage(
            "rebuild_checkpoint_rename_failed",
            &format!("cannot rename checkpoint: {err}"),
        )
    })?;
    Ok(())
}

fn create_or_open_temp(
    temp_db: &Path,
    checkpoint: &RebuildCheckpoint,
) -> Result<Connection, lomo_core::LomoError> {
    if checkpoint.phase == RebuildPhase::Starting || !temp_db.exists() {
        create_schema_db(temp_db)
    } else {
        open_temp_existing(temp_db)
    }
}

fn open_temp_existing(temp_db: &Path) -> Result<Connection, lomo_core::LomoError> {
    let conn = Connection::open(temp_db).map_err(|err| from_sqlite(&err))?;
    conn.pragma_update(None, "foreign_keys", "ON")
        .map_err(|err| from_sqlite(&err))?;
    Ok(conn)
}

fn list_memo_files(workspace_root: &Path) -> Result<Vec<PathBuf>, lomo_core::LomoError> {
    let mut out = Vec::new();
    collect_md_files(&workspace_root.join("memos"), &mut out)?;
    // Trashed bodies remain durable under trash/; rebuild must rehydrate them for FK pin/trash.
    collect_md_files(&workspace_root.join("trash"), &mut out)?;
    out.sort();
    Ok(out)
}

fn collect_md_files(dir: &Path, out: &mut Vec<PathBuf>) -> Result<(), lomo_core::LomoError> {
    if !dir.exists() {
        return Ok(());
    }
    for entry in fs::read_dir(dir).map_err(|err| {
        storage(
            "memo_list_failed",
            &format!("cannot list {}: {err}", dir.display()),
        )
    })? {
        let entry = entry.map_err(|err| {
            storage(
                "memo_list_failed",
                &format!("cannot read memo entry: {err}"),
            )
        })?;
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) == Some("md") {
            out.push(path);
        }
    }
    Ok(())
}

fn index_memo_file(conn: &Connection, path: &Path) -> Result<(), lomo_core::LomoError> {
    let content = fs::read_to_string(path).map_err(|err| {
        storage(
            "memo_read_failed",
            &format!("cannot read {} for rebuild: {err}", path.display()),
        )
    })?;
    let memo_id = path
        .file_stem()
        .and_then(|s| s.to_str())
        .ok_or_else(|| validation("invalid_memo_filename", "memo file stem must be utf-8"))?
        .to_owned();
    let fingerprint = fingerprint_content(&content);
    let facts = project_content_facts(&content)?;
    let parent_name = path
        .parent()
        .and_then(|p| p.file_name())
        .and_then(|n| n.to_str())
        .unwrap_or("memos");
    let source_path = format!("{parent_name}/{memo_id}.md");
    let modified = fs::metadata(path)
        .and_then(|metadata| metadata.modified())
        .map_err(|error| {
            storage(
                "memo_chronology_unavailable",
                &format!("cannot read {} modification time: {error}", path.display()),
            )
        })?;
    let duration = modified
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(|_error| {
            validation(
                "invalid_memo_chronology",
                "direct memo modification time must be after the Unix epoch",
            )
        })?;
    let chronology_epoch_ms = i64::try_from(duration.as_millis()).map_err(|_error| {
        validation(
            "invalid_memo_chronology",
            "direct memo modification epoch exceeds i64 milliseconds",
        )
    })?;
    if chronology_epoch_ms <= 0 {
        return Err(validation(
            "invalid_memo_chronology",
            "direct memo modification epoch must be positive",
        ));
    }
    index_scanned_memo(
        conn,
        &ScannedMemoProjection {
            memo_id,
            source_path,
            file_fingerprint: fingerprint,
            chronology_epoch_ms,
            body: content,
            tags: facts.tags,
            attachment_paths: facts.attachment_paths,
            has_todo: facts.has_todo,
            has_url: facts.has_url,
            reminders: Vec::new(),
        },
    )
}

fn index_scanned_memo(
    conn: &Connection,
    memo: &ScannedMemoProjection,
) -> Result<(), lomo_core::LomoError> {
    validate_scanned_projection(memo)?;
    let search_content = index_tokens(&memo.body);
    let preview: String = memo.body.chars().take(200).collect();
    let has_todo = i64::from(memo.has_todo);
    let has_url = i64::from(memo.has_url);
    let has_attachment = i64::from(!memo.attachment_paths.is_empty());
    let reminders_json = serde_json::to_string(&memo.reminders)
        .map_err(|error| validation("invalid_reminder_projection", &error.to_string()))?;

    // Skip if already indexed (resume).
    let exists: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM memo WHERE memo_id = ?1",
            params![memo.memo_id],
            |row| row.get(0),
        )
        .map_err(|err| from_sqlite(&err))?;
    if exists > 0 {
        return Ok(());
    }

    conn.execute(
        "INSERT INTO memo(memo_id, source_path, file_fingerprint, has_todo, has_url, has_attachment, \
         created_at_ms, updated_at_ms, body_preview, search_content, content_revision, reminders_json) \
         VALUES(?1,?2,?3,?4,?5,?6,?7,?7,?8,?9,1,?10)",
        params![
            &memo.memo_id,
            &memo.source_path,
            &memo.file_fingerprint,
            has_todo,
            has_url,
            has_attachment,
            memo.chronology_epoch_ms,
            preview,
            search_content,
            reminders_json,
        ],
    )
    .map_err(|err| from_sqlite(&err))?;
    let rowid: i64 = conn
        .query_row(
            "SELECT rowid FROM memo WHERE memo_id = ?1",
            params![&memo.memo_id],
            |row| row.get(0),
        )
        .map_err(|err| from_sqlite(&err))?;
    conn.execute(
        "INSERT INTO memo_fts(rowid, search_content) VALUES(?1, ?2)",
        params![rowid, search_content],
    )
    .map_err(|err| from_sqlite(&err))?;
    for tag in &memo.tags {
        rehydrate_tag(conn, &memo.memo_id, tag)?;
    }
    for rel in &memo.attachment_paths {
        if rel.is_empty() || rel.len() > 1024 {
            return Err(validation(
                "invalid_attachment_path",
                "attachment relative path is empty or too long",
            ));
        }
        conn.execute(
            "INSERT OR IGNORE INTO attachment_ref(memo_id, relative_path) VALUES(?1, ?2)",
            params![memo.memo_id, rel],
        )
        .map_err(|err| from_sqlite(&err))?;
    }
    Ok(())
}

fn validate_scanned_projection(memo: &ScannedMemoProjection) -> Result<(), lomo_core::LomoError> {
    if memo.memo_id.is_empty() || memo.memo_id.len() > 512 {
        return Err(validation(
            "invalid_memo_id",
            "scanned memo id is empty or too long",
        ));
    }
    let _path = lomo_workspace::WorkspaceRelativePath::parse(&memo.source_path)?;
    let _fingerprint = lomo_workspace::SourceFingerprint::parse(&memo.file_fingerprint)?;
    if memo.chronology_epoch_ms <= 0 {
        return Err(validation(
            "invalid_memo_chronology",
            "scanned memo chronology must be a positive epoch millisecond",
        ));
    }

    Ok(())
}

fn apply_lomo_state(conn: &Connection, paths: &LomoPaths) -> Result<u64, lomo_core::LomoError> {
    let mut isolated = 0u64;
    isolated += apply_state_dir(conn, paths)?;
    isolated += apply_history_dir(conn, paths)?;
    Ok(isolated)
}

fn apply_state_dir(conn: &Connection, paths: &LomoPaths) -> Result<u64, lomo_core::LomoError> {
    let mut isolated = 0u64;
    if !paths.state.exists() {
        return Ok(0);
    }
    for entry in fs::read_dir(&paths.state).map_err(|err| {
        storage(
            "lomo_state_list_failed",
            &format!("cannot list state: {err}"),
        )
    })? {
        let entry = entry.map_err(|err| {
            storage(
                "lomo_state_list_failed",
                &format!("cannot read state entry: {err}"),
            )
        })?;
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("rec") {
            continue;
        }
        match read_record(&path) {
            Ok(record) if record.payload.kind == LomoRecordKind::State => {
                let body: StateBody =
                    serde_json::from_str(&record.payload.body_json).map_err(|err| {
                        corruption(
                            "lomo_state_payload_invalid",
                            &format!("state payload invalid: {err}"),
                        )
                    })?;
                rehydrate_state_body(conn, &body)?;
            }
            Ok(_) => {}
            Err(_) => {
                drop(isolate_corrupt_record(&path)?);
                isolated += 1;
            }
        }
    }
    Ok(isolated)
}

fn rehydrate_state_body(conn: &Connection, body: &StateBody) -> Result<(), lomo_core::LomoError> {
    if body.pinned {
        conn.execute(
            "INSERT OR REPLACE INTO memo_pin(memo_id, pinned_at_ms) VALUES(?1, ?2)",
            params![body.memo_id, body.pinned_at_ms.unwrap_or(0)],
        )
        .map_err(|err| from_sqlite(&err))?;
    }
    if body.trashed {
        conn.execute(
            "INSERT OR REPLACE INTO memo_trash(memo_id, trashed_at_ms) VALUES(?1, ?2)",
            params![body.memo_id, body.trashed_at_ms.unwrap_or(0)],
        )
        .map_err(|err| from_sqlite(&err))?;
    }
    // Durable tags are authoritative when present. Empty durable tags leave content-indexed tags
    // (import / plain Markdown without a prior state write).
    if !body.tags.is_empty() {
        conn.execute(
            "DELETE FROM memo_tag WHERE memo_id = ?1",
            params![body.memo_id],
        )
        .map_err(|err| from_sqlite(&err))?;
        for tag in &body.tags {
            rehydrate_tag(conn, &body.memo_id, tag)?;
        }
    }
    Ok(())
}

fn apply_history_dir(conn: &Connection, paths: &LomoPaths) -> Result<u64, lomo_core::LomoError> {
    let mut isolated = 0u64;
    if !paths.history.exists() {
        return Ok(0);
    }
    for entry in fs::read_dir(&paths.history).map_err(|err| {
        storage(
            "lomo_history_list_failed",
            &format!("cannot list history: {err}"),
        )
    })? {
        let entry = entry.map_err(|err| {
            storage(
                "lomo_history_list_failed",
                &format!("cannot read history entry: {err}"),
            )
        })?;
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("rec") {
            continue;
        }
        match read_record(&path) {
            Ok(record) if record.payload.kind == LomoRecordKind::History => {
                let body: HistoryBody =
                    serde_json::from_str(&record.payload.body_json).map_err(|err| {
                        corruption(
                            "lomo_history_payload_invalid",
                            &format!("history payload invalid: {err}"),
                        )
                    })?;
                let rev = i64::try_from(body.revision).unwrap_or(i64::MAX);
                conn.execute(
                    "INSERT OR REPLACE INTO revision_index(memo_id, revision, history_record_id, created_at_ms) \
                     VALUES(?1,?2,?3,0)",
                    params![body.memo_id, rev, record.payload.record_id],
                )
                .map_err(|err| from_sqlite(&err))?;
            }
            Ok(_) => {}
            Err(_) => {
                drop(isolate_corrupt_record(&path)?);
                isolated += 1;
            }
        }
    }
    Ok(isolated)
}

fn rehydrate_tag(conn: &Connection, memo_id: &str, tag: &str) -> Result<(), lomo_core::LomoError> {
    if tag.is_empty() || tag.len() > 128 || tag.contains('\'') {
        return Err(validation(
            "invalid_tag_on_rebuild",
            "durable state tag is invalid",
        ));
    }
    conn.execute("INSERT OR IGNORE INTO tag(name) VALUES(?1)", params![tag])
        .map_err(|err| from_sqlite(&err))?;
    let tag_id: i64 = conn
        .query_row("SELECT id FROM tag WHERE name = ?1", params![tag], |row| {
            row.get(0)
        })
        .map_err(|err| from_sqlite(&err))?;
    conn.execute(
        "INSERT OR IGNORE INTO memo_tag(memo_id, tag_id) VALUES(?1, ?2)",
        params![memo_id, tag_id],
    )
    .map_err(|err| from_sqlite(&err))?;
    Ok(())
}
