//! Behavior Contract
//! Capability: retain exact identity and operation semantics across real partial writes and scans.
//! Scenarios: completed creates replay without writing; a crash after the Markdown write resumes
//! the frozen identity plan; external reorder preserves exact block identities; ambiguous edits
//! create durable conflict evidence; unspecified create paths use dated journals.
//! Observable outcomes: actual source bytes, stable memo IDs, query bodies and .lomo records.
//! TDD proof: the initial implementation duplicates a partially written create, rewrites completed
//! creates, binds external reorders by position, and defaults to notes.md.
//! Journal budget scenarios: Given completed operations, When a new command commits, Then no
//! completed payload/receipt is read and journal metadata stays below 32 KiB for a 48 KiB memo.
//! Given a real delete followed by executor failure, When the same restore retries, Then its
//! durable operation witness completes the restore without duplicating Markdown.
//! Journal TDD proof: the added `committed_journal/new_commit/restore_after` tests fail against the
//! history scan and delete Boolean implementation; GREEN uses the same filtered contract command.
//! Epoch retirement scenarios: Given a committed operation, When the session closes its epoch,
//! Then a durable witness replaces the deleted receipt and a later retry of the same operation ID
//! fails with `operation_expired` instead of re-executing. Given two closed epochs, When a retry
//! names an operation from either, Then both still expire. Given a reopened epoch, When a new
//! operation ID arrives, Then it is admitted normally.
//! Epoch TDD proof: without the retirement witness the retry falls through to the new-command path
//! and fails with `identity_operation_mismatch`; GREEN returns `operation_expired`.
//! Excludes: UI, network providers, terminal rendering and Android lifecycle.

#[cfg(test)]
mod tests {
    use std::{
        fmt::Debug,
        fs,
        sync::{
            Arc,
            atomic::{AtomicBool, Ordering},
        },
    };

    use lomo_application::{
        CreateMemoRequest, DeleteMemoRequest, PinMemoRequest, PinPolicy, RestoreMemoRequest,
        UpdateMemoRequest, WorkspaceSession, WorkspaceSessionConfig,
    };
    use lomo_core::{
        ActionOutcome, ActionResult, CapabilityToken, ErrorCategory, LomoError, OperationId,
        PlatformAction, PlatformActionBatch, PlatformActionExecutor, PlatformBatchResult,
        RelativeWorkspacePath, RetryDisposition,
    };
    use lomo_platform_fs::PosixPlatformActionExecutor;
    use lomo_workspace::{
        MemoIdentityMap, WorkspaceRelativePath, WorkspaceRootId, memo_identity_record_path,
    };

    trait TestResult<T> {
        fn value(self) -> T;
    }

    impl<T, E: Debug> TestResult<T> for Result<T, E> {
        fn value(self) -> T {
            match self {
                Ok(value) => value,
                Err(error) => panic!("unexpected failure: {error:?}"),
            }
        }
    }

    struct Fixture {
        temp: tempfile::TempDir,
        config: WorkspaceSessionConfig,
        executor: Arc<PosixPlatformActionExecutor>,
    }

    impl Fixture {
        fn new() -> Self {
            let temp = tempfile::tempdir().value();
            fs::create_dir(temp.path().join("notes")).value();
            let capability = CapabilityToken::parse("notes").value();
            let config = WorkspaceSessionConfig {
                capability: capability.clone(),
                root_id: WorkspaceRootId::Notes,
                workspace_generation: lomo_workspace::WorkspaceGenerationId::mint().value(),
                time_zone: "UTC".to_owned(),
                date_format: lomo_application::calendar::DateFormat::default(),
                state_dir: temp.path().join("state"),
                cache_dir: temp.path().join("cache"),
                runtime_dir: temp.path().join("runtime"),
                exchange_dir: temp.path().join("exchange"),
            };
            let executor = Arc::new(PosixPlatformActionExecutor::new(&config.exchange_dir).value());
            executor
                .bind_root(capability, temp.path().join("notes"))
                .value();
            Self {
                temp,
                config,
                executor,
            }
        }

