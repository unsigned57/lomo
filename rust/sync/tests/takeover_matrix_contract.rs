//! Behavior Contract — P5-12 host takeover matrix deepen (Wave-11)
//!
//! - Unit under test: first-takeover / migration product scenarios on owner planner +
//!   store local snapshot bridge + identity fence + durable session revive
//! - Owning layer: `lomo-sync` decisions; `lomo-store` local expected-revision ports
//! - Priority tier: P0 (P5-12 host deepen; not real-provider takeover)
//! - Capability: product-shaped `FirstTakeover` / `Migration` rules — read-only preflight
//!   with hard `ensure_absent == 0` post-condition (symmetric for both session kinds),
//!   store-backed overlap / same-bytes / remote-only cases, durable fence revival +
//!   session re-open after process restart (hermetic files), forced RED on injected
//!   `EnsureAbsent`, optional plan-only → apply-with-verify for safe ensure-present.
//!
//! Scenarios:
//! - Given store local-only memo + empty remote, when `FirstTakeover` preflight, then
//!   `EnsurePresent` ≥ 1, `EnsureAbsent` 0, baseline not advanced, no publish.
//! - Given store local + remote same path different digests, when `FirstTakeover` plan,
//!   then `OpenConflict` ≥ 1 and `EnsureAbsent` 0.
//! - Given remote-only owned paths + empty store, when `FirstTakeover` plan, then
//!   `PullPresent` ≥ 1 and `EnsureAbsent` 0.
//! - Given complete remote listing with baseline path absent under `Migration` session,
//!   when planned, then `EnsureAbsent` 0 (migration class).
//! - Given durable session fence G1 vs current G2, when `assert_fence_for_revival` /
//!   `SyncIdentityFence::matches`, then `sync_identity_mismatch` and no clean slate.
//! - Given `FirstTakeover` with complete listing + empty remote, when
//!   `first_takeover_preflight`, then structural post-condition `ensure_absent` == 0.
//! - Given overlapping same-byte local/remote on `FirstTakeover`, when planned, then no
//!   `OpenConflict` and no `EnsureAbsent` (same-bytes establish later via verify).
//! - Given store-backed Migration preflight (local-only memo), when `migration_preflight`,
//!   then session kind `Migration`, `ensure_present` ≥ 1, `ensure_absent` 0, no publish.
//! - Given store `Migration` overlap (different digests), when planned, then `OpenConflict`
//!   and `ensure_absent` 0.
//! - Given store `Migration` same-bytes overlap, when planned, then no `OpenConflict` /
//!   `EnsureAbsent` / `EnsurePresent` / `PullPresent`.
//! - Given store empty + remote-only under `Migration`, when planned, then `PullPresent` ≥ 1
//!   and `ensure_absent` 0.
//! - Given injected `EnsureAbsent` under `FirstTakeover`, when
//!   `reject_if_migration_class_emitted_delete`, then `first_takeover_emitted_delete`.
//! - Given durable session + fence written, when process restarts (re-open `SyncPaths`) and
//!   fence matches, then session reloads and inspect/plan cycle succeeds; mismatch rejects.
//! - Given `FirstTakeover` plan-only ensure-present only, when apply-with-verify on hermetic
//!   fakes with body + verified receipt, then baseline advances for that path.
//!
//! Observable outcomes: intent counts, fence error codes, store path/digest facts,
//! preflight `ensure_absent` == 0, durable session bytes after restart, baseline advance.
//!
//! TDD proof: RED before `migration_preflight` / reject inject / durable revive coverage;
//! GREEN after.
//!
//! Excludes: real provider takeover GREEN, production DI, P5-13 cutover, arm64,
//! six-provider smoke, APK gate.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_core::{ErrorCategory, OperationId};
    use lomo_store::{MemoCommand, MemoCommandKind, Store, fingerprint_content};
    use lomo_sync::{
        BaselineHead, BatchAtomicity, ContentDigest, FakeLocalPort, FakeRemotePort, LocalPathEntry,
        LocalSnapshot, MapRemoteObjectSource, PathPublishStatus, PreparedRemoteBatch,
        ProviderNeutralIntent, PublishReceipt, RemotePathEntry, RemoteSnapshot, SessionKind,
        SnapshotCompleteness, StoreLocalSnapshotPort, SyncIdentityFence, SyncPath, SyncPaths,
        SyncSession, TombstoneSet, VerifiedRemoteState, VerifyStatus, apply_with_verify,
        assert_fence_for_revival, error_category, first_takeover_preflight,
        inspect_sync_cycle_plan, migration_preflight, plan_intents, read_session,
        reject_if_migration_class_emitted_delete, write_session,
    };
    use lomo_workspace::{RemoteDatasetId, RemoteIdentityDigest, WorkspaceGenerationId};
    use sha2::{Digest, Sha256};
    use tempfile::tempdir;

    fn dig(seed: u8) -> ContentDigest {
        ContentDigest::parse(&format!("{seed:02x}").repeat(32)).expect("digest")
    }

    fn path(raw: &str) -> SyncPath {
        SyncPath::parse(raw).expect("path")
    }

    fn fence_g1() -> SyncIdentityFence {
        SyncIdentityFence::from_parts(
            &WorkspaceGenerationId::parse(&"ab".repeat(32)).expect("gen"),
            &RemoteDatasetId::parse("ds").expect("ds"),
            &RemoteIdentityDigest::parse(&"cd".repeat(32)).expect("id"),
        )
    }

    fn fence_g2() -> SyncIdentityFence {
        SyncIdentityFence::from_parts(
            &WorkspaceGenerationId::parse(&"ef".repeat(32)).expect("gen2"),
            &RemoteDatasetId::parse("ds").expect("ds"),
            &RemoteIdentityDigest::parse(&"cd".repeat(32)).expect("id"),
        )
    }

    fn seed_store_memo(root: &std::path::Path, memo_id: &str, body: &str) -> Store {
        let mut store = Store::open(root).expect("open");
        store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse(&format!("op-{memo_id}")).expect("op"),
                    kind: MemoCommandKind::Create,
                    memo_id: memo_id.into(),
                    expected_revision: 0,
                    expected_fingerprint: None,
                    content: Some(body.into()),
                    tags: vec![],
                    pin: None,
                    pending_promotes: vec![],
                },
                None,
            )
            .expect("create");
        store
    }

    fn store_local_port(store: &Store) -> StoreLocalSnapshotPort {
        let snap = store.snapshot_sync_view().expect("snap");
        StoreLocalSnapshotPort::from_store_snapshot(
            &snap.workspace_generation,
            snap.entries
                .iter()
                .map(|e| (e.path.clone(), e.digest.clone())),
        )
        .expect("port")
    }

    fn body_digest(body: &[u8]) -> ContentDigest {
        let hex = format!("{:x}", Sha256::digest(body));
        ContentDigest::parse(&hex).expect("digest")
    }

    #[test]
    fn first_takeover_preflight_store_local_only_no_deletes_no_apply() {
        let temporary = tempdir().expect("temp");
        let store = seed_store_memo(temporary.path(), "take-local", "local-body");
        let local = store_local_port(&store);
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
            PublishReceipt {
                path_results: Vec::new(),
            },
            VerifiedRemoteState {
                results: Vec::new(),
            },
        );
        let (_session, result) =
            first_takeover_preflight(fence_g1(), "take-s1", &local, &remote).expect("preflight");
        assert_eq!(result.batch.ensure_absent_count(), 0);
        assert!(
            result.batch.ensure_present_count() >= 1,
            "store local-only must ensure present: {:?}",
            result.batch.intents
        );
        assert!(!result.baseline_advanced);
        assert!(result.receipt.is_none());
        assert_eq!(remote.publish_call_count(), 0);
        // Store path uses memos/{id}.md layout.
        assert!(
            result.batch.intents.iter().any(|i| matches!(
                i,
                ProviderNeutralIntent::EnsurePresent { path, .. }
                    if path.as_str() == "memos/take-local.md"
            )),
            "expected ensure present for store memo path: {:?}",
            result.batch.intents
        );
        assert_eq!(
            fingerprint_content("local-body"),
            store
                .snapshot_sync_view()
                .expect("snap")
                .entries
                .iter()
                .find(|e| e.path == "memos/take-local.md")
                .expect("entry")
                .digest
        );
    }

    #[test]
    fn first_takeover_unproven_overlap_opens_conflict_not_delete() {
        let temporary = tempdir().expect("temp");
        let store = seed_store_memo(temporary.path(), "overlap", "local-overlap");
        let local = store_local_port(&store);
        let local_snap = local.snapshot().expect("local");
        let local_digest = local_snap.entries.first().expect("entry").digest.clone();
        // Remote claims same store path with different digest (unproven overlap).
        let remote_snap = RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![RemotePathEntry {
                path: path("memos/overlap.md"),
                digest: dig(9),
                revision_token: "r-overlap".to_owned(),
            }],
        )
        .expect("snap");
        assert_ne!(local_digest.as_str(), dig(9).as_str());
        let batch = plan_intents(
            SessionKind::FirstTakeover,
            &local_snap,
            &remote_snap,
            &BaselineHead::empty(),
            &TombstoneSet::empty(),
        )
        .expect("plan");
        assert_eq!(batch.open_conflict_count(), 1);
        assert_eq!(batch.ensure_absent_count(), 0);
        assert_eq!(batch.ensure_present_count(), 0);
    }

    #[test]
    fn first_takeover_remote_only_pulls_without_delete() {
        let local = LocalSnapshot {
            entries: Vec::new(),
            workspace_generation: Some("ab".repeat(32)),
        };
        let remote_snap = RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![
                RemotePathEntry {
                    path: path("memo/remote-a.md"),
                    digest: dig(2),
                    revision_token: "ra".to_owned(),
                },
                RemotePathEntry {
                    path: path("memo/remote-b.md"),
                    digest: dig(3),
                    revision_token: "rb".to_owned(),
                },
            ],
        )
        .expect("snap");
        let batch = plan_intents(
            SessionKind::FirstTakeover,
            &local,
            &remote_snap,
            &BaselineHead::empty(),
            &TombstoneSet::empty(),
        )
        .expect("plan");
        assert_eq!(batch.pull_present_count(), 2);
        assert_eq!(batch.ensure_absent_count(), 0);
        assert_eq!(batch.open_conflict_count(), 0);
    }

    #[test]
    fn migration_session_never_emits_ensure_absent_with_complete_listing() {
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence_g1());
        baseline.upsert(&path("memo/a.md"), &dig(1), "tok-a".to_owned());
        let local = LocalSnapshot {
            entries: Vec::new(),
            workspace_generation: None,
        };
        let remote = RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap");
        // Structural type gate: Migration is migration-class (may_emit_user_file_delete = false).
        assert!(SessionKind::Migration.is_migration_or_takeover_class());
        assert!(!SessionKind::Migration.may_emit_user_file_delete());
        let batch = plan_intents(
            SessionKind::Migration,
            &local,
            &remote,
            &baseline,
            &TombstoneSet::empty(),
        )
        .expect("plan");
        assert_eq!(batch.ensure_absent_count(), 0);
    }

    #[test]
    fn identity_mismatch_fails_closed_without_clean_slate() {
        let durable = fence_g1();
        let current = fence_g2();
        let err = durable
            .matches(
                &WorkspaceGenerationId::parse(&current.workspace_generation).expect("gen"),
                &RemoteDatasetId::parse(&current.remote_dataset_id).expect("ds"),
                &RemoteIdentityDigest::parse(&current.remote_identity_digest).expect("id"),
            )
            .expect_err("mismatch");
        assert_eq!(error_category(&err), ErrorCategory::Validation);
        assert_eq!(err.code(), "sync_identity_mismatch");

        let err2 = assert_fence_for_revival(&durable, &current).expect_err("revival");
        assert_eq!(err2.code(), "sync_identity_mismatch");

        // Durable fence bytes unchanged (no clean slate).
        assert_eq!(durable.workspace_generation, "ab".repeat(32));
        assert_ne!(durable.workspace_generation, current.workspace_generation);
    }

    #[test]
    fn first_takeover_preflight_rejects_if_ensure_absent_leaked() {
        // Structural: first_takeover_preflight re-checks ensure_absent_count == 0 after plan.
        // With empty baseline + empty local + empty remote there is nothing to delete; this
        // locks the post-condition path stays zero under product-shaped empty remote.
        let local = FakeLocalPort {
            entries: Vec::new(),
        };
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
            PublishReceipt {
                path_results: Vec::new(),
            },
            VerifiedRemoteState {
                results: Vec::new(),
            },
        );
        let (session, result) =
            first_takeover_preflight(fence_g1(), "take-empty", &local, &remote).expect("ok");
        assert_eq!(session.kind, SessionKind::FirstTakeover);
        assert_eq!(result.batch.ensure_absent_count(), 0);
        assert!(!result.baseline_advanced);
    }

    #[test]
    fn first_takeover_same_bytes_overlap_is_noop_not_conflict_or_delete() {
        let body_digest = dig(4);
        let local = LocalSnapshot {
            entries: vec![LocalPathEntry {
                path: path("memo/same.md"),
                digest: body_digest.clone(),
            }],
            workspace_generation: None,
        };
        let remote = RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![RemotePathEntry {
                path: path("memo/same.md"),
                digest: body_digest,
                revision_token: "r-same".to_owned(),
            }],
        )
        .expect("snap");
        let batch = plan_intents(
            SessionKind::FirstTakeover,
            &local,
            &remote,
            &BaselineHead::empty(),
            &TombstoneSet::empty(),
        )
        .expect("plan");
        assert_eq!(batch.open_conflict_count(), 0);
        assert_eq!(batch.ensure_absent_count(), 0);
        assert_eq!(batch.ensure_present_count(), 0);
        assert_eq!(batch.pull_present_count(), 0);
    }

    #[test]
    fn migration_class_helpers_cover_first_takeover_and_migration_only() {
        assert!(SessionKind::FirstTakeover.is_migration_or_takeover_class());
        assert!(SessionKind::Migration.is_migration_or_takeover_class());
        assert!(!SessionKind::Incremental.is_migration_or_takeover_class());
        assert!(!SessionKind::FirstTakeover.may_emit_user_file_delete());
        assert!(!SessionKind::Migration.may_emit_user_file_delete());
        assert!(SessionKind::Incremental.may_emit_user_file_delete());
    }

    #[test]
    fn session_kind_migration_round_trips_in_durable_session() {
        let session =
            SyncSession::new(fence_g1(), SessionKind::Migration, "mig-1").expect("session");
        assert_eq!(session.kind, SessionKind::Migration);
        assert!(session.kind.is_migration_or_takeover_class());
    }

    // --- Wave-11 deepen ---

    #[test]
    fn migration_preflight_store_local_only_no_deletes_symmetric() {
        let temporary = tempdir().expect("temp");
        let store = seed_store_memo(temporary.path(), "mig-local", "migration-body");
        let local = store_local_port(&store);
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
            PublishReceipt {
                path_results: Vec::new(),
            },
            VerifiedRemoteState {
                results: Vec::new(),
            },
        );
        let (session, result) =
            migration_preflight(fence_g1(), "mig-s1", &local, &remote).expect("preflight");
        assert_eq!(session.kind, SessionKind::Migration);
        assert_eq!(result.batch.ensure_absent_count(), 0);
        assert!(
            result.batch.ensure_present_count() >= 1,
            "store local-only migration must ensure present: {:?}",
            result.batch.intents
        );
        assert!(!result.baseline_advanced);
        assert!(result.receipt.is_none());
        assert_eq!(remote.publish_call_count(), 0);
        assert!(
            result.batch.intents.iter().any(|i| matches!(
                i,
                ProviderNeutralIntent::EnsurePresent { path, .. }
                    if path.as_str() == "memos/mig-local.md"
            )),
            "expected ensure present for store memo path: {:?}",
            result.batch.intents
        );
    }

    #[test]
    fn migration_store_unproven_overlap_opens_conflict_not_delete() {
        let temporary = tempdir().expect("temp");
        let store = seed_store_memo(temporary.path(), "mig-overlap", "local-mig");
        let local = store_local_port(&store);
        let local_snap = local.snapshot().expect("local");
        let remote_snap = RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![RemotePathEntry {
                path: path("memos/mig-overlap.md"),
                digest: dig(8),
                revision_token: "r-mig".to_owned(),
            }],
        )
        .expect("snap");
        let batch = plan_intents(
            SessionKind::Migration,
            &local_snap,
            &remote_snap,
            &BaselineHead::empty(),
            &TombstoneSet::empty(),
        )
        .expect("plan");
        assert_eq!(batch.open_conflict_count(), 1);
        assert_eq!(batch.ensure_absent_count(), 0);
        assert_eq!(batch.ensure_present_count(), 0);
    }

    #[test]
    fn migration_store_same_bytes_overlap_is_noop() {
        let temporary = tempdir().expect("temp");
        let body = "same-mig-bytes";
        let store = seed_store_memo(temporary.path(), "mig-same", body);
        let local = store_local_port(&store);
        let local_snap = local.snapshot().expect("local");
        let digest = local_snap.entries.first().expect("entry").digest.clone();
        let remote_snap = RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![RemotePathEntry {
                path: path("memos/mig-same.md"),
                digest,
                revision_token: "r-same-mig".to_owned(),
            }],
        )
        .expect("snap");
        let batch = plan_intents(
            SessionKind::Migration,
            &local_snap,
            &remote_snap,
            &BaselineHead::empty(),
            &TombstoneSet::empty(),
        )
        .expect("plan");
        assert_eq!(batch.open_conflict_count(), 0);
        assert_eq!(batch.ensure_absent_count(), 0);
        assert_eq!(batch.ensure_present_count(), 0);
        assert_eq!(batch.pull_present_count(), 0);
    }

    #[test]
    fn migration_store_remote_only_pulls_without_delete() {
        let temporary = tempdir().expect("temp");
        // Empty store still supplies a generation fence via open.
        let store = Store::open(temporary.path()).expect("open empty");
        let local = store_local_port(&store);
        let local_snap = local.snapshot().expect("local");
        assert!(local_snap.entries.is_empty());
        let remote_snap = RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![RemotePathEntry {
                path: path("memo/remote-mig.md"),
                digest: dig(3),
                revision_token: "rm".to_owned(),
            }],
        )
        .expect("snap");
        let batch = plan_intents(
            SessionKind::Migration,
            &local_snap,
            &remote_snap,
            &BaselineHead::empty(),
            &TombstoneSet::empty(),
        )
        .expect("plan");
        assert_eq!(batch.pull_present_count(), 1);
        assert_eq!(batch.ensure_absent_count(), 0);
        assert_eq!(batch.open_conflict_count(), 0);
    }

    #[test]
    fn first_takeover_emitted_delete_rejects_injected_ensure_absent() {
        let batch = PreparedRemoteBatch::new(
            BatchAtomicity::PerPath,
            vec![ProviderNeutralIntent::EnsureAbsent {
                path: path("memo/leaked.md"),
                expected_remote_token: "tok-leak".to_owned(),
            }],
        )
        .expect("batch");
        let err = reject_if_migration_class_emitted_delete(SessionKind::FirstTakeover, &batch)
            .expect_err("must reject");
        assert_eq!(error_category(&err), ErrorCategory::Validation);
        assert_eq!(err.code(), "first_takeover_emitted_delete");

        let err_mig = reject_if_migration_class_emitted_delete(SessionKind::Migration, &batch)
            .expect_err("migration reject");
        assert_eq!(err_mig.code(), "migration_emitted_delete");

        // Incremental is not migration-class: inject is allowed through (delete gates live elsewhere).
        reject_if_migration_class_emitted_delete(SessionKind::Incremental, &batch)
            .expect("incremental ok");
    }

    #[test]
    fn durable_fence_and_session_revive_after_process_restart() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let paths = SyncPaths::for_workspace(&workspace);
        paths.ensure_layout().expect("layout");

        let session =
            SyncSession::new(fence_g1(), SessionKind::FirstTakeover, "revive-s1").expect("session");
        write_session(&paths, &session).expect("write session");

        // Simulate process restart: new SyncPaths on same root, re-read durable session.
        let paths_after = SyncPaths::for_workspace(&workspace);
        let loaded = read_session(&paths_after).expect("read after restart");
        assert_eq!(loaded.session_id, "revive-s1");
        assert_eq!(loaded.kind, SessionKind::FirstTakeover);
        assert_eq!(loaded.fence, fence_g1());

        // Matching fence allows revival; inspect cycle re-opens plan surface.
        assert_fence_for_revival(&loaded.fence, &fence_g1()).expect("match");
        let summary = inspect_sync_cycle_plan(&paths_after).expect("inspect");
        assert_eq!(summary.session_id, "revive-s1");
        assert_eq!(summary.session_kind, SessionKind::FirstTakeover);
        assert_eq!(summary.ensure_absent_count, 0);

        // Mismatched fence fails closed without clean slate (session file still present).
        let err = assert_fence_for_revival(&loaded.fence, &fence_g2()).expect_err("mismatch");
        assert_eq!(err.code(), "sync_identity_mismatch");
        assert!(paths_after.session.exists(), "session must not clean-slate");
        let still = read_session(&paths_after).expect("still durable");
        assert_eq!(still.fence, fence_g1());
    }

    #[test]
    fn first_takeover_plan_only_then_apply_with_verify_safe_ensure_present() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        paths.ensure_layout().expect("layout");

        let body = b"safe-ensure-present-body";
        let digest = body_digest(body);
        let local = FakeLocalPort {
            entries: vec![LocalPathEntry {
                path: path("memo/safe.md"),
                digest: digest.clone(),
            }],
        };
        // Plan-only preflight: empty remote, local-only → EnsurePresent, no delete.
        let remote_plan = FakeRemotePort::new(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
            PublishReceipt {
                path_results: Vec::new(),
            },
            VerifiedRemoteState {
                results: Vec::new(),
            },
        );
        let (session, preflight) =
            first_takeover_preflight(fence_g1(), "safe-apply", &local, &remote_plan)
                .expect("preflight");
        assert_eq!(preflight.batch.ensure_absent_count(), 0);
        assert!(preflight.batch.ensure_present_count() >= 1);
        assert!(!preflight.baseline_advanced);
        assert_eq!(remote_plan.publish_call_count(), 0);

        // Apply-with-verify on hermetic fakes: body-bound EnsurePresent + verified receipt.
        let objects =
            MapRemoteObjectSource::from_entries([("memo/safe.md".to_owned(), body.to_vec())]);
        let remote_apply = FakeRemotePort::with_objects(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
            PublishReceipt {
                path_results: vec![(
                    path("memo/safe.md"),
                    PathPublishStatus::Applied {
                        new_token: "tok-safe".to_owned(),
                    },
                )],
            },
            VerifiedRemoteState {
                results: vec![VerifyStatus::Verified {
                    path: path("memo/safe.md"),
                    digest: digest.clone(),
                    remote_token: "tok-safe".to_owned(),
                }],
            },
            objects,
        );
        let applied = apply_with_verify(
            &paths,
            &session,
            &local,
            &remote_apply,
            BaselineHead::empty(),
            None,
        )
        .expect("apply");
        assert_eq!(applied.batch.ensure_absent_count(), 0);
        assert!(applied.baseline_advanced);
        assert_eq!(
            applied
                .baseline
                .get("memo/safe.md")
                .map(|e| e.digest.as_str()),
            Some(digest.as_str())
        );
        assert_eq!(remote_apply.publish_call_count(), 1);
        assert_eq!(remote_apply.verify_call_count(), 1);
        // Durable session written by apply_with_verify.
        let durable = read_session(&paths).expect("session");
        assert_eq!(durable.session_id, "safe-apply");
        assert_eq!(durable.kind, SessionKind::FirstTakeover);
    }
}
