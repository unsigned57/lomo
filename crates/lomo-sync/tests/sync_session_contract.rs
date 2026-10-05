// adversarial-audit: PullPresent advances baseline although no local byte write exists
// (no production executor: LocalSyncPort is snapshot-only, apply_local_sync_batch has no
// production caller); the next cycle then reads "local missing + baseline tracked + token match"
// as a user delete and writes a durable tombstone + EnsureAbsent for a file the user never saw.
//
// adversarial-audit: a resolved (never cleared) conflict session masks a fresh
// divergence on the same path: `session_covers_open_paths` checks path membership only, so the
// stale record keeps `Resolved*` status, open_count stays 0 and re-resolution is refused.
//
// adversarial-audit: the durable session fence is never re-validated against the live
// workspace generation / dataset identity: `SyncIdentityFence::matches` and
// `assert_fence_for_revival` have no production callers, and `ensure_session_for_composition`
// returns early whenever `session.rec` exists.

#![cfg(test)]
#![expect(
    clippy::expect_used,
    clippy::similar_names,
    clippy::too_many_lines,
    reason = "adversarial contracts fail closed on panics; per-version digest bindings keep multi-cycle scenarios readable"
)]

use lomo_sync::{
    BaselineHead, ConflictBodySource, ConflictPathStatus, ConflictResolution, ConflictSession,
    ConflictSessionState, ContentDigest, FakeLocalPort, FakeRemotePort, LocalPathEntry,
    PathPublishStatus, PublishReceipt, RemoteDigestFact, RemotePathEntry, RemoteSnapshot,
    RemoteValidator, SessionKind, SnapshotCompleteness, StoreLocalSnapshotPort, SyncIdentityFence,
    SyncPath, SyncPaths, SyncSession, VerifiedRemoteState, VerifyStatus,
    advance_baseline_after_local_pull, collect_resolved_local_pull_mutations,
    conflict_path_from_open, list_sync_conflicts, read_baseline, read_conflict_session_state,
    read_tombstones, resolve_sync_conflicts, run_sync_cycle, write_baseline,
    write_conflict_artifact, write_conflict_session, write_session,
};
use lomo_workspace::{RemoteDatasetId, RemoteIdentityDigest, WorkspaceGenerationId};
use tempfile::tempdir;

fn dig(seed: u8) -> ContentDigest {
    ContentDigest::parse(&format!("{seed:02x}").repeat(32)).expect("digest")
}

fn body_digest(bytes: &[u8]) -> ContentDigest {
    ContentDigest::from_bytes(bytes)
}

fn path(raw: &str) -> SyncPath {
    SyncPath::parse(raw).expect("path")
}

fn fence_gen(gen_byte: &str, dataset: &str) -> SyncIdentityFence {
    SyncIdentityFence::from_parts(
        &WorkspaceGenerationId::parse(&gen_byte.repeat(32)).expect("gen"),
        &RemoteDatasetId::parse(dataset).expect("ds"),
        &RemoteIdentityDigest::parse(&"cd".repeat(32)).expect("id"),
    )
}

fn remote_entry(path_s: &str, digest: ContentDigest, token: &str) -> RemotePathEntry {
    RemotePathEntry {
        path: path(path_s),
        digest: RemoteDigestFact::Known(digest),
        validator: RemoteValidator::Strong(token.to_owned()),
    }
}

/// Fixture: cycle 1 with a remote-only path under a complete listing. Returns the advanced
/// baseline the cycle produced (local bytes are never written by any executor).
fn run_pull_present_first_cycle(
    root: &std::path::Path,
    session: &SyncSession,
) -> (SyncPaths, BaselineHead) {
    let paths = SyncPaths::for_workspace(root);
    write_session(&paths, session).expect("session head");

    let remote_bytes = b"# created on another device\n".to_vec();
    let d_remote = body_digest(&remote_bytes);
    let remote = FakeRemotePort::new(
        RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![remote_entry("memo/new.md", d_remote.clone(), "tok-new")],
        )
        .expect("snap"),
        PublishReceipt {
            path_results: Vec::new(),
        },
        VerifiedRemoteState {
            results: vec![VerifyStatus::Verified {
                path: path("memo/new.md"),
                digest: d_remote,
                remote_token: "tok-new".to_owned(),
            }],
        },
    );
    let local = FakeLocalPort {
        entries: Vec::new(),
    };
    let cycle = run_sync_cycle(
        session,
        &local,
        &remote,
        BaselineHead::empty(),
        Some(&paths),
        true,
        None,
    )
    .expect("pull cycle");
    assert_eq!(
        cycle.batch.pull_present_count(),
        1,
        "expected PullPresent plan"
    );
    assert!(
        !root.join("memo/new.md").exists(),
        "no executor may write local bytes in this cycle"
    );
    (paths, cycle.baseline)
}

