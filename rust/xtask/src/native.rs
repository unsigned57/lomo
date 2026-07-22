use std::collections::BTreeMap;
use std::fmt::Write;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::Instant;

use anyhow::{Context, Result, bail};

use crate::tools;
use crate::util::{cargo, remove_if_exists, repository_command, run, text_output};
use crate::workspace::{ANDROID_API, Workspace};

/// Final Android library stem produced by `BoltFFI` packaging for this repository.
pub const NATIVE_LIBRARY: &str = "liblomo_native_jni.so";
const GENERATED_OWNER: &str = "LomoNativeBridge.kt";
const GENERATED_PACKAGE_DIR: &str = "com/lomo/nativebridge";

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum NativeProfile {
    Dev,
    /// Fat-LTO shipping profile used by local `just ci` and release builds.
    Release,
    /// Thin-LTO PR/CI verification profile for four-ABI load/ELF contracts.
    ReleaseCi,
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

    /// `BoltFFI` `targets.android.architectures` name.
    const fn boltffi_arch(self) -> &'static str {
        match self {
            Self::Arm64 => "arm64",
            Self::Arm => "armv7",
            Self::X86_64 => "x86_64",
            Self::X86 => "x86",
        }
    }
}

pub fn generate_all(workspace: &Workspace, profile: NativeProfile) -> Result<()> {
    tools::ensure_quality(workspace)?;
    tools::ensure_boltffi(workspace)?;
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
    tools::ensure_boltffi(workspace)?;
    ensure_generated_not_tracked(workspace)?;
    generate_bindings(workspace)?;
    generate_android(workspace, profile, abis)
}

pub fn generate_bindings(workspace: &Workspace) -> Result<()> {
    tools::ensure_boltffi(workspace)?;
    let temporary = workspace.temp_dir("boltffi-bindings")?;
    let kotlin_out = temporary.join("kotlin");
    let started = Instant::now();
    run_boltffi_generate_kotlin(workspace, &kotlin_out)?;
    let elapsed_ms = started.elapsed().as_millis();

    let generated = locate_generated_kotlin(&kotlin_out)?;
    let text = fs::read_to_string(&generated)
        .with_context(|| format!("BoltFFI did not produce {}", generated.display()))?;
    let canonical = canonicalize_binding(&text)?;
    remove_if_exists(&workspace.generated_bindings())?;
    fs::create_dir_all(workspace.generated_bindings())?;
    let target = workspace.generated_bindings().join(GENERATED_OWNER);
    fs::write(&target, &canonical)
        .with_context(|| format!("failed to write {}", target.display()))?;
    crate::util::emit_stderr(format_args!(
        "xtask: generated {} ({} bytes, {} lines, warm generate {} ms)",
        target.display(),
        canonical.len(),
        canonical.lines().count(),
        elapsed_ms
    ));
    Ok(())
}

pub fn generate_android(workspace: &Workspace, profile: NativeProfile, abis: &[Abi]) -> Result<()> {
    ensure_ndk(workspace)?;
    tools::ensure_boltffi(workspace)?;
    remove_selected_abis(workspace, abis)?;
    remove_smoke_abis(workspace, abis)?;

    let temporary = workspace.temp_dir("boltffi-android")?;
    let pack_root = temporary.join("pack");
    let started = Instant::now();
    run_boltffi_pack_android(workspace, profile, &pack_root, abis)?;
    let elapsed_ms = started.elapsed().as_millis();

    let jni_source = locate_jni_libs(&pack_root)?;
    let production_jni = workspace.jni_libs();
    let smoke_jni = workspace.root.join("native-smoke/jniLibs");
    publish_selected_abis(&jni_source, &production_jni, abis)?;
    // Formal engine surface is shared: production and smoke use the same library.
    publish_selected_abis(&jni_source, &smoke_jni, abis)?;
    verify_native_tree(workspace, abis, profile)?;
    verify_smoke_native_tree(workspace, abis)?;
    crate::util::emit_stderr(format_args!(
        "xtask: packaged {NATIVE_LIBRARY} for {} ABI(s) in {elapsed_ms} ms",
        abis.len()
    ));
    Ok(())
}

