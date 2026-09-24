//! Change inventory, owner graph and the shared development/handoff verification plan.

mod graph;
mod runner;

use std::{
    collections::{BTreeMap, BTreeSet},
    path::{Path, PathBuf},
    process::Command,
};

use anyhow::{Context, Result, bail, ensure};
use serde::{Deserialize, Serialize};

pub use graph::{ImpactGraph, Owner, OwnerKind};
pub use runner::{CommandRunner, TaskOutcome, TaskReport, TaskStatus, execute, parse_test_count};

use crate::{util, workspace::Workspace};

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ChangeSource {
    Worktree,
    Push { remote: String },
    All,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct ChangeInventory {
    pub paths: BTreeSet<PathBuf>,
    /// Paths that the change set removes, including the source endpoint of a rename. Ownership
    /// rules about "new" modules must not be applied to these: a manifest that is gone cannot be
    /// an unowned new module.
    #[serde(default)]
    pub removed: BTreeSet<PathBuf>,
    pub complete: bool,
}

impl ChangeInventory {
    /// Reads both rename endpoints and deletions, without Git's quoted-path text protocol.
    ///
    /// # Errors
    /// Returns an error for invalid Git output or an unreadable repository.
    pub fn read(root: &Path, source: &ChangeSource) -> Result<Self> {
        let mut paths = BTreeSet::new();
        let mut removed = BTreeSet::new();
        match source {
            ChangeSource::All => {
                return Ok(Self {
                    paths,
                    removed,
                    complete: true,
                });
            }
            ChangeSource::Worktree => {
                paths.extend(git_paths(root, &WORKTREE_DIFF)?);
                removed.extend(git_paths(root, &deleted_only(&WORKTREE_DIFF))?);
                paths.extend(git_paths(
                    root,
                    &["ls-files", "--others", "--exclude-standard", "-z"],
                )?);
            }
            ChangeSource::Push { remote } => {
                let Some(base) = push_base(root, remote)? else {
                    return Ok(Self {
                        paths,
                        removed,
                        complete: true,
                    });
                };
                let range = format!("{base}...HEAD");
                let diff = [
                    "diff",
                    "--name-only",
                    "--no-renames",
                    "-z",
                    range.as_str(),
                    "--",
                ];
                paths.extend(git_paths(root, &diff)?);
                removed.extend(git_paths(root, &deleted_only(&diff))?);
            }
        }
        Ok(Self {
            paths,
            removed,
            complete: false,
        })
    }
}

/// Resolves the push comparison base (`refs/remotes/<remote>/HEAD`, then `.../main`).
///
/// # Errors
/// Returns an error for an invalid remote name or an unreadable repository.
fn push_base(root: &Path, remote: &str) -> Result<Option<String>> {
    ensure!(
        !remote.starts_with('-') && !remote.contains(char::is_whitespace),
        "invalid remote name"
    );
    for reference in [
        format!("refs/remotes/{remote}/HEAD"),
        format!("refs/remotes/{remote}/main"),
    ] {
        let output = Command::new("git")
            .current_dir(root)
            .args(["rev-parse", "--verify", &reference])
            .output()?;
        if output.status.success() {
            return Ok(Some(reference));
        }
    }
    Ok(None)
}

/// The unified diff matching one inventory source, for diff-scoped tooling such as
/// `cargo mutants --in-diff`. `None` for whole-worktree scopes, which have no base to
/// diff against.
///
/// # Errors
/// Returns an error for an unreadable repository or non-UTF-8 diff output.
pub fn unified_diff(root: &Path, source: &ChangeSource) -> Result<Option<String>> {
    let arguments: Vec<String> = match source {
        ChangeSource::All => return Ok(None),
        ChangeSource::Worktree => ["diff", "HEAD", "--"].map(str::to_owned).to_vec(),
        ChangeSource::Push { remote } => match push_base(root, remote)? {
            Some(base) => vec!["diff".to_owned(), format!("{base}...HEAD"), "--".to_owned()],
            None => return Ok(None),
        },
    };
    let output = Command::new("git")
        .current_dir(root)
        .args(&arguments)
        .output()?;
    ensure!(
        output.status.success(),
        "git diff failed: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    Ok(Some(
        String::from_utf8(output.stdout).context("git diff output must be UTF-8")?,
    ))
}

