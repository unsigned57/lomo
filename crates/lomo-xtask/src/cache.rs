use std::{
    fs,
    path::{Path, PathBuf},
    time::{Duration, SystemTime},
};

use anyhow::{Context, Result, bail, ensure};
use serde_json::{Value, json};

use crate::{util::remove_if_exists, workspace::Workspace};

/// Project scratch files are disposable after a week without modification. Cargo artifact
/// retention is delegated to cargo-sweep, which uses fingerprint access times.
const STALE_SCRATCH_AGE: Duration = Duration::from_hours(24 * 7);

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
    let artifacts = workspace.artifacts_dir();
    let apk_debug = workspace.apk_output_dir("debug");
    let apk_release = workspace.apk_output_dir("release");
    let tui_dist = workspace.tui_dist_dir();
    let build_state = workspace.build_state_dir();
    let reports = workspace.reports_dir();
    let corpora = workspace.corpora_dir();
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
        ("artifacts", &artifacts),
        ("android_apk_debug", &apk_debug),
        ("android_apk_release", &apk_release),
        ("tui_dist", &tui_dist),
        ("build_state", &build_state),
        ("reports", &reports),
        ("corpora", &corpora),
        ("cargo_tools", &workspace.tool_root),
        ("kotlin_build", &workspace.kotlin_build),
    ]
    .into_iter()
    .collect();
    json!({"paths": paths})
}

fn audit(workspace: &Workspace) -> Result<Value> {
    let mut entries = Vec::new();
    for path in [
        workspace.rust_target(),
        workspace.artifacts_dir(),
        workspace.build_state_dir(),
        workspace.reports_dir(),
        workspace.corpora_dir(),
        workspace.lomo_output_dir().join("tmp"),
        workspace.root.join("apps/android/app/jniLibs"),
        workspace.root.join("apps/android/native-bindings/src"),
        // Surface divergent module-local outputs so they cannot shadow the shared build.
        workspace.root.join("apps/android/.kotlin"),
        workspace.root.join("apps/android/build"),
    ] {
        if path.try_exists()? {
            entries
                .push(json!({"path": path, "state": "present", "bytes": directory_size(&path)?}));
        } else {
            entries.push(json!({"path": path, "state": "absent"}));
        }
    }
    Ok(json!({"entries": entries}))
}

pub fn prune(workspace: &Workspace) -> Result<Value> {
    let target = &workspace.rust_target;
    crate::tools::ensure_pinned_tool(workspace, "cargo-sweep")?;
    let lomo_output = workspace.lomo_output_dir();
    let _cargo_locks = lock_cargo_profiles(target, &lomo_output)?;
    let instrumented = target.join("llvm-cov-target");
    let removed_instrumented = instrumented.try_exists()?;
    if removed_instrumented {
        crate::util::emit_stderr(format_args!("xtask: removing {}", instrumented.display()));
        let mut clean = crate::util::cargo(workspace);
        clean.args(["clean", "--target-dir"]).arg(&instrumented);
        crate::util::run(&mut clean)?;
    }
    let mut sweep = crate::util::repository_command(
        workspace,
        workspace
            .tool_bin()
            .join(format!("cargo-sweep{}", std::env::consts::EXE_SUFFIX)),
    );
    sweep
        .args(["sweep", "--time", "7"])
        .arg(&workspace.root)
        .env("CARGO_TARGET_DIR", target);
    let output = crate::util::output(&mut sweep)?;
    let sweep_log = String::from_utf8(output.stdout).context("cargo-sweep output is not UTF-8")?;
    crate::util::emit_stderr(format_args!("{sweep_log}"));
    // cargo-sweep 0.8 reports per-target failures with [ERROR] but can still exit zero.
    ensure!(
        !sweep_log.lines().any(|line| line.starts_with("[ERROR]") || line.starts_with("[WARN] Failed to remove:")),
        "cargo-sweep reported a cleanup failure"
    );
    let Some(cutoff) = SystemTime::now().checked_sub(STALE_SCRATCH_AGE) else {
        bail!("system clock predates the stale-artifact cutoff");
    };
    let mut removed = 0_u64;
    let mut freed = 0_u64;
    let scratch = lomo_output.join("tmp");
    let mut pending = vec![scratch.clone()];
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
            let metadata = fs::symlink_metadata(&path)
                .with_context(|| format!("failed to stat {}", path.display()))?;
            if metadata.is_dir() {
                pending.push(path);
                continue;
            }
            let stale = metadata
                .modified()
                .with_context(|| format!("read mtime {}", path.display()))?
                < cutoff;
            if stale {
                freed = freed.saturating_add(metadata.len());
                removed += 1;
                fs::remove_file(&path)
                    .with_context(|| format!("failed to remove {}", path.display()))?;
            }
        }
    }
    crate::util::emit_stderr(format_args!(
        "xtask: scratch prune removed {removed} expired files ({freed} bytes); recent scratch, artifacts and reports retained"
    ));
    Ok(json!({
        "target": target, "cargo_sweep": sweep_log, "scratch": scratch, "removed_scratch_files": removed, "freed_scratch_bytes": freed,
        "removed_instrumented_directory": removed_instrumented,
    }))
}

// Cargo holds these same profile locks while compiling. Acquire every existing profile lock
// before deleting anything, and retain the handles until pruning finishes. Never unlink locks.
fn lock_cargo_profiles(target: &Path, lomo_output: &Path) -> Result<Vec<fs::File>> {
    let mut locks = Vec::new();
    let mut pending = vec![target.to_path_buf()];
    while let Some(directory) = pending.pop() {
        let entries = match fs::read_dir(&directory) {
            Ok(entries) => entries,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => continue,
            Err(error) => {
                return Err(error).with_context(|| format!("read {}", directory.display()));
            }
        };
        let lock_path = directory.join(".cargo-lock");
        if lock_path.try_exists()? {
            let lock = fs::OpenOptions::new()
                .read(true)
                .write(true)
                .open(&lock_path)?;
            lock.try_lock().with_context(|| {
                format!(
                    "Cargo profile is busy: {}; retry after the other build finishes",
                    directory.display()
                )
            })?;
            locks.push(lock);
        }
        for entry in entries {
            let entry = entry?;
            if entry.file_type()?.is_dir()
                && entry.path() != lomo_output
                && entry.path() != target.join("llvm-cov-target")
            {
                pending.push(entry.path());
            }
        }
    }
    Ok(locks)
}

fn clean(workspace: &Workspace) -> Result<Value> {
    let mut removed = Vec::new();
    for relative in [
        // Divergent Kotlin build trees outside the canonical --build-dir location;
        // regenerable output, removed so a stale graph can never shadow the shared one.
        "apps/android/.kotlin",
        "apps/android/build",
        "target",
        "apps/android/app/jniLibs",
        "apps/android/native-bindings/src",
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
