// adversarial-audit: reconcile cost must be proportional to the change set, not the
// library, and a scoped reconcile must produce the same projection a fresh full
// materialize produces from the same durable facts.
//!
//! Invariant under test:
//! - `rebuild_projection` detects the file-level delta between the current listing and
//!   the snapshot persisted with the last projection commit, then re-parses only the
//!   documents and re-walks only the `.lomo` sub-graphs those paths can affect.
//! - `reconcile_observed_paths` accepts the watcher's changed-path evidence and applies
//!   the same scoped pass; anything whose scope cannot be proven falls back to the full
//!   scan, which remains the truth rebuilder.
//! - Either way the result is indistinguishable from a sibling session that materialized
//!   the same workspace from scratch.
//!
//! Observable evidence: a counting `PlatformActionExecutor` records every
//! `ReadToExchange`; a scoped reconcile of one document must keep that count bounded by
//! the document's own fact set, independent of total library size.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "adversarial audit tests fail closed with panics on missing facts"
)]
mod tests {
    use std::{
        collections::BTreeSet,
        fs,
        path::{Path, PathBuf},
        sync::{
            Arc,
            atomic::{AtomicUsize, Ordering},
        },
    };

    use lomo_application::{
        MemoFilters, MemoQuery, MemoSort, WorkspaceSession, WorkspaceSessionConfig,
    };
    use lomo_core::{
        CapabilityToken, PageSize, PlatformAction, PlatformActionBatch, PlatformActionExecutor,
        PlatformBatchResult, RelativeWorkspacePath,
    };
    use lomo_platform_fs::FsPlatformActionExecutor;
    use lomo_workspace::{MemoId, WorkspaceGenerationId, WorkspaceRootId};
    use tempfile::tempdir;

    /// Counts platform reads so reconcile cost is observable without timing.
    struct CountingExecutor {
        inner: Arc<FsPlatformActionExecutor>,
        reads: AtomicUsize,
    }

    impl CountingExecutor {
        fn reads(&self) -> usize {
            self.reads.load(Ordering::SeqCst)
        }
        fn reset(&self) {
            self.reads.store(0, Ordering::SeqCst);
        }
    }

    impl PlatformActionExecutor for CountingExecutor {
        fn execute(
            &self,
            batch: &PlatformActionBatch,
        ) -> Result<PlatformBatchResult, lomo_core::LomoError> {
            for action in batch.actions() {
                if matches!(action, PlatformAction::ReadToExchange { .. }) {
                    self.reads.fetch_add(1, Ordering::SeqCst);
                }
            }
            self.inner.execute(batch)
        }
    }

    struct Ctx {
        session: WorkspaceSession,
        workspace: PathBuf,
        counting: Arc<CountingExecutor>,
        _dirs: Vec<tempfile::TempDir>,
    }

    /// Private fixture directories are positional; the index is a fixture slot, not data.
    fn fixture_dir(dirs: &[tempfile::TempDir], index: usize) -> &Path {
        dirs.get(index).expect("fixture dir slot").path()
    }

    fn open_session(workspace: &Path) -> Ctx {
        let dirs = (0..6)
            .map(|_| tempdir().expect("fixture dir"))
            .collect::<Vec<_>>();
        let workspace_path = if workspace.as_os_str().is_empty() {
            fixture_dir(&dirs, 0).to_path_buf()
        } else {
            workspace.to_path_buf()
        };
        let real = Arc::new(FsPlatformActionExecutor::new(fixture_dir(&dirs, 4)).expect("exec"));
        let capability = CapabilityToken::parse("notes").expect("cap");
        real.bind_root(capability.clone(), &workspace_path)
            .expect("bind");
        let counting = Arc::new(CountingExecutor {
            inner: real,
            reads: AtomicUsize::new(0),
        });
        let executor: Arc<dyn PlatformActionExecutor> = Arc::<CountingExecutor>::clone(&counting);
        let session = WorkspaceSession::open(
            WorkspaceSessionConfig {
                capability,
                root_id: WorkspaceRootId::Notes,
                workspace_generation: WorkspaceGenerationId::mint().expect("generation"),
                time_zone: "UTC".to_owned(),
                date_format: lomo_application::calendar::DateFormat::default(),
                state_dir: fixture_dir(&dirs, 1).to_path_buf(),
                cache_dir: fixture_dir(&dirs, 2).to_path_buf(),
                runtime_dir: fixture_dir(&dirs, 3).to_path_buf(),
                exchange_dir: fixture_dir(&dirs, 4).to_path_buf(),
                media_stage_root: fixture_dir(&dirs, 5).to_path_buf(),
            },
            executor,
        )
        .expect("open");
        Ctx {
            session,
            workspace: workspace_path,
            counting,
            _dirs: dirs,
        }
    }

