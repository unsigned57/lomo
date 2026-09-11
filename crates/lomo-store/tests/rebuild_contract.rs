//! Behavior Contract (P3-06)
//!
//! Capability: rebuild enters read-only (mutations rejected), scans Markdown + `.lomo` into a
//! temporary database with checkpoints, integrity/compare, atomic `SQLite` replace; process-death
//! resume continues; `SQLite` damage never deletes `.lomo`.
//!
//! Scenarios:
//! - Given memos on disk, when rebuild runs, then projections restore and queries succeed.
//! - Given a direct Markdown memo with a reminder token, when rebuild runs, then the reminder
//!   projection keeps the filename-backed memo identity and typed token facts.
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
//!   rebuilt, then its complete body and summary are readable from one published revision without
//!   creating a second Markdown document, and the replacement revision is durable and monotonic.
//! - Given a SAF projection refresh started from revision N, when a verified live mutation advances
//!   the projection beyond N before publish, then stale staging is rejected and the live mutation
//!   remains authoritative.
//! - Given two memos share one SAF source document, when one verified mutation changes that
//!   document, then the canonical source fingerprint and every sibling projection advance in the
//!   same `SQLite` transaction.
//! - Given an active SAF memo and its durable trash record, when projection rebuild publishes, then
//!   the trash snapshot is recoverable, the memo remains trashed, and the active document's current
//!   sibling fingerprint is not rolled back by the older deletion snapshot.
//!
//! Observable outcomes: query rows and complete snapshots, error codes, rebuild evidence, durable
//! `.lomo` preservation, and absence of a user Markdown document mirror under the projection root.
//! TDD proof: SAF projection rebuild was RED on 2026-08-02 because `lomo-store` could only rebuild
//! by traversing a Direct filesystem workspace; A-SAF-REV-001 was RED because scanned rebuilds
//! returned and persisted high-water revision 0 across every replacement.
//! TDD proof: RED on 2026-08-06 because `SafProjectionRebuild::finish` replaced the live projection
//! without comparing its captured base revision, so a concurrent verified mutation could be lost.
//! TDD proof: RED on 2026-08-17 because a readable SAF projection persisted summaries but kept
//! complete bodies only in process memory, so reopen made mutations structurally impossible.
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
        LomoPaths, LomoPayload, LomoRecordKind, MemoCommand, MemoCommandKind, MemoFilters,
        MemoQuery, RebuildPhase, SafPermanentDeleteTarget, SafProjectionMutation,
        SafProjectionMutationKind, SafProjectionRebuild, ScannedHistoryProjection,
        ScannedMemoProjection, ScannedTrashProjection, StateBody, Store, WriteGate,
        commit_saf_permanent_delete_many, ensure_writable, fingerprint_content,
        project_reminder_references, read_record, rebuild_scanned_projection, run_rebuild,
        write_gate_for_checkpoint, write_record_atomic,
    };
    use tempfile::tempdir;

    fn seed_memo(root: &Path, memo: &str, content: &str, tags: &[&str]) {
        let dir = root.join("memos");
        fs::create_dir_all(&dir).expect("memos dir");
        let mut body = content.to_owned();
        for tag in tags {
            body.push_str(" #");
            body.push_str(tag);
        }
        fs::write(dir.join(format!("{memo}.md")), body).expect("write memo");
    }

    fn seed_state(root: &Path, memo: &str, pinned: bool, trashed: bool) {
        let paths = LomoPaths::for_workspace(root);
        paths.ensure_layout().expect("layout");
        let body = StateBody {
            memo_id: memo.to_owned(),
            pinned,
            trashed,
            pinned_at_ms: pinned.then_some(1_700_000_000_000),
            trashed_at_ms: trashed.then_some(1_700_000_000_001),
            tags: Vec::new(),
        };
        let body_json = serde_json::to_string(&body).expect("state json");
        write_record_atomic(
            &paths.state.join(format!("{memo}.rec")),
            &LomoPayload {
                kind: LomoRecordKind::State,
                record_id: memo.to_owned(),
                body_json,
            },
        )
        .expect("write state");
    }

    fn indexed_store(root: &Path) -> Store {
        run_rebuild(root, 8).expect("index seed markdown");
        Store::open(root).expect("open indexed store")
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
                    sort: lomo_store::MemoSort::default(),
                },
                None,
                PageSize::new(20).expect("page"),
            )
            .expect("query")
            .items
            .len()
    }

    fn pin_replay_and_delete_projection(
        store: &mut Store,
        projection_root: &Path,
        memo_id: &str,
        fingerprint: &str,
        updated: ScannedMemoProjection,
    ) {
        let pinned = store
            .commit_saf_projection_mutation(&SafProjectionMutation {
                operation_id: "saf-op-pin".to_owned(),
                kind: SafProjectionMutationKind::Pin,
                memo_id: memo_id.to_owned(),
                expected_revision: 2,
                expected_fingerprint: Some(fingerprint.to_owned()),
                projection: None,
                trashed_at_ms: None,
            })
            .expect("pin projection");
        assert_eq!(pinned.content_revision, 2);
        let pin_replay = store
            .commit_saf_projection_mutation(&SafProjectionMutation {
                operation_id: "saf-op-pin".to_owned(),
                kind: SafProjectionMutationKind::Pin,
                memo_id: memo_id.to_owned(),
                expected_revision: 2,
                expected_fingerprint: Some(fingerprint.to_owned()),
                projection: None,
                trashed_at_ms: None,
            })
            .expect("pin replay");
        assert!(pin_replay.idempotent_replay);
        assert_eq!(pin_replay.core_revision, pinned.core_revision);
        assert_eq!(pin_replay.event_sequence, pinned.event_sequence);

        store
            .commit_saf_projection_mutation(&SafProjectionMutation {
                operation_id: "saf-op-delete".to_owned(),
                kind: SafProjectionMutationKind::Delete,
                memo_id: memo_id.to_owned(),
                expected_revision: 2,
                expected_fingerprint: Some(fingerprint.to_owned()),
                projection: Some(updated),
                trashed_at_ms: Some(1_754_300_200_000),
            })
            .expect("delete projection");
        assert!(!projection_root.join("2026-08-04.md").exists());
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
            chronology_epoch_ms: 1_754_128_200_000,
            body: body.clone(),
            tags: vec!["device".to_owned()],
            attachment_paths: Vec::new(),
            has_todo: false,
            has_url: false,
            reminders: Vec::new(),
        };

        let invalid_projection = tempdir().expect("invalid projection root");
        let mut invalid_chronology = source.clone();
        invalid_chronology.chronology_epoch_ms = 0;
        let error = rebuild_scanned_projection(invalid_projection.path(), &[invalid_chronology])
            .expect_err("epoch zero must be rejected");
        assert_eq!(error.code(), "invalid_memo_chronology");

        let result = rebuild_scanned_projection(projection.path(), &[source]).expect("rebuild");
        let store = Store::open_projection(projection.path()).expect("open projection");
        let page = store
            .query_memos(
                &MemoQuery {
                    search_text: Some("searchable".to_owned()),
                    filters: MemoFilters::default(),
                    sort: lomo_store::MemoSort::default(),
                },
                None,
                PageSize::new(10).expect("page size"),
            )
            .expect("query projection");

        assert_eq!(result.memos_indexed, 1);
        assert_eq!(result.workspace_digest, result.store_digest);
        assert_eq!(result.high_water_revision, 1);
        assert_eq!(store.high_water_revision(), result.high_water_revision);
        assert_eq!(page.items.len(), 1);
        assert_eq!(
            page.items.first().expect("projected memo").source_path,
            "2026-08-02.md"
        );
        assert_eq!(
            page.items.first().expect("projected memo").created_at_ms,
            1_754_128_200_000
        );
        assert_eq!(
            page.items.first().expect("projected memo").updated_at_ms,
            1_754_128_200_000
        );
        assert!(
            !projection.path().join("2026-08-02.md").exists(),
            "SAF projection must not mirror the user Markdown file"
        );
        let snapshot = store
            .get_projected_memo("2026-08-02_19:30:00_0")
            .expect("read complete projection")
            .expect("projected memo exists");
        assert_eq!(snapshot.body, body);

        drop(store);
        let second = rebuild_scanned_projection(projection.path(), &[]).expect("second rebuild");
        let reopened = Store::open_projection(projection.path()).expect("reopen projection");
        assert_eq!(second.high_water_revision, 2);
        assert_eq!(reopened.high_water_revision(), second.high_water_revision);
    }

    #[test]
    fn saf_projection_commit_updates_only_projection_and_is_idempotent() {
        let projection_root = tempdir().expect("projection root");
        let old_body = "old SAF body".to_owned();
        let old = ScannedMemoProjection {
            memo_id: "2026-08-04_09:30:00_0".to_owned(),
            source_path: "2026-08-04.md".to_owned(),
            file_fingerprint: fingerprint_content(&old_body),
            chronology_epoch_ms: 1_754_300_000_000,
            body: old_body,
            tags: vec!["old".to_owned()],
            attachment_paths: Vec::new(),
            has_todo: false,
            has_url: false,
            reminders: Vec::new(),
        };
        rebuild_scanned_projection(projection_root.path(), std::slice::from_ref(&old))
            .expect("initial rebuild");

        let new_body = "new SAF body".to_owned();
        let updated = ScannedMemoProjection {
            file_fingerprint: fingerprint_content(&new_body),
            body: new_body,
            source_path: old.source_path.clone(),
            chronology_epoch_ms: old.chronology_epoch_ms + 1_000,
            tags: vec!["new".to_owned()],
            ..old.clone()
        };
        let mut store = Store::open_projection(projection_root.path()).expect("open projection");
        let commit = store
            .commit_saf_projection_mutation(&SafProjectionMutation {
                operation_id: "saf-op-update".to_owned(),
                kind: SafProjectionMutationKind::Update,
                memo_id: old.memo_id.clone(),
                expected_revision: 1,
                expected_fingerprint: Some(old.file_fingerprint.clone()),
                projection: Some(updated.clone()),
                trashed_at_ms: None,
            })
            .expect("projection update");
        assert_eq!(commit.content_revision, 2);
        assert!(!commit.idempotent_replay);
        assert_eq!(store.high_water_revision(), commit.core_revision);

        let replay = store
            .commit_saf_projection_mutation(&SafProjectionMutation {
                operation_id: "saf-op-update".to_owned(),
                kind: SafProjectionMutationKind::Update,
                memo_id: old.memo_id.clone(),
                expected_revision: 1,
                expected_fingerprint: Some(old.file_fingerprint),
                projection: Some(updated.clone()),
                trashed_at_ms: None,
            })
            .expect("idempotent replay");
        assert!(replay.idempotent_replay);
        assert_eq!(replay.content_revision, 2);
        assert_eq!(replay.core_revision, commit.core_revision);
        assert_eq!(replay.event_sequence, commit.event_sequence);

        let conflicting_replay = store
            .commit_saf_projection_mutation(&SafProjectionMutation {
                operation_id: "saf-op-update".to_owned(),
                kind: SafProjectionMutationKind::Delete,
                memo_id: old.memo_id.clone(),
                expected_revision: 2,
                expected_fingerprint: Some(commit.file_fingerprint.clone()),
                projection: None,
                trashed_at_ms: Some(1_754_300_100_000),
            })
            .expect_err("one operation id cannot identify two SAF mutations");
        assert_eq!(conflicting_replay.code(), "saf_operation_conflict");

        pin_replay_and_delete_projection(
            &mut store,
            projection_root.path(),
            &old.memo_id,
            &commit.file_fingerprint,
            updated,
        );
    }

    #[test]
    fn saf_history_restore_commits_a_new_revision_with_the_restored_body() {
        let projection_root = tempdir().expect("projection root");
        let original = ScannedMemoProjection {
            memo_id: "2026-08-04_09:30:00_0".to_owned(),
            source_path: "2026-08-04.md".to_owned(),
            file_fingerprint: fingerprint_content("current body"),
            chronology_epoch_ms: 1_754_300_000_000,
            body: "current body".to_owned(),
            tags: Vec::new(),
            attachment_paths: Vec::new(),
            has_todo: false,
            has_url: false,
            reminders: Vec::new(),
        };
        rebuild_scanned_projection(projection_root.path(), std::slice::from_ref(&original))
            .expect("seed projection");
        let restored = ScannedMemoProjection {
            file_fingerprint: fingerprint_content("restored historical body"),
            body: "restored historical body".to_owned(),
            ..original.clone()
        };
        let mut store = Store::open_projection(projection_root.path()).expect("open projection");

        let commit = store
            .commit_saf_projection_mutation(&SafProjectionMutation {
                operation_id: "saf-history-restore".to_owned(),
                kind: SafProjectionMutationKind::HistoryRestore,
                memo_id: original.memo_id.clone(),
                expected_revision: 1,
                expected_fingerprint: Some(original.file_fingerprint),
                projection: Some(restored),
                trashed_at_ms: None,
            })
            .expect("history restore projection");
        let page = store
            .list_memo_history(&original.memo_id, None, 10)
            .expect("history page");

        assert_eq!(commit.content_revision, 2);
        assert_eq!(page.items.len(), 1);
        let restored_revision = page.items.first().expect("restored revision");
        assert_eq!(restored_revision.revision, 2);
        assert_eq!(restored_revision.content, "restored historical body");
    }

    #[test]
    fn saf_projection_refresh_preserves_committed_history_index() {
        let projection_root = tempdir().expect("projection root");
        let memo = ScannedMemoProjection {
            memo_id: "2026-08-04_09:30:00_0".to_owned(),
            source_path: "2026-08-04.md".to_owned(),
            file_fingerprint: fingerprint_content("first body"),
            chronology_epoch_ms: 1_754_300_000_000,
            body: "first body".to_owned(),
            tags: Vec::new(),
            attachment_paths: Vec::new(),
            has_todo: false,
            has_url: false,
            reminders: Vec::new(),
        };
        rebuild_scanned_projection(projection_root.path(), std::slice::from_ref(&memo))
            .expect("seed projection");
        let updated = ScannedMemoProjection {
            file_fingerprint: fingerprint_content("second body"),
            body: "second body".to_owned(),
            ..memo.clone()
        };
        let mut store = Store::open_projection(projection_root.path()).expect("open projection");
        store
            .commit_saf_projection_mutation(&SafProjectionMutation {
                operation_id: "history-before-refresh".to_owned(),
                kind: SafProjectionMutationKind::Update,
                memo_id: memo.memo_id.clone(),
                expected_revision: 1,
                expected_fingerprint: Some(memo.file_fingerprint),
                projection: Some(updated.clone()),
                trashed_at_ms: None,
            })
            .expect("commit update");
        drop(store);

        let mut refresh =
            SafProjectionRebuild::begin(projection_root.path()).expect("begin refresh");
        refresh
            .append_page(std::slice::from_ref(&updated))
            .expect("active page");
        refresh
            .append_history_page(&[ScannedHistoryProjection {
                record_id: format!("{}-r2", memo.memo_id),
                memo_id: memo.memo_id.clone(),
                revision: 2,
                created_at_ms: updated.chronology_epoch_ms,
                content: updated.body.clone(),
                file_fingerprint: updated.file_fingerprint.clone(),
            }])
            .expect("history page");
        refresh.finish().expect("refresh projection");
        let reopened = Store::open_projection(projection_root.path()).expect("reopen projection");
        let history = reopened
            .list_memo_history(&memo.memo_id, None, 10)
            .expect("history after refresh");

        assert_eq!(history.items.len(), 1);
        let retained_revision = history.items.first().expect("retained revision");
        assert_eq!(retained_revision.revision, 2);
        assert_eq!(retained_revision.content, "second body");
    }

    #[test]
    fn saf_projection_create_replay_returns_the_original_commit() {
        let projection_root = tempdir().expect("projection root");
        rebuild_scanned_projection(projection_root.path(), &[]).expect("empty projection");
        let body = "new SAF memo".to_owned();
        let projection = ScannedMemoProjection {
            memo_id: "2026-08-04_12:00:00_0".to_owned(),
            source_path: "2026-08-04.md".to_owned(),
            file_fingerprint: fingerprint_content(&body),
            chronology_epoch_ms: 1_754_309_000_000,
            body,
            tags: Vec::new(),
            attachment_paths: Vec::new(),
            has_todo: false,
            has_url: false,
            reminders: Vec::new(),
        };
        let mutation = SafProjectionMutation {
            operation_id: "saf-op-create".to_owned(),
            kind: SafProjectionMutationKind::Create,
            memo_id: projection.memo_id.clone(),
            expected_revision: 0,
            expected_fingerprint: None,
            projection: Some(projection),
            trashed_at_ms: None,
        };
        let mut store = Store::open_projection(projection_root.path()).expect("open projection");

        let commit = store
            .commit_saf_projection_mutation(&mutation)
            .expect("create projection");
        let replay = store
            .commit_saf_projection_mutation(&mutation)
            .expect("create replay");

        assert!(replay.idempotent_replay);
        assert_eq!(replay.core_revision, commit.core_revision);
        assert_eq!(replay.event_sequence, commit.event_sequence);
        assert_eq!(replay.content_revision, commit.content_revision);
        assert_eq!(replay.file_fingerprint, commit.file_fingerprint);
    }

    #[test]
    fn saf_document_fingerprint_advances_for_every_sibling_atomically() {
        let projection_root = tempdir().expect("projection root");
        let old_fingerprint = fingerprint_content("original shared document bytes");
        let first = ScannedMemoProjection {
            memo_id: "2026_08_04_09:00:00_0".to_owned(),
            source_path: "2026_08_04.md".to_owned(),
            file_fingerprint: old_fingerprint.clone(),
            chronology_epoch_ms: 1_754_298_000_000,
            body: "first".to_owned(),
            tags: Vec::new(),
            attachment_paths: Vec::new(),
            has_todo: false,
            has_url: false,
            reminders: Vec::new(),
        };
        let sibling = ScannedMemoProjection {
            memo_id: "2026_08_04_10:00:00_0".to_owned(),
            chronology_epoch_ms: 1_754_301_600_000,
            body: "sibling".to_owned(),
            ..first.clone()
        };
        rebuild_scanned_projection(projection_root.path(), &[first.clone(), sibling.clone()])
            .expect("seed shared source document");
        let new_fingerprint = fingerprint_content("verified rewritten shared document bytes");
        let updated = ScannedMemoProjection {
            file_fingerprint: new_fingerprint.clone(),
            body: "first updated".to_owned(),
            ..first.clone()
        };
        let mut store = Store::open_projection(projection_root.path()).expect("open projection");

        store
            .commit_saf_projection_mutation(&SafProjectionMutation {
                operation_id: "saf-shared-document-update".to_owned(),
                kind: SafProjectionMutationKind::Update,
                memo_id: first.memo_id,
                expected_revision: 1,
                expected_fingerprint: Some(old_fingerprint),
                projection: Some(updated),
                trashed_at_ms: None,
            })
            .expect("commit shared document update");

        assert_eq!(
            store
                .source_document_fingerprint("2026_08_04.md")
                .expect("source fingerprint"),
            Some(new_fingerprint.clone())
        );
        assert_eq!(
            store
                .get_memo_projection(&sibling.memo_id)
                .expect("sibling query")
                .expect("sibling")
                .file_fingerprint,
            new_fingerprint,
            "a source-document mutation must not leave sibling rows stale"
        );
    }

    #[test]
    fn saf_delete_commits_verified_document_fingerprint_and_recoverable_body_projection() {
        let projection_root = tempdir().expect("projection root");
        let old_fingerprint = fingerprint_content("shared bytes before remove");
        let removed = ScannedMemoProjection {
            memo_id: "2026_08_04_09:00:00_0".to_owned(),
            source_path: "2026_08_04.md".to_owned(),
            file_fingerprint: old_fingerprint.clone(),
            chronology_epoch_ms: 1_754_298_000_000,
            body: "recoverable deleted body".to_owned(),
            tags: vec!["trash".to_owned()],
            attachment_paths: Vec::new(),
            has_todo: false,
            has_url: false,
            reminders: Vec::new(),
        };
        let sibling = ScannedMemoProjection {
            memo_id: "2026_08_04_10:00:00_0".to_owned(),
            chronology_epoch_ms: 1_754_301_600_000,
            body: "still active".to_owned(),
            tags: Vec::new(),
            ..removed.clone()
        };
        rebuild_scanned_projection(projection_root.path(), &[removed.clone(), sibling.clone()])
            .expect("seed shared source");
        let deletion_projection = ScannedMemoProjection {
            file_fingerprint: old_fingerprint.clone(),
            ..removed.clone()
        };
        let mut store = Store::open_projection(projection_root.path()).expect("open projection");

        let commit = store
            .commit_saf_projection_mutation(&SafProjectionMutation {
                operation_id: "saf-shared-document-delete".to_owned(),
                kind: SafProjectionMutationKind::Delete,
                memo_id: removed.memo_id.clone(),
                expected_revision: 1,
                expected_fingerprint: Some(old_fingerprint),
                projection: Some(deletion_projection),
                trashed_at_ms: Some(1_754_305_000_000),
            })
            .expect("commit verified delete");

        assert_eq!(commit.file_fingerprint, removed.file_fingerprint);
        assert!(
            store
                .get_memo_projection(&removed.memo_id)
                .expect("deleted projection")
                .expect("deleted memo")
                .is_trashed
        );
        assert_eq!(
            store
                .get_memo_projection(&sibling.memo_id)
                .expect("sibling projection")
                .expect("sibling")
                .file_fingerprint,
            commit.file_fingerprint
        );
    }

    #[test]
    fn saf_trash_lifecycle_restores_or_permanently_removes_only_after_verified_workspace_facts() {
        let projection_root = tempdir().expect("projection root");
        let original_fingerprint = fingerprint_content("daily document before permanent delete");
        let target = ScannedMemoProjection {
            memo_id: "2026_08_13_09:00:00_0".to_owned(),
            source_path: "2026_08_13.md".to_owned(),
            file_fingerprint: original_fingerprint.clone(),
            chronology_epoch_ms: 1_755_058_800_000,
            body: "trash lifecycle target".to_owned(),
            tags: vec!["lifecycle".to_owned()],
            attachment_paths: Vec::new(),
            has_todo: false,
            has_url: false,
            reminders: Vec::new(),
        };
        let sibling = ScannedMemoProjection {
            memo_id: "2026_08_13_10:00:00_0".to_owned(),
            chronology_epoch_ms: 1_755_062_400_000,
            body: "surviving sibling".to_owned(),
            tags: Vec::new(),
            ..target.clone()
        };
        rebuild_scanned_projection(projection_root.path(), &[target.clone(), sibling.clone()])
            .expect("seed");
        let mut store = Store::open_projection(projection_root.path()).expect("open");
        let delete = |operation_id: &str, expected_revision: u64| SafProjectionMutation {
            operation_id: operation_id.to_owned(),
            kind: SafProjectionMutationKind::Delete,
            memo_id: target.memo_id.clone(),
            expected_revision,
            expected_fingerprint: Some(original_fingerprint.clone()),
            projection: Some(target.clone()),
            trashed_at_ms: Some(1_755_063_000_000),
        };

        store
            .commit_saf_projection_mutation(&delete("trash-before-restore", 1))
            .expect("trash");
        store
            .commit_saf_projection_mutation(&SafProjectionMutation {
                operation_id: "restore-durable-trash".to_owned(),
                kind: SafProjectionMutationKind::Restore,
                memo_id: target.memo_id.clone(),
                expected_revision: 1,
                expected_fingerprint: Some(original_fingerprint.clone()),
                projection: Some(target.clone()),
                trashed_at_ms: None,
            })
            .expect("restore");
        assert!(
            !store
                .get_memo_projection(&target.memo_id)
                .expect("restored query")
                .expect("restored")
                .is_trashed
        );

        store
            .commit_saf_projection_mutation(&delete("trash-before-permanent", 2))
            .expect("trash again");
        let final_fingerprint = fingerprint_content("daily document after permanent delete");
        store
            .commit_saf_projection_mutation(&SafProjectionMutation {
                operation_id: "permanent-delete-durable-trash".to_owned(),
                kind: SafProjectionMutationKind::PermanentDelete,
                memo_id: target.memo_id.clone(),
                expected_revision: 2,
                expected_fingerprint: Some(original_fingerprint),
                projection: Some(ScannedMemoProjection {
                    file_fingerprint: final_fingerprint.clone(),
                    ..target.clone()
                }),
                trashed_at_ms: None,
            })
            .expect("permanent delete");

        assert!(
            store
                .get_memo_projection(&target.memo_id)
                .expect("deleted query")
                .is_none()
        );
        assert_eq!(
            store
                .get_memo_projection(&sibling.memo_id)
                .expect("sibling query")
                .expect("sibling")
                .file_fingerprint,
            final_fingerprint
        );
    }

    #[test]
    fn saf_projection_rebuild_pages_are_invisible_until_atomic_finish() {
        let projection_root = tempdir().expect("projection root");
        let old_body = "old projection".to_owned();
        let old = ScannedMemoProjection {
            memo_id: "2026_08_03_10:00:00_0".to_owned(),
            source_path: "2026_08_03.md".to_owned(),
            file_fingerprint: fingerprint_content(&old_body),
            chronology_epoch_ms: 1_754_200_000_000,
            body: old_body,
            tags: vec![],
            attachment_paths: vec![],
            has_todo: false,
            has_url: false,
            reminders: Vec::new(),
        };
        rebuild_scanned_projection(projection_root.path(), std::slice::from_ref(&old))
            .expect("seed projection");
        let new_body = "new projection".to_owned();
        let new = ScannedMemoProjection {
            memo_id: "2026_08_04_10:00:00_0".to_owned(),
            source_path: "2026_08_04.md".to_owned(),
            file_fingerprint: fingerprint_content(&new_body),
            chronology_epoch_ms: 1_754_300_000_000,
            body: new_body,
            tags: vec!["streamed".to_owned()],
            attachment_paths: vec![],
            has_todo: false,
            has_url: false,
            reminders: Vec::new(),
        };

        let mut rebuild = SafProjectionRebuild::begin(projection_root.path()).expect("begin");
        rebuild
            .append_page(std::slice::from_ref(&new))
            .expect("append page");
        let before_finish =
            Store::open_projection(projection_root.path()).expect("live projection");
        assert!(
            before_finish
                .get_memo_projection(&old.memo_id)
                .expect("old query")
                .is_some()
        );
        assert!(
            before_finish
                .get_memo_projection(&new.memo_id)
                .expect("new query")
                .is_none()
        );
        drop(before_finish);

        let result = rebuild.finish().expect("finish");
        let finished = Store::open_projection(projection_root.path()).expect("finished projection");
        assert_eq!(result.memos_indexed, 1);
        assert!(
            finished
                .get_memo_projection(&old.memo_id)
                .expect("old query")
                .is_none()
        );
        assert!(
            finished
                .get_memo_projection(&new.memo_id)
                .expect("new query")
                .is_some()
        );
    }

    #[test]
    fn saf_projection_refresh_rejects_publish_after_live_revision_advances() {
        let projection_root = tempdir().expect("projection root");
        let old_body = "published projection".to_owned();
        let old = ScannedMemoProjection {
            memo_id: "2026_08_03_10:00:00_0".to_owned(),
            source_path: "2026_08_03.md".to_owned(),
            file_fingerprint: fingerprint_content(&old_body),
            chronology_epoch_ms: 1_754_200_000_000,
            body: old_body,
            tags: vec![],
            attachment_paths: vec![],
            has_todo: false,
            has_url: false,
            reminders: Vec::new(),
        };
        rebuild_scanned_projection(projection_root.path(), std::slice::from_ref(&old))
            .expect("seed projection");

        let refreshed_body = "staged refresh".to_owned();
        let refreshed = ScannedMemoProjection {
            file_fingerprint: fingerprint_content(&refreshed_body),
            body: refreshed_body,
            ..old.clone()
        };
        let mut refresh =
            SafProjectionRebuild::begin(projection_root.path()).expect("begin refresh");
        refresh
            .append_page(std::slice::from_ref(&refreshed))
            .expect("append staged page");

        let live_body = "verified live mutation".to_owned();
        let live = ScannedMemoProjection {
            memo_id: "2026_08_04_11:00:00_0".to_owned(),
            source_path: "2026_08_04.md".to_owned(),
            file_fingerprint: fingerprint_content(&live_body),
            chronology_epoch_ms: 1_754_303_600_000,
            body: live_body,
            tags: vec!["live".to_owned()],
            attachment_paths: vec![],
            has_todo: false,
            has_url: false,
            reminders: Vec::new(),
        };
        let mut store =
            Store::open_projection(projection_root.path()).expect("open live projection");
        store
            .commit_saf_projection_mutation(&SafProjectionMutation {
                operation_id: "saf-op-during-refresh".to_owned(),
                kind: SafProjectionMutationKind::Create,
                memo_id: live.memo_id.clone(),
                expected_revision: 0,
                expected_fingerprint: None,
                projection: Some(live.clone()),
                trashed_at_ms: None,
            })
            .expect("commit verified live mutation");
        drop(store);

        let error = refresh
            .finish()
            .expect_err("staging from an older projection revision must not publish");
        assert_eq!(error.code(), "stale_saf_projection_rebuild");

        let preserved =
            Store::open_projection(projection_root.path()).expect("reopen live projection");
        assert!(
            preserved
                .get_memo_projection(&live.memo_id)
                .expect("live query")
                .is_some(),
            "the verified live mutation must survive stale refresh rejection"
        );
        let old_after = preserved
            .get_memo_projection(&old.memo_id)
            .expect("old query")
            .expect("old projection remains live");
        assert_eq!(old_after.file_fingerprint, old.file_fingerprint);
    }

    #[test]
    fn saf_projection_rebuild_rejects_duplicate_page_and_abort_preserves_live() {
        let projection_root = tempdir().expect("projection root");
        let body = "stable projection".to_owned();
        let memo = ScannedMemoProjection {
            memo_id: "2026_08_04_11:00:00_0".to_owned(),
            source_path: "2026_08_04.md".to_owned(),
            file_fingerprint: fingerprint_content(&body),
            chronology_epoch_ms: 1_754_303_600_000,
            body,
            tags: vec![],
            attachment_paths: vec![],
            has_todo: false,
            has_url: false,
            reminders: Vec::new(),
        };
        rebuild_scanned_projection(projection_root.path(), std::slice::from_ref(&memo))
            .expect("seed projection");
        let mut rebuild = SafProjectionRebuild::begin(projection_root.path()).expect("begin");
        rebuild
            .append_page(std::slice::from_ref(&memo))
            .expect("first page");
        let duplicate = rebuild
            .append_page(std::slice::from_ref(&memo))
            .expect_err("duplicate scan page must fail closed");
        assert_eq!(duplicate.code(), "duplicate_saf_projection_memo");
        rebuild.abort().expect("abort");

        let live = Store::open_projection(projection_root.path()).expect("live projection");
        assert!(
            live.get_memo_projection(&memo.memo_id)
                .expect("memo query")
                .is_some()
        );
    }

    #[test]
    fn saf_rebuild_merges_durable_trash_snapshot_without_rolling_back_source_fingerprint() {
        let projection_root = tempdir().expect("projection root");
        let current_fingerprint = fingerprint_content("current shared daily document");
        let active = ScannedMemoProjection {
            memo_id: "2026_08_12_08:15:00_0".to_owned(),
            source_path: "2026_08_12.md".to_owned(),
            file_fingerprint: current_fingerprint.clone(),
            chronology_epoch_ms: 1_754_972_100_000,
            body: "active bytes retained after soft delete".to_owned(),
            tags: vec!["active".to_owned()],
            attachment_paths: Vec::new(),
            has_todo: false,
            has_url: false,
            reminders: Vec::new(),
        };
        let trash = ScannedTrashProjection {
            memo: ScannedMemoProjection {
                file_fingerprint: fingerprint_content("document at deletion time"),
                body: "recoverable trash snapshot sentinel".to_owned(),
                tags: vec!["trash".to_owned()],
                ..active.clone()
            },
            trashed_at_ms: 1_754_972_200_000,
        };
        let mut rebuild = SafProjectionRebuild::begin(projection_root.path()).expect("begin");

        rebuild
            .append_page(std::slice::from_ref(&active))
            .expect("active page");
        rebuild
            .append_trash_page(std::slice::from_ref(&trash))
            .expect("trash page");
        let result = rebuild.finish().expect("publish");
        let store = Store::open_projection(projection_root.path()).expect("open");
        let projected = store
            .get_memo_projection(&active.memo_id)
            .expect("query")
            .expect("memo");
        let trash_search = store
            .query_memos(
                &MemoQuery {
                    search_text: Some("sentinel".to_owned()),
                    filters: MemoFilters {
                        include_trash: true,
                        trash_only: true,
                        ..MemoFilters::default()
                    },
                    sort: lomo_store::MemoSort::default(),
                },
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("trash search");

        assert_eq!(result.memos_indexed, 1);
        assert!(projected.is_trashed);
        assert_eq!(projected.file_fingerprint, current_fingerprint);
        assert_eq!(projected.tags, vec!["trash"]);
        assert_eq!(trash_search.items.len(), 1);
    }

    #[test]
    fn rebuild_restores_projections_without_deleting_lomo() {
        let dir = tempdir().expect("tempdir");
        seed_memo(dir.path(), "r1", "rebuild me 你好", &["x"]);
        seed_state(dir.path(), "r1", true, false);
        let store = indexed_store(dir.path());

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
                    sort: lomo_store::MemoSort::default(),
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
                    sort: lomo_store::MemoSort::default(),
                },
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("pin query");
        assert_eq!(pin_page.items.len(), 1);
    }

    #[test]
    fn direct_rebuild_projects_reminder_identity_and_token() {
        let dir = tempdir().expect("tempdir");
        let memo_id = "2026-07-20_10:00:00_0";
        fs::create_dir_all(dir.path().join("memos")).expect("memos dir");
        fs::write(
            dir.path().join("memos").join(format!("{memo_id}.md")),
            "# note\n\n@2026-07-20-09:30x2",
        )
        .expect("memo body");

        let result = run_rebuild(dir.path(), 8).expect("direct rebuild");
        assert_eq!(result.memos_indexed, 1);
        let store = Store::open(dir.path()).expect("open rebuilt store");
        let projection = store
            .get_memo_projection(memo_id)
            .expect("projection")
            .expect("memo");
        let reminder = projection.reminders.first().expect("one reminder");
        assert_eq!(projection.reminders.len(), 1);
        assert_eq!(reminder.memo_identity, memo_id);
        assert_eq!(reminder.token, "@2026-07-20-09:30x2");
        assert_eq!(reminder.repeat_count, 2);
    }

    #[test]
    fn saf_batch_replay_preserves_reminder_identity_facts() {
        let dir = tempdir().expect("tempdir");
        let mut store = Store::open(dir.path()).expect("open");
        let memo_id = "2026_09_03_09:30:00_0";
        let body = "batch reminder @2026-09-03-09:30";
        let fingerprint = fingerprint_content(body);
        let reminders = project_reminder_references(body, memo_id).expect("reminder facts");
        let projection = ScannedMemoProjection {
            memo_id: memo_id.to_owned(),
            source_path: "2026_09_03.md".to_owned(),
            file_fingerprint: fingerprint.clone(),
            chronology_epoch_ms: 1_756_876_200_000,
            body: body.to_owned(),
            tags: Vec::new(),
            attachment_paths: Vec::new(),
            has_todo: false,
            has_url: false,
            reminders,
        };
        store
            .commit_saf_projection_mutation(&SafProjectionMutation {
                operation_id: "saf-batch-create".to_owned(),
                kind: SafProjectionMutationKind::Create,
                memo_id: memo_id.to_owned(),
                expected_revision: 0,
                expected_fingerprint: None,
                projection: Some(projection.clone()),
                trashed_at_ms: None,
            })
            .expect("create projection");
        store
            .commit_saf_projection_mutation(&SafProjectionMutation {
                operation_id: "saf-batch-trash".to_owned(),
                kind: SafProjectionMutationKind::Delete,
                memo_id: memo_id.to_owned(),
                expected_revision: 1,
                expected_fingerprint: Some(fingerprint.clone()),
                projection: Some(projection),
                trashed_at_ms: Some(1_756_876_300_000),
            })
            .expect("trash projection");
        let target = SafPermanentDeleteTarget {
            memo_id: memo_id.to_owned(),
            source_path: "2026_09_03.md".to_owned(),
            expected_revision: 1,
            expected_fingerprint: fingerprint,
            result_fingerprint: "0".repeat(64),
        };

        let first = commit_saf_permanent_delete_many(
            dir.path(),
            "saf-batch-purge",
            std::slice::from_ref(&target),
        )
        .expect("first batch commit");
        let first_deleted = first.deleted.first().expect("deleted memo");
        assert_eq!(first_deleted.reminder_ids.len(), 1);

        let replay = commit_saf_permanent_delete_many(
            dir.path(),
            "saf-batch-purge",
            std::slice::from_ref(&target),
        )
        .expect("batch replay");
        assert!(replay.idempotent_replay);
        assert_eq!(replay.deleted, first.deleted);
    }

    #[test]
    fn rebuild_rehydrates_tags_after_sqlite_wipe() {
        let dir = tempdir().expect("tempdir");
        seed_memo(
            dir.path(),
            "tagged",
            "body with tag dimension #project_alpha",
            &[],
        );
        let store = indexed_store(dir.path());

        let before = store
            .query_memos(
                &MemoQuery {
                    search_text: None,
                    filters: MemoFilters {
                        tag: Some("project_alpha".into()),
                        ..MemoFilters::default()
                    },
                    sort: lomo_store::MemoSort::default(),
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
                        tag: Some("project_alpha".into()),
                        ..MemoFilters::default()
                    },
                    sort: lomo_store::MemoSort::default(),
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
        seed_memo(dir.path(), "tp1", "trash pin body", &["keep"]);
        seed_state(dir.path(), "tp1", true, true);
        let store = indexed_store(dir.path());
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
                    sort: lomo_store::MemoSort::default(),
                },
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("tag after rebuild");
        assert_eq!(tag_hits.items.len(), 1);
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
                    sort: lomo_store::MemoSort::default(),
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

    fn assert_durable_pin_trash_tags(root: &Path, memo_id: &str, _tag: &str) {
        let paths = LomoPaths::for_workspace(root);
        let record = read_record(&paths.state.join(format!("{memo_id}.rec"))).expect("state");
        let body: StateBody = serde_json::from_str(&record.payload.body_json).expect("state body");
        assert!(body.pinned, "durable state must remain pinned");
        assert!(body.trashed, "durable state must remain trashed after pin");
    }

    #[test]
    fn replacing_phase_with_temp_gone_does_not_destroy_live_db() {
        let dir = tempdir().expect("tempdir");
        seed_memo(dir.path(), "rep1", "replace boundary body", &["edge"]);
        let store = indexed_store(dir.path());
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
                    sort: lomo_store::MemoSort::default(),
                },
                None,
                PageSize::new(10).expect("page"),
            )
            .expect("query live after resume");
        assert_eq!(page.items.first().map(|m| m.memo_id.as_str()), Some("rep1"));
        let err = store
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
            .expect_err("direct create remains refused after rebuild");
        assert_eq!(err.code(), "session_owns_document_writes");
    }

    #[test]
    fn mid_indexing_checkpoint_resumes_without_duplicate_or_stuck_gate() {
        let dir = tempdir().expect("tempdir");
        for i in 0..5 {
            seed_memo(
                dir.path(),
                &format!("mid{i}"),
                &format!("mid body {i} 搜索"),
                &["batch"],
            );
        }
        let store = indexed_store(dir.path());
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
                    sort: lomo_store::MemoSort::default(),
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
                    sort: lomo_store::MemoSort::default(),
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
        seed_memo(dir.path(), "rbw", "rebuild wrapper body", &["w"]);
        let store = indexed_store(dir.path());
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
        seed_memo(store.workspace_root(), "rbw2", "after rebuild", &["w"]);
        let (store, _second) = store.rebuild(8).expect("reindex second memo");
        assert_eq!(query_all(&store), 2);
    }

    #[test]
    fn rebuild_isolates_corrupt_lomo_state_and_history_without_deleting_tree() {
        let dir = tempdir().expect("tempdir");
        seed_memo(dir.path(), "iso1", "updated isolation body", &["iso"]);
        let store = indexed_store(dir.path());
        let db_path = store.open_info().database_path;
        drop(store);

        let paths = LomoPaths::for_workspace(dir.path());
        fs::create_dir_all(&paths.state).expect("state dir");
        fs::create_dir_all(&paths.history).expect("history dir");
        let state_path = paths.state.join("iso1.rec");
        fs::write(&state_path, b"not-a-valid-lomo-record").expect("bad state");
        fs::write(
            paths.history.join("iso1-r1.rec"),
            b"not-a-valid-lomo-record",
        )
        .expect("bad history");

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
