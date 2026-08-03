use std::env;
use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result};

pub const ANDROID_API: u32 = 26;
pub const NDK_VERSION: &str = "29.0.14206865";

#[derive(Clone, Debug)]
pub struct Workspace {
    pub root: PathBuf,
    pub rust: PathBuf,
    pub cargo_home: PathBuf,
    pub rust_target: PathBuf,
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
}

impl Workspace {
    pub fn discover() -> Result<Self> {
        let root = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../..")
            .canonicalize()
            .context("repository root does not exist")?;
        let rust = root.join("rust");
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
            env_path("CARGO_TARGET_DIR").unwrap_or_else(|| rust.join("target")),
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

        Ok(Self {
            rust,
            cargo_home,
            rust_target,
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
                    .unwrap_or_else(|| root.join(".kotlin/toolchain-build/shared")),
            ),
            root,
        })
    }

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

    pub fn prepare_kotlin_invocation(&self) -> Result<()> {
        self.prepare_directories()?;
        let logs = self.kotlin_build.join("logs");
        if logs.exists() {
            fs::remove_dir_all(&logs)
                .with_context(|| format!("failed to reset {}", logs.display()))?;
        }
        Ok(())
    }

    pub fn tool_bin(&self) -> PathBuf {
        self.tool_root.join("bin")
    }

    pub fn ndk_root(&self) -> PathBuf {
        self.android_sdk.join("ndk").join(NDK_VERSION)
    }

    pub fn rust_target(&self) -> PathBuf {
        self.rust_target.clone()
    }

    pub fn generated_bindings(&self) -> PathBuf {
        self.root.join("native-bindings/src")
    }

    pub fn jni_libs(&self) -> PathBuf {
        self.root.join("app/jniLibs")
    }

    pub fn android_artifacts(&self) -> PathBuf {
        self.root.join(".kotlin/artifacts/android-release")
    }

    pub fn temp_dir(&self, name: &str) -> Result<PathBuf> {
        let directory = self.rust_target().join("xtask").join(name);
        if directory.exists() {
            fs::remove_dir_all(&directory)
                .with_context(|| format!("failed to reset {}", directory.display()))?;
        }
        fs::create_dir_all(&directory)
            .with_context(|| format!("failed to create {}", directory.display()))?;
        Ok(directory)
    }
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
