//! Behavior Contract:
//! Capability: acknowledge superseded edits only through exact rebuilt history and terminal facts;
//! owning layer: lomo-store query projection; priority: P0.
//! Scenarios:
//! - Given two indexed edits and lost receipts, when the earlier edit cites the exact terminal
//!   version, then its original revision/fingerprint are acknowledged without changing current data.
//! - Given missing earlier history or an incorrect terminal version/fingerprint, when acknowledged,
//!   then recovery fails closed and inserts no receipt.
//! - Given a later sibling edit in the same source, when the document fingerprint advances,
//!   then an unchanged target version is attested against that final document fingerprint.
//!
//! Observable outcomes: receipt revisions/fingerprints, typed rejection and unchanged live bodies.
//! TDD proof: application edit-batch-red.log exposed `recovery_projection_mismatch` for a real
//! earlier journal member; this suite locks the admission boundary added for that capability.
//! Excludes: workspace bytes, recovery ordering and scheduler latency.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "fixture failures stop the receipt contract"
)]
mod tests {
    use lomo_store::{
        DocumentPublication, ProjectionClock, RebuiltPublicationEvidence, SafProjectionMutation,
        SafProjectionMutationKind, ScannedHistoryProjection, ScannedMemoProjection, Store,
        fingerprint_content,
    };
    use rusqlite::Connection;
    use tempfile::tempdir;

