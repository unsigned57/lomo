//! Behavior Contract
//!
//! Capability: observe directory changes (creation, modification, deletion, rename) in Linux POSIX
//! filesystems using Linux inotify to detect external modifications and feed sync/refresh loops.
//!
//! Scenarios:
//! - Given a watched directory, when a new file is created, then a Created event is captured.
//! - Given an existing file, when it is modified, then a Modified event is captured.
//! - Given a file, when it is deleted, then a Deleted event is captured.
//! - Given no filesystem changes, when `poll_events` is called, then an empty event list is returned immediately.
//! - Given a watched subdirectory is deleted or renamed, polling publishes invalidation and
//!   keeps watching surviving directory paths without unknown-descriptor failures.
//!
//! Observable outcomes:
//! - `DirectoryChangeEvent` with `ChangeKind` and relative/file path
//!
//! TDD proof:
//! - Fails RED initially because `DirectoryWatcher` does not exist.
//!
//! Excludes:
//! - Polling remote network mounts, cross-filesystem recursive auto-mounting.

#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    use std::fs;
    use std::thread::sleep;
    use std::time::Duration;

    use lomo_platform_fs::{ChangeKind, DirectoryWatcher};
    use tempfile::TempDir;

    use super::support::ResultTestExt;

    #[test]
    fn deleting_a_watched_subdirectory_publishes_invalidation_without_losing_the_root() {
        let temp = TempDir::new().must_succeed("root");
        let child = temp.path().join("child");
        fs::create_dir(&child).must_succeed("child");
        let mut watcher = DirectoryWatcher::new(temp.path()).must_succeed("watcher");
        fs::remove_dir(&child).must_succeed("remove child");
        let events = watcher.poll_events().must_succeed("delete invalidation");
        assert!(
            events
                .iter()
                .any(|event| matches!(event.kind, ChangeKind::Invalidated | ChangeKind::Rescan))
        );
        fs::write(temp.path().join("after.md"), b"after").must_succeed("root still in use");
        assert!(
            watcher
                .poll_events()
                .must_succeed("root event")
                .iter()
                .any(|event| event.path.ends_with("after.md"))
        );
    }

    #[test]
    fn renamed_subdirectory_events_use_its_new_path() {
        let temp = TempDir::new().must_succeed("root");
        let old = temp.path().join("old");
        let new = temp.path().join("new");
        fs::create_dir(&old).must_succeed("child");
        let mut watcher = DirectoryWatcher::new(temp.path()).must_succeed("watcher");
        fs::rename(&old, &new).must_succeed("rename child");
        watcher.poll_events().must_succeed("rename invalidation");
        fs::write(new.join("memo.md"), b"body").must_succeed("nested memo");
        let events = watcher
            .poll_events()
            .must_succeed("renamed child remains watched");
        assert!(events.iter().any(|event| event.path == new.join("memo.md")));
    }

    #[test]
    fn directory_watcher_captures_create_modify_delete_events() {
        let temp_dir = TempDir::new().must_succeed("temp dir");
        let watched_dir = temp_dir.path().join("watched");
        fs::create_dir_all(&watched_dir).must_succeed("create dir");

        let mut watcher = DirectoryWatcher::new(&watched_dir).must_succeed("watcher init");

        // Initially no events
        let initial_events = watcher.poll_events().must_succeed("poll initial");
        assert!(initial_events.is_empty());

        // 1. Create a file
        let file_path = watched_dir.join("test.md");
        fs::write(&file_path, b"initial content").must_succeed("write file");
        sleep(Duration::from_millis(50));

        let events = watcher.poll_events().must_succeed("poll create");
        assert!(
            events
                .iter()
                .any(|e| e.kind == ChangeKind::Created && e.path.ends_with("test.md")),
            "expected Created event, got {events:?}"
        );

        // 2. Modify the file
        fs::write(&file_path, b"modified content").must_succeed("modify file");
        sleep(Duration::from_millis(50));

        let events = watcher.poll_events().must_succeed("poll modify");
        assert!(
            events
                .iter()
                .any(|e| e.kind == ChangeKind::Modified && e.path.ends_with("test.md")),
            "expected Modified event, got {events:?}"
        );

        // 3. Delete the file
        fs::remove_file(&file_path).must_succeed("remove file");
        sleep(Duration::from_millis(50));

        let events = watcher.poll_events().must_succeed("poll delete");
        assert!(
            events
                .iter()
                .any(|e| e.kind == ChangeKind::Deleted && e.path.ends_with("test.md")),
            "expected Deleted event, got {events:?}"
        );
    }

    #[test]
    fn directory_watcher_observes_preexisting_nested_and_newly_created_subdirectories() {
        let temp_dir = TempDir::new().must_succeed("temp dir");
        let watched_dir = temp_dir.path().join("watched");
        let nested_dir = watched_dir.join("sub1").join("sub2");
        fs::create_dir_all(&nested_dir).must_succeed("create nested dirs");

        let mut watcher = DirectoryWatcher::new(&watched_dir).must_succeed("watcher init");

        // 1. Write file in pre-existing nested directory
        let nested_file = nested_dir.join("nested.md");
        fs::write(&nested_file, b"nested content").must_succeed("write nested file");
        sleep(Duration::from_millis(50));

        let events = watcher.poll_events().must_succeed("poll nested create");
        assert!(
            events
                .iter()
                .any(|e| e.kind == ChangeKind::Created && e.path.ends_with("nested.md")),
            "expected Created event in nested dir, got {events:?}"
        );

        // 2. Create a new subdirectory dynamically
        let new_subdir = watched_dir.join("new_sub");
        fs::create_dir_all(&new_subdir).must_succeed("create new subdir");
        sleep(Duration::from_millis(50));

        // Drain directory creation events
        let topology = watcher.poll_events().must_succeed("poll dir create");
        assert!(
            topology
                .iter()
                .any(|event| event.kind == ChangeKind::Rescan)
        );

        // 3. Write file inside the newly created subdirectory
        let new_file = new_subdir.join("dynamic.md");
        fs::write(&new_file, b"dynamic content").must_succeed("write dynamic file");
        sleep(Duration::from_millis(50));

        let dynamic_events = watcher.poll_events().must_succeed("poll dynamic create");
        assert!(
            dynamic_events
                .iter()
                .any(|e| e.kind == ChangeKind::Created && e.path.ends_with("dynamic.md")),
            "expected Created event in dynamically created subdir, got {dynamic_events:?}"
        );
    }
}