/// Build the linked `lomo-feasibility-device` cdylib for all Android ABIs, ELF-verify, and
/// record per-ABI sizes. Not packaged into production `app/jniLibs`.
#[expect(
    clippy::too_many_lines,
    reason = "packaging + ELF + evidence write is a single audit trail"
)]
pub fn verify_feasibility_android_targets(workspace: &Workspace, abis: &[Abi]) -> Result<()> {
    ensure_ndk(workspace)?;
    tools::ensure_quality(workspace)?;
    let output_dir = workspace.root.join("build/feasibility-device/jniLibs");
    remove_if_exists(&output_dir)?;
    fs::create_dir_all(&output_dir)?;

    let mut command = cargo(workspace);
    command.env("ANDROID_NDK_HOME", workspace.ndk_root()).args([
        "ndk",
        "--platform",
        &ANDROID_API.to_string(),
    ]);
    for abi in abis {
        command.args(["--target", abi.android_name()]);
    }
    command.arg("--output-dir").arg(&output_dir).args([
        "build",
        "--locked",
        "-p",
        "lomo-feasibility-device",
        "--profile",
        "release-ci",
    ]);
    run(&mut command)?;

    let readelf = ndk_tool(workspace, "llvm-readelf")?;
    let mut size_rows = String::new();
    let mut sizes = BTreeMap::new();
    for &abi in abis {
        let so = output_dir
            .join(abi.android_name())
            .join("liblomo_feasibility_device.so");
        if !so.is_file() {
            bail!(
                "missing linked feasibility-device library: {}",
                so.display()
            );
        }
        let mut header = Command::new(&readelf);
        header.args(["--file-header", so.to_string_lossy().as_ref()]);
        let header = text_output(&mut header)?;
        if !header.contains(abi.machine()) {
            bail!(
                "{} has wrong ELF architecture for {}",
                so.display(),
                abi.android_name()
            );
        }
        let bytes = fs::metadata(&so)
            .with_context(|| format!("stat {}", so.display()))?
            .len();
        let strings_out = text_output(Command::new("strings").arg(&so))?;
        let required = [
            lomo_feasibility::MARKER_GIT2,
            lomo_feasibility::MARKER_REQWEST_RUSTLS,
            lomo_feasibility::MARKER_SQLITE,
        ];
        let mut missing = Vec::new();
        for marker in required {
            if !strings_out.contains(marker) {
                missing.push(marker);
            }
        }
        if !missing.is_empty() {
            bail!(
                "{} missing exact LOMO retention markers after LTO: {:?}; \
                 ensure run_feasibility_device_bundle returns candidate_link_markers + MARKER_SQLITE. \
                 Note: generic OpenSSL/aws-lc strings are intentionally NOT accepted.",
                so.display(),
                missing
            );
        }
        sizes.insert(abi.android_name().to_owned(), bytes);
        writeln!(
            size_rows,
            "| `{}` | pass (ELF + exact LOMO_LINK_MARKER_*) | {} |",
            abi.android_name(),
            bytes
        )
        .context("write native size evidence row")?;
    }

    let mut host = cargo(workspace);
    host.args([
        "test",
        "--locked",
        "-p",
        "lomo-feasibility-device",
        "--test",
        "device_bundle_contract",
        "--",
        "--nocapture",
    ]);
    run(&mut host)?;

    let evidence = workspace
        .root
        .join("fixtures/baseline/feasibility-android-targets.v1.md");
    let mut body = String::from(
        "# Feasibility dependency four-ABI **linked** evidence\n\n\
         Tooling crate `lomo-feasibility-device` retains rusqlite/pulldown-cmark/reqwest/git2 via \
         live call paths (`candidate_link_markers` + SQLite/Markdown probes) in \
         `liblomo_feasibility_device.so` for Android API 26 via cargo-ndk (`release-ci`).\n\n\
         **Proves:** constructor/version retention of candidate crates after LTO (volume selection).\n\
         **Does not prove:** full smart-HTTP push/rebase or HTTP stream matrices inside this SO \
         (those remain host-fixture contracts).\n\n\
         Each `.so` is `strings`-checked for exact sentinels \
         `LOMO_LINK_MARKER_GIT2_v1`, `LOMO_LINK_MARKER_REQWEST_RUSTLS_v1`, \
         `LOMO_LINK_MARKER_SQLITE_v1`. Generic OpenSSL/aws-lc strings are **not** accepted.\n\
         Not packaged into production `app/jniLibs`.\n\n\
         | ABI | Result | `.so` bytes |\n| --- | --- | --- |\n",
    );
    body.push_str(&size_rows);
    body.push_str(
        "\nHost runtime: `cargo test -p lomo-feasibility-device --test device_bundle_contract` \
         (SQLite+Markdown+exact markers).\n\
         Device process load of this SO is optional tooling; production ownership still stage-gated.\n\n\
         Generated by `lomo-xtask` `verify_feasibility_android_targets`.\n",
    );
    fs::write(&evidence, body).with_context(|| format!("write {}", evidence.display()))?;

    let size_json = workspace
        .root
        .join("fixtures/baseline/feasibility-device-size.v1.json");
    let document = serde_json::json!({
        "schema_version": 1,
        "description": "Linked feasibility-device .so sizes after live candidate_link_markers. Proves constructor retention volume only — not full smart-HTTP/push/rebase inside the SO.",
        "liblomo_feasibility_device_so_bytes": sizes,
        "not_production": true,
        "stale": false,
        "symbol_retention": {
            "method": "strings exact match",
            "required_markers": [
                "LOMO_LINK_MARKER_GIT2_v1",
                "LOMO_LINK_MARKER_REQWEST_RUSTLS_v1",
                "LOMO_LINK_MARKER_SQLITE_v1"
            ],
            "rejected_as_insufficient": ["openssl-alone", "aws-lc-alone", "cargo-dep-edge"]
        }
    });
    fs::write(
        &size_json,
        format!(
            "{}\n",
            serde_json::to_string_pretty(&document)
                .context("serialize feasibility-device sizes")?
        ),
    )
    .with_context(|| format!("write {}", size_json.display()))?;
    crate::util::emit_stderr(format_args!("xtask: wrote {}", evidence.display()));
    crate::util::emit_stderr(format_args!("xtask: wrote {}", size_json.display()));
    Ok(())
}

fn run_boltffi_generate_kotlin(workspace: &Workspace, output: &Path) -> Result<()> {
    fs::create_dir_all(output)?;
    let boltffi = tools::boltffi_binary(workspace)?;
    let mut command = Command::new(boltffi);
    command
        .current_dir(workspace.rust.join("native"))
        .env("CARGO_HOME", &workspace.cargo_home)
        // Absolute target: boltffi may spawn cargo from rust/native; a relative
        // CARGO_TARGET_DIR would nest under rust/native/rust/target.
        .env("CARGO_TARGET_DIR", workspace.rust_target())
        .env("ANDROID_NDK_HOME", workspace.ndk_root())
        .env("ANDROID_NDK_ROOT", workspace.ndk_root())
        .env("ANDROID_HOME", &workspace.android_sdk)
        .env("ANDROID_SDK_ROOT", &workspace.android_sdk)
        .args(["generate", "kotlin", "--output"])
        .arg(output);
    run(&mut command)
}

