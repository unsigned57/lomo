//! Behavior Contract:
//! Capability: select the affected owner closure and execute a verification DAG honestly;
//! owning layer: lomo-xtask; priority: P0.
//! Scenarios:
//! - Given TUI/app/domain/FFI/golden/deleted sources, when planning, then the matching owners run.
//! - Given a moved Unicode path, when reading the worktree, then both endpoints retain ownership.
//! - Given failed/cancelled/empty tests or missing outputs, when executing, then no pass is cached.
//! - Given source changes during a run, when finishing, then earlier evidence is invalidated.
//!
//! Observable outcomes: task sets, dependency ordering, declared outputs and result states.
//! TDD proof: `verification_cli_contract` RED rejected dev; this matrix additionally observes RED
//! for missing declared generation outputs before completing the scheduler.
//! Excludes: performance timing, compiler implementation and Android device execution.

#[cfg(test)]
mod tests {
    use anyhow::{Context, Result, ensure};
    use lomo_xtask::verification::{
        Artifact, ChangeInventory, ChangeSource, CommandRunner, ImpactGraph, PlanMode, Task,
        TaskAction, TaskOutcome, TaskStatus, VerificationPlan, execute, unified_diff,
    };
    use std::{
        collections::{BTreeMap, BTreeSet},
        path::{Path, PathBuf},
        process::Command,
        sync::Mutex,
    };

    fn graph() -> Result<ImpactGraph> {
        ImpactGraph::load(&lomo_xtask::repository_root()?)
    }

    fn plan(paths: &[&str]) -> Result<VerificationPlan> {
        plan_with_removed(paths, &[])
    }

    fn plan_with_removed(paths: &[&str], removed: &[&str]) -> Result<VerificationPlan> {
        VerificationPlan::build(
            &graph()?,
            &ChangeInventory {
                paths: paths.iter().map(PathBuf::from).collect(),
                removed: removed.iter().map(PathBuf::from).collect(),
                complete: false,
            },
            None,
            PlanMode::Dev,
        )
    }

    fn check_plan(paths: &[&str]) -> Result<VerificationPlan> {
        VerificationPlan::build(
            &graph()?,
            &ChangeInventory {
                paths: paths.iter().map(PathBuf::from).collect(),
                removed: BTreeSet::new(),
                complete: false,
            },
            None,
            PlanMode::Check,
        )
    }

    fn ids(plan: &VerificationPlan) -> BTreeSet<&str> {
        plan.tasks.iter().map(|task| task.id.as_str()).collect()
    }

    #[test]
    fn tui_and_app_iterations_do_not_schedule_native_packaging() -> Result<()> {
        let tui = plan(&["apps/tui/src/view.rs"])?;
        ensure!(ids(&tui).contains("rust-tests:lomo-tui"));
        ensure!(
            !ids(&tui)
                .iter()
                .any(|id| id.starts_with("kotlin") || *id == "bindings")
        );
        let app = plan(&["apps/android/app/src/feature/main/MainViewModel.kt"])?;
        for required in ["kotlin-light", "kotlin-full", "kotlin-tests:app"] {
            ensure!(ids(&app).contains(required));
        }
        ensure!(
            !ids(&app)
                .iter()
                .any(|id| id.starts_with("rust-tests") || id.starts_with("native-pack"))
        );
        Ok(())
    }

    #[test]
    fn domain_models_expand_reverse_dependencies_and_ffi_expands_consumers() -> Result<()> {
        let domain = plan(&["apps/android/domain/src/model/Memo.kt"])?;
        for required in [
            "kotlin-tests:domain",
            "kotlin-tests:data",
            "kotlin-tests:app",
            "kotlin-tests:ui-components",
        ] {
            ensure!(ids(&domain).contains(required), "missing {required}");
        }
        let native = plan(&["crates/lomo-native/src/session_ffi.rs"])?;
        for required in [
            "bindings",
            "rust-tests:lomo-native",
            "kotlin-tests:data",
            "kotlin-tests:app",
        ] {
            ensure!(ids(&native).contains(required), "missing {required}");
        }
        Ok(())
    }

