//! Rebuild follows durable heads; historical state objects cannot resurrect an old pin.

use std::collections::{BTreeMap, BTreeSet};
use std::path::Path;

use lomo_core::{LomoError, RelativeWorkspacePath};
use lomo_store::{ScannedHistoryProjection, ScannedPinProjection};
use lomo_workspace::{
    HistoryHead, HistoryRevisionV2, HistoryTombstone, LomoLayoutVersion, LomoPaths, LomoRecord,
    LomoRecordKind, MemoId, RevisionId, StateHead, StateRevisionV2, decode_record,
    history_revision_path, history_tombstone_path, state_head_path,
};
use serde::de::DeserializeOwned;

use crate::{
    error::corruption,
    record_plan::{history_tip, state_tip, state_tip_at, validate_history_revision},
    workspace_io::{FileSnapshot, WorkspaceIo},
};

fn layout_paths() -> LomoPaths {
    LomoPaths::for_workspace_with_layout(Path::new(""), LomoLayoutVersion::V2)
}

fn relative(path: &Path) -> Result<RelativeWorkspacePath, LomoError> {
    RelativeWorkspacePath::parse(
        path.to_str()
            .ok_or_else(|| corruption("invalid_record_path", "record path is not UTF-8"))?,
    )
}

fn body<T: DeserializeOwned>(record: &LomoRecord, kind: LomoRecordKind) -> Result<T, LomoError> {
    if record.payload.kind != kind {
        return Err(corruption(
            "record_kind_mismatch",
            "durable record has the wrong kind for its directory",
        ));
    }
    serde_json::from_str(&record.payload.body_json)
        .map_err(|error| corruption("invalid_record_body", error.to_string()))
}

#[derive(Default)]
struct HistoryGraph {
    heads: BTreeMap<String, RevisionId>,
    revisions: BTreeMap<RevisionId, HistoryRevisionV2>,
    pruned: BTreeSet<RevisionId>,
}

impl HistoryGraph {
    /// Admits one durable history record under its path's naming authority.
    ///
    /// The filename stem is the claimed identity the path itself makes: a head's
    /// stem is a memo id, an object's and a tombstone's stem is a revision id.
    /// Every branch proves the stem agrees with the identity the decoded body
    /// claims before the record joins a map — a file naming anything else is
    /// naming-authority-foreign evidence and dies fail-closed instead of being
    /// keyed by a claim its stem could never make. Returns the parent claims an
    /// admitted object carries so a closure walk's discovery follows what was
    /// actually admitted; heads and tombstones discover nothing.
    fn insert(
        &mut self,
        path: &RelativeWorkspacePath,
        record: &LomoRecord,
    ) -> Result<Vec<RevisionId>, LomoError> {
        let location = path.as_str();
        if let Some(name) = location.strip_prefix(".lomo/history/v2/heads/") {
            // A head file's canonical address is part of its claim: the
            // envelope must read `head:<stem>` and the decoded body must name
            // `<stem>` — the same agreement `history_tip` proves when it
            // resolves a slot. Both are file-level facts judged in
            // `history_tip`'s order (envelope, revision id, then identity)
            // before the claim joins the map, so a file carrying another
            // identity's head dies on itself instead of surfacing later as a
            // duplicate or a missing tip.
            let Some(stem) = name.strip_suffix(".rec") else {
                return Err(corruption(
                    "unsupported_history_layout",
                    "unknown durable history directory",
                ));
            };
            if record.payload.kind != LomoRecordKind::History
                || record.payload.record_id != format!("head:{stem}")
            {
                return Err(corruption(
                    "record_identity_mismatch",
                    "record envelope does not match its kind or address",
                ));
            }
            let head: HistoryHead = body(record, LomoRecordKind::History)?;
            RevisionId::parse(head.head_revision_id.as_str())?;
            if head.memo_id != stem {
                return Err(corruption(
                    "history_head_mismatch",
                    "history head points outside its memo identity",
                ));
            }
            MemoId::parse(&head.memo_id)?;
            self.heads.insert(head.memo_id, head.head_revision_id);
        } else if let Some(name) = location.strip_prefix(".lomo/history/v2/objects/") {
            let Some(stem) = name.strip_suffix(".rec") else {
                return Err(corruption(
                    "unsupported_history_layout",
                    "unknown durable history directory",
                ));
            };
            let revision: HistoryRevisionV2 = body(record, LomoRecordKind::History)?;
            validate_history_revision(&revision)?;
            if record.payload.record_id != revision.revision_id.as_str() {
                return Err(corruption(
                    "history_record_identity_mismatch",
                    "history object ID differs from its envelope",
                ));
            }
            // The path stem is the revision this slot names: an object keyed
            // anywhere else would let a claim escape the very path the closure
            // walk — and every later reader — can name, the same axiom the
            // heads branch proves against `head:<stem>`.
            if stem != revision.revision_id.as_str() {
                return Err(corruption(
                    "history_object_path_mismatch",
                    "history object is not stored at its canonical path",
                ));
            }
            let parent_ids = revision.parent_ids.clone();
            self.revisions
                .insert(revision.revision_id.clone(), revision);
            return Ok(parent_ids);
        } else if let Some(name) = location.strip_prefix(".lomo/history/v2/tombstones/") {
            let Some(stem) = name.strip_suffix(".rec") else {
                return Err(corruption(
                    "unsupported_history_layout",
                    "unknown durable history directory",
                ));
            };
            let tombstone: HistoryTombstone = body(record, LomoRecordKind::HistoryTombstone)?;
            RevisionId::parse(tombstone.revision_id.as_str())?;
            // A tombstone's prune effect keys on the body's claimed revision
            // id, but a scoped re-walk can only ever open `tombstones/<stem>.rec`:
            // an unbound claim would prune on the listing arm while staying
            // unnameable inside every closure.
            if stem != tombstone.revision_id.as_str() {
                return Err(corruption(
                    "history_tombstone_path_mismatch",
                    "history tombstone is not stored at its canonical path",
                ));
            }
            self.pruned.insert(tombstone.revision_id);
        } else {
            return Err(corruption(
                "unsupported_history_layout",
                "unknown durable history directory",
            ));
        }
        Ok(Vec::new())
    }

