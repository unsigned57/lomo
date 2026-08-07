//! Behavior Contract — P3-09 store `BoltFFI` dark-build surface
//!
//! - Unit under test: `LomoEngine::{query_memos,get_memo,apply_memo_command,query_reminder_plan,
//!   apply_reminder_command,start_rebuild}` + cursor encode/decode + reminder command conversion
//! - Owning layer: `lomo-native` (conversion only); rules in `lomo-store`
//! - Priority tier: P0
//! - Capability: expose store/reminder/rebuild through the unique `BoltFFI` facade without wiring
//!   production Kotlin DI dual-stack.
//!
//! Scenarios:
//! - Given a `Direct` workspace engine, when a memo is created via `apply_memo_command` and listed
//!   via `query_memos`, then the page contains the memo and commit scopes are non-empty.
//! - Given `get_memo` for a missing id, when called, then [`None`] is returned.
//! - Given a past daily reminder session, when `query_reminder_plan` runs, then at most one
//!   catch-up alarm is planned.
//! - Given snooze via `apply_reminder_command`, when planned, then `replacement_token` is absent
//!   (`snooze_only`).
//! - Given `start_rebuild`, when completed, then a rebuild result with high-water revision is
//!   returned.
//! - Given create→update→pin→delete→restore→unpin via FFI kinds, when queried, then DTO fields
//!   and scopes match the observable store state (including trash/pin scopes).
//! - Given multi-memo pages with `page_size=1`, when the next cursor is reused, then the second
//!   page is disjoint; given a malformed cursor, when decoded, then `invalid_page_cursor`.
//! - Given `MarkDone` / `RecordFired` / `ClearSnooze` with full fields, when applied, then replacement
//!   tokens or snooze-only flags match store rules; missing required fields fail closed.
//! - Given zone transitions on the reminder query, when planned, then the plan succeeds without
//!   dropping the session.
//! - Given a SAF engine and memo facts produced by the Rust workspace scan, when its app-private
//!   projection is rebuilt, then bounded queries and full in-memory snapshots are readable and
//!   each successfully appended single-consumer exchange body is removed.
//!
//! Observable outcomes: FFI DTO fields, structured `EngineError` codes.
//! TDD proof: RED before store methods exist on `LomoEngine`.
//! TDD proof: RED on 2026-08-06 because projection append retained every verified exchange body.
//! Excludes: production DI cutover (P3-10), Room deletion, device smoke.

#[cfg(test)]
mod support;

#[cfg(test)]
#[expect(
    clippy::too_many_lines,
    reason = "FFI lifecycle and reminder conversion matrices are intentionally long contracts"
)]
mod tests {
    use super::support::{OptionTestExt, ResultTestExt};
    use std::{fs, path::Path};

    use lomo_native::{
        EngineConfig, LomoEngine, StoreMemoCommand, StoreMemoCommandKind, StoreMemoFilters,
        StoreMemoQuery, StorePageCursor, StoreReminderCommand, StoreReminderCommandKind,
        StoreReminderQuery, StoreReminderSession, StoreSafMemoProjection,
        StoreSafMemoProjectionReference, StoreTimeZoneContext, StoreZoneTransition,
        WorkspaceDescriptor, WorkspaceMemoContentReference,
    };
    use tempfile::tempdir;

