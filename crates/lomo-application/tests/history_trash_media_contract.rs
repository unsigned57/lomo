//! Behavior Contract
//! Capability: history listing, trash restore, attachment protection, archive export/import,
//! and fail-closed soft delete when the activity Markdown source has been removed externally.
//! Scenarios: wikilink attachments survive soft delete via history/trash refs; restore rebinds
//! the original `MemoId` and returns a full store commit (`core_revision` + scopes); archive
//! export is self-contained; a corrupt archive fails closed; deleting a projected memo whose `.md`
//! was unlinked outside Lomo returns `memo_source_missing` and does not invent a trash record.
//! Observable outcomes: protected paths, restored bodies, restore commit clocks, zip entries,
//! unchanged live files, error code `memo_source_missing`, and no `.lomo/trash/v1` record for the
//! rejected delete.
//! TDD proof: session lifecycle/media/archive APIs did not exist; missing-source delete used to
//! parse an empty document and fail later at identity resolution.
//! Excludes: Android SAF archive UI.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed on missing facts"
)]
mod tests {
    use std::{fs, sync::Arc};

    use lomo_application::{
        CreateMemoRequest, DeleteMemoRequest, PermanentDeleteManyRequest,
        PermanentDeleteManyTarget, RestoreMemoRequest, WorkspaceSession, WorkspaceSessionConfig,
    };
    use lomo_core::{CapabilityToken, OperationId, RelativeWorkspacePath};
    use lomo_media::{
        MediaSource, PromotePlan, stage_media, suggest_human_relative_path, write_bytes_for_tests,
    };
    use lomo_platform_fs::FsPlatformActionExecutor;
    use lomo_workspace::{WorkspaceRootId, trash_record_relative_path};
    use tempfile::tempdir;

    const PNG: &[u8] = &[
        0x89, b'P', b'N', b'G', b'\r', b'\n', 0x1a, b'\n', 0x00, 0x00, 0x00, 0x0d, b'I', b'H',
        b'D', b'R', 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00,
        0x90, 0x77, 0x53, 0xde, 0x00, 0x00, 0x00, 0x0c, b'I', b'D', b'A', b'T', 0x08, 0xd7, 0x63,
        0xf8, 0xcf, 0xc0, 0x00, 0x00, 0x03, 0x01, 0x01, 0x00, 0x18, 0xdd, 0x8d, 0xb4, 0x00, 0x00,
        0x00, 0x00, b'I', b'E', b'N', b'D', 0xae, 0x42, 0x60, 0x82,
    ];

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

