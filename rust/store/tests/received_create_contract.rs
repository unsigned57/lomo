//! Behavior Contract (Stage-6 received memo identity allocation)
//!
//! Capability: the store single writer allocates a content-independent memo identity for a
//! received memo from its original timestamp and the next ordinal at that timestamp.
//!
//! Scenarios:
//! - Given two different operations with the same original timestamp and content, when both are
//!   created, then two memos exist with ordinal 0 and 1 identities.
//! - Given a received memo, when its projection is queried, then `created_at_ms` preserves the
//!   sender's original timestamp rather than the local commit clock.
//! - Given the first operation is replayed, when create runs again, then the existing memo id is
//!   returned and no third memo is created.
//! - Given a verified received attachment and remapped body, when create commits, then promote and
//!   Markdown publication share the same durable operation.
//!
//! Observable outcomes: committed memo ids, persisted memo count, projected creation timestamp,
//! and idempotent replay state.
//!
//! TDD proof: fails before the fix because `Store` has no atomic received-memo create operation;
//! callers must currently provide an arbitrary memo id and create projections use commit time.
//!
//! Excludes: LAN sockets, attachment byte transport, Kotlin adapters.

#![deny(unsafe_code)]

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed when observable store facts are missing"
)]
mod tests {
    use lomo_core::{OperationId, PageSize};
    use lomo_media::{
        MediaRelativePath, MediaSource, PromotePlan, stage_media, write_bytes_for_tests,
    };
    use lomo_store::{MemoFilters, MemoQuery, Store};
    use lomo_workspace::{load_or_mint_workspace_generation, mint_new_workspace_generation};
    use tempfile::tempdir;

    const ORIGINAL_TIMESTAMP_MS: i64 = 1_700_000_000_123;
    const PNG_1X1: &[u8] = &[
        0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, b'I', b'H', b'D', b'R', 0, 0,
        0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0x1F, 0x15, 0xC4, 0x89,
    ];

    #[test]
    fn same_timestamp_operations_allocate_distinct_ordinals_and_replay_once() {
        let dir = tempdir().expect("tempdir");
        let mut store = Store::open(dir.path()).expect("open store");
        let generation =
            load_or_mint_workspace_generation(dir.path()).expect("workspace generation");

        let first = store
            .create_received_memo(
                OperationId::parse("lan-item-a").expect("operation id"),
                generation.as_str(),
                ORIGINAL_TIMESTAMP_MS,
                "same body".to_owned(),
                Vec::new(),
            )
            .expect("first received memo commits");
        let second = store
            .create_received_memo(
                OperationId::parse("lan-item-b").expect("operation id"),
                generation.as_str(),
                ORIGINAL_TIMESTAMP_MS,
                "same body".to_owned(),
                Vec::new(),
            )
            .expect("second received memo commits");

        assert_eq!(first.memo_id, "1700000000123_epochms_0");
        assert_eq!(second.memo_id, "1700000000123_epochms_1");
        assert_ne!(first.memo_id, second.memo_id);

        let first_snapshot = store
            .get_memo(&first.memo_id)
            .expect("query first")
            .expect("first memo exists");
        assert_eq!(
            first_snapshot.summary.created_at_ms, ORIGINAL_TIMESTAMP_MS,
            "received memo projection must preserve the sender's original timestamp"
        );

        let replay = store
            .create_received_memo(
                OperationId::parse("lan-item-a").expect("operation id"),
                generation.as_str(),
                ORIGINAL_TIMESTAMP_MS,
                "same body".to_owned(),
                Vec::new(),
            )
            .expect("operation replay succeeds");
        assert_eq!(replay.memo_id, first.memo_id);
        assert!(replay.idempotent_replay);

        let page = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters::default(),
                },
                None,
                PageSize::new(10).expect("page size"),
            )
            .expect("query memos");
        assert_eq!(page.items.len(), 2, "replay must not create a third memo");
    }

    #[test]
    fn changed_workspace_generation_rejects_received_create_without_a_write() {
        let dir = tempdir().expect("tempdir");
        let approved =
            load_or_mint_workspace_generation(dir.path()).expect("approved generation exists");
        let live = mint_new_workspace_generation(dir.path()).expect("workspace switches");
        assert_ne!(approved, live);
        let mut store = Store::open(dir.path()).expect("open store");

        let error = store
            .create_received_memo(
                OperationId::parse("lan-stale-generation").expect("operation id"),
                approved.as_str(),
                ORIGINAL_TIMESTAMP_MS,
                "must not land".to_owned(),
                Vec::new(),
            )
            .expect_err("approval from the prior workspace generation must fail closed");
        assert_eq!(error.code(), "lan_workspace_generation_changed");

        let page = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters::default(),
                },
                None,
                PageSize::new(10).expect("page size"),
            )
            .expect("query memos");
        assert!(
            page.items.is_empty(),
            "generation mismatch must write no memo"
        );
    }

    #[test]
    fn received_create_promotes_attachment_under_the_same_operation() {
        let dir = tempdir().expect("tempdir");
        let generation =
            load_or_mint_workspace_generation(dir.path()).expect("workspace generation");
        let incoming = dir.path().join("incoming-received.png");
        write_bytes_for_tests(&incoming, PNG_1X1).expect("write received attachment temp");
        let staged = stage_media(
            dir.path(),
            MediaSource::StagedTemp { path: incoming },
            "received.png",
        )
        .expect("received attachment stages");
        let operation_id = OperationId::parse("lan-item-with-attachment").expect("operation id");
        let final_relative =
            MediaRelativePath::parse("media/received.png").expect("final media path");
        let mut store = Store::open(dir.path()).expect("open store");

        let committed = store
            .create_received_memo(
                operation_id.clone(),
                generation.as_str(),
                ORIGINAL_TIMESTAMP_MS,
                "![received](media/received.png)".to_owned(),
                vec![PromotePlan {
                    operation_id: operation_id.as_str().to_owned(),
                    staged,
                    final_relative_path: final_relative,
                }],
            )
            .expect("received memo and attachment commit together");

        assert!(dir.path().join("media/received.png").is_file());
        let snapshot = store
            .get_memo(&committed.memo_id)
            .expect("query committed memo")
            .expect("memo exists");
        assert_eq!(snapshot.body, "![received](media/received.png)");
    }
}
