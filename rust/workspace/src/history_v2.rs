//! History/state v2 models: content-addressed revisions and retention (stage-5 P5-01).
//!
//! - `RevisionId = sha256(memo_id + sorted_parent_ids + content_digest + canonical_metadata)`
//! - generation = `1 + max(parent.generation)` (roots = 1)
//! - retention: 20 reachable revisions by generation desc, `RevisionId` tie-break
//! - pruned revisions receive permanent tombstones
//! - active conflict/session pins are respected via an explicit pin set (hook)

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::hash::BuildHasher;
use std::path::Path;

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::limits::{corruption, storage, validation};
use crate::lomo_record::{
    LomoLayoutVersion, LomoPaths, LomoPayload, LomoRecordKind, hex_encode, read_record,
    write_record_atomic,
};

/// Product policy: keep the newest 20 reachable revisions per memo.
pub const HISTORY_RETENTION_REVISIONS: usize = 20;

/// Content-addressed history revision id (64 lowercase hex chars).
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
pub struct RevisionId(String);

impl RevisionId {
    /// Parses a 64-char lowercase hex revision id.
    ///
    /// # Errors
    ///
    /// Validation when length/charset is wrong.
    pub fn parse(raw: &str) -> Result<Self, lomo_core::LomoError> {
        if raw.len() != 64 || !raw.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(validation(
                "invalid_revision_id",
                "RevisionId must be 64 lowercase hex characters",
            ));
        }
        Ok(Self(raw.to_ascii_lowercase()))
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }

    /// Computes `RevisionId = sha256(memo_id + sorted_parent_ids + content_digest + canonical_metadata)`.
    #[must_use]
    pub fn compute(
        memo_id: &str,
        parent_ids: &[Self],
        content_digest: &str,
        canonical_metadata: &str,
    ) -> Self {
        let mut parents: Vec<&str> = parent_ids.iter().map(Self::as_str).collect();
        parents.sort_unstable();
        parents.dedup();
        let mut hasher = Sha256::new();
        hasher.update(memo_id.as_bytes());
        hasher.update([0]);
        for parent in parents {
            hasher.update(parent.as_bytes());
            hasher.update([0]);
        }
        hasher.update(content_digest.as_bytes());
        hasher.update([0]);
        hasher.update(canonical_metadata.as_bytes());
        Self(hex_encode(&hasher.finalize()[..]))
    }
}

/// Immutable history revision object (v2).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HistoryRevisionV2 {
    pub revision_id: RevisionId,
    pub memo_id: String,
    /// Empty for root; one parent for ordinary; two for merge.
    pub parent_ids: Vec<RevisionId>,
    /// `1 + max(parent.generation)`; roots are 1.
    pub generation: u64,
    pub content_digest: String,
    /// Full Markdown snapshot (durable; large bodies may later move to artifact refs).
    pub content: String,
    /// Canonical metadata string that participates in `RevisionId` (stable form).
    pub canonical_metadata: String,
    /// Display-only timestamp; never orders retention.
    pub created_at_ms: i64,
}

impl HistoryRevisionV2 {
    /// Builds a revision, computing id and generation from parents.
    ///
    /// # Errors
    ///
    /// Validation when parent count exceeds 2 or generation overflows.
    pub fn create(
        memo_id: &str,
        parents: &[Self],
        content: String,
        content_digest: &str,
        canonical_metadata: &str,
        created_at_ms: i64,
    ) -> Result<Self, lomo_core::LomoError> {
        if parents.len() > 2 {
            return Err(validation(
                "history_too_many_parents",
                "history revision may have at most two parents",
            ));
        }
        for parent in parents {
            if parent.memo_id != memo_id {
                return Err(validation(
                    "history_parent_memo_mismatch",
                    "parent revision must belong to the same memo",
                ));
            }
        }
        let parent_ids: Vec<RevisionId> = parents.iter().map(|p| p.revision_id.clone()).collect();
        let generation = if parents.is_empty() {
            1
        } else {
            let max_parent = parents.iter().map(|p| p.generation).max().unwrap_or(0);
            max_parent.checked_add(1).ok_or_else(|| {
                validation("history_generation_overflow", "history generation overflow")
            })?
        };
        let revision_id =
            RevisionId::compute(memo_id, &parent_ids, content_digest, canonical_metadata);
        Ok(Self {
            revision_id,
            memo_id: memo_id.to_owned(),
            parent_ids,
            generation,
            content_digest: content_digest.to_owned(),
            content,
            canonical_metadata: canonical_metadata.to_owned(),
            created_at_ms,
        })
    }
}

