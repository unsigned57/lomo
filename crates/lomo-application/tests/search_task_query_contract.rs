//! Behavior Contract
//! Capability: dual-mode search (Unicode fulltext + pinyin fuzzy) with epoch cancellation,
//! and Markdown task aggregation/toggle through the shared session.
//! Scenarios: `bdlcc` ranks 八达岭长城; stale epochs are discarded; `- [ ]` aggregates and toggles.
//! Observable outcomes: ordered hits, discarded epochs, `[x]` in source Markdown.
//! TDD proof: search/task session APIs did not exist before this package.
//! Excludes: Android UI, TUI rendering, network.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::indexing_slicing,
    reason = "contract tests fail closed on missing facts"
)]
mod tests {
    use std::sync::Arc;

    use lomo_application::{
        CreateMemoRequest, SearchMode, SearchOutcome, SearchRequest, ToggleTaskRequest,
        WorkspaceSession, WorkspaceSessionConfig,
    };
    use lomo_core::{CapabilityToken, OperationId, PageSize, RelativeWorkspacePath};
    use lomo_platform_fs::PosixPlatformActionExecutor;
    use lomo_workspace::WorkspaceRootId;
    use tempfile::tempdir;

    struct Ctx {
        session: WorkspaceSession,
        _workspace: tempfile::TempDir,
        _state: tempfile::TempDir,
        _cache: tempfile::TempDir,
        _runtime: tempfile::TempDir,
        _exchange: tempfile::TempDir,
        workspace_path: std::path::PathBuf,
    }

    fn open_session() -> Ctx {
        let workspace = tempdir().expect("workspace");
        let state = tempdir().expect("state");
        let cache = tempdir().expect("cache");
        let runtime = tempdir().expect("runtime");
        let exchange = tempdir().expect("exchange");
        let executor = Arc::new(PosixPlatformActionExecutor::new(exchange.path()).expect("exec"));
        let capability = CapabilityToken::parse("notes").expect("cap");
        executor
            .bind_root(capability.clone(), workspace.path())
            .expect("bind");
        let config = WorkspaceSessionConfig {
            capability,
            root_id: WorkspaceRootId::Notes,
            time_zone: "UTC".to_owned(),
            date_format: lomo_application::calendar::DateFormat::default(),
            state_dir: state.path().to_path_buf(),
            cache_dir: cache.path().to_path_buf(),
            runtime_dir: runtime.path().to_path_buf(),
            exchange_dir: exchange.path().to_path_buf(),
        };
        let session = WorkspaceSession::open(config, executor).expect("open");
        Ctx {
            workspace_path: workspace.path().to_path_buf(),
            session,
            _workspace: workspace,
            _state: state,
            _cache: cache,
            _runtime: runtime,
            _exchange: exchange,
        }
    }

    fn op(raw: &str) -> OperationId {
        OperationId::parse(raw).expect("op")
    }

    fn create(
        ctx: &Ctx,
        id: &str,
        path: &str,
        time: &str,
        content: &str,
        fingerprint: Option<String>,
    ) {
        ctx.session
            .create_memo(CreateMemoRequest {
                operation_id: op(id),
                relative_path: Some(RelativeWorkspacePath::parse(path).expect("path")),
                time_token: Some(time.to_owned()),
                content: content.to_owned(),
                expected_document_fingerprint: fingerprint,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create");
    }

    #[test]
    fn fuzzy_pinyin_initials_rank_badaling_and_stale_epochs_are_discarded() {
        let ctx = open_session();
        let first = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: op("c1"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_09_10.md").expect("path")),
                time_token: Some("10:00:00".to_owned()),
                content: "八达岭长城".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("wall");
        create(
            &ctx,
            "c2",
            "2026_09_10.md",
            "10:01:00",
            "ordinary pineapple note",
            Some(first.commit_result.file_fingerprint),
        );

        ctx.session
            .search(&SearchRequest {
                query_epoch: 1,
                mode: SearchMode::Fuzzy,
                text: "zzzz".to_owned(),
                cursor: None,
                page_size: PageSize::new(16).expect("page"),
            })
            .expect("stale search");
        let fresh = ctx
            .session
            .search(&SearchRequest {
                query_epoch: 2,
                mode: SearchMode::Fuzzy,
                text: "bdlcc".to_owned(),
                cursor: None,
                page_size: PageSize::new(16).expect("page"),
            })
            .expect("fuzzy");
        let SearchOutcome::Ready(page) = fresh else {
            panic!("fresh epoch must produce hits");
        };
        assert!(!page.items.is_empty());
        assert!(page.items[0].summary.body_preview.contains("八达岭长城"));

        let discarded = ctx
            .session
            .search(&SearchRequest {
                query_epoch: 1,
                mode: SearchMode::Fuzzy,
                text: "zzzz".to_owned(),
                cursor: None,
                page_size: PageSize::new(16).expect("page"),
            })
            .expect("discard");
        assert!(matches!(
            discarded,
            SearchOutcome::Discarded { query_epoch: 1, .. }
        ));

        let fulltext = ctx
            .session
            .search(&SearchRequest {
                query_epoch: 3,
                mode: SearchMode::Fulltext,
                text: "八达岭".to_owned(),
                cursor: None,
                page_size: PageSize::new(16).expect("page"),
            })
            .expect("fulltext");
        let SearchOutcome::Ready(hits) = fulltext else {
            panic!("fulltext must be ready");
        };
        assert!(
            hits.items
                .iter()
                .any(|hit| hit.summary.body_preview.contains("八达岭长城"))
        );
    }

    #[test]
    fn task_aggregation_lists_and_toggles_markdown_checkboxes() {
        let ctx = open_session();
        let created = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: op("todo"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_09_10.md").expect("path")),
                time_token: Some("11:00:00".to_owned()),
                content: "- [ ] buy milk\n- [x] stretch".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("todo memo");
        let tasks = ctx.session.list_tasks().expect("tasks");
        assert_eq!(tasks.len(), 2);
        assert!(!tasks[0].done);
        assert_eq!(tasks[0].text, "buy milk");
        assert!(tasks[1].done);

        ctx.session
            .toggle_task(ToggleTaskRequest {
                operation_id: op("check"),
                memo_id: created.memo_id,
                line_index: 0,
                done: true,
            })
            .expect("toggle");
        let markdown =
            std::fs::read_to_string(ctx.workspace_path.join("2026_09_10.md")).expect("md");
        assert!(markdown.contains("- [x] buy milk"));
        let tasks = ctx.session.list_tasks().expect("tasks after");
        assert!(
            tasks
                .iter()
                .any(|task| task.text == "buy milk" && task.done)
        );
    }
}
