//! Behavior Contract:
//! Capability: bounded push checks and prompt failure in the verification scheduler;
//! owning layer: lomo-xtask; priority: P1.
//! Scenarios:
//! - Given any changed owner, when planning pre-push, then only formatting and quick policies run.
//! - Given a full check, when planning, then quick policies precede compiler and test tasks.
//! - Given a failed task and queued independent work sharing its resource, when executing,
//!   then the queued work is `NotScheduled` and never invokes its runner.
//! - Given passing tasks, when executing, then every selected task runs and passes.
//! - Given failed CI attempts, when restoring/saving build caches, then compatible previous
//!   snapshots are reusable and the current attempt can publish its progress.
//! - Given a release without notes, when scheduling, then the expensive gate cannot start.
//! - Given unknown executable paths, when selecting CI lanes, then every lane runs; known prose
//!   may skip builds, and malformed inventories fail before deriving successful flags.
//! - Given prose-only versus source commits, when the hook runs, then only source commits
//!   bootstrap a formatter, and unstaged edits survive both paths.
//!
//! Observable outcomes: task actions, dependencies, invocation list and result statuses.
//! TDD proof: `cargo test -p lomo-xtask --test development_flow_contract --locked`;
//! RED: pre-push contains architecture/compilation, quick policies are absent, and queued work runs.
//! GREEN: the same command after implementing the stage boundaries and fail-fast scheduling.
//! Excludes: CI wall-clock speed, compiler caching and external service availability.
//!
//! Test Change Justification:
//! Reason category: release checks now have one producer instead of a workflow-call duplicate.
//! Old behavior/assertion being replaced: the full fixture supplies the removed `inputs.full` switch.
//! Why old assertion is no longer correct: default-branch pushes/manual runs own full evidence;
//! feature-branch/PR events select paths. Release consumes that evidence without executing tests.
//! Coverage preserved by: identical selective/full outcomes, plus default-branch and manual controls.
//! Why this is not fitting the test to the implementation: `ci_release_dedup_contract` first exposes
//! the actual duplicate invocation and rejects incomplete evidence before release can proceed.

#[cfg(test)]
mod tests {
    use std::{collections::BTreeSet, sync::Mutex};

    use anyhow::{Context, Result, ensure};
    use lomo_xtask::verification::{
        ChangeInventory, CommandRunner, ImpactGraph, PlanMode, Resource, Task, TaskAction,
        TaskOutcome, TaskStatus, VerificationPlan, execute,
    };

    fn plan(mode: PlanMode) -> Result<VerificationPlan> {
        VerificationPlan::build(
            &ImpactGraph::load(&lomo_xtask::repository_root()?)?,
            &ChangeInventory {
                paths: BTreeSet::new(),
                removed: BTreeSet::new(),
                complete: true,
            },
            None,
            mode,
        )
    }

    #[test]
    fn pre_push_does_not_compile_test_or_generate_native_bindings() -> Result<()> {
        let plan = plan(PlanMode::Preflight)?;
        let ids: BTreeSet<_> = plan.tasks.iter().map(|task| task.id.as_str()).collect();
        ensure!(
            ids == BTreeSet::from(["quick-contracts", "rust-fmt", "kotlin-format"]),
            "pre-push must be a bounded policy check: {ids:?}"
        );
        Ok(())
    }

    #[test]
    fn quick_contracts_block_expensive_work_before_it_starts() -> Result<()> {
        let plan = plan(PlanMode::Check)?;
        ensure!(plan.tasks.iter().any(|task| task.id == "quick-contracts"));
        for task in plan
            .tasks
            .iter()
            .filter(|task| task.id != "quick-contracts")
        {
            ensure!(
                task.dependencies.contains("quick-contracts"),
                "{} can start before quick policies pass",
                task.id
            );
        }
        Ok(())
    }

    struct Runner {
        fail: bool,
        calls: Mutex<Vec<String>>,
    }

    impl CommandRunner for Runner {
        fn input_digest(&self, _task: &Task) -> Result<String> {
            Ok("fixed-input".to_owned())
        }

