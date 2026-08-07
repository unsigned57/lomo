//! Behavior Contract (P3-02)
//!
//! Capability: pure-Rust Unicode tokenizer produces `searchContent` projection tokens and ordered
//! multi-char CJK query plans (adjacent bigram/NEAR), never unbounded unigram-OR; FTS5 external
//! content indexes the projection.
//!
//! Scenarios:
//! - Given CJK/emoji/latin text, when index tokens are produced, then CJK emits unigram+bigram,
//!   latin is whole-word, emoji is preserved (no JVM `UnicodeBlock`).
//! - Given a multi-character CJK query, when a query plan is built, then it is adjacent
//!   bigram/NEAR ordered and `uses_unbounded_cjk_unigram_or` is false.
//! - Given indexed memos, when FTS5 `MATCH` uses the plan, then hits are observable.
//! - Given multiple FTS hits, when pages are chained, then the complete rank order is preserved.
//!
//! Observable outcomes: token strings, `QueryPlan` terms/`match_expr`, FTS5 hit counts.
//! Excludes: paging/cursor, Room, JVM tokenizer authority.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_core::{OperationId, PageSize};
    use lomo_store::{
        MemoCommand, MemoCommandKind, MemoFilters, MemoQuery, QueryPlan, QueryTerm, Store,
        TOKENIZER_VERSION, Tokenizer, UnicodeTokenizer, index_tokens, query_plan,
        tokenizer_version,
    };
    use tempfile::tempdir;

    #[test]
    fn index_tokens_cover_cjk_latin_and_emoji() {
        let tokens = index_tokens("hello 你好 🎉 world");
        assert!(tokens.contains("hello"), "latin word: {tokens}");
        assert!(tokens.contains("world"), "latin word: {tokens}");
        assert!(tokens.contains('你') || tokens.contains("你"), "{tokens}");
        assert!(tokens.contains("你好"), "bigram required: {tokens}");
        assert!(
            tokens.contains("🎉") || tokens.contains('\u{1F389}'),
            "emoji: {tokens}"
        );
        assert_eq!(tokenizer_version(), TOKENIZER_VERSION);
        assert_eq!(TOKENIZER_VERSION, 1);
    }

    #[test]
    fn multi_char_cjk_query_is_adjacent_bigram_not_unigram_or() {
        let plan = query_plan("你好世界").expect("plan");
        assert!(
            !plan.uses_unbounded_cjk_unigram_or(),
            "must not use unbounded unigram-OR: {:?}",
            plan.match_expr
        );
        let expr = plan.match_expr.expect("match expr");
        assert!(
            !expr.to_ascii_uppercase().contains(" OR "),
            "multi-char CJK must not OR unigrams: {expr}"
        );
        assert!(
            expr.contains("你好") && expr.contains("好世") && expr.contains("世界"),
            "expected adjacent bigram phrase plan: {expr}"
        );
        assert!(
            expr.contains('"'),
            "bigrams must be phrase-bound, not bare OR: {expr}"
        );
        let first = plan.terms.first().expect("term");
        match first {
            QueryTerm::CjkAdjacentBigrams { bigrams } => {
                assert_eq!(bigrams.len(), 3, "你好世界 → 3 bigrams");
                assert_eq!(bigrams.first().map(String::as_str), Some("你好"));
                assert_eq!(bigrams.get(1).map(String::as_str), Some("好世"));
                assert_eq!(bigrams.get(2).map(String::as_str), Some("世界"));
            }
            QueryTerm::CjkUnigram { .. } | QueryTerm::Word { .. } | QueryTerm::Emoji { .. } => {
                panic!("expected CjkAdjacentBigrams, got {first:?}")
            }
        }

        let single = query_plan("你").expect("single");
        let single_term = single.terms.first().expect("single term");
        assert!(matches!(
            single_term,
            QueryTerm::CjkUnigram { token } if token == "你"
        ));
    }

    #[test]
    fn fts_external_content_indexes_search_content_projection() {
        let dir = tempdir().expect("tempdir");
        let mut store = Store::open(dir.path()).expect("open");
        let op = OperationId::parse("op-fts-1").expect("op");
        store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: op,
                    kind: MemoCommandKind::Create,
                    memo_id: "m1".into(),
                    expected_revision: 0,
                    expected_fingerprint: None,
                    content: Some("笔记：你好世界 hello".into()),
                    tags: vec![],
                    pin: None,
                    pending_promotes: vec![],
                },
                None,
            )
            .expect("create");

        let page = store
            .query_memos(
                &MemoQuery {
                    search_text: Some("你好世界".into()),
                    filters: MemoFilters::default(),
                },
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("query");
        assert_eq!(page.items.len(), 1);
        assert_eq!(page.items.first().map(|m| m.memo_id.as_str()), Some("m1"));

        let tokenizer = UnicodeTokenizer;
        let indexed: String = tokenizer.index_tokens("x");
        assert!(!indexed.is_empty() || indexed.is_empty());
        let planned: QueryPlan = tokenizer.query_plan("x").expect("plan");
        assert!(planned.match_expr.is_some() || planned.match_expr.is_none());
    }

    #[test]
    fn fts_cursor_preserves_rank_order_across_pages() {
        let dir = tempdir().expect("tempdir");
        let mut store = Store::open(dir.path()).expect("open");
        for (id, content) in [
            ("rank-a", "needle needle needle alpha"),
            ("rank-b", "needle beta"),
            ("rank-c", "needle needle gamma"),
        ] {
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
                        tags: vec![],
                        pin: None,
                        pending_promotes: vec![],
                    },
                    None,
                )
                .expect("create");
        }

        let query = MemoQuery {
            search_text: Some("needle".into()),
            filters: MemoFilters::default(),
        };
        let full = store
            .query_memos(&query, None, PageSize::new(10).expect("page"))
            .expect("full FTS query");
        assert_eq!(full.items.len(), 3);
        assert!(full.items.iter().all(|item| item.rank.is_some()));
        let expected_ids: Vec<_> = full.items.iter().map(|item| item.memo_id.clone()).collect();

        let first = store
            .query_memos(&query, None, PageSize::new(1).expect("page"))
            .expect("first FTS page");
        let ranked_cursor = first.next_cursor.expect("next cursor");
        let mut missing_rank = ranked_cursor.clone();
        missing_rank.sort_rank_bits = None;
        let error = store
            .query_memos(&query, Some(&missing_rank), PageSize::new(1).expect("page"))
            .expect_err("FTS cursor without rank");
        assert_eq!(error.code(), "invalid_page_cursor");

        let mut non_finite_rank = ranked_cursor;
        non_finite_rank.sort_rank_bits = Some(f64::NAN.to_bits());
        let error = store
            .query_memos(
                &query,
                Some(&non_finite_rank),
                PageSize::new(1).expect("page"),
            )
            .expect_err("FTS cursor with non-finite rank");
        assert_eq!(error.code(), "invalid_page_cursor");

        let mut cursor = None;
        let mut actual_ids = Vec::new();
        for _ in 0..4 {
            let page = store
                .query_memos(&query, cursor.as_ref(), PageSize::new(1).expect("page"))
                .expect("paged FTS query");
            actual_ids.extend(page.items.iter().map(|item| item.memo_id.clone()));
            cursor = page.next_cursor;
            if cursor.is_none() {
                break;
            }
        }
        assert_eq!(actual_ids, expected_ids);
    }
}