    fn try_open_session(workspace: &Path) -> Result<Ctx, lomo_core::LomoError> {
        let dirs = (0..6)
            .map(|_| tempdir().expect("fixture dir"))
            .collect::<Vec<_>>();
        let workspace_path = if workspace.as_os_str().is_empty() {
            fixture_dir(&dirs, 0).to_path_buf()
        } else {
            workspace.to_path_buf()
        };
        let real = Arc::new(FsPlatformActionExecutor::new(fixture_dir(&dirs, 4))?);
        let capability = CapabilityToken::parse("notes")?;
        real.bind_root(capability.clone(), &workspace_path)?;
        let counting = Arc::new(CountingExecutor {
            inner: real,
            reads: AtomicUsize::new(0),
        });
        let executor: Arc<dyn PlatformActionExecutor> = Arc::<CountingExecutor>::clone(&counting);
        let session = WorkspaceSession::open(
            WorkspaceSessionConfig {
                capability,
                root_id: WorkspaceRootId::Notes,
                workspace_generation: WorkspaceGenerationId::mint()?,
                time_zone: "UTC".to_owned(),
                date_format: lomo_application::calendar::DateFormat::default(),
                state_dir: fixture_dir(&dirs, 1).to_path_buf(),
                cache_dir: fixture_dir(&dirs, 2).to_path_buf(),
                runtime_dir: fixture_dir(&dirs, 3).to_path_buf(),
                exchange_dir: fixture_dir(&dirs, 4).to_path_buf(),
                media_stage_root: fixture_dir(&dirs, 5).to_path_buf(),
            },
            executor,
        )?;
        Ok(Ctx {
            session,
            workspace: workspace_path,
            counting,
            _dirs: dirs,
        })
    }

    /// Opens a second, independent session on the same workspace: a fresh private store
    /// means its open can only materialize from durable facts — the equivalence oracle.
    /// The returned fixture keeps its private directories alive for the comparison.
    fn fresh_oracle(workspace: &Path) -> Ctx {
        try_open_session(workspace).expect("oracle session")
    }

    /// `memos_per_doc` memos in each of `docs` dated documents. Day-of-month rolls over into
    /// the next month so large libraries still produce valid calendar dates.
    fn seed_docs(workspace: &Path, docs: usize, memos_per_doc: usize) {
        use std::fmt::Write;
        for doc in 0..docs {
            let mut text = String::new();
            for index in 0..memos_per_doc {
                write!(
                    text,
                    "- 10:{:02}:{:02}\nmemo {doc}.{index} #tag{doc}\n\n",
                    index / 60,
                    index % 60
                )
                .expect("seed write");
            }
            fs::write(
                workspace.join(format!("2026_{:02}_{:02}.md", 9 + doc / 20, 10 + doc % 20)),
                text,
            )
            .expect("seed doc");
        }
    }

    fn summaries(
        session: &WorkspaceSession,
        trash_only: bool,
    ) -> Vec<lomo_application::MemoSummary> {
        let query = MemoQuery {
            search_text: None,
            filters: MemoFilters {
                trash_only,
                ..MemoFilters::default()
            },
            sort: MemoSort::default(),
        };
        let mut items = Vec::new();
        let mut cursor = None;
        loop {
            let page = session
                .query_memos_page(
                    &query,
                    None,
                    cursor.as_ref(),
                    PageSize::new(256).expect("ps"),
                )
                .expect("summary page");
            items.extend(page.items);
            match page.next_cursor {
                Some(next) => cursor = Some(next),
                None => break,
            }
        }
        items
    }

