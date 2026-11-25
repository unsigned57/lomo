use std::{
    env, fs,
    path::{Path, PathBuf},
};

use anyhow::{Context, Result, bail, ensure};

pub const ANDROID_API: u32 = 26;
pub const NDK_VERSION: &str = "30.0.16248370";

#[derive(Clone, Debug)]
pub struct Workspace {
    pub root: PathBuf,
    pub rust: PathBuf,
    pub cargo_home: PathBuf,
    pub rust_target: PathBuf,
    pub lomo_output: PathBuf,
    pub tool_root: PathBuf,
    pub android_sdk: PathBuf,
    pub kotlin_home: PathBuf,
    pub kotlin_cache: PathBuf,
    pub kotlin_data: PathBuf,
    pub kotlin_config: PathBuf,
    pub android_home: PathBuf,
    pub kotlin_cli_cache: PathBuf,
    pub gradle_home: PathBuf,
    pub kotlin_build: PathBuf,
    pub reports_dir: PathBuf,
}

impl Workspace {
    /// Discovers canonical workspace and standard toolchain paths.
    ///
    /// # Errors
    /// Returns invalid or unavailable workspace/environment paths.
    pub fn discover() -> Result<Self> {
        let root = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../..")
            .canonicalize()
            .context("repository root does not exist")?;
        let rust = root.clone();
        let kotlin_home = env_path("LOMO_KOTLIN_HOME")
            .or_else(|| env_path("HOME"))
            .context("HOME must be set to resolve reusable build caches")?;
        let kotlin_home = absolute_path(&root, kotlin_home);
        let kotlin_cache = absolute_path(
            &root,
            env_path("LOMO_KOTLIN_XDG_CACHE_HOME")
                .or_else(|| env_path("XDG_CACHE_HOME"))
                .unwrap_or_else(|| kotlin_home.join(".cache")),
        );
        let kotlin_data = absolute_path(
            &root,
            env_path("LOMO_KOTLIN_XDG_DATA_HOME")
                .or_else(|| env_path("XDG_DATA_HOME"))
                .unwrap_or_else(|| kotlin_home.join(".local/share")),
        );
        let kotlin_config = absolute_path(
            &root,
            env_path("LOMO_KOTLIN_XDG_CONFIG_HOME")
                .or_else(|| env_path("XDG_CONFIG_HOME"))
                .unwrap_or_else(|| kotlin_home.join(".config")),
        );
        let cargo_home = absolute_path(
            &root,
            env_path("CARGO_HOME").unwrap_or_else(|| kotlin_home.join(".cargo")),
        );
        let rust_target = absolute_path(
            &root,
            match env_path("CARGO_TARGET_DIR") {
                Some(path) => path,
                None => cargo_target_from_config(&root)?,
            },
        );
        let lomo_output = absolute_path(
            &root,
            env_path("LOMO_GENERATED_ROOT").unwrap_or_else(|| root.join("target/lomo")),
        );
        let tool_root = absolute_path(
            &root,
            env_path("LOMO_CARGO_TOOLS_DIR")
                .unwrap_or_else(|| kotlin_cache.join("lomo/cargo-tools")),
        );
        let android_sdk = env_path("LOMO_KOTLIN_ANDROID_SDK")
            .or_else(|| env_path("ANDROID_SDK_ROOT"))
            .or_else(|| env_path("ANDROID_HOME"))
            .unwrap_or_else(|| kotlin_home.join("Android/Sdk"));
        let reports_dir = absolute_path(
            &root,
            env_path("LOMO_REPORTS_DIR").unwrap_or_else(|| root.join("reports")),
        );

        Ok(Self {
            rust,
            cargo_home,
            rust_target,
            lomo_output: lomo_output.clone(),
            tool_root,
            android_sdk: absolute_path(&root, android_sdk),
            kotlin_home: kotlin_home.clone(),
            kotlin_cache: kotlin_cache.clone(),
            kotlin_data,
            kotlin_config,
            android_home: absolute_path(
                &root,
                env_path("LOMO_KOTLIN_ANDROID_HOME")
                    .or_else(|| env_path("ANDROID_USER_HOME"))
                    .unwrap_or_else(|| kotlin_home.join(".android")),
            ),
            kotlin_cli_cache: absolute_path(
                &root,
                env_path("LOMO_KOTLIN_CLI_CACHE")
                    .or_else(|| env_path("KOTLIN_CLI_BOOTSTRAP_CACHE_DIR"))
                    .unwrap_or_else(|| kotlin_cache.join("JetBrains/Kotlin")),
            ),
            gradle_home: absolute_path(
                &root,
                env_path("LOMO_KOTLIN_GRADLE_USER_HOME")
                    .or_else(|| env_path("GRADLE_USER_HOME"))
                    .unwrap_or_else(|| kotlin_home.join(".gradle")),
            ),
            kotlin_build: absolute_path(
                &root,
                env_path("LOMO_KOTLIN_BUILD_DIR")
                    .unwrap_or_else(|| lomo_output.join("build/kotlin")),
            ),
            reports_dir,
            root,
        })
    }

