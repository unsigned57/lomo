//! Behavior Contract
//!
//! Capability: represent every Android platform side effect as a bounded, versioned, identity-
//! preserving batch whose result advances only an ordered action prefix with verified evidence.
//!
//! Scenarios:
//! - Given all seven action kinds, when a batch is built, then it carries no workspace bytes and
//!   preserves job, batch, attempt, capability, action, path, page, exchange, and postcondition.
//! - Given zero or more than 64 actions, when a batch is built, then it is rejected rather than
//!   omitted, split implicitly, or truncated.
//! - Given a result with the wrong identity or action order, when validated, then it cannot advance.
//! - Given an already-satisfied result, when evidence is built, then digest, length, and fingerprint
//!   are all mandatory.
//!
//! Observable outcomes: batch fields, exact action order, structured validation errors, and a
//! validated ordered result prefix.
//! TDD proof: the first run fails because the versioned platform protocol types do not exist;
//! GREEN is recorded in `STAGE1-EVIDENCE.md`.
//! Excludes: Android `ContentResolver` execution, actor scheduling, journal receipts, and FFI DTOs.

#[cfg(test)]
#[path = "support/failure.rs"]
mod failure_support;
#[cfg(test)]
#[path = "support/success.rs"]
mod support;

#[cfg(test)]
mod tests {

    use lomo_core::{
        ActionEvidence, ActionId, ActionOutcome, ActionResult, BatchId, CapabilityToken,
        DocumentKind, DocumentMetadata, ExchangeArtifact, ExpectedFingerprint, JobId, MetadataPage,
        PageSize, PlatformAction, PlatformActionBatch, PlatformActionOutput, PlatformBatchResult,
        RelativeWorkspacePath, Sha256Digest, WorkspaceTarget, WriteMode,
    };

    use super::failure_support::ResultFailureTestExt;
    use super::support::ResultTestExt;

    fn path(raw: &str) -> RelativeWorkspacePath {
        RelativeWorkspacePath::parse(raw).must_succeed("valid fixture path")
    }

    fn action_id(index: usize) -> ActionId {
        ActionId::parse(&format!("action-{index}")).must_succeed("valid action id")
    }

    fn fixture_actions() -> Vec<PlatformAction> {
        let capability = CapabilityToken::parse("root-capability").must_succeed("capability");
        let expected = ExpectedFingerprint::absent();
        let digest = Sha256Digest::parse(&"b".repeat(64)).must_succeed("digest");
        let artifact =
            ExchangeArtifact::new("exchange-write-1", 12, digest).must_succeed("exchange artifact");
        vec![
            PlatformAction::stat(action_id(1), capability.clone(), path("memo.md")),
            PlatformAction::list_children(
                action_id(2),
                capability.clone(),
                path("memos"),
                None,
                PageSize::new(256).must_succeed("bounded page"),
            ),
            PlatformAction::ensure_directory(action_id(3), capability.clone(), path("images")),
            PlatformAction::read_to_exchange(
                action_id(4),
                capability.clone(),
                path("memo.md"),
                "exchange-read-1",
                expected.clone(),
            )
            .must_succeed("exchange token"),
            PlatformAction::write_from_exchange(
                action_id(5),
                capability.clone(),
                artifact,
                path("memo.md"),
                WriteMode::Replace,
                expected.clone(),
            ),
            PlatformAction::move_path(
                action_id(6),
                capability.clone(),
                path("memo.md"),
                path("trash/memo.md"),
                expected.clone(),
                ExpectedFingerprint::absent(),
            ),
            PlatformAction::delete(action_id(7), capability, path("trash/memo.md"), expected),
        ]
    }

    fn fixture_batch() -> PlatformActionBatch {
        PlatformActionBatch::new(
            JobId::parse("job-1").must_succeed("job id"),
            BatchId::parse("batch-1").must_succeed("batch id"),
            1,
            1_800_000_000_000,
            fixture_actions(),
        )
        .must_succeed("valid batch")
    }

