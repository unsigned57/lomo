//! Behavior Contract — P3-09 store `BoltFFI` dark-build surface
//!
//! - Unit under test: `LomoEngine::{query_memos,get_memo,apply_memo_command,query_reminder_plan,
//!   session_reminder_plan,session_record_reminder_fired,start_rebuild}`
//! - Owning layer: `lomo-native` (conversion only); rules in `lomo-store`
//! - Priority tier: P0
//! - Capability: expose store/reminder/rebuild through the unique `BoltFFI` facade without wiring
//!   production Kotlin DI dual-stack.
//!
//! Scenarios:
//! - Given a `Direct` workspace engine, when Markdown is seeded and rebuild runs, then
//!   `query_memos` lists the memo and `get_memo` returns it.
//! - Given `apply_memo_command` Create, when called, then `session_owns_document_writes` fails
//!   closed and no host identity is minted.
//! - Given `get_memo` for a missing id, when called, then [`None`] is returned.
//! - Given a dated memo with a past daily reminder token, when `query_reminder_plan` runs through
//!   the session, then at most one catch-up alarm is planned.
//! - Given that same memo, when `session_record_reminder_fired` runs, then the session write
//!   commits without a `StoreHandle` snooze database.
//! - Given engine open without a session, when `apply_memo_command` is refused, then no
//!   `.lomo-sqlite`, `control_root/store`, or `reminder_snooze` directory is created.
//! - Given `start_rebuild` on an empty workspace, when fingerprints already match, then the
//!   result reports `rewritten = false`. Given a seeded memo, when rebuild runs twice, then
//!   the first result rewrites and the second reconciles without advancing high-water.
//! - Given Direct create/update/restore/history-restore/pin/delete via FFI, when applied, then
//!   `session_owns_document_writes` fails closed.
//! - Given multi-memo pages with `page_size=1`, when the next cursor is reused, then the second
//!   page is disjoint; given a malformed cursor, when decoded, then `invalid_page_cursor`.
//! - Given a memo identity start, when `query_memos` runs, then the page is inclusive at that id
//!   with `items_before`/`items_after`; mixing identity with a cursor fails `invalid_page_start`.
//! - Given a SAF engine with no Direct root path, when a POSIX host is bound to the capability and
//!   the workspace session scans dated Markdown, then `query_memos`/`get_memo` return the minted
//!   identity and body from the session projection.
//! - Given that session cache and the same workspace files, when the engine is closed and reopened
//!   and a new session is bound, then the complete memo body remains queryable.
//! - Given session update then delete on that SAF scan, when the same `operation_id` is replayed,
//!   then the second update is `idempotent_replay`; trash-only query returns the minted identity.
//!
//! Test Change Justification: session scan mints CSPRNG ids and owns the only production SQLite
//! (`control_root/session/{identity}/cache`). `StoreHandle` streaming rebuild (`begin/append/finish`
//! SAF pages, hardcoded `2026-08-17_14:30:00_0` ids) was the second materialization T12 deletes;
//! these contracts now assert the session path instead of injecting a parallel store.
//! Test Change Justification: `apply_reminder_command` / injected `StoreReminderSession` matrices
//! used `StoreHandle` snooze at `control_root/reminder_snooze`. T12 routes planning and fire through
//! the session; MarkDone/Snooze/ClearSnooze conversion remains locked in `reminder_core_contract`.
//!
//! Observable outcomes: FFI DTO fields, structured `EngineError` codes.
//! TDD proof: RED before store methods exist on `LomoEngine`.
//! TDD proof: RED on 2026-08-06 because projection append retained every verified exchange body.
//! TDD proof: RED on 2026-08-09 because native SAF projection DTOs had no durable trash timestamp,
//! trash rebuild page, or restore/permanent-delete mapping.
//! TDD proof: RED on 2026-08-17 because SAF bodies lived only in the process-local `saf_bodies`
//! map, so reopening a valid projection returned `saf_store_body_unavailable`.
//! TDD proof: RED on 2026-09-16 because `query_memos`/`get_memo` fell back to `StoreHandle`
//! when no workspace session was open, so a second SQLite could answer reads.
//! TDD proof: RED on 2026-09-16 because `LomoEngine::open` constructed `StoreHandle` (snooze dir)
//! and `apply_memo_command` called `ensure_store_open`, creating workspace `.lomo-sqlite`.
//! Excludes: production DI cutover (P3-10), Room deletion.

