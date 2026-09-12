use std::{
    collections::BTreeMap,
    fs,
    path::{Path, PathBuf},
    process::Command,
};

use anyhow::{Context, Result, bail};

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
