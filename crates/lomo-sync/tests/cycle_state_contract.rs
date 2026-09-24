//! Behavior Contract (B05 durable sync-cycle record + cancel)
//!
//! Capability: every production composed cycle persists a `cycle_state.rec` record (cycle id,
//! fence, phase, counts, disposition, last successful, state stamp); a cancel request is durable
//! (`cancel_request.rec`) and stops publication between pages; a stale `Running` record is
//! repaired, never silently lost.
//!
//! Scenarios:
//! - Given a composed hermetic apply cycle, when it finishes, then `cycle_state.rec` records
//!   `completed` with counts/disposition/backend and `last_successful_at_ms` set.
//! - Given a terminal record, when a new cycle begins, then `cycle_seq`/`state_stamp` advance
//!   monotonically and `last_successful` carries forward.
//! - Given a stale `Running` record (writer died), when the next cycle begins, then the new
//!   cycle starts cleanly, a stale cancel file cannot abort it, and stamps stay monotonic.
//! - Given a running cycle + durable cancel request, when the apply loop reaches the next page
//!   boundary, then the record becomes `cancelled` at `pages_applied` and no further publish
//!   call is issued.
//! - Given a cancel request written mid-apply (during page-1 publish), when the loop reaches
//!   page 2, then it aborts `sync_cycle_cancelled`, `publish` count stays 1, and the record
//!   records `pages_applied = 1` — committed pages are never claimed rolled back.
//! - Given no running cycle (absent or terminal record), when cancel is requested, then
//!   validation rejects `sync_cycle_not_running`.
//! - Given a composed cycle that fails inside the body, when the error returns, then the record
//!   persists `failed` with the owner error code.
//!
//! Observable outcomes: durable record fields across restart reads, publish-call counts on the
//! fake remote, error codes. Excludes: real provider transports, `WorkManager` scheduling,
//! Kotlin-side state mapping (native FFI contract).

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_store::run_rebuild;
    use lomo_sync::{
        CYCLE_CANCELLED_CODE, ContentDigest, FakeLocalPort, FakeRemotePort, LocalPathEntry,
        PreparedRemoteBatch, PublishReceipt, RemoteListingStream, RemotePathEntry,
        RemoteResolvedObject, RemoteSnapshot, RemoteSyncPort, RemoteValidator, SessionKind,
        SnapshotCompleteness, SyncBackendConfig, SyncBackendKind, SyncCyclePhase,
        SyncIdentityFence, SyncPath, SyncPaths, SyncSession, VerifiedRemoteState,
        VerifyExpectation, begin_sync_cycle, inspect_sync_cycle_plan_with_ports, read_cycle_state,
        request_sync_cycle_cancel, run_composed_sync_cycle,
        run_composed_sync_cycle_with_remote_port, sync_cycle_cancel_requested, write_session,
    };
    use lomo_workspace::{RemoteDatasetId, RemoteIdentityDigest, WorkspaceGenerationId};
    use tempfile::tempdir;

    fn fence() -> SyncIdentityFence {
        SyncIdentityFence::from_parts(
            &WorkspaceGenerationId::parse(&"ab".repeat(32)).expect("gen"),
            &RemoteDatasetId::parse("ds").expect("ds"),
            &RemoteIdentityDigest::parse(&"cd".repeat(32)).expect("id"),
        )
    }

    fn dig(seed: u8) -> ContentDigest {
        ContentDigest::parse(&format!("{seed:02x}").repeat(32)).expect("digest")
    }

    fn local_entries(count: usize) -> FakeLocalPort {
        FakeLocalPort {
            entries: (0..count)
                .map(|index| LocalPathEntry {
                    path: SyncPath::parse(&format!("memo/e{index:04}.md")).expect("path"),
                    digest: dig(u8::try_from(index % 251).expect("u8")),
                })
                .collect(),
        }
    }

    fn empty_remote() -> FakeRemotePort {
        FakeRemotePort::new(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
            PublishReceipt {
                path_results: Vec::new(),
            },
            VerifiedRemoteState {
                results: Vec::new(),
            },
        )
    }

    /// Workspace fixture: generation fence + store projection so the composed local port has
    /// real facts (empty memo set — the plan is empty and apply completes vacuously).
    fn composed_workspace() -> (tempfile::TempDir, std::path::PathBuf) {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        lomo_workspace::load_or_mint_workspace_generation(&workspace).expect("generation");
        run_rebuild(&workspace, 8).expect("rebuild");
        (temporary, workspace)
    }

    #[test]
    fn composed_apply_cycle_persists_completed_record() {
        let (_temporary, workspace) = composed_workspace();
        let summary = run_composed_sync_cycle(
            &workspace,
            &SyncBackendConfig::hermetic_fake("ds-recorded"),
            None,
            true,
        )
        .expect("composed cycle");

        let paths = SyncPaths::for_workspace(&workspace);
        let record = read_cycle_state(&paths)
            .expect("read cycle state")
            .expect("record present");
        assert_eq!(record.phase, SyncCyclePhase::Completed);
        assert_eq!(record.cycle_seq, 1);
        assert_eq!(record.cycle_id, "cycle-000001");
        assert_eq!(record.backend_kind, "hermetic_fake");
        assert_eq!(record.session_id, summary.session_id);
        assert!(record.apply_remote);
        assert_eq!(record.stage, "finished");
        assert_eq!(record.retry_disposition, summary.retry_disposition);
        assert_eq!(record.ensure_present_count, summary.ensure_present_count);
        assert_eq!(record.pull_present_count, summary.pull_present_count);
        assert_eq!(record.open_conflict_count, summary.open_conflict_count);
        assert!(record.failure_code.is_none());
        assert!(record.finished_at_ms.is_some());
        assert!(record.last_successful_at_ms.is_some());
        assert!(record.state_stamp > 0);
        // Fence binds the durable generation + dataset + remote identity observed at begin.
        assert!(record.fence_key.contains("ds-recorded"));
    }

    #[test]
    fn consecutive_cycles_advance_seq_and_stamp_and_carry_last_successful() {
        let (_temporary, workspace) = composed_workspace();
        let config = SyncBackendConfig::hermetic_fake("ds-seq");
        run_composed_sync_cycle(&workspace, &config, None, true).expect("first cycle");
        let paths = SyncPaths::for_workspace(&workspace);
        let first = read_cycle_state(&paths).expect("read").expect("record");
        let first_success = first.last_successful_at_ms;

        run_composed_sync_cycle(&workspace, &config, None, false).expect("plan-only cycle");
        let second = read_cycle_state(&paths).expect("read").expect("record");
        assert_eq!(second.cycle_seq, 2);
        assert_eq!(second.cycle_id, "cycle-000002");
        assert!(second.state_stamp > first.state_stamp);
        // Plan-only cycles do not advance last_successful but never erase it either.
        assert_eq!(second.last_successful_at_ms, first_success);
        assert!(!second.apply_remote);
    }

    #[test]
    fn plan_only_cycle_does_not_mark_last_successful() {
        let (_temporary, workspace) = composed_workspace();
        run_composed_sync_cycle(
            &workspace,
            &SyncBackendConfig::hermetic_fake("ds-plan-only"),
            None,
            false,
        )
        .expect("cycle");
        let paths = SyncPaths::for_workspace(&workspace);
        let record = read_cycle_state(&paths).expect("read").expect("record");
        assert_eq!(record.phase, SyncCyclePhase::Completed);
        assert!(record.last_successful_at_ms.is_none());
    }

    #[test]
    fn stale_running_record_is_repaired_and_stale_cancel_cleared() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let paths = SyncPaths::for_workspace(&workspace);
        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "sess-stale").expect("session");
        write_session(&paths, &session).expect("write session");

        // Simulate process death: a Running record + cancel request with no live writer.
        let dead = begin_sync_cycle(&paths, &session, SyncBackendKind::WebDav, true)
            .expect("begin dead cycle");
        request_sync_cycle_cancel(&paths).expect("cancel dead cycle");
        assert!(sync_cycle_cancel_requested(&paths).expect("cancel observed"));

        let record = begin_sync_cycle(&paths, &session, SyncBackendKind::S3, false)
            .expect("begin next cycle");
        assert_eq!(record.cycle_seq, dead.cycle_seq + 1);
        assert!(record.state_stamp > dead.state_stamp);
        assert_eq!(record.phase, SyncCyclePhase::Running);
        assert_eq!(record.backend_kind, "s3");
        assert!(!record.cancel_requested);
        // The dead cycle's cancel request must not abort the new cycle.
        assert!(!sync_cycle_cancel_requested(&paths).expect("cancel cleared"));
    }

    #[test]
    fn request_cancel_rejects_when_no_running_cycle() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let paths = SyncPaths::for_workspace(&workspace);
        let err = request_sync_cycle_cancel(&paths).expect_err("no record");
        assert_eq!(err.code(), "sync_cycle_not_running");

        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "sess-term").expect("session");
        write_session(&paths, &session).expect("write session");
        let mut record =
            begin_sync_cycle(&paths, &session, SyncBackendKind::Git, false).expect("begin");
        lomo_sync::complete_sync_cycle(&paths, &mut record, &idle_summary()).expect("complete");
        let err = request_sync_cycle_cancel(&paths).expect_err("terminal");
        assert_eq!(err.code(), "sync_cycle_not_running");
    }

    fn idle_summary() -> lomo_sync::SyncCyclePlanSummary {
        lomo_sync::SyncCyclePlanSummary {
            session_id: "sess-term".to_owned(),
            session_kind: SessionKind::Incremental,
            session_revision: 1,
            baseline_established: false,
            ensure_present_count: 0,
            ensure_absent_count: 0,
            pull_present_count: 0,
            open_conflict_count: 0,
            hold_count: 0,
            open_conflict_paths: 0,
            conflict_revision: None,
            retry_disposition: "after_user_action",
            pages_applied: 0,
            baseline_advanced: false,
            local_entry_count: 0,
            remote_listed_count: 0,
            baseline_entry_count: 0,
        }
    }

    #[test]
    fn cancel_before_first_page_blocks_all_publication() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let paths = SyncPaths::for_workspace(&workspace);
        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "sess-cancel").expect("session");
        write_session(&paths, &session).expect("write session");

        let running =
            begin_sync_cycle(&paths, &session, SyncBackendKind::WebDav, true).expect("begin");
        request_sync_cycle_cancel(&paths).expect("cancel");

        let local = local_entries(4);
        let remote = empty_remote();
        let err = inspect_sync_cycle_plan_with_ports(&paths, &local, &remote, true, None)
            .expect_err("cancelled");
        assert_eq!(err.code(), CYCLE_CANCELLED_CODE);
        assert_eq!(err.category(), lomo_core::ErrorCategory::Cancelled);
        // No unauthorized publication after the cancel point.
        assert_eq!(remote.publish_call_count(), 0);

        let record = read_cycle_state(&paths).expect("read").expect("record");
        assert_eq!(record.cycle_id, running.cycle_id);
        assert_eq!(record.phase, SyncCyclePhase::Cancelled);
        assert_eq!(record.stage, "cancelled");
        assert_eq!(record.pages_applied, 0);
        assert!(record.cancel_requested);
        assert_eq!(record.failure_code.as_deref(), Some(CYCLE_CANCELLED_CODE));
        assert!(record.finished_at_ms.is_some());
    }

    /// Remote port that cancels the running durable cycle inside its first `publish` call,
    /// proving a mid-cycle request stops the next page without rolling back page 1.
    struct CancelOnFirstPublishPort {
        inner: FakeRemotePort,
        paths: SyncPaths,
        armed: std::sync::atomic::AtomicBool,
    }

    impl RemoteSyncPort for CancelOnFirstPublishPort {
        fn list_remote(&self) -> Result<RemoteSnapshot, lomo_core::LomoError> {
            self.inner.list_remote()
        }

        fn list_remote_pages(&self) -> Result<RemoteListingStream, lomo_core::LomoError> {
            self.inner.list_remote_pages()
        }

        fn remote_capabilities(
            &self,
        ) -> Result<lomo_sync::RemoteCapabilities, lomo_core::LomoError> {
            self.inner.remote_capabilities()
        }

        fn publish(
            &self,
            batch: &PreparedRemoteBatch,
        ) -> Result<PublishReceipt, lomo_core::LomoError> {
            let receipt = self.inner.publish(batch)?;
            if self.armed.swap(false, std::sync::atomic::Ordering::SeqCst) {
                request_sync_cycle_cancel(&self.paths).expect("cancel request");
            }
            Ok(receipt)
        }

        fn verify(
            &self,
            expectations: &[VerifyExpectation],
        ) -> Result<VerifiedRemoteState, lomo_core::LomoError> {
            self.inner.verify(expectations)
        }

        fn resolve_remote_object(
            &self,
            path: &SyncPath,
        ) -> Result<Option<RemoteResolvedObject>, lomo_core::LomoError> {
            self.inner.resolve_remote_object(path)
        }

        fn load_object(
            &self,
            path: &SyncPath,
            expected_digest: &ContentDigest,
        ) -> Result<Option<Vec<u8>>, lomo_core::LomoError> {
            self.inner.load_object(path, expected_digest)
        }
    }

    #[test]
    fn cancel_mid_apply_records_cancellation_point_without_republish() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let paths = SyncPaths::for_workspace(&workspace);
        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "sess-midcancel").expect("session");
        write_session(&paths, &session).expect("write session");
        begin_sync_cycle(&paths, &session, SyncBackendKind::WebDav, true).expect("begin");

        // More than one intent page (> 512 per-path intents) so the loop reaches a second
        // cancellation check after the first published page.
        let local = local_entries(600);
        let remote = CancelOnFirstPublishPort {
            inner: empty_remote(),
            paths: paths.clone(),
            armed: std::sync::atomic::AtomicBool::new(true),
        };
        let err = inspect_sync_cycle_plan_with_ports(&paths, &local, &remote, true, None)
            .expect_err("cancelled mid-apply");
        assert_eq!(err.code(), CYCLE_CANCELLED_CODE);
        // Exactly one page published before the cancel point; no further publication.
        assert_eq!(remote.inner.publish_call_count(), 1);

        let record = read_cycle_state(&paths).expect("read").expect("record");
        assert_eq!(record.phase, SyncCyclePhase::Cancelled);
        assert_eq!(record.pages_applied, 1);
        assert!(record.cancel_requested);
    }

    #[test]
    fn failed_composed_cycle_persists_failure_record() {
        let (_temporary, workspace) = composed_workspace();
        let config = SyncBackendConfig::WebDav {
            endpoint_url: "https://dav.example/remote.php/dav".into(),
            username: "alice".into(),
            remote_dataset_id: "ds-webdav".into(),
        };
        let err = run_composed_sync_cycle(&workspace, &config, None, true)
            .expect_err("missing secret fails");
        assert_eq!(err.code(), "webdav_secret_required");

        let paths = SyncPaths::for_workspace(&workspace);
        let record = read_cycle_state(&paths).expect("read").expect("record");
        assert_eq!(record.phase, SyncCyclePhase::Failed);
        assert_eq!(record.stage, "failed");
        assert_eq!(record.backend_kind, "webdav");
        assert_eq!(
            record.failure_code.as_deref(),
            Some("webdav_secret_required")
        );
        assert_eq!(record.retry_disposition, "never");
        assert!(record.last_successful_at_ms.is_none());
        assert!(record.finished_at_ms.is_some());
    }

    #[test]
    fn remote_port_cycle_records_baseline_and_listing_counts() {
        let (_temporary, workspace) = composed_workspace();
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(
                SnapshotCompleteness::Complete,
                vec![RemotePathEntry {
                    path: SyncPath::parse("memo/remote-only.md").expect("path"),
                    digest: lomo_sync::RemoteDigestFact::Known(dig(9)),
                    validator: RemoteValidator::Strong("tok-r".to_owned()),
                }],
            )
            .expect("snap"),
            PublishReceipt {
                path_results: Vec::new(),
            },
            VerifiedRemoteState {
                results: Vec::new(),
            },
        );
        run_composed_sync_cycle_with_remote_port(
            &workspace,
            &SyncBackendConfig::hermetic_fake("ds-counts"),
            &remote,
            false,
        )
        .expect("cycle");
        let paths = SyncPaths::for_workspace(&workspace);
        let record = read_cycle_state(&paths).expect("read").expect("record");
        assert_eq!(record.remote_listed_count, 1);
        assert_eq!(record.phase, SyncCyclePhase::Completed);
        // Fence carries the durable identity observed by the cycle.
        assert!(record.fence_key.contains("ds-counts"));
    }

    #[test]
    fn cancel_request_does_not_abort_completed_cycle() {
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let paths = SyncPaths::for_workspace(&workspace);
        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "sess-late").expect("session");
        write_session(&paths, &session).expect("write session");
        let mut record =
            begin_sync_cycle(&paths, &session, SyncBackendKind::WebDav, true).expect("begin");
        lomo_sync::complete_sync_cycle(&paths, &mut record, &idle_summary()).expect("complete");
        // A late cancel request cannot flip a terminal record.
        let err = request_sync_cycle_cancel(&paths).expect_err("terminal rejects");
        assert_eq!(err.code(), "sync_cycle_not_running");
        let after = read_cycle_state(&paths).expect("read").expect("record");
        assert_eq!(after.phase, SyncCyclePhase::Completed);
    }
}
