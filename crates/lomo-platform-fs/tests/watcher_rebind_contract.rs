// adversarial-audit: the inotify backend goes permanently deaf after the
// watched root is replaced (moved aside + recreated). `Invalidated` is emitted once but
// the watch graph is never rebuilt for the new directory, while `watcher_active` stays
// true and focus regains stay silent — changes under the new root are invisible forever.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Adversarial fixtures must be constructed successfully before probing"
)]
mod tests {
    use lomo_platform_fs::DirectoryWatcher;
    use std::{fs, time::Duration};

    /// Replace the library root while the watcher lives: rename it away (`IN_MOVE_SELF`,
    /// the kernel keeps the watch on the *moved inode*), then create a fresh directory
    /// at the same path. The spec requires root replacement to be an explicit state —
    /// either re-watch or stay loudly unavailable — never a silent deaf watch.
    #[test]
    fn root_replacement_keeps_observing_the_new_tree() {
        let temp = tempfile::tempdir().expect("tempdir");
        let root = temp.path().join("watched");
        fs::create_dir_all(&root).expect("root");
        fs::write(root.join("seed.md"), b"seed").expect("seed file");
        let mut watcher = DirectoryWatcher::new(&root).expect("watcher");

        let away = temp.path().join("away");
        fs::rename(&root, &away).expect("move the root aside");
        fs::create_dir(&root).expect("recreate the root in place");

        // Drain the invalidation batch (MOVE_SELF/IGNORED → Invalidated).
        let invalidated = watcher
            .wait_events(Duration::from_secs(2))
            .expect("the root move must be reported");
        assert!(
            !invalidated.is_empty(),
            "moving the watched root away must surface an event batch"
        );

        // The recreated root must be observed again — a deaf watcher leaves the UI
        // reporting watcher_active while no event can ever arrive.
        fs::write(root.join("fresh.md"), b"fresh").expect("write into the new root");
        let events = watcher
            .wait_events(Duration::from_millis(600))
            .expect("post-replacement wait");
        assert!(
            !events.is_empty(),
            "changes under the recreated root must be observed; \
             a deaf watcher with watcher_active=true silently drops external changes"
        );
    }
}
