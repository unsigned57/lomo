//! Batch, item and attachment model: product limits, bounded preview, approval TTL and per-item
//! outcomes.
//!
//! Nothing here transports bytes. This module answers four questions:
//!
//! 1. is this batch inside the LAN v2 product limits (checked **before** any transfer),
//! 2. what may the receiver see before approving (a bounded preview, never a body),
//! 3. is an approval still usable after a process restart (TTL), and
//! 4. what is the per-item outcome, so a batch can be explicitly partially complete.

use std::collections::{BTreeMap, BTreeSet};

use sha2::{Digest, Sha256};

use crate::error::{permission, resource_limit, validation};
use crate::identity::{DeviceId, DisplayName};
use crate::limits::{
    MAX_ATTACHMENT_BYTES, MAX_BATCH_ITEMS, MAX_BATCH_TOTAL_BYTES, MAX_PREVIEW_TITLE_CHARS,
};
use lomo_core::LomoError;

/// Maximum bytes for a batch identifier.
const MAX_BATCH_ID_BYTES: usize = 64;

/// A sender-assigned batch identifier, unique per transfer.
#[derive(Clone, Debug, Eq, PartialEq, Hash, Ord, PartialOrd)]
pub struct LanBatchId(String);

impl LanBatchId {
    /// Parses a batch identifier.
    ///
    /// # Errors
    ///
    /// Validation when empty, oversized, or containing characters outside `[A-Za-z0-9_-]`.
    pub fn parse(raw: &str) -> Result<Self, LomoError> {
        if raw.is_empty() || raw.len() > MAX_BATCH_ID_BYTES {
            return Err(validation(
                "lan_batch_id_invalid",
                "batch id must be 1..=64 bytes",
            ));
        }
        if !raw
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
        {
            return Err(validation(
                "lan_batch_id_invalid",
                "batch id must use only ASCII alphanumerics, '-' and '_'",
            ));
        }
        Ok(Self(raw.to_owned()))
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// A per-item identity, stable across retries of the same transfer position and distinct across
/// transfers.
///
/// Derived from `(batch id, item index)` so two different transfers carrying identical content and
/// timestamps still produce two memos — LAN never de-duplicates by content.
#[derive(Clone, Debug, Eq, PartialEq, Hash, Ord, PartialOrd)]
pub struct LanItemId(String);

impl LanItemId {
    /// Derives the item id for one position in one batch.
    #[must_use]
    pub fn derive(batch_id: &LanBatchId, item_index: u16) -> Self {
        let mut hasher = Sha256::new();
        hasher.update(b"lomo-lan-item-id-v2");
        hasher.update(batch_id.as_str().as_bytes());
        hasher.update(item_index.to_be_bytes());
        Self(format!("{:x}", hasher.finalize()))
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// One attachment referenced by an item.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanAttachmentRef {
    slot: u16,
    name: String,
    digest: String,
    size_bytes: u64,
}

impl LanAttachmentRef {
    /// Builds an attachment reference.
    ///
    /// # Errors
    ///
    /// Validation for an empty/oversized name or a malformed digest; resource-limit when the
    /// attachment exceeds the per-attachment ceiling.
    pub fn new(slot: u16, name: &str, digest: &str, size_bytes: u64) -> Result<Self, LomoError> {
        if name.is_empty() || name.len() > 255 || name.contains('/') || name.contains('\\') {
            return Err(validation(
                "lan_attachment_name_invalid",
                "attachment name must be a 1..=255 byte single path segment",
            ));
        }
        if digest.len() != 64 || !digest.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            return Err(validation(
                "lan_attachment_digest_invalid",
                "attachment digest must be 64 lowercase hex characters",
            ));
        }
        if size_bytes > MAX_ATTACHMENT_BYTES {
            return Err(resource_limit(
                "lan_attachment_too_large",
                "attachment exceeds the 100 MiB per-attachment ceiling",
            ));
        }
        Ok(Self {
            slot,
            name: name.to_owned(),
            digest: digest.to_ascii_lowercase(),
            size_bytes,
        })
    }

    #[must_use]
    pub const fn slot(&self) -> u16 {
        self.slot
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
    pub const fn size_bytes(&self) -> u64 {
        self.size_bytes
    }
}

/// One memo the sender intends to transfer.
///
/// Carries only the current body facts, the original timestamp and referenced attachments. Pin,
/// trash, history and snooze are never part of a LAN item.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanItemPlan {
    item_id: LanItemId,
    index: u16,
    timestamp_ms: i64,
    content_digest: String,
    content_bytes: u64,
    title: String,
    attachments: Vec<LanAttachmentRef>,
}

impl LanItemPlan {
    /// Builds an item plan.
    ///
    /// # Errors
    ///
    /// Validation for a malformed content digest; resource-limit when the body itself exceeds the
    /// batch ceiling.
    pub fn new(
        batch_id: &LanBatchId,
        index: u16,
        timestamp_ms: i64,
        content_digest: &str,
        content_bytes: u64,
        title: &str,
        attachments: Vec<LanAttachmentRef>,
    ) -> Result<Self, LomoError> {
        if content_digest.len() != 64
            || !content_digest.bytes().all(|byte| byte.is_ascii_hexdigit())
        {
            return Err(validation(
                "lan_item_digest_invalid",
                "item content digest must be 64 lowercase hex characters",
            ));
        }
        if content_bytes > MAX_BATCH_TOTAL_BYTES {
            return Err(resource_limit(
                "lan_item_too_large",
                "item body exceeds the batch byte ceiling",
            ));
        }
        Ok(Self {
            item_id: LanItemId::derive(batch_id, index),
            index,
            timestamp_ms,
            content_digest: content_digest.to_ascii_lowercase(),
            content_bytes,
            title: title.to_owned(),
            attachments,
        })
    }

    #[must_use]
    pub const fn item_id(&self) -> &LanItemId {
        &self.item_id
    }

    #[must_use]
    pub const fn index(&self) -> u16 {
        self.index
    }

    #[must_use]
    pub const fn timestamp_ms(&self) -> i64 {
        self.timestamp_ms
    }

    #[must_use]
    pub fn content_digest(&self) -> &str {
        &self.content_digest
    }

    #[must_use]
    pub const fn content_bytes(&self) -> u64 {
        self.content_bytes
    }

    #[must_use]
    pub fn attachments(&self) -> &[LanAttachmentRef] {
        &self.attachments
    }

    /// The title truncated to the preview ceiling on a character boundary.
    #[must_use]
    pub fn preview_title(&self) -> String {
        self.title.chars().take(MAX_PREVIEW_TITLE_CHARS).collect()
    }
}

/// A validated batch the sender may transfer.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanBatchPlan {
    batch_id: LanBatchId,
    items: Vec<LanItemPlan>,
    total_bytes: u64,
}

impl LanBatchPlan {
    /// Validates a batch against every LAN v2 product limit before any transfer starts.
    ///
    /// Shared attachment bytes are counted once, matching the wire behaviour where a repeated
    /// digest transfers a single time.
    ///
    /// # Errors
    ///
    /// Resource-limit when the item count or total byte budget is exceeded; validation when the
    /// batch is empty or item indices are not the contiguous range `0..items.len()`.
    pub fn new(batch_id: LanBatchId, items: Vec<LanItemPlan>) -> Result<Self, LomoError> {
        if items.is_empty() {
            return Err(validation(
                "lan_batch_empty",
                "a LAN batch must carry at least one item",
            ));
        }
        if items.len() > MAX_BATCH_ITEMS {
            return Err(resource_limit(
                "lan_batch_too_many_items",
                "batch exceeds the 100-item LAN ceiling; use a workspace archive instead",
            ));
        }
        for (position, item) in items.iter().enumerate() {
            if usize::from(item.index()) != position {
                return Err(validation(
                    "lan_batch_index_gap",
                    "batch item indices must be the contiguous range 0..item_count",
                ));
            }
        }

        let mut total_bytes = 0_u64;
        let mut counted_digests = BTreeSet::new();
        for item in &items {
            total_bytes = total_bytes.saturating_add(item.content_bytes());
            for attachment in item.attachments() {
                if counted_digests.insert(attachment.digest().to_owned()) {
                    total_bytes = total_bytes.saturating_add(attachment.size_bytes());
                }
            }
        }
        if total_bytes > MAX_BATCH_TOTAL_BYTES {
            return Err(resource_limit(
                "lan_batch_too_large",
                "batch exceeds the 100 MiB LAN ceiling; use a workspace archive instead",
            ));
        }

        Ok(Self {
            batch_id,
            items,
            total_bytes,
        })
    }

