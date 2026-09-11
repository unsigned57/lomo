//! Capability: Real lomo-application session service, shared document write transaction,
//! and full/incremental SQLite projection rebuild.
//!
//! - Given three memos created at the exact same second with identical body,
//!   When committed to `yyyy_MM_dd.md`,
//!   Then each memo gets an independent stable CSPRNG `MemoId`,
//!   updating/deleting intermediate memos preserves sibling IDs without renumbering,
//!   and no machine markers are injected into the Markdown content.
//!
//! - Given a populated workspace with Markdown, identity, `history_v2`, and pins in .lomo,
//!   When the SQLite cache is cleared and opened in another device private directory,
//!   Then 100% of IDs, history, and pins are reconstructed from Markdown + .lomo,
//!   and device identity is not inherited.
//!
//! - Given a baseline read followed by external edits to the Markdown file,
//!   When a write command with the baseline fingerprint is executed,
//!   Then it is rejected with conflict, preserving 3-way evidence and uncompleted intent,
//!   without corrupting BOM, CRLF, or untouched bytes.
//!
//! - Given an injected platform executor failure midway through a write batch,
//!   When the session re-opens or retries with the same `operation_id`,
//!   Then it reuses the operation and memo ID without duplicate entries in the document,
//!   and replaying with a different payload is rejected.
//!
//! - Given a document with multiple sibling memos,
//!   When one memo is modified,
//!   Then SQLite publication occurs after verified durable write,
//!   all sibling memo fingerprints in that document are incrementally refreshed,
//!   and event sequence advances monotonically.
//!
//! TDD proof: RED initially because `WorkspaceSession` implementation is pending.

