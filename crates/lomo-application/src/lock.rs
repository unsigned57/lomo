use std::fs::{File, create_dir_all};
use std::path::{Path, PathBuf};

use lomo_core::LomoError;
use rustix::fs::{FlockOperation, Mode, OFlags, flock, open};
use rustix::io::Errno;

use crate::error::{conflict, permission, storage};

/// RAII transaction lock held only during the critical write transaction.
#[derive(Debug)]
pub struct TransactionLock {
    _file: File,
    path: PathBuf,
}

impl TransactionLock {
    /// Attempts to acquire an exclusive, non-blocking lock on the transaction file.
    ///
    /// # Errors
    /// Returns `Conflict` when another transaction currently holds the lock,
    /// or `Storage`/`Permission` on I/O issues.
    pub fn acquire(runtime_dir: &Path) -> Result<Self, LomoError> {
        create_dir_all(runtime_dir).map_err(|err| {
            storage(
                "runtime_dir_unavailable",
                format!("failed to create runtime directory: {err}"),
            )
        })?;

        let path = runtime_dir.join("lomo_transaction.lock");
        let fd = open(
            &path,
            OFlags::RDWR | OFlags::CREATE | OFlags::NOFOLLOW | OFlags::CLOEXEC,
            Mode::from_bits_truncate(0o600),
        )
        .map_err(|err| {
            if err == Errno::LOOP {
                permission(
                    "transaction_lock_symlink_rejected",
                    format!("lockfile '{}' is a symbolic link", path.display()),
                )
            } else {
                storage(
                    "transaction_lock_open_failed",
                    format!(
                        "failed to open transaction lockfile '{}': {err}",
                        path.display()
                    ),
                )
            }
        })?;

        let file = File::from(fd);
        match flock(&file, FlockOperation::NonBlockingLockExclusive) {
            Ok(()) => Ok(Self { _file: file, path }),
            Err(Errno::WOULDBLOCK) => Err(conflict(
                "transaction_lock_held",
                format!("transaction lock '{}' is currently held", path.display()),
            )),
            Err(err) => Err(storage(
                "transaction_lock_failed",
                format!("flock failed on '{}': {err}", path.display()),
            )),
        }
    }

    #[must_use]
    pub fn path(&self) -> &Path {
        &self.path
    }
}
