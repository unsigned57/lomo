// adversarial-reaudit2: do the 05-F3 fix invariants survive the machinery that landed
// after them — specifically the scoped/incremental reconcile path?
//!
//! Cross-seam invariants probed, evidence-first:
//!
//! - A04 × reconcile: a consumed operation id must keep classifying `operation_expired`
//!   after the durable identity record it lives in is decoded and re-encoded by
//!   `reconcile_identity`. Every scoped pass mints a fresh `scan-*`/`discover-*`
//!   operation and pushes it onto `MemoIdentityMap.operations`; the consumed list must
//!   ride through the rewrite — a record that loses it would let an evicted retry
//!   re-execute as a brand-new command.
//! - A07/A09 × reconcile: a memo trashed through reconcile (peer-side delete: the doc
//!   block is gone and a durable trash record is observed in the same pass) must keep
//!   its media protected. The incremental trash merge must leave `attachment_ref` rows
//!   the sweep index reads as `TrashMemo` protection — exactly like the commit path —
//!   otherwise a reconcile-fed delete makes the next sweep reclaim restorable media.
//!
//! Tests asserting the CORRECT invariant that FAIL are residual defects and stay RED;
//! each maps into `audit/11-再复审-核心与平台.md`.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "adversarial audit tests fail closed with panics on missing facts"
)]
mod tests {
    use std::{fs, path::PathBuf, sync::Arc};

    use lomo_application::{
        CreateMemoRequest, MemoFilters, MemoQuery, MemoSort, WorkspaceSession,
        WorkspaceSessionConfig,
    };
    use lomo_core::{
        CapabilityToken, OperationId, PageSize, PlatformActionExecutor, RelativeWorkspacePath,
    };
    use lomo_media::write_bytes_for_tests;
    use lomo_platform_fs::FsPlatformActionExecutor;
    use lomo_workspace::{
        MemoId, TrashRecordCreate, TrashRecordV1, trash_record_relative_path,
        write_trash_record_atomic,
    };
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
    const DOC: &str = "2026_09_20.md";

    struct Ctx {
        session: WorkspaceSession,
        workspace: PathBuf,
        _dirs: Vec<tempfile::TempDir>,
    }