    /// The complete observable projection: memo rows, bodies, lifecycle bits, pins,
    /// history windows, attachment references, tasks and aggregate state. Two
    /// projections are equivalent exactly when this dump is equal.
    fn dump(session: &WorkspaceSession) -> String {
        use std::fmt::Write;
        let mut out = String::new();
        for trash_only in [false, true] {
            for summary in summaries(session, trash_only) {
                let snapshot = session
                    .projected_memo(&summary.memo_id)
                    .expect("snapshot")
                    .expect("projected memo");
                write!(
                    out,
                    "{}|{}|{}|rev{}|p{}|t{}|{:?}|",
                    summary.memo_id,
                    summary.file_fingerprint,
                    summary.source_path,
                    summary.content_revision,
                    u8::from(summary.is_pinned),
                    u8::from(summary.is_trashed),
                    snapshot.body,
                )
                .expect("dump");
                let memo_id = MemoId::parse(&summary.memo_id).expect("memo id");
                let history = session.list_history(&memo_id, None, 64).expect("history");
                for rev in &history.items {
                    write!(
                        out,
                        "h{}:{}:{}|",
                        rev.revision, rev.record_id, rev.file_fingerprint
                    )
                    .expect("dump");
                }
            }
        }
        for item in session.observe_attachments().expect("attachment index") {
            write!(
                out,
                "att:{}:{}:{:?};",
                item.owner_key, item.relative_path, item.source
            )
            .expect("dump");
        }
        let sidebar = session.sidebar_projection().expect("sidebar");
        for tag in &sidebar.tag_counts {
            write!(out, "tag:{}:{};", tag.name, tag.count).expect("dump");
        }
        for task in session.list_tasks().expect("tasks") {
            write!(
                out,
                "task:{}:{}:{};",
                task.memo_id, task.line_index, task.done
            )
            .expect("dump");
        }
        out
    }

    /// The result of the same workspace under a scoped reconcile (the live session) must
    /// equal a sibling session that could only fully materialize the durable facts.
    fn assert_equivalent_to_fresh_scan(session: &WorkspaceSession, workspace: &Path) {
        let oracle = fresh_oracle(workspace);
        assert_eq!(
            dump(session),
            dump(&oracle.session),
            "incremental projection must match a fresh full materialize"
        );
    }

    /// Appends one new memo block without touching existing block bytes: the only
    /// external document modification whose memo identities stay resolvable, because
    /// durable identity binds to block-content hashes.
    fn append_memo(path: &Path, time: &str, body: &str) {
        use std::fmt::Write;
        let mut text = fs::read_to_string(path).expect("read doc");
        write!(text, "- {time}\n{body}\n\n").expect("append text");
        fs::write(path, text).expect("append memo");
    }

    #[test]
    fn modified_document_matches_fresh_materialize_with_bounded_reads() {
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 6, 4);
        let ctx = open_session(scratch.path());
        ctx.session.rebuild_projection().expect("seed rebuild");

        append_memo(
            &ctx.workspace.join("2026_09_12.md"),
            "10:04:00",
            "memo 2.4 added #tag2",
        );

