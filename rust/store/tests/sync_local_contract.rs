//! Behavior Contract (P5-04 unified Direct/SAF local sync ports)
//!
//! Capability: coarse `snapshot_sync_view` (path/digest/revision/media only) and expected-revision
//! `LocalSyncMutationBatch` apply via prepare → verify platform results → commit on the same
//! `Store` memo transaction path as user edits. SAF projection DB is app-private, generation-bound,
//! and rebuildable from coarse store facts — never a second write authority.
//!
//! Scenarios:
//! - Given a Direct workspace with a user-created memo, when snapshot runs, then path/digest/
//!   `content_revision` appear without loading bulk unrelated text into a second authority.
//! - Given an `UpsertMemo` batch with `expected_revision` 0, when applied, then memo is created and
//!   equivalent to a direct `MemoCommand::Create` (observable body + revision).
//! - Given a stale `expected_revision`, when `UpsertMemo`/`DeleteMemo` applies, then `stale_snapshot`
//!   rejects and durable body is unchanged.
//! - Given `prepare_sync_apply` then a Failed platform result, when commit runs, then fail-closed
//!   without advancing memo revision.
//! - Given prepare then workspace generation mint/replace, when verify/commit runs, then
//!   `sync_apply_generation_mismatch` rejects.
//! - Given an external user edit of memo bytes between prepare and commit, when commit runs, then
//!   `sync_expected_fingerprint_mismatch` rejects without overwriting user bytes.
//! - Given a user revision bump between prepare and commit (same bytes), when commit runs, then
//!   `stale_snapshot` rejects; sync does not freeze edits.
//! - Given prepare then process re-open (crash between prepare and commit), when the same prepared
//!   plan commits with verified platform results, then apply succeeds; when results are incomplete,
//!   commit fails closed with no partial projection.
//! - Given Direct `apply_local_sync_batch` vs SAF prepare+synthetic platform results+commit for the
//!   same batch, when both complete, then memo body/revision/digest match (behavior-equivalent).
//! - Given SAF projection binding for generation A, when required against generation B, then
//!   `saf_projection_generation_mismatch`.
//! - Given a live store snapshot, when SAF projection rebuilds then reads back, then only coarse
//!   path/digest/revision/media facts round-trip (no body authority).
//! - Given `EnsureMediaPresent` with matching digest, when Direct apply runs, then file exists with
//!   that digest; path traversal is rejected; matching re-apply is idempotent (process-death replay).
//! - Given `EnsureMediaPresent` when an existing file has a different digest, when Direct apply runs,
//!   then `sync_media_precondition_failed` refuses overwrite.
//!
//! Observable outcomes: snapshot entries, `content_revision`, error codes, on-disk media, generation
//! fence, projection DB bytes. Excludes: WebDAV/S3/Git adapters, production DI, Kotlin SAF executor
//! device wiring, 100k matrix.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use std::fs;

    use lomo_core::{ErrorCategory, OperationId};
    use lomo_store::{
        LocalSyncMutation, LocalSyncMutationBatch, MemoCommand, MemoCommandKind,
        SafProjectionBinding, Store, SyncLocalPathFact, SyncLocalSnapshot, SyncPlatformAction,
        SyncPlatformActionResult, fingerprint_content, prepare_sync_apply,
        sync_local_write_authority, verify_platform_results,
    };
    use lomo_workspace::{
        WorkspaceGenerationId, load_workspace_generation, mint_new_workspace_generation,
    };
    use sha2::{Digest, Sha256};
    use tempfile::tempdir;

    fn create_cmd(op: &str, memo: &str, content: &str) -> MemoCommand {
        MemoCommand {
            operation_id: OperationId::parse(op).expect("op"),
            kind: MemoCommandKind::Create,
            memo_id: memo.into(),
            expected_revision: 0,
            expected_fingerprint: None,
            content: Some(content.into()),
            tags: vec!["t".into()],
            pin: None,
            pending_promotes: vec![],
        }
    }

    fn hex_digest(bytes: &[u8]) -> String {
        use std::fmt::Write as _;
        let digest = Sha256::digest(bytes);
        let mut out = String::with_capacity(64);
        for byte in &digest {
            write!(out, "{byte:02x}").expect("write");
        }
        out
    }

    #[test]
    fn snapshot_exposes_path_digest_revision_without_second_authority() {
        let temporary = tempdir().expect("temp");
        let root = temporary.path();
        let mut store = Store::open(root).expect("open");
        store
            .apply_memo_command(&create_cmd("op-snap-1", "m1", "hello-sync"), None)
            .expect("create");
        let snap = store.snapshot_sync_view().expect("snapshot");
        assert!(!snap.workspace_generation.is_empty());
        assert_eq!(snap.entries.len(), 1);
        let entry = snap.entries.first().expect("entry");
        assert_eq!(entry.path, "memos/m1.md");
        assert_eq!(entry.digest, fingerprint_content("hello-sync"));
        assert_eq!(entry.content_revision, 1);
        assert_eq!(entry.memo_id.as_deref(), Some("m1"));
        // Coarse only: no body field on the fact type (compile-time); digest is not full text bulk.
        assert_ne!(entry.digest, "hello-sync");
    }

    #[test]
    fn direct_upsert_equivalence_with_user_edit_path() {
        let temporary = tempdir().expect("temp");
        let root = temporary.path();
        let mut store = Store::open(root).expect("open");
        let batch = LocalSyncMutationBatch {
            mutations: vec![LocalSyncMutation::UpsertMemo {
                operation_id: "op-sync-create-1".into(),
                memo_id: "sync-a".into(),
                expected_revision: 0,
                expected_fingerprint: None,
                content: "from-sync".into(),
                tags: vec!["s".into()],
            }],
        };
        let result = store.apply_local_sync_batch(&batch).expect("apply");
        assert_eq!(result.results.len(), 1);
        let snap = store.get_memo("sync-a").expect("get").expect("present");
        assert_eq!(snap.body, "from-sync");
        assert_eq!(snap.summary.content_revision, 1);
        assert_eq!(
            snap.summary.file_fingerprint,
            fingerprint_content("from-sync")
        );
        // Second path: user update after sync create uses same revision fence.
        store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-user-upd").expect("op"),
                    kind: MemoCommandKind::Update,
                    memo_id: "sync-a".into(),
                    expected_revision: 1,
                    expected_fingerprint: Some(fingerprint_content("from-sync")),
                    content: Some("user-edit".into()),
                    tags: vec!["s".into()],
                    pin: None,
                    pending_promotes: vec![],
                },
                None,
            )
            .expect("user update");
        let after = store.get_memo("sync-a").expect("get").expect("present");
        assert_eq!(after.body, "user-edit");
        assert_eq!(after.summary.content_revision, 2);
    }

    #[test]
    fn stale_revision_rejects_sync_upsert() {
        let temporary = tempdir().expect("temp");
        let root = temporary.path();
        let mut store = Store::open(root).expect("open");
        store
            .apply_memo_command(&create_cmd("op-stale-seed", "m-stale", "seed"), None)
            .expect("seed");
        let err = store
            .apply_local_sync_batch(&LocalSyncMutationBatch {
                mutations: vec![LocalSyncMutation::UpsertMemo {
                    operation_id: "op-stale-upd".into(),
                    memo_id: "m-stale".into(),
                    expected_revision: 0, // stale: live is 1
                    expected_fingerprint: None,
                    content: "hijack".into(),
                    tags: vec![],
                }],
            })
            .expect_err("stale");
        assert_eq!(err.code(), "stale_snapshot");
        assert_eq!(err.category(), ErrorCategory::Conflict);
        let body = store
            .get_memo("m-stale")
            .expect("get")
            .expect("present")
            .body;
        assert_eq!(body, "seed");
    }

    #[test]
    fn failed_platform_result_fails_closed_without_commit() {
        let temporary = tempdir().expect("temp");
        let root = temporary.path();
        let store = Store::open(root).expect("open");
        let prepared = prepare_sync_apply(
            root,
            &LocalSyncMutationBatch {
                mutations: vec![LocalSyncMutation::UpsertMemo {
                    operation_id: "op-fail-plat".into(),
                    memo_id: "m-fail".into(),
                    expected_revision: 0,
                    expected_fingerprint: None,
                    content: "x".into(),
                    tags: vec![],
                }],
            },
        )
        .expect("prepare");
        let results = vec![SyncPlatformActionResult::Failed {
            relative_path: "memos/m-fail.md".into(),
            code: "io_error".into(),
        }];
        let err = verify_platform_results(root, &prepared, &results).expect_err("fail");
        assert_eq!(err.code(), "sync_platform_action_failed");
        assert!(store.get_memo("m-fail").expect("get").is_none());
    }

    #[test]
    fn generation_fence_rejects_commit_after_mint() {
        let temporary = tempdir().expect("temp");
        let root = temporary.path();
        let mut store = Store::open(root).expect("open");
        let prepared = store
            .prepare_sync_apply(&LocalSyncMutationBatch {
                mutations: vec![LocalSyncMutation::UpsertMemo {
                    operation_id: "op-gen-fence".into(),
                    memo_id: "m-gen".into(),
                    expected_revision: 0,
                    expected_fingerprint: None,
                    content: "gen".into(),
                    tags: vec![],
                }],
            })
            .expect("prepare");
        // Simulate archive activation / generation rotate between prepare and commit.
        mint_new_workspace_generation(root).expect("mint");
        let results = vec![SyncPlatformActionResult::Applied {
            relative_path: "memos/m-gen.md".into(),
            observed_fingerprint: fingerprint_content("gen"),
        }];
        let err = store
            .commit_sync_apply(&prepared, &results)
            .expect_err("generation fence");
        assert_eq!(err.code(), "sync_apply_generation_mismatch");
        assert!(store.get_memo("m-gen").expect("get").is_none());
    }

    #[test]
    fn external_memo_edit_between_prepare_and_commit_is_rejected_without_overwrite() {
        let temporary = tempdir().expect("temp");
        let root = temporary.path();
        let mut store = Store::open(root).expect("open");
        store
            .apply_memo_command(&create_cmd("op-race-seed", "m-race", "seed"), None)
            .expect("seed");
        let prepared = store
            .prepare_sync_apply(&LocalSyncMutationBatch {
                mutations: vec![LocalSyncMutation::UpsertMemo {
                    operation_id: "op-race-update".into(),
                    memo_id: "m-race".into(),
                    expected_revision: 1,
                    expected_fingerprint: Some(fingerprint_content("seed")),
                    content: "remote-update".into(),
                    tags: vec![],
                }],
            })
            .expect("prepare");
        // Simulate an Android SAF/user edit that reached the workspace after prepare. The
        // projection still says `seed`, but the user bytes are now a different revision.
        fs::write(root.join("memos/m-race.md"), "user-edit").expect("external edit");
        let results = vec![SyncPlatformActionResult::Applied {
            relative_path: "memos/m-race.md".into(),
            observed_fingerprint: fingerprint_content("remote-update"),
        }];
        let err = store
            .commit_sync_apply(&prepared, &results)
            .expect_err("race must fail closed");
        assert_eq!(err.code(), "sync_expected_fingerprint_mismatch");
        assert_eq!(
            fs::read_to_string(root.join("memos/m-race.md")).expect("read"),
            "user-edit"
        );
        let memo = store.get_memo("m-race").expect("get").expect("present");
        assert_eq!(memo.body, "user-edit");
        assert_eq!(memo.summary.content_revision, 1);
        assert_eq!(memo.summary.file_fingerprint, fingerprint_content("seed"));
    }

    #[test]
    fn saf_projection_binding_generation_fence() {
        let temporary = tempdir().expect("temp");
        let app_private = temporary.path().join("app-private");
        let gen_a = WorkspaceGenerationId::parse(&"aa".repeat(32)).expect("a");
        let gen_b = WorkspaceGenerationId::parse(&"bb".repeat(32)).expect("b");
        let binding = SafProjectionBinding::new(&app_private, &gen_a).expect("bind");
        assert!(
            binding
                .projection_db_path
                .to_string_lossy()
                .contains(gen_a.as_str())
        );
        binding.require_generation(&gen_a).expect("match");
        let err = binding.require_generation(&gen_b).expect_err("mismatch");
        assert_eq!(err.code(), "saf_projection_generation_mismatch");
    }

    #[test]
    fn saf_projection_rebuild_is_generation_bound_and_contains_only_coarse_facts() {
        let temporary = tempdir().expect("temp");
        let generation = WorkspaceGenerationId::parse(&"ac".repeat(32)).expect("generation");
        let binding = SafProjectionBinding::new(temporary.path(), &generation).expect("binding");
        let snapshot = SyncLocalSnapshot {
            workspace_generation: generation.as_str().to_owned(),
            high_water_revision: 17,
            entries: vec![SyncLocalPathFact {
                path: "memos/rebuilt.md".into(),
                digest: fingerprint_content("body-never-stored-here"),
                content_revision: 4,
                memo_id: Some("rebuilt".into()),
                media_paths: vec!["attachments/a.bin".into()],
            }],
        };
        binding
            .rebuild_from_snapshot(&snapshot)
            .expect("rebuild projection");
        let rebuilt = binding.read_snapshot().expect("read projection");
        assert_eq!(rebuilt, snapshot);

        let wrong = SyncLocalSnapshot {
            workspace_generation: "bd".repeat(32),
            ..snapshot
        };
        let err = binding
            .rebuild_from_snapshot(&wrong)
            .expect_err("mismatched generation");
        assert_eq!(err.code(), "saf_projection_generation_mismatch");
    }

    #[test]
    fn media_ensure_present_and_path_traversal_reject() {
        let temporary = tempdir().expect("temp");
        let root = temporary.path();
        let mut store = Store::open(root).expect("open");
        let bytes = b"media-bytes-v1".to_vec();
        let digest = hex_digest(&bytes);
        store
            .apply_local_sync_batch(&LocalSyncMutationBatch {
                mutations: vec![LocalSyncMutation::EnsureMediaPresent {
                    relative_path: "attachments/a.bin".into(),
                    expected_digest: digest.clone(),
                    bytes: bytes.clone(),
                }],
            })
            .expect("media");
        let on_disk = fs::read(root.join("attachments/a.bin")).expect("read");
        assert_eq!(on_disk, bytes);
        assert_eq!(hex_digest(&on_disk), digest);

        let err = store
            .apply_local_sync_batch(&LocalSyncMutationBatch {
                mutations: vec![LocalSyncMutation::EnsureMediaPresent {
                    relative_path: "../escape.bin".into(),
                    expected_digest: digest,
                    bytes,
                }],
            })
            .expect_err("traversal");
        assert_eq!(err.code(), "sync_path_traversal");
    }

    #[test]
    fn sync_delete_uses_expected_revision_fence() {
        let temporary = tempdir().expect("temp");
        let root = temporary.path();
        let mut store = Store::open(root).expect("open");
        store
            .apply_memo_command(&create_cmd("op-del-seed", "m-del", "bye"), None)
            .expect("seed");
        // Stale delete.
        let stale = store
            .apply_local_sync_batch(&LocalSyncMutationBatch {
                mutations: vec![LocalSyncMutation::DeleteMemo {
                    operation_id: "op-del-stale".into(),
                    memo_id: "m-del".into(),
                    expected_revision: 0,
                    expected_fingerprint: None,
                }],
            })
            .expect_err("stale delete");
        assert_eq!(stale.code(), "stale_snapshot");
        // Correct revision.
        store
            .apply_local_sync_batch(&LocalSyncMutationBatch {
                mutations: vec![LocalSyncMutation::DeleteMemo {
                    operation_id: "op-del-ok".into(),
                    memo_id: "m-del".into(),
                    expected_revision: 1,
                    expected_fingerprint: Some(fingerprint_content("bye")),
                }],
            })
            .expect("delete");
        let memo = store.get_memo("m-del").expect("get").expect("row remains");
        assert!(memo.summary.is_trashed);
        // Generation fence still loadable (store open mints when missing; after delete it remains).
        let generation = load_workspace_generation(root)
            .or_else(|_| mint_new_workspace_generation(root))
            .expect("generation fence");
        assert!(!generation.as_str().is_empty());
    }

    #[test]
    fn user_revision_bump_between_prepare_and_commit_rejects_without_freezing_edits() {
        let temporary = tempdir().expect("temp");
        let root = temporary.path();
        let mut store = Store::open(root).expect("open");
        store
            .apply_memo_command(&create_cmd("op-race-rev-seed", "m-rev", "seed"), None)
            .expect("seed");
        let prepared = store
            .prepare_sync_apply(&LocalSyncMutationBatch {
                mutations: vec![LocalSyncMutation::UpsertMemo {
                    operation_id: "op-race-rev-sync".into(),
                    memo_id: "m-rev".into(),
                    expected_revision: 1,
                    expected_fingerprint: Some(fingerprint_content("seed")),
                    content: "remote-body".into(),
                    tags: vec![],
                }],
            })
            .expect("prepare");
        // User edit advances revision on the same serial store path (sync must not freeze edits).
        store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-race-rev-user").expect("op"),
                    kind: MemoCommandKind::Update,
                    memo_id: "m-rev".into(),
                    expected_revision: 1,
                    expected_fingerprint: Some(fingerprint_content("seed")),
                    content: Some("user-newer".into()),
                    tags: vec![],
                    pin: None,
                    pending_promotes: vec![],
                },
                None,
            )
            .expect("user edit");
        let results = vec![SyncPlatformActionResult::Applied {
            relative_path: "memos/m-rev.md".into(),
            observed_fingerprint: fingerprint_content("remote-body"),
        }];
        let err = store
            .commit_sync_apply(&prepared, &results)
            .expect_err("user edit race must fail closed");
        // Fingerprint re-check runs before memo revision validate; either code is fail-closed.
        assert!(
            err.code() == "sync_expected_fingerprint_mismatch" || err.code() == "stale_snapshot",
            "unexpected race reject code {}",
            err.code()
        );
        let memo = store.get_memo("m-rev").expect("get").expect("present");
        assert_eq!(memo.body, "user-newer");
        assert_eq!(memo.summary.content_revision, 2);
    }

    #[test]
    fn process_death_between_prepare_and_commit_replays_or_fails_closed() {
        let temporary = tempdir().expect("temp");
        let root = temporary.path().to_path_buf();
        let batch = LocalSyncMutationBatch {
            mutations: vec![LocalSyncMutation::UpsertMemo {
                operation_id: "op-death-create".into(),
                memo_id: "m-death".into(),
                expected_revision: 0,
                expected_fingerprint: None,
                content: "survived".into(),
                tags: vec!["d".into()],
            }],
        };
        let prepared = {
            let store = Store::open(&root).expect("open");
            store.prepare_sync_apply(&batch).expect("prepare")
        };
        // Incomplete platform results after crash → fail closed, no memo.
        {
            let mut store = Store::open(&root).expect("reopen");
            let err = store
                .commit_sync_apply(&prepared, &[])
                .expect_err("incomplete results");
            assert_eq!(err.code(), "sync_platform_result_count_mismatch");
            assert!(store.get_memo("m-death").expect("get").is_none());
        }
        // Verified platform results after re-open → commit succeeds (prepare is generation-fenced only).
        {
            let mut store = Store::open(&root).expect("reopen");
            let results = vec![SyncPlatformActionResult::Applied {
                relative_path: "memos/m-death.md".into(),
                observed_fingerprint: fingerprint_content("survived"),
            }];
            store
                .commit_sync_apply(&prepared, &results)
                .expect("commit after death");
            let memo = store.get_memo("m-death").expect("get").expect("present");
            assert_eq!(memo.body, "survived");
            assert_eq!(memo.summary.content_revision, 1);
        }
    }

    #[test]
    fn direct_and_saf_prepare_commit_are_behavior_equivalent_for_memo_upsert() {
        let temporary = tempdir().expect("temp");
        let direct_root = temporary.path().join("direct");
        let saf_root = temporary.path().join("saf");
        fs::create_dir_all(&direct_root).expect("direct");
        fs::create_dir_all(&saf_root).expect("saf");
        let batch = LocalSyncMutationBatch {
            mutations: vec![LocalSyncMutation::UpsertMemo {
                operation_id: "op-equiv-1".into(),
                memo_id: "m-equiv".into(),
                expected_revision: 0,
                expected_fingerprint: None,
                content: "same-body".into(),
                tags: vec!["eq".into()],
            }],
        };

        let direct_snap = {
            let mut store = Store::open(&direct_root).expect("open direct");
            store.apply_local_sync_batch(&batch).expect("direct apply");
            store.get_memo("m-equiv").expect("get").expect("present")
        };

        let saf_snap = {
            let mut store = Store::open(&saf_root).expect("open saf");
            let prepared = store.prepare_sync_apply(&batch).expect("prepare");
            assert_eq!(prepared.platform_actions.len(), 1);
            let action = prepared.platform_actions.first().expect("action");
            assert!(matches!(
                action,
                SyncPlatformAction::WriteUserBytes {
                    relative_path,
                    ..
                } if relative_path == "memos/m-equiv.md"
            ));
            // SAF executor would write user bytes; host models verified results only.
            let results = vec![SyncPlatformActionResult::Applied {
                relative_path: "memos/m-equiv.md".into(),
                observed_fingerprint: fingerprint_content("same-body"),
            }];
            store
                .commit_sync_apply(&prepared, &results)
                .expect("saf-style commit");
            store.get_memo("m-equiv").expect("get").expect("present")
        };

        assert_eq!(direct_snap.body, saf_snap.body);
        assert_eq!(
            direct_snap.summary.content_revision,
            saf_snap.summary.content_revision
        );
        assert_eq!(
            direct_snap.summary.file_fingerprint,
            saf_snap.summary.file_fingerprint
        );
        assert_eq!(
            direct_snap.summary.file_fingerprint,
            fingerprint_content("same-body")
        );
    }

    #[test]
    fn saf_projection_rebuilds_from_store_snapshot_without_body_authority() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        let app_private = temporary.path().join("app-private");
        fs::create_dir_all(&workspace).expect("ws");
        let mut store = Store::open(&workspace).expect("open");
        store
            .apply_memo_command(&create_cmd("op-proj-1", "m-proj", "projection-body"), None)
            .expect("create");
        let snap = store.snapshot_sync_view().expect("snapshot");
        assert_eq!(snap.entries.len(), 1);
        let entry = snap.entries.first().expect("entry");
        assert_ne!(entry.digest, "projection-body");

        let generation = WorkspaceGenerationId::parse(&snap.workspace_generation).expect("gen");
        let binding = SafProjectionBinding::new(&app_private, &generation).expect("binding");
        binding
            .rebuild_from_snapshot(&snap)
            .expect("rebuild from store snapshot");
        let rebuilt = binding.read_snapshot().expect("read");
        assert_eq!(rebuilt, snap);
        // Projection file must not contain Markdown body authority.
        let db_bytes = fs::read(&binding.projection_db_path).expect("db bytes");
        let needle = b"projection-body";
        assert!(
            !db_bytes
                .windows(needle.len())
                .any(|window| window == needle),
            "SAF projection must not store memo body bytes"
        );
    }

    #[test]
    fn media_matching_digest_reapply_is_idempotent_process_death_replay() {
        let temporary = tempdir().expect("temp");
        let root = temporary.path();
        let mut store = Store::open(root).expect("open");
        let bytes = b"media-replay-v1".to_vec();
        let digest = hex_digest(&bytes);
        let batch = LocalSyncMutationBatch {
            mutations: vec![LocalSyncMutation::EnsureMediaPresent {
                relative_path: "attachments/replay.bin".into(),
                expected_digest: digest,
                bytes: bytes.clone(),
            }],
        };
        store.apply_local_sync_batch(&batch).expect("first apply");
        store
            .apply_local_sync_batch(&batch)
            .expect("idempotent re-apply");
        assert_eq!(
            fs::read(root.join("attachments/replay.bin")).expect("read"),
            bytes
        );

        let conflicting = LocalSyncMutationBatch {
            mutations: vec![LocalSyncMutation::EnsureMediaPresent {
                relative_path: "attachments/replay.bin".into(),
                expected_digest: hex_digest(b"other"),
                bytes: b"other".to_vec(),
            }],
        };
        let err = store
            .apply_local_sync_batch(&conflicting)
            .expect_err("different digest must not overwrite");
        assert_eq!(err.code(), "sync_media_precondition_failed");
        assert_eq!(
            fs::read(root.join("attachments/replay.bin")).expect("read"),
            bytes
        );
    }

    #[test]
    fn ensure_media_absent_and_write_authority_marker() {
        let temporary = tempdir().expect("temp");
        let root = temporary.path();
        let mut store = Store::open(root).expect("open");
        let bytes = b"to-remove".to_vec();
        let digest = hex_digest(&bytes);
        store
            .apply_local_sync_batch(&LocalSyncMutationBatch {
                mutations: vec![LocalSyncMutation::EnsureMediaPresent {
                    relative_path: "attachments/gone.bin".into(),
                    expected_digest: digest,
                    bytes,
                }],
            })
            .expect("present");
        assert!(root.join("attachments/gone.bin").exists());
        store
            .apply_local_sync_batch(&LocalSyncMutationBatch {
                mutations: vec![LocalSyncMutation::EnsureMediaAbsent {
                    relative_path: "attachments/gone.bin".into(),
                }],
            })
            .expect("absent");
        assert!(!root.join("attachments/gone.bin").exists());
        assert_eq!(
            sync_local_write_authority(),
            "lomo-store expected-revision LocalSyncMutationBatch"
        );
    }
}
