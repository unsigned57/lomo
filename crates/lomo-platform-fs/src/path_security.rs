use std::io::copy;
use std::path::Path;

use lomo_core::{
    ActionEvidence, DocumentHandle, DocumentKind, DocumentMetadata, LomoError,
    RelativeWorkspacePath, Sha256Digest, WorkspaceTarget,
};
use rustix::fd::OwnedFd;
use rustix::fs::{Mode, OFlags, ResolveFlags};
use rustix::io::Errno;
use sha2::{Digest, Sha256};

use crate::error::{permission, storage};

/// Opens a path beneath `root_fd` ensuring resolution cannot escape `root_fd`
/// and symbolic links are strictly rejected.
///
/// # Errors
///
/// Returns permission error if a symbolic link or escape is detected,
/// storage error if the file is absent or open syscall fails.
pub fn open_beneath(
    root_fd: &OwnedFd,
    rel_path: &str,
    oflags: OFlags,
    mode: Mode,
) -> Result<OwnedFd, LomoError> {
    if rel_path.is_empty() || rel_path == "." {
        return rustix::fs::openat(
            root_fd,
            ".",
            OFlags::RDONLY | OFlags::DIRECTORY | OFlags::CLOEXEC,
            Mode::empty(),
        )
        .map_err(|err| {
            storage(
                "open_root_dir_failed",
                &format!("failed to reopen root directory: {err}"),
            )
        });
    }

    let res = rustix::fs::openat2(
        root_fd,
        rel_path,
        oflags,
        mode,
        ResolveFlags::BENEATH | ResolveFlags::NO_SYMLINKS,
    );

    match res {
        Ok(fd) => Ok(fd),
        Err(Errno::LOOP | Errno::XDEV) => Err(permission(
            "symlink_escape_rejected",
            &format!("symbolic link traversal rejected for '{rel_path}'"),
        )),
        Err(Errno::NOSYS) => open_step_by_step(root_fd, rel_path, oflags, mode),
        Err(Errno::NOENT) => Err(storage(
            "document_not_found",
            &format!("path '{rel_path}' not found"),
        )),
        Err(err) => Err(storage(
            "open_failed",
            &format!("failed to open path '{rel_path}': {err}"),
        )),
    }
}

fn open_step_by_step(
    root_fd: &OwnedFd,
    rel_path: &str,
    oflags: OFlags,
    mode: Mode,
) -> Result<OwnedFd, LomoError> {
    let mut current_fd = rustix::fs::openat(
        root_fd,
        ".",
        OFlags::RDONLY | OFlags::DIRECTORY | OFlags::CLOEXEC,
        Mode::empty(),
    )
    .map_err(|err| storage("open_failed", &format!("failed to dup root fd: {err}")))?;

    let segments: Vec<&str> = rel_path.split('/').filter(|s| !s.is_empty()).collect();
    if segments.is_empty() {
        return Ok(current_fd);
    }

    for (i, segment) in segments.iter().enumerate() {
        if *segment == "." || *segment == ".." {
            return Err(permission(
                "symlink_escape_rejected",
                &format!("invalid relative path segment '{segment}'"),
            ));
        }

        let is_last = i == segments.len() - 1;
        if is_last {
            let fd = rustix::fs::openat(&current_fd, *segment, oflags | OFlags::NOFOLLOW, mode)
                .map_err(|err| match err {
                    Errno::LOOP => permission(
                        "symlink_escape_rejected",
                        &format!("symbolic link rejected for segment '{segment}'"),
                    ),
                    Errno::NOENT => storage(
                        "document_not_found",
                        &format!("segment '{segment}' not found"),
                    ),
                    _ => storage(
                        "open_failed",
                        &format!("failed to open segment '{segment}': {err}"),
                    ),
                })?;
            return Ok(fd);
        }

        let next_fd = rustix::fs::openat(
            &current_fd,
            *segment,
            OFlags::RDONLY | OFlags::DIRECTORY | OFlags::CLOEXEC | OFlags::NOFOLLOW,
            Mode::empty(),
        )
        .map_err(|err| match err {
            Errno::LOOP => permission(
                "symlink_escape_rejected",
                &format!("symbolic link directory rejected for segment '{segment}'"),
            ),
            Errno::NOENT => storage(
                "document_not_found",
                &format!("directory segment '{segment}' not found"),
            ),
            _ => storage(
                "open_failed",
                &format!("failed to open directory segment '{segment}': {err}"),
            ),
        })?;
        current_fd = next_fd;
    }

    Ok(current_fd)
}