#[cfg(test)]
mod support;

#[cfg(test)]
#[expect(
    clippy::too_many_lines,
    reason = "FFI lifecycle and reminder conversion matrices are intentionally long contracts"
)]
mod tests {
    use super::support::{OptionTestExt, ResultTestExt};
    use std::{
        fs,
        path::{Path, PathBuf},
    };

    use lomo_core::{CapabilityToken, PlatformActionExecutor};
    use lomo_native::{
        EngineConfig, EngineError, LomoEngine, PlatformActionBatch, PlatformBatchHost,
        PlatformBatchResult, SessionDeleteMemoRequest, SessionFireReminderRequest,
        SessionUpdateMemoRequest, StoreInvalidationScope, StoreMemoCommand, StoreMemoCommandKind,
        StoreMemoFilters, StoreMemoQuery, StoreMemoSort, StorePageCursor, WorkspaceDescriptor,
    };
    use lomo_platform_fs::PosixPlatformActionExecutor;
    use tempfile::tempdir;

    struct PosixBatchHost {
        executor: PosixPlatformActionExecutor,
    }

    impl PlatformBatchHost for PosixBatchHost {
        fn execute(&self, batch: PlatformActionBatch) -> Result<PlatformBatchResult, EngineError> {
            let core_batch = lomo_native::batch_from_ffi(batch)?;
            let result = self
                .executor
                .execute(&core_batch)
                .map_err(EngineError::from)?;
            Ok(lomo_native::result_to_ffi(&result))
        }
    }

    fn open_engine_without_session() -> (tempfile::TempDir, PathBuf, LomoEngine) {
        let temporary = tempdir().test_ok("temp");
        let control = temporary.path().join("control");
        let exchange = temporary.path().join("exchange");
        let workspace = temporary.path().join("workspace");
        fs::create_dir_all(&control).test_ok("control");
        fs::create_dir_all(&exchange).test_ok("exchange");
        fs::create_dir_all(&workspace).test_ok("workspace");
        fs::create_dir_all(workspace.join("memos")).test_ok("memos");
        let engine = LomoEngine::open(EngineConfig {
            control_root: control.display().to_string(),
            exchange_root: exchange.display().to_string(),
            workspace: Some(WorkspaceDescriptor::Direct {
                root_path: workspace.display().to_string(),
                capability_token: "notes-root".to_owned(),
            }),
            bootstrap_deadline_millis: 30_000,
        })
        .test_ok("open");
        (temporary, workspace, engine)
    }

    fn bind_session(engine: &LomoEngine, workspace: &Path, exchange: &Path, token: &str) {
        let executor = PosixPlatformActionExecutor::new(exchange).test_ok("executor");
        executor
            .bind_root(
                CapabilityToken::parse(token).test_ok("capability"),
                workspace,
            )
            .test_ok("bind root");
        engine
            .open_workspace_session(Box::new(PosixBatchHost { executor }), "UTC".to_owned())
            .test_ok("open session");
    }

    fn open_engine() -> (tempfile::TempDir, PathBuf, LomoEngine) {
        let (temporary, workspace, engine) = open_engine_without_session();
        let exchange = temporary.path().join("exchange");
        bind_session(&engine, &workspace, &exchange, "notes-root");
        (temporary, workspace, engine)
    }

    fn seed_markdown(workspace: &Path, date_key: &str, content: &str) {
        fs::write(workspace.join(format!("{date_key}.md")), content).test_ok("write memo");
    }

    fn index_workspace(engine: &LomoEngine) {
        engine.start_rebuild(16).test_ok("rebuild seed");
    }

    fn create_command(operation_id: &str, memo_id: &str, content: &str) -> StoreMemoCommand {
        StoreMemoCommand {
            operation_id: operation_id.to_owned(),
            kind: StoreMemoCommandKind::Create,
            memo_id: memo_id.to_owned(),
            expected_revision: 0,
            expected_fingerprint: None,
            content: Some(content.to_owned()),
            tags: vec![],
            pin: None,
            pending_promotes: vec![],
            chronology_epoch_ms: None,
        }
    }

