//! Git remote endpoint and credentials (HTTPS username/token only; no SSH in Stage 5).

use std::path::PathBuf;
use std::time::Duration;

use crate::error::validation;
use lomo_core::LomoError;

/// Workspace objects embedded into Git trees stay under the sync object bound.
const MAX_GIT_OBJECT_BYTES: u64 = 32 * 1_048_576;

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
        validate_git_remote_url(&remote_url)?;
        if !is_valid_branch_name(&branch) {
            return Err(validation(
                "git_branch_invalid",
                "git branch must be a non-empty single ref path segment",
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

/// Validates a remote URL without building an endpoint (settings precheck shares this rule).
///
/// # Errors
///
/// Validation when the URL is empty, SSH-shaped, carries userinfo, or uses an unsupported scheme.
pub fn validate_git_remote_url(remote_url: &str) -> Result<(), LomoError> {
    let remote_url = remote_url.trim();
    if remote_url.is_empty() {
        return Err(validation(
            "git_remote_url_empty",
            "git remote url must be non-empty",
        ));
    }
    if remote_url.starts_with("ssh://")
        || remote_url.starts_with("git@")
        || remote_url.contains("://git@")
        || is_scp_like_ssh(remote_url)
    {
        return Err(validation(
            "git_ssh_not_supported",
            "stage 5 git adapter supports https (and local bare paths for hermetic tests) only",
        ));
    }
    validate_remote_url_shape(remote_url)
}

/// `user@host:path` SCP-like syntax is SSH transport — rejected with the SSH error code.
fn is_scp_like_ssh(remote_url: &str) -> bool {
    if remote_url.contains("://") {
        return false;
    }
    let Some((before_colon, _)) = remote_url.split_once(':') else {
        return false;
    };
    before_colon.contains('@') && !before_colon.contains('/')
}

/// Supported remote schemes: `https` (production) and `file` (hermetic local remotes).
/// Plain paths carry no scheme marker. URL userinfo (`user:pass@host`) is always rejected so
/// credentials can never ride inside a persisted endpoint.
fn validate_remote_url_shape(remote_url: &str) -> Result<(), LomoError> {
    let Some((scheme, rest)) = remote_url.split_once("://") else {
        return Ok(());
    };
    let authority = match rest.split_once('/') {
        Some((authority, _)) => authority,
        None => rest,
    };
    if authority.contains('@') {
        return Err(validation(
            "git_url_userinfo_rejected",
            "git remote url must not embed userinfo credentials; configure them via the credential store",
        ));
    }
    if scheme != "https" && scheme != "file" {
        return Err(validation(
            "git_scheme_unsupported",
            "git remote url scheme must be https or file",
        ));
    }
    Ok(())
}

/// `git check-ref-format`-compatible single-segment branch name (the app uses short branch names
/// only; slashes stay rejected).
fn is_valid_branch_name(branch: &str) -> bool {
    !branch.is_empty()
        && !branch.contains('/')
        && !branch.contains('\\')
        && !branch.starts_with('-')
        && !branch.starts_with('.')
        && !ends_with_lock_suffix(branch)
        && !branch.ends_with('.')
        && !branch.contains("..")
        && !branch.contains("@{")
        && !branch
            .chars()
            .any(|ch| ch.is_control() || "~^:?*[".contains(ch))
}

/// Git forbids a `.lock` ref component suffix; the check is ASCII case-insensitive.
fn ends_with_lock_suffix(branch: &str) -> bool {
    branch
        .get(branch.len().saturating_sub(".lock".len())..)
        .is_some_and(|tail| tail.eq_ignore_ascii_case(".lock"))
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

/// One opened workspace object as a bounded byte stream.
///
/// `len` is measured on the opened descriptor before any byte is read, so the adapter can reject
/// over-budget objects pre-read and size the ODB stream writer exactly.
pub struct GitObjectStream {
    /// Object length in bytes measured at open time.
    pub len: u64,
    /// Bounded byte stream for the object body.
    pub reader: Box<dyn std::io::Read + Send>,
}

/// Object-byte source for `EnsurePresent` publishes (workspace path → stream).
///
/// The adapter owns digest verification while streaming into the ODB; sources only open a bounded,
/// length-measured stream. Missing paths fail closed; sources never invent bodies.
pub trait GitObjectSource {
    /// Opens a bounded, length-measured byte stream for a workspace-relative path.
    ///
    /// # Errors
    ///
    /// Validation when the path is unknown or unreadable.
    fn open_object(&self, path: &lomo_sync::SyncPath) -> Result<GitObjectStream, LomoError>;
}

/// Workspace-rooted object source for production composition (Direct path bytes).
///
/// Length is measured on the opened file descriptor (not the path) so a concurrently grown file
/// is caught by the declared-length/digest checks instead of silently truncating. Used by
/// `lomo-sync` composed Git cycles only.
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
    fn open_object(&self, path: &lomo_sync::SyncPath) -> Result<GitObjectStream, LomoError> {
        use std::io::Read;
        let absolute = self.workspace_root.join(path.as_str());
        let file = std::fs::File::open(&absolute).map_err(|error| {
            validation(
                "git_workspace_object_source_missing",
                &format!(
                    "git workspace object source cannot open {}: {error}",
                    path.as_str()
                ),
            )
        })?;
        let len = file
            .metadata()
            .map_err(|error| {
                validation(
                    "git_workspace_object_source_metadata_failed",
                    &format!(
                        "git workspace object source cannot stat {}: {error}",
                        path.as_str()
                    ),
                )
            })?
            .len();
        Ok(GitObjectStream {
            len,
            reader: Box::new(file.take(MAX_GIT_OBJECT_BYTES.saturating_add(1))),
        })
    }
}

/// In-memory object source for hermetic contracts.
#[derive(Clone, Debug, Default)]
pub struct MapGitObjectSource {
    pub objects: std::collections::BTreeMap<String, Vec<u8>>,
}

impl GitObjectSource for MapGitObjectSource {
    fn open_object(&self, path: &lomo_sync::SyncPath) -> Result<GitObjectStream, LomoError> {
        let bytes = self.objects.get(path.as_str()).ok_or_else(|| {
            validation(
                "git_object_source_missing",
                "git object source has no bytes for the ensure-present path",
            )
        })?;
        Ok(GitObjectStream {
            len: u64::try_from(bytes.len()).map_err(|error| {
                validation("git_object_source_len_overflow", &error.to_string())
            })?,
            reader: Box::new(std::io::Cursor::new(bytes.clone())),
        })
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