fn run_boltffi_pack_android(
    workspace: &Workspace,
    profile: NativeProfile,
    pack_root: &Path,
    abis: &[Abi],
) -> Result<()> {
    fs::create_dir_all(pack_root)?;
    let kotlin_out = pack_root.join("kotlin");
    let jni_out = pack_root.join("jniLibs");
    let header_out = pack_root.join("include");
    fs::create_dir_all(&kotlin_out)?;
    fs::create_dir_all(&jni_out)?;
    fs::create_dir_all(&header_out)?;

    let overlay = pack_root.join("boltffi.overlay.toml");
    write_android_pack_overlay(&overlay, &kotlin_out, &jni_out, &header_out, abis)?;

    // Official `boltffi pack android` always regenerates jni_glue.c, so a pre-pack patch is
    // overwritten. Intercept NDK clang during pack to inject the missing callback helper just
    // before jni_glue.c is compiled — reusing BoltFFI build/generate/link end-to-end.
    let wrapped_ndk = prepare_ndk_clang_glue_wrappers(workspace, pack_root)?;
    let boltffi = tools::boltffi_binary(workspace)?;
    let mut command = Command::new(boltffi);
    command
        .current_dir(workspace.rust.join("native"))
        .env("CARGO_HOME", &workspace.cargo_home)
        .env("CARGO_TARGET_DIR", workspace.rust_target())
        .env("ANDROID_NDK_HOME", &wrapped_ndk)
        .env("ANDROID_NDK_ROOT", &wrapped_ndk)
        .env("ANDROID_HOME", &workspace.android_sdk)
        .env("ANDROID_SDK_ROOT", &workspace.android_sdk)
        .args(["--overlay"])
        .arg(&overlay)
        .args(["pack", "android"]);
    match profile {
        NativeProfile::Dev => {}
        NativeProfile::Release => {
            // Size-first Android shipping profile. Do not also pass boltffi
            // `--release`: Cargo rejects combining that with `--profile`.
            apply_android_release_size_env(&mut command);
            command.args(["--cargo-arg", "--profile", "--cargo-arg", "release-android"]);
        }
        NativeProfile::ReleaseCi => {
            // Thin LTO for PR speed with the same panic/unwind size policy so
            // ELF contracts stay close to shipping characteristics.
            apply_android_release_size_env(&mut command);
            command.args(["--cargo-arg", "--profile", "--cargo-arg", "release-ci"]);
        }
    }
    run(&mut command)?;
    if !matches!(profile, NativeProfile::Dev) {
        strip_packaged_native_libraries(workspace, &jni_out, abis)?;
    }
    crate::util::emit_stderr(format_args!(
        "xtask: boltffi pack android published into {}",
        jni_out.display()
    ));
    Ok(())
}

fn strip_packaged_native_libraries(
    workspace: &Workspace,
    jni_root: &Path,
    abis: &[Abi],
) -> Result<()> {
    let strip = workspace
        .ndk_root()
        .join("toolchains/llvm/prebuilt")
        .join(ndk_host_tag()?)
        .join("bin")
        .join("llvm-strip");
    if !strip.is_file() {
        bail!("llvm-strip missing: {}", strip.display());
    }
    for &abi in abis {
        let library = jni_root.join(abi.android_name()).join(NATIVE_LIBRARY);
        if !library.is_file() {
            bail!("pack output missing {}", library.display());
        }
        let mut command = Command::new(&strip);
        command.args(["--strip-all"]).arg(&library);
        run(&mut command)?;
    }
    Ok(())
}

fn write_android_pack_overlay(
    overlay: &Path,
    kotlin_out: &Path,
    jni_out: &Path,
    header_out: &Path,
    abis: &[Abi],
) -> Result<()> {
    let architectures = abis
        .iter()
        .map(|abi| format!("\"{}\"", abi.boltffi_arch()))
        .collect::<Vec<_>>()
        .join(", ");
    let text = format!(
        r#"[targets.android]
architectures = [{architectures}]

[targets.android.kotlin]
output = "{kotlin}"

[targets.android.pack]
output = "{jni}"

[targets.android.header]
output = "{header}"
"#,
        kotlin = escape_toml_path(kotlin_out),
        jni = escape_toml_path(jni_out),
        header = escape_toml_path(header_out),
    );
    fs::write(overlay, text).with_context(|| format!("failed to write {}", overlay.display()))
}

fn escape_toml_path(path: &Path) -> String {
    path.display().to_string().replace('\\', "\\\\")
}

/// Build a synthetic NDK root whose clang drivers patch `jni_glue.c` then exec the real NDK clang.
fn prepare_ndk_clang_glue_wrappers(workspace: &Workspace, pack_root: &Path) -> Result<PathBuf> {
    let real_ndk = workspace.ndk_root();
    let host_tag = ndk_host_tag()?;
    let wrap_root = pack_root.join("ndk-wrap");
    remove_if_exists(&wrap_root)?;

    let real_prebuilt = real_ndk.join("toolchains/llvm/prebuilt").join(host_tag);
    let wrap_prebuilt = wrap_root.join("toolchains/llvm/prebuilt").join(host_tag);
    let real_bin = real_prebuilt.join("bin");
    let wrap_bin = wrap_prebuilt.join("bin");
    if !real_bin.is_dir() {
        bail!("NDK prebuilt bin missing: {}", real_bin.display());
    }
    fs::create_dir_all(&wrap_bin)?;

    // Mirror non-bin prebuilt contents (libs, include, etc.) via symlinks.
    for entry in
        fs::read_dir(&real_prebuilt).with_context(|| format!("read {}", real_prebuilt.display()))?
    {
        let entry = entry?;
        let name = entry.file_name();
        if name == "bin" {
            continue;
        }
        let target = wrap_prebuilt.join(&name);
        symlink_path(&entry.path(), &target)?;
    }

    // Mirror top-level NDK entries except toolchains (we synthesize the host prebuilt above).
    for entry in fs::read_dir(&real_ndk).with_context(|| format!("read {}", real_ndk.display()))? {
        let entry = entry?;
        let name = entry.file_name();
        if name == "toolchains" {
            continue;
        }
        let target = wrap_root.join(&name);
        symlink_path(&entry.path(), &target)?;
    }
    // Keep other toolchains/llvm content available if present.
    let real_llvm = real_ndk.join("toolchains/llvm");
    let wrap_llvm = wrap_root.join("toolchains/llvm");
    fs::create_dir_all(&wrap_llvm)?;
    if real_llvm.is_dir() {
        for entry in
            fs::read_dir(&real_llvm).with_context(|| format!("read {}", real_llvm.display()))?
        {
            let entry = entry?;
            let name = entry.file_name();
            if name == "prebuilt" {
                continue;
            }
            symlink_path(&entry.path(), &wrap_llvm.join(&name))?;
        }
    }
    let real_prebuilt_root = real_ndk.join("toolchains/llvm/prebuilt");
    let wrap_prebuilt_root = wrap_root.join("toolchains/llvm/prebuilt");
    fs::create_dir_all(&wrap_prebuilt_root)?;
    if real_prebuilt_root.is_dir() {
        for entry in fs::read_dir(&real_prebuilt_root)
            .with_context(|| format!("read {}", real_prebuilt_root.display()))?
        {
            let entry = entry?;
            let name = entry.file_name();
            if name == host_tag {
                continue;
            }
            symlink_path(&entry.path(), &wrap_prebuilt_root.join(&name))?;
        }
    }

    let patch_script = pack_root.join("patch_jni_glue.py");
    write_jni_glue_patch_script(&patch_script)?;

    for entry in fs::read_dir(&real_bin).with_context(|| format!("read {}", real_bin.display()))? {
        let entry = entry?;
        let name = entry.file_name();
        let name_str = name.to_string_lossy();
        let wrap_path = wrap_bin.join(&name);
        let real_path = entry.path();
        if is_ndk_clang_driver(&name_str) {
            write_clang_glue_wrapper(&wrap_path, &real_path, &patch_script)?;
        } else {
            symlink_path(&real_path, &wrap_path)?;
        }
    }

    crate::util::emit_stderr(format_args!(
        "xtask: NDK clang wrappers ready at {} (jni_glue callback helper inject)",
        wrap_root.display()
    ));
    Ok(wrap_root)
}