    fn open_saf_engine(
        identity: &str,
        token: &str,
        files: &[(&str, &str)],
    ) -> (tempfile::TempDir, LomoEngine) {
        let temporary = tempdir().test_ok("temp");
        let control = temporary.path().join("control");
        let exchange = temporary.path().join("exchange");
        let files_root = temporary.path().join("saf-files");
        fs::create_dir_all(&control).test_ok("control");
        fs::create_dir_all(&exchange).test_ok("exchange");
        fs::create_dir_all(&files_root).test_ok("saf files");
        for (name, body) in files {
            fs::write(files_root.join(name), body).test_ok("seed SAF markdown");
        }
        let engine = LomoEngine::open(EngineConfig {
            control_root: control.display().to_string(),
            exchange_root: exchange.display().to_string(),
            workspace: Some(WorkspaceDescriptor::Saf {
                stable_workspace_id: identity.to_owned(),
                capability_token: token.to_owned(),
            }),
            bootstrap_deadline_millis: 30_000,
        })
        .test_ok("open SAF engine");
        bind_session(&engine, &files_root, &exchange, token);
        (temporary, engine)
    }

    #[test]
    fn engine_open_does_not_materialize_a_second_sqlite() {
        let (temporary, workspace, engine) = open_engine_without_session();
        let control = temporary.path().join("control");
        assert!(
            !control.join("store").exists(),
            "engine open must not create control_root/store"
        );
        assert!(
            !control.join("reminder_snooze").exists(),
            "engine open must not create a StoreHandle snooze directory"
        );
        assert!(
            !workspace.join(".lomo-sqlite").exists(),
            "engine open must not open a workspace projection sqlite"
        );
        let error = engine
            .apply_memo_command(create_command(
                "op-no-second-store",
                "",
                "must not open sqlite",
            ))
            .test_err("refused write");
        assert_eq!(error.code(), "session_owns_document_writes");
        assert!(
            !workspace.join(".lomo-sqlite").exists(),
            "refusing Direct writes must not lazy-open a second sqlite"
        );
        assert!(!control.join("store").exists());
        assert!(!control.join("reminder_snooze").exists());
        assert!(
            !control.join("session").exists(),
            "refusing writes without a session must not mint session cache"
        );
    }

    #[test]
    fn query_memos_without_session_fails_closed() {
        let (_tmp, _workspace, engine) = open_engine_without_session();
        let error = engine
            .query_memos(StoreMemoQuery::default(), None, 10, None, false)
            .test_err("query without session");
        assert_eq!(error.code(), "workspace_session_unavailable");
        let missing = engine
            .get_memo("m-any".to_owned())
            .test_err("get without session");
        assert_eq!(missing.code(), "workspace_session_unavailable");
    }

    #[test]
    fn apply_memo_and_query_memos_round_trip_with_scopes() {
        let (_tmp, workspace, engine) = open_engine();
        seed_markdown(&workspace, "2026_08_06", "hello store ffi #tag/a");
        index_workspace(&engine);

        let page = engine
            .query_memos(
                StoreMemoQuery {
                    search_text: None,
                    filters: StoreMemoFilters::default(),
                    sort: StoreMemoSort::default(),
                    boundary: None,
                },
                None,
                32,
                None,
                false,
            )
            .test_ok("query");
        assert_eq!(page.items_before, 0);
        assert_eq!(page.items_after, 0);
        assert!(page.prev_cursor.is_none());
        assert!(page.next_cursor.is_none());
        assert_eq!(page.items.len(), 1);
        let memo_id = page.items.first().test_ok("seeded memo").memo_id.clone();

        let snap = engine.get_memo(memo_id).test_ok("get").test_ok("snapshot");
        assert!(snap.body.contains("hello store ffi"));
        let missing = engine.get_memo("nope".to_owned()).test_ok("missing");
        assert!(missing.is_none());
        let sidebar = engine.sidebar_projection().test_ok("sidebar aggregate");
        assert_eq!(sidebar.schema_version, 1);
        assert_eq!(sidebar.memo_count, 1);
        let tag = sidebar.tag_counts.first().cloned().test_ok("sidebar tag");
        assert_eq!(tag.name, "tag/a");
        assert_eq!(tag.count, 1);
    }

