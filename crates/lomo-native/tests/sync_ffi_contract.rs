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
//! - Given no durable `conflicts.rec`, when `sync_list_conflicts` runs, then the page is
//!   `SyncConflictSessionStateDto::Absent` (not `EngineError`).
//! - Given durable markdown conflict artifacts, when `sync_read_conflict_artifact` runs, then
//!   body bytes round-trip; traversal / empty refs fail closed.
//! - Given expected conflict revision, when `sync_resolve_conflicts` `KeepLocal` runs, then
//!   revision advances and applied path is returned.
//! - Given stale expected revision, when resolve runs, then structured conflict error.
//! - Given invalid resolution kind / empty workspace / oversize page limit / oversize secret,
//!   when free-functions run, then `validation` / `resource_limit` codes fire.
//! - Given secret lease issue→probe→revoke, when inspected, then only lease ids appear and
//!   plaintext secret bytes never appear in lease id wire form.
//! - Given a store-backed workspace + hermetic backend, when `sync_run_cycle` runs, then real local
//!   store port composition yields a non-empty `ensure_present` plan (not empty-port inspect).
//! - Given blank workspace / invalid backend / missing `WebDAV` secret lease, when `sync_run_cycle`
//!   runs, then fail-closed codes without inventing planner rules.
//! - Given blank Git remote URL, when `sync_run_cycle` runs with backend `git`, then
//!   `git_config_incomplete` (not the old fail-closed theater code).
//! - Given a store-backed workspace + hermetic bare Git remote, when `sync_run_cycle` runs with
//!   backend `git` (plan-only), then real local + `lomo-git` composition yields
//!   `ensure_present` ≥ 1 (Git-in-native composition GREEN).
//! - Given a held workspace cycle lock, when `sync_run_cycle` runs, then `sync_cycle_lock_held`
//!   Busy without entering the owner cycle.
//! - Given durable session/baseline files, when `sync_reset_control_tree` runs, then control
//!   records are removed and user Markdown remains.
//! - Given a minted `generation.rec`, when `sync_workspace_generation` runs, then the hex id
//!   round-trips; missing generation fails closed without minting.
//!
//! Observable outcomes: DTO fields, `EngineError` codes/categories, lease id shape, lock/reset.
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
    use lomo_native::{
        SyncBackendConfigDto, SyncConflictPathStatusDto, SyncConflictResolutionDto,
        SyncConflictSessionStateDto, looks_like_lease_id, sync_cycle_status,
        sync_issue_secret_lease, sync_list_conflicts, sync_probe_backend, sync_probe_secret_lease,
        sync_read_conflict_artifact, sync_request_cancel, sync_reset_control_tree,
        sync_resolve_conflicts, sync_revoke_secret_lease, sync_run_cycle,
        sync_workspace_generation,
    };
    use lomo_store::{Store, run_rebuild};
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

    fn config_dto(kind: &str, dataset: &str) -> SyncBackendConfigDto {
        SyncBackendConfigDto {
            backend_kind: kind.to_owned(),
            remote_dataset_id: dataset.to_owned(),
            ..SyncBackendConfigDto::default()
        }
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
        assert_eq!(page.session, SyncConflictSessionStateDto::Present);
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
    fn missing_conflict_session_lists_as_absent_not_engine_error() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");

        let page = sync_list_conflicts(workspace.to_string_lossy().into_owned(), 0, 10)
            .test_ok("absent is a domain state");
        assert_eq!(page.session, SyncConflictSessionStateDto::Absent);
        assert!(page.items.is_empty());
        assert!(page.session_id.is_empty());
        assert_eq!(page.conflict_revision, 0);
    }

    #[test]
    fn truncated_conflict_session_lists_as_structured_corrupt_error() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let paths = SyncPaths::for_workspace(&workspace);
        paths.ensure_layout().expect("layout");
        std::fs::write(&paths.conflicts, b"BAD!").expect("seed");

        let err = sync_list_conflicts(workspace.to_string_lossy().into_owned(), 0, 10)
            .test_err("truncated is EngineError");
        assert_eq!(err.category(), "corruption");
        assert_ne!(err.code(), "conflict_session_missing");
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
    fn run_cycle_hermetic_uses_store_local_port_not_empty_inspect() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws-composed");
        std::fs::create_dir_all(&workspace).expect("ws");
        let memos = workspace.join("memos");
        std::fs::create_dir_all(&memos).expect("memos");
        std::fs::write(memos.join("composed.md"), "composed-body").expect("seed markdown");
        lomo_workspace::load_or_mint_workspace_generation(&workspace)
            .expect("workspace generation");
        run_rebuild(&workspace, 8).expect("index seed");

        let root = workspace.to_string_lossy().into_owned();
        let summary = sync_run_cycle(
            root,
            config_dto("hermetic_fake", "ds-composed"),
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
            config_dto("hermetic_fake", "ds"),
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
            config_dto("not-a-backend", "ds"),
            String::new(),
            false,
        )
        .test_err("invalid backend");
        assert_eq!(err.code(), "sync_ffi_backend_kind_invalid");

        let err = sync_run_cycle(root.clone(), config_dto("git", "ds"), String::new(), false)
            .test_err("git incomplete");
        assert_eq!(err.code(), "git_config_incomplete");

        // Git remote URL with embedded userinfo is rejected at the FFI edge (before adapter).
        let err = sync_run_cycle(
            root.clone(),
            SyncBackendConfigDto {
                endpoint_url: "https://alice:s3cr3t@example.com/repo.git".to_owned(),
                git_branch: "main".to_owned(),
                ..config_dto("git", "ds")
            },
            String::new(),
            false,
        )
        .test_err("git userinfo");
        assert_eq!(err.code(), "git_url_userinfo_rejected");

        // Fields belonging to another backend kind are a wire violation, not a silent borrow.
        let err = sync_run_cycle(
            root,
            SyncBackendConfigDto {
                s3_bucket: "leaked".to_owned(),
                ..config_dto("git", "ds")
            },
            String::new(),
            false,
        )
        .test_err("mixed shape");
        assert_eq!(err.code(), "sync_ffi_config_field_mismatch");
    }

    #[test]
    fn run_cycle_git_hermetic_bare_repo_composes_ensure_present() {
        use git2::{Repository, RepositoryInitOptions};

        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let bare = temporary.path().join("remote.git");
        let mut opts = RepositoryInitOptions::new();
        opts.bare(true);
        opts.initial_head("main");
        Repository::init_opts(&bare, &opts).expect("init bare");

        let memos = workspace.join("memos");
        std::fs::create_dir_all(&memos).expect("memos");
        std::fs::write(memos.join("git-compose.md"), "git-composed-body").expect("seed markdown");
        lomo_workspace::load_or_mint_workspace_generation(&workspace)
            .expect("workspace generation");
        run_rebuild(&workspace, 8).expect("index seed");

        let root = workspace.to_string_lossy().into_owned();
        let bare_url = bare.to_string_lossy().into_owned();
        let summary = sync_run_cycle(
            root,
            SyncBackendConfigDto {
                endpoint_url: bare_url,
                git_branch: "main".to_owned(),
                git_author_name: "Lomo".to_owned(),
                git_author_email: "git@lomo.local".to_owned(),
                ..config_dto("git", "ds-git-compose")
            },
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
        lomo_workspace::load_or_mint_workspace_generation(&workspace)
            .expect("workspace generation");
        // Ensure store exists so failure is secret/config, not store open.
        let _store = Store::open(&workspace).test_ok("open store");
        let root = workspace.to_string_lossy().into_owned();

        let err = sync_run_cycle(
            root,
            SyncBackendConfigDto {
                endpoint_url: "https://dav.example/remote.php/dav".to_owned(),
                identity: "alice".to_owned(),
                ..config_dto("webdav", "ds-webdav")
            },
            String::new(),
            true,
        )
        .test_err("missing secret");
        assert_eq!(err.code(), "webdav_secret_required");
    }

    #[test]
    fn run_cycle_held_lock_is_busy_without_entering_cycle() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws-lock");
        std::fs::create_dir_all(&workspace).expect("ws");
        let paths = SyncPaths::for_workspace(&workspace);
        let _held = lomo_platform_fs::ProcessFileLock::try_acquire(&paths.cycle_lock)
            .test_ok("hold cycle lock");

        let err = sync_run_cycle(
            workspace.to_string_lossy().into_owned(),
            config_dto("hermetic_fake", "ds-lock"),
            String::new(),
            false,
        )
        .test_err("lock held");
        assert_eq!(err.code(), "sync_cycle_lock_held");
        assert_eq!(err.category(), "busy");
        assert!(
            !paths.session.exists(),
            "lock refusal must not write a durable session"
        );
    }

    #[test]
    fn reset_control_tree_removes_sync_records_not_user_markdown() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws-reset");
        std::fs::create_dir_all(&workspace).expect("ws");
        let memo = workspace.join("memos");
        std::fs::create_dir_all(&memo).expect("memos");
        std::fs::write(memo.join("keep.md"), "user-bytes").expect("user markdown");
        let paths = SyncPaths::for_workspace(&workspace);
        write_session(
            &paths,
            &SyncSession::new(fence(), SessionKind::Incremental, "reset-session")
                .test_ok("session"),
        )
        .test_ok("write session");
        assert!(paths.session.exists());

        sync_reset_control_tree(workspace.to_string_lossy().into_owned()).test_ok("reset");

        assert!(!paths.session.exists());
        assert_eq!(
            std::fs::read(memo.join("keep.md")).expect("user survives"),
            b"user-bytes"
        );
    }

    #[test]
    fn workspace_generation_loads_without_minting() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws-gen");
        std::fs::create_dir_all(&workspace).expect("ws");
        let missing = sync_workspace_generation(workspace.to_string_lossy().into_owned())
            .test_err("missing generation");
        assert_eq!(missing.code(), "workspace_generation_missing");
        assert!(
            !lomo_workspace::LomoPaths::generation_record_path(&workspace).exists(),
            "read FFI must not mint generation.rec"
        );

        let minted =
            lomo_workspace::load_or_mint_workspace_generation(&workspace).test_ok("mint write");
        let loaded = sync_workspace_generation(workspace.to_string_lossy().into_owned())
            .test_ok("load generation");
        assert_eq!(loaded, minted.as_str());
    }

    #[test]
    fn cycle_status_reads_durable_record_and_survives_result_writes() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws-status");
        std::fs::create_dir_all(&workspace).expect("ws");
        let memos = workspace.join("memos");
        std::fs::create_dir_all(&memos).expect("memos");
        std::fs::write(memos.join("status.md"), "status-body").expect("seed markdown");
        lomo_workspace::load_or_mint_workspace_generation(&workspace)
            .expect("workspace generation");
        run_rebuild(&workspace, 8).expect("index seed");

        let root = workspace.to_string_lossy().into_owned();

        // No durable cycle yet: honest idle, not a fabricated zero-count record.
        let idle = sync_cycle_status(root.clone()).test_ok("empty status");
        assert!(!idle.has_record);
        assert_eq!(idle.phase, "idle");
        assert_eq!(idle.cycle_seq, 0);

        let summary = sync_run_cycle(
            root.clone(),
            config_dto("hermetic_fake", "ds-status"),
            String::new(),
            false,
        )
        .test_ok("run composed hermetic");
        assert!(summary.ensure_present_count >= 1);

        // Durable record is the authority: cycle facts round-trip without Kotlin-side state.
        let status = sync_cycle_status(root.clone()).test_ok("status after cycle");
        assert!(status.has_record);
        assert_eq!(status.phase, "completed");
        assert_eq!(status.stage, "finished");
        assert_eq!(status.cycle_seq, 1);
        assert_eq!(status.cycle_id, "cycle-000001");
        assert_eq!(status.backend_kind, "hermetic_fake");
        assert_eq!(status.ensure_present_count, summary.ensure_present_count);
        assert!(status.state_stamp >= 2, "begin+finish bumps the stamp");
        // Plan-only cycle must not claim a successful sync timestamp.
        assert_eq!(status.last_successful_at_ms, None);
        assert!(status.finished_at_ms.is_some());

        // Second read observes the same durable fact — no memory-only state.
        let again = sync_cycle_status(root).test_ok("status re-read");
        assert_eq!(again.cycle_id, status.cycle_id);
        assert_eq!(again.state_stamp, status.state_stamp);
    }

    #[test]
    fn request_cancel_rejects_when_no_cycle_running() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws-cancel");
        std::fs::create_dir_all(&workspace).expect("ws");
        lomo_workspace::load_or_mint_workspace_generation(&workspace)
            .expect("workspace generation");
        run_rebuild(&workspace, 8).expect("index seed");
        let root = workspace.to_string_lossy().into_owned();

        let err = sync_request_cancel(root.clone()).test_err("no running cycle");
        assert_eq!(err.code(), "sync_cycle_not_running");

        // A completed cycle is not running — cancel must not rewrite a terminal record.
        sync_run_cycle(
            root.clone(),
            config_dto("hermetic_fake", "ds-cancel"),
            String::new(),
            false,
        )
        .test_ok("completed cycle");
        let err = sync_request_cancel(root.clone()).test_err("terminal cycle");
        assert_eq!(err.code(), "sync_cycle_not_running");
        let status = sync_cycle_status(root).test_ok("status preserved");
        assert_eq!(status.phase, "completed");
        assert!(!status.cancel_requested);
    }

    #[test]
    fn probe_backend_runs_real_adapter_round_trip() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws-probe");
        std::fs::create_dir_all(&workspace).expect("ws");
        lomo_workspace::load_or_mint_workspace_generation(&workspace)
            .expect("workspace generation");
        run_rebuild(&workspace, 8).expect("index seed");
        let root = workspace.to_string_lossy().into_owned();

        // Hermetic probe: real port construction + listing round-trip (empty remote).
        let probe = sync_probe_backend(
            root.clone(),
            config_dto("hermetic_fake", "ds-probe"),
            String::new(),
        )
        .test_ok("hermetic probe");
        assert_eq!(probe.backend_kind, "hermetic_fake");
        assert_eq!(probe.listed_entry_count, 0);
        assert!(probe.conditional_write);
        assert!(probe.conditional_delete);
        assert!(probe.probed_at_ms > 0);
        // Probe must not invent a cycle record — status stays idle.
        let status = sync_cycle_status(root.clone()).test_ok("post-probe status");
        assert!(!status.has_record);

        let err = sync_probe_backend(
            String::new(),
            config_dto("hermetic_fake", "ds-probe"),
            String::new(),
        )
        .test_err("blank workspace");
        assert_eq!(err.code(), "sync_ffi_workspace_root_invalid");

        // A held cycle lock refuses the probe — remote mutation observation must not race.
        let paths = SyncPaths::for_workspace(&workspace);
        let _held = lomo_platform_fs::ProcessFileLock::try_acquire(&paths.cycle_lock)
            .test_ok("hold cycle lock");
        let err = sync_probe_backend(root, config_dto("hermetic_fake", "ds-probe"), String::new())
            .test_err("probe under held lock");
        assert_eq!(err.code(), "sync_cycle_lock_held");
    }
}
