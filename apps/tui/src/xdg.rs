use std::path::{Path, PathBuf};

use crate::error::TuiError;

/// Directory suffix every lomo runtime path shares.
const APP_DIR: &str = "lomo";

/// Device-private locations for one TUI process.
///
/// Bases come from [`HostDirs`]: `dirs` resolves them from the host
/// conventions (XDG on Linux/Unix, `~/Library` on macOS, known folders on
/// Windows); `resolve_paths` then applies lomo's own `<base>/lomo` layout
/// and per-OS state/cache/runtime fallbacks.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RuntimePaths {
    pub config_dir: PathBuf,
    pub state_dir: PathBuf,
    pub cache_dir: PathBuf,
    pub runtime_dir: PathBuf,
    pub exchange_dir: PathBuf,
    /// TUI-private draft workspace — the capture journal and external-editor
    /// drafts. Deliberately *not* `state_dir/drafts`: that directory is the
    /// session's conflict-evidence store (`DraftStore`), whose `*.json` scan
    /// fails closed on anything it did not write.
    pub capture_dir: PathBuf,
    /// First-run notes root when `config.toml` is absent: `~/Notes`, else the data base.
    pub default_workspace: Option<PathBuf>,
    /// User home for `~` expansion in workspace paths; absent on Windows when
    /// the profile directory is unset.
    pub home_dir: Option<PathBuf>,
}

/// Resolved host directory bases — `dirs` output in production, literals in
/// tests.
///
/// Each `Option` mirrors the platform guarantee: `state` has no platform
/// default outside Linux/Unix, `runtime` only exists on Linux/Unix, and
/// `data_local` is the Windows local (non-roaming) profile base.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct HostDirs {
    /// `dirs::home_dir()` — the user profile root.
    pub home: Option<PathBuf>,
    /// `dirs::config_dir()` — XDG config, `~/Library/Application Support`, `%APPDATA%`.
    pub config: Option<PathBuf>,
    /// `dirs::state_dir()` — XDG state on Linux/Unix; `None` elsewhere.
    pub state: Option<PathBuf>,
    /// `dirs::cache_dir()` — XDG cache, `~/Library/Caches`, `%LOCALAPPDATA%`.
    pub cache: Option<PathBuf>,
    /// `dirs::runtime_dir()` — `$XDG_RUNTIME_DIR`; `None` on macOS/Windows.
    pub runtime: Option<PathBuf>,
    /// `dirs::data_dir()` — the first-run workspace fallback base.
    pub data: Option<PathBuf>,
    /// `dirs::data_local_dir()` — the Windows local-profile state base.
    pub data_local: Option<PathBuf>,
}

impl HostDirs {
    /// Resolve every base through `dirs`, which merges platform conventions:
    /// XDG base directories on Unix (empty and relative `XDG_*` values already
    /// mean "unset" there), `~/Library` on macOS, and the Windows known-folder
    /// API — a wrong or unset `HOME`/`APPDATA` env var can no longer steer
    /// resolution sideways.
    #[must_use]
    pub fn detect() -> Self {
        Self {
            home: dirs::home_dir(),
            config: dirs::config_dir(),
            state: dirs::state_dir(),
            cache: dirs::cache_dir(),
            runtime: dirs::runtime_dir(),
            data: dirs::data_dir(),
            data_local: dirs::data_local_dir(),
        }
    }
}

/// Resolves `lomo` directories from the host bases with documented per-OS
/// fallbacks.
///
/// Required bases (config, the platform state base, cache) fail closed when
/// absent or non-absolute — injected relative bases are rejected per the
/// base-directory spec instead of silently anchoring to the process cwd. On
/// Linux/Unix a missing runtime base fails closed (`MissingRuntimeDir`);
/// macOS and Windows fall back to a per-user runtime directory because
/// neither OS provides one.
///
/// # Errors
/// Missing or invalid required base; `MissingRuntimeDir` on Linux/Unix
/// without a runtime base.
pub fn resolve_paths(host: &HostDirs) -> Result<RuntimePaths, TuiError> {
    let config_dir =
        require(absolute_base("config", host.config.as_deref()), "config")?.join(APP_DIR);
    let state_dir = require(state_base(host), "state")?.join(APP_DIR);
    let cache_dir = cache_leaf(&require(
        absolute_base("cache", host.cache.as_deref()),
        "cache",
    )?);
    let runtime_dir = runtime_dir(host, &config_dir, &state_dir)?;
    let session_state = lomo_workspace::SessionStateLayout::for_state_dir(&state_dir);
    Ok(RuntimePaths {
        config_dir,
        capture_dir: session_state.capture,
        exchange_dir: session_state.exchange,
        state_dir,
        cache_dir,
        runtime_dir,
        default_workspace: first_run_workspace(host),
        home_dir: absolute_base("home", host.home.as_deref()),
    })
}

/// Required bases have no second chance: an absent or rejected base is a
/// configuration error, not a cwd-relative directory.
fn require(base: Option<PathBuf>, what: &str) -> Result<PathBuf, TuiError> {
    base.ok_or_else(|| TuiError::config(format!("{what} directory is unavailable on this host")))
}

/// The state base: XDG state on Linux/Unix, the config base on macOS
/// (`~/Library/Application Support` doubles as app state), the local profile
/// data base on Windows. A rejected base counts as unset and falls through.
#[cfg(all(unix, not(target_os = "macos")))]
fn state_base(host: &HostDirs) -> Option<PathBuf> {
    absolute_base("state", host.state.as_deref())
}

