// adversarial-audit: a permanently deleted memo has no durable tombstone;
// a well-formed TrashRecordV1 rewritten under .lomo/trash/v1/ resurrects the memo
// in the next projection rebuild
//!
//! Hypothesis under audit:
//! - `permanently_delete_many` deletes `.lomo/trash/v1/<sha256(memo_id)>.rec` files
//!   and the projection rows, but nothing records "this `memo_id` was permanently
//!   deleted". `scan_trash_files` decodes every well-formed `.lomo/trash/*.rec`
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
        CreateMemoRequest, DeleteMemoRequest, PermanentDeleteManyRequest,
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
            })
            .expect("create");
        let deleted = ctx
            .session
            .delete_memo(DeleteMemoRequest {
                operation_id: OperationId::parse(&format!("{op_seed}-delete")).expect("op"),
                memo_id: created.memo_id.clone(),
                expected_document_fingerprint: created.commit_result.file_fingerprint,
                trashed_at_ms: Some(1_757_500_000_000),
            })
            .expect("delete");
        PermanentDeleteManyTarget {
            memo_id: created.memo_id,
            source_path: file.to_owned(),
            expected_revision: deleted.commit_result.content_revision,
            expected_fingerprint: deleted.commit_result.file_fingerprint,
        }
    }

    #[test]
    fn rewritten_trash_record_does_not_resurrect_permanently_deleted_memo() {
        let ctx = open_session();
        let target = trash_memo(&ctx, "res", "2026_09_21.md", "body that must stay deleted");
        let record_rel = trash_record_relative_path(target.memo_id.as_str()).expect("trash rel");
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
        let rel = trash_record_relative_path(target2.memo_id.as_str()).expect("rel");
        assert!(ctx.workspace_path.join(rel.as_str()).is_file());
    }
}
