//! Behavior Contract: bounded memo-history paging (audit W3 P-30)
//!
//! Capability: `list_memo_history` serves `revision_index` pages in
//! `revision DESC, history_record_id ASC` order through an opaque keyset cursor — the cursor
//! carries the last row's `(revision, history_record_id)` bound plus the store high-water it was
//! minted under, so every page costs `O(limit)` instead of `OFFSET` re-scanning, and a malformed,
//! foreign-memo, stale, or out-of-range cursor fails closed instead of silently restarting or
//! skipping history.
//!
//! Scenarios:
//! - Given revisions sharing one revision number, when history is paged, then every row appears
//!   exactly once in `revision DESC, history_record_id ASC` order across page boundaries, and the
//!   opaque cursor is not a raw offset.
//! - Given a cursor minted before a newer projection commit, when it is reused, then the stale
//!   cursor is rejected instead of paging against shifted rows.
//! - Given a cursor minted for one memo, when it is replayed against another memo, then the
//!   cursor binding is rejected.
//! - Given a malformed or legacy decimal-offset cursor, when it is decoded, then validation
//!   fails closed.
//! - Given a structurally valid cursor whose bound row no longer exists under the current
//!   revision, when it is applied, then the out-of-range bound is rejected.
//!
//! Observable outcomes: page contents, `next_cursor` opacity, and structured failure codes.
//!
//! TDD proof: RED before the fix because `list_memo_history` parsed the cursor as a decimal
//! `OFFSET`, so a stale cursor silently re-paged, a cross-memo cursor silently answered the
//! foreign memo, and every page re-scanned all skipped rows.
//!
//! Excludes:
//!
//! - Memo-list `PageCursor` keyset paging (already owned by `query.rs`), history retention
//!   windows, and rebuild orchestration.

mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_store::{
        ScannedMemoProjection, Store, StoreReader, database_path, fingerprint_content,
        rebuild_scanned_projection,
    };
    use rusqlite::params;
    use tempfile::tempdir;

    const MEMO: &str = "2026-08-04_09:30:00_0";

    fn seed_projection(root: &std::path::Path) {
        let memo = ScannedMemoProjection {
            memo_id: MEMO.to_owned(),
            source_path: "memos/2026-08-04.md".to_owned(),
            file_fingerprint: fingerprint_content("rev1"),
            chronology_epoch_ms: 1_754_300_000_000,
            body: "rev1".to_owned(),
            tags: Vec::new(),
            attachment_paths: Vec::new(),
            has_todo: false,
            has_url: false,
            reminders: Vec::new(),
        };
        rebuild_scanned_projection(root, &[memo]).expect("seed projection");
    }

    /// Writes one durable `revision_index` row the way the history indexer does.
    fn seed_revision(root: &std::path::Path, revision: i64, record_id: &str) {
        let connection = rusqlite::Connection::open(database_path(root)).expect("open projection");
        connection
            .execute(
                "INSERT INTO revision_index( \
                 memo_id, revision, history_record_id, created_at_ms, content, file_fingerprint) \
                 VALUES(?1, ?2, ?3, ?4, ?5, ?6)",
                params![
                    MEMO,
                    revision,
                    record_id,
                    1_754_300_000_000 + revision,
                    format!("body of {record_id}"),
                    fingerprint_content(&format!("body of {record_id}")),
                ],
            )
            .expect("insert revision row");
    }

    /// Seeds revisions `(5, rec-5a)`, `(5, rec-5b)`, `(4, rec-4)`, `(2, rec-2)`, `(1, rec-1)` —
    /// the same-revision pair exercises the record-id tie-break across a page boundary.
    fn seed_history_chain(root: &std::path::Path) {
        for (revision, record_id) in [
            (5_i64, "rec-5a"),
            (5, "rec-5b"),
            (4, "rec-4"),
            (2, "rec-2"),
            (1, "rec-1"),
        ] {
            seed_revision(root, revision, record_id);
        }
    }

    #[test]
    fn history_pages_walk_the_keyset_order_once() {
        let root = tempdir().expect("projection root");
        seed_projection(root.path());
        seed_history_chain(root.path());
        let reader = StoreReader::open(root.path()).expect("reader");

        let first = reader.list_memo_history(MEMO, None, 2).expect("first page");
        assert_eq!(
            first
                .items
                .iter()
                .map(|item| item.record_id.as_str())
                .collect::<Vec<_>>(),
            vec!["rec-5a", "rec-5b"],
        );
        let cursor = first.next_cursor.expect("more history remains");
        // An opaque keyset cursor is never a re-scanned decimal offset.
        assert!(
            cursor.parse::<usize>().is_err(),
            "history cursor must not be a bare OFFSET: {cursor}"
        );

        let second = reader
            .list_memo_history(MEMO, Some(&cursor), 2)
            .expect("second page");
        assert_eq!(
            second
                .items
                .iter()
                .map(|item| item.record_id.as_str())
                .collect::<Vec<_>>(),
            vec!["rec-4", "rec-2"],
        );

        let third = reader
            .list_memo_history(MEMO, second.next_cursor.as_deref(), 2)
            .expect("third page");
        assert_eq!(
            third
                .items
                .iter()
                .map(|item| item.record_id.as_str())
                .collect::<Vec<_>>(),
            vec!["rec-1"],
        );
        assert_eq!(third.next_cursor, None);
    }

    #[test]
    fn history_cursor_fails_closed_after_a_newer_commit() {
        let root = tempdir().expect("projection root");
        seed_projection(root.path());
        seed_history_chain(root.path());
        let reader = StoreReader::open(root.path()).expect("reader");
        let first = reader.list_memo_history(MEMO, None, 2).expect("first page");
        let cursor = first.next_cursor.expect("more history remains");

        // Any committed projection mutation advances the high-water the cursor was minted under.
        let bump = rusqlite::Connection::open(database_path(root.path())).expect("raw");
        bump.execute(
            "UPDATE store_meta SET value = CAST(value AS INTEGER) + 1 WHERE key = 'high_water_revision'",
            [],
        )
        .expect("bump high-water");
        drop(bump);

        let error = reader
            .list_memo_history(MEMO, Some(&cursor), 2)
            .expect_err("a cursor minted under an older revision must not re-page");
        assert_eq!(error.code(), "stale_cursor");
    }

    #[test]
    fn history_cursor_is_bound_to_its_own_memo() {
        let root = tempdir().expect("projection root");
        seed_projection(root.path());
        seed_history_chain(root.path());
        let other = ScannedMemoProjection {
            memo_id: "2026-08-05_09:30:00_1".to_owned(),
            source_path: "memos/2026-08-05.md".to_owned(),
            file_fingerprint: fingerprint_content("other body"),
            chronology_epoch_ms: 1_754_300_000_000,
            body: "other body".to_owned(),
            tags: Vec::new(),
            attachment_paths: Vec::new(),
            has_todo: false,
            has_url: false,
            reminders: Vec::new(),
        };
        let connection = rusqlite::Connection::open(database_path(root.path())).expect("raw");
        connection
            .execute(
                "INSERT INTO revision_index( \
                 memo_id, revision, history_record_id, created_at_ms, content, file_fingerprint) \
                 VALUES(?1, 9, 'other-rec', 1754300000009, 'other', 'fp')",
                params![other.memo_id],
            )
            .expect("insert other memo revision");
        let reader = StoreReader::open(root.path()).expect("reader");
        let first = reader.list_memo_history(MEMO, None, 2).expect("first page");
        let cursor = first.next_cursor.expect("more history remains");

        let error = reader
            .list_memo_history(&other.memo_id, Some(&cursor), 2)
            .expect_err("a cursor minted for one memo must not page another");
        assert_eq!(error.code(), "invalid_history_cursor");
    }

    #[test]
    fn history_cursor_rejects_malformed_and_legacy_offset_forms() {
        let root = tempdir().expect("projection root");
        seed_projection(root.path());
        seed_history_chain(root.path());
        let reader = StoreReader::open(root.path()).expect("reader");

        for cursor in ["not-json", "2", ""] {
            let error = reader
                .list_memo_history(MEMO, Some(cursor), 2)
                .expect_err("malformed or legacy offset cursors must fail closed");
            assert_eq!(error.code(), "invalid_history_cursor", "cursor: {cursor}");
        }
    }

    #[test]
    fn history_cursor_rejects_a_bound_row_that_is_not_visible() {
        let root = tempdir().expect("projection root");
        seed_projection(root.path());
        seed_history_chain(root.path());
        let reader = StoreReader::open(root.path()).expect("reader");
        let store = Store::open_projection(root.path()).expect("store");
        let high_water = store.high_water_revision();
        drop(store);

        // A forged cursor under the current revision naming a bound row that was never
        // visible to the pageable history set must not silently restart the listing. The JSON
        // field names are the opaque wire contract, spelled out deliberately.
        let forged = format!(
            "{{\"memo_id\":\"{MEMO}\",\"last_revision\":4,\
             \"last_history_record_id\":\"absent-record\",\
             \"high_water_revision\":{high_water}}}"
        );
        let error = reader
            .list_memo_history(MEMO, Some(&forged), 2)
            .expect_err("an out-of-range bound must fail closed");
        assert_eq!(error.code(), "invalid_history_cursor");
    }
}
