//! Test Change Justification:
//! Reason category: explicitly requested quality contract change.
//! Old behavior/assertion being replaced: touched Rust crates require diff-scoped mutants.
//! Why old assertion is no longer correct: Rust mutation testing is removed from the workflow.
//! Coverage preserved by: all-mode plans still require package tests and applicable Clippy.
//! Why this is not fitting the test to the implementation: the user requested removal;
//! the new planning regression is observed RED before implementation.
//! TDD proof: `cargo test -p lomo-xtask --test verification_plan_contract --locked
//! rust_changes_keep_standard_checks_without_mutation_tasks`; RED contains `rust-mutants:lomo-store`.
//!
//! Test Change Justification:
//! Reason category: measured profile packaging replaces static class enumeration.
//! Old behavior/assertion being replaced: require every runtime method in a regenerated profile.
//! Why old assertion is no longer correct: device-collected hot paths are intentional subsets;
//! expanding them changes the workload evidence instead of validating the measured input.
//! Coverage preserved by: official SDK parsing, collection receipt hash checks, unchanged gate
//! scheduling/output checks and `measured_profiles_contract` rejection/round-trip tests.
//! Why this is not fitting the test to the implementation: the existing --write workflow could
//! never satisfy static byte equality; the new tests reject tampering and parser errors while
//! accepting legitimate measured subsets from the supported collector.
//!
//! Behavior Contract:
//! Capability: select the affected owner closure and execute a verification DAG honestly;
//! owning layer: lomo-xtask; priority: P0.
//! Scenarios:
//! - Given Rust changes, when any verification mode is planned, then normal tests remain
//!   and no mutation task or tool is required.
//! - Given TUI/app/domain/FFI/golden/deleted sources, when planning, then the matching owners run.
//! - Given a moved Unicode path, when reading the worktree, then both endpoints retain ownership.
//! - Given failed/cancelled/empty tests or missing outputs, when executing, then no pass is cached.
//! - Given source changes during a run, when finishing, then earlier evidence is invalidated.
//! - Given runtime-only, compile-only and test module edges, when resolving profile inputs,
//!   then runtime-only is included and compile-only/test edges are excluded.
//! - Given an app resource change, when Android Lint is scheduled, then analysis inputs cover
//!   every packaged product module lint analyzes (data included), not only the changed owner.
//!
//!
//! Observable outcomes: task sets, dependency ordering, declared outputs and result states.
//! TDD proof: `verification_cli_contract` RED rejected dev; this matrix additionally observes RED
//! for missing declared generation outputs before completing the scheduler.
//! Excludes: performance timing, compiler implementation and Android device execution.

#[cfg(test)]
mod tests {
    use anyhow::{Context, Result, ensure};
    use lomo_xtask::verification::{
        Artifact, ChangeInventory, ChangeSource, CommandRunner, ImpactGraph, PlanMode,
        RequiredTool, Task, TaskAction, TaskOutcome, TaskStatus, VerificationPlan, execute,
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

    #[test]
    fn runtime_profile_closure_includes_runtime_modules_and_excludes_test_edges() -> Result<()> {
        let graph = graph()?;
        let root = tempfile::tempdir()?;
        for (module, manifest) in [
            (
                "app",
                "dependencies:\n  - //data: runtime-only\n  - //ui-components: compile-only\ntest-dependencies:\n  - //ui-components\n",
            ),
            ("data", "dependencies:\n  - //domain\n"),
            ("domain", "dependencies: []\n"),
        ] {
            let owner = graph.owners.get(module).context("module owner")?;
            let directory = root.path().join(&owner.path);
            std::fs::create_dir_all(&directory)?;
            std::fs::write(directory.join("module.yaml"), manifest)?;
        }
        ensure!(
            graph.kotlin_runtime_closure(root.path(), "app")?
                == ["app", "data", "domain"]
                    .map(str::to_owned)
                    .into_iter()
                    .collect()
        );
        ensure!(
            graph
                .kotlin_runtime_closure(root.path(), "unknown")
                .is_err()
        );
        Ok(())
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
    fn android_lint_plans_analysis_inputs_for_every_packaged_product_module() -> Result<()> {
        let plan = plan(&["apps/android/app/res/values/strings.xml"])?;
        ensure!(ids(&plan).contains("android-lint"));
        let analysis = plan
            .tasks
            .iter()
            .find_map(|task| {
                if let TaskAction::AnalysisInput { modules } = &task.action {
                    Some(modules)
                } else {
                    None
                }
            })
            .context("analysis input task")?;
        for module in ["app", "data", "domain", "ui-components"] {
            ensure!(
                analysis.contains(module),
                "lint analysis inputs miss {module}: {analysis:?}"
            );
        }
        ensure!(!analysis.contains("native-bindings") && !analysis.contains("detekt-rules"));
        Ok(())
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
    fn rust_changes_keep_standard_checks_without_mutation_tasks() -> Result<()> {
        for mode in [PlanMode::Dev, PlanMode::Tests, PlanMode::Check] {
            let planned = VerificationPlan::build(
                &graph()?,
                &ChangeInventory {
                    paths: [PathBuf::from("crates/lomo-store/src/cursor.rs")].into(),
                    removed: BTreeSet::new(),
                    complete: false,
                },
                None,
                mode,
            )?;
            ensure!(ids(&planned).contains("rust-tests:lomo-store"));
            if mode != PlanMode::Tests {
                ensure!(ids(&planned).contains("rust-clippy:lomo-store"));
            }
            ensure!(
                !planned.tasks.iter().any(|task| {
                    task.id.contains("mutants")
                        || task
                            .tools
                            .iter()
                            .any(|tool| format!("{tool:?}") == "Mutants")
                }),
                "standard verification must not require mutation tooling: {:?}",
                ids(&planned)
            );
        }
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
    fn the_check_gate_validates_measured_android_profiles() -> Result<()> {
        // The packaging gate validates the measured subset and its receipt after app tests.
        let app = check_plan(&["apps/android/app/src/feature/main/MainViewModel.kt"])?;
        let task = app
            .tasks
            .iter()
            .find(|task| task.id == "baseline-profile")
            .context("baseline profile task")?;
        ensure!(
            task.outputs.contains(&Artifact::BaselineProfile),
            "the measured profile must be a declared output"
        );
        ensure!(
            task.dependencies.contains("kotlin-tests:app"),
            "profile validation follows the app checks"
        );
        ensure!(
            task.inputs
                .iter()
                .any(|path| path == Path::new("apps/android/app")),
            "the measured profiles and receipt are task inputs"
        );
        ensure!(
            task.tools.contains(&RequiredTool::AndroidSdk),
            "profile grammar is owned by SDK profgen"
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
