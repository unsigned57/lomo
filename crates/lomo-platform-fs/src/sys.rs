//! Platform filesystem primitives behind one boundary-internal API.
//!
//! `Root` pins a bound capability directory, `Dir` anchors entry operations
//! inside it, and `Node` is a resolved leaf handle. Every composite helper in
//! this module is implemented once against those primitives; the per-OS
//! backends in `unix` and `windows` supply only the primitive operations.

#[cfg(unix)]
mod unix;
#[cfg(unix)]
use unix as backend;
#[cfg(unix)]
pub use unix::{Dir, Node, NodeKind, Root};

#[cfg(windows)]
mod windows;
#[cfg(windows)]
use windows as backend;
#[cfg(windows)]
pub use windows::{Dir, Node, NodeKind, Root};

use std::fs::File;
use std::io::{Read, Write};
use std::path::Path;

use lomo_core::{
    ActionEvidence, DocumentHandle, DocumentKind, DocumentMetadata, LomoError,
    RelativeWorkspacePath, Sha256Digest, StagedArtifactSource, WorkspaceTarget, WriteMode,
};
use sha2::{Digest, Sha256};

use crate::error::{conflict, permission, storage};

/// Rejects `.`/`..` segments before [`Dir::open_dir`] walks them.
///
/// # Errors
/// Permission error naming the offending segment.
fn check_segment(segment: &str) -> Result<(), LomoError> {
    if segment == "." || segment == ".." {
        return Err(permission(
            "symlink_escape_rejected",
            &format!("invalid relative path segment '{segment}'"),
        ));
    }
    Ok(())
}

/// Resolves `rel` to its parent directory anchor and final component name.
///
/// # Errors
/// `document_not_found` when a component is absent, permission on
/// symlink/escape, storage otherwise.
pub fn open_parent(root: &Root, rel: &str) -> Result<(Dir, String), LomoError> {
    let mut parts: Vec<&str> = rel.split('/').filter(|s| !s.is_empty()).collect();
    let Some(file_name) = parts.pop() else {
        return Err(storage("invalid_path", "empty relative path has no parent"));
    };
    let mut dir = root.as_dir()?;
    for segment in parts {
        check_segment(segment)?;
        dir = dir.open_dir(segment)?;
    }
    Ok((dir, file_name.to_owned()))
}

/// Opens the `rel` leaf beneath `root`. Linux uses a single `openat2` with
/// `RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS`; other targets walk components via
/// [`walk_node_at`].
///
/// # Errors
/// `document_not_found` when absent, permission on symlink, storage otherwise.
pub fn open_node_at(root: &Root, rel: &str) -> Result<Node, LomoError> {
    backend::open_beneath(root, rel)
}

/// Component-wise leaf resolution shared by the non-Linux backends.
///
/// # Errors
/// `document_not_found` when absent, permission on symlink, storage otherwise.
pub fn walk_node_at(root: &Root, rel: &str) -> Result<Node, LomoError> {
    let (parent, name) = open_parent(root, rel)?;
    parent.open_node(&name)
}

/// Opens the `rel` directory beneath `root`, rejecting links at every level.
///
/// # Errors
/// `document_not_found` when absent, permission on symlink, storage otherwise.
pub fn open_dir_at(root: &Root, rel: &str) -> Result<Dir, LomoError> {
    let mut dir = root.as_dir()?;
    for segment in rel.split('/').filter(|s| !s.is_empty()) {
        check_segment(segment)?;
        dir = dir.open_dir(segment)?;
    }
    Ok(dir)
}

/// Creates `rel` (and missing intermediate directories) beneath `root`.
///
/// # Errors
/// `document_not_found` resolution failures, permission on symlink, storage
/// on mkdir/fsync failures.
pub fn ensure_directory_beneath(root: &Root, rel: &str) -> Result<Dir, LomoError> {
    let mut dir = root.as_dir()?;
    for segment in rel.split('/').filter(|s| !s.is_empty()) {
        check_segment(segment)?;
        match dir.open_dir(segment) {
            Ok(next) => dir = next,
            Err(err) if err.code() == "document_not_found" => {
                dir.mkdir(segment)?;
                dir.fsync()?;
                dir = dir.open_dir(segment)?;
            }
            Err(err) => return Err(err),
        }
    }
    Ok(dir)
}

