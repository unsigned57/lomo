use anyhow::{Result, bail};

use crate::android::{self, AndroidVariant};
use crate::cache;
use crate::deps;
use crate::native::{self, Abi, NativeProfile};
use crate::perf;
use crate::provider_smoke;
use crate::quality::{self, CoverageMode, FormatMode};
use crate::tools;
use crate::workspace::Workspace;

pub fn run(workspace: &Workspace, arguments: &[String]) -> Result<()> {
    let Some((command, rest)) = arguments.split_first() else {
        print_help();
        return Ok(());
    };
    match command.as_str() {
        "bootstrap" => no_args(rest, || tools::bootstrap(workspace)),
        "fmt" => quality::format(workspace, format_mode(rest)?),
        "test" => no_args(rest, || quality::test(workspace)),
        "preflight" => no_args(rest, || quality::preflight(workspace)),
        "check" => no_args(rest, || quality::check(workspace)),
        "bindings" => no_args(rest, || native::generate_bindings(workspace)),
        "native" => native_command(workspace, rest),
        "android" => android_command(workspace, rest),
        "ci" => no_args(rest, || quality::ci(workspace)),
        "device-smoke" => no_args(rest, || android::device_smoke(workspace)),
        "sync-provider-smoke" => sync_provider_smoke(workspace, rest),
        "deps" => deps_command(workspace, rest),
        "perf" => no_args(rest, || perf::run_diagnostics(workspace)),
        "cache" => cache_command(workspace, rest),
        "ci-rust" => ci_rust(workspace, rest),
        "bootstrap-rust" => no_args(rest, || tools::bootstrap_rust(workspace)),
        "ci-native" => ci_native(workspace, rest),
        "ci-android" => ci_android(workspace, rest),
        "rust-toolchain-bump" => rust_toolchain_bump(workspace, rest),
        "help" | "--help" | "-h" => {
            print_help();
            Ok(())
        }
        unknown => bail!("unknown xtask command `{unknown}`; run `just --list`"),
    }
}

fn rust_toolchain_bump(workspace: &Workspace, arguments: &[String]) -> Result<()> {
    let Some((channel, rest)) = arguments.split_first() else {
        bail!("usage: just rust-toolchain-bump <channel> [--dry-run]");
    };
    let dry_run = match rest {
        [] => false,
        [flag] if flag == "--dry-run" => true,
        _ => bail!("usage: just rust-toolchain-bump <channel> [--dry-run]"),
    };
    crate::rust_pin::bump(workspace, channel, dry_run)
}

fn native_command(workspace: &Workspace, arguments: &[String]) -> Result<()> {
    let abis = match arguments {
        [] => Abi::ALL.to_vec(),
        [abi] => Abi::parse_selector(abi)?,
        _ => bail!("usage: just native [abi]"),
    };
    native::generate_selected(workspace, NativeProfile::Release, &abis)
}

fn android_command(workspace: &Workspace, arguments: &[String]) -> Result<()> {
    let (variant, abis) = match arguments {
        [] => (AndroidVariant::Debug, Abi::ALL.to_vec()),
        [arg1] => {
            if let Ok(variant) = parse_variant(arg1) {
                (variant, Abi::ALL.to_vec())
            } else if let Ok(abis) = Abi::parse_selector(arg1) {
                (AndroidVariant::Debug, abis)
            } else {
                bail!(
                    "invalid android argument: {arg1}; usage: just android [debug|release] [abi]"
                );
            }
        }
        [arg1, arg2] => (parse_variant(arg1)?, Abi::parse_selector(arg2)?),
        _ => bail!("usage: just android [debug|release] [abi]"),
    };
    let apk = android::build(workspace, variant, &abis)?;
    crate::util::emit_stderr(format_args!(
        "xtask: Android artifact ready: {}",
        apk.display()
    ));
    Ok(())
}

fn parse_variant(value: &str) -> Result<AndroidVariant> {
    match value {
        "debug" => Ok(AndroidVariant::Debug),
        "release" => Ok(AndroidVariant::Release),
        _ => bail!("unknown Android variant: {value}"),
    }
}

