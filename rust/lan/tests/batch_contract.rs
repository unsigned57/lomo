//! Behavior Contract (Stage-6 P6-04 batch limits, bounded preview, approval TTL, per-item outcome)
//!
//! Capability: `lomo-lan` enforces the LAN v2 product limits before transfer, exposes only a
//! bounded preview before approval, records approval with a TTL that survives process death, and
//! reports per-item outcomes so a batch can be explicitly partially complete.
//!
//! Scenarios:
//! - Given a batch inside the limits, when validated, then it is accepted and reports its totals.
//! - Given more than `MAX_BATCH_ITEMS` items, more than `MAX_BATCH_TOTAL_BYTES` in total, or one
//!   attachment above `MAX_ATTACHMENT_BYTES`, when validated, then it is rejected before transfer.
//! - Given a validated batch, when the preview is built, then it carries sender identity, counts,
//!   total bytes and truncated titles only — never a body or attachment byte.
//! - Given a title longer than the preview ceiling, when previewed, then it is truncated on a
//!   character boundary.
//! - Given an approval with a TTL, when the clock is inside the TTL, then resume is allowed; when
//!   the clock is past it, then re-approval is required.
//! - Given a batch where one item fails and others commit, when snapshotted, then it reports
//!   partial completion, the committed items keep their memo ids, and only the failed item is
//!   retryable.
//! - Given the same item id submitted twice, when the first already committed, then the second
//!   returns the existing result and creates no second memo.
//! - Given two different batches with identical content and timestamps, then their item ids differ,
//!   so both commit as separate memos instead of de-duplicating.
//! - Given several items referencing one attachment digest, then the transfer plan lists that
//!   attachment once while each referencing item still tracks it.
//!
//! Observable outcomes: validation results and error codes, preview field values, approval
//! validity decisions, `LanBatchSnapshot` outcomes and partial-completion state.
//!
//! Excludes: sockets, AEAD, journal file durability, `lomo-store` transaction internals, Kotlin.

#![deny(unsafe_code)]

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::indexing_slicing,
    reason = "contract tests fail closed with panics and index fixture batches of known size"
)]
mod tests {
    use lomo_core::ErrorCategory;
    use lomo_lan::{
        DeviceId, DevicePublicKey, DisplayName, LanApproval, LanAttachmentRef, LanBatchId,
        LanBatchPlan, LanBatchSnapshot, LanItemOutcome, LanItemPlan, MAX_ATTACHMENT_BYTES,
        MAX_BATCH_ITEMS, MAX_BATCH_TOTAL_BYTES, MAX_PREVIEW_TITLE_CHARS,
    };

    const DIGEST_A: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const DIGEST_B: &str = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    fn batch_id(raw: &str) -> LanBatchId {
        LanBatchId::parse(raw).expect("fixture batch id is valid")
    }

    fn item(batch: &LanBatchId, index: u16, title: &str) -> LanItemPlan {
        LanItemPlan::new(
            batch,
            index,
            1_700_000_000_000,
            DIGEST_A,
            128,
            title,
            Vec::new(),
        )
        .expect("fixture item plan is valid")
    }

    fn sender() -> (DeviceId, DisplayName) {
        let key = DevicePublicKey::parse(&sample_key()).expect("sample key is a valid P-256 point");
        (
            DeviceId::derive(&key),
            DisplayName::parse("Phone").expect("fixture name is valid"),
        )
    }

    /// A real P-256 point so `DevicePublicKey::parse` succeeds without generating a key pair.
    fn sample_key() -> Vec<u8> {
        use aws_lc_rs::encoding::AsBigEndian;
        use aws_lc_rs::signature::{ECDSA_P256_SHA256_ASN1_SIGNING, EcdsaKeyPair, KeyPair};
        let pair =
            EcdsaKeyPair::generate(&ECDSA_P256_SHA256_ASN1_SIGNING).expect("key pair generates");
        let bytes: aws_lc_rs::encoding::EcPublicKeyUncompressedBin<'_> =
            pair.public_key().as_be_bytes().expect("public key exports");
        bytes.as_ref().to_vec()
    }