    #[test]
    fn direct_create_fails_closed_because_session_owns_document_writes() {
        let (_tmp, _workspace, engine) = open_engine();
        let command = create_command(
            "op-ffi-direct-identity",
            "",
            "created through the native facade",
        );
        let error = engine.apply_memo_command(command).test_err("direct create");
        assert_eq!(error.code(), "session_owns_document_writes");
    }

    #[test]
    fn saf_scan_projection_is_queryable_without_a_direct_workspace_path() {
        let body = "readable body from SAF #device";
        let (_tmp, engine) = open_saf_engine(
            "ws-saf-store-test",
            "cap-saf-store-test",
            &[("2026_08_02.md", body)],
        );
        let page = engine
            .query_memos(StoreMemoQuery::default(), None, 10, None, false)
            .test_ok("query SAF projection");
        assert_eq!(page.items.len(), 1);
        let memo_id = page.items.first().test_ok("SAF memo").memo_id.clone();
        let memo = engine
            .get_memo(memo_id)
            .test_ok("get SAF memo")
            .test_ok("SAF memo snapshot");
        assert_eq!(memo.body, body);
        assert_eq!(
            engine
                .source_document_fingerprint("2026_08_02.md".to_owned())
                .test_ok("source document fingerprint"),
            Some(lomo_store::fingerprint_content(body))
        );
    }

    #[test]
    fn saf_projection_body_survives_engine_reopen_without_workspace_scan() {
        let temporary = tempdir().test_ok("temp");
        let control = temporary.path().join("control");
        let exchange = temporary.path().join("exchange");
        let files_root = temporary.path().join("saf-files");
        fs::create_dir_all(&control).test_ok("control");
        fs::create_dir_all(&exchange).test_ok("exchange");
        fs::create_dir_all(&files_root).test_ok("files");
        let body = "durable SAF body after process restart";
        fs::write(files_root.join("2026_08_17.md"), body).test_ok("seed");
        let config = EngineConfig {
            control_root: control.display().to_string(),
            exchange_root: exchange.display().to_string(),
            workspace: Some(WorkspaceDescriptor::Saf {
                stable_workspace_id: "ws-saf-reopen-test".to_owned(),
                capability_token: "cap-saf-reopen-test".to_owned(),
            }),
            bootstrap_deadline_millis: 30_000,
        };
        let engine = LomoEngine::open(config.clone()).test_ok("open SAF engine");
        bind_session(&engine, &files_root, &exchange, "cap-saf-reopen-test");
        let memo_id = engine
            .query_memos(StoreMemoQuery::default(), None, 8, None, false)
            .test_ok("initial query")
            .items
            .into_iter()
            .next()
            .test_ok("seeded memo")
            .memo_id;
        drop(engine);

        let reopened = LomoEngine::open(config).test_ok("reopen SAF engine");
        bind_session(&reopened, &files_root, &exchange, "cap-saf-reopen-test");
        let memo = reopened
            .get_memo(memo_id)
            .test_ok("read reopened SAF memo")
            .test_ok("reopened SAF memo snapshot");
        assert_eq!(memo.body, body);
    }

    #[test]
    fn saf_projection_mutation_ffi_is_idempotent_and_supports_verified_trash_lifecycle() {
        let body = "old body";
        let (_tmp, engine) = open_saf_engine(
            "ws-saf-mutation-test",
            "cap-saf-mutation-test",
            &[("2026_08_04.md", body)],
        );
        let memo_id = engine
            .query_memos(StoreMemoQuery::default(), None, 8, None, false)
            .test_ok("seeded")
            .items
            .into_iter()
            .next()
            .test_ok("memo")
            .memo_id;
        let seeded = engine
            .get_memo(memo_id.clone())
            .test_ok("get seed")
            .test_ok("present");
        let updated = engine
            .session_update_memo(SessionUpdateMemoRequest {
                operation_id: "ffi-saf-update".to_owned(),
                memo_id: memo_id.clone(),
                content: "updated body".to_owned(),
                expected_document_fingerprint: seeded.summary.file_fingerprint.clone(),
                pending_promotes: Vec::new(),
            })
            .test_ok("session update");
        let replay = engine
            .session_update_memo(SessionUpdateMemoRequest {
                operation_id: "ffi-saf-update".to_owned(),
                memo_id: memo_id.clone(),
                content: "updated body".to_owned(),
                expected_document_fingerprint: seeded.summary.file_fingerprint,
                pending_promotes: Vec::new(),
            })
            .test_ok("session update replay");
        assert!(!updated.idempotent_replay);
        assert!(replay.idempotent_replay);
        assert_eq!(
            engine
                .get_memo(memo_id.clone())
                .test_ok("get updated")
                .test_ok("updated memo")
                .body,
            "updated body"
        );
        let deleted = engine
            .session_delete_memo(SessionDeleteMemoRequest {
                operation_id: "ffi-saf-delete".to_owned(),
                memo_id: memo_id.clone(),
                expected_document_fingerprint: updated.file_fingerprint,
            })
            .test_ok("session delete");
        assert!(deleted.scopes.contains(&StoreInvalidationScope::Trash));
        let trash = engine
            .query_memos(
                StoreMemoQuery {
                    filters: StoreMemoFilters {
                        include_trash: true,
                        trash_only: true,
                        ..StoreMemoFilters::default()
                    },
                    ..StoreMemoQuery::default()
                },
                None,
                10,
                None,
                false,
            )
            .test_ok("query SAF trash");
        assert_eq!(trash.items.len(), 1);
        assert_eq!(trash.items.first().test_ok("trashed memo").memo_id, memo_id);
    }