/// baseline must only record verified *and* locally-landed facts. A `PullPresent` path
/// whose bytes never reached the workspace must not enter the durable baseline.
#[test]
fn pull_present_advances_baseline_without_local_landing() {
    let temporary = tempdir().expect("temp");
    let session = SyncSession::new(fence_gen("ab", "ds"), SessionKind::Incremental, "adv-pull")
        .expect("session");
    let (_paths, baseline) = run_pull_present_first_cycle(temporary.path(), &session);
    assert!(
        baseline.get("memo/new.md").is_none(),
        "baseline recorded memo/new.md as synced although the pull never landed locally"
    );
}

/// with the poisoned baseline from cycle 1, the next cycle reads "local missing +
/// baseline-tracked + remote token matches" as a user delete — durable tombstone plus `EnsureAbsent`
/// for a file the user never saw on this device.
#[test]
fn never_landed_pull_is_tombstoned_as_phantom_user_delete() {
    let temporary = tempdir().expect("temp");
    let session = SyncSession::new(fence_gen("ab", "ds"), SessionKind::Incremental, "adv-pull2")
        .expect("session");
    let (paths, baseline) = run_pull_present_first_cycle(temporary.path(), &session);
    if baseline.get("memo/new.md").is_none() {
        // Cycle already refused to record the never-landed pull — the phantom-delete chain cannot
        // even be constructed; nothing left to prove.
        return;
    }

    // Cycle 2: identical remote listing, local still lacks the path.
    let d_remote = body_digest(b"# created on another device\n");
    let remote2 = FakeRemotePort::new(
        RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![remote_entry("memo/new.md", d_remote, "tok-new")],
        )
        .expect("snap"),
        PublishReceipt {
            path_results: vec![(
                path("memo/new.md"),
                PathPublishStatus::Applied {
                    new_token: "tok-del".to_owned(),
                },
            )],
        },
        VerifiedRemoteState {
            results: vec![VerifyStatus::AbsentVerified {
                path: path("memo/new.md"),
            }],
        },
    );
    let local = FakeLocalPort {
        entries: Vec::new(),
    };
    let cycle2 = run_sync_cycle(
        &session,
        &local,
        &remote2,
        baseline,
        Some(&paths),
        true,
        None,
    )
    .expect("cycle 2");

    let tombstones = read_tombstones(&paths).expect("tombstones");
    assert!(
        !tombstones.entries.iter().any(|e| e.path == "memo/new.md"),
        "durable tombstone recorded a user delete that never happened"
    );
    assert_eq!(
        cycle2.batch.ensure_absent_count(),
        0,
        "EnsureAbsent issued for a path that was only ever PullPresent (never landed)"
    );
    assert_eq!(
        remote2.publish_call_count(),
        0,
        "remote publish must not run for a phantom user delete"
    );
}

