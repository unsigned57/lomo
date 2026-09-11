use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, ExitStatus};

use crate::error::TuiError;

/// How an editor session will be committed.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum EditKind {
    Create,
    Update { memo_id: String },
}

/// SHA-256 hex of the document bytes taken before the editor started.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct EditBaseline {
    pub fingerprint: Option<String>,
}

/// Observable three-way decision after the editor exits.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum CommitDecision {
    CancelledEmpty,
    Unchanged,
    Submit {
        content: String,
    },
    Conflict {
        draft_content: String,
        baseline: String,
        disk: String,
    },
}

/// Resolved argv for an external editor. Never defaults to vim.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct EditorArgv {
    pub program: String,
    pub args: Vec<String>,
}

/// Process launcher so tests do not spawn a real editor.
pub trait CommandRunner {
    /// Runs `program` with `args` in the foreground and returns the wait status.
    ///
    /// # Errors
    /// Spawn or wait failures, including a missing binary.
    fn run_foreground(&self, program: &str, args: &[String]) -> Result<ExitStatus, std::io::Error>;
}

/// Production runner using `std::process::Command` with an argv vector (no shell).
#[derive(Clone, Copy, Debug, Default)]
pub struct StdCommandRunner;

impl CommandRunner for StdCommandRunner {
    fn run_foreground(&self, program: &str, args: &[String]) -> Result<ExitStatus, std::io::Error> {
        Command::new(program).args(args).status()
    }
}

/// Resolves editor argv: config > `$VISUAL` > `$EDITOR`. Absence is an error, not vim.
///
/// # Errors
/// Returns [`TuiError::EditorNotConfigured`] when every source is empty.
pub fn resolve_editor(
    config: Option<&[String]>,
    visual: Option<&str>,
    editor_env: Option<&str>,
) -> Result<EditorArgv, TuiError> {
    if let Some(program) = config
        .and_then(|argv| argv.first())
        .filter(|value| !value.is_empty())
    {
        return Ok(EditorArgv {
            program: program.clone(),
            args: config
                .iter()
                .flat_map(|argv| argv.iter().skip(1).cloned())
                .collect(),
        });
    }
    if let Some(spec) = nonempty(visual) {
        return Ok(split_spec(spec));
    }
    if let Some(spec) = nonempty(editor_env) {
        return Ok(split_spec(spec));
    }
    Err(TuiError::EditorNotConfigured)
}

/// Three-way commit law: empty create cancels; fingerprint mismatch keeps the draft.
#[must_use]
pub fn decide_commit(
    kind: &EditKind,
    initial: &str,
    draft: &str,
    baseline: &EditBaseline,
    disk_fingerprint: Option<&str>,
) -> CommitDecision {
    if matches!(kind, EditKind::Create) && draft.trim().is_empty() {
        return CommitDecision::CancelledEmpty;
    }
    if draft == initial {
        return CommitDecision::Unchanged;
    }
    let baseline_fp = baseline.fingerprint.as_deref();
    if baseline_fp != disk_fingerprint {
        return CommitDecision::Conflict {
            draft_content: draft.to_owned(),
            baseline: baseline_fp.unwrap_or("absent").to_owned(),
            disk: disk_fingerprint.unwrap_or("absent").to_owned(),
        };
    }
    CommitDecision::Submit {
        content: draft.to_owned(),
    }
}

/// Writes `initial`, runs the editor against `draft_path`, then reads the draft back.
///
/// # Errors
/// Missing editor, IO, or spawn failures. Conflict is a successful decision, not an error.
pub fn run_editor<R: CommandRunner>(
    runner: &R,
    argv: &EditorArgv,
    draft_path: &Path,
    initial: &str,
) -> Result<String, TuiError> {
    if let Some(parent) = draft_path.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(draft_path, initial)?;
    let mut command_args = argv.args.clone();
    command_args.push(draft_path.display().to_string());
    match runner.run_foreground(&argv.program, &command_args) {
        Ok(_status) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Err(TuiError::EditorNotConfigured);
        }
        Err(error) => return Err(TuiError::io(error.to_string())),
    }
    Ok(fs::read_to_string(draft_path)?)
}

#[must_use]
pub fn draft_path(drafts_dir: &Path, operation_id: &str) -> PathBuf {
    drafts_dir.join(format!("{operation_id}.md"))
}

fn nonempty(value: Option<&str>) -> Option<&str> {
    value.filter(|text| !text.trim().is_empty())
}

fn split_spec(spec: &str) -> EditorArgv {
    let mut parts = spec.split_whitespace().map(str::to_string);
    parts.next().map_or_else(
        || EditorArgv {
            program: String::new(),
            args: Vec::new(),
        },
        |program| EditorArgv {
            program,
            args: parts.collect(),
        },
    )
}