/// Checks whether `rel` resolves beneath `root`, strictly rejecting links.
///
/// # Errors
/// Permission error on symlink escape, storage error on unexpected I/O failure.
pub fn exists_beneath(root: &Root, rel: &str) -> Result<bool, LomoError> {
    match open_node_at(root, rel) {
        Ok(_) => Ok(true),
        Err(err) if err.code() == "document_not_found" => Ok(false),
        Err(err) => Err(err),
    }
}

/// Queries metadata for `target` beneath `root`.
///
/// # Errors
/// Permission on symlink escape, storage on missing target or I/O failure.
pub fn stat_document(root: &Root, target: &WorkspaceTarget) -> Result<DocumentMetadata, LomoError> {
    let (node, handle_str) = match target {
        WorkspaceTarget::Root => (root.open_self()?, "root".to_owned()),
        WorkspaceTarget::Relative(rel) => {
            (open_node_at(root, rel.as_str())?, rel.as_str().to_owned())
        }
    };
    metadata_for(node, target.clone(), &handle_str)
}

/// Queries listing metadata for `target` beneath `root` without hashing bytes.
///
/// Directory enumeration is the reconcile worklist: hashing every child at listing
/// time makes an unchanged workspace cost a full library read. The returned evidence
/// carries an `Unknown` digest and a stat change token as its fingerprint, so
/// callers that need content authority ([`crate::sys::stat_document`],
/// `read_document_stream`) still obtain verified digests while unchanged paths are
/// cheap to compare.
///
/// # Errors
/// Permission on symlink escape, storage on missing target or I/O failure.
pub fn list_document(
    root: &Root,
    rel: &RelativeWorkspacePath,
) -> Result<DocumentMetadata, LomoError> {
    let node = open_node_at(root, rel.as_str())?;
    metadata_for_token(&node, WorkspaceTarget::Relative(rel.clone()), rel.as_str())
}

/// Reads source bytes in a single streaming operation with authoritative evidence.
///
/// # Errors
/// Permission on symlink escape, storage on absent file or read failure.
pub fn read_document_stream(
    root: &Root,
    rel: &RelativeWorkspacePath,
) -> Result<(DocumentMetadata, Vec<u8>), LomoError> {
    let handle_str = rel.as_str().to_owned();
    let node = open_node_at(root, &handle_str)?;
    if node.kind()? != NodeKind::File {
        return Err(storage(
            "document_not_found",
            &format!("path '{handle_str}' is not a regular file"),
        ));
    }
    let mut file = node.into_file();
    let mut content = Vec::new();
    std::io::copy(&mut file, &mut content).map_err(|err| {
        storage(
            "read_file_failed",
            &format!("failed to read file bytes for '{handle_str}': {err}"),
        )
    })?;
    let metadata = metadata_for_file(
        WorkspaceTarget::Relative(rel.clone()),
        &handle_str,
        &content,
    )?;
    Ok((metadata, content))
}

/// Builds `DocumentMetadata` for an opened node, hashing file bytes.
fn metadata_for(
    node: Node,
    target: WorkspaceTarget,
    handle_str: &str,
) -> Result<DocumentMetadata, LomoError> {
    let doc_handle = DocumentHandle::parse(handle_str)?;
    match node.kind()? {
        NodeKind::Directory => {
            let fp = compute_sha256_bytes(handle_str.as_bytes())?;
            let evidence = ActionEvidence::unknown(0, fp.as_str())?;
            DocumentMetadata::new_with_handle(
                target,
                doc_handle,
                DocumentKind::Directory,
                None,
                evidence,
            )
        }
        NodeKind::File => {
            let mut file = node.into_file();
            let (length, digest) = stream_sha256(&mut file)?;
            let fp = digest.as_str().to_owned();
            let evidence = ActionEvidence::verified(length, digest, &fp)?;
            DocumentMetadata::new_with_handle(
                target,
                doc_handle,
                DocumentKind::File,
                Some(mime_for(handle_str)),
                evidence,
            )
        }
        NodeKind::Other => Err(storage(
            "unsupported_document_kind",
            "workspace documents must be regular files or directories",
        )),
    }
}

