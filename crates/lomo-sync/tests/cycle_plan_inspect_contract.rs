//! Behavior Contract — P5-09 Wave-8/9 dark cycle plan inspect (host hermetic)
//!
//! - Unit under test: `inspect_sync_cycle_plan` + `inspect_sync_cycle_plan_with_ports` in
//!   `lomo-sync`
//! - Owning layer: `lomo-sync` (sole planner owner); conversion FFI maps empty-port inspect only
//! - Priority tier: P0
//! - Capability: coarse plan/readiness entry that loads durable session/baseline and runs an owner
//!   cycle against hermetic ports; residual deepen accepts **real local/remote snapshots** under
//!   fakes so disposition is not always `after_user_action` when verify/precondition fails.
//!
//! Scenarios:
//! - Given no durable session, when inspect runs, then `sync_session_missing` validation.
//! - Given a durable incremental session with empty baseline, when empty-port inspect runs, then
//!   idle counts, `after_user_action` disposition, and session identity round-trip.
//! - Given a durable conflict session with one open path, when inspect runs, then
//!   `open_conflict_paths` is 1 and disposition remains `after_user_action`.
//! - Given first-takeover session kind, when inspect runs, then session kind is preserved.
//! - Given real local/remote snapshots (local-only memo), when plan-only with ports runs, then
//!   `ensure_present_count` ≥ 1 and disposition `after_user_action`.
//! - Given both-modified digests under ports (plan-only), when inspect runs, then
//!   `open_conflict_count` ≥ 1 and disposition `after_user_action`.
//! - Given both-modified with local workspace file + remote object bytes, when inspect-with-ports
//!   runs **without** an injected `ConflictBodySource`, then the cycle loads those bytes and
//!   durable-opens the conflict (production body path; never constant `None`).
//! - Given a durable `KeepLocal` resolution, when inspect-with-ports runs with `apply_remote`, then
//!   the remote port publishes the local candidate body (production `apply_resolved_conflicts_remote`).
//! - Given both-modified listing facts but no workspace file and no remote object, when inspect
//!   runs without injected bodies, then `conflict_candidate_body_missing` (hollow still fail-closed).
//! - Given apply with `PreconditionFailed` under ports, when inspect-with-apply runs, then
//!   disposition `transient` (replan) and baseline not advanced.
//! - Given apply with verify failure under ports, when inspect-with-apply runs, then disposition
//!   `transient` and baseline not advanced.
//! - Given a store-backed workspace + hermetic backend, when `run_composed_sync_cycle` runs, then
//!   real local store port yields `ensure_present` ≥ 1 (not empty-port inspect).
//! - Given `WebDAV` config without secret, when `run_composed_sync_cycle` runs, then fail-closed
//!   `webdav_secret_required`.
//! - Given Git backend kind without a remote port, when `run_composed_sync_cycle` runs, then
//!   `sync_git_compose_via_remote_port` (Git is composed at the native edge).
//! - Given a store-backed workspace + hermetic bare Git remote port, when
//!   `run_composed_sync_cycle_with_remote_port` runs plan-only, then `ensure_present` ≥ 1.
//! - Given the same Git composition with `apply_remote`, when the cycle runs, then publish
//!   succeeds (planner `WholeBatchRef` + tip CAS) and the remote listing observes the path.
//!
//! Observable outcomes: `SyncCyclePlanSummary` fields; structured error codes; meaningful
//! disposition under real fake snapshots; durable conflict artifacts when workspace/remote bodies
//! exist without an injected `ConflictBodySource`.
//! TDD proof: RED `with_ports_both_modified_loads_workspace_and_remote_bodies_without_injection`
//! failed `inspect auto-load bodies` with `conflict_candidate_body_missing` (materialize required
//! an injected candidate body source); GREEN same command after `RemoteSyncPort::load_object` +
//! workspace auto-load (then 15 passed). RED `with_ports_apply_remote_publishes_keep_local_from_durable_session`
//! failed `apply cycle` with `fake_remote_object_source_digest_mismatch` until pending `KeepLocal`
//! apply ran before plan and existing sessions were not rematerialized; GREEN same command.
//! Excludes: real provider publish/apply, production DI, `BoltFFI` wire (native contract), Kotlin
//! planner re-implementation, multi-process death. `KeepRemote` `WorkspaceSession` local pull is
//! owned by `lomo-native` `sync_session_keep_remote_contract`.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_store::{Store, run_rebuild};
    use lomo_sync::{
        BaselineHead, ConflictBodySource, ConflictResolution, ConflictSession, ContentDigest,
        FakeLocalPort, FakeRemotePort, LocalPathEntry, MapRemoteObjectSource, PathPublishStatus,
        PublishReceipt, RemoteDigestFact, RemotePathEntry, RemoteSnapshot, RemoteValidator,
        SessionKind, SnapshotCompleteness, SyncBackendConfig, SyncIdentityFence, SyncPath,
        SyncPaths, SyncSession, VerifiedRemoteState, VerifyStatus, collect_resolved_present_bodies,
        conflict_path_from_open, inspect_sync_cycle_plan, inspect_sync_cycle_plan_with_ports,
        read_conflict_session, resolve_sync_conflicts, run_composed_sync_cycle, write_baseline,
        write_conflict_session, write_session,
    };
    use lomo_workspace::{RemoteDatasetId, RemoteIdentityDigest, WorkspaceGenerationId};
    use tempfile::tempdir;

    fn dig(seed: u8) -> ContentDigest {
        ContentDigest::parse(&format!("{seed:02x}").repeat(32)).expect("digest")
    }

    fn path(raw: &str) -> SyncPath {
        SyncPath::parse(raw).expect("path")
    }

    fn fence() -> SyncIdentityFence {
        SyncIdentityFence::from_parts(
            &WorkspaceGenerationId::parse(&"ab".repeat(32)).expect("gen"),
            &RemoteDatasetId::parse("ds").expect("ds"),
            &RemoteIdentityDigest::parse(&"cd".repeat(32)).expect("id"),
        )
    }

    #[test]
    fn missing_session_fails_closed_validation() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let paths = SyncPaths::for_workspace(&workspace);

        let err = inspect_sync_cycle_plan(&paths).expect_err("missing session");
        assert_eq!(err.code(), "sync_session_missing");
        assert_eq!(err.category(), lomo_core::ErrorCategory::Validation);
    }

    #[test]
    fn idle_incremental_session_reports_after_user_action() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let paths = SyncPaths::for_workspace(&workspace);
        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "cycle-idle-1").expect("session");
        write_session(&paths, &session).expect("write session");

        let summary = inspect_sync_cycle_plan(&paths).expect("inspect");
        assert_eq!(summary.session_id, "cycle-idle-1");
        assert_eq!(summary.session_kind, SessionKind::Incremental);
        assert_eq!(summary.session_revision, 1);
        assert!(!summary.baseline_established);
        assert_eq!(summary.ensure_present_count, 0);
        assert_eq!(summary.ensure_absent_count, 0);
        assert_eq!(summary.pull_present_count, 0);
        assert_eq!(summary.open_conflict_count, 0);
        assert_eq!(summary.open_conflict_paths, 0);
        assert!(summary.conflict_revision.is_none());
        assert_eq!(summary.retry_disposition, "after_user_action");
    }

    #[test]
    fn open_conflict_paths_surface_on_durable_conflict_session() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let paths = SyncPaths::for_workspace(&workspace);
        let session = SyncSession::new(fence(), SessionKind::Incremental, "cycle-conflict-1")
            .expect("session");
        write_session(&paths, &session).expect("write session");

        let record = conflict_path_from_open(
            &path("memo/a.md"),
            Some(&dig(1)),
            Some(&dig(2)),
            Some(&dig(0)),
            Some("tok-x"),
        )
        .expect("record");
        let conflict =
            ConflictSession::open(fence(), "conflict-head-1", vec![record]).expect("open");
        write_conflict_session(&paths, &conflict).expect("write conflict");

        let summary = inspect_sync_cycle_plan(&paths).expect("inspect");
        assert_eq!(summary.session_id, "cycle-conflict-1");
        assert_eq!(summary.open_conflict_paths, 1);
        assert_eq!(summary.conflict_revision, Some(1));
        assert_eq!(summary.retry_disposition, "after_user_action");
    }

    #[test]
    fn first_takeover_session_kind_is_preserved() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let paths = SyncPaths::for_workspace(&workspace);
        let session =
            SyncSession::new(fence(), SessionKind::FirstTakeover, "cycle-ft-1").expect("session");
        write_session(&paths, &session).expect("write session");

        let summary = inspect_sync_cycle_plan(&paths).expect("inspect");
        assert_eq!(summary.session_kind, SessionKind::FirstTakeover);
        assert_eq!(summary.session_id, "cycle-ft-1");
        assert_eq!(summary.ensure_absent_count, 0);
        assert_eq!(summary.retry_disposition, "after_user_action");
    }

    #[test]
    fn with_ports_plan_only_local_only_reports_ensure_present() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let paths = SyncPaths::for_workspace(&workspace);
        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "cycle-ports-1").expect("session");
        write_session(&paths, &session).expect("write session");

        let local = FakeLocalPort {
            entries: vec![LocalPathEntry {
                path: path("memo/local-only.md"),
                digest: dig(4),
            }],
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

        let summary = inspect_sync_cycle_plan_with_ports(&paths, &local, &remote, false, None)
            .expect("inspect");
        assert!(
            summary.ensure_present_count >= 1,
            "local-only must plan EnsurePresent: {summary:?}"
        );
        assert_eq!(summary.ensure_absent_count, 0);
        assert_eq!(summary.retry_disposition, "after_user_action");
        // Plan-only never advances baseline.
        assert!(!summary.baseline_established);
    }

    #[test]
    fn with_ports_plan_only_both_modified_opens_conflict_disposition() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let paths = SyncPaths::for_workspace(&workspace);
        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "cycle-ports-cf").expect("session");
        write_session(&paths, &session).expect("write session");

        let local_bytes = b"# local both-mod\n";
        let remote_bytes = b"# remote both-mod\n";
        let base_bytes = b"# base both-mod\n";
        let d_local = ContentDigest::from_bytes(local_bytes);
        let d_remote = ContentDigest::from_bytes(remote_bytes);
        let d_base = ContentDigest::from_bytes(base_bytes);

        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/a.md"), &d_base, "tok-base".to_owned());
        write_baseline(&paths, &baseline).expect("baseline");

        let local = FakeLocalPort {
            entries: vec![LocalPathEntry {
                path: path("memo/a.md"),
                digest: d_local,
            }],
        };
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(
                SnapshotCompleteness::Complete,
                vec![RemotePathEntry {
                    path: path("memo/a.md"),
                    digest: RemoteDigestFact::Known(d_remote),
                    validator: RemoteValidator::Strong("tok-remote".to_owned()),
                }],
            )
            .expect("snap"),
            PublishReceipt {
                path_results: Vec::new(),
            },
            VerifiedRemoteState {
                results: Vec::new(),
            },
        );
        let bodies = ConflictBodySource::from_entries([(
            "memo/a.md",
            Some(local_bytes.to_vec()),
            Some(remote_bytes.to_vec()),
            Some(base_bytes.to_vec()),
        )]);

        let summary =
            inspect_sync_cycle_plan_with_ports(&paths, &local, &remote, false, Some(&bodies))
                .expect("inspect");
        assert!(
            summary.open_conflict_count >= 1,
            "both-modified must open conflict: {summary:?}"
        );
        assert!(summary.open_conflict_paths >= 1);
        assert_eq!(summary.retry_disposition, "after_user_action");
        // Pre-seeded baseline remains established; open conflict holds advance for that path.
        assert!(summary.baseline_established);
    }

    #[test]
    fn with_ports_both_modified_loads_workspace_and_remote_bodies_without_injection() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(workspace.join("memo")).expect("ws");
        let paths = SyncPaths::for_workspace(&workspace);
        let session = SyncSession::new(fence(), SessionKind::Incremental, "cycle-ports-auto")
            .expect("session");
        write_session(&paths, &session).expect("write session");

        let local_bytes = b"# local auto-load\n";
        let remote_bytes = b"# remote auto-load\n";
        let base_bytes = b"# base auto-load\n";
        let d_local = ContentDigest::from_bytes(local_bytes);
        let d_remote = ContentDigest::from_bytes(remote_bytes);
        let d_base = ContentDigest::from_bytes(base_bytes);
        std::fs::write(workspace.join("memo/a.md"), local_bytes).expect("local file");

        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/a.md"), &d_base, "tok-base".to_owned());
        write_baseline(&paths, &baseline).expect("baseline");

        let local = FakeLocalPort {
            entries: vec![LocalPathEntry {
                path: path("memo/a.md"),
                digest: d_local.clone(),
            }],
        };
        let mut objects = MapRemoteObjectSource::empty();
        objects.insert("memo/a.md", remote_bytes.to_vec());
        let remote = FakeRemotePort::with_objects(
            RemoteSnapshot::new(
                SnapshotCompleteness::Complete,
                vec![RemotePathEntry {
                    path: path("memo/a.md"),
                    digest: RemoteDigestFact::Known(d_remote.clone()),
                    validator: RemoteValidator::Strong("tok-remote".to_owned()),
                }],
            )
            .expect("snap"),
            PublishReceipt {
                path_results: Vec::new(),
            },
            VerifiedRemoteState {
                results: Vec::new(),
            },
            objects,
        );

        let summary = inspect_sync_cycle_plan_with_ports(&paths, &local, &remote, false, None)
            .expect("inspect auto-load bodies");
        assert!(
            summary.open_conflict_count >= 1,
            "production body load must open conflict: {summary:?}"
        );
        assert!(summary.open_conflict_paths >= 1);
        let session = read_conflict_session(&paths).expect("durable session");
        let record = session
            .paths
            .iter()
            .find(|item| item.path == "memo/a.md")
            .expect("conflict path");
        assert_eq!(record.local_digest.as_deref(), Some(d_local.as_str()));
        assert_eq!(record.remote_digest.as_deref(), Some(d_remote.as_str()));
        assert!(record.local_artifact_ref.is_some());
        assert!(record.remote_artifact_ref.is_some());
        assert_eq!(record.remote_token.as_deref(), Some("tok-remote"));
    }

    #[test]
    fn with_ports_apply_remote_publishes_keep_local_from_durable_session() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(workspace.join("memo")).expect("ws");
        let paths = SyncPaths::for_workspace(&workspace);
        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "cycle-apply-kl").expect("session");
        write_session(&paths, &session).expect("write session");

        let local_bytes = b"# keep local apply\n";
        let remote_bytes = b"# remote losing\n";
        let base_bytes = b"# base apply\n";
        let d_local = ContentDigest::from_bytes(local_bytes);
        let d_remote = ContentDigest::from_bytes(remote_bytes);
        let d_base = ContentDigest::from_bytes(base_bytes);
        std::fs::write(workspace.join("memo/a.md"), local_bytes).expect("local file");

        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/a.md"), &d_base, "tok-base".to_owned());
        write_baseline(&paths, &baseline).expect("baseline");

        let local = FakeLocalPort {
            entries: vec![LocalPathEntry {
                path: path("memo/a.md"),
                digest: d_local.clone(),
            }],
        };
        let listing = RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![RemotePathEntry {
                path: path("memo/a.md"),
                digest: RemoteDigestFact::Known(d_remote),
                validator: RemoteValidator::Strong("tok-remote".to_owned()),
            }],
        )
        .expect("snap");
        let mut remote_objects = MapRemoteObjectSource::empty();
        remote_objects.insert("memo/a.md", remote_bytes.to_vec());
        let open_remote = FakeRemotePort::with_objects(
            listing.clone(),
            PublishReceipt {
                path_results: Vec::new(),
            },
            VerifiedRemoteState {
                results: Vec::new(),
            },
            remote_objects,
        );
        inspect_sync_cycle_plan_with_ports(&paths, &local, &open_remote, false, None)
            .expect("materialize");
        resolve_sync_conflicts(
            &paths,
            1,
            &[ConflictResolution::KeepLocal {
                path: "memo/a.md".to_owned(),
            }],
        )
        .expect("resolve KeepLocal");

        let apply_objects = collect_resolved_present_bodies(
            &paths,
            &read_conflict_session(&paths).expect("session"),
        )
        .expect("artifact bodies");
        let apply_remote = FakeRemotePort::with_objects(
            listing,
            PublishReceipt {
                path_results: vec![(
                    path("memo/a.md"),
                    PathPublishStatus::Applied {
                        new_token: "tok-new".to_owned(),
                    },
                )],
            },
            VerifiedRemoteState {
                results: vec![VerifyStatus::Verified {
                    path: path("memo/a.md"),
                    digest: d_local.clone(),
                    remote_token: "tok-new".to_owned(),
                }],
            },
            apply_objects,
        );
        inspect_sync_cycle_plan_with_ports(&paths, &local, &apply_remote, true, None)
            .expect("apply cycle");
        assert_eq!(apply_remote.publish_call_count(), 1);
        let published = apply_remote.published_bodies();
        let first = published.first().expect("published KeepLocal body");
        assert_eq!(first.path, "memo/a.md");
        assert_eq!(first.digest, d_local.as_str());
        assert_eq!(first.body, local_bytes);
    }

    #[test]
    fn with_ports_hollow_open_without_bodies_fails_closed() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let paths = SyncPaths::for_workspace(&workspace);
        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "cycle-hollow").expect("session");
        write_session(&paths, &session).expect("write session");

        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/a.md"), &dig(0), "tok-base".to_owned());
        write_baseline(&paths, &baseline).expect("baseline");

        let local = FakeLocalPort {
            entries: vec![LocalPathEntry {
                path: path("memo/a.md"),
                digest: dig(1),
            }],
        };
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(
                SnapshotCompleteness::Complete,
                vec![RemotePathEntry {
                    path: path("memo/a.md"),
                    digest: RemoteDigestFact::Known(dig(2)),
                    validator: RemoteValidator::Strong("tok-remote".to_owned()),
                }],
            )
            .expect("snap"),
            PublishReceipt {
                path_results: Vec::new(),
            },
            VerifiedRemoteState {
                results: Vec::new(),
            },
        );

        let err = inspect_sync_cycle_plan_with_ports(&paths, &local, &remote, false, None)
            .expect_err("hollow open");
        assert_eq!(err.code(), "conflict_candidate_body_missing");
    }

    #[test]
    fn with_ports_apply_precondition_failed_is_transient_replan() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let paths = SyncPaths::for_workspace(&workspace);
        let session = SyncSession::new(fence(), SessionKind::Incremental, "cycle-ports-412")
            .expect("session");
        write_session(&paths, &session).expect("write session");

        let local = FakeLocalPort {
            entries: vec![LocalPathEntry {
                path: path("memo/a.md"),
                digest: dig(7),
            }],
        };
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
            PublishReceipt {
                path_results: vec![(path("memo/a.md"), PathPublishStatus::PreconditionFailed)],
            },
            VerifiedRemoteState {
                results: Vec::new(),
            },
        );

        let summary = inspect_sync_cycle_plan_with_ports(&paths, &local, &remote, true, None)
            .expect("inspect");
        assert_eq!(summary.retry_disposition, "transient");
        // PreconditionFailed must not invent baseline establishment.
        assert!(!summary.baseline_established);
        assert_eq!(summary.open_conflict_count, 0);
    }

    /// Given an apply cycle whose remote reports no conditional-write capability, when
    /// the plan emits `EnsurePresent`, then publish fails closed with
    /// `remote_capability_unsupported` — capability facts participate in the cycle;
    /// conditional writes never rely on server leniency.
    #[test]
    fn with_ports_apply_refuses_mutation_when_capability_unsupported() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let paths = SyncPaths::for_workspace(&workspace);
        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "cycle-caps-1").expect("session");
        write_session(&paths, &session).expect("write session");

        let local = FakeLocalPort {
            entries: vec![LocalPathEntry {
                path: path("memo/a.md"),
                digest: dig(7),
            }],
        };
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
            PublishReceipt {
                path_results: Vec::new(),
            },
            VerifiedRemoteState {
                results: Vec::new(),
            },
        )
        .with_capabilities(lomo_sync::RemoteCapabilities::default());

        // Plan-only inspect does not mutate: no capability gate fires.
        let summary = inspect_sync_cycle_plan_with_ports(&paths, &local, &remote, false, None)
            .expect("plan-only inspect");
        assert_eq!(summary.ensure_present_count, 1);

        let err = inspect_sync_cycle_plan_with_ports(&paths, &local, &remote, true, None)
            .expect_err("capability refusal");
        assert_eq!(err.code(), "remote_capability_unsupported");
        assert_eq!(remote.publish_call_count(), 0);
    }

    #[test]
    fn with_ports_apply_verify_failure_is_transient() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let paths = SyncPaths::for_workspace(&workspace);
        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "cycle-ports-vf").expect("session");
        write_session(&paths, &session).expect("write session");

        let local = FakeLocalPort {
            entries: vec![LocalPathEntry {
                path: path("memo/a.md"),
                digest: dig(8),
            }],
        };
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
            PublishReceipt {
                path_results: vec![(
                    path("memo/a.md"),
                    PathPublishStatus::Applied {
                        new_token: "n-vf".to_owned(),
                    },
                )],
            },
            VerifiedRemoteState {
                results: vec![VerifyStatus::Failed {
                    path: path("memo/a.md"),
                    code: "digest_mismatch".to_owned(),
                }],
            },
        );

        let summary = inspect_sync_cycle_plan_with_ports(&paths, &local, &remote, true, None)
            .expect("inspect");
        assert_eq!(summary.retry_disposition, "transient");
        assert!(!summary.baseline_established);
    }

    #[test]
    fn composed_hermetic_cycle_uses_store_local_port_not_empty_inspect() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws-composed");
        std::fs::create_dir_all(&workspace).expect("ws");
        let memos = workspace.join("memos");
        std::fs::create_dir_all(&memos).expect("memos");
        std::fs::write(memos.join("composed.md"), "composed-body").expect("seed markdown");
        lomo_workspace::load_or_mint_workspace_generation(&workspace)
            .expect("workspace generation");
        run_rebuild(&workspace, 8).expect("index seed");

        let summary = run_composed_sync_cycle(
            &workspace,
            &SyncBackendConfig::hermetic_fake("ds-composed"),
            None,
            false,
        )
        .expect("composed");
        assert!(
            summary.ensure_present_count >= 1,
            "real store local port must surface EnsurePresent, got {}",
            summary.ensure_present_count
        );
        assert_eq!(summary.session_kind, SessionKind::FirstTakeover);
        assert_eq!(summary.retry_disposition, "after_user_action");
    }

    #[test]
    fn composed_webdav_missing_secret_fail_closed() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        lomo_workspace::load_or_mint_workspace_generation(&workspace)
            .expect("workspace generation");
        let _store = Store::open(&workspace).expect("open");
        let config = SyncBackendConfig::WebDav {
            endpoint_url: "https://dav.example/remote.php/dav".into(),
            username: "alice".into(),
            remote_dataset_id: "ds-webdav".into(),
        };
        let err =
            run_composed_sync_cycle(&workspace, &config, None, true).expect_err("secret required");
        assert_eq!(err.code(), "webdav_secret_required");
    }

    #[test]
    fn composed_git_kind_without_remote_port_fail_closed() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let _store = Store::open(&workspace).expect("open");
        let config = SyncBackendConfig::Git {
            remote_url: "https://example.com/repo.git".into(),
            username: String::new(),
            branch: "main".into(),
            author_name: String::new(),
            author_email: String::new(),
            remote_dataset_id: "ds-git".into(),
        };
        let err = run_composed_sync_cycle(&workspace, &config, None, false)
            .expect_err("git must use remote-port entry");
        assert_eq!(err.code(), "sync_git_compose_via_remote_port");
    }

    #[test]
    fn composed_git_with_remote_port_hermetic_bare_ensure_present() {
        use std::time::Duration;

        use git2::{Repository, RepositoryInitOptions};
        use lomo_git::{
            GitCredentials, GitLocalMode, MapGitConnectParams, MapGitObjectSource,
            connect_map_git_source,
        };
        use lomo_sync::run_composed_sync_cycle_with_remote_port;

        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let bare = temporary.path().join("remote.git");
        let mirror = temporary.path().join("mirror.git");
        let mut opts = RepositoryInitOptions::new();
        opts.bare(true);
        opts.initial_head("main");
        Repository::init_opts(&bare, &opts).expect("init bare");

        let memos = workspace.join("memos");
        std::fs::create_dir_all(&memos).expect("memos");
        std::fs::write(memos.join("git-port.md"), "git-port-body").expect("seed markdown");
        lomo_workspace::load_or_mint_workspace_generation(&workspace)
            .expect("workspace generation");
        run_rebuild(&workspace, 8).expect("index seed");

        let remote = connect_map_git_source(MapGitConnectParams {
            remote_url: bare.to_str().expect("utf8"),
            branch: "main",
            local: GitLocalMode::AppPrivateBareMirror { mirror_dir: mirror },
            credentials: GitCredentials::anonymous(),
            objects: MapGitObjectSource::default(),
            timeout: Duration::from_secs(5),
            author_name: "lomo-git",
            author_email: "git@lomo.local",
        })
        .expect("git adapter");

        let config = SyncBackendConfig::Git {
            remote_url: bare.to_string_lossy().into_owned(),
            username: String::new(),
            branch: "main".into(),
            author_name: "Lomo".into(),
            author_email: "git@lomo.local".into(),
            remote_dataset_id: "ds-git-port".into(),
        };
        let summary = run_composed_sync_cycle_with_remote_port(&workspace, &config, &remote, false)
            .expect("composed with git port");
        assert!(
            summary.ensure_present_count >= 1,
            "git remote-port composition must surface EnsurePresent, got {}",
            summary.ensure_present_count
        );
        assert_eq!(summary.session_kind, SessionKind::FirstTakeover);
    }

    #[test]
    fn composed_git_with_remote_port_apply_publishes_whole_batch_ref() {
        use std::time::Duration;

        use git2::{Repository, RepositoryInitOptions};
        use lomo_git::{WorkspaceFileGitObjectSource, connect_workspace_git};
        use lomo_sync::{RemoteSyncPort, run_composed_sync_cycle_with_remote_port};

        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let bare = temporary.path().join("remote.git");
        let mirror = temporary.path().join("mirror.git");
        let mut opts = RepositoryInitOptions::new();
        opts.bare(true);
        opts.initial_head("main");
        Repository::init_opts(&bare, &opts).expect("init bare");

        let memos = workspace.join("memos");
        std::fs::create_dir_all(&memos).expect("memos");
        std::fs::write(memos.join("git-apply.md"), "- 10:00:00\ngit-apply-body\n")
            .expect("seed markdown");
        lomo_workspace::load_or_mint_workspace_generation(&workspace)
            .expect("workspace generation");
        run_rebuild(&workspace, 8).expect("index seed");

        let remote = connect_workspace_git(
            bare.to_str().expect("utf8"),
            "main",
            mirror,
            "",
            "",
            WorkspaceFileGitObjectSource::new(&workspace),
            "lomo-git",
            "git@lomo.local",
            Duration::from_secs(5),
        )
        .expect("git adapter");

        let config = SyncBackendConfig::Git {
            remote_url: bare.to_string_lossy().into_owned(),
            username: String::new(),
            branch: "main".into(),
            author_name: "Lomo".into(),
            author_email: "git@lomo.local".into(),
            remote_dataset_id: "ds-git-apply".into(),
        };
        let summary = run_composed_sync_cycle_with_remote_port(&workspace, &config, &remote, true)
            .expect("composed git apply");
        assert!(
            summary.ensure_present_count >= 1,
            "apply cycle must still plan EnsurePresent, got {}",
            summary.ensure_present_count
        );
        let listed = remote.list_remote().expect("list after apply");
        assert!(
            listed
                .entries
                .iter()
                .any(|entry| entry.path.as_str().ends_with("git-apply.md")),
            "published tree must contain the local markdown: {:?}",
            listed.entries
        );
    }
}
