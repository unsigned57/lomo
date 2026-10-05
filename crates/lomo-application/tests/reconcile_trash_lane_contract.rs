//! Adversarial reconcile probes on the trash lane: record-echo rows,
//! lane-aware row images, claim disambiguation, purge ordering and the
//! lane-authority widen. Merged from the numbered re-audit rounds.

mod tests {

    // adversarial-reaudit round 3: the 11-D repair batch claims the trash lane is
    // closed-loop — lane-aware row images, record-content attestation digests and a
    // purge-tombstoned SAF delete arm. This pass probes the seams the second round
    // never reached: the v11->v12 empty-digest migration window, and whether the
    // SCOPED apply (`reconcile_scoped` → `apply_scanned_incremental` →
    // `upsert_trash_projection` → `merge_trash_projection`) converges to the same
    // committed image the cold-scan gate predicts, on record *rewrites* — the only
    // durable-fact transition the incremental path sees mid-flight.
    //
    // Invariants probed, evidence-first:
    // - A migrated `memo_trash.record_digest=''` row can never certify as equal to
    //   any scanned attestation (digests are non-empty SHAs): a listing-quiet
    //   reconcile must not manufacture certification for it, and the first gate
    //   pass must materialize once — repopulating the digest — and then converge.
    // - A rewrite that touches only *restore-only* record fields (`time_part`) or
    //   the *declared* `attachments` payload moves no committed row and no digest:
    //   the projection must stay converged without a rewrite.
    // - A rewrite that changes the record's *claimed* `source_fingerprint` on a
    //   document-absent identity: the cold-scan prediction adopts the new claim as
    //   the row's canonical fingerprint (the record is the only authority). Before
    //   the round-3 repair the scoped merge reused the *committed row's*
    //   fingerprint — treating the stale claim as if it were a same-scan document
    //   attestation — so the committed image diverged from every fresh
    //   materialize.
    // - The same rewrite changing `source_path`: the gate's designed answer is
    //   `Ok(None)` → materialize converges (no `current` row exists in the temp
    //   build, so the doc-attestation guard never fires). Before the repair the
    //   scoped apply hard-errored `saf_trash_source_path_mismatch` inside its own
    //   transaction — and because the failed commit never rewrote the listing
    //   snapshot, every later reconcile took the same path and wedged the session.
    //
    // Tests asserting the CORRECT invariant that still FAIL are kept RED as
    // residual-defect evidence for `audit/13-再复审-数据路径修复.md`. After the
    // round-3 repair (`audit/14-修复-数据路径残留.md`) every probe is GREEN: the two
    // former REDs are the acceptance locks, and the two boundary-characterization
    // probes now assert convergence invariants where they once pinned the defect
    // boundary.