        fn run(&self, task: &Task) -> Result<TaskOutcome> {
            self.calls
                .lock()
                .map_err(|e| anyhow::anyhow!("{e}"))?
                .push(task.id.clone());
            Ok(if self.fail {
                TaskOutcome::Failure("injected policy failure".to_owned())
            } else {
                TaskOutcome::Success { tests: None }
            })
        }

        fn verify_outputs(&self, _task: &Task) -> Result<()> {
            Ok(())
        }
    }

    fn independent_serial_tasks() -> VerificationPlan {
        VerificationPlan {
            scope: None,
            complete_worktree: true,
            excluded_owners: BTreeSet::new(),
            tasks: ["first", "queued"]
                .map(|id| Task {
                    id: id.to_owned(),
                    action: TaskAction::RustFmt,
                    dependencies: BTreeSet::new(),
                    inputs: BTreeSet::new(),
                    outputs: BTreeSet::new(),
                    tools: BTreeSet::new(),
                    resources: BTreeSet::from([Resource::Cargo]),
                    reason: "independent work sharing a compiler".to_owned(),
                })
                .to_vec(),
        }
    }

    #[test]
    fn failure_stops_queued_independent_work() -> Result<()> {
        let runner = Runner {
            fail: true,
            calls: Mutex::new(Vec::new()),
        };
        let reports = execute(&independent_serial_tasks(), &runner)?;
        ensure!(reports.first().context("first")?.status == TaskStatus::Failed);
        ensure!(
            reports.last().context("queued")?.status == TaskStatus::NotScheduled,
            "independent expensive work must stop after a failure"
        );
        ensure!(*runner.calls.lock().map_err(|e| anyhow::anyhow!("{e}"))? == ["first"]);
        Ok(())
    }

    #[test]
    fn successful_work_still_executes_every_selected_task() -> Result<()> {
        let runner = Runner {
            fail: false,
            calls: Mutex::new(Vec::new()),
        };
        let reports = execute(&independent_serial_tasks(), &runner)?;
        ensure!(reports.len() == 2 && reports.iter().all(|r| r.status == TaskStatus::Passed));
        ensure!(*runner.calls.lock().map_err(|e| anyhow::anyhow!("{e}"))? == ["first", "queued"]);
        Ok(())
    }

    fn workflow(file: &str) -> Result<yaml_rust2::Yaml> {
        let text = std::fs::read_to_string(lomo_xtask::repository_root()?.join(file))?;
        let documents = yaml_rust2::YamlLoader::load_from_str(&text)?;
        documents.into_iter().next().context("workflow document")
    }

    #[test]
    fn build_caches_advance_on_each_attempt_and_survive_gate_failures() -> Result<()> {
        for file in [
            ".github/workflows/architecture_checks.yml",
            ".github/workflows/android_release.yml",
            ".github/workflows/tui_release.yml",
        ] {
            let document = workflow(file)?;
            for (name, job) in document["jobs"].as_hash().context("jobs")? {
                let Some(steps) = job["steps"].as_vec() else {
                    continue;
                };
                for step in steps {
                    let Some(path) = step["with"]["path"].as_str() else {
                        continue;
                    };
                    if !path.contains("target/cargo") {
                        continue;
                    }
                    let action = step["uses"].as_str().context("cache action")?;
                    if action.starts_with("actions/cache/restore@") {
                        let key = step["with"]["key"].as_str().context("build key")?;
                        ensure!(
                            key.contains("github.run_id") && key.contains("github.run_attempt"),
                            "{file} {name:?}: immutable build cache never advances: {key}"
                        );
                        ensure!(
                            step["with"]["restore-keys"].as_str().is_some(),
                            "{file} {name:?}: no warm-build prefix"
                        );
                    } else {
                        ensure!(
                            action.starts_with("actions/cache/save@"),
                            "{file} {name:?}: build cache saves only on success"
                        );
                        ensure!(
                            step["if"]
                                .as_str()
                                .context("save condition")?
                                .contains("!cancelled()"),
                            "{file} {name:?}: failed compilation loses reusable outputs"
                        );
                    }
                }
            }
        }
        Ok(())
    }

