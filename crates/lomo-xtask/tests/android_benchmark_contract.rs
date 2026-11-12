//! Test Change Justification:
//! Reason category: user-requested Android task page removal.
//! Old behavior/assertion being replaced: tasksOpen was a supported and required CUJ.
//! Why old assertion is no longer correct: that Android screen no longer exists.
//! Coverage preserved by: every remaining scenario still resolves to its real journey.
//! Why this is not fitting the test to the implementation: the user removed the measured feature.
//! Test Change Justification:
//! Reason category: profileable release anchors replace window-only readiness.
//! Old behavior/assertion being replaced: release startup accepted any target package window.
//! Why old assertion is no longer correct: onboarding is not populated-workspace readiness,
//! and scrolling an empty root does not measure the requested interaction.
//! Coverage preserved by: main-root/card anchors, an actual first-card scroll witness,
//! SDK release debuggable validation and compilation-mode/profile collection pins.
//! The shared scroll helper is pinned at both callers and its container/displacement
//! boundaries; the assertion does not depend on a nullable local variable's spelling.
//! Why this is not fitting the test to the implementation: stricter semantic outcomes reject
//! the empty-screen benchmark accepted by the old package-window fallback.
//!
//! Behavior Contract:
//!
//! Test Change Justification (workspace first index):
//! Reason category: new explicit performance scenario requested for fresh indexing.
//! Old behavior/assertion being replaced: the named scenario inventory has 33 entries.
//! Why old assertion is no longer correct: workspaceFirstIndex and workspaceReopen add real five-iteration
//! fresh-store/content-ready scenarios, outside the default sweeps.
//! Coverage preserved by: parsing every named scenario and verifying its harness method.
//! Why this is not fitting the test to the implementation: the new scenario measures the
//! user-visible grant-to-indexed-content interval independently of picker navigation.
//! Capability: `just build android benchmark` scenario resolution, fail-closed argument surface and
//! the wiring that maps each scenario name onto one `LomoMacrobenchmark` `JUnit` method in the
//! `apps/android/benchmark` instrumented-test module.
//! Owning layer: lomo-xtask android command surface; priority: P0 (measurement-facility wiring).
//!
//! Scenarios:
//! - Given no scenario, `all`, or the recipe's default ABI token in the scenario slot, when
//!   `benchmark_scenarios` parses, then the original seven-scenario sweep resolves
//!   (`ALL` intentionally excludes the `perf device`-only scenarios).
//! - Given `startup`, `frame`, `scroll`, `create`, `expand`, `menu` or `search`, when parsed,
//!   then exactly that scenario resolves.
//! - Given any device-plane name (`startupWarm`, `startupHot`, `startupDirty`,
//!   `startupOnboarding`, `drawer`, `filterSort`, `scrollTop`, `tag`, `trash`,
//!   `versionHistory`, `settings`, `searchClear`, `memoActions`, plus the §3-C set
//!   `editorOpen`, `reviewOpen`, `statsOpen`, `galleryScroll`, `reelOpen`,
//!   `imageViewerOpen`, `syncCenterOpen`, `shareCardRender`, `conflictDialogShow`,
//!   `widgetRefresh`, `lanShareOpen`), when parsed, then exactly that scenario resolves —
//!   every named scenario is also runnable through `build android benchmark <name>`.
//! - Given an unknown scenario name through the CLI, when dispatched, then the command fails
//!   closed before any device or metric work — never inventing a benchmark run.
//! - Given the harness module, when its sources are inspected, then every
//!   `BenchmarkScenario::name()` is a `@Test` method in `LomoMacrobenchmark`, the module pins
//!   `androidx.benchmark:benchmark-macro-junit4`, and EMULATOR+DEBUGGABLE suppressions are
//!   declared so emulator evidence can never masquerade as physical-device truth.
//! - Given `ci-android`, when the Android gate runs, then the harness androidTest APK is
//!   assembled — the compile-level half of the gate, since CI has no benchmark device.
//! - Given a completed benchmark, when raw single-value metrics omit iterations, then
//!   summarization rejects partial evidence; a complete sample set remains accepted.
//! - Given an installer returning success without creating app data, when installation
//!   completes, then the boundary rejects it before running any benchmark. Both CE and DE
//!   directories must exist; a real ready install remains accepted.
//!
//! Observable outcomes: parsed scenario lists, CLI non-zero exits for bad arguments, and the
//! source-level wiring pins between xtask, Gradle module and `JUnit` methods.
//!
//! TDD proof: `cargo test -p lomo-xtask --test android_benchmark_contract --locked`.
//! RED: before P-60 `benchmark_scenarios` did not exist and the CLI had no `benchmark`
//! argument; the scenario/method-name pin fails if either side is renamed alone.
//! Excludes: attached-device execution and metric values (covered by `just build android
//! benchmark` runs that emit `android-benchmark.v1.json`).
//!
//! Test Change Justification:
//! Reason category: unified application build entrypoint (`just build <app>`).
//! Old behavior/assertion being replaced: CLI dispatch through the retired top-level
//! `android` command (`["android", "benchmark", ...]`).
//! Why old assertion is no longer correct: the `android` command merged into `build android`.
//! Coverage preserved by: the same unknown-scenario rejection now drives `build android
//! benchmark`; scenario/method wiring pins are unchanged.
//! Why this is not fitting the test to the implementation: the scenario surface and its
//! fail-closed evidence contract are identical; only the dispatch prefix moved.