/// a fully-resolved conflict session is never cleared, and `session_covers_open_paths`
/// only checks path membership. When the same path diverges again, the new `OpenConflict` intent is
/// swallowed by the stale session: the durable record keeps `ResolvedKeepRemote`, `open_count` stays
/// 0, and re-resolution is refused (`conflict_path_already_resolved`).
#[test]
fn resolved_session_masks_fresh_divergence_on_same_path() {
    let temporary = tempdir().expect("temp");
    let root = temporary.path();
    let paths = SyncPaths::for_workspace(root);
    let session = SyncSession::new(fence_gen("ab", "ds"), SessionKind::Incremental, "adv-recon")
        .expect("session");
    write_session(&paths, &session).expect("session head");

    // Durable conflict session for memo/a.md: local L1 vs remote R1, both artifacts materialized.
    let local1 = b"# local v1\n".to_vec();
    let remote1 = b"# remote v1\n".to_vec();
    let d_l1 = body_digest(&local1);
    let d_r1 = body_digest(&remote1);
    let mut record = conflict_path_from_open(
        &path("memo/a.md"),
        Some(&d_l1),
        Some(&d_r1),
        None,
        Some("tok-r1"),
    )
    .expect("record");
    record.remote_artifact_ref = Some(
        write_conflict_artifact(&paths, "adv-recon", "remote", "memo/a.md", &remote1)
            .expect("remote artifact"),
    );
    record.local_artifact_ref = Some(
        write_conflict_artifact(&paths, "adv-recon", "local", "memo/a.md", &local1)
            .expect("local artifact"),
    );
    let conflict = ConflictSession::open(session.fence.clone(), "adv-recon", vec![record])
        .expect("open session");
    write_conflict_session(&paths, &conflict).expect("write conflict");

    // User resolves KeepRemote; host applies the pull (mutation + baseline advance).
    let resolved = resolve_sync_conflicts(
        &paths,
        1,
        &[ConflictResolution::KeepRemote {
            path: "memo/a.md".to_owned(),
        }],
    )
    .expect("resolve keep_remote");
    let pulls = collect_resolved_local_pull_mutations(&paths, &resolved.session).expect("pulls");
    assert_eq!(pulls.len(), 1);
    let mut baseline = BaselineHead::empty();
    baseline.fence = Some(session.fence.clone());
    advance_baseline_after_local_pull(&paths, 2, baseline, &pulls).expect("baseline after pull");

    // Same path diverges AGAIN: local edited to L2 while remote moved to R2.
    let local2 = b"# local v2\n".to_vec();
    let remote2 = b"# remote v2\n".to_vec();
    let d_l2 = body_digest(&local2);
    let d_r2 = body_digest(&remote2);
    let local = FakeLocalPort {
        entries: vec![LocalPathEntry {
            path: path("memo/a.md"),
            digest: d_l2,
        }],
    };
    let remote = FakeRemotePort::new(
        RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![remote_entry("memo/a.md", d_r2, "tok-r2")],
        )
        .expect("snap"),
        PublishReceipt {
            path_results: Vec::new(),
        },
        VerifiedRemoteState {
            results: Vec::new(),
        },
    );
    let baseline = read_baseline(&paths).expect("baseline");
    run_sync_cycle(
        &session,
        &local,
        &remote,
        baseline,
        Some(&paths),
        true,
        None,
    )
    .expect("cycle");

    // SAFE INVARIANT: the fresh divergence must surface as an open conflict again.
    let listed = list_sync_conflicts(&paths, 0, 100).expect("list");
    let record = listed
        .items
        .iter()
        .find(|item| item.path == "memo/a.md")
        .expect("conflict record for a.md");
    assert_eq!(
        record.status,
        ConflictPathStatus::Open,
        "fresh divergence on a previously-resolved path must reopen the conflict, \
         not inherit the stale ResolvedKeepRemote record"
    );
    let state = read_conflict_session_state(&paths).expect("session state");
    let ConflictSessionState::Present(current) = state else {
        panic!("conflict session must remain present");
    };
    assert_eq!(
        current.open_count(),
        1,
        "open conflict count must reflect the new divergence"
    );

    // And a fresh resolution must be accepted (revision fenced, not path-refused).
    let attempt = resolve_sync_conflicts(
        &paths,
        current.conflict_revision,
        &[ConflictResolution::KeepRemote {
            path: "memo/a.md".to_owned(),
        }],
    );
    assert!(
        attempt.is_ok(),
        "re-resolution of a fresh divergence refused: {attempt:?}"
    );
}

