// adversarial-audit: gate-wiring and scheduler adversarial probes.
// The semantic canary `detekt_full_analysis_contract_test.sh` must be reachable from a
// gate; usecase-reachability must require calls not mentions, and the executor must refuse
// zero/unreported test counts, propagate `NotScheduled`, and detect mid-run input drift.
// A RED result documents a live bypass shape; GREEN results lock the M6 contract.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "adversarial fixtures pin exact shapes and fail closed"
)]
mod tests {
    use std::collections::{BTreeMap, BTreeSet};
    use std::fs;
    use std::path::{Path, PathBuf};
    use std::sync::Mutex;

    use anyhow::Result;
    use lomo_xtask::verification::{
        ChangeInventory, CommandRunner, ImpactGraph, Owner, OwnerKind, PlanMode, RequiredTool,
        Resource, Task, TaskAction, TaskOutcome, TaskStatus, VerificationPlan, execute,
        parse_test_count,
    };

    fn root() -> PathBuf {
        Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../..")
            .canonicalize()
            .expect("repository root")
    }

    fn read(path: &str) -> String {
        fs::read_to_string(root().join(path)).unwrap_or_else(|e| panic!("{path}: {e}"))
    }

    fn fixture(files: &[(&str, &str)]) -> tempfile::TempDir {
        let dir = tempfile::tempdir().expect("fixture dir");
        for (path, content) in files {
            let absolute = dir.path().join(path);
            fs::create_dir_all(absolute.parent().expect("fixture parent")).expect("fixture mkdir");
            fs::write(&absolute, content).expect("fixture file");
        }
        dir
    }

    fn kotlin_owner(name: &str, deps: &[&str]) -> (String, Owner) {
        (
            name.to_owned(),
            Owner {
                name: name.to_owned(),
                path: PathBuf::from(format!("apps/android/{name}")),
                kind: OwnerKind::Kotlin,
                dependencies: deps.iter().map(ToString::to_string).collect(),
            },
        )
    }

    fn synthetic_graph() -> ImpactGraph {
        let owners: BTreeMap<_, _> = [
            kotlin_owner("domain", &[]),
            kotlin_owner("data", &["domain"]),
            kotlin_owner("ui-components", &["domain"]),
            kotlin_owner("app", &["domain", "data", "ui-components"]),
            kotlin_owner("native-bindings", &[]),
            kotlin_owner("detekt-rules", &[]),
            (
                "lomo-native".to_owned(),
                Owner {
                    name: "lomo-native".to_owned(),
                    path: PathBuf::from("crates/lomo-native"),
                    kind: OwnerKind::Rust,
                    dependencies: BTreeSet::new(),
                },
            ),
        ]
        .into_iter()
        .collect();
        ImpactGraph { owners }
    }

    fn complete() -> ChangeInventory {
        ChangeInventory {
            paths: BTreeSet::new(),
            removed: BTreeSet::new(),
            complete: true,
        }
    }

