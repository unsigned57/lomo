//! Durable record preparation; the shared transaction applies every prepared file.

use lomo_core::{LomoError, OperationId, RelativeWorkspacePath};
use lomo_workspace::{
    HistoryHead, HistoryRevisionV2, HistorySnapshotV1, LomoLayoutVersion, LomoPayload,
    LomoRecordKind, MemoId, RevisionId, SourceFingerprint, StateHead, StateRevisionCreate,
    StateRevisionV2, WorkspaceLayout, encode_record, history_head_path, history_revision_path,
    state_head_path, state_revision_path,
};
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use std::path::Path;

use crate::{
    error::corruption,
    transaction::PlannedFile,
    workspace_io::{FileSnapshot, MAX_WORKSPACE_READ_BATCH, WorkspaceIo},
};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct LayoutHeadBody {
    layout: LomoLayoutVersion,
}

pub fn validate_workspace_layout(io: &WorkspaceIo<'_>) -> Result<(), LomoError> {
    let path = RelativeWorkspacePath::parse(lomo_workspace::layout::relative::LAYOUT_HEAD)?;
    let Some(snapshot) = io.read(&path)? else {
        return Ok(());
    };
    let record = lomo_workspace::decode_record(&snapshot.bytes)?;
    if record.payload.kind != LomoRecordKind::LayoutHead || record.payload.record_id != "layout" {
        return Err(corruption(
            "invalid_workspace_layout",
            "layout head does not contain its canonical record",
        ));
    }
    let head: LayoutHeadBody =
        serde_json::from_str(&record.payload.body_json).map_err(|error| {
            crate::error::validation("unsupported_workspace_layout", error.to_string())
        })?;
    match head.layout {
        LomoLayoutVersion::V1 | LomoLayoutVersion::V2 => Ok(()),
    }
}

pub fn record_bytes(
    kind: LomoRecordKind,
    id: &str,
    body: &impl Serialize,
) -> Result<Vec<u8>, LomoError> {
    let body_json = serde_json::to_string(body)
        .map_err(|error| corruption("record_encode_failed", error.to_string()))?;
    encode_record(&LomoPayload {
        kind,
        record_id: id.to_owned(),
        body_json,
    })
}

pub struct RevisionTip<T> {
    pub head_file: FileSnapshot,
    pub revision: T,
}

fn paths() -> WorkspaceLayout {
    WorkspaceLayout::for_workspace_with_layout(Path::new(""), LomoLayoutVersion::V2)
}

fn relative(path: &Path) -> Result<RelativeWorkspacePath, LomoError> {
    RelativeWorkspacePath::parse(
        path.to_str()
            .ok_or_else(|| corruption("invalid_record_path", "record path is not UTF-8"))?,
    )
}

pub fn decode_typed<T: DeserializeOwned>(
    snapshot: &FileSnapshot,
    kind: LomoRecordKind,
    id: &str,
) -> Result<T, LomoError> {
    let record = lomo_workspace::decode_record(&snapshot.bytes)?;
    if record.payload.kind != kind || record.payload.record_id != id {
        return Err(corruption(
            "record_identity_mismatch",
            "record envelope does not match its kind or address",
        ));
    }
    serde_json::from_str(&record.payload.body_json)
        .map_err(|error| corruption("invalid_record_body", error.to_string()))
}

pub fn history_head_relative_path(id: &MemoId) -> Result<RelativeWorkspacePath, LomoError> {
    relative(&history_head_path(&paths(), id.as_str()))
}

pub fn history_tip(
    io: &WorkspaceIo<'_>,
    id: &MemoId,
) -> Result<Option<RevisionTip<HistoryRevisionV2>>, LomoError> {
    let path = history_head_relative_path(id)?;
    let Some(head_file) = io.read(&path)? else {
        return Ok(None);
    };
    let head = decode_canonical_history_head(&head_file, id)?;
    let object = io.require(&relative(&history_revision_path(
        &paths(),
        &head.head_revision_id,
    ))?)?;
    Ok(Some(admit_history_tip(id, head_file, &head, &object)?))
}

