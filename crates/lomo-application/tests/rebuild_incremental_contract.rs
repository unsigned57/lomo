//! Behavior Contract:
//! Capability: bounded, verified scan I/O; owner: lomo-application; priority: P1.
//! Scenarios:
//! - Given many memos in one dated document, when first indexing, then history chains overlap
//!   within the same eight-lane budget and all memo/history facts match a fresh scan.
//! - Given a failed memo history write, siblings drain before failure, no partial rows publish,
//!   and reopening the same private projection recovers all 24 memos.
//!   RED: reports/manual/trash-index-picker/index-red.log (memo histories stayed serial).
//!   GREEN: reports/manual/trash-index-picker/index-green.log.
//! - Given existing history, when a fresh projection indexes it, then each immutable object
//!   is read once while canonical heads still receive a separate freshness observation.
//! - Given independent Markdown documents, when indexing, then source reads share bounded
//!   batches and the projection equals a fresh scan, including memo bodies and history.
//! - Given blocked identity writes in independent documents, when indexing, then at most
//!   eight document chains overlap and all finish before the complete projection is exposed.
//! - Given one rejected identity write, when sibling chains run, then all workers finish,
//!   the original error surfaces, no partial projection is published, and a retry recovers.
//!
//! - Given twelve externally changed documents, when a scoped rebuild runs, then independent
//!   identity writes overlap through the same bounded lane and all 36 memos publish together.
//!   RED/GREEN: reports/workspace-scaling/incremental-parallel-*.log.
//! - Given existing heads and tips, when rebuilding, then graph admission batches their reads
//!   instead of issuing two singleton platform callbacks for every memo.
//!
//! TDD proof (history batching): workspace-scaling/history-red.log (48 singleton batches),
//! workspace-scaling/history-green.log; command: `cargo test -p lomo-application --locked`
//! `--test rebuild_incremental_contract cold_rebuild_batches_existing_heads_and_tips`.
//! Bulk metadata control: first import of new large record directories uses bulk observation,
//! while small edits to large directories use exact metadata targets. Neither weakens byte CAS.
//! RED/GREEN: workspace-scaling/bulk-metadata-*.log.
//! Complete inventory control: a 12-note delta in 120 existing heads still uses exact targets;
//! RED/GREEN: workspace-scaling/complete-parent-inventory-*.log.
//! Post-write observation budget: each directory starts enumeration once per dirty reconcile;
//! verified writes refresh only their own metadata, with unchanged cold projection outcomes.
//! RED/GREEN: workspace-scaling/metadata-inspect-*.log.
//! Own-write presence control: verified heads created during document scan override the old
//! listing's absence; RED/GREEN: workspace-scaling/own-write-presence-*.log.
//! Inventory absence proof: complete listings prevent optional tombstone/state-head reads for
//! records absent from that same listing. RED/GREEN: workspace-scaling/absence-*.log.
//! Observable outcomes: platform source reads and complete projection equivalence.
//! TDD proof: RED/GREEN evidence in reports/path-optimization/scan-io-*.log.
//! Concurrent scan proof: reports/path-optimization-round2/concurrent-*.log.
//! Excludes: device latency, concurrent external changes after the final head observation.
//!
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
//! the document's own fact set, independent of total library size. Deleted durable
//! records are scoped when their authority still resolves — an unclaimed state object,
//! a hash-named identity map — and known inert `.lomo` control families never provoke
//! a fact re-read, while anything unproven still falls back to the full scan. The same
//! executor records the widest all-`ListChildren` batch: the recursive walk must fan
//! independent directory chains into shared bounded batches instead of one batch per
//! directory.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "adversarial audit tests fail closed with panics on missing facts"
)]
mod tests {
    use std::{
        collections::{BTreeMap, BTreeSet},
        fs,
        path::{Path, PathBuf},
        sync::{
            Arc, Condvar, Mutex,
            atomic::{AtomicBool, AtomicUsize, Ordering},
            mpsc,
        },
        time::Duration,
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
        /// Widest all-`ListChildren` batch submitted so far — the fan-out evidence for the
        /// bounded-parallel enumeration contract.
        max_listing_batch: AtomicUsize,
        max_history_read_batch: AtomicUsize,
        history_object_reads: AtomicUsize,
        singleton_history_batches: AtomicUsize,
        tombstone_reads: AtomicUsize,
        state_head_reads: AtomicUsize,
        listing_starts: Mutex<BTreeMap<String, usize>>,
        metadata_inspections: AtomicUsize,
        max_markdown_read_batch: AtomicUsize,
    }

    impl CountingExecutor {
        fn reads(&self) -> usize {
            self.reads.load(Ordering::SeqCst)
        }
        fn max_listing_batch(&self) -> usize {
            self.max_listing_batch.load(Ordering::SeqCst)
        }
        fn reset(&self) {
            self.reads.store(0, Ordering::SeqCst);
            self.tombstone_reads.store(0, Ordering::SeqCst);
            self.state_head_reads.store(0, Ordering::SeqCst);
            self.listing_starts.lock().expect("listing counts").clear();
            self.metadata_inspections.store(0, Ordering::SeqCst);
            self.max_listing_batch.store(0, Ordering::SeqCst);
        }
    }

