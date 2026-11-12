// Dependency upgrade — Test Change Justification:
// Reason category: mechanical migration.
// Old behavior/assertion being replaced: old dependency API spelling and boolean empty checks.
// Why old assertion is no longer correct: updated APIs and Rust 1.99 diagnostics require explicit
// byte encoding, image geometry types and equality assertions that print failing collections.
// Coverage preserved by: the same scenarios, input bytes, expected values, ordering and failures.
// Why this is not fitting the test to the implementation: observable behavior and golden vectors
// are unchanged; only equivalent API/assertion syntax and fixture construction are migrated.
//
// Task regression scenarios: Given a durable memo ID and a UTF-8 task span, when toggled,
// then the same memo commits and both source and task projection show checked.
// Given fenced examples and star/ordered tasks, when listed/toggled, then only rendered tasks
// participate and non-marker source bytes retain their spacing.
// TDD proof: RED rendered_task_span returned invalid_memo_identity; task_board_and returned
// [example] instead of [actual, ordered]. GREEN cargo test -p lomo-application
// --test search_task_query_contract --locked.
//
//! Behavior Contract
//! Capability: dual-mode search (Unicode fulltext + pinyin fuzzy) with epoch cancellation,
//! incremental prefix re-scoring, and Markdown task aggregation/toggle through the shared
//! session — all backed by revision-keyed projection caches.
//! Scenarios: `bdlcc` ranks 八达岭长城; stale epochs are discarded; `- [ ]` aggregates and
//! toggles; extending the fuzzy needle rescans only the prior hit set; a superseded scan
//! drops partial work fail-closed; task lists rebuild only when the projection revision moves.
//! Observable outcomes: ordered hits, discarded epochs, `[x]` in source Markdown, scan/build
//! counters proving incremental reuse.
//! TDD proof: fuzzy continuation skipped the first item after each page; the pagination
//! regression fails before the fix. Incremental/cached-path counters (`seeded_scans`,
//! `task_hits`) do not exist before this change — the tests do not compile.
//! Given unrelated writes, unchanged task bodies are reused by memo version; a changed
//! task is rehydrated before publishing the next view.
//! Given one memo whose IR alone exceeds the presentation node budget, when the workspace is
//! indexed, then rebuild, task listing and task toggle succeed — index/task facts use
//! `project_markdown_facts`; only IR crossing to a renderer keeps `render_document_too_large`.
//! TDD proof: RED 2026-11-10 `cargo test -p lomo-application --test search_task_query_contract
//! --locked single_memo_beyond` — `rebuild_projection` failed `render_document_too_large` (the
//! Android read-only lock with a long checklist memo); GREEN after fact callers left the
//! presentation budget.
//! Excludes: Android UI, TUI rendering, network.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::indexing_slicing,
    reason = "contract tests fail closed on missing facts"
)]
mod tests {
    use std::sync::Arc;

    use lomo_application::{
        CreateMemoRequest, SearchMode, SearchOutcome, SearchRequest, ToggleTaskRequest,
        WorkspaceSession, WorkspaceSessionConfig,
    };
    use lomo_core::{CapabilityToken, OperationId, PageSize, RelativeWorkspacePath};
    use lomo_platform_fs::FsPlatformActionExecutor;
    use lomo_workspace::WorkspaceRootId;
    use tempfile::tempdir;

    struct Ctx {
        session: WorkspaceSession,
        _workspace: tempfile::TempDir,
        _state: tempfile::TempDir,
        _cache: tempfile::TempDir,
        _runtime: tempfile::TempDir,
        _exchange: tempfile::TempDir,
        workspace_path: std::path::PathBuf,
    }

    fn open_session() -> Ctx {
        let workspace = tempdir().expect("workspace");
        let state = tempdir().expect("state");
        let cache = tempdir().expect("cache");
        let runtime = tempdir().expect("runtime");
        let exchange = tempdir().expect("exchange");
        let executor = Arc::new(FsPlatformActionExecutor::new(exchange.path()).expect("exec"));
        let capability = CapabilityToken::parse("notes").expect("cap");
        executor
            .bind_root(capability.clone(), workspace.path())
            .expect("bind");
        let config = WorkspaceSessionConfig {
            capability,
            root_id: WorkspaceRootId::Notes,
            workspace_generation: lomo_workspace::WorkspaceGenerationId::mint()
                .expect("workspace generation"),
            time_zone: "UTC".to_owned(),
            date_format: lomo_application::calendar::DateFormat::default(),
            state_dir: state.path().to_path_buf(),
            cache_dir: cache.path().to_path_buf(),
            runtime_dir: runtime.path().to_path_buf(),
            exchange_dir: exchange.path().to_path_buf(),
            media_stage_root: exchange.path().join("media-stage"),
        };
        let session = WorkspaceSession::open(config, executor).expect("open");
        Ctx {
            workspace_path: workspace.path().to_path_buf(),
            session,
            _workspace: workspace,
            _state: state,
            _cache: cache,
            _runtime: runtime,
            _exchange: exchange,
        }
    }

