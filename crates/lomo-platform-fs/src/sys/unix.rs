//! Unix directory-descriptor primitives (Linux, macOS, and other POSIX targets).
//!
//! `Root`/`Dir`/`Node` are open file descriptors, so every operation is anchored
//! to the bound directory even if ancestors are renamed concurrently. On Linux,
//! `openat2` with `RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS` hardens whole-path
//! resolution; older kernels and other unix targets use the same component-wise
//! `O_NOFOLLOW` walk as [`super::descend`].

use std::fs::File;
#[cfg(target_os = "linux")]
use std::mem::MaybeUninit;
use std::path::Path;

use lomo_core::LomoError;
use rustix::fd::OwnedFd;
use rustix::fs::{AtFlags, Mode, OFlags, RenameFlags};
use rustix::io::Errno;

use crate::error::{conflict, permission, storage, validation};

/// Open directory descriptor pinning the capability root.
#[derive(Debug)]
pub struct Root {
    fd: OwnedFd,
    /// Filesystem path used by the non-Linux `read_dir` fallback.
    #[cfg(not(target_os = "linux"))]
    path: std::path::PathBuf,
}

/// Open directory descriptor used as the anchor for entry operations.
#[derive(Debug)]
pub struct Dir {
    fd: OwnedFd,
    /// Filesystem path used by the non-Linux `read_dir` fallback.
    #[cfg(not(target_os = "linux"))]
    path: std::path::PathBuf,
}

/// Open handle on a single resolved leaf entry (file or directory).
#[derive(Debug)]
pub struct Node {
    fd: OwnedFd,
}

/// Entry classification used by stat/remove paths.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum NodeKind {
    File,
    Directory,
    Other,
}

impl Root {
    /// Opens `canonical` as a no-follow directory descriptor.
    ///
    /// # Errors
    /// Storage error when the directory cannot be opened.
    pub fn open(canonical: &Path) -> Result<Self, LomoError> {
        let fd = rustix::fs::open(
            canonical,
            OFlags::RDONLY | OFlags::DIRECTORY | OFlags::CLOEXEC | OFlags::NOFOLLOW,
            Mode::empty(),
        )
        .map_err(|err| {
            storage(
                "capability_root_open_failed",
                &format!(
                    "cannot open directory FD for capability root '{}': {err}",
                    canonical.display()
                ),
            )
        })?;
        Ok(Self {
            fd,
            #[cfg(not(target_os = "linux"))]
            path: canonical.to_path_buf(),
        })
    }

    /// Duplicates the root descriptor as a [`Dir`] anchor.
    ///
    /// # Errors
    /// Storage error when the descriptor cannot be reopened.
    pub fn as_dir(&self) -> Result<Dir, LomoError> {
        let fd = rustix::fs::openat(
            &self.fd,
            ".",
            OFlags::RDONLY | OFlags::DIRECTORY | OFlags::CLOEXEC,
            Mode::empty(),
        )
        .map_err(|err| storage("open_failed", &format!("failed to dup root fd: {err}")))?;
        Ok(Dir {
            fd,
            #[cfg(not(target_os = "linux"))]
            path: self.path.clone(),
        })
    }

    /// Opens the root directory itself as a [`Node`] for stat calls.
    ///
    /// # Errors
    /// Storage error when the root cannot be reopened.
    pub fn open_self(&self) -> Result<Node, LomoError> {
        let fd = rustix::fs::openat(
            &self.fd,
            ".",
            OFlags::RDONLY | OFlags::DIRECTORY | OFlags::CLOEXEC,
            Mode::empty(),
        )
        .map_err(|err| storage("stat_failed", &format!("failed to open root: {err}")))?;
        Ok(Node { fd })
    }
}

impl Dir {
    /// Opens the single `name` component as a directory, rejecting links.
    ///
    /// # Errors
    /// `document_not_found` when absent, permission on symlink, storage otherwise.
    pub fn open_dir(&self, name: &str) -> Result<Self, LomoError> {
        let fd = rustix::fs::openat(
            &self.fd,
            name,
            OFlags::RDONLY | OFlags::DIRECTORY | OFlags::CLOEXEC | OFlags::NOFOLLOW,
            Mode::empty(),
        )
        .map_err(|err| map_open_segment_error(name, err, true))?;
        Ok(Self {
            fd,
            #[cfg(not(target_os = "linux"))]
            path: self.path.join(name),
        })
    }

