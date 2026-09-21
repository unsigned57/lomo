//! Behavior Contract:
//! Capability: inspect an owner-scoped worktree verification plan without starting build tools;
//! owning layer: lomo-xtask; priority: P0.
//! Scenarios:
//! - Given a TUI iteration, when dev --scope lomo-tui --plan runs, then only host checks are planned.
//! - Given an unknown owner, when planning, then the command rejects the scope explicitly.
//! - Given the retired ffi-parity dispatch, when the CLI runs, then it is not advertised and not
//!   accepted: the DAG's own `ffi-contract` node owns explicit-export verification.
//!
//! Observable outcomes: CLI status and the serialized task graph, including scope completeness.
//! TDD proof: `cargo test -p lomo-xtask --test verification_cli_contract --locked`;
//! RED: the existing CLI rejects dev as an unknown command; GREEN: same command after wiring.
//! RED: the CLI advertised and accepted `ffi-parity` before the legacy dispatch was deleted.
//! Excludes: execution of Rust/Kotlin compilers and native packaging.

#[cfg(test)]
mod tests {
    use std::process::Command;

    use anyhow::{Context, Result, ensure};
    use serde_json::Value;

    #[test]
    fn tui_iteration_plans_host_checks_without_android_tools() -> Result<()> {
        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["dev", "--scope", "lomo-tui", "--plan"])
            .output()?;
        ensure!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
        let plan: Value = serde_json::from_slice(&output.stdout).context("plan JSON")?;
        let tasks = plan
            .get("tasks")
            .and_then(Value::as_array)
            .context("task array")?;
        ensure!(
            tasks
                .iter()
                .any(|task| task["id"] == "rust-clippy:lomo-tui")
        );
        ensure!(tasks.iter().any(|task| task["id"] == "rust-tests:lomo-tui"));
        ensure!(!tasks.iter().any(|task| {
            task["id"].as_str().is_some_and(|id| {
                id.starts_with("kotlin") || id.starts_with("native") || id == "bindings"
            })
        }));
        ensure!(plan.get("scope").and_then(Value::as_str) == Some("lomo-tui"));
        ensure!(plan.get("complete_worktree").is_some_and(Value::is_boolean));
        Ok(())
    }

    #[test]
    fn unknown_owner_is_not_an_empty_successful_plan() -> Result<()> {
        let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(["dev", "--scope", "missing-owner", "--plan"])
            .output()?;
        ensure!(!output.status.success());
        ensure!(String::from_utf8_lossy(&output.stderr).contains("unknown verification owner"));
        Ok(())
    }

    #[test]
    fn the_retired_ffi_parity_dispatch_is_gone() -> Result<()> {
        let help = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .arg("help")
            .output()?;
        ensure!(help.status.success());
        let help = String::from_utf8_lossy(&help.stderr);
        ensure!(
            !help.contains("ffi-parity"),
            "the retired command must not be advertised: {help}"
        );
        let retired = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .arg("ffi-parity")
            .output()?;
        ensure!(
            !retired.status.success(),
            "ffi-parity must not remain an accepted command"
        );
        ensure!(String::from_utf8_lossy(&retired.stderr).contains("unknown xtask command"));
        Ok(())
    }
}