fn is_ndk_clang_driver(name: &str) -> bool {
    // Target drivers: aarch64-linux-android26-clang, *-clang++, and plain clang/clang++.
    // Skip clang-scan-deps and *.cfg sidecars.
    if Path::new(name)
        .extension()
        .is_some_and(|ext| ext.eq_ignore_ascii_case("cfg"))
        || name.contains("clang-scan")
        || name.contains("clangd")
    {
        return false;
    }
    name == "clang" || name == "clang++" || name.ends_with("-clang") || name.ends_with("-clang++")
}

const fn android_release_rustflags() -> &'static str {
    // panic=abort shipping still pulls std backtrace/gimli into the final .so.
    // immediate-abort + build-std (see android_release_cargo_z_args) drops that
    // weight. force-unwind-tables=no removes leftover DWARF unwind tables.
    // Pack-path only: host tooling keeps ordinary panic=abort.
    "-C force-unwind-tables=no -Zunstable-options -Cpanic=immediate-abort"
}

/// Extra Cargo `-Z` args required for the shipping Android size profile.
const fn android_release_build_std_args() -> &'static [&'static str] {
    // Requires `rust-src` on the pin (see rust-toolchain.toml) and RUSTC_BOOTSTRAP=1
    // so stable 1.96 accepts -Z. panic_abort is the std panic runtime companion for
    // immediate-abort; build-std rebuilds core/std without backtrace symbolization.
    &["-Z", "build-std=std,panic_abort"]
}

/// Target triples built by `boltffi pack android` for this repository.
const fn android_pack_target_triples() -> &'static [&'static str] {
    &[
        "aarch64-linux-android",
        "armv7-linux-androideabi",
        "i686-linux-android",
        "x86_64-linux-android",
    ]
}

fn apply_android_release_size_env(command: &mut Command) {
    // RUSTC_BOOTSTRAP unlocks -Z on the pin; do not set global RUSTFLAGS — that
    // would force immediate-abort onto host build-scripts/proc-macros and fail.
    command.env("RUSTC_BOOTSTRAP", "1");
    let rustflags = android_release_rustflags();
    for triple in android_pack_target_triples() {
        let key = format!(
            "CARGO_TARGET_{}_RUSTFLAGS",
            triple.replace('-', "_").to_ascii_uppercase()
        );
        command.env(key, rustflags);
    }
    for arg in android_release_build_std_args() {
        command.args(["--cargo-arg", arg]);
    }
}

fn write_clang_glue_wrapper(wrapper: &Path, real_clang: &Path, patch_script: &Path) -> Result<()> {
    let script = format!(
        r#"#!/usr/bin/env bash
set -euo pipefail
REAL_CLANG={real}
PATCH_SCRIPT={patch}
EXTRA=()
for arg in "$@"; do
  case "$arg" in
    *jni_glue.c)
      if [[ -f "$arg" ]]; then
        # Fail closed: a missing helper or failed inject must stop the link.
        python3 "$PATCH_SCRIPT" "$arg"
        # C glue never throws; drop EH/unwind tables that survive into the final .so.
        EXTRA+=(-fno-exceptions -fno-unwind-tables -fno-asynchronous-unwind-tables)
      fi
      ;;
  esac
done
exec "$REAL_CLANG" "$@" "${{EXTRA[@]}}"
"#,
        real = shell_quote(&real_clang.display().to_string()),
        patch = shell_quote(&patch_script.display().to_string()),
    );
    fs::write(wrapper, script).with_context(|| format!("write {}", wrapper.display()))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut permissions = fs::metadata(wrapper)?.permissions();
        permissions.set_mode(0o755);
        fs::set_permissions(wrapper, permissions)
            .with_context(|| format!("chmod {}", wrapper.display()))?;
    }
    Ok(())
}

/// Upstream gap (historically pre-#696): class-method foreign listeners call
/// `boltffi_jni_callback_parameter` from generated `jni_glue.c`, but older pins omitted the helper.
/// #696 is in formal `v0.28.0`; inject is fail-closed and no-ops when the helper is already present.
/// Re-audit each pin upgrade; delete this inject once a full regenerate proves glue always emits it.
/// Layout assumptions for the temporary inject are encoded in [`JNI_GLUE_CALLBACK_HELPER`].
const JNI_GLUE_CALLBACK_HELPER: &str = r"
typedef struct {
    void (*free)(uint64_t handle);
    uint64_t (*clone)(uint64_t handle);
} BoltFFICallbackVTablePrefix;

typedef BoltFFICallbackHandle (*BoltFFICallbackCreate)(uint64_t handle);

static BoltFFICallbackHandle *boltffi_jni_callback_handle_ref(jlong handle) {
    return handle == 0 ? NULL : (BoltFFICallbackHandle *)(uintptr_t)handle;
}

