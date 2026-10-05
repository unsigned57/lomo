//! Rebuild state machine: read-only → temp DB → batched checkpoint → integrity → atomic replace.

use std::collections::{BTreeMap, BTreeSet};
use std::fmt::Write as _;
use std::fs;
use std::path::{Path, PathBuf};

use rusqlite::{Connection, OpenFlags, OptionalExtension, Transaction, params};
use serde::{Deserialize, Serialize};

use crate::content_facts::{
    aggregate_memo_digest, body_preview, count_characters, count_words, fingerprint_content,
    project_content_facts, project_reminder_references,
};
use crate::error::{busy, conflict, corruption, from_sqlite, storage, validation};
use crate::lomo_format::{LomoPaths, MemoCommandKind};
use crate::open::{SQLITE_DIR_NAME, create_schema_db, database_path};
use crate::purge::list_purged_memo_ids;
use crate::query::recompute_stats;
use crate::tokenizer::index_tokens;
use crate::transaction::{WriteGate, memo_command_scopes};
use lomo_workspace::{
    TrashRecordV1, canonical_attachment_keys, decode_trash_record, trash_record_relative_path,
};

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
    /// `false` when live projection fingerprints already matched workspace facts, so SQLite was
    /// not replaced and the high-water clock was not advanced.
    pub rewritten: bool,
}

/// One memo projection already parsed by the Rust workspace owner through a SAF scan.
///
/// `chronology_epoch_ms` is required source chronology resolved before this store boundary.
/// `body` is an in-memory indexing input only. The projection persists bounded preview/search
/// facts, never a Markdown file mirror.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ScannedMemoProjection {
    pub memo_id: String,
    pub source_path: String,
    pub file_fingerprint: String,
    pub chronology_epoch_ms: i64,
    pub body: String,
    pub tags: Vec<String>,
    /// Attachment destinations as scanned — workspace facts already carry the canonical
    /// projection, but this boundary also accepts durable/FFI spellings. Only destinations
    /// that name a workspace file (`canonical_attachment_path` returns `Some`) produce
    /// `attachment_ref` rows and evidence counts.
    pub attachment_paths: Vec<String>,
    pub has_todo: bool,
    pub has_url: bool,
    pub reminders: Vec<lomo_workspace::ReminderReference>,
}

/// One durable trash-record projection decoded by the Rust workspace owner.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ScannedTrashProjection {
    pub memo: ScannedMemoProjection,
    pub trashed_at_ms: i64,
}

impl ScannedTrashProjection {
    /// Projects one decoded durable trash record into the recoverable snapshot the
    /// projection owns.
    ///
    /// The record's declared `attachments` list is payload, never evidence: attachment
    /// protection (`attachment_ref` rows and the sweep keep-set) derives from the
    /// recoverable body — the only independently verifiable source. A body the render
    /// owner cannot project fails closed instead of silently keeping an empty keep-set.
    ///
    /// # Errors
    /// Fails when the recoverable body cannot be projected for attachment extraction.
    pub fn from_record(record: &TrashRecordV1) -> Result<Self, lomo_core::LomoError> {
        let attachment_paths = project_content_facts(&record.body)?.attachment_paths;
        Ok(Self {
            memo: ScannedMemoProjection {
                memo_id: record.memo_id.clone(),
                source_path: record.source_path.clone(),
                file_fingerprint: record.source_fingerprint.clone(),
                chronology_epoch_ms: record.chronology_epoch_ms,
                body: record.body.clone(),
                tags: record.tags.clone(),
                attachment_paths,
                has_todo: record.has_todo,
                has_url: record.has_url,
                reminders: record.reminders.clone(),
            },
            trashed_at_ms: record.trashed_at_ms,
        })
    }

    /// Canonical digest over every record-decided projection fact.
    ///
    /// The digest covers the claimed source path and fingerprint, chronology, the
    /// recoverable body, the declared tag set, the attachment keys extracted from that
    /// body, the todo/url flags, the ordered reminder facts, and the trash timestamp —
    /// every field a `memo`/`memo_trash`/`attachment_ref`/`memo_tag` row commits. It is
    /// a pure function of the durable record, so a rewrite that preserves the claimed
    /// fingerprint but changes any recoverable fact still flips the digest and forces
    /// the reconcile gate to decline certification.
    ///
    /// Attachment evidence is re-derived from the body rather than the caller-supplied
    /// `attachment_paths` field so every producer — record decode, commit-time
    /// attestation, or rebuild — computes the same value from the same bytes.
    ///
    /// # Errors
    /// Fails closed when the body cannot render for attachment extraction.
    pub fn attestation_digest(&self) -> Result<String, lomo_core::LomoError> {
        let mut tags = self.memo.tags.clone();
        tags.sort();
        tags.dedup();
        let mut attachment_keys =
            canonical_attachment_keys(&project_content_facts(&self.memo.body)?.attachment_paths);
        attachment_keys.sort();
        let payload = (
            self.memo.memo_id.as_str(),
            self.memo.source_path.as_str(),
            self.memo.file_fingerprint.as_str(),
            self.memo.chronology_epoch_ms,
            self.memo.body.as_str(),
            tags,
            attachment_keys,
            self.memo.has_todo,
            self.memo.has_url,
            self.memo.reminders.as_slice(),
            self.trashed_at_ms,
        );
        let json = serde_json::to_string(&payload)
            .map_err(|error| corruption("trash_attestation_digest_failed", &error.to_string()))?;
        Ok(fingerprint_content(&json))
    }
}

/// The predicted committed row image a workspace scan certifies the projection against.
///
/// `memo_rows` carries `(memo_id, file_fingerprint, is_trashed)` — lifecycle membership
/// is a row-deciding factor, so it is part of the certification vector — and
/// `trash_attestations` carries `(memo_id, trashed_at_ms, record_digest)` for every
/// durable record's committed `memo_trash` row. `attachment_count` is the predicted
/// `attachment_ref` row count.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ScannedRowImage {
    pub memo_rows: Vec<(String, String, bool)>,
    pub trash_attestations: Vec<(String, i64, String)>,
    pub attachment_count: u64,
}

/// One durable history snapshot decoded and verified by the Rust workspace scan.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ScannedHistoryProjection {
    pub memo_id: String,
    pub record_id: String,
    pub revision: u64,
    pub created_at_ms: i64,
    pub content: String,
    pub file_fingerprint: String,
}

/// One verified workspace listing row persisted with a projection commit.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ScannedListingRow {
    pub path: String,
    pub digest: String,
}

/// Path-scoped facts admitted for an incremental projection update.
///
/// The application layer supplies only facts whose provenance is proven scoped to changed
/// workspace paths; every set is applied atomically against the live projection and the derived
/// aggregates (`stats`, lifecycle bits, listing digest) are recomputed inside the same commit.
#[derive(Debug, Clone, Default)]
pub struct ScannedIncrementalFacts {
    /// Listing rows to upsert (verified path + content digest).
    pub listing_upserts: Vec<ScannedListingRow>,
    /// Listing paths whose files disappeared.
    pub listing_removes: Vec<String>,
    /// Memo identities whose whole projection is removed before upserts run.
    pub memo_removes: Vec<String>,
    /// Rescanned active-document memos to index or update.
    pub memo_upserts: Vec<ScannedMemoProjection>,
    /// Trashed memos whose durable trash records changed.
    pub trash_upserts: Vec<ScannedTrashProjection>,
    /// Trash-claim identities a live document still attests: either re-emitted by
    /// a rescanned document this pass (`memo_upserts`), or owning a committed
    /// document-derived row that survives this apply. A committed row outside
    /// this set is the record's own earlier claim — retired before its merge so
    /// `current` can never act as attestation for it. The post-apply check uses
    /// the set to certify each merged row's canonical fingerprint.
    pub doc_attested_ids: BTreeSet<String>,
    /// Per-memo history replacements: the complete durable revision set for each owner.
    pub history_replaces: Vec<ScannedMemoHistoryReplace>,
    /// Pin facts to upsert.
    pub pin_upserts: Vec<ScannedPinProjection>,
    /// Memo identities whose pin rows are removed.
    pub pin_removes: Vec<String>,
    /// Purged tombstone identities to upsert or remove.
    pub purged_upserts: Vec<String>,
    pub purged_removes: Vec<String>,
}

impl ScannedIncrementalFacts {
    /// True when no fact set carries a change — an empty apply only re-derives aggregates.
    #[must_use]
    pub const fn is_empty(&self) -> bool {
        self.listing_upserts.is_empty()
            && self.listing_removes.is_empty()
            && self.memo_removes.is_empty()
            && self.memo_upserts.is_empty()
            && self.trash_upserts.is_empty()
            && self.history_replaces.is_empty()
            && self.pin_upserts.is_empty()
            && self.pin_removes.is_empty()
            && self.purged_upserts.is_empty()
            && self.purged_removes.is_empty()
    }
}

/// Complete durable revision set replacing one memo's history rows.
#[derive(Debug, Clone)]
pub struct ScannedMemoHistoryReplace {
    pub memo_id: String,
    pub revisions: Vec<ScannedHistoryProjection>,
}

/// One durable pin fact decoded from the workspace `.lomo` state facts.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ScannedPinProjection {
    pub memo_id: String,
    pub pinned_at_ms: i64,
}

/// SAF mutation kind after the Android platform action has been verified.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum SafProjectionMutationKind {
    Create,
    Update,
    HistoryRestore,
    Delete,
    Restore,
    PermanentDelete,
    /// One atomic permanent delete covering a bounded target set. Unlike per-target loops, the
    /// whole batch validates and applies inside a single projection transaction and owns one
    /// durable operation receipt.
    PermanentDeleteMany,
    Pin,
    Unpin,
}

/// Facts supplied by the Rust workspace scan for a projection-only SAF commit.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SafProjectionMutation {
    pub operation_id: String,
    pub kind: SafProjectionMutationKind,
    pub memo_id: String,
    pub expected_revision: u64,
    pub expected_fingerprint: Option<String>,
    pub projection: Option<ScannedMemoProjection>,
    pub trashed_at_ms: Option<i64>,
    /// Verified per-target facts for [`SafProjectionMutationKind::PermanentDeleteMany`]; empty for
    /// every other kind. `memo_id`/`expected_revision`/`expected_fingerprint` mirror the first
    /// target so the committed receipt keeps a single primary subject.
    #[serde(default)]
    pub batch_targets: Vec<SafPermanentDeleteTarget>,
}

/// Commit facts returned after a verified SAF projection mutation.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SafProjectionCommitResult {
    pub operation_id: String,
    pub memo_id: String,
    pub core_revision: u64,
    pub event_sequence: u64,
    pub content_revision: u64,
    pub file_fingerprint: String,
    pub scopes: Vec<lomo_core::InvalidationScope>,
    pub idempotent_replay: bool,
    /// Per-target reminder facts for [`SafProjectionMutationKind::PermanentDeleteMany`]. The facts
    /// are part of the frozen mutation, so every replay path reconstructs them without re-reading
    /// the deleted projection rows.
    #[serde(default)]
    pub deleted_memos: Vec<SafPermanentDeleteMemoResult>,
}

/// Verified platform facts for one permanent-delete target.
///
/// `result_fingerprint` is the post-delete source document fingerprint used to refresh sibling
/// memo rows from the same document in the same projection transaction.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SafPermanentDeleteTarget {
    pub memo_id: String,
    pub source_path: String,
    pub expected_revision: u64,
    pub expected_fingerprint: String,
    pub result_fingerprint: String,
    /// Reminder opaque identities captured from the projection before the row is deleted. The
    /// commit re-verifies them against `reminders_json`, then the receipt echoes them so the
    /// platform scheduler can cancel alarms without a second read.
    #[serde(default)]
    pub reminder_ids: Vec<String>,
}

/// One memo's reminder identities captured before its durable row is removed.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SafPermanentDeleteMemoResult {
    pub memo_id: String,
    pub reminder_ids: Vec<String>,
}

/// Commits a verified SAF mutation into the app-private projection only.
///
/// This boundary deliberately has no workspace path and cannot write user Markdown, trash files,
/// history records, media, or `.lomo` state. User bytes must already have been committed by the
/// platform executor and represented by `projection` facts from a fresh Rust-owned scan.
///
/// # Errors
/// Commits a SAF projection mutation using an already-open store connection.
pub fn commit_saf_projection_mutation_on_connection(
    connection: &Connection,
    mutation: &SafProjectionMutation,
) -> Result<SafProjectionCommitResult, lomo_core::LomoError> {
    let history = legacy_history_projection(mutation)?;
    let mutation_json = serde_json::to_string(mutation)
        .map_err(|error| corruption("saf_mutation_digest_failed", &error.to_string()))?;
    commit_projection_publication(
        connection,
        mutation,
        history.as_ref(),
        &fingerprint_content(&mutation_json),
    )
}

pub fn commit_document_publication_on_connection(
    connection: &Connection,
    publication: &crate::DocumentPublication,
) -> Result<SafProjectionCommitResult, lomo_core::LomoError> {
    publication.validate()?;
    let json = serde_json::to_string(publication)
        .map_err(|error| corruption("publication_digest_failed", &error.to_string()))?;
    commit_projection_publication(
        connection,
        &publication.mutation,
        publication.history.as_ref(),
        &fingerprint_content(&json),
    )
}

fn legacy_history_projection(
    mutation: &SafProjectionMutation,
) -> Result<Option<ScannedHistoryProjection>, lomo_core::LomoError> {
    if !matches!(
        mutation.kind,
        SafProjectionMutationKind::Create
            | SafProjectionMutationKind::Update
            | SafProjectionMutationKind::HistoryRestore
    ) {
        return Ok(None);
    }
    let Some(projection) = &mutation.projection else {
        return Ok(None);
    };
    let revision = mutation
        .expected_revision
        .checked_add(1)
        .ok_or_else(|| validation("revision_overflow", "content revision overflow"))?;
    Ok(Some(ScannedHistoryProjection {
        memo_id: mutation.memo_id.clone(),
        record_id: format!("{}-r{revision}", mutation.memo_id),
        revision,
        created_at_ms: projection.chronology_epoch_ms,
        content: projection.body.clone(),
        file_fingerprint: projection.file_fingerprint.clone(),
    }))
}

