//! Behavior Contract:
//! Capability: `native::ensure_android_libraries` reuses a published
//! `liblomo_native_jni.so` only when a stamped source digest proves the artifact was
//! produced from the current crate sources, the requested profile, and the exact bytes on
//! disk. A source mutation, a missing/corrupt stamp, a profile change, or a tampered
//! `.so` must all force a rebuild — file existence alone can never prove freshness.
//! Owning layer: lomo-xtask native packaging; priority: P0 (measurement-facility
//! integrity — a stale `.so` silently measures old code, audit §9.0/§9.9).
//!
//! Scenarios:
//! - Given a stamped tree whose sources are unchanged, when the reuse verdict runs, then
//!   the library is reusable and no pack is invoked.
//! - Given one line edited in a crate linked into the JNI library (a `lomo-native` path
//!   dependency), when the verdict runs, then the stamped input digest no longer matches
//!   and reuse is denied — source change ⇒ rebuild.
//! - Given a `.so` whose bytes were replaced after stamping (hand-planted or stale stash),
//!   when the verdict runs, then the output digest mismatches and reuse is denied.
//! - Given no stamp or a corrupt stamp file, when the verdict runs, then reuse is denied —
//!   unverifiable provenance always rebuilds instead of trusting whatever sits in
//!   `jniLibs/`.
//! - Given a Release request against a Dev-stamped library (or the reverse), when the
//!   verdict runs, then the profile slot mismatches and reuse is denied — a stripped
//!   shipping library can never serve a diagnosable debug build and vice versa.
//! - Given a selected ABI whose `.so` exists only in the ABI stash, when the verdict runs,
//!   then the stash copy is verified by digest like a jniLibs resident.
//! - Given a partial ABI rebuild after source changes, when its stamp is published, then
//!   other ABI artifacts keep their old provenance and cannot be reused for the new source.
//! - Given added/deleted untracked source or changed build configuration, when checked,
//!   then reuse is denied; unrelated test evidence does not invalidate production artifacts.
//! - Given changed machine-local Cargo settings, when checked, then reuse is denied.
//!
//! Observable outcomes: `android_libraries_reusable` verdicts over a fixture workspace.
//!
//! TDD proof: `cargo test -p lomo-xtask --test native_library_stamp_contract --locked`.
//! Before implementation these supplied tests did not compile (missing stamp APIs); that
//! is not behavioral RED. The pre-fix build reused an unstamped .so, recorded separately
//! in audit/performance/device-followup-2026-10-21.md; this suite supplies GREEN behavior
//! evidence for provenance, partial rebuilds and exact profile identity.
//! Local-settings RED: `cargo test -p lomo-xtask --test native_library_stamp_contract
//! local_cargo_settings_are_part_of_native_provenance --locked` failed because reuse stayed
//! true after adding .cargo/local.toml; GREEN: the same command after digesting local settings.
//! Excludes: the `BoltFFI` pack invocation itself (covered by real device builds).
//! Test Change Justification:
//! Reason category: requested output layout change.
//! Old behavior/assertion being replaced: provenance was read directly from lomo/.
//! Why old assertion is no longer correct: internal stamps now belong in lomo/build/.
//! Coverage preserved by: identical reuse, tampering, missing/corrupt stamp and profile cases.
//! Why this is not fitting the test to the implementation: the new public directory contract
//! separates build state from deliverable packages; all integrity verdicts remain unchanged.

#[cfg(test)]
mod tests {
    use std::fs;

    use anyhow::{Context, Result, ensure};
    use lomo_xtask::{
        Abi, NativeProfile, Workspace, android_libraries_reusable, write_android_libraries_stamp,
    };

    /// Minimal repository shaped like the real tree: `lomo-native` path-depends on
    /// `lomo-store`, so a source edit in the dependency must invalidate the artifact.
    /// Every input the digest contract reads exists; nothing else in the fake root does.
    fn fixture() -> Result<(tempfile::TempDir, Workspace)> {
        let temporary = tempfile::tempdir().context("tempdir")?;
        let root = temporary.path().to_path_buf();
        let write = |relative: &str, body: &str| -> Result<()> {
            let path = root.join(relative);
            if let Some(parent) = path.parent() {
                fs::create_dir_all(parent)
                    .with_context(|| format!("create {}", parent.display()))?;
            }
            fs::write(&path, body).with_context(|| format!("write {}", path.display()))
        };
        write(
            "crates/lomo-native/Cargo.toml",
            "[package]\nname = \"lomo-native\"\n\n[dependencies]\nlomo-store = { path = \"../lomo-store\" }\n",
        )?;
        write(
            "crates/lomo-native/src/lib.rs",
            "pub fn native_entry() -> u32 { 1 }\n",
        )?;
        write(
            "crates/lomo-native/boltffi.toml",
            "[package]\nname = \"lomo-native\"\n",
        )?;
        write(
            "crates/lomo-store/Cargo.toml",
            "[package]\nname = \"lomo-store\"\n",
        )?;
        write(
            "crates/lomo-store/src/lib.rs",
            "pub fn store_entry() -> u32 { 2 }\n",
        )?;
        write("Cargo.toml", "[workspace]\nmembers = []\n")?;
        write("Cargo.lock", "version = 4\n")?;
        write("quality/tools.toml", "[boltffi]\nrev = \"pinned\"\n")?;
        write("rust-toolchain.toml", "[toolchain]\nchannel = \"1.98\"\n")?;
        write(
            "crates/lomo-xtask/src/native.rs",
            "// build-flag owner stub\n",
        )?;

        let workspace = Workspace {
            root: root.clone(),
            rust: root.clone(),
            cargo_home: root.join("cargo-home"),
            rust_target: root.join("target/cargo"),
            lomo_output: root.join("target/lomo"),
            tool_root: root.join("tools"),
            android_sdk: root.join("sdk"),
            kotlin_home: root.join("kotlin-home"),
            kotlin_cache: root.join("kotlin-cache"),
            kotlin_data: root.join("kotlin-data"),
            kotlin_config: root.join("kotlin-config"),
            android_home: root.join("android-home"),
            kotlin_cli_cache: root.join("kotlin-cli-cache"),
            gradle_home: root.join("gradle-home"),
            kotlin_build: root.join("kotlin-build"),
            reports_dir: root.join("reports"),
        };
        Ok((temporary, workspace))
    }

