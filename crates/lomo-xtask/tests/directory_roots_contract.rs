//! Behavior Contract:
//! Capability: keep compiler caches and generated deliverables in separate directory roots;
//! owning layer: lomo-xtask; priority: P1.
//! Scenarios:
//! - Given no overrides, when paths are discovered, then Cargo uses target/cargo,
//!   Lomo uses target/lomo and Kotlin builds use target/lomo/build/kotlin.
//! - Given independent Cargo and Lomo overrides, when discovered, then each is preserved
//!   and Kotlin output follows the Lomo root.
//! - Given a clean checkout without local compiler settings, when Cargo reads the shared
//!   configuration from a nested directory, then metadata selects target/cargo.
//!
//! Observable outcomes: CLI JSON paths and Cargo metadata `target_directory`.
//! TDD proof: `cargo test -p lomo-xtask --test directory_roots_contract --locked`;
//! RED: Cargo still resolves target/ and Kotlin still resolves .kotlin/toolchain-build/shared.
//! GREEN: the same command after consolidation.
//! Excludes: native compilation and global dependency cache relocation.

#[cfg(test)]
mod tests {
    use std::{fs, path::Path, process::Command};

    use anyhow::{Context, Result, ensure};
    use serde_json::Value;

    #[test]
    fn default_and_overridden_roots_keep_builds_and_deliverables_separate() -> Result<()> {
        let repository = lomo_xtask::repository_root()?;
        let temporary = tempfile::tempdir()?;
        for overridden in [false, true] {
            let mut command = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"));
            command
                .args(["cache", "paths"])
                .env_remove("CARGO_TARGET_DIR")
                .env_remove("LOMO_GENERATED_ROOT")
                .env_remove("LOMO_KOTLIN_BUILD_DIR");
            let (cargo, lomo) = if overridden {
                let cargo = temporary.path().join("compilation");
                let lomo = temporary.path().join("results");
                command
                    .env("CARGO_TARGET_DIR", &cargo)
                    .env("LOMO_GENERATED_ROOT", &lomo);
                (cargo, lomo)
            } else {
                (
                    repository.join("target/cargo"),
                    repository.join("target/lomo"),
                )
            };
            let output = command.output()?;
            ensure!(output.status.success());
            let document: Value = serde_json::from_slice(&output.stdout)?;
            let paths = document.pointer("/data/paths").context("paths")?;
            for (name, expected) in [
                ("cargo_target", cargo),
                ("kotlin_build", lomo.join("build/kotlin")),
                ("lomo_output", lomo),
            ] {
                let actual = paths.get(name).and_then(Value::as_str).context(name)?;
                ensure!(
                    Path::new(actual) == expected,
                    "{name}: {actual} != {}",
                    expected.display()
                );
            }
        }
        Ok(())
    }

    #[test]
    fn cargo_discovers_the_shared_output_configuration_without_local_settings() -> Result<()> {
        let repository = lomo_xtask::repository_root()?;
        let temporary = tempfile::tempdir()?;
        let root = temporary.path();
        fs::create_dir(root.join(".cargo"))?;
        fs::create_dir(root.join("src"))?;
        fs::copy(
            repository.join(".cargo/config.toml"),
            root.join(".cargo/config.toml"),
        )?;
        fs::write(
            root.join("Cargo.toml"),
            "[package]\nname = \"layout-control\"\nversion = \"0.0.0\"\nedition = \"2024\"\n",
        )?;
        fs::write(root.join("src/lib.rs"), "")?;
        let output = Command::new("cargo")
            .current_dir(root.join("src"))
            .args([
                "metadata",
                "--no-deps",
                "--format-version",
                "1",
                "--offline",
            ])
            .env_remove("CARGO_TARGET_DIR")
            .env("RUSTC_WRAPPER", "")
            .output()?;
        ensure!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
        let metadata: Value = serde_json::from_slice(&output.stdout)?;
        let actual = metadata
            .get("target_directory")
            .and_then(Value::as_str)
            .context("Cargo target_directory")?;
        ensure!(
            Path::new(actual) == root.join("target/cargo"),
            "Cargo selected {actual}"
        );
        Ok(())
    }
}
