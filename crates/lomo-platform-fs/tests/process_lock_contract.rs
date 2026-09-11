//! Behavior Contract
//!
//! Capability: non-blocking process-level exclusive file locking on Linux POSIX using flock(2)
//! to guarantee single-writer / single-process mutual exclusion over workspace/runtime lockfiles.
//!
//! Scenarios:
//! - Given an available lockfile path, when `try_acquire` is called, then the lock is acquired.
//! - Given an already held lock, when another non-blocking acquisition is attempted, then it fails
//!   immediately with conflict code `process_lock_held`.
//! - Given an acquired lock, when `unlock()` is called, then a subsequent acquisition succeeds.
//! - Given an acquired lock, when the lock guard is dropped, then the lock is released and another
//!   acquisition succeeds.
//!
//! Observable outcomes:
//! - `ProcessFileLock` handle on success
//! - Structured `LomoError::conflict("process_lock_held", ...)` on contention
//!
//! TDD proof:
//! - Fails RED initially because `ProcessFileLock` does not exist.
//!
//! Excludes:
//! - Cross-machine distributed locks, Windows file locks, cooperative advisory locks.

#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    use lomo_core::ErrorCategory;
    use lomo_platform_fs::ProcessFileLock;
    use tempfile::TempDir;

    use super::support::ResultTestExt;

    #[test]
    fn non_blocking_exclusive_lock_mutual_exclusion() {
        let temp_dir = TempDir::new().must_succeed("temp dir");
        let lock_path = temp_dir.path().join("lomo.lock");

        // First acquisition must succeed
        let lock1 = ProcessFileLock::try_acquire(&lock_path).must_succeed("lock1 acquire");
        assert_eq!(lock1.path(), lock_path.as_path());

        // Second acquisition on the same lockfile must fail with conflict
        let Err(lock2_err) = ProcessFileLock::try_acquire(&lock_path) else {
            panic!("unexpected second lock acquisition success");
        };
        assert_eq!(lock2_err.category(), ErrorCategory::Conflict);
        assert_eq!(lock2_err.code(), "process_lock_held");

        // Release first lock
        lock1.unlock().must_succeed("unlock lock1");

        // Third acquisition must now succeed
        let lock3 = ProcessFileLock::try_acquire(&lock_path).must_succeed("lock3 acquire");
        drop(lock3);

        // Fourth acquisition after drop must succeed
        let _lock4 = ProcessFileLock::try_acquire(&lock_path).must_succeed("lock4 acquire");
    }

    #[test]
    fn process_lock_rejects_symlink() {
        let temp_dir = TempDir::new().must_succeed("temp dir");
        let real_file = temp_dir.path().join("real.lock");
        std::fs::write(&real_file, b"").must_succeed("create real file");

        let symlink_path = temp_dir.path().join("symlink.lock");
        std::os::unix::fs::symlink(&real_file, &symlink_path).must_succeed("create symlink");

        let result = ProcessFileLock::try_acquire(&symlink_path);
        let Err(err) = result else {
            panic!("expected acquisition on symlink to fail");
        };
        assert_eq!(err.category(), ErrorCategory::Permission);
        assert_eq!(err.code(), "process_lock_symlink_rejected");
    }
}
