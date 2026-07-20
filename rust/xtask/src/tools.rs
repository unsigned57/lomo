use std::fs;
use std::path::PathBuf;
use std::process::Command;

use anyhow::{Context, Result, bail};

use crate::util::{repository_command, run, text_output};
use crate::workspace::{NDK_VERSION, Workspace};

#[derive(Clone, Debug, Eq, PartialEq)]
struct Tool {
    package: String,
    version: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct BoltffiPin {
    package: String,
    binary: String,
    git: String,
    rev: String,
}

pub fn bootstrap(workspace: &Workspace) -> Result<()> {
    bootstrap_rust(workspace)?;
    install_ndk(workspace)?;
    eprintln!("xtask: bootstrap complete");
    Ok(())
}

pub fn bootstrap_rust(workspace: &Workspace) -> Result<()> {
    workspace.prepare_directories()?;
    install_rust_components(workspace)?;
    for tool in quality_and_diagnostic_tools(workspace)? {
        install_tool(workspace, &tool)?;
    }
    install_boltffi(workspace)?;
    eprintln!("xtask: Rust tool bootstrap complete");
    Ok(())
}

pub fn ensure_quality(workspace: &Workspace) -> Result<()> {
    ensure_rust_version(workspace)?;
    let tools = quality_and_diagnostic_tools(workspace)?;
    for package in [
        "cargo-deny",
        "cargo-nextest",
        "cargo-llvm-cov",
        "cargo-machete",
        "cargo-ndk",
    ] {
        let tool = tools
            .iter()
            .find(|tool| tool.package == package)
            .with_context(|| format!("{package} is missing from rust/tools.toml"))?;
        ensure_tool(workspace, tool)?;
    }
    Ok(())
}

pub fn ensure_diagnostics(workspace: &Workspace) -> Result<()> {
    ensure_rust_version(workspace)?;
    let tools = quality_and_diagnostic_tools(workspace)?;
    for package in ["cargo-bloat", "cargo-llvm-lines"] {
        let tool = tools
            .iter()
            .find(|tool| tool.package == package)
            .with_context(|| format!("{package} is missing from rust/tools.toml"))?;
        ensure_tool(workspace, tool)?;
    }
    Ok(())
}

pub fn ensure_boltffi(workspace: &Workspace) -> Result<()> {
    ensure_rust_version(workspace)?;
    let pin = boltffi_pin(workspace)?;
    let binary = workspace.tool_bin().join(&pin.binary);
    if !binary.is_file() {
        install_boltffi(workspace)?;
    }
    let binary = workspace.tool_bin().join(&pin.binary);
    if !binary.is_file() {
        bail!(
            "{} is not installed at {}; run `just bootstrap`",
            pin.binary,
            binary.display()
        );
    }
    // Identity sidecar written at install time for exact-rev verification.
    let identity = boltffi_identity_path(workspace);
    let expected = format!(
        "package={}\nbinary={}\ngit={}\nrev={}\n",
        pin.package, pin.binary, pin.git, pin.rev
    );
    if !identity.is_file() {
        bail!(
            "BoltFFI identity sidecar missing at {}; run `just bootstrap`",
            identity.display()
        );
    }
    let actual = fs::read_to_string(&identity)
        .with_context(|| format!("failed to read {}", identity.display()))?;
    if actual != expected {
        bail!(
            "BoltFFI pin mismatch at {}:\nexpected:\n{expected}\nfound:\n{actual}\nrun `just bootstrap`",
            identity.display()
        );
    }
    Ok(())
}

pub fn boltffi_binary(workspace: &Workspace) -> Result<PathBuf> {
    ensure_boltffi(workspace)?;
    let pin = boltffi_pin(workspace)?;
    Ok(workspace.tool_bin().join(pin.binary))
}

fn install_boltffi(workspace: &Workspace) -> Result<()> {
    let pin = boltffi_pin(workspace)?;
    let mut command = repository_command(workspace, "cargo");
    command.args([
        "+1.96",
        "install",
        "--locked",
        "--root",
        workspace.tool_root.to_string_lossy().as_ref(),
        "--git",
        &pin.git,
        "--rev",
        &pin.rev,
        &pin.package,
    ]);
    run(&mut command)?;
    let binary = workspace.tool_bin().join(&pin.binary);
    if !binary.is_file() {
        bail!(
            "installed {} but binary {} is missing",
            pin.package,
            binary.display()
        );
    }
    let identity = boltffi_identity_path(workspace);
    if let Some(parent) = identity.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(
        &identity,
        format!(
            "package={}\nbinary={}\ngit={}\nrev={}\n",
            pin.package, pin.binary, pin.git, pin.rev
        ),
    )
    .with_context(|| format!("failed to write {}", identity.display()))?;
    Ok(())
}

fn boltffi_identity_path(workspace: &Workspace) -> PathBuf {
    workspace.tool_root.join("share/lomo/boltffi-identity.txt")
}

fn boltffi_pin(workspace: &Workspace) -> Result<BoltffiPin> {
    let path = workspace.rust.join("tools.toml");
    let text =
        fs::read_to_string(&path).with_context(|| format!("failed to read {}", path.display()))?;
    let mut in_section = false;
    let mut package = None;
    let mut binary = None;
    let mut git = None;
    let mut rev = None;
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if line.starts_with('[') {
            in_section = line == "[ffi.boltffi_cli]";
            continue;
        }
        if !in_section {
            continue;
        }
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        let key = key.trim();
        let value = value.trim().trim_matches('"').to_owned();
        match key {
            "package" => package = Some(value),
            "binary" => binary = Some(value),
            "git" => git = Some(value),
            "rev" => rev = Some(value),
            _ => {}
        }
    }
    Ok(BoltffiPin {
        package: package.context("ffi.boltffi_cli.package missing from rust/tools.toml")?,
        binary: binary.context("ffi.boltffi_cli.binary missing from rust/tools.toml")?,
        git: git.context("ffi.boltffi_cli.git missing from rust/tools.toml")?,
        rev: rev.context("ffi.boltffi_cli.rev missing from rust/tools.toml")?,
    })
}

