//! Behavior Contract:
//! - Unit under test: `list_history_attachment_refs` / retention window
//! - Owning layer: `lomo-store` (D6 history ref projection for media orphan keep-set)
//! - Priority tier: P0
//! - Capability: durable history revision bodies project attachment relative paths so orphan
//!   sweep can keep digests referenced only by **in-window** history; out-of-window history
//!   must not pin digests even when `.rec` files remain on disk.
//!
//! Scenarios:
//! - Given a history record whose body links `media/keep.png`, when list runs, then that path
//!   is returned with source `owner_key` `memo@r{revision}`.
//! - Given no history directory, when list runs, then the result is empty.
//! - Given corrupt history payload, when list runs, then it is skipped without failing the scan.
//! - Given more than `retention_revisions` history records for one memo, when list runs with N=2,
//!   then only the newest two revisions contribute attachment refs; older revisions' digests
//!   are absent from the keep-set.
//! - Given `retention_revisions=0`, when list runs, then the keep-set is empty.
//!
//! Observable outcomes: `HistoryAttachmentRef` `relative_path` / `memo_id` / revision membership.
//! TDD proof: RED before retention filter lands on `history_refs`.
//! Excludes: FFI, media-trash FS moves, current/trash `attachment_ref` SQL, durable history prune.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::indexing_slicing,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use std::fs;
    use std::path::Path;

    use lomo_store::{
        HistoryBody, LomoPaths, LomoPayload, LomoRecordKind, list_history_attachment_refs,
        list_history_attachment_refs_with_retention, write_record_atomic,
    };

    fn write_history(root: &Path, memo_id: &str, revision: u64, content: &str) {
        let paths = LomoPaths::for_workspace(root);
        paths.ensure_layout().expect("layout");
        let body = HistoryBody {
            memo_id: memo_id.to_owned(),
            revision,
            content: content.to_owned(),
            file_fingerprint: "fp".to_owned(),
        };
        let body_json = serde_json::to_string(&body).expect("json");
        let payload = LomoPayload {
            kind: LomoRecordKind::History,
            record_id: format!("{memo_id}-r{revision}"),
            body_json,
        };
        let path = paths.history.join(format!("{memo_id}-r{revision}.rec"));
        write_record_atomic(&path, &payload).expect("write history");
    }

    #[test]
    fn history_body_attachment_is_projected() {
        let root = tempfile::tempdir().expect("tmp");
        write_history(root.path(), "m1", 2, "see ![x](media/keep.png)\n");
        let refs = list_history_attachment_refs(root.path()).expect("list");
        assert_eq!(refs.len(), 1);
        assert_eq!(refs[0].memo_id, "m1");
        assert_eq!(refs[0].revision, 2);
        assert_eq!(refs[0].relative_path, "media/keep.png");
        assert_eq!(refs[0].owner_key, "m1@r2");
    }

    #[test]
    fn missing_history_dir_returns_empty() {
        let root = tempfile::tempdir().expect("tmp");
        let refs = list_history_attachment_refs(root.path()).expect("list");
        assert!(refs.is_empty());
    }

    #[test]
    fn corrupt_history_record_is_skipped() {
        let root = tempfile::tempdir().expect("tmp");
        let paths = LomoPaths::for_workspace(root.path());
        paths.ensure_layout().expect("layout");
        fs::write(paths.history.join("bad.rec"), b"not-a-lomo-record").expect("write bad");
        write_history(root.path(), "m2", 1, "![y](media/ok.png)\n");
        let refs = list_history_attachment_refs(root.path()).expect("list");
        assert_eq!(refs.len(), 1);
        assert_eq!(refs[0].relative_path, "media/ok.png");
    }

    #[test]
    fn out_of_window_history_does_not_keep_digest() {
        let root = tempfile::tempdir().expect("tmp");
        // Three revisions; retention keeps only the newest two (r3, r2). r1 is out of window.
        write_history(root.path(), "m-win", 1, "![old](media/old.png)\n");
        write_history(root.path(), "m-win", 2, "![mid](media/mid.png)\n");
        write_history(root.path(), "m-win", 3, "![new](media/new.png)\n");

        let refs =
            list_history_attachment_refs_with_retention(root.path(), 2).expect("list with window");
        let paths: Vec<&str> = refs.iter().map(|r| r.relative_path.as_str()).collect();
        assert!(
            paths.contains(&"media/new.png") && paths.contains(&"media/mid.png"),
            "in-window revisions must keep digests: {paths:?}"
        );
        assert!(
            !paths.contains(&"media/old.png"),
            "out-of-window revision must not keep digest: {paths:?}"
        );
        assert_eq!(refs.len(), 2);
        // Durable r1 file still on disk — filter is projection-only, not prune.
        let paths_layout = LomoPaths::for_workspace(root.path());
        assert!(
            paths_layout.history.join("m-win-r1.rec").is_file(),
            "out-of-window history record remains on disk until durable prune"
        );
    }

    #[test]
    fn zero_retention_yields_empty_keep_set() {
        let root = tempfile::tempdir().expect("tmp");
        write_history(root.path(), "m0", 5, "![z](media/z.png)\n");
        let refs =
            list_history_attachment_refs_with_retention(root.path(), 0).expect("list zero window");
        assert!(refs.is_empty());
    }

    #[test]
    fn retention_is_per_memo_not_global() {
        let root = tempfile::tempdir().expect("tmp");
        write_history(root.path(), "a", 1, "![a1](media/a1.png)\n");
        write_history(root.path(), "a", 2, "![a2](media/a2.png)\n");
        write_history(root.path(), "b", 1, "![b1](media/b1.png)\n");
        write_history(root.path(), "b", 2, "![b2](media/b2.png)\n");
        // Keep 1 per memo → a@r2 and b@r2 only.
        let refs =
            list_history_attachment_refs_with_retention(root.path(), 1).expect("per-memo window");
        let keys: Vec<&str> = refs.iter().map(|r| r.owner_key.as_str()).collect();
        assert_eq!(refs.len(), 2, "{keys:?}");
        assert!(keys.contains(&"a@r2") && keys.contains(&"b@r2"), "{keys:?}");
    }
}
