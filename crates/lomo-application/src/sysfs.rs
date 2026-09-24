//! Platform split for the handful of host-filesystem operations that have no
//! single portable std API: no-follow opens, private-file modes, directory
//! durability, and the transaction lockfile anchor.

use std::{
    fs::{File, OpenOptions},
    path::Path,
};

use lomo_core::LomoError;

#[cfg(not(unix))]
use crate::error::permission;
use crate::error::storage;

/// Opens `path` for reading, refusing to follow a symbolic link at the leaf.
///
/// Unix passes `O_NOFOLLOW` atomically; Windows opens the reparse point itself
/// (`FILE_FLAG_OPEN_REPARSE_POINT`) and rejects it on the opened handle. Other
/// targets pre-check `symlink_metadata` — the check is documented as
/// non-atomic because no better primitive exists there.
///
/// # Errors
/// `NotFound` propagates as a storage error on open, permission on symlink,
/// storage otherwise.
pub fn open_read_nofollow(path: &Path) -> Result<File, LomoError> {
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc_o_nofollow());
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        options.custom_flags(FILE_FLAG_OPEN_REPARSE_POINT);
    }
    #[cfg(not(any(unix, windows)))]
    if path
        .symlink_metadata()
        .map(|metadata| metadata.file_type().is_symlink())
        .unwrap_or(false)
    {
        return Err(permission(
            "symlink_rejected",
            format!("symbolic link rejected for '{}'", path.display()),
        ));
    }
    let file = options
        .open(path)
        .map_err(|error| storage("open_failed", error.to_string()))?;
    #[cfg(windows)]
    if file
        .metadata()
        .map_err(|error| storage("stat_failed", error.to_string()))?
        .file_type()
        .is_symlink()
    {
        return Err(permission(
            "symlink_rejected",
            format!("symbolic link rejected for '{}'", path.display()),
        ));
    }
    Ok(file)
}

/// Opens `path` read-write for locking, creating it with private permissions
/// when absent and refusing a symlink at the leaf.
///
/// # Errors
/// Permission on symlink, storage otherwise.
pub fn open_lock_file(path: &Path) -> Result<File, LomoError> {
    let mut options = OpenOptions::new();
    options.read(true).write(true).create(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc_o_nofollow()).mode(0o600);
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        options.custom_flags(FILE_FLAG_OPEN_REPARSE_POINT);
    }
    let file = options
        .open(path)
        .map_err(|error| storage("transaction_lock_open_failed", error.to_string()))?;
    #[cfg(windows)]
    if file
        .metadata()
        .map_err(|error| storage("stat_failed", error.to_string()))?
        .file_type()
        .is_symlink()
    {
        return Err(permission(
            "transaction_lock_symlink_rejected",
            format!("lockfile '{}' is a symbolic link", path.display()),
        ));
    }
    Ok(file)
}

/// Applies owner-only permissions (`0o600`) to a create-new open on Unix.
/// Windows private files inherit the user profile ACL, which is already
/// per-user — no equivalent flag exists or is needed.
pub fn private_create_options() -> OpenOptions {
    let mut options = OpenOptions::new();
    options.create_new(true).write(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options
}

/// Durably syncs the parent directory of `path` after a rename or removal.
///
/// Unix syncs the directory fd. Windows has no directory-sync primitive —
/// NTFS journals directory metadata, so entry durability rides on the journal
/// while file bytes are still synced individually.
///
/// # Errors
/// Storage error when the directory cannot be opened or synced on Unix.
pub fn sync_parent_dir(path: &Path) -> Result<(), LomoError> {
    #[cfg(unix)]
    {
        let parent = path
            .parent()
            .ok_or_else(|| storage("private_path_invalid", "missing parent"))?;
        File::open(parent)
            .and_then(|directory| directory.sync_all())
            .map_err(|error| storage("private_directory_sync_failed", error.to_string()))
    }
    #[cfg(not(unix))]
    {
        let _ = path;
        Ok(())
    }
}

/// `O_NOFOLLOW` as an `OpenOptionsExt::custom_flags` value — the flag never
/// carries bit 31, so the widening cast is lossless on every Unix target.
#[cfg(unix)]
const fn libc_o_nofollow() -> i32 {
    rustix::fs::OFlags::NOFOLLOW.bits().cast_signed()
}

#[cfg(windows)]
const FILE_FLAG_OPEN_REPARSE_POINT: u32 = 0x0020_0000;