    /// Opens the single `name` component for metadata or byte reads.
    ///
    /// # Errors
    /// `document_not_found` when absent, permission on symlink, storage otherwise.
    pub fn open_node(&self, name: &str) -> Result<Node, LomoError> {
        let fd = rustix::fs::openat(
            &self.fd,
            name,
            OFlags::RDONLY | OFlags::CLOEXEC | OFlags::NONBLOCK | OFlags::NOFOLLOW,
            Mode::empty(),
        )
        .map_err(|err| map_open_segment_error(name, err, false))?;
        Ok(Node { fd })
    }

    /// Sorted entry names; non-UTF-8 names are surfaced as validation errors.
    ///
    /// Linux reads through the pinned descriptor (`RawDir`); other Unix targets
    /// reopen the recorded path — observation only, never a write anchor.
    ///
    /// # Errors
    /// Storage on read failure, validation on non-UTF-8 entry names.
    #[cfg(target_os = "linux")]
    pub fn entries(&self) -> Result<Vec<String>, LomoError> {
        let mut buf = [MaybeUninit::uninit(); 4096];
        let mut raw_dir = rustix::fs::RawDir::new(&self.fd, &mut buf);
        let mut entry_names = Vec::new();
        while let Some(entry_res) = raw_dir.next() {
            let entry = entry_res.map_err(|err| {
                storage(
                    "read_dir_entry_failed",
                    &format!("failed to read directory entry: {err}"),
                )
            })?;
            let entry_name = entry.file_name().to_str().map_err(|err| {
                validation(
                    "invalid_utf8_filename",
                    &format!("non-UTF-8 directory entry: {err}"),
                )
            })?;
            if entry_name == "." || entry_name == ".." {
                continue;
            }
            entry_names.push(entry_name.to_owned());
        }
        entry_names.sort();
        Ok(entry_names)
    }

    /// Sorted entry names via `read_dir` on the recorded path.
    ///
    /// # Errors
    /// Storage on read failure, validation on non-UTF-8 entry names.
    #[cfg(not(target_os = "linux"))]
    pub fn entries(&self) -> Result<Vec<String>, LomoError> {
        let mut entry_names = Vec::new();
        for entry_res in std::fs::read_dir(&self.path).map_err(|err| {
            storage(
                "read_dir_entry_failed",
                &format!("failed to read directory entry: {err}"),
            )
        })? {
            let entry = entry_res.map_err(|err| {
                storage(
                    "read_dir_entry_failed",
                    &format!("failed to read directory entry: {err}"),
                )
            })?;
            let name = entry.file_name();
            let entry_name = name
                .to_str()
                .ok_or_else(|| validation("invalid_utf8_filename", "non-UTF-8 directory entry"))?;
            entry_names.push(entry_name.to_owned());
        }
        entry_names.sort();
        Ok(entry_names)
    }

    /// Creates `name` as a directory beneath this anchor.
    ///
    /// # Errors
    /// Storage error on `mkdirat` failure.
    pub fn mkdir(&self, name: &str) -> Result<(), LomoError> {
        rustix::fs::mkdirat(&self.fd, name, Mode::from_bits_truncate(0o755)).map_err(|err| {
            storage(
                "create_directory_failed",
                &format!("mkdirat failed on '{name}': {err}"),
            )
        })
    }

    /// Creates a private temp file `name` exclusively; fails when it already exists.
    ///
    /// # Errors
    /// Storage error on open failure.
    pub fn create_temp(&self, name: &str) -> Result<File, LomoError> {
        let fd = rustix::fs::openat(
            &self.fd,
            name,
            OFlags::RDWR | OFlags::CREATE | OFlags::EXCL | OFlags::CLOEXEC,
            Mode::from_bits_truncate(0o600),
        )
        .map_err(|err| {
            storage(
                "create_temp_file_failed",
                &format!("failed to open temp file: {err}"),
            )
        })?;
        Ok(File::from(fd))
    }