    #[must_use]
    pub const fn batch_id(&self) -> &LanBatchId {
        &self.batch_id
    }

    #[must_use]
    pub fn items(&self) -> &[LanItemPlan] {
        &self.items
    }

    #[must_use]
    pub const fn item_count(&self) -> usize {
        self.items.len()
    }

    #[must_use]
    pub const fn total_bytes(&self) -> u64 {
        self.total_bytes
    }

    /// Total attachment references across items (a shared digest is referenced more than once).
    #[must_use]
    pub fn attachment_count(&self) -> usize {
        self.items.iter().map(|item| item.attachments().len()).sum()
    }

    /// Distinct attachment digests, i.e. what actually travels on the wire.
    #[must_use]
    pub fn distinct_attachment_digests(&self) -> BTreeSet<String> {
        self.items
            .iter()
            .flat_map(LanItemPlan::attachments)
            .map(|attachment| attachment.digest().to_owned())
            .collect()
    }

    /// Builds the bounded preview shown before approval.
    ///
    /// The preview is derived from plan metadata only: it structurally cannot carry a body or
    /// attachment byte, because those never enter [`LanItemPlan`].
    #[must_use]
    pub fn preview(
        &self,
        sender_device_id: &DeviceId,
        sender_name: &DisplayName,
    ) -> LanBatchPreview {
        LanBatchPreview {
            batch_id: self.batch_id.clone(),
            sender_device_id: sender_device_id.clone(),
            sender_name: sender_name.clone(),
            item_count: self.items.len(),
            attachment_count: self.distinct_attachment_digests().len(),
            total_bytes: self.total_bytes,
            titles: self.items.iter().map(LanItemPlan::preview_title).collect(),
        }
    }
}

/// The bounded preview a receiver may see before approving a batch.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanBatchPreview {
    batch_id: LanBatchId,
    sender_device_id: DeviceId,
    sender_name: DisplayName,
    item_count: usize,
    attachment_count: usize,
    total_bytes: u64,
    titles: Vec<String>,
}

