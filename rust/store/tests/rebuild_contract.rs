//! Behavior Contract (P3-06)
//!
//! Capability: rebuild enters read-only (mutations rejected), scans Markdown + `.lomo` into a
//! temporary database with checkpoints, integrity/compare, atomic `SQLite` replace; process-death
//! resume continues; `SQLite` damage never deletes `.lomo`.
//!
//! Scenarios:
//! - Given memos on disk, when rebuild runs, then projections restore and queries succeed.
//! - Given rebuild checkpoint mid-indexing with a partial temp DB, when rebuild is invoked again,
//!   then it resumes and completes without duplicate rows or a stuck gate.
//! - Given phase=`replacing` with temp already gone (crash after temp→live), when rebuild resumes,
//!   then the good live DB is not destroyed and the store is usable / gate Ready.
//! - Given a memo with tags, when `SQLite` is wiped and rebuild runs, then the tag filter finds it.
//! - Given trash then pin, when durable state and rebuild are inspected, then both flags survive.
//! - Given active rebuild gate, when a mutation is submitted, then it is rejected with
//!   `store_rebuilding`.
//! - Given `SQLite` file deleted while `.lomo` remains, when rebuild runs, then `.lomo` is intact.
//! - Given bounded memo facts scanned from a SAF workspace, when its app-private projection is
//!   rebuilt, then queries succeed without creating a Markdown body mirror.
//!
//! Observable outcomes: query rows, error codes, rebuild evidence, durable `.lomo` preservation,
//! and absence of user Markdown bytes under the SAF projection root.
//! TDD proof: SAF projection rebuild was RED on 2026-08-02 because `lomo-store` could only rebuild
//! by traversing a Direct filesystem workspace.
//! Excludes: Android `DocumentsContract` execution and FFI conversion.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use std::fs;
    use std::path::Path;

    use lomo_core::{ErrorCategory, OperationId, PageSize};
    use lomo_store::{
        LomoPaths, MemoCommand, MemoCommandKind, MemoFilters, MemoQuery, RebuildPhase,
        ScannedMemoProjection, StateBody, Store, WriteGate, ensure_writable, fingerprint_content,
        read_record, rebuild_scanned_projection, run_rebuild, write_gate_for_checkpoint,
    };
    use tempfile::tempdir;

    fn create_with_tags(store: &mut Store, op: &str, memo: &str, content: &str, tags: &[&str]) {
        store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse(op).expect("op"),
                    kind: MemoCommandKind::Create,
                    memo_id: memo.into(),
                    expected_revision: 0,
                    expected_fingerprint: None,
                    content: Some(content.into()),
                    tags: tags.iter().map(|t| (*t).to_owned()).collect(),
                    pin: None,
                    pending_promotes: vec![],
                },
                None,
            )
            .expect("create");
    }

    fn wipe_sqlite(db_path: &Path) {
        fs::remove_file(db_path).expect("remove sqlite");
        drop(fs::remove_file(format!("{}-wal", db_path.display())));
        drop(fs::remove_file(format!("{}-shm", db_path.display())));
    }

    fn query_all(store: &Store) -> usize {
        store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters::default(),
                },
                None,
                PageSize::new(20).expect("page"),
            )
            .expect("query")
            .items
            .len()
    }

    #[test]
    fn scanned_saf_facts_rebuild_query_projection_without_markdown_mirror() {
        let projection = tempdir().expect("projection root");
        let body = format!(
            "# daily\n\nsearchable SAF body\n\n{}raw-tail-8f7431b6",
            "bounded projection input ".repeat(20)
        );
        let source = ScannedMemoProjection {
            memo_id: "2026-08-02_19:30:00_0".to_owned(),
            source_path: "2026-08-02.md".to_owned(),
            file_fingerprint: fingerprint_content(&body),
            body: body.clone(),
            tags: vec!["device".to_owned()],
            attachment_paths: Vec::new(),
            has_todo: false,
            has_url: false,
        };

        let result = rebuild_scanned_projection(projection.path(), &[source]).expect("rebuild");
        let store = Store::open_projection(projection.path()).expect("open projection");
        let page = store
            .query_memos(
                &MemoQuery {
                    search_text: Some("searchable".to_owned()),
                    filters: MemoFilters::default(),
                },
                None,
                PageSize::new(10).expect("page size"),
            )
            .expect("query projection");

        assert_eq!(result.memos_indexed, 1);
        assert_eq!(result.workspace_digest, result.store_digest);
        assert_eq!(page.items.len(), 1);
        assert_eq!(
            page.items.first().expect("projected memo").source_path,
            "2026-08-02.md"
        );
        assert!(
            !projection.path().join("2026-08-02.md").exists(),
            "SAF projection must not mirror the user Markdown file"
        );
        let database = fs::read(store.open_info().database_path).expect("projection database");
        assert!(
            !database
                .windows(body.len())
                .any(|window| window == body.as_bytes()),
            "SAF projection must not persist the complete raw Markdown body"
        );
    }

    #[test]
    fn rebuild_restores_projections_without_deleting_lomo() {
        let dir = tempdir().expect("tempdir");
        let mut store = Store::open(dir.path()).expect("open");
        create_with_tags(&mut store, "op-r1", "r1", "rebuild me 你好", &["x"]);
        store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-pin-r1").expect("op"),
                    kind: MemoCommandKind::Pin,
                    memo_id: "r1".into(),
                    expected_revision: 1,
                    expected_fingerprint: None,
                    content: None,
                    tags: vec![],
                    pin: Some(true),
                    pending_promotes: vec![],
                },
                None,
            )
            .expect("pin");

        let lomo = LomoPaths::for_workspace(dir.path());
        assert!(lomo.root.exists());
        let lomo_entries_before = fs::read_dir(&lomo.root).expect("lomo").count();

        let db_path = store.open_info().database_path;
        drop(store);
        wipe_sqlite(&db_path);
        assert!(lomo.root.exists());

        let result = run_rebuild(dir.path(), 1).expect("rebuild");
        assert!(result.memos_indexed >= 1);
        assert!(lomo.root.exists());
        let lomo_entries_after = fs::read_dir(&lomo.root).expect("lomo").count();
        assert_eq!(
            lomo_entries_before, lomo_entries_after,
            "rebuild must not wipe .lomo tree"
        );

        let store = Store::open(dir.path()).expect("reopen after rebuild");
        let page = store
            .query_memos(
                &MemoQuery {
                    search_text: Some("你好".into()),
                    filters: MemoFilters::default(),
                },
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("query");
        assert_eq!(page.items.len(), 1);
        assert_eq!(page.items.first().map(|m| m.memo_id.as_str()), Some("r1"));
        let pin_page = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters {
                        pinned_only: true,
                        ..MemoFilters::default()
                    },
                },
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("pin query");
        assert_eq!(pin_page.items.len(), 1);
    }

    #[test]
    fn rebuild_rehydrates_tags_after_sqlite_wipe() {
        let dir = tempdir().expect("tempdir");
        let mut store = Store::open(dir.path()).expect("open");
        create_with_tags(
            &mut store,
            "op-tag-1",
            "tagged",
            "body with tag dimension",
            &["project-alpha"],
        );

        let before = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters {
                        tag: Some("project-alpha".into()),
                        ..MemoFilters::default()
                    },
                },
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("before tag query");
        assert_eq!(before.items.len(), 1);

        let db_path = store.open_info().database_path;
        drop(store);
        wipe_sqlite(&db_path);

        run_rebuild(dir.path(), 8).expect("rebuild");
        let store = Store::open(dir.path()).expect("reopen");
        let after = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters {
                        tag: Some("project-alpha".into()),
                        ..MemoFilters::default()
                    },
                },
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("after tag query");
        assert_eq!(
            after.items.len(),
            1,
            "tag filter must find memo after wipe+rebuild"
        );
        assert_eq!(query_all(&store), 1);
        let stats_after = store.stats().expect("stats");
        assert!(
            stats_after.tag_count >= 1,
            "stats tag_count must rehydrate, got {}",
            stats_after.tag_count
        );
    }

    #[test]
    fn trash_then_pin_preserves_both_in_durable_state_and_rebuild() {
        let dir = tempdir().expect("tempdir");
        let mut store = Store::open(dir.path()).expect("open");
        create_with_tags(
            &mut store,
            "op-tp-create",
            "tp1",
            "trash pin body",
            &["keep"],
        );
        apply_delete_then_pin(&mut store, "tp1");
        assert_pin_and_trash_live(&store);
        assert_durable_pin_trash_tags(dir.path(), "tp1", "keep");

        let db_path = store.open_info().database_path;
        drop(store);
        wipe_sqlite(&db_path);
        run_rebuild(dir.path(), 4).expect("rebuild");
        let store = Store::open(dir.path()).expect("reopen");
        assert_pin_and_trash_live(&store);
        let tag_hits = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters {
                        tag: Some("keep".into()),
                        include_trash: true,
                        ..MemoFilters::default()
                    },
                },
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("tag after rebuild");
        assert_eq!(tag_hits.items.len(), 1);
    }

    fn apply_delete_then_pin(store: &mut Store, memo_id: &str) {
        store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-tp-del").expect("op"),
                    kind: MemoCommandKind::Delete,
                    memo_id: memo_id.into(),
                    expected_revision: 1,
                    expected_fingerprint: None,
                    content: None,
                    tags: vec![],
                    pin: None,
                    pending_promotes: vec![],
                },
                None,
            )
            .expect("delete");
        store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-tp-pin").expect("op"),
                    kind: MemoCommandKind::Pin,
                    memo_id: memo_id.into(),
                    expected_revision: 1,
                    expected_fingerprint: None,
                    content: None,
                    tags: vec![],
                    pin: Some(true),
                    pending_promotes: vec![],
                },
                None,
            )
            .expect("pin after trash");
    }

    fn assert_pin_and_trash_live(store: &Store) {
        let page = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters {
                        trash_only: true,
                        include_trash: true,
                        pinned_only: true,
                        ..MemoFilters::default()
                    },
                },
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("pin+trash query");
        assert_eq!(page.items.len(), 1);
        let item = page.items.first().expect("item");
        assert!(item.is_trashed);
        assert!(item.is_pinned);
    }

    fn assert_durable_pin_trash_tags(root: &Path, memo_id: &str, tag: &str) {
        let paths = LomoPaths::for_workspace(root);
        let record = read_record(&paths.state.join(format!("{memo_id}.rec"))).expect("state");
        let body: StateBody = serde_json::from_str(&record.payload.body_json).expect("state body");
        assert!(body.pinned, "durable state must remain pinned");
        assert!(body.trashed, "durable state must remain trashed after pin");
        assert!(
            body.tags.iter().any(|t| t == tag),
            "durable tags must survive pin merge"
        );
    }

    #[test]
    fn replacing_phase_with_temp_gone_does_not_destroy_live_db() {
        let dir = tempdir().expect("tempdir");
        let mut store = Store::open(dir.path()).expect("open");
        create_with_tags(
            &mut store,
            "op-rep-1",
            "rep1",
            "replace boundary body",
            &["edge"],
        );
        let db_path = store.open_info().database_path;
        drop(store);

        // Simulate crash after temp→live rename: phase=replacing, temp missing, live good.
        let sqlite_dir = dir.path().join(".lomo-sqlite");
        fs::create_dir_all(&sqlite_dir).expect("sqlite dir");
        let checkpoint = sqlite_dir.join("rebuild.checkpoint.json");
        fs::write(
            &checkpoint,
            r#"{"phase":"replacing","scanned":1,"total_hint":1,"isolated":0}"#,
        )
        .expect("checkpoint");
        drop(fs::remove_file(sqlite_dir.join("store.rebuild.db")));
        assert!(db_path.exists(), "live must exist before resume");

        let result = run_rebuild(dir.path(), 8).expect("resume replacing must succeed");
        assert!(
            db_path.exists(),
            "live DB must survive replacing resume when temp is gone"
        );
        assert!(
            fs::metadata(&db_path).expect("meta after").len() > 0,
            "live DB must remain non-empty"
        );
        assert!(
            !checkpoint.exists(),
            "checkpoint must be cleared on successful replace completion"
        );
        assert_eq!(
            write_gate_for_checkpoint(dir.path()),
            WriteGate::Ready,
            "write gate must not stick at RebuildingReadOnly"
        );
        assert_eq!(result.memos_indexed, 1);

        let mut store = Store::open(dir.path()).expect("open after replace resume");
        assert_eq!(query_all(&store), 1);
        let page = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters::default(),
                },
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("query live after resume");
        assert_eq!(page.items.first().map(|m| m.memo_id.as_str()), Some("rep1"));
        store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-rep-write").expect("op"),
                    kind: MemoCommandKind::Create,
                    memo_id: "rep2".into(),
                    expected_revision: 0,
                    expected_fingerprint: None,
                    content: Some("writable after replace resume".into()),
                    tags: vec![],
                    pin: None,
                    pending_promotes: vec![],
                },
                None,
            )
            .expect("write after replace resume");
    }

    #[test]
    fn mid_indexing_checkpoint_resumes_without_duplicate_or_stuck_gate() {
        let dir = tempdir().expect("tempdir");
        let mut store = Store::open(dir.path()).expect("open");
        for i in 0..5 {
            create_with_tags(
                &mut store,
                &format!("op-mid-{i}"),
                &format!("mid{i}"),
                &format!("mid body {i} 搜索"),
                &["batch"],
            );
        }
        let live = store.open_info().database_path;
        drop(store);

        // Materialize a complete projection, then re-stage mid-index resume: copy live → temp,
        // set indexing checkpoint scanned=2, wipe live. Resume finishes remaining paths via
        // skip-if-indexed, applies state, and replaces.
        run_rebuild(dir.path(), 2).expect("baseline rebuild");
        let sqlite_dir = dir.path().join(".lomo-sqlite");
        let temp_db = sqlite_dir.join("store.rebuild.db");
        let checkpoint = sqlite_dir.join("rebuild.checkpoint.json");
        fs::copy(&live, &temp_db).expect("copy live to temp as partial-complete index");
        fs::write(
            &checkpoint,
            r#"{"phase":"indexing","scanned":2,"total_hint":5,"isolated":0}"#,
        )
        .expect("mid checkpoint");
        wipe_sqlite(&live);

        assert_eq!(
            write_gate_for_checkpoint(dir.path()),
            WriteGate::RebuildingReadOnly
        );
        let result = run_rebuild(dir.path(), 2).expect("resume mid-index");
        assert!(result.memos_indexed >= 5);
        assert_eq!(write_gate_for_checkpoint(dir.path()), WriteGate::Ready);
        assert!(!checkpoint.exists());
        assert!(!temp_db.exists(), "temp must be promoted away");

        let store = Store::open(dir.path()).expect("open after resume");
        assert_eq!(query_all(&store), 5, "no missing memos after resume");
        let page = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters::default(),
                },
                None,
                PageSize::new(20).expect("page"),
            )
            .expect("query");
        let mut ids: Vec<_> = page.items.iter().map(|m| m.memo_id.clone()).collect();
        ids.sort();
        ids.dedup();
        assert_eq!(ids.len(), 5, "no duplicate memo ids after resume");
        let tag_page = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters {
                        tag: Some("batch".into()),
                        ..MemoFilters::default()
                    },
                },
                None,
                PageSize::new(20).expect("page"),
            )
            .expect("tags");
        assert_eq!(tag_page.items.len(), 5);
    }

    #[test]
    fn rebuild_gate_rejects_mutations() {
        let dir = tempdir().expect("tempdir");
        let _: Store = Store::open(dir.path()).expect("open");
        let cp = dir
            .path()
            .join(".lomo-sqlite")
            .join("rebuild.checkpoint.json");
        fs::create_dir_all(cp.parent().expect("parent")).expect("dir");
        fs::write(
            &cp,
            r#"{"phase":"indexing","scanned":0,"total_hint":1,"isolated":0}"#,
        )
        .expect("checkpoint");
        assert_eq!(
            write_gate_for_checkpoint(dir.path()),
            WriteGate::RebuildingReadOnly
        );
        let err = ensure_writable(WriteGate::RebuildingReadOnly).expect_err("reject");
        assert_eq!(err.category(), ErrorCategory::Busy);
        assert_eq!(err.code(), "store_rebuilding");

        let mut store = Store::open(dir.path()).expect("open while rebuild flag");
        let err = store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-blocked").expect("op"),
                    kind: MemoCommandKind::Create,
                    memo_id: "blocked".into(),
                    expected_revision: 0,
                    expected_fingerprint: None,
                    content: Some("no".into()),
                    tags: vec![],
                    pin: None,
                    pending_promotes: vec![],
                },
                None,
            )
            .expect_err("mutation blocked");
        assert_eq!(err.code(), "store_rebuilding");
        assert_eq!(RebuildPhase::Indexing.as_str(), "indexing");
    }

    #[test]
    fn store_rebuild_wrapper_publishes_high_water_revision() {
        let dir = tempdir().expect("tempdir");
        let mut store = Store::open(dir.path()).expect("open");
        create_with_tags(&mut store, "op-rbw", "rbw", "rebuild wrapper body", &["w"]);
        let (store, result) = store.rebuild(8).expect("store.rebuild");
        assert!(result.memos_indexed >= 1);
        assert_eq!(result.file_count, result.memos_indexed);
        assert!(!result.workspace_digest.is_empty());
        assert_eq!(result.workspace_digest, result.store_digest);
        // Rebuild replaces the SQLite projection (meta counters reset) then the wrapper
        // publishes exactly one new high-water + event sequence for the completed rebuild.
        assert!(
            store.high_water_revision() >= 1,
            "wrapper must publish high-water after rebuild, got {}",
            store.high_water_revision()
        );
        assert!(store.event_sequence() >= 1);
        assert_eq!(result.high_water_revision, store.high_water_revision());
        assert_eq!(query_all(&store), 1);
        // Store remains writable after rebuild.
        let mut store = store;
        create_with_tags(&mut store, "op-rbw-2", "rbw2", "after rebuild", &["w"]);
        assert_eq!(query_all(&store), 2);
    }

    #[test]
    fn rebuild_isolates_corrupt_lomo_state_and_history_without_deleting_tree() {
        let dir = tempdir().expect("tempdir");
        let mut store = Store::open(dir.path()).expect("open");
        create_with_tags(
            &mut store,
            "op-iso-c",
            "iso1",
            "body for isolation 你好",
            &["iso"],
        );
        // Force a history record via update.
        store
            .apply_memo_command(
                &MemoCommand {
                    operation_id: OperationId::parse("op-iso-u").expect("op"),
                    kind: MemoCommandKind::Update,
                    memo_id: "iso1".into(),
                    expected_revision: 1,
                    expected_fingerprint: None,
                    content: Some("updated isolation body".into()),
                    tags: vec!["iso".into()],
                    pin: None,
                    pending_promotes: vec![],
                },
                None,
            )
            .expect("update");
        let db_path = store.open_info().database_path;
        drop(store);

        let paths = LomoPaths::for_workspace(dir.path());
        // Corrupt one state + one history record in place (checksum flip).
        let state_path = paths.state.join("iso1.rec");
        let mut state_bytes = fs::read(&state_path).expect("state bytes");
        if let Some(b) = state_bytes.get_mut(12) {
            *b ^= 0xff;
        }
        fs::write(&state_path, &state_bytes).expect("corrupt state");

        let hist_entry = fs::read_dir(&paths.history)
            .expect("history dir")
            .map(|e| e.expect("history entry").path())
            .find(|p| p.extension().and_then(|x| x.to_str()) == Some("rec"))
            .expect("history rec");
        let mut hist_bytes = fs::read(&hist_entry).expect("hist bytes");
        if let Some(b) = hist_bytes.get_mut(12) {
            *b ^= 0xff;
        }
        fs::write(&hist_entry, &hist_bytes).expect("corrupt hist");

        wipe_sqlite(&db_path);
        let result = run_rebuild(dir.path(), 4).expect("rebuild with isolation");
        assert!(
            result.corrupt_lomo_isolated >= 2,
            "state+history corrupt must isolate: {:?}",
            result.corrupt_lomo_isolated
        );
        assert!(paths.root.exists(), ".lomo root must survive isolation");
        assert!(
            !state_path.exists(),
            "corrupt state must be renamed away from live path"
        );
        assert!(
            state_path.with_extension("corrupt").exists(),
            "isolated sibling must exist"
        );

        let store = Store::open(dir.path()).expect("reopen");
        assert_eq!(query_all(&store), 1, "memo markdown still rebuilds");
        let body = store.get_memo("iso1").expect("get").expect("present");
        assert!(body.body.contains("updated isolation body"));
    }

    #[test]
    fn write_gate_helpers_cover_ready_and_rebuilding() {
        let dir = tempdir().expect("tempdir");
        assert_eq!(
            write_gate_for_checkpoint(dir.path()),
            WriteGate::Ready,
            "no checkpoint => Ready"
        );
        ensure_writable(WriteGate::Ready).expect("ready is writable");
        let err = ensure_writable(WriteGate::RebuildingReadOnly).expect_err("readonly");
        assert_eq!(err.code(), "store_rebuilding");
        assert_eq!(err.category(), ErrorCategory::Busy);

        // Synthetic incomplete checkpoint must force read-only gate.
        let sqlite_dir = dir.path().join(".lomo-sqlite");
        fs::create_dir_all(&sqlite_dir).expect("sqlite dir");
        let checkpoint = sqlite_dir.join("rebuild.checkpoint.json");
        fs::write(
            &checkpoint,
            r#"{"phase":"indexing","workspace_root":"x","started_at_ms":1,"pages_done":0,"temp_db_path":"t"}"#,
        )
        .expect("checkpoint");
        assert_eq!(
            write_gate_for_checkpoint(dir.path()),
            WriteGate::RebuildingReadOnly
        );
        assert_eq!(RebuildPhase::Indexing.as_str(), "indexing");
        assert_eq!(RebuildPhase::Complete.as_str(), "complete");
        assert_eq!(RebuildPhase::Replacing.as_str(), "replacing");
    }
}
