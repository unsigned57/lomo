//! Behavior Contract: review pages belong to the store ordering owner (P1).
//! Given months, pins, filters and a seed, pages use month-first random order or global random
//! order, retain that order across bounded cursors, and reject cursors for another seed/filter.
//! TDD: `cargo test -p lomo-store --test review_query_contract --locked`; RED pinned dec-a preceded
//! January despite recent-month order; GREEN with deterministic seeded review keys.
//! Excludes: Android presentation, session persistence, and statistical randomness certification.
mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract assertions fail closed on missing query and fixture facts"
)]
mod tests {
    use super::support::{indexed_store, publish_pin, seed_memo};
    use lomo_core::PageSize;
    use lomo_store::{MemoFilters, MemoQuery, MemoSort, MemoSortField, SortDirection, Store};
    use std::error::Error;
    use tempfile::TempDir;

    fn fixture() -> Result<(TempDir, Store), Box<dyn Error>> {
        let dir = tempfile::tempdir()?;
        for (id, seconds) in [
            ("jan-a", 1_767_225_600),
            ("jan-b", 1_767_312_000),
            ("jan-c", 1_767_398_400),
            ("dec-a", 1_764_547_200),
            ("dec-b", 1_764_633_600),
        ] {
            let body = if id == "jan-a" || id == "dec-b" {
                "- [ ] task\n![photo](photo.png)\nhttps://example.com"
            } else {
                "review note"
            };
            seed_memo(dir.path(), id, body, &[]);
            std::fs::File::open(dir.path().join(format!("memos/{id}.md")))?
                .set_modified(std::time::UNIX_EPOCH + std::time::Duration::from_secs(seconds))?;
        }
        let mut store = indexed_store(dir.path());
        publish_pin(&mut store, "dec-a", "review-pin");
        Ok((dir, store))
    }

    fn query(field: MemoSortField, direction: SortDirection, seed: i64) -> MemoQuery {
        MemoQuery {
            search_text: None,
            filters: MemoFilters::default(),
            sort: MemoSort {
                field,
                direction,
                seed,
                time_zone: "UTC".to_owned(),
            },
        }
    }

    #[test]
    fn month_groups_follow_the_explicit_calendar_zone_and_reject_invalid_zones() {
        let dir = tempfile::tempdir().expect("fixture directory");
        for (id, instant) in [
            ("jan-a", "2026-01-01T00:00:00Z"),
            ("dec-a", "2025-12-31T18:00:00Z"),
            ("dec-b", "2025-12-01T00:00:00Z"),
        ] {
            seed_memo(dir.path(), id, "review", &[]);
            let timestamp = instant.parse::<jiff::Timestamp>().expect("timestamp");
            std::fs::File::open(dir.path().join(format!("memos/{id}.md")))
                .expect("memo file")
                .set_modified(std::time::SystemTime::from(timestamp))
                .expect("mtime");
        }
        let store = indexed_store(dir.path());
        let mut spec = query(MemoSortField::ReviewMonth, SortDirection::Ascending, 7);
        spec.sort.time_zone = "Asia/Shanghai".to_owned();
        let page = store
            .query_memos(&spec, None, PageSize::new(2).expect("page size"))
            .expect("query");
        assert_eq!(
            page.items
                .into_iter()
                .map(|memo| memo.memo_id)
                .collect::<Vec<_>>(),
            ["dec-b", "dec-a"]
        );
        spec.sort.time_zone = "+08:00".to_owned();
        let offset_page = store
            .query_memos(&spec, None, PageSize::new(2).expect("page size"))
            .expect("fixed-offset query");
        assert_eq!(
            offset_page
                .items
                .into_iter()
                .map(|memo| memo.memo_id)
                .collect::<Vec<_>>(),
            ["dec-b", "dec-a"]
        );
        let cursor = page.next_cursor.expect("continuation");
        spec.sort.time_zone = "UTC".to_owned();
        let error = store
            .query_memos(&spec, Some(&cursor), PageSize::new(2).expect("page size"))
            .expect_err("zone change invalidates cursor");
        assert_eq!(error.code(), "stale_cursor");
        spec.sort.time_zone = "invalid/zone".to_owned();
        let error = store
            .query_memos(&spec, None, PageSize::new(2).expect("page size"))
            .expect_err("invalid zone");
        assert_eq!(error.code(), "invalid_review_time_zone");
    }