    fn open_session() -> Ctx {
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
        let executor: Arc<dyn PlatformActionExecutor> = real;
        let session = WorkspaceSession::open(
            WorkspaceSessionConfig {
                capability,
                root_id: lomo_workspace::WorkspaceRootId::Notes,
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
            session,
            workspace: workspace.path().to_path_buf(),
            _dirs: vec![workspace, state, cache, runtime, exchange, media_stage],
        }
    }

    fn request(operation: &str, time: &str, content: &str) -> CreateMemoRequest {
        CreateMemoRequest {
            operation_id: OperationId::parse(operation).expect("op"),
            relative_path: Some(RelativeWorkspacePath::parse(DOC).expect("doc path")),
            time_token: Some(time.to_owned()),
            content: content.to_owned(),
            expected_document_fingerprint: None,
            pinned: false,
            pending_promotes: Vec::new(),
            chronology_epoch_ms: None,
        }
    }

    fn trash_ids(ctx: &Ctx) -> Vec<String> {
        let query = MemoQuery {
            search_text: None,
            filters: MemoFilters {
                trash_only: true,
                ..MemoFilters::default()
            },
            sort: MemoSort::default(),
        };
        let mut ids = Vec::new();
        let mut cursor = None;
        loop {
            let page = ctx
                .session
                .query_memos_page(
                    &query,
                    None,
                    cursor.as_ref(),
                    PageSize::new(64).expect("ps"),
                )
                .expect("trash page");
            ids.extend(page.items.into_iter().map(|item| item.memo_id));
            match page.next_cursor {
                Some(next) => cursor = Some(next),
                None => return ids,
            }
        }
    }

    /// Writes a durable trash record at its canonical hashed path, exactly like a peer's
    /// soft-delete attestation arriving through sync.
    fn write_trash_record(ctx: &Ctx, record: &TrashRecordV1) {
        let rel = trash_record_relative_path(&record.memo_id).expect("trash path");
        let abs = ctx.workspace.join(rel.as_str());
        fs::create_dir_all(abs.parent().expect("record dir")).expect("record dir");
        write_trash_record_atomic(&abs, record).expect("write record");
    }

    /// A well-formed peer trash record: the claimed fingerprint and declared attachment
    /// list mirror the committed projection — the common "trash an unmodified memo" case.
    fn trash_record_for(memo: &MemoId, snapshot: &lomo_store::MemoSnapshot) -> TrashRecordV1 {
        let attachments = if snapshot.summary.has_attachment {
            vec!["media/keep.png".to_owned()]
        } else {
            Vec::new()
        };
        TrashRecordV1::try_new(TrashRecordCreate {
            memo_id: memo.as_str().to_owned(),
            source_path: DOC.to_owned(),
            time_part: "10:00:00".to_owned(),
            source_fingerprint: snapshot.summary.file_fingerprint.clone(),
            chronology_epoch_ms: snapshot.summary.created_at_ms,
            trashed_at_ms: 1_757_500_000_000,
            body: snapshot.body.clone(),
            tags: Vec::new(),
            attachments,
            reminders: Vec::new(),
            has_todo: false,
            has_url: false,
        })
        .expect("record")
    }

    /// A consumed operation id must stay expired after the identity record is decoded and
    /// re-encoded by a scoped reconcile — the map's `operations` list is the terminal
    /// witness `document_plan` consults once the journal receipt is evicted.
    #[test]
    fn a_consumed_operation_stays_expired_after_reconcile_rewrites_its_identity_record() {
        let ctx = open_session();
        let victim = request("op-consumed-victim", "10:00:00", "consumed body marker");
        ctx.session.create_memo(victim.clone()).expect("create");
        // Three seals evict the journal receipt (MAX_RETIRED_EPOCHS = 2), leaving the
        // durable identity record as the only witness for the consumed operation id.
        // Distinct times and bodies keep `reconcile_external` bindings unambiguous.
        for (operation, time, body) in [
            ("op-filler-two", "10:01:00", "filler alpha"),
            ("op-filler-three", "10:02:00", "filler beta"),
            ("op-filler-four", "10:03:00", "filler gamma"),
        ] {
            ctx.session
                .create_memo(request(operation, time, body))
                .expect("filler create");
            assert!(ctx.session.seal().expect("seal"), "seal must retire");
        }

        // An external edit the watcher would report: the scoped pass re-encodes the
        // identity record (fresh `scan-*` operation appended to `operations`).
        let doc_path = ctx.workspace.join(DOC);
        let mut text = fs::read_to_string(&doc_path).expect("doc before");
        text.push_str("- 11:00:00\nexternal reconcile block\n\n");
        fs::write(&doc_path, text).expect("external rewrite");
        ctx.session
            .reconcile_observed_paths(&[RelativeWorkspacePath::parse(DOC).expect("doc path")])
            .expect("scoped reconcile");

        let retry = ctx.session.create_memo(victim);
        let Err(error) = retry else {
            panic!(
                "a consumed operation id survived the identity-record rewrite as a fresh \
                 command — the reconcile pass dropped `operations`"
            );
        };
        assert_eq!(
            error.code(),
            "operation_expired",
            "a consumed id must classify as expired after reconcile rewrote its record, \
             got {error:?}"
        );
        let after = fs::read_to_string(&doc_path).expect("doc after");
        assert_eq!(
            after.matches("consumed body marker").count(),
            1,
            "an expired retry must not append a second block"
        );
    }

    /// Finding 11-K-01 (see `audit/11-再复审-核心与平台.md` §2): a peer-side soft
    /// delete — doc removed, durable trash record arrived — must project the memo
    /// into the trash lane. When no `file_listing` baseline exists yet (a session
    /// that only ever committed through SAF), the scoped pass bails to the inventory
    /// gate on `snapshot.is_empty()`, and the gate compares `(memo_id,
    /// file_fingerprint)` pairs plus attachment COUNT — no lifecycle lane. A clean
    /// trash record claims the same fingerprint and the same attachment count, so
    /// the live→trash transition is certified as "no change" and the durable delete
    /// never lands — permanently: the gate's `align_listing_snapshot` then commits
    /// the post-delete listing, sealing the blind spot into the baseline.
    #[test]
    fn a_peer_soft_delete_must_not_be_certified_away_by_the_inventory_gate() {
        let ctx = open_session();
        let memo: MemoId = ctx
            .session
            .create_memo(request("op-trash-src", "10:00:00", "peer-deleted body"))
            .expect("create")
            .memo_id;
        let snapshot = ctx
            .session
            .projected_memo(memo.as_str())
            .expect("snapshot query")
            .expect("memo projected");

        // Peer delete: the block leaves the document and a durable trash record claims
        // the identity; the watcher reports the document path and the trash directory.
        fs::remove_file(doc_path(&ctx)).expect("peer removes the doc");
        write_trash_record(&ctx, &trash_record_for(&memo, &snapshot));
        let mut passes = Vec::new();
        for _ in 0..2 {
            passes.push(
                ctx.session
                    .reconcile_observed_paths(&[
                        RelativeWorkspacePath::parse(DOC).expect("doc path"),
                        RelativeWorkspacePath::parse(".lomo/trash").expect("trash dir"),
                    ])
                    .expect("reconcile must not error"),
            );
        }

        assert!(
            trash_ids(&ctx).iter().any(|id| id == memo.as_str()),
            "a clean peer soft-delete must land — the inventory gate may not certify \
             the live row as equal to a trash claim for the same fingerprint; \
             memo_id={:?} passes={passes:?}",
            memo.as_str()
        );
    }

    /// Control for the gate-blindness probe: once a reconcile has committed a
    /// `file_listing` baseline, the scoped pass can prove the document's removal and
    /// `retire_memo` re-admits the trash record — the same durable facts then claim.
    /// If THIS regresses, the defect is in the merge arm, not the gate's evidence shape.
    #[test]
    fn a_seeded_listing_baseline_lets_the_same_trash_record_claim() {
        let ctx = open_session();
        let memo: MemoId = ctx
            .session
            .create_memo(request("op-trash-src", "10:00:00", "peer-deleted body"))
            .expect("create")
            .memo_id;
        let snapshot = ctx
            .session
            .projected_memo(memo.as_str())
            .expect("snapshot query")
            .expect("memo projected");

        // Seed the committed listing baseline through one ordinary reconcile so the
        // scoped pass can prove the later removal.
        ctx.session
            .reconcile_observed_paths(&[RelativeWorkspacePath::parse(DOC).expect("doc path")])
            .expect("baseline reconcile");

        fs::remove_file(doc_path(&ctx)).expect("peer removes the doc");
        write_trash_record(&ctx, &trash_record_for(&memo, &snapshot));
        ctx.session
            .reconcile_observed_paths(&[
                RelativeWorkspacePath::parse(DOC).expect("doc path"),
                RelativeWorkspacePath::parse(".lomo/trash").expect("trash dir"),
            ])
            .expect("scoped reconcile");

        assert!(
            trash_ids(&ctx).iter().any(|id| id == memo.as_str()),
            "with a committed baseline the peer soft-delete must land"
        );
    }

    /// A peer-side soft delete observed through reconcile — block removed, durable trash
    /// record arrived — must leave `attachment_ref` rows that protect the memo's media
    /// as `TrashMemo` coverage, identical to what the SAF `Delete` commit arm projects.
    /// Otherwise a reconcile-fed delete lets the next sweep reclaim restorable bytes.
    #[test]
    fn media_referenced_by_a_reconcile_trashed_memo_must_survive_the_sweep() {
        let ctx = open_session();
        let media_path = ctx.workspace.join("media/keep.png");
        fs::create_dir_all(media_path.parent().expect("media dir")).expect("media dir");
        write_bytes_for_tests(&media_path, PNG).expect("seed media");
        let memo: MemoId = ctx
            .session
            .create_memo(request(
                "op-trash-src",
                "10:00:00",
                "see ![[media/keep.png]]",
            ))
            .expect("create")
            .memo_id;
        let snapshot = ctx
            .session
            .projected_memo(memo.as_str())
            .expect("snapshot query")
            .expect("memo projected");

        // Seed the committed listing baseline so the scoped pass can prove the removal
        // (the gate-blindness precondition is probed separately above).
        ctx.session
            .reconcile_observed_paths(&[RelativeWorkspacePath::parse(DOC).expect("doc path")])
            .expect("baseline reconcile");

        // Peer delete: the block leaves the document and a durable trash record claims
        // the identity; the watcher reports the document path and the trash directory.
        fs::remove_file(doc_path(&ctx)).expect("peer removes the doc");
        write_trash_record(&ctx, &trash_record_for(&memo, &snapshot));
        ctx.session
            .reconcile_observed_paths(&[
                RelativeWorkspacePath::parse(DOC).expect("doc path"),
                RelativeWorkspacePath::parse(".lomo/trash").expect("trash dir"),
            ])
            .expect("scoped reconcile");
        assert!(
            trash_ids(&ctx).iter().any(|id| id == memo.as_str()),
            "the reconciled trash record must claim the memo"
        );

        let report = ctx
            .session
            .media_orphan_sweep(NOW_MS, WINDOW_MS)
            .expect("sweep");
        assert!(
            report.protections.iter().any(|item| {
                item.relative_path == "media/keep.png"
                    && item.source == lomo_media::ReferenceSource::TrashMemo
            }),
            "media referenced only by a reconcile-trashed memo must stay protected on the \
             trash lane; protections={:?} moved={:?} failures={:?}",
            report.protections,
            report.moved_to_trash,
            report.failures
        );
        assert!(
            media_path.is_file(),
            "the incremental trash lane must protect restorable media just like the \
             commit path does"
        );
    }

    fn doc_path(ctx: &Ctx) -> PathBuf {
        ctx.workspace.join(DOC)
    }
}