fn find_publication_receipt(
    connection: &Connection,
    mutation: &SafProjectionMutation,
    mutation_digest: &str,
) -> Result<Option<SafProjectionCommitResult>, lomo_core::LomoError> {
    let prior = connection
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
        return Ok(Some(SafProjectionCommitResult {
            operation_id: mutation.operation_id.clone(),
            memo_id,
            core_revision: stored_revision(core_revision)?,
            event_sequence: stored_revision(event_sequence)?,
            content_revision: stored_revision(content_revision)?,
            file_fingerprint: fingerprint,
            scopes: saf_projection_scopes(mutation.kind),
            idempotent_replay: true,
            deleted_memos: batch_deleted_facts(mutation),
        }));
    }
    Ok(None)
}

/// Rebuilds per-target reminder facts from the frozen batch mutation.
fn batch_deleted_facts(mutation: &SafProjectionMutation) -> Vec<SafPermanentDeleteMemoResult> {
    if mutation.kind != SafProjectionMutationKind::PermanentDeleteMany {
        return Vec::new();
    }
    mutation
        .batch_targets
        .iter()
        .map(|target| SafPermanentDeleteMemoResult {
            memo_id: target.memo_id.clone(),
            reminder_ids: target.reminder_ids.clone(),
        })
        .collect()
}