    #[test]
    fn ci_runs_the_short_policy_regression_before_bootstrap() -> Result<()> {
        let document = workflow(".github/workflows/architecture_checks.yml")?;
        let steps = document["jobs"]["changes"]["steps"]
            .as_vec()
            .context("changes steps")?;
        ensure!(
            steps.iter().any(|step| step["run"].as_str().is_some_and(
                |run| run.contains("quality/scripts/test/check_meaningful_tests_smoke_test.sh")
            )),
            "the nine-second regression must fail before any compiler bootstrap"
        );
        Ok(())
    }

    #[test]
    fn ci_selection_skips_known_prose_and_runs_unknown_inputs_conservatively() -> Result<()> {
        use std::{fs, process::Command};
        let document = workflow(".github/workflows/architecture_checks.yml")?;
        let steps = document["jobs"]["changes"]["steps"]
            .as_vec()
            .context("changes steps")?;
        let script = steps
            .iter()
            .find(|step| step["id"].as_str() == Some("flags"))
            .context("flag derivation step")?["run"]
            .as_str()
            .context("flag script")?;
        for (name, changed, known_rust, manual, expected) in [
            (
                "docs",
                vec!["docs/guide.md"],
                vec![],
                false,
                [false, false, false, false],
            ),
            (
                "tui",
                vec!["apps/tui/src/lib.rs"],
                vec!["apps/tui/src/lib.rs"],
                false,
                [true, true, false, false],
            ),
            (
                "unknown",
                vec![".gitignore"],
                vec![],
                false,
                [true, true, true, true],
            ),
            (
                "mixed",
                vec!["apps/tui/src/lib.rs", "new-tool.py"],
                vec!["apps/tui/src/lib.rs"],
                false,
                [true, true, true, true],
            ),
            ("manual", vec![], vec![], true, [true, true, true, true]),
        ] {
            let directory = tempfile::tempdir()?;
            let output_file = directory.path().join("flags");
            let mut script = script.to_owned();
            for (key, value) in [
                (
                    "rust",
                    if known_rust.is_empty() {
                        "false"
                    } else {
                        "true"
                    },
                ),
                ("kotlin", "false"),
                ("native", "false"),
                ("quality_infra", "false"),
            ] {
                script = script.replace(&format!("${{{{ steps.filter.outputs.{key} }}}}"), value);
            }
            let output = Command::new("bash")
                .args(["-euo", "pipefail", "-c", &script])
                .env(
                    "GITHUB_EVENT_NAME",
                    if manual { "workflow_dispatch" } else { "push" },
                )
                .env("GITHUB_REF", "refs/heads/feature")
                .env("DEFAULT_BRANCH", "main")
                .env("GITHUB_OUTPUT", &output_file)
                .env("ALL_CHANGED_FILES", serde_json::to_string(&changed)?)
                .env("RUST_CHANGED_FILES", serde_json::to_string(&known_rust)?)
                .env("KOTLIN_CHANGED_FILES", "[]")
                .env("NATIVE_CHANGED_FILES", "[]")
                .env("QUALITY_CHANGED_FILES", "[]")
                .output()?;
            ensure!(
                output.status.success(),
                "{name}: {}",
                String::from_utf8_lossy(&output.stderr)
            );
            let actual = fs::read_to_string(&output_file)?;
            for (flag, value) in ["run_check", "run_rust", "run_native", "run_android"]
                .into_iter()
                .zip(expected)
            {
                ensure!(
                    actual.lines().any(|line| line == format!("{flag}={value}")),
                    "{name}: incorrect {flag} in {actual}"
                );
            }
        }
        Ok(())
    }

