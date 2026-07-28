//! Behavior Contract — P5-11 host residual deepen (safe-behavior fixtures)
//!
//! - Unit under test: language-agnostic `stage5-safe-behavior-fixtures.v1.json` cases
//!   exercised against `lomo-sync` owner surfaces (`plan_intents`, `run_sync_cycle` /
//!   `apply_with_verify`, fence restore, diagnostics, action-class gates)
//! - Owning layer: `lomo-sync` (planner / durable / diagnostics); fixture is language-agnostic
//!   under `fixtures/baseline`
//! - Priority tier: P1 (P5-11 residual deepen; not formal exit)
//! - Capability: host hermetic differential lock for plan-facing and host-closeable
//!   state-machine cases without claiming Kotlin parity oracle or APK × 1.15.
//!
//! Scenarios:
//! - Given fixture file, when loaded, then schema 1 and case ids SB-01..SB-10 present.
//! - SB-01: Incomplete remote + baseline path missing → `ensure_absent_count` 0.
//! - SB-02: `FirstTakeover` local-only + remote-only → `ensure_absent` 0; `ensure_present` may be >0.
//! - SB-03: apply Applied + verify Failed → `baseline_advanced` false.
//! - SB-04: publish `PreconditionFailed` → `receipt_requires_replan`; no baseline advance; no force.
//! - SB-05: both-modified digests → `open_conflict_count` ≥ 1.
//! - SB-06: durable session fence G1 vs workspace G2 → matches rejects; no clean slate.
//! - SB-07: session + diagnostic export JSON contain no secret markers.
//! - SB-08: remote `unknown/tooling.bin` → `ReportUnrecognized`; no pull/delete/ensure.
//! - SB-09: `FirstTakeover` / Migration-class plan never emits `EnsureAbsent` (type-level gate).
//! - SB-10: `reset_sync_control_tree` leaves non-control user files (inbox-shaped) intact.
//!
//! Observable outcomes: intent counts, `baseline_advanced`, fence error codes, secret-free JSON,
//! path retention under control reset.
//! Excludes: APK hard gate measurement, four-ABI production SO ceiling claim, real providers,
//! arm64, production DI, Kotlin business planner re-run as oracle, formal APK×1.15.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use std::collections::BTreeSet;
    use std::path::PathBuf;

    use lomo_sync::{
        BaselineHead, ContentDigest, FakeLocalPort, FakeRemotePort, LocalPathEntry, LocalSnapshot,
        PathPublishStatus, PreparedRemoteBatch, ProviderNeutralIntent, PublishReceipt,
        RemotePathEntry, RemoteSnapshot, SessionKind, SnapshotCompleteness, SyncDiagnosticExport,
        SyncIdentityFence, SyncPath, SyncPaths, SyncSession, TombstoneSet, VerifiedRemoteState,
        VerifyStatus, apply_with_verify, build_default_diagnostic_export, is_owned_sync_user_path,
        plan_intents, reset_sync_control_tree, write_session,
    };
    use lomo_workspace::{RemoteDatasetId, RemoteIdentityDigest, WorkspaceGenerationId};
    use serde::Deserialize;
    use tempfile::tempdir;

    #[derive(Debug, Deserialize)]
    struct FixtureRoot {
        schema_version: u32,
        cases: Vec<FixtureCase>,
    }

    #[derive(Debug, Deserialize)]
    struct FixtureCase {
        id: String,
        title: String,
        when: String,
    }

    fn fixture_path() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../fixtures/baseline/stage5-safe-behavior-fixtures.v1.json")
    }

    fn dig(seed: u8) -> ContentDigest {
        ContentDigest::parse(&format!("{seed:02x}").repeat(32)).expect("digest")
    }

    fn path(raw: &str) -> SyncPath {
        SyncPath::parse(raw).expect("path")
    }

    fn load_fixture() -> FixtureRoot {
        let text = std::fs::read_to_string(fixture_path()).expect("read fixture");
        serde_json::from_str(&text).expect("parse fixture")
    }

    fn fence_g1() -> SyncIdentityFence {
        SyncIdentityFence::from_parts(
            &WorkspaceGenerationId::parse(&"ab".repeat(32)).expect("gen"),
            &RemoteDatasetId::parse("ds").expect("ds"),
            &RemoteIdentityDigest::parse(&"cd".repeat(32)).expect("id"),
        )
    }

    fn fence_g2() -> SyncIdentityFence {
        SyncIdentityFence::from_parts(
            &WorkspaceGenerationId::parse(&"ef".repeat(32)).expect("gen2"),
            &RemoteDatasetId::parse("ds").expect("ds"),
            &RemoteIdentityDigest::parse(&"cd".repeat(32)).expect("id"),
        )
    }

    #[test]
    fn fixture_schema_and_case_inventory_present() {
        let root = load_fixture();
        assert_eq!(root.schema_version, 1);
        let ids: BTreeSet<_> = root.cases.iter().map(|c| c.id.as_str()).collect();
        for expected in [
            "SB-01", "SB-02", "SB-03", "SB-04", "SB-05", "SB-06", "SB-07", "SB-08", "SB-09",
            "SB-10",
        ] {
            assert!(ids.contains(expected), "missing fixture case {expected}");
        }
        assert_eq!(root.cases.len(), 10);
        assert!(root.cases.iter().any(|c| c.id == "SB-01"
            && c.title == "partial_listing_no_delete"
            && c.when == "plan"));
    }

    #[test]
    fn sb01_partial_listing_no_delete() {
        let mut baseline = BaselineHead::empty();
        baseline.upsert(&path("memo/a.md"), &dig(1), "tok-a".to_owned());
        let local = LocalSnapshot {
            entries: Vec::new(),
            workspace_generation: None,
        };
        let remote =
            RemoteSnapshot::new(SnapshotCompleteness::Incomplete, Vec::new()).expect("snap");
        let batch = plan_intents(
            SessionKind::Incremental,
            &local,
            &remote,
            &baseline,
            &TombstoneSet::empty(),
        )
        .expect("plan");
        assert_eq!(batch.ensure_absent_count(), 0);
    }

    #[test]
    fn sb02_first_takeover_no_user_file_delete() {
        let local = LocalSnapshot {
            entries: vec![LocalPathEntry {
                path: path("memo/a.md"),
                digest: dig(1),
            }],
            workspace_generation: None,
        };
        let remote = RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![RemotePathEntry {
                path: path("memo/b.md"),
                digest: dig(2),
                revision_token: "r1".to_owned(),
            }],
        )
        .expect("snap");
        let batch = plan_intents(
            SessionKind::FirstTakeover,
            &local,
            &remote,
            &BaselineHead::empty(),
            &TombstoneSet::empty(),
        )
        .expect("plan");
        assert_eq!(batch.ensure_absent_count(), 0);
        assert!(
            batch.ensure_present_count() >= 1,
            "local-only should ensure present: {:?}",
            batch.intents
        );
    }

    #[test]
    fn sb03_verify_failure_no_baseline_advance() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let session =
            SyncSession::new(fence_g1(), SessionKind::Incremental, "s-sb03").expect("session");
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
        assert!(result.baseline.entries.is_empty());
        let on_disk = lomo_sync::read_baseline(&paths).expect("read");
        assert!(on_disk.entries.is_empty());
    }

    #[test]
    fn sb04_conditional_write_failure_replans() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let session =
            SyncSession::new(fence_g1(), SessionKind::Incremental, "s-sb04").expect("session");
        let local = FakeLocalPort {
            entries: vec![LocalPathEntry {
                path: path("memo/a.md"),
                digest: dig(9),
            }],
        };
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
            PublishReceipt {
                path_results: vec![(path("memo/a.md"), PathPublishStatus::PreconditionFailed)],
            },
            // PreconditionFailed paths are not re-verified; empty verify must not invent success.
            VerifiedRemoteState {
                results: Vec::new(),
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
        let receipt = result.receipt.as_ref().expect("receipt");
        assert!(
            PreparedRemoteBatch::receipt_requires_replan(receipt),
            "PreconditionFailed must force replan: {receipt:?}"
        );
        assert!(!result.baseline_advanced);
        // Owner surface never emits force-push; replan is the only recovery.
        assert_eq!(result.baseline.entries.len(), 0);
        assert!(
            PreparedRemoteBatch::receipt_requires_replan(receipt),
            "replan gate must remain true"
        );
    }

    #[test]
    fn sb05_both_modified_opens_conflict() {
        let mut baseline = BaselineHead::empty();
        baseline.upsert(&path("memo/a.md"), &dig(0), "tok-base".to_owned());
        let local = LocalSnapshot {
            entries: vec![LocalPathEntry {
                path: path("memo/a.md"),
                digest: dig(1),
            }],
            workspace_generation: None,
        };
        let remote = RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![RemotePathEntry {
                path: path("memo/a.md"),
                digest: dig(2),
                revision_token: "tok-remote".to_owned(),
            }],
        )
        .expect("snap");
        let batch = plan_intents(
            SessionKind::Incremental,
            &local,
            &remote,
            &baseline,
            &TombstoneSet::empty(),
        )
        .expect("plan");
        assert!(
            batch.open_conflict_count() >= 1,
            "both-modified must open conflict: {:?}",
            batch.intents
        );
        assert_eq!(batch.ensure_absent_count(), 0);
    }

    #[test]
    fn sb06_generation_mismatch_reject_no_clean_slate() {
        let durable = fence_g1();
        let current = fence_g2();
        let err = durable
            .matches(
                &WorkspaceGenerationId::parse(&current.workspace_generation).expect("g"),
                &RemoteDatasetId::parse(&current.remote_dataset_id).expect("ds"),
                &RemoteIdentityDigest::parse(&current.remote_identity_digest).expect("id"),
            )
            .expect_err("mismatch");
        assert_eq!(err.code(), "sync_identity_mismatch");
        // Fence values remain intact (reject, not wipe).
        assert_eq!(
            durable.workspace_generation,
            fence_g1().workspace_generation
        );
        assert_ne!(durable.workspace_generation, current.workspace_generation);
    }

    #[test]
    fn sb07_secrets_not_in_durable_state_or_diagnostics() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let session =
            SyncSession::new(fence_g1(), SessionKind::Incremental, "s-sb07").expect("session");
        write_session(&paths, &session).expect("write");
        let raw = std::fs::read(&paths.session).expect("read session bytes");
        let text = String::from_utf8_lossy(&raw);
        for forbidden in [
            "password",
            "aws_secret",
            "secret_access",
            "private_key",
            "bearer ",
            "authorization",
        ] {
            assert!(
                !text.to_ascii_lowercase().contains(forbidden),
                "session record leaked marker {forbidden}: {text}"
            );
        }

        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence_g1());
        baseline.upsert(&path("memo/a.md"), &dig(1), "secret-token-value".to_owned());
        let export = build_default_diagnostic_export(
            Some("s-sb07"),
            Some(SessionKind::Incremental),
            None,
            &baseline,
            None,
            &[],
            &[],
        );
        let json = export.to_json().expect("json");
        assert!(
            SyncDiagnosticExport::is_secret_free_json(&json),
            "diagnostic leaked secret-like content: {json}"
        );
        assert!(!json.contains("secret-token-value"));
    }

    #[test]
    fn sb08_unrecognized_remote_path_report_only() {
        assert!(!is_owned_sync_user_path("unknown/tooling.bin"));
        assert!(is_owned_sync_user_path("memo/a.md"));
        assert!(is_owned_sync_user_path("media/photo.jpg"));

        let local = LocalSnapshot {
            entries: Vec::new(),
            workspace_generation: None,
        };
        let remote = RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![RemotePathEntry {
                path: path("unknown/tooling.bin"),
                digest: dig(3),
                revision_token: "r-x".to_owned(),
            }],
        )
        .expect("snap");
        let batch = plan_intents(
            SessionKind::Incremental,
            &local,
            &remote,
            &BaselineHead::empty(),
            &TombstoneSet::empty(),
        )
        .expect("plan");
        assert_eq!(batch.report_unrecognized_count(), 1);
        assert_eq!(batch.ensure_absent_count(), 0);
        assert_eq!(batch.ensure_present_count(), 0);
        assert_eq!(batch.pull_present_count(), 0);
        assert_eq!(batch.open_conflict_count(), 0);
        assert!(
            batch.intents.iter().any(|intent| matches!(
                intent,
                ProviderNeutralIntent::ReportUnrecognized { path }
                    if path.as_str() == "unknown/tooling.bin"
            )),
            "must report only: {:?}",
            batch.intents
        );
    }

    #[test]
    fn sb09_migration_or_takeover_forbids_user_file_delete() {
        // Host typecheck lock: FirstTakeover and Migration are the migration/reset/takeover
        // action class on SessionKind. EnsureAbsent is forbidden regardless of complete listing
        // + baseline.
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence_g1());
        baseline.upsert(&path("memo/a.md"), &dig(1), "tok-a".to_owned());
        let local = LocalSnapshot {
            entries: Vec::new(),
            workspace_generation: None,
        };
        let remote = RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap");
        for kind in [SessionKind::FirstTakeover, SessionKind::Migration] {
            assert!(
                kind.is_migration_or_takeover_class(),
                "{kind:?} must be migration-class"
            );
            assert!(!kind.may_emit_user_file_delete());
            let batch = plan_intents(kind, &local, &remote, &baseline, &TombstoneSet::empty())
                .expect("plan");
            assert_eq!(
                batch.ensure_absent_count(),
                0,
                "{kind:?} must not EnsureAbsent: {:?}",
                batch.intents
            );
            assert!(
                !batch
                    .intents
                    .iter()
                    .any(|intent| matches!(intent, ProviderNeutralIntent::EnsureAbsent { .. })),
                "migration class must not emit user-file delete: {:?}",
                batch.intents
            );
        }
    }

    #[test]
    fn sb10_sync_inbox_survives_remote_tail_deletion() {
        // Host stand-in for cutover tail cleanup: control-tree reset must not delete user inbox
        // files outside `.lomo/sync/v1` (pending review retention).
        let temporary = tempdir().expect("temp");
        let workspace = temporary.path().join("ws");
        std::fs::create_dir_all(workspace.join("inbox")).expect("inbox dir");
        let inbox_review = workspace.join("inbox").join("pending-review.md");
        std::fs::write(&inbox_review, b"# pending review\n").expect("seed inbox");
        let user_memo = workspace.join("memo").join("keep.md");
        std::fs::create_dir_all(user_memo.parent().expect("parent")).expect("memo dir");
        std::fs::write(&user_memo, b"# keep\n").expect("seed memo");

        let paths = SyncPaths::for_workspace(&workspace);
        let session =
            SyncSession::new(fence_g1(), SessionKind::Incremental, "s-sb10").expect("session");
        write_session(&paths, &session).expect("write session");
        assert!(paths.session.exists());

        reset_sync_control_tree(&paths).expect("reset control");
        assert!(!paths.session.exists());
        assert!(
            inbox_review.exists(),
            "inbox reviews must be retained across control-tail deletion"
        );
        assert!(
            user_memo.exists(),
            "user memos must survive control-tree reset"
        );
        assert_eq!(
            std::fs::read(&inbox_review).expect("read inbox"),
            b"# pending review\n"
        );
    }
}
