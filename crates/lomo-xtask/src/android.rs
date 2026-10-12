use std::{
    collections::BTreeMap,
    fs,
    path::{Path, PathBuf},
    process::Command,
};

use anyhow::{Context, Result, bail, ensure};
use serde_json::Value;

use crate::{
    native::{self, Abi, NativeProfile},
    util::{find_files, kotlin, output, run, text_output},
    workspace::Workspace,
};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum AndroidVariant {
    Debug,
    Release,
}

impl AndroidVariant {
    const fn name(self) -> &'static str {
        match self {
            Self::Debug => "debug",
            Self::Release => "release",
        }
    }
}

const APP_APK_TASK_PREFIX: &str = "_app_buildAndroid";

pub fn abi_tag_name(abis: &[Abi]) -> String {
    if abis.len() == Abi::ALL.len() && Abi::ALL.iter().all(|abi| abis.contains(abi)) {
        "all".to_owned()
    } else if let [abi] = abis {
        abi.android_name().to_owned()
    } else {
        let mut names: Vec<_> = abis.iter().map(|abi| abi.android_name()).collect();
        names.sort_unstable();
        names.join("-")
    }
}

pub fn build(workspace: &Workspace, variant: AndroidVariant, abis: &[Abi]) -> Result<PathBuf> {
    let signing = if variant == AndroidVariant::Release {
        validate_baseline_sources(workspace)?;
        Some(SigningConfig::load(workspace)?)
    } else {
        None
    };
    let profile = match variant {
        AndroidVariant::Debug => NativeProfile::Dev,
        AndroidVariant::Release => NativeProfile::Release,
    };
    native::ensure_android_libraries(workspace, profile, abis)?;

    let abi_tag = abi_tag_name(abis);
    let build_dir = workspace.kotlin_build.clone();

    let _stash_guard = native::AbiStashGuard::stash_unselected(workspace, abis)?;

    let mut command = kotlin(workspace)?;
    command.args([
        "build",
        "--module",
        "app",
        "--platform",
        "android",
        "--variant",
        variant.name(),
        "--build-dir",
        build_dir.to_string_lossy().as_ref(),
    ]);
    run(&mut command)?;
    let apk = validate_built_apk(
        workspace,
        &build_dir,
        variant == AndroidVariant::Release,
        abis,
    )?;
    if let Some(signing) = signing {
        let output = publish_path(workspace, variant.name(), &abi_tag)?;
        sign_release(workspace, &apk, &signing, &output)
    } else {
        publish_apk(workspace, &apk, variant.name(), &abi_tag)
    }
}

/// Copy a validated APK to the canonical `target/lomo/apk/<variant>` directory using the
/// `<app-name>-<versionName>-<abi>.apk` naming contract (e.g. `Lomo-1.6.2-arm64-v8a.apk`).
pub fn publish_apk(
    workspace: &Workspace,
    source: &Path,
    variant: &str,
    abi_tag: &str,
) -> Result<PathBuf> {
    let output = publish_path(workspace, variant, abi_tag)?;
    if let Some(parent) = output.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::copy(source, &output).with_context(|| {
        format!(
            "failed to copy {variant} APK {} -> {}",
            source.display(),
            output.display()
        )
    })?;
    crate::util::emit_stderr(format_args!("xtask: {variant} APK at {}", output.display()));
    Ok(output)
}

fn publish_path(workspace: &Workspace, variant: &str, abi_tag: &str) -> Result<PathBuf> {
    let metadata = AppMetadata::load(workspace)?;
    Ok(workspace.apk_output_dir(variant).join(format!(
        "{}-{}-{abi_tag}.apk",
        metadata.name, metadata.version
    )))
}

