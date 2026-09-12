use std::fs::{self, OpenOptions};
use std::io::{ErrorKind, Write};
use std::path::{Path, PathBuf};

use lomo_application::calendar::{DateFormat, parse_pattern};
use serde::Deserialize;

use crate::error::TuiError;
use crate::xdg::RuntimePaths;

/// User-facing TUI configuration loaded from `$XDG_CONFIG_HOME/lomo/config.toml`.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AppConfig {
    pub workspace: PathBuf,
    pub time_zone: String,
    pub date_format: DateFormat,
    pub editor: Option<Vec<String>>,
    pub player: Vec<String>,
}

#[derive(Deserialize)]
struct FileConfig {
    workspace: String,
    #[serde(default)]
    time_zone: Option<String>,
    #[serde(default)]
    date_format: Option<String>,
    #[serde(default)]
    editor: Option<TomlEditor>,
    #[serde(default)]
    player: Option<Vec<String>>,
}

#[derive(Deserialize)]
#[serde(untagged)]
enum TomlEditor {
    Program(String),
    Argv(Vec<String>),
}

/// CLI verbs. Unknown flags fail closed.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum CliAction {
    Help,
    Version,
    Run { workspace_override: Option<PathBuf> },
}

/// Parses argv after the binary name.
///
/// # Errors
/// Unknown flags.
pub fn parse_cli(args: impl IntoIterator<Item = String>) -> Result<CliAction, TuiError> {
    let mut args = args.into_iter();
    let _binary = args.next();
    match args.next().as_deref() {
        None => Ok(CliAction::Run {
            workspace_override: None,
        }),
        Some("--help" | "-h") => Ok(CliAction::Help),
        Some("--version" | "-V") => Ok(CliAction::Version),
        Some(flag) if flag.starts_with('-') => Err(TuiError::config(format!(
            "unknown argument {flag}; try --help"
        ))),
        Some(path) => {
            if args.next().is_some() {
                return Err(TuiError::config("unexpected extra arguments"));
            }
            Ok(CliAction::Run {
                workspace_override: Some(PathBuf::from(path)),
            })
        }
    }
}

/// Loads config.toml and applies an optional workspace override.
///
/// Missing `config.toml` is a first-run state: the file is created with the CLI workspace
/// if provided, otherwise `$HOME/Notes` / `$XDG_DATA_HOME/lomo/notes`. An existing file is
/// never overwritten. Invalid TOML and empty workspace still fail closed.
///
/// # Errors
/// Unreadable existing file, invalid TOML, empty workspace, unknown date format, or missing
/// file with no mint target.
pub fn load_config(
    paths: &RuntimePaths,
    workspace_override: Option<&Path>,
) -> Result<AppConfig, TuiError> {
    let file = paths.config_dir.join("config.toml");
    let raw = match fs::read_to_string(&file) {
        Ok(raw) => raw,
        Err(error) if error.kind() == ErrorKind::NotFound => {
            mint_first_run_config(&file, paths, workspace_override)?
        }
        Err(error) => {
            return Err(TuiError::config(format!(
                "cannot read {}: {error}",
                file.display()
            )));
        }
    };
    parse_config_toml(&raw, workspace_override)
}

fn mint_first_run_config(
    file: &Path,
    paths: &RuntimePaths,
    workspace_override: Option<&Path>,
) -> Result<String, TuiError> {
    let workspace = workspace_override
        .map(Path::to_path_buf)
        .or_else(|| paths.default_workspace.clone())
        .ok_or_else(|| {
            TuiError::config(
                "config.toml is missing and no default workspace is available; set HOME or pass a workspace path",
            )
        })?;
    fs::create_dir_all(&paths.config_dir)?;
    let contents = first_run_toml(&workspace)?;
    match OpenOptions::new().write(true).create_new(true).open(file) {
        Ok(mut out) => {
            out.write_all(contents.as_bytes())?;
            Ok(contents)
        }
        Err(error) if error.kind() == ErrorKind::AlreadyExists => {
            fs::read_to_string(file).map_err(|read_error| {
                TuiError::config(format!("cannot read {}: {read_error}", file.display()))
            })
        }
        Err(error) => Err(TuiError::config(format!(
            "cannot create {}: {error}",
            file.display()
        ))),
    }
}

fn first_run_toml(workspace: &Path) -> Result<String, TuiError> {
    let text = workspace
        .to_str()
        .ok_or_else(|| TuiError::config("workspace path must be UTF-8"))?;
    if text.trim().is_empty() {
        return Err(TuiError::config("workspace path must be non-empty"));
    }
    if text.contains(['\n', '\r']) {
        return Err(TuiError::config("workspace path must not contain newlines"));
    }
    let escaped = text.replace('\\', "\\\\").replace('"', "\\\"");
    Ok(format!(
        "# Created on first run. Change workspace to your Markdown notes directory.\n\
         workspace = \"{escaped}\"\n\
         time_zone = \"UTC\"\n\
         # editor = [\"helix\"]\n\
         # If editor is omitted, lomo uses $VISUAL then $EDITOR. It never defaults to vim.\n"
    ))
}

/// Parses a config document. Used by tests without touching XDG.
///
/// # Errors
/// Invalid TOML or empty workspace path.
pub fn parse_config_toml(
    raw: &str,
    workspace_override: Option<&Path>,
) -> Result<AppConfig, TuiError> {
    let parsed: FileConfig =
        toml::from_str(raw).map_err(|error| TuiError::config(error.to_string()))?;
    if parsed.workspace.trim().is_empty() {
        return Err(TuiError::config("workspace path must be non-empty"));
    }
    let workspace = match workspace_override {
        Some(path) => path.to_path_buf(),
        None => PathBuf::from(parsed.workspace),
    };
    let date_format = match parsed.date_format {
        Some(pattern) => {
            parse_pattern(&pattern).map_err(|error| TuiError::config(error.to_string()))?
        }
        None => DateFormat::default(),
    };
    let editor = match parsed.editor {
        Some(TomlEditor::Program(program)) if program.trim().is_empty() => None,
        Some(TomlEditor::Program(program)) => Some(vec![program]),
        Some(TomlEditor::Argv(argv)) => {
            if argv.first().is_some_and(|program| !program.is_empty()) {
                Some(argv)
            } else {
                None
            }
        }
        None => None,
    };
    Ok(AppConfig {
        workspace,
        time_zone: parsed
            .time_zone
            .filter(|zone| !zone.is_empty())
            .unwrap_or_else(|| "UTC".to_owned()),
        date_format,
        editor,
        player: parsed
            .player
            .filter(|argv| argv.first().is_some_and(|program| !program.is_empty()))
            .unwrap_or_else(|| vec!["xdg-open".to_owned()]),
    })
}

pub const HELP_TEXT: &str = "\
lomo — Linux TUI for a Lomo workspace

Usage:
  lomo [--help|--version] [workspace]

Keys:
  j/k or arrows   select a memo / scroll full text
  Enter / Esc     read / return
  n               quick capture (Enter inserts a newline)
  Ctrl+s          submit capture
  Ctrl+e / e      edit capture / existing memo in external editor
  / t c           search / tags / date
  Ctrl+f          toggle fulltext / fuzzy and pinyin
  Ctrl+p / .      searchable functions / memo actions
  PageUp/PageDown scroll a page
  ? / q           help / quit (drafts are retained)
";
