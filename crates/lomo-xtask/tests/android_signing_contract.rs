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
//!
//! Test Change Justification:
//! Reason category: clean-checkout regression and production-boundary coverage.
//! Old behavior/assertion being replaced: duplicate parsing of an ignored real keystore file.
//! Why old assertion is no longer correct: CI deliberately has no signing secrets during checks;
//! presence on a developer machine neither proves the parser nor the build's validation boundary.
//! Coverage preserved by: temporary relative/absolute keystores accepted by the real command
//! constructor, plus absent/incomplete inputs rejected before Kotlin preparation.
//! Why this is not fitting the test to the implementation: remote run 43883705876 failed on the
//! missing ignored file; the new negative cases also expose the unvalidated command constructor.
//! TDD proof: `missing_signing_inputs_reject_before_kotlin_preparation` fails before the fix.

#[cfg(test)]
mod tests {
    use std::{fmt::Write as _, fs, path::Path, process::Command};

    use anyhow::{Context, Result, ensure};
    use lomo_xtask::{AndroidVariant, Workspace, android_apk_command, repository_root};

    fn read(path: &Path) -> Result<String> {
        fs::read_to_string(path).with_context(|| format!("read {}", path.display()))
    }

    fn signing_workspace() -> Result<(tempfile::TempDir, Workspace)> {
        let temporary = tempfile::tempdir()?;
        let root = temporary.path().to_path_buf();
        let workspace = Workspace {
            root: root.clone(),
            rust: root.clone(),
            cargo_home: root.join("cargo-home"),
            rust_target: root.join("target/cargo"),
            lomo_output: root.join("target/lomo"),
            tool_root: root.join("tools"),
            android_sdk: root.join("sdk"),
            kotlin_home: root.join("home"),
            kotlin_cache: root.join("cache"),
            kotlin_data: root.join("data"),
            kotlin_config: root.join("config"),
            android_home: root.join("android"),
            kotlin_cli_cache: root.join("kotlin-cli"),
            gradle_home: root.join("gradle"),
            kotlin_build: root.join("shared-kotlin-build"),
        };
        fs::create_dir_all(root.join("apps/android/app/signing"))?;
        fs::write(root.join("kotlin"), "#!/bin/sh\nexit 0\n")?;
        Ok((temporary, workspace))
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
        for absolute in [false, true] {
            let (_temporary, workspace) = signing_workspace()?;
            let module = workspace.root.join("apps/android/app");
            let store = module.join("signing/release.keystore");
            fs::write(
                &store,
                "fixture keystore; cryptographic signing is a separate gate",
            )?;
            let path = if absolute {
                store
            } else {
                "signing/release.keystore".into()
            };
            fs::write(
                module.join("keystore.properties"),
                format!(
                    "storeFile={}\nstorePassword=fixture\nkeyAlias=fixture\nkeyPassword=fixture\n",
                    path.display()
                ),
            )?;
            let command = android_apk_command(&workspace, AndroidVariant::Release)?;
            ensure!(command.get_program() == workspace.root.join("kotlin"));
        }
        Ok(())
    }

    #[test]
    fn missing_signing_inputs_reject_before_kotlin_preparation() -> Result<()> {
        for missing in [
            "properties",
            "storeFile",
            "storePassword",
            "keyAlias",
            "keyPassword",
            "keystore",
        ] {
            let (_temporary, workspace) = signing_workspace()?;
            let module = workspace.root.join("apps/android/app");
            if missing != "properties" {
                let mut text = String::new();
                for (key, value) in [
                    ("storeFile", "signing/release.keystore"),
                    ("storePassword", "fixture"),
                    ("keyAlias", "fixture"),
                    ("keyPassword", "fixture"),
                ] {
                    if key != missing {
                        writeln!(&mut text, "{key}={value}")?;
                    }
                }
                fs::write(module.join("keystore.properties"), text)?;
            }
            if missing != "keystore" {
                fs::write(module.join("signing/release.keystore"), "fixture")?;
            }
            let error = android_apk_command(&workspace, AndroidVariant::Release)
                .err()
                .context("missing signing inputs must reject the release command")?;
            let expected = match missing {
                "properties" => "keystore.properties",
                "keystore" => "release keystore does not exist",
                key => key,
            };
            ensure!(error.to_string().contains(expected), "{missing}: {error}");
            ensure!(
                !workspace.kotlin_build.exists() && !workspace.kotlin_cache.exists(),
                "invalid signing must reject before build/cache preparation"
            );
        }
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
