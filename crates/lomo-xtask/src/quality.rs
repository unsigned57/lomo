use anyhow::{Context, Result, bail};

use crate::{
    native::{self, NativeProfile},
    tools,
    util::{cargo, kotlin, policy_script, repository_command, run},
    workspace::Workspace,
};

const TEST_MODULES: [&str; 5] = ["app", "data", "detekt-rules", "domain", "ui-components"];
const RUST_COVERAGE_MINIMUM: u32 = 70;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FormatMode {
    Staged,
    All,
    Check,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CoverageMode {
    /// Run production tests without instrumented coverage collection.
    Off,
    /// Run llvm-cov / `JaCoCo` fail-under gates.
    On,
}

pub fn format(workspace: &Workspace, mode: FormatMode) -> Result<()> {
    let mut rust = cargo(workspace);
    rust.args(["fmt", "--all"]);
    if mode == FormatMode::Check {
        rust.args(["--", "--check"]);
    }
    run(&mut rust)?;

    let kotlin_mode = match mode {
        FormatMode::Staged => "staged",
        FormatMode::All => "all",
        FormatMode::Check => return Ok(()),
    };
    let mut kotlin_format = policy_script(workspace, "quality/scripts/kotlin_detekt_format.sh");
    kotlin_format.arg(kotlin_mode);
    run(&mut kotlin_format)
}

pub use crate::verification::ChangeSource;

pub fn preflight(workspace: &Workspace, source: &ChangeSource) -> Result<serde_json::Value> {
    crate::cache::run_cache(workspace, crate::cache::CacheMode::Prune)?;
    crate::verification::run(
        workspace,
        source,
        None,
        crate::verification::PlanMode::Dev,
        false,
    )
}

pub fn check(workspace: &Workspace) -> Result<serde_json::Value> {
    crate::verification::run(
        workspace,
        &ChangeSource::All,
        None,
        crate::verification::PlanMode::Check,
        false,
    )
}

pub fn ci(workspace: &Workspace) -> Result<serde_json::Value> {
    tools::ensure_quality(workspace)?;
    let verification = check(workspace)?;
    rust_full_gate(workspace, CoverageMode::On)?;
    native::generate_all(workspace, NativeProfile::Release)?;
    kotlin_gate(
        workspace,
        KotlinGateOptions {
            compose: true,
            coverage: CoverageMode::On,
        },
    )?;
    let apk = crate::android::validate_built_apk(
        workspace,
        &workspace.kotlin_build,
        false,
        &native::Abi::ALL,
    )?;
    let published = crate::android::publish_apk(workspace, &apk, "debug", "all")?;
    crate::util::emit_stderr(format_args!("xtask: ci complete"));
    Ok(serde_json::json!({"verification": verification, "apk": published}))
}

pub fn rust_ci(workspace: &Workspace, coverage: CoverageMode) -> Result<()> {
    tools::ensure_quality(workspace)?;
    rust_full_gate(workspace, coverage)
}

pub fn android_ci(workspace: &Workspace, coverage: CoverageMode) -> Result<()> {
    tools::ensure_quality(workspace)?;
    native::generate_bindings(workspace)?;
    // android_ci consumes whatever is currently packaged; treat as shipping-class so size
    // honesty fails closed if Dev unstripped artifacts remain in app/jniLibs.
    native::verify_native_tree(workspace, &native::Abi::ALL, NativeProfile::Release)?;
    kotlin_gate(
        workspace,
        KotlinGateOptions {
            compose: true,
            coverage,
        },
    )?;
    let apk = crate::android::validate_built_apk(
        workspace,
        &workspace.kotlin_build,
        false,
        &native::Abi::ALL,
    )?;
    crate::android::publish_apk(workspace, &apk, "debug", "all")?;
    Ok(())
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct KotlinGateOptions {
    compose: bool,
    coverage: CoverageMode,
}

fn rust_fast_gate(workspace: &Workspace) -> Result<()> {
    format(workspace, FormatMode::Check)?;
    let mut clippy = cargo(workspace);
    clippy.args([
        "clippy",
        "--workspace",
        "--all-targets",
        "--all-features",
        "--locked",
        "--",
        "-D",
        "warnings",
    ]);
    run(&mut clippy)?;
    // rustdoc::broken_intra_doc_links is denied at the workspace level; documentation links
    // must be resolvable or the gate fails.
    let mut docs = cargo(workspace);
    docs.args([
        "doc",
        "--workspace",
        "--no-deps",
        "--all-features",
        "--locked",
    ]);
    run(&mut docs)?;
    rust_tests(workspace)?;
    workspace_property_fuzz(workspace)?;
    let mut machete = repository_command(workspace, workspace.tool_bin().join("cargo-machete"));
    machete.current_dir(&workspace.rust).arg(".");
    run(&mut machete)
}

fn workspace_property_fuzz(workspace: &Workspace) -> Result<()> {
    let mut fuzz = cargo(workspace);
    fuzz.args([
        "run",
        "--locked",
        "-p",
        "lomo-workspace",
        "--example",
        "workspace_property_fuzz",
        "--",
        "--seed",
        "20260720",
        "--cases",
        "10000",
    ]);
    run(&mut fuzz)
}

fn rust_full_gate(workspace: &Workspace, coverage: CoverageMode) -> Result<()> {
    rust_fast_gate(workspace)?;
    let mut deny = cargo(workspace);
    deny.args(["deny", "check"]);
    run(&mut deny)?;

    if coverage == CoverageMode::Off {
        return Ok(());
    }

    let coverage_minimum = RUST_COVERAGE_MINIMUM.to_string();
    let mut coverage_cmd = cargo(workspace);
    coverage_cmd.args([
        "llvm-cov",
        "--workspace",
        "--all-features",
        "--locked",
        "--exclude",
        "lomo-xtask",
        "--exclude",
        "lomo-architecture-tests",
        "--fail-under-lines",
        &coverage_minimum,
    ]);
    run(&mut coverage_cmd)
}

fn rust_tests(workspace: &Workspace) -> Result<()> {
    let mut nextest = cargo(workspace);
    nextest.args([
        "nextest",
        "run",
        "--workspace",
        "--all-features",
        "--locked",
    ]);
    run(&mut nextest)?;

    let mut docs = cargo(workspace);
    docs.args(["test", "--workspace", "--doc", "--all-features", "--locked"]);
    run(&mut docs)
}

fn kotlin_gate(workspace: &Workspace, options: KotlinGateOptions) -> Result<()> {
    let mut model = kotlin(workspace)?;
    model.args(["show", "modules"]);
    run(&mut model)?;

    let mut build = kotlin(workspace)?;
    build
        .arg("build")
        .arg("--build-dir")
        .arg(&workspace.kotlin_build);
    run(&mut build)?;

    for script in [
        "quality/scripts/kotlin_detekt_check.sh",
        "quality/scripts/kotlin_test_style_check.sh",
    ] {
        run_policy(workspace, script)?;
    }
    run_lint_policy(workspace)?;
    if options.compose {
        run_policy(
            workspace,
            "quality/scripts/kotlin_compose_static_analysis.sh",
        )?;
    }
    for script in [
        "quality/scripts/check_meaningful_tests.sh",
        "quality/scripts/check_string_resource_parity.sh",
        "quality/scripts/test/android_runtime_dependency_boundary_contract_test.sh",
        "quality/scripts/test/kotlin_quality_check_contract_test.sh",
    ] {
        run_policy(workspace, script)?;
    }
    if options.coverage == CoverageMode::On {
        run_policy(workspace, "quality/scripts/kotlin_coverage_check.sh")
    } else {
        kotlin_tests(workspace)
    }
}

fn kotlin_tests(workspace: &Workspace) -> Result<()> {
    if workspace.kotlin_build.as_os_str().is_empty() {
        bail!("Kotlin build directory must not be empty");
    }
    let mut command = kotlin(workspace)?;
    command.arg("test");
    for module in TEST_MODULES {
        command.arg(format!("--include-module={module}"));
    }
    command.arg("--build-dir").arg(&workspace.kotlin_build);
    run(&mut command)
}

fn run_policy(workspace: &Workspace, script: &str) -> Result<()> {
    let mut command = policy_script(workspace, script);
    command
        .env("LOMO_KOTLIN_BUILD_DIR", &workspace.kotlin_build)
        .env("LOMO_LINT_BUILD_DIR", &workspace.kotlin_build)
        .env("LOMO_COMPOSE_BUILD_DIR", &workspace.kotlin_build)
        .env("LOMO_COVERAGE_BUILD_DIR", &workspace.kotlin_build);
    run(&mut command)
}

/// Android Lint needs the app version facts; xtask reads them from `app/module.yaml` so the
/// script never carries a second copy of version/SDK numbers.
pub fn run_lint_policy(workspace: &Workspace) -> Result<()> {
    let metadata = crate::android::AppMetadata::load(workspace)?;
    let mut command = policy_script(workspace, "quality/scripts/kotlin_android_lint_check.sh");
    command
        .env("LOMO_KOTLIN_BUILD_DIR", &workspace.kotlin_build)
        .env("LOMO_LINT_BUILD_DIR", &workspace.kotlin_build)
        .env("LOMO_APP_VERSION_CODE", metadata.version_code.to_string())
        .env("LOMO_APP_VERSION_NAME", &metadata.version)
        .env("LOMO_APP_MIN_SDK", metadata.min_sdk.to_string())
        .env("LOMO_APP_TARGET_SDK", metadata.target_sdk.to_string())
        .env("LOMO_APP_COMPILE_SDK", metadata.compile_sdk.to_string());
    run(&mut command)
}

pub fn run_shell_contracts(workspace: &Workspace) -> Result<()> {
    for script in [
        "quality/scripts/test/android_runtime_dependency_boundary_contract_test.sh",
        "quality/scripts/test/kotlin_quality_check_contract_test.sh",
        "quality/scripts/check_string_resource_parity.sh",
    ] {
        run_policy(workspace, script)
            .with_context(|| format!("shell contract failed: {script}"))?;
    }
    Ok(())
}
