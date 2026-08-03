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

use crate::batch::{LanApproval, LanAttachmentRef, LanBatchSnapshot, LanItemId, LanItemPlan};
use crate::error::{conflict, validation};
use lomo_core::LomoError;

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
    timestamp_ms: i64,
    content: String,
}

impl ReceivedItem {
    /// Builds a received item after its body has been fully verified against the plan digest.
    ///
    /// # Errors
    ///
    /// Validation when the body digest does not match the plan.
    pub fn verified(plan: &LanItemPlan, content: String) -> Result<Self, LomoError> {
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
            timestamp_ms: plan.timestamp_ms(),
            content,
        })
    }

    #[must_use]
    pub const fn item_id(&self) -> &LanItemId {
        &self.item_id
    }

    #[must_use]
    pub const fn timestamp_ms(&self) -> i64 {
        self.timestamp_ms
    }

    #[must_use]
    pub fn content(&self) -> &str {
        &self.content
    }
}

/// Fully authorized facts for the store's atomic received-memo create boundary.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AuthorizedReceivedCreate {
    item_id: LanItemId,
    timestamp_ms: i64,
    content: String,
    attachments: Vec<AuthorizedReceivedAttachment>,
    approved_generation: ApprovedGeneration,
}

/// One fully verified attachment carried by an authorized received create.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AuthorizedReceivedAttachment {
    source_reference: String,
    name: String,
    digest: String,
    bytes: Vec<u8>,
}

impl AuthorizedReceivedAttachment {
    pub(crate) fn verified(
        reference: &LanAttachmentRef,
        transfer: &LanAttachmentRef,
        bytes: Vec<u8>,
    ) -> Result<Self, LomoError> {
        let size = u64::try_from(bytes.len()).map_err(|_error| {
            validation(
                "lan_attachment_size_invalid",
                "received attachment size does not fit the plan width",
            )
        })?;
        let digest = format!("{:x}", <sha2::Sha256 as sha2::Digest>::digest(&bytes));
        if reference.digest() != transfer.digest()
            || size != transfer.size_bytes()
            || digest != transfer.digest()
        {
            return Err(validation(
                "lan_attachment_digest_mismatch",
                "received attachment does not match its transferred size and digest",
            ));
        }
        Ok(Self {
            source_reference: reference.source_reference().to_owned(),
            name: transfer.name().to_owned(),
            digest,
            bytes,
        })
    }

    #[must_use]
    pub fn source_reference(&self) -> &str {
        &self.source_reference
    }

    #[must_use]
    pub fn name(&self) -> &str {
        &self.name
    }

    #[must_use]
    pub fn digest(&self) -> &str {
        &self.digest
    }

    #[must_use]
    pub fn bytes(&self) -> &[u8] {
        &self.bytes
    }
}

impl AuthorizedReceivedCreate {
    #[must_use]
    pub const fn item_id(&self) -> &LanItemId {
        &self.item_id
    }

    #[must_use]
    pub const fn timestamp_ms(&self) -> i64 {
        self.timestamp_ms
    }

    #[must_use]
    pub fn content(&self) -> &str {
        &self.content
    }

    #[must_use]
    pub const fn approved_generation(&self) -> &ApprovedGeneration {
        &self.approved_generation
    }

    #[must_use]
    pub fn attachments(&self) -> &[AuthorizedReceivedAttachment] {
        &self.attachments
    }

    pub(crate) fn with_attachments(
        mut self,
        attachments: Vec<AuthorizedReceivedAttachment>,
    ) -> Self {
        self.attachments = attachments;
        self
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
) -> Result<Option<AuthorizedReceivedCreate>, LomoError> {
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
    Ok(Some(AuthorizedReceivedCreate {
        item_id: item.item_id.clone(),
        timestamp_ms: item.timestamp_ms,
        content: item.content.clone(),
        attachments: Vec::new(),
        approved_generation: approved_generation.clone(),
    }))
}
