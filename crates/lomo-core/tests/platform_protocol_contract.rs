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
//! - Given SAF listing metadata, when a later content read is planned, then the provider's opaque
//!   document handle is preserved independently of the mutable display path.
//! - Given listing evidence, when content was not hashed, then the digest is `Unknown` and is not
//!   the SHA-256 of empty bytes used as a missing-hash sentinel.
//!
//! Observable outcomes: batch fields, exact action order, structured validation errors, and a
//! validated ordered result prefix.
//! TDD proof: the first run fails because the versioned platform protocol types do not exist;
//! The current platform protocol tests are the executable evidence.
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
        DocumentHandle, DocumentKind, DocumentLocator, DocumentMetadata, ExchangeArtifact,
        ExpectedFingerprint, JobId, MetadataPage, PageSize, PlatformAction, PlatformActionBatch,
        PlatformActionOutput, PlatformBatchResult, RelativeWorkspacePath, Sha256Digest,
        WorkspaceTarget, WriteMode,
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

    #[test]
    fn durable_batch_deserialization_rechecks_shape_invariants() {
        let empty = serde_json::json!({
            "schema_version": 1,
            "job_id": "job-1",
            "batch_id": "batch-1",
            "attempt": 1,
            "deadline_epoch_millis": 1,
            "actions": []
        });
        assert!(
            serde_json::from_value::<PlatformActionBatch>(empty)
                .err()
                .is_some()
        );

        let unknown_schema = serde_json::json!({
            "schema_version": 99,
            "job_id": "job-1",
            "batch_id": "batch-1",
            "attempt": 1,
            "deadline_epoch_millis": 1,
            "actions": []
        });
        assert!(
            serde_json::from_value::<PlatformActionBatch>(unknown_schema)
                .err()
                .is_some()
        );
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
    fn unknown_content_digest_is_not_the_empty_file_hash() {
        let empty =
            Sha256Digest::parse("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                .must_succeed("empty digest");
        let verified = ActionEvidence::verified(0, empty.clone(), "fingerprint-emptyfile")
            .must_succeed("verified empty file");
        let unknown =
            ActionEvidence::unknown(0, "fingerprint-unhashed").must_succeed("unknown listing");
        assert_ne!(verified, unknown);
        assert_eq!(verified.verified_digest(), Some(&empty));
        assert_eq!(unknown.verified_digest(), None);
        assert_eq!(
            verified.content_digest(),
            &lomo_core::ContentDigest::Verified(empty)
        );
        assert_eq!(unknown.content_digest(), &lomo_core::ContentDigest::Unknown);
    }

    #[test]
    fn listed_document_handle_is_the_identity_used_by_a_later_read() {
        let handle =
            DocumentHandle::parse("provider:opaque/document-42").must_succeed("document handle");
        let metadata = DocumentMetadata::new_with_handle(
            WorkspaceTarget::Relative(path("mutable-name.md")),
            handle.clone(),
            DocumentKind::File,
            Some("text/markdown"),
            ActionEvidence::verified(
                12,
                Sha256Digest::parse(&"a".repeat(64)).must_succeed("digest"),
                "fingerprint-1",
            )
            .must_succeed("evidence"),
        )
        .must_succeed("metadata");

        assert_eq!(metadata.document_handle(), &handle);
        let read = PlatformAction::read_listed_to_exchange(
            action_id(9),
            CapabilityToken::parse("root-capability").must_succeed("capability"),
            path("mutable-name.md"),
            handle.clone(),
            "exchange-read-handle",
            ExpectedFingerprint::absent(),
        )
        .must_succeed("read action");
        let PlatformAction::ReadToExchange { locator, .. } = read else {
            panic!("expected read action");
        };
        assert_eq!(locator, DocumentLocator::Opaque(handle));
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

    fn witness_test_batch() -> (PlatformActionBatch, PlatformActionOutput) {
        let capability = CapabilityToken::parse("root-capability").must_succeed("capability");
        let artifact = ExchangeArtifact::new(
            "exchange-write-1",
            12,
            Sha256Digest::parse(&"b".repeat(64)).must_succeed("digest"),
        )
        .must_succeed("exchange artifact");
        let batch = PlatformActionBatch::new(
            JobId::parse("job-witness").must_succeed("job id"),
            BatchId::parse("batch-witness").must_succeed("batch id"),
            1,
            1_800_000_000_000,
            vec![
                PlatformAction::ensure_directory(action_id(1), capability.clone(), path("images")),
                PlatformAction::write_from_exchange(
                    action_id(2),
                    capability,
                    artifact,
                    path("memo.md"),
                    WriteMode::Replace,
                    ExpectedFingerprint::absent(),
                ),
            ],
        )
        .must_succeed("valid batch");

        let directory_ready = PlatformActionOutput::DirectoryReady {
            metadata: fixture_metadata(path("images"), DocumentKind::Directory, 0, "c"),
        };
        (batch, directory_ready)
    }

    fn check_rejected_output(
        batch: &PlatformActionBatch,
        outputs: Vec<PlatformActionOutput>,
        reason: &str,
    ) -> String {
        PlatformBatchResult::new(
            1,
            JobId::parse("job-witness").must_succeed("job id"),
            BatchId::parse("batch-witness").must_succeed("batch id"),
            1,
            outputs
                .into_iter()
                .enumerate()
                .map(|(index, output)| {
                    ActionResult::new(action_id(index + 1), ActionOutcome::Applied(output))
                })
                .collect(),
        )
        .validate_against(batch)
        .must_fail(reason)
        .diagnostic()
        .to_owned()
    }

    #[test]
    fn a_rejected_output_names_its_position_and_the_field_that_diverged() {
        let (batch, directory_ready) = witness_test_batch();
        let reject = |outputs, reason| check_rejected_output(&batch, outputs, reason);

        let wrong_shape = reject(
            vec![PlatformActionOutput::Stat {
                metadata: fixture_metadata(path("images"), DocumentKind::Directory, 0, "c"),
            }],
            "a stat output cannot witness an ensure-directory action",
        );
        assert!(
            wrong_shape.contains("index 0")
                && wrong_shape.contains("EnsureDirectory")
                && wrong_shape.contains("output shape expected EnsureDirectory, observed Stat"),
            "{wrong_shape}"
        );

        let wrong_kind = reject(
            vec![PlatformActionOutput::DirectoryReady {
                metadata: fixture_metadata(path("images"), DocumentKind::File, 12, "c"),
            }],
            "a file cannot witness an ensure-directory action",
        );
        assert!(
            wrong_kind.contains("index 0")
                && wrong_kind.contains("document kind expected Directory, observed File"),
            "{wrong_kind}"
        );

        let wrong_target = reject(
            vec![
                directory_ready.clone(),
                PlatformActionOutput::WriteComplete {
                    metadata: fixture_metadata(path("other.md"), DocumentKind::File, 12, "b"),
                },
            ],
            "a write to another path cannot witness this write action",
        );
        assert!(
            wrong_target.contains("index 1")
                && wrong_target.contains("target expected memo.md, observed other.md"),
            "{wrong_target}"
        );

        let wrong_digest = reject(
            vec![
                directory_ready,
                PlatformActionOutput::WriteComplete {
                    metadata: fixture_metadata(path("memo.md"), DocumentKind::File, 12, "a"),
                },
            ],
            "persisted bytes that differ from the artifact cannot witness this write action",
        );
        assert!(
            wrong_digest.contains("index 1")
                && wrong_digest.contains(&format!(
                    "digest expected {}, observed {}",
                    "b".repeat(64),
                    "a".repeat(64)
                )),
            "{wrong_digest}"
        );
    }

    fn fixture_metadata(
        target: RelativeWorkspacePath,
        kind: DocumentKind,
        length: u64,
        digest_byte: &str,
    ) -> DocumentMetadata {
        DocumentMetadata::new(
            WorkspaceTarget::Relative(target),
            kind,
            Some("application/octet-stream"),
            ActionEvidence::verified(
                length,
                Sha256Digest::parse(&digest_byte.repeat(64)).must_succeed("digest"),
                "fingerprint-witness",
            )
            .must_succeed("evidence"),
        )
        .must_succeed("metadata")
    }
}