fn deps_command(workspace: &Workspace, arguments: &[String]) -> Result<()> {
    let mode = match arguments {
        [] => deps::DependencyMode::Check,
        [value] => deps::parse_mode(value)?,
        _ => bail!("usage: just deps [check|update]"),
    };
    deps::run_dependencies(workspace, mode)
}

/// Runs the six locked Stage-5 provider lines, or a single selected line.
///
/// Lines without credentials stay `OPEN / pending_env` and the command exits non-zero.
fn sync_provider_smoke(workspace: &Workspace, arguments: &[String]) -> Result<()> {
    match arguments {
        [] => provider_smoke::run(workspace, None),
        [selector] => provider_smoke::run(workspace, Some(selector)),
        _ => bail!(
            "usage: just sync-provider-smoke [all|nutstore|nextcloud|aws-s3|cloudflare-r2|github|gitlab]"
        ),
    }
}

fn cache_command(workspace: &Workspace, arguments: &[String]) -> Result<()> {
    let mode = match arguments {
        [] => cache::CacheMode::Audit,
        [value] => cache::parse_mode(value)?,
        _ => bail!("usage: just cache [audit|paths|clean]"),
    };
    cache::run_cache(workspace, mode)
}

fn ci_native(workspace: &Workspace, arguments: &[String]) -> Result<()> {
    // Default PR/CI path uses thin-LTO release-ci. Pass `release` for fat LTO.
    let (profile, abi) = match arguments {
        [abi] => (NativeProfile::ReleaseCi, abi.as_str()),
        [profile, abi] if profile == "release-ci" => (NativeProfile::ReleaseCi, abi.as_str()),
        [profile, abi] if profile == "release" => (NativeProfile::Release, abi.as_str()),
        _ => bail!("usage: lomo-xtask ci-native [release-ci|release] <abi>"),
    };
    tools::ensure_quality(workspace)?;
    native::generate_android(workspace, profile, &[Abi::parse(abi)?])
}

fn ci_rust(workspace: &Workspace, arguments: &[String]) -> Result<()> {
    quality::rust_ci(workspace, parse_coverage_mode(arguments, "ci-rust")?)
}

fn ci_android(workspace: &Workspace, arguments: &[String]) -> Result<()> {
    quality::android_ci(workspace, parse_coverage_mode(arguments, "ci-android")?)
}

fn parse_coverage_mode(arguments: &[String], command: &str) -> Result<CoverageMode> {
    match arguments {
        [] => Ok(CoverageMode::Off),
        [value] if value == "fast" => Ok(CoverageMode::Off),
        [value] if value == "coverage" => Ok(CoverageMode::On),
        _ => bail!("usage: lomo-xtask {command} [fast|coverage]"),
    }
}

fn format_mode(arguments: &[String]) -> Result<FormatMode> {
    match arguments {
        [] => Ok(FormatMode::Staged),
        [value] if value == "staged" => Ok(FormatMode::Staged),
        [value] if value == "all" => Ok(FormatMode::All),
        [value] if value == "check" => Ok(FormatMode::Check),
        _ => bail!("usage: just fmt [staged|all|check]"),
    }
}

fn no_args(arguments: &[String], action: impl FnOnce() -> Result<()>) -> Result<()> {
    if !arguments.is_empty() {
        bail!("command does not accept arguments: {}", arguments.join(" "));
    }
    action()
}

fn print_help() {
    crate::util::emit_stderr(format_args!(
        "Lomo xtask\n\nCommands:\n  bootstrap\n  fmt [staged|all|check]\n  test\n  preflight\n  check\n  bindings\n  native\n  android [debug|release]\n  ci\n  device-smoke\n  sync-provider-smoke [all|nutstore|nextcloud|aws-s3|cloudflare-r2|github|gitlab]\n  deps [check|update]\n  perf\n  cache [audit|paths|clean]\n  rust-toolchain-bump <channel> [--dry-run]"
    ));
}
