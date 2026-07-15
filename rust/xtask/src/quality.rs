use std::collections::BTreeSet;
use std::path::Path;
use std::process::Command;

use anyhow::{Context, Result, bail};

use crate::native::{self, NativeProfile};
use crate::tools;
use crate::util::{cargo, kotlin, policy_script, repository_command, run, text_output};
use crate::workspace::Workspace;

const TEST_MODULES: [&str; 5] = ["app", "data", "detekt-rules", "domain", "ui-components"];
const RUST_COVERAGE_MINIMUM: u32 = 80;

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

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[allow(
    clippy::struct_excessive_bools,
    reason = "orthogonal path-class flags for gate selection"
)]
struct ChangeSet {
    rust: bool,
    kotlin: bool,
    native: bool,
    quality_infra: bool,
    docs_only: bool,
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

pub fn test(workspace: &Workspace) -> Result<()> {
    tools::ensure_quality(workspace)?;
    rust_tests(workspace)?;
    native::generate_all(workspace, NativeProfile::Dev)?;
    kotlin_tests(workspace, ".kotlin/toolchain-build/test")
}

/// Path-aware commit gate used by pre-commit. Never weaker than the contracts that
/// staged paths can break; skips unrelated multi-minute surfaces.
pub fn preflight(workspace: &Workspace) -> Result<()> {
    tools::ensure_quality(workspace)?;
    let changes = classify_changes(workspace, ChangeSource::Staged)?;
    eprintln!(
        "xtask: preflight rust={} kotlin={} native={} quality_infra={} docs_only={}",
        changes.rust, changes.kotlin, changes.native, changes.quality_infra, changes.docs_only
    );

    if changes.docs_only && !changes.quality_infra {
        run_shell_contracts(workspace)?;
        eprintln!("xtask: preflight complete (docs-only)");
        return Ok(());
    }

    if changes.rust || changes.quality_infra {
        rust_fast_gate(workspace)?;
    }

    if changes.native || (changes.kotlin && !changes.rust) || changes.quality_infra {
        // Kotlin packaging and native contracts need generated bindings/libs.
        native::generate_all(workspace, NativeProfile::Dev)?;
    } else if changes.kotlin {
        native::generate_bindings(workspace)?;
    }

    if changes.kotlin || changes.quality_infra {
        kotlin_gate(
            workspace,
            KotlinGateOptions {
                compose: false,
                coverage: CoverageMode::Off,
                build_dir: ".kotlin/toolchain-build/preflight",
            },
        )?;
    } else if changes.quality_infra || changes.native {
        run_shell_contracts(workspace)?;
    }

    eprintln!("xtask: preflight complete");
    Ok(())
}

pub fn check(workspace: &Workspace) -> Result<()> {
    tools::ensure_quality(workspace)?;
    rust_fast_gate(workspace)?;
    native::generate_all(workspace, NativeProfile::Dev)?;
    kotlin_gate(
        workspace,
        KotlinGateOptions {
            compose: false,
            coverage: CoverageMode::Off,
            build_dir: ".kotlin/toolchain-build/check",
        },
    )?;
    eprintln!("xtask: check complete");
    Ok(())
}

pub fn ci(workspace: &Workspace) -> Result<()> {
    tools::ensure_quality(workspace)?;
    rust_full_gate(workspace, CoverageMode::On)?;
    native::generate_all(workspace, NativeProfile::Release)?;
    kotlin_gate(
        workspace,
        KotlinGateOptions {
            compose: true,
            coverage: CoverageMode::On,
            build_dir: ".kotlin/toolchain-build/ci",
        },
    )?;
    crate::android::validate_built_apk(workspace, ".kotlin/toolchain-build/ci", false)?;
    eprintln!("xtask: ci complete");
    Ok(())
}

pub fn rust_ci(workspace: &Workspace, coverage: CoverageMode) -> Result<()> {
    tools::ensure_quality(workspace)?;
    rust_full_gate(workspace, coverage)
}

pub fn android_ci(workspace: &Workspace, coverage: CoverageMode) -> Result<()> {
    tools::ensure_quality(workspace)?;
    native::generate_bindings(workspace)?;
    native::verify_native_tree(workspace, &native::Abi::ALL)?;
    kotlin_gate(
        workspace,
        KotlinGateOptions {
            compose: true,
            coverage,
            build_dir: ".kotlin/toolchain-build/ci-android",
        },
    )?;
    crate::android::validate_built_apk(workspace, ".kotlin/toolchain-build/ci-android", false)?;
    Ok(())
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct KotlinGateOptions {
    compose: bool,
    coverage: CoverageMode,
    build_dir: &'static str,
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
    rust_tests(workspace)?;
    let mut machete = repository_command(workspace, workspace.tool_bin().join("cargo-machete"));
    machete.current_dir(&workspace.rust).arg(".");
    run(&mut machete)
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
        // Tooling CLI entrypoint is exercised via `just perf` / corpus generate, not unit tests.
        "--ignore-filename-regex",
        "feasibility/src/main\\.rs",
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
    build.args(["build", "--build-dir", options.build_dir]);
    run(&mut build)?;

    for script in [
        "quality/scripts/kotlin_detekt_check.sh",
        "quality/scripts/kotlin_test_style_check.sh",
        "quality/scripts/kotlin_android_lint_check.sh",
    ] {
        run_policy(workspace, script, options.build_dir)?;
    }
    if options.compose {
        run_policy(
            workspace,
            "quality/scripts/kotlin_compose_static_analysis.sh",
            options.build_dir,
        )?;
    }
    for script in [
        "quality/scripts/check_meaningful_tests.sh",
        "quality/scripts/check_string_resource_parity.sh",
        "quality/scripts/test/android_runtime_dependency_boundary_contract_test.sh",
        "quality/scripts/test/kotlin_quality_check_contract_test.sh",
    ] {
        run_policy(workspace, script, options.build_dir)?;
    }
    if options.coverage == CoverageMode::On {
        run_policy(
            workspace,
            "quality/scripts/kotlin_coverage_check.sh",
            options.build_dir,
        )
    } else {
        kotlin_tests(workspace, options.build_dir)
    }
}

fn kotlin_tests(workspace: &Workspace, build_dir: &str) -> Result<()> {
    if build_dir.is_empty() {
        bail!("Kotlin build directory must not be empty");
    }
    let mut command = kotlin(workspace)?;
    command.arg("test");
    for module in TEST_MODULES {
        command.arg(format!("--include-module={module}"));
    }
    command.args(["--build-dir", build_dir]);
    run(&mut command)
}

fn run_policy(workspace: &Workspace, script: &str, build_dir: &str) -> Result<()> {
    let mut command = policy_script(workspace, script);
    command
        .env("LOMO_KOTLIN_BUILD_DIR", build_dir)
        .env("LOMO_LINT_BUILD_DIR", build_dir)
        .env("LOMO_COMPOSE_BUILD_DIR", build_dir)
        .env("LOMO_COVERAGE_BUILD_DIR", build_dir);
    run(&mut command)
}

fn run_shell_contracts(workspace: &Workspace) -> Result<()> {
    for script in [
        "quality/scripts/test/android_runtime_dependency_boundary_contract_test.sh",
        "quality/scripts/test/kotlin_quality_check_contract_test.sh",
        "quality/scripts/check_string_resource_parity.sh",
    ] {
        run_policy(workspace, script, ".kotlin/toolchain-build/preflight")
            .with_context(|| format!("shell contract failed: {script}"))?;
    }
    Ok(())
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ChangeSource {
    Staged,
}

fn classify_changes(workspace: &Workspace, source: ChangeSource) -> Result<ChangeSet> {
    let files = changed_paths(workspace, source)?;
    if files.is_empty() {
        // Empty stage still runs a cheap contract surface rather than silent success.
        return Ok(ChangeSet {
            rust: false,
            kotlin: false,
            native: false,
            quality_infra: true,
            docs_only: false,
        });
    }

    let mut rust = false;
    let mut kotlin = false;
    let mut native = false;
    let mut quality_infra = false;
    let mut other = false;

    for path in &files {
        if is_quality_infra(path) {
            quality_infra = true;
        }
        if is_rust_path(path) {
            rust = true;
        }
        if is_kotlin_path(path) {
            kotlin = true;
        }
        if is_native_path(path) {
            native = true;
        }
        if !is_docs_path(path)
            && !is_rust_path(path)
            && !is_kotlin_path(path)
            && !is_native_path(path)
            && !is_quality_infra(path)
        {
            other = true;
        }
    }

    let docs_only = files.iter().all(|path| is_docs_path(path)) && !other && !quality_infra;
    if other {
        // Unknown paths fall back to the broadest local iterative surface.
        rust = true;
        kotlin = true;
        native = true;
        quality_infra = true;
    }

    Ok(ChangeSet {
        rust,
        kotlin,
        native,
        quality_infra,
        docs_only,
    })
}

fn changed_paths(workspace: &Workspace, source: ChangeSource) -> Result<BTreeSet<String>> {
    let mut command = Command::new("git");
    command.current_dir(&workspace.root);
    match source {
        ChangeSource::Staged => {
            command.args(["diff", "--cached", "--name-only", "--diff-filter=ACMR"]);
        }
    }
    let output = text_output(&mut command)?;
    Ok(output
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .map(str::to_owned)
        .collect())
}

fn is_rust_path(path: &str) -> bool {
    path == "Justfile"
        || path.starts_with("rust/")
        || path.starts_with("rust-bindings/")
        || path.starts_with("native-smoke/")
}

fn is_kotlin_path(path: &str) -> bool {
    let extension = Path::new(path).extension().and_then(|value| value.to_str());
    path.starts_with("app/")
        || path.starts_with("data/")
        || path.starts_with("domain/")
        || path.starts_with("ui-components/")
        || path.starts_with("quality/detekt")
        || path == "project.yaml"
        || path == "kotlin"
        || path.ends_with("module.yaml")
        || extension.is_some_and(|value| value.eq_ignore_ascii_case("kt"))
        || extension.is_some_and(|value| value.eq_ignore_ascii_case("kts"))
}

fn is_native_path(path: &str) -> bool {
    path.starts_with("rust/native/")
        || path.starts_with("rust/sync-core/")
        || path == "rust/Cargo.toml"
        || path == "rust/Cargo.lock"
        || path == "rust/tools.toml"
        || path == "rust/rust-toolchain.toml"
        || path.starts_with("rust/xtask/src/native.rs")
        || path.starts_with("rust/xtask/src/android.rs")
        || path.starts_with("rust/xtask/src/tools.rs")
        || path.starts_with("native-smoke/")
        || path.starts_with("rust-bindings/")
}

fn is_quality_infra(path: &str) -> bool {
    path.starts_with("quality/")
        || path.starts_with(".githooks/")
        || path.starts_with(".github/workflows/")
        || path == "Justfile"
        || path == "AGENTS.md"
        || path == "ARCHITECTURE.md"
        || path == "project.yaml"
}

fn is_docs_path(path: &str) -> bool {
    Path::new(path)
        .extension()
        .is_some_and(|value| value.eq_ignore_ascii_case("md"))
        || path.starts_with("docs/")
        || path == "LICENSE"
        || path == "README.md"
        || path == "README_CN.md"
        || path == "ROADMAP.MD"
        || path == "plan.md"
}
