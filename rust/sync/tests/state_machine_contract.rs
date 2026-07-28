//! Behavior Contract (P5-03 unified state machine)
//!
//! Capability: hermetic first-takeover, partial listing, and verify-before-baseline rules on
//! fake local/remote ports without production DI.
//!
//! Scenarios:
//! - Given first-takeover session, when preflight plans, then no `EnsureAbsent` / user-file deletes;
//!   ensure-present / pull / conflict may appear.
//! - Given partial (`Incomplete`) remote listing with a baseline path missing, when planned, then
//!   no `EnsureAbsent`.
//! - Given apply then verify failure, when the cycle ends, then baseline does not advance and
//!   durable baseline file (if any) stays unchanged.
//! - Given apply + all-verified, when the cycle ends, then baseline advances for verified paths.
//! - Given same-byte local/remote on first-takeover, when verified, then baseline may establish.
//!
//! Observable outcomes: intent counts, `baseline_advanced`, durable baseline entries, verify flags.
//! Excludes: WebDAV/S3/Git adapters, store mutation ports, production registry.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_sync::{
        BaselineHead, ContentDigest, FakeLocalPort, FakeRemotePort, LocalPathEntry, LocalSyncPort,
        PathPublishStatus, PublishReceipt, RemotePathEntry, RemoteSnapshot, SessionKind,
        SnapshotCompleteness, SyncIdentityFence, SyncPath, SyncPaths, SyncSession, TombstoneSet,
        VerifiedRemoteState, VerifyStatus, apply_with_verify, first_takeover_preflight,
        plan_intents, run_sync_cycle,
    };
    use lomo_workspace::{RemoteDatasetId, RemoteIdentityDigest, WorkspaceGenerationId};
    use tempfile::tempdir;

    fn dig(seed: u8) -> ContentDigest {
        ContentDigest::parse(&format!("{seed:02x}").repeat(32)).expect("digest")
    }

    fn path(raw: &str) -> SyncPath {
        SyncPath::parse(raw).expect("path")
    }

    fn fence() -> SyncIdentityFence {
        SyncIdentityFence::from_parts(
            &WorkspaceGenerationId::parse(&"ab".repeat(32)).expect("gen"),
            &RemoteDatasetId::parse("ds").expect("ds"),
            &RemoteIdentityDigest::parse(&"cd".repeat(32)).expect("id"),
        )
    }

    #[test]
    fn first_takeover_preflight_emits_no_user_file_deletes() {
        let local = FakeLocalPort {
            entries: vec![LocalPathEntry {
                path: path("memo/local-only.md"),
                digest: dig(1),
            }],
        };
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(
                SnapshotCompleteness::Complete,
                vec![RemotePathEntry {
                    path: path("memo/remote-only.md"),
                    digest: dig(2),
                    revision_token: "r1".to_owned(),
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
        let (_session, result) =
            first_takeover_preflight(fence(), "s1", &local, &remote).expect("preflight");
        assert_eq!(result.batch.ensure_absent_count(), 0);
        assert!(
            result.batch.ensure_present_count() >= 1,
            "local-only should ensure present: {:?}",
            result.batch
        );
        assert!(
            result
                .batch
                .intents
                .iter()
                .any(|i| matches!(i, lomo_sync::ProviderNeutralIntent::PullPresent { .. })),
            "remote-only should pull: {:?}",
            result.batch
        );
        assert!(!result.baseline_advanced);
    }

    #[test]
    fn partial_listing_never_emits_ensure_absent() {
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/a.md"), &dig(1), "tok-a".to_owned());
        let local = FakeLocalPort {
            entries: Vec::new(),
        };
        // Incomplete listing: path a missing from remote view — must NOT delete.
        let remote_snap =
            RemoteSnapshot::new(SnapshotCompleteness::Incomplete, Vec::new()).expect("snap");
        let batch = plan_intents(
            SessionKind::Incremental,
            &local.snapshot().expect("local"),
            &remote_snap,
            &baseline,
            &TombstoneSet::empty(),
        )
        .expect("plan");
        assert_eq!(batch.ensure_absent_count(), 0);
    }

    #[test]
    fn complete_listing_may_emit_ensure_absent_when_baseline_established() {
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/a.md"), &dig(1), "tok-a".to_owned());
        let local = FakeLocalPort {
            entries: Vec::new(),
        };
        let remote_snap =
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap");
        let batch = plan_intents(
            SessionKind::Incremental,
            &local.snapshot().expect("local"),
            &remote_snap,
            &baseline,
            &TombstoneSet::empty(),
        )
        .expect("plan");
        assert_eq!(batch.ensure_absent_count(), 1);
    }

    #[test]
    fn first_takeover_never_emits_ensure_absent_even_with_complete_listing() {
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/a.md"), &dig(1), "tok-a".to_owned());
        let local = FakeLocalPort {
            entries: Vec::new(),
        };
        let remote_snap =
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap");
        let batch = plan_intents(
            SessionKind::FirstTakeover,
            &local.snapshot().expect("local"),
            &remote_snap,
            &baseline,
            &TombstoneSet::empty(),
        )
        .expect("plan");
        assert_eq!(batch.ensure_absent_count(), 0);
    }

    #[test]
    fn verify_failure_leaves_baseline_unchanged() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "s-verify-fail").expect("session");
        let local = FakeLocalPort {
            entries: vec![LocalPathEntry {
                path: path("memo/a.md"),
                digest: dig(5),
            }],
        };
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
            PublishReceipt {
                path_results: vec![(
                    path("memo/a.md"),
                    PathPublishStatus::Applied {
                        new_token: "n1".to_owned(),
                    },
                )],
            },
            VerifiedRemoteState {
                results: vec![VerifyStatus::Failed {
                    path: path("memo/a.md"),
                    code: "etag_mismatch".to_owned(),
                }],
            },
        );
        let result = apply_with_verify(
            &paths,
            &session,
            &local,
            &remote,
            BaselineHead::empty(),
            None,
        )
        .expect("cycle");
        assert!(!result.baseline_advanced);
        assert!(!result.baseline.is_established());
        assert!(result.baseline.entries.is_empty());
        // Durable baseline was never written (still missing / empty).
        let on_disk = lomo_sync::read_baseline(&paths).expect("read");
        assert!(on_disk.entries.is_empty());
        assert_eq!(remote.verify_call_count(), 1);
        assert_eq!(remote.publish_call_count(), 1);
    }

    #[test]
    fn verify_success_advances_baseline() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "s-verify-ok").expect("session");
        let local = FakeLocalPort {
            entries: vec![LocalPathEntry {
                path: path("memo/a.md"),
                digest: dig(7),
            }],
        };
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
            PublishReceipt {
                path_results: vec![(
                    path("memo/a.md"),
                    PathPublishStatus::Applied {
                        new_token: "n2".to_owned(),
                    },
                )],
            },
            VerifiedRemoteState {
                results: vec![VerifyStatus::Verified {
                    path: path("memo/a.md"),
                    digest: dig(7),
                    remote_token: "n2".to_owned(),
                }],
            },
        );
        let result = apply_with_verify(
            &paths,
            &session,
            &local,
            &remote,
            BaselineHead::empty(),
            None,
        )
        .expect("cycle");
        assert!(result.baseline_advanced);
        assert!(result.baseline.is_established());
        assert_eq!(
            result.baseline.get("memo/a.md").map(|e| e.digest.as_str()),
            Some(dig(7).as_str())
        );
        let on_disk = lomo_sync::read_baseline(&paths).expect("read");
        assert_eq!(
            on_disk.get("memo/a.md").map(|e| e.digest.as_str()),
            Some(dig(7).as_str())
        );
    }

    #[test]
    fn unproven_overlap_opens_durable_conflict_on_first_takeover() {
        let local = FakeLocalPort {
            entries: vec![LocalPathEntry {
                path: path("memo/a.md"),
                digest: dig(1),
            }],
        };
        let remote_snap = RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![RemotePathEntry {
                path: path("memo/a.md"),
                digest: dig(2),
                revision_token: "r".to_owned(),
            }],
        )
        .expect("snap");
        let batch = plan_intents(
            SessionKind::FirstTakeover,
            &local.snapshot().expect("local"),
            &remote_snap,
            &BaselineHead::empty(),
            &TombstoneSet::empty(),
        )
        .expect("plan");
        assert_eq!(batch.open_conflict_count(), 1);
        assert_eq!(batch.ensure_absent_count(), 0);
    }

    #[test]
    fn same_bytes_after_tombstone_emits_ensure_absent_when_delete_gates_pass() {
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        let mut tombstones = TombstoneSet::empty();
        tombstones.upsert("memo/gone.md", "ds", dig(3).as_str());
        let local = FakeLocalPort {
            entries: Vec::new(),
        };
        let remote_snap = RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![RemotePathEntry {
                path: path("memo/gone.md"),
                digest: dig(3),
                revision_token: "r-gone".to_owned(),
            }],
        )
        .expect("snap");
        let batch = plan_intents(
            SessionKind::Incremental,
            &local.snapshot().expect("local"),
            &remote_snap,
            &baseline,
            &tombstones,
        )
        .expect("plan");
        assert_eq!(batch.ensure_absent_count(), 1);
        assert_eq!(batch.open_conflict_count(), 0);
        assert!(
            !batch
                .intents
                .iter()
                .any(|i| matches!(i, lomo_sync::ProviderNeutralIntent::PullPresent { .. })),
            "tombstoned same-bytes must not pull: {batch:?}"
        );
    }

    #[test]
    fn different_bytes_after_tombstone_opens_conflict_not_pull() {
        let mut tombstones = TombstoneSet::empty();
        tombstones.upsert("memo/gone.md", "ds", dig(3).as_str());
        let local = FakeLocalPort {
            entries: Vec::new(),
        };
        let remote_snap = RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![RemotePathEntry {
                path: path("memo/gone.md"),
                digest: dig(9),
                revision_token: "r-new".to_owned(),
            }],
        )
        .expect("snap");
        let batch = plan_intents(
            SessionKind::Incremental,
            &local.snapshot().expect("local"),
            &remote_snap,
            &BaselineHead::empty(),
            &tombstones,
        )
        .expect("plan");
        assert_eq!(batch.open_conflict_count(), 1);
        assert_eq!(batch.ensure_absent_count(), 0);
        assert!(
            !batch
                .intents
                .iter()
                .any(|i| matches!(i, lomo_sync::ProviderNeutralIntent::PullPresent { .. })),
            "tombstoned different-bytes must not auto-pull: {batch:?}"
        );
    }

    #[test]
    fn first_takeover_tombstone_same_bytes_does_not_ensure_absent() {
        let mut tombstones = TombstoneSet::empty();
        tombstones.upsert("memo/gone.md", "ds", dig(3).as_str());
        let local = FakeLocalPort {
            entries: Vec::new(),
        };
        let remote_snap = RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![RemotePathEntry {
                path: path("memo/gone.md"),
                digest: dig(3),
                revision_token: "r-gone".to_owned(),
            }],
        )
        .expect("snap");
        let batch = plan_intents(
            SessionKind::FirstTakeover,
            &local.snapshot().expect("local"),
            &remote_snap,
            &BaselineHead::empty(),
            &tombstones,
        )
        .expect("plan");
        assert_eq!(batch.ensure_absent_count(), 0);
        assert_eq!(batch.open_conflict_count(), 0);
    }

    #[test]
    fn run_sync_cycle_plan_only_does_not_publish() {
        let local = FakeLocalPort {
            entries: vec![LocalPathEntry {
                path: path("memo/a.md"),
                digest: dig(1),
            }],
        };
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
            PublishReceipt {
                path_results: Vec::new(),
            },
            VerifiedRemoteState {
                results: Vec::new(),
            },
        );
        let session =
            SyncSession::new(fence(), SessionKind::FirstTakeover, "plan-only").expect("session");
        let result = run_sync_cycle(
            &session,
            &local,
            &remote,
            BaselineHead::empty(),
            None,
            false,
            None,
        )
        .expect("plan only");
        assert!(result.receipt.is_none());
        assert_eq!(remote.publish_call_count(), 0);
        assert_eq!(remote.verify_call_count(), 0);
        assert!(!result.baseline_advanced);
    }
}
