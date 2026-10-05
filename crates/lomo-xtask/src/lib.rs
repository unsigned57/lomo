#![deny(unsafe_code)]

mod android;
mod cache;
mod cli;
mod deps;
mod kotlin_text;
mod native;
mod perf;
mod protocol;
mod quality;
mod rust_pin;
mod tools;
mod usecase_reachability;
mod util;
pub mod verification;
mod workspace;

use std::path::PathBuf;

pub use kotlin_text::{KotlinText, kotlin_code_view};
pub use native::canonicalize_binding;
pub use rust_pin::{RustPin, parse_channel, replace_toml_assignment};
pub use tools::pinned_tool_version;
pub use usecase_reachability::check_usecase_reachability;

/// Canonical repository root discovered the same way as the xtask CLI.
///
/// # Errors
///
/// Returns an error when the repository root cannot be canonicalized.
pub fn repository_root() -> anyhow::Result<PathBuf> {
    Ok(workspace::Workspace::discover()?.root)
}

/// Verifies generated Lomo artifacts stay namespaced under `target/lomo`.
///
/// # Errors
///
/// Returns an error when discovery fails or a derived path escapes the `lomo` namespace.
pub fn check_generated_artifact_layout() -> anyhow::Result<()> {
    workspace::Workspace::discover()?.check_generated_artifact_layout()
}

/// Runs the `lomo-xtask` command line interface with the discovered workspace.
///
/// # Errors
///
/// Returns an error if workspace discovery fails or if the command execution fails.
pub fn run_cli(arguments: &[String]) -> anyhow::Result<()> {
    let result =
        workspace::Workspace::discover().and_then(|workspace| cli::run(&workspace, arguments));
    protocol::finish(arguments, result)
}

/// Validates that an Android NDK directory contains a valid `source.properties`
/// matching the repository's pinned NDK version.
///
/// # Errors
///
/// Returns an error if `source.properties` is missing, unreadable, does not specify `Pkg.Revision`,
/// or specifies an NDK version that does not match the pinned version.
pub fn validate_ndk_directory(path: &std::path::Path) -> anyhow::Result<()> {
    workspace::validate_ndk_version(path)
}