    #[test]
    fn saf_trash_projection_rebuild_merges_recoverable_body_with_active_fingerprint() {
        let (_tmp, engine) = open_saf_engine(
            "ws-saf-trash-rebuild-test",
            "cap-saf-trash-rebuild-test",
            &[("2026_08_05.md", "active then trashed")],
        );
        let memo_id = engine
            .query_memos(StoreMemoQuery::default(), None, 8, None, false)
            .test_ok("seeded")
            .items
            .into_iter()
            .next()
            .test_ok("memo")
            .memo_id;
        let seeded = engine
            .get_memo(memo_id.clone())
            .test_ok("get seed")
            .test_ok("present");
        engine
            .session_delete_memo(SessionDeleteMemoRequest {
                operation_id: "ffi-saf-trash".to_owned(),
                memo_id: memo_id.clone(),
                expected_document_fingerprint: seeded.summary.file_fingerprint,
            })
            .test_ok("trash");
        let snapshot = engine
            .get_memo(memo_id)
            .test_ok("get trashed memo")
            .test_ok("trashed memo snapshot");
        assert!(snapshot.summary.is_trashed);
        assert_eq!(snapshot.body, "active then trashed");
    }

    #[test]
    fn saf_projection_streaming_ffi_reads_exchange_body_only_in_rust() {
        let body = "streamed body from exchange";
        let (_tmp, engine) = open_saf_engine(
            "ws-saf-stream-test",
            "cap-saf-stream-test",
            &[("2026_08_04.md", body)],
        );
        let memo_id = engine
            .query_memos(StoreMemoQuery::default(), None, 8, None, false)
            .test_ok("query streamed")
            .items
            .into_iter()
            .next()
            .test_ok("streamed memo")
            .memo_id;
        let memo = engine
            .get_memo(memo_id.clone())
            .test_ok("get streamed memo")
            .test_ok("streamed memo snapshot");
        assert_eq!(memo.body, body);
        engine.start_rebuild(16).test_ok("rebuild while live");
        assert!(
            engine
                .get_memo(memo_id)
                .test_ok("query live projection after rebuild")
                .is_some()
        );
    }

    #[test]
    fn reminder_plan_and_snooze_command_via_ffi() {
        let (_tmp, workspace, engine) = open_engine();
        seed_markdown(
            &workspace,
            "2026_08_09",
            "catch-up reminder @2020-01-01-09:00rd",
        );
        index_workspace(&engine);
        let plan = engine
            .session_reminder_plan(Some(1_700_000_000_000))
            .test_ok("plan");
        let catch_ups = plan.alarms.iter().filter(|alarm| alarm.is_catch_up).count();
        assert_eq!(
            catch_ups, 1,
            "catch-up storm prevention via session plan: {:?}",
            plan.alarms
        );
    }