    /// Re-reads the durable head and proves it still points at `head` — the freshness check
    /// every projection runs before walking.
    fn check_tip(io: &WorkspaceIo<'_>, memo_id: &str, head: &RevisionId) -> Result<(), LomoError> {
        let tip = history_tip(io, &MemoId::parse(memo_id)?)?.ok_or_else(|| {
            corruption(
                "history_head_missing",
                "history head disappeared while rebuilding",
            )
        })?;
        if &tip.revision.revision_id != head {
            return Err(corruption(
                "history_head_changed",
                "history head changed during rebuild",
            ));
        }
        Ok(())
    }

    /// Walks the already-loaded object map beneath `head`.
    fn project_loaded(
        &self,
        memo_id: &str,
        head: &RevisionId,
    ) -> Result<Vec<ScannedHistoryProjection>, LomoError> {
        let mut output = Vec::new();
        let mut pending = vec![head.clone()];
        let mut visited = BTreeSet::new();
        while let Some(id) = pending.pop() {
            if !visited.insert(id.clone()) || self.pruned.contains(&id) {
                continue;
            }
            let revision = self.revisions.get(&id).ok_or_else(|| {
                corruption(
                    "history_parent_missing",
                    "reachable history object is absent",
                )
            })?;
            if revision.memo_id.as_str() != memo_id {
                return Err(corruption(
                    "history_parent_memo_mismatch",
                    "history parent belongs to another memo",
                ));
            }
            for parent_id in &revision.parent_ids {
                if let Some(parent) = self.revisions.get(parent_id)
                    && parent.generation >= revision.generation
                {
                    return Err(corruption(
                        "history_generation_invalid",
                        "history generation does not increase along its parent chain",
                    ));
                }
                pending.push(parent_id.clone());
            }
            output.push(ScannedHistoryProjection {
                memo_id: memo_id.to_owned(),
                record_id: id.as_str().to_owned(),
                revision: revision.generation,
                created_at_ms: revision.created_at_ms,
                content: revision.content.clone(),
                file_fingerprint: revision.content_digest.clone(),
            });
        }
        Ok(output)
    }

    fn project(&self, io: &WorkspaceIo<'_>) -> Result<Vec<ScannedHistoryProjection>, LomoError> {
        let mut output = Vec::new();
        for (memo_id, head) in &self.heads {
            Self::check_tip(io, memo_id, head)?;
            output.extend(self.project_loaded(memo_id, head)?);
        }
        Ok(output)
    }
}

