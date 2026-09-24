use std::fs::{File, OpenOptions};
use std::path::{Path, PathBuf};

use lomo_core::LomoError;

use crate::error::{conflict, permission, storage};

/// Non-blocking, process-exclusive file lock using the platform advisory lock
/// (`flock` on unix, `LockFileEx` on Windows, both via `File::try_lock`).
///
/// Ensures exclusive access across separate processes (or separate descriptors)
/// for local runtime and workspace coordination. Automatically unlocks when
/// the descriptor is closed on drop.
#[derive(Debug)]
pub struct ProcessFileLock {
    file: File,
    path: PathBuf,
}

/// Opens the lockfile without following a symbolic link.
///
/// On unix this is `O_NOFOLLOW`. On Windows the file is opened with
/// `FILE_FLAG_OPEN_REPARSE_POINT` and the opened handle itself is checked, so a
/// link swapped in between stat and open is still caught.
#[cfg(unix)]
fn open_lockfile(path: &Path) -> Result<File, LomoError> {
    use std::os::unix::fs::OpenOptionsExt;
    let nofollow = i32::try_from(rustix::fs::OFlags::NOFOLLOW.bits())
        .map_err(|err| storage("file_flags_invalid", &err.to_string()))?;
    OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .custom_flags(nofollow)
        .open(path)
        .map_err(|err| {
            if err.raw_os_error() == Some(rustix::io::Errno::LOOP.raw_os_error()) {
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
        })
}

/// Opens the lockfile on Windows via `FILE_FLAG_OPEN_REPARSE_POINT`, rejecting
/// a reparse point on the opened handle.
#[cfg(windows)]
fn open_lockfile(path: &Path) -> Result<File, LomoError> {
    use std::os::windows::fs::OpenOptionsExt;
    const OPEN_REPARSE: u32 = 0x0020_0000; // FILE_FLAG_OPEN_REPARSE_POINT
    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .custom_flags(OPEN_REPARSE)
        .open(path)
        .map_err(|err| {
            storage(
                "process_lock_open_failed",
                &format!("failed to open lockfile '{}': {err}", path.display()),
            )
        })?;
    let metadata = file.metadata().map_err(|err| {
        storage(
            "process_lock_open_failed",
            &format!("failed to stat lockfile '{}': {err}", path.display()),
        )
    })?;
    if metadata.file_type().is_symlink() {
        return Err(permission(
            "process_lock_symlink_rejected",
            &format!("lockfile '{}' is a symbolic link", path.display()),
        ));
    }
    Ok(file)
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

        let file = open_lockfile(path)?;

        match file.try_lock() {
            Ok(()) => Ok(Self {
                file,
                path: path.to_path_buf(),
            }),
            Err(std::fs::TryLockError::WouldBlock) => Err(conflict(
                "process_lock_held",
                &format!(
                    "lockfile '{}' is already held by another process",
                    path.display()
                ),
            )),
            Err(std::fs::TryLockError::Error(err)) => Err(storage(
                "process_lock_failed",
                &format!("file lock failed on '{}': {err}", path.display()),
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
    /// Returns an error if the kernel unlock operation fails.
    pub fn unlock(self) -> Result<(), LomoError> {
        self.file.unlock().map_err(|err| {
            storage(
                "process_lock_unlock_failed",
                &format!("failed to unlock '{}': {err}", self.path.display()),
            )
        })
    }
}
