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
/// `{mirror}.corrupt-{epoch_ms}`) and re-initialized exactly once.
fn open_or_recover_bare_mirror(mirror_dir: &Path) -> Result<Repository, LomoError> {
    match Repository::open_bare(mirror_dir) {
        Ok(repo) => Ok(repo),
        Err(error) if is_recoverable_mirror_corruption(&error) => {
            let epoch_ms = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map_err(|error| storage("git_clock_failed", &error.to_string()))?
                .as_millis();
            let quarantine = mirror_dir.with_file_name(format!(
                "{}.corrupt-{epoch_ms}",
                mirror_dir
                    .file_name()
                    .and_then(|name| name.to_str())
                    .unwrap_or("mirror")
            ));
            fs::rename(mirror_dir, &quarantine).map_err(|error| {
                storage(
                    "git_mirror_quarantine_failed",
                    &format!(
                        "failed to quarantine corrupt mirror to {}: {error}",
                        quarantine.display()
                    ),
                )
            })?;
            init_bare_mirror(mirror_dir)
        }
        Err(error) => Err(from_git2("git_open_bare_failed", &error)),
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