    #[test]
    fn dev_mode_schedules_diff_scoped_mutants_only_on_touched_rust_crates() -> Result<()> {
        let store = plan(&["crates/lomo-store/src/cursor.rs"])?;
        let mutants: Vec<&Task> = store
            .tasks
            .iter()
            .filter(|task| task.id.starts_with("rust-mutants:"))
            .collect();
        ensure!(
            mutants.len() == 1,
            "expected exactly one mutants task: {:?}",
            ids(&store)
        );
        let task = mutants.first().context("mutants task")?;
        ensure!(task.id == "rust-mutants:lomo-store");
        ensure!(
            task.dependencies.contains("rust-tests:lomo-store"),
            "mutation runs only after the package suite is green"
        );
        ensure!(
            task.tools
                .iter()
                .any(|tool| format!("{tool:?}") == "Mutants"),
            "the task must declare the pinned cargo-mutants tool"
        );

        // An API-shaped change pulls reverse dependencies into the gate, but only the
        // directly touched crate has diff hunks to mutate.
        let api = plan(&["crates/lomo-store/Cargo.toml"])?;
        let mutants: BTreeSet<&str> = api
            .tasks
            .iter()
            .filter(|task| task.id.starts_with("rust-mutants:"))
            .map(|task| task.id.as_str())
            .collect();
        ensure!(
            mutants == BTreeSet::from(["rust-mutants:lomo-store"]),
            "{mutants:?}"
        );

        // Kotlin-only and complete-inventory plans never schedule mutation tasks.
        let kotlin = plan(&["apps/android/app/src/feature/main/MainViewModel.kt"])?;
        ensure!(!ids(&kotlin).iter().any(|id| id.starts_with("rust-mutants")));
        ensure!(
            !ids(&check_plan(&["crates/lomo-store/src/cursor.rs"])?)
                .iter()
                .any(|id| id.starts_with("rust-mutants"))
        );
        let complete = VerificationPlan::build(
            &graph()?,
            &ChangeInventory {
                paths: BTreeSet::new(),
                removed: BTreeSet::new(),
                complete: true,
            },
            None,
            PlanMode::Dev,
        )?;
        ensure!(
            !ids(&complete)
                .iter()
                .any(|id| id.starts_with("rust-mutants"))
        );
        Ok(())
    }

    #[test]
    fn product_kotlin_changes_schedule_usecase_reachability() -> Result<()> {
        // Any product module can break reachability: domain adds a usecase, app/data drop a caller.
        for path in [
            "apps/android/domain/src/usecase/CreateMemoUseCase.kt",
            "apps/android/app/src/feature/main/MainViewModel.kt",
        ] {
            let planned = plan(&[path])?;
            ensure!(
                ids(&planned).contains("usecase-reachability"),
                "{path} must schedule the usecase reachability contract: {:?}",
                ids(&planned)
            );
        }
        let checked = check_plan(&["apps/android/app/src/feature/main/MainViewModel.kt"])?;
        ensure!(ids(&checked).contains("usecase-reachability"));

        let rust_only = plan(&["crates/lomo-store/src/cursor.rs"])?;
        ensure!(!ids(&rust_only).contains("usecase-reachability"));
        let tests_only = VerificationPlan::build(
            &graph()?,
            &ChangeInventory {
                paths: BTreeSet::from([PathBuf::from(
                    "apps/android/domain/src/usecase/CreateMemoUseCase.kt",
                )]),
                removed: BTreeSet::new(),
                complete: false,
            },
            None,
            PlanMode::Tests,
        )?;
        ensure!(!ids(&tests_only).contains("usecase-reachability"));
        Ok(())
    }

    #[test]
    fn golden_vectors_and_deleted_sources_never_become_docs_only() -> Result<()> {
        ensure!(ids(&plan(&["fixtures/sync/v1.json"])?).contains("rust-tests:lomo-sync"));
        ensure!(
            ids(&plan(&["crates/lomo-store/tests/deleted.rs"])?).contains("rust-tests:lomo-store")
        );
        let docs = plan(&["docs/explanation.md"])?;
        ensure!(!ids(&docs).iter().any(|id| *id == "bindings"
            || id.starts_with("kotlin-tests")
            || id.starts_with("rust-tests")));
        ensure!(plan(&["apps/android/new-owner/module.yaml"]).is_err());
        Ok(())
    }

    #[test]
    fn generated_prerequisites_declare_reviewable_outputs() -> Result<()> {
        let app = plan(&["apps/android/app/src/Screen.kt"])?;
        for id in ["bindings", "kotlin-rules", "kotlin-analysis-input"] {
            let task = app
                .tasks
                .iter()
                .find(|task| task.id == id)
                .context("generated task")?;
            ensure!(
                !task.outputs.is_empty(),
                "{id} must declare its generated output"
            );
        }
        Ok(())
    }