    #[test]
    fn recent_months_keep_seeded_random_order_within_each_month_across_pages() {
        let (_dir, store) = fixture().expect("fixture");
        let query = query(MemoSortField::ReviewMonth, SortDirection::Descending, 7);
        let mut ids = Vec::new();
        let mut cursor = None;
        loop {
            let page = store
                .query_memos(
                    &query,
                    cursor.as_ref(),
                    PageSize::new(2).expect("valid page size"),
                )
                .expect("query");
            ids.extend(page.items.into_iter().map(|item| item.memo_id));
            cursor = page.next_cursor;
            if cursor.is_none() {
                break;
            }
        }
        assert_eq!(ids, ["jan-c", "jan-b", "jan-a", "dec-b", "dec-a"]);
    }

    #[test]
    fn past_months_progress_oldest_first_and_do_not_prioritize_pins() {
        let (_dir, store) = fixture().expect("fixture");
        let page = store
            .query_memos(
                &query(MemoSortField::ReviewMonth, SortDirection::Ascending, 7),
                None,
                PageSize::new(20).expect("valid page size"),
            )
            .expect("query");
        assert_eq!(
            page.items
                .into_iter()
                .map(|memo| memo.memo_id)
                .collect::<Vec<_>>(),
            ["dec-a", "dec-b", "jan-a", "jan-b", "jan-c"]
        );
    }

    #[test]
    fn global_random_is_repeatable_and_crosses_month_boundaries() {
        let (_dir, store) = fixture().expect("fixture");
        let spec = query(MemoSortField::ReviewRandom, SortDirection::Ascending, 7);
        let first = store
            .query_memos(&spec, None, PageSize::new(20).expect("valid page size"))
            .expect("query");
        let again = store
            .query_memos(&spec, None, PageSize::new(20).expect("valid page size"))
            .expect("query");
        assert_eq!(first.items, again.items);
        let expected = ["dec-a", "jan-a", "jan-b", "dec-b", "jan-c"];
        assert_eq!(
            first
                .items
                .into_iter()
                .map(|memo| memo.memo_id)
                .collect::<Vec<_>>(),
            expected
        );
    }

    #[test]
    fn changed_seed_or_filter_rejects_the_previous_cursor() {
        let (_dir, store) = fixture().expect("fixture");
        let spec = query(MemoSortField::ReviewRandom, SortDirection::Ascending, 7);
        let page = store
            .query_memos(&spec, None, PageSize::new(2).expect("valid page size"))
            .expect("query");
        let other_seed = query(MemoSortField::ReviewRandom, SortDirection::Ascending, 8);
        assert_eq!(
            store
                .query_memos(
                    &other_seed,
                    page.next_cursor.as_ref(),
                    PageSize::new(2).expect("valid page size")
                )
                .err()
                .map(|error| error.code().to_owned()),
            Some("stale_cursor".to_owned())
        );
        let other_filter = MemoQuery {
            filters: MemoFilters {
                has_attachment: Some(true),
                ..MemoFilters::default()
            },
            ..spec
        };
        assert_eq!(
            store
                .query_memos(
                    &other_filter,
                    page.next_cursor.as_ref(),
                    PageSize::new(2).expect("valid page size")
                )
                .err()
                .map(|error| error.code().to_owned()),
            Some("stale_cursor".to_owned())
        );
    }

    #[test]
    fn review_date_attachment_todo_and_url_filters_compose_before_sorting() {
        let (_dir, store) = fixture().expect("fixture");
        let spec = MemoQuery {
            filters: MemoFilters {
                date_from_inclusive_ms: Some(1_767_225_600_000),
                date_until_exclusive_ms: Some(1_767_484_800_000),
                has_attachment: Some(true),
                has_todo: Some(true),
                has_url: Some(true),
                ..MemoFilters::default()
            },
            ..query(MemoSortField::ReviewRandom, SortDirection::Ascending, 7)
        };
        let page = store
            .query_memos(&spec, None, PageSize::new(20).expect("valid page size"))
            .expect("query");
        assert_eq!(
            page.items
                .into_iter()
                .map(|memo| memo.memo_id)
                .collect::<Vec<_>>(),
            ["jan-a"]
        );
    }
}
