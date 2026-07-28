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
//!
//! Observable outcomes: `SyncCyclePlanSummary` fields; structured error codes; meaningful
//! disposition under real fake snapshots.
//! Excludes: real provider publish/apply, production DI, `BoltFFI` wire (native contract), Kotlin
//! planner re-implementation, multi-process death.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_core::OperationId;
    use lomo_store::{MemoCommand, MemoCommandKind, Store};
    use lomo_sync::{
        BaselineHead, ConflictBodySource, ConflictSession, ContentDigest, FakeLocalPort,
        FakeRemotePort, LocalPathEntry, PathPublishStatus, PublishReceipt, RemotePathEntry,
        RemoteSnapshot, SessionKind, SnapshotCompleteness, SyncBackendConfig, SyncIdentityFence,
        SyncPath, SyncPaths, SyncSession, VerifiedRemoteState, VerifyStatus,
        conflict_path_from_open, inspect_sync_cycle_plan, inspect_sync_cycle_plan_with_ports,
        run_composed_sync_cycle, write_baseline, write_conflict_session, write_session,
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
                    digest: d_remote,
                    revision_token: "tok-remote".to_owned(),
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
                    digest: dig(2),
                    revision_token: "tok-remote".to_owned(),
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
        let mut store = Store::open(&workspace).expect("open");
        store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-composed-1").expect("op"),
                    kind: MemoCommandKind::Create,
                    memo_id: "composed".into(),
                    expected_revision: 0,
                    expected_fingerprint: None,
                    content: Some("composed-body".into()),
                    tags: vec![],
                    pin: None,
                    pending_promotes: vec![],
                },
                None,
            )
            .expect("create");
        drop(store);

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
        let _store = Store::open(&workspace).expect("open");
        let config = SyncBackendConfig {
            kind: lomo_sync::SyncBackendKind::WebDav,
            endpoint_url: "https://dav.example/remote.php/dav".into(),
            username_or_access_key: "alice".into(),
            bucket: String::new(),
            prefix: String::new(),
            region: String::new(),
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
        let config = SyncBackendConfig {
            kind: lomo_sync::SyncBackendKind::Git,
            endpoint_url: "https://example.com/repo.git".into(),
            username_or_access_key: String::new(),
            bucket: "main".into(),
            prefix: String::new(),
            region: String::new(),
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

        let mut store = Store::open(&workspace).expect("open");
        store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-git-port-1").expect("op"),
                    kind: MemoCommandKind::Create,
                    memo_id: "git-port".into(),
                    expected_revision: 0,
                    expected_fingerprint: None,
                    content: Some("git-port-body".into()),
                    tags: vec![],
                    pin: None,
                    pending_promotes: vec![],
                },
                None,
            )
            .expect("create");
        drop(store);

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

        let config = SyncBackendConfig {
            kind: lomo_sync::SyncBackendKind::Git,
            endpoint_url: bare.to_string_lossy().into_owned(),
            username_or_access_key: String::new(),
            bucket: "main".into(),
            prefix: "Lomo".into(),
            region: "git@lomo.local".into(),
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
}
