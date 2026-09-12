//! Behavior Contract
//! Capability: Markdown + `.lomo` is a complete interchange unit between two private sessions.
//! Scenarios:
//! - Given session A creates a task memo and pins it;
//!   When the workspace is copied into a fresh private directory for session B;
//!   Then session B reconstructs the same opaque `MemoId`, pin, and unchecked task.
//! - Given session B toggles the task;
//!   When the updated workspace is copied into session C;
//!   Then Markdown contains `- [x]` and the `MemoId` is unchanged.
//!
//! Observable outcomes: identical `MemoId`, pin flag, Markdown checkbox bytes, reconstructed tasks.
//! TDD proof: TC-01/TC-06 interchange is a first-round acceptance lock, not a UI test.
//! Excludes: Android SAF, network sync, LAN.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::indexing_slicing,
    reason = "contract tests fail closed on missing interchange facts"
)]
mod tests {
    use std::{fs, path::Path, sync::Arc};

    use lomo_application::{
        CreateMemoRequest, PinMemoRequest, ToggleTaskRequest, WorkspaceSession,
        WorkspaceSessionConfig,
    };
    use lomo_core::{CapabilityToken, OperationId, RelativeWorkspacePath};
    use lomo_platform_fs::PosixPlatformActionExecutor;
    use lomo_store::{MemoFilters, MemoQuery};
    use lomo_workspace::WorkspaceRootId;
    use tempfile::tempdir;

    struct SessionCtx {
        session: WorkspaceSession,
        workspace_path: std::path::PathBuf,
        _workspace: tempfile::TempDir,
        _state: tempfile::TempDir,
        _cache: tempfile::TempDir,
        _runtime: tempfile::TempDir,
        _exchange: tempfile::TempDir,
    }

    fn open_on(workspace: tempfile::TempDir) -> SessionCtx {
        let state = tempdir().expect("state");
        let cache = tempdir().expect("cache");
        let runtime = tempdir().expect("runtime");
        let exchange = tempdir().expect("exchange");
        let executor = Arc::new(PosixPlatformActionExecutor::new(exchange.path()).expect("exec"));
        let capability = CapabilityToken::parse("notes").expect("cap");
        executor
            .bind_root(capability.clone(), workspace.path())
            .expect("bind");
        let config = WorkspaceSessionConfig {
            capability,
            root_id: WorkspaceRootId::Notes,
            time_zone: "UTC".to_owned(),
            date_format: lomo_application::calendar::DateFormat::default(),
            state_dir: state.path().to_path_buf(),
            cache_dir: cache.path().to_path_buf(),
            runtime_dir: runtime.path().to_path_buf(),
            exchange_dir: exchange.path().to_path_buf(),
        };
        let session = WorkspaceSession::open(config, executor).expect("open");
        SessionCtx {
            workspace_path: workspace.path().to_path_buf(),
            session,
            _workspace: workspace,
            _state: state,
            _cache: cache,
            _runtime: runtime,
            _exchange: exchange,
        }
    }

    fn copy_tree(src: &Path, dst: &Path) {
        fs::create_dir_all(dst).expect("dst");
        for entry in fs::read_dir(src).expect("read") {
            let entry = entry.expect("entry");
            let from = entry.path();
            let to = dst.join(entry.file_name());
            if from.is_dir() {
                copy_tree(&from, &to);
            } else {
                fs::copy(&from, &to).expect("copy file");
            }
        }
    }

    fn copy_workspace(src: &Path) -> tempfile::TempDir {
        let dst = tempdir().expect("copied workspace");
        copy_tree(src, dst.path());
        dst
    }

    fn op(raw: &str) -> OperationId {
        OperationId::parse(raw).expect("op")
    }

    #[test]
    fn copied_workspace_keeps_memo_id_pin_and_toggled_task() {
        let ctx_a = open_on(tempdir().expect("workspace a"));
        let created = ctx_a
            .session
            .create_memo(CreateMemoRequest {
                operation_id: op("create-task"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_09_11.md").expect("path")),
                time_token: Some("10:00:00".to_owned()),
                content: "- [ ] buy milk".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create");
        let memo_id = created.memo_id;
        assert!(
            memo_id.as_str().starts_with("m_"),
            "session create must mint CSPRNG MemoId, got {}",
            memo_id.as_str()
        );
        ctx_a
            .session
            .pin_memo(PinMemoRequest {
                operation_id: op("pin"),
                memo_id: memo_id.clone(),
                pinned: true,
                pinned_at_ms: Some(1_757_548_800_000),
            })
            .expect("pin");

        let ctx_b = open_on(copy_workspace(&ctx_a.workspace_path));
        ctx_b.session.rebuild_projection().expect("rebuild b");
        let viewed = ctx_b
            .session
            .get_memo(&memo_id)
            .expect("get")
            .expect("present after copy");
        assert_eq!(viewed.memo_id, memo_id.as_str());
        assert!(viewed.is_pinned);
        assert!(viewed.body.contains("- [ ] buy milk"));

        ctx_b
            .session
            .toggle_task(ToggleTaskRequest {
                operation_id: op("toggle"),
                memo_id: memo_id.clone(),
                line_index: 0,
                done: true,
            })
            .expect("toggle");
        let markdown_b =
            fs::read_to_string(ctx_b.workspace_path.join("2026_09_11.md")).expect("md b");
        assert!(
            markdown_b.contains("- [x] buy milk"),
            "toggle must write checkbox bytes, got {markdown_b}"
        );

        let ctx_c = open_on(copy_workspace(&ctx_b.workspace_path));
        ctx_c.session.rebuild_projection().expect("rebuild c");
        let viewed_c = ctx_c
            .session
            .get_memo(&memo_id)
            .expect("get c")
            .expect("present after second copy");
        assert_eq!(viewed_c.memo_id, memo_id.as_str());
        assert!(viewed_c.is_pinned);
        assert!(viewed_c.body.contains("- [x] buy milk"));
        let tasks = ctx_c.session.list_tasks().expect("tasks");
        assert!(
            tasks
                .iter()
                .any(|task| task.text == "buy milk" && task.done)
        );
        let pinned = ctx_c
            .session
            .list_memos(&MemoQuery {
                search_text: None,
                filters: MemoFilters {
                    pinned_only: true,
                    ..MemoFilters::default()
                },
                sort: lomo_store::MemoSort::default(),
            })
            .expect("pinned");
        assert_eq!(pinned.items.len(), 1);
        assert_eq!(pinned.items[0].memo_id, memo_id.as_str());
    }
}
