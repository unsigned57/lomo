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
 * - Returns Ok(()) when Pkg.Revision matches 29.0.14206865.
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
    use std::env;
    use std::fs;

    use anyhow::{Result, bail};
    use lomo_xtask::validate_ndk_directory;

    #[test]
    fn test_validate_ndk_version_valid() -> Result<()> {
        let path = env::temp_dir().join(format!("lomo_ndk_test_valid_{}", std::process::id()));
        if path.exists() {
            fs::remove_dir_all(&path)?;
        }
        fs::create_dir_all(&path)?;
        let props = path.join("source.properties");
        fs::write(&props, "Pkg.Revision = 29.0.14206865\n")?;
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
        if !msg.contains("has version 28.0.12433566, expected 29.0.14206865") {
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
