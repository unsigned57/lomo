use std::env;
use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result};

pub const ANDROID_API: u32 = 26;
pub const NDK_VERSION: &str = "29.0.14206865";
pub const JNA_VERSION: &str = "5.18.1";
pub const JNA_SHA256: &str = "7f053e3ec99e14dd71259c82c1c8a02738d64a13c31226b2acc170f3060951e0";

#[derive(Clone, Debug)]
pub struct Workspace {
    pub root: PathBuf,
    pub rust: PathBuf,
    pub cargo_home: PathBuf,
    pub tool_root: PathBuf,
    pub android_sdk: PathBuf,
    pub kotlin_home: PathBuf,
    pub kotlin_cache: PathBuf,
    pub kotlin_data: PathBuf,
    pub kotlin_config: PathBuf,
    pub android_home: PathBuf,
    pub kotlin_cli_cache: PathBuf,
    pub gradle_home: PathBuf,
}

impl Workspace {
    pub fn discover() -> Result<Self> {
        let root = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../..")
            .canonicalize()
            .context("repository root does not exist")?;
        let rust = root.join("rust");
        let cargo_home = root.join(".cache/cargo-home");
        let tool_root = root.join(".cache/cargo-tools");
        let android_sdk = env_path("LOMO_KOTLIN_ANDROID_SDK")
            .or_else(|| env_path("ANDROID_SDK_ROOT"))
            .or_else(|| env_path("ANDROID_HOME"))
            .unwrap_or_else(|| root.join(".android-sdk"));

        Ok(Self {
            rust,
            cargo_home,
            tool_root,
            android_sdk,
            kotlin_home: env_path("LOMO_KOTLIN_HOME").unwrap_or_else(|| root.join(".home")),
            kotlin_cache: env_path("LOMO_KOTLIN_XDG_CACHE_HOME")
                .unwrap_or_else(|| root.join(".cache")),
            kotlin_data: env_path("LOMO_KOTLIN_XDG_DATA_HOME")
                .unwrap_or_else(|| root.join(".local/share")),
            kotlin_config: env_path("LOMO_KOTLIN_XDG_CONFIG_HOME")
                .unwrap_or_else(|| root.join(".config")),
            android_home: env_path("LOMO_KOTLIN_ANDROID_HOME")
                .unwrap_or_else(|| root.join(".android")),
            kotlin_cli_cache: env_path("KOTLIN_CLI_BOOTSTRAP_CACHE_DIR")
                .unwrap_or_else(|| root.join(".kotlin-cli")),
            gradle_home: env_path("LOMO_KOTLIN_GRADLE_USER_HOME")
                .unwrap_or_else(|| root.join(".gradle/kotlin-toolchain")),
            root,
        })
    }

    pub fn prepare_directories(&self) -> Result<()> {
        for directory in [
            &self.cargo_home,
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

    pub fn tool_bin(&self) -> PathBuf {
        self.tool_root.join("bin")
    }

    pub fn ndk_root(&self) -> PathBuf {
        self.android_sdk.join("ndk").join(NDK_VERSION)
    }

    pub fn rust_target(&self) -> PathBuf {
        self.rust.join("target")
    }

    pub fn generated_bindings(&self) -> PathBuf {
        self.root.join("rust-bindings/src")
    }

    pub fn jni_libs(&self) -> PathBuf {
        self.root.join("app/jniLibs")
    }

    pub fn temp_dir(&self, name: &str) -> Result<PathBuf> {
        let directory = self
            .rust_target()
            .join("xtask")
            .join(format!("{name}-{}", std::process::id()));
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