    #[cfg(test)]
    #[expect(
        clippy::expect_used,
        reason = "adversarial audit tests fail closed with panics on missing facts"
    )]
    mod trash_lane_closure {
        use std::{
            collections::BTreeSet,
            fmt::Write as _,
            fs,
            path::{Path, PathBuf},
            sync::Arc,
        };

        use lomo_application::{
            MemoFilters, MemoQuery, MemoSort, WorkspaceSession, WorkspaceSessionConfig,
        };
        use lomo_core::{CapabilityToken, PageSize, PlatformActionExecutor};
        use lomo_platform_fs::FsPlatformActionExecutor;
        use lomo_workspace::{
            SourceFingerprint, TrashRecordCreate, TrashRecordV1, WorkspaceGenerationId,
            WorkspaceRootId, trash_record_relative_path, write_trash_record_atomic,
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

        fn try_open_session(workspace: &Path) -> Result<Ctx, lomo_core::LomoError> {
            let dirs = (0..6)
                .map(|_| tempdir().expect("fixture dir"))
                .collect::<Vec<_>>();
            let workspace_path = workspace.to_path_buf();
            let real = Arc::new(FsPlatformActionExecutor::new(fixture_dir(&dirs, 4))?);
            let capability = CapabilityToken::parse("notes")?;
            real.bind_root(capability.clone(), &workspace_path)?;
            let executor: Arc<dyn PlatformActionExecutor> = real;
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
                _dirs: dirs,
            })
        }

        fn open_session(workspace: &Path) -> Ctx {
            try_open_session(workspace).expect("session")
        }

        /// A second session on the same workspace with fresh private directories: its
        /// open can only materialize from durable facts — the "全量" oracle.
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

        /// Every row of one table, ordered, rendered textually — including the new
        /// `record_digest` column this audit round attests.
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
        /// lifecycle membership (with `record_digest` this round), tags, attachments,
        /// purge tombstones and the committed listing snapshot.
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

        /// The committed `record_digest` for one trash identity, read at the raw layer.
        fn committed_digest(cache_dir: &Path, memo_id: &str) -> Option<String> {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            conn.query_row(
                "SELECT record_digest FROM memo_trash WHERE memo_id=?1",
                rusqlite::params![memo_id],
                |row| row.get(0),
            )
            .optional()
            .expect("digest query")
        }

        /// The committed `memo.file_fingerprint` for one identity, read at the raw layer.
        fn committed_fingerprint(cache_dir: &Path, memo_id: &str) -> Option<String> {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            conn.query_row(
                "SELECT file_fingerprint FROM memo WHERE memo_id=?1",
                rusqlite::params![memo_id],
                |row| row.get(0),
            )
            .optional()
            .expect("fingerprint query")
        }

        /// Simulates the post-v11→v12-migration committed state: the row survives with
        /// a `''` digest the scan can never reproduce.
        fn blank_out_digest(cache_dir: &Path, memo_id: &str) {
            let db = cache_dir.join(".lomo-sqlite").join("store.db");
            let conn = rusqlite::Connection::open(&db).expect("store db");
            let changed = conn
                .execute(
                    "UPDATE memo_trash SET record_digest='' WHERE memo_id=?1",
                    rusqlite::params![memo_id],
                )
                .expect("tamper digest");
            assert_eq!(changed, 1, "fixture tamper must hit the trash row");
        }

        /// The result of the same workspace under a scoped/watcher-guided reconcile
        /// (the live session) must equal a sibling session that could only fully
        /// materialize the durable facts — on both the public surface and the raw rows.
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
        /// mechanism the second-round suite uses to reach the inventory gate.
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

        // ---------- v11→v12 empty-digest migration window ----------

        /// `MIGRATE_V11_TO_V12_DDL` leaves existing `memo_trash` rows with
        /// `record_digest=''`. The repair claim is fail-closed: a `''` can never equal
        /// a scanned attestation digest, so certification is impossible and the first
        /// gate pass materializes once — never loops — and converges to a fresh scan.
        ///
        /// Boundary the repair doc's wording glosses over: a listing-quiet reconcile
        /// (digest short-circuit, `session.rs:723-731`) never *inspects* the row at
        /// all — the `''` persists untouched. That window is asserted here
        /// descriptively: `''` must never be *certified* (the gate cannot run while
        /// the listing claims equality), and once a scope-defeating change forces the
        /// gate the row heals and stays healed.
        #[test]
        fn a_v11_era_empty_digest_never_certifies_and_heals_on_the_first_gate() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "trash me")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed rebuild");
            let victim = summaries(&ctx.session, false)
                .into_iter()
                .find(|summary| {
                    ctx.session
                        .projected_memo(&summary.memo_id)
                        .expect("snapshot")
                        .is_some_and(|snapshot| snapshot.body.contains("trash me"))
                })
                .expect("victim");
            let victim_id = victim.memo_id.clone();
            ctx.session
                .delete_memo(lomo_application::DeleteMemoRequest {
                    operation_id: lomo_core::OperationId::parse("v11-delete").expect("op"),
                    memo_id: lomo_workspace::MemoId::parse(&victim_id).expect("id"),
                    expected_document_fingerprint: victim.file_fingerprint,
                    trashed_at_ms: Some(1_757_500_000_000),
                })
                .expect("delete commits a trash row");
            let digest = committed_digest(&ctx.cache_dir, &victim_id).expect("trash row");
            assert!(!digest.is_empty(), "the commit path writes a real digest");

            // A v11-era store ends the session converged: the committed listing
            // snapshot already contains the record file the delete wrote. Settle
            // it so the blanked digest is the ONLY divergence the next pass sees.
            ctx.session
                .rebuild_projection()
                .expect("settle the listing after the delete");

            // The simulated migration window: row survives, digest blanked.
            blank_out_digest(&ctx.cache_dir, &victim_id);
            assert_eq!(
                committed_digest(&ctx.cache_dir, &victim_id).as_deref(),
                Some("")
            );

            // A listing-quiet reconcile certifies nothing and heals nothing — the
            // digest short-circuit answers without ever comparing the row.
            let quiet = ctx.session.rebuild_projection().expect("quiet reconcile");
            assert!(!quiet.rewritten, "a quiet pass must not claim a rewrite");
            assert_eq!(
                committed_digest(&ctx.cache_dir, &victim_id).as_deref(),
                Some(""),
                "descriptive: the short-circuit never inspected the blank row — \
                 the fail-closed property is 'can never certify', not 'healed eagerly'"
            );

            // The first gate pass must materialize exactly once and repopulate the
            // real digest — then steady-state must short-circuit again.
            park_unclassifiable(&ctx.workspace);
            let gated = ctx
                .session
                .rebuild_projection()
                .expect("the gate must materialize, not error");
            assert!(
                gated.rewritten,
                "a '' digest can never equal the scanned attestation — the gate \
                 must decline and materialize"
            );
            let healed = committed_digest(&ctx.cache_dir, &victim_id).expect("trash row");
            assert!(
                !healed.is_empty(),
                "materialize repopulates the attestation digest"
            );
            let steady = ctx.session.rebuild_projection().expect("steady-state");
            assert!(
                !steady.rewritten,
                "the healed row must certify on the very next pass — one materialize, \
                 not a permanent rewrite loop"
            );
            assert_equivalent_to_fresh_scan(&ctx);
        }

        // ---------- scoped-apply convergence on record rewrites ----------

        /// The scoped apply must converge to the same committed image a cold
        /// materialize derives. For a document-absent identity the record is the only
        /// fingerprint authority — a rewrite that changes the claimed
        /// `source_fingerprint` must land that claim in the committed `memo` row.
        /// `merge_trash_projection` instead prefers the *committed row's* fingerprint
        /// whenever `current` exists — in the incremental transaction `current` is the
        /// stale record-derived row, not a same-scan document attestation — so the
        /// committed image keeps the superseded claim while every fresh materialize
        /// adopts the new one.
        #[test]
        fn a_rewritten_records_new_fingerprint_claim_must_reach_the_committed_row() {
            let scratch = tempdir().expect("workspace");
            let ctx = open_session(scratch.path());
            let ghost = "m_00000000000000000000000000000fade1";

            write_trash_record(
                &ctx.workspace,
                &ghost_record(ghost, "2026_09_10.md", b"ghost-doc-v1", 1_757_500_000_000),
            );
            ctx.session
                .rebuild_projection()
                .expect("the scoped pass claims the record");
            assert!(projected_ids(&ctx, true).contains(ghost), "fixture sanity");

            // The same durable record redelivered with a corrected fingerprint claim —
            // listing token drifts, the scoped diff admits exactly this record.
            write_trash_record(
                &ctx.workspace,
                &ghost_record(ghost, "2026_09_10.md", b"ghost-doc-v2", 1_757_500_000_000),
            );
            ctx.session
                .rebuild_projection()
                .expect("the scoped apply must converge the rewrite, not error");

            let oracle = fresh_oracle(&ctx.workspace);
            assert_eq!(
                public_dump(&ctx.session),
                public_dump(&oracle.session),
                "the committed fingerprint diverged from a cold materialize — the \
                 incremental merge anchored the stale committed claim instead of the \
                 record's current claim"
            );
            assert_eq!(
                store_dump(&ctx.cache_dir),
                store_dump(&oracle.cache_dir),
                "memo.file_fingerprint diverged: incremental kept the old claim, \
                 materialize adopts the record's"
            );
        }

        /// The same conflation with a changed `source_path` claim: the committed row
        /// carries the old claim, so `merge_trash_projection`'s doc-attestation guard
        /// fires on *stale committed state* — `saf_trash_source_path_mismatch` — while
        /// the gate + materialize answer for identical durable facts is convergence
        /// (a document-absent record's merge sees no `current` and errors nothing).
        /// Worse: the aborted transaction never rewrites `file_listing`, so every
        /// later reconcile diffs the same record onto the same committed row — the
        /// session wedges until the record file is removed by hand.
        #[test]
        fn a_rewritten_records_new_source_path_claim_must_converge_not_wedge() {
            let scratch = tempdir().expect("workspace");
            let ctx = open_session(scratch.path());
            let ghost = "m_00000000000000000000000000000fade2";

            write_trash_record(
                &ctx.workspace,
                &ghost_record(ghost, "2026_09_10.md", b"ghost-doc", 1_757_500_000_000),
            );
            ctx.session
                .rebuild_projection()
                .expect("the scoped pass claims the record");

            // A peer redelivery moves the claim to a different source document —
            // `certification_image` treats this as "no certifiable image →
            // materialize", and materialize converges because no committed row
            // survives inside the temp build.
            write_trash_record(
                &ctx.workspace,
                &ghost_record(ghost, "2026_09_11.md", b"ghost-doc", 1_757_500_000_000),
            );
            let first = ctx.session.rebuild_projection();
            assert!(
                first.is_ok(),
                "the scoped apply hard-errored on a record rewrite the cold scan \
                 converges — and the wedge repeats on every later pass: {first:?}"
            );
            let second = ctx.session.rebuild_projection();
            assert!(
                second.is_ok(),
                "the wedge is permanent: the failed transaction never re-lists, so \
                 the same diff reproduces the same error forever: {second:?}"
            );
            assert_equivalent_to_fresh_scan(&ctx);
        }

        // ---------- rewritten-claim convergence invariants ----------
        //
        // These probes were boundary characterizations while the defects stood;
        // they now assert the invariant the two RED tests demand: every rewritten
        // claim reaches the committed row through the scoped apply — the merge may
        // never anchor the record-echo row it is about to overwrite.

        /// A rewritten fingerprint claim lands in the committed row on every scoped
        /// pass — the record is the only fingerprint authority for a
        /// document-absent identity, so each rewrite must re-derive the row from
        /// the new claim instead of inheriting the superseded one.
        #[test]
        fn rewritten_fingerprint_claims_converge_through_every_scoped_pass() {
            let scratch = tempdir().expect("workspace");
            let ctx = open_session(scratch.path());
            let ghost = "m_00000000000000000000000000000bade1";

            for claim in [b"claim-v1".as_slice(), b"claim-v2", b"claim-v3"] {
                write_trash_record(
                    &ctx.workspace,
                    &ghost_record(ghost, "2026_09_10.md", claim, 1_757_500_000_000),
                );
                ctx.session
                    .rebuild_projection()
                    .expect("every scoped pass converges the rewrite");
                let expected = SourceFingerprint::of_bytes(claim).as_str().to_owned();
                assert_eq!(
                    committed_fingerprint(&ctx.cache_dir, ghost).as_deref(),
                    Some(expected.as_str()),
                    "the committed fingerprint must be the record's current claim"
                );
            }
            assert_equivalent_to_fresh_scan(&ctx);
        }

        /// A moved `source_path` claim converges identically: with no document
        /// emitting the identity, the record's new claim is the row's only truth —
        /// the scoped apply commits it, and unrelated classifiable churn keeps the
        /// converged image because the record never re-diffs onto a stale row.
        #[test]
        fn a_moved_source_path_claim_converges_and_stays_converged() {
            let scratch = tempdir().expect("workspace");
            let ctx = open_session(scratch.path());
            let ghost = "m_00000000000000000000000000000bade2";

            write_trash_record(
                &ctx.workspace,
                &ghost_record(ghost, "2026_09_10.md", b"ghost-doc", 1_757_500_000_000),
            );
            ctx.session.rebuild_projection().expect("claim");
            write_trash_record(
                &ctx.workspace,
                &ghost_record(ghost, "2026_09_11.md", b"ghost-doc", 1_757_500_000_000),
            );

            ctx.session
                .rebuild_projection()
                .expect("the scoped apply converges a moved claim — the cold verdict");

            // Ordinary classifiable churn keeps the converged image: the record no
            // longer re-diffs onto a stale committed row.
            write_doc(
                &ctx.workspace,
                "2026_09_20.md",
                &[("09:00:00", "unrelated doc churn")],
            );
            ctx.session
                .rebuild_projection()
                .expect("classifiable churn after the move must not disturb it");
            assert_equivalent_to_fresh_scan(&ctx);
        }

        /// A committed record echo can also squat under a *live* document path —
        /// the document exists but does not emit the identity. A claim moving off
        /// that path must converge like a cold scan (sibling-or-claim at the new
        /// path): the echo may not act as document attestation, so the claimed
        /// document is rescanned to decide the lane before the merge runs.
        #[test]
        fn a_moved_claim_off_a_live_document_path_converges_like_a_cold_scan() {
            let scratch = tempdir().expect("workspace");
            write_doc(scratch.path(), "2026_09_10.md", &[("10:00:00", "stayer")]);
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let ghost = "m_00000000000000000000000000000bade3";

            // The record squats under the live document's path: the committed row is
            // a record echo anchored on the sibling document's fingerprint.
            write_trash_record(
                &ctx.workspace,
                &ghost_record(ghost, "2026_09_10.md", b"ghost-doc", 1_757_500_000_000),
            );
            ctx.session.rebuild_projection().expect("squatter claim");
            assert_equivalent_to_fresh_scan(&ctx);

            write_trash_record(
                &ctx.workspace,
                &ghost_record(ghost, "2026_09_11.md", b"ghost-doc", 1_757_500_000_000),
            );
            ctx.session
                .rebuild_projection()
                .expect("the echo row must not wedge the moved claim");
            assert_equivalent_to_fresh_scan(&ctx);
        }

        /// The guard is preserved for the lane it exists for: while a live document
        /// still emits the identity, a record claiming a different source path is a
        /// genuine attestation conflict — the scoped apply surfaces
        /// `saf_trash_source_path_mismatch`, the same verdict the cold merge
        /// produces, instead of silently moving the row off the document.
        #[test]
        fn a_record_claim_moving_off_a_live_document_still_fails_the_guard() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "dual lane memo")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = summaries(&ctx.session, false)
                .first()
                .expect("seeded memo")
                .memo_id
                .clone();

            // The block still sits in the document: record + doc = dual lane.
            write_trash_record(
                &ctx.workspace,
                &ghost_record(&victim, "2026_09_10.md", b"doc", 1_757_500_000_000),
            );
            ctx.session
                .rebuild_projection()
                .expect("a claim matching the live document commits dual lane");
            assert_equivalent_to_fresh_scan(&ctx);

            write_trash_record(
                &ctx.workspace,
                &ghost_record(&victim, "2026_09_11.md", b"doc", 1_757_500_000_000),
            );
            let moved = ctx.session.rebuild_projection();
            assert_eq!(
                moved
                    .as_ref()
                    .expect_err("a claim moving off the live document must fail")
                    .code(),
                "saf_trash_source_path_mismatch",
                "verdict parity with the cold merge — the live document still emits \
                 the identity, so a disagreeing claim is a real attestation conflict"
            );
        }

        // ---------- digest-coverage controls ----------

        /// `time_part` is restore-only payload: it lands in no committed row and is
        /// deliberately outside the attestation digest. A rewrite touching only it
        /// must leave the projection byte-identical — the apply must even report
        /// `rewritten == false` (the projection-state digest covers the same rows).
        #[test]
        fn a_rewrite_touching_only_restore_fields_stays_converged() {
            let scratch = tempdir().expect("workspace");
            let ctx = open_session(scratch.path());
            let ghost = "m_00000000000000000000000000000fade3";

            let mut record = ghost_record(ghost, "2026_09_10.md", b"ghost-doc", 1_757_500_000_000);
            write_trash_record(&ctx.workspace, &record);
            ctx.session.rebuild_projection().expect("claim");

            // Rewrite the record with only `time_part` changed — restore metadata,
            // invisible to every committed row and to the digest.
            record.time_part = "23:59:59".to_owned();
            write_trash_record(&ctx.workspace, &record);
            let result = ctx
                .session
                .rebuild_projection()
                .expect("scoped apply converges");
            assert!(
                !result.rewritten,
                "a restore-only record rewrite must move no committed row — the \
                 digest's exclusion of `time_part` is the whole point"
            );
            assert_equivalent_to_fresh_scan(&ctx);
        }

        /// The declared `attachments` list is payload, never evidence: attachment
        /// rows derive from the body alone. A rewrite that changes only the declared
        /// list (body untouched) must not move a single committed row — the digest
        /// covers body-derived keys, so both the apply and the gate see an identical
        /// image.
        #[test]
        fn a_rewrite_touching_only_declared_attachments_stays_converged() {
            let scratch = tempdir().expect("workspace");
            let ctx = open_session(scratch.path());
            let ghost = "m_00000000000000000000000000000fade4";

            let mut record = ghost_record(ghost, "2026_09_10.md", b"ghost-doc", 1_757_500_000_000);
            write_trash_record(&ctx.workspace, &record);
            ctx.session.rebuild_projection().expect("claim");

            record.attachments = vec!["media/phantom.png".to_owned()];
            write_trash_record(&ctx.workspace, &record);
            let result = ctx
                .session
                .rebuild_projection()
                .expect("scoped apply converges");
            assert!(
                !result.rewritten,
                "a declared-attachments rewrite moves nothing: attachment evidence \
                 comes from the body, so the committed image is identical"
            );
            assert_equivalent_to_fresh_scan(&ctx);
        }

        /// `trashed_at_ms` IS a row-deciding fact: the digest covers it and the
        /// committed `memo_trash.trashed_at_ms` column tracks it. A timestamp-only
        /// rewrite through the scoped path must land in the committed row — and a
        /// fresh oracle must agree byte-for-byte.
        #[test]
        fn a_rewrite_of_the_trash_timestamp_tracks_through_the_scoped_apply() {
            let scratch = tempdir().expect("workspace");
            let ctx = open_session(scratch.path());
            let ghost = "m_00000000000000000000000000000fade5";

            write_trash_record(
                &ctx.workspace,
                &ghost_record(ghost, "2026_09_10.md", b"ghost-doc", 1_757_500_000_000),
            );
            ctx.session.rebuild_projection().expect("claim");

            write_trash_record(
                &ctx.workspace,
                &ghost_record(ghost, "2026_09_10.md", b"ghost-doc", 1_757_500_999_000),
            );
            ctx.session
                .rebuild_projection()
                .expect("scoped apply converges the timestamp");
            assert_equivalent_to_fresh_scan(&ctx);
        }
    }

    // adversarial-reaudit round 4: the P4-F1 batch
    // (`audit/14-修复-数据路径残留.md`) claims the scoped trash lane is now
    // closed-loop — `disambiguate_trash_claims` widens the rescan to every live
    // document a committed trashed row sits under, `Gather::trash()` retires
    // record-echo rows before re-claiming, `doc_attested_ids` carries the lane
    // verdict into the store, the apply reorders purge facts ahead of upserts,
    // and a post-apply self-certification fail-closes on any merged row a cold
    // scan could not produce.
    //
    // The probes below attack the seams that batch did NOT widen:
    //
    // - `Gather::trash()`'s `trash_removed` arm still retires the whole identity
    //   unconditionally. For a document-absent echo that is exactly the cold
    //   answer — but for a *dual-lane* identity (the durable record claimed a
    //   memo a live document still emits) the retire deletes the document lane
    //   too: the committed row leaves with no rescan to re-emit it, while a cold
    //   scan keeps the document row untrashed. The round-3 defect class —
    //   "the committed row's shape decides the lane" — survives on the removal
    //   path: nothing asks the document whether it still emits the identity.
    // - `apply_purged_id` deletes any `memo` row carrying `memo_trash`. On a
    //   dual-lane committed row that is BOTH lanes at once: the reorder lets a
    //   *rescanned* document re-project the row after the purge lands, but a
    //   tombstone arriving while the document is unchanged still deletes the
    //   document lane — the cold scan suppresses only the record and keeps the
    //   row. `audit/14 §8-4` claims cold isomorphism for the purge/document
    //   interplay; the claim holds only on the rescanned arm.
    // - Boundary arms that MUST stay green: an echo's record deletion still
    //   retires (the doc-absent lane the retire exists for), a same-pass rescan
    //   still converges the delete/purge through delete+reinsert, a first claim
    //   squatting a live document path still anchors the sibling fingerprint,
    //   and a dual-lane claim rewritten on the same path keeps the document's
    //   fingerprint authority.
    //
    // RED probes assert the cold-scan invariant and are kept failing as
    // residual-defect evidence for `audit/15-再复审-数据路径修复.md`.

    #[cfg(test)]
    #[expect(
        clippy::expect_used,
        reason = "adversarial audit tests fail closed with panics on missing facts"
    )]
    mod trash_claim_widening {
        use std::{
            collections::BTreeSet,
            fmt::Write as _,
            fs,
            path::{Path, PathBuf},
            sync::Arc,
        };

        use lomo_application::{
            MemoFilters, MemoQuery, MemoSort, WorkspaceSession, WorkspaceSessionConfig,
        };
        use lomo_core::{CapabilityToken, PageSize, PlatformActionExecutor};
        use lomo_platform_fs::FsPlatformActionExecutor;
        use lomo_workspace::{
            SourceFingerprint, TrashRecordCreate, TrashRecordV1, WorkspaceGenerationId,
            WorkspaceRootId, trash_record_relative_path, write_trash_record_atomic,
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

        fn try_open_session(workspace: &Path) -> Result<Ctx, lomo_core::LomoError> {
            let dirs = (0..6)
                .map(|_| tempdir().expect("fixture dir"))
                .collect::<Vec<_>>();
            let workspace_path = workspace.to_path_buf();
            let real = Arc::new(FsPlatformActionExecutor::new(fixture_dir(&dirs, 4))?);
            let capability = CapabilityToken::parse("notes")?;
            real.bind_root(capability.clone(), &workspace_path)?;
            let executor: Arc<dyn PlatformActionExecutor> = real;
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
                _dirs: dirs,
            })
        }

        fn open_session(workspace: &Path) -> Ctx {
            try_open_session(workspace).expect("session")
        }

        /// A second session on the same workspace with fresh private directories: its
        /// open can only materialize from durable facts — the "全量" oracle.
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
        /// lifecycle membership (with `record_digest`), tags, attachments, purge
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

        /// The result of the same workspace under a scoped/watcher-guided reconcile
        /// (the live session) must equal a sibling session that could only fully
        /// materialize the durable facts — on both the public surface and the raw rows.
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

        /// Writes a durable trash record for `memo_id` at its canonical hashed path.
        fn write_trash_record(workspace: &Path, record: &TrashRecordV1) {
            let rel = trash_record_relative_path(&record.memo_id).expect("trash path");
            let abs = workspace.join(rel.as_str());
            fs::create_dir_all(abs.parent().expect("record dir")).expect("record dir");
            write_trash_record_atomic(&abs, record).expect("write record");
        }

        /// Deletes the durable trash record for `memo_id` — the file-level restore a
        /// peer redelivery can surface on its own once the document half already
        /// landed in an earlier pass.
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
        /// plus a durable record claiming it at the same path. The merge arm accepts
        /// this by contract (the document row is live attestation), so both lanes
        /// agree it is committable — the divergences below all start here.
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
            assert_equivalent_to_fresh_scan(ctx);
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

        // ---------- removal-side lane verdicts the fix never reached ----------

        /// `trash_removed` retires the record's owner unconditionally — the same
        /// "committed row decides the lane" conflation the fix removed from the
        /// upsert path survives on the removal path. When the durable record is
        /// deleted while a live document still emits the identity (dual lane),
        /// the cold answer is "the document row stays, untrashed": the record was
        /// the only trash authority and it is gone. The scoped pass instead runs
        /// `retire_memo` — `memo_removes` deletes the whole row, no rescan re-emits
        /// it because the document never changed — and the identity silently drops
        /// out of the projection until the document next changes or a full
        /// materialize heals it.
        #[test]
        fn a_removed_trash_record_over_a_live_document_must_restore_the_document_lane() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "dual lane victim")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "dual lane victim");
            commit_dual_lane(&ctx, &victim);

            // The record half of a restore arrives alone — the document already
            // carried the block when the dual lane committed, so nothing in this
            // pass re-reads it. The document is the only remaining authority and
            // it still emits the identity: the row must stay, untrashed.
            remove_trash_record(&ctx.workspace, &victim);
            ctx.session
                .rebuild_projection()
                .expect("the scoped removal converge, not error");

            assert_equivalent_to_fresh_scan(&ctx);
            assert!(
                projected_ids(&ctx, false).contains(&victim),
                "the document lane must survive the record's removal — the \
                 document still emits the identity",
            );
        }

        /// The same gap through the purge arm: `apply_purged_id` deletes every row
        /// carrying `memo_trash`, and a dual-lane committed row carries both. The
        /// tombstone is documented to suppress only the trash lane — a cold scan
        /// keeps the document row because the document still emits the identity —
        /// but the incremental apply deletes it outright when the document was not
        /// rescanned in the same pass.
        #[test]
        fn a_purge_tombstone_over_a_live_document_must_keep_its_document_lane() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "dual lane victim")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "dual lane victim");
            commit_dual_lane(&ctx, &victim);

            // A peer-delivered tombstone lands while the document is untouched.
            // Cold: the record is suppressed, the document row stays — the
            // tombstone retires the trash lane only.
            write_purge_tombstone(&ctx.workspace, &victim, "r4-purge");
            ctx.session
                .rebuild_projection()
                .expect("the scoped purge converges");

            assert_equivalent_to_fresh_scan(&ctx);
            assert!(
                projected_ids(&ctx, false).contains(&victim),
                "the tombstone suppresses the trash lane, never the document lane"
            );
        }

        // ---------- boundary arms that must hold ----------

        /// The retire is correct for the lane it was designed for: a
        /// document-absent echo's authority is the record alone — delete it and
        /// the identity leaves the projection exactly as the cold scan computes.
        #[test]
        fn a_removed_trash_record_over_an_echo_still_retires_the_identity() {
            let scratch = tempdir().expect("workspace");
            let ctx = open_session(scratch.path());
            let ghost = "m_00000000000000000000000000000re4a1";

            write_trash_record(
                &ctx.workspace,
                &ghost_record(ghost, "2026_09_10.md", b"ghost-doc", 1_757_500_000_000),
            );
            ctx.session.rebuild_projection().expect("claim");
            assert!(projected_ids(&ctx, true).contains(ghost), "fixture sanity");

            remove_trash_record(&ctx.workspace, ghost);
            ctx.session
                .rebuild_projection()
                .expect("echo retire converges");
            assert!(
                !projected_ids(&ctx, true).contains(ghost)
                    && !projected_ids(&ctx, false).contains(ghost),
                "with the record gone nothing attests the identity"
            );
            assert_equivalent_to_fresh_scan(&ctx);
        }

        /// The delete+reinsert convergence arm: when the record removal and the
        /// document change land in the SAME pass, the rescan re-emits the identity
        /// into `memo_upserts`; `memo_removes` deleting the row first and the
        /// upsert re-creating it after is exactly the cold outcome — untrashed
        /// document lane restored. The hole is specifically the unrescanned arm.
        #[test]
        fn a_rescanned_document_survives_a_same_pass_record_delete() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "dual lane victim")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "dual lane victim");
            commit_dual_lane(&ctx, &victim);

            // The restore arrives as one diff: document re-emits the block, the
            // record leaves — both scopes in one transaction.
            write_doc(
                &ctx.workspace,
                "2026_09_10.md",
                &[
                    ("10:00:00", "stayer"),
                    ("10:01:00", "dual lane victim"),
                    ("10:03:00", "restored neighbor"),
                ],
            );
            remove_trash_record(&ctx.workspace, &victim);
            ctx.session
                .rebuild_projection()
                .expect("same-pass rescan converges the restore");
            assert_equivalent_to_fresh_scan(&ctx);
        }

        /// The purge-ordering rescue arm the fix did add: `purged_upserts` land
        /// before `memo_upserts`, so a tombstone arriving in the same pass as the
        /// document's rescan lets the re-emitted row re-project after the delete —
        /// cold-isomorphic. The remaining hole is the unchanged-document arm.
        #[test]
        fn a_rescanned_document_survives_a_same_pass_purge() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "dual lane victim")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "dual lane victim");
            commit_dual_lane(&ctx, &victim);

            write_doc(
                &ctx.workspace,
                "2026_09_10.md",
                &[
                    ("10:00:00", "stayer"),
                    ("10:01:00", "dual lane victim"),
                    ("10:03:00", "restored neighbor"),
                ],
            );
            write_purge_tombstone(&ctx.workspace, &victim, "r4-purge-rescan");
            ctx.session
                .rebuild_projection()
                .expect("same-pass rescan converges the purge");
            assert_equivalent_to_fresh_scan(&ctx);
        }

        /// A first claim on a path a live document occupies (without emitting the
        /// identity) squats under it by sibling derivation: no committed row for
        /// the identity exists, so the merge's `current=None` arm anchors the
        /// sibling document's fingerprint — the cold outcome, and the post-apply
        /// self-certification's re-derived expectation.
        #[test]
        fn a_first_claim_squatting_a_live_document_path_anchors_the_sibling() {
            let scratch = tempdir().expect("workspace");
            write_doc(scratch.path(), "2026_09_10.md", &[("10:00:00", "stayer")]);
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let ghost = "m_00000000000000000000000000000re4a2";

            write_trash_record(
                &ctx.workspace,
                &ghost_record(ghost, "2026_09_10.md", b"not-the-doc", 1_757_500_000_000),
            );
            ctx.session
                .rebuild_projection()
                .expect("first claim squats the live path by sibling anchor");
            assert!(projected_ids(&ctx, true).contains(ghost), "fixture sanity");
            assert_equivalent_to_fresh_scan(&ctx);
        }

        /// A dual-lane claim rewritten on the same path keeps the document's
        /// fingerprint authority: the rescan the disambiguation stage widens
        /// re-emits the identity, the merge sees the fresh document row, and the
        /// record's own claimed fingerprint never touches the committed row.
        #[test]
        fn a_dual_lane_record_rewrite_keeps_document_attestation() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "dual lane victim")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "dual lane victim");
            commit_dual_lane(&ctx, &victim);

            // Same path, drifted claim: the document still emits the identity, so
            // the claim's fingerprint is payload, never authority.
            write_trash_record(
                &ctx.workspace,
                &ghost_record(
                    &victim,
                    "2026_09_10.md",
                    b"drifted-claim",
                    1_757_500_999_000,
                ),
            );
            ctx.session
                .rebuild_projection()
                .expect("same-path rewrite converges on the document lane");
            assert_equivalent_to_fresh_scan(&ctx);
        }
    }

    // adversarial-reaudit round 5: the P5-F1 batch
    // (`audit/16-修复-车道路权补全.md`) claims the lane-authority widen now covers
    // all three trash-authority arms — `trash_reads` re-claims, `trash_removed`
    // record deletions, and `purged_upserts` landing tombstones — through one
    // shared `widen_document_lane` criterion, plus an `emitted_ids`-gated
    // `state_memos` re-push healing the `memo_pin` cascade `apply_purged_id`
    // performs on reinserted dual-lane rows.
    //
    // The probes below attack what the batch's own residual-risk note admits and
    // what it does not:
    //
    // - The pin re-push gate is `purged_upserts ∩ committed_trashed ∩
    //   emitted_ids`. A pinned dual-lane row MUST keep its pin through the
    //   cascade delete+reinsert (positive arm). A pinned committed-trashed row
    //   that is NOT re-emitted must never receive a `pin_upserts` — and after the
    //   cold-side pin axiom fix (`audit/16-修复-冷侧pin公理.md`), the other
    //   `state_memos` producers (`retire_memo`, the state-record scope arm) that
    //   feed the same re-walk without a liveness gate now degrade a dead pinned
    //   tip to `pin_removes` instead of firing the FK wedge. Both halves are
    //   probed against a fresh-materialize oracle.
    // - The pinned-tip × dead-row wedge the residual note called "isomorphic
    //   failure" is now probed as isomorphic CONVERGENCE — including whether
    //   `permanently_delete_memo` (a pure-app operation) still converges on both
    //   paths and whether a peer-delivered pinned tip over a rowless identity
    //   projects anything anywhere.
    // - A tombstone over a pure document row (no `memo_trash` membership) must
    //   only ever suppress records — `apply_purged_id` does not delete it and the
    //   incremental answer must equal the cold one.
    // - The fourth reachable arbitration path: `purge_removed` lifting a
    //   tombstone over a purged identity — with the claim record alive (the
    //   suppression lift lets it claim again) and with the record removed in the
    //   same pass (the unclassifiable fallback must fire, not a stale row).
    //
    // Probes asserting cold-scan equivalence are GREEN expectations. The four
    // probes that asserted symmetric failure documented the pre-fix wedge; they
    // now assert symmetric convergence — a semantic update (the fix, not a
    // weakened assertion), since both paths must produce the same dead-pin-free
    // projection.

    #[cfg(test)]
    #[expect(
        clippy::expect_used,
        reason = "adversarial audit tests fail closed with panics on missing facts"
    )]
    mod lane_authority {
        use std::{
            collections::BTreeSet,
            fmt::Write as _,
            fs,
            path::{Path, PathBuf},
            sync::Arc,
        };

        use lomo_application::{
            DeleteMemoRequest, MemoFilters, MemoQuery, MemoSort, PermanentDeleteRequest,
            PinMemoRequest, PinPolicy, WorkspaceSession, WorkspaceSessionConfig,
        };
        use lomo_core::{CapabilityToken, OperationId, PageSize, PlatformActionExecutor};
        use lomo_platform_fs::FsPlatformActionExecutor;
        use lomo_workspace::{
            MemoId, SourceFingerprint, TrashRecordCreate, TrashRecordV1, WorkspaceGenerationId,
            WorkspaceRootId, trash_record_relative_path, write_trash_record_atomic,
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

        fn try_open_session(workspace: &Path) -> Result<Ctx, lomo_core::LomoError> {
            let dirs = (0..6)
                .map(|_| tempdir().expect("fixture dir"))
                .collect::<Vec<_>>();
            let workspace_path = workspace.to_path_buf();
            let real = Arc::new(FsPlatformActionExecutor::new(fixture_dir(&dirs, 4))?);
            let capability = CapabilityToken::parse("notes")?;
            real.bind_root(capability.clone(), &workspace_path)?;
            let executor: Arc<dyn PlatformActionExecutor> = real;
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
                _dirs: dirs,
            })
        }

        fn open_session(workspace: &Path) -> Ctx {
            try_open_session(workspace).expect("session")
        }

        /// A second session on the same workspace with fresh private directories: its
        /// open can only materialize from durable facts — the "全量" oracle.
        fn fresh_oracle(workspace: &Path) -> Result<Ctx, lomo_core::LomoError> {
            try_open_session(workspace)
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

        /// Writes a durable trash record for `memo_id` at its canonical hashed path.
        fn write_trash_record(workspace: &Path, record: &TrashRecordV1) {
            let rel = trash_record_relative_path(&record.memo_id).expect("trash path");
            let abs = workspace.join(rel.as_str());
            fs::create_dir_all(abs.parent().expect("record dir")).expect("record dir");
            write_trash_record_atomic(&abs, record).expect("write record");
        }

        /// Deletes the durable trash record for `memo_id` — the file-level restore a
        /// peer redelivery can surface on its own once the document half already
        /// landed in an earlier pass.
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

        /// Deletes the durable purge tombstone for `memo_id` — the file-level
        /// suppression lift a peer redelivery can surface on its own.
        fn remove_purge_tombstone(workspace: &Path, memo_id: &str) {
            let rel = lomo_store::purge_record_relative_path(memo_id).expect("purge path");
            fs::remove_file(workspace.join(rel.as_str())).expect("remove tombstone");
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
            assert_equivalent_to_fresh_scan(ctx);
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
        /// A `purged_memo` target takes the delete's tombstone-aware retirement
        /// (the row is dropped outright, never left trashed), so the caller picks
        /// the committed shape it expects.
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

        /// Writes a durable pinned state head plus its tip object for `memo_id` — the
        /// shape a peer's state sync delivers — without any document or record that
        /// could project a `memo` row for it.
        fn write_pinned_state_tip(workspace: &Path, memo_id: &str, pinned_at_ms: i64) {
            let paths = lomo_workspace::LomoPaths::for_workspace_with_layout(
                Path::new(""),
                lomo_workspace::LomoLayoutVersion::V2,
            );
            let revision =
                lomo_workspace::StateRevisionV2::create(lomo_workspace::StateRevisionCreate {
                    memo_id,
                    parent: None,
                    pinned: true,
                    trashed: false,
                    pinned_at_ms: Some(pinned_at_ms),
                    trashed_at_ms: None,
                    pin_operation_id: Some("peer-pin".to_owned()),
                    trash_operation_id: None,
                    canonical_metadata: "",
                    created_at_ms: pinned_at_ms,
                })
                .expect("state revision");
            let head = lomo_workspace::StateHead {
                memo_id: memo_id.to_owned(),
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
                record_id: format!("head:{memo_id}"),
                body_json: serde_json::to_string(&head).expect("head json"),
            })
            .expect("state head record");
            let object_path = workspace.join(lomo_workspace::state_revision_path(
                &paths,
                &revision.revision_id,
            ));
            let head_path = workspace.join(lomo_workspace::state_head_path(&paths, memo_id));
            fs::create_dir_all(object_path.parent().expect("object dir")).expect("object dir");
            fs::create_dir_all(head_path.parent().expect("head dir")).expect("head dir");
            fs::write(object_path, object).expect("write state object");
            fs::write(head_path, head_record).expect("write state head");
        }

        // ---------- pin facts the cascade delete must heal ----------

        /// `apply_purged_id`'s row delete cascades `memo_pin`. The batch's answer is
        /// the `purged_upserts ∩ committed_trashed ∩ emitted_ids` gate pushing the
        /// identity back through `state_memos` so the durable pin tip lands on the
        /// reinserted row. A pinned dual-lane identity purged while its document is
        /// untouched must come back active AND pinned — exactly what a cold scan
        /// commits (tombstone suppresses the record only, pin tip still applies).
        #[test]
        fn a_purge_tombstone_over_a_pinned_dual_lane_memo_re_pins_the_reinserted_row() {
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
            commit_dual_lane(&ctx, &victim);

            write_purge_tombstone(&ctx.workspace, &victim, "r5-purge");
            ctx.session
                .rebuild_projection()
                .expect("the scoped purge converges and re-pins");

            assert_equivalent_to_fresh_scan(&ctx);
            let summary = summaries(&ctx.session, false)
                .into_iter()
                .find(|summary| summary.memo_id == victim)
                .expect("the document lane survives");
            assert!(
                summary.is_pinned && !summary.is_trashed,
                "the tombstone may kill the trash lane only — the reinserted \
                 document row must get its durable pin back"
            );
        }

        /// The `trash_removed` arm's `retire_memo` already re-pushes `state_memos`
        /// unconditionally: a pinned dual-lane identity whose record is deleted
        /// must come back active AND pinned — the pin row the cascade took is
        /// re-derived from the durable tip, as a cold scan does.
        #[test]
        fn a_removed_trash_record_over_a_pinned_dual_lane_memo_restores_lane_and_pin() {
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
            commit_dual_lane(&ctx, &victim);

            remove_trash_record(&ctx.workspace, &victim);
            ctx.session
                .rebuild_projection()
                .expect("the scoped removal converges and re-pins");

            assert_equivalent_to_fresh_scan(&ctx);
            let summary = summaries(&ctx.session, false)
                .into_iter()
                .find(|summary| summary.memo_id == victim)
                .expect("the document lane is restored");
            assert!(
                summary.is_pinned && !summary.is_trashed,
                "the record was the only trash authority — the restored document \
                 row must get its durable pin back"
            );
        }

        // ---------- the admitted wedge, probed end to end ----------

        /// The F5-1 wedge closed by the cold-side pin axiom: `retire_memo` still
        /// re-pushes `state_memos` unconditionally, but a pinned tip over an
        /// identity that retires and is re-emitted by nothing is now a dead pin
        /// fact — the re-walk degrades it to `pin_removes` instead of owing a
        /// `pin_upserts` to a deleted parent row. An app-trashed pinned echo
        /// (doc block removed, durable state tip still `pinned=true`) whose
        /// record is deleted retires dead: the incremental pass must converge,
        /// and a fresh materialize of the same durable facts must produce the
        /// identical projection — the identity leaves both with no row and no pin.
        #[test]
        fn a_retired_pinned_echo_converges_incremental_and_cold_identically() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "pinned echo")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let echo = memo_by_body(&ctx, "pinned echo");
            pin_active(&ctx, &echo, "pin-echo");
            app_trash(&ctx, &echo, "del-echo");
            assert_equivalent_to_fresh_scan(&ctx);

            // Peer-style record deletion: the only trash authority leaves, no
            // document emits the identity, and the durable state tip still says
            // pinned — the retire meets a dead pin fact, not a landable one.
            remove_trash_record(&ctx.workspace, &echo);
            ctx.session
                .rebuild_projection()
                .expect("a dead pinned tip must not wedge the scoped reconcile");
            assert_equivalent_to_fresh_scan(&ctx);
        }

        /// The purge arm reaches the same dead pin fact through the widen itself:
        /// the `purged_upserts ∩ committed_trashed` arm inserts the live document
        /// into `docs_changed`, the rescan does not re-emit the echo, and
        /// `documents()` retires it through `retire_memo`. The landing tombstone
        /// suppression plus the dead tip still converge on both paths — the cold
        /// scan emits nothing for the identity either.
        #[test]
        fn a_purge_tombstone_over_a_pinned_echo_converges_incremental_and_cold_identically() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "pinned echo")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let echo = memo_by_body(&ctx, "pinned echo");
            pin_active(&ctx, &echo, "pin-echo");
            app_trash(&ctx, &echo, "del-echo");
            assert_equivalent_to_fresh_scan(&ctx);

            write_purge_tombstone(&ctx.workspace, &echo, "r5-purge-echo");
            ctx.session
                .rebuild_projection()
                .expect("a tombstoned pinned echo must not wedge the scoped reconcile");
            assert_equivalent_to_fresh_scan(&ctx);
        }

        /// The F5-1 wedge's widest arm — no lane arm at all. `commit_transaction`
        /// never updates `file_listing`, so the state head a pin+trash+permanent-
        /// delete sequence wrote stays a pending diff: the next reconcile hits
        /// `state_heads`, re-walks the pinned tip for a row the mutation already
        /// deleted, and must degrade it to `pin_removes` instead of owing an
        /// un-landable `pin_upserts`. The same durable facts cold-materialize to
        /// the identical empty-pin projection.
        #[test]
        fn a_permanently_deleted_pinned_memo_converges_the_next_reconcile_too() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "doomed")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let doomed = memo_by_body(&ctx, "doomed");
            pin_active(&ctx, &doomed, "pin-doomed");

            // No reconcile between trash and permanent delete: the durable
            // writes of both operations are still pending diffs, so the state
            // head diff reaches the pin re-walk with the parent row already gone.
            let summary = summaries(&ctx.session, false)
                .into_iter()
                .find(|summary| summary.memo_id == doomed)
                .expect("active summary");
            ctx.session
                .delete_memo(DeleteMemoRequest {
                    operation_id: OperationId::parse("del-doomed").expect("operation id"),
                    memo_id: MemoId::parse(&doomed).expect("memo id"),
                    expected_document_fingerprint: summary.file_fingerprint,
                    trashed_at_ms: Some(1_757_500_000_000),
                })
                .expect("app trash");
            ctx.session
                .permanently_delete_memo(&PermanentDeleteRequest {
                    operation_id: OperationId::parse("purge-doomed").expect("operation id"),
                    memo_id: MemoId::parse(&doomed).expect("memo id"),
                })
                .expect("permanent delete");

            ctx.session
                .rebuild_projection()
                .expect("a dead pinned tip must not wedge the scoped reconcile");
            assert_equivalent_to_fresh_scan(&ctx);
        }

        /// The F5-2 asymmetric form, now closed: with the pin+trash writes already
        /// reconciled away, the permanent-delete diff is only record-removal +
        /// tombstone — no lane arm retires the identity, no state arm re-walks its
        /// tip — and the same durable facts a live session commits must also
        /// materialize cold. `pins()` now drops the orphaned tip as a dead pin
        /// fact instead of handing `append_pin_page` an FK violation, so the
        /// workspace opens identically on every new cache.
        #[test]
        fn a_permanently_deleted_pinned_memo_materializes_cold_identically() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "doomed")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let doomed = memo_by_body(&ctx, "doomed");
            pin_active(&ctx, &doomed, "pin-doomed");
            app_trash(&ctx, &doomed, "del-doomed");
            ctx.session
                .permanently_delete_memo(&PermanentDeleteRequest {
                    operation_id: OperationId::parse("purge-doomed").expect("operation id"),
                    memo_id: MemoId::parse(&doomed).expect("memo id"),
                })
                .expect("permanent delete");

            ctx.session
                .rebuild_projection()
                .expect("the live session stays converged");
            assert_equivalent_to_fresh_scan(&ctx);
        }

        /// The purest dead-pin fact: a peer-delivered state head whose tip says
        /// `pinned=true` for an identity nothing else in the workspace emits — no
        /// document block, no trash record, not even a committed row. The scoped
        /// `state_heads` arm re-walks it to a dead fact and the cold `pins()`
        /// filter drops it; both paths converge on "no such identity".
        #[test]
        fn a_pinned_state_tip_over_a_rowless_identity_converges_both_paths() {
            let scratch = tempdir().expect("workspace");
            write_doc(scratch.path(), "2026_09_10.md", &[("10:00:00", "stayer")]);
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");

            write_pinned_state_tip(&ctx.workspace, "ghost-pinned", 1_757_500_000_000);
            ctx.session
                .rebuild_projection()
                .expect("a rowless pinned tip must not wedge the scoped reconcile");
            assert_equivalent_to_fresh_scan(&ctx);
            assert!(
                !projected_ids(&ctx, false).contains("ghost-pinned")
                    && !projected_ids(&ctx, true).contains("ghost-pinned"),
                "a dead pinned tip projects no identity on either lane"
            );
        }

        // ---------- intersection semantics the widen must not touch ----------

        /// `purged_upserts ∩ committed_trashed` is exactly the row set
        /// `apply_purged_id` deletes. A tombstone over a pure document row (no
        /// `memo_trash` membership) suppresses records only — the row is neither
        /// widened nor deleted, matching the cold scan's answer byte for byte.
        #[test]
        fn a_purge_tombstone_over_a_pure_document_row_suppresses_only_records() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "doc only victim")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "doc only victim");

            // A peer-delivered tombstone for an identity that was never trashed:
            // no membership means `apply_purged_id` must leave the row standing.
            write_purge_tombstone(&ctx.workspace, &victim, "r5-pure-purge");
            ctx.session
                .rebuild_projection()
                .expect("the scoped purge keeps the document row");
            assert!(
                projected_ids(&ctx, false).contains(&victim),
                "the document row must survive a tombstone it never asked for"
            );
            assert_equivalent_to_fresh_scan(&ctx);
        }

        /// The fourth arbitration path: `purge_removed` lifting the tombstone over
        /// a purged identity while its durable claim record is still live. The
        /// lifted suppression lets the record claim again — the same row a cold
        /// scan produces once the tombstone is gone. (The app-level delete on a
        /// tombstoned identity retires the row outright, so the record waits
        /// suppressed in durable state until the lift.)
        #[test]
        fn a_lifted_tombstone_reclaims_the_suppressed_record() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "reclaimed")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let reclaimed = memo_by_body(&ctx, "reclaimed");

            // Peer tombstone over the pure document row: it commits `purged_memo`
            // while the row stays active.
            write_purge_tombstone(&ctx.workspace, &reclaimed, "r5-lift");
            ctx.session
                .rebuild_projection()
                .expect("the tombstone commits");
            assert_equivalent_to_fresh_scan(&ctx);

            // The app trashes the tombstoned identity: the mutation retires the
            // row outright and leaves the suppressed record in durable state.
            let summary = summaries(&ctx.session, false)
                .into_iter()
                .find(|summary| summary.memo_id == reclaimed)
                .expect("active summary");
            ctx.session
                .delete_memo(DeleteMemoRequest {
                    operation_id: OperationId::parse("del-reclaimed").expect("operation id"),
                    memo_id: MemoId::parse(&reclaimed).expect("memo id"),
                    expected_document_fingerprint: summary.file_fingerprint,
                    trashed_at_ms: Some(1_757_500_000_000),
                })
                .expect("app delete on a tombstoned memo retires the row");
            ctx.session
                .rebuild_projection()
                .expect("the suppressed record stays suppressed");
            assert!(
                !projected_ids(&ctx, false).contains(&reclaimed)
                    && !projected_ids(&ctx, true).contains(&reclaimed),
                "fixture sanity: the tombstoned identity left the projection"
            );

            // Peer lift: the tombstone disappears, the record speaks again.
            remove_purge_tombstone(&ctx.workspace, &reclaimed);
            ctx.session
                .rebuild_projection()
                .expect("the lifted suppression lets the record claim");
            assert_equivalent_to_fresh_scan(&ctx);
            assert!(
                projected_ids(&ctx, true).contains(&reclaimed),
                "the reclaimed identity must be trashed again — the durable \
                 record outlives the lifted tombstone"
            );
        }

        /// The same lift with the record removed in the very same pass: no
        /// authority remains. The removed record resolves to neither a committed
        /// trash owner nor an effective purge owner — the scope cannot be proven,
        /// so the pass must fall back to the full scan rather than strand a stale
        /// `purged_memo` row or a ghost identity.
        #[test]
        fn a_lifted_tombstone_plus_a_removed_record_falls_back_to_the_full_scan() {
            let scratch = tempdir().expect("workspace");
            write_doc(
                scratch.path(),
                "2026_09_10.md",
                &[("10:00:00", "stayer"), ("10:01:00", "twice dead")],
            );
            let ctx = open_session(scratch.path());
            ctx.session.rebuild_projection().expect("seed");
            let victim = memo_by_body(&ctx, "twice dead");

            write_purge_tombstone(&ctx.workspace, &victim, "r5-lift-dead");
            ctx.session
                .rebuild_projection()
                .expect("the tombstone commits");
            assert_equivalent_to_fresh_scan(&ctx);

            // Tombstone and a never-claimed ghost record leave in one pass —
            // nothing re-attests, and the removed record's owner resolves to
            // neither lane's committed set.
            write_trash_record(
                &ctx.workspace,
                &ghost_record(&victim, "2026_09_10.md", b"doc", 1_757_500_000_000),
            );
            ctx.session
                .rebuild_projection()
                .expect("the suppressed record cannot project");
            remove_purge_tombstone(&ctx.workspace, &victim);
            remove_trash_record(&ctx.workspace, &victim);
            ctx.session
                .rebuild_projection()
                .expect("the unclassifiable scope falls back and converges");
            assert_equivalent_to_fresh_scan(&ctx);
            assert!(
                projected_ids(&ctx, false).contains(&victim),
                "the document still emits the identity — the lift only freed the \
                 suppression; the live doc lane keeps the row, exactly as a cold \
                 scan reports"
            );
            assert!(
                !projected_ids(&ctx, true).contains(&victim),
                "no record survived the same-pass removal — nothing may re-trash it"
            );
        }
    }
}
