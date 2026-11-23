//! Behavior Contract:
//! Capability: recreate an externally removed daily document without reviving old identities;
//! owning layer: lomo-application; priority: P0.
//! Scenarios:
//! - Given a committed daily document removed outside Lomo, when a current or reopened session
//!   creates a memo for the same date without an explicit projection refresh, then
//!   only the new content is stored under a fresh ID.
//! - Given the old ID and baseline, when updated after recreation, then the old memo stays absent.
//!
//! Observable outcomes: durable document bytes, fresh memo ID and rejected old-ID update.
//!
//! TDD proof: external-delete-red.log / external-delete-green.log under
//! reports/manual/reported-bugs/; RED fails with `stale_identity_map`.
//!
//! Cached projection proof: cached-projection-red.log fails with `memo_source_missing`;
//! cached-projection-green.log verifies the live-session path as well.
//!
//! Excludes: Android SAF provider and IME behavior.

#[cfg(test)]
#[expect(clippy::expect_used, reason = "fixture failures stop the regression")]
mod tests {
    use lomo_application::{CreateMemoRequest, WorkspaceSession, WorkspaceSessionConfig};
    use lomo_core::{CapabilityToken, OperationId, RelativeWorkspacePath};
    use lomo_platform_fs::FsPlatformActionExecutor;
    use lomo_workspace::{
        MemoIdentityMap, WorkspaceRelativePath, WorkspaceRootId, memo_identity_record_path,
    };
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
        executor: Arc<FsPlatformActionExecutor>,
        config: WorkspaceSessionConfig,
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
            executor,
            config,
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
                date_format: None,
                timestamp_format: None,
            })
            .expect("create");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        committed
    }

    #[test]
    fn reopening_after_external_deletion_allows_same_day_creation() {
        for reopen in [true, false] {
            let ctx = setup_test_context();
            let first = create(&ctx, "before-external-deletion");
            fs::remove_file(ctx.workspace_path.join("2026_11_04.md")).expect("external removal");
            let session = if reopen {
                drop(ctx.session);
                WorkspaceSession::open(ctx.config, ctx.executor).expect("reopen")
            } else {
                ctx.session
            };
            let created = session
                .create_memo(CreateMemoRequest {
                    operation_id: OperationId::parse("after-external-deletion").expect("operation"),
                    relative_path: Some(
                        RelativeWorkspacePath::parse("2026_11_04.md").expect("path"),
                    ),
                    time_token: Some("11:00:00".to_owned()),
                    content: "after-external-deletion".to_owned(),
                    expected_document_fingerprint: None,
                    pinned: false,
                    pending_promotes: Vec::new(),
                    chronology_epoch_ms: None,
                    date_format: None,
                    timestamp_format: None,
                })
                .expect("new memo after external removal");
            session
                .settle_workspace()
                .expect("settle recreated document");
            assert!(
                session
                    .get_memo(&first.memo_id)
                    .expect("retired projection lookup")
                    .is_none(),
                "verified source absence must retire its previous projected rows before publication"
            );
            session
                .rebuild_projection()
                .expect("reconcile recreated document");
            assert_ne!(created.memo_id, first.memo_id);
            let bytes =
                fs::read_to_string(ctx.workspace_path.join("2026_11_04.md")).expect("document");
            assert!(bytes.contains("after-external-deletion"));
            assert!(!bytes.contains("before-external-deletion"));
            let record_path = memo_identity_record_path(
                WorkspaceRootId::Notes,
                &WorkspaceRelativePath::parse("2026_11_04.md").expect("document path"),
            )
            .expect("record path");
            let map = MemoIdentityMap::decode(
                &fs::read(ctx.workspace_path.join(record_path.as_str())).expect("identity record"),
            )
            .expect("identity map");
            assert!(map.retired_ids().contains(&first.memo_id));
            let failure = session
                .update_memo(lomo_application::UpdateMemoRequest {
                    operation_id: OperationId::parse("update-removed-id").expect("operation"),
                    memo_id: first.memo_id,
                    expected_document_fingerprint: first.commit_result.file_fingerprint,
                    content: "must not return".to_owned(),
                    pending_promotes: Vec::new(),
                })
                .expect_err("removed identity stays absent");
            assert_eq!(failure.code(), "memo_not_found");
            assert_eq!(
                fs::read_to_string(ctx.workspace_path.join("2026_11_04.md")).expect("document"),
                bytes
            );
        }
    }
}