pub fn validate_built_apk(
    workspace: &Workspace,
    build_dir: impl AsRef<Path>,
    release: bool,
    expected_abis: &[Abi],
) -> Result<PathBuf> {
    let build_dir = workspace.root.join(build_dir.as_ref());
    let apk = find_apk(&build_dir, release)?;
    let entries = apk_entries(&apk)?;
    for &abi in expected_abis {
        let expected = format!("lib/{}/{}", abi.android_name(), native::NATIVE_LIBRARY);
        if !entries.iter().any(|entry| entry == &expected) {
            bail!("{} is missing {expected}", apk.display());
        }
        validate_apk_elf(workspace, &apk, &expected, abi)?;
        for forbidden in [
            format!("lib/{}/libjnidispatch.so", abi.android_name()),
            format!("lib/{}/liblomo_native.so", abi.android_name()),
        ] {
            if entries.iter().any(|entry| entry == &forbidden) {
                bail!(
                    "{} retains forbidden legacy library {forbidden}",
                    apk.display()
                );
            }
        }
    }
    for abi in Abi::ALL {
        if !expected_abis.contains(&abi) {
            let forbidden = format!("lib/{}/{}", abi.android_name(), native::NATIVE_LIBRARY);
            if entries.iter().any(|entry| entry == &forbidden) {
                bail!(
                    "{} contains unselected ABI native library {forbidden}",
                    apk.display()
                );
            }
        }
    }
    if entries.iter().any(|entry| {
        let extension = Path::new(entry).extension();
        entry.starts_with("com/sun/jna/")
            && (entry.contains("jnidispatch")
                || extension.is_some_and(|value| value.eq_ignore_ascii_case("dll"))
                || extension.is_some_and(|value| value.eq_ignore_ascii_case("dylib")))
    }) {
        bail!("{} retains desktop JNA native resources", apk.display());
    }
    if entries
        .iter()
        .any(|entry| entry.contains("jnidispatch") || entry.contains("com/sun/jna/"))
    {
        bail!(
            "{} still packages JNA classes or jnidispatch assets",
            apk.display()
        );
    }
    if release {
        for baseline in [
            "assets/dexopt/baseline.prof",
            "assets/dexopt/baseline.profm",
        ] {
            if !entries.iter().any(|entry| entry == baseline) {
                bail!("release APK is missing {baseline}: {}", apk.display());
            }
        }
    }
    crate::util::emit_stderr(format_args!("xtask: validated {}", apk.display()));
    Ok(apk)
}

fn validate_apk_elf(workspace: &Workspace, apk: &Path, entry: &str, abi: Abi) -> Result<()> {
    let temporary = workspace.temp_dir("apk-elf")?;
    let target = temporary.join(entry.replace('/', "_"));
    let mut unzip = Command::new("unzip");
    unzip.args(["-p", apk.to_string_lossy().as_ref(), entry]);
    let bytes = output(&mut unzip)?.stdout;
    if bytes.is_empty() {
        bail!("failed to extract {entry} from {}", apk.display());
    }
    fs::write(&target, bytes)?;
    let readelf = native::ndk_tool(workspace, "llvm-readelf")?;
    let mut header = Command::new(readelf);
    header.args(["--file-header", target.to_string_lossy().as_ref()]);
    let header = text_output(&mut header)?;
    let expected_machine = match abi {
        Abi::Arm64 => "AArch64",
        Abi::Arm => "ARM",
        Abi::X86_64 => "Advanced Micro Devices X86-64",
        Abi::X86 => "Intel 80386",
    };
    if !header.contains(expected_machine) {
        bail!(
            "{entry} has the wrong ELF architecture for {}",
            abi.android_name()
        );
    }
    Ok(())
}

pub struct AppMetadata {
    pub(crate) name: String,
    pub(crate) version: String,
    pub(crate) version_code: i64,
    pub(crate) min_sdk: i64,
    pub(crate) target_sdk: i64,
    pub(crate) compile_sdk: i64,
}

impl AppMetadata {
    pub(crate) fn load(workspace: &Workspace) -> Result<Self> {
        Self::load_from_root(&workspace.root)
    }

