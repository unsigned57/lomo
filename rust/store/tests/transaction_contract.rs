//! Behavior Contract (P3-05)
//!
//! Capability: memo create/update/delete/restore/pin/unpin/history-restore run the nine-step
//! machine with operation-id idempotency; crash points recover to complete-once or remain
//! explicitly pending; commits emit `CoreRevision` + `InvalidationScope`; stale expected
//! revision/fingerprint fails closed; deferred operation cleanup removes only committed intents.
//!
//! Scenarios:
//! - Given a create, when applied twice with the same `operation_id`, then the second is an
//!   idempotent replay without double-insert.
//! - Given a stale expected revision, when update runs, then `stale_snapshot` is returned.
//! - Given crash after intent / after history / after files / after projection / after committed
//!   mark, when the same `operation_id` is re-applied, then the mutation completes once and
//!   revision publishes exactly once.
//! - Given a successful commit, when scopes are inspected, then `MemoList`/`Search`/`Stats` appear.
//! - Given delete then pin, when durable `.lomo` state is read, then both trash and pin are true.
//! - Given create then update with todo/url content, when queried, then projections and FTS
//!   reflect the new body without losing identity.
//! - Given wrong expected fingerprint on update, when applied, then `stale_snapshot` fails closed.
//! - Given delete then restore, when listed, then the memo is active again and trash flag is false.
//! - Given pin then unpin, when filtered, then `pinned_only` no longer includes the memo.
//! - Given history-restore with new content at matching revision, when `get_memo` runs, then body
//!   matches the restored content and `content_revision` advances.
//! - Given invalid `memo_id` / create revision / duplicate create / missing content, when applied,
//!   then structured validation/conflict codes are returned without partial publish.
//! - Given committed operations older than retain window, when cleanup runs, then only committed
//!   intents are removed and pending intents remain.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::too_many_lines,
    reason = "contract tests fail closed with panics on missing facts; lifecycle matrix is intentionally long"
)]
mod tests {
    use lomo_core::{ErrorCategory, InvalidationScope, OperationId, PageSize};
    use lomo_store::{
        CrashPoint, LomoPaths, MemoCommand, MemoCommandKind, MemoFilters, MemoQuery, StateBody,
        Store, cleanup_expired_operations, read_record,
    };
    use tempfile::tempdir;

    fn create_cmd(op: &str, memo: &str, content: &str) -> MemoCommand {
        MemoCommand {
            operation_id: OperationId::parse(op).expect("op"),
            kind: MemoCommandKind::Create,
            memo_id: memo.into(),
            expected_revision: 0,
            expected_fingerprint: None,
            content: Some(content.into()),
            tags: vec!["t".into()],
            pin: None,
        }
    }

    fn recover_once(store: &mut Store, cmd: &MemoCommand, point: CrashPoint, code: &str) {
        let err = store
            .apply_memo_command(cmd, Some(point))
            .expect_err("injected crash");
        assert_eq!(err.code(), code);
        let hw_before = store.high_water_revision();
        let recovered = store.apply_memo_command(cmd, None).expect("recover");
        assert!(!recovered.idempotent_replay);
        assert_eq!(store.high_water_revision(), hw_before + 1);
        let again = store.apply_memo_command(cmd, None).expect("idempotent");
        assert!(again.idempotent_replay);
        assert_eq!(
            store.high_water_revision(),
            hw_before + 1,
            "idempotent replay must not double-publish"
        );
    }

    #[test]
    fn create_idempotent_and_stale_snapshot_and_scopes() {
        let dir = tempdir().expect("tempdir");
        let mut store = Store::open(dir.path()).expect("open");
        let cmd = create_cmd("op-create-1", "m1", "hello body");
        let first = store.apply_memo_command(&cmd, None).expect("create");
        assert!(!first.idempotent_replay);
        assert!(first.core_revision.get() >= 1);
        assert!(first.scopes.contains(&InvalidationScope::MemoList));
        assert!(first.scopes.contains(&InvalidationScope::Search));
        assert!(first.scopes.contains(&InvalidationScope::Stats));

        let second = store.apply_memo_command(&cmd, None).expect("replay");
        assert!(second.idempotent_replay);

        let page = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters::default(),
                },
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("query");
        assert_eq!(page.items.len(), 1);

