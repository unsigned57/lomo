//! Behavior Contract:
//! - Unit under test: `build_reminder_token`, `plan_reminder_token_mutation`
//! - Owning layer: lomo-workspace
//! - Priority tier: P1
//! - Capability: Owner constructs canonical reminder tokens for insert/done/fire so Kotlin is not
//!   a second grammar writer. Tokens may carry a durable embedded `#<16 lowercase hex>` reminder
//!   id; owner mutations preserve an existing id and mint one into legacy tokens as the controlled
//!   format migration.
//!
//! Scenarios:
//! - Given typed insert fields, when built, then the token matches the strict stage-2 grammar.
//! - Given an explicit embedded id, when built, then the token carries `#<id>` and invalid id
//!   shapes are rejected.
//! - Given an active token, when `MarkDone`, then `.done` or recurrence advance is produced and an
//!   embedded id is present (minted on legacy tokens, preserved on migrated tokens).
//! - Given a recurring token at a month/year/leap-day boundary, when `MarkDone`, then the due date
//!   advances by the exact proleptic-Gregorian rule; advance past `9999-12-31` fails closed.
//! - Given a `due_at_local` with invalid civil date or variable-width fields, when built, then it
//!   is rejected; boundary-valid dates (`0000`, leap Feb 29, `9999-12-31`) are accepted.
//! - Given a multi-fire token, when `RecordFired`, then fired count / done / recurrence advance.
//!
//! Observable outcomes: exact token strings, parsed embedded ids, and validation failures.
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
        ReminderRecurrence, ReminderTokenMutation, build_reminder_token,
        plan_reminder_token_mutation, reminder_token_facts,
    };

    const EMBEDDED: &str = "0123456789abcdef";

    fn base_of(token: &str) -> &str {
        token.split('#').next().unwrap_or(token)
    }

    #[test]
    fn build_insert_token_matches_strict_grammar() {
        let token = build_reminder_token(
            "2026-07-20-10:45",
            1,
            0,
            false,
            10,
            ReminderRecurrence::None,
            None,
        )
        .expect("simple insert");
        assert_eq!(token, "@2026-07-20-10:45");

        let multi = build_reminder_token(
            "2026-07-20-10:45",
            3,
            0,
            false,
            15,
            ReminderRecurrence::Daily,
            None,
        )
        .expect("multi insert");
        assert_eq!(multi, "@2026-07-20-10:45x3i15rd");
    }

    #[test]
    fn build_token_embeds_and_validates_durable_id() {
        let token = build_reminder_token(
            "2026-07-20-10:45",
            2,
            1,
            false,
            10,
            ReminderRecurrence::Daily,
            Some(EMBEDDED),
        )
        .expect("embedded insert");
        assert_eq!(token, "@2026-07-20-10:45x2rd.1#0123456789abcdef");
        assert_eq!(
            reminder_token_facts(&token)
                .expect("facts")
                .embedded_id
                .as_deref(),
            Some(EMBEDDED)
        );

        for bad in [
            "0123456789abcde",
            "0123456789abcdef0",
            "0123456789ABCDEF",
            "g123456789abcdef",
        ] {
            assert!(
                build_reminder_token(
                    "2026-07-20-10:45",
                    1,
                    0,
                    false,
                    10,
                    ReminderRecurrence::None,
                    Some(bad)
                )
                .is_err(),
                "embedded id {bad} must be rejected"
            );
        }
    }

    #[test]
    fn mark_done_sets_done_or_advances_recurrence() {
        let done =
            plan_reminder_token_mutation("@2026-07-20-10:45", ReminderTokenMutation::MarkDone)
                .expect("mark done");
        assert_eq!(base_of(&done), "@2026-07-20-10:45.done");
        assert!(
            reminder_token_facts(&done)
                .expect("done facts")
                .embedded_id
                .is_some(),
            "legacy token mutation must mint an embedded id"
        );

        let advanced =
            plan_reminder_token_mutation("@2026-07-20-10:45rd", ReminderTokenMutation::MarkDone)
                .expect("recurrence advance");
        assert_eq!(base_of(&advanced), "@2026-07-21-10:45rd");
        assert!(
            reminder_token_facts(&advanced)
                .expect("advanced facts")
                .embedded_id
                .is_some()
        );
    }

    #[test]
    fn recurrence_advance_crosses_calendar_boundaries() {
        // Equivalence pin for the civil-date arithmetic behind `rd`/`rw` advance:
        // month/year rollovers, proleptic-Gregorian leap rules (incl. century cases).
        let cases = [
            ("@2026-01-31-10:45rd", "@2026-02-01-10:45rd"), // month rollover
            ("@2026-12-31-10:45rd", "@2027-01-01-10:45rd"), // year rollover
            ("@2024-02-28-10:45rd", "@2024-02-29-10:45rd"), // leap year Feb 29
            ("@2025-02-28-10:45rd", "@2025-03-01-10:45rd"), // non-leap Feb ends at 28
            ("@2100-02-28-10:45rd", "@2100-03-01-10:45rd"), // century year is not leap
            ("@2000-02-28-10:45rd", "@2000-02-29-10:45rd"), // 400-year leap
            ("@2026-04-30-10:45rw", "@2026-05-07-10:45rw"), // weekly month rollover
            ("@2026-12-28-10:45rw", "@2027-01-04-10:45rw"), // weekly year rollover
        ];
        for (input, expected) in cases {
            let out = plan_reminder_token_mutation(input, ReminderTokenMutation::MarkDone)
                .expect("recurrence advance must succeed");
            assert_eq!(base_of(&out), expected, "case {input}");
        }

        // Advancing past the representable civil range must fail closed, not wrap
        // into a non-grammar string.
        assert!(
            plan_reminder_token_mutation("@9999-12-31-23:59rd", ReminderTokenMutation::MarkDone)
                .is_err(),
            "9999-12-31 + 1d must fail closed"
        );
    }

    #[test]
    fn due_date_gate_accepts_and_rejects_civil_dates_exactly() {
        for bad in [
            "2025-02-29-10:45", // Feb 29 in a non-leap year
            "2100-02-29-10:45", // century non-leap Feb 29
            "2024-02-30-10:45", // Feb 30 never exists
            "2026-04-31-10:45", // April has 30 days
            "2026-13-01-10:45", // month 13
            "2026-00-10-10:45", // month 0
            "2026-01-00-10:45", // day 0
            "2026-01-32-10:45", // day 32
            "2026-1-01-10:45",  // variable-width month (strict fixed-width grammar)
        ] {
            assert!(
                build_reminder_token(bad, 1, 0, false, 10, ReminderRecurrence::None, None).is_err(),
                "due_at_local {bad} must be rejected"
            );
        }
        for ok in [
            "2024-02-29-10:45", // leap Feb 29
            "2000-02-29-10:45", // 400-year leap Feb 29
            "0000-01-01-00:00", // year 0000 is a valid proleptic Gregorian date
            "9999-12-31-23:59", // upper bound of the 4-digit grammar
        ] {
            assert!(
                build_reminder_token(ok, 1, 0, false, 10, ReminderRecurrence::None, None).is_ok(),
                "due_at_local {ok} must be accepted"
            );
        }
    }

    #[test]
    fn record_fired_advances_count_and_exhaustion() {
        let fired =
            plan_reminder_token_mutation("@2026-07-20-10:45x2", ReminderTokenMutation::RecordFired)
                .expect("first fire");
        assert_eq!(base_of(&fired), "@2026-07-20-10:45x2.1");

        let exhausted = plan_reminder_token_mutation(
            "@2026-07-20-10:45x2.1",
            ReminderTokenMutation::RecordFired,
        )
        .expect("second fire exhausts");
        assert_eq!(base_of(&exhausted), "@2026-07-20-10:45x2.done");

        let recur = plan_reminder_token_mutation(
            "@2026-07-20-10:45x2rd",
            ReminderTokenMutation::RecordFired,
        )
        .expect("recurrence after exhaust");
        // First fire only increments; not exhausted yet.
        assert_eq!(base_of(&recur), "@2026-07-20-10:45x2rd.1");

        let recur_done = plan_reminder_token_mutation(
            "@2026-07-20-10:45x2rd.1",
            ReminderTokenMutation::RecordFired,
        )
        .expect("recurrence exhaust advances day");
        assert_eq!(base_of(&recur_done), "@2026-07-21-10:45x2rd");
    }

    #[test]
    fn mutations_preserve_an_existing_embedded_id() {
        let migrated = plan_reminder_token_mutation(
            "@2026-07-20-10:45x2#0123456789abcdef",
            ReminderTokenMutation::RecordFired,
        )
        .expect("first fire on migrated token");
        assert_eq!(migrated, "@2026-07-20-10:45x2.1#0123456789abcdef");

        let done = plan_reminder_token_mutation(&migrated, ReminderTokenMutation::MarkDone)
            .expect("done on migrated token");
        assert_eq!(done, "@2026-07-20-10:45x2.done#0123456789abcdef");
    }
}