    pub(crate) fn load_from_root(root: &Path) -> Result<Self> {
        let module = root.join("apps/android/app/module.yaml");
        let module_yaml = fs::read_to_string(&module)
            .with_context(|| format!("failed to read {}", module.display()))?;
        Ok(Self {
            name: read_app_name(root)?,
            version: read_version_name(&module_yaml)?,
            version_code: read_module_scalar(&module_yaml, "versionCode")?,
            min_sdk: read_module_scalar(&module_yaml, "minSdk")?,
            target_sdk: read_module_scalar(&module_yaml, "targetSdk")?,
            compile_sdk: read_module_scalar(&module_yaml, "compileSdk")?,
        })
    }
}

fn read_module_scalar(module_yaml: &str, key: &str) -> Result<i64> {
    for line in module_yaml.lines() {
        if let Some(rest) = line.trim().strip_prefix(&format!("{key}:")) {
            let value = rest.trim();
            return value.parse::<i64>().with_context(|| {
                format!("{key} in apps/android/app/module.yaml is not an integer: {value}")
            });
        }
    }
    bail!("apps/android/app/module.yaml is missing {key}")
}

fn read_app_name(root: &Path) -> Result<String> {
    let strings = root.join("apps/android/app/res/values/strings.xml");
    let content = fs::read_to_string(&strings)
        .with_context(|| format!("failed to read {}", strings.display()))?;
    for line in content.lines() {
        if !line.contains("name=\"app_name\"") {
            continue;
        }
        let trimmed = line.trim();
        if let Some(name) = trimmed
            .strip_prefix("<string name=\"app_name\">")
            .and_then(|name| name.strip_suffix("</string>"))
        {
            let name = name.trim();
            if !name.is_empty() {
                return Ok(name.to_owned());
            }
            bail!("{} has an empty app_name resource", strings.display());
        }
    }
    bail!("{} is missing the app_name resource", strings.display())
}

fn read_version_name(module_yaml: &str) -> Result<String> {
    for line in module_yaml.lines() {
        if let Some(rest) = line.trim().strip_prefix("versionName:") {
            let version = rest.trim().trim_matches('"');
            if !version.is_empty() {
                return Ok(version.to_owned());
            }
            bail!("apps/android/app/module.yaml has an empty versionName");
        }
    }
    bail!("apps/android/app/module.yaml is missing versionName")
}

fn find_apk(build_dir: &Path, release: bool) -> Result<PathBuf> {
    let mut apks = find_files(build_dir, "apk")?;
    apks.retain(|path| {
        let value = path.to_string_lossy();
        let variant_matches = if release {
            value.contains("release")
        } else {
            value.contains("debug")
        };
        variant_matches
            && path.components().any(|component| {
                component
                    .as_os_str()
                    .to_string_lossy()
                    .starts_with(APP_APK_TASK_PREFIX)
            })
    });
    apks.sort_by_key(|path| path.components().count());
    apks.into_iter().next().with_context(|| {
        format!(
            "no {} APK found under {}",
            if release { "release" } else { "debug" },
            build_dir.display()
        )
    })
}

fn apk_entries(apk: &Path) -> Result<Vec<String>> {
    let mut command = Command::new("unzip");
    command.args(["-Z1", apk.to_string_lossy().as_ref()]);
    Ok(text_output(&mut command)?
        .lines()
        .map(str::to_owned)
        .collect())
}

fn validate_baseline_sources(workspace: &Workspace) -> Result<()> {
    for relative in [
        "apps/android/app/src/main/baseline-prof.txt",
        "apps/android/app/src/main/baselineProfiles/generated.txt",
    ] {
        let path = workspace.root.join(relative);
        if !path.is_file() || fs::metadata(&path)?.len() == 0 {
            bail!(
                "release baseline profile is missing or empty: {}",
                path.display()
            );
        }
    }
    Ok(())
}

