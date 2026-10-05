//! Behavior Contract
//! Capability: reminder plan from Markdown tokens and `RecordFired` token rewrite (`.1`).
//! Scenarios: `@2026-07-20-10:45x2rd` first fire becomes `.1` without rewriting other text.
//! Observable outcomes: planned catch-up/future alarms, updated token, preserved surrounding text.
//! TDD proof: session reminder APIs did not exist.
//! Excludes: TUI process timers and Android `AlarmManager`.
//!
//! The session no longer owns a dedicated record-fired entry point: the store's reminder
//! command plans the token mutation and the generic `update_memo` commits it, which is the
//! same composition the Android receiver performs.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed on missing facts"
)]
mod tests {
    use std::sync::Arc;

    use lomo_application::{
        CreateMemoRequest, UpdateMemoRequest, WorkspaceSession, WorkspaceSessionConfig,
    };
    use lomo_core::{CapabilityToken, OperationId, RelativeWorkspacePath};
    use lomo_platform_fs::FsPlatformActionExecutor;
    use lomo_store::{MemoSummary, ReminderCommand, ReminderSessionInput, SnoozeStore};
    use lomo_workspace::{ReminderReference, WorkspaceRootId};
    use tempfile::tempdir;

    struct Fixture {
        session: WorkspaceSession,
        state: tempfile::TempDir,
        _workspace: tempfile::TempDir,
        _cache: tempfile::TempDir,
        _runtime: tempfile::TempDir,
        _exchange: tempfile::TempDir,
    }

    impl Fixture {
        fn new() -> Self {
            let workspace = tempdir().expect("ws");
            let state = tempdir().expect("st");
            let cache = tempdir().expect("ca");
            let runtime = tempdir().expect("rt");
            let exchange = tempdir().expect("ex");
            let executor = Arc::new(FsPlatformActionExecutor::new(exchange.path()).expect("exec"));
            let capability = CapabilityToken::parse("notes").expect("cap");
            executor
                .bind_root(capability.clone(), workspace.path())
                .expect("bind");
            let session = WorkspaceSession::open(
                WorkspaceSessionConfig {
                    capability,
                    root_id: WorkspaceRootId::Notes,
                    workspace_generation: lomo_workspace::WorkspaceGenerationId::mint()
                        .expect("workspace generation"),
                    time_zone: "UTC".to_owned(),
                    date_format: lomo_application::calendar::DateFormat::default(),
                    state_dir: state.path().to_path_buf(),
                    cache_dir: cache.path().to_path_buf(),
                    runtime_dir: runtime.path().to_path_buf(),
                    exchange_dir: exchange.path().to_path_buf(),
                    media_stage_root: exchange.path().join("media-stage"),
                },
                executor,
            )
            .expect("open");
            Self {
                session,
                state,
                _workspace: workspace,
                _cache: cache,
                _runtime: runtime,
                _exchange: exchange,
            }
        }

        fn summary(&self, memo_id: &lomo_workspace::MemoId) -> MemoSummary {
            self.session
                .list_memos(&lomo_store::MemoQuery {
                    search_text: None,
                    filters: lomo_store::MemoFilters::default(),
                    sort: lomo_store::MemoSort::default(),
                })
                .expect("list")
                .items
                .into_iter()
                .find(|item| item.memo_id == memo_id.as_str())
                .expect("created memo must be listed")
        }

        /// Plans the `RecordFired` token mutation the Android receiver commits through
        /// `rewriteReminder` + `commitDocumentMutation`.
        fn plan_record_fired(&self, reminder: &ReminderReference) -> String {
            let mut snooze =
                SnoozeStore::open_app_private(self.state.path()).expect("snooze store");
            lomo_store::apply_reminder_command(
                &ReminderCommand::RecordFired {
                    session: ReminderSessionInput {
                        opaque_id: reminder.opaque_id.clone(),
                        memo_identity: reminder.memo_identity.clone(),
                        memo_revision: reminder.revision.clone(),
                        token: reminder.token.clone(),
                        due_at_local: reminder.due_at_local.clone(),
                        repeat_count: reminder.repeat_count,
                        fired_count: reminder.fired_count,
                        done: reminder.done,
                        interval_minutes: reminder.interval_minutes,
                        recurrence_code: reminder.recurrence_code.clone(),
                    },
                    expected_revision: reminder.revision.clone(),
                },
                &mut snooze,
            )
            .expect("record fired")
            .replacement_token
            .expect("record-fired must rewrite the token")
        }
    }

    #[test]
    fn first_fire_advances_repeat_token_and_preserves_surrounding_text() {
        let fixture = Fixture::new();
        let created = fixture
            .session
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
        let plan = fixture.session.reminder_plan(Some(now)).expect("plan");
        assert!(
            plan.alarms.iter().any(|alarm| alarm.is_catch_up),
            "overdue reminder must produce a catch-up alarm: {:?}",
            plan.alarms
        );
        let opaque = plan.alarms.first().expect("alarm").opaque_id.clone();
        let summary = fixture.summary(&created.memo_id);
        let reminder = summary
            .reminders
            .iter()
            .find(|item| item.opaque_id == opaque)
            .expect("planned alarm must resolve to a reminder");
        let replacement = fixture.plan_record_fired(reminder);
        let body = fixture
            .session
            .get_memo(&created.memo_id)
            .expect("get")
            .expect("found")
            .body;
        fixture
            .session
            .update_memo(UpdateMemoRequest {
                operation_id: OperationId::parse("fire").expect("op"),
                memo_id: created.memo_id.clone(),
                content: body.replacen(&reminder.token, &replacement, 1),
                expected_document_fingerprint: summary.file_fingerprint.clone(),
                pending_promotes: Vec::new(),
            })
            .expect("fire");
        let body = fixture
            .session
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