impl LanBatchPreview {
    #[must_use]
    pub const fn batch_id(&self) -> &LanBatchId {
        &self.batch_id
    }

    #[must_use]
    pub const fn sender_device_id(&self) -> &DeviceId {
        &self.sender_device_id
    }

    #[must_use]
    pub const fn sender_name(&self) -> &DisplayName {
        &self.sender_name
    }

    #[must_use]
    pub const fn item_count(&self) -> usize {
        self.item_count
    }

    #[must_use]
    pub const fn attachment_count(&self) -> usize {
        self.attachment_count
    }

    #[must_use]
    pub const fn total_bytes(&self) -> u64 {
        self.total_bytes
    }

    #[must_use]
    pub fn titles(&self) -> &[String] {
        &self.titles
    }
}

/// A durable user approval for exactly one batch, valid for a bounded time.
///
/// Recovery inside the TTL resumes without asking the user again; after it, the batch must be
/// re-approved.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanApproval {
    batch_id: LanBatchId,
    approved_at_ms: i64,
    ttl_ms: i64,
}

impl LanApproval {
    /// Records an approval.
    #[must_use]
    pub const fn granted(batch_id: LanBatchId, approved_at_ms: i64, ttl_ms: i64) -> Self {
        Self {
            batch_id,
            approved_at_ms,
            ttl_ms,
        }
    }

    #[must_use]
    pub const fn batch_id(&self) -> &LanBatchId {
        &self.batch_id
    }

    /// Fails closed when the approval no longer covers `now`.
    ///
    /// # Errors
    ///
    /// Permission when `now` is before the grant or past the TTL.
    pub fn assert_valid_at(&self, now_ms: i64) -> Result<(), LomoError> {
        let expires_at = self.approved_at_ms.saturating_add(self.ttl_ms);
        if now_ms < self.approved_at_ms || now_ms > expires_at {
            return Err(permission(
                "lan_approval_expired",
                "batch approval is outside its time-to-live and must be granted again",
            ));
        }
        Ok(())
    }

    /// Fails closed when the approval is for a different batch.
    ///
    /// # Errors
    ///
    /// Permission when the batch id does not match.
    pub fn assert_covers(&self, batch_id: &LanBatchId) -> Result<(), LomoError> {
        if &self.batch_id != batch_id {
            return Err(permission(
                "lan_approval_batch_mismatch",
                "approval was granted for a different batch",
            ));
        }
        Ok(())
    }
}