        fn open(&self) -> WorkspaceSession {
            let executor = Arc::clone(&self.executor);
            WorkspaceSession::open(self.config.clone(), executor).value()
        }

        fn request(op: &str, content: &str) -> CreateMemoRequest {
            CreateMemoRequest {
                operation_id: OperationId::parse(op).value(),
                relative_path: Some(RelativeWorkspacePath::parse("2026_09_09.md").value()),
                time_token: Some("09:00:00".to_owned()),
                content: content.to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            }
        }

        fn path(&self) -> std::path::PathBuf {
            self.temp.path().join("notes/2026_09_09.md")
        }
    }

    #[test]
    fn committed_create_replay_returns_the_same_id_without_a_second_write() {
        let fixture = Fixture::new();
        let request = Fixture::request("once", "one memo");
        let session = fixture.open();
        let first = session.create_memo(request.clone()).value();
        let original = fs::read(fixture.path()).value();
        let replay = session.create_memo(request).value();
        assert_eq!(replay.memo_id, first.memo_id);
        assert_eq!(
            replay.commit_result.event_sequence,
            first.commit_result.event_sequence
        );
        assert_eq!(fs::read(fixture.path()).value(), original);
    }

    #[test]
    fn command_json_cannot_construct_invalid_body_time_or_edit_baseline() {
        let create = Fixture::request("typed-create", "valid body");
        let valid = serde_json::to_value(&create).value();
        let round_trip: CreateMemoRequest = serde_json::from_value(valid.clone()).value();
        assert_eq!(round_trip, create);
        for (key, invalid) in [
            (
                "content",
                serde_json::json!("x".repeat(lomo_workspace::MAX_EDITABLE_MEMO_UTF8_CHARS + 1)),
            ),
            ("time_token", serde_json::json!("99:99")),
            (
                "expected_document_fingerprint",
                serde_json::json!("not-a-fingerprint"),
            ),
        ] {
            let mut value = valid.clone();
            value
                .as_object_mut()
                .unwrap_or_else(|| panic!("request JSON object"))
                .insert(key.to_owned(), invalid);
            if let Ok(request) = serde_json::from_value::<CreateMemoRequest>(value) {
                panic!("invalid {key} decoded as {request:?}");
            }
        }
    }

    fn journal_files(root: &std::path::Path) -> Vec<std::path::PathBuf> {
        let mut directories = vec![root.to_owned()];
        let mut files = Vec::new();
        while let Some(directory) = directories.pop() {
            for entry in fs::read_dir(directory).value() {
                let path = entry.value().path();
                if path.is_dir() {
                    directories.push(path);
                } else {
                    files.push(path);
                }
            }
        }
        files
    }

    #[test]
    fn committed_journal_metadata_is_independent_of_historical_body_bytes() {
        let fixture = Fixture::new();
        let session = fixture.open();
        session
            .create_memo(Fixture::request("large", &"x".repeat(48 * 1024)))
            .value();
        let bytes: u64 = journal_files(&fixture.config.state_dir.join("intents"))
            .iter()
            .map(|path| fs::metadata(path).value().len())
            .sum();
        assert!(
            bytes < 32 * 1024,
            "committed metadata retained {bytes} bytes of file payload"
        );
    }

    #[test]
    fn new_commit_does_not_read_an_unrelated_completed_receipt() {
        let fixture = Fixture::new();
        let session = fixture.open();
        session
            .create_memo(Fixture::request("old", "first"))
            .value();
        let receipt = journal_files(&fixture.config.state_dir.join("intents"))
            .into_iter()
            .find(|path| path.file_name().is_some_and(|name| name == "old.rec"))
            .unwrap_or_else(|| panic!("completed receipt missing"));
        fs::write(&receipt, b"corrupt historical evidence").value();
        let created = session
            .create_memo(Fixture::request("new", "second"))
            .value();
        assert!(created.commit_result.core_revision > 0);
        assert_eq!(fs::read(receipt).value(), b"corrupt historical evidence");
    }