#[expect(
    clippy::too_many_lines,
    reason = "the SAF mutation matrix is one atomic transaction boundary"
)]
fn commit_projection_publication(
    connection: &Connection,
    mutation: &SafProjectionMutation,
    history: Option<&ScannedHistoryProjection>,
    mutation_digest: &str,
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
    if mutation.kind != SafProjectionMutationKind::PermanentDeleteMany
        && !mutation.batch_targets.is_empty()
    {
        return Err(validation(
            "unexpected_batch_targets",
            "batch targets are only valid for a permanent-delete batch",
        ));
    }
    match mutation.kind {
        SafProjectionMutationKind::Delete => {
            if mutation
                .trashed_at_ms
                .is_none_or(|timestamp| timestamp <= 0)
            {
                return Err(validation(
                    "invalid_trash_timestamp",
                    "SAF delete requires a positive durable trash timestamp",
                ));
            }
        }
        SafProjectionMutationKind::Create
        | SafProjectionMutationKind::Update
        | SafProjectionMutationKind::HistoryRestore
        | SafProjectionMutationKind::Restore
        | SafProjectionMutationKind::PermanentDelete
        | SafProjectionMutationKind::PermanentDeleteMany
        | SafProjectionMutationKind::Pin
        | SafProjectionMutationKind::Unpin => {
            if mutation.trashed_at_ms.is_some() {
                return Err(validation(
                    "unexpected_trash_timestamp",
                    "only SAF delete may publish a trash timestamp",
                ));
            }
        }
    }
    let transaction = connection
        .unchecked_transaction()
        .map_err(|error| from_sqlite(&error))?;
    if let Some(receipt) = find_publication_receipt(&transaction, mutation, mutation_digest)? {
        return Ok(receipt);
    }
    let current = transaction
        .query_row(
            "SELECT content_revision, file_fingerprint, source_path FROM memo WHERE memo_id = ?1",
            params![&mutation.memo_id],
            |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                ))
            },
        )
        .optional()
        .map_err(|error| from_sqlite(&error))?;

    let mut deleted_memos: Vec<SafPermanentDeleteMemoResult> = Vec::new();
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
            let pending_owner: Option<Option<String>> = if current.is_some() {
                Some(
                    transaction
                        .query_row(
                            "SELECT pending_operation_id FROM memo WHERE memo_id = ?1",
                            params![&mutation.memo_id],
                            |row| row.get::<_, Option<String>>(0),
                        )
                        .map_err(|error| from_sqlite(&error))?,
                )
            } else {
                None
            };
            let pending_completion = match (&current, pending_owner) {
                (Some((_, _, source_path)), Some(owner))
                    if owner.as_deref() == Some(mutation.operation_id.as_str()) =>
                {
                    if source_path != &projection.source_path {
                        return Err(validation(
                            "saf_projection_source_path_mismatch",
                            "committed create facts do not match the begun source path",
                        ));
                    }
                    true
                }
                (Some(_), _) => {
                    return Err(validation(
                        "saf_projection_create_conflict",
                        "SAF projection create target already exists",
                    ));
                }
                (None, _) => false,
            };
            if mutation.expected_revision != 0 {
                return Err(validation(
                    "saf_projection_create_conflict",
                    "SAF projection create requires expected revision zero",
                ));
            }
            validate_scanned_projection(projection)?;
            let revision = mutation
                .expected_revision
                .checked_add(1)
                .ok_or_else(|| validation("revision_overflow", "content revision overflow"))?;
            upsert_saf_projection(&transaction, projection, revision)?;
            transaction
                .execute(
                    "UPDATE memo SET file_fingerprint=?1 WHERE source_path=?2",
                    params![&projection.file_fingerprint, &projection.source_path],
                )
                .map_err(|error| from_sqlite(&error))?;
            if pending_completion {
                transaction
                    .execute(
                        "UPDATE memo SET pending_operation_id = NULL WHERE memo_id = ?1",
                        params![&mutation.memo_id],
                    )
                    .map_err(|error| from_sqlite(&error))?;
            }
            (revision, projection.file_fingerprint.clone())
        }
        SafProjectionMutationKind::Update | SafProjectionMutationKind::HistoryRestore => {
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
            let (revision, fingerprint, _source_path) = current.as_ref().ok_or_else(|| {
                validation("memo_not_found", "SAF projection update target is absent")
            })?;
            let expected_revision = i64::try_from(mutation.expected_revision)
                .map_err(|_error| validation("revision_overflow", "revision overflow"))?;
            if *revision != expected_revision
                || mutation.expected_fingerprint.as_deref() != Some(fingerprint)
            {
                return Err(conflict(
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
            transaction
                .execute(
                    "UPDATE memo SET file_fingerprint=?1 WHERE source_path=?2",
                    params![&projection.file_fingerprint, &projection.source_path],
                )
                .map_err(|error| from_sqlite(&error))?;
            (next_revision, projection.file_fingerprint.clone())
        }
        SafProjectionMutationKind::Delete => {
            let projection = mutation.projection.as_ref().ok_or_else(|| {
                validation(
                    "saf_projection_facts_missing",
                    "delete SAF projection commit requires the verified result fingerprint",
                )
            })?;
            if projection.memo_id != mutation.memo_id {
                return Err(validation(
                    "saf_projection_memo_id_mismatch",
                    "scanned projection memo id does not match mutation",
                ));
            }
            validate_scanned_projection(projection)?;
            let (revision, fingerprint, source_path) = current.ok_or_else(|| {
                validation("memo_not_found", "SAF projection delete target is absent")
            })?;
            let expected_revision = i64::try_from(mutation.expected_revision)
                .map_err(|_error| validation("revision_overflow", "revision overflow"))?;
            if revision != expected_revision
                || mutation.expected_fingerprint.as_deref() != Some(fingerprint.as_str())
            {
                return Err(conflict(
                    "stale_snapshot",
                    "SAF projection delete snapshot is stale",
                ));
            }
            if projection.source_path != source_path {
                return Err(validation(
                    "saf_projection_source_path_mismatch",
                    "delete projection source path does not match the current memo",
                ));
            }
            // The purge tombstone is the trash lane's suppression authority. The trash
            // record this commit already wrote to durable state is suppressed by the
            // standing tombstone, so no scan of the same facts can project a `memo_trash`
            // row — the committed image is the row's retirement, not a second tombstone.
            let tombstoned: bool = transaction
                .query_row(
                    "SELECT EXISTS(SELECT 1 FROM purged_memo WHERE memo_id=?1)",
                    params![&mutation.memo_id],
                    |row| row.get(0),
                )
                .map_err(|error| from_sqlite(&error))?;
            if tombstoned {
                delete_memo_row(&transaction, &mutation.memo_id)?;
            } else {
                transaction
                    .execute(
                        "UPDATE memo SET file_fingerprint=?1 WHERE source_path=?2",
                        params![&projection.file_fingerprint, &source_path],
                    )
                    .map_err(|error| from_sqlite(&error))?;
                let trashed_at_ms = mutation.trashed_at_ms.ok_or_else(|| {
                    validation(
                        "invalid_trash_timestamp",
                        "SAF delete requires a durable trash timestamp",
                    )
                })?;
                // Attest exactly the record this transaction wrote: the mutation
                // projection carries the same recoverable facts the session encoded,
                // so this digest is the one a later scan re-derives from the record.
                let record_digest = ScannedTrashProjection {
                    memo: projection.clone(),
                    trashed_at_ms,
                }
                .attestation_digest()?;
                transaction
                    .execute(
                        "INSERT OR REPLACE INTO memo_trash(memo_id, trashed_at_ms, record_digest) VALUES(?1, ?2, ?3)",
                        params![&mutation.memo_id, trashed_at_ms, record_digest],
                    )
                    .map_err(|error| from_sqlite(&error))?;
                transaction
                    .execute(
                        "UPDATE memo SET is_trashed=1 WHERE memo_id=?1",
                        params![&mutation.memo_id],
                    )
                    .map_err(|error| from_sqlite(&error))?;
            }
            (
                u64::try_from(revision)
                    .map_err(|_error| validation("revision_overflow", "negative revision"))?,
                projection.file_fingerprint.clone(),
            )
        }
        SafProjectionMutationKind::Restore => {
            let projection = mutation.projection.as_ref().ok_or_else(|| {
                validation(
                    "saf_projection_facts_missing",
                    "restore SAF projection commit requires verified active memo facts",
                )
            })?;
            if projection.memo_id != mutation.memo_id {
                return Err(validation(
                    "saf_projection_memo_id_mismatch",
                    "restore projection memo id does not match mutation",
                ));
            }
            validate_scanned_projection(projection)?;
            let (revision, fingerprint, source_path) = current.ok_or_else(|| {
                validation("memo_not_found", "SAF projection restore target is absent")
            })?;
            let expected_revision = i64::try_from(mutation.expected_revision)
                .map_err(|_error| validation("revision_overflow", "revision overflow"))?;
            if revision != expected_revision
                || mutation.expected_fingerprint.as_deref() != Some(fingerprint.as_str())
            {
                return Err(conflict(
                    "stale_snapshot",
                    "SAF projection restore snapshot is stale",
                ));
            }
            if projection.source_path != source_path {
                return Err(validation(
                    "saf_projection_source_path_mismatch",
                    "restore projection source path does not match the current memo",
                ));
            }
            let trashed: bool = transaction
                .query_row(
                    "SELECT EXISTS(SELECT 1 FROM memo_trash WHERE memo_id=?1)",
                    params![&mutation.memo_id],
                    |row| row.get(0),
                )
                .map_err(|error| from_sqlite(&error))?;
            if !trashed {
                return Err(validation(
                    "memo_not_trashed",
                    "SAF restore requires a memo currently projected in trash",
                ));
            }
            let next_revision = mutation
                .expected_revision
                .checked_add(1)
                .ok_or_else(|| validation("revision_overflow", "content revision overflow"))?;
            upsert_saf_projection(&transaction, projection, next_revision)?;
            transaction
                .execute(
                    "UPDATE memo SET file_fingerprint=?1 WHERE source_path=?2",
                    params![&projection.file_fingerprint, &projection.source_path],
                )
                .map_err(|error| from_sqlite(&error))?;
            transaction
                .execute(
                    "DELETE FROM memo_trash WHERE memo_id=?1",
                    params![&mutation.memo_id],
                )
                .map_err(|error| from_sqlite(&error))?;
            transaction
                .execute(
                    "UPDATE memo SET is_trashed=0 WHERE memo_id=?1",
                    params![&mutation.memo_id],
                )
                .map_err(|error| from_sqlite(&error))?;
            (next_revision, projection.file_fingerprint.clone())
        }
        SafProjectionMutationKind::PermanentDelete => {
            let projection = mutation.projection.as_ref().ok_or_else(|| {
                validation(
                    "saf_projection_facts_missing",
                    "permanent delete requires the verified result source fingerprint",
                )
            })?;
            if projection.memo_id != mutation.memo_id {
                return Err(validation(
                    "saf_projection_memo_id_mismatch",
                    "permanent delete projection memo id does not match mutation",
                ));
            }
            validate_scanned_projection(projection)?;
            let (revision, fingerprint, source_path) = current.ok_or_else(|| {
                validation(
                    "memo_not_found",
                    "SAF projection permanent delete target is absent",
                )
            })?;
            let expected_revision = i64::try_from(mutation.expected_revision)
                .map_err(|_error| validation("revision_overflow", "revision overflow"))?;
            if revision != expected_revision
                || mutation.expected_fingerprint.as_deref() != Some(fingerprint.as_str())
            {
                return Err(conflict(
                    "stale_snapshot",
                    "SAF projection permanent delete snapshot is stale",
                ));
            }
            if projection.source_path != source_path {
                return Err(validation(
                    "saf_projection_source_path_mismatch",
                    "permanent delete source path does not match the current memo",
                ));
            }
            let trashed: bool = transaction
                .query_row(
                    "SELECT EXISTS(SELECT 1 FROM memo_trash WHERE memo_id=?1)",
                    params![&mutation.memo_id],
                    |row| row.get(0),
                )
                .map_err(|error| from_sqlite(&error))?;
            if !trashed {
                return Err(validation(
                    "memo_not_trashed",
                    "permanent delete requires a memo currently projected in trash",
                ));
            }
            transaction
                .execute(
                    "UPDATE memo SET file_fingerprint=?1 WHERE source_path=?2",
                    params![&projection.file_fingerprint, &source_path],
                )
                .map_err(|error| from_sqlite(&error))?;
            transaction
                .execute(
                    "INSERT INTO memo_fts(memo_fts,rowid,search_content) SELECT 'delete',rowid,search_content FROM memo WHERE memo_id=?1",
                    params![&mutation.memo_id],
                )
                .map_err(|error| from_sqlite(&error))?;
            transaction
                .execute(
                    "DELETE FROM memo WHERE memo_id=?1",
                    params![&mutation.memo_id],
                )
                .map_err(|error| from_sqlite(&error))?;
            // `revision_index`/`history_attachment_ref` are file-owned rows: the durable
            // history records survive the purge, so they stay exactly as a cold rescan
            // leaves them.
            // The purge tombstone file committed alongside this mutation is the durable
            // suppression fact; project it now so `purged_memo` and `.lomo/purged/` never
            // diverge between commit and the next reconcile.
            transaction
                .execute(
                    "INSERT OR REPLACE INTO purged_memo(memo_id) VALUES(?1)",
                    params![&mutation.memo_id],
                )
                .map_err(|error| from_sqlite(&error))?;
            (
                u64::try_from(revision)
                    .map_err(|_error| validation("revision_overflow", "negative revision"))?,
                projection.file_fingerprint.clone(),
            )
        }
        SafProjectionMutationKind::PermanentDeleteMany => {
            if mutation.projection.is_some() {
                return Err(validation(
                    "unexpected_projection_facts",
                    "permanent delete batch carries per-target facts, not one projection",
                ));
            }
            let targets = &mutation.batch_targets;
            if targets.is_empty() {
                return Err(validation(
                    "empty_permanent_delete_batch",
                    "permanent delete batch must contain at least one target",
                ));
            }
            if targets.len() > MAX_PERMANENT_DELETE_BATCH_TARGETS {
                return Err(validation(
                    "permanent_delete_batch_too_large",
                    "permanent delete batch exceeds the bounded target limit",
                ));
            }
            if targets
                .first()
                .is_some_and(|first| first.memo_id != mutation.memo_id)
            {
                return Err(validation(
                    "saf_batch_subject_mismatch",
                    "batch memo identity must mirror its first target",
                ));
            }
            validate_permanent_delete_targets(targets)?;
            // Every target row is validated before any delete: a stale baseline fails the whole
            // batch without touching the projection.
            for target in targets {
                let row = transaction
                    .query_row(
                        "SELECT content_revision, file_fingerprint, source_path, reminders_json FROM memo WHERE memo_id=?1",
                        params![&target.memo_id],
                        |row| {
                            Ok((
                                row.get::<_, i64>(0)?,
                                row.get::<_, String>(1)?,
                                row.get::<_, String>(2)?,
                                row.get::<_, String>(3)?,
                            ))
                        },
                    )
                    .optional()
                    .map_err(|error| from_sqlite(&error))?
                    .ok_or_else(|| {
                        validation(
                            "memo_not_found",
                            "permanent delete batch target is absent",
                        )
                    })?;
                let expected_revision = i64::try_from(target.expected_revision)
                    .map_err(|_error| validation("revision_overflow", "revision overflow"))?;
                if row.0 != expected_revision || row.1 != target.expected_fingerprint {
                    return Err(conflict(
                        "stale_snapshot",
                        "permanent delete batch snapshot is stale",
                    ));
                }
                if row.2 != target.source_path {
                    return Err(validation(
                        "saf_projection_source_path_mismatch",
                        "permanent delete source path does not match the current memo",
                    ));
                }
                let trashed: bool = transaction
                    .query_row(
                        "SELECT EXISTS(SELECT 1 FROM memo_trash WHERE memo_id=?1)",
                        params![&target.memo_id],
                        |row| row.get(0),
                    )
                    .map_err(|error| from_sqlite(&error))?;
                if !trashed {
                    return Err(validation(
                        "memo_not_trashed",
                        "permanent delete requires a memo currently projected in trash",
                    ));
                }
                let projected_reminders: Vec<lomo_workspace::ReminderReference> =
                    serde_json::from_str(&row.3).map_err(|error| {
                        corruption(
                            "invalid_reminder_projection",
                            &format!("cannot decode reminder projection: {error}"),
                        )
                    })?;
                let projected_ids: BTreeSet<&str> = projected_reminders
                    .iter()
                    .map(|reminder| reminder.opaque_id.as_str())
                    .collect();
                let supplied_ids: BTreeSet<&str> =
                    target.reminder_ids.iter().map(String::as_str).collect();
                if projected_ids != supplied_ids {
                    return Err(conflict(
                        "stale_snapshot",
                        "permanent delete reminder facts disagree with the projection",
                    ));
                }
            }
            delete_saf_projection_rows(&transaction, targets)?;
            let mut last_revision = 0_u64;
            let mut last_fingerprint = String::new();
            for target in targets {
                last_revision = target.expected_revision;
                last_fingerprint.clone_from(&target.result_fingerprint);
                deleted_memos.push(SafPermanentDeleteMemoResult {
                    memo_id: target.memo_id.clone(),
                    reminder_ids: target.reminder_ids.clone(),
                });
            }
            (last_revision, last_fingerprint)
        }
        SafProjectionMutationKind::Pin | SafProjectionMutationKind::Unpin => {
            let (revision, fingerprint, _source_path) = current.ok_or_else(|| {
                validation("memo_not_found", "SAF projection pin target is absent")
            })?;
            let expected_revision = i64::try_from(mutation.expected_revision)
                .map_err(|_error| validation("revision_overflow", "revision overflow"))?;
            if revision != expected_revision
                || mutation.expected_fingerprint.as_deref() != Some(fingerprint.as_str())
            {
                return Err(conflict(
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
                transaction
                    .execute(
                        "UPDATE memo SET is_pinned=1 WHERE memo_id=?1",
                        params![&mutation.memo_id],
                    )
                    .map_err(|error| from_sqlite(&error))?;
            } else {
                transaction
                    .execute(
                        "DELETE FROM memo_pin WHERE memo_id = ?1",
                        params![&mutation.memo_id],
                    )
                    .map_err(|error| from_sqlite(&error))?;
                transaction
                    .execute(
                        "UPDATE memo SET is_pinned=0 WHERE memo_id=?1",
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
    if let Some(history) = history {
        // Same indexer the rebuild path runs: the durable revision lands with
        // its materialized attachment references so media protection never
        // waits for the next full scan.
        index_scanned_history_revision(&transaction, history)?;
    }
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
        scopes: saf_projection_scopes(mutation.kind),
        idempotent_replay: false,
        deleted_memos,
    })
}

pub fn acknowledge_rebuilt_publication(
    connection: &Connection,
    publication: &crate::DocumentPublication,
    clock: crate::ProjectionClock,
) -> Result<SafProjectionCommitResult, lomo_core::LomoError> {
    publication.validate()?;
    let json = serde_json::to_string(publication)
        .map_err(|error| corruption("publication_digest_failed", &error.to_string()))?;
    let digest = fingerprint_content(&json);
    let transaction = connection
        .unchecked_transaction()
        .map_err(|error| from_sqlite(&error))?;
    if let Some(receipt) = find_publication_receipt(&transaction, &publication.mutation, &digest)? {
        return Ok(receipt);
    }
    if clock.core_revision == 0
        || clock.event_sequence == 0
        || clock.core_revision > crate::read_meta_u64(&transaction, "high_water_revision")?
        || clock.event_sequence > crate::read_meta_u64(&transaction, "event_sequence")?
    {
        return Err(corruption(
            "recovery_clock_mismatch",
            "rebuild must retain the frozen operation publication clock",
        ));
    }
    let (content_revision, file_fingerprint) =
        verify_rebuilt_publication(&transaction, publication)?;
    let mutation = &publication.mutation;
    transaction.execute(
        "INSERT INTO saf_mutation_operation(operation_id,mutation_digest,memo_id,core_revision,event_sequence,content_revision,file_fingerprint) VALUES(?1,?2,?3,?4,?5,?6,?7)",
        params![&mutation.operation_id, &digest, &mutation.memo_id, persisted_revision(clock.core_revision)?,
            persisted_revision(clock.event_sequence)?, persisted_revision(content_revision)?, &file_fingerprint],
    ).map_err(|error| from_sqlite(&error))?;
    transaction.commit().map_err(|error| from_sqlite(&error))?;
    Ok(SafProjectionCommitResult {
        operation_id: mutation.operation_id.clone(),
        memo_id: mutation.memo_id.clone(),
        core_revision: clock.core_revision,
        event_sequence: clock.event_sequence,
        content_revision,
        file_fingerprint,
        scopes: saf_projection_scopes(mutation.kind),
        idempotent_replay: true,
        deleted_memos: batch_deleted_facts(mutation),
    })
}

fn verify_rebuilt_publication(
    connection: &Connection,
    publication: &crate::DocumentPublication,
) -> Result<(u64, String), lomo_core::LomoError> {
    let mutation = &publication.mutation;
    let current = crate::query::get_projected_memo(connection, &mutation.memo_id)?;
    let desired_fingerprint = mutation
        .projection
        .as_ref()
        .map(|memo| memo.file_fingerprint.as_str())
        .or(mutation.expected_fingerprint.as_deref())
        .ok_or_else(|| {
            corruption(
                "recovery_facts_missing",
                "publication has no source fingerprint",
            )
        })?;
    if mutation.kind == SafProjectionMutationKind::PermanentDelete && current.is_none() {
        return Ok((mutation.expected_revision, desired_fingerprint.to_owned()));
    }
    if mutation.kind == SafProjectionMutationKind::PermanentDeleteMany {
        for target in &mutation.batch_targets {
            if crate::query::get_projected_memo(connection, &target.memo_id)?.is_some() {
                return Err(corruption(
                    "recovery_projection_mismatch",
                    "rebuilt projection still contains a permanently deleted memo",
                ));
            }
        }
        let last = mutation.batch_targets.last().ok_or_else(|| {
            corruption(
                "invalid_operation_plan",
                "permanent delete batch has no targets",
            )
        })?;
        return Ok((last.expected_revision, last.result_fingerprint.clone()));
    }
    let current = current.ok_or_else(|| {
        corruption(
            "recovery_memo_missing",
            "rebuild did not produce the committed memo",
        )
    })?;
    let expected_revision = match mutation.kind {
        SafProjectionMutationKind::Create
        | SafProjectionMutationKind::Update
        | SafProjectionMutationKind::HistoryRestore
        | SafProjectionMutationKind::Restore => mutation
            .expected_revision
            .checked_add(1)
            .ok_or_else(|| validation("revision_overflow", "recovery content revision overflow"))?,
        SafProjectionMutationKind::Delete
        | SafProjectionMutationKind::Pin
        | SafProjectionMutationKind::Unpin
        | SafProjectionMutationKind::PermanentDelete
        | SafProjectionMutationKind::PermanentDeleteMany => mutation.expected_revision,
    };
    let lifecycle_matches = match mutation.kind {
        SafProjectionMutationKind::Delete => current.summary.is_trashed,
        SafProjectionMutationKind::Pin => current.summary.is_pinned && !current.summary.is_trashed,
        SafProjectionMutationKind::Unpin => {
            !current.summary.is_pinned && !current.summary.is_trashed
        }
        SafProjectionMutationKind::PermanentDelete
        | SafProjectionMutationKind::PermanentDeleteMany => false,
        SafProjectionMutationKind::Create
        | SafProjectionMutationKind::Update
        | SafProjectionMutationKind::HistoryRestore
        | SafProjectionMutationKind::Restore => !current.summary.is_trashed,
    };
    if !lifecycle_matches
        || current.summary.content_revision != expected_revision
        || current.summary.file_fingerprint != desired_fingerprint
    {
        return Err(corruption(
            "recovery_projection_mismatch",
            "rebuilt memo differs from the frozen publication",
        ));
    }
    if let Some(projection) = &mutation.projection
        && (current.body != projection.body
            || current.summary.source_path != projection.source_path)
    {
        return Err(corruption(
            "recovery_projection_mismatch",
            "rebuilt memo body or path differs from the frozen publication",
        ));
    }
    if let Some(history) = &publication.history {
        let found: bool = connection.query_row(
            "SELECT EXISTS(SELECT 1 FROM revision_index WHERE memo_id=?1 AND history_record_id=?2 AND revision=?3 AND created_at_ms=?4 AND content=?5 AND file_fingerprint=?6)",
            params![&history.memo_id, &history.record_id, persisted_revision(history.revision)?, history.created_at_ms, &history.content, &history.file_fingerprint],
            |row| row.get(0),
        ).map_err(|error| from_sqlite(&error))?;
        if !found {
            return Err(corruption(
                "recovery_history_mismatch",
                "rebuilt history differs from the committed immutable object",
            ));
        }
    }
    Ok((expected_revision, desired_fingerprint.to_owned()))
}

/// Upper bound on targets in one permanent-delete batch. Session chunking stays far below this;
/// the bound exists so a hostile or corrupt frozen plan cannot fan out unbounded work.
const MAX_PERMANENT_DELETE_BATCH_TARGETS: usize = 4_096;
/// Upper bound on one reminder identity string.
const MAX_REMINDER_ID_LEN: usize = 512;

fn validate_permanent_delete_targets(
    targets: &[SafPermanentDeleteTarget],
) -> Result<(), lomo_core::LomoError> {
    if targets.is_empty() {
        return Err(validation(
            "empty_permanent_delete_batch",
            "permanent delete batch must contain at least one target",
        ));
    }
    if targets.len() > MAX_PERMANENT_DELETE_BATCH_TARGETS {
        return Err(validation(
            "permanent_delete_batch_too_large",
            "permanent delete batch exceeds the bounded target limit",
        ));
    }
    let mut seen = BTreeSet::new();
    let mut source_results = BTreeMap::new();
    for target in targets {
        if target.memo_id.trim().is_empty() || target.memo_id.len() > 512 {
            return Err(validation(
                "invalid_memo_id",
                "permanent delete memo identity must be non-empty and bounded",
            ));
        }
        if !seen.insert(&target.memo_id) {
            return Err(validation(
                "duplicate_permanent_delete_target",
                "permanent delete batch contains a duplicate memo identity",
            ));
        }
        if target.source_path.trim().is_empty() || target.source_path.len() > 4_096 {
            return Err(validation(
                "invalid_source_path",
                "permanent delete source path must be non-empty and bounded",
            ));
        }
        if target.expected_fingerprint.is_empty() || target.result_fingerprint.is_empty() {
            return Err(validation(
                "invalid_source_fingerprint",
                "permanent delete source fingerprints must be non-empty",
            ));
        }
        let mut reminder_ids = BTreeSet::new();
        for id in &target.reminder_ids {
            if id.trim().is_empty() || id.len() > MAX_REMINDER_ID_LEN || !reminder_ids.insert(id) {
                return Err(validation(
                    "invalid_reminder_facts",
                    "permanent delete reminder identities must be unique and bounded",
                ));
            }
        }
        if let Some(previous) =
            source_results.insert(&target.source_path, &target.result_fingerprint)
            && previous != &target.result_fingerprint
        {
            return Err(conflict(
                "saf_batch_source_fingerprint_conflict",
                "targets from one source document must share the final fingerprint",
            ));
        }
    }
    Ok(())
}

/// Removes every memo-owned projection row for a permanent-delete batch and projects the
/// tombstone each target's durable `.lomo/purged/` record carries in the same commit.
///
/// `history_attachment_ref`/`revision_index` are file-owned rows — the durable history
/// records survive the purge, so the projection keeps them exactly as a cold rescan would.
fn delete_saf_projection_rows(
    transaction: &Transaction<'_>,
    targets: &[SafPermanentDeleteTarget],
) -> Result<(), lomo_core::LomoError> {
    for target in targets {
        transaction
            .execute(
                "UPDATE memo SET file_fingerprint=?1 WHERE source_path=?2",
                params![&target.result_fingerprint, &target.source_path],
            )
            .map_err(|error| from_sqlite(&error))?;
        transaction
            .execute(
                "INSERT INTO memo_fts(memo_fts,rowid,search_content) SELECT 'delete',rowid,search_content FROM memo WHERE memo_id=?1",
                params![&target.memo_id],
            )
            .map_err(|error| from_sqlite(&error))?;
        for table in [
            "memo",
            "memo_trash",
            "memo_pin",
            "memo_tag",
            "attachment_ref",
        ] {
            let sql = format!("DELETE FROM {table} WHERE memo_id=?1");
            transaction
                .execute(&sql, params![&target.memo_id])
                .map_err(|error| from_sqlite(&error))?;
        }
        transaction
            .execute(
                "INSERT OR REPLACE INTO purged_memo(memo_id) VALUES(?1)",
                params![&target.memo_id],
            )
            .map_err(|error| from_sqlite(&error))?;
    }
    Ok(())
}

/// Begin facts for a SAF memo create whose workspace bytes are not yet durable.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SafMemoCreateBegin {
    pub operation_id: String,
    /// Filename stem of the target day document (product filename format).
    pub date_key: String,
    /// Header time part of the appended memo block.
    pub time_part: String,
    pub chronology_epoch_ms: i64,
    pub source_path: String,
    pub body: String,
}

/// One projection publication emitted by begin/rollback, shaped for invalidation buses.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SafMemoPublication {
    pub core_revision: u64,
    pub event_sequence: u64,
    pub scopes: Vec<lomo_core::InvalidationScope>,
}

/// Result of a begun SAF memo create: the identity every later step must agree on.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SafMemoCreateBeginResult {
    pub memo_id: String,
    pub core_revision: u64,
    pub event_sequence: u64,
    pub scopes: Vec<lomo_core::InvalidationScope>,
    pub idempotent_replay: bool,
}

/// Publishes a pending create projection before durable platform I/O.
///
/// The durable memo identity is minted here as an opaque CSPRNG `MemoId` (`m_` + hex). Date key
/// and time part remain document-layout facts, never the persistent ID. The pending row is
/// ordinary queryable projection state carrying `pending_operation_id`; the commit upgrades it in
/// place and rollback removes it. Crash between begin and commit is recovered by the open-time
/// sweep (or the next rebuild), never by durable half-state.
/// Publishes a pending SAF create using an already-open projection connection.
pub fn begin_saf_memo_create_on_connection(
    connection: &Connection,
    begin: &SafMemoCreateBegin,
) -> Result<SafMemoCreateBeginResult, lomo_core::LomoError> {
    validate_saf_memo_begin(begin)?;

    let transaction = connection
        .unchecked_transaction()
        .map_err(|error| from_sqlite(&error))?;

    if let Some(memo_id) = transaction
        .query_row(
            "SELECT memo_id FROM memo WHERE pending_operation_id = ?1",
            params![&begin.operation_id],
            |row| row.get::<_, String>(0),
        )
        .optional()
        .map_err(|error| from_sqlite(&error))?
    {
        let stored: String = transaction
            .query_row(
                "SELECT COALESCE(body, '') FROM memo WHERE memo_id = ?1",
                params![&memo_id],
                |row| row.get::<_, String>(0),
            )
            .map_err(|error| from_sqlite(&error))?;
        let pending = stored;
        if pending != begin.body {
            return Err(conflict(
                "saf_operation_conflict",
                "SAF operation id is already bound to a different begin body",
            ));
        }
        let (core_revision, event_sequence) = current_counters(&transaction)?;
        return Ok(SafMemoCreateBeginResult {
            memo_id,
            core_revision,
            event_sequence,
            scopes: saf_projection_scopes(SafProjectionMutationKind::Create),
            idempotent_replay: true,
        });
    }

    let memo_id = allocate_saf_create_identity(&transaction)?;
    insert_pending_memo_row(&transaction, begin, &memo_id)?;
    recompute_stats(&transaction)?;
    let (core_revision, event_sequence) = bump_counters(&transaction)?;
    transaction.commit().map_err(|error| from_sqlite(&error))?;
    Ok(SafMemoCreateBeginResult {
        memo_id,
        core_revision,
        event_sequence,
        scopes: saf_projection_scopes(SafProjectionMutationKind::Create),
        idempotent_replay: false,
    })
}

/// Validates begin facts at the furthest boundary before any projection state is touched.
fn validate_saf_memo_begin(begin: &SafMemoCreateBegin) -> Result<(), lomo_core::LomoError> {
    if begin.operation_id.trim().is_empty() || begin.operation_id.len() > 256 {
        return Err(validation(
            "invalid_saf_operation_id",
            "SAF projection operation id must be non-empty and bounded",
        ));
    }
    // Date/time shape belongs to the document layout, not to the durable MemoId.
    lomo_workspace::MemoIdentity::try_new(&begin.date_key, &begin.time_part, 0)?;
    if begin.chronology_epoch_ms <= 0 {
        return Err(validation(
            "invalid_memo_chronology",
            "SAF memo begin chronology must be a positive epoch millisecond",
        ));
    }
    lomo_workspace::WorkspaceRelativePath::parse(&begin.source_path)?;
    Ok(())
}

fn insert_pending_memo_row(
    transaction: &Transaction<'_>,
    begin: &SafMemoCreateBegin,
    memo_id: &str,
) -> Result<(), lomo_core::LomoError> {
    let facts = project_content_facts(&begin.body)?;
    let search_content = index_tokens(&begin.body);
    let preview = body_preview(&begin.body);
    let word_count = count_words(&begin.body);
    let char_count = count_characters(&begin.body);
    let fingerprint = fingerprint_content(&begin.body);
    transaction
        .execute(
            "INSERT INTO memo( \
             memo_id,source_path,file_fingerprint,has_todo,has_url,has_attachment, \
             created_at_ms,updated_at_ms,body_preview,body,search_content,word_count,char_count, \
             reminders_json,content_revision,pending_operation_id \
             ) VALUES(?1,?2,?3,?4,?5,?6,?7,?7,?8,?9,?10,?11,?12,'[]',1,?13)",
            params![
                memo_id,
                &begin.source_path,
                &fingerprint,
                i64::from(facts.has_todo),
                i64::from(facts.has_url),
                i64::from(!facts.attachment_paths.is_empty()),
                begin.chronology_epoch_ms,
                &preview,
                &begin.body,
                &search_content,
                word_count,
                char_count,
                &begin.operation_id,
            ],
        )
        .map_err(|error| from_sqlite(&error))?;
    let rowid = transaction.last_insert_rowid();
    transaction
        .execute(
            "INSERT INTO memo_fts(rowid, search_content) VALUES(?1, ?2)",
            params![rowid, &search_content],
        )
        .map_err(|error| from_sqlite(&error))?;
    Ok(())
}

/// Removes a pending SAF create using an already-open projection connection.
pub fn rollback_saf_memo_create_on_connection(
    connection: &Connection,
    operation_id: &str,
    memo_id: &str,
) -> Result<Option<SafMemoPublication>, lomo_core::LomoError> {
    if operation_id.trim().is_empty() || operation_id.len() > 256 {
        return Err(validation(
            "invalid_saf_operation_id",
            "SAF projection operation id must be non-empty and bounded",
        ));
    }
    if memo_id.trim().is_empty() || memo_id.len() > 512 {
        return Err(validation(
            "invalid_memo_id",
            "SAF projection memo id must be non-empty and bounded",
        ));
    }
    let transaction = connection
        .unchecked_transaction()
        .map_err(|error| from_sqlite(&error))?;
    let pending = transaction
        .query_row(
            "SELECT rowid, search_content FROM memo WHERE memo_id = ?1 AND pending_operation_id = ?2",
            params![memo_id, operation_id],
            |row| Ok((row.get::<_, i64>(0)?, row.get::<_, String>(1)?)),
        )
        .optional()
        .map_err(|error| from_sqlite(&error))?;
    let Some((rowid, search_content)) = pending else {
        return Ok(None);
    };
    transaction
        .execute(
            "INSERT INTO memo_fts(memo_fts, rowid, search_content) VALUES('delete', ?1, ?2)",
            params![rowid, &search_content],
        )
        .map_err(|error| from_sqlite(&error))?;
    let removed = transaction
        .execute(
            "DELETE FROM memo WHERE memo_id = ?1 AND pending_operation_id = ?2",
            params![memo_id, operation_id],
        )
        .map_err(|error| from_sqlite(&error))?;
    if removed != 1 {
        return Err(corruption(
            "saf_pending_rollback_missing_row",
            "pending create row disappeared between select and delete",
        ));
    }
    recompute_stats(&transaction)?;
    let (core_revision, event_sequence) = bump_counters(&transaction)?;
    transaction.commit().map_err(|error| from_sqlite(&error))?;
    Ok(Some(SafMemoPublication {
        core_revision,
        event_sequence,
        scopes: saf_projection_scopes(SafProjectionMutationKind::Create),
    }))
}

/// Mints an opaque CSPRNG `MemoId`. Historical date-time-ordinal IDs remain valid when already stored.
fn allocate_saf_create_identity(
    transaction: &Transaction<'_>,
) -> Result<String, lomo_core::LomoError> {
    for _ in 0..8 {
        let candidate = mint_opaque_memo_id()?;
        let exists = transaction
            .query_row(
                "SELECT 1 FROM memo WHERE memo_id = ?1",
                params![&candidate],
                |row| row.get::<_, i64>(0),
            )
            .optional()
            .map_err(|error| from_sqlite(&error))?;
        if exists.is_none() {
            return Ok(candidate);
        }
    }
    Err(conflict(
        "saf_projection_create_conflict",
        "CSPRNG memo identity collided repeatedly in the projection",
    ))
}

fn mint_opaque_memo_id() -> Result<String, lomo_core::LomoError> {
    let mut bytes = [0_u8; 16];
    getrandom::fill(&mut bytes).map_err(|error| {
        let diagnostic = format!("system CSPRNG getrandom failed: {error}");
        storage("csprng_read_failed", &diagnostic)
    })?;
    let mut hex = String::with_capacity(32);
    for byte in bytes {
        write!(hex, "{byte:02x}").map_err(|error| {
            let diagnostic = format!("{error}");
            storage("fmt_write_failed", &diagnostic)
        })?;
    }
    lomo_workspace::MemoId::parse(&format!("m_{hex}")).map(|id| id.as_str().to_owned())
}

fn current_counters(transaction: &Transaction<'_>) -> Result<(u64, u64), lomo_core::LomoError> {
    Ok((
        crate::read_meta_u64(transaction, "high_water_revision")?,
        crate::read_meta_u64(transaction, "event_sequence")?,
    ))
}

fn bump_counters(transaction: &Transaction<'_>) -> Result<(u64, u64), lomo_core::LomoError> {
    let (current_revision, current_sequence) = current_counters(transaction)?;
    let core_revision = current_revision
        .checked_add(1)
        .ok_or_else(|| validation("revision_overflow", "core revision overflow"))?;
    let event_sequence = current_sequence
        .checked_add(1)
        .ok_or_else(|| validation("event_sequence_overflow", "event sequence overflow"))?;
    crate::write_meta_u64(transaction, "high_water_revision", core_revision)?;
    crate::write_meta_u64(transaction, "event_sequence", event_sequence)?;
    Ok((core_revision, event_sequence))
}

fn saf_projection_scopes(kind: SafProjectionMutationKind) -> Vec<lomo_core::InvalidationScope> {
    memo_command_scopes(match kind {
        SafProjectionMutationKind::Create => MemoCommandKind::Create,
        SafProjectionMutationKind::Update => MemoCommandKind::Update,
        SafProjectionMutationKind::HistoryRestore => MemoCommandKind::HistoryRestore,
        SafProjectionMutationKind::Delete => MemoCommandKind::Delete,
        SafProjectionMutationKind::Restore => MemoCommandKind::Restore,
        SafProjectionMutationKind::PermanentDelete
        | SafProjectionMutationKind::PermanentDeleteMany => MemoCommandKind::PermanentDelete,
        SafProjectionMutationKind::Pin => MemoCommandKind::Pin,
        SafProjectionMutationKind::Unpin => MemoCommandKind::Unpin,
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
    connection: &Transaction<'_>,
    projection: &ScannedMemoProjection,
    revision: u64,
) -> Result<(), lomo_core::LomoError> {
    connection
        .execute(
            "UPDATE memo SET file_fingerprint=?1 WHERE source_path=?2",
            params![&projection.file_fingerprint, &projection.source_path],
        )
        .map_err(|error| from_sqlite(&error))?;
    let existing: Option<i64> = connection
        .query_row(
            "SELECT rowid FROM memo WHERE memo_id = ?1",
            params![&projection.memo_id],
            |row| row.get(0),
        )
        .optional()
        .map_err(|error| from_sqlite(&error))?;
    let search_content = index_tokens(&projection.body);
    let preview = body_preview(&projection.body);
    let word_count = count_words(&projection.body);
    let char_count = count_characters(&projection.body);
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
                "UPDATE memo SET source_path=?1,file_fingerprint=?2,has_todo=?3,has_url=?4,has_attachment=?5,updated_at_ms=?6,body_preview=?7,body=?8,search_content=?9,word_count=?10,char_count=?11,content_revision=?12,reminders_json=?13,is_trashed=0 WHERE memo_id=?14",
                params![
                    &projection.source_path,
                    &projection.file_fingerprint,
                    i64::from(projection.has_todo),
                    i64::from(projection.has_url),
                    i64::from(!projection.attachment_paths.is_empty()),
                    projection.chronology_epoch_ms,
                    preview,
                    &projection.body,
                    search_content,
                    word_count,
                    char_count,
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
                "INSERT INTO memo(memo_id,source_path,file_fingerprint,has_todo,has_url,has_attachment,created_at_ms,updated_at_ms,body_preview,body,search_content,word_count,char_count,content_revision,reminders_json,is_pinned,is_trashed) VALUES(?1,?2,?3,?4,?5,?6,?7,?7,?8,?9,?10,?11,?12,?13,?14,0,0)",
                params![
                    &projection.memo_id,
                    &projection.source_path,
                    &projection.file_fingerprint,
                    i64::from(projection.has_todo),
                    i64::from(projection.has_url),
                    i64::from(!projection.attachment_paths.is_empty()),
                    projection.chronology_epoch_ms,
                    preview,
                    &projection.body,
                    search_content,
                    word_count,
                    char_count,
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
    // Sweep protection keys attachment rows by the canonical workspace path; spellings that
    // resolve to one file (`media/./x`, `media//x`) share one key. Destinations that cannot
    // name a workspace file (external objects, root escapes) carry no key and get no row.
    for key in canonical_attachment_keys(&projection.attachment_paths) {
        connection
            .execute(
                "INSERT OR IGNORE INTO attachment_ref(memo_id, relative_path) VALUES(?1, ?2)",
                params![&projection.memo_id, &key],
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
    workspace_evidence: BTreeMap<String, ScannedProjectionEvidence>,
    /// Identities a durable state head attested a pin verdict for. The finish-time
    /// private-state carry-over must not copy a live-cache `memo_pin` row for any
    /// of them — durable state already owns their verdict.
    pin_attested_ids: BTreeSet<String>,
}

struct ScannedProjectionEvidence {
    fingerprint: String,
    /// The committed `is_trashed` bit — lifecycle membership is part of the row image
    /// the finish-time compare certifies, never a detail the evidence can drop.
    trashed: bool,
    attachment_count: u64,
    /// `(trashed_at_ms, record_digest)` committed into `memo_trash` for this identity.
    trash_attestation: Option<(i64, String)>,
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
            workspace_evidence: BTreeMap::new(),
            pin_attested_ids: BTreeSet::new(),
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
        let mut page_evidence = Vec::with_capacity(memos.len());
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
            page_evidence.push((
                memo.memo_id.clone(),
                ScannedProjectionEvidence {
                    fingerprint: memo.file_fingerprint.clone(),
                    trashed: memo.source_path.starts_with("trash/"),
                    attachment_count: u64::try_from(
                        canonical_attachment_keys(&memo.attachment_paths).len(),
                    )
                    .map_err(|_error| {
                        validation("attachment_count_overflow", "attachment count exceeds u64")
                    })?,
                    trash_attestation: None,
                },
            ));
        }
        transaction.commit().map_err(|error| from_sqlite(&error))?;
        for (memo_id, evidence) in page_evidence {
            if self.workspace_evidence.insert(memo_id, evidence).is_some() {
                return Err(corruption(
                    "duplicate_saf_projection_memo",
                    "committed SAF projection evidence contains a duplicate memo identity",
                ));
            }
        }
        Ok(())
    }

    /// Appends durable trash-record facts after active document pages.
    ///
    /// One active projection and one trash record for the same identity are expected: the trash
    /// snapshot owns recoverable body/semantic facts, while the current active document (or one of
    /// its siblings) owns the canonical source fingerprint.
    ///
    /// # Errors
    ///
    /// Returns validation for oversized/duplicate/malformed trash pages, inconsistent source
    /// identity, or storage errors while atomically merging the page.
    pub fn append_trash_page(
        &mut self,
        trash_memos: &[ScannedTrashProjection],
    ) -> Result<(), lomo_core::LomoError> {
        if trash_memos.len() > MAX_SAF_PROJECTION_PAGE_SIZE {
            return Err(validation(
                "saf_projection_page_too_large",
                "SAF trash projection rebuild page exceeds 256 memos",
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
        let mut page_evidence = Vec::with_capacity(trash_memos.len());
        for trash in trash_memos {
            validate_scanned_projection(&trash.memo)?;
            if trash.trashed_at_ms <= 0 {
                return Err(validation(
                    "invalid_trash_timestamp",
                    "SAF trash timestamp must be a positive epoch millisecond",
                ));
            }
            if !page_ids.insert(trash.memo.memo_id.as_str()) {
                return Err(validation(
                    "duplicate_saf_trash_memo",
                    "SAF trash page contains a duplicate memo identity",
                ));
            }
            page_evidence.push(merge_trash_projection(&transaction, trash)?);
        }
        transaction.commit().map_err(|error| from_sqlite(&error))?;
        for (memo_id, evidence) in page_evidence {
            self.workspace_evidence.insert(memo_id, evidence);
        }
        Ok(())
    }

    /// Appends verified durable history snapshots to the pending projection.
    ///
    /// # Errors
    ///
    /// Returns validation for malformed/duplicate snapshots or storage failures.
    pub fn append_history_page(
        &mut self,
        revisions: &[ScannedHistoryProjection],
    ) -> Result<(), lomo_core::LomoError> {
        if revisions.len() > MAX_SAF_PROJECTION_PAGE_SIZE {
            return Err(validation(
                "saf_projection_page_too_large",
                "SAF history projection rebuild page exceeds 256 revisions",
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
        for revision in revisions {
            if revision.memo_id.trim().is_empty()
                || revision.record_id.is_empty()
                || revision.revision == 0
                || revision.created_at_ms <= 0
                || revision.file_fingerprint.trim().is_empty()
            {
                return Err(validation(
                    "invalid_history_projection",
                    "history projection requires memo, revision, time, and fingerprint",
                ));
            }
            if !page_ids.insert((revision.memo_id.as_str(), revision.record_id.as_str())) {
                return Err(validation(
                    "duplicate_saf_history_revision",
                    "SAF history page contains a duplicate revision",
                ));
            }
            index_scanned_history_revision(&transaction, revision)?;
        }
        transaction.commit().map_err(|error| from_sqlite(&error))
    }

    /// Appends durable purge tombstone identities decoded by the workspace scan.
    ///
    /// # Errors
    /// Returns validation for oversized pages or storage failures.
    pub fn append_purged_page(&mut self, memo_ids: &[String]) -> Result<(), lomo_core::LomoError> {
        if memo_ids.len() > MAX_SAF_PROJECTION_PAGE_SIZE {
            return Err(validation(
                "saf_projection_page_too_large",
                "SAF purged projection rebuild page exceeds 256 identities",
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
        for memo_id in memo_ids {
            if memo_id.trim().is_empty() || !page_ids.insert(memo_id.as_str()) {
                return Err(validation(
                    "invalid_purged_memo_identity",
                    "purged page requires distinct non-empty memo ids",
                ));
            }
            transaction
                .execute(
                    "INSERT INTO purged_memo(memo_id) VALUES(?1)",
                    params![memo_id],
                )
                .map_err(|error| from_sqlite(&error))?;
        }
        transaction.commit().map_err(|error| from_sqlite(&error))
    }

    /// Appends verified workspace listing rows admitted with this projection commit.
    ///
    /// # Errors
    /// Returns validation for oversized pages or storage failures.
    pub fn append_listing_page(
        &mut self,
        rows: &[ScannedListingRow],
    ) -> Result<(), lomo_core::LomoError> {
        if rows.len() > MAX_SAF_PROJECTION_PAGE_SIZE {
            return Err(validation(
                "saf_projection_page_too_large",
                "SAF listing projection rebuild page exceeds 256 rows",
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
        let mut page_paths = BTreeSet::new();
        for row in rows {
            if row.path.trim().is_empty()
                || row.digest.trim().is_empty()
                || !page_paths.insert(row.path.as_str())
            {
                return Err(validation(
                    "invalid_listing_row",
                    "listing page requires distinct non-empty verified paths and digests",
                ));
            }
            transaction
                .execute(
                    "INSERT INTO file_listing(path, digest) VALUES(?1, ?2)",
                    params![&row.path, &row.digest],
                )
                .map_err(|error| from_sqlite(&error))?;
        }
        transaction.commit().map_err(|error| from_sqlite(&error))
    }

    /// Appends verified durable pin facts to the pending projection.
    ///
    /// # Errors
    ///
    /// Returns validation for malformed/duplicate pin facts or storage failures.
    pub fn append_pin_page(
        &mut self,
        pins: &[ScannedPinProjection],
    ) -> Result<(), lomo_core::LomoError> {
        if pins.len() > MAX_SAF_PROJECTION_PAGE_SIZE {
            return Err(validation(
                "saf_projection_page_too_large",
                "SAF pin projection rebuild page exceeds 256 pins",
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
        for pin in pins {
            if pin.memo_id.trim().is_empty() {
                return Err(validation(
                    "invalid_memo_id",
                    "SAF pin projection requires a non-empty memo id",
                ));
            }
            if pin.pinned_at_ms <= 0 {
                return Err(validation(
                    "invalid_pin_timestamp",
                    "SAF pin projection requires a positive epoch millisecond",
                ));
            }
            transaction
                .execute(
                    "INSERT OR REPLACE INTO memo_pin(memo_id, pinned_at_ms) VALUES(?1, ?2)",
                    params![&pin.memo_id, pin.pinned_at_ms],
                )
                .map_err(|error| from_sqlite(&error))?;
            transaction
                .execute(
                    "UPDATE memo SET is_pinned=1 WHERE memo_id=?1",
                    params![&pin.memo_id],
                )
                .map_err(|error| from_sqlite(&error))?;
        }
        transaction.commit().map_err(|error| from_sqlite(&error))
    }

    /// Records the memo identities a durable state head attested a pin verdict for.
    ///
    /// The verdict may be pinned or unpinned — either way durable state owns the answer for
    /// that identity, so the finish-time private-state carry-over treats a live-cache
    /// `memo_pin` row for an attested identity as stale evidence and keeps the scanned
    /// fact (or its deliberate absence) authoritative. Identities never attested keep the
    /// legacy app-private carry-over.
    ///
    /// # Errors
    ///
    /// Returns validation when the rebuild is already finished or aborted.
    pub fn append_pin_attestations(
        &mut self,
        memo_ids: &BTreeSet<String>,
    ) -> Result<(), lomo_core::LomoError> {
        if self.connection.is_none() {
            return Err(validation(
                "saf_projection_rebuild_closed",
                "SAF projection rebuild is already finished or aborted",
            ));
        }
        self.pin_attested_ids.extend(memo_ids.iter().cloned());
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
        copy_saf_private_state(&self.live_db, &connection, &self.pin_attested_ids)?;
        recompute_stats(&connection)?;
        crate::write_meta_u64(&connection, "high_water_revision", self.high_water_revision)?;
        crate::write_meta_u64(&connection, "event_sequence", self.event_sequence)?;
        ensure_quick_check(&connection, "SAF projection temp")?;
        let mut image = ScannedRowImage::default();
        for (memo_id, evidence) in &self.workspace_evidence {
            image.memo_rows.push((
                memo_id.clone(),
                evidence.fingerprint.clone(),
                evidence.trashed,
            ));
            if let Some((trashed_at_ms, digest)) = &evidence.trash_attestation {
                image
                    .trash_attestations
                    .push((memo_id.clone(), *trashed_at_ms, digest.clone()));
            }
        }
        image.attachment_count =
            self.workspace_evidence
                .values()
                .try_fold(0_u64, |total, evidence| {
                    total.checked_add(evidence.attachment_count).ok_or_else(|| {
                        validation("attachment_count_overflow", "attachment count exceeds u64")
                    })
                })?;
        let memos_indexed = u64::try_from(self.workspace_evidence.len())
            .map_err(|_error| validation("memo_count_overflow", "memo count exceeds u64"))?;
        let evidence = compare_scanned_rows_to_store(&mut image, &connection)?;
        drop(connection);
        finish_atomic_replace(&self.live_db, &self.temp_db, &self.live_bak)?;
        Ok(RebuildResult {
            memos_indexed,
            file_count: evidence.file_count,
            attachment_count: evidence.attachment_count,
            workspace_digest: evidence.workspace_digest,
            store_digest: evidence.store_digest,
            corrupt_lomo_isolated: 0,
            high_water_revision: self.high_water_revision,
            rewritten: true,
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

fn merge_trash_projection(
    transaction: &Transaction<'_>,
    trash: &ScannedTrashProjection,
) -> Result<(String, ScannedProjectionEvidence), lomo_core::LomoError> {
    let already_trashed: i64 = transaction
        .query_row(
            "SELECT COUNT(*) FROM memo_trash WHERE memo_id=?1",
            params![&trash.memo.memo_id],
            |row| row.get(0),
        )
        .map_err(|error| from_sqlite(&error))?;
    if already_trashed != 0 {
        return Err(validation(
            "duplicate_saf_trash_memo",
            "SAF projection rebuild received a duplicate trash record",
        ));
    }
    let current = transaction
        .query_row(
            "SELECT content_revision,source_path,file_fingerprint FROM memo WHERE memo_id=?1",
            params![&trash.memo.memo_id],
            |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                ))
            },
        )
        .optional()
        .map_err(|error| from_sqlite(&error))?;
    // A surviving memo row for the same identity is itself the live-document attestation:
    // it is either a row this commit inserted from a fresh document read, or a committed
    // document-derived row — a removed document retires every lifecycle row before this
    // merge runs, and the incremental caller retires any record-echo row before
    // re-claiming the identity, so reaching here with `current` present proves the
    // source document still emits the identity.
    let canonical_fingerprint = if let Some((_revision, source_path, fingerprint)) = &current {
        if source_path != &trash.memo.source_path {
            return Err(validation(
                "saf_trash_source_path_mismatch",
                "active memo and trash record disagree on the source document",
            ));
        }
        fingerprint.clone()
    } else {
        // No row of this memo survives — the document may still be attested by sibling rows
        // sharing the source path; once the source document is gone no row can anchor the
        // fingerprint, so the durable trash record's stored fingerprint is the only truth
        // left, exactly as a cold scan derives it.
        crate::query::sibling_document_fingerprint(
            transaction,
            &trash.memo.source_path,
            &trash.memo.memo_id,
        )?
        .unwrap_or_else(|| trash.memo.file_fingerprint.clone())
    };
    let mut merged = trash.memo.clone();
    merged.file_fingerprint.clone_from(&canonical_fingerprint);
    if let Some((revision, _, _)) = current {
        let revision = u64::try_from(revision).map_err(|_error| {
            corruption(
                "invalid_content_revision",
                "SAF trash target has a negative content revision",
            )
        })?;
        upsert_saf_projection(transaction, &merged, revision)?;
    } else {
        index_scanned_memo(transaction, &merged)?;
    }
    let record_digest = trash.attestation_digest()?;
    transaction
        .execute(
            "INSERT INTO memo_trash(memo_id,trashed_at_ms,record_digest) VALUES(?1,?2,?3)",
            params![&merged.memo_id, trash.trashed_at_ms, &record_digest],
        )
        .map_err(|error| from_sqlite(&error))?;
    transaction
        .execute(
            "UPDATE memo SET is_trashed=1 WHERE memo_id=?1",
            params![&merged.memo_id],
        )
        .map_err(|error| from_sqlite(&error))?;
    let attachment_count = u64::try_from(canonical_attachment_keys(&merged.attachment_paths).len())
        .map_err(|_error| {
            validation("attachment_count_overflow", "attachment count exceeds u64")
        })?;
    Ok((
        merged.memo_id,
        ScannedProjectionEvidence {
            fingerprint: canonical_fingerprint,
            trashed: true,
            attachment_count,
            trash_attestation: Some((trash.trashed_at_ms, record_digest)),
        },
    ))
}

/// Upserts a durable trash fact: an existing `memo_trash` row is replaced rather than rejected,
/// which makes the merge idempotent for path-scoped re-application.
fn upsert_trash_projection(
    transaction: &Transaction<'_>,
    trash: &ScannedTrashProjection,
) -> Result<(), lomo_core::LomoError> {
    transaction
        .execute(
            "DELETE FROM memo_trash WHERE memo_id=?1",
            params![&trash.memo.memo_id],
        )
        .map_err(|error| from_sqlite(&error))?;
    merge_trash_projection(transaction, trash).map(|_| ())
}

/// Indexes one verified durable history revision and its derived attachment references.
///
/// `history_attachment_ref` rows are parsed from the revision body at index time so media
/// protection reads projected rows instead of re-parsing retained bodies on every observation.
fn index_scanned_history_revision(
    connection: &Connection,
    revision: &ScannedHistoryProjection,
) -> Result<(), lomo_core::LomoError> {
    connection
        .execute(
            "INSERT OR REPLACE INTO revision_index( \
             memo_id,revision,history_record_id,created_at_ms,content,file_fingerprint \
             ) VALUES(?1,?2,?3,?4,?5,?6)",
            params![
                &revision.memo_id,
                persisted_revision(revision.revision)?,
                &revision.record_id,
                revision.created_at_ms,
                &revision.content,
                &revision.file_fingerprint,
            ],
        )
        .map_err(|error| from_sqlite(&error))?;
    connection
        .execute(
            "UPDATE memo SET content_revision = MAX(content_revision, ?1) WHERE memo_id = ?2",
            params![persisted_revision(revision.revision)?, &revision.memo_id],
        )
        .map_err(|error| from_sqlite(&error))?;
    connection
        .execute(
            "DELETE FROM history_attachment_ref WHERE memo_id=?1 AND revision=?2",
            params![&revision.memo_id, persisted_revision(revision.revision)?],
        )
        .map_err(|error| from_sqlite(&error))?;
    for key in
        canonical_attachment_keys(&project_content_facts(&revision.content)?.attachment_paths)
    {
        connection
            .execute(
                "INSERT OR IGNORE INTO history_attachment_ref(memo_id,revision,relative_path) \
                 VALUES(?1,?2,?3)",
                params![
                    &revision.memo_id,
                    persisted_revision(revision.revision)?,
                    key
                ],
            )
            .map_err(|error| from_sqlite(&error))?;
    }
    Ok(())
}

/// Removes one memo projection row and every memo-owned derived row (tags, FTS,
/// lifecycle membership, attachment refs).
///
/// `memo_tag`, `attachment_ref`, `memo_pin` and `memo_trash` cascade through foreign keys;
/// the `memo_fts` virtual table is cleaned explicitly. Durable history (`revision_index`,
/// `history_attachment_ref`) is file-owned, not row-owned: `.lomo/history` records outlive a
/// memo's projection row, so scan-driven removal keeps them — a fresh rebuild re-indexes the
/// same records — and `history_replaces` owns history edits explicitly.
fn delete_memo_row(connection: &Connection, memo_id: &str) -> Result<(), lomo_core::LomoError> {
    connection
        .execute(
            "INSERT INTO memo_fts(memo_fts,rowid,search_content) \
             SELECT 'delete',rowid,search_content FROM memo WHERE memo_id=?1",
            params![memo_id],
        )
        .map_err(|error| from_sqlite(&error))?;
    connection
        .execute("DELETE FROM memo WHERE memo_id=?1", params![memo_id])
        .map_err(|error| from_sqlite(&error))?;
    Ok(())
}

/// Content digest over the rows an incremental apply can change: memo projections, lifecycle
/// membership, pin facts, purge tombstones and durable history. A scoped apply whose gathered
/// facts prove content-identical leaves this digest untouched, which is exactly the evidence
/// `rewritten` reports.
fn projection_state_digest(connection: &Connection) -> Result<String, lomo_core::LomoError> {
    let mut facts = Vec::new();
    for (table, columns) in [
        ("memo", "memo_id, file_fingerprint"),
        ("memo_pin", "memo_id, pinned_at_ms"),
        ("memo_trash", "memo_id, trashed_at_ms, record_digest"),
        ("purged_memo", "memo_id"),
        ("revision_index", "memo_id, history_record_id, revision"),
    ] {
        let sql = format!("SELECT {columns} FROM {table} ORDER BY {columns}");
        let mut statement = connection
            .prepare(&sql)
            .map_err(|error| from_sqlite(&error))?;
        let mut rows = statement.query([]).map_err(|error| from_sqlite(&error))?;
        while let Some(row) = rows.next().map_err(|error| from_sqlite(&error))? {
            let mut key = format!("{table}:");
            for index in 0..row.as_ref().column_count() {
                let value = row.get_ref(index).map_err(|error| from_sqlite(&error))?;
                if index > 0 {
                    key.push('|');
                }
                match value {
                    rusqlite::types::ValueRef::Text(text) => {
                        key.push_str(std::str::from_utf8(text).unwrap_or("\u{fffd}"));
                    }
                    rusqlite::types::ValueRef::Integer(number) => {
                        key.push_str(&number.to_string());
                    }
                    rusqlite::types::ValueRef::Null
                    | rusqlite::types::ValueRef::Real(_)
                    | rusqlite::types::ValueRef::Blob(_) => {
                        return Err(corruption(
                            "digest_fact_coerce",
                            "unexpected projection fact type",
                        ));
                    }
                }
            }
            facts.push((table.to_owned(), key));
        }
    }
    facts.sort();
    Ok(aggregate_memo_digest(&facts))
}

/// Replaces one memo's durable history projection rows with the caller's complete set.
fn apply_history_replace(
    transaction: &Transaction<'_>,
    replace: &ScannedMemoHistoryReplace,
) -> Result<(), lomo_core::LomoError> {
    transaction
        .execute(
            "DELETE FROM revision_index WHERE memo_id=?1",
            params![&replace.memo_id],
        )
        .map_err(|error| from_sqlite(&error))?;
    transaction
        .execute(
            "DELETE FROM history_attachment_ref WHERE memo_id=?1",
            params![&replace.memo_id],
        )
        .map_err(|error| from_sqlite(&error))?;
    let mut seen = BTreeSet::new();
    for revision in &replace.revisions {
        if revision.memo_id != replace.memo_id || !seen.insert(revision.record_id.as_str()) {
            return Err(validation(
                "invalid_history_projection",
                "incremental history facts must belong to one owner and stay distinct",
            ));
        }
        index_scanned_history_revision(transaction, revision)?;
    }
    Ok(())
}

/// Inserts one purge tombstone and removes any memo row that exists only through trash
/// membership — a fresh scan filters the tombstoned memo's trash record before it ever
/// projects, so the incremental apply must suppress the same resurrection.
fn apply_purged_id(
    transaction: &Transaction<'_>,
    memo_id: &str,
) -> Result<(), lomo_core::LomoError> {
    transaction
        .execute(
            "INSERT OR REPLACE INTO purged_memo(memo_id) VALUES(?1)",
            params![memo_id],
        )
        .map_err(|error| from_sqlite(&error))?;
    transaction
        .execute(
            "INSERT INTO memo_fts(memo_fts,rowid,search_content) \
             SELECT 'delete',rowid,search_content FROM memo WHERE memo_id=?1 \
             AND EXISTS(SELECT 1 FROM memo_trash WHERE memo_trash.memo_id=?1)",
            params![memo_id],
        )
        .map_err(|error| from_sqlite(&error))?;
    transaction
        .execute(
            "DELETE FROM memo WHERE memo_id=?1 AND EXISTS(\
                SELECT 1 FROM memo_trash WHERE memo_trash.memo_id=memo.memo_id)",
            params![memo_id],
        )
        .map_err(|error| from_sqlite(&error))?;
    Ok(())
}

/// Applies path-scoped scanned facts to the live projection in one transaction.
///
/// The caller — the workspace application layer — supplies only facts whose provenance is proven
/// scoped to changed paths. Aggregate state (`stats`, lifecycle bits, `content_revision`,
/// `file_listing` snapshot and its digest meta) is recomputed inside the same commit so the live
/// projection stays indistinguishable from a fresh full rebuild of the same durable facts.
///
/// Returns the committed publication clock when the projection content actually changed; a
/// content-identical fact set (e.g. a document re-parsed after only its listing token drifted)
/// returns `None` and leaves the high-water clock alone, exactly like a reconcile that skipped
/// the rewrite. The returned clock is the authoritative post-commit value — the owning `Store`
/// re-anchors its cached counters to it, as on every other commit boundary.
///
/// # Errors
/// Returns validation for malformed facts and storage errors; nothing commits on failure.
pub fn apply_scanned_incremental(
    connection: &Connection,
    facts: &ScannedIncrementalFacts,
) -> Result<Option<crate::ProjectionClock>, lomo_core::LomoError> {
    let transaction = connection
        .unchecked_transaction()
        .map_err(|error| from_sqlite(&error))?;
    let before = projection_state_digest(&transaction)?;

    for memo_id in &facts.memo_removes {
        delete_memo_row(&transaction, memo_id)?;
    }

    // Suppression lands before any claim — the gather phases prove the tombstone
    // set first for the same reason: a row a tombstoned record committed must be
    // gone before the merges re-derive siblings, or an echo row could anchor a
    // claim no scan would produce.
    for memo_id in &facts.purged_removes {
        transaction
            .execute("DELETE FROM purged_memo WHERE memo_id=?1", params![memo_id])
            .map_err(|error| from_sqlite(&error))?;
    }
    for memo_id in &facts.purged_upserts {
        apply_purged_id(&transaction, memo_id)?;
    }

    for memo in &facts.memo_upserts {
        upsert_saf_projection(&transaction, memo, 1)?;
    }

    for trash in &facts.trash_upserts {
        if trash.trashed_at_ms <= 0 {
            return Err(validation(
                "invalid_trash_timestamp",
                "trash timestamp must be a positive epoch millisecond",
            ));
        }
        upsert_trash_projection(&transaction, trash)?;
    }

    for replace in &facts.history_replaces {
        apply_history_replace(&transaction, replace)?;
    }

    for memo_id in &facts.pin_removes {
        transaction
            .execute("DELETE FROM memo_pin WHERE memo_id=?1", params![memo_id])
            .map_err(|error| from_sqlite(&error))?;
    }
    for pin in &facts.pin_upserts {
        if pin.memo_id.trim().is_empty() || pin.pinned_at_ms <= 0 {
            return Err(validation(
                "invalid_pin_projection",
                "pin projection requires a memo id and positive timestamp",
            ));
        }
        transaction
            .execute(
                "INSERT OR REPLACE INTO memo_pin(memo_id, pinned_at_ms) VALUES(?1, ?2)",
                params![&pin.memo_id, pin.pinned_at_ms],
            )
            .map_err(|error| from_sqlite(&error))?;
    }

    for path in &facts.listing_removes {
        transaction
            .execute("DELETE FROM file_listing WHERE path=?1", params![path])
            .map_err(|error| from_sqlite(&error))?;
    }
    for row in &facts.listing_upserts {
        if row.path.trim().is_empty() || row.digest.trim().is_empty() {
            return Err(validation(
                "invalid_listing_row",
                "listing rows require verified paths and digests",
            ));
        }
        transaction
            .execute(
                "INSERT OR REPLACE INTO file_listing(path, digest) VALUES(?1, ?2)",
                params![&row.path, &row.digest],
            )
            .map_err(|error| from_sqlite(&error))?;
    }

    // Derived aggregate state mirrors exactly what a fresh materialize produces: lifecycle bits
    // follow membership tables plus the `trash/` source-path convention, `content_revision` is
    // the durable history ceiling, and orphan tag rows never survive a rebuild.
    transaction
        .execute_batch(
            "UPDATE memo SET is_trashed = CASE WHEN EXISTS(\
                 SELECT 1 FROM memo_trash WHERE memo_trash.memo_id = memo.memo_id\
             ) OR source_path GLOB 'trash/*' THEN 1 ELSE 0 END;\
             UPDATE memo SET is_pinned = CASE WHEN EXISTS(\
                 SELECT 1 FROM memo_pin WHERE memo_pin.memo_id = memo.memo_id\
             ) THEN 1 ELSE 0 END;\
             UPDATE memo SET content_revision = MAX(1, COALESCE((\
                 SELECT MAX(revision) FROM revision_index \
                 WHERE revision_index.memo_id = memo.memo_id\
             ), 0));\
             DELETE FROM tag WHERE id NOT IN (SELECT tag_id FROM memo_tag);",
        )
        .map_err(|error| from_sqlite(&error))?;

    // Post-apply self-certification: a merged trash row may only ever commit what a
    // cold scan of the same durable facts produces. The row must sit at the record's
    // claimed source path — the merge writes the claim, never a stale lane position —
    // and a row no live document attests must carry the fingerprint the sibling-or-
    // claim rule derives from this transaction's final row set, not an earlier claim
    // it happened to inherit. Document-attested rows are the attestation itself, so
    // only their lane position is checked.
    for trash in &facts.trash_upserts {
        let merged_row = transaction
            .query_row(
                "SELECT source_path,file_fingerprint FROM memo WHERE memo_id=?1",
                params![&trash.memo.memo_id],
                |row| Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?)),
            )
            .optional()
            .map_err(|error| from_sqlite(&error))?;
        let Some((source_path, file_fingerprint)) = merged_row else {
            return Err(corruption(
                "incremental_merge_diverged",
                "applied trash fact committed no memo row",
            ));
        };
        if source_path != trash.memo.source_path {
            return Err(corruption(
                "incremental_merge_diverged",
                "merged trash row sits off the record's claimed source path",
            ));
        }
        if !facts.doc_attested_ids.contains(&trash.memo.memo_id) {
            let expected = crate::query::sibling_document_fingerprint(
                &transaction,
                &trash.memo.source_path,
                &trash.memo.memo_id,
            )?
            .unwrap_or_else(|| trash.memo.file_fingerprint.clone());
            if file_fingerprint != expected {
                return Err(corruption(
                    "incremental_merge_diverged",
                    "merged trash row anchored a fingerprint the merge rules cannot derive",
                ));
            }
        }
    }

    recompute_stats(&transaction)?;

    let mut pairs = Vec::new();
    {
        let mut statement = transaction
            .prepare("SELECT path,digest FROM file_listing ORDER BY path")
            .map_err(|error| from_sqlite(&error))?;
        let rows = statement
            .query_map([], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            })
            .map_err(|error| from_sqlite(&error))?;
        for row in rows {
            pairs.push(row.map_err(|error| from_sqlite(&error))?);
        }
    }
    crate::write_meta_string(
        &transaction,
        "workspace_listing_digest",
        &aggregate_memo_digest(&pairs),
    )?;

    let after = projection_state_digest(&transaction)?;
    // A scoped commit that moved projection content is still a projection revision: the
    // high-water clock and event sequence advance exactly as a full rewrite advances them.
    let clock = if before == after {
        None
    } else {
        let (core_revision, event_sequence) = bump_counters(&transaction)?;
        Some(crate::ProjectionClock {
            core_revision,
            event_sequence,
        })
    };

    transaction.commit().map_err(|error| from_sqlite(&error))?;
    Ok(clock)
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
        let trash_records = list_trash_records(workspace_root)?;
        let memo_file_count = u64::try_from(memo_files.len()).map_err(|_error| {
            validation(
                "rebuild_file_count_overflow",
                "memo file count does not fit the rebuild progress width",
            )
        })?;
        let trash_record_count = u64::try_from(trash_records.len()).map_err(|_error| {
            validation(
                "rebuild_trash_count_overflow",
                "trash record count does not fit the rebuild progress width",
            )
        })?;
        checkpoint.total_hint =
            memo_file_count
                .checked_add(trash_record_count)
                .ok_or_else(|| {
                    validation(
                        "rebuild_progress_overflow",
                        "rebuild progress total exceeds the supported width",
                    )
                })?;
        checkpoint.phase = RebuildPhase::Indexing;
        save_checkpoint(&checkpoint_path, &checkpoint)?;

        let start = usize::try_from(checkpoint.scanned).map_err(|_error| {
            validation(
                "rebuild_checkpoint_invalid",
                "rebuild checkpoint scanned count does not fit this platform",
            )
        })?;
        if start > memo_files.len() {
            return Err(validation(
                "rebuild_checkpoint_invalid",
                "rebuild checkpoint scanned count exceeds memo file count",
            ));
        }
        for (idx, memo_path) in memo_files.iter().enumerate().skip(start) {
            index_memo_file(&conn, memo_path)?;
            checkpoint.scanned = u64::try_from(idx + 1).map_err(|_error| {
                validation(
                    "rebuild_progress_overflow",
                    "rebuild memo progress does not fit the supported width",
                )
            })?;
            if (idx + 1) % batch_size == 0 {
                save_checkpoint(&checkpoint_path, &checkpoint)?;
            }
        }

        // Direct and SAF soft deletes share the same checksummed workspace record.  Index these
        // records after ordinary files so a marker remains authoritative even when a stale
        // physical `trash/{id}.md` optimization is still present.
        let record_start_u64 = checkpoint.scanned.saturating_sub(memo_file_count);
        let record_start = usize::try_from(record_start_u64).map_err(|_error| {
            validation(
                "rebuild_checkpoint_invalid",
                "rebuild checkpoint trash progress does not fit this platform",
            )
        })?;
        if record_start > trash_records.len() {
            return Err(validation(
                "rebuild_checkpoint_invalid",
                "rebuild checkpoint trash progress exceeds trash record count",
            ));
        }
        for (index, record) in trash_records.iter().enumerate().skip(record_start) {
            index_trash_record(&conn, record)?;
            let indexed_records = u64::try_from(index + 1).map_err(|_error| {
                validation(
                    "rebuild_progress_overflow",
                    "rebuild trash progress does not fit the supported width",
                )
            })?;
            checkpoint.scanned = memo_file_count
                .checked_add(indexed_records)
                .ok_or_else(|| {
                    validation(
                        "rebuild_progress_overflow",
                        "rebuild progress total exceeds the supported width",
                    )
                })?;
            if (index + 1) % batch_size == 0 {
                save_checkpoint(&checkpoint_path, &checkpoint)?;
            }
        }

        // Durable trash markers are authoritative after memo files so a stale physical
        // `trash/{id}.md` cannot outrank the checksummed record.
        apply_trash_record_state(&conn, &trash_records)?;
        // Purge tombstones are durable suppression facts the projection itself carries: the
        // reconcile gate compares `purged_memo` against `.lomo/purged/`, so every build must
        // leave the table equal to the tombstone set the scan just read.
        for memo_id in list_purged_memo_ids(workspace_root)? {
            conn.execute(
                "INSERT OR REPLACE INTO purged_memo(memo_id) VALUES(?1)",
                params![memo_id],
            )
            .map_err(|error| from_sqlite(&error))?;
        }
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
        rewritten: true,
    })
}

#[derive(Debug, Clone)]
struct CompareEvidence {
    file_count: u64,
    attachment_count: u64,
    workspace_digest: String,
    store_digest: String,
}

/// Collects the Direct workspace's predicted committed row image for reconcile or
/// post-rebuild integrity.
///
/// A memo file and a durable trash record may name one identity — the file's row attests
/// content while the record owns the lane — so the lanes merge instead of colliding.
fn collect_direct_workspace_rows(
    workspace_root: &Path,
) -> Result<ScannedRowImage, lomo_core::LomoError> {
    let memo_files = list_memo_files(workspace_root)?;
    let mut image = ScannedRowImage {
        memo_rows: Vec::with_capacity(memo_files.len()),
        ..ScannedRowImage::default()
    };
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
        // `index_memo_file` derives the lane from the same `trash/` parent convention.
        let trashed = path
            .parent()
            .and_then(|parent| parent.file_name())
            .and_then(|name| name.to_str())
            == Some("trash");
        image
            .memo_rows
            .push((memo_id, fingerprint_content(&content), trashed));
        let facts = project_content_facts(&content)?;
        image.attachment_count = image
            .attachment_count
            .checked_add(
                u64::try_from(canonical_attachment_keys(&facts.attachment_paths).len())
                    .unwrap_or(u64::MAX),
            )
            .ok_or_else(|| corruption("rebuild_compare_failed", "attachment count overflow"))?;
    }
    // Marker-backed Direct trash bodies are durable workspace facts even when their optional
    // `trash/{id}.md` optimization has been removed. A record for a file-covered identity only
    // claims the lane — the file's row (and its attachment refs) survives indexing — while a
    // record without a file attests the whole row.
    for record in list_trash_records(workspace_root)? {
        let projection = ScannedTrashProjection::from_record(&record)?;
        if let Some(row) = image
            .memo_rows
            .iter_mut()
            .find(|(memo_id, _, _)| memo_id == &record.memo_id)
        {
            row.2 = true;
        } else {
            image.memo_rows.push((
                record.memo_id.clone(),
                record.source_fingerprint.clone(),
                true,
            ));
            image.attachment_count = image
                .attachment_count
                .checked_add(
                    u64::try_from(
                        canonical_attachment_keys(&projection.memo.attachment_paths).len(),
                    )
                    .unwrap_or(u64::MAX),
                )
                .ok_or_else(|| corruption("rebuild_compare_failed", "attachment count overflow"))?;
        }
        image.trash_attestations.push((
            record.memo_id.clone(),
            record.trashed_at_ms,
            projection.attestation_digest()?,
        ));
    }
    Ok(image)
}

pub fn live_reconciled_result(
    connection: &Connection,
    high_water_revision: u64,
) -> Result<RebuildResult, lomo_core::LomoError> {
    let mut store_pairs = Vec::new();
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
    let file_count = u64::try_from(store_pairs.len())
        .map_err(|_error| validation("memo_count_overflow", "memo count exceeds u64"))?;
    let digest = aggregate_memo_digest(&store_pairs);
    let store_attachments: i64 = connection
        .query_row("SELECT COUNT(*) FROM attachment_ref", [], |row| row.get(0))
        .map_err(|error| from_sqlite(&error))?;
    let attachment_count = u64::try_from(store_attachments)
        .map_err(|_error| corruption("rebuild_compare_failed", "negative attachment count"))?;
    Ok(RebuildResult {
        memos_indexed: file_count,
        file_count,
        attachment_count,
        workspace_digest: digest.clone(),
        store_digest: digest,
        corrupt_lomo_isolated: 0,
        high_water_revision,
        rewritten: false,
    })
}

fn rebuild_result_from_evidence(
    evidence: CompareEvidence,
    memos_indexed: u64,
    high_water_revision: u64,
    rewritten: bool,
) -> RebuildResult {
    RebuildResult {
        memos_indexed,
        file_count: evidence.file_count,
        attachment_count: evidence.attachment_count,
        workspace_digest: evidence.workspace_digest,
        store_digest: evidence.store_digest,
        corrupt_lomo_isolated: 0,
        high_water_revision,
        rewritten,
    }
}

/// Live-projection fingerprint gate. `None` means the projection diverges and a rewrite is required.
pub fn try_reconcile_direct(
    workspace_root: &Path,
    connection: &Connection,
    high_water_revision: u64,
) -> Result<Option<RebuildResult>, lomo_core::LomoError> {
    let mut image = collect_direct_workspace_rows(workspace_root)?;
    let purged = list_purged_memo_ids(workspace_root)?;
    try_reconcile_scanned(
        connection,
        &mut image,
        high_water_revision,
        None,
        None,
        Some(&purged),
    )
}

/// Live-projection certification gate for scanned (session/SAF) memo facts.
///
/// `image` carries the predicted committed rows — `memo(memo_id, file_fingerprint,
/// is_trashed)` and `memo_trash(memo_id, trashed_at_ms, record_digest)` — so lifecycle
/// membership and record-decided content are row-deciding factors: a clean peer soft
/// delete or a rewritten record never certifies as equal. When `pins`/`history` are
/// `Some`, those workspace-derived records must also match the live projection.
/// `purged` is the durable `.lomo/purged/` tombstone set the scan decoded; the committed
/// `purged_memo` table must equal it before the gate may certify without rewriting.
/// Direct rebuild leaves `pins`/`history` `None` because pins are app-private live copies.
pub fn try_reconcile_scanned(
    connection: &Connection,
    image: &mut ScannedRowImage,
    high_water_revision: u64,
    pins: Option<&[ScannedPinProjection]>,
    history: Option<&[ScannedHistoryProjection]>,
    purged: Option<&BTreeSet<String>>,
) -> Result<Option<RebuildResult>, lomo_core::LomoError> {
    let Some(evidence) = projection_matches_rows(connection, image)? else {
        return Ok(None);
    };
    if let Some(pins) = pins
        && !projection_matches_pins(connection, pins)?
    {
        return Ok(None);
    }
    if let Some(history) = history
        && !projection_matches_history(connection, history)?
    {
        return Ok(None);
    }
    if let Some(purged) = purged
        && crate::query::purged_memo_ids(connection)? != *purged
    {
        return Ok(None);
    }
    let memos_indexed = evidence.file_count;
    Ok(Some(rebuild_result_from_evidence(
        evidence,
        memos_indexed,
        high_water_revision,
        false,
    )))
}

/// Fail-closed compare: workspace memo files vs store projection counts + digests.
fn compare_workspace_to_store(
    workspace_root: &Path,
    conn: &Connection,
) -> Result<CompareEvidence, lomo_core::LomoError> {
    let mut image = collect_direct_workspace_rows(workspace_root)?;
    projection_matches_rows(conn, &mut image)?.ok_or_else(|| {
        corruption(
            "rebuild_compare_failed",
            "workspace and store content digests diverge",
        )
    })
}

fn compare_scanned_rows_to_store(
    image: &mut ScannedRowImage,
    connection: &Connection,
) -> Result<CompareEvidence, lomo_core::LomoError> {
    projection_matches_rows(connection, image)?.ok_or_else(|| {
        corruption(
            "rebuild_compare_failed",
            "SAF page facts and rebuilt projection diverge",
        )
    })
}

/// The certification vector is the committed row image itself:
/// `memo(memo_id, file_fingerprint, is_trashed)` rows plus `memo_trash(memo_id,
/// trashed_at_ms, record_digest)` attestations. A live row and a trash claim can never
/// certify each other, and a rewritten record can never hide behind a stable claimed
/// fingerprint because `record_digest` covers every recoverable record fact.
fn projection_matches_rows(
    connection: &Connection,
    image: &mut ScannedRowImage,
) -> Result<Option<CompareEvidence>, lomo_core::LomoError> {
    image.memo_rows.sort();
    let lane_aware_pairs: Vec<(String, String)> = image
        .memo_rows
        .iter()
        .map(|(memo_id, fingerprint, trashed)| {
            (
                memo_id.clone(),
                format!("{fingerprint}:{}", u8::from(*trashed)),
            )
        })
        .collect();
    let workspace_digest = aggregate_memo_digest(&lane_aware_pairs);
    let file_count = u64::try_from(image.memo_rows.len())
        .map_err(|_error| validation("memo_count_overflow", "memo count exceeds u64"))?;
    let memo_count: i64 = connection
        .query_row("SELECT COUNT(*) FROM memo", [], |row| row.get(0))
        .map_err(|error| from_sqlite(&error))?;
    let store_count = u64::try_from(memo_count)
        .map_err(|_error| corruption("rebuild_compare_failed", "negative memo count"))?;
    if store_count != file_count {
        return Ok(None);
    }
    let mut store_rows = Vec::with_capacity(image.memo_rows.len());
    let mut statement = connection
        .prepare("SELECT memo_id,file_fingerprint,is_trashed FROM memo ORDER BY memo_id")
        .map_err(|error| from_sqlite(&error))?;
    let rows = statement
        .query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, i64>(2)? != 0,
            ))
        })
        .map_err(|error| from_sqlite(&error))?;
    for row in rows {
        store_rows.push(row.map_err(|error| from_sqlite(&error))?);
    }
    let store_digest = aggregate_memo_digest(
        &store_rows
            .iter()
            .map(|(memo_id, fingerprint, trashed)| {
                (
                    memo_id.clone(),
                    format!("{fingerprint}:{}", u8::from(*trashed)),
                )
            })
            .collect::<Vec<_>>(),
    );
    if image.memo_rows != store_rows.as_slice() {
        return Ok(None);
    }
    image.trash_attestations.sort();
    let mut committed_attestations = Vec::with_capacity(image.trash_attestations.len());
    let mut statement = connection
        .prepare("SELECT memo_id,trashed_at_ms,record_digest FROM memo_trash ORDER BY memo_id")
        .map_err(|error| from_sqlite(&error))?;
    let rows = statement
        .query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, i64>(1)?,
                row.get::<_, String>(2)?,
            ))
        })
        .map_err(|error| from_sqlite(&error))?;
    for row in rows {
        committed_attestations.push(row.map_err(|error| from_sqlite(&error))?);
    }
    if committed_attestations != image.trash_attestations {
        return Ok(None);
    }
    let store_attachments: i64 = connection
        .query_row("SELECT COUNT(*) FROM attachment_ref", [], |row| row.get(0))
        .map_err(|error| from_sqlite(&error))?;
    let store_attachment_count = u64::try_from(store_attachments)
        .map_err(|_error| corruption("rebuild_compare_failed", "negative attachment count"))?;
    if store_attachment_count != image.attachment_count {
        return Ok(None);
    }
    Ok(Some(CompareEvidence {
        file_count,
        attachment_count: store_attachment_count,
        workspace_digest,
        store_digest,
    }))
}

fn projection_matches_pins(
    connection: &Connection,
    pins: &[ScannedPinProjection],
) -> Result<bool, lomo_core::LomoError> {
    let mut expected: Vec<(String, i64)> = pins
        .iter()
        .map(|pin| (pin.memo_id.clone(), pin.pinned_at_ms))
        .collect();
    expected.sort();
    let mut statement = connection
        .prepare("SELECT memo_id,pinned_at_ms FROM memo_pin ORDER BY memo_id")
        .map_err(|error| from_sqlite(&error))?;
    let rows = statement
        .query_map([], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?))
        })
        .map_err(|error| from_sqlite(&error))?;
    let mut live = Vec::with_capacity(expected.len());
    for row in rows {
        live.push(row.map_err(|error| from_sqlite(&error))?);
    }
    if expected != live {
        return Ok(false);
    }
    let mut pinned = connection
        .prepare("SELECT memo_id FROM memo WHERE is_pinned=1 ORDER BY memo_id")
        .map_err(|error| from_sqlite(&error))?;
    let pinned_rows = pinned
        .query_map([], |row| row.get::<_, String>(0))
        .map_err(|error| from_sqlite(&error))?;
    let mut pinned_ids = Vec::with_capacity(expected.len());
    for row in pinned_rows {
        pinned_ids.push(row.map_err(|error| from_sqlite(&error))?);
    }
    let expected_ids: Vec<String> = expected.into_iter().map(|(memo_id, _)| memo_id).collect();
    Ok(expected_ids == pinned_ids)
}

fn projection_matches_history(
    connection: &Connection,
    history: &[ScannedHistoryProjection],
) -> Result<bool, lomo_core::LomoError> {
    let mut expected = Vec::with_capacity(history.len());
    for item in history {
        let revision = i64::try_from(item.revision)
            .map_err(|_error| validation("revision_overflow", "history revision exceeds i64"))?;
        expected.push((
            item.memo_id.clone(),
            item.record_id.clone(),
            revision,
            item.created_at_ms,
            item.file_fingerprint.clone(),
        ));
    }
    expected.sort();
    let mut statement = connection
        .prepare(
            "SELECT memo_id,history_record_id,revision,created_at_ms,file_fingerprint \
             FROM revision_index ORDER BY memo_id,history_record_id",
        )
        .map_err(|error| from_sqlite(&error))?;
    let rows = statement
        .query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, i64>(2)?,
                row.get::<_, i64>(3)?,
                row.get::<_, String>(4)?,
            ))
        })
        .map_err(|error| from_sqlite(&error))?;
    let mut live = Vec::with_capacity(expected.len());
    for row in rows {
        live.push(row.map_err(|error| from_sqlite(&error))?);
    }
    Ok(expected == live)
}

fn copy_saf_private_state(
    live_db: &Path,
    target: &Connection,
    pin_attested_ids: &BTreeSet<String>,
) -> Result<(), lomo_core::LomoError> {
    if !live_db.exists() {
        return Ok(());
    }
    let live = Connection::open_with_flags(live_db, OpenFlags::SQLITE_OPEN_READ_ONLY)
        .map_err(|error| from_sqlite(&error))?;
    // Pin is still app-private projection state. Trash is deliberately not copied: durable
    // workspace trash records are the sole rebuild authority, so a deleted/absent marker restores
    // the active memo instead of preserving stale SQLite state.
    for table in ["memo_pin"] {
        let mut statement = live
            .prepare(&format!("SELECT memo_id,pinned_at_ms FROM {table}"))
            .map_err(|error| from_sqlite(&error))?;
        let rows = statement
            .query_map([], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?))
            })
            .map_err(|error| from_sqlite(&error))?;
        for row in rows {
            let (memo_id, timestamp) = row.map_err(|error| from_sqlite(&error))?;
            // Durable state owns the pin verdict for every identity it names: a
            // cached pin for an attested identity is stale evidence — an unpinned
            // tip must not be resurrected by it, and a pinned tip already landed
            // as a scanned fact.
            if pin_attested_ids.contains(&memo_id) {
                continue;
            }
            // A pin only projects where its memo row exists in the rebuild — the new
            // projection may legitimately drop an identity the previous projection
            // still carried, and an orphaned `memo_pin` insert would violate the
            // foreign key. `OR IGNORE` keeps the scanned durable pin facts (already
            // appended) authoritative over anything the old projection still held.
            target
                .execute(
                    "INSERT OR IGNORE INTO memo_pin(memo_id,pinned_at_ms) \
                     SELECT ?1,?2 WHERE EXISTS(SELECT 1 FROM memo WHERE memo_id=?1)",
                    params![memo_id, timestamp],
                )
                .map_err(|error| from_sqlite(&error))?;
            target
                .execute(
                    "UPDATE memo SET is_pinned=1 WHERE memo_id=?1",
                    params![memo_id],
                )
                .map_err(|error| from_sqlite(&error))?;
        }
    }
    if table_exists(&live, "saf_mutation_operation")? {
        copy_saf_operation_rows(&live, target)?;
    }
    Ok(())
}

