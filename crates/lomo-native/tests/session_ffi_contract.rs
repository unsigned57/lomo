//! Behavior Contract
//! Storage scenarios: external `.lomo` is ignored without deleting its source; private state can reset while existing `.trash` Markdown remains recoverable.
//! Storage evidence: `storage-red.log` observed an external control tree; `plain-trash-red.log` rejected an old daily trash file; `privacy-tests.log` verifies the final behavior.
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
//! - Given a supported date-format token, when the session opens, then memo filenames are minted in
//!   that format instead of `DateFormat::default()`.
//! - Given a foreign date-format token, when the session opens, then the boundary fails closed with
//!   `invalid_calendar_input` and installs no session.
//!
//! Observable outcomes: structured `EngineError` codes, workspace Markdown bytes, session DTOs.
//! TDD proof: RED because `open_workspace_session` / `session_create_memo` / `session_search`
//! are not exported on `LomoEngine`.
//! Excludes: Android SAF execution, Kotlin DI cutover, live LAN sockets.
//! Read-concurrency scenario: Given a session command paused inside platform I/O, When the real
//! facade reads the current memo, Then the published snapshot is returned before the write resumes.
//! Read-concurrency TDD proof: RED times out while the native session mutex is held over host I/O;
//! GREEN uses the same controlled host barrier and asserts the pre-commit body.
//! Acceptance scenario: Given a create paused inside its Markdown write, When the observer has
//! been told the create was accepted, Then that pending memo is already queryable through the
//! facade and the returned commit carries the same id at a later revision; a commit failing after
//! acceptance reports a later withdrawal and leaves no row.
//! Acceptance TDD proof: RED 2026-11-07 — `session_create_memo` took no observer and published
//! only after durable I/O. GREEN: `cargo test -p lomo-native --test session_ffi_contract
//! create_acceptance --locked`.

