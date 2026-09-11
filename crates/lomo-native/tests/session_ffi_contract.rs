//! Behavior Contract
//!
//! Capability: `lomo-native` `BoltFFI` is a type bridge onto `lomo-application`. Memo writes and
//! dual-mode search cross JNI as DTO conversion plus a synchronous `PlatformBatchHost` callback;
//! the facade does not choose dated paths, mint ordinal ids, or run POSIX I/O itself.
//!
//! Scenarios:
//! - Given an engine with no workspace session, when a session command is called, then the
//!   boundary returns `workspace_session_unavailable` and writes no Markdown.
//! - Given a Direct workspace and a POSIX `PlatformBatchHost`, when `session_create_memo` commits,
//!   then the memo is appended to a dated `yyyy_MM_dd.md` with a CSPRNG `m_` id, `session_get_memo`
//!   returns that body, and no `memos/<id>.md` sidecar is created.
//! - Given an open session and an unknown LAN batch, when `commit_received_lan_item` runs, then
//!   `lan_batch_unknown` fails closed and no Markdown is written.
//! - Given that memo body contains `八达岭长城`, when `session_search` runs in fuzzy mode with
//!   `bdlcc`, then the page contains that memo id.
//! - Given a memo whose body is a Markdown checkbox, when `session_list_tasks` runs, then the
//!   page contains that memo id, line index, and unchecked text.
//! - Given a malformed operation id, when create is requested, then FFI validation fails closed
//!   before any workspace file appears.
//!
//! Observable outcomes: structured `EngineError` codes, workspace Markdown bytes, session DTOs.
//! TDD proof: RED because `open_workspace_session` / `session_create_memo` / `session_search`
//! are not exported on `LomoEngine`.
//! Excludes: Android SAF execution, Kotlin DI cutover, live LAN sockets.