fn sign_release(
    workspace: &Workspace,
    apk: &Path,
    signing: &SigningConfig,
    output: &Path,
) -> Result<PathBuf> {
    let apksigner = apksigner(workspace)?;
    if let Some(parent) = output.parent() {
        fs::create_dir_all(parent)?;
    }
    let mut sign = Command::new(&apksigner);
    sign.env("LOMO_APK_STORE_PASSWORD", &signing.store_password)
        .env("LOMO_APK_KEY_PASSWORD", &signing.key_password)
        .args(["sign", "--ks"])
        .arg(&signing.store_file)
        .args([
            "--ks-key-alias",
            &signing.key_alias,
            "--ks-pass",
            "env:LOMO_APK_STORE_PASSWORD",
            "--key-pass",
            "env:LOMO_APK_KEY_PASSWORD",
            "--out",
        ])
        .arg(output)
        .arg(apk);
    run(&mut sign)?;

    let mut verify = Command::new(&apksigner);
    verify.args(["verify", "--verbose"]).arg(output);
    run(&mut verify)?;

    crate::util::emit_stderr(format_args!("xtask: signed {}", output.display()));
    Ok(output.to_path_buf())
}

fn apksigner(workspace: &Workspace) -> Result<PathBuf> {
    let root = workspace.android_sdk.join("build-tools");
    let mut candidates = Vec::new();
    if root.is_dir() {
        for entry in fs::read_dir(&root)? {
            let candidate = entry?.path().join("apksigner");
            if candidate.is_file() {
                candidates.push(candidate);
            }
        }
    }
    candidates.sort();
    candidates
        .pop()
        .with_context(|| format!("apksigner is missing under {}", root.display()))
}

struct SigningConfig {
    store_file: PathBuf,
    store_password: String,
    key_alias: String,
    key_password: String,
}

impl SigningConfig {
    fn load(workspace: &Workspace) -> Result<Self> {
        let mut values = BTreeMap::new();
        let properties = workspace.root.join("apps/android/app/keystore.properties");
        if properties.is_file() {
            for line in fs::read_to_string(&properties)?.lines() {
                let line = line.trim();
                if line.is_empty() || line.starts_with('#') {
                    continue;
                }
                if let Some((key, value)) = line.split_once('=') {
                    values.insert(key.trim().to_owned(), value.trim().to_owned());
                }
            }
        }
        for key in [
            "KEYSTORE_FILE",
            "KEYSTORE_PASSWORD",
            "KEY_ALIAS",
            "KEY_PASSWORD",
        ] {
            if let Ok(value) = std::env::var(key)
                && !value.is_empty()
            {
                values.insert(key.to_owned(), value);
            }
        }
        let store_file = required(&values, "KEYSTORE_FILE", "storeFile")?;
        let store_file = PathBuf::from(store_file);
        let store_file = if store_file.is_absolute() {
            store_file
        } else {
            workspace.root.join(store_file)
        };
        if !store_file.is_file() {
            bail!("release keystore does not exist: {}", store_file.display());
        }
        Ok(Self {
            store_file,
            store_password: required(&values, "KEYSTORE_PASSWORD", "storePassword")?,
            key_alias: required(&values, "KEY_ALIAS", "keyAlias")?,
            key_password: required(&values, "KEY_PASSWORD", "keyPassword")?,
        })
    }
}

fn required(values: &BTreeMap<String, String>, primary: &str, alternate: &str) -> Result<String> {
    values
        .get(primary)
        .or_else(|| values.get(alternate))
        .filter(|value| !value.is_empty())
        .cloned()
        .with_context(|| format!("release signing requires {primary} (or {alternate})"))
}

// ---- P-60 AndroidX Macrobenchmark harness ----

/// Instrumented benchmark scenarios; each maps to one `LomoMacrobenchmark` `JUnit` method in the
/// `apps/android/benchmark` harness module.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum BenchmarkScenario {
    Startup,
    Frame,
    Scroll,
}

