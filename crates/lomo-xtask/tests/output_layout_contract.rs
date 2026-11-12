//! Test Change Justification:
//! Reason category: explicitly requested output contract change.
//! Old behavior/assertion being replaced: reports follow `LOMO_GENERATED_ROOT/reports`.
//! Why old assertion is no longer correct: durable evidence belongs in root reports/.
//! Coverage preserved by: CLI discovery checks the report root under a build override;
//! existing artifact publication and failure checks remain.
//! Why this is not fitting the test to the implementation: the report location is a user
//! requirement and this assertion fails before changing Workspace.
//! TDD proof: `cargo test -p lomo-xtask --test output_layout_contract --locked`;
//! RED: reports must survive build-root overrides.
//!
//! Behavior Contract:
//! Capability: group deliverable packages separately from build state and reports;
//! owning layer: lomo-xtask; priority: P1.
//! Scenarios:
//! - Given stale and recent cache, when building, then stale files disappear before the
//!   compiler starts, recent files remain and compiler errors still propagate.
//! - Given a configured Cargo target, when paths are discovered, then Android packages,
//!   desktop packages, intermediate builds and reports have distinct named directories.
//! - Given debug/release Cargo binaries, when the TUI build command succeeds, then the
//!   returned deliverable lives under artifacts/tui/<profile> with identical bytes and mode.
//! - Given a failed compiler or missing binary, when building, then no package is published.
//!
//! Observable outcomes: JSON paths, exit status, published bytes and executable permissions.
//! TDD proof: `cargo test -p lomo-xtask --test output_layout_contract --locked`;
//! RED: package directory keys are absent and TUI returns the Cargo intermediate path.
//! GREEN: the same command after the layout change.
//! Excludes: real compilation, Android signing and device execution.

#[cfg(test)]
mod tests {
    use std::{path::Path, process::Command};

    use anyhow::{Context, Result, ensure};
    use serde_json::Value;

    #[cfg(unix)]
    fn install_sweeper(tools: &Path) -> Result<()> {
        use std::{fs, os::unix::fs::PermissionsExt as _};
        let path = tools.join("cargo-sweep");
        fs::write(
            &path,
            "#!/bin/sh\nif [ \"$1\" = --version ]; then echo 'cargo-sweep 0.8.0'; exit 0; fi\n/bin/rm -f \"$CARGO_TARGET_DIR/debug/deps/expired.rlib\"\n",
        )?;
        fs::set_permissions(path, fs::Permissions::from_mode(0o755))?;
        Ok(())
    }

