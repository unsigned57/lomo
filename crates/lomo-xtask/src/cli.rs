use anyhow::{Result, bail};

use crate::{
    android::{self, AndroidVariant},
    cache, deps,
    native::{self, Abi, NativeProfile},
    perf,
    quality::{self, CoverageMode, FormatMode},
    tools,
    workspace::Workspace,
};

pub fn run(workspace: &Workspace, arguments: &[String]) -> Result<()> {
    let Some((command, rest)) = arguments.split_first() else {
        print_help();
        return Ok(());
    };
    match command.as_str() {
        "bootstrap" => no_args(rest, || tools::bootstrap(workspace)),
        "fmt" => quality::format(workspace, format_mode(rest)?),
        "test" => no_args(rest, || quality::test(workspace)),
        "dev" => dev(workspace, rest),
        "preflight" => preflight(workspace, rest),
        "check" => no_args(rest, || quality::check(workspace)),
        "check-linux" => no_args(rest, || quality::check_linux(workspace)),
        "tui" => tui_command(workspace, rest),
        "package-linux" => no_args(rest, || crate::package::package_linux(workspace)),
        "bindings" => no_args(rest, || native::generate_bindings(workspace)),
        "native" => native_command(workspace, rest),
        "android" => android_command(workspace, rest),
        "ci" => no_args(rest, || quality::ci(workspace)),
        "deps" => deps_command(workspace, rest),
        "usecase-reachability" => no_args(rest, || {
            crate::usecase_reachability::check_usecase_reachability(&workspace.root)
        }),
        "mutants" => mutants_command(workspace, rest),
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

fn dev(workspace: &Workspace, arguments: &[String]) -> Result<()> {
    let mut scope = None;
    let mut print_only = false;
    let mut arguments = arguments.iter();
    while let Some(argument) = arguments.next() {
        match argument.as_str() {
            "--plan" => print_only = true,
            "--scope" => {
                scope = Some(
                    arguments
                        .next()
                        .ok_or_else(|| anyhow::anyhow!("--scope requires an owner"))?
                        .as_str(),
                );
            }
            _ => bail!("usage: just dev [--scope <owner>] [--plan]"),
        }
    }
    crate::verification::run(
        workspace,
        &crate::verification::ChangeSource::Worktree,
        scope,
        crate::verification::PlanMode::Dev,
        print_only,
    )
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

fn preflight(workspace: &Workspace, arguments: &[String]) -> Result<()> {
    let source = match arguments {
        [] => quality::ChangeSource::Staged,
        [value] | [value, _] if value == "staged" => quality::ChangeSource::Staged,
        [value] if value == "push" => quality::ChangeSource::Push {
            remote: "origin".to_owned(),
        },
        [value, remote] if value == "push" => quality::ChangeSource::Push {
            remote: remote.clone(),
        },
        _ => bail!("usage: just preflight [staged|push [<remote>]]"),
    };
    quality::preflight(workspace, &source)
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

fn tui_command(workspace: &Workspace, arguments: &[String]) -> Result<()> {
    let mut command = crate::util::cargo(workspace);
    command.args(["run", "--locked", "-p", "lomo-tui", "--"]);
    command.args(arguments);
    crate::util::run(&mut command)
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

fn mutants_command(workspace: &Workspace, arguments: &[String]) -> Result<()> {
    let mut cmd = crate::util::cargo(workspace);
    cmd.arg("mutants");
    cmd.args(arguments);
    crate::util::run(&mut cmd)
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
        "Lomo xtask\n\nCommands:\n  bootstrap\n  fmt [staged|all|check]\n  test\n  preflight\n  check\n  check-linux\n  tui\n  package-linux\n  bindings\n  native\n  android [debug|release]\n  ci\n  deps [check|update]\n  usecase-reachability\n  mutants\n  perf\n  cache [audit|paths|clean]\n  rust-toolchain-bump <channel> [--dry-run]"
    ));
}