#[cfg(test)]
mod create_observer;
#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    use super::support::{OptionTestExt, ResultTestExt};
    use std::{
        fs,
        path::{Path, PathBuf},
        sync::{
            Arc, Mutex,
            atomic::{AtomicBool, Ordering},
            mpsc,
        },
        time::Duration,
    };

    use lomo_core::{CapabilityToken, PlatformActionExecutor};
    use lomo_native::{
        EngineConfig, EngineError, LomoEngine, MemoCreateObserver, PlatformActionBatch,
        PlatformBatchHost, PlatformBatchResult, SessionCreateMemoRequest, SessionSearchMode,
        SessionSearchOutcome, SessionSearchRequest, StoreMemoCommit, WorkspaceDescriptor,
    };
    use lomo_platform_fs::FsPlatformActionExecutor;
    use tempfile::tempdir;

    struct PosixBatchHost {
        executor: FsPlatformActionExecutor,
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

    struct PausedBatchHost {
        executor: FsPlatformActionExecutor,
        armed: Arc<AtomicBool>,
        entered: mpsc::Sender<()>,
        resume: Mutex<mpsc::Receiver<()>>,
    }

    impl PlatformBatchHost for PausedBatchHost {
        fn execute(&self, batch: PlatformActionBatch) -> Result<PlatformBatchResult, EngineError> {
            let batch = lomo_native::batch_from_ffi(batch)?;
            if batch.actions().iter().any(|action| matches!(action, lomo_core::PlatformAction::WriteFromExchange { path, .. } if Path::new(path.as_str()).extension().is_some_and(|extension| extension.eq_ignore_ascii_case("md")))) && self.armed.swap(false, Ordering::SeqCst) {
                self.entered.send(()).test_ok("announce paused write");
                self.resume.lock().test_ok("resume lock").recv_timeout(Duration::from_secs(5)).test_ok("resume platform write");
            }
            self.executor
                .execute(&batch)
                .map(|result| lomo_native::result_to_ffi(&result))
                .map_err(EngineError::from)
        }
    }

    #[test]
    fn session_reads_do_not_queue_behind_a_paused_platform_write() {
        let session = open_direct_engine();
        let executor = FsPlatformActionExecutor::new(&session.exchange).test_ok("executor");
        executor
            .bind_root(
                CapabilityToken::parse("notes-root").test_ok("direct capability"),
                &session.workspace,
            )
            .test_ok("bind");
        let armed = Arc::new(AtomicBool::new(false));
        let (entered, entered_rx) = mpsc::channel();
        let (resume, resume_rx) = mpsc::channel();
        session
            .engine
            .open_workspace_session(
                Box::new(PausedBatchHost {
                    executor,
                    armed: Arc::clone(&armed),
                    entered,
                    resume: Mutex::new(resume_rx),
                }),
                "UTC".to_owned(),
                "yyyy_MM_dd".to_owned(),
                session.workspace.to_string_lossy().into_owned(),
            )
            .test_ok("open session");
        let created = session
            .engine
            .session_create_memo(
                SessionCreateMemoRequest {
                    operation_id: "create-concurrent".to_owned(),
                    relative_path: None,
                    time_token: None,
                    content: "published body".to_owned(),
                    expected_document_fingerprint: None,
                    pinned: false,
                    pending_promotes: Vec::new(),
                    chronology_epoch_ms: None,
                },
                crate::create_observer::returned_commit_only(),
            )
            .test_ok("seed memo");
        armed.store(true, Ordering::SeqCst);
        std::thread::scope(|scope| {
            let writer = scope.spawn(|| {
                session
                    .engine
                    .session_update_memo(lomo_native::SessionUpdateMemoRequest {
                        operation_id: "update-concurrent".to_owned(),
                        memo_id: created.memo_id.clone(),
                        content: "new body".to_owned(),
                        expected_document_fingerprint: created.file_fingerprint.clone(),
                        pending_promotes: Vec::new(),
                    })
                    .map_err(Box::new)
            });
            entered_rx
                .recv_timeout(Duration::from_secs(5))
                .test_ok("writer reached host barrier");
            let (read_tx, read_rx) = mpsc::channel();
            let engine = &session.engine;
            let memo_id = &created.memo_id;
            let reader = scope.spawn(move || {
                read_tx
                    .send(engine.get_memo(memo_id.clone()))
                    .test_ok("read result");
            });
            let observed = read_rx.recv_timeout(Duration::from_secs(1));
            resume.send(()).test_ok("release writer");
            writer
                .join()
                .test_ok("writer thread")
                .test_ok("write commit");
            reader.join().test_ok("reader thread");
            let snapshot = observed
                .test_ok("query must finish before host resumes")
                .test_ok("read snapshot")
                .unwrap_or_else(|| panic!("memo must exist"));
            assert_eq!(snapshot.body, "published body");
        });
    }

    #[derive(Debug)]
    enum Observed {
        Accepted(StoreMemoCommit),
        Withdrawn(StoreMemoCommit),
    }

    struct RecordingObserver(mpsc::Sender<Observed>);

    impl MemoCreateObserver for RecordingObserver {
        fn accepted(&self, publication: StoreMemoCommit) {
            self.0
                .send(Observed::Accepted(publication))
                .test_ok("record acceptance");
        }

        fn withdrawn(&self, publication: StoreMemoCommit) {
            self.0
                .send(Observed::Withdrawn(publication))
                .test_ok("record withdrawal");
        }
    }

    fn create_request(operation: &str, expected: Option<String>) -> SessionCreateMemoRequest {
        SessionCreateMemoRequest {
            operation_id: operation.to_owned(),
            relative_path: None,
            time_token: None,
            content: format!("body of {operation}"),
            expected_document_fingerprint: expected,
            pinned: false,
            pending_promotes: Vec::new(),
            chronology_epoch_ms: None,
        }
    }

    /// Given a host that pauses the markdown write, when the create is accepted and its write
    /// is still paused, then the memo is queryable with its committed intent (not pending) and
    /// the final commit carries a later revision without any withdrawal.
    #[test]
    fn create_acceptance_is_queryable_while_the_markdown_write_is_paused() {
        let session = open_direct_engine();
        let executor = FsPlatformActionExecutor::new(&session.exchange).test_ok("executor");
        executor
            .bind_root(
                CapabilityToken::parse("notes-root").test_ok("direct capability"),
                &session.workspace,
            )
            .test_ok("bind");
        let armed = Arc::new(AtomicBool::new(true));
        let (entered, entered_rx) = mpsc::channel();
        let (resume, resume_rx) = mpsc::channel();
        session
            .engine
            .open_workspace_session(
                Box::new(PausedBatchHost {
                    executor,
                    armed: Arc::clone(&armed),
                    entered,
                    resume: Mutex::new(resume_rx),
                }),
                "UTC".to_owned(),
                "yyyy_MM_dd".to_owned(),
                session.workspace.to_string_lossy().into_owned(),
            )
            .test_ok("open session");
        let (observed_tx, observed_rx) = mpsc::channel();
        std::thread::scope(|scope| {
            let engine = &session.engine;
            let writer = scope.spawn(move || {
                engine
                    .session_create_memo(
                        create_request("create-accepted", None),
                        Box::new(RecordingObserver(observed_tx)),
                    )
                    .map_err(Box::new)
            });
            let Observed::Accepted(accepted) = observed_rx
                .recv_timeout(Duration::from_secs(5))
                .test_ok("acceptance precedes the write")
            else {
                panic!("first publication must be the acceptance");
            };
            entered_rx
                .recv_timeout(Duration::from_secs(5))
                .test_ok("writer reached host barrier");
            let pending = engine
                .get_memo(accepted.memo_id.clone())
                .test_ok("read pending")
                .test_ok("accepted memo is queryable");
            resume.send(()).test_ok("release writer");
            let committed = writer.join().test_ok("writer thread").test_ok("commit");

            // Write-behind: the create's intent is committed before its markdown write
            // converges, so the row observed during the paused write is already durable.
            assert!(!pending.summary.is_pending);
            assert_eq!(pending.body, "body of create-accepted");
            assert_eq!(committed.memo_id, accepted.memo_id);
            assert!(committed.core_revision > accepted.core_revision);
            assert!(
                observed_rx.try_recv().is_err(),
                "a committed create is never withdrawn"
            );
        });
    }

    #[test]
    fn create_acceptance_failing_its_commit_reports_the_withdrawal() {
        let session = open_direct_engine();
        attach_posix_session(&session);
        let (observed_tx, observed_rx) = mpsc::channel();

        let failure = session
            .engine
            .session_create_memo(
                create_request("create-withdrawn", Some("0".repeat(64))),
                Box::new(RecordingObserver(observed_tx)),
            )
            .test_err("stale baseline must fail the commit");

        let Observed::Accepted(accepted) = observed_rx.try_recv().test_ok("acceptance") else {
            panic!("acceptance comes first");
        };
        let Observed::Withdrawn(withdrawn) = observed_rx.try_recv().test_ok("withdrawal") else {
            panic!("withdrawal follows a failed commit");
        };
        assert_eq!(withdrawn.memo_id, accepted.memo_id);
        assert!(withdrawn.core_revision > accepted.core_revision);
        assert_eq!(failure.code(), "stale_document_baseline");
        assert!(
            session
                .engine
                .get_memo(accepted.memo_id)
                .test_ok("read withdrawn")
                .is_none()
        );
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
                capability_token: "notes-root".to_owned(),
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
        let executor = FsPlatformActionExecutor::new(&session.exchange).test_ok("posix executor");
        let capability = CapabilityToken::parse("notes-root").test_ok("direct capability");
        executor
            .bind_root(capability, &session.workspace)
            .test_ok("bind workspace");
        session
            .engine
            .open_workspace_session(
                Box::new(PosixBatchHost { executor }),
                "UTC".to_owned(),
                "yyyy_MM_dd".to_owned(),
                session.workspace.to_string_lossy().into_owned(),
            )
            .test_ok("open session");
    }

    #[test]
    fn private_mount_ignores_external_controls_and_reindexes_documents() {
        let ctx = open_direct_engine();
        let legacy = ctx
            .workspace
            .parent()
            .test_ok("fixture parent")
            .join("old-app-state");
        let executor =
            Arc::new(FsPlatformActionExecutor::new(&ctx.exchange).test_ok("legacy executor"));
        let capability = CapabilityToken::parse("notes-root").test_ok("capability");
        executor
            .bind_root(capability.clone(), &ctx.workspace)
            .test_ok("legacy bind");
        let session = lomo_application::WorkspaceSession::open(
            lomo_application::WorkspaceSessionConfig {
                capability,
                root_id: lomo_workspace::WorkspaceRootId::Notes,
                workspace_generation: lomo_workspace::load_or_mint_workspace_generation(
                    &ctx.workspace,
                )
                .test_ok("old generation"),
                time_zone: "UTC".to_owned(),
                date_format: lomo_application::calendar::DateFormat::default(),
                state_dir: legacy.join("state"),
                cache_dir: legacy.join("cache"),
                runtime_dir: legacy.join("runtime"),
                exchange_dir: ctx.exchange.clone(),
                media_stage_root: ctx.workspace.clone(),
            },
            executor,
        )
        .test_ok("legacy session");
        let created = session
            .create_memo(lomo_application::CreateMemoRequest {
                operation_id: lomo_core::OperationId::parse("legacy-create").test_ok("operation"),
                relative_path: Some(
                    lomo_core::RelativeWorkspacePath::parse("2026_11_08.md").test_ok("path"),
                ),
                time_token: Some("12:00:00".to_owned()),
                content: "retain my history".to_owned(),
                expected_document_fingerprint: None,
                pinned: true,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .test_ok("old memo");
        session.settle_workspace().test_ok("old writes converge");
        let id = created.memo_id.as_str().to_owned();
        drop(session);
        attach_posix_session(&ctx);
        assert!(
            ctx.engine
                .get_memo(id)
                .test_ok("discarded old identity")
                .is_none()
        );
        let outcome = ctx
            .engine
            .session_search(SessionSearchRequest {
                filters: lomo_native::StoreMemoFilters::default(),
                query_epoch: 1,
                mode: SessionSearchMode::Fulltext,
                text: "retain my history".to_owned(),
                cursor: None,
                page_size: 20,
            })
            .test_ok("reindex user documents");
        let SessionSearchOutcome::Ready { page } = outcome else {
            panic!("search must be ready");
        };
        assert_eq!(page.items.len(), 1);
        assert!(
            !page
                .items
                .first()
                .test_ok("reindexed memo")
                .summary
                .is_pinned,
            "new private state starts without the old pin"
        );
        assert!(
            ctx.workspace.join(".lomo").is_dir(),
            "reset never deletes the user's source tree"
        );
    }

    #[test]
    fn existing_plain_dot_trash_markdown_remains_recoverable_after_private_state_reset() {
        let session = open_direct_engine();
        fs::create_dir_all(session.workspace.join(".trash")).test_ok("old trash directory");
        fs::write(
            session.workspace.join(".trash/2026_11_08.md"),
            "- 12:00:00\nold deleted memo\n",
        )
        .test_ok("old trash Markdown");
        attach_posix_session(&session);
        let outcome = session
            .engine
            .session_search(SessionSearchRequest {
                filters: lomo_native::StoreMemoFilters {
                    trash_only: true,
                    include_trash: true,
                    ..lomo_native::StoreMemoFilters::default()
                },
                query_epoch: 1,
                mode: SessionSearchMode::Fulltext,
                text: "old deleted memo".to_owned(),
                cursor: None,
                page_size: 20,
            })
            .test_ok("trash query");
        let SessionSearchOutcome::Ready { page } = outcome else {
            panic!("trash query must be ready");
        };
        assert_eq!(page.items.len(), 1);
        session
            .engine
            .session_restore_memo(lomo_native::SessionRestoreRequest {
                operation_id: "restore-plain-trash".to_owned(),
                memo_id: page
                    .items
                    .first()
                    .test_ok("trash memo")
                    .summary
                    .memo_id
                    .clone(),
            })
            .test_ok("restore old Markdown");
        session
            .engine
            .session_settle_workspace()
            .test_ok("restore converges");
        assert!(
            fs::read_to_string(session.workspace.join("2026_11_08.md"))
                .test_ok("restored main document")
                .contains("old deleted memo")
        );
    }

    #[test]
    fn private_records_do_not_materialize_in_the_selected_workspace() {
        let session = open_direct_engine();
        attach_posix_session(&session);
        assert!(
            !session.workspace.join(".lomo").exists(),
            "control records belong to the app-private session"
        );
    }

    #[test]
    fn soft_delete_keeps_readable_markdown_in_dot_trash_and_restore_removes_it() {
        let session = open_direct_engine();
        fs::create_dir_all(session.workspace.join("images")).test_ok("image directory");
        fs::write(session.workspace.join("images/a.png"), b"attached image").test_ok("image file");
        attach_posix_session(&session);
        let commit = session
            .engine
            .session_create_memo(
                SessionCreateMemoRequest {
                    operation_id: "trash-markdown-create".to_owned(),
                    relative_path: None,
                    time_token: Some("12:00:00".to_owned()),
                    content: "recover this memo\n![image](images/a.png)".to_owned(),
                    expected_document_fingerprint: None,
                    pinned: false,
                    pending_promotes: Vec::new(),
                    chronology_epoch_ms: None,
                },
                crate::create_observer::returned_commit_only(),
            )
            .test_ok("create");
        let source_path = session
            .engine
            .get_memo(commit.memo_id.clone())
            .test_ok("created memo query")
            .test_ok("created memo")
            .summary
            .source_path;
        session
            .engine
            .session_delete_memo(lomo_native::SessionDeleteMemoRequest {
                operation_id: "trash-markdown-delete".to_owned(),
                memo_id: commit.memo_id.clone(),
                expected_document_fingerprint: commit.file_fingerprint,
            })
            .test_ok("delete");
        session
            .engine
            .session_settle_workspace()
            .test_ok("delete converges");
        let trash = session.workspace.join(
            lomo_workspace::trash_record_relative_path(&commit.memo_id, &source_path)
                .test_ok("trash path")
                .as_str(),
        );
        let bytes = fs::read_to_string(&trash).test_ok("read public trash Markdown");
        assert!(bytes.contains("recover this memo\n![image](images/a.png)"));
        session
            .engine
            .session_restore_memo(lomo_native::SessionRestoreRequest {
                operation_id: "trash-markdown-restore".to_owned(),
                memo_id: commit.memo_id,
            })
            .test_ok("restore");
        session
            .engine
            .session_settle_workspace()
            .test_ok("restore converges");
        assert!(
            !trash.exists(),
            "restoring removes its public trash document"
        );
        assert!(dated_markdown(&session.workspace).contains("recover this memo"));
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
            .session_create_memo(
                SessionCreateMemoRequest {
                    operation_id: "op-create-unopened".to_owned(),
                    relative_path: None,
                    time_token: None,
                    content: "orphan".to_owned(),
                    expected_document_fingerprint: None,
                    pinned: false,
                    pending_promotes: Vec::new(),
                    chronology_epoch_ms: None,
                },
                crate::create_observer::returned_commit_only(),
            )
            .test_err("create without session");
        assert_eq!(error.code(), "workspace_session_unavailable");
        assert!(
            !workspace_has_markdown(&session.workspace),
            "unopened session must not write markdown"
        );
    }

    #[test]
    fn session_open_binds_the_host_supplied_date_format_to_memo_filenames() {
        let session = open_direct_engine();
        let executor = FsPlatformActionExecutor::new(&session.exchange).test_ok("posix executor");
        executor
            .bind_root(
                CapabilityToken::parse("notes-root").test_ok("direct capability"),
                &session.workspace,
            )
            .test_ok("bind workspace");
        session
            .engine
            .open_workspace_session(
                Box::new(PosixBatchHost { executor }),
                "UTC".to_owned(),
                "yyyy-MM-dd".to_owned(),
                session.workspace.to_string_lossy().into_owned(),
            )
            .test_ok("open session with hyphen date format");
        let commit = session
            .engine
            .session_create_memo(
                SessionCreateMemoRequest {
                    operation_id: "op-create-hyphen-date".to_owned(),
                    relative_path: None,
                    time_token: Some("09:30:00".to_owned()),
                    content: "dated format wiring".to_owned(),
                    expected_document_fingerprint: None,
                    pinned: false,
                    pending_promotes: Vec::new(),
                    // 2026-05-23T12:00:00Z — a real instant the journal stamp resolves locally.
                    chronology_epoch_ms: Some(1_779_537_600_000),
                },
                crate::create_observer::returned_commit_only(),
            )
            .test_ok("create memo");
        let loaded = session
            .engine
            .get_memo(commit.memo_id)
            .test_ok("get memo")
            .test_ok("created memo");
        assert_eq!(
            loaded.summary.source_path, "2026-05-23.md",
            "the host token must reach WorkspaceSessionConfig; default() would mint 2026_05_23.md"
        );
    }

    #[test]
    fn session_open_rejects_unknown_date_format_tokens_without_installing_a_session() {
        let session = open_direct_engine();
        let executor = FsPlatformActionExecutor::new(&session.exchange).test_ok("posix executor");
        executor
            .bind_root(
                CapabilityToken::parse("notes-root").test_ok("direct capability"),
                &session.workspace,
            )
            .test_ok("bind workspace");
        let error = session
            .engine
            .open_workspace_session(
                Box::new(PosixBatchHost { executor }),
                "UTC".to_owned(),
                // A Java display-pattern token: never a storage format, must fail closed
                // rather than fall back to DateFormat::default().
                "dd/MM/yyyy".to_owned(),
                session.workspace.to_string_lossy().into_owned(),
            )
            .test_err("foreign date format token");
        assert_eq!(error.code(), "invalid_calendar_input");
        assert!(
            !workspace_has_markdown(&session.workspace),
            "a rejected token must not write markdown"
        );
        // Fail-closed means no session was installed: a valid token still opens cleanly.
        let executor = FsPlatformActionExecutor::new(&session.exchange).test_ok("retry executor");
        executor
            .bind_root(
                CapabilityToken::parse("notes-root").test_ok("direct capability"),
                &session.workspace,
            )
            .test_ok("rebind workspace");
        session
            .engine
            .open_workspace_session(
                Box::new(PosixBatchHost { executor }),
                "UTC".to_owned(),
                "yyyy_MM_dd".to_owned(),
                session.workspace.to_string_lossy().into_owned(),
            )
            .test_ok("valid token opens after rejection");
    }

    #[test]
    fn session_open_receipt_names_the_committed_projection_revision() {
        // P-27 contract: `open_workspace_session` resolves only after the initial rebuild
        // commits, and the receipt's `projection_revision` is that committed high-water mark —
        // the same clock a post-open query observes, never a pre-open or re-queried value.
        let session = open_direct_engine();
        let executor = FsPlatformActionExecutor::new(&session.exchange).test_ok("posix executor");
        executor
            .bind_root(
                CapabilityToken::parse("notes-root").test_ok("direct capability"),
                &session.workspace,
            )
            .test_ok("bind workspace");
        let opened = session
            .engine
            .open_workspace_session(
                Box::new(PosixBatchHost { executor }),
                "UTC".to_owned(),
                "yyyy_MM_dd".to_owned(),
                session.workspace.to_string_lossy().into_owned(),
            )
            .test_ok("open session");
        assert!(
            !opened.device_id.is_empty(),
            "the open receipt must carry the session device identity"
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
                None,
                false,
            )
            .test_ok("post-open query observes the installed session projection");
        assert_eq!(
            opened.projection_revision, page.high_water_revision,
            "the open receipt must name the projection the initial rebuild committed"
        );
    }

    #[test]
    fn session_create_writes_dated_markdown_with_csprng_identity_not_store_direct_sidecar() {
        let session = open_direct_engine();
        attach_posix_session(&session);
        let commit = session
            .engine
            .session_create_memo(
                SessionCreateMemoRequest {
                    operation_id: "op-create-great-wall".to_owned(),
                    relative_path: None,
                    time_token: Some("10:15:00".to_owned()),
                    content: "八达岭长城".to_owned(),
                    expected_document_fingerprint: None,
                    pinned: false,
                    pending_promotes: Vec::new(),
                    chronology_epoch_ms: None,
                },
                crate::create_observer::returned_commit_only(),
            )
            .test_ok("create memo");
        assert!(
            commit.memo_id.starts_with("m_") && commit.memo_id.len() == 34,
            "session must mint CSPRNG memo ids, got {}",
            commit.memo_id
        );
        session
            .engine
            .session_settle_workspace()
            .test_ok("committed writes converge");
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
            .get_memo(commit.memo_id.clone())
            .test_ok("get memo")
            .test_ok("created memo");
        assert_eq!(loaded.summary.memo_id, commit.memo_id);
        assert_eq!(loaded.body, "八达岭长城");
        assert!(
            Path::new(&loaded.summary.source_path)
                .extension()
                .is_some_and(|ext| ext.eq_ignore_ascii_case("md"))
                && !loaded.summary.source_path.starts_with("memos/"),
            "projection source must be the dated document, got {}",
            loaded.summary.source_path
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
                None,
                false,
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
            .session_create_memo(
                SessionCreateMemoRequest {
                    operation_id: "op-create-search-target".to_owned(),
                    relative_path: None,
                    time_token: Some("11:00:00".to_owned()),
                    content: "八达岭长城".to_owned(),
                    expected_document_fingerprint: None,
                    pinned: false,
                    pending_promotes: Vec::new(),
                    chronology_epoch_ms: None,
                },
                crate::create_observer::returned_commit_only(),
            )
            .test_ok("create searchable memo");
        let outcome = session
            .engine
            .session_search(SessionSearchRequest {
                filters: lomo_native::StoreMemoFilters::default(),
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
                .any(|hit| hit.summary.memo_id == commit.memo_id && hit.score > 0),
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
            .session_create_memo(
                SessionCreateMemoRequest {
                    operation_id: "not a valid id".to_owned(),
                    relative_path: None,
                    time_token: None,
                    content: "should not land".to_owned(),
                    expected_document_fingerprint: None,
                    pinned: false,
                    pending_promotes: Vec::new(),
                    chronology_epoch_ms: None,
                },
                crate::create_observer::returned_commit_only(),
            )
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
            .session_create_memo(
                SessionCreateMemoRequest {
                    operation_id: "op-create-task-list".to_owned(),
                    relative_path: None,
                    time_token: Some("12:00:00".to_owned()),
                    content: "- [ ] buy milk".to_owned(),
                    expected_document_fingerprint: None,
                    pinned: false,
                    pending_promotes: Vec::new(),
                    chronology_epoch_ms: None,
                },
                crate::create_observer::returned_commit_only(),
            )
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
