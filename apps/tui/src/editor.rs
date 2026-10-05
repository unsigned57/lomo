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

    /// Spawns `program` as a managed child whose wait runs on a dedicated
    /// monitor, keeping the effect worker free for queries.
    ///
    /// # Errors
    /// Spawn failures, including a missing binary.
    fn spawn_managed(
        &self,
        program: &str,
        args: &[String],
    ) -> Result<Box<dyn ManagedChild>, std::io::Error>;
}

/// A spawned process whose exit is awaited off the effect worker.
pub trait ManagedChild: Send {
    /// # Errors
    /// Wait failures, including an already-reaped child.
    fn wait(&mut self) -> Result<ExitStatus, std::io::Error>;

    /// The piped stderr read end when the spawn captured it — `None` when the
    /// runner cannot pipe (fakes, drivers that keep stdio inherited).
    fn take_stderr(&mut self) -> Option<Box<dyn std::io::Read + Send>> {
        None
    }
}

impl ManagedChild for std::process::Child {
    fn wait(&mut self) -> Result<ExitStatus, std::io::Error> {
        Self::wait(self)
    }

    fn take_stderr(&mut self) -> Option<Box<dyn std::io::Read + Send>> {
        self.stderr
            .take()
            .map(|stderr| -> Box<dyn std::io::Read + Send> { Box::new(stderr) })
    }
}

/// Production runner using `std::process::Command` with an argv vector (no shell).
#[derive(Clone, Copy, Debug, Default)]
pub struct StdCommandRunner;

impl CommandRunner for StdCommandRunner {
    fn run_foreground(&self, program: &str, args: &[String]) -> Result<ExitStatus, std::io::Error> {
        Command::new(program).args(args).status()
    }

    fn spawn_managed(
        &self,
        program: &str,
        args: &[String],
    ) -> Result<Box<dyn ManagedChild>, std::io::Error> {
        // Managed children (the external player) never inherit the TUI's
        // terminal: stdin/stdout are null so no output paints the alternate
        // screen, and stderr is piped so diagnostics reach `PlayerFinished`
        // instead of the frame (D-06).
        let child: Box<dyn ManagedChild> = Box::new(
            Command::new(program)
                .args(args)
                .stdin(std::process::Stdio::null())
                .stdout(std::process::Stdio::null())
                .stderr(std::process::Stdio::piped())
                .spawn()?,
        );
        Ok(child)
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
        return split_spec("VISUAL", spec);
    }
    if let Some(spec) = nonempty(editor_env) {
        return split_spec("EDITOR", spec);
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

/// `$VISUAL`/`$EDITOR` are shell-style command lines — `shlex` parses quoting
/// and escapes so `EDITOR="code --wait"` means argv, not a binary named with
/// spaces. Unbalanced quoting is a configuration error, never a silent misparsed
/// program name.
fn split_spec(name: &str, spec: &str) -> Result<EditorArgv, TuiError> {
    let argv = shlex::split(spec)
        .ok_or_else(|| TuiError::config(format!("${name} has unbalanced quoting: {spec}")))?;
    let mut parts = argv.into_iter();
    match parts.next() {
        Some(program) if !program.is_empty() => Ok(EditorArgv {
            program,
            args: parts.collect(),
        }),
        Some(_) | None => Err(TuiError::config(format!(
            "${name} does not name a program: {spec}"
        ))),
    }
}
