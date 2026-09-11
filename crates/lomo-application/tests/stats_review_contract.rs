//! Behavior Contract
//! Capability: timezone-sliced daily review candidates and session statistics from projection facts.
//! Scenarios: a UTC pre-dawn memo falls on the previous civil date in `America/Los_Angeles`;
//! completing review excludes it; statistics count created memos.
//! Observable outcomes: candidate pools, exclusion, nonzero totals.
//! TDD proof: session review/statistics APIs did not exist.
//! Excludes: Android paging UI.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed on missing facts"
)]
mod tests {
    use std::sync::Arc;

    use lomo_application::calendar::CivilDate;
    use lomo_application::{
        CreateMemoRequest, StatisticsSnapshot, WorkspaceSession, WorkspaceSessionConfig,
    };
    use lomo_core::{CapabilityToken, OperationId, RelativeWorkspacePath};
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
    }

    fn open() -> Ctx {
        let workspace = tempdir().expect("ws");
        let state = tempdir().expect("st");
        let cache = tempdir().expect("ca");
        let runtime = tempdir().expect("rt");
        let exchange = tempdir().expect("ex");
        let executor = Arc::new(PosixPlatformActionExecutor::new(exchange.path()).expect("exec"));
        let capability = CapabilityToken::parse("notes").expect("cap");
        executor
            .bind_root(capability.clone(), workspace.path())
            .expect("bind");
        let session = WorkspaceSession::open(
            WorkspaceSessionConfig {
                capability,
                root_id: WorkspaceRootId::Notes,
                time_zone: "UTC".to_owned(),
                date_format: lomo_application::calendar::DateFormat::default(),
                state_dir: state.path().to_path_buf(),
                cache_dir: cache.path().to_path_buf(),
                runtime_dir: runtime.path().to_path_buf(),
                exchange_dir: exchange.path().to_path_buf(),
            },
            executor,
        )
        .expect("open");
        Ctx {
            session,
            _workspace: workspace,
            _state: state,
            _cache: cache,
            _runtime: runtime,
            _exchange: exchange,
        }
    }

    #[test]
    fn review_splits_by_timezone_and_excludes_completed_notes() {
        let ctx = open();
        let created = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("dawn").expect("op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_09_10.md").expect("path")),
                time_token: Some("00:30:00".to_owned()),
                content: "pre-dawn UTC memo".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create");
        let la = CivilDate::new(2026, 9, 9).expect("la date");
        let shanghai = CivilDate::new(2026, 9, 10).expect("sh date");
        let west = ctx
            .session
            .review_candidates("America/Los_Angeles", la)
            .expect("west");
        let east = ctx
            .session
            .review_candidates("Asia/Shanghai", shanghai)
            .expect("east");
        assert!(
            west.iter()
                .any(|item| item.memo_id == created.memo_id.as_str())
        );
        assert!(
            east.iter()
                .any(|item| item.memo_id == created.memo_id.as_str())
        );
        ctx.session
            .complete_review("America/Los_Angeles", la, &created.memo_id)
            .expect("complete");
        let west_after = ctx
            .session
            .review_candidates("America/Los_Angeles", la)
            .expect("west after");
        assert!(
            west_after
                .iter()
                .all(|item| item.memo_id != created.memo_id.as_str())
        );
        let stats = ctx
            .session
            .statistics(&StatisticsSnapshot::new(
                "UTC",
                CivilDate::new(2026, 9, 10).expect("as of"),
            ))
            .expect("stats");
        assert!(stats.total_memos >= 1);
    }
}
