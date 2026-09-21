//! Behavior Contract
//!
//! Capability: authorize physical writes using exact content and replay only the same action.
//! Scenarios:
//! - Given unchanged bytes with a changed mtime, replacement still accepts the original baseline.
//! - Given a reused action ID with different write parameters, the second action writes its bytes.
//! - Given a damaged exchange artifact, repeating a read restores verified source bytes.
//! - Given Unicode filenames, opaque pagination resumes without loss and rejects a changed list.
//! - Given inconsistent path and locator evidence, reading refuses to stage the wrong document.
//! - Given a bound capability, rebinding it cannot redirect pending actions to another root.
//! - Given a nonempty directory, a single delete action preserves unenumerated child documents.
//! - Given an existing move destination, replacement requires and honors its exact byte baseline.
//! - Given a named pipe among workspace entries, enumeration rejects it without blocking for a writer.
//!
//! Observable outcomes: file bytes, stable SHA fingerprints, verified action outputs.
//! TDD proof: RED shows mtime-dependent baselines and action-ID-only replay returning stale bytes.
//! Excludes: application identity journals and non-cooperative check/rename race elimination.

#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    // Given a literal .tmp.user.md, enumeration must retain it. RED observed the old prefix
    // filter returning an empty page; platform I/O cannot reserve business filenames implicitly.
    use std::{
        fs,
        time::{Duration, UNIX_EPOCH},
    };

    use super::support::ResultTestExt;
    use lomo_core::{
        ActionId, ActionOutcome, CapabilityToken, DocumentLocator, DocumentMetadata, ExchangeToken,
        ExpectedFingerprint, MetadataPage, PageSize, PlatformAction, PlatformActionOutput,
        RelativeWorkspacePath, Sha256Digest, WriteMode,
    };
    use lomo_platform_fs::{ExchangeDirectory, PosixPlatformActionExecutor};
    use tempfile::TempDir;

    fn list(executor: &PosixPlatformActionExecutor, cursor: Option<String>) -> ActionOutcome {
        executor
            .execute_action(&PlatformAction::list_root(
                action_id(),
                capability(),
                cursor,
                PageSize::new(1).must_succeed("page size"),
            ))
            .outcome()
            .clone()
    }

    fn page(outcome: ActionOutcome) -> MetadataPage {
        match outcome {
            ActionOutcome::Applied(PlatformActionOutput::Listed { page }) => page,
            other @ (ActionOutcome::Applied(_)
            | ActionOutcome::AlreadySatisfied(_)
            | ActionOutcome::Failed(_)) => panic!("expected page: {other:?}"),
        }
    }

    #[test]
    fn unicode_filename_pagination_is_opaque_and_bound_to_the_directory_snapshot() {
        let (temp, executor, _) = setup();
        fs::write(temp.path().join("notes/中文.md"), b"first").must_succeed("first document");
        fs::write(temp.path().join("notes/笔记 空格.md"), b"second")
            .must_succeed("second document");
        let first = page(list(&executor, None));
        let Some(cursor) = first.next_cursor() else {
            panic!("second page must exist");
        };
        let second = page(list(&executor, Some(cursor.as_str().to_owned())));
        assert_eq!(
            second
                .items()
                .first()
                .map(|item| item.document_handle().as_str()),
            Some("笔记 空格.md")
        );
        assert!(second.next_cursor().is_none());
        fs::write(temp.path().join("notes/another.md"), b"external addition")
            .must_succeed("change directory");
        let ActionOutcome::Failed(error) = list(&executor, Some(cursor.as_str().to_owned())) else {
            panic!("cursor from old directory snapshot must fail");
        };
        assert_eq!(error.code(), "stale_directory_cursor");
    }

    #[test]
    fn filename_prefix_does_not_hide_a_real_user_document() {
        let (temp, executor, _) = setup();
        fs::write(temp.path().join("notes/.tmp.user.md"), b"user document")
            .must_succeed("literal user filename");
        let listed = page(list(&executor, None));
        let names: Vec<_> = listed
            .items()
            .iter()
            .map(DocumentMetadata::target)
            .collect();
        assert_eq!(
            names,
            vec![&lomo_core::WorkspaceTarget::Relative(
                RelativeWorkspacePath::parse(".tmp.user.md").must_succeed("user path"),
            )]
        );
    }

    #[test]
    fn read_rejects_conflicting_locator_before_creating_exchange_artifact() {
        let (temp, executor, exchange) = setup();
        fs::write(temp.path().join("notes/memo.md"), b"source").must_succeed("source");
        let action = PlatformAction::ReadToExchange {
            action_id: action_id(),
            capability: capability(),
            path: path(),
            locator: DocumentLocator::Path(
                RelativeWorkspacePath::parse("another.md").must_succeed("other path"),
            ),
            exchange_token: ExchangeToken::parse("read").must_succeed("token"),
            expected_source: ExpectedFingerprint::Absent,
        };
        let result = executor.execute_action(&action);
        let ActionOutcome::Failed(error) = result.outcome() else {
            panic!("contradictory locator must fail");
        };
        assert_eq!(error.code(), "document_locator_mismatch");
        assert!(!exchange.path().join("read").exists());
    }

    fn action_id() -> ActionId {
        ActionId::parse("action-1").must_succeed("action")
    }

    #[test]
    fn named_pipes_are_rejected_without_blocking_the_workspace_scan() {
        let (temp, executor, _) = setup();
        rustix::fs::mkfifoat(
            rustix::fs::CWD,
            temp.path().join("notes/memo.md"),
            rustix::fs::Mode::RUSR | rustix::fs::Mode::WUSR,
        )
        .must_succeed("named pipe");
        let result =
            executor.execute_action(&PlatformAction::stat(action_id(), capability(), path()));
        let ActionOutcome::Failed(error) = result.outcome() else {
            panic!("only regular files are documents");
        };
        assert_eq!(error.code(), "unsupported_document_kind");
    }

    #[test]
    fn move_to_an_existing_destination_requires_both_source_and_target_baselines() {
        let (temp, executor, _) = setup();
        let source_path = temp.path().join("notes/memo.md");
        let destination_path = temp.path().join("notes/destination.md");
        fs::write(&source_path, b"source").must_succeed("source");
        fs::write(&destination_path, b"old destination").must_succeed("destination");
        let source = stat(&executor);
        let destination =
            RelativeWorkspacePath::parse("destination.md").must_succeed("destination path");
        let result = executor.execute_action(&PlatformAction::stat(
            action_id(),
            capability(),
            destination.clone(),
        ));
        let ActionOutcome::Applied(PlatformActionOutput::Stat { metadata }) = result.outcome()
        else {
            panic!("destination metadata");
        };
        let action = PlatformAction::move_path(
            action_id(),
            capability(),
            path(),
            destination,
            ExpectedFingerprint::matching(source.evidence().clone()),
            ExpectedFingerprint::matching(metadata.evidence().clone()),
        );
        fs::write(&destination_path, b"external change").must_succeed("external edit");
        assert!(matches!(
            executor.execute_action(&action).outcome(),
            ActionOutcome::Failed(_)
        ));
        assert_eq!(
            fs::read(&source_path).must_succeed("source preserved"),
            b"source"
        );
        assert_eq!(
            fs::read(&destination_path).must_succeed("external bytes preserved"),
            b"external change"
        );
        fs::write(&destination_path, b"old destination").must_succeed("restore expected baseline");
        let moved = executor.execute_action(&action);
        assert!(
            matches!(
                moved.outcome(),
                ActionOutcome::Applied(PlatformActionOutput::MoveComplete { .. })
            ),
            "{moved:?}"
        );
        assert!(!source_path.exists());
        assert_eq!(
            fs::read(destination_path).must_succeed("moved bytes"),
            b"source"
        );
    }

    #[test]
    fn a_capability_cannot_be_rebound_to_another_physical_root() {
        let (temp, executor, _) = setup();
        fs::write(temp.path().join("notes/memo.md"), b"trusted").must_succeed("source");
        let original = stat(&executor);
        let other = temp.path().join("other");
        fs::create_dir(&other).must_succeed("other root");
        fs::write(other.join("memo.md"), b"unrelated").must_succeed("other source");
        let Err(error) = executor.bind_root(capability(), other) else {
            panic!("capability is immutable");
        };
        assert_eq!(error.code(), "capability_already_bound");
        assert_eq!(stat(&executor), original);
    }

    #[test]
    fn deleting_a_directory_does_not_recursively_remove_unenumerated_user_files() {
        let (temp, executor, _) = setup();
        let nested = temp.path().join("notes/folder");
        fs::create_dir(&nested).must_succeed("folder");
        fs::write(nested.join("memo.md"), b"preserve").must_succeed("memo");
        let folder = RelativeWorkspacePath::parse("folder").must_succeed("path");
        let result = executor.execute_action(&PlatformAction::stat(
            action_id(),
            capability(),
            folder.clone(),
        ));
        let ActionOutcome::Applied(PlatformActionOutput::Stat { metadata }) = result.outcome()
        else {
            panic!("directory metadata");
        };
        let deleted = executor.execute_action(&PlatformAction::delete(
            action_id(),
            capability(),
            folder,
            ExpectedFingerprint::matching(metadata.evidence().clone()),
        ));
        assert!(matches!(deleted.outcome(), ActionOutcome::Failed(_)));
        assert_eq!(
            fs::read(nested.join("memo.md")).must_succeed("preserved child"),
            b"preserve"
        );
    }
    fn capability() -> CapabilityToken {
        CapabilityToken::parse("notes").must_succeed("root")
    }
    fn path() -> RelativeWorkspacePath {
        RelativeWorkspacePath::parse("memo.md").must_succeed("path")
    }

    fn setup() -> (TempDir, PosixPlatformActionExecutor, ExchangeDirectory) {
        let temp = TempDir::new().must_succeed("private fixture");
        let root = temp.path().join("notes");
        fs::create_dir(&root).must_succeed("notes");
        let exchange =
            ExchangeDirectory::new(temp.path().join("exchange")).must_succeed("exchange");
        let executor = PosixPlatformActionExecutor::new(exchange.path()).must_succeed("executor");
        executor.bind_root(capability(), root).must_succeed("bind");
        (temp, executor, exchange)
    }

    fn stat(executor: &PosixPlatformActionExecutor) -> DocumentMetadata {
        let result =
            executor.execute_action(&PlatformAction::stat(action_id(), capability(), path()));
        match result.outcome() {
            ActionOutcome::Applied(PlatformActionOutput::Stat { metadata })
            | ActionOutcome::AlreadySatisfied(PlatformActionOutput::Stat { metadata }) => {
                metadata.clone()
            }
            other @ (ActionOutcome::Applied(_)
            | ActionOutcome::AlreadySatisfied(_)
            | ActionOutcome::Failed(_)) => panic!("stat must succeed: {other:?}"),
        }
    }

    #[test]
    fn mtime_changes_do_not_change_content_fingerprints_or_block_a_valid_write() {
        let (temp, executor, exchange) = setup();
        let target = temp.path().join("notes/memo.md");
        fs::write(&target, b"\xef\xbb\xbfbody\r\n").must_succeed("source");
        let before = stat(&executor);
        fs::File::open(&target)
            .must_succeed("file")
            .set_modified(UNIX_EPOCH + Duration::from_secs(10))
            .must_succeed("touch mtime only");
        let touched = stat(&executor);
        assert_eq!(touched.evidence(), before.evidence());
        assert_eq!(
            Some(before.evidence().fingerprint()),
            before
                .evidence()
                .verified_digest()
                .map(Sha256Digest::as_str),
        );
        let artifact = exchange
            .write_content(
                &ExchangeToken::parse("draft").must_succeed("token"),
                b"updated",
            )
            .must_succeed("draft");
        let result = executor.execute_action(&PlatformAction::write_from_exchange(
            action_id(),
            capability(),
            artifact,
            path(),
            WriteMode::Replace,
            ExpectedFingerprint::matching(before.evidence().clone()),
        ));
        assert!(matches!(
            result.outcome(),
            ActionOutcome::Applied(PlatformActionOutput::WriteComplete { .. })
        ));
        assert_eq!(fs::read(target).must_succeed("committed"), b"updated");
    }

    #[test]
    fn repeated_action_ids_do_not_replay_another_write_payload() {
        let (temp, executor, exchange) = setup();
        let first = exchange
            .write_content(
                &ExchangeToken::parse("first").must_succeed("token"),
                b"first",
            )
            .must_succeed("draft");
        let created = executor.execute_action(&PlatformAction::write_from_exchange(
            action_id(),
            capability(),
            first,
            path(),
            WriteMode::Create,
            ExpectedFingerprint::Absent,
        ));
        let ActionOutcome::Applied(PlatformActionOutput::WriteComplete { metadata }) =
            created.outcome()
        else {
            panic!("create must succeed: {:?}", created.outcome());
        };
        let second = exchange
            .write_content(
                &ExchangeToken::parse("second").must_succeed("token"),
                b"second",
            )
            .must_succeed("draft");
        let updated = executor.execute_action(&PlatformAction::write_from_exchange(
            action_id(),
            capability(),
            second,
            path(),
            WriteMode::Replace,
            ExpectedFingerprint::matching(metadata.evidence().clone()),
        ));
        assert!(matches!(
            updated.outcome(),
            ActionOutcome::Applied(_) | ActionOutcome::AlreadySatisfied(_)
        ));
        assert_eq!(
            fs::read(temp.path().join("notes/memo.md")).must_succeed("committed"),
            b"second"
        );
    }

    #[test]
    fn repeated_reads_never_return_evidence_for_damaged_exchange_bytes() {
        let (temp, executor, exchange) = setup();
        fs::write(temp.path().join("notes/memo.md"), b"original").must_succeed("source");
        let action = PlatformAction::read_to_exchange(
            action_id(),
            capability(),
            path(),
            "read",
            ExpectedFingerprint::Absent,
        )
        .must_succeed("read action");
        assert!(matches!(
            executor.execute_action(&action).outcome(),
            ActionOutcome::Applied(_)
        ));
        fs::write(exchange.path().join("read"), b"damaged")
            .must_succeed("simulate exchange corruption");
        let replay = executor.execute_action(&action);
        assert!(matches!(
            replay.outcome(),
            ActionOutcome::Applied(_) | ActionOutcome::AlreadySatisfied(_)
        ));
        assert_eq!(
            fs::read(exchange.path().join("read")).must_succeed("verified exchange"),
            b"original"
        );
    }
}
