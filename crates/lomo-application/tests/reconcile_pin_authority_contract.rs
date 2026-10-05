//! Adversarial reconcile probes on pin authority: the cold-side pin axiom,
//! the three-way scoped-pin verdict, head stem-checks and rewalk
//! re-attestation/absorb. Merged from the numbered re-audit rounds.

mod tests {

    // adversarial-reaudit round 6: the P6-F1 batch
    // (`audit/16-修复-冷侧pin公理.md`) claims the cold-side pin axiom is closed:
    // `memo_pin` may only land on identities this projection produces a `memo`
    // row for; `.lomo/state` heads are the sole pin authority; incremental
    // reconcile and cold materialize converge on the same durable facts.
    //
    // The probes below attack what that batch's own mechanisms leave open:
    //
    // - `rewalk`'s `_ -> pin_removes` arm collapses two different durable
    //   answers: "the head says unpinned" (attested) and "no head exists"
    //   (unattested). `copy_saf_private_state` honors a carried projection-cache
    //   pin for an unattested identity, so the two paths must answer a revoked
    //   head identically — but the incremental arm's `pin_removes` deletes what
    //   the materialize arm carries. Probed head-on: the same durable revocation
    //   plus the same committed cache, differing only in whether the inert orphan
    //   state-object file is also deleted (which flips the pass from incremental
    //   to materialize).
    // - The three copy gates: an attested-unpinned identity must never resurrect
    //   a stale cache pin (gate 1 — the `OR IGNORE`/`EXISTS` guards alone would
    //   let it land, because a live row exists); an attested-but-purged identity
    //   keeps no pin even with a stale cache row; an unattested dead identity's
    //   cache pin must never land orphaned (gate 2).
    // - Corruption parity on dead tips: `pins()` still decodes and tip-checks
    //   every head — a pinned tip over a rowless identity with a missing
    //   timestamp must fail cold and incremental scans identically rather than
    //   being silently filtered.
    // - The cascade claim: batch permanent delete decomposes `memo_pin`
    //   explicitly (FK-independent), the single path relies on `ON DELETE
    //   CASCADE` under `PRAGMA foreign_keys=ON` — both must leave the identical
    //   dead-pin-free projection.

