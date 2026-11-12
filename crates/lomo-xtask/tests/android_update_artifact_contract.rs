//! Behavior Contract:
//! Capability: universal release packages are discoverable by installed Android updaters;
//! owning layer: lomo-xtask packaging; priority: P0.
//! Scenarios:
//! - Given a universal release, when published, then its name contains the manifest version
//!   and version code in the installed updater's format and its bytes are preserved.
//! - Given split or debug packages, when published, then ABI/variant identity stays explicit.
//!
//! Observable outcomes: actual published filename and bytes.
//! TDD proof: `cargo test -p lomo-xtask --test android_update_artifact_contract --locked`;
//! RED: universal release is Lomo-2.0.0-all.apk, which v1.6.2 cannot discover;
//! GREEN: lomo-v2.0.0-vc47.apk, with no change to split/debug names.
//! Excludes: signature trust, HTTP release discovery and Android installation.

#[cfg(test)]
mod tests {
    use std::fs;

    use anyhow::{Result, ensure};
    use lomo_xtask::{Workspace, publish_apk};

    #[test]
    fn universal_release_is_discoverable_without_losing_split_identity() -> Result<()> {
        let temporary = tempfile::tempdir()?;
        let mut workspace = Workspace::discover()?;
        workspace.root = temporary.path().to_path_buf();
        workspace.lomo_output = temporary.path().join("target/lomo");
        let module = workspace.root.join("apps/android/app");
        fs::create_dir_all(module.join("res/values"))?;
        fs::write(
            module.join("module.yaml"),
            "settings:\n  android:\n    versionName: 2.0.0\n    versionCode: 47\n    minSdk: 26\n    targetSdk: 37\n    compileSdk:\n      apiLevel: 37\n      minorApiLevel: 2\n",
        )?;
        fs::write(
            module.join("res/values/strings.xml"),
            "<string name=\"app_name\">Lomo</string>\n",
        )?;
        let source = temporary.path().join("validated.apk");
        fs::write(&source, b"validated package bytes")?;
        for (variant, abi, expected) in [
            ("release", "all", "lomo-v2.0.0-vc47.apk"),
            ("release", "arm64-v8a", "Lomo-2.0.0-arm64-v8a.apk"),
            ("debug", "all", "Lomo-2.0.0-all.apk"),
        ] {
            let published = publish_apk(&workspace, &source, variant, abi)?;
            ensure!(
                published.file_name().and_then(|name| name.to_str()) == Some(expected),
                "{variant}/{abi} must publish {expected}, got {}",
                published.display()
            );
            ensure!(
                fs::read(published)? == b"validated package bytes",
                "published bytes changed"
            );
        }
        Ok(())
    }
}
