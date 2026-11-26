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
        cache_path: std::path::PathBuf,
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
        let cache_path = cache.path().to_path_buf();
        let config = WorkspaceSessionConfig {
            capability,
            root_id: WorkspaceRootId::Notes,
            workspace_generation: lomo_workspace::WorkspaceGenerationId::mint()
                .expect("workspace generation"),
            time_zone: "UTC".to_owned(),
            date_format: lomo_application::calendar::DateFormat::default(),
            state_dir: state.path().to_path_buf(),
            cache_dir: cache_path.clone(),
            runtime_dir: runtime.path().to_path_buf(),
            exchange_dir: exchange.path().to_path_buf(),
            media_stage_root: exchange.path().join("media-stage"),
        };
        let session = WorkspaceSession::open(config, executor).expect("open");
        Ctx {
            workspace_path: workspace.path().to_path_buf(),
            cache_path,
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
                date_format: None,
                timestamp_format: None,
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
    fn imported_long_utf8_memo_indexes_without_truncating_the_body() {
        let ctx = open_session();
        let body = format!("{}月末目标", "界".repeat(150_000));
        let source = lomo_workspace::SourceBytes::try_from_str(&body).expect("body source");
        let presentation = lomo_workspace::render_markdown(&source)
            .expect_err("body exceeds the presentation string budget");
        assert_eq!(presentation.code(), "ir_string_too_large");

        std::fs::write(
            ctx.workspace_path.join("2026_11_09.md"),
            format!("- 09:00:00\n{body}\n\n- 10:00:00\n- [ ] neighbouring memo\n"),
        )
        .expect("import");

        let indexed = ctx.session.rebuild_projection().expect("index import");
        assert_eq!(indexed.memos_indexed, 2);
        let rows = ctx
            .session
            .list_memos(&lomo_store::MemoQuery {
                search_text: None,
                filters: lomo_store::MemoFilters::default(),
                sort: lomo_store::MemoSort::default(),
            })
            .expect("memo list");
        let imported = ctx
            .session
            .projected_memo(&rows.items[1].memo_id)
            .expect("projected memo")
            .expect("imported memo");
        assert_eq!(imported.body.as_bytes(), body.as_bytes());
        let result = ctx
            .session
            .search(&SearchRequest {
                mode: SearchMode::Fuzzy,
                query_epoch: 1,
                text: "月末目标".to_owned(),
                sort: lomo_application::MemoSort::default(),
                cursor: None,
                anchor: None,
                backward: false,
                page_size: PageSize::new(1).expect("page"),
                filters: lomo_application::MemoFilters::default(),
            })
            .expect("search past the preview");
        let SearchOutcome::Ready(page) = result else {
            panic!("fresh search is ready")
        };
        let excerpt = &page.items.first().expect("long memo match").excerpt;
        assert!(excerpt.text.ends_with("月末目标"));
        assert_eq!(excerpt.body_start, Some((150_000 - 60) * '界'.len_utf8()));
        let tasks = ctx.session.list_tasks().expect("tasks");
        assert_eq!(tasks[0].text, "neighbouring memo");
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
                date_format: None,
                timestamp_format: None,
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
                sort: lomo_application::MemoSort::default(),
                query_epoch: 1,
                mode: SearchMode::Fuzzy,
                text: "zzzz".to_owned(),
                cursor: None,
                anchor: None,
                backward: false,
                page_size: PageSize::new(16).expect("page"),
            })
            .expect("stale search");
        let fresh = ctx
            .session
            .search(&SearchRequest {
                filters: lomo_application::MemoFilters::default(),
                sort: lomo_application::MemoSort::default(),
                query_epoch: 2,
                mode: SearchMode::Fuzzy,
                text: "bdlcc".to_owned(),
                cursor: None,
                anchor: None,
                backward: false,
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
                sort: lomo_application::MemoSort::default(),
                query_epoch: 1,
                mode: SearchMode::Fuzzy,
                text: "zzzz".to_owned(),
                cursor: None,
                anchor: None,
                backward: false,
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
                sort: lomo_application::MemoSort::default(),
                query_epoch: 3,
                mode: SearchMode::Fulltext,
                text: "八达岭".to_owned(),
                cursor: None,
                anchor: None,
                backward: false,
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
                    sort: lomo_application::MemoSort::default(),
                    query_epoch: 1,
                    mode: SearchMode::Fuzzy,
                    text: "needle".to_owned(),
                    cursor,
                    anchor: None,
                    backward: false,
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
                date_format: None,
                timestamp_format: None,
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
                    sort: lomo_application::MemoSort::default(),
                    cursor: None,
                    anchor: None,
                    backward: false,
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
            sort: lomo_application::MemoSort::default(),
            cursor: None,
            anchor: None,
            backward: false,
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
            sort: lomo_application::MemoSort::default(),
            cursor: None,
            anchor: None,
            backward: false,
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
                    sort: lomo_application::MemoSort::default(),
                    cursor: None,
                    anchor: None,
                    backward: false,
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
            sort: lomo_application::MemoSort::default(),
            cursor: None,
            anchor: None,
            backward: false,
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

    /*
     * Test Change Justification:
     * Reason category: Architecture cutover (in-memory corpus scan + FuzzyResult LRU replaced by Tantivy).
     * Old behavior/assertion being replaced: Asserted internal `FUZZY_CACHE_ENTRIES` LRU eviction,
     *   prefix-seeded candidate rescoring (`fuzzy_scan_stats`), and `fuzzy_snapshot` cancellation.
     * Why old assertion is no longer correct: `WorkspaceSession::search` delegates both `Fulltext` and
     *   `Fuzzy` to Tantivy + Charabia in `lomo-store` with lazy index sync and zero write overhead;
     *   `FuzzyResult`, `FUZZY_CACHE_ENTRIES`, and `FuzzyScanStats` no longer exist.
     * Coverage preserved by: Verifying lazy zero-write overhead before first search, `warmup_search_index`
     *   and incremental changelog sync counters via `search_index_stats`, `Fulltext` vs `Fuzzy` mode
     *   capabilities (Traditional/Simplified, typo edit distance, within-term subsequence, heteronym
     *   pinyin), `search_window_sides`, and stale epoch discarding.
     * Why this is not fitting the test to the implementation: Directly tests observable search contracts.
     */
    #[test]
    fn lazy_tantivy_warmup_syncs_incrementally_and_preserves_zero_write_overhead() {
        let ctx = open_session();
        create(
            &ctx,
            "lazy-1",
            "2026_09_10.md",
            "10:00:00",
            "開發筆記",
            None,
        );
        let search_dir = ctx
            .cache_path
            .join(lomo_store::SQLITE_DIR_NAME)
            .join("search");
        assert!(
            !ctx.session.search_index_stats().is_loaded,
            "writes must never eagerly load Tantivy"
        );
        assert!(
            !search_dir.exists(),
            "writes must never eagerly materialize search directory"
        );

        let warmed = ctx.session.warmup_search_index().expect("warmup");
        assert!(warmed.is_loaded);
        assert_eq!(warmed.doc_count, 1);
        assert_eq!(warmed.full_rebuilds, 1);
        assert_eq!(warmed.incremental_syncs, 0);

        create(
            &ctx,
            "lazy-2",
            "2026_09_11.md",
            "11:00:00",
            "重庆长城",
            None,
        );
        let outcome = ctx
            .session
            .search(&SearchRequest {
                query_epoch: 1,
                mode: SearchMode::Fuzzy,
                text: "zq".to_owned(),
                sort: lomo_application::MemoSort::default(),
                cursor: None,
                anchor: None,
                backward: false,
                page_size: PageSize::new(10).expect("page"),
                filters: lomo_application::MemoFilters::default(),
            })
            .expect("incremental sync on search");
        let SearchOutcome::Ready(page) = outcome else {
            panic!("expected ready page");
        };
        assert_eq!(page.total, 1);
        let after = ctx.session.search_index_stats();
        assert_eq!(after.doc_count, 2);
        assert_eq!(after.full_rebuilds, 1);
        assert_eq!(after.incremental_syncs, 1);
    }

    #[test]
    fn unified_tantivy_modes_enforce_fulltext_strictness_and_fuzzy_recall() {
        let ctx = open_session();
        create(
            &ctx,
            "u-1",
            "2026_09_10.md",
            "10:00:00",
            "needle 開發筆記",
            None,
        );

        let full_ndle = ctx
            .session
            .search(&SearchRequest {
                query_epoch: 1,
                mode: SearchMode::Fulltext,
                text: "ndle".to_owned(),
                sort: lomo_application::MemoSort::default(),
                cursor: None,
                anchor: None,
                backward: false,
                page_size: PageSize::new(10).expect("page"),
                filters: lomo_application::MemoFilters::default(),
            })
            .expect("fulltext ndle");
        let SearchOutcome::Ready(full_ndle) = full_ndle else {
            panic!("ready");
        };
        assert_eq!(full_ndle.total, 0);

        let full_sc = ctx
            .session
            .search(&SearchRequest {
                query_epoch: 2,
                mode: SearchMode::Fulltext,
                text: "开发".to_owned(),
                sort: lomo_application::MemoSort::default(),
                cursor: None,
                anchor: None,
                backward: false,
                page_size: PageSize::new(10).expect("page"),
                filters: lomo_application::MemoFilters::default(),
            })
            .expect("fulltext simplified");
        let SearchOutcome::Ready(full_sc) = full_sc else {
            panic!("ready");
        };
        assert_eq!(full_sc.total, 1);
        assert_eq!(
            full_sc.items[0].excerpt.source,
            lomo_application::search_excerpt::MatchSource::Body
        );

        for (epoch, query_text) in [(3, "ndle"), (4, "nedle")] {
            let outcome = ctx
                .session
                .search(&SearchRequest {
                    query_epoch: epoch,
                    mode: SearchMode::Fuzzy,
                    text: query_text.to_owned(),
                    sort: lomo_application::MemoSort::default(),
                    cursor: None,
                    anchor: None,
                    backward: false,
                    page_size: PageSize::new(10).expect("page"),
                    filters: lomo_application::MemoFilters::default(),
                })
                .expect("fuzzy match");
            let SearchOutcome::Ready(page) = outcome else {
                panic!("ready");
            };
            assert_eq!(page.total, 1, "fuzzy query '{query_text}' must match");
            assert_eq!(
                page.items[0].excerpt.source,
                lomo_application::search_excerpt::MatchSource::Body
            );
        }

        let all_ids: Vec<String> = full_sc
            .items
            .iter()
            .map(|hit| hit.memo_id.clone())
            .collect();
        let sides = ctx
            .session
            .search_window_sides(
                &lomo_application::MemoQuery {
                    search_text: Some("开发".to_owned()),
                    filters: lomo_application::MemoFilters::default(),
                    sort: lomo_application::MemoSort::default(),
                },
                SearchMode::Fulltext,
                &all_ids,
                None,
                None,
            )
            .expect("window sides");
        assert!(sides.above.is_empty() && sides.below.is_empty());
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

    #[test]
    fn render_memo_previews_preserves_order_and_isolates_mismatch_and_not_found() {
        let ctx = open_session();
        std::fs::create_dir_all(ctx.workspace_path.join("images")).expect("images dir");
        std::fs::write(ctx.workspace_path.join("images/a.png"), b"png-bytes").expect("image");
        create(
            &ctx,
            "op-prev-1",
            "2026_10_12.md",
            "10:00:00",
            "#notes 中文 ![p](images/a.png)",
            None,
        );
        let first_doc = fs_read_doc(&ctx.workspace_path, "2026_10_12.md");
        create(
            &ctx,
            "op-prev-2",
            "2026_10_12.md",
            "10:01:00",
            "- [ ] buy milk",
            Some(first_doc),
        );
        let page = ctx
            .session
            .list_memos(&lomo_application::MemoQuery {
                search_text: None,
                filters: lomo_application::MemoFilters::default(),
                sort: lomo_application::MemoSort::default(),
            })
            .expect("list memos");
        assert_eq!(page.items.len(), 2);
        let m1 = page
            .items
            .iter()
            .find(|item| item.body_preview.contains("中文"))
            .expect("first memo");
        let m2 = page
            .items
            .iter()
            .find(|item| item.body_preview.contains("buy milk"))
            .expect("second memo");

        let outcomes = ctx
            .session
            .render_memo_previews(
                &[
                    lomo_application::MemoPreviewRenderRequest {
                        memo_id: m1.memo_id.clone(),
                        expected_content_revision: m1.content_revision,
                    },
                    lomo_application::MemoPreviewRenderRequest {
                        memo_id: "missing-preview".to_owned(),
                        expected_content_revision: 1,
                    },
                    lomo_application::MemoPreviewRenderRequest {
                        memo_id: m2.memo_id.clone(),
                        expected_content_revision: m2.content_revision + 1,
                    },
                ],
                lomo_workspace::RENDER_DOCUMENT_SCHEMA_V1,
            )
            .expect("mixed batch");
        let [rendered, missing, stale] = outcomes.as_slice() else {
            panic!("expected three outcomes, got {}", outcomes.len());
        };
        match rendered {
            lomo_application::MemoPreviewRenderOutcome::Rendered {
                memo_id,
                content_revision,
                source_byte_length,
                document,
            } => {
                assert_eq!(memo_id, &m1.memo_id);
                assert_eq!(*content_revision, m1.content_revision);
                assert_eq!(*source_byte_length, m1.body_preview.len() as u64);
                assert_eq!(document.tag_names(), &["notes".to_owned()]);
            }
            other @ (lomo_application::MemoPreviewRenderOutcome::NotFound { .. }
            | lomo_application::MemoPreviewRenderOutcome::RevisionMismatch { .. }) => {
                panic!("expected Rendered at index 0, got {other:?}");
            }
        }
        assert_eq!(
            *missing,
            lomo_application::MemoPreviewRenderOutcome::NotFound {
                memo_id: "missing-preview".to_owned(),
            }
        );
        assert_eq!(
            *stale,
            lomo_application::MemoPreviewRenderOutcome::RevisionMismatch {
                memo_id: m2.memo_id.clone(),
                actual_content_revision: m2.content_revision,
            }
        );
    }

    #[test]
    fn render_memo_previews_validates_batch_inputs() {
        let ctx = open_session();
        assert_eq!(
            ctx.session
                .render_memo_previews(&[], lomo_workspace::RENDER_DOCUMENT_SCHEMA_V1)
                .expect("empty batch")
                .len(),
            0
        );
        let schema_err = ctx
            .session
            .render_memo_previews(&[], 2)
            .expect_err("unknown schema must fail closed");
        assert_eq!(schema_err.code(), "unknown_render_schema");

        let oversized: Vec<_> = (0..=lomo_application::MAX_PREVIEW_RENDER_BATCH_SIZE)
            .map(|_| lomo_application::MemoPreviewRenderRequest {
                memo_id: "preview-limit".to_owned(),
                expected_content_revision: 1,
            })
            .collect();
        let limit_err = ctx
            .session
            .render_memo_previews(&oversized, lomo_workspace::RENDER_DOCUMENT_SCHEMA_V1)
            .expect_err("oversized batch must fail closed");
        assert_eq!(limit_err.code(), "preview_render_batch_too_large");

        let blank_err = ctx
            .session
            .render_memo_previews(
                &[lomo_application::MemoPreviewRenderRequest {
                    memo_id: "   ".to_owned(),
                    expected_content_revision: 1,
                }],
                lomo_workspace::RENDER_DOCUMENT_SCHEMA_V1,
            )
            .expect_err("blank memo_id must fail closed");
        assert_eq!(blank_err.code(), "invalid_memo_id");
    }

    fn fs_read_doc(root: &std::path::Path, rel: &str) -> String {
        let bytes = std::fs::read(root.join(rel)).expect("read doc");
        lomo_workspace::SourceFingerprint::of_bytes(&bytes)
            .as_str()
            .to_owned()
    }

    fn ready_search(
        session: &WorkspaceSession,
        request: &SearchRequest,
    ) -> lomo_application::SearchPage {
        let outcome = session.search(request).expect("search must succeed");
        let SearchOutcome::Ready(page) = outcome else {
            panic!("expected ready search page");
        };
        *page
    }

    fn simple_search_req(
        query_epoch: u64,
        mode: SearchMode,
        text: &str,
        filters: lomo_application::MemoFilters,
        page_size: u32,
    ) -> SearchRequest {
        SearchRequest {
            query_epoch,
            mode,
            text: text.to_owned(),
            filters,
            sort: lomo_application::MemoSort::default(),
            cursor: None,
            anchor: None,
            backward: false,
            page_size: PageSize::new(page_size).expect("page"),
        }
    }

    #[test]
    fn search_excerpts_handle_punctuation_long_fuzzy_tokens_path_stems_and_heteronyms() {
        let ctx = open_session();
        let long_word = format!("prefix_{}q{}z", "x".repeat(80), "y".repeat(20));
        create(
            &ctx,
            "ex-long",
            "2026_09_11.md",
            "10:00:00",
            &format!("{long_word} #project/alpha"),
            None,
        );
        let doc_fp = fs_read_doc(&ctx.workspace_path, "2026_09_11.md");
        create(
            &ctx,
            "ex-pinyin",
            "2026_09_11.md",
            "10:01:00",
            "重庆火锅",
            Some(doc_fp),
        );

        let project_filter = || lomo_application::MemoFilters {
            tag: Some("project".to_owned()),
            tag_selection: lomo_application::TagSelectionMode::Subtree,
            ..lomo_application::MemoFilters::default()
        };
        for (epoch, mode) in [(1, SearchMode::Fulltext), (2, SearchMode::Fuzzy)] {
            let page = ready_search(
                &ctx.session,
                &simple_search_req(epoch, mode, "!!!", project_filter(), 10),
            );
            assert_eq!(page.items.len(), 1);
            let first = page.items.first().expect("punctuation hit");
            assert_eq!(
                first.excerpt.source,
                lomo_application::search_excerpt::MatchSource::Body
            );
            assert_eq!(
                first.excerpt.highlights,
                Vec::<std::ops::Range<usize>>::new()
            );
        }

        let long_hit = ready_search(
            &ctx.session,
            &simple_search_req(
                3,
                SearchMode::Fuzzy,
                "qz",
                lomo_application::MemoFilters::default(),
                10,
            ),
        );
        assert_eq!(long_hit.items.len(), 1);
        let long_first = long_hit.items.first().expect("long fuzzy hit");
        assert_eq!(
            long_first.excerpt.source,
            lomo_application::search_excerpt::MatchSource::Body
        );
        assert_ne!(
            long_first.excerpt.highlights,
            Vec::<std::ops::Range<usize>>::new()
        );

        let path_hit = ready_search(
            &ctx.session,
            &simple_search_req(
                4,
                SearchMode::Fuzzy,
                "2026_09_12",
                lomo_application::MemoFilters::default(),
                10,
            ),
        );
        assert_eq!(path_hit.items.len(), 2);
        let path_first = path_hit.items.first().expect("path hit");
        assert_eq!(
            path_first.excerpt.source,
            lomo_application::search_excerpt::MatchSource::Path
        );
        assert_ne!(
            path_first.excerpt.highlights,
            Vec::<std::ops::Range<usize>>::new()
        );

        let pinyin_hit = ready_search(
            &ctx.session,
            &simple_search_req(
                5,
                SearchMode::Fuzzy,
                "zongqing",
                lomo_application::MemoFilters::default(),
                10,
            ),
        );
        assert_eq!(pinyin_hit.items.len(), 1);
        let pinyin_first = pinyin_hit.items.first().expect("pinyin hit");
        assert_eq!(
            pinyin_first.excerpt.source,
            lomo_application::search_excerpt::MatchSource::Pinyin
        );
        assert_ne!(
            pinyin_first.excerpt.highlights,
            Vec::<std::ops::Range<usize>>::new()
        );
    }

    #[test]
    fn search_supports_anchor_and_backward_cursor_paging() {
        let ctx = open_session();
        for idx in 10..15 {
            create(
                &ctx,
                &format!("pg-{idx}"),
                &format!("2026_09_{idx}.md"),
                "10:00:00",
                "needle common",
                None,
            );
        }

        for (base_epoch, mode) in [(1, SearchMode::Fulltext), (10, SearchMode::Fuzzy)] {
            let p1 = ready_search(
                &ctx.session,
                &simple_search_req(
                    base_epoch,
                    mode,
                    "needle",
                    lomo_application::MemoFilters::default(),
                    2,
                ),
            );
            let p2 = ready_search(
                &ctx.session,
                &SearchRequest {
                    cursor: p1.next_cursor,
                    ..simple_search_req(
                        base_epoch + 1,
                        mode,
                        "needle",
                        lomo_application::MemoFilters::default(),
                        2,
                    )
                },
            );
            let prev_cursor = p2.prev_cursor.clone().expect("prev_cursor");
            let back = ready_search(
                &ctx.session,
                &SearchRequest {
                    cursor: Some(prev_cursor),
                    backward: true,
                    ..simple_search_req(
                        base_epoch + 2,
                        mode,
                        "needle",
                        lomo_application::MemoFilters::default(),
                        2,
                    )
                },
            );
            assert_eq!(
                back.items
                    .iter()
                    .map(|hit| hit.memo_id.as_str())
                    .collect::<Vec<_>>(),
                p1.items
                    .iter()
                    .map(|hit| hit.memo_id.as_str())
                    .collect::<Vec<_>>()
            );

            let anchor_id = p2.items.first().expect("page 2 head").memo_id.clone();
            let anchored = ready_search(
                &ctx.session,
                &SearchRequest {
                    anchor: Some(anchor_id.clone()),
                    ..simple_search_req(
                        base_epoch + 3,
                        mode,
                        "needle",
                        lomo_application::MemoFilters::default(),
                        2,
                    )
                },
            );
            assert_eq!(anchored.items_before, 2);
            assert_eq!(
                anchored.items.first().expect("anchored head").memo_id,
                anchor_id
            );
        }

        let err = ctx
            .session
            .search(&SearchRequest {
                query_epoch: 99,
                mode: SearchMode::Fulltext,
                text: "needle".to_owned(),
                filters: lomo_application::MemoFilters::default(),
                sort: lomo_application::MemoSort::default(),
                cursor: None,
                anchor: None,
                backward: true,
                page_size: PageSize::new(2).expect("page"),
            })
            .expect_err("backward without cursor must fail closed");
        assert_eq!(err.code(), "invalid_search_start");
    }

    #[test]
    fn session_read_paths_compact_consumed_search_changelog() {
        use std::fmt::Write as _;

        let ctx = open_session();
        let mut doc_body = String::new();
        for idx in 0..1_106_u32 {
            let hh = idx / 3600;
            let mm = (idx % 3600) / 60;
            let ss = idx % 60;
            writeln!(doc_body, "- {hh:02}:{mm:02}:{ss:02}\nitem {idx}\n").expect("write item");
        }
        std::fs::write(ctx.workspace_path.join("2026_09_10.md"), doc_body).expect("seed bulk doc");
        ctx.session.rebuild_projection().expect("rebuild 1106 rows");

        let db_path = lomo_store::database_path(&ctx.cache_path);
        let inspect = rusqlite::Connection::open(&db_path).expect("open store db");
        let before_count: i64 = inspect
            .query_row("SELECT COUNT(*) FROM search_changelog", [], |row| {
                row.get(0)
            })
            .expect("count before");
        assert_eq!(before_count, 1_106);

        let warmed = ctx.session.warmup_search_index().expect("session warmup");
        assert_eq!(warmed.doc_count, 1_106);
        assert_eq!(warmed.full_rebuilds, 1);

        let after_count: i64 = inspect
            .query_row("SELECT COUNT(*) FROM search_changelog", [], |row| {
                row.get(0)
            })
            .expect("count after");
        let max_seq: i64 = inspect
            .query_row(
                "SELECT COALESCE(MAX(seq), 0) FROM search_changelog",
                [],
                |row| row.get(0),
            )
            .expect("max seq");
        assert_eq!(after_count, 1);
        assert_eq!(max_seq, 1_106);

        create(
            &ctx,
            "post-compact",
            "2026_09_11.md",
            "10:00:00",
            "compaction sentinel works",
            None,
        );
        let SearchOutcome::Ready(hit) = ctx
            .session
            .search(&SearchRequest {
                query_epoch: 1,
                mode: SearchMode::Fulltext,
                text: "compaction".to_owned(),
                filters: lomo_application::MemoFilters::default(),
                sort: lomo_application::MemoSort::default(),
                cursor: None,
                anchor: None,
                backward: false,
                page_size: PageSize::new(5).expect("page"),
            })
            .expect("incremental search after compaction")
        else {
            panic!("ready");
        };
        assert_eq!(hit.total, 1);
        let stats = ctx.session.search_index_stats();
        assert_eq!(stats.full_rebuilds, 1);
        assert_eq!(stats.incremental_syncs, 1);
    }

    fn edit_memo_several_times(session: &WorkspaceSession, target_id: &str, count: u32) {
        let memo_id = lomo_workspace::MemoId::parse(target_id).expect("memo id");
        for edit_idx in 1..=count {
            let snap = session
                .projected_memo(target_id)
                .expect("projected")
                .expect("memo exists");
            session
                .update_memo(lomo_application::UpdateMemoRequest {
                    operation_id: op(&format!("rb-edit-{edit_idx}")),
                    memo_id: memo_id.clone(),
                    content: format!("needle edited version {edit_idx}"),
                    expected_document_fingerprint: snap.summary.file_fingerprint,
                    pending_promotes: Vec::new(),
                })
                .expect("update memo");
            session.settle_workspace().expect("settle edit");
        }
    }

    #[test]
    fn search_cursor_is_rejected_after_derived_search_cache_rebuild_at_same_revision() {
        let ctx = open_session();
        create(
            &ctx,
            "rb-1",
            "2026_09_10.md",
            "10:00:00",
            "needle alpha",
            None,
        );
        create(
            &ctx,
            "rb-2",
            "2026_09_11.md",
            "10:00:00",
            "needle beta x",
            None,
        );
        create(
            &ctx,
            "rb-3",
            "2026_09_12.md",
            "10:00:00",
            "needle gamma x y",
            None,
        );
        ctx.session.warmup_search_index().expect("initial warmup");

        let no_filters = lomo_application::MemoFilters::default;
        let target_id = ctx
            .session
            .query_memos_page(
                &lomo_application::MemoQuery {
                    search_text: None,
                    filters: no_filters(),
                    sort: lomo_application::MemoSort::default(),
                },
                None,
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("list memos")
            .items
            .first()
            .expect("seeded memo")
            .memo_id
            .clone();
        edit_memo_several_times(&ctx.session, &target_id, 3);

        let exact_page = ready_search(
            &ctx.session,
            &simple_search_req(1, SearchMode::Fulltext, "needle", no_filters(), 1),
        );
        let fuzzy_page = ready_search(
            &ctx.session,
            &simple_search_req(2, SearchMode::Fuzzy, "needle", no_filters(), 1),
        );
        let exact_cursor = exact_page.next_cursor.expect("fulltext next_cursor");
        let fuzzy_cursor = fuzzy_page.next_cursor.expect("fuzzy next_cursor");
        let stats_before = ctx.session.search_index_stats();

        let search_dir = ctx
            .cache_path
            .join(lomo_store::SQLITE_DIR_NAME)
            .join("search");
        std::fs::remove_dir_all(&search_dir).expect("simulate search cache loss");

        for (epoch, mode, cursor) in [
            (3_u64, SearchMode::Fulltext, exact_cursor),
            (4_u64, SearchMode::Fuzzy, fuzzy_cursor),
        ] {
            let err = ctx
                .session
                .search(&SearchRequest {
                    cursor: Some(cursor),
                    ..simple_search_req(epoch, mode, "needle", no_filters(), 1)
                })
                .expect_err("cursor from deleted index snapshot must fail closed");
            assert_eq!(err.code(), "stale_cursor");
        }

        let stats_after = ctx.session.search_index_stats();
        assert_eq!(
            stats_after.high_water_revision,
            stats_before.high_water_revision
        );
        assert_eq!(stats_after.full_rebuilds, stats_before.full_rebuilds + 1);

        let mut seen = std::collections::BTreeSet::new();
        let mut cursor = None;
        for step in 0..3_u64 {
            let page = ready_search(
                &ctx.session,
                &SearchRequest {
                    cursor,
                    ..simple_search_req(10 + step, SearchMode::Fulltext, "needle", no_filters(), 1)
                },
            );
            let id = page.items.first().expect("walk item").memo_id.clone();
            assert!(seen.insert(id), "fresh walk must not duplicate items");
            cursor = page.next_cursor;
        }
        assert_eq!(seen.len(), 3);
    }
}
