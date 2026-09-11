//! Behavior Contract
//!
//! Capability: Store Direct no longer writes `memos/<id>.md` or pin/trash sidecars.
//! `WorkspaceSession` owns Markdown and lifecycle mutations. Layout V2 still refuses v1 writers;
//! promote selection stays in Rust.
//!
//! Scenarios:
//!
//! - Given a Direct create/update/restore/pin/delete, when `apply_memo_command` runs, then
//!   `session_owns_document_writes` is returned and no sidecar Markdown is written.
//! - Given unused and used staged media candidates, when `select_pending_promotes` runs, then only
//!   the body-referenced path is selected.
//! - Given layout V2, when `Store::open` or mutate runs, then `layout_v2_requires_v2_writers`.
//!
//! Observable outcomes: error codes, absence of `memos/<id>.md`, selected promote paths.
//! TDD proof: Direct pin/delete previously mutated projection and files; session owns those writes.
//! Excludes: `WorkspaceSession` crash recovery (`session_transaction_contract`).

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_core::{ErrorCategory, OperationId};
    use lomo_media::{
        MediaSource, PromotePlan, stage_media, suggest_human_relative_path, write_bytes_for_tests,
    };
    use lomo_store::{
        LomoLayoutVersion, LomoPaths, MemoCommand, MemoCommandKind, Store, select_pending_promotes,
    };
    use lomo_workspace::write_layout_head_v2;
    use tempfile::tempdir;

    const PNG_1X1: &[u8] = &[
        0x89, b'P', b'N', b'G', b'\r', b'\n', 0x1a, b'\n', 0x00, 0x00, 0x00, 0x0d, b'I', b'H',
        b'D', b'R', 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00,
        0x90, 0x77, 0x53, 0xde, 0x00, 0x00, 0x00, 0x0c, b'I', b'D', b'A', b'T', 0x08, 0xd7, 0x63,
        0xf8, 0xcf, 0xc0, 0x00, 0x00, 0x03, 0x01, 0x01, 0x00, 0x18, 0xdd, 0x8d, 0xb4, 0x00, 0x00,
        0x00, 0x00, b'I', b'E', b'N', b'D', 0xae, 0x42, 0x60, 0x82,
    ];

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
            pending_promotes: vec![],
        }
    }

    #[test]
    fn direct_document_writes_fail_closed_so_session_owns_markdown() {
        let dir = tempdir().expect("tempdir");
        let mut store = Store::open(dir.path()).expect("open");
        let error = store
            .apply_memo_command(&create_cmd("op-create-1", "m1", "hello body"), None)
            .expect_err("direct create");
        assert_eq!(error.code(), "session_owns_document_writes");
        assert!(
            !dir.path().join("memos").join("m1.md").exists(),
            "refused create must not write memos/<id>.md"
        );

        let update = store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-upd").expect("op"),
                    kind: MemoCommandKind::Update,
                    memo_id: "m1".into(),
                    expected_revision: 1,
                    expected_fingerprint: None,
                    content: Some("after".into()),
                    tags: vec![],
                    pin: None,
                    pending_promotes: vec![],
                },
                None,
            )
            .expect_err("direct update");
        assert_eq!(update.code(), "session_owns_document_writes");

        let restore = store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-restore").expect("op"),
                    kind: MemoCommandKind::Restore,
                    memo_id: "m1".into(),
                    expected_revision: 1,
                    expected_fingerprint: None,
                    content: None,
                    tags: vec![],
                    pin: None,
                    pending_promotes: vec![],
                },
                None,
            )
            .expect_err("direct restore");
        assert_eq!(restore.code(), "session_owns_document_writes");

        let pin = store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-pin").expect("op"),
                    kind: MemoCommandKind::Pin,
                    memo_id: "m1".into(),
                    expected_revision: 0,
                    expected_fingerprint: None,
                    content: None,
                    tags: vec![],
                    pin: Some(true),
                    pending_promotes: vec![],
                },
                None,
            )
            .expect_err("direct pin");
        assert_eq!(pin.code(), "session_owns_document_writes");

        let delete = store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-del").expect("op"),
                    kind: MemoCommandKind::Delete,
                    memo_id: "m1".into(),
                    expected_revision: 0,
                    expected_fingerprint: None,
                    content: None,
                    tags: vec![],
                    pin: None,
                    pending_promotes: vec![],
                },
                None,
            )
            .expect_err("direct delete");
        assert_eq!(delete.code(), "session_owns_document_writes");
    }

    #[test]
    fn rust_selects_only_body_referenced_staged_media() {
        let dir = tempdir().expect("tempdir");
        let root = dir.path();
        let first_source = root.join("unused.png");
        let second_source = root.join("used.png");
        write_bytes_for_tests(&first_source, PNG_1X1).expect("write first");
        let mut second_bytes = PNG_1X1.to_vec();
        second_bytes.push(1);
        write_bytes_for_tests(&second_source, &second_bytes).expect("write second");
        let first = stage_media(
            root,
            MediaSource::DirectPath { path: first_source },
            "unused.png",
        )
        .expect("stage first");
        let second = stage_media(
            root,
            MediaSource::DirectPath {
                path: second_source,
            },
            "used.png",
        )
        .expect("stage second");
        let first_path = suggest_human_relative_path("unused", first.mime).expect("first path");
        let second_path = suggest_human_relative_path("used", second.mime).expect("second path");
        let candidates = vec![
            PromotePlan {
                operation_id: "op-select-media".into(),
                staged: first,
                final_relative_path: first_path.clone(),
            },
            PromotePlan {
                operation_id: "op-select-media".into(),
                staged: second,
                final_relative_path: second_path.clone(),
            },
        ];
        let body = format!(
            "```md\n![example]({})\n```\n\n![used]({})",
            first_path.as_str(),
            second_path.as_str()
        );
        let selected = select_pending_promotes(&body, &candidates).expect("select");
        assert_eq!(selected.len(), 1);
        assert_eq!(
            selected
                .first()
                .map(|plan| plan.final_relative_path.clone()),
            Some(second_path),
        );
    }

    #[test]
    fn layout_v2_refuses_store_open_and_mutate_until_v2_writers() {
        let dir = tempdir().expect("tempdir");
        write_layout_head_v2(dir.path()).expect("layout head v2");
        assert_eq!(
            LomoPaths::for_workspace(dir.path()).layout,
            LomoLayoutVersion::V2
        );

        let open_err = match Store::open(dir.path()) {
            Ok(_store) => panic!("open must refuse layout V2"),
            Err(error) => error,
        };
        assert_eq!(open_err.code(), "layout_v2_requires_v2_writers");
        assert_eq!(open_err.category(), ErrorCategory::Validation);

        let dir_v1 = tempdir().expect("tempdir v1");
        let mut store = Store::open(dir_v1.path()).expect("open v1");
        write_layout_head_v2(dir_v1.path()).expect("switch head to v2");
        let mutate_err = store
            .apply_memo_command(&create_cmd("op-v2-fence", "m-v2", "should fail"), None)
            .expect_err("mutate under layout V2");
        assert_eq!(mutate_err.code(), "layout_v2_requires_v2_writers");
        let v2_history = dir_v1.path().join(".lomo/history/v2");
        if v2_history.is_dir() {
            let mut flat_records = Vec::new();
            for entry in std::fs::read_dir(&v2_history).expect("read v2 history") {
                let entry = entry.expect("dir entry");
                let path = entry.path();
                if path.is_file() && path.extension().is_some_and(|ext| ext == "rec") {
                    flat_records.push(path);
                }
            }
            assert!(
                flat_records.is_empty(),
                "must not write v1 flat history records under history/v2: {flat_records:?}"
            );
        }
    }
}