    impl PlatformActionExecutor for CountingExecutor {
        fn execute(
            &self,
            batch: &PlatformActionBatch,
        ) -> Result<PlatformBatchResult, lomo_core::LomoError> {
            if !batch.actions().is_empty()
                && batch
                    .actions()
                    .iter()
                    .all(|action| matches!(action, PlatformAction::ListChildren { .. }))
            {
                self.max_listing_batch
                    .fetch_max(batch.actions().len(), Ordering::SeqCst);
            }
            let history_reads = batch
                .actions()
                .iter()
                .filter(|action| {
                    matches!(action, PlatformAction::Read { path, .. }
                    if path.as_str().starts_with(".lomo/history/"))
                })
                .count();
            if history_reads == 1 && batch.actions().len() == 1 {
                self.singleton_history_batches
                    .fetch_add(1, Ordering::SeqCst);
            }
            self.max_history_read_batch
                .fetch_max(history_reads, Ordering::SeqCst);
            let markdown_reads = batch.actions().iter().filter(|action| {
                matches!(action, PlatformAction::Read { path, .. } if Path::new(path.as_str()).extension().is_some_and(|ext| ext.eq_ignore_ascii_case("md")))
            }).count();
            self.max_markdown_read_batch
                .fetch_max(markdown_reads, Ordering::SeqCst);
            self.metadata_inspections.fetch_add(
                batch
                    .actions()
                    .iter()
                    .filter(|action| matches!(action, PlatformAction::Inspect { .. }))
                    .count(),
                Ordering::SeqCst,
            );
            for action in batch.actions() {
                if let PlatformAction::ListChildren {
                    target,
                    cursor: None,
                    ..
                } = action
                {
                    let parent = match target {
                        lomo_core::WorkspaceTarget::Root => "/",
                        lomo_core::WorkspaceTarget::Relative(path) => path.as_str(),
                    };
                    *self
                        .listing_starts
                        .lock()
                        .expect("listing counts")
                        .entry(parent.to_owned())
                        .or_default() += 1;
                }
            }
            for action in batch.actions() {
                if let PlatformAction::Read { path, .. } = action {
                    if path.as_str().starts_with(".lomo/history/v2/tombstones/") {
                        self.tombstone_reads.fetch_add(1, Ordering::SeqCst);
                    }
                    if path.as_str().starts_with(".lomo/state/v2/heads/") {
                        self.state_head_reads.fetch_add(1, Ordering::SeqCst);
                    }
                }
                if matches!(action, PlatformAction::Read { path, .. }
                    if path.as_str().starts_with(".lomo/history/v2/objects/"))
                {
                    self.history_object_reads.fetch_add(1, Ordering::SeqCst);
                }
                if matches!(
                    action,
                    PlatformAction::ReadToExchange { .. } | PlatformAction::Read { .. }
                ) {
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
        /// The session's projection root — `store_meta` telemetry counters are
        /// readable here through a second `Store::open_projection` handle.
        cache_dir: PathBuf,
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
            max_listing_batch: AtomicUsize::new(0),
            max_history_read_batch: AtomicUsize::new(0),
            history_object_reads: AtomicUsize::new(0),
            singleton_history_batches: AtomicUsize::new(0),
            tombstone_reads: AtomicUsize::new(0),
            state_head_reads: AtomicUsize::new(0),
            listing_starts: Mutex::new(BTreeMap::new()),
            metadata_inspections: AtomicUsize::new(0),
            max_markdown_read_batch: AtomicUsize::new(0),
        });
        let executor: Arc<dyn PlatformActionExecutor> = Arc::<CountingExecutor>::clone(&counting);
        let cache_dir = fixture_dir(&dirs, 2).to_path_buf();
        let session = WorkspaceSession::open(
            WorkspaceSessionConfig {
                capability,
                root_id: WorkspaceRootId::Notes,
                workspace_generation: WorkspaceGenerationId::mint().expect("generation"),
                time_zone: "UTC".to_owned(),
                date_format: lomo_application::calendar::DateFormat::default(),
                state_dir: fixture_dir(&dirs, 1).to_path_buf(),
                cache_dir: cache_dir.clone(),
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
            cache_dir,
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
            max_listing_batch: AtomicUsize::new(0),
            max_history_read_batch: AtomicUsize::new(0),
            history_object_reads: AtomicUsize::new(0),
            singleton_history_batches: AtomicUsize::new(0),
            tombstone_reads: AtomicUsize::new(0),
            state_head_reads: AtomicUsize::new(0),
            listing_starts: Mutex::new(BTreeMap::new()),
            metadata_inspections: AtomicUsize::new(0),
            max_markdown_read_batch: AtomicUsize::new(0),
        });
        let executor: Arc<dyn PlatformActionExecutor> = Arc::<CountingExecutor>::clone(&counting);
        let cache_dir = fixture_dir(&dirs, 2).to_path_buf();
        let session = WorkspaceSession::open(
            WorkspaceSessionConfig {
                capability,
                root_id: WorkspaceRootId::Notes,
                workspace_generation: WorkspaceGenerationId::mint()?,
                time_zone: "UTC".to_owned(),
                date_format: lomo_application::calendar::DateFormat::default(),
                state_dir: fixture_dir(&dirs, 1).to_path_buf(),
                cache_dir: cache_dir.clone(),
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
            cache_dir,
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
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");

        // Delete one durable state object: the owner cannot be proven from the path
        // alone, so the scoped pass must refuse and the full scan must surface the same
        // corruption a cold rebuild surfaces — never a half-updated projection.
        let objects = ctx.workspace.join(".lomo/state/v2/objects");
        let mut removed = BTreeSet::new();
        ctx.session
            .settle_workspace()
            .expect("committed writes converge");
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

    #[test]
    fn sync_control_tree_writes_never_reach_the_listing_diff() {
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 4, 4);
        let ctx = open_session(scratch.path());
        ctx.session.rebuild_projection().expect("seed rebuild");

        // `.lomo/sync` is transport control state — peer heads, cursors, inflight
        // records. None of it is a memo document or a supported durable record, so a
        // sync write must leave the committed listing digest and the reconcile cost
        // untouched: the diff baseline never sees these paths at all.
        let sync_dir = ctx.workspace.join(".lomo/sync/v1");
        fs::create_dir_all(&sync_dir).expect("sync dir");
        fs::write(sync_dir.join("peer-a-head.rec"), b"peer-head").expect("sync record");
        fs::write(sync_dir.join("cursor.bin"), b"cursor").expect("sync blob");
        fs::write(ctx.workspace.join(".lomo/sync/tmp-partial"), b"partial").expect("sync tmp");

        ctx.counting.reset();
        ctx.session.rebuild_projection().expect("reconcile");
        assert!(
            ctx.counting.reads() <= 2,
            "sync churn must not re-read the workspace: {} reads",
            ctx.counting.reads()
        );
        assert_equivalent_to_fresh_scan(&ctx.session, &ctx.workspace);
    }

    #[test]
    fn observed_sync_paths_are_inert_for_scoped_reconcile() {
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 4, 4);
        let ctx = open_session(scratch.path());
        ctx.session.rebuild_projection().expect("seed rebuild");

        let sync_dir = ctx.workspace.join(".lomo/sync/v1");
        fs::create_dir_all(&sync_dir).expect("sync dir");
        fs::write(sync_dir.join("peer-b-head.rec"), b"peer-head").expect("sync record");

        ctx.counting.reset();
        ctx.session
            .reconcile_observed_paths(&[RelativeWorkspacePath::parse(
                ".lomo/sync/v1/peer-b-head.rec",
            )
            .expect("path")])
            .expect("scoped reconcile");
        assert!(
            ctx.counting.reads() <= 2,
            "a watcher-reported sync path must not scope or re-read the workspace: {} reads",
            ctx.counting.reads()
        );
        assert_equivalent_to_fresh_scan(&ctx.session, &ctx.workspace);
    }

    /// The reconcile fallback/skip instrumentation is observable state: a pass that
    /// re-materialized the projection bumps `reconcile_fallback_total`, every committed
    /// pass adds the `.lomo/sync` subtrees its enumeration excluded to
    /// `reconcile_skipped_sync_v1_total`, and a digest-matched no-op commits nothing.
    #[test]
    fn reconcile_telemetry_counts_fallbacks_and_skipped_sync_subtrees() {
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 3, 2);
        let sync_dir = scratch.path().join(".lomo/sync/v1");
        fs::create_dir_all(&sync_dir).expect("sync dir");
        fs::write(sync_dir.join("peer-head.rec"), b"peer-head").expect("sync record");

        // First mount has no committed listing baseline, so it must materialize —
        // that is exactly the fallback the counter exists to measure.
        let ctx = open_session(scratch.path());
        let counters = |ctx: &Ctx| {
            lomo_store::Store::open_projection(&ctx.cache_dir)
                .and_then(|store| store.reconcile_telemetry())
                .expect("telemetry read")
        };
        let (fallbacks, skipped) = counters(&ctx);
        assert_eq!(fallbacks, 1, "first mount materializes once");
        assert_eq!(skipped, 1, "the .lomo/sync subtree was skipped once");

        // A digest-matched reconcile stays write-free: telemetry never moves.
        ctx.session.rebuild_projection().expect("no-op reconcile");
        assert_eq!(counters(&ctx), (fallbacks, skipped));

        // A real document change commits a scoped pass that excluded the same sync
        // subtree again: the skip counter accumulates without a new fallback.
        append_memo(
            &ctx.workspace.join("2026_09_10.md"),
            "10:04:00",
            "memo added #tag0",
        );
        ctx.session.rebuild_projection().expect("reconcile");
        assert_eq!(
            counters(&ctx),
            (fallbacks, skipped + 1),
            "committed reconcile counts the skipped subtree, no new fallback"
        );
        assert_equivalent_to_fresh_scan(&ctx.session, &ctx.workspace);
    }

