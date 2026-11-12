use std::{
    collections::BTreeMap,
    fs,
    io::Write,
    path::{Path, PathBuf},
    process::Command,
    time::{Duration, Instant},
};

use anyhow::{Context, Result, bail, ensure};
use serde_json::Value;

use crate::{
    native::{self, Abi, NativeProfile},
    util::{find_files, kotlin, output, run, text_output},
    workspace::Workspace,
};

mod lan_uitest;
pub use lan_uitest::{
    lan_pairing_code, lan_validate_device_pair, lan_verify_discovery, lan_verify_pairing_codes,
    lan_verify_workspace,
};

/// Android compile API identity, including its independently versioned minor API surface.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct CompileSdk {
    api_level: u32,
    minor_api_level: u32,
}

impl CompileSdk {
    pub fn from_yaml(value: &yaml_rust2::Yaml) -> Result<Self> {
        let fields = value
            .as_hash()
            .context("compileSdk must declare apiLevel and minorApiLevel")?;
        ensure!(
            fields.len() == 2,
            "compileSdk requires exactly apiLevel and minorApiLevel"
        );
        let api_level = value["apiLevel"]
            .as_i64()
            .context("compileSdk.apiLevel must be an integer")?;
        let minor_api_level = value["minorApiLevel"]
            .as_i64()
            .context("compileSdk.minorApiLevel must be an integer")?;
        ensure!(api_level > 0, "compileSdk.apiLevel must be positive");
        Ok(Self {
            api_level: u32::try_from(api_level).context("compileSdk.apiLevel is out of range")?,
            minor_api_level: u32::try_from(minor_api_level)
                .context("compileSdk.minorApiLevel must be nonnegative and in range")?,
        })
    }
}

impl std::fmt::Display for CompileSdk {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        if self.minor_api_level == 0 {
            write!(formatter, "{}", self.api_level)
        } else {
            write!(formatter, "{}.{}", self.api_level, self.minor_api_level)
        }
    }
}

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
    if variant == AndroidVariant::Release {
        validate_baseline_sources(workspace)?;
        validate_signing_config(workspace)?;
    }
    crate::cache::prune(workspace)?;
    let profile = match variant {
        AndroidVariant::Debug => NativeProfile::Dev,
        AndroidVariant::Release => NativeProfile::Release,
    };
    native::ensure_android_libraries(workspace, profile, abis)?;

    let abi_tag = abi_tag_name(abis);
    let build_dir = workspace.kotlin_build.clone();

    let _stash_guard = native::AbiStashGuard::stash_unselected(workspace, abis)?;

    let mut command = android_apk_command(workspace, variant)?;
    run(&mut command)?;
    let release = variant == AndroidVariant::Release;
    let apk = validate_built_apk(workspace, &build_dir, release, abis)?;
    if release {
        verify_signature(workspace, &apk)?;
    }
    publish_apk(workspace, &apk, variant.name(), &abi_tag)
}

/// Prepares the canonical Kotlin command for one Android APK variant.
///
/// # Errors
/// Returns errors invalidating resource merge state, preparing shared directories or locating the wrapper.
pub fn android_apk_command(workspace: &Workspace, variant: AndroidVariant) -> Result<Command> {
    invalidate_java_resource_merge_state(workspace, variant)?;
    let build_dir = &workspace.kotlin_build;
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
    Ok(command)
}

pub fn invalidate_java_resource_merge_state(
    workspace: &Workspace,
    variant: AndroidVariant,
) -> Result<()> {
    let build_dir = &workspace.kotlin_build;
    let suffix = match variant {
        AndroidVariant::Debug => "Debug",
        AndroidVariant::Release => "Release",
    };
    let intermediates = build_dir
        .join("tasks")
        .join(format!("{APP_APK_TASK_PREFIX}{suffix}"))
        .join("gradle-project/build/_app/intermediates");
    // Amper rewrites module archives in place. AGP's retained Java-resource zip index
    // can then refer to entries no longer present in those archives ("Unknown file").
    // Rebuild this opaque merger state and its derived archives before every consumer,
    // including same-ABI builds where native packaging invalidation never runs.
    for directory in [
        intermediates.join(format!("incremental/{}-mergeJavaRes", variant.name())),
        intermediates.join("merged_java_res").join(variant.name()),
    ] {
        match fs::remove_dir_all(&directory) {
            Ok(()) => crate::util::emit_stderr(format_args!(
                "xtask: invalidated Java resource merge state {}",
                directory.display()
            )),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => {
                return Err(error).with_context(|| {
                    format!(
                        "failed to invalidate Java resource merge state {}",
                        directory.display()
                    )
                });
            }
        }
    }
    Ok(())
}

/// Publishes a validated APK in the canonical artifact directory.
///
/// Universal releases use `lomo-v<versionName>-vc<versionCode>.apk`, as required by installed
/// updaters. Split/debug packages use `<app-name>-<versionName>-<abi>.apk`.
///
/// # Errors
///
/// Returns an error when app metadata cannot be read or the package cannot be copied.
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
    let name = if variant == "release" && abi_tag == "all" {
        format!("lomo-v{}-vc{}.apk", metadata.version, metadata.version_code)
    } else {
        format!("{}-{}-{abi_tag}.apk", metadata.name, metadata.version)
    };
    Ok(workspace.apk_output_dir(variant).join(name))
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
        let debuggable = text_output(
            Command::new(
                workspace
                    .android_sdk
                    .join("cmdline-tools/latest/bin/apkanalyzer"),
            )
            .args(["manifest", "debuggable"])
            .arg(&apk),
        )?;
        ensure!(
            debuggable.trim() == "false",
            "release APK is debuggable: {}",
            apk.display()
        );
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
    pub(crate) compile_sdk: CompileSdk,
}

impl AppMetadata {
    pub fn load(workspace: &Workspace) -> Result<Self> {
        Self::load_from_root(&workspace.root)
    }

    pub fn load_from_root(root: &Path) -> Result<Self> {
        let module = root.join("apps/android/app/module.yaml");
        let module_yaml = fs::read_to_string(&module)
            .with_context(|| format!("failed to read {}", module.display()))?;
        Ok(Self {
            name: read_app_name(root)?,
            version: read_version_name(&module_yaml)?,
            version_code: read_module_scalar(&module_yaml, "versionCode")?,
            min_sdk: read_module_scalar(&module_yaml, "minSdk")?,
            target_sdk: read_module_scalar(&module_yaml, "targetSdk")?,
            compile_sdk: CompileSdk::from_yaml(
                &crate::util::load_yaml_document(&module)?["settings"]["android"]["compileSdk"],
            )?,
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
            // With `android.signing` enabled the toolchain emits the signed
            // `*-release.apk`; a stale `-unsigned.apk` from a pre-signing build is
            // never a release artifact candidate.
            value.contains("release") && !value.contains("unsigned")
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
    crate::profiles::validate_measured_profiles(
        &workspace.root,
        &workspace
            .android_sdk
            .join("cmdline-tools/latest/bin/profgen"),
    )?;
    Ok(())
}

/// Release signing is owned by the toolchain: `settings.android.signing` in
/// `apps/android/app/module.yaml` makes the build emit an already-signed APK from
/// the module-local `keystore.properties`. This pre-build validation fails with a
/// readable error instead of the toolchain's generic "properties file not found".
fn validate_signing_config(workspace: &Workspace) -> Result<()> {
    let module_dir = workspace.root.join("apps/android/app");
    let properties = module_dir.join("keystore.properties");
    ensure!(
        properties.is_file(),
        "release signing requires {}; the toolchain `android.signing` contract reads \
         storeFile/storePassword/keyAlias/keyPassword from that file",
        properties.display()
    );
    let mut values = BTreeMap::new();
    for line in fs::read_to_string(&properties)?.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if let Some((key, value)) = line.split_once('=') {
            values.insert(key.trim().to_owned(), value.trim().to_owned());
        }
    }
    for key in ["storeFile", "storePassword", "keyAlias", "keyPassword"] {
        ensure!(
            values.get(key).is_some_and(|value| !value.is_empty()),
            "release signing requires a non-empty {key} in {}",
            properties.display()
        );
    }
    // Amper resolves a relative `storeFile` from the module directory.
    let store_file = values
        .get("storeFile")
        .map(|path| {
            let path = PathBuf::from(path);
            if path.is_absolute() {
                path
            } else {
                module_dir.join(path)
            }
        })
        .with_context(|| {
            format!(
                "release signing requires storeFile in {}",
                properties.display()
            )
        })?;
    ensure!(
        store_file.is_file(),
        "release keystore does not exist: {}",
        store_file.display()
    );
    Ok(())
}

/// Post-build edge: the toolchain-applied signature must verify. An unsigned or
/// badly-signed artifact fails here — before publish — rather than at upload.
fn verify_signature(workspace: &Workspace, apk: &Path) -> Result<()> {
    let apksigner = apksigner(workspace)?;
    let mut verify = Command::new(&apksigner);
    verify.args(["verify", "--verbose"]).arg(apk);
    run(&mut verify).with_context(|| {
        format!(
            "release APK {} failed `apksigner verify` — signing is owned by \
             settings.android.signing in apps/android/app/module.yaml; check \
             apps/android/app/keystore.properties",
            apk.display()
        )
    })
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

// ---- P-60 AndroidX Macrobenchmark harness ----

/// Instrumented benchmark scenarios; each maps to one `LomoMacrobenchmark` `JUnit` method in the
/// `apps/android/benchmark` harness module.
///
/// Every variant is one `public void <name>()` `@Test` method on `LomoMacrobenchmark`; the
/// `ALL` set stays the original seven so `just build android benchmark` keeps its sweep.
/// The `DEVICE_*` sets group the `just perf device` plane members — they are also real
/// instrumented methods and may be run individually through the scenario slot.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum BenchmarkScenario {
    Startup,
    Frame,
    Scroll,
    Create,
    Expand,
    Menu,
    Search,
    StartupWarm,
    StartupHot,
    StartupDirty,
    Drawer,
    FilterSort,
    ScrollTop,
    Tag,
    Trash,
    VersionHistory,
    Settings,
    SearchClear,
    MemoActions,
    // ---- §3-C device CUJ group — production anchors added alongside; media surfaces
    // require `--corpus device`; restricted members declare `limitations()`.
    EditorOpen,
    ReviewOpen,
    StatsOpen,
    GalleryScroll,
    ReelOpen,
    ImageViewerOpen,
    SyncCenterOpen,
    ShareCardRender,
    ConflictDialogShow,
    WidgetRefresh,
    LanShareOpen,
    /// A5 first-install onboarding: `pm clear` + `.lomo` wipe per iteration, then the
    /// production `DocumentsUI` grant → first reconcile → first card. Runner-appended to
    /// the startup plane under `--corpus device --backend saf`.
    StartupOnboarding,
    /// Five fresh SAF mounts, measuring grant confirmation through indexed first content.
    WorkspaceFirstIndex,
    /// Cold reopen through the first populated memo card, rather than the empty root shell.
    WorkspaceReopen,
    /// Measured baseline-profile collection journey (`BaselineProfileRule.collect`). Not a
    /// measurement scenario — it emits `*-baseline-prof.txt`/`-startup-prof.txt` artifacts
    /// instead of benchmark metrics and joins no sweep; `perf device profile` invokes it.
    ProfileGen,
}

impl BenchmarkScenario {
    pub const ALL: [Self; 7] = [
        Self::Startup,
        Self::Frame,
        Self::Scroll,
        Self::Create,
        Self::Expand,
        Self::Menu,
        Self::Search,
    ];

    /// Startup plane for `perf device`: clean cold + warm + hot. `startupDirty` is appended
    /// by the runner only when an injected corpus exists to dirty — a dirty-start measurement
    /// on an empty workspace would fabricate a B1 claim.
    pub const DEVICE_STARTUP: [Self; 3] = [Self::Startup, Self::StartupWarm, Self::StartupHot];

    /// CUJ plane for `perf device`: the original zero-anchor-cost set plus the §3-C
    /// group whose anchors ship with this extension. Restricted members stay in the
    /// sweep — they measure their reachable sub-path and annotate the report.
    pub const DEVICE_CUJ: [Self; 20] = [
        Self::Drawer,
        Self::FilterSort,
        Self::ScrollTop,
        Self::Tag,
        Self::Trash,
        Self::VersionHistory,
        Self::Settings,
        Self::SearchClear,
        Self::MemoActions,
        Self::EditorOpen,
        Self::ReviewOpen,
        Self::StatsOpen,
        Self::GalleryScroll,
        Self::ReelOpen,
        Self::ImageViewerOpen,
        Self::SyncCenterOpen,
        Self::ShareCardRender,
        Self::ConflictDialogShow,
        Self::WidgetRefresh,
        Self::LanShareOpen,
    ];

    /// Every scenario this enum can name — parse allowlist for the scenario slot.
    pub const NAMED: [Self; 34] = [
        Self::Startup,
        Self::Frame,
        Self::Scroll,
        Self::Create,
        Self::Expand,
        Self::Menu,
        Self::Search,
        Self::StartupWarm,
        Self::StartupHot,
        Self::StartupDirty,
        Self::StartupOnboarding,
        Self::WorkspaceFirstIndex,
        Self::WorkspaceReopen,
        Self::Drawer,
        Self::FilterSort,
        Self::ScrollTop,
        Self::Tag,
        Self::Trash,
        Self::VersionHistory,
        Self::Settings,
        Self::SearchClear,
        Self::MemoActions,
        Self::EditorOpen,
        Self::ReviewOpen,
        Self::StatsOpen,
        Self::GalleryScroll,
        Self::ReelOpen,
        Self::ImageViewerOpen,
        Self::SyncCenterOpen,
        Self::ShareCardRender,
        Self::ConflictDialogShow,
        Self::WidgetRefresh,
        Self::LanShareOpen,
        Self::ProfileGen,
    ];

    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            Self::Startup => "startup",
            Self::Frame => "frame",
            Self::Scroll => "scroll",
            Self::Create => "create",
            Self::Expand => "expand",
            Self::Menu => "menu",
            Self::Search => "search",
            Self::StartupWarm => "startupWarm",
            Self::StartupHot => "startupHot",
            Self::StartupDirty => "startupDirty",
            Self::Drawer => "drawer",
            Self::FilterSort => "filterSort",
            Self::ScrollTop => "scrollTop",
            Self::Tag => "tag",
            Self::Trash => "trash",
            Self::VersionHistory => "versionHistory",
            Self::Settings => "settings",
            Self::SearchClear => "searchClear",
            Self::MemoActions => "memoActions",
            Self::EditorOpen => "editorOpen",
            Self::ReviewOpen => "reviewOpen",
            Self::StatsOpen => "statsOpen",
            Self::GalleryScroll => "galleryScroll",
            Self::ReelOpen => "reelOpen",
            Self::ImageViewerOpen => "imageViewerOpen",
            Self::SyncCenterOpen => "syncCenterOpen",
            Self::ShareCardRender => "shareCardRender",
            Self::ConflictDialogShow => "conflictDialogShow",
            Self::WidgetRefresh => "widgetRefresh",
            Self::LanShareOpen => "lanShareOpen",
            Self::StartupOnboarding => "startupOnboarding",
            Self::WorkspaceFirstIndex => "workspaceFirstIndex",
            Self::WorkspaceReopen => "workspaceReopen",
            Self::ProfileGen => "profileGen",
        }
    }

