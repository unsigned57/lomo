//! Behavior Contract — P3-07 reminder core
//!
//! - Unit under test: `lomo_store::reminder` (DST resolve, plan, catch-up, snooze, commands)
//! - Owning layer: `lomo-store`
//! - Priority tier: P0
//! - Capability: own recurrence/fired/done/next-trigger, floating local wall time with platform
//!   zone transitions, at-most-one catch-up per session, app-private snooze binding, and
//!   mark-done/record-fired token mutation planning without rewriting body on snooze.
//!
//! Scenarios:
//! - Given a US spring-forward DST gap wall time, when resolved, then the first valid UTC instant
//!   after the gap is returned.
//! - Given a US fall-back DST overlap wall time, when resolved, then the earlier UTC instant is
//!   returned.
//! - Given a daily recurrence missed for several days, when the plan rebuilds, then at most one
//!   catch-up fire is emitted for that session before the next future trigger.
//! - Given snooze bound to workspace generation + opaque id + memo revision, when plan rebuilds
//!   with the same binding, then the snooze instant is used; with a different generation/revision,
//!   snooze does not apply.
//! - Given mark-done / record-fired, when applied, then a Markdown replacement token is planned;
//!   given snooze, when applied, then `replacement_token` is None and body rewrite is forbidden.
//! - Given snooze open path under `.lomo`, when opened, then validation fails closed.
//!
//! Observable outcomes: UTC instants, planned alarms (`is_catch_up`), replacement tokens, scopes,
//! structured `LomoError` codes.
//!
//! TDD proof: RED — package has no `reminder_core_contract` target / reminder symbols before P3-07.
//!
//! Excludes: Android `AlarmManager` delivery (P3-08), `BoltFFI` wiring (P3-09), Room cutover
//! (P3-10).

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::unwrap_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use std::path::PathBuf;

    use lomo_store::{
        PlannedAlarm, ReminderCommand, ReminderQuery, ReminderSessionInput, SnoozeStore, Store,
        TimeZoneContext, ZoneTransition, apply_reminder_command, query_reminder_plan,
        resolve_floating_local_to_utc_ms,
    };
    use tempfile::tempdir;

    /// `America/New_York` 2024 spring + fall transitions (platform `ZoneRules` shape).
    fn new_york_2024() -> TimeZoneContext {
        TimeZoneContext {
            zone_id: "America/New_York".to_owned(),
            base_offset_secs: -5 * 3600,
            transitions: vec![
                ZoneTransition {
                    transition_utc_ms: 1_710_054_000_000,
                    offset_before_secs: -5 * 3600,
                    offset_after_secs: -4 * 3600,
                },
                ZoneTransition {
                    transition_utc_ms: 1_730_613_600_000,
                    offset_before_secs: -4 * 3600,
                    offset_after_secs: -5 * 3600,
                },
            ],
        }
    }

    struct SessionSpec {
        opaque: &'static str,
        token: &'static str,
        due: &'static str,
        repeat: u32,
        fired: u32,
        done: bool,
        interval: u32,
        recurrence: &'static str,
        revision: &'static str,
    }

    fn session(spec: &SessionSpec) -> ReminderSessionInput {
        ReminderSessionInput {
            opaque_id: spec.opaque.to_owned(),
            memo_identity: "2026-07-20_10:00:00_0".to_owned(),
            memo_revision: spec.revision.to_owned(),
            token: spec.token.to_owned(),
            due_at_local: spec.due.to_owned(),
            repeat_count: spec.repeat,
            fired_count: spec.fired,
            done: spec.done,
            interval_minutes: spec.interval,
            recurrence_code: spec.recurrence.to_owned(),
        }
    }

    fn first_alarm(plan_alarms: &[PlannedAlarm]) -> &PlannedAlarm {
        plan_alarms
            .first()
            .expect("plan must contain at least one alarm")
    }

    #[test]
    fn store_handle_reminder_plan_and_commands_delegate() {
        let dir = tempdir().expect("tempdir");
        let store = Store::open(dir.path()).expect("open");
        let zone = new_york_2024();
        let s = session(&SessionSpec {
            opaque: "rem-store",
            token: "@2024-06-01-15:00",
            due: "2024-06-01-15:00",
            repeat: 1,
            fired: 0,
            done: false,
            interval: 10,
            recurrence: "",
            revision: "rev-s",
        });
        let due = resolve_floating_local_to_utc_ms("2024-06-01-15:00", &zone).expect("due");
        let mut snooze = SnoozeStore::memory();
        let plan = store
            .query_reminder_plan(
                &ReminderQuery {
                    now_utc_ms: due - 60_000,
                    zone,
                    sessions: vec![s.clone()],
                    rolling_window: 4,
                    workspace_generation: 1,
                },
                &snooze,
            )
            .expect("store plan");
        assert_eq!(first_alarm(&plan.alarms).trigger_at_utc_ms, due);
        let done = store
            .apply_reminder_command(
                &ReminderCommand::MarkDone {
                    session: s,
                    expected_revision: "rev-s".to_owned(),
                },
                &mut snooze,
            )
            .expect("store mark done");
        assert!(done.replacement_token.is_some());
    }

    #[test]
    fn invalid_and_leap_local_times_fail_closed_or_resolve() {
        let zone = new_york_2024();
        for bad in [
            "not-a-date",
            "2024-13-01-09:00",
            "2024-02-30-09:00",
            "2024-04-31-09:00",
            "2024-01-01-24:00",
            "2024-01-01-12:60",
            "2024/01/01-09:00",
        ] {
            let err = resolve_floating_local_to_utc_ms(bad, &zone).expect_err(bad);
            assert_eq!(
                err.code(),
                "invalid_due_at_local",
                "bad={bad} code={}",
                err.code()
            );
        }
        // Leap day is valid.
        let leap = resolve_floating_local_to_utc_ms("2024-02-29-12:00", &zone).expect("leap");
        assert!(leap > 0);
        // Non-leap Feb 29 fails.
        let err =
            resolve_floating_local_to_utc_ms("2023-02-29-12:00", &zone).expect_err("non-leap");
        assert_eq!(err.code(), "invalid_due_at_local");
    }

    #[test]
    fn clear_snooze_and_done_session_skip_plan() {
        let zone = new_york_2024();
        let s = session(&SessionSpec {
            opaque: "rem-clear",
            token: "@2024-06-01-15:00",
            due: "2024-06-01-15:00",
            repeat: 1,
            fired: 0,
            done: false,
            interval: 10,
            recurrence: "",
            revision: "rev-c",
        });
        let mut snooze = SnoozeStore::memory();
        let due = resolve_floating_local_to_utc_ms("2024-06-01-15:00", &zone).expect("due");
        apply_reminder_command(
            &ReminderCommand::Snooze {
                opaque_id: "rem-clear".to_owned(),
                memo_identity: s.memo_identity.clone(),
                memo_revision: "rev-c".to_owned(),
                workspace_generation: 2,
                snooze_until_utc_ms: due + 9_000_000,
            },
            &mut snooze,
        )
        .expect("snooze");
        apply_reminder_command(
            &ReminderCommand::ClearSnooze {
                opaque_id: "rem-clear".to_owned(),
                memo_identity: s.memo_identity.clone(),
                memo_revision: "rev-c".to_owned(),
                workspace_generation: 2,
            },
            &mut snooze,
        )
        .expect("clear");
        let plan = query_reminder_plan(
            &ReminderQuery {
                now_utc_ms: due - 60_000,
                zone: zone.clone(),
                sessions: vec![s.clone()],
                rolling_window: 4,
                workspace_generation: 2,
            },
            &snooze,
        )
        .expect("plan after clear");
        assert_eq!(
            first_alarm(&plan.alarms).trigger_at_utc_ms,
            due,
            "cleared snooze must fall back to due"
        );

        let mut done_session = s;
        done_session.done = true;
        let plan_done = query_reminder_plan(
            &ReminderQuery {
                now_utc_ms: due,
                zone,
                sessions: vec![done_session],
                rolling_window: 4,
                workspace_generation: 2,
            },
            &snooze,
        )
        .expect("done plan");
        assert!(
            plan_done.alarms.is_empty(),
            "done sessions must not schedule: {:?}",
            plan_done.alarms
        );
    }

    #[test]
    fn dst_gap_resolves_to_first_valid_instant() {
        let zone = new_york_2024();
        let utc = resolve_floating_local_to_utc_ms("2024-03-10-02:30", &zone).expect("gap resolve");
        assert_eq!(utc, 1_710_054_000_000);
    }

    #[test]
    fn dst_overlap_chooses_earlier_instant() {
        let zone = new_york_2024();
        let utc =
            resolve_floating_local_to_utc_ms("2024-11-03-01:30", &zone).expect("overlap resolve");
        assert_eq!(utc, 1_730_611_800_000);
    }

    #[test]
    fn ordinary_local_time_resolves_with_active_offset() {
        let zone = new_york_2024();
        let utc = resolve_floating_local_to_utc_ms("2024-07-01-12:00", &zone).expect("summer");
        assert_eq!(utc, 1_719_849_600_000);
        let utc = resolve_floating_local_to_utc_ms("2024-01-15-12:00", &zone).expect("winter");
        assert_eq!(utc, 1_705_338_000_000);
    }

    #[test]
    fn catch_up_storm_prevention_emits_at_most_one_catch_up_per_session() {
        let zone = new_york_2024();
        let s = session(&SessionSpec {
            opaque: "rem-daily",
            token: "@2024-01-01-09:00rd",
            due: "2024-01-01-09:00",
            repeat: 1,
            fired: 0,
            done: false,
            interval: 10,
            recurrence: "d",
            revision: "rev-a",
        });
        let now = resolve_floating_local_to_utc_ms("2024-01-10-12:00", &zone).expect("now");
        let plan = query_reminder_plan(
            &ReminderQuery {
                now_utc_ms: now,
                zone,
                sessions: vec![s],
                rolling_window: 16,
                workspace_generation: 1,
            },
            &SnoozeStore::memory(),
        )
        .expect("plan");
        let session_alarms: Vec<&PlannedAlarm> = plan
            .alarms
            .iter()
            .filter(|a| a.opaque_id == "rem-daily")
            .collect();
        let catch_ups = session_alarms.iter().filter(|a| a.is_catch_up).count();
        assert_eq!(catch_ups, 1, "at most one catch-up: {session_alarms:?}");
        assert!(
            session_alarms.len() <= 2,
            "storm: too many alarms {session_alarms:?}"
        );
        assert!(
            session_alarms.iter().any(|a| a.is_catch_up),
            "must include catch-up"
        );
    }

    #[test]
    fn multi_fire_missed_emits_one_catch_up_not_all_repeats() {
        let zone = new_york_2024();
        let s = session(&SessionSpec {
            opaque: "rem-multi",
            token: "@2024-01-01-09:00x3i10",
            due: "2024-01-01-09:00",
            repeat: 3,
            fired: 0,
            done: false,
            interval: 10,
            recurrence: "",
            revision: "rev-m",
        });
        let now = resolve_floating_local_to_utc_ms("2024-01-01-12:00", &zone).expect("now");
        let plan = query_reminder_plan(
            &ReminderQuery {
                now_utc_ms: now,
                zone,
                sessions: vec![s],
                rolling_window: 16,
                workspace_generation: 1,
            },
            &SnoozeStore::memory(),
        )
        .expect("plan");
        let catch_ups = plan.alarms.iter().filter(|a| a.is_catch_up).count();
        assert_eq!(catch_ups, 1, "must not storm multi-fire: {:?}", plan.alarms);
        assert!(plan.alarms.len() <= 2);
    }

    #[test]
    fn snooze_binds_to_generation_ref_and_revision() {
        let zone = new_york_2024();
        let s = session(&SessionSpec {
            opaque: "rem-snooze",
            token: "@2024-06-01-15:00",
            due: "2024-06-01-15:00",
            repeat: 1,
            fired: 0,
            done: false,
            interval: 10,
            recurrence: "",
            revision: "rev-1",
        });
        let due = resolve_floating_local_to_utc_ms("2024-06-01-15:00", &zone).expect("due");
        let now = due - 60_000;
        let mut snooze = SnoozeStore::memory();
        let until = due + 3_600_000;
        apply_reminder_command(
            &ReminderCommand::Snooze {
                opaque_id: "rem-snooze".to_owned(),
                memo_identity: s.memo_identity.clone(),
                memo_revision: "rev-1".to_owned(),
                workspace_generation: 7,
                snooze_until_utc_ms: until,
            },
            &mut snooze,
        )
        .expect("snooze");

        let plan_ok = query_reminder_plan(
            &ReminderQuery {
                now_utc_ms: now,
                zone: zone.clone(),
                sessions: vec![s.clone()],
                rolling_window: 8,
                workspace_generation: 7,
            },
            &snooze,
        )
        .expect("plan with binding");
        assert_eq!(first_alarm(&plan_ok.alarms).trigger_at_utc_ms, until);
        assert!(!first_alarm(&plan_ok.alarms).is_catch_up);

        let plan_gen = query_reminder_plan(
            &ReminderQuery {
                now_utc_ms: now,
                zone: zone.clone(),
                sessions: vec![s.clone()],
                rolling_window: 8,
                workspace_generation: 8,
            },
            &snooze,
        )
        .expect("plan other gen");
        assert_eq!(first_alarm(&plan_gen.alarms).trigger_at_utc_ms, due);

        let mut s2 = s;
        s2.memo_revision = "rev-2".to_owned();
        let plan_rev = query_reminder_plan(
            &ReminderQuery {
                now_utc_ms: now,
                zone,
                sessions: vec![s2],
                rolling_window: 8,
                workspace_generation: 7,
            },
            &snooze,
        )
        .expect("plan other rev");
        assert_eq!(first_alarm(&plan_rev.alarms).trigger_at_utc_ms, due);
    }

    #[test]
    fn mark_done_and_record_fired_plan_markdown_tokens_snooze_does_not() {
        let mut snooze = SnoozeStore::memory();
        let s = session(&SessionSpec {
            opaque: "rem-cmd",
            token: "@2024-06-01-15:00",
            due: "2024-06-01-15:00",
            repeat: 1,
            fired: 0,
            done: false,
            interval: 10,
            recurrence: "",
            revision: "rev-x",
        });
        let done = apply_reminder_command(
            &ReminderCommand::MarkDone {
                session: s.clone(),
                expected_revision: "rev-x".to_owned(),
            },
            &mut snooze,
        )
        .expect("done");
        assert_eq!(
            done.replacement_token.as_deref(),
            Some("@2024-06-01-15:00.done")
        );
        assert!(!done.snooze_only);

        let multi = session(&SessionSpec {
            opaque: "rem-fire",
            token: "@2024-06-01-15:00x3",
            due: "2024-06-01-15:00",
            repeat: 3,
            fired: 0,
            done: false,
            interval: 10,
            recurrence: "",
            revision: "rev-x",
        });
        let fired = apply_reminder_command(
            &ReminderCommand::RecordFired {
                session: multi,
                expected_revision: "rev-x".to_owned(),
            },
            &mut snooze,
        )
        .expect("fired");
        assert_eq!(
            fired.replacement_token.as_deref(),
            Some("@2024-06-01-15:00x3.1")
        );

        let snoozed = apply_reminder_command(
            &ReminderCommand::Snooze {
                opaque_id: "rem-cmd".to_owned(),
                memo_identity: s.memo_identity,
                memo_revision: "rev-x".to_owned(),
                workspace_generation: 1,
                snooze_until_utc_ms: 99,
            },
            &mut snooze,
        )
        .expect("snooze");
        assert!(snoozed.replacement_token.is_none());
        assert!(snoozed.snooze_only);
    }

    #[test]
    fn stale_revision_fails_closed() {
        let mut snooze = SnoozeStore::memory();
        let s = session(&SessionSpec {
            opaque: "rem-stale",
            token: "@2024-06-01-15:00",
            due: "2024-06-01-15:00",
            repeat: 1,
            fired: 0,
            done: false,
            interval: 10,
            recurrence: "",
            revision: "rev-current",
        });
        let err = apply_reminder_command(
            &ReminderCommand::MarkDone {
                session: s,
                expected_revision: "rev-old".to_owned(),
            },
            &mut snooze,
        )
        .expect_err("stale");
        assert_eq!(err.code(), "stale_snapshot");
    }

    #[test]
    fn snooze_must_not_open_under_lomo() {
        let dir = tempdir().unwrap();
        let lomo = dir.path().join(".lomo").join("state");
        std::fs::create_dir_all(&lomo).unwrap();
        let err = SnoozeStore::open_app_private(&lomo).expect_err("lomo forbidden");
        assert_eq!(err.code(), "snooze_in_lomo_forbidden");

        let private = dir.path().join("app_private");
        let store = SnoozeStore::open_app_private(&private).expect("private ok");
        assert!(store.snooze_until(1, "x", "r").is_none());
        assert!(
            !PathBuf::from(&private)
                .components()
                .any(|c| c.as_os_str() == ".lomo")
        );
    }

    #[test]
    fn future_single_shot_schedules_exact_due_without_catch_up() {
        let zone = new_york_2024();
        let s = session(&SessionSpec {
            opaque: "rem-future",
            token: "@2024-08-01-10:00",
            due: "2024-08-01-10:00",
            repeat: 1,
            fired: 0,
            done: false,
            interval: 10,
            recurrence: "",
            revision: "rev",
        });
        let due = resolve_floating_local_to_utc_ms("2024-08-01-10:00", &zone).expect("due");
        let now = due - 86_400_000;
        let plan = query_reminder_plan(
            &ReminderQuery {
                now_utc_ms: now,
                zone,
                sessions: vec![s],
                rolling_window: 4,
                workspace_generation: 1,
            },
            &SnoozeStore::memory(),
        )
        .expect("plan");
        assert_eq!(plan.alarms.len(), 1);
        assert_eq!(first_alarm(&plan.alarms).trigger_at_utc_ms, due);
        assert!(!first_alarm(&plan.alarms).is_catch_up);
    }
}
