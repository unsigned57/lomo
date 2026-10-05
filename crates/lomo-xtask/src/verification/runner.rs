use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    io::Read as _,
    path::PathBuf,
    process::Command,
    time::{Instant, SystemTime, UNIX_EPOCH},
};

use anyhow::{Context, Result, ensure};
use serde::{Deserialize, Serialize};
use sha2::{Digest as _, Sha256};

use super::{Artifact, Resource, Task, TaskAction, VerificationPlan, git_paths};
use crate::{native, quality, tools, util, workspace::Workspace};

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum TaskStatus {
    Passed,
    Failed,
    Cancelled,
    NotScheduled,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum TaskOutcome {
    Success { tests: Option<u64> },
    Failure(String),
    Cancelled(String),
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct TaskReport {
    pub id: String,
    pub status: TaskStatus,
    pub input_digest: String,
    pub elapsed_ms: u128,
    pub detail: String,
}

/// Execution seam used by the scheduler and real command adapter. It also makes resource and
/// interruption contracts testable without invoking compilers or sleeping.
pub trait CommandRunner: Sync {
    /// # Errors
    /// Returns an error if any declared input cannot be inspected.
    fn input_digest(&self, task: &Task) -> Result<String>;
    /// # Errors
    /// Returns an error if the command cannot start or its output protocol is invalid.
    fn run(&self, task: &Task) -> Result<TaskOutcome>;
    /// # Errors
    /// Returns an error if a successful command did not produce every declared output.
    fn verify_outputs(&self, task: &Task) -> Result<()>;
}

/// Executes a validated DAG, sharing only nonconflicting resource sets in a wave.
///
/// # Errors
/// Returns an error for an invalid graph or an input snapshot that cannot be read.
pub fn execute(plan: &VerificationPlan, runner: &impl CommandRunner) -> Result<Vec<TaskReport>> {
    plan.validate()?;
    let initial: BTreeMap<_, _> = plan
        .tasks
        .iter()
        .map(|task| Ok((task.id.clone(), runner.input_digest(task)?)))
        .collect::<Result<_>>()?;
    let mut reports: BTreeMap<String, TaskReport> = BTreeMap::new();
    while reports.len() < plan.tasks.len() {
        let mut held = BTreeSet::<Resource>::new();
        let mut ready = Vec::new();
        for task in &plan.tasks {
            if reports.contains_key(&task.id) {
                continue;
            }
            if task.dependencies.iter().any(|id| {
                reports
                    .get(id)
                    .is_some_and(|report| report.status != TaskStatus::Passed)
            }) {
                reports.insert(
                    task.id.clone(),
                    TaskReport {
                        id: task.id.clone(),
                        status: TaskStatus::NotScheduled,
                        input_digest: initial
                            .get(&task.id)
                            .context("task input snapshot")?
                            .clone(),
                        elapsed_ms: 0,
                        detail: "prerequisite failed or was cancelled".to_owned(),
                    },
                );
            } else if task.dependencies.iter().all(|id| reports.contains_key(id))
                && held.is_disjoint(&task.resources)
            {
                held.extend(task.resources.iter().cloned());
                ready.push(task);
            }
        }
        let wave = std::thread::scope(|scope| -> Result<Vec<TaskReport>> {
            let mut handles = Vec::new();
            for task in &ready {
                let expected = initial.get(&task.id).context("task input snapshot")?;
                handles.push(scope.spawn(move || execute_task(task, expected, runner)));
            }
            handles
                .into_iter()
                .map(|handle| {
                    handle
                        .join()
                        .map_err(|_panic| anyhow::anyhow!("verification worker panicked"))
                })
                .collect()
        })?;
        for report in wave {
            reports.insert(report.id.clone(), report);
        }
    }
    // A source edit after its task passed also invalidates the overall result.
    for task in &plan.tasks {
        if let Some(report) = reports.get_mut(&task.id)
            && report.status == TaskStatus::Passed
            && runner.input_digest(task)? != report.input_digest
        {
            report.status = TaskStatus::Failed;
            "inputs changed during verification; rerun required".clone_into(&mut report.detail);
        }
    }
    Ok(plan
        .tasks
        .iter()
        .filter_map(|task| reports.remove(&task.id))
        .collect())
}

fn execute_task(task: &Task, expected: &str, runner: &impl CommandRunner) -> TaskReport {
    let start = Instant::now();
    let result = (|| -> Result<TaskOutcome> {
        ensure!(
            runner.input_digest(task)? == expected,
            "inputs changed before task started"
        );
        let outcome = runner.run(task)?;
        if let TaskOutcome::Success { tests } = &outcome {
            if matches!(
                task.action,
                TaskAction::RustTests { .. }
                    | TaskAction::KotlinTests { .. }
                    | TaskAction::Architecture
                    | TaskAction::FfiContract
            ) {
                ensure!(
                    tests.is_some_and(|count| count > 0),
                    "zero or unreported tests cannot pass"
                );
            }
            runner.verify_outputs(task)?;
            ensure!(
                runner.input_digest(task)? == expected,
                "inputs changed while task was running"
            );
        }
        Ok(outcome)
    })();
    let (status, detail) = match result {
        Ok(TaskOutcome::Success { tests }) => (
            TaskStatus::Passed,
            tests.map_or_else(|| "completed".to_owned(), |count| format!("{count} tests")),
        ),
        Ok(TaskOutcome::Failure(reason)) => (TaskStatus::Failed, reason),
        Ok(TaskOutcome::Cancelled(reason)) => (TaskStatus::Cancelled, reason),
        Err(error) => (TaskStatus::Failed, format!("{error:#}")),
    };
    TaskReport {
        id: task.id.clone(),
        status,
        input_digest: expected.to_owned(),
        elapsed_ms: start.elapsed().as_millis(),
        detail,
    }
}

pub(super) struct RepositoryRunner<'a> {
    workspace: &'a Workspace,
    report_dir: PathBuf,
    mutation_diff: Option<PathBuf>,
}

impl<'a> RepositoryRunner<'a> {
    pub(super) fn new(workspace: &'a Workspace, mutation_diff: Option<&str>) -> Result<Self> {
        let run_id = format!(
            "{}-{}",
            SystemTime::now().duration_since(UNIX_EPOCH)?.as_nanos(),
            std::process::id()
        );
        let report_dir = workspace.reports_dir().join("verification").join(run_id);
        fs::create_dir_all(&report_dir)?;
        let mutation_diff = mutation_diff
            .map(|text| -> Result<PathBuf> {
                let path = report_dir.join("mutants.diff");
                fs::write(&path, text)?;
                Ok(path)
            })
            .transpose()?;
        Ok(Self {
            workspace,
            report_dir,
            mutation_diff,
        })
    }

    pub(super) fn write_report(
        &self,
        plan: &VerificationPlan,
        reports: &[TaskReport],
    ) -> Result<serde_json::Value> {
        let path = self.report_dir.join("results.json");
        let mut logs = BTreeMap::new();
        for report in reports {
            let log = self
                .report_dir
                .join(format!("{}.log", report.id.replace(':', "_")));
            if log.try_exists()? {
                logs.insert(&report.id, log);
            }
        }
        let evidence = serde_json::json!({
            "plan": plan, "results": reports, "report_path": path, "logs": logs,
        });
        fs::write(&path, serde_json::to_vec_pretty(&evidence)?)?;
        Ok(evidence)
    }

    fn command(&self, task: &Task) -> Result<Command> {
        match &task.action {
            TaskAction::KotlinRules
            | TaskAction::KotlinTests { .. }
            | TaskAction::KotlinLight { .. }
            | TaskAction::KotlinFull { .. }
            | TaskAction::AnalysisInput { .. } => self.kotlin_command(task),
            TaskAction::Architecture
            | TaskAction::FfiContract
            | TaskAction::RustFmt
            | TaskAction::RustClippy { .. }
            | TaskAction::RustTests { .. }
            | TaskAction::RustMutants { .. }
            | TaskAction::RustDocs
            | TaskAction::Machete => self.rust_command(task),
            TaskAction::BaselineProfile => self.baseline_profile_command(),
            TaskAction::AndroidLint
            | TaskAction::Bindings
            | TaskAction::ShellContracts
            | TaskAction::UseCaseReachability => {
                anyhow::bail!("task uses an owned executor")
            }
        }
    }

    /// Regenerates the Android baseline profile from the shared Kotlin build into this run's
    /// evidence directory. The committed `baselineProfiles/generated.txt` is a release-packaging
    /// input rather than a hand-maintained fact, so the gate proves the generator still works
    /// against the current app classes instead of only requiring the file to exist.
    fn baseline_profile_command(&self) -> Result<Command> {
        let evidence = self.report_dir.join("baseline-profile");
        fs::create_dir_all(&evidence)?;
        let mut command = util::repository_command(self.workspace, "python3");
        command
            .arg(
                self.workspace
                    .root
                    .join("quality/scripts/generate_static_baseline_profile.py"),
            )
            .arg("--build-dir")
            .arg(&self.workspace.kotlin_build)
            .arg("--output")
            .arg(evidence.join("generated.txt"))
            .arg("--report")
            .arg(evidence.join("report.txt"));
        Ok(command)
    }

    fn rust_command(&self, task: &Task) -> Result<Command> {
        let workspace = self.workspace;
        let mut cargo = util::cargo(workspace);
        match &task.action {
            TaskAction::Architecture => {
                cargo.args([
                    "test",
                    "-p",
                    "lomo-architecture-tests",
                    "--test",
                    "architecture",
                    "--locked",
                ]);
            }
            TaskAction::FfiContract => {
                cargo
                    .args([
                        "test",
                        "-p",
                        "lomo-architecture-tests",
                        "--test",
                        "architecture",
                        "--locked",
                        "tests::ffi_generated_contract_has_reachable_adapters",
                        "--",
                        "--ignored",
                        "--exact",
                    ])
                    .env(
                        "LOMO_FFI_FACTS",
                        workspace.reports_dir().join("detekt/symbols"),
                    );
            }
            TaskAction::RustFmt => {
                cargo.args(["fmt", "--all", "--", "--check"]);
            }
            TaskAction::RustClippy { package } => {
                cargo.args([
                    "clippy",
                    "-p",
                    package,
                    "--all-targets",
                    "--all-features",
                    "--locked",
                    "--",
                    "-D",
                    "warnings",
                ]);
            }
            TaskAction::RustTests { package } => {
                cargo.args([
                    "nextest",
                    "run",
                    "-p",
                    package,
                    "--all-features",
                    "--locked",
                    "--no-tests=fail",
                ]);
            }
            TaskAction::RustMutants { package } => return self.mutants_command(package),
            TaskAction::RustDocs => {
                cargo.args([
                    "doc",
                    "--workspace",
                    "--no-deps",
                    "--all-features",
                    "--locked",
                ]);
            }
            TaskAction::Machete => {
                return Ok(util::repository_command(
                    workspace,
                    workspace.tool_bin().join("cargo-machete"),
                ));
            }
            TaskAction::KotlinRules
            | TaskAction::KotlinTests { .. }
            | TaskAction::KotlinLight { .. }
            | TaskAction::KotlinFull { .. }
            | TaskAction::AnalysisInput { .. } => {
                anyhow::bail!("task uses the Kotlin executor")
            }
            TaskAction::AndroidLint
            | TaskAction::Bindings
            | TaskAction::ShellContracts
            | TaskAction::UseCaseReachability
            | TaskAction::BaselineProfile => {
                anyhow::bail!("task uses an owned executor")
            }
        }
        Ok(cargo)
    }

    /// Diff-scoped incremental mutation testing. Copy mode (no `--in-place`) with `-j`
    /// parallelism: each mutant gets its own source copy, so jobs run concurrently instead
    /// of serializing on a single live tree. The baseline is skipped because this task
    /// runs strictly after `rust-tests:<package>` proved the suite green.
    fn mutants_command(&self, package: &str) -> Result<Command> {
        let mut cargo = util::cargo(self.workspace);
        cargo
            .args([
                "mutants",
                "--baseline",
                "skip",
                "--test-tool",
                "nextest",
                "--all-features",
                "--colors",
                "never",
                "--minimum-test-timeout",
                "120",
                "-j",
                "8",
                "--package",
                package,
                "--in-diff",
            ])
            .arg(
                self.mutation_diff
                    .as_ref()
                    .context("rust-mutants task has no change diff")?,
            )
            .arg("--output")
            .arg(self.report_dir.join("mutants").join(package));
        fs::create_dir_all(self.report_dir.join("mutants"))?;
        Ok(cargo)
    }

    fn kotlin_command(&self, task: &Task) -> Result<Command> {
        let workspace = self.workspace;
        match &task.action {
            TaskAction::KotlinRules => {
                let mut command = util::kotlin(workspace)?;
                command
                    .args(["build", "--module", "detekt-rules", "--build-dir"])
                    .arg(&workspace.kotlin_build);
                Ok(command)
            }
            TaskAction::KotlinTests { module } => {
                let mut command = util::kotlin(workspace)?;
                command
                    .arg("test")
                    .arg(format!("--include-module={module}"))
                    .arg("--build-dir")
                    .arg(&workspace.kotlin_build);
                Ok(command)
            }
            TaskAction::KotlinLight { modules } | TaskAction::KotlinFull { modules } => {
                let mut command =
                    util::policy_script(workspace, "quality/scripts/kotlin_detekt_check.sh");
                command
                    .env("LOMO_KOTLIN_BUILD_DIR", &workspace.kotlin_build)
                    .env(
                        "LOMO_DETEKT_MODE",
                        if matches!(task.action, TaskAction::KotlinFull { .. }) {
                            "full"
                        } else {
                            "light"
                        },
                    )
                    .env(
                        "LOMO_DETEKT_MODULES",
                        modules.iter().cloned().collect::<Vec<_>>().join(","),
                    );
                Ok(command)
            }
            TaskAction::AnalysisInput { modules } => {
                let mut command =
                    util::policy_script(workspace, "quality/scripts/kotlin_analysis_input.sh");
                command
                    .env("LOMO_KOTLIN_BUILD_DIR", &workspace.kotlin_build)
                    .args(modules);
                Ok(command)
            }
            TaskAction::Architecture
            | TaskAction::FfiContract
            | TaskAction::RustFmt
            | TaskAction::RustClippy { .. }
            | TaskAction::RustTests { .. }
            | TaskAction::RustMutants { .. }
            | TaskAction::RustDocs
            | TaskAction::Machete
            | TaskAction::Bindings
            | TaskAction::AndroidLint
            | TaskAction::ShellContracts
            | TaskAction::UseCaseReachability
            | TaskAction::BaselineProfile => {
                anyhow::bail!("task does not use the Kotlin command executor")
            }
        }
    }
}

impl CommandRunner for RepositoryRunner<'_> {
    fn input_digest(&self, task: &Task) -> Result<String> {
        let paths = git_paths(
            &self.workspace.root,
            &[
                "ls-files",
                "--cached",
                "--others",
                "--exclude-standard",
                "-z",
            ],
        )?;
        let mut hash = Sha256::new();
        hash.update(serde_json::to_vec(task)?);
        for path in paths
            .iter()
            .filter(|path| task.inputs.iter().any(|input| path.starts_with(input)))
        {
            hash.update(path.as_os_str().as_encoded_bytes());
            hash.update([0]);
            let absolute = self.workspace.root.join(path);
            match fs::File::open(&absolute) {
                Ok(mut file) => {
                    ensure!(
                        absolute.canonicalize()?.starts_with(&self.workspace.root),
                        "source escapes repository: {}",
                        absolute.display()
                    );
                    let mut buffer = [0; 8192];
                    loop {
                        let count = file.read(&mut buffer)?;
                        if count == 0 {
                            break;
                        }
                        hash.update(
                            buffer
                                .get(..count)
                                .context("file reader returned an invalid count")?,
                        );
                    }
                }
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                    hash.update(b"deleted");
                }
                Err(error) => return Err(error.into()),
            }
        }
        Ok(format!("{:x}", hash.finalize()))
    }

    fn run(&self, task: &Task) -> Result<TaskOutcome> {
        util::emit_stderr(format_args!("xtask: {}: {}", task.id, task.reason));
        tools::ensure_required(self.workspace, &task.tools)?;
        match task.action {
            TaskAction::Bindings => {
                native::generate_bindings(self.workspace)?;
                return Ok(TaskOutcome::Success { tests: None });
            }
            TaskAction::AndroidLint => {
                quality::run_lint_policy(self.workspace)?;
                return Ok(TaskOutcome::Success { tests: None });
            }
            TaskAction::ShellContracts => {
                quality::run_shell_contracts(self.workspace)?;
                return Ok(TaskOutcome::Success { tests: None });
            }
            TaskAction::UseCaseReachability => {
                crate::usecase_reachability::check_usecase_reachability(&self.workspace.root)?;
                return Ok(TaskOutcome::Success { tests: None });
            }
            TaskAction::Architecture
            | TaskAction::FfiContract
            | TaskAction::RustFmt
            | TaskAction::RustClippy { .. }
            | TaskAction::RustTests { .. }
            | TaskAction::RustMutants { .. }
            | TaskAction::RustDocs
            | TaskAction::Machete
            | TaskAction::KotlinRules
            | TaskAction::KotlinLight { .. }
            | TaskAction::AnalysisInput { .. }
            | TaskAction::KotlinFull { .. }
            | TaskAction::KotlinTests { .. }
            | TaskAction::BaselineProfile => {}
        }
        let mut command = self.command(task)?;
        let description = format!(
            "{} {}",
            command.get_program().to_string_lossy(),
            command
                .get_args()
                .map(|arg| arg.to_string_lossy())
                .collect::<Vec<_>>()
                .join(" ")
        );
        util::emit_stderr(format_args!("xtask: {description}"));
        let output = command.output().context("start verification command")?;
        let stdout = String::from_utf8_lossy(&output.stdout);
        let stderr = String::from_utf8_lossy(&output.stderr);
        let log = format!("{description}\n{stdout}\n{stderr}");
        let log_path = self
            .report_dir
            .join(format!("{}.log", task.id.replace(':', "_")));
        fs::write(&log_path, &log)?;
        if output.status.code().is_none() {
            return Ok(TaskOutcome::Cancelled(format!(
                "{description}: terminated; {}",
                log_path.display()
            )));
        }
        if !output.status.success() {
            return Ok(TaskOutcome::Failure(format!(
                "{description}: {}; {}\n{stderr}",
                output.status,
                log_path.display()
            )));
        }
        let tests = parse_test_count(&log)?;
        Ok(TaskOutcome::Success { tests })
    }

    fn verify_outputs(&self, task: &Task) -> Result<()> {
        for artifact in &task.outputs {
            let output = match artifact {
                Artifact::NativeBindings => self
                    .workspace
                    .generated_bindings()
                    .join("LomoNativeBridge.kt"),
                Artifact::DetektRules => self
                    .workspace
                    .kotlin_build
                    .join("tasks/_detekt-rules_jarJvm/detekt-rules-jvm.jar"),
                Artifact::AnalysisInput { module } => {
                    let variant = if module == "domain" || module == "detekt-rules" {
                        "jvm-main"
                    } else {
                        "android-debug"
                    };
                    self.workspace
                        .kotlin_build
                        .join("analysis-input")
                        .join(format!("{module}-{variant}.json"))
                }
                Artifact::BaselineProfile => self
                    .workspace
                    .root
                    .join("apps/android/app/src/main/baselineProfiles/generated.txt"),
            };
            ensure!(
                output.is_file(),
                "missing declared output {}",
                output.display()
            );
            if matches!(artifact, Artifact::BaselineProfile) {
                // Existence is not freshness: the committed profile is only valid while it is
                // byte-identical to what the generator just produced from the compiled
                // classes. A mismatch means the committed file is stale or hand-edited.
                let regenerated = self.report_dir.join("baseline-profile/generated.txt");
                let regenerated_bytes = fs::read(&regenerated).with_context(|| {
                    format!(
                        "baseline task declared its output but produced no regenerated \
                         evidence at {}",
                        regenerated.display()
                    )
                })?;
                ensure!(
                    !regenerated_bytes.is_empty(),
                    "generator produced an empty baseline profile: {}",
                    regenerated.display()
                );
                let committed_bytes = fs::read(&output)
                    .with_context(|| format!("read committed {}", output.display()))?;
                ensure!(
                    regenerated_bytes == committed_bytes,
                    "committed baseline profile is stale or hand-edited: {} does not match \
                     the profile regenerated from the current classes at {}; refresh it via \
                     `quality/scripts/generate_static_baseline_profile.py --build-dir \
                     <shared kotlin build dir>`",
                    output.display(),
                    regenerated.display()
                );
            }
        }
        Ok(())
    }
}

