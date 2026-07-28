//! Behavior Contract (P5-04 store local port wired into dark hermetic cycle)
//!
//! Capability: `Store::snapshot_sync_view` feeds `StoreLocalSnapshotPort` / `plan_intents`;
//! Direct `apply_local_sync_batch` is the sole local mutation path for pull materialization
//! (still dark; no production DI). Write authority marker and generation fence are locked.
//!
//! Scenarios:
//! - Given store memos, when snapshot is bridged into `LocalSyncPort`, then planner sees matching
//!   path/digest entries and generation fence.
//! - Given local-only memo + empty remote + first-takeover, when planned, then `EnsurePresent` and no
//!   `EnsureAbsent`.
//! - Given a store mutation batch with stale `expected_revision`, when pull materializes, then
//!   `stale_snapshot` and body unchanged.
//! - Given write authority marker, when inspected, then sole local authority is store expected-revision
//!   `LocalSyncMutationBatch`.
//!
//! Observable outcomes: plan intents, store body after apply, error codes, write-authority string.
//! Excludes: production DI, WebDAV/S3/Git, Kotlin SAF executor device wiring.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_core::OperationId;
    use lomo_store::{
        LocalSyncMutation, LocalSyncMutationBatch, MemoCommand, MemoCommandKind, Store,
        fingerprint_content,
    };
    use lomo_sync::{
        BaselineHead, SessionKind, SnapshotCompleteness, StoreLocalSnapshotPort, TombstoneSet,
        plan_intents,
    };
    use tempfile::tempdir;

    #[test]
    fn store_snapshot_bridges_into_planner_local_port() {
        let temporary = tempdir().expect("temp");
        let root = temporary.path();
        let mut store = Store::open(root).expect("open");
        store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-bridge-1").expect("op"),
                    kind: MemoCommandKind::Create,
                    memo_id: "bridge".into(),
                    expected_revision: 0,
                    expected_fingerprint: None,
                    content: Some("bridge-body".into()),
                    tags: vec![],
                    pin: None,
                    pending_promotes: vec![],
                },
                None,
            )
            .expect("create");
        let snap = store.snapshot_sync_view().expect("snap");
        let port = StoreLocalSnapshotPort::from_store_snapshot(
            &snap.workspace_generation,
            snap.entries
                .iter()
                .map(|e| (e.path.clone(), e.digest.clone())),
        )
        .expect("port");
        let local = port.snapshot().expect("local");
        assert_eq!(local.entries.len(), 1);
        let entry = local.entries.first().expect("entry");
        assert_eq!(entry.path.as_str(), "memos/bridge.md");
        assert_eq!(entry.digest.as_str(), fingerprint_content("bridge-body"));
        assert_eq!(
            local.workspace_generation.as_deref(),
            Some(snap.workspace_generation.as_str())
        );

        let remote = lomo_sync::RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new())
            .expect("remote");
        let batch = plan_intents(
            SessionKind::FirstTakeover,
            &local,
            &remote,
            &BaselineHead::empty(),
            &TombstoneSet::empty(),
        )
        .expect("plan");
        assert_eq!(batch.ensure_absent_count(), 0);
        assert!(batch.ensure_present_count() >= 1);
    }

    #[test]
    fn hermetic_pull_materializes_via_store_mutation_batch() {
        let temporary = tempdir().expect("temp");
        let root = temporary.path();
        let mut store = Store::open(root).expect("open");
        // Simulate pull of remote body into local store through the sole mutation batch path.
        store
            .apply_local_sync_batch(&LocalSyncMutationBatch {
                mutations: vec![LocalSyncMutation::UpsertMemo {
                    operation_id: "op-pull-1".into(),
                    memo_id: "pulled".into(),
                    expected_revision: 0,
                    expected_fingerprint: None,
                    content: "from-remote".into(),
                    tags: vec!["r".into()],
                }],
            })
            .expect("pull apply");
        let memo = store.get_memo("pulled").expect("get").expect("present");
        assert_eq!(memo.body, "from-remote");
        let snap = store.snapshot_sync_view().expect("snap");
        assert!(
            snap.entries
                .iter()
                .any(|e| e.path == "memos/pulled.md"
                    && e.digest == fingerprint_content("from-remote"))
        );
    }

    #[test]
    fn stale_store_mutation_batch_fails_closed_on_pull_path() {
        let temporary = tempdir().expect("temp");
        let root = temporary.path();
        let mut store = Store::open(root).expect("open");
        store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-stale-seed").expect("op"),
                    kind: MemoCommandKind::Create,
                    memo_id: "stale-pull".into(),
                    expected_revision: 0,
                    expected_fingerprint: None,
                    content: Some("seed".into()),
                    tags: vec![],
                    pin: None,
                    pending_promotes: vec![],
                },
                None,
            )
            .expect("seed");
        let err = store
            .apply_local_sync_batch(&LocalSyncMutationBatch {
                mutations: vec![LocalSyncMutation::UpsertMemo {
                    operation_id: "op-stale-pull".into(),
                    memo_id: "stale-pull".into(),
                    expected_revision: 0,
                    expected_fingerprint: None,
                    content: "hijack".into(),
                    tags: vec![],
                }],
            })
            .expect_err("stale");
        assert_eq!(err.code(), "stale_snapshot");
        assert_eq!(
            store
                .get_memo("stale-pull")
                .expect("get")
                .expect("present")
                .body,
            "seed"
        );
    }

    #[test]
    fn write_authority_marker_is_store_expected_revision_batch() {
        assert_eq!(
            lomo_store::sync_local_write_authority(),
            "lomo-store expected-revision LocalSyncMutationBatch"
        );
    }
}
