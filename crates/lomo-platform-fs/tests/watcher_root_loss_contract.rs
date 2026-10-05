// adversarial-reaudit2: the root_lost state machine added for the 05-F4 watcher rebind
// fix must surface — and recover from — a *deleted* root, not only a *replaced* one.
//!
//! `watcher_rebind_contract` covers `rename-away + recreate` (`MOVE_SELF` on a live inode).
//! This variant drives the other arm: `remove_dir_all` leaves the root absent while
//! the queue still holds its death events, so `poll_events` must
//! 1. deliver the buffered `Invalidated` batch instead of collapsing it into an error,
//! 2. on the next call — queue drained, rebind still failing — return Err rather than
//!    a silent `Ok([])` that would certify a deaf watcher as healthy,
//! 3. once the root returns, rebind and publish `Rescan` plus live coverage of the
//!    new tree.
//!
//! A failure at step 2 means the watcher went silently deaf (05-F4 C12 regression);
//! a failure at step 3 means `root_lost` never retries.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "adversarial audit tests fail closed with panics on missing facts"
)]
mod tests {
    use std::{fs, time::Duration};

    use lomo_platform_fs::{ChangeKind, DirectoryWatcher};

    #[test]
    fn a_deleted_watch_root_errors_then_heals_on_recreation() {
        let temp = tempfile::tempdir().expect("tempdir");
        let root = temp.path().join("watched");
        fs::create_dir_all(root.join("nested")).expect("root tree");
        fs::write(root.join("seed.md"), b"seed").expect("seed file");
        let mut watcher = DirectoryWatcher::new(&root).expect("watcher");

        // Sanity: live coverage before the outage.
        fs::write(root.join("nested/tick.md"), b"tick").expect("tick file");
        let before = watcher
            .wait_events(Duration::from_secs(2))
            .expect("pre-deletion events");
        assert!(
            !before.is_empty(),
            "the live watch must observe its own tree"
        );

        // Delete the whole root: DELETE_SELF/IGNORED stay queued for the drained batch.
        fs::remove_dir_all(&root).expect("delete root");
        let death = watcher
            .wait_events(Duration::from_secs(2))
            .expect("buffered death events must be delivered before any error");
        assert!(
            death
                .iter()
                .any(|event| event.kind == ChangeKind::Invalidated && event.path == root),
            "deleting the watched root must publish an Invalidated for the root, got {death:?}"
        );

        // Queue drained, root still absent: the next poll retries the rebind and must
        // report the failure — an `Ok([])` here is a silently deaf watcher.
        let deaf = watcher.poll_events();
        assert!(
            deaf.is_err(),
            "a lost root with no queued events must surface the rebind failure, got {deaf:?}"
        );

        // Root returns: the poll-driven rebind must re-arm the graph and say so.
        fs::create_dir_all(&root).expect("recreate root");
        let rebound = watcher
            .wait_events(Duration::from_secs(2))
            .expect("rebind must deliver a batch");
        assert!(
            rebound
                .iter()
                .any(|event| event.kind == ChangeKind::Rescan && event.path == root),
            "a healed root must publish Rescan so consumers rebuild derived state, \
             got {rebound:?}"
        );

        // The new graph must actually observe — not just claim health.
        fs::write(root.join("fresh.md"), b"fresh").expect("fresh file");
        let events = watcher
            .wait_events(Duration::from_secs(2))
            .expect("post-heal events");
        assert!(
            events
                .iter()
                .any(|event| event.path == root.join("fresh.md")),
            "changes under the healed root must be observed, got {events:?}"
        );
    }
}
