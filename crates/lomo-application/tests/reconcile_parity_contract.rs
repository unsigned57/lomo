//! Adversarial reconcile probes: a scoped or watcher-guided reconcile must
//! leave the projection indistinguishable from a sibling cold materialize —
//! the "incremental ≡ full" oracle. Merged from the numbered re-audit
//! rounds; each module pins the surface its round documented.

mod tests {

    // adversarial-reaudit: "增量 ≡ 全量" — a scoped or watcher-guided reconcile must leave the
    // projection indistinguishable from a sibling session that cold-materialized the same durable
    // facts, because any divergence here is user-visible corruption.
    //
    // Invariants probed independently, evidence-first:
    // - `reconcile_observed_paths` trusts the caller's attested path set: anything outside it
    //   stays certified as committed. A rename event that reports only the destination — the
    //   exact `ChangeKind::Renamed` payload the snapshot-diff watcher emits
    //   (`crates/lomo-platform-fs/src/watcher/poll.rs`, non-Linux backend) — must not leave
    //   the source path's rows stale.
    // - The inventory gate (`try_reconcile_scanned`) must compare every durable fact class —
    //   including `.lomo/purged/` tombstones — before it may certify the live projection and
    //   skip materialization.
    // - A trashed memo's `file_fingerprint` anchors to the live document fingerprint while any
    //   sibling row can attest it; once the source document is gone, the durable trash record's
    //   stored fingerprint is the only truth a cold scan can reproduce.
    // - The publication clock moves only when projection content moves: a token-only rewrite of
    //   identical bytes keeps cursors valid, a content change stales them, and a full
    //   materialize always stales them.
    // - Records a reconcile itself writes (identity maps, initial history) refresh their listing
    //   evidence so the next pass does not rediscover them as unverified changes.
    //
    // Equivalence oracle: `assert_equivalent_to_fresh_scan` opens a second session on the same
    // workspace with fresh private directories — its only option is a cold materialize — and
    // compares both the public projection surface and the raw store tables row-for-row.
    //
    // Tests asserting the CORRECT invariant that still FAIL are genuine residual defects and are
    // kept RED deliberately; each maps to `audit/09-复审-增量投影与启动.md`.