/// Checks whether a document exists beneath `root_fd`, strictly rejecting symbolic links.
///
/// # Errors
///
/// Returns permission error if a symbolic link is traversed, or storage error on unexpected I/O failure.
pub fn exists_beneath(root_fd: &OwnedFd, rel_path: &str) -> Result<bool, LomoError> {
    match open_beneath(
        root_fd,
        rel_path,
        OFlags::RDONLY | OFlags::CLOEXEC | OFlags::NONBLOCK,
        Mode::empty(),
    ) {
        Ok(_) => Ok(true),
        Err(err) if err.code() == "document_not_found" => Ok(false),
        Err(err) => Err(err),
    }
}

/// Resolves the parent directory FD and file name for a relative path beneath `root_fd`.
///
/// # Errors
///
/// Returns error if parent path resolution fails or escapes `root_fd`.
pub fn open_parent_beneath(
    root_fd: &OwnedFd,
    rel_path: &str,
) -> Result<(OwnedFd, String), LomoError> {
    let mut parts: Vec<&str> = rel_path.split('/').filter(|s| !s.is_empty()).collect();
    let Some(file_name) = parts.pop() else {
        return Err(storage("invalid_path", "empty relative path has no parent"));
    };

    let parent_fd = if parts.is_empty() {
        rustix::fs::openat(
            root_fd,
            ".",
            OFlags::RDONLY | OFlags::DIRECTORY | OFlags::CLOEXEC,
            Mode::empty(),
        )
        .map_err(|err| {
            storage(
                "open_failed",
                &format!("failed to open root directory: {err}"),
            )
        })?
    } else {
        let parent_rel = parts.join("/");
        open_beneath(
            root_fd,
            &parent_rel,
            OFlags::RDONLY | OFlags::DIRECTORY | OFlags::CLOEXEC,
            Mode::empty(),
        )?
    };

    Ok((parent_fd, file_name.to_owned()))
}

/// Queries metadata for a target directly from descriptor beneath `root_fd`.
///
/// # Errors
///
/// Returns permission error on symlink escape, or storage error on missing target or I/O failure.
pub fn stat_document_fd(
    root_fd: &OwnedFd,
    target: &WorkspaceTarget,
) -> Result<DocumentMetadata, LomoError> {
    let (fd, handle_str) = match target {
        WorkspaceTarget::Root => {
            let fd = rustix::fs::openat(
                root_fd,
                ".",
                OFlags::RDONLY | OFlags::DIRECTORY | OFlags::CLOEXEC,
                Mode::empty(),
            )
            .map_err(|err| storage("stat_failed", &format!("failed to open root: {err}")))?;
            (fd, "root".to_owned())
        }
        WorkspaceTarget::Relative(rel) => {
            let fd = open_beneath(
                root_fd,
                rel.as_str(),
                OFlags::RDONLY | OFlags::CLOEXEC | OFlags::NONBLOCK,
                Mode::empty(),
            )?;
            (fd, rel.as_str().to_owned())
        }
    };

    let stat = rustix::fs::fstat(&fd).map_err(|err| {
        storage(
            "stat_failed",
            &format!("fstat failed on '{handle_str}': {err}"),
        )
    })?;

    let doc_handle = DocumentHandle::parse(&handle_str)?;

    let kind = rustix::fs::FileType::from_raw_mode(stat.st_mode);
    if !kind.is_dir() && !kind.is_file() {
        return Err(storage(
            "unsupported_document_kind",
            "workspace documents must be regular files or directories",
        ));
    }
    let is_dir = kind.is_dir();
    if is_dir {
        let fp = compute_sha256_bytes(handle_str.as_bytes())?;
        let evidence = ActionEvidence::unknown(0, fp.as_str())?;
        DocumentMetadata::new_with_handle(
            target.clone(),
            doc_handle,
            DocumentKind::Directory,
            None,
            evidence,
        )
    } else {
        let mut file = std::fs::File::from(fd);
        let mut content = Vec::new();
        copy(&mut file, &mut content).map_err(|err| {
            storage(
                "read_file_failed",
                &format!("failed to read file bytes: {err}"),
            )
        })?;
        let length = u64::try_from(content.len()).map_err(|err| {
            storage(
                "file_length_overflow",
                &format!("file length exceeds u64: {err}"),
            )
        })?;
        let digest = compute_sha256_bytes(&content)?;
        let fp = digest.as_str().to_owned();
        let evidence = ActionEvidence::verified(length, digest, &fp)?;
        let mime = if Path::new(&handle_str)
            .extension()
            .is_some_and(|ext| ext.eq_ignore_ascii_case("md"))
        {
            Some("text/markdown")
        } else {
            Some("application/octet-stream")
        };
        DocumentMetadata::new_with_handle(
            target.clone(),
            doc_handle,
            DocumentKind::File,
            mime,
            evidence,
        )
    }
}