    struct FailAfterDelete {
        executor: Arc<PosixPlatformActionExecutor>,
        failed: AtomicBool,
    }

    impl PlatformActionExecutor for FailAfterDelete {
        fn execute(&self, batch: &PlatformActionBatch) -> Result<PlatformBatchResult, LomoError> {
            let result = self.executor.execute(batch)?;
            if batch
                .actions()
                .iter()
                .any(|action| matches!(action, PlatformAction::Delete { .. }))
                && !self.failed.swap(true, Ordering::SeqCst)
            {
                return Err(lomo_application::error::storage(
                    "injected_after_delete",
                    "the delete reached disk before the host disconnected",
                ));
            }
            Ok(result)
        }
    }

    #[test]
    fn restore_after_a_real_delete_failure_replays_without_duplicate_content() {
        let fixture = Fixture::new();
        let executor = Arc::new(FailAfterDelete {
            executor: Arc::clone(&fixture.executor),
            failed: AtomicBool::new(false),
        });
        let session = WorkspaceSession::open(fixture.config.clone(), executor).value();
        let created = session
            .create_memo(Fixture::request("create", "restored exactly once"))
            .value();
        session
            .delete_memo(DeleteMemoRequest {
                operation_id: OperationId::parse("delete").value(),
                memo_id: created.memo_id.clone(),
                expected_document_fingerprint: created.commit_result.file_fingerprint,
                trashed_at_ms: None,
            })
            .value();
        let request = RestoreMemoRequest {
            operation_id: OperationId::parse("restore").value(),
            memo_id: created.memo_id.clone(),
        };
        let Err(error) = session.restore_memo(&request) else {
            panic!("injected delete failure must be visible");
        };
        assert_eq!(error.code(), "injected_after_delete");
        session.restore_memo(&request).value();
        assert_eq!(
            fs::read_to_string(fixture.path())
                .value()
                .matches("restored exactly once")
                .count(),
            1
        );
        assert!(session.get_memo(&created.memo_id).value().is_some());
    }

    struct FailIdentityWrite {
        executor: Arc<PosixPlatformActionExecutor>,
        failed: AtomicBool,
    }

    impl PlatformActionExecutor for FailIdentityWrite {
        fn execute(&self, batch: &PlatformActionBatch) -> Result<PlatformBatchResult, LomoError> {
            let mut results = Vec::new();
            for action in batch.actions() {
                let target = matches!(action, PlatformAction::WriteFromExchange { path, .. }
                    if path.as_str().starts_with(".lomo/identity/"));
                if target && !self.failed.swap(true, Ordering::SeqCst) {
                    let failure = LomoError::from_platform_boundary(
                        ErrorCategory::Storage,
                        "injected_identity_failure",
                        RetryDisposition::Transient,
                        None,
                        None,
                        "the preceding document write really reached the filesystem",
                    )?;
                    results.push(ActionResult::new(
                        action.id().clone(),
                        ActionOutcome::Failed(failure),
                    ));
                    break;
                }
                let result = self.executor.execute_action(action);
                let failed = matches!(result.outcome(), ActionOutcome::Failed(_));
                results.push(result);
                if failed {
                    break;
                }
            }
            Ok(PlatformBatchResult::new(
                batch.schema_version(),
                batch.job_id().clone(),
                batch.batch_id().clone(),
                batch.attempt(),
                results,
            ))
        }
    }

