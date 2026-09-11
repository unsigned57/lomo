use std::fs::File;
use std::path::{Path, PathBuf};

use lomo_core::LomoError;
use rustix::fs::{FlockOperation, Mode, OFlags, flock, open};
use rustix::io::Errno;

use crate::error::{conflict, permission, storage};

/// Non-blocking, process-exclusive file lock using POSIX `flock(2)`.
///
/// Ensures exclusive access across separate processes (or separate descriptors)
/// for local runtime and workspace coordination. Automatically unlocks when descriptor is closed on drop.
#[derive(Debug)]
pub struct ProcessFileLock {
    file: File,
    path: PathBuf,
}

impl ProcessFileLock {
    /// Attempts to acquire a non-blocking exclusive lock on the file at `path`.
    ///
    /// # Errors
    ///
    /// Returns permission error if the lock path is a symbolic link.
    /// Returns conflict error if the lock is already held by another process or descriptor.
    /// Returns storage error if the file or parent directory cannot be opened.
    pub fn try_acquire(path: impl AsRef<Path>) -> Result<Self, LomoError> {
        let path = path.as_ref();
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).map_err(|err| {
                storage(
                    "process_lock_dir_unavailable",
                    &format!("failed to create parent directory for lockfile: {err}"),
                )
            })?;
        }

        let fd = open(
            path,
            OFlags::RDWR | OFlags::CREATE | OFlags::NOFOLLOW | OFlags::CLOEXEC,
            Mode::from_bits_truncate(0o600),
        )
        .map_err(|err| {
            if err == Errno::LOOP {
                permission(
                    "process_lock_symlink_rejected",
                    &format!("lockfile '{}' is a symbolic link", path.display()),
                )
            } else {
                storage(
                    "process_lock_open_failed",
                    &format!("failed to open lockfile '{}': {err}", path.display()),
                )
            }
        })?;

        let file = File::from(fd);

        match flock(&file, FlockOperation::NonBlockingLockExclusive) {
            Ok(()) => Ok(Self {
                file,
                path: path.to_path_buf(),
            }),
            Err(Errno::WOULDBLOCK) => Err(conflict(
                "process_lock_held",
                &format!(
                    "lockfile '{}' is already held by another process",
                    path.display()
                ),
            )),
            Err(err) => Err(storage(
                "process_lock_failed",
                &format!("flock failed on '{}': {err}", path.display()),
            )),
        }
    }

    /// Path to the held lockfile.
    #[must_use]
    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Explicitly releases the lock.
    ///
    /// # Errors
    ///
    /// Returns an error if the kernel `flock` unlock syscall fails.
    pub fn unlock(self) -> Result<(), LomoError> {
        flock(&self.file, FlockOperation::Unlock).map_err(|err| {
            storage(
                "process_lock_unlock_failed",
                &format!("failed to unlock '{}': {err}", self.path.display()),
            )
        })
    }
}