    /// Removes a temp `name` beneath this anchor; an already-absent temp is fine.
    ///
    /// # Errors
    /// Storage error on `unlinkat` failure other than a missing entry.
    pub fn unlink_temp(&self, name: &str) -> Result<(), LomoError> {
        match rustix::fs::unlinkat(&self.fd, name, AtFlags::empty()) {
            Ok(()) | Err(Errno::NOENT) => Ok(()),
            Err(err) => Err(storage(
                "unlink_failed",
                &format!("failed to unlink '{name}': {err}"),
            )),
        }
    }

    /// Removes `name` beneath this anchor; directories must be empty.
    ///
    /// # Errors
    /// Storage error on stat or unlink failure.
    pub fn remove_entry(&self, name: &str) -> Result<(), LomoError> {
        let stat = rustix::fs::statat(&self.fd, name, AtFlags::SYMLINK_NOFOLLOW)
            .map_err(|err| storage("stat_failed", &format!("failed to stat '{name}': {err}")))?;
        let flags = if rustix::fs::FileType::from_raw_mode(stat.st_mode).is_dir() {
            AtFlags::REMOVEDIR
        } else {
            AtFlags::empty()
        };
        rustix::fs::unlinkat(&self.fd, name, flags).map_err(|err| {
            storage(
                "unlink_failed",
                &format!("failed to unlink '{name}': {err}"),
            )
        })
    }

    /// Publishes `temp_name` as `name`. `Create` never replaces an existing entry;
    /// `Replace` atomically swaps.
    ///
    /// # Errors
    /// Conflict when `Create` finds an existing target; storage on rename failure.
    pub fn publish_temp(
        &self,
        temp_name: &str,
        name: &str,
        mode: lomo_core::WriteMode,
    ) -> Result<(), LomoError> {
        if mode == lomo_core::WriteMode::Create {
            match rustix::fs::renameat_with(
                &self.fd,
                temp_name,
                &self.fd,
                name,
                RenameFlags::NOREPLACE,
            ) {
                Ok(()) => return Ok(()),
                Err(Errno::EXIST) => {
                    return Err(conflict(
                        "platform_postcondition_mismatch",
                        "Create refused because the target already exists",
                    ));
                }
                Err(Errno::NOSYS) => {}
                Err(err) => {
                    return Err(storage(
                        "rename_file_failed",
                        &format!("renameat failed: {err}"),
                    ));
                }
            }
            let link_res =
                rustix::fs::linkat(&self.fd, temp_name, &self.fd, name, AtFlags::empty());
            match rustix::fs::unlinkat(&self.fd, temp_name, AtFlags::empty()) {
                Ok(()) => {}
                Err(err) => {
                    return Err(storage(
                        "unlink_temp_failed",
                        &format!("failed to clean up temp file after link: {err}"),
                    ));
                }
            }
            match link_res {
                Ok(()) => Ok(()),
                Err(Errno::EXIST) => Err(conflict(
                    "platform_postcondition_mismatch",
                    "Create refused because the target already exists",
                )),
                Err(err) => Err(storage(
                    "link_file_failed",
                    &format!("linkat failed: {err}"),
                )),
            }
        } else {
            rustix::fs::renameat(&self.fd, temp_name, &self.fd, name)
                .map_err(|err| storage("rename_file_failed", &format!("renameat failed: {err}")))
        }
    }

    /// Moves `name` from this directory into `dst` under `dst_name`.
    /// `no_replace` maps to `RENAME_NOREPLACE` (atomic on Linux via `renameat2`,
    /// atomic on macOS via `renameatx_np`).
    ///
    /// # Errors
    /// Conflict when the target exists under `no_replace`; storage otherwise.
    pub fn move_entry(
        &self,
        name: &str,
        dst: &Self,
        dst_name: &str,
        no_replace: bool,
    ) -> Result<(), LomoError> {
        let flags = if no_replace {
            RenameFlags::NOREPLACE
        } else {
            RenameFlags::empty()
        };
        rustix::fs::renameat_with(&self.fd, name, &dst.fd, dst_name, flags).map_err(|err| {
            if err == Errno::EXIST {
                conflict(
                    "platform_postcondition_mismatch",
                    "Move refused because the destination already exists",
                )
            } else {
                storage("move_failed", &format!("renameat failed: {err}"))
            }
        })
    }

