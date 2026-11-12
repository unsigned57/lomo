//! Behavior Contract:
//! Capability: release signing has exactly one owner — the toolchain's
//! `settings.android.signing` reading `apps/android/app/keystore.properties` — with
//! xtask validating the input before the build and verifying the signature after it.
//! Owning layer: lomo-xtask android build pipeline; priority: P0 (a silently unsigned
//! or wrongly signed release artifact is a release incident).
//!
//! Scenarios:
//! - Given `apps/android/app/module.yaml`, when inspected, then `android.signing` is
//!   enabled — the toolchain emits the signed `*-release.apk` itself.
//! - Given `apps/android/app/keystore.properties`, when parsed, then all four
//!   camelCase keys are non-empty and `storeFile` resolves to an existing keystore
//!   next to the module.
//! - Given the xtask sources, when inspected, then release candidates reject stale
//!   `-unsigned.apk` files, `apksigner verify` gates publish, and the removed manual
//!   signing path (`KEYSTORE_FILE` env merge, `SigningConfig`) is gone.
//! - Given the release workflow, when inspected, then CI materializes the same
//!   `keystore.properties` contract from secrets rather than exporting env vars the
//!   toolchain cannot read.
//! - Given the repository root, when inspected, then the dead `build_and_sign.sh`
//!   and the unused jitpack repository declaration are absent, and no source file is
//!   tracked under the divergent `apps/android/build`/`.kotlin` trees.
//!
//! Observable outcomes: manifest/signing-input facts and source-level pins.
//!
//! TDD proof: `cargo test -p lomo-xtask --test android_signing_contract --locked`.
//! RED: `settings.android.signing` was unset, `build_and_sign.sh` referenced a
//! deleted script, and jitpack was declared with no dependency needing it.
//! Excludes: an actual signed release build (covered by `just build android release`
//! emitting `apksigner verify` output in its JSON result).

#[cfg(test)]
mod tests {
    use std::{fs, path::Path, process::Command};

    use anyhow::{Context, Result, ensure};
    use lomo_xtask::repository_root;

    fn read(path: &Path) -> Result<String> {
        fs::read_to_string(path).with_context(|| format!("read {}", path.display()))
    }

    #[test]
    fn module_manifest_owns_release_signing() -> Result<()> {
        let root = repository_root()?;
        let manifest = read(&root.join("apps/android/app/module.yaml"))?;
        ensure!(
            manifest.contains("signing:") && manifest.contains("enabled: true"),
            "app/module.yaml must enable settings.android.signing"
        );
        Ok(())
    }

    #[test]
    fn keystore_properties_is_complete_and_resolves() -> Result<()> {
        let root = repository_root()?;
        let module_dir = root.join("apps/android/app");
        let properties = module_dir.join("keystore.properties");
        let text = read(&properties)?;
        let mut values = std::collections::BTreeMap::new();
        for line in text.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            if let Some((key, value)) = line.split_once('=') {
                values.insert(key.trim().to_owned(), value.trim().to_owned());
            }
        }
        for key in ["storeFile", "storePassword", "keyAlias", "keyPassword"] {
            ensure!(
                values.get(key).is_some_and(|value| !value.is_empty()),
                "{key} must be non-empty in keystore.properties"
            );
        }
        let store = values
            .get("storeFile")
            .context("storeFile missing from keystore.properties")?;
        let store = module_dir.join(store);
        ensure!(
            store.is_file(),
            "storeFile must resolve to an existing keystore: {}",
            store.display()
        );
        Ok(())
    }

    #[test]
    fn xtask_verifies_toolchain_signature_and_rejects_stale_unsigned() -> Result<()> {
        let root = repository_root()?;
        let android = read(&root.join("crates/lomo-xtask/src/android.rs"))?;
        for pin in [
            "validate_signing_config",
            "verify_signature",
            "\"verify\"",
            "contains(\"unsigned\")",
        ] {
            ensure!(
                android.contains(pin),
                "android.rs missing signing pin: {pin}"
            );
        }
        for removed in ["SigningConfig", "KEYSTORE_PASSWORD", "sign_release"] {
            ensure!(
                !android.contains(removed),
                "removed manual signing path still present: {removed}"
            );
        }
        Ok(())
    }

    #[test]
    fn release_workflow_materializes_keystore_properties() -> Result<()> {
        let root = repository_root()?;
        let workflow = read(&root.join(".github/workflows/android_release.yml"))?;
        ensure!(
            workflow.contains("apps/android/app/keystore.properties"),
            "release workflow must materialize the toolchain's keystore.properties"
        );
        ensure!(
            workflow.contains("apps/android/app/release.keystore"),
            "release workflow must decode the keystore into the module directory"
        );
        Ok(())
    }

    #[test]
    fn dead_signing_script_and_unused_repository_are_gone() -> Result<()> {
        let root = repository_root()?;
        ensure!(
            !root.join("build_and_sign.sh").exists(),
            "build_and_sign.sh must be deleted — it sources a script that no longer exists"
        );
        let data = read(&root.join("apps/android/data/module.yaml"))?;
        ensure!(
            !data.contains("jitpack"),
            "data/module.yaml declares jitpack.io but no dependency resolves there"
        );
        Ok(())
    }

    #[test]
    fn divergent_kotlin_build_trees_are_not_tracked() -> Result<()> {
        let root = repository_root()?;
        let output = Command::new("git")
            .args([
                "-C",
                root.to_str().context("repository root must be UTF-8")?,
                "ls-files",
                "apps/android/build",
                "apps/android/.kotlin",
            ])
            .output()?;
        ensure!(
            output.status.success(),
            "git ls-files failed: {}",
            String::from_utf8_lossy(&output.stderr)
        );
        ensure!(
            output.stdout.is_empty(),
            "source files must not live under divergent Kotlin build trees: {}",
            String::from_utf8_lossy(&output.stdout)
        );
        Ok(())
    }
}
