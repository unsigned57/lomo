//! Behavior Contract (operation journal lifecycle)
//!
//! Capability: every store opening boundary applies the same bounded cleanup policy to the
//! operation journal, regardless of whether the caller owns a Direct workspace or an app-private
//! SAF projection.
//!
//! Scenarios:
//!
//! - Given a committed operation older than the retention window, when a SAF projection is
//!   opened, then the replay guard is removed before the projection is returned to callers.
//! - Given an incomplete operation, when the same boundary runs, then the recovery witness
//!   remains available instead of being silently discarded.
//!
//! Observable outcomes: durable operation-record presence after the open boundary.
//! TDD proof: RED before the fix because `Store::open_projection` opened the database without
//! invoking the operation-journal lifecycle; GREEN after both store-open paths share the cleanup.
//!
//! Test Change Justification:
//!
//! - Reason category: Direct pin/delete are refused (`session_owns_document_writes`).
//! - Old behavior/assertion being replaced: Direct pin minting the operation journal record.
//! - Why old assertion is no longer correct: `WorkspaceSession` owns lifecycle writes; cleanup
//!   still applies to whatever operation records exist on disk.
//! - Coverage preserved by: seeding committed vs incomplete operation records directly.
//!
//! Excludes: operation replay semantics and Android provider execution.

mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing durable facts"
)]
mod tests {
    use std::fs::File;
    use std::time::SystemTime;

    use super::support::{indexed_store, seed_memo};
    use lomo_store::{
        LomoPaths, LomoPayload, LomoRecordKind, MemoCommandKind, OperationIntent, OperationStatus,
        Store, cleanup_expired_operations, read_record, write_record_atomic,
    };
    use tempfile::tempdir;

    fn write_operation(root: &std::path::Path, operation_id: &str, status: OperationStatus) {
        let paths = LomoPaths::for_workspace(root);
        paths.ensure_layout().expect("layout");
        let intent = OperationIntent {
            operation_id: operation_id.to_owned(),
            command: MemoCommandKind::Pin,
            memo_id: "journal-memo".to_owned(),
            expected_revision: 1,
            expected_fingerprint: None,
            content: None,
            tags: Vec::new(),
            pin: Some(true),
            created_at_ms: None,
            status,
            content_revision_after: Some(1),
            file_fingerprint_after: Some("fp".to_owned()),
            core_revision_after: Some(2),
            event_sequence_after: Some(2),
            pending_promotes: Vec::new(),
            batch_targets: Vec::new(),
            batch_deleted_files: Vec::new(),
            batch_reminder_sets: Vec::new(),
            trashed_at_ms: None,
        };
        let body_json = serde_json::to_string(&intent).expect("intent json");
        write_record_atomic(
            &paths.operations.join(format!("{operation_id}.rec")),
            &LomoPayload {
                kind: LomoRecordKind::Operation,
                record_id: operation_id.to_owned(),
                body_json,
            },
        )
        .expect("write operation");
    }

    #[test]
    fn projection_open_cleans_expired_committed_operations() {
        let root = tempdir().expect("workspace root");
        seed_memo(root.path(), "journal-memo", "journal lifecycle", &[]);
        let direct = indexed_store(root.path());
        write_operation(root.path(), "operation-expired", OperationStatus::Committed);
        let operation_path = LomoPaths::for_workspace(root.path())
            .operations
            .join("operation-expired.rec");
        assert!(operation_path.is_file(), "seed must leave a replay guard");
        File::open(&operation_path)
            .expect("open operation")
            .set_modified(SystemTime::UNIX_EPOCH)
            .expect("age operation");
        drop(direct);

        let _projection = Store::open_projection(root.path()).expect("projection open");

        assert!(
            !operation_path.exists(),
            "all store opening modes must enforce the same bounded committed-log policy"
        );
    }

    #[test]
    fn explicit_cleanup_preserves_incomplete_operation_witness() {
        let root = tempdir().expect("workspace root");
        seed_memo(root.path(), "journal-memo", "journal lifecycle", &[]);
        let _store = indexed_store(root.path());
        write_operation(
            root.path(),
            "operation-incomplete",
            OperationStatus::IntentAppended,
        );
        let operation_path = LomoPaths::for_workspace(root.path())
            .operations
            .join("operation-incomplete.rec");
        File::open(&operation_path)
            .expect("open operation")
            .set_modified(SystemTime::UNIX_EPOCH)
            .expect("age operation");

        let removed = cleanup_expired_operations(root.path(), 0).expect("cleanup");
        assert_eq!(removed, 0, "incomplete records are recovery witnesses");
        let record = read_record(&operation_path).expect("witness remains readable");
        assert_eq!(record.payload.kind, LomoRecordKind::Operation);
    }
}
