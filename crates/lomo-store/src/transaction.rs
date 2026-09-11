//! Store Direct memo commands fail closed; `WorkspaceSession` owns document writes.

use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use rusqlite::Connection;

use lomo_core::{CoreRevision, EventSequence, InvalidationScope, OperationId};

use crate::content_facts::project_content_facts;
use crate::error::{busy, conflict, storage, validation};
use crate::lomo_format::{
    LomoLayoutVersion, LomoPaths, MemoCommandKind, OperationIntent, OperationStatus, read_record,
};

/// Crash injection points for recovery matrix tests (production uses `None`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CrashPoint {
    AfterIntent,
    AfterHistory,
    /// After staged media promote succeeds, before Markdown body commit (P4-04).
    AfterPromoteBeforeFiles,
    AfterFiles,
    AfterProjection,
    AfterCommittedMark,
}

/// Fails closed when layout head is V2 while this crate still only writes v1-shaped records.
///
/// First principles: layout head is the sole authority for history/state tree shape. Store memo
/// writers still produce flat v1 bodies (`memoId-rN` history, single-file state). Writing those into
/// a V2 tree would corrupt the dual-layout contract. Production activation migration must stay off
/// the hot path until store v2 writers exist.
///
/// # Errors
///
/// - `layout_v2_requires_v2_writers` when layout is V2.
pub fn refuse_v1_writers_on_layout_v2(paths: &LomoPaths) -> Result<(), lomo_core::LomoError> {
    if paths.layout == LomoLayoutVersion::V2 {
        return Err(validation(
            "layout_v2_requires_v2_writers",
            "layout head is V2 but store memo writers still emit v1-shaped history/state; refuse mutate until store v2 writers cut over",
        ));
    }
    Ok(())
}

/// Command accepted by the Store Direct fail-closed boundary.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemoCommand {
    pub operation_id: OperationId,
    pub kind: MemoCommandKind,
    pub memo_id: String,
    pub expected_revision: u64,
    pub expected_fingerprint: Option<String>,
    pub content: Option<String>,
    pub tags: Vec<String>,
    pub pin: Option<bool>,
    /// Staged media to promote under this operation-id before body/`attachment_ref` (P4-04).
    pub pending_promotes: Vec<lomo_media::PromotePlan>,
}

/// Selects the staged media facts that the Rust Markdown owner says belong to one memo body.
///
/// The host may have more than one staged file waiting (for example, an editor can stage an
/// image, then abandon that draft and start another one).  Passing a host-selected subset across
/// the FFI boundary makes the promote set a second, independently evolving Markdown parser.  The
/// command instead carries the complete candidate set and this function derives the set from the
/// same render projection used for `attachment_ref` and `has_attachment`.
///
/// Matching is exact on the owner-suggested/final relative path first and falls back to a basename
/// only when that basename identifies one candidate.  Ambiguous basename matches are rejected;
/// silently choosing one would turn a media reference into data loss.  External destinations are
/// never candidates for local promotion.
///
/// # Errors
///
/// Returns validation when a candidate path is malformed or one destination resolves to multiple
/// different staged digests, and content-projection errors from the Rust Markdown owner.
pub fn select_pending_promotes(
    content: &str,
    candidates: &[lomo_media::PromotePlan],
) -> Result<Vec<lomo_media::PromotePlan>, lomo_core::LomoError> {
    if candidates.is_empty() {
        return Ok(Vec::new());
    }

    let mut exact: BTreeMap<String, Vec<usize>> = BTreeMap::new();
    let mut basenames: BTreeMap<String, Vec<usize>> = BTreeMap::new();
    for (index, candidate) in candidates.iter().enumerate() {
        let suggested =
            normalize_attachment_target(&candidate.staged.suggested_final_relative_path)
                .ok_or_else(|| {
                    validation(
                        "invalid_pending_promote_path",
                        "staged media suggested destination is empty or invalid",
                    )
                })?;
        let final_path = normalize_attachment_target(candidate.final_relative_path.as_str())
            .ok_or_else(|| {
                validation(
                    "invalid_pending_promote_path",
                    "staged media final destination is empty or invalid",
                )
            })?;
        for key in [suggested, final_path] {
            exact.entry(key).or_default().push(index);
        }
        // Basename matching is intentionally a separate map: an exact path always wins over a
        // potentially ambiguous basename.
        for key in [
            basename_of(&candidate.staged.suggested_final_relative_path),
            basename_of(candidate.final_relative_path.as_str()),
        ]
        .into_iter()
        .flatten()
        {
            basenames.entry(key).or_default().push(index);
        }
    }

    let facts = project_content_facts(content)?;
    let mut selected = BTreeSet::new();
    for destination in facts.attachment_paths {
        let Some(normalized) = normalize_attachment_target(&destination) else {
            continue;
        };
        let mut matches = exact.get(&normalized).cloned().unwrap_or_else(Vec::new);
        if matches.is_empty()
            && let Some(basename) = basename_of(&normalized)
        {
            matches = basenames.get(&basename).cloned().unwrap_or_else(Vec::new);
        }
        matches.sort_unstable();
        matches.dedup();
        if matches.len() > 1 {
            let first = matches.first().copied().ok_or_else(|| {
                validation(
                    "pending_promote_match_missing",
                    "media destination match set became empty while validating",
                )
            })?;
            let first_candidate = candidates.get(first).ok_or_else(|| {
                validation(
                    "pending_promote_candidate_missing",
                    "media destination match points outside the candidate set",
                )
            })?;
            let same_digest = matches
                .iter()
                .map(|index| candidates.get(*index))
                .collect::<Option<Vec<_>>>()
                .is_some_and(|matched| {
                    matched
                        .iter()
                        .all(|candidate| candidate.staged.digest == first_candidate.staged.digest)
                });
            if !same_digest {
                return Err(validation(
                    "ambiguous_pending_promote_destination",
                    "one Markdown attachment destination matches multiple staged media digests",
                ));
            }
        }
        selected.extend(matches);
    }

    selected
        .into_iter()
        .map(|index| {
            candidates.get(index).cloned().ok_or_else(|| {
                validation(
                    "pending_promote_candidate_missing",
                    "selected media candidate disappeared before promotion",
                )
            })
        })
        .collect::<Result<Vec<_>, _>>()
}

