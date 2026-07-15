use std::fmt::Write;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

use anyhow::{Context, Result, bail};
use camino::Utf8PathBuf;
use sha2::{Digest, Sha256};
use uniffi_bindgen::bindings::{GenerateOptions, TargetLanguage, generate};

use crate::tools;
use crate::util::{cargo, output, remove_if_exists, repository_command, run, text_output};
use crate::workspace::{ANDROID_API, JNA_SHA256, JNA_VERSION, Workspace};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum NativeProfile {
    Dev,
    /// Fat-LTO shipping profile used by local `just ci` and release builds.
    Release,
    /// Thin-LTO PR/CI verification profile for four-ABI load/ELF contracts.
    ReleaseCi,
}

impl NativeProfile {
    fn apply_cargo_profile(self, command: &mut Command) {
        match self {
            Self::Dev => {}
            Self::Release => {
                command.arg("--release");
            }
            Self::ReleaseCi => {
                command.args(["--profile", "release-ci"]);
            }
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Abi {
    Arm64,
    Arm,
    X86_64,
    X86,
}

impl Abi {
    pub const ALL: [Self; 4] = [Self::Arm64, Self::Arm, Self::X86_64, Self::X86];

    pub fn parse(value: &str) -> Result<Self> {
        match value {
            "arm64-v8a" => Ok(Self::Arm64),
            "armeabi-v7a" => Ok(Self::Arm),
            "x86_64" => Ok(Self::X86_64),
            "x86" => Ok(Self::X86),
            _ => bail!("unsupported Android ABI: {value}"),
        }
    }

    pub const fn android_name(self) -> &'static str {
        match self {
            Self::Arm64 => "arm64-v8a",
            Self::Arm => "armeabi-v7a",
            Self::X86_64 => "x86_64",
            Self::X86 => "x86",
        }
    }

    const fn machine(self) -> &'static str {
        match self {
            Self::Arm64 => "AArch64",
            Self::Arm => "ARM",
            Self::X86_64 => "Advanced Micro Devices X86-64",
            Self::X86 => "Intel 80386",
        }
    }
}

pub fn generate_all(workspace: &Workspace, profile: NativeProfile) -> Result<()> {
    tools::ensure_quality(workspace)?;
    ensure_generated_not_tracked(workspace)?;
    generate_bindings(workspace)?;
    generate_android(workspace, profile, &Abi::ALL)
}

pub fn generate_selected(
    workspace: &Workspace,
    profile: NativeProfile,
    abis: &[Abi],
) -> Result<()> {
    tools::ensure_quality(workspace)?;
    ensure_generated_not_tracked(workspace)?;
    generate_bindings(workspace)?;
    generate_android(workspace, profile, abis)
}

pub fn generate_bindings(workspace: &Workspace) -> Result<()> {
    // Bindings include the tooling-only FeasibilityProbe API surface. Production
    // app/jniLibs are still built without the feature so probe symbols are absent
    // from the shipping .so; architecture tests forbid production Kotlin imports.
    let mut build = cargo(workspace);
    build.args([
        "build",
        "--locked",
        "-p",
        "lomo-native",
        "--features",
        "feasibility-probe",
    ]);
    run(&mut build)?;

    let library_name = match std::env::consts::OS {
        "linux" => "liblomo_native.so",
        "macos" => "liblomo_native.dylib",
        other => bail!("binding generation is unsupported on host OS {other}"),
    };
    let library = workspace.rust_target().join("debug").join(library_name);
    if !library.is_file() {
        bail!("host native library is missing: {}", library.display());
    }

    let temporary = workspace.temp_dir("bindings")?;
    let source = Utf8PathBuf::from_path_buf(library)
        .map_err(|path| anyhow::anyhow!("library path is not UTF-8: {}", path.display()))?;
    let out_dir = Utf8PathBuf::from_path_buf(temporary.join("generated"))
        .map_err(|path| anyhow::anyhow!("output path is not UTF-8: {}", path.display()))?;
    let original_directory = std::env::current_dir().context("failed to read current directory")?;
    std::env::set_current_dir(&workspace.rust).context("failed to enter Rust workspace")?;
    let generation = generate(GenerateOptions {
        languages: vec![TargetLanguage::Kotlin],
        source,
        out_dir: out_dir.clone(),
        config_override: None,
        format: false,
        crate_filter: Some("lomo_native".to_owned()),
        metadata_no_deps: false,
    });
    std::env::set_current_dir(&original_directory)
        .context("failed to restore repository directory after binding generation")?;
    generation.context("UniFFI Kotlin binding generation failed")?;

    let generated = out_dir.join("com/lomo/rust/lomo_native.kt");
    let text = fs::read_to_string(&generated)
        .with_context(|| format!("UniFFI did not produce {generated}"))?;
    let canonical = canonical_binding(&text)?;
    remove_if_exists(&workspace.generated_bindings())?;
    fs::create_dir_all(workspace.generated_bindings())?;
    let target = workspace.generated_bindings().join("lomo_native.kt");
    fs::write(&target, canonical)
        .with_context(|| format!("failed to write {}", target.display()))?;
    eprintln!("xtask: generated {}", target.display());
    Ok(())
}

pub fn generate_android(workspace: &Workspace, profile: NativeProfile, abis: &[Abi]) -> Result<()> {
    ensure_ndk(workspace)?;
    remove_selected_abis(workspace, abis)?;
    remove_smoke_abis(workspace, abis)?;

    // Production packaging: no feasibility-probe symbols.
    let production_jni = workspace.jni_libs();
    build_android_ndk(workspace, profile, abis, &production_jni, &[])?;
    // Tooling smoke packaging: enable FeasibilityProbe for device lifecycle checks.
    let smoke_jni = workspace.root.join("native-smoke/jniLibs");
    build_android_ndk(workspace, profile, abis, &smoke_jni, &["feasibility-probe"])?;

    let jna = ensure_jna_aar(workspace)?;
    for &abi in abis {
        install_jnidispatch(&jna, abi, &production_jni)?;
        install_jnidispatch(&jna, abi, &smoke_jni)?;
    }
    verify_native_tree(workspace, abis)?;
    verify_smoke_native_tree(workspace, abis)
}

fn build_android_ndk(
    workspace: &Workspace,
    profile: NativeProfile,
    abis: &[Abi],
    output_dir: &Path,
    features: &[&str],
) -> Result<()> {
    fs::create_dir_all(output_dir)?;
    let mut command = cargo(workspace);
    command.env("ANDROID_NDK_HOME", workspace.ndk_root()).args([
        "ndk",
        "--platform",
        &ANDROID_API.to_string(),
    ]);
    for abi in abis {
        command.args(["--target", abi.android_name()]);
    }
    command
        .arg("--output-dir")
        .arg(output_dir)
        .args(["build", "--locked", "-p", "lomo-native"]);
    if !features.is_empty() {
        command.arg("--features").arg(features.join(","));
    }
    profile.apply_cargo_profile(&mut command);
    run(&mut command)
}

fn install_jnidispatch(jna: &Path, abi: Abi, output_root: &Path) -> Result<()> {
    let directory = output_root.join(abi.android_name());
    fs::create_dir_all(&directory)?;
    let mut unzip = Command::new("unzip");
    unzip.args([
        "-p",
        jna.to_string_lossy().as_ref(),
        &format!("jni/{}/libjnidispatch.so", abi.android_name()),
    ]);
    let dispatcher = output(&mut unzip)?.stdout;
    if dispatcher.is_empty() {
        bail!("JNA AAR has no dispatcher for {}", abi.android_name());
    }
    fs::write(directory.join("libjnidispatch.so"), dispatcher)?;
    Ok(())
}

fn remove_smoke_abis(workspace: &Workspace, abis: &[Abi]) -> Result<()> {
    for abi in abis {
        let path = workspace
            .root
            .join("native-smoke/jniLibs")
            .join(abi.android_name());
        remove_if_exists(&path)?;
    }
    Ok(())
}

fn verify_smoke_native_tree(workspace: &Workspace, abis: &[Abi]) -> Result<()> {
    let readelf = ndk_tool(workspace, "llvm-readelf")?;
    for abi in abis {
        for library in ["liblomo_native.so", "libjnidispatch.so"] {
            let path = workspace
                .root
                .join("native-smoke/jniLibs")
                .join(abi.android_name())
                .join(library);
            if !path.is_file() {
                bail!("smoke native library is missing: {}", path.display());
            }
            let mut header = Command::new(&readelf);
            header.args(["--file-header", path.to_string_lossy().as_ref()]);
            let header = text_output(&mut header)?;
            if !header.contains(abi.machine()) {
                bail!(
                    "smoke {} has wrong ELF architecture for {}",
                    path.display(),
                    abi.android_name()
                );
            }
        }
    }
    Ok(())
}

pub fn verify_native_tree(workspace: &Workspace, abis: &[Abi]) -> Result<()> {
    let readelf = ndk_tool(workspace, "llvm-readelf")?;
    for abi in abis {
        for library in ["liblomo_native.so", "libjnidispatch.so"] {
            let path = workspace.jni_libs().join(abi.android_name()).join(library);
            if !path.is_file() {
                bail!("native library is missing: {}", path.display());
            }
            let mut header = Command::new(&readelf);
            header.args(["--file-header", path.to_string_lossy().as_ref()]);
            let header = text_output(&mut header)?;
            if !header.contains(abi.machine()) {
                bail!(
                    "{} has wrong ELF architecture for {}",
                    path.display(),
                    abi.android_name()
                );
            }

            let mut dynamic = Command::new(&readelf);
            dynamic.args(["--dynamic", path.to_string_lossy().as_ref()]);
            let dynamic = text_output(&mut dynamic)?;
            if dynamic.contains("libstdc++") || dynamic.contains("lomo_sync_ffi") {
                bail!("{} has a forbidden native dependency", path.display());
            }
        }
    }
    Ok(())
}

pub fn ndk_tool(workspace: &Workspace, name: &str) -> Result<PathBuf> {
    let tool = workspace
        .ndk_root()
        .join("toolchains/llvm/prebuilt")
        .join(ndk_host_tag()?)
        .join("bin")
        .join(name);
    if !tool.is_file() {
        bail!(
            "NDK tool is missing: {}; run `just bootstrap`",
            tool.display()
        );
    }
    Ok(tool)
}

fn ensure_generated_not_tracked(workspace: &Workspace) -> Result<()> {
    let mut command = repository_command(workspace, "git");
    command.args(["ls-files", "--", "rust-bindings/src", "app/jniLibs"]);
    let tracked = text_output(&mut command)?;
    if !tracked.trim().is_empty() {
        bail!("generated bindings/native libraries are tracked by Git:\n{tracked}");
    }
    Ok(())
}

fn ensure_ndk(workspace: &Workspace) -> Result<()> {
    if !workspace
        .ndk_root()
        .join("toolchains/llvm/prebuilt")
        .is_dir()
    {
        bail!(
            "Android NDK {} is missing at {}; run `just bootstrap`",
            crate::workspace::NDK_VERSION,
            workspace.ndk_root().display()
        );
    }
    Ok(())
}

fn remove_selected_abis(workspace: &Workspace, abis: &[Abi]) -> Result<()> {
    fs::create_dir_all(workspace.jni_libs())?;
    for abi in abis {
        remove_if_exists(&workspace.jni_libs().join(abi.android_name()))?;
    }
    Ok(())
}

fn ensure_jna_aar(workspace: &Workspace) -> Result<PathBuf> {
    let cache = workspace
        .root
        .join(".cache/native")
        .join(format!("jna-{JNA_VERSION}.aar"));
    if cache.is_file() && sha256(&cache)? == JNA_SHA256 {
        return Ok(cache);
    }
    if let Some(parent) = cache.parent() {
        fs::create_dir_all(parent)?;
    }
    let url = format!(
        "https://repo1.maven.org/maven2/net/java/dev/jna/jna/{JNA_VERSION}/jna-{JNA_VERSION}.aar"
    );
    let mut curl = repository_command(workspace, "curl");
    curl.args([
        "--location",
        "--fail",
        "--silent",
        "--show-error",
        "--output",
    ])
    .arg(&cache)
    .arg(url);
    run(&mut curl)?;
    let actual = sha256(&cache)?;
    if actual != JNA_SHA256 {
        bail!("JNA AAR checksum mismatch: expected {JNA_SHA256}, found {actual}");
    }
    Ok(cache)
}

fn sha256(path: &Path) -> Result<String> {
    let bytes = fs::read(path).with_context(|| format!("failed to read {}", path.display()))?;
    let digest = Sha256::digest(bytes);
    let mut encoded = String::with_capacity(digest.len() * 2);
    for byte in digest {
        write!(&mut encoded, "{byte:02x}").context("failed to encode SHA-256")?;
    }
    Ok(encoded)
}

fn canonical_binding(text: &str) -> Result<String> {
    let package = text
        .lines()
        .position(|line| line.starts_with("package "))
        .context("generated binding has no package declaration")?;
    let mut canonical =
        String::from("// Generated from rust/native by lomo-xtask.\n// Do not edit manually.\n\n");
    for line in text.lines().skip(package) {
        if !line.trim_start().starts_with("@Suppress") {
            canonical.push_str(line);
            canonical.push('\n');
        }
    }
    Ok(canonical)
}

fn ndk_host_tag() -> Result<&'static str> {
    match (std::env::consts::OS, std::env::consts::ARCH) {
        ("linux", "x86_64") => Ok("linux-x86_64"),
        ("macos", "x86_64" | "aarch64") => Ok("darwin-x86_64"),
        (os, architecture) => bail!("unsupported NDK host: {os}-{architecture}"),
    }
}
