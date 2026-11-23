// Dependency upgrade — Test Change Justification:
// Reason category: mechanical migration.
// Old behavior/assertion being replaced: old dependency API spelling and boolean empty checks.
// Why old assertion is no longer correct: updated APIs and Rust 1.99 diagnostics require explicit
// byte encoding, image geometry types and equality assertions that print failing collections.
// Coverage preserved by: the same scenarios, input bytes, expected values, ordering and failures.
// Why this is not fitting the test to the implementation: observable behavior and golden vectors
// are unchanged; only equivalent API/assertion syntax and fixture construction are migrated.
//
//! Test Change Justification: indexed reminder facts replace the all-memo summary walk;
//! walk-count assertions now require zero. Alarm, cache-hit and revision invalidation
//! assertions remain, and a separate valid-reminder scenario proves nonempty scheduling.
//! Behavior Contract
//! Capability: reminder plan from Markdown tokens and `RecordFired` token rewrite (`.1`),
//! with reminder session extraction sharing the revision-keyed query projection.
//! Scenarios: `@2026-07-20-10:45x2rd` first fire becomes `.1` without rewriting other text;
//! re-planning at an unchanged revision reuses the extracted sessions instead of re-walking.
//! Observable outcomes: planned catch-up/future alarms, updated token, preserved surrounding
//! text, build/hit counters proving reuse.
//! TDD proof: session reminder APIs did not exist; the reuse counters do not exist before
//! this change — the test does not compile.
//! Excludes: TUI process timers and Android `AlarmManager`.
//! Pure-plan/atomic-commit regression RED: the real Android reminder workflow wrote the token
//! outside the session transaction; restart produced `memo_identity_unresolved` (device-final-create
//! and recovery-shot evidence in reports/manual/reminder-alarm/). GREEN asserts
//! planning leaves source untouched, canonical commit survives cold reopen, stale tokens reject.
//! Test Change Justification: the unsafe write-then-project composition is replaced by the
//! existing session update transaction; source reference checks remain owned by the Rust parser.
//!
//! Alarm identity regression: Given a durable memo with a timestamp-based source locator,
//! when planning an alarm, then its memo identity resolves through the session projection.
//! TDD proof: `cargo test -p lomo-application --test reminder_plan_contract
//! planned_alarm_targets_the_durable_memo_identity --locked`; RED/GREEN logs in
//! reports/manual/reminder-alarm/identity-{red,green}.log.
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
        config: WorkspaceSessionConfig,
        executor: Arc<FsPlatformActionExecutor>,
        state: tempfile::TempDir,
        workspace: tempfile::TempDir,
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
            let config = WorkspaceSessionConfig {
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
            };
            let session_executor = Arc::clone(&executor);
            let session = WorkspaceSession::open(config.clone(), session_executor).expect("open");
            Self {
                session,
                config,
                executor,
                state,
                workspace,
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
        /// pure reminder planning + the canonical session memo update.
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
                        recurrence: reminder.recurrence,
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
    fn reminder_planning_does_not_walk_unrelated_memo_summaries() {
        let fixture = Fixture::new();
        fixture
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("rem-bounded").expect("operation"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_07_21.md").expect("path")),
                time_token: Some("10:45:00".to_owned()),
                content: "due @2026-07-21-10:45".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
                date_format: None,
                timestamp_format: None,
            })
            .expect("create reminder");
        fixture
            .session
            .settle_workspace()
            .expect("committed writes converge");
        let plan = fixture
            .session
            .reminder_plan(Some(1_784_547_900_000))
            .expect("plan");
        fixture
            .session
            .settle_workspace()
            .expect("committed writes converge");
        assert_ne!(plan.alarms, Vec::<lomo_application::PlannedAlarm>::new());
        assert_eq!(
            fixture
                .session
                .projection_query_stats()
                .expect("query evidence")
                .summary_walks,
            0,
            "alarm planning must not materialize memo pages or parse unrelated bodies"
        );
    }

    #[test]
    fn planned_alarm_targets_the_durable_memo_identity() {
        let fixture = Fixture::new();
        let created = fixture
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("alarm-identity").expect("op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_07_20.md").expect("path")),
                time_token: Some("10:45:00".to_owned()),
                content: "alarm identity @2026-07-20-10:45".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
                date_format: None,
                timestamp_format: None,
            })
            .expect("create");
        fixture
            .session
            .settle_workspace()
            .expect("committed writes converge");
        let plan = fixture
            .session
            .reminder_plan(Some(1_784_547_900_000))
            .expect("plan");
        fixture
            .session
            .settle_workspace()
            .expect("committed writes converge");
        let alarm = plan.alarms.first().expect("planned alarm");
        fixture
            .session
            .settle_workspace()
            .expect("committed writes converge");

        assert_eq!(alarm.memo_identity, created.memo_id.as_str());
        let target = lomo_workspace::MemoId::parse(&alarm.memo_identity).expect("durable memo id");
        let memo = fixture
            .session
            .get_memo(&target)
            .expect("get")
            .expect("alarm target exists");
        assert!(memo.body.contains("alarm identity"));
    }

    #[test]
    fn reminder_edit_planning_is_pure_and_session_commit_survives_cold_reopen() {
        let fixture = Fixture::new();
        let created = fixture
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("rem-atomic-create").expect("op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_07_20.md").expect("path")),
                time_token: Some("10:45:00".to_owned()),
                content: "保留前文 @2026-07-20-10:45x2#0123456789abcdef and tail".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
                date_format: None,
                timestamp_format: None,
            })
            .expect("create");
        fixture
            .session
            .settle_workspace()
            .expect("committed writes converge");
        let summary = fixture.summary(&created.memo_id);
        let reference = summary.reminders.first().expect("reminder");
        fixture
            .session
            .settle_workspace()
            .expect("committed writes converge");
        let replacement = fixture.plan_record_fired(reference);
        fixture
            .session
            .settle_workspace()
            .expect("committed writes converge");
        let original =
            std::fs::read(fixture.workspace.path().join("2026_07_20.md")).expect("source");
        let plan = fixture
            .session
            .plan_reminder_rewrite(
                &created.memo_id,
                &reference.opaque_id,
                &reference.token,
                &replacement,
            )
            .expect("pure edit plan");
        fixture
            .session
            .settle_workspace()
            .expect("committed writes converge");
        assert_eq!(plan.memo_id, created.memo_id.as_str());
        assert_eq!(
            std::fs::read(fixture.workspace.path().join("2026_07_20.md")).expect("source"),
            original
        );
        assert_eq!(
            fixture
                .summary(&created.memo_id)
                .reminders
                .first()
                .expect("reminder")
                .fired_count,
            0
        );
        assert!(plan.content.contains("保留前文"));
        assert!(plan.content.contains("and tail"));
        assert!(plan.content.contains("x2.1#0123456789abcdef"));
        fixture
            .session
            .update_memo(UpdateMemoRequest {
                operation_id: OperationId::parse("rem-atomic-fire").expect("op"),
                memo_id: created.memo_id.clone(),
                content: plan.content,
                expected_document_fingerprint: plan.expected_fingerprint,
                pending_promotes: Vec::new(),
            })
            .expect("session commit");
        fixture
            .session
            .settle_workspace()
            .expect("committed writes converge");
        drop(fixture.session);
        let reopened = WorkspaceSession::open(fixture.config, fixture.executor)
            .expect("cold reopen must retain identity");
        let memo = reopened
            .get_memo(&created.memo_id)
            .expect("query")
            .expect("durable target");
        assert!(memo.body.contains("x2.1#0123456789abcdef"));
        assert_eq!(
            reopened
                .reminder_plan(Some(1_784_547_900_000))
                .expect("next plan")
                .alarms
                .first()
                .expect("next alarm")
                .memo_identity,
            created.memo_id.as_str()
        );
    }

    #[test]
    fn stale_reminder_token_is_rejected_without_writing_source() {
        let fixture = Fixture::new();
        let created = fixture
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("rem-stale-create").expect("op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_07_20.md").expect("path")),
                time_token: Some("10:45:00".to_owned()),
                content: "keep @2026-07-20-10:45x2#0123456789abcdef".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
                date_format: None,
                timestamp_format: None,
            })
            .expect("create");
        fixture
            .session
            .settle_workspace()
            .expect("committed writes converge");
        let summary = fixture.summary(&created.memo_id);
        let reference = summary.reminders.first().expect("reminder");
        fixture
            .session
            .settle_workspace()
            .expect("committed writes converge");
        fixture
            .session
            .settle_workspace()
            .expect("committed writes converge");
        let original =
            std::fs::read(fixture.workspace.path().join("2026_07_20.md")).expect("source");
        let error = fixture
            .session
            .plan_reminder_rewrite(
                &created.memo_id,
                &reference.opaque_id,
                "stale token",
                "@2026-07-20-10:45x2.1#0123456789abcdef",
            )
            .expect_err("stale token must fail");
        assert!(error.to_string().contains("stale_snapshot"));
        assert_eq!(
            std::fs::read(fixture.workspace.path().join("2026_07_20.md")).expect("source"),
            original
        );
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
                date_format: None,
                timestamp_format: None,
            })
            .expect("create");
        fixture
            .session
            .settle_workspace()
            .expect("committed writes converge");
        // 2026-07-20 11:45:00 UTC, one hour after `@2026-07-20-10:45`.
        let now = 1_784_547_900_000;
        let plan = fixture.session.reminder_plan(Some(now)).expect("plan");
        fixture
            .session
            .settle_workspace()
            .expect("committed writes converge");
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
        fixture
            .session
            .settle_workspace()
            .expect("committed writes converge");
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

    #[test]
    fn reminder_plan_reuses_the_indexed_reminder_projection_per_revision() {
        let fixture = Fixture::new();
        fixture
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("rem-share").expect("op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_07_21.md").expect("path")),
                time_token: Some("10:45:00".to_owned()),
                content: "shared walk @2026-07-21-10:45x1d".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
                date_format: None,
                timestamp_format: None,
            })
            .expect("create");
        fixture
            .session
            .settle_workspace()
            .expect("committed writes converge");
        let now = 1_784_547_900_000_i64;
        let first = fixture.session.reminder_plan(Some(now)).expect("plan");
        fixture
            .session
            .settle_workspace()
            .expect("committed writes converge");
        let probe = fixture.session.projection_query_stats().expect("probe");
        assert_eq!(probe.reminder_builds, 1);
        assert_eq!(probe.summary_walks, 0);

        let second = fixture
            .session
            .reminder_plan(Some(now))
            .expect("plan again");
        fixture
            .session
            .settle_workspace()
            .expect("committed writes converge");
        assert_eq!(
            first.alarms.len(),
            second.alarms.len(),
            "a cached session set produces the identical plan"
        );
        let probe = fixture.session.projection_query_stats().expect("probe");
        fixture
            .session
            .settle_workspace()
            .expect("committed writes converge");
        assert_eq!(
            probe.reminder_builds, 1,
            "re-entry reuses the sessions view"
        );
        assert_eq!(probe.reminder_hits, 1);
        assert_eq!(
            probe.summary_walks, 0,
            "reminder planning never walks unrelated memo summaries"
        );

        // A committed projection write moves the revision — the next plan rebuilds.
        fixture
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("rem-share-2").expect("op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_07_22.md").expect("path")),
                time_token: Some("10:45:00".to_owned()),
                content: "plain note".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
                date_format: None,
                timestamp_format: None,
            })
            .expect("create second");
        fixture
            .session
            .settle_workspace()
            .expect("committed writes converge");
        fixture.session.reminder_plan(Some(now)).expect("replan");
        fixture
            .session
            .settle_workspace()
            .expect("committed writes converge");
        let probe = fixture.session.projection_query_stats().expect("probe");
        assert_eq!(probe.reminder_builds, 2);
        assert_eq!(probe.summary_walks, 0);
    }
}
