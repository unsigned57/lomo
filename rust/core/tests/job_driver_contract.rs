//! Behavior Contract
//!
//! Capability: job driver kinds, registry, and driver context allocate identifiers and expose
//! workspace capability without performing platform I/O.
//!
//! Scenarios:
//! - Given a valid kind string, when parsed, then `JobDriverKind` round-trips via `as_str`.
//! - Given empty/oversized kind, when parsed, then validation fails closed.
//! - Given a registry of drivers, when `get`/`is_empty`/Debug run, then kinds are discoverable.
//! - Given a driver context, when next action/batch ids are allocated, then they are unique and
//!   capability follows SAF vs Direct workspace mode.
//! - Given a probe driver, when `start` runs, then durable result JSON is returned.
//!
//! Observable outcomes: parsed kinds, registry membership, structured validation errors, ids.
//! TDD proof: fails when registry/context helpers are missing or capability mapping regresses.
//! Excludes: real engine job execution, platform I/O, SAF execution.

#[cfg(test)]
#[path = "support/failure.rs"]
mod failure_support;
#[cfg(test)]
#[path = "support/success.rs"]
mod support;

#[cfg(test)]
#[expect(
    clippy::cognitive_complexity,
    reason = "contract scenarios intentionally exercise multiple observable outcomes per test"
)]
mod tests {
    use std::path::Path;
    use std::sync::Arc;

    use lomo_core::{
        CapabilityToken, DriverAdvance, DriverStart, ErrorCategory, JobDriver, JobDriverContext,
        JobDriverKind, JobDriverRegistry, JobId, LomoError, PlatformActionBatch,
        PlatformBatchResult, WorkspaceDescriptor, job_driver_context,
    };
    use tempfile::tempdir;

    use super::failure_support::ResultFailureTestExt;
    use super::support::ResultTestExt;

    struct ProbeDriver;

    impl JobDriver for ProbeDriver {
        fn kind(&self) -> &'static str {
            "probe-driver-v1"
        }

        fn start(
            &self,
            _ctx: &mut JobDriverContext<'_>,
            _request_json: &str,
        ) -> Result<DriverStart, LomoError> {
            Ok(DriverStart {
                state_json: "{}".to_owned(),
                actions: Vec::new(),
                result_json: Some(r#"{"ok":true}"#.to_owned()),
            })
        }

        fn advance(
            &self,
            _ctx: &mut JobDriverContext<'_>,
            _state_json: &str,
            _batch: &PlatformActionBatch,
            _result: &PlatformBatchResult,
        ) -> Result<DriverAdvance, LomoError> {
            Ok(DriverAdvance::Done {
                result_json: r#"{"done":true}"#.to_owned(),
            })
        }
    }

    #[test]
    fn job_driver_kind_parse_and_registry_membership() {
        let kind = JobDriverKind::parse("workspace-scan-v1").must_succeed("kind");
        assert_eq!(kind.as_str(), "workspace-scan-v1");

        let empty = JobDriverKind::parse("").must_fail("empty kind");
        assert_eq!(empty.category(), ErrorCategory::Validation);
        assert_eq!(empty.code(), "invalid_job_driver_kind");

        let too_long = JobDriverKind::parse(&"k".repeat(129)).must_fail("oversized");
        assert_eq!(too_long.code(), "invalid_job_driver_kind");

        let empty_registry = JobDriverRegistry::default();
        assert!(empty_registry.is_empty());
        assert!(empty_registry.get("probe-driver-v1").is_none());
        assert!(format!("{empty_registry:?}").contains("JobDriverRegistry"));

        let registry = JobDriverRegistry::new(vec![Arc::new(ProbeDriver)]);
        assert!(!registry.is_empty());
        match registry.get("probe-driver-v1") {
            Some(found) => assert_eq!(found.kind(), "probe-driver-v1"),
            None => panic!("driver present"),
        }
        assert!(registry.get("missing").is_none());
        assert!(format!("{registry:?}").contains("probe-driver-v1"));
    }

    #[test]
    fn driver_context_allocates_ids_and_capability() {
        let temporary = tempdir().must_succeed("temp");
        let exchange = temporary.path().join("exchange");
        std::fs::create_dir_all(&exchange).must_succeed("exchange");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(&workspace).must_succeed("ws");
        let job_id = JobId::parse("job-probe-1").must_succeed("job");
        let mut counter = 0_u64;

        let direct = WorkspaceDescriptor::direct(&workspace).must_succeed("direct");
        {
            let mut ctx = job_driver_context(
                &job_id,
                Path::new(&exchange),
                &direct,
                1_000,
                0,
                &mut counter,
            );
            let action_a = ctx.next_action_id("read").must_succeed("action a");
            let action_b = ctx.next_action_id("write").must_succeed("action b");
            assert_ne!(action_a.as_str(), action_b.as_str());
            let batch_id = ctx.next_batch_id().must_succeed("batch");
            assert!(batch_id.as_str().starts_with("batch-"));
            assert_eq!(ctx.capability().as_str(), "direct-root");
        }
        assert_eq!(counter, 3);

        let saf_token = CapabilityToken::parse("saf-cap-token-xyz").must_succeed("saf token");
        let saf = WorkspaceDescriptor::saf(saf_token);
        let mut counter2 = 10_u64;
        let mut saf_ctx =
            job_driver_context(&job_id, Path::new(&exchange), &saf, 2_000, 1, &mut counter2);
        assert_eq!(saf_ctx.capability().as_str(), "saf-cap-token-xyz");
        assert_eq!(saf_ctx.deadline_epoch_millis, 2_000);
        assert_eq!(saf_ctx.attempt, 1);
        assert_eq!(saf_ctx.job_id.as_str(), "job-probe-1");
        assert_eq!(saf_ctx.exchange_root, exchange.as_path());

        let start = ProbeDriver.start(&mut saf_ctx, "{}").must_succeed("start");
        assert_eq!(start.state_json, "{}");
        assert!(start.actions.is_empty());
        assert_eq!(start.result_json.as_deref(), Some(r#"{"ok":true}"#));
        assert!(matches!(
            format!("{start:?}"),
            s if s.contains("DriverStart")
        ));
        let done = DriverAdvance::Done {
            result_json: r#"{"done":true}"#.to_owned(),
        };
        assert!(matches!(
            format!("{done:?}"),
            s if s.contains("done")
        ));
        let needs = DriverAdvance::NeedsBatch {
            state_json: "{}".to_owned(),
            actions: Vec::new(),
            result_json: None,
        };
        assert!(matches!(
            format!("{needs:?}"),
            s if s.contains("NeedsBatch")
        ));
    }
}
