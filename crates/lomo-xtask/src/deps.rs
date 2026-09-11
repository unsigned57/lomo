use anyhow::{Result, bail};

use crate::tools;
use crate::util::{cargo, run};
use crate::workspace::Workspace;

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
            let mut machete = cargo(workspace);
            machete.arg("machete");
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