static const BoltFFICallbackVTablePrefix *boltffi_jni_callback_vtable_prefix(const BoltFFICallbackHandle *callback) {
    return callback == NULL ? NULL : (const BoltFFICallbackVTablePrefix *)callback->vtable;
}

static BoltFFICallbackHandle boltffi_jni_callback_parameter(uint64_t handle, BoltFFICallbackCreate create) {
    if ((handle & 1u) != 0u) {
        return create(handle);
    }
    const BoltFFICallbackHandle *stored_callback = boltffi_jni_callback_handle_ref((jlong)handle);
    const BoltFFICallbackVTablePrefix *vtable = boltffi_jni_callback_vtable_prefix(stored_callback);
    if (stored_callback == NULL || stored_callback->handle == 0 || vtable == NULL || vtable->clone == NULL) {
        return (BoltFFICallbackHandle){0};
    }
    return (BoltFFICallbackHandle){
        .handle = vtable->clone(stored_callback->handle),
        .vtable = stored_callback->vtable,
    };
}

";

const JNI_ONLOAD_MARKER: &str = "JNIEXPORT jint JNICALL JNI_OnLoad";

/// Pure inject for missing `boltffi_jni_callback_parameter` (fail-closed when referenced but marker absent).
///
/// Returns `Ok(None)` when no change is needed, `Ok(Some(patched))` when inject applies,
/// `Err` when the glue references the helper but cannot be patched safely.
fn patch_jni_glue_source(text: &str) -> Result<Option<String>> {
    if text.contains("static BoltFFICallbackHandle boltffi_jni_callback_parameter") {
        return Ok(None);
    }
    if !text.contains("boltffi_jni_callback_parameter(") {
        return Ok(None);
    }
    if !text.contains(JNI_ONLOAD_MARKER) {
        bail!(
            "jni_glue references boltffi_jni_callback_parameter but is missing {JNI_ONLOAD_MARKER}"
        );
    }
    Ok(Some(text.replacen(
        JNI_ONLOAD_MARKER,
        &format!("{JNI_GLUE_CALLBACK_HELPER}{JNI_ONLOAD_MARKER}"),
        1,
    )))
}

fn write_jni_glue_patch_script(path: &Path) -> Result<()> {
    // Self-check: pure inject must still apply to the current reference shape before we ship the
    // pack-time Python mirror used by the NDK clang wrapper.
    let sample = format!("boltffi_jni_callback_parameter(0, 0);\n{JNI_ONLOAD_MARKER}\n");
    let patched = patch_jni_glue_source(&sample)?
        .context("jni_glue inject self-check expected a patch for the reference sample")?;
    if !patched.contains("static BoltFFICallbackHandle boltffi_jni_callback_parameter") {
        bail!("jni_glue inject self-check lost helper body");
    }

    // Fail-closed Python driver used by the NDK clang wrapper; logic mirrors [`patch_jni_glue_source`].
    let script = format!(
        r#"#!/usr/bin/env python3
import pathlib
import sys

HELPER = r'''{JNI_GLUE_CALLBACK_HELPER}'''

MARKER = "{JNI_ONLOAD_MARKER}"

def main() -> int:
    if len(sys.argv) != 2:
        return 2
    path = pathlib.Path(sys.argv[1])
    text = path.read_text(encoding="utf-8")
    if "static BoltFFICallbackHandle boltffi_jni_callback_parameter" in text:
        return 0
    if "boltffi_jni_callback_parameter(" not in text:
        return 0
    if MARKER not in text:
        print(f"xtask: jni_glue patch failed; missing {{MARKER}} in {{path}}", file=sys.stderr)
        return 1
    path.write_text(text.replace(MARKER, HELPER + MARKER, 1), encoding="utf-8")
    print(f"xtask: patched boltffi_jni_callback_parameter into {{path}}", file=sys.stderr)
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
"#
    );
    fs::write(path, script).with_context(|| format!("write {}", path.display()))
}

fn shell_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', r"'\''"))
}

fn symlink_path(original: &Path, link: &Path) -> Result<()> {
    if let Some(parent) = link.parent() {
        fs::create_dir_all(parent).with_context(|| format!("create {}", parent.display()))?;
    }
    if link.exists() || link.symlink_metadata().is_ok() {
        remove_if_exists(link)?;
    }
    std::os::unix::fs::symlink(original, link)
        .with_context(|| format!("symlink {} -> {}", link.display(), original.display()))
}

fn locate_generated_kotlin(root: &Path) -> Result<PathBuf> {
    let preferred = root.join(GENERATED_PACKAGE_DIR).join(GENERATED_OWNER);
    if preferred.is_file() {
        return Ok(preferred);
    }
    // BoltFFI may emit multiple files; accept any single owner under the package path.
    let package_dir = root.join(GENERATED_PACKAGE_DIR);
    if package_dir.is_dir() {
        let mut kotlin_files = Vec::new();
        collect_kotlin_files(&package_dir, &mut kotlin_files)?;
        if kotlin_files.len() == 1 {
            return Ok(kotlin_files.remove(0));
        }
        if let Some(owner) = kotlin_files
            .iter()
            .find(|path| path.file_name().is_some_and(|name| name == GENERATED_OWNER))
        {
            return Ok(owner.clone());
        }
        // Multi-file package: concatenate is not allowed. Prefer the largest file as owner probe.
        if let Some(path) = kotlin_files
            .into_iter()
            .max_by_key(|path| fs::metadata(path).map_or(0, |meta| meta.len()))
        {
            return Ok(path);
        }
    }
    // Recursive search as last resort for layout drift (still fails if package identity wrong later).
    let mut found = Vec::new();
    collect_kotlin_files(root, &mut found)?;
    found
        .into_iter()
        .find(|path| {
            path.components()
                .any(|component| component.as_os_str() == "nativebridge")
        })
        .with_context(|| {
            format!(
                "BoltFFI Kotlin output missing under {} (expected package com.lomo.nativebridge)",
                root.display()
            )
        })
}