/// when the plan's open-conflict set is only partially covered by the existing session,
/// re-materialization rewrites the WHOLE session with fresh `Open` records — silently discarding a
/// `ResolvedKeepRemote`/`ResolvedKeepLocal`/`Merged` record that was resolved but not yet applied
/// (e.g. crash between resolve and the apply step, or worker restart before the session pull).
#[test]
fn pending_resolution_is_not_overwritten_by_new_conflict_page() {
    let temporary = tempdir().expect("temp");
    let paths = SyncPaths::for_workspace(temporary.path());
    let session = SyncSession::new(fence_gen("ab", "ds"), SessionKind::Incremental, "adv-mask")
        .expect("session");
    write_session(&paths, &session).expect("session head");

    // memo/a.md: conflict resolved KeepRemote; the local pull has NOT landed yet (crash window).
    let local1 = b"# local v1\n".to_vec();
    let remote1 = b"# remote v1\n".to_vec();
    let d_l1 = body_digest(&local1);
    let d_r1 = body_digest(&remote1);
    let mut record = conflict_path_from_open(
        &path("memo/a.md"),
        Some(&d_l1),
        Some(&d_r1),
        None,
        Some("tok-r1"),
    )
    .expect("record");
    record.remote_artifact_ref = Some(
        write_conflict_artifact(&paths, "adv-mask", "remote", "memo/a.md", &remote1)
            .expect("remote artifact"),
    );
    record.local_artifact_ref = Some(
        write_conflict_artifact(&paths, "adv-mask", "local", "memo/a.md", &local1)
            .expect("local artifact"),
    );
    let conflict = ConflictSession::open(session.fence.clone(), "adv-mask", vec![record])
        .expect("open session");
    write_conflict_session(&paths, &conflict).expect("write conflict");
    let resolved = resolve_sync_conflicts(
        &paths,
        1,
        &[ConflictResolution::KeepRemote {
            path: "memo/a.md".to_owned(),
        }],
    )
    .expect("resolve keep_remote");
    assert_eq!(
        resolved.session.paths.first().expect("p").status,
        ConflictPathStatus::ResolvedKeepRemote
    );

    // Baseline still holds the pre-conflict base digest for a.md (local pull not applied).
    let mut baseline = BaselineHead::empty();
    baseline.fence = Some(session.fence.clone());
    baseline.upsert(&path("memo/a.md"), &dig(9), "tok-base".to_owned());
    write_baseline(&paths, &baseline).expect("persist baseline");

    // New divergence on a second path memo/b.md (local Lb vs remote Rb); a.md is still
    // divergent locally (L1 vs R1) because the resolved pull never landed.
    let local_b = b"# local b\n".to_vec();
    let remote_b = b"# remote b\n".to_vec();
    let d_lb = body_digest(&local_b);
    let d_rb = body_digest(&remote_b);
    let local = FakeLocalPort {
        entries: vec![
            LocalPathEntry {
                path: path("memo/a.md"),
                digest: d_l1,
            },
            LocalPathEntry {
                path: path("memo/b.md"),
                digest: d_lb,
            },
        ],
    };
    let remote = FakeRemotePort::new(
        RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![
                remote_entry("memo/a.md", d_r1, "tok-r1"),
                remote_entry("memo/b.md", d_rb, "tok-rb"),
            ],
        )
        .expect("snap"),
        PublishReceipt {
            path_results: Vec::new(),
        },
        VerifiedRemoteState {
            results: Vec::new(),
        },
    );
    let bodies = ConflictBodySource::from_entries([
        ("memo/a.md", Some(local1), Some(remote1), None),
        ("memo/b.md", Some(local_b), Some(remote_b), None),
    ]);
    run_sync_cycle(
        &session,
        &local,
        &remote,
        baseline,
        Some(&paths),
        true,
        Some(&bodies),
    )
    .expect("cycle");

    // SAFE INVARIANT: the durable, un-applied KeepRemote resolution must survive the new
    // conflict materialization (either by keeping the record or by explicit staged recovery) —
    // it must not be silently re-opened.
    let ConflictSessionState::Present(current) =
        read_conflict_session_state(&paths).expect("session")
    else {
        panic!("conflict session vanished");
    };
    let record_a = current
        .paths
        .iter()
        .find(|item| item.path == "memo/a.md")
        .expect("a.md record");
    assert_eq!(
        record_a.status,
        ConflictPathStatus::ResolvedKeepRemote,
        "un-applied KeepRemote resolution was silently overwritten back to {:?} \
         by re-materialization",
        record_a.status
    );
}

/// the durable session fence is never re-validated against the live workspace generation
/// carried by the local snapshot. A session minted under generation A keeps driving cycles after
/// the workspace was re-minted to generation B (or the durable tree survived an identity change),
/// mixing baseline/tombstone facts across identities.
#[test]
fn stale_session_fence_is_not_revalidated_against_live_generation() {
    let temporary = tempdir().expect("temp");
    let paths = SyncPaths::for_workspace(temporary.path());
    // Session + baseline written under generation A / dataset ds-a.
    let session = SyncSession::new(
        fence_gen("aa", "ds-a"),
        SessionKind::Incremental,
        "adv-fence",
    )
    .expect("session");
    write_session(&paths, &session).expect("session head");

    // Live local snapshot now advertises generation B (workspace re-minted / different workspace
    // mounted at the same root while .lomo/sync/v1 survived).
    let local = StoreLocalSnapshotPort::from_store_snapshot(
        &"bb".repeat(32),
        Vec::<(String, String)>::new(),
    )
    .expect("local port");
    let remote = FakeRemotePort::new(
        RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
        PublishReceipt {
            path_results: Vec::new(),
        },
        VerifiedRemoteState {
            results: Vec::new(),
        },
    );

    let outcome = run_sync_cycle(
        &session,
        &local,
        &remote,
        BaselineHead::empty(),
        Some(&paths),
        false,
        None,
    );
    // SAFE INVARIANT: durable fence must be rejected when the live generation diverges
    // (`sync_identity_mismatch`), not silently trusted.
    let err = outcome.expect_err("cycle must refuse a stale session fence");
    assert_eq!(
        err.code(),
        "sync_identity_mismatch",
        "expected fence mismatch refusal, got {err:?}"
    );
}