fn copy_saf_operation_rows(
    live: &Connection,
    target: &Connection,
) -> Result<(), lomo_core::LomoError> {
    if table_column_exists(live, "saf_mutation_operation", "reminder_ids_json")? {
        let mut statement = live
            .prepare(
                "SELECT operation_id,mutation_digest,memo_id,core_revision,event_sequence,content_revision,file_fingerprint,reminder_ids_json \
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
                    row.get::<_, Option<String>>(7)?,
                ))
            })
            .map_err(|error| from_sqlite(&error))?;
        for row in rows {
            let values = row.map_err(|error| from_sqlite(&error))?;
            target
                .execute(
                    "INSERT INTO saf_mutation_operation(operation_id,mutation_digest,memo_id,core_revision,event_sequence,content_revision,file_fingerprint,reminder_ids_json) VALUES(?1,?2,?3,?4,?5,?6,?7,?8)",
                    params![values.0, values.1, values.2, values.3, values.4, values.5, values.6, values.7],
                )
                .map_err(|error| from_sqlite(&error))?;
        }
    } else {
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

fn table_column_exists(
    connection: &Connection,
    table: &str,
    column: &str,
) -> Result<bool, lomo_core::LomoError> {
    let mut statement = connection
        .prepare(&format!("PRAGMA table_info({table})"))
        .map_err(|error| from_sqlite(&error))?;
    let rows = statement
        .query_map([], |row| row.get::<_, String>(1))
        .map_err(|error| from_sqlite(&error))?;
    for row in rows {
        if row.map_err(|error| from_sqlite(&error))? == column {
            return Ok(true);
        }
    }
    Ok(false)
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
    // A legacy physical trash body is considered only when no canonical workspace trash record
    // exists.  Once the record is present, the body/metadata in that record is the sole rebuild
    // authority and the file is merely an optional local optimization.
    let mut legacy_trash = Vec::new();
    collect_md_files(&workspace_root.join("trash"), &mut legacy_trash)?;
    let purged = list_purged_memo_ids(workspace_root)?;
    for path in legacy_trash {
        let memo_id = path
            .file_stem()
            .and_then(|stem| stem.to_str())
            .ok_or_else(|| {
                validation(
                    "invalid_memo_filename",
                    "trash memo file stem must be utf-8",
                )
            })?;
        // A permanent-delete tombstone retires the memo identity entirely: a stale body file
        // can never resurrect it.
        if purged.contains(memo_id) {
            continue;
        }
        let has_record = {
            let relative = trash_record_relative_path(memo_id)?;
            workspace_root.join(relative.as_str()).is_file()
        };
        if !has_record {
            out.push(path);
        }
    }
    out.sort();
    Ok(out)
}

fn list_trash_records(workspace_root: &Path) -> Result<Vec<TrashRecordV1>, lomo_core::LomoError> {
    let directory = workspace_root.join(lomo_workspace::TRASH_RECORD_DIRECTORY);
    if !directory.exists() {
        return Ok(Vec::new());
    }
    let mut records = Vec::new();
    for entry in fs::read_dir(&directory).map_err(|error| {
        storage(
            "trash_record_list_failed",
            &format!("cannot list durable trash records: {error}"),
        )
    })? {
        let entry = entry.map_err(|error| {
            storage(
                "trash_record_list_failed",
                &format!("cannot read durable trash record entry: {error}"),
            )
        })?;
        let path = entry.path();
        if path.extension().and_then(|extension| extension.to_str()) != Some("rec") {
            continue;
        }
        let bytes = fs::read(&path).map_err(|error| {
            storage(
                "trash_record_read_failed",
                &format!(
                    "cannot read durable trash record {}: {error}",
                    path.display()
                ),
            )
        })?;
        let record = decode_trash_record(&bytes)?;
        let expected = trash_record_relative_path(&record.memo_id)?;
        if path != workspace_root.join(expected.as_str()) {
            return Err(corruption(
                "trash_record_path_mismatch",
                "durable trash record is not stored at its canonical hashed path",
            ));
        }
        records.push(record);
    }
    // Permanent-delete tombstones are the suppression authority: a purged memo id must never
    // re-project from a stray, rewritten, or peer-redelivered trash record.
    let purged = list_purged_memo_ids(workspace_root)?;
    records.retain(|record| !purged.contains(&record.memo_id));
    records.sort_by(|left, right| left.memo_id.cmp(&right.memo_id));
    Ok(records)
}

fn index_trash_record(
    conn: &Connection,
    record: &TrashRecordV1,
) -> Result<(), lomo_core::LomoError> {
    // `from_record` is the single record→projection construction: attachment evidence
    // comes from the recoverable body, never from the declared `attachments` payload.
    let projection = ScannedTrashProjection::from_record(record)?;
    index_scanned_memo_with_lifecycle(conn, &projection.memo, true)?;
    conn.execute(
        "INSERT OR REPLACE INTO memo_trash(memo_id,trashed_at_ms,record_digest) VALUES(?1,?2,?3)",
        params![
            record.memo_id,
            record.trashed_at_ms,
            projection.attestation_digest()?,
        ],
    )
    .map_err(|error| from_sqlite(&error))?;
    conn.execute(
        "UPDATE memo SET is_trashed=1 WHERE memo_id=?1",
        params![record.memo_id],
    )
    .map_err(|error| from_sqlite(&error))?;
    Ok(())
}

fn apply_trash_record_state(
    conn: &Connection,
    records: &[TrashRecordV1],
) -> Result<(), lomo_core::LomoError> {
    for record in records {
        let digest = ScannedTrashProjection::from_record(record)?.attestation_digest()?;
        conn.execute(
            "INSERT OR REPLACE INTO memo_trash(memo_id,trashed_at_ms,record_digest) VALUES(?1,?2,?3)",
            params![record.memo_id, record.trashed_at_ms, digest],
        )
        .map_err(|error| from_sqlite(&error))?;
        conn.execute(
            "UPDATE memo SET is_trashed=1 WHERE memo_id=?1",
            params![record.memo_id],
        )
        .map_err(|error| from_sqlite(&error))?;
    }
    Ok(())
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
    // The workspace parser is the sole reminder authority.  Direct-mode files are plain
    // Markdown bodies in the same grammar as SAF documents; carry its typed references into the
    // projection instead of manufacturing an empty `reminders_json` value.
    let reminders = project_reminder_references(&content, &memo_id)?;
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
            reminders,
        },
    )
}

