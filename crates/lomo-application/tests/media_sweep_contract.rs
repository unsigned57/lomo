//! Behavior Contract
//! Capability: the session-owned two-phase media orphan sweep reclaims only bytes no protection
//! source still names — live and trashed bodies, in-window history, conflict drafts, frozen
//! transactions, and stage-ledger leases — while expired trash leaves through a durable delete
//! intent.
//! Scenarios: an unreferenced committed object moves into `.lomo-media-trash` under its durable
//! name while a referenced sibling stays; audio references protect like images; two files sharing
//! a basename in different directories are independent candidates; trash/history/draft/pending/
//! stage-lease protections each keep their bytes; an expired trash entry is journaled then
//! deleted and a fresh one survives; an empty workspace sweeps clean. Conflict drafts are
//! consumed by file token: an unchanged token reuses the retained projection without decoding,
//! a changed token falls back to the full fail-closed decode. The read-side index itself is
//! memoized on one unified stamp — committed revision plus pending-record, draft-evidence, and
//! stage-ledger file tokens — so a second call at unchanged state pays zero rebuild and any
//! deviation rebuilds fail-closed; the sweep's under-lock recheck always collects fresh.
//! Test Change Justification:
//! - Reason category: `attachment_index` is now stamp-memoized (P-39); the always-fresh
//!   collector moved behind `attachment_index_guarding`, which the sweep already used.
//! - Old behavior/assertion being replaced: draft-token reuse asserted through `attachment_index`.
//! - Why old assertion is no longer correct: a second cached call returns without touching the
//!   draft layer at all, so it cannot observe token hits.
//! - Coverage preserved by: the same assertions run through `attachment_index_guarding(&[])`
//!   (identical collect path, uncached), and `attachment_index_memoizes_on_its_unified_stamp`
//!   proves the zero-rebuild hit contract plus every invalidation leg.
//!
//! Observable outcomes: sweep report protections/moves/purges/failures, durable trash and
//! delete-intent files, surviving candidate bytes.
//! TDD proof: the Kotlin collector saw only `imageUrls`, keyed digests by basename, and ran
//! outside the write lease, so audio/history/draft/pending/stage references could not protect
//! and same-name files collided; the session sweep did not exist.
//! Excludes: SAF provider evidence quirks (capability surface is identical), mid-sweep
//! concurrent edits (single-writer isolation is the `TransactionLock` itself).

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed on missing facts"
)]
mod tests {
    use std::{
        fs,
        path::PathBuf,
        sync::{
            Arc,
            atomic::{AtomicBool, Ordering},
        },
        time::{Duration, SystemTime},
    };

    use lomo_application::{
        CreateMemoRequest, DeleteMemoRequest, UpdateMemoRequest, WorkspaceSession,
        WorkspaceSessionConfig,
    };
    use lomo_core::{
        CapabilityToken, ErrorCategory, LomoError, OperationId, PlatformAction,
        PlatformActionBatch, PlatformActionExecutor, PlatformBatchResult, RelativeWorkspacePath,
        RetryDisposition,
    };
    use lomo_media::{
        ArtifactId, MediaSource, ReferenceSource, StageLease, StageLedger, StageOwnerKind,
        stage_directory, stage_media, trash_entry_name, write_bytes_for_tests,
    };
    use lomo_platform_fs::FsPlatformActionExecutor;
    use lomo_workspace::{MemoId, WorkspaceRootId};
    use tempfile::tempdir;

    const PNG: &[u8] = &[
        0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, b'I', b'H', b'D',
        b'R', 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00, 0x90,
        0x77, 0x53, 0xde, 0x00, 0x00, 0x00, 0x0c, b'I', b'D', b'A', b'T', 0x08, 0xd7, 0x63, 0xf8,
        0xcf, 0xc0, 0x00, 0x00, 0x03, 0x01, 0x01, 0x00, 0x18, 0xdd, 0x8d, 0xb4, 0x00, 0x00, 0x00,
        0x00, b'I', b'E', b'N', b'D', 0xae, 0x42, 0x60, 0x82,
    ];

