//! Test Change Justification:
//! Reason category: gate contents changed under an unchanged CLI contract.
//! Old behavior/assertion being replaced: `fmt check` ran only `cargo fmt`, so a PATH holding a
//! fake `cargo` alone exercised the whole command.
//! Why old assertion is no longer correct: `fmt check` now also verifies Kotlin layout through
//! the policy script, which needs `bash` on PATH; without it the passing case cannot pass.
//! Coverage preserved by: the same exit/JSON/stderr assertions for both child outcomes; a `bash`
//! stub stands for a passing Kotlin check so only the injected `cargo` outcome varies.
//! Why this is not fitting the test to the implementation: the asserted JSON/exit contract is
//! unchanged; the stub only supplies the second child the command now legitimately runs.
//!
//! Behavior Contract:
//! Capability: agents discover commands and consume results without parsing terminal prose;
//! owning layer: lomo-xtask; priority: P0.
//! Scenarios:
//! - Given no command, when invoked, then stdout contains one versioned JSON command catalog.
//! - Given invalid command input, when invoked, then JSON explains failure and exit is nonzero.
//! - Given the unified application build entry, when the catalog is inspected, then `build`
//!   exists and per-app commands (`android`, `tui`, `install-tui`) do not.
//! - Given cache discovery, when invoked, then resolved paths are JSON data.
//! - Given a child tool writing stdout, when formatting, then logs cannot corrupt result JSON.
//! - Given a passing/failing verification tool, when executing, then JSON agrees with the saved
//!   report, preserves scope and log paths, and reports failure without losing evidence.
//!
//! Observable outcomes: process exit status, decoded stdout, and child diagnostics on stderr.
//! TDD proof: `cargo test -p lomo-xtask --test agent_cli_contract --locked`;
//! RED: existing discovery/cache/error output is not JSON; GREEN: same command after implementation.
//! Excludes: running real compilers, changing gate contents, and release packaging.

#[cfg(test)]
mod tests {
    use std::process::{Command, Output};

    use anyhow::{Context, Result, ensure};
    use serde_json::Value;

    fn invoke(arguments: &[&str]) -> Result<Output> {
        Ok(Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
            .args(arguments)
            .output()?)
    }

    fn document(output: &Output) -> Result<Value> {
        let result: Value = serde_json::from_slice(&output.stdout)
            .context("stdout must contain exactly one JSON result")?;
        ensure!(
            result
                .pointer("/schema_version")
                .context("/schema_version")?
                == 1
        );
        Ok(result)
    }

    #[test]
    fn discovery_is_a_machine_readable_catalog() -> Result<()> {
        for arguments in [&[][..], &["commands"][..], &["help"][..]] {
            let output = invoke(arguments)?;
            ensure!(output.status.success());
            let result = document(&output)?;
            ensure!(result.pointer("/status").context("/status")? == "succeeded");
            let commands = result
                .pointer("/data/commands")
                .context("/data/commands")?
                .as_array()
                .context("commands")?;
            ensure!(commands.iter().any(|command| command["name"] == "dev"));
            ensure!(commands.iter().any(|command| command["name"] == "check"));
            ensure!(commands.iter().any(|command| command["name"] == "build"));
            for retired in ["install-tui", "android", "tui"] {
                ensure!(
                    !commands.iter().any(|command| command["name"] == retired),
                    "retired per-app command remains in catalog: {retired}"
                );
            }
        }
        Ok(())
    }

    #[test]
    fn invalid_arguments_return_json_and_fail_closed() -> Result<()> {
        for arguments in [
            &["missing-command"][..],
            &["cache", "missing-mode"][..],
            &["commands", "unexpected"][..],
            &["dev", "--scope"][..],
            &["build"][..],
            &["build", "missing-target"][..],
            &["build", "tui", "missing-profile"][..],
            &["build", "tui", "debug", "extra"][..],
        ] {
            let output = invoke(arguments)?;
            ensure!(!output.status.success());
            let result = document(&output)?;
            ensure!(result.pointer("/status").context("/status")? == "failed");
            ensure!(
                result
                    .pointer("/error/message")
                    .context("/error/message")?
                    .as_str()
                    .is_some_and(|text| !text.is_empty())
            );
        }
        Ok(())
    }

