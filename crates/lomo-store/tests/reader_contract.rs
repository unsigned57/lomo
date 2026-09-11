//! Behavior Contract: bounded projection readers (audit-03 Q1/Q3)
//!
//! Capability: a Rust-owned read connection observes the current published projection without
//! borrowing the single writer connection, while preserving the revision attached to each page.
//!
//! Scenarios:
//! - Given a reader opened before a writer publication, when the writer pins a seeded memo, then
//!   the reader observes the pin and the new high-water revision.
//! - Given several readers on one WAL projection, when each queries the same bounded page, then
//!   every reader returns the same published snapshot.
//! - Given a reader opened against a missing projection, when it is constructed, then opening
//!   fails instead of creating an implicit writable database.
//!
//! Observable outcomes: returned page items, high-water revision, pin flag, and structured open
//! failure.
//!
//! TDD proof:
//!
//! - Fails before the reader boundary exists because `StoreReader` is not exported.
//!
//! Test Change Justification:
//!
//! - Reason category: Direct `memos/<id>.md` writer removed; session owns document writes.
//! - Old behavior/assertion being replaced: reader refresh after Direct Create publication.
//! - Why old assertion is no longer correct: Create/pin Direct writes are
//!   `session_owns_document_writes`; projection pin publication still proves WAL refresh.
//! - Coverage preserved by: session-shaped pin publication still proves the reader observes WAL commits.
//!
//! Excludes:
//!
//! - pool scheduling internals, SQLite query-plan implementation, and UI lifecycle policy.

mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use super::support::{indexed_store, publish_pin, seed_memo};
    use lomo_core::{ErrorCategory, PageSize};
    use lomo_store::{MemoFilters, MemoQuery, MemoSort, StoreReader};
    use tempfile::tempdir;

    fn query() -> MemoQuery {
        MemoQuery {
            search_text: None,
            filters: MemoFilters::default(),
            sort: MemoSort::default(),
        }
    }

    fn pin(store: &mut lomo_store::Store, id: &str) {
        publish_pin(store, id, &format!("op-pin-{id}"));
    }

    #[test]
    fn reader_refreshes_revision_after_writer_publication() {
        let root = tempdir().expect("workspace");
        seed_memo(root.path(), "reader-visible", "body reader-visible", &[]);
        let mut writer = indexed_store(root.path());
        let reader = StoreReader::open(root.path()).expect("reader");

        let initial = reader
            .query_memos(&query(), None, PageSize::new(10).expect("page"))
            .expect("initial page");
        assert_eq!(1, initial.items.len());
        assert!(!initial.items.first().expect("seeded memo").is_pinned);
        let before = initial.high_water_revision;

        pin(&mut writer, "reader-visible");

        let published = reader
            .query_memos(&query(), None, PageSize::new(10).expect("page"))
            .expect("published page");
        assert_eq!(1, published.items.len());
        assert!(published.high_water_revision > before);
        let item = published.items.first().expect("published memo");
        assert_eq!("reader-visible", item.memo_id.as_str());
        assert!(item.is_pinned);
    }

    #[test]
    fn independent_readers_share_the_same_published_snapshot() {
        let root = tempdir().expect("workspace");
        seed_memo(root.path(), "same-snapshot", "body same-snapshot", &[]);
        let _writer = indexed_store(root.path());
        let first = StoreReader::open(root.path()).expect("first reader");
        let second = StoreReader::open(root.path()).expect("second reader");

        let first_page = first
            .query_memos(&query(), None, PageSize::new(10).expect("page"))
            .expect("first page");
        let second_page = second
            .query_memos(&query(), None, PageSize::new(10).expect("page"))
            .expect("second page");
        assert_eq!(first_page, second_page);
    }

    #[test]
    fn reader_does_not_create_a_missing_projection() {
        let root = tempdir().expect("workspace");
        let error = StoreReader::open(root.path()).expect_err("missing projection must fail");
        assert_eq!(ErrorCategory::Storage, error.category());
    }
}
