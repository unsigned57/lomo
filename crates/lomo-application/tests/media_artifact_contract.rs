//! Behavior Contract
//! Capability: staged media commits through an artifact-reference transaction — the durable
//! pending plan carries identity, length and a recoverable staged source, never media bytes —
//! while the capability-bound executor streams the retained source into the workspace.
//! Scenarios: a 50 MiB attachment interrupted while durable history is still unwritten keeps a
//! byte-free pending plan and replays to completion; a target already holding the declared
//! digest is replayed as satisfied; a third-party target change fails closed without being
//! overwritten; an artifact still leased by a sibling draft is never consumed by a commit.
//! Observable outcomes: intent-payload volume far below the media volume, byte-exact published
//! media, `idempotent_replay` receipts, stage-file survival under a sibling lease, and the
//! `stale_transaction_baseline` conflict.
//! TDD proof: `PlannedFile::Write` froze whole media bytes into `intent-payloads`; the
//! artifact-reference plan variant and its streaming executor path did not exist.
//! Excludes: SAF transport parity (platform contract suite) and inbox commit routing.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed on missing facts"
)]
mod tests {
    use std::{
        fs,
        path::Path,
        sync::{
            Arc,
            atomic::{AtomicBool, Ordering},
        },
    };

    use lomo_application::{CreateMemoRequest, WorkspaceSession, WorkspaceSessionConfig};
    use lomo_core::{
        CapabilityToken, ErrorCategory, LomoError, OperationId, PlatformAction,
        PlatformActionBatch, PlatformActionExecutor, PlatformBatchResult, RelativeWorkspacePath,
        RetryDisposition,
    };
    use lomo_media::{
        ArtifactId, MediaSource, MediaStaged, PromotePlan, StageLease, StageLedger, StageOwnerKind,
        stage_directory_of, stage_media, suggest_human_relative_path, write_bytes_for_tests,
    };
    use lomo_platform_fs::FsPlatformActionExecutor;
    use lomo_workspace::WorkspaceRootId;
    use tempfile::tempdir;

    const PNG: &[u8] = &[
        0x89, b'P', b'N', b'G', b'\r', b'\n', 0x1a, b'\n', 0x00, 0x00, 0x00, 0x0d, b'I', b'H',
        b'D', b'R', 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00,
        0x90, 0x77, 0x53, 0xde, 0x00, 0x00, 0x00, 0x0c, b'I', b'D', b'A', b'T', 0x08, 0xd7, 0x63,
        0xf8, 0xcf, 0xc0, 0x00, 0x00, 0x03, 0x01, 0x01, 0x00, 0x18, 0xdd, 0x8d, 0xb4, 0x00, 0x00,
        0x00, 0x00, b'I', b'E', b'N', b'D', 0xae, 0x42, 0x60, 0x82,
    ];

    /// Frozen plans hold document bytes, history records and publication payloads — bounded by
    /// the transaction budget and independent of attached media volume. 256 KiB leaves generous
    /// headroom for metadata payloads while staying orders of magnitude below a media file.
    const MAX_FROZEN_PAYLOAD_BYTES: u64 = 256 * 1024;

    /// Fails durable `.lomo/history/` record writes so a commit freezes mid-apply: the Markdown
    /// document, identity map and media target are already published while history is not.
    struct FailOnInternalWrite {
        inner: FsPlatformActionExecutor,
        fail: Arc<AtomicBool>,
    }

    impl PlatformActionExecutor for FailOnInternalWrite {
        fn execute(&self, batch: &PlatformActionBatch) -> Result<PlatformBatchResult, LomoError> {
            if self.fail.load(Ordering::SeqCst)
                && batch.actions().iter().any(|action| {
                    matches!(
                        action,
                        PlatformAction::WriteFromExchange { path, .. }
                            if path.as_str().starts_with(".lomo/history/")
                    )
                })
            {
                return Err(LomoError::from_platform_boundary(
                    ErrorCategory::Storage,
                    "injected_internal_write_failure",
                    RetryDisposition::Transient,
                    None,
                    None,
                    "test-injected durable write failure",
                )
                .unwrap_or_else(|error| error));
            }
            self.inner.execute(batch)
        }
    }

    struct Ctx {
        session: WorkspaceSession,
        workspace_path: std::path::PathBuf,
        state_path: std::path::PathBuf,
        fail: Arc<AtomicBool>,
        _workspace: tempfile::TempDir,
        _state: tempfile::TempDir,
        _cache: tempfile::TempDir,
        _runtime: tempfile::TempDir,
        _exchange: tempfile::TempDir,
    }