pub fn history(
    io: &WorkspaceIo<'_>,
    paths: &[RelativeWorkspacePath],
) -> Result<Vec<ScannedHistoryProjection>, LomoError> {
    let mut graph = HistoryGraph::default();
    for path in paths {
        graph.insert(path, &decode_record(&io.require(path)?.bytes)?)?;
    }
    graph.project(io)
}

/// Re-walks exactly one memo's durable history chain for a path-scoped reconcile.
///
/// The canonical slot resolves through [`history_tip`] — the same fail-closed
/// envelope, revision-id, and tip-object checks the full scan's freshness check
/// and the write path share — then the closure walk admits every reachable
/// object and tombstone byte beneath it through the same [`HistoryGraph::insert`]
/// audit the cold scan applies to every listed file. A tombstone is only a
/// projection verdict (`project_loaded` prunes on `graph.pruned`); it never
/// exempts the object's bytes from admission, so a corrupt tombstoned ancestor
/// dies here exactly as [`history`] dies decoding it. A missing head projects
/// no revisions, matching a cold scan that never saw the head file.
///
/// # Errors
/// `history_tip`'s verdict for the slot first — `record_identity_mismatch` for
/// a foreign envelope or kind, `invalid_revision_id` for an unparseable claim,
/// `history_head_mismatch` for a body naming another memo — then the same
/// object/tombstone admission failures the full scan surfaces, including
/// `history_object_path_mismatch`/`history_tombstone_path_mismatch` when a
/// file's body claims an identity its filename stem cannot name.
pub fn history_scoped(
    io: &WorkspaceIo<'_>,
    memo_id: &MemoId,
) -> Result<Vec<ScannedHistoryProjection>, LomoError> {
    let mut graph = HistoryGraph::default();
    let Some(tip) = history_tip(io, memo_id)? else {
        return Ok(Vec::new());
    };
    // `history_tip` already proved `revision.revision_id == head_revision_id`, so the
    // resolved tip is exactly the claim the slot file made — the `check_tip` freshness
    // compare a batched cold scan needs is trivially satisfied by this single read.
    let head = tip.revision.revision_id;
    // Load the reachable closure. The walk's job is admission: every durable byte
    // it can name — object or tombstone — meets the `graph.insert` audit the cold
    // scan applies to every listed file, including the stem↔claim binding that
    // makes a foreign body at a canonical slot die on its own stem instead of
    // keying a claim no path in the closure could ever name. Discovery follows
    // each admitted object's parent claims whether or not a tombstone marks the
    // node, because pruning is `project_loaded`'s verdict (`graph.pruned`), never
    // a byte-level exemption: an absent file owes no audit on either arm, while
    // a non-pruned reachable gap surfaces `project_loaded`'s
    // `history_parent_missing` identically to cold.
    let mut pending = vec![head.clone()];
    let mut visited = BTreeSet::new();
    while let Some(id) = pending.pop() {
        if !visited.insert(id.clone()) {
            continue;
        }
        let object_path = relative(&history_revision_path(&layout_paths(), &id))?;
        if let Some(snapshot) = io.read(&object_path)? {
            let discovered = graph.insert(&object_path, &decode_record(&snapshot.bytes)?)?;
            pending.extend(discovered);
        }
        let tombstone_path = relative(&history_tombstone_path(&layout_paths(), &id))?;
        if let Some(snapshot) = io.read(&tombstone_path)? {
            graph.insert(&tombstone_path, &decode_record(&snapshot.bytes)?)?;
        }
    }
    graph.project_loaded(memo_id.as_str(), &head)
}

/// The pin verdict one memo's canonical durable state head attests.
///
/// A state head is the sole pin authority for the identity it names, and its absence is a
/// different durable answer than an unpinned tip. A projection-cache `memo_pin` row is
/// app-private state a rebuild may carry forward only for identities durable never answered —
/// the cold side gates that carry on `attested_ids`, so the scoped re-walk must hand the
/// caller the same distinction instead of collapsing "no head" into "no pin".
pub enum ScopedPinVerdict {
    /// The tip says `pinned=true` — the projection row to upsert where a memo row survives.
    Pinned(ScannedPinProjection),
    /// The tip says `pinned=false` — durable answered "no pin": a committed cache pin for
    /// this identity is stale evidence the caller removes.
    Unpinned,
    /// No state head anchors this identity — the canonical slot is absent or
    /// holds a file naming another identity — so durable has no answer for it
    /// and the committed cache pin keeps its verdict wherever a memo row
    /// survives.
    Unattested,
}

