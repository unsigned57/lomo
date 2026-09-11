//! Behavior Contract (P5-03 pipeline types)
//!
//! Capability: public five-stage pipeline types validate paths/digests/page limits and expose
//! observable intent counts without provider-specific planners.
//!
//! Scenarios:
//! - Given a shipped owner identity, when validated, then ok; forged name fails closed.
//! - Given absolute or `..` paths, when `SyncPath::parse` runs, then validation fails.
//! - Given a complete remote snapshot over the page limit, when built, then resource-limit fails.
//! - Given prepared batch intents, when counted, then EnsureAbsent/EnsurePresent/OpenConflict are
//!   observable.
//!
//! Observable outcomes: parse errors, page limits, intent counts, owner identity.
//! Excludes: production DI, provider adapters, `WorkManager`.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_core::ErrorCategory;
    use lomo_sync::{
        BatchAtomicity, ContentDigest, MAX_ACTION_PAGE_ITEMS, PreparedRemoteBatch,
        ProviderNeutralIntent, RemotePathEntry, RemoteSnapshot, SYNC_CRATE_NAME,
        SnapshotCompleteness, SyncOwnerIdentity, SyncPath, error_category,
    };

    fn digest(seed: u8) -> ContentDigest {
        let hex = format!("{seed:02x}").repeat(32);
        ContentDigest::parse(&hex).expect("digest")
    }

    #[test]
    fn owner_identity_matches_shipped_scaffold() {
        let identity = SyncOwnerIdentity::current();
        assert_eq!(identity.crate_name, "lomo-sync");
        assert_eq!(identity.crate_name, SYNC_CRATE_NAME);
        identity
            .validate()
            .expect("shipped owner identity must validate");
    }

    #[test]
    fn forged_owner_identity_fails_closed() {
        let wrong = SyncOwnerIdentity {
            crate_name: "not-lomo-sync",
        };
        let error = wrong.validate().expect_err("forged crate name must fail");
        assert_eq!(error.category(), ErrorCategory::Validation);
        assert_eq!(error.code(), "invalid_sync_owner");
    }

    #[test]
    fn sync_path_rejects_absolute_and_parent_segments() {
        for bad in ["/abs", "a/../b", "", "a//b"] {
            let err = SyncPath::parse(bad).expect_err("must reject");
            assert_eq!(error_category(&err), ErrorCategory::Validation);
        }
        let ok = SyncPath::parse("memo/a.md").expect("relative path");
        assert_eq!(ok.as_str(), "memo/a.md");
    }

    #[test]
    fn remote_snapshot_page_limit_fails_closed() {
        let mut entries = Vec::new();
        for index in 0..=MAX_ACTION_PAGE_ITEMS {
            entries.push(RemotePathEntry {
                path: SyncPath::parse(&format!("p/{index}")).expect("path"),
                digest: digest(1),
                revision_token: "t".to_owned(),
            });
        }
        let err = RemoteSnapshot::new(SnapshotCompleteness::Complete, entries)
            .expect_err("over page limit");
        assert_eq!(err.category(), ErrorCategory::ResourceLimit);
        assert_eq!(err.code(), "remote_snapshot_page_too_large");
    }

    #[test]
    fn prepared_batch_intent_counts_are_observable() {
        let batch = PreparedRemoteBatch::new(
            BatchAtomicity::PerPath,
            vec![
                ProviderNeutralIntent::EnsurePresent {
                    path: SyncPath::parse("memo/a.md").expect("path"),
                    digest: digest(2),
                    expected_remote_token: None,
                },
                ProviderNeutralIntent::EnsureAbsent {
                    path: SyncPath::parse("memo/b.md").expect("path"),
                    expected_remote_token: "e".to_owned(),
                },
                ProviderNeutralIntent::OpenConflict {
                    path: SyncPath::parse("memo/c.md").expect("path"),
                    local_digest: digest(3),
                    remote_digest: digest(4),
                    baseline_digest: None,
                },
            ],
        )
        .expect("batch");
        assert_eq!(batch.ensure_present_count(), 1);
        assert_eq!(batch.ensure_absent_count(), 1);
        assert_eq!(batch.open_conflict_count(), 1);
    }

    #[test]
    fn prepared_batch_page_limit_fails_closed() {
        // P5-11 host slice: plan-size bound is fail-closed (never clamp).
        let mut intents = Vec::new();
        for index in 0..=MAX_ACTION_PAGE_ITEMS {
            intents.push(ProviderNeutralIntent::EnsurePresent {
                path: SyncPath::parse(&format!("p/{index}")).expect("path"),
                digest: digest(1),
                expected_remote_token: None,
            });
        }
        let err = PreparedRemoteBatch::new(BatchAtomicity::PerPath, intents)
            .expect_err("over page limit");
        assert_eq!(err.category(), ErrorCategory::ResourceLimit);
        assert_eq!(err.code(), "prepared_batch_page_too_large");
    }

    #[test]
    fn remote_snapshot_at_exact_page_limit_is_accepted() {
        // Differential host fixture: exact MAX_ACTION_PAGE_ITEMS is legal; +1 is not (above).
        let mut entries = Vec::new();
        for index in 0..MAX_ACTION_PAGE_ITEMS {
            entries.push(RemotePathEntry {
                path: SyncPath::parse(&format!("ok/{index}")).expect("path"),
                digest: digest(2),
                revision_token: "t".to_owned(),
            });
        }
        let snap = RemoteSnapshot::new(SnapshotCompleteness::Complete, entries).expect("at limit");
        assert_eq!(snap.entries.len(), MAX_ACTION_PAGE_ITEMS);
    }

    #[test]
    fn plan_intents_fails_closed_when_compiled_batch_exceeds_page_limit() {
        // Host-scale differential: many local-only paths compile to EnsurePresent and hit the same
        // prepared-batch ceiling (streaming 10k–100k remains OPEN / later P5-11; this proves the
        // bound is enforced at plan, not only at snapshot construction).
        use lomo_sync::{
            BaselineHead, LocalPathEntry, LocalSnapshot, SessionKind, TombstoneSet, plan_intents,
        };
        let mut local_entries = Vec::new();
        for index in 0..=MAX_ACTION_PAGE_ITEMS {
            local_entries.push(LocalPathEntry {
                path: SyncPath::parse(&format!("memo/{index}.md")).expect("path"),
                digest: digest(3),
            });
        }
        let local = LocalSnapshot {
            entries: local_entries,
            workspace_generation: None,
        };
        let remote =
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("empty remote");
        let err = plan_intents(
            SessionKind::Incremental,
            &local,
            &remote,
            &BaselineHead::empty(),
            &TombstoneSet::empty(),
        )
        .expect_err("plan over page");
        assert_eq!(error_category(&err), ErrorCategory::ResourceLimit);
        assert_eq!(err.code(), "prepared_batch_page_too_large");
    }
}