fn install_rust_components(workspace: &Workspace) -> Result<()> {
    let mut components = repository_command(workspace, "rustup");
    components.args([
        "component",
        "add",
        "--toolchain",
        "1.96",
        "rustfmt",
        "clippy",
        "llvm-tools-preview",
    ]);
    run(&mut components)?;

    let mut targets = repository_command(workspace, "rustup");
    targets.args([
        "target",
        "add",
        "--toolchain",
        "1.96",
        "aarch64-linux-android",
        "armv7-linux-androideabi",
        "i686-linux-android",
        "x86_64-linux-android",
    ]);
    run(&mut targets)
}

fn install_tool(workspace: &Workspace, tool: &Tool) -> Result<()> {
    if ensure_tool(workspace, tool).is_ok() {
        return Ok(());
    }
    let mut command = repository_command(workspace, "cargo");
    command.args([
        "+1.96",
        "install",
        "--locked",
        "--root",
        workspace.tool_root.to_string_lossy().as_ref(),
        "--version",
        &tool.version,
        &tool.package,
    ]);
    run(&mut command)?;
    ensure_tool(workspace, tool)
}

fn ensure_tool(workspace: &Workspace, tool: &Tool) -> Result<()> {
    let binary = workspace.tool_bin().join(&tool.package);
    if !binary.is_file() {
        bail!(
            "{} {} is not installed at {}; run `just bootstrap`",
            tool.package,
            tool.version,
            binary.display()
        );
    }
    let version = installed_version(&binary, &tool.package)?;
    if !version
        .split_whitespace()
        .any(|token| token == tool.version)
    {
        bail!(
            "{} version mismatch: expected {}, found {}; run `just bootstrap`",
            tool.package,
            tool.version,
            version.trim()
        );
    }
    Ok(())
}

fn installed_version(binary: &PathBuf, package: &str) -> Result<String> {
    let subcommand = package.trim_start_matches("cargo-");
    for arguments in [
        vec!["--version".to_owned()],
        vec![subcommand.to_owned(), "--version".to_owned()],
    ] {
        let command_output = Command::new(binary).args(&arguments).output();
        let Ok(command_output) = command_output else {
            continue;
        };
        if command_output.status.success() {
            return String::from_utf8(command_output.stdout)
                .context("tool version output is not UTF-8");
        }
    }
    bail!("unable to query version from {}", binary.display())
}

fn ensure_rust_version(workspace: &Workspace) -> Result<()> {
    let mut command = repository_command(workspace, "rustc");
    command.arg("--version");
    let version = text_output(&mut command)?;
    if !version
        .split_whitespace()
        .any(|token| token.starts_with("1.96"))
    {
        bail!("Rust 1.96 is required, found {}", version.trim());
    }
    Ok(())
}

fn install_ndk(workspace: &Workspace) -> Result<()> {
    if workspace
        .ndk_root()
        .join("toolchains/llvm/prebuilt")
        .is_dir()
    {
        return Ok(());
    }
    let sdkmanager = sdkmanager(workspace)?;
    let mut command = Command::new(sdkmanager);
    command
        .current_dir(&workspace.root)
        .env("ANDROID_HOME", &workspace.android_sdk)
        .env("ANDROID_SDK_ROOT", &workspace.android_sdk)
        .arg(format!("ndk;{NDK_VERSION}"));
    run(&mut command)?;
    if !workspace
        .ndk_root()
        .join("toolchains/llvm/prebuilt")
        .is_dir()
    {
        bail!(
            "sdkmanager completed without installing NDK {NDK_VERSION} under {}",
            workspace.android_sdk.display()
        );
    }
    Ok(())
}

fn sdkmanager(workspace: &Workspace) -> Result<PathBuf> {
    for candidate in [
        workspace
            .android_sdk
            .join("cmdline-tools/latest/bin/sdkmanager"),
        workspace.android_sdk.join("tools/bin/sdkmanager"),
        PathBuf::from("sdkmanager"),
    ] {
        let mut command = Command::new(&candidate);
        command.arg("--version");
        if command.output().is_ok_and(|output| output.status.success()) {
            return Ok(candidate);
        }
    }
    bail!("sdkmanager is required to install NDK {NDK_VERSION}")
}

fn quality_and_diagnostic_tools(workspace: &Workspace) -> Result<Vec<Tool>> {
    let path = workspace.rust.join("tools.toml");
    let text =
        fs::read_to_string(&path).with_context(|| format!("failed to read {}", path.display()))?;
    let mut tools = Vec::new();
    let mut in_simple_section = false;
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if line.starts_with('[') {
            in_simple_section = matches!(line, "[quality]" | "[diagnostics]");
            continue;
        }
        if !in_simple_section {
            continue;
        }
        let (package, version) = line
            .split_once('=')
            .with_context(|| format!("invalid tool manifest line: {line}"))?;
        tools.push(Tool {
            package: package.trim().to_owned(),
            version: version.trim().trim_matches('"').to_owned(),
        });
    }
    if tools.is_empty() {
        bail!("rust/tools.toml contains no quality/diagnostics tools");
    }
    Ok(tools)
}
