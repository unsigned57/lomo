//! Git remote endpoint and credentials (HTTPS username/token only; no SSH in Stage 5).

use std::path::PathBuf;
use std::time::Duration;

use crate::error::validation;
use lomo_core::LomoError;

/// How the adapter opens the local Git object store.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum GitLocalMode {
    /// Open an existing on-disk `.git` directory or worktree in place (Direct workspace).
    ///
    /// Must never checkout/reset user files; object graph reads + CAS push only.
    OpenExisting { git_dir: PathBuf },
    /// App-private bare mirror (SAF). Rebuild deletes only this tree's objects/cache.
    AppPrivateBareMirror { mirror_dir: PathBuf },
}

/// HTTPS Git remote configuration (Stage 5: no SSH).
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct GitEndpoint {
    /// Remote URL (https only for production path; local bare path allowed for hermetic tests).
    remote_url: String,
    /// Branch / ref short name (e.g. `main`). Full ref is `refs/heads/{branch}`.
    branch: String,
    local: GitLocalMode,
}

impl GitEndpoint {
    /// Builds and validates an endpoint.
    ///
    /// # Errors
    ///
    /// Validation when URL/branch empty, SSH URL, or path missing.
    pub fn parse(
        remote_url: impl Into<String>,
        branch: impl Into<String>,
        local: GitLocalMode,
    ) -> Result<Self, LomoError> {
        let remote_url = remote_url.into().trim().to_owned();
        let branch = branch.into().trim().to_owned();
        if remote_url.is_empty() {
            return Err(validation(
                "git_remote_url_empty",
                "git remote url must be non-empty",
            ));
        }
        if branch.is_empty() || branch.contains('/') || branch.contains('\\') {
            return Err(validation(
                "git_branch_invalid",
                "git branch must be a non-empty single path segment",
            ));
        }
        if remote_url.starts_with("ssh://")
            || remote_url.starts_with("git@")
            || remote_url.contains("://git@")
        {
            return Err(validation(
                "git_ssh_not_supported",
                "stage 5 git adapter supports https (and local bare paths for hermetic tests) only",
            ));
        }
        match &local {
            GitLocalMode::OpenExisting { git_dir } => {
                if !git_dir.exists() {
                    return Err(validation(
                        "git_local_missing",
                        "open-existing git directory does not exist",
                    ));
                }
            }
            GitLocalMode::AppPrivateBareMirror { mirror_dir } => {
                if mirror_dir.as_os_str().is_empty() {
                    return Err(validation(
                        "git_mirror_path_empty",
                        "app-private bare mirror path must be non-empty",
                    ));
                }
            }
        }
        Ok(Self {
            remote_url,
            branch,
            local,
        })
    }

    #[must_use]
    pub fn remote_url(&self) -> &str {
        &self.remote_url
    }

    #[must_use]
    pub fn branch(&self) -> &str {
        &self.branch
    }

    #[must_use]
    pub fn branch_ref(&self) -> String {
        format!("refs/heads/{}", self.branch)
    }

    #[must_use]
    pub const fn local(&self) -> &GitLocalMode {
        &self.local
    }
}

/// Ephemeral HTTPS username + token. Never place these in diagnostics or durable state.
#[derive(Clone)]
pub struct GitCredentials {
    username: String,
    token: String,
}

impl GitCredentials {
    /// Builds credentials (token may be empty for local bare remotes).
    ///
    /// # Errors
    ///
    /// Validation when username is empty while token is non-empty.
    pub fn new(username: impl Into<String>, token: impl Into<String>) -> Result<Self, LomoError> {
        let username = username.into();
        let token = token.into();
        if username.is_empty() && !token.is_empty() {
            return Err(validation(
                "git_credentials_username_empty",
                "git credentials require a username when a token is supplied",
            ));
        }
        Ok(Self { username, token })
    }

    /// Anonymous / no-auth credentials (local bare path remotes).
    #[must_use]
    pub const fn anonymous() -> Self {
        Self {
            username: String::new(),
            token: String::new(),
        }
    }