    /// Flushes directory metadata to durable storage.
    ///
    /// # Errors
    /// Storage error on `fsync` failure.
    pub fn fsync(&self) -> Result<(), LomoError> {
        rustix::fs::fsync(&self.fd)
            .map_err(|err| storage("fsync_failed", &format!("fsync failed: {err}")))
    }
}

impl Node {
    /// Classifies the opened entry.
    ///
    /// # Errors
    /// Storage error on `fstat` failure.
    pub fn kind(&self) -> Result<NodeKind, LomoError> {
        let stat = rustix::fs::fstat(&self.fd)
            .map_err(|err| storage("stat_failed", &format!("fstat failed: {err}")))?;
        let kind = rustix::fs::FileType::from_raw_mode(stat.st_mode);
        Ok(if kind.is_dir() {
            NodeKind::Directory
        } else if kind.is_file() {
            NodeKind::File
        } else {
            NodeKind::Other
        })
    }

    /// Converts the handle into a standard file for streaming reads.
    #[must_use]
    pub fn into_file(self) -> File {
        File::from(self.fd)
    }

    /// Metadata-only change token for listing evidence: device, inode, size,
    /// modification and status-change nanoseconds. No byte is read; the token
    /// changes on every write, rename-in-place or metadata mutation, which is
    /// exactly the signal a reconcile diff needs before re-hashing anything.
    ///
    /// # Errors
    /// Storage error on `fstat` failure.
    pub fn change_token(&self) -> Result<(u64, String), LomoError> {
        let stat = rustix::fs::fstat(&self.fd)
            .map_err(|err| storage("stat_failed", &format!("fstat failed: {err}")))?;
        let length = u64::try_from(stat.st_size)
            .map_err(|_err| storage("stat_failed", "negative file length"))?;
        Ok((
            length,
            format!(
                "st.{:x}.{:x}.{}.{}.{}",
                stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_nsec, stat.st_ctime_nsec
            ),
        ))
    }
}

/// Resolves `rel` beneath `root` in one `openat2` call on Linux, else walks
/// components. Whole-path `RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS` rejects both
/// escapes and links atomically; the component walk offers the same rejections
/// with per-segment `O_NOFOLLOW`.
///
/// # Errors
/// `document_not_found` when absent, permission on symlink/escape, storage otherwise.
pub fn open_beneath(root: &Root, rel: &str) -> Result<Node, LomoError> {
    #[cfg(target_os = "linux")]
    {
        match rustix::fs::openat2(
            &root.fd,
            rel,
            OFlags::RDONLY | OFlags::CLOEXEC | OFlags::NONBLOCK,
            Mode::empty(),
            rustix::fs::ResolveFlags::BENEATH | rustix::fs::ResolveFlags::NO_SYMLINKS,
        ) {
            Ok(fd) => return Ok(Node { fd }),
            Err(Errno::LOOP | Errno::XDEV) => {
                return Err(permission(
                    "symlink_escape_rejected",
                    &format!("symbolic link traversal rejected for '{rel}'"),
                ));
            }
            Err(Errno::NOSYS) => {}
            Err(Errno::NOENT) => {
                return Err(storage(
                    "document_not_found",
                    &format!("path '{rel}' not found"),
                ));
            }
            Err(err) => {
                return Err(storage(
                    "open_failed",
                    &format!("failed to open path '{rel}': {err}"),
                ));
            }
        }
    }
    super::walk_node_at(root, rel)
}

/// Maps a single-segment open failure to the shared error vocabulary.
fn map_open_segment_error(name: &str, err: Errno, _directory: bool) -> LomoError {
    match err {
        Errno::LOOP => permission(
            "symlink_escape_rejected",
            &format!("symbolic link rejected for segment '{name}'"),
        ),
        Errno::NOENT => storage("document_not_found", &format!("segment '{name}' not found")),
        _ => storage(
            "open_failed",
            &format!("failed to open segment '{name}': {err}"),
        ),
    }
}