    fn publication(
        memo: &str,
        revision: u64,
        body: &str,
        before: &str,
        after: &str,
    ) -> DocumentPublication {
        DocumentPublication {
            history: Some(ScannedHistoryProjection {
                memo_id: memo.to_owned(),
                record_id: fingerprint_content(&format!("{memo}-{revision}")),
                revision,
                created_at_ms: 1,
                content: body.to_owned(),
                file_fingerprint: fingerprint_content(body),
            }),
            mutation: SafProjectionMutation {
                operation_id: format!("{memo}-{revision}"),
                kind: if revision == 1 {
                    SafProjectionMutationKind::Create
                } else {
                    SafProjectionMutationKind::Update
                },
                memo_id: memo.to_owned(),
                expected_revision: revision - 1,
                expected_fingerprint: if revision == 1 {
                    None
                } else {
                    Some(before.to_owned())
                },
                projection: Some(ScannedMemoProjection {
                    memo_id: memo.to_owned(),
                    source_path: "2026_11_04.md".to_owned(),
                    time_part: "09:30:00".to_owned(),
                    file_fingerprint: after.to_owned(),
                    chronology_epoch_ms: 1,
                    body: body.to_owned(),
                    tags: Vec::new(),
                    attachment_paths: Vec::new(),
                    has_todo: false,
                    has_url: false,
                    reminders: Vec::new(),
                }),
                trashed_at_ms: None,
                batch_targets: Vec::new(),
            },
        }
    }
    fn forget_receipts(store: &Store) -> Connection {
        let connection =
            Connection::open(store.open_info().database_path).expect("projection connection");
        connection
            .execute("DELETE FROM saf_mutation_operation", [])
            .expect("simulate lost receipt index");
        connection
    }
    fn index_edits(
        store: &mut Store,
    ) -> (DocumentPublication, DocumentPublication, ProjectionClock) {
        let first = fingerprint_content("source-1");
        let second = fingerprint_content("source-2");
        let third = fingerprint_content("source-3");
        store
            .publish_document(&publication("memo-a", 1, "original", "", &first))
            .expect("create");
        let earlier = publication("memo-a", 2, "earlier", &first, &second);
        let terminal = publication("memo-a", 3, "terminal", &second, &third);
        let receipt = store.publish_document(&earlier).expect("earlier");
        store.publish_document(&terminal).expect("terminal");
        (
            earlier,
            terminal,
            ProjectionClock {
                core_revision: receipt.core_revision,
                event_sequence: receipt.event_sequence,
            },
        )
    }
    #[test]
    fn an_earlier_edit_requires_history_and_terminal_evidence_and_preserves_its_receipt() {
        let temp = tempdir().expect("projection");
        let mut store = Store::open_projection(temp.path()).expect("store");
        let (earlier, terminal, clock) = index_edits(&mut store);
        let _connection = forget_receipts(&store);
        let rejected = store
            .acknowledge_rebuilt_publication(&earlier, clock, RebuiltPublicationEvidence::Current)
            .expect_err("earlier is not current");
        assert_eq!(rejected.code(), "recovery_projection_mismatch");
        let final_fingerprint = terminal
            .mutation
            .projection
            .as_ref()
            .expect("terminal source")
            .file_fingerprint
            .as_str();
        let receipt = store
            .acknowledge_rebuilt_publication(
                &earlier,
                clock,
                RebuiltPublicationEvidence::EditChain {
                    terminal_memo: &terminal,
                    document_fingerprint: final_fingerprint,
                },
            )
            .expect("history-attested receipt");
        assert_eq!(receipt.content_revision, 2);
        assert_eq!(
            receipt.file_fingerprint,
            earlier
                .mutation
                .projection
                .as_ref()
                .expect("earlier source")
                .file_fingerprint
        );
        assert_eq!(
            store
                .get_projected_memo("memo-a")
                .expect("memo")
                .expect("present")
                .body,
            "terminal"
        );
    }
    #[test]
    fn missing_history_or_wrong_terminal_facts_never_manufacture_a_receipt() {
        let temp = tempdir().expect("projection");
        let mut store = Store::open_projection(temp.path()).expect("store");
        let (earlier, terminal, clock) = index_edits(&mut store);
        let connection = forget_receipts(&store);
        let wrong = fingerprint_content("foreign source");
        let rejected = store
            .acknowledge_rebuilt_publication(
                &earlier,
                clock,
                RebuiltPublicationEvidence::EditChain {
                    terminal_memo: &terminal,
                    document_fingerprint: &wrong,
                },
            )
            .expect_err("wrong source");
        assert_eq!(rejected.code(), "recovery_projection_mismatch");
        let final_fingerprint = terminal
            .mutation
            .projection
            .as_ref()
            .expect("terminal source")
            .file_fingerprint
            .as_str();
        connection
            .execute("DELETE FROM revision_index WHERE revision=2", [])
            .expect("remove earlier immutable evidence");
        let rejected = store
            .acknowledge_rebuilt_publication(
                &earlier,
                clock,
                RebuiltPublicationEvidence::EditChain {
                    terminal_memo: &terminal,
                    document_fingerprint: final_fingerprint,
                },
            )
            .expect_err("history absent");
        assert_eq!(rejected.code(), "recovery_history_mismatch");
        assert!(
            !store
                .operation_was_committed(&earlier.mutation.operation_id)
                .expect("receipt index")
        );
    }
    #[test]
    fn a_sibling_edit_advances_the_source_without_advancing_the_targets_content_revision() {
        let temp = tempdir().expect("projection");
        let mut store = Store::open_projection(temp.path()).expect("store");
        let initial = fingerprint_content("initial");
        let middle = fingerprint_content("middle");
        let final_source = fingerprint_content("final");
        store
            .publish_document(&publication("memo-a", 1, "first original", "", &initial))
            .expect("create a");
        store
            .publish_document(&publication("memo-b", 1, "second original", "", &initial))
            .expect("create b");
        let earlier = publication("memo-a", 2, "first edited", &initial, &middle);
        let receipt = store.publish_document(&earlier).expect("edit a");
        store
            .publish_document(&publication(
                "memo-b",
                2,
                "second edited",
                &middle,
                &final_source,
            ))
            .expect("edit b");
        let _connection = forget_receipts(&store);
        let recovered = store
            .acknowledge_rebuilt_publication(
                &earlier,
                ProjectionClock {
                    core_revision: receipt.core_revision,
                    event_sequence: receipt.event_sequence,
                },
                RebuiltPublicationEvidence::EditChain {
                    terminal_memo: &earlier,
                    document_fingerprint: &final_source,
                },
            )
            .expect("sibling-attested document");
        assert_eq!(recovered.content_revision, 2);
        assert_eq!(recovered.file_fingerprint, middle);
    }
}
