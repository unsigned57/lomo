// Dependency upgrade — Test Change Justification:
// Reason category: mechanical migration.
// Old behavior/assertion being replaced: old dependency API spelling and boolean empty checks.
// Why old assertion is no longer correct: updated APIs and Rust 1.99 diagnostics require explicit
// byte encoding, image geometry types and equality assertions that print failing collections.
// Coverage preserved by: the same scenarios, input bytes, expected values, ordering and failures.
// Why this is not fitting the test to the implementation: observable behavior and golden vectors
// are unchanged; only equivalent API/assertion syntax and fixture construction are migrated.
//
// Image presentation regression: Given Markdown order z/remote/a and an audio attachment,
// when page summaries are read, then image URLs retain z/remote/a and omit audio.
// TDD proof: RED query_cursor_contract returned [images/a.png, images/z.png];
// GREEN cargo test -p lomo-store --test query_cursor_contract --locked.
//
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
//! - Given created/updated timestamps and each direction, when the typed sort changes, then all
//!   four orderings are honored and the cursor remains coupled to that sort identity.
//! - Given a tag path, exact matching returns only that tag while subtree matching also returns
//!   slash-delimited descendants and never prefix siblings.
//! - Given a bounded query, when a page is loaded from head, after a cursor, at a memo identity,
//!   or exclusively before a cursor, then `items_before`/`items_after` are the ranks around that
//!   window and `AtMemo` missing identity falls back to head.
//!
//! Observable outcomes: page items, `next_cursor`, `prev_cursor`, rank fields, stats, structured
//! `stale_cursor` errors.

mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::too_many_lines,
    clippy::cognitive_complexity,
    reason = "contract tests fail closed with panics on missing facts; matrix is intentionally long"
)]
mod tests {
    use super::support::{indexed_store, publish_pin, seed_memo};
    use lomo_core::{ErrorCategory, PageSize};
    use lomo_store::{
        MemoFilters, MemoQuery, MemoQueryBoundary, MemoQueryStart, MemoSort, MemoSortField,
        PageCursor, SortDirection, TagSelectionMode,
    };
    use tempfile::tempdir;

