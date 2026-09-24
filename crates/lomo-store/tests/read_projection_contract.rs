//! Behavior Contract: materialized read outlets (audit-01 读链路 F1/F2/F7)
//!
//! Capability: filtered counts, statistics, and list previews are read from the store projection
//! without paging through or transferring memo bodies.
//!
//! Scenarios:
//! - Given mixed memos, when `query_count` runs with a filter/search predicate, then the count
//!   equals the number of rows `query_memos` returns for the same predicate (trash excluded).
//! - Given created memos, when statistics rows are read, then word/character counts are
//!   materialized per memo, trash-only rows are excluded, and updates re-materialize the counts.
//! - Given a body longer than the preview budget, when the preview is materialized, then the cut
//!   lands on a block boundary and never inside an unclosed fenced code block.
//!
//! Observable outcomes: `query_count`, `memo_statistics_rows`, `body_preview` values.

mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use super::support::{indexed_store, publish_trash, seed_memo};
    use lomo_core::PageSize;
    use lomo_store::{
        MemoFilters, MemoQuery, MemoSort, Store, body_preview, count_characters, count_words,
    };
    use tempfile::tempdir;

    fn delete(store: &mut Store, id: &str) {
        publish_trash(store, id, &format!("del-{id}"));
    }

    fn all_query() -> MemoQuery {
        MemoQuery {
            search_text: None,
            filters: MemoFilters::default(),
            sort: MemoSort::default(),
        }
    }

    #[test]
    fn query_count_matches_page_rows_for_filters_and_search() {
        let dir = tempdir().expect("tempdir");
        seed_memo(dir.path(), "m1", "plain body one", &["work"]);
        seed_memo(dir.path(), "m2", "- [ ] task body two", &["work"]);
        seed_memo(dir.path(), "m3", "plain body three", &[]);
        seed_memo(dir.path(), "m4", "unique zebra token", &["home"]);
        let mut store = indexed_store(dir.path());

        let assert_count_matches = |store: &Store, query: &MemoQuery| {
            let expected = store
                .query_memos(query, None, PageSize::new(50).expect("page size"))
                .expect("page")
                .items
                .len();
            let count = store.query_count(query).expect("count");
            assert_eq!(
                u64::try_from(expected).expect("positive"),
                count,
                "count must equal the row set for the same predicate"
            );
        };

        assert_count_matches(&store, &all_query());
        assert_count_matches(
            &store,
            &MemoQuery {
                filters: MemoFilters {
                    tag: Some("work".into()),
                    ..MemoFilters::default()
                },
                ..all_query()
            },
        );
        assert_count_matches(
            &store,
            &MemoQuery {
                filters: MemoFilters {
                    has_todo: Some(true),
                    ..MemoFilters::default()
                },
                ..all_query()
            },
        );
        assert_count_matches(
            &store,
            &MemoQuery {
                search_text: Some("zebra".into()),
                ..all_query()
            },
        );

        delete(&mut store, "m1");
        assert_eq!(
            3,
            usize::try_from(store.query_count(&all_query()).expect("count")).expect("positive"),
            "trashed rows leave the active count"
        );
    }

    #[test]
    fn statistics_rows_are_materialized_without_bodies() {
        let dir = tempdir().expect("tempdir");
        seed_memo(dir.path(), "m1", "alpha beta beta", &[]);
        seed_memo(dir.path(), "m2", "一句中文两个词", &[]);
        let mut store = indexed_store(dir.path());

        let rows = store.memo_statistics_rows().expect("rows");
        assert_eq!(2, rows.len(), "one compact row per active memo");
        let m1 = rows
            .iter()
            .find(|row| {
                row.word_count == count_words("alpha beta beta")
                    && row.char_count == count_characters("alpha beta beta")
            })
            .expect("m1 materialized row");
        assert_eq!(3, m1.word_count, "word count materialized at commit");
        assert_eq!(
            count_words("alpha beta beta"),
            m1.word_count,
            "materialized count matches the word-count contract"
        );
        assert_eq!(
            count_characters("alpha beta beta"),
            m1.char_count,
            "character count materialized in UTF-16 code units"
        );

        delete(&mut store, "m2");
        let rows = store.memo_statistics_rows().expect("rows");
        assert_eq!(1, rows.len(), "trashed memos leave the statistics rows");
    }

    #[test]
    fn statistics_rows_carry_memo_identity_and_tag_facts() {
        let dir = tempdir().expect("tempdir");
        seed_memo(dir.path(), "m1", "alpha beta", &["work", "home"]);
        seed_memo(dir.path(), "m2", "untagged body", &[]);
        let store = indexed_store(dir.path());

        let rows = store.memo_statistics_rows().expect("rows");
        assert_eq!(2, rows.len(), "one fact row per active memo");
        let m1 = rows
            .iter()
            .find(|row| row.memo_id == "m1")
            .expect("m1 row keyed by memo identity");
        assert_eq!(
            vec!["home".to_owned(), "work".to_owned()],
            m1.tags,
            "tag facts travel with the materialized statistics row in canonical order"
        );
        let m2 = rows
            .iter()
            .find(|row| row.memo_id == "m2")
            .expect("m2 row keyed by memo identity");
        assert!(m2.tags.is_empty(), "untagged memos carry no tag facts");
    }

    #[test]
    fn statistics_rows_never_require_body_bytes() {
        let dir = tempdir().expect("tempdir");
        seed_memo(dir.path(), "m1", "alpha beta beta", &["work"]);
        seed_memo(dir.path(), "m2", "second memo body", &[]);
        let store = indexed_store(dir.path());

        // A legacy/incomplete projection may hold NULL bodies; statistics must stay servable
        // purely from materialized columns.
        let database = rusqlite::Connection::open(dir.path().join(".lomo-sqlite").join("store.db"))
            .expect("open store database");
        database
            .execute("UPDATE memo SET body = NULL", [])
            .expect("null out projection bodies");
        drop(database);

        let rows = store.memo_statistics_rows().expect("rows");
        assert_eq!(2, rows.len(), "statistics rows do not touch body bytes");
        assert!(
            rows.iter().all(|row| row.word_count > 0),
            "materialized counts survive absent bodies"
        );
    }

    #[test]
    fn projected_memos_batch_hydrates_a_candidate_set() {
        let dir = tempdir().expect("tempdir");
        seed_memo(dir.path(), "m1", "first body #work", &[]);
        seed_memo(dir.path(), "m2", "second body", &[]);
        seed_memo(dir.path(), "m3", "third body", &[]);
        let store = indexed_store(dir.path());

        let snapshots = store
            .get_projected_memos(&["m1".to_owned(), "m3".to_owned()])
            .expect("batch hydrate");
        assert_eq!(2, snapshots.len(), "one snapshot per requested memo");
        let m1 = snapshots
            .iter()
            .find(|snapshot| snapshot.summary.memo_id == "m1")
            .expect("m1 snapshot");
        assert_eq!(
            "first body #work", m1.body,
            "body travels with the snapshot"
        );
        assert_eq!(
            vec!["work".to_owned()],
            m1.summary.tags,
            "tag facts attach to batched summaries"
        );
        let m3 = snapshots
            .iter()
            .find(|snapshot| snapshot.summary.memo_id == "m3")
            .expect("m3 snapshot");
        assert_eq!("third body", m3.body, "every requested memo hydrates once");

        let missing = store
            .get_projected_memos(&["ghost".to_owned()])
            .expect("missing set");
        assert!(
            missing.is_empty(),
            "unknown memo ids yield no snapshot rows instead of inventing facts"
        );
    }

    #[test]
    fn body_preview_cuts_on_block_boundaries_and_never_inside_a_fence() {
        // Short body is preserved verbatim.
        assert_eq!("short", body_preview("short"));

        // Cut lands on the last blank line inside the budget: both blocks stay complete.
        let paragraphs = format!("first paragraph\n\n{}\n\nsecond paragraph", "x".repeat(400));
        let preview = body_preview(&paragraphs);
        assert_eq!(
            "first paragraph",
            preview.trim_end(),
            "cut must land on the blank-line block separator"
        );

        // A fence opened inside the budget is excluded whole instead of leaking unclosed code.
        let fenced = format!("intro line\n```rust\ncode {}\n", "y".repeat(300));
        let preview = body_preview(&fenced);
        assert!(
            !preview.contains("```"),
            "preview must not open a fence it cannot close: {preview:?}"
        );
        assert_eq!("intro line", preview.trim_end());
    }

    #[test]
    fn query_memos_projects_full_document_char_count_on_preview_rows() {
        let dir = tempdir().expect("tempdir");
        let body = "alpha beta beta";
        seed_memo(dir.path(), "m1", body, &[]);
        let store = indexed_store(dir.path());
        let page = store
            .query_memos(&all_query(), None, PageSize::new(10).expect("page size"))
            .expect("page");
        let summary = page
            .items
            .iter()
            .find(|item| item.memo_id == "m1")
            .expect("seeded memo");
        assert_eq!(
            count_characters(body),
            summary.char_count,
            "list rows must carry the rebuilt full-document character count, not preview length"
        );
        assert!(
            summary.body_preview.len() <= body.len(),
            "preview is a bounded view of the same document whose char_count is projected"
        );
    }

    #[test]
    fn count_words_matches_whitespace_run_contract() {
        assert_eq!(0, count_words("   \n\t"));
        assert_eq!(3, count_words("alpha beta\ngamma"));
        assert_eq!(2, count_words("中文 words"));
    }
}
