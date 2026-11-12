#![deny(unsafe_code)]

mod android;
mod cache;
mod cli;
mod deps;
mod kotlin_text;
mod native;
mod perf;
mod perf_device;
mod profiles;
mod protocol;
mod quality;
mod rust_pin;
mod toolchain_parity;
mod tools;
mod tui;
mod tui_uitest;
mod usecase_reachability;
mod util;
pub mod verification;
mod workspace;

use std::path::PathBuf;

pub use android::{
    AndroidVariant, BenchmarkScenario, DeviceBoot, MAESTRO_VERSION, ProfileArtifactStamp,
    UishotOptions, UitestOptions, adb_install, android_apk_command, benchmark_channel,
    benchmark_scenarios, collect_benchmark_outputs, collect_profile_outputs,
    deduplicate_device_serials, frame_timeline_capable, lan_pairing_code, lan_validate_device_pair,
    lan_verify_discovery, lan_verify_pairing_codes, lan_verify_workspace,
    profile_artifact_snapshot, publish_apk, summarize_benchmark_files, uishot_flatten_hierarchy,
    uishot_prune_snapshots, uishot_screenshot_is_png, uishot_screenshot_is_suspicious, uishot_slug,
    uishot_slug_is_timestamp, uitest_flow_files, valid_redroid_gpu_mode,
};
pub use kotlin_text::{KotlinText, kotlin_code_view};
pub use native::{
    Abi, NATIVE_LIBRARY, NativeProfile, android_libraries_reusable, canonicalize_binding,
    write_android_libraries_stamp,
};
pub use perf_device::{
    DeviceBackend, DevicePerfOptions, DevicePerfPlane, engine_noise_gate, parse_device_perf_args,
};
pub use profiles::{
    ProfileCapture, publish_measured_profiles, validate_measured_profiles,
    validate_profile_with_profgen,
};
pub use quality::kotlin_build_command;
pub use rust_pin::{RustPin, parse_channel, replace_toml_assignment};
pub use toolchain_parity::{
    PinViolation, ToolchainParity, check_kotlin_toolchain_parity,
    kotlin_toolchain_parity_violations,
};
pub use tools::pinned_tool_version;
pub use tui_uitest::{TUI_UITEST_FLOW_DIR, TuiUitestOptions, tui_flow_files};
pub use usecase_reachability::check_usecase_reachability;
pub use workspace::{ANDROID_API, NDK_VERSION, Workspace};

/// Canonical repository root discovered the same way as the xtask CLI.
///
/// # Errors
///
/// Returns an error when the repository root cannot be canonicalized.
pub fn repository_root() -> anyhow::Result<PathBuf> {
    Ok(Workspace::discover()?.root)
}

/// Verifies generated Lomo artifacts stay namespaced under `target/lomo`.
///
/// # Errors
///
/// Returns an error when discovery fails or a derived path escapes the `lomo` namespace.
pub fn check_generated_artifact_layout() -> anyhow::Result<()> {
    Workspace::discover()?.check_generated_artifact_layout()
}

/// Runs the `lomo-xtask` command line interface with the discovered workspace.
///
/// # Errors
///
/// Returns an error if workspace discovery fails or if the command execution fails.
pub fn run_cli(arguments: &[String]) -> anyhow::Result<()> {
    let result = Workspace::discover().and_then(|workspace| cli::run(&workspace, arguments));
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
