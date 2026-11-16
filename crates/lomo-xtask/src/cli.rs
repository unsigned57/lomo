use anyhow::{Context, Result, bail, ensure};
use serde::Serialize;
use serde_json::{Value, json};

use crate::{
    android::{self, AndroidVariant},
    cache, deps,
    native::{self, Abi, NativeProfile},
    perf, perf_device,
    quality::{self, CoverageMode, FormatMode},
    tools,
    tui::{self, TuiProfile},
    tui_uitest,
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
        "Compiler-free push policies and formatting; just entrypoint is _preflight",
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
        "build",
        "<android|tui> [debug|release|benchmark] [abi|scenario] [--redroid]",
        "Build and validate an application: android APK (default debug arm64; benchmark runs the device Macrobenchmark suite, --redroid provisions the pinned container) or tui desktop binary",
        build_command,
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
        "[device [startup|cuj|engine|workspace|system|sync|profile|release|all]... [--corpus device] [--backend saf|direct] [--redroid] [--trace]]",
        "Produce performance evidence; diagnostic, not a handoff gate. Bare `perf` runs the host diagnostics; `perf device` measures a debuggable device",
        perf_command,
    ),
    command(
        "uitest",
        "[flow ...] [--redroid] [--skip-build] [--no-install] [--clear-data] [--peer=<serial> for lan_share]",
        "Run committed Maestro UI flows on an attached device (or provision the redroid container via --redroid); diagnostic, not a gate",
        uitest_command,
    ),
    command(
        "uishot",
        "[name] [--redroid]",
        "Snapshot the current device screen: screenshot + view hierarchy + component index; diagnostic, not a gate",
        uishot_command,
    ),
    command(
        "redroid",
        "<up|down|status>",
        "Lifecycle for the pinned redroid container: provision and wait (`up`), report backend state (`status`), or stop it (`down`)",
        android::redroid,
    ),
    command(
        "tuitest",
        "[flow ...] [--skip-build]",
        "Run committed PTY interaction flows against the lomo TUI binary; diagnostic, not a gate",
        tuitest_command,
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
        "kotlin-toolchain-check",
        "",
        "Assert JDK/SDK/NDK/serialization pin parity: module.yaml copies, workflows, workspace.rs",
        |w, args| {
            no_args(args, || {
                crate::toolchain_parity::check_kotlin_toolchain_parity(&w.root)
            })
        },
    ),
    command(
        "ci-rust",
        "[fast|coverage|delivery]",
        "CI-only Rust gate; delivery adds coverage evidence to the separate check job",
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

fn build_command(workspace: &Workspace, arguments: &[String]) -> Result<Value> {
    let Some((target, rest)) = arguments.split_first() else {
        bail!("usage: just build <android|tui> [debug|release|benchmark] [abi|scenario]");
    };
    match target.as_str() {
        "android" => android_command(workspace, rest),
        "tui" => tui_command(workspace, rest),
        _ => bail!(
            "unknown build target: {target}; usage: just build <android|tui> [debug|release|benchmark] [abi|scenario]"
        ),
    }
}

fn tui_command(workspace: &Workspace, arguments: &[String]) -> Result<Value> {
    let profile = match arguments {
        [] => TuiProfile::Debug,
        [value] => TuiProfile::parse(value)?,
        _ => bail!("usage: just build tui [debug|release]"),
    };
    let binary = tui::build(workspace, profile)?;
    Ok(json!({"binary": binary}))
}

fn android_command(workspace: &Workspace, arguments: &[String]) -> Result<Value> {
    if matches!(arguments.first().map(String::as_str), Some("benchmark")) {
        // The scenario slot also accepts an ABI token so `just build android benchmark
        // arm64` still resolves to the full run set; `benchmark_scenarios` maps it (and
        // explicit scenario names) to the run set. The instrumented harness is
        // ABI-universal, so the slot carries no target.
        let mut scenario = None;
        let mut boot = None;
        for argument in arguments.iter().skip(1) {
            match argument.as_str() {
                "--redroid" => boot = Some(android::DeviceBoot::Redroid),
                _ if argument.starts_with("--") => {
                    bail!("unknown benchmark flag: {argument}")
                }
                _ if scenario.is_none() => scenario = Some(argument.clone()),
                _ => bail!(
                    "usage: just build android benchmark [startup|frame|scroll|create|expand|menu|search|all] [--redroid]"
                ),
            }
        }
        let scenarios = android::benchmark_scenarios(scenario.as_ref())?;
        return android::benchmark(workspace, &scenarios, boot);
    }
    let (variant, abis) = match arguments {
        // The Just entrypoint previously defaulted to debug arm64; `build android`
        // keeps that single-ABI dev default (`all` remains explicit).
        [] => (AndroidVariant::Debug, vec![Abi::Arm64]),
        [arg1] => {
            if let Ok(variant) = parse_variant(arg1) {
                (variant, vec![Abi::Arm64])
            } else if let Ok(abis) = Abi::parse_selector(arg1) {
                (AndroidVariant::Debug, abis)
            } else {
                bail!(
                    "invalid android argument: {arg1}; usage: just build android [debug|release|benchmark] [abi|scenario]"
                );
            }
        }
        [arg1, arg2] => (parse_variant(arg1)?, Abi::parse_selector(arg2)?),
        _ => bail!("usage: just build android [debug|release|benchmark] [abi|scenario]"),
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

fn uitest_command(workspace: &Workspace, arguments: &[String]) -> Result<Value> {
    let mut options = android::UitestOptions::default();
    for argument in arguments {
        match argument.as_str() {
            "--redroid" => options.boot = Some(android::DeviceBoot::Redroid),
            "--skip-build" => options.skip_build = true,
            "--no-install" => options.no_install = true,
            "--clear-data" => options.clear_data = true,
            _ if argument.starts_with("--peer=") => {
                ensure!(
                    options.peer_serial.is_none(),
                    "--peer must be specified once"
                );
                let peer = argument
                    .strip_prefix("--peer=")
                    .context("invalid --peer flag")?;
                ensure!(!peer.is_empty(), "--peer requires a nonempty serial");
                options.peer_serial = Some(peer.to_owned());
            }
            _ if argument.starts_with("--") => bail!("unknown uitest flag: {argument}"),
            _ => options.flows.push(argument.clone()),
        }
    }
    android::uitest(workspace, &options)
}

fn uishot_command(workspace: &Workspace, arguments: &[String]) -> Result<Value> {
    let mut options = android::UishotOptions::default();
    for argument in arguments {
        match argument.as_str() {
            "--redroid" => options.boot = Some(android::DeviceBoot::Redroid),
            _ if argument.starts_with("--") => bail!("unknown uishot flag: {argument}"),
            _ if options.name.is_none() => options.name = Some(argument.clone()),
            _ => bail!("usage: just uishot [name] [--redroid]"),
        }
    }
    android::uishot(workspace, &options)
}

fn tuitest_command(workspace: &Workspace, arguments: &[String]) -> Result<Value> {
    let mut options = tui_uitest::TuiUitestOptions::default();
    for argument in arguments {
        match argument.as_str() {
            "--skip-build" => options.skip_build = true,
            _ if argument.starts_with("--") => bail!("unknown tuitest flag: {argument}"),
            _ => options.flows.push(argument.clone()),
        }
    }
    tui_uitest::tui_uitest(workspace, &options)
}

/// `perf` has exactly two surfaces: bare `just perf` = host diagnostics (unchanged
/// methodology); `just perf device ...` = the debuggable device measurement planes.
fn perf_command(workspace: &Workspace, arguments: &[String]) -> Result<Value> {
    match arguments.split_first() {
        None => perf::run_diagnostics(workspace),
        Some((token, rest)) if token == "device" => perf_device::run_device_perf(workspace, rest),
        Some((token, _)) => bail!(
            "unknown perf argument `{token}`; usage: just perf [device [startup|cuj|engine|workspace|system|sync|profile|release|all]... [--corpus device] [--backend saf|direct] [--redroid] [--trace] [--write]]"
        ),
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
    if matches!(arguments, [mode] if mode == "delivery") {
        return quality::rust_delivery_ci(workspace);
    }
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
