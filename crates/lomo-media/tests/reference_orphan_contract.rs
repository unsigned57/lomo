//! Behavior Contract
//!
//! Capability: cross-source refcount and deterministic orphan sweep with media-trash recovery.
//!
//! Scenarios:
//! - Given history still references a digest after current delete, when sweep runs, then file stays.
//! - Given zero refs, when sweep runs, then file moves to media-trash with expiry.
//! - Given expired trash, when sweep runs, then permanent delete records intent first.
//! - Given in-window trash, when restore runs, then file returns to destination.
//!
//! Observable outcomes: `kept_live`, trash entries, delete intents.
//! Excludes: store `SQLite` projection, FFI, production DI.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use std::collections::BTreeMap;
    use std::path::PathBuf;

    use lomo_media::{
        AttachmentRef, ContentDigest, DEFAULT_RECOVERY_WINDOW_MS, MEDIA_DELETE_INTENT_DIR_NAME,
        MEDIA_TRASH_DIR_NAME, MediaTrashEntry, ReferenceSource, restore_from_trash, sweep_orphans,
        write_bytes_for_tests,
    };
    use tempfile::tempdir;

    const PNG_1X1: &[u8] = &[
        0x89, b'P', b'N', b'G', b'\r', b'\n', 0x1a, b'\n', 0x00, 0x00, 0x00, 0x0d, b'I', b'H',
        b'D', b'R', 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00,
        0x90, 0x77, 0x53, 0xde, 0x00, 0x00, 0x00, 0x0c, b'I', b'D', b'A', b'T', 0x08, 0xd7, 0x63,
        0xf8, 0xcf, 0xc0, 0x00, 0x00, 0x03, 0x01, 0x01, 0x00, 0x18, 0xdd, 0x8d, 0xb4, 0x00, 0x00,
        0x00, 0x00, b'I', b'E', b'N', b'D', 0xae, 0x42, 0x60, 0x82,
    ];

    #[test]
    fn history_reference_keeps_file() {
        let root = tempdir().expect("temp");
        let path = root.path().join("photo.png");
        write_bytes_for_tests(&path, PNG_1X1).expect("write");
        let digest = ContentDigest::of_slice(PNG_1X1);
        let mut committed = BTreeMap::new();
        committed.insert(digest.clone(), path.clone());
        let refs = vec![AttachmentRef {
            digest,
            source: ReferenceSource::HistoryVersion,
            owner_key: "hist-1".into(),
        }];
        let result = sweep_orphans(
            root.path(),
            &committed,
            &refs,
            &[],
            1_000,
            DEFAULT_RECOVERY_WINDOW_MS,
        )
        .expect("sweep");
        assert_eq!(result.kept_live, 1);
        assert!(result.moved_to_trash.is_empty());
        assert!(path.is_file());
    }

    #[test]
    fn zero_refcount_moves_to_trash_then_expires() {
        let root = tempdir().expect("temp");
        let path = root.path().join("orphan.png");
        write_bytes_for_tests(&path, PNG_1X1).expect("write");
        let digest = ContentDigest::of_slice(PNG_1X1);
        let mut committed = BTreeMap::new();
        committed.insert(digest.clone(), path.clone());
        let result = sweep_orphans(
            root.path(),
            &committed,
            &[],
            &[],
            10_000,
            DEFAULT_RECOVERY_WINDOW_MS,
        )
        .expect("sweep");
        assert_eq!(result.moved_to_trash.len(), 1);
        assert!(!path.exists());
        let entry = result.moved_to_trash.first().cloned().expect("trash entry");
        assert!(entry.trash_path.is_file());
        assert!(
            entry
                .trash_path
                .starts_with(root.path().join(MEDIA_TRASH_DIR_NAME))
        );

        let restore_dest = root.path().join("restored.png");
        restore_from_trash(&entry, &restore_dest, 10_001).expect("restore");
        assert!(restore_dest.is_file());

        // Re-trash and expire.
        write_bytes_for_tests(&path, PNG_1X1).expect("rewrite");
        let mut committed2 = BTreeMap::new();
        committed2.insert(digest, path);
        let moved =
            sweep_orphans(root.path(), &committed2, &[], &[], 20_000, 1_000).expect("sweep2");
        let trash = moved.moved_to_trash.first().cloned().expect("moved trash");
        let expired = sweep_orphans(root.path(), &BTreeMap::new(), &[], &[trash], 30_000, 1_000)
            .expect("expire");
        assert_eq!(expired.permanently_deleted.len(), 1);
        assert_eq!(
            expired.permanently_deleted.first().expect("intent").reason,
            "recovery_window_elapsed"
        );
        let intent_dir = root.path().join(MEDIA_DELETE_INTENT_DIR_NAME);
        assert!(
            intent_dir.is_dir(),
            "durable delete-intent journal must exist before/with permanent delete"
        );
        let journaled = std::fs::read_dir(&intent_dir).expect("intent dir").count();
        assert!(journaled >= 1, "at least one delete-intent file journaled");
    }

    #[test]
    fn expired_restore_fails_closed() {
        let entry = MediaTrashEntry {
            digest: ContentDigest::of_slice(PNG_1X1),
            trash_path: PathBuf::from("/nope"),
            trashed_at_ms: 0,
            expires_at_ms: 5,
        };
        let error = restore_from_trash(&entry, &PathBuf::from("/x"), 10).expect_err("expired");
        assert_eq!(error.code(), "media_trash_expired");
    }

    #[test]
    fn missing_trash_file_restore_fails_closed() {
        let root = tempdir().expect("temp");
        let entry = MediaTrashEntry {
            digest: ContentDigest::of_slice(PNG_1X1),
            trash_path: root.path().join("missing-trash-file"),
            trashed_at_ms: 1_000,
            expires_at_ms: 10_000,
        };
        let dest = root.path().join("media/restored.png");
        let error = restore_from_trash(&entry, &dest, 2_000).expect_err("missing");
        assert_eq!(error.code(), "media_trash_missing");
    }

    #[test]
    fn restore_from_trash_moves_file_inside_window() {
        let root = tempdir().expect("temp");
        let trash_dir = root.path().join(MEDIA_TRASH_DIR_NAME);
        std::fs::create_dir_all(&trash_dir).expect("trash");
        let digest = ContentDigest::of_slice(PNG_1X1);
        let trash_path = trash_dir.join(format!("{}_1000_photo.png", digest.as_str()));
        write_bytes_for_tests(&trash_path, PNG_1X1).expect("trash file");
        let entry = MediaTrashEntry {
            digest,
            trash_path: trash_path.clone(),
            trashed_at_ms: 1_000,
            expires_at_ms: 10_000,
        };
        let dest = root.path().join("media/restored.png");
        restore_from_trash(&entry, &dest, 2_000).expect("restore");
        assert!(dest.is_file());
        assert!(!trash_path.exists());
    }

    #[test]
    fn list_trash_empty_when_dir_absent() {
        use lomo_media::list_trash_entries;
        let root = tempdir().expect("temp");
        let listed = list_trash_entries(root.path(), 5_000, 1_000).expect("list");
        assert!(listed.is_empty());
    }

    #[test]
    fn empty_existing_trash_auto_lists_disk_and_expires() {
        let root = tempdir().expect("temp");
        let path = root.path().join("orphan.png");
        write_bytes_for_tests(&path, PNG_1X1).expect("write");
        let digest = ContentDigest::of_slice(PNG_1X1);
        let mut committed = BTreeMap::new();
        committed.insert(digest, path);
        let moved = sweep_orphans(root.path(), &committed, &[], &[], 20_000, 1_000).expect("move");
        assert_eq!(moved.moved_to_trash.len(), 1);
        // Host forgets the in-memory trash list; disk still holds the file.
        let expired =
            sweep_orphans(root.path(), &BTreeMap::new(), &[], &[], 30_000, 1_000).expect("expire");
        assert_eq!(expired.permanently_deleted.len(), 1);
        let intent_dir = root.path().join(MEDIA_DELETE_INTENT_DIR_NAME);
        assert!(intent_dir.is_dir());
    }

    #[test]
    fn list_trash_skips_malformed_names_and_restores_valid() {
        use lomo_media::list_trash_entries;
        let root = tempdir().expect("temp");
        let trash = root.path().join(MEDIA_TRASH_DIR_NAME);
        std::fs::create_dir_all(&trash).expect("trash dir");
        // Malformed basenames must not fail the scan.
        write_bytes_for_tests(&trash.join("not-a-trash-name.png"), b"x").expect("bad name");
        write_bytes_for_tests(&trash.join("onlyone_part"), b"y").expect("partial");
        let digest = ContentDigest::of_slice(PNG_1X1);
        let valid_name = format!("{}_1000_photo.png", digest.as_str());
        let valid_path = trash.join(&valid_name);
        write_bytes_for_tests(&valid_path, PNG_1X1).expect("valid trash");
        let listed = list_trash_entries(root.path(), 5_000, 2_000).expect("list");
        assert_eq!(listed.len(), 1);
        let entry = listed.first().expect("one trash entry");
        assert_eq!(entry.digest, digest);
        assert_eq!(entry.trashed_at_ms, 1_000);
        assert_eq!(entry.expires_at_ms, 6_000);
        // wall clock helper is callable for host tests that need a real now_ms seed.
        let now_ms: u64 = lomo_media::wall_clock_ms();
        assert!(now_ms < u64::MAX);
    }

    #[test]
    fn current_and_trash_memo_refs_keep_digest_live() {
        let root = tempdir().expect("temp");
        let path = root.path().join("live.png");
        write_bytes_for_tests(&path, PNG_1X1).expect("write");
        let digest = ContentDigest::of_slice(PNG_1X1);
        let mut committed = BTreeMap::new();
        committed.insert(digest.clone(), path.clone());
        let refs = vec![
            AttachmentRef {
                digest: digest.clone(),
                source: ReferenceSource::CurrentMemo,
                owner_key: "m1".into(),
            },
            AttachmentRef {
                digest,
                source: ReferenceSource::TrashMemo,
                owner_key: "m1".into(),
            },
        ];
        let result = sweep_orphans(
            root.path(),
            &committed,
            &refs,
            &[],
            1_000,
            DEFAULT_RECOVERY_WINDOW_MS,
        )
        .expect("sweep");
        assert_eq!(result.kept_live, 1);
        assert!(path.is_file());
        assert!(result.moved_to_trash.is_empty());
    }
}
