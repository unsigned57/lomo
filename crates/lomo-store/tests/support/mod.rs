//! Shared Direct-workspace seed helpers for store contracts.
//!
//! Document create, update, pin, trash, and restore belong to
//! [`lomo_application::WorkspaceSession`]. Store tests seed `memos/<id>.md` plus rebuildable
//! `.lomo` state, or publish pin/trash through the projection commit used by the session.

#![expect(
    clippy::expect_used,
    reason = "contract helpers fail closed with panics on missing seed facts"
)]
#![allow(
    dead_code,
    reason = "each integration test binary compiles this module independently and uses a subset"
)]

use std::fs;
use std::path::Path;

use lomo_store::{
    SafProjectionMutation, SafProjectionMutationKind, ScannedMemoProjection, Store,
    fingerprint_content, project_reminder_references, run_rebuild,
};
use lomo_workspace::{
    MemoIdentity, TrashRecordCreate, TrashRecordV1, encode_trash_record, trash_record_relative_path,
};

pub fn seed_memo(root: &Path, memo: &str, content: &str, tags: &[&str]) {
    let dir = root.join("memos");
    fs::create_dir_all(&dir).expect("memos dir");
    let mut body = content.to_owned();
    for tag in tags {
        let marker = format!("#{tag}");
        if !body.contains(&marker) {
            body.push(' ');
            body.push_str(&marker);
        }
    }
    fs::write(dir.join(format!("{memo}.md")), body).expect("write memo");
}

/// Seeds the canonical durable trash marker. `run_rebuild` rehydrates the trashed projection from
/// this record alone; pin state belongs to the session-owned V2 state graph, not this indexer.
pub fn seed_trash_record(root: &Path, memo_id: &str, body: &str, tags: &[&str]) {
    let time_part = MemoIdentity::parse(memo_id).map_or_else(
        |_| memo_id.to_owned(),
        |identity| identity.time_part().to_owned(),
    );
    let record = TrashRecordV1::try_new(TrashRecordCreate {
        memo_id: memo_id.to_owned(),
        source_path: format!("memos/{memo_id}.md"),
        time_part,
        source_fingerprint: fingerprint_content(body),
        chronology_epoch_ms: 1_700_000_000_000,
        trashed_at_ms: 1_700_000_000_001,
        body: body.to_owned(),
        tags: tags.iter().map(|tag| (*tag).to_owned()).collect(),
        attachments: Vec::new(),
        reminders: project_reminder_references(body, memo_id).expect("reminders"),
        has_todo: false,
        has_url: false,
    })
    .expect("trash record");
    let relative = trash_record_relative_path(memo_id).expect("record path");
    let marker = root.join(relative.as_str());
    fs::create_dir_all(marker.parent().expect("trash parent")).expect("trash dir");
    fs::write(&marker, encode_trash_record(&record).expect("encode")).expect("write marker");
}

pub fn indexed_store(root: &Path) -> Store {
    lomo_workspace::load_or_mint_workspace_generation(root).expect("workspace generation");
    run_rebuild(root, 8).expect("index seed markdown");
    Store::open(root).expect("open indexed store")
}

pub fn publish_pin(store: &mut Store, memo_id: &str, operation_id: &str) {
    let snap = store
        .get_memo_projection(memo_id)
        .expect("projection")
        .expect("memo");
    store
        .commit_saf_projection_mutation(&SafProjectionMutation {
            operation_id: operation_id.to_owned(),
            kind: SafProjectionMutationKind::Pin,
            memo_id: memo_id.to_owned(),
            expected_revision: snap.content_revision,
            expected_fingerprint: Some(snap.file_fingerprint),
            projection: None,
            trashed_at_ms: None,
            batch_targets: Vec::new(),
        })
        .expect("pin publication");
}

pub fn publish_trash(store: &mut Store, memo_id: &str, operation_id: &str) {
    let snap = store.get_memo(memo_id).expect("get").expect("memo");
    let projection = ScannedMemoProjection {
        memo_id: snap.summary.memo_id.clone(),
        source_path: snap.summary.source_path.clone(),
        file_fingerprint: snap.summary.file_fingerprint.clone(),
        chronology_epoch_ms: snap.summary.created_at_ms,
        body: snap.body.clone(),
        tags: snap.summary.tags.clone(),
        attachment_paths: snap.summary.image_urls.clone(),
        has_todo: snap.summary.has_todo,
        has_url: snap.summary.has_url,
        reminders: snap.summary.reminders.clone(),
    };
    store
        .commit_saf_projection_mutation(&SafProjectionMutation {
            operation_id: operation_id.to_owned(),
            kind: SafProjectionMutationKind::Delete,
            memo_id: memo_id.to_owned(),
            expected_revision: snap.summary.content_revision,
            expected_fingerprint: Some(snap.summary.file_fingerprint),
            projection: Some(projection),
            trashed_at_ms: Some(1_700_000_000_001),
            batch_targets: Vec::new(),
        })
        .expect("trash publication");
}