/// Re-derives one memo's pin verdict from its canonical durable state head.
///
/// A pinned tip, an explicit unpinned tip and a missing head are three different durable
/// answers and stay distinct in [`ScopedPinVerdict`]. What each contributes to the projection
/// is the caller's reconcile semantics; `Unattested` is exactly the case where a cold
/// materialize contributes the carried projection-cache pin — an identity outside
/// `attested_ids` whose `memo` row survives — not "no pin".
///
/// The slot file is admitted under body authority — the same authority `pins()`
/// applies to every head it iterates. A file whose decoded body names another
/// identity is that identity's duplicate evidence, never this identity's head,
/// so the verdict is `Unattested` exactly as the cold walk yields when no head
/// claims the identity. Only a file anchoring `memo_id` reaches the tip walk,
/// where [`state_tip_at`] applies the same fail-closed envelope check
/// `state_tip` performs inside `pins()`.
///
/// # Errors
/// Same failures `pins()` surfaces for this file: framing, kind, body, and
/// identity admission are the identical checks.
pub fn state_scoped(io: &WorkspaceIo<'_>, memo_id: &MemoId) -> Result<ScopedPinVerdict, LomoError> {
    let Some((head_file, _head)) = admitted_state_head(io, memo_id)? else {
        return Ok(ScopedPinVerdict::Unattested);
    };
    let tip = state_tip_at(io, memo_id, head_file)?;
    Ok(
        pin_timestamp(tip.revision.pinned, tip.revision.pinned_at_ms)?.map_or(
            ScopedPinVerdict::Unpinned,
            |pinned_at_ms| {
                ScopedPinVerdict::Pinned(ScannedPinProjection {
                    memo_id: memo_id.as_str().to_owned(),
                    pinned_at_ms,
                })
            },
        ),
    )
}

/// Reads `memo_id`'s canonical durable state head and admits it under body
/// authority — the single head-admission predicate [`state_scoped`]'s
/// `Unattested` answer and [`pins()`]'s head walk share.
///
/// `None` is the unattested answer: the canonical slot is absent or the file
/// on it decodes to a body naming another identity — that identity's duplicate
/// evidence, never this one's head. Decode failures propagate: `pins()` meets
/// the same file through the identical `decode_state_head` predicate, so the
/// error is already the cold verdict for this file.
fn admitted_state_head(
    io: &WorkspaceIo<'_>,
    memo_id: &MemoId,
) -> Result<Option<(FileSnapshot, StateHead)>, LomoError> {
    let head_path = relative(&state_head_path(&layout_paths(), memo_id.as_str()))?;
    let Some(head_file) = io.read(&head_path)? else {
        return Ok(None);
    };
    let (body_id, head) = decode_state_head(&decode_record(&head_file.bytes)?)?;
    if body_id != *memo_id {
        return Ok(None);
    }
    Ok(Some((head_file, head)))
}

/// The tip `memo_id`'s canonical durable state head claims — the head-level
/// half of the [`state_scoped`] judgment, stopping before the tip-object read.
///
/// A changed `state/v2/objects/<stem>.rec` is re-judged cold only through the
/// durable heads pointing at its stem, so its projection scope is provable
/// exactly when the memo its body claims owns an anchoring head claiming that
/// stem. `None` is [`admitted_state_head`]'s unattested answer: no canonical
/// head, or a body naming another identity — the slot's owner is then outside
/// what the body's claim can name.
///
/// # Errors
/// Same head-file decode failures [`pins()`] surfaces for the slot.
pub fn state_head_tip_claim(
    io: &WorkspaceIo<'_>,
    memo_id: &MemoId,
) -> Result<Option<RevisionId>, LomoError> {
    Ok(admitted_state_head(io, memo_id)?.map(|(_, head)| head.head_revision_id))
}