#[cfg(target_os = "macos")]
fn state_base(host: &HostDirs) -> Option<PathBuf> {
    absolute_base("state", host.state.as_deref())
        .or_else(|| absolute_base("config", host.config.as_deref()))
}

#[cfg(windows)]
fn state_base(host: &HostDirs) -> Option<PathBuf> {
    absolute_base("state", host.state.as_deref())
        .or_else(|| absolute_base("local data", host.data_local.as_deref()))
}

#[cfg(not(any(unix, windows)))]
fn state_base(host: &HostDirs) -> Option<PathBuf> {
    absolute_base("state", host.state.as_deref())
}

/// `lomo` on Unix/macOS; `lomo\cache` under the Windows local tree.
#[cfg(windows)]
fn cache_leaf(base: &Path) -> PathBuf {
    base.join(APP_DIR).join("cache")
}

#[cfg(not(windows))]
fn cache_leaf(base: &Path) -> PathBuf {
    base.join(APP_DIR)
}

/// `$XDG_RUNTIME_DIR` has no generic fallback on Linux/Unix.
#[cfg(all(unix, not(target_os = "macos")))]
fn runtime_dir(
    host: &HostDirs,
    _config_dir: &Path,
    _state_dir: &Path,
) -> Result<PathBuf, TuiError> {
    absolute_base("runtime", host.runtime.as_deref())
        .map(|base| base.join(APP_DIR))
        .ok_or(TuiError::MissingRuntimeDir)
}

/// macOS has no runtime dir; a private `run` directory under the app-support
/// tree stands in when the platform provides no runtime base.
#[cfg(target_os = "macos")]
fn runtime_dir(host: &HostDirs, config_dir: &Path, _state_dir: &Path) -> Result<PathBuf, TuiError> {
    Ok(absolute_base("runtime", host.runtime.as_deref())
        .map(|base| base.join(APP_DIR))
        .unwrap_or_else(|| config_dir.join("run")))
}

/// Windows has no runtime dir; `run` under the local app-data tree is private
/// to the user profile.
#[cfg(windows)]
fn runtime_dir(host: &HostDirs, _config_dir: &Path, state_dir: &Path) -> Result<PathBuf, TuiError> {
    Ok(absolute_base("runtime", host.runtime.as_deref())
        .map(|base| base.join(APP_DIR))
        .unwrap_or_else(|| state_dir.join("run")))
}

#[cfg(not(any(unix, windows)))]
fn runtime_dir(
    host: &HostDirs,
    _config_dir: &Path,
    _state_dir: &Path,
) -> Result<PathBuf, TuiError> {
    absolute_base("runtime", host.runtime.as_deref())
        .map(|base| base.join(APP_DIR))
        .ok_or(TuiError::MissingRuntimeDir)
}

fn first_run_workspace(host: &HostDirs) -> Option<PathBuf> {
    if let Some(home) = absolute_base("home", host.home.as_deref()) {
        return Some(home.join("Notes"));
    }
    absolute_base("data", host.data.as_deref()).map(|data| data.join(APP_DIR).join("notes"))
}

/// Reads a process environment value, treating empty or whitespace-only as
/// unset — a field the user "set" to nothing carries no configuration intent.
#[must_use]
pub fn env_nonempty(key: &str) -> Option<String> {
    match std::env::var(key) {
        Ok(value) if !value.trim().is_empty() => Some(value),
        Ok(_) | Err(_) => None,
    }
}

/// Validates one injected base directory. An empty or whitespace-only value
/// means unset; a non-absolute value violates the base-directory spec and is
/// rejected with a trace — never silently anchored to the process cwd.
fn absolute_base(name: &str, base: Option<&Path>) -> Option<PathBuf> {
    let path = base?;
    if path.as_os_str().to_string_lossy().trim().is_empty() {
        return None;
    }
    if path.is_absolute() {
        return Some(path.to_path_buf());
    }
    tracing::warn!(
        "{name} base {} is not absolute; ignoring it per the base-directory spec",
        path.display()
    );
    None
}

/// Name of the marker file under `state_dir` recording that first-run setup
/// minted config.toml (it holds the confirmed workspace path).
pub const INITIALIZED_MARKER: &str = "initialized";

/// The workspace recorded by the initialization marker.
///
/// A missing or empty marker is "not yet initialized"; an unreadable marker
/// is a state error — silently treating it as a fresh install would hide the
/// very deletion-vs-first-run distinction it exists to preserve.
///
/// # Errors
/// Marker read failures other than not-found.
pub fn initialized_workspace(paths: &RuntimePaths) -> Result<Option<PathBuf>, TuiError> {
    let raw = match std::fs::read_to_string(paths.state_dir.join(INITIALIZED_MARKER)) {
        Ok(raw) => raw,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Ok(None);
    }
    Ok(Some(PathBuf::from(trimmed)))
}

/// Records that first-run setup completed against `workspace`. The marker is
/// advisory state: a missing or unreadable file only means the next launch
/// cannot tell a deleted config from a fresh install.
///
/// # Errors
/// State directory creation or marker write failures.
pub fn mark_initialized(paths: &RuntimePaths, workspace: &Path) -> Result<(), TuiError> {
    crate::drafts::private_directory(&paths.state_dir)?;
    std::fs::write(
        paths.state_dir.join(INITIALIZED_MARKER),
        format!(
            "{}
",
            workspace.display()
        ),
    )?;
    Ok(())
}