    #[test]
    fn retry_after_a_real_document_write_completes_the_original_frozen_plan() {
        let fixture = Fixture::new();
        let executor = Arc::new(FailIdentityWrite {
            executor: Arc::clone(&fixture.executor),
            failed: AtomicBool::new(false),
        });
        let session = WorkspaceSession::open(fixture.config.clone(), executor).value();
        let request = Fixture::request("partial", "saved once");
        let Err(failure) = session.create_memo(request.clone()) else {
            panic!("the injected identity write must fail");
        };
        assert_eq!(failure.code(), "injected_identity_failure");
        assert_eq!(
            fs::read_to_string(fixture.path()).value(),
            "- 09:00:00\nsaved once\n"
        );
        drop(session);
        let reopened = fixture.open();
        let committed = reopened.create_memo(request).value();
        let body = fs::read_to_string(fixture.path()).value();
        assert_eq!(body.matches("saved once").count(), 1);
        assert_eq!(
            reopened
                .get_memo(&committed.memo_id)
                .value()
                .map(|memo| memo.body),
            Some("saved once".to_owned())
        );
    }

    #[test]
    fn external_reorder_keeps_exact_block_identity_in_the_rebuilt_projection() {
        let fixture = Fixture::new();
        let session = fixture.open();
        let first = session
            .create_memo(Fixture::request("first", "first body"))
            .value();
        let second = session
            .create_memo(Fixture::request("second", "second body"))
            .value();
        let source =
            lomo_workspace::SourceBytes::try_from_bytes(fs::read(fixture.path()).value()).value();
        let document = lomo_workspace::parse_workspace_document(&source, "2026_09_09").value();
        let reversed = document
            .memos()
            .iter()
            .rev()
            .map(|memo| source.slice(memo.memo_span()).value())
            .collect::<String>();
        fs::write(fixture.path(), reversed).value();
        session.rebuild_projection().value();
        assert_eq!(
            session
                .get_memo(&first.memo_id)
                .value()
                .map(|memo| memo.body),
            Some("first body".to_owned())
        );
        assert_eq!(
            session
                .get_memo(&second.memo_id)
                .value()
                .map(|memo| memo.body),
            Some("second body".to_owned())
        );
    }

    #[test]
    fn ambiguous_external_edits_publish_identity_conflicts_instead_of_positional_guesses() {
        let fixture = Fixture::new();
        let session = fixture.open();
        session
            .create_memo(Fixture::request("first", "same"))
            .value();
        session
            .create_memo(Fixture::request("second", "same"))
            .value();
        fs::write(
            fixture.path(),
            "<!-- external -->\n- 09:00:00\nsame\n- 09:00:00\nsame\n",
        )
        .value();
        // A rebuild may expose recovery as an error, but must preserve inspectable durable evidence.
        let result = session.rebuild_projection();
        if let Err(error) = result {
            assert_eq!(error.category(), ErrorCategory::Conflict);
        }
        let record_path = memo_identity_record_path(
            WorkspaceRootId::Notes,
            &WorkspaceRelativePath::parse("2026_09_09.md").value(),
        )
        .value();
        let map = MemoIdentityMap::decode(
            &fs::read(fixture.temp.path().join("notes").join(record_path.as_str())).value(),
        )
        .value();
        assert!(
            !map.conflicts().is_empty(),
            "external ambiguity must be recorded in .lomo"
        );
    }

    #[test]
    fn default_create_uses_a_date_filename_and_a_real_clock_time() {
        let fixture = Fixture::new();
        let mut request = Fixture::request("defaults", "dated memo");
        request.relative_path = None;
        request.time_token = None;
        let session = fixture.open();
        let created = session.create_memo(request).value();
        let memo = session
            .get_memo(&created.memo_id)
            .value()
            .unwrap_or_else(|| panic!("created memo must be projected"));
        let stem = memo
            .source_path
            .strip_suffix(".md")
            .unwrap_or_else(|| panic!("Markdown extension"));
        let parts: Vec<_> = stem.split('_').map(str::len).collect();
        assert_eq!(parts, vec![4, 2, 2]);
        assert!(!fixture.temp.path().join("notes/notes.md").exists());
    }

