//! Behavior Contract (P5-08 conflict, delete, recovery, diagnostics)
//!
//! Capability: durable conflict sessions with expected conflict revision; Markdown `MergedBody`
//! re-parsed via workspace parser + resource limits; binary KeepLocal/KeepRemote/SkipForNow only;
//! stale resolution rejects without overwrite; `SkipForNow` holds baseline for that path; user
//! delete is tombstone-first under hard gates; partial listing never deletes; delete-vs-edit opens
//! conflict; diagnostics export digests/paths/status/error codes only (no body/credentials).
//!
//! Scenarios:
//! - Given both-modified Markdown, When conflict opens and resolves with expected revision, Then
//!   session advances; stale expected revision rejects.
//! - Given binary path, When `MergedBody` is submitted, Then validation rejects.
//! - Given `SkipForNow` on one path, When listed, Then status is skipped and
//!   `baseline_must_hold_for_path` is true for that path.
//! - Given user delete gates incomplete (first-takeover / partial / no baseline), When
//!   tombstone-first is attempted, Then reject codes fire and no tombstone is written.
//! - Given gates pass, When tombstone-first runs, Then tombstone is durable before `EnsureAbsent`.
//! - Given remote gone + local edited, When planned, Then `OpenConflict` (delete-vs-edit).
//! - Given plan emits `OpenConflict`, When materialize runs with candidate bodies whose SHA-256
//!   equals planner digests, Then durable session + digests + artifact refs land under
//!   `.lomo/sync/v1` before conflict is open (fail-closed on digest mismatch / missing body).
//! - Given open/skip path in durable session, When cycle commits baseline, Then held paths do not
//!   advance; resolved `KeepLocal` can publish + verify + baseline after resolve with body wire.
//! - Given remaining user-delete reject gates (no baseline / not in baseline / token / local present),
//!   When tombstone-first is attempted, Then each reject code fires.
//! - Given invalid `MergedBody` (resource budget), When resolve runs, Then validation/resource-limit
//!   rejects without advancing revision.
//! - Given durable tombstone after crash, When `run_sync_cycle` revives the session, Then
//!   `recover_pending_delete_intent` re-issues `EnsureAbsent` and verify-before-baseline advances.
//! - Given identity fence mismatch on revival, When `assert_fence_for_revival` runs, Then
//!   validation rejects.
//! - Given diagnostic export, When serialized, Then secret markers are absent.
//!
//! Observable outcomes: `conflict_revision`, error codes, tombstone presence, intent kinds, JSON
//! redaction, durable artifact refs (SHA-256-coupled), baseline hold across cycle, publish count,
//! published body bytes/digests on KeepLocal/Merged apply.
//! Excludes: production DI, real providers, full multi-process OS-kill crash-at-every-transition
//! graph (host crash-at-transition matrix is included and GREEN; not claimed as full OS multi-process
//! death). Durable on-disk multipart process-death resume is owned by `s3_adapter_contract` (host).
//! KeepRemote/Merged **local** store expected-revision apply is host-proven via
//! `collect_resolved_local_pull_mutations` + store `LocalSyncMutationBatch` +
//! `advance_baseline_after_local_pull` (status alone still must not pretend apply).

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_core::{ErrorCategory, OperationId};
    use lomo_store::{
        LocalSyncMutation, LocalSyncMutationBatch, MemoCommand, MemoCommandKind, Store,
        fingerprint_content,
    };
    use lomo_sync::{
        BaselineHead, ConflictBodySource, ConflictContentKind, ConflictPathStatus,
        ConflictResolution, ConflictSession, ContentDigest, DeleteVersusEdit, FakeLocalPort,
        FakeRemotePort, LocalPathEntry, LocalSnapshot, PathPublishStatus, ProviderNeutralIntent,
        PublishReceipt, RecoverDeleteRequest, RemotePathEntry, RemoteSnapshot, SessionKind,
        SnapshotCompleteness, SyncDiagnosticError, SyncDiagnosticExport, SyncIdentityFence,
        SyncPath, SyncPaths, SyncSession, TombstoneSet, UserDeleteContext, UserDeleteRequest,
        VerifiedRemoteState, VerifyStatus, advance_baseline_after_local_pull,
        apply_resolved_conflicts_remote, assert_fence_for_revival, baseline_must_hold_for_path,
        build_default_diagnostic_export, classify_delete_versus_edit,
        collect_resolved_local_pull_mutations, collect_resolved_present_bodies,
        conflict_path_from_open, error_category, list_sync_conflicts,
        materialize_conflicts_from_plan, plan_intents, read_baseline, read_conflict_artifact,
        read_conflict_session, read_session, read_tombstones, record_user_delete_tombstone_first,
        recover_pending_delete_intent, reset_sync_control_tree, resolve_sync_conflicts,
        run_sync_cycle, tombstone_authoritative_for_fence, user_delete_gate_for_path,
        validate_merged_markdown_body, write_baseline, write_conflict_artifact,
        write_conflict_session, write_diagnostic_export, write_session,
    };
    use lomo_workspace::{RemoteDatasetId, RemoteIdentityDigest, WorkspaceGenerationId};
    use tempfile::tempdir;

    /// Synthetic path-keyed digest for non-body-coupled scenarios (tombstone/delete gates).
    fn dig(seed: u8) -> ContentDigest {
        ContentDigest::parse(&format!("{seed:02x}").repeat(32)).expect("digest")
    }

    /// Real content-addressed digest (SHA-256 lowercase hex) for body-wire contracts.
    fn body_digest(bytes: &[u8]) -> ContentDigest {
        ContentDigest::from_bytes(bytes)
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

    fn open_markdown_conflict(paths: &SyncPaths) -> ConflictSession {
        let record = conflict_path_from_open(
            &path("memo/a.md"),
            Some(&dig(1)),
            Some(&dig(2)),
            Some(&dig(0)),
            Some("tok-r"),
        )
        .expect("record");
        assert_eq!(record.kind, ConflictContentKind::Markdown);
        let session = ConflictSession::open(fence(), "c-session-1", vec![record]).expect("open");
        write_conflict_session(paths, &session).expect("write");
        session
    }

    #[test]
    fn open_conflict_session_persists_digests_and_revision_one() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let session = open_markdown_conflict(&paths);
        let loaded = read_conflict_session(&paths).expect("read");
        assert_eq!(loaded.conflict_revision, 1);
        assert_eq!(loaded.session_id, "c-session-1");
        assert_eq!(loaded.paths.len(), 1);
        let first = loaded.paths.first().expect("path");
        assert_eq!(first.local_digest.as_deref(), Some(dig(1).as_str()));
        assert_eq!(first.remote_digest.as_deref(), Some(dig(2).as_str()));
        assert_eq!(first.baseline_digest.as_deref(), Some(dig(0).as_str()));
        assert_eq!(first.remote_token.as_deref(), Some("tok-r"));
        assert_eq!(loaded.open_count(), 1);
        assert_eq!(session.conflict_revision, 1);
    }

    #[test]
    fn resolve_with_expected_revision_advances_monotonic_revision() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        open_markdown_conflict(&paths);
        let result = resolve_sync_conflicts(
            &paths,
            1,
            &[ConflictResolution::KeepLocal {
                path: "memo/a.md".to_owned(),
            }],
        )
        .expect("resolve");
        assert_eq!(result.session.conflict_revision, 2);
        assert_eq!(result.applied_paths, vec!["memo/a.md".to_owned()]);
        assert_eq!(
            result.session.paths.first().expect("path").status,
            ConflictPathStatus::ResolvedKeepLocal
        );
        let page = list_sync_conflicts(&paths, 0, 10).expect("page");
        assert_eq!(page.conflict_revision, 2);
        assert_eq!(
            page.items.first().expect("item").status,
            ConflictPathStatus::ResolvedKeepLocal
        );
    }

    #[test]
    fn stale_resolution_rejects_without_advancing_or_overwriting() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        open_markdown_conflict(&paths);
        resolve_sync_conflicts(
            &paths,
            1,
            &[ConflictResolution::SkipForNow {
                path: "memo/a.md".to_owned(),
            }],
        )
        .expect("first resolve");
        let err = resolve_sync_conflicts(
            &paths,
            1,
            &[ConflictResolution::KeepRemote {
                path: "memo/a.md".to_owned(),
            }],
        )
        .expect_err("stale");
        assert_eq!(err.code(), "conflict_revision_stale");
        assert_eq!(error_category(&err), ErrorCategory::Conflict);
        let loaded = read_conflict_session(&paths).expect("read");
        assert_eq!(loaded.conflict_revision, 2);
        assert_eq!(
            loaded.paths.first().expect("path").status,
            ConflictPathStatus::SkippedForNow
        );
    }

    #[test]
    fn skip_for_now_holds_baseline_for_that_path() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let records = vec![
            conflict_path_from_open(
                &path("memo/a.md"),
                Some(&dig(1)),
                Some(&dig(2)),
                Some(&dig(0)),
                Some("t1"),
            )
            .expect("a"),
            conflict_path_from_open(
                &path("memo/b.md"),
                Some(&dig(3)),
                Some(&dig(4)),
                Some(&dig(0)),
                Some("t2"),
            )
            .expect("b"),
        ];
        let session = ConflictSession::open(fence(), "multi", records).expect("s");
        write_conflict_session(&paths, &session).expect("write");
        let result = resolve_sync_conflicts(
            &paths,
            1,
            &[ConflictResolution::SkipForNow {
                path: "memo/a.md".to_owned(),
            }],
        )
        .expect("skip");
        // SkipForNow and still-Open paths both hold baseline; unrelated paths may complete.
        assert!(baseline_must_hold_for_path(&result.session, "memo/a.md"));
        assert!(baseline_must_hold_for_path(&result.session, "memo/b.md"));
        assert!(!baseline_must_hold_for_path(
            &result.session,
            "memo/unrelated.md"
        ));
        assert_eq!(
            result.session.paths.first().expect("a").status,
            ConflictPathStatus::SkippedForNow
        );
        assert_eq!(
            result.session.paths.get(1).expect("b").status,
            ConflictPathStatus::Open
        );
        // After KeepLocal on b, that path may advance baseline (hold becomes false).
        let after_b = resolve_sync_conflicts(
            &paths,
            2,
            &[ConflictResolution::KeepLocal {
                path: "memo/b.md".to_owned(),
            }],
        )
        .expect("keep b");
        assert!(baseline_must_hold_for_path(&after_b.session, "memo/a.md"));
        assert!(!baseline_must_hold_for_path(&after_b.session, "memo/b.md"));
    }

    #[test]
    fn binary_merged_body_is_rejected() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let record = conflict_path_from_open(
            &path("media/photo.jpg"),
            Some(&dig(1)),
            Some(&dig(2)),
            None,
            Some("tok"),
        )
        .expect("record");
        assert_eq!(record.kind, ConflictContentKind::Binary);
        let session = ConflictSession::open(fence(), "bin", vec![record]).expect("open");
        write_conflict_session(&paths, &session).expect("write");
        let err = resolve_sync_conflicts(
            &paths,
            1,
            &[ConflictResolution::MergedBody {
                path: "media/photo.jpg".to_owned(),
                body: "# not allowed for binary\n".to_owned(),
            }],
        )
        .expect_err("binary merge");
        assert_eq!(err.code(), "conflict_merged_body_binary_forbidden");
        let loaded = read_conflict_session(&paths).expect("unchanged rev");
        assert_eq!(loaded.conflict_revision, 1);
    }

    #[test]
    fn markdown_merged_body_is_reparsed_via_workspace() {
        validate_merged_markdown_body("# Title\n\nhello world\n").expect("valid md");
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        open_markdown_conflict(&paths);
        let result = resolve_sync_conflicts(
            &paths,
            1,
            &[ConflictResolution::MergedBody {
                path: "memo/a.md".to_owned(),
                body: "# Merged\n\nresolved body\n".to_owned(),
            }],
        )
        .expect("merge");
        let first = result.session.paths.first().expect("path");
        assert_eq!(first.status, ConflictPathStatus::ResolvedMerged);
        assert!(first.local_artifact_ref.is_some());
    }

    #[test]
    fn user_delete_rejects_first_takeover_and_partial_listing() {
        let f = fence();
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(f.clone());
        baseline.upsert(&path("memo/a.md"), &dig(1), "tok-a".to_owned());
        let path_a = path("memo/a.md");
        let gate_ft = user_delete_gate_for_path(&UserDeleteContext {
            session_kind: SessionKind::FirstTakeover,
            remote_completeness: SnapshotCompleteness::Complete,
            fence: &f,
            baseline: &baseline,
            path: &path_a,
            local_has_path: false,
            observed_remote_token: Some("tok-a"),
        })
        .expect("gate");
        assert_eq!(gate_ft.reject_code(), Some("user_delete_first_takeover"));

        let gate_partial = user_delete_gate_for_path(&UserDeleteContext {
            session_kind: SessionKind::Incremental,
            remote_completeness: SnapshotCompleteness::Incomplete,
            fence: &f,
            baseline: &baseline,
            path: &path_a,
            local_has_path: false,
            observed_remote_token: Some("tok-a"),
        })
        .expect("gate");
        assert_eq!(
            gate_partial.reject_code(),
            Some("user_delete_partial_listing")
        );
    }

    #[test]
    fn user_delete_tombstone_first_then_ensure_absent() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let f = fence();
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(f.clone());
        let path_a = path("memo/a.md");
        let digest = dig(1);
        baseline.upsert(&path_a, &digest, "tok-a".to_owned());

        let intent = record_user_delete_tombstone_first(&UserDeleteRequest {
            paths: &paths,
            fence: &f,
            baseline: &baseline,
            session_kind: SessionKind::Incremental,
            remote_completeness: SnapshotCompleteness::Complete,
            path: &path_a,
            local_has_path: false,
            observed_remote_token: Some("tok-a"),
            content_digest: &digest,
        })
        .expect("delete");
        assert!(matches!(
            intent,
            ProviderNeutralIntent::EnsureAbsent {
                ref path,
                ref expected_remote_token
            } if path.as_str() == "memo/a.md" && expected_remote_token == "tok-a"
        ));
        let tombstones = read_tombstones(&paths).expect("tombstones");
        assert!(tombstones.contains_path("memo/a.md"));
        assert!(tombstone_authoritative_for_fence(
            &tombstones,
            "memo/a.md",
            &f
        ));
    }

    #[test]
    fn crash_between_tombstone_and_delete_recovers_ensure_absent() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let f = fence();
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(f.clone());
        let path_a = path("memo/a.md");
        let digest = dig(1);
        baseline.upsert(&path_a, &digest, "tok-a".to_owned());
        // Simulate crash: tombstone durable, remote delete never ran.
        let _intent = record_user_delete_tombstone_first(&UserDeleteRequest {
            paths: &paths,
            fence: &f,
            baseline: &baseline,
            session_kind: SessionKind::Incremental,
            remote_completeness: SnapshotCompleteness::Complete,
            path: &path_a,
            local_has_path: false,
            observed_remote_token: Some("tok-a"),
            content_digest: &digest,
        })
        .expect("tombstone first");
        let tombstones = read_tombstones(&paths).expect("tombstones");
        let recovered = recover_pending_delete_intent(&RecoverDeleteRequest {
            fence: &f,
            baseline: &baseline,
            tombstones: &tombstones,
            session_kind: SessionKind::Incremental,
            remote_completeness: SnapshotCompleteness::Complete,
            path: &path_a,
            local_has_path: false,
            remote_token: Some("tok-a"),
            remote_digest: Some(&digest),
        })
        .expect("recover")
        .expect("intent");
        assert!(matches!(
            recovered,
            ProviderNeutralIntent::EnsureAbsent { .. }
        ));
        // Partial listing must not re-issue delete.
        let blocked = recover_pending_delete_intent(&RecoverDeleteRequest {
            fence: &f,
            baseline: &baseline,
            tombstones: &tombstones,
            session_kind: SessionKind::Incremental,
            remote_completeness: SnapshotCompleteness::Incomplete,
            path: &path_a,
            local_has_path: false,
            remote_token: Some("tok-a"),
            remote_digest: Some(&digest),
        })
        .expect("partial");
        assert!(blocked.is_none());
    }

    #[test]
    fn delete_versus_edit_opens_conflict_when_local_edited_and_remote_gone() {
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/a.md"), &dig(1), "tok-a".to_owned());
        let local = LocalSnapshot {
            entries: vec![LocalPathEntry {
                path: path("memo/a.md"),
                digest: dig(9),
            }],
            workspace_generation: None,
        };
        let remote = RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap");
        let batch = plan_intents(
            SessionKind::Incremental,
            &local,
            &remote,
            &baseline,
            &TombstoneSet::empty(),
        )
        .expect("plan");
        assert_eq!(batch.open_conflict_count(), 1);
        assert_eq!(batch.ensure_absent_count(), 0);
        assert_eq!(
            classify_delete_versus_edit(Some(&dig(1)), Some(&dig(9)), false),
            DeleteVersusEdit::LocalEditRemoteDelete
        );
    }

    #[test]
    fn pure_remote_delete_emits_ensure_absent_when_gates_pass() {
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/a.md"), &dig(1), "tok-a".to_owned());
        let local = LocalSnapshot {
            entries: Vec::new(),
            workspace_generation: None,
        };
        let remote = RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap");
        let batch = plan_intents(
            SessionKind::Incremental,
            &local,
            &remote,
            &baseline,
            &TombstoneSet::empty(),
        )
        .expect("plan");
        assert_eq!(batch.ensure_absent_count(), 1);
        assert_eq!(batch.open_conflict_count(), 0);
    }

    #[test]
    fn offline_revival_fence_mismatch_rejects() {
        let durable = fence();
        let mut other = fence();
        other.remote_dataset_id = "other-ds".to_owned();
        let err = assert_fence_for_revival(&durable, &other).expect_err("mismatch");
        assert_eq!(err.code(), "sync_identity_mismatch");
    }

    #[test]
    fn identity_reset_clears_control_tree_not_user_files() {
        let temporary = tempdir().expect("temp");
        let root = temporary.path();
        let paths = SyncPaths::for_workspace(root);
        open_markdown_conflict(&paths);
        let user_file = root.join("memo/user.md");
        std::fs::create_dir_all(user_file.parent().expect("parent")).expect("dir");
        std::fs::write(&user_file, b"user content").expect("user");
        reset_sync_control_tree(&paths).expect("reset");
        assert!(!paths.conflicts.exists());
        assert!(!paths.session.exists());
        assert!(user_file.exists(), "user files must survive identity reset");
        assert_eq!(std::fs::read(&user_file).expect("read"), b"user content");
    }

    #[test]
    fn diagnostic_export_is_secret_free() {
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/a.md"), &dig(1), "secret-token-value".to_owned());
        let export = build_default_diagnostic_export(
            Some("sess-1"),
            Some(SessionKind::Incremental),
            Some(3),
            &baseline,
            None,
            &[SyncDiagnosticError {
                code: "example_error".to_owned(),
                category: "validation".to_owned(),
                path: Some("memo/a.md".to_owned()),
            }],
            &[],
        );
        let json = export.to_json().expect("json");
        assert!(
            SyncDiagnosticExport::is_secret_free_json(&json),
            "export leaked secret-like content: {json}"
        );
        assert!(!json.contains("secret-token-value"));
        assert!(json.contains("memo/a.md"));
        assert!(json.contains(&dig(1).as_str().to_owned()) || json.contains("baseline"));
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let out = write_diagnostic_export(&paths, &export).expect("write");
        assert!(out.exists());
    }

    #[test]
    fn open_conflict_from_plan_materializes_session_and_artifacts() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let local_bytes = b"# local body\n".to_vec();
        let remote_bytes = b"# remote body\n".to_vec();
        let baseline_bytes = b"# base body\n".to_vec();
        let local_d = body_digest(&local_bytes);
        let remote_d = body_digest(&remote_bytes);
        let baseline_d = body_digest(&baseline_bytes);

        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/a.md"), &baseline_d, "tok-base".to_owned());
        let local = LocalSnapshot {
            entries: vec![LocalPathEntry {
                path: path("memo/a.md"),
                digest: local_d.clone(),
            }],
            workspace_generation: None,
        };
        let remote = RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![RemotePathEntry {
                path: path("memo/a.md"),
                digest: remote_d.clone(),
                revision_token: "tok-r".to_owned(),
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
        assert_eq!(batch.open_conflict_count(), 1);

        // Body wire: SHA-256(body) must equal planner digests (fail-closed).
        let bodies = ConflictBodySource::from_entries([(
            "memo/a.md",
            Some(local_bytes.clone()),
            Some(remote_bytes.clone()),
            Some(baseline_bytes),
        )]);
        let session = materialize_conflicts_from_plan(
            &paths,
            &fence(),
            "mat-sess-1",
            &batch,
            &remote,
            &bodies,
        )
        .expect("materialize")
        .expect("session present");
        assert_eq!(session.conflict_revision, 1);
        assert_eq!(session.open_count(), 1);
        let first = session.paths.first().expect("path");
        assert_eq!(first.local_digest.as_deref(), Some(local_d.as_str()));
        assert_eq!(first.remote_digest.as_deref(), Some(remote_d.as_str()));
        assert_eq!(first.baseline_digest.as_deref(), Some(baseline_d.as_str()));
        assert_eq!(first.remote_token.as_deref(), Some("tok-r"));
        assert!(first.local_artifact_ref.is_some());
        assert!(first.remote_artifact_ref.is_some());
        assert!(first.baseline_artifact_ref.is_some());
        let loaded = read_conflict_session(&paths).expect("durable");
        assert_eq!(loaded.session_id, "mat-sess-1");
        let local_ref = first.local_artifact_ref.as_deref().expect("local ref");
        assert_eq!(
            read_conflict_artifact(&paths, local_ref).expect("read local"),
            local_bytes
        );
        let remote_ref = first.remote_artifact_ref.as_deref().expect("remote ref");
        assert_eq!(
            read_conflict_artifact(&paths, remote_ref).expect("read remote"),
            remote_bytes
        );
    }

    #[test]
    fn materialize_rejects_body_digest_mismatch() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
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
                revision_token: "tok-r".to_owned(),
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
        // Bodies do not match dig(1)/dig(2)/dig(0) — fail closed.
        let bodies = ConflictBodySource::from_entries([(
            "memo/a.md",
            Some(b"# local body\n".to_vec()),
            Some(b"# remote body\n".to_vec()),
            Some(b"# base body\n".to_vec()),
        )]);
        let err =
            materialize_conflicts_from_plan(&paths, &fence(), "mismatch", &batch, &remote, &bodies)
                .expect_err("digest mismatch");
        assert_eq!(err.code(), "conflict_candidate_body_digest_mismatch");
        assert!(!paths.conflicts.exists(), "mismatch must not leave session");
    }

    #[test]
    fn materialize_without_candidate_bodies_is_rejected() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
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
                revision_token: "tok-r".to_owned(),
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
        let empty = ConflictBodySource::empty();
        let err =
            materialize_conflicts_from_plan(&paths, &fence(), "hollow", &batch, &remote, &empty)
                .expect_err("hollow");
        assert_eq!(err.code(), "conflict_candidate_body_missing");
        assert!(
            !paths.conflicts.exists(),
            "hollow open must not leave session"
        );
    }

    #[test]
    fn run_sync_cycle_rejects_hollow_open_without_candidate_bodies() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/a.md"), &dig(0), "tok-base".to_owned());
        let local = FakeLocalPort {
            entries: vec![LocalPathEntry {
                path: path("memo/a.md"),
                digest: dig(1),
            }],
        };
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(
                SnapshotCompleteness::Complete,
                vec![RemotePathEntry {
                    path: path("memo/a.md"),
                    digest: dig(2),
                    revision_token: "tok-r".to_owned(),
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
        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "hollow-c").expect("session");
        let err = run_sync_cycle(
            &session,
            &local,
            &remote,
            baseline,
            Some(&paths),
            true,
            None,
        )
        .expect_err("hollow open");
        assert_eq!(err.code(), "conflict_candidate_body_missing");
        assert!(!paths.conflicts.exists());
        assert_eq!(remote.publish_call_count(), 0);
    }

    #[test]
    #[expect(
        clippy::too_many_lines,
        reason = "single materialize scenario asserts open-conflict hold + baseline retention observables"
    )]
    fn run_sync_cycle_materializes_open_conflict_and_holds_baseline() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let local_a = b"# L\n".to_vec();
        let remote_a = b"# R\n".to_vec();
        let base_a = b"# B\n".to_vec();
        let ok_body = b"# ok same\n".to_vec();
        let d_local = body_digest(&local_a);
        let d_remote = body_digest(&remote_a);
        let d_base = body_digest(&base_a);
        let d_ok = body_digest(&ok_body);

        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/a.md"), &d_base, "tok-base".to_owned());
        baseline.upsert(&path("memo/ok.md"), &d_ok, "tok-ok".to_owned());

        let local = FakeLocalPort {
            entries: vec![
                LocalPathEntry {
                    path: path("memo/a.md"),
                    digest: d_local,
                },
                LocalPathEntry {
                    path: path("memo/ok.md"),
                    digest: d_ok.clone(),
                },
            ],
        };
        let remote_snap = RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![
                RemotePathEntry {
                    path: path("memo/a.md"),
                    digest: d_remote.clone(),
                    revision_token: "tok-r".to_owned(),
                },
                RemotePathEntry {
                    path: path("memo/ok.md"),
                    digest: d_ok.clone(),
                    revision_token: "tok-ok".to_owned(),
                },
            ],
        )
        .expect("snap");
        // Fake remote: no publish work; verify would claim both present — baseline hold must still
        // block the open conflict path while allowing the same-byte ok path.
        let remote = FakeRemotePort::new(
            remote_snap,
            PublishReceipt {
                path_results: Vec::new(),
            },
            VerifiedRemoteState {
                results: vec![
                    VerifyStatus::Verified {
                        path: path("memo/a.md"),
                        digest: d_remote,
                        remote_token: "tok-r".to_owned(),
                    },
                    VerifyStatus::Verified {
                        path: path("memo/ok.md"),
                        digest: d_ok.clone(),
                        remote_token: "tok-ok".to_owned(),
                    },
                ],
            },
        );
        let bodies = ConflictBodySource::from_entries([(
            "memo/a.md",
            Some(local_a),
            Some(remote_a),
            Some(base_a),
        )]);
        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "cycle-c1").expect("session");
        let result = run_sync_cycle(
            &session,
            &local,
            &remote,
            baseline.clone(),
            Some(&paths),
            true,
            Some(&bodies),
        )
        .expect("cycle");
        assert_eq!(result.batch.open_conflict_count(), 1);
        // OpenConflict-only + same-byte verify path: no EnsurePresent/Absent → no publish.
        assert!(result.receipt.is_none());
        assert_eq!(remote.publish_call_count(), 0);
        let conflict = result.conflict_session.expect("materialized");
        assert_eq!(conflict.open_count(), 1);
        assert!(baseline_must_hold_for_path(&conflict, "memo/a.md"));
        // ok.md may establish/refresh; conflict path must retain prior baseline digest.
        let after = result.baseline.get("memo/a.md").expect("held");
        assert_eq!(after.digest, d_base.as_str());
        assert_eq!(after.remote_token, "tok-base");
        assert_eq!(
            result.baseline.get("memo/ok.md").map(|e| e.digest.as_str()),
            Some(d_ok.as_str())
        );
        let durable = read_conflict_session(&paths).expect("session on disk");
        assert_eq!(durable.session_id, "cycle-c1-conflict");
        assert!(
            durable
                .paths
                .first()
                .expect("p")
                .local_artifact_ref
                .is_some()
        );
        assert!(
            durable
                .paths
                .first()
                .expect("p")
                .remote_artifact_ref
                .is_some()
        );
    }

    #[test]
    #[expect(
        clippy::too_many_lines,
        reason = "single E2E scenario asserts plan→materialize→resolve→apply→baseline observables"
    )]
    #[expect(
        clippy::cognitive_complexity,
        reason = "single body-wire E2E asserts durable artifact → ObjectSource → published bytes"
    )]
    fn e2e_plan_materialize_resolve_keep_local_apply_baseline() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let local_bytes = b"# keep local\n".to_vec();
        let remote_bytes = b"# remote side\n".to_vec();
        let base_bytes = b"# base\n".to_vec();
        let d_local = body_digest(&local_bytes);
        let d_remote = body_digest(&remote_bytes);
        let d_base = body_digest(&base_bytes);

        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/a.md"), &d_base, "tok-base".to_owned());

        let local = FakeLocalPort {
            entries: vec![LocalPathEntry {
                path: path("memo/a.md"),
                digest: d_local.clone(),
            }],
        };
        let remote_snap = RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![RemotePathEntry {
                path: path("memo/a.md"),
                digest: d_remote,
                revision_token: "tok-r".to_owned(),
            }],
        )
        .expect("snap");
        // Open cycle remote: no EnsurePresent body binding (OpenConflict only).
        let open_remote = FakeRemotePort::new(
            remote_snap.clone(),
            PublishReceipt {
                path_results: vec![(
                    path("memo/a.md"),
                    PathPublishStatus::Applied {
                        new_token: "tok-new".to_owned(),
                    },
                )],
            },
            VerifiedRemoteState {
                results: vec![VerifyStatus::Verified {
                    path: path("memo/a.md"),
                    digest: d_local.clone(),
                    remote_token: "tok-new".to_owned(),
                }],
            },
        );
        let bodies = ConflictBodySource::from_entries([(
            "memo/a.md",
            Some(local_bytes.clone()),
            Some(remote_bytes),
            Some(base_bytes),
        )]);
        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "e2e-1").expect("session");
        // Open: materialize durable session; OpenConflict-only batch must not publish.
        let plan_result = run_sync_cycle(
            &session,
            &local,
            &open_remote,
            baseline.clone(),
            Some(&paths),
            true,
            Some(&bodies),
        )
        .expect("open cycle");
        assert_eq!(plan_result.batch.open_conflict_count(), 1);
        assert!(plan_result.receipt.is_none());
        assert_eq!(open_remote.publish_call_count(), 0);
        assert_eq!(
            plan_result.baseline.get("memo/a.md").expect("held").digest,
            d_base.as_str()
        );
        let durable = read_conflict_session(&paths).expect("session durable before resolve");
        assert_eq!(durable.open_count(), 1);
        assert!(
            durable
                .paths
                .first()
                .expect("p")
                .local_artifact_ref
                .is_some()
        );
        assert!(
            durable
                .paths
                .first()
                .expect("p")
                .remote_artifact_ref
                .is_some()
        );

        let resolved = resolve_sync_conflicts(
            &paths,
            1,
            &[ConflictResolution::KeepLocal {
                path: "memo/a.md".to_owned(),
            }],
        )
        .expect("resolve");
        assert_eq!(
            resolved.session.paths.first().expect("p").status,
            ConflictPathStatus::ResolvedKeepLocal
        );
        assert!(!baseline_must_hold_for_path(&resolved.session, "memo/a.md"));

        // Apply remote with body-wire: FakeRemote requires SHA-256(body) == EnsurePresent digest.
        // Bodies come from durable artifacts via collect_resolved_present_bodies inside apply.
        // Pre-load the same durable local body into FakeRemote ObjectSource so publish binds it.
        let apply_objects = collect_resolved_present_bodies(&paths, &resolved.session)
            .expect("collect bodies for ObjectSource");
        assert_eq!(
            apply_objects.objects.get("memo/a.md").map(Vec::as_slice),
            Some(local_bytes.as_slice())
        );
        let apply_remote = FakeRemotePort::with_objects(
            remote_snap,
            PublishReceipt {
                path_results: vec![(
                    path("memo/a.md"),
                    PathPublishStatus::Applied {
                        new_token: "tok-new".to_owned(),
                    },
                )],
            },
            VerifiedRemoteState {
                results: vec![VerifyStatus::Verified {
                    path: path("memo/a.md"),
                    digest: d_local.clone(),
                    remote_token: "tok-new".to_owned(),
                }],
            },
            apply_objects,
        );
        let applied =
            apply_resolved_conflicts_remote(&paths, 2, &apply_remote, plan_result.baseline)
                .expect("apply remote");
        assert!(applied.baseline_advanced);
        assert_eq!(
            applied.baseline.get("memo/a.md").expect("advanced").digest,
            d_local.as_str()
        );
        // Only the resolve→apply path publishes (open cycle was mutation-free).
        assert_eq!(apply_remote.publish_call_count(), 1);
        assert!(apply_remote.verify_call_count() >= 1);
        let published = apply_remote.published_bodies();
        assert_eq!(published.len(), 1);
        let first_pub = published.first().expect("published body");
        assert_eq!(first_pub.path, "memo/a.md");
        assert_eq!(first_pub.digest, d_local.as_str());
        assert_eq!(first_pub.body, local_bytes);
        assert_eq!(
            applied
                .publish_bodies
                .objects
                .get("memo/a.md")
                .map(Vec::as_slice),
            Some(local_bytes.as_slice())
        );
        let on_disk = read_baseline(&paths).expect("baseline written");
        assert_eq!(
            on_disk.get("memo/a.md").map(|e| e.digest.as_str()),
            Some(d_local.as_str())
        );
    }

    #[test]
    #[expect(
        clippy::too_many_lines,
        reason = "single E2E scenario asserts plan→materialize→Merged resolve→apply→baseline observables"
    )]
    fn e2e_plan_materialize_resolve_merged_apply_baseline() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let local_bytes = b"# L\n".to_vec();
        let remote_bytes = b"# R\n".to_vec();
        let base_bytes = b"# B\n".to_vec();
        let d_local = body_digest(&local_bytes);
        let d_remote = body_digest(&remote_bytes);
        let d_base = body_digest(&base_bytes);

        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/a.md"), &d_base, "tok-base".to_owned());
        let local = FakeLocalPort {
            entries: vec![LocalPathEntry {
                path: path("memo/a.md"),
                digest: d_local,
            }],
        };
        // Open cycle: OpenConflict only — no remote publish.
        let open_snap = RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![RemotePathEntry {
                path: path("memo/a.md"),
                digest: d_remote,
                revision_token: "tok-r".to_owned(),
            }],
        )
        .expect("snap");
        let open_remote = FakeRemotePort::new(
            open_snap.clone(),
            PublishReceipt {
                path_results: Vec::new(),
            },
            VerifiedRemoteState {
                results: Vec::new(),
            },
        );
        let bodies = ConflictBodySource::from_entries([(
            "memo/a.md",
            Some(local_bytes),
            Some(remote_bytes),
            Some(base_bytes),
        )]);
        let session =
            SyncSession::new(fence(), SessionKind::Incremental, "e2e-m").expect("session");
        let open = run_sync_cycle(
            &session,
            &local,
            &open_remote,
            baseline,
            Some(&paths),
            true,
            Some(&bodies),
        )
        .expect("open");
        assert!(open.receipt.is_none());
        assert_eq!(open_remote.publish_call_count(), 0);

        let merged_body = "# Merged\n\nresolved body\n".to_owned();
        let merged_bytes = merged_body.as_bytes().to_vec();
        let resolved = resolve_sync_conflicts(
            &paths,
            1,
            &[ConflictResolution::MergedBody {
                path: "memo/a.md".to_owned(),
                body: merged_body,
            }],
        )
        .expect("resolve");
        assert_eq!(
            resolved.session.paths.first().expect("p").status,
            ConflictPathStatus::ResolvedMerged
        );
        let merged_digest_hex = resolved
            .session
            .paths
            .first()
            .expect("p")
            .local_digest
            .clone()
            .expect("merged digest");
        let merged_digest = ContentDigest::parse(&merged_digest_hex).expect("parse");
        assert_eq!(merged_digest, body_digest(&merged_bytes));

        let apply_objects = collect_resolved_present_bodies(&paths, &resolved.session)
            .expect("collect merged body");
        assert_eq!(
            apply_objects.objects.get("memo/a.md").map(Vec::as_slice),
            Some(merged_bytes.as_slice())
        );
        let apply_remote = FakeRemotePort::with_objects(
            open_snap,
            PublishReceipt {
                path_results: vec![(
                    path("memo/a.md"),
                    PathPublishStatus::Applied {
                        new_token: "tok-merged".to_owned(),
                    },
                )],
            },
            VerifiedRemoteState {
                results: vec![VerifyStatus::Verified {
                    path: path("memo/a.md"),
                    digest: merged_digest.clone(),
                    remote_token: "tok-merged".to_owned(),
                }],
            },
            apply_objects,
        );
        let applied = apply_resolved_conflicts_remote(&paths, 2, &apply_remote, open.baseline)
            .expect("apply");
        assert!(applied.baseline_advanced);
        assert_eq!(apply_remote.publish_call_count(), 1);
        assert_eq!(
            applied.baseline.get("memo/a.md").expect("b").digest,
            merged_digest.as_str()
        );
        let published = apply_remote.published_bodies();
        assert_eq!(published.len(), 1);
        let first_pub = published.first().expect("published body");
        assert_eq!(first_pub.digest, merged_digest.as_str());
        assert_eq!(first_pub.body, merged_bytes);
        let on_disk = read_baseline(&paths).expect("baseline written");
        assert_eq!(
            on_disk.get("memo/a.md").map(|e| e.digest.as_str()),
            Some(merged_digest.as_str())
        );
    }

    #[test]
    fn apply_resolved_keep_local_fails_closed_when_artifact_missing() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        // Hollow KeepLocal: digest present, no artifact ref → body wire fails closed.
        let record = conflict_path_from_open(
            &path("memo/a.md"),
            Some(&dig(1)),
            Some(&dig(2)),
            Some(&dig(0)),
            Some("tok-r"),
        )
        .expect("record");
        let session = ConflictSession::open(fence(), "hollow-body", vec![record]).expect("open");
        write_conflict_session(&paths, &session).expect("write");
        resolve_sync_conflicts(
            &paths,
            1,
            &[ConflictResolution::KeepLocal {
                path: "memo/a.md".to_owned(),
            }],
        )
        .expect("resolve status");
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
            PublishReceipt {
                path_results: Vec::new(),
            },
            VerifiedRemoteState {
                results: Vec::new(),
            },
        )
        .requiring_body();
        let err = apply_resolved_conflicts_remote(&paths, 2, &remote, BaselineHead::empty())
            .expect_err("missing artifact");
        assert_eq!(err.code(), "conflict_apply_local_artifact_missing");
        assert_eq!(remote.publish_call_count(), 0);
    }

    #[test]
    fn keep_remote_resolve_does_not_pretend_local_or_baseline_apply() {
        // Honesty residual: KeepRemote status-only is not full apply. Session advances; remote
        // publish and baseline stay unchanged until store expected-revision local apply lands.
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let record = conflict_path_from_open(
            &path("memo/a.md"),
            Some(&dig(1)),
            Some(&dig(2)),
            Some(&dig(0)),
            Some("tok-r"),
        )
        .expect("record");
        let session = ConflictSession::open(fence(), "keep-r", vec![record]).expect("open");
        write_conflict_session(&paths, &session).expect("write");
        let resolved = resolve_sync_conflicts(
            &paths,
            1,
            &[ConflictResolution::KeepRemote {
                path: "memo/a.md".to_owned(),
            }],
        )
        .expect("resolve");
        assert_eq!(
            resolved.session.paths.first().expect("p").status,
            ConflictPathStatus::ResolvedKeepRemote
        );
        assert!(!baseline_must_hold_for_path(&resolved.session, "memo/a.md"));

        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/a.md"), &dig(0), "tok-base".to_owned());
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
            PublishReceipt {
                path_results: Vec::new(),
            },
            VerifiedRemoteState {
                results: Vec::new(),
            },
        );
        let applied =
            apply_resolved_conflicts_remote(&paths, 2, &remote, baseline.clone()).expect("apply");
        assert!(!applied.baseline_advanced);
        assert_eq!(remote.publish_call_count(), 0);
        assert_eq!(
            applied
                .baseline
                .get("memo/a.md")
                .expect("held prior")
                .digest,
            dig(0).as_str(),
            "KeepRemote must not silently rewrite baseline without local apply + verify"
        );
        // Prior baseline entries unchanged (no durable baseline write on KeepRemote-only).
        assert_eq!(applied.baseline.entries, baseline.entries);
    }

    #[test]
    fn apply_resolved_conflicts_remote_rejects_stale_revision() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        open_markdown_conflict(&paths);
        resolve_sync_conflicts(
            &paths,
            1,
            &[ConflictResolution::KeepLocal {
                path: "memo/a.md".to_owned(),
            }],
        )
        .expect("resolve once → rev 2");
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
            PublishReceipt {
                path_results: Vec::new(),
            },
            VerifiedRemoteState {
                results: Vec::new(),
            },
        );
        let err = apply_resolved_conflicts_remote(&paths, 1, &remote, BaselineHead::empty())
            .expect_err("stale");
        assert_eq!(err.code(), "conflict_revision_stale");
        assert_eq!(remote.publish_call_count(), 0);
    }

    #[test]
    fn run_sync_cycle_reissues_pending_delete_from_tombstone_on_revive() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let f = fence();
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(f.clone());
        baseline.upsert(&path("memo/gone.md"), &dig(3), "tok-gone".to_owned());
        // Crash after tombstone, before remote delete.
        let _intent = record_user_delete_tombstone_first(&UserDeleteRequest {
            paths: &paths,
            fence: &f,
            baseline: &baseline,
            session_kind: SessionKind::Incremental,
            remote_completeness: SnapshotCompleteness::Complete,
            path: &path("memo/gone.md"),
            local_has_path: false,
            observed_remote_token: Some("tok-gone"),
            content_digest: &dig(3),
        })
        .expect("tombstone first");
        assert!(
            read_tombstones(&paths)
                .expect("t")
                .contains_path("memo/gone.md")
        );

        let local = FakeLocalPort {
            entries: Vec::new(),
        };
        let remote_snap = RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![RemotePathEntry {
                path: path("memo/gone.md"),
                digest: dig(3),
                revision_token: "tok-gone".to_owned(),
            }],
        )
        .expect("snap");
        let remote = FakeRemotePort::new(
            remote_snap,
            PublishReceipt {
                path_results: vec![(
                    path("memo/gone.md"),
                    PathPublishStatus::Applied {
                        new_token: "deleted".to_owned(),
                    },
                )],
            },
            VerifiedRemoteState {
                results: vec![VerifyStatus::AbsentVerified {
                    path: path("memo/gone.md"),
                }],
            },
        );
        let session = SyncSession::new(f, SessionKind::Incremental, "revive-del").expect("session");
        let result = run_sync_cycle(
            &session,
            &local,
            &remote,
            baseline,
            Some(&paths),
            true,
            None,
        )
        .expect("cycle");
        assert_eq!(result.batch.ensure_absent_count(), 1);
        assert_eq!(remote.publish_call_count(), 1);
        assert!(result.baseline_advanced);
        assert!(result.baseline.get("memo/gone.md").is_none());
    }

    #[test]
    fn skip_for_now_blocks_baseline_via_apply_resolved_path() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let record = conflict_path_from_open(
            &path("memo/a.md"),
            Some(&dig(1)),
            Some(&dig(2)),
            Some(&dig(0)),
            Some("tok-r"),
        )
        .expect("record");
        let session = ConflictSession::open(fence(), "skip-hold", vec![record]).expect("open");
        write_conflict_session(&paths, &session).expect("write");
        resolve_sync_conflicts(
            &paths,
            1,
            &[ConflictResolution::SkipForNow {
                path: "memo/a.md".to_owned(),
            }],
        )
        .expect("skip");
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/a.md"), &dig(0), "tok-base".to_owned());
        let remote = FakeRemotePort::new(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
            PublishReceipt {
                path_results: Vec::new(),
            },
            VerifiedRemoteState {
                results: vec![VerifyStatus::Verified {
                    path: path("memo/a.md"),
                    digest: dig(9),
                    remote_token: "evil".to_owned(),
                }],
            },
        );
        let result = apply_resolved_conflicts_remote(&paths, 2, &remote, baseline).expect("apply");
        // SkipForNow must not emit remote apply intents; baseline stays dig(0).
        assert!(!result.baseline_advanced);
        assert_eq!(
            result.baseline.get("memo/a.md").expect("held").digest,
            dig(0).as_str()
        );
        assert_eq!(remote.publish_call_count(), 0);
    }

    #[test]
    fn user_delete_remaining_reject_codes() {
        let f = fence();
        let path_a = path("memo/a.md");
        // baseline incomplete (no fence)
        let baseline_empty = BaselineHead::empty();
        let gate_bl = user_delete_gate_for_path(&UserDeleteContext {
            session_kind: SessionKind::Incremental,
            remote_completeness: SnapshotCompleteness::Complete,
            fence: &f,
            baseline: &baseline_empty,
            path: &path_a,
            local_has_path: false,
            observed_remote_token: Some("tok-a"),
        })
        .expect("gate");
        assert_eq!(
            gate_bl.reject_code(),
            Some("user_delete_baseline_incomplete")
        );

        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(f.clone());
        // path not in baseline
        let gate_path = user_delete_gate_for_path(&UserDeleteContext {
            session_kind: SessionKind::Incremental,
            remote_completeness: SnapshotCompleteness::Complete,
            fence: &f,
            baseline: &baseline,
            path: &path_a,
            local_has_path: false,
            observed_remote_token: Some("tok-a"),
        })
        .expect("gate");
        assert_eq!(
            gate_path.reject_code(),
            Some("user_delete_path_not_in_baseline")
        );

        baseline.upsert(&path_a, &dig(1), "tok-a".to_owned());
        let gate_tok = user_delete_gate_for_path(&UserDeleteContext {
            session_kind: SessionKind::Incremental,
            remote_completeness: SnapshotCompleteness::Complete,
            fence: &f,
            baseline: &baseline,
            path: &path_a,
            local_has_path: false,
            observed_remote_token: Some("tok-other"),
        })
        .expect("gate");
        assert_eq!(gate_tok.reject_code(), Some("user_delete_token_mismatch"));

        let gate_local = user_delete_gate_for_path(&UserDeleteContext {
            session_kind: SessionKind::Incremental,
            remote_completeness: SnapshotCompleteness::Complete,
            fence: &f,
            baseline: &baseline,
            path: &path_a,
            local_has_path: true,
            observed_remote_token: Some("tok-a"),
        })
        .expect("gate");
        assert_eq!(
            gate_local.reject_code(),
            Some("user_delete_local_still_present")
        );

        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        for (local_has, token, code) in [
            (false, Some("tok-other"), "user_delete_token_mismatch"),
            (true, Some("tok-a"), "user_delete_local_still_present"),
        ] {
            let err = record_user_delete_tombstone_first(&UserDeleteRequest {
                paths: &paths,
                fence: &f,
                baseline: &baseline,
                session_kind: SessionKind::Incremental,
                remote_completeness: SnapshotCompleteness::Complete,
                path: &path_a,
                local_has_path: local_has,
                observed_remote_token: token,
                content_digest: &dig(1),
            })
            .expect_err(code);
            assert_eq!(err.code(), code);
            assert!(
                !paths.tombstones.exists()
                    || !read_tombstones(&paths)
                        .expect("t")
                        .contains_path("memo/a.md")
            );
        }
    }

    #[test]
    fn invalid_merged_body_rejects_without_advancing_revision() {
        // Over-budget body (chars) — resource-limit from workspace owner.
        let huge = "x".repeat(100_001);
        let err = validate_merged_markdown_body(&huge).expect_err("budget");
        assert!(
            err.code() == "editable_memo_too_large"
                || err.category() == ErrorCategory::ResourceLimit,
            "code={} category={:?}",
            err.code(),
            err.category()
        );

        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        open_markdown_conflict(&paths);
        let err = resolve_sync_conflicts(
            &paths,
            1,
            &[ConflictResolution::MergedBody {
                path: "memo/a.md".to_owned(),
                body: huge,
            }],
        )
        .expect_err("merged reject");
        assert_eq!(error_category(&err), ErrorCategory::ResourceLimit);
        let loaded = read_conflict_session(&paths).expect("unchanged");
        assert_eq!(loaded.conflict_revision, 1);
        assert_eq!(
            loaded.paths.first().expect("p").status,
            ConflictPathStatus::Open
        );
    }

    #[test]
    fn corrupt_conflict_session_is_corrupt_state_not_clean_slate() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        paths.ensure_layout().expect("layout");
        std::fs::write(&paths.conflicts, b"BAD!").expect("seed");
        let err = read_conflict_session(&paths).expect_err("corrupt");
        assert_eq!(error_category(&err), ErrorCategory::Corruption);
        assert_eq!(std::fs::read(&paths.conflicts).expect("retain"), b"BAD!");
    }

    /// Host path → memo id mapping for store apply (`memos/{id}.md` only).
    fn memo_id_from_sync_path(path: &str) -> Option<&str> {
        path.strip_prefix("memos/")
            .and_then(|rest| rest.strip_suffix(".md"))
            .filter(|id| !id.is_empty() && !id.contains('/') && !id.contains('\\'))
    }

    fn seed_memo(store: &mut Store, op: &str, memo_id: &str, body: &str) {
        store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse(op).expect("op"),
                    kind: MemoCommandKind::Create,
                    memo_id: memo_id.into(),
                    expected_revision: 0,
                    expected_fingerprint: None,
                    content: Some(body.into()),
                    tags: vec![],
                    pin: None,
                    pending_promotes: vec![],
                },
                None,
            )
            .expect("seed memo");
    }

    fn open_conflict_with_side_artifacts(
        paths: &SyncPaths,
        session_id: &str,
        path_s: &str,
        local_body: &[u8],
        remote_body: &[u8],
        base_body: &[u8],
        token: &str,
    ) -> ConflictSession {
        let d_local = body_digest(local_body);
        let d_remote = body_digest(remote_body);
        let d_base = body_digest(base_body);
        let mut record = conflict_path_from_open(
            &path(path_s),
            Some(&d_local),
            Some(&d_remote),
            Some(&d_base),
            Some(token),
        )
        .expect("record");
        let session =
            ConflictSession::open(fence(), session_id, vec![record.clone()]).expect("open");
        write_conflict_session(paths, &session).expect("write");
        record.remote_artifact_ref = Some(
            write_conflict_artifact(paths, session_id, "remote", path_s, remote_body)
                .expect("remote artifact"),
        );
        record.local_artifact_ref = Some(
            write_conflict_artifact(paths, session_id, "local", path_s, local_body)
                .expect("local artifact"),
        );
        let session = ConflictSession::open(fence(), session_id, vec![record]).expect("reopen");
        write_conflict_session(paths, &session).expect("write artifacts");
        session
    }

    fn store_upsert_memo_from_pull(store: &mut Store, op: &str, memo_id: &str, content: String) {
        let expected_revision = store
            .get_memo(memo_id)
            .expect("get")
            .expect("present")
            .summary
            .content_revision;
        store
            .apply_local_sync_batch(&LocalSyncMutationBatch {
                mutations: vec![LocalSyncMutation::UpsertMemo {
                    operation_id: op.into(),
                    memo_id: memo_id.into(),
                    expected_revision,
                    expected_fingerprint: None,
                    content,
                    tags: vec![],
                }],
            })
            .expect("store local apply");
    }

    #[test]
    fn keep_remote_local_store_apply_body_wire_and_baseline() {
        // RED residual closed: KeepRemote must load remote_artifact_ref, apply via store
        // LocalSyncMutationBatch, then advance baseline with the remote digest — status alone is
        // not user-byte state.
        let temporary = tempdir().expect("temp");
        let root = temporary.path();
        let paths = SyncPaths::for_workspace(root);
        let mut store = Store::open(root).expect("store open");

        let local_body = "# local diverged\n";
        let remote_body = "# remote wins\n";
        let base_body = "# base\n";
        let d_remote = body_digest(remote_body.as_bytes());
        let d_base = body_digest(base_body.as_bytes());

        seed_memo(&mut store, "op-seed-local", "keep-r", local_body);
        let _session = open_conflict_with_side_artifacts(
            &paths,
            "keep-r-local",
            "memos/keep-r.md",
            local_body.as_bytes(),
            remote_body.as_bytes(),
            base_body.as_bytes(),
            "tok-r",
        );

        let resolved = resolve_sync_conflicts(
            &paths,
            1,
            &[ConflictResolution::KeepRemote {
                path: "memos/keep-r.md".to_owned(),
            }],
        )
        .expect("resolve KeepRemote");
        assert_eq!(
            resolved.session.paths.first().expect("p").status,
            ConflictPathStatus::ResolvedKeepRemote
        );

        let pulls =
            collect_resolved_local_pull_mutations(&paths, &resolved.session).expect("pull bodies");
        assert_eq!(pulls.len(), 1);
        let pull = pulls.first().expect("one");
        assert_eq!(pull.path, "memos/keep-r.md");
        assert_eq!(pull.body.as_slice(), remote_body.as_bytes());
        assert_eq!(pull.content_digest, d_remote.as_str());

        let memo_id = memo_id_from_sync_path(&pull.path).expect("memo id");
        let content = String::from_utf8(pull.body.clone()).expect("utf8 memo");
        store_upsert_memo_from_pull(&mut store, "op-keep-remote-pull", memo_id, content);
        let after = store.get_memo(memo_id).expect("get").expect("present");
        assert_eq!(after.body, remote_body);
        assert_eq!(fingerprint_content(&after.body), d_remote.as_str());

        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memos/keep-r.md"), &d_base, "tok-base".to_owned());
        let advanced = advance_baseline_after_local_pull(
            &paths,
            resolved.session.conflict_revision,
            baseline,
            &pulls,
        )
        .expect("baseline after local");
        assert_eq!(
            advanced.get("memos/keep-r.md").expect("entry").digest,
            d_remote.as_str()
        );
        assert_eq!(
            advanced.get("memos/keep-r.md").expect("entry").remote_token,
            "tok-r"
        );
    }

    #[test]
    fn keep_remote_local_pull_fails_closed_when_remote_artifact_missing() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let record = conflict_path_from_open(
            &path("memos/hollow.md"),
            Some(&dig(1)),
            Some(&dig(2)),
            Some(&dig(0)),
            Some("tok-r"),
        )
        .expect("record");
        // No remote_artifact_ref — status-only KeepRemote must not invent bodies.
        let session = ConflictSession::open(fence(), "hollow-kr", vec![record]).expect("open");
        write_conflict_session(&paths, &session).expect("write");
        resolve_sync_conflicts(
            &paths,
            1,
            &[ConflictResolution::KeepRemote {
                path: "memos/hollow.md".to_owned(),
            }],
        )
        .expect("resolve status");
        let durable = read_conflict_session(&paths).expect("read");
        let err = collect_resolved_local_pull_mutations(&paths, &durable)
            .expect_err("missing remote artifact");
        assert_eq!(err.code(), "conflict_apply_remote_artifact_missing");
    }

    #[test]
    fn merged_local_store_apply_body_wire_and_baseline() {
        let temporary = tempdir().expect("temp");
        let root = temporary.path();
        let paths = SyncPaths::for_workspace(root);
        let mut store = Store::open(root).expect("store open");

        let local_body = "# local side\n";
        let remote_body = "# remote side\n";
        let base_body = "# base\n";
        let merged_body = "# merged body\n\nAccepted both sides.\n";
        let d_merged = body_digest(merged_body.as_bytes());

        seed_memo(&mut store, "op-seed-merged", "merged-pull", local_body);
        let _session = open_conflict_with_side_artifacts(
            &paths,
            "merged-local",
            "memos/merged-pull.md",
            local_body.as_bytes(),
            remote_body.as_bytes(),
            base_body.as_bytes(),
            "tok-m",
        );

        let resolved = resolve_sync_conflicts(
            &paths,
            1,
            &[ConflictResolution::MergedBody {
                path: "memos/merged-pull.md".to_owned(),
                body: merged_body.to_owned(),
            }],
        )
        .expect("resolve merged");
        assert_eq!(
            resolved.session.paths.first().expect("p").status,
            ConflictPathStatus::ResolvedMerged
        );
        assert_eq!(
            resolved
                .session
                .paths
                .first()
                .expect("p")
                .local_digest
                .as_deref(),
            Some(d_merged.as_str())
        );

        let pulls =
            collect_resolved_local_pull_mutations(&paths, &resolved.session).expect("pull bodies");
        assert_eq!(pulls.len(), 1);
        assert_eq!(pulls.first().expect("p").content_digest, d_merged.as_str());
        assert_eq!(
            pulls.first().expect("p").body.as_slice(),
            merged_body.as_bytes()
        );

        store_upsert_memo_from_pull(
            &mut store,
            "op-merged-pull",
            "merged-pull",
            merged_body.to_owned(),
        );
        assert_eq!(
            store.get_memo("merged-pull").expect("get").expect("p").body,
            merged_body
        );

        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        let advanced = advance_baseline_after_local_pull(
            &paths,
            resolved.session.conflict_revision,
            baseline,
            &pulls,
        )
        .expect("baseline");
        assert_eq!(
            advanced.get("memos/merged-pull.md").expect("e").digest,
            d_merged.as_str()
        );
    }

    // --- P5-08 residual: narrow crash-at-transition host matrix ---

    #[test]
    fn crash_after_artifacts_before_session_head_leaves_no_open_session() {
        // Transition: artifacts durable, session head never written (crash before write_conflict_session).
        // Recoverability: no open session → materialize must re-run; no hollow OpenConflict.
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        paths.ensure_layout().expect("layout");
        let local_bytes = b"# local after crash\n";
        let remote_bytes = b"# remote after crash\n";
        let local_d = body_digest(local_bytes);
        let remote_d = body_digest(remote_bytes);
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/a.md"), &dig(0), "tok-base".to_owned());
        let local = LocalSnapshot {
            entries: vec![LocalPathEntry {
                path: path("memo/a.md"),
                digest: local_d,
            }],
            workspace_generation: None,
        };
        let remote = RemoteSnapshot::new(
            SnapshotCompleteness::Complete,
            vec![RemotePathEntry {
                path: path("memo/a.md"),
                digest: remote_d,
                revision_token: "tok-r".to_owned(),
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
        assert_eq!(batch.open_conflict_count(), 1);

        // Simulate crash mid-materialize: write artifacts only; omit session head.
        let art_local =
            write_conflict_artifact(&paths, "crash-mat-1", "local", "memo/a.md", local_bytes)
                .expect("art local");
        let art_remote =
            write_conflict_artifact(&paths, "crash-mat-1", "remote", "memo/a.md", remote_bytes)
                .expect("art remote");
        assert!(paths.conflict_artifacts.join(&art_local).exists());
        assert!(paths.conflict_artifacts.join(&art_remote).exists());
        assert!(!paths.conflicts.exists());

        // No durable open session (missing head is fail-closed; not hollow-open).
        let missing = read_conflict_session(&paths).expect_err("no session");
        assert_eq!(error_category(&missing), ErrorCategory::Storage);
        assert_eq!(missing.code(), "conflict_session_missing");
        assert!(!paths.conflicts.exists());

        // Re-materialize after crash: full atomic path writes session head + artifacts.
        let bodies = ConflictBodySource::from_entries([(
            "memo/a.md",
            Some(local_bytes.to_vec()),
            Some(remote_bytes.to_vec()),
            None,
        )]);
        let session = materialize_conflicts_from_plan(
            &paths,
            &fence(),
            "crash-mat-1-revive",
            &batch,
            &remote,
            &bodies,
        )
        .expect("rematerialize")
        .expect("session");
        assert_eq!(session.conflict_revision, 1);
        assert_eq!(session.open_count(), 1);
        let loaded = read_conflict_session(&paths).expect("durable after revive");
        assert_eq!(loaded.session_id, "crash-mat-1-revive");
    }

    #[test]
    fn crash_after_resolve_write_revives_with_advanced_revision() {
        // Transition: resolve mutates in-memory then write_conflict_session; crash after durable write
        // is just "process death with advanced revision on disk" — re-read must see new revision and
        // re-resolve with stale expected revision must fail closed.
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let _seeded: ConflictSession = open_markdown_conflict(&paths);
        let resolved = resolve_sync_conflicts(
            &paths,
            1,
            &[ConflictResolution::KeepLocal {
                path: "memo/a.md".to_owned(),
            }],
        )
        .expect("resolve");
        assert_eq!(resolved.session.conflict_revision, 2);
        assert_eq!(
            resolved.session.paths.first().expect("p").status,
            ConflictPathStatus::ResolvedKeepLocal
        );

        // Process death: drop handles, re-read from disk only.
        let revived = read_conflict_session(&paths).expect("revive");
        assert_eq!(revived.conflict_revision, 2);
        assert_eq!(
            revived.paths.first().expect("p").status,
            ConflictPathStatus::ResolvedKeepLocal
        );

        // Stale expected revision after crash-revive fails closed (no double-apply).
        let stale = resolve_sync_conflicts(
            &paths,
            1,
            &[ConflictResolution::KeepRemote {
                path: "memo/a.md".to_owned(),
            }],
        )
        .expect_err("stale");
        assert_eq!(error_category(&stale), ErrorCategory::Conflict);
        assert_eq!(stale.code(), "conflict_revision_stale");
        let still = read_conflict_session(&paths).expect("unchanged");
        assert_eq!(still.conflict_revision, 2);
        assert_eq!(
            still.paths.first().expect("p").status,
            ConflictPathStatus::ResolvedKeepLocal
        );
    }

    #[test]
    fn crash_after_tombstone_before_baseline_advance_reissues_ensure_absent_on_cycle() {
        // Already covered in part by run_sync_cycle_reissues_pending_delete_from_tombstone_on_revive;
        // this matrix entry asserts the narrower transition: tombstone durable + no session/baseline
        // mutation, recover_pending_delete_intent alone re-issues EnsureAbsent (fail-closed partial).
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let f = fence();
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(f.clone());
        let path_a = path("memo/a.md");
        let digest = dig(1);
        baseline.upsert(&path_a, &digest, "tok-a".to_owned());
        let _intent = record_user_delete_tombstone_first(&UserDeleteRequest {
            paths: &paths,
            fence: &f,
            baseline: &baseline,
            session_kind: SessionKind::Incremental,
            remote_completeness: SnapshotCompleteness::Complete,
            path: &path_a,
            local_has_path: false,
            observed_remote_token: Some("tok-a"),
            content_digest: &digest,
        })
        .expect("tombstone first");
        // Crash: only tombstones on disk; no remote apply; no baseline advance.
        let tombstones = read_tombstones(&paths).expect("tombstones");
        assert!(tombstones.contains_path("memo/a.md"));
        // Crash surface: tombstone durable; no session file advanced by this delete path.
        assert!(!paths.session.exists());

        let recovered = recover_pending_delete_intent(&RecoverDeleteRequest {
            fence: &f,
            baseline: &baseline,
            tombstones: &tombstones,
            session_kind: SessionKind::Incremental,
            remote_completeness: SnapshotCompleteness::Complete,
            path: &path_a,
            local_has_path: false,
            remote_token: Some("tok-a"),
            remote_digest: Some(&digest),
        })
        .expect("recover")
        .expect("intent");
        assert!(matches!(
            recovered,
            ProviderNeutralIntent::EnsureAbsent { .. }
        ));
        // Partial listing still fail-closed after crash.
        let blocked = recover_pending_delete_intent(&RecoverDeleteRequest {
            fence: &f,
            baseline: &baseline,
            tombstones: &tombstones,
            session_kind: SessionKind::Incremental,
            remote_completeness: SnapshotCompleteness::Incomplete,
            path: &path_a,
            local_has_path: false,
            remote_token: Some("tok-a"),
            remote_digest: Some(&digest),
        })
        .expect("partial");
        assert!(blocked.is_none());
    }

    #[test]
    fn crash_corrupt_mid_transition_session_is_not_clean_slate() {
        // Transition: partial/corrupt conflict head after interrupted write must surface Corruption
        // and retain bytes (same invariant as corrupt_conflict_session_is_corrupt_state).
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        paths.ensure_layout().expect("layout");
        // Truncated / garbage head as if rename/fsync interrupted mid-frame.
        std::fs::write(&paths.conflicts, b"LOMO\x01\x00").expect("seed partial");
        let err = read_conflict_session(&paths).expect_err("corrupt");
        assert_eq!(error_category(&err), ErrorCategory::Corruption);
        assert_eq!(
            std::fs::read(&paths.conflicts).expect("retain"),
            b"LOMO\x01\x00"
        );
    }

    // --- Wave-9 P5-08 residual: additional host crash-at-transition (not corrupt-head twins) ---

    #[test]
    fn crash_after_baseline_temp_before_rename_retains_prior_head() {
        // Transition: write_sync_record_atomic writes temp then renames. Crash after temp is
        // durable but before rename must leave the prior baseline head authoritative (not clean
        // slate; not promote partial temp as head).
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        paths.ensure_layout().expect("layout");

        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/a.md"), &dig(1), "tok-a".to_owned());
        write_baseline(&paths, &baseline).expect("seed baseline");
        let prior = std::fs::read(&paths.baseline).expect("prior bytes");
        assert!(!prior.is_empty());

        // Simulate interrupted commit: temp sibling exists with garbage; head untouched.
        let temp_path = paths.baseline.with_extension("tmp");
        std::fs::write(&temp_path, b"LSYN\x00partial-baseline").expect("seed temp");
        assert!(temp_path.exists());

        let loaded = read_baseline(&paths).expect("prior head still loads");
        assert_eq!(
            loaded.get("memo/a.md").map(|e| e.digest.as_str()),
            Some(dig(1).as_str())
        );
        assert_eq!(std::fs::read(&paths.baseline).expect("retain head"), prior);
        // Temp is leftover junk — not promoted to head.
        assert_ne!(
            std::fs::read(&temp_path).expect("temp"),
            prior,
            "temp must not equal promoted head"
        );
    }

    #[test]
    fn crash_after_session_head_before_conflict_open_is_recoverable_idle() {
        // Transition: durable session written, conflict materialize never started (crash before
        // open). Re-entry via run_sync_cycle plan-only with empty ports is idle (no open conflict
        // paths); session identity survives.
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let session = SyncSession::new(fence(), SessionKind::Incremental, "crash-sess-only")
            .expect("session");
        write_session(&paths, &session).expect("session head");
        assert!(paths.session.exists());
        assert!(!paths.conflicts.exists());

        let local = FakeLocalPort {
            entries: Vec::new(),
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
        let result = run_sync_cycle(
            &session,
            &local,
            &remote,
            BaselineHead::empty(),
            Some(&paths),
            false,
            None,
        )
        .expect("revive idle");
        assert_eq!(result.batch.open_conflict_count(), 0);
        assert!(!result.baseline_advanced);
        let reloaded = read_session(&paths).expect("session survives");
        assert_eq!(reloaded.session_id, "crash-sess-only");
        assert_eq!(reloaded.session_revision, 1);
        assert!(!paths.conflicts.exists());
    }

    #[test]
    fn crash_between_baseline_and_session_revision_does_not_double_advance_on_reapply() {
        // Transition: KeepLocal remote apply advanced baseline; crash before a second mutation of
        // session bookkeeping. Re-running apply_resolved with the **old** expected revision must
        // fail closed (stale fence) — no double baseline advance / no clean slate.
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let local_bytes = b"# local reapply\n";
        let remote_bytes = b"# remote reapply\n";
        let base_bytes = b"# base reapply\n";
        let d_local = body_digest(local_bytes);
        let d_remote = body_digest(remote_bytes);
        let d_base = body_digest(base_bytes);

        let open = open_conflict_with_side_artifacts(
            &paths,
            "crash-reapply",
            "memo/a.md",
            local_bytes,
            remote_bytes,
            base_bytes,
            "tok-r",
        );
        assert_eq!(open.conflict_revision, 1);

        let resolved = resolve_sync_conflicts(
            &paths,
            1,
            &[ConflictResolution::KeepLocal {
                path: "memo/a.md".to_owned(),
            }],
        )
        .expect("resolve");
        assert_eq!(resolved.session.conflict_revision, 2);

        let apply_objects =
            collect_resolved_present_bodies(&paths, &resolved.session).expect("collect bodies");
        let remote = FakeRemotePort::with_objects(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
            PublishReceipt {
                path_results: vec![(
                    path("memo/a.md"),
                    PathPublishStatus::Applied {
                        new_token: "n-re".to_owned(),
                    },
                )],
            },
            VerifiedRemoteState {
                results: vec![VerifyStatus::Verified {
                    path: path("memo/a.md"),
                    digest: d_local.clone(),
                    remote_token: "n-re".to_owned(),
                }],
            },
            apply_objects,
        );
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/a.md"), &d_base, "tok-base".to_owned());
        let applied = apply_resolved_conflicts_remote(&paths, 2, &remote, baseline).expect("apply");
        assert!(applied.baseline_advanced);
        assert_eq!(
            applied.baseline.get("memo/a.md").map(|e| e.digest.as_str()),
            Some(d_local.as_str())
        );
        let after_first = read_baseline(&paths).expect("baseline after first");
        let first_digest = after_first.get("memo/a.md").expect("path").digest.clone();

        // Crash revive: stale expected revision (1) must reject; baseline digest unchanged.
        let err =
            apply_resolved_conflicts_remote(&paths, 1, &remote, after_first).expect_err("stale");
        assert_eq!(err.code(), "conflict_revision_stale");
        let after_stale = read_baseline(&paths).expect("baseline retained");
        assert_eq!(
            after_stale.get("memo/a.md").map(|e| e.digest.as_str()),
            Some(first_digest.as_str())
        );
        // Remote digest must not have been silently written as winner.
        assert_ne!(first_digest, d_remote.as_str());
    }

    /// Transition: `KeepLocal` remote Applied + verified, crash before `write_baseline`.
    /// Recoverability: prior baseline remains; re-apply with current expected revision is
    /// idempotent and advances baseline once (no double digest flip / no clean slate).
    #[test]
    fn crash_after_publish_before_baseline_write_advances_on_reapply() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let local_bytes = b"# local after publish crash\n";
        let remote_bytes = b"# remote after publish crash\n";
        let base_bytes = b"# base after publish crash\n";
        let d_local = body_digest(local_bytes);
        let d_remote = body_digest(remote_bytes);
        let d_base = body_digest(base_bytes);

        let _open = open_conflict_with_side_artifacts(
            &paths,
            "crash-pub-base",
            "memo/a.md",
            local_bytes,
            remote_bytes,
            base_bytes,
            "tok-r",
        );
        let resolved = resolve_sync_conflicts(
            &paths,
            1,
            &[ConflictResolution::KeepLocal {
                path: "memo/a.md".to_owned(),
            }],
        )
        .expect("resolve");
        assert_eq!(resolved.session.conflict_revision, 2);

        // Seed prior baseline (pre-apply).
        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memo/a.md"), &d_base, "tok-base".to_owned());
        write_baseline(&paths, &baseline).expect("seed baseline");
        let prior = read_baseline(&paths).expect("prior");
        assert_eq!(
            prior.get("memo/a.md").map(|e| e.digest.as_str()),
            Some(d_base.as_str())
        );

        // Simulate: remote Applied happened (ObjectSource would bind) but baseline write never ran.
        // Crash surface is "session resolved + remote may already have body + baseline still prior".
        let apply_objects =
            collect_resolved_present_bodies(&paths, &resolved.session).expect("bodies");
        assert_eq!(
            apply_objects
                .load_bytes(&path("memo/a.md"), &d_local)
                .expect("load"),
            local_bytes
        );
        // Baseline still prior after "crash".
        let after_crash = read_baseline(&paths).expect("after crash");
        assert_eq!(
            after_crash.get("memo/a.md").map(|e| e.digest.as_str()),
            Some(d_base.as_str())
        );
        assert_ne!(d_base.as_str(), d_local.as_str());
        assert_ne!(d_base.as_str(), d_remote.as_str());

        // Revive: re-apply with current revision advances baseline to local winner once.
        let remote = FakeRemotePort::with_objects(
            RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new()).expect("snap"),
            PublishReceipt {
                path_results: vec![(
                    path("memo/a.md"),
                    PathPublishStatus::Applied {
                        new_token: "n-pub".to_owned(),
                    },
                )],
            },
            VerifiedRemoteState {
                results: vec![VerifyStatus::Verified {
                    path: path("memo/a.md"),
                    digest: d_local.clone(),
                    remote_token: "n-pub".to_owned(),
                }],
            },
            apply_objects,
        );
        let applied =
            apply_resolved_conflicts_remote(&paths, 2, &remote, after_crash).expect("reapply");
        assert!(applied.baseline_advanced);
        let advanced = read_baseline(&paths).expect("advanced");
        assert_eq!(
            advanced.get("memo/a.md").map(|e| e.digest.as_str()),
            Some(d_local.as_str())
        );

        // Second re-apply with same revision still succeeds (session not advanced by apply) but
        // must not flip to remote digest.
        let applied2 =
            apply_resolved_conflicts_remote(&paths, 2, &remote, advanced).expect("idempotent");
        assert!(applied2.baseline_advanced);
        let again = read_baseline(&paths).expect("again");
        assert_eq!(
            again.get("memo/a.md").map(|e| e.digest.as_str()),
            Some(d_local.as_str())
        );
    }

    /// Transition: conflict session atomic write left `.tmp` sibling; head never renamed.
    /// Recoverability: prior conflict head remains authoritative; temp is not promoted.
    #[test]
    fn crash_after_conflict_session_temp_before_rename_retains_prior_session() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        paths.ensure_layout().expect("layout");

        let session = open_markdown_conflict(&paths);
        assert_eq!(session.conflict_revision, 1);
        let prior = std::fs::read(&paths.conflicts).expect("prior session bytes");
        assert!(!prior.is_empty());

        let temp_path = paths.conflicts.with_extension("tmp");
        std::fs::write(&temp_path, b"LSYN\x00partial-conflict-session").expect("seed temp");
        assert!(temp_path.exists());

        let loaded = read_conflict_session(&paths).expect("prior head still loads");
        assert_eq!(loaded.conflict_revision, 1);
        assert_eq!(loaded.session_id, session.session_id);
        assert_eq!(std::fs::read(&paths.conflicts).expect("retain head"), prior);
        assert_ne!(
            std::fs::read(&temp_path).expect("temp"),
            prior,
            "temp must not equal promoted conflict head"
        );
    }

    /// Transition: `KeepRemote` local store apply done; crash before `advance_baseline_after_local_pull`.
    /// Recoverability: baseline stays prior; re-collect pull mutations + advance completes once.
    #[test]
    fn crash_after_local_pull_before_baseline_advance_completes_on_revive() {
        let temporary = tempdir().expect("temp");
        let root = temporary.path();
        let paths = SyncPaths::for_workspace(root);
        let mut store = Store::open(root).expect("store open");

        let local_body = "# local pull crash\n";
        let remote_body = "# remote pull crash\n";
        let base_body = "# base pull crash\n";
        let d_remote = body_digest(remote_body.as_bytes());
        let d_base = body_digest(base_body.as_bytes());

        seed_memo(&mut store, "op-seed-pull-crash", "pull-c", local_body);
        let _session = open_conflict_with_side_artifacts(
            &paths,
            "crash-local-pull",
            "memos/pull-c.md",
            local_body.as_bytes(),
            remote_body.as_bytes(),
            base_body.as_bytes(),
            "tok-r",
        );
        let resolved = resolve_sync_conflicts(
            &paths,
            1,
            &[ConflictResolution::KeepRemote {
                path: "memos/pull-c.md".to_owned(),
            }],
        )
        .expect("resolve KeepRemote");
        assert_eq!(resolved.session.conflict_revision, 2);

        let mut baseline = BaselineHead::empty();
        baseline.fence = Some(fence());
        baseline.upsert(&path("memos/pull-c.md"), &d_base, "tok-base".to_owned());
        write_baseline(&paths, &baseline).expect("seed baseline");

        let mutations =
            collect_resolved_local_pull_mutations(&paths, &resolved.session).expect("pull bodies");
        assert_eq!(mutations.len(), 1);
        assert_eq!(
            mutations
                .first()
                .expect("one mutation")
                .content_digest
                .as_str(),
            d_remote.as_str()
        );
        store_upsert_memo_from_pull(
            &mut store,
            "op-pull-crash",
            "pull-c",
            remote_body.to_owned(),
        );
        // Crash: store has remote body; baseline still prior.
        let after_crash = read_baseline(&paths).expect("baseline after store apply");
        assert_eq!(
            after_crash
                .get("memos/pull-c.md")
                .map(|e| e.digest.as_str()),
            Some(d_base.as_str())
        );

        let advanced = advance_baseline_after_local_pull(&paths, 2, after_crash, &mutations)
            .expect("advance on revive");
        assert_eq!(
            advanced.get("memos/pull-c.md").map(|e| e.digest.as_str()),
            Some(d_remote.as_str())
        );
        let durable = read_baseline(&paths).expect("durable baseline");
        assert_eq!(
            durable.get("memos/pull-c.md").map(|e| e.digest.as_str()),
            Some(d_remote.as_str())
        );
    }
}
