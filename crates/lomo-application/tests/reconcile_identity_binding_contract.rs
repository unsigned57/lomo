//! Adversarial reconcile probes on identity binding: canonical-slot body
//! authority, the shared head-admit predicate, tombstone closure walks and
//! the stem↔claim binding across objects/tombstones/state objects. Merged
//! from the numbered re-audit rounds.

mod tests {

    // adversarial-reaudit round 10: the P10-F1 batch
    // (`audit/26-修复-canonical槽异体.md`) claims the audit/25 REDs are closed:
    // `state_scoped` now reads its own canonical slot under *body* authority
    // (alien body -> `Unattested`, anchored body -> `state_tip_at`), `state()`
    // decodes every diffed head in a first pass that yields the true
    // `anchored_stems` set, and absorb condition (a) trusts only a stem that was
    // both diffed and body-anchored this pass. The probes below attack what the
    // new two-pass / two-authority split still does not look at:
    //
    // - `decode_state_head` admits by *body* only — envelope `record_id` is never
    //   checked — so a file that anchors its stem by body but carries a foreign
    //   envelope still lands in `anchored_stems`, granting every stray a free
    //   absorb. The anchor must poison the re-walk itself instead of silently
    //   laundering the strays beneath it.
    // - `decode_state_head` also parses `head_revision_id`, a check `pins()`
    //   once skipped on a stray's claim — the predicate is now shared, so a
    //   claim that cannot parse dies `invalid_revision_id` on both arms.
    // - `Unattested` cascades: an alien body makes the slot's owner unattested,
    //   and the committed-pin carry must stay identical when the identity is
    //   simultaneously a trash-claim target or the slot never entered the diff.
    // - The `anchored_stems` upgrade must preserve the ratified bound: a true
    //   re-anchor tolerates a stale-claiming stray for exactly one pass — never
    //   permanently — and the next pass must confront the live tip.
    // - Coverage (`reconcile_observed_paths`) is not a shield: an uncovered
    //   hijacked slot is still read live by `state_scoped`/`state_tip`, so a
    //   shadowing stray sees the same corruption the cold walk does.
    // - The history-side slot hijack: the envelope check now meets the file on
    //   every arm — `history_scoped` resolves the slot through `history_tip`
    //   and `HistoryGraph::insert` proves `head:<stem>` — so coverage-gated
    //   re-walk, doc-supplied full scan, and doc-less full scan all surface
    //   the same `record_identity_mismatch`.