/// Loads listed canonical heads and their tips in bounded dependency-ordered batches.
/// A listed head disappearing is corruption, not permission to create replacement history.
pub fn history_tips(
    io: &WorkspaceIo<'_>,
    ids: &[&MemoId],
) -> Result<Vec<RevisionTip<HistoryRevisionV2>>, LomoError> {
    let mut tips = Vec::with_capacity(ids.len());
    for chunk in ids.chunks(MAX_WORKSPACE_READ_BATCH) {
        let head_paths = chunk
            .iter()
            .map(|id| history_head_relative_path(id))
            .collect::<Result<Vec<_>, _>>()?;
        let head_files = io.require_batch(&head_paths).map_err(|error| {
            if error.code() == "document_not_found" {
                corruption(
                    "history_head_missing",
                    "listed history head disappeared during scan",
                )
            } else {
                error
            }
        })?;
        let heads = chunk
            .iter()
            .zip(&head_files)
            .map(|(id, file)| decode_canonical_history_head(file, id))
            .collect::<Result<Vec<_>, _>>()?;
        let object_paths = heads
            .iter()
            .map(|head| relative(&history_revision_path(&paths(), &head.head_revision_id)))
            .collect::<Result<Vec<_>, _>>()?;
        let objects = io.require_batch(&object_paths)?;
        for (((id, head_file), head), object) in
            chunk.iter().zip(head_files).zip(heads).zip(objects)
        {
            tips.push(admit_history_tip(id, head_file, &head, &object)?);
        }
    }
    Ok(tips)
}

fn admit_history_tip(
    id: &MemoId,
    head_file: FileSnapshot,
    head: &HistoryHead,
    object: &FileSnapshot,
) -> Result<RevisionTip<HistoryRevisionV2>, LomoError> {
    let revision: HistoryRevisionV2 = decode_typed(
        object,
        LomoRecordKind::History,
        head.head_revision_id.as_str(),
    )?;
    validate_history_revision(&revision)?;
    if revision.memo_id != id.as_str() || revision.revision_id != head.head_revision_id {
        return Err(corruption(
            "history_head_mismatch",
            "history head points outside its memo identity",
        ));
    }
    Ok(RevisionTip {
        head_file,
        revision,
    })
}

pub fn decode_canonical_history_head(
    file: &FileSnapshot,
    id: &MemoId,
) -> Result<HistoryHead, LomoError> {
    let head: HistoryHead = decode_typed(
        file,
        LomoRecordKind::History,
        &format!("head:{}", id.as_str()),
    )?;
    RevisionId::parse(head.head_revision_id.as_str())?;
    if head.memo_id != id.as_str() {
        return Err(corruption(
            "history_head_mismatch",
            "history head points outside its memo identity",
        ));
    }
    Ok(head)
}

pub fn validate_history_revision(revision: &HistoryRevisionV2) -> Result<(), LomoError> {
    MemoId::parse(&revision.memo_id)?;
    let digest = SourceFingerprint::of_bytes(revision.content.as_bytes());
    let expected = RevisionId::compute(
        &revision.memo_id,
        &revision.parent_ids,
        &revision.content_digest,
        &revision.canonical_metadata,
    );
    if digest.as_str() != revision.content_digest
        || expected != revision.revision_id
        || revision.generation == 0
        || revision.parent_ids.len() > 2
        || (revision.parent_ids.is_empty() && revision.generation != 1)
    {
        return Err(corruption(
            "invalid_history_revision",
            "history revision does not match its content-addressed facts",
        ));
    }
    Ok(())
}

