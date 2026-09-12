use std::{
    collections::{BTreeSet, HashMap, HashSet, VecDeque},
    path::Path,
    process::Command,
};

use anyhow::{Context, Result, bail};

use crate::{
    native::{self, NativeProfile},
    tools,
    util::{cargo, kotlin, policy_script, repository_command, run, text_output},
    workspace::Workspace,
};

const TEST_MODULES: [&str; 5] = ["app", "data", "detekt-rules", "domain", "ui-components"];
const HOST_PACKAGES: [&str; 9] = [
    "lomo-application",
    "lomo-core",
    "lomo-workspace",
    "lomo-store",
    "lomo-media",
    "lomo-platform-fs",
    "lomo-tui",
    "lomo-architecture-tests",
    "lomo-xtask",
];
const FORBIDDEN_HOST_DEPENDENCIES: [&str; 7] = [
    "lomo-native",
    "boltffi",
    "jni",
    "ndk",
    "ndk-sys",
    "ndk-glue",
    "android-activity",
];
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

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[expect(
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
    kotlin_tests(workspace)
}

/// Path-aware commit gate used by pre-commit. Never weaker than the contracts that
/// staged paths can break; skips unrelated multi-minute surfaces.
pub fn preflight(workspace: &Workspace, source: ChangeSource) -> Result<()> {
    tools::ensure_quality(workspace)?;
    let changes = classify_changes(workspace, source)?;
    crate::util::emit_stderr(format_args!(
        "xtask: preflight rust={} kotlin={} native={} quality_infra={} docs_only={}",
        changes.rust, changes.kotlin, changes.native, changes.quality_infra, changes.docs_only
    ));

    if changes.docs_only && !changes.quality_infra {
        run_shell_contracts(workspace)?;
        crate::util::emit_stderr(format_args!("xtask: preflight complete (docs-only)"));
        return Ok(());
    }

    let check_ffi = changes.rust || changes.kotlin || changes.native || changes.quality_infra;
    let check_usecase = changes.kotlin || changes.quality_infra;
    let mut contract_violations = Vec::new();
    if check_ffi && let Err(err) = crate::ffi_parity::check_ffi_parity(workspace) {
        contract_violations.push(err.to_string());
    }
    if check_usecase
        && let Err(err) = crate::usecase_reachability::check_usecase_reachability(&workspace.root)
    {
        contract_violations.push(err.to_string());
    }
    if let Err(err) = workspace.check_generated_artifact_layout() {
        contract_violations.push(err.to_string());
    }
    if !contract_violations.is_empty() {
        bail!("{}", contract_violations.join("\n\n"));
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
            },
        )?;
    } else if changes.quality_infra || changes.native {
        run_shell_contracts(workspace)?;
    }

    crate::util::emit_stderr(format_args!("xtask: preflight complete"));
    Ok(())
}

fn check_architecture_contracts(workspace: &Workspace) -> Result<()> {
    let mut violations = Vec::new();
    if let Err(err) = crate::ffi_parity::check_ffi_parity(workspace) {
        violations.push(err.to_string());
    }
    if let Err(err) = crate::usecase_reachability::check_usecase_reachability(&workspace.root) {
        violations.push(err.to_string());
    }
    if let Err(err) = workspace.check_generated_artifact_layout() {
        violations.push(err.to_string());
    }
    if !violations.is_empty() {
        bail!("{}", violations.join("\n\n"));
    }
    Ok(())
}

pub fn check(workspace: &Workspace) -> Result<()> {
    tools::ensure_quality(workspace)?;
    check_architecture_contracts(workspace)?;
    rust_fast_gate(workspace)?;
    native::generate_all(workspace, NativeProfile::Dev)?;
    kotlin_gate(
        workspace,
        KotlinGateOptions {
            compose: false,
            coverage: CoverageMode::Off,
        },
    )?;
    crate::util::emit_stderr(format_args!("xtask: check complete"));
    Ok(())
}

