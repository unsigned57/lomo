//! Behavior Contract:
//! Capability: one JDK/SDK/NDK/serialization declaration site with every copy verified;
//! owning layer: lomo-xtask; priority: P0.
//! Scenarios:
//! - Given the live repository, when checked, then every pin copy equals its declaration site.
//! - Given a drifted or missing module/workflow copy, when checked, then the violation names
//!   the location, pin, found and expected literals.
//! - Given unreadable/malformed manifests or a workflow set with no JDK/NDK copies, when
//!   checked, then the gate fails closed instead of passing vacuously.
//! - Given the verification DAG, when planning check/dev/ci scopes, then
//!   `kotlin-toolchain-check` is scheduled exactly when its inputs can be in scope.
//!
//! Observable outcomes: per-pin violation lists, error causes, scheduled task sets, CLI JSON.
//! TDD proof: `cargo test -p lomo-xtask --test toolchain_parity_contract --locked`;
//! RED: no such command or task existed and drifting copies passed; GREEN: same fixtures
//! report violations and the live repository passes.
//! Excludes: Gradle/Amper execution, NDK installation state and release packaging.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "fixtures pin exact YAML shapes and fail closed"
)]
mod tests {
    use std::collections::BTreeSet;
    use std::fs;
    use std::path::{Path, PathBuf};

    use anyhow::{Result, ensure};
    use lomo_xtask::verification::{
        ChangeInventory, ImpactGraph, PlanMode, TaskAction, VerificationPlan,
    };
    use lomo_xtask::{ANDROID_API, NDK_VERSION};

    fn root() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../..")
            .canonicalize()
            .expect("repository root")
    }

    const APP_MANIFEST: &str = "\
product: android/app

settings:
  junit: junit-5
  android:
    namespace: com.lomo.app
    minSdk: 26
    compileSdk: 37
    targetSdk: 37
  jvm:
    release: 25
    jdk:
      version: 27
      selectionMode: javaHome
  kotlin:
    allWarningsAsErrors: true
    serialization:
      enabled: true
      version: 1.11.0

dependencies:
  - //data
  - org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0
";

    const MODULE_MANIFEST: &str = "\
product:
  type: lib
  platforms: [ android ]

settings:
  junit: junit-5
  android:
    namespace: com.lomo.data
    minSdk: 26
    compileSdk: 37
  jvm:
    release: 25
    jdk:
      version: 27
      selectionMode: javaHome
  kotlin:
    allWarningsAsErrors: true
    serialization:
      enabled: true
      version: 1.11.0

dependencies:
  - org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0
";

    const WORKFLOW: &str = "\
name: release
on: push
jobs:
  build:
    steps:
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '27'
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '27'
      - uses: actions/cache@v4
        with:
          path: ndk/29.0.14206865
          key: android-ndk29-cache
