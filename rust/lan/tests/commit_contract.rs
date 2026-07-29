//! Behavior Contract (Stage-6 P6-06 per-item commit fences)
//!
//! Capability: a received LAN item is committed through the `lomo-store` single writer only when
//! the approval still covers it, the workspace generation is unchanged, and the item has not
//! already committed.
//!
//! Scenarios:
//! - Given a verified item under a valid approval and unchanged generation, when authorized, then
//!   it yields a single-item store mutation batch carrying the item id as the operation id.
//! - Given a body whose digest does not match the transferred plan, when built, then it is rejected
//!   and no mutation is produced.
//! - Given an approval past its TTL, when authorized, then it fails closed.
//! - Given an approval for a different batch, when authorized, then it fails closed.
//! - Given the workspace generation changed after approval, when authorized, then it fails closed
//!   and the new workspace is not written.
//! - Given an item that already committed, when authorized again, then no mutation is produced, so
//!   a replay creates no second memo.
//! - Given an item that is not part of the approved batch, when authorized, then it is rejected.
//! - Given a created memo, then the mutation uses an expected revision of zero, so LAN receive can
//!   never silently overwrite an existing memo.
//!
//! Observable outcomes: produced `LocalSyncMutationBatch` contents, `LomoError` code/category.
//!
//! Excludes: sockets, AEAD, the store transaction itself, Kotlin adapters.

#![deny(unsafe_code)]

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::indexing_slicing,
    reason = "contract tests fail closed with panics and index single-item fixture batches"
)]
mod tests {
    use lomo_core::ErrorCategory;
    use lomo_lan::{
        ApprovedGeneration, LanApproval, LanBatchId, LanBatchPlan, LanBatchSnapshot,
        LanItemOutcome, LanItemPlan, ReceivedItem, authorize_item_commit,
    };
    use lomo_store::LocalSyncMutation;
    use sha2::{Digest, Sha256};

    const BODY: &str = "# 收到的 memo\n\n- from the tablet\n";
    const NOW: i64 = 1_700_000_100_000;

    fn digest_of(value: &str) -> String {
        format!("{:x}", Sha256::digest(value.as_bytes()))
    }

    fn batch_id() -> LanBatchId {
        LanBatchId::parse("batch-commit").expect("fixture batch id is valid")
    }

    fn plan() -> LanBatchPlan {
        let id = batch_id();
        let item = LanItemPlan::new(
            &id,
            0,
            1_700_000_000_000,
            &digest_of(BODY),
            BODY.len() as u64,
            "收到的 memo",
            Vec::new(),
        )
        .expect("fixture item plan is valid");
        LanBatchPlan::new(id, vec![item]).expect("fixture batch is inside the limits")
    }

    fn approval() -> LanApproval {
        LanApproval::granted(batch_id(), 1_700_000_000_000, 600_000)
    }

    fn generation() -> ApprovedGeneration {
        ApprovedGeneration::capture("generation-a").expect("fixture generation is valid")
    }

    fn received(plan: &LanBatchPlan) -> ReceivedItem {
        ReceivedItem::verified(&plan.items()[0], "memo-1", BODY.to_owned())
            .expect("a body matching the plan digest is accepted")
    }

    #[test]
    fn a_verified_item_yields_one_store_mutation_carrying_the_item_id() {
        let plan = plan();
        let snapshot = LanBatchSnapshot::pending(&plan);
        let item = received(&plan);

        let batch = authorize_item_commit(
            &approval(),
            &generation(),
            "generation-a",
            NOW,
            &snapshot,
            &item,
        )
        .expect("an authorized item produces a mutation")
        .expect("a not-yet-committed item produces a mutation");

        assert_eq!(batch.mutations.len(), 1, "one item commits as one mutation");
        match &batch.mutations[0] {
            LocalSyncMutation::UpsertMemo {
                operation_id,
                memo_id,
                expected_revision,
                content,
                tags,
                ..
            } => {
                assert_eq!(
                    operation_id,
                    plan.items()[0].item_id().as_str(),
                    "the item id is the store operation id, so the store's own idempotency keys off it"
                );
                assert_eq!(memo_id, "memo-1");
                assert_eq!(
                    *expected_revision, 0,
                    "LAN receive creates a memo and never overwrites an existing revision"
                );
                assert_eq!(content, BODY);
                assert!(
                    tags.is_empty(),
                    "LAN transfers no pin, trash, history or tags"
                );
            }
            LocalSyncMutation::DeleteMemo { .. }
            | LocalSyncMutation::EnsureMediaPresent { .. }
            | LocalSyncMutation::EnsureMediaAbsent { .. } => {
                panic!("a received memo must commit as an UpsertMemo")
            }
        }
    }