    fn open_ctx() -> Ctx {
        let workspace = tempdir().expect("ws");
        let state = tempdir().expect("st");
        let cache = tempdir().expect("ca");
        let runtime = tempdir().expect("rt");
        let exchange = tempdir().expect("ex");
        let fail = Arc::new(AtomicBool::new(false));
        let inner = FsPlatformActionExecutor::new(exchange.path()).expect("exec");
        let capability = CapabilityToken::parse("notes").expect("cap");
        inner
            .bind_root(capability.clone(), workspace.path())
            .expect("bind");
        let executor: Arc<dyn PlatformActionExecutor> = Arc::new(FailOnInternalWrite {
            inner,
            fail: Arc::clone(&fail),
        });
        let session = WorkspaceSession::open(
            WorkspaceSessionConfig {
                capability,
                root_id: WorkspaceRootId::Notes,
                workspace_generation: lomo_workspace::WorkspaceGenerationId::mint()
                    .expect("workspace generation"),
                time_zone: "UTC".to_owned(),
                date_format: lomo_application::calendar::DateFormat::default(),
                state_dir: state.path().to_path_buf(),
                cache_dir: cache.path().to_path_buf(),
                runtime_dir: runtime.path().to_path_buf(),
                exchange_dir: exchange.path().to_path_buf(),
                media_stage_root: workspace.path().to_path_buf(),
            },
            executor,
        )
        .expect("open");
        Ctx {
            session,
            workspace_path: workspace.path().to_path_buf(),
            state_path: state.path().to_path_buf(),
            fail,
            _workspace: workspace,
            _state: state,
            _cache: cache,
            _runtime: runtime,
            _exchange: exchange,
        }
    }

    fn stage_png(ctx: &Ctx, stem: &str, size: usize) -> MediaStaged {
        let src = ctx.workspace_path.join(format!("src-{stem}.png"));
        let mut bytes = Vec::with_capacity(size.max(PNG.len()));
        bytes.extend_from_slice(PNG);
        bytes.resize(size.max(PNG.len()), 0xAB);
        write_bytes_for_tests(&src, &bytes).expect("write source");
        stage_media(
            &ctx.workspace_path,
            MediaSource::DirectPath { path: src },
            &format!("{stem}.png"),
        )
        .expect("stage")
    }

    fn create_request(
        operation_id: &str,
        stem: &str,
        staged: &MediaStaged,
        final_rel: &lomo_media::MediaRelativePath,
    ) -> CreateMemoRequest {
        CreateMemoRequest {
            operation_id: OperationId::parse(operation_id).expect("op"),
            relative_path: Some(RelativeWorkspacePath::parse("2026_09_10.md").expect("doc path")),
            time_token: Some("12:00:00".to_owned()),
            content: format!("see ![[{}]] carried by {stem}", final_rel.as_str()),
            expected_document_fingerprint: None,
            pinned: false,
            pending_promotes: vec![PromotePlan {
                operation_id: operation_id.to_owned(),
                staged: staged.clone(),
                final_relative_path: final_rel.clone(),
            }],
            chronology_epoch_ms: None,
        }
    }

    fn directory_bytes(directory: &Path) -> u64 {
        let Ok(entries) = fs::read_dir(directory) else {
            return 0;
        };
        entries
            .map(|entry| entry.expect("dir entry"))
            .map(|entry| entry.metadata().map_or(0, |meta| meta.len()))
            .sum()
    }

    /// Given a staged attachment and a commit that fails while durable history is unwritten,
    /// when the frozen plan is inspected, then it retains identity plus a recoverable source —
    /// never the media bytes — and the pending record stays retryable.
    #[test]
    fn pending_media_plan_stays_byte_free_while_media_volume_grows() {
        for media_len in [PNG.len(), 50 * 1024 * 1024] {
            let ctx = open_ctx();
            ctx.fail.store(true, Ordering::SeqCst);
            let staged = stage_png(&ctx, "big", media_len);
            let final_rel = suggest_human_relative_path("big", staged.mime).expect("path");
            let operation = format!("media-op-{media_len}");
            let error = ctx
                .session
                .create_memo(create_request(&operation, "big", &staged, &final_rel))
                .expect_err("injected durable failure");
            assert_eq!(error.code(), "injected_internal_write_failure");

            assert!(
                ctx.state_path
                    .join("intents/pending")
                    .join(format!("{operation}.rec"))
                    .is_file(),
                "the failed operation must retain a durable pending witness"
            );
            let retained = directory_bytes(&ctx.state_path.join("intent-payloads"));
            assert!(
                retained <= MAX_FROZEN_PAYLOAD_BYTES,
                "frozen plan retained {retained} payload bytes for a {media_len}-byte artifact"
            );
        }
    }

