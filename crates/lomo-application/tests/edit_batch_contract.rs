//! Behavior Contract:
//! Capability: recover consecutive edits with one document write while preserving logical edits;
//! owning layer: lomo-application; priority: P0.
//! Scenarios:
//! - Given two genuine frozen edit records in reverse lexical ID order, when reopening from the
//!   original workspace, then recovery follows publication clocks and writes the document once.
//! - Given a live burst, when acknowledged before settlement, then every intermediate revision is queryable.
//! - Given a foreign write at the next physical read, then conflict preserves its bytes.
//! - Given a failure after the coalesced document lands, when reopening again, then its final
//!   bytes are accepted and both edits' history and replay receipts survive.
//!
//! Observable outcomes: actual applied document-write count, final body, three history revisions,
//! two idempotent receipts, and preserved pending records after the injected failure.
//! TDD proof: reported-bugs/edit-batch-red.log rejects the later lexical-first baseline;
//! edit-batch-green.log verifies one physical write through the production recovery path.
//! Excludes: Android SAF latency and wall-clock scheduling assertions.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "fixture failures stop the recovery contract"
)]
mod tests {
    use lomo_application::{CreateMemoRequest, WorkspaceSession, WorkspaceSessionConfig};
    use lomo_core::{
        ActionOutcome, ActionResult, LomoError, PlatformAction, PlatformActionBatch,
        PlatformActionExecutor, PlatformBatchResult,
    };
    use lomo_core::{CapabilityToken, OperationId, RelativeWorkspacePath};
    use lomo_platform_fs::FsPlatformActionExecutor;
    use lomo_workspace::{SessionStateLayout, WorkspaceRootId};
    use std::{
        collections::BTreeMap,
        fs,
        path::{Path, PathBuf},
        sync::{
            Arc, Mutex,
            atomic::{AtomicUsize, Ordering},
        },
    };
    use tempfile::tempdir;
    struct TestContext {
        _workspace_dir: tempfile::TempDir,
        _state_dir: tempfile::TempDir,
        _cache_dir: tempfile::TempDir,
        _runtime_dir: tempfile::TempDir,
        _exchange_dir: tempfile::TempDir,
        workspace_path: PathBuf,
        session: WorkspaceSession,
        executor: Arc<ControlledExecutor>,
        config: WorkspaceSessionConfig,
    }

    fn setup_test_context() -> TestContext {
        let workspace_dir = tempdir().expect("workspace dir");
        let state_dir = tempdir().expect("state dir");
        let cache_dir = tempdir().expect("cache dir");
        let runtime_dir = tempdir().expect("runtime dir");
        let exchange_dir = tempdir().expect("exchange dir");

        let executor = Arc::new(ControlledExecutor {
            inner: FsPlatformActionExecutor::new(exchange_dir.path()).expect("executor"),
            failure_path: Mutex::new(None),
            foreign_on_read: Mutex::new(None),
            workspace: workspace_dir.path().to_path_buf(),
            document_writes: AtomicUsize::new(0),
        });
        let capability = CapabilityToken::parse("test-notes-root").expect("capability token");
        executor
            .inner
            .bind_root(capability.clone(), workspace_dir.path())
            .expect("bind root");

        let config = WorkspaceSessionConfig {
            capability,
            root_id: WorkspaceRootId::Notes,
            workspace_generation: lomo_workspace::WorkspaceGenerationId::mint()
                .expect("workspace generation"),
            time_zone: "UTC".to_owned(),
            date_format: lomo_application::calendar::DateFormat::default(),
            state_dir: state_dir.path().to_path_buf(),
            cache_dir: cache_dir.path().to_path_buf(),
            runtime_dir: runtime_dir.path().to_path_buf(),
            exchange_dir: exchange_dir.path().to_path_buf(),
            media_stage_root: exchange_dir.path().join("media-stage"),
        };

        let session =
            WorkspaceSession::open(config.clone(), Arc::<ControlledExecutor>::clone(&executor))
                .expect("open session");
        let workspace_path = workspace_dir.path().to_path_buf();

        TestContext {
            _workspace_dir: workspace_dir,
            _state_dir: state_dir,
            _cache_dir: cache_dir,
            _runtime_dir: runtime_dir,
            _exchange_dir: exchange_dir,
            workspace_path,
            session,
            executor,
            config,
        }
    }

