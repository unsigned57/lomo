//! Behavior Contract
//!
//! Capability: execute the complete set of 8 platform action protocols (`Stat`, `ListChildren`,
//! `EnsureDirectory`, `ReadToExchange`, `WriteFromExchange`, `ArtifactWrite`, `Move`, `Delete`)
//! on a real Linux POSIX filesystem with root capability binding, symlink escape rejection,
//! private exchange staging, SHA-256 baseline verification, and temp-fsync-rename atomicity.
//!
//! Scenarios:
//! - Given an unbound capability token, when an action is executed, then it is rejected with a permission error.
//! - Given a path traversing outside the root via a symlink, when executed, then symlink escape is rejected.
//! - Given `Stat` on existing file/dir or root, when executed, then metadata with valid SHA-256 evidence is returned.
//! - Given `EnsureDirectory`, when executed, then it idempotently ensures directory readiness.
//! - Given `ReadToExchange`, when executed, then source content is streamed into private exchange and verified.
//! - Given `WriteFromExchange` with mismatched expected fingerprint, when executed, then baseline conflict is detected and overwrite is refused.
//! - Given `WriteFromExchange` with `WriteMode::Create` on existing target, when executed, then overwrite is refused.
//! - Given `WriteFromExchange` with valid artifact, when executed, then atomic temp-fsync-rename writes durable bytes.
//! - Given `ArtifactWrite` over a retained staged source, when executed, then bytes stream through temp storage and publish only after digest/length re-verification.
//! - Given `ArtifactWrite` replay states, when the target already holds the declared digest then `AlreadySatisfied`, when a third party owns the target then fail-closed conflict, when an incomplete temp copy remains then the retry redoes the write and reclaims the orphan.
//! - Given `ArtifactWrite` with a missing or digest-mismatched source, when executed, then it fails before touching the target.
//! - Given `Move`, when executed, then source is renamed to target with precondition and satisfaction checks.
//! - Given `Delete`, when executed, then target is removed with verified absence evidence.
//! - Given a batch of actions where one fails, when executed, then execution stops at the first failure and returns a valid prefix.
//!
//! Observable outcomes:
//! - `ActionResult` with `Applied`, `AlreadySatisfied`, or `Failed`
//! - Verifiable `ActionEvidence` with length, digest, and fingerprint
//! - Strict rejection of symlink escapes and unbound capabilities
//!
//! TDD proof:
//! - Fails RED initially because `FsPlatformActionExecutor` does not exist.
//!
//! Excludes:
//! - Android SAF, JNI, SQLite projections, UI presentation, network transfer.