fn index_scanned_memo(
    conn: &Connection,
    memo: &ScannedMemoProjection,
) -> Result<(), lomo_core::LomoError> {
    index_scanned_memo_with_lifecycle(conn, memo, memo.source_path.starts_with("trash/"))
}

fn index_scanned_memo_with_lifecycle(
    conn: &Connection,
    memo: &ScannedMemoProjection,
    is_trashed: bool,
) -> Result<(), lomo_core::LomoError> {
    validate_scanned_projection(memo)?;
    let search_content = index_tokens(&memo.body);
    let preview = body_preview(&memo.body);
    let word_count = count_words(&memo.body);
    let char_count = count_characters(&memo.body);
    let has_todo = i64::from(memo.has_todo);
    let has_url = i64::from(memo.has_url);
    let has_attachment = i64::from(!memo.attachment_paths.is_empty());
    let is_trashed = i64::from(is_trashed);
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
         created_at_ms, updated_at_ms, body_preview, body, search_content, word_count, char_count, content_revision, reminders_json, is_pinned, is_trashed) \
         VALUES(?1,?2,?3,?4,?5,?6,?7,?7,?8,?9,?10,?11,?12,1,?13,0,?14)",
        params![
            &memo.memo_id,
            &memo.source_path,
            &memo.file_fingerprint,
            has_todo,
            has_url,
            has_attachment,
            memo.chronology_epoch_ms,
            preview,
            &memo.body,
            search_content,
            word_count,
            char_count,
            reminders_json,
            is_trashed,
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
    }
    // Destinations that cannot name a workspace file (external objects, root escapes) carry no
    // attachment key and get no `attachment_ref` row.
    for key in canonical_attachment_keys(&memo.attachment_paths) {
        conn.execute(
            "INSERT OR IGNORE INTO attachment_ref(memo_id, relative_path) VALUES(?1, ?2)",
            params![memo.memo_id, &key],
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
