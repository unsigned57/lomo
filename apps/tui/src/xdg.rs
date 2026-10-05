use std::path::{Path, PathBuf};

use crate::error::TuiError;

/// Device-private locations for one TUI process.
///
/// Bases follow the host convention: XDG on Linux/Unix, `~/Library` on macOS,
/// `%APPDATA%` / `%LOCALAPPDATA%` on Windows. Explicit `XDG_*_HOME`-style
/// overrides always resolve as `<dir>/lomo` on every platform.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RuntimePaths {
    pub config_dir: PathBuf,
    pub state_dir: PathBuf,
    pub cache_dir: PathBuf,
    pub runtime_dir: PathBuf,
    pub exchange_dir: PathBuf,
    pub drafts_dir: PathBuf,
    /// First-run notes root when `config.toml` is absent: `~/Notes`, else the data base.
    pub default_workspace: Option<PathBuf>,
    /// User home for `~` expansion in workspace paths; absent on Windows when
    /// the profile directory is unset.
    pub home_dir: Option<PathBuf>,
}

/// Injected environment snapshot. Tests pass literals; production reads process env.
/// `appdata`/`localappdata` are only consulted on Windows; `home` is `HOME` on
/// Unix and `USERPROFILE` on Windows.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct EnvLookup<'a> {
    pub home: Option<&'a str>,
    pub config: Option<&'a str>,
    pub state: Option<&'a str>,
    pub cache: Option<&'a str>,
    pub runtime: Option<&'a str>,
    pub data: Option<&'a str>,
    pub appdata: Option<&'a str>,
    pub localappdata: Option<&'a str>,
}

/// Resolves `lomo` directories with documented per-OS fallbacks.
///
/// Explicit `XDG_*` values follow the base-directory spec exactly: an empty or
/// whitespace-only value means unset, and a relative value is invalid and is
/// ignored with a warning trace — never silently anchored to the process cwd.
///
/// # Errors
/// Missing home/profile base when a directory is unset. On Linux/Unix a missing
/// `$XDG_RUNTIME_DIR` fails closed (`MissingRuntimeDir`); macOS and Windows fall
/// back to a per-user runtime directory because neither OS provides one.
pub fn resolve_paths(env: EnvLookup<'_>) -> Result<RuntimePaths, TuiError> {
    let config_dir = dir_or(env.config, "XDG_CONFIG_HOME", || default_config_dir(&env))?;
    let state_dir = dir_or(env.state, "XDG_STATE_HOME", || default_state_dir(&env))?;
    let cache_dir = dir_or(env.cache, "XDG_CACHE_HOME", || default_cache_dir(&env))?;
    let runtime_dir = runtime_dir(&env)?;
    Ok(RuntimePaths {
        config_dir,
        drafts_dir: state_dir.join("drafts"),
        exchange_dir: state_dir.join("exchange"),
        state_dir,
        cache_dir,
        runtime_dir,
        default_workspace: first_run_workspace(&env),
        home_dir: env.home.and_then(|value| absolute_env_dir("HOME", value)),
    })
}

// ---- Linux / generic Unix: XDG base directories ----

#[cfg(all(unix, not(target_os = "macos")))]
fn default_config_dir(env: &EnvLookup<'_>) -> Result<PathBuf, TuiError> {
    home_base(env, ".config/lomo")
}

#[cfg(all(unix, not(target_os = "macos")))]
fn default_state_dir(env: &EnvLookup<'_>) -> Result<PathBuf, TuiError> {
    home_base(env, ".local/state/lomo")
}

#[cfg(all(unix, not(target_os = "macos")))]
fn default_cache_dir(env: &EnvLookup<'_>) -> Result<PathBuf, TuiError> {
    home_base(env, ".cache/lomo")
}

/// `$XDG_RUNTIME_DIR` has no generic fallback on Linux/Unix.
#[cfg(all(unix, not(target_os = "macos")))]
fn runtime_dir(env: &EnvLookup<'_>) -> Result<PathBuf, TuiError> {
    env.runtime
        .and_then(|dir| absolute_env_dir("XDG_RUNTIME_DIR", dir))
        .map_or(Err(TuiError::MissingRuntimeDir), |dir| Ok(dir.join("lomo")))
}

// ---- macOS: ~/Library conventions ----

/// macOS `~/Library/Application Support/lomo` for config, state, and data.
#[cfg(target_os = "macos")]
fn app_support(env: &EnvLookup<'_>) -> Result<PathBuf, TuiError> {
    home_base(env, "Library/Application Support/lomo")
}

#[cfg(target_os = "macos")]
fn default_config_dir(env: &EnvLookup<'_>) -> Result<PathBuf, TuiError> {
    app_support(env)
}

#[cfg(target_os = "macos")]
fn default_state_dir(env: &EnvLookup<'_>) -> Result<PathBuf, TuiError> {
    app_support(env)
}

#[cfg(target_os = "macos")]
fn default_cache_dir(env: &EnvLookup<'_>) -> Result<PathBuf, TuiError> {
    home_base(env, "Library/Caches/lomo")
}