    #[test]
    fn the_check_gate_regenerates_the_android_baseline_profile() -> Result<()> {
        // The committed profile is a release-packaging input. Only the handoff gate regenerates it,
        // and it must do so from the same shared build the app classes were compiled into, so a
        // profile that no rule can produce is a failure rather than a satisfied "file exists".
        let app = check_plan(&["apps/android/app/src/feature/main/MainViewModel.kt"])?;
        let task = app
            .tasks
            .iter()
            .find(|task| task.id == "baseline-profile")
            .context("baseline profile task")?;
        ensure!(
            task.outputs.contains(&Artifact::BaselineProfile),
            "the regenerated profile must be a declared output"
        );
        ensure!(
            task.dependencies.contains("kotlin-tests:app"),
            "regeneration reads the compiled app classes"
        );
        ensure!(
            task.inputs
                .iter()
                .any(|path| path == Path::new("apps/android/app")),
            "the baseline rules and static profile are task inputs"
        );
        let tui = check_plan(&["apps/tui/src/view.rs"])?;
        ensure!(
            !ids(&tui).contains("baseline-profile"),
            "a host-only change must not regenerate the Android profile"
        );
        Ok(())
    }

    #[test]
    fn git_inventory_includes_unstaged_untracked_deleted_and_rename_endpoints() -> Result<()> {
        let root = tempfile::tempdir()?;
        let git = |args: &[&str]| -> Result<()> {
            let output = Command::new("git")
                .current_dir(root.path())
                .args(args)
                .output()?;
            ensure!(
                output.status.success(),
                "{}",
                String::from_utf8_lossy(&output.stderr)
            );
            Ok(())
        };
        git(&["init", "-q"])?;
        let old = "旧 路径.kt";
        std::fs::write(root.path().join(old), "old")?;
        std::fs::write(root.path().join("removed.rs"), "remove")?;
        git(&["add", "."])?;
        git(&[
            "-c",
            "user.name=fixture",
            "-c",
            "user.email=fixture@example.invalid",
            "-c",
            "core.hooksPath=/dev/null",
            "commit",
            "-qm",
            "fixture",
        ])?;
        std::fs::rename(root.path().join(old), root.path().join("新\n路径.kt"))?;
        std::fs::remove_file(root.path().join("removed.rs"))?;
        std::fs::write(root.path().join("untracked.kt"), "new")?;
        let changes = ChangeInventory::read(root.path(), &ChangeSource::Worktree)?;
        for expected in [old, "新\n路径.kt", "removed.rs", "untracked.kt"] {
            ensure!(
                changes.paths.contains(&PathBuf::from(expected)),
                "missing {expected}"
            );
        }
        ensure!(
            changes.removed == BTreeSet::from([PathBuf::from(old), PathBuf::from("removed.rs")]),
            "removed must hold exactly the rename source and the deletion: {:?}",
            changes.removed
        );
        Ok(())
    }

    #[test]
    fn unified_diff_follows_the_same_scope_as_the_inventory() -> Result<()> {
        let root = tempfile::tempdir()?;
        let git = |args: &[&str]| -> Result<()> {
            let output = Command::new("git")
                .current_dir(root.path())
                .args(args)
                .output()?;
            ensure!(
                output.status.success(),
                "{}",
                String::from_utf8_lossy(&output.stderr)
            );
            Ok(())
        };
        git(&["init", "-q"])?;
        std::fs::write(root.path().join("tracked.rs"), "fn old() {}\n")?;
        git(&["add", "."])?;
        git(&[
            "-c",
            "user.name=fixture",
            "-c",
            "user.email=fixture@example.invalid",
            "-c",
            "core.hooksPath=/dev/null",
            "commit",
            "-qm",
            "fixture",
        ])?;
        std::fs::write(root.path().join("tracked.rs"), "fn new() {}\n")?;
        std::fs::write(root.path().join("untracked.rs"), "fn fresh() {}\n")?;

        let worktree = unified_diff(root.path(), &ChangeSource::Worktree)?
            .context("a modified tracked file must produce a diff")?;
        ensure!(
            worktree.contains("tracked.rs") && worktree.contains("fn new"),
            "the diff must name the touched file and hunk: {worktree}"
        );
        ensure!(
            unified_diff(root.path(), &ChangeSource::All)?.is_none(),
            "a complete inventory has no diff scope"
        );
        ensure!(
            unified_diff(
                root.path(),
                &ChangeSource::Push {
                    remote: "missing-remote".to_owned()
                }
            )?
            .is_none(),
            "a missing remote base means the inventory is complete and no diff exists"
        );
        Ok(())
    }

