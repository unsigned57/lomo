//! Behavior Contract (P5-01 history/state v2)
//!
//! Capability: content-addressed history revisions with stable `RevisionId`, parent-derived
//! generation, retention of 20 reachable revisions with permanent prune tombstones, and fail-closed
//! corrupt loads (never clean-slate).
//!
//! Scenarios:
//! - Given memo + parents + content digest + metadata, when `RevisionId` is computed twice, then
//!   results are identical (order of parents does not matter after sort/dedup).
//! - Given a root then children, when create runs, then generation is 1, 2, 3 …
//! - Given a linear chain longer than retention, when `retention_keep_set` runs, then at most 20
//!   revisions remain; prune writes permanent tombstones and never touches user Markdown.
//! - Given a pin set, when retention runs, then pinned revisions stay even if outside top-20.
//! - Given corrupt revision bytes, when read runs, then corruption (file not deleted).
//!
//! Observable outcomes: hex revision ids, generation numbers, tombstone files, keep-set size.
//! Excludes: store transaction machine cutover, sync conflict pins production wiring.

#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    use super::support::{OptionTestExt, ResultTestExt};
    use lomo_core::ErrorCategory;
    use lomo_workspace::{
        HISTORY_RETENTION_REVISIONS, HistoryHead, HistoryRevisionV2, LomoLayoutVersion, LomoPaths,
        RevisionId, history_revision_path, history_tombstone_path, prune_history_with_tombstones,
        read_history_revision, retention_keep_set, revisions_to_prune, write_history_head,
        write_history_revision,
    };
    use std::collections::{HashMap, HashSet};
    use std::fs;
    use tempfile::tempdir;

    fn digest_of(content: &str) -> String {
        use sha2::{Digest, Sha256};
        let d = Sha256::digest(content.as_bytes());
        lomo_workspace::hex_encode(&d[..])
    }

    #[test]
    fn revision_id_is_stable_and_parent_order_independent() {
        let p1 = RevisionId::parse(&"a".repeat(64)).test_ok("p1");
        let p2 = RevisionId::parse(&"b".repeat(64)).test_ok("p2");
        let a = RevisionId::compute("memo-1", &[p1.clone(), p2.clone()], "digest", "meta");
        let b = RevisionId::compute("memo-1", &[p2, p1], "digest", "meta");
        assert_eq!(a.as_str(), b.as_str());
        assert_eq!(a.as_str().len(), 64);

        let other = RevisionId::compute("memo-1", &[], "digest", "meta");
        assert_ne!(a.as_str(), other.as_str());
    }

    #[test]
    fn history_generation_is_one_plus_max_parent() {
        let root =
            HistoryRevisionV2::create("m1", &[], "root".into(), &digest_of("root"), "meta", 1)
                .test_ok("root");
        assert_eq!(root.generation, 1);
        assert!(root.parent_ids.is_empty());

        let child = HistoryRevisionV2::create(
            "m1",
            std::slice::from_ref(&root),
            "child".into(),
            &digest_of("child"),
            "meta",
            2,
        )
        .test_ok("child");
        assert_eq!(child.generation, 2);
        assert_eq!(child.parent_ids.first(), Some(&root.revision_id));

        let grand = HistoryRevisionV2::create(
            "m1",
            std::slice::from_ref(&child),
            "grand".into(),
            &digest_of("grand"),
            "meta",
            3,
        )
        .test_ok("grand");
        assert_eq!(grand.generation, 3);
    }

    #[test]
    fn retention_keeps_twenty_and_prune_writes_tombstones_without_user_files() {
        let dir = tempdir().test_ok("tempdir");
        // User markdown that must never be touched by prune.
        let user_md = dir.path().join("memos").join("2026-07-22.md");
        fs::create_dir_all(user_md.parent().test_ok("parent")).test_ok("mkdir memos");
        fs::write(&user_md, "# keep me\n").test_ok("user file");
        let user_before = fs::read(&user_md).test_ok("read user before");

        let paths = LomoPaths::for_workspace_with_layout(dir.path(), LomoLayoutVersion::V2);
        paths.ensure_layout().test_ok("layout");
        fs::create_dir_all(paths.history.join("objects")).test_ok("objects");
        fs::create_dir_all(paths.history.join("tombstones")).test_ok("tombstones");
        fs::create_dir_all(paths.history.join("heads")).test_ok("heads");

        let mut chain: Vec<HistoryRevisionV2> = Vec::new();
        let mut parent: Option<HistoryRevisionV2> = None;
        // Build 25 revisions so retention prunes 5.
        for i in 0..25_u64 {
            let content = format!("body-{i}");
            let parents = parent.as_ref().map_or_else(Vec::new, |p| vec![p.clone()]);
            let rev = HistoryRevisionV2::create(
                "memo-ret",
                &parents,
                content.clone(),
                &digest_of(&content),
                &format!("meta-{i}"),
                i.cast_signed(),
            )
            .test_ok("create rev");
            write_history_revision(&paths, &rev).test_ok("write rev");
            parent = Some(rev.clone());
            chain.push(rev);
        }
        let head = chain.last().test_ok("head").revision_id.clone();
        write_history_head(
            &paths,
            &HistoryHead {
                memo_id: "memo-ret".into(),
                head_revision_id: head.clone(),
            },
        )
        .test_ok("head");

        let by_id: HashMap<_, _> = chain
            .iter()
            .map(|r| (r.revision_id.clone(), r.clone()))
            .collect();
        let pins = HashSet::new();
        let keep = retention_keep_set(&head, &by_id, &pins, HISTORY_RETENTION_REVISIONS);
        assert_eq!(keep.len(), HISTORY_RETENTION_REVISIONS);
        // Newest generations must be kept.
        for rev in chain.iter().rev().take(HISTORY_RETENTION_REVISIONS) {
            assert!(
                keep.contains(&rev.revision_id),
                "newest revision {} should be kept",
                rev.generation
            );
        }

        let to_prune = revisions_to_prune(&head, &by_id, &keep);
        assert_eq!(to_prune.len(), 5);

        let pruned =
            prune_history_with_tombstones(&paths, &head, &by_id, &pins, 1_700_000_000_000, true)
                .test_ok("prune");
        assert_eq!(pruned.len(), 5);
        for id in &pruned {
            let tomb = history_tombstone_path(&paths, id);
            assert!(tomb.is_file(), "tombstone must exist for {}", id.as_str());
            let obj = history_revision_path(&paths, id);
            assert!(!obj.exists(), "pruned object should be deleted");
        }
        // Kept objects remain.
        for id in &keep {
            assert!(history_revision_path(&paths, id).is_file());
        }

        let user_after = fs::read(&user_md).test_ok("read user after");
        assert_eq!(user_before, user_after, "user Markdown must be untouched");
    }

    #[test]
    fn retention_respects_pin_set() {
        let mut chain: Vec<HistoryRevisionV2> = Vec::new();
        let mut parent: Option<HistoryRevisionV2> = None;
        for i in 0..25_u64 {
            let content = format!("p-{i}");
            let parents = parent.as_ref().map_or_else(Vec::new, |p| vec![p.clone()]);
            let rev = HistoryRevisionV2::create(
                "memo-pin",
                &parents,
                content.clone(),
                &digest_of(&content),
                &format!("m-{i}"),
                i.cast_signed(),
            )
            .test_ok("rev");
            parent = Some(rev.clone());
            chain.push(rev);
        }
        let head = chain.last().test_ok("head").revision_id.clone();
        let by_id: HashMap<_, _> = chain
            .iter()
            .map(|r| (r.revision_id.clone(), r.clone()))
            .collect();
        // Pin the oldest (generation 1) so it survives beyond top-20.
        let oldest = chain.first().test_ok("oldest").revision_id.clone();
        let mut pins = HashSet::new();
        pins.insert(oldest.clone());
        let keep = retention_keep_set(&head, &by_id, &pins, HISTORY_RETENTION_REVISIONS);
        assert!(keep.contains(&oldest));
        assert!(keep.len() > HISTORY_RETENTION_REVISIONS);
    }

    #[test]
    fn corrupt_history_object_is_not_clean_slated() {
        let dir = tempdir().test_ok("tempdir");
        let paths = LomoPaths::for_workspace_with_layout(dir.path(), LomoLayoutVersion::V2);
        paths.ensure_layout().test_ok("layout");
        let rev = HistoryRevisionV2::create("m-c", &[], "x".into(), &digest_of("x"), "meta", 0)
            .test_ok("rev");
        write_history_revision(&paths, &rev).test_ok("write");
        let path = history_revision_path(&paths, &rev.revision_id);
        fs::write(&path, b"garbage").test_ok("corrupt");
        let err = read_history_revision(&paths, &rev.revision_id).test_err("read corrupt");
        assert_eq!(err.category(), ErrorCategory::Corruption);
        assert!(path.is_file(), "corrupt object must not be auto-deleted");
    }

    #[test]
    fn history_v2_write_requires_v2_layout() {
        let dir = tempdir().test_ok("tempdir");
        let paths = LomoPaths::for_workspace_with_layout(dir.path(), LomoLayoutVersion::V1);
        paths.ensure_layout().test_ok("layout");
        let rev = HistoryRevisionV2::create("m-v1", &[], "x".into(), &digest_of("x"), "meta", 0)
            .test_ok("rev");
        let err = write_history_revision(&paths, &rev).test_err("v1 layout");
        assert_eq!(err.category(), ErrorCategory::Validation);
        assert_eq!(err.code(), "history_v2_layout_required");
    }
}