        ctx.counting.reset();
        ctx.session.rebuild_projection().expect("reconcile");
        let reads = ctx.counting.reads();
        assert_equivalent_to_fresh_scan(&ctx.session, &ctx.workspace);
        assert!(
            reads <= 24,
            "one modified document must not re-parse the library: {reads} file reads",
        );
    }

    #[test]
    fn deleted_document_matches_fresh_materialize() {
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 6, 4);
        let ctx = open_session(scratch.path());
        ctx.session.rebuild_projection().expect("seed rebuild");

        fs::remove_file(ctx.workspace.join("2026_09_12.md")).expect("delete doc");

        ctx.counting.reset();
        ctx.session.rebuild_projection().expect("reconcile");
        let reads = ctx.counting.reads();
        assert_equivalent_to_fresh_scan(&ctx.session, &ctx.workspace);
        assert!(
            reads <= 24,
            "one deleted document must not re-parse the library: {reads} file reads",
        );
    }

    #[test]
    fn added_document_matches_fresh_materialize() {
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 6, 4);
        let ctx = open_session(scratch.path());
        ctx.session.rebuild_projection().expect("seed rebuild");

        fs::write(
            ctx.workspace.join("2026_10_01.md"),
            "- 10:00:00\nnew memo a\n\n- 10:01:00\nnew memo b\n\n",
        )
        .expect("add doc");

        ctx.counting.reset();
        ctx.session.rebuild_projection().expect("reconcile");
        let reads = ctx.counting.reads();
        assert_equivalent_to_fresh_scan(&ctx.session, &ctx.workspace);
        assert!(
            reads <= 32,
            "one added document must not re-parse the library: {reads} file reads",
        );
    }

    #[test]
    fn observed_paths_reconcile_matches_fresh_materialize() {
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 6, 4);
        let ctx = open_session(scratch.path());
        ctx.session.rebuild_projection().expect("seed rebuild");

        append_memo(
            &ctx.workspace.join("2026_09_13.md"),
            "10:04:00",
            "memo 3.4 appended",
        );

        ctx.counting.reset();
        ctx.session
            .reconcile_observed_paths(&[
                RelativeWorkspacePath::parse("2026_09_13.md").expect("path")
            ])
            .expect("scoped reconcile");
        let reads = ctx.counting.reads();
        assert_equivalent_to_fresh_scan(&ctx.session, &ctx.workspace);
        assert!(
            reads <= 24,
            "one reported path must scope the reconcile: {reads} file reads",
        );
    }

    #[test]
    fn unchanged_reconcile_reads_no_documents() {
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 6, 4);
        let ctx = open_session(scratch.path());
        ctx.session.rebuild_projection().expect("seed rebuild");

        ctx.counting.reset();
        ctx.session.rebuild_projection().expect("idle reconcile");
        // A fixed-layout probe may read one durable marker; documents and record payloads
        // must never be re-read when the listing digest already matches the projection.
        assert!(
            ctx.counting.reads() <= 2,
            "an unchanged listing must not read document or record bytes: {}",
            ctx.counting.reads()
        );
    }

    #[test]
    fn scoped_reconcile_cost_does_not_grow_with_library_size() {
        for docs in [4_usize, 40] {
            let scratch = tempdir().expect("workspace");
            seed_docs(scratch.path(), docs, 4);
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed rebuild");
            append_memo(
                &ctx.workspace.join("2026_09_10.md"),
                "10:04:00",
                "memo 0.4 appended",
            );
            ctx.counting.reset();
            ctx.session.rebuild_projection().expect("reconcile");
            assert!(
                ctx.counting.reads() <= 24,
                "{docs} documents in library, one changed: {} reads must stay bounded by the change",
                ctx.counting.reads()
            );
        }
    }

    #[test]
    fn unscoped_lomo_path_falls_back_to_full_scan() {
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 6, 4);
        let ctx = open_session(scratch.path());
        ctx.session.rebuild_projection().expect("seed rebuild");

        let unknown = ctx.workspace.join(".lomo/unknown-layout/blob.bin");
        fs::create_dir_all(unknown.parent().expect("parent")).expect("dir");
        fs::write(&unknown, b"opaque").expect("opaque record");

        ctx.counting.reset();
        ctx.session.rebuild_projection().expect("reconcile");
        // An unclassifiable `.lomo` change must re-verify the whole workspace rather
        // than guess a scope: the read count covers every document again.
        assert!(
            ctx.counting.reads() >= 6,
            "a blocked scope must run the full scan: {} reads",
            ctx.counting.reads()
        );
        assert_equivalent_to_fresh_scan(&ctx.session, &ctx.workspace);
    }

    #[test]
    fn deleted_state_object_fails_like_a_cold_rebuild() {
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 4, 4);
        let ctx = open_session(scratch.path());
        ctx.session.rebuild_projection().expect("seed rebuild");
        let summaries = summaries(&ctx.session, false);
        let memo =
            MemoId::parse(&summaries.first().expect("seeded summary").memo_id).expect("memo id");
        ctx.session
            .pin_memo(
                lomo_application::PinMemoRequest::new(
                    lomo_core::OperationId::parse("pin-op").expect("op"),
                    memo,
                    lomo_application::PinPolicy::Pinned { at_ms: None },
                )
                .expect("pin request"),
            )
            .expect("pin");

        // Delete one durable state object: the owner cannot be proven from the path
        // alone, so the scoped pass must refuse and the full scan must surface the same
        // corruption a cold rebuild surfaces — never a half-updated projection.
        let objects = ctx.workspace.join(".lomo/state/v2/objects");
        let mut removed = BTreeSet::new();
        for entry in fs::read_dir(&objects).expect("read objects") {
            let path = entry.expect("entry").path();
            fs::remove_file(&path).expect("remove state object");
            removed.insert(path);
        }
        assert_eq!(removed.len(), 1, "pin writes exactly one state object");

        let outcome = ctx.session.rebuild_projection();
        let oracle = try_open_session(&ctx.workspace);
        match (outcome, oracle) {
            (Ok(_), Ok(oracle)) => assert_eq!(dump(&ctx.session), dump(&oracle.session)),
            (Err(error), Err(cold)) => {
                assert_eq!(
                    error.code(),
                    cold.code(),
                    "a live reconcile and a cold rebuild must surface the same corruption"
                );
            }
            (Ok(_), Err(cold)) => {
                panic!("live reconcile succeeded where a cold rebuild rejects: {cold:?}")
            }
            (Err(error), Ok(oracle)) => panic!(
                "live reconcile failed ({error:?}) where a cold rebuild accepts: {}",
                dump(&oracle.session)
            ),
        }
    }
}