        let err = store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-upd-stale").expect("op"),
                    kind: MemoCommandKind::Update,
                    memo_id: "m1".into(),
                    expected_revision: 0,
                    expected_fingerprint: None,
                    content: Some("x".into()),
                    tags: vec![],
                    pin: None,
                },
                None,
            )
            .expect_err("stale");
        assert_eq!(err.category(), ErrorCategory::Conflict);
        assert_eq!(err.code(), "stale_snapshot");
    }

    #[test]
    fn crash_point_matrix_recovers_complete_once() {
        let dir = tempdir().expect("tempdir");
        let mut store = Store::open(dir.path()).expect("open");

        recover_once(
            &mut store,
            &create_cmd("op-crash-intent", "c1", "after intent"),
            CrashPoint::AfterIntent,
            "crash_point_after_intent",
        );
        recover_once(
            &mut store,
            &create_cmd("op-crash-files", "c2", "after files"),
            CrashPoint::AfterFiles,
            "crash_point_after_files",
        );
        recover_once(
            &mut store,
            &create_cmd("op-crash-proj", "c3", "after projection"),
            CrashPoint::AfterProjection,
            "crash_point_after_projection",
        );

        // AfterCommittedMark: publish is durable before the mark; recovery is pure idempotent.
        let cmd4 = create_cmd("op-crash-commit", "c4", "after committed mark");
        let err = store
            .apply_memo_command(&cmd4, Some(CrashPoint::AfterCommittedMark))
            .expect_err("injected crash");
        assert_eq!(err.code(), "crash_point_after_committed_mark");
        let hw4 = store.high_water_revision();
        let seq4 = store.event_sequence();
        assert!(hw4 >= 1, "publish must precede AfterCommittedMark crash");
        let recovered4 = store
            .apply_memo_command(&cmd4, None)
            .expect("recover after committed mark");
        assert!(recovered4.idempotent_replay);
        assert_eq!(store.high_water_revision(), hw4);
        assert_eq!(store.event_sequence(), seq4);
        assert!(
            dir.path().join("memos").join("c4.md").exists(),
            "memo file must exist after AfterCommittedMark"
        );

        let page = store
            .query_memos(
                &MemoQuery {
                    search_text: Some("after".into()),
                    filters: MemoFilters::default(),
                },
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("search");
        assert!(page.items.len() >= 4);

        let pin = store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-pin-c1").expect("op"),
                    kind: MemoCommandKind::Pin,
                    memo_id: "c1".into(),
                    expected_revision: 1,
                    expected_fingerprint: None,
                    content: None,
                    tags: vec![],
                    pin: Some(true),
                },
                None,
            )
            .expect("pin");
        assert!(pin.scopes.contains(&InvalidationScope::Pin));
    }

    #[test]
    fn delete_then_pin_merges_durable_state_without_clobber() {
        let dir = tempdir().expect("tempdir");
        let mut store = Store::open(dir.path()).expect("open");
        store
            .apply_memo_command(&create_cmd("op-dtp-c", "dtp", "body"), None)
            .expect("create");
        store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-dtp-d").expect("op"),
                    kind: MemoCommandKind::Delete,
                    memo_id: "dtp".into(),
                    expected_revision: 1,
                    expected_fingerprint: None,
                    content: None,
                    tags: vec![],
                    pin: None,
                },
                None,
            )
            .expect("delete");
        store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-dtp-p").expect("op"),
                    kind: MemoCommandKind::Pin,
                    memo_id: "dtp".into(),
                    expected_revision: 1,
                    expected_fingerprint: None,
                    content: None,
                    tags: vec![],
                    pin: Some(true),
                },
                None,
            )
            .expect("pin");

        let paths = LomoPaths::for_workspace(dir.path());
        let record = read_record(&paths.state.join("dtp.rec")).expect("state");
        let body: StateBody =
            serde_json::from_str(&record.payload.body_json).expect("decode state");
        assert!(body.pinned);
        assert!(body.trashed);
        assert!(body.tags.iter().any(|t| t == "t"));

        let page = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters {
                        include_trash: true,
                        trash_only: true,
                        pinned_only: true,
                        ..MemoFilters::default()
                    },
                },
                None,
                PageSize::new(5).expect("page"),
            )
            .expect("query");
        assert_eq!(page.items.len(), 1);
        let item = page.items.first().expect("one item");
        assert!(item.is_pinned && item.is_trashed);
    }

    #[test]
    fn update_restore_unpin_and_history_restore_publish_once() {
        let dir = tempdir().expect("tempdir");
        let mut store = Store::open(dir.path()).expect("open");
        let create = store
            .apply_memo_command(&create_cmd("op-life-c", "life", "v1 body"), None)
            .expect("create");
        assert_eq!(create.content_revision, 1);

        // Wrong fingerprint fails closed before mutation.
        let fp_err = store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-life-bad-fp").expect("op"),
                    kind: MemoCommandKind::Update,
                    memo_id: "life".into(),
                    expected_revision: 1,
                    expected_fingerprint: Some("deadbeef".into()),
                    content: Some("nope".into()),
                    tags: vec![],
                    pin: None,
                },
                None,
            )
            .expect_err("bad fingerprint");
        assert_eq!(fp_err.code(), "stale_snapshot");

        // Missing update content fails closed.
        let missing = store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-life-no-body").expect("op"),
                    kind: MemoCommandKind::Update,
                    memo_id: "life".into(),
                    expected_revision: 1,
                    expected_fingerprint: None,
                    content: None,
                    tags: vec![],
                    pin: None,
                },
                None,
            )
            .expect_err("missing content");
        assert_eq!(missing.code(), "missing_content");

        let updated = store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-life-u").expect("op"),
                    kind: MemoCommandKind::Update,
                    memo_id: "life".into(),
                    expected_revision: 1,
                    expected_fingerprint: Some(create.file_fingerprint),
                    content: Some("v2 body with todo\n- [ ] ship\nhttps://example.com/path".into()),
                    tags: vec!["ship".into()],
                    pin: None,
                },
                None,
            )
            .expect("update");
        assert_eq!(updated.content_revision, 2);
        assert!(updated.scopes.contains(&InvalidationScope::Search));

        let snap = store.get_memo("life").expect("get").expect("present");
        assert!(snap.body.contains("v2 body"));
        assert!(snap.summary.has_todo);
        assert!(snap.summary.has_url);

        let pin = store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-life-pin").expect("op"),
                    kind: MemoCommandKind::Pin,
                    memo_id: "life".into(),
                    expected_revision: 2,
                    expected_fingerprint: None,
                    content: None,
                    tags: vec![],
                    pin: Some(true),
                },
                None,
            )
            .expect("pin");
        assert!(pin.scopes.contains(&InvalidationScope::Pin));

        store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-life-del").expect("op"),
                    kind: MemoCommandKind::Delete,
                    memo_id: "life".into(),
                    expected_revision: 2,
                    expected_fingerprint: None,
                    content: None,
                    tags: vec![],
                    pin: None,
                },
                None,
            )
            .expect("delete");
        assert!(
            store
                .query_memos(
                    &MemoQuery {
                        search_text: None,
                        filters: MemoFilters::default(),
                    },
                    None,
                    PageSize::new(10).expect("page"),
                )
                .expect("query")
                .items
                .is_empty()
        );

        let restored = store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-life-res").expect("op"),
                    kind: MemoCommandKind::Restore,
                    memo_id: "life".into(),
                    expected_revision: 2,
                    expected_fingerprint: None,
                    content: None,
                    tags: vec![],
                    pin: None,
                },
                None,
            )
            .expect("restore");
        assert!(restored.scopes.contains(&InvalidationScope::Trash));
        let after_restore = store.get_memo("life").expect("get").expect("restored");
        assert!(!after_restore.summary.is_trashed);
        assert!(after_restore.summary.is_pinned);

        store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-life-unpin").expect("op"),
                    kind: MemoCommandKind::Unpin,
                    memo_id: "life".into(),
                    expected_revision: 2,
                    expected_fingerprint: None,
                    content: None,
                    tags: vec![],
                    pin: Some(false),
                },
                None,
            )
            .expect("unpin");
        let pinned_only = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters {
                        pinned_only: true,
                        ..MemoFilters::default()
                    },
                },
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("pinned filter");
        assert!(
            pinned_only.items.iter().all(|m| m.memo_id != "life"),
            "unpin must drop pin projection"
        );

        let history = store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-life-hist").expect("op"),
                    kind: MemoCommandKind::HistoryRestore,
                    memo_id: "life".into(),
                    expected_revision: 2,
                    expected_fingerprint: None,
                    content: Some("history restored body".into()),
                    tags: vec!["hist".into()],
                    pin: None,
                },
                None,
            )
            .expect("history restore");
        assert_eq!(history.content_revision, 3);
        let hist_body = store.get_memo("life").expect("get").expect("present");
        assert_eq!(hist_body.body, "history restored body");
        assert_eq!(hist_body.summary.content_revision, 3);
    }

    #[test]
    fn validate_command_rejects_invalid_create_and_missing_targets() {
        let dir = tempdir().expect("tempdir");
        let mut store = Store::open(dir.path()).expect("open");

        let empty_id = store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-bad-empty").expect("op"),
                    kind: MemoCommandKind::Create,
                    memo_id: String::new(),
                    expected_revision: 0,
                    expected_fingerprint: None,
                    content: Some("x".into()),
                    tags: vec![],
                    pin: None,
                },
                None,
            )
            .expect_err("empty id");
        assert_eq!(empty_id.code(), "invalid_memo_id");

        let long_id = "m".repeat(129);
        let too_long = store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-bad-long").expect("op"),
                    kind: MemoCommandKind::Create,
                    memo_id: long_id,
                    expected_revision: 0,
                    expected_fingerprint: None,
                    content: Some("x".into()),
                    tags: vec![],
                    pin: None,
                },
                None,
            )
            .expect_err("long id");
        assert_eq!(too_long.code(), "invalid_memo_id");

        let bad_rev = store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-bad-rev").expect("op"),
                    kind: MemoCommandKind::Create,
                    memo_id: "ok".into(),
                    expected_revision: 3,
                    expected_fingerprint: None,
                    content: Some("x".into()),
                    tags: vec![],
                    pin: None,
                },
                None,
            )
            .expect_err("create rev");
        assert_eq!(bad_rev.code(), "invalid_create_revision");

        let no_content = store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-bad-nc").expect("op"),
                    kind: MemoCommandKind::Create,
                    memo_id: "ok".into(),
                    expected_revision: 0,
                    expected_fingerprint: None,
                    content: None,
                    tags: vec![],
                    pin: None,
                },
                None,
            )
            .expect_err("create content");
        assert_eq!(no_content.code(), "missing_content");

        store
            .apply_memo_command(&create_cmd("op-ok-c", "ok", "body"), None)
            .expect("create");
        let exists = store
            .apply_memo_command(&create_cmd("op-dup", "ok", "other"), None)
            .expect_err("duplicate");
        assert_eq!(exists.category(), ErrorCategory::Conflict);
        assert_eq!(exists.code(), "memo_already_exists");

        let missing = store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-miss").expect("op"),
                    kind: MemoCommandKind::Delete,
                    memo_id: "ghost".into(),
                    expected_revision: 1,
                    expected_fingerprint: None,
                    content: None,
                    tags: vec![],
                    pin: None,
                },
                None,
            )
            .expect_err("missing memo");
        assert_eq!(missing.code(), "memo_not_found");
        assert_eq!(
            store.high_water_revision(),
            1,
            "failed cmds must not publish"
        );
    }

    #[test]
    fn get_memo_fails_closed_when_markdown_body_is_missing() {
        let dir = tempdir().expect("tempdir");
        let mut store = Store::open(dir.path()).expect("open");
        store
            .apply_memo_command(&create_cmd("op-miss-body", "ghost-body", "present"), None)
            .expect("create");
        let body_path = dir.path().join("memos").join("ghost-body.md");
        std::fs::remove_file(&body_path).expect("unlink body");
        let err = store
            .get_memo("ghost-body")
            .expect_err("missing body must fail closed");
        assert_eq!(err.code(), "memo_body_read_failed");
        assert_eq!(err.category(), ErrorCategory::Storage);
    }

    #[test]
    fn after_history_crash_recovers_complete_once() {
        let dir = tempdir().expect("tempdir");
        let mut store = Store::open(dir.path()).expect("open");
        recover_once(
            &mut store,
            &create_cmd("op-crash-hist", "h1", "after history"),
            CrashPoint::AfterHistory,
            "crash_point_after_history",
        );
        let body = store.get_memo("h1").expect("get").expect("present");
        assert_eq!(body.body, "after history");
    }

    #[test]
    fn cleanup_expired_operations_removes_only_committed_intents() {
        let dir = tempdir().expect("tempdir");
        let mut store = Store::open(dir.path()).expect("open");
        store
            .apply_memo_command(&create_cmd("op-clean-1", "c1", "body"), None)
            .expect("create");

        // Leave a pending intent via injected crash; it must survive cleanup.
        let pending = create_cmd("op-clean-pending", "c2", "pending body");
        let err = store
            .apply_memo_command(&pending, Some(CrashPoint::AfterIntent))
            .expect_err("crash");
        assert_eq!(err.code(), "crash_point_after_intent");

        let paths = LomoPaths::for_workspace(dir.path());
        let committed = paths.operations.join("op-clean-1.rec");
        let pending_path = paths.operations.join("op-clean-pending.rec");
        assert!(committed.exists());
        assert!(pending_path.exists());

        // retain_ms=0 makes cutoff=now, so every committed intent with mtime<=now is eligible.
        let removed = cleanup_expired_operations(dir.path(), 0).expect("cleanup");
        assert!(
            removed >= 1,
            "at least the committed operation must be cleaned"
        );
        assert!(
            !committed.exists(),
            "committed intent must be removed by cleanup"
        );
        assert!(
            pending_path.exists(),
            "pending (non-committed) intent must remain"
        );

        // Recover pending after cleanup of unrelated ops.
        let recovered = store.apply_memo_command(&pending, None).expect("recover");
        assert!(!recovered.idempotent_replay);
        assert!(store.get_memo("c2").expect("get").is_some());

        // Empty operations tree / zero work is not an error.
        let empty = tempdir().expect("empty");
        assert_eq!(
            cleanup_expired_operations(empty.path(), 0).expect("empty cleanup"),
            0
        );
    }
}
