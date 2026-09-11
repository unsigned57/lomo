#![deny(unsafe_code)]

mod android;
mod cache;
mod cli;
mod deps;
mod ffi_parity;
mod native;
pub mod package;
mod perf;
mod provider_smoke;
mod quality;
mod rust_pin;
mod tools;
mod usecase_reachability;
mod util;
mod workspace;

pub use usecase_reachability::check_usecase_reachability;

/// Runs the `lomo-xtask` command line interface with the discovered workspace.
///
/// # Errors
///
/// Returns an error if workspace discovery fails or if the command execution fails.
pub fn run_cli(arguments: &[String]) -> anyhow::Result<()> {
    let workspace = workspace::Workspace::discover()?;
    cli::run(&workspace, arguments)
}

/// Parses cargo metadata JSON and verifies that Linux host packages are free from
/// forbidden Android/FFI dependencies and that the dependency graph is valid.
///
/// # Errors
///
/// Returns an error if the JSON is malformed, if required host packages or resolve nodes
/// are missing, if dependency entries are invalid, or if any host package transitively
/// depends on a forbidden Android or FFI package.
pub fn parse_and_verify_host_dependencies(metadata_json: &str) -> anyhow::Result<()> {
    quality::parse_and_verify_host_dependencies(metadata_json)
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