/// macOS has no runtime dir; `$XDG_RUNTIME_DIR` wins when set, else a private
/// `run` directory under the app-support tree.
#[cfg(target_os = "macos")]
fn runtime_dir(env: &EnvLookup<'_>) -> Result<PathBuf, TuiError> {
    env.runtime
        .and_then(|dir| absolute_env_dir("XDG_RUNTIME_DIR", dir))
        .map_or_else(
            || app_support(env).map(|base| base.join("run")),
            |dir| Ok(dir.join("lomo")),
        )
}

// ---- Windows: %APPDATA% roaming config, %LOCALAPPDATA% local state ----

#[cfg(windows)]
fn default_config_dir(env: &EnvLookup<'_>) -> Result<PathBuf, TuiError> {
    Ok(env
        .appdata
        .and_then(|value| absolute_env_dir("APPDATA", value))
        .ok_or_else(|| TuiError::config("APPDATA is required on Windows"))?
        .join("lomo"))
}

#[cfg(windows)]
fn default_state_dir(env: &EnvLookup<'_>) -> Result<PathBuf, TuiError> {
    Ok(local_base(env)?.join("lomo"))
}

#[cfg(windows)]
fn default_cache_dir(env: &EnvLookup<'_>) -> Result<PathBuf, TuiError> {
    Ok(local_base(env)?.join("lomo").join("cache"))
}

/// Windows has no runtime dir; `run` under the local app-data tree is private
/// to the user profile.
#[cfg(windows)]
fn runtime_dir(env: &EnvLookup<'_>) -> Result<PathBuf, TuiError> {
    env.runtime
        .and_then(|dir| absolute_env_dir("XDG_RUNTIME_DIR", dir))
        .map_or_else(
            || local_base(env).map(|base| base.join("lomo").join("run")),
            |dir| Ok(dir.join("lomo")),
        )
}

#[cfg(windows)]
fn local_base(env: &EnvLookup<'_>) -> Result<PathBuf, TuiError> {
    env.localappdata
        .and_then(|value| absolute_env_dir("LOCALAPPDATA", value))
        .ok_or_else(|| TuiError::config("LOCALAPPDATA is required on Windows"))
}

// ---- Fallback for targets outside the three supported families ----

#[cfg(not(any(unix, windows)))]
fn default_config_dir(env: &EnvLookup<'_>) -> Result<PathBuf, TuiError> {
    home_base(env, ".config/lomo")
}

#[cfg(not(any(unix, windows)))]
fn default_state_dir(env: &EnvLookup<'_>) -> Result<PathBuf, TuiError> {
    home_base(env, ".local/state/lomo")
}

#[cfg(not(any(unix, windows)))]
fn default_cache_dir(env: &EnvLookup<'_>) -> Result<PathBuf, TuiError> {
    home_base(env, ".cache/lomo")
}

#[cfg(not(any(unix, windows)))]
fn runtime_dir(env: &EnvLookup<'_>) -> Result<PathBuf, TuiError> {
    env.runtime
        .and_then(|dir| absolute_env_dir("XDG_RUNTIME_DIR", dir))
        .map_or(Err(TuiError::MissingRuntimeDir), |dir| Ok(dir.join("lomo")))
}

#[cfg(not(windows))]
fn home_base(env: &EnvLookup<'_>, suffix: &str) -> Result<PathBuf, TuiError> {
    let home = env
        .home
        .and_then(|value| absolute_env_dir("HOME", value))
        .ok_or_else(|| TuiError::config("HOME is required when a base directory is unset"))?;
    Ok(home.join(suffix))
}

fn first_run_workspace(env: &EnvLookup<'_>) -> Option<PathBuf> {
    if let Some(home) = env.home.and_then(|value| absolute_env_dir("HOME", value)) {
        return Some(home.join("Notes"));
    }
    env.data
        .and_then(|value| absolute_env_dir("XDG_DATA_HOME", value))
        .map(|data| data.join("lomo").join("notes"))
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

/// Validates one explicit environment directory value. Whitespace-only values
/// mean unset; non-absolute values violate the base-directory spec and are
/// ignored with a trace — both leave the path's platform default in charge,
/// never a cwd-relative or literal-whitespace directory.
fn absolute_env_dir(name: &str, value: &str) -> Option<PathBuf> {
    if value.trim().is_empty() {
        return None;
    }
    let path = Path::new(value);
    if !path.is_absolute() {
        tracing::warn!("{name}={value} is not absolute; ignoring it per the base-directory spec");
        return None;
    }
    Some(path.to_path_buf())
}

/// `<explicit>/lomo` when an override is set, else the lazily resolved platform
/// default — explicit values must not require the default's inputs.
fn dir_or(
    explicit: Option<&str>,
    name: &'static str,
    default: impl FnOnce() -> Result<PathBuf, TuiError>,
) -> Result<PathBuf, TuiError> {
    if let Some(dir) = explicit.and_then(|value| absolute_env_dir(name, value)) {
        return Ok(dir.join("lomo"));
    }
    default()
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