    fn metadata() -> DocumentMetadata {
        DocumentMetadata::new(
            WorkspaceTarget::Relative(path("memo.md")),
            DocumentKind::File,
            Some("text/markdown"),
            ActionEvidence::verified(
                12,
                Sha256Digest::parse(&"a".repeat(64)).must_succeed("digest"),
                "fingerprint-1",
            )
            .must_succeed("evidence"),
        )
        .must_succeed("metadata")
    }

    #[test]
    fn batch_contains_every_bounded_platform_action_without_content_bytes() {
        let batch = fixture_batch();
        assert_eq!(batch.schema_version(), 1);
        assert_eq!(batch.actions().len(), 7);
        assert_eq!(batch.attempt(), 1);

        let error = PlatformActionBatch::new(
            JobId::parse("job-empty").must_succeed("job id"),
            BatchId::parse("batch-empty").must_succeed("batch id"),
            1,
            1_800_000_000_000,
            Vec::new(),
        )
        .must_fail("empty batch must be explicit invalid state");
        assert_eq!(error.code(), "invalid_platform_batch_size");

        let oversized = (0..65)
            .map(|index| {
                PlatformAction::stat(
                    action_id(index),
                    CapabilityToken::parse("root-capability").must_succeed("capability"),
                    path("memo.md"),
                )
            })
            .collect();
        PlatformActionBatch::new(
            JobId::parse("job-large").must_succeed("job id"),
            BatchId::parse("batch-large").must_succeed("batch id"),
            1,
            1_800_000_000_000,
            oversized,
        )
        .must_fail("65 actions must be rejected");
    }

    #[test]
    fn only_an_ordered_identity_matching_result_prefix_validates() {
        let batch = fixture_batch();
        let first = ActionResult::new(
            action_id(1),
            ActionOutcome::AlreadySatisfied(PlatformActionOutput::Stat {
                metadata: metadata(),
            }),
        );
        let valid = PlatformBatchResult::new(
            1,
            JobId::parse("job-1").must_succeed("job id"),
            BatchId::parse("batch-1").must_succeed("batch id"),
            1,
            vec![first.clone()],
        );
        assert_eq!(
            valid.validate_against(&batch).must_succeed("valid prefix"),
            1
        );

        let wrong_order = PlatformBatchResult::new(
            1,
            JobId::parse("job-1").must_succeed("job id"),
            BatchId::parse("batch-1").must_succeed("batch id"),
            1,
            vec![ActionResult::new(
                action_id(2),
                ActionOutcome::Applied(PlatformActionOutput::Stat {
                    metadata: metadata(),
                }),
            )],
        );
        assert_eq!(
            wrong_order
                .validate_against(&batch)
                .must_fail("out-of-order result")
                .code(),
            "platform_result_action_mismatch"
        );

        let wrong_attempt = PlatformBatchResult::new(
            1,
            JobId::parse("job-1").must_succeed("job id"),
            BatchId::parse("batch-1").must_succeed("batch id"),
            2,
            vec![first],
        );
        assert_eq!(
            wrong_attempt
                .validate_against(&batch)
                .must_fail("wrong attempt")
                .code(),
            "platform_result_identity_mismatch"
        );

        let wrong_output = PlatformBatchResult::new(
            1,
            JobId::parse("job-1").must_succeed("job id"),
            BatchId::parse("batch-1").must_succeed("batch id"),
            1,
            vec![ActionResult::new(
                action_id(1),
                ActionOutcome::Applied(PlatformActionOutput::Listed {
                    page: MetadataPage::new(vec![metadata()], None).must_succeed("metadata page"),
                }),
            )],
        );
        assert_eq!(
            wrong_output
                .validate_against(&batch)
                .must_fail("stat cannot accept a list-page output")
                .code(),
            "platform_result_output_mismatch"
        );
    }
}