    const NOW_MS: u64 = 1_757_500_000_000;
    const WINDOW_MS: u64 = 30 * 24 * 60 * 60 * 1000;

    struct Ctx {
        session: WorkspaceSession,
        workspace_path: PathBuf,
        state_path: PathBuf,
        stage_root: PathBuf,
        _workspace: tempfile::TempDir,
        _state: tempfile::TempDir,
        _cache: tempfile::TempDir,
        _runtime: tempfile::TempDir,
        _exchange: tempfile::TempDir,
        _stage: tempfile::TempDir,
    }

    fn open_session() -> Ctx {
        open_session_with_executor(None)
    }

    type ExecutorWrap =
        Box<dyn FnOnce(Arc<FsPlatformActionExecutor>) -> Arc<dyn PlatformActionExecutor>>;

    fn open_session_with_executor(wrap: Option<ExecutorWrap>) -> Ctx {
        let workspace = tempdir().expect("ws");
        let state = tempdir().expect("st");
        let cache = tempdir().expect("ca");
        let runtime = tempdir().expect("rt");
        let exchange = tempdir().expect("ex");
        let media_stage = tempdir().expect("stage");
        let real = Arc::new(FsPlatformActionExecutor::new(exchange.path()).expect("exec"));
        let capability = CapabilityToken::parse("notes").expect("cap");
        real.bind_root(capability.clone(), workspace.path())
            .expect("bind");
        let executor: Arc<dyn PlatformActionExecutor> = match wrap {
            Some(wrap) => wrap(real),
            None => real,
        };
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
                media_stage_root: media_stage.path().to_path_buf(),
            },
            executor,
        )
        .expect("open");
        Ctx {
            workspace_path: workspace.path().to_path_buf(),
            state_path: state.path().to_path_buf(),
            stage_root: media_stage.path().to_path_buf(),
            session,
            _workspace: workspace,
            _state: state,
            _cache: cache,
            _runtime: runtime,
            _exchange: exchange,
            _stage: media_stage,
        }
    }

    /// Writes a committed `media/` object directly into the workspace root.
    fn seed_media(ctx: &Ctx, relative: &str, bytes: &[u8]) -> PathBuf {
        let path = ctx.workspace_path.join(relative);
        fs::create_dir_all(path.parent().expect("media parent")).expect("media dir");
        write_bytes_for_tests(&path, bytes).expect("seed media");
        path
    }

    fn create_memo(ctx: &Ctx, operation: &str, relative_path: &str, content: &str) -> MemoId {
        ctx.session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse(operation).expect("op"),
                relative_path: Some(RelativeWorkspacePath::parse(relative_path).expect("path")),
                time_token: Some("12:00:00".to_owned()),
                content: content.to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create")
            .memo_id
    }

    fn protection_source(
        report: &lomo_application::media_sweep::MediaSweepReport,
        path: &str,
    ) -> Option<ReferenceSource> {
        report
            .protections
            .iter()
            .find(|item| item.relative_path == path)
            .map(|item| item.source)
    }

    #[test]
    fn unreferenced_media_moves_to_trash_and_referenced_stays() {
        let ctx = open_session();
        seed_media(&ctx, "media/img/keep.png", PNG);
        let orphan = seed_media(&ctx, "media/img/gone.png", b"orphan bytes");
        create_memo(
            &ctx,
            "memo-keep",
            "2026_09_10.md",
            "see ![[media/img/keep.png]]",
        );

        let report = ctx
            .session
            .media_orphan_sweep(NOW_MS, WINDOW_MS)
            .expect("sweep");

        assert_eq!(report.candidates, 2);
        assert_eq!(
            protection_source(&report, "media/img/keep.png"),
            Some(ReferenceSource::CurrentMemo),
            "referenced file must be reported protected"
        );
        assert!(ctx.workspace_path.join("media/img/keep.png").is_file());
        assert_eq!(report.moved_to_trash.len(), 1);
        let moved = report.moved_to_trash.first().expect("moved entry");
        assert!(
            ctx.workspace_path.join(&moved.trash_path).is_file(),
            "durable trash entry must exist at {:?}",
            moved.trash_path
        );
        assert!(!orphan.exists(), "collected file left its committed path");
        assert!(
            report.failures.is_empty(),
            "unexpected failures: {:?}",
            report.failures
        );
    }

    #[test]
    fn audio_attachment_is_protected_like_an_image() {
        let ctx = open_session();
        seed_media(&ctx, "media/voice/n.m4a", b"voice bytes");
        create_memo(
            &ctx,
            "memo-audio",
            "2026_09_10.md",
            "hear ![[media/voice/n.m4a]]",
        );

        let report = ctx
            .session
            .media_orphan_sweep(NOW_MS, WINDOW_MS)
            .expect("sweep");

        assert_eq!(
            protection_source(&report, "media/voice/n.m4a"),
            Some(ReferenceSource::CurrentMemo),
            "audio reference must protect; image-only collectors drop it"
        );
        assert!(ctx.workspace_path.join("media/voice/n.m4a").is_file());
        assert!(report.moved_to_trash.is_empty());
    }

    #[test]
    fn same_basename_in_different_directories_is_independent() {
        let ctx = open_session();
        seed_media(&ctx, "media/a/dup.png", PNG);
        seed_media(&ctx, "media/b/dup.png", b"different bytes, same name");
        create_memo(
            &ctx,
            "memo-dup",
            "2026_09_10.md",
            "see ![[media/a/dup.png]]",
        );

        let report = ctx
            .session
            .media_orphan_sweep(NOW_MS, WINDOW_MS)
            .expect("sweep");

        assert_eq!(
            protection_source(&report, "media/a/dup.png"),
            Some(ReferenceSource::CurrentMemo)
        );
        assert!(ctx.workspace_path.join("media/a/dup.png").is_file());
        assert_eq!(report.moved_to_trash.len(), 1);
        assert!(
            !ctx.workspace_path.join("media/b/dup.png").exists(),
            "basename collision must not protect the unreferenced twin"
        );
    }

    #[test]
    fn trashed_memo_still_protects_its_attachments() {
        let ctx = open_session();
        seed_media(&ctx, "media/img/t.png", PNG);
        let memo_id = create_memo(
            &ctx,
            "memo-trash",
            "2026_09_10.md",
            "see ![[media/img/t.png]]",
        );
        let snapshot = ctx.session.get_memo(&memo_id).expect("get").expect("memo");
        ctx.session
            .delete_memo(DeleteMemoRequest {
                operation_id: OperationId::parse("del-trash").expect("op"),
                memo_id,
                expected_document_fingerprint: snapshot.file_fingerprint,
                trashed_at_ms: Some(1_757_400_000_000),
            })
            .expect("delete");

        let report = ctx
            .session
            .media_orphan_sweep(NOW_MS, WINDOW_MS)
            .expect("sweep");

        assert_eq!(
            protection_source(&report, "media/img/t.png"),
            Some(ReferenceSource::TrashMemo),
            "a trashed memo's last projection must still protect"
        );
        assert!(ctx.workspace_path.join("media/img/t.png").is_file());
    }

    #[test]
    fn in_window_history_revision_still_protects() {
        let ctx = open_session();
        seed_media(&ctx, "media/img/h.png", PNG);
        let memo_id = create_memo(
            &ctx,
            "memo-hist",
            "2026_09_10.md",
            "see ![[media/img/h.png]]",
        );
        let snapshot = ctx.session.get_memo(&memo_id).expect("get").expect("memo");
        ctx.session
            .update_memo(UpdateMemoRequest {
                operation_id: OperationId::parse("upd-hist").expect("op"),
                memo_id,
                content: "attachment removed".to_owned(),
                expected_document_fingerprint: snapshot.file_fingerprint,
                pending_promotes: Vec::new(),
            })
            .expect("update");

        let report = ctx
            .session
            .media_orphan_sweep(NOW_MS, WINDOW_MS)
            .expect("sweep");

        assert_eq!(
            protection_source(&report, "media/img/h.png"),
            Some(ReferenceSource::HistoryVersion),
            "the retained create revision must protect after the body unlinked it"
        );
        assert!(ctx.workspace_path.join("media/img/h.png").is_file());
    }

    #[test]
    fn conflict_draft_evidence_protects_its_attachments() {
        let ctx = open_session();
        seed_media(&ctx, "media/img/d.png", PNG);
        let memo_id = create_memo(&ctx, "memo-draft", "2026_09_10.md", "original");
        // External edit races the editor's stale baseline; the losing body becomes draft evidence.
        fs::write(ctx.workspace_path.join("2026_09_10.md"), "external rewrite").expect("external");
        let stale = ctx
            .session
            .get_memo(&memo_id)
            .expect("get")
            .expect("memo")
            .file_fingerprint;
        let conflict = ctx
            .session
            .update_memo(UpdateMemoRequest {
                operation_id: OperationId::parse("upd-draft").expect("op"),
                memo_id,
                content: "editor keeps ![[media/img/d.png]]".to_owned(),
                expected_document_fingerprint: stale,
                pending_promotes: Vec::new(),
            })
            .expect_err("stale baseline must conflict");
        assert_eq!(conflict.category(), ErrorCategory::Conflict);
        assert!(
            ctx.state_path.join("drafts/upd-draft.json").is_file(),
            "conflict draft evidence must be durable"
        );

        let report = ctx
            .session
            .media_orphan_sweep(NOW_MS, WINDOW_MS)
            .expect("sweep");

        assert_eq!(
            protection_source(&report, "media/img/d.png"),
            Some(ReferenceSource::Draft),
            "the retained draft body must protect its attachment"
        );
        assert!(ctx.workspace_path.join("media/img/d.png").is_file());
    }

    /// Collects a fresh attachment index through the always-uncached sweep path.
    fn fresh_index(ctx: &Ctx) -> lomo_application::media_index::AttachmentIndex {
        ctx.session.attachment_index_guarding(&[]).expect("index")
    }

    /// Draft evidence is consumed by file token: a second protection pass reuses the
    /// retained projection without reading or decoding bytes, only a file whose size or
    /// modification time moved is decoded again, and corrupt evidence still fails closed
    /// on that decode path.
    #[test]
    fn draft_evidence_tokens_redecode_only_changed_files() {
        let ctx = open_session();
        let drafts = ctx.state_path.join("drafts");
        fs::create_dir_all(&drafts).expect("drafts dir");
        // Controlled ASCII bodies need no escaping inside the JSON literal.
        let write_draft = |name: &str, operation: &str, body: &str| {
            fs::write(
                drafts.join(name),
                format!(r#"{{"operation_id":"{operation}","draft_content":"{body}"}}"#),
            )
            .expect("seed draft evidence");
        };
        // Force one file's modification token forward so even a coarse mtime
        // clock must observe the rewrite.
        let bump_token = |name: &str, ahead: Duration| {
            fs::File::options()
                .write(true)
                .open(drafts.join(name))
                .expect("open draft")
                .set_modified(SystemTime::now() + ahead)
                .expect("bump token");
        };
        write_draft("op-a.json", "op-a", "keep ![[media/a.png]]");
        write_draft("op-b.json", "op-b", "keep ![[media/b.png]]");

        let index = fresh_index(&ctx);
        assert!(index.protects_path("media/a.png"));
        assert!(index.protects_path("media/b.png"));
        let stats = ctx
            .session
            .draft_evidence_stats()
            .expect("draft evidence stats");
        assert_eq!(
            (stats.decoded, stats.token_hits),
            (2, 0),
            "first pass decodes every unseen draft token once"
        );

        // Unchanged file tokens prove the retained projections are still valid: the
        // second pass decodes zero bytes.
        let index = fresh_index(&ctx);
        assert!(index.protects_path("media/a.png"));
        assert!(index.protects_path("media/b.png"));
        let stats = ctx
            .session
            .draft_evidence_stats()
            .expect("draft evidence stats");
        assert_eq!(
            (stats.decoded, stats.token_hits),
            (2, 2),
            "stable tokens reuse the retained projections; no draft is re-decoded"
        );

        // Rewrite only op-b with a different reference.
        write_draft("op-b.json", "op-b", "keep ![[media/c.png]]");
        bump_token("op-b.json", Duration::from_secs(30));

        let index = fresh_index(&ctx);
        assert!(
            index.protects_path("media/a.png"),
            "unchanged draft stays cached"
        );
        assert!(
            index.protects_path("media/c.png"),
            "rewritten draft re-decodes"
        );
        assert!(
            !index.protects_path("media/b.png"),
            "the stale projection must not survive its invalidated token"
        );
        let stats = ctx
            .session
            .draft_evidence_stats()
            .expect("draft evidence stats");
        assert_eq!(
            (stats.decoded, stats.token_hits),
            (3, 3),
            "only the file whose token changed pays the full decode"
        );

        // Corrupt retained evidence still fails closed on the decode path — the token
        // cache never converts evidence failure into a skipped or empty keep-set.
        fs::write(drafts.join("op-a.json"), b"not json").expect("corrupt draft");
        bump_token("op-a.json", Duration::from_secs(60));
        let error = ctx
            .session
            .attachment_index_guarding(&[])
            .expect_err("corrupt evidence must fail closed");
        assert_eq!(error.code(), "draft_evidence_corrupt");

        // The token path feeds the sweep itself: a rewritten draft still protects its
        // attachment through the two-phase collector. Protection reporting enumerates
        // sweep candidates, so the referenced objects must exist on disk.
        write_draft("op-a.json", "op-a", "keep ![[media/a.png]]");
        bump_token("op-a.json", Duration::from_secs(90));
        seed_media(&ctx, "media/a.png", PNG);
        seed_media(&ctx, "media/c.png", PNG);
        let report = ctx
            .session
            .media_orphan_sweep(NOW_MS, WINDOW_MS)
            .expect("sweep");
        assert_eq!(
            protection_source(&report, "media/c.png"),
            Some(ReferenceSource::Draft),
            "the re-decoded draft protects through the sweep"
        );
        assert_eq!(
            protection_source(&report, "media/a.png"),
            Some(ReferenceSource::Draft),
            "the token-hit draft protects through the sweep"
        );
    }

    /// `attachment_index`/`observe_attachments` are memoized on one unified stamp — committed
    /// revision plus pending-record, draft-evidence, and stage-ledger file tokens — so a second
    /// read at unchanged state is a counted hit with zero rebuild, while a revision move, a new
    /// draft file, or a stage-ledger write each force exactly one rebuild.
    #[test]
    fn attachment_index_memoizes_on_its_unified_stamp() {
        let ctx = open_session();
        seed_media(&ctx, "media/img/k.png", PNG);
        create_memo(
            &ctx,
            "memo-idx",
            "2026_09_10.md",
            "see ![[media/img/k.png]]",
        );

        let first = ctx.session.attachment_index().expect("first index");
        assert!(first.protects_path("media/img/k.png"));
        let stats = ctx.session.attachment_index_stats().expect("stats");
        assert_eq!(
            (stats.builds, stats.hits),
            (1, 0),
            "the first read pays one build"
        );

        // Same stamp → identical observations, zero rebuild — also through the
        // `observe_attachments` view the TUI consumes.
        let second = ctx.session.attachment_index().expect("second index");
        assert_eq!(first.observations(), second.observations());
        let observed = ctx.session.observe_attachments().expect("observed");
        assert_eq!(observed.as_slice(), second.observations());
        let stats = ctx.session.attachment_index_stats().expect("stats");
        assert_eq!(
            (stats.builds, stats.hits),
            (1, 2),
            "an unchanged stamp must serve the memoized index without rebuilding"
        );

        // A committed revision move invalidates.
        create_memo(&ctx, "memo-idx2", "2026_09_11.md", "plain body");
        let third = ctx.session.attachment_index().expect("third index");
        assert!(third.protects_path("media/img/k.png"));
        let stats = ctx.session.attachment_index_stats().expect("stats");
        assert_eq!(
            (stats.builds, stats.hits),
            (2, 2),
            "a committed revision change must rebuild"
        );

        // Draft evidence appearing with no revision move still invalidates: the
        // file token is part of the stamp, so a retained draft protects immediately.
        let drafts = ctx.state_path.join("drafts");
        fs::create_dir_all(&drafts).expect("drafts dir");
        fs::write(
            drafts.join("op-x.json"),
            r#"{"operation_id":"op-x","draft_content":"keep ![[media/img/d.png]]"}"#,
        )
        .expect("draft evidence");
        let fourth = ctx.session.attachment_index().expect("fourth index");
        assert!(
            fourth.protects_path("media/img/d.png"),
            "a draft arriving between reads must be observed"
        );
        assert_eq!(
            ctx.session.attachment_index_stats().expect("stats").builds,
            3,
            "draft evidence is part of the invalidation stamp"
        );

        // A stage-ledger write invalidates the same way: the new lease's digest
        // protection appears without any revision movement.
        let source = ctx.stage_root.join("inbound.png");
        write_bytes_for_tests(&source, PNG).expect("stage source");
        let staged = stage_media(
            &ctx.stage_root,
            MediaSource::DirectPath { path: source },
            "inbound.png",
        )
        .expect("stage");
        let stage_dir = stage_directory(&ctx.stage_root);
        let mut ledger = StageLedger::load(&stage_dir).expect("ledger");
        ledger
            .record(
                Some(&ctx.workspace_path),
                &staged,
                StageLease::new(
                    ArtifactId::of_digest(&staged.digest),
                    StageOwnerKind::IncomingTransfer,
                    "transfer-idx",
                )
                .expect("lease"),
            )
            .expect("record");
        let fifth = ctx.session.attachment_index().expect("fifth index");
        assert!(
            fifth.protects_digest(staged.digest.as_str()),
            "a stage lease appearing between reads must be observed"
        );
        assert_eq!(
            ctx.session.attachment_index_stats().expect("stats").builds,
            4,
            "the stage-ledger token is part of the invalidation stamp"
        );

        // The settled stamp hits again.
        ctx.session.attachment_index().expect("sixth index");
        assert_eq!(
            ctx.session.attachment_index_stats().expect("stats"),
            lomo_application::media_index::AttachmentIndexStats { builds: 4, hits: 3 },
            "once re-settled the index serves hits again"
        );
    }

    /// Executor wrapper that fails one `WriteFromExchange` path once, freezing the operation as a
    /// pending intent-journal record.
    struct FailOnceWrite {
        inner: Arc<dyn PlatformActionExecutor>,
        path_substring: String,
        fired: AtomicBool,
    }

    impl PlatformActionExecutor for FailOnceWrite {
        fn execute(&self, batch: &PlatformActionBatch) -> Result<PlatformBatchResult, LomoError> {
            if self.fired.load(Ordering::SeqCst) {
                return self.inner.execute(batch);
            }
            for action in batch.actions() {
                if let PlatformAction::WriteFromExchange { path, .. } = action
                    && path.as_str().contains(&self.path_substring)
                {
                    self.fired.store(true, Ordering::SeqCst);
                    return Err(LomoError::from_platform_boundary(
                        ErrorCategory::Storage,
                        "injected_executor_failure",
                        RetryDisposition::Never,
                        None,
                        None,
                        "injected document write failure",
                    )
                    .unwrap_or_else(|error| error));
                }
            }
            self.inner.execute(batch)
        }
    }

    #[test]
    fn pending_operation_protects_its_planned_attachment() {
        let ctx = open_session_with_executor(Some(Box::new(|inner| {
            Arc::new(FailOnceWrite {
                inner,
                path_substring: "2026_09_11.md".to_owned(),
                fired: AtomicBool::new(false),
            })
        })));
        seed_media(&ctx, "media/img/p.png", PNG);
        ctx.session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("memo-pending").expect("op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_09_11.md").expect("path")),
                time_token: Some("12:00:00".to_owned()),
                content: "see ![[media/img/p.png]]".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect_err("injected write failure freezes the operation");

        let report = ctx
            .session
            .media_orphan_sweep(NOW_MS, WINDOW_MS)
            .expect("sweep");

        assert_eq!(
            protection_source(&report, "media/img/p.png"),
            Some(ReferenceSource::PendingOperation),
            "a frozen transaction's planned attachment must protect until recovery"
        );
        assert!(ctx.workspace_path.join("media/img/p.png").is_file());
        assert!(report.moved_to_trash.is_empty());
    }

    #[test]
    fn incoming_transfer_stage_lease_protects_by_digest() {
        let ctx = open_session();
        let committed = seed_media(&ctx, "media/img/incoming.png", PNG);
        // An inbound transfer staged the same bytes under a different suggested name.
        let source = ctx.stage_root.join("inbound-source.png");
        write_bytes_for_tests(&source, PNG).expect("stage source");
        let staged = stage_media(
            &ctx.stage_root,
            MediaSource::DirectPath { path: source },
            "inbound.png",
        )
        .expect("stage");
        let stage_dir = stage_directory(&ctx.stage_root);
        let mut ledger = StageLedger::load(&stage_dir).expect("ledger");
        ledger
            .record(
                Some(&ctx.workspace_path),
                &staged,
                StageLease::new(
                    ArtifactId::of_digest(&staged.digest),
                    StageOwnerKind::IncomingTransfer,
                    "transfer-1",
                )
                .expect("lease"),
            )
            .expect("record");
        assert_ne!(
            staged.suggested_final_relative_path, "media/img/incoming.png",
            "scenario requires a different suggested path so only digest protection applies"
        );

        let report = ctx
            .session
            .media_orphan_sweep(NOW_MS, WINDOW_MS)
            .expect("sweep");

        assert_eq!(
            protection_source(&report, "media/img/incoming.png"),
            Some(ReferenceSource::StageLease),
            "a stage-ledger lease must protect identical bytes by digest"
        );
        assert!(committed.is_file());
        assert!(report.moved_to_trash.is_empty());
    }

    #[test]
    fn expired_trash_is_purged_with_durable_intent_and_fresh_trash_stays() {
        let ctx = open_session();
        let trash_dir = ctx.workspace_path.join(".lomo-media-trash");
        fs::create_dir_all(&trash_dir).expect("trash dir");
        let expired_name = trash_entry_name(&format!("{:064x}", 7), 1_000, "old.png");
        let fresh_name = trash_entry_name(&format!("{:064x}", 8), NOW_MS, "new.png");
        write_bytes_for_tests(&trash_dir.join(&expired_name), b"expired").expect("expired");
        write_bytes_for_tests(&trash_dir.join(&fresh_name), b"fresh").expect("fresh");

        let report = ctx
            .session
            .media_orphan_sweep(NOW_MS, WINDOW_MS)
            .expect("sweep");

        assert_eq!(report.permanently_deleted.len(), 1);
        assert!(
            !trash_dir.join(&expired_name).exists(),
            "expired trash entry must be deleted"
        );
        assert!(
            trash_dir.join(&fresh_name).is_file(),
            "in-window trash entry must survive"
        );
        let intents_dir = ctx.workspace_path.join(".lomo-media-delete-intents");
        let intents: Vec<_> = fs::read_dir(&intents_dir)
            .expect("delete-intent journal must exist")
            .collect();
        assert_eq!(intents.len(), 1, "one durable delete intent per purge");
        assert!(
            report.failures.is_empty(),
            "unexpected failures: {:?}",
            report.failures
        );
    }

    /// Executor wrapper that fails the first `Move` touching a path substring, freezing one
    /// candidate mid-sweep while every other verified action commits.
    struct FailOnceMove {
        inner: Arc<dyn PlatformActionExecutor>,
        path_substring: String,
        fired: AtomicBool,
    }

    impl PlatformActionExecutor for FailOnceMove {
        fn execute(&self, batch: &PlatformActionBatch) -> Result<PlatformBatchResult, LomoError> {
            if self.fired.load(Ordering::SeqCst) {
                return self.inner.execute(batch);
            }
            for action in batch.actions() {
                if let PlatformAction::Move { source, .. } = action
                    && source.as_str().contains(&self.path_substring)
                {
                    self.fired.store(true, Ordering::SeqCst);
                    return Err(LomoError::from_platform_boundary(
                        ErrorCategory::Storage,
                        "injected_executor_failure",
                        RetryDisposition::Never,
                        None,
                        None,
                        "injected move failure",
                    )
                    .unwrap_or_else(|error| error));
                }
            }
            self.inner.execute(batch)
        }
    }

    #[test]
    fn interrupted_sweep_leaves_durable_state_and_recovers() {
        let ctx = open_session_with_executor(Some(Box::new(|inner| {
            Arc::new(FailOnceMove {
                inner,
                path_substring: "media/img/b.png".to_owned(),
                fired: AtomicBool::new(false),
            })
        })));
        seed_media(&ctx, "media/img/a.png", PNG);
        seed_media(&ctx, "media/img/b.png", b"second orphan");

        let report = ctx
            .session
            .media_orphan_sweep(NOW_MS, WINDOW_MS)
            .expect("sweep");

        assert_eq!(report.moved_to_trash.len(), 1);
        let moved = report.moved_to_trash.first().expect("moved entry");
        assert!(
            ctx.workspace_path.join(&moved.trash_path).is_file(),
            "the committed move stays durable in trash"
        );
        assert_eq!(report.failures.len(), 1);
        assert_eq!(
            report
                .failures
                .first()
                .map(|item| item.relative_path.as_str()),
            Some("media/img/b.png")
        );
        assert!(
            ctx.workspace_path.join("media/img/b.png").is_file(),
            "an interrupted move leaves the candidate at its committed path"
        );

        // Re-sweep finishes the remaining candidate without redoing the committed move.
        let second = ctx
            .session
            .media_orphan_sweep(NOW_MS, WINDOW_MS)
            .expect("re-sweep");
        assert_eq!(second.moved_to_trash.len(), 1);
        assert!(!ctx.workspace_path.join("media/img/b.png").exists());
        assert!(second.failures.is_empty());
        assert!(
            ctx.workspace_path.join(&moved.trash_path).is_file(),
            "the first trash entry stays durable across re-sweep"
        );
    }

    #[test]
    fn empty_workspace_sweeps_clean() {
        let ctx = open_session();
        let report = ctx
            .session
            .media_orphan_sweep(NOW_MS, WINDOW_MS)
            .expect("sweep");

        assert_eq!(report.candidates, 0);
        assert!(report.protections.is_empty());
        assert!(report.moved_to_trash.is_empty());
        assert!(report.permanently_deleted.is_empty());
        assert!(report.failures.is_empty());
    }
}