#[cfg(test)]
mod tests {
    use std::{fs, path::Path, process::Command};

    use anyhow::{Context, Result, ensure};
    use lomo_xtask::{
        BenchmarkScenario, adb_install, benchmark_scenarios, repository_root,
        summarize_benchmark_files,
    };

    #[cfg(unix)]
    #[test]
    fn successful_install_requires_real_app_data_directories() -> Result<()> {
        use std::os::unix::fs::PermissionsExt as _;
        let dir = tempfile::tempdir()?;
        let adb = dir.path().join("adb");
        fs::write(
            &adb,
            r#"#!/bin/sh
set -eu
TASK_FAKE_ROOT=$(dirname "$0")
case "$3" in
  install) echo Success ;;
  shell) test "$4" = test && test "$5" = -d && test -d "$TASK_FAKE_ROOT$6" ;;
  *) exit 2 ;;
esac
"#,
        )?;
        fs::set_permissions(&adb, fs::Permissions::from_mode(0o755))?;
        let apk = dir.path().join("app.apk");
        fs::write(&apk, [])?;
        for ready in [0, 1, 2] {
            if ready >= 1 {
                fs::create_dir_all(dir.path().join("data/user/0/com.lomo.app"))?;
            }
            if ready == 2 {
                fs::create_dir_all(dir.path().join("data/user_de/0/com.lomo.app"))?;
            }
            let result = adb_install(&adb, "device", &apk);
            if ready == 2 {
                result?;
            } else {
                let error = result
                    .err()
                    .context("installer success accepted absent app data")?;
                ensure!(error.to_string().contains("data directory"), "{error:#}");
            }
        }
        Ok(())
    }

    #[test]
    fn benchmark_summary_rejects_missing_iteration_metrics() -> Result<()> {
        let dir = tempfile::tempdir()?;
        let path = dir.path().join("benchmarkData.json");
        for count in [2, 5] {
            let document = serde_json::json!({"benchmarks": [{
                "name": "startup", "repeatIterations": 5,
                "metrics": {"launchToReadyFirstMs": {
                    "median": 10.0, "minimum": 10.0, "maximum": 10.0,
                    "runs": vec![10.0; count],
                }},
            }]});
            fs::write(&path, serde_json::to_vec(&document)?)?;
            let result = summarize_benchmark_files(std::slice::from_ref(&path), "startup");
            if count == 5 {
                ensure!(
                    result?.pointer("/metrics/launchToReadyFirstMs/runs")
                        == Some(&serde_json::json!(5))
                );
            } else {
                let error = result.err().context("partial metrics were accepted")?;
                ensure!(error.to_string().contains("2/5"), "{error:#}");
            }
        }
        Ok(())
    }

    fn read(path: &Path) -> Result<String> {
        fs::read_to_string(path).with_context(|| format!("read {}", path.display()))
    }

    #[test]
    fn scenario_slot_resolves_all_names_and_recipe_abi_token() -> Result<()> {
        let all = BenchmarkScenario::ALL.to_vec();
        ensure!(all.len() == 7);
        for argument in [None, Some("all"), Some("arm64"), Some("x86_64")] {
            let parsed = benchmark_scenarios(argument.map(str::to_owned).as_ref())?;
            ensure!(
                parsed == all,
                "argument {argument:?} must resolve to all scenarios"
            );
        }
        for (name, expected) in [
            ("startup", BenchmarkScenario::Startup),
            ("frame", BenchmarkScenario::Frame),
            ("scroll", BenchmarkScenario::Scroll),
            ("create", BenchmarkScenario::Create),
            ("expand", BenchmarkScenario::Expand),
            ("menu", BenchmarkScenario::Menu),
            ("search", BenchmarkScenario::Search),
        ] {
            let parsed = benchmark_scenarios(Some(&name.to_owned()))?;
            ensure!(parsed == vec![expected]);
            ensure!(
                expected.name() == name,
                "scenario name must equal the JUnit method"
            );
        }
        // Device-perf scenarios are individually addressable too — the same JUnit method
        // serves `just perf device cuj` and `just build android benchmark <name>`.
        for (name, expected) in [
            ("startupWarm", BenchmarkScenario::StartupWarm),
            ("startupHot", BenchmarkScenario::StartupHot),
            ("startupDirty", BenchmarkScenario::StartupDirty),
            ("startupOnboarding", BenchmarkScenario::StartupOnboarding),
            (
                "workspaceFirstIndex",
                BenchmarkScenario::WorkspaceFirstIndex,
            ),
            ("drawer", BenchmarkScenario::Drawer),
            ("filterSort", BenchmarkScenario::FilterSort),
            ("scrollTop", BenchmarkScenario::ScrollTop),
            ("tag", BenchmarkScenario::Tag),
            ("trash", BenchmarkScenario::Trash),
            ("versionHistory", BenchmarkScenario::VersionHistory),
            ("settings", BenchmarkScenario::Settings),
            ("searchClear", BenchmarkScenario::SearchClear),
            ("memoActions", BenchmarkScenario::MemoActions),
            // C-group CUJs (device-measurement-plan §3-C) — every name below needs a
            // production anchor chain plus a harness JUnit method of the same name.
            ("editorOpen", BenchmarkScenario::EditorOpen),
            ("reviewOpen", BenchmarkScenario::ReviewOpen),
            ("statsOpen", BenchmarkScenario::StatsOpen),
            ("galleryScroll", BenchmarkScenario::GalleryScroll),
            ("reelOpen", BenchmarkScenario::ReelOpen),
            ("imageViewerOpen", BenchmarkScenario::ImageViewerOpen),
            ("syncCenterOpen", BenchmarkScenario::SyncCenterOpen),
            ("shareCardRender", BenchmarkScenario::ShareCardRender),
            ("conflictDialogShow", BenchmarkScenario::ConflictDialogShow),
            ("widgetRefresh", BenchmarkScenario::WidgetRefresh),
            ("lanShareOpen", BenchmarkScenario::LanShareOpen),
        ] {
            let parsed = benchmark_scenarios(Some(&name.to_owned()))?;
            ensure!(parsed == vec![expected]);
            ensure!(
                expected.name() == name,
                "device scenario name must equal the JUnit method"
            );
        }
        // `profileGen` is the measured baseline-profile collection entry point (P7a): it is
        // individually addressable but belongs to no sweep — `perf device profile` owns it.
        let parsed = benchmark_scenarios(Some(&"profileGen".to_owned()))?;
        ensure!(parsed == vec![BenchmarkScenario::ProfileGen]);
        for sweep in [
            BenchmarkScenario::ALL.to_vec(),
            BenchmarkScenario::DEVICE_STARTUP.to_vec(),
            BenchmarkScenario::DEVICE_CUJ.to_vec(),
        ] {
            ensure!(
                !sweep.contains(&BenchmarkScenario::ProfileGen),
                "collection must not join a measurement sweep"
            );
        }
        // `startupOnboarding` is a startup-plane scenario appended by the perf runner only
        // when a SAF corpus exists to grant — it must not join the unconditional DEVICE_*
        // constants (an onboarding measurement without a pushed corpus cannot exist) but
        // stays individually addressable for `build android benchmark`.
        for sweep in [
            BenchmarkScenario::ALL.to_vec(),
            BenchmarkScenario::DEVICE_STARTUP.to_vec(),
            BenchmarkScenario::DEVICE_CUJ.to_vec(),
        ] {
            ensure!(
                !sweep.contains(&BenchmarkScenario::StartupOnboarding),
                "onboarding is runner-appended under --corpus saf; it must not run unconditionally"
            );
        }
        // `ALL` stays the original sweep so `build android benchmark` keeps its blast radius;
        // the device planes own the expanded set.
        ensure!(
            BenchmarkScenario::NAMED.len() == 34,
            "NAMED must cover every scenario variant (34)"
        );
        ensure!(
            benchmark_scenarios(Some(&"bogus-scenario".to_owned())).is_err(),
            "unknown scenario must fail closed"
        );
        Ok(())
    }

    #[test]
    fn cli_rejects_unknown_scenario_before_device_work() -> Result<()> {
        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["build", "android", "benchmark", "bogus-scenario"])
            .output()?;
        ensure!(
            !output.status.success(),
            "bogus scenario must exit non-zero"
        );
        let stderr = String::from_utf8_lossy(&output.stderr);
        ensure!(
            stderr.contains("bogus-scenario"),
            "error must name the rejected scenario: {stderr}"
        );

        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["build", "android", "benchmark", "startup", "--bogus-flag"])
            .output()?;
        ensure!(
            !output.status.success(),
            "unknown benchmark flag must exit non-zero"
        );
        let stderr = String::from_utf8_lossy(&output.stderr);
        ensure!(
            stderr.contains("--bogus-flag"),
            "error must name the rejected flag: {stderr}"
        );
        Ok(())
    }

    #[test]
    fn harness_module_wires_every_scenario_to_a_junit_method() -> Result<()> {
        let root = repository_root()?;
        let harness = root.join("apps/android/benchmark");
        let java =
            read(&harness.join("src/androidTest/java/com/lomo/benchmark/LomoMacrobenchmark.java"))?;
        for scenario in BenchmarkScenario::NAMED {
            let method = format!("public void {}()", scenario.name());
            ensure!(
                java.contains(&method),
                "LomoMacrobenchmark is missing @Test method {method}"
            );
        }
        // The SAF injection path drives the production DocumentsUI picker through this
        // driver method — it is a @Test only because instrumentation invokes it.
        ensure!(
            java.contains("public void prepareWorkspace()"),
            "LomoMacrobenchmark is missing the DocumentsUI grant driver prepareWorkspace()"
        );
        ensure!(
            java.contains("workspaceRoot") && java.contains("DEFAULT_WORKSPACE_ROOT"),
            "dirty-start mutation must target the injected workspace root"
        );
        ensure!(
            java.contains("TARGET_PACKAGE = \"com.lomo.app\""),
            "harness must measure the production package"
        );
        // P7a/P7c wiring: the measured-profile collector and the compilation-mode arg the
        // release A/B sweep drives (`partialRequire` maps to BaselineProfileMode.Require and
        // fails closed inside androidx when no profile registered). Profileable release
        // and debug targets share main-root anchors and the indexed-card scroll witness.
        for pin in [
            "BaselineProfileRule",
            "includeInStartupProfile",
            "compilationMode",
            "partialRequire",
            "BaselineProfileMode.Require",
            "scrollMemoList(scope.getDevice())",
            "scrollMemoList(device)",
            "mainRoot.findObject(By.scrollable(true)), \"memo list is not scrollable\")",
            "Until.gone(By.res(firstCardId))",
        ] {
            ensure!(
                java.contains(pin),
                "LomoMacrobenchmark missing P7 wiring: {pin}"
            );
        }
        // `includeInStartupProfile` only renames androidx's output — a single collect emits
        // exactly one artifact. `profileGen` therefore collects twice: a launch-only journey
        // pinned `true` (`*-startup-prof.txt`, the R8 dex-layout input) and the full CUJ
        // journey pinned `false` (`*-baseline-prof.txt`, the `baseline.prof` input).
        for pin in [
            "includeInStartupProfile */ true",
            "includeInStartupProfile */ false",
        ] {
            ensure!(
                java.contains(pin),
                "LomoMacrobenchmark must run both collect flavors: {pin}"
            );
        }

        let build = read(&harness.join("build.gradle.kts"))?;
        for pin in [
            "benchmark-macro-junit4",
            "EMULATOR,DEBUGGABLE",
            "testInstrumentationRunnerArguments",
        ] {
            ensure!(
                build.contains(pin),
                "benchmark build.gradle.kts missing {pin}"
            );
        }
        ensure!(
            harness.join("settings.gradle.kts").is_file(),
            "benchmark module must be a standalone Gradle project (outside the Kotlin toolchain project)"
        );
        Ok(())
    }

    #[test]
    fn xtask_wiring_pins_class_scenarios_and_ci_assembly() -> Result<()> {
        let root = repository_root()?;
        let android = read(&root.join("crates/lomo-xtask/src/android.rs"))?;
        for pin in [
            "com.lomo.benchmark.LomoMacrobenchmark",
            "connectedDebugAndroidTest",
            "benchmark_harness_assemble",
            "android.testInstrumentationRunnerArguments.class",
            // `resolve_serial` reuses an attached device or provisions the pinned
            // redroid container before measuring.
            "resolve_serial",
            "boot_redroid",
            // Every benchmark scenario rides the frame channel — a device without the
            // SF frame-completion timeline (redroid) is refused before the APK build.
            "frame_timeline_capable",
        ] {
            ensure!(
                android.contains(pin),
                "android.rs missing benchmark wiring: {pin}"
            );
        }
        let cli = read(&root.join("crates/lomo-xtask/src/cli.rs"))?;
        ensure!(
            cli.contains("--redroid"),
            "cli.rs must expose `--redroid` for benchmark container boot"
        );
        // The adb→scenario→JUnit dispatch builds `-P...class={CLASS}#{scenario.name()}`;
        // renaming either side without the other breaks instrumentation selection.
        ensure!(
            android.contains("scenario.name()"),
            "scenario dispatch must forward BenchmarkScenario::name()"
        );

        let quality = read(&root.join("crates/lomo-xtask/src/quality.rs"))?;
        ensure!(
            quality.contains("benchmark_harness_assemble"),
            "android_ci must assemble the benchmark harness for compile-level CI evidence"
        );
        Ok(())
    }
}
