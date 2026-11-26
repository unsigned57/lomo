//! Behavior Contract (P3-02 -> Tantivy + Charabia)
//!
//! Capability: Charabia Chinese segmentation (jieba-rs + Traditional/Simplified normalization), CJK
//! bigram fallback, emoji preservation, heteronym pinyin indexing, and lazy snapshot-aligned
//! Tantivy search in `lomo-store` with SQLite `search_changelog` consistency.
//!
//! Scenarios:
//! - Given CJK, Traditional Chinese, Latin, and emoji text, when analyzed, Charabia segments Chinese
//!   words (`開發` <-> `开发`) and preserves Latin words and emoji codepoints; CJK bigram fallback
//!   emits normalized unigrams and adjacent 2-char pairs (`TOKENIZER_VERSION == 2`).
//! - Given indexed memos, `Fulltext` matches Chinese words (Traditional & Simplified), CJK sub-words,
//!   Latin words, and emojis, while strictly rejecting partial substring fragments (`ndle` vs `needle`).
//! - Given indexed memos, `Fuzzy` matches edit-distance typos (`nedle` -> `needle`), within-term
//!   subsequences (`ndle` -> `needle`), and heteronym pinyin (`bdlcc`, `cc`, `zc` -> `八达岭长城`).
//! - Given multiple ranked hits, cursor continuation validates rank bits, preserves rank order, and
//!   resolves `AtMemo` + `search_window_sides` cleanly.
//! - Given fresh projection writes (`Store` / `StoreReader`), Tantivy stays unloaded on disk until
//!   first search or `warmup_search_index`, tracks mutations via `search_changelog`, and self-heals
//!   if `.lomo-sqlite/search/` is deleted while open.
//!
//! Observable outcomes: analyzed tokens, Tantivy hit sets/ranks, cursor validation errors,
//! `SearchIndexStats`, and on-disk search directory lifecycle.
//! Excludes: Room, JVM tokenizer authority.
//!
//! Test Change Justification:
//! Reason category: Architecture cutover (`SQLite FTS5` + `UnicodeTokenizer` replaced by Tantivy + Charabia).
//! Old behavior/assertion being replaced: Asserted `index_tokens`, `QueryPlan`, `QueryTerm`, and
//!   `TOKENIZER_VERSION == 1`.
//! Why old assertion is no longer correct: `UnicodeTokenizer` and SQLite `memo_fts` are removed and
//!   `TOKENIZER_VERSION` bumped to `2` for Tantivy + Charabia.
//! Coverage preserved by: Asserting Charabia + CJK bigram + emoji tokenization, non-OR bigram
//!   adjacency, `Fulltext` ranking and cursor validation, plus new `Fuzzy`, lazy-sync, and self-heal
//!   contracts.
//! Why this is not fitting the test to the implementation: Verifies observable retrieval contracts.

mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use super::support::{indexed_store, seed_memo};
    use lomo_core::PageSize;
    use lomo_store::{
        MemoFilters, MemoQuery, MemoQueryStart, MemoSort, SQLITE_DIR_NAME, SafProjectionMutation,
        SafProjectionMutationKind, ScannedMemoProjection, SearchCoordinator, SearchPageRequest,
        StoreReader, StoreSearchMode, TOKENIZER_VERSION, analyze_charabia_tokens,
        analyze_cjk_bigrams, extract_pinyin_full_tokens, extract_pinyin_initial_tokens,
        fingerprint_content, tokenizer_version,
    };
    use tempfile::tempdir;

    #[test]
    fn charabia_and_cjk_bigram_analyzers_cover_chinese_latin_and_emoji() {
        let mixed_text = "Hello 你好 🎉 world";
        let mixed_tokens = analyze_charabia_tokens(mixed_text);
        let mixed: Vec<&str> = mixed_tokens
            .iter()
            .map(|token| token.text.as_str())
            .collect();
        assert_eq!(mixed, vec!["hello", "你好", "🎉", "world"]);
        for token in &mixed_tokens {
            assert_eq!(token.text.to_lowercase(), token.text);
            assert!(mixed_text.get(token.byte_start..token.byte_end).is_some());
        }

        let trad: Vec<String> = analyze_charabia_tokens("開發")
            .into_iter()
            .map(|token| token.text)
            .collect();
        assert_eq!(trad, vec!["开发"]);

        let trad_phrase = "開發筆記";
        let trad_phrase_tokens = analyze_charabia_tokens(trad_phrase);
        let trad_words: Vec<&str> = trad_phrase_tokens
            .iter()
            .map(|token| token.text.as_str())
            .collect();
        assert_eq!(trad_words, vec!["开发", "笔记"]);
        for token in &trad_phrase_tokens {
            assert!(trad_phrase.get(token.byte_start..token.byte_end).is_some());
        }

        let trad_bigrams: Vec<String> = analyze_cjk_bigrams("開發筆記")
            .into_iter()
            .map(|token| token.text)
            .collect();
        assert_eq!(
            trad_bigrams,
            vec!["开", "开发", "发", "发笔", "笔", "笔记", "记"]
        );

        let bigrams: Vec<String> = analyze_cjk_bigrams("你好世界")
            .into_iter()
            .map(|token| token.text)
            .collect();
        assert!(bigrams.contains(&"你好".to_owned()));
        assert!(bigrams.contains(&"好世".to_owned()));
        assert!(bigrams.contains(&"世界".to_owned()));
        assert!(!bigrams.contains(&"你世".to_owned()));
        assert_eq!(tokenizer_version(), TOKENIZER_VERSION);
        assert_eq!(TOKENIZER_VERSION, 2);
    }

    #[test]
    fn heteronym_pinyin_and_variant_equivalence_contracts() {
        let shenme_trad: Vec<String> = analyze_charabia_tokens("什麼")
            .into_iter()
            .map(|token| token.text)
            .collect();
        let shenme_simp: Vec<String> = analyze_charabia_tokens("什么")
            .into_iter()
            .map(|token| token.text)
            .collect();
        assert_eq!(shenme_trad, shenme_simp);
        assert_eq!(shenme_simp, vec!["什么"]);

        let shuofa_simp: Vec<String> = analyze_charabia_tokens("说法")
            .into_iter()
            .map(|token| token.text)
            .collect();
        for variant in ["說法", "説法"] {
            let tokens: Vec<String> = analyze_charabia_tokens(variant)
                .into_iter()
                .map(|token| token.text)
                .collect();
            assert_eq!(tokens, shuofa_simp, "variant '{variant}' equivalence");
        }

        let limian_simp: Vec<String> = analyze_charabia_tokens("里面")
            .into_iter()
            .map(|token| token.text)
            .collect();
        for variant in ["裡面", "裏面"] {
            let tokens: Vec<String> = analyze_charabia_tokens(variant)
                .into_iter()
                .map(|token| token.text)
                .collect();
            assert_eq!(tokens, limian_simp, "variant '{variant}' equivalence");
        }

        let full = extract_pinyin_full_tokens("八达岭长城 重庆");
        assert!(full.iter().any(|token| token == "changcheng"));
        assert!(full.iter().any(|token| token == "zhangcheng"));
        assert!(full.iter().any(|token| token == "badalingchangcheng"));
        assert!(full.iter().any(|token| token == "chongqing"));
        assert!(full.iter().any(|token| token == "zhongqing"));

        let initials = extract_pinyin_initial_tokens("八达岭长城 重庆");
        assert!(initials.iter().any(|token| token == "bdlcc"));
        assert!(initials.iter().any(|token| token == "cc"));
        assert!(initials.iter().any(|token| token == "zc"));
        assert!(initials.iter().any(|token| token == "cq"));
        assert!(initials.iter().any(|token| token == "zq"));
    }

    #[test]
    fn fulltext_and_fuzzy_tantivy_semantics_and_normalization() {
        let dir = tempdir().expect("tempdir");
        seed_memo(
            dir.path(),
            "m1",
            "開發筆記：你好世界 needle 八达岭长城 🎉",
            &[],
        );
        seed_memo(dir.path(), "m2", "无关内容 大城市", &[]);
        let store = indexed_store(dir.path());

        let search_dir = dir.path().join(SQLITE_DIR_NAME).join("search");
        assert!(
            !store.search_index_stats().is_loaded && !search_dir.exists(),
            "writes must not build or load Tantivy before first search"
        );

        let filters = MemoFilters::default();
        let sort = MemoSort::default();

        // Simplified query finds Traditional Chinese memo via Charabia normalization.
        let trad_hit = store
            .search_memos(SearchPageRequest {
                text: Some("开发"),
                mode: StoreSearchMode::Fulltext,
                filters: &filters,
                sort: &sort,
                boundary: None,
                start: MemoQueryStart::Head,
                page_size: PageSize::new(10).expect("page"),
            })
            .expect("fulltext query");
        assert_eq!(trad_hit.items.len(), 1);
        assert_eq!(
            trad_hit.items.first().map(|item| item.memo_id.as_str()),
            Some("m1")
        );

        // Emoji and contiguous CJK bigram match cleanly; scattered unigram OR is absent.
        for hit_query in ["你好世界", "好世", "🎉"] {
            let page = store
                .query_memos(
                    &MemoQuery {
                        search_text: Some(hit_query.to_owned()),
                        filters: filters.clone(),
                        sort: sort.clone(),
                    },
                    None,
                    PageSize::new(10).expect("page"),
                )
                .expect("fulltext query");
            assert_eq!(page.items.len(), 1, "expected hit for '{hit_query}'");
            assert_eq!(
                page.items.first().map(|item| item.memo_id.as_str()),
                Some("m1")
            );
        }
        let scattered = store
            .query_memos(
                &MemoQuery {
                    search_text: Some("你好城市".to_owned()),
                    filters: filters.clone(),
                    sort: sort.clone(),
                },
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("scattered CJK");
        assert_eq!(scattered.items.len(), 0);

        // Fulltext rejects partial fragment `ndle`; Fuzzy matches typo `nedle`, subsequence `ndle`,
        // and heteronym pinyin (`bdlcc`, `cc`, `zc`).
        let fulltext_ndle = store
            .search_memos(SearchPageRequest {
                text: Some("ndle"),
                mode: StoreSearchMode::Fulltext,
                filters: &filters,
                sort: &sort,
                boundary: None,
                start: MemoQueryStart::Head,
                page_size: PageSize::new(10).expect("page"),
            })
            .expect("fulltext ndle");
        assert_eq!(fulltext_ndle.items.len(), 0);

        for fuzzy_query in ["nedle", "ndle", "bdlcc", "cc", "zc"] {
            let fuzzy = store
                .search_memos(SearchPageRequest {
                    text: Some(fuzzy_query),
                    mode: StoreSearchMode::Fuzzy,
                    filters: &filters,
                    sort: &sort,
                    boundary: None,
                    start: MemoQueryStart::Head,
                    page_size: PageSize::new(10).expect("page"),
                })
                .expect("fuzzy query");
            assert_eq!(
                fuzzy.items.len(),
                1,
                "expected fuzzy hit for '{fuzzy_query}'"
            );
            assert_eq!(
                fuzzy.items.first().map(|item| item.memo_id.as_str()),
                Some("m1")
            );
        }
    }

    #[test]
    fn tantivy_cursor_preserves_rank_order_and_validates_rank_bits() {
        let dir = tempdir().expect("tempdir");
        for (id, content) in [
            ("rank-a", "needle needle needle alpha"),
            ("rank-b", "needle beta"),
            ("rank-c", "needle needle gamma"),
        ] {
            seed_memo(dir.path(), id, content, &[]);
        }
        let store = indexed_store(dir.path());

        let query = MemoQuery {
            search_text: Some("needle".into()),
            filters: MemoFilters::default(),
            sort: MemoSort::default(),
        };
        let full = store
            .query_memos(&query, None, PageSize::new(10).expect("page"))
            .expect("full query");
        assert_eq!(full.items.len(), 3);
        assert!(full.items.iter().all(|item| item.rank.is_some()));
        let expected_ids: Vec<_> = full.items.iter().map(|item| item.memo_id.clone()).collect();

        let first = store
            .query_memos(&query, None, PageSize::new(1).expect("page"))
            .expect("first page");
        let ranked_cursor = first.next_cursor.expect("next cursor");
        let mut missing_rank = ranked_cursor.clone();
        missing_rank.sort_rank_bits = None;
        let error = store
            .query_memos(&query, Some(&missing_rank), PageSize::new(1).expect("page"))
            .expect_err("search cursor without rank");
        assert_eq!(error.code(), "invalid_page_cursor");

        let mut non_finite_rank = ranked_cursor;
        non_finite_rank.sort_rank_bits = Some(f64::NAN.to_bits());
        let error = store
            .query_memos(
                &query,
                Some(&non_finite_rank),
                PageSize::new(1).expect("page"),
            )
            .expect_err("search cursor with non-finite rank");
        assert_eq!(error.code(), "invalid_page_cursor");

        let mut cursor = None;
        let mut actual_ids = Vec::new();
        for _ in 0..4 {
            let page = store
                .query_memos(&query, cursor.as_ref(), PageSize::new(1).expect("page"))
                .expect("paged search");
            actual_ids.extend(page.items.iter().map(|item| item.memo_id.clone()));
            cursor = page.next_cursor;
            if cursor.is_none() {
                break;
            }
        }
        assert_eq!(actual_ids, expected_ids);
    }

    #[test]
    fn incremental_changelog_sync_and_missing_dir_self_heal_via_reader() {
        let dir = tempdir().expect("tempdir");
        seed_memo(dir.path(), "s1", "initial alpha note", &["work/sub"]);
        let mut store = indexed_store(dir.path());
        let reader = StoreReader::open(dir.path()).expect("open reader");

        let filters = MemoFilters {
            tag: Some("work".to_owned()),
            tag_selection: lomo_store::TagSelectionMode::Subtree,
            ..MemoFilters::default()
        };
        let sort = MemoSort::default();
        let initial = reader
            .search_memos(SearchPageRequest {
                text: Some("alpha"),
                mode: StoreSearchMode::Fulltext,
                filters: &filters,
                sort: &sort,
                boundary: None,
                start: MemoQueryStart::Head,
                page_size: PageSize::new(10).expect("page"),
            })
            .expect("reader initial search");
        assert_eq!(initial.items.len(), 1);
        assert_eq!(reader.search_index_stats().full_rebuilds, 1);

        // Commit a second memo into SQLite without rotating search_generation -> search_changelog
        // syncs incrementally on the Store without triggering a full rebuild.
        let body_s2 = "second alpha beta #work";
        store
            .commit_saf_projection_mutation(&SafProjectionMutation {
                operation_id: "op-search-sync-s2".to_owned(),
                kind: SafProjectionMutationKind::Create,
                memo_id: "s2".to_owned(),
                expected_revision: 0,
                expected_fingerprint: None,
                projection: Some(ScannedMemoProjection {
                    memo_id: "s2".to_owned(),
                    source_path: "memos/s2.md".to_owned(),
                    time_part: "10:00:00".to_owned(),
                    file_fingerprint: fingerprint_content(body_s2),
                    chronology_epoch_ms: 1_700_000_000_100,
                    body: body_s2.to_owned(),
                    tags: vec!["work".to_owned()],
                    attachment_paths: Vec::new(),
                    has_todo: false,
                    has_url: false,
                    reminders: Vec::new(),
                }),
                trashed_at_ms: None,
                batch_targets: Vec::new(),
            })
            .expect("incremental SQLite projection create");
        let updated = store
            .search_memos(SearchPageRequest {
                text: Some("alpha"),
                mode: StoreSearchMode::Fulltext,
                filters: &filters,
                sort: &sort,
                boundary: None,
                start: MemoQueryStart::Head,
                page_size: PageSize::new(10).expect("page"),
            })
            .expect("incremental synced search");
        assert_eq!(updated.items.len(), 2);
        assert_eq!(store.search_index_stats().incremental_syncs, 1);
        assert_eq!(store.search_index_stats().full_rebuilds, 1);

        // Deleting `.lomo-sqlite/search/` while handles stay open self-heals on next query.
        let search_dir = dir.path().join(SQLITE_DIR_NAME).join("search");
        std::fs::remove_dir_all(&search_dir).expect("delete search dir");
        let healed = reader
            .search_memos(SearchPageRequest {
                text: Some("beta"),
                mode: StoreSearchMode::Fulltext,
                filters: &filters,
                sort: &sort,
                boundary: None,
                start: MemoQueryStart::Head,
                page_size: PageSize::new(10).expect("page"),
            })
            .expect("self-healed search");
        assert_eq!(healed.items.len(), 1);
        assert_eq!(
            healed.items.first().map(|item| item.memo_id.as_str()),
            Some("s2")
        );
        assert_eq!(reader.search_index_stats().full_rebuilds, 2);
    }

    fn install_snapshot_pause_view(
        conn: &rusqlite::Connection,
        paused_tx: std::sync::mpsc::Sender<()>,
        resume_rx: std::sync::Arc<std::sync::Mutex<std::sync::mpsc::Receiver<()>>>,
        pause_fired: std::sync::Arc<std::sync::atomic::AtomicBool>,
    ) {
        use rusqlite::functions::FunctionFlags;
        use std::sync::atomic::{AtomicUsize, Ordering};

        let calls = AtomicUsize::new(0);
        conn.create_scalar_function(
            "lomo_test_pause",
            0,
            FunctionFlags::SQLITE_UTF8,
            move |_| {
                if calls.fetch_add(1, Ordering::SeqCst) == 1 {
                    pause_fired.store(true, Ordering::SeqCst);
                    paused_tx.send(()).expect("signal reader snapshot pinned");
                    resume_rx
                        .lock()
                        .expect("resume lock")
                        .recv()
                        .expect("resume signal");
                }
                Ok(1_i64)
            },
        )
        .expect("register pause hook");
        conn.execute_batch(
            "CREATE TEMP VIEW search_changelog AS
             SELECT seq, memo_id FROM main.search_changelog
             UNION ALL
             SELECT 0 AS seq, '' AS memo_id WHERE lomo_test_pause() = 0;",
        )
        .expect("create temp shadow view");
    }

    #[test]
    fn concurrent_incremental_reload_does_not_leak_new_index_into_old_snapshot() {
        use std::sync::{
            Arc, Mutex,
            atomic::{AtomicBool, Ordering},
            mpsc,
        };

        let dir = tempdir().expect("tempdir");
        seed_memo(dir.path(), "m1", "alpha unique baseline", &[]);
        let mut store = indexed_store(dir.path());
        store.warmup_search_index().expect("initial warmup");

        let db_path = store.open_info().database_path;
        let coordinator = SearchCoordinator::for_root(dir.path());
        let reader_conn = rusqlite::Connection::open(&db_path).expect("second reader connection");
        let pause_fired = Arc::new(AtomicBool::new(false));
        let (paused_tx, paused_rx) = mpsc::channel::<()>();
        let (resume_tx, resume_rx) = mpsc::channel::<()>();
        install_snapshot_pause_view(
            &reader_conn,
            paused_tx,
            Arc::new(Mutex::new(resume_rx)),
            Arc::clone(&pause_fired),
        );

        let reader_coord = Arc::clone(&coordinator);
        let handle = std::thread::spawn(move || {
            let filters = MemoFilters::default();
            let sort = MemoSort::default();
            reader_coord.search_memos(
                &reader_conn,
                false,
                SearchPageRequest {
                    text: Some("alpha"),
                    mode: StoreSearchMode::Fulltext,
                    filters: &filters,
                    sort: &sort,
                    boundary: None,
                    start: MemoQueryStart::Head,
                    page_size: PageSize::new(10).expect("page"),
                },
            )
        });

        paused_rx.recv().expect("reader pinned old snapshot");
        let old_snap = store.get_memo("m1").expect("get old").expect("m1 exists");
        let new_body = "beta replaced content";
        store
            .commit_saf_projection_mutation(&SafProjectionMutation {
                operation_id: "op-concurrent-update-m1".to_owned(),
                kind: SafProjectionMutationKind::Update,
                memo_id: "m1".to_owned(),
                expected_revision: old_snap.summary.content_revision,
                expected_fingerprint: Some(old_snap.summary.file_fingerprint),
                projection: Some(ScannedMemoProjection {
                    memo_id: "m1".to_owned(),
                    source_path: old_snap.summary.source_path,
                    time_part: old_snap.summary.time_part,
                    file_fingerprint: fingerprint_content(new_body),
                    chronology_epoch_ms: old_snap.summary.created_at_ms,
                    body: new_body.to_owned(),
                    tags: Vec::new(),
                    attachment_paths: Vec::new(),
                    has_todo: false,
                    has_url: false,
                    reminders: Vec::new(),
                }),
                trashed_at_ms: None,
                batch_targets: Vec::new(),
            })
            .expect("commit update");
        store.warmup_search_index().expect("incremental reload");
        resume_tx.send(()).expect("resume reader");

        let page = handle
            .join()
            .expect("reader thread")
            .expect("aligned snapshot search");
        assert!(pause_fired.load(Ordering::SeqCst));
        assert_eq!(page.high_water_revision, 0);
        assert_eq!(page.items.len(), 1);
        assert_eq!(
            page.items.first().map(|item| item.memo_id.as_str()),
            Some("m1")
        );

        let after_alpha = store
            .search_memos(SearchPageRequest {
                text: Some("alpha"),
                mode: StoreSearchMode::Fulltext,
                filters: &MemoFilters::default(),
                sort: &MemoSort::default(),
                boundary: None,
                start: MemoQueryStart::Head,
                page_size: PageSize::new(10).expect("page"),
            })
            .expect("post-update alpha");
        assert_eq!(after_alpha.high_water_revision, 1);
        assert_eq!(after_alpha.items, Vec::<lomo_store::MemoSummary>::new());
    }

    #[test]
    fn tantivy_cursor_rejects_structural_query_drift() {
        let dir = tempdir().expect("tempdir");
        seed_memo(dir.path(), "c1", "needle needle 开发 笔记 a b", &[]);
        seed_memo(dir.path(), "c2", "needle 开发笔记 a_b", &[]);
        let store = indexed_store(dir.path());
        let filters = MemoFilters::default();
        let sort = MemoSort::default();

        for (first_q, drifted_q) in [
            ("needle", "needle needle"),
            ("开发笔记", "开发 笔记"),
            ("a_b", "a b"),
        ] {
            let page1 = store
                .search_memos(SearchPageRequest {
                    text: Some(first_q),
                    mode: StoreSearchMode::Fulltext,
                    filters: &filters,
                    sort: &sort,
                    boundary: None,
                    start: MemoQueryStart::Head,
                    page_size: PageSize::new(1).expect("page"),
                })
                .expect("first page");
            let cursor = page1.next_cursor.expect("page 1 next_cursor");
            let err = store
                .search_memos(SearchPageRequest {
                    text: Some(drifted_q),
                    mode: StoreSearchMode::Fulltext,
                    filters: &filters,
                    sort: &sort,
                    boundary: None,
                    start: MemoQueryStart::After(&cursor),
                    page_size: PageSize::new(1).expect("page"),
                })
                .expect_err("structurally different query must reject cursor");
            assert_eq!(
                err.code(),
                "stale_cursor",
                "cursor from '{first_q}' must be rejected for '{drifted_q}'"
            );
        }
    }

    #[test]
    fn tantivy_queries_do_not_truncate_chunks_past_eight_or_prefixes_past_256() {
        use std::fmt::Write as _;

        let dir = tempdir().expect("tempdir");
        seed_memo(dir.path(), "m-nine", "t1 t2 t3 t4 t5 t6 t7 t8 t9", &[]);

        let mut many_probes = String::new();
        for idx in 0..300 {
            if !many_probes.is_empty() {
                many_probes.push(' ');
            }
            write!(many_probes, "probe{idx:03}").expect("write probe term");
        }
        seed_memo(dir.path(), "m-probes", &many_probes, &[]);
        seed_memo(dir.path(), "m-probe-tail", "probezzzz", &[]);

        let store = indexed_store(dir.path());
        let filters = MemoFilters::default();
        let sort = MemoSort::default();

        let miss = store
            .search_memos(SearchPageRequest {
                text: Some("t1 t2 t3 t4 t5 t6 t7 t8 missing"),
                mode: StoreSearchMode::Fulltext,
                filters: &filters,
                sort: &sort,
                boundary: None,
                start: MemoQueryStart::Head,
                page_size: PageSize::new(10).expect("page"),
            })
            .expect("9th chunk miss");
        assert!(miss.items.is_empty(), "9th chunk must not be ignored");

        let hit = store
            .search_memos(SearchPageRequest {
                text: Some("t1 t2 t3 t4 t5 t6 t7 t8 t9"),
                mode: StoreSearchMode::Fulltext,
                filters: &filters,
                sort: &sort,
                boundary: None,
                start: MemoQueryStart::Head,
                page_size: PageSize::new(10).expect("page"),
            })
            .expect("9th chunk hit");
        assert_eq!(
            hit.items.first().map(|item| item.memo_id.as_str()),
            Some("m-nine")
        );

        let prefix_hits = store
            .search_memos(SearchPageRequest {
                text: Some("probe"),
                mode: StoreSearchMode::Fulltext,
                filters: &filters,
                sort: &sort,
                boundary: None,
                start: MemoQueryStart::Head,
                page_size: PageSize::new(10).expect("page"),
            })
            .expect("prefix query beyond 256 expansions");
        let tail_hit = prefix_hits
            .items
            .iter()
            .find(|item| item.memo_id == "m-probe-tail")
            .expect("probezzzz must not be dropped by 256-term prefix cutoff");
        assert!(tail_hit.rank.is_some_and(|rank| rank > 0.0));
    }

    #[test]
    fn streaming_search_rebuild_indexes_all_tags_without_temp_btree() {
        let dir = tempdir().expect("tempdir");
        seed_memo(dir.path(), "00-notag", "sharedbody", &[]);
        seed_memo(
            dir.path(),
            "01-multi",
            "sharedbody",
            &["zeta", "work/sub", "alpha"],
        );
        seed_memo(dir.path(), "02-single", "sharedbody", &["work"]);
        seed_memo(dir.path(), "03-notag-after", "sharedbody", &[]);
        let store = indexed_store(dir.path());
        store.warmup_search_index().expect("streaming cold rebuild");

        let db_path = store.open_info().database_path;
        let explain_conn = rusqlite::Connection::open(&db_path).expect("open explain conn");
        for sql in [
            "SELECT memo_id, source_path, COALESCE(body, body_preview), \
             has_todo, has_attachment, has_url, is_pinned, is_trashed, \
             pending_operation_id IS NOT NULL, created_at_ms, updated_at_ms \
             FROM memo ORDER BY memo_id ASC",
            "SELECT mt.memo_id, t.name \
             FROM memo_tag mt JOIN tag t ON t.id = mt.tag_id \
             ORDER BY mt.memo_id ASC",
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
                !plan.contains("TEMP B-TREE"),
                "streaming index query must not materialize TEMP B-TREE:\n{plan}"
            );
        }

        let sort = MemoSort::default();
        let subtree = store
            .search_memos(SearchPageRequest {
                text: Some("sharedbody"),
                mode: StoreSearchMode::Fulltext,
                filters: &MemoFilters {
                    tag: Some("work".to_owned()),
                    tag_selection: lomo_store::TagSelectionMode::Subtree,
                    ..MemoFilters::default()
                },
                sort: &sort,
                boundary: None,
                start: MemoQueryStart::Head,
                page_size: PageSize::new(10).expect("page"),
            })
            .expect("subtree tag search");
        let subtree_ids: std::collections::BTreeSet<&str> = subtree
            .items
            .iter()
            .map(|item| item.memo_id.as_str())
            .collect();
        assert_eq!(
            subtree_ids,
            std::collections::BTreeSet::from(["01-multi", "02-single"])
        );

        let exact_alpha = store
            .search_memos(SearchPageRequest {
                text: Some("sharedbody"),
                mode: StoreSearchMode::Fulltext,
                filters: &MemoFilters {
                    tag: Some("alpha".to_owned()),
                    tag_selection: lomo_store::TagSelectionMode::Exact,
                    ..MemoFilters::default()
                },
                sort: &sort,
                boundary: None,
                start: MemoQueryStart::Head,
                page_size: PageSize::new(10).expect("page"),
            })
            .expect("exact alpha tag search");
        assert_eq!(
            exact_alpha
                .items
                .iter()
                .map(|item| item.memo_id.as_str())
                .collect::<Vec<_>>(),
            vec!["01-multi"]
        );
    }

    fn search_needle_page_one(
        store: &lomo_store::Store,
        start: MemoQueryStart<'_>,
    ) -> Result<lomo_store::MemoPage, lomo_core::LomoError> {
        store.search_memos(SearchPageRequest {
            text: Some("needle"),
            mode: StoreSearchMode::Fulltext,
            filters: &MemoFilters::default(),
            sort: &MemoSort::default(),
            boundary: None,
            start,
            page_size: PageSize::new(1).expect("page size"),
        })
    }

    #[test]
    fn tantivy_cursor_fails_closed_after_derived_index_rebuild() {
        let dir = tempdir().expect("tempdir");
        seed_memo(dir.path(), "r1", "needle alpha", &[]);
        seed_memo(dir.path(), "r2", "needle beta x", &[]);
        seed_memo(dir.path(), "r3", "needle gamma x y", &[]);
        let mut store = indexed_store(dir.path());
        store.warmup_search_index().expect("initial warmup");

        let old_r1 = store.get_memo("r1").expect("get r1").expect("r1 exists");
        let new_body = "needle alpha revised extra words";
        store
            .commit_saf_projection_mutation(&SafProjectionMutation {
                operation_id: "op-rebuild-cursor-update-r1".to_owned(),
                kind: SafProjectionMutationKind::Update,
                memo_id: "r1".to_owned(),
                expected_revision: old_r1.summary.content_revision,
                expected_fingerprint: Some(old_r1.summary.file_fingerprint),
                projection: Some(ScannedMemoProjection {
                    memo_id: "r1".to_owned(),
                    source_path: old_r1.summary.source_path,
                    time_part: old_r1.summary.time_part,
                    file_fingerprint: fingerprint_content(new_body),
                    chronology_epoch_ms: old_r1.summary.created_at_ms,
                    body: new_body.to_owned(),
                    tags: Vec::new(),
                    attachment_paths: Vec::new(),
                    has_todo: false,
                    has_url: false,
                    reminders: Vec::new(),
                }),
                trashed_at_ms: None,
                batch_targets: Vec::new(),
            })
            .expect("commit update");

        let page1 = search_needle_page_one(&store, MemoQueryStart::Head).expect("page 1");
        let p1_next = page1.next_cursor.expect("page 1 next_cursor");
        let rev_before = page1.high_water_revision;
        let page2 =
            search_needle_page_one(&store, MemoQueryStart::After(&p1_next)).expect("page 2");
        let p2_prev = page2.prev_cursor.expect("page 2 prev_cursor");
        let p2_id = page2.items.first().expect("page 2 hit").memo_id.clone();

        drop(store);
        let reopened = lomo_store::Store::open(dir.path()).expect("reopen store");
        let same_snapshot = search_needle_page_one(&reopened, MemoQueryStart::After(&p1_next))
            .expect("clean reopen preserves scoring snapshot cursor");
        assert_eq!(
            same_snapshot
                .items
                .first()
                .map(|item| item.memo_id.as_str()),
            Some(p2_id.as_str())
        );

        let search_dir = dir.path().join(SQLITE_DIR_NAME).join("search");
        std::fs::remove_dir_all(&search_dir).expect("delete derived search dir");

        for start in [
            MemoQueryStart::After(&p1_next),
            MemoQueryStart::Before(&p2_prev),
        ] {
            let err = search_needle_page_one(&reopened, start)
                .expect_err("cursor from pre-rebuild index must fail closed");
            assert_eq!(err.code(), "stale_cursor");
        }

        let mut seen = std::collections::BTreeSet::new();
        let mut cursor = None;
        for _ in 0..3 {
            let start = cursor
                .as_ref()
                .map_or(MemoQueryStart::Head, MemoQueryStart::After);
            let page = search_needle_page_one(&reopened, start).expect("fresh walk");
            assert_eq!(page.high_water_revision, rev_before);
            let id = page.items.first().expect("fresh item").memo_id.clone();
            assert!(seen.insert(id), "fresh walk must not duplicate items");
            cursor = page.next_cursor;
        }
        assert_eq!(seen.len(), 3);
    }
}