    fn create(ctx: &TestContext, name: &str) -> lomo_application::CreateMemoResult {
        let committed = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse(name).expect("operation"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_11_04.md").expect("path")),
                time_token: Some("10:00:00".to_owned()),
                content: name.to_owned(),
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
        committed
    }

    struct ControlledExecutor {
        inner: FsPlatformActionExecutor,
        failure_path: Mutex<Option<String>>,
        foreign_on_read: Mutex<Option<Vec<u8>>>,
        workspace: PathBuf,
        document_writes: AtomicUsize,
    }
    impl PlatformActionExecutor for ControlledExecutor {
        fn execute(&self, batch: &PlatformActionBatch) -> Result<PlatformBatchResult, LomoError> {
            if batch.actions().iter().any(|action|matches!(action,PlatformAction::Read{path,..} if path.as_str()=="2026_11_04.md"))
                && let Some(bytes)=self.foreign_on_read.lock().expect("external write control").take() {
                    fs::write(self.workspace.join("2026_11_04.md"),bytes).map_err(|error|lomo_application::error::storage("fixture_external_write",error.to_string()))?;
            }
            if let Some(PlatformAction::WriteFromExchange { path, .. }) = batch.actions().first()
                && self
                    .failure_path
                    .lock()
                    .expect("failure control")
                    .as_ref()
                    .is_some_and(|needle| path.as_str().contains(needle))
            {
                return Ok(PlatformBatchResult::new(
                    batch.schema_version(),
                    batch.job_id().clone(),
                    batch.batch_id().clone(),
                    batch.attempt(),
                    vec![ActionResult::new(
                        batch.actions().first().expect("action").id().clone(),
                        ActionOutcome::Failed(lomo_application::error::storage(
                            "injected_batch_write",
                            "controlled write failure",
                        )),
                    )],
                ));
            }
            let result = self.inner.execute(batch)?;
            for (action, outcome) in batch.actions().iter().zip(result.action_results()) {
                if matches!(action, PlatformAction::WriteFromExchange { path, .. } if path.as_str()=="2026_11_04.md")
                    && matches!(outcome.outcome(), ActionOutcome::Applied(_))
                {
                    self.document_writes.fetch_add(1, Ordering::SeqCst);
                }
            }
            Ok(result)
        }
    }
    fn snapshot(root: &Path) -> BTreeMap<PathBuf, Vec<u8>> {
        fn visit(root: &Path, path: &Path, out: &mut BTreeMap<PathBuf, Vec<u8>>) {
            if !path.exists() {
                return;
            }
            for entry in fs::read_dir(path).expect("fixture directory") {
                let path = entry.expect("entry").path();
                if path.is_dir() {
                    visit(root, &path, out);
                } else {
                    out.insert(
                        path.strip_prefix(root).expect("relative").to_path_buf(),
                        fs::read(path).expect("file"),
                    );
                }
            }
        }
        let mut out = BTreeMap::new();
        visit(root, root, &mut out);
        out
    }
    fn restore(root: &Path, files: &BTreeMap<PathBuf, Vec<u8>>) {
        for (path, bytes) in files {
            let path = root.join(path);
            fs::create_dir_all(path.parent().expect("parent")).expect("directory");
            fs::write(path, bytes).expect("restore file");
        }
    }
    fn clear(root: &Path) {
        for entry in fs::read_dir(root).expect("fixture directory") {
            let path = entry.expect("entry").path();
            if path.is_dir() {
                fs::remove_dir_all(path).expect("remove fixture tree");
            } else {
                fs::remove_file(path).expect("remove fixture file");
            }
        }
    }
    #[test]
    fn consecutive_edits_recover_as_one_write_after_a_partial_batch_failure() {
        let ctx = setup_test_context();
        let created = create(&ctx, "initial-body");
        let original = snapshot(&ctx.workspace_path);
        let state = SessionStateLayout::for_state_dir(&ctx.config.state_dir);
        let mut pending = BTreeMap::new();
        let mut payloads = BTreeMap::new();
        let mut expected = created.commit_result.file_fingerprint;
        let mut attempts = Vec::new();
        for (operation, body) in [
            ("z-first-edit", "first edit"),
            ("a-second-edit", "second edit"),
        ] {
            *ctx.executor.failure_path.lock().expect("fault") = Some("2026_11_04.md".to_owned());
            let request = lomo_application::UpdateMemoRequest {
                operation_id: OperationId::parse(operation).expect("op"),
                memo_id: created.memo_id.clone(),
                content: body.to_owned(),
                expected_document_fingerprint: expected,
                pending_promotes: Vec::new(),
            };
            let result = ctx
                .session
                .update_memo(request.clone())
                .expect("commit edit");
            ctx.session
                .settle_workspace()
                .expect_err("controlled document failure");
            pending.extend(snapshot(&state.intents.join("pending")));
            payloads.extend(snapshot(&state.intent_payloads));
            *ctx.executor.failure_path.lock().expect("fault") = None;
            ctx.session
                .settle_workspace()
                .expect("converge fixture edit");
            expected = result.commit_result.file_fingerprint.clone();
            attempts.push((request, result));
        }
        drop(ctx.session);
        clear(&ctx.workspace_path);
        restore(&ctx.workspace_path, &original);
        clear(&ctx.config.cache_dir);
        restore(&state.intents.join("pending"), &pending);
        restore(&state.intent_payloads, &payloads);
        for (request, _) in &attempts {
            fs::remove_file(
                state
                    .intents
                    .join("committed")
                    .join(format!("{}.rec", request.operation_id.as_str())),
            )
            .expect("remove captured receipt");
        }
        ctx.executor.document_writes.store(0, Ordering::SeqCst);
        *ctx.executor.failure_path.lock().expect("fault") = Some(".lomo/identity".to_owned());
        let Err(failure) = WorkspaceSession::open(
            ctx.config.clone(),
            Arc::<ControlledExecutor>::clone(&ctx.executor),
        ) else {
            panic!("coalesced apply must be interrupted");
        };
        assert_eq!(failure.code(), "injected_batch_write");
        assert_eq!(ctx.executor.document_writes.load(Ordering::SeqCst), 1);
        assert_eq!(snapshot(&state.intents.join("pending")).len(), 2);
        *ctx.executor.failure_path.lock().expect("fault") = None;
        let recovered =
            WorkspaceSession::open(ctx.config, Arc::<ControlledExecutor>::clone(&ctx.executor))
                .expect("resume batch");
        assert_eq!(
            recovered
                .get_memo(&created.memo_id)
                .expect("memo")
                .expect("present")
                .body,
            "second edit"
        );
        assert_eq!(
            recovered
                .list_history(&created.memo_id, None, 10)
                .expect("history")
                .items
                .len(),
            3
        );
        for (request, result) in attempts {
            let replay = recovered.update_memo(request).expect("replay");
            assert!(replay.commit_result.idempotent_replay);
            assert_eq!(
                replay.commit_result.content_revision,
                result.commit_result.content_revision
            );
        }
        assert_eq!(ctx.executor.document_writes.load(Ordering::SeqCst), 1);
        assert!(snapshot(&state.intents.join("pending")).is_empty());
    }
    #[test]
    fn a_live_burst_publishes_each_revision_and_settles_the_last_document_version() {
        let ctx = setup_test_context();
        let created = create(&ctx, "burst-base");
        let mut fingerprint = created.commit_result.file_fingerprint;
        let mut attempts = Vec::new();
        for index in 0..32 {
            let request = lomo_application::UpdateMemoRequest {
                operation_id: OperationId::parse(&format!("live-edit-{index}")).expect("op"),
                memo_id: created.memo_id.clone(),
                content: format!("live revision {index}"),
                expected_document_fingerprint: fingerprint,
                pending_promotes: Vec::new(),
            };
            let result = ctx.session.update_memo(request.clone()).expect("live edit");
            assert_eq!(result.commit_result.content_revision, index + 2);
            assert_eq!(
                ctx.session
                    .get_memo(&created.memo_id)
                    .expect("read committed view")
                    .expect("memo")
                    .body,
                request.content
            );
            fingerprint = result.commit_result.file_fingerprint.clone();
            attempts.push((request, result));
        }
        ctx.session.settle_workspace().expect("settle burst");
        assert_eq!(
            ctx.session
                .write_convergence()
                .expect("convergence")
                .backlog,
            0
        );
        let bytes = fs::read_to_string(ctx.workspace_path.join("2026_11_04.md")).expect("document");
        assert!(bytes.contains("live revision 31"));
        assert!(!bytes.contains("live revision 30"));
        assert_eq!(
            ctx.session
                .list_history(&created.memo_id, None, 64)
                .expect("history")
                .items
                .len(),
            33
        );
        for (request, result) in attempts {
            let replay = ctx.session.update_memo(request).expect("replay each edit");
            assert!(replay.commit_result.idempotent_replay);
            assert_eq!(
                replay.commit_result.content_revision,
                result.commit_result.content_revision
            );
        }
    }
    #[test]
    fn an_external_change_at_the_next_physical_observation_survives_queued_edits() {
        let ctx = setup_test_context();
        let created = create(&ctx, "external-base");
        let first = ctx
            .session
            .update_memo(lomo_application::UpdateMemoRequest {
                operation_id: OperationId::parse("before-foreign").expect("op"),
                memo_id: created.memo_id.clone(),
                content: "first logical edit".to_owned(),
                expected_document_fingerprint: created.commit_result.file_fingerprint,
                pending_promotes: Vec::new(),
            })
            .expect("first edit");
        let foreign = b"- 10:00:00\nforeign content must survive\n".to_vec();
        *ctx.executor
            .foreign_on_read
            .lock()
            .expect("external control") = Some(foreign.clone());
        let next = ctx
            .session
            .update_memo(lomo_application::UpdateMemoRequest {
                operation_id: OperationId::parse("after-foreign").expect("op"),
                memo_id: created.memo_id,
                content: "must not overwrite foreign content".to_owned(),
                expected_document_fingerprint: first.commit_result.file_fingerprint,
                pending_promotes: Vec::new(),
            });
        let failure = match next {
            Err(error) => error,
            Ok(_) => ctx
                .session
                .settle_workspace()
                .expect_err("convergence must expose the external conflict"),
        };
        assert!(
            ["stale_document_baseline", "stale_transaction_baseline"].contains(&failure.code())
        );
        assert_eq!(
            fs::read(ctx.workspace_path.join("2026_11_04.md")).expect("preserved source"),
            foreign
        );
    }
}