#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    use std::fs;

    use lomo_core::{
        ActionEvidence, ActionId, ActionOutcome, ActionResult, BatchId, CapabilityToken,
        DocumentKind, ErrorCategory, ExchangeArtifact, ExpectedFingerprint, JobId, PageSize,
        PlatformAction, PlatformActionBatch, PlatformActionExecutor, PlatformActionOutput,
        RelativeWorkspacePath, Sha256Digest, StagedArtifactSource, WriteMode,
    };
    use lomo_platform_fs::FsPlatformActionExecutor;
    use tempfile::TempDir;

    use super::support::ResultTestExt;

    fn dummy_action_id(n: usize) -> ActionId {
        ActionId::parse(&format!("action-{n}")).must_succeed("valid action id")
    }

    fn dummy_job_id() -> JobId {
        JobId::parse("job-1").must_succeed("valid job id")
    }

    fn dummy_batch_id() -> BatchId {
        BatchId::parse("batch-1").must_succeed("valid batch id")
    }

    fn sha256_hex(data: &[u8]) -> Sha256Digest {
        use sha2::{Digest, Sha256};
        let mut hasher = Sha256::new();
        hasher.update(data);
        let hex = format!("{:x}", hasher.finalize());
        Sha256Digest::parse(&hex).must_succeed("valid sha256 hex")
    }

    struct TestHarness {
        _temp_dir: TempDir,
        root: std::path::PathBuf,
        exchange_dir: std::path::PathBuf,
        stage_dir: std::path::PathBuf,
        executor: FsPlatformActionExecutor,
        capability: CapabilityToken,
    }

    impl TestHarness {
        fn new() -> Self {
            let temp_dir = TempDir::new().must_succeed("create temp dir");
            let root = temp_dir.path().join("workspace");
            let exchange_dir = temp_dir.path().join("exchange");
            let stage_dir = temp_dir.path().join("media-stage");
            fs::create_dir_all(&root).must_succeed("create root");
            fs::create_dir_all(&exchange_dir).must_succeed("create exchange");
            fs::create_dir_all(&stage_dir).must_succeed("create stage dir");

            let executor =
                FsPlatformActionExecutor::new(&exchange_dir).must_succeed("create executor");
            let capability = CapabilityToken::parse("workspace-root").must_succeed("cap token");
            executor
                .bind_root(capability.clone(), &root)
                .must_succeed("bind root");

            Self {
                _temp_dir: temp_dir,
                root,
                exchange_dir,
                stage_dir,
                executor,
                capability,
            }
        }

        fn create_file(&self, rel_path: &str, content: &[u8]) {
            let full_path = self.root.join(rel_path);
            if let Some(parent) = full_path.parent() {
                fs::create_dir_all(parent).must_succeed("parent dir");
            }
            fs::write(&full_path, content).must_succeed("write file");
        }

        fn create_exchange_artifact(&self, token_str: &str, content: &[u8]) -> ExchangeArtifact {
            let path = self.exchange_dir.join(token_str);
            fs::write(&path, content).must_succeed("write exchange file");
            let digest = sha256_hex(content);
            ExchangeArtifact::new(token_str, content.len() as u64, digest)
                .must_succeed("create exchange artifact")
        }

        /// Retains `content` as a durable staged artifact outside the bound workspace root,
        /// mirroring the host's private media stage directory.
        fn stage_artifact(&self, name: &str, content: &[u8]) -> StagedArtifactSource {
            let path = self.stage_dir.join(name);
            fs::write(&path, content).must_succeed("write staged source");
            StagedArtifactSource::new(
                path.to_str()
                    .unwrap_or_else(|| panic!("staged path is not UTF-8")),
                content.len() as u64,
                sha256_hex(content),
            )
            .must_succeed("staged artifact source")
        }
    }

    #[test]
    fn unbound_capability_is_rejected() {
        let harness = TestHarness::new();
        let unknown_cap = CapabilityToken::parse("unknown-cap").must_succeed("token");
        let path = RelativeWorkspacePath::parse("note.md").must_succeed("path");
        let action = PlatformAction::stat(dummy_action_id(1), unknown_cap, path);

        let result = harness.executor.execute_action(&action);
        let ActionOutcome::Failed(err) = result.outcome() else {
            panic!("expected failure, got {:?}", result.outcome());
        };
        assert_eq!(err.category(), ErrorCategory::Permission);
        assert_eq!(err.code(), "capability_unbound");
    }

    #[test]
    fn symlink_escape_is_strictly_rejected() {
        let harness = TestHarness::new();
        // Create an outside secret file
        let outside_dir = TempDir::new().must_succeed("outside dir");
        let secret_file = outside_dir.path().join("secret.txt");
        fs::write(&secret_file, b"super-secret").must_succeed("write secret");

        // Create a symlink pointing outside the root
        let symlink_path = harness.root.join("escape_link.md");
        std::os::unix::fs::symlink(&secret_file, &symlink_path).must_succeed("create symlink");

        let path = RelativeWorkspacePath::parse("escape_link.md").must_succeed("path");
        let action = PlatformAction::stat(dummy_action_id(1), harness.capability.clone(), path);

        let result = harness.executor.execute_action(&action);
        let ActionOutcome::Failed(err) = result.outcome() else {
            panic!(
                "expected symlink escape rejected, got {:?}",
                result.outcome()
            );
        };
        assert_eq!(err.category(), ErrorCategory::Permission);
        assert_eq!(err.code(), "symlink_escape_rejected");
    }

    #[test]
    fn stat_and_ensure_directory_and_list_children() {
        let harness = TestHarness::new();
        harness.create_file("sub/file1.txt", b"hello world");
        harness.create_file("sub/file2.txt", b"foo bar baz");

        // Stat file
        let file_path = RelativeWorkspacePath::parse("sub/file1.txt").must_succeed("path");
        let stat_action =
            PlatformAction::stat(dummy_action_id(1), harness.capability.clone(), file_path);
        let stat_res = harness.executor.execute_action(&stat_action);
        let ActionOutcome::Applied(PlatformActionOutput::Stat { metadata }) = stat_res.outcome()
        else {
            panic!("unexpected stat outcome: {:?}", stat_res.outcome());
        };
        assert_eq!(metadata.kind(), DocumentKind::File);
        assert_eq!(metadata.evidence().length(), 11);
        assert_eq!(
            metadata.evidence().verified_digest(),
            Some(&sha256_hex(b"hello world"))
        );

        // EnsureDirectory
        let dir_path = RelativeWorkspacePath::parse("sub/new_dir").must_succeed("dir path");
        let ensure_action = PlatformAction::ensure_directory(
            dummy_action_id(2),
            harness.capability.clone(),
            dir_path,
        );
        let ensure_res = harness.executor.execute_action(&ensure_action);
        assert!(matches!(
            ensure_res.outcome(),
            ActionOutcome::Applied(PlatformActionOutput::DirectoryReady { .. })
        ));
        // EnsureDirectory idempotent -> AlreadySatisfied
        let ensure_again = harness.executor.execute_action(&ensure_action);
        assert!(matches!(
            ensure_again.outcome(),
            ActionOutcome::AlreadySatisfied(PlatformActionOutput::DirectoryReady { .. })
        ));

        // ListChildren
        let sub_path = RelativeWorkspacePath::parse("sub").must_succeed("sub path");
        let list_action = PlatformAction::list_children(
            dummy_action_id(3),
            harness.capability.clone(),
            sub_path,
            None,
            PageSize::new(10).must_succeed("page size"),
        );
        let list_res = harness.executor.execute_action(&list_action);
        let ActionOutcome::Applied(PlatformActionOutput::Listed { page }) = list_res.outcome()
        else {
            panic!("unexpected list outcome: {:?}", list_res.outcome());
        };
        assert_eq!(page.items().len(), 3); // file1.txt, file2.txt, new_dir
    }

    #[test]
    fn read_to_exchange_streams_and_verifies_evidence() {
        let harness = TestHarness::new();
        let content = b"read-stream-content-test";
        harness.create_file("source.txt", content);

        let path = RelativeWorkspacePath::parse("source.txt").must_succeed("path");
        let read_action = PlatformAction::read_to_exchange(
            dummy_action_id(1),
            harness.capability.clone(),
            path,
            "read-token-1",
            ExpectedFingerprint::absent(),
        )
        .must_succeed("action");

        let res = harness.executor.execute_action(&read_action);
        let ActionOutcome::Applied(PlatformActionOutput::ReadToExchange {
            source_metadata,
            artifact,
        }) = res.outcome()
        else {
            panic!("unexpected read outcome: {:?}", res.outcome());
        };
        assert_eq!(source_metadata.evidence().length(), content.len() as u64);
        assert_eq!(artifact.token().as_str(), "read-token-1");
        let staged =
            fs::read(harness.exchange_dir.join("read-token-1")).must_succeed("read staged");
        assert_eq!(staged, content);
    }

    #[test]
    fn write_from_exchange_baseline_mismatch_refuses_overwrite() {
        let harness = TestHarness::new();
        harness.create_file("memo.md", b"disk-content-version-1");

        // Prepare exchange file
        let artifact = harness.create_exchange_artifact("write-token-1", b"new-content-draft");
        let path = RelativeWorkspacePath::parse("memo.md").must_succeed("path");

        // Expected fingerprint for another different baseline (simulate external modification)
        let stale_digest = sha256_hex(b"some-other-old-content");
        let stale_evidence = ActionEvidence::verified(22, stale_digest, "fp.stale1234567890abcdef")
            .must_succeed("evidence");

        let write_action = PlatformAction::write_from_exchange(
            dummy_action_id(1),
            harness.capability.clone(),
            artifact,
            path,
            WriteMode::Replace,
            ExpectedFingerprint::matching(stale_evidence),
        );

        let res = harness.executor.execute_action(&write_action);
        let ActionOutcome::Failed(err) = res.outcome() else {
            panic!(
                "expected conflict on mismatched baseline, got {:?}",
                res.outcome()
            );
        };
        assert_eq!(err.category(), ErrorCategory::Conflict);
        assert_eq!(err.code(), "platform_postcondition_mismatch");

        // Verify disk content was NOT overwritten
        let on_disk = fs::read(harness.root.join("memo.md")).must_succeed("read disk");
        assert_eq!(on_disk, b"disk-content-version-1");
    }

    #[test]
    fn write_from_exchange_create_mode_refuses_existing_file() {
        let harness = TestHarness::new();
        harness.create_file("existing.md", b"existing-content");

        let artifact = harness.create_exchange_artifact("write-create-1", b"brand-new-content");
        let path = RelativeWorkspacePath::parse("existing.md").must_succeed("path");

        let write_action = PlatformAction::write_from_exchange(
            dummy_action_id(1),
            harness.capability.clone(),
            artifact,
            path,
            WriteMode::Create,
            ExpectedFingerprint::absent(),
        );

        let res = harness.executor.execute_action(&write_action);
        let ActionOutcome::Failed(err) = res.outcome() else {
            panic!(
                "expected conflict on create mode over existing file, got {:?}",
                res.outcome()
            );
        };
        assert_eq!(err.category(), ErrorCategory::Conflict);
        assert_eq!(err.code(), "platform_postcondition_mismatch");
    }

    #[test]
    fn write_from_exchange_atomic_temp_fsync_rename_success() {
        let harness = TestHarness::new();
        let new_bytes = b"atomic-write-verified-bytes";
        let artifact = harness.create_exchange_artifact("write-atomic-1", new_bytes);
        let path = RelativeWorkspacePath::parse("docs/atomic.md").must_succeed("path");

        // Ensure parent directory
        fs::create_dir_all(harness.root.join("docs")).must_succeed("parent dir");

        let write_action = PlatformAction::write_from_exchange(
            dummy_action_id(1),
            harness.capability.clone(),
            artifact,
            path,
            WriteMode::Create,
            ExpectedFingerprint::absent(),
        );

        let res = harness.executor.execute_action(&write_action);
        let ActionOutcome::Applied(PlatformActionOutput::WriteComplete { metadata }) =
            res.outcome()
        else {
            panic!("expected applied write complete, got {:?}", res.outcome());
        };
        assert_eq!(metadata.evidence().length(), new_bytes.len() as u64);
        assert_eq!(
            metadata.evidence().verified_digest(),
            Some(&sha256_hex(new_bytes))
        );

        // Verify content on disk
        let written = fs::read(harness.root.join("docs/atomic.md")).must_succeed("read file");
        assert_eq!(written, new_bytes);

        // Verify no leftover temporary files in docs/
        let entries = fs::read_dir(harness.root.join("docs")).must_succeed("read dir");
        let names: Vec<_> = entries
            .map(|e| {
                e.must_succeed("entry")
                    .file_name()
                    .to_string_lossy()
                    .to_string()
            })
            .collect();
        assert_eq!(names, vec!["atomic.md"]);

        // Replay same write -> AlreadySatisfied
        let replay = harness.executor.execute_action(&write_action);
        assert!(matches!(
            replay.outcome(),
            ActionOutcome::AlreadySatisfied(PlatformActionOutput::WriteComplete { .. })
        ));
    }

    #[test]
    fn move_and_delete_lifecycle() {
        let harness = TestHarness::new();
        let content = b"move-me-to-trash";
        harness.create_file("source.md", content);

        let src_path = RelativeWorkspacePath::parse("source.md").must_succeed("src");
        let dst_path = RelativeWorkspacePath::parse("trash/source.md").must_succeed("dst");
        fs::create_dir_all(harness.root.join("trash")).must_succeed("trash dir");

        // Contract Note:
        // In previous pseudo-contract implementations, Move accepted `ExpectedFingerprint::absent()`
        // as the source fingerprint even when `source.md` was an existing file. This violated the
        // baseline verification specification because `ExpectedFingerprint::absent()` specifies that
        // the path must NOT exist prior to execution. Moving an existing file requires providing
        // `ExpectedFingerprint::matching(evidence)` corresponding to the verified baseline of `source.md`,
        // while the destination (which does not yet exist) expects `ExpectedFingerprint::absent()`.
        //
        // Similarly, deleting `trash/source.md` when it exists requires matching fingerprint evidence,
        // rather than `ExpectedFingerprint::absent()`. Passing `absent()` to an existing file in Delete
        // is strictly rejected with a conflict error (see `delete_with_absent_baseline_refuses_to_delete_existing_file`).
        let stat_action = PlatformAction::stat(
            dummy_action_id(100),
            harness.capability.clone(),
            src_path.clone(),
        );
        let stat_res = harness.executor.execute_action(&stat_action);
        let ActionOutcome::Applied(PlatformActionOutput::Stat { metadata: src_meta }) =
            stat_res.outcome()
        else {
            panic!("stat failed: {:?}", stat_res.outcome());
        };
        let src_evidence = src_meta.evidence().clone();

        // Move action with matching source evidence and absent destination
        let move_action = PlatformAction::move_path(
            dummy_action_id(1),
            harness.capability.clone(),
            src_path,
            dst_path.clone(),
            ExpectedFingerprint::matching(src_evidence),
            ExpectedFingerprint::absent(),
        );

        let move_res = harness.executor.execute_action(&move_action);
        let ActionOutcome::Applied(PlatformActionOutput::MoveComplete { metadata: dst_meta }) =
            move_res.outcome()
        else {
            panic!("move failed: {:?}", move_res.outcome());
        };
        assert!(!harness.root.join("source.md").exists());
        assert_eq!(
            fs::read(harness.root.join("trash/source.md")).must_succeed("read trash"),
            content
        );

        // Delete action with matching target evidence
        let delete_action = PlatformAction::delete(
            dummy_action_id(2),
            harness.capability.clone(),
            dst_path,
            ExpectedFingerprint::matching(dst_meta.evidence().clone()),
        );
        let del_res = harness.executor.execute_action(&delete_action);
        assert!(matches!(
            del_res.outcome(),
            ActionOutcome::Applied(PlatformActionOutput::DeleteComplete { .. })
        ));
        assert!(!harness.root.join("trash/source.md").exists());

        // Replay delete -> AlreadySatisfied
        let del_again = harness.executor.execute_action(&delete_action);
        assert!(matches!(
            del_again.outcome(),
            ActionOutcome::AlreadySatisfied(PlatformActionOutput::DeleteComplete { .. })
        ));
    }

    #[test]
    fn batch_execution_stops_at_first_failure_and_witnesses() {
        let harness = TestHarness::new();
        harness.create_file("file1.txt", b"content1");

        let path1 = RelativeWorkspacePath::parse("file1.txt").must_succeed("path");
        let path_nonexistent = RelativeWorkspacePath::parse("nonexistent.txt").must_succeed("path");
        let path3 = RelativeWorkspacePath::parse("file3.txt").must_succeed("path");

        let action1 = PlatformAction::stat(dummy_action_id(1), harness.capability.clone(), path1);
        let action2 = PlatformAction::stat(
            dummy_action_id(2),
            harness.capability.clone(),
            path_nonexistent,
        );
        let action3 = PlatformAction::stat(dummy_action_id(3), harness.capability.clone(), path3);

        let batch = PlatformActionBatch::new(
            dummy_job_id(),
            dummy_batch_id(),
            1,
            u64::MAX, // far in the future
            vec![action1, action2, action3],
        )
        .must_succeed("batch");

        let batch_res = harness
            .executor
            .execute(&batch)
            .must_succeed("execute batch");
        assert_eq!(batch_res.action_results().len(), 2);
        let results = batch_res.action_results();
        let first_outcome = results.first().map(ActionResult::outcome);
        let second_outcome = results.get(1).map(ActionResult::outcome);
        assert!(matches!(first_outcome, Some(ActionOutcome::Applied(_))));
        assert!(matches!(second_outcome, Some(ActionOutcome::Failed(_))));

        // Result validates against batch prefix
        let prefix_len = batch_res
            .validate_against(&batch)
            .must_succeed("validate against");
        assert_eq!(prefix_len, 2);
    }

    #[test]
    fn list_children_symlink_escape_is_strictly_rejected() {
        let harness = TestHarness::new();
        let outside_dir = TempDir::new().must_succeed("outside dir");
        let secret_file = outside_dir.path().join("secret.txt");
        fs::write(&secret_file, b"outside-secret-content").must_succeed("write secret");

        // Symlink pointing outside workspace root
        let symlink_path = harness.root.join("leak_link.txt");
        std::os::unix::fs::symlink(&secret_file, &symlink_path).must_succeed("create symlink");

        let list_action = PlatformAction::list_root(
            dummy_action_id(1),
            harness.capability.clone(),
            None,
            PageSize::new(10).must_succeed("page size"),
        );

        let res = harness.executor.execute_action(&list_action);
        let ActionOutcome::Failed(err) = res.outcome() else {
            panic!(
                "expected list_children to reject symlink escape, got {:?}",
                res.outcome()
            );
        };
        assert_eq!(err.category(), ErrorCategory::Permission);
        assert_eq!(err.code(), "symlink_escape_rejected");
    }

    #[test]
    fn delete_with_absent_baseline_refuses_to_delete_existing_file() {
        let harness = TestHarness::new();
        harness.create_file("important.md", b"pre-existing-content");

        let path = RelativeWorkspacePath::parse("important.md").must_succeed("path");
        // Absent baseline must NOT delete an existing file
        let delete_action = PlatformAction::delete(
            dummy_action_id(1),
            harness.capability.clone(),
            path,
            ExpectedFingerprint::absent(),
        );

        let res = harness.executor.execute_action(&delete_action);
        let ActionOutcome::Failed(err) = res.outcome() else {
            panic!(
                "expected delete with Absent baseline on existing file to fail, got {:?}",
                res.outcome()
            );
        };
        assert_eq!(err.category(), ErrorCategory::Conflict);
        assert_eq!(err.code(), "platform_postcondition_mismatch");

        // Verify the file was not deleted
        assert!(harness.root.join("important.md").exists());
    }

    #[test]
    fn list_children_rejects_non_utf8_filename() {
        use std::os::unix::ffi::OsStrExt;
        let harness = TestHarness::new();
        let bad_filename = std::ffi::OsStr::from_bytes(b"invalid_\xff\xfe_name.txt");
        let bad_file_path = harness.root.join(bad_filename);
        fs::write(&bad_file_path, b"content").must_succeed("write bad file");

        let list_action = PlatformAction::list_root(
            dummy_action_id(1),
            harness.capability.clone(),
            None,
            PageSize::new(10).must_succeed("page size"),
        );

        let res = harness.executor.execute_action(&list_action);
        let ActionOutcome::Failed(err) = res.outcome() else {
            panic!(
                "expected list_children to reject non-UTF8 filename, got {:?}",
                res.outcome()
            );
        };
        assert_eq!(err.category(), ErrorCategory::Validation);
    }

    #[test]
    fn artifact_write_streams_the_retained_source_into_the_workspace() {
        let harness = TestHarness::new();
        let bytes = b"artifact-write-payload";
        let source = harness.stage_artifact("blob-a.bin", bytes);
        let path = RelativeWorkspacePath::parse("media/blob-a.bin").must_succeed("path");

        let action = PlatformAction::artifact_write(
            dummy_action_id(1),
            harness.capability.clone(),
            source,
            path,
            ExpectedFingerprint::absent(),
        );
        let res = harness.executor.execute_action(&action);
        let ActionOutcome::Applied(PlatformActionOutput::WriteComplete { metadata }) =
            res.outcome()
        else {
            panic!("expected applied write complete, got {:?}", res.outcome());
        };
        assert_eq!(metadata.evidence().length(), bytes.len() as u64);
        assert_eq!(
            metadata.evidence().verified_digest(),
            Some(&sha256_hex(bytes))
        );
        assert_eq!(
            fs::read(harness.root.join("media/blob-a.bin")).must_succeed("read target"),
            bytes
        );
        assert!(
            harness.stage_dir.join("blob-a.bin").is_file(),
            "the executor copies the retained source; the lease owner decides reclamation"
        );
    }

    #[test]
    fn artifact_write_is_already_satisfied_when_the_target_holds_the_declared_digest() {
        let harness = TestHarness::new();
        let bytes = b"already-committed-media";
        harness.create_file("media/blob-b.bin", bytes);
        let source = harness.stage_artifact("blob-b.bin", bytes);
        let path = RelativeWorkspacePath::parse("media/blob-b.bin").must_succeed("path");

        let action = PlatformAction::artifact_write(
            dummy_action_id(1),
            harness.capability.clone(),
            source,
            path,
            ExpectedFingerprint::absent(),
        );
        let res = harness.executor.execute_action(&action);
        assert!(
            matches!(
                res.outcome(),
                ActionOutcome::AlreadySatisfied(PlatformActionOutput::WriteComplete { .. })
            ),
            "a target holding the declared digest is satisfaction, not conflict: {:?}",
            res.outcome()
        );
    }

    #[test]
    fn artifact_write_conflicts_when_a_third_party_owns_the_target() {
        let harness = TestHarness::new();
        harness.create_file("media/blob-c.bin", b"foreign-content");
        let source = harness.stage_artifact("blob-c.bin", b"planned-media-bytes");
        let path = RelativeWorkspacePath::parse("media/blob-c.bin").must_succeed("path");

        let action = PlatformAction::artifact_write(
            dummy_action_id(1),
            harness.capability.clone(),
            source,
            path,
            ExpectedFingerprint::absent(),
        );
        let res = harness.executor.execute_action(&action);
        let ActionOutcome::Failed(err) = res.outcome() else {
            panic!("expected conflict, got {:?}", res.outcome());
        };
        assert_eq!(err.category(), ErrorCategory::Conflict);
        assert_eq!(err.code(), "platform_postcondition_mismatch");
        assert_eq!(
            fs::read(harness.root.join("media/blob-c.bin")).must_succeed("read target"),
            b"foreign-content",
            "a third-party target is never overwritten"
        );
    }

    #[test]
    fn artifact_write_replaces_only_a_matched_baseline() {
        let harness = TestHarness::new();
        harness.create_file("media/blob-d.bin", b"baseline-content");
        let path = RelativeWorkspacePath::parse("media/blob-d.bin").must_succeed("path");

        let stat = harness.executor.execute_action(&PlatformAction::stat(
            dummy_action_id(100),
            harness.capability.clone(),
            path.clone(),
        ));
        let ActionOutcome::Applied(PlatformActionOutput::Stat { metadata }) = stat.outcome() else {
            panic!("stat failed: {:?}", stat.outcome());
        };
        let baseline = metadata.evidence().clone();

        let source = harness.stage_artifact("blob-d.bin", b"replacement-media");
        let action = PlatformAction::artifact_write(
            dummy_action_id(1),
            harness.capability.clone(),
            source,
            path,
            ExpectedFingerprint::matching(baseline.clone()),
        );
        let res = harness.executor.execute_action(&action);
        assert!(
            matches!(
                res.outcome(),
                ActionOutcome::Applied(PlatformActionOutput::WriteComplete { .. })
            ),
            "a matched baseline permits replace: {:?}",
            res.outcome()
        );
        assert_eq!(
            fs::read(harness.root.join("media/blob-d.bin")).must_succeed("read"),
            b"replacement-media"
        );

        // The same action replayed against a vanished baseline fails closed.
        let vanished = harness.stage_artifact("blob-e.bin", b"media");
        let gone = RelativeWorkspacePath::parse("media/blob-e.bin").must_succeed("path");
        let stale = PlatformAction::artifact_write(
            dummy_action_id(2),
            harness.capability.clone(),
            vanished,
            gone,
            ExpectedFingerprint::matching(baseline),
        );
        let res = harness.executor.execute_action(&stale);
        let ActionOutcome::Failed(err) = res.outcome() else {
            panic!(
                "expected conflict on vanished baseline, got {:?}",
                res.outcome()
            );
        };
        assert_eq!(err.category(), ErrorCategory::Conflict);
    }

    #[test]
    fn artifact_write_fails_closed_before_publish_when_the_source_disagrees() {
        let harness = TestHarness::new();
        let path = RelativeWorkspacePath::parse("media/blob-f.bin").must_succeed("path");

        // Missing source file.
        let missing = StagedArtifactSource::new(
            harness
                .stage_dir
                .join("absent.bin")
                .to_str()
                .unwrap_or_else(|| panic!("utf8")),
            128,
            sha256_hex(b"declared"),
        )
        .must_succeed("source");
        let action = PlatformAction::artifact_write(
            dummy_action_id(1),
            harness.capability.clone(),
            missing,
            path.clone(),
            ExpectedFingerprint::absent(),
        );
        let res = harness.executor.execute_action(&action);
        let ActionOutcome::Failed(err) = res.outcome() else {
            panic!("expected missing-source failure, got {:?}", res.outcome());
        };
        assert_eq!(err.code(), "artifact_source_missing");
        assert!(!harness.root.join("media/blob-f.bin").exists());

        // Declared digest disagrees with the retained bytes.
        let retained = harness.stage_artifact("corrupt.bin", b"actual-bytes");
        let corrupt = StagedArtifactSource::new(
            retained.path(),
            retained.length(),
            sha256_hex(b"other-bytes"),
        )
        .must_succeed("corrupt source");
        let action = PlatformAction::artifact_write(
            dummy_action_id(2),
            harness.capability.clone(),
            corrupt,
            path.clone(),
            ExpectedFingerprint::absent(),
        );
        let res = harness.executor.execute_action(&action);
        let ActionOutcome::Failed(err) = res.outcome() else {
            panic!("expected digest-mismatch failure, got {:?}", res.outcome());
        };
        assert_eq!(err.code(), "artifact_source_mismatch");
        assert!(!harness.root.join("media/blob-f.bin").exists());

        // Declared length disagrees with the retained bytes.
        let staged = harness.stage_artifact("short.bin", b"actual-bytes");
        let wrong_length = StagedArtifactSource::new(
            staged.path(),
            staged.length() + 1,
            sha256_hex(b"actual-bytes"),
        )
        .must_succeed("length-mismatched source");
        let action = PlatformAction::artifact_write(
            dummy_action_id(3),
            harness.capability.clone(),
            wrong_length,
            path,
            ExpectedFingerprint::absent(),
        );
        let res = harness.executor.execute_action(&action);
        let ActionOutcome::Failed(err) = res.outcome() else {
            panic!("expected length-mismatch failure, got {:?}", res.outcome());
        };
        assert_eq!(err.code(), "artifact_source_mismatch");
    }

    #[test]
    fn artifact_write_retries_past_an_incomplete_temp_copy() {
        let harness = TestHarness::new();
        fs::create_dir_all(harness.root.join("media")).must_succeed("media dir");
        // A prior attempt crashed mid-copy: an orphaned partial temp remains beside the target.
        fs::write(
            harness.root.join("media/.tmp.blob-g.bin.deadbeef"),
            b"partial-",
        )
        .must_succeed("stale temp");
        let bytes = b"complete-media-payload";
        let source = harness.stage_artifact("blob-g.bin", bytes);
        let path = RelativeWorkspacePath::parse("media/blob-g.bin").must_succeed("path");

        let action = PlatformAction::artifact_write(
            dummy_action_id(1),
            harness.capability.clone(),
            source,
            path,
            ExpectedFingerprint::absent(),
        );
        let res = harness.executor.execute_action(&action);
        assert!(
            matches!(
                res.outcome(),
                ActionOutcome::Applied(PlatformActionOutput::WriteComplete { .. })
            ),
            "an incomplete temp copy must not block the retry: {:?}",
            res.outcome()
        );
        assert_eq!(
            fs::read(harness.root.join("media/blob-g.bin")).must_succeed("read"),
            bytes
        );
        let names: Vec<String> = fs::read_dir(harness.root.join("media"))
            .must_succeed("read media dir")
            .map(|entry| {
                entry
                    .must_succeed("entry")
                    .file_name()
                    .to_string_lossy()
                    .into_owned()
            })
            .collect();
        assert_eq!(
            names,
            vec!["blob-g.bin"],
            "the retried write reclaims its incomplete temp copy"
        );
    }

    #[test]
    fn artifact_write_replays_its_witness_and_validates_in_a_batch() {
        let harness = TestHarness::new();
        let bytes = b"witnessed-artifact";
        let source = harness.stage_artifact("blob-h.bin", bytes);
        let path = RelativeWorkspacePath::parse("media/blob-h.bin").must_succeed("path");
        let action = PlatformAction::artifact_write(
            dummy_action_id(1),
            harness.capability.clone(),
            source,
            path,
            ExpectedFingerprint::absent(),
        );

        let batch = PlatformActionBatch::new(
            dummy_job_id(),
            dummy_batch_id(),
            1,
            u64::MAX,
            vec![action.clone()],
        )
        .must_succeed("batch");
        let first = harness.executor.execute(&batch).must_succeed("execute");
        assert_eq!(first.validate_against(&batch).must_succeed("witness"), 1);

        let replay = harness.executor.execute_action(&action);
        assert!(
            matches!(
                replay.outcome(),
                ActionOutcome::AlreadySatisfied(PlatformActionOutput::WriteComplete { .. })
            ),
            "the recorded witness replays satisfaction: {:?}",
            replay.outcome()
        );
    }

    #[test]
    fn artifact_write_streams_large_media() {
        let harness = TestHarness::new();
        let media_len = 50 * 1024 * 1024usize;
        let mut bytes = Vec::with_capacity(media_len);
        bytes.extend_from_slice(b"large-media-magic");
        bytes.resize(media_len, 0xCD);
        let digest = sha256_hex(&bytes);
        let source = harness.stage_artifact("blob-large.bin", &bytes);
        drop(bytes);
        let source_path = source.path().to_owned();
        let path = RelativeWorkspacePath::parse("media/blob-large.bin").must_succeed("path");

        let action = PlatformAction::artifact_write(
            dummy_action_id(1),
            harness.capability.clone(),
            source,
            path,
            ExpectedFingerprint::absent(),
        );
        let res = harness.executor.execute_action(&action);
        let ActionOutcome::Applied(PlatformActionOutput::WriteComplete { metadata }) =
            res.outcome()
        else {
            panic!("expected applied write complete, got {:?}", res.outcome());
        };
        assert_eq!(metadata.evidence().length(), media_len as u64);
        assert_eq!(metadata.evidence().verified_digest(), Some(&digest));

        // A crash-style retry against the published target is satisfied, not a conflict.
        let retained = StagedArtifactSource::new(&source_path, media_len as u64, digest)
            .must_succeed("retained source");
        let replay = PlatformAction::artifact_write(
            dummy_action_id(2),
            harness.capability.clone(),
            retained,
            RelativeWorkspacePath::parse("media/blob-large.bin").must_succeed("path"),
            ExpectedFingerprint::absent(),
        );
        let res = harness.executor.execute_action(&replay);
        assert!(
            matches!(
                res.outcome(),
                ActionOutcome::AlreadySatisfied(PlatformActionOutput::WriteComplete { .. })
            ),
            "a retried large write replays as satisfied: {:?}",
            res.outcome()
        );
    }
}
