//! Behavior Contract:
//! Capability: APK resource merge starts without stale state from rewritten Amper archives;
//! owner: lomo-xtask Android packaging; priority: P0.
//! Scenarios:
//! - Given a warm debug or release build, when its Kotlin command is prepared and executed,
//!   then the consumer sees neither the old Java resource snapshot nor merged resource archives.
//! - Given the full CI Kotlin build, when its command is prepared, then debug resource
//!   state is removed before the consumer while all-module build arguments are preserved.
//! - Given another variant, compiled classes, native outputs and dependency caches,
//!   when preparing an APK build, then their bytes remain unchanged.
//! - Given a fresh build, preparation succeeds; given an invalid cache parent, it fails
//!   before a build command can consume the stale state.
//!
//! Observable outcomes: subprocess status/arguments, removed and preserved file bytes, I/O errors.
//! TDD proof: `cargo test -p lomo-xtask --test android_java_resources_contract --locked`;
//! RED: old Java resource snapshots reach the Kotlin consumer; GREEN: the same target passes.
//! CI regression RED: reports/manual/gates/resources-red.log, consumer exited 31 on stale state.
//! GREEN: reports/manual/gates/resources-green.log, all resource contracts pass.
//! Excludes: AGP's internal merger implementation; real signed release builds validate integration.
//!
//! Test Change Justification:
//! Reason category: supply the release command's required signing precondition in its fixture.
//! Old behavior/assertion being replaced: release command construction accepted absent signing input.
//! Why old assertion is no longer correct: the canonical constructor now validates signing before
//! preparing build state; quality tests must provide self-contained input rather than real secrets.
//! Coverage preserved by: every stale-resource, variant and preservation assertion stays unchanged.
//! Why this is not fitting the test to the implementation: signing rejection/acceptance is tested
//! through the real constructor in `android_signing_contract`; this fixture isolates resource state.

#[cfg(test)]
mod tests {
    use std::{fs, path::Path};

    use anyhow::{Context, Result, ensure};
    use lomo_xtask::{AndroidVariant, Workspace, android_apk_command};

