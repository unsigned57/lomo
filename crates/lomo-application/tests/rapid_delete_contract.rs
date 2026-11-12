//! Behavior Contract:
//! Capability: deletion from an unchanged memo baseline survives sibling writes; owner: application; P0.
//! Scenarios:
//! - Given two rows from one document snapshot, when deleted consecutively, then both enter trash.
//! - Given an updated target or external edit, when deleted with an old baseline, then conflict preserves bytes.
//!
//! Observable outcomes: trash rows, unchanged active target and conflict code.
//!
//! TDD proof: `cargo test -p lomo-application --test rapid_delete_contract --locked`;
//! RED: second delete returns `stale_document_baseline`; GREEN: all scenarios pass.
//!
//! Excludes: Compose gestures and Android SAF execution.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract fixture failures must stop the test"
)]
mod tests {
    use lomo_application::{
        CreateMemoRequest, DeleteMemoRequest, UpdateMemoRequest, WorkspaceSession,
        WorkspaceSessionConfig,
    };
    use lomo_core::{CapabilityToken, OperationId, RelativeWorkspacePath};
    use lomo_platform_fs::FsPlatformActionExecutor;
    use lomo_workspace::WorkspaceRootId;
    use std::{fs, sync::Arc};
    use tempfile::tempdir;
    struct TestContext {
        _workspace_dir: tempfile::TempDir,
        _state_dir: tempfile::TempDir,
        _cache_dir: tempfile::TempDir,
        _runtime_dir: tempfile::TempDir,
        _exchange_dir: tempfile::TempDir,
        workspace_path: std::path::PathBuf,
        session: WorkspaceSession,
        _executor: Arc<FsPlatformActionExecutor>,
        _config: WorkspaceSessionConfig,
    }

    fn setup_test_context() -> TestContext {
        let workspace_dir = tempdir().expect("workspace dir");
        let state_dir = tempdir().expect("state dir");
        let cache_dir = tempdir().expect("cache dir");
        let runtime_dir = tempdir().expect("runtime dir");
        let exchange_dir = tempdir().expect("exchange dir");

        let executor =
            Arc::new(FsPlatformActionExecutor::new(exchange_dir.path()).expect("executor"));
        let capability = CapabilityToken::parse("test-notes-root").expect("capability token");
        executor
            .bind_root(capability.clone(), workspace_dir.path())
            .expect("bind root");

        let config = WorkspaceSessionConfig {
            capability,
            root_id: WorkspaceRootId::Notes,
            workspace_generation: lomo_workspace::WorkspaceGenerationId::mint()
                .expect("workspace generation"),
            time_zone: "UTC".to_owned(),
            date_format: lomo_application::calendar::DateFormat::default(),
            state_dir: state_dir.path().to_path_buf(),
            cache_dir: cache_dir.path().to_path_buf(),
            runtime_dir: runtime_dir.path().to_path_buf(),
            exchange_dir: exchange_dir.path().to_path_buf(),
            media_stage_root: exchange_dir.path().join("media-stage"),
        };

        let session = WorkspaceSession::open(
            config.clone(),
            Arc::<FsPlatformActionExecutor>::clone(&executor),
        )
        .expect("open session");
        let workspace_path = workspace_dir.path().to_path_buf();

        TestContext {
            _workspace_dir: workspace_dir,
            _state_dir: state_dir,
            _cache_dir: cache_dir,
            _runtime_dir: runtime_dir,
            _exchange_dir: exchange_dir,
            workspace_path,
            session,
            _executor: executor,
            _config: config,
        }
    }

    fn create(ctx: &TestContext, name: &str) -> lomo_application::CreateMemoResult {
        let committed = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse(name).expect("operation"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_11_04.md").expect("path")),
                time_token: Some("10:00:00".to_owned()),
                content: name.to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        committed
    }

    #[test]
    fn consecutive_sibling_deletes_use_the_original_visible_baseline() {
        let ctx = setup_test_context();
        let first = create(&ctx, "first");
        let second = create(&ctx, "second");
        let baseline = second.commit_result.file_fingerprint;
        for (operation, id) in [
            ("delete-first", first.memo_id),
            ("delete-second", second.memo_id),
        ] {
            ctx.session
                .delete_memo(DeleteMemoRequest {
                    operation_id: OperationId::parse(operation).expect("op"),
                    memo_id: id.clone(),
                    expected_document_fingerprint: baseline.clone(),
                    trashed_at_ms: None,
                })
                .expect("unchanged sibling must be deletable");
            ctx.session
                .settle_workspace()
                .expect("committed writes converge");
            assert!(ctx.session.get_memo(&id).expect("read").is_none());
        }
    }

    #[test]
    fn changed_target_rejects_the_original_baseline() {
        let ctx = setup_test_context();
        let target = create(&ctx, "target");
        let baseline = target.commit_result.file_fingerprint;
        ctx.session
            .update_memo(UpdateMemoRequest {
                operation_id: OperationId::parse("update-target").expect("op"),
                memo_id: target.memo_id.clone(),
                expected_document_fingerprint: baseline.clone(),
                content: "changed".to_owned(),
                pending_promotes: Vec::new(),
            })
            .expect("update");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        let error = ctx
            .session
            .delete_memo(DeleteMemoRequest {
                operation_id: OperationId::parse("delete-target").expect("op"),
                memo_id: target.memo_id.clone(),
                expected_document_fingerprint: baseline,
                trashed_at_ms: None,
            })
            .expect_err("target changed");
        assert_eq!(error.code(), "stale_document_baseline");
        assert_eq!(
            ctx.session
                .get_memo(&target.memo_id)
                .expect("read")
                .expect("active")
                .body,
            "changed"
        );
    }

    #[test]
    fn external_changes_after_a_sibling_write_are_rejected() {
        let ctx = setup_test_context();
        let target = create(&ctx, "target");
        create(&ctx, "sibling");
        let path = ctx.workspace_path.join("2026_11_04.md");
        let changed = fs::read_to_string(&path)
            .expect("read")
            .replace("target", "external");
        fs::write(&path, &changed).expect("external write");
        let error = ctx
            .session
            .delete_memo(DeleteMemoRequest {
                operation_id: OperationId::parse("delete-external").expect("op"),
                memo_id: target.memo_id,
                expected_document_fingerprint: target.commit_result.file_fingerprint,
                trashed_at_ms: None,
            })
            .expect_err("external edit");
        assert_eq!(error.code(), "stale_document_baseline");
        assert_eq!(fs::read_to_string(path).expect("read"), changed);
    }
}
