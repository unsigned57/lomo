// adversarial re-audit (round 2) of the 05-F2 sync fixes (B02–B08): same-family variants the
// first pass did not reach — every fence component independently (dataset swap and remote-
// identity rotation, not only workspace generation), the durable-conflict-session bypass
// route for pending remote publication, all-or-nothing baseline advance under a failed
// verify, port-injected verified paths filtered before baseline, digest resolution firing
// when the plan *does* need bytes (the conditional-skip twin), tombstone defeating the
// listing skip, dataset-bound tombstone recovery, and durable cancel gating the main
// publish phase of both cycle entry points.
// Any RED is a live regression, not an expectation edit.
//
//! Probes:
//! - `SyncIdentityFence::matches` rejects each component flip; `run_sync_cycle` refuses a
//!   baseline whose fence carries a rotated dataset or remote identity even when the hermetic
//!   local port reports no generation; a durable conflict session under a foreign fence can
//!   never drive remote publication (`sync_identity_mismatch`, zero publish calls).
//! - `advance_baseline_after_verify` is all-or-nothing (one `Failed` verify leaves *no* path
//!   advanced), only requested paths can be injected by the port, and a verified path held
//!   by an open conflict record cannot advance baseline (`may_advance_baseline_for_path`).
//! - `resolve_listing_digests` skips *only* proven facts: remote token moved → resolve fires;
//!   tombstone present → the "unchanged" skip is defeated and resolve fires; fully in-sync →
//!   resolve stays silent.
//! - `recover_pending_delete_intent`: a tombstone bound to a rotated `RemoteDatasetId` is a
//!   hard `tombstone_dataset_mismatch`, a digest-mismatched remote is `Ok(None)`, and a
//!   matching tombstone re-issues `EnsureAbsent` with the observed token.
//! - A durable cancel observed while Running gates the *main* publish phase of
//!   `run_sync_cycle` and `run_sync_cycle_streaming` alike — zero remote mutations and a
//!   `Cancelled` cycle record.
//! - `merge_conflicts_into_session` reopen semantics: one-sided remote movement re-arms the
//!   record with the *new* digests, resolved records outside the planned set survive, and
//!   `conflict_revision` bumps monotonically.

#![cfg(test)]
#![expect(
    clippy::expect_used,
    clippy::similar_names,
    clippy::too_many_lines,
    reason = "adversarial fixtures fail fast; a broken fixture is not a finding"
)]

mod tests {
    use std::collections::BTreeMap;
    use std::sync::atomic::{AtomicU32, Ordering};

    use lomo_core::LomoError;
    use lomo_sync::{
        BaselineHead, BatchAtomicity, ConflictContentKind, ConflictPathRecord, ConflictPathStatus,
        ConflictSession, ContentDigest, FakeLocalPort, LocalPathEntry, PathPublishStatus,
        PreparedRemoteBatch, ProviderNeutralIntent, PublishReceipt, RecoverDeleteRequest,
        RemoteCapabilities, RemoteDigestFact, RemoteListingStream, RemotePathEntry,
        RemoteResolvedObject, RemoteSnapshot, RemoteSyncPort, RemoteValidator, SessionKind,
        SnapshotCompleteness, SyncBackendKind, SyncCyclePhase, SyncIdentityFence, SyncPath,
        SyncPaths, SyncSession, TombstoneSet, UserDeleteRequest, VerifiedRemoteState,
        VerifyExpectation, VerifyStatus, baseline_must_hold_for_path, begin_sync_cycle,
        conflict_path_from_open, inspect_sync_cycle_plan_with_ports, may_advance_baseline_for_path,
        merge_conflicts_into_session, read_baseline, read_cycle_state, read_tombstones,
        record_user_delete_tombstone_first, recover_pending_delete_intent,
        request_sync_cycle_cancel, run_sync_cycle, run_sync_cycle_streaming,
        tombstone_authoritative_for_fence, write_baseline, write_conflict_artifact,
        write_conflict_session, write_session,
    };
    use lomo_workspace::{RemoteDatasetId, RemoteIdentityDigest, WorkspaceGenerationId};
    use tempfile::tempdir;

    const GENERATION: &str = "ab";
    const DATASET: &str = "ds-a";
    const IDENTITY: &str = "cd";