/// Counts the tests a completed command actually executed.
///
/// Recognizes the summaries the repository's real commands emit: nextest's human summary
/// (`N test run:` / `N tests run:`, where `N` may be a `run/total` ratio), the `JUnit` Platform
/// console listener used by the Kotlin suites (`[ <N> tests successful ]`) and libtest's
/// `test result:` line. A singular nextest summary still reports a real run, so a one-test pass
/// is never mistaken for "zero or unreported tests". Failure, skip and interruption stay
/// distinct because the scheduler classifies the exit status before this count is used.
///
/// # Errors
///
/// Returns an error when a recognized summary carries a non-numeric count.
pub fn parse_test_count(log: &str) -> Result<Option<u64>> {
    let mut total = None;
    for line in log.lines() {
        if let Some(count) = count_before(line, &["test", "tests"], "run")? {
            total = Some(total.unwrap_or(0) + count);
            continue;
        }
        if let Some(count) = count_before(line, &["tests"], "successful")? {
            total = Some(total.unwrap_or(0) + count);
            continue;
        }
        if let Some(count) = libtest_executed_count(line)? {
            total = Some(total.unwrap_or(0) + count);
        }
    }
    Ok(total)
}

/// Parses the count immediately preceding `<word> <suffix>…`, e.g. `1 test run:` or `4 tests successful`.
fn count_before(line: &str, words: &[&str], suffix: &str) -> Result<Option<u64>> {
    let tokens: Vec<_> = line.split_whitespace().collect();
    let Some(count) = tokens.windows(3).find_map(|parts| match parts {
        [count, word, tail] if words.contains(word) && tail.starts_with(suffix) => Some(*count),
        _ => None,
    }) else {
        return Ok(None);
    };
    leading_count(count).map(Some)
}

/// libtest's `test result: <status>. <N> passed; <M> failed; ...`; executed = passed + failed.
fn libtest_executed_count(line: &str) -> Result<Option<u64>> {
    let Some(result) = line.split("test result:").nth(1) else {
        return Ok(None);
    };
    let words: Vec<_> = result.split_whitespace().collect();
    let mut executed = 0_u64;
    let mut recognized = false;
    for parts in words.windows(2) {
        if let [count, outcome] = parts
            && (outcome.starts_with("passed") || outcome.starts_with("failed"))
        {
            executed += count
                .parse::<u64>()
                .with_context(|| format!("invalid libtest count `{count}`"))?;
            recognized = true;
        }
    }
    Ok(recognized.then_some(executed))
}

/// Parses a plain count or the numerator of nextest's `run/total` ratio.
fn leading_count(token: &str) -> Result<u64> {
    let numerator = token
        .split_once('/')
        .map_or(token, |(numerator, _)| numerator);
    numerator
        .parse::<u64>()
        .with_context(|| format!("invalid test count `{token}`"))
}