    #[test]
    fn a_body_that_does_not_match_the_transferred_digest_is_rejected() {
        let plan = plan();
        let error = ReceivedItem::verified(&plan.items()[0], "memo-1", "tampered body".to_owned())
            .expect_err("a body whose digest differs from the plan must be rejected");
        assert_eq!(error.category(), ErrorCategory::Validation);
        assert_eq!(error.code(), "lan_item_digest_mismatch");
    }

    #[test]
    fn an_expired_approval_fails_closed() {
        let plan = plan();
        let snapshot = LanBatchSnapshot::pending(&plan);
        let error = authorize_item_commit(
            &approval(),
            &generation(),
            "generation-a",
            1_700_000_600_001,
            &snapshot,
            &received(&plan),
        )
        .expect_err("an approval past its TTL must not authorize a commit");
        assert_eq!(error.category(), ErrorCategory::Permission);
        assert_eq!(error.code(), "lan_approval_expired");
    }

    #[test]
    fn an_approval_for_another_batch_fails_closed() {
        let plan = plan();
        let snapshot = LanBatchSnapshot::pending(&plan);
        let other = LanApproval::granted(
            LanBatchId::parse("batch-other").expect("fixture batch id is valid"),
            1_700_000_000_000,
            600_000,
        );

        let error = authorize_item_commit(
            &other,
            &generation(),
            "generation-a",
            NOW,
            &snapshot,
            &received(&plan),
        )
        .expect_err("an approval for another batch must not authorize this one");
        assert_eq!(error.code(), "lan_approval_batch_mismatch");
    }

    #[test]
    fn a_workspace_switch_after_approval_fails_closed_and_writes_nothing() {
        let plan = plan();
        let snapshot = LanBatchSnapshot::pending(&plan);

        let error = authorize_item_commit(
            &approval(),
            &generation(),
            "generation-b",
            NOW,
            &snapshot,
            &received(&plan),
        )
        .expect_err("a generation change after approval must not write the new workspace");
        assert_eq!(error.category(), ErrorCategory::Conflict);
        assert_eq!(error.code(), "lan_workspace_generation_changed");
    }

    #[test]
    fn an_empty_generation_fence_is_rejected_at_capture() {
        let error =
            ApprovedGeneration::capture("").expect_err("an approval requires a real generation");
        assert_eq!(error.code(), "lan_generation_missing");
    }

    #[test]
    fn replaying_an_already_committed_item_produces_no_mutation() {
        let plan = plan();
        let mut snapshot = LanBatchSnapshot::pending(&plan);
        let item = received(&plan);
        snapshot
            .record(item.item_id(), LanItemOutcome::committed("memo-1"))
            .expect("the first commit is recorded");

        let mutation = authorize_item_commit(
            &approval(),
            &generation(),
            "generation-a",
            NOW,
            &snapshot,
            &item,
        )
        .expect("replaying a committed item is not an error");
        assert!(
            mutation.is_none(),
            "an already-committed item must produce no store write, so no second memo is created"
        );
    }

    #[test]
    fn an_item_outside_the_approved_batch_is_rejected() {
        let plan = plan();
        let snapshot = LanBatchSnapshot::pending(&plan);

        let foreign_id = LanBatchId::parse("batch-foreign").expect("fixture batch id is valid");
        let foreign_plan = LanItemPlan::new(
            &foreign_id,
            0,
            1_700_000_000_000,
            &digest_of(BODY),
            BODY.len() as u64,
            "foreign",
            Vec::new(),
        )
        .expect("fixture item plan is valid");
        let foreign = ReceivedItem::verified(&foreign_plan, "memo-x", BODY.to_owned())
            .expect("the foreign body matches its own plan");

        let error = authorize_item_commit(
            &approval(),
            &generation(),
            "generation-a",
            NOW,
            &snapshot,
            &foreign,
        )
        .expect_err("an item from another batch must not commit under this approval");
        assert_eq!(error.code(), "lan_item_not_in_batch");
    }
}