    fn fence(generation: &str, dataset: &str, identity: &str) -> SyncIdentityFence {
        SyncIdentityFence::from_parts(
            &WorkspaceGenerationId::parse(&generation.repeat(32)).expect("generation"),
            &RemoteDatasetId::parse(dataset).expect("dataset"),
            &RemoteIdentityDigest::parse(&identity.repeat(32)).expect("identity"),
        )
    }

    fn base_fence() -> SyncIdentityFence {
        fence(GENERATION, DATASET, IDENTITY)
    }

    fn dig(seed: u8) -> ContentDigest {
        ContentDigest::parse(&format!("{seed:02x}").repeat(32)).expect("digest")
    }

    fn digest_of(bytes: &[u8]) -> ContentDigest {
        ContentDigest::from_bytes(bytes)
    }

    fn path(raw: &str) -> SyncPath {
        SyncPath::parse(raw).expect("path")
    }

    fn remote_entry(path_s: &str, digest: RemoteDigestFact, token: &str) -> RemotePathEntry {
        RemotePathEntry {
            path: path(path_s),
            digest,
            validator: RemoteValidator::Strong(token.to_owned()),
        }
    }

    /// `RemoteSyncPort` shim over a static listing + object map that counts the observations
    /// the spec makes load-bearing (resolutions and publishes).
    struct CountingRemote {
        entries: Vec<RemotePathEntry>,
        objects: BTreeMap<String, Vec<u8>>,
        resolve_calls: AtomicU32,
        publish_calls: AtomicU32,
        receipt: PublishReceipt,
        verified: VerifiedRemoteState,
    }

    impl CountingRemote {
        fn new(entries: Vec<RemotePathEntry>) -> Self {
            Self {
                entries,
                objects: BTreeMap::new(),
                resolve_calls: AtomicU32::new(0),
                publish_calls: AtomicU32::new(0),
                receipt: PublishReceipt {
                    path_results: Vec::new(),
                },
                verified: VerifiedRemoteState {
                    results: Vec::new(),
                },
            }
        }

        fn publish_count(&self) -> u32 {
            self.publish_calls.load(Ordering::Acquire)
        }

        fn resolve_count(&self) -> u32 {
            self.resolve_calls.load(Ordering::Acquire)
        }
    }

    impl RemoteSyncPort for CountingRemote {
        fn list_remote(&self) -> Result<RemoteSnapshot, LomoError> {
            RemoteSnapshot::new(SnapshotCompleteness::Complete, self.entries.clone())
        }

        fn list_remote_pages(&self) -> Result<RemoteListingStream, LomoError> {
            let snap = RemoteSnapshot::new(SnapshotCompleteness::Complete, self.entries.clone())?;
            Ok(RemoteListingStream::from_single_snapshot(snap))
        }

        fn batch_atomicity(&self) -> BatchAtomicity {
            BatchAtomicity::PerPath
        }

        fn remote_capabilities(&self) -> Result<RemoteCapabilities, LomoError> {
            Ok(RemoteCapabilities::FULL)
        }

        fn publish(&self, _batch: &PreparedRemoteBatch) -> Result<PublishReceipt, LomoError> {
            self.publish_calls.fetch_add(1, Ordering::AcqRel);
            Ok(PublishReceipt {
                path_results: self.receipt.path_results.clone(),
            })
        }

        fn verify(&self, _exp: &[VerifyExpectation]) -> Result<VerifiedRemoteState, LomoError> {
            Ok(self.verified.clone())
        }

        fn resolve_remote_object(
            &self,
            path: &SyncPath,
        ) -> Result<Option<RemoteResolvedObject>, LomoError> {
            self.resolve_calls.fetch_add(1, Ordering::AcqRel);
            Ok(self
                .objects
                .get(path.as_str())
                .map(|body| RemoteResolvedObject {
                    digest: digest_of(body),
                    body: body.clone(),
                }))
        }

        fn load_object(
            &self,
            path: &SyncPath,
            expected_digest: &ContentDigest,
        ) -> Result<Option<Vec<u8>>, LomoError> {
            Ok(self
                .objects
                .get(path.as_str())
                .filter(|body| digest_of(body).as_str() == expected_digest.as_str())
                .cloned())
        }
    }

    fn session(id: &str) -> SyncSession {
        SyncSession::new(base_fence(), SessionKind::Incremental, id).expect("session")
    }