fn collect_kotlin_files(root: &Path, out: &mut Vec<PathBuf>) -> Result<()> {
    if !root.exists() {
        return Ok(());
    }
    for entry in fs::read_dir(root).with_context(|| format!("read {}", root.display()))? {
        let entry = entry?;
        let path = entry.path();
        if path.is_dir() {
            collect_kotlin_files(&path, out)?;
        } else if path.extension().is_some_and(|ext| ext == "kt") {
            out.push(path);
        }
    }
    Ok(())
}

fn locate_jni_libs(pack_root: &Path) -> Result<PathBuf> {
    let direct = pack_root.join("jniLibs");
    if direct.is_dir() {
        return Ok(direct);
    }
    // Some BoltFFI versions nest under dist/android/jniLibs even with overlay.
    let nested = pack_root.join("android/jniLibs");
    if nested.is_dir() {
        return Ok(nested);
    }
    bail!(
        "BoltFFI pack did not produce jniLibs under {}",
        pack_root.display()
    )
}

fn publish_selected_abis(source_root: &Path, destination_root: &Path, abis: &[Abi]) -> Result<()> {
    fs::create_dir_all(destination_root)?;
    for &abi in abis {
        let source_dir = source_root.join(abi.android_name());
        let source_lib = find_native_library(&source_dir)?;
        let destination_dir = destination_root.join(abi.android_name());
        remove_if_exists(&destination_dir)?;
        fs::create_dir_all(&destination_dir)?;
        let destination_lib = destination_dir.join(NATIVE_LIBRARY);
        fs::copy(&source_lib, &destination_lib).with_context(|| {
            format!(
                "failed to publish {} -> {}",
                source_lib.display(),
                destination_lib.display()
            )
        })?;
        // Reject any residual dispatcher libraries if BoltFFI or older trees left them behind.
        for forbidden in ["libjnidispatch.so", "liblomo_native.so"] {
            let leftover = destination_dir.join(forbidden);
            if leftover.exists() {
                bail!(
                    "forbidden native library present after BoltFFI publish: {}",
                    leftover.display()
                );
            }
        }
    }
    Ok(())
}

fn find_native_library(abi_dir: &Path) -> Result<PathBuf> {
    let preferred = abi_dir.join(NATIVE_LIBRARY);
    if preferred.is_file() {
        return Ok(preferred);
    }
    // Accept BoltFFI default name and rename on publish.
    if !abi_dir.is_dir() {
        bail!("missing ABI pack directory {}", abi_dir.display());
    }
    let mut candidates = Vec::new();
    for entry in fs::read_dir(abi_dir).with_context(|| format!("read {}", abi_dir.display()))? {
        let path = entry?.path();
        if path.extension().is_some_and(|ext| ext == "so") {
            candidates.push(path);
        }
    }
    match candidates.as_slice() {
        [only] => Ok(only.clone()),
        [] => bail!("no .so in {}", abi_dir.display()),
        _ => bail!(
            "ambiguous native libraries in {}: {:?}",
            abi_dir.display(),
            candidates
        ),
    }
}

fn verify_smoke_native_tree(workspace: &Workspace, abis: &[Abi]) -> Result<()> {
    let readelf = ndk_tool(workspace, "llvm-readelf")?;
    for &abi in abis {
        let path = workspace
            .root
            .join("native-smoke/jniLibs")
            .join(abi.android_name())
            .join(NATIVE_LIBRARY);
        verify_one_library(&readelf, abi, &path)?;
    }
    Ok(())
}

pub fn verify_native_tree(
    workspace: &Workspace,
    abis: &[Abi],
    profile: NativeProfile,
) -> Result<()> {
    let readelf = ndk_tool(workspace, "llvm-readelf")?;
    let mut total_bytes = 0u64;
    for &abi in abis {
        let path = workspace
            .jni_libs()
            .join(abi.android_name())
            .join(NATIVE_LIBRARY);
        verify_one_library(&readelf, abi, &path)?;
        // Production tree must not contain UniFFI/JNA leftovers.
        let dir = workspace.jni_libs().join(abi.android_name());
        for forbidden in ["libjnidispatch.so", "liblomo_native.so"] {
            let leftover = dir.join(forbidden);
            if leftover.exists() {
                bail!(
                    "forbidden legacy native library remains: {}",
                    leftover.display()
                );
            }
        }
        let bytes = fs::metadata(&path)
            .with_context(|| format!("stat {}", path.display()))?
            .len();
        total_bytes = total_bytes.saturating_add(bytes);
        crate::util::emit_stderr(format_args!(
            "xtask: verify {} {} bytes profile={profile:?} ({})",
            abi.android_name(),
            bytes,
            path.display()
        ));
    }
    // Shipping honesty only for release-class packs. Dev packs intentionally leave unstripped
    // libraries for faster host iteration and must not be cited as shipping GREEN.
    let shipping = matches!(profile, NativeProfile::Release | NativeProfile::ReleaseCi);
    if shipping && abis.len() == Abi::ALL.len() {
        // Stage-3 `lomo-store` + stage-4 `lomo-media`/archive path-only FFI expand the shipping surface
        // beyond the stage-2 workspace owner total (~3.28 MiB / 3.6 MiB ceiling). Observed stripped
        // `release-android` four-ABI total after media cutover is ~9.2 MiB; keep modest headroom for
        // non-semantic native churn without absorbing Dev packs.
        const MAX_FOUR_ABI_BYTES: u64 = 10_500_000;
        if total_bytes > MAX_FOUR_ABI_BYTES {
            bail!(
                "shipping four-ABI native total {total_bytes} exceeds stage-4 media shipping ceiling {MAX_FOUR_ABI_BYTES};                  app/jniLibs may contain unstripped Dev artifacts — repack with `just native` (release-android + strip)"
            );
        }
        crate::util::emit_stderr(format_args!(
            "xtask: four-ABI shipping size gate GREEN ({total_bytes} <= {MAX_FOUR_ABI_BYTES})"
        ));
    } else if !shipping {
        crate::util::emit_stderr(format_args!(
            "xtask: skipping four-ABI shipping size gate for Dev pack (total={total_bytes});              not shipping evidence"
        ));
    }
    Ok(())
}