    fn plant_library(workspace: &Workspace, abi: Abi, bytes: &[u8]) -> Result<()> {
        let dir = workspace.jni_libs().join(abi.android_name());
        fs::create_dir_all(&dir).with_context(|| format!("create {}", dir.display()))?;
        fs::write(dir.join(lomo_xtask::NATIVE_LIBRARY), bytes).context("plant .so")
    }

    #[test]
    fn stamped_tree_reuses_and_source_or_artifact_mutation_denies() -> Result<()> {
        let (_temporary, workspace) = fixture()?;
        plant_library(&workspace, Abi::X86_64, b"dev-jni-bytes")?;
        write_android_libraries_stamp(&workspace, NativeProfile::Dev, &[Abi::X86_64])?;

        ensure!(
            android_libraries_reusable(&workspace, NativeProfile::Dev, &[Abi::X86_64])?,
            "unchanged stamped tree must reuse"
        );

        // Source mutation in a linked path dependency ⇒ the artifact can no longer be
        // attributed to the current sources — the core audit defect.
        fs::write(
            workspace.root.join("crates/lomo-store/src/lib.rs"),
            "pub fn store_entry() -> u32 { 3 } // changed\n",
        )?;
        ensure!(
            !android_libraries_reusable(&workspace, NativeProfile::Dev, &[Abi::X86_64])?,
            "a source change must deny reuse"
        );
        Ok(())
    }

    #[test]
    fn artifact_or_stamp_integrity_failures_deny_reuse() -> Result<()> {
        let (_temporary, workspace) = fixture()?;
        plant_library(&workspace, Abi::X86_64, b"dev-jni-bytes")?;
        write_android_libraries_stamp(&workspace, NativeProfile::Dev, &[Abi::X86_64])?;

        // Bytes swapped after stamping ⇒ stamped output digest mismatches.
        plant_library(&workspace, Abi::X86_64, b"swapped-jni-bytes")?;
        ensure!(
            !android_libraries_reusable(&workspace, NativeProfile::Dev, &[Abi::X86_64])?,
            "tampered .so bytes must deny reuse"
        );

        // A profile change must deny reuse without any readelf probe — the stamp slot is
        // the identity, and a Dev-stamped artifact can never serve a Release pack.
        plant_library(&workspace, Abi::X86_64, b"dev-jni-bytes")?;
        write_android_libraries_stamp(&workspace, NativeProfile::Dev, &[Abi::X86_64])?;
        ensure!(
            !android_libraries_reusable(&workspace, NativeProfile::Release, &[Abi::X86_64])?,
            "profile change must deny reuse"
        );
        ensure!(
            android_libraries_reusable(&workspace, NativeProfile::Dev, &[Abi::X86_64])?,
            "the same profile must still reuse"
        );

        // Unstamped / corrupt-stamp provenance is always denied — never trust the tree.
        let stamp = workspace.build_state_dir().join("android-libraries.json");
        fs::remove_file(&stamp).context("remove stamp")?;
        ensure!(
            !android_libraries_reusable(&workspace, NativeProfile::Dev, &[Abi::X86_64])?,
            "missing stamp must deny reuse"
        );
        fs::create_dir_all(workspace.build_state_dir())?;
        fs::write(&stamp, b"{ not json")?;
        ensure!(
            !android_libraries_reusable(&workspace, NativeProfile::Dev, &[Abi::X86_64])?,
            "corrupt stamp must deny reuse"
        );
        Ok(())
    }