    #[test]
    fn a_batch_inside_the_limits_is_accepted_and_reports_its_totals() {
        let id = batch_id("batch-inside-limits");
        let plan = LanBatchPlan::new(
            id.clone(),
            vec![item(&id, 0, "first"), item(&id, 1, "second")],
        )
        .expect("a two-item batch is inside the limits");

        assert_eq!(plan.item_count(), 2);
        assert_eq!(plan.total_bytes(), 256);
        assert_eq!(plan.attachment_count(), 0);
    }

    #[test]
    fn more_than_the_item_ceiling_is_rejected_before_transfer() {
        let id = batch_id("batch-too-many-items");
        let items = (0..=u16::try_from(MAX_BATCH_ITEMS).expect("ceiling fits u16"))
            .map(|index| item(&id, index, "title"))
            .collect();

        let error = LanBatchPlan::new(id, items).expect_err("more than 100 items is rejected");
        assert_eq!(error.category(), ErrorCategory::ResourceLimit);
        assert_eq!(error.code(), "lan_batch_too_many_items");
    }

    #[test]
    fn a_single_oversize_attachment_is_rejected_before_transfer() {
        let error = LanAttachmentRef::new(0, "huge.bin", DIGEST_B, MAX_ATTACHMENT_BYTES + 1)
            .expect_err("an attachment above 100 MiB is rejected");
        assert_eq!(error.category(), ErrorCategory::ResourceLimit);
        assert_eq!(error.code(), "lan_attachment_too_large");

        LanAttachmentRef::new(0, "at-ceiling.bin", DIGEST_B, MAX_ATTACHMENT_BYTES)
            .expect("exactly the ceiling is accepted");
    }

    #[test]
    fn a_batch_above_the_total_byte_ceiling_is_rejected_before_transfer() {
        let id = batch_id("batch-oversize-total");
        let half = MAX_BATCH_TOTAL_BYTES / 2 + 1;
        let items = (0..2_u16)
            .map(|index| {
                LanItemPlan::new(
                    &id,
                    index,
                    1_700_000_000_000,
                    DIGEST_A,
                    half,
                    "title",
                    Vec::new(),
                )
                .expect("item plan is valid on its own")
            })
            .collect();

        let error =
            LanBatchPlan::new(id, items).expect_err("a batch above 100 MiB total is rejected");
        assert_eq!(error.category(), ErrorCategory::ResourceLimit);
        assert_eq!(error.code(), "lan_batch_too_large");
    }

    #[test]
    fn the_preview_carries_counts_and_truncated_titles_but_never_content() {
        let id = batch_id("batch-preview");
        let long_title = "标题".repeat(200);
        let plan = LanBatchPlan::new(
            id.clone(),
            vec![item(&id, 0, "short title"), item(&id, 1, &long_title)],
        )
        .expect("preview batch is inside the limits");

        let (device_id, display_name) = sender();
        let preview = plan.preview(&device_id, &display_name);

        assert_eq!(preview.sender_device_id(), &device_id);
        assert_eq!(preview.item_count(), 2);
        assert_eq!(preview.total_bytes(), 256);
        assert_eq!(preview.titles().len(), 2);
        for title in preview.titles() {
            assert!(
                title.chars().count() <= MAX_PREVIEW_TITLE_CHARS,
                "preview titles are truncated to the ceiling: {title}"
            );
        }
        assert!(
            !preview
                .titles()
                .iter()
                .any(|title| title.len() > 4 * MAX_PREVIEW_TITLE_CHARS),
            "truncation must happen on character boundaries, not byte slicing"
        );
    }

    #[test]
    fn approval_is_reusable_inside_its_ttl_and_expires_after_it() {
        let id = batch_id("batch-approval-ttl");
        let approval = LanApproval::granted(id, 1_700_000_000_000, 600_000);

        approval
            .assert_valid_at(1_700_000_000_000)
            .expect("approval is valid at the instant it is granted");
        approval
            .assert_valid_at(1_700_000_599_999)
            .expect("approval is valid inside the TTL, so recovery resumes without re-approval");

        let error = approval
            .assert_valid_at(1_700_000_600_001)
            .expect_err("approval expires after the TTL");
        assert_eq!(error.category(), ErrorCategory::Permission);
        assert_eq!(error.code(), "lan_approval_expired");

        let other = batch_id("batch-other");
        let error = approval
            .assert_covers(&other)
            .expect_err("an approval covers exactly one batch");
        assert_eq!(error.code(), "lan_approval_batch_mismatch");
    }

