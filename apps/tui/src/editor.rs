use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, ExitStatus};

use crate::error::TuiError;

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
    crate::drafts::write_draft(draft_path, initial)?;
    let mut command_args = argv.args.clone();
    command_args.push(draft_path.display().to_string());
    match runner.run_foreground(&argv.program, &command_args) {
        Ok(status) if status.success() => {}
        Ok(status) => {
            return Err(TuiError::io(format!(
                "editor exited {status}; draft kept at {}",
                draft_path.display()
            )));
        }
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