    #[test]
    fn empty_creation_cancels_without_writing_workspace_files() {
        let fixture = Fixture::new();
        let session = fixture.open();
        let Err(error) = session.create_memo(Fixture::request("empty", " \t\r\n")) else {
            panic!("empty content is a cancellation");
        };
        assert_eq!(error.category(), ErrorCategory::Cancelled);
        assert_eq!(
            fs::read_dir(fixture.temp.path().join("notes"))
                .value()
                .count(),
            0
        );
    }

    #[test]
    fn memo_commands_cannot_write_metadata_git_control_or_non_markdown_paths() {
        let fixture = Fixture::new();
        let session = fixture.open();
        for target in [".lomo/forbidden.md", ".git/config.md", "notes.txt"] {
            let mut request = Fixture::request("invalid-document", "body");
            request.relative_path = Some(RelativeWorkspacePath::parse(target).value());
            let Err(error) = session.create_memo(request) else {
                panic!("invalid document target was written: {target}");
            };
            assert_eq!(error.code(), "invalid_memo_document_path");
            assert!(!fixture.temp.path().join("notes").join(target).exists());
        }
        assert_eq!(
            fs::read_dir(fixture.temp.path().join("notes"))
                .value()
                .count(),
            0
        );
    }

    #[test]
    fn memo_chronology_uses_the_explicit_workspace_time_zone() {
        let mut fixture = Fixture::new();
        fixture.config.time_zone = "Asia/Shanghai".to_owned();
        let session = fixture.open();
        let created = session
            .create_memo(Fixture::request("zone", "local morning"))
            .value();
        let memo = session
            .get_memo(&created.memo_id)
            .value()
            .unwrap_or_else(|| panic!("created memo"));
        assert_eq!(memo.created_at_ms, 1_788_915_600_000);
    }

    #[test]
    fn configured_date_format_is_applied_by_the_shared_create_service() {
        let mut fixture = Fixture::new();
        fixture.config.date_format = lomo_application::calendar::DateFormat::MmDdYyyyHyphen;
        let session = fixture.open();
        let mut request = Fixture::request("format", "formatted filename");
        request.relative_path = None;
        let created = session.create_memo(request).value();
        let memo = session
            .get_memo(&created.memo_id)
            .value()
            .unwrap_or_else(|| panic!("created memo"));
        let stem = memo
            .source_path
            .strip_suffix(".md")
            .unwrap_or_else(|| panic!("Markdown filename"));
        assert_eq!(
            stem.split('-').map(str::len).collect::<Vec<_>>(),
            vec![2, 2, 4]
        );
    }

    #[test]
    fn opening_a_cold_workspace_indexes_existing_notes_without_client_orchestration() {
        let fixture = Fixture::new();
        fs::write(fixture.path(), "- 09:00:00\nexisting note\n").value();
        let session = fixture.open();
        let page = session
            .list_memos(&lomo_store::MemoQuery {
                search_text: None,
                filters: lomo_store::MemoFilters::default(),
                sort: lomo_store::MemoSort::default(),
            })
            .value();
        assert_eq!(page.items.len(), 1);
        let id = lomo_workspace::MemoId::parse(
            &page.items.first().unwrap_or_else(|| panic!("memo")).memo_id,
        )
        .value();
        update(&session, &id, "edit-import", "edited existing note");
        assert_eq!(
            session.get_memo(&id).value().map(|memo| memo.body),
            Some("edited existing note".to_owned())
        );
    }