fn normalize_attachment_target(raw: &str) -> Option<String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() || is_external_attachment_target(trimmed) {
        return None;
    }
    let mut normalized = trimmed.replace('\\', "/");
    while let Some(rest) = normalized.strip_prefix("./").map(str::to_owned) {
        normalized = rest;
    }
    if normalized.is_empty() || normalized.contains('\0') {
        None
    } else {
        Some(normalized)
    }
}

fn basename_of(raw: &str) -> Option<String> {
    let normalized = raw.trim().replace('\\', "/");
    normalized
        .rsplit('/')
        .next()
        .filter(|value| !value.is_empty())
        .map(str::to_owned)
}

fn is_external_attachment_target(raw: &str) -> bool {
    if raw.starts_with('#') || raw.starts_with("//") {
        return true;
    }
    let bytes = raw.as_bytes();
    let Some(first) = bytes.first() else {
        return false;
    };
    if !first.is_ascii_alphabetic() {
        return false;
    }
    let mut index = 1;
    while index < bytes.len()
        && (bytes.get(index).is_some_and(u8::is_ascii_alphanumeric)
            || matches!(bytes.get(index), Some(b'+' | b'-' | b'.')))
    {
        index += 1;
    }
    bytes.get(index) == Some(&b':')
}

/// Publication facts returned for a committed Direct operation replay.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemoCommitResult {
    pub operation_id: String,
    pub memo_id: String,
    pub core_revision: CoreRevision,
    pub event_sequence: EventSequence,
    pub content_revision: u64,
    pub file_fingerprint: String,
    pub scopes: Vec<InvalidationScope>,
    pub idempotent_replay: bool,
}

/// CAS facts supplied to a direct-store batch permanent delete.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PermanentDeleteTarget {
    pub memo_id: String,
    pub source_path: String,
    pub expected_revision: u64,
    pub expected_fingerprint: String,
}

/// One memo's reminder identities captured before its durable row is removed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PermanentDeleteMemoResult {
    pub memo_id: String,
    pub reminder_ids: Vec<String>,
}

/// One atomic direct-store batch publication.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PermanentDeleteManyResult {
    pub operation_id: String,
    pub deleted: Vec<PermanentDeleteMemoResult>,
    pub core_revision: CoreRevision,
    pub event_sequence: EventSequence,
    pub scopes: Vec<InvalidationScope>,
    pub idempotent_replay: bool,
}

/// Store write mode gate.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WriteGate {
    Ready,
    RebuildingReadOnly,
}

