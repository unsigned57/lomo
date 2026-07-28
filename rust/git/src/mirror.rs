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
                Repository::open_bare(mirror_dir)
                    .map_err(|error| from_git2("git_open_bare_failed", &error))
            } else {
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
        }
    }
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