    /// Evidence-scope caveats the device report must attach to a scenario's result block —
    /// never a success substitute: they keep emulator-visible restrictions on the measured
    /// numbers instead of silently scoping them.
    #[must_use]
    pub const fn limitations(self) -> &'static [&'static str] {
        match self {
            Self::LanShareOpen => &[
                "emulator_limited: the emulator virtual NIC isolates multicast/NSD, so peer \
                 discovery never resolves real devices; measured surface = memo-menu → \
                 LAN-share page-open frames only",
            ],
            Self::WidgetRefresh => &[
                "restricted: `cmd appwidget` is unimplemented on this emulator build, so no \
                 host binding exists, and APPWIDGET_UPDATE is a protected broadcast only the \
                 system may send; measured = an explicit-component broadcast into \
                 LomoWidgetReceiver (same AMS dispatch path) + the `com.lomo.app:widget` \
                 process spin-up — RemoteViews render/layout is not exercised",
            ],
            Self::ConflictDialogShow => &[
                "restricted: provider conflicts need a configured remote sync backend, which \
                 a SAF workspace cannot synthesize deterministically; measured = Sync Center \
                 conflicts-pane journey — benchmark_sync_conflict_root stays pinned for a \
                 provider-backed run",
            ],
            Self::ShareCardRender => &[
                "scope: the measured surface ends at the Sharesheet handoff — render, PNG \
                 persist and chooser dispatch run in-app, while the chooser window belongs \
                 to com.android.intentresolver and is not attributed to the target package",
            ],
            // Exhaustive on purpose: a new scenario must decide its evidence caveats
            // instead of silently inheriting "none".
            Self::Startup
            | Self::Frame
            | Self::Scroll
            | Self::Create
            | Self::Expand
            | Self::Menu
            | Self::Search
            | Self::StartupWarm
            | Self::StartupHot
            | Self::StartupDirty
            | Self::Drawer
            | Self::FilterSort
            | Self::ScrollTop
            | Self::Tag
            | Self::Trash
            | Self::VersionHistory
            | Self::Settings
            | Self::SearchClear
            | Self::MemoActions
            | Self::EditorOpen
            | Self::ReviewOpen
            | Self::StatsOpen
            | Self::GalleryScroll
            | Self::ReelOpen
            | Self::ImageViewerOpen
            | Self::SyncCenterOpen
            | Self::StartupOnboarding
            | Self::WorkspaceFirstIndex
            | Self::WorkspaceReopen
            | Self::ProfileGen => &[],
        }
    }

    /// Whether the scenario's evidence is emulator-limited by construction — surfaced as a
    /// boolean on the result block so tooling can filter without parsing prose.
    #[must_use]
    pub const fn emulator_limited(self) -> bool {
        matches!(self, Self::LanShareOpen)
    }

    /// Resolves a scenario name to its variant; unknown names return `None` so callers can
    /// keep their own usage/error wording.
    #[must_use]
    pub fn parse(name: &str) -> Option<Self> {
        Self::NAMED
            .iter()
            .copied()
            .find(|scenario| scenario.name() == name)
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
        Some(name) => match BenchmarkScenario::parse(name) {
            Some(scenario) => Ok(vec![scenario]),
            None if Abi::parse_selector(name).is_ok() => Ok(BenchmarkScenario::ALL.to_vec()),
            None => bail!("unknown android benchmark scenario: {name}"),
        },
    }
}

pub const BENCHMARK_MODULE: &str = "apps/android/benchmark";
pub const BENCHMARK_PACKAGE: &str = "com.lomo.benchmark";
pub const BENCHMARK_CLASS: &str = "com.lomo.benchmark.LomoMacrobenchmark";
pub const BENCHMARK_TARGET_PACKAGE: &str = "com.lomo.app";
pub const BENCHMARK_SUPPRESSED_ERRORS: [&str; 2] = ["EMULATOR", "DEBUGGABLE"];
/// AGP pinned by the standalone benchmark module, independent of the Kotlin bridge.
const BENCHMARK_AGP: &str = "9.5.1";

/// Redirected Gradle build directory of the benchmark module (`layout.buildDirectory` in its
/// `build.gradle.kts` resolves to `target/lomo/build/android-benchmark/gradle`).
pub fn benchmark_harness_build_dir(workspace: &Workspace) -> PathBuf {
    workspace
        .root
        .join(BENCHMARK_MODULE)
        .join("../../../target/lomo/build/android-benchmark/gradle")
}