    #[test]
    fn cold_cache_recovers_a_partially_written_update_before_publishing_ready() {
        let fixture = Fixture::new();
        let initial = fixture.open();
        let created = initial
            .create_memo(Fixture::request("create", "before edit"))
            .value();
        drop(initial);
        let executor = Arc::new(FailIdentityWrite {
            executor: Arc::clone(&fixture.executor),
            failed: AtomicBool::new(false),
        });
        let session = WorkspaceSession::open(fixture.config.clone(), executor).value();
        let before = session
            .get_memo(&created.memo_id)
            .value()
            .unwrap_or_else(|| panic!("memo"));
        let request = UpdateMemoRequest {
            operation_id: OperationId::parse("partial-update").value(),
            memo_id: created.memo_id.clone(),
            content: "after edit".to_owned(),
            expected_document_fingerprint: before.file_fingerprint,
            pending_promotes: Vec::new(),
        };
        let Err(error) = session.update_memo(request.clone()) else {
            panic!("injected partial update");
        };
        assert_eq!(error.code(), "injected_identity_failure");
        assert_eq!(
            fs::read_to_string(fixture.path()).value(),
            "- 09:00:00\nafter edit\n"
        );
        drop(session);
        fs::remove_dir_all(&fixture.config.cache_dir).value();
        let restored = fixture.open();
        assert_eq!(
            restored
                .get_memo(&created.memo_id)
                .value()
                .map(|memo| memo.body),
            Some("after edit".to_owned())
        );
        let replay = restored.update_memo(request).value();
        assert_eq!(replay.commit_result.content_revision, 2);
        assert!(replay.event_sequence > created.commit_result.event_sequence);
    }

    fn update(
        session: &WorkspaceSession,
        memo: &lomo_workspace::MemoId,
        operation: &str,
        content: &str,
    ) -> lomo_application::UpdateMemoResult {
        let before = session
            .get_memo(memo)
            .value()
            .unwrap_or_else(|| panic!("active memo"));
        session
            .update_memo(UpdateMemoRequest {
                operation_id: OperationId::parse(operation).value(),
                memo_id: memo.clone(),
                content: content.to_owned(),
                expected_document_fingerprint: before.file_fingerprint,
                pending_promotes: Vec::new(),
            })
            .value()
    }

    #[test]
    fn history_uses_the_canonical_v2_head_and_immutable_parent_chain() {
        let fixture = Fixture::new();
        let session = fixture.open();
        let first = session
            .create_memo(Fixture::request("create", "version one"))
            .value();
        let paths = lomo_workspace::LomoPaths::for_workspace_with_layout(
            &fixture.temp.path().join("notes"),
            lomo_workspace::LomoLayoutVersion::V2,
        );
        let head_path = lomo_workspace::history_head_path(&paths, first.memo_id.as_str());
        let old_head: lomo_workspace::HistoryHead = serde_json::from_str(
            &lomo_workspace::decode_record(&fs::read(&head_path).value())
                .value()
                .payload
                .body_json,
        )
        .value();
        update(&session, &first.memo_id, "edit", "version two");
        let new_head: lomo_workspace::HistoryHead = serde_json::from_str(
            &lomo_workspace::decode_record(&fs::read(head_path).value())
                .value()
                .payload
                .body_json,
        )
        .value();
        let revision =
            lomo_workspace::read_history_revision(&paths, &new_head.head_revision_id).value();
        assert_eq!(revision.parent_ids, vec![old_head.head_revision_id]);
        assert_eq!(revision.generation, 2);
        assert_eq!(revision.content, "version two");
    }

    #[test]
    fn rebuild_restores_revision_before_accepting_the_next_edit() {
        let fixture = Fixture::new();
        let session = fixture.open();
        let created = session
            .create_memo(Fixture::request("create", "one"))
            .value();
        update(&session, &created.memo_id, "second", "two");
        session.rebuild_projection().value();
        let third = update(&session, &created.memo_id, "third", "three");
        assert_eq!(third.commit_result.content_revision, 3);
    }

