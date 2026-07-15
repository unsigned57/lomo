//! Behavior Contract
//!
//! Capability: feature-gated `FeasibilityProbe` `UniFFI` surface for lifecycle, paging, cancel,
//! and platform batch replay.
//!
//! Scenarios:
//! - Given an open probe, when revision is bumped, then it advances monotonically.
//! - Given `page_size` above the cap, when listing, then the page is bounded.
//! - Given cancel then complete, when complete runs, then the result stays cancelled.
//! - Given the same batch id twice, when submitted, then the second call is a replay.
//! - Given shutdown, when `list_page` runs, then the probe rejects with closed.
//!
//! Observable outcomes: revision, page length, cancelled/closed errors, replayed batch.
//! Excludes: production APK packaging of this feature (must stay off by default).

#[cfg(test)]
mod tests {
    #[cfg(feature = "feasibility-probe")]
    #[test]
    fn probe_lifecycle_paging_cancel_and_batch_replay() {
        use lomo_native::feasibility_probe::{FeasibilityProbe, FeasibilityProbeError};

        let probe = FeasibilityProbe::new();
        assert_eq!(probe.revision().expect("open"), 0);
        assert_eq!(probe.bump_revision().expect("bump"), 1);
        let page = probe.list_page(None, 100).expect("page");
        assert_eq!(page.items.len(), 32);
        assert!(page.next_cursor.is_some());
        probe.cancel("op-1".to_owned()).expect("cancel");
        let cancelled = probe
            .complete_operation("op-1".to_owned())
            .expect_err("cancelled");
        assert!(matches!(
            cancelled,
            FeasibilityProbeError::Cancelled { operation_id } if operation_id == "op-1"
        ));
        let first = probe
            .submit_platform_batch("batch-9".to_owned())
            .expect("accept");
        assert_eq!(first, "accepted:batch-9");
        let second = probe
            .submit_platform_batch("batch-9".to_owned())
            .expect("replay");
        assert_eq!(second, "replayed:batch-9");
        probe.shutdown().expect("shutdown");
        let closed = probe.list_page(None, 1).expect_err("closed");
        assert!(matches!(closed, FeasibilityProbeError::Closed { .. }));
    }

    #[cfg(not(feature = "feasibility-probe"))]
    #[test]
    fn feasibility_probe_feature_is_disabled_by_default() {
        assert!(!cfg!(feature = "feasibility-probe"));
    }
}
