use anyhow::{Result, bail};
use serde::Serialize;
use serde_json::{Value, json};

use crate::{
    android::{self, AndroidVariant},
    cache, deps,
    native::{self, Abi, NativeProfile},
    perf,
    quality::{self, CoverageMode, FormatMode},
    tools,
    workspace::Workspace,
};

#[derive(Serialize)]
struct CommandSpec {
    name: &'static str,
    arguments: &'static str,
    purpose: &'static str,
    #[serde(skip)]
    execute: fn(&Workspace, &[String]) -> Result<Value>,
}

const fn command(
    name: &'static str,
    arguments: &'static str,
    purpose: &'static str,
    execute: fn(&Workspace, &[String]) -> Result<Value>,
) -> CommandSpec {
    CommandSpec {
        name,
        arguments,
        purpose,
        execute,
    }
}

// Discovery and dispatch use this same registry; there is no second command menu.
const COMMANDS: &[CommandSpec] = &[
    command(
        "commands",
        "",
        "Discover this command protocol without executing gates",
        |_, args| no_args(args, || Ok(catalog())),
    ),
    command(
        "bootstrap",
        "",
        "Install pinned tools and targets",
        |w, args| no_args(args, || tools::bootstrap(w)),
    ),
    command(
        "fmt",
        "[staged|all|check]",
        "Format sources; check is read-only",
        |w, args| {
            quality::format(w, format_mode(args)?)?;
            Ok(json!({"mode": args.first().map_or("staged", String::as_str)}))
        },
    ),
    command(
        "dev",
        "[--scope <owner>] [--plan] [--tests-only]",
        "Plan or execute worktree-scoped checks; iteration only",
        dev,
    ),
    command(
        "preflight",
        "[push [<remote>]]",
        "Push hook gate; just entrypoint is _preflight",
        preflight,
    ),
    command(
        "check",
        "",
        "Execute the complete handoff gate",
        |w, args| no_args(args, || quality::check(w)),
    ),
    command(
        "bindings",
        "",
        "Regenerate ignored Kotlin bindings",
        |w, args| {
            no_args(args, || {
                native::generate_bindings(w)?;
                Ok(json!({"directory": w.root.join("apps/android/native-bindings/src")}))
            })
        },
    ),
    command(
        "native",
        "[arm64|arm|x86_64|x86|all]",
        "Generate and validate release native outputs",
        native_command,
    ),
    command(
        "android",
        "[debug|release] [arm64|arm|x86_64|x86|all]",
        "Build and validate APK; release requires signing",
        android_command,
    ),
    command(
        "ci",
        "",
        "Execute full merge/release gate, including coverage and packaging",
        |w, args| no_args(args, || quality::ci(w)),
    ),
    command(
        "deps",
        "[check|update]",
        "Check dependencies or explicitly apply updates",
        deps_command,
    ),
    command(
        "perf",
        "",
        "Produce performance evidence; diagnostic, not a handoff gate",
        |w, args| no_args(args, || perf::run_diagnostics(w)),
    ),
    command(
        "cache",
        "[audit|paths|prune|clean]",
        "Inspect caches or explicitly delete generated state",
        cache_command,
    ),
    command(
        "rust-toolchain-bump",
        "<channel> [--dry-run]",
        "Rewrite the canonical Rust pin sites",
        |w, args| {
            rust_toolchain_bump(w, args)?;
            Ok(Value::Null)
        },
    ),
    command(
        "ci-rust",
        "[fast|coverage]",
        "CI-only Rust gate; invoke lomo-xtask directly",
        |w, args| {
            ci_rust(w, args)?;
            Ok(Value::Null)
        },
    ),
    command(
        "bootstrap-rust",
        "",
        "CI-only Rust bootstrap; invoke lomo-xtask directly",
        |w, args| no_args(args, || tools::bootstrap_rust(w)),
    ),
    command(
        "ci-native",
        "[release-ci|release] <abi>",
        "CI-only native generation; invoke lomo-xtask directly",
        |w, args| {
            ci_native(w, args)?;
            Ok(Value::Null)
        },
    ),
    command(
        "ci-android",
        "[fast|coverage]",
        "CI-only Android gate; invoke lomo-xtask directly",
        |w, args| {
            ci_android(w, args)?;
            Ok(Value::Null)
        },
    ),
];

