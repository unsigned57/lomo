//! Behavior Contract (T34/B04 content digest vs conditional-update token separation)
//!
//! Capability: `RemotePathEntry` keeps content identity (`RemoteDigestFact`) strictly separate
//! from conditional-update authority (`RemoteValidator`) and whole-batch CAS
//! (`RemoteSnapshot::snapshot_revision`). Metadata-only listings resolve digests on demand.
//!
//! Scenarios:
//! - Given a strong validator equal to the baseline token and local still at baseline, when the
//!   cycle runs, then the path is in-sync: no intent, no digest resolution fetch.
//! - Given a remote-only entry with unresolved digest, when the cycle plans a pull, then the
//!   digest is resolved on demand and `PullPresent` carries the resolved digest.
//! - Given an unresolved digest reaching `plan_intents` without the resolution pre-pass, when a
//!   byte-level decision is required, then planning fails closed (`remote_digest_unresolved`).
//! - Given a tombstone-matched delete with only a weak validator, when planned, then a `Hold`
//!   intent is emitted — never an unconditional `EnsureAbsent`.
//! - Given a local update against a weak/absent validator on a per-path provider, when planned,
//!   then `Hold` is emitted; on a whole-batch (Git CAS) provider the write proceeds because the
//!   snapshot token is the precondition.
//! - Given an unresolved entry whose object vanishes before resolution, when the cycle runs,
//!   then it fails closed (`remote_object_vanished`).
//!
//! Observable outcomes: intent variants/tokens, `remote_digest_unresolved` /
//! `remote_object_vanished` error codes, `RemoteValidator` strength typing.
//! Excludes: real provider transports, Kotlin FFI, durable schema migration.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_sync::{
        BaselineHead, BatchAtomicity, ContentDigest, FakeLocalPort, FakeRemotePort, HoldReason,
        LocalPathEntry, LocalSyncPort, MapRemoteObjectSource, PreparedRemoteBatch,
        ProviderNeutralIntent, PublishReceipt, RemoteDigestFact, RemotePathEntry,
        RemotePublishContract, RemoteSnapshot, RemoteValidator, SessionKind, SnapshotCompleteness,
        SyncIdentityFence, SyncPath, SyncSession, TombstoneSet, VerifiedRemoteState, plan_intents,
        plan_intents_with_atomicity, run_sync_cycle,
    };
    use lomo_workspace::{RemoteDatasetId, RemoteIdentityDigest, WorkspaceGenerationId};
    use sha2::{Digest, Sha256};

    fn dig(seed: u8) -> ContentDigest {
        ContentDigest::parse(&format!("{seed:02x}").repeat(32)).expect("digest")
    }

    fn digest_of(bytes: &[u8]) -> ContentDigest {
        ContentDigest::parse(&format!("{:x}", Sha256::digest(bytes))).expect("digest")
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

    fn session(kind: SessionKind) -> SyncSession {
        SyncSession::new(fence(), kind, "s1".to_owned()).expect("session")
    }

    fn established_baseline(path_raw: &str, digest: &ContentDigest, token: &str) -> BaselineHead {
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path(path_raw), digest, token.to_owned());
        baseline
    }

    fn fake_remote(
        entries: Vec<RemotePathEntry>,
        objects: MapRemoteObjectSource,
    ) -> FakeRemotePort {
        FakeRemotePort::with_objects(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, entries).expect("snap"),
            PublishReceipt {
                path_results: Vec::new(),
            },
            VerifiedRemoteState {
                results: Vec::new(),
            },
            objects,
        )
    }

    #[test]
    fn strong_token_proven_in_sync_skips_digest_resolution() {
        // Remote validator still equals the baseline token and local still matches baseline:
        // the unresolved digest is never fetched.
        let baseline = established_baseline("memo/a.md", &dig(1), "tok-a");
        let local = FakeLocalPort {
            entries: vec![LocalPathEntry {
                path: path("memo/a.md"),
                digest: dig(1),
            }],
        };
        // Empty object map: any resolution attempt would fail the cycle with
        // `remote_object_vanished` — absence of error proves no fetch happened.
        let remote = fake_remote(
            vec![RemotePathEntry {
                path: path("memo/a.md"),
                digest: RemoteDigestFact::Unresolved,
                validator: RemoteValidator::Strong("tok-a".to_owned()),
            }],
            MapRemoteObjectSource::empty(),
        );
        let result = run_sync_cycle(
            &session(SessionKind::Incremental),
            &local,
            &remote,
            baseline,
            None,
            false,
            None,
        )
        .expect("in-sync path must not require digest resolution");
        assert_eq!(result.batch.intents.len(), 0);
    }

    #[test]
    fn unresolved_digest_resolves_on_demand_for_pull() {
        let body = b"remote-body";
        let remote_digest = digest_of(body);
        let mut objects = MapRemoteObjectSource::empty();
        objects.insert("memo/new.md", body.to_vec());
        let remote = fake_remote(
            vec![RemotePathEntry {
                path: path("memo/new.md"),
                digest: RemoteDigestFact::Unresolved,
                validator: RemoteValidator::Strong("tok-n".to_owned()),
            }],
            objects,
        );
        let local = FakeLocalPort {
            entries: Vec::new(),
        };
        let result = run_sync_cycle(
            &session(SessionKind::Incremental),
            &local,
            &remote,
            BaselineHead::empty(),
            None,
            false,
            None,
        )
        .expect("cycle");
        let intent = result.batch.intents.first().expect("pull intent");
        match intent {
            ProviderNeutralIntent::PullPresent {
                digest,
                remote_token,
                ..
            } => {
                assert_eq!(digest.as_str(), remote_digest.as_str());
                assert_eq!(remote_token.as_deref(), Some("tok-n"));
            }
            ProviderNeutralIntent::EnsurePresent { .. }
            | ProviderNeutralIntent::EnsureAbsent { .. }
            | ProviderNeutralIntent::OpenConflict { .. }
            | ProviderNeutralIntent::ReportUnrecognized { .. }
            | ProviderNeutralIntent::Hold { .. } => {
                panic!("expected PullPresent, got {intent:?}");
            }
        }
    }

    #[test]
    fn unresolved_digest_at_byte_decision_fails_closed() {
        // plan_intents is pure: an unresolved digest where bytes must be compared is an error,
        // never a guess.
        let local = FakeLocalPort {
            entries: vec![LocalPathEntry {
                path: path("memo/a.md"),
                digest: dig(2),
            }],
        };
        let remote_snap = RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![RemotePathEntry {
                path: path("memo/a.md"),
                digest: RemoteDigestFact::Unresolved,
                validator: RemoteValidator::Strong("tok-changed".to_owned()),
            }],
        )
        .expect("snap");
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/a.md"), &dig(1), "tok-old".to_owned());
        let err = plan_intents(
            SessionKind::Incremental,
            &local.snapshot().expect("local"),
            &remote_snap,
            &baseline,
            &TombstoneSet::empty(),
        )
        .expect_err("unresolved digest must fail closed");
        assert_eq!(err.code(), "remote_digest_unresolved");
    }

    #[test]
    fn weak_validator_holds_authorized_delete() {
        // Tombstone + baseline authorize the delete, but `W/"…"` cannot drive If-Match → Hold.
        let mut tombstones = TombstoneSet::empty();
        tombstones.upsert("memo/d.md", "ds", dig(5).as_str());
        let baseline = established_baseline("memo/d.md", &dig(5), "tok-d");
        let local = FakeLocalPort {
            entries: Vec::new(),
        };
        let remote_snap = RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![RemotePathEntry {
                path: path("memo/d.md"),
                digest: RemoteDigestFact::Known(dig(5)),
                validator: RemoteValidator::Weak("W/\"e1\"".to_owned()),
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
        assert_eq!(batch.intents.len(), 1);
        assert!(
            matches!(
                batch.intents.first(),
                Some(ProviderNeutralIntent::Hold {
                    reason: HoldReason::ConditionalUpdateUnsupported,
                    ..
                })
            ),
            "weak validator must hold the delete: {:?}",
            batch.intents
        );
    }

    #[test]
    fn weak_or_absent_validator_holds_local_update() {
        for validator in [
            RemoteValidator::Weak("W/\"e1\"".to_owned()),
            RemoteValidator::Absent,
        ] {
            // Remote digest still equals baseline (proven unchanged) but local moved on →
            // an update is required; without a strong validator it must Hold.
            let baseline = established_baseline("memo/u.md", &dig(1), "tok-u");
            let local = FakeLocalPort {
                entries: vec![LocalPathEntry {
                    path: path("memo/u.md"),
                    digest: dig(2),
                }],
            };
            let remote_snap = RemoteSnapshot::new(
                SnapshotCompleteness::Complete,
                vec![RemotePathEntry {
                    path: path("memo/u.md"),
                    digest: RemoteDigestFact::Known(dig(1)),
                    validator: validator.clone(),
                }],
            )
            .expect("snap");
            let batch = plan_intents(
                SessionKind::Incremental,
                &local.snapshot().expect("local"),
                &remote_snap,
                &baseline,
                &TombstoneSet::empty(),
            )
            .expect("plan");
            assert!(
                matches!(
                    batch.intents.as_slice(),
                    [ProviderNeutralIntent::Hold {
                        reason: HoldReason::ConditionalUpdateUnsupported,
                        ..
                    }]
                ),
                "expected Hold for {validator:?}, got {:?}",
                batch.intents
            );
        }
    }

    #[test]
    fn whole_batch_cas_does_not_hold_on_absent_path_token() {
        // Git-style whole-batch publish: the snapshot CAS token is the write precondition, so a
        // missing per-path validator never holds the intent.
        let mut tombstones = TombstoneSet::empty();
        tombstones.upsert("memo/d.md", "ds", dig(5).as_str());
        let baseline = established_baseline("memo/d.md", &dig(5), "blob-oid");
        let local = FakeLocalPort {
            entries: Vec::new(),
        };
        let remote_snap = RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![RemotePathEntry {
                path: path("memo/d.md"),
                digest: RemoteDigestFact::Known(dig(5)),
                validator: RemoteValidator::Absent,
            }],
        )
        .expect("snap");
        let batch = plan_intents_with_atomicity(
            SessionKind::Incremental,
            &local.snapshot().expect("local"),
            &remote_snap,
            &baseline,
            &tombstones,
            RemotePublishContract::whole_batch(Some("tip-oid".to_owned())),
        )
        .expect("plan");
        assert!(
            matches!(
                batch.intents.as_slice(),
                [ProviderNeutralIntent::EnsureAbsent { .. }]
            ),
            "whole-batch CAS must still emit the delete: {:?}",
            batch.intents
        );
        assert_eq!(
            batch.expected_snapshot_token.as_deref(),
            Some("tip-oid"),
            "snapshot CAS is the whole-batch precondition, not the per-path token"
        );
    }

    #[test]
    fn vanished_object_fails_closed_during_resolution() {
        // Local differs, listing digest unresolved, object gone before resolve → fail closed.
        let baseline = established_baseline("memo/v.md", &dig(1), "tok-old");
        let local = FakeLocalPort {
            entries: vec![LocalPathEntry {
                path: path("memo/v.md"),
                digest: dig(2),
            }],
        };
        let remote = fake_remote(
            vec![RemotePathEntry {
                path: path("memo/v.md"),
                digest: RemoteDigestFact::Unresolved,
                validator: RemoteValidator::Strong("tok-changed".to_owned()),
            }],
            MapRemoteObjectSource::empty(),
        );
        let err = run_sync_cycle(
            &session(SessionKind::Incremental),
            &local,
            &remote,
            baseline,
            None,
            false,
            None,
        )
        .expect_err("vanished object must fail closed");
        assert_eq!(err.code(), "remote_object_vanished");
    }

    #[test]
    fn pull_present_carries_strong_validator_only() {
        // A weak listing token is recorded for diagnostics but never becomes durable remote_token
        // authority for the pull intent.
        let remote = fake_remote(
            vec![RemotePathEntry {
                path: path("memo/r.md"),
                digest: RemoteDigestFact::Known(dig(3)),
                validator: RemoteValidator::Weak("W/\"e9\"".to_owned()),
            }],
            MapRemoteObjectSource::empty(),
        );
        let local = FakeLocalPort {
            entries: Vec::new(),
        };
        let result = run_sync_cycle(
            &session(SessionKind::Incremental),
            &local,
            &remote,
            BaselineHead::empty(),
            None,
            false,
            None,
        )
        .expect("cycle");
        match result.batch.intents.first() {
            Some(ProviderNeutralIntent::PullPresent { remote_token, .. }) => {
                assert_eq!(remote_token, &None);
            }
            other => panic!("expected PullPresent, got {other:?}"),
        }
    }

    #[test]
    fn prepared_batch_counts_holds() {
        let batch = PreparedRemoteBatch::new(
            BatchAtomicity::PerPath,
            vec![ProviderNeutralIntent::Hold {
                path: path("memo/h.md"),
                reason: HoldReason::ConditionalUpdateUnsupported,
            }],
        )
        .expect("batch");
        assert_eq!(batch.hold_count(), 1);
        assert_eq!(batch.ensure_present_count(), 0);
    }
}