/// Inputs for building a content-addressed state revision (avoids too-many-arguments).
#[derive(Debug, Clone)]
pub struct StateRevisionCreate<'a> {
    pub memo_id: &'a str,
    pub parent: Option<&'a StateRevisionV2>,
    pub pinned: bool,
    pub trashed: bool,
    pub pinned_at_ms: Option<i64>,
    pub trashed_at_ms: Option<i64>,
    pub pin_operation_id: Option<String>,
    pub trash_operation_id: Option<String>,
    pub canonical_metadata: &'a str,
    pub created_at_ms: i64,
}

/// Immutable state revision object (v2). Pin/trash only — tags remain Markdown-owned.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StateRevisionV2 {
    pub revision_id: RevisionId,
    pub memo_id: String,
    pub parent_ids: Vec<RevisionId>,
    pub generation: u64,
    pub pinned: bool,
    pub trashed: bool,
    pub pinned_at_ms: Option<i64>,
    pub trashed_at_ms: Option<i64>,
    /// Last operation id that mutated pin (identity of change, not only bool).
    pub pin_operation_id: Option<String>,
    /// Last operation id that mutated trash.
    pub trash_operation_id: Option<String>,
    pub canonical_metadata: String,
    pub created_at_ms: i64,
}

impl StateRevisionV2 {
    /// Builds a state revision with content-addressed id.
    ///
    /// # Errors
    ///
    /// Validation when parent memo mismatches or generation overflows.
    pub fn create(input: StateRevisionCreate<'_>) -> Result<Self, lomo_core::LomoError> {
        if let Some(parent) = input.parent
            && parent.memo_id != input.memo_id
        {
            return Err(validation(
                "state_parent_memo_mismatch",
                "parent state revision must belong to the same memo",
            ));
        }
        let parent_ids: Vec<RevisionId> = input
            .parent
            .map_or_else(Vec::new, |parent| vec![parent.revision_id.clone()]);
        let generation = input.parent.map_or(Ok(1), |parent| {
            parent
                .generation
                .checked_add(1)
                .ok_or_else(|| validation("state_generation_overflow", "state generation overflow"))
        })?;
        // Content digest for state is the stable field tuple.
        let content_digest = {
            let mut hasher = Sha256::new();
            hasher.update([u8::from(input.pinned)]);
            hasher.update([u8::from(input.trashed)]);
            hasher.update(input.pinned_at_ms.unwrap_or(0).to_le_bytes());
            hasher.update(input.trashed_at_ms.unwrap_or(0).to_le_bytes());
            hasher.update(input.pin_operation_id.as_deref().unwrap_or("").as_bytes());
            hasher.update([0]);
            hasher.update(input.trash_operation_id.as_deref().unwrap_or("").as_bytes());
            hex_encode(&hasher.finalize()[..])
        };
        let revision_id = RevisionId::compute(
            input.memo_id,
            &parent_ids,
            &content_digest,
            input.canonical_metadata,
        );
        Ok(Self {
            revision_id,
            memo_id: input.memo_id.to_owned(),
            parent_ids,
            generation,
            pinned: input.pinned,
            trashed: input.trashed,
            pinned_at_ms: input.pinned_at_ms,
            trashed_at_ms: input.trashed_at_ms,
            pin_operation_id: input.pin_operation_id,
            trash_operation_id: input.trash_operation_id,
            canonical_metadata: input.canonical_metadata.to_owned(),
            created_at_ms: input.created_at_ms,
        })
    }
}

/// Permanent prune tombstone for a history revision.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HistoryTombstone {
    pub memo_id: String,
    pub revision_id: RevisionId,
    pub pruned_at_ms: i64,
}

/// Per-memo head pointer (points at a history revision id).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HistoryHead {
    pub memo_id: String,
    pub head_revision_id: RevisionId,
}

/// Per-memo state head pointer.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StateHead {
    pub memo_id: String,
    pub head_revision_id: RevisionId,
}

