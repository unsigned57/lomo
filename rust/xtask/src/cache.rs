use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail};

use crate::util::remove_if_exists;
use crate::workspace::Workspace;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CacheMode {
    Audit,
    Clean,
}

pub fn run_cache(workspace: &Workspace, mode: CacheMode) -> Result<()> {
    match mode {
        CacheMode::Audit => audit(workspace),
        CacheMode::Clean => clean(workspace),
    }
}

pub fn parse_mode(value: &str) -> Result<CacheMode> {
    match value {
        "audit" => Ok(CacheMode::Audit),
        "clean" => Ok(CacheMode::Clean),
        _ => bail!("cache mode must be `audit` or `clean`, found `{value}`"),
    }
}

fn audit(workspace: &Workspace) -> Result<()> {
    eprintln!("Lomo generated-state audit");
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
            eprintln!("{relative}: {} bytes", directory_size(&path)?);
        } else {
            eprintln!("{relative}: absent");
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
        eprintln!("xtask: removing {}", path.display());
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