    #[test]
    fn one_failed_item_yields_explicit_partial_completion_without_rolling_back_the_rest() {
        let id = batch_id("batch-partial");
        let plan = LanBatchPlan::new(
            id.clone(),
            vec![
                item(&id, 0, "ok"),
                item(&id, 1, "fails"),
                item(&id, 2, "ok"),
            ],
        )
        .expect("three-item batch is inside the limits");

        let mut snapshot = LanBatchSnapshot::pending(&plan);
        assert!(!snapshot.is_complete());

        snapshot
            .record(
                plan.items()[0].item_id(),
                LanItemOutcome::committed("memo-a"),
            )
            .expect("recording the first commit succeeds");
        snapshot
            .record(
                plan.items()[1].item_id(),
                LanItemOutcome::failed("lan_item_digest_mismatch"),
            )
            .expect("recording the failure succeeds");
        snapshot
            .record(
                plan.items()[2].item_id(),
                LanItemOutcome::committed("memo-c"),
            )
            .expect("recording the third commit succeeds");

        assert!(
            snapshot.is_complete(),
            "every item reached a terminal state"
        );
        assert!(
            snapshot.is_partially_failed(),
            "a batch with a failed item must report partial completion, not success"
        );
        assert_eq!(snapshot.committed_memo_ids(), vec!["memo-a", "memo-c"]);
        assert_eq!(
            snapshot.retryable_item_ids(),
            vec![plan.items()[1].item_id().clone()],
            "only the failed item is retryable; committed items are never rolled back"
        );
    }

    #[test]
    fn replaying_a_committed_item_returns_the_existing_result_and_creates_no_second_memo() {
        let id = batch_id("batch-replay");
        let plan =
            LanBatchPlan::new(id.clone(), vec![item(&id, 0, "once")]).expect("batch is valid");
        let mut snapshot = LanBatchSnapshot::pending(&plan);
        let item_id = plan.items()[0].item_id().clone();

        snapshot
            .record(&item_id, LanItemOutcome::committed("memo-a"))
            .expect("first commit recorded");

        let replayed = snapshot
            .record(&item_id, LanItemOutcome::committed("memo-b"))
            .expect("replaying a committed item is idempotent, not an error");
        assert_eq!(
            replayed,
            LanItemOutcome::committed("memo-a"),
            "the existing result is returned; a second memo id is never adopted"
        );
        assert_eq!(snapshot.committed_memo_ids(), vec!["memo-a"]);
    }

    #[test]
    fn identical_content_in_two_batches_still_yields_distinct_item_ids() {
        let first = batch_id("batch-one");
        let second = batch_id("batch-two");

        let left = item(&first, 0, "same title");
        let right = item(&second, 0, "same title");

        assert_ne!(
            left.item_id(),
            right.item_id(),
            "two different transfers must never collapse into one memo"
        );
        assert_eq!(
            left.item_id(),
            item(&first, 0, "same title").item_id(),
            "the same transfer position is stable across retries"
        );
    }

    #[test]
    fn a_shared_attachment_digest_is_listed_once_but_tracked_by_every_referencing_item() {
        let id = batch_id("batch-shared-attachment");
        let shared = LanAttachmentRef::new(0, "shared.png", DIGEST_B, 2_048)
            .expect("attachment ref is valid");

        let plan = LanBatchPlan::new(
            id.clone(),
            vec![
                LanItemPlan::new(
                    &id,
                    0,
                    1_700_000_000_000,
                    DIGEST_A,
                    64,
                    "first",
                    vec![shared.clone()],
                )
                .expect("item plan is valid"),
                LanItemPlan::new(
                    &id,
                    1,
                    1_700_000_000_000,
                    DIGEST_A,
                    64,
                    "second",
                    vec![shared],
                )
                .expect("item plan is valid"),
            ],
        )
        .expect("batch is inside the limits");

        assert_eq!(
            plan.distinct_attachment_digests().len(),
            1,
            "a shared attachment digest transfers once"
        );
        assert_eq!(
            plan.total_bytes(),
            64 + 64 + 2_048,
            "shared attachment bytes are counted once against the batch ceiling"
        );
        assert_eq!(
            plan.attachment_count(),
            2,
            "each item still tracks its own reference"
        );
    }
}
