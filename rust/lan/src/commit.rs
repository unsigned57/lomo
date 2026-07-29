//! Per-item commit of received LAN memos through the `lomo-store` single writer.
//!
//! `lomo-lan` never writes user files. A received item becomes a `LocalSyncMutationBatch` and is
//! committed by `lomo-store` on the same expected-revision path as an ordinary edit, so LAN receive
//! cannot become a second write authority.
//!
//! Three fences hold on every commit:
//!
//! 1. the batch approval must still cover this batch and still be inside its TTL,
//! 2. the active workspace generation must equal the one captured at approval, and
//! 3. the item id must not already be committed — a replay returns the existing result.

use lomo_core::LomoError;
use lomo_store::{LocalSyncMutation, LocalSyncMutationBatch};

use crate::batch::{LanApproval, LanBatchSnapshot, LanItemId, LanItemPlan};
use crate::error::{conflict, validation};

/// The workspace generation an approval was bound to.
///
/// Captured when the user approves and re-checked at commit, so a workspace switch or archive
/// activation between approval and apply cannot write into the new workspace.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ApprovedGeneration(String);

impl ApprovedGeneration {
    /// Captures the active workspace generation at approval time.
    ///
    /// # Errors
    ///
    /// Validation when the generation is empty.
    pub fn capture(workspace_generation: &str) -> Result<Self, LomoError> {
        if workspace_generation.is_empty() {
            return Err(validation(
                "lan_generation_missing",
                "LAN approval requires an active workspace generation fence",
            ));
        }
        Ok(Self(workspace_generation.to_owned()))
    }

    /// Fails closed when the workspace changed between approval and apply.
    ///
    /// # Errors
    ///
    /// Conflict when the active generation differs from the approved one.
    pub fn assert_matches(&self, active_generation: &str) -> Result<(), LomoError> {
        if self.0 != active_generation {
            return Err(conflict(
                "lan_workspace_generation_changed",
                "workspace generation changed after approval; the new workspace is not written",
            ));
        }
        Ok(())
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// One fully received and verified item ready to commit.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ReceivedItem {
    item_id: LanItemId,
    memo_id: String,
    content: String,
}

impl ReceivedItem {
    /// Builds a received item after its body has been fully verified against the plan digest.
    ///
    /// # Errors
    ///
    /// Validation when the body digest does not match the plan, or the memo id is empty.
    pub fn verified(plan: &LanItemPlan, memo_id: &str, content: String) -> Result<Self, LomoError> {
        if memo_id.is_empty() {
            return Err(validation(
                "lan_item_memo_id_invalid",
                "received item requires a non-empty memo id",
            ));
        }
        let digest = format!(
            "{:x}",
            <sha2::Sha256 as sha2::Digest>::digest(content.as_bytes())
        );
        if digest != plan.content_digest() {
            return Err(validation(
                "lan_item_digest_mismatch",
                "received body digest does not match the transferred item plan",
            ));
        }
        Ok(Self {
            item_id: plan.item_id().clone(),
            memo_id: memo_id.to_owned(),
            content,
        })
    }

    #[must_use]
    pub const fn item_id(&self) -> &LanItemId {
        &self.item_id
    }

    #[must_use]
    pub fn memo_id(&self) -> &str {
        &self.memo_id
    }

    /// Builds the single-item store mutation batch for this received memo.
    ///
    /// Creating a memo means an expected revision of zero: LAN receive never overwrites an existing
    /// memo, and identity collisions are resolved by the store's ordinal, not by de-duplication.
    #[must_use]
    pub fn to_mutation_batch(&self) -> LocalSyncMutationBatch {
        LocalSyncMutationBatch {
            mutations: vec![LocalSyncMutation::UpsertMemo {
                operation_id: self.item_id.as_str().to_owned(),
                memo_id: self.memo_id.clone(),
                expected_revision: 0,
                expected_fingerprint: None,
                content: self.content.clone(),
                tags: Vec::new(),
            }],
        }
    }
}

/// Decides whether one received item may be committed right now.
///
/// Returns `Ok(None)` when the item already committed — the caller returns the existing outcome and
/// performs no store write, so a replay never creates a second memo.
///
/// # Errors
///
/// Permission when the approval is missing or expired, conflict when the workspace generation
/// changed, validation when the item does not belong to the batch.
pub fn authorize_item_commit(
    approval: &LanApproval,
    approved_generation: &ApprovedGeneration,
    active_generation: &str,
    now_ms: i64,
    snapshot: &LanBatchSnapshot,
    item: &ReceivedItem,
) -> Result<Option<LocalSyncMutationBatch>, LomoError> {
    approval.assert_covers(snapshot.batch_id())?;
    approval.assert_valid_at(now_ms)?;
    approved_generation.assert_matches(active_generation)?;

    let Some(outcome) = snapshot.outcome(item.item_id()) else {
        return Err(validation(
            "lan_item_not_in_batch",
            "received item does not belong to the approved batch",
        ));
    };
    if let crate::batch::LanItemOutcome::Committed { .. } = outcome {
        return Ok(None);
    }
    Ok(Some(item.to_mutation_batch()))
}