    #[test]
    fn memo_images_keep_markdown_order_and_remote_urls() {
        let dir = tempdir().expect("tempdir");
        seed_memo(
            dir.path(),
            "images",
            "![z](images/z.png)\n![remote](https://example.com/photo)\n![a](images/a.png)\n![voice](voice/a.m4a)\n![again](images/z.png)",
            &[],
        );
        let store = indexed_store(dir.path());
        let page = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters::default(),
                    sort: MemoSort::default(),
                },
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("images page");
        assert_eq!(
            page.items.first().expect("image memo").image_urls,
            vec!["images/z.png", "https://example.com/photo", "images/a.png",]
        );
    }

    #[test]
    fn filters_and_stats_and_stale_cursor() {
        let dir = tempdir().expect("tempdir");
        seed_memo(
            dir.path(),
            "a",
            "todo item\n- [ ] work\nhttps://example.com",
            &["work"],
        );
        seed_memo(dir.path(), "b", "plain note", &["life"]);
        seed_memo(dir.path(), "c", "another", &["work"]);
        let mut store = indexed_store(dir.path());
        publish_pin(&mut store, "b", "op-pin-b");

        let todo_page = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters {
                        has_todo: Some(true),
                        ..MemoFilters::default()
                    },
                    sort: MemoSort::default(),
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
                    sort: MemoSort::default(),
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
                    sort: MemoSort::default(),
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
                    sort: MemoSort::default(),
                },
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("url filter");
        assert_eq!(url_page.items.len(), 1);

        let stats = store.stats().expect("stats");
        assert_eq!(stats.memo_count, 3);
        assert_eq!(stats.pinned_count, 1);
        let sidebar = store.sidebar_projection().expect("sidebar projection");
        assert_eq!(sidebar.schema_version, 1);
        assert_eq!(sidebar.memo_count, 3);
        assert_eq!(
            sidebar
                .date_counts
                .iter()
                .map(|bucket| bucket.count)
                .sum::<i64>(),
            3
        );

        let page1 = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters::default(),
                    sort: MemoSort::default(),
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
                    sort: MemoSort::default(),
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
                    sort: MemoSort::default(),
                },
                Some(&cursor),
                PageSize::new(2).expect("page"),
            )
            .expect_err("stale fingerprint");
        assert_eq!(err.category(), ErrorCategory::Validation);
        assert_eq!(err.code(), "stale_cursor");

        publish_pin(&mut store, "c", "op-pin-c-stale");
        let err = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters::default(),
                    sort: MemoSort::default(),
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
        assert_ne!(fp, "");
        assert_ne!(fp, cursor.query_fingerprint);
    }

    #[test]
    fn ordering_boundary_excludes_new_rows_ahead_of_captured_head() {
        let dir = tempdir().expect("tempdir");
        seed_memo(dir.path(), "a", "first", &[]);
        seed_memo(dir.path(), "b", "second", &[]);
        let store = indexed_store(dir.path());

        let initial = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters::default(),
                    sort: MemoSort::default(),
                },
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("initial page");
        let anchor = initial.items.first().expect("head");
        let boundary = MemoQueryBoundary {
            sort_pinned: anchor.is_pinned,
            sort_primary_ms: anchor.created_at_ms,
            sort_created_at_ms: anchor.created_at_ms,
            sort_memo_id: anchor.memo_id.clone(),
        };
        drop(store);

        seed_memo(dir.path(), "new-head", "new", &[]);
        let mut store = indexed_store(dir.path());
        publish_pin(&mut store, "new-head", "op-pin-new-head");

        let bounded = store
            .query_memos_with_boundary(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters::default(),
                    sort: MemoSort::default(),
                },
                Some(&boundary),
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("bounded page");
        assert!(!bounded.items.iter().any(|memo| memo.memo_id == "new-head"));
        assert!(
            bounded
                .items
                .iter()
                .any(|memo| memo.memo_id == anchor.memo_id)
        );
    }

    #[test]
    fn query_and_get_memo_project_tags_and_image_urls() {
        let dir = tempdir().expect("tempdir");
        std::fs::create_dir_all(dir.path().join("images")).expect("images dir");
        std::fs::write(dir.path().join("images/cover.png"), b"x").expect("cover");
        seed_memo(
            dir.path(),
            "img1",
            "see #travel\n\n![cover](images/cover.png)\n",
            &["explicit"],
        );
        let store = indexed_store(dir.path());

        let page = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters::default(),
                    sort: MemoSort::default(),
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

    #[test]
    fn tag_selection_distinguishes_exact_from_subtree() {
        let dir = tempdir().expect("tempdir");
        seed_memo(dir.path(), "root", "root", &["work"]);
        seed_memo(dir.path(), "child", "child", &["work/project"]);
        seed_memo(dir.path(), "sibling", "sibling", &["workspace"]);
        let store = indexed_store(dir.path());

        let exact = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters {
                        tag: Some("work".into()),
                        tag_selection: TagSelectionMode::Exact,
                        ..MemoFilters::default()
                    },
                    sort: MemoSort::default(),
                },
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("exact tag");
        assert_eq!(
            exact
                .items
                .iter()
                .map(|item| item.memo_id.as_str())
                .collect::<Vec<_>>(),
            vec!["root"]
        );

        let subtree = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters {
                        tag: Some("work".into()),
                        tag_selection: TagSelectionMode::Subtree,
                        ..MemoFilters::default()
                    },
                    sort: MemoSort::default(),
                },
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("subtree tag");
        let ids = subtree
            .items
            .iter()
            .map(|item| item.memo_id.as_str())
            .collect::<std::collections::BTreeSet<_>>();
        assert_eq!(ids, std::collections::BTreeSet::from(["child", "root"]));
    }

    #[test]
    fn created_and_updated_sort_directions_are_query_and_cursor_identity() {
        let dir = tempdir().expect("tempdir");
        seed_memo(dir.path(), "a", "first", &[]);
        seed_memo(dir.path(), "b", "second", &[]);
        let older = std::time::UNIX_EPOCH + std::time::Duration::from_secs(1_700_000_000);
        let newer = std::time::UNIX_EPOCH + std::time::Duration::from_secs(1_700_000_001);
        std::fs::File::open(dir.path().join("memos/a.md"))
            .expect("open a")
            .set_modified(older)
            .expect("mtime a");
        std::fs::File::open(dir.path().join("memos/b.md"))
            .expect("open b")
            .set_modified(newer)
            .expect("mtime b");
        let store = indexed_store(dir.path());

        let cases = [
            (
                MemoSortField::CreatedAt,
                SortDirection::Ascending,
                vec!["a", "b"],
            ),
            (
                MemoSortField::CreatedAt,
                SortDirection::Descending,
                vec!["b", "a"],
            ),
            (
                MemoSortField::UpdatedAt,
                SortDirection::Ascending,
                vec!["a", "b"],
            ),
            (
                MemoSortField::UpdatedAt,
                SortDirection::Descending,
                vec!["b", "a"],
            ),
        ];
        for (field, direction, expected) in cases {
            let page = store
                .query_memos(
                    &MemoQuery {
                        search_text: None,
                        filters: MemoFilters::default(),
                        sort: MemoSort {
                            field,
                            direction,
                            ..MemoSort::default()
                        },
                    },
                    None,
                    PageSize::new(1).expect("page"),
                )
                .expect("sorted first page");
            assert_eq!(page.items.len(), 1);
            assert_eq!(
                page.items.first().map(|memo| memo.memo_id.as_str()),
                expected.first().copied()
            );
            let cursor = page.next_cursor.expect("second page cursor");
            let second = store
                .query_memos(
                    &MemoQuery {
                        search_text: None,
                        filters: MemoFilters::default(),
                        sort: MemoSort {
                            field,
                            direction,
                            ..MemoSort::default()
                        },
                    },
                    Some(&cursor),
                    PageSize::new(1).expect("page"),
                )
                .expect("sorted second page");
            assert_eq!(
                second.items.first().map(|memo| memo.memo_id.as_str()),
                expected.get(1).copied()
            );

            let opposite = match direction {
                SortDirection::Ascending => SortDirection::Descending,
                SortDirection::Descending => SortDirection::Ascending,
            };
            let error = store
                .query_memos(
                    &MemoQuery {
                        search_text: None,
                        filters: MemoFilters::default(),
                        sort: MemoSort {
                            seed: 0,
                            field,
                            direction: opposite,
                            ..MemoSort::default()
                        },
                    },
                    Some(&cursor),
                    PageSize::new(1).expect("page"),
                )
                .expect_err("cursor must be coupled to sort direction");
            assert_eq!(error.code(), "stale_cursor");
        }
    }

    #[test]
    fn positional_pages_rank_identity_start_and_exclusive_before() {
        let dir = tempdir().expect("tempdir");
        for id in ["m1", "m2", "m3", "m4", "m5"] {
            seed_memo(dir.path(), id, id, &[]);
        }
        let store = indexed_store(dir.path());
        let query = MemoQuery {
            search_text: None,
            filters: MemoFilters::default(),
            sort: MemoSort::default(),
        };
        let page_size = PageSize::new(2).expect("page");
        let ordered: Vec<String> = store
            .query_memos(&query, None, PageSize::new(10).expect("all"))
            .expect("full order")
            .items
            .into_iter()
            .map(|memo| memo.memo_id)
            .collect();
        assert_eq!(ordered.len(), 5);

        let head = store
            .query_memos(&query, None, page_size)
            .expect("head page");
        assert_eq!(head.items.len(), 2);
        assert_eq!(head.items_before, 0);
        assert_eq!(head.items_after, 3);
        assert!(head.prev_cursor.is_none());
        assert!(head.next_cursor.is_some());
        assert_eq!(
            head.items
                .iter()
                .map(|memo| memo.memo_id.as_str())
                .collect::<Vec<_>>(),
            ordered
                .iter()
                .take(2)
                .map(String::as_str)
                .collect::<Vec<_>>()
        );

        let page2 = store
            .query_memos(&query, head.next_cursor.as_ref(), page_size)
            .expect("second page");
        assert_eq!(page2.items_before, 2);
        assert_eq!(page2.items_after, 1);
        assert!(page2.prev_cursor.is_some());
        assert_eq!(
            page2
                .items
                .iter()
                .map(|memo| memo.memo_id.as_str())
                .collect::<Vec<_>>(),
            ordered
                .iter()
                .skip(2)
                .take(2)
                .map(String::as_str)
                .collect::<Vec<_>>()
        );

        let start_id = ordered.get(2).expect("middle identity");
        let from_identity = store
            .query_memos_starting_at(&query, None, MemoQueryStart::AtMemo(start_id), page_size)
            .expect("identity start");
        assert_eq!(
            from_identity
                .items
                .first()
                .map(|memo| memo.memo_id.as_str()),
            Some(start_id.as_str())
        );
        assert_eq!(from_identity.items_before, 2);
        assert_eq!(from_identity.items_after, 1);

        let missing = store
            .query_memos_starting_at(&query, None, MemoQueryStart::AtMemo("missing"), page_size)
            .expect("missing identity");
        assert_eq!(missing.items_before, 0);
        assert_eq!(
            missing.items.first().map(|memo| memo.memo_id.as_str()),
            ordered.first().map(String::as_str)
        );

        let prepended = store
            .query_memos_starting_at(
                &query,
                None,
                MemoQueryStart::Before(page2.prev_cursor.as_ref().expect("prev cursor")),
                page_size,
            )
            .expect("exclusive before");
        assert_eq!(prepended.items_before, 0);
        assert_eq!(
            prepended
                .items
                .iter()
                .map(|memo| memo.memo_id.as_str())
                .collect::<Vec<_>>(),
            ordered
                .iter()
                .take(2)
                .map(String::as_str)
                .collect::<Vec<_>>()
        );
        assert_eq!(
            prepended.items.last().map(|memo| memo.memo_id.as_str()),
            head.items.last().map(|memo| memo.memo_id.as_str())
        );
    }

    #[test]
    fn gallery_page_uses_partial_index_filters_orders_pages_and_fails_closed() {
        let dir = tempdir().expect("tempdir");
        seed_memo(dir.path(), "plain", "just text", &[]);
        seed_memo(dir.path(), "audio", "![voice](voice/clip.m4a)", &[]);
        seed_memo(dir.path(), "g1", "![img](images/g1.png)", &[]);
        seed_memo(dir.path(), "g2", "![img](images/g2.png)", &[]);
        seed_memo(dir.path(), "g3", "![img](images/g3.png)", &[]);
        seed_memo(
            dir.path(),
            "g4",
            "![z](images/z.png)\n![remote](https://cdn.example.com/r.png)\n![a](images/a.png)\n![voice](voice/skip.m4a)",
            &[],
        );
        seed_memo(dir.path(), "trashed-img", "![gone](images/gone.png)", &[]);

        let mut store = indexed_store(dir.path());
        publish_pin(&mut store, "g2", "op-gallery-pin-g2");
        super::support::publish_trash(&mut store, "trashed-img", "op-gallery-trash");

        let db_path = store.open_info().database_path.clone();
        let explain_conn = rusqlite::Connection::open(&db_path).expect("open explain conn");
        for sql in [
            "SELECT m.memo_id FROM memo m INDEXED BY idx_memo_gallery \
             WHERE m.is_trashed = 0 AND m.has_attachment = 1 AND m.image_urls_json <> '[]' \
             ORDER BY m.is_pinned DESC, m.created_at_ms DESC, m.memo_id DESC LIMIT 21",
            "SELECT m.memo_id FROM memo m INDEXED BY idx_memo_gallery \
             WHERE m.is_trashed = 0 AND m.has_attachment = 1 AND m.image_urls_json <> '[]' \
             AND (m.is_pinned, m.created_at_ms, m.memo_id) < (0, 1000, 'm1') \
             ORDER BY m.is_pinned DESC, m.created_at_ms DESC, m.memo_id DESC LIMIT 21",
            "SELECT m.memo_id FROM memo m INDEXED BY idx_memo_gallery \
             WHERE m.is_trashed = 0 AND m.has_attachment = 1 AND m.image_urls_json <> '[]' \
             AND (m.is_pinned, m.created_at_ms, m.memo_id) > (0, 1000, 'm1') \
             ORDER BY m.is_pinned ASC, m.created_at_ms ASC, m.memo_id ASC LIMIT 21",
            "SELECT EXISTS(SELECT 1 FROM memo m INDEXED BY idx_memo_gallery \
             WHERE m.is_trashed = 0 AND m.has_attachment = 1 AND m.image_urls_json <> '[]' \
             AND (m.is_pinned, m.created_at_ms, m.memo_id) > (0, 1000, 'm1'))",
        ] {
            let mut stmt = explain_conn
                .prepare(&format!("EXPLAIN QUERY PLAN {sql}"))
                .expect("prepare explain");
            let plan = stmt
                .query_map([], |row| row.get::<_, String>(3))
                .expect("query explain")
                .map(|r| r.expect("detail"))
                .collect::<Vec<_>>()
                .join("\n");
            assert!(
                plan.contains("idx_memo_gallery"),
                "expected idx_memo_gallery in plan:\n{plan}"
            );
            assert!(
                !plan.contains("TEMP B-TREE"),
                "unexpected TEMP B-TREE in gallery plan:\n{plan}"
            );
        }
        drop(explain_conn);

        let all = store
            .query_gallery_page(MemoQueryStart::Head, PageSize::new(10).expect("page"))
            .expect("full gallery");
        assert_eq!(all.items.len(), 4, "must exclude plain, audio, and trashed");
        assert!(all.items[0].is_pinned && all.items[0].memo_id == "g2");
        assert!(all.items.iter().all(|row| row.memo_id != "plain"));
        assert!(all.items.iter().all(|row| row.memo_id != "audio"));
        assert!(all.items.iter().all(|row| row.memo_id != "trashed-img"));
        let g4 = all
            .items
            .iter()
            .find(|row| row.memo_id == "g4")
            .expect("g4 present");
        assert_eq!(
            g4.image_urls,
            vec![
                "images/z.png",
                "https://cdn.example.com/r.png",
                "images/a.png"
            ]
        );

        let ordered_ids: Vec<String> = all.items.iter().map(|row| row.memo_id.clone()).collect();
        let page1 = store
            .query_gallery_page(MemoQueryStart::Head, PageSize::new(2).expect("p1"))
            .expect("page 1");
        assert!(page1.prev_cursor.is_none());
        let cursor1 = page1.next_cursor.clone().expect("next_cursor on page 1");
        assert_eq!(
            page1
                .items
                .iter()
                .map(|row| row.memo_id.as_str())
                .collect::<Vec<_>>(),
            vec![ordered_ids[0].as_str(), ordered_ids[1].as_str()]
        );

        let page2 = store
            .query_gallery_page(
                MemoQueryStart::After(&cursor1),
                PageSize::new(2).expect("p2"),
            )
            .expect("page 2");
        assert!(page2.next_cursor.is_none());
        let cursor2_prev = page2.prev_cursor.clone().expect("prev_cursor on page 2");
        assert_eq!(
            page2
                .items
                .iter()
                .map(|row| row.memo_id.as_str())
                .collect::<Vec<_>>(),
            vec![ordered_ids[2].as_str(), ordered_ids[3].as_str()]
        );

        let back = store
            .query_gallery_page(
                MemoQueryStart::Before(&cursor2_prev),
                PageSize::new(2).expect("back"),
            )
            .expect("back page");
        assert!(back.prev_cursor.is_none());
        assert!(back.next_cursor.is_some());
        assert_eq!(
            back.items
                .iter()
                .map(|row| row.memo_id.as_str())
                .collect::<Vec<_>>(),
            vec![ordered_ids[0].as_str(), ordered_ids[1].as_str()]
        );

        let anchored = store
            .query_gallery_page(
                MemoQueryStart::AtMemo(&ordered_ids[1]),
                PageSize::new(2).expect("anchor"),
            )
            .expect("anchored");
        assert!(anchored.prev_cursor.is_some());
        assert!(anchored.next_cursor.is_some());
        assert_eq!(
            anchored
                .items
                .iter()
                .map(|row| row.memo_id.as_str())
                .collect::<Vec<_>>(),
            vec![ordered_ids[1].as_str(), ordered_ids[2].as_str()]
        );

        let fallback = store
            .query_gallery_page(
                MemoQueryStart::AtMemo("plain"),
                PageSize::new(2).expect("fallback"),
            )
            .expect("filtered anchor falls back to head");
        assert!(fallback.prev_cursor.is_none());
        assert_eq!(
            fallback
                .items
                .iter()
                .map(|row| row.memo_id.as_str())
                .collect::<Vec<_>>(),
            vec![ordered_ids[0].as_str(), ordered_ids[1].as_str()]
        );

        let pool = lomo_store::StoreReaderPool::new(
            dir.path().to_owned(),
            lomo_store::ReaderPoolOptions::new(2, std::time::Duration::ZERO).expect("pool opts"),
        );
        let reader_lease = pool.checkout().expect("checkout reader");
        let reader_page = reader_lease
            .reader()
            .expect("reader")
            .query_gallery_page(MemoQueryStart::Head, PageSize::new(4).expect("rp"))
            .expect("reader gallery");
        assert_eq!(
            reader_page
                .items
                .iter()
                .map(|row| row.memo_id.as_str())
                .collect::<Vec<_>>(),
            ordered_ids.iter().map(String::as_str).collect::<Vec<_>>()
        );
        drop(reader_lease);

        publish_pin(&mut store, "g3", "op-gallery-pin-g3");
        let stale = store
            .query_gallery_page(
                MemoQueryStart::After(&cursor1),
                PageSize::new(2).expect("stale"),
            )
            .expect_err("mutation must stale gallery cursor");
        assert_eq!(stale.code(), "stale_cursor");
        drop(store);

        let corrupt_conn = rusqlite::Connection::open(&db_path).expect("corrupt conn");
        corrupt_conn
            .execute(
                "UPDATE memo SET image_urls_json = '{not-json' WHERE memo_id = 'g4'",
                [],
            )
            .expect("corrupt row");
        drop(corrupt_conn);

        let reopened = lomo_store::Store::open(dir.path()).expect("reopen");
        let err = reopened
            .query_gallery_page(MemoQueryStart::Head, PageSize::new(10).expect("corrupt"))
            .expect_err("corrupt image_urls_json must fail closed");
        assert_eq!(err.category(), ErrorCategory::Corruption);
        assert_eq!(err.code(), "invalid_image_urls_projection");
    }
}