    #[test]
    fn soft_delete_keeps_wikilink_attachments_and_restore_rebinds_id() {
        let ctx = open_session();
        fs::write(ctx.workspace_path.join("image.png"), PNG).expect("png");
        let created = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("pic").expect("op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_09_10.md").expect("path")),
                time_token: Some("12:00:00".to_owned()),
                content: "see ![[image.png]]".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create");
        let history = ctx
            .session
            .list_history(&created.memo_id, None, 10)
            .expect("history");
        assert!(!history.items.is_empty());
        ctx.session
            .delete_memo(DeleteMemoRequest {
                operation_id: OperationId::parse("del").expect("op"),
                memo_id: created.memo_id.clone(),
                expected_document_fingerprint: created.commit_result.file_fingerprint,
                trashed_at_ms: Some(1_757_500_000_000),
            })
            .expect("delete");
        assert!(
            ctx.session
                .attachment_is_protected("image.png")
                .expect("protected")
        );
        assert!(ctx.workspace_path.join("image.png").exists());
        let restored_commit = ctx
            .session
            .restore_memo(&RestoreMemoRequest {
                operation_id: OperationId::parse("undel").expect("op"),
                memo_id: created.memo_id.clone(),
            })
            .expect("restore");
        assert!(
            restored_commit.commit_result.core_revision > created.commit_result.core_revision,
            "restore must publish a later store clock than create"
        );
        assert!(
            !restored_commit.commit_result.scopes.is_empty(),
            "restore commit must carry rust-owned scopes"
        );
        let restored = ctx
            .session
            .get_memo(&created.memo_id)
            .expect("get")
            .expect("found");
        assert!(restored.body.contains("image.png"));
    }

    #[test]
    fn delete_fails_closed_when_markdown_was_removed_externally() {
        let ctx = open_session();
        let created = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("gone-src").expect("op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_09_10.md").expect("path")),
                time_token: Some("12:00:00".to_owned()),
                content: "about to vanish".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create");
        fs::remove_file(ctx.workspace_path.join("2026_09_10.md")).expect("unlink source");
        let error = ctx
            .session
            .delete_memo(DeleteMemoRequest {
                operation_id: OperationId::parse("del-missing").expect("op"),
                memo_id: created.memo_id.clone(),
                expected_document_fingerprint: created.commit_result.file_fingerprint,
                trashed_at_ms: Some(1_757_500_000_000),
            })
            .expect_err("missing activity source must fail closed");
        assert_eq!(error.code(), "memo_source_missing");
        let trash = trash_record_relative_path(created.memo_id.as_str()).expect("trash path");
        assert!(
            !ctx.workspace_path.join(trash.as_str()).exists(),
            "delete must not invent a trash record for a missing source"
        );
    }

    #[test]
    fn archive_export_contains_notes_and_corrupt_import_leaves_live_workspace() {
        let ctx = open_session();
        ctx.session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("note").expect("op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_09_10.md").expect("path")),
                time_token: Some("13:00:00".to_owned()),
                content: "archive me".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create");
        let archives = tempdir().expect("archives");
        let archive_path = archives.path().join("lomo.zip");
        ctx.session
            .export_archive(&ctx.workspace_path, &archive_path)
            .expect("export");
        assert!(archive_path.exists());
        let original =
            fs::read_to_string(ctx.workspace_path.join("2026_09_10.md")).expect("original");
        let bad = archives.path().join("bad.zip");
        fs::write(&bad, b"not a zip").expect("bad zip");
        let staging = tempdir().expect("staging");
        if let Ok(result) = ctx
            .session
            .import_archive(&ctx.workspace_path, &bad, staging.path())
        {
            panic!("corrupt import must fail, got {result:?}");
        }
        let after = fs::read_to_string(ctx.workspace_path.join("2026_09_10.md")).expect("after");
        assert_eq!(original, after);
    }

    #[test]
    fn create_promotes_staged_attachment_in_the_same_frozen_transaction() {
        let ctx = open_session();
        let src = ctx.workspace_path.join("in.png");
        write_bytes_for_tests(&src, PNG).expect("write source");
        let staged = stage_media(
            &ctx.workspace_path,
            MediaSource::DirectPath { path: src },
            "shot.png",
        )
        .expect("stage");
        let final_rel = suggest_human_relative_path("shot", staged.mime).expect("path");
        let staging_path = staged.staging_path.clone();
        let body = format!("see ![[{}]]", final_rel.as_str());
        let created = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("pic-attach").expect("op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_09_10.md").expect("path")),
                time_token: Some("14:00:00".to_owned()),
                content: body,
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: vec![PromotePlan {
                    operation_id: "pic-attach".to_owned(),
                    staged,
                    final_relative_path: final_rel.clone(),
                }],
                chronology_epoch_ms: None,
            })
            .expect("create with attachment");
        let dest = ctx.workspace_path.join(final_rel.as_str());
        assert!(dest.is_file(), "attachment must land in the workspace");
        assert_eq!(fs::read(&dest).expect("dest bytes"), PNG);
        assert!(
            !staging_path.exists(),
            "private stage file is consumed after commit"
        );
        assert!(
            !ctx.workspace_path
                .join("memos")
                .join(format!("{}.md", created.memo_id.as_str()))
                .exists(),
            "session writes must not create Store Direct memos/<id>.md sidecars"
        );
        let loaded = ctx
            .session
            .get_memo(&created.memo_id)
            .expect("get")
            .expect("found");
        assert!(loaded.body.contains(final_rel.as_str()));
    }

    #[test]
    fn create_fails_closed_when_the_staged_attachment_file_is_missing() {
        let ctx = open_session();
        let src = ctx.workspace_path.join("in.png");
        write_bytes_for_tests(&src, PNG).expect("write source");
        let staged = stage_media(
            &ctx.workspace_path,
            MediaSource::DirectPath { path: src },
            "shot.png",
        )
        .expect("stage");
        let final_rel = suggest_human_relative_path("shot", staged.mime).expect("path");
        fs::remove_file(&staged.staging_path).expect("remove staged");
        let body = format!("see ![[{}]]", final_rel.as_str());
        let error = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("pic-missing").expect("op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_09_10.md").expect("path")),
                time_token: Some("14:00:00".to_owned()),
                content: body,
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: vec![PromotePlan {
                    operation_id: "pic-missing".to_owned(),
                    staged,
                    final_relative_path: final_rel.clone(),
                }],
                chronology_epoch_ms: None,
            })
            .expect_err("missing staged file");
        assert_eq!(error.code(), "promote_staged_missing");
        assert!(
            !ctx.workspace_path.join(final_rel.as_str()).exists(),
            "failed promote must not publish the destination file"
        );
        assert!(
            !ctx.workspace_path.join("2026_09_10.md").exists(),
            "failed promote must not publish the dated document"
        );
    }

    #[test]
    fn create_uses_sender_chronology_for_the_dated_filename() {
        let ctx = open_session();
        let chronology = 1_736_942_400_000;
        let stamp = lomo_application::calendar::journal_stamp(
            chronology,
            "UTC",
            lomo_application::calendar::DateFormat::default(),
        )
        .expect("stamp");
        let created = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("lan-chrono").expect("op"),
                relative_path: None,
                time_token: None,
                content: "received from peer".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: Some(chronology),
            })
            .expect("create");
        assert!(
            ctx.workspace_path.join(&stamp.filename).is_file(),
            "dated file must follow sender chronology, expected {}",
            stamp.filename
        );
        let loaded = ctx
            .session
            .get_memo(&created.memo_id)
            .expect("get")
            .expect("found");
        assert_eq!(loaded.created_at_ms, chronology);
        assert!(
            !ctx.workspace_path
                .join("memos")
                .join(format!("{}.md", created.memo_id.as_str()))
                .exists(),
            "session writes must not create Store Direct memos/<id>.md sidecars"
        );
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

    fn trash_record_exists(ctx: &Ctx, target: &PermanentDeleteManyTarget) -> bool {
        let path = trash_record_relative_path(target.memo_id.as_str()).expect("trash path");
        ctx.workspace_path.join(path.as_str()).exists()
    }

    #[test]
    fn permanent_delete_many_commits_sorted_batch_with_durable_receipt() {
        let ctx = open_session();
        let alpha = trash_memo(&ctx, "b-a", "2026_09_11.md", "alpha");
        let beta = trash_memo(&ctx, "b-b", "2026_09_12.md", "beta");
        let gamma = trash_memo(&ctx, "b-c", "2026_09_13.md", "gamma");
        let result = ctx
            .session
            .permanently_delete_many(&PermanentDeleteManyRequest {
                operation_id: OperationId::parse("purge-1").expect("op"),
                // Deliberately unsorted: the batch must canonicalize target order.
                targets: vec![gamma.clone(), alpha.clone(), beta.clone()],
            })
            .expect("batch delete");
        let mut expected = vec![
            alpha.memo_id.clone(),
            beta.memo_id.clone(),
            gamma.memo_id.clone(),
        ];
        expected.sort();
        assert_eq!(result.deleted, expected, "deleted set must be sorted");
        assert_eq!(
            result.batches.len(),
            1,
            "three targets fit one bounded batch"
        );
        assert!(!result.batches.first().expect("one batch").idempotent_replay);
        assert!(!result.commit_result.scopes.is_empty());
        for target in [&alpha, &beta, &gamma] {
            assert!(
                ctx.session
                    .get_memo(&target.memo_id)
                    .expect("get")
                    .is_none(),
                "permanently deleted memo must leave the projection"
            );
            assert!(
                !trash_record_exists(&ctx, target),
                "durable trash record must be deleted with the memo"
            );
        }
    }

    #[test]
    fn permanent_delete_many_replays_committed_batch_without_reexecuting() {
        let ctx = open_session();
        let alpha = trash_memo(&ctx, "r-a", "2026_09_14.md", "alpha");
        let beta = trash_memo(&ctx, "r-b", "2026_09_15.md", "beta");
        let request = PermanentDeleteManyRequest {
            operation_id: OperationId::parse("purge-replay").expect("op"),
            targets: vec![alpha, beta],
        };
        ctx.session
            .permanently_delete_many(&request)
            .expect("first batch");
        let replayed = ctx
            .session
            .permanently_delete_many(&request)
            .expect("replay must succeed");
        assert!(replayed.idempotent_replay);
        assert_eq!(replayed.batches.len(), 1);
        assert!(
            replayed
                .batches
                .first()
                .expect("one batch")
                .idempotent_replay
        );
        assert_eq!(replayed.deleted.len(), 2);
    }

    #[test]
    fn permanent_delete_many_retry_after_failure_never_redoes_committed_batches() {
        let ctx = open_session();
        let chunk_capacity = 128_usize;
        let target_count = chunk_capacity + 1;
        let mut targets = Vec::with_capacity(target_count);
        for index in 0..target_count {
            targets.push(trash_memo(
                &ctx,
                &format!("k-{index:04}"),
                &format!("2026_{:02}_{:02}.md", index / 28 + 1, index % 28 + 1),
                &format!("memo {index}"),
            ));
        }
        targets.sort_by(|left, right| left.memo_id.as_str().cmp(right.memo_id.as_str()));
        // Corrupt the baseline of the last-sorted target so only the second batch fails.
        let mut stale = targets.last().expect("last").clone();
        stale.expected_fingerprint = "00".repeat(32);
        let first_request = PermanentDeleteManyRequest {
            operation_id: OperationId::parse("purge-k").expect("op"),
            targets: {
                let mut list: Vec<_> = targets.iter().take(chunk_capacity).cloned().collect();
                list.push(stale);
                list
            },
        };
        let error = ctx
            .session
            .permanently_delete_many(&first_request)
            .expect_err("stale baseline must fail closed");
        assert_eq!(error.code(), "stale_snapshot");
        let committed = ctx
            .session
            .permanently_delete_many(&PermanentDeleteManyRequest {
                operation_id: OperationId::parse("purge-k").expect("op"),
                targets: targets.clone(),
            })
            .expect("retry with corrected baseline");
        assert!(
            committed.batches.len() > 1,
            "129 targets must split into multiple durable batches"
        );
        assert!(
            committed
                .batches
                .first()
                .expect("first batch")
                .idempotent_replay,
            "the batch committed before the failure must replay instead of re-executing"
        );
        assert!(!committed.batches.last().expect("last").idempotent_replay);
        assert_eq!(committed.deleted.len(), target_count);
        for target in &targets {
            assert!(
                ctx.session
                    .get_memo(&target.memo_id)
                    .expect("get")
                    .is_none()
            );
            assert!(!trash_record_exists(&ctx, target));
        }
    }

    #[test]
    fn permanent_delete_many_rejects_target_restored_since_walk() {
        let ctx = open_session();
        let kept = trash_memo(&ctx, "keep", "2026_09_16.md", "keep me");
        let restored = trash_memo(&ctx, "back", "2026_09_17.md", "restore me");
        ctx.session
            .restore_memo(&RestoreMemoRequest {
                operation_id: OperationId::parse("bring-back").expect("op"),
                memo_id: restored.memo_id.clone(),
            })
            .expect("restore");
        let error = ctx
            .session
            .permanently_delete_many(&PermanentDeleteManyRequest {
                operation_id: OperationId::parse("purge-stale").expect("op"),
                targets: vec![kept.clone(), restored.clone()],
            })
            .expect_err("a restored target must reject the batch");
        assert_eq!(error.code(), "memo_not_trashed");
        assert!(
            ctx.session
                .get_memo(&restored.memo_id)
                .expect("get")
                .is_some(),
            "a restored memo must never be deleted by a stale clear-trash batch"
        );
        assert!(
            trash_record_exists(&ctx, &kept),
            "the surviving trash target must remain recoverable"
        );
    }

    #[test]
    fn permanent_delete_many_rejects_unknown_and_untrashed_targets() {
        let ctx = open_session();
        let live = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("live-c").expect("op"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_09_18.md").expect("path")),
                time_token: Some("12:00:00".to_owned()),
                content: "still active".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create");
        let untrashed = PermanentDeleteManyTarget {
            memo_id: live.memo_id.clone(),
            source_path: "2026_09_18.md".to_owned(),
            expected_revision: live.commit_result.content_revision,
            expected_fingerprint: live.commit_result.file_fingerprint,
        };
        let error = ctx
            .session
            .permanently_delete_many(&PermanentDeleteManyRequest {
                operation_id: OperationId::parse("purge-live").expect("op"),
                targets: vec![untrashed],
            })
            .expect_err("an active memo must reject permanent delete");
        assert_eq!(error.code(), "memo_not_trashed");
        let ghost = PermanentDeleteManyTarget {
            memo_id: lomo_workspace::MemoId::parse("ghost.memo").expect("id"),
            source_path: "2026_09_19.md".to_owned(),
            expected_revision: 1,
            expected_fingerprint: "ab".repeat(32),
        };
        let error = ctx
            .session
            .permanently_delete_many(&PermanentDeleteManyRequest {
                operation_id: OperationId::parse("purge-ghost").expect("op"),
                targets: vec![ghost],
            })
            .expect_err("an absent memo must be rejected explicitly");
        assert_eq!(error.code(), "memo_not_found");
        let empty = ctx
            .session
            .permanently_delete_many(&PermanentDeleteManyRequest {
                operation_id: OperationId::parse("purge-empty").expect("op"),
                targets: Vec::new(),
            })
            .expect_err("empty target list must be rejected");
        assert_eq!(empty.code(), "invalid_batch_targets");
    }
}
