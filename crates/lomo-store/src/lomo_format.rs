//! Transaction-only durable bodies and command kinds for `lomo-store`.
//!
//! Generic record codec and layout roots live in `lomo-workspace` (P5-01). This module keeps
//! operation intent / v1 state-history body types used by the memo transaction machine.

use serde::{Deserialize, Serialize};

// Re-export codec surface so existing `lomo_store::…` call sites keep compiling.
pub use lomo_workspace::{
    HistorySnapshotV1 as HistoryBody, LOMO_CODEC_SCHEMA, LOMO_MAGIC, LomoLayoutVersion, LomoPaths,
    LomoPayload, LomoRecord, LomoRecordKind, decode_record, encode_record, isolate_corrupt_record,
    read_record, write_record_atomic,
};

/// Operation intent body (step 2 durable journal).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct OperationIntent {
    pub operation_id: String,
    pub command: MemoCommandKind,
    pub memo_id: String,
    pub expected_revision: u64,
    pub expected_fingerprint: Option<String>,
    pub content: Option<String>,
    pub tags: Vec<String>,
    pub pin: Option<bool>,
    /// Original creation timestamp for imported/received creates. `None` means the ordinary local
    /// create contract uses the transaction commit clock.
    #[serde(default)]
    pub created_at_ms: Option<i64>,
    pub status: OperationStatus,
    pub content_revision_after: Option<u64>,
    pub file_fingerprint_after: Option<String>,
    /// Durable publish plan: once set, recovery re-applies these exact counters (no double-bump).
    #[serde(default)]
    pub core_revision_after: Option<u64>,
    /// Durable publish plan for the event sequence counter.
    #[serde(default)]
    pub event_sequence_after: Option<u64>,
    /// Staged media promote plans for this operation (P4-04). Empty when no media promote.
    /// Serialized so crash recovery re-runs promote under the same operation-id before body/refs.
    #[serde(default)]
    pub pending_promotes: Vec<lomo_media::PromotePlan>,
    /// Targets for a batched permanent-delete operation.  Ordinary memo operations leave this
    /// empty; keeping the batch facts in the same checksummed operation record gives the batch a
    /// durable replay identity without inventing a second journal format.
    #[serde(default)]
    pub batch_targets: Vec<BatchDeleteTarget>,
    /// Direct-trash files that have already been removed by a batch operation.  The per-target
    /// progress is persisted so recovery can distinguish a crash after a file delete from an
    /// externally missing trash file and fail closed in the latter case.
    #[serde(default)]
    pub batch_deleted_files: Vec<String>,
    /// Reminder identities captured from the projection before the rows are deleted.  They are
    /// returned to the platform scheduler after commit and remain available on idempotent replay.
    #[serde(default)]
    pub batch_reminder_sets: Vec<BatchDeleteReminderSet>,
    /// The single durable timestamp for a Direct soft-delete transition. It is captured in the
    /// operation intent before any filesystem move so retries/recovery cannot mint a different
    /// trash fact or make the projection and workspace record disagree.
    #[serde(default)]
    pub trashed_at_ms: Option<i64>,
}

/// CAS facts required to permanently delete one memo as part of a batch.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BatchDeleteTarget {
    pub memo_id: String,
    pub source_path: String,
    pub expected_revision: u64,
    pub expected_fingerprint: String,
}

/// Reminder identities observed before a batch permanently deletes a memo.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BatchDeleteReminderSet {
    pub memo_id: String,
    pub reminder_ids: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MemoCommandKind {
    Create,
    Update,
    Delete,
    PermanentDelete,
    Restore,
    Pin,
    Unpin,
    HistoryRestore,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum OperationStatus {
    IntentAppended,
    HistoryAppended,
    FilesCommitted,
    ProjectionCommitted,
    Committed,
}

/// Pin/trash/tag durable state body (`SQLite` projections rehydrate from this).
///
/// v1 mutable single-file form. v2 state revisions live in `lomo-workspace::StateRevisionV2`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StateBody {
    pub memo_id: String,
    pub pinned: bool,
    pub trashed: bool,
    pub pinned_at_ms: Option<i64>,
    pub trashed_at_ms: Option<i64>,
    /// Durable tag names for the memo (rebuildable into `tag` / `memo_tag`).
    #[serde(default)]
    pub tags: Vec<String>,
}