    /// Creates the configured shared toolchain directories.
    ///
    /// # Errors
    /// Returns directory creation errors.
    pub fn prepare_directories(&self) -> Result<()> {
        for directory in [
            &self.tool_root,
            &self.android_sdk,
            &self.kotlin_home,
            &self.kotlin_cache,
            &self.kotlin_data,
            &self.kotlin_config,
            &self.android_home,
            &self.kotlin_cli_cache,
            &self.gradle_home,
        ] {
            fs::create_dir_all(directory)
                .with_context(|| format!("failed to create {}", directory.display()))?;
        }
        Ok(())
    }

    /// Prepares the shared directories for a Kotlin invocation.
    ///
    /// # Errors
    /// Returns directory creation errors.
    pub fn prepare_kotlin_invocation(&self) -> Result<()> {
        self.prepare_directories()
    }

    #[must_use]
    pub fn tool_bin(&self) -> PathBuf {
        self.tool_root.join("bin")
    }

    /// Resolve the pinned Android NDK.
    ///
    /// The committed [`NDK_VERSION`] is the toolchain identity: an environment override only
    /// qualifies when it exists and matches that pin. Rolling distros routinely leave a stale
    /// `ANDROID_NDK_HOME`/`ANDROID_NDK_ROOT` pointing at a newer or removed NDK — an unusable
    /// override is skipped with a warning and the SDK-managed copy of the pin wins instead of
    /// tripping every build. When the pin rolls forward, either side may satisfy it first.
    ///
    /// # Errors
    /// Returns missing or mismatched pinned NDK errors.
    pub fn ndk_root(&self) -> Result<PathBuf> {
        self.find_ndk()?.with_context(|| {
            format!(
                "Android NDK {NDK_VERSION} not found: install `ndk;{NDK_VERSION}` into {} or point ANDROID_NDK_HOME at a matching NDK",
                self.android_sdk.join("ndk").display()
            )
        })
    }

    pub(crate) fn find_ndk(&self) -> Result<Option<PathBuf>> {
        if let Some(configured) =
            env_path("ANDROID_NDK_HOME").or_else(|| env_path("ANDROID_NDK_ROOT"))
        {
            let ndk = absolute_path(&self.root, configured.clone());
            match usable_ndk(&ndk) {
                Ok(()) => return Ok(Some(ndk)),
                Err(reason) => crate::util::emit_stderr(format_args!(
                    "xtask: ignoring ANDROID_NDK_HOME/ANDROID_NDK_ROOT={} ({reason:#})",
                    configured.display()
                )),
            }
        }
        let sdk_ndk = self.android_sdk.join("ndk").join(NDK_VERSION);
        match fs::metadata(&sdk_ndk) {
            Ok(metadata) => ensure!(
                metadata.is_dir(),
                "{} is not a directory",
                sdk_ndk.display()
            ),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(error).with_context(|| format!("stat {}", sdk_ndk.display())),
        }
        validate_ndk_version(&sdk_ndk)?;
        Ok(Some(sdk_ndk))
    }

    #[must_use]
    pub fn rust_target(&self) -> PathBuf {
        self.rust_target.clone()
    }

    #[must_use]
    pub fn generated_bindings(&self) -> PathBuf {
        self.root.join("apps/android/native-bindings/src")
    }

    #[must_use]
    pub fn jni_libs(&self) -> PathBuf {
        self.root.join("apps/android/app/jniLibs")
    }

    /// Lomo-owned artifacts, namespaced away from Cargo `debug`/`release` graphs.
    #[must_use]
    pub fn lomo_output_dir(&self) -> PathBuf {
        self.lomo_output.clone()
    }

    #[must_use]
    pub fn artifacts_dir(&self) -> PathBuf {
        self.lomo_output_dir().join("artifacts")
    }

    #[must_use]
    pub fn tui_dist_dir(&self) -> PathBuf {
        self.artifacts_dir().join("tui/dist")
    }