/// Computes the retention keep-set for a memo head.
///
/// From `head`, walk parents; order by generation desc then `RevisionId` asc; keep at most
/// `retention` entries. Revisions in `pins` are always kept (active conflict/session).
#[must_use]
pub fn retention_keep_set<S: BuildHasher, P: BuildHasher>(
    head: &RevisionId,
    by_id: &HashMap<RevisionId, HistoryRevisionV2, S>,
    pins: &HashSet<RevisionId, P>,
    retention: usize,
) -> BTreeSet<RevisionId> {
    let mut reachable: Vec<RevisionId> = Vec::new();
    let mut stack = vec![head.clone()];
    let mut seen = HashSet::new();
    while let Some(id) = stack.pop() {
        if !seen.insert(id.clone()) {
            continue;
        }
        reachable.push(id.clone());
        if let Some(rev) = by_id.get(&id) {
            for parent in &rev.parent_ids {
                stack.push(parent.clone());
            }
        }
    }
    reachable.sort_by(|a, b| {
        let ga = by_id.get(a).map_or(0, |r| r.generation);
        let gb = by_id.get(b).map_or(0, |r| r.generation);
        gb.cmp(&ga).then_with(|| a.cmp(b))
    });
    let mut keep = BTreeSet::new();
    for id in reachable.into_iter().take(retention) {
        keep.insert(id);
    }
    for pin in pins {
        if by_id.contains_key(pin) {
            keep.insert(pin.clone());
        }
    }
    keep
}

/// Given keep-set, returns revisions that must be tombstoned (reachable but not kept).
#[must_use]
pub fn revisions_to_prune<S: BuildHasher>(
    head: &RevisionId,
    by_id: &HashMap<RevisionId, HistoryRevisionV2, S>,
    keep: &BTreeSet<RevisionId>,
) -> Vec<RevisionId> {
    let mut reachable = BTreeSet::new();
    let mut stack = vec![head.clone()];
    while let Some(id) = stack.pop() {
        if !reachable.insert(id.clone()) {
            continue;
        }
        if let Some(rev) = by_id.get(&id) {
            for parent in &rev.parent_ids {
                stack.push(parent.clone());
            }
        }
    }
    reachable
        .into_iter()
        .filter(|id| !keep.contains(id))
        .collect()
}

/// Absolute path for a history revision object under v2 layout.
#[must_use]
pub fn history_revision_path(paths: &LomoPaths, revision_id: &RevisionId) -> std::path::PathBuf {
    paths
        .history
        .join("objects")
        .join(format!("{}.rec", revision_id.as_str()))
}

/// Absolute path for a history head under v2 layout.
#[must_use]
pub fn history_head_path(paths: &LomoPaths, memo_id: &str) -> std::path::PathBuf {
    // Memo ids are path-safe product ids; reject path separators at write time.
    paths.history.join("heads").join(format!("{memo_id}.rec"))
}

/// Absolute path for a history tombstone under v2 layout.
#[must_use]
pub fn history_tombstone_path(paths: &LomoPaths, revision_id: &RevisionId) -> std::path::PathBuf {
    paths
        .history
        .join("tombstones")
        .join(format!("{}.rec", revision_id.as_str()))
}

/// Absolute path for a state revision object under v2 layout.
#[must_use]
pub fn state_revision_path(paths: &LomoPaths, revision_id: &RevisionId) -> std::path::PathBuf {
    paths
        .state
        .join("objects")
        .join(format!("{}.rec", revision_id.as_str()))
}

/// Absolute path for a state head under v2 layout.
#[must_use]
pub fn state_head_path(paths: &LomoPaths, memo_id: &str) -> std::path::PathBuf {
    paths.state.join("heads").join(format!("{memo_id}.rec"))
}

/// Writes a history revision object atomically.
///
/// # Errors
///
/// Encode/storage failures; validation if layout is not v2.
pub fn write_history_revision(
    paths: &LomoPaths,
    revision: &HistoryRevisionV2,
) -> Result<(), lomo_core::LomoError> {
    require_v2(paths)?;
    let body_json = serde_json::to_string(revision).map_err(|err| {
        validation(
            "history_v2_encode_failed",
            &format!("cannot encode history revision: {err}"),
        )
    })?;
    write_record_atomic(
        &history_revision_path(paths, &revision.revision_id),
        &LomoPayload {
            kind: LomoRecordKind::History,
            record_id: revision.revision_id.as_str().to_owned(),
            body_json,
        },
    )
}