/// Unstaged worktree inventory against `HEAD`, emitted as NUL-terminated paths.
const WORKTREE_DIFF: [&str; 6] = ["diff", "--name-only", "--no-renames", "-z", "HEAD", "--"];

/// Narrows one inventory diff to deleted paths, which is what survives of a rename's source.
///
/// `--diff-filter=D` has to precede the trailing pathspec separator.
fn deleted_only<'a>(arguments: &[&'a str]) -> Vec<&'a str> {
    let mut narrowed = arguments.to_vec();
    let separator = narrowed
        .iter()
        .position(|argument| *argument == "--")
        .unwrap_or(narrowed.len());
    narrowed.insert(separator, "--diff-filter=D");
    narrowed
}

pub(super) fn git_paths(root: &Path, arguments: &[&str]) -> Result<BTreeSet<PathBuf>> {
    let output = Command::new("git")
        .current_dir(root)
        .args(arguments)
        .output()?;
    ensure!(
        output.status.success(),
        "git inventory failed: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let text = String::from_utf8(output.stdout).context("Git inventory paths must be UTF-8")?;
    ensure!(
        text.is_empty() || text.ends_with('\0'),
        "Git inventory is not NUL terminated"
    );
    text.split_terminator('\0')
        .map(|path| {
            let path = PathBuf::from(path);
            ensure!(
                !path.is_absolute()
                    && !path
                        .components()
                        .any(|part| part == std::path::Component::ParentDir),
                "path escapes repository"
            );
            Ok(path)
        })
        .collect()
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PlanMode {
    Dev,
    Check,
    Tests,
}

#[derive(Clone, Debug, Eq, PartialEq, Ord, PartialOrd, Serialize, Deserialize)]
pub enum RequiredTool {
    Rust,
    Nextest,
    Kotlin,
    Java,
    Python,
    BoltFfi,
    AndroidSdk,
    Machete,
    Mutants,
}

#[derive(Clone, Debug, Eq, PartialEq, Ord, PartialOrd, Serialize, Deserialize)]
pub enum Resource {
    Cargo,
    KotlinBuild,
    Bindings,
    Reports,
}

#[derive(Clone, Debug, Eq, PartialEq, Ord, PartialOrd, Serialize, Deserialize)]
pub enum Artifact {
    NativeBindings,
    DetektRules,
    AnalysisInput { module: String },
    BaselineProfile,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "kebab-case")]
pub enum TaskAction {
    Architecture,
    FfiContract,
    RustFmt,
    RustClippy { package: String },
    RustTests { package: String },
    RustMutants { package: String },
    RustDocs,
    Machete,
    Bindings,
    KotlinRules,
    KotlinLight { modules: BTreeSet<String> },
    AnalysisInput { modules: BTreeSet<String> },
    KotlinFull { modules: BTreeSet<String> },
    KotlinTests { module: String },
    AndroidLint,
    ShellContracts,
    UseCaseReachability,
    BaselineProfile,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct Task {
    pub id: String,
    pub action: TaskAction,
    pub dependencies: BTreeSet<String>,
    pub inputs: BTreeSet<PathBuf>,
    pub outputs: BTreeSet<Artifact>,
    pub tools: BTreeSet<RequiredTool>,
    pub resources: BTreeSet<Resource>,
    pub reason: String,
}

impl Task {
    fn new(id: impl Into<String>, action: TaskAction, reason: &str) -> Self {
        Self {
            id: id.into(),
            action,
            dependencies: BTreeSet::new(),
            inputs: BTreeSet::new(),
            outputs: BTreeSet::new(),
            tools: BTreeSet::new(),
            resources: BTreeSet::new(),
            reason: reason.to_owned(),
        }
    }

    fn after(mut self, dependency: &str) -> Self {
        self.dependencies.insert(dependency.to_owned());
        self
    }
    fn using(mut self, tool: RequiredTool, resource: Resource) -> Self {
        self.tools.insert(tool);
        self.resources.insert(resource);
        self
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct VerificationPlan {
    pub scope: Option<String>,
    pub complete_worktree: bool,
    pub excluded_owners: BTreeSet<String>,
    pub tasks: Vec<Task>,
}

impl VerificationPlan {
    /// Builds a conservative graph from real manifest ownership and dependency edges.
    ///
    /// # Errors
    /// Rejects unknown owners and malformed executable inputs instead of returning an empty pass.
    pub fn build(
        graph: &ImpactGraph,
        changes: &ChangeInventory,
        scope: Option<&str>,
        mode: PlanMode,
    ) -> Result<Self> {
        let impacted = graph.affected(changes)?;
        let mut selected = if let Some(scope) = scope {
            ensure!(
                graph.owners.contains_key(scope),
                "unknown verification owner `{scope}`"
            );
            BTreeSet::from([scope.to_owned()])
        } else {
            impacted.clone()
        };
        if selected.contains("lomo-native")
            && (changes.complete
                || changes
                    .paths
                    .iter()
                    .any(|path| ImpactGraph::binding_input(path)))
        {
            selected.extend(
                ["native-bindings", "domain", "data", "app", "ui-components"].map(str::to_owned),
            );
        }
        let excluded_owners = impacted
            .difference(&selected)
            .cloned()
            .collect::<BTreeSet<_>>();
        let mut plan = Self {
            scope: scope.map(str::to_owned),
            complete_worktree: excluded_owners.is_empty(),
            excluded_owners,
            tasks: Vec::new(),
        };
        let rust: BTreeSet<_> = selected
            .iter()
            .filter(|name| {
                graph
                    .owners
                    .get(*name)
                    .is_some_and(|owner| owner.kind == OwnerKind::Rust)
            })
            .cloned()
            .collect();
        // Mutation testing is diff-scoped: only crates whose own sources appear in the
        // change set have mutants to generate, and a complete inventory has no diff.
        let mutating: BTreeSet<_> = if mode == PlanMode::Dev && !changes.complete {
            rust.iter()
                .filter(|name| {
                    graph.owners.get(*name).is_some_and(|owner| {
                        changes
                            .paths
                            .iter()
                            .any(|path| path.starts_with(&owner.path))
                    })
                })
                .cloned()
                .collect()
        } else {
            BTreeSet::new()
        };
        let kotlin: BTreeSet<_> = selected
            .iter()
            .filter(|name| {
                graph
                    .owners
                    .get(*name)
                    .is_some_and(|owner| owner.kind == OwnerKind::Kotlin)
            })
            .cloned()
            .collect();
        if mode != PlanMode::Tests {
            plan.tasks.push(
                Task::new(
                    "architecture",
                    TaskAction::Architecture,
                    "repository ownership and source contracts",
                )
                .using(RequiredTool::Rust, Resource::Cargo),
            );
        }
        plan.add_rust_tasks(rust, mutating, mode);
        let ffi_changed = changes.complete
            || changes
                .paths
                .iter()
                .any(|path| ImpactGraph::binding_input(path));
        let product_modules: BTreeSet<_> = kotlin
            .iter()
            .filter(|module| !matches!(module.as_str(), "native-bindings" | "detekt-rules"))
            .cloned()
            .collect();
        plan.add_kotlin_tasks(&kotlin, &product_modules, changes, ffi_changed, mode);
        plan.add_evidence_tasks(&product_modules, mode);
        plan.assign_artifacts(graph);
        plan.validate()?;
        Ok(plan)
    }

    fn add_kotlin_tasks(
        &mut self,
        kotlin: &BTreeSet<String>,
        product_modules: &BTreeSet<String>,
        changes: &ChangeInventory,
        ffi_changed: bool,
        mode: PlanMode,
    ) {
        if kotlin.is_empty() {
            return;
        }
        // Generation is incremental and validates its input/output digest; native packaging is
        // never a prerequisite for host Kotlin compilation.
        if !product_modules.is_empty() || kotlin.contains("native-bindings") {
            self.tasks.push(
                Task::new(
                    "bindings",
                    TaskAction::Bindings,
                    if ffi_changed {
                        "FFI/schema inputs changed"
                    } else {
                        "validate generated Kotlin prerequisite"
                    },
                )
                .using(RequiredTool::BoltFfi, Resource::Bindings)
                .using(RequiredTool::Rust, Resource::Cargo),
            );
        }
        if mode != PlanMode::Tests && !product_modules.is_empty() {
            self.add_kotlin_analysis_tasks(product_modules);
        }
        self.add_kotlin_test_tasks(kotlin);
        let resource_change = changes.complete
            || changes.paths.iter().any(|path| {
                path.components()
                    .any(|component| component.as_os_str() == "res")
                    || path
                        .file_name()
                        .is_some_and(|name| name == "AndroidManifest.xml" || name == "module.yaml")
            });
        if mode != PlanMode::Tests
            && !product_modules.is_empty()
            && (resource_change || mode == PlanMode::Check)
        {
            self.tasks.push(
                Task::new(
                    "android-lint",
                    TaskAction::AndroidLint,
                    "Android resources, manifests and compile model",
                )
                .after("kotlin-analysis-input")
                .using(RequiredTool::AndroidSdk, Resource::KotlinBuild),
            );
        }
        // The committed Android baseline profile is an input to release packaging, not a
        // hand-maintained fact. The gate regenerates it from the same shared build directory the
        // app classes were compiled into, so a profile that no rule can produce fails here.
        if mode == PlanMode::Check && product_modules.contains("app") {
            self.tasks.push(
                Task::new(
                    "baseline-profile",
                    TaskAction::BaselineProfile,
                    "regenerate the Android baseline profile from the shared build",
                )
                .after("kotlin-tests:app")
                .using(RequiredTool::Python, Resource::KotlinBuild),
            );
        }
    }

    fn add_kotlin_analysis_tasks(&mut self, product_modules: &BTreeSet<String>) {
        self.tasks.push(
            Task::new(
                "kotlin-rules",
                TaskAction::KotlinRules,
                "compile the current rule JAR",
            )
            .using(RequiredTool::Kotlin, Resource::KotlinBuild),
        );
        self.tasks.push(
            Task::new(
                "kotlin-light",
                TaskAction::KotlinLight {
                    modules: product_modules.clone(),
                },
                "early syntax and authority constraints",
            )
            .after("kotlin-rules")
            .using(RequiredTool::Java, Resource::Reports),
        );
        self.tasks.push(
            Task::new(
                "kotlin-analysis-input",
                TaskAction::AnalysisInput {
                    modules: product_modules.clone(),
                },
                "exact compile context for selected modules",
            )
            .after("bindings")
            .using(RequiredTool::Kotlin, Resource::KotlinBuild),
        );
        self.tasks.push(
            Task::new(
                "kotlin-full",
                TaskAction::KotlinFull {
                    modules: product_modules.clone(),
                },
                "type-resolved module checks",
            )
            .after("kotlin-light")
            .after("kotlin-analysis-input")
            .using(RequiredTool::Java, Resource::Reports),
        );
    }

    fn add_kotlin_test_tasks(&mut self, kotlin: &BTreeSet<String>) {
        for module in kotlin {
            if module == "native-bindings" {
                continue;
            }
            let mut task = Task::new(
                format!("kotlin-tests:{module}"),
                TaskAction::KotlinTests {
                    module: module.clone(),
                },
                "selected module host specifications",
            )
            .using(RequiredTool::Kotlin, Resource::KotlinBuild);
            if module != "detekt-rules" {
                task = task.after("bindings");
            }
            self.tasks.push(task);
        }
    }

    fn add_evidence_tasks(&mut self, product_modules: &BTreeSet<String>, mode: PlanMode) {
        if mode == PlanMode::Check {
            self.tasks.push(
                Task::new(
                    "rust-docs",
                    TaskAction::RustDocs,
                    "workspace documentation links",
                )
                .using(RequiredTool::Rust, Resource::Cargo),
            );
            self.tasks.push(
                Task::new(
                    "rust-machete",
                    TaskAction::Machete,
                    "unused direct dependencies",
                )
                .using(RequiredTool::Machete, Resource::Cargo),
            );
        }
        if mode != PlanMode::Tests
            && ["app", "domain", "data", "ui-components"]
                .iter()
                .all(|module| product_modules.contains(*module))
        {
            self.tasks.push(
                Task::new(
                    "ffi-contract",
                    TaskAction::FfiContract,
                    "explicit exports, generated declarations and resolved consumers",
                )
                .after("kotlin-full")
                .using(RequiredTool::Rust, Resource::Cargo),
            );
        }
        if mode != PlanMode::Tests && !product_modules.is_empty() {
            self.tasks.push(
                Task::new(
                    "usecase-reachability",
                    TaskAction::UseCaseReachability,
                    "domain usecases must stay reachable from production pipelines",
                )
                .using(RequiredTool::Rust, Resource::Reports),
            );
        }
        if mode != PlanMode::Tests {
            self.tasks.push(
                Task::new(
                    "shell-contracts",
                    TaskAction::ShellContracts,
                    "quality wiring, test contracts and resource parity",
                )
                .using(RequiredTool::Rust, Resource::Reports),
            );
        }
    }

    fn assign_artifacts(&mut self, graph: &ImpactGraph) {
        for task in &mut self.tasks {
            task.inputs = graph.task_inputs(&task.action);
            match &task.action {
                TaskAction::Bindings => {
                    task.outputs.insert(Artifact::NativeBindings);
                }
                TaskAction::KotlinRules => {
                    task.outputs.insert(Artifact::DetektRules);
                }
                TaskAction::AnalysisInput { modules } => {
                    task.outputs
                        .extend(modules.iter().map(|module| Artifact::AnalysisInput {
                            module: module.clone(),
                        }));
                }
                TaskAction::KotlinFull { .. } => {
                    task.resources.insert(Resource::KotlinBuild);
                }
                TaskAction::BaselineProfile => {
                    task.outputs.insert(Artifact::BaselineProfile);
                }
                TaskAction::Architecture
                | TaskAction::FfiContract
                | TaskAction::RustFmt
                | TaskAction::RustClippy { .. }
                | TaskAction::RustTests { .. }
                | TaskAction::RustMutants { .. }
                | TaskAction::RustDocs
                | TaskAction::Machete
                | TaskAction::KotlinLight { .. }
                | TaskAction::KotlinTests { .. }
                | TaskAction::AndroidLint
                | TaskAction::ShellContracts
                | TaskAction::UseCaseReachability => {}
            }
        }
    }

    fn add_rust_tasks(
        &mut self,
        rust: BTreeSet<String>,
        mutating: BTreeSet<String>,
        mode: PlanMode,
    ) {
        if !rust.is_empty() && mode != PlanMode::Tests {
            self.tasks.push(
                Task::new("rust-fmt", TaskAction::RustFmt, "Rust source formatting")
                    .using(RequiredTool::Rust, Resource::Cargo),
            );
        }
        for package in rust {
            if mode != PlanMode::Tests {
                self.tasks.push(
                    Task::new(
                        format!("rust-clippy:{package}"),
                        TaskAction::RustClippy {
                            package: package.clone(),
                        },
                        "changed owner or reverse dependency",
                    )
                    .after("architecture")
                    .using(RequiredTool::Rust, Resource::Cargo),
                );
            }
            let mut tests = Task::new(
                format!("rust-tests:{package}"),
                TaskAction::RustTests {
                    package: package.clone(),
                },
                "observable owner behavior",
            )
            .using(RequiredTool::Nextest, Resource::Cargo);
            if mode != PlanMode::Tests {
                tests = tests.after(&format!("rust-clippy:{package}"));
            }
            self.tasks.push(tests);
        }
        for package in mutating {
            self.tasks.push(
                Task::new(
                    format!("rust-mutants:{package}"),
                    TaskAction::RustMutants {
                        package: package.clone(),
                    },
                    "incremental mutation testing of the change diff",
                )
                .after(&format!("rust-tests:{package}"))
                .using(RequiredTool::Rust, Resource::Cargo)
                .using(RequiredTool::Mutants, Resource::Cargo),
            );
        }
    }

    /// Validates graph identity and ordering before any command can execute.
    ///
    /// # Errors
    /// Rejects duplicate tasks, missing prerequisites and cycles.
    pub fn validate(&self) -> Result<()> {
        ensure!(
            !self.tasks.is_empty(),
            "verification plan contains no tasks"
        );
        let by_id: BTreeMap<_, _> = self.tasks.iter().map(|task| (&task.id, task)).collect();
        ensure!(
            by_id.len() == self.tasks.len(),
            "duplicate verification task"
        );
        let mut remaining: BTreeSet<_> = by_id.keys().copied().collect();
        while !remaining.is_empty() {
            let ready: Vec<_> = remaining
                .iter()
                .filter(|id| {
                    by_id.get(**id).is_some_and(|task| {
                        task.dependencies
                            .iter()
                            .all(|dep| by_id.contains_key(dep) && !remaining.contains(dep))
                    })
                })
                .copied()
                .collect();
            ensure!(
                !ready.is_empty(),
                "cyclic or missing verification prerequisite: {remaining:?}"
            );
            for id in ready {
                remaining.remove(id);
            }
        }
        Ok(())
    }
}

pub(crate) fn run(
    workspace: &Workspace,
    source: &ChangeSource,
    scope: Option<&str>,
    mode: PlanMode,
    print_only: bool,
) -> Result<()> {
    let graph = ImpactGraph::load(&workspace.root)?;
    let inventory = ChangeInventory::read(&workspace.root, source)?;
    let plan = VerificationPlan::build(&graph, &inventory, scope, mode)?;
    if print_only {
        use std::io::Write as _;
        let mut stdout = std::io::stdout().lock();
        serde_json::to_writer_pretty(&mut stdout, &plan)?;
        writeln!(stdout)?;
        return Ok(());
    }
    util::emit_stderr(format_args!(
        "xtask: {} tasks; complete_worktree={}; excluded={:?}",
        plan.tasks.len(),
        plan.complete_worktree,
        plan.excluded_owners
    ));
    let mutation_diff = if plan
        .tasks
        .iter()
        .any(|task| matches!(task.action, TaskAction::RustMutants { .. }))
    {
        Some(
            unified_diff(&workspace.root, source)?
                .context("rust-mutants tasks require a diffable change scope")?,
        )
    } else {
        None
    };
    let runner = runner::RepositoryRunner::new(workspace, mutation_diff.as_deref())?;
    let results = execute(&plan, &runner)?;
    runner.write_report(&plan, &results)?;
    if let Some(failed) = results
        .iter()
        .find(|result| !matches!(result.status, TaskStatus::Passed))
    {
        bail!(
            "verification remains open: {} {:?}: {}",
            failed.id,
            failed.status,
            failed.detail
        );
    }
    util::emit_stderr(format_args!(
        "xtask: verification passed; complete_worktree={}",
        plan.complete_worktree
    ));
    Ok(())
}
