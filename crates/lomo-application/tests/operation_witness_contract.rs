//! Behavior Contract
//!
//! Capability: a `sessionUpdateMemo` commit costs bounded durable work no matter how many
//! commits the document already carries, and an operation replayed after every retained
//! witness window still expires instead of re-executing or replaying a stale receipt.
//! Owning layer: lomo-application session transaction + lomo-workspace identity record;
//! priority P0.
//!
//! Scenarios:
//! - Given a document whose durable witness window is already full, when further memo
//!   updates accumulate and its identity record is decoded, then the retained operation
//!   tail and record bytes stay bounded, and median per-commit latency in the final stretch is
//!   comparable to the first stretch instead of growing with history depth.
//! - Given an operation committed and then evicted from both the intent journal's retained
//!   retired epochs and the identity record's retained tail, when the same operation id is
//!   replayed through `update_memo`, then the session fails `operation_expired` — the
//!   projection's committed-operation index is the terminal witness — and the next fresh
//!   operation still commits.
//!
//! Observable outcomes: bounded `MemoIdentityMap::operations()`, bounded `.lomo/identity`
//! record bytes, flat per-commit latency, `operation_expired` error, fresh commits
//! unaffected.
//! TDD proof: the original append-only identity journal failed the bounded-tail, byte-budget
//! and flat-latency assertions as document history grew.
//! Excludes: SAF provider execution, Kotlin FFI surface, and cross-device sync arbitration
//! (the retained tail remains the durable cross-device replay witness inside its window).
//!
//! Test Change Justification:
//! Reason category: correct the steady-state performance baseline.
//! Old behavior/assertion being replaced: compare the first 30 commits of a growing
//! witness tail with the final 30 commits of a saturated tail.
//! Why old assertion is no longer correct: bounded initial growth to 64 operations is
//! permitted; coverage instrumentation exposes its extra work compared with an empty tail.
//! Coverage preserved by: the unchanged 1.75x latency limit, 64-operation limit, record-byte
//! budget and expiration scenario, plus an explicit saturated-baseline assertion.
//! Why this is not fitting the test to the implementation: both measured stretches now
//! start from the same contract-defined witness capacity while history depth keeps growing.
//! RED evidence: ci-release.stderr.log and ci-verified.stderr.log under
//! reports/manual/directory-tidy-2026-11-14/ record 8.077ms vs 14.254ms against the 1.75x limit.
//! GREEN evidence: witness-steady-state.stderr.log in the same directory records both
//! existing scenarios passing under workspace-feature coverage after baseline saturation.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::indexing_slicing,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use std::{fs, path::PathBuf, sync::Arc, time::Duration, time::Instant};

    use lomo_application::{
        CreateMemoRequest, UpdateMemoRequest, WorkspaceSession, WorkspaceSessionConfig,
    };
    use lomo_core::{CapabilityToken, OperationId, RelativeWorkspacePath};
    use lomo_platform_fs::FsPlatformActionExecutor;
    use lomo_workspace::{
        MemoId, MemoIdentityMap, WorkspaceRelativePath, WorkspaceRootId, memo_identity_record_path,
    };
    use tempfile::tempdir;

    /// Retained durable witness window per document, pinned by the workspace contract.
    const WITNESS_BUDGET: usize = 64;
    /// Upper bound for the serialized identity record under the witness window.
    const RECORD_BYTE_BUDGET: usize = WITNESS_BUDGET * 512;
    /// Measured commits after the witness window has reached its retained capacity.
    const COMMITS: usize = 140;
    /// Compare saturated witness windows with the existing 1.75x latency budget.
    const MAX_LATENCY_RATIO_NUM: u128 = 7;
    const MAX_LATENCY_RATIO_DEN: u128 = 4;

    struct TestContext {
        _workspace_dir: tempfile::TempDir,
        _state_dir: tempfile::TempDir,
        _cache_dir: tempfile::TempDir,
        _runtime_dir: tempfile::TempDir,
        _exchange_dir: tempfile::TempDir,
        workspace_path: PathBuf,
        session: WorkspaceSession,
    }

    fn setup_test_context() -> TestContext {
        let workspace_dir = tempdir().expect("workspace dir");
        let state_dir = tempdir().expect("state dir");
        let cache_dir = tempdir().expect("cache dir");
        let runtime_dir = tempdir().expect("runtime dir");
        let exchange_dir = tempdir().expect("exchange dir");

        let executor =
            Arc::new(FsPlatformActionExecutor::new(exchange_dir.path()).expect("executor"));
        let capability = CapabilityToken::parse("test-notes-root").expect("capability token");
        executor
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

        let session = WorkspaceSession::open(config, executor).expect("open session");
        let workspace_path = workspace_dir.path().to_path_buf();

        TestContext {
            _workspace_dir: workspace_dir,
            _state_dir: state_dir,
            _cache_dir: cache_dir,
            _runtime_dir: runtime_dir,
            _exchange_dir: exchange_dir,
            workspace_path,
            session,
        }
    }

    fn seed_document(ctx: &TestContext) -> (MemoId, String) {
        let created = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: OperationId::parse("op-witness-seed").expect("valid op"),
                relative_path: Some(
                    RelativeWorkspacePath::parse("2026_10_01.md").expect("valid path"),
                ),
                time_token: Some("10:00:00".to_owned()),
                content: format!("witness seed {}", "x".repeat(300)),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("seed memo");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        (created.memo_id, created.commit_result.file_fingerprint)
    }

    /// One fixed-size `update_memo` commit; returns wall-clock latency and the new
    /// document fingerprint for the next revision.
    fn commit_update(
        ctx: &TestContext,
        index: usize,
        memo_id: &MemoId,
        fingerprint: &str,
    ) -> (Duration, String) {
        let started = Instant::now();
        let result = ctx
            .session
            .update_memo(UpdateMemoRequest {
                operation_id: OperationId::parse(&format!("op-witness-{index:04}"))
                    .expect("valid op"),
                memo_id: memo_id.to_owned(),
                // Fixed-size body keeps the document itself constant: any measured
                // drift belongs to journal growth, not content growth.
                content: format!("{} revision {index:06}", "x".repeat(280)),
                expected_document_fingerprint: fingerprint.to_owned(),
                pending_promotes: Vec::new(),
            })
            .expect("update memo");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        (started.elapsed(), result.commit_result.file_fingerprint)
    }

    fn identity_record(ctx: &TestContext) -> Vec<u8> {
        let relative = memo_identity_record_path(
            WorkspaceRootId::Notes,
            &WorkspaceRelativePath::parse("2026_10_01.md").expect("workspace path"),
        )
        .expect("identity record path");
        fs::read(ctx.workspace_path.join(relative.as_str())).expect("identity record bytes")
    }

    fn median(durations: &[Duration]) -> Duration {
        let mut sorted = durations.to_vec();
        sorted.sort();
        sorted[sorted.len() / 2]
    }

    #[test]
    fn commit_work_is_bounded_as_document_history_grows() {
        let ctx = setup_test_context();
        let (memo_id, mut fingerprint) = seed_document(&ctx);
        for index in 0..WITNESS_BUDGET {
            let (_, next) = commit_update(&ctx, index, &memo_id, &fingerprint);
            fingerprint = next;
        }
        let baseline_record = identity_record(&ctx);
        let baseline_map = MemoIdentityMap::decode(&baseline_record).expect("decode baseline");
        assert_eq!(
            baseline_map.operations().len(),
            WITNESS_BUDGET,
            "latency sampling requires a saturated witness tail"
        );
        let mut durations = Vec::with_capacity(COMMITS);
        for index in WITNESS_BUDGET..WITNESS_BUDGET + COMMITS {
            let (elapsed, next) = commit_update(&ctx, index, &memo_id, &fingerprint);
            durations.push(elapsed);
            fingerprint = next;
        }

        let record = identity_record(&ctx);
        let map = MemoIdentityMap::decode(&record).expect("decode identity record");
        assert!(
            map.operations().len() <= WITNESS_BUDGET,
            "durable identity journal must stay a bounded witness tail, found {} operations",
            map.operations().len()
        );
        assert!(
            record.len() <= RECORD_BYTE_BUDGET,
            "identity record grew to {} bytes after {} commits",
            record.len(),
            WITNESS_BUDGET + COMMITS
        );

        let head = median(&durations[..30]);
        let tail = median(&durations[COMMITS - 30..]);
        assert!(
            tail.as_micros() * MAX_LATENCY_RATIO_DEN <= head.as_micros() * MAX_LATENCY_RATIO_NUM,
            "per-commit latency grew with history depth: first-stretch median {head:?}, last-stretch median {tail:?}"
        );
    }

    #[test]
    fn operation_evicted_from_every_witness_window_still_expires() {
        let ctx = setup_test_context();
        let (memo_id, mut fingerprint) = seed_document(&ctx);
        for index in 0..WITNESS_BUDGET + 8 {
            let (_, next) = commit_update(&ctx, index, &memo_id, &fingerprint);
            fingerprint = next;
        }
        // Retire three epochs: the first operation's receipt leaves the committed
        // directory and its id leaves the two retained retired-epoch witnesses.
        for round in 0..3 {
            assert!(
                ctx.session.seal().expect("seal epoch"),
                "epoch {round} must retire with an empty pending set"
            );
        }
        // `op-witness-0000` is now evicted from the intent journal's retired window
        // and from the identity record's retained tail; the committed-operation
        // index in the projection is the only remaining durable witness.
        let error = ctx
            .session
            .update_memo(UpdateMemoRequest {
                operation_id: OperationId::parse("op-witness-0000").expect("valid op"),
                memo_id: memo_id.clone(),
                content: format!("{} revision {:06}", "x".repeat(280), 0),
                expected_document_fingerprint: fingerprint.clone(),
                pending_promotes: Vec::new(),
            })
            .expect_err("an operation evicted from every witness must expire");
        assert_eq!(error.code(), "operation_expired");

        // The witness check did not poison the session: a fresh operation commits.
        let (_, _) = commit_update(&ctx, WITNESS_BUDGET + 100, &memo_id, &fingerprint);
    }
}
