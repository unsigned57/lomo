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
/// # Errors
/// Missing home/profile base when a directory is unset. On Linux/Unix a missing
/// `$XDG_RUNTIME_DIR` fails closed (`MissingRuntimeDir`); macOS and Windows fall
/// back to a per-user runtime directory because neither OS provides one.
pub fn resolve_paths(env: EnvLookup<'_>) -> Result<RuntimePaths, TuiError> {
    let config_dir = dir_or(env.config, || default_config_dir(&env))?;
    let state_dir = dir_or(env.state, || default_state_dir(&env))?;
    let cache_dir = dir_or(env.cache, || default_cache_dir(&env))?;
    let runtime_dir = runtime_dir(&env)?;
    Ok(RuntimePaths {
        config_dir,
        drafts_dir: state_dir.join("drafts"),
        exchange_dir: state_dir.join("exchange"),
        state_dir,
        cache_dir,
        runtime_dir,
        default_workspace: first_run_workspace(&env),
        home_dir: env
            .home
            .filter(|value| !value.is_empty())
            .map(PathBuf::from),
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
    match env.runtime {
        Some(dir) if !dir.is_empty() => Ok(Path::new(dir).join("lomo")),
        Some(_) | None => Err(TuiError::MissingRuntimeDir),
    }
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
    match env.runtime {
        Some(dir) if !dir.is_empty() => Ok(Path::new(dir).join("lomo")),
        Some(_) | None => Ok(app_support(env)?.join("run")),
    }
}

// ---- Windows: %APPDATA% roaming config, %LOCALAPPDATA% local state ----

#[cfg(windows)]
fn default_config_dir(env: &EnvLookup<'_>) -> Result<PathBuf, TuiError> {
    Ok(env
        .appdata
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
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
    match env.runtime {
        Some(dir) if !dir.is_empty() => Ok(Path::new(dir).join("lomo")),
        Some(_) | None => Ok(local_base(env)?.join("lomo").join("run")),
    }
}

#[cfg(windows)]
fn local_base(env: &EnvLookup<'_>) -> Result<PathBuf, TuiError> {
    env.localappdata
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
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
    match env.runtime {
        Some(dir) if !dir.is_empty() => Ok(Path::new(dir).join("lomo")),
        Some(_) | None => Err(TuiError::MissingRuntimeDir),
    }
}

#[cfg(not(windows))]
fn home_base(env: &EnvLookup<'_>, suffix: &str) -> Result<PathBuf, TuiError> {
    let home = env
        .home
        .filter(|value| !value.is_empty())
        .ok_or_else(|| TuiError::config("HOME is required when a base directory is unset"))?;
    Ok(Path::new(home).join(suffix))
}

fn first_run_workspace(env: &EnvLookup<'_>) -> Option<PathBuf> {
    if let Some(home) = env.home.filter(|value| !value.is_empty()) {
        return Some(Path::new(home).join("Notes"));
    }
    env.data
        .filter(|value| !value.is_empty())
        .map(|data| Path::new(data).join("lomo").join("notes"))
}

/// Reads a process environment value, treating empty as unset.
#[must_use]
pub fn env_nonempty(key: &str) -> Option<String> {
    match std::env::var(key) {
        Ok(value) if !value.is_empty() => Some(value),
        Ok(_) | Err(_) => None,
    }
}

/// `<explicit>/lomo` when an override is set, else the lazily resolved platform
/// default — explicit values must not require the default's inputs.
fn dir_or(
    explicit: Option<&str>,
    default: impl FnOnce() -> Result<PathBuf, TuiError>,
) -> Result<PathBuf, TuiError> {
    if let Some(dir) = explicit.filter(|value| !value.is_empty()) {
        return Ok(Path::new(dir).join("lomo"));
    }
    default()
}