pub fn state_tip(
    io: &WorkspaceIo<'_>,
    id: &MemoId,
) -> Result<Option<RevisionTip<StateRevisionV2>>, LomoError> {
    let head_path = relative(&state_head_path(&paths(), id.as_str()))?;
    let Some(head_file) = io.read(&head_path)? else {
        return Ok(None);
    };
    state_tip_at(io, id, head_file).map(Some)
}

/// Resolves the durable state tip beneath an already-read canonical head file.
///
/// The file must still prove it anchors `id`: the envelope record id and the
/// decoded body face the same fail-closed checks [`state_tip`] applies, so a
/// caller that admitted the file under body authority gets the identical tip —
/// and the identical corruption — the canonical-slot resolver produces.
pub fn state_tip_at(
    io: &WorkspaceIo<'_>,
    id: &MemoId,
    head_file: FileSnapshot,
) -> Result<RevisionTip<StateRevisionV2>, LomoError> {
    let head: StateHead = decode_typed(
        &head_file,
        LomoRecordKind::State,
        &format!("head:{}", id.as_str()),
    )?;
    RevisionId::parse(head.head_revision_id.as_str())?;
    let object = io.require(&relative(&state_revision_path(
        &paths(),
        &head.head_revision_id,
    ))?)?;
    let revision: StateRevisionV2 = decode_typed(
        &object,
        LomoRecordKind::State,
        head.head_revision_id.as_str(),
    )?;
    if head.memo_id != id.as_str()
        || revision.memo_id != id.as_str()
        || revision.revision_id != head.head_revision_id
        || revision.generation == 0
        || revision.parent_ids.len() > 1
    {
        return Err(corruption(
            "state_head_mismatch",
            "state head points outside its memo identity",
        ));
    }
    Ok(RevisionTip {
        head_file,
        revision,
    })
}

fn immutable_file(
    path: &Path,
    kind: LomoRecordKind,
    id: &str,
    body: &impl Serialize,
) -> Result<PlannedFile, LomoError> {
    let path = relative(path)?;
    let bytes = record_bytes(kind, id, body)?;
    // Content-addressed objects are create-only. The platform's fused write observation
    // accepts identical bytes and rejects a foreign occupant before mutation. A planning
    // read would repeat that arbitration and cannot protect against a later race.
    Ok(PlannedFile::new(path, None, bytes))
}

pub struct PreparedHistory {
    pub files: Vec<PlannedFile>,
    pub revision: HistoryRevisionV2,
}

pub fn history_files(
    io: &WorkspaceIo<'_>,
    history: &HistorySnapshotV1,
) -> Result<PreparedHistory, LomoError> {
    let id = MemoId::parse(&history.memo_id)?;
    let previous = history_tip(io, &id)?;
    history_files_after_tip(history, previous.as_ref())
}

/// Prepares the next revision from the tip already observed by this operation.
pub fn history_files_after_tip(
    history: &HistorySnapshotV1,
    previous: Option<&RevisionTip<HistoryRevisionV2>>,
) -> Result<PreparedHistory, LomoError> {
    let parents = previous
        .as_ref()
        .map_or(&[][..], |tip| std::slice::from_ref(&tip.revision));
    let digest = SourceFingerprint::of_bytes(history.content.as_bytes());
    let revision = HistoryRevisionV2::create(
        &history.memo_id,
        parents,
        history.content.clone(),
        digest.as_str(),
        "",
        history.created_at_ms,
    )?;
    if revision.generation != history.revision {
        return Err(corruption(
            "history_projection_revision_mismatch",
            "projection revision disagrees with the durable history head",
        ));
    }
    let head = HistoryHead {
        memo_id: history.memo_id.clone(),
        head_revision_id: revision.revision_id.clone(),
    };
    let files = vec![
        immutable_file(
            &history_revision_path(&paths(), &revision.revision_id),
            LomoRecordKind::History,
            revision.revision_id.as_str(),
            &revision,
        )?,
        PlannedFile::new(
            relative(&history_head_path(&paths(), &history.memo_id))?,
            previous.as_ref().map(|tip| &tip.head_file),
            record_bytes(
                LomoRecordKind::History,
                &format!("head:{}", history.memo_id),
                &head,
            )?,
        ),
    ];
    Ok(PreparedHistory { files, revision })
}