impl BenchmarkScenario {
    pub const ALL: [Self; 3] = [Self::Startup, Self::Frame, Self::Scroll];

    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            Self::Startup => "startup",
            Self::Frame => "frame",
            Self::Scroll => "scroll",
        }
    }
}

/// Parses the optional `[scenario]` slot of `android benchmark`.
///
/// The Justfile recipe always forwards its default ABI token in this position, so an ABI
/// selector also resolves to all scenarios — the instrumented test APK is universal and the
/// ABI slot is documented unused.
///
/// # Errors
/// Unknown scenario names fail closed.
pub fn benchmark_scenarios(argument: Option<&String>) -> Result<Vec<BenchmarkScenario>> {
    match argument.map(String::as_str) {
        None | Some("all") => Ok(BenchmarkScenario::ALL.to_vec()),
        Some("startup") => Ok(vec![BenchmarkScenario::Startup]),
        Some("frame") => Ok(vec![BenchmarkScenario::Frame]),
        Some("scroll") => Ok(vec![BenchmarkScenario::Scroll]),
        Some(value) if Abi::parse_selector(value).is_ok() => Ok(BenchmarkScenario::ALL.to_vec()),
        Some(value) => bail!("unknown android benchmark scenario: {value}"),
    }
}

const BENCHMARK_MODULE: &str = "apps/android/benchmark";
const BENCHMARK_PACKAGE: &str = "com.lomo.benchmark";
const BENCHMARK_CLASS: &str = "com.lomo.benchmark.LomoMacrobenchmark";
const BENCHMARK_TARGET_PACKAGE: &str = "com.lomo.app";
const BENCHMARK_SUPPRESSED_ERRORS: [&str; 2] = ["EMULATOR", "DEBUGGABLE"];
/// AGP version the benchmark module pins — the same AGP the pinned Kotlin toolchain resolves
/// for its embedded Android bridge, verified by `gradle tasks` against the shared cache.
const BENCHMARK_AGP: &str = "9.2.1";

/// Redirected Gradle build directory of the benchmark module (`layout.buildDirectory` in its
/// `build.gradle.kts` resolves to `target/lomo/android-benchmark/gradle`).
fn benchmark_harness_build_dir(workspace: &Workspace) -> PathBuf {
    workspace
        .root
        .join(BENCHMARK_MODULE)
        .join("../../../target/lomo/android-benchmark/gradle")
}

/// Compiles the instrumented-test APK (`assembleDebugAndroidTest`) without a device — the
/// CI-verifiable half of the P-60 harness. Scenario execution still requires
/// `just android benchmark` on an attached device; this only proves the harness itself
/// configures, compiles and packages.
///
/// # Errors
/// Missing `gradle`, a failing harness build, or an absent androidTest APK all fail closed.
pub fn benchmark_harness_assemble(workspace: &Workspace) -> Result<()> {
    let gradle = gradle_command(workspace)?;
    let mut command = Command::new(&gradle);
    command
        .current_dir(workspace.root.join(BENCHMARK_MODULE))
        .args(["assembleDebugAndroidTest", "--console=plain"]);
    run(&mut command).context("android benchmark harness assembleDebugAndroidTest failed")?;
    let outputs = benchmark_harness_build_dir(workspace).join("outputs/apk/androidTest");
    let apks = find_files(&outputs, "apk")?;
    ensure!(
        !apks.is_empty(),
        "benchmark harness assembled no androidTest APK under {}",
        outputs.display()
    );
    for apk in &apks {
        crate::util::emit_stderr(format_args!(
            "xtask: benchmark harness androidTest APK at {}",
            apk.display()
        ));
    }
    Ok(())
}

