//! Git remote adapter (`lomo-git`) — sole production-graph `git2` owner.
//!
//! Sole crate allowed to depend on `git2` / vendored libgit2 for production-graph sync.
//! Implements the public [`lomo_sync::RemoteSyncPort`] only: compiles path intents into
//! tree/commit + non-force CAS ref push (`WholeBatchRef`). Does **not** own direction, conflict,
//! baseline, tombstone, or retry policy (`lomo-sync` remains the sole planner).
//!
//! Production composition: `lomo-native` constructs this adapter at the conversion edge
//! (`connect_workspace_git`) and injects it into
//! [`lomo_sync::run_composed_sync_cycle_with_remote_port`]. `lomo-sync` production must **not**
//! depend on this crate (planner stays provider-neutral; Git is a `RemoteSyncPort` only).
//!
//! Never force-pushes, never checkout/resets user worktrees, never writes user files except via
//! the unified store/workspace expected-revision path (this adapter only mutates Git objects/refs).

#![deny(unsafe_code)]

mod adapter;
mod endpoint;
mod error;
mod lock;
mod mirror;
mod redaction;

pub use adapter::{GitAdapter, connect_map_git_source, connect_workspace_git};
pub use endpoint::{
    GitCredentials, GitEndpoint, GitLocalMode, GitObjectSource, GitObjectStream,
    MapGitConnectParams, MapGitObjectSource, WorkspaceFileGitObjectSource, validate_git_remote_url,
};
pub use error::{
    authentication as git_authentication, busy as git_busy, conflict as git_conflict, from_git2,
    network as git_network, permission as git_permission, storage as git_storage,
    validation as git_validation,
};
pub use lock::{
    DEFAULT_STALE_LOCK_THRESHOLD, LockReclaimOutcome, ensure_index_lock_clear, process_alive,
    try_reclaim_stale_index_lock, write_index_lock,
};
pub use mirror::{open_local_repository, path_is_under, rebuild_app_private_mirror};
pub use redaction::redact_diagnostic;

/// Crate package identity for architecture ownership locks.
pub const GIT_CRATE_NAME: &str = "lomo-git";

/// Owner identity document for stage-5 ownership locks.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GitOwnerIdentity {
    /// Package name of the git adapter crate.
    pub crate_name: &'static str,
}

impl GitOwnerIdentity {
    /// Returns the shipped dark-owner identity.
    #[must_use]
    pub const fn current() -> Self {
        Self {
            crate_name: GIT_CRATE_NAME,
        }
    }
}
