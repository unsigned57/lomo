//! Behavior Contract:
//! Capability: `just android benchmark` scenario resolution, fail-closed argument surface and
//! the wiring that maps each scenario name onto one `LomoMacrobenchmark` `JUnit` method in the
//! `apps/android/benchmark` instrumented-test module.
//! Owning layer: lomo-xtask android command surface; priority: P0 (measurement-facility wiring).
//!
//! Scenarios:
//! - Given no scenario, `all`, or the recipe's default ABI token in the scenario slot, when
//!   `benchmark_scenarios` parses, then all three scenarios resolve.
//! - Given `startup`, `frame` or `scroll`, when parsed, then exactly that scenario resolves.
//! - Given an unknown scenario name through the CLI, when dispatched, then the command fails
//!   closed before any device or metric work — never inventing a benchmark run.
//! - Given the harness module, when its sources are inspected, then every
//!   `BenchmarkScenario::name()` is a `@Test` method in `LomoMacrobenchmark`, the module pins
//!   `androidx.benchmark:benchmark-macro-junit4`, and EMULATOR+DEBUGGABLE suppressions are
//!   declared so emulator evidence can never masquerade as physical-device truth.
//! - Given `ci-android`, when the Android gate runs, then the harness androidTest APK is
//!   assembled — the compile-level half of the gate, since CI has no benchmark device.
//!
//! Observable outcomes: parsed scenario lists, CLI non-zero exits for bad arguments, and the
//! source-level wiring pins between xtask, Gradle module and `JUnit` methods.
//!
//! TDD proof: `cargo test -p lomo-xtask --test android_benchmark_contract --locked`.
//! RED: before P-60 `benchmark_scenarios` did not exist and the CLI had no `benchmark`
//! argument; the scenario/method-name pin fails if either side is renamed alone.
//! Excludes: attached-device execution and metric values (covered by `just android
//! benchmark` runs that emit `android-benchmark.v1.json`).

#[cfg(test)]
mod tests {
    use std::{fs, path::Path, process::Command};

    use anyhow::{Context, Result, ensure};
    use lomo_xtask::{BenchmarkScenario, benchmark_scenarios, repository_root};

    fn read(path: &Path) -> Result<String> {
        fs::read_to_string(path).with_context(|| format!("read {}", path.display()))
    }

    #[test]
    fn scenario_slot_resolves_all_names_and_recipe_abi_token() -> Result<()> {
        let all = BenchmarkScenario::ALL.to_vec();
        ensure!(all.len() == 3);
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
        ] {
            let parsed = benchmark_scenarios(Some(&name.to_owned()))?;
            ensure!(parsed == vec![expected]);
            ensure!(
                expected.name() == name,
                "scenario name must equal the JUnit method"
            );
        }
        ensure!(
            benchmark_scenarios(Some(&"bogus-scenario".to_owned())).is_err(),
            "unknown scenario must fail closed"
        );
        Ok(())
    }

    #[test]
    fn cli_rejects_unknown_scenario_before_device_work() -> Result<()> {
        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["android", "benchmark", "bogus-scenario"])
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
        Ok(())
    }

    #[test]
    fn harness_module_wires_every_scenario_to_a_junit_method() -> Result<()> {
        let root = repository_root()?;
        let harness = root.join("apps/android/benchmark");
        let java =
            read(&harness.join("src/androidTest/java/com/lomo/benchmark/LomoMacrobenchmark.java"))?;
        for scenario in BenchmarkScenario::ALL {
            let method = format!("public void {}()", scenario.name());
            ensure!(
                java.contains(&method),
                "LomoMacrobenchmark is missing @Test method {method}"
            );
        }
        ensure!(
            java.contains("TARGET_PACKAGE = \"com.lomo.app\""),
            "harness must measure the production package"
        );

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
        ] {
            ensure!(
                android.contains(pin),
                "android.rs missing benchmark wiring: {pin}"
            );
        }
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
