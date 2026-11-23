// Dependency upgrade — Test Change Justification:
// Reason category: mechanical migration.
// Old behavior/assertion being replaced: old dependency API spelling and boolean empty checks.
// Why old assertion is no longer correct: updated APIs and Rust 1.99 diagnostics require explicit
// byte encoding, image geometry types and equality assertions that print failing collections.
// Coverage preserved by: the same scenarios, input bytes, expected values, ordering and failures.
// Why this is not fitting the test to the implementation: observable behavior and golden vectors
// are unchanged; only equivalent API/assertion syntax and fixture construction are migrated.
//
// adversarial-audit: a permanently deleted memo has no durable tombstone;
// a well-formed TrashRecordV1 rewritten under .trash/ resurrects the memo
// in the next projection rebuild
//!
//! Hypothesis under audit:
//! - `permanently_delete_many` deletes `.trash/<sha256(memo_id)>.md` files
//!   and the projection rows, but nothing records "this `memo_id` was permanently
//!   deleted". `scan_trash_files` decodes every well-formed `.trash/*.rec`
//!   into `ScannedTrashProjection` without consulting `MemoIdentityMap` or any
//!   permanent-delete ledger.
//! - Therefore a stray record — a lagging sync peer re-delivering the .rec, a
//!   filesystem-level restore, or a manual copy back into the Direct workspace —
//!   resurrects a "permanently deleted" memo on the next rebuild, re-exposing
//!   its body/tags/reminders to the trash lane.
//! - Compound check: an idempotent replay of the committed batch performs no IO
//!   at all, so it cannot even observe the resurrected record; the file survives
//!   the replay.
//!
//! If `rewritten_trash_record_does_not_resurrect_permanently_deleted_memo` fails,
//! permanent deletion is not durable against record resurrection.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "adversarial audit tests fail closed with panics on missing facts"
)]
mod tests {
    use std::{fs, sync::Arc};

    use lomo_application::{
        CreateMemoRequest, DeleteMemoRequest, EmptyTrashRequest, PermanentDeleteManyRequest,
        PermanentDeleteManyTarget, WorkspaceSession, WorkspaceSessionConfig,
    };
    use lomo_core::{CapabilityToken, OperationId, RelativeWorkspacePath};
    use lomo_platform_fs::FsPlatformActionExecutor;
    use lomo_workspace::{
        WorkspaceRootId, decode_trash_record, trash_record_relative_path, write_trash_record_atomic,
    };
    use tempfile::tempdir;

    struct Ctx {
        session: WorkspaceSession,
        workspace_path: std::path::PathBuf,
        _workspace: tempfile::TempDir,
        _state: tempfile::TempDir,
        _cache: tempfile::TempDir,
        _runtime: tempfile::TempDir,
        _exchange: tempfile::TempDir,
    }

