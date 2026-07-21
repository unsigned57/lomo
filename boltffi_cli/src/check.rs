use std::process::Command;

use crate::cli::{CliError, Result};
use crate::toolchain::AndroidNdk;

#[derive(Debug)]
pub struct EnvironmentCheck {
    pub rust_version: Option<String>,
    pub installed_targets: Vec<String>,
    pub missing_targets: Vec<String>,
    pub tools: ToolsCheck,
}

#[derive(Debug)]
pub struct ToolsCheck {
    pub xcode_cli: bool,
    pub lipo: bool,
    pub xcodebuild: bool,
    pub android_ndk: Option<String>,
}

impl EnvironmentCheck {
    pub fn run_with_required_triples(required_triples: &[String]) -> Self {
        let rust_version = get_rust_version();
        let installed_targets = get_installed_targets();

        let missing_targets = required_triples
            .iter()
            .filter(|triple| {
                !installed_targets
                    .iter()
                    .any(|installed| installed == *triple)
            })
            .cloned()
            .collect();

        let tools = ToolsCheck {
            xcode_cli: check_tool_exists("xcode-select"),
            lipo: check_tool_exists("lipo"),
            xcodebuild: check_tool_exists("xcodebuild"),
            android_ndk: find_android_ndk(),
        };

        Self {
            rust_version,
            installed_targets,
            missing_targets,
            tools,
        }
    }

    pub fn is_ready_for_android(&self) -> bool {
        self.tools.android_ndk.is_some()
    }

    pub fn has_missing_targets(&self) -> bool {
        !self.missing_targets.is_empty()
    }

    pub fn fix_commands(&self) -> Vec<String> {
        self.missing_targets
            .iter()
            .map(|target| format!("rustup target add {}", target))
            .collect()
    }
}

fn get_rust_version() -> Option<String> {
    Command::new("rustc")
        .arg("--version")
        .output()
        .ok()
        .and_then(|output| {
            String::from_utf8(output.stdout)
                .ok()
                .map(|s| s.trim().to_string())
        })
}

fn get_installed_targets() -> Vec<String> {
    Command::new("rustup")
        .args(["target", "list", "--installed"])
        .output()
        .ok()
        .and_then(|output| {
            String::from_utf8(output.stdout).ok().map(|s| {
                s.lines()
                    .map(|line| line.trim().to_string())
                    .filter(|line| !line.is_empty())
                    .collect()
            })
        })
        .unwrap_or_default()
}

fn check_tool_exists(tool: &str) -> bool {
    which::which(tool).is_ok()
}

fn find_android_ndk() -> Option<String> {
    AndroidNdk::discover(None)
        .ok()
        .map(|ndk| ndk.root().display().to_string())
}

pub fn install_missing_targets(targets: &[String]) -> Result<()> {
    targets.iter().try_for_each(|target| {
        let status = Command::new("rustup")
            .args(["target", "add", target])
            .status()
            .map_err(|_| CliError::CommandFailed {
                command: format!("rustup target add {}", target),
                status: None,
            })?;

        if !status.success() {
            return Err(CliError::CommandFailed {
                command: format!("rustup target add {}", target),
                status: status.code(),
            });
        }

        Ok(())
    })
}
