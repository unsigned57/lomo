use std::{
    fs::{File, TryLockError, create_dir_all},
    path::{Path, PathBuf},
};

use lomo_core::LomoError;

use crate::{
    error::{conflict, storage},
    sysfs::open_lock_file,
};

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
        let file = open_lock_file(&path)?;
        match file.try_lock() {
            Ok(()) => Ok(Self { _file: file, path }),
            Err(TryLockError::WouldBlock) => Err(conflict(
                "transaction_lock_held",
                format!("transaction lock '{}' is currently held", path.display()),
            )),
            Err(TryLockError::Error(err)) => Err(storage(
                "transaction_lock_failed",
                format!("lock failed on '{}': {err}", path.display()),
            )),
        }
    }

    #[must_use]
    pub fn path(&self) -> &Path {
        &self.path
    }
}