/// Runs the `AndroidX` Macrobenchmark harness on the attached device.
///
/// The flow is fail-closed: no attached device aborts before any metric exists; the debug APK
/// is rebuilt for the device's primary ABI so measured artifacts match current sources; the
/// Gradle harness runs each scenario as a separate instrumented test method; and the androidx
/// `*-benchmarkData.json` files are parsed into one `android-benchmark.v1.json` summary whose
/// `measurement` block permanently records the emulator/debuggable suppression.
///
/// # Errors
/// Missing adb/device, APK build/install failure, missing `gradle`, a failing instrumented
/// test, or absent benchmark output all propagate as errors — the command never reports a
/// scenario it did not execute.
pub fn benchmark(workspace: &Workspace, scenarios: &[BenchmarkScenario]) -> Result<Value> {
    ensure!(
        !scenarios.is_empty(),
        "android benchmark requires at least one scenario"
    );
    let adb = adb_program(workspace)?;
    let serial = adb_primary_serial(&adb)?.with_context(
        || "android benchmark requires an attached device or emulator (`adb devices` lists none)",
    )?;
    let model = adb_getprop(&adb, &serial, "ro.product.model")?;
    let api_level = adb_getprop(&adb, &serial, "ro.build.version.sdk")?;
    let abilist = adb_getprop(&adb, &serial, "ro.product.cpu.abilist")?;
    let primary = abilist
        .split(',')
        .next()
        .filter(|value| !value.trim().is_empty())
        .with_context(|| format!("device {serial} reports empty ro.product.cpu.abilist"))?;
    let abi = Abi::parse(primary.trim())?;
    crate::util::emit_stderr(format_args!(
        "xtask: android benchmark on {serial} ({model} api={api_level} abi={})",
        abi.android_name()
    ));

    // Measure the artifact built from current sources: rebuild the debug APK for the
    // device's primary ABI rather than trusting a previously published universal APK.
    let apk = build(workspace, AndroidVariant::Debug, &[abi])?;
    adb_install(&adb, &serial, &apk)?;

    let gradle = gradle_command(workspace)?;
    let module_root = workspace.root.join(BENCHMARK_MODULE);
    let harness_build = benchmark_harness_build_dir(workspace);
    let report_dir = workspace.reports_dir().join("android-benchmark");
    if report_dir.exists() {
        fs::remove_dir_all(&report_dir)
            .with_context(|| format!("failed to reset {}", report_dir.display()))?;
    }
    fs::create_dir_all(&report_dir)
        .with_context(|| format!("failed to create {}", report_dir.display()))?;

    let mut scenario_results = Vec::with_capacity(scenarios.len());
    for scenario in scenarios {
        let mut command = Command::new(&gradle);
        command
            .current_dir(&module_root)
            .args(["connectedDebugAndroidTest", "--console=plain"])
            .arg(format!(
                "-Pandroid.testInstrumentationRunnerArguments.class={BENCHMARK_CLASS}#{}",
                scenario.name()
            ));
        run(&mut command).with_context(|| {
            format!(
                "instrumented {} benchmark failed on {serial} (see {} for the Gradle log)",
                scenario.name(),
                harness_build.display()
            )
        })?;
        let raw = collect_benchmark_outputs(&harness_build, &report_dir, scenario.name())?;
        let summary = summarize_benchmark_files(&raw, scenario.name())?;
        scenario_results.push(summary);
    }

    let report = serde_json::json!({
        "schema_version": 1,
        "kind": "androidx_macrobenchmark",
        "git_revision": benchmark_git_revision(workspace)?,
        "measurement": {
            "target_package": BENCHMARK_TARGET_PACKAGE,
            "harness_package": BENCHMARK_PACKAGE,
            "harness_module": BENCHMARK_MODULE,
            "harness_build_dir": harness_build,
            "agp": BENCHMARK_AGP,
            "suppressed_errors": BENCHMARK_SUPPRESSED_ERRORS,
            "accuracy_note": "Values come from an emulator plus a debuggable target; androidx.benchmark suppressedErrors are declared in every raw artifact. Emulator-grade evidence, not physical-device release truth.",
        },
        "device": {
            "serial": serial,
            "model": model,
            "api_level": api_level.parse::<u32>().unwrap_or(0),
            "abilist": abilist,
        },
        "apk": apk,
        "scenarios": scenario_results,
    });
    let report_path = report_dir.join("android-benchmark.v1.json");
    let pretty = serde_json::to_string_pretty(&report)
        .context("failed to serialize android benchmark report")?;
    fs::write(&report_path, format!("{pretty}\n"))
        .with_context(|| format!("failed to write {}", report_path.display()))?;
    crate::util::emit_stderr(format_args!("xtask: wrote {}", report_path.display()));
    Ok(report)
}

