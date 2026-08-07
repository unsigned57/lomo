//! Behavior Contract
//!
//! Capability: serialize job transitions through one bounded writer, durably arbitrate
//! cancel/complete/deadline races, and publish loss-detectable events without callback backpressure.
//!
//! Scenarios:
//! - Given a SAF bootstrap job, when it is polled, then the same durable platform batch is returned.
//! - Given cancellation commits before a complete platform result, when that result arrives, then
//!   the job remains cancelled before and after process-style reopen.
//! - Given a slow listener, when cancellation commits, then the writer responds without waiting for
//!   the callback; the event advances `EventSequence` but not `CoreRevision`.
//! - Given a process reopens after the persisted bootstrap deadline, then the job is durably failed
//!   as timeout rather than receiving a fresh deadline.
//!
//! Observable outcomes: `JobStep`, `CancelOutcome`, `CoreEvent`, engine snapshots, elapsed writer
//! response, and recovered terminal state.
//! TDD proof: the first run fails because job/listener/cancel/shutdown APIs do not exist; GREEN is
//! retained by current engine recovery tests.
//! Excludes: Kotlin Flow adaptation, Android side-effect execution, and `UniFFI` conversion.

#[cfg(test)]
#[path = "support/option.rs"]
mod option_support;
#[cfg(test)]
#[path = "support/success.rs"]
mod support;

#[cfg(test)]
mod tests {

    use std::fs;
    use std::sync::{Arc, Condvar, Mutex};
    use std::time::{Duration, Instant};

    use lomo_core::{
        ActionEvidence, ActionOutcome, ActionResult, CancelOutcome, CapabilityToken, CoreEvent,
        CoreEventListener, DocumentKind, DocumentMetadata, EngineConfig, EngineState,
        ErrorCategory, ExchangeArtifact, JobStep, LomoEngine, MetadataPage, PlatformAction,
        PlatformActionOutput, PlatformBatchResult, Sha256Digest, VerifiedAbsence,
        WorkspaceDescriptor, WorkspaceId, WorkspaceTarget,
    };
    use tempfile::tempdir;

    use super::option_support::OptionTestExt;
    use super::support::ResultTestExt;

    struct Fixture {
        _temporary: tempfile::TempDir,
        config: EngineConfig,
    }

    impl Fixture {
        fn new(deadline: Duration) -> Self {
            let temporary = tempdir().must_succeed("temporary root");
            let control = temporary.path().join("control");
            let exchange = temporary.path().join("exchange");
            fs::create_dir(&control).must_succeed("control root");
            fs::create_dir(&exchange).must_succeed("exchange root");
            let capability = CapabilityToken::parse("saf-root-1").must_succeed("capability");
            let identity = WorkspaceId::parse("ws-saf-root-1").must_succeed("workspace identity");
            let workspace = WorkspaceDescriptor::saf(identity, capability);
            let config = EngineConfig::new(control, exchange, Some(workspace))
                .must_succeed("engine config")
                .with_bootstrap_deadline(deadline)
                .must_succeed("deadline");
            Self {
                _temporary: temporary,
                config,
            }
        }
    }

    fn opening_job(engine: &LomoEngine) -> lomo_core::JobId {
        let state = engine.state();
        match state {
            EngineState::Opening { job_id } => job_id,
            EngineState::AwaitingWorkspaceSelection
            | EngineState::Ready { .. }
            | EngineState::ReadOnlyRecovery { .. }
            | EngineState::ShuttingDown => {
                panic!("SAF engine must await bootstrap, got {state:?}")
            }
        }
    }

    fn successful_result(step: &JobStep) -> PlatformBatchResult {
        let JobStep::NeedsPlatformBatch { batch } = step else {
            panic!("job must need a platform batch, got {step:?}");
        };
        let results = batch
            .actions()
            .iter()
            .map(|action| {
                ActionResult::new(
                    action.id().clone(),
                    ActionOutcome::Applied(output_for(action)),
                )
            })
            .collect();
        PlatformBatchResult::new(
            batch.schema_version(),
            batch.job_id().clone(),
            batch.batch_id().clone(),
            batch.attempt(),
            results,
        )
    }

    fn output_for(action: &PlatformAction) -> PlatformActionOutput {
        let evidence = || {
            ActionEvidence::verified(
                0,
                Sha256Digest::parse(&"c".repeat(64)).must_succeed("digest"),
                "root-fingerprint",
            )
            .must_succeed("evidence")
        };
        let metadata = |target: WorkspaceTarget, kind: DocumentKind| {
            DocumentMetadata::new(target, kind, None, evidence()).must_succeed("metadata")
        };
        match action {
            PlatformAction::Stat { target, .. } => PlatformActionOutput::Stat {
                metadata: metadata(target.clone(), DocumentKind::Directory),
            },
            PlatformAction::ListChildren { .. } => PlatformActionOutput::Listed {
                page: MetadataPage::new(Vec::new(), None).must_succeed("empty metadata page"),
            },
            PlatformAction::EnsureDirectory { path, .. } => PlatformActionOutput::DirectoryReady {
                metadata: metadata(
                    WorkspaceTarget::Relative(path.clone()),
                    DocumentKind::Directory,
                ),
            },
            PlatformAction::ReadToExchange {
                path,
                exchange_token,
                ..
            } => PlatformActionOutput::ReadToExchange {
                source_metadata: metadata(
                    WorkspaceTarget::Relative(path.clone()),
                    DocumentKind::File,
                ),
                artifact: ExchangeArtifact::new(
                    exchange_token.as_str(),
                    0,
                    Sha256Digest::parse(&"c".repeat(64)).must_succeed("digest"),
                )
                .must_succeed("artifact"),
            },
            PlatformAction::WriteFromExchange { artifact, path, .. } => {
                let written_evidence = ActionEvidence::verified(
                    artifact.length(),
                    artifact.digest().clone(),
                    "root-fingerprint",
                )
                .must_succeed("written evidence");
                PlatformActionOutput::WriteComplete {
                    metadata: DocumentMetadata::new(
                        WorkspaceTarget::Relative(path.clone()),
                        DocumentKind::File,
                        None,
                        written_evidence,
                    )
                    .must_succeed("written metadata"),
                }
            }
            PlatformAction::Move { target, .. } => PlatformActionOutput::MoveComplete {
                metadata: metadata(
                    WorkspaceTarget::Relative(target.clone()),
                    DocumentKind::File,
                ),
            },
            PlatformAction::Delete { path, .. } => PlatformActionOutput::DeleteComplete {
                absence: VerifiedAbsence::new(
                    WorkspaceTarget::Relative(path.clone()),
                    "deleted-fingerprint",
                )
                .must_succeed("verified absence"),
            },
        }
    }

