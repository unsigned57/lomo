use crate::check::{EnvironmentCheck, install_missing_targets};
use crate::cli::Result;
use crate::target::RustTarget;

pub struct CheckOptions {
    pub fix: bool,
    pub apple: bool,
    pub apple_targets: Vec<RustTarget>,
    pub android: bool,
    pub android_targets: Vec<RustTarget>,
    pub wasm: bool,
    pub wasm_target_triple: Option<String>,
}

impl Default for CheckOptions {
    fn default() -> Self {
        Self {
            fix: false,
            apple: true,
            apple_targets: RustTarget::ALL_IOS.to_vec(),
            android: true,
            android_targets: RustTarget::ALL_ANDROID.to_vec(),
            wasm: true,
            wasm_target_triple: Some(RustTarget::WASM32_UNKNOWN_UNKNOWN.triple().to_string()),
        }
    }
}

pub fn run_check(options: CheckOptions) -> Result<bool> {
    let mut required_triples = Vec::new();

    if options.apple {
        required_triples.extend(
            options
                .apple_targets
                .iter()
                .map(|target| target.triple().to_string()),
        );
    }

    if options.android {
        required_triples.extend(
            options
                .android_targets
                .iter()
                .map(|target| target.triple().to_string()),
        );
    }

    if options.wasm {
        required_triples.push(
            options
                .wasm_target_triple
                .clone()
                .unwrap_or_else(|| RustTarget::WASM32_UNKNOWN_UNKNOWN.triple().to_string()),
        );
    }

    let check = EnvironmentCheck::run_with_required_triples(&required_triples);

    print_environment_status(&check, &options);

    if options.fix && check.has_missing_targets() {
        println!();
        println!("Installing missing targets...");
        install_missing_targets(&check.missing_targets)?;
        println!("Done!");
    }

    let apple_tools_ready = !options.apple
        || (check.tools.xcode_cli
            && check.tools.xcodebuild
            && (!apple_targets_require_lipo(&options.apple_targets) || check.tools.lipo));

    let all_good = !check.has_missing_targets()
        && apple_tools_ready
        && (!options.android || check.is_ready_for_android());

    Ok(all_good)
}

pub(crate) fn apple_targets_require_lipo(apple_targets: &[RustTarget]) -> bool {
    let simulator_slices = apple_targets
        .iter()
        .filter(|target| target.platform() == crate::target::Platform::IosSimulator)
        .count();
    let macos_slices = apple_targets
        .iter()
        .filter(|target| target.platform() == crate::target::Platform::MacOs)
        .count();

    simulator_slices > 1 || macos_slices > 1
}

fn print_environment_status(check: &EnvironmentCheck, options: &CheckOptions) {
    println!("Environment");

    match &check.rust_version {
        Some(version) => println!("  {} {}", status_icon(true), version),
        None => println!("  {} Rust not found", status_icon(false)),
    }

    println!();

    if options.apple {
        let requires_lipo = apple_targets_require_lipo(&options.apple_targets);
        print_apple_targets(check, &options.apple_targets);
        println!();

        println!("Apple Tools");
        println!("  {} Xcode CLI tools", status_icon(check.tools.xcode_cli));
        if requires_lipo {
            println!("  {} lipo", status_icon(check.tools.lipo));
        } else {
            println!("  [ok] lipo (not required for configured slices)");
        }
        println!("  {} xcodebuild", status_icon(check.tools.xcodebuild));
        println!();
    }

    if options.android {
        println!("Android Targets");
        options.android_targets.iter().for_each(|target| {
            let installed = check.installed_targets.iter().any(|t| t == target.triple());
            println!("  {} {}", status_icon(installed), target.triple());
        });
        println!();

        println!("Android Tools");
        match &check.tools.android_ndk {
            Some(path) => println!("  {} Android NDK ({})", status_icon(true), path),
            None => println!("  {} Android NDK not found", status_icon(false)),
        }
        println!();
    }

    if options.wasm {
        println!("WASM Targets");
        let wasm_target = options
            .wasm_target_triple
            .as_deref()
            .unwrap_or(RustTarget::WASM32_UNKNOWN_UNKNOWN.triple());
        let installed = check
            .installed_targets
            .iter()
            .any(|installed| installed == wasm_target);
        println!("  {} {}", status_icon(installed), wasm_target);
        println!();
    }

    if check.has_missing_targets() {
        println!("Missing targets can be installed with:");
        check.fix_commands().iter().for_each(|cmd| {
            println!("  {}", cmd);
        });
        println!();
        println!("Or run: boltffi check --fix");
    }
}

fn status_icon(success: bool) -> &'static str {
    if success { "[ok]" } else { "[missing]" }
}

fn print_apple_targets(check: &EnvironmentCheck, apple_targets: &[RustTarget]) {
    print_apple_target_group(
        check,
        apple_targets,
        "Apple Targets (iOS)",
        crate::target::Platform::Ios,
    );
    print_apple_target_group(
        check,
        apple_targets,
        "Apple Targets (iOS Simulator)",
        crate::target::Platform::IosSimulator,
    );
    print_apple_target_group(
        check,
        apple_targets,
        "Apple Targets (macOS)",
        crate::target::Platform::MacOs,
    );
}

fn print_apple_target_group(
    check: &EnvironmentCheck,
    apple_targets: &[RustTarget],
    label: &str,
    platform: crate::target::Platform,
) {
    let matching_targets: Vec<_> = apple_targets
        .iter()
        .filter(|target| target.platform() == platform)
        .collect();

    if matching_targets.is_empty() {
        return;
    }

    println!("{label}");
    matching_targets.iter().for_each(|target| {
        let installed = check.installed_targets.iter().any(|t| t == target.triple());
        println!("  {} {}", status_icon(installed), target.triple());
    });
    println!();
}

#[cfg(test)]
mod tests {
    use super::apple_targets_require_lipo;
    use crate::target::RustTarget;

    #[test]
    fn requires_lipo_for_multi_slice_simulator_targets() {
        assert!(apple_targets_require_lipo(&[
            RustTarget::IOS_SIM_ARM64,
            RustTarget::IOS_SIM_X86_64,
        ]));
    }

    #[test]
    fn requires_lipo_for_multi_slice_macos_targets() {
        assert!(apple_targets_require_lipo(&[
            RustTarget::MACOS_ARM64,
            RustTarget::MACOS_X86_64,
        ]));
    }

    #[test]
    fn skips_lipo_for_single_slice_apple_configuration() {
        assert!(!apple_targets_require_lipo(&[
            RustTarget::IOS_ARM64,
            RustTarget::IOS_SIM_ARM64,
            RustTarget::MACOS_ARM64,
        ]));
    }
}
