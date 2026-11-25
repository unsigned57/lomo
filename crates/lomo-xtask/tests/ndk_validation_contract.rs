// Dependency upgrade — Test Change Justification:
// Reason category: mechanical migration.
// Old behavior/assertion being replaced: old dependency API spelling and boolean empty checks.
// Why old assertion is no longer correct: updated APIs and Rust 1.99 diagnostics require explicit
// byte encoding, image geometry types and equality assertions that print failing collections.
// Coverage preserved by: the same scenarios, input bytes, expected values, ordering and failures.
// Why this is not fitting the test to the implementation: observable behavior and golden vectors
// are unchanged; only equivalent API/assertion syntax and fixture construction are migrated.
//
/*
 * Behavior Contract:
 * - Unit under test: validate_ndk_directory.
 * - Owning layer: quality orchestration.
 * - Priority tier: P0.
 * - Capability: verify Android NDK directory contains valid source.properties matching pinned version.
 *
 * Scenarios:
 * - Given an NDK directory with matching Pkg.Revision, when validated, then succeeds.
 * - Given an NDK directory with mismatched Pkg.Revision, when validated, then returns descriptive version mismatch error.
 * - Given an NDK directory missing source.properties, when validated, then returns missing source.properties error.
 *
 * Observable outcomes:
 * - Returns Ok(()) when Pkg.Revision matches 30.0.16248370.
 * - Returns Err with version mismatch message when Pkg.Revision does not match.
 * - Returns Err with missing source.properties message when source.properties does not exist.
 *
 * TDD proof:
 * - Relocated from crates/lomo-xtask/src/workspace.rs to satisfy external Rust test architecture rule.
 *
 * Excludes:
 * - Full Android build and SDK manager network operations.
 */

#[cfg(test)]
mod tests {
    use std::{env, fs};

    use anyhow::{Result, bail};
    use lomo_xtask::validate_ndk_directory;

    #[cfg(unix)]
    #[test]
    fn ci_native_requests_the_pinned_ndk_on_a_clean_sdk() -> Result<()> {
        use std::{os::unix::fs::PermissionsExt as _, process::Command};

        use anyhow::{Context as _, ensure};

        let directory = tempfile::tempdir()?;
        let sdkmanager = directory.path().join("cmdline-tools/latest/bin/sdkmanager");
        let bin = sdkmanager.parent().context("sdkmanager directory")?;
        fs::create_dir_all(bin)?;
        fs::write(
            &sdkmanager,
            "#!/bin/sh\n[ \"$1\" != --version ] || exit 0\nprintf '%s\\n' \"$@\" > \"$ANDROID_HOME/requested\"\nexit 7\n",
        )?;
        fs::set_permissions(&sdkmanager, fs::Permissions::from_mode(0o755))?;
        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["ci-native", "release", "arm64"])
            .env("LOMO_KOTLIN_ANDROID_SDK", directory.path())
            .env_remove("ANDROID_NDK_HOME")
            .env_remove("ANDROID_NDK_ROOT")
            .output()?;
        ensure!(
            !output.status.success(),
            "failed sdkmanager must block native build"
        );
        let request = fs::read_to_string(directory.path().join("requested"))
            .with_context(|| String::from_utf8_lossy(&output.stderr).into_owned())?;
        ensure!(request.trim() == format!("ndk;{}", lomo_xtask::NDK_VERSION));
        Ok(())
    }

    #[test]
    fn test_validate_ndk_version_valid() -> Result<()> {
        let path = env::temp_dir().join(format!("lomo_ndk_test_valid_{}", std::process::id()));
        if path.exists() {
            fs::remove_dir_all(&path)?;
        }
        fs::create_dir_all(&path)?;
        let props = path.join("source.properties");
        fs::write(&props, "Pkg.Revision = 30.0.16248370\n")?;
        let res = validate_ndk_directory(&path);
        let _clean: std::io::Result<()> = fs::remove_dir_all(&path);
        res?;
        Ok(())
    }

    #[test]
    fn test_validate_ndk_version_invalid() -> Result<()> {
        let path = env::temp_dir().join(format!("lomo_ndk_test_invalid_{}", std::process::id()));
        if path.exists() {
            fs::remove_dir_all(&path)?;
        }
        fs::create_dir_all(&path)?;
        let props = path.join("source.properties");
        fs::write(&props, "Pkg.Revision = 28.0.12433566\n")?;
        let err = match validate_ndk_directory(&path) {
            Ok(()) => bail!("expected version validation failure"),
            Err(e) => e,
        };
        let _clean: std::io::Result<()> = fs::remove_dir_all(&path);
        let msg = err.to_string();
        if !msg.contains("has version 28.0.12433566, expected 30.0.16248370") {
            bail!("unexpected error message: {msg}");
        }
        Ok(())
    }

    #[test]
    fn test_validate_ndk_version_missing_properties() -> Result<()> {
        let path = env::temp_dir().join(format!("lomo_ndk_test_missing_{}", std::process::id()));
        if path.exists() {
            fs::remove_dir_all(&path)?;
        }
        fs::create_dir_all(&path)?;
        let err = match validate_ndk_directory(&path) {
            Ok(()) => bail!("expected missing source.properties failure"),
            Err(e) => e,
        };
        let _clean: std::io::Result<()> = fs::remove_dir_all(&path);
        let msg = err.to_string();
        if !msg.contains("missing source.properties") {
            bail!("unexpected error message: {msg}");
        }
        Ok(())
    }
}
