//! Behavior Contract: bulk media-reference projection reads (audit-01 T36)
//!
//! Capability: the projection answers the complete media keep-set inputs in bounded bulk reads —
//! one scan of `attachment_ref` rows carrying each owner's trash state, and one windowed scan of
//! in-window history bodies — so the session sweep never runs per-memo queries and never treats
//! an incomplete projection as an empty keep-set.
//!
//! Scenarios:
//! - Given live and trashed memos whose projected bodies name image and audio attachments, when
//!   refs are listed, then every path appears with its owner id and trash bit — audio included.
//! - Given more durable revisions than the retention window, when history bodies are listed, then
//!   only the newest in-window revisions per memo come back.
//! - Given an in-window revision whose projected body is absent, when bodies are listed, then
//!   corruption surfaces instead of silently shrinking the keep-set.
//! - Given a zero retention window, when bodies are listed, then validation rejects the call.
//!
//! Observable outcomes: returned ref rows, returned bodies in memo/revision order, structured
//! failures.
//!
//! TDD proof: the disk-scanning `list_history_attachment_refs` could not answer trash state,
//! covered only image paths, and scanned history files per memo; these bulk reads did not exist.
//!
//! Excludes:
//!
//! - Sweep planning/execution (session layer), draft and stage-lease protections, and platform
//!   evidence verification.

mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use lomo_store::{
        SafProjectionMutation, SafProjectionMutationKind, ScannedMemoProjection, Store,
        StoreReader, database_path, fingerprint_content, rebuild_scanned_projection,
    };
    use tempfile::tempdir;

    fn projection(
        memo_id: &str,
        source_path: &str,
        body: &str,
        attachments: &[&str],
    ) -> ScannedMemoProjection {
        ScannedMemoProjection {
            memo_id: memo_id.to_owned(),
            source_path: source_path.to_owned(),
            file_fingerprint: fingerprint_content(body),
            chronology_epoch_ms: 1_754_300_000_000,
            body: body.to_owned(),
            tags: Vec::new(),
            attachment_paths: attachments.iter().map(|path| (*path).to_owned()).collect(),
            has_todo: false,
            has_url: false,
            reminders: Vec::new(),
        }
    }

    #[test]
    fn projected_refs_cover_all_types_and_owner_trash_state() {
        let root = tempdir().expect("projection root");
        let live = projection(
            "2026-08-04_09:30:00_0",
            "2026-08-04.md",
            "live body",
            &["media/img/a.png", "media/voice/a.m4a"],
        );
        let doomed = projection(
            "2026-08-04_10:30:00_1",
            "2026-08-04.md",
            "trashed body",
            &["media/img/b.png"],
        );
        rebuild_scanned_projection(root.path(), &[live.clone(), doomed.clone()])
            .expect("seed projection");
        let mut store = Store::open_projection(root.path()).expect("open projection");
        store
            .commit_saf_projection_mutation(&SafProjectionMutation {
                operation_id: "trash-b".to_owned(),
                kind: SafProjectionMutationKind::Delete,
                memo_id: doomed.memo_id.clone(),
                expected_revision: 1,
                expected_fingerprint: Some(doomed.file_fingerprint.clone()),
                projection: Some(doomed.clone()),
                trashed_at_ms: Some(1_754_300_100_000),
                batch_targets: Vec::new(),
            })
            .expect("trash publication");

        let reader = StoreReader::open(root.path()).expect("reader");
        let refs = reader.list_projected_attachment_refs().expect("ref scan");

        assert_eq!(refs.len(), 3);
        let audio = refs
            .iter()
            .find(|item| item.relative_path == "media/voice/a.m4a")
            .expect("audio ref survives projection");
        assert_eq!(audio.memo_id, live.memo_id);
        assert!(!audio.is_trashed);
        let trashed = refs
            .iter()
            .find(|item| item.memo_id == doomed.memo_id)
            .expect("trashed memo keeps its refs");
        assert_eq!(trashed.relative_path, "media/img/b.png");
        assert!(trashed.is_trashed);
    }

    #[test]
    fn history_bodies_stay_inside_the_retention_window() {
        let root = tempdir().expect("projection root");
        let memo = projection("2026-08-04_09:30:00_0", "2026-08-04.md", "rev1", &[]);
        rebuild_scanned_projection(root.path(), std::slice::from_ref(&memo))
            .expect("seed projection");
        let mut store = Store::open_projection(root.path()).expect("open projection");
        for (index, revision) in (2_u64..=4).enumerate() {
            let updated = projection(
                &memo.memo_id,
                &memo.source_path,
                &format!("rev{revision}"),
                &[],
            );
            store
                .commit_saf_projection_mutation(&SafProjectionMutation {
                    operation_id: format!("update-{index}"),
                    kind: SafProjectionMutationKind::Update,
                    memo_id: memo.memo_id.clone(),
                    expected_revision: revision - 1,
                    expected_fingerprint: Some(fingerprint_content(&format!(
                        "rev{}",
                        revision - 1
                    ))),
                    projection: Some(updated),
                    trashed_at_ms: None,
                    batch_targets: Vec::new(),
                })
                .expect("update publication");
        }

        let reader = StoreReader::open(root.path()).expect("reader");
        let bodies = reader
            .list_history_revision_bodies(2)
            .expect("windowed bodies");

        let revisions: Vec<u64> = bodies.iter().map(|item| item.revision).collect();
        assert_eq!(revisions, vec![4, 3]);
        assert!(bodies.iter().all(|item| item.memo_id == memo.memo_id));
        let contents: Vec<&str> = bodies.iter().map(|item| item.content.as_str()).collect();
        assert_eq!(contents, vec!["rev4", "rev3"]);
    }

    #[test]
    fn missing_in_window_body_is_corruption_not_an_empty_set() {
        let root = tempdir().expect("projection root");
        let memo = projection("2026-08-04_09:30:00_0", "2026-08-04.md", "rev1", &[]);
        rebuild_scanned_projection(root.path(), std::slice::from_ref(&memo))
            .expect("seed projection");
        let mut store = Store::open_projection(root.path()).expect("open projection");
        let updated = projection(&memo.memo_id, &memo.source_path, "rev2", &[]);
        store
            .commit_saf_projection_mutation(&SafProjectionMutation {
                operation_id: "update-0".to_owned(),
                kind: SafProjectionMutationKind::Update,
                memo_id: memo.memo_id.clone(),
                expected_revision: 1,
                expected_fingerprint: Some(memo.file_fingerprint),
                projection: Some(updated),
                trashed_at_ms: None,
                batch_targets: Vec::new(),
            })
            .expect("update publication");
        drop(store);

        // A pre-backfill projection leaves an in-window revision without a body.
        let connection = rusqlite::Connection::open(database_path(root.path()))
            .expect("raw projection connection");
        connection
            .execute(
                "UPDATE revision_index SET content = NULL WHERE revision = 2",
                [],
            )
            .expect("inject missing body");
        drop(connection);

        let reader = StoreReader::open(root.path()).expect("reader");
        let error = reader
            .list_history_revision_bodies(5)
            .expect_err("missing in-window body must fail closed");
        assert_eq!(error.code(), "history_revision_body_missing");
    }

    #[test]
    fn zero_retention_window_is_rejected() {
        let root = tempdir().expect("projection root");
        let memo = projection("2026-08-04_09:30:00_0", "2026-08-04.md", "rev1", &[]);
        rebuild_scanned_projection(root.path(), std::slice::from_ref(&memo))
            .expect("seed projection");

        let reader = StoreReader::open(root.path()).expect("reader");
        let error = reader
            .list_history_revision_bodies(0)
            .expect_err("zero window must be rejected");
        assert_eq!(error.code(), "invalid_history_retention");
    }
}
