use std::path::{Path, PathBuf};

use crate::error::TuiError;

/// Device-private XDG locations for one TUI process.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RuntimePaths {
    pub config_dir: PathBuf,
    pub state_dir: PathBuf,
    pub cache_dir: PathBuf,
    pub runtime_dir: PathBuf,
    pub exchange_dir: PathBuf,
    pub drafts_dir: PathBuf,
    /// First-run notes root when `config.toml` is absent: `$HOME/Notes`, else `$XDG_DATA_HOME/lomo/notes`.
    pub default_workspace: Option<PathBuf>,
}

/// Injected environment snapshot. Tests pass literals; production reads process env.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct EnvLookup<'a> {
    pub home: Option<&'a str>,
    pub config: Option<&'a str>,
    pub state: Option<&'a str>,
    pub cache: Option<&'a str>,
    pub runtime: Option<&'a str>,
    pub data: Option<&'a str>,
}

/// Resolves `$XDG_*_HOME/lomo` with documented fallbacks. Runtime dir has no fallback.
///
/// # Errors
/// Missing `$HOME` when an XDG base is unset, or missing `$XDG_RUNTIME_DIR`.
pub fn resolve_paths(env: EnvLookup<'_>) -> Result<RuntimePaths, TuiError> {
    let config_dir = lomo_dir(env.config, env.home, ".config")?;
    let state_dir = lomo_dir(env.state, env.home, ".local/state")?;
    let cache_dir = lomo_dir(env.cache, env.home, ".cache")?;
    let runtime_dir = match env.runtime {
        Some(dir) if !dir.is_empty() => Path::new(dir).join("lomo"),
        Some(_) | None => return Err(TuiError::MissingRuntimeDir),
    };
    Ok(RuntimePaths {
        config_dir,
        drafts_dir: state_dir.join("drafts"),
        exchange_dir: state_dir.join("exchange"),
        state_dir,
        cache_dir,
        runtime_dir,
        default_workspace: first_run_workspace(env),
    })
}

fn first_run_workspace(env: EnvLookup<'_>) -> Option<PathBuf> {
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

fn lomo_dir(xdg: Option<&str>, home: Option<&str>, fallback: &str) -> Result<PathBuf, TuiError> {
    if let Some(base) = xdg.filter(|value| !value.is_empty()) {
        return Ok(Path::new(base).join("lomo"));
    }
    let home = home
        .filter(|value| !value.is_empty())
        .ok_or_else(|| TuiError::config("HOME is required when an XDG base directory is unset"))?;
    Ok(Path::new(home).join(fallback).join("lomo"))
}
