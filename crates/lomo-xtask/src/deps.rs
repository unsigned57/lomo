use anyhow::{Result, bail};

use crate::{
    tools,
    util::{cargo, repository_command, run},
    workspace::Workspace,
};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum DependencyMode {
    Check,
    Update,
}

pub fn run_dependencies(workspace: &Workspace, mode: DependencyMode) -> Result<()> {
    tools::ensure_quality(workspace)?;
    match mode {
        DependencyMode::Check => {
            let mut deny = cargo(workspace);
            deny.args(["deny", "check"]);
            run(&mut deny)?;
            // Invoke the tool binary directly: `cargo machete` relies on the
            // `CARGO` env var that `cargo()` intentionally strips, which would
            // leave "machete" misread as a path argument.
            let mut machete =
                repository_command(workspace, workspace.tool_bin().join("cargo-machete"));
            run(&mut machete)?;
            let mut update = cargo(workspace);
            update.args(["update", "--dry-run"]);
            run(&mut update)
        }
        DependencyMode::Update => {
            let mut update = cargo(workspace);
            update.arg("update");
            run(&mut update)?;
            let mut deny = cargo(workspace);
            deny.args(["deny", "check"]);
            run(&mut deny)
        }
    }
}

pub fn parse_mode(value: &str) -> Result<DependencyMode> {
    match value {
        "check" => Ok(DependencyMode::Check),
        "update" => Ok(DependencyMode::Update),
        _ => bail!("deps mode must be `check` or `update`, found `{value}`"),
    }
}