    fn fixture() -> Result<(tempfile::TempDir, Workspace)> {
        let temporary = tempfile::Builder::new()
            .prefix("lomo java resources ")
            .tempdir()?;
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
        fs::write(
            root.join("kotlin"),
            "#!/bin/sh\n[ ! -e \"$LOMO_RESOURCE_STATE\" ] || exit 31\n[ ! -e \"$LOMO_RESOURCE_OUTPUT\" ] || exit 32\nprintf '%s\\n' \"$@\"\n",
        )?;
        let module = root.join("apps/android/app");
        fs::create_dir_all(&module)?;
        fs::write(module.join("fixture.keystore"), "test-only keystore bytes")?;
        fs::write(
            module.join("keystore.properties"),
            "storeFile=fixture.keystore\nstorePassword=fixture\nkeyAlias=fixture\nkeyPassword=fixture\n",
        )?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt as _;
            fs::set_permissions(root.join("kotlin"), fs::Permissions::from_mode(0o755))?;
        }
        Ok((temporary, workspace))
    }

    fn plant(path: &Path, bytes: &[u8]) -> Result<()> {
        fs::create_dir_all(path.parent().context("file parent")?)?;
        fs::write(path, bytes)?;
        Ok(())
    }

    fn intermediates(workspace: &Workspace, task: &str) -> std::path::PathBuf {
        workspace
            .kotlin_build
            .join("tasks")
            .join(task)
            .join("gradle-project/build/_app/intermediates")
    }

    #[cfg(unix)]
    #[test]
    fn warm_variant_rebuilds_resource_state_before_the_consumer_and_preserves_other_outputs()
    -> Result<()> {
        for (variant, title, name, other) in [
            (AndroidVariant::Debug, "Debug", "debug", "Release"),
            (AndroidVariant::Release, "Release", "release", "Debug"),
        ] {
            let (_temporary, workspace) = fixture()?;
            let base = intermediates(&workspace, &format!("_app_buildAndroid{title}"));
            let snapshot = base.join(format!("incremental/{name}-mergeJavaRes/merge-state"));
            let merged = base.join(format!("merged_java_res/{name}"));
            let retained = [
                base.join("javac/classes.bin"),
                base.join("merged_native_libs/native.so"),
                intermediates(&workspace, &format!("_app_buildAndroid{other}")).join(format!(
                    "incremental/{}-mergeJavaRes/merge-state",
                    other.to_lowercase()
                )),
                intermediates(&workspace, &format!("_app_buildAndroid{other}"))
                    .join(format!("merged_java_res/{}/base.jar", other.to_lowercase())),
                workspace.gradle_home.join("caches/dependency.jar"),
                workspace.kotlin_cache.join("dependency.jar"),
            ];
            for path in &retained {
                plant(path, b"retained")?;
            }
            // Recreate stale merger state between builds, including same-ABI warm builds.
            for _ in 0..2 {
                plant(&snapshot, b"old snapshot")?;
                plant(
                    &base.join(format!("incremental/{name}-mergeJavaRes/zip-cache/old")),
                    b"old zip",
                )?;
                plant(
                    &merged.join(format!("merge{title}JavaResource/base.jar")),
                    b"old merge",
                )?;
                plant(
                    &merged.join(format!("minify{title}WithR8/base.jar")),
                    b"old derived resource",
                )?;
                let output = android_apk_command(&workspace, variant)?
                    .env("LOMO_RESOURCE_STATE", &snapshot)
                    .env("LOMO_RESOURCE_OUTPUT", &merged)
                    .output()?;
                ensure!(
                    output.status.success(),
                    "stale Java resource state reached {name} consumer: {:?}",
                    output.status
                );
                let arguments = String::from_utf8(output.stdout)?;
                ensure!(
                    arguments.lines().collect::<Vec<_>>()
                        == vec![
                            "--log-level=warn",
                            "build",
                            "--module",
                            "app",
                            "--platform",
                            "android",
                            "--variant",
                            name,
                            "--build-dir",
                            workspace.kotlin_build.to_str().context("build path")?,
                        ]
                );
                for path in &retained {
                    ensure!(fs::read(path)? == b"retained", "changed {}", path.display());
                }
            }
        }
        Ok(())
    }

    #[cfg(unix)]
    #[test]
    fn full_ci_build_rejects_stale_resource_state_before_the_consumer() -> Result<()> {
        let (_temporary, workspace) = fixture()?;
        let base = intermediates(&workspace, "_app_buildAndroidDebug");
        let snapshot = base.join("incremental/debug-mergeJavaRes/merge-state");
        let merged = base.join("merged_java_res/debug");
        plant(&snapshot, b"old snapshot")?;
        plant(
            &merged.join("mergeDebugJavaResource/base.jar"),
            b"old merge",
        )?;
        let output = lomo_xtask::kotlin_build_command(&workspace)?
            .env("LOMO_RESOURCE_STATE", &snapshot)
            .env("LOMO_RESOURCE_OUTPUT", &merged)
            .output()?;
        ensure!(
            output.status.success(),
            "stale state reached CI consumer: {:?}",
            output.status
        );
        let arguments = String::from_utf8(output.stdout)?;
        ensure!(
            arguments.lines().collect::<Vec<_>>()
                == vec![
                    "--log-level=warn",
                    "build",
                    "--build-dir",
                    workspace.kotlin_build.to_str().context("build path")?,
                ]
        );
        Ok(())
    }

    #[test]
    fn fresh_build_is_accepted_and_invalid_cache_parent_is_reported() -> Result<()> {
        let (_temporary, workspace) = fixture()?;
        android_apk_command(&workspace, AndroidVariant::Release)?;
        let parent = intermediates(&workspace, "_app_buildAndroidRelease").join("incremental");
        plant(&parent, b"not a directory")?;
        let error = android_apk_command(&workspace, AndroidVariant::Release)
            .err()
            .context("invalid resource cache parent must reject command preparation")?;
        ensure!(format!("{error:#}").contains("release-mergeJavaRes"));
        ensure!(fs::read(parent)? == b"not a directory");
        Ok(())
    }
}