    #[test]
    fn start_rebuild_returns_result() {
        let (_tmp, workspace, engine) = open_engine();
        let empty = engine.start_rebuild(16).test_ok("empty reconcile");
        assert!(!empty.rewritten);
        seed_markdown(&workspace, "2026_08_07", "rebuild body");
        let first = engine.start_rebuild(16).test_ok("rewrite");
        assert!(first.rewritten);
        assert!(first.high_water_revision >= 1);
        let second = engine.start_rebuild(16).test_ok("reconcile");
        assert!(!second.rewritten);
        assert_eq!(second.high_water_revision, first.high_water_revision);
    }

    #[test]
    fn memo_command_kinds_and_filters_round_trip_via_ffi() {
        let (_tmp, workspace, engine) = open_engine();
        let create_error = engine
            .apply_memo_command(create_command(
                "op-ffi-create",
                "m-kind",
                "seed\n- [ ] task\nhttps://lomo.example #k #u",
            ))
            .test_err("direct create");
        assert_eq!(create_error.code(), "session_owns_document_writes");

        seed_markdown(
            &workspace,
            "2026_08_08",
            "seed\n- [ ] task\nhttps://lomo.example #k #u",
        );
        index_workspace(&engine);
        let seeded_id = engine
            .query_memos(StoreMemoQuery::default(), None, 8, None, false)
            .test_ok("seeded page")
            .items
            .into_iter()
            .next()
            .test_ok("seeded memo")
            .memo_id;
        let seeded = engine
            .get_memo(seeded_id.clone())
            .test_ok("get seed")
            .test_ok("present");

        let update_error = engine
            .apply_memo_command(StoreMemoCommand {
                operation_id: "op-ffi-update".to_owned(),
                kind: StoreMemoCommandKind::Update,
                memo_id: seeded_id.clone(),
                expected_revision: seeded.summary.content_revision,
                expected_fingerprint: Some(seeded.summary.file_fingerprint.clone()),
                content: Some("updated body".to_owned()),
                tags: vec![],
                pin: None,
                pending_promotes: vec![],
                chronology_epoch_ms: None,
            })
            .test_err("direct update");
        assert_eq!(update_error.code(), "session_owns_document_writes");

        let history_error = engine
            .apply_memo_command(StoreMemoCommand {
                operation_id: "op-ffi-hist".to_owned(),
                kind: StoreMemoCommandKind::HistoryRestore,
                memo_id: seeded_id.clone(),
                expected_revision: seeded.summary.content_revision,
                expected_fingerprint: Some(seeded.summary.file_fingerprint.clone()),
                content: Some("history via ffi".to_owned()),
                tags: vec![],
                pin: None,
                pending_promotes: vec![],
                chronology_epoch_ms: None,
            })
            .test_err("direct history restore");
        assert_eq!(history_error.code(), "session_owns_document_writes");

        let pin_error = engine
            .apply_memo_command(StoreMemoCommand {
                operation_id: "op-ffi-pin".to_owned(),
                kind: StoreMemoCommandKind::Pin,
                memo_id: seeded_id.clone(),
                expected_revision: seeded.summary.content_revision,
                expected_fingerprint: None,
                content: None,
                tags: vec![],
                pin: Some(true),
                pending_promotes: vec![],
                chronology_epoch_ms: None,
            })
            .test_err("direct pin");
        assert_eq!(pin_error.code(), "session_owns_document_writes");

        let deleted = engine
            .apply_memo_command(StoreMemoCommand {
                operation_id: "op-ffi-del".to_owned(),
                kind: StoreMemoCommandKind::Delete,
                memo_id: seeded_id.clone(),
                expected_revision: seeded.summary.content_revision,
                expected_fingerprint: None,
                content: None,
                tags: vec![],
                pin: None,
                pending_promotes: vec![],
                chronology_epoch_ms: None,
            })
            .test_err("direct delete");
        assert_eq!(deleted.code(), "session_owns_document_writes");

        let live = engine
            .get_memo(seeded_id.clone())
            .test_ok("get after refused delete")
            .test_ok("still present");
        assert!(!live.summary.is_trashed);
        assert!(!live.summary.is_pinned);

        let restore_error = engine
            .apply_memo_command(StoreMemoCommand {
                operation_id: "op-ffi-restore".to_owned(),
                kind: StoreMemoCommandKind::Restore,
                memo_id: seeded_id,
                expected_revision: seeded.summary.content_revision,
                expected_fingerprint: None,
                content: None,
                tags: vec![],
                pin: None,
                pending_promotes: vec![],
                chronology_epoch_ms: None,
            })
            .test_err("direct restore");
        assert_eq!(restore_error.code(), "session_owns_document_writes");
    }

