//! Behavior Contract
//!
//! Capability: durable `FeasibilityProbe` journal with atomic publish, constrained ids, and
//! batch→action structure.
//!
//! Scenarios:
//! - Given cancel then complete, when reopened, then complete stays cancelled.
//! - Given batch + action + confirm, when reopened, then batch replays and action skips.
//! - Given id with newline/whitespace, when applied, then Invalid (no journal injection).
//! - Given corrupt / inconsistent journal, when open runs, then fail closed.
//!
//! Observable outcomes: durable cancel/replay/skip, Invalid on bad ids and corrupt journals.
//! Excludes: production APK packaging of this feature.

#[cfg(test)]
mod tests {
    #[cfg(feature = "feasibility-probe")]
    #[test]
    fn durable_cancel_batch_action_survive_reopen() {
        use std::sync::Arc;
        use std::sync::atomic::{AtomicU64, Ordering};
        use std::time::{SystemTime, UNIX_EPOCH};

        use lomo_native::feasibility_probe::{
            FeasibilityProbe, FeasibilityProbeError, FeasibilityProbeListener,
        };

        struct CountingListener {
            last: AtomicU64,
        }
        impl FeasibilityProbeListener for CountingListener {
            fn on_revision(&self, revision: u64) {
                self.last.store(revision, Ordering::SeqCst);
            }
        }

        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("time")
            .as_nanos();
        let journal = std::env::temp_dir().join(format!("lomo-probe-journal-{nanos}.log"));
        let probe = FeasibilityProbe::open(journal.display().to_string()).expect("open journal");
        let listener = Arc::new(CountingListener {
            last: AtomicU64::new(0),
        });
        probe
            .add_listener(Arc::clone(&listener) as Arc<dyn FeasibilityProbeListener>)
            .expect("listener");
        assert_eq!(probe.bump_revision().expect("bump"), 1);
        assert_eq!(listener.last.load(Ordering::SeqCst), 1);

        probe.cancel("op-1".to_owned()).expect("cancel");
        let cancelled = probe
            .complete_operation("op-1".to_owned())
            .expect_err("cancelled");
        assert!(matches!(
            cancelled,
            FeasibilityProbeError::Cancelled { operation_id } if operation_id == "op-1"
        ));

        assert_eq!(
            probe
                .submit_platform_batch("batch-9".to_owned())
                .expect("accept"),
            "accepted:batch-9"
        );
        assert_eq!(
            probe
                .apply_action("batch-9".to_owned(), "saf:doc:digest".to_owned())
                .expect("apply"),
            "applied:saf:doc:digest"
        );
        assert_eq!(
            probe
                .confirm_platform_batch("batch-9".to_owned())
                .expect("confirm"),
            "confirmed:batch-9"
        );
        assert_eq!(
            probe
                .submit_platform_batch("batch-crash".to_owned())
                .expect("accept crash"),
            "accepted:batch-crash"
        );
        probe.shutdown().expect("shutdown");

        let recovered =
            FeasibilityProbe::open(journal.display().to_string()).expect("reopen journal");
        let still_cancelled = recovered
            .complete_operation("op-1".to_owned())
            .expect_err("cancel must survive reopen");
        assert!(matches!(
            still_cancelled,
            FeasibilityProbeError::Cancelled { operation_id } if operation_id == "op-1"
        ));
        assert_eq!(
            recovered
                .submit_platform_batch("batch-9".to_owned())
                .expect("replay confirmed"),
            "replayed:batch-9"
        );
        assert_eq!(
            recovered
                .apply_action("batch-9".to_owned(), "saf:doc:digest".to_owned())
                .expect("skip"),
            "skipped:saf:doc:digest"
        );
        assert_eq!(
            recovered
                .submit_platform_batch("batch-crash".to_owned())
                .expect("re-accept unconfirmed"),
            "accepted:batch-crash"
        );
        recovered.shutdown().expect("shutdown recovered");
        drop(std::fs::remove_file(journal));
    }

    #[cfg(feature = "feasibility-probe")]
    #[test]
    fn id_injection_and_empty_confirm_are_rejected() {
        use std::time::{SystemTime, UNIX_EPOCH};

        use lomo_native::feasibility_probe::{FeasibilityProbe, FeasibilityProbeError};

        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("time")
            .as_nanos();
        let journal = std::env::temp_dir().join(format!("lomo-probe-id-{nanos}.log"));
        let probe = FeasibilityProbe::open(journal.display().to_string()).expect("open");
        probe
            .submit_platform_batch("batch-a".to_owned())
            .expect("submit");
        let injected = probe.apply_action("batch-a".to_owned(), "safe\nC forged-batch".to_owned());
        assert!(matches!(
            injected,
            Err(FeasibilityProbeError::Invalid { .. })
        ));
        let empty_confirm = probe.confirm_platform_batch("batch-a".to_owned());
        assert!(matches!(
            empty_confirm,
            Err(FeasibilityProbeError::Invalid { .. })
        ));
        // Memory must not observe injected action after failed apply.
        assert_eq!(
            probe
                .apply_action("batch-a".to_owned(), "safe-action".to_owned())
                .expect("apply real"),
            "applied:safe-action"
        );
        probe.shutdown().expect("shutdown");
        drop(std::fs::remove_file(journal));
    }