";

    fn fixture(files: &[(&str, &str)]) -> tempfile::TempDir {
        let dir = tempfile::tempdir().expect("fixture dir");
        for (path, content) in files {
            let absolute = dir.path().join(path);
            fs::create_dir_all(absolute.parent().expect("fixture parent")).expect("fixture mkdir");
            fs::write(&absolute, content).expect("fixture file");
        }
        dir
    }

    /// A minimal parity fixture: one canonical app manifest, one copy manifest, one workflow.
    fn parity_fixture() -> tempfile::TempDir {
        fixture(&[
            ("apps/android/project.yaml", "modules:\n  - app\n  - data\n"),
            ("apps/android/app/module.yaml", APP_MANIFEST),
            ("apps/android/data/module.yaml", MODULE_MANIFEST),
            (".github/workflows/release.yml", WORKFLOW),
        ])
    }

    fn message(error: &anyhow::Error) -> String {
        format!("{error:#}")
    }

    // -----------------------------------------------------------------
    // Live repository: GREEN baseline
    // -----------------------------------------------------------------

    #[test]
    fn repository_pins_hold_across_every_copy_site() -> Result<()> {
        let parity = lomo_xtask::check_kotlin_toolchain_parity(&root())?;
        ensure!(parity.pins.get("jvm.release").is_some_and(|v| v == "25"));
        ensure!(parity.pins.get("jdk.version").is_some_and(|v| v == "27"));
        ensure!(
            parity
                .pins
                .get("jdk.selectionMode")
                .is_some_and(|v| v == "javaHome")
        );
        ensure!(parity.pins.get("minSdk").is_some_and(|v| v == "26"));
        ensure!(parity.pins.get("compileSdk").is_some_and(|v| v == "37"));
        ensure!(parity.pins.get("targetSdk").is_some_and(|v| v == "37"));
        ensure!(
            parity
                .pins
                .get("serialization.version")
                .is_some_and(|v| v == "1.11.0")
        );
        ensure!(
            parity
                .pins
                .get("ANDROID_API")
                .is_some_and(|v| *v == ANDROID_API.to_string())
        );
        ensure!(
            parity
                .pins
                .get("NDK_VERSION")
                .is_some_and(|v| v == NDK_VERSION)
        );
        ensure!(parity.module_manifests.len() >= 6);
        ensure!(parity.workflows.len() >= 2);
        Ok(())
    }

    #[test]
    fn cli_reports_parity_as_versioned_json() -> Result<()> {
        let output = std::process::Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .arg("kotlin-toolchain-check")
            .output()?;
        ensure!(output.status.success());
        let result: serde_json::Value = serde_json::from_slice(&output.stdout)?;
        ensure!(result.pointer("/schema_version") == Some(&serde_json::json!(1)));
        ensure!(result.pointer("/status") == Some(&serde_json::json!("succeeded")));
        ensure!(result.pointer("/data/pins/jdk.version") == Some(&serde_json::json!("27")));
        Ok(())
    }

    // -----------------------------------------------------------------
    // Module manifest copies
    // -----------------------------------------------------------------

    #[test]
    fn fixture_parity_passes_with_aligned_pins() -> Result<()> {
        let dir = parity_fixture();
        let parity = lomo_xtask::check_kotlin_toolchain_parity(dir.path())?;
        ensure!(parity.module_manifests.len() == 2);
        ensure!(parity.workflows.len() == 1);
        Ok(())
    }

    #[test]
    fn drifted_jvm_release_is_a_named_violation() -> Result<()> {
        let dir = parity_fixture();
        fs::write(
            dir.path().join("apps/android/data/module.yaml"),
            MODULE_MANIFEST.replace("release: 25", "release: 24"),
        )?;
        let error = lomo_xtask::check_kotlin_toolchain_parity(dir.path())
            .expect_err("drifted jvm.release must fail");
        let report = message(&error);
        ensure!(report.contains("apps/android/data/module.yaml"));
        ensure!(report.contains("jvm.release"));
        ensure!(report.contains("\"24\""));
        ensure!(report.contains("\"25\""));
        Ok(())
    }

    #[test]
    fn missing_jvm_release_fails_closed_not_silently() -> Result<()> {
        let dir = parity_fixture();
        fs::write(
            dir.path().join("apps/android/data/module.yaml"),
            MODULE_MANIFEST.replace("    release: 25\n", ""),
        )?;
        let error = lomo_xtask::check_kotlin_toolchain_parity(dir.path())
            .expect_err("missing jvm.release must fail");
        let report = message(&error);
        ensure!(report.contains("jvm.release"), "report: {report}");
        ensure!(report.contains("<missing>"), "report: {report}");
        Ok(())
    }

    #[test]
    fn drifted_android_sdk_and_jdk_and_serialization_each_violate() -> Result<()> {
        for (before, after, pin) in [
            ("minSdk: 26", "minSdk: 24", "minSdk"),
            ("compileSdk: 37", "compileSdk: 36", "compileSdk"),
            ("version: 27", "version: 26", "jdk.version"),
            (
                "selectionMode: javaHome",
                "selectionMode: auto",
                "jdk.selectionMode",
            ),
            (
                "version: 1.11.0",
                "version: 1.10.0",
                "serialization.version",
            ),
        ] {
            let dir = parity_fixture();
            fs::write(
                dir.path().join("apps/android/data/module.yaml"),
                MODULE_MANIFEST.replace(before, after),
            )?;
            let error =
                lomo_xtask::check_kotlin_toolchain_parity(dir.path()).expect_err("drift must fail");
            ensure!(
                message(&error).contains(pin),
                "report must name {pin}: {}",
                message(&error)
            );
        }
        Ok(())
    }

    /// The serialization plugin pin and the `kotlinx-serialization-json` coordinate are one
    /// fact; a coordinate written as `{coordinate: {version: ...}}` must still be checked.
    #[test]
    fn serialization_coordinate_in_map_form_is_still_compared() -> Result<()> {
        let dir = parity_fixture();
        fs::write(
            dir.path().join("apps/android/data/module.yaml"),
            MODULE_MANIFEST.replace(
                "- org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0",
                "- org.jetbrains.kotlinx:kotlinx-serialization-json:\n      version: 9.9.9",
            ),
        )?;
        let error = lomo_xtask::check_kotlin_toolchain_parity(dir.path())
            .expect_err("map-form coordinate drift must fail");
        ensure!(message(&error).contains("kotlinx-serialization-json"));
        Ok(())
    }

    #[test]
    fn canonical_manifest_missing_a_pin_fails_closed() -> Result<()> {
        let dir = fixture(&[
            ("apps/android/project.yaml", "modules:\n  - app\n"),
            (
                "apps/android/app/module.yaml",
                &APP_MANIFEST.replace(
                    "    jdk:\n      version: 27\n      selectionMode: javaHome\n",
                    "",
                ),
            ),
            (".github/workflows/release.yml", WORKFLOW),
        ]);
        let error = lomo_xtask::check_kotlin_toolchain_parity(dir.path())
            .expect_err("canonical manifest without jdk pin must fail");
        ensure!(message(&error).contains("apps/android/app/module.yaml"));
        Ok(())
    }

    #[test]
    fn malformed_module_manifest_fails_closed() -> Result<()> {
        let dir = parity_fixture();
        fs::write(
            dir.path().join("apps/android/data/module.yaml"),
            "settings: [unclosed\n",
        )?;
        ensure!(
            lomo_xtask::check_kotlin_toolchain_parity(dir.path()).is_err(),
            "malformed YAML must fail closed"
        );
        Ok(())
    }

    #[test]
    fn project_manifest_without_modules_list_fails_closed() -> Result<()> {
        let dir = fixture(&[
            ("apps/android/project.yaml", "modules: none\n"),
            ("apps/android/app/module.yaml", APP_MANIFEST),
            (".github/workflows/release.yml", WORKFLOW),
        ]);
        ensure!(
            lomo_xtask::check_kotlin_toolchain_parity(dir.path()).is_err(),
            "non-list modules must fail closed"
        );
        Ok(())
    }

    /// `workspace.rs` owns the native API copy: if `minSdk` drifts from `ANDROID_API`
    /// the manifest-vs-constant comparison must fire. The fixture cannot rewrite the
    /// compiled constant, so this pins the *other* direction — a canonical `minSdk`
    /// that disagrees with `ANDROID_API` is a violation, not the new authority.
    #[test]
    fn min_sdk_disagreeing_with_android_api_constant_violates() -> Result<()> {
        let dir = fixture(&[
            ("apps/android/project.yaml", "modules:\n  - app\n"),
            (
                "apps/android/app/module.yaml",
                &APP_MANIFEST.replace("minSdk: 26", "minSdk: 28"),
            ),
            (".github/workflows/release.yml", WORKFLOW),
        ]);
        let error = lomo_xtask::check_kotlin_toolchain_parity(dir.path())
            .expect_err("minSdk != ANDROID_API must fail");
        let report = message(&error);
        ensure!(report.contains("ANDROID_API"));
        ensure!(report.contains("workspace.rs"));
        Ok(())
    }

    // -----------------------------------------------------------------
    // Workflow copies
    // -----------------------------------------------------------------

    #[test]
    fn drifted_java_version_is_a_violation() -> Result<()> {
        let dir = parity_fixture();
        fs::write(
            dir.path().join(".github/workflows/release.yml"),
            WORKFLOW.replace("java-version: '27'", "java-version: '21'"),
        )?;
        let error = lomo_xtask::check_kotlin_toolchain_parity(dir.path())
            .expect_err("drifted java-version must fail");
        let report = message(&error);
        ensure!(report.contains("java-version"));
        ensure!(report.contains("\"21\""));
        Ok(())
    }

    #[test]
    fn setup_java_without_a_version_pin_is_a_violation() -> Result<()> {
        let dir = parity_fixture();
        fs::write(
            dir.path().join(".github/workflows/release.yml"),
            WORKFLOW.replacen("          java-version: '27'\n", "", 1),
        )?;
        let error = lomo_xtask::check_kotlin_toolchain_parity(dir.path())
            .expect_err("setup-java without java-version must fail");
        ensure!(message(&error).contains("setup-java"));
        Ok(())
    }

    #[test]
    fn drifted_ndk_cache_path_and_key_prefix_violate() -> Result<()> {
        for (before, after) in [
            // A synthetic version, not a historical pin — the shell contract scans the
            // worktree for retired NDK literals.
            ("ndk/29.0.14206865", "ndk/99.0.0"),
            ("ndk29-cache", "ndk28-cache"),
        ] {
            let dir = parity_fixture();
            fs::write(
                dir.path().join(".github/workflows/release.yml"),
                WORKFLOW.replace(before, after),
            )?;
            ensure!(
                lomo_xtask::check_kotlin_toolchain_parity(dir.path()).is_err(),
                "{before} -> {after} must fail"
            );
        }
        Ok(())
    }

    #[test]
    fn workflow_set_without_jdk_or_ndk_copies_fails_closed() -> Result<()> {
        let dir = fixture(&[
            ("apps/android/project.yaml", "modules:\n  - app\n"),
            ("apps/android/app/module.yaml", APP_MANIFEST),
            (
                ".github/workflows/docs.yml",
                "name: docs\non: push\njobs:\n  build:\n    steps:\n      - run: echo hi\n",
            ),
        ]);
        let error = lomo_xtask::check_kotlin_toolchain_parity(dir.path())
            .expect_err("a workflow set with no pins must not pass vacuously");
        ensure!(message(&error).contains("java-version"));
        Ok(())
    }

    #[test]
    fn missing_workflow_directory_fails_closed() -> Result<()> {
        let dir = fixture(&[
            ("apps/android/project.yaml", "modules:\n  - app\n"),
            ("apps/android/app/module.yaml", APP_MANIFEST),
        ]);
        ensure!(
            lomo_xtask::check_kotlin_toolchain_parity(dir.path()).is_err(),
            "no workflows must fail closed"
        );
        Ok(())
    }

    // -----------------------------------------------------------------
    // Verification DAG wiring
    // -----------------------------------------------------------------

    fn graph() -> Result<ImpactGraph> {
        ImpactGraph::load(&root())
    }

    fn plan(mode: PlanMode, paths: &[&str]) -> Result<VerificationPlan> {
        VerificationPlan::build(
            &graph()?,
            &ChangeInventory {
                paths: paths.iter().map(PathBuf::from).collect(),
                removed: BTreeSet::new(),
                complete: true,
            },
            None,
            mode,
        )
    }

    fn action<'a>(plan: &'a VerificationPlan, id: &str) -> Option<&'a TaskAction> {
        plan.tasks
            .iter()
            .find(|task| task.id == id)
            .map(|task| &task.action)
    }

    #[test]
    fn check_and_dev_plans_schedule_the_parity_task() -> Result<()> {
        for mode in [PlanMode::Check, PlanMode::Dev] {
            let built = plan(mode, &[])?;
            ensure!(
                matches!(
                    action(&built, "kotlin-toolchain-check"),
                    Some(TaskAction::KotlinToolchainCheck)
                ),
                "{mode:?} must schedule kotlin-toolchain-check"
            );
        }
        // A tests-only run has no room for a repository-wide pin gate.
        let tests = plan(PlanMode::Tests, &[])?;
        ensure!(
            tests
                .tasks
                .iter()
                .all(|task| task.id != "kotlin-toolchain-check")
        );
        Ok(())
    }

    #[test]
    fn parity_task_inputs_cover_manifests_and_workflows() -> Result<()> {
        let built = plan(PlanMode::Check, &[])?;
        let task = built
            .tasks
            .iter()
            .find(|task| task.id == "kotlin-toolchain-check")
            .expect("task scheduled");
        for input in [
            "apps/android/app",
            "apps/android/data",
            "apps/android/domain",
            "apps/android/ui-components",
            "apps/android/native-bindings",
            "apps/android/project.yaml",
            "crates/lomo-xtask",
        ] {
            ensure!(
                task.inputs.iter().any(|p| p == Path::new(input)),
                "inputs must cover {input}"
            );
        }
        ensure!(
            task.inputs.iter().any(|p| p == Path::new(".github")),
            "inputs must cover the workflow directory"
        );
        Ok(())
    }

    /// The task must not run for a change set that cannot touch its inputs — a pure
    /// Rust-owner edit with an incomplete inventory schedules nothing Kotlin.
    #[test]
    fn parity_task_is_scope_selected_not_unconditional() -> Result<()> {
        let built = VerificationPlan::build(
            &graph()?,
            &ChangeInventory {
                paths: BTreeSet::from([PathBuf::from("crates/lomo-git/src/lib.rs")]),
                removed: BTreeSet::new(),
                complete: false,
            },
            None,
            PlanMode::Dev,
        )?;
        ensure!(
            !built
                .tasks
                .iter()
                .any(|task| task.id == "kotlin-toolchain-check"),
            "a pure lomo-git edit must not schedule the Kotlin toolchain check"
        );
        let kotlin_edit = VerificationPlan::build(
            &graph()?,
            &ChangeInventory {
                paths: BTreeSet::from([PathBuf::from("apps/android/data/module.yaml")]),
                removed: BTreeSet::new(),
                complete: false,
            },
            None,
            PlanMode::Dev,
        )?;
        ensure!(
            kotlin_edit
                .tasks
                .iter()
                .any(|task| task.id == "kotlin-toolchain-check"),
            "a module.yaml edit must schedule the Kotlin toolchain check"
        );
        Ok(())
    }
}