    fn open_engine() -> (tempfile::TempDir, LomoEngine) {
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
            }),
            bootstrap_deadline_millis: 30_000,
        })
        .test_ok("open");
        (temporary, engine)
    }

    fn stream_saf_projection(
        engine: &LomoEngine,
        exchange: &Path,
        memo_id: &str,
        source_path: &str,
        chronology_epoch_ms: i64,
        body: &str,
        tags: Vec<String>,
    ) -> lomo_native::StoreRebuildResult {
        let digest = lomo_store::fingerprint_content(body);
        let token = format!("ex.{digest}.body");
        fs::write(exchange.join(&token), body).test_ok("exchange body");
        let rebuild_id = engine
            .begin_saf_projection_rebuild()
            .test_ok("begin streaming rebuild");
        engine
            .append_saf_projection_rebuild_page(
                rebuild_id.clone(),
                vec![StoreSafMemoProjectionReference {
                    memo_id: memo_id.to_owned(),
                    source_path: source_path.to_owned(),
                    file_fingerprint: digest.clone(),
                    chronology_epoch_ms,
                    content: WorkspaceMemoContentReference {
                        exchange_token: token,
                        length: body.len() as u64,
                        digest,
                    },
                    tags,
                    attachment_paths: vec![],
                    has_todo: false,
                    has_url: false,
                    reminders: vec![],
                }],
            )
            .test_ok("append streaming page");
        engine
            .finish_saf_projection_rebuild(rebuild_id)
            .test_ok("finish streaming rebuild")
    }

    #[test]
    fn apply_memo_and_query_memos_round_trip_with_scopes() {
        let (_tmp, engine) = open_engine();
        let commit = engine
            .apply_memo_command(StoreMemoCommand {
                operation_id: "op-create-1".to_owned(),
                kind: StoreMemoCommandKind::Create,
                memo_id: "m-ffi-1".to_owned(),
                expected_revision: 0,
                expected_fingerprint: None,
                content: Some("hello store ffi".to_owned()),
                tags: vec!["tag/a".to_owned()],
                pin: None,
                pending_promotes: vec![],
                chronology_epoch_ms: None,
            })
            .test_ok("create");
        assert!(!commit.scopes.is_empty(), "commit must publish scopes");
        assert!(
            commit
                .scopes
                .iter()
                .any(|s| s == "memo_list" || s == "search")
        );

        let page = engine
            .query_memos(
                StoreMemoQuery {
                    search_text: None,
                    filters: StoreMemoFilters::default(),
                },
                None,
                32,
            )
            .test_ok("query");
        assert!(
            page.items.iter().any(|m| m.memo_id == "m-ffi-1"),
            "page={:?}",
            page.items.iter().map(|m| &m.memo_id).collect::<Vec<_>>()
        );

        let snap = engine.get_memo("m-ffi-1".to_owned()).test_ok("get");
        assert!(snap.is_some());
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
    fn saf_scan_projection_is_queryable_without_a_direct_workspace_path() {
        let temporary = tempdir().test_ok("temp");
        let control = temporary.path().join("control");
        let exchange = temporary.path().join("exchange");
        fs::create_dir_all(&control).test_ok("control");
        fs::create_dir_all(&exchange).test_ok("exchange");
        let engine = LomoEngine::open(EngineConfig {
            control_root: control.display().to_string(),
            exchange_root: exchange.display().to_string(),
            workspace: Some(WorkspaceDescriptor::Saf {
                stable_workspace_id: "ws-saf-store-test".to_owned(),
                capability_token: "cap-saf-store-test".to_owned(),
            }),
            bootstrap_deadline_millis: 30_000,
        })
        .test_ok("open SAF engine");
        let body = "readable body from SAF";

        let rebuilt = stream_saf_projection(
            &engine,
            &exchange,
            "2026-08-02_19:30:00_0",
            "2026-08-02.md",
            1_754_128_200_000,
            body,
            vec!["device".to_owned()],
        );
        let page = engine
            .query_memos(StoreMemoQuery::default(), None, 10)
            .test_ok("query SAF projection");
        let memo = engine
            .get_memo("2026-08-02_19:30:00_0".to_owned())
            .test_ok("get SAF memo")
            .test_ok("SAF memo snapshot");

        assert_eq!(rebuilt.memos_indexed, 1);
        assert_eq!(page.items.len(), 1);
        assert_eq!(memo.body, body);
    }

    #[test]
    fn saf_projection_mutation_ffi_is_idempotent_and_rejects_unplanned_restore() {
        let temporary = tempdir().test_ok("temp");
        let control = temporary.path().join("control");
        let exchange = temporary.path().join("exchange");
        fs::create_dir_all(&control).test_ok("control");
        fs::create_dir_all(&exchange).test_ok("exchange");
        let engine = LomoEngine::open(EngineConfig {
            control_root: control.display().to_string(),
            exchange_root: exchange.display().to_string(),
            workspace: Some(WorkspaceDescriptor::Saf {
                stable_workspace_id: "ws-saf-mutation-test".to_owned(),
                capability_token: "cap-saf-mutation-test".to_owned(),
            }),
            bootstrap_deadline_millis: 30_000,
        })
        .test_ok("open SAF engine");
        let old_body = "old body";
        let old_fingerprint = lomo_store::fingerprint_content(old_body);
        let memo_id = "2026_08_04_10:00:00_0".to_owned();
        stream_saf_projection(
            &engine,
            &exchange,
            &memo_id,
            "2026_08_04.md",
            1_754_300_000_000,
            old_body,
            vec![],
        );

        let updated_body = "updated body";
        let updated_fingerprint = lomo_store::fingerprint_content(updated_body);
        let update = StoreMemoCommand {
            operation_id: "ffi-saf-update".to_owned(),
            kind: StoreMemoCommandKind::Update,
            memo_id: memo_id.clone(),
            expected_revision: 1,
            expected_fingerprint: Some(old_fingerprint),
            content: None,
            tags: vec![],
            pin: None,
            pending_promotes: vec![],
            chronology_epoch_ms: None,
        };
        let projection = StoreSafMemoProjection {
            memo_id: memo_id.clone(),
            source_path: "2026_08_04.md".to_owned(),
            file_fingerprint: updated_fingerprint.clone(),
            chronology_epoch_ms: 1_754_300_001_000,
            body: updated_body.to_owned(),
            tags: vec!["updated".to_owned()],
            attachment_paths: vec![],
            has_todo: false,
            has_url: false,
            reminders: vec![],
        };
        let first = engine
            .commit_saf_projection_mutation(update.clone(), Some(projection.clone()))
            .test_ok("SAF update");
        let replay = engine
            .commit_saf_projection_mutation(update, Some(projection))
            .test_ok("SAF update replay");
        assert!(!first.idempotent_replay);
        assert!(replay.idempotent_replay);
        assert_eq!(replay.core_revision, first.core_revision);
        assert_eq!(replay.event_sequence, first.event_sequence);
        assert_eq!(
            engine
                .get_memo(memo_id.clone())
                .test_ok("get updated")
                .test_ok("updated memo")
                .body,
            updated_body
        );

        let pin = engine
            .commit_saf_projection_mutation(
                StoreMemoCommand {
                    operation_id: "ffi-saf-pin".to_owned(),
                    kind: StoreMemoCommandKind::Pin,
                    memo_id: memo_id.clone(),
                    expected_revision: 2,
                    expected_fingerprint: Some(updated_fingerprint.clone()),
                    content: None,
                    tags: vec![],
                    pin: Some(true),
                    pending_promotes: vec![],
                    chronology_epoch_ms: None,
                },
                None,
            )
            .test_ok("SAF pin");
        assert_eq!(pin.content_revision, 2);

        let restore = engine
            .commit_saf_projection_mutation(
                StoreMemoCommand {
                    operation_id: "ffi-saf-restore".to_owned(),
                    kind: StoreMemoCommandKind::Restore,
                    memo_id,
                    expected_revision: 2,
                    expected_fingerprint: Some(updated_fingerprint),
                    content: None,
                    tags: vec![],
                    pin: None,
                    pending_promotes: vec![],
                    chronology_epoch_ms: None,
                },
                None,
            )
            .test_err("SAF restore requires a platform plan");
        assert_eq!(restore.code(), "unsupported_saf_projection_command");
    }

    #[test]
    fn saf_projection_streaming_ffi_reads_exchange_body_only_in_rust() {
        let temporary = tempdir().test_ok("temp");
        let control = temporary.path().join("control");
        let exchange = temporary.path().join("exchange");
        fs::create_dir_all(&control).test_ok("control");
        fs::create_dir_all(&exchange).test_ok("exchange");
        let engine = LomoEngine::open(EngineConfig {
            control_root: control.display().to_string(),
            exchange_root: exchange.display().to_string(),
            workspace: Some(WorkspaceDescriptor::Saf {
                stable_workspace_id: "ws-saf-stream-test".to_owned(),
                capability_token: "cap-saf-stream-test".to_owned(),
            }),
            bootstrap_deadline_millis: 30_000,
        })
        .test_ok("open SAF engine");
        let token = "ex.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.body";
        let body = "streamed body from exchange";
        fs::write(exchange.join(token), body).test_ok("exchange body");
        let rebuild_id = engine
            .begin_saf_projection_rebuild()
            .test_ok("begin streaming rebuild");
        engine
            .append_saf_projection_rebuild_page(
                rebuild_id.clone(),
                vec![StoreSafMemoProjectionReference {
                    memo_id: "2026_08_04_12:00:00_0".to_owned(),
                    source_path: "2026_08_04.md".to_owned(),
                    file_fingerprint: lomo_store::fingerprint_content(body),
                    chronology_epoch_ms: 1_754_308_800_000,
                    content: WorkspaceMemoContentReference {
                        exchange_token: token.to_owned(),
                        length: body.len() as u64,
                        digest: lomo_store::fingerprint_content(body),
                    },
                    tags: vec!["streamed".to_owned()],
                    attachment_paths: vec![],
                    has_todo: false,
                    has_url: false,
                    reminders: vec![],
                }],
            )
            .test_ok("append streaming page");
        assert!(!exchange.join(token).exists());
        let result = engine
            .finish_saf_projection_rebuild(rebuild_id)
            .test_ok("finish streaming rebuild");
        assert_eq!(result.memos_indexed, 1);
        let memo = engine
            .get_memo("2026_08_04_12:00:00_0".to_owned())
            .test_ok("get streamed memo")
            .test_ok("streamed memo snapshot");
        assert_eq!(memo.body, body);

        let aborted_id = engine
            .begin_saf_projection_rebuild()
            .test_ok("begin replacement rebuild");
        let still_live = engine
            .get_memo("2026_08_04_12:00:00_0".to_owned())
            .test_ok("query live projection during rebuild")
            .test_ok("live memo during rebuild");
        assert_eq!(still_live.body, body);
        engine
            .abort_saf_projection_rebuild(aborted_id)
            .test_ok("abort replacement rebuild");
        assert!(
            engine
                .get_memo("2026_08_04_12:00:00_0".to_owned())
                .test_ok("query live projection after abort")
                .is_some()
        );
    }

    #[test]
    fn reminder_plan_and_snooze_command_via_ffi() {
        let (_tmp, engine) = open_engine();
        let zone = StoreTimeZoneContext {
            zone_id: "UTC".to_owned(),
            base_offset_secs: 0,
            transitions: vec![],
        };
        let session = StoreReminderSession {
            opaque_id: "rem-1".to_owned(),
            memo_identity: "2026-07-20_10:00:00_0".to_owned(),
            memo_revision: "rev-1".to_owned(),
            token: "@2020-01-01-09:00rd".to_owned(),
            due_at_local: "2020-01-01-09:00".to_owned(),
            repeat_count: 1,
            fired_count: 0,
            done: false,
            interval_minutes: 10,
            recurrence_code: "d".to_owned(),
        };
        let plan = engine
            .query_reminder_plan(StoreReminderQuery {
                now_utc_ms: 1_700_000_000_000,
                zone,
                sessions: vec![session.clone()],
                rolling_window: 8,
                workspace_generation: 1,
            })
            .test_ok("plan");
        let catch_ups = plan.alarms.iter().filter(|a| a.is_catch_up).count();
        assert_eq!(
            catch_ups, 1,
            "catch-up storm prevention via FFI: {:?}",
            plan.alarms
        );

        let snooze = engine
            .apply_reminder_command(StoreReminderCommand {
                kind: StoreReminderCommandKind::Snooze,
                session: None,
                expected_revision: None,
                opaque_id: Some("rem-1".to_owned()),
                memo_identity: Some(session.memo_identity),
                memo_revision: Some("rev-1".to_owned()),
                workspace_generation: Some(1),
                snooze_until_utc_ms: Some(1_800_000_000_000),
            })
            .test_ok("snooze");
        assert!(snooze.snooze_only);
        assert!(snooze.replacement_token.is_none());
        assert!(snooze.scopes.iter().any(|s| s == "reminder"));
    }

    #[test]
    fn start_rebuild_returns_result() {
        let (_tmp, engine) = open_engine();
        let result = engine.start_rebuild(16).test_ok("rebuild");
        assert!(result.high_water_revision >= 1);
    }

    fn session_fixture(opaque: &str) -> StoreReminderSession {
        StoreReminderSession {
            opaque_id: opaque.to_owned(),
            memo_identity: "2026-07-20_10:00:00_0".to_owned(),
            memo_revision: "rev-1".to_owned(),
            token: "@2024-06-01-15:00".to_owned(),
            due_at_local: "2024-06-01-15:00".to_owned(),
            repeat_count: 1,
            fired_count: 0,
            done: false,
            interval_minutes: 10,
            recurrence_code: String::new(),
        }
    }

    #[test]
    fn memo_command_kinds_and_filters_round_trip_via_ffi() {
        let (_tmp, engine) = open_engine();
        let create = engine
            .apply_memo_command(StoreMemoCommand {
                operation_id: "op-ffi-create".to_owned(),
                kind: StoreMemoCommandKind::Create,
                memo_id: "m-kind".to_owned(),
                expected_revision: 0,
                expected_fingerprint: None,
                content: Some("seed\n- [ ] task\nhttps://lomo.example".to_owned()),
                tags: vec!["k".to_owned()],
                pin: None,
                pending_promotes: vec![],
                chronology_epoch_ms: None,
            })
            .test_ok("create");
        assert!(!create.idempotent_replay);
        assert!(create.scopes.iter().any(|s| s == "memo_list"));

        let update = engine
            .apply_memo_command(StoreMemoCommand {
                operation_id: "op-ffi-update".to_owned(),
                kind: StoreMemoCommandKind::Update,
                memo_id: "m-kind".to_owned(),
                expected_revision: create.content_revision,
                expected_fingerprint: Some(create.file_fingerprint.clone()),
                content: Some("updated body\n- [x] task\nhttps://lomo.example/x".to_owned()),
                tags: vec!["k".to_owned(), "u".to_owned()],
                pin: None,
                pending_promotes: vec![],
                chronology_epoch_ms: None,
            })
            .test_ok("update");
        assert_eq!(update.content_revision, create.content_revision + 1);
        assert!(update.scopes.iter().any(|s| s == "search"));

        let pin = engine
            .apply_memo_command(StoreMemoCommand {
                operation_id: "op-ffi-pin".to_owned(),
                kind: StoreMemoCommandKind::Pin,
                memo_id: "m-kind".to_owned(),
                expected_revision: update.content_revision,
                expected_fingerprint: None,
                content: None,
                tags: vec![],
                pin: Some(true),
                pending_promotes: vec![],
                chronology_epoch_ms: None,
            })
            .test_ok("pin");
        assert!(pin.scopes.iter().any(|s| s == "pin"));

        let pinned = engine
            .query_memos(
                StoreMemoQuery {
                    search_text: None,
                    filters: StoreMemoFilters {
                        pinned_only: true,
                        has_todo: Some(true),
                        has_url: Some(true),
                        tag: Some("u".to_owned()),
                        ..StoreMemoFilters::default()
                    },
                },
                None,
                16,
            )
            .test_ok("pinned query");
        assert!(
            pinned
                .items
                .iter()
                .any(|m| m.memo_id == "m-kind" && m.is_pinned),
            "pin+filters must surface memo: {:?}",
            pinned.items
        );

        let deleted = engine
            .apply_memo_command(StoreMemoCommand {
                operation_id: "op-ffi-del".to_owned(),
                kind: StoreMemoCommandKind::Delete,
                memo_id: "m-kind".to_owned(),
                expected_revision: update.content_revision,
                expected_fingerprint: None,
                content: None,
                tags: vec![],
                pin: None,
                pending_promotes: vec![],
                chronology_epoch_ms: None,
            })
            .test_ok("delete");
        assert!(deleted.scopes.iter().any(|s| s == "trash"));

        let trash_page = engine
            .query_memos(
                StoreMemoQuery {
                    search_text: None,
                    filters: StoreMemoFilters {
                        include_trash: true,
                        trash_only: true,
                        ..StoreMemoFilters::default()
                    },
                },
                None,
                16,
            )
            .test_ok("trash");
        assert!(
            trash_page
                .items
                .iter()
                .any(|m| m.memo_id == "m-kind" && m.is_trashed)
        );

        let restored = engine
            .apply_memo_command(StoreMemoCommand {
                operation_id: "op-ffi-restore".to_owned(),
                kind: StoreMemoCommandKind::Restore,
                memo_id: "m-kind".to_owned(),
                expected_revision: update.content_revision,
                expected_fingerprint: None,
                content: None,
                tags: vec![],
                pin: None,
                pending_promotes: vec![],
                chronology_epoch_ms: None,
            })
            .test_ok("restore");
        assert!(restored.scopes.iter().any(|s| s == "trash"));

        engine
            .apply_memo_command(StoreMemoCommand {
                operation_id: "op-ffi-unpin".to_owned(),
                kind: StoreMemoCommandKind::Unpin,
                memo_id: "m-kind".to_owned(),
                expected_revision: update.content_revision,
                expected_fingerprint: None,
                content: None,
                tags: vec![],
                pin: Some(false),
                pending_promotes: vec![],
                chronology_epoch_ms: None,
            })
            .test_ok("unpin");

        let hist = engine
            .apply_memo_command(StoreMemoCommand {
                operation_id: "op-ffi-hist".to_owned(),
                kind: StoreMemoCommandKind::HistoryRestore,
                memo_id: "m-kind".to_owned(),
                expected_revision: update.content_revision,
                expected_fingerprint: None,
                content: Some("history via ffi".to_owned()),
                tags: vec!["h".to_owned()],
                pin: None,
                pending_promotes: vec![],
                chronology_epoch_ms: None,
            })
            .test_ok("history restore");
        assert_eq!(hist.content_revision, update.content_revision + 1);

        let snap = engine
            .get_memo("m-kind".to_owned())
            .test_ok("get")
            .test_ok("present");
        assert_eq!(snap.body, "history via ffi");
        assert!(!snap.summary.is_pinned);
        assert!(!snap.summary.is_trashed);
    }

    #[test]
    fn page_cursor_round_trip_and_invalid_cursor_fail_closed() {
        let (_tmp, engine) = open_engine();
        for i in 0..3 {
            engine
                .apply_memo_command(StoreMemoCommand {
                    operation_id: format!("op-page-{i}"),
                    kind: StoreMemoCommandKind::Create,
                    memo_id: format!("page-{i}"),
                    expected_revision: 0,
                    expected_fingerprint: None,
                    content: Some(format!("needle {} body {i}", "needle ".repeat(i))),
                    tags: vec![],
                    pin: None,
                    pending_promotes: vec![],
                    chronology_epoch_ms: None,
                })
                .test_ok("create page memo");
        }

        let first = engine
            .query_memos(
                StoreMemoQuery {
                    search_text: None,
                    filters: StoreMemoFilters::default(),
                },
                None,
                1,
            )
            .test_ok("page1");
        assert_eq!(first.items.len(), 1);
        let cursor = first
            .next_cursor
            .clone()
            .test_ok("must page when more rows exist");
        assert!(
            cursor.encoded.split('|').count() == 6,
            "cursor wire form: {}",
            cursor.encoded
        );
        assert_eq!(cursor.encoded.split('|').nth(1), Some("none"));

        let second = engine
            .query_memos(
                StoreMemoQuery {
                    search_text: None,
                    filters: StoreMemoFilters::default(),
                },
                Some(cursor),
                1,
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
                },
                None,
                1,
            )
            .test_ok("FTS page1");
        let fts_cursor = fts_first.next_cursor.test_ok("FTS must page");
        assert_ne!(fts_cursor.encoded.split('|').nth(1), Some("none"));
        let fts_second = engine
            .query_memos(
                StoreMemoQuery {
                    search_text: Some("needle".to_owned()),
                    filters: StoreMemoFilters::default(),
                },
                Some(fts_cursor),
                1,
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
                },
                Some(StorePageCursor {
                    encoded: "not|a|valid".to_owned(),
                }),
                1,
            )
            .test_err("malformed cursor");
        assert_eq!(bad.code(), "invalid_page_cursor");

        let bad_num = engine
            .query_memos(
                StoreMemoQuery {
                    search_text: None,
                    filters: StoreMemoFilters::default(),
                },
                Some(StorePageCursor {
                    encoded: "fp|none|not-i64|id|1|1".to_owned(),
                }),
                1,
            )
            .test_err("non-i64 sort");
        assert_eq!(bad_num.code(), "invalid_page_cursor");

        for bad in [
            "fp|not-u64-bits|1|id|1|1",
            "fp|1|id|1|1",
            "fp|none|1|id|not-u64|1",
            "fp|none|1|id|1|not-u32",
            "fp|none|1|id|1|1|extra",
        ] {
            let err = engine
                .query_memos(
                    StoreMemoQuery {
                        search_text: None,
                        filters: StoreMemoFilters::default(),
                    },
                    Some(StorePageCursor {
                        encoded: bad.to_owned(),
                    }),
                    1,
                )
                .test_err(bad);
            assert_eq!(err.code(), "invalid_page_cursor", "cursor={bad}");
        }
    }

    #[test]
    fn tag_subtree_selection_survives_native_boundary() {
        let (_tmp, engine) = open_engine();
        for (id, tag) in [
            ("tag-root", "work"),
            ("tag-child", "work/project"),
            ("tag-sibling", "workspace"),
        ] {
            engine
                .apply_memo_command(StoreMemoCommand {
                    operation_id: format!("op-{id}"),
                    kind: StoreMemoCommandKind::Create,
                    memo_id: id.to_owned(),
                    expected_revision: 0,
                    expected_fingerprint: None,
                    content: Some(id.to_owned()),
                    tags: vec![tag.to_owned()],
                    pin: None,
                    pending_promotes: vec![],
                    chronology_epoch_ms: None,
                })
                .test_ok("create tagged memo");
        }
        let page = engine
            .query_memos(
                StoreMemoQuery {
                    search_text: None,
                    filters: StoreMemoFilters {
                        tag: Some("work".to_owned()),
                        tag_subtree: true,
                        ..StoreMemoFilters::default()
                    },
                },
                None,
                10,
            )
            .test_ok("query tag subtree");
        let ids = page
            .items
            .iter()
            .map(|item| item.memo_id.as_str())
            .collect::<std::collections::BTreeSet<_>>();
        assert_eq!(
            ids,
            std::collections::BTreeSet::from(["tag-child", "tag-root"])
        );
    }

    #[test]
    fn reminder_commands_and_zone_transitions_via_ffi() {
        let (_tmp, engine) = open_engine();
        let zone = StoreTimeZoneContext {
            zone_id: "America/New_York".to_owned(),
            base_offset_secs: -5 * 3600,
            transitions: vec![StoreZoneTransition {
                transition_utc_ms: 1_710_054_000_000,
                offset_before_secs: -5 * 3600,
                offset_after_secs: -4 * 3600,
            }],
        };
        let session = session_fixture("rem-cmd-ffi");
        let plan = engine
            .query_reminder_plan(StoreReminderQuery {
                now_utc_ms: 1_700_000_000_000,
                zone,
                sessions: vec![session.clone()],
                rolling_window: 8,
                workspace_generation: 3,
            })
            .test_ok("plan with transitions");
        assert_eq!(plan.workspace_generation, 3);

        let done = engine
            .apply_reminder_command(StoreReminderCommand {
                kind: StoreReminderCommandKind::MarkDone,
                session: Some(session.clone()),
                expected_revision: Some("rev-1".to_owned()),
                opaque_id: None,
                memo_identity: None,
                memo_revision: None,
                workspace_generation: None,
                snooze_until_utc_ms: None,
            })
            .test_ok("mark done");
        assert_eq!(
            done.replacement_token.as_deref(),
            Some("@2024-06-01-15:00.done")
        );
        assert!(!done.snooze_only);
        assert!(done.scopes.iter().any(|s| s == "reminder"));

        let fired = engine
            .apply_reminder_command(StoreReminderCommand {
                kind: StoreReminderCommandKind::RecordFired,
                session: Some(session.clone()),
                expected_revision: Some("rev-1".to_owned()),
                opaque_id: None,
                memo_identity: None,
                memo_revision: None,
                workspace_generation: None,
                snooze_until_utc_ms: None,
            })
            .test_ok("record fired");
        assert!(fired.replacement_token.is_some());
        assert!(!fired.snooze_only);

        engine
            .apply_reminder_command(StoreReminderCommand {
                kind: StoreReminderCommandKind::Snooze,
                session: None,
                expected_revision: None,
                opaque_id: Some("rem-cmd-ffi".to_owned()),
                memo_identity: Some(session.memo_identity.clone()),
                memo_revision: Some("rev-1".to_owned()),
                workspace_generation: Some(3),
                snooze_until_utc_ms: Some(1_800_000_000_000),
            })
            .test_ok("snooze");
        let clear = engine
            .apply_reminder_command(StoreReminderCommand {
                kind: StoreReminderCommandKind::ClearSnooze,
                session: None,
                expected_revision: None,
                opaque_id: Some("rem-cmd-ffi".to_owned()),
                memo_identity: Some(session.memo_identity.clone()),
                memo_revision: Some("rev-1".to_owned()),
                workspace_generation: Some(3),
                snooze_until_utc_ms: None,
            })
            .test_ok("clear snooze");
        assert!(clear.snooze_only || clear.replacement_token.is_none());
        assert!(clear.scopes.iter().any(|s| s == "reminder"));

        // Fail-closed conversion for missing required fields.
        let missing_session = engine
            .apply_reminder_command(StoreReminderCommand {
                kind: StoreReminderCommandKind::MarkDone,
                session: None,
                expected_revision: Some("rev-1".to_owned()),
                opaque_id: None,
                memo_identity: None,
                memo_revision: None,
                workspace_generation: None,
                snooze_until_utc_ms: None,
            })
            .test_err("mark done needs session");
        assert_eq!(missing_session.code(), "invalid_reminder_command");

        let missing_snooze_fields = engine
            .apply_reminder_command(StoreReminderCommand {
                kind: StoreReminderCommandKind::Snooze,
                session: None,
                expected_revision: None,
                opaque_id: None,
                memo_identity: None,
                memo_revision: None,
                workspace_generation: None,
                snooze_until_utc_ms: None,
            })
            .test_err("snooze needs fields");
        assert_eq!(missing_snooze_fields.code(), "invalid_reminder_command");

        let missing_clear = engine
            .apply_reminder_command(StoreReminderCommand {
                kind: StoreReminderCommandKind::ClearSnooze,
                session: None,
                expected_revision: None,
                opaque_id: Some("x".to_owned()),
                memo_identity: None,
                memo_revision: None,
                workspace_generation: None,
                snooze_until_utc_ms: None,
            })
            .test_err("clear needs binding");
        assert_eq!(missing_clear.code(), "invalid_reminder_command");

        let missing_fired_rev = engine
            .apply_reminder_command(StoreReminderCommand {
                kind: StoreReminderCommandKind::RecordFired,
                session: Some(session),
                expected_revision: None,
                opaque_id: None,
                memo_identity: None,
                memo_revision: None,
                workspace_generation: None,
                snooze_until_utc_ms: None,
            })
            .test_err("record fired needs revision");
        assert_eq!(missing_fired_rev.code(), "invalid_reminder_command");
    }
}
