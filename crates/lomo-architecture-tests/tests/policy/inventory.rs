use std::path::{Path, PathBuf};
use std::process::Command;

use super::KOTLIN_MODULES;

pub fn source_files(root: &Path, path: &str) -> Result<Vec<PathBuf>, String> {
    let output = Command::new("git")
        .args([
            "ls-files",
            "-z",
            "--cached",
            "--others",
            "--exclude-standard",
            "--",
            path,
        ])
        .current_dir(root)
        .output()
        .map_err(|error| format!("cannot enumerate sources: {error}"))?;
    if !output.status.success() {
        return Err(format!(
            "git source inventory failed: {}",
            String::from_utf8_lossy(&output.stderr)
        ));
    }
    let mut paths = Vec::new();
    for bytes in output
        .stdout
        .split(|byte| *byte == 0)
        .filter(|part| !part.is_empty())
    {
        let relative = std::str::from_utf8(bytes)
            .map_err(|error| format!("source path is not UTF-8: {error}"))?;
        let path = root.join(relative);
        // A tracked deletion is no longer source; unreadable or redirected source is an error.
        if !path
            .try_exists()
            .map_err(|error| format!("{relative}: {error}"))?
        {
            continue;
        }
        let canonical = path
            .canonicalize()
            .map_err(|error| format!("{relative}: {error}"))?;
        if !canonical.starts_with(root) {
            return Err(format!("source escapes repository ownership: {relative}"));
        }
        paths.push(path);
    }
    paths.sort();
    paths.dedup();
    Ok(paths)
}

pub fn owned_kotlin_module(manifest: &str) -> bool {
    manifest
        .strip_suffix("/module.yaml")
        .is_some_and(|directory| KOTLIN_MODULES.contains(&directory))
}
