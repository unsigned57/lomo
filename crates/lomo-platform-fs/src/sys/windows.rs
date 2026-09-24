//! Windows std-only filesystem primitives.
//!
//! `Root`/`Dir` keep a canonical path plus an open handle; `Node` is an open
//! `File`. Symbolic-link rejection is atomic per component: every open uses
//! `FILE_FLAG_OPEN_REPARSE_POINT`, which yields a handle to the link itself
//! instead of following it, and `file_type().is_symlink()` is checked on the
//! opened handle. `FILE_FLAG_BACKUP_SEMANTICS` lets plain `OpenOptions` hold
//! directory handles, keeping the whole backend free of `unsafe` code.
//!
//! Windows has no `openat`: component resolution re-joins the canonical parent
//! path per level, so a directory swapped between levels could still be raced.
//! The root handle pins the root directory itself against deletion.

use std::fs::{self, File, OpenOptions};
use std::os::windows::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};

use lomo_core::LomoError;

use crate::error::{conflict, permission, storage, validation};

const OPEN_REPARSE: u32 = 0x0020_0000; // FILE_FLAG_OPEN_REPARSE_POINT
const BACKUP_SEMANTICS: u32 = 0x0200_0000; // FILE_FLAG_BACKUP_SEMANTICS

/// Canonical root path plus an open handle pinning the directory.
#[derive(Debug)]
pub struct Root {
    path: PathBuf,
    _handle: File,
}

/// Directory anchor: canonical path for joins plus an open dir handle.
///
/// The handle is held purely for its RAII pin: an open directory handle blocks
/// rename/deletion of the directory itself, approximating the fd-pinning the
/// Unix backend relies on. It is never read or written.
#[derive(Debug)]
pub struct Dir {
    path: PathBuf,
    _handle: File,
}

/// Open handle on a single resolved leaf entry (file or directory).
#[derive(Debug)]
pub struct Node {
    file: File,
}

/// Entry classification used by stat/remove paths.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum NodeKind {
    File,
    Directory,
    Other,
}

/// Opens `path` without following a final reparse point, then rejects links
/// and non-directories on the opened handle itself.
fn open_dir_handle(path: &Path, label: &str) -> Result<File, LomoError> {
    let file = OpenOptions::new()
        .read(true)
        .custom_flags(OPEN_REPARSE | BACKUP_SEMANTICS)
        .open(path)
        .map_err(|err| {
            if err.kind() == std::io::ErrorKind::NotFound {
                storage(
                    "document_not_found",
                    &format!("segment '{label}' not found"),
                )
            } else {
                storage(
                    "open_failed",
                    &format!("failed to open segment '{label}': {err}"),
                )
            }
        })?;
    check_handle(&file, label, true)?;
    Ok(file)
}

/// Rejects symlink reparse points and, when `require_dir`, non-directory nodes.
fn check_handle(file: &File, label: &str, require_dir: bool) -> Result<(), LomoError> {
    let metadata = file
        .metadata()
        .map_err(|err| storage("stat_failed", &format!("failed to stat '{label}': {err}")))?;
    if metadata.file_type().is_symlink() {
        return Err(permission(
            "symlink_escape_rejected",
            &format!("symbolic link rejected for segment '{label}'"),
        ));
    }
    if require_dir && !metadata.is_dir() {
        return Err(storage(
            "open_failed",
            &format!("segment '{label}' is not a directory"),
        ));
    }
    Ok(())
}

impl Root {
    /// Opens `canonical` as a pinned, non-symlink directory root.
    ///
    /// # Errors
    /// Storage error when the directory cannot be opened, permission on a link.
    pub fn open(canonical: &Path) -> Result<Self, LomoError> {
        let handle = open_dir_handle(canonical, &canonical.display().to_string())?;
        Ok(Self {
            path: canonical.to_path_buf(),
            _handle: handle,
        })
    }

    /// Reopens the root directory as a [`Dir`] anchor.
    ///
    /// # Errors
    /// Storage error when the directory cannot be reopened.
    pub fn as_dir(&self) -> Result<Dir, LomoError> {
        let handle = open_dir_handle(&self.path, &self.path.display().to_string())?;
        Ok(Dir {
            path: self.path.clone(),
            _handle: handle,
        })
    }