    #[cfg(test)]
    #[expect(
        clippy::expect_used,
        reason = "adversarial audit tests fail closed with panics on missing facts"
    )]
    mod parity_oracle {
        use std::{
            collections::BTreeSet,
            fmt::Write as _,
            fs,
            path::{Path, PathBuf},
            sync::{
                Arc,
                atomic::{AtomicUsize, Ordering},
            },
        };

        use lomo_application::{
            CreateMemoRequest, DeleteMemoRequest, MemoFilters, MemoQuery, MemoSort,
            PermanentDeleteManyRequest, PermanentDeleteManyTarget, UpdateMemoRequest,
            WorkspaceSession, WorkspaceSessionConfig,
        };
        use lomo_core::{
            CapabilityToken, OperationId, PageSize, PlatformAction, PlatformActionBatch,
            PlatformActionExecutor, PlatformBatchResult, RelativeWorkspacePath,
        };
        use lomo_platform_fs::FsPlatformActionExecutor;
        use lomo_workspace::{
            MemoId, WorkspaceGenerationId, WorkspaceRootId, decode_trash_record,
            trash_record_relative_path, write_trash_record_atomic,
        };
        use rusqlite::OptionalExtension;
        use tempfile::tempdir;

        /// Counts platform document/record reads so reconcile provenance is observable.
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
            cache_dir: PathBuf,
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
            let real =
                Arc::new(FsPlatformActionExecutor::new(fixture_dir(&dirs, 4)).expect("exec"));
            let capability = CapabilityToken::parse("notes").expect("cap");
            real.bind_root(capability.clone(), &workspace_path)
                .expect("bind");
            let counting = Arc::new(CountingExecutor {
                inner: real,
                reads: AtomicUsize::new(0),
            });
            let executor: Arc<dyn PlatformActionExecutor> =
                Arc::<CountingExecutor>::clone(&counting);
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
                cache_dir,
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
            let executor: Arc<dyn PlatformActionExecutor> =
                Arc::<CountingExecutor>::clone(&counting);
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
                cache_dir,
                counting,
                _dirs: dirs,
            })
        }

        /// A second session on the same workspace with fresh private directories: its open can
        /// only materialize from durable facts — the "全量" oracle.
        fn fresh_oracle(workspace: &Path) -> Ctx {
            try_open_session(workspace).expect("oracle session")
        }

        fn write_doc(workspace: &Path, name: &str, blocks: &[(&str, &str)]) {
            let mut text = String::new();
            for (time, body) in blocks {
                write!(text, "- {time}\n{body}\n\n").expect("doc text");
            }
            fs::write(workspace.join(name), text).expect("write doc");
        }

        fn append_block(path: &Path, time: &str, body: &str) {
            let mut text = fs::read_to_string(path).expect("read doc");
            write!(text, "- {time}\n{body}\n\n").expect("append text");
            fs::write(path, text).expect("append block");
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

        /// The public observable projection: memo rows, bodies, lifecycle bits, pins, history
        /// windows, attachment observations, tag counts and tasks.
        fn public_dump(session: &WorkspaceSession) -> String {
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

        /// Every row of one table, ordered, rendered textually. `rowid` and session-private
        /// journal/meta tables are excluded; `created_at_ms`/`updated_at_ms`/`trashed_at_ms`
        /// derive from document content or explicit request parameters, so they are part of the
        /// deterministic contract.
        fn table_dump(conn: &rusqlite::Connection, tag: &str, sql: &str, out: &mut String) {
            let mut stmt = conn.prepare(sql).expect("prepare");
            let columns = stmt.column_count();
            let mut rows = stmt.query([]).expect("query");
            while let Some(row) = rows.next().expect("row") {
                out.push_str(tag);
                out.push('|');
                for index in 0..columns {
                    let value = row.get_ref(index).expect("column");
                    match value {
                        rusqlite::types::ValueRef::Null => out.push_str("NULL;"),
                        rusqlite::types::ValueRef::Integer(number) => {
                            write!(out, "{number};").expect("dump");
                        }
                        rusqlite::types::ValueRef::Real(number) => {
                            write!(out, "{number};").expect("dump");
                        }
                        rusqlite::types::ValueRef::Text(text) => {
                            out.push_str(&String::from_utf8_lossy(text));
                            out.push(';');
                        }
                        rusqlite::types::ValueRef::Blob(blob) => {
                            write!(out, "blob:{};", blob.len()).expect("dump");
                        }
                    }
                }
                out.push('\n');
            }
        }

        /// The raw store rows a byte-for-byte rebuild must reproduce — memo projections, lifecycle
        /// membership, durable history, purge tombstones, attachment references, and the committed
        /// file-listing snapshot that scopes the next reconcile.
        fn store_dump(cache_dir: &Path) -> String {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            let mut out = String::new();
            for (tag, sql) in [
                (
                    "memo",
                    "SELECT memo_id, source_path, source_start, source_end, file_fingerprint, \
                     has_todo, has_url, has_attachment, is_pinned, is_trashed, created_at_ms, \
                     updated_at_ms, body_preview, COALESCE(body,''), search_content, word_count, \
                     char_count, reminders_json, content_revision, COALESCE(pending_operation_id,'') \
                     FROM memo ORDER BY memo_id",
                ),
                ("tag", "SELECT name FROM tag ORDER BY name"),
                (
                    "memo_tag",
                    "SELECT mt.memo_id, t.name FROM memo_tag mt JOIN tag t ON t.id = mt.tag_id \
                     ORDER BY mt.memo_id, t.name",
                ),
                (
                    "attachment_ref",
                    "SELECT memo_id, relative_path FROM attachment_ref ORDER BY memo_id, relative_path",
                ),
                (
                    "memo_pin",
                    "SELECT memo_id, pinned_at_ms FROM memo_pin ORDER BY memo_id",
                ),
                (
                    "memo_trash",
                    "SELECT memo_id, trashed_at_ms FROM memo_trash ORDER BY memo_id",
                ),
                (
                    "revision_index",
                    "SELECT memo_id, revision, history_record_id, created_at_ms, \
                     COALESCE(content,''), COALESCE(file_fingerprint,'') FROM revision_index \
                     ORDER BY memo_id, revision, history_record_id",
                ),
                (
                    "history_attachment_ref",
                    "SELECT memo_id, revision, relative_path FROM history_attachment_ref \
                     ORDER BY memo_id, revision, relative_path",
                ),
                (
                    "purged_memo",
                    "SELECT memo_id FROM purged_memo ORDER BY memo_id",
                ),
                (
                    "file_listing",
                    "SELECT path, digest FROM file_listing ORDER BY path",
                ),
                ("stats", "SELECT key, value_i64 FROM stats ORDER BY key"),
            ] {
                table_dump(&conn, tag, sql, &mut out);
            }
            let digest: Option<String> = conn
                .query_row(
                    "SELECT value FROM store_meta WHERE key='workspace_listing_digest'",
                    [],
                    |row| row.get(0),
                )
                .optional()
                .expect("listing digest meta");
            writeln!(out, "meta|workspace_listing_digest|{digest:?}").expect("dump");
            out
        }

        /// The result of the same workspace under a scoped/watcher-guided reconcile (the live
        /// session) must equal a sibling session that could only fully materialize the durable
        /// facts — on both the public surface and the raw store rows.
        fn assert_equivalent_to_fresh_scan(ctx: &Ctx) {
            let oracle = fresh_oracle(&ctx.workspace);
            assert_eq!(
                public_dump(&ctx.session),
                public_dump(&oracle.session),
                "public projection diverged from a fresh materialize"
            );
            assert_eq!(
                store_dump(&ctx.cache_dir),
                store_dump(&oracle.cache_dir),
                "raw projection rows diverged from a fresh materialize"
            );
        }

        fn projected_ids(ctx: &Ctx, trash_only: bool) -> BTreeSet<String> {
            summaries(&ctx.session, trash_only)
                .iter()
                .map(|summary| summary.memo_id.clone())
                .collect()
        }

        // ---------- watcher evidence: rename coverage ----------

        /// The snapshot-diff poll watcher (the non-Linux backend) pairs a created path with a
        /// deleted path sharing one file identity and emits `Renamed{path: destination}` only —
        /// the source never reaches the event stream, and `host.rs` forwards exactly the event
        /// path. The observed set `[destination]` must still converge: an unobserved source path
        /// cannot stay certified.
        #[test]
        fn rename_reported_destination_only_must_not_certify_the_stale_source() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "alpha body"), ("10:01:00", "beta body")],
            );
            write_doc(scratch.path(), "2026_09_11.md", &[("10:00:00", "gamma")]);
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed rebuild");
            let seeded = projected_ids(&ctx, false);
            assert_eq!(seeded.len(), 3, "fixture sanity");

            // A real rename preserves inode identity; this is the payload a poll watcher can
            // legitimately deliver: destination only.
            fs::rename(
                ctx.workspace.join("2026_09_10.md"),
                ctx.workspace.join("2026_09_14.md"),
            )
            .expect("rename");
            ctx.session
                .reconcile_observed_paths(&[
                    RelativeWorkspacePath::parse("2026_09_14.md").expect("path")
                ])
                .expect("scoped reconcile");

            assert_equivalent_to_fresh_scan(&ctx);
        }

        /// Rename plus a same-event content edit: the destination blocks resolve to fresh memo
        /// identities while the source blocks' rows must still be retired. A live session that
        /// keeps the source projection shows the memos as ghosts under a path that no longer
        /// exists.
        #[test]
        fn rename_and_rewrite_reported_destination_only_must_not_ghost_source_memos() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "alpha body"), ("10:01:00", "beta body")],
            );
            write_doc(scratch.path(), "2026_09_11.md", &[("10:00:00", "gamma")]);
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed rebuild");
            // The renamed document's projected ids — the identities that must vanish once the
            // edited destination mints fresh ones. An unrelated document's memo keeps its
            // durable identity; it belongs to both sides of the equivalence, not to this set.
            let source_ids: BTreeSet<String> = summaries(&ctx.session, false)
                .iter()
                .filter(|summary| summary.source_path == "2026_09_10.md")
                .map(|summary| summary.memo_id.clone())
                .collect();
            assert_eq!(source_ids.len(), 2, "fixture sanity");

            fs::rename(
                ctx.workspace.join("2026_09_10.md"),
                ctx.workspace.join("2026_09_14.md"),
            )
            .expect("rename");
            write_doc(
                scratch.path(),
                "2026_09_14.md",
                &[
                    ("10:00:00", "alpha body EDITED"),
                    ("10:01:00", "beta body EDITED"),
                ],
            );
            ctx.session
                .reconcile_observed_paths(&[
                    RelativeWorkspacePath::parse("2026_09_14.md").expect("path")
                ])
                .expect("scoped reconcile");

            let live = projected_ids(&ctx, false);
            let oracle = fresh_oracle(&ctx.workspace);
            let oracle_ids = projected_ids(&oracle, false);
            assert_eq!(
                live,
                oracle_ids,
                "destination-only evidence must retire the source document's memos; \
                 live keeps {} extra rows under a path that no longer exists",
                live.difference(&oracle_ids).count()
            );
            assert!(
                live.is_disjoint(&source_ids),
                "edited blocks mint fresh identities — the original memo ids must be gone"
            );
            assert_equivalent_to_fresh_scan(&ctx);
        }

        /// Control: when the observed set covers BOTH endpoints — the payload the inotify backend
        /// produces (`MOVED_FROM` + `MOVED_TO` each map to `Renamed`) — the same scoped pass must
        /// converge, isolating the failure to missing source evidence rather than the scoped
        /// retire machinery.
        #[test]
        fn rename_reported_with_source_and_destination_converges() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "alpha body"), ("10:01:00", "beta body")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed rebuild");

            fs::rename(
                ctx.workspace.join("2026_09_10.md"),
                ctx.workspace.join("2026_09_14.md"),
            )
            .expect("rename");
            write_doc(
                scratch.path(),
                "2026_09_14.md",
                &[
                    ("10:00:00", "alpha body EDITED"),
                    ("10:01:00", "beta body EDITED"),
                ],
            );
            ctx.session
                .reconcile_observed_paths(&[
                    RelativeWorkspacePath::parse("2026_09_10.md").expect("source path"),
                    RelativeWorkspacePath::parse("2026_09_14.md").expect("dest path"),
                ])
                .expect("scoped reconcile");

            assert_equivalent_to_fresh_scan(&ctx);
        }

        /// The residue from destination-only evidence is healed by the next unscoped pass — the
        /// committed snapshot diff then re-verifies the source path and retires its rows. This
        /// bounds the defect: it persists exactly as long as only watcher-scoped reconciles run.
        #[test]
        fn unscoped_reconcile_heals_destination_only_rename_residue() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "alpha body"), ("10:01:00", "beta body")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed rebuild");

            fs::rename(
                ctx.workspace.join("2026_09_10.md"),
                ctx.workspace.join("2026_09_14.md"),
            )
            .expect("rename");
            write_doc(
                scratch.path(),
                "2026_09_14.md",
                &[
                    ("10:00:00", "alpha body EDITED"),
                    ("10:01:00", "beta body EDITED"),
                ],
            );
            ctx.session
                .reconcile_observed_paths(&[
                    RelativeWorkspacePath::parse("2026_09_14.md").expect("path")
                ])
                .expect("scoped reconcile");
            ctx.session
                .rebuild_projection()
                .expect("unscoped reconcile");

            assert_equivalent_to_fresh_scan(&ctx);
        }

        // ---------- trash-record fingerprint anchoring ----------

        /// While the source document still exists, a trashed sibling's `file_fingerprint` must
        /// track the live document fingerprint — both the incremental blanket update and the cold
        /// merge's `source_document_fingerprint` anchor it there.
        #[test]
        fn trashed_sibling_tracks_canonical_fingerprint_while_document_lives() {
            let scratch = tempdir().expect("workspace");
            let ctx = open_session(scratch.path());
            let active = ctx
                .session
                .create_memo(CreateMemoRequest {
                    operation_id: OperationId::parse("anchor-a").expect("op"),
                    relative_path: Some(RelativeWorkspacePath::parse("2026_09_10.md").expect("p")),
                    time_token: Some("12:00:00".to_owned()),
                    content: "active sibling".to_owned(),
                    expected_document_fingerprint: None,
                    pinned: false,
                    pending_promotes: Vec::new(),
                    chronology_epoch_ms: None,
                })
                .expect("create active");
            let victim = ctx
                .session
                .create_memo(CreateMemoRequest {
                    operation_id: OperationId::parse("anchor-b").expect("op"),
                    relative_path: Some(RelativeWorkspacePath::parse("2026_09_10.md").expect("p")),
                    time_token: Some("12:01:00".to_owned()),
                    content: "victim sibling".to_owned(),
                    expected_document_fingerprint: None,
                    pinned: false,
                    pending_promotes: Vec::new(),
                    chronology_epoch_ms: None,
                })
                .expect("create victim");
            ctx.session
                .delete_memo(DeleteMemoRequest {
                    operation_id: OperationId::parse("anchor-del").expect("op"),
                    memo_id: victim.memo_id.clone(),
                    expected_document_fingerprint: victim.commit_result.file_fingerprint,
                    trashed_at_ms: Some(1_757_500_000_000),
                })
                .expect("trash victim");

            append_block(
                &ctx.workspace.join("2026_09_10.md"),
                "12:02:00",
                "appended third",
            );
            ctx.session.rebuild_projection().expect("reconcile");
            assert!(
                projected_ids(&ctx, false).contains(active.memo_id.as_str()),
                "the active sibling must stay projected"
            );

            assert_equivalent_to_fresh_scan(&ctx);
        }

        /// Once the source document is gone, no row can anchor the trashed memo's fingerprint:
        /// a cold scan falls back to the fingerprint stored in the trash record. The incremental
        /// path must reproduce that — a row that tracked every intermediate document edit must
        /// not keep a fingerprint the durable record never saw.
        #[test]
        fn trashed_memo_fingerprint_must_fall_back_to_the_record_once_source_is_gone() {
            let scratch = tempdir().expect("workspace");
            let ctx = open_session(scratch.path());
            ctx.session
                .create_memo(CreateMemoRequest {
                    operation_id: OperationId::parse("anchor2-a").expect("op"),
                    relative_path: Some(RelativeWorkspacePath::parse("2026_09_10.md").expect("p")),
                    time_token: Some("12:00:00".to_owned()),
                    content: "active sibling".to_owned(),
                    expected_document_fingerprint: None,
                    pinned: false,
                    pending_promotes: Vec::new(),
                    chronology_epoch_ms: None,
                })
                .expect("create active");
            let victim = ctx
                .session
                .create_memo(CreateMemoRequest {
                    operation_id: OperationId::parse("anchor2-b").expect("op"),
                    relative_path: Some(RelativeWorkspacePath::parse("2026_09_10.md").expect("p")),
                    time_token: Some("12:01:00".to_owned()),
                    content: "victim sibling".to_owned(),
                    expected_document_fingerprint: None,
                    pinned: false,
                    pending_promotes: Vec::new(),
                    chronology_epoch_ms: None,
                })
                .expect("create victim");
            ctx.session
                .delete_memo(DeleteMemoRequest {
                    operation_id: OperationId::parse("anchor2-del").expect("op"),
                    memo_id: victim.memo_id.clone(),
                    expected_document_fingerprint: victim.commit_result.file_fingerprint,
                    trashed_at_ms: Some(1_757_500_000_000),
                })
                .expect("trash victim");

            // Move the live fingerprint forward while the victim sits trashed, then remove the
            // document entirely: the live row keeps the last canonical fingerprint while the
            // durable record can only attest the trash-time one.
            append_block(
                &ctx.workspace.join("2026_09_10.md"),
                "12:02:00",
                "appended third",
            );
            ctx.session.rebuild_projection().expect("reconcile edit");
            fs::remove_file(ctx.workspace.join("2026_09_10.md")).expect("remove doc");
            ctx.session.rebuild_projection().expect("reconcile delete");

            assert_equivalent_to_fresh_scan(&ctx);
        }

        // ---------- purge tombstone coverage in the inventory gate ----------

        /// Creates one memo, trashes it, and returns the permanent-delete target plus the durable
        /// trash record bytes captured before the purge removes them.
        fn purge_target(
            ctx: &Ctx,
            op_seed: &str,
            file: &str,
            content: &str,
        ) -> (MemoId, PermanentDeleteManyTarget, Vec<u8>) {
            let created = ctx
                .session
                .create_memo(CreateMemoRequest {
                    operation_id: OperationId::parse(&format!("{op_seed}-create")).expect("op"),
                    relative_path: Some(RelativeWorkspacePath::parse(file).expect("path")),
                    time_token: Some("12:00:00".to_owned()),
                    content: content.to_owned(),
                    expected_document_fingerprint: None,
                    pinned: false,
                    pending_promotes: Vec::new(),
                    chronology_epoch_ms: None,
                })
                .expect("create");
            let deleted = ctx
                .session
                .delete_memo(DeleteMemoRequest {
                    operation_id: OperationId::parse(&format!("{op_seed}-delete")).expect("op"),
                    memo_id: created.memo_id.clone(),
                    expected_document_fingerprint: created.commit_result.file_fingerprint,
                    trashed_at_ms: Some(1_757_500_000_000),
                })
                .expect("delete");
            let record_rel =
                trash_record_relative_path(created.memo_id.as_str()).expect("trash record path");
            let record_abs = ctx.workspace.join(record_rel.as_str());
            let record_bytes = fs::read(&record_abs).expect("read durable trash record");
            (
                created.memo_id.clone(),
                PermanentDeleteManyTarget {
                    memo_id: created.memo_id,
                    source_path: file.to_owned(),
                    expected_revision: deleted.commit_result.content_revision,
                    expected_fingerprint: deleted.commit_result.file_fingerprint,
                },
                record_bytes,
            )
        }

        fn purged_ids(cache_dir: &Path) -> BTreeSet<String> {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            let mut stmt = conn
                .prepare("SELECT memo_id FROM purged_memo ORDER BY memo_id")
                .expect("prepare");
            stmt.query_map([], |row| row.get::<_, String>(0))
                .expect("query")
                .collect::<Result<BTreeSet<_>, _>>()
                .expect("collect")
        }

        /// The permanent-delete commit writes the durable tombstone but never projects it:
        /// `purged_memo` only learns the identity from a later reconcile reading the file.
        /// Between the commit and that reconcile the live projection disagrees with a cold
        /// scan of the same durable facts.
        #[test]
        fn permanent_delete_must_project_the_tombstone_immediately() {
            let scratch = tempdir().expect("workspace");
            let ctx = open_session(scratch.path());
            let (memo_id, target, _record) =
                purge_target(&ctx, "pur0", "2026_09_10.md", "memo purged in place");
            ctx.session
                .permanently_delete_many(&PermanentDeleteManyRequest {
                    operation_id: OperationId::parse("pur0-purge").expect("op"),
                    targets: vec![target],
                })
                .expect("purge");
            let purge_rel =
                lomo_store::purge_record_relative_path(memo_id.as_str()).expect("purge path");
            assert!(
                ctx.workspace.join(purge_rel.as_str()).is_file(),
                "permanent delete writes a durable tombstone"
            );

            let oracle = fresh_oracle(&ctx.workspace);
            assert_eq!(
                purged_ids(&ctx.cache_dir),
                purged_ids(&oracle.cache_dir),
                "the committed projection must carry the tombstone a cold scan finds"
            );
        }

        /// Consequence of the unprojected tombstone: a watcher-scoped reconcile that only
        /// observes a re-delivered trash record consults the committed `purged_memo` set —
        /// which the commit left empty — and resurrects a permanently deleted memo.
        #[test]
        fn observed_scope_reconcile_must_not_resurrect_a_purged_memo() {
            let scratch = tempdir().expect("workspace");
            let ctx = open_session(scratch.path());
            let (memo_id, target, record_bytes) =
                purge_target(&ctx, "pur1", "2026_09_10.md", "memo that must stay purged");
            // Commit a non-empty listing snapshot first — otherwise the scoped pass correctly
            // fails closed ("no baseline, no provable deletions") and the resurrection hole
            // in the committed purged_memo set is never exercised.
            ctx.session
                .rebuild_projection()
                .expect("seed committed snapshot");
            ctx.session
                .permanently_delete_many(&PermanentDeleteManyRequest {
                    operation_id: OperationId::parse("pur1-purge").expect("op"),
                    targets: vec![target],
                })
                .expect("purge");
            let purge_rel =
                lomo_store::purge_record_relative_path(memo_id.as_str()).expect("purge path");
            assert!(
                ctx.workspace.join(purge_rel.as_str()).is_file(),
                "the suppression fact still exists durably"
            );

            // Peer sync replays the pre-purge trash record; the watcher reports exactly that
            // path — the tombstone in a sibling directory is outside the observed scope.
            let record_rel = trash_record_relative_path(memo_id.as_str()).expect("trash path");
            let record_abs = ctx.workspace.join(record_rel.as_str());
            let record = decode_trash_record(&record_bytes).expect("decode");
            write_trash_record_atomic(&record_abs, &record).expect("restore record");
            ctx.session
                .reconcile_observed_paths(&[
                    RelativeWorkspacePath::parse(record_rel.as_str()).expect("relative path")
                ])
                .expect("scoped reconcile");

            let oracle = fresh_oracle(&ctx.workspace);
            assert_eq!(
                projected_ids(&ctx, true),
                projected_ids(&oracle, true),
                "a purged memo must stay deleted under observed-scope reconcile"
            );
            assert_equivalent_to_fresh_scan(&ctx);
        }

        /// When the scoped pass cannot prove coverage (an unclassifiable `.lomo` path forces the
        /// fallback), the inventory gate certifies the live projection by comparing memo pairs,
        /// attachments, pins and history — a `.lomo/purged/` tombstone that disappeared between
        /// commits must not leave its `purged_memo` row standing.
        #[test]
        fn lifted_purge_tombstone_must_not_survive_the_inventory_gate() {
            let scratch = tempdir().expect("workspace");
            let ctx = open_session(scratch.path());
            let (memo_id, target, _record) =
                purge_target(&ctx, "pur", "2026_09_10.md", "memo that must stay purged");
            ctx.session
                .permanently_delete_many(&PermanentDeleteManyRequest {
                    operation_id: OperationId::parse("pur-purge").expect("op"),
                    targets: vec![target],
                })
                .expect("purge");
            let purge_rel =
                lomo_store::purge_record_relative_path(memo_id.as_str()).expect("purge path");
            let purge_abs = ctx.workspace.join(purge_rel.as_str());
            assert!(purge_abs.is_file(), "permanent delete writes a tombstone");

            // A reconcile projects the tombstone the commit left unprojected.
            ctx.session
                .rebuild_projection()
                .expect("reconcile tombstone");
            assert!(
                purged_ids(&ctx.cache_dir).contains(memo_id.as_str()),
                "the reconcile must carry the durable tombstone into purged_memo"
            );

            // Peer sync lifts the tombstone while an unclassifiable .lomo change defeats the
            // scoped pass; the inventory gate must still detect the divergence.
            fs::remove_file(&purge_abs).expect("lift tombstone");
            let blob = ctx.workspace.join(".lomo/unknown-layout/blob.bin");
            fs::create_dir_all(blob.parent().expect("parent")).expect("blob dir");
            fs::write(&blob, b"opaque").expect("blob");
            ctx.session.rebuild_projection().expect("gated reconcile");

            let oracle = fresh_oracle(&ctx.workspace);
            assert_eq!(
                purged_ids(&ctx.cache_dir),
                purged_ids(&oracle.cache_dir),
                "a removed .lomo/purged tombstone must drop the committed purged_memo row"
            );
        }

        /// Consequence of the stale tombstone row: once the inventory gate has certified a
        /// `purged_memo` row whose file is gone, a watcher-scoped reconcile suppresses a
        /// legitimately re-delivered trash record — the memo stays invisible even though a cold
        /// scan resurrects it.
        #[test]
        fn stale_tombstone_row_must_not_suppress_a_restored_trash_record() {
            let scratch = tempdir().expect("workspace");
            let ctx = open_session(scratch.path());
            let (memo_id, target, record_bytes) = purge_target(
                &ctx,
                "pur2",
                "2026_09_10.md",
                "memo suppressed by a stale row",
            );
            ctx.session
                .permanently_delete_many(&PermanentDeleteManyRequest {
                    operation_id: OperationId::parse("pur2-purge").expect("op"),
                    targets: vec![target],
                })
                .expect("purge");
            ctx.session
                .rebuild_projection()
                .expect("reconcile tombstone");
            let purge_rel =
                lomo_store::purge_record_relative_path(memo_id.as_str()).expect("purge path");
            fs::remove_file(ctx.workspace.join(purge_rel.as_str())).expect("lift tombstone");
            let blob = ctx.workspace.join(".lomo/unknown-layout/blob.bin");
            fs::create_dir_all(blob.parent().expect("parent")).expect("blob dir");
            fs::write(&blob, b"opaque").expect("blob");
            ctx.session.rebuild_projection().expect("gated reconcile");

            let record_rel = trash_record_relative_path(memo_id.as_str()).expect("trash path");
            let record_abs = ctx.workspace.join(record_rel.as_str());
            let record = decode_trash_record(&record_bytes).expect("decode");
            write_trash_record_atomic(&record_abs, &record).expect("restore record");
            ctx.session
                .reconcile_observed_paths(&[
                    RelativeWorkspacePath::parse(record_rel.as_str()).expect("relative path")
                ])
                .expect("scoped reconcile");

            let oracle = fresh_oracle(&ctx.workspace);
            assert_eq!(
                projected_ids(&ctx, true),
                projected_ids(&oracle, true),
                "a lifted tombstone must let the restored trash record resurrect the memo"
            );
            assert_equivalent_to_fresh_scan(&ctx);
        }

        /// Consequence of the stale tombstone: a trash record the purge once suppressed must
        /// resurrect when the tombstone is lifted — the scoped reconcile reads the committed
        /// `purged_memo` set as its suppression authority.
        #[test]
        fn restored_trash_record_must_resurrect_once_the_tombstone_is_lifted() {
            let scratch = tempdir().expect("workspace");
            let ctx = open_session(scratch.path());
            let (memo_id, target, record_bytes) = purge_target(
                &ctx,
                "res",
                "2026_09_10.md",
                "memo resurrected by record restore",
            );
            ctx.session
                .permanently_delete_many(&PermanentDeleteManyRequest {
                    operation_id: OperationId::parse("res-purge").expect("op"),
                    targets: vec![target],
                })
                .expect("purge");
            let purge_rel =
                lomo_store::purge_record_relative_path(memo_id.as_str()).expect("purge path");
            fs::remove_file(ctx.workspace.join(purge_rel.as_str())).expect("lift tombstone");
            let blob = ctx.workspace.join(".lomo/unknown-layout/blob.bin");
            fs::create_dir_all(blob.parent().expect("parent")).expect("blob dir");
            fs::write(&blob, b"opaque").expect("blob");
            ctx.session.rebuild_projection().expect("gated reconcile");

            // The lifted tombstone lets a re-delivered trash record claim the memo again.
            let record_rel = trash_record_relative_path(memo_id.as_str()).expect("trash path");
            let record_abs = ctx.workspace.join(record_rel.as_str());
            let record = decode_trash_record(&record_bytes).expect("decode");
            write_trash_record_atomic(&record_abs, &record).expect("restore record");
            ctx.session.rebuild_projection().expect("reconcile restore");

            let oracle = fresh_oracle(&ctx.workspace);
            assert_eq!(
                projected_ids(&ctx, true),
                projected_ids(&oracle, true),
                "trash lane must match a cold materialize once the tombstone is gone"
            );
            assert_equivalent_to_fresh_scan(&ctx);
        }

        // ---------- publication clock and cursor invalidation ----------

        fn feed_query() -> MemoQuery {
            MemoQuery {
                search_text: None,
                filters: MemoFilters::default(),
                sort: MemoSort::default(),
            }
        }

        /// Rewriting a document with identical bytes moves only the listing token (inode/mtime),
        /// not the content. The scoped pass must prove content-identical, leave the publication
        /// clock alone and keep already-issued page cursors valid.
        #[test]
        fn content_identical_rewrite_keeps_clock_and_cursors() {
            let scratch = tempdir().expect("workspace");
            for doc in 0..4 {
                write_doc(
                    scratch.path(),
                    &format!("2026_09_1{doc}.md"),
                    &[("10:00:00", &format!("memo {doc}"))],
                );
            }
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed rebuild");

            let query = feed_query();
            let first = ctx
                .session
                .query_memos_page(&query, None, None, PageSize::new(2).expect("ps"))
                .expect("page one");
            let cursor = first.next_cursor.expect("four memos page at size two");

            let path = ctx.workspace.join("2026_09_12.md");
            let bytes = fs::read(&path).expect("read doc");
            fs::remove_file(&path).expect("remove doc");
            fs::write(&path, &bytes).expect("rewrite identical bytes");

            ctx.counting.reset();
            let result = ctx.session.rebuild_projection().expect("reconcile");
            assert!(
                !result.rewritten,
                "identical content must not advance the publication clock"
            );
            assert!(
                ctx.counting.reads() >= 1,
                "the token drift must still reach the scoped diff"
            );
            let second = ctx
                .session
                .query_memos_page(&query, None, Some(&cursor), PageSize::new(2).expect("ps"))
                .expect("a cursor from before an identical reconcile must stay valid");
            assert_eq!(second.items.len(), 2, "remaining page");
            assert_equivalent_to_fresh_scan(&ctx);
        }

        /// A real content change must move the publication clock exactly once and stale every
        /// cursor minted before it.
        #[test]
        fn content_change_advances_clock_and_stales_cursors() {
            let scratch = tempdir().expect("workspace");
            for doc in 0..4 {
                write_doc(
                    scratch.path(),
                    &format!("2026_09_1{doc}.md"),
                    &[("10:00:00", &format!("memo {doc}"))],
                );
            }
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed rebuild");

            let query = feed_query();
            let first = ctx
                .session
                .query_memos_page(&query, None, None, PageSize::new(2).expect("ps"))
                .expect("page one");
            let cursor = first.next_cursor.expect("four memos page at size two");

            append_block(
                &ctx.workspace.join("2026_09_13.md"),
                "10:05:00",
                "new memo arriving",
            );
            let result = ctx.session.rebuild_projection().expect("reconcile");
            assert!(
                result.rewritten,
                "a content-moving apply must advance the publication clock"
            );
            let error = ctx
                .session
                .query_memos_page(&query, None, Some(&cursor), PageSize::new(2).expect("ps"))
                .expect_err("a pre-change cursor must be rejected");
            assert_eq!(error.code(), "stale_cursor");
            assert_equivalent_to_fresh_scan(&ctx);
        }

        /// The full-materialize path replaces the projection wholesale; its clock must move even
        /// though the live store object is swapped, and every prior cursor goes stale.
        #[test]
        fn unprovable_scope_materialize_stales_cursors() {
            let scratch = tempdir().expect("workspace");
            for doc in 0..4 {
                write_doc(
                    scratch.path(),
                    &format!("2026_09_1{doc}.md"),
                    &[("10:00:00", &format!("memo {doc}"))],
                );
            }
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed rebuild");

            let query = feed_query();
            let first = ctx
                .session
                .query_memos_page(&query, None, None, PageSize::new(2).expect("ps"))
                .expect("page one");
            let cursor = first.next_cursor.expect("four memos page at size two");

            // An unclassifiable .lomo change defeats the scoped pass; the appended memo makes the
            // inventory gate diverge, forcing the materialize-and-replace path.
            let blob = ctx.workspace.join(".lomo/unknown-layout/blob.bin");
            fs::create_dir_all(blob.parent().expect("parent")).expect("blob dir");
            fs::write(&blob, b"opaque").expect("blob");
            append_block(
                &ctx.workspace.join("2026_09_13.md"),
                "10:05:00",
                "new memo arriving",
            );
            let result = ctx.session.rebuild_projection().expect("reconcile");
            assert!(result.rewritten, "a materialize always moves the clock");
            let error = ctx
                .session
                .query_memos_page(&query, None, Some(&cursor), PageSize::new(2).expect("ps"))
                .expect_err("a pre-materialize cursor must be rejected");
            assert_eq!(error.code(), "stale_cursor");
            assert_equivalent_to_fresh_scan(&ctx);
        }

        // ---------- listing evidence refresh for reconcile-generated writes ----------

        /// A reconcile that writes identity/history records for new memo identities must refresh
        /// those files' listing rows before committing; otherwise the very next reconcile
        /// rediscovers its own writes as unverified `.lomo` changes and pays for a re-scan.
        #[test]
        fn reconcile_generated_writes_must_refresh_their_listing_evidence() {
            let scratch = tempdir().expect("workspace");
            write_doc(scratch.path(), "2026_09_10.md", &[("10:00:00", "memo a")]);
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed rebuild");

            append_block(&ctx.workspace.join("2026_09_10.md"), "10:01:00", "memo b");
            ctx.session.rebuild_projection().expect("reconcile append");

            ctx.counting.reset();
            ctx.session
                .rebuild_projection()
                .expect("follow-up reconcile");
            assert!(
                ctx.counting.reads() <= 2,
                "records written by the previous reconcile must already be certified: {} reads",
                ctx.counting.reads()
            );
            assert_equivalent_to_fresh_scan(&ctx);
        }

        // ---------- attachment index and history parity ----------

        /// Attachment references materialized from durable history (`history_attachment_ref`) and
        /// live bodies (`attachment_ref`) must be identical whether produced by the commit-time
        /// publication path plus incremental reconciles, or by a cold scan.
        #[test]
        fn history_and_live_attachment_rows_match_fresh_materialize() {
            let scratch = tempdir().expect("workspace");
            fs::create_dir_all(scratch.path().join("media")).expect("media dir");
            fs::write(scratch.path().join("media/x.png"), b"png").expect("media file");
            let ctx = open_session(scratch.path());
            let created = ctx
                .session
                .create_memo(CreateMemoRequest {
                    operation_id: OperationId::parse("att-create").expect("op"),
                    relative_path: Some(RelativeWorkspacePath::parse("2026_09_10.md").expect("p")),
                    time_token: Some("12:00:00".to_owned()),
                    content: "see ![[media/x.png]]".to_owned(),
                    expected_document_fingerprint: None,
                    pinned: false,
                    pending_promotes: Vec::new(),
                    chronology_epoch_ms: None,
                })
                .expect("create");
            ctx.session
                .update_memo(UpdateMemoRequest {
                    operation_id: OperationId::parse("att-update").expect("op"),
                    memo_id: created.memo_id.clone(),
                    content: "detached".to_owned(),
                    expected_document_fingerprint: created.commit_result.file_fingerprint,
                    pending_promotes: Vec::new(),
                })
                .expect("update writes history retaining the reference");

            // External change to an unrelated document: the scoped pass must leave the materialized
            // attachment state byte-identical to a cold scan of the same durable facts.
            write_doc(
                scratch.path(),
                "2026_09_11.md",
                &[("10:00:00", "unrelated")],
            );
            ctx.session.rebuild_projection().expect("reconcile");

            assert_equivalent_to_fresh_scan(&ctx);
        }

        /// Removing a durable history head record scopes a single-memo chain re-walk; the
        /// resulting revision rows must match a cold walk of the same (now headless) chain —
        /// or surface the same corruption if cold rejects it.
        #[test]
        fn removed_history_head_rewalks_the_same_chain_as_cold_scan() {
            let scratch = tempdir().expect("workspace");
            let ctx = open_session(scratch.path());
            let created = ctx
                .session
                .create_memo(CreateMemoRequest {
                    operation_id: OperationId::parse("hist-create").expect("op"),
                    relative_path: Some(RelativeWorkspacePath::parse("2026_09_10.md").expect("p")),
                    time_token: Some("12:00:00".to_owned()),
                    content: "version one".to_owned(),
                    expected_document_fingerprint: None,
                    pinned: false,
                    pending_promotes: Vec::new(),
                    chronology_epoch_ms: None,
                })
                .expect("create");
            ctx.session
                .update_memo(UpdateMemoRequest {
                    operation_id: OperationId::parse("hist-update").expect("op"),
                    memo_id: created.memo_id.clone(),
                    content: "version two".to_owned(),
                    expected_document_fingerprint: created.commit_result.file_fingerprint,
                    pending_promotes: Vec::new(),
                })
                .expect("update");

            // Delete one durable history head record: a removal the committed revision_index can
            // attribute to its owner keeps the reconcile scoped.
            let mut removed = 0_usize;
            for entry in fs::read_dir(ctx.workspace.join(".lomo/history/v2/heads"))
                .expect("history heads dir")
            {
                let path = entry.expect("entry").path();
                if path.extension().is_some_and(|ext| ext == "rec") {
                    fs::remove_file(path).expect("remove head");
                    removed += 1;
                }
            }
            assert!(removed >= 1, "fixture must have written history heads");

            let outcome = ctx.session.rebuild_projection();
            let oracle = try_open_session(&ctx.workspace);
            match (outcome, oracle) {
                (Ok(_), Ok(oracle)) => {
                    assert_eq!(
                        public_dump(&ctx.session),
                        public_dump(&oracle.session),
                        "incremental history re-walk must equal the cold walk"
                    );
                    assert_eq!(
                        store_dump(&ctx.cache_dir),
                        store_dump(&oracle.cache_dir),
                        "raw projection rows diverged after the head removal"
                    );
                }
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
                    public_dump(&oracle.session)
                ),
            }
        }
    }

    // adversarial-reaudit round 2: "增量 ≡ 全量" — the second pass probes the edges the
    // first suite did not reach, using the same oracle discipline: whatever a scoped or
    // watcher-guided reconcile converges to must equal a sibling session that could only
    // cold-materialize the same durable facts — or fail identically.
    //
    // Invariants probed, evidence-first:
    // - A memo that lives in BOTH lanes (block present in a document AND a durable trash
    //   record) is a state `merge_trash_projection` explicitly merges — the live row is
    //   "live-document attestation" and the record's body wins the row. The scoped pass
    //   converges it. The inventory gate's pair builder (`fingerprint_pairs`) treats the
    //   same identity appearing in both lanes as corruption — and that `Err` propagates
    //   before the materialize fallback can run, so a fresh session can never open the
    //   workspace the merge arm was written to support.
    // - The gate compares `(memo_id, file_fingerprint)` pairs plus attachments, pins,
    //   history and the purged set — but for a TRASHED memo the pair fingerprint is the
    //   record's *claimed* `source_fingerprint`, not a hash of record content. A record
    //   rewrite that preserves the claimed fingerprint (body, `trashed_at_ms`, tags,
    //   `source_path`) is invisible to the gate: identical pairs certify while a cold
    //   materialize writes different rows.
    // - The SAF commit path writes projection rows but no `file_listing` rows; the
    //   committed listing evidence catches up through the next reconcile — that pass
    //   must stay bounded, and the pass after it must cost zero document/record reads
    //   (one fixed `layout_head` validation read is the per-entry preamble, not drift).
    // - `reconcile_observed_paths` treats an observed directory as attestation for
    //   everything beneath it — and nothing outside it: an observed trash directory must
    //   admit the nested record while an unobserved new document stays deferred until a
    //   digest-diffed pass.
    // - A purge tombstone suppresses only the trash lane of its identity: a doc-present
    //   memo keeps projecting, and a lifted tombstone lets the live record claim it
    //   again. But the SAF `Delete` commit arm writes `memo_trash` unconditionally —
    //   deleting a tombstoned memo commits a trash row no scan of the same durable facts
    //   can produce.
    //
    // Tests asserting the CORRECT invariant that still FAIL are genuine residual
    // defects and are kept RED deliberately; each maps to
    // `audit/11-再复审-数据路径修复.md`.

    #[cfg(test)]
    #[expect(
        clippy::expect_used,
        reason = "adversarial audit tests fail closed with panics on missing facts"
    )]
    mod parity_edges {
        use std::{
            collections::BTreeSet,
            fmt::Write as _,
            fs,
            path::{Path, PathBuf},
            sync::{
                Arc,
                atomic::{AtomicUsize, Ordering},
            },
        };

        use lomo_application::{
            CreateMemoRequest, DeleteMemoRequest, MemoFilters, MemoQuery, MemoSort,
            WorkspaceSession, WorkspaceSessionConfig,
        };
        use lomo_core::{
            CapabilityToken, OperationId, PageSize, PlatformAction, PlatformActionBatch,
            PlatformActionExecutor, PlatformBatchResult, RelativeWorkspacePath,
        };
        use lomo_platform_fs::FsPlatformActionExecutor;
        use lomo_workspace::{
            MemoId, SourceFingerprint, TrashRecordCreate, TrashRecordV1, WorkspaceGenerationId,
            WorkspaceRootId, trash_record_relative_path, write_trash_record_atomic,
        };
        use rusqlite::OptionalExtension;
        use tempfile::tempdir;

        /// Counts platform document/record reads so reconcile provenance is observable.
        struct CountingExecutor {
            inner: Arc<FsPlatformActionExecutor>,
            reads: AtomicUsize,
            read_paths: std::sync::Mutex<Vec<String>>,
        }

        impl CountingExecutor {
            fn reads(&self) -> usize {
                self.reads.load(Ordering::SeqCst)
            }
            fn reset(&self) {
                self.reads.store(0, Ordering::SeqCst);
                self.read_paths.lock().expect("read paths").clear();
            }
            fn paths(&self) -> Vec<String> {
                self.read_paths.lock().expect("read paths").clone()
            }
        }

        impl PlatformActionExecutor for CountingExecutor {
            fn execute(
                &self,
                batch: &PlatformActionBatch,
            ) -> Result<PlatformBatchResult, lomo_core::LomoError> {
                for action in batch.actions() {
                    if let PlatformAction::ReadToExchange { path, .. } = action {
                        self.reads.fetch_add(1, Ordering::SeqCst);
                        self.read_paths
                            .lock()
                            .expect("read paths")
                            .push(path.as_str().to_owned());
                    }
                }
                self.inner.execute(batch)
            }
        }

        struct Ctx {
            session: WorkspaceSession,
            workspace: PathBuf,
            cache_dir: PathBuf,
            counting: Arc<CountingExecutor>,
            _dirs: Vec<tempfile::TempDir>,
        }

        /// Private fixture directories are positional; the index is a fixture slot, not data.
        fn fixture_dir(dirs: &[tempfile::TempDir], index: usize) -> &Path {
            dirs.get(index).expect("fixture dir slot").path()
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
                read_paths: std::sync::Mutex::new(Vec::new()),
            });
            let executor: Arc<dyn PlatformActionExecutor> =
                Arc::<CountingExecutor>::clone(&counting);
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
                cache_dir,
                counting,
                _dirs: dirs,
            })
        }

        fn open_session(workspace: &Path) -> Ctx {
            try_open_session(workspace).expect("session")
        }

        /// A second session on the same workspace with fresh private directories: its open can
        /// only materialize from durable facts — the "全量" oracle.
        fn fresh_oracle(workspace: &Path) -> Ctx {
            try_open_session(workspace).expect("oracle session")
        }

        fn write_doc(workspace: &Path, name: &str, blocks: &[(&str, &str)]) {
            let mut text = String::new();
            for (time, body) in blocks {
                write!(text, "- {time}\n{body}\n\n").expect("doc text");
            }
            fs::write(workspace.join(name), text).expect("write doc");
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

        /// The public observable projection: memo rows, bodies, lifecycle bits, pins, history
        /// windows, attachment observations, tag counts and tasks.
        fn public_dump(session: &WorkspaceSession) -> String {
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

        /// Every row of one table, ordered, rendered textually. `rowid` and session-private
        /// journal/meta tables are excluded.
        fn table_dump(conn: &rusqlite::Connection, tag: &str, sql: &str, out: &mut String) {
            let mut stmt = conn.prepare(sql).expect("prepare");
            let columns = stmt.column_count();
            let mut rows = stmt.query([]).expect("query");
            while let Some(row) = rows.next().expect("row") {
                out.push_str(tag);
                out.push('|');
                for index in 0..columns {
                    let value = row.get_ref(index).expect("column");
                    match value {
                        rusqlite::types::ValueRef::Null => out.push_str("NULL;"),
                        rusqlite::types::ValueRef::Integer(number) => {
                            write!(out, "{number};").expect("dump");
                        }
                        rusqlite::types::ValueRef::Real(number) => {
                            write!(out, "{number};").expect("dump");
                        }
                        rusqlite::types::ValueRef::Text(text) => {
                            out.push_str(&String::from_utf8_lossy(text));
                            out.push(';');
                        }
                        rusqlite::types::ValueRef::Blob(blob) => {
                            write!(out, "blob:{};", blob.len()).expect("dump");
                        }
                    }
                }
                out.push('\n');
            }
        }

        /// The raw store rows a byte-for-byte rebuild must reproduce — memo projections, lifecycle
        /// membership, durable history, purge tombstones, attachment references, and the committed
        /// file-listing snapshot that scopes the next reconcile.
        fn store_dump(cache_dir: &Path) -> String {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            let mut out = String::new();
            for (tag, sql) in [
                (
                    "memo",
                    "SELECT memo_id, source_path, source_start, source_end, file_fingerprint, \
                     has_todo, has_url, has_attachment, is_pinned, is_trashed, created_at_ms, \
                     updated_at_ms, body_preview, COALESCE(body,''), search_content, word_count, \
                     char_count, reminders_json, content_revision, COALESCE(pending_operation_id,'') \
                     FROM memo ORDER BY memo_id",
                ),
                ("tag", "SELECT name FROM tag ORDER BY name"),
                (
                    "memo_tag",
                    "SELECT mt.memo_id, t.name FROM memo_tag mt JOIN tag t ON t.id = mt.tag_id \
                     ORDER BY mt.memo_id, t.name",
                ),
                (
                    "attachment_ref",
                    "SELECT memo_id, relative_path FROM attachment_ref ORDER BY memo_id, relative_path",
                ),
                (
                    "memo_pin",
                    "SELECT memo_id, pinned_at_ms FROM memo_pin ORDER BY memo_id",
                ),
                (
                    "memo_trash",
                    "SELECT memo_id, trashed_at_ms FROM memo_trash ORDER BY memo_id",
                ),
                (
                    "revision_index",
                    "SELECT memo_id, revision, history_record_id, created_at_ms, \
                     COALESCE(content,''), COALESCE(file_fingerprint,'') FROM revision_index \
                     ORDER BY memo_id, revision, history_record_id",
                ),
                (
                    "history_attachment_ref",
                    "SELECT memo_id, revision, relative_path FROM history_attachment_ref \
                     ORDER BY memo_id, revision, relative_path",
                ),
                (
                    "purged_memo",
                    "SELECT memo_id FROM purged_memo ORDER BY memo_id",
                ),
                (
                    "file_listing",
                    "SELECT path, digest FROM file_listing ORDER BY path",
                ),
                ("stats", "SELECT key, value_i64 FROM stats ORDER BY key"),
            ] {
                table_dump(&conn, tag, sql, &mut out);
            }
            let digest: Option<String> = conn
                .query_row(
                    "SELECT value FROM store_meta WHERE key='workspace_listing_digest'",
                    [],
                    |row| row.get(0),
                )
                .optional()
                .expect("listing digest meta");
            writeln!(out, "meta|workspace_listing_digest|{digest:?}").expect("dump");
            out
        }

        /// The result of the same workspace under a scoped/watcher-guided reconcile (the live
        /// session) must equal a sibling session that could only fully materialize the durable
        /// facts — on both the public surface and the raw store rows.
        fn assert_equivalent_to_fresh_scan(ctx: &Ctx) {
            let oracle = fresh_oracle(&ctx.workspace);
            assert_eq!(
                public_dump(&ctx.session),
                public_dump(&oracle.session),
                "public projection diverged from a fresh materialize"
            );
            assert_eq!(
                store_dump(&ctx.cache_dir),
                store_dump(&oracle.cache_dir),
                "raw projection rows diverged from a fresh materialize"
            );
        }

        fn projected_ids(ctx: &Ctx, trash_only: bool) -> BTreeSet<String> {
            summaries(&ctx.session, trash_only)
                .iter()
                .map(|summary| summary.memo_id.clone())
                .collect()
        }

        /// The unclassifiable `.lomo` passenger that defeats the path scope — the same
        /// mechanism the first-round suite uses to reach the inventory gate.
        fn park_unclassifiable(workspace: &Path) {
            let blob = workspace.join(".lomo/unknown-layout/blob.bin");
            fs::create_dir_all(blob.parent().expect("parent")).expect("blob dir");
            fs::write(&blob, b"opaque").expect("blob");
        }

        /// Writes a durable trash record for `memo_id` at its canonical hashed path.
        fn write_trash_record(workspace: &Path, record: &TrashRecordV1) {
            let rel = trash_record_relative_path(&record.memo_id).expect("trash path");
            let abs = workspace.join(rel.as_str());
            fs::create_dir_all(abs.parent().expect("record dir")).expect("record dir");
            write_trash_record_atomic(&abs, record).expect("write record");
        }

        /// Writes a durable purge tombstone for `memo_id` at its canonical hashed path.
        fn write_purge_tombstone(workspace: &Path, memo_id: &str, operation_id: &str) {
            let tombstone = lomo_store::PurgeRecordV1 {
                memo_id: memo_id.to_owned(),
                operation_id: operation_id.to_owned(),
                purged_at_ms: 1_757_600_000_000,
            };
            let rel = lomo_store::purge_record_relative_path(memo_id).expect("purge path");
            let abs = workspace.join(rel.as_str());
            fs::create_dir_all(abs.parent().expect("purge dir")).expect("purge dir");
            fs::write(
                &abs,
                lomo_store::encode_purge_record(&tombstone).expect("encode"),
            )
            .expect("write tombstone");
        }

        // ---------- cross-lane durable states through the inventory gate ----------

        /// A memo whose block sits in a document while a durable trash record also names
        /// it is a state `merge_trash_projection` accepts by contract — "a row this commit
        /// inserted from a fresh document read" is exactly the dual-lane merge arm, and
        /// `upsert_trash_projection` converges it on the scoped path. The SAME durable
        /// facts routed through the inventory gate must not fail closed: the pair
        /// builder's "appears in both lanes" rejection runs before the materialize
        /// fallback, so the gate's `Err` both wedges the live session on the next
        /// unclassifiable change and locks every fresh session out of the workspace.
        #[test]
        fn dual_lane_membership_must_not_flip_verdict_with_the_fallback_path() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "dual-lane memo")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed rebuild");
            let victim = summaries(&ctx.session, false)
                .into_iter()
                .find(|summary| {
                    ctx.session
                        .projected_memo(&summary.memo_id)
                        .expect("snapshot")
                        .is_some_and(|snapshot| snapshot.body.contains("dual-lane memo"))
                })
                .expect("victim memo");
            let snapshot = ctx
                .session
                .projected_memo(&victim.memo_id)
                .expect("snapshot")
                .expect("victim snapshot");

            // The record arrives while the document still attests the block — e.g. a peer
            // redelivery or a sync merge that kept the local document revision. The doc
            // never changed, so the identity map still matches; the scoped pass is the
            // only writer that can converge this without the gate ever seeing it.
            write_trash_record(
                &ctx.workspace,
                &TrashRecordV1::try_new(TrashRecordCreate {
                    memo_id: victim.memo_id.clone(),
                    source_path: "2026_09_10.md".to_owned(),
                    time_part: "10:01:00".to_owned(),
                    source_fingerprint: victim.file_fingerprint.clone(),
                    chronology_epoch_ms: snapshot.summary.created_at_ms,
                    trashed_at_ms: 1_757_500_000_000,
                    body: snapshot.body,
                    tags: Vec::new(),
                    attachments: Vec::new(),
                    reminders: Vec::new(),
                    has_todo: false,
                    has_url: false,
                })
                .expect("record"),
            );
            ctx.session
                .rebuild_projection()
                .expect("the scoped pass accepts dual-lane facts");
            assert!(
                projected_ids(&ctx, true).contains(&victim.memo_id),
                "the merge arm converges dual-lane facts: the record claims the memo"
            );

            // The same durable facts on the inventory-gate path: an unrelated
            // unclassifiable `.lomo` passenger forces the fallback, and the pair
            // builder must answer "not equal" — materialize — not "corrupt". And a
            // fresh session must be able to open the same workspace — its first
            // reconcile reaches the same gate before materialize.
            park_unclassifiable(&ctx.workspace);
            let gated = ctx.session.rebuild_projection();
            let cold = try_open_session(&ctx.workspace);
            assert!(
                gated.is_ok() && cold.is_ok(),
                "the inventory gate hard-errors on a state the merge arm converges: \
                 gated={:?} cold={:?}",
                gated.map(|result| result.rewritten),
                cold.map(|_| ()).map_err(|error| format!("{error:?}"))
            );
            assert_equivalent_to_fresh_scan(&ctx);
        }

        /// The gate folds the trash lane into `(memo_id, source_fingerprint)` — a
        /// fingerprint the record *claims* rather than one derived from its bytes. A
        /// trash record rewrite that preserves the claimed fingerprint but changes the
        /// recoverable body or `trashed_at_ms` must still fail certification: the scan's
        /// rows and the committed rows are observably different, so `try_reconcile`
        /// certifying here leaves the committed projection permanently diverged from
        /// any cold materialize.
        #[test]
        fn inventory_gate_must_not_certify_trash_drift_under_fingerprint_collision() {
            let scratch = tempdir().expect("workspace");
            write_doc(scratch.path(), "2026_09_10.md", &[("10:00:00", "stayer")]);
            let ctx = open_session(scratch.path());
            let doc_fingerprint = summaries(&ctx.session, false)
                .first()
                .expect("seeded memo")
                .file_fingerprint
                .clone();
            let ghost = "m_00000000000000000000000000000faded";

            // A durable trash record claiming the live document's fingerprint: the
            // committed row is anchored to the sibling document fingerprint either way,
            // so this is exactly the pair value the store will carry.
            let record = |body: &str, trashed_at_ms: i64| {
                TrashRecordV1::try_new(TrashRecordCreate {
                    memo_id: ghost.to_owned(),
                    source_path: "2026_09_10.md".to_owned(),
                    time_part: "10:02:00".to_owned(),
                    source_fingerprint: doc_fingerprint.clone(),
                    chronology_epoch_ms: 1_757_400_000_000,
                    trashed_at_ms,
                    body: body.to_owned(),
                    tags: Vec::new(),
                    attachments: Vec::new(),
                    reminders: Vec::new(),
                    has_todo: false,
                    has_url: false,
                })
                .expect("record")
            };
            write_trash_record(
                &ctx.workspace,
                &record("original trash body", 1_757_500_000_000),
            );
            ctx.session
                .rebuild_projection()
                .expect("the scoped pass claims the new trash record");
            assert!(projected_ids(&ctx, true).contains(ghost), "fixture sanity");

            // The record is redelivered with corrected content — same claimed
            // fingerprint, different recoverable facts. Pair evidence alone cannot see
            // it; the gate must refuse to certify so materialize re-derives the row.
            write_trash_record(
                &ctx.workspace,
                &record("rewritten trash body", 1_757_500_999_000),
            );
            park_unclassifiable(&ctx.workspace);
            ctx.session
                .rebuild_projection()
                .expect("the gated reconcile must not error — it must materialize");
            let oracle = fresh_oracle(&ctx.workspace);
            assert_eq!(
                public_dump(&ctx.session),
                public_dump(&oracle.session),
                "a fingerprint-claimed trash rewrite that the gate cannot see leaves the \
                 committed row diverged from a cold materialize"
            );
            assert_eq!(
                store_dump(&ctx.cache_dir),
                store_dump(&oracle.cache_dir),
                "memo_trash.trashed_at_ms and the memo row body must track the record"
            );

            // Once realigned, the digest short-circuits — an unnoticed drift would be
            // cemented: every later pass must still agree with the cold peer.
            ctx.session.rebuild_projection().expect("steady-state");
            assert_equivalent_to_fresh_scan(&ctx);
        }

        // ---------- the SAF commit path's listing evidence ----------

        /// `finish_transaction` publishes projection rows but never refreshes
        /// `file_listing` — every commit leaves the listing snapshot one revision
        /// behind. The next reconcile must certify the commit's own writes inside ONE
        /// bounded scoped pass, and the pass after that must cost zero document/record
        /// reads (the fixed `.lomo/layout_head.rec` validation read is the per-entry
        /// preamble `recover_pending` pays, not projection drift). An unbounded or
        /// never-converging trail means every mutation pays a growing re-read tax.
        #[test]
        fn commit_written_files_converge_within_one_bounded_pass() {
            let scratch = tempdir().expect("workspace");
            write_doc(scratch.path(), "2026_09_10.md", &[("10:00:00", "seed")]);
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed rebuild");

            ctx.session
                .create_memo(CreateMemoRequest {
                    operation_id: OperationId::parse("cw-create").expect("op"),
                    relative_path: Some(RelativeWorkspacePath::parse("2026_09_10.md").expect("p")),
                    time_token: Some("12:00:00".to_owned()),
                    content: "committed through the SAF path".to_owned(),
                    expected_document_fingerprint: None,
                    pinned: false,
                    pending_promotes: Vec::new(),
                    chronology_epoch_ms: None,
                })
                .expect("create");

            ctx.counting.reset();
            let first = ctx
                .session
                .rebuild_projection()
                .expect("post-commit reconcile");
            assert!(
                !first.rewritten,
                "re-reading the commit's own writes proves them content-identical"
            );
            assert!(
                ctx.counting.reads() <= 16,
                "certifying one commit's durable writes cost {} reads — the stale \
                 listing evidence must converge in a bounded pass, not a re-scan: {:?}",
                ctx.counting.reads(),
                ctx.counting.paths()
            );

            ctx.counting.reset();
            ctx.session
                .rebuild_projection()
                .expect("follow-up reconcile");
            let rereads = ctx.counting.paths();
            assert!(
                rereads.iter().all(|path| path == ".lomo/layout_head.rec"),
                "once the listing rows are realigned the digest must short-circuit; \
                 only the fixed layout-head validation read remains: {rereads:?}"
            );
            assert_equivalent_to_fresh_scan(&ctx);
        }

        // ---------- watcher attestation granularity ----------

        /// Watchers legitimately emit directory-level paths; `cover_observed` attests
        /// every currently listed path beneath the prefix — and nothing outside it. A
        /// trash record observed only through its parent directory must still claim the
        /// memo, while an unobserved new document must stay deferred until a
        /// digest-diffed pass; over-covering would make attestation meaningless and
        /// under-covering silently drops soft deletes.
        #[test]
        fn observed_directory_prefix_attests_nested_trash_records() {
            let scratch = tempdir().expect("workspace");
            write_doc(scratch.path(), "2026_09_10.md", &[("10:00:00", "stayer")]);
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed rebuild");
            let ghost = "m_00000000000000000000000000000feed0";

            // A durable record for a memo the document never held — the watcher only
            // reports the trash *directory*, not the file.
            write_trash_record(
                &ctx.workspace,
                &TrashRecordV1::try_new(TrashRecordCreate {
                    memo_id: ghost.to_owned(),
                    source_path: "2026_09_10.md".to_owned(),
                    time_part: "10:02:00".to_owned(),
                    source_fingerprint: SourceFingerprint::of_bytes(b"ghost-doc")
                        .as_str()
                        .to_owned(),
                    chronology_epoch_ms: 1_757_400_000_000,
                    trashed_at_ms: 1_757_500_000_000,
                    body: "directory-attested trash body".to_owned(),
                    tags: Vec::new(),
                    attachments: Vec::new(),
                    reminders: Vec::new(),
                    has_todo: false,
                    has_url: false,
                })
                .expect("record"),
            );

            // Control: an unrelated new document arrives in the same listing diff and is
            // NOT inside the observed prefix — it must stay deferred, not sneak into the
            // same scoped pass.
            write_doc(
                scratch.path(),
                "2026_09_11.md",
                &[("09:00:00", "unobserved document memo")],
            );

            ctx.session
                .reconcile_observed_paths(&[
                    RelativeWorkspacePath::parse(".lomo/trash").expect("trash dir")
                ])
                .expect("directory-scoped reconcile");

            assert!(
                projected_ids(&ctx, true).contains(ghost),
                "a directory-level observation must still admit the nested record"
            );
            assert!(
                summaries(&ctx.session, false)
                    .iter()
                    .all(|summary| summary.source_path != "2026_09_11.md"),
                "an unobserved document must not be covered by a `.lomo/trash` prefix"
            );

            // The deferred document converges on the next digest-diffed pass — deferral
            // must be honest, not silent loss.
            ctx.session
                .rebuild_projection()
                .expect("the deferred document catches up");
            assert!(
                summaries(&ctx.session, false)
                    .iter()
                    .any(|summary| summary.source_path == "2026_09_11.md"),
                "the deferred document must converge on the next pass"
            );
            assert_equivalent_to_fresh_scan(&ctx);
        }

        // ---------- purge suppression is lane-scoped ----------

        /// A tombstone only suppresses the *trash lane* of its identity: a memo whose
        /// block still sits in a document stays projected (the document is the stronger
        /// attestation — the purge record never claims the doc lane), and the
        /// `purged_memo` row lands so a later trash record cannot resurrect it. But the
        /// SAF `Delete` commit arm writes `memo_trash` unconditionally — deleting a
        /// tombstoned memo produces a committed trash row no scan of the same durable
        /// facts could ever produce, because the standing tombstone suppresses the very
        /// record the commit just wrote. Finally, lifting the tombstone must let the
        /// surviving record claim the identity again.
        #[test]
        fn tombstone_over_a_doc_present_memo_suppresses_only_the_trash_lane() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "still alive")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed rebuild");
            let alive = projected_ids(&ctx, false);
            assert_eq!(alive.len(), 1, "fixture sanity");
            let target = alive.iter().next().expect("the memo").clone();

            write_purge_tombstone(&ctx.workspace, &target, "tb-purge");
            ctx.session
                .rebuild_projection()
                .expect("reconcile tombstone");
            assert!(
                projected_ids(&ctx, false).contains(&target),
                "a tombstone does not suppress a memo the document still attests"
            );
            assert_equivalent_to_fresh_scan(&ctx);

            // Suppression and re-claim, trash lane only: a tombstoned record never
            // projects, and lifting the tombstone lets the surviving record claim the
            // identity again — exactly once, in the trash lane.
            let ghost = "m_00000000000000000000000000000cafe0";
            write_trash_record(
                &ctx.workspace,
                &TrashRecordV1::try_new(TrashRecordCreate {
                    memo_id: ghost.to_owned(),
                    source_path: "2026_09_10.md".to_owned(),
                    time_part: "10:02:00".to_owned(),
                    source_fingerprint: SourceFingerprint::of_bytes(b"ghost-doc")
                        .as_str()
                        .to_owned(),
                    chronology_epoch_ms: 1_757_400_000_000,
                    trashed_at_ms: 1_757_500_000_000,
                    body: "suppressed then reclaimed".to_owned(),
                    tags: Vec::new(),
                    attachments: Vec::new(),
                    reminders: Vec::new(),
                    has_todo: false,
                    has_url: false,
                })
                .expect("record"),
            );
            write_purge_tombstone(&ctx.workspace, ghost, "tb-purge-ghost");
            ctx.session
                .rebuild_projection()
                .expect("reconcile suppressed record");
            assert!(
                !projected_ids(&ctx, true).contains(ghost)
                    && !projected_ids(&ctx, false).contains(ghost),
                "a tombstoned record must never project"
            );
            assert_equivalent_to_fresh_scan(&ctx);

            let ghost_tombstone =
                lomo_store::purge_record_relative_path(ghost).expect("ghost purge path");
            fs::remove_file(ctx.workspace.join(ghost_tombstone.as_str())).expect("lift tombstone");
            ctx.session
                .rebuild_projection()
                .expect("reconcile lifted tombstone");
            assert!(
                projected_ids(&ctx, true).contains(ghost)
                    && !projected_ids(&ctx, false).contains(ghost),
                "with suppression lifted the surviving record claims the memo — trash only"
            );
            assert_equivalent_to_fresh_scan(&ctx);

            // The standing tombstone must keep `delete_memo`'s committed projection equal
            // to what the same durable facts scan to: the block leaves the document and
            // the record it writes is already suppressed — no scan admits a trash row.
            let victim = summaries(&ctx.session, false)
                .into_iter()
                .find(|summary| summary.memo_id == target)
                .expect("victim row");
            ctx.session
                .delete_memo(DeleteMemoRequest {
                    operation_id: OperationId::parse("tb-delete").expect("op"),
                    memo_id: MemoId::parse(&target).expect("id"),
                    expected_document_fingerprint: victim.file_fingerprint,
                    trashed_at_ms: Some(1_757_500_000_000),
                })
                .expect("delete over a standing tombstone");
            assert!(
                !projected_ids(&ctx, true).contains(&target)
                    && !projected_ids(&ctx, false).contains(&target),
                "the commit arm must not project a trash row the standing tombstone \
                 already suppresses in every cold scan"
            );
        }
    }
}