/// Fails closed for new Store Direct document commands; replays committed operation ids.
///
/// # Errors
///
/// - `store_rebuilding` when the write gate is read-only.
/// - `session_owns_document_writes` for new or incomplete Direct mutations.
/// - `operation_kind_conflict` when the operation id belongs to a batch delete.
pub fn apply_memo_command(
    workspace_root: &Path,
    connection: &Connection,
    gate: WriteGate,
    command: &MemoCommand,
    high_water_revision: &mut u64,
    event_sequence: &mut u64,
    crash_point: Option<CrashPoint>,
) -> Result<MemoCommitResult, lomo_core::LomoError> {
    apply_memo_command_inner(
        workspace_root,
        connection,
        gate,
        command,
        None,
        *high_water_revision,
        *event_sequence,
        crash_point,
    )
}

/// Same fail-closed Direct boundary as [`apply_memo_command`]; the timestamp is unused.
///
/// # Errors
///
/// See [`apply_memo_command`].
#[expect(
    clippy::too_many_arguments,
    reason = "the public transaction boundary keeps the durable connection and revision counters explicit"
)]
pub fn apply_memo_command_with_created_at(
    workspace_root: &Path,
    connection: &Connection,
    gate: WriteGate,
    command: &MemoCommand,
    created_at_ms: Option<i64>,
    high_water_revision: &mut u64,
    event_sequence: &mut u64,
    crash_point: Option<CrashPoint>,
) -> Result<MemoCommitResult, lomo_core::LomoError> {
    apply_memo_command_inner(
        workspace_root,
        connection,
        gate,
        command,
        created_at_ms,
        *high_water_revision,
        *event_sequence,
        crash_point,
    )
}

/// Fails closed: batch permanent delete belongs to `WorkspaceSession`, not Store Direct.
///
/// # Errors
///
/// Always `session_owns_document_writes`.
pub fn permanent_delete_many(
    _workspace_root: &Path,
    _connection: &Connection,
    _gate: WriteGate,
    _operation_id: &OperationId,
    _targets: &[PermanentDeleteTarget],
    _high_water_revision: &mut u64,
    _event_sequence: &mut u64,
) -> Result<PermanentDeleteManyResult, lomo_core::LomoError> {
    Err(validation(
        "session_owns_document_writes",
        "Store Direct must not batch-delete documents; WorkspaceSession owns permanent delete",
    ))
}

#[expect(
    clippy::too_many_arguments,
    reason = "transaction core carries durable store counters and an optional source timestamp"
)]
fn apply_memo_command_inner(
    workspace_root: &Path,
    _connection: &Connection,
    gate: WriteGate,
    command: &MemoCommand,
    _created_at_ms: Option<i64>,
    high_water_revision: u64,
    event_sequence: u64,
    _crash_point: Option<CrashPoint>,
) -> Result<MemoCommitResult, lomo_core::LomoError> {
    if gate == WriteGate::RebuildingReadOnly {
        return Err(busy(
            "store_rebuilding",
            "mutations are rejected while rebuild is active",
        ));
    }

    let paths = LomoPaths::for_workspace(workspace_root);
    // Dual-layout fence: store writers still emit v1-shaped flat history/state records.
    // Layout V2 is authoritative only after migration; until store v2 writers cut over, refuse
    // mutate so v1 bodies never land under history/v2 or state/v2 paths.
    refuse_v1_writers_on_layout_v2(&paths)?;
    paths.ensure_layout()?;

    let op_path = operation_path(&paths, command.operation_id.as_str());

    // Idempotent replay of a fully committed operation.
    if op_path.exists() {
        let existing = read_record(&op_path)?;
        let intent = decode_operation_intent(&existing.payload.body_json)?;
        if !intent.batch_targets.is_empty() {
            return Err(conflict(
                "operation_kind_conflict",
                "operation id belongs to a permanent delete batch",
            ));
        }
        if intent.status == OperationStatus::Committed {
            return Ok(MemoCommitResult {
                operation_id: intent.operation_id,
                memo_id: intent.memo_id,
                core_revision: CoreRevision::from_raw(high_water_revision),
                event_sequence: EventSequence::from_raw(event_sequence),
                content_revision: intent.content_revision_after.unwrap_or(0),
                file_fingerprint: option_string(intent.file_fingerprint_after),
                scopes: Vec::new(),
                idempotent_replay: true,
            });
        }
        return Err(validation(
            "session_owns_document_writes",
            "Store Direct must not resume document mutations; WorkspaceSession owns writes",
        ));
    }

    let Err(error) = refuse_session_owned_document_command(command.kind) else {
        unreachable!("Store Direct document commands are always refused");
    };
    Err(error)
}