    #[test]
    fn stash_resident_library_verifies_by_digest() -> Result<()> {
        let (_temporary, workspace) = fixture()?;
        // Stamped while resident, then moved to the ABI stash (the transient state
        // `AbiStashGuard` leaves behind when a build never restored it).
        plant_library(&workspace, Abi::X86_64, b"dev-jni-bytes")?;
        write_android_libraries_stamp(&workspace, NativeProfile::Dev, &[Abi::X86_64])?;
        let stash_dir = workspace
            .build_state_dir()
            .join("jni-stash")
            .join(Abi::X86_64.android_name());
        fs::create_dir_all(&stash_dir)?;
        fs::rename(
            workspace
                .jni_libs()
                .join(Abi::X86_64.android_name())
                .join(lomo_xtask::NATIVE_LIBRARY),
            stash_dir.join(lomo_xtask::NATIVE_LIBRARY),
        )?;
        ensure!(
            android_libraries_reusable(&workspace, NativeProfile::Dev, &[Abi::X86_64])?,
            "a stash-resident .so with a matching digest must still reuse"
        );
        Ok(())
    }

    #[test]
    fn partial_rebuild_cannot_certify_other_abi_artifacts() -> Result<()> {
        let (_temporary, workspace) = fixture()?;
        plant_library(&workspace, Abi::Arm64, b"arm-old")?;
        plant_library(&workspace, Abi::X86_64, b"x86-old")?;
        write_android_libraries_stamp(&workspace, NativeProfile::Dev, &[Abi::Arm64, Abi::X86_64])?;
        fs::write(
            workspace.root.join("crates/lomo-store/src/lib.rs"),
            "// new source\n",
        )?;
        plant_library(&workspace, Abi::X86_64, b"x86-new")?;
        write_android_libraries_stamp(&workspace, NativeProfile::Dev, &[Abi::X86_64])?;
        ensure!(android_libraries_reusable(
            &workspace,
            NativeProfile::Dev,
            &[Abi::X86_64]
        )?);
        ensure!(!android_libraries_reusable(
            &workspace,
            NativeProfile::Dev,
            &[Abi::Arm64]
        )?);
        ensure!(!android_libraries_reusable(
            &workspace,
            NativeProfile::Dev,
            &[Abi::Arm64, Abi::X86_64]
        )?);
        Ok(())
    }

    #[test]
    fn source_membership_and_configuration_control_provenance() -> Result<()> {
        let (_temporary, workspace) = fixture()?;
        plant_library(&workspace, Abi::X86_64, b"dev-jni-bytes")?;
        write_android_libraries_stamp(&workspace, NativeProfile::Dev, &[Abi::X86_64])?;
        let added = workspace.root.join("crates/lomo-store/src/new.rs");
        fs::write(&added, "// untracked production input\n")?;
        ensure!(!android_libraries_reusable(
            &workspace,
            NativeProfile::Dev,
            &[Abi::X86_64]
        )?);
        write_android_libraries_stamp(&workspace, NativeProfile::Dev, &[Abi::X86_64])?;
        fs::remove_file(&added)?;
        ensure!(!android_libraries_reusable(
            &workspace,
            NativeProfile::Dev,
            &[Abi::X86_64]
        )?);
        write_android_libraries_stamp(&workspace, NativeProfile::Dev, &[Abi::X86_64])?;
        let tests = workspace.root.join("crates/lomo-store/tests");
        fs::create_dir_all(&tests)?;
        fs::write(tests.join("evidence.rs"), "// test evidence\n")?;
        ensure!(android_libraries_reusable(
            &workspace,
            NativeProfile::Dev,
            &[Abi::X86_64]
        )?);
        fs::write(
            workspace.root.join("quality/tools.toml"),
            "[boltffi]\nrev = \"new\"\n",
        )?;
        ensure!(!android_libraries_reusable(
            &workspace,
            NativeProfile::Dev,
            &[Abi::X86_64]
        )?);
        Ok(())
    }

    #[test]
    fn local_cargo_settings_are_part_of_native_provenance() -> Result<()> {
        let (_temporary, workspace) = fixture()?;
        plant_library(&workspace, Abi::X86_64, b"dev-jni-bytes")?;
        write_android_libraries_stamp(&workspace, NativeProfile::Dev, &[Abi::X86_64])?;
        fs::create_dir_all(workspace.root.join(".cargo"))?;
        fs::write(
            workspace.root.join(".cargo/local.toml"),
            "[build]\nrustflags = [\"-Copt-level=1\"]\n",
        )?;
        ensure!(
            !android_libraries_reusable(&workspace, NativeProfile::Dev, &[Abi::X86_64])?,
            "changed local compiler settings must invalidate native provenance"
        );
        Ok(())
    }

    #[test]
    fn release_and_release_ci_have_distinct_provenance() -> Result<()> {
        let (_temporary, workspace) = fixture()?;
        plant_library(&workspace, Abi::X86_64, b"shipping-bytes")?;
        write_android_libraries_stamp(&workspace, NativeProfile::ReleaseCi, &[Abi::X86_64])?;
        ensure!(android_libraries_reusable(
            &workspace,
            NativeProfile::ReleaseCi,
            &[Abi::X86_64]
        )?);
        ensure!(!android_libraries_reusable(
            &workspace,
            NativeProfile::Release,
            &[Abi::X86_64]
        )?);
        Ok(())
    }
}
