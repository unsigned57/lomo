//! Test Change Justification:
//! Reason category: user requested automatic, bounded cleanup of project garbage.
//! Old behavior/assertion being replaced: custom Cargo file-mtime pruning and unconditional .dwp deletion.
//! Why old assertion is no longer correct: the requested tool delegation uses Cargo fingerprint
//! access times; recent fingerprint-owned symbols must survive. Expired scratch is disposable.
//! Coverage preserved by: stale Cargo fingerprints, cross-target files, scratch, lock contention,
//! tool errors, fresh symbols, packages and reports are exercised through the real CLI.
//! Why this is not fitting the test to the implementation: retention is specified before code changes.
//! TDD proof: `cargo test -p lomo-xtask --test cache_prune_contract --locked`;
//! RED: lomo/tmp/old/probe.bin should be pruned.
/*
 * Behavior Contract:
 * - Unit under test: lomo-xtask cache prune.
 * - Owning layer: quality orchestration.
 * - Priority tier: P1.
 * - Capability: reclaim stale Cargo build artifacts without discarding fresh build state or
 *   release outputs.
 *
 * Scenarios:
 * - Given a locked Cargo profile, when pruning, then cleanup fails before deleting files.
 * - Given a scratch symlink to unowned state, when pruning, then that state survives.
 * - Given old and recent command scratch files, when pruning, then only old scratch is removed.
 * - Given historical reports or packages, when pruning, then evidence and deliverables remain.
 * - Given a cargo target directory mixing fresh artifacts, artifacts older than the staleness
 *   cutoff, packed debug-symbol files, an llvm-cov instrumented build directory, and release
 *   outputs under the `lomo` namespace, when `cache prune` runs, then only stale files,
 *   expired scratch, and the instrumented build directory are removed by their owning tools.
 *
 * Observable outcomes:
 * - `cache prune` exits successfully.
 * - Cargo fingerprints unused for seven days are swept; fresh artifacts
 *   and `lomo/` release outputs survive regardless of age.
 *
 * TDD proof:
 * - Before the fix, `cache prune` is rejected as an unknown mode.
 *
 * Test Change Justification:
 * Reason category: requested output layout change.
 * Old behavior/assertion being replaced: archived packages lived in lomo/dist/.
 * Why old assertion is no longer correct: packages are grouped under lomo/artifacts/.
 * Coverage preserved by: the same stale package must survive Cargo pruning.
 * Why this is not fitting the test to the implementation: retention policy is unchanged;
 * only the package location follows the public directory contract.
 *
 * Excludes:
 * - Staleness threshold tuning and global dependency caches.
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
    fn prune_delegates_cargo_retention_and_preserves_recent_files_and_deliverables() -> Result<()> {
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
        fs::write(
            target.join("llvm-cov-target/CACHEDIR.TAG"),
            "Signature: 8a477f597d28d172789f06886806bc55\n",
        )?;
        let release_output = write(
            &target.join("lomo/artifacts/tui/dist/lomo-0.1.0.tar.gz"),
            STALE_AGE,
        )?;

        let stale_scratch = write(&target.join("lomo/tmp/old/probe.bin"), STALE_AGE)?;
        let fresh_scratch = write(&target.join("lomo/tmp/current/probe.bin"), Duration::ZERO)?;
        let reports = fixture.join("reports");
        let baseline_report = write(
            &reports.join("device-perf-engine-baseline.v2.json"),
            STALE_AGE,
        )?;

        fingerprint(&target.join("debug"), "stale-aaaaaaaaaaaaaaaa", STALE_AGE)?;
        fingerprint(
            &target.join("debug"),
            "fresh-bbbbbbbbbbbbbbbb",
            Duration::ZERO,
        )?;
        fingerprint(
            &target.join("debug"),
            "test-binary-cccccccccccccccc",
            Duration::ZERO,
        )?;
        fingerprint(
            &target.join("x86_64-pc-windows-msvc/debug"),
            "stale-dddddddddddddddd",
            STALE_AGE,
        )?;
        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["cache", "prune"])
            .env("CARGO_TARGET_DIR", &target)
            .env("LOMO_GENERATED_ROOT", target.join("lomo"))
            .env("LOMO_REPORTS_DIR", &reports)
            .output()
            .context("run lomo-xtask cache prune")?;
        if !output.status.success() {
            bail!(
                "cache prune failed: {}",
                String::from_utf8_lossy(&output.stderr)
            );
        }

        for removed in [&stale, &cross, &stale_scratch] {
            ensure!(!removed.exists(), "{} should be pruned", removed.display());
        }
        ensure!(
            !target.join("llvm-cov-target").exists(),
            "llvm-cov instrumented artifacts should be pruned"
        );
        for kept in [
            &fresh,
            &dwp,
            &release_output,
            &fresh_scratch,
            &baseline_report,
        ] {
            ensure!(kept.exists(), "{} should be kept", kept.display());
        }
        Ok(())
    }

    #[test]
    fn prune_bounds_verification_runs_and_stale_detekt_symbols() -> Result<()> {
        let fixture = tempfile::tempdir()?;
        let reports = fixture.path().join("reports");
        let mut verification_runs = Vec::new();
        for ts in 1_u128..=12 {
            let run_dir = reports.join(format!("verification/{ts}-100"));
            write(&run_dir.join("results.json"), STALE_AGE)?;
            verification_runs.push(run_dir);
        }
        let active_symbols = reports.join("detekt/symbols/app.active");
        write(&active_symbols.join("facts.json"), STALE_AGE)?;
        fs::write(
            reports.join("detekt/symbols/index-app.json"),
            serde_json::json!({"schema_version": 1, "directory": active_symbols}).to_string(),
        )?;
        let stale_symbols = reports.join("detekt/symbols/app.stale");
        write(&stale_symbols.join("facts.json"), STALE_AGE)?;

        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["cache", "prune"])
            .env("CARGO_TARGET_DIR", fixture.path().join("target"))
            .env("LOMO_GENERATED_ROOT", fixture.path().join("lomo"))
            .env("LOMO_REPORTS_DIR", &reports)
            .output()
            .context("run lomo-xtask cache prune")?;
        ensure!(
            output.status.success(),
            "cache prune failed: {}",
            String::from_utf8_lossy(&output.stderr)
        );
        for removed in [
            &stale_symbols,
            verification_runs.first().context("run 1")?,
            verification_runs.get(1).context("run 2")?,
        ] {
            ensure!(!removed.exists(), "{} should be pruned", removed.display());
        }
        for kept in std::iter::once(&active_symbols).chain(verification_runs.iter().skip(2)) {
            ensure!(kept.exists(), "{} should be kept", kept.display());
        }
        Ok(())
    }

    #[test]
    fn prune_refuses_an_active_cargo_profile_without_removing_files() -> Result<()> {
        let fixture = tempfile::tempdir()?;
        let target = fixture.path().join("target");
        let stale = write(
            &target.join("debug/deps/old-aaaaaaaaaaaaaaaa.rlib"),
            STALE_AGE,
        )?;
        fingerprint(&target.join("debug"), "old-aaaaaaaaaaaaaaaa", STALE_AGE)?;
        let lock_path = write(&target.join("debug/.cargo-lock"), STALE_AGE)?;
        let lock = fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(&lock_path)?;
        lock.lock()?;
        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["cache", "prune"])
            .env("CARGO_TARGET_DIR", &target)
            .env("LOMO_GENERATED_ROOT", fixture.path().join("lomo"))
            .env("LOMO_REPORTS_DIR", fixture.path().join("reports"))
            .output()?;
        ensure!(
            !output.status.success(),
            "an active compiler must block cleanup"
        );
        ensure!(String::from_utf8(output.stdout)?.contains("Cargo profile is busy"));
        ensure!(
            stale.exists() && lock_path.exists(),
            "failed cleanup must retain build state"
        );
        drop(lock);
        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["cache", "prune"])
            .env("CARGO_TARGET_DIR", &target)
            .env("LOMO_GENERATED_ROOT", fixture.path().join("lomo"))
            .env("LOMO_REPORTS_DIR", fixture.path().join("reports"))
            .output()?;
        ensure!(
            output.status.success(),
            "cleanup must proceed after the compiler releases its lock: {} {}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        ensure!(
            !stale.exists() && lock_path.exists(),
            "lock identity must survive cleanup"
        );
        Ok(())
    }

    #[cfg(unix)]
    #[test]
    fn prune_never_follows_scratch_symlinks_into_other_directories() -> Result<()> {
        let fixture = tempfile::tempdir()?;
        let target = fixture.path().join("target");
        let outside = write(&fixture.path().join("unowned/precious.txt"), STALE_AGE)?;
        let scratch = target.join("lomo/tmp");
        fs::create_dir_all(&scratch)?;
        std::os::unix::fs::symlink(
            outside.parent().context("outside parent")?,
            scratch.join("link"),
        )?;
        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["cache", "prune"])
            .env("CARGO_TARGET_DIR", &target)
            .env("LOMO_GENERATED_ROOT", target.join("lomo"))
            .env("LOMO_REPORTS_DIR", fixture.path().join("reports"))
            .output()?;
        ensure!(output.status.success());
        ensure!(
            outside.exists(),
            "unowned files must survive symlink traversal attempts"
        );
        Ok(())
    }

    #[cfg(unix)]
    #[test]
    fn sweep_diagnostics_fail_cleanup_even_when_the_tool_exits_zero() -> Result<()> {
        use std::os::unix::fs::PermissionsExt as _;
        let fixture = tempfile::tempdir()?;
        let tools = fixture.path().join("tools/bin");
        fs::create_dir_all(&tools)?;
        let sweeper = tools.join("cargo-sweep");
        fs::write(
            &sweeper,
            "#!/bin/sh\nif [ \"$1\" = --version ]; then echo 'cargo-sweep 0.8.0'; else echo '[ERROR] injected cleanup failure'; fi\n",
        )?;
        fs::set_permissions(sweeper, fs::Permissions::from_mode(0o755))?;
        let scratch = write(&fixture.path().join("lomo/tmp/old.bin"), STALE_AGE)?;
        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["cache", "prune"])
            .env("CARGO_TARGET_DIR", fixture.path().join("target"))
            .env("LOMO_GENERATED_ROOT", fixture.path().join("lomo"))
            .env("LOMO_REPORTS_DIR", fixture.path().join("reports"))
            .env("LOMO_CARGO_TOOLS_DIR", fixture.path().join("tools"))
            .output()?;
        ensure!(!output.status.success());
        ensure!(
            String::from_utf8(output.stdout)?.contains("cargo-sweep reported a cleanup failure")
        );
        ensure!(
            scratch.exists(),
            "tool failure must stop subsequent cleanup"
        );
        Ok(())
    }

    fn fingerprint(profile: &Path, name: &str, age: Duration) -> Result<()> {
        fs::create_dir_all(profile.join("build"))?;
        let path = write(
            &profile.join(".fingerprint").join(name).join("lib-fixture"),
            age,
        )?;
        let accessed = SystemTime::now()
            .checked_sub(age)
            .context("fingerprint atime")?;
        fs::File::options().write(true).open(path)?.set_times(
            fs::FileTimes::new()
                .set_accessed(accessed)
                .set_modified(accessed),
        )?;
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