    /// Opens the root directory itself as a [`Node`] for stat calls.
    ///
    /// # Errors
    /// Storage error when the root cannot be reopened, permission on a link.
    pub fn open_self(&self) -> Result<Node, LomoError> {
        let file = OpenOptions::new()
            .read(true)
            .custom_flags(OPEN_REPARSE | BACKUP_SEMANTICS)
            .open(&self.path)
            .map_err(|err| storage("stat_failed", &format!("failed to open root: {err}")))?;
        let metadata = file
            .metadata()
            .map_err(|err| storage("stat_failed", &format!("failed to stat root: {err}")))?;
        if metadata.file_type().is_symlink() {
            return Err(permission(
                "symlink_escape_rejected",
                "capability root is a symbolic link",
            ));
        }
        Ok(Node { file })
    }
}

impl Dir {
    /// Opens the single `name` component as a directory, rejecting links.
    ///
    /// # Errors
    /// `document_not_found` when absent, permission on symlink, storage otherwise.
    pub fn open_dir(&self, name: &str) -> Result<Dir, LomoError> {
        let path = self.path.join(name);
        let handle = open_dir_handle(&path, name)?;
        Ok(Dir {
            path,
            _handle: handle,
        })
    }

    /// Opens the single `name` component for metadata or byte reads.
    ///
    /// # Errors
    /// `document_not_found` when absent, permission on symlink, storage otherwise.
    pub fn open_node(&self, name: &str) -> Result<Node, LomoError> {
        let file = OpenOptions::new()
            .read(true)
            .custom_flags(OPEN_REPARSE | BACKUP_SEMANTICS)
            .open(self.path.join(name))
            .map_err(|err| {
                if err.kind() == std::io::ErrorKind::NotFound {
                    storage("document_not_found", &format!("segment '{name}' not found"))
                } else {
                    storage(
                        "open_failed",
                        &format!("failed to open segment '{name}': {err}"),
                    )
                }
            })?;
        let metadata = file
            .metadata()
            .map_err(|err| storage("stat_failed", &format!("failed to stat '{name}': {err}")))?;
        if metadata.file_type().is_symlink() {
            return Err(permission(
                "symlink_escape_rejected",
                &format!("symbolic link rejected for segment '{name}'"),
            ));
        }
        Ok(Node { file })
    }

    /// Sorted entry names; non-UTF-8 names are surfaced as validation errors.
    ///
    /// # Errors
    /// Storage on read failure, validation on non-UTF-8 entry names.
    pub fn entries(&self) -> Result<Vec<String>, LomoError> {
        let mut names = Vec::new();
        for entry in fs::read_dir(&self.path).map_err(|err| {
            storage(
                "read_dir_entry_failed",
                &format!("failed to read directory '{}': {err}", self.path.display()),
            )
        })? {
            let entry = entry.map_err(|err| {
                storage(
                    "read_dir_entry_failed",
                    &format!("failed to read directory entry: {err}"),
                )
            })?;
            let name = entry.file_name();
            let name = name
                .to_str()
                .ok_or_else(|| validation("invalid_utf8_filename", "non-UTF-8 directory entry"))?;
            names.push(name.to_owned());
        }
        names.sort();
        Ok(names)
    }

    /// Creates `name` as a directory beneath this anchor.
    ///
    /// # Errors
    /// Storage error on `create_dir` failure.
    pub fn mkdir(&self, name: &str) -> Result<(), LomoError> {
        fs::create_dir(self.path.join(name)).map_err(|err| {
            storage(
                "create_directory_failed",
                &format!("mkdir failed on '{name}': {err}"),
            )
        })
    }

    /// Creates a private temp file `name` exclusively; fails when it already exists.
    ///
    /// # Errors
    /// Storage error on open failure.
    pub fn create_temp(&self, name: &str) -> Result<File, LomoError> {
        OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .open(self.path.join(name))
            .map_err(|err| {
                storage(
                    "create_temp_file_failed",
                    &format!("failed to open temp file: {err}"),
                )
            })
    }

    /// Removes a temp `name` beneath this anchor; an already-absent temp is fine.
    ///
    /// # Errors
    /// Storage error on removal failure other than a missing entry.
    pub fn unlink_temp(&self, name: &str) -> Result<(), LomoError> {
        match fs::remove_file(self.path.join(name)) {
            Ok(()) => Ok(()),
            Err(err) if err.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(err) => Err(storage(
                "unlink_failed",
                &format!("failed to unlink '{name}': {err}"),
            )),
        }
    }

    /// Removes `name` beneath this anchor; directories must be empty.
    ///
    /// # Errors
    /// Storage error on stat or removal failure.
    pub fn remove_entry(&self, name: &str) -> Result<(), LomoError> {
        remove_path(&self.path.join(name), name)
    }