    /// Given an interrupted 50 MiB commit, when the failure clears and a later command runs,
    /// then recovery replays the retained staged source: the media target lands byte-exact,
    /// the satisfied document and media files are not rewritten, and the operation receipt is
    /// durable enough for an identical retry to answer `idempotent_replay`.
    #[test]
    fn interrupted_media_commit_recovers_from_the_retained_stage() {
        let ctx = open_ctx();
        ctx.fail.store(true, Ordering::SeqCst);
        let media_len = 50 * 1024 * 1024;
        let staged = stage_png(&ctx, "retry", media_len);
        let final_rel = suggest_human_relative_path("retry", staged.mime).expect("path");
        let staged_digest = staged.digest.clone();
        let staging_path = staged.staging_path.clone();
        ctx.session
            .create_memo(create_request("media-retry", "retry", &staged, &final_rel))
            .expect_err("injected durable failure");
        assert!(
            staging_path.is_file(),
            "a pending operation keeps its staged source"
        );

        ctx.fail.store(false, Ordering::SeqCst);
        ctx.session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("follow-up").expect("op"),
                relative_path: Some(
                    RelativeWorkspacePath::parse("2026_09_11.md").expect("doc path"),
                ),
                time_token: Some("09:00:00".to_owned()),
                content: "unrelated follow-up".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("recovery command");

        let dest = ctx.workspace_path.join(final_rel.as_str());
        let (dest_digest, dest_len) =
            lomo_media::ContentDigest::stream_from_path(&dest).expect("digest destination");
        assert_eq!(dest_digest.as_str(), staged_digest.as_str());
        assert_eq!(dest_len, media_len as u64);
        assert!(
            !staging_path.exists(),
            "a committed operation releases its staged source"
        );

        let replayed = ctx
            .session
            .create_memo(create_request("media-retry", "retry", &staged, &final_rel))
            .expect("identical retry");
        assert!(
            replayed.commit_result.idempotent_replay,
            "a committed operation answers an identical retry from its durable receipt"
        );
    }

    /// Given a pending commit whose media target was published before the interruption, when a
    /// third party rewrites that target, then recovery fails closed instead of overwriting —
    /// and once the workspace again holds the declared digest the same plan is satisfied, not
    /// rewritten.
    #[test]
    fn recovery_fails_closed_when_a_third_party_rewrites_the_target() {
        let ctx = open_ctx();
        ctx.fail.store(true, Ordering::SeqCst);
        let staged = stage_png(&ctx, "conflict", 4096);
        let final_rel = suggest_human_relative_path("conflict", staged.mime).expect("path");
        let source_bytes = fs::read(&staged.staging_path).expect("staged bytes");
        ctx.session
            .create_memo(create_request(
                "media-conflict",
                "conflict",
                &staged,
                &final_rel,
            ))
            .expect_err("injected durable failure");
        let dest = ctx.workspace_path.join(final_rel.as_str());
        assert!(dest.is_file(), "the media target is already published");

        let foreign = b"third-party rewrite of the media target";
        fs::write(&dest, foreign).expect("foreign write");

        ctx.fail.store(false, Ordering::SeqCst);
        let error = ctx
            .session
            .rebuild_projection()
            .expect_err("third-party target must fail closed");
        assert_eq!(error.code(), "stale_transaction_baseline");
        assert_eq!(
            fs::read(&dest).expect("read target"),
            foreign,
            "a conflicting target is never overwritten by recovery"
        );

        fs::write(&dest, &source_bytes).expect("restore declared bytes");
        ctx.session.rebuild_projection().expect("satisfied replay");
        assert!(ctx.workspace_path.join("2026_09_10.md").is_file());
    }

    /// Given one staged artifact leased by a second draft, when the first draft's operation
    /// commits, then the staged bytes survive for the sibling; when the sibling commits to a
    /// different destination and releases its lease, the source is finally reclaimed.
    #[test]
    fn staged_artifact_shared_by_two_drafts_survives_the_first_commit() {
        let ctx = open_ctx();
        let staged = stage_png(&ctx, "shared", 8192);
        let stage_dir = stage_directory_of(&staged.staging_path).expect("stage dir");
        let sibling_lease = StageLease::new(
            ArtifactId::of_digest(&staged.digest),
            StageOwnerKind::Draft,
            "draft-b",
        )
        .expect("lease");
        StageLedger::load(&stage_dir)
            .and_then(|mut ledger| {
                ledger.record(
                    Some(ctx.workspace_path.as_path()),
                    &staged,
                    sibling_lease.clone(),
                )
            })
            .expect("record sibling lease");

        let first_rel = suggest_human_relative_path("shared-a", staged.mime).expect("path");
        ctx.session
            .create_memo(create_request("media-a", "shared", &staged, &first_rel))
            .expect("first commit");
        assert!(
            staged.staging_path.is_file(),
            "a sibling lease must keep the staged source alive"
        );
        assert_eq!(
            fs::read(ctx.workspace_path.join(first_rel.as_str())).expect("first bytes"),
            fs::read(&staged.staging_path).expect("staged bytes"),
        );

        let second_rel = suggest_human_relative_path("shared-b", staged.mime).expect("path");
        ctx.session
            .create_memo(create_request("media-b", "shared", &staged, &second_rel))
            .expect("second commit from the retained source");
        assert_eq!(
            fs::read(ctx.workspace_path.join(second_rel.as_str())).expect("second bytes"),
            fs::read(&staged.staging_path).expect("staged bytes"),
        );
        assert!(
            staged.staging_path.is_file(),
            "the sibling draft still leases the staged source"
        );

        let mut ledger = StageLedger::load(&stage_dir).expect("reload ledger");
        let release = ledger
            .release(&stage_dir, &sibling_lease)
            .expect("release sibling lease");
        assert!(release.bytes_deleted, "the last lease releases the bytes");
        assert!(!staged.staging_path.exists());
    }
}