/// Builds `DocumentMetadata` from stat evidence alone: files get an `Unknown`
/// digest plus a backend change token as the listing fingerprint. Only
/// [`Node::kind`] and [`Node::change_token`] touch the node — no byte is read.
fn metadata_for_token(
    node: &Node,
    target: WorkspaceTarget,
    handle_str: &str,
) -> Result<DocumentMetadata, LomoError> {
    let doc_handle = DocumentHandle::parse(handle_str)?;
    match node.kind()? {
        NodeKind::Directory => {
            let fp = compute_sha256_bytes(handle_str.as_bytes())?;
            let evidence = ActionEvidence::unknown(0, fp.as_str())?;
            DocumentMetadata::new_with_handle(
                target,
                doc_handle,
                DocumentKind::Directory,
                None,
                evidence,
            )
        }
        NodeKind::File => {
            let (length, token) = node.change_token()?;
            let evidence = ActionEvidence::unknown(length, &token)?;
            DocumentMetadata::new_with_handle(
                target,
                doc_handle,
                DocumentKind::File,
                Some(mime_for(handle_str)),
                evidence,
            )
        }
        NodeKind::Other => Err(storage(
            "unsupported_document_kind",
            "workspace documents must be regular files or directories",
        )),
    }
}

fn metadata_for_file(
    target: WorkspaceTarget,
    handle_str: &str,
    content: &[u8],
) -> Result<DocumentMetadata, LomoError> {
    let length = u64::try_from(content.len()).map_err(|err| {
        storage(
            "file_length_overflow",
            &format!("file length exceeds u64: {err}"),
        )
    })?;
    let digest = compute_sha256_bytes(content)?;
    let fp = digest.as_str().to_owned();
    let evidence = ActionEvidence::verified(length, digest, &fp)?;
    let doc_handle = DocumentHandle::parse(handle_str)?;
    DocumentMetadata::new_with_handle(
        target,
        doc_handle,
        DocumentKind::File,
        Some(mime_for(handle_str)),
        evidence,
    )
}

fn mime_for(handle_str: &str) -> &'static str {
    if Path::new(handle_str)
        .extension()
        .is_some_and(|ext| ext.eq_ignore_ascii_case("md"))
    {
        "text/markdown"
    } else {
        "application/octet-stream"
    }
}

/// Writes `bytes` into a fresh exclusive temp inside `dir`, then fsyncs it.
///
/// # Errors
/// Storage on create/write/fsync failure; the temp is cleaned up on error.
pub fn write_temp(dir: &Dir, temp_name: &str, bytes: &[u8]) -> Result<(), LomoError> {
    let mut file = dir.create_temp(temp_name)?;
    if let Err(err) = file.write_all(bytes) {
        let msg = cleanup_temp(
            dir,
            temp_name,
            &format!("failed to write temp bytes: {err}"),
        );
        return Err(storage("write_temp_file_failed", &msg));
    }
    if let Err(err) = file.sync_all() {
        let msg = cleanup_temp(dir, temp_name, &format!("failed to fsync temp file: {err}"));
        return Err(storage("fsync_temp_file_failed", &msg));
    }
    Ok(())
}

/// Streams `source_file` into a fresh temp inside `dir` while hashing, fsyncs,
/// and enforces the declared digest/length before publish.
///
/// # Errors
/// Conflict on declared-evidence mismatch; storage otherwise; temp cleaned up.
pub fn stream_temp(
    dir: &Dir,
    temp_name: &str,
    source_file: &mut File,
    source: &StagedArtifactSource,
) -> Result<(), LomoError> {
    let mut temp_file = dir.create_temp(temp_name)?;
    let mut hasher = Sha256::new();
    let mut buffer = vec![0_u8; 64 * 1024].into_boxed_slice();
    let mut total = 0_u64;
    let outcome = loop {
        match source_file.read(&mut buffer) {
            Ok(0) => break Ok(()),
            Ok(read) => {
                let Some(chunk) = buffer.get(..read) else {
                    break Err((
                        "artifact_source_mismatch",
                        "staged artifact read out of bounds".to_owned(),
                    ));
                };
                if let Err(err) = temp_file.write_all(chunk) {
                    break Err((
                        "write_temp_file_failed",
                        format!("failed to write temp bytes: {err}"),
                    ));
                }
                hasher.update(chunk);
                total = total.saturating_add(u64::try_from(read).unwrap_or(u64::MAX));
            }
            Err(err) if err.kind() == std::io::ErrorKind::Interrupted => {}
            Err(err) => {
                break Err((
                    "artifact_source_read_failed",
                    format!("failed to read staged artifact source: {err}"),
                ));
            }
        }
    };
    let outcome = outcome.and_then(|()| {
        if total != source.length()
            || format!("{:x}", hasher.finalize()) != source.digest().as_str()
        {
            return Err((
                "artifact_source_mismatch",
                "staged artifact bytes do not match the frozen declaration".to_owned(),
            ));
        }
        temp_file.sync_all().map_err(|err| {
            (
                "fsync_temp_file_failed",
                format!("failed to fsync temp file: {err}"),
            )
        })
    });
    match outcome {
        Ok(()) => Ok(()),
        Err((code, message)) => {
            let diagnostic = cleanup_temp(dir, temp_name, &message);
            if code == "artifact_source_mismatch" {
                Err(conflict(code, &diagnostic))
            } else {
                Err(storage(code, &diagnostic))
            }
        }
    }
}

