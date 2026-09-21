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
    LomoPaths, LomoPayload, LomoRecordKind, SafProjectionMutation, SafProjectionMutationKind,
    ScannedMemoProjection, StateBody, Store, run_rebuild, write_record_atomic,
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

pub fn seed_state(root: &Path, memo: &str, pinned: bool, trashed: bool) {
    let paths = LomoPaths::for_workspace(root);
    paths.ensure_layout().expect("layout");
    let body = StateBody {
        memo_id: memo.to_owned(),
        pinned,
        trashed,
        pinned_at_ms: pinned.then_some(1_700_000_000_000),
        trashed_at_ms: trashed.then_some(1_700_000_000_001),
        tags: Vec::new(),
    };
    let body_json = serde_json::to_string(&body).expect("state json");
    write_record_atomic(
        &paths.state.join(format!("{memo}.rec")),
        &LomoPayload {
            kind: LomoRecordKind::State,
            record_id: memo.to_owned(),
            body_json,
        },
    )
    .expect("write state");
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
        })
        .expect("trash publication");
}