    #[test]
    fn page_cursor_round_trip_and_invalid_cursor_fail_closed() {
        let (_tmp, workspace, engine) = open_engine();
        for i in 0..3 {
            seed_markdown(
                &workspace,
                &format!("2026_09_0{}", i + 1),
                &format!("needle {} body {i}", "needle ".repeat(i)),
            );
        }
        index_workspace(&engine);

        let first = engine
            .query_memos(
                StoreMemoQuery {
                    search_text: None,
                    filters: StoreMemoFilters::default(),
                    sort: StoreMemoSort::default(),
                    boundary: None,
                },
                None,
                1,
                None,
                false,
            )
            .test_ok("page1");
        assert_eq!(first.items.len(), 1);
        let cursor = first
            .next_cursor
            .clone()
            .test_ok("must page when more rows exist");
        assert!(
            cursor.encoded.split('|').count() == 8,
            "cursor wire form: {}",
            cursor.encoded
        );
        assert_eq!(cursor.encoded.split('|').nth(1), Some("none"));

        let second = engine
            .query_memos(
                StoreMemoQuery {
                    search_text: None,
                    filters: StoreMemoFilters::default(),
                    sort: StoreMemoSort::default(),
                    boundary: None,
                },
                Some(cursor),
                1,
                None,
                false,
            )
            .test_ok("page2");
        assert_eq!(second.items.len(), 1);
        let first_id = first
            .items
            .first()
            .map(|m| m.memo_id.as_str())
            .test_ok("first page item");
        let second_id = second
            .items
            .first()
            .map(|m| m.memo_id.as_str())
            .test_ok("second page item");
        assert_ne!(first_id, second_id, "pages must be disjoint");

        let fts_first = engine
            .query_memos(
                StoreMemoQuery {
                    search_text: Some("needle".to_owned()),
                    filters: StoreMemoFilters::default(),
                    sort: StoreMemoSort::default(),
                    boundary: None,
                },
                None,
                1,
                None,
                false,
            )
            .test_ok("FTS page1");
        let fts_cursor = fts_first.next_cursor.test_ok("FTS must page");
        assert_ne!(fts_cursor.encoded.split('|').nth(1), Some("none"));
        let fts_second = engine
            .query_memos(
                StoreMemoQuery {
                    search_text: Some("needle".to_owned()),
                    filters: StoreMemoFilters::default(),
                    sort: StoreMemoSort::default(),
                    boundary: None,
                },
                Some(fts_cursor),
                1,
                None,
                false,
            )
            .test_ok("FTS page2");
        assert_eq!(fts_second.items.len(), 1);
        assert_ne!(
            fts_first.items.first().map(|item| &item.memo_id),
            fts_second.items.first().map(|item| &item.memo_id),
        );

        let bad = engine
            .query_memos(
                StoreMemoQuery {
                    search_text: None,
                    filters: StoreMemoFilters::default(),
                    sort: StoreMemoSort::default(),
                    boundary: None,
                },
                Some(StorePageCursor {
                    encoded: "not|a|valid".to_owned(),
                }),
                1,
                None,
                false,
            )
            .test_err("malformed cursor");
        assert_eq!(bad.code(), "invalid_page_cursor");

        let bad_num = engine
            .query_memos(
                StoreMemoQuery {
                    search_text: None,
                    filters: StoreMemoFilters::default(),
                    sort: StoreMemoSort::default(),
                    boundary: None,
                },
                Some(StorePageCursor {
                    encoded: "fp|none|0|not-i64|1|id|1|1".to_owned(),
                }),
                1,
                None,
                false,
            )
            .test_err("non-i64 sort");
        assert_eq!(bad_num.code(), "invalid_page_cursor");

        for bad in [
            "fp|not-u64-bits|0|1|1|id|1|1",
            "fp|none|0|1|1|id|1",
            "fp|none|2|1|1|id|1|1",
            "fp|none|0|1|not-i64|id|1|1",
            "fp|none|0|1|1|id|not-u64|1",
            "fp|none|0|1|1|id|1|not-u32",
            "fp|none|0|1|1|id|1|1|extra",
        ] {
            let err = engine
                .query_memos(
                    StoreMemoQuery {
                        search_text: None,
                        filters: StoreMemoFilters::default(),
                        sort: StoreMemoSort::default(),
                        boundary: None,
                    },
                    Some(StorePageCursor {
                        encoded: bad.to_owned(),
                    }),
                    1,
                    None,
                    false,
                )
                .test_err(bad);
            assert_eq!(err.code(), "invalid_page_cursor", "cursor={bad}");
        }
    }