    #[test]
    fn malformed_ci_inventory_cannot_produce_green_flags() -> Result<()> {
        use std::process::Command;
        let document = workflow(".github/workflows/architecture_checks.yml")?;
        let script = document["jobs"]["changes"]["steps"]
            .as_vec()
            .context("changes steps")?
            .iter()
            .find(|step| step["id"].as_str() == Some("flags"))
            .context("flag derivation step")?["run"]
            .as_str()
            .context("flag script")?;
        let mut invalid = script.to_owned();
        for key in ["rust", "kotlin", "native", "quality_infra"] {
            invalid = invalid.replace(&format!("${{{{ steps.filter.outputs.{key} }}}}"), "false");
        }
        let directory = tempfile::tempdir()?;
        for inventory in ["malformed-json", "null", "{}", "[3]"] {
            let output = Command::new("bash")
                .args(["-euo", "pipefail", "-c", &invalid])
                .env("GITHUB_EVENT_NAME", "push")
                .env("GITHUB_REF", "refs/heads/feature")
                .env("DEFAULT_BRANCH", "main")
                .env("GITHUB_OUTPUT", directory.path().join("flags"))
                .env("ALL_CHANGED_FILES", inventory)
                .env("RUST_CHANGED_FILES", "[]")
                .env("KOTLIN_CHANGED_FILES", "[]")
                .env("NATIVE_CHANGED_FILES", "[]")
                .env("QUALITY_CHANGED_FILES", "[]")
                .output()?;
            ensure!(
                !output.status.success(),
                "malformed inventory {inventory} must not produce green flags"
            );
        }
        Ok(())
    }

    #[test]
    fn rust_delivery_job_does_not_repeat_the_check_graph() -> Result<()> {
        let document = workflow(".github/workflows/architecture_checks.yml")?;
        let steps = document["jobs"]["rust-host"]["steps"]
            .as_vec()
            .context("Rust steps")?;
        ensure!(steps.iter().any(|step| {
            step["run"]
                .as_str()
                .is_some_and(|run| run.ends_with("lomo-xtask -- ci-rust delivery"))
        }));
        Ok(())
    }

    #[test]
    fn release_notes_reject_before_the_reusable_gate_starts() -> Result<()> {
        let document = workflow(".github/workflows/android_release.yml")?;
        ensure!(
            document["jobs"]["gate"]["needs"] == yaml_rust2::Yaml::String("notes".to_owned()),
            "missing notes must stop the expensive release gate"
        );
        Ok(())
    }

    #[cfg(unix)]
    #[test]
    fn documentation_commits_skip_formatter_bootstrap_and_source_commits_keep_it() -> Result<()> {
        use std::{fs, os::unix::fs::PermissionsExt as _, process::Command};
        for (file, expected) in [
            ("guide.md", "contracts\n"),
            ("source.rs", "format\ncontracts\n"),
        ] {
            let fixture = tempfile::tempdir()?;
            let root = fixture.path();
            let status = Command::new("git")
                .args(["init", "-q"])
                .current_dir(root)
                .status()?;
            ensure!(status.success());
            fs::create_dir_all(root.join("quality/scripts"))?;
            fs::create_dir(root.join("bin"))?;
            fs::write(root.join("Justfile"), "# fixture")?;
            for (path, command) in [
                ("bin/just", "format"),
                ("quality/scripts/check_meaningful_tests.sh", "contracts"),
            ] {
                fs::write(
                    root.join(path),
                    format!("#!/bin/sh\necho {command} >> \"$TRACE\"\n"),
                )?;
                fs::set_permissions(root.join(path), fs::Permissions::from_mode(0o755))?;
            }
            fs::write(root.join(file), "staged\n")?;
            ensure!(
                Command::new("git")
                    .args(["add", file])
                    .current_dir(root)
                    .status()?
                    .success()
            );
            // An unstaged edit to a staged file must survive either path through the hook.
            fs::write(root.join(file), "staged\nunstaged\n")?;
            let path = std::env::join_paths(std::iter::once(root.join("bin")).chain(
                std::env::split_paths(&std::env::var_os("PATH").context("PATH")?),
            ))?;
            let output = Command::new("bash")
                .arg(lomo_xtask::repository_root()?.join(".githooks/pre-commit"))
                .current_dir(root)
                .env("PATH", path)
                .env("TRACE", root.join("trace"))
                .output()?;
            ensure!(
                output.status.success(),
                "{}",
                String::from_utf8_lossy(&output.stderr)
            );
            ensure!(
                fs::read_to_string(root.join("trace"))? == expected,
                "{file} bootstrapped an unnecessary formatter"
            );
            ensure!(fs::read_to_string(root.join(file))? == "staged\nunstaged\n");
        }
        Ok(())
    }
}
