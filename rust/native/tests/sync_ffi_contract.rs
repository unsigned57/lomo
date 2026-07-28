//! Behavior Contract — P5-09 dark `BoltFFI` sync surface (host hermetic)
//!
//! - Unit under test: free-function `sync_*` `BoltFFI` conversion APIs in `lomo-native`
//! - Owning layer: `lomo-native` (conversion only); rules in `lomo-sync` / `lomo-core`
//! - Priority tier: P0
//! - Capability: coarse-grained typed sync FFI without DAO / SDK models / enum ordinals /
//!   per-file JNI callbacks; ephemeral secret lease (id only on wire); `WorkManager`-facing
//!   `RetryDisposition` mapping; oversize/invalid boundary fail-closed.
//!
//! Scenarios:
//! - Given a durable conflict session, when `sync_list_conflicts` runs, then digests/status
//!   round-trip and remote token values are not exposed (presence only).
//! - Given durable markdown conflict artifacts, when `sync_read_conflict_artifact` runs, then
//!   body bytes round-trip; traversal / empty refs fail closed.
//! - Given expected conflict revision, when `sync_resolve_conflicts` `KeepLocal` runs, then
//!   revision advances and applied path is returned.
//! - Given stale expected revision, when resolve runs, then structured conflict error.
//! - Given invalid resolution kind / empty workspace / oversize page limit / oversize secret,
//!   when free-functions run, then `validation` / `resource_limit` codes fire.
//! - Given secret lease issue→probe→revoke, when inspected, then only lease ids appear and
//!   plaintext secret bytes never appear in lease id wire form.
//! - Given retry disposition names, when mapped, then `Never` / `AfterUserAction` / `Transient`
//!   (no fixed three-retry policy in the DTO).
//! - Given a durable sync session, when `sync_inspect_cycle_plan` runs, then session identity +
//!   disposition round-trip without planner re-implementation in native.
//! - Given no durable session / empty workspace root, when inspect runs, then fail-closed codes.
//! - Given a store-backed workspace + hermetic backend, when `sync_run_cycle` runs, then real local
//!   store port composition yields a non-empty `ensure_present` plan (not empty-port inspect).
//! - Given blank workspace / invalid backend / missing `WebDAV` secret lease, when `sync_run_cycle`
//!   runs, then fail-closed codes without inventing planner rules.
//! - Given blank Git remote URL, when `sync_run_cycle` runs with backend `git`, then
//!   `git_config_incomplete` (not the old fail-closed theater code).
//! - Given a store-backed workspace + hermetic bare Git remote, when `sync_run_cycle` runs with
//!   backend `git` (plan-only), then real local + `lomo-git` composition yields
//!   `ensure_present` ≥ 1 (Git-in-native composition GREEN).
//!
//! Observable outcomes: DTO fields, `EngineError` codes/categories, lease id shape.
//! Excludes: production DI / registry / navigation / `WorkManager` wiring theater, Kotlin
//! fake-first production adapters, real providers, arm64 device, Sync Center UI.