    #[test]
    fn query_memos_identity_start_reports_rank_and_rejects_mixed_start() {
        let (_tmp, workspace, engine) = open_engine();
        for date in [
            "2026_09_01",
            "2026_09_02",
            "2026_09_03",
            "2026_09_04",
            "2026_09_05",
        ] {
            seed_markdown(&workspace, date, date);
        }
        index_workspace(&engine);
        let query = StoreMemoQuery {
            search_text: None,
            filters: StoreMemoFilters::default(),
            sort: StoreMemoSort::default(),
            boundary: None,
        };
        let ordered = engine
            .query_memos(query.clone(), None, 10, None, false)
            .test_ok("full order");
        assert_eq!(ordered.items.len(), 5);
        assert_eq!(ordered.items_before, 0);
        assert_eq!(ordered.items_after, 0);

        let start_id = ordered
            .items
            .get(2)
            .map(|memo| memo.memo_id.clone())
            .test_ok("third row");
        let from_identity = engine
            .query_memos(query.clone(), None, 2, Some(start_id.clone()), false)
            .test_ok("identity start");
        assert_eq!(
            from_identity
                .items
                .first()
                .map(|memo| memo.memo_id.as_str()),
            Some(start_id.as_str())
        );
        assert_eq!(from_identity.items_before, 2);
        assert_eq!(from_identity.items_after, 1);
        assert!(from_identity.prev_cursor.is_some());
        assert!(from_identity.next_cursor.is_some());

        let mixed = engine
            .query_memos(query, from_identity.next_cursor, 2, Some(start_id), false)
            .test_err("cursor plus identity");
        assert_eq!(mixed.code(), "invalid_page_start");
    }

    #[test]
    fn tag_subtree_selection_survives_native_boundary() {
        let (_tmp, workspace, engine) = open_engine();
        for (date, tag) in [
            ("2026_09_10", "work"),
            ("2026_09_11", "work/project"),
            ("2026_09_12", "workspace"),
        ] {
            seed_markdown(&workspace, date, &format!("{date} #{tag}"));
        }
        index_workspace(&engine);
        let page = engine
            .query_memos(
                StoreMemoQuery {
                    search_text: None,
                    filters: StoreMemoFilters {
                        tag: Some("work".to_owned()),
                        tag_subtree: true,
                        ..StoreMemoFilters::default()
                    },
                    sort: StoreMemoSort::default(),
                    boundary: None,
                },
                None,
                10,
                None,
                false,
            )
            .test_ok("query tag subtree");
        let paths = page
            .items
            .iter()
            .map(|item| item.source_path.as_str())
            .collect::<std::collections::BTreeSet<_>>();
        assert_eq!(
            paths,
            std::collections::BTreeSet::from(["2026_09_10.md", "2026_09_11.md"])
        );
    }

    #[test]
    fn reminder_commands_and_zone_transitions_via_ffi() {
        let (_tmp, workspace, engine) = open_engine();
        seed_markdown(&workspace, "2026_08_10", "fire me @2024-06-01-15:00");
        index_workspace(&engine);
        let plan = engine
            .session_reminder_plan(Some(1_700_000_000_000))
            .test_ok("session plan");
        assert!(!plan.alarms.is_empty());
        let alarm = plan.alarms.first().cloned().test_ok("planned alarm");
        let memo_id = engine
            .query_memos(StoreMemoQuery::default(), None, 8, None, false)
            .test_ok("seeded")
            .items
            .into_iter()
            .next()
            .test_ok("memo")
            .memo_id;
        engine
            .session_record_reminder_fired(SessionFireReminderRequest {
                operation_id: "ffi-fire-1".to_owned(),
                memo_id,
                opaque_id: alarm.opaque_id,
            })
            .test_ok("record fired through session");
    }
}
