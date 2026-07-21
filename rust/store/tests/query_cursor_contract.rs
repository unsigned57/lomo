//! Behavior Contract (P3-03)
//!
//! Capability: `query_memos` applies tag/date/todo/attachment/url/pin/trash filters, stable sort
//! with unique tie-breaker, bounded pages, and `stale_cursor` on fingerprint/revision mismatch
//! (no offset full-scan fallback). Stats aggregates are readable.
//!
//! Scenarios:
//! - Given mixed memos, when filters are applied, then only matching rows return.
//! - Given a page cursor from query A, when used with query B or after a write that advances
//!   high-water revision, then `stale_cursor` is returned.
//! - Given multiple pages, when cursors are chained under a stable revision, then pages are
//!   disjoint and ordered.
//!
//! Observable outcomes: page items, `next_cursor`, stats, structured `stale_cursor` errors.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::too_many_lines,
    clippy::cognitive_complexity,
    reason = "contract tests fail closed with panics on missing facts; matrix is intentionally long"
)]
mod tests {
    use lomo_core::{ErrorCategory, OperationId, PageSize};
    use lomo_store::{MemoCommand, MemoCommandKind, MemoFilters, MemoQuery, PageCursor, Store};
    use tempfile::tempdir;

    fn create(store: &mut Store, id: &str, content: &str, tags: &[&str]) {
        let op = OperationId::parse(&format!("op-{id}")).expect("op");
        store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: op,
                    kind: MemoCommandKind::Create,
                    memo_id: id.into(),
                    expected_revision: 0,
                    expected_fingerprint: None,
                    content: Some(content.into()),
                    tags: tags.iter().map(|s| (*s).to_owned()).collect(),
                    pin: None,
                },
                None,
            )
            .expect("create");
    }

    #[test]
    fn filters_and_stats_and_stale_cursor() {
        let dir = tempdir().expect("tempdir");
        let mut store = Store::open(dir.path()).expect("open");
        create(
            &mut store,
            "a",
            "todo item\n- [ ] work\nhttps://example.com",
            &["work"],
        );
        create(&mut store, "b", "plain note", &["life"]);
        create(&mut store, "c", "another", &["work"]);

        let pin_op = OperationId::parse("op-pin-b").expect("op");
        store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: pin_op,
                    kind: MemoCommandKind::Pin,
                    memo_id: "b".into(),
                    expected_revision: 1,
                    expected_fingerprint: None,
                    content: None,
                    tags: vec![],
                    pin: Some(true),
                },
                None,
            )
            .expect("pin");

        let todo_page = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters {
                        has_todo: Some(true),
                        ..MemoFilters::default()
                    },
                },
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("todo filter");
        assert_eq!(todo_page.items.len(), 1);
        assert_eq!(
            todo_page.items.first().map(|m| m.memo_id.as_str()),
            Some("a")
        );

        let tag_page = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters {
                        tag: Some("work".into()),
                        ..MemoFilters::default()
                    },
                },
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("tag filter");
        assert_eq!(tag_page.items.len(), 2);

        let pin_page = store
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
            .expect("pin filter");
        assert_eq!(pin_page.items.len(), 1);
        assert_eq!(
            pin_page.items.first().map(|m| m.memo_id.as_str()),
            Some("b")
        );

        let url_page = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters {
                        has_url: Some(true),
                        ..MemoFilters::default()
                    },
                },
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("url filter");
        assert_eq!(url_page.items.len(), 1);

        let stats = store.stats().expect("stats");
        assert_eq!(stats.memo_count, 3);
        assert_eq!(stats.pinned_count, 1);

        let page1 = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters::default(),
                },
                None,
                PageSize::new(2).expect("page"),
            )
            .expect("page1");
        assert_eq!(page1.items.len(), 2);
        let cursor = page1.next_cursor.expect("next cursor");
        let page2 = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters::default(),
                },
                Some(&cursor),
                PageSize::new(2).expect("page"),
            )
            .expect("page2");
        assert_eq!(page2.items.len(), 1);
        let ids: Vec<_> = page1
            .items
            .iter()
            .chain(page2.items.iter())
            .map(|m| m.memo_id.as_str())
            .collect();
        assert_eq!(ids.len(), 3);
        assert_eq!(
            ids.iter().collect::<std::collections::BTreeSet<_>>().len(),
            3
        );

        let err = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters {
                        pinned_only: true,
                        ..MemoFilters::default()
                    },
                },
                Some(&cursor),
                PageSize::new(2).expect("page"),
            )
            .expect_err("stale fingerprint");
        assert_eq!(err.category(), ErrorCategory::Validation);
        assert_eq!(err.code(), "stale_cursor");

        create(&mut store, "d", "new write", &[]);
        let err = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters::default(),
                },
                Some(&cursor),
                PageSize::new(2).expect("page"),
            )
            .expect_err("stale revision");
        assert_eq!(err.code(), "stale_cursor");

        let err = PageCursor::decode("not-json").expect_err("bad cursor");
        assert_eq!(err.code(), "invalid_page_cursor");

        // Encode/decode round-trip and tokenizer mismatch fail closed.
        let encoded = cursor.encode().expect("encode");
        let decoded = PageCursor::decode(&encoded).expect("decode");
        assert_eq!(decoded.high_water_revision, cursor.high_water_revision);
        let mut bad_tok = decoded;
        bad_tok.tokenizer_version = 0;
        let err = bad_tok
            .validate_against(&cursor.query_fingerprint, cursor.high_water_revision)
            .expect_err("tokenizer mismatch");
        assert_eq!(err.code(), "stale_cursor");

        let plan = lomo_store::query_plan("hello world").expect("plan");
        let fp = lomo_store::fingerprint_plan(&plan, "filters");
        assert!(!fp.is_empty());
        assert_ne!(fp, cursor.query_fingerprint);
    }

    #[test]
    fn query_and_get_memo_project_tags_and_image_urls() {
        let dir = tempdir().expect("tempdir");
        let mut store = Store::open(dir.path()).expect("open");
        // Content-derived #tag merges with explicit command tags; markdown image becomes image_urls.
        create(
            &mut store,
            "img1",
            "see #travel\n\n![cover](images/cover.png)\n",
            &["explicit"],
        );

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
        let item = page.items.first().expect("one memo");
        assert!(
            item.tags.iter().any(|t| t == "travel"),
            "content tag missing: {:?}",
            item.tags
        );
        assert!(
            item.tags.iter().any(|t| t == "explicit"),
            "command tag missing: {:?}",
            item.tags
        );
        assert_eq!(item.image_urls, vec!["images/cover.png".to_owned()]);
        assert!(item.has_attachment);

        let snap = store.get_memo("img1").expect("get").expect("present");
        assert!(snap.summary.tags.iter().any(|t| t == "travel"));
        assert_eq!(snap.summary.image_urls, vec!["images/cover.png".to_owned()]);
    }
}