    fn open_session() -> Ctx {
        let workspace = tempdir().expect("ws");
        let state = tempdir().expect("st");
        let cache = tempdir().expect("ca");
        let runtime = tempdir().expect("rt");
        let exchange = tempdir().expect("ex");
        let executor = Arc::new(FsPlatformActionExecutor::new(exchange.path()).expect("exec"));
        let capability = CapabilityToken::parse("notes").expect("cap");
        executor
            .bind_root(capability.clone(), workspace.path())
            .expect("bind");
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
            workspace_path: workspace.path().to_path_buf(),
            session,
            _workspace: workspace,
            _state: state,
            _cache: cache,
            _runtime: runtime,
            _exchange: exchange,
        }
    }

    fn trash_memo(
        ctx: &Ctx,
        op_seed: &str,
        file: &str,
        content: &str,
    ) -> PermanentDeleteManyTarget {
        let created = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse(&format!("{op_seed}-create")).expect("op"),
                relative_path: Some(RelativeWorkspacePath::parse(file).expect("path")),
                time_token: Some("12:00:00".to_owned()),
                content: content.to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
                date_format: None,
                timestamp_format: None,
            })
            .expect("create");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        let deleted = ctx
            .session
            .delete_memo(DeleteMemoRequest {
                operation_id: OperationId::parse(&format!("{op_seed}-delete")).expect("op"),
                memo_id: created.memo_id.clone(),
                expected_document_fingerprint: created.commit_result.file_fingerprint,
                trashed_at_ms: Some(1_757_500_000_000),
            })
            .expect("delete");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        PermanentDeleteManyTarget {
            memo_id: created.memo_id,
            source_path: file.to_owned(),
            expected_revision: deleted.commit_result.content_revision,
            expected_fingerprint: deleted.commit_result.file_fingerprint,
        }
    }

    // Behavior Contract:
    // Capability: admit earlier digest-only trash names without losing recovery facts; application; P0.
    // Given an old record or an interrupted admission with both names, when rebuilding,
    // then only the dated name remains and the memo can still be restored.
    // Observable outcomes: durable bytes, names, projected body and restore result.
    // TDD proof: gallery-media-refresh/trash-red.log rejects digest-only user filenames;
    // rust-green.log and trash-migration-green.log exercise recovery through the real session.
    // Excludes: Android provider execution and concurrent filesystem writers.
    #[test]
    fn digest_only_trash_names_are_admitted_and_interrupted_admission_resumes() {
        for interrupted in [false, true] {
            let ctx = open_session();
            let target = trash_memo(&ctx, "rename", "2026_09_21.md", "recover dated trash #tag");
            let relative = trash_record_relative_path(target.memo_id.as_str(), &target.source_path)
                .expect("dated address");
            assert!(relative.as_str().starts_with(".trash/2026_09_21-"));
            let dated = ctx.workspace_path.join(relative.as_str());
            let bytes = fs::read(&dated).expect("snapshot");
            let facts = decode_trash_record(&bytes).expect("checked record");
            let digest_name = relative
                .as_str()
                .rsplit_once('-')
                .expect("identity suffix")
                .1;
            let old = ctx.workspace_path.join(".trash").join(digest_name);
            if interrupted {
                fs::write(&old, &bytes).expect("admission interrupted after destination write");
            } else {
                fs::rename(&dated, &old).expect("previous release name");
            }

            ctx.session
                .rebuild_projection()
                .expect("admit previous name");

            assert!(!old.exists());
            assert_eq!(fs::read(&dated).expect("dated bytes"), bytes);
            assert_eq!(
                decode_trash_record(&fs::read(&dated).expect("snapshot")).expect("facts"),
                facts
            );
            let page = ctx
                .session
                .list_memos(&lomo_store::MemoQuery {
                    search_text: None,
                    filters: lomo_store::MemoFilters {
                        trash_only: true,
                        ..lomo_store::MemoFilters::default()
                    },
                    sort: lomo_store::MemoSort::default(),
                })
                .expect("trash page");
            assert_eq!(page.items.len(), 1);
            assert_eq!(
                page.items.first().expect("trash row").memo_id,
                target.memo_id.as_str()
            );
            ctx.session
                .restore_memo(&lomo_application::RestoreMemoRequest {
                    operation_id: OperationId::parse("restore-renamed").expect("op"),
                    memo_id: target.memo_id.clone(),
                })
                .expect("restore renamed memo");
            ctx.session.settle_workspace().expect("settle restore");
            assert!(!dated.exists());
            assert!(
                ctx.session
                    .get_memo(&target.memo_id)
                    .expect("query")
                    .expect("restored memo")
                    .body
                    .contains("recover dated trash #tag")
            );
        }
    }

    #[test]
    fn trash_name_admission_rejects_different_existing_recovery_facts() {
        let ctx = open_session();
        let target = trash_memo(&ctx, "collision", "2026_09_21.md", "original recovery");
        let relative = trash_record_relative_path(target.memo_id.as_str(), &target.source_path)
            .expect("dated address");
        let dated = ctx.workspace_path.join(relative.as_str());
        let bytes = fs::read(&dated).expect("snapshot");
        let old = ctx.workspace_path.join(".trash").join(
            relative
                .as_str()
                .rsplit_once('-')
                .expect("identity suffix")
                .1,
        );
        fs::write(&old, &bytes).expect("previous name");
        let mut conflicting = decode_trash_record(&bytes).expect("record");
        conflicting.body = "different recovery".to_owned();
        let conflicting_bytes = lomo_workspace::encode_trash_record(&conflicting).expect("encode");
        fs::write(&dated, &conflicting_bytes).expect("conflicting destination");

        let error = ctx
            .session
            .rebuild_projection()
            .expect_err("do not overwrite recovery facts");

        assert_eq!(error.code(), "trash_admission_conflict");
        assert_eq!(fs::read(old).expect("old recovery retained"), bytes);
        assert_eq!(
            fs::read(dated).expect("dated recovery retained"),
            conflicting_bytes
        );
    }

    #[test]
    fn two_dated_addresses_cannot_claim_the_same_recovery_identity() {
        let ctx = open_session();
        let target = trash_memo(&ctx, "duplicate", "2026_09_21.md", "recover once");
        let relative = trash_record_relative_path(target.memo_id.as_str(), &target.source_path)
            .expect("dated address");
        let original = ctx.workspace_path.join(relative.as_str());
        let bytes = fs::read(&original).expect("original snapshot");
        let mut duplicate = decode_trash_record(&bytes).expect("record");
        duplicate.source_path = "2026_09_22.md".to_owned();
        let second = ctx.workspace_path.join(
            trash_record_relative_path(&duplicate.memo_id, &duplicate.source_path)
                .expect("second address")
                .as_str(),
        );
        let duplicate_bytes = lomo_workspace::encode_trash_record(&duplicate).expect("encode");
        fs::write(&second, &duplicate_bytes).expect("duplicate claim");

        let error = ctx
            .session
            .rebuild_projection()
            .expect_err("ambiguous recovery must reject");

        assert_eq!(error.code(), "duplicate_saf_trash_memo");
        assert_eq!(fs::read(original).expect("original preserved"), bytes);
        assert_eq!(
            fs::read(second).expect("duplicate preserved"),
            duplicate_bytes
        );
    }

    #[test]
    fn rewritten_trash_record_does_not_resurrect_permanently_deleted_memo() {
        let ctx = open_session();
        let target = trash_memo(&ctx, "res", "2026_09_21.md", "body that must stay deleted");
        let record_rel = trash_record_relative_path(target.memo_id.as_str(), &target.source_path)
            .expect("trash rel");
        let record_abs = ctx.workspace_path.join(record_rel.as_str());
        assert!(record_abs.is_file(), "soft delete must drop a trash record");

        // Capture the exact durable record before the batch delete removes it —
        // this is the byte shape a lagging peer or a backup restore re-delivers.
        let resurrected_record =
            decode_trash_record(&fs::read(&record_abs).expect("read record")).expect("decode");

        let result = ctx
            .session
            .permanently_delete_many(&PermanentDeleteManyRequest {
                operation_id: OperationId::parse("res-purge").expect("op"),
                targets: vec![target.clone()],
            })
            .expect("batch delete");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        assert!(!result.idempotent_replay);
        assert!(!record_abs.exists(), "committed batch deletes the record");
        assert!(
            ctx.session
                .get_memo(&target.memo_id)
                .expect("get")
                .is_none(),
            "projection row is gone"
        );

        // The stray record reappears after the commit (peer re-delivery / FS
        // restore). An idempotent replay performs no IO, so it cannot observe
        // or re-delete the resurrected file.
        write_trash_record_atomic(&record_abs, &resurrected_record).expect("rewrite record");
        let replayed = ctx
            .session
            .permanently_delete_many(&PermanentDeleteManyRequest {
                operation_id: OperationId::parse("res-purge").expect("op"),
                targets: vec![target.clone()],
            })
            .expect("replay");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        assert!(replayed.idempotent_replay, "committed batch replays");
        assert!(
            record_abs.exists(),
            "replay is journal-only: it never re-deletes the resurrected record"
        );

        // The next projection rebuild consumes durable records as truth.
        ctx.session.rebuild_projection().expect("rebuild");
        let view = ctx
            .session
            .get_memo(&target.memo_id)
            .expect("get after rebuild");
        assert!(
            view.is_none(),
            "a rewritten trash record must not resurrect a permanently deleted memo; \
             got back a projection for {:?}",
            target.memo_id
        );
        // `get_memo` filters trashed rows, so also interrogate the trash lane
        // directly: a resurrected record would re-project the memo there with
        // its full body/tags/reminders.
        let trash_page = ctx
            .session
            .list_memos(&lomo_store::MemoQuery {
                search_text: None,
                filters: lomo_store::MemoFilters {
                    include_trash: true,
                    trash_only: true,
                    ..lomo_store::MemoFilters::default()
                },
                sort: lomo_store::MemoSort::default(),
            })
            .expect("trash page");
        assert!(
            trash_page
                .items
                .iter()
                .all(|summary| summary.memo_id != target.memo_id.as_str()),
            "resurrected trash record re-projected the permanently deleted memo \
             into the trash lane"
        );
    }

    #[test]
    fn parent_operation_id_length_leaves_room_for_batch_token() {
        let ctx = open_session();
        let target = trash_memo(&ctx, "len", "2026_09_22.md", "length probe");

        // child id = "{parent}.{token16}"; the parent budget is 128 - 1 - 16 = 111.
        let ok_parent = "o".repeat(111);
        ctx.session
            .permanently_delete_many(&PermanentDeleteManyRequest {
                operation_id: OperationId::parse(&ok_parent).expect("op"),
                targets: vec![target],
            })
            .expect("111-char parent fits one derived child id");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");

        let target2 = trash_memo(&ctx, "len2", "2026_09_23.md", "length probe 2");
        let long_parent = "p".repeat(112);
        let error = ctx
            .session
            .permanently_delete_many(&PermanentDeleteManyRequest {
                operation_id: OperationId::parse(&long_parent).expect("op parses"),
                targets: vec![target2.clone()],
            })
            .expect_err("112-char parent must be rejected before any IO");
        assert_eq!(error.code(), "invalid_operation_id");
        // Rejected before IO: the trash record of the refused batch survives.
        let rel = trash_record_relative_path(target2.memo_id.as_str(), &target2.source_path)
            .expect("rel");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        assert!(ctx.workspace_path.join(rel.as_str()).is_file());
    }

    /// `empty_trash` is the host's single batch call: the engine enumerates the trash
    /// lane into CAS targets and commits bounded child batches — no per-item
    /// mutation runs on this path, so one call drops every trashed row durably.
    #[test]
    fn empty_trash_removes_every_trashed_memo_in_one_call() {
        let ctx = open_session();
        let first = trash_memo(&ctx, "et-a", "2026_09_24.md", "alpha body");
        let second = trash_memo(&ctx, "et-b", "2026_09_25.md", "beta body");
        let live = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("et-live").expect("op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_09_26.md").expect("path")),
                time_token: Some("12:00:00".to_owned()),
                content: "still alive".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
                date_format: None,
                timestamp_format: None,
            })
            .expect("live create");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");

        let result = ctx
            .session
            .empty_trash(
                &EmptyTrashRequest {
                    operation_id: OperationId::parse("et-purge").expect("op"),
                },
                &mut || false,
            )
            .expect("empty trash");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        assert!(!result.cancelled);
        assert_eq!(result.removed, 2);
        assert!(
            result.commit_result.is_some(),
            "a non-empty sweep carries its last child receipt"
        );
        for target in [&first, &second] {
            let rel = trash_record_relative_path(target.memo_id.as_str(), &target.source_path)
                .expect("rel");
            assert!(
                !ctx.workspace_path.join(rel.as_str()).exists(),
                "the durable trash record is deleted"
            );
            assert!(
                ctx.session
                    .get_memo(&target.memo_id)
                    .expect("get")
                    .is_none(),
                "the projection row is gone"
            );
        }
        assert!(
            ctx.session.get_memo(&live.memo_id).expect("get").is_some(),
            "a live memo is never part of the sweep"
        );
        let trash = ctx
            .session
            .list_memos(&lomo_store::MemoQuery {
                search_text: None,
                filters: lomo_store::MemoFilters {
                    include_trash: true,
                    trash_only: true,
                    ..lomo_store::MemoFilters::default()
                },
                sort: lomo_store::MemoSort::default(),
            })
            .expect("trash page");
        assert!(trash.items.is_empty(), "the trash lane is empty");
    }

    /// Cancellation is an explicit result, not a swallowed error: a token that
    /// already fired reports `cancelled` with zero removals and deletes nothing,
    /// and a later pass with a live token still completes the sweep.
    #[test]
    fn cancelled_empty_trash_reports_progress_explicitly() {
        let ctx = open_session();
        let target = trash_memo(&ctx, "cx", "2026_09_27.md", "survive cancel");

        let cancelled = ctx
            .session
            .empty_trash(
                &EmptyTrashRequest {
                    operation_id: OperationId::parse("cx-purge").expect("op"),
                },
                &mut || true,
            )
            .expect("cancelled pass still reports its committed state");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        assert!(cancelled.cancelled);
        assert_eq!(cancelled.removed, 0);
        assert_eq!(
            cancelled.batches,
            Vec::<lomo_store::SafProjectionCommitResult>::new()
        );
        let rel =
            trash_record_relative_path(target.memo_id.as_str(), &target.source_path).expect("rel");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        assert!(
            ctx.workspace_path.join(rel.as_str()).is_file(),
            "a pre-empted sweep deleted nothing"
        );

        let done = ctx
            .session
            .empty_trash(
                &EmptyTrashRequest {
                    operation_id: OperationId::parse("cx-purge-2").expect("op"),
                },
                &mut || false,
            )
            .expect("retry with a live token");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        assert_eq!(done.removed, 1);
        assert!(!ctx.workspace_path.join(rel.as_str()).exists());
    }

    /// An already-empty trash lane is a real domain state, not an error: zero
    /// removals and no child receipts.
    #[test]
    fn empty_trash_on_empty_trash_is_a_no_op() {
        let ctx = open_session();
        let result = ctx
            .session
            .empty_trash(
                &EmptyTrashRequest {
                    operation_id: OperationId::parse("noop-purge").expect("op"),
                },
                &mut || false,
            )
            .expect("empty lane");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        assert_eq!(result.removed, 0);
        assert!(result.commit_result.is_none());
        assert_eq!(
            result.batches,
            Vec::<lomo_store::SafProjectionCommitResult>::new()
        );
        assert!(!result.cancelled);
    }
}
