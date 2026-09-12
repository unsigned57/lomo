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
//! - Given a `Direct` workspace engine, when Markdown is seeded and rebuild runs, then
//!   `query_memos` lists the memo and `get_memo` returns it.
//! - Given `apply_memo_command` Create, when called, then `session_owns_document_writes` fails
//!   closed and no host identity is minted.
//! - Given `get_memo` for a missing id, when called, then [`None`] is returned.
//! - Given a past daily reminder session, when `query_reminder_plan` runs, then at most one
//!   catch-up alarm is planned.
//! - Given snooze via `apply_reminder_command`, when planned, then `replacement_token` is absent
//!   (`snooze_only`).
//! - Given `start_rebuild`, when completed, then a rebuild result with high-water revision is
//!   returned.
//! - Given Direct create/update/restore/history-restore/pin/delete via FFI, when applied, then
//!   `session_owns_document_writes` fails closed.
//! - Given multi-memo pages with `page_size=1`, when the next cursor is reused, then the second
//!   page is disjoint; given a malformed cursor, when decoded, then `invalid_page_cursor`.
//! - Given a memo identity start, when `query_memos` runs, then the page is inclusive at that id
//!   with `items_before`/`items_after`; mixing identity with a cursor fails `invalid_page_start`.
//! - Given `MarkDone` / `RecordFired` / `ClearSnooze` with full fields, when applied, then replacement
//!   tokens or snooze-only flags match store rules; missing required fields fail closed.
//! - Given zone transitions on the reminder query, when planned, then the plan succeeds without
//!   dropping the session.
//! - Given a SAF engine and active/trash facts produced by Rust workspace scans, when its
//!   app-private projection is rebuilt, then the durable trash snapshot remains queryable with the
//!   active document fingerprint and each consumed exchange body is removed.
//! - Given a completed SAF projection rebuild, when the process-level engine is closed and reopened,
//!   then the complete memo body remains queryable without another workspace scan.
//! - Given verified trash-command results, when delete, restore, and permanent delete are committed
//!   through the SAF projection boundary, then trash visibility and memo lifetime follow those
//!   workspace facts without an unsupported compatibility path.
//!
//! Observable outcomes: FFI DTO fields, structured `EngineError` codes.
//! TDD proof: RED before store methods exist on `LomoEngine`.
//! TDD proof: RED on 2026-08-06 because projection append retained every verified exchange body.
//! TDD proof: RED on 2026-08-09 because native SAF projection DTOs had no durable trash timestamp,
//! trash rebuild page, or restore/permanent-delete mapping.
//! TDD proof: RED on 2026-08-17 because SAF bodies lived only in the process-local `saf_bodies`
//! map, so reopening a valid projection returned `saf_store_body_unavailable`.
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

    use lomo_native::{
        EngineConfig, LomoEngine, StoreMemoCommand, StoreMemoCommandKind, StoreMemoFilters,
        StoreMemoQuery, StoreMemoSort, StorePageCursor, StoreReminderCommand,
        StoreReminderCommandKind, StoreReminderQuery, StoreReminderSession, StoreSafMemoProjection,
        StoreSafMemoProjectionReference, StoreSafTrashProjectionReference, StoreTimeZoneContext,
        StoreZoneTransition, WorkspaceDescriptor, WorkspaceMemoContentReference,
    };
    use tempfile::tempdir;

    fn open_engine() -> (tempfile::TempDir, PathBuf, LomoEngine) {
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
        (temporary, workspace, engine)
    }

    fn seed_markdown(workspace: &Path, memo_id: &str, content: &str) {
        let dir = workspace.join("memos");
        fs::create_dir_all(&dir).test_ok("memos dir");
        fs::write(dir.join(format!("{memo_id}.md")), content).test_ok("write memo");
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
        let (_tmp, workspace, engine) = open_engine();
        seed_markdown(&workspace, "m-ffi-1", "hello store ffi #tag/a");
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
            .query_memos(StoreMemoQuery::default(), None, 10, None, false)
            .test_ok("query SAF projection");
        let memo = engine
            .get_memo("2026-08-02_19:30:00_0".to_owned())
            .test_ok("get SAF memo")
            .test_ok("SAF memo snapshot");

        assert_eq!(rebuilt.memos_indexed, 1);
        assert_eq!(page.items.len(), 1);
        assert_eq!(memo.body, body);
        assert_eq!(
            engine
                .source_document_fingerprint("2026-08-02.md".to_owned())
                .test_ok("source document fingerprint"),
            Some(lomo_store::fingerprint_content(body))
        );
    }

    #[test]
    fn saf_projection_body_survives_engine_reopen_without_workspace_scan() {
        let temporary = tempdir().test_ok("temp");
        let control = temporary.path().join("control");
        let exchange = temporary.path().join("exchange");
        fs::create_dir_all(&control).test_ok("control");
        fs::create_dir_all(&exchange).test_ok("exchange");
        let config = EngineConfig {
            control_root: control.display().to_string(),
            exchange_root: exchange.display().to_string(),
            workspace: Some(WorkspaceDescriptor::Saf {
                stable_workspace_id: "ws-saf-reopen-test".to_owned(),
                capability_token: "cap-saf-reopen-test".to_owned(),
            }),
            bootstrap_deadline_millis: 30_000,
        };
        let body = "durable SAF body after process restart";
        let memo_id = "2026-08-17_14:30:00_0";
        let engine = LomoEngine::open(config.clone()).test_ok("open SAF engine");
        stream_saf_projection(
            &engine,
            &exchange,
            memo_id,
            "2026-08-17.md",
            1_776_586_200_000,
            body,
            vec!["restart".to_owned()],
        );
        drop(engine);

        let reopened = LomoEngine::open(config).test_ok("reopen SAF engine");
        let memo = reopened
            .get_memo(memo_id.to_owned())
            .test_ok("read reopened SAF memo")
            .test_ok("reopened SAF memo snapshot");

        assert_eq!(memo.body, body);
    }

    #[test]
    fn saf_projection_mutation_ffi_is_idempotent_and_supports_verified_trash_lifecycle() {
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
            trashed_at_ms: None,
        };
        let first = engine
            .commit_saf_projection_mutation(update.clone(), Some(projection.clone()))
            .test_ok("SAF update");
        let replay = engine
            .commit_saf_projection_mutation(update, Some(projection.clone()))
            .test_ok("SAF update replay");
        assert!(!first.idempotent_replay);
        assert!(replay.idempotent_replay);
        assert!(first.scopes.iter().any(|scope| scope == "memo_list"));
        assert!(first.scopes.iter().any(|scope| scope == "search"));
        assert!(first.scopes.iter().any(|scope| scope == "tags"));
        assert!(first.scopes.iter().any(|scope| scope == "stats"));
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

        let deleted = engine
            .commit_saf_projection_mutation(
                StoreMemoCommand {
                    operation_id: "ffi-saf-delete".to_owned(),
                    kind: StoreMemoCommandKind::Delete,
                    memo_id: memo_id.clone(),
                    expected_revision: 2,
                    expected_fingerprint: Some(updated_fingerprint.clone()),
                    content: None,
                    tags: vec![],
                    pin: None,
                    pending_promotes: vec![],
                    chronology_epoch_ms: None,
                },
                Some(StoreSafMemoProjection {
                    trashed_at_ms: Some(1_754_300_100_000),
                    ..projection.clone()
                }),
            )
            .test_ok("SAF delete");
        assert!(deleted.scopes.iter().any(|scope| scope == "trash"));
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

        let restored = engine
            .commit_saf_projection_mutation(
                StoreMemoCommand {
                    operation_id: "ffi-saf-restore".to_owned(),
                    kind: StoreMemoCommandKind::Restore,
                    memo_id: memo_id.clone(),
                    expected_revision: 2,
                    expected_fingerprint: Some(updated_fingerprint.clone()),
                    content: None,
                    tags: vec![],
                    pin: None,
                    pending_promotes: vec![],
                    chronology_epoch_ms: None,
                },
                Some(projection.clone()),
            )
            .test_ok("SAF restore");
        assert!(
            engine
                .query_memos(StoreMemoQuery::default(), None, 10, None, false)
                .test_ok("query restored memo")
                .items
                .iter()
                .any(|memo| memo.memo_id == memo_id && !memo.is_trashed)
        );

        engine
            .commit_saf_projection_mutation(
                StoreMemoCommand {
                    operation_id: "ffi-saf-delete-again".to_owned(),
                    kind: StoreMemoCommandKind::Delete,
                    memo_id: memo_id.clone(),
                    expected_revision: restored.content_revision,
                    expected_fingerprint: Some(updated_fingerprint.clone()),
                    content: None,
                    tags: vec![],
                    pin: None,
                    pending_promotes: vec![],
                    chronology_epoch_ms: None,
                },
                Some(StoreSafMemoProjection {
                    trashed_at_ms: Some(1_754_300_200_000),
                    ..projection.clone()
                }),
            )
            .test_ok("SAF delete again");
        let source_without_memo = lomo_store::fingerprint_content("source without deleted memo");
        engine
            .commit_saf_projection_mutation(
                StoreMemoCommand {
                    operation_id: "ffi-saf-permanent-delete".to_owned(),
                    kind: StoreMemoCommandKind::PermanentDelete,
                    memo_id: memo_id.clone(),
                    expected_revision: restored.content_revision,
                    expected_fingerprint: Some(updated_fingerprint),
                    content: None,
                    tags: vec![],
                    pin: None,
                    pending_promotes: vec![],
                    chronology_epoch_ms: None,
                },
                Some(StoreSafMemoProjection {
                    file_fingerprint: source_without_memo,
                    ..projection
                }),
            )
            .test_ok("SAF permanent delete");
        assert!(
            engine
                .get_memo(memo_id)
                .test_ok("query permanently deleted memo")
                .is_none()
        );
    }

    #[test]
    fn saf_trash_projection_rebuild_merges_recoverable_body_with_active_fingerprint() {
        let temporary = tempdir().test_ok("temp");
        let control = temporary.path().join("control");
        let exchange = temporary.path().join("exchange");
        fs::create_dir_all(&control).test_ok("control");
        fs::create_dir_all(&exchange).test_ok("exchange");
        let engine = LomoEngine::open(EngineConfig {
            control_root: control.display().to_string(),
            exchange_root: exchange.display().to_string(),
            workspace: Some(WorkspaceDescriptor::Saf {
                stable_workspace_id: "ws-saf-trash-rebuild-test".to_owned(),
                capability_token: "cap-saf-trash-rebuild-test".to_owned(),
            }),
            bootstrap_deadline_millis: 30_000,
        })
        .test_ok("open SAF engine");
        let memo_id = "2026_08_13_08:00:00_0";
        let active_body = "active source bytes";
        let trash_body = "recoverable deleted body sentinel";
        let active_digest = lomo_store::fingerprint_content(active_body);
        let active_token = format!("ex.{active_digest}.active");
        let trash_digest = lomo_store::fingerprint_content(trash_body);
        let trash_token = format!("ex.{trash_digest}.trash");
        fs::write(exchange.join(&active_token), active_body).test_ok("active exchange body");
        fs::write(exchange.join(&trash_token), trash_body).test_ok("trash exchange body");
        let rebuild_id = engine
            .begin_saf_projection_rebuild()
            .test_ok("begin rebuild");
        engine
            .append_saf_projection_rebuild_page(
                rebuild_id.clone(),
                vec![StoreSafMemoProjectionReference {
                    memo_id: memo_id.to_owned(),
                    source_path: "2026_08_13.md".to_owned(),
                    file_fingerprint: active_digest.clone(),
                    chronology_epoch_ms: 1_755_063_000_000,
                    content: WorkspaceMemoContentReference {
                        exchange_token: active_token.clone(),
                        length: active_body.len() as u64,
                        digest: active_digest.clone(),
                    },
                    tags: vec!["active".to_owned()],
                    attachment_paths: vec![],
                    has_todo: false,
                    has_url: false,
                    reminders: vec![],
                }],
            )
            .test_ok("append active page");
        engine
            .append_saf_trash_projection_rebuild_page(
                rebuild_id.clone(),
                vec![StoreSafTrashProjectionReference {
                    memo_id: memo_id.to_owned(),
                    source_path: "2026_08_13.md".to_owned(),
                    file_fingerprint: lomo_store::fingerprint_content("source at deletion"),
                    chronology_epoch_ms: 1_755_063_000_000,
                    trashed_at_ms: 1_755_063_100_000,
                    content: WorkspaceMemoContentReference {
                        exchange_token: trash_token.clone(),
                        length: trash_body.len() as u64,
                        digest: trash_digest,
                    },
                    tags: vec!["trash".to_owned()],
                    attachment_paths: vec![],
                    has_todo: false,
                    has_url: false,
                    reminders: vec![],
                }],
            )
            .test_ok("append trash page");
        engine
            .finish_saf_projection_rebuild(rebuild_id)
            .test_ok("finish rebuild");

        let snapshot = engine
            .get_memo(memo_id.to_owned())
            .test_ok("get trashed memo")
            .test_ok("trashed memo snapshot");
        assert!(snapshot.summary.is_trashed);
        assert_eq!(snapshot.summary.file_fingerprint, active_digest);
        assert_eq!(snapshot.body, trash_body);
        assert!(!exchange.join(active_token).exists());
        assert!(!exchange.join(trash_token).exists());
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
        let (_tmp, _workspace, engine) = open_engine();
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
        let (_tmp, _workspace, engine) = open_engine();
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
            "m-kind",
            "seed\n- [ ] task\nhttps://lomo.example #k #u",
        );
        index_workspace(&engine);
        let seeded = engine
            .get_memo("m-kind".to_owned())
            .test_ok("get seed")
            .test_ok("present");

        let update_error = engine
            .apply_memo_command(StoreMemoCommand {
                operation_id: "op-ffi-update".to_owned(),
                kind: StoreMemoCommandKind::Update,
                memo_id: "m-kind".to_owned(),
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
                memo_id: "m-kind".to_owned(),
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
                memo_id: "m-kind".to_owned(),
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
                memo_id: "m-kind".to_owned(),
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
            .get_memo("m-kind".to_owned())
            .test_ok("get after refused delete")
            .test_ok("still present");
        assert!(!live.summary.is_trashed);
        assert!(!live.summary.is_pinned);

        let restore_error = engine
            .apply_memo_command(StoreMemoCommand {
                operation_id: "op-ffi-restore".to_owned(),
                kind: StoreMemoCommandKind::Restore,
                memo_id: "m-kind".to_owned(),
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
                &format!("page-{i}"),
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
        for id in ["pos-a", "pos-b", "pos-c", "pos-d", "pos-e"] {
            seed_markdown(&workspace, id, id);
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
        for (id, tag) in [
            ("tag-root", "work"),
            ("tag-child", "work/project"),
            ("tag-sibling", "workspace"),
        ] {
            seed_markdown(&workspace, id, &format!("{id} #{tag}"));
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
        let (_tmp, _workspace, engine) = open_engine();
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