/// Writes a history head atomically.
///
/// # Errors
///
/// Encode/storage failures; validation if layout is not v2 or `memo_id` has path separators.
pub fn write_history_head(
    paths: &LomoPaths,
    head: &HistoryHead,
) -> Result<(), lomo_core::LomoError> {
    require_v2(paths)?;
    reject_unsafe_memo_id(&head.memo_id)?;
    let body_json = serde_json::to_string(head).map_err(|err| {
        validation(
            "history_head_encode_failed",
            &format!("cannot encode history head: {err}"),
        )
    })?;
    write_record_atomic(
        &history_head_path(paths, &head.memo_id),
        &LomoPayload {
            kind: LomoRecordKind::History,
            record_id: format!("head:{}", head.memo_id),
            body_json,
        },
    )
}

/// Writes a permanent history tombstone.
///
/// # Errors
///
/// Encode/storage failures.
pub fn write_history_tombstone(
    paths: &LomoPaths,
    tombstone: &HistoryTombstone,
) -> Result<(), lomo_core::LomoError> {
    require_v2(paths)?;
    let body_json = serde_json::to_string(tombstone).map_err(|err| {
        validation(
            "history_tombstone_encode_failed",
            &format!("cannot encode history tombstone: {err}"),
        )
    })?;
    write_record_atomic(
        &history_tombstone_path(paths, &tombstone.revision_id),
        &LomoPayload {
            kind: LomoRecordKind::HistoryTombstone,
            record_id: tombstone.revision_id.as_str().to_owned(),
            body_json,
        },
    )
}

/// Writes a state revision object atomically.
///
/// # Errors
///
/// Encode/storage failures.
pub fn write_state_revision(
    paths: &LomoPaths,
    revision: &StateRevisionV2,
) -> Result<(), lomo_core::LomoError> {
    require_v2(paths)?;
    let body_json = serde_json::to_string(revision).map_err(|err| {
        validation(
            "state_v2_encode_failed",
            &format!("cannot encode state revision: {err}"),
        )
    })?;
    write_record_atomic(
        &state_revision_path(paths, &revision.revision_id),
        &LomoPayload {
            kind: LomoRecordKind::State,
            record_id: revision.revision_id.as_str().to_owned(),
            body_json,
        },
    )
}

/// Writes a state head atomically.
///
/// # Errors
///
/// Encode/storage failures.
pub fn write_state_head(paths: &LomoPaths, head: &StateHead) -> Result<(), lomo_core::LomoError> {
    require_v2(paths)?;
    reject_unsafe_memo_id(&head.memo_id)?;
    let body_json = serde_json::to_string(head).map_err(|err| {
        validation(
            "state_head_encode_failed",
            &format!("cannot encode state head: {err}"),
        )
    })?;
    write_record_atomic(
        &state_head_path(paths, &head.memo_id),
        &LomoPayload {
            kind: LomoRecordKind::State,
            record_id: format!("head:{}", head.memo_id),
            body_json,
        },
    )
}

/// Loads a history revision object; corrupt → `CorruptState` codes, never clean-slate.
///
/// # Errors
///
/// Storage/corruption on missing/invalid records.
pub fn read_history_revision(
    paths: &LomoPaths,
    revision_id: &RevisionId,
) -> Result<HistoryRevisionV2, lomo_core::LomoError> {
    require_v2(paths)?;
    let path = history_revision_path(paths, revision_id);
    let record = read_record(&path)?;
    if record.payload.kind != LomoRecordKind::History {
        return Err(corruption(
            "history_v2_kind_mismatch",
            "history object is not a History record",
        ));
    }
    serde_json::from_str(&record.payload.body_json).map_err(|err| {
        corruption(
            "history_v2_payload_invalid",
            &format!("cannot decode history revision: {err}"),
        )
    })
}

/// Applies retention prune for a memo head.
///
/// Writes permanent tombstones for pruned ids and optionally deletes pruned object files.
/// **Never** deletes user Markdown/media.
///
/// # Errors
///
/// Storage failures.
pub fn prune_history_with_tombstones<S: BuildHasher, P: BuildHasher>(
    paths: &LomoPaths,
    head: &RevisionId,
    by_id: &HashMap<RevisionId, HistoryRevisionV2, S>,
    pins: &HashSet<RevisionId, P>,
    pruned_at_ms: i64,
    delete_pruned_objects: bool,
) -> Result<Vec<RevisionId>, lomo_core::LomoError> {
    require_v2(paths)?;
    let keep = retention_keep_set(head, by_id, pins, HISTORY_RETENTION_REVISIONS);
    let to_prune = revisions_to_prune(head, by_id, &keep);
    for id in &to_prune {
        let memo_id = by_id
            .get(id)
            .map_or_else(String::new, |r| r.memo_id.clone());
        write_history_tombstone(
            paths,
            &HistoryTombstone {
                memo_id,
                revision_id: id.clone(),
                pruned_at_ms,
            },
        )?;
        if delete_pruned_objects {
            let path = history_revision_path(paths, id);
            if path.exists() {
                // Object delete is internal history only — not a user-file path.
                std::fs::remove_file(&path).map_err(|err| {
                    storage(
                        "history_prune_delete_failed",
                        &format!("cannot delete pruned history object: {err}"),
                    )
                })?;
            }
        }
    }
    Ok(to_prune)
}

