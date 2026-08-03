/*
 * Behavior Contract:
 * - Unit under test: lomo-xtask cache path resolution.
 * - Owning layer: quality orchestration.
 * - Priority tier: P0.
 * - Capability: reuse caller-owned dependency and build caches across every gate.
 *
 * Scenarios:
 * - Given standard cache environment variables, when xtask resolves cache paths, then every
 *   standard path is preserved and one stable Kotlin build directory is reported.
 * - Given an explicit LOMO Kotlin build override, when paths are resolved, then that override is
 *   the canonical build directory without changing the standard dependency caches.
 *
 * Observable outcomes:
 * - `cache paths` exits successfully and reports the resolved absolute paths.
 *
 * TDD proof:
 * - Before the fix, `cache paths` is rejected and repository-local isolated defaults remain.
 *
 * Excludes:
 * - Cache deletion, compilation performance, and external tool internals.
 */

#[cfg(test)]
mod tests {
    use std::path::Path;
    use std::process::Command;

    use anyhow::{Context, Result, bail, ensure};

    #[test]
    fn standard_cache_environment_and_shared_build_override_are_preserved() -> Result<()> {
        let fixture = Path::new("/tmp/lomo-cache-reuse-contract");
        let mut command = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"));
        command
            .args(["cache", "paths"])
            .env("HOME", fixture.join("home"))
            .env("XDG_CACHE_HOME", fixture.join("xdg-cache"))
            .env("XDG_DATA_HOME", fixture.join("xdg-data"))
            .env("XDG_CONFIG_HOME", fixture.join("xdg-config"))
            .env("ANDROID_USER_HOME", fixture.join("android"))
            .env("KOTLIN_CLI_BOOTSTRAP_CACHE_DIR", fixture.join("kotlin-cli"))
            .env("GRADLE_USER_HOME", fixture.join("gradle"))
            .env("CARGO_TARGET_DIR", fixture.join("cargo-target"))
            .env("LOMO_KOTLIN_BUILD_DIR", fixture.join("kotlin-build"));

        let output = command.output().context("run lomo-xtask cache paths")?;
        if !output.status.success() {
            bail!(
                "cache path resolution failed: {}",
                String::from_utf8_lossy(&output.stderr)
            );
        }
        let stderr = String::from_utf8(output.stderr).context("cache paths output is UTF-8")?;

        for expected in [
            "home=/tmp/lomo-cache-reuse-contract/home",
            "xdg_cache=/tmp/lomo-cache-reuse-contract/xdg-cache",
            "xdg_data=/tmp/lomo-cache-reuse-contract/xdg-data",
            "xdg_config=/tmp/lomo-cache-reuse-contract/xdg-config",
            "android_user_home=/tmp/lomo-cache-reuse-contract/android",
            "kotlin_cli_cache=/tmp/lomo-cache-reuse-contract/kotlin-cli",
            "gradle_user_home=/tmp/lomo-cache-reuse-contract/gradle",
            "cargo_target=/tmp/lomo-cache-reuse-contract/cargo-target",
            "kotlin_build=/tmp/lomo-cache-reuse-contract/kotlin-build",
        ] {
            ensure!(
                stderr.lines().any(|line| line == expected),
                "missing {expected}"
            );
        }
        Ok(())
    }
}
