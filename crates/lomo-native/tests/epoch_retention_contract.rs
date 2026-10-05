// adversarial-audit: retired-operation witness is bounded to MAX_RETIRED_EPOCHS=2;
// a retry of an operation retired three epochs ago must still fail with `operation_expired`
// instead of being admitted as a brand-new command and re-executed
//!
//! Hypothesis under audit: `JournalLifecycle.retired` keeps only the two most recent sealed
//! epochs (`intent.rs` `MAX_RETIRED_EPOCHS`), and `JournalLifecycle::is_retired` consults only
//! that vec — the `retired_through` watermark is validated for continuity but never used to
//! reject a forgotten id. After a third seal, a retry naming an epoch-1 operation id reaches
//! `record_pending` as `None` and is re-executed, violating the declared invariant
//! "Retired → `OperationExpired`, 不重新执行".
//!
//! If this test fails because the create succeeds, the eviction hole is real.

#[cfg(test)]
mod tests {
    use std::{fmt::Debug, fs, sync::Arc};

    use lomo_application::{CreateMemoRequest, WorkspaceSession, WorkspaceSessionConfig};
    use lomo_core::{CapabilityToken, OperationId, PlatformActionExecutor, RelativeWorkspacePath};
    use lomo_platform_fs::FsPlatformActionExecutor;
    use lomo_workspace::WorkspaceRootId;

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
        executor: Arc<dyn PlatformActionExecutor>,
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
                media_stage_root: temp.path().join("media-stage"),
            };
            let fs_executor = FsPlatformActionExecutor::new(&config.exchange_dir).value();
            fs_executor
                .bind_root(capability, temp.path().join("notes"))
                .value();
            let executor: Arc<dyn PlatformActionExecutor> = Arc::new(fs_executor);
            Self {
                temp,
                config,
                executor,
            }
        }

        fn open(&self) -> WorkspaceSession {
            WorkspaceSession::open(self.config.clone(), Arc::clone(&self.executor)).value()
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

    /// Three seals evict the oldest witness (`MAX_RETIRED_EPOCHS` = 2). A retry of the evicted
    /// operation id must still be refused as expired rather than committed a second time.
    #[test]
    fn a_retry_older_than_the_witness_window_must_still_expire() {
        let fixture = Fixture::new();
        let session = fixture.open();
        let victim = Fixture::request("epoch-one-victim", "victim memo body");
        session.create_memo(victim.clone()).value();
        assert!(session.seal().value(), "first seal retires epoch one");
        session
            .create_memo(Fixture::request("epoch-two-filler", "filler two"))
            .value();
        assert!(session.seal().value(), "second seal retires epoch two");
        session
            .create_memo(Fixture::request("epoch-three-filler", "filler three"))
            .value();
        assert!(
            session.seal().value(),
            "third seal evicts epoch one's witness"
        );
        drop(session);

        let reopened = fixture.open();
        let before = fs::read_to_string(fixture.path()).value();
        let result = reopened.create_memo(victim);
        let Err(error) = result else {
            panic!(
                "adversarial finding confirmed: a retired operation whose witness was evicted \
                 re-executed instead of returning operation_expired; file now: {before:?}"
            );
        };
        assert_eq!(
            error.code(),
            "operation_expired",
            "evicted witness must still expire the retry, got {error:?}"
        );
    }
}