    #[must_use]
    pub fn tui_output_dir(&self, profile: &str) -> PathBuf {
        self.artifacts_dir().join("tui").join(profile)
    }

    #[must_use]
    pub fn build_state_dir(&self) -> PathBuf {
        self.lomo_output_dir().join("build")
    }

    #[must_use]
    pub fn reports_dir(&self) -> PathBuf {
        self.reports_dir.clone()
    }

    #[must_use]
    pub fn corpora_dir(&self) -> PathBuf {
        self.lomo_output_dir().join("corpora")
    }

    #[must_use]
    pub fn apk_output_dir(&self, variant: &str) -> PathBuf {
        self.artifacts_dir().join("android").join(variant)
    }

    /// Resets one named temporary output directory.
    ///
    /// # Errors
    /// Returns directory cleanup or creation errors.
    pub fn temp_dir(&self, name: &str) -> Result<PathBuf> {
        let directory = self.lomo_output_dir().join("tmp").join(name);
        if directory.exists() {
            fs::remove_dir_all(&directory)
                .with_context(|| format!("failed to reset {}", directory.display()))?;
        }
        fs::create_dir_all(&directory)
            .with_context(|| format!("failed to create {}", directory.display()))?;
        Ok(directory)
    }

    /// Fail closed when a derived artifact path would escape `target/lomo`.
    ///
    /// # Errors
    ///
    /// Returns an error when a generated path is not under the configured Lomo output
    /// namespace or would sit in a repository-root `build/` tree.
    pub fn check_generated_artifact_layout(&self) -> Result<()> {
        let lomo = self.lomo_output_dir();
        for generated in [
            self.apk_output_dir("debug"),
            self.apk_output_dir("release"),
            self.tui_dist_dir(),
            self.tui_output_dir("debug"),
            self.tui_output_dir("release"),
            self.build_state_dir(),
            self.corpora_dir(),
            self.lomo_output_dir().join("tmp").join("layout-probe"),
        ] {
            ensure!(
                generated.starts_with(&lomo),
                "{} must live under {}",
                generated.display(),
                lomo.display()
            );
            ensure!(
                !generated
                    .strip_prefix(&self.root)
                    .is_ok_and(|path| path.starts_with("build")),
                "{} must not use a second repository-root build/ tree",
                generated.display()
            );
        }
        Ok(())
    }
}

/// A configured NDK path is usable only when the directory exists and carries the pinned
/// revision; [`validate_ndk_version`] supplies the identity check.
fn usable_ndk(path: &Path) -> Result<()> {
    ensure!(
        path.is_dir(),
        "Android NDK path does not exist: {}",
        path.display()
    );
    validate_ndk_version(path)
}

fn env_path(name: &str) -> Option<PathBuf> {
    env::var_os(name)
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
}

fn absolute_path(root: &Path, path: PathBuf) -> PathBuf {
    if path.is_absolute() {
        path
    } else {
        root.join(path)
    }
}

fn cargo_target_from_config(root: &Path) -> Result<PathBuf> {
    let path = root.join(".cargo/config.toml");
    let source = fs::read_to_string(&path).with_context(|| format!("read {}", path.display()))?;
    let document = crate::util::load_toml_document(&source, ".cargo/config.toml")?;
    let target = document
        .get("build")
        .and_then(|build| build.get("target-dir"))
        .and_then(toml_edit::Item::as_str)
        .context(".cargo/config.toml must declare build.target-dir")?;
    ensure!(!target.is_empty(), "build.target-dir must not be empty");
    Ok(PathBuf::from(target))
}

pub fn validate_ndk_version(path: &Path) -> Result<()> {
    let props_path = path.join("source.properties");
    if !props_path.is_file() {
        bail!(
            "Android NDK directory {} is missing source.properties",
            path.display()
        );
    }
    let content = fs::read_to_string(&props_path)
        .with_context(|| format!("failed to read {}", props_path.display()))?;
    for line in content.lines() {
        let trimmed = line.trim();
        if let Some(version) = trimmed.strip_prefix("Pkg.Revision") {
            let version = version.trim_start_matches(['=', ' ']).trim();
            if version == NDK_VERSION || version.starts_with(&format!("{NDK_VERSION}.")) {
                return Ok(());
            }
            bail!(
                "Android NDK at {} has version {version}, expected {NDK_VERSION}",
                path.display()
            );
        }
    }
    bail!(
        "Android NDK at {} does not specify Pkg.Revision in source.properties",
        path.display()
    );
}
