use std::path::{Path, PathBuf};

use crate::check::EnvironmentCheck;
use crate::cli::Result;
use crate::commands::check::apple_targets_require_lipo;
use crate::config::Config;
use crate::target::RustTarget;

pub enum ConfigSummary {
    Loaded(Box<Config>),
    Missing,
    Invalid(String),
}

pub struct DoctorOptions {
    pub apple: bool,
    pub apple_targets: Vec<RustTarget>,
    pub android: bool,
    pub android_targets: Vec<RustTarget>,
    pub wasm: bool,
    pub wasm_target_triple: Option<String>,
    pub config_summary: ConfigSummary,
    pub config_path: PathBuf,
    pub overlay_path: Option<PathBuf>,
    pub config_warning: Option<String>,
}

pub fn run_doctor(options: DoctorOptions) -> Result<()> {
    let required_triples = required_target_triples(&options);
    let check = EnvironmentCheck::run_with_required_triples(&required_triples);

    println!("boltffi doctor");
    println!();
    if let Some(warning) = options.config_warning.as_deref() {
        println!("Warning: {}", warning);
        println!();
    }
    print_environment(&check, &options);
    println!();
    print_config_summary(
        &options.config_summary,
        &options.config_path,
        options.overlay_path.as_deref(),
    );

    Ok(())
}

fn required_target_triples(options: &DoctorOptions) -> Vec<String> {
    let apple_targets = options
        .apple
        .then(|| options.apple_targets.iter().copied())
        .into_iter()
        .flatten()
        .map(|target| target.triple().to_string());

    let android_targets = options
        .android
        .then(|| options.android_targets.iter().copied())
        .into_iter()
        .flatten()
        .map(|target| target.triple().to_string());

    let wasm_targets = options
        .wasm
        .then(|| {
            options
                .wasm_target_triple
                .clone()
                .unwrap_or_else(|| RustTarget::WASM32_UNKNOWN_UNKNOWN.triple().to_string())
        })
        .into_iter();

    apple_targets
        .chain(android_targets)
        .chain(wasm_targets)
        .collect()
}

fn print_environment(check: &EnvironmentCheck, options: &DoctorOptions) {
    let apple_tooling_ready = !options.apple
        || (check.tools.xcode_cli
            && check.tools.xcodebuild
            && (!apple_targets_require_lipo(&options.apple_targets) || check.tools.lipo));

    match &check.rust_version {
        Some(version) => println!("Rust: {}", version),
        None => println!("Rust: missing"),
    }

    println!("Installed targets: {}", check.installed_targets.len());
    println!("Missing targets: {}", check.missing_targets.len());
    check
        .missing_targets
        .iter()
        .for_each(|triple| println!("  - {}", triple));

    println!();
    println!("Apple tooling: {}", readiness(apple_tooling_ready));
    if options.apple {
        let requires_lipo = apple_targets_require_lipo(&options.apple_targets);
        options.apple_targets.iter().for_each(|target| {
            let installed = check
                .installed_targets
                .iter()
                .any(|triple| triple == target.triple());
            println!("  target {}: {}", target.triple(), readiness(installed));
        });
        println!("  xcode-select: {}", readiness(check.tools.xcode_cli));
        println!("  xcodebuild: {}", readiness(check.tools.xcodebuild));
        if requires_lipo {
            println!("  lipo: {}", readiness(check.tools.lipo));
        } else {
            println!("  lipo: ok (not required for configured slices)");
        }
    }

    println!();
    println!(
        "Android tooling: {}",
        readiness(check.is_ready_for_android())
    );
    if options.android {
        options.android_targets.iter().for_each(|target| {
            let installed = check
                .installed_targets
                .iter()
                .any(|triple| triple == target.triple());
            println!("  target {}: {}", target.triple(), readiness(installed));
        });
        match &check.tools.android_ndk {
            Some(path) => println!("  ndk: {}", path),
            None => println!("  ndk: missing (set ANDROID_NDK_HOME)"),
        }
    }

    if options.wasm {
        let wasm_target = options
            .wasm_target_triple
            .as_deref()
            .unwrap_or(RustTarget::WASM32_UNKNOWN_UNKNOWN.triple());
        println!();
        println!(
            "WASM target {} ({})",
            readiness(
                check
                    .installed_targets
                    .iter()
                    .any(|target| target == wasm_target)
            ),
            wasm_target
        );
    }
}