    #[test]
    fn deleted_identity_record_recreates_scoped() {
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 6, 4);
        let ctx = open_session(scratch.path());
        ctx.session.rebuild_projection().expect("seed rebuild");

        // Delete exactly one identity map: the hashed record name provably belongs to one
        // source document, so only that document is reparsed and the map rewritten — never
        // a library-wide scan.
        let identity_dir = ctx.workspace.join(".lomo/identity/v1");
        let record = fs::read_dir(&identity_dir)
            .expect("identity dir")
            .next()
            .expect("one identity record")
            .expect("entry")
            .path();
        fs::remove_file(&record).expect("remove identity record");

        ctx.counting.reset();
        ctx.session.rebuild_projection().expect("reconcile");
        let reads = ctx.counting.reads();
        // The rewrite is genuinely scoped to the owning document: its four memos re-mint
        // identities, retire the old ones, and write fresh initial chains — reads scale
        // with that one document (~8 per memo), never with the six-document library.
        assert!(
            reads <= 40,
            "a provable identity-record removal must stay scoped: {reads} file reads",
        );
        assert_eq!(
            fs::read_dir(&identity_dir).expect("identity dir").count(),
            6,
            "the scoped pass must rewrite the deleted identity record"
        );
        assert_equivalent_to_fresh_scan(&ctx.session, &ctx.workspace);
    }