/// Decodes a durable history head far enough for scope proving — the body-authority
/// half of [`HistoryGraph::insert`]'s admission, which adds the path's envelope and
/// stem checks the decode cannot see.
///
/// # Errors
/// Propagates framing, kind, body, and id validation failures.
pub fn decode_history_head(bytes: &[u8]) -> Result<HistoryHead, LomoError> {
    let record = decode_record(bytes)?;
    let head: HistoryHead = body(&record, LomoRecordKind::History)?;
    MemoId::parse(&head.memo_id)?;
    RevisionId::parse(head.head_revision_id.as_str())?;
    Ok(head)
}

/// Decodes and validates one durable history object — the body-authority half
/// of [`HistoryGraph::insert`]'s admission, which adds the stem↔claim check
/// this decode cannot see. A caller judging a named file must also prove
/// `revision.revision_id == <stem>` before trusting the decoded owner.
///
/// # Errors
/// Propagates decode, content-address, and envelope mismatches.
pub fn decode_history_revision_record(bytes: &[u8]) -> Result<HistoryRevisionV2, LomoError> {
    let record = decode_record(bytes)?;
    let revision: HistoryRevisionV2 = body(&record, LomoRecordKind::History)?;
    validate_history_revision(&revision)?;
    if record.payload.record_id != revision.revision_id.as_str() {
        return Err(corruption(
            "history_record_identity_mismatch",
            "history object ID differs from its envelope",
        ));
    }
    Ok(revision)
}

/// Decodes one durable history tombstone — the body-authority half of
/// [`HistoryGraph::insert`]'s admission, which adds the stem↔claim check this
/// decode cannot see. A caller judging a named file must also prove
/// `tombstone.revision_id == <stem>` before trusting the claimed prune target.
///
/// # Errors
/// Propagates decode and revision-id validation failures.
pub fn decode_history_tombstone(bytes: &[u8]) -> Result<HistoryTombstone, LomoError> {
    let record = decode_record(bytes)?;
    let tombstone: HistoryTombstone = body(&record, LomoRecordKind::HistoryTombstone)?;
    RevisionId::parse(tombstone.revision_id.as_str())?;
    Ok(tombstone)
}

/// Admits one decoded record as a durable state head — the single predicate
/// `pins()`, the scoped pass, and `state_scoped` all run before judging a head
/// file: `State` kind, a parseable [`StateHead`] body, a valid memo identity,
/// and a parseable claimed tip revision. The validated identity returns
/// alongside the body so callers never re-derive it.
///
/// # Errors
/// Propagates kind, body, memo-id, and revision-id validation failures.
pub fn decode_state_head(record: &LomoRecord) -> Result<(MemoId, StateHead), LomoError> {
    let head: StateHead = body(record, LomoRecordKind::State)?;
    let id = MemoId::parse(&head.memo_id)?;
    RevisionId::parse(head.head_revision_id.as_str())?;
    Ok((id, head))
}

/// Decodes one durable state revision object — the body-authority half of the
/// admission a `state_tip_at` walk performs when a head claims the object's
/// stem: `State` kind, a parseable [`StateRevisionV2`], a valid memo identity,
/// a parseable claimed revision id, and an envelope `record_id` equal to that
/// claim. The validated memo identity returns alongside the body so callers
/// never re-derive it.
///
/// The full scan never reads state objects standalone — heads resolve their
/// tip objects — so the caller treats a decode failure as an unprovable scope
/// and falls back to the full scan rather than rejecting bytes a cold scan
/// would have ignored. A caller judging a named file must still prove
/// `revision.revision_id == <stem>`, that the claimed memo's head points at
/// that stem, and `revision.recomputed_id() == revision.revision_id` — the
/// path-naming, slot-ownership, and content-address checks this decode cannot
/// see. The recompute stays outside this decode on purpose: every failure
/// here degrades to `Ok(false)`, and folding the content-address check in
/// would let it preempt the claimant head's decode error, which must keep
/// propagating as the head-layer code.
///
/// # Errors
/// Propagates framing, kind, body, id-validation, and envelope mismatches.
pub fn decode_state_revision_owner(bytes: &[u8]) -> Result<(MemoId, StateRevisionV2), LomoError> {
    let record = decode_record(bytes)?;
    let revision: StateRevisionV2 = body(&record, LomoRecordKind::State)?;
    let memo_id = MemoId::parse(&revision.memo_id)?;
    RevisionId::parse(revision.revision_id.as_str())?;
    if record.payload.record_id != revision.revision_id.as_str() {
        return Err(corruption(
            "record_identity_mismatch",
            "state object ID differs from its envelope",
        ));
    }
    Ok((memo_id, revision))
}

