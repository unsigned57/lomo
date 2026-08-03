use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail};

use crate::util::remove_if_exists;
use crate::workspace::Workspace;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CacheMode {
    Audit,
    Paths,
    Clean,
}

pub fn run_cache(workspace: &Workspace, mode: CacheMode) -> Result<()> {
    match mode {
        CacheMode::Audit => audit(workspace),
        CacheMode::Paths => {
            paths(workspace);
            Ok(())
        }
        CacheMode::Clean => clean(workspace),
    }
}

pub fn parse_mode(value: &str) -> Result<CacheMode> {
    match value {
        "audit" => Ok(CacheMode::Audit),
        "paths" => Ok(CacheMode::Paths),
        "clean" => Ok(CacheMode::Clean),
        _ => bail!("cache mode must be `audit`, `paths`, or `clean`, found `{value}`"),
    }
}

fn paths(workspace: &Workspace) {
    for (name, path) in [
        ("home", &workspace.kotlin_home),
        ("xdg_cache", &workspace.kotlin_cache),
        ("xdg_data", &workspace.kotlin_data),
        ("xdg_config", &workspace.kotlin_config),
        ("android_user_home", &workspace.android_home),
        ("kotlin_cli_cache", &workspace.kotlin_cli_cache),
        ("gradle_user_home", &workspace.gradle_home),
        ("cargo_home", &workspace.cargo_home),
        ("cargo_target", &workspace.rust_target),
        ("cargo_tools", &workspace.tool_root),
        ("kotlin_build", &workspace.kotlin_build),
    ] {
        crate::util::emit_stderr(format_args!("{name}={}", path.display()));
    }
}

fn audit(workspace: &Workspace) -> Result<()> {
    crate::util::emit_stderr(format_args!("Lomo generated-state audit"));
    for relative in [
        ".cache",
        ".gradle",
        ".kotlin",
        ".kotlin-cli",
        ".android-sdk",
        "build/reports",
        "rust/target",
        // Accidental nested targets when relative CARGO_TARGET_DIR leaked into boltffi pack.
        "rust/native/rust",
        "rust/native/target",
        "app/jniLibs",
        "native-bindings/src",
    ] {
        let path = workspace.root.join(relative);
        if path.exists() {
            crate::util::emit_stderr(format_args!("{relative}: {} bytes", directory_size(&path)?));
        } else {
            crate::util::emit_stderr(format_args!("{relative}: absent"));
        }
    }
    Ok(())
}

fn clean(workspace: &Workspace) -> Result<()> {
    for relative in [
        ".kotlin/toolchain-build",
        "build/reports",
        "build/jacoco",
        "rust/target",
        "rust/native/rust",
        "rust/native/target",
        "app/jniLibs",
        "native-bindings/src",
        ".cache/native",
    ] {
        let path = workspace.root.join(relative);
        crate::util::emit_stderr(format_args!("xtask: removing {}", path.display()));
        remove_if_exists(&path)?;
    }
    Ok(())
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
