//! Stale `.git/index.lock` reclaim: only when owner PID is gone + frozen threshold elapsed.

use std::fs;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime};

use crate::error::{busy, storage, validation};
use lomo_core::LomoError;

/// Default frozen threshold before a lock whose owner is gone may be reclaimed.
pub const DEFAULT_STALE_LOCK_THRESHOLD: Duration = Duration::from_mins(30);

/// Outcome of a lock reclaim attempt.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum LockReclaimOutcome {
    /// No lock file present.
    Absent,
    /// Lock reclaimed (owner gone + frozen).
    Reclaimed,
    /// Lock still held by a live owner, or age below threshold.
    Held,
}

/// Attempts to reclaim `index.lock` under `git_dir` when the recorded owner PID is gone and the
/// lock file is older than `threshold`.
///
/// Lock file format (repo-owned): first line is decimal owner PID. Unknown format → Held (fail closed).
///
/// # Errors
///
/// Storage errors when the lock path cannot be inspected/removed.
pub fn try_reclaim_stale_index_lock(
    git_dir: &Path,
    threshold: Duration,
    now: SystemTime,
) -> Result<LockReclaimOutcome, LomoError> {
    let lock_path = git_dir.join("index.lock");
    if !lock_path.exists() {
        return Ok(LockReclaimOutcome::Absent);
    }
    let meta = fs::metadata(&lock_path).map_err(|error| {
        storage(
            "git_lock_stat_failed",
            &format!("failed to stat git index.lock: {error}"),
        )
    })?;
    let modified = meta.modified().map_err(|error| {
        storage(
            "git_lock_mtime_failed",
            &format!("failed to read index.lock mtime: {error}"),
        )
    })?;
    let age = now.duration_since(modified).unwrap_or(Duration::ZERO);
    if age < threshold {
        return Ok(LockReclaimOutcome::Held);
    }
    let contents = fs::read_to_string(&lock_path).map_err(|error| {
        storage(
            "git_lock_read_failed",
            &format!("failed to read index.lock: {error}"),
        )
    })?;
    let pid_line = contents.lines().next().unwrap_or("").trim();
    let Ok(pid) = pid_line.parse::<i32>() else {
        return Ok(LockReclaimOutcome::Held);
    };
    if pid <= 0 {
        return Ok(LockReclaimOutcome::Held);
    }
    if process_alive(pid) {
        return Ok(LockReclaimOutcome::Held);
    }
    fs::remove_file(&lock_path).map_err(|error| {
        storage(
            "git_lock_remove_failed",
            &format!("failed to remove stale index.lock: {error}"),
        )
    })?;
    Ok(LockReclaimOutcome::Reclaimed)
}

/// Writes a lock file with owner PID for hermetic tests / adapter acquisition helpers.
///
/// # Errors
///
/// Storage when create fails; validation when path parent is missing.
pub fn write_index_lock(git_dir: &Path, owner_pid: i32) -> Result<PathBuf, LomoError> {
    if !git_dir.is_dir() {
        return Err(validation(
            "git_lock_git_dir_missing",
            "git directory must exist before writing index.lock",
        ));
    }
    let lock_path = git_dir.join("index.lock");
    fs::write(&lock_path, format!("{owner_pid}\n")).map_err(|error| {
        storage(
            "git_lock_write_failed",
            &format!("failed to write index.lock: {error}"),
        )
    })?;
    Ok(lock_path)
}

/// Returns true when `/proc/{pid}` exists (Linux host matrix).
#[must_use]
pub fn process_alive(pid: i32) -> bool {
    if pid <= 0 {
        return false;
    }
    Path::new(&format!("/proc/{pid}")).exists()
}

/// Blocks with `Busy` when a live lock cannot be reclaimed.
///
/// # Errors
///
/// Busy when held; storage on I/O failure.
pub fn ensure_index_lock_clear(
    git_dir: &Path,
    threshold: Duration,
    now: SystemTime,
) -> Result<(), LomoError> {
    match try_reclaim_stale_index_lock(git_dir, threshold, now)? {
        LockReclaimOutcome::Absent | LockReclaimOutcome::Reclaimed => Ok(()),
        LockReclaimOutcome::Held => Err(busy(
            "git_index_lock_held",
            "git index.lock is held by a live owner or is younger than the frozen threshold",
        )),
    }
}
