use std::path::PathBuf;

use clap::{CommandFactory, Parser, ValueEnum, error::ErrorKind};
use clap_complete::Shell;

use crate::error::TuiError;

/// CLI verbs. Unknown flags fail closed.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum CliAction {
    Help,
    Version,
    Completions(Shell),
    Run { workspace_override: Option<PathBuf> },
}

/// The key table embedded in `--help` is rendered straight from
/// `KEY_BINDINGS` — the same rows `command_from_key` dispatches on — so an
/// advertised key can never drift from a bound key (E-16).
#[derive(Parser)]
#[command(
    name = "lomo",
    version,
    about = "Terminal UI for a Lomo workspace",
    after_help = crate::event::keys_help()
)]
struct Cli {
    /// Workspace directory to bind for this run; on first run the setup wizard proposes it (only written once you confirm).
    workspace: Option<PathBuf>,
    /// Print shell completions to stdout.
    #[arg(long, value_name = "SHELL")]
    generate_completions: Option<CompletionShell>,
}

/// Shells we ship completions for. Restricting at parse time keeps `--help`
/// honest: unsupported `clap_complete` shells are rejected as invalid values.
#[derive(Clone, Copy, Debug, Eq, PartialEq, ValueEnum)]
enum CompletionShell {
    Bash,
    Zsh,
    Fish,
    #[value(name = "powershell")]
    PowerShell,
}

impl From<CompletionShell> for Shell {
    fn from(shell: CompletionShell) -> Self {
        match shell {
            CompletionShell::Bash => Self::Bash,
            CompletionShell::Zsh => Self::Zsh,
            CompletionShell::Fish => Self::Fish,
            CompletionShell::PowerShell => Self::PowerShell,
        }
    }
}

/// Parses argv including the binary name.
///
/// # Errors
/// Unknown flags, extra positionals, or unsupported shells.
pub fn parse_cli(args: impl IntoIterator<Item = String>) -> Result<CliAction, TuiError> {
    match Cli::try_parse_from(args) {
        Ok(cli) => match cli.generate_completions {
            Some(shell) => Ok(CliAction::Completions(shell.into())),
            None => Ok(CliAction::Run {
                workspace_override: cli.workspace,
            }),
        },
        Err(error) => {
            if error.kind() == ErrorKind::DisplayHelp {
                Ok(CliAction::Help)
            } else if error.kind() == ErrorKind::DisplayVersion {
                Ok(CliAction::Version)
            } else {
                Err(TuiError::config(error.render().to_string()))
            }
        }
    }
}

/// Renders `--help` output: usage, flags, and the key table.
#[must_use]
pub fn render_help() -> String {
    Cli::command().render_long_help().to_string()
}

/// The clap command definition used for shell completion generation.
#[must_use]
pub fn command() -> clap::Command {
    Cli::command()
}