pub fn run(workspace: &Workspace, arguments: &[String]) -> Result<Value> {
    let (name, rest) = match arguments.split_first() {
        None => ("commands", &[][..]),
        Some((name, rest)) => (name.as_str(), rest),
    };
    if matches!(name, "help" | "--help" | "-h") {
        return no_args(rest, || Ok(catalog()));
    }
    let spec = COMMANDS
        .iter()
        .find(|entry| entry.name == name)
        .ok_or_else(|| anyhow::anyhow!("unknown xtask command `{name}`; run `just commands`"))?;
    (spec.execute)(workspace, rest)
}

fn catalog() -> Value {
    json!({
        "commands": COMMANDS,
        "protocol": "quality/README.md#command-protocol",
        "stdout": "one JSON result; data null means no command-specific payload",
        "stderr": "progress and child diagnostics",
        "exit": {"0": "operation succeeded; scope is defined by the command", "nonzero": "failed or incomplete"},
    })
}

fn dev(workspace: &Workspace, arguments: &[String]) -> Result<Value> {
    let mut scope = None;
    let mut print_only = false;
    let mut mode = crate::verification::PlanMode::Dev;
    let mut arguments = arguments.iter();
    while let Some(argument) = arguments.next() {
        match argument.as_str() {
            "--plan" => print_only = true,
            "--tests-only" => mode = crate::verification::PlanMode::Tests,
            "--scope" => {
                scope = Some(
                    arguments
                        .next()
                        .ok_or_else(|| anyhow::anyhow!("--scope requires an owner"))?
                        .as_str(),
                );
            }
            _ => bail!("usage: just dev [--scope <owner>] [--plan] [--tests-only]"),
        }
    }
    crate::verification::run(
        workspace,
        &crate::verification::ChangeSource::Worktree,
        scope,
        mode,
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

fn native_command(workspace: &Workspace, arguments: &[String]) -> Result<Value> {
    let abis = match arguments {
        [] => Abi::ALL.to_vec(),
        [abi] => Abi::parse_selector(abi)?,
        _ => bail!("usage: just native [abi]"),
    };
    native::generate_selected(workspace, NativeProfile::Release, &abis)?;
    Ok(
        json!({"directory": workspace.jni_libs(), "abis": abis.iter().map(|abi| abi.android_name()).collect::<Vec<_>>()}),
    )
}

fn android_command(workspace: &Workspace, arguments: &[String]) -> Result<Value> {
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
    Ok(json!({"apk": apk}))
}

fn preflight(workspace: &Workspace, arguments: &[String]) -> Result<Value> {
    // The only remaining preflight scope is the pushed-commit diff; worktree/staged
    // iteration is `just dev`.
    let source = match arguments {
        [] => quality::ChangeSource::Push {
            remote: "origin".to_owned(),
        },
        [value] if value == "push" => quality::ChangeSource::Push {
            remote: "origin".to_owned(),
        },
        [value, remote] if value == "push" => quality::ChangeSource::Push {
            remote: remote.clone(),
        },
        _ => bail!("usage: just preflight [push [<remote>]]; staged iteration is `just dev`"),
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

fn deps_command(workspace: &Workspace, arguments: &[String]) -> Result<Value> {
    let mode = match arguments {
        [] => deps::DependencyMode::Check,
        [value] => deps::parse_mode(value)?,
        _ => bail!("usage: just deps [check|update]"),
    };
    deps::run_dependencies(workspace, mode)?;
    Ok(Value::Null)
}

fn cache_command(workspace: &Workspace, arguments: &[String]) -> Result<Value> {
    let mode = match arguments {
        [] => cache::CacheMode::Audit,
        [value] => cache::parse_mode(value)?,
        _ => bail!("usage: just cache [audit|paths|prune|clean]"),
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

fn no_args<T: Serialize>(
    arguments: &[String],
    action: impl FnOnce() -> Result<T>,
) -> Result<Value> {
    if !arguments.is_empty() {
        bail!("command does not accept arguments: {}", arguments.join(" "));
    }
    Ok(serde_json::to_value(action()?)?)
}
