use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::thread;
use std::time::Duration;

use anyhow::{Context, Result, bail};

use crate::native::{self, Abi, NativeProfile};
use crate::util::{find_files, kotlin, output, run, text_output};
use crate::workspace::{self, Workspace};

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

pub fn build(workspace: &Workspace, variant: AndroidVariant) -> Result<PathBuf> {
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
    native::generate_all(workspace, profile)?;

    let build_dir = workspace
        .root
        .join(".kotlin/toolchain-build")
        .join(format!("android-{}", variant.name()));
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
    let apk = validate_built_apk(workspace, &build_dir, variant == AndroidVariant::Release)?;
    if let Some(signing) = signing {
        sign_release(workspace, &apk, &signing)
    } else {
        Ok(apk)
    }
}

pub fn validate_built_apk(
    workspace: &Workspace,
    build_dir: impl AsRef<Path>,
    release: bool,
) -> Result<PathBuf> {
    let build_dir = workspace.root.join(build_dir.as_ref());
    let apk = find_apk(&build_dir, release)?;
    let entries = apk_entries(&apk)?;
    for abi in Abi::ALL {
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

pub fn device_smoke(workspace: &Workspace) -> Result<()> {
    native::generate_all(workspace, NativeProfile::Dev)?;
    let build_dir = workspace.root.join(".kotlin/toolchain-build/device-smoke");
    let mut build = kotlin(workspace)?;
    build.args([
        "build",
        "--module",
        "native-smoke",
        "--platform",
        "android",
        "--variant",
        "debug",
        "--build-dir",
        build_dir.to_string_lossy().as_ref(),
    ]);
    run(&mut build)?;
    // Fail closed before install: stale Amper/Gradle jni merge can produce a dex-only APK that
    // boots then dies with UnsatisfiedLinkError (not authentic smoke GREEN).
    let apk = validate_built_apk(workspace, &build_dir, false)?;
    let adb = adb(workspace);

    let mut devices = Command::new(&adb);
    devices.arg("devices");
    let device_list = text_output(&mut devices)?;
    if !device_list.lines().any(|line| line.ends_with("\tdevice")) {
        bail!("no ready adb device; start an API 26 x86_64 emulator");
    }
    require_device_api_and_abi(&adb)?;

    let mut clear = Command::new(&adb);
    clear.args(["logcat", "-c"]);
    run(&mut clear)?;
    let mut install = Command::new(&adb);
    install.args(["install", "-r"]).arg(&apk);
    run(&mut install)?;
    // Clear app data so durable journal recovery starts from a clean seed phase.
    let mut clear_data = Command::new(&adb);
    clear_data.args(["shell", "pm", "clear", "com.lomo.nativesmoke"]);
    run(&mut clear_data)
        .context("pm clear com.lomo.nativesmoke must succeed for hermetic journal")?;

    launch_native_smoke(&adb)?;

    // seed → gap → recover may each force-kill; allow multiple RESTART_REQUIRED relaunches.
    let mut restart_count = 0_u32;
    let mut seen_restart_marker = 0_u32;
    for _ in 0..180 {
        let mut logs = Command::new(&adb);
        logs.args(["logcat", "-d", "-s", "LomoNativeSmoke:I", "*:S"]);
        let logs = text_output(&mut logs)?;
        if logs.contains("PASS") {
            crate::util::emit_stderr(format_args!("xtask: device smoke passed"));
            return Ok(());
        }
        if logs.contains("FAIL") {
            bail!("device smoke reported failure:\n{logs}");
        }
        let restart_markers =
            u32::try_from(logs.matches("RESTART_REQUIRED").count()).unwrap_or(u32::MAX);
        if restart_markers > seen_restart_marker && restart_count < 4 {
            seen_restart_marker = restart_markers;
            restart_count += 1;
            crate::util::emit_stderr(format_args!(
                "xtask: relaunching native-smoke for durable recovery (restart {restart_count})"
            ));
            thread::sleep(Duration::from_millis(500));
            launch_native_smoke(&adb)?;
        }
        thread::sleep(Duration::from_millis(250));
    }
    bail!("device smoke did not report PASS within 45 seconds")
}

fn launch_native_smoke(adb: &Path) -> Result<()> {
    let mut launch = Command::new(adb);
    launch.args([
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        "com.lomo.nativesmoke/.NativeSmokeActivity",
    ]);
    run(&mut launch)
}

/// Stage-1 device smoke requires API >= 26 and an ABI we package (prefer `x86_64` emulator).
fn require_device_api_and_abi(adb: &Path) -> Result<()> {
    let api = adb_shell_getprop(adb, "ro.build.version.sdk")?;
    let api_level: u32 = api
        .trim()
        .parse()
        .with_context(|| format!("device API level is not a number: {api:?}"))?;
    if api_level < workspace::ANDROID_API {
        bail!(
            "device API {api_level} is below required {}",
            workspace::ANDROID_API
        );
    }
    let abi = adb_shell_getprop(adb, "ro.product.cpu.abi")?;
    let abi = abi.trim();
    let supported = matches!(abi, "x86_64" | "arm64-v8a" | "x86" | "armeabi-v7a");
    if !supported {
        bail!("device ABI {abi:?} is not a packaged Android ABI");
    }
    crate::util::emit_stderr(format_args!(
        "xtask: device smoke target API {api_level} abi {abi}"
    ));
    Ok(())
}

fn adb_shell_getprop(adb: &Path, key: &str) -> Result<String> {
    let mut command = Command::new(adb);
    command.args(["shell", "getprop", key]);
    text_output(&mut command).map(|value| value.trim().to_owned())
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

fn find_apk(build_dir: &Path, release: bool) -> Result<PathBuf> {
    let mut apks = find_files(build_dir, "apk")?;
    apks.retain(|path| {
        let value = path.to_string_lossy();
        if release {
            value.contains("release")
        } else {
            value.contains("debug")
        }
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
        "app/src/main/baseline-prof.txt",
        "app/src/main/baselineProfiles/generated.txt",
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

fn sign_release(workspace: &Workspace, apk: &Path, signing: &SigningConfig) -> Result<PathBuf> {
    let apksigner = apksigner(workspace)?;
    let signed = workspace
        .root
        .join(".kotlin/toolchain-build/android-release/lomo-release.apk");
    if let Some(parent) = signed.parent() {
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
        .arg(&signed)
        .arg(apk);
    run(&mut sign)?;

    let mut verify = Command::new(apksigner);
    verify.args(["verify", "--verbose"]).arg(&signed);
    run(&mut verify)?;
    crate::util::emit_stderr(format_args!("xtask: signed {}", signed.display()));
    Ok(signed)
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

fn adb(workspace: &Workspace) -> PathBuf {
    let local = workspace.android_sdk.join("platform-tools/adb");
    if local.is_file() {
        local
    } else {
        PathBuf::from("adb")
    }
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
        let properties = workspace.root.join("app/keystore.properties");
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