fn verify_one_library(readelf: &Path, abi: Abi, path: &Path) -> Result<()> {
    if !path.is_file() {
        bail!("native library is missing: {}", path.display());
    }
    let mut header = Command::new(readelf);
    header.args(["--file-header", path.to_string_lossy().as_ref()]);
    let header = text_output(&mut header)?;
    if !header.contains(abi.machine()) {
        bail!(
            "{} has wrong ELF architecture for {}",
            path.display(),
            abi.android_name()
        );
    }

    let mut dynamic = Command::new(readelf);
    dynamic.args(["--dynamic", path.to_string_lossy().as_ref()]);
    let dynamic = text_output(&mut dynamic)?;
    if dynamic.contains("libstdc++") || dynamic.contains("lomo_sync_ffi") {
        bail!("{} has a forbidden native dependency", path.display());
    }

    let mut exports = Command::new(readelf);
    exports.args(["--dyn-syms", path.to_string_lossy().as_ref()]);
    let exports = text_output(&mut exports)?;
    if !exports.contains("JNI_OnLoad") {
        bail!("{} is missing JNI_OnLoad", path.display());
    }
    for forbidden in ["uniffi_", "libjnidispatch", "Java_com_sun_jna"] {
        if exports.contains(forbidden) {
            bail!(
                "{} still exports forbidden legacy symbol substring {forbidden}",
                path.display()
            );
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
    command.args([
        "ls-files",
        "--",
        "native-bindings/src",
        "rust-bindings/src",
        "app/jniLibs",
    ]);
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

/// Normalize generated Kotlin: drop suppressions, reject unchecked helper leakage, enforce package.
pub fn canonicalize_binding(text: &str) -> Result<String> {
    let package = text
        .lines()
        .position(|line| line.starts_with("package "))
        .context("generated binding has no package declaration")?;
    let package_line = text
        .lines()
        .nth(package)
        .context("generated binding package line disappeared")?;
    if package_line.trim() != "package com.lomo.nativebridge" {
        bail!("generated package must be com.lomo.nativebridge, found {package_line}");
    }

    let mut canonical = String::from(
        "// Generated from rust/native by lomo-xtask + BoltFFI.\n// Do not edit manually.\n\n",
    );
    // Keep required file-level opt-ins that appear before the package declaration.
    for line in text.lines().take(package) {
        let trimmed = line.trim_start();
        if trimmed.starts_with("@file:OptIn") {
            canonical.push_str(line.trim_start());
            canonical.push('\n');
        }
    }
    if canonical.contains("@file:OptIn") {
        canonical.push('\n');
    }
    for line in text.lines().skip(package) {
        let trimmed = line.trim_start();
        if trimmed.starts_with("@Suppress")
            || trimmed.starts_with("@file:Suppress")
            || trimmed.starts_with("@SuppressLint")
            || trimmed.starts_with("@SuppressWarnings")
        {
            continue;
        }
        canonical.push_str(line);
        canonical.push('\n');
    }
    // BoltFFI emits redundant `.toInt()` on parenthesized already-Int wire-size expressions under
    // Kotlin 2.x (nested records and UTF-8 string sequences both exercise this generator shape).
    canonical = canonical.replace(")).toInt()", "))");
    canonical = repair_native_loader_block(&canonical)?;

    if canonical.contains("@Suppress")
        || canonical.contains("@file:Suppress")
        || canonical.contains("@SuppressLint")
        || canonical.contains("@SuppressWarnings")
    {
        bail!("canonical BoltFFI Kotlin still contains suppression annotations");
    }

    // Drop unused unchecked cast helper when it is never referenced beyond its declaration.
    if let Some(helper_name) = find_unsafe_cast_helper(&canonical) {
        let references = canonical.matches(&helper_name).count();
        if references == 1 {
            canonical = strip_helper_function(&canonical, &helper_name);
        } else if references > 1 {
            bail!(
                "generated Kotlin still references unchecked cast helper {helper_name}; refusing publish"
            );
        }
    }

    if canonical.contains("as ")
        && canonical.contains("Unchecked")
        && canonical.to_lowercase().contains("unsafe")
    {
        // Keep this soft: only the named helper is a hard fail above.
    }

    Ok(canonical)
}

/// `BoltFFI` `desktop_loader = "none"` currently emits a truncated `Native` init (missing `}` for
/// the Android-only load branch / init / object). Repair that known shape before publish.
fn repair_native_loader_block(text: &str) -> Result<String> {
    let broken = "if (isAndroidRuntime) {\n            System.loadLibrary(androidLibrary)\n    }\n    @JvmStatic external fun";
    if !text.contains(broken) {
        // Already balanced or different generator shape.
        if text.contains("private object Native") {
            let opens = text.matches('{').count();
            let closes = text.matches('}').count();
            if opens != closes {
                bail!(
                    "generated Kotlin has unbalanced braces ({opens} open / {closes} close) and no known Native-loader repair pattern"
                );
            }
        }
        return Ok(text.to_owned());
    }
    let fixed = "if (isAndroidRuntime) {\n            System.loadLibrary(androidLibrary)\n        }\n    }\n\n    @JvmStatic external fun";
    Ok(text.replacen(broken, fixed, 1))
}

fn find_unsafe_cast_helper(text: &str) -> Option<String> {
    for name in ["boltffiUnsafeCast", "unsafeCast", "uncheckedCast"] {
        if text.contains(name) {
            return Some(name.to_owned());
        }
    }
    None
}

fn strip_helper_function(text: &str, helper_name: &str) -> String {
    let marker = format!("fun {helper_name}");
    let Some(start) = text.find(&marker) else {
        return text.to_owned();
    };
    // Walk backwards to include any KDoc/annotations immediately above the helper.
    let prefix = text.get(..start).unwrap_or("");
    let line_start = prefix.rfind('\n').map_or(0, |index| index + 1);
    let rest = text.get(start..).unwrap_or("");
    // Nursery `option_if_let_else` would force a less readable map_or_else over brace matching.
    #[expect(
        clippy::option_if_let_else,
        reason = "brace-depth scan is clearer than map_or_else nesting"
    )]
    let end = if let Some(brace) = rest.find('{') {
        // Block body: match braces.
        let mut depth = 0i32;
        let mut cursor = start + brace;
        let bytes = text.as_bytes();
        while cursor < bytes.len() {
            match bytes.get(cursor).copied() {
                Some(b'{') => depth += 1,
                Some(b'}') => {
                    depth -= 1;
                    if depth == 0 {
                        cursor += 1;
                        if bytes.get(cursor) == Some(&b'\n') {
                            cursor += 1;
                        }
                        break;
                    }
                }
                _ => {}
            }
            cursor += 1;
        }
        cursor
    } else if let Some(newline) = rest.find('\n') {
        // Expression body on one line: `fun name(...) = expr`.
        start + newline + 1
    } else {
        text.len()
    };
    let mut out = String::new();
    out.push_str(text.get(..line_start).unwrap_or(""));
    out.push_str(text.get(end..).unwrap_or(""));
    out
}

fn ndk_host_tag() -> Result<&'static str> {
    match (std::env::consts::OS, std::env::consts::ARCH) {
        ("linux", "x86_64") => Ok("linux-x86_64"),
        ("macos", "x86_64" | "aarch64") => Ok("darwin-x86_64"),
        (os, architecture) => bail!("unsupported NDK host: {os}-{architecture}"),
    }
}