    #[must_use]
    pub fn username(&self) -> &str {
        &self.username
    }

    #[must_use]
    pub fn token(&self) -> &str {
        &self.token
    }

    #[must_use]
    pub const fn has_secret(&self) -> bool {
        !self.token.is_empty()
    }
}

impl std::fmt::Debug for GitCredentials {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("GitCredentials")
            .field("username", &"<redacted>")
            .field("token", &"<redacted>")
            .finish()
    }
}

/// Object-byte source for `EnsurePresent` publishes (workspace path → bytes).
pub trait GitObjectSource {
    /// Loads full object bytes for a workspace-relative path.
    ///
    /// # Errors
    ///
    /// Validation when the path is unknown or digest mismatches.
    fn load_bytes(
        &self,
        path: &lomo_sync::SyncPath,
        expected_digest: &lomo_sync::ContentDigest,
    ) -> Result<Vec<u8>, LomoError>;
}

/// Workspace-rooted object source for production composition (Direct path bytes).
///
/// Digest is verified against the intent before publish. Missing path fails closed.
/// Never invents bodies. Used by `lomo-sync` composed Git cycles only.
#[derive(Clone, Debug)]
pub struct WorkspaceFileGitObjectSource {
    workspace_root: PathBuf,
}

impl WorkspaceFileGitObjectSource {
    /// Builds a workspace-rooted object source.
    #[must_use]
    pub fn new(workspace_root: impl Into<PathBuf>) -> Self {
        Self {
            workspace_root: workspace_root.into(),
        }
    }
}

impl GitObjectSource for WorkspaceFileGitObjectSource {
    fn load_bytes(
        &self,
        path: &lomo_sync::SyncPath,
        expected_digest: &lomo_sync::ContentDigest,
    ) -> Result<Vec<u8>, LomoError> {
        use sha2::{Digest, Sha256};
        let absolute = self.workspace_root.join(path.as_str());
        let bytes = std::fs::read(&absolute).map_err(|err| {
            validation(
                "git_workspace_object_source_missing",
                &format!(
                    "git workspace object source cannot read {}: {err}",
                    path.as_str()
                ),
            )
        })?;
        let digest = format!("{:x}", Sha256::digest(&bytes));
        if digest != expected_digest.as_str() {
            return Err(validation(
                "git_workspace_object_source_digest_mismatch",
                "git workspace object source digest does not match the ensure-present intent",
            ));
        }
        Ok(bytes)
    }
}

/// In-memory object source for hermetic contracts.
#[derive(Clone, Debug, Default)]
pub struct MapGitObjectSource {
    pub objects: std::collections::BTreeMap<String, Vec<u8>>,
}

impl GitObjectSource for MapGitObjectSource {
    fn load_bytes(
        &self,
        path: &lomo_sync::SyncPath,
        expected_digest: &lomo_sync::ContentDigest,
    ) -> Result<Vec<u8>, LomoError> {
        use sha2::{Digest, Sha256};
        let bytes = self.objects.get(path.as_str()).ok_or_else(|| {
            validation(
                "git_object_source_missing",
                "git object source has no bytes for the ensure-present path",
            )
        })?;
        let digest = format!("{:x}", Sha256::digest(bytes));
        if digest != expected_digest.as_str() {
            return Err(validation(
                "git_object_source_digest_mismatch",
                "git object source digest does not match the ensure-present intent",
            ));
        }
        Ok(bytes.clone())
    }
}

/// Connect parameters for the map-source constructor used by hermetic tests.
#[derive(Clone, Debug)]
pub struct MapGitConnectParams<'a> {
    pub remote_url: &'a str,
    pub branch: &'a str,
    pub local: GitLocalMode,
    pub credentials: GitCredentials,
    pub objects: MapGitObjectSource,
    pub timeout: Duration,
    /// Author identity for commits created by publish (never a secret).
    pub author_name: &'a str,
    pub author_email: &'a str,
}