    #[test]
    fn a_renamed_manifest_never_fails_closed_on_its_removed_endpoint() -> Result<()> {
        // A manifest rename reports both endpoints. The source endpoint no longer exists, so it
        // cannot be an unowned *new* module; only the surviving path may be rejected.
        let renamed = plan_with_removed(
            &["app/module.yaml", "apps/android/app/module.yaml"],
            &["app/module.yaml"],
        )?;
        ensure!(
            ids(&renamed).contains("kotlin-tests:app"),
            "the surviving manifest path must still schedule its owner"
        );
        ensure!(
            plan(&["app/module.yaml"]).is_err(),
            "an unowned manifest that still exists must fail closed"
        );
        Ok(())
    }

    struct FakeRunner {
        calls: Mutex<Vec<String>>,
        outcomes: BTreeMap<String, TaskOutcome>,
        missing_output: bool,
        change_inputs: bool,
    }

    impl CommandRunner for FakeRunner {
        fn input_digest(&self, _task: &Task) -> Result<String> {
            let changed = self.change_inputs
                && !self
                    .calls
                    .lock()
                    .map_err(|error| anyhow::anyhow!(error.to_string()))?
                    .is_empty();
            Ok(if changed { "changed" } else { "initial" }.to_owned())
        }
        fn run(&self, task: &Task) -> Result<TaskOutcome> {
            self.calls
                .lock()
                .map_err(|error| anyhow::anyhow!(error.to_string()))?
                .push(task.id.clone());
            Ok(self
                .outcomes
                .get(&task.id)
                .cloned()
                .unwrap_or(TaskOutcome::Success { tests: Some(1) }))
        }
        fn verify_outputs(&self, _task: &Task) -> Result<()> {
            ensure!(!self.missing_output, "declared output missing");
            Ok(())
        }
    }

    fn two_tasks() -> VerificationPlan {
        let task = |id: &str, dependencies: &[&str]| Task {
            id: id.to_owned(),
            action: TaskAction::RustTests {
                package: "fixture".to_owned(),
            },
            dependencies: dependencies
                .iter()
                .map(|value| (*value).to_owned())
                .collect(),
            inputs: BTreeSet::new(),
            outputs: BTreeSet::new(),
            tools: BTreeSet::new(),
            resources: BTreeSet::new(),
            reason: "fixture".to_owned(),
        };
        VerificationPlan {
            scope: None,
            complete_worktree: true,
            excluded_owners: BTreeSet::new(),
            tasks: vec![task("first", &[]), task("second", &["first"])],
        }
    }

    #[test]
    fn failure_cancellation_empty_tests_and_missing_outputs_cannot_pass() -> Result<()> {
        for (outcome, expected) in [
            (
                TaskOutcome::Failure("failed".to_owned()),
                TaskStatus::Failed,
            ),
            (
                TaskOutcome::Cancelled("terminated".to_owned()),
                TaskStatus::Cancelled,
            ),
            (TaskOutcome::Success { tests: Some(0) }, TaskStatus::Failed),
            (TaskOutcome::Success { tests: None }, TaskStatus::Failed),
        ] {
            let runner = FakeRunner {
                calls: Mutex::new(Vec::new()),
                outcomes: BTreeMap::from([("first".to_owned(), outcome)]),
                missing_output: false,
                change_inputs: false,
            };
            let reports = execute(&two_tasks(), &runner)?;
            ensure!(reports.first().context("first report")?.status == expected);
            ensure!(reports.last().context("dependent report")?.status == TaskStatus::NotScheduled);
        }
        let runner = FakeRunner {
            calls: Mutex::new(Vec::new()),
            outcomes: BTreeMap::new(),
            missing_output: true,
            change_inputs: false,
        };
        ensure!(
            execute(&two_tasks(), &runner)?
                .first()
                .context("report")?
                .status
                == TaskStatus::Failed
        );
        Ok(())
    }

    #[test]
    fn successful_prerequisites_run_once_in_order_and_source_changes_invalidate_evidence()
    -> Result<()> {
        let runner = FakeRunner {
            calls: Mutex::new(Vec::new()),
            outcomes: BTreeMap::new(),
            missing_output: false,
            change_inputs: false,
        };
        ensure!(
            execute(&two_tasks(), &runner)?
                .iter()
                .all(|report| report.status == TaskStatus::Passed)
        );
        ensure!(
            *runner
                .calls
                .lock()
                .map_err(|error| anyhow::anyhow!(error.to_string()))?
                == ["first", "second"]
        );
        let runner = FakeRunner {
            calls: Mutex::new(Vec::new()),
            outcomes: BTreeMap::new(),
            missing_output: false,
            change_inputs: true,
        };
        ensure!(
            execute(&two_tasks(), &runner)?
                .first()
                .context("report")?
                .status
                == TaskStatus::Failed
        );
        Ok(())
    }
}