#[cfg(test)]
mod tests {
    use std::fmt::Display;

    use super::{
        JNI_GLUE_CALLBACK_HELPER, JNI_ONLOAD_MARKER, canonicalize_binding, patch_jni_glue_source,
    };

    trait ResultTestExt<T> {
        fn test_ok(self, context: &str) -> T;
        fn test_error(self, context: &str) -> String;
    }

    impl<T, E: Display> ResultTestExt<T> for Result<T, E> {
        fn test_ok(self, context: &str) -> T {
            self.unwrap_or_else(|error| panic!("{context}: {error}"))
        }

        fn test_error(self, context: &str) -> String {
            match self {
                Ok(_value) => panic!("{context}: expected an error"),
                Err(error) => error.to_string(),
            }
        }
    }

    trait OptionTestExt<T> {
        fn test_some(self, context: &str) -> T;
    }

    impl<T> OptionTestExt<T> for Option<T> {
        fn test_some(self, context: &str) -> T {
            self.unwrap_or_else(|| panic!("{context}: expected Some"))
        }
    }

    #[test]
    fn jni_glue_patch_is_noop_when_helper_already_present() {
        let text = format!(
            "static BoltFFICallbackHandle boltffi_jni_callback_parameter(uint64_t h, void *c);\n{JNI_ONLOAD_MARKER}(JavaVM *vm, void *r) {{ return 0; }}\n"
        );
        assert!(
            patch_jni_glue_source(&text)
                .test_ok("patch existing helper")
                .is_none()
        );
    }

    #[test]
    fn jni_glue_patch_is_noop_when_helper_unreferenced() {
        let text = format!("{JNI_ONLOAD_MARKER}(JavaVM *vm, void *r) {{ return 0; }}\n");
        assert!(
            patch_jni_glue_source(&text)
                .test_ok("patch unreferenced helper")
                .is_none()
        );
    }

    #[test]
    fn jni_glue_patch_injects_helper_before_jni_onload_when_referenced() {
        let text = format!(
            "void use(void) {{ boltffi_jni_callback_parameter(1, 0); }}\n{JNI_ONLOAD_MARKER}(JavaVM *vm, void *r) {{ return 0; }}\n"
        );
        let patched = patch_jni_glue_source(&text)
            .test_ok("patch referenced helper")
            .test_some("injected helper");
        assert!(patched.contains("static BoltFFICallbackHandle boltffi_jni_callback_parameter"));
        assert!(patched.contains(JNI_GLUE_CALLBACK_HELPER.trim_start()));
        let helper_at = patched
            .find("static BoltFFICallbackHandle boltffi_jni_callback_parameter")
            .test_some("helper position");
        let onload_at = patched.find(JNI_ONLOAD_MARKER).test_some("onload position");
        assert!(helper_at < onload_at, "helper must precede JNI_OnLoad");
    }

    #[test]
    fn jni_glue_patch_fails_closed_when_referenced_without_onload_marker() {
        let text = "void use(void) { boltffi_jni_callback_parameter(1, 0); }\n";
        let error = patch_jni_glue_source(text).test_error("missing onload must fail closed");
        assert!(error.contains("JNI_OnLoad"));
    }

    #[test]
    fn canonicalize_rejects_wrong_package() {
        let error = canonicalize_binding("package com.lomo.rust\nclass X\n")
            .test_error("wrong package must fail");
        assert!(error.contains("com.lomo.nativebridge"));
    }

    #[test]
    fn canonicalize_strips_suppression_and_unused_helper() {
        let input = r#"
package com.lomo.nativebridge

@file:Suppress("UNCHECKED_CAST")
@Suppress("UNUSED")
class Demo

@Suppress("UNCHECKED_CAST")
internal fun boltffiUnsafeCast(value: Any?): Any? = value as Any?
"#;
        let out = canonicalize_binding(input).test_ok("canonical suppression removal");
        assert!(out.contains("package com.lomo.nativebridge"));
        assert!(out.contains("class Demo"));
        assert!(!out.contains("@Suppress"));
        assert!(!out.contains("boltffiUnsafeCast"));
    }

    #[test]
    fn canonicalize_removes_redundant_string_sequence_size_conversion() {
        let input = r"
package com.lomo.nativebridge

fun wireSize(values: List<String>): Int =
    values.sumOf { value -> (4 + Utf8Codec.maxBytes(value)).toInt() }
";

        let out = canonicalize_binding(input).test_ok("canonical wire size");

        assert!(out.contains("values.sumOf { value -> (4 + Utf8Codec.maxBytes(value)) }"));
        assert!(!out.contains("Utf8Codec.maxBytes(value)).toInt()"));
    }

    #[test]
    fn canonicalize_rejects_referenced_unsafe_cast_helper() {
        let input = r"
package com.lomo.nativebridge

fun use(): Any? = boltffiUnsafeCast(1)
internal fun boltffiUnsafeCast(value: Any?): Any? = value as Any?
";
        let error = canonicalize_binding(input).test_error("referenced helper must fail");
        assert!(error.contains("unchecked cast helper"));
    }
}
