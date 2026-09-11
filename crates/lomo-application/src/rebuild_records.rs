//! Rebuild follows durable heads; historical state objects cannot resurrect an old pin.

use std::collections::{BTreeMap, BTreeSet};

use lomo_core::{LomoError, RelativeWorkspacePath};
use lomo_store::{ScannedHistoryProjection, ScannedPinProjection, StateBody};
use lomo_workspace::{
    HistoryHead, HistoryRevisionV2, HistorySnapshotV1, HistoryTombstone, LomoRecord,
    LomoRecordKind, MemoId, RevisionId, StateHead, decode_record,
};
use serde::de::DeserializeOwned;

use crate::error::corruption;
use crate::record_plan::{history_tip, state_tip, validate_history_revision};
use crate::workspace_io::WorkspaceIo;

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
    legacy: Vec<HistorySnapshotV1>,
}

impl HistoryGraph {
    fn insert(
        &mut self,
        path: &RelativeWorkspacePath,
        record: &LomoRecord,
    ) -> Result<(), LomoError> {
        let location = path.as_str();
        if location.starts_with(".lomo/history/v2/heads/") {
            let head: HistoryHead = body(record, LomoRecordKind::History)?;
            MemoId::parse(&head.memo_id)?;
            RevisionId::parse(head.head_revision_id.as_str())?;
            if self
                .heads
                .insert(head.memo_id, head.head_revision_id)
                .is_some()
            {
                return Err(corruption(
                    "duplicate_history_head",
                    "memo has multiple physical history heads",
                ));
            }
        } else if location.starts_with(".lomo/history/v2/objects/") {
            let revision: HistoryRevisionV2 = body(record, LomoRecordKind::History)?;
            validate_history_revision(&revision)?;
            if record.payload.record_id != revision.revision_id.as_str() {
                return Err(corruption(
                    "history_record_identity_mismatch",
                    "history object ID differs from its envelope",
                ));
            }
            self.revisions
                .insert(revision.revision_id.clone(), revision);
        } else if location.starts_with(".lomo/history/v2/tombstones/") {
            let tombstone: HistoryTombstone = body(record, LomoRecordKind::HistoryTombstone)?;
            RevisionId::parse(tombstone.revision_id.as_str())?;
            self.pruned.insert(tombstone.revision_id);
        } else if location.starts_with(".lomo/history/v1/") {
            self.legacy.push(body(record, LomoRecordKind::History)?);
        } else {
            return Err(corruption(
                "unsupported_history_layout",
                "unknown durable history directory",
            ));
        }
        Ok(())
    }

    fn project(&self, io: &WorkspaceIo<'_>) -> Result<Vec<ScannedHistoryProjection>, LomoError> {
        let mut output = Vec::new();
        for (memo_id, head) in &self.heads {
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
                if &revision.memo_id != memo_id {
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
                    memo_id: memo_id.clone(),
                    record_id: id.as_str().to_owned(),
                    revision: revision.generation,
                    created_at_ms: revision.created_at_ms,
                    content: revision.content.clone(),
                    file_fingerprint: revision.content_digest.clone(),
                });
            }
        }
        for revision in &self.legacy {
            if self.heads.contains_key(&revision.memo_id) {
                continue;
            }
            output.push(ScannedHistoryProjection {
                memo_id: revision.memo_id.clone(),
                record_id: format!("{}-r{}", revision.memo_id, revision.revision),
                revision: revision.revision,
                created_at_ms: revision.created_at_ms,
                content: revision.content.clone(),
                file_fingerprint: revision.file_fingerprint.clone(),
            });
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

pub fn pins(
    io: &WorkspaceIo<'_>,
    paths: &[RelativeWorkspacePath],
) -> Result<Vec<ScannedPinProjection>, LomoError> {
    let mut legacy = BTreeMap::new();
    let mut heads = BTreeMap::new();
    for path in paths {
        if path.as_str().starts_with(".lomo/state/v2/objects/") {
            continue;
        }
        let record = decode_record(&io.require(path)?.bytes)?;
        if path.as_str().starts_with(".lomo/state/v2/heads/") {
            let head: StateHead = body(&record, LomoRecordKind::State)?;
            let id = MemoId::parse(&head.memo_id)?;
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
            heads.insert(
                head.memo_id,
                pin_timestamp(current.revision.pinned, current.revision.pinned_at_ms)?,
            );
        } else if path.as_str().starts_with(".lomo/state/v1/") {
            let state: StateBody = body(&record, LomoRecordKind::State)?;
            legacy.insert(
                state.memo_id,
                pin_timestamp(state.pinned, state.pinned_at_ms)?,
            );
        } else {
            return Err(corruption(
                "unsupported_state_layout",
                "unknown durable state directory",
            ));
        }
    }
    legacy.extend(heads);
    Ok(legacy
        .into_iter()
        .filter_map(|(memo_id, timestamp)| {
            timestamp.map(|pinned_at_ms| ScannedPinProjection {
                memo_id,
                pinned_at_ms,
            })
        })
        .collect())
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