    #[test]
    fn deleted_identity_record_with_deleted_document_stays_scoped() {
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 6, 4);
        let ctx = open_session(scratch.path());
        ctx.session.rebuild_projection().expect("seed rebuild");

        // Delete a document together with its identity record: the record's owner is
        // provable from the committed baseline, and the document's own removal lane
        // already retires every identity it emitted.
        let record_rel = lomo_workspace::memo_identity_record_path(
            WorkspaceRootId::Notes,
            &lomo_workspace::WorkspaceRelativePath::parse("2026_09_12.md").expect("doc path"),
        )
        .expect("identity record path");
        fs::remove_file(ctx.workspace.join("2026_09_12.md")).expect("delete doc");
        fs::remove_file(ctx.workspace.join(record_rel.as_str())).expect("delete record");

        ctx.counting.reset();
        ctx.session.rebuild_projection().expect("reconcile");
        let reads = ctx.counting.reads();
        assert!(
            reads <= 24,
            "a co-deleted identity record must not force a full scan: {reads} file reads",
        );
        assert_equivalent_to_fresh_scan(&ctx.session, &ctx.workspace);
    }

    #[test]
    fn unprovable_identity_record_removal_falls_back() {
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 6, 4);
        let ctx = open_session(scratch.path());
        ctx.session.rebuild_projection().expect("seed rebuild");

        // Commit a foreign identity record whose hashed name no live or committed
        // document produces, then delete it: the owner can never be proven, so the
        // reconcile must fall back to the full scan — fail closed, never a guessed scope.
        let foreign = ctx
            .workspace
            .join(".lomo/identity/v1/ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff.rec");
        fs::write(&foreign, b"foreign identity").expect("foreign record");
        ctx.session.rebuild_projection().expect("commit baseline");

        fs::remove_file(&foreign).expect("remove foreign record");
        ctx.counting.reset();
        ctx.session.rebuild_projection().expect("reconcile");
        assert!(
            ctx.counting.reads() >= 6,
            "an identity record whose owner is unprovable must trigger the full scan: {} reads",
            ctx.counting.reads()
        );
        assert_equivalent_to_fresh_scan(&ctx.session, &ctx.workspace);
    }

    #[test]
    fn unclaimed_state_object_removal_is_inert() {
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 4, 4);
        let ctx = open_session(scratch.path());
        ctx.session.rebuild_projection().expect("seed rebuild");
        let summaries = summaries(&ctx.session, false);
        let memo =
            MemoId::parse(&summaries.first().expect("seeded summary").memo_id).expect("memo id");
        let pin = |op: &str| {
            ctx.session
                .pin_memo(
                    lomo_application::PinMemoRequest::new(
                        lomo_core::OperationId::parse(op).expect("op"),
                        memo.clone(),
                        lomo_application::PinPolicy::Pinned { at_ms: None },
                    )
                    .expect("pin request"),
                )
                .expect("pin");
            ctx.session
                .settle_workspace()
                .expect("committed writes converge");
        };
        let objects_dir = ctx.workspace.join(".lomo/state/v2/objects");
        let object_names = || {
            ctx.session
                .settle_workspace()
                .expect("committed writes converge");
            fs::read_dir(&objects_dir)
                .expect("objects dir")
                .map(|entry| entry.expect("entry").file_name())
                .collect::<BTreeSet<_>>()
        };
        pin("pin-superseded");
        let after_first = object_names();
        pin("pin-current");
        let after_second = object_names();
        assert_eq!(after_second.len(), 2, "two pins mint two state objects");

        // The first object is a chain ancestor the pin walk never reaches: heads only
        // resolve their tip, so deleting it is provably inert — no claimant exists.
        let orphan = after_first.iter().next().expect("first object").clone();
        fs::remove_file(objects_dir.join(&orphan)).expect("remove orphan object");

        ctx.counting.reset();
        ctx.session.rebuild_projection().expect("reconcile");
        let reads = ctx.counting.reads();
        assert!(
            reads <= 12,
            "a state object no head claims must not re-scan the workspace: {reads} reads",
        );
        assert_equivalent_to_fresh_scan(&ctx.session, &ctx.workspace);
    }

    #[test]
    fn operations_and_staging_churn_is_inert() {
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 6, 4);
        let ctx = open_session(scratch.path());
        ctx.session.rebuild_projection().expect("seed rebuild");

        // `.lomo/operations` and `.lomo/migration-staging` are engine-owned control
        // families that never project: present or removed, they join only the committed
        // listing rows — never a fact re-read and never the full-scan fallback.
        let operations = ctx.workspace.join(".lomo/operations");
        let staging = ctx.workspace.join(".lomo/migration-staging");
        fs::create_dir_all(&operations).expect("operations dir");
        fs::create_dir_all(&staging).expect("staging dir");
        fs::write(operations.join("op-1.rec"), b"intent").expect("op record");
        fs::write(staging.join("staged.bin"), b"staged").expect("staged blob");

        ctx.counting.reset();
        ctx.session.rebuild_projection().expect("reconcile");
        assert!(
            ctx.counting.reads() <= 2,
            "inert .lomo families must not re-read the workspace: {} reads",
            ctx.counting.reads()
        );

        fs::remove_file(operations.join("op-1.rec")).expect("remove op record");
        fs::remove_file(staging.join("staged.bin")).expect("remove staged blob");
        ctx.counting.reset();
        ctx.session.rebuild_projection().expect("reconcile");
        assert!(
            ctx.counting.reads() <= 2,
            "inert .lomo family removals must stay scoped: {} reads",
            ctx.counting.reads()
        );
        assert_equivalent_to_fresh_scan(&ctx.session, &ctx.workspace);
    }

    #[test]
    fn first_import_of_new_large_record_directories_observes_metadata_in_bulk() {
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 12, 4);
        let ctx = open_session(scratch.path());
        assert_eq!(
            ctx.counting.metadata_inspections.load(Ordering::SeqCst),
            0,
            "new large parents must use one bulk observation rather than individual per-file queries"
        );
        assert_eq!(summaries(&ctx.session, false).len(), 48);
        assert_equivalent_to_fresh_scan(&ctx.session, &ctx.workspace);
    }

    #[test]
    fn dirty_reconcile_observes_written_paths_without_reenumerating_their_siblings() {
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 1, 2);
        let ctx = open_session(scratch.path());
        fs::write(
            scratch.path().join("2026_12_31.md"),
            "- 11:00\nnew first\n\n- 12:00\nnew second\n\n",
        )
        .expect("external document");
        ctx.counting.reset();
        ctx.session.rebuild_projection().expect("dirty reconcile");
        let starts = ctx
            .counting
            .listing_starts
            .lock()
            .expect("listing counts")
            .clone();
        assert!(
            starts.contains_key("/"),
            "the full initial inventory must still run"
        );
        assert!(
            starts.values().all(|count| *count == 1),
            "post-write fingerprints must observe exact targets instead of listing large parents again: {starts:?}"
        );
        assert_eq!(summaries(&ctx.session, false).len(), 4);
        assert_equivalent_to_fresh_scan(&ctx.session, &ctx.workspace);
    }

    #[test]
    fn changed_batch_larger_than_one_lane_uses_complete_inventory_for_parent_size() {
        use std::fmt::Write as _;

        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 30, 4);
        let ctx = open_session(scratch.path());
        let mut source = String::new();
        for index in 0..12 {
            writeln!(source, "- 11:{index:02}\nnew memo {index}\n").expect("format fixture memo");
        }
        fs::write(scratch.path().join("2026_12_31.md"), source).expect("external document");
        ctx.counting.reset();
        ctx.session.rebuild_projection().expect("dirty reconcile");
        let starts = ctx
            .counting
            .listing_starts
            .lock()
            .expect("listing counts")
            .clone();
        assert!(
            starts.values().all(|count| *count == 1),
            "small delta inside large existing parents must use their complete membership: {starts:?}"
        );
        assert_eq!(summaries(&ctx.session, false).len(), 132);
        assert_equivalent_to_fresh_scan(&ctx.session, &ctx.workspace);
    }

    #[test]
    fn scoped_history_admits_heads_written_after_the_listing_that_provoked_rebuild() {
        use std::io::Write as _;
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 1, 2);
        let ctx = open_session(scratch.path());
        let views = summaries(&ctx.session, false);
        let first = views.first().expect("seeded memo");
        let id = MemoId::parse(&first.memo_id).expect("memo identity");
        let before = ctx.session.get_memo(&id).expect("read").expect("memo");
        fs::remove_file(
            scratch
                .path()
                .join(".lomo/history/v2/heads")
                .join(format!("{}.rec", id.as_str())),
        )
        .expect("external head deletion");
        fs::OpenOptions::new()
            .append(true)
            .open(scratch.path().join(&before.source_path))
            .expect("source file")
            .write_all(b"- 12:00\nnew third memo\n\n")
            .expect("external append");
        ctx.session
            .rebuild_projection()
            .expect("rebuild with initial history repair");
        let history = ctx
            .session
            .list_history(&id, None, 10)
            .expect("repaired history");
        assert_eq!(
            history.items.len(),
            1,
            "the verified new head must outrank pre-write absence evidence"
        );
        assert_eq!(
            history.items.first().expect("initial history").content,
            before.body
        );
        assert_eq!(summaries(&ctx.session, false).len(), 3);
        assert_equivalent_to_fresh_scan(&ctx.session, &ctx.workspace);
    }

    #[test]
    fn complete_inventory_avoids_reading_absent_history_tombstones() {
        use std::time::UNIX_EPOCH;
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 6, 4);
        let ctx = open_session(scratch.path());
        for entry in fs::read_dir(scratch.path().join(".lomo/history/v2/heads")).expect("heads") {
            let file = fs::File::open(entry.expect("head entry").path()).expect("head file");
            let old = file
                .metadata()
                .expect("head metadata")
                .modified()
                .expect("mtime")
                .duration_since(UNIX_EPOCH)
                .expect("modern time")
                .subsec_nanos();
            let nanos = if old == 1 { 2 } else { 1 };
            file.set_modified(UNIX_EPOCH + Duration::new(2_000_000_000, nanos))
                .expect("changed head token");
        }
        ctx.counting.reset();
        ctx.session
            .rebuild_projection()
            .expect("scoped history refresh");
        assert_eq!(
            ctx.counting.tombstone_reads.load(Ordering::SeqCst),
            0,
            "complete enumeration already proves that no tombstone slots exist"
        );
        assert_eq!(summaries(&ctx.session, false).len(), 24);
        assert_equivalent_to_fresh_scan(&ctx.session, &ctx.workspace);
    }

    #[test]
    fn complete_inventory_avoids_reading_absent_state_heads_for_discovered_memos() {
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 1, 2);
        let ctx = open_session(scratch.path());
        fs::write(
            scratch.path().join("2026_12_31.md"),
            "- 11:00\nnew first\n\n- 12:00\nnew second\n\n",
        )
        .expect("external document");
        ctx.counting.reset();
        ctx.session
            .rebuild_projection()
            .expect("scoped document refresh");
        assert_eq!(
            ctx.counting.state_head_reads.load(Ordering::SeqCst),
            0,
            "unlisted state heads are unattested, not per-memo missing-file probes"
        );
        assert_eq!(summaries(&ctx.session, false).len(), 4);
        assert_equivalent_to_fresh_scan(&ctx.session, &ctx.workspace);
    }

    #[test]
    fn cold_rebuild_batches_independent_history_record_reads() {
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 6, 4);
        let seeded = open_session(scratch.path());
        seeded
            .session
            .rebuild_projection()
            .expect("seed durable history");
        drop(seeded);
        let cold = open_session(scratch.path());
        let widest = cold.counting.max_history_read_batch.load(Ordering::SeqCst);
        assert!(
            widest > 1,
            "independent history records must share a batch, widest={widest}"
        );
        assert!(
            widest <= 8,
            "record read batches must stay within the bounded read lane"
        );
        assert_eq!(summaries(&cold.session, false).len(), 24);
        assert_equivalent_to_fresh_scan(&cold.session, &cold.workspace);
    }

    #[test]
    fn cold_rebuild_batches_existing_heads_and_tips_instead_of_reading_per_memo() {
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 6, 4);
        drop(open_session(scratch.path()));
        let cold = open_session(scratch.path());
        assert_eq!(
            cold.counting
                .singleton_history_batches
                .load(Ordering::SeqCst),
            0,
            "existing history must be loaded by the batched graph admission stage"
        );
        assert_eq!(summaries(&cold.session, false).len(), 24);
        assert_equivalent_to_fresh_scan(&cold.session, &cold.workspace);
    }

    #[test]
    fn cold_rebuild_reads_each_history_object_once() {
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 6, 4);
        drop(open_session(scratch.path()));
        let cold = open_session(scratch.path());
        assert_eq!(
            cold.counting.history_object_reads.load(Ordering::SeqCst),
            24,
            "a full scan must load each immutable history object once"
        );
        assert_equivalent_to_fresh_scan(&cold.session, &cold.workspace);
    }

    #[test]
    fn cold_rebuild_batches_independent_markdown_reads() {
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 12, 2);
        let cold = open_session(scratch.path());
        let widest = cold.counting.max_markdown_read_batch.load(Ordering::SeqCst);
        assert!(
            (2..=8).contains(&widest),
            "independent source reads must use the bounded read lane, widest={widest}"
        );
        assert_equivalent_to_fresh_scan(&cold.session, &cold.workspace);
    }

    #[derive(Default)]
    struct WriteGateState {
        active: usize,
        peak: usize,
        released: bool,
    }

    /// Identity writes wait for the test coordinator, exposing actual overlap without sleeps.
    struct GatedIdentityExecutor {
        inner: FsPlatformActionExecutor,
        state: Mutex<WriteGateState>,
        release: Condvar,
        overlap: mpsc::Sender<()>,
        gate_history: bool,
    }

    impl PlatformActionExecutor for GatedIdentityExecutor {
        fn execute(
            &self,
            batch: &PlatformActionBatch,
        ) -> Result<PlatformBatchResult, lomo_core::LomoError> {
            let identity_write = batch.actions().iter().any(|action| {
                matches!(action, PlatformAction::WriteFromExchange { path, .. }
                    if path.as_str().starts_with(if self.gate_history { ".lomo/history/v2/objects/" } else { ".lomo/identity/v1/" }))
            });
            if identity_write {
                let mut state = self.state.lock().expect("gate lock");
                state.active += 1;
                state.peak = state.peak.max(state.active);
                if state.active == 2 && !state.released {
                    self.overlap.send(()).expect("notify overlap");
                }
                while !state.released {
                    state = self.release.wait(state).expect("gate wait");
                }
                drop(state);
            }
            let result = self.inner.execute(batch);
            if identity_write {
                self.state.lock().expect("gate lock").active -= 1;
            }
            result
        }
    }

    struct GatedScanFixture {
        dirs: Vec<tempfile::TempDir>,
        config: WorkspaceSessionConfig,
        gate: Arc<GatedIdentityExecutor>,
        observed: mpsc::Receiver<()>,
    }

    fn gated_scan_fixture(docs: usize, memos: usize, gate_history: bool) -> GatedScanFixture {
        let dirs = (0..6)
            .map(|_| tempdir().expect("fixture dir"))
            .collect::<Vec<_>>();
        let workspace = fixture_dir(&dirs, 0);
        seed_docs(workspace, docs, memos);
        let capability = CapabilityToken::parse("notes").expect("cap");
        let inner = FsPlatformActionExecutor::new(fixture_dir(&dirs, 4)).expect("executor");
        inner
            .bind_root(capability.clone(), workspace)
            .expect("bind");
        let (overlap, observed) = mpsc::channel();
        let gate = Arc::new(GatedIdentityExecutor {
            inner,
            state: Mutex::new(WriteGateState::default()),
            release: Condvar::new(),
            overlap,
            gate_history,
        });
        let config = WorkspaceSessionConfig {
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
        };
        GatedScanFixture {
            dirs,
            config,
            gate,
            observed,
        }
    }

    fn release_gate(gate: &GatedIdentityExecutor) {
        let mut state = gate.state.lock().expect("gate lock");
        state.released = true;
        drop(state);
        gate.release.notify_all();
    }

    #[test]
    fn cold_index_overlaps_memo_history_in_one_large_document() {
        let GatedScanFixture {
            dirs,
            config,
            gate,
            observed,
        } = gated_scan_fixture(1, 24, true);
        let workspace = fixture_dir(&dirs, 0);
        let executor: Arc<dyn PlatformActionExecutor> = Arc::<GatedIdentityExecutor>::clone(&gate);
        let worker = std::thread::spawn(move || WorkspaceSession::open(config, executor));
        let observation = observed.recv_timeout(Duration::from_secs(5));
        release_gate(&gate);
        let session = worker.join().expect("scan worker").expect("open");
        let state = gate.state.lock().expect("gate lock");
        assert!(
            observation.is_ok(),
            "memo histories in one document stayed serial"
        );
        assert!((2..=8).contains(&state.peak), "peak={}", state.peak);
        assert_eq!(state.active, 0);
        assert_eq!(summaries(&session, false).len(), 24);
        assert_equivalent_to_fresh_scan(&session, workspace);
    }

    #[test]
    fn cold_index_shares_the_history_lane_budget_across_documents() {
        let GatedScanFixture {
            dirs,
            config,
            gate,
            observed,
        } = gated_scan_fixture(3, 24, true);
        let workspace = fixture_dir(&dirs, 0);
        let executor: Arc<dyn PlatformActionExecutor> = Arc::<GatedIdentityExecutor>::clone(&gate);
        let worker = std::thread::spawn(move || WorkspaceSession::open(config, executor));
        let observation = observed.recv_timeout(Duration::from_secs(5));
        release_gate(&gate);
        let session = worker.join().expect("scan worker").expect("open");
        let state = gate.state.lock().expect("gate lock");
        assert!(observation.is_ok(), "history chains stayed serial");
        assert!((2..=8).contains(&state.peak), "global peak={}", state.peak);
        assert_eq!(state.active, 0);
        assert_eq!(summaries(&session, false).len(), 72);
        assert_equivalent_to_fresh_scan(&session, workspace);
    }

    #[test]
    fn cold_index_overlaps_independent_document_writes_with_a_bounded_lane() {
        let GatedScanFixture {
            dirs,
            config,
            gate,
            observed,
        } = gated_scan_fixture(12, 2, false);
        let workspace = fixture_dir(&dirs, 0);
        let executor: Arc<dyn PlatformActionExecutor> = Arc::<GatedIdentityExecutor>::clone(&gate);
        let worker = std::thread::spawn(move || WorkspaceSession::open(config, executor));
        // The timeout only bounds the intentional serial RED failure; the condvar controls
        // ordering. Release and join even on timeout so no blocked worker leaks out of the test.
        let observation = observed.recv_timeout(Duration::from_secs(5));
        release_gate(&gate);
        let session = worker.join().expect("scan worker").expect("open");
        let state = gate.state.lock().expect("gate lock");
        assert!(observation.is_ok(), "independent writes stayed serial");
        assert!((2..=8).contains(&state.peak), "peak={}", state.peak);
        assert_eq!(state.active, 0, "all writes must finish before publication");
        assert_eq!(summaries(&session, false).len(), 24);
        assert_equivalent_to_fresh_scan(&session, workspace);
    }

    #[test]
    fn incremental_scan_overlaps_changed_documents_without_exposing_a_partial_projection() {
        let GatedScanFixture {
            dirs,
            config,
            gate,
            observed,
        } = gated_scan_fixture(12, 2, false);
        let workspace = fixture_dir(&dirs, 0);
        release_gate(&gate);
        let executor: Arc<dyn PlatformActionExecutor> = Arc::<GatedIdentityExecutor>::clone(&gate);
        let session = WorkspaceSession::open(config, executor).expect("initial projection");
        seed_docs(workspace, 12, 3);
        *gate.state.lock().expect("gate lock") = WriteGateState::default();
        let worker = std::thread::spawn(move || {
            let result = session.rebuild_projection();
            (session, result)
        });
        let observation = observed.recv_timeout(Duration::from_secs(5));
        release_gate(&gate);
        let (session, result) = worker.join().expect("scan worker");
        result.expect("incremental rebuild");
        let state = gate.state.lock().expect("gate lock");
        assert!(observation.is_ok(), "changed documents stayed serial");
        assert!((2..=8).contains(&state.peak), "peak={}", state.peak);
        assert_eq!(state.active, 0, "all writers finish before publication");
        drop(state);
        assert_eq!(summaries(&session, false).len(), 36);
        assert_equivalent_to_fresh_scan(&session, workspace);
    }

    struct RejectIdentityExecutor {
        inner: Arc<GatedIdentityExecutor>,
        rejected: RelativeWorkspacePath,
    }

    impl PlatformActionExecutor for RejectIdentityExecutor {
        fn execute(
            &self,
            batch: &PlatformActionBatch,
        ) -> Result<PlatformBatchResult, lomo_core::LomoError> {
            if batch.actions().iter().any(|action| {
                matches!(action, PlatformAction::WriteFromExchange { path, .. }
                    if path == &self.rejected)
            }) {
                return Err(lomo_application::error::storage(
                    "injected_identity_failure",
                    "fixture rejects one identity write",
                ));
            }
            self.inner.execute(batch)
        }
    }

    #[test]
    fn failed_document_scan_drains_workers_and_never_publishes_partial_rows() {
        let GatedScanFixture {
            dirs,
            config,
            gate,
            observed,
        } = gated_scan_fixture(12, 2, false);
        let workspace = fixture_dir(&dirs, 0);
        let cache_dir = config.cache_dir.clone();
        let rejected = lomo_workspace::memo_identity_record_path(
            WorkspaceRootId::Notes,
            &lomo_workspace::WorkspaceRelativePath::parse("2026_09_10.md").expect("path"),
        )
        .expect("identity path");
        let executor: Arc<dyn PlatformActionExecutor> = Arc::new(RejectIdentityExecutor {
            inner: Arc::clone(&gate),
            rejected: RelativeWorkspacePath::parse(rejected.as_str()).expect("relative path"),
        });
        let worker = std::thread::spawn(move || WorkspaceSession::open(config, executor));
        let observation = observed.recv_timeout(Duration::from_secs(5));
        release_gate(&gate);
        let outcome = worker.join().expect("scan worker");
        let Err(error) = outcome else {
            panic!("rejected write must fail the scan");
        };
        assert!(observation.is_ok(), "valid sibling chains must run");
        assert_eq!(error.code(), "injected_identity_failure");
        assert_eq!(gate.state.lock().expect("gate lock").active, 0);
        let store = lomo_store::Store::open_projection(&cache_dir).expect("projection");
        assert_eq!(
            store
                .query_count(&MemoQuery {
                    search_text: None,
                    filters: MemoFilters::default(),
                    sort: MemoSort::default(),
                })
                .expect("count"),
            0,
            "a failed scan cannot publish partial rows"
        );
        drop(store);
        let recovered = open_session(workspace);
        assert_eq!(summaries(&recovered.session, false).len(), 24);
        assert_equivalent_to_fresh_scan(&recovered.session, workspace);
    }

    struct RejectFirstHistoryExecutor {
        inner: Arc<GatedIdentityExecutor>,
        rejected: AtomicBool,
    }

    impl PlatformActionExecutor for RejectFirstHistoryExecutor {
        fn execute(
            &self,
            batch: &PlatformActionBatch,
        ) -> Result<PlatformBatchResult, lomo_core::LomoError> {
            let history_write = batch.actions().iter().any(|action| {
                matches!(action, PlatformAction::WriteFromExchange { path, .. }
                    if path.as_str().starts_with(".lomo/history/v2/objects/"))
            });
            if history_write && !self.rejected.swap(true, Ordering::SeqCst) {
                return Err(lomo_application::error::storage(
                    "injected_history_failure",
                    "reject one history write",
                ));
            }
            self.inner.execute(batch)
        }
    }

    #[test]
    fn failed_memo_history_drains_siblings_without_publishing_partial_rows_and_retries() {
        let GatedScanFixture {
            dirs,
            config,
            gate,
            observed,
        } = gated_scan_fixture(1, 24, true);
        let workspace = fixture_dir(&dirs, 0);
        let cache_dir = config.cache_dir.clone();
        let executor: Arc<dyn PlatformActionExecutor> = Arc::new(RejectFirstHistoryExecutor {
            inner: Arc::clone(&gate),
            rejected: AtomicBool::new(false),
        });
        let retry_config = config.clone();
        let worker = std::thread::spawn(move || WorkspaceSession::open(config, executor));
        let observation = observed.recv_timeout(Duration::from_secs(5));
        release_gate(&gate);
        let Err(error) = worker.join().expect("scan worker") else {
            panic!("history failure must surface")
        };
        assert!(observation.is_ok(), "sibling histories must run");
        assert_eq!(error.code(), "injected_history_failure");
        assert_eq!(gate.state.lock().expect("gate lock").active, 0);
        let store = lomo_store::Store::open_projection(&cache_dir).expect("projection");
        assert_eq!(
            store
                .query_count(&MemoQuery {
                    search_text: None,
                    filters: MemoFilters::default(),
                    sort: MemoSort::default(),
                })
                .expect("count"),
            0,
            "failed scans must not publish partial rows"
        );
        drop(store);
        let executor: Arc<dyn PlatformActionExecutor> = gate;
        let recovered = WorkspaceSession::open(retry_config, executor).expect("retry");
        assert_eq!(summaries(&recovered, false).len(), 24);
        assert_equivalent_to_fresh_scan(&recovered, workspace);
    }

    #[test]
    fn workspace_listing_uses_bounded_fanout_batches() {
        let scratch = tempdir().expect("workspace");
        seed_docs(scratch.path(), 6, 4);
        let ctx = open_session(scratch.path());
        ctx.session.rebuild_projection().expect("seed rebuild");

        // The recursive walk fans independent directory listings into one platform batch
        // (bounded by the parallel read lane) instead of issuing one batch per directory:
        // the `.lomo` tree alone gives the frontier more than one listing task per round.
        ctx.counting.reset();
        ctx.session.rebuild_projection().expect("idle reconcile");
        assert!(
            ctx.counting.max_listing_batch() > 1,
            "the recursive listing must fan directories into shared batches: {} was the widest",
            ctx.counting.max_listing_batch()
        );
        assert!(
            ctx.counting.max_listing_batch() <= 8,
            "the listing fan-out stays bounded by the read lane: {}",
            ctx.counting.max_listing_batch()
        );
    }
}
