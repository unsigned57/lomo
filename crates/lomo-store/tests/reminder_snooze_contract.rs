//! adversarial-audit: snooze budget is cumulative across expired bindings (durable
//! self-DoS after 1024 lifetime snoozes), and catch-up occurrence identity embeds the plan
//! clock rather than the missed instant, so every re-plan churns the `PendingIntent` identity.
//! Third probe: a fully-consumed non-recurring token (`fired == repeat`, no `.done`) still
//! yields a catch-up alarm because planning keys exhaustion on `done` only.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "adversarial probes fail closed with panics on missing facts"
)]
mod tests {
    use lomo_store::{
        ReminderCommand, ReminderQuery, ReminderSessionInput, SnoozeStore, TimeZoneContext,
        apply_reminder_command, query_reminder_plan,
    };
    use tempfile::tempdir;

    const GEN: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    fn utc_zone() -> TimeZoneContext {
        TimeZoneContext {
            zone_id: "UTC".to_owned(),
            base_offset_secs: 0,
            transitions: Vec::new(),
            coverage_start_utc_ms: 0,
            coverage_end_utc_ms: i64::MAX / 2,
        }
    }

    fn session(
        token: &str,
        due: &str,
        repeat: u32,
        fired: u32,
        done: bool,
    ) -> ReminderSessionInput {
        ReminderSessionInput {
            opaque_id: "reminder:probe".to_owned(),
            memo_identity: "2026-01-01_00:00:00_0".to_owned(),
            memo_revision: "rev".to_owned(),
            token: token.to_owned(),
            due_at_local: due.to_owned(),
            repeat_count: repeat,
            fired_count: fired,
            done,
            interval_minutes: 10,
            recurrence_code: String::new(),
        }
    }

    /// The `MAX_SNOOZE_ENTRIES` bound counts *stored keys*, and expired bindings are never
    /// pruned from `entries`. A device that accumulated 1024 lifetime snoozes refuses every
    /// subsequent snooze forever — durable state survives restarts, so there is no recovery
    /// short of `recover()` wiping all bindings. Expected-desired: expiry frees the slot.
    #[test]
    fn expired_snooze_bindings_must_not_exhaust_the_durable_budget() {
        let dir = tempdir().expect("dir");
        let mut store = SnoozeStore::open_app_private(dir.path()).expect("open");
        // 1024 lifetime snoozes, all long expired (until = 1 ms after epoch).
        for i in 0..1024_u32 {
            apply_reminder_command(
                &ReminderCommand::Snooze {
                    opaque_id: format!("reminder:{i}"),
                    workspace_generation: GEN.to_owned(),
                    snooze_until_utc_ms: 1,
                },
                &mut store,
            )
            .expect("expired binding still consumes budget");
        }
        // A brand-new snooze must succeed: every stored binding is expired dead weight.
        apply_reminder_command(
            &ReminderCommand::Snooze {
                opaque_id: "reminder:fresh".to_owned(),
                workspace_generation: GEN.to_owned(),
                snooze_until_utc_ms: i64::MAX / 4,
            },
            &mut store,
        )
        .expect("budget exhausted by expired bindings — durable self-DoS");
    }

    /// Catch-up occurrence identity is `{gen}\u{1f}{opaque}\u{1f}{now}` — the plan clock, not
    /// the missed trigger instant. Two consecutive plans for the same overdue reminder mint
    /// different occurrence ids, so the ledger treats the pending alarm as stale and
    /// cancel+reschedules the `PendingIntent` on every re-plan. Expected-desired: the catch-up
    /// occurrence is keyed on the missed instant and is stable across plans.
    #[test]
    fn catchup_occurrence_identity_must_be_stable_across_plans() {
        let snooze = SnoozeStore::memory();
        let query = |now: i64| ReminderQuery {
            now_utc_ms: now,
            zone: utc_zone(),
            sessions: vec![session(
                "@2024-01-01-08:00",
                "2024-01-01-08:00",
                1,
                0,
                false,
            )],
            rolling_window: 64,
            workspace_generation: GEN.to_owned(),
        };
        let first = query_reminder_plan(&query(1_800_000_000_000), &snooze).expect("plan1");
        let second = query_reminder_plan(&query(1_800_000_060_000), &snooze).expect("plan2");
        let first_occ = &first.alarms.first().expect("alarm1").occurrence_id;
        let second_occ = &second.alarms.first().expect("alarm2").occurrence_id;
        assert!(
            first.alarms.first().expect("alarm1").is_catch_up
                && second.alarms.first().expect("alarm2").is_catch_up
        );
        assert_eq!(
            first_occ, second_occ,
            "catch-up occurrence identity must not depend on the plan clock"
        );
    }

    /// `@...x2.2` (fired == repeat, `.done` absent) is accepted by the strict grammar but is a
    /// fully-consumed reminder. `plan_session_triggers` only checks `done`, so it emits a
    /// catch-up alarm for a reminder that can never legitimately fire again — the platform
    /// arms a real `PendingIntent` whose only defense is the Kotlin-side `isExhausted` guard.
    #[test]
    fn exhausted_oneshot_without_done_flag_must_not_emit_catchup() {
        let snooze = SnoozeStore::memory();
        let query = ReminderQuery {
            now_utc_ms: 1_800_000_000_000,
            zone: utc_zone(),
            sessions: vec![session(
                "@2024-01-01-08:00x2.2",
                "2024-01-01-08:00",
                2,
                2,
                false,
            )],
            rolling_window: 64,
            workspace_generation: GEN.to_owned(),
        };
        let plan = query_reminder_plan(&query, &snooze).expect("plan");
        assert!(
            plan.alarms.is_empty(),
            "fired == repeat must be terminal even without .done: {:?}",
            plan.alarms
        );
    }
}