fn print_config_summary(summary: &ConfigSummary, config_path: &Path, overlay_path: Option<&Path>) {
    match summary {
        ConfigSummary::Loaded(config) => {
            println!("Config: {}", config_path.display());
            if let Some(overlay_path) = overlay_path {
                println!("Overlay: {}", overlay_path.display());
            }
            println!("  crate: {}", config.library_name());
            println!(
                "  targets.apple.output: {}",
                config.apple_output().display()
            );
            println!(
                "  targets.apple.include_macos: {}",
                config.apple_include_macos()
            );
            println!(
                "  targets.apple.ios_architectures: {}",
                config
                    .apple_ios_architectures()
                    .iter()
                    .map(|architecture| architecture.canonical_name())
                    .collect::<Vec<_>>()
                    .join(", ")
            );
            println!(
                "  targets.apple.simulator_architectures: {}",
                config
                    .apple_simulator_architectures()
                    .iter()
                    .map(|architecture| architecture.canonical_name())
                    .collect::<Vec<_>>()
                    .join(", ")
            );
            println!(
                "  targets.apple.macos_architectures: {}{}",
                config
                    .apple_macos_architectures()
                    .iter()
                    .map(|architecture| architecture.canonical_name())
                    .collect::<Vec<_>>()
                    .join(", "),
                if config.apple_include_macos() {
                    ""
                } else {
                    " (ignored unless include_macos = true)"
                }
            );
            println!(
                "  targets.apple.swift.output: {}",
                config.apple_swift_output().display()
            );
            println!(
                "  targets.apple.xcframework.output: {}",
                config.apple_xcframework_output().display()
            );
            println!(
                "  targets.apple.spm.output: {}",
                config.apple_spm_output().display()
            );
            println!(
                "  targets.android.output: {}",
                config.android_output().display()
            );
            println!(
                "  targets.android.kotlin.output: {}",
                config.android_kotlin_output().display()
            );
            println!(
                "  targets.android.header.output: {}",
                config.android_header_output().display()
            );
            println!(
                "  targets.android.pack.output: {}",
                config.android_pack_output().display()
            );
            println!(
                "  targets.android.architectures: {}",
                config
                    .android_architectures()
                    .iter()
                    .map(|architecture| architecture.canonical_name())
                    .collect::<Vec<_>>()
                    .join(", ")
            );
            println!("  targets.dart.output: {}", config.dart_output().display());
            println!(
                "  targets.dart.native_architectures: {}",
                config
                    .dart_native_architectures()
                    .iter()
                    .filter_map(|target| target.dart_native_name())
                    .collect::<Vec<_>>()
                    .join(", ")
            );
            println!("  targets.wasm.output: {}", config.wasm_output().display());
            println!(
                "  targets.wasm.typescript.output: {}",
                config.wasm_typescript_output().display()
            );
            println!(
                "  targets.wasm.npm.output: {}",
                config.wasm_npm_output().display()
            );
        }
        ConfigSummary::Missing => {
            if let Some(overlay_path) = overlay_path {
                println!(
                    "Config: missing (expected {} before applying overlay {})",
                    config_path.display(),
                    overlay_path.display()
                );
            } else {
                println!("Config: missing (expected {})", config_path.display());
            }
        }
        ConfigSummary::Invalid(error) => {
            println!("Config: {}", config_path.display());
            if let Some(overlay_path) = overlay_path {
                println!("Overlay: {}", overlay_path.display());
            }
            println!("  invalid: {}", error);
        }
    }
}

fn readiness(is_ready: bool) -> &'static str {
    if is_ready { "ok" } else { "missing" }
}

#[cfg(test)]
mod tests {
    use super::{ConfigSummary, DoctorOptions, required_target_triples};
    use crate::target::RustTarget;
    use std::path::PathBuf;

    #[test]
    fn uses_configured_wasm_target_triple() {
        let options = DoctorOptions {
            apple: false,
            apple_targets: Vec::new(),
            android: false,
            android_targets: Vec::new(),
            wasm: true,
            wasm_target_triple: Some("wasm32-wasip1".to_string()),
            config_summary: ConfigSummary::Missing,
            config_path: PathBuf::from("boltffi.toml"),
            overlay_path: None,
            config_warning: None,
        };

        assert_eq!(
            required_target_triples(&options),
            vec!["wasm32-wasip1".to_string()]
        );
    }

    #[test]
    fn defaults_wasm_target_triple_when_not_configured() {
        let options = DoctorOptions {
            apple: false,
            apple_targets: Vec::new(),
            android: false,
            android_targets: Vec::new(),
            wasm: true,
            wasm_target_triple: None,
            config_summary: ConfigSummary::Missing,
            config_path: PathBuf::from("boltffi.toml"),
            overlay_path: None,
            config_warning: None,
        };

        assert_eq!(
            required_target_triples(&options),
            vec![RustTarget::WASM32_UNKNOWN_UNKNOWN.triple().to_string()]
        );
    }
}