pub fn history_projection(revision: HistoryRevisionV2) -> lomo_store::ScannedHistoryProjection {
    lomo_store::ScannedHistoryProjection {
        memo_id: revision.memo_id,
        record_id: revision.revision_id.as_str().to_owned(),
        revision: revision.generation,
        created_at_ms: revision.created_at_ms,
        content: revision.content,
        file_fingerprint: revision.content_digest,
    }
}

#[derive(Clone, Copy)]
pub enum StateChange {
    Pin(Option<i64>),
    Trash(i64),
    Restore,
}

pub fn state_files(
    io: &WorkspaceIo<'_>,
    id: &MemoId,
    operation: &OperationId,
    change: StateChange,
    now_ms: i64,
) -> Result<Vec<PlannedFile>, LomoError> {
    let previous = state_tip(io, id)?;
    let parent = previous.as_ref().map(|tip| &tip.revision);
    let (pinned_at_ms, trashed_at_ms, pin_operation_id, trash_operation_id) = match change {
        StateChange::Pin(timestamp) => (
            timestamp,
            parent.and_then(|state| state.trashed_at_ms),
            Some(operation.as_str().to_owned()),
            parent.and_then(|state| state.trash_operation_id.clone()),
        ),
        StateChange::Trash(timestamp) => (
            parent.and_then(|state| state.pinned_at_ms),
            Some(timestamp),
            parent.and_then(|state| state.pin_operation_id.clone()),
            Some(operation.as_str().to_owned()),
        ),
        StateChange::Restore => (
            parent.and_then(|state| state.pinned_at_ms),
            None,
            parent.and_then(|state| state.pin_operation_id.clone()),
            Some(operation.as_str().to_owned()),
        ),
    };
    let state = StateRevisionV2::create(StateRevisionCreate {
        memo_id: id.as_str(),
        parent,
        pinned: pinned_at_ms.is_some(),
        trashed: trashed_at_ms.is_some(),
        pinned_at_ms,
        trashed_at_ms,
        pin_operation_id,
        trash_operation_id,
        canonical_metadata: "",
        created_at_ms: now_ms,
    })?;
    let head = StateHead {
        memo_id: id.as_str().to_owned(),
        head_revision_id: state.revision_id.clone(),
    };
    Ok(vec![
        immutable_file(
            &state_revision_path(&paths(), &state.revision_id),
            LomoRecordKind::State,
            state.revision_id.as_str(),
            &state,
        )?,
        PlannedFile::new(
            relative(&state_head_path(&paths(), id.as_str()))?,
            previous.as_ref().map(|tip| &tip.head_file),
            record_bytes(
                LomoRecordKind::State,
                &format!("head:{}", id.as_str()),
                &head,
            )?,
        ),
    ])
}

/// Freezes a trash snapshot from the canonical memo projection used by the same publication.
pub fn trash_snapshot(
    projection: &lomo_store::ScannedMemoProjection,
    time_part: &str,
    trashed_at_ms: i64,
) -> Result<lomo_workspace::TrashRecordV1, LomoError> {
    lomo_workspace::TrashRecordV1::try_new(lomo_workspace::TrashRecordCreate {
        memo_id: projection.memo_id.clone(),
        source_path: projection.source_path.clone(),
        time_part: time_part.to_owned(),
        source_fingerprint: projection.file_fingerprint.clone(),
        chronology_epoch_ms: projection.chronology_epoch_ms,
        trashed_at_ms,
        body: projection.body.clone(),
        tags: projection.tags.clone(),
        attachments: projection.attachment_paths.clone(),
        reminders: projection.reminders.clone(),
        has_todo: projection.has_todo,
        has_url: projection.has_url,
    })
}