    fn task<'a>(plan: &'a VerificationPlan, id: &str) -> &'a Task {
        plan.tasks
            .iter()
            .find(|task| task.id == id)
            .unwrap_or_else(|| panic!("task {id} must be scheduled"))
    }

    // -----------------------------------------------------------------------
    // usecase-reachability: token-mention scanner, not a call graph
    // -----------------------------------------------------------------------

    /// `contains_identifier_usage` matches any bare identifier occurrence. A `::class` literal
    /// (or a type annotation, typealias, generic argument — any mention) in a non-DI production
    /// file marks the usecase reachable without any invocation.
    #[test]
    fn dead_class_literal_mention_must_not_satisfy_reachability() {
        let dir = fixture(&[
            (
                "apps/android/domain/src/usecase/TraceMemoUseCase.kt",
                "package com.lomo.domain.usecase\nclass TraceMemoUseCase { operator fun invoke() = 1 }\n",
            ),
            (
                "apps/android/app/src/DeadReference.kt",
                "package com.lomo.app\nval deadReference = com.lomo.domain.usecase.TraceMemoUseCase::class\n",
            ),
        ]);
        let result = lomo_xtask::check_usecase_reachability(dir.path());
        assert!(
            result.is_err(),
            "BLIND SPOT: a `::class` mention counts as a production consumer; the check never \
             distinguishes call sites from type/literal references"
        );
    }

    /// Kotlin block comments nest; `skip_block_comment` does not track depth, so the text
    /// between an inner `*/` and the outer `*/` leaks into the stripped source and can fake a
    /// consumer.
    #[test]
    fn identifier_smuggled_through_nested_block_comment_must_not_count() {
        let dir = fixture(&[
            (
                "apps/android/domain/src/usecase/GhostUseCase.kt",
                "package com.lomo.domain.usecase\nclass GhostUseCase { operator fun invoke() = 1 }\n",
            ),
            (
                // In real Kotlin this whole line is ONE nested comment; the scanner's
                // first-`*/` termination leaves `GhostUseCase` visible as a fake consumer.
                "apps/android/app/src/Smuggled.kt",
                "package com.lomo.app\n/* /* */ GhostUseCase /* */\n",
            ),
        ]);
        let result = lomo_xtask::check_usecase_reachability(dir.path());
        assert!(
            result.is_err(),
            "BLIND SPOT: nested block comments leak identifiers into the scan; a fully \
             commented mention counts as a production consumer"
        );
    }

    /// `collect_usecases` reads only the TOP level of `domain/src/usecase`; a usecase declared
    /// in a subdirectory (or anywhere outside that directory) is never registered, so its dead
    /// code is invisible to the gate entirely.
    #[test]
    fn usecase_in_subdirectory_must_not_escape_the_scan() {
        let dir = fixture(&[
            (
                "apps/android/domain/src/usecase/RealUseCase.kt",
                "package com.lomo.domain.usecase\nclass RealUseCase { operator fun invoke() = 1 }\n",
            ),
            (
                "apps/android/domain/src/usecase/nested/GhostUseCase.kt",
                "package com.lomo.domain.usecase.nested\nclass GhostUseCase { operator fun invoke() = 1 }\n",
            ),
            (
                "apps/android/app/src/Consumer.kt",
                "package com.lomo.app\nfun f() = com.lomo.domain.usecase.RealUseCase()\n",
            ),
        ]);
        let result = lomo_xtask::check_usecase_reachability(dir.path());
        assert!(
            result.is_err(),
            "BLIND SPOT: `usecase/nested/GhostUseCase.kt` is never collected — subdirectory \
             declarations silently evade the zero-consumer check"
        );
    }

    /// A `*UseCase` class declared outside `domain/src/usecase` is likewise invisible: the decl
    /// scan is directory-scoped, so dead domain code placed elsewhere has no obligation.
    #[test]
    fn usecase_outside_usecase_dir_must_not_escape_the_scan() {
        let dir = fixture(&[
            (
                "apps/android/domain/src/usecase/RealUseCase.kt",
                "package com.lomo.domain.usecase\nclass RealUseCase { operator fun invoke() = 1 }\n",
            ),
            (
                "apps/android/domain/src/internal/StrayUseCase.kt",
                "package com.lomo.domain.internal\nclass StrayUseCase { operator fun invoke() = 1 }\n",
            ),
            (
                "apps/android/app/src/Consumer.kt",
                "package com.lomo.app\nfun f() = com.lomo.domain.usecase.RealUseCase()\n",
            ),
        ]);
        let result = lomo_xtask::check_usecase_reachability(dir.path());
        assert!(
            result.is_err(),
            "BLIND SPOT: a dead `*UseCase` outside `domain/src/usecase` is never collected"
        );
    }

    /// Control: the DI/`test`/`androidTest`/`*Module.kt` exclusions do hold — a usecase
    /// referenced only from a DI module is correctly reported unreachable.
    #[test]
    fn di_only_reference_is_correctly_rejected() {
        let dir = fixture(&[
            (
                "apps/android/domain/src/usecase/OrphanUseCase.kt",
                "package com.lomo.domain.usecase\nclass OrphanUseCase { operator fun invoke() = 1 }\n",
            ),
            (
                "apps/android/data/src/di/WiringModule.kt",
                "package com.lomo.data.di\nimport org.koin.dsl.module\nval wiringModule = module { single { com.lomo.domain.usecase.OrphanUseCase() } }\n",
            ),
        ]);
        let result = lomo_xtask::check_usecase_reachability(dir.path());
        assert!(
            result.is_err(),
            "the documented exclusion must hold: DI registration alone is not a consumer"
        );
    }

    // -----------------------------------------------------------------------
    // DAG wiring: required predecessor edges cannot be skipped
    // -----------------------------------------------------------------------

    #[test]
    fn ffi_contract_is_structurally_behind_kotlin_full_and_bindings_feeds_analysis() {
        let plan = VerificationPlan::build(&synthetic_graph(), &complete(), None, PlanMode::Check)
            .expect("complete check plan builds");
        let ffi = task(&plan, "ffi-contract");
        assert!(
            ffi.dependencies.contains("kotlin-full"),
            "ffi-contract must wait for resolved symbol facts"
        );
        let full = task(&plan, "kotlin-full");
        for required in ["kotlin-light", "kotlin-analysis-input"] {
            assert!(
                full.dependencies.contains(required),
                "kotlin-full must wait for {required}"
            );
        }
        let analysis = task(&plan, "kotlin-analysis-input");
        assert!(
            analysis.dependencies.contains("bindings"),
            "analysis input must wait for generated bindings"
        );
        let baseline = task(&plan, "baseline-profile");
        assert!(
            baseline.dependencies.contains("kotlin-tests:app"),
            "baseline regeneration must wait for the compiled app"
        );
    }

    /// `usecase-reachability` and `shell-contracts` carry no `.after` edges — they are pure
    /// source scans whose inputs cover the whole worktree, so they legitimately run in wave 1.
    /// The lock here is that both are present in every non-Tests plan touching product modules.
    #[test]
    fn independent_evidence_nodes_are_present_not_silently_dropped() {
        for mode in [PlanMode::Check, PlanMode::Dev] {
            let plan = VerificationPlan::build(&synthetic_graph(), &complete(), None, mode)
                .expect("plan builds");
            for id in ["usecase-reachability", "shell-contracts"] {
                task(&plan, id);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Executor: zero/unreported tests, cancellation and input drift must fail closed
    // -----------------------------------------------------------------------

    struct StubRunner {
        outcome: TaskOutcome,
    }

    impl StubRunner {
        fn new(outcome: TaskOutcome) -> Self {
            Self { outcome }
        }
    }

    impl CommandRunner for StubRunner {
        fn input_digest(&self, _task: &Task) -> Result<String> {
            Ok("digest-0".to_owned())
        }

        fn run(&self, _task: &Task) -> Result<TaskOutcome> {
            Ok(self.outcome.clone())
        }

        fn verify_outputs(&self, _task: &Task) -> Result<()> {
            Ok(())
        }
    }

    /// Runner whose input digest mutates between the scheduling snapshot and task start — the
    /// executor must fail the task instead of running against stale inputs.
    struct DriftRunner {
        calls: Mutex<u64>,
    }

    impl CommandRunner for DriftRunner {
        fn input_digest(&self, _task: &Task) -> Result<String> {
            let next = {
                let mut calls = self.calls.lock().expect("digest counter");
                *calls += 1;
                *calls
            };
            Ok(format!("digest-{next}"))
        }

        fn run(&self, _task: &Task) -> Result<TaskOutcome> {
            Ok(TaskOutcome::Success { tests: Some(1) })
        }

        fn verify_outputs(&self, _task: &Task) -> Result<()> {
            Ok(())
        }
    }

    fn bare_task(id: &str, action: TaskAction, deps: &[&str]) -> Task {
        Task {
            id: id.to_owned(),
            action,
            dependencies: deps.iter().map(ToString::to_string).collect(),
            inputs: BTreeSet::from([PathBuf::from("crates/lomo-xtask")]),
            outputs: BTreeSet::new(),
            tools: BTreeSet::from([RequiredTool::Rust]),
            resources: BTreeSet::from([Resource::Cargo]),
            reason: "adversarial probe".to_owned(),
        }
    }

    fn plan_with(tasks: Vec<Task>) -> VerificationPlan {
        VerificationPlan {
            scope: None,
            complete_worktree: true,
            excluded_owners: BTreeSet::new(),
            tasks,
        }
    }

    /// M6: a green exit status is not enough — a test-bearing task reporting zero executed
    /// tests (or no recognizable summary at all) must not pass, and dependents must go
    /// `NotScheduled`.
    #[test]
    fn zero_or_unreported_test_counts_cannot_pass_test_tasks() {
        for outcome in [
            TaskOutcome::Success { tests: Some(0) },
            TaskOutcome::Success { tests: None },
        ] {
            for (id, action) in [
                (
                    "rust-tests:lomo-core",
                    TaskAction::RustTests {
                        package: "lomo-core".to_owned(),
                    },
                ),
                (
                    "kotlin-tests:app",
                    TaskAction::KotlinTests {
                        module: "app".to_owned(),
                    },
                ),
                ("ffi-contract", TaskAction::FfiContract),
                ("architecture", TaskAction::Architecture),
            ] {
                let dependent = bare_task("downstream", TaskAction::ShellContracts, &[id]);
                let plan = plan_with(vec![bare_task(id, action, &[]), dependent]);
                let reports = execute(&plan, &StubRunner::new(outcome.clone())).expect("execute");
                let failing = reports
                    .iter()
                    .find(|report| report.id == id)
                    .expect("report");
                assert_eq!(
                    failing.status,
                    TaskStatus::Failed,
                    "{id} must fail on {outcome:?}"
                );
                let downstream = reports
                    .iter()
                    .find(|report| report.id == "downstream")
                    .expect("downstream report");
                assert_eq!(
                    downstream.status,
                    TaskStatus::NotScheduled,
                    "dependent of a failed gate must never run or pass"
                );
            }
        }
    }

    /// Non-test actions legitimately report no test count; the guard must not reject them.
    #[test]
    fn non_test_actions_are_not_required_to_report_tests() {
        let plan = plan_with(vec![bare_task(
            "shell-contracts",
            TaskAction::ShellContracts,
            &[],
        )]);
        let reports = execute(
            &plan,
            &StubRunner::new(TaskOutcome::Success { tests: None }),
        )
        .expect("execute");
        assert_eq!(
            reports.first().map(|report| &report.status),
            Some(&TaskStatus::Passed)
        );
    }

    /// A task whose runner is interrupted reports `Cancelled`; dependents become `NotScheduled`
    /// and the plan as a whole cannot report success — `NotScheduled` never counts as passing.
    #[test]
    fn cancelled_prerequisite_marks_dependents_not_scheduled() {
        let plan = plan_with(vec![
            bare_task(
                "rust-tests:lomo-core",
                TaskAction::RustTests {
                    package: "lomo-core".to_owned(),
                },
                &[],
            ),
            bare_task(
                "kotlin-full",
                TaskAction::KotlinFull {
                    modules: BTreeSet::new(),
                },
                &["rust-tests:lomo-core"],
            ),
        ]);
        let reports = execute(
            &plan,
            &StubRunner::new(TaskOutcome::Cancelled("interrupted".to_owned())),
        )
        .expect("execute");
        let status_of = |id: &str| {
            reports
                .iter()
                .find(|report| report.id == id)
                .map(|report| &report.status)
        };
        assert_eq!(
            status_of("rust-tests:lomo-core"),
            Some(&TaskStatus::Cancelled)
        );
        assert_eq!(status_of("kotlin-full"), Some(&TaskStatus::NotScheduled));
        assert!(
            !reports
                .iter()
                .all(|report| report.status == TaskStatus::Passed),
            "a cancelled wave must not collapse into a pass"
        );
    }

    /// An input change between the plan snapshot and task start must fail the task closed.
    #[test]
    fn input_drift_between_snapshot_and_start_fails_the_task() {
        let plan = plan_with(vec![bare_task(
            "rust-tests:lomo-core",
            TaskAction::RustTests {
                package: "lomo-core".to_owned(),
            },
            &[],
        )]);
        let reports = execute(
            &plan,
            &DriftRunner {
                calls: Mutex::new(0),
            },
        )
        .expect("execute");
        assert_eq!(
            reports.first().map(|report| &report.status),
            Some(&TaskStatus::Failed),
            "a drifting input digest must fail the task, not run it"
        );
    }

    /// `parse_test_count` trusts ANY matching summary line in the whole log — including the
    /// command description itself. Documented asymmetry: the executor only uses it to prove
    /// tests ran (`Some > 0`), never to cross-check per-platform totals, so a fabricating line
    /// cannot rescue a suite that ran nothing — but nothing stops a mixed log from summing a
    /// zero-platform away.
    #[test]
    fn summary_lines_are_summed_without_provenance_checks() {
        assert_eq!(
            parse_test_count("[ 0 tests successful ]\n[ 735 tests successful ]\n").expect("parse"),
            Some(735),
            "one platform reporting zero while another reports real tests sums to a pass; \
             there is no per-platform floor"
        );
        assert_eq!(
            parse_test_count("noise\n").expect("parse"),
            None,
            "no summary -> unreported -> rejected by the zero-test guard"
        );
    }

    // -----------------------------------------------------------------------
    // Baseline profile: regeneration is scheduled and the committed artifact must be
    // byte-compared against the regenerated evidence — existence alone must not pass.
    // -----------------------------------------------------------------------

    #[test]
    fn regenerated_baseline_is_compared_to_the_committed_profile() {
        let source = read("crates/lomo-xtask/src/verification/runner.rs");
        assert!(
            source.contains("report_dir.join(\"baseline-profile\")"),
            "generator must write into the per-run evidence dir"
        );
        assert!(
            source.contains("baselineProfiles/generated.txt"),
            "verify_outputs maps the artifact to the committed file"
        );
        // "fn verify_outputs" appears twice: the CommandRunner trait declaration and the
        // RepositoryRunner implementation — nth(2) selects the implementation body.
        let verify = source
            .split("fn verify_outputs")
            .nth(2)
            .expect("verify_outputs implementation exists");
        let verify_body = verify.split("\n    }\n").next().expect("body");
        assert!(
            verify_body.contains("baseline-profile"),
            "verify_outputs must reach the regenerated evidence copy, otherwise committed \
             generated.txt only needs to exist and be non-empty — staleness and \
             hand-edited content are undetectable by this node"
        );
        assert!(
            verify_body.contains("stale") && verify_body.contains("=="),
            "verify_outputs must fail closed with an explicit stale/hand-edited error when \
             the committed profile differs byte-for-byte from the regenerated evidence"
        );
    }

    // -----------------------------------------------------------------------
    // Wiring: the full-mode semantic canary must be reachable from some gate
    // -----------------------------------------------------------------------

    /// `detekt_full_analysis_contract_test.sh` is the only artifact that proves type-resolved
    /// rules actually fire (`NoInferredMutableFlowExposure`/`ForbiddenMethodCall` in full mode
    /// but not light). `kotlin_detekt_check.sh` mode dispatch runs only the activation contract
    /// (light) and the analysis-input contract (full); the canary is invoked nowhere.
    #[test]
    fn full_mode_semantic_canary_must_be_wired_into_a_gate() {
        let wired_in = [
            "quality/scripts/kotlin_detekt_check.sh",
            "quality/scripts/kotlin_analysis_input.sh",
            "quality/scripts/test/kotlin_quality_check_contract_test.sh",
            "quality/scripts/test/detekt_activation_contract_test.sh",
            "crates/lomo-xtask/src/quality.rs",
            "crates/lomo-xtask/src/verification/runner.rs",
            "Justfile",
        ]
        .iter()
        .filter(|path| read(path).contains("detekt_full_analysis_contract_test"))
        .count();
        assert_ne!(
            wired_in, 0,
            "BLIND SPOT: the full-vs-light semantic canary is an orphan — nothing in any gate \
             re-proves that type-resolved analysis actually produces findings"
        );
    }

    // ------------------------------------------------------------------
    // DAG coverage and scheduling.
    // ------------------------------------------------------------------

    fn graph_with(owners: Vec<(String, Owner)>) -> ImpactGraph {
        ImpactGraph {
            owners: owners.into_iter().collect::<BTreeMap<_, _>>(),
        }
    }

    /// A new Amper product module (registered in project.yaml but unknown to
    /// `kotlin_detekt_check.sh`'s `module_config` table) must propagate into the detekt
    /// module list — the script then rejects it with "unknown module", failing closed
    /// instead of silently skipping its analysis.
    #[test]
    fn a_new_product_module_must_reach_the_detekt_module_list() {
        let mut owners = vec![
            kotlin_owner("domain", &[]),
            kotlin_owner("data", &["domain"]),
            kotlin_owner("ui-components", &["domain"]),
            kotlin_owner("app", &["domain", "data", "ui-components"]),
            kotlin_owner("feature-new", &["domain"]),
            kotlin_owner("native-bindings", &[]),
            kotlin_owner("detekt-rules", &[]),
        ];
        owners.push((
            "lomo-native".to_owned(),
            Owner {
                name: "lomo-native".to_owned(),
                path: PathBuf::from("crates/lomo-native"),
                kind: OwnerKind::Rust,
                dependencies: BTreeSet::new(),
            },
        ));
        let plan = VerificationPlan::build(&graph_with(owners), &complete(), None, PlanMode::Check)
            .expect("plan builds");
        let full = task(&plan, "kotlin-full");
        let TaskAction::KotlinFull { modules } = &full.action else {
            panic!("unexpected action {:?}", full.action);
        };
        assert!(
            modules.contains("feature-new"),
            "a registered product module must be handed to the detekt gate; the gate \
             script fails closed on modules it does not know"
        );
    }

    /// `ffi-contract` exists only when all four product modules are in scope — a scoped
    /// `just dev`/`_preflight` over a Kotlin-only diff schedules no FFI check at all.
    /// This locks the documented boundary: the FFI gate is a `check`-surface property,
    /// and scoped iteration can push a broken foreign surface.
    #[test]
    fn scoped_plans_carry_no_ffi_contract() {
        let kotlin_only = graph_with(vec![kotlin_owner("app", &[])]);
        let changes = ChangeInventory {
            paths: BTreeSet::from([PathBuf::from("apps/android/app/src/Screen.kt")]),
            removed: BTreeSet::new(),
            complete: false,
        };
        let plan = VerificationPlan::build(&kotlin_only, &changes, None, PlanMode::Dev)
            .expect("plan builds");
        assert!(
            plan.tasks.iter().all(|task| task.id != "ffi-contract"),
            "ffi-contract cannot run without all four modules' symbol facts — this is a \
             documented scoped-gate gap, not a pass"
        );
        // The complete check plan still carries it.
        let full = VerificationPlan::build(&synthetic_graph(), &complete(), None, PlanMode::Check)
            .expect("full plan builds");
        task(&full, "ffi-contract");
    }

    /// Green lock: an unowned executable manifest (new module that never registered with
    /// `project.yaml`/owners) is rejected instead of silently escaping every owner scan.
    #[test]
    fn an_unowned_module_manifest_fails_closed() {
        let changes = ChangeInventory {
            paths: BTreeSet::from([PathBuf::from("apps/android/rogue/module.yaml")]),
            removed: BTreeSet::new(),
            complete: false,
        };
        assert!(
            synthetic_graph().affected(&changes).is_err(),
            "unowned executable manifests must fail closed"
        );
    }

    /// Green lock: the final input-digest sweep must catch a task whose inputs moved
    /// *after* its own run/verify completed — pre-start drift is not the only check.
    #[test]
    fn post_run_input_drift_fails_a_passed_task() {
        struct PostDriftRunner {
            calls: Mutex<u64>,
        }
        impl CommandRunner for PostDriftRunner {
            fn input_digest(&self, _task: &Task) -> Result<String> {
                let next = {
                    let mut calls = self.calls.lock().expect("digest counter");
                    *calls += 1;
                    *calls
                };
                // Calls 1-2 (initial snapshot + pre-start re-check) agree; everything
                // after run/verify reports drift for the final sweep.
                Ok(if next <= 2 {
                    "digest-0".to_owned()
                } else {
                    format!("digest-{next}")
                })
            }
            fn run(&self, _task: &Task) -> Result<TaskOutcome> {
                Ok(TaskOutcome::Success { tests: None })
            }
            fn verify_outputs(&self, _task: &Task) -> Result<()> {
                Ok(())
            }
        }
        let task = Task {
            id: "shell-contracts".to_owned(),
            action: TaskAction::ShellContracts,
            dependencies: BTreeSet::new(),
            inputs: BTreeSet::from([PathBuf::from("crates/lomo-xtask")]),
            outputs: BTreeSet::new(),
            tools: BTreeSet::new(),
            resources: BTreeSet::from([Resource::Reports]),
            reason: "post-run drift probe".to_owned(),
        };
        let plan = VerificationPlan {
            scope: None,
            complete_worktree: true,
            excluded_owners: BTreeSet::new(),
            tasks: vec![task],
        };
        let reports = execute(
            &plan,
            &PostDriftRunner {
                calls: Mutex::new(0),
            },
        )
        .expect("execute");
        assert_eq!(
            reports.first().map(|report| &report.status),
            Some(&TaskStatus::Failed),
            "the final digest sweep must fail a task whose inputs moved after it passed"
        );
    }
}