/// Validates parent closure for a set of revisions (every parent exists in the set or is root).
///
/// # Errors
///
/// Corruption when a parent is missing from the closure.
pub fn validate_parent_closure(
    revisions: &[HistoryRevisionV2],
) -> Result<(), lomo_core::LomoError> {
    let ids: HashSet<&str> = revisions.iter().map(|r| r.revision_id.as_str()).collect();
    for rev in revisions {
        for parent in &rev.parent_ids {
            if !ids.contains(parent.as_str()) {
                return Err(corruption(
                    "history_parent_closure_broken",
                    "history parent is missing from migration closure",
                ));
            }
        }
        // Recompute id and fail closed on mismatch.
        let expected = RevisionId::compute(
            &rev.memo_id,
            &rev.parent_ids,
            &rev.content_digest,
            &rev.canonical_metadata,
        );
        if expected != rev.revision_id {
            return Err(corruption(
                "history_revision_id_mismatch",
                "history revision id does not match content-addressed formula",
            ));
        }
    }
    Ok(())
}

fn require_v2(paths: &LomoPaths) -> Result<(), lomo_core::LomoError> {
    if paths.layout != LomoLayoutVersion::V2 {
        return Err(validation(
            "history_v2_layout_required",
            "history/state v2 APIs require LomoLayoutVersion::V2",
        ));
    }
    Ok(())
}

fn reject_unsafe_memo_id(memo_id: &str) -> Result<(), lomo_core::LomoError> {
    if memo_id.is_empty()
        || memo_id.contains('/')
        || memo_id.contains('\\')
        || memo_id.contains("..")
        || memo_id.contains('\0')
    {
        return Err(validation(
            "invalid_memo_id_for_path",
            "memo_id is empty or contains path separators",
        ));
    }
    Ok(())
}

/// Groups v1 history bodies by memo for migration (caller supplies decoded v1 rows).
#[must_use]
pub fn order_v1_history_for_migration(
    rows: &[(String, u64, String, String)],
) -> BTreeMap<String, Vec<(u64, String, String)>> {
    // (memo_id, revision, content, fingerprint) → per-memo sorted by revision ascending.
    let mut map: BTreeMap<String, Vec<(u64, String, String)>> = BTreeMap::new();
    for (memo_id, revision, content, fingerprint) in rows {
        map.entry(memo_id.clone()).or_default().push((
            *revision,
            content.clone(),
            fingerprint.clone(),
        ));
    }
    for list in map.values_mut() {
        list.sort_by_key(|(rev, _, _)| *rev);
    }
    map
}

/// Converts ordered v1 history rows for one memo into a v2 linear chain.
///
/// # Errors
///
/// Validation on empty content digest issues (none expected for fingerprint).
pub fn migrate_memo_history_chain(
    memo_id: &str,
    ordered: &[(u64, String, String)],
    created_at_ms: i64,
) -> Result<Vec<HistoryRevisionV2>, lomo_core::LomoError> {
    let mut out = Vec::with_capacity(ordered.len());
    let mut parent: Option<HistoryRevisionV2> = None;
    for (revision, content, fingerprint) in ordered {
        let parents = parent.as_ref().map_or_else(Vec::new, |p| vec![p.clone()]);
        let metadata = format!("v1_revision={revision}");
        let rev = HistoryRevisionV2::create(
            memo_id,
            &parents,
            content.clone(),
            fingerprint,
            &metadata,
            created_at_ms,
        )?;
        parent = Some(rev.clone());
        out.push(rev);
    }
    Ok(out)
}

/// Ensures `workspace_root` is usable as a path argument without further use (doc helper).
#[must_use]
pub fn workspace_history_root(workspace_root: &Path) -> LomoPaths {
    LomoPaths::for_workspace_with_layout(workspace_root, LomoLayoutVersion::V2)
}
