//! Behavior Contract:
//! - Unit under test: `build_reminder_token`, `plan_reminder_token_mutation`
//! - Owning layer: lomo-workspace
//! - Priority tier: P1
//! - Capability: Owner constructs canonical reminder tokens for insert/done/fire so Kotlin is not
//!   a second grammar writer.
//!
//! Scenarios:
//! - Given typed insert fields, when built, then the token matches the strict stage-2 grammar.
//! - Given an active token, when `MarkDone`, then `.done` or recurrence advance is produced.
//! - Given a multi-fire token, when `RecordFired`, then fired count / done / recurrence advance.
//!
//! Observable outcomes: exact token strings and validation failures.
//!
//! Excludes: `AlarmManager` scheduling, Room, document patch application.

#![deny(unsafe_code)]

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_workspace::{
        ReminderTokenMutation, build_reminder_token, plan_reminder_token_mutation,
    };

    #[test]
    fn build_insert_token_matches_strict_grammar() {
        let token =
            build_reminder_token("2026-07-20-10:45", 1, 0, false, 10, "").expect("simple insert");
        assert_eq!(token, "@2026-07-20-10:45");

        let multi =
            build_reminder_token("2026-07-20-10:45", 3, 0, false, 15, "d").expect("multi insert");
        assert_eq!(multi, "@2026-07-20-10:45x3i15rd");
    }

    #[test]
    fn mark_done_sets_done_or_advances_recurrence() {
        let done =
            plan_reminder_token_mutation("@2026-07-20-10:45", ReminderTokenMutation::MarkDone)
                .expect("mark done");
        assert_eq!(done, "@2026-07-20-10:45.done");

        let advanced =
            plan_reminder_token_mutation("@2026-07-20-10:45rd", ReminderTokenMutation::MarkDone)
                .expect("recurrence advance");
        assert_eq!(advanced, "@2026-07-21-10:45rd");
    }

    #[test]
    fn record_fired_advances_count_and_exhaustion() {
        let fired =
            plan_reminder_token_mutation("@2026-07-20-10:45x2", ReminderTokenMutation::RecordFired)
                .expect("first fire");
        assert_eq!(fired, "@2026-07-20-10:45x2.1");

        let exhausted = plan_reminder_token_mutation(
            "@2026-07-20-10:45x2.1",
            ReminderTokenMutation::RecordFired,
        )
        .expect("second fire exhausts");
        assert_eq!(exhausted, "@2026-07-20-10:45x2.done");

        let recur = plan_reminder_token_mutation(
            "@2026-07-20-10:45x2rd",
            ReminderTokenMutation::RecordFired,
        )
        .expect("recurrence after exhaust");
        // First fire only increments; not exhausted yet.
        assert_eq!(recur, "@2026-07-20-10:45x2rd.1");

        let recur_done = plan_reminder_token_mutation(
            "@2026-07-20-10:45x2rd.1",
            ReminderTokenMutation::RecordFired,
        )
        .expect("recurrence exhaust advances day");
        assert_eq!(recur_done, "@2026-07-21-10:45x2rd");
    }
}
