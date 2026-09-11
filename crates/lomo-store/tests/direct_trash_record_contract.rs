//! Behavior Contract (durable trash authority)
//!
//! Capability: Direct `apply_memo_command` does not write trash snapshots. Session owns trash
//! records; store rebuild rehydrates a seeded workspace `TrashRecordV1` without a Direct writer.
//!
//! Scenarios:
//! - Given a seeded Direct memo, when soft delete is applied through Store Direct, then
//!   `session_owns_document_writes` is returned and no `.lomo/trash` marker is minted.
//! - Given a workspace-owned trash marker and no live Markdown, when rebuild runs, then the
//!   projection is trashed with the marker fingerprint and the marker remains on disk.
//!
//! Observable outcomes: Direct error code, absence of Direct trash sidecars, rebuilt trash
//! projection from a seeded marker.
//! TDD proof: Direct delete previously moved `trash/{id}.md`; session/history contracts own the
//! write path now.
//! Excludes: SAF `DocumentsProvider` actions and UI scheduling.

mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing durable facts"
)]
mod tests {
    use std::fs;

    use super::support::{indexed_store, seed_memo};
    use lomo_core::OperationId;
    use lomo_store::{
        MemoCommand, MemoCommandKind, Store, fingerprint_content, project_reminder_references,
    };
    use lomo_workspace::{
        TrashRecordCreate, TrashRecordV1, encode_trash_record, trash_record_relative_path,
    };
    use tempfile::tempdir;

    const MEMO_ID: &str = "2026_09_04_12:00:00_0";
    const BODY: &str = "# work\n\n@2026-09-04-13:00x2";

    fn command(
        operation_id: &str,
        kind: MemoCommandKind,
        revision: u64,
        fingerprint: Option<String>,
    ) -> MemoCommand {
        MemoCommand {
            operation_id: OperationId::parse(operation_id).expect("operation id"),
            kind,
            memo_id: MEMO_ID.to_owned(),
            expected_revision: revision,
            expected_fingerprint: fingerprint,
            content: (kind == MemoCommandKind::Create).then(|| BODY.to_owned()),
            tags: vec!["work".to_owned()],
            pin: None,
            pending_promotes: Vec::new(),
        }
    }

    #[test]
    fn direct_delete_fail_closed_so_session_owns_trash_snapshots() {
        let root = tempdir().expect("workspace root");
        seed_memo(root.path(), MEMO_ID, BODY, &[]);
        let mut store = indexed_store(root.path());
        let before = store
            .get_memo_projection(MEMO_ID)
            .expect("projection")
            .expect("memo");
        let error = store
            .apply_memo_command(
                &command(
                    "trash-delete",
                    MemoCommandKind::Delete,
                    before.content_revision,
                    Some(before.file_fingerprint),
                ),
                None,
            )
            .expect_err("direct delete");
        assert_eq!(error.code(), "session_owns_document_writes");
        let relative = trash_record_relative_path(MEMO_ID).expect("record path");
        assert!(
            !root.path().join(relative.as_str()).exists(),
            "refused Direct delete must not mint a trash marker"
        );
        assert!(
            root.path()
                .join("memos")
                .join(format!("{MEMO_ID}.md"))
                .is_file(),
            "refused Direct delete must not move memos/<id>.md"
        );
    }

    #[test]
    fn rebuild_indexes_seeded_workspace_trash_marker() {
        let root = tempdir().expect("workspace root");
        let reminders = project_reminder_references(BODY, MEMO_ID).expect("reminders");
        let record = TrashRecordV1::try_new(TrashRecordCreate {
            memo_id: MEMO_ID.to_owned(),
            source_path: format!("memos/{MEMO_ID}.md"),
            time_part: lomo_workspace::MemoIdentity::parse(MEMO_ID)
                .expect("seed identity")
                .time_part()
                .to_owned(),
            source_fingerprint: fingerprint_content(BODY),
            chronology_epoch_ms: 1_756_976_400_000,
            trashed_at_ms: 1_756_980_000_000,
            body: BODY.to_owned(),
            tags: vec!["work".to_owned()],
            attachments: Vec::new(),
            reminders,
            has_todo: false,
            has_url: false,
        })
        .expect("trash record");
        let relative = trash_record_relative_path(MEMO_ID).expect("record path");
        let marker = root.path().join(relative.as_str());
        if let Some(parent) = marker.parent() {
            fs::create_dir_all(parent).expect("trash dir");
        }
        fs::write(&marker, encode_trash_record(&record).expect("encode")).expect("write marker");

        lomo_store::run_rebuild(root.path(), 4).expect("rebuild from marker");
        let rebuilt = Store::open(root.path()).expect("reopen rebuilt store");
        let summary = rebuilt
            .get_memo_projection(MEMO_ID)
            .expect("projection")
            .expect("marker memo");
        assert!(summary.is_trashed);
        assert_eq!(summary.file_fingerprint, fingerprint_content(BODY));
        assert!(
            marker.is_file(),
            "rebuild must not consume the durable trash record"
        );
    }
}