    fn op(raw: &str) -> OperationId {
        OperationId::parse(raw).expect("op")
    }

    fn create(
        ctx: &Ctx,
        id: &str,
        path: &str,
        time: &str,
        content: &str,
        fingerprint: Option<String>,
    ) {
        ctx.session
            .create_memo(CreateMemoRequest {
                operation_id: op(id),
                relative_path: Some(RelativeWorkspacePath::parse(path).expect("path")),
                time_token: Some(time.to_owned()),
                content: content.to_owned(),
                expected_document_fingerprint: fingerprint,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("create");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
    }

    #[test]
    fn imported_missing_attachments_do_not_block_task_edits_or_authorize_new_references() {
        let ctx = open_session();
        std::fs::write(
            ctx.workspace_path.join("2026_09_10.md"),
            "- 09:00:00\n- [ ] imported task\n\n![external folder](z.png)\n",
        )
        .expect("import");
        ctx.session.rebuild_projection().expect("index import");
        let task = ctx.session.list_tasks().expect("tasks").remove(0);
        let id = lomo_workspace::MemoId::parse(&task.memo_id).expect("id");
        ctx.session
            .toggle_task_marker(op("imported-toggle"), id.clone(), 2, 5)
            .expect("toggle imported memo");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        assert!(ctx.session.list_tasks().expect("updated")[0].done);
        let current = ctx
            .session
            .projected_memo(id.as_str())
            .expect("query")
            .expect("memo");
        let error = ctx
            .session
            .update_memo(lomo_application::UpdateMemoRequest {
                operation_id: op("new-missing-reference"),
                memo_id: id,
                content: format!("{}\n![new](missing.png)", current.body),
                expected_document_fingerprint: current.summary.file_fingerprint,
                pending_promotes: Vec::new(),
            })
            .expect_err("new missing attachment must still be rejected");
        assert_eq!(error.code(), "attachment_file_missing_after_promote");
    }

    #[test]
    fn single_memo_beyond_presentation_node_budget_indexes_lists_and_toggles_tasks() {
        // One memo body whose IR alone exceeds the presentation budget: a plain imported note or a
        // long checklist. Index/task facts are not presentation, so the workspace must open and
        // stay writable instead of failing whole with `render_document_too_large`.
        let ctx = open_session();
        let task_count = 3_000_usize;
        let body = (0..task_count)
            .map(|index| format!("- [ ] task {index}"))
            .collect::<Vec<_>>()
            .join("\n");
        let source = lomo_workspace::SourceBytes::try_from_str(&body).expect("body source");
        let presentation =
            lomo_workspace::render_markdown(&source).expect_err("body exceeds presentation budget");
        assert_eq!(presentation.code(), "render_document_too_large");
        std::fs::write(
            ctx.workspace_path.join("2026_11_09.md"),
            format!("- 09:00:00\n{body}\n"),
        )
        .expect("oversized memo");

        ctx.session
            .rebuild_projection()
            .expect("oversized memo indexes");
        let tasks = ctx.session.list_tasks().expect("oversized memo tasks");
        assert_eq!(tasks.len(), task_count);
        let id = lomo_workspace::MemoId::parse(&tasks[0].memo_id).expect("id");
        ctx.session
            .toggle_task_marker(op("oversized-toggle"), id, 2, 5)
            .expect("toggle inside oversized memo");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        let done = ctx
            .session
            .list_tasks()
            .expect("updated tasks")
            .into_iter()
            .filter(|task| task.done)
            .map(|task| task.text)
            .collect::<Vec<_>>();
        assert_eq!(done, ["task 0"]);
    }

    #[test]
    fn task_board_and_memo_cards_share_markdown_task_semantics() {
        let ctx = open_session();
        create(
            &ctx,
            "markdown-task-create",
            "2026_09_10.md",
            "09:00:00",
            "```markdown\n- [ ] example\n```\n\n* [ ] actual  \n1. [X] ordered",
            None,
        );
        let tasks = ctx.session.list_tasks().expect("tasks");
        assert_eq!(
            tasks
                .iter()
                .map(|task| task.text.as_str())
                .collect::<Vec<_>>(),
            vec!["actual", "ordered"]
        );
        ctx.session
            .toggle_task(ToggleTaskRequest {
                operation_id: op("board-toggle"),
                memo_id: lomo_workspace::MemoId::parse(&tasks[0].memo_id).expect("memo"),
                line_index: tasks[0].line_index,
                done: true,
            })
            .expect("toggle star task");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        let written =
            std::fs::read_to_string(ctx.workspace_path.join("2026_09_10.md")).expect("source");
        assert!(written.contains("* [x] actual  "));
        assert!(written.contains("- [ ] example"));
    }

    #[test]
    fn rendered_task_span_uses_durable_memo_identity_and_commits_once() {
        let ctx = open_session();
        create(
            &ctx,
            "task-create",
            "2026_09_10.md",
            "09:00:00",
            "前言\n- [ ] task",
            None,
        );
        let task = ctx.session.list_tasks().expect("tasks").remove(0);
        let id = lomo_workspace::MemoId::parse(&task.memo_id).expect("id");
        let start = u64::try_from("前言\n- ".len()).expect("offset");
        ctx.session
            .toggle_task_marker(op("task-marker"), id.clone(), start, start + 3)
            .expect("toggle");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        let tasks = ctx.session.list_tasks().expect("updated tasks");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        assert!(tasks[0].done);
        assert_eq!(tasks[0].memo_id, id.as_str());
        let written =
            std::fs::read_to_string(ctx.workspace_path.join("2026_09_10.md")).expect("source");
        assert!(written.contains("前言\n- [x] task"));
    }

    #[test]
    fn fuzzy_pinyin_initials_rank_badaling_and_stale_epochs_are_discarded() {
        let ctx = open_session();
        let first = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: op("c1"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_09_10.md").expect("path")),
                time_token: Some("10:00:00".to_owned()),
                content: "八达岭长城".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("wall");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        create(
            &ctx,
            "c2",
            "2026_09_10.md",
            "10:01:00",
            "ordinary pineapple note",
            Some(first.commit_result.file_fingerprint),
        );

        ctx.session
            .search(&SearchRequest {
                filters: lomo_application::MemoFilters::default(),
                query_epoch: 1,
                mode: SearchMode::Fuzzy,
                text: "zzzz".to_owned(),
                cursor: None,
                anchor: None,
                page_size: PageSize::new(16).expect("page"),
            })
            .expect("stale search");
        let fresh = ctx
            .session
            .search(&SearchRequest {
                filters: lomo_application::MemoFilters::default(),
                query_epoch: 2,
                mode: SearchMode::Fuzzy,
                text: "bdlcc".to_owned(),
                cursor: None,
                anchor: None,
                page_size: PageSize::new(16).expect("page"),
            })
            .expect("fuzzy");
        let SearchOutcome::Ready(page) = fresh else {
            panic!("fresh epoch must produce hits");
        };
        assert_ne!(page.items, Vec::<lomo_application::SearchHit>::new());
        assert!(page.items[0].summary.body_preview.contains("八达岭长城"));

        let discarded = ctx
            .session
            .search(&SearchRequest {
                filters: lomo_application::MemoFilters::default(),
                query_epoch: 1,
                mode: SearchMode::Fuzzy,
                text: "zzzz".to_owned(),
                cursor: None,
                anchor: None,
                page_size: PageSize::new(16).expect("page"),
            })
            .expect("discard");
        assert!(matches!(
            discarded,
            SearchOutcome::Discarded { query_epoch: 1, .. }
        ));

        let fulltext = ctx
            .session
            .search(&SearchRequest {
                filters: lomo_application::MemoFilters::default(),
                query_epoch: 3,
                mode: SearchMode::Fulltext,
                text: "八达岭".to_owned(),
                cursor: None,
                anchor: None,
                page_size: PageSize::new(16).expect("page"),
            })
            .expect("fulltext");
        let SearchOutcome::Ready(hits) = fulltext else {
            panic!("fulltext must be ready");
        };
        assert!(
            hits.items
                .iter()
                .any(|hit| hit.summary.body_preview.contains("八达岭长城"))
        );
    }

    #[test]
    fn fuzzy_continuation_does_not_skip_the_next_result() {
        let ctx = open_session();
        for index in 0..5 {
            create(
                &ctx,
                &format!("page-{index}"),
                &format!("2026_09_{}.md", 10 + index),
                "10:00:00",
                "needle common",
                None,
            );
        }
        let mut cursor = None;
        let mut ids = std::collections::BTreeSet::new();
        loop {
            let outcome = ctx
                .session
                .search(&SearchRequest {
                    filters: lomo_application::MemoFilters::default(),
                    query_epoch: 1,
                    mode: SearchMode::Fuzzy,
                    text: "needle".to_owned(),
                    cursor,
                    anchor: None,
                    page_size: PageSize::new(2).expect("page size"),
                })
                .expect("search page");
            let SearchOutcome::Ready(page) = outcome else {
                panic!("current search must be ready");
            };
            for hit in page.items {
                assert!(ids.insert(hit.memo_id), "duplicate result");
            }
            cursor = page.next_cursor;
            if cursor.is_none() {
                break;
            }
        }
        assert_eq!(
            ids.len(),
            5,
            "every matching memo must be reachable across pages"
        );
    }

    #[test]
    fn task_aggregation_lists_and_toggles_markdown_checkboxes() {
        let ctx = open_session();
        let created = ctx
            .session
            .create_memo(CreateMemoRequest {
                operation_id: op("todo"),
                relative_path: Some(RelativeWorkspacePath::parse("2026_09_10.md").expect("path")),
                time_token: Some("11:00:00".to_owned()),
                content: "- [ ] buy milk\n- [x] stretch".to_owned(),
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes: Vec::new(),
                chronology_epoch_ms: None,
            })
            .expect("todo memo");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        let tasks = ctx.session.list_tasks().expect("tasks");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        assert_eq!(tasks.len(), 2);
        assert!(!tasks[0].done);
        assert_eq!(tasks[0].text, "buy milk");
        assert!(tasks[1].done);

        ctx.session
            .toggle_task(ToggleTaskRequest {
                operation_id: op("check"),
                memo_id: created.memo_id,
                line_index: 0,
                done: true,
            })
            .expect("toggle");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        let markdown =
            std::fs::read_to_string(ctx.workspace_path.join("2026_09_10.md")).expect("md");
        assert!(markdown.contains("- [x] buy milk"));
        let tasks = ctx.session.list_tasks().expect("tasks after");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        assert!(
            tasks
                .iter()
                .any(|task| task.text == "buy milk" && task.done)
        );
    }

    #[test]
    fn both_search_modes_apply_tag_subtree_and_date_together() {
        let ctx = open_session();
        create(
            &ctx,
            "filter-a",
            "2026_09_10.md",
            "10:00:00",
            "needle #reading/book",
            None,
        );
        create(
            &ctx,
            "filter-b",
            "2026_09_11.md",
            "10:00:00",
            "needle #reading/book",
            None,
        );
        create(
            &ctx,
            "filter-c",
            "2026_09_12.md",
            "10:00:00",
            "needle #other",
            None,
        );
        let date = lomo_application::calendar::CivilDate::new(2026, 9, 11).expect("date");
        let (start, end) = lomo_application::calendar::day_bounds(date, "UTC").expect("bounds");
        for mode in [SearchMode::Fulltext, SearchMode::Fuzzy] {
            let outcome = ctx
                .session
                .search(&SearchRequest {
                    query_epoch: 1,
                    mode,
                    text: "needle".to_owned(),
                    cursor: None,
                    anchor: None,
                    page_size: PageSize::new(2).expect("size"),
                    filters: lomo_application::MemoFilters {
                        tag: Some("reading".to_owned()),
                        tag_selection: lomo_application::TagSelectionMode::Subtree,
                        date_from_inclusive_ms: Some(start),
                        date_until_exclusive_ms: Some(end),
                        ..lomo_application::MemoFilters::default()
                    },
                })
                .expect("filtered search");
            let SearchOutcome::Ready(page) = outcome else {
                panic!("current query");
            };
            assert_eq!(page.items.len(), 1);
            assert_eq!(page.items[0].summary.source_path, "2026_09_11.md");
        }
    }

    #[test]
    fn fuzzy_cursor_survives_a_view_epoch_change_but_rejects_changed_filters() {
        let ctx = open_session();
        for index in 10..14 {
            create(
                &ctx,
                &format!("resume-{index}"),
                &format!("2026_09_{index}.md"),
                "10:00:00",
                "needle #reading",
                None,
            );
        }
        let mut request = SearchRequest {
            query_epoch: 1,
            mode: SearchMode::Fuzzy,
            text: "needle".to_owned(),
            cursor: None,
            anchor: None,
            page_size: PageSize::new(2).expect("size"),
            filters: lomo_application::MemoFilters::default(),
        };
        let SearchOutcome::Ready(first) = ctx.session.search(&request).expect("first page") else {
            panic!("current query");
        };
        request.cursor = first.next_cursor;
        request.query_epoch = 2;
        let SearchOutcome::Ready(second) = ctx
            .session
            .search(&request)
            .expect("returning to same query")
        else {
            panic!("current query");
        };
        assert_eq!(second.items.len(), 2);
        assert!(
            second
                .items
                .iter()
                .all(|hit| first.items.iter().all(|old| old.memo_id != hit.memo_id))
        );
        request.filters.tag = Some("reading".to_owned());
        let error = ctx
            .session
            .search(&request)
            .expect_err("different filters cannot reuse a cursor");
        assert!(error.to_string().contains("stale_cursor"));
    }

    #[test]
    fn fuzzy_search_rejects_oversized_queries_and_malformed_tags_at_the_boundary() {
        let ctx = open_session();
        let mut request = SearchRequest {
            query_epoch: 1,
            mode: SearchMode::Fuzzy,
            text: "x".repeat(4097),
            cursor: None,
            anchor: None,
            page_size: PageSize::new(2).expect("size"),
            filters: lomo_application::MemoFilters::default(),
        };
        let error = ctx.session.search(&request).expect_err("oversized query");
        assert!(error.to_string().contains("query_too_long"));
        request.text = "needle".to_owned();
        request.filters.tag = Some("two tags".to_owned());
        let error = ctx.session.search(&request).expect_err("malformed tag");
        assert!(error.to_string().contains("invalid_tag"));
    }

    #[test]
    fn search_evidence_locates_body_path_and_pinyin_matches() {
        let ctx = open_session();
        create(
            &ctx,
            "excerpt",
            "2026_09_11.md",
            "10:00:00",
            &format!("{} 八达岭长城 needle", "前文 ".repeat(300)),
            None,
        );
        for (epoch, mode, text, source) in [
            (
                1,
                SearchMode::Fulltext,
                "needle",
                lomo_application::search_excerpt::MatchSource::Body,
            ),
            (
                2,
                SearchMode::Fuzzy,
                "bdlcc",
                lomo_application::search_excerpt::MatchSource::Pinyin,
            ),
            (
                3,
                SearchMode::Fuzzy,
                "2026_09_11",
                lomo_application::search_excerpt::MatchSource::Path,
            ),
        ] {
            let SearchOutcome::Ready(page) = ctx
                .session
                .search(&SearchRequest {
                    query_epoch: epoch,
                    mode,
                    text: text.to_owned(),
                    cursor: None,
                    anchor: None,
                    page_size: PageSize::new(2).expect("size"),
                    filters: lomo_application::MemoFilters::default(),
                })
                .expect("search")
            else {
                panic!("current query");
            };
            let excerpt = &page.items[0].excerpt;
            assert_eq!(excerpt.source, source);
            assert!(
                !excerpt.highlights.is_empty(),
                "match must identify real text: {excerpt:?}"
            );
            for range in &excerpt.highlights {
                assert!(excerpt.text.get(range.clone()).is_some());
            }
            if source != lomo_application::search_excerpt::MatchSource::Path {
                assert!(excerpt.body_start.is_some_and(|offset| offset > 256));
            }
        }
    }

    #[test]
    fn fuzzy_continuation_rejects_a_changed_projection_snapshot() {
        let ctx = open_session();
        for index in 10..14 {
            create(
                &ctx,
                &format!("snapshot-{index}"),
                &format!("2026_09_{index}.md"),
                "10:00:00",
                "needle",
                None,
            );
        }
        let mut request = SearchRequest {
            query_epoch: 1,
            mode: SearchMode::Fuzzy,
            text: "needle".to_owned(),
            cursor: None,
            anchor: None,
            page_size: PageSize::new(2).expect("size"),
            filters: lomo_application::MemoFilters::default(),
        };
        let SearchOutcome::Ready(page) = ctx.session.search(&request).expect("first page") else {
            panic!("current query");
        };
        request.cursor = page.next_cursor;
        create(
            &ctx,
            "snapshot-new",
            "2026_09_15.md",
            "10:00:00",
            "needle new",
            None,
        );
        let error = ctx
            .session
            .search(&request)
            .expect_err("projection changed between pages");
        assert!(error.to_string().contains("stale_cursor"));
    }

    #[test]
    fn fuzzy_result_cache_evicts_the_least_recently_used_entry_at_capacity() {
        // Test Change Justification: `cache_fuzzy` now carries the scored needle and filter
        // fingerprint (the prefix-derivation evidence); the calls below are the same inserts
        // with the metadata the entry genuinely belongs to.
        use lomo_application::search::{
            FUZZY_CACHE_ENTRIES, FuzzyResult, cache_fuzzy, cached_fuzzy,
        };
        use std::sync::Mutex;

        let cache: Mutex<lru::LruCache<(String, u64), FuzzyResult>> =
            Mutex::new(lru::LruCache::new(FUZZY_CACHE_ENTRIES));
        for query in ["q1", "q2", "q3", "q4", "q5"] {
            cache_fuzzy(
                &cache,
                query.to_owned(),
                7,
                query.to_owned(),
                "filters".to_owned(),
                Vec::new(),
            )
            .expect("cache insert");
        }
        // The fifth insert overfills the bound — the oldest entry leaves.
        assert_eq!(cached_queries(&cache), ["q5", "q4", "q3", "q2"]);

        cached_fuzzy(&cache, "q2", 7)
            .expect("cache read")
            .expect("q2 remains memoized");
        cache_fuzzy(
            &cache,
            "q6".to_owned(),
            7,
            "q6".to_owned(),
            "filters".to_owned(),
            Vec::new(),
        )
        .expect("cache insert");

        // A hit promotes: q3 — not the re-read q2 — is the next victim.
        assert_eq!(cached_queries(&cache), ["q6", "q2", "q5", "q4"]);
    }

    #[test]
    fn fuzzy_prefix_derivation_rescores_only_prior_hits_and_matches_a_fresh_scan() {
        let ctx = open_session();
        // The needle chain narrows purely on body content: no `z` ever appears
        // in the `2026_09_*.md` source paths.
        create(&ctx, "z1", "2026_09_10.md", "10:00:00", "zoo meadow", None);
        create(&ctx, "z2", "2026_09_11.md", "10:00:00", "zap maple", None);
        create(&ctx, "z3", "2026_09_12.md", "10:00:00", "cozy unit", None);
        create(&ctx, "z4", "2026_09_13.md", "10:00:00", "lazy calm", None);
        create(&ctx, "z5", "2026_09_14.md", "10:00:00", "plain water", None);

        let fuzzy = |epoch: u64, text: &str| {
            let outcome = ctx
                .session
                .search(&SearchRequest {
                    query_epoch: epoch,
                    mode: SearchMode::Fuzzy,
                    text: text.to_owned(),
                    cursor: None,
                    anchor: None,
                    page_size: PageSize::new(16).expect("size"),
                    filters: lomo_application::MemoFilters::default(),
                })
                .expect("fuzzy");
            let SearchOutcome::Ready(page) = outcome else {
                panic!("current query must be ready");
            };
            page
        };
        let key = |page: &lomo_application::SearchPage| {
            page.items
                .iter()
                .map(|hit| (hit.memo_id.clone(), hit.score, hit.excerpt.clone()))
                .collect::<Vec<_>>()
        };

        let baseline = ctx.session.fuzzy_scan_stats().expect("stats");
        let wide = fuzzy(1, "z");
        assert_eq!(wide.total, 4, "z hits every z-bearing body");
        let stats = ctx.session.fuzzy_scan_stats().expect("stats");
        assert_eq!(stats.full_scans - baseline.full_scans, 1);
        assert_eq!(stats.corpus_builds - baseline.corpus_builds, 1);
        assert_eq!(
            stats.candidates_scored - baseline.candidates_scored,
            5,
            "the first keystroke still pays the whole corpus"
        );

        let mid = fuzzy(2, "za");
        assert_eq!(mid.total, 3);
        let stats = ctx.session.fuzzy_scan_stats().expect("stats");
        assert_eq!(stats.seeded_scans - baseline.seeded_scans, 1);
        assert_eq!(stats.corpus_builds - baseline.corpus_builds, 1);
        assert_eq!(
            stats.candidates_scored - baseline.candidates_scored,
            9,
            "an extending keystroke rescores only the prior hit set (5 + 4)"
        );

        let deep = fuzzy(3, "zap");
        assert_eq!(deep.total, 1);
        assert_eq!(
            ctx.session
                .fuzzy_scan_stats()
                .expect("stats")
                .candidates_scored
                - baseline.candidates_scored,
            12,
            "the deepest step pays only the 'za' hits (5 + 4 + 3)"
        );

        // "ZAP" folds to the same needle but is not a textual prefix of any
        // cached entry — a fresh full scan that must equal the seeded order.
        let fresh = fuzzy(4, "ZAP");
        assert_eq!(
            key(&fresh),
            key(&deep),
            "incremental derivation must equal a fresh full scan"
        );
        let stats = ctx.session.fuzzy_scan_stats().expect("stats");
        assert_eq!(stats.full_scans - baseline.full_scans, 2);
        assert_eq!(stats.seeded_scans - baseline.seeded_scans, 2);

        // A repeated query is a snapshot hit — no scan work at all.
        let again = fuzzy(5, "zap");
        assert_eq!(key(&again), key(&deep));
        let stats = ctx.session.fuzzy_scan_stats().expect("stats");
        assert_eq!(stats.snapshot_hits - baseline.snapshot_hits, 1);
        assert_eq!(stats.candidates_scored - baseline.candidates_scored, 17);
    }

    #[test]
    fn fuzzy_scan_cancellation_is_fail_safe_and_poaches_nothing() {
        let ctx = open_session();
        for index in 0..6 {
            create(
                &ctx,
                &format!("s{index}"),
                &format!("2026_09_{}.md", 10 + index),
                "10:00:00",
                "zesty note",
                None,
            );
        }
        let filters = lomo_application::MemoFilters::default();

        // Warm the corpus so the injected cancellation lands inside the
        // scoring loop — deterministic, no threads or clocks.
        let built =
            lomo_application::search::fuzzy_snapshot(&ctx.session, "z", &filters, &|| false)
                .expect("warm build");
        assert_eq!(built.hits().len(), 6);

        let checks = std::sync::atomic::AtomicUsize::new(0);
        let cancel = || checks.fetch_add(1, std::sync::atomic::Ordering::Relaxed) >= 2;
        let error = lomo_application::search::fuzzy_snapshot(&ctx.session, "ze", &filters, &cancel)
            .expect_err("a superseded scan fails closed");
        assert_eq!(error.category(), lomo_core::ErrorCategory::Cancelled);
        let stats = ctx.session.fuzzy_scan_stats().expect("stats");
        assert_eq!(stats.superseded_scans, 1);
        assert_eq!(stats.seeded_scans, 0, "a cancelled scan is never counted");
        assert_eq!(
            stats.candidates_scored, 8,
            "six warm scores + two partial scores before the drop"
        );

        // Nothing torn was cached: the same needle re-runs and derives from
        // the "z" seed exactly once.
        let retried =
            lomo_application::search::fuzzy_snapshot(&ctx.session, "ze", &filters, &|| false)
                .expect("retry");
        assert_eq!(retried.hits().len(), 6);
        let stats = ctx.session.fuzzy_scan_stats().expect("stats");
        assert_eq!(stats.seeded_scans, 1);

        // The same fail-safe guards the corpus build's paged walk: cancelling
        // before the first page leaves no corpus behind.
        let cold = open_session();
        create(&cold, "cold", "2026_09_10.md", "10:00:00", "zesty", None);
        let error =
            lomo_application::search::fuzzy_snapshot(&cold.session, "z", &filters, &|| true)
                .expect_err("a cancelled build fails closed");
        assert_eq!(error.category(), lomo_core::ErrorCategory::Cancelled);
        let stats = cold.session.fuzzy_scan_stats().expect("stats");
        assert_eq!(stats.superseded_scans, 1);
        assert_eq!(stats.corpus_builds, 0);
    }

    #[test]
    fn task_list_is_built_once_per_projection_revision() {
        let ctx = open_session();
        create(&ctx, "t1", "2026_09_10.md", "10:00:00", "- [ ] alpha", None);
        create(&ctx, "t2", "2026_09_11.md", "10:00:00", "- [x] beta", None);

        let first = ctx.session.list_tasks().expect("tasks");
        assert_eq!(first.len(), 2);
        let probe = ctx.session.projection_query_stats().expect("probe");
        assert_eq!(probe.task_builds, 1);
        assert_eq!(probe.summary_walks, 1);

        let second = ctx.session.list_tasks().expect("cached tasks");
        assert_eq!(first, second);
        let probe = ctx.session.projection_query_stats().expect("probe");
        assert_eq!(probe.task_builds, 1, "re-entry reuses the built view");
        assert_eq!(probe.task_hits, 1);
        assert_eq!(
            probe.summary_walks, 1,
            "a cached task list never re-walks SQL"
        );

        // A committed write bumps the revision — the next entry rebuilds.
        create(&ctx, "t3", "2026_09_12.md", "10:00:00", "- [ ] gamma", None);
        let third = ctx.session.list_tasks().expect("rebuilt tasks");
        assert_eq!(third.len(), 3);
        assert!(third.iter().any(|task| task.text == "gamma"));
        let probe = ctx.session.projection_query_stats().expect("probe");
        assert_eq!(probe.task_builds, 2);
        assert_eq!(probe.summary_walks, 2);
    }

    #[test]
    fn task_hydration_reuses_unchanged_memos_after_unrelated_commits() {
        let ctx = open_session();
        create(
            &ctx,
            "tasks-a",
            "2026_09_10.md",
            "10:00:00",
            "- [ ] alpha",
            None,
        );
        create(
            &ctx,
            "tasks-b",
            "2026_09_11.md",
            "10:00:00",
            "- [x] beta",
            None,
        );
        let initial = ctx.session.list_tasks().expect("initial task view");
        assert_eq!(
            ctx.session
                .projection_query_stats()
                .expect("stats")
                .task_memos_hydrated,
            2
        );
        create(
            &ctx,
            "plain",
            "2026_09_12.md",
            "10:00:00",
            "no checkboxes",
            None,
        );
        assert_eq!(
            ctx.session
                .list_tasks()
                .expect("tasks after unrelated write"),
            initial
        );
        assert_eq!(
            ctx.session
                .projection_query_stats()
                .expect("stats")
                .task_memos_hydrated,
            2,
            "an unrelated commit must not re-hydrate existing task bodies"
        );
        create(
            &ctx,
            "tasks-c",
            "2026_09_13.md",
            "10:00:00",
            "- [ ] gamma",
            None,
        );
        let next = ctx.session.list_tasks().expect("new task view");
        assert_eq!(next.len(), 3);
        assert!(next.iter().any(|task| task.text == "gamma"));
        assert_eq!(
            ctx.session
                .projection_query_stats()
                .expect("stats")
                .task_memos_hydrated,
            3,
            "only the new memo body needs hydration"
        );
        let alpha = next
            .iter()
            .find(|task| task.text == "alpha")
            .expect("alpha");
        ctx.session
            .toggle_task(ToggleTaskRequest {
                operation_id: op("toggle-cached-alpha"),
                memo_id: lomo_workspace::MemoId::parse(&alpha.memo_id).expect("memo id"),
                line_index: alpha.line_index,
                done: true,
            })
            .expect("toggle");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        let updated = ctx.session.list_tasks().expect("updated task view");
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
        assert!(
            updated
                .iter()
                .find(|task| task.text == "alpha")
                .expect("updated alpha")
                .done
        );
        assert_eq!(
            ctx.session
                .projection_query_stats()
                .expect("stats")
                .task_memos_hydrated,
            4,
            "a task edit must refresh exactly its changed memo"
        );
    }

    /// Iteration order is most- to least-recently-used — the tail is the next
    /// eviction victim.
    fn cached_queries(
        cache: &std::sync::Mutex<
            lru::LruCache<(String, u64), lomo_application::search::FuzzyResult>,
        >,
    ) -> Vec<String> {
        cache
            .lock()
            .expect("fuzzy cache lock")
            .iter()
            .map(|((fingerprint, _), _)| fingerprint.clone())
            .collect()
    }
}