    /// Publishes `temp_name` as `name`. `Create` uses `hard_link` + remove for an
    /// atomic no-clobber commit; `Replace` renames over the target.
    ///
    /// # Errors
    /// Conflict when `Create` finds an existing target; storage otherwise.
    pub fn publish_temp(
        &self,
        temp_name: &str,
        name: &str,
        mode: lomo_core::WriteMode,
    ) -> Result<(), LomoError> {
        let temp_path = self.path.join(temp_name);
        let target = self.path.join(name);
        match mode {
            lomo_core::WriteMode::Create => {
                let link_res = fs::hard_link(&temp_path, &target);
                match fs::remove_file(&temp_path) {
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
                    Err(err) if err.kind() == std::io::ErrorKind::AlreadyExists => Err(conflict(
                        "platform_postcondition_mismatch",
                        "Create refused because the target already exists",
                    )),
                    Err(err) => Err(storage(
                        "link_file_failed",
                        &format!("hard_link failed: {err}"),
                    )),
                }
            }
            lomo_core::WriteMode::Replace => fs::rename(&temp_path, &target)
                .map_err(|err| storage("rename_file_failed", &format!("rename failed: {err}"))),
        }
    }

    /// Moves `name` from this directory into `dst` under `dst_name`.
    /// `no_replace` is atomic for files (`hard_link` + remove); directories rely
    /// on `MoveFileExW` failing when the destination exists.
    ///
    /// # Errors
    /// Conflict when the target exists under `no_replace`; storage otherwise.
    pub fn move_entry(
        &self,
        name: &str,
        dst: &Dir,
        dst_name: &str,
        no_replace: bool,
    ) -> Result<(), LomoError> {
        let src = self.path.join(name);
        let target = dst.path.join(dst_name);
        if no_replace && target.symlink_metadata().is_ok() {
            return Err(conflict(
                "platform_postcondition_mismatch",
                "Move refused because the destination already exists",
            ));
        }
        if !no_replace {
            return fs::rename(&src, &target)
                .map_err(|err| storage("move_failed", &format!("rename failed: {err}")));
        }
        let is_dir = src
            .symlink_metadata()
            .map_err(|err| storage("stat_failed", &format!("failed to stat '{name}': {err}")))?
            .is_dir();
        if is_dir {
            return fs::rename(&src, &target)
                .map_err(|err| storage("move_failed", &format!("rename failed: {err}")));
        }
        fs::hard_link(&src, &target)
            .and_then(|()| fs::remove_file(&src))
            .map_err(|err| {
                if err.kind() == std::io::ErrorKind::AlreadyExists {
                    conflict(
                        "platform_postcondition_mismatch",
                        "Move refused because the destination already exists",
                    )
                } else {
                    storage("move_failed", &format!("move failed: {err}"))
                }
            })
    }

    /// Flushes directory metadata to durable storage.
    ///
    /// Windows exposes no directory-sync syscall: NTFS journals directory
    /// metadata, and `FlushFileBuffers` rejects directory handles. Durability
    /// of the directory entry therefore rides on the NTFS journal; file data is
    /// still fsynced individually before publication.
    pub fn fsync(&self) -> Result<(), LomoError> {
        Ok(())
    }
}

impl Node {
    /// Classifies the opened entry.
    ///
    /// # Errors
    /// Storage error on `metadata` failure.
    pub fn kind(&self) -> Result<NodeKind, LomoError> {
        let metadata = self
            .file
            .metadata()
            .map_err(|err| storage("stat_failed", &format!("fstat failed: {err}")))?;
        Ok(if metadata.is_dir() {
            NodeKind::Directory
        } else if metadata.is_file() {
            NodeKind::File
        } else {
            NodeKind::Other
        })
    }

    /// Converts the handle into a standard file for streaming reads.
    #[must_use]
    pub fn into_file(self) -> File {
        self.file
    }
}

/// Resolves `rel` beneath `root` by walking components; each level opens the
/// entry with `FILE_FLAG_OPEN_REPARSE_POINT` and rejects reparse points on the
/// opened handle.
///
/// # Errors
/// `document_not_found` when absent, permission on symlink, storage otherwise.
pub fn open_beneath(root: &Root, rel: &str) -> Result<Node, LomoError> {
    super::walk_node_at(root, rel)
}

/// Removes a file, symlink, or empty directory at `path`.
fn remove_path(path: &Path, name: &str) -> Result<(), LomoError> {
    let metadata = fs::symlink_metadata(path)
        .map_err(|err| storage("stat_failed", &format!("failed to stat '{name}': {err}")))?;
    let result = if metadata.is_dir() {
        fs::remove_dir(path)
    } else {
        fs::remove_file(path)
    };
    result.map_err(|err| {
        storage(
            "unlink_failed",
            &format!("failed to unlink '{name}': {err}"),
        )
    })
}
