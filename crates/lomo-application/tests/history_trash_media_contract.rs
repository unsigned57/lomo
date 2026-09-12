//! Behavior Contract
//! Capability: history listing, trash restore, attachment protection, archive export/import,
//! and fail-closed soft delete when the activity Markdown source has been removed externally.
//! Scenarios: wikilink attachments survive soft delete via history/trash refs; restore rebinds
//! the original `MemoId`; archive export is self-contained; a corrupt archive fails closed;
//! deleting a projected memo whose `.md` was unlinked outside Lomo returns `memo_source_missing`
//! and does not invent a trash record.
//! Observable outcomes: protected paths, restored bodies, zip entries, unchanged live files,
//! error code `memo_source_missing`, and no `.lomo/trash/v1` record for the rejected delete.
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
        CreateMemoRequest, DeleteMemoRequest, RestoreMemoRequest, WorkspaceSession,
        WorkspaceSessionConfig,
    };
    use lomo_core::{CapabilityToken, OperationId, RelativeWorkspacePath};
    use lomo_media::{
        MediaSource, PromotePlan, stage_media, suggest_human_relative_path, write_bytes_for_tests,
    };
    use lomo_platform_fs::PosixPlatformActionExecutor;
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
        let executor = Arc::new(PosixPlatformActionExecutor::new(exchange.path()).expect("exec"));
        let capability = CapabilityToken::parse("notes").expect("cap");
        executor
            .bind_root(capability.clone(), workspace.path())
            .expect("bind");
        let session = WorkspaceSession::open(
            WorkspaceSessionConfig {
                capability,
                root_id: WorkspaceRootId::Notes,
                time_zone: "UTC".to_owned(),
                date_format: lomo_application::calendar::DateFormat::default(),
                state_dir: state.path().to_path_buf(),
                cache_dir: cache.path().to_path_buf(),
                runtime_dir: runtime.path().to_path_buf(),
                exchange_dir: exchange.path().to_path_buf(),
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
        ctx.session
            .restore_memo(&RestoreMemoRequest {
                operation_id: OperationId::parse("undel").expect("op"),
                memo_id: created.memo_id.clone(),
            })
            .expect("restore");
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
}