#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    use super::support::{OptionTestExt, ResultTestExt};
    use std::fs;
    use std::path::{Path, PathBuf};

    use lomo_core::{CapabilityToken, PlatformActionExecutor};
    use lomo_native::{
        EngineConfig, EngineError, LomoEngine, PlatformActionBatch, PlatformBatchHost,
        PlatformBatchResult, SessionCreateMemoRequest, SessionSearchMode, SessionSearchOutcome,
        SessionSearchRequest, WorkspaceDescriptor,
    };
    use lomo_platform_fs::PosixPlatformActionExecutor;
    use tempfile::tempdir;

    struct PosixBatchHost {
        executor: PosixPlatformActionExecutor,
    }

    impl PlatformBatchHost for PosixBatchHost {
        fn execute(&self, batch: PlatformActionBatch) -> Result<PlatformBatchResult, EngineError> {
            let core_batch = lomo_native::batch_from_ffi(batch)?;
            let result = self
                .executor
                .execute(&core_batch)
                .map_err(EngineError::from)?;
            Ok(lomo_native::result_to_ffi(&result))
        }
    }

    struct DirectSession {
        _temporary: tempfile::TempDir,
        workspace: PathBuf,
        exchange: PathBuf,
        engine: LomoEngine,
    }

    fn open_direct_engine() -> DirectSession {
        let temporary = tempdir().test_ok("temp");
        let control = temporary.path().join("control");
        let exchange = temporary.path().join("exchange");
        let workspace = temporary.path().join("workspace");
        fs::create_dir_all(&control).test_ok("control");
        fs::create_dir_all(&exchange).test_ok("exchange");
        fs::create_dir_all(&workspace).test_ok("workspace");
        let engine = LomoEngine::open(EngineConfig {
            control_root: control.display().to_string(),
            exchange_root: exchange.display().to_string(),
            workspace: Some(WorkspaceDescriptor::Direct {
                root_path: workspace.display().to_string(),
            }),
            bootstrap_deadline_millis: 30_000,
        })
        .test_ok("direct engine");
        DirectSession {
            _temporary: temporary,
            workspace,
            exchange,
            engine,
        }
    }

    fn attach_posix_session(session: &DirectSession) {
        let executor =
            PosixPlatformActionExecutor::new(&session.exchange).test_ok("posix executor");
        let capability = CapabilityToken::parse("direct-root").test_ok("direct capability");
        executor
            .bind_root(capability, &session.workspace)
            .test_ok("bind workspace");
        session
            .engine
            .open_workspace_session(Box::new(PosixBatchHost { executor }), "UTC".to_owned())
            .test_ok("open session");
    }

    fn dated_markdown(workspace: &Path) -> String {
        let mut bodies = Vec::new();
        for entry in fs::read_dir(workspace).test_ok("list workspace") {
            let path = entry.test_ok("dir entry").path();
            if path.extension().and_then(|ext| ext.to_str()) == Some("md") {
                bodies.push(fs::read_to_string(&path).test_ok("read markdown"));
            }
        }
        assert_eq!(bodies.len(), 1, "expected one dated markdown document");
        bodies.remove(0)
    }

    #[test]
    fn session_commands_fail_closed_before_the_workspace_session_is_opened() {
        let session = open_direct_engine();
        let error = session
            .engine
            .session_create_memo(SessionCreateMemoRequest {
                operation_id: "op-create-unopened".to_owned(),
                relative_path: None,
                time_token: None,
                content: "orphan".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .test_err("create without session");
        assert_eq!(error.code(), "workspace_session_unavailable");
        assert!(
            !workspace_has_markdown(&session.workspace),
            "unopened session must not write markdown"
        );
    }

    #[test]
    fn session_create_writes_dated_markdown_with_csprng_identity_not_store_direct_sidecar() {
        let session = open_direct_engine();
        attach_posix_session(&session);
        let commit = session
            .engine
            .session_create_memo(SessionCreateMemoRequest {
                operation_id: "op-create-great-wall".to_owned(),
                relative_path: None,
                time_token: Some("10:15:00".to_owned()),
                content: "八达岭长城".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .test_ok("create memo");
        assert!(
            commit.memo_id.starts_with("m_") && commit.memo_id.len() == 34,
            "session must mint CSPRNG memo ids, got {}",
            commit.memo_id
        );
        let markdown = dated_markdown(&session.workspace);
        assert!(
            markdown.contains("- 10:15:00"),
            "dated document must keep the time header: {markdown}"
        );
        assert!(
            markdown.contains("八达岭长城"),
            "dated document must keep user bytes: {markdown}"
        );
        assert!(
            !markdown.contains(&commit.memo_id),
            "Markdown must not embed the durable memo id: {markdown}"
        );
        let memos_dir = session.workspace.join("memos");
        assert!(
            !memos_dir.exists()
                || fs::read_dir(&memos_dir)
                    .test_ok("list memos")
                    .next()
                    .is_none(),
            "session writes must not create Store Direct memos/<id>.md sidecars"
        );
        let loaded = session
            .engine
            .session_get_memo(commit.memo_id.clone())
            .test_ok("get memo")
            .test_ok("created memo");
        assert_eq!(loaded.memo_id, commit.memo_id);
        assert_eq!(loaded.body, "八达岭长城");
        assert!(
            Path::new(&loaded.source_path)
                .extension()
                .is_some_and(|ext| ext.eq_ignore_ascii_case("md"))
                && !loaded.source_path.starts_with("memos/"),
            "projection source must be the dated document, got {}",
            loaded.source_path
        );
        let page = session
            .engine
            .query_memos(
                lomo_native::StoreMemoQuery {
                    search_text: None,
                    filters: lomo_native::StoreMemoFilters::default(),
                    sort: lomo_native::StoreMemoSort::default(),
                    boundary: None,
                },
                None,
                32,
            )
            .test_ok("engine query prefers session");
        assert!(
            page.items.iter().any(|item| item.memo_id == commit.memo_id),
            "open session projection must be visible through engine query, got {:?}",
            page.items
                .iter()
                .map(|item| &item.memo_id)
                .collect::<Vec<_>>()
        );
    }

    #[test]
    fn session_search_routes_fuzzy_pinyin_through_application() {
        let session = open_direct_engine();
        attach_posix_session(&session);
        let commit = session
            .engine
            .session_create_memo(SessionCreateMemoRequest {
                operation_id: "op-create-search-target".to_owned(),
                relative_path: None,
                time_token: Some("11:00:00".to_owned()),
                content: "八达岭长城".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .test_ok("create searchable memo");
        let outcome = session
            .engine
            .session_search(SessionSearchRequest {
                query_epoch: 1,
                mode: SessionSearchMode::Fuzzy,
                text: "bdlcc".to_owned(),
                cursor: None,
                page_size: 32,
            })
            .test_ok("fuzzy search");
        let SessionSearchOutcome::Ready { page } = outcome else {
            panic!("expected a ready search page, got {outcome:?}");
        };
        assert!(
            page.items
                .iter()
                .any(|hit| hit.memo_id == commit.memo_id && hit.score > 0),
            "fuzzy search must rank the Great Wall memo, got {:?}",
            page.items
        );
    }

    #[test]
    fn session_create_rejects_malformed_operation_ids_before_writing() {
        let session = open_direct_engine();
        attach_posix_session(&session);
        let error = session
            .engine
            .session_create_memo(SessionCreateMemoRequest {
                operation_id: "not a valid id".to_owned(),
                relative_path: None,
                time_token: None,
                content: "should not land".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .test_err("invalid operation id");
        assert_eq!(error.code(), "invalid_operation_id");
        assert!(
            !workspace_has_markdown(&session.workspace),
            "invalid FFI identity must not create a dated document"
        );
    }

    #[test]
    fn session_list_tasks_aggregates_checkbox_lines_from_dated_markdown() {
        let session = open_direct_engine();
        attach_posix_session(&session);
        let commit = session
            .engine
            .session_create_memo(SessionCreateMemoRequest {
                operation_id: "op-create-task-list".to_owned(),
                relative_path: None,
                time_token: Some("12:00:00".to_owned()),
                content: "- [ ] buy milk".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .test_ok("create task memo");
        let tasks = session.engine.session_list_tasks().test_ok("list tasks");
        assert_eq!(tasks.len(), 1, "session must project one checkbox task");
        let task = tasks.first().test_ok("task item");
        assert_eq!(task.memo_id, commit.memo_id);
        assert_eq!(task.line_index, 0);
        assert!(!task.done);
        assert_eq!(task.text, "buy milk");
    }

    #[test]
    fn commit_received_lan_item_fails_closed_for_unknown_batch_without_writing_markdown() {
        let session = open_direct_engine();
        attach_posix_session(&session);
        let error = session
            .engine
            .commit_received_lan_item("batch-missing".to_owned(), 0, 1_700_000_000_000)
            .test_err("unknown LAN batch");
        assert_eq!(error.code(), "lan_batch_unknown");
        assert!(
            !workspace_has_markdown(&session.workspace),
            "unknown LAN batch must not write markdown"
        );
        assert!(
            !session.workspace.join("memos").exists(),
            "unknown LAN batch must not write memos/<id>.md"
        );
    }

    fn workspace_has_markdown(workspace: &Path) -> bool {
        fs::read_dir(workspace)
            .test_ok("list workspace")
            .any(|entry| {
                entry
                    .test_ok("dir entry")
                    .path()
                    .extension()
                    .and_then(|ext| ext.to_str())
                    == Some("md")
            })
    }
}