#[cfg(test)]
mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use super::support::ResultTestExt;
    use lomo_core::OperationId;
    use lomo_native::{
        SyncConflictPathStatusDto, SyncConflictResolutionDto, SyncRetryDispositionDto,
        looks_like_lease_id, sync_inspect_cycle_plan, sync_issue_secret_lease, sync_list_conflicts,
        sync_probe_secret_lease, sync_read_conflict_artifact, sync_resolve_conflicts,
        sync_retry_disposition_from_name, sync_revoke_secret_lease, sync_run_cycle,
    };
    use lomo_store::{MemoCommand, MemoCommandKind, Store};
    use lomo_sync::{
        ConflictResolution, ConflictSession, ContentDigest, SessionKind, SyncIdentityFence,
        SyncPath, SyncPaths, SyncSession, conflict_path_from_open, resolve_sync_conflicts,
        write_conflict_artifact, write_conflict_session, write_session,
    };
    use lomo_workspace::{RemoteDatasetId, RemoteIdentityDigest, WorkspaceGenerationId};
    use tempfile::tempdir;

    fn dig(seed: u8) -> ContentDigest {
        ContentDigest::parse(&format!("{seed:02x}").repeat(32)).test_ok("digest")
    }

    fn path(raw: &str) -> SyncPath {
        SyncPath::parse(raw).test_ok("path")
    }

    fn fence() -> SyncIdentityFence {
        SyncIdentityFence::from_parts(
            &WorkspaceGenerationId::parse(&"ab".repeat(32)).test_ok("gen"),
            &RemoteDatasetId::parse("ds").test_ok("ds"),
            &RemoteIdentityDigest::parse(&"cd".repeat(32)).test_ok("id"),
        )
    }

    fn seed_markdown_conflict(workspace: &std::path::Path) -> SyncPaths {
        let paths = SyncPaths::for_workspace(workspace);
        let mut record = conflict_path_from_open(
            &path("memo/a.md"),
            Some(&dig(1)),
            Some(&dig(2)),
            Some(&dig(0)),
            Some("tok-secret-value-must-not-leak"),
        )
        .test_ok("record");
        let session_id = "ffi-session-1";
        record.local_artifact_ref = Some(
            write_conflict_artifact(&paths, session_id, "local", "memo/a.md", b"# local body\n")
                .test_ok("local art"),
        );
        record.remote_artifact_ref = Some(
            write_conflict_artifact(
                &paths,
                session_id,
                "remote",
                "memo/a.md",
                b"# remote body\n",
            )
            .test_ok("remote art"),
        );
        record.baseline_artifact_ref = Some(
            write_conflict_artifact(
                &paths,
                session_id,
                "baseline",
                "memo/a.md",
                b"# base body\n",
            )
            .test_ok("base art"),
        );
        let session = ConflictSession::open(fence(), session_id, vec![record]).test_ok("open");
        write_conflict_session(&paths, &session).test_ok("write");
        paths
    }

    #[test]
    fn list_conflicts_round_trip_hides_remote_token_value() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        seed_markdown_conflict(&workspace);

        let page =
            sync_list_conflicts(workspace.to_string_lossy().into_owned(), 0, 10).test_ok("list");
        assert_eq!(page.session_id, "ffi-session-1");
        assert_eq!(page.conflict_revision, 1);
        assert_eq!(page.items.len(), 1);
        let item = page.items.first().expect("item");
        assert_eq!(item.path, "memo/a.md");
        assert_eq!(item.kind, "markdown");
        assert_eq!(item.status, SyncConflictPathStatusDto::Open);
        assert!(item.remote_token_present);
        assert!(item.local_artifact_ref.is_some());
        assert!(item.remote_artifact_ref.is_some());
        assert!(item.baseline_artifact_ref.is_some());
        // Wire must not carry the token value — only presence.
        let encoded = format!("{item:?}");
        assert!(
            !encoded.contains("tok-secret-value-must-not-leak"),
            "remote token value must not appear on FFI wire: {encoded}"
        );
    }

    #[test]
    fn resolve_conflicts_advances_revision_via_ffi() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        seed_markdown_conflict(&workspace);
        let root = workspace.to_string_lossy().into_owned();

        let result = sync_resolve_conflicts(
            root.clone(),
            1,
            vec![SyncConflictResolutionDto {
                path: "memo/a.md".to_owned(),
                kind: "keep_local".to_owned(),
                merged_body: None,
            }],
        )
        .test_ok("resolve");
        assert_eq!(result.conflict_revision, 2);
        assert_eq!(result.applied_paths, vec!["memo/a.md".to_owned()]);
        assert_eq!(result.session_id, "ffi-session-1");

        let page = sync_list_conflicts(root, 0, 10).test_ok("list after");
        assert_eq!(page.conflict_revision, 2);
        assert_eq!(
            page.items.first().expect("i").status,
            SyncConflictPathStatusDto::ResolvedKeepLocal
        );
    }

    #[test]
    fn resolve_stale_revision_is_conflict_category() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let paths = seed_markdown_conflict(&workspace);
        // Advance once via owner API so revision is 2.
        resolve_sync_conflicts(
            &paths,
            1,
            &[ConflictResolution::SkipForNow {
                path: "memo/a.md".to_owned(),
            }],
        )
        .test_ok("owner resolve");

        let err = sync_resolve_conflicts(
            workspace.to_string_lossy().into_owned(),
            1,
            vec![SyncConflictResolutionDto {
                path: "memo/a.md".to_owned(),
                kind: "keep_local".to_owned(),
                merged_body: None,
            }],
        )
        .test_err("stale");
        assert_eq!(err.code(), "conflict_revision_stale");
        assert_eq!(err.category(), "conflict");
    }

    #[test]
    fn invalid_resolution_kind_and_empty_workspace_fail_closed() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        seed_markdown_conflict(&workspace);

        let err = sync_resolve_conflicts(
            workspace.to_string_lossy().into_owned(),
            1,
            vec![SyncConflictResolutionDto {
                path: "memo/a.md".to_owned(),
                kind: "force_overwrite".to_owned(),
                merged_body: None,
            }],
        )
        .test_err("bad kind");
        assert_eq!(err.code(), "sync_ffi_resolution_kind_invalid");

        let err = sync_list_conflicts(String::new(), 0, 10).test_err("empty root");
        assert_eq!(err.code(), "sync_ffi_workspace_root_invalid");
    }

    #[test]
    fn oversize_conflict_page_limit_and_secret_fail_closed() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        seed_markdown_conflict(&workspace);
        let root = workspace.to_string_lossy().into_owned();

        let err = sync_list_conflicts(root, 0, 0).test_err("zero limit");
        assert_eq!(err.code(), "sync_ffi_conflict_page_limit");
        assert_eq!(err.category(), "resource_limit");

        let err = sync_list_conflicts(
            temporary.path().join("ws").to_string_lossy().into_owned(),
            0,
            101,
        )
        .test_err("over page");
        assert_eq!(err.code(), "sync_ffi_conflict_page_limit");

        let huge = vec![0u8; 64 * 1024 + 1];
        let err = sync_issue_secret_lease(huge, 5_000).test_err("secret oversize");
        assert_eq!(err.code(), "sync_ffi_secret_too_large");
        assert_eq!(err.category(), "resource_limit");
    }

    #[test]
    fn secret_lease_round_trip_never_returns_plaintext_as_lease_id() {
        let secret = b"super-secret-token-value-do-not-log".to_vec();
        let lease = sync_issue_secret_lease(secret.clone(), 60_000).test_ok("issue");
        assert!(looks_like_lease_id(&lease.lease_id));
        assert!(!lease.lease_id.contains("super-secret"));
        assert_ne!(lease.lease_id.as_bytes(), secret.as_slice());

        let len = sync_probe_secret_lease(lease.lease_id.clone()).test_ok("probe");
        assert_eq!(len, u32::try_from(secret.len()).expect("len"));

        sync_revoke_secret_lease(lease.lease_id.clone()).test_ok("revoke");
        let err = sync_probe_secret_lease(lease.lease_id).test_err("missing after revoke");
        assert_eq!(err.code(), "secret_lease_missing");
    }

    #[test]
    fn process_death_style_unknown_lease_is_missing_not_plaintext_recovery() {
        // Process death drops the vault; recovery is re-issue credentials, not journal restore.
        let err = sync_probe_secret_lease("lease-999999".to_owned()).test_err("never issued");
        assert!(
            err.code() == "secret_lease_missing" || err.code() == "invalid_secret_lease_id",
            "unexpected code {}",
            err.code()
        );
    }

    #[test]
    fn retry_disposition_mapping_has_no_fixed_three_retry() {
        let never = sync_retry_disposition_from_name("never".to_owned()).test_ok("never");
        assert_eq!(never.disposition, SyncRetryDispositionDto::Never);
        assert!(never.retry_after_millis.is_none());

        let user = sync_retry_disposition_from_name("after_user_action".to_owned()).test_ok("user");
        assert_eq!(user.disposition, SyncRetryDispositionDto::AfterUserAction);

        let transient =
            sync_retry_disposition_from_name("transient".to_owned()).test_ok("transient");
        assert_eq!(transient.disposition, SyncRetryDispositionDto::Transient);
        // Dark slice maps disposition only; concrete delay is host scheduler policy.
        assert!(transient.retry_after_millis.is_none());

        let err = sync_retry_disposition_from_name("retry_three_times".to_owned())
            .test_err("fixed three-retry is not a disposition");
        assert_eq!(err.code(), "sync_ffi_retry_disposition_invalid");
    }

    #[test]
    fn read_conflict_artifact_returns_seeded_markdown_body() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        seed_markdown_conflict(&workspace);
        let root = workspace.to_string_lossy().into_owned();
        let page = sync_list_conflicts(root.clone(), 0, 10).test_ok("list");
        let item = page.items.first().expect("item");
        let local_ref = item.local_artifact_ref.clone().expect("local ref");
        let body = sync_read_conflict_artifact(root, local_ref).test_ok("read");
        assert_eq!(body, b"# local body\n");
    }

    #[test]
    fn read_conflict_artifact_rejects_traversal_and_empty_ref() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        seed_markdown_conflict(&workspace);
        let root = workspace.to_string_lossy().into_owned();

        let err = sync_read_conflict_artifact(root.clone(), String::new()).test_err("empty");
        assert_eq!(err.code(), "sync_ffi_artifact_ref_invalid");

        let err = sync_read_conflict_artifact(root, "../escape".to_owned()).test_err("traversal");
        assert_eq!(err.code(), "invalid_conflict_artifact_ref");
    }

    #[test]
    fn inspect_cycle_plan_round_trips_session_and_disposition() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let paths = SyncPaths::for_workspace(&workspace);
        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "ffi-cycle-1").test_ok("session");
        write_session(&paths, &session).test_ok("write session");

        let summary =
            sync_inspect_cycle_plan(workspace.to_string_lossy().into_owned()).test_ok("inspect");
        assert_eq!(summary.session_id, "ffi-cycle-1");
        assert_eq!(summary.session_kind, "incremental");
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
    fn inspect_cycle_plan_surfaces_open_conflict_paths() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        seed_markdown_conflict(&workspace);
        let paths = SyncPaths::for_workspace(&workspace);
        let session = SyncSession::new(fence(), SessionKind::Incremental, "ffi-cycle-conflict")
            .test_ok("session");
        write_session(&paths, &session).test_ok("write session");

        let summary =
            sync_inspect_cycle_plan(workspace.to_string_lossy().into_owned()).test_ok("inspect");
        assert_eq!(summary.session_id, "ffi-cycle-conflict");
        assert_eq!(summary.open_conflict_paths, 1);
        assert_eq!(summary.conflict_revision, Some(1));
        assert_eq!(summary.retry_disposition, "after_user_action");
    }

    #[test]
    fn inspect_cycle_plan_missing_session_and_empty_root_fail_closed() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws-empty");
        std::fs::create_dir_all(&workspace).expect("ws");

        let err = sync_inspect_cycle_plan(workspace.to_string_lossy().into_owned())
            .test_err("missing session");
        assert_eq!(err.code(), "sync_session_missing");

        let err = sync_inspect_cycle_plan(String::new()).test_err("empty root");
        assert_eq!(err.code(), "sync_ffi_workspace_root_invalid");
    }

    #[test]
    fn run_cycle_hermetic_uses_store_local_port_not_empty_inspect() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws-composed");
        std::fs::create_dir_all(&workspace).expect("ws");
        let mut store = Store::open(&workspace).test_ok("open store");
        store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-composed-1").test_ok("op"),
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
            .test_ok("create memo");
        drop(store);

        let root = workspace.to_string_lossy().into_owned();
        let summary = sync_run_cycle(
            root,
            "hermetic_fake".to_owned(),
            String::new(),
            String::new(),
            String::new(),
            String::new(),
            String::new(),
            "ds-composed".to_owned(),
            String::new(),
            false,
        )
        .test_ok("run composed hermetic");

        // Real store local port sees the memo → EnsurePresent under first-takeover.
        // Empty-port inspect would report 0 ensure_present.
        assert!(
            summary.ensure_present_count >= 1,
            "composed cycle must use real store local port, got ensure_present={}",
            summary.ensure_present_count
        );
        assert_eq!(summary.session_kind, "first_takeover");
        assert_eq!(summary.retry_disposition, "after_user_action");
        assert!(summary.session_id.contains("ds-composed"));
    }

    #[test]
    fn run_cycle_fail_closed_blank_workspace_and_invalid_backend() {
        let err = sync_run_cycle(
            String::new(),
            "hermetic_fake".to_owned(),
            String::new(),
            String::new(),
            String::new(),
            String::new(),
            String::new(),
            "ds".to_owned(),
            String::new(),
            false,
        )
        .test_err("blank workspace");
        assert_eq!(err.code(), "sync_ffi_workspace_root_invalid");

        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let root = workspace.to_string_lossy().into_owned();

        let err = sync_run_cycle(
            root.clone(),
            "not-a-backend".to_owned(),
            String::new(),
            String::new(),
            String::new(),
            String::new(),
            String::new(),
            "ds".to_owned(),
            String::new(),
            false,
        )
        .test_err("invalid backend");
        assert_eq!(err.code(), "sync_ffi_backend_kind_invalid");

        let err = sync_run_cycle(
            root,
            "git".to_owned(),
            String::new(),
            String::new(),
            String::new(),
            String::new(),
            String::new(),
            "ds".to_owned(),
            String::new(),
            false,
        )
        .test_err("git incomplete");
        assert_eq!(err.code(), "git_config_incomplete");
    }

    #[test]
    fn run_cycle_git_hermetic_bare_repo_composes_ensure_present() {
        use git2::{Repository, RepositoryInitOptions};
        use lomo_core::OperationId;
        use lomo_store::{MemoCommand, MemoCommandKind, Store};

        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let bare = temporary.path().join("remote.git");
        let mut opts = RepositoryInitOptions::new();
        opts.bare(true);
        opts.initial_head("main");
        Repository::init_opts(&bare, &opts).expect("init bare");

        let mut store = Store::open(&workspace).test_ok("open store");
        store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-git-compose-1").expect("op"),
                    kind: MemoCommandKind::Create,
                    memo_id: "git-compose".into(),
                    expected_revision: 0,
                    expected_fingerprint: None,
                    content: Some("git-composed-body".into()),
                    tags: vec![],
                    pin: None,
                    pending_promotes: vec![],
                },
                None,
            )
            .test_ok("create memo");
        drop(store);

        let root = workspace.to_string_lossy().into_owned();
        let bare_url = bare.to_string_lossy().into_owned();
        let summary = sync_run_cycle(
            root,
            "git".to_owned(),
            bare_url,
            String::new(),
            "main".to_owned(),
            "Lomo".to_owned(),
            "git@lomo.local".to_owned(),
            "ds-git-compose".to_owned(),
            String::new(),
            false,
        )
        .test_ok("git composed cycle");
        assert!(
            summary.ensure_present_count >= 1,
            "git composition must surface EnsurePresent from store local port, got {}",
            summary.ensure_present_count
        );
        assert_eq!(summary.session_kind, "first_takeover");
    }

    #[test]
    fn run_cycle_webdav_missing_secret_lease_fail_closed() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        // Ensure store exists so failure is secret/config, not store open.
        let _store = Store::open(&workspace).test_ok("open store");
        let root = workspace.to_string_lossy().into_owned();

        let err = sync_run_cycle(
            root,
            "webdav".to_owned(),
            "https://dav.example/remote.php/dav".to_owned(),
            "alice".to_owned(),
            String::new(),
            String::new(),
            String::new(),
            "ds-webdav".to_owned(),
            String::new(),
            true,
        )
        .test_err("missing secret");
        assert_eq!(err.code(), "webdav_secret_required");
    }
}