    #[test]
    fn discovery_separates_packages_builds_and_reports() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let target = directory.path().join("cargo-target");
        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["cache", "paths"])
            .env("CARGO_TARGET_DIR", &target)
            .env("LOMO_GENERATED_ROOT", target.join("lomo"))
            .output()?;
        ensure!(output.status.success());
        let result: Value = serde_json::from_slice(&output.stdout)?;
        let paths = result.pointer("/data/paths").context("paths")?;
        for (name, relative) in [
            ("artifacts", "lomo/artifacts"),
            ("android_apk_debug", "lomo/artifacts/android/debug"),
            ("android_apk_release", "lomo/artifacts/android/release"),
            ("tui_dist", "lomo/artifacts/tui/dist"),
            ("build_state", "lomo/build"),
            ("corpora", "lomo/corpora"),
        ] {
            let actual = paths.get(name).and_then(Value::as_str);
            ensure!(
                actual.map(Path::new) == Some(target.join(relative).as_path()),
                "{name}: expected {}, got {actual:?}",
                target.join(relative).display()
            );
        }
        let reports = paths
            .get("reports")
            .and_then(Value::as_str)
            .context("reports")?;
        ensure!(
            Path::new(reports) == lomo_xtask::repository_root()?.join("reports"),
            "reports must survive build-root overrides: {reports}"
        );
        Ok(())
    }

    #[cfg(unix)]
    #[test]
    fn build_prunes_expired_cache_before_starting_the_compiler() -> Result<()> {
        use std::{
            fs,
            os::unix::fs::PermissionsExt as _,
            time::{Duration, SystemTime},
        };
        let directory = tempfile::tempdir()?;
        let target = directory.path().join("target");
        let tools = directory.path().join("bin");
        fs::create_dir_all(target.join("debug/deps"))?;
        fs::create_dir_all(&tools)?;
        install_sweeper(&tools)?;
        let stale = target.join("debug/deps/expired.rlib");
        let file = fs::File::create(&stale)?;
        file.set_modified(
            SystemTime::now()
                .checked_sub(Duration::from_hours(24 * 8))
                .context("mtime")?,
        )?;
        let fresh = target.join("debug/deps/recent.rlib");
        fs::write(&fresh, "reusable")?;
        let compiler = tools.join("cargo");
        fs::write(
            &compiler,
            "#!/bin/sh\ntest ! -e \"$CARGO_TARGET_DIR/debug/deps/expired.rlib\" || exit 9\nexit 7\n",
        )?;
        fs::set_permissions(&compiler, fs::Permissions::from_mode(0o755))?;
        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["build", "tui"])
            .env("CARGO_TARGET_DIR", &target)
            .env("LOMO_GENERATED_ROOT", target.join("lomo"))
            .env("LOMO_CARGO_TOOLS_DIR", directory.path())
            .env("PATH", &tools)
            .output()?;
        ensure!(!output.status.success(), "compiler failure must propagate");
        ensure!(
            !stale.exists(),
            "expired cache must be pruned before compilation"
        );
        ensure!(fs::read_to_string(fresh)? == "reusable");
        Ok(())
    }

    #[cfg(unix)]
    #[test]
    fn tui_build_publishes_both_profiles_and_rejects_failed_builds() -> Result<()> {
        use std::{fs, os::unix::fs::PermissionsExt as _};

        let directory = tempfile::tempdir()?;
        let target = directory.path().join("cargo-target");
        let tools = directory.path().join("bin");
        fs::create_dir_all(&tools)?;
        install_sweeper(&tools)?;
        let cargo = tools.join("cargo");
        fs::write(&cargo, "#!/bin/sh\nexit \"$LOMO_TEST_COMPILER_EXIT\"\n")?;
        fs::set_permissions(&cargo, fs::Permissions::from_mode(0o755))?;

        for profile in ["debug", "release"] {
            let source = target.join(profile).join("lomo");
            fs::create_dir_all(source.parent().context("source parent")?)?;
            let bytes = format!("compiled-{profile}");
            fs::write(&source, &bytes)?;
            fs::set_permissions(&source, fs::Permissions::from_mode(0o755))?;
            let published = target.join("lomo/artifacts/tui").join(profile).join("lomo");

            for compiler_exit in ["7", "0"] {
                let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
                    .args(["build", "tui", profile])
                    .env("CARGO_TARGET_DIR", &target)
                    .env("LOMO_GENERATED_ROOT", target.join("lomo"))
                    .env("LOMO_CARGO_TOOLS_DIR", directory.path())
                    .env("PATH", &tools)
                    .env("LOMO_TEST_COMPILER_EXIT", compiler_exit)
                    .output()?;
                let result: Value = serde_json::from_slice(&output.stdout)?;
                if compiler_exit == "7" {
                    ensure!(!output.status.success());
                    ensure!(!published.exists(), "failed build must not publish");
                } else {
                    ensure!(output.status.success(), "{result}");
                    ensure!(
                        result
                            .pointer("/data/binary")
                            .and_then(Value::as_str)
                            .map(Path::new)
                            == Some(published.as_path()),
                        "{result}"
                    );
                    ensure!(fs::read(&published)? == bytes.as_bytes());
                    ensure!(fs::metadata(&published)?.permissions().mode() & 0o111 != 0);
                }
            }
            fs::remove_file(&source)?;
            let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
                .args(["build", "tui", profile])
                .env("CARGO_TARGET_DIR", &target)
                .env("LOMO_GENERATED_ROOT", target.join("lomo"))
                .env("LOMO_CARGO_TOOLS_DIR", directory.path())
                .env("PATH", &tools)
                .env("LOMO_TEST_COMPILER_EXIT", "0")
                .output()?;
            ensure!(
                !output.status.success(),
                "missing compiler output must fail"
            );
            ensure!(fs::read(&published)? == bytes.as_bytes());
        }
        Ok(())
    }
}