fn adb_program(workspace: &Workspace) -> Result<PathBuf> {
    let adb = workspace.android_sdk.join("platform-tools/adb");
    if adb.is_file() {
        return Ok(adb);
    }
    // Fall back to PATH so developer machines without the pinned SDK path still work.
    let probe = Command::new("adb").arg("version").output();
    match probe {
        Ok(output) if output.status.success() => Ok(PathBuf::from("adb")),
        _ => bail!("adb not found at {} or on PATH", adb.display()),
    }
}

fn adb_primary_serial(adb: &Path) -> Result<Option<String>> {
    let mut command = Command::new(adb);
    command.arg("devices");
    let devices = text_output(&mut command).context("adb devices failed")?;
    Ok(devices.lines().find_map(|line| {
        let mut parts = line.split_whitespace();
        match (parts.next(), parts.next()) {
            (Some(serial), Some("device")) if !serial.is_empty() && serial != "List" => {
                Some(serial.to_owned())
            }
            _ => None,
        }
    }))
}

fn adb_getprop(adb: &Path, serial: &str, key: &str) -> Result<String> {
    let mut command = Command::new(adb);
    command.args(["-s", serial, "shell", "getprop", key]);
    text_output(&mut command)
        .map(|value| value.trim().to_owned())
        .with_context(|| format!("adb getprop {key} failed on {serial}"))
}

fn adb_install(adb: &Path, serial: &str, apk: &Path) -> Result<()> {
    let mut command = Command::new(adb);
    command
        .args(["-s", serial, "install", "-r"])
        .arg(apk.as_os_str());
    run(&mut command).with_context(|| format!("failed to install {} on {serial}", apk.display()))
}

fn gradle_command(workspace: &Workspace) -> Result<PathBuf> {
    let probe = Command::new("gradle").arg("--version").output();
    match probe {
        Ok(output) if output.status.success() => Ok(PathBuf::from("gradle")),
        _ => bail!(
            "android benchmark requires a `gradle` binary on PATH (the instrumented-test harness lives in {} and is not part of the Kotlin toolchain project)",
            workspace.root.join(BENCHMARK_MODULE).display()
        ),
    }
}

/// Moves androidx `*-benchmarkData.json` artifacts for `scenario` from the harness build output
/// into the report directory and returns their paths.
fn collect_benchmark_outputs(
    harness_build: &Path,
    report_dir: &Path,
    scenario: &str,
) -> Result<Vec<PathBuf>> {
    let output_root = harness_build.join("outputs/connected_android_test_additional_output");
    let mut collected = Vec::new();
    if !output_root.is_dir() {
        return Ok(collected);
    }
    let mut pending = vec![output_root];
    while let Some(directory) = pending.pop() {
        for entry in fs::read_dir(&directory)
            .with_context(|| format!("failed to read {}", directory.display()))?
        {
            let path = entry?.path();
            if path.is_dir() {
                pending.push(path);
                continue;
            }
            let is_benchmark_artifact = path
                .file_name()
                .and_then(|name| name.to_str())
                .is_some_and(|name| name.ends_with("-benchmarkData.json"));
            if !is_benchmark_artifact {
                continue;
            }
            // androidx names the artifact after the test package
            // (`com.lomo.benchmark.test-benchmarkData.json`), never after the scenario, so
            // attribution must come from the artifact body: each instrumented run invokes
            // exactly one `LomoMacrobenchmark#<scenario>` method and `benchmarks[].name`
            // records it. A stale artifact from another scenario can never be attributed here.
            let text = fs::read_to_string(&path)
                .with_context(|| format!("failed to read {}", path.display()))?;
            let document: Value = serde_json::from_str(&text)
                .with_context(|| format!("{} is not valid benchmark JSON", path.display()))?;
            let belongs_to_scenario = document
                .get("benchmarks")
                .and_then(Value::as_array)
                .is_some_and(|benchmarks| {
                    benchmarks.iter().any(|benchmark| {
                        benchmark.get("name").and_then(Value::as_str) == Some(scenario)
                            && benchmark.get("className").and_then(Value::as_str)
                                == Some(BENCHMARK_CLASS)
                    })
                });
            if belongs_to_scenario {
                let file_name = path
                    .file_name()
                    .with_context(|| format!("{} has no file name", path.display()))?;
                let target = report_dir.join(format!("{scenario}-{}", file_name.to_string_lossy()));
                fs::copy(&path, &target).with_context(|| {
                    format!("failed to copy {} -> {}", path.display(), target.display())
                })?;
                collected.push(target);
            }
        }
    }
    collected.sort();
    Ok(collected)
}