    #[test]
    fn durable_cancel_wins_over_a_late_complete_result_and_reopen() {
        let fixture = Fixture::new(Duration::from_secs(30));
        let engine = LomoEngine::open(fixture.config.clone()).must_succeed("engine");
        let job_id = opening_job(&engine);
        let waiting = engine.poll_job(&job_id).must_succeed("waiting job");
        let result = successful_result(&waiting);

        assert_eq!(
            engine.cancel_job(&job_id).must_succeed("durable cancel"),
            CancelOutcome::Accepted
        );
        assert_eq!(
            engine.cancel_job(&job_id).must_succeed("repeat cancel"),
            CancelOutcome::AlreadyCancelled
        );
        let late = engine
            .submit_platform_result(&job_id, result)
            .must_succeed("terminal job remains observable");
        let JobStep::Failed { error } = late else {
            panic!("late completion must not replace cancellation");
        };
        assert_eq!(error.category(), ErrorCategory::Cancelled);
        drop(engine);

        let reopened = LomoEngine::open(fixture.config).must_succeed("reopen cancelled journal");
        let JobStep::Failed { error } = reopened.poll_job(&job_id).must_succeed("recovered job")
        else {
            panic!("cancelled terminal must recover");
        };
        assert_eq!(error.category(), ErrorCategory::Cancelled);
    }

    struct SlowListener {
        event: Mutex<Option<CoreEvent>>,
        event_ready: Condvar,
        released: Mutex<bool>,
        release: Condvar,
    }

    impl SlowListener {
        fn new() -> Self {
            Self {
                event: Mutex::new(None),
                event_ready: Condvar::new(),
                released: Mutex::new(false),
                release: Condvar::new(),
            }
        }

        fn await_event(&self) -> CoreEvent {
            let (mut event, timeout) = self
                .event_ready
                .wait_timeout_while(
                    self.event.lock().must_succeed("event lock"),
                    Duration::from_secs(2),
                    |event| event.is_none(),
                )
                .must_succeed("event wait");
            assert!(
                !timeout.timed_out(),
                "listener did not receive cancellation event"
            );
            let received = event.take().must_succeed("event is present");
            drop(event);
            received
        }

        fn release(&self) {
            *self.released.lock().must_succeed("release lock") = true;
            self.release.notify_one();
        }
    }

    impl CoreEventListener for SlowListener {
        fn on_event(&self, event: CoreEvent) -> Result<(), lomo_core::LomoError> {
            *self.event.lock().must_succeed("event lock") = Some(event);
            self.event_ready.notify_one();
            let released = self.released.lock().must_succeed("release lock");
            drop(
                self.release
                    .wait_while(released, |released| !*released)
                    .must_succeed("release wait"),
            );
            Ok(())
        }
    }

    #[test]
    fn slow_listener_does_not_block_the_single_writer() {
        let fixture = Fixture::new(Duration::from_secs(30));
        let engine = LomoEngine::open(fixture.config).must_succeed("engine");
        let job_id = opening_job(&engine);
        let listener = Arc::new(SlowListener::new());
        // Coerce concrete Arc to trait object without a trivial `as` cast.
        let listener_dyn: Arc<dyn CoreEventListener> = Arc::<SlowListener>::clone(&listener);
        let subscription = engine
            .subscribe(listener_dyn)
            .must_succeed("explicit subscription");
        let before = engine.state();

        let started = Instant::now();
        assert_eq!(
            engine.cancel_job(&job_id).must_succeed("cancel"),
            CancelOutcome::Accepted
        );
        assert!(
            started.elapsed() < Duration::from_millis(250),
            "foreign callback blocked the writer"
        );
        let event = listener.await_event();
        assert_eq!(event.core_revision().get(), 0);
        let EngineState::Opening { .. } = before else {
            panic!("fixture must begin opening");
        };
        assert!(event.event_sequence().get() > 0);
        listener.release();
        assert!(
            subscription.close(),
            "first explicit close removes listener"
        );
        assert!(!subscription.close(), "subscription close is idempotent");
    }

    #[test]
    fn persisted_deadline_expires_instead_of_restarting_on_reopen() {
        let fixture = Fixture::new(Duration::from_millis(30));
        let engine = LomoEngine::open(fixture.config.clone()).must_succeed("engine");
        let job_id = opening_job(&engine);
        drop(engine);
        std::thread::sleep(Duration::from_millis(60));

        let reopened = LomoEngine::open(fixture.config).must_succeed("reopen after deadline");
        let JobStep::Failed { error } = reopened.poll_job(&job_id).must_succeed("expired job")
        else {
            panic!("expired job must be terminal");
        };
        assert_eq!(error.category(), ErrorCategory::Timeout);
        assert_eq!(error.code(), "job_deadline_exceeded");
    }
}