/// The terminal or pending state of one item.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum LanItemOutcome {
    /// Not yet attempted or still transferring.
    Pending,
    /// Committed through the store; carries the created memo id.
    Committed { memo_id: String },
    /// Failed with a stable code; retryable without touching the committed items.
    Failed { code: String },
}

impl LanItemOutcome {
    /// Builds a committed outcome.
    #[must_use]
    pub fn committed(memo_id: &str) -> Self {
        Self::Committed {
            memo_id: memo_id.to_owned(),
        }
    }

    /// Builds a failed outcome.
    #[must_use]
    pub fn failed(code: &str) -> Self {
        Self::Failed {
            code: code.to_owned(),
        }
    }

    #[must_use]
    pub const fn is_terminal(&self) -> bool {
        matches!(self, Self::Committed { .. } | Self::Failed { .. })
    }
}

/// Per-item state of one batch, including explicit partial completion.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LanBatchSnapshot {
    batch_id: LanBatchId,
    order: Vec<LanItemId>,
    outcomes: BTreeMap<LanItemId, LanItemOutcome>,
}

impl LanBatchSnapshot {
    /// Builds an all-pending snapshot for a plan.
    #[must_use]
    pub fn pending(plan: &LanBatchPlan) -> Self {
        let order: Vec<LanItemId> = plan
            .items()
            .iter()
            .map(|item| item.item_id().clone())
            .collect();
        let outcomes = order
            .iter()
            .map(|item_id| (item_id.clone(), LanItemOutcome::Pending))
            .collect();
        Self {
            batch_id: plan.batch_id().clone(),
            order,
            outcomes,
        }
    }

    #[must_use]
    pub const fn batch_id(&self) -> &LanBatchId {
        &self.batch_id
    }

    /// Records an outcome, returning the effective outcome for the item.
    ///
    /// Recording over an already-committed item is idempotent: the existing result is returned and
    /// the new one is discarded, so a replayed item never creates a second memo.
    ///
    /// # Errors
    ///
    /// Validation when the item does not belong to this batch.
    pub fn record(
        &mut self,
        item_id: &LanItemId,
        outcome: LanItemOutcome,
    ) -> Result<LanItemOutcome, LomoError> {
        let Some(existing) = self.outcomes.get(item_id) else {
            return Err(validation(
                "lan_item_not_in_batch",
                "item id does not belong to this batch",
            ));
        };
        if let LanItemOutcome::Committed { .. } = existing {
            return Ok(existing.clone());
        }
        self.outcomes.insert(item_id.clone(), outcome.clone());
        Ok(outcome)
    }

    /// The outcome for one item, if it belongs to this batch.
    #[must_use]
    pub fn outcome(&self, item_id: &LanItemId) -> Option<&LanItemOutcome> {
        self.outcomes.get(item_id)
    }

    /// True when every item reached a terminal state.
    #[must_use]
    pub fn is_complete(&self) -> bool {
        self.outcomes.values().all(LanItemOutcome::is_terminal)
    }

    /// True when at least one item failed — the batch must not be reported as success.
    #[must_use]
    pub fn is_partially_failed(&self) -> bool {
        self.outcomes
            .values()
            .any(|outcome| matches!(outcome, LanItemOutcome::Failed { .. }))
    }

    /// Committed memo ids in batch order.
    #[must_use]
    pub fn committed_memo_ids(&self) -> Vec<String> {
        self.order
            .iter()
            .filter_map(|item_id| match self.outcomes.get(item_id) {
                Some(LanItemOutcome::Committed { memo_id }) => Some(memo_id.clone()),
                Some(LanItemOutcome::Failed { .. } | LanItemOutcome::Pending) | None => None,
            })
            .collect()
    }

    /// Item ids that may be retried; committed items are never rolled back.
    #[must_use]
    pub fn retryable_item_ids(&self) -> Vec<LanItemId> {
        self.order
            .iter()
            .filter(|item_id| {
                matches!(
                    self.outcomes.get(*item_id),
                    Some(LanItemOutcome::Failed { .. } | LanItemOutcome::Pending)
                )
            })
            .cloned()
            .collect()
    }
}
