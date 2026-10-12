//! Executor-backed attachment destination resolution.
//!
//! The *rule* lives in `lomo-workspace` (`resolve_attachment_listing` /
//! `ATTACHMENT_LOOKUP_DIRS`); this module owns the directory evidence: a bare
//! basename stats the workspace root and enumerates every managed attachment
//! root recursively, then hands the surviving paths to the workspace law.
//!
//! Fail-closed edges:
//! - a `document_not_found` listing page means the managed directory is absent
//!   — empty evidence, not an error;
//! - any other listing rejection surfaces unchanged;
//! - media lifecycle lanes (`.lomo-media-stage` / `.lomo-media-trash` /
//!   `.lomo-media-delete-intents`) are enumerated out — pending and trashed
//!   bytes never claim a committed basename;
//! - an `Ambiguous` verdict becomes `ambiguous_attachment`, a typed error the
//!   caller must surface — the first candidate is never a default.

use std::sync::Arc;

use lomo_core::{
    DocumentKind, LomoError, PlatformActionExecutor, RelativeWorkspacePath, WorkspaceTarget,
};
use lomo_media::{MEDIA_DELETE_INTENT_DIR_NAME, MEDIA_TRASH_DIR_NAME, STAGE_DIR_NAME};
use lomo_workspace::{AttachmentResolution, resolve_attachment_listing};

use crate::{
    WorkspaceSessionConfig,
    error::validation,
    rebuild::{ListingPage, list_target_pages},
    workspace_io::WorkspaceIo,
};

/// Resolves an attachment destination to the workspace path its bytes live at.
///
/// Qualified destinations (`/` inside) answer verbatim with zero I/O — the `/`
/// is the identity. A bare basename stats the root spelling and enumerates
/// [`lomo_workspace::ATTACHMENT_LOOKUP_DIRS`]; exactly one claimant answers
/// that canonical path, none returns the authored spelling (the downstream
/// read reports `document_not_found` on the name the memo wrote), and more
/// than one fails `ambiguous_attachment`.
///
/// # Errors
/// `Validation` for an ambiguous basename; `Storage`/`Corruption` propagated
/// from listing or stat failures; `document_not_found` listing pages for
/// absent managed directories are treated as empty evidence.
pub fn resolve_attachment_path(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
    destination: &RelativeWorkspacePath,
) -> Result<RelativeWorkspacePath, LomoError> {
    let name = destination.as_str();
    if name.contains('/') {
        return Ok(destination.clone());
    }
    let io = WorkspaceIo { config, executor };
    let mut existing: Vec<RelativeWorkspacePath> = Vec::new();
    if io.stat(destination)?.is_some() {
        existing.push(destination.clone());
    }
    for dir in lomo_workspace::ATTACHMENT_LOOKUP_DIRS {
        let dir_path = RelativeWorkspacePath::parse(dir)
            .map_err(|error| crate::error::corruption("invalid_lookup_dir", error.to_string()))?;
        collect_managed_files(config, executor, &dir_path, &mut existing)?;
    }
    match resolve_attachment_listing(destination, existing) {
        AttachmentResolution::Found(path) => Ok(path),
        AttachmentResolution::Missing => Ok(destination.clone()),
        AttachmentResolution::Ambiguous(hits) => Err(validation(
            "ambiguous_attachment",
            format!(
                "attachment basename '{name}' matches {}",
                hits.iter()
                    .map(RelativeWorkspacePath::as_str)
                    .collect::<Vec<_>>()
                    .join(", ")
            ),
        )),
    }
}

/// Depth-first enumeration of file paths under `dir`, skipping media lifecycle
/// lanes. A missing directory contributes no paths; other listing failures
/// propagate — resolution never decides on rejected evidence.
fn collect_managed_files(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
    dir: &RelativeWorkspacePath,
    out: &mut Vec<RelativeWorkspacePath>,
) -> Result<(), LomoError> {
    let mut stack = vec![dir.clone()];
    while let Some(next) = stack.pop() {
        let page = list_target_pages(config, executor, &WorkspaceTarget::Relative(next))?;
        let items = match page {
            ListingPage::Items(items) => items,
            // An absent managed directory is empty evidence, not a failure.
            ListingPage::Rejected(error) if error.code() == "document_not_found" => continue,
            ListingPage::Rejected(error) => return Err(error),
        };
        for item in items {
            let WorkspaceTarget::Relative(path) = item.target().clone() else {
                continue;
            };
            match item.kind() {
                DocumentKind::File => out.push(path),
                DocumentKind::Directory => {
                    if !is_media_lifecycle_lane(&path) {
                        stack.push(path);
                    }
                }
            }
        }
    }
    Ok(())
}

/// Media lifecycle directories (stage / trash / delete-intents) hold pending or
/// dead bytes — mirrors the manifest walker's exclusions so a staged or trashed
/// name can never satisfy a committed attachment.
fn is_media_lifecycle_lane(path: &RelativeWorkspacePath) -> bool {
    let Some(name) = path.as_str().rsplit('/').next() else {
        return false;
    };
    matches!(
        name,
        STAGE_DIR_NAME | MEDIA_TRASH_DIR_NAME | MEDIA_DELETE_INTENT_DIR_NAME
    )
}