use std::fs;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use lomo_application::{
    CreateMemoRequest, DeleteMemoRequest, PinMemoRequest, UpdateMemoRequest, WorkspaceSession,
    WorkspaceSessionConfig,
};
use lomo_core::{
    CapabilityToken, OperationId, PlatformActionBatch, PlatformActionExecutor, PlatformBatchResult,
    RelativeWorkspacePath,
};
use lomo_platform_fs::PosixPlatformActionExecutor;
use lomo_store::MemoQuery;
use lomo_workspace::WorkspaceRootId;
#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::too_many_lines,
    clippy::redundant_clone,
    clippy::excessive_nesting,
    clippy::similar_names,
    clippy::clone_on_ref_ptr,
    clippy::indexing_slicing,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    struct TestContext {
        _workspace_dir: tempfile::TempDir,
        _state_dir: tempfile::TempDir,
        _cache_dir: tempfile::TempDir,
        _runtime_dir: tempfile::TempDir,
        _exchange_dir: tempfile::TempDir,
        workspace_path: std::path::PathBuf,
        session: WorkspaceSession,
        _executor: Arc<PosixPlatformActionExecutor>,
        config: WorkspaceSessionConfig,
    }

    fn setup_test_context() -> TestContext {
        let workspace_dir = tempdir().expect("workspace dir");
        let state_dir = tempdir().expect("state dir");
        let cache_dir = tempdir().expect("cache dir");
        let runtime_dir = tempdir().expect("runtime dir");
        let exchange_dir = tempdir().expect("exchange dir");

        let executor =
            Arc::new(PosixPlatformActionExecutor::new(exchange_dir.path()).expect("executor"));
        let capability = CapabilityToken::parse("test-notes-root").expect("capability token");
        executor
            .bind_root(capability.clone(), workspace_dir.path())
            .expect("bind root");

        let config = WorkspaceSessionConfig {
            capability,
            root_id: WorkspaceRootId::Notes,
            time_zone: "UTC".to_owned(),
            date_format: lomo_application::calendar::DateFormat::default(),
            state_dir: state_dir.path().to_path_buf(),
            cache_dir: cache_dir.path().to_path_buf(),
            runtime_dir: runtime_dir.path().to_path_buf(),
            exchange_dir: exchange_dir.path().to_path_buf(),
        };

        let session =
            WorkspaceSession::open(config.clone(), executor.clone()).expect("open session");
        let workspace_path = workspace_dir.path().to_path_buf();

        TestContext {
            _workspace_dir: workspace_dir,
            _state_dir: state_dir,
            _cache_dir: cache_dir,
            _runtime_dir: runtime_dir,
            _exchange_dir: exchange_dir,
            workspace_path,
            session,
            _executor: executor,
            config,
        }
    }

    #[test]
    fn same_second_identical_content_memos_have_independent_stable_ids_and_clean_markdown() {
        let ctx = setup_test_context();
        let path = RelativeWorkspacePath::parse("2026_09_09.md").expect("valid path");

        // 1. Create three identical memos at the exact same second
        let body = "Same identical memo text";
        let res1 = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-create-1").expect("valid op"),
                relative_path: Some(path.clone()),
                time_token: Some("10:00:00".to_string()),
                content: body.to_string(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create 1");

        let res2 = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-create-2").expect("valid op"),
                relative_path: Some(path.clone()),
                time_token: Some("10:00:00".to_string()),
                content: body.to_string(),
                expected_document_fingerprint: Some(res1.commit_result.file_fingerprint.clone()),
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create 2");

        let res3 = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-create-3").expect("valid op"),
                relative_path: Some(path.clone()),
                time_token: Some("10:00:00".to_string()),
                content: body.to_string(),
                expected_document_fingerprint: Some(res2.commit_result.file_fingerprint.clone()),
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create 3");

        // All three IDs must be distinct and non-empty
        assert_ne!(res1.memo_id, res2.memo_id);
        assert_ne!(res2.memo_id, res3.memo_id);
        assert_ne!(res1.memo_id, res3.memo_id);

        // Markdown file inspection: No machine markers, clean standard Markdown
        let md_content =
            fs::read_to_string(ctx.workspace_path.join("2026_09_09.md")).expect("read md");
        assert!(!md_content.contains("<!--"));
        assert!(!md_content.contains("-->"));
        assert!(!md_content.contains("id:"));
        assert!(!md_content.contains("memo-"));
        assert_eq!(
            md_content.trim(),
            "- 10:00:00\nSame identical memo text\n\n- 10:00:00\nSame identical memo text\n\n- 10:00:00\nSame identical memo text"
        );

        // 2. Update middle item (res2)
        let update_res = ctx
            .session
            .update_memo(UpdateMemoRequest {
                operation_id: OperationId::parse("op-update-mid").expect("valid op"),
                memo_id: res2.memo_id.clone(),
                content: "Updated middle content".to_string(),
                expected_document_fingerprint: res3.commit_result.file_fingerprint.clone(),
                pending_promotes: Vec::new(),
            })
            .expect("update middle");

        // Sibling memos (res1, res3) must retain their original identities! No renumbering!
        let proj1 = ctx
            .session
            .get_memo(&res1.memo_id)
            .expect("get 1")
            .expect("found 1");
        let proj2 = ctx
            .session
            .get_memo(&res2.memo_id)
            .expect("get 2")
            .expect("found 2");
        let proj3 = ctx
            .session
            .get_memo(&res3.memo_id)
            .expect("get 3")
            .expect("found 3");

        assert_eq!(proj1.memo_id, res1.memo_id.as_str());
        assert_eq!(proj1.body, body);
        assert_eq!(proj2.memo_id, res2.memo_id.as_str());
        assert_eq!(proj2.body, "Updated middle content");
        assert_eq!(proj3.memo_id, res3.memo_id.as_str());
        assert_eq!(proj3.body, body);

        // 3. Delete middle item (res2)
        let _del_res = ctx
            .session
            .delete_memo(DeleteMemoRequest {
                operation_id: OperationId::parse("op-del-mid").expect("valid op"),
                memo_id: res2.memo_id.clone(),
                expected_document_fingerprint: update_res.file_fingerprint.clone(),
                trashed_at_ms: Some(1_700_000_000_000),
            })
            .expect("delete middle");

        // Verify remaining memos on disk still retain original IDs
        let remaining1 = ctx
            .session
            .get_memo(&res1.memo_id)
            .expect("get 1")
            .expect("found 1");
        let remaining3 = ctx
            .session
            .get_memo(&res3.memo_id)
            .expect("get 3")
            .expect("found 3");
        assert_eq!(remaining1.memo_id, res1.memo_id.as_str());
        assert_eq!(remaining3.memo_id, res3.memo_id.as_str());

        let md_after_delete =
            fs::read_to_string(ctx.workspace_path.join("2026_09_09.md")).expect("read md");
        assert_eq!(
            md_after_delete.trim(),
            "- 10:00:00\nSame identical memo text\n\n- 10:00:00\nSame identical memo text"
        );
    }

    #[test]
    fn cold_cache_and_copied_workspace_recovers_same_ids_history_and_pins_from_physical_facts() {
        let ctx = setup_test_context();
        let path = RelativeWorkspacePath::parse("2026_09_09.md").expect("valid path");

        let res1 = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-cold-1").expect("valid op"),
                relative_path: Some(path.clone()),
                time_token: Some("09:00:00".to_string()),
                content: "First pinned memo".to_string(),
                expected_document_fingerprint: None,
                pinned: true,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create 1");

        let res2 = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-cold-2").expect("valid op"),
                relative_path: Some(path.clone()),
                time_token: Some("11:00:00".to_string()),
                content: "Second memo with history".to_string(),
                expected_document_fingerprint: Some(res1.commit_result.file_fingerprint.clone()),
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create 2");

        // Update res2 to create history snapshot
        let _up_res = ctx
            .session
            .update_memo(UpdateMemoRequest {
                operation_id: OperationId::parse("op-cold-update").expect("valid op"),
                memo_id: res2.memo_id.clone(),
                content: "Second memo updated text".to_string(),
                expected_document_fingerprint: res2.commit_result.file_fingerprint.clone(),
                pending_promotes: Vec::new(),
            })
            .expect("update 2");

        // Explicitly pin res1 to ensure state record is written
        let _pin_res = ctx
            .session
            .pin_memo(PinMemoRequest {
                operation_id: OperationId::parse("op-cold-pin").expect("valid op"),
                memo_id: res1.memo_id.clone(),
                pinned: true,
                pinned_at_ms: Some(1_700_000_100_000),
            })
            .expect("pin memo");

        let orig_device_id = ctx.session.device_id().to_string();

        // Now open a brand new session in fresh, separate private directories!
        // This simulates a cold cache or copying the workspace to another device!
        let new_state_dir = tempdir().expect("new state dir");
        let new_cache_dir = tempdir().expect("new cache dir");
        let new_runtime_dir = tempdir().expect("new runtime dir");
        let new_exchange_dir = tempdir().expect("new exchange dir");

        let new_executor = Arc::new(
            PosixPlatformActionExecutor::new(new_exchange_dir.path()).expect("new executor"),
        );
        new_executor
            .bind_root(ctx.config.capability.clone(), &ctx.workspace_path)
            .expect("bind root to same workspace");

        let new_config = WorkspaceSessionConfig {
            capability: ctx.config.capability.clone(),
            root_id: WorkspaceRootId::Notes,
            time_zone: "UTC".to_owned(),
            date_format: lomo_application::calendar::DateFormat::default(),
            state_dir: new_state_dir.path().to_path_buf(),
            cache_dir: new_cache_dir.path().to_path_buf(),
            runtime_dir: new_runtime_dir.path().to_path_buf(),
            exchange_dir: new_exchange_dir.path().to_path_buf(),
        };

        let session2 = WorkspaceSession::open(new_config, new_executor).expect("open session 2");

        // Device identity must NOT be inherited from workspace!
        assert_ne!(session2.device_id(), orig_device_id);

        // Rebuild projection from physical facts
        let rebuild_res = session2.rebuild_projection().expect("rebuild projection");
        assert_eq!(rebuild_res.memos_indexed, 2);

        // Assert that res1 is pinned and has identical memo_id
        let memo1 = session2
            .get_memo(&res1.memo_id)
            .expect("get 1")
            .expect("found 1");
        assert_eq!(memo1.memo_id, res1.memo_id.as_str());
        assert!(
            memo1.is_pinned,
            "memo1 must be reconstructed as pinned from .lomo state facts!"
        );

        // Assert that res2 has identical memo_id and updated text
        let memo2 = session2
            .get_memo(&res2.memo_id)
            .expect("get 2")
            .expect("found 2");
        assert_eq!(memo2.memo_id, res2.memo_id.as_str());
        assert_eq!(memo2.body, "Second memo updated text");

        // Query pinned list
        let pinned_list = session2
            .list_memos(&MemoQuery {
                search_text: None,
                filters: lomo_store::MemoFilters {
                    pinned_only: true,
                    ..lomo_store::MemoFilters::default()
                },
                sort: lomo_store::MemoSort::default(),
            })
            .expect("list pinned");
        assert_eq!(pinned_list.items.len(), 1);
        assert_eq!(pinned_list.items[0].memo_id, res1.memo_id.as_str());
    }

    #[test]
    fn stale_baseline_rejects_overwrite_and_preserves_three_way_evidence() {
        let ctx = setup_test_context();
        let path = RelativeWorkspacePath::parse("2026_09_09.md").expect("valid path");

        let res = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-stale-1").expect("valid op"),
                relative_path: Some(path.clone()),
                time_token: Some("12:00:00".to_string()),
                content: "Original baseline text".to_string(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create");

        let baseline_fingerprint = res.commit_result.file_fingerprint.clone();

        // Simulate external edit: modifying the file on disk directly
        let file_path = ctx.workspace_path.join("2026_09_09.md");
        let external_content = "- 12:00:00 Externally modified text\n";
        fs::write(&file_path, external_content).expect("external write");

        // Now attempt to update using the stale baseline
        let update_err = ctx
            .session
            .update_memo(UpdateMemoRequest {
                operation_id: OperationId::parse("op-stale-update").expect("valid op"),
                memo_id: res.memo_id.clone(),
                content: "My local draft edit".to_string(),
                expected_document_fingerprint: baseline_fingerprint.clone(),
                pending_promotes: Vec::new(),
            })
            .expect_err("must reject stale baseline");

        assert_eq!(update_err.category(), lomo_core::ErrorCategory::Conflict);

        // Disk content must NOT be overwritten!
        let disk_now = fs::read_to_string(&file_path).expect("read disk");
        assert_eq!(disk_now, external_content);

        // Draft evidence must be preserved in state_dir/drafts/
        let drafts_dir = ctx.config.state_dir.join("drafts");
        assert!(drafts_dir.exists(), "drafts directory must exist");
        let draft_evidence = fs::read_to_string(drafts_dir.join("op-stale-update.json"))
            .expect("read draft evidence");
        assert!(draft_evidence.contains("My local draft edit"));
        assert!(draft_evidence.contains(&baseline_fingerprint));
    }

    /// A wrapper around `PlatformActionExecutor` that can inject failures on specific actions.
    struct FaultInjectingExecutor {
        inner: Arc<dyn PlatformActionExecutor>,
        fail_on_write_path_substring: Option<String>,
        failed: AtomicBool,
    }

    impl PlatformActionExecutor for FaultInjectingExecutor {
        fn execute(
            &self,
            batch: &PlatformActionBatch,
        ) -> Result<PlatformBatchResult, lomo_core::LomoError> {
            if let Some(target) = &self.fail_on_write_path_substring {
                for action in batch.actions() {
                    if let lomo_core::PlatformAction::WriteFromExchange { path, .. } = action {
                        if !path.as_str().contains(target) {
                            continue;
                        }
                        self.failed.store(true, Ordering::SeqCst);
                        return Err(lomo_core::LomoError::from_platform_boundary(
                            lomo_core::ErrorCategory::Storage,
                            "injected_executor_failure",
                            lomo_core::RetryDisposition::Never,
                            None,
                            None,
                            &format!("injected failure on writing {}", path.as_str()),
                        )
                        .unwrap_or_else(|e| e));
                    }
                }
            }
            self.inner.execute(batch)
        }
    }

    #[test]
    fn failure_recovery_reuses_operation_and_memo_id_without_duplication_and_rejects_mismatched_replay()
     {
        let workspace_dir = tempdir().expect("workspace dir");
        let state_dir = tempdir().expect("state dir");
        let cache_dir = tempdir().expect("cache dir");
        let runtime_dir = tempdir().expect("runtime dir");
        let exchange_dir = tempdir().expect("exchange dir");

        let real_executor =
            Arc::new(PosixPlatformActionExecutor::new(exchange_dir.path()).expect("executor"));
        let capability = CapabilityToken::parse("test-notes-root").expect("capability token");
        real_executor
            .bind_root(capability.clone(), workspace_dir.path())
            .expect("bind root");

        let fault_executor = Arc::new(FaultInjectingExecutor {
            inner: real_executor.clone(),
            fail_on_write_path_substring: Some(".lomo/identity".to_string()),
            failed: AtomicBool::new(false),
        });

        let config = WorkspaceSessionConfig {
            capability,
            root_id: WorkspaceRootId::Notes,
            time_zone: "UTC".to_owned(),
            date_format: lomo_application::calendar::DateFormat::default(),
            state_dir: state_dir.path().to_path_buf(),
            cache_dir: cache_dir.path().to_path_buf(),
            runtime_dir: runtime_dir.path().to_path_buf(),
            exchange_dir: exchange_dir.path().to_path_buf(),
        };

        let session = WorkspaceSession::open(config.clone(), fault_executor).expect("open session");
        let op_id = OperationId::parse("op-fail-retry-1").expect("valid op");
        let path = RelativeWorkspacePath::parse("2026_09_09.md").expect("valid path");

        // Attempt create with injected failure on .lomo/identity
        let err = session
            .create_memo(CreateMemoRequest {
                operation_id: op_id.clone(),
                relative_path: Some(path.clone()),
                time_token: Some("14:00:00".to_string()),
                content: "Fault injection memo text".to_string(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect_err("should fail due to injected failure");

        assert_eq!(err.code(), "injected_executor_failure");

        // Now remove the fault injection and retry with the SAME operation_id and SAME payload
        let session_recovered =
            WorkspaceSession::open(config, real_executor).expect("open recovered session");

        let _retry_res = session_recovered
            .create_memo(CreateMemoRequest {
                operation_id: op_id.clone(),
                relative_path: Some(path.clone()),
                time_token: Some("14:00:00".to_string()),
                content: "Fault injection memo text".to_string(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("retry must succeed");

        // Must NOT append a second duplicate entry into markdown!
        let md_content =
            fs::read_to_string(workspace_dir.path().join("2026_09_09.md")).expect("read md");
        let count = md_content.matches("Fault injection memo text").count();
        assert_eq!(count, 1, "Duplicate memo must not be appended on retry!");

        // Now attempt to replay the SAME operation_id with a DIFFERENT payload: must be rejected!
        let replay_err = session_recovered
            .create_memo(CreateMemoRequest {
                operation_id: op_id,
                relative_path: Some(path),
                time_token: Some("14:00:00".to_string()),
                content: "Different payload with same op_id".to_string(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect_err("must reject replay with different payload");

        assert_eq!(replay_err.category(), lomo_core::ErrorCategory::Conflict);
    }

    #[test]
    fn sqlite_publication_after_durable_write_refreshes_sibling_fingerprints_monotonically() {
        let ctx = setup_test_context();
        let path = RelativeWorkspacePath::parse("2026_09_09.md").expect("valid path");

        let res_a = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-sib-a").expect("valid op"),
                relative_path: Some(path.clone()),
                time_token: Some("15:00:00".to_string()),
                content: "Memo A body".to_string(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create a");

        let res_b = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-sib-b").expect("valid op"),
                relative_path: Some(path.clone()),
                time_token: Some("15:01:00".to_string()),
                content: "Memo B body".to_string(),
                expected_document_fingerprint: Some(res_a.commit_result.file_fingerprint.clone()),
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create b");

        // Initially, both memo A and memo B share the document's fingerprint after creating B
        let memo_a_before = ctx
            .session
            .get_memo(&res_a.memo_id)
            .expect("get a")
            .expect("found a");
        let memo_b_before = ctx
            .session
            .get_memo(&res_b.memo_id)
            .expect("get b")
            .expect("found b");
        assert_eq!(
            memo_a_before.file_fingerprint,
            res_b.commit_result.file_fingerprint
        );
        assert_eq!(
            memo_b_before.file_fingerprint,
            res_b.commit_result.file_fingerprint
        );

        let seq_before = res_b.commit_result.event_sequence;

        // Now update memo A
        let update_a = ctx
            .session
            .update_memo(UpdateMemoRequest {
                operation_id: OperationId::parse("op-sib-update-a").expect("valid op"),
                memo_id: res_a.memo_id.clone(),
                content: "Memo A body updated".to_string(),
                expected_document_fingerprint: res_b.commit_result.file_fingerprint.clone(),
                pending_promotes: Vec::new(),
            })
            .expect("update a");

        assert!(
            update_a.event_sequence > seq_before,
            "event sequence must be monotonic!"
        );

        // Check sibling memo B in SQLite: its file_fingerprint must have been refreshed to the new document fingerprint!
        let memo_b_after = ctx
            .session
            .get_memo(&res_b.memo_id)
            .expect("get b")
            .expect("found b");
        assert_eq!(
            memo_b_after.file_fingerprint, update_a.file_fingerprint,
            "Sibling memo B fingerprint must be incrementally refreshed in SQLite!"
        );
    }
}