    #[cfg(test)]
    #[expect(
        clippy::expect_used,
        reason = "adversarial audit tests fail closed with panics on missing facts"
    )]
    mod cold_pin_axiom {
        use std::{
            collections::BTreeSet,
            fmt::Write as _,
            fs,
            path::{Path, PathBuf},
            sync::Arc,
        };

        use lomo_application::{
            DeleteMemoRequest, MemoFilters, MemoQuery, MemoSort, PermanentDeleteManyRequest,
            PermanentDeleteManyTarget, PermanentDeleteRequest, PinMemoRequest, PinPolicy,
            WorkspaceSession, WorkspaceSessionConfig,
        };
        use lomo_core::{CapabilityToken, OperationId, PageSize, PlatformActionExecutor};
        use lomo_platform_fs::FsPlatformActionExecutor;
        use lomo_workspace::{MemoId, WorkspaceGenerationId, WorkspaceRootId};
        use rusqlite::OptionalExtension;
        use tempfile::tempdir;

        struct Ctx {
            session: WorkspaceSession,
            workspace: PathBuf,
            cache_dir: PathBuf,
            _dirs: Vec<tempfile::TempDir>,
        }

        /// Private fixture directories are positional; the index is a fixture slot, not data.
        fn fixture_dir(dirs: &[tempfile::TempDir], index: usize) -> &Path {
            dirs.get(index).expect("fixture dir slot").path()
        }

        /// Opens a session on `workspace` against an existing `cache_dir` — the
        /// caller owns the cache tempdir, which is adopted into the fixture so the
        /// store file outlives the session.
        fn open_session_with_cache(
            workspace: &Path,
            cache_dir: tempfile::TempDir,
        ) -> Result<Ctx, lomo_core::LomoError> {
            let mut dirs = (0..6)
                .map(|_| tempdir().expect("fixture dir"))
                .collect::<Vec<_>>();
            let workspace_path = workspace.to_path_buf();
            let real = Arc::new(FsPlatformActionExecutor::new(fixture_dir(&dirs, 4))?);
            let capability = CapabilityToken::parse("notes")?;
            real.bind_root(capability.clone(), &workspace_path)?;
            let executor: Arc<dyn PlatformActionExecutor> = real;
            let session = WorkspaceSession::open(
                WorkspaceSessionConfig {
                    capability,
                    root_id: WorkspaceRootId::Notes,
                    workspace_generation: WorkspaceGenerationId::mint()?,
                    time_zone: "UTC".to_owned(),
                    date_format: lomo_application::calendar::DateFormat::default(),
                    state_dir: fixture_dir(&dirs, 1).to_path_buf(),
                    cache_dir: cache_dir.path().to_path_buf(),
                    runtime_dir: fixture_dir(&dirs, 3).to_path_buf(),
                    exchange_dir: fixture_dir(&dirs, 4).to_path_buf(),
                    media_stage_root: fixture_dir(&dirs, 5).to_path_buf(),
                },
                executor,
            )?;
            let cache_dir_path = cache_dir.path().to_path_buf();
            dirs.push(cache_dir);
            Ok(Ctx {
                session,
                workspace: workspace_path,
                cache_dir: cache_dir_path,
                _dirs: dirs,
            })
        }

        fn open_session(workspace: &Path) -> Ctx {
            open_session_with_cache(workspace, tempdir().expect("cache dir")).expect("session")
        }

        /// A second session on the same workspace with fresh private directories: its
        /// open can only materialize from durable facts — the "全量" oracle.
        fn fresh_oracle(workspace: &Path) -> Result<Ctx, lomo_core::LomoError> {
            open_session_with_cache(workspace, tempdir().expect("oracle cache"))
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

        /// The public observable projection: memo rows, bodies, lifecycle bits, pins,
        /// history windows, attachment observations, tag counts and tasks.
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

        /// Every row of one table, ordered, rendered textually.
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

        /// The raw store rows a byte-for-byte rebuild must reproduce — memo projections,
        /// lifecycle membership (with `record_digest`), tags, attachments, pins, purge
        /// tombstones and the committed listing snapshot.
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
                    "SELECT memo_id, trashed_at_ms, record_digest FROM memo_trash ORDER BY memo_id",
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

        /// Every `memo_pin` row committed in a cache, `(memo_id, pinned_at_ms)` pairs.
        fn pin_rows(cache_dir: &Path) -> Vec<(String, i64)> {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            let mut stmt = conn
                .prepare("SELECT memo_id, pinned_at_ms FROM memo_pin ORDER BY memo_id")
                .expect("pin query");
            stmt.query_map([], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?))
            })
            .expect("pin rows")
            .map(|row| row.expect("row"))
            .collect()
        }

        /// The result of the same workspace under a scoped/watcher-guided reconcile
        /// (the live session) must equal a sibling session that could only fully
        /// materialize the durable facts — on both the public surface and the raw rows.
        fn assert_equivalent_to_fresh_scan(ctx: &Ctx) {
            let oracle = fresh_oracle(&ctx.workspace).expect("fresh oracle session");
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

        /// Finds the memo id of the block carrying `needle` in the live projection.
        fn memo_by_body(ctx: &Ctx, needle: &str) -> String {
            summaries(&ctx.session, false)
                .into_iter()
                .find(|summary| {
                    ctx.session
                        .projected_memo(&summary.memo_id)
                        .expect("snapshot")
                        .is_some_and(|snapshot| snapshot.body.contains(needle))
                })
                .expect("a memo carrying the needle body")
                .memo_id
        }

        /// Pins a memo through the app path so the durable state tip keeps
        /// `pinned=true` — the fact every pin probe below depends on. The rebuild
        /// realigns the committed `memo_pin` row to the durable tip's timestamp
        /// (the mutation publishes a wall-clock value the scan later rewrites).
        fn pin_active(ctx: &Ctx, memo_id: &str, operation_id: &str) {
            ctx.session
                .pin_memo(
                    PinMemoRequest::new(
                        OperationId::parse(operation_id).expect("operation id"),
                        MemoId::parse(memo_id).expect("memo id"),
                        PinPolicy::Pinned { at_ms: None },
                    )
                    .expect("pin request"),
                )
                .expect("pin");
            ctx.session
                .rebuild_projection()
                .expect("the pin's durable state records reconcile");
            assert!(
                summaries(&ctx.session, false)
                    .iter()
                    .any(|summary| summary.memo_id == memo_id && summary.is_pinned),
                "fixture sanity: the memo is pinned"
            );
        }

        /// Trashes a memo through the app path: the document block is removed, a
        /// durable trash record is written, and the state tip keeps `pinned=true`
        /// when the memo was pinned — exactly the durable shape a peer replays.
        fn app_trash(ctx: &Ctx, memo_id: &str, operation_id: &str) {
            let summary = summaries(&ctx.session, false)
                .into_iter()
                .find(|summary| summary.memo_id == memo_id)
                .expect("active summary");
            ctx.session
                .delete_memo(DeleteMemoRequest {
                    operation_id: OperationId::parse(operation_id).expect("operation id"),
                    memo_id: MemoId::parse(memo_id).expect("memo id"),
                    expected_document_fingerprint: summary.file_fingerprint,
                    trashed_at_ms: Some(1_757_500_000_000),
                })
                .expect("app trash");
            ctx.session
                .rebuild_projection()
                .expect("the trash's durable records reconcile");
            assert!(
                projected_ids(ctx, true).contains(memo_id),
                "fixture sanity: the memo is committed trashed"
            );
        }

        /// Absolute path of a memo's canonical v2 state head inside `workspace`.
        fn state_head_file(workspace: &Path, memo_id: &str) -> PathBuf {
            let paths = lomo_workspace::LomoPaths::for_workspace_with_layout(
                Path::new(""),
                lomo_workspace::LomoLayoutVersion::V2,
            );
            workspace.join(lomo_workspace::state_head_path(&paths, memo_id))
        }

        /// Absolute path of the tip object the durable head currently points at.
        fn state_tip_object_file(workspace: &Path, memo_id: &str) -> PathBuf {
            let paths = lomo_workspace::LomoPaths::for_workspace_with_layout(
                Path::new(""),
                lomo_workspace::LomoLayoutVersion::V2,
            );
            let head_path = state_head_file(workspace, memo_id);
            let record = lomo_workspace::decode_record(&fs::read(&head_path).expect("head file"))
                .expect("decode head");
            let head: lomo_workspace::StateHead =
                serde_json::from_str(&record.payload.body_json).expect("head json");
            workspace.join(lomo_workspace::state_revision_path(
                &paths,
                &head.head_revision_id,
            ))
        }

        /// Snapshots a committed projection cache into `dst`: checkpoint the WAL so
        /// the standalone file image carries every committed row, then copy it.
        fn snapshot_cache(src_cache_dir: &Path, dst_cache_dir: &tempfile::TempDir) {
            let src_db = src_cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&src_db).expect("source store db");
            conn.execute_batch("PRAGMA wal_checkpoint(TRUNCATE)")
                .expect("checkpoint source wal");
            drop(conn);
            let dst_db = dst_cache_dir.path().join(".lomo-sqlite").join("store.db");
            fs::create_dir_all(dst_db.parent().expect("dst parent")).expect("dst dir");
            fs::copy(&src_db, &dst_db).expect("cache snapshot");
        }

        /// Whether `memo_id` is pinned in this session's live projection.
        fn is_pinned(ctx: &Ctx, memo_id: &str) -> bool {
            summaries(&ctx.session, false)
                .iter()
                .any(|summary| summary.memo_id == memo_id && summary.is_pinned)
        }

        // ---------- path-selection divergence on a revoked state head ----------

        /// The `_ -> pin_removes` arm conflates "the durable head answered
        /// unpinned" with "no durable head exists". For an unattested identity the
        /// cold-side carry-over (`copy_saf_private_state`) grants the old
        /// projection cache the pin verdict — so whether the pin survives a
        /// revoked head must not depend on which path serves the reconcile.
        ///
        /// Two reconciles face the same durable revocation — the head file is gone
        /// — over the same committed cache carrying the pin row. They differ only
        /// in whether the now-orphaned tip object file is also deleted: deleting
        /// it makes the pass unclassifiable (`state_object_removed` is never
        /// provable), forcing the materialize arm; keeping it leaves the pass
        /// incremental. An orphaned state object is inert — cold scans never read
        /// objects standalone — so the pin answer must not flip with it.
        #[test]
        fn a_revoked_state_head_must_answer_identically_on_both_paths() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "pinned victim")],
            );
            let ctx_a = open_session(scratch.path());
            ctx_a.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx_a, "pinned victim");
            pin_active(&ctx_a, &victim, "pin-victim");

            // Snapshot the committed cache — the materialize arm must read exactly
            // the same carried pin row the incremental arm starts from.
            let cache_b = tempdir().expect("cache b");
            snapshot_cache(&ctx_a.cache_dir, &cache_b);
            let tip_object = state_tip_object_file(&ctx_a.workspace, &victim);

            // Incremental arm: only the head is revoked. The diff classifies as
            // `state_head_removed`, the re-walk sees no tip, and `pin_removes` is
            // the answer the pass commits.
            fs::remove_file(state_head_file(&ctx_a.workspace, &victim)).expect("revoke head");
            ctx_a
                .session
                .rebuild_projection()
                .expect("the head revocation reconciles");
            let incremental_answer = is_pinned(&ctx_a, &victim);

            // Materialize arm on the cloned cache: same revoked head, plus the
            // inert orphan object deleted — that alone flips the pass into the
            // unclassifiable full-scan fallback.
            fs::remove_file(&tip_object).expect("remove orphan tip object");
            let ctx_b = open_session_with_cache(&ctx_a.workspace, cache_b).expect("session b");
            ctx_b
                .session
                .rebuild_projection()
                .expect("the same revocation materializes");
            let materialize_answer = is_pinned(&ctx_b, &victim);

            assert_eq!(
                incremental_answer, materialize_answer,
                "the same durable revocation over the same committed cache must \
                 produce the same pin verdict — incremental dropped the carried \
                 pin ({incremental_answer}) while materialize kept it \
                 ({materialize_answer})"
            );
        }

        // ---------- copy gate 1: attested identities never resurrect stale pins ----------

        /// A workspace unpin lands the durable `pinned=false` tip on a second
        /// device. When that device's reconcile takes the materialize path, the
        /// stale cache pin it still carries is evidence durable already answered —
        /// the attested gate must skip the row wholesale, not merely guard its
        /// insert, because the memo row itself survives and would happily accept
        /// the stale pin. This isolates gate 1: without it the `EXISTS`/`OR
        /// IGNORE` pair lets the stale pin land.
        #[test]
        fn an_attested_unpinned_identity_never_resurrects_a_cached_pin_through_materialize() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "pinned victim")],
            );
            let ctx_a = open_session(scratch.path());
            ctx_a.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx_a, "pinned victim");
            pin_active(&ctx_a, &victim, "pin-victim");

            // The second device's cache still carries the pinned row.
            let cache_b = tempdir().expect("cache b");
            snapshot_cache(&ctx_a.cache_dir, &cache_b);

            // Device A unpins: a new `pinned=false` tip lands — durable has now
            // answered "unpinned" for this identity.
            ctx_a
                .session
                .pin_memo(
                    PinMemoRequest::new(
                        OperationId::parse("unpin-victim").expect("operation id"),
                        MemoId::parse(&victim).expect("memo id"),
                        PinPolicy::Unpinned,
                    )
                    .expect("unpin request"),
                )
                .expect("unpin");
            ctx_a
                .session
                .rebuild_projection()
                .expect("unpin reconciles");
            assert!(!is_pinned(&ctx_a, &victim), "fixture sanity: unpinned");

            // Device B materializes over the same durable facts: an
            // unclassifiable path makes the pass a full scan rather than the
            // incremental apply the unpin head diff alone would permit.
            fs::write(
                ctx_a.workspace.join(".lomo/unscoped.evidence"),
                b"not a durable record".as_slice(),
            )
            .expect("unclassifiable path");
            let ctx_b = open_session_with_cache(&ctx_a.workspace, cache_b).expect("session b");
            ctx_b
                .session
                .rebuild_projection()
                .expect("the materialize pass must converge");

            assert!(
                !is_pinned(&ctx_b, &victim),
                "durable answered unpinned — the stale cache pin must never land"
            );
            assert!(
                pin_rows(&ctx_b.cache_dir).is_empty(),
                "no memo_pin row may survive the attested gate"
            );
            assert_equivalent_to_fresh_scan(&ctx_b);
        }

        /// Attestation is not revoked by a purge tombstone: a permanently deleted
        /// identity's state head still owns its pin answer. A stale cache pin from
        /// the pre-delete projection must not ride the carry-over back into a
        /// materialized cache — the attested gate fires before the orphan guard.
        #[test]
        fn a_purged_attested_identity_cannot_carry_a_stale_cache_pin() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "pinned victim")],
            );
            let ctx_a = open_session(scratch.path());
            ctx_a.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx_a, "pinned victim");
            pin_active(&ctx_a, &victim, "pin-victim");
            app_trash(&ctx_a, &victim, "del-victim");

            // The second cache still holds the trashed+pinned row.
            let cache_b = tempdir().expect("cache b");
            snapshot_cache(&ctx_a.cache_dir, &cache_b);

            ctx_a
                .session
                .permanently_delete_memo(&PermanentDeleteRequest {
                    operation_id: OperationId::parse("purge-victim").expect("operation id"),
                    memo_id: MemoId::parse(&victim).expect("memo id"),
                })
                .expect("permanent delete");

            fs::write(
                ctx_a.workspace.join(".lomo/unscoped.evidence"),
                b"not a durable record".as_slice(),
            )
            .expect("unclassifiable path");
            let ctx_b = open_session_with_cache(&ctx_a.workspace, cache_b).expect("session b");
            ctx_b
                .session
                .rebuild_projection()
                .expect("the materialize pass must converge");

            assert!(
                !projected_ids(&ctx_b, false).contains(&victim)
                    && !projected_ids(&ctx_b, true).contains(&victim),
                "the purged identity stays out of both lanes"
            );
            assert!(
                pin_rows(&ctx_b.cache_dir)
                    .iter()
                    .all(|(id, _)| id != &victim),
                "the stale cache pin must not be carried for an attested identity"
            );
            assert_equivalent_to_fresh_scan(&ctx_b);
        }

        // ---------- copy gate 2: orphan cache pins never land ----------

        /// The durable head is gone AND the memo row is gone (its document was
        /// deleted): durable has no pin answer and no row to pin. A carried cache
        /// pin for such an identity must be stopped by the `WHERE EXISTS(memo)`
        /// guard — `OR IGNORE` alone would also drop it, so this probes the pair
        /// converging on "no row, no pin" without an FK violation.
        #[test]
        fn a_headless_dead_identity_cache_pin_never_lands_orphaned_through_materialize() {
            let scratch = tempdir().expect("workspace");
            write_doc(scratch.path(), "2026_09_10.md", &[("10:00:00", "stayer")]);
            write_doc(
                scratch.path(),
                "2026_09_11.md",
                &[("10:01:00", "pinned victim")],
            );
            let ctx_a = open_session(scratch.path());
            ctx_a.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx_a, "pinned victim");
            pin_active(&ctx_a, &victim, "pin-victim");

            let cache_b = tempdir().expect("cache b");
            snapshot_cache(&ctx_a.cache_dir, &cache_b);

            // Revoke the durable attestation and remove the row source together
            // (whole-file removal, not a block rewrite — a bound identity dropped
            // inside a surviving document is an ambiguity guard, not this probe's
            // target); the extra unclassifiable path forces the materialize arm.
            fs::remove_file(state_head_file(&ctx_a.workspace, &victim)).expect("revoke head");
            fs::remove_file(ctx_a.workspace.join("2026_09_11.md")).expect("remove doc");
            fs::write(
                ctx_a.workspace.join(".lomo/unscoped.evidence"),
                b"not a durable record".as_slice(),
            )
            .expect("unclassifiable path");

            let ctx_b = open_session_with_cache(&ctx_a.workspace, cache_b).expect("session b");
            ctx_b
                .session
                .rebuild_projection()
                .expect("the materialize pass must converge, not FK-violate");

            assert!(
                !projected_ids(&ctx_b, false).contains(&victim)
                    && !projected_ids(&ctx_b, true).contains(&victim),
                "the rowless identity stays out of the projection"
            );
            assert!(
                pin_rows(&ctx_b.cache_dir)
                    .iter()
                    .all(|(id, _)| id != &victim),
                "the orphan cache pin must never land"
            );
            assert_equivalent_to_fresh_scan(&ctx_b);
        }

        // ---------- corruption parity on dead tips ----------

        /// `pins()` decodes and tip-checks every head before the `live_ids` filter
        /// — corruption parity means a malformed pinned tip over a rowless
        /// identity must fail the cold scan the same way `state_scoped` fails the
        /// incremental re-walk. Filtering before validating would silently absorb
        /// a corrupt durable record.
        #[test]
        fn a_corrupt_pinned_tip_over_a_rowless_identity_fails_both_paths_identically() {
            let scratch = tempdir().expect("workspace");
            write_doc(scratch.path(), "2026_09_10.md", &[("10:00:00", "stayer")]);
            let ctx_a = open_session(scratch.path());
            ctx_a.session.rebuild_projection().expect("seed");

            // A peer-delivered pinned tip with no timestamp: pinned=true but
            // pinned_at_ms absent — `pin_timestamp` must reject it on both paths.
            let paths = lomo_workspace::LomoPaths::for_workspace_with_layout(
                Path::new(""),
                lomo_workspace::LomoLayoutVersion::V2,
            );
            let mut revision =
                lomo_workspace::StateRevisionV2::create(lomo_workspace::StateRevisionCreate {
                    memo_id: "ghost-corrupt",
                    parent: None,
                    pinned: true,
                    trashed: false,
                    pinned_at_ms: Some(1_757_500_000_000),
                    trashed_at_ms: None,
                    pin_operation_id: Some("peer-pin".to_owned()),
                    trash_operation_id: None,
                    canonical_metadata: "",
                    created_at_ms: 1_757_500_000_000,
                })
                .expect("state revision");
            revision.pinned_at_ms = None;
            let head = lomo_workspace::StateHead {
                memo_id: "ghost-corrupt".to_owned(),
                head_revision_id: revision.revision_id.clone(),
            };
            let object = lomo_workspace::encode_record(&lomo_workspace::LomoPayload {
                kind: lomo_workspace::LomoRecordKind::State,
                record_id: revision.revision_id.as_str().to_owned(),
                body_json: serde_json::to_string(&revision).expect("revision json"),
            })
            .expect("state object record");
            let head_record = lomo_workspace::encode_record(&lomo_workspace::LomoPayload {
                kind: lomo_workspace::LomoRecordKind::State,
                record_id: "head:ghost-corrupt".to_owned(),
                body_json: serde_json::to_string(&head).expect("head json"),
            })
            .expect("state head record");
            let object_path = ctx_a.workspace.join(lomo_workspace::state_revision_path(
                &paths,
                &revision.revision_id,
            ));
            let head_path = state_head_file(&ctx_a.workspace, "ghost-corrupt");
            fs::create_dir_all(object_path.parent().expect("object dir")).expect("object dir");
            fs::create_dir_all(head_path.parent().expect("head dir")).expect("head dir");
            fs::write(&object_path, object).expect("write state object");
            fs::write(&head_path, head_record).expect("write state head");

            let incremental = ctx_a
                .session
                .rebuild_projection()
                .expect_err("a corrupt pinned tip must fail the scoped reconcile");
            let cold = match fresh_oracle(&ctx_a.workspace) {
                Ok(_oracle) => panic!("the same corrupt tip must fail a fresh materialize"),
                Err(error) => error,
            };
            assert_eq!(
                incremental.code(),
                cold.code(),
                "both paths must surface the same corruption code"
            );
            assert_eq!(incremental.code(), "invalid_pin_timestamp");
        }

        // ---------- cascade coverage: batch permanent delete ----------

        /// `delete_saf_projection_rows` deletes `memo_pin` explicitly after the
        /// parent row — the batch path must leave no pin row even if the cascade
        /// were off — while the single-delete path relies on `ON DELETE CASCADE`
        /// under `PRAGMA foreign_keys=ON`. Both must converge with a fresh
        /// materialize.
        #[test]
        fn a_batch_permanent_delete_of_a_pinned_memo_cascades_the_pin_identically() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "pinned victim")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "pinned victim");
            pin_active(&ctx, &victim, "pin-victim");
            app_trash(&ctx, &victim, "del-victim");

            let trashed = summaries(&ctx.session, true)
                .into_iter()
                .find(|summary| summary.memo_id == victim)
                .expect("committed trashed summary");
            ctx.session
                .permanently_delete_many(&PermanentDeleteManyRequest {
                    operation_id: OperationId::parse("purge-batch").expect("operation id"),
                    targets: vec![PermanentDeleteManyTarget {
                        memo_id: MemoId::parse(&victim).expect("memo id"),
                        source_path: trashed.source_path.clone(),
                        expected_revision: trashed.content_revision,
                        expected_fingerprint: trashed.file_fingerprint,
                    }],
                })
                .expect("batch permanent delete");
            ctx.session
                .rebuild_projection()
                .expect("the post-delete reconcile converges");

            assert!(
                !projected_ids(&ctx, false).contains(&victim)
                    && !projected_ids(&ctx, true).contains(&victim),
                "the purged identity stays out of both lanes"
            );
            assert!(
                pin_rows(&ctx.cache_dir).iter().all(|(id, _)| id != &victim),
                "the pin row must die with its memo row"
            );
            assert_equivalent_to_fresh_scan(&ctx);
        }
    }

    // adversarial-reaudit round 7: the P7-F1 batch
    // (`audit/20-修复-head撤销三值裁决.md`) claims the head-revocation verdict is
    // closed: `ScopedPinVerdict` separates `Pinned`/`Unpinned`/`Unattested`, and
    // the incremental re-walk carries a committed `memo_pin` row forward for an
    // unattested identity whose `memo` row survives — verbatim, the same row
    // `copy_saf_private_state` ships on the materialize path.
    //
    // The probes below attack what the batch's own mechanisms leave open:
    //
    // - The re-emitted pin is the pre-apply committed row, and the row it lands
    //   on may be deleted and re-inserted inside the same apply. Both mid-apply
    //   delete paths are probed under carry: `apply_purged_id` (tombstone over a
    //   committed-trashed dual-lane row the document still emits) and
    //   `delete_memo_row` (`retire_memo` on a removed record whose document
    //   re-emits). A sentinel `pinned_at_ms` stamped into the committed cache
    //   proves the carried value is the cache row's bytes, not a re-derived one.
    // - `Unattested ∧ dead` must emit nothing: the pin leaves with the row on
    //   both paths (`WHERE EXISTS(memo)` cold / no fact + FK cascade
    //   incremental).
    // - Idempotent re-emit: a pure revocation whose only facts are
    //   `listing_removes` + `pin_upserts(committed)` must leave the projection
    //   digest untouched — `rewritten == false`, no phantom clock bump.
    // - Error parity the fix does not touch: a stray *stale duplicate* state head
    //   (canonical stem absent or divergent from the canonical tip) is corruption
    //   evidence a cold `pins()` rejects via `state_head_changed` —
    //   `state_head_missing` for a missing canonical head — while the scoped pass
    //   only ever reads the canonical path and never consults the stray at all.
    // - Attested-cache asymmetry: cold gate 1 treats a committed pin over an
    //   attested-unpinned identity as stale evidence it never copies; the
    //   incremental side only re-derives pin facts for memos inside the changed
    //   scope, so the same stale row over the same durable facts must not survive
    //   on one path and die on the other.

    #[cfg(test)]
    #[expect(
        clippy::expect_used,
        reason = "adversarial audit tests fail closed with panics on missing facts"
    )]
    mod pin_verdicts {
        use std::{
            collections::BTreeSet,
            fmt::Write as _,
            fs,
            path::{Path, PathBuf},
            sync::Arc,
        };

        use lomo_application::{
            MemoFilters, MemoQuery, MemoSort, PinMemoRequest, PinPolicy, WorkspaceSession,
            WorkspaceSessionConfig,
        };
        use lomo_core::{CapabilityToken, OperationId, PageSize, PlatformActionExecutor};
        use lomo_platform_fs::FsPlatformActionExecutor;
        use lomo_workspace::{
            MemoId, SourceFingerprint, TrashRecordCreate, TrashRecordV1, WorkspaceGenerationId,
            WorkspaceRootId, trash_record_relative_path, write_trash_record_atomic,
        };
        use tempfile::tempdir;

        /// A `pinned_at_ms` no durable tip or mutation ever produced — landing it
        /// proves the carried value is the committed cache row verbatim.
        const SENTINEL_PIN_MS: i64 = 42_424_242;

        struct Ctx {
            session: WorkspaceSession,
            workspace: PathBuf,
            cache_dir: PathBuf,
            _dirs: Vec<tempfile::TempDir>,
        }

        /// Private fixture directories are positional; the index is a fixture slot, not data.
        fn fixture_dir(dirs: &[tempfile::TempDir], index: usize) -> &Path {
            dirs.get(index).expect("fixture dir slot").path()
        }

        /// Opens a session on `workspace` against an existing `cache_dir` — the
        /// caller owns the cache tempdir, which is adopted into the fixture so the
        /// store file outlives the session.
        fn open_session_with_cache(
            workspace: &Path,
            cache_dir: tempfile::TempDir,
        ) -> Result<Ctx, lomo_core::LomoError> {
            let mut dirs = (0..6)
                .map(|_| tempdir().expect("fixture dir"))
                .collect::<Vec<_>>();
            let workspace_path = workspace.to_path_buf();
            let real = Arc::new(FsPlatformActionExecutor::new(fixture_dir(&dirs, 4))?);
            let capability = CapabilityToken::parse("notes")?;
            real.bind_root(capability.clone(), &workspace_path)?;
            let executor: Arc<dyn PlatformActionExecutor> = real;
            let session = WorkspaceSession::open(
                WorkspaceSessionConfig {
                    capability,
                    root_id: WorkspaceRootId::Notes,
                    workspace_generation: WorkspaceGenerationId::mint()?,
                    time_zone: "UTC".to_owned(),
                    date_format: lomo_application::calendar::DateFormat::default(),
                    state_dir: fixture_dir(&dirs, 1).to_path_buf(),
                    cache_dir: cache_dir.path().to_path_buf(),
                    runtime_dir: fixture_dir(&dirs, 3).to_path_buf(),
                    exchange_dir: fixture_dir(&dirs, 4).to_path_buf(),
                    media_stage_root: fixture_dir(&dirs, 5).to_path_buf(),
                },
                executor,
            )?;
            let cache_dir_path = cache_dir.path().to_path_buf();
            dirs.push(cache_dir);
            Ok(Ctx {
                session,
                workspace: workspace_path,
                cache_dir: cache_dir_path,
                _dirs: dirs,
            })
        }

        fn open_session(workspace: &Path) -> Ctx {
            open_session_with_cache(workspace, tempdir().expect("cache dir")).expect("session")
        }

        /// A second session on the same workspace with fresh private directories: its
        /// open can only materialize from durable facts — the "全量" oracle.
        fn fresh_oracle(workspace: &Path) -> Result<Ctx, lomo_core::LomoError> {
            open_session_with_cache(workspace, tempdir().expect("oracle cache"))
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

        /// The public observable projection: memo rows, bodies, lifecycle bits, pins,
        /// history windows, attachment observations, tag counts and tasks.
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

        /// Every row of one table, ordered, rendered textually.
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

        /// The projection rows both reconcile arms must commit identically.
        ///
        /// `file_listing` and the listing digest meta are deliberately excluded:
        /// the materialize arm is forced by an extra unclassifiable path the
        /// incremental arm's listing never contained, so the committed listing
        /// legitimately differs while the projection content must not.
        fn projection_dump(cache_dir: &Path) -> String {
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
                    "SELECT memo_id, trashed_at_ms, record_digest FROM memo_trash ORDER BY memo_id",
                ),
                (
                    "purged_memo",
                    "SELECT memo_id FROM purged_memo ORDER BY memo_id",
                ),
                (
                    "revision_index",
                    "SELECT memo_id, history_record_id, revision, created_at_ms, file_fingerprint \
                     FROM revision_index ORDER BY memo_id, history_record_id",
                ),
                ("stats", "SELECT key, value_i64 FROM stats ORDER BY key"),
            ] {
                table_dump(&conn, tag, sql, &mut out);
            }
            out
        }

        /// Every `memo_pin` row committed in a cache, `(memo_id, pinned_at_ms)` pairs.
        fn pin_rows(cache_dir: &Path) -> Vec<(String, i64)> {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            let mut stmt = conn
                .prepare("SELECT memo_id, pinned_at_ms FROM memo_pin ORDER BY memo_id")
                .expect("pin query");
            stmt.query_map([], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?))
            })
            .expect("pin rows")
            .map(|row| row.expect("row"))
            .collect()
        }

        /// Writes `pinned_at_ms` into the committed `memo_pin` row out of band —
        /// the cache-state a stale device snapshot or restored store can carry.
        /// `INSERT OR REPLACE` keeps the row well-formed so only the carried
        /// *value* is under test.
        fn stamp_committed_pin(cache_dir: &Path, memo_id: &str, pinned_at_ms: i64) {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            conn.execute(
                "INSERT OR REPLACE INTO memo_pin(memo_id, pinned_at_ms) VALUES(?1, ?2)",
                rusqlite::params![memo_id, pinned_at_ms],
            )
            .expect("stamp committed pin");
        }

        /// Snapshots a committed projection cache into `dst`: checkpoint the WAL so
        /// the standalone file image carries every committed row, then copy it.
        fn snapshot_cache(src_cache_dir: &Path, dst_cache_dir: &tempfile::TempDir) {
            let src_db = src_cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&src_db).expect("source store db");
            conn.execute_batch("PRAGMA wal_checkpoint(TRUNCATE)")
                .expect("checkpoint source wal");
            drop(conn);
            let dst_db = dst_cache_dir.path().join(".lomo-sqlite").join("store.db");
            fs::create_dir_all(dst_db.parent().expect("dst parent")).expect("dst dir");
            fs::copy(&src_db, &dst_db).expect("cache snapshot");
        }

        /// Whether `memo_id` is pinned in this session's live projection.
        fn is_pinned(ctx: &Ctx, memo_id: &str) -> bool {
            summaries(&ctx.session, false)
                .iter()
                .any(|summary| summary.memo_id == memo_id && summary.is_pinned)
        }

        fn projected_ids(ctx: &Ctx, trash_only: bool) -> BTreeSet<String> {
            summaries(&ctx.session, trash_only)
                .iter()
                .map(|summary| summary.memo_id.clone())
                .collect()
        }

        /// Finds the memo id of the block carrying `needle` in the live projection.
        fn memo_by_body(ctx: &Ctx, needle: &str) -> String {
            summaries(&ctx.session, false)
                .into_iter()
                .find(|summary| {
                    ctx.session
                        .projected_memo(&summary.memo_id)
                        .expect("snapshot")
                        .is_some_and(|snapshot| snapshot.body.contains(needle))
                })
                .expect("a memo carrying the needle body")
                .memo_id
        }

        /// Pins a memo through the app path so the durable state tip keeps
        /// `pinned=true` — the fact every pin probe below depends on. The rebuild
        /// realigns the committed `memo_pin` row to the durable tip's timestamp
        /// (the mutation publishes a wall-clock value the scan later rewrites).
        fn pin_active(ctx: &Ctx, memo_id: &str, operation_id: &str) {
            ctx.session
                .pin_memo(
                    PinMemoRequest::new(
                        OperationId::parse(operation_id).expect("operation id"),
                        MemoId::parse(memo_id).expect("memo id"),
                        PinPolicy::Pinned { at_ms: None },
                    )
                    .expect("pin request"),
                )
                .expect("pin");
            ctx.session
                .rebuild_projection()
                .expect("the pin's durable state records reconcile");
            assert!(
                summaries(&ctx.session, false)
                    .iter()
                    .any(|summary| summary.memo_id == memo_id && summary.is_pinned),
                "fixture sanity: the memo is pinned"
            );
        }

        /// Absolute path of a memo's canonical v2 state head inside `workspace`.
        fn state_head_file(workspace: &Path, memo_id: &str) -> PathBuf {
            let paths = lomo_workspace::LomoPaths::for_workspace_with_layout(
                Path::new(""),
                lomo_workspace::LomoLayoutVersion::V2,
            );
            workspace.join(lomo_workspace::state_head_path(&paths, memo_id))
        }

        /// Writes a durable trash record for `memo_id` at its canonical hashed path.
        fn write_trash_record(workspace: &Path, record: &TrashRecordV1) {
            let rel = trash_record_relative_path(&record.memo_id).expect("trash path");
            let abs = workspace.join(rel.as_str());
            fs::create_dir_all(abs.parent().expect("record dir")).expect("record dir");
            write_trash_record_atomic(&abs, record).expect("write record");
        }

        /// Deletes the durable trash record for `memo_id`.
        fn remove_trash_record(workspace: &Path, memo_id: &str) {
            let rel = trash_record_relative_path(memo_id).expect("trash path");
            fs::remove_file(workspace.join(rel.as_str())).expect("remove record");
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

        /// One doc-absent ghost record whose only authority is its own bytes.
        fn ghost_record(
            memo_id: &str,
            source_path: &str,
            claimed: &[u8],
            trashed_at_ms: i64,
        ) -> TrashRecordV1 {
            TrashRecordV1::try_new(TrashRecordCreate {
                memo_id: memo_id.to_owned(),
                source_path: source_path.to_owned(),
                time_part: "10:02:00".to_owned(),
                source_fingerprint: SourceFingerprint::of_bytes(claimed).as_str().to_owned(),
                chronology_epoch_ms: 1_757_400_000_000,
                trashed_at_ms,
                body: "ghost trash body".to_owned(),
                tags: Vec::new(),
                attachments: Vec::new(),
                reminders: Vec::new(),
                has_todo: false,
                has_url: false,
            })
            .expect("record")
        }

        /// The committed dual-lane state: a document emitting `victim`'s identity
        /// plus a durable record claiming it at the same path.
        fn commit_dual_lane(ctx: &Ctx, victim: &str) {
            write_trash_record(
                &ctx.workspace,
                &ghost_record(victim, "2026_09_10.md", b"doc", 1_757_500_000_000),
            );
            ctx.session
                .rebuild_projection()
                .expect("a claim matching the live document commits dual lane");
            assert!(
                projected_ids(ctx, true).contains(victim),
                "fixture sanity: the victim is committed trashed"
            );
        }

        /// Drops the path that defeats scope proving so the same workspace
        /// materializes on the materialize arm.
        fn force_materialize_path(workspace: &Path) {
            fs::write(
                workspace.join(".lomo/unscoped.evidence"),
                b"not a durable record".as_slice(),
            )
            .expect("unclassifiable path");
        }

        /// Both reconcile arms over the same committed cache must commit the same
        /// projection rows and answer the same pin verdict — the carried cache row
        /// is transport on both, never re-derivation.
        fn assert_carry_arms_equal(ctx_a: &Ctx, ctx_b: &Ctx, memo_id: &str) {
            assert_eq!(
                public_dump(&ctx_a.session),
                public_dump(&ctx_b.session),
                "the incremental and materialize arms diverged on the public surface"
            );
            assert_eq!(
                projection_dump(&ctx_a.cache_dir),
                projection_dump(&ctx_b.cache_dir),
                "the incremental and materialize arms committed different projection rows"
            );
            assert_eq!(
                pin_rows(&ctx_a.cache_dir),
                pin_rows(&ctx_b.cache_dir),
                "the arms disagree on the carried memo_pin row set"
            );
            assert_eq!(
                is_pinned(ctx_a, memo_id),
                is_pinned(ctx_b, memo_id),
                "the same durable facts plus the same committed cache flipped the pin verdict"
            );
        }

        // ---------- carry through a mid-apply delete+reinsert (purge arm) ----------

        /// `apply_purged_id` deletes the tombstoned identity's trashed row mid-apply
        /// — the FK cascade takes `memo_pin` with it — before `memo_upserts`
        /// reinserts the document lane. The committed pin the re-walk read is now
        /// a row this transaction deleted; `Unattested ∧ survives` must re-emit it
        /// so the reinserted row keeps the pin a cold materialize's `WHERE
        /// EXISTS(memo)` carry lands on the new projection. The sentinel
        /// `pinned_at_ms` proves the re-emitted fact is the committed row's bytes.
        #[test]
        fn a_revoked_head_over_a_purged_reinserted_row_carries_the_committed_pin_verbatim() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "pinned victim")],
            );
            let ctx_a = open_session(scratch.path());
            ctx_a.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx_a, "pinned victim");
            pin_active(&ctx_a, &victim, "pin-victim");
            commit_dual_lane(&ctx_a, &victim);
            stamp_committed_pin(&ctx_a.cache_dir, &victim, SENTINEL_PIN_MS);

            // Same committed cache for the materialize arm — sentinel included.
            let cache_b = tempdir().expect("cache b");
            snapshot_cache(&ctx_a.cache_dir, &cache_b);

            // Incremental arm: purge tombstone lands (purge_reads) and the head is
            // revoked (state_head_removed) in one classifiable pass. The tombstone
            // deletes the trashed row mid-apply; the document lane re-emits it.
            write_purge_tombstone(&ctx_a.workspace, &victim, "r7-purge");
            fs::remove_file(state_head_file(&ctx_a.workspace, &victim)).expect("revoke head");
            ctx_a
                .session
                .rebuild_projection()
                .expect("the tombstone + revocation reconcile incrementally");

            // Materialize arm over the cloned cache: same durable facts plus an
            // unclassifiable path — the cold scan carries the same sentinel row.
            force_materialize_path(&ctx_a.workspace);
            let ctx_b = open_session_with_cache(&ctx_a.workspace, cache_b).expect("session b");
            ctx_b
                .session
                .rebuild_projection()
                .expect("the same workspace materializes");

            // The tombstone suppresses the trash lane only: the document still
            // emits the identity, so both arms must land it ACTIVE and pinned with
            // the committed sentinel — the cache row verbatim, not a re-derivation.
            let summary = summaries(&ctx_a.session, false)
                .into_iter()
                .find(|summary| summary.memo_id == victim)
                .expect("the document lane survives the tombstone");
            assert!(
                summary.is_pinned && !summary.is_trashed,
                "the purged+revoked identity must come back active and pinned"
            );
            assert!(
                pin_rows(&ctx_a.cache_dir).contains(&(victim.clone(), SENTINEL_PIN_MS)),
                "the reinserted row must carry the committed pin verbatim"
            );
            assert_carry_arms_equal(&ctx_a, &ctx_b, &victim);
        }

        // ---------- carry through a mid-apply delete+reinsert (retire arm) ----------

        /// `retire_memo` on the removed claim record pushes the owner into
        /// `memo_removes`; `delete_memo_row` cascades `memo_pin` away before the
        /// document re-emit lands. `Unattested ∧ survives` must re-emit the
        /// committed pin so the row the reinsert created is pinned identically to
        /// a cold scan's carried row. Same sentinel discipline as the purge arm.
        #[test]
        fn a_revoked_head_over_a_retired_reemitted_row_restores_the_cache_pin() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "pinned victim")],
            );
            let ctx_a = open_session(scratch.path());
            ctx_a.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx_a, "pinned victim");
            pin_active(&ctx_a, &victim, "pin-victim");
            commit_dual_lane(&ctx_a, &victim);
            stamp_committed_pin(&ctx_a.cache_dir, &victim, SENTINEL_PIN_MS);

            let cache_b = tempdir().expect("cache b");
            snapshot_cache(&ctx_a.cache_dir, &cache_b);

            // Incremental arm: the claim record leaves (trash_removed →
            // retire_memo) and the head is revoked (state_head_removed); the
            // document lane re-emits the identity inside the same apply.
            remove_trash_record(&ctx_a.workspace, &victim);
            fs::remove_file(state_head_file(&ctx_a.workspace, &victim)).expect("revoke head");
            ctx_a
                .session
                .rebuild_projection()
                .expect("the record removal + revocation reconcile incrementally");

            force_materialize_path(&ctx_a.workspace);
            let ctx_b = open_session_with_cache(&ctx_a.workspace, cache_b).expect("session b");
            ctx_b
                .session
                .rebuild_projection()
                .expect("the same workspace materializes");

            let summary = summaries(&ctx_a.session, false)
                .into_iter()
                .find(|summary| summary.memo_id == victim)
                .expect("the document lane is restored");
            assert!(
                summary.is_pinned && !summary.is_trashed,
                "the retired+reemitted identity must come back active and pinned"
            );
            assert!(
                pin_rows(&ctx_a.cache_dir).contains(&(victim.clone(), SENTINEL_PIN_MS)),
                "the reinserted row must carry the committed pin verbatim"
            );
            assert_carry_arms_equal(&ctx_a, &ctx_b, &victim);
        }

        // ---------- Unattested ∧ dead: no fact, no orphan, no carry ----------

        /// `Unattested ∧ dead` owes no fact: the row leaves on `memo_removes` and
        /// the committed pin row follows it through the FK cascade — nothing is
        /// re-emitted, exactly what the cold `WHERE EXISTS(memo)` gate refuses to
        /// copy. A sentinel-stamped committed pin makes "not carried" observable:
        /// both arms must end with no row and no pin for the identity.
        #[test]
        fn a_revoked_head_over_a_dead_identity_carries_nothing_on_either_path() {
            let scratch = tempdir().expect("workspace");
            write_doc(scratch.path(), "2026_09_10.md", &[("10:00:00", "stayer")]);
            write_doc(
                scratch.path(),
                "2026_09_11.md",
                &[("10:01:00", "pinned victim")],
            );
            let ctx_a = open_session(scratch.path());
            ctx_a.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx_a, "pinned victim");
            pin_active(&ctx_a, &victim, "pin-victim");
            stamp_committed_pin(&ctx_a.cache_dir, &victim, SENTINEL_PIN_MS);

            let cache_b = tempdir().expect("cache b");
            snapshot_cache(&ctx_a.cache_dir, &cache_b);

            // Incremental arm: document removal retires the row (docs_removed →
            // retire_memo) and the head is revoked in the same pass.
            fs::remove_file(ctx_a.workspace.join("2026_09_11.md")).expect("remove doc");
            fs::remove_file(state_head_file(&ctx_a.workspace, &victim)).expect("revoke head");
            ctx_a
                .session
                .rebuild_projection()
                .expect("the doc removal + revocation reconcile incrementally");

            force_materialize_path(&ctx_a.workspace);
            let ctx_b = open_session_with_cache(&ctx_a.workspace, cache_b).expect("session b");
            ctx_b
                .session
                .rebuild_projection()
                .expect("the same workspace materializes");

            for ctx in [&ctx_a, &ctx_b] {
                assert!(
                    !projected_ids(ctx, false).contains(&victim)
                        && !projected_ids(ctx, true).contains(&victim),
                    "the dead identity stays out of both lanes"
                );
                assert!(
                    pin_rows(&ctx.cache_dir).iter().all(|(id, _)| id != &victim),
                    "a dead unattested identity owes no pin fact — the sentinel \
                     must leave with the row, never land orphaned"
                );
            }
            assert_carry_arms_equal(&ctx_a, &ctx_b, &victim);
        }

        // ---------- idempotent re-emit: the projection digest must not move ----------

        /// A pure revocation whose entire delta is `listing_removes(head)` plus
        /// `pin_upserts(committed)` rewrites the pin row content-identically.
        /// `projection_state_digest` reads `(memo_id, pinned_at_ms)` — not rowid —
        /// so `INSERT OR REPLACE` of the same value must leave the digest still,
        /// `rewritten == false`, no phantom publication bump. The sentinel proves
        /// the landed value is the committed row, not the durable tip's timestamp.
        #[test]
        fn an_untouched_survivor_carries_its_committed_pin_verbatim_without_a_rewrite() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "pinned victim")],
            );
            let ctx_a = open_session(scratch.path());
            ctx_a.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx_a, "pinned victim");
            pin_active(&ctx_a, &victim, "pin-victim");
            stamp_committed_pin(&ctx_a.cache_dir, &victim, SENTINEL_PIN_MS);

            let cache_b = tempdir().expect("cache b");
            snapshot_cache(&ctx_a.cache_dir, &cache_b);

            // Incremental arm: only the head leaves — `state_head_removed` is a
            // first-class scope, so this stays on the scoped path.
            fs::remove_file(state_head_file(&ctx_a.workspace, &victim)).expect("revoke head");
            let result = ctx_a
                .session
                .rebuild_projection()
                .expect("the revocation reconciles incrementally");

            assert!(
                is_pinned(&ctx_a, &victim),
                "the untouched survivor keeps the committed pin verdict"
            );
            assert!(
                pin_rows(&ctx_a.cache_dir).contains(&(victim.clone(), SENTINEL_PIN_MS)),
                "the carried row must be the committed cache row verbatim"
            );
            assert!(
                !result.rewritten,
                "a content-identical carry must not bump the projection clock — \
                 the re-emit is `INSERT OR REPLACE` of the same (memo_id, \
                 pinned_at_ms) and the digest must not drift"
            );

            force_materialize_path(&ctx_a.workspace);
            let ctx_b = open_session_with_cache(&ctx_a.workspace, cache_b).expect("session b");
            ctx_b
                .session
                .rebuild_projection()
                .expect("the same workspace materializes");
            assert!(
                pin_rows(&ctx_b.cache_dir).contains(&(victim.clone(), SENTINEL_PIN_MS)),
                "the materialize arm ships the same sentinel row"
            );
            assert_carry_arms_equal(&ctx_a, &ctx_b, &victim);
        }

        // ---------- error parity: a stray disagreeing head is corruption evidence ----------

        /// Cold `pins()` decodes and tip-checks EVERY head file: a stray head whose
        /// body names a live memo but points at a superseded tip is
        /// `state_head_changed` corruption — the durable state directory certifies
        /// two disagreeing authorities. The incremental `state()` arm decodes the
        /// stray only for its `memo_id` and resolves the verdict from the canonical
        /// path alone, so the same durable evidence produces a committed answer on
        /// one path and a corruption error on the other. Both must fail the same
        /// way — a scoped pass may not silently absorb evidence a cold scan treats
        /// as fatal.
        #[test]
        fn a_stray_disagreeing_state_head_is_corruption_evidence_on_both_paths() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "pinned victim")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "pinned victim");
            pin_active(&ctx, &victim, "pin-victim");

            // Keep the pinned-era head bytes, then move the canonical tip forward:
            // the saved copy is a stale authority asserting `pinned=true` at a
            // superseded revision.
            let stale_head =
                fs::read(state_head_file(&ctx.workspace, &victim)).expect("head bytes");
            ctx.session
                .pin_memo(
                    PinMemoRequest::new(
                        OperationId::parse("unpin-victim").expect("operation id"),
                        MemoId::parse(&victim).expect("memo id"),
                        PinPolicy::Unpinned,
                    )
                    .expect("unpin request"),
                )
                .expect("unpin");
            ctx.session.rebuild_projection().expect("unpin reconciles");
            assert!(!is_pinned(&ctx, &victim), "fixture sanity: unpinned");

            // The stray lands under a non-canonical stem — a valid envelope with a
            // valid `memo_id` body, but disagreeing with the canonical tip. The
            // listing diff classifies it `state_heads`, decodable, so the scoped
            // pass claims it proven.
            let stray = ctx
                .workspace
                .join(".lomo/state/v2/heads/stale-duplicate.rec");
            fs::write(&stray, &stale_head).expect("write stray head");

            let incremental = ctx.session.rebuild_projection();
            let cold_error = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("a stray disagreeing head must fail cold materialize"),
                Err(error) => error,
            };
            assert_eq!(
                cold_error.code(),
                "state_head_changed",
                "cold fails on the disagreeing duplicate head"
            );
            assert!(
                incremental.is_err(),
                "the same durable corruption must fail the scoped reconcile too — \
                 it committed a verdict ({incremental:?}) where cold returned \
                 {cold_error:?}"
            );
        }

        // ---------- attested-unpinned identities: the committed cache is never authority ----------

        /// Cold gate 1 (`pin_attested_ids`) treats a committed `memo_pin` row over
        /// an attested-unpinned identity as stale evidence it never copies — the
        /// carried row is dropped, not merely guarded at insert. The incremental
        /// side derives pin facts only for memos inside the changed scope, so a
        /// stale committed row over an identity whose head did not diff is never
        /// re-examined: the same durable facts over the same committed cache must
        /// not let the stale pin survive on one path only.
        #[test]
        fn a_stale_cache_pin_over_an_attested_unpinned_identity_must_not_survive_incremental() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "victim")],
            );
            let ctx_a = open_session(scratch.path());
            ctx_a.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx_a, "victim");
            pin_active(&ctx_a, &victim, "pin-victim");
            ctx_a
                .session
                .pin_memo(
                    PinMemoRequest::new(
                        OperationId::parse("unpin-victim").expect("operation id"),
                        MemoId::parse(&victim).expect("memo id"),
                        PinPolicy::Unpinned,
                    )
                    .expect("unpin request"),
                )
                .expect("unpin");
            ctx_a
                .session
                .rebuild_projection()
                .expect("unpin reconciles");
            assert!(!is_pinned(&ctx_a, &victim), "fixture sanity: unpinned");

            // A stale committed pin row durable already answered for — the input
            // class cold gate 1 exists to drop. Stamped into both caches.
            stamp_committed_pin(&ctx_a.cache_dir, &victim, SENTINEL_PIN_MS);
            let cache_b = tempdir().expect("cache b");
            snapshot_cache(&ctx_a.cache_dir, &cache_b);

            // Incremental arm: an unrelated document change — the victim's head
            // never diffs, so no pin scope covers it.
            write_doc(
                &ctx_a.workspace,
                "2026_09_12.md",
                &[("10:02:00", "unrelated edit")],
            );
            ctx_a
                .session
                .rebuild_projection()
                .expect("the unrelated change reconciles incrementally");

            // Materialize arm over the same stale cache: gate 1 drops the row.
            force_materialize_path(&ctx_a.workspace);
            let ctx_b = open_session_with_cache(&ctx_a.workspace, cache_b).expect("session b");
            ctx_b
                .session
                .rebuild_projection()
                .expect("the same workspace materializes");

            assert_eq!(
                is_pinned(&ctx_a, &victim),
                is_pinned(&ctx_b, &victim),
                "the same committed stale pin over the same attested-unpinned \
                 durable answer must meet the same verdict on both paths — \
                 incremental kept it ({}), materialize dropped it ({})",
                is_pinned(&ctx_a, &victim),
                is_pinned(&ctx_b, &victim),
            );
            assert!(
                pin_rows(&ctx_b.cache_dir)
                    .iter()
                    .all(|(id, _)| id != &victim),
                "cold gate 1 never copies an attested identity's stale cache pin"
            );
        }
    }

    // adversarial-reaudit round 8: the P8-F1 batch
    // (`audit/22-修复-head校验与pin复检.md`) claims the head-revocation seam is
    // closed: `state()` stem-checks every diffed head (`head.memo_id != stem` →
    // unclassifiable → cold `pins()` decides), and `rewalk` merges every committed
    // `memo_pin` key into `state_memos` so each cache row is re-verdicted against
    // durable attestation every pass. §6 of the same record waives the
    // *missing-row* mirror direction — an attested-pinned identity whose committed
    // pin row is absent — as reachable only through out-of-band cache mutation and
    // too expensive to close (`O(#heads)` reads per pass).
    //
    // The probes below attack both halves of that waiver plus the fix's own
    // unchecked claims:
    //
    // - Missing-row reachability: the `memo_pin → memo` FK cascade deletes a
    //   committed pin row *in band* whenever the memo row dies — no cache mutation
    //   needed. A row recreated on a later pass whose head never diffed sits
    //   outside every `state_memos` supply set, so the durable-pinned verdict is
    //   never re-derived. `rewalk`'s gather-time `committed_pins` snapshot only
    //   bridges delete+reinsert inside ONE apply; across two reconcile passes the
    //   pin row is gone from the snapshot and the identity is gone from scope.
    // - Committed stray heads: `pins()` tip-checks rather than stem-checks, so a
    //   tip-matching duplicate head is *absorbed* and committed into
    //   `file_listing` by the materialize/reconcile path itself — no pre-fix
    //   window needed. Once committed it never re-diffs, the stem check never
    //   sees it, and any later canonical-head advance or removal splits the two
    //   paths: incremental answers Ok where cold returns
    //   `state_head_changed`/`state_head_missing`.
    // - The rewalk's own claims, end to end in one pass: stale committed rows over
    //   attested-pinned identities are corrected to the durable timestamp, rows
    //   over attested-unpinned identities die, unattested rows carry verbatim,
    //   unparseable and dead ids leave, and a non-positive committed timestamp
    //   stays untouched — each shape asserted against the materialize arm.
    // - The benign direction of the stem check: a tip-matching duplicate must be
    //   *absorbed*, never a hard failure — the unclassifiable kick may only cost a
    //   full scan, not change the outcome.

    #[cfg(test)]
    #[expect(
        clippy::expect_used,
        reason = "adversarial audit tests fail closed with panics on missing facts"
    )]
    mod head_stem_checks {
        use std::{
            collections::BTreeSet,
            fmt::Write as _,
            fs,
            path::{Path, PathBuf},
            sync::Arc,
        };

        use lomo_application::{
            MemoFilters, MemoQuery, MemoSort, PinMemoRequest, PinPolicy, WorkspaceSession,
            WorkspaceSessionConfig,
        };
        use lomo_core::{
            CapabilityToken, LomoError, OperationId, PageSize, PlatformActionExecutor,
        };
        use lomo_platform_fs::FsPlatformActionExecutor;
        use lomo_workspace::{MemoId, WorkspaceGenerationId, WorkspaceRootId};
        use tempfile::tempdir;

        /// A `pinned_at_ms` no durable tip or mutation ever produced — landing it
        /// proves the carried/corrected value is (or is not) the committed row.
        const SENTINEL_PIN_MS: i64 = 42_424_242;

        struct Ctx {
            session: WorkspaceSession,
            workspace: PathBuf,
            cache_dir: PathBuf,
            _dirs: Vec<tempfile::TempDir>,
        }

        /// Private fixture directories are positional; the index is a fixture slot, not data.
        fn fixture_dir(dirs: &[tempfile::TempDir], index: usize) -> &Path {
            dirs.get(index).expect("fixture dir slot").path()
        }

        /// Opens a session on `workspace` against an existing `cache_dir` — the
        /// caller owns the cache tempdir, which is adopted into the fixture so the
        /// store file outlives the session.
        fn open_session_with_cache(
            workspace: &Path,
            cache_dir: tempfile::TempDir,
        ) -> Result<Ctx, LomoError> {
            let mut dirs = (0..6)
                .map(|_| tempdir().expect("fixture dir"))
                .collect::<Vec<_>>();
            let workspace_path = workspace.to_path_buf();
            let real = Arc::new(FsPlatformActionExecutor::new(fixture_dir(&dirs, 4))?);
            let capability = CapabilityToken::parse("notes")?;
            real.bind_root(capability.clone(), &workspace_path)?;
            let executor: Arc<dyn PlatformActionExecutor> = real;
            let session = WorkspaceSession::open(
                WorkspaceSessionConfig {
                    capability,
                    root_id: WorkspaceRootId::Notes,
                    workspace_generation: WorkspaceGenerationId::mint()?,
                    time_zone: "UTC".to_owned(),
                    date_format: lomo_application::calendar::DateFormat::default(),
                    state_dir: fixture_dir(&dirs, 1).to_path_buf(),
                    cache_dir: cache_dir.path().to_path_buf(),
                    runtime_dir: fixture_dir(&dirs, 3).to_path_buf(),
                    exchange_dir: fixture_dir(&dirs, 4).to_path_buf(),
                    media_stage_root: fixture_dir(&dirs, 5).to_path_buf(),
                },
                executor,
            )?;
            let cache_dir_path = cache_dir.path().to_path_buf();
            dirs.push(cache_dir);
            Ok(Ctx {
                session,
                workspace: workspace_path,
                cache_dir: cache_dir_path,
                _dirs: dirs,
            })
        }

        fn open_session(workspace: &Path) -> Ctx {
            open_session_with_cache(workspace, tempdir().expect("cache dir")).expect("session")
        }

        /// A second session on the same workspace with fresh private directories: its
        /// open can only materialize from durable facts — the cold-scan oracle.
        fn fresh_oracle(workspace: &Path) -> Result<Ctx, LomoError> {
            open_session_with_cache(workspace, tempdir().expect("oracle cache"))
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

        /// The public observable projection: memo rows, bodies, lifecycle bits, pins,
        /// history windows, attachment observations, tag counts and tasks.
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

        /// Every row of one table, ordered, rendered textually.
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

        /// The projection rows both reconcile arms must commit identically.
        ///
        /// `file_listing` and the listing digest meta are deliberately excluded:
        /// the materialize arm is forced by an extra unclassifiable path the
        /// incremental arm's listing never contained, so the committed listing
        /// legitimately differs while the projection content must not.
        fn projection_dump(cache_dir: &Path) -> String {
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
                    "SELECT memo_id, trashed_at_ms, record_digest FROM memo_trash ORDER BY memo_id",
                ),
                (
                    "purged_memo",
                    "SELECT memo_id FROM purged_memo ORDER BY memo_id",
                ),
                (
                    "revision_index",
                    "SELECT memo_id, history_record_id, revision, created_at_ms, file_fingerprint \
                     FROM revision_index ORDER BY memo_id, history_record_id",
                ),
                ("stats", "SELECT key, value_i64 FROM stats ORDER BY key"),
            ] {
                table_dump(&conn, tag, sql, &mut out);
            }
            out
        }

        /// Every `memo_pin` row committed in a cache, `(memo_id, pinned_at_ms)` pairs.
        fn pin_rows(cache_dir: &Path) -> Vec<(String, i64)> {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            let mut stmt = conn
                .prepare("SELECT memo_id, pinned_at_ms FROM memo_pin ORDER BY memo_id")
                .expect("pin query");
            stmt.query_map([], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?))
            })
            .expect("pin rows")
            .map(|row| row.expect("row"))
            .collect()
        }

        /// Writes `pinned_at_ms` into the committed `memo_pin` row out of band —
        /// the cache-state a stale device snapshot or restored store can carry.
        /// Foreign keys are switched off on the stamping connection so rows
        /// naming identities with no `memo` row (dead, or unparseable) are
        /// stampable too — exactly the shapes a cache restore could deliver.
        fn stamp_committed_pin(cache_dir: &Path, memo_id: &str, pinned_at_ms: i64) {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            conn.pragma_update(None, "foreign_keys", "OFF")
                .expect("foreign keys off for out-of-band stamping");
            conn.execute(
                "INSERT OR REPLACE INTO memo_pin(memo_id, pinned_at_ms) VALUES(?1, ?2)",
                rusqlite::params![memo_id, pinned_at_ms],
            )
            .expect("stamp committed pin");
        }

        /// Snapshots a committed projection cache into `dst`: checkpoint the WAL so
        /// the standalone file image carries every committed row, then copy it.
        fn snapshot_cache(src_cache_dir: &Path, dst_cache_dir: &tempfile::TempDir) {
            let src_db = src_cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&src_db).expect("source store db");
            conn.execute_batch("PRAGMA wal_checkpoint(TRUNCATE)")
                .expect("checkpoint source wal");
            drop(conn);
            let dst_db = dst_cache_dir.path().join(".lomo-sqlite").join("store.db");
            fs::create_dir_all(dst_db.parent().expect("dst parent")).expect("dst dir");
            fs::copy(&src_db, &dst_db).expect("cache snapshot");
        }

        /// Whether `memo_id` is pinned in this session's live projection.
        fn is_pinned(ctx: &Ctx, memo_id: &str) -> bool {
            summaries(&ctx.session, false)
                .iter()
                .any(|summary| summary.memo_id == memo_id && summary.is_pinned)
        }

        fn projected_ids(ctx: &Ctx, trash_only: bool) -> BTreeSet<String> {
            summaries(&ctx.session, trash_only)
                .iter()
                .map(|summary| summary.memo_id.clone())
                .collect()
        }

        /// Finds the memo id of the block carrying `needle` in the live projection.
        fn memo_by_body(ctx: &Ctx, needle: &str) -> String {
            summaries(&ctx.session, false)
                .into_iter()
                .find(|summary| {
                    ctx.session
                        .projected_memo(&summary.memo_id)
                        .expect("snapshot")
                        .is_some_and(|snapshot| snapshot.body.contains(needle))
                })
                .expect("a memo carrying the needle body")
                .memo_id
        }

        /// Pins a memo through the app path so the durable state tip keeps
        /// `pinned=true` — the fact every pin probe below depends on. The rebuild
        /// realigns the committed `memo_pin` row to the durable tip's timestamp.
        fn pin_active(ctx: &Ctx, memo_id: &str, operation_id: &str) {
            ctx.session
                .pin_memo(
                    PinMemoRequest::new(
                        OperationId::parse(operation_id).expect("operation id"),
                        MemoId::parse(memo_id).expect("memo id"),
                        PinPolicy::Pinned { at_ms: None },
                    )
                    .expect("pin request"),
                )
                .expect("pin");
            ctx.session
                .rebuild_projection()
                .expect("the pin's durable state records reconcile");
            assert!(
                is_pinned(ctx, memo_id),
                "fixture sanity: the memo is pinned"
            );
        }

        /// Unpins a memo through the app path so the durable state tip moves to a
        /// `pinned=false` head revision.
        fn unpin_active(ctx: &Ctx, memo_id: &str, operation_id: &str) {
            ctx.session
                .pin_memo(
                    PinMemoRequest::new(
                        OperationId::parse(operation_id).expect("operation id"),
                        MemoId::parse(memo_id).expect("memo id"),
                        PinPolicy::Unpinned,
                    )
                    .expect("unpin request"),
                )
                .expect("unpin");
            ctx.session
                .rebuild_projection()
                .expect("the unpin's durable state records reconcile");
            assert!(
                !is_pinned(ctx, memo_id),
                "fixture sanity: the memo is unpinned"
            );
        }

        /// Absolute path of a memo's canonical v2 state head inside `workspace`.
        fn state_head_file(workspace: &Path, memo_id: &str) -> PathBuf {
            let paths = lomo_workspace::LomoPaths::for_workspace_with_layout(
                Path::new(""),
                lomo_workspace::LomoLayoutVersion::V2,
            );
            workspace.join(lomo_workspace::state_head_path(&paths, memo_id))
        }

        /// Drops the path that defeats scope proving so the same workspace
        /// materializes on the materialize arm.
        fn force_materialize_path(workspace: &Path) {
            fs::write(
                workspace.join(".lomo/unscoped.evidence"),
                b"not a durable record".as_slice(),
            )
            .expect("unclassifiable path");
        }

        /// Both reconcile arms over the same committed cache must commit the same
        /// projection rows and answer the same pin verdict.
        fn assert_carry_arms_equal(ctx_a: &Ctx, ctx_b: &Ctx, memo_id: &str) {
            assert_eq!(
                public_dump(&ctx_a.session),
                public_dump(&ctx_b.session),
                "the incremental and materialize arms diverged on the public surface"
            );
            assert_eq!(
                projection_dump(&ctx_a.cache_dir),
                projection_dump(&ctx_b.cache_dir),
                "the incremental and materialize arms committed different projection rows"
            );
            assert_eq!(
                pin_rows(&ctx_a.cache_dir),
                pin_rows(&ctx_b.cache_dir),
                "the arms disagree on the committed memo_pin row set"
            );
            assert_eq!(
                is_pinned(ctx_a, memo_id),
                is_pinned(ctx_b, memo_id),
                "the same durable facts plus the same committed cache flipped the pin verdict"
            );
        }

        // ---------- missing-row direction, in-band trigger ----------
        //
        // §6 of the fix record waives the missing-row direction as reachable only
        // through out-of-band cache mutation: an attested-pinned identity whose
        // committed `memo_pin` row is absent can never be re-derived by `rewalk`
        // because `state_memos` is fed from the diff and from `committed_pins`
        // keys — the absent row is in neither. But the row's *absence* needs no
        // out-of-band writer: `memo_pin` cascades with `memo`, so any pass that
        // deletes the row (doc removal → `retire_memo` → `delete_memo_row`) takes
        // the pin with it — in band, durably provoked. A later pass that re-emits
        // the identity recreates the `memo` row and re-derives nothing: the head
        // never diffed, the identity is retired by nothing, and `committed_pins`
        // no longer names it. The cold side re-derives the pin from the head on
        // every materialize. Same durable facts, same committed cache → the arms
        // must answer the same verdict.
        #[test]
        fn a_deleted_and_restored_document_loses_the_durable_pin_verdict_incrementally() {
            let scratch = tempdir().expect("workspace");
            write_doc(scratch.path(), "2026_09_10.md", &[("10:00:00", "stayer")]);
            write_doc(
                scratch.path(),
                "2026_09_11.md",
                &[("10:01:00", "pinned victim")],
            );
            let ctx_a = open_session(scratch.path());
            ctx_a.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx_a, "pinned victim");
            pin_active(&ctx_a, &victim, "pin-victim");

            // Pass 1: the document leaves — `docs_removed` retires the row and the
            // FK cascade takes `memo_pin` with it. The pinned head stays durable.
            fs::remove_file(ctx_a.workspace.join("2026_09_11.md")).expect("remove doc");
            ctx_a
                .session
                .rebuild_projection()
                .expect("the doc removal reconciles incrementally");
            assert!(
                !projected_ids(&ctx_a, false).contains(&victim)
                    && !projected_ids(&ctx_a, true).contains(&victim),
                "fixture sanity: the retired identity left both lanes"
            );

            // Pass 2: the document returns — the row is re-emitted while the
            // pinned head sits untouched outside every pin-fact scope.
            write_doc(
                &ctx_a.workspace,
                "2026_09_11.md",
                &[("10:01:00", "pinned victim")],
            );
            ctx_a
                .session
                .rebuild_projection()
                .expect("the doc restore reconciles incrementally");

            // The materialize arm over the same committed cache re-derives the pin
            // from the durable head — the cold path needs no committed row.
            let cache_b = tempdir().expect("cache b");
            snapshot_cache(&ctx_a.cache_dir, &cache_b);
            force_materialize_path(&ctx_a.workspace);
            let ctx_b = open_session_with_cache(&ctx_a.workspace, cache_b).expect("session b");
            ctx_b
                .session
                .rebuild_projection()
                .expect("the same workspace materializes");

            assert_carry_arms_equal(&ctx_a, &ctx_b, &victim);
        }

        // ---------- committed stray heads: the absorbed-duplicate lifecycle ----------
        //
        // The stem check only judges heads that diff THIS pass. `pins()` never
        // stem-checks — it tip-checks — so a duplicate head file whose claimed
        // revision equals the canonical tip is absorbed as redundant evidence and
        // committed into `file_listing` by the reconcile/materialize path itself.
        // From then on the stray never re-diffs and is invisible to the scoped
        // pass, while the cold side keeps tip-checking it forever. The moment the
        // canonical tip advances, the committed stray becomes *disagreeing*
        // evidence the cold scan rejects — and the scoped pass cannot see it.
        #[test]
        fn a_tip_matching_stray_turns_head_advance_into_an_ok_err_split() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "pinned victim")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "pinned victim");
            pin_active(&ctx, &victim, "pin-victim");

            // A byte-copy of the current head under a foreign stem: `pins()`
            // absorbs it (claim == canonical tip) and the reconcile commits it
            // into `file_listing` — production-reachable, no pre-fix window.
            let head = fs::read(state_head_file(&ctx.workspace, &victim)).expect("head bytes");
            fs::write(
                ctx.workspace.join(".lomo/state/v2/heads/zzz-dup.rec"),
                &head,
            )
            .expect("write stray head");
            ctx.session
                .rebuild_projection()
                .expect("the tip-matching duplicate is absorbed and committed");

            // The canonical tip advances through the normal app path: the scoped
            // pass re-verdicts the memo from its canonical head and never reads
            // the now-divergent committed stray.
            unpin_active(&ctx, &victim, "unpin-victim");
            let incremental = ctx.session.rebuild_projection();

            let cold_error = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("a divergent committed stray must fail cold materialize"),
                Err(error) => error,
            };
            assert_eq!(
                cold_error.code(),
                "state_head_changed",
                "cold fails on the stale committed duplicate"
            );
            let incremental_error = match incremental {
                Ok(result) => panic!(
                    "the same durable corruption must fail the scoped reconcile too — \
                     it committed a verdict ({result:?}) where cold returned {cold_error:?}"
                ),
                Err(error) => error,
            };
            assert_eq!(incremental_error.code(), cold_error.code());
        }

        /// Same committed stray, canonical head *removed* instead of advanced:
        /// the scoped pass reads `Unattested` for the identity and carries the
        /// committed pin, while cold tip-checks the stray against a canonical
        /// head that no longer exists — `state_head_missing`.
        #[test]
        fn a_tip_matching_stray_turns_head_removal_into_an_ok_err_split() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "pinned victim")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "pinned victim");
            pin_active(&ctx, &victim, "pin-victim");

            let head = fs::read(state_head_file(&ctx.workspace, &victim)).expect("head bytes");
            fs::write(
                ctx.workspace.join(".lomo/state/v2/heads/zzz-dup.rec"),
                &head,
            )
            .expect("write stray head");
            ctx.session
                .rebuild_projection()
                .expect("the tip-matching duplicate is absorbed and committed");

            fs::remove_file(state_head_file(&ctx.workspace, &victim)).expect("revoke head");
            let incremental = ctx.session.rebuild_projection();

            let cold_error = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("a stray over a missing canonical head must fail cold"),
                Err(error) => error,
            };
            assert_eq!(
                cold_error.code(),
                "state_head_missing",
                "cold fails tip-checking the stray against the absent canonical head"
            );
            let incremental_error = match incremental {
                Ok(result) => panic!(
                    "the same durable corruption must fail the scoped reconcile too — \
                     it committed a verdict ({result:?}) where cold returned {cold_error:?}"
                ),
                Err(error) => error,
            };
            assert_eq!(incremental_error.code(), cold_error.code());
        }

        // ---------- the rewalk's full-row claims, all shapes in one pass ----------
        //
        // Every committed `memo_pin` row owes `state_scoped` a verdict each pass.
        // Five committed shapes over one unrelated diff: an attested-pinned row
        // carrying a stale value is corrected to the durable timestamp (the
        // same-value sibling of F7-2 the fix table claims but never probed), an
        // attested-unpinned row dies, an unattested row carries verbatim, an
        // unparseable id leaves, and a dead id leaves. The materialize arm must
        // agree row-for-row.
        #[test]
        fn every_committed_pin_row_is_rewalked_against_durable_each_pass() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[
                    ("10:00:00", "stayer"),
                    ("10:01:00", "alpha pin target"),
                    ("10:02:00", "beta drop target"),
                    ("10:03:00", "gamma carry target"),
                ],
            );
            let ctx_a = open_session(scratch.path());
            ctx_a.session.rebuild_projection().expect("seed");
            let victim_p = memo_by_body(&ctx_a, "alpha pin target");
            let victim_u = memo_by_body(&ctx_a, "beta drop target");
            let victim_x = memo_by_body(&ctx_a, "gamma carry target");
            pin_active(&ctx_a, &victim_p, "pin-p");
            pin_active(&ctx_a, &victim_u, "pin-u");
            unpin_active(&ctx_a, &victim_u, "unpin-u");
            pin_active(&ctx_a, &victim_x, "pin-x");
            // The durable timestamp the attested-pinned correction must land.
            let durable_ts = pin_rows(&ctx_a.cache_dir)
                .iter()
                .find(|(id, _)| id == &victim_p)
                .map(|(_, ts)| *ts)
                .expect("durable pin timestamp committed");
            // Revoke the third head: the identity turns Unattested with a carried
            // committed row the next stamp overwrites with the sentinel.
            fs::remove_file(state_head_file(&ctx_a.workspace, &victim_x)).expect("revoke head");
            ctx_a
                .session
                .rebuild_projection()
                .expect("the revocation reconciles incrementally");

            stamp_committed_pin(&ctx_a.cache_dir, &victim_p, SENTINEL_PIN_MS);
            stamp_committed_pin(&ctx_a.cache_dir, &victim_u, SENTINEL_PIN_MS);
            stamp_committed_pin(&ctx_a.cache_dir, &victim_x, SENTINEL_PIN_MS);
            stamp_committed_pin(&ctx_a.cache_dir, "bad/id", 7);
            stamp_committed_pin(&ctx_a.cache_dir, "ghost-memo", 8);

            let cache_b = tempdir().expect("cache b");
            snapshot_cache(&ctx_a.cache_dir, &cache_b);

            // One unrelated document change: no victim head diffs, so only the
            // committed-row rewalk can touch these identities.
            write_doc(
                &ctx_a.workspace,
                "2026_09_12.md",
                &[("10:04:00", "unrelated edit")],
            );
            ctx_a
                .session
                .rebuild_projection()
                .expect("the unrelated change reconciles incrementally");

            force_materialize_path(&ctx_a.workspace);
            let ctx_b = open_session_with_cache(&ctx_a.workspace, cache_b).expect("session b");
            ctx_b
                .session
                .rebuild_projection()
                .expect("the same workspace materializes");

            let expected: Vec<(String, i64)> = {
                let mut rows = vec![(victim_p.clone(), durable_ts), (victim_x, SENTINEL_PIN_MS)];
                rows.sort();
                rows
            };
            assert_eq!(
                pin_rows(&ctx_a.cache_dir),
                expected,
                "the rewalk must correct the stale attested-pinned value to durable, \
                 drop the attested-unpinned row, carry the unattested sentinel verbatim, \
                 and evict the unparseable and dead ids — all in one pass"
            );
            assert_carry_arms_equal(&ctx_a, &ctx_b, &victim_p);
        }

        // ---------- non-positive committed timestamp: verbatim carry ----------
        //
        // A committed `memo_pin` row with `pinned_at_ms <= 0` can never be
        // re-emitted — fact validation rejects `pin_upserts` with a non-positive
        // timestamp. Over an Unattested survivor the fix claims the row is left
        // untouched, which is exactly the cold `INSERT OR IGNORE` verbatim carry.
        /// Both arms must keep the same malformed row rather than one dropping it.
        #[test]
        fn a_nonpositive_committed_pin_timestamp_over_an_unattested_identity_carries_verbatim() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "unattested victim")],
            );
            let ctx_a = open_session(scratch.path());
            ctx_a.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx_a, "unattested victim");
            pin_active(&ctx_a, &victim, "pin-victim");
            fs::remove_file(state_head_file(&ctx_a.workspace, &victim)).expect("revoke head");
            ctx_a
                .session
                .rebuild_projection()
                .expect("the revocation reconciles incrementally");

            stamp_committed_pin(&ctx_a.cache_dir, &victim, 0);
            let cache_b = tempdir().expect("cache b");
            snapshot_cache(&ctx_a.cache_dir, &cache_b);

            write_doc(
                &ctx_a.workspace,
                "2026_09_12.md",
                &[("10:02:00", "unrelated edit")],
            );
            ctx_a
                .session
                .rebuild_projection()
                .expect("the unrelated change reconciles incrementally");

            force_materialize_path(&ctx_a.workspace);
            let ctx_b = open_session_with_cache(&ctx_a.workspace, cache_b).expect("session b");
            ctx_b
                .session
                .rebuild_projection()
                .expect("the same workspace materializes");

            assert!(
                pin_rows(&ctx_a.cache_dir).contains(&(victim.clone(), 0)),
                "the non-positive row must survive untouched — the verbatim carry"
            );
            assert!(
                pin_rows(&ctx_b.cache_dir).contains(&(victim.clone(), 0)),
                "the cold EXISTS gate copies the same row verbatim"
            );
            assert_carry_arms_equal(&ctx_a, &ctx_b, &victim);
        }

        // ---------- benign stem mismatch: absorbed, never failed ----------
        //
        // The stem check kicks every `head.memo_id != stem` file back to the full
        // scan. For a tip-matching duplicate — a sync-delivered copy of the live
        // head under a foreign stem — the cold side's answer is *absorption*, not
        // corruption: the kick may cost a full scan but must not change the
        // outcome. This guards the over-correction direction of the fix.
        #[test]
        fn a_tip_matching_duplicate_head_is_absorbed_identically_when_it_diffs() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "pinned victim")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "pinned victim");
            pin_active(&ctx, &victim, "pin-victim");

            let head = fs::read(state_head_file(&ctx.workspace, &victim)).expect("head bytes");
            fs::write(
                ctx.workspace.join(".lomo/state/v2/heads/zzz-dup.rec"),
                &head,
            )
            .expect("write stray head");
            ctx.session
                .rebuild_projection()
                .expect("a tip-agreeing duplicate must reconcile, not fail");
            assert!(
                is_pinned(&ctx, &victim),
                "the absorbed duplicate must not disturb the pin verdict"
            );

            let oracle = fresh_oracle(&ctx.workspace).expect("the same workspace materializes");
            assert_eq!(
                public_dump(&ctx.session),
                public_dump(&oracle.session),
                "absorption must agree with the cold oracle row-for-row"
            );
            assert_eq!(
                pin_rows(&ctx.cache_dir),
                pin_rows(&oracle.cache_dir),
                "both paths keep exactly the durable pin fact"
            );
        }
    }

    // adversarial-reaudit round 9: the P9-F1 batch
    // (`audit/24-修复-pin吸收与stale行.md`) claims the audit/23 REDs are closed:
    // `rewalk` re-attests `emitted ∪ trash-claimed` identities whose gather-time
    // `memo` row is absent (F8-1's FK-cascade hole), and stem-mismatched head
    // files are judged by a cold-equivalent absorb predicate whose absorbed paths
    // never land in `file_listing` (F8-2's committed-stray self-seed).
    //
    // The probes below attack what the absorb judgment did not look at plus the
    // fix's own claims end to end:
    //
    // - One file, two authorities: the absorb judgment classifies a head file by
    //   its *body*, but `state_tip` resolves the canonical slot by *path*. A byte
    //   copy of `heads/<zeta>.rec` written over `heads/<victim>.rec` is tolerated
    //   zeta-duplicate evidence to `pins()` (absorbed; victim unattested; the
    //   committed pin carries) while the same file is victim's head to
    //   `state_tip(victim)` — the re-walk of every committed pin dies
    //   `record_identity_mismatch` on it, every pass, forever.
    // - Condition (a) of `absorb_duplicate_state_head` trusts "the claimed
    //   canonical path was re-committed this pass" as an anchor — but the check
    //   only sees the canonical *path* inside the diff scope, never whether the
    //   file there anchors the same identity. A stray shadowing a hijacked
    //   canonical slot is absorbed forever without a single tip resolution.
    // - The F8-1 supply's second direction: a trash claim recreating an absent
    //   `memo` row must push its claimant into `state_memos` exactly as the
    //   emitted-row direction does — the durable pinned tip re-derives.
    // - Never-commit + eviction: absorbed heads must stay out of `file_listing`
    //   on every commit arm (reconcile apply, `align_listing_snapshot`), and a
    //   row a predecessor wrote for one must be evicted by `listing_removes`.
    // - The v12→v13 migration's LIKE clause must drop only `heads/` rows (shared
    //   prefixes for objects/memos/trash/`headsX` survive), kill the digest meta,
    //   and let the first post-upgrade pass re-judge every head file.

    #[cfg(test)]
    #[expect(
        clippy::expect_used,
        reason = "adversarial audit tests fail closed with panics on missing facts"
    )]
    mod pin_reattestation {
        use std::{
            collections::BTreeSet,
            fmt::Write as _,
            fs,
            path::{Path, PathBuf},
            sync::Arc,
        };

        use lomo_application::{
            DeleteMemoRequest, MemoFilters, MemoQuery, MemoSort, PinMemoRequest, PinPolicy,
            WorkspaceSession, WorkspaceSessionConfig,
        };
        use lomo_core::{
            CapabilityToken, LomoError, OperationId, PageSize, PlatformActionExecutor,
        };
        use lomo_platform_fs::FsPlatformActionExecutor;
        use lomo_workspace::{MemoId, WorkspaceGenerationId, WorkspaceRootId};
        use tempfile::tempdir;

        struct Ctx {
            session: WorkspaceSession,
            workspace: PathBuf,
            cache_dir: PathBuf,
            _dirs: Vec<tempfile::TempDir>,
        }

        /// Private fixture directories are positional; the index is a fixture slot, not data.
        fn fixture_dir(dirs: &[tempfile::TempDir], index: usize) -> &Path {
            dirs.get(index).expect("fixture dir slot").path()
        }

        /// Opens a session on `workspace` against an existing `cache_dir` — the
        /// caller owns the cache tempdir, which is adopted into the fixture so the
        /// store file outlives the session.
        fn open_session_with_cache(
            workspace: &Path,
            cache_dir: tempfile::TempDir,
        ) -> Result<Ctx, LomoError> {
            let mut dirs = (0..6)
                .map(|_| tempdir().expect("fixture dir"))
                .collect::<Vec<_>>();
            let workspace_path = workspace.to_path_buf();
            let real = Arc::new(FsPlatformActionExecutor::new(fixture_dir(&dirs, 4))?);
            let capability = CapabilityToken::parse("notes")?;
            real.bind_root(capability.clone(), &workspace_path)?;
            let executor: Arc<dyn PlatformActionExecutor> = real;
            let session = WorkspaceSession::open(
                WorkspaceSessionConfig {
                    capability,
                    root_id: WorkspaceRootId::Notes,
                    workspace_generation: WorkspaceGenerationId::mint()?,
                    time_zone: "UTC".to_owned(),
                    date_format: lomo_application::calendar::DateFormat::default(),
                    state_dir: fixture_dir(&dirs, 1).to_path_buf(),
                    cache_dir: cache_dir.path().to_path_buf(),
                    runtime_dir: fixture_dir(&dirs, 3).to_path_buf(),
                    exchange_dir: fixture_dir(&dirs, 4).to_path_buf(),
                    media_stage_root: fixture_dir(&dirs, 5).to_path_buf(),
                },
                executor,
            )?;
            let cache_dir_path = cache_dir.path().to_path_buf();
            dirs.push(cache_dir);
            Ok(Ctx {
                session,
                workspace: workspace_path,
                cache_dir: cache_dir_path,
                _dirs: dirs,
            })
        }

        fn open_session(workspace: &Path) -> Ctx {
            open_session_with_cache(workspace, tempdir().expect("cache dir")).expect("session")
        }

        /// A second session on the same workspace with fresh private directories: its
        /// open can only materialize from durable facts — the cold-scan oracle.
        fn fresh_oracle(workspace: &Path) -> Result<Ctx, LomoError> {
            open_session_with_cache(workspace, tempdir().expect("oracle cache"))
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

        /// The public observable projection: memo rows, bodies, lifecycle bits, pins,
        /// history windows, attachment observations, tag counts and tasks.
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

        /// Every row of one table, ordered, rendered textually.
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

        /// The projection rows both reconcile arms must commit identically.
        ///
        /// `file_listing` and the listing digest meta are deliberately excluded:
        /// the materialize arm is forced by an extra unclassifiable path the
        /// incremental arm's listing never contained, so the committed listing
        /// legitimately differs while the projection content must not.
        fn projection_dump(cache_dir: &Path) -> String {
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
                    "SELECT memo_id, trashed_at_ms, record_digest FROM memo_trash ORDER BY memo_id",
                ),
                (
                    "purged_memo",
                    "SELECT memo_id FROM purged_memo ORDER BY memo_id",
                ),
                (
                    "revision_index",
                    "SELECT memo_id, history_record_id, revision, created_at_ms, file_fingerprint \
                     FROM revision_index ORDER BY memo_id, history_record_id",
                ),
                ("stats", "SELECT key, value_i64 FROM stats ORDER BY key"),
            ] {
                table_dump(&conn, tag, sql, &mut out);
            }
            out
        }

        /// Every `memo_pin` row committed in a cache, `(memo_id, pinned_at_ms)` pairs.
        fn pin_rows(cache_dir: &Path) -> Vec<(String, i64)> {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            let mut stmt = conn
                .prepare("SELECT memo_id, pinned_at_ms FROM memo_pin ORDER BY memo_id")
                .expect("pin query");
            stmt.query_map([], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?))
            })
            .expect("pin rows")
            .map(|row| row.expect("row"))
            .collect()
        }

        /// Every committed `file_listing` path — the diff baseline both arms write.
        fn file_listing_paths(cache_dir: &Path) -> BTreeSet<String> {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            conn.prepare("SELECT path FROM file_listing ORDER BY path")
                .expect("listing query")
                .query_map([], |row| row.get::<_, String>(0))
                .expect("listing rows")
                .map(|row| row.expect("row"))
                .collect()
        }

        /// Plants a `file_listing` row out of band — the committed baseline a
        /// predecessor build or a stale snapshot can carry. Rows an old build
        /// committed for absorbed duplicates are exactly this shape.
        fn stamp_listing_row(cache_dir: &Path, path: &str, digest: &str) {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            conn.execute(
                "INSERT OR REPLACE INTO file_listing(path, digest) VALUES(?1, ?2)",
                rusqlite::params![path, digest],
            )
            .expect("stamp listing row");
        }

        /// Rewinds `PRAGMA user_version` so the next store open replays the
        /// migration under test over the live rows.
        fn set_user_version(cache_dir: &Path, version: i64) {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            conn.pragma_update(None, "user_version", version)
                .expect("user_version rewind");
        }

        /// Snapshots a committed projection cache into `dst`: checkpoint the WAL so
        /// the standalone file image carries every committed row, then copy it.
        fn snapshot_cache(src_cache_dir: &Path, dst_cache_dir: &tempfile::TempDir) {
            let src_db = src_cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&src_db).expect("source store db");
            conn.execute_batch("PRAGMA wal_checkpoint(TRUNCATE)")
                .expect("checkpoint source wal");
            drop(conn);
            let dst_db = dst_cache_dir.path().join(".lomo-sqlite").join("store.db");
            fs::create_dir_all(dst_db.parent().expect("dst parent")).expect("dst dir");
            fs::copy(&src_db, &dst_db).expect("cache snapshot");
        }

        /// Whether `memo_id` is pinned in this session's live projection.
        fn is_pinned(ctx: &Ctx, memo_id: &str) -> bool {
            summaries(&ctx.session, false)
                .iter()
                .any(|summary| summary.memo_id == memo_id && summary.is_pinned)
        }

        fn projected_ids(ctx: &Ctx, trash_only: bool) -> BTreeSet<String> {
            summaries(&ctx.session, trash_only)
                .iter()
                .map(|summary| summary.memo_id.clone())
                .collect()
        }

        /// Finds the memo id of the block carrying `needle` in the live projection.
        fn memo_by_body(ctx: &Ctx, needle: &str) -> String {
            summaries(&ctx.session, false)
                .into_iter()
                .find(|summary| {
                    ctx.session
                        .projected_memo(&summary.memo_id)
                        .expect("snapshot")
                        .is_some_and(|snapshot| snapshot.body.contains(needle))
                })
                .expect("a memo carrying the needle body")
                .memo_id
        }

        /// Pins a memo through the app path so the durable state tip keeps
        /// `pinned=true` — the fact every pin probe below depends on. The rebuild
        /// realigns the committed `memo_pin` row to the durable tip's timestamp.
        fn pin_active(ctx: &Ctx, memo_id: &str, operation_id: &str) {
            ctx.session
                .pin_memo(
                    PinMemoRequest::new(
                        OperationId::parse(operation_id).expect("operation id"),
                        MemoId::parse(memo_id).expect("memo id"),
                        PinPolicy::Pinned { at_ms: None },
                    )
                    .expect("pin request"),
                )
                .expect("pin");
            ctx.session
                .rebuild_projection()
                .expect("the pin's durable state records reconcile");
            assert!(
                is_pinned(ctx, memo_id),
                "fixture sanity: the memo is pinned"
            );
        }

        /// Unpins a memo through the app path so the durable state tip moves to a
        /// `pinned=false` head revision.
        fn unpin_active(ctx: &Ctx, memo_id: &str, operation_id: &str) {
            ctx.session
                .pin_memo(
                    PinMemoRequest::new(
                        OperationId::parse(operation_id).expect("operation id"),
                        MemoId::parse(memo_id).expect("memo id"),
                        PinPolicy::Unpinned,
                    )
                    .expect("unpin request"),
                )
                .expect("unpin");
            ctx.session
                .rebuild_projection()
                .expect("the unpin's durable state records reconcile");
            assert!(
                !is_pinned(ctx, memo_id),
                "fixture sanity: the memo is unpinned"
            );
        }

        /// Soft-deletes a memo through the app path so the durable trash record
        /// and the pinned-and-trashed state tip are both committed.
        fn delete_active(ctx: &Ctx, memo_id: &str, operation_id: &str) {
            let fingerprint = summaries(&ctx.session, false)
                .iter()
                .find(|summary| summary.memo_id == memo_id)
                .map(|summary| summary.file_fingerprint.clone())
                .expect("the delete target is projected");
            ctx.session
                .delete_memo(DeleteMemoRequest {
                    operation_id: OperationId::parse(operation_id).expect("operation id"),
                    memo_id: MemoId::parse(memo_id).expect("memo id"),
                    expected_document_fingerprint: fingerprint,
                    trashed_at_ms: Some(1_757_500_000_000),
                })
                .expect("delete");
            ctx.session
                .rebuild_projection()
                .expect("the delete's durable records reconcile");
        }

        /// Absolute path of a memo's canonical v2 state head inside `workspace`.
        fn state_head_file(workspace: &Path, memo_id: &str) -> PathBuf {
            let paths = lomo_workspace::LomoPaths::for_workspace_with_layout(
                Path::new(""),
                lomo_workspace::LomoLayoutVersion::V2,
            );
            workspace.join(lomo_workspace::state_head_path(&paths, memo_id))
        }

        /// Absolute path of a memo's canonical v1 trash record inside `workspace`.
        fn trash_record_file(workspace: &Path, memo_id: &str) -> PathBuf {
            workspace.join(
                lomo_workspace::trash_record_relative_path(memo_id)
                    .expect("trash path")
                    .as_str(),
            )
        }

        /// Drops the path that defeats scope proving so the same workspace
        /// materializes on the materialize arm.
        fn force_materialize_path(workspace: &Path) {
            fs::write(
                workspace.join(".lomo/unscoped.evidence"),
                b"not a durable record".as_slice(),
            )
            .expect("unclassifiable path");
        }

        /// Both reconcile arms over the same committed cache must commit the same
        /// projection rows and answer the same pin verdict.
        fn assert_carry_arms_equal(ctx_a: &Ctx, ctx_b: &Ctx, memo_id: &str) {
            assert_eq!(
                public_dump(&ctx_a.session),
                public_dump(&ctx_b.session),
                "the incremental and materialize arms diverged on the public surface"
            );
            assert_eq!(
                projection_dump(&ctx_a.cache_dir),
                projection_dump(&ctx_b.cache_dir),
                "the incremental and materialize arms committed different projection rows"
            );
            assert_eq!(
                pin_rows(&ctx_a.cache_dir),
                pin_rows(&ctx_b.cache_dir),
                "the arms disagree on the committed memo_pin row set"
            );
            assert_eq!(
                is_pinned(ctx_a, memo_id),
                is_pinned(ctx_b, memo_id),
                "the same durable facts plus the same committed cache flipped the pin verdict"
            );
        }

        // ---------- one file, two authorities: the canonical slot itself ----------
        //
        // `pins()` judges a head file by its body: `heads/<victim>.rec` carrying a
        // byte copy of `heads/<zeta>.rec` is duplicate zeta evidence at a
        // non-canonical path — tip-check passes, the path is absorbed, victim's
        // identity is never attested and its committed pin carries. The scoped
        // pass makes the identical absorb judgment for the identical reason — and
        // then `rewalk` resolves `state_tip(victim)` for the committed pin row
        // through that same physical file, where `decode_typed` dies on the
        // `head:zeta` envelope: `record_identity_mismatch`, every pass, forever.
        // The same durable bytes plus the same committed cache produce a session
        // that can never reconcile again on one arm and a clean carried pin on
        // the other — the invariant splits on which judge reads the slot.
        #[test]
        fn a_foreign_body_at_the_canonical_head_path_splits_the_arms() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[
                    ("10:00:00", "stayer"),
                    ("10:01:00", "hijack victim"),
                    ("10:02:00", "hijack zeta"),
                ],
            );
            let ctx_a = open_session(scratch.path());
            ctx_a.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx_a, "hijack victim");
            let zeta = memo_by_body(&ctx_a, "hijack zeta");
            pin_active(&ctx_a, &victim, "pin-victim");
            pin_active(&ctx_a, &zeta, "pin-zeta");

            // Hijack victim's canonical slot with zeta's head bytes: the envelope
            // still decodes, the body still parses — only the identity is wrong.
            fs::copy(
                state_head_file(&ctx_a.workspace, &zeta),
                state_head_file(&ctx_a.workspace, &victim),
            )
            .expect("hijack canonical head");

            let incremental = ctx_a.session.rebuild_projection();

            // Pure cold on the same durable bytes: `pins()` absorbs the foreign
            // body at the canonical slot as tolerated zeta evidence.
            let oracle =
                fresh_oracle(&ctx_a.workspace).expect("the cold scan absorbs the hijacked slot");

            // The materialize arm over the same committed cache keeps victim's
            // pin through the unattested carry — then its own next incremental
            // pass dies the same death the first arm did.
            let cache_b = tempdir().expect("cache b");
            snapshot_cache(&ctx_a.cache_dir, &cache_b);
            force_materialize_path(&ctx_a.workspace);
            let ctx_b = open_session_with_cache(&ctx_a.workspace, cache_b).expect("session b");
            assert!(
                is_pinned(&ctx_b, &victim),
                "the carried pin survives on the cold arm — victim is unattested"
            );
            let incremental_b = ctx_b.session.rebuild_projection();

            assert!(
                incremental.is_ok(),
                "cold tolerates the same bytes (oracle ok, materialize arm ok + \
                 pin carried); every scoped pass dies on the canonical slot — \
                 arm a: {incremental:?}, arm b pass 2: {incremental_b:?}"
            );
            drop(oracle);
            assert!(
                !file_listing_paths(&ctx_b.cache_dir)
                    .contains(&format!(".lomo/state/v2/heads/{victim}.rec")),
                "the foreign body at the canonical slot must not commit as the \
                 baseline row for victim's head"
            );
            assert_carry_arms_equal(&ctx_a, &ctx_b, &victim);
        }

        // ---------- condition (a) trusts the path, not the anchor ----------
        //
        // `absorb_duplicate_state_head` absorbs a stem-mismatched stray without a
        // tip resolution whenever its claimed canonical *path* is inside this
        // pass's diff scope. That premise — "the canonical head was re-committed
        // this pass" — is only true when the file at that path actually anchors
        // the claimed identity. With the canonical slot hijacked by a foreign
        // body, every later pass still sees both files diff (absorbed files are
        // never committed), condition (a) still fires for the stray, and
        // `state_tip(victim)` is never resolved — while `pins()` resolves it for
        // the stray's tip-check and dies `record_identity_mismatch`. For an
        // unpinned victim the re-walk never touches the identity either, so the
        // scoped path absorbs the pair forever.
        #[test]
        fn a_stray_shadowing_a_hijacked_canonical_path_is_absorbed_forever() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[
                    ("10:00:00", "stayer"),
                    ("10:01:00", "shadow victim"),
                    ("10:02:00", "shadow zeta"),
                ],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "shadow victim");
            let zeta = memo_by_body(&ctx, "shadow zeta");
            // A head must exist for the stray to claim, but no committed pin —
            // the re-walk's committed-pin supply is what would have tripped the
            // foreign body, so victim must owe the durable tip nothing.
            pin_active(&ctx, &victim, "pin-victim");
            unpin_active(&ctx, &victim, "unpin-victim");
            pin_active(&ctx, &zeta, "pin-zeta");

            let victim_head =
                fs::read(state_head_file(&ctx.workspace, &victim)).expect("victim head bytes");
            fs::copy(
                state_head_file(&ctx.workspace, &zeta),
                state_head_file(&ctx.workspace, &victim),
            )
            .expect("hijack canonical head");
            fs::write(
                ctx.workspace.join(".lomo/state/v2/heads/zz-dup.rec"),
                victim_head,
            )
            .expect("stray claims victim");

            // Pass 1 absorbs both; nothing commits them so pass 2 diffs and
            // re-judges the identical pair — the absorption is permanent.
            let first = ctx.session.rebuild_projection();
            let second = ctx.session.rebuild_projection();

            let cold_error = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("the hijacked canonical slot must fail cold too"),
                Err(error) => error,
            };
            assert_eq!(
                cold_error.code(),
                "record_identity_mismatch",
                "cold pins() resolves victim's canonical slot and dies: {cold_error:?}"
            );
            for (pass, result) in [(0, first), (1, second)] {
                assert!(
                    result.is_err(),
                    "pass {pass}: cold dies {cold_error:?} resolving the dup's \
                     victim tip through the hijacked slot — the scoped pass must \
                     not keep absorbing the stray on the path's say-so: {result:?}"
                );
            }
        }

        // ---------- the F8-1 supply's second direction: the trash claim ----------
        //
        // `recreated_ids` draws from `emitted_ids ∪ trash_upserts` whose gather-
        // time `memo` row is absent. The emitted direction was probed last round;
        // the claim direction is the one where "recreated" and "survived" differ
        // only in whether the row existed when the gather snapshot read it: the
        // record re-claims an identity no document emits and no committed row
        // carries. Delete -> doc out -> record out -> record back lands the claim
        // with the claimant absent from every other supply set — only the new
        // supply joins it to the durable pinned tip.
        #[test]
        fn a_trash_claim_recreating_an_absent_row_recovers_the_durable_pin() {
            let scratch = tempdir().expect("workspace");
            write_doc(scratch.path(), "2026_09_10.md", &[("10:00:00", "stayer")]);
            write_doc(
                scratch.path(),
                "2026_09_11.md",
                &[("10:01:00", "trash pin victim")],
            );
            let ctx_a = open_session(scratch.path());
            ctx_a.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx_a, "trash pin victim");
            pin_active(&ctx_a, &victim, "pin-victim");
            delete_active(&ctx_a, &victim, "trash-victim");
            assert!(
                projected_ids(&ctx_a, true).contains(&victim),
                "fixture sanity: the trashed row is projected"
            );

            let record_bytes =
                fs::read(trash_record_file(&ctx_a.workspace, &victim)).expect("trash record");
            // The document leaves: the row retires and the live claim re-projects
            // it trashed — `retire_memo` supplied that pass's durable verdict.
            fs::remove_file(ctx_a.workspace.join("2026_09_11.md")).expect("remove doc");
            ctx_a
                .session
                .rebuild_projection()
                .expect("the doc removal reconciles");
            assert!(
                projected_ids(&ctx_a, true).contains(&victim)
                    && pin_rows(&ctx_a.cache_dir)
                        .iter()
                        .any(|(id, _)| id == &victim),
                "fixture sanity: the surviving claim re-projects the row and the \
                 pinned tip re-projects the pin"
            );
            // The record leaves: the claimed row retires for good and the
            // cascade takes `memo_pin` with it — the absent-row shape.
            fs::remove_file(trash_record_file(&ctx_a.workspace, &victim)).expect("remove record");
            ctx_a
                .session
                .rebuild_projection()
                .expect("the record removal reconciles");
            assert!(
                !projected_ids(&ctx_a, true).contains(&victim)
                    && pin_rows(&ctx_a.cache_dir)
                        .iter()
                        .all(|(id, _)| id != &victim),
                "fixture sanity: the row and its pin are both gone"
            );

            // The record returns: the claim re-creates the row while its
            // identity sits outside every other `state_memos` supply — the head
            // never diffed, nothing retired it, `committed_pins` no longer names
            // it. Only the absent-row supply joins it to the durable tip.
            fs::write(trash_record_file(&ctx_a.workspace, &victim), record_bytes)
                .expect("restore record");
            ctx_a
                .session
                .rebuild_projection()
                .expect("the record restore reconciles");
            assert!(
                pin_rows(&ctx_a.cache_dir)
                    .iter()
                    .any(|(id, _)| id == &victim),
                "the durable pinned tip must re-derive the pin the cascade deleted \
                 — the same answer `pins()` gives the materialize arm"
            );

            let cache_b = tempdir().expect("cache b");
            snapshot_cache(&ctx_a.cache_dir, &cache_b);
            force_materialize_path(&ctx_a.workspace);
            let ctx_b = open_session_with_cache(&ctx_a.workspace, cache_b).expect("session b");
            ctx_b
                .session
                .rebuild_projection()
                .expect("the same workspace materializes");

            assert_carry_arms_equal(&ctx_a, &ctx_b, &victim);
        }

        // ---------- never-commit and listing_removes eviction, every arm ----------
        //
        // An absorbed head must never reach `file_listing`: a committed row is
        // the F8-2 self-seed — it stops diffing and goes invisible the moment the
        // tip it shadows moves. A row a predecessor committed for the absorbed
        // path is the same seed already planted, and must be evicted by
        // `listing_removes` — on the reconcile apply *and* on the
        // `align_listing_snapshot` arm `try_reconcile` takes.
        #[test]
        fn absorbed_duplicate_heads_never_commit_and_stale_rows_are_evicted() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "dup victim")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "dup victim");
            pin_active(&ctx, &victim, "pin-victim");
            let dup = ".lomo/state/v2/heads/zzz-dup.rec";
            fs::copy(
                state_head_file(&ctx.workspace, &victim),
                ctx.workspace.join(dup),
            )
            .expect("write duplicate head");
            ctx.session
                .rebuild_projection()
                .expect("the tip-matching duplicate is absorbed");
            assert!(
                !file_listing_paths(&ctx.cache_dir).contains(dup),
                "an absorbed head must never reach file_listing — a committed \
                 stray row is exactly the F8-2 self-seed"
            );

            // The reconcile-apply arm: the planted stale row makes the stray
            // diff; the tip-match absorb must evict the row with it.
            stamp_listing_row(&ctx.cache_dir, dup, "planted-legacy-digest");
            ctx.session.rebuild_projection().expect("evict pass");
            assert!(
                !file_listing_paths(&ctx.cache_dir).contains(dup),
                "listing_removes must evict the absorbed path's stale row"
            );

            // The align arm: the scope can't be proven, `try_reconcile` matches
            // the projection image, and the absorbed path's row is not among the
            // inventory rows the align commits.
            stamp_listing_row(&ctx.cache_dir, dup, "planted-legacy-digest");
            force_materialize_path(&ctx.workspace);
            ctx.session.rebuild_projection().expect("align pass");
            assert!(
                !file_listing_paths(&ctx.cache_dir).contains(dup),
                "align_listing_snapshot must evict the absorbed path's row too"
            );
            assert!(
                file_listing_paths(&ctx.cache_dir)
                    .contains(&format!(".lomo/state/v2/heads/{victim}.rec")),
                "the canonical head row must survive — absorbed-path eviction \
                 must never hit the canonical slot"
            );
            assert!(
                is_pinned(&ctx, &victim),
                "the absorbed duplicate must not disturb the pin verdict"
            );
        }

        // ---------- v12 -> v13: LIKE precision and re-judgment ----------
        //
        // The migration drops every `.lomo/state/v2/heads/%` listing row and the
        // digest meta — head files re-diff and pass the naming-authority gate an
        // older build never enforced. The clause must be precise: `objects/`,
        // `headsX`, `memos/` and `trash/` rows share the prefix up to `state/v2/`
        // and must survive. Then the first post-upgrade pass re-commits the
        // canonical rows, keeps the absorbed stray out and retires the dead rows.
        #[test]
        fn the_v12_to_v13_migration_drops_only_state_head_listing_rows() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "migration victim")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "migration victim");
            pin_active(&ctx, &victim, "pin-victim");
            let dup = ".lomo/state/v2/heads/zzz-dup.rec";
            fs::copy(
                state_head_file(&ctx.workspace, &victim),
                ctx.workspace.join(dup),
            )
            .expect("write duplicate head");
            ctx.session
                .rebuild_projection()
                .expect("the tip-matching duplicate is absorbed");

            // The v12 baseline a pre-fix build could carry: the absorbed stray
            // committed, a dead heads row, and shared-prefix rows the LIKE clause
            // must not touch.
            stamp_listing_row(&ctx.cache_dir, dup, "committed-stray-digest");
            stamp_listing_row(
                &ctx.cache_dir,
                ".lomo/state/v2/heads/ghost.rec",
                "dead-head-row",
            );
            stamp_listing_row(
                &ctx.cache_dir,
                ".lomo/state/v2/objects/ghost.rec",
                "object-row",
            );
            stamp_listing_row(
                &ctx.cache_dir,
                ".lomo/state/v2/headsX/ghost.rec",
                "shared-prefix-decoy",
            );
            stamp_listing_row(
                &ctx.cache_dir,
                ".lomo/state/v2/memos/ghost.rec",
                "shared-prefix-decoy",
            );
            stamp_listing_row(&ctx.cache_dir, ".lomo/trash/v1/ghost.rec", "trash-decoy");
            set_user_version(&ctx.cache_dir, 12);

            // Re-open the store so the migration replays over the planted rows —
            // no reconcile has run yet at this assertion point.
            drop(lomo_store::Store::open_projection(&ctx.cache_dir).expect("migration"));
            let migrated = file_listing_paths(&ctx.cache_dir);
            for dropped in [dup, ".lomo/state/v2/heads/ghost.rec"] {
                assert!(
                    !migrated.contains(dropped),
                    "every heads/* listing row must drop: {dropped}"
                );
            }
            assert!(
                !migrated.contains(&format!(".lomo/state/v2/heads/{victim}.rec")),
                "even the canonical head row drops — it re-verifies on the next scan"
            );
            for survivor in [
                ".lomo/state/v2/objects/ghost.rec",
                ".lomo/state/v2/headsX/ghost.rec",
                ".lomo/state/v2/memos/ghost.rec",
                ".lomo/trash/v1/ghost.rec",
            ] {
                assert!(
                    migrated.contains(survivor),
                    "the LIKE clause must not touch shared-prefix rows: {survivor}"
                );
            }
            let db = ctx.cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            let meta_rows: i64 = conn
                .query_row(
                    "SELECT COUNT(*) FROM store_meta WHERE key='workspace_listing_digest'",
                    [],
                    |row| row.get(0),
                )
                .expect("meta query");
            assert_eq!(meta_rows, 0, "the digest meta must drop with the rows");

            // The first post-upgrade pass re-judges every head: canonical rows
            // re-commit, the stray is absorbed and stays uncommitted, and the
            // dead rows retire through the removal arms.
            ctx.session
                .rebuild_projection()
                .expect("the first post-migration pass");
            let rebuilt = file_listing_paths(&ctx.cache_dir);
            assert!(
                rebuilt.contains(&format!(".lomo/state/v2/heads/{victim}.rec")),
                "the canonical head row must be rebuilt"
            );
            assert!(
                !rebuilt.contains(dup),
                "the absorbed stray must stay out of the rebuilt baseline"
            );
            assert!(
                is_pinned(&ctx, &victim),
                "the upgrade must not disturb the pin verdict"
            );
        }
    }
}