    #[cfg(test)]
    #[expect(
        clippy::expect_used,
        reason = "adversarial audit tests fail closed with panics on missing facts"
    )]
    mod canonical_slots {
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
            RelativeWorkspacePath,
        };
        use lomo_platform_fs::FsPlatformActionExecutor;
        use lomo_workspace::{
            MemoId, WorkspaceGenerationId, WorkspaceRootId, decode_record, encode_record,
        };
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

        /// One committed `history_record_id` for `memo_id` — the durable object
        /// identity whose file lives at `.lomo/history/v2/objects/<id>.rec`.
        fn history_record_id(cache_dir: &Path, memo_id: &str) -> String {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            conn.query_row(
                "SELECT history_record_id FROM revision_index WHERE memo_id=?1 \
                 ORDER BY history_record_id LIMIT 1",
                [memo_id],
                |row| row.get(0),
            )
            .expect("a committed history record for the memo")
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

        /// Absolute path of a memo's canonical v2 history head inside `workspace`.
        fn history_head_file(workspace: &Path, memo_id: &str) -> PathBuf {
            let paths = lomo_workspace::LomoPaths::for_workspace_with_layout(
                Path::new(""),
                lomo_workspace::LomoLayoutVersion::V2,
            );
            workspace.join(lomo_workspace::history_head_path(&paths, memo_id))
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

        // ---------- a body-anchored pseudo-anchor cannot launder its strays ----------
        //
        // `decode_state_head` admits a head file by *body*: kind, a parseable
        // `StateHead`, a valid memo id and a valid revision id — the envelope
        // `record_id` is only checked later, inside `state_tip_at`'s
        // `decode_typed`. A file whose body names `victim` but whose envelope says
        // `head:forged` therefore lands `victim` in `anchored_stems`, and every
        // stray claiming victim absorbs without a tip resolution. The fix holds
        // only because the pseudo-anchor itself always joins `state_memos`: the
        // re-walk's `state_scoped` re-decodes the same bytes and dies
        // `record_identity_mismatch` — the identical verdict `pins()` produces
        // when its own tip-check resolves the same slot. A pass that let the
        // strays in and never punished the anchor would commit what the cold
        // scan rejects.
        #[test]
        fn a_body_anchored_head_with_a_rogue_envelope_still_fails_both_arms() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "anchor victim")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "anchor victim");
            pin_active(&ctx, &victim, "pin-victim");

            // Re-encode victim's real head body under a foreign envelope record
            // id: body still anchors `victim`, the envelope no longer does.
            let real_head =
                fs::read(state_head_file(&ctx.workspace, &victim)).expect("victim head bytes");
            let body_json = decode_record(&real_head)
                .expect("decode head")
                .payload
                .body_json;
            let rogue = encode_record(&lomo_workspace::LomoPayload {
                kind: lomo_workspace::LomoRecordKind::State,
                record_id: "head:forged".to_owned(),
                body_json,
            })
            .expect("rogue envelope record");
            fs::write(state_head_file(&ctx.workspace, &victim), rogue)
                .expect("plant pseudo-anchor");
            fs::write(
                ctx.workspace.join(".lomo/state/v2/heads/zz-dup.rec"),
                real_head,
            )
            .expect("stray claims victim");

            let first = ctx.session.rebuild_projection();
            let second = ctx.session.rebuild_projection();
            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("a pseudo-anchored slot must fail the cold walk"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "record_identity_mismatch",
                "cold resolves victim's slot through decode_typed: {cold:?}"
            );
            for (pass, result) in [(0, first), (1, second)] {
                let error = match result {
                    Ok(done) => panic!(
                        "pass {pass}: the pseudo-anchor laundered its stray into an \
                         absorb the cold walk rejects {cold:?}: {done:?}"
                    ),
                    Err(error) => error,
                };
                assert_eq!(
                    error.code(),
                    "record_identity_mismatch",
                    "pass {pass}: the anchored body must poison the re-walk with the \
                     same verdict the cold tip-check produces: {error:?}"
                );
            }
        }

        // ---------- a claim that cannot parse never reaches the absorb judgment ----------
        //
        // `pins()`, the scoped pass, and `state_scoped` admit every head file
        // through the one `decode_state_head` predicate, which validates
        // `head_revision_id`. A stray carrying an unparseable revision id
        // therefore dies at admit time on both arms — `invalid_revision_id`,
        // identical code, before either walk reaches the absorb judgment or the
        // tip compare.
        #[test]
        fn a_stray_claim_that_cannot_parse_dies_on_both_arms() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "claim victim")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "claim victim");
            pin_active(&ctx, &victim, "pin-victim");

            // A well-formed record whose claim can never parse — the absorb
            // judgment itself is unreachable past this decode.
            let stray = encode_record(&lomo_workspace::LomoPayload {
                kind: lomo_workspace::LomoRecordKind::State,
                record_id: format!("head:{victim}"),
                body_json: format!(
                    "{{\"memo_id\":\"{victim}\",\"head_revision_id\":\"not-a-revision\"}}"
                ),
            })
            .expect("stray record");
            fs::write(ctx.workspace.join(".lomo/state/v2/heads/zz-mal.rec"), stray)
                .expect("plant malformed stray");

            let incremental = ctx.session.rebuild_projection();
            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("an unparseable claim must fail the cold walk"),
                Err(error) => error,
            };
            let incremental_error = match incremental {
                Ok(done) => panic!(
                    "the unparseable claim was absorbed where the cold walk dies \
                     {cold:?}: {done:?}"
                ),
                Err(error) => error,
            };
            assert_eq!(
                incremental_error.code(),
                "invalid_revision_id",
                "the scoped pass dies at claim decode, before any absorb: \
                 {incremental_error:?}"
            );
            assert_eq!(
                cold.code(),
                "invalid_revision_id",
                "the cold walk admits the stray through the same predicate: {cold:?}"
            );
        }

        // ---------- Unattested x trash claim: the carry survives ----------
        //
        // An alien body at `heads/<victim>.rec` makes victim `Unattested`: durable
        // has no pin answer for it and the committed `memo_pin` row carries —
        // `memo_row_survives` is the only gate. The row that survives can equally
        // be a *trash-claimed* row: the pinned tip was written before the delete,
        // the trash record keeps the row alive, and the hijacked slot must not
        // flip the verdict to either arm. The cold walk computes the same carry —
        // victim never enters `attested_ids`, the row exists, the pin copies.
        #[test]
        fn an_unattested_trash_claimed_identity_still_carries_its_pin() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[
                    ("10:00:00", "stayer"),
                    ("10:01:00", "trashed victim"),
                    ("10:02:00", "trashed zeta"),
                ],
            );
            let ctx_a = open_session(scratch.path());
            ctx_a.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx_a, "trashed victim");
            let zeta = memo_by_body(&ctx_a, "trashed zeta");
            pin_active(&ctx_a, &victim, "pin-victim");
            pin_active(&ctx_a, &zeta, "pin-zeta");
            delete_active(&ctx_a, &victim, "trash-victim");
            assert!(
                pin_rows(&ctx_a.cache_dir)
                    .iter()
                    .any(|(id, _)| id == &victim),
                "fixture sanity: the trashed row keeps its committed pin"
            );

            // Hijack victim's canonical state slot with zeta's head bytes. The
            // trash claim keeps victim's row alive; the alien body makes the slot
            // unattested; the committed pin must carry on both arms.
            fs::copy(
                state_head_file(&ctx_a.workspace, &zeta),
                state_head_file(&ctx_a.workspace, &victim),
            )
            .expect("hijack canonical head");

            ctx_a
                .session
                .rebuild_projection()
                .expect("the hijack reconciles through the Unattested carry");
            assert!(
                pin_rows(&ctx_a.cache_dir)
                    .iter()
                    .any(|(id, _)| id == &victim),
                "the trash-claimed row must keep its carried pin"
            );
            assert!(
                !file_listing_paths(&ctx_a.cache_dir)
                    .contains(&format!(".lomo/state/v2/heads/{victim}.rec")),
                "the alien body must never commit as victim's canonical listing row"
            );

            let cache_b = tempdir().expect("cache b");
            snapshot_cache(&ctx_a.cache_dir, &cache_b);
            force_materialize_path(&ctx_a.workspace);
            let ctx_b = open_session_with_cache(&ctx_a.workspace, cache_b).expect("session b");
            assert!(
                pin_rows(&ctx_b.cache_dir)
                    .iter()
                    .any(|(id, _)| id == &victim),
                "the materialize arm carries the same committed pin"
            );
            assert_carry_arms_equal(&ctx_a, &ctx_b, &victim);
        }

        // ---------- the ratified bound: one pass of grace, never permanent ----------
        //
        // `anchored_stems` is the upgrade of condition (a): a stray claiming
        // `victim` absorbs without a tip resolution only while `heads/<victim>.rec`
        // diffed this pass *and* decoded to victim. A true re-anchor (the unpin
        // writes a fresh head) is exactly the ratified lag — the stray tolerated
        // this pass confronts the new live tip on the next, because absorbed
        // files never commit and always re-diff. What must not come back is the
        // F9-2 shape: the anchor being a foreign body made the absorb permanent.
        #[test]
        fn a_true_reanchor_grants_the_stray_exactly_one_pass() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "lag victim")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "lag victim");
            pin_active(&ctx, &victim, "pin-victim");

            // A stray claiming the *current* tip — benign duplicate evidence the
            // live tip-check absorbs identically on both arms.
            let tip_one =
                fs::read(state_head_file(&ctx.workspace, &victim)).expect("tip-one head bytes");
            fs::write(
                ctx.workspace.join(".lomo/state/v2/heads/zz-dup.rec"),
                tip_one,
            )
            .expect("stray claims the current tip");
            ctx.session
                .rebuild_projection()
                .expect("the tip-matching stray is absorbed");

            // The re-anchor pass: victim's slot diffs and body-anchors, so the
            // stale-claiming stray gets the one ratified pass of grace — the
            // fresh durable verdict the pass installs is the authority.
            unpin_active(&ctx, &victim, "unpin-victim");

            // Grace is exactly one pass. The stray never committed, so it
            // re-diffs and now meets the live tip it diverged from — the full
            // scan the deferral falls back to reports the cold verdict verbatim.
            let confronted = ctx.session.rebuild_projection();
            let error = match confronted {
                Ok(done) => panic!(
                    "the stale claim kept its grace past the re-anchor pass — the \
                     absorb must never be permanent: {done:?}"
                ),
                Err(error) => error,
            };
            assert_eq!(
                error.code(),
                "state_head_changed",
                "the stale claim must confront the live tip on the very next pass: \
                 {error:?}"
            );

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("the stale claim must fail the cold walk"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "state_head_changed",
                "cold never granted the stray any grace: {cold:?}"
            );
        }

        // ---------- the history-side slot hijack, one code on every arm ----------
        //
        // A `heads/<victim>.rec` carrying `head:zeta` bytes violates the head
        // file's address claim. Every arm now proves that claim the same way —
        // `history_scoped` resolves the slot through `history_tip`'s envelope
        // check and `HistoryGraph::insert` proves `head:<stem>` before indexing
        // — so whichever path meets the file first reports the identical
        // `record_identity_mismatch`:
        //
        // - Coverage-gated (`observed` hides the head; a removed object pulls
        //   the victim into the re-walk): `history_scoped` -> `history_tip`.
        // - Diffed head while the victim is still doc-emitted: scope proving
        //   defers to the full scan, where `scan_markdown_files` calls
        //   `ensure_initial_history` -> `history_tip` -> `decode_typed` BEFORE
        //   `history()` walks anything — the same envelope verdict on the
        //   incremental-fallback and pure-cold arms.
        // - Diffed head after the victim's document is gone: no binding reaches
        //   `ensure_initial_history`, so `history()` itself meets the file —
        //   the insert's own `head:<stem>` proof, still the same code.
        //
        // One code, one physical shape, zero Ok/Err or verdict splits.
        #[test]
        fn a_hijacked_history_slot_fails_closed_on_every_arm() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[
                    ("10:00:00", "stayer"),
                    ("10:01:00", "history victim"),
                    ("10:02:00", "history zeta"),
                ],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "history victim");
            let zeta = memo_by_body(&ctx, "history zeta");

            // Hijack victim's canonical history slot with zeta's head bytes.
            fs::copy(
                history_head_file(&ctx.workspace, &zeta),
                history_head_file(&ctx.workspace, &victim),
            )
            .expect("hijack canonical history head");

            // The coverage-gated provenance: the watcher reports only the object
            // removal — the hijacked head stays uncovered — yet the removal still
            // pulls victim into `history_memos`, and the re-walk reads the slot.
            let record_id = history_record_id(&ctx.cache_dir, &victim);
            fs::remove_file(
                ctx.workspace
                    .join(format!(".lomo/history/v2/objects/{record_id}.rec")),
            )
            .expect("remove a victim history object");
            let observed = ctx.session.reconcile_observed_paths(&[]);
            let scoped_error = match observed {
                Ok(done) => panic!("the hijacked slot passed the scoped history re-walk: {done:?}"),
                Err(error) => error,
            };
            assert_eq!(
                scoped_error.code(),
                "record_identity_mismatch",
                "the re-walk resolves the slot through history_tip's envelope check: {scoped_error:?}"
            );

            // The diffed-head provenance with the victim still doc-emitted: the
            // incremental arm defers to the full scan, which dies inside
            // `ensure_initial_history` before `history()` runs — the same
            // `decode_typed` envelope verdict on both arms.
            let incremental = ctx.session.rebuild_projection();
            let incremental_error = match incremental {
                Ok(done) => panic!("the hijacked slot reconciled clean: {done:?}"),
                Err(error) => error,
            };
            assert_eq!(
                incremental_error.code(),
                "record_identity_mismatch",
                "the full-scan fallback surfaces the cold code: {incremental_error:?}"
            );

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("the hijacked slot must fail cold"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "record_identity_mismatch",
                "cold dies in ensure_initial_history, before the graph insert: {cold:?}"
            );

            // The doc-less provenance: with the emitting document gone, no
            // binding reaches `ensure_initial_history` and `history()` meets
            // the file — whose own `head:<stem>` proof reports the identical
            // envelope verdict.
            fs::remove_file(ctx.workspace.join("2026_09_10.md")).expect("remove doc");
            let orphaned = ctx.session.rebuild_projection();
            let orphaned_error = match orphaned {
                Ok(done) => panic!("the hijacked slot reconciled clean: {done:?}"),
                Err(error) => error,
            };
            assert_eq!(
                orphaned_error.code(),
                "record_identity_mismatch",
                "with no doc emitting the victim, the graph insert's own envelope \
                 proof reports the same code: {orphaned_error:?}"
            );

            let cold_orphaned = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("a foreign-envelope head file must fail cold"),
                Err(error) => error,
            };
            assert_eq!(
                cold_orphaned.code(),
                "record_identity_mismatch",
                "cold reports the identical envelope verdict: {cold_orphaned:?}"
            );
        }

        // ---------- coverage is not a shield for the live tip-check ----------
        //
        // `reconcile_observed_paths` restricts *upsert classification* to the
        // attested set — an unattested change stays deferred — but it does not
        // restrict what the live tip-check reads. With victim's slot hijacked and
        // the watcher reporting only the shadowing stray, the stray's absorb
        // judgment still resolves `state_tip(victim)` against the live bytes and
        // dies on the same `record_identity_mismatch` the cold walk reports.
        #[test]
        fn an_uncovered_hijack_still_confronts_a_shadowing_stray() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[
                    ("10:00:00", "stayer"),
                    ("10:01:00", "watched victim"),
                    ("10:02:00", "watched zeta"),
                ],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "watched victim");
            let zeta = memo_by_body(&ctx, "watched zeta");
            pin_active(&ctx, &victim, "pin-victim");
            pin_active(&ctx, &zeta, "pin-zeta");

            let victim_head =
                fs::read(state_head_file(&ctx.workspace, &victim)).expect("victim head bytes");
            fs::copy(
                state_head_file(&ctx.workspace, &zeta),
                state_head_file(&ctx.workspace, &victim),
            )
            .expect("hijack canonical head");
            let stray = ".lomo/state/v2/heads/zz-shadow.rec";
            fs::write(ctx.workspace.join(stray), victim_head).expect("stray claims victim");

            // Only the stray is attested; the hijack itself is outside coverage.
            let observed =
                ctx.session.reconcile_observed_paths(&[
                    RelativeWorkspacePath::parse(stray).expect("stray path")
                ]);
            let error = match observed {
                Ok(done) => panic!("coverage shielded the stray from the hijacked slot: {done:?}"),
                Err(error) => error,
            };
            assert_eq!(
                error.code(),
                "record_identity_mismatch",
                "the live tip-check reads the slot regardless of coverage: {error:?}"
            );

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("the hijacked slot must fail cold"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "record_identity_mismatch",
                "cold dies the same death through the same live tip-check: {cold:?}"
            );
        }

        // ---------- an unseen hijack still yields the same Unattested verdict ----------
        //
        // The complement of the coverage probe: nothing attests either the
        // hijacked slot or a shadowing stray — an unrelated document event is the
        // only observed change. The slot's own absorb judgment legitimately
        // defers (the watcher contract postpones unchanged-by-attestation files),
        // but victim's pin verdict must not defer: the committed-pin re-walk
        // reads the live slot, finds the alien body, and answers `Unattested` —
        // the carry the materialize arm produces for the same committed cache.
        #[test]
        fn an_unseen_hijack_still_yields_the_same_unattested_carry() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[
                    ("10:00:00", "stayer"),
                    ("10:01:00", "unseen victim"),
                    ("10:02:00", "unseen zeta"),
                ],
            );
            let ctx_a = open_session(scratch.path());
            ctx_a.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx_a, "unseen victim");
            let zeta = memo_by_body(&ctx_a, "unseen zeta");
            pin_active(&ctx_a, &victim, "pin-victim");
            pin_active(&ctx_a, &zeta, "pin-zeta");

            fs::copy(
                state_head_file(&ctx_a.workspace, &zeta),
                state_head_file(&ctx_a.workspace, &victim),
            )
            .expect("hijack canonical head");
            write_doc(
                &ctx_a.workspace,
                "2026_09_12.md",
                &[("09:00:00", "unrelated event")],
            );

            // The watcher attests only the new document: the hijacked slot never
            // enters the diff, yet the committed-pin re-walk must still read it.
            ctx_a
                .session
                .reconcile_observed_paths(&[
                    RelativeWorkspacePath::parse("2026_09_12.md").expect("doc path")
                ])
                .expect("the unseen hijack reconciles through the same verdict");
            assert!(
                is_pinned(&ctx_a, &victim),
                "the Unattested carry answers the same pin the cold arm carries"
            );

            let cache_b = tempdir().expect("cache b");
            snapshot_cache(&ctx_a.cache_dir, &cache_b);
            force_materialize_path(&ctx_a.workspace);
            let ctx_b = open_session_with_cache(&ctx_a.workspace, cache_b).expect("session b");
            assert_carry_arms_equal(&ctx_a, &ctx_b, &victim);
        }
    }

    // adversarial-reaudit round 11: the P11-F2 batch
    // (`audit/28-修复-错误码出处.md`) claims error-code provenance is unified:
    // `decode_state_head` is the single admit predicate shared by `pins()`,
    // `state()`'s first pass and `state_scoped`; `history_scoped` resolves its
    // slot through the canonical `history_tip` parser; `HistoryGraph::insert`
    // self-proves envelope `head:<stem>` -> revision -> identity before indexing;
    // `history_head_scope_mismatch` and `duplicate_history_head` are retired.
    // The probes below attack what the convergence still leaves open:
    //
    // - The tombstone semantics in `history_scoped`'s closure walk: a tombstone at
    //   `tombstones/<id>.rec` prunes `project_loaded`'s output but never exempts
    //   `objects/<id>.rec` from admission — the walk audits every reachable byte
    //   the cold `history()` scan would, and a valid tombstoned object still
    //   projects nothing.
    // - `RevisionId::parse` validates uppercase hex (normalizing a copy it
    //   discards): the admitted `StateHead` keeps the raw claim, so a
    //   case-variant claim must still diverge as `state_head_changed` on both
    //   arms — the shared predicate must not launder normalization into equality.
    // - The `duplicate_history_head` retirement argument probed negatively: two
    //   distinct paths claiming one memo can only exist with a stem violation, so
    //   a stray head copy must die on its own envelope, never reach the map.
    // - A self-consistent stray head (`head:zz-twin` envelope + `zz-twin` body)
    //   passes the file-level self-proof and can only die at tip resolution —
    //   identically on both arms.
    // - The declared compound-fault first-code residual pinned concretely:
    //   `decode_history_head` parses memo_id before revision_id while
    //   `insert`/`history_tip` judge revision before identity — same file, two
    //   first codes, both fail-closed.
    // - The state-domain ordering residual: `state()`'s first pass decodes every
    //   diffed head before any absorb judgment, while cold `pins()` interleaves
    //   admit and tip-check per file — a decode fault and a divergent claim
    //   report different first codes per arm.
    // - The `Franken` history head (honest `head:<victim>` envelope, foreign
    //   `zeta` body): unified provenance must answer `history_head_mismatch` on
    //   the coverage-hidden arm, the diffed arm, and the cold arm alike.

    #[cfg(test)]
    #[expect(
        clippy::expect_used,
        reason = "adversarial audit tests fail closed with panics on missing facts"
    )]
    mod admit_predicates {
        use std::{
            fmt::Write as _,
            fs,
            path::{Path, PathBuf},
            sync::Arc,
        };

        use lomo_application::{
            MemoFilters, MemoQuery, MemoSort, PinMemoRequest, PinPolicy, UpdateMemoRequest,
            WorkspaceSession, WorkspaceSessionConfig,
        };
        use lomo_core::{
            CapabilityToken, LomoError, OperationId, PageSize, PlatformActionExecutor,
            RelativeWorkspacePath,
        };
        use lomo_platform_fs::FsPlatformActionExecutor;
        use lomo_workspace::{
            LomoPayload, LomoRecordKind, MemoId, WorkspaceGenerationId, WorkspaceRootId,
            decode_record, encode_record,
        };
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

        /// The committed `history_record_id` for `memo_id` at `revision` — the
        /// durable object identity `.lomo/history/v2/objects/<id>.rec` holds.
        fn history_record_at(cache_dir: &Path, memo_id: &str, revision: i64) -> String {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            conn.query_row(
                "SELECT history_record_id FROM revision_index WHERE memo_id=?1 \
                 AND revision=?2 LIMIT 1",
                rusqlite::params![memo_id, revision],
                |row| row.get(0),
            )
            .expect("a committed history record for the memo at that revision")
        }

        /// The memo's newest committed `history_record_id` — its live tip claim.
        fn history_tip_record(cache_dir: &Path, memo_id: &str) -> String {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            conn.query_row(
                "SELECT history_record_id FROM revision_index WHERE memo_id=?1 \
                 ORDER BY revision DESC LIMIT 1",
                [memo_id],
                |row| row.get(0),
            )
            .expect("a committed history tip for the memo")
        }

        /// The memo's committed `revision_index` record ids, revision-ordered —
        /// the exact row set a history replace leaves behind.
        fn revision_records(cache_dir: &Path, memo_id: &str) -> Vec<String> {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            let mut statement = conn
                .prepare(
                    "SELECT history_record_id FROM revision_index WHERE memo_id=?1 \
                     ORDER BY revision",
                )
                .expect("revision_index query");
            statement
                .query_map([memo_id], |row| row.get::<_, String>(0))
                .expect("revision_index rows")
                .collect::<Result<Vec<_>, _>>()
                .expect("revision_index collect")
        }

        /// Pins a memo through the app path so its canonical state head and
        /// revision object exist under `.lomo/state/v2/`.
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
        }

        /// Edits a memo through the app path so a second durable history revision
        /// commits beneath the same head — rev1 becomes a non-tip ancestor.
        fn update_active(ctx: &Ctx, memo_id: &str, operation_id: &str, content: &str) {
            let snapshot = ctx
                .session
                .get_memo(&MemoId::parse(memo_id).expect("memo id"))
                .expect("get memo")
                .expect("memo view");
            ctx.session
                .update_memo(UpdateMemoRequest {
                    operation_id: OperationId::parse(operation_id).expect("operation id"),
                    memo_id: MemoId::parse(memo_id).expect("memo id"),
                    content: content.to_owned(),
                    expected_document_fingerprint: snapshot.file_fingerprint,
                    pending_promotes: Vec::new(),
                })
                .expect("update");
        }

        /// Absolute path of a memo's canonical v2 state head inside `workspace`.
        fn state_head_file(workspace: &Path, memo_id: &str) -> PathBuf {
            let paths = lomo_workspace::LomoPaths::for_workspace_with_layout(
                Path::new(""),
                lomo_workspace::LomoLayoutVersion::V2,
            );
            workspace.join(lomo_workspace::state_head_path(&paths, memo_id))
        }

        /// Absolute path of a memo's canonical v2 history head inside `workspace`.
        fn history_head_file(workspace: &Path, memo_id: &str) -> PathBuf {
            let paths = lomo_workspace::LomoPaths::for_workspace_with_layout(
                Path::new(""),
                lomo_workspace::LomoLayoutVersion::V2,
            );
            workspace.join(lomo_workspace::history_head_path(&paths, memo_id))
        }

        /// Whether `path` has a committed `file_listing` row — absorbed duplicate
        /// heads are tolerated evidence that must never become baseline rows.
        fn listing_contains(cache_dir: &Path, path: &str) -> bool {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            conn.query_row(
                "SELECT COUNT(*) FROM file_listing WHERE path=?1",
                [path],
                |row| row.get::<_, i64>(0),
            )
            .expect("file_listing count")
                > 0
        }

        /// The claimed `head_revision_id` inside a durable head file's body.
        fn head_claim(head_bytes: &[u8]) -> String {
            let record = decode_record(head_bytes).expect("head record");
            let body: serde_json::Value =
                serde_json::from_str(&record.payload.body_json).expect("head body json");
            body.get("head_revision_id")
                .and_then(|value| value.as_str())
                .expect("head_revision_id field")
                .to_owned()
        }

        fn head_record_bytes(
            kind: LomoRecordKind,
            envelope_id: &str,
            body_json: String,
        ) -> Vec<u8> {
            encode_record(&LomoPayload {
                kind,
                record_id: envelope_id.to_owned(),
                body_json,
            })
            .expect("head record bytes")
        }

        /// Reads the error out of a `Result`, panicking with `what` on `Ok`.
        fn expect_err(
            what: &str,
            result: Result<lomo_store::RebuildResult, LomoError>,
        ) -> LomoError {
            match result {
                Ok(done) => panic!("{what}: expected Err, got {done:?}"),
                Err(error) => error,
            }
        }

        // ---------- the tombstone prunes the projection, never the byte audit ----------
        //
        // `history_scoped`'s closure walk admits both durable files for every
        // reachable id: the object meets the same `graph.insert` audit the cold
        // `history()` scan applies to every listed byte, and the tombstone feeds
        // `project_loaded`'s prune set — a projection verdict, never an audit
        // exemption. Plant a valid tombstone for a non-tip ancestor whose object
        // bytes are corrupt, then let the watcher attest only the tombstone: the
        // tombstone pulls the memo into `history_memos`, the re-walked closure
        // audit dies on the same decode fault the uncovered reconcile and the
        // cold oracle report. Restoring honest bytes then locks the second half:
        // a VALID tombstoned object reconciles fine and still projects nothing —
        // the bytes were admitted, never turned into facts.
        #[test]
        fn a_tombstoned_object_corruption_dies_in_the_scoped_closure() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "tombstone victim")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "tombstone victim");
            update_active(&ctx, &victim, "hist-update", "tombstone victim v2");
            ctx.session.rebuild_projection().expect("settle");
            let rev_one = history_record_at(&ctx.cache_dir, &victim, 1);
            let rev_two = history_tip_record(&ctx.cache_dir, &victim);
            let object_rel = format!(".lomo/history/v2/objects/{rev_one}.rec");
            let honest_object = fs::read(ctx.workspace.join(&object_rel)).expect("rev1 object");

            // A well-formed tombstone for rev1 plus garbage at rev1's object slot.
            let tombstone_path = format!(".lomo/history/v2/tombstones/{rev_one}.rec");
            let tombstone = encode_record(&LomoPayload {
                kind: LomoRecordKind::HistoryTombstone,
                record_id: rev_one.clone(),
                body_json: format!(
                    "{{\"memo_id\":\"{victim}\",\"revision_id\":\"{rev_one}\",\
                     \"pruned_at_ms\":1757600000000}}"
                ),
            })
            .expect("tombstone record");
            fs::create_dir_all(ctx.workspace.join(".lomo/history/v2/tombstones"))
                .expect("tombstones dir");
            fs::write(ctx.workspace.join(&tombstone_path), tombstone).expect("plant tombstone");
            fs::write(
                ctx.workspace.join(&object_rel),
                b"corrupted-object-bytes".as_slice(),
            )
            .expect("corrupt tombstoned object");

            // The watcher attests the tombstone only. rev1 sits inside the memo's
            // re-walked durable closure, so its bytes are admitted — the same
            // decode fault the uncovered and cold arms report kills the pass.
            let scoped = expect_err(
                "coverage-gated arm",
                ctx.session
                    .reconcile_observed_paths(&[
                        RelativeWorkspacePath::parse(&tombstone_path).expect("tombstone rel")
                    ]),
            );
            let uncovered = expect_err("uncovered reconcile", ctx.session.rebuild_projection());
            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("the cold scan must audit the corrupt object bytes"),
                Err(error) => error,
            };
            assert_eq!(
                scoped.code(),
                uncovered.code(),
                "the re-walked closure audits tombstoned bytes like the full scan: \
                 scoped={scoped:?} uncovered={uncovered:?}"
            );
            assert_eq!(
                uncovered.code(),
                cold.code(),
                "the uncovered and cold arms must report the same byte-verdict: \
                 uncovered={uncovered:?} cold={cold:?}"
            );
            assert!(
                !listing_contains(&ctx.cache_dir, &tombstone_path),
                "a failed reconcile commits nothing — the tombstone stays outside \
                 the baseline and re-diffs on the next attestation"
            );

            // The honest replay pins the prune's real semantics: the valid
            // tombstoned object is still read and admitted — and still projects
            // nothing. The same closure walk that just died on corrupt bytes
            // commits a tip-only replacement set for the memo.
            fs::write(ctx.workspace.join(&object_rel), honest_object).expect("restore object");
            ctx.session
                .reconcile_observed_paths(&[
                    RelativeWorkspacePath::parse(&tombstone_path).expect("tombstone rel")
                ])
                .expect("honest tombstoned bytes reconcile");
            assert_eq!(
                revision_records(&ctx.cache_dir, &victim),
                vec![rev_two],
                "the tombstoned ancestor is admitted but must not project a row"
            );
            fresh_oracle(&ctx.workspace).expect("the restored workspace is cold-clean");
        }

        // ---------- envelope-honest, body-foreign: one code on every arm ----------
        //
        // The Franken head keeps the honest `head:<victim>` envelope but names
        // `zeta` in its body, claiming zeta's real tip revision. The unified
        // provenance must answer `history_head_mismatch` everywhere: on the
        // coverage-hidden arm (an uncovered head is still read live when a
        // removal pulls the memo into the re-walk), on the diffed arm (scope
        // deferral falls into the full scan's identical `insert` verdict), and
        // on the cold arm.
        #[test]
        fn a_franken_history_head_dies_as_history_head_mismatch_on_every_arm() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[
                    ("10:00:00", "stayer"),
                    ("10:01:00", "franken victim"),
                    ("10:02:00", "franken zeta"),
                ],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "franken victim");
            let zeta = memo_by_body(&ctx, "franken zeta");
            let zeta_tip = history_tip_record(&ctx.cache_dir, &zeta);

            // Honest envelope, foreign body, well-formed foreign tip claim.
            let franken = head_record_bytes(
                LomoRecordKind::History,
                &format!("head:{victim}"),
                format!("{{\"memo_id\":\"{zeta}\",\"head_revision_id\":\"{zeta_tip}\"}}"),
            );
            fs::write(history_head_file(&ctx.workspace, &victim), franken)
                .expect("plant franken head");

            // The coverage-hidden arm: a removal pulls victim into the re-walk
            // while the Franken head stays uncovered — the live slot read must
            // still meet it.
            let victim_rev = history_record_at(&ctx.cache_dir, &victim, 1);
            fs::remove_file(
                ctx.workspace
                    .join(format!(".lomo/history/v2/objects/{victim_rev}.rec")),
            )
            .expect("remove victim object");
            let hidden = expect_err(
                "coverage-hidden arm",
                ctx.session.reconcile_observed_paths(&[]),
            );
            assert_eq!(
                hidden.code(),
                "history_head_mismatch",
                "the re-walk resolves the slot through history_tip's identity check: {hidden:?}"
            );

            // The diffed arm: scope proving defers to the full scan, whose insert
            // self-proof reports the identical code.
            let diffed = expect_err("diffed arm", ctx.session.rebuild_projection());
            assert_eq!(
                diffed.code(),
                "history_head_mismatch",
                "the full scan's stem check reports the same verdict: {diffed:?}"
            );

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("a franken head must fail the cold walk"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "history_head_mismatch",
                "cold reports the identical code: {cold:?}"
            );
        }

        // ---------- a second path can never launder a duplicate claim ----------
        //
        // `duplicate_history_head` was retired on the argument that two admitted
        // files claiming one memo id must share one canonical path. The negative
        // case: a raw copy of zeta's head bytes at `heads/zz-copy.rec` — same
        // body, same claim, wrong stem. It must die on its own envelope proof
        // (`head:zeta` != `head:zz-copy`), never reaching the heads map — on the
        // incremental deferral arm and the cold arm alike.
        #[test]
        fn a_second_path_claiming_the_same_memo_dies_on_its_own_stem() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "copy zeta")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let zeta = memo_by_body(&ctx, "copy zeta");

            let zeta_head = fs::read(history_head_file(&ctx.workspace, &zeta)).expect("zeta head");
            fs::write(
                ctx.workspace.join(".lomo/history/v2/heads/zz-copy.rec"),
                zeta_head,
            )
            .expect("plant stray head copy");

            let incremental = expect_err("incremental arm", ctx.session.rebuild_projection());
            assert_eq!(
                incremental.code(),
                "record_identity_mismatch",
                "the stray dies on its own stem's envelope proof: {incremental:?}"
            );
            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("a duplicate-claim head must fail cold"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "record_identity_mismatch",
                "cold reports the identical envelope verdict: {cold:?}"
            );
        }

        // ---------- a nested heads/ path derives a stem no identity can claim ----------
        //
        // The other two-path attack: `heads/sub/zz-nest.rec` derives stem
        // `sub/zz-nest`, which no `MemoId` can parse (path separators are
        // rejected). Even a file crafted self-consistent end-to-end — envelope
        // `head:sub/zz-nest`, body `memo_id:"sub/zz-nest"`, a parseable tip claim —
        // cannot be admitted: cold `insert` dies at `MemoId::parse` after the
        // envelope and stem-identity checks pass; the scoped decoder dies on the
        // same parse inside `decode_history_head`. Both arms must report
        // `invalid_memo_id` — the path layer and the body layer agree there is no
        // legitimate identity for a nested slot.
        #[test]
        fn a_nested_head_path_dies_as_invalid_memo_id_on_both_arms() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "nested zeta")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let zeta = memo_by_body(&ctx, "nested zeta");
            let zeta_tip = history_tip_record(&ctx.cache_dir, &zeta);

            let nested = head_record_bytes(
                LomoRecordKind::History,
                "head:sub/zz-nest",
                format!("{{\"memo_id\":\"sub/zz-nest\",\"head_revision_id\":\"{zeta_tip}\"}}"),
            );
            fs::create_dir_all(ctx.workspace.join(".lomo/history/v2/heads/sub"))
                .expect("nested heads dir");
            fs::write(
                ctx.workspace.join(".lomo/history/v2/heads/sub/zz-nest.rec"),
                nested,
            )
            .expect("plant nested head");

            let incremental = expect_err("incremental arm", ctx.session.rebuild_projection());
            assert_eq!(
                incremental.code(),
                "invalid_memo_id",
                "no MemoId survives a stem containing a path separator: {incremental:?}"
            );
            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("a nested head path must fail cold"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "invalid_memo_id",
                "cold dies on the same parse after the envelope proof passes: {cold:?}"
            );
        }

        // ---------- a self-consistent stray dies only at tip resolution ----------
        //
        // The inverse of the copy: `heads/zz-twin.rec` carries an honest
        // `head:zz-twin` envelope and a body naming `zz-twin` — the file-level
        // self-proof passes, admission is real — but its claimed tip is zeta's
        // revision. The only remaining verdict is the tip resolution's identity
        // check: `revision.memo_id(zeta) != zz-twin` -> `history_head_mismatch`.
        // The re-walk's `history_tip` and the cold `check_tip` hit it identically.
        #[test]
        fn a_self_consistent_stray_head_dies_at_tip_resolution() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "claimant zeta")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let zeta = memo_by_body(&ctx, "claimant zeta");
            let zeta_tip = history_tip_record(&ctx.cache_dir, &zeta);

            let stray = head_record_bytes(
                LomoRecordKind::History,
                "head:zz-twin",
                format!("{{\"memo_id\":\"zz-twin\",\"head_revision_id\":\"{zeta_tip}\"}}"),
            );
            fs::write(
                ctx.workspace.join(".lomo/history/v2/heads/zz-twin.rec"),
                stray,
            )
            .expect("plant self-consistent stray head");

            let incremental = expect_err("incremental arm", ctx.session.rebuild_projection());
            assert_eq!(
                incremental.code(),
                "history_head_mismatch",
                "the admitted stray dies at tip resolution: {incremental:?}"
            );
            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("a stray claiming a foreign tip must fail cold"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "history_head_mismatch",
                "cold dies at the same identity check inside check_tip: {cold:?}"
            );
        }

        // ---------- the declared compound-fault ordering residual, pinned ----------
        //
        // `decode_history_head` validates memo_id before revision_id (scope
        // proving order); `insert`/`history_tip` validate revision before
        // identity (tip-resolution order). One file carrying both faults must
        // stay fail-closed on every arm while reporting a different first code —
        // this is the declared residual, not a defect: the probe pins it so a
        // later claim of "same code" is testable.
        #[test]
        fn compound_faults_report_different_first_codes_but_stay_fail_closed() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "compound zeta")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");

            // Honest envelope `head:zz-compound`, but the body carries an
            // unparseable memo id AND an unparseable revision id.
            let compound = head_record_bytes(
                LomoRecordKind::History,
                "head:zz-compound",
                "{\"memo_id\":\"bad/id\",\"head_revision_id\":\"not-hex\"}".to_owned(),
            );
            fs::write(
                ctx.workspace.join(".lomo/history/v2/heads/zz-compound.rec"),
                compound,
            )
            .expect("plant compound head");

            let incremental = expect_err("incremental arm", ctx.session.rebuild_projection());
            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("a compound-fault head must fail cold"),
                Err(error) => error,
            };
            // Scope proving decodes the body for its owner first: MemoId::parse
            // runs before RevisionId::parse inside decode_history_head.
            assert_eq!(
                incremental.code(),
                "invalid_memo_id",
                "the scoped decoder hits the memo-id fault first: {incremental:?}"
            );
            // The canonical parser resolves tip metadata first: RevisionId::parse
            // runs before the stem-identity check inside HistoryGraph::insert.
            assert_eq!(
                cold.code(),
                "invalid_revision_id",
                "the cold insert hits the revision fault first: {cold:?}"
            );
        }

        // ---------- the anchored absorb defers — never launders — a divergent claim ----------
        //
        // `RevisionId` deserializes the raw claim (serde derive, no normalization);
        // only `RevisionId::parse` output is normalized, and `decode_state_head`
        // discards it after validating. An UPPERCASE variant of the live tip
        // therefore diverges under the raw tip-equality check — but the absorb
        // judgment defers it for exactly one pass whenever the canonical anchor
        // itself is in the same diff (`anchored_stems`). The probe pins the full
        // lifecycle: pass 1 tolerates (never baselines the stray, projects
        // nothing from it), pass 2 rejects `state_head_changed`, and the cold
        // walk rejects identically — the absorb is a deferral, not a laundering.
        #[test]
        fn an_uppercase_revision_claim_is_deferred_then_rejected_on_both_arms() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "case victim")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "case victim");
            pin_active(&ctx, &victim, "pin-case");

            let tip =
                head_claim(&fs::read(state_head_file(&ctx.workspace, &victim)).expect("head"));
            let claim = tip.to_ascii_uppercase();
            assert_ne!(
                claim, tip,
                "a digit-only revision id would make the case variant equal"
            );
            let stray_path = ".lomo/state/v2/heads/zz-up.rec";
            let stray = head_record_bytes(
                LomoRecordKind::State,
                "head:zz-up",
                format!("{{\"memo_id\":\"{victim}\",\"head_revision_id\":\"{claim}\"}}"),
            );
            fs::create_dir_all(ctx.workspace.join(".lomo/state/v2/heads"))
                .expect("state heads dir");
            fs::write(ctx.workspace.join(stray_path), stray).expect("plant uppercase-claim stray");

            // Pass 1: the pin's own durable head write never committed a
            // `file_listing` row, so the canonical head re-diffs beside the stray
            // and lands in `anchored_stems` — the absorb defers the stray's tip
            // verdict to the fresh anchor the pass installs. The stray contributes
            // no projection and never becomes a baseline row.
            ctx.session
                .rebuild_projection()
                .expect("the anchor-churned absorb defers the stray's verdict");
            assert!(
                !listing_contains(&ctx.cache_dir, stray_path),
                "an absorbed stray must never become a committed baseline row"
            );

            // Pass 2: the anchor is now stable — the stray meets the live tip
            // check, the case-variant claim diverges from the normalized tip, and
            // both the scoped deferral and the cold walk die on it.
            let second = expect_err("second-pass converge", ctx.session.rebuild_projection());
            assert_eq!(
                second.code(),
                "state_head_changed",
                "once the anchor is stable the divergent claim is re-judged and \
                 rejected: {second:?}"
            );
            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("the cold walk must reject the divergent claim"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "state_head_changed",
                "cold rejects the same claim on the same tip check: {cold:?}"
            );
        }

        // ---------- the state-domain decode-first ordering residual ----------
        //
        // `state()`'s first pass decodes every diffed head before judging any
        // stray — a malformed claim always outranks a divergent one on the
        // incremental arm. Cold `pins()` interleaves admit and tip-check per
        // file in listing order, so which of the two faults reports first
        // depends on enumeration order — `state_head_changed` when the divergent
        // file is met first, `invalid_revision_id` when the malformed one is.
        // Both arms stay fail-closed; the probe pins the incremental arm's
        // deterministic decode-layer verdict and cold's order-dependent one.
        #[test]
        fn a_decode_fault_and_a_divergent_claim_report_their_own_layer() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "order victim")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "order victim");
            pin_active(&ctx, &victim, "pin-order");

            // zz-aaa: well-formed but divergent claim (64 zeros parses, never
            // equals the live tip). zz-zzz: malformed claim.
            let divergent = head_record_bytes(
                LomoRecordKind::State,
                "head:zz-aaa",
                format!(
                    "{{\"memo_id\":\"{victim}\",\"head_revision_id\":\"{}\"}}",
                    "0".repeat(64)
                ),
            );
            fs::create_dir_all(ctx.workspace.join(".lomo/state/v2/heads"))
                .expect("state heads dir");
            fs::write(
                ctx.workspace.join(".lomo/state/v2/heads/zz-aaa.rec"),
                divergent,
            )
            .expect("plant divergent stray");
            let malformed = head_record_bytes(
                LomoRecordKind::State,
                "head:zz-zzz",
                format!("{{\"memo_id\":\"{victim}\",\"head_revision_id\":\"garbage\"}}"),
            );
            fs::write(
                ctx.workspace.join(".lomo/state/v2/heads/zz-zzz.rec"),
                malformed,
            )
            .expect("plant malformed stray");

            let incremental = expect_err("incremental arm", ctx.session.rebuild_projection());
            assert_eq!(
                incremental.code(),
                "invalid_revision_id",
                "the first pass decodes every diffed head before any absorb \
                 judgment — the decode fault always outranks: {incremental:?}"
            );
            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("two faulty strays must fail the cold walk"),
                Err(error) => error,
            };
            assert!(
                cold.code() == "state_head_changed" || cold.code() == "invalid_revision_id",
                "cold's first code is enumeration-order-dependent but must be one \
                 of the two fault layers, both fail-closed: {cold:?}"
            );
        }
    }

    // adversarial-reaudit round 12: the P12-F1 tombstone fix
    // (`audit/30-修复-tombstone祖先.md`) pierced the `history_scoped` closure walk
    // through tombstones, but its re-audit (`audit/31-终验-tombstone修复.md`)
    // pinned three splits whose single root was that the F7-1 stem↔claim axiom
    // bound only `heads/` — `objects/` and `tombstones/` keyed their maps by the
    // body's claim while every path a closure walk can name is a stem. The fix
    // round (`audit/32-修复-stem绑定补全.md`) extended the same naming authority
    // to both directories, and the formerly-divergent probes below now lock the
    // converged behavior:
    //
    // - `HistoryGraph::insert` proves `stem == claim` for objects and tombstones
    //   before a record joins a map (`history_object_path_mismatch` /
    //   `history_tombstone_path_mismatch`). The incremental gather mirrors the
    //   heads precedent: a stem-mismatched file is unclassifiable and defers to
    //   the full scan's identical `insert` verdict.
    // - With the binding in place, a successful object admission at
    //   `objects/<id>.rec` is keyed under `id` by construction — the walk's
    //   prune-blind `revisions.get(&id)` presence check was unreachable code and
    //   is deleted; `project_loaded` alone owns the missingness verdict.
    // - The declared residual pins (the severed tombstoned bridge, the
    //   path-shaped parent id, the generation check across a tombstoned edge,
    //   the same-id double-fault ordering) are unchanged and still asserted.

    #[cfg(test)]
    #[expect(
        clippy::expect_used,
        reason = "adversarial audit tests fail closed with panics on missing facts"
    )]
    mod tombstone_closure {
        use std::{
            fmt::Write as _,
            fs,
            path::{Path, PathBuf},
            sync::Arc,
        };

        use lomo_application::{
            MemoFilters, MemoQuery, MemoSort, MemoSummary, UpdateMemoRequest, WorkspaceSession,
            WorkspaceSessionConfig,
        };
        use lomo_core::{
            CapabilityToken, LomoError, OperationId, PageSize, PlatformActionExecutor,
            RelativeWorkspacePath,
        };
        use lomo_platform_fs::FsPlatformActionExecutor;
        use lomo_workspace::{
            LomoPayload, LomoRecordKind, MemoId, RevisionId, SourceFingerprint,
            WorkspaceGenerationId, WorkspaceRootId, decode_record, encode_record,
        };
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

        fn summaries(session: &WorkspaceSession, trash_only: bool) -> Vec<MemoSummary> {
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

        /// The committed `history_record_id` for `memo_id` at `revision` — the
        /// durable object identity `.lomo/history/v2/objects/<id>.rec` holds.
        fn history_record_at(cache_dir: &Path, memo_id: &str, revision: i64) -> String {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            conn.query_row(
                "SELECT history_record_id FROM revision_index WHERE memo_id=?1 \
                 AND revision=?2 LIMIT 1",
                rusqlite::params![memo_id, revision],
                |row| row.get(0),
            )
            .expect("a committed history record for the memo at that revision")
        }

        /// The memo's newest committed `history_record_id` — its live tip claim.
        fn history_tip_record(cache_dir: &Path, memo_id: &str) -> String {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            conn.query_row(
                "SELECT history_record_id FROM revision_index WHERE memo_id=?1 \
                 ORDER BY revision DESC LIMIT 1",
                [memo_id],
                |row| row.get(0),
            )
            .expect("a committed history tip for the memo")
        }

        /// The memo's committed `revision_index` record ids, revision-ordered —
        /// the exact row set a history replace leaves behind.
        fn revision_records(cache_dir: &Path, memo_id: &str) -> Vec<String> {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            let mut statement = conn
                .prepare(
                    "SELECT history_record_id FROM revision_index WHERE memo_id=?1 \
                     ORDER BY revision",
                )
                .expect("revision_index query");
            statement
                .query_map([memo_id], |row| row.get::<_, String>(0))
                .expect("revision_index rows")
                .collect::<Result<Vec<_>, _>>()
                .expect("revision_index collect")
        }

        /// Whether `path` has a committed `file_listing` row — once a poisoned path
        /// lands in the baseline it stops diffing and never re-arms an audit.
        fn listing_contains(cache_dir: &Path, path: &str) -> bool {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            conn.query_row(
                "SELECT COUNT(*) FROM file_listing WHERE path=?1",
                [path],
                |row| row.get::<_, i64>(0),
            )
            .expect("file_listing count")
                > 0
        }

        /// Edits a memo through the app path so a second durable history revision
        /// commits beneath the same head — rev1 becomes a non-tip ancestor.
        fn update_active(ctx: &Ctx, memo_id: &str, operation_id: &str, content: &str) {
            let snapshot = ctx
                .session
                .get_memo(&MemoId::parse(memo_id).expect("memo id"))
                .expect("get memo")
                .expect("memo view");
            ctx.session
                .update_memo(UpdateMemoRequest {
                    operation_id: OperationId::parse(operation_id).expect("operation id"),
                    memo_id: MemoId::parse(memo_id).expect("memo id"),
                    content: content.to_owned(),
                    expected_document_fingerprint: snapshot.file_fingerprint,
                    pending_promotes: Vec::new(),
                })
                .expect("update");
        }

        /// Absolute path of a memo's canonical v2 history head inside `workspace`.
        fn history_head_file(workspace: &Path, memo_id: &str) -> PathBuf {
            let paths = lomo_workspace::LomoPaths::for_workspace_with_layout(
                Path::new(""),
                lomo_workspace::LomoLayoutVersion::V2,
            );
            workspace.join(lomo_workspace::history_head_path(&paths, memo_id))
        }

        fn rel(path: &str) -> RelativeWorkspacePath {
            RelativeWorkspacePath::parse(path).expect("workspace-relative path")
        }

        fn head_record_bytes(
            kind: LomoRecordKind,
            envelope_id: &str,
            body_json: String,
        ) -> Vec<u8> {
            encode_record(&LomoPayload {
                kind,
                record_id: envelope_id.to_owned(),
                body_json,
            })
            .expect("record bytes")
        }

        /// A well-formed tombstone record: envelope and body agree on the honest
        /// writer shape; `claim` is the revision id the body names — nothing in the
        /// admit path binds it to the file's stem.
        fn tombstone_record_bytes(stem: &str, memo_id: &str, claim: &str) -> Vec<u8> {
            encode_record(&LomoPayload {
                kind: LomoRecordKind::HistoryTombstone,
                record_id: stem.to_owned(),
                body_json: format!(
                    "{{\"memo_id\":\"{memo_id}\",\"revision_id\":\"{claim}\",\
                     \"pruned_at_ms\":1757600000000}}"
                ),
            })
            .expect("tombstone record")
        }

        /// Reads the error out of a `Result`, panicking with `what` on `Ok`.
        fn expect_err(
            what: &str,
            result: Result<lomo_store::RebuildResult, LomoError>,
        ) -> LomoError {
            match result {
                Ok(done) => panic!("{what}: expected Err, got {done:?}"),
                Err(error) => error,
            }
        }

        /// One seeded workspace: `stayer`, `victim` (grows a chain), `zeta`.
        fn seeded_three_memo_ctx() -> (tempfile::TempDir, Ctx, String, String) {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[
                    ("10:00:00", "stayer"),
                    ("10:01:00", "probe victim"),
                    ("10:02:00", "probe zeta"),
                ],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "probe victim");
            let zeta = memo_by_body(&ctx, "probe zeta");
            (scratch, ctx, victim, zeta)
        }

        // ---------- F12-1: a foreign body at a pruned slot dies on its stem ----------
        //
        // The pierce-down walk once asserted `graph.revisions.get(&id)` after a
        // successful `insert` — a prune-blind presence check that fired
        // `history_parent_missing` before the node's own tombstone was read, while
        // the cold arm pruned the id without ever asking about its object. With
        // `insert` proving stem == claim, an admit under `objects/<id>.rec` is
        // keyed under `id` by construction: the get-miss case no longer exists,
        // the check is deleted, and the slot's foreign body dies at admission on
        // every arm — the tombstone's prune verdict never even gets a vote.
        #[test]
        fn a_pruned_slots_foreign_body_dies_on_its_stem_on_every_arm() {
            let (_scratch, ctx, victim, zeta) = seeded_three_memo_ctx();
            update_active(&ctx, &victim, "hist-p12a", "probe victim v2");
            ctx.session.rebuild_projection().expect("settle");
            let rev_one = history_record_at(&ctx.cache_dir, &victim, 1);
            let zeta_tip = history_tip_record(&ctx.cache_dir, &zeta);

            let object_rel = format!(".lomo/history/v2/objects/{rev_one}.rec");
            let tombstone_rel = format!(".lomo/history/v2/tombstones/{rev_one}.rec");
            let zeta_bytes = fs::read(
                ctx.workspace
                    .join(format!(".lomo/history/v2/objects/{zeta_tip}.rec")),
            )
            .expect("zeta object");
            fs::create_dir_all(ctx.workspace.join(".lomo/history/v2/tombstones"))
                .expect("tombstones dir");
            fs::write(
                ctx.workspace.join(&tombstone_rel),
                tombstone_record_bytes(&rev_one, &victim, &rev_one),
            )
            .expect("plant honest tombstone");
            // rev1's canonical slot now carries a byte-valid foreign body — the
            // record self-proves content and envelope (envelope zeta-tip == body
            // revision_id), so only the stem binding stands between it and the map.
            fs::write(ctx.workspace.join(&object_rel), zeta_bytes).expect("overwrite slot");

            let scoped = expect_err(
                "coverage-gated arm",
                ctx.session.reconcile_observed_paths(&[rel(&tombstone_rel)]),
            );
            assert_eq!(
                scoped.code(),
                "history_object_path_mismatch",
                "the re-walk admits the slot's bytes under stem authority — the \
                 foreign body dies on its own stem: {scoped:?}"
            );
            assert!(
                !listing_contains(&ctx.cache_dir, &tombstone_rel),
                "a failed reconcile commits nothing — the tombstone re-diffs next pass"
            );

            // The cold listing meets the identical stem↔claim binding in `insert`.
            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("the foreign body at rev1's slot must fail cold"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "history_object_path_mismatch",
                "cold reports the identical admission verdict: {cold:?}"
            );
        }

        // ---------- F12-3a: a foreign body at a live ancestor slot dies on its stem ----------
        //
        // The incremental `history()` phase once attributed a changed object file
        // through `decode_history_revision_record`'s `memo_id` alone — the body's
        // claim, never the path stem — so a foreign body at rev1's slot blamed
        // zeta, committed its `file_listing` row, and left victim's chain claiming
        // a slot the cold scan died on. The gather now proves `stem == claim`
        // exactly like the heads arm: a mismatched file is unclassifiable, so the
        // scope falls back to the full scan whose `insert` reports the identical
        // `history_object_path_mismatch` on the same bytes.
        #[test]
        fn a_live_ancestor_slot_carrying_a_foreign_body_dies_on_its_stem_on_every_arm() {
            let (_scratch, ctx, victim, zeta) = seeded_three_memo_ctx();
            update_active(&ctx, &victim, "hist-p12b", "probe victim v2");
            ctx.session.rebuild_projection().expect("settle");
            let rev_one = history_record_at(&ctx.cache_dir, &victim, 1);
            let rev_two = history_tip_record(&ctx.cache_dir, &victim);
            let zeta_tip = history_tip_record(&ctx.cache_dir, &zeta);

            let object_rel = format!(".lomo/history/v2/objects/{rev_one}.rec");
            let zeta_bytes = fs::read(
                ctx.workspace
                    .join(format!(".lomo/history/v2/objects/{zeta_tip}.rec")),
            )
            .expect("zeta object");
            fs::write(ctx.workspace.join(&object_rel), zeta_bytes).expect("overwrite slot");

            let scoped = expect_err(
                "coverage-gated arm",
                ctx.session.reconcile_observed_paths(&[rel(&object_rel)]),
            );
            assert_eq!(
                scoped.code(),
                "history_object_path_mismatch",
                "the stem-mismatched file is unclassifiable — the full scan's \
                 insert reports the cold verdict on the same bytes: {scoped:?}"
            );
            assert!(
                listing_contains(&ctx.cache_dir, &object_rel),
                "the honest baseline row survives — the poisoned bytes keep \
                 diffing and re-arm the check on every later pass"
            );
            assert_eq!(
                revision_records(&ctx.cache_dir, &victim),
                vec![rev_one, rev_two],
                "the failed pass commits nothing — victim's last committed rows \
                 stay exactly where they were"
            );

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("victim's chain must die on the foreign body at rev1"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "history_object_path_mismatch",
                "cold meets the identical admission verdict: {cold:?}"
            );
        }

        // ---------- F12-3c: a stray copy dies at admission instead of resurrecting ----------
        //
        // A byte-identical copy of rev1's object at `objects/zz-copy.rec` used to
        // insert under the copy's claimed key — inert while the canonical slot
        // lived, then resurrecting rev1 inside the cold keyed map once the slot
        // emptied. The stem binding kills the stray the first time any arm meets
        // it: the gather defers to the full scan's `insert`, which dies on
        // `history_object_path_mismatch`. rev1's absence afterwards stays
        // `history_parent_missing` inside the closure — the declared first-code
        // residual: both arms fail closed on the same durable bytes, the scoped
        // arm on the reachable gap and the cold arm on the unnameable stray.
        #[test]
        fn a_stray_copy_at_a_foreign_stem_dies_at_admit_and_never_resurrects() {
            let (_scratch, ctx, victim, _zeta) = seeded_three_memo_ctx();
            update_active(&ctx, &victim, "hist-p12c", "probe victim v2");
            ctx.session.rebuild_projection().expect("settle");
            let rev_one = history_record_at(&ctx.cache_dir, &victim, 1);

            let object_rel = format!(".lomo/history/v2/objects/{rev_one}.rec");
            let stray_rel = ".lomo/history/v2/objects/zz-copy.rec";
            let bytes = fs::read(ctx.workspace.join(&object_rel)).expect("rev1 object");
            fs::write(ctx.workspace.join(stray_rel), bytes).expect("plant stray copy");
            let scoped = expect_err(
                "coverage-gated arm",
                ctx.session.reconcile_observed_paths(&[rel(stray_rel)]),
            );
            assert_eq!(
                scoped.code(),
                "history_object_path_mismatch",
                "the stray's body claim cannot name its stem — it dies at \
                 admission: {scoped:?}"
            );
            assert!(
                !listing_contains(&ctx.cache_dir, stray_rel),
                "the stray copy never becomes a committed baseline row"
            );

            fs::remove_file(ctx.workspace.join(&object_rel)).expect("delete canonical object");
            let scoped = expect_err(
                "removal-driven re-walk",
                ctx.session.reconcile_observed_paths(&[]),
            );
            assert_eq!(
                scoped.code(),
                "history_parent_missing",
                "the walk names only canonical slots — the stray's bytes stay \
                 unreachable: {scoped:?}"
            );

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("the stray must die at admission, never resurrect rev1"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "history_object_path_mismatch",
                "cold dies on the stray itself — the keyed map can no longer \
                 answer rev1 present from a stem that never proved the id: {cold:?}"
            );
        }

        // ---------- F12-2: a foreign-stem tombstone dies on its stem ----------
        //
        // `insert`'s tombstone branch once keyed `graph.pruned` by the body's
        // claimed `revision_id` with no stem check: `tombstones/zz-mule.rec`
        // claiming the live tip pruned the whole chain on the cold listing while
        // no scoped re-walk could ever name the file — and the incremental arm
        // even committed it into the baseline. The stem↔claim binding makes the
        // file unclassifiable at the gather and fatal at `insert`: both arms now
        // answer the identical `history_tombstone_path_mismatch`.
        #[test]
        fn a_foreign_stem_tombstone_claiming_the_tip_dies_on_its_stem_on_every_arm() {
            let (_scratch, ctx, victim, _zeta) = seeded_three_memo_ctx();
            update_active(&ctx, &victim, "hist-p12d", "probe victim v2");
            ctx.session.rebuild_projection().expect("settle");
            let rev_one = history_record_at(&ctx.cache_dir, &victim, 1);
            let rev_two = history_tip_record(&ctx.cache_dir, &victim);

            let mule_rel = ".lomo/history/v2/tombstones/zz-mule.rec";
            fs::create_dir_all(ctx.workspace.join(".lomo/history/v2/tombstones"))
                .expect("tombstones dir");
            fs::write(
                ctx.workspace.join(mule_rel),
                // The stem is never claimable: hex revision ids can never equal it.
                tombstone_record_bytes("zz-mule", &victim, &rev_two),
            )
            .expect("plant foreign-stem tombstone");

            let scoped = expect_err(
                "coverage-gated arm",
                ctx.session.reconcile_observed_paths(&[rel(mule_rel)]),
            );
            assert_eq!(
                scoped.code(),
                "history_tombstone_path_mismatch",
                "a prune claim outside its stem is unclassifiable — the full \
                 scan's insert reports the cold verdict: {scoped:?}"
            );
            assert!(
                !listing_contains(&ctx.cache_dir, mule_rel),
                "the smuggled tombstone never becomes a committed baseline row"
            );
            assert_eq!(
                revision_records(&ctx.cache_dir, &victim),
                vec![rev_one, rev_two],
                "the failed pass leaves the committed projection untouched"
            );

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("the foreign-stem tombstone must die cold"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "history_tombstone_path_mismatch",
                "cold meets the identical stem↔claim binding in `insert`: {cold:?}"
            );
        }

        // ---------- F12-2 cross-memo: a closure stem claiming a foreign tip ----------
        //
        // The sharper variant: `tombstones/<rev1>.rec` sits at a stem the victim's
        // closure can name while its body claims zeta's tip. Attribution alone
        // could not save either direction — the claim pointed at zeta, whose
        // closure cannot name the file either. Under the binding the file is
        // unclassifiable no matter whose claim it carries: both arms die on
        // `history_tombstone_path_mismatch` and neither memo's committed rows move.
        #[test]
        fn a_closure_stem_tombstone_claiming_a_foreign_tip_dies_on_its_stem_on_every_arm() {
            let (_scratch, ctx, victim, zeta) = seeded_three_memo_ctx();
            update_active(&ctx, &victim, "hist-p12e", "probe victim v2");
            ctx.session.rebuild_projection().expect("settle");
            let rev_one = history_record_at(&ctx.cache_dir, &victim, 1);
            let rev_two = history_tip_record(&ctx.cache_dir, &victim);
            let zeta_tip = history_tip_record(&ctx.cache_dir, &zeta);

            let tombstone_rel = format!(".lomo/history/v2/tombstones/{rev_one}.rec");
            fs::create_dir_all(ctx.workspace.join(".lomo/history/v2/tombstones"))
                .expect("tombstones dir");
            fs::write(
                ctx.workspace.join(&tombstone_rel),
                tombstone_record_bytes(&rev_one, &zeta, &zeta_tip),
            )
            .expect("plant foreign-claim tombstone at a closure stem");

            let scoped = expect_err(
                "coverage-gated arm",
                ctx.session.reconcile_observed_paths(&[rel(&tombstone_rel)]),
            );
            assert_eq!(
                scoped.code(),
                "history_tombstone_path_mismatch",
                "the closure stem cannot launder a foreign claim: {scoped:?}"
            );
            assert_eq!(
                revision_records(&ctx.cache_dir, &victim),
                vec![rev_one, rev_two],
                "victim's committed rows are untouched by the failed pass"
            );
            assert_eq!(
                revision_records(&ctx.cache_dir, &zeta),
                vec![zeta_tip],
                "zeta's committed rows are untouched — the tombstone never prunes \
                 anything"
            );

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("the foreign-claim tombstone must die cold"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "history_tombstone_path_mismatch",
                "cold reports the identical admission verdict: {cold:?}"
            );
        }

        // ---------- F12-3b: a shadow object dies at admission on every arm ----------
        //
        // `generation` and `created_at_ms` sit outside `RevisionId::compute` — a
        // body claiming rev2's id with generation=99 is byte-valid. The keyed map
        // once let `objects/zz-shadow.rec` overwrite the canonical rev2 under the
        // sorted listing, surfacing later as `history_generation_invalid` on cold
        // only. The stem binding rejects the file before it ever reaches the map:
        // both arms now answer `history_object_path_mismatch` at admission and the
        // generation check never sees the shadow.
        #[test]
        fn a_shadow_object_at_a_foreign_stem_dies_at_admit_on_every_arm() {
            let (_scratch, ctx, victim, _zeta) = seeded_three_memo_ctx();
            update_active(&ctx, &victim, "hist-p12f1", "probe victim v2");
            update_active(&ctx, &victim, "hist-p12f2", "probe victim v3");
            ctx.session.rebuild_projection().expect("settle");
            let rev_two = history_record_at(&ctx.cache_dir, &victim, 2);
            let rev_three = history_tip_record(&ctx.cache_dir, &victim);

            let shadow_rel = ".lomo/history/v2/objects/zz-shadow.rec";
            let canonical = fs::read(
                ctx.workspace
                    .join(format!(".lomo/history/v2/objects/{rev_two}.rec")),
            )
            .expect("rev2 object");
            let record = decode_record(&canonical).expect("rev2 record");
            let mut body: serde_json::Value =
                serde_json::from_str(&record.payload.body_json).expect("rev2 body json");
            *body.get_mut("generation").expect("generation field") =
                serde_json::Value::from(99_u64);
            let shadow = encode_record(&LomoPayload {
                kind: LomoRecordKind::History,
                record_id: rev_two,
                body_json: serde_json::to_string(&body).expect("shadow body"),
            })
            .expect("shadow record");
            fs::write(ctx.workspace.join(shadow_rel), shadow).expect("plant shadow");

            let scoped = expect_err(
                "coverage-gated arm",
                ctx.session.reconcile_observed_paths(&[rel(shadow_rel)]),
            );
            assert_eq!(
                scoped.code(),
                "history_object_path_mismatch",
                "the shadow's claim cannot name its stem — it dies at admission: \
                 {scoped:?}"
            );
            assert!(
                !listing_contains(&ctx.cache_dir, shadow_rel),
                "the shadow never becomes a committed baseline row"
            );
            assert_eq!(
                revision_records(&ctx.cache_dir, &victim)
                    .last()
                    .map(String::as_str),
                Some(rev_three.as_str()),
                "the failed pass committed nothing — the honest chain keeps its \
                 last committed tip"
            );

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("the shadow must die at admission on the cold listing"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "history_object_path_mismatch",
                "cold rejects the shadow in `insert` — the keyed-map overwrite it \
                 relied on is gone: {cold:?}"
            );
        }

        // ---------- raw parent_ids materialize as paths inside the walk ----------
        //
        // `RevisionId` deserializes unchecked strings; `history_scoped` turns each
        // claimed parent into `objects/<raw>.rec` and hands it to
        // `RelativeWorkspacePath::parse`, which rejects `..` segments. The cold arm
        // never materializes parent names — it only looks them up in the keyed
        // map, where a path-shaped id is simply absent. Same crafted tip, both
        // arms fail closed, different first codes: `invalid_workspace_path` in the
        // walk vs `history_parent_missing` in the projection. Pinned as a
        // first-code instance of the declared ordering residual.
        #[test]
        fn a_path_shaped_parent_id_fails_closed_with_arm_specific_codes() {
            let (_scratch, ctx, victim, _zeta) = seeded_three_memo_ctx();
            update_active(&ctx, &victim, "hist-p12g", "probe victim v2");
            ctx.session.rebuild_projection().expect("settle");

            // A self-consistent crafted tip whose only parent name is a path
            // fragment — `RevisionId` deserialization keeps it raw.
            let raw_parent: RevisionId =
                serde_json::from_str("\"../x\"").expect("RevisionId keeps raw parent strings");
            let digest = SourceFingerprint::of_bytes(b"crafted").as_str().to_owned();
            let crafted = RevisionId::compute(&victim, &[raw_parent], &digest, "");
            let object_rel = format!(".lomo/history/v2/objects/{}.rec", crafted.as_str());
            let object = head_record_bytes(
                LomoRecordKind::History,
                crafted.as_str(),
                format!(
                    "{{\"revision_id\":\"{}\",\"memo_id\":\"{victim}\",\
                     \"parent_ids\":[\"../x\"],\"generation\":2,\
                     \"content_digest\":\"{digest}\",\"content\":\"crafted\",\
                     \"canonical_metadata\":\"\",\"created_at_ms\":1757600000000}}",
                    crafted.as_str()
                ),
            );
            fs::write(ctx.workspace.join(&object_rel), object).expect("plant crafted object");
            let head = head_record_bytes(
                LomoRecordKind::History,
                &format!("head:{victim}"),
                format!(
                    "{{\"memo_id\":\"{victim}\",\"head_revision_id\":\"{}\"}}",
                    crafted.as_str()
                ),
            );
            fs::write(history_head_file(&ctx.workspace, &victim), head)
                .expect("plant crafted head");

            let head_rel = format!(".lomo/history/v2/heads/{victim}.rec");
            let scoped = expect_err(
                "coverage-gated arm",
                ctx.session
                    .reconcile_observed_paths(&[rel(&head_rel), rel(&object_rel)]),
            );
            assert_eq!(
                scoped.code(),
                "invalid_workspace_path",
                "the walk materializes the parent claim as a path — `..` dies at \
                 the path layer: {scoped:?}"
            );

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("a path-shaped parent must fail the cold walk"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "history_parent_missing",
                "cold never materializes the name — the map lookup simply misses: \
                 {cold:?}"
            );
        }

        // ---------- symmetric pins: the declared residual and the phase parity ----------
        //
        // The pruned-but-present / pruned-and-absent distinction is the fix's own
        // contract: a tombstoned ancestor whose object file is gone owes no audit
        // on either arm. The scoped walk reads `None`, admits the tombstone, and
        // `project_loaded` prunes it — the cold scan does the identical dance from
        // its listing. Both arms must project the live tip alone.
        #[test]
        fn a_pruned_ancestor_missing_its_object_stays_silent_on_both_arms() {
            let (_scratch, ctx, victim, _zeta) = seeded_three_memo_ctx();
            update_active(&ctx, &victim, "hist-p12h", "probe victim v2");
            ctx.session.rebuild_projection().expect("settle");
            let rev_one = history_record_at(&ctx.cache_dir, &victim, 1);
            let rev_two = history_tip_record(&ctx.cache_dir, &victim);

            let object_rel = format!(".lomo/history/v2/objects/{rev_one}.rec");
            let tombstone_rel = format!(".lomo/history/v2/tombstones/{rev_one}.rec");
            fs::create_dir_all(ctx.workspace.join(".lomo/history/v2/tombstones"))
                .expect("tombstones dir");
            fs::write(
                ctx.workspace.join(&tombstone_rel),
                tombstone_record_bytes(&rev_one, &victim, &rev_one),
            )
            .expect("plant honest tombstone");
            fs::remove_file(ctx.workspace.join(&object_rel)).expect("delete pruned object");

            ctx.session
                .reconcile_observed_paths(&[rel(&tombstone_rel)])
                .expect("a tombstoned gap owes no audit — the prune excuses the absence");
            assert_eq!(
                revision_records(&ctx.cache_dir, &victim),
                vec![rev_two.clone()],
                "the incremental arm projects the live tip alone"
            );

            let oracle = fresh_oracle(&ctx.workspace).expect("cold agrees on the pruned gap");
            assert_eq!(
                revision_records(&oracle.cache_dir, &victim),
                vec![rev_two],
                "the cold projection answers identically"
            );
        }

        // A tombstoned parent still carries its generation into the child edge:
        // `project_loaded` judges `revisions.get(parent_id)` without asking the
        // prune set, so the tombstone never exempts the ordering proof. A crafted
        // low-generation child beneath the tombstoned tip must die
        // `history_generation_invalid` on every arm — the pierce-down admit is
        // what now makes the scoped side see the tombstoned parent's bytes at all.
        #[test]
        fn a_tombstoned_parent_still_faces_the_generation_check_on_both_arms() {
            let (_scratch, ctx, victim, _zeta) = seeded_three_memo_ctx();
            update_active(&ctx, &victim, "hist-p12i", "probe victim v2");
            ctx.session.rebuild_projection().expect("settle");
            let rev_two = history_tip_record(&ctx.cache_dir, &victim);

            // Crafted tip: generation 1 claiming the generation-2 tip as parent —
            // legal id arithmetic (parents are hashed, generation is not).
            let tip_id: RevisionId =
                serde_json::from_str(&format!("\"{rev_two}\"")).expect("real revision id");
            let digest = SourceFingerprint::of_bytes(b"low-gen child")
                .as_str()
                .to_owned();
            let crafted = RevisionId::compute(&victim, &[tip_id], &digest, "");
            let object_rel = format!(".lomo/history/v2/objects/{}.rec", crafted.as_str());
            let object = head_record_bytes(
                LomoRecordKind::History,
                crafted.as_str(),
                format!(
                    "{{\"revision_id\":\"{}\",\"memo_id\":\"{victim}\",\
                     \"parent_ids\":[\"{rev_two}\"],\"generation\":1,\
                     \"content_digest\":\"{digest}\",\"content\":\"low-gen child\",\
                     \"canonical_metadata\":\"\",\"created_at_ms\":1757600000000}}",
                    crafted.as_str()
                ),
            );
            fs::write(ctx.workspace.join(&object_rel), object).expect("plant crafted child");
            let head = head_record_bytes(
                LomoRecordKind::History,
                &format!("head:{victim}"),
                format!(
                    "{{\"memo_id\":\"{victim}\",\"head_revision_id\":\"{}\"}}",
                    crafted.as_str()
                ),
            );
            fs::write(history_head_file(&ctx.workspace, &victim), head)
                .expect("plant crafted head");
            let tombstone_rel = format!(".lomo/history/v2/tombstones/{rev_two}.rec");
            fs::create_dir_all(ctx.workspace.join(".lomo/history/v2/tombstones"))
                .expect("tombstones dir");
            fs::write(
                ctx.workspace.join(&tombstone_rel),
                tombstone_record_bytes(&rev_two, &victim, &rev_two),
            )
            .expect("tombstone the claimed parent");

            let head_rel = format!(".lomo/history/v2/heads/{victim}.rec");
            let scoped = expect_err(
                "coverage-gated arm",
                ctx.session.reconcile_observed_paths(&[
                    rel(&head_rel),
                    rel(&object_rel),
                    rel(&tombstone_rel),
                ]),
            );
            assert_eq!(
                scoped.code(),
                "history_generation_invalid",
                "the tombstoned parent's bytes still face the ordering proof: {scoped:?}"
            );
            let uncovered = expect_err("uncovered reconcile", ctx.session.rebuild_projection());
            assert_eq!(uncovered.code(), "history_generation_invalid");
            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("a generation inversion must fail cold"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "history_generation_invalid",
                "cold applies the identical check across the prune boundary: {cold:?}"
            );
        }

        // ---------- the declared severed-chain residual, instantiated ----------
        //
        // audit/30 §2: a tombstoned bridge whose object is deleted severs the
        // naming chain — deeper ancestors are unnameable to the closure walk, so a
        // corrupt byte beneath the bridge stays invisible to the coverage-gated
        // arm while every listing arm decodes it. Pinned as declared: scoped Ok,
        // ungated and cold Err on the same durable bytes — coverage semantics, not
        // a free-standing defect.
        #[test]
        fn a_corrupt_ancestor_below_a_deleted_tombstoned_bridge_stays_invisible_to_the_closure() {
            let (_scratch, ctx, victim, _zeta) = seeded_three_memo_ctx();
            update_active(&ctx, &victim, "hist-p12j1", "probe victim v2");
            update_active(&ctx, &victim, "hist-p12j2", "probe victim v3");
            ctx.session.rebuild_projection().expect("settle");
            let rev_one = history_record_at(&ctx.cache_dir, &victim, 1);
            let rev_two = history_record_at(&ctx.cache_dir, &victim, 2);
            let rev_three = history_tip_record(&ctx.cache_dir, &victim);

            let bridge_object = format!(".lomo/history/v2/objects/{rev_two}.rec");
            let bridge_tombstone = format!(".lomo/history/v2/tombstones/{rev_two}.rec");
            fs::create_dir_all(ctx.workspace.join(".lomo/history/v2/tombstones"))
                .expect("tombstones dir");
            fs::write(
                ctx.workspace.join(&bridge_tombstone),
                tombstone_record_bytes(&rev_two, &victim, &rev_two),
            )
            .expect("tombstone the bridge");
            fs::remove_file(ctx.workspace.join(&bridge_object)).expect("sever the bridge");
            fs::write(
                ctx.workspace
                    .join(format!(".lomo/history/v2/objects/{rev_one}.rec")),
                b"corrupted-object-bytes".as_slice(),
            )
            .expect("corrupt the unreachable ancestor");

            ctx.session
                .reconcile_observed_paths(&[rel(&bridge_tombstone)])
                .expect("the severed bridge excuses everything beneath it");
            assert_eq!(
                revision_records(&ctx.cache_dir, &victim),
                vec![rev_three],
                "the closure projects the live tip and cannot name the corrupt \
                 ancestor beneath the tombstoned bridge"
            );

            let uncovered = expect_err("uncovered reconcile", ctx.session.rebuild_projection());
            assert_eq!(
                uncovered.code(),
                "lomo_record_truncated",
                "the ungated listing decodes the corrupt ancestor: {uncovered:?}"
            );
            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("the corrupt ancestor must die on the cold listing"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "lomo_record_truncated",
                "cold reports the identical byte-verdict: {cold:?}"
            );
        }

        // ---------- admit ordering: the object layer reports first on every arm ----------
        //
        // When the same id's object AND tombstone are both garbage, the admit
        // order still converges: the incremental gather decodes `history_objects`
        // before `history_tombstones`, the scoped walk reads the object before the
        // tombstone, and the cold listing's lexical sort puts `objects/` before
        // `tombstones/`. All three arms must report the framing layer of the
        // object bytes — a same-id double fault that cannot produce a code split.
        #[test]
        fn the_same_id_double_fault_reports_the_object_layer_on_every_arm() {
            let (_scratch, ctx, victim, _zeta) = seeded_three_memo_ctx();
            update_active(&ctx, &victim, "hist-p12k", "probe victim v2");
            ctx.session.rebuild_projection().expect("settle");
            let rev_one = history_record_at(&ctx.cache_dir, &victim, 1);

            let object_rel = format!(".lomo/history/v2/objects/{rev_one}.rec");
            let tombstone_rel = format!(".lomo/history/v2/tombstones/{rev_one}.rec");
            fs::create_dir_all(ctx.workspace.join(".lomo/history/v2/tombstones"))
                .expect("tombstones dir");
            fs::write(
                ctx.workspace.join(&object_rel),
                b"corrupted-object-bytes".as_slice(),
            )
            .expect("corrupt the object");
            fs::write(
                ctx.workspace.join(&tombstone_rel),
                b"corrupted-tombstone-bytes".as_slice(),
            )
            .expect("corrupt the tombstone");

            let scoped = expect_err(
                "coverage-gated arm",
                ctx.session
                    .reconcile_observed_paths(&[rel(&object_rel), rel(&tombstone_rel)]),
            );
            assert_eq!(
                scoped.code(),
                "lomo_record_truncated",
                "the object file's framing fault outranks the tombstone's: {scoped:?}"
            );
            let uncovered = expect_err("uncovered reconcile", ctx.session.rebuild_projection());
            assert_eq!(
                uncovered.code(),
                "lomo_record_truncated",
                "the ungated gather decodes objects before tombstones: {uncovered:?}"
            );
            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("a same-id double fault must fail cold"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "lomo_record_truncated",
                "the sorted listing meets objects/ before tombstones/: {cold:?}"
            );
        }
    }

    // adversarial-reaudit round 13: the P13-F1 stem↔claim binding
    // (`audit/32-修复-stem绑定补全.md`) closed `history/v2/objects/` and
    // `history/v2/tombstones/` — `HistoryGraph::insert` now proves `stem == claim`
    // before keying either map, `history_scoped` discovery rides the admit
    // return value, and the incremental gather mirrors the heads precedent
    // (`claim != stem` -> unclassifiable -> the full scan's `insert` reports the
    // cold verdict). The probes below attack what that convergence still leaves
    // unbound plus the boundary edges of the new checks:
    //
    // - `state/v2/objects/<rev>.rec` is the remaining durable slot whose stem is
    //   never bound to its body's claim. `Gather::state()` attributes a changed
    //   state object by `decode_state_revision_owner`'s `memo_id` alone, so a
    //   foreign body copied onto a live tip slot re-walks the *claiming* memo and
    //   spares the *slot owner* — while cold `pins()` reaches the slot through
    //   `state_tip`'s canonical path and dies `record_identity_mismatch`. The
    //   `committed_pins` re-walk union is a partial net: it covers only owners
    //   with a committed `memo_pin` row, so the split shows exactly when the
    //   slot owner is unpinned. A second probe forges the opposite edge — a body
    //   claiming the slot's own stem but a foreign owner — which only the
    //   claimant's-head proof (not the stem check) sends to the full scan's
    //   `state_head_mismatch`.
    // - Filename-extraction edges of `strip_prefix`/`strip_suffix(".rec")` and
    //   `record_stem`: nested stems, double suffixes, case-variant extensions
    //   (`has_extension` is case-insensitive, `strip_suffix` is not), and
    //   extension-less dotfiles.
    // - First-code ordering: the byte layer must outrank the stem check on every
    //   arm, a kind-confused history head exposes the decode-order gap between
    //   `decode_history_head` (kind first) and `insert`'s envelope check, and
    //   `gather_facts` decodes state before history while the cold scan admits
    //   history before state.
    // - The tombstone envelope `record_id` is never checked on any arm — a
    //   consistent gap, pinned so a later drift cannot hide inside it.

    #[cfg(test)]
    #[expect(
        clippy::expect_used,
        reason = "adversarial audit tests fail closed with panics on missing facts"
    )]
    mod stem_claim_binding {
        use std::{
            fmt::Write as _,
            fs,
            path::{Path, PathBuf},
            sync::Arc,
        };

        use lomo_application::{
            MemoFilters, MemoQuery, MemoSort, MemoSummary, PinMemoRequest, PinPolicy,
            UpdateMemoRequest, WorkspaceSession, WorkspaceSessionConfig,
        };
        use lomo_core::{
            CapabilityToken, LomoError, OperationId, PageSize, PlatformActionExecutor,
            RelativeWorkspacePath,
        };
        use lomo_platform_fs::FsPlatformActionExecutor;
        use lomo_workspace::{
            LomoPayload, LomoRecordKind, MemoId, WorkspaceGenerationId, WorkspaceRootId,
            decode_record, encode_record,
        };
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

        fn summaries(session: &WorkspaceSession, trash_only: bool) -> Vec<MemoSummary> {
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

        /// The committed `history_record_id` for `memo_id` at `revision` — the
        /// durable object identity `.lomo/history/v2/objects/<id>.rec` holds.
        fn history_record_at(cache_dir: &Path, memo_id: &str, revision: i64) -> String {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            conn.query_row(
                "SELECT history_record_id FROM revision_index WHERE memo_id=?1 \
                 AND revision=?2 LIMIT 1",
                rusqlite::params![memo_id, revision],
                |row| row.get(0),
            )
            .expect("a committed history record for the memo at that revision")
        }

        /// The memo's newest committed `history_record_id` — its live tip claim.
        fn history_tip_record(cache_dir: &Path, memo_id: &str) -> String {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            conn.query_row(
                "SELECT history_record_id FROM revision_index WHERE memo_id=?1 \
                 ORDER BY revision DESC LIMIT 1",
                [memo_id],
                |row| row.get(0),
            )
            .expect("a committed history tip for the memo")
        }

        /// The memo's committed `revision_index` record ids, revision-ordered —
        /// the exact row set a history replace leaves behind.
        fn revision_records(cache_dir: &Path, memo_id: &str) -> Vec<String> {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            let mut statement = conn
                .prepare(
                    "SELECT history_record_id FROM revision_index WHERE memo_id=?1 \
                     ORDER BY revision",
                )
                .expect("revision_index query");
            statement
                .query_map([memo_id], |row| row.get::<_, String>(0))
                .expect("revision_index rows")
                .collect::<Result<Vec<_>, _>>()
                .expect("revision_index collect")
        }

        /// Whether `path` has a committed `file_listing` row — once a poisoned path
        /// lands in the baseline it stops diffing and never re-arms an audit.
        fn listing_contains(cache_dir: &Path, path: &str) -> bool {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            conn.query_row(
                "SELECT COUNT(*) FROM file_listing WHERE path=?1",
                [path],
                |row| row.get::<_, i64>(0),
            )
            .expect("file_listing count")
                > 0
        }

        /// Edits a memo through the app path so a second durable history revision
        /// commits beneath the same head — rev1 becomes a non-tip ancestor.
        fn update_active(ctx: &Ctx, memo_id: &str, operation_id: &str, content: &str) {
            let snapshot = ctx
                .session
                .get_memo(&MemoId::parse(memo_id).expect("memo id"))
                .expect("get memo")
                .expect("memo view");
            ctx.session
                .update_memo(UpdateMemoRequest {
                    operation_id: OperationId::parse(operation_id).expect("operation id"),
                    memo_id: MemoId::parse(memo_id).expect("memo id"),
                    content: content.to_owned(),
                    expected_document_fingerprint: snapshot.file_fingerprint,
                    pending_promotes: Vec::new(),
                })
                .expect("update");
        }

        /// Pins a memo through the app path so its canonical state head and tip
        /// object exist under `.lomo/state/v2/` and the `memo_pin` row commits.
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
        }

        /// Unpins a memo through the app path: the durable state head keeps
        /// anchoring a tip object while the committed `memo_pin` row is deleted —
        /// the exact durable shape that escapes `rewalk`'s committed-pin union.
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
        }

        /// Absolute path of a memo's canonical v2 state head inside `workspace`.
        fn state_head_file(workspace: &Path, memo_id: &str) -> PathBuf {
            let paths = lomo_workspace::LomoPaths::for_workspace_with_layout(
                Path::new(""),
                lomo_workspace::LomoLayoutVersion::V2,
            );
            workspace.join(lomo_workspace::state_head_path(&paths, memo_id))
        }

        /// Workspace-relative path of the tip object a memo's durable state head
        /// currently claims — `.lomo/state/v2/objects/<head_revision_id>.rec`.
        fn state_tip_object_rel(workspace: &Path, memo_id: &str) -> String {
            let head_bytes = fs::read(state_head_file(workspace, memo_id)).expect("state head");
            let record = decode_record(&head_bytes).expect("decode state head");
            let head: lomo_workspace::StateHead =
                serde_json::from_str(&record.payload.body_json).expect("state head json");
            format!(
                ".lomo/state/v2/objects/{}.rec",
                head.head_revision_id.as_str()
            )
        }

        /// Absolute path of a memo's canonical v2 history head inside `workspace`.
        fn history_head_file(workspace: &Path, memo_id: &str) -> PathBuf {
            let paths = lomo_workspace::LomoPaths::for_workspace_with_layout(
                Path::new(""),
                lomo_workspace::LomoLayoutVersion::V2,
            );
            workspace.join(lomo_workspace::history_head_path(&paths, memo_id))
        }

        fn rel(path: &str) -> RelativeWorkspacePath {
            RelativeWorkspacePath::parse(path).expect("workspace-relative path")
        }

        /// A record encoded with an envelope the caller chooses — used where a
        /// probe needs the envelope to disagree with the body or the slot.
        fn record_bytes(kind: LomoRecordKind, envelope_id: &str, body_json: String) -> Vec<u8> {
            encode_record(&LomoPayload {
                kind,
                record_id: envelope_id.to_owned(),
                body_json,
            })
            .expect("record bytes")
        }

        /// A well-formed tombstone record whose envelope `record_id` is caller-chosen:
        /// the honest writer stamps the stem there, but no admit path checks it.
        fn tombstone_bytes(envelope_id: &str, memo_id: &str, claim: &str) -> Vec<u8> {
            record_bytes(
                LomoRecordKind::HistoryTombstone,
                envelope_id,
                format!(
                    "{{\"memo_id\":\"{memo_id}\",\"revision_id\":\"{claim}\",\
                     \"pruned_at_ms\":1757600000000}}"
                ),
            )
        }

        /// Reads the error out of a `Result`, panicking with `what` on `Ok`.
        fn expect_err(
            what: &str,
            result: Result<lomo_store::RebuildResult, LomoError>,
        ) -> LomoError {
            match result {
                Ok(done) => panic!("{what}: expected Err, got {done:?}"),
                Err(error) => error,
            }
        }

        /// One seeded workspace: `stayer`, `victim` (grows a chain), `zeta`.
        fn seeded_three_memo_ctx() -> (tempfile::TempDir, Ctx, String, String) {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[
                    ("10:00:00", "stayer"),
                    ("10:01:00", "probe victim"),
                    ("10:02:00", "probe zeta"),
                ],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "probe victim");
            let zeta = memo_by_body(&ctx, "probe zeta");
            (scratch, ctx, victim, zeta)
        }

        // ---------- F13-1: a state object slot is never bound to its body's claim ----------
        //
        // `Gather::state()` decodes a changed `state/v2/objects/<rev>.rec` through
        // `decode_state_revision_owner` and attributes the re-walk by the body's
        // `memo_id` alone — the stem is classified but never compared against
        // `revision_id`. The cold scan never reads state objects standalone; it
        // reaches the slot only through `state_tip`'s canonical path, where
        // `decode_typed` proves `envelope record_id == head claim` and
        // `body.revision_id == head claim`. So a byte-valid foreign body on a live
        // tip slot splits the arms: incremental blames the claiming memo, re-walks
        // it, commits the poisoned listing row, and returns Ok; cold dies on the
        // slot. `rewalk`'s committed-pin union rescues only owners holding a
        // `memo_pin` row — an unpinned owner's verdict is never re-derived.
        #[test]
        fn a_foreign_body_on_an_unpinned_tip_slot_dies_cold_but_commits_incrementally() {
            let (_scratch, ctx, victim, zeta) = seeded_three_memo_ctx();
            pin_active(&ctx, &victim, "pin-victim-a");
            pin_active(&ctx, &zeta, "pin-zeta-a");
            // Unpin leaves the head -> tip edge durable while deleting the
            // `memo_pin` row — the only `rewalk` supply a state-object scope never
            // produces for the slot's true owner.
            unpin_active(&ctx, &victim, "unpin-victim-a");
            ctx.session.rebuild_projection().expect("settle");

            let victim_slot = state_tip_object_rel(&ctx.workspace, &victim);
            let zeta_slot = state_tip_object_rel(&ctx.workspace, &zeta);
            assert_ne!(victim_slot, zeta_slot, "fixture needs distinct tip ids");
            let zeta_bytes = fs::read(ctx.workspace.join(&zeta_slot)).expect("zeta state object");
            // zeta's real tip object carries envelope record_id=<zeta tip> and
            // body memo_id=zeta — a byte-valid record that self-proves a claim no
            // path in victim's scope can name.
            fs::write(ctx.workspace.join(&victim_slot), zeta_bytes)
                .expect("overwrite the victim tip slot");

            // The cold arm's verdict is asserted first so the oracle's behavior is
            // proven even while the incremental arms diverge.
            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("the foreign tip body must die on the cold listing"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "record_identity_mismatch",
                "cold binds the slot through `state_tip`'s envelope check: {cold:?}"
            );

            // Parity verdict: the gather must prove `stem == revision_id` (or that
            // the claimed memo's head points at this stem) before attributing; the
            // unprovable case falls back to the full scan whose `state_tip` dies
            // on the envelope — `record_identity_mismatch`.
            let scoped = ctx.session.reconcile_observed_paths(&[rel(&victim_slot)]);
            // A failed pass commits nothing: the slot keeps diffing, so every
            // later reconcile re-meets the same durable bytes — a committed poison
            // row instead digest-matches the baseline and certifies forever.
            let replay = ctx.session.rebuild_projection();
            assert!(
                scoped.is_err() && replay.is_err(),
                "the same durable bytes must die on every arm — coverage: \
                 {scoped:?}; ungated replay: {replay:?}"
            );
            let scoped = expect_err("coverage-gated arm", scoped);
            assert_eq!(
                scoped.code(),
                "record_identity_mismatch",
                "the slot owner's tip resolution must die on the foreign envelope: \
                 {scoped:?}"
            );
            let replay = expect_err("the slot re-diffs", replay);
            assert_eq!(
                replay.code(),
                "record_identity_mismatch",
                "an uncommitted poison row re-arms the identical verdict: {replay:?}"
            );
        }

        // The safety-net boundary the F13-1 divergence hinges on: while the slot
        // owner still holds a committed `memo_pin` row, `rewalk` merges it into
        // `state_memos` unconditionally, so `state_scoped(victim)` re-reads the
        // poisoned tip slot and dies on the same envelope the cold `pins()` walk
        // dies on. Both arms fail closed, one code.
        #[test]
        fn a_foreign_body_on_a_pinned_tip_slot_dies_on_every_arm() {
            let (_scratch, ctx, victim, zeta) = seeded_three_memo_ctx();
            pin_active(&ctx, &victim, "pin-victim-b");
            pin_active(&ctx, &zeta, "pin-zeta-b");
            ctx.session.rebuild_projection().expect("settle");

            let victim_slot = state_tip_object_rel(&ctx.workspace, &victim);
            let zeta_slot = state_tip_object_rel(&ctx.workspace, &zeta);
            let zeta_bytes = fs::read(ctx.workspace.join(&zeta_slot)).expect("zeta state object");
            fs::write(ctx.workspace.join(&victim_slot), zeta_bytes)
                .expect("overwrite the victim tip slot");

            let scoped = expect_err(
                "coverage-gated arm",
                ctx.session.reconcile_observed_paths(&[rel(&victim_slot)]),
            );
            assert_eq!(
                scoped.code(),
                "record_identity_mismatch",
                "the committed-pin re-walk confronts the foreign body at the \
                 claimed tip: {scoped:?}"
            );
            let replay = expect_err("ungated re-walk", ctx.session.rebuild_projection());
            assert_eq!(
                replay.code(),
                "record_identity_mismatch",
                "the committed `memo_pin` row re-arms the tip read every pass: \
                 {replay:?}"
            );

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("the foreign tip body must die on the cold listing"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "record_identity_mismatch",
                "cold reports the identical slot verdict: {cold:?}"
            );
        }

        // The F13-1 sibling a bare `stem == revision_id` check would leave open:
        // the body claims the slot's own stem but a foreign owner. Path naming is
        // satisfied while the claimed memo's head never pointed at this slot, so
        // body attribution would re-walk zeta and spare victim — and the cold
        // `pins()` walk dies on `revision.memo_id` inside `state_tip`. The
        // claimant's own head must claim the stem before attribution, so the slot
        // owner's verdict is re-derived on every arm: `state_head_mismatch`.
        #[test]
        fn a_forged_owner_claim_on_an_unpinned_tip_slot_dies_on_every_arm() {
            let (_scratch, ctx, victim, zeta) = seeded_three_memo_ctx();
            pin_active(&ctx, &victim, "pin-victim-forged");
            pin_active(&ctx, &zeta, "pin-zeta-forged");
            unpin_active(&ctx, &victim, "unpin-victim-forged");
            ctx.session.rebuild_projection().expect("settle");

            let victim_slot = state_tip_object_rel(&ctx.workspace, &victim);
            let victim_tip = victim_slot
                .strip_prefix(".lomo/state/v2/objects/")
                .and_then(|name| name.strip_suffix(".rec"))
                .expect("the tip slot path carries a stem");
            let real = decode_record(
                &fs::read(ctx.workspace.join(&victim_slot)).expect("victim tip object"),
            )
            .expect("decode victim tip");
            let mut forged: lomo_workspace::StateRevisionV2 =
                serde_json::from_str(&real.payload.body_json).expect("victim tip body");
            // The body keeps the slot's own revision stem — only the owner lies.
            forged.memo_id = zeta;
            fs::write(
                ctx.workspace.join(&victim_slot),
                record_bytes(
                    LomoRecordKind::State,
                    victim_tip,
                    serde_json::to_string(&forged).expect("forged body"),
                ),
            )
            .expect("overwrite the victim tip slot with the forged owner claim");

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("the forged owner claim must die on the cold listing"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "state_head_mismatch",
                "cold binds the slot through the claiming head's memo check: {cold:?}"
            );

            // The claimed owner's head points at a different tip, so the gather
            // cannot prove the slot owner inside the diff's naming authority and
            // defers to the full scan — whose `pins()` walk produces the cold
            // verdict on the identical bytes.
            let scoped = ctx.session.reconcile_observed_paths(&[rel(&victim_slot)]);
            let replay = ctx.session.rebuild_projection();
            assert!(
                scoped.is_err() && replay.is_err(),
                "an owner no claiming head names is foreign on every arm — \
                 coverage: {scoped:?}; ungated replay: {replay:?}"
            );
            let scoped = expect_err("coverage-gated arm", scoped);
            assert_eq!(
                scoped.code(),
                "state_head_mismatch",
                "the slot owner's tip resolution dies on the forged memo: {scoped:?}"
            );
            let replay = expect_err("the slot re-diffs", replay);
            assert_eq!(
                replay.code(),
                "state_head_mismatch",
                "an uncommitted forged row re-arms the identical verdict: {replay:?}"
            );
        }

        // ---------- stem-extraction boundaries ----------
        //
        // `record_stem` refuses a nested name while `insert`'s `strip_suffix`
        // keeps it — both paths converge on the same `insert` verdict because the
        // gather declares the file unclassifiable and the full scan judges the
        // stem `sub/<id>` against the body's claim.
        #[test]
        fn a_nested_object_path_dies_as_path_mismatch_on_every_arm() {
            let (_scratch, ctx, victim, _zeta) = seeded_three_memo_ctx();
            update_active(&ctx, &victim, "hist-p13a", "probe victim v2");
            ctx.session.rebuild_projection().expect("settle");
            let rev_one = history_record_at(&ctx.cache_dir, &victim, 1);
            let rev_two = history_tip_record(&ctx.cache_dir, &victim);

            let nested_rel = format!(".lomo/history/v2/objects/sub/{rev_one}.rec");
            let bytes = fs::read(
                ctx.workspace
                    .join(format!(".lomo/history/v2/objects/{rev_one}.rec")),
            )
            .expect("rev1 object");
            fs::create_dir_all(ctx.workspace.join(".lomo/history/v2/objects/sub"))
                .expect("nested objects dir");
            fs::write(ctx.workspace.join(&nested_rel), bytes).expect("plant nested copy");

            let scoped = expect_err(
                "coverage-gated arm",
                ctx.session.reconcile_observed_paths(&[rel(&nested_rel)]),
            );
            assert_eq!(
                scoped.code(),
                "history_object_path_mismatch",
                "a stem carrying a separator can never equal a revision id: {scoped:?}"
            );
            assert_eq!(
                revision_records(&ctx.cache_dir, &victim),
                vec![rev_one, rev_two],
                "the failed pass leaves the committed chain untouched"
            );

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("the nested slot must die cold"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "history_object_path_mismatch",
                "the listing's insert meets the identical stem judgment: {cold:?}"
            );
        }

        // `x.rec.rec` strips exactly one suffix on both extractors — the remaining
        // stem is not a revision id, so the file dies `history_object_path_mismatch`
        // everywhere rather than sneaking through a second extension layer.
        #[test]
        fn a_double_suffixed_object_dies_as_path_mismatch_on_every_arm() {
            let (_scratch, ctx, victim, _zeta) = seeded_three_memo_ctx();
            ctx.session.rebuild_projection().expect("settle");
            let rev_one = history_tip_record(&ctx.cache_dir, &victim);

            let double_rel = format!(".lomo/history/v2/objects/{rev_one}.rec.rec");
            let bytes = fs::read(
                ctx.workspace
                    .join(format!(".lomo/history/v2/objects/{rev_one}.rec")),
            )
            .expect("tip object");
            fs::write(ctx.workspace.join(&double_rel), bytes).expect("plant double-suffixed copy");

            let scoped = expect_err(
                "coverage-gated arm",
                ctx.session.reconcile_observed_paths(&[rel(&double_rel)]),
            );
            assert_eq!(
                scoped.code(),
                "history_object_path_mismatch",
                "the residual stem `<id>.rec` cannot equal the body's claim: {scoped:?}"
            );
            assert!(
                !listing_contains(&ctx.cache_dir, &double_rel),
                "the poisoned name never becomes a committed baseline row"
            );

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("the double-suffixed copy must die cold"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "history_object_path_mismatch",
                "cold applies the identical one-suffix strip: {cold:?}"
            );
        }

        // `has_extension` is case-insensitive while `strip_suffix(".rec")` is not:
        // an uppercase `.REC` file enters the history listing and dies
        // `unsupported_history_layout` — reachable evidence that the suffix check
        // fires ahead of every body judgment.
        #[test]
        fn an_uppercase_rec_object_dies_as_unsupported_layout_on_every_arm() {
            let (_scratch, ctx, victim, _zeta) = seeded_three_memo_ctx();
            ctx.session.rebuild_projection().expect("settle");
            let rev_one = history_tip_record(&ctx.cache_dir, &victim);

            let upper_rel = format!(".lomo/history/v2/objects/{rev_one}.REC");
            let bytes = fs::read(
                ctx.workspace
                    .join(format!(".lomo/history/v2/objects/{rev_one}.rec")),
            )
            .expect("tip object");
            fs::write(ctx.workspace.join(&upper_rel), bytes).expect("plant uppercase copy");

            let scoped = expect_err(
                "coverage-gated arm",
                ctx.session.reconcile_observed_paths(&[rel(&upper_rel)]),
            );
            assert_eq!(
                scoped.code(),
                "unsupported_history_layout",
                "a case-variant extension is outside the durable layout: {scoped:?}"
            );
            assert!(
                !listing_contains(&ctx.cache_dir, &upper_rel),
                "the case-variant file never becomes a committed baseline row"
            );

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("the uppercase suffix must die cold"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "unsupported_history_layout",
                "cold's case-insensitive listing hands the file to `insert`, which \
                 rejects it on the case-sensitive suffix: {cold:?}"
            );
        }

        // A dotfile named `.rec` carries no stem at all: the gather cannot classify
        // it (empty stem) and the cold listing never routes it to `insert`
        // (`Path::extension` is empty for leading-dot names), so it stays inert
        // evidence on every arm — committed to the baseline but never decoded.
        #[test]
        fn a_hidden_dotfile_object_is_inert_on_every_arm() {
            let (_scratch, ctx, victim, _zeta) = seeded_three_memo_ctx();
            ctx.session.rebuild_projection().expect("settle");
            let rev_one = history_tip_record(&ctx.cache_dir, &victim);

            let hidden_rel = ".lomo/history/v2/objects/.rec";
            let bytes = fs::read(
                ctx.workspace
                    .join(format!(".lomo/history/v2/objects/{rev_one}.rec")),
            )
            .expect("tip object");
            fs::write(ctx.workspace.join(hidden_rel), bytes).expect("plant dotfile");

            ctx.session
                .reconcile_observed_paths(&[rel(hidden_rel)])
                .expect("an unclassifiable dotfile falls back to a scan that ignores it");
            assert_eq!(
                revision_records(&ctx.cache_dir, &victim),
                vec![rev_one.clone()],
                "the inert file changes no committed history"
            );

            let oracle = fresh_oracle(&ctx.workspace).expect("cold ignores the dotfile");
            assert_eq!(
                revision_records(&oracle.cache_dir, &victim),
                vec![rev_one],
                "the cold projection answers identically"
            );
        }

        // The byte layer outranks the stem check on every arm: a corrupt body at a
        // foreign stem dies on its framing (`lomo_record_truncated`), never on
        // `history_object_path_mismatch` — `decode_record` precedes the stem
        // judgment inside `insert`, and the gather decodes before it compares.
        #[test]
        fn a_corrupt_body_at_a_foreign_stem_reports_the_byte_layer_on_every_arm() {
            let (_scratch, ctx, victim, _zeta) = seeded_three_memo_ctx();
            update_active(&ctx, &victim, "hist-p13b", "probe victim v2");
            ctx.session.rebuild_projection().expect("settle");
            let rev_one = history_record_at(&ctx.cache_dir, &victim, 1);
            let rev_two = history_tip_record(&ctx.cache_dir, &victim);

            let rot_rel = ".lomo/history/v2/objects/zz-rot.rec";
            fs::write(
                ctx.workspace.join(rot_rel),
                b"corrupted-object-bytes".as_slice(),
            )
            .expect("plant corrupt foreign-stem object");

            let scoped = expect_err(
                "coverage-gated arm",
                ctx.session.reconcile_observed_paths(&[rel(rot_rel)]),
            );
            assert_eq!(
                scoped.code(),
                "lomo_record_truncated",
                "the framing fault precedes the stem judgment: {scoped:?}"
            );
            assert_eq!(
                revision_records(&ctx.cache_dir, &victim),
                vec![rev_one, rev_two],
                "the failed pass leaves the committed chain untouched"
            );

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("the corrupt foreign-stem object must die cold"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "lomo_record_truncated",
                "cold dies at `decode_record` before ever judging the stem: {cold:?}"
            );
        }

        // ---------- first-code ordering residual instances ----------
        //
        // `insert`'s heads branch proves the envelope (`kind=History` and
        // `record_id=head:<stem>`) before decoding the body, so a kind-confused
        // head dies `record_identity_mismatch` on the listing arm. The gather's
        // `decode_history_head` is the path-blind half: `body()` judges the kind
        // first and reports `record_kind_mismatch`. Same durable file, two first
        // codes, both fail-closed — a new instance of the declared decode-order
        // residual (the memo_id/revision_id ordering gap reaudit11 already pins).
        #[test]
        fn a_kind_confused_history_head_reports_body_layer_scoped_and_envelope_layer_cold() {
            let (_scratch, ctx, victim, _zeta) = seeded_three_memo_ctx();
            ctx.session.rebuild_projection().expect("settle");
            let rev_one = history_tip_record(&ctx.cache_dir, &victim);

            let head_rel = format!(".lomo/history/v2/heads/{victim}.rec");
            // An honestly-framed HistoryTombstone record on the head slot: the
            // envelope even names `head:<victim>` — only the kind is foreign.
            let confused = tombstone_bytes(&format!("head:{victim}"), &victim, &rev_one);
            fs::write(history_head_file(&ctx.workspace, &victim), confused)
                .expect("kind-confuse the history head");

            let scoped = expect_err(
                "coverage-gated arm",
                ctx.session.reconcile_observed_paths(&[rel(&head_rel)]),
            );
            assert_eq!(
                scoped.code(),
                "record_kind_mismatch",
                "the path-blind decode judges the kind before the envelope: {scoped:?}"
            );
            let replay = expect_err("ungated reconcile", ctx.session.rebuild_projection());
            assert_eq!(
                replay.code(),
                "record_kind_mismatch",
                "the ungated gather hits the same decode order: {replay:?}"
            );

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("a kind-confused head must die cold"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "record_identity_mismatch",
                "the listing's insert proves the envelope first — kind included: \
                 {cold:?}"
            );
        }

        // `gather_facts` decodes state records before history records, while the
        // cold scan admits the whole sorted history listing before `pins()` ever
        // runs. Two faults in different domains therefore report different first
        // codes per arm — the cross-phase instance of the declared ordering
        // residual: both fail closed on the same durable set.
        #[test]
        fn cross_phase_faults_report_state_first_scoped_and_history_first_cold() {
            let (_scratch, ctx, victim, zeta) = seeded_three_memo_ctx();
            pin_active(&ctx, &zeta, "pin-zeta-c");
            update_active(&ctx, &victim, "hist-p13c", "probe victim v2");
            ctx.session.rebuild_projection().expect("settle");
            let rev_one = history_record_at(&ctx.cache_dir, &victim, 1);

            let object_rel = format!(".lomo/history/v2/objects/{rev_one}.rec");
            fs::write(
                ctx.workspace.join(&object_rel),
                b"corrupted-object-bytes".as_slice(),
            )
            .expect("corrupt the history object");
            let state_head_rel = format!(".lomo/state/v2/heads/{zeta}.rec");
            let confused = tombstone_bytes(&format!("head:{zeta}"), &zeta, &rev_one);
            fs::write(state_head_file(&ctx.workspace, &zeta), confused)
                .expect("kind-confuse the state head");

            let scoped = expect_err(
                "coverage-gated arm",
                ctx.session
                    .reconcile_observed_paths(&[rel(&object_rel), rel(&state_head_rel)]),
            );
            assert_eq!(
                scoped.code(),
                "record_kind_mismatch",
                "the gather decodes state heads before history objects: {scoped:?}"
            );

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("a compound fault must die cold"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "lomo_record_truncated",
                "the cold scan admits all of history/ before state/ — the corrupt \
                 object reports first: {cold:?}"
            );
        }

        // ---------- the consistent gap: tombstone envelopes are never bound ----------
        //
        // `insert`'s tombstone branch and `decode_history_tombstone` both skip the
        // envelope `record_id` — only `body.revision_id` is bound to the stem.
        // A tombstone at its true stem carrying a lying envelope is admitted and
        // prunes identically on every arm. Pinned as a *consistent* asymmetry:
        // heads and objects bind the envelope to the claim, tombstones do not —
        // harmless while every reader ignores the field, but a later consumer
        // trusting `record_id` on tombstones would inherit an unbound slot.
        #[test]
        fn an_envelope_lying_tombstone_at_its_true_stem_prunes_identically_on_every_arm() {
            let (_scratch, ctx, victim, _zeta) = seeded_three_memo_ctx();
            update_active(&ctx, &victim, "hist-p13d", "probe victim v2");
            ctx.session.rebuild_projection().expect("settle");
            let rev_one = history_record_at(&ctx.cache_dir, &victim, 1);
            let rev_two = history_tip_record(&ctx.cache_dir, &victim);

            let tombstone_rel = format!(".lomo/history/v2/tombstones/{rev_one}.rec");
            fs::create_dir_all(ctx.workspace.join(".lomo/history/v2/tombstones"))
                .expect("tombstones dir");
            fs::write(
                ctx.workspace.join(&tombstone_rel),
                // The stem and the body claim agree; only the envelope lies.
                tombstone_bytes("bogus-envelope", &victim, &rev_one),
            )
            .expect("plant envelope-lying tombstone");

            ctx.session
                .reconcile_observed_paths(&[rel(&tombstone_rel)])
                .expect("the stem-true tombstone is admitted — the envelope is unchecked");
            assert_eq!(
                revision_records(&ctx.cache_dir, &victim),
                vec![rev_two.clone()],
                "the prune lands identically on the scoped arm"
            );

            let oracle = fresh_oracle(&ctx.workspace).expect("cold prunes identically");
            assert_eq!(
                revision_records(&oracle.cache_dir, &victim),
                vec![rev_two],
                "the cold projection answers the same pruned chain"
            );
        }
    }

    // adversarial-reaudit round 14: the P14-F1 state-object binding fix
    // (`audit/34-修复-state_objects绑定.md`) taught `Gather::state()` a two-level
    // naming authority for `state/v2/objects/<stem>.rec`: the body's claimed
    // `revision_id` must equal the stem, and the claimed memo's canonical head —
    // admitted through the shared `admitted_state_head` predicate — must claim
    // that same stem. `decode_state_revision_owner` grew `RevisionId::parse` and
    // the envelope `record_id` check, and `state_scoped`'s head admission was
    // extracted into the shared helper. The probes below attack what that
    // convergence still leaves open:
    //
    // - The claimant-head proof judges the object against a head the *same pass*
    //   is still admitting. Nothing binds a stem to a single claimant, so a
    //   mutually-forged pair — `heads/<zeta>.rec` claiming victim's tip stem and
    //   `objects/<victim_tip>.rec` claiming zeta — satisfies both checks, gets
    //   re-walked as zeta's self-consistent tip, and commits while the slot's
    //   honest head (victim's, never diffed, never re-walked for an unpinned
    //   owner) kills the cold scan on `state_head_mismatch`. The fix report's
    //   provability induction — "a foreign head claiming the stem can never
    //   commit" — holds only because the claimant's head is presumed durable;
    //   a diffed head is durable-but-unjudged until `state_scoped` re-walks it,
    //   and by then the object has already been attributed to it.
    // - The `state_head_tip_claim` boundaries: no head (None), a foreign body on
    //   the claimed head slot (None via `admitted_state_head`), a head claiming
    //   another tip (Some(other) → fallback), and a corrupt head (Err →
    //   propagate — the declared first-code-ordering residual).
    // - `decode_state_revision_owner`'s new admit layers: a lying envelope and
    //   an unparseable `revision_id` die inside the decode, so the gather defers
    //   to the full scan and the cold slot walk supplies the verdict.
    // - The lone-forgery complement: a head claiming a foreign tip without its
    //   co-forged object can never commit — `state_scoped` judges it against the
    //   honest object — and the committed-pin union rescues even the mutual
    //   forgery while the slot owner stays pinned.
    // - The declared intentional change: a non-tip object diff now falls back
    //   to the full scan, which must settle the row as inert with a
    //   byte-identical projection.

    #[cfg(test)]
    #[expect(
        clippy::expect_used,
        reason = "adversarial audit tests fail closed with panics on missing facts"
    )]
    mod state_object_binding {
        use std::{
            fmt::Write as _,
            fs,
            path::{Path, PathBuf},
            sync::Arc,
        };

        use lomo_application::{
            MemoFilters, MemoQuery, MemoSort, MemoSummary, PinMemoRequest, PinPolicy,
            WorkspaceSession, WorkspaceSessionConfig,
        };
        use lomo_core::{
            CapabilityToken, LomoError, OperationId, PageSize, PlatformActionExecutor,
            RelativeWorkspacePath,
        };
        use lomo_platform_fs::FsPlatformActionExecutor;
        use lomo_workspace::{
            LomoPayload, LomoRecordKind, MemoId, WorkspaceGenerationId, WorkspaceRootId,
            decode_record, encode_record,
        };
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

        fn summaries(session: &WorkspaceSession, trash_only: bool) -> Vec<MemoSummary> {
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

        /// Whether `path` has a committed `file_listing` row — once a poisoned path
        /// lands in the baseline it stops diffing and never re-arms an audit.
        fn listing_contains(cache_dir: &Path, path: &str) -> bool {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            conn.query_row(
                "SELECT COUNT(*) FROM file_listing WHERE path=?1",
                [path],
                |row| row.get::<_, i64>(0),
            )
            .expect("file_listing count")
                > 0
        }

        /// The committed `memo_pin` timestamp for `memo_id`, if a row survives —
        /// the exact verdict a forged tip can overwrite once `state_scoped`
        /// accepts a mutually-consistent lie.
        fn pin_timestamp_for(cache_dir: &Path, memo_id: &str) -> Option<i64> {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            conn.query_row(
                "SELECT pinned_at_ms FROM memo_pin WHERE memo_id=?1",
                [memo_id],
                |row| row.get(0),
            )
            .optional()
            .expect("memo_pin query")
        }

        /// Pins a memo through the app path so its canonical state head and tip
        /// object exist under `.lomo/state/v2/` and the `memo_pin` row commits.
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
        }

        /// Unpins a memo through the app path: the durable state head keeps
        /// anchoring a tip object while the committed `memo_pin` row is deleted —
        /// the exact durable shape that escapes `rewalk`'s committed-pin union.
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
        }

        /// Absolute path of a memo's canonical v2 state head inside `workspace`.
        fn state_head_file(workspace: &Path, memo_id: &str) -> PathBuf {
            let paths = lomo_workspace::LomoPaths::for_workspace_with_layout(
                Path::new(""),
                lomo_workspace::LomoLayoutVersion::V2,
            );
            workspace.join(lomo_workspace::state_head_path(&paths, memo_id))
        }

        /// Workspace-relative path of the tip object a memo's durable state head
        /// currently claims — `.lomo/state/v2/objects/<head_revision_id>.rec`.
        fn state_tip_object_rel(workspace: &Path, memo_id: &str) -> String {
            let head_bytes = fs::read(state_head_file(workspace, memo_id)).expect("state head");
            let record = decode_record(&head_bytes).expect("decode state head");
            let head: lomo_workspace::StateHead =
                serde_json::from_str(&record.payload.body_json).expect("state head json");
            format!(
                ".lomo/state/v2/objects/{}.rec",
                head.head_revision_id.as_str()
            )
        }

        /// The stem `.lomo/state/v2/objects/<stem>.rec` names — the slot identity
        /// every arm's naming authority binds the body's `revision_id` to.
        fn state_object_stem(slot_rel: &str) -> &str {
            slot_rel
                .strip_prefix(".lomo/state/v2/objects/")
                .and_then(|name| name.strip_suffix(".rec"))
                .expect("the tip slot path carries a stem")
        }

        fn rel(path: &str) -> RelativeWorkspacePath {
            RelativeWorkspacePath::parse(path).expect("workspace-relative path")
        }

        /// A record encoded with an envelope the caller chooses — used where a
        /// probe needs the envelope to disagree with the body or the slot.
        fn record_bytes(kind: LomoRecordKind, envelope_id: &str, body_json: String) -> Vec<u8> {
            encode_record(&LomoPayload {
                kind,
                record_id: envelope_id.to_owned(),
                body_json,
            })
            .expect("record bytes")
        }

        /// Reads the error out of a `Result`, panicking with `what` on `Ok`.
        fn expect_err(
            what: &str,
            result: Result<lomo_store::RebuildResult, LomoError>,
        ) -> LomoError {
            match result {
                Ok(done) => panic!("{what}: expected Err, got {done:?}"),
                Err(error) => error,
            }
        }

        /// One seeded workspace: `stayer`, `victim` (grows a chain), `zeta`.
        fn seeded_three_memo_ctx() -> (tempfile::TempDir, Ctx, String, String) {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[
                    ("10:00:00", "stayer"),
                    ("10:01:00", "probe victim"),
                    ("10:02:00", "probe zeta"),
                ],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "probe victim");
            let zeta = memo_by_body(&ctx, "probe zeta");
            (scratch, ctx, victim, zeta)
        }

        /// Decodes the durable state revision body sitting at `slot_rel`.
        fn state_revision_at(workspace: &Path, slot_rel: &str) -> lomo_workspace::StateRevisionV2 {
            let record = decode_record(&fs::read(workspace.join(slot_rel)).expect("state object"))
                .expect("record");
            serde_json::from_str(&record.payload.body_json).expect("state revision json")
        }

        /// Writes a `StateHead` claiming `tip` for `owner` at `owner`'s canonical
        /// head slot — envelope `head:<owner>` honest about the slot it occupies.
        fn forge_head_claiming(workspace: &Path, owner: &str, tip: &str) {
            let body = format!("{{\"memo_id\":\"{owner}\",\"head_revision_id\":\"{tip}\"}}");
            fs::write(
                state_head_file(workspace, owner),
                record_bytes(LomoRecordKind::State, &format!("head:{owner}"), body),
            )
            .expect("forge the head");
        }

        // ---------- the claimant-head proof's mutual-forgery hole ----------
        //
        // `Gather::state()` proves "the claimed memo's head claims this stem" by
        // reading the live head file — a file the same pass is still admitting.
        // Forge the claimant's head to point at victim's tip stem and forge the
        // object to claim zeta: stem == revision_id, tip_claim(zeta) == stem, so
        // attribution lands on zeta and `state_scoped(zeta)` re-walks a
        // self-consistent lie. Victim — unpinned, never diffed — is never
        // re-walked: the committed-pin union cannot reach it. The cold `pins()`
        // walk still reads victim's honest head against the same slot and dies
        // `state_head_mismatch`. Same durable bytes, inc-Ok/cold-Err — the F13-1
        // class through the new check's own proof channel.
        #[test]
        fn a_co_forged_claimant_head_and_slot_body_commit_incrementally_but_die_cold() {
            let (_scratch, ctx, victim, zeta) = seeded_three_memo_ctx();
            pin_active(&ctx, &victim, "pin-victim-mutual");
            pin_active(&ctx, &zeta, "pin-zeta-mutual");
            unpin_active(&ctx, &victim, "unpin-victim-mutual");
            ctx.session.rebuild_projection().expect("settle");

            let victim_slot = state_tip_object_rel(&ctx.workspace, &victim);
            let victim_tip = state_object_stem(&victim_slot).to_owned();
            // The forged object keeps the slot's own revision stem but claims
            // zeta — the content-address binding `RevisionId::compute` wrote at
            // create time is never re-verified on any read arm.
            let mut forged = state_revision_at(&ctx.workspace, &victim_slot);
            forged.memo_id = zeta.clone();
            forged.pinned = true;
            forged.pinned_at_ms = Some(1_777_777_777_000);
            fs::write(
                ctx.workspace.join(&victim_slot),
                record_bytes(
                    LomoRecordKind::State,
                    &victim_tip,
                    serde_json::to_string(&forged).expect("forged object"),
                ),
            )
            .expect("forge the slot body");
            // The co-forged claimant head: zeta's canonical slot claims victim's
            // tip stem — the only premise `state_head_tip_claim` checks.
            forge_head_claiming(&ctx.workspace, &zeta, &victim_tip);
            let head_rel = format!(".lomo/state/v2/heads/{zeta}.rec");

            // The cold arm's verdict is asserted first so the oracle's behavior is
            // proven even while the incremental arms diverge.
            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("the mutually-forged pair must die on the cold listing"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "state_head_mismatch",
                "victim's honest head still claims the stem the forged body gives \
                 to zeta: {cold:?}"
            );

            // Parity verdict: attribution must re-walk the slot's true owner, not
            // just the body's claimant — the unprovable case falls back to the
            // full scan whose `pins()` walk produces the cold verdict.
            let scoped = ctx
                .session
                .reconcile_observed_paths(&[rel(&victim_slot), rel(&head_rel)]);
            let replay = ctx.session.rebuild_projection();
            assert!(
                scoped.is_err() && replay.is_err(),
                "a mutually-forged head+object pair must die on every arm — \
                 coverage: {scoped:?}; ungated replay: {replay:?}; poisoned \
                 listing rows committed: head={} object={}; forged pin verdict \
                 committed for zeta: {:?}",
                listing_contains(&ctx.cache_dir, &head_rel),
                listing_contains(&ctx.cache_dir, &victim_slot),
                pin_timestamp_for(&ctx.cache_dir, &zeta),
            );
            let scoped = expect_err("coverage-gated arm", scoped);
            assert_eq!(
                scoped.code(),
                "state_head_mismatch",
                "the slot owner's tip resolution dies on the forged memo: {scoped:?}"
            );
            let replay = expect_err("the pair re-diffs", replay);
            assert_eq!(
                replay.code(),
                "state_head_mismatch",
                "an uncommitted forged pair re-arms the identical verdict: {replay:?}"
            );
        }

        // The coverage gate is no defense either: the forged head needs no slot
        // in the same diff — `state_head_tip_claim` reads it live regardless of
        // what the observed set covered. Watching only the object still commits
        // the poisoned slot row in pass one; the next ungated pass re-diffs the
        // forged head, anchors it under its own stem, and commits that row too.
        // Two passes, never a joint judgment, cold still dies.
        #[test]
        fn a_co_forged_pair_infects_the_baseline_across_two_passes() {
            let (_scratch, ctx, victim, zeta) = seeded_three_memo_ctx();
            pin_active(&ctx, &victim, "pin-victim-split");
            pin_active(&ctx, &zeta, "pin-zeta-split");
            unpin_active(&ctx, &victim, "unpin-victim-split");
            ctx.session.rebuild_projection().expect("settle");

            let victim_slot = state_tip_object_rel(&ctx.workspace, &victim);
            let victim_tip = state_object_stem(&victim_slot).to_owned();
            let mut forged = state_revision_at(&ctx.workspace, &victim_slot);
            forged.memo_id = zeta.clone();
            fs::write(
                ctx.workspace.join(&victim_slot),
                record_bytes(
                    LomoRecordKind::State,
                    &victim_tip,
                    serde_json::to_string(&forged).expect("forged object"),
                ),
            )
            .expect("forge the slot body");
            forge_head_claiming(&ctx.workspace, &zeta, &victim_tip);
            let head_rel = format!(".lomo/state/v2/heads/{zeta}.rec");

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("the mutually-forged pair must die on the cold listing"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "state_head_mismatch",
                "victim's honest head kills the same bytes cold: {cold:?}"
            );

            // Pass one: only the object is observed — `tip_claim` still reads the
            // live forged head and attributes the slot to zeta.
            let first = ctx.session.reconcile_observed_paths(&[rel(&victim_slot)]);
            // Pass two (ungated): the forged head re-diffs, anchors under its own
            // stem, and its baseline row commits — the pair was never judged
            // jointly on this arm.
            let second = ctx.session.rebuild_projection();
            assert!(
                first.is_err() && second.is_err(),
                "the forged pair must die even when only the object is watched — \
                 pass one: {first:?}; pass two: {second:?}; committed poison \
                 rows: head={} object={}",
                listing_contains(&ctx.cache_dir, &head_rel),
                listing_contains(&ctx.cache_dir, &victim_slot),
            );
            let first = expect_err("object-only coverage", first);
            assert_eq!(
                first.code(),
                "state_head_mismatch",
                "the slot owner must be re-walked, not just the claimant: {first:?}"
            );
            let second = expect_err("head re-diff pass", second);
            assert_eq!(
                second.code(),
                "state_head_mismatch",
                "the forged head must die on its own re-walk: {second:?}"
            );
        }

        // The red-zone boundary of the mutual forgery: while the slot owner holds
        // a committed `memo_pin` row, `rewalk`'s union pulls it into `state_memos`
        // unconditionally — `state_scoped(victim)` re-reads the slot and dies on
        // the forged body regardless of the claimant's co-forged head.
        #[test]
        fn a_co_forged_pair_on_a_pinned_victim_dies_on_every_arm() {
            let (_scratch, ctx, victim, zeta) = seeded_three_memo_ctx();
            pin_active(&ctx, &victim, "pin-victim-mutual-p");
            pin_active(&ctx, &zeta, "pin-zeta-mutual-p");
            ctx.session.rebuild_projection().expect("settle");

            let victim_slot = state_tip_object_rel(&ctx.workspace, &victim);
            let victim_tip = state_object_stem(&victim_slot).to_owned();
            let mut forged = state_revision_at(&ctx.workspace, &victim_slot);
            forged.memo_id = zeta.clone();
            fs::write(
                ctx.workspace.join(&victim_slot),
                record_bytes(
                    LomoRecordKind::State,
                    &victim_tip,
                    serde_json::to_string(&forged).expect("forged object"),
                ),
            )
            .expect("forge the slot body");
            forge_head_claiming(&ctx.workspace, &zeta, &victim_tip);
            let head_rel = format!(".lomo/state/v2/heads/{zeta}.rec");

            let scoped = expect_err(
                "coverage-gated arm",
                ctx.session
                    .reconcile_observed_paths(&[rel(&victim_slot), rel(&head_rel)]),
            );
            assert_eq!(
                scoped.code(),
                "state_head_mismatch",
                "the committed-pin union confronts the forged body at the claimed \
                 tip: {scoped:?}"
            );
            let replay = expect_err("ungated re-walk", ctx.session.rebuild_projection());
            assert_eq!(
                replay.code(),
                "state_head_mismatch",
                "the committed `memo_pin` row re-arms the tip read every pass: {replay:?}"
            );

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("the forged pair must die cold"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "state_head_mismatch",
                "cold reports the identical slot verdict: {cold:?}"
            );
        }

        // The complementary edge a co-forged pair exploits: a lone forged head
        // claiming a foreign tip can never commit — `state_scoped` judges it
        // against the honest object still on the slot, and the failed pass leaves
        // the head uncommitted so every later pass re-arms the verdict.
        #[test]
        fn a_lone_forged_head_claiming_a_foreign_tip_dies_on_every_arm() {
            let (_scratch, ctx, victim, zeta) = seeded_three_memo_ctx();
            pin_active(&ctx, &victim, "pin-victim-lone");
            pin_active(&ctx, &zeta, "pin-zeta-lone");
            unpin_active(&ctx, &victim, "unpin-victim-lone");
            ctx.session.rebuild_projection().expect("settle");

            let victim_slot = state_tip_object_rel(&ctx.workspace, &victim);
            let victim_tip = state_object_stem(&victim_slot).to_owned();
            forge_head_claiming(&ctx.workspace, &zeta, &victim_tip);
            let head_rel = format!(".lomo/state/v2/heads/{zeta}.rec");

            let scoped = expect_err(
                "coverage-gated arm",
                ctx.session.reconcile_observed_paths(&[rel(&head_rel)]),
            );
            assert_eq!(
                scoped.code(),
                "state_head_mismatch",
                "the re-walk judges the forged claim against the honest object: \
                 {scoped:?}"
            );
            let replay = expect_err("ungated replay", ctx.session.rebuild_projection());
            assert_eq!(
                replay.code(),
                "state_head_mismatch",
                "the uncommitted head re-diffs and re-arms the verdict: {replay:?}"
            );

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("the lone forged head must die cold"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "state_head_mismatch",
                "zeta's tip walk dies on the honest object's owner: {cold:?}"
            );
        }

        // ---------- `state_head_tip_claim` boundaries ----------
        //
        // `admitted_state_head` answers `None` both for an absent head and for a
        // head whose body names another identity. A body claiming a memo that has
        // no head at all cannot prove the slot owner inside the diff, so the
        // gather defers and the cold walk of the honest head dies on the same
        // `state_head_mismatch` on every arm.
        #[test]
        fn a_body_claiming_a_headless_memo_dies_on_every_arm() {
            let (_scratch, ctx, victim, _zeta) = seeded_three_memo_ctx();
            pin_active(&ctx, &victim, "pin-victim-ghost");
            unpin_active(&ctx, &victim, "unpin-victim-ghost");
            ctx.session.rebuild_projection().expect("settle");

            let victim_slot = state_tip_object_rel(&ctx.workspace, &victim);
            let victim_tip = state_object_stem(&victim_slot).to_owned();
            let mut forged = state_revision_at(&ctx.workspace, &victim_slot);
            // A parseable memo id that never owned a durable head.
            forged.memo_id = "zz-ghost-owner".to_owned();
            fs::write(
                ctx.workspace.join(&victim_slot),
                record_bytes(
                    LomoRecordKind::State,
                    &victim_tip,
                    serde_json::to_string(&forged).expect("forged object"),
                ),
            )
            .expect("forge the slot body");

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("a headless claimant must die cold"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "state_head_mismatch",
                "victim's head meets the ghost claim at its tip slot: {cold:?}"
            );

            let scoped = expect_err(
                "coverage-gated arm",
                ctx.session.reconcile_observed_paths(&[rel(&victim_slot)]),
            );
            assert_eq!(
                scoped.code(),
                "state_head_mismatch",
                "no head claims the stem for the body's owner — the full scan \
                 produces the cold verdict: {scoped:?}"
            );
            let replay = expect_err("ungated replay", ctx.session.rebuild_projection());
            assert_eq!(
                replay.code(),
                "state_head_mismatch",
                "the uncommitted forged row re-diffs forever: {replay:?}"
            );
        }

        // A foreign body on the claimed memo's head slot is `admitted_state_head`'s
        // `None` — never zeta's anchor. The stray also rides the absorb path: its
        // `state_tip` check re-reads the live canonical slot, meets the forged
        // object, and dies before any listing row can commit it.
        #[test]
        fn a_stray_head_on_the_claimed_slot_cannot_anchor_a_foreign_body() {
            let (_scratch, ctx, victim, zeta) = seeded_three_memo_ctx();
            pin_active(&ctx, &victim, "pin-victim-stray");
            pin_active(&ctx, &zeta, "pin-zeta-stray");
            unpin_active(&ctx, &victim, "unpin-victim-stray");
            ctx.session.rebuild_projection().expect("settle");

            let victim_slot = state_tip_object_rel(&ctx.workspace, &victim);
            let victim_tip = state_object_stem(&victim_slot).to_owned();
            let mut forged = state_revision_at(&ctx.workspace, &victim_slot);
            forged.memo_id = zeta.clone();
            fs::write(
                ctx.workspace.join(&victim_slot),
                record_bytes(
                    LomoRecordKind::State,
                    &victim_tip,
                    serde_json::to_string(&forged).expect("forged object"),
                ),
            )
            .expect("forge the slot body");
            // Victim's honest head file copied onto zeta's canonical slot — the
            // classic stray: the body names victim, never zeta.
            let stray = fs::read(state_head_file(&ctx.workspace, &victim)).expect("victim head");
            fs::write(state_head_file(&ctx.workspace, &zeta), stray).expect("plant stray head");
            let head_rel = format!(".lomo/state/v2/heads/{zeta}.rec");

            let scoped = expect_err(
                "coverage-gated arm",
                ctx.session
                    .reconcile_observed_paths(&[rel(&victim_slot), rel(&head_rel)]),
            );
            assert_eq!(
                scoped.code(),
                "state_head_mismatch",
                "the absorb path's live tip-check meets the forged object first: \
                 {scoped:?}"
            );
            let replay = expect_err("ungated replay", ctx.session.rebuild_projection());
            assert_eq!(
                replay.code(),
                "state_head_mismatch",
                "the uncommitted files re-diff into the same verdict: {replay:?}"
            );

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("the stray+forgery must die cold"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "state_head_mismatch",
                "victim's tip resolution dies on the forged body: {cold:?}"
            );
        }

        // The declared first-code-ordering residual: `state_head_tip_claim`
        // propagates the claimed head's decode error. With coverage restricted to
        // the object, the corrupt head never enters the scope but is still read
        // live — the incremental arm reports the head layer (`lomo_record_
        // truncated`), while the cold listing may first meet victim's slot walk
        // (`state_head_mismatch`) or zeta's corrupt head depending on file order.
        // Both arms fail closed on the same durable set.
        #[test]
        fn a_corrupt_claimed_head_reports_the_head_layer_code_incrementally() {
            let (_scratch, ctx, victim, zeta) = seeded_three_memo_ctx();
            pin_active(&ctx, &victim, "pin-victim-corrupt-head");
            pin_active(&ctx, &zeta, "pin-zeta-corrupt-head");
            unpin_active(&ctx, &victim, "unpin-victim-corrupt-head");
            ctx.session.rebuild_projection().expect("settle");

            let victim_slot = state_tip_object_rel(&ctx.workspace, &victim);
            let victim_tip = state_object_stem(&victim_slot).to_owned();
            let mut forged = state_revision_at(&ctx.workspace, &victim_slot);
            forged.memo_id = zeta.clone();
            fs::write(
                ctx.workspace.join(&victim_slot),
                record_bytes(
                    LomoRecordKind::State,
                    &victim_tip,
                    serde_json::to_string(&forged).expect("forged object"),
                ),
            )
            .expect("forge the slot body");
            fs::write(
                state_head_file(&ctx.workspace, &zeta),
                b"corrupted-head-bytes".as_slice(),
            )
            .expect("corrupt the claimed head");

            // Observing only the object leaves the corrupt head out of the
            // classified scope — `state_head_tip_claim` still reads it live and
            // its decode failure propagates instead of degrading to Ok(false).
            let scoped = expect_err(
                "coverage-gated arm",
                ctx.session.reconcile_observed_paths(&[rel(&victim_slot)]),
            );
            assert_eq!(
                scoped.code(),
                "lomo_record_truncated",
                "the claimed head's framing fault propagates as the gather's \
                 first code: {scoped:?}"
            );
            let replay = expect_err("ungated replay", ctx.session.rebuild_projection());
            assert_eq!(
                replay.code(),
                "lomo_record_truncated",
                "the ungated gather meets the corrupt head in the heads loop: \
                 {replay:?}"
            );

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("a compound fault must die cold"),
                Err(error) => error,
            };
            assert!(
                ["lomo_record_truncated", "state_head_mismatch"].contains(&cold.code()),
                "cold reports whichever fault its listing order meets first — the \
                 declared ordering residual: {}",
                cold.code()
            );
        }

        // ---------- `decode_state_revision_owner`'s new admit layers ----------
        //
        // The envelope `record_id` is now bound inside the owner decode: a lying
        // envelope dies there even when the body itself is self-consistent and
        // claims the slot's own stem. The gather degrades to the full scan, whose
        // `decode_typed` envelope check reports `record_identity_mismatch` — the
        // identical verdict on every arm.
        #[test]
        fn an_object_with_a_lying_envelope_dies_on_every_arm() {
            let (_scratch, ctx, victim, zeta) = seeded_three_memo_ctx();
            pin_active(&ctx, &victim, "pin-victim-envelope");
            unpin_active(&ctx, &victim, "unpin-victim-envelope");
            ctx.session.rebuild_projection().expect("settle");

            let victim_slot = state_tip_object_rel(&ctx.workspace, &victim);
            let mut forged = state_revision_at(&ctx.workspace, &victim_slot);
            forged.memo_id = zeta;
            fs::write(
                ctx.workspace.join(&victim_slot),
                // The stem and the body agree; only the envelope lies.
                record_bytes(
                    LomoRecordKind::State,
                    "bogus-envelope",
                    serde_json::to_string(&forged).expect("forged object"),
                ),
            )
            .expect("forge the slot body");

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("a lying envelope must die cold"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "record_identity_mismatch",
                "the claimed tip's envelope check dies on the lie: {cold:?}"
            );

            let scoped = expect_err(
                "coverage-gated arm",
                ctx.session.reconcile_observed_paths(&[rel(&victim_slot)]),
            );
            assert_eq!(
                scoped.code(),
                "record_identity_mismatch",
                "the owner decode's envelope binding defers to the cold verdict: \
                 {scoped:?}"
            );
            let replay = expect_err("ungated replay", ctx.session.rebuild_projection());
            assert_eq!(
                replay.code(),
                "record_identity_mismatch",
                "the uncommitted liar re-diffs forever: {replay:?}"
            );
        }

        // `RevisionId::parse` rejects a malformed claim inside the owner decode —
        // but the gather never surfaces `invalid_revision_id` to the caller: the
        // decode failure degrades to the full scan, and the slot walk's
        // `revision_id != head claim` comparison reports `state_head_mismatch`
        // instead. Same durable bytes, one observable verdict.
        #[test]
        fn an_object_with_an_unparseable_revision_claim_dies_on_the_slot_walk() {
            let (_scratch, ctx, victim, _zeta) = seeded_three_memo_ctx();
            pin_active(&ctx, &victim, "pin-victim-unparseable");
            unpin_active(&ctx, &victim, "unpin-victim-unparseable");
            ctx.session.rebuild_projection().expect("settle");

            let victim_slot = state_tip_object_rel(&ctx.workspace, &victim);
            let victim_tip = state_object_stem(&victim_slot).to_owned();
            let mut forged = state_revision_at(&ctx.workspace, &victim_slot);
            // `RevisionId`'s serde is an unchecked newtype — the claim only meets
            // format validation inside the owner decode.
            forged.revision_id =
                serde_json::from_str("\"zz-garbage\"").expect("unchecked revision id");
            fs::write(
                ctx.workspace.join(&victim_slot),
                record_bytes(
                    LomoRecordKind::State,
                    &victim_tip,
                    serde_json::to_string(&forged).expect("forged object"),
                ),
            )
            .expect("forge the slot body");

            let cold = match fresh_oracle(&ctx.workspace) {
                Ok(_oracle) => panic!("an unparseable claim must die cold"),
                Err(error) => error,
            };
            assert_eq!(
                cold.code(),
                "state_head_mismatch",
                "the tip walk's claim comparison dies on the garbage revision: \
                 {cold:?}"
            );

            let scoped = expect_err(
                "coverage-gated arm",
                ctx.session.reconcile_observed_paths(&[rel(&victim_slot)]),
            );
            assert_eq!(
                scoped.code(),
                "state_head_mismatch",
                "the owner decode's `invalid_revision_id` never leaks — the slot \
                 walk's verdict governs: {scoped:?}"
            );
            let replay = expect_err("ungated replay", ctx.session.rebuild_projection());
            assert_eq!(
                replay.code(),
                "state_head_mismatch",
                "the uncommitted garbage claim re-diffs forever: {replay:?}"
            );
        }

        // ---------- the declared intentional change ----------
        //
        // A diffed object whose claiming memo's head moved on is unprovable
        // inside the diff — the gather defers and the full scan settles the row
        // as inert: no head claims the stem, so `pins()` skips it and the row
        // joins the committed baseline with a byte-identical projection.
        #[test]
        fn a_stale_tip_object_with_drifted_bytes_is_inert_on_every_arm() {
            let (_scratch, ctx, victim, _zeta) = seeded_three_memo_ctx();
            pin_active(&ctx, &victim, "pin-victim-stale");
            let stale_slot = state_tip_object_rel(&ctx.workspace, &victim);
            unpin_active(&ctx, &victim, "unpin-victim-stale");
            ctx.session.rebuild_projection().expect("settle");

            // Drift the stale tip's bytes: same claims, new listing token.
            let record = decode_record(
                &fs::read(ctx.workspace.join(&stale_slot)).expect("stale tip object"),
            )
            .expect("record");
            let mut drifted: lomo_workspace::StateRevisionV2 =
                serde_json::from_str(&record.payload.body_json).expect("stale body");
            drifted.created_at_ms += 1;
            fs::write(
                ctx.workspace.join(&stale_slot),
                record_bytes(
                    LomoRecordKind::State,
                    &record.payload.record_id,
                    serde_json::to_string(&drifted).expect("drifted body"),
                ),
            )
            .expect("drift the stale tip");

            ctx.session
                .reconcile_observed_paths(&[rel(&stale_slot)])
                .expect("a non-tip object defers to the full scan and settles inert");
            ctx.session
                .rebuild_projection()
                .expect("the inert row commits into the baseline");
            assert!(
                listing_contains(&ctx.cache_dir, &stale_slot),
                "the inert row stays committed — it stops diffing honestly"
            );

            let oracle = fresh_oracle(&ctx.workspace).expect("cold ignores the stale object");
            assert_eq!(
                summaries(&ctx.session, false)
                    .iter()
                    .map(|summary| summary.memo_id.as_str())
                    .collect::<Vec<_>>(),
                summaries(&oracle.session, false)
                    .iter()
                    .map(|summary| summary.memo_id.as_str())
                    .collect::<Vec<_>>(),
                "the fallback full scan projects the identical memo set"
            );
            assert_eq!(
                pin_timestamp_for(&ctx.cache_dir, &victim),
                pin_timestamp_for(&oracle.cache_dir, &victim),
                "the pin verdict is identical on both arms"
            );
        }
    }
}
