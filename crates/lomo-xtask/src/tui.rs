use std::{fs, path::PathBuf};

use anyhow::{Context, Result, bail, ensure};

use crate::{
    util::{cargo, run},
    workspace::Workspace,
};

/// Cargo profile for the desktop `lomo` binary produced by `apps/tui`.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TuiProfile {
    Debug,
    Release,
}

impl TuiProfile {
    pub fn parse(value: &str) -> Result<Self> {
        match value {
            "debug" => Ok(Self::Debug),
            "release" => Ok(Self::Release),
            _ => bail!("unknown tui profile: {value}"),
        }
    }

    pub const fn target_dir_name(self) -> &'static str {
        match self {
            Self::Debug => "debug",
            Self::Release => "release",
        }
    }
}

/// Builds `lomo-tui` and publishes the binary alongside other deliverable packages.
pub fn build(workspace: &Workspace, profile: TuiProfile) -> Result<PathBuf> {
    crate::cache::prune(workspace)?;
    let mut command = cargo(workspace);
    command.args(["build", "--locked", "--package", "lomo-tui"]);
    if profile == TuiProfile::Release {
        command.arg("--release");
    }
    run(&mut command)?;
    let binary = workspace
        .rust_target()
        .join(profile.target_dir_name())
        .join(format!("lomo{}", std::env::consts::EXE_SUFFIX));
    ensure!(
        binary.is_file(),
        "cargo build did not produce {}",
        binary.display()
    );
    let directory = workspace.tui_output_dir(profile.target_dir_name());
    fs::create_dir_all(&directory).with_context(|| format!("create {}", directory.display()))?;
    let published = directory.join(format!("lomo{}", std::env::consts::EXE_SUFFIX));
    fs::copy(&binary, &published)
        .with_context(|| format!("publish {} -> {}", binary.display(), published.display()))?;
    Ok(published)
}