/// Publishes `temp_name` as `name` under `mode`, cleaning the temp on failure
/// and appending the cleanup diagnostic.
///
/// # Errors
/// Conflict when the publish precondition breaks; storage otherwise.
pub fn commit_temp(
    dir: &Dir,
    temp_name: &str,
    name: &str,
    mode: WriteMode,
) -> Result<(), LomoError> {
    dir.publish_temp(temp_name, name, mode).map_err(|err| {
        let diagnostic = cleanup_temp(dir, temp_name, &err.to_string());
        if err.code() == "platform_postcondition_mismatch" {
            conflict("platform_postcondition_mismatch", &diagnostic)
        } else {
            storage(err.code(), &diagnostic)
        }
    })
}

/// Best-effort temp removal folded into the original diagnostic.
pub fn cleanup_temp(dir: &Dir, temp_name: &str, original_err: &str) -> String {
    match dir.unlink_temp(temp_name) {
        Ok(()) => original_err.to_owned(),
        Err(cleanup_err) => format!("{original_err} (cleanup failed: {cleanup_err})"),
    }
}

/// Removes `.tmp.{file_name}.*` siblings left by earlier crashed writes.
///
/// # Errors
/// Storage when a stale temp cannot be removed.
pub fn reclaim_temps(dir: &Dir, file_name: &str) -> Result<(), LomoError> {
    let prefix = format!(".tmp.{file_name}.");
    for entry in dir.entries()? {
        if !entry.starts_with(&prefix) {
            continue;
        }
        dir.unlink_temp(&entry).map_err(|err| {
            storage(
                "temp_reclaim_failed",
                &format!("failed to reclaim stale temp '{entry}': {err}"),
            )
        })?;
    }
    Ok(())
}

/// Computes SHA-256 of `data` as a typed digest.
///
/// # Errors
/// Validation error if the digest fails to parse (unreachable for real SHA-256).
pub fn compute_sha256_bytes(data: &[u8]) -> Result<Sha256Digest, LomoError> {
    let mut hasher = Sha256::new();
    hasher.update(data);
    let hex = format!("{:x}", hasher.finalize());
    Sha256Digest::parse(&hex)
}

/// Streams `reader` through SHA-256 with a fixed buffer, returning `(length, digest)`.
///
/// # Errors
/// Storage when the reader fails mid-stream.
pub fn stream_sha256(reader: &mut impl Read) -> Result<(u64, Sha256Digest), LomoError> {
    let mut hasher = Sha256::new();
    let mut buffer = vec![0_u8; 64 * 1024].into_boxed_slice();
    let mut length = 0_u64;
    loop {
        let read = reader.read(&mut buffer).map_err(|err| {
            storage(
                "read_file_failed",
                &format!("failed to read file bytes: {err}"),
            )
        })?;
        if read == 0 {
            break;
        }
        if let Some(chunk) = buffer.get(..read) {
            hasher.update(chunk);
        }
        length = length.saturating_add(u64::try_from(read).unwrap_or(u64::MAX));
    }
    let hex = format!("{:x}", hasher.finalize());
    Ok((length, Sha256Digest::parse(&hex)?))
}
