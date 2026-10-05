use std::{
    fs,
    path::{Path, PathBuf},
    time::{Duration, SystemTime},
};

use anyhow::{Context, Result, bail};
use serde_json::{Value, json};

use crate::{util::remove_if_exists, workspace::Workspace};

/// Cargo keeps every artifact fingerprint it has ever produced; files untouched for longer than
/// this necessarily predate the active dependency/feature set.
const STALE_ARTIFACT_AGE: Duration = Duration::from_hours(24 * 7);

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CacheMode {
    Audit,
    Paths,
    Prune,
    Clean,
}

pub fn run_cache(workspace: &Workspace, mode: CacheMode) -> Result<Value> {
    match mode {
        CacheMode::Audit => audit(workspace),
        CacheMode::Paths => Ok(paths(workspace)),
        CacheMode::Prune => prune(workspace),
        CacheMode::Clean => clean(workspace),
    }
}

pub fn parse_mode(value: &str) -> Result<CacheMode> {
    match value {
        "audit" => Ok(CacheMode::Audit),
        "paths" => Ok(CacheMode::Paths),
        "prune" => Ok(CacheMode::Prune),
        "clean" => Ok(CacheMode::Clean),
        _ => bail!("cache mode must be `audit`, `paths`, `prune`, or `clean`, found `{value}`"),
    }
}

fn paths(workspace: &Workspace) -> Value {
    let lomo_output = workspace.lomo_output_dir();
    let paths: std::collections::BTreeMap<_, _> = [
        ("home", &workspace.kotlin_home),
        ("xdg_cache", &workspace.kotlin_cache),
        ("xdg_data", &workspace.kotlin_data),
        ("xdg_config", &workspace.kotlin_config),
        ("android_user_home", &workspace.android_home),
        ("kotlin_cli_cache", &workspace.kotlin_cli_cache),
        ("gradle_user_home", &workspace.gradle_home),
        ("cargo_home", &workspace.cargo_home),
        ("cargo_target", &workspace.rust_target),
        ("lomo_output", &lomo_output),
        ("cargo_tools", &workspace.tool_root),
        ("kotlin_build", &workspace.kotlin_build),
    ]
    .into_iter()
    .collect();
    json!({"paths": paths})
}

fn audit(workspace: &Workspace) -> Result<Value> {
    let mut entries = Vec::new();
    for relative in [
        ".cache",
        ".gradle",
        ".kotlin",
        ".kotlin-cli",
        ".android-sdk",
        "target",
        "apps/android/app/jniLibs",
        "apps/android/native-bindings/src",
    ] {
        let path = workspace.root.join(relative);
        if path.try_exists()? {
            entries
                .push(json!({"path": path, "state": "present", "bytes": directory_size(&path)?}));
        } else {
            entries.push(json!({"path": path, "state": "absent"}));
        }
    }
    Ok(json!({"entries": entries}))
}

fn prune(workspace: &Workspace) -> Result<Value> {
    let target = &workspace.rust_target;
    let lomo_output = workspace.lomo_output_dir();
    let instrumented = target.join("llvm-cov-target");
    let removed_instrumented = instrumented.try_exists()?;
    if removed_instrumented {
        crate::util::emit_stderr(format_args!("xtask: removing {}", instrumented.display()));
        remove_if_exists(&instrumented)?;
    }
    let Some(cutoff) = SystemTime::now().checked_sub(STALE_ARTIFACT_AGE) else {
        bail!("system clock predates the stale-artifact cutoff");
    };
    let mut removed = 0_u64;
    let mut freed = 0_u64;
    let mut pending = vec![target.clone()];
    while let Some(directory) = pending.pop() {
        let entries = match fs::read_dir(&directory) {
            Ok(entries) => entries,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => continue,
            Err(error) => {
                return Err(error)
                    .with_context(|| format!("failed to read {}", directory.display()));
            }
        };
        for entry in entries {
            let path = entry?.path();
            if path == lomo_output {
                continue;
            }
            let metadata = fs::symlink_metadata(&path)
                .with_context(|| format!("failed to stat {}", path.display()))?;
            if metadata.is_dir() {
                pending.push(path);
                continue;
            }
            let stale = metadata.modified().is_ok_and(|modified| modified < cutoff);
            let debug_symbol_package = path.extension().is_some_and(|ext| ext == "dwp");
            if stale || debug_symbol_package {
                freed = freed.saturating_add(metadata.len());
                removed += 1;
                fs::remove_file(&path)
                    .with_context(|| format!("failed to remove {}", path.display()))?;
            }
        }
    }
    Ok(json!({
        "target": target, "removed_files": removed, "freed_file_bytes": freed,
        "removed_instrumented_directory": removed_instrumented,
    }))
}

fn clean(workspace: &Workspace) -> Result<Value> {
    let mut removed = Vec::new();
    for relative in [
        ".kotlin/toolchain-build",
        "build/apk",
        "build/dist",
        "build/reports",
        "build/corpora",
        "build/jacoco",
        "target/apk",
        "target/dist",
        "target/reports",
        "target/corpora",
        "target/xtask",
        "target/boltffi-tmp",
        "target",
        "apps/android/app/jniLibs",
        "apps/android/native-bindings/src",
        ".cache/native",
    ] {
        let path = workspace.root.join(relative);
        if path.try_exists()? {
            remove_if_exists(&path)?;
            removed.push(path);
        }
    }
    Ok(json!({"removed": removed}))
}

fn directory_size(path: &Path) -> Result<u64> {
    if path.is_file() {
        return Ok(fs::metadata(path)?.len());
    }
    let mut total = 0_u64;
    let mut pending = vec![PathBuf::from(path)];
    while let Some(directory) = pending.pop() {
        for entry in fs::read_dir(&directory)
            .with_context(|| format!("failed to read {}", directory.display()))?
        {
            let path = entry?.path();
            if path.is_dir() {
                pending.push(path);
            } else {
                total = total.saturating_add(fs::metadata(path)?.len());
            }
        }
    }
    Ok(total)
}