    #[test]
    fn merged_history_preserves_both_revisions_at_the_same_generation() {
        let fixture = Fixture::new();
        let session = fixture.open();
        let created = session
            .create_memo(Fixture::request("create", "merged body"))
            .value();
        let paths = lomo_workspace::LomoPaths::for_workspace_with_layout(
            &fixture.temp.path().join("notes"),
            lomo_workspace::LomoLayoutVersion::V2,
        );
        let head: lomo_workspace::HistoryHead = serde_json::from_str(
            &lomo_workspace::decode_record(
                &fs::read(lomo_workspace::history_head_path(
                    &paths,
                    created.memo_id.as_str(),
                ))
                .value(),
            )
            .value()
            .payload
            .body_json,
        )
        .value();
        let root = lomo_workspace::read_history_revision(&paths, &head.head_revision_id).value();
        let branch = |content: &str| {
            lomo_workspace::HistoryRevisionV2::create(
                created.memo_id.as_str(),
                std::slice::from_ref(&root),
                content.to_owned(),
                lomo_workspace::SourceFingerprint::of_bytes(content.as_bytes()).as_str(),
                "",
                10_000,
            )
            .value()
        };
        let left = branch("left branch");
        let right = branch("right branch");
        let merged = lomo_workspace::HistoryRevisionV2::create(
            created.memo_id.as_str(),
            &[left.clone(), right.clone()],
            "merged body".to_owned(),
            lomo_workspace::SourceFingerprint::of_bytes(b"merged body").as_str(),
            "",
            20_000,
        )
        .value();
        for revision in [&left, &right, &merged] {
            lomo_workspace::write_history_revision(&paths, revision).value();
        }
        lomo_workspace::write_history_head(
            &paths,
            &lomo_workspace::HistoryHead {
                memo_id: created.memo_id.as_str().to_owned(),
                head_revision_id: merged.revision_id,
            },
        )
        .value();
        session.rebuild_projection().value();
        let store = lomo_store::Store::open_projection(&fixture.config.cache_dir).value();
        let page = store
            .list_memo_history(created.memo_id.as_str(), None, 20)
            .value();
        let bodies: Vec<_> = page
            .items
            .iter()
            .map(|revision| revision.content.as_str())
            .collect();
        assert_eq!(bodies.len(), 4);
        assert!(bodies.contains(&"left branch"));
        assert!(bodies.contains(&"right branch"));
    }

    #[test]
    fn a_workspace_unpin_overrides_another_devices_stale_cached_pin() {
        let fixture = Fixture::new();
        let first_device = fixture.open();
        let mut request = Fixture::request("create", "shared pin state");
        request.pinned = true;
        let created = first_device.create_memo(request).value();
        let mut second_config = fixture.config.clone();
        second_config.cache_dir = fixture.temp.path().join("second/cache");
        second_config.state_dir = fixture.temp.path().join("second/state");
        let executor = Arc::clone(&fixture.executor);
        let second_device = WorkspaceSession::open(second_config, executor).value();
        second_device.rebuild_projection().value();
        second_device
            .pin_memo(
                PinMemoRequest::new(
                    OperationId::parse("unpin").value(),
                    created.memo_id.clone(),
                    PinPolicy::Unpinned,
                )
                .value(),
            )
            .value();
        first_device.rebuild_projection().value();
        assert_eq!(
            first_device
                .get_memo(&created.memo_id)
                .value()
                .map(|memo| memo.is_pinned),
            Some(false)
        );
    }

    #[test]
    fn unknown_layout_version_is_rejected_without_mutating_the_workspace() {
        let fixture = Fixture::new();
        let session = fixture.open();
        let bytes = lomo_workspace::encode_record(&lomo_workspace::LomoPayload {
            kind: lomo_workspace::LomoRecordKind::LayoutHead,
            record_id: "layout".to_owned(),
            body_json: r#"{"layout":"V999"}"#.to_owned(),
        })
        .value();
        fs::create_dir_all(fixture.temp.path().join("notes/.lomo")).value();
        let head = fixture.temp.path().join("notes/.lomo/layout_head.rec");
        fs::write(&head, &bytes).value();
        let Err(error) = session.rebuild_projection() else {
            panic!("unknown newer layout must reject downgrade");
        };
        assert_eq!(error.code(), "unsupported_workspace_layout");
        assert_eq!(fs::read(head).value(), bytes);
    }