    fn established_baseline(
        fence: &SyncIdentityFence,
        entries: &[(&str, &ContentDigest, &str)],
    ) -> BaselineHead {
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence.clone());
        for (path_s, digest, token) in entries {
            baseline.upsert(&path(path_s), digest, (*token).to_owned());
        }
        baseline
    }

    /// B03 — every fence component is load-bearing on its own; none may be skipped.
    #[test]
    fn each_fence_component_rejects_independently() {
        let live_gen = WorkspaceGenerationId::parse(&GENERATION.repeat(32)).expect("generation");
        let ds = RemoteDatasetId::parse(DATASET).expect("dataset");
        let id = RemoteIdentityDigest::parse(&IDENTITY.repeat(32)).expect("identity");
        let fence = base_fence();
        fence
            .matches(&live_gen, &ds, &id)
            .expect("the exact fence components match");

        for (gen_flip, ds_flip, id_flip) in [
            (true, false, false),
            (false, true, false),
            (false, false, true),
        ] {
            let g = if gen_flip {
                WorkspaceGenerationId::parse(&"ee".repeat(32)).expect("gen")
            } else {
                live_gen.clone()
            };
            let d = if ds_flip {
                RemoteDatasetId::parse("ds-rotated").expect("ds")
            } else {
                ds.clone()
            };
            let i = if id_flip {
                RemoteIdentityDigest::parse(&"ef".repeat(32)).expect("id")
            } else {
                id.clone()
            };
            let err = fence
                .matches(&g, &d, &i)
                .expect_err("a single rotated component must refuse");
            assert_eq!(err.code(), "sync_identity_mismatch");
            assert_ne!(
                fence.stable_key(),
                SyncIdentityFence::from_parts(&g, &d, &i).stable_key(),
                "the stable key binds all three components"
            );
        }
    }

    /// B03 — the bypass route that only the baseline gate can catch: the hermetic local port
    /// reports no generation, so a rotated dataset/identity *inside the durable baseline* must
    /// still refuse the cycle. A revoked/rotated remote must never replay old baseline facts.
    #[test]
    fn rotated_baseline_fence_refuses_the_cycle_without_live_generation() {
        for rotated in [
            fence(GENERATION, "ds-rotated", IDENTITY),
            fence(GENERATION, DATASET, "ef"),
        ] {
            let temporary = tempdir().expect("temp");
            let paths = SyncPaths::for_workspace(temporary.path());
            let session = session("fence-rotate");
            write_session(&paths, &session).expect("session head");
            let baseline = established_baseline(&rotated, &[("memo/a.md", &dig(1), "tok-a")]);
            write_baseline(&paths, &baseline).expect("baseline");

            // FakeLocalPort reports no generation: only the baseline fence carries truth.
            let local = FakeLocalPort { entries: vec![] };
            let remote = CountingRemote::new(vec![]);
            let outcome = run_sync_cycle(
                &session,
                &local,
                &remote,
                baseline,
                Some(&paths),
                true,
                None,
            );
            let err = outcome.expect_err("a rotated durable fence must refuse the cycle");
            assert_eq!(err.code(), "sync_identity_mismatch");
            assert_eq!(
                remote.publish_count(),
                0,
                "a refused cycle must never publish"
            );
        }
    }

    /// B03 — the conflict-session bypass: a durable resolved resolution minted under a
    /// rotated identity must never reach remote publication. `execute_pending_resolved_
    /// remote_apply` asserts the fence *before* the publish step.
    #[test]
    fn a_resolved_conflict_under_a_rotated_fence_never_publishes() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let session = session("fence-conflict");
        write_session(&paths, &session).expect("session head");

        let body = b"local winner body".to_vec();
        let artifact =
            write_conflict_artifact(&paths, &session.session_id, "local", "memo/c.md", &body)
                .expect("artifact");
        let record = ConflictPathRecord {
            path: "memo/c.md".to_owned(),
            kind: ConflictContentKind::Markdown,
            local_digest: Some(digest_of(&body).as_str().to_owned()),
            remote_digest: Some(dig(0x33).as_str().to_owned()),
            baseline_digest: Some(dig(0x11).as_str().to_owned()),
            remote_token: Some("etag-conflict".to_owned()),
            local_artifact_ref: Some(artifact),
            remote_artifact_ref: None,
            baseline_artifact_ref: None,
            status: ConflictPathStatus::ResolvedKeepLocal,
        };
        // Durable conflict under a DIFFERENT dataset — the identity this session was minted
        // for is not the identity the cycle runs.
        let foreign = ConflictSession::open(
            fence(GENERATION, "ds-foreign", IDENTITY),
            session.session_id.clone(),
            vec![record],
        )
        .expect("conflict session");
        write_conflict_session(&paths, &foreign).expect("write conflict");

        let remote = CountingRemote::new(vec![]);
        let local = FakeLocalPort { entries: vec![] };
        let outcome = run_sync_cycle(
            &session,
            &local,
            &remote,
            BaselineHead::empty(),
            Some(&paths),
            true,
            None,
        );
        let err = outcome.expect_err("a foreign-fenced resolution must refuse");
        assert_eq!(err.code(), "sync_identity_mismatch");
        assert_eq!(
            remote.publish_count(),
            0,
            "a pending resolution under a rotated identity must not publish a single byte"
        );
    }

    /// B02 — verify-before-baseline is all-or-nothing AND filtered to requested paths: one
    /// `Failed` verify stops the whole advance (no partial landing), and a port-injected
    /// `Verified` for a path the cycle never requested cannot reach the durable baseline.
    #[test]
    fn baseline_advances_only_requested_paths_and_never_partially() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let session = session("baseline-mix");
        write_session(&paths, &session).expect("session head");

        let d_base_a = dig(0x11);
        let d_base_b = dig(0x22);
        let baseline = established_baseline(
            &base_fence(),
            &[
                ("memo/a.md", &d_base_a, "tok-a"),
                ("memo/b.md", &d_base_b, "tok-b"),
            ],
        );
        write_baseline(&paths, &baseline).expect("baseline");

        let d_new_a = digest_of(b"# a v2\n");
        let d_new_b = digest_of(b"# b v2\n");
        let local = FakeLocalPort {
            entries: vec![
                LocalPathEntry {
                    path: path("memo/a.md"),
                    digest: d_new_a.clone(),
                },
                LocalPathEntry {
                    path: path("memo/b.md"),
                    digest: d_new_b.clone(),
                },
            ],
        };
        let mut remote = CountingRemote::new(vec![
            remote_entry("memo/a.md", RemoteDigestFact::Unresolved, "tok-a"),
            remote_entry("memo/b.md", RemoteDigestFact::Unresolved, "tok-b"),
        ]);
        remote.receipt = PublishReceipt {
            path_results: vec![
                (
                    path("memo/a.md"),
                    PathPublishStatus::Applied {
                        new_token: "tok-a2".to_owned(),
                    },
                ),
                (
                    path("memo/b.md"),
                    PathPublishStatus::Applied {
                        new_token: "tok-b2".to_owned(),
                    },
                ),
            ],
        };
        remote.verified = VerifiedRemoteState {
            results: vec![
                VerifyStatus::Verified {
                    path: path("memo/a.md"),
                    digest: d_new_a.clone(),
                    remote_token: "tok-a2".to_owned(),
                },
                VerifyStatus::Failed {
                    path: path("memo/b.md"),
                    code: "etag mismatch".to_owned(),
                },
            ],
        };

        let cycle = run_sync_cycle(
            &session,
            &local,
            &remote,
            baseline,
            Some(&paths),
            true,
            None,
        )
        .expect("cycle");
        assert!(
            !cycle.baseline_advanced,
            "one failed verify must veto the whole advance — no partial baseline landing"
        );
        let stored = read_baseline(&paths).expect("baseline");
        assert_eq!(
            stored
                .get("memo/a.md")
                .map(|entry| entry.remote_token.as_str()),
            Some("tok-a"),
            "a verified path beside a failed verify must not land either (atomicity)"
        );

        // Second half: everything verifies, but the port injects an unrequested Verified for
        // a foreign path — the request-path filter must drop it before baseline sees it.
        remote.verified = VerifiedRemoteState {
            results: vec![
                VerifyStatus::Verified {
                    path: path("memo/a.md"),
                    digest: d_new_a,
                    remote_token: "tok-a2".to_owned(),
                },
                VerifyStatus::Verified {
                    path: path("memo/b.md"),
                    digest: d_new_b,
                    remote_token: "tok-b2".to_owned(),
                },
                VerifyStatus::Verified {
                    path: path("memo/injected.md"),
                    digest: dig(0x66),
                    remote_token: "tok-evil".to_owned(),
                },
            ],
        };
        let cycle2 = run_sync_cycle(&session, &local, &remote, stored, Some(&paths), true, None)
            .expect("cycle 2");
        assert!(cycle2.baseline_advanced);
        assert_eq!(
            cycle2
                .baseline
                .get("memo/a.md")
                .map(|entry| entry.remote_token.as_str()),
            Some("tok-a2"),
        );
        assert!(
            cycle2.baseline.get("memo/injected.md").is_none(),
            "a port-injected verified fact for an unrequested path must not enter baseline"
        );
    }

    /// B02 — `may_advance_baseline_for_path` / `baseline_must_hold_for_path`: an open or
    /// skipped conflict record pins its baseline entry even when remote verifies the path.
    #[test]
    fn a_path_held_by_an_open_conflict_never_advances_baseline() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let open_record = conflict_path_from_open(
            &path("memo/held.md"),
            Some(&dig(0x41)),
            Some(&dig(0x42)),
            Some(&dig(0x40)),
            Some("tok-held"),
        )
        .expect("open record");
        let resolved_record = ConflictPathRecord {
            path: "memo/free.md".to_owned(),
            kind: ConflictContentKind::Markdown,
            local_digest: Some(dig(0x51).as_str().to_owned()),
            remote_digest: Some(dig(0x52).as_str().to_owned()),
            baseline_digest: None,
            remote_token: None,
            local_artifact_ref: None,
            remote_artifact_ref: None,
            baseline_artifact_ref: None,
            status: ConflictPathStatus::ResolvedKeepRemote,
        };
        let session = ConflictSession::open(
            base_fence(),
            "held-session".to_owned(),
            vec![open_record, resolved_record],
        )
        .expect("conflict session");

        assert!(!may_advance_baseline_for_path(
            Some(&session),
            "memo/held.md"
        ));
        assert!(baseline_must_hold_for_path(&session, "memo/held.md"));
        assert!(
            may_advance_baseline_for_path(Some(&session), "memo/free.md"),
            "a resolved record releases its baseline hold"
        );
        assert!(may_advance_baseline_for_path(None, "memo/anything.md"));

        drop(paths); // durable side is covered by the merge tests above and the cycle probes
    }

    /// B04 twin — the conditional skip is *precise*: when the remote strong token moved, the
    /// planner needs the remote digest (pull decision) and resolution must fire; when a
    /// tombstone exists for the path the "unchanged" skip is defeated even though the token
    /// still matches baseline.
    #[test]
    fn digest_resolution_fires_exactly_when_the_plan_needs_bytes() {
        // (a) remote moved: token differs from baseline → PullPresent needs the digest.
        {
            let temporary = tempdir().expect("temp");
            let paths = SyncPaths::for_workspace(temporary.path());
            let session = session("digest-pull");
            write_session(&paths, &session).expect("session head");
            let d_base = dig(0x10);
            let baseline = established_baseline(&base_fence(), &[("memo/p.md", &d_base, "tok-p")]);
            write_baseline(&paths, &baseline).expect("baseline");
            let local = FakeLocalPort {
                entries: vec![LocalPathEntry {
                    path: path("memo/p.md"),
                    digest: d_base,
                }],
            };
            let mut remote = CountingRemote::new(vec![remote_entry(
                "memo/p.md",
                RemoteDigestFact::Unresolved,
                "tok-p2",
            )]);
            remote
                .objects
                .insert("memo/p.md".to_owned(), b"remote v2 body".to_vec());
            let summary = inspect_sync_cycle_plan_with_ports(&paths, &local, &remote, false, None)
                .expect("plan inspect");
            assert_eq!(
                remote.resolve_count(),
                1,
                "a moved remote token requires the remote digest — the skip must fire only \
                 when the token *proves* unchanged"
            );
            assert_eq!(summary.pull_present_count, 1, "remote change plans a pull");
        }

        // (b) tombstoned path: local changed, remote token still equals baseline, but a
        // tombstone exists → the local-present+unchanged skip must not apply (the recovery
        // path needs the digest to compare against the tombstone).
        {
            let temporary = tempdir().expect("temp");
            let paths = SyncPaths::for_workspace(temporary.path());
            let session = session("digest-tombstone");
            write_session(&paths, &session).expect("session head");
            let d_base = dig(0x20);
            let baseline = established_baseline(&base_fence(), &[("memo/t.md", &d_base, "tok-t")]);
            write_baseline(&paths, &baseline).expect("baseline");
            let mut tombstones = TombstoneSet::empty();
            tombstones.upsert(
                "memo/t.md",
                &base_fence().remote_dataset_id,
                d_base.as_str(),
            );
            lomo_sync::write_tombstones(&paths, &tombstones).expect("tombstones");
            let local = FakeLocalPort {
                entries: vec![LocalPathEntry {
                    path: path("memo/t.md"),
                    digest: dig(0x21), // local moved relative to baseline
                }],
            };
            let mut remote = CountingRemote::new(vec![remote_entry(
                "memo/t.md",
                RemoteDigestFact::Unresolved,
                "tok-t",
            )]);
            remote
                .objects
                .insert("memo/t.md".to_owned(), b"remote baseline body".to_vec());
            drop(
                inspect_sync_cycle_plan_with_ports(&paths, &local, &remote, false, None)
                    .expect("plan inspect"),
            );
            assert_eq!(
                remote.resolve_count(),
                1,
                "a tombstoned path defeats the unchanged-token skip — recovery needs the \
                 remote digest fact"
            );
        }

        // (c) fully in sync (token match + local == baseline): resolution stays silent.
        {
            let temporary = tempdir().expect("temp");
            let paths = SyncPaths::for_workspace(temporary.path());
            let session = session("digest-insync");
            write_session(&paths, &session).expect("session head");
            let d_base = dig(0x30);
            let baseline = established_baseline(&base_fence(), &[("memo/s.md", &d_base, "tok-s")]);
            write_baseline(&paths, &baseline).expect("baseline");
            let local = FakeLocalPort {
                entries: vec![LocalPathEntry {
                    path: path("memo/s.md"),
                    digest: d_base,
                }],
            };
            let remote = CountingRemote::new(vec![remote_entry(
                "memo/s.md",
                RemoteDigestFact::Unresolved,
                "tok-s",
            )]);
            let summary = inspect_sync_cycle_plan_with_ports(&paths, &local, &remote, false, None)
                .expect("plan inspect");
            assert_eq!(remote.resolve_count(), 0, "an in-sync path needs no bytes");
            assert_eq!(summary.ensure_present_count, 0);
            assert_eq!(summary.pull_present_count, 0);
        }
    }

    /// B03/B02 — the dataset binding on tombstones is the phantom-delete guard under identity
    /// rotation: recover only under the exact same dataset, with matching digest and a live
    /// remote token.
    #[test]
    fn a_tombstone_under_a_rotated_dataset_never_recovers_into_ensure_absent() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let fence_a = base_fence();
        let fence_b = fence(GENERATION, "ds-rotated", IDENTITY);
        let d_del = digest_of(b"deleted body");
        let baseline = established_baseline(&fence_a, &[("memo/del.md", &d_del, "tok-del")]);

        // The tombstone is recorded under dataset A — exactly once, before any remote write.
        let intent = record_user_delete_tombstone_first(&UserDeleteRequest {
            paths: &paths,
            fence: &fence_a,
            baseline: &baseline,
            session_kind: SessionKind::Incremental,
            remote_completeness: SnapshotCompleteness::Complete,
            path: &path("memo/del.md"),
            local_has_path: false,
            observed_remote_token: Some("tok-del"),
            content_digest: &d_del,
        })
        .expect("tombstone-first delete records");
        assert!(matches!(intent, ProviderNeutralIntent::EnsureAbsent { .. }));
        let tombstones = read_tombstones(&paths).expect("tombstones");
        assert!(
            tombstone_authoritative_for_fence(&tombstones, "memo/del.md", &fence_a),
            "the tombstone is authoritative under its own dataset"
        );
        assert!(
            !tombstone_authoritative_for_fence(&tombstones, "memo/del.md", &fence_b),
            "a rotated dataset strips the tombstone's authority"
        );

        // Crash-recovery under the rotated dataset: hard refusal, never an EnsureAbsent.
        let outcome = recover_pending_delete_intent(&RecoverDeleteRequest {
            fence: &fence_b,
            baseline: &baseline,
            tombstones: &tombstones,
            session_kind: SessionKind::Incremental,
            remote_completeness: SnapshotCompleteness::Complete,
            path: &path("memo/del.md"),
            local_has_path: false,
            remote_token: Some("tok-del"),
            remote_digest: Some(&d_del),
        });
        let err = outcome.expect_err("a foreign-dataset tombstone must refuse recovery");
        assert_eq!(err.code(), "tombstone_dataset_mismatch");

        // Same dataset but the remote moved on (digest no longer matches the tombstone):
        // recovery stays silent — deleting the *new* remote bytes would be a phantom delete.
        let recovered = recover_pending_delete_intent(&RecoverDeleteRequest {
            fence: &fence_a,
            baseline: &baseline,
            tombstones: &tombstones,
            session_kind: SessionKind::Incremental,
            remote_completeness: SnapshotCompleteness::Complete,
            path: &path("memo/del.md"),
            local_has_path: false,
            remote_token: Some("tok-del"),
            remote_digest: Some(&dig(0x77)),
        })
        .expect("digest-mismatched recovery resolves to no intent");
        assert!(
            recovered.is_none(),
            "the remote object changed under the tombstone — recovery must not delete it"
        );

        // Same dataset + matching digest + live token: the pending delete re-issues.
        let recovered = recover_pending_delete_intent(&RecoverDeleteRequest {
            fence: &fence_a,
            baseline: &baseline,
            tombstones: &tombstones,
            session_kind: SessionKind::Incremental,
            remote_completeness: SnapshotCompleteness::Complete,
            path: &path("memo/del.md"),
            local_has_path: false,
            remote_token: Some("tok-del"),
            remote_digest: Some(&d_del),
        })
        .expect("legitimate recovery resolves");
        let Some(ProviderNeutralIntent::EnsureAbsent {
            expected_remote_token,
            ..
        }) = recovered
        else {
            panic!("matching tombstone + gates re-issue EnsureAbsent, got {recovered:?}");
        };
        assert_eq!(expected_remote_token, "tok-del");
    }

    /// B05 twin — the durable cancel also gates the *main* publish phase (not only the
    /// pending resolved-conflict apply), on both cycle entry points.
    #[test]
    fn cancel_before_the_main_publish_phase_stops_remote_mutation() {
        // Single-shot cycle.
        {
            let temporary = tempdir().expect("temp");
            let paths = SyncPaths::for_workspace(temporary.path());
            let session = session("cancel-main");
            write_session(&paths, &session).expect("session head");
            let baseline = established_baseline(&base_fence(), &[("memo/x.md", &dig(1), "tok-x")]);
            write_baseline(&paths, &baseline).expect("baseline");
            begin_sync_cycle(&paths, &session, SyncBackendKind::WebDav, true)
                .expect("cycle begins");
            request_sync_cycle_cancel(&paths).expect("cancel request");

            let local = FakeLocalPort {
                entries: vec![LocalPathEntry {
                    path: path("memo/x.md"),
                    digest: dig(2),
                }],
            };
            let mut remote = CountingRemote::new(vec![remote_entry(
                "memo/x.md",
                RemoteDigestFact::Unresolved,
                "tok-x",
            )]);
            remote.receipt = PublishReceipt {
                path_results: vec![(
                    path("memo/x.md"),
                    PathPublishStatus::Applied {
                        new_token: "tok-x2".to_owned(),
                    },
                )],
            };
            let outcome = run_sync_cycle(
                &session,
                &local,
                &remote,
                baseline,
                Some(&paths),
                true,
                None,
            );
            let err = outcome.expect_err("a cancelled cycle refuses");
            assert_eq!(err.code(), "sync_cycle_cancelled");
            assert_eq!(
                remote.publish_count(),
                0,
                "durable cancel must gate the main publish, not only the conflict apply"
            );
            let record = read_cycle_state(&paths)
                .expect("cycle state")
                .expect("record");
            assert_ne!(record.phase, SyncCyclePhase::Completed);
        }

        // Streaming entry point.
        {
            let temporary = tempdir().expect("temp");
            let paths = SyncPaths::for_workspace(temporary.path());
            let session = session("cancel-stream");
            write_session(&paths, &session).expect("session head");
            let baseline = established_baseline(&base_fence(), &[("memo/y.md", &dig(3), "tok-y")]);
            write_baseline(&paths, &baseline).expect("baseline");
            begin_sync_cycle(&paths, &session, SyncBackendKind::WebDav, true)
                .expect("cycle begins");
            request_sync_cycle_cancel(&paths).expect("cancel request");

            let local = FakeLocalPort {
                entries: vec![LocalPathEntry {
                    path: path("memo/y.md"),
                    digest: dig(4),
                }],
            };
            let mut remote = CountingRemote::new(vec![remote_entry(
                "memo/y.md",
                RemoteDigestFact::Unresolved,
                "tok-y",
            )]);
            remote.receipt = PublishReceipt {
                path_results: vec![(
                    path("memo/y.md"),
                    PathPublishStatus::Applied {
                        new_token: "tok-y2".to_owned(),
                    },
                )],
            };
            let outcome = run_sync_cycle_streaming(
                &session,
                &local,
                &remote,
                baseline,
                Some(&paths),
                true,
                None,
            );
            let err = outcome.expect_err("a cancelled streaming cycle refuses");
            assert_eq!(err.code(), "sync_cycle_cancelled");
            assert_eq!(
                remote.publish_count(),
                0,
                "the streaming entry point must honour the same durable cancel gate"
            );
        }
    }

    /// B02 — conflict merge semantics under one-sided movement: the remote digest moved while
    /// local stayed put; the durable record must reopen with the *new* divergence digests and
    /// the revision must advance — a resolved/record elsewhere in the session survives.
    #[test]
    fn one_sided_remote_movement_reopens_the_record_with_fresh_digests() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let d_l1 = dig(0x61);
        let d_r1 = dig(0x62);
        let d_r2 = dig(0x63);

        // Existing session: a.md resolved (outside the next planned set), b.md open on
        // divergence (L1, R1) with artifacts armed.
        let mut open_b = conflict_path_from_open(
            &path("memo/b.md"),
            Some(&d_l1),
            Some(&d_r1),
            Some(&dig(0x60)),
            Some("tok-b"),
        )
        .expect("open record");
        let remote_artifact =
            write_conflict_artifact(&paths, "merge", "remote", "memo/b.md", b"r1")
                .expect("artifact");
        let local_artifact = write_conflict_artifact(&paths, "merge", "local", "memo/b.md", b"l1")
            .expect("artifact");
        open_b.remote_artifact_ref = Some(remote_artifact);
        open_b.local_artifact_ref = Some(local_artifact);
        let resolved_a = ConflictPathRecord {
            path: "memo/a.md".to_owned(),
            kind: ConflictContentKind::Markdown,
            local_digest: Some(dig(0x51).as_str().to_owned()),
            remote_digest: Some(dig(0x52).as_str().to_owned()),
            baseline_digest: Some(dig(0x50).as_str().to_owned()),
            remote_token: Some("tok-a".to_owned()),
            local_artifact_ref: None,
            remote_artifact_ref: None,
            baseline_artifact_ref: None,
            status: ConflictPathStatus::ResolvedKeepRemote,
        };
        let existing =
            ConflictSession::open(base_fence(), "merge".to_owned(), vec![open_b, resolved_a])
                .expect("existing session");
        write_conflict_session(&paths, &existing).expect("write session");

        // New cycle: only b.md re-plans, on divergence (L1, R2) — the remote moved alone.
        let intents = vec![ProviderNeutralIntent::OpenConflict {
            path: path("memo/b.md"),
            local_digest: d_l1,
            remote_digest: d_r2.clone(),
            baseline_digest: Some(dig(0x60)),
        }];
        let remote_tokens = BTreeMap::from([("memo/b.md".to_owned(), "tok-b2".to_owned())]);
        let merged = merge_conflicts_into_session(
            &paths,
            &base_fence(),
            "merge",
            &intents,
            &remote_tokens,
            None,
            Some(&existing),
        )
        .expect("merge")
        .expect("session persists");

        let record_b = merged
            .paths
            .iter()
            .find(|record| record.path == "memo/b.md")
            .expect("b.md record");
        assert_eq!(
            record_b.status,
            ConflictPathStatus::Open,
            "one-sided movement must reopen the record, not keep stale armed state"
        );
        assert_eq!(
            record_b.remote_digest.as_deref(),
            Some(d_r2.as_str()),
            "the reopened record pins the *new* remote digest — old divergence cannot mask it"
        );
        assert_eq!(record_b.remote_token.as_deref(), Some("tok-b2"));
        let record_a = merged
            .paths
            .iter()
            .find(|record| record.path == "memo/a.md")
            .expect("a.md record survives the merge");
        assert_eq!(
            record_a.status,
            ConflictPathStatus::ResolvedKeepRemote,
            "a resolved record outside the planned set is never dropped by a new page"
        );
        assert_eq!(
            merged.conflict_revision,
            existing.conflict_revision + 1,
            "the conflict revision advances monotonically per materialization"
        );
    }
}