pub fn ci(workspace: &Workspace) -> Result<()> {
    tools::ensure_quality(workspace)?;
    check_architecture_contracts(workspace)?;
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
    crate::android::publish_apk(workspace, &apk, "debug", "all")?;
    crate::util::emit_stderr(format_args!("xtask: ci complete"));
    Ok(())
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

pub fn check_linux(workspace: &Workspace) -> Result<()> {
    crate::util::emit_stderr(format_args!("xtask: running Linux host quality gate..."));

    crate::util::emit_stderr(format_args!("xtask: checking rust formatting..."));
    let mut fmt = cargo(workspace);
    fmt.args(["fmt", "--all", "--", "--check"]);
    run(&mut fmt)?;

    verify_host_dependency_closure(workspace)?;

    crate::util::emit_stderr(format_args!("xtask: running clippy for host packages..."));
    let mut clippy = cargo(workspace);
    clippy.arg("clippy");
    for pkg in HOST_PACKAGES {
        clippy.args(["-p", pkg]);
    }
    clippy.args(["--all-targets", "--locked", "--", "-D", "warnings"]);
    run(&mut clippy)?;

    crate::util::emit_stderr(format_args!("xtask: running architecture tests..."));
    let mut arch_tests = cargo(workspace);
    arch_tests.args(["test", "-p", "lomo-architecture-tests", "--locked"]);
    run(&mut arch_tests)?;

    crate::util::emit_stderr(format_args!(
        "xtask: running unit and integration tests for host packages..."
    ));
    let mut host_tests = cargo(workspace);
    host_tests.arg("test");
    for pkg in HOST_PACKAGES {
        host_tests.args(["-p", pkg]);
    }
    host_tests.arg("--locked");
    run(&mut host_tests)?;

    crate::util::emit_stderr(format_args!("xtask: check-linux passed successfully."));
    Ok(())
}

fn host_target_triple() -> Result<String> {
    let mut cmd = Command::new("rustc");
    let output = text_output(cmd.arg("-vV"))?;
    for line in output.lines() {
        if let Some(host) = line.strip_prefix("host: ") {
            return Ok(host.trim().to_owned());
        }
    }
    bail!("failed to determine host target triple from rustc -vV");
}

fn parse_metadata_packages(
    meta: &serde_json::Value,
) -> Result<(HashMap<String, String>, Vec<String>)> {
    let packages = meta
        .get("packages")
        .and_then(serde_json::Value::as_array)
        .context("packages array missing in cargo metadata")?;

    let mut id_to_name = HashMap::new();
    let mut host_root_ids = Vec::new();

    for pkg in packages {
        let id = pkg
            .get("id")
            .and_then(serde_json::Value::as_str)
            .ok_or_else(|| anyhow::anyhow!("package in metadata is missing string id: {pkg:?}"))?;
        let name = pkg
            .get("name")
            .and_then(serde_json::Value::as_str)
            .ok_or_else(|| {
                anyhow::anyhow!("package in metadata is missing string name: {pkg:?}")
            })?;

        id_to_name.insert(id.to_owned(), name.to_owned());
        if HOST_PACKAGES.contains(&name) {
            host_root_ids.push(id.to_owned());
        }
    }

    let mut missing_hosts = Vec::new();
    for &expected_host in &HOST_PACKAGES {
        if !id_to_name.values().any(|name| name == expected_host) {
            missing_hosts.push(expected_host);
        }
    }
    if !missing_hosts.is_empty() {
        bail!(
            "missing required host packages in metadata: {}",
            missing_hosts.join(", ")
        );
    }

    if host_root_ids.len() != HOST_PACKAGES.len() {
        bail!(
            "expected {} host package root IDs in metadata, found {}",
            HOST_PACKAGES.len(),
            host_root_ids.len()
        );
    }

    Ok((id_to_name, host_root_ids))
}

fn parse_metadata_nodes(meta: &serde_json::Value) -> Result<HashMap<String, Vec<String>>> {
    let nodes = meta
        .get("resolve")
        .and_then(|r| r.get("nodes"))
        .and_then(serde_json::Value::as_array)
        .context("resolve.nodes array missing in cargo metadata")?;

    let mut adj = HashMap::new();
    for node in nodes {
        let id = node
            .get("id")
            .and_then(serde_json::Value::as_str)
            .ok_or_else(|| anyhow::anyhow!("resolve node missing string id: {node:?}"))?;
        let deps = node
            .get("dependencies")
            .and_then(serde_json::Value::as_array)
            .ok_or_else(|| {
                anyhow::anyhow!("resolve node {id} missing dependencies array: {node:?}")
            })?;
        let mut dep_ids = Vec::with_capacity(deps.len());
        for dep in deps {
            let dep_str = dep.as_str().ok_or_else(|| {
                anyhow::anyhow!("dependency entry in node {id} is not a string: {dep:?}")
            })?;
            dep_ids.push(dep_str.to_owned());
        }
        adj.insert(id.to_owned(), dep_ids);
    }

    Ok(adj)
}

fn check_host_closure_purity(
    host_root_ids: &[String],
    id_to_name: &HashMap<String, String>,
    adj: &HashMap<String, Vec<String>>,
) -> Result<()> {
    let mut violations = Vec::new();
    for root_id in host_root_ids {
        let root_name = id_to_name
            .get(root_id)
            .ok_or_else(|| anyhow::anyhow!("host root id `{root_id}` not found in package map"))?;
        let mut visited = HashSet::new();
        let mut queue = VecDeque::new();
        queue.push_back(root_id.clone());

        while let Some(curr) = queue.pop_front() {
            if !visited.insert(curr.clone()) {
                continue;
            }
            let pkg_name = id_to_name.get(&curr).ok_or_else(|| {
                anyhow::anyhow!("dependency id `{curr}` not found in packages list")
            })?;
            for &forbidden in &FORBIDDEN_HOST_DEPENDENCIES {
                if pkg_name == forbidden || pkg_name.starts_with("boltffi_") {
                    violations.push(format!(
                        "host package `{root_name}` transitively depends on forbidden Android/FFI package `{pkg_name}`"
                    ));
                }
            }
            let neighbors = adj.get(&curr).ok_or_else(|| {
                anyhow::anyhow!("dependency id `{curr}` not found in resolve nodes")
            })?;
            for neighbor in neighbors {
                if !visited.contains(neighbor) {
                    queue.push_back(neighbor.clone());
                }
            }
        }
    }

    if !violations.is_empty() {
        violations.sort();
        violations.dedup();
        bail!(
            "host dependency closure purity violations:\n{}",
            violations.join("\n")
        );
    }

    Ok(())
}

pub fn parse_and_verify_host_dependencies(metadata_json: &str) -> Result<()> {
    let meta: serde_json::Value =
        serde_json::from_str(metadata_json).context("failed to parse cargo metadata JSON")?;
    let (id_to_name, host_root_ids) = parse_metadata_packages(&meta)?;
    let adj = parse_metadata_nodes(&meta)?;
    check_host_closure_purity(&host_root_ids, &id_to_name, &adj)
}

pub fn verify_host_dependency_closure(workspace: &Workspace) -> Result<()> {
    crate::util::emit_stderr(format_args!(
        "xtask: verifying Linux host dependency closure..."
    ));
    let host_triple = host_target_triple()?;
    let mut cmd = cargo(workspace);
    cmd.args([
        "metadata",
        "--locked",
        "--format-version",
        "1",
        "--filter-platform",
        &host_triple,
    ]);
    let raw_json = text_output(&mut cmd)?;
    parse_and_verify_host_dependencies(&raw_json)?;
    crate::util::emit_stderr(format_args!(
        "xtask: host dependency closure verified (0 forbidden Android/FFI dependencies in host packages)."
    ));
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
fn run_lint_policy(workspace: &Workspace) -> Result<()> {
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

fn run_shell_contracts(workspace: &Workspace) -> Result<()> {
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

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ChangeSource {
    /// Compare against the git index (staged paths).
    Staged,
    /// Compare pushed commits against the remote base (path-aware pre-push gate).
    Push { remote: String },
}

fn classify_changes(workspace: &Workspace, source: ChangeSource) -> Result<ChangeSet> {
    let files = changed_paths(workspace, source)?;
    let Some(files) = files else {
        // No usable remote base for push-time comparison; run the full iterative surface.
        return Ok(ChangeSet {
            rust: true,
            kotlin: true,
            native: true,
            quality_infra: true,
            docs_only: false,
        });
    };
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

fn changed_paths(workspace: &Workspace, source: ChangeSource) -> Result<Option<BTreeSet<String>>> {
    let mut command = Command::new("git");
    command.current_dir(&workspace.root);
    match source {
        ChangeSource::Staged => {
            command.args(["diff", "--cached", "--name-only", "--diff-filter=ACMR"]);
        }
        ChangeSource::Push { remote } => {
            let Some(base) = push_base(workspace, &remote)? else {
                return Ok(None);
            };
            command.args(["diff", "--name-only", "--diff-filter=ACMR"]);
            command.arg(format!("{base}...HEAD"));
        }
    }
    let output = text_output(&mut command)?;
    Ok(Some(
        output
            .lines()
            .map(str::trim)
            .filter(|line| !line.is_empty())
            .map(str::to_owned)
            .collect(),
    ))
}

/// Resolve the remote base ref for push-time comparison: the remote HEAD (or the remote
/// default branch) tracked locally. `None` means no base is available, not that the
/// comparison is empty.
fn push_base(workspace: &Workspace, remote: &str) -> Result<Option<String>> {
    for reference in [
        format!("refs/remotes/{remote}/HEAD"),
        format!("refs/remotes/{remote}/main"),
    ] {
        let mut rev = Command::new("git");
        rev.current_dir(&workspace.root)
            .args(["rev-parse", "--verify"])
            .arg(&reference);
        if rev.status().context("git rev-parse failed")?.success() {
            return Ok(Some(reference));
        }
    }
    Ok(None)
}

fn is_rust_path(path: &str) -> bool {
    path == "Justfile"
        || path.starts_with("crates/")
        || path == "Cargo.toml"
        || path == "Cargo.lock"
        || path == "rust-toolchain.toml"
        || path == "tools.toml"
        || path == "deny.toml"
        || path == "rustfmt.toml"
        || path == "clippy.toml"
        || path.starts_with("apps/android/native-bindings/")
}

fn is_kotlin_path(path: &str) -> bool {
    let extension = Path::new(path).extension().and_then(|value| value.to_str());
    path.starts_with("apps/android/")
        || path == "kotlin"
        || path.ends_with("module.yaml")
        || extension.is_some_and(|value| value.eq_ignore_ascii_case("kt"))
        || extension.is_some_and(|value| value.eq_ignore_ascii_case("kts"))
}

fn is_native_path(path: &str) -> bool {
    path.starts_with("crates/lomo-native/")
        || path == "Cargo.toml"
        || path == "Cargo.lock"
        || path == "tools.toml"
        || path == "rust-toolchain.toml"
        || path.starts_with("crates/lomo-xtask/src/native.rs")
        || path.starts_with("crates/lomo-xtask/src/android.rs")
        || path.starts_with("crates/lomo-xtask/src/tools.rs")
        || path.starts_with("apps/android/native-bindings/")
}

fn is_quality_infra(path: &str) -> bool {
    // Executable gate ownership only. Narrative docs (AGENTS/ARCHITECTURE/README) are docs paths.
    path.starts_with("quality/")
        || path.starts_with(".githooks/")
        || path.starts_with(".github/workflows/")
        || path == "Justfile"
        || path == "apps/android/project.yaml"
}

fn is_docs_path(path: &str) -> bool {
    Path::new(path)
        .extension()
        .is_some_and(|value| value.eq_ignore_ascii_case("md"))
        || path.starts_with("docs/")
        || path.starts_with("fixtures/")
        || path == "LICENSE"
        || path == "README.md"
        || path == "README_CN.md"
        || path == "AGENTS.md"
        || path == "ARCHITECTURE.md"
        // Local-only planning notes (gitignored); still docs for path-aware preflight.
        || path == "ROADMAP.MD"
        || path == "plan.md"
}
