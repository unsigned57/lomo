/*
 * Behavior Contract:
 * - Unit under test: lomo-xtask cache prune.
 * - Owning layer: quality orchestration.
 * - Priority tier: P1.
 * - Capability: reclaim stale Cargo build artifacts without discarding fresh build state or
 *   release outputs.
 *
 * Scenarios:
 * - Given a cargo target directory mixing fresh artifacts, artifacts older than the staleness
 *   cutoff, packed debug-symbol files, an llvm-cov instrumented build directory, and release
 *   outputs under the `lomo` namespace, when `cache prune` runs, then only stale files,
 *   `.dwp` files, and the instrumented build directory are removed.
 *
 * Observable outcomes:
 * - `cache prune` exits successfully.
 * - Files older than the staleness cutoff and every `.dwp` file are deleted; fresh artifacts
 *   and `lomo/` release outputs survive regardless of age.
 *
 * TDD proof:
 * - Before the fix, `cache prune` is rejected as an unknown mode.
 *
 * Excludes:
 * - Staleness threshold tuning, empty-directory cleanup, and caches outside the cargo target
 *   directory.
 */

#[cfg(test)]
mod tests {
    use std::{
        fs,
        path::{Path, PathBuf},
        process::Command,
        time::{Duration, SystemTime},
    };

    use anyhow::{Context, Result, bail, ensure};

    const STALE_AGE: Duration = Duration::from_hours(24 * 8);

    #[test]
    fn prune_removes_stale_and_debug_only_artifacts_but_keeps_fresh_and_lomo_outputs() -> Result<()>
    {
        let fixture = Path::new("/tmp/lomo-cache-prune-contract");
        if fixture.exists() {
            fs::remove_dir_all(fixture).context("reset prune fixture")?;
        }
        let target = fixture.join("cargo-target");

        let stale = write(
            &target.join("debug/deps/stale-aaaaaaaaaaaaaaaa.rlib"),
            STALE_AGE,
        )?;
        let fresh = write(
            &target.join("debug/deps/fresh-bbbbbbbbbbbbbbbb.rlib"),
            Duration::ZERO,
        )?;
        let dwp = write(
            &target.join("debug/deps/test-binary-cccccccccccccccc.dwp"),
            Duration::ZERO,
        )?;
        let cross = write(
            &target.join("x86_64-pc-windows-msvc/debug/deps/stale-dddddddddddddddd.rmeta"),
            STALE_AGE,
        )?;
        write(
            &target.join("llvm-cov-target/debug/instrumented.o"),
            Duration::ZERO,
        )?;
        let release_output = write(&target.join("lomo/dist/lomo-0.1.0.tar.gz"), STALE_AGE)?;

        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["cache", "prune"])
            .env("CARGO_TARGET_DIR", &target)
            .output()
            .context("run lomo-xtask cache prune")?;
        if !output.status.success() {
            bail!(
                "cache prune failed: {}",
                String::from_utf8_lossy(&output.stderr)
            );
        }

        for removed in [&stale, &dwp, &cross] {
            ensure!(!removed.exists(), "{} should be pruned", removed.display());
        }
        ensure!(
            !target.join("llvm-cov-target").exists(),
            "llvm-cov instrumented artifacts should be pruned"
        );
        for kept in [&fresh, &release_output] {
            ensure!(kept.exists(), "{} should be kept", kept.display());
        }
        Ok(())
    }

    fn write(path: &Path, age: Duration) -> Result<PathBuf> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).with_context(|| format!("create {}", parent.display()))?;
        }
        let file = fs::File::create(path).with_context(|| format!("create {}", path.display()))?;
        file.set_len(4096)?;
        let modified = SystemTime::now()
            .checked_sub(age)
            .context("derive fixture mtime")?;
        file.set_modified(modified)
            .with_context(|| format!("stamp {}", path.display()))?;
        Ok(path.to_path_buf())
    }
}
