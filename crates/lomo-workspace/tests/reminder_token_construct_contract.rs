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
        ReminderTokenMutation, build_reminder_token, plan_reminder_token_mutation,
        reminder_token_facts,
    };

    const EMBEDDED: &str = "0123456789abcdef";

    fn base_of(token: &str) -> &str {
        token.split('#').next().unwrap_or(token)
    }

    #[test]
    fn build_insert_token_matches_strict_grammar() {
        let token = build_reminder_token("2026-07-20-10:45", 1, 0, false, 10, "", None)
            .expect("simple insert");
        assert_eq!(token, "@2026-07-20-10:45");

        let multi = build_reminder_token("2026-07-20-10:45", 3, 0, false, 15, "d", None)
            .expect("multi insert");
        assert_eq!(multi, "@2026-07-20-10:45x3i15rd");
    }

    #[test]
    fn build_token_embeds_and_validates_durable_id() {
        let token = build_reminder_token("2026-07-20-10:45", 2, 1, false, 10, "d", Some(EMBEDDED))
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
                build_reminder_token("2026-07-20-10:45", 1, 0, false, 10, "", Some(bad)).is_err(),
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
