//! Behavior Contract
//! Capability: reminder plan from Markdown tokens and `RecordFired` token rewrite (`.1`).
//! Scenarios: `@2026-07-20-10:45x2rd` first fire becomes `.1` without rewriting other text.
//! Observable outcomes: planned catch-up/future alarms, updated token, preserved surrounding text.
//! TDD proof: session reminder APIs did not exist.
//! Excludes: TUI process timers and Android `AlarmManager`.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed on missing facts"
)]
mod tests {
    use std::sync::Arc;

    use lomo_application::{
        CreateMemoRequest, FireReminderRequest, WorkspaceSession, WorkspaceSessionConfig,
    };
    use lomo_core::{CapabilityToken, OperationId, RelativeWorkspacePath};
    use lomo_platform_fs::PosixPlatformActionExecutor;
    use lomo_workspace::WorkspaceRootId;
    use tempfile::tempdir;

    #[test]
    fn first_fire_advances_repeat_token_and_preserves_surrounding_text() {
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
        let created = session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("rem").expect("op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_07_20.md").expect("path")),
                time_token: Some("10:45:00".to_owned()),
                content: "keep me @2026-07-20-10:45x2rd and this".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create");
        // 2026-07-20 11:45:00 UTC, one hour after `@2026-07-20-10:45`.
        let now = 1_784_547_900_000;
        let plan = session.reminder_plan(Some(now)).expect("plan");
        assert!(
            plan.alarms.iter().any(|alarm| alarm.is_catch_up),
            "overdue reminder must produce a catch-up alarm: {:?}",
            plan.alarms
        );
        let opaque = plan.alarms.first().expect("alarm").opaque_id.clone();
        session
            .record_reminder_fired(FireReminderRequest {
                operation_id: OperationId::parse("fire").expect("op"),
                memo_id: created.memo_id.clone(),
                opaque_id: opaque,
            })
            .expect("fire");
        let body = session
            .get_memo(&created.memo_id)
            .expect("get")
            .expect("found")
            .body;
        assert!(body.contains("keep me"));
        assert!(body.contains("and this"));
        assert!(
            body.contains(".1"),
            "token should record first fire: {body}"
        );
        assert!(!body.contains("@2026-07-20-10:45x2rd "));
    }
}