    #[cfg(feature = "feasibility-probe")]
    #[test]
    fn corrupt_and_inconsistent_journals_fail_closed() {
        use std::time::{SystemTime, UNIX_EPOCH};

        use lomo_native::feasibility_probe::{FeasibilityProbe, FeasibilityProbeError};

        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("time")
            .as_nanos();
        let bad = std::env::temp_dir().join(format!("lomo-probe-corrupt-{nanos}.log"));
        std::fs::write(
            &bad,
            "# lomo feasibility batch journal v1\nschema 1\nU ok-batch\nGARBAGE without-kind\n",
        )
        .expect("write");
        match FeasibilityProbe::open(bad.display().to_string()) {
            Ok(_) => panic!("corrupt journal must fail closed"),
            Err(error) => assert!(matches!(error, FeasibilityProbeError::Invalid { .. })),
        }
        drop(std::fs::remove_file(bad));

        let conflict = std::env::temp_dir().join(format!("lomo-probe-conflict-{nanos}.log"));
        std::fs::write(
            &conflict,
            "# lomo feasibility batch journal v1\nschema 1\nU both\nC both\n",
        )
        .expect("write");
        match FeasibilityProbe::open(conflict.display().to_string()) {
            Ok(_) => panic!("U/C overlap must fail closed"),
            Err(error) => assert!(matches!(error, FeasibilityProbeError::Invalid { .. })),
        }
        drop(std::fs::remove_file(conflict));

        let empty = std::env::temp_dir().join(format!("lomo-probe-empty-{nanos}.log"));
        std::fs::write(&empty, "").expect("write empty");
        match FeasibilityProbe::open(empty.display().to_string()) {
            Ok(_) => panic!("empty journal file must fail closed"),
            Err(error) => assert!(matches!(error, FeasibilityProbeError::Invalid { .. })),
        }
        drop(std::fs::remove_file(empty));

        let header_only = std::env::temp_dir().join(format!("lomo-probe-header-only-{nanos}.log"));
        std::fs::write(&header_only, "# lomo feasibility batch journal v1\n").expect("write");
        match FeasibilityProbe::open(header_only.display().to_string()) {
            Ok(_) => panic!("header without schema must fail closed"),
            Err(error) => assert!(matches!(error, FeasibilityProbeError::Invalid { .. })),
        }
        drop(std::fs::remove_file(header_only));

        let empty_confirm = std::env::temp_dir().join(format!("lomo-probe-empty-c-{nanos}.log"));
        std::fs::write(
            &empty_confirm,
            "# lomo feasibility batch journal v1\nschema 1\nC orphan-confirm\n",
        )
        .expect("write");
        match FeasibilityProbe::open(empty_confirm.display().to_string()) {
            Ok(_) => panic!("confirmed batch without actions must fail closed"),
            Err(error) => assert!(matches!(error, FeasibilityProbeError::Invalid { .. })),
        }
        drop(std::fs::remove_file(empty_confirm));
    }

    #[cfg(feature = "feasibility-probe")]
    #[test]
    fn invalid_page_cursor_fails_closed() {
        use lomo_native::feasibility_probe::{FeasibilityProbe, FeasibilityProbeError};

        let probe = FeasibilityProbe::new();
        let bad = probe.list_page(Some("not-a-number".to_owned()), 8);
        assert!(matches!(bad, Err(FeasibilityProbeError::Invalid { .. })));
        let empty = probe.list_page(Some(String::new()), 8);
        assert!(matches!(empty, Err(FeasibilityProbeError::Invalid { .. })));
        let ok = probe
            .list_page(Some("16".to_owned()), 4)
            .expect("decimal cursor");
        assert_eq!(ok.items.len(), 4);
        assert_eq!(ok.items[0], "item-16");
        let overflow = probe.list_page(Some(usize::MAX.to_string()), 8);
        assert!(matches!(
            overflow,
            Err(FeasibilityProbeError::Invalid { reason }) if reason.contains("overflow")
        ));
    }

    #[cfg(feature = "feasibility-probe")]
    #[test]
    fn reentrant_listener_does_not_deadlock_on_bump() {
        use std::sync::Arc;
        use std::sync::atomic::{AtomicU64, Ordering};

        use lomo_native::feasibility_probe::{FeasibilityProbe, FeasibilityProbeListener};

        struct ReentrantListener {
            count: AtomicU64,
            probe: Arc<FeasibilityProbe>,
        }
        impl FeasibilityProbeListener for ReentrantListener {
            fn on_revision(&self, _revision: u64) {
                let n = self.count.fetch_add(1, Ordering::SeqCst);
                // One nested bump only — proves mutex is not held across callback.
                if n == 0 {
                    let _nested: Result<u64, _> = self.probe.bump_revision();
                }
            }
        }

        let probe = FeasibilityProbe::new();
        let listener = Arc::new(ReentrantListener {
            count: AtomicU64::new(0),
            probe: Arc::clone(&probe),
        });
        probe
            .add_listener(listener as Arc<dyn FeasibilityProbeListener>)
            .expect("listener");
        assert_eq!(probe.bump_revision().expect("outer bump"), 1);
        // Outer + nested revisions.
        assert!(probe.revision().expect("rev") >= 2);
    }

    #[cfg(not(feature = "feasibility-probe"))]
    #[test]
    fn feasibility_probe_feature_is_disabled_by_default() {
        assert!(!cfg!(feature = "feasibility-probe"));
    }
}