/// Reads source bytes in a single streaming operation and computes authoritative evidence.
///
/// # Errors
///
/// Returns permission error on symlink escape, storage error on absent file or read failure.
pub fn read_document_stream(
    root_fd: &OwnedFd,
    rel: &RelativeWorkspacePath,
) -> Result<(DocumentMetadata, Vec<u8>), LomoError> {
    let handle_str = rel.as_str().to_owned();
    let fd = open_beneath(
        root_fd,
        &handle_str,
        OFlags::RDONLY | OFlags::CLOEXEC | OFlags::NONBLOCK,
        Mode::empty(),
    )?;

    let stat = rustix::fs::fstat(&fd).map_err(|err| {
        storage(
            "stat_failed",
            &format!("fstat failed on '{handle_str}': {err}"),
        )
    })?;

    let kind = rustix::fs::FileType::from_raw_mode(stat.st_mode);
    if !kind.is_dir() && !kind.is_file() {
        return Err(storage(
            "unsupported_document_kind",
            "workspace documents must be regular files or directories",
        ));
    }
    let is_dir = kind.is_dir();
    if is_dir {
        return Err(storage(
            "document_not_found",
            &format!("path '{handle_str}' is a directory, not a regular file"),
        ));
    }

    let mut file = std::fs::File::from(fd);
    let mut content = Vec::new();
    copy(&mut file, &mut content).map_err(|err| {
        storage(
            "read_file_failed",
            &format!("failed to read file bytes for '{handle_str}': {err}"),
        )
    })?;

    let length = u64::try_from(content.len()).map_err(|err| {
        storage(
            "file_length_overflow",
            &format!("file length exceeds u64: {err}"),
        )
    })?;
    let digest = compute_sha256_bytes(&content)?;
    let fp = digest.as_str().to_owned();
    let evidence = ActionEvidence::verified(length, digest, &fp)?;
    let mime = if Path::new(&handle_str)
        .extension()
        .is_some_and(|ext| ext.eq_ignore_ascii_case("md"))
    {
        Some("text/markdown")
    } else {
        Some("application/octet-stream")
    };
    let doc_handle = DocumentHandle::parse(&handle_str)?;
    let metadata = DocumentMetadata::new_with_handle(
        WorkspaceTarget::Relative(rel.clone()),
        doc_handle,
        DocumentKind::File,
        mime,
        evidence,
    )?;

    Ok((metadata, content))
}

pub fn compute_sha256_bytes(data: &[u8]) -> Result<Sha256Digest, LomoError> {
    let mut hasher = Sha256::new();
    hasher.update(data);
    let hex = format!("{:x}", hasher.finalize());
    Sha256Digest::parse(&hex)
}
