//! Bare-mirror helpers: open/init app-private mirror; rebuild deletes only mirror objects/cache.

use std::fs;
use std::path::Path;

use git2::{Repository, RepositoryInitOptions};

use crate::endpoint::GitLocalMode;
use crate::error::{from_git2, storage, validation};
use lomo_core::LomoError;

/// Opens or initializes the local repository according to [`GitLocalMode`].
///
/// Never checkout/resets a user worktree. For `OpenExisting`, opens the path as-is.
/// For `AppPrivateBareMirror`, initializes a bare repo if missing.
///
/// # Errors
///
/// Validation / storage / git2 boundary errors.
pub fn open_local_repository(mode: &GitLocalMode) -> Result<Repository, LomoError> {
    match mode {
        GitLocalMode::OpenExisting { git_dir } => {
            Repository::open(git_dir).map_err(|error| from_git2("git_open_failed", &error))
        }
        GitLocalMode::AppPrivateBareMirror { mirror_dir } => {
            if mirror_dir.exists() {
                open_or_recover_bare_mirror(mirror_dir)
            } else {
                init_bare_mirror(mirror_dir)
            }
        }
    }
}

/// Initializes a fresh bare mirror (creating the parent chain when needed).
fn init_bare_mirror(mirror_dir: &Path) -> Result<Repository, LomoError> {
    if let Some(parent) = mirror_dir.parent() {
        fs::create_dir_all(parent).map_err(|error| {
            storage(
                "git_mirror_parent_create_failed",
                &format!("failed to create bare mirror parent: {error}"),
            )
        })?;
    }
    let mut opts = RepositoryInitOptions::new();
    opts.bare(true);
    opts.initial_head("main");
    Repository::init_opts(mirror_dir, &opts)
        .map_err(|error| from_git2("git_init_bare_failed", &error))
}

/// Opens an existing mirror; only a corrupt/absent repository is quarantined and rebuilt once.
///
/// The mirror is a rebuildable cache: permission/filesystem failures propagate unchanged, while a
/// corrupt or half-initialized tree is moved aside intact (evidence preserved at
/// `{mirror}.corrupt-{epoch_ms}[-{n}]`) and re-initialized exactly once. Quarantine evidence is
/// bounded to [`MAX_MIRROR_QUARANTINE_DIRS`] sibling directories — the newest are kept, older
/// corrupt trees are pruned so repeated corruption cannot grow unbounded.
fn open_or_recover_bare_mirror(mirror_dir: &Path) -> Result<Repository, LomoError> {
    match Repository::open_bare(mirror_dir) {
        Ok(repo) => Ok(repo),
        Err(error) if is_recoverable_mirror_corruption(&error) => {
            quarantine_corrupt_mirror(mirror_dir)?;
            init_bare_mirror(mirror_dir)
        }
        Err(error) => Err(from_git2("git_open_bare_failed", &error)),
    }
}

/// Cap on retained `{mirror}.corrupt-*` evidence trees (newest survive; older are pruned).
const MAX_MIRROR_QUARANTINE_DIRS: usize = 4;

/// Moves the corrupt mirror aside into a unique `{mirror}.corrupt-{epoch_ms}[-{n}]` sibling,
/// then prunes the oldest corrupt trees beyond [`MAX_MIRROR_QUARANTINE_DIRS`].
fn quarantine_corrupt_mirror(mirror_dir: &Path) -> Result<(), LomoError> {
    let epoch_ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(|error| storage("git_clock_failed", &error.to_string()))?
        .as_millis();
    let base_name = mirror_dir
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("mirror");
    // Same-millisecond corruptions would collide on `corrupt-{epoch_ms}`; the `-{n}` suffix
    // keeps every quarantined tree distinct so evidence is never silently overwritten.
    let mut suffix = 0u32;
    loop {
        let quarantine = mirror_dir.with_file_name(if suffix == 0 {
            format!("{base_name}.corrupt-{epoch_ms}")
        } else {
            format!("{base_name}.corrupt-{epoch_ms}-{suffix}")
        });
        if quarantine.exists() {
            suffix += 1;
            continue;
        }
        fs::rename(mirror_dir, &quarantine).map_err(|error| {
            storage(
                "git_mirror_quarantine_failed",
                &format!(
                    "failed to quarantine corrupt mirror to {}: {error}",
                    quarantine.display()
                ),
            )
        })?;
        break;
    }
    prune_quarantine_evidence(mirror_dir, base_name);
    Ok(())
}

/// Keeps only the newest [`MAX_MIRROR_QUARANTINE_DIRS`] `{name}.corrupt-*` siblings.
fn prune_quarantine_evidence(mirror_dir: &Path, base_name: &str) {
    let Some(parent) = mirror_dir.parent() else {
        return;
    };
    let prefix = format!("{base_name}.corrupt-");
    let mut quarantines: Vec<std::path::PathBuf> = match fs::read_dir(parent) {
        Ok(entries) => entries
            .filter_map(|entry| {
                // behavior-contract: silent-result-ok: an unreadable directory entry is
                // evidence we cannot bound — skipping it never fails the already-quarantined
                // rebuild, and a prune failure must never fail open.
                entry.map_or(None, |entry| Some(entry.path()))
            })
            .filter(|path| {
                path.file_name()
                    .and_then(|name| name.to_str())
                    .is_some_and(|name| name.starts_with(&prefix))
            })
            .collect(),
        // behavior-contract: silent-result-ok: evidence prune is best-effort; the corrupt tree
        // is already quarantined and the mirror rebuilt — a listing failure must not fail open.
        Err(_) => return,
    };
    // Timestamp-prefixed names sort chronologically; prune the oldest beyond the bound.
    quarantines.sort();
    let excess = quarantines.len().saturating_sub(MAX_MIRROR_QUARANTINE_DIRS);
    for stale in quarantines.into_iter().take(excess) {
        // behavior-contract: silent-result-ok: stale evidence removal is best-effort; a prune
        // failure leaves extra evidence but never corrupts the rebuilt mirror.
        drop(fs::remove_dir_all(&stale));
    }
}

/// Corruption/absent markers that justify a quarantine+rebuild of the rebuildable mirror cache.
fn is_recoverable_mirror_corruption(error: &git2::Error) -> bool {
    error.code() == git2::ErrorCode::NotFound || error.class() == git2::ErrorClass::Repository
}

/// Rebuilds an app-private bare mirror by deleting the mirror directory and re-init bare.
///
/// **Never** deletes user workspace files or remote content — only the app-private path.
///
/// # Errors
///
/// Validation when mode is not an app-private mirror; storage/git2 on failure.
pub fn rebuild_app_private_mirror(mode: &GitLocalMode) -> Result<Repository, LomoError> {
    let GitLocalMode::AppPrivateBareMirror { mirror_dir } = mode else {
        return Err(validation(
            "git_rebuild_not_app_private",
            "rebuild local mirror only applies to app-private bare mirrors",
        ));
    };
    if mirror_dir.exists() {
        // Safety: only remove under the provided mirror path (caller-owned app-private).
        fs::remove_dir_all(mirror_dir).map_err(|error| {
            storage(
                "git_mirror_remove_failed",
                &format!("failed to remove app-private bare mirror: {error}"),
            )
        })?;
    }
    open_local_repository(mode)
}

/// Returns true when `path` looks like it is inside an app-private mirror root (prefix check).
#[must_use]
pub fn path_is_under(root: &Path, candidate: &Path) -> bool {
    candidate.starts_with(root)
}
