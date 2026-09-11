//! Behavior Contract
//!
//! Capability: a completed job result is transferred exactly once from the durable engine journal
//! to its owning boundary.
//!
//! Scenario:
//! - Given a completed user job with a durable result, when the owner takes the result, then the
//!   exact payload is returned and the terminal job is removed from the durable journal.
//! - Given the same id after transfer, when result access is attempted again, then `unknown_job`
//!   is surfaced instead of replaying an already-owned command or scan result.
//!
//! Observable outcomes: returned payload, structured second-access error, persisted journal bytes.
//! TDD proof: RED on 2026-08-17 because `read_job_result` cloned terminal payloads forever.
//! Excludes: platform I/O and native result decoding.

#[cfg(test)]
#[path = "support/failure.rs"]
mod failure_support;
#[cfg(test)]
#[path = "support/option.rs"]
mod option_support;
#[cfg(test)]
#[path = "support/success.rs"]
mod support;

#[cfg(test)]
mod tests {
    use std::fs;
    use std::sync::Arc;
    use std::time::Duration;

    use lomo_core::{
        DriverAdvance, DriverStart, EngineConfig, JobDriver, JobDriverContext, JobDriverRegistry,
        LomoEngine, PlatformActionBatch, PlatformBatchResult, WorkspaceDescriptor,
    };
    use tempfile::tempdir;

    use super::failure_support::ResultFailureTestExt;
    use super::option_support::OptionTestExt;
    use super::support::ResultTestExt;

    struct CompletedProbeDriver;

    impl JobDriver for CompletedProbeDriver {
        fn kind(&self) -> &'static str {
            "completed-probe-v1"
        }

        fn start(
            &self,
            _ctx: &mut JobDriverContext<'_>,
            _request_json: &str,
        ) -> Result<DriverStart, lomo_core::LomoError> {
            Ok(DriverStart {
                state_json: "{}".to_owned(),
                actions: Vec::new(),
                result_json: Some(r#"{"transfer":"once"}"#.to_owned()),
            })
        }

        fn advance(
            &self,
            _ctx: &mut JobDriverContext<'_>,
            _state_json: &str,
            _batch: &PlatformActionBatch,
            _result: &PlatformBatchResult,
        ) -> Result<DriverAdvance, lomo_core::LomoError> {
            unreachable!("completed probe publishes without platform work")
        }
    }

    #[test]
    fn completed_result_is_transferred_once_and_removed_from_journal() {
        let temporary = tempdir().must_succeed("temporary root");
        let control = temporary.path().join("control");
        let exchange = temporary.path().join("exchange");
        let workspace = temporary.path().join("workspace");
        fs::create_dir(&control).must_succeed("control root");
        fs::create_dir(&exchange).must_succeed("exchange root");
        fs::create_dir(&workspace).must_succeed("workspace root");
        let descriptor =
            WorkspaceDescriptor::direct(workspace).must_succeed("workspace descriptor");
        let config = EngineConfig::new(&control, exchange, Some(descriptor))
            .must_succeed("engine config")
            .with_drivers(JobDriverRegistry::new(vec![Arc::new(CompletedProbeDriver)]));
        let journal_path = config.journal_path().must_succeed("journal path");
        let engine = LomoEngine::open(config).must_succeed("open");

        let job_id = engine
            .start_user_job("completed-probe-v1", "{}", Duration::from_secs(30))
            .must_succeed("start completed job");
        let payload = engine
            .take_job_result(&job_id)
            .must_succeed("take result")
            .must_succeed("completed payload");

        assert_eq!(payload, r#"{"transfer":"once"}"#);
        let second = engine
            .take_job_result(&job_id)
            .must_fail("result is single-owner");
        assert_eq!(second.code(), "unknown_job");
        let journal = fs::read_to_string(journal_path).must_succeed("read journal");
        assert!(!journal.contains("transfer"));
    }
}