/// Summarizes one scenario's raw benchmark artifacts: every `benchmarks[].metrics.*` median,
/// minimum, maximum and run count, plus the suppression declaration androidx embeds in
/// `context`. A scenario with no parsed benchmark is a hard error — partial evidence would
/// masquerade as a completed suite.
fn summarize_benchmark_files(paths: &[PathBuf], scenario: &str) -> Result<Value> {
    let mut metrics = serde_json::Map::new();
    let mut files = Vec::with_capacity(paths.len());
    let mut iterations = Value::Null;
    for path in paths {
        files.push(path.clone());
        let text = fs::read_to_string(path)
            .with_context(|| format!("failed to read {}", path.display()))?;
        let document: Value = serde_json::from_str(&text)
            .with_context(|| format!("{} is not valid benchmark JSON", path.display()))?;
        let benchmarks = document
            .get("benchmarks")
            .and_then(Value::as_array)
            .with_context(|| format!("{} carries no benchmarks array", path.display()))?;
        for benchmark in benchmarks {
            if iterations.is_null() {
                iterations = benchmark
                    .get("repeatIterations")
                    .cloned()
                    .unwrap_or(Value::Null);
            }
            if let Some(object) = benchmark.get("metrics").and_then(Value::as_object) {
                for (name, metric) in object {
                    metrics.insert(
                        name.clone(),
                        serde_json::json!({
                            "median": metric.get("median"),
                            "minimum": metric.get("minimum"),
                            "maximum": metric.get("maximum"),
                            "runs": metric.get("runs").and_then(Value::as_array).map_or(0, Vec::len),
                        }),
                    );
                }
            }
        }
    }
    ensure!(
        !metrics.is_empty(),
        "scenario {scenario} produced no benchmark metrics under {}",
        paths
            .first()
            .and_then(|path| path.parent())
            .map_or_else(|| "<none>".to_owned(), |dir| dir.display().to_string())
    );
    Ok(serde_json::json!({
        "name": scenario,
        "repeat_iterations": iterations,
        "metrics": metrics,
        "artifacts": files,
    }))
}

fn benchmark_git_revision(workspace: &Workspace) -> Result<String> {
    let mut command = Command::new("git");
    command
        .args(["rev-parse", "HEAD"])
        .current_dir(&workspace.root);
    let value = text_output(&mut command)?.trim().to_owned();
    if value.is_empty() {
        bail!("git revision is empty");
    }
    let mut status = Command::new("git");
    status
        .args(["status", "--porcelain"])
        .current_dir(&workspace.root);
    let porcelain = text_output(&mut status)?;
    if porcelain.trim().is_empty() {
        Ok(value)
    } else {
        Ok(format!("{value}-dirty"))
    }
}
