use std::ffi::OsStr;
use std::fs;
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Output};

use anyhow::{Context, Result, bail};

use crate::workspace::Workspace;

/// Write one stderr status line for operator-facing xtask progress (CLI surface).
pub fn emit_stderr(args: std::fmt::Arguments<'_>) {
    match writeln!(io::stderr(), "{args}") {
        Ok(()) => {}
        Err(_write_error) => {}
    }
}

pub fn run(command: &mut Command) -> Result<()> {
    announce(command);
    let status = command.status().context("failed to start command")?;
    if !status.success() {
        bail!("command failed with {status}: {}", describe(command));
    }
    Ok(())
}

pub fn output(command: &mut Command) -> Result<Output> {
    announce(command);
    let output = command.output().context("failed to start command")?;
    if !output.status.success() {
        bail!(
            "command failed with {}: {}\n{}",
            output.status,
            describe(command),
            String::from_utf8_lossy(&output.stderr)
        );
    }
    Ok(output)
}

pub fn text_output(command: &mut Command) -> Result<String> {
    let output = output(command)?;
    String::from_utf8(output.stdout).context("command output is not UTF-8")
}

pub fn cargo(workspace: &Workspace) -> Command {
    let mut command = Command::new("cargo");
    command
        .current_dir(&workspace.rust)
        .env_remove("CARGO")
        .env("CARGO_TARGET_DIR", workspace.rust_target());
    prepend_tool_path(workspace, &mut command);
    command
}

pub fn repository_command(workspace: &Workspace, program: impl AsRef<OsStr>) -> Command {
    let mut command = Command::new(program);
    command.current_dir(&workspace.root).env_remove("CARGO");
    prepend_tool_path(workspace, &mut command);
    command
}

pub fn kotlin(workspace: &Workspace) -> Result<Command> {
    workspace.prepare_kotlin_invocation()?;
    let wrapper = workspace.root.join("kotlin");
    if !wrapper.is_file() {
        bail!("Kotlin Toolchain wrapper is missing: {}", wrapper.display());
    }
    let mut command = Command::new(wrapper);
    command
        .current_dir(&workspace.root)
        .env("HOME", &workspace.kotlin_home)
        .env("XDG_CACHE_HOME", &workspace.kotlin_cache)
        .env("XDG_DATA_HOME", &workspace.kotlin_data)
        .env("XDG_CONFIG_HOME", &workspace.kotlin_config)
        .env("GRADLE_OPTS", gradle_options(workspace))
        .env("ANDROID_HOME", &workspace.android_sdk)
        .env("ANDROID_SDK_ROOT", &workspace.android_sdk)
        .env("ANDROID_USER_HOME", &workspace.android_home)
        .env(
            "KOTLIN_CLI_BOOTSTRAP_CACHE_DIR",
            &workspace.kotlin_cli_cache,
        )
        .env("KOTLIN_CLI_NO_WELCOME_BANNER", "1")
        .env("GRADLE_USER_HOME", &workspace.gradle_home)
        .arg("--log-level=warn");
    Ok(command)
}

pub fn policy_script(workspace: &Workspace, relative: &str) -> Command {
    let mut command = repository_command(workspace, workspace.root.join(relative));
    command
        .env("HOME", &workspace.kotlin_home)
        .env("XDG_CACHE_HOME", &workspace.kotlin_cache)
        .env("XDG_DATA_HOME", &workspace.kotlin_data)
        .env("XDG_CONFIG_HOME", &workspace.kotlin_config)
        .env("GRADLE_OPTS", gradle_options(workspace))
        .env("ANDROID_HOME", &workspace.android_sdk)
        .env("ANDROID_SDK_ROOT", &workspace.android_sdk)
        .env("ANDROID_USER_HOME", &workspace.android_home)
        .env(
            "KOTLIN_CLI_BOOTSTRAP_CACHE_DIR",
            &workspace.kotlin_cli_cache,
        )
        .env("GRADLE_USER_HOME", &workspace.gradle_home)
        .env("LOMO_KOTLIN_ANDROID_SDK", &workspace.android_sdk)
        .env("LOMO_KOTLIN_WRAPPER", workspace.root.join("kotlin"))
        .env(
            "LOMO_KOTLIN_TEST_MODULE_ARGS",
            "--include-module=app --include-module=data --include-module=detekt-rules --include-module=domain --include-module=ui-components",
        );
    command
}

pub fn find_files(root: &Path, extension: &str) -> Result<Vec<PathBuf>> {
    let mut matches = Vec::new();
    if !root.exists() {
        return Ok(matches);
    }
    let mut pending = vec![root.to_owned()];
    while let Some(directory) = pending.pop() {
        for entry in fs::read_dir(&directory)
            .with_context(|| format!("failed to read {}", directory.display()))?
        {
            let path = entry?.path();
            if path.is_dir() {
                pending.push(path);
            } else if path.extension().is_some_and(|value| value == extension) {
                matches.push(path);
            }
        }
    }
    matches.sort();
    Ok(matches)
}

pub fn remove_if_exists(path: &Path) -> Result<()> {
    if path.is_dir() {
        fs::remove_dir_all(path).with_context(|| format!("failed to remove {}", path.display()))?;
    } else if path.exists() {
        fs::remove_file(path).with_context(|| format!("failed to remove {}", path.display()))?;
    }
    Ok(())
}

fn prepend_tool_path(workspace: &Workspace, command: &mut Command) {
    let mut paths = vec![workspace.tool_bin()];
    if let Some(existing) = std::env::var_os("PATH") {
        paths.extend(std::env::split_paths(&existing));
    }
    if let Ok(joined) = std::env::join_paths(paths) {
        command.env("PATH", joined);
    }
}

fn gradle_options(workspace: &Workspace) -> String {
    format!("-Duser.home={}", workspace.kotlin_home.display())
}

fn announce(command: &Command) {
    emit_stderr(format_args!("xtask: {}", describe(command)));
}

fn describe(command: &Command) -> String {
    let mut parts = vec![command.get_program().to_string_lossy().into_owned()];
    parts.extend(
        command
            .get_args()
            .map(|arg| arg.to_string_lossy().into_owned()),
    );
    parts.join(" ")
}