    #[test]
    fn stale_edit_evidence_contains_original_current_and_draft_bytes() {
        let fixture = Fixture::new();
        let session = fixture.open();
        let created = session
            .create_memo(Fixture::request("create", "original body"))
            .value();
        let baseline = fs::read(fixture.path()).value();
        let current = b"- 09:00:00\r\nexternal edit\r\n";
        fs::write(fixture.path(), current).value();
        let Err(error) = session.update_memo(UpdateMemoRequest {
            operation_id: OperationId::parse("conflict").value(),
            memo_id: created.memo_id,
            content: "editor draft".to_owned(),
            expected_document_fingerprint: created.commit_result.file_fingerprint,
            pending_promotes: Vec::new(),
        }) else {
            panic!("stale edit must fail");
        };
        assert_eq!(error.category(), ErrorCategory::Conflict);
        let evidence: serde_json::Value = serde_json::from_slice(
            &fs::read(fixture.config.state_dir.join("drafts/conflict.json")).value(),
        )
        .value();
        assert_eq!(
            evidence.get("baseline_bytes"),
            Some(&serde_json::json!(baseline))
        );
        assert_eq!(
            evidence.get("current_bytes"),
            Some(&serde_json::json!(current.as_slice()))
        );
        assert_eq!(
            evidence.get("draft_content"),
            Some(&serde_json::json!("editor draft"))
        );
        assert_eq!(fs::read(fixture.path()).value(), current);
    }

    #[test]
    fn sealing_a_session_retires_the_receipt_and_expires_a_retry() {
        let fixture = Fixture::new();
        let session = fixture.open();
        let request = Fixture::request("sealed-op", "only memo");
        session.create_memo(request.clone()).value();
        let committed = fixture
            .config
            .state_dir
            .join("intents/committed/sealed-op.rec");
        assert!(
            committed.exists(),
            "a committed receipt must exist before seal"
        );
        let before = fs::read(fixture.path()).value();
        assert!(session.seal().value());
        assert!(
            !committed.exists(),
            "the retirement witness replaces the deleted receipt"
        );
        assert!(
            fixture
                .config
                .state_dir
                .join("intents/lifecycle.rec")
                .exists(),
            "the durable epoch witness must outlive the receipt"
        );
        drop(session);

        let reopened = fixture.open();
        assert!(reopened.seal().value());
        let Err(error) = reopened.create_memo(request) else {
            panic!("a retired operation must not be re-executed");
        };
        assert_eq!(error.code(), "operation_expired");
        assert_eq!(
            fs::read(fixture.path()).value(),
            before,
            "an expired retry must not rewrite the workspace"
        );
    }

    #[test]
    fn a_reopened_epoch_accepts_new_operations_after_retirement() {
        let fixture = Fixture::new();
        let session = fixture.open();
        session
            .create_memo(Fixture::request("epoch-one", "first memo"))
            .value();
        assert!(session.seal().value());
        drop(session);

        let reopened = fixture.open();
        let created = reopened
            .create_memo(Fixture::request("epoch-two", "second memo"))
            .value();
        assert!(created.commit_result.core_revision > 0);
        let body = fs::read_to_string(fixture.path()).value();
        assert!(body.contains("first memo"));
        assert!(body.contains("second memo"));
    }

    #[test]
    fn retirement_witnesses_are_retained_across_the_retry_window() {
        let fixture = Fixture::new();
        let session = fixture.open();
        let first = Fixture::request("epoch-one-op", "first memo");
        session.create_memo(first.clone()).value();
        assert!(session.seal().value());
        session
            .create_memo(Fixture::request("epoch-two-op", "second memo"))
            .value();
        assert!(session.seal().value());
        drop(session);

        let reopened = fixture.open();
        for request in [first, Fixture::request("epoch-two-op", "second memo")] {
            let Err(error) = reopened.create_memo(request) else {
                panic!("a witnessed retirement must still expire after a later epoch");
            };
            assert_eq!(error.code(), "operation_expired");
        }
    }
}