/// Durable state-head scan result: projectable pin facts plus the identities
/// whose pin verdict the workspace attests.
pub struct ScannedStateScan {
    /// Pins that may project — pinned tips over identities owning a live `memo` row.
    pub pins: Vec<ScannedPinProjection>,
    /// Every identity a durable state head attested a verdict for — pinned or
    /// unpinned, whether or not a `memo` row survives for it. Durable state owns
    /// the pin answer for these identities: a projection-cache `memo_pin` row for
    /// any of them is stale evidence from an older device or projection, never
    /// authority, so a rebuild carrying private cache forward must not copy it.
    pub attested_ids: BTreeSet<String>,
    /// Head paths outside their naming authority — the canonical head path of
    /// the identity the body names is elsewhere — that the tip-check absorbed
    /// as tolerated duplicate evidence. They must never join the committed
    /// `file_listing` baseline: a committed duplicate stops diffing and a claim
    /// gone stale would stay invisible to every later reconcile, so callers
    /// drop them from the listing rows they persist.
    pub absorbed_head_paths: Vec<String>,
}

/// Scans durable state heads for pin facts.
///
/// A pin fact exists only where its identity also owns a `memo` row — `live_ids` carries
/// every identity the scan projects a row for (document emissions plus surviving trash
/// claims). A pinned tip over any other identity — a memo permanently deleted or
/// record-suppressed while its tip stayed `pinned=true` — is a dead fact: emitting it would
/// violate the `memo_pin → memo` foreign key, so the head is still decoded and tip-checked
/// (corruption parity with any other state record) but never produces a projection.
///
/// # Errors
/// Same decode, envelope, and tip-consistency failures any state head surfaces.
pub fn pins(
    io: &WorkspaceIo<'_>,
    paths: &[RelativeWorkspacePath],
    live_ids: &BTreeSet<String>,
) -> Result<ScannedStateScan, LomoError> {
    let mut heads = BTreeMap::new();
    let mut absorbed_head_paths = Vec::new();
    for path in paths {
        if path.as_str().starts_with(".lomo/state/v2/objects/") {
            continue;
        }
        let record = decode_record(&io.require(path)?.bytes)?;
        if path.as_str().starts_with(".lomo/state/v2/heads/") {
            let (id, head) = decode_state_head(&record)?;
            let current = state_tip(io, &id)?.ok_or_else(|| {
                corruption(
                    "state_head_missing",
                    "state head disappeared during rebuild",
                )
            })?;
            if current.revision.revision_id != head.head_revision_id {
                return Err(corruption(
                    "state_head_changed",
                    "state changed during rebuild",
                ));
            }
            // The same tip-check absorbed the duplicate's claim, but the file is
            // outside its naming authority — it is duplicate evidence, never a
            // baseline row: a committed copy would stop diffing and a claim gone
            // stale would stay invisible to every later reconcile.
            if path.as_str() != relative(&state_head_path(&layout_paths(), id.as_str()))?.as_str() {
                absorbed_head_paths.push(path.as_str().to_owned());
            }
            heads.insert(
                head.memo_id,
                pin_timestamp(current.revision.pinned, current.revision.pinned_at_ms)?,
            );
        } else {
            return Err(corruption(
                "unsupported_state_layout",
                "unknown durable state directory",
            ));
        }
    }
    let attested_ids = heads.keys().cloned().collect();
    let pins = heads
        .into_iter()
        .filter(|(memo_id, _)| live_ids.contains(memo_id))
        .filter_map(|(memo_id, timestamp)| {
            timestamp.map(|pinned_at_ms| ScannedPinProjection {
                memo_id,
                pinned_at_ms,
            })
        })
        .collect();
    Ok(ScannedStateScan {
        pins,
        attested_ids,
        absorbed_head_paths,
    })
}

fn pin_timestamp(pinned: bool, timestamp: Option<i64>) -> Result<Option<i64>, LomoError> {
    if !pinned {
        return Ok(None);
    }
    let timestamp = timestamp
        .filter(|timestamp| *timestamp > 0)
        .ok_or_else(|| {
            corruption(
                "invalid_pin_timestamp",
                "pinned state must carry a positive timestamp",
            )
        })?;
    Ok(Some(timestamp))
}