    #[test]
    fn cache_paths_are_named_json_values() -> Result<()> {
        let output = invoke(&["cache", "paths"])?;
        ensure!(output.status.success());
        let result = document(&output)?;
        let paths = result.pointer("/data/paths").context("/data/paths")?;
        ensure!(
            paths
                .pointer("/cargo_target")
                .context("/cargo_target")?
                .as_str()
                .is_some_and(|path| std::path::Path::new(path).is_absolute())
        );
        ensure!(
            paths
                .pointer("/kotlin_build")
                .context("/kotlin_build")?
                .as_str()
                .is_some_and(|path| std::path::Path::new(path).is_absolute())
        );
        Ok(())
    }

    #[cfg(unix)]
    #[test]
    fn child_output_and_failure_do_not_corrupt_json() -> Result<()> {
        use std::{fs, os::unix::fs::PermissionsExt as _};

        let directory = tempfile::tempdir()?;
        let cargo = directory.path().join("cargo");
        for exit in [0, 7] {
            fs::write(
                &cargo,
                format!("#!/bin/sh\necho child-diagnostic\nexit {exit}\n"),
            )?;
            fs::set_permissions(&cargo, fs::Permissions::from_mode(0o755))?;
            let bash = directory.path().join("bash");
            fs::write(&bash, "#!/bin/sh\nexit 0\n")?;
            fs::set_permissions(&bash, fs::Permissions::from_mode(0o755))?;
            let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
                .args(["fmt", "check"])
                .env("PATH", directory.path())
                .output()?;
            ensure!(output.status.success() == (exit == 0));
            let result = document(&output)?;
            ensure!(
                result.pointer("/status").context("/status")?
                    == if exit == 0 { "succeeded" } else { "failed" }
            );
            ensure!(String::from_utf8_lossy(&output.stderr).contains("child-diagnostic"));
        }
        Ok(())
    }

    #[cfg(unix)]
    #[test]
    fn verification_preserves_pass_and_failure_evidence() -> Result<()> {
        use std::{fs, os::unix::fs::PermissionsExt as _};

        let directory = tempfile::tempdir()?;
        let cargo = directory.path().join("cargo");
        fs::write(
            &cargo,
            r#"#!/bin/sh
if [ "$1" = metadata ]; then
    exec "$LOMO_TEST_REAL_CARGO" "$@"
fi
echo 'test result: ok. 1 passed; 0 failed; 0 ignored;'
echo injected-tool-diagnostic >&2
exit "$LOMO_TEST_TOOL_EXIT"
"#,
        )?;
        fs::set_permissions(&cargo, fs::Permissions::from_mode(0o755))?;
        let mut paths = vec![directory.path().to_path_buf()];
        paths.extend(std::env::split_paths(
            &std::env::var_os("PATH").context("PATH")?,
        ));
        let path = std::env::join_paths(paths)?;
        for exit in [0, 7] {
            let output = Command::new(env!("CARGO_BIN_EXE_lomo-xtask"))
                .args(["dev", "--scope", "lomo-xtask", "--tests-only"])
                .env("PATH", &path)
                .env("LOMO_TEST_REAL_CARGO", env!("CARGO"))
                .env("LOMO_TEST_TOOL_EXIT", exit.to_string())
                .env("CARGO_TARGET_DIR", directory.path().join("target"))
                .env("LOMO_GENERATED_ROOT", directory.path().join("generated"))
                .env("LOMO_REPORTS_DIR", directory.path().join("reports"))
                .output()?;
            let result = document(&output)?;
            ensure!(output.status.success() == (exit == 0), "{result}");
            ensure!(
                result.pointer("/status").context("/status")?
                    == if exit == 0 { "succeeded" } else { "failed" }
            );
            let evidence = result.pointer("/data").context("/data")?;
            let report_path = evidence
                .pointer("/report_path")
                .context("/report_path")?
                .as_str()
                .context("report path")?;
            let report: Value = serde_json::from_slice(&fs::read(report_path)?)?;
            ensure!(report == *evidence);
            ensure!(evidence.pointer("/plan/scope").context("/plan/scope")? == "lomo-xtask");
            let reports = evidence
                .pointer("/results")
                .context("/results")?
                .as_array()
                .context("task results")?;
            ensure!(
                reports
                    .iter()
                    .any(|task| task["status"] == if exit == 0 { "Passed" } else { "Failed" })
            );
            let log = evidence
                .pointer("/logs/rust-tests:lomo-xtask")
                .context("/logs/rust-tests:lomo-xtask")?
                .as_str()
                .context("task log")?;
            ensure!(fs::read_to_string(log)?.contains("injected-tool-diagnostic"));
        }
        Ok(())
    }
}