/// Compiles the instrumented-test APK (`assembleDebugAndroidTest`) without a device — the
/// CI-verifiable half of the P-60 harness. Scenario execution still requires
/// `just build android benchmark` on an attached device; this only proves the harness itself
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
/// The flow is fail-closed: no attached device aborts before any metric exists unless
/// `boot` opts into provisioning the pinned redroid container; the debug APK
/// is rebuilt for the device's primary ABI so measured artifacts match current sources; the
/// Gradle harness runs each scenario as a separate instrumented test method; and the androidx
/// `*-benchmarkData.json` files are parsed into one `android-benchmark.v1.json` summary whose
/// `measurement` block permanently records the emulator/debuggable suppression.
///
/// # Errors
/// Missing adb/device, APK build/install failure, missing `gradle`, a failing instrumented
/// test, or absent benchmark output all propagate as errors — the command never reports a
/// scenario it did not execute.
pub fn benchmark(
    workspace: &Workspace,
    scenarios: &[BenchmarkScenario],
    boot: Option<DeviceBoot>,
) -> Result<Value> {
    ensure!(
        !scenarios.is_empty(),
        "android benchmark requires at least one scenario"
    );
    let adb = adb_program(workspace)?;
    let report_dir = workspace.reports_dir().join("android-benchmark");
    if report_dir.exists() {
        fs::remove_dir_all(&report_dir)
            .with_context(|| format!("failed to reset {}", report_dir.display()))?;
    }
    fs::create_dir_all(&report_dir)
        .with_context(|| format!("failed to create {}", report_dir.display()))?;

    let serial = resolve_serial(&adb, boot, "benchmark")?;
    let device = device_facts(&adb, &serial)?;
    crate::util::emit_stderr(format_args!(
        "xtask: android benchmark on {serial} ({} api={} abi={})",
        device.model,
        device.api_level,
        device.abi.android_name()
    ));
    let frame_timeline = frame_timeline_capable(&device.fingerprint, &device.hardware);
    // Measure the artifact built from current sources: rebuild the debug APK for the
    // device's primary ABI rather than trusting a previously published universal APK.
    let apk = build(workspace, AndroidVariant::Debug, &[device.abi])?;
    adb_install(&adb, &serial, &apk)?;

    let gradle = gradle_command(workspace)?;
    let module_root = workspace.root.join(BENCHMARK_MODULE);
    let harness_build = benchmark_harness_build_dir(workspace);

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
        command.arg(format!(
            "-Pandroid.testInstrumentationRunnerArguments.measurementChannel={}",
            benchmark_channel(frame_timeline)
        ));
        run(&mut command).with_context(|| {
            format!(
                "instrumented {} benchmark failed on {serial} (see {} for the Gradle log)",
                scenario.name(),
                harness_build.display()
            )
        })?;
        let raw = collect_benchmark_outputs(&harness_build, &report_dir, scenario.name(), true)?;
        let summary = summarize_benchmark_files(&raw, scenario.name())?;
        scenario_results.push(summary);
    }

    let report = serde_json::json!({
        "schema_version": 1,
        "kind": "androidx_macrobenchmark",
        "git_revision": git_revision(workspace)?,
        "measurement": {
            "target_package": BENCHMARK_TARGET_PACKAGE,
            "harness_package": BENCHMARK_PACKAGE,
            "harness_module": BENCHMARK_MODULE,
            "harness_build_dir": harness_build,
            "channel": benchmark_channel(frame_timeline),
            "agp": BENCHMARK_AGP,
            "suppressed_errors": BENCHMARK_SUPPRESSED_ERRORS,
            "accuracy_note": "Values come from an emulator plus a debuggable target; androidx.benchmark suppressedErrors are declared in every raw artifact. Emulator-grade evidence, not physical-device release truth.",
        },
        "device": {
            "serial": serial,
            "model": device.model,
            "api_level": device.api_level.parse::<u32>().unwrap_or(0),
            "abilist": device.abilist,
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

pub fn adb_program(workspace: &Workspace) -> Result<PathBuf> {
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

/// Every serial in `adb devices` state `device`. A serial is a *transport*, not a
/// device identity — redroid's adbd registers twice (emulator transport `emulator-<n>`
/// plus the published TCP `host:port`), so the list feeds [`device_identities`].
fn adb_device_serials(adb: &Path) -> Result<Vec<String>> {
    let mut command = Command::new(adb);
    command.arg("devices");
    let devices = text_output(&mut command).context("adb devices failed")?;
    Ok(devices
        .lines()
        .filter_map(|line| {
            let mut parts = line.split_whitespace();
            match (parts.next(), parts.next()) {
                (Some(serial), Some("device")) if !serial.is_empty() && serial != "List" => {
                    Some(serial.to_owned())
                }
                _ => None,
            }
        })
        .collect())
}

/// Kernel boot identity of a device: `/proc/sys/kernel/random/boot_id` changes on
/// every boot and differs across machines, so serials reporting the same value are
/// transports into one OS instance. A serial that cannot answer is its own identity —
/// a flapping device must surface in ambiguity errors, never vanish silently.
fn device_boot_id(adb: &Path, serial: &str) -> String {
    adb_shell_text(adb, serial, &["cat", "/proc/sys/kernel/random/boot_id"]).map_or_else(
        |_| format!("unreachable:{serial}"),
        |value| value.trim().to_owned(),
    )
}

/// Groups `(serial, boot_id)` pairs by machine identity and picks each group's
/// canonical serial: the `host:port` transport when present, else the first seen.
///
/// redroid's dual registration (`emulator-5554` + `127.0.0.1:5555`) collapses into a
/// single entry; the `host:port` form wins inside a group because it is the published,
/// stable transport the documentation and `wait_booted` polling standardize on.
#[must_use]
pub fn deduplicate_device_serials(
    serials: impl IntoIterator<Item = (String, String)>,
) -> Vec<String> {
    // (boot_id, fallback serial, preferred `host:port` serial)
    let mut by_boot: Vec<(String, String, Option<String>)> = Vec::new();
    for (serial, boot_id) in serials {
        if let Some((_, _, tcp)) = by_boot.iter_mut().find(|(id, _, _)| id == &boot_id) {
            if serial.contains(':') {
                *tcp = Some(serial);
            }
        } else {
            let tcp = serial.contains(':').then(|| serial.clone());
            by_boot.push((boot_id, serial, tcp));
        }
    }
    by_boot
        .into_iter()
        .map(|(_, fallback, tcp)| tcp.unwrap_or(fallback))
        .collect()
}

/// Attached devices deduplicated to one canonical serial per machine.
fn attached_device_serials(adb: &Path) -> Result<Vec<String>> {
    let pairs = adb_device_serials(adb)?
        .into_iter()
        .map(|serial| (serial.clone(), device_boot_id(adb, &serial)))
        .collect::<Vec<_>>();
    Ok(deduplicate_device_serials(pairs))
}

pub fn adb_getprop(adb: &Path, serial: &str, key: &str) -> Result<String> {
    let mut command = Command::new(adb);
    command.args(["-s", serial, "shell", "getprop", key]);
    text_output(&mut command)
        .map(|value| value.trim().to_owned())
        .with_context(|| format!("adb getprop {key} failed on {serial}"))
}

/// `adb -s <serial> shell <args>` returning stdout; callers compose their own shell string
/// when they need quoting (`sh -c '...'` counts as one argv element).
pub fn adb_shell_text(adb: &Path, serial: &str, args: &[&str]) -> Result<String> {
    let mut command = Command::new(adb);
    command.arg("-s").arg(serial).arg("shell").args(args);
    text_output(&mut command).with_context(|| format!("adb shell {args:?} failed on {serial}"))
}

/// Installs the application and verifies its user-0 storage was actually prepared.
///
/// # Errors
///
/// Rejects installation failure or unavailable CE/DE app data directories, including an
/// installer reporting success while its data-preparation service is frozen.
pub fn adb_install(adb: &Path, serial: &str, apk: &Path) -> Result<()> {
    let mut command = Command::new(adb);
    command
        .args(["-s", serial, "install", "-r"])
        .arg(apk.as_os_str());
    run(&mut command)
        .with_context(|| format!("failed to install {} on {serial}", apk.display()))?;
    for storage in ["user", "user_de"] {
        let directory = format!("/data/{storage}/0/{BENCHMARK_TARGET_PACKAGE}");
        adb_shell_text(adb, serial, &["test", "-d", &directory]).with_context(|| {
            format!("install reported success but app data directory is unavailable: {directory} on {serial}")
        })?;
    }
    Ok(())
}

pub fn gradle_command(workspace: &Workspace) -> Result<PathBuf> {
    let probe = Command::new("gradle").arg("--version").output();
    match probe {
        Ok(output) if output.status.success() => Ok(PathBuf::from("gradle")),
        _ => bail!(
            "android benchmark requires a `gradle` binary on PATH (the instrumented-test harness lives in {} and is not part of the Kotlin toolchain project)",
            workspace.root.join(BENCHMARK_MODULE).display()
        ),
    }
}

/// Moves androidx `*-benchmarkData.json` artifacts for `scenario` into the report dir.
///
/// The destination may be a per-mode subdirectory the caller has not created yet (the
/// release sweep archives under `release/<mode>`), so the helper owns making it
/// exist — `fs::copy` never creates missing parents.
///
/// # Errors
/// Propagates directory creation/read failures, non-JSON artifact bodies and copy errors —
/// an unreadable artifact is a broken run, not an empty collection.
pub fn collect_benchmark_outputs(
    harness_build: &Path,
    report_dir: &Path,
    scenario: &str,
    retain_traces: bool,
) -> Result<Vec<PathBuf>> {
    fs::create_dir_all(report_dir)
        .with_context(|| format!("failed to create {}", report_dir.display()))?;
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
                if retain_traces {
                    archive_profiler_traces(&document, &path, report_dir, scenario)?;
                }
                for kind in ["gfxinfo", "framestats", "meminfo"] {
                    let snapshot_name = format!("{scenario}-{kind}.txt");
                    let source = path
                        .parent()
                        .context("benchmark output has no parent")?
                        .join(&snapshot_name);
                    // These files exist only when the caller requested SystemSnapshotMetric.
                    if source.try_exists()? {
                        let directory = report_dir.join("system");
                        fs::create_dir_all(&directory)?;
                        fs::copy(&source, directory.join(snapshot_name))?;
                    }
                }
                collected.push(target);
            }
        }
    }
    collected.sort();
    Ok(collected)
}

/// `AndroidX` owns capture and lists each binary trace beside its benchmark JSON.
fn archive_profiler_traces(
    document: &Value,
    source: &Path,
    report: &Path,
    scenario: &str,
) -> Result<()> {
    let benchmarks = document
        .get("benchmarks")
        .and_then(Value::as_array)
        .context("benchmark artifact has no benchmarks array")?;
    for benchmark in benchmarks.iter().filter(|benchmark| {
        benchmark.get("name").and_then(Value::as_str) == Some(scenario)
            && benchmark.get("className").and_then(Value::as_str) == Some(BENCHMARK_CLASS)
    }) {
        let outputs = benchmark
            .get("profilerOutputs")
            .context("requested traces have no profilerOutputs")?;
        let mut trace_count = 0;
        for output in outputs
            .as_array()
            .context("profilerOutputs must be an array")?
        {
            if output.get("type").and_then(Value::as_str) != Some("PerfettoTrace") {
                continue;
            }
            trace_count += 1;
            let filename = output
                .get("filename")
                .and_then(Value::as_str)
                .context("PerfettoTrace has no filename")?;
            let relative = Path::new(filename);
            ensure!(
                !filename.is_empty()
                    && relative.components().count() == 1
                    && matches!(
                        relative.components().next(),
                        Some(std::path::Component::Normal(_))
                    ),
                "invalid profiler artifact filename {filename:?}"
            );
            let origin = source
                .parent()
                .context("benchmark artifact has no parent")?
                .join(relative);
            ensure!(
                fs::metadata(&origin)?.len() > 0,
                "empty trace {}",
                origin.display()
            );
            let directory = report.join("traces");
            fs::create_dir_all(&directory)?;
            fs::copy(&origin, directory.join(relative))
                .with_context(|| format!("failed to archive trace {}", origin.display()))?;
        }
        ensure!(
            trace_count > 0,
            "requested traces contain no Perfetto artifacts"
        );
    }
    Ok(())
}

/// Names a `BaselineProfileRule`-emitted artifact: aggregated `*-baseline-prof.txt` /
/// `*-startup-prof.txt`, their per-iteration `*-<label>.txt` variants and the profman dump
/// `*-primary.prof.txt`.
fn is_profile_artifact(path: &Path) -> bool {
    let is_txt = path
        .extension()
        .is_some_and(|extension| extension.eq_ignore_ascii_case("txt"));
    is_txt
        && path
            .file_name()
            .and_then(|name| name.to_str())
            .is_some_and(|name| {
                name.contains("-baseline-prof")
                    || name.contains("-startup-prof")
                    || name.contains("-primary.prof")
            })
}

/// One observed profile artifact; a same-name rewrite is distinct from untouched history.
#[derive(Debug, Eq, PartialEq)]
pub struct ProfileArtifactStamp {
    modified: std::time::SystemTime,
    digest: String,
}

/// Captures content and modification evidence for profile artifacts.
///
/// Unlike `*-benchmarkData.json`, `*-prof.txt` files carry no embedded scenario/class tag, so the
/// caller snapshots this set before a collection run and hands it back to
/// [`collect_profile_outputs`] — new or overwritten artifacts belong to that run.
///
/// # Errors
/// Propagates directory traversal failures.
pub fn profile_artifact_snapshot(
    harness_build: &Path,
) -> Result<BTreeMap<PathBuf, ProfileArtifactStamp>> {
    let output_root = harness_build.join("outputs/connected_android_test_additional_output");
    let mut paths = BTreeMap::new();
    if !output_root.is_dir() {
        return Ok(paths);
    }
    let mut pending = vec![output_root];
    while let Some(directory) = pending.pop() {
        for entry in fs::read_dir(&directory)
            .with_context(|| format!("failed to read {}", directory.display()))?
        {
            let path = entry?.path();
            if path.is_dir() {
                pending.push(path);
            } else if is_profile_artifact(&path) {
                use sha2::Digest as _;
                let stamp = ProfileArtifactStamp {
                    modified: fs::metadata(&path)?.modified()?,
                    digest: lomo_core::hex_encode(&sha2::Sha256::digest(fs::read(&path)?)),
                };
                paths.insert(path, stamp);
            }
        }
    }
    Ok(paths)
}

/// Copies new and overwritten profile artifacts from this collection into `report_dir`.
/// An unchanged artifact from the pre-run snapshot remains excluded.
///
/// # Errors
/// Propagates traversal/read/copy failures.
pub fn collect_profile_outputs(
    harness_build: &Path,
    report_dir: &Path,
    before: &BTreeMap<PathBuf, ProfileArtifactStamp>,
) -> Result<Vec<PathBuf>> {
    fs::create_dir_all(report_dir)
        .with_context(|| format!("failed to create {}", report_dir.display()))?;
    let mut collected = Vec::new();
    for (path, stamp) in profile_artifact_snapshot(harness_build)? {
        if before.get(&path) == Some(&stamp) {
            continue;
        }
        let file_name = path
            .file_name()
            .with_context(|| format!("{} has no file name", path.display()))?;
        let target = report_dir.join(file_name);
        fs::copy(&path, &target).with_context(|| {
            format!("failed to copy {} -> {}", path.display(), target.display())
        })?;
        collected.push(target);
    }
    collected.sort();
    Ok(collected)
}

/// Summarizes a scenario's complete single-value benchmark measurements.
///
/// Preserves each metric's median, minimum, maximum and actual sample count.
///
/// # Errors
///
/// Rejects unreadable/malformed artifacts, missing metrics, invalid iteration counts and
/// partial samples; incomplete evidence cannot masquerade as a completed suite.
pub fn summarize_benchmark_files(paths: &[PathBuf], scenario: &str) -> Result<Value> {
    let mut metrics = serde_json::Map::new();
    let mut files = Vec::with_capacity(paths.len());
    let mut iterations = None;
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
            let expected = benchmark
                .get("repeatIterations")
                .and_then(Value::as_u64)
                .filter(|count| *count > 0)
                .context("benchmark repeatIterations must be positive")?;
            if let Some(previous) = iterations {
                ensure!(
                    previous == expected,
                    "scenario {scenario} has inconsistent iteration counts"
                );
            } else {
                iterations = Some(expected);
            }
            if let Some(object) = benchmark.get("metrics").and_then(Value::as_object) {
                for (name, metric) in object {
                    let runs = metric
                        .get("runs")
                        .and_then(Value::as_array)
                        .with_context(|| {
                            format!("scenario {scenario} metric {name} has no samples")
                        })?;
                    ensure!(
                        u64::try_from(runs.len())? == expected,
                        "scenario {scenario} metric {name} has incomplete samples: {}/{expected}",
                        runs.len()
                    );
                    metrics.insert(
                        name.clone(),
                        serde_json::json!({
                            "median": metric.get("median"),
                            "minimum": metric.get("minimum"),
                            "maximum": metric.get("maximum"),
                            "runs": runs.len(),
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

pub fn git_revision(workspace: &Workspace) -> Result<String> {
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

// ---- Android UI interaction flows (Maestro) ----

/// Target application under test; identical to the benchmark target package.
const UITEST_PACKAGE: &str = "com.lomo.app";
/// Committed flow directory; only top-level `*.yaml` files run, in sorted order.
const UITEST_FLOW_DIR: &str = "apps/android/uitest/flows";
const UITEST_REPORT_DIR: &str = "android-uitest";
/// Pinned Maestro CLI distribution version.
///
/// Resolved from the GitHub `cli-<version>` release `maestro.zip` — an external driver
/// tool like `gradle` for the benchmark harness, not a Cargo/Amper manifest dependency,
/// so the capability catalogue stays untouched.
pub const MAESTRO_VERSION: &str = "2.12.1";
const MAESTRO_SHA256: &str = "54dbb0716425626604973cbb374c585ca30272375d9ce15177a1152765ddd9d7";
const DEVICE_BOOT_TIMEOUT: Duration = Duration::from_secs(180);

/// The pinned redroid image — a dated tag rather than `-latest`, so every provision
/// resolves the same AOSP build. Fully qualified: podman refuses unqualified short
/// names without a registries alias. Android 15 on purpose — the `16.0.0` image
/// crash-loops surfaceflinger under `SwiftShader` (redroid-doc#934) and its
/// `SystemServer` still hard-opens `/dev/ashmem`, absent on modern kernels.
const REDROID_IMAGE: &str = "docker.io/redroid/redroid:15.0.0_64only-250627";
/// The canonical container name; reused while running, recreated cold otherwise.
const REDROID_CONTAINER: &str = "lomo-redroid";
/// redroid publishes adbd over TCP; with `-p 5555:5555` its adb serial is this host:port.
/// Numeric IPv4 on purpose: `adb connect localhost` resolves `::1` first and fails
/// outright on IPv6-disabled hosts, and the serial adb records is the literal string.
const REDROID_SERIAL: &str = "127.0.0.1:5555";
/// Host property, not a code constant: `host` renders on the real GPU (faster,
/// verified on this setup); GPU-less hosts need `guest` (`SwiftShader`). The env
/// var carries the per-machine choice so the kernel arg never has to be edited.
const REDROID_GPU_MODE_ENV: &str = "LOMO_REDROID_GPU_MODE";

/// Device identity every report records identically across the device commands.
struct DeviceFacts {
    model: String,
    api_level: String,
    abilist: String,
    abi: Abi,
    /// `ro.build.fingerprint` — one half of the frame-timeline capability check.
    fingerprint: String,
    /// `ro.hardware` — `redroid` marks the container image.
    hardware: String,
}

fn device_facts(adb: &Path, serial: &str) -> Result<DeviceFacts> {
    let model = adb_getprop(adb, serial, "ro.product.model")?;
    let api_level = adb_getprop(adb, serial, "ro.build.version.sdk")?;
    let abilist = adb_getprop(adb, serial, "ro.product.cpu.abilist")?;
    let fingerprint = adb_getprop(adb, serial, "ro.build.fingerprint")?;
    let hardware = adb_getprop(adb, serial, "ro.hardware")?;
    let primary = abilist
        .split(',')
        .next()
        .filter(|value| !value.trim().is_empty())
        .with_context(|| format!("device {serial} reports empty ro.product.cpu.abilist"))?;
    let abi = Abi::parse(primary.trim())?;
    Ok(DeviceFacts {
        model,
        api_level,
        abilist,
        abi,
        fingerprint,
        hardware,
    })
}

/// Whether the image supplies the display frame timeline consumed by `AndroidX` frame metrics.
/// Redroid uses the same journeys with application trace metrics instead.
#[must_use]
pub fn frame_timeline_capable(fingerprint: &str, hardware: &str) -> bool {
    !(fingerprint.starts_with("redroid/") || hardware.trim() == "redroid")
}

/// Names the metric channel recorded in reports and passed to the shared `AndroidX` harness.
#[must_use]
pub const fn benchmark_channel(frame_timeline: bool) -> &'static str {
    if frame_timeline {
        "display_frames"
    } else {
        "application_trace"
    }
}

/// Options for the Android uitest command; every flag is an explicit opt-in, never a default.
#[derive(Clone, Debug, Default)]
pub struct UitestOptions {
    /// Explicit second Android instance for the opt-in `lan_share` scenario.
    pub peer_serial: Option<String>,
    /// Device backend to provision when `adb devices` reports nothing (`--redroid`).
    pub boot: Option<DeviceBoot>,
    /// Reuse the published debug APK for the device ABI instead of rebuilding.
    pub skip_build: bool,
    /// Skip APK install entirely (whatever is already installed runs).
    pub no_install: bool,
    /// `pm clear` the app before running flows — exercises the fresh-install path.
    pub clear_data: bool,
    /// Flow selectors: existing paths, or names resolved under `apps/android/uitest/flows`.
    pub flows: Vec<String>,
}

/// Runs the committed Maestro UI flows against an attached device.
///
/// The flow is fail-closed and evidence-first: no attached device aborts before any state
/// changes; the debug APK is rebuilt for the device's primary ABI unless `--skip-build`;
/// every flow runs as its own `maestro test` invocation so one failing flow cannot hide the
/// evidence of the others; screenshots, view hierarchies, per-flow logs and one
/// `android-uitest.v1.json` summary land in `reports/android-uitest/`.
///
/// # Errors
/// Missing adb/maestro, a device that never finishes booting, absent flow files,
/// build/install failure, or any failed flow all propagate — a partially executed suite is
/// reported in the JSON evidence but never claimed as passed.
pub fn uitest(workspace: &Workspace, options: &UitestOptions) -> Result<Value> {
    if options.flows.iter().any(|flow| flow == "lan_share") {
        ensure!(
            options.flows == ["lan_share"],
            "lan_share must run by itself"
        );
        return lan_uitest::run(workspace, options);
    }
    ensure!(
        options.peer_serial.is_none(),
        "--peer is only valid with lan_share"
    );
    let flows = uitest_flow_files(&workspace.root, &options.flows)?;
    let adb = adb_program(workspace)?;
    let report_dir = workspace.reports_dir().join(UITEST_REPORT_DIR);
    if report_dir.exists() {
        fs::remove_dir_all(&report_dir)
            .with_context(|| format!("failed to reset {}", report_dir.display()))?;
    }
    fs::create_dir_all(&report_dir)
        .with_context(|| format!("failed to create {}", report_dir.display()))?;

    let serial = resolve_serial(&adb, options.boot, "uitest")?;
    let device = device_facts(&adb, &serial)?;
    crate::util::emit_stderr(format_args!(
        "xtask: android uitest on {serial} ({} api={} abi={})",
        device.model,
        device.api_level,
        device.abi.android_name()
    ));
    prepare_device(&adb, &serial)?;

    let apk = if options.no_install {
        None
    } else {
        let apk = if options.skip_build {
            published_debug_apk(workspace, device.abi)?
        } else {
            build(workspace, AndroidVariant::Debug, &[device.abi])?
        };
        adb_install(&adb, &serial, &apk)?;
        Some(apk)
    };
    if options.clear_data {
        let mut clear = Command::new(&adb);
        clear.args(["-s", &serial, "shell", "pm", "clear", UITEST_PACKAGE]);
        run(&mut clear).context("pm clear failed")?;
    }

    let maestro = maestro_program(workspace)?;
    let maestro_version = maestro_version(&maestro);
    if maestro_version != MAESTRO_VERSION {
        crate::util::emit_stderr(format_args!(
            "xtask: maestro {maestro_version} differs from pinned {MAESTRO_VERSION}; the report records the binary actually run"
        ));
    }
    let mut results = Vec::with_capacity(flows.len());
    let mut failed = Vec::new();
    for flow in &flows {
        let outcome = run_maestro_flow(&maestro, &serial, flow, &report_dir, &[])?;
        if outcome.get("status").and_then(Value::as_str) != Some("passed")
            && let Some(name) = outcome.get("name").and_then(Value::as_str)
        {
            failed.push(name.to_owned());
        }
        results.push(outcome);
    }

    let report = serde_json::json!({
        "schema_version": 1,
        "kind": "maestro_uitest",
        "git_revision": git_revision(workspace)?,
        "device": {
            "serial": serial,
            "model": device.model,
            "api_level": device.api_level.parse::<u32>().unwrap_or(0),
            "abilist": device.abilist,
        },
        "apk": apk,
        "maestro": {
            "version": maestro_version,
            "pinned": MAESTRO_VERSION,
            "program": maestro,
        },
        "options": {
            "device_boot": options.boot.as_ref().map(DeviceBoot::label),
            "skip_build": options.skip_build,
            "no_install": options.no_install,
            "clear_data": options.clear_data,
        },
        "flows": results,
    });
    let report_path = report_dir.join("android-uitest.v1.json");
    let pretty = serde_json::to_string_pretty(&report)
        .context("failed to serialize android uitest report")?;
    fs::write(&report_path, format!("{pretty}\n"))
        .with_context(|| format!("failed to write {}", report_path.display()))?;
    crate::util::emit_stderr(format_args!("xtask: wrote {}", report_path.display()));
    ensure!(
        failed.is_empty(),
        "android uitest failed flows: {} (see {})",
        failed.join(", "),
        report_dir.display()
    );
    Ok(report)
}

/// Resolves flow selectors to committed flow files.
///
/// With no selectors every top-level `*.yaml` under `apps/android/uitest/flows` runs in
/// sorted order; a selector is tried as an existing path (absolute or repository-relative),
/// then `flows/<name>` and `flows/<name>.yaml`. Missing directories, empty suites and
/// unknown names fail closed.
///
/// # Errors
/// Returns an error when the flow directory is absent/empty or a selector matches nothing.
pub fn uitest_flow_files(root: &Path, selectors: &[String]) -> Result<Vec<PathBuf>> {
    resolve_flow_files(root, UITEST_FLOW_DIR, selectors)
}

/// Shared selector resolution for committed flow suites (`apps/*/uitest/flows`):
/// no selectors → every top-level `*.yaml` sorted; a selector tries an existing path
/// (absolute or repository-relative), then `flows/<name>` and `flows/<name>.yaml`.
/// Missing directories, empty suites and unknown names fail closed.
pub fn resolve_flow_files(
    root: &Path,
    flow_dir_rel: &str,
    selectors: &[String],
) -> Result<Vec<PathBuf>> {
    let flow_dir = root.join(flow_dir_rel);
    if selectors.is_empty() {
        let mut flows = find_files(&flow_dir, "yaml")?;
        flows.retain(|path| path.parent() == Some(flow_dir.as_path()));
        flows.sort();
        ensure!(
            !flows.is_empty(),
            "no committed flows under {}; author a *.yaml scenario or pass flow paths",
            flow_dir.display()
        );
        return Ok(flows);
    }
    let mut resolved = Vec::with_capacity(selectors.len());
    for selector in selectors {
        let candidate = PathBuf::from(selector);
        let rooted = if candidate.is_absolute() {
            candidate.clone()
        } else {
            root.join(&candidate)
        };
        let candidates = [
            rooted.clone(),
            rooted.with_extension("yaml"),
            flow_dir.join(selector),
            flow_dir.join(format!("{selector}.yaml")),
        ];
        match candidates.iter().find(|path| path.is_file()) {
            Some(path) => resolved.push(path.clone()),
            None => bail!("unknown uitest flow: {selector}"),
        }
    }
    Ok(resolved)
}

/// Locates the published debug APK for `abi` under `target/lomo/artifacts/android/debug` using the
/// canonical `<app>-<version>-<abi>` naming contract — the `--skip-build` input.
fn published_debug_apk(workspace: &Workspace, abi: Abi) -> Result<PathBuf> {
    let metadata = AppMetadata::load(workspace)?;
    let apk = workspace.apk_output_dir("debug").join(format!(
        "{}-{}-{}.apk",
        metadata.name,
        metadata.version,
        abi.android_name()
    ));
    ensure!(
        apk.is_file(),
        "--skip-build requires an existing debug APK at {}; run without it to rebuild",
        apk.display()
    );
    Ok(apk)
}

/// Waits for `wait-for-device` then `sys.boot_completed=1` on a serial already visible to
/// adb, bounded by `deadline`.
fn wait_booted(adb: &Path, serial: &str, deadline: Instant) -> Result<String> {
    let mut wait = Command::new(adb);
    wait.args(["-s", serial, "wait-for-device"]);
    run(&mut wait).context("adb wait-for-device failed")?;
    while Instant::now() < deadline {
        if adb_getprop(adb, serial, "sys.boot_completed").is_ok_and(|value| value == "1") {
            return Ok(serial.to_owned());
        }
        std::thread::sleep(Duration::from_secs(3));
    }
    bail!("device {serial} did not finish booting within {DEVICE_BOOT_TIMEOUT:?}")
}

/// The device-provisioning selection for a run: reuse an attached device, or provision
/// the one supported backend when the caller opted in.
#[derive(Clone, Copy, Debug)]
pub enum DeviceBoot {
    /// Provision the pinned redroid container and attach its adb-over-TCP serial.
    Redroid,
}

impl DeviceBoot {
    /// Stable evidence label for report options (`redroid`).
    #[must_use]
    pub const fn label(&self) -> &'static str {
        match self {
            Self::Redroid => "redroid",
        }
    }
}

/// Resolves the single device this run drives.
///
/// Selection order: an explicit `ANDROID_SERIAL` (must name an attached device — a
/// typo'd serial fails rather than silently provisioning or hitting another device),
/// then the deduplicated attached set, then `--redroid` provisioning. More than one
/// unique attached device without `ANDROID_SERIAL` fails closed: picking by `adb
/// devices` row order would make the target a coin toss.
pub fn resolve_serial(adb: &Path, boot: Option<DeviceBoot>, what: &str) -> Result<String> {
    let serials = attached_device_serials(adb)?;
    if let Ok(selected) = std::env::var("ANDROID_SERIAL")
        && !selected.is_empty()
    {
        ensure!(
            adb_device_serials(adb)?.contains(&selected),
            "ANDROID_SERIAL={selected} is not an attached device (attached: {})",
            serials.join(", ")
        );
        return Ok(selected);
    }
    match serials.as_slice() {
        [serial] => return Ok(serial.clone()),
        [] => {}
        _ => bail!(
            "android {what} found multiple attached devices: {} — set ANDROID_SERIAL to \
             select one",
            serials.join(", ")
        ),
    }
    let Some(boot) = boot else {
        bail!(
            "android {what} requires an attached device (`adb devices` lists none); pass \
             --redroid to provision the pinned redroid container"
        );
    };
    match boot {
        DeviceBoot::Redroid => boot_redroid(adb),
    }
}

/// Provisions the pinned redroid container and waits for its adb serial. A running
/// container is reused as-is; a stopped one restarts cold (`--rm` + tmpfs `/data` keep
/// every start state-free, the container equivalent of `-no-snapshot-load`). Provisioning
/// needs a rootful engine — docker's daemon socket or rootful podman — so when the engine
/// accepts the run but init dies (rootless cannot run redroid), the error names the exact
/// sudo command instead of hanging on the boot wait.
fn boot_redroid(adb: &Path) -> Result<String> {
    let deadline = Instant::now() + DEVICE_BOOT_TIMEOUT;
    // A rootful container is invisible to a rootless engine's inspect but still answers
    // adb — attach before touching the engine so reuse never depends on privileges.
    Command::new(adb)
        .args(["connect", REDROID_SERIAL])
        .output()?;
    if let Some(serial) = adb_devices_matching(adb, REDROID_SERIAL)? {
        return wait_booted(adb, &serial, deadline);
    }
    let engine = redroid_engine()?;
    let gpu_mode = redroid_gpu_mode()?;
    let mut managed = redroid_running(engine)?;
    if !managed {
        managed = redroid_exists(engine)?;
        let mut provision = Command::new(engine);
        if managed {
            provision.args(["start", REDROID_CONTAINER]);
        } else {
            provision.args([
                "run",
                "-d",
                "--rm",
                "--privileged",
                "--name",
                REDROID_CONTAINER,
                "--tmpfs",
                "/data:rw,exec,size=2G",
                "-p",
                "5555:5555",
                REDROID_IMAGE,
                "androidboot.use_memfd=1",
            ]);
            // `host` renders on the real GPU (`--privileged` exposes /dev/dri);
            // GPU-less hosts set LOMO_REDROID_GPU_MODE=guest for SwiftShader.
            provision.arg(format!("androidboot.redroid_gpu_mode={gpu_mode}"));
            provision.args([
                "androidboot.redroid_width=1080",
                "androidboot.redroid_height=2400",
                "androidboot.redroid_dpi=420",
            ]);
        }
        run(&mut provision).with_context(|| {
            format!(
                "redroid provisioning via {engine} failed; {}",
                redroid_manual_command(engine, &gpu_mode)
            )
        })?;
        managed = true;
    }
    // `managed` = the engine can observe this container's lifecycle; a rootful container
    // predating this run is invisible to a rootless engine and was handled by the adb
    // attach above.
    crate::util::emit_stderr(format_args!(
        "xtask: waiting for {REDROID_CONTAINER} on {REDROID_SERIAL}"
    ));
    let serial = loop {
        // adbd inside the container opens 5555 once it is up; connect retries are cheap.
        // A refused port exits non-zero but is not a spawn failure — the serial poll
        // is the authority.
        Command::new(adb)
            .args(["connect", REDROID_SERIAL])
            .output()?;
        if let Some(serial) = adb_devices_matching(adb, REDROID_SERIAL)? {
            break serial;
        }
        // Only a container this engine itself launched can be observed dying through
        // its inspect — a rootful container predates the check above and never reaches
        // here.
        if managed {
            ensure!(
                redroid_running(engine)?,
                "redroid container exited during boot (see `{engine} logs {REDROID_CONTAINER}`); {}",
                redroid_manual_command(engine, &gpu_mode)
            );
        }
        ensure!(
            Instant::now() < deadline,
            "redroid {REDROID_SERIAL} never registered with adb within {DEVICE_BOOT_TIMEOUT:?}"
        );
        std::thread::sleep(Duration::from_secs(3));
    };
    wait_booted(adb, &serial, deadline)
}

/// Resolves the engine that provisions redroid: docker first (a running dockerd is rootful
/// and answers without sudo), then podman (rootless cannot run redroid — the boot wait
/// fails fast with the rootful command instead).
fn redroid_engine() -> Result<&'static str> {
    for engine in ["docker", "podman"] {
        if Command::new(engine)
            .arg("info")
            .output()
            .is_ok_and(|output| output.status.success())
        {
            return Ok(engine);
        }
    }
    bail!("no container engine on PATH for --redroid (install podman or docker)")
}

/// Whether `mode` is a well-formed `androidboot.redroid_gpu_mode` token.
///
/// The kernel cmdline takes a single lowercase word (`host`, `guest`, `angle`, …);
/// anything else would silently corrupt provisioning.
#[must_use]
pub fn valid_redroid_gpu_mode(mode: &str) -> bool {
    !mode.is_empty() && mode.chars().all(|c| c.is_ascii_lowercase() || c == '_')
}

/// The GPU rendering mode passed as `androidboot.redroid_gpu_mode`. `host` is the
/// default; `guest` is the documented fallback for GPU-less hosts. Free-form other
/// redroid modes (e.g. `angle`) stay reachable — only the token shape is gated.
fn redroid_gpu_mode() -> Result<String> {
    match std::env::var(REDROID_GPU_MODE_ENV) {
        Err(_) => Ok("host".to_owned()),
        Ok(mode) => {
            ensure!(
                valid_redroid_gpu_mode(&mode),
                "{REDROID_GPU_MODE_ENV}={mode} is not a valid redroid gpu mode \
                 (expected e.g. `host` or `guest`)"
            );
            Ok(mode)
        }
    }
}

/// The exact command that provisions `REDROID_CONTAINER` rootfully when the engine's
/// unprivileged path cannot.
fn redroid_manual_command(engine: &str, gpu_mode: &str) -> String {
    format!(
        "provision rootful once: sudo {engine} run -d --privileged --name {REDROID_CONTAINER} \
         --tmpfs /data:rw,exec,size=2G -p 5555:5555 {REDROID_IMAGE} \
         androidboot.use_memfd=1 androidboot.redroid_gpu_mode={gpu_mode}"
    )
}

fn redroid_exists(engine: &str) -> Result<bool> {
    Ok(Command::new(engine)
        .args(["inspect", REDROID_CONTAINER])
        .output()?
        .status
        .success())
}

fn redroid_running(engine: &str) -> Result<bool> {
    let output = Command::new(engine)
        .args(["inspect", "-f", "{{.State.Running}}", REDROID_CONTAINER])
        .output()?;
    Ok(output.status.success() && String::from_utf8_lossy(&output.stdout).trim() == "true")
}

/// Whether the pinned image is already pulled for `engine` (bootstrap prefetch state).
fn redroid_image_present(engine: &str) -> Result<bool> {
    Ok(Command::new(engine)
        .args(["image", "exists", REDROID_IMAGE])
        .output()?
        .status
        .success())
}

/// Best-effort prefetch of the pinned redroid image during `just bootstrap`, so the
/// first `--redroid` run does not stall inside `engine run` on a multi-GB pull.
///
/// Only engines that can actually boot redroid pull: docker's daemon is rootful, and
/// podman reports `Host.Security.Rootless` — a rootless podman pull would land in user
/// storage where redroid still cannot run (no binderfs), so it is skipped, not wasted.
/// A missing engine or failed pull warns and continues: `--redroid` is opt-in
/// diagnostic infrastructure, and a prefetch hiccup must not redact the pinned
/// toolchain bootstrap.
pub fn prefetch_redroid_image() {
    let Ok(engine) = redroid_engine() else {
        return;
    };
    if engine == "podman" {
        let rootless = Command::new(engine)
            .args(["info", "--format", "{{.Host.Security.Rootless}}"])
            .output()
            .is_ok_and(|output| String::from_utf8_lossy(&output.stdout).trim() == "true");
        if rootless {
            crate::util::emit_stderr(format_args!(
                "xtask: skipping redroid image prefetch — rootless podman cannot run \
                 {REDROID_CONTAINER} (provision rootful once: sudo podman pull {REDROID_IMAGE})"
            ));
            return;
        }
    }
    if matches!(redroid_image_present(engine), Ok(true)) {
        return;
    }
    crate::util::emit_stderr(format_args!("xtask: prefetching {REDROID_IMAGE}"));
    let status = Command::new(engine).args(["pull", REDROID_IMAGE]).status();
    match status {
        Ok(exit) if exit.success() => {
            crate::util::emit_stderr(format_args!("xtask: {REDROID_IMAGE} prefetched"));
        }
        _ => crate::util::emit_stderr(format_args!(
            "xtask: redroid image prefetch failed; the first `--redroid` run pulls on demand"
        )),
    }
}

/// `just redroid <up|down|status>` — explicit lifecycle for the pinned container
/// backend. `--redroid` on uitest/uishot/benchmark/perf provisions implicitly for one
/// run; this command owns the standing state: bring it up, read its evidence-level
/// status, or stop it again. `down`/`status` never provision.
pub fn redroid(workspace: &Workspace, arguments: &[String]) -> Result<Value> {
    let [action] = arguments else {
        bail!("usage: just redroid <up|down|status>");
    };
    let adb = adb_program(workspace)?;
    match action.as_str() {
        "up" => {
            // `up` owns the redroid backend specifically — unlike `--redroid` on the
            // run commands, an unrelated attached device is not a reason to skip
            // bringing the pinned container up.
            let serial = boot_redroid(&adb)?;
            let device = device_facts(&adb, &serial)?;
            Ok(serde_json::json!({
                "serial": serial,
                "model": device.model,
                "api_level": device.api_level,
                "abilist": device.abilist,
            }))
        }
        "status" => redroid_status(&adb),
        "down" => redroid_down(&adb),
        _ => bail!("unknown redroid action `{action}`; usage: just redroid <up|down|status>"),
    }
}

/// Readout of the redroid backend: adb-visible serials (grouped to unique devices),
/// the engine-visible container state, and whether the pinned image is pulled. All
/// fields are facts; nothing is provisioned.
fn redroid_status(adb: &Path) -> Result<Value> {
    let serials = adb_device_serials(adb)?;
    let devices = attached_device_serials(adb)?;
    let (engine, container_exists, container_running, image_present) = match redroid_engine() {
        Ok(engine) => (
            Some(engine),
            redroid_exists(engine)?,
            redroid_running(engine)?,
            redroid_image_present(engine)?,
        ),
        Err(_) => (None, false, false, false),
    };
    Ok(serde_json::json!({
        "adb_serials": serials,
        "devices": devices,
        "container": {
            "name": REDROID_CONTAINER,
            "exists": container_exists,
            "running": container_running,
            // A rootful container (e.g. the documented Quadlet) is invisible to a
            // rootless engine; when adb serials answer but inspect does not, that is
            // the state being reported.
            "engine": engine,
        },
        "image": {"name": REDROID_IMAGE, "pulled": image_present},
        "gpu_mode": redroid_gpu_mode()?,
    }))
}

/// Stops the pinned container. With `--rm` + tmpfs `/data` a stop is a full cold-state
/// reset — the next `--redroid` run boots fresh by construction.
fn redroid_down(adb: &Path) -> Result<Value> {
    let Ok(engine) = redroid_engine() else {
        bail!("no container engine on PATH (install podman or docker)");
    };
    if !redroid_exists(engine)? {
        if adb_devices_matching(adb, REDROID_SERIAL)?.is_none() {
            crate::util::emit_stderr(format_args!("xtask: {REDROID_CONTAINER} is not running"));
            return Ok(serde_json::json!({"stopped": false, "reason": "not_running"}));
        }
        bail!(
            "{REDROID_CONTAINER} is answering adb but not visible to `{engine}` — it is \
             running under a rootful context; stop it with: sudo {engine} stop {REDROID_CONTAINER}"
        );
    }
    if redroid_running(engine)? {
        let mut stop = Command::new(engine);
        stop.args(["stop", REDROID_CONTAINER]);
        run(&mut stop).with_context(|| format!("failed to stop {REDROID_CONTAINER}"))?;
    } else {
        // Stopped-but-present container: remove it so the next up is the same
        // `run` path rather than a stale `start`.
        let mut remove = Command::new(engine);
        remove.args(["rm", "-f", REDROID_CONTAINER]);
        run(&mut remove).with_context(|| format!("failed to remove {REDROID_CONTAINER}"))?;
    }
    // Disconnect the published TCP serial so `adb devices` does not keep a stale
    // transport; the `emulator-<n>` twin disappears with the container itself.
    drop(
        Command::new(adb)
            .args(["disconnect", REDROID_SERIAL])
            .output(),
    );
    crate::util::emit_stderr(format_args!("xtask: {REDROID_CONTAINER} stopped"));
    Ok(serde_json::json!({"stopped": true, "container": REDROID_CONTAINER}))
}

pub fn adb_devices_matching(adb: &Path, prefix: &str) -> Result<Option<String>> {
    let mut command = Command::new(adb);
    command.arg("devices");
    let devices = text_output(&mut command).context("adb devices failed")?;
    Ok(devices.lines().find_map(|line| {
        let mut parts = line.split_whitespace();
        match (parts.next(), parts.next()) {
            (Some(serial), Some("device")) if serial.starts_with(prefix) => Some(serial.to_owned()),
            _ => None,
        }
    }))
}

/// Deterministic input surface for scripted interaction: software keyboard stays off the
/// stylus-handwriting onboarding and animations no longer gate tap/assert timing.
fn prepare_device(adb: &Path, serial: &str) -> Result<()> {
    for (namespace, key, value) in [
        ("secure", "stylus_handwriting_enabled", "0"),
        ("global", "window_animation_scale", "0"),
        ("global", "transition_animation_scale", "0"),
        ("global", "animator_duration_scale", "0"),
    ] {
        let mut command = Command::new(adb);
        command.args([
            "-s", serial, "shell", "settings", "put", namespace, key, value,
        ]);
        run(&mut command)
            .with_context(|| format!("failed to set {namespace}.{key} on {serial}"))?;
    }
    Ok(())
}

/// Resolves the Maestro CLI: `MAESTRO` env, `MAESTRO_HOME/bin/maestro`, `maestro` on PATH
/// (a package-manager install wins over self-provisioned copies), then the XDG-cached
/// pinned distribution. When none resolves the pinned zip is downloaded
/// (sha256-verified) into `kotlin_cache/lomo/maestro/<version>` — outside the repository,
/// reused across worktrees.
fn maestro_program(workspace: &Workspace) -> Result<PathBuf> {
    if let Some(path) = std::env::var_os("MAESTRO").map(PathBuf::from)
        && path.is_file()
    {
        return Ok(path);
    }
    if let Some(home) = std::env::var_os("MAESTRO_HOME").map(PathBuf::from) {
        let candidate = home.join("bin/maestro");
        if candidate.is_file() {
            return Ok(candidate);
        }
    }
    let probe = Command::new("maestro").arg("--version").output();
    if let Ok(output) = probe
        && output.status.success()
    {
        return Ok(PathBuf::from("maestro"));
    }
    let cached = workspace.kotlin_cache.join(format!(
        "lomo/maestro/{MAESTRO_VERSION}/maestro/bin/maestro"
    ));
    if cached.is_file() {
        return Ok(cached);
    }
    provision_maestro(workspace)
}

/// Queries the resolved binary's `maestro --version`; an unqueryable binary reports
/// `unknown` — the report records the binary actually run, not the provisioning pin.
fn maestro_version(maestro: &Path) -> String {
    let version = match Command::new(maestro).arg("--version").output() {
        Ok(output) if output.status.success() => {
            String::from_utf8_lossy(&output.stdout).trim().to_owned()
        }
        _ => String::new(),
    };
    if version.is_empty() {
        "unknown".to_owned()
    } else {
        version
    }
}

/// Downloads and verifies the pinned `maestro.zip` release into the shared XDG cache.
/// A checksum mismatch or absent network fails before anything is executed.
fn provision_maestro(workspace: &Workspace) -> Result<PathBuf> {
    let root = workspace
        .kotlin_cache
        .join(format!("lomo/maestro/{MAESTRO_VERSION}"));
    let program = root.join("maestro/bin/maestro");
    if program.is_file() {
        return Ok(program);
    }
    let temporary = workspace.temp_dir("maestro")?;
    let zip = temporary.join("maestro.zip");
    crate::util::emit_stderr(format_args!(
        "xtask: provisioning Maestro {MAESTRO_VERSION} into {}",
        root.display()
    ));
    let mut curl = Command::new("curl");
    curl.args([
        "-fL",
        "--retry",
        "2",
        "-o",
        zip.to_string_lossy().as_ref(),
        &format!(
            "https://github.com/mobile-dev-inc/maestro/releases/download/cli-{MAESTRO_VERSION}/maestro.zip"
        ),
    ]);
    run(&mut curl).context("maestro.zip download failed")?;
    let mut digest = Command::new("sha256sum");
    digest.arg(zip.to_string_lossy().as_ref());
    let digest = text_output(&mut digest)?;
    let computed = digest
        .split_whitespace()
        .next()
        .context("sha256sum produced no digest")?;
    ensure!(
        computed == MAESTRO_SHA256,
        "maestro.zip sha256 {computed} does not match the pinned {MAESTRO_SHA256}"
    );
    fs::create_dir_all(&root).with_context(|| format!("failed to create {}", root.display()))?;
    let mut unzip = Command::new("unzip");
    unzip
        .args(["-q", "-o", zip.to_string_lossy().as_ref(), "-d"])
        .arg(root.to_string_lossy().as_ref());
    run(&mut unzip).context("maestro.zip extraction failed")?;
    ensure!(
        program.is_file(),
        "maestro distribution unpacked without {}",
        program.display()
    );
    Ok(program)
}

/// Runs one flow file as its own `maestro test` invocation; all artifacts land under
/// `<report_dir>/<stem>/` (screenshots, hierarchies) plus `<stem>.log` and `<stem>.junit.xml`.
/// A nonzero exit is recorded as `failed` — the suite keeps running so the report holds
/// evidence for every flow, not just the first failure.
fn run_maestro_flow(
    maestro: &Path,
    serial: &str,
    flow: &Path,
    report_dir: &Path,
    environment: &[(&str, &str)],
) -> Result<Value> {
    let stem = flow
        .file_stem()
        .and_then(|stem| stem.to_str())
        .with_context(|| format!("{} has no usable file name", flow.display()))?;
    let flow_dir = report_dir.join(stem);
    let junit = report_dir.join(format!("{stem}.junit.xml"));
    let mut command = Command::new(maestro);
    command
        .arg("test")
        .args(["--test-output-dir", flow_dir.to_string_lossy().as_ref()])
        .args([
            "--format",
            "junit",
            "--output",
            junit.to_string_lossy().as_ref(),
        ])
        .args(["--udid", serial])
        .arg(flow.as_os_str())
        .env("MAESTRO_CLI_NO_ANALYTICS", "1")
        .env("MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED", "true")
        .env_remove("CARGO");
    for (name, value) in environment {
        command.args(["-e", &format!("{name}={value}")]);
    }
    crate::util::emit_stderr(format_args!("xtask: maestro test {}", flow.display()));
    let output = command
        .output()
        .with_context(|| format!("failed to start maestro at {}", maestro.display()))?;
    let mut log = Vec::new();
    log.extend_from_slice(&output.stdout);
    if !output.stderr.is_empty() {
        log.extend_from_slice(&output.stderr);
    }
    let log_path = report_dir.join(format!("{stem}.log"));
    fs::File::create(&log_path)
        .and_then(|mut file| file.write_all(&log))
        .with_context(|| format!("failed to write {}", log_path.display()))?;
    let status = if output.status.success() {
        "passed"
    } else {
        "failed"
    };
    crate::util::emit_stderr(format_args!("xtask: flow {stem} -> {status}"));
    Ok(serde_json::json!({
        "name": stem,
        "file": flow,
        "status": status,
        "artifact_dir": flow_dir,
        "log": log_path,
        "junit": junit,
    }))
}

const UISHOT_REPORT_DIR: &str = "android-uisnap";
const UISHOT_PNG_MAGIC: &[u8; 8] = b"\x89PNG\r\n\x1a\n";
/// A rendered 1080x2400 frame compresses below this only when the captured surface is
/// still blank/black (mid-transition or pre-layout) — worth one settle retry.
const UISHOT_SUSPICIOUS_PNG_BYTES: usize = 32 * 1024;
const UISHOT_SETTLE_RETRY: Duration = Duration::from_millis(800);
/// Timestamped snapshots are unbounded by nature; every successful run keeps the
/// newest this many `YYYY-MM-DD_hhmmss` directories and removes older ones. Named
/// snapshots are never pruned — they are stable paths callers may reference.
const UISHOT_RETAINED_SNAPSHOTS: usize = 20;

/// Options for `just uishot`; every flag is an explicit opt-in, never a default.
#[derive(Clone, Debug, Default)]
pub struct UishotOptions {
    /// Device backend to provision when `adb devices` reports nothing (`--redroid`).
    pub boot: Option<DeviceBoot>,
    /// Output directory slug under `android-uisnap/`; defaults to a UTC timestamp.
    pub name: Option<String>,
}

/// Captures an atomic snapshot of whatever the attached device currently displays:
/// one `exec-out screencap` PNG (single adb call — no /sdcard intermediate file that can
/// go missing), the Maestro semantic hierarchy, a flattened `components.json` index, the
/// focused window, and a schema-versioned manifest. The manifest is also the command's
/// stdout JSON payload, so callers can read artifact paths directly.
///
/// Fail-closed: no adb/maestro/device, a non-PNG capture, or a non-JSON hierarchy all
/// abort rather than emit partial evidence claimed as complete.
///
/// # Errors
/// Missing adb/maestro, a device that never finishes booting, an unreadable
/// screenshot or hierarchy, or any artifact-write failure propagates.
pub fn uishot(workspace: &Workspace, options: &UishotOptions) -> Result<Value> {
    let slug = uishot_slug(options.name.as_deref())?;
    let adb = adb_program(workspace)?;
    let report_root = workspace.reports_dir().join(UISHOT_REPORT_DIR);
    let report_dir = report_root.join(&slug);
    if report_dir.exists() {
        fs::remove_dir_all(&report_dir)
            .with_context(|| format!("failed to reset {}", report_dir.display()))?;
    }
    fs::create_dir_all(&report_dir)
        .with_context(|| format!("failed to create {}", report_dir.display()))?;

    let serial = resolve_serial(&adb, options.boot, "uishot")?;
    let device = device_facts(&adb, &serial)?;
    crate::util::emit_stderr(format_args!(
        "xtask: android uishot on {serial} ({} api={}) -> {}",
        device.model,
        device.api_level,
        report_dir.display()
    ));
    prepare_device(&adb, &serial)?;

    let (png, retried) = uishot_capture_screenshot(&adb, &serial)?;
    ensure!(
        uishot_screenshot_is_png(&png),
        "screencap on {serial} did not return a PNG even after settle retry ({} bytes)",
        png.len()
    );
    let screenshot_path = report_dir.join("screenshot.png");
    fs::write(&screenshot_path, &png)
        .with_context(|| format!("failed to write {}", screenshot_path.display()))?;
    let screenshot_suspicious = uishot_screenshot_is_suspicious(&png);
    if screenshot_suspicious {
        crate::util::emit_stderr(format_args!(
            "xtask: screenshot is only {} bytes — surface may still be blank; see {}",
            png.len(),
            screenshot_path.display()
        ));
    }

    let semantic = uishot_semantic_artifacts(workspace, &adb, &serial, &report_dir)?;

    let pruned = uishot_prune_snapshots(&report_root, UISHOT_RETAINED_SNAPSHOTS)?;
    if pruned > 0 {
        crate::util::emit_stderr(format_args!(
            "xtask: pruned {pruned} timestamped snapshot(s), keeping newest {UISHOT_RETAINED_SNAPSHOTS}"
        ));
    }

    let report = serde_json::json!({
        "schema_version": 1,
        "kind": "android_uisnap",
        "git_revision": git_revision(workspace)?,
        "device": {
            "serial": serial,
            "model": device.model,
            "api_level": device.api_level.parse::<u32>().unwrap_or(0),
            "abilist": device.abilist,
        },
        "maestro": {
            "version": semantic.maestro_version,
            "pinned": MAESTRO_VERSION,
            "program": semantic.maestro,
        },
        "focus": semantic.focus,
        "snapshot_dir": report_dir,
        "artifacts": {
            "screenshot": screenshot_path,
            "hierarchy": semantic.hierarchy_path,
            "components": semantic.components_path,
            "window": semantic.window_path,
        },
        "screenshot": {
            "bytes": png.len(),
            "retried": retried,
            "suspiciously_small": screenshot_suspicious,
        },
        "retention": {
            "timestamped_keep": UISHOT_RETAINED_SNAPSHOTS,
            "pruned": pruned,
        },
        "component_count": semantic.component_count,
    });
    let report_path = report_dir.join("uisnap.v1.json");
    let pretty = serde_json::to_string_pretty(&report)
        .context("failed to serialize android uisnap report")?;
    fs::write(&report_path, format!("{pretty}\n"))
        .with_context(|| format!("failed to write {}", report_path.display()))?;
    crate::util::emit_stderr(format_args!("xtask: wrote {}", report_path.display()));
    Ok(report)
}

/// Semantic-capture outputs of one `uishot` — every field lands in the report verbatim.
struct UishotSemanticArtifacts {
    maestro: PathBuf,
    maestro_version: String,
    focus: String,
    hierarchy_path: PathBuf,
    components_path: PathBuf,
    window_path: PathBuf,
    component_count: usize,
}

/// Captures the semantic side of a snapshot: Maestro hierarchy → `hierarchy.json` →
/// flattened `components.json` → focused window `window.txt`.
///
/// A non-JSON hierarchy aborts the capture (the raw dump is kept for diagnosis) — partial
/// semantic evidence is never written up as a complete snapshot.
///
/// # Errors
/// Missing `maestro`, a failed `hierarchy` capture, a non-JSON/non-UTF-8 payload or any
/// artifact-write failure propagates.
fn uishot_semantic_artifacts(
    workspace: &Workspace,
    adb: &Path,
    serial: &str,
    report_dir: &Path,
) -> Result<UishotSemanticArtifacts> {
    let maestro = maestro_program(workspace)?;
    let maestro_version = maestro_version(&maestro);
    if maestro_version != MAESTRO_VERSION {
        crate::util::emit_stderr(format_args!(
            "xtask: maestro {maestro_version} differs from pinned {MAESTRO_VERSION}; the report records the binary actually run"
        ));
    }
    let hierarchy_raw = uishot_capture_hierarchy(&maestro, serial)?;
    let hierarchy: Value = match serde_json::from_str(hierarchy_raw.trim()) {
        Ok(value) => value,
        Err(error) => {
            let raw_path = report_dir.join("hierarchy.txt");
            fs::write(&raw_path, &hierarchy_raw)
                .with_context(|| format!("failed to write {}", raw_path.display()))?;
            bail!(
                "maestro hierarchy output is not JSON ({error}); raw dump kept at {}",
                raw_path.display()
            );
        }
    };
    let hierarchy_path = report_dir.join("hierarchy.json");
    fs::write(
        &hierarchy_path,
        format!("{}\n", serde_json::to_string_pretty(&hierarchy)?),
    )
    .with_context(|| format!("failed to write {}", hierarchy_path.display()))?;
    let components = uishot_flatten_hierarchy(&hierarchy);
    let components_path = report_dir.join("components.json");
    fs::write(
        &components_path,
        format!("{}\n", serde_json::to_string_pretty(&components)?),
    )
    .with_context(|| format!("failed to write {}", components_path.display()))?;

    let focus = adb_shell_text(
        adb,
        serial,
        &["dumpsys window | grep -E 'mCurrentFocus|mFocusedApp|mInputMethodTarget'"],
    )?;
    let focus = focus.trim().to_owned();
    let window_path = report_dir.join("window.txt");
    fs::write(&window_path, format!("{focus}\n"))
        .with_context(|| format!("failed to write {}", window_path.display()))?;

    Ok(UishotSemanticArtifacts {
        maestro,
        maestro_version,
        focus,
        hierarchy_path,
        components_path,
        window_path,
        component_count: components.len(),
    })
}

/// Directory slug for one snapshot: an explicit `--name` (limited to path-safe
/// characters) or a UTC timestamp so unnamed captures never overwrite each other.
///
/// # Errors
/// An invalid `--name` (empty, longer than 64 chars, or containing characters outside
/// `[A-Za-z0-9._-]`) is rejected — a path-unsafe slug can never become a report dir.
pub fn uishot_slug(name: Option<&str>) -> Result<String> {
    match name {
        Some(name) => {
            ensure!(
                !name.is_empty()
                    && name.len() <= 64
                    && name
                        .chars()
                        .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-')),
                "invalid --name `{name}`: use 1-64 characters from [A-Za-z0-9._-]"
            );
            Ok(name.to_owned())
        }
        None => Ok(uishot_utc_stamp(
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map_or(0, |stamp| stamp.as_secs()),
        )),
    }
}

/// `YYYY-MM-DD_hhmmss` in UTC from epoch seconds — civil-from-days arithmetic, no
/// calendar dependency in the toolchain crate.
fn uishot_utc_stamp(epoch_secs: u64) -> String {
    // u64 epoch days (≈2e14 max) always fit i64 — the cast is deliberate, never a wrap.
    let days = (epoch_secs / 86_400).cast_signed();
    let secs_of_day = epoch_secs % 86_400;
    let (hour, minute, second) = (secs_of_day / 3600, secs_of_day / 60 % 60, secs_of_day % 60);
    // Howard Hinnant's civil_from_days.
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let year = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let (day, month) = (
        doy - (153 * mp + 2) / 5 + 1,
        if mp < 10 { mp + 3 } else { mp - 9 },
    );
    let year = if month <= 2 { year + 1 } else { year };
    format!("{year:04}-{month:02}-{day:02}_{hour:02}{minute:02}{second:02}")
}

/// True when a directory name is exactly the auto-generated timestamp layout.
///
/// Timestamped names are the only ones retention is allowed to collect; a user-named
/// snapshot matching the layout is treated as timestamped by design.
#[must_use]
pub fn uishot_slug_is_timestamp(name: &str) -> bool {
    let bytes = name.as_bytes();
    bytes.len() == 17
        && matches!(
            (bytes.get(4), bytes.get(7), bytes.get(10)),
            (Some(&b'-'), Some(&b'-'), Some(&b'_'))
        )
        && bytes
            .iter()
            .enumerate()
            .all(|(index, byte)| matches!(index, 4 | 7 | 10) || byte.is_ascii_digit())
}

/// Removes all but the newest `keep` timestamped snapshot directories under `report_root`.
///
/// Lexicographic order equals chronological for the zero-padded layout. Returns how many
/// directories were removed; named snapshots and non-directory entries stay untouched.
///
/// # Errors
/// Listing `report_root` or removing a stale snapshot propagates the IO failure.
pub fn uishot_prune_snapshots(report_root: &Path, keep: usize) -> Result<usize> {
    let mut stamped = Vec::new();
    for entry in fs::read_dir(report_root)
        .with_context(|| format!("failed to list {}", report_root.display()))?
    {
        let entry = entry?;
        if entry.file_type()?.is_dir()
            && uishot_slug_is_timestamp(&entry.file_name().to_string_lossy())
        {
            stamped.push(entry.path());
        }
    }
    stamped.sort();
    let excess = stamped.len().saturating_sub(keep);
    for path in stamped.iter().take(excess) {
        fs::remove_dir_all(path).with_context(|| format!("failed to prune {}", path.display()))?;
    }
    Ok(excess)
}

/// `adb -s <serial> exec-out <args>` returning raw stdout bytes — required for binary
/// payloads (PNG) that `shell` would mangle.
fn adb_exec_out(adb: &Path, serial: &str, args: &[&str]) -> Result<Vec<u8>> {
    let mut command = Command::new(adb);
    command.arg("-s").arg(serial).arg("exec-out").args(args);
    let output = command
        .output()
        .with_context(|| format!("adb exec-out {args:?} failed to start on {serial}"))?;
    ensure!(
        output.status.success(),
        "adb exec-out {args:?} exited {} on {serial}: {}",
        output.status,
        String::from_utf8_lossy(&output.stderr)
    );
    Ok(output.stdout)
}

/// True when the payload has the PNG signature — the only hard acceptance check.
#[must_use]
pub fn uishot_screenshot_is_png(bytes: &[u8]) -> bool {
    bytes.starts_with(UISHOT_PNG_MAGIC)
}

/// Heuristic for a not-yet-rendered frame: a real 1080x2400 screenshot never compresses
/// under 32 KiB; a black mid-transition frame does.
#[must_use]
pub const fn uishot_screenshot_is_suspicious(bytes: &[u8]) -> bool {
    bytes.len() < UISHOT_SUSPICIOUS_PNG_BYTES
}

fn uishot_capture_screenshot(adb: &Path, serial: &str) -> Result<(Vec<u8>, bool)> {
    let first = adb_exec_out(adb, serial, &["screencap", "-p"])?;
    if uishot_screenshot_is_png(&first) && !uishot_screenshot_is_suspicious(&first) {
        return Ok((first, false));
    }
    std::thread::sleep(UISHOT_SETTLE_RETRY);
    let second = adb_exec_out(adb, serial, &["screencap", "-p"])?;
    let better = if second.len() >= first.len() {
        second
    } else {
        first
    };
    Ok((better, true))
}

/// `maestro hierarchy --udid <serial>` — the semantic view tree as JSON.
fn uishot_capture_hierarchy(maestro: &Path, serial: &str) -> Result<String> {
    let mut command = Command::new(maestro);
    command
        .args(["--udid", serial])
        .arg("hierarchy")
        .env("MAESTRO_CLI_NO_ANALYTICS", "1")
        .env("MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED", "true")
        .env_remove("CARGO");
    let output = command
        .output()
        .with_context(|| format!("failed to start maestro at {}", maestro.display()))?;
    ensure!(
        output.status.success(),
        "maestro hierarchy exited {}: {}",
        output.status,
        String::from_utf8_lossy(&output.stderr)
    );
    String::from_utf8(output.stdout).context("maestro hierarchy output is not UTF-8")
}

/// Flattens the Maestro `{attributes, children}` tree into a one-indexed component list.
///
/// Only nodes that identify themselves — an id, text, accessibility text, or an
/// interactive flag — are kept. `resource-id` entries expose every `benchmark_*` anchor,
/// so a component maps back to source via `rg benchmarkAnchor`.
#[must_use]
pub fn uishot_flatten_hierarchy(root: &Value) -> Vec<Value> {
    let mut components = Vec::new();
    uishot_flatten_node(root, 0, &mut components);
    components
}

fn uishot_flatten_node(node: &Value, depth: usize, components: &mut Vec<Value>) {
    if let Some(attributes) = node.get("attributes") {
        // A missing attribute is semantically the empty string — absent Maestro fields
        // mean "no id/text", never an error worth dropping the node over.
        let text_of = |key: &str| attributes.get(key).and_then(Value::as_str).unwrap_or("");
        let flag_of = |key: &str| text_of(key) == "true";
        let (id, text, accessibility_text, class) = (
            text_of("resource-id"),
            text_of("text"),
            text_of("accessibilityText"),
            text_of("class"),
        );
        let (clickable, scrollable, focused) = (
            flag_of("clickable"),
            flag_of("scrollable"),
            flag_of("focused"),
        );
        if !id.is_empty()
            || !text.is_empty()
            || !accessibility_text.is_empty()
            || clickable
            || scrollable
            || focused
        {
            components.push(serde_json::json!({
                "idx": components.len(),
                "depth": depth,
                "id": id,
                "text": text,
                "accessibility_text": accessibility_text,
                "class": class,
                "bounds": text_of("bounds"),
                "clickable": clickable,
                "scrollable": scrollable,
                "focused": focused,
            }));
        }
    }
    if let Some(children) = node.get("children").and_then(Value::as_array) {
        for child in children {
            uishot_flatten_node(child, depth + 1, components);
        }
    }
}