/// Received memo document writes belong to the application session, not this store writer.
///
/// # Errors
///
/// Always returns `session_owns_document_writes`. LAN commits through the application session.
#[expect(
    clippy::too_many_arguments,
    reason = "store transaction boundary keeps the former LAN signature while refusing writes"
)]
pub fn create_received_memo(
    _workspace_root: &Path,
    _connection: &Connection,
    _gate: WriteGate,
    _operation_id: OperationId,
    _expected_workspace_generation: &str,
    _timestamp_ms: i64,
    _content: String,
    _pending_promotes: Vec<lomo_media::PromotePlan>,
    _high_water_revision: &mut u64,
    _event_sequence: &mut u64,
) -> Result<MemoCommitResult, lomo_core::LomoError> {
    Err(validation(
        "session_owns_document_writes",
        "LAN received memos must commit through WorkspaceSession::create_memo",
    ))
}

fn refuse_session_owned_document_command(
    _kind: MemoCommandKind,
) -> Result<(), lomo_core::LomoError> {
    Err(validation(
        "session_owns_document_writes",
        "Store Direct must not mutate documents or pin/trash sidecars; WorkspaceSession owns writes",
    ))
}

pub fn memo_command_scopes(kind: MemoCommandKind) -> Vec<InvalidationScope> {
    match kind {
        MemoCommandKind::Create | MemoCommandKind::Update | MemoCommandKind::HistoryRestore => {
            vec![
                InvalidationScope::MemoList,
                InvalidationScope::Search,
                InvalidationScope::Tags,
                InvalidationScope::Stats,
            ]
        }
        MemoCommandKind::Delete | MemoCommandKind::PermanentDelete | MemoCommandKind::Restore => {
            vec![
                InvalidationScope::MemoList,
                InvalidationScope::Trash,
                InvalidationScope::Search,
                InvalidationScope::Stats,
            ]
        }
        MemoCommandKind::Pin | MemoCommandKind::Unpin => {
            vec![
                InvalidationScope::MemoList,
                InvalidationScope::Pin,
                InvalidationScope::Stats,
            ]
        }
    }
}

fn decode_operation_intent(body_json: &str) -> Result<OperationIntent, lomo_core::LomoError> {
    serde_json::from_str(body_json).map_err(|error| {
        validation(
            "operation_intent_decode_failed",
            &format!("cannot decode operation intent: {error}"),
        )
    })
}

fn operation_path(paths: &LomoPaths, operation_id: &str) -> PathBuf {
    paths.operations.join(format!("{operation_id}.rec"))
}

#[expect(
    clippy::manual_unwrap_or_default,
    clippy::option_if_let_else,
    reason = "unwrap_or_default is disallowed by clippy.toml; explicit empty string is intentional"
)]
fn option_string(value: Option<String>) -> String {
    match value {
        Some(inner) => inner,
        None => String::new(),
    }
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}

/// Step 9: remove committed operation logs older than `retain_ms` (explicit deferred cleanup).
///
/// # Errors
///
/// Storage errors when directory listing or deletion fails. Never deletes non-committed intents.
pub fn cleanup_expired_operations(
    workspace_root: &Path,
    retain_ms: u64,
) -> Result<usize, lomo_core::LomoError> {
    let paths = LomoPaths::for_workspace(workspace_root);
    if !paths.operations.exists() {
        return Ok(0);
    }
    let cutoff = now_ms().saturating_sub(i64::try_from(retain_ms).unwrap_or(i64::MAX));
    let mut removed = 0usize;
    for entry in std::fs::read_dir(&paths.operations).map_err(|err| {
        storage(
            "operations_list_failed",
            &format!("cannot list operations: {err}"),
        )
    })? {
        let entry = entry.map_err(|err| {
            storage(
                "operations_list_failed",
                &format!("cannot read operations entry: {err}"),
            )
        })?;
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("rec") {
            continue;
        }
        let meta = entry.metadata().map_err(|err| {
            storage(
                "operations_meta_failed",
                &format!("cannot stat operation: {err}"),
            )
        })?;
        let modified = meta.modified().map_or(0, |time| {
            time.duration_since(UNIX_EPOCH).map_or(0, |duration| {
                i64::try_from(duration.as_millis()).unwrap_or(0)
            })
        });
        if modified > cutoff {
            continue;
        }
        if let Ok(record) = read_record(&path)
            && let Ok(intent) = serde_json::from_str::<OperationIntent>(&record.payload.body_json)
            && intent.status == OperationStatus::Committed
        {
            std::fs::remove_file(&path).map_err(|err| {
                storage(
                    "operations_cleanup_failed",
                    &format!("cannot remove committed op: {err}"),
                )
            })?;
            removed += 1;
        }
        // Corrupt records are not auto-deleted; isolation is a separate path.
    }
    Ok(removed)
}
