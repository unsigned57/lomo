use std::fs::{self, OpenOptions};
use std::io::{ErrorKind, Write};
use std::path::{Path, PathBuf};

use lomo_application::calendar::{DateFormat, parse_pattern, system_zone_name, validate_zone};
use serde::Deserialize;

use crate::error::TuiError;
use crate::xdg::{RuntimePaths, initialized_workspace};

/// User-facing TUI configuration loaded from `<config base>/lomo/config.toml`
/// (`$XDG_CONFIG_HOME`, `~/Library/Application Support`, or `%APPDATA%`).
///
/// Every field is fully validated at the parse boundary: unknown TOML keys,
/// blank strings, unknown zones, unrecognized date patterns, and empty command
/// specifications are configuration errors — never silently defaulted.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AppConfig {
    pub workspace: PathBuf,
    /// Host root that owns `.lomo-media-stage`; defaults to `workspace` when
    /// `media_dir` is absent from the file.
    pub media_dir: PathBuf,
    pub time_zone: String,
    pub date_format: DateFormat,
    /// Explicit editor argv; `None` falls back to `$VISUAL` then `$EDITOR`.
    pub editor: Option<Vec<String>>,
    pub player: Vec<String>,
}

/// One row of the canonical configuration field table.
///
/// `config.toml` parsing/serialization, the Settings screen, settings editing,
/// and hot-reload versus restart-required semantics are all projections of
/// this single registry — they cannot drift apart.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SettingsField {
    Workspace,
    MediaDir,
    TimeZone,
    DateFormat,
    Editor,
    Player,
}

/// Settings fields in canonical TOML/display order.
pub const SETTINGS_FIELDS: &[SettingsField] = &[
    SettingsField::Workspace,
    SettingsField::MediaDir,
    SettingsField::TimeZone,
    SettingsField::DateFormat,
    SettingsField::Editor,
    SettingsField::Player,
];

/// Whether a field applies to a live runtime or only to the next launch.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ReloadMode {
    /// Applied in place by `apply_reload`; the next consumer sees it.
    Hot,
    /// Bound into `WorkspaceSession`/capabilities at open; needs restart.
    Restart,
}

/// A typed value for one settings field, produced by [`SettingsField::parse_edit`].
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum FieldValue {
    Workspace(PathBuf),
    MediaDir(PathBuf),
    TimeZone(String),
    DateFormat(DateFormat),
    /// `None` clears the override so `$VISUAL`/`$EDITOR` apply.
    Editor(Option<Vec<String>>),
    /// Always resolved: clearing the field restores [`default_player`].
    Player(Vec<String>),
}

impl SettingsField {
    /// The `config.toml` key this field serializes under.
    #[must_use]
    pub const fn key(self) -> &'static str {
        match self {
            Self::Workspace => "workspace",
            Self::MediaDir => "media_dir",
            Self::TimeZone => "time_zone",
            Self::DateFormat => "date_format",
            Self::Editor => "editor",
            Self::Player => "player",
        }
    }

    /// Whether a changed value lands on the running app or only on restart.
    ///
    /// `editor`/`player` are read at spawn time so they are genuinely hot;
    /// `workspace`, `media_dir`, `time_zone` and `date_format` are bound into
    /// `WorkspaceSessionConfig` and capability roots at open, so pretending
    /// they apply live would lie to the user.
    #[must_use]
    pub const fn reload(self) -> ReloadMode {
        match self {
            Self::Editor | Self::Player => ReloadMode::Hot,
            Self::Workspace | Self::MediaDir | Self::TimeZone | Self::DateFormat => {
                ReloadMode::Restart
            }
        }
    }

    /// The field's current typed value.
    #[must_use]
    pub fn value(self, config: &AppConfig) -> FieldValue {
        match self {
            Self::Workspace => FieldValue::Workspace(config.workspace.clone()),
            Self::MediaDir => FieldValue::MediaDir(config.media_dir.clone()),
            Self::TimeZone => FieldValue::TimeZone(config.time_zone.clone()),
            Self::DateFormat => FieldValue::DateFormat(config.date_format),
            Self::Editor => FieldValue::Editor(config.editor.clone()),
            Self::Player => FieldValue::Player(config.player.clone()),
        }
    }

    /// Human-readable current value for the Settings list.
    ///
    /// The shown text is also the inline-edit seed, so it must be a lossless
    /// serialization of the typed value: `parse_edit(display_value(v)) == v`
    /// for every field. Command fields therefore render through
    /// `command_display`, the inverse of the line `parse_edit` reads.
    #[must_use]
    pub fn display_value(self, config: &AppConfig) -> String {
        match self.value(config) {
            FieldValue::Workspace(path) | FieldValue::MediaDir(path) => path.display().to_string(),
            FieldValue::TimeZone(zone) => zone,
            FieldValue::DateFormat(format) => format.pattern().to_owned(),
            FieldValue::Editor(Some(argv)) | FieldValue::Player(argv) => command_display(&argv),
            FieldValue::Editor(None) => String::new(),
        }
    }

    /// The TOML `key = value` line for this field.
    ///
    /// A cleared `editor` renders as a comment so the round-trip parse keeps
    /// `None` instead of minting a phantom argv.
    #[must_use]
    pub fn toml_line(self, config: &AppConfig) -> String {
        let key = self.key();
        match self.value(config) {
            FieldValue::Workspace(path) | FieldValue::MediaDir(path) => {
                format!("{key} = {}", toml_string(&path.display().to_string()))
            }
            FieldValue::TimeZone(zone) => format!("{key} = {}", toml_string(&zone)),
            FieldValue::DateFormat(format) => {
                format!("{key} = {}", toml_string(format.pattern()))
            }
            FieldValue::Editor(Some(argv)) | FieldValue::Player(argv) => {
                let items = argv
                    .iter()
                    .map(|arg| toml_string(arg))
                    .collect::<Vec<_>>()
                    .join(", ");
                format!("{key} = [{items}]")
            }
            FieldValue::Editor(None) => format!("# {key} = [\"helix\"]"),
        }
    }

    /// Parses one settings-editor input line into a typed value.
    ///
    /// Empty input clears `editor` (env fallback) and resets `player` to the
    /// platform default; for every other field it is a validation error.
    ///
    /// # Errors
    /// Blank required value, bad path/zone/pattern, or unparsable command spec.
    pub fn parse_edit(self, input: &str, home_dir: Option<&Path>) -> Result<FieldValue, TuiError> {
        let trimmed = input.trim();
        match self {
            Self::Workspace => Ok(FieldValue::Workspace(normalize_config_dir(
                self.key(),
                trimmed,
                home_dir,
            )?)),
            Self::MediaDir => Ok(FieldValue::MediaDir(normalize_config_dir(
                self.key(),
                trimmed,
                home_dir,
            )?)),
            Self::TimeZone => {
                let zone = required(self.key(), trimmed)?;
                validate_zone(zone)
                    .map_err(|error| TuiError::config(format!("time_zone: {error}")))?;
                Ok(FieldValue::TimeZone(zone.to_owned()))
            }
            Self::DateFormat => {
                let pattern = required(self.key(), trimmed)?;
                let format = parse_pattern(pattern)
                    .map_err(|error| TuiError::config(format!("date_format: {error}")))?;
                Ok(FieldValue::DateFormat(format))
            }
            Self::Editor => {
                if trimmed.is_empty() {
                    return Ok(FieldValue::Editor(None));
                }
                Ok(FieldValue::Editor(Some(parse_command_edit(
                    self.key(),
                    trimmed,
                )?)))
            }
            Self::Player => {
                if trimmed.is_empty() {
                    return Ok(FieldValue::Player(default_player()));
                }
                Ok(FieldValue::Player(parse_command_edit(self.key(), trimmed)?))
            }
        }
    }

    /// Writes a parsed value back into the config. The value's variant must
    /// match the field — it does, because only [`SettingsField::parse_edit`]
    /// and [`SettingsField::value`] construct `FieldValue`s.
    pub fn apply(self, config: &mut AppConfig, value: FieldValue) {
        match (self, value) {
            (Self::Workspace, FieldValue::Workspace(v)) => config.workspace = v,
            (Self::MediaDir, FieldValue::MediaDir(v)) => config.media_dir = v,
            (Self::TimeZone, FieldValue::TimeZone(v)) => config.time_zone = v,
            (Self::DateFormat, FieldValue::DateFormat(v)) => config.date_format = v,
            (Self::Editor, FieldValue::Editor(v)) => config.editor = v,
            (Self::Player, FieldValue::Player(v)) => config.player = v,
            (field, value) => unreachable!(
                "FieldValue::{value:?} can never target {field:?}; parse_edit pins the pairing"
            ),
        }
    }
}

/// What a config reload did to the live config: which hot fields were applied
/// in place and which differing fields only take effect on restart.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct ReloadOutcome {
    pub applied: Vec<SettingsField>,
    pub restart_pending: Vec<SettingsField>,
}

/// Applies a freshly parsed config to the live one.
///
/// Hot fields are written in place; differing restart-required fields are
/// named in the outcome so the UI can say exactly what a restart will change
/// instead of silently keeping or silently mutating session-bound state.
pub fn apply_reload(current: &mut AppConfig, next: &AppConfig) -> ReloadOutcome {
    let mut outcome = ReloadOutcome::default();
    for &field in SETTINGS_FIELDS {
        if field.value(current) == field.value(next) {
            continue;
        }
        match field.reload() {
            ReloadMode::Hot => {
                field.apply(current, field.value(next));
                outcome.applied.push(field);
            }
            ReloadMode::Restart => outcome.restart_pending.push(field),
        }
    }
    outcome
}

/// What probing `config.toml` found — before any persistent side effect.
#[derive(Clone, Debug)]
pub enum ConfigProbe {
    /// An existing file parsed and validated completely.
    Ready { config: AppConfig, file: PathBuf },
    /// No `config.toml` exists. `proposal` is what the setup wizard shows;
    /// nothing has been written or created.
    FirstRun {
        file: PathBuf,
        proposal: ConfigProposal,
    },
}

/// The first-run proposal the wizard reviews before minting.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ConfigProposal {
    /// Suggested workspace: the CLI override, else `~/Notes` / the data base.
    pub workspace: PathBuf,
    /// Suggested zone: the host's IANA name when known, else UTC.
    pub time_zone: String,
    /// True when the `initialized` marker exists — meaning config.toml was
    /// deleted after a completed setup, so the wizard warns instead of greeting.
    pub previously_initialized: bool,
    /// The workspace recorded by the marker, when present.
    pub recorded_workspace: Option<PathBuf>,
}

/// The canonical config file path for a resolved runtime layout.
#[must_use]
pub fn config_file(paths: &RuntimePaths) -> PathBuf {
    paths.config_dir.join("config.toml")
}

/// Reads `config.toml` if present — without writing anything.
///
/// A missing file is not an error and not a write trigger: it returns
/// [`ConfigProbe::FirstRun`] carrying the wizard's proposal. Persistent side
/// effects happen only in [`mint_config`], after user confirmation.
///
/// # Errors
/// Existing-but-unreadable file, invalid TOML, any failed field validation, or
/// a first run with no suggestible workspace (no HOME/XDG data base and no
/// CLI override).
pub fn probe_config(
    paths: &RuntimePaths,
    workspace_override: Option<&Path>,
) -> Result<ConfigProbe, TuiError> {
    let file = config_file(paths);
    match fs::read_to_string(&file) {
        Ok(raw) => Ok(ConfigProbe::Ready {
            config: parse_config_toml(&raw, workspace_override, paths.home_dir.as_deref())?,
            file,
        }),
        Err(error) if error.kind() == ErrorKind::NotFound => {
            // A deleted config on an initialized install recovers the recorded
            // workspace as the proposal instead of greeting a fresh default.
            // The marker is advisory state: content that is not an absolute
            // workspace path is corruption, not a record — it degrades to the
            // fresh-install proposal rather than failing the probe with a
            // diagnostic that blames a field the user never wrote. Marker IO
            // failures still propagate: unreadable means "cannot tell", which
            // must not masquerade as a fresh install either.
            let recorded_workspace = initialized_workspace(paths)?.and_then(|recorded| {
                if recorded.is_absolute()
                    && !recorded
                        .components()
                        .any(|component| matches!(component, std::path::Component::ParentDir))
                {
                    Some(recorded)
                } else {
                    tracing::warn!(
                        "ignoring corrupt {} marker content {}",
                        crate::xdg::INITIALIZED_MARKER,
                        recorded.display()
                    );
                    None
                }
            });
            let suggested = workspace_override
                .map(Path::to_path_buf)
                .or_else(|| recorded_workspace.clone())
                .or_else(|| paths.default_workspace.clone())
                .ok_or_else(|| {
                    TuiError::config(
                        "config.toml is missing and no default workspace is available; set HOME or pass a workspace path",
                    )
                })?;
            Ok(ConfigProbe::FirstRun {
                file,
                proposal: ConfigProposal {
                    workspace: normalize_workspace(&suggested, paths.home_dir.as_deref())?,
                    time_zone: system_zone_name().unwrap_or_else(|| "UTC".to_owned()),
                    previously_initialized: recorded_workspace.is_some(),
                    recorded_workspace,
                },
            })
        }
        Err(error) => Err(TuiError::config(format!(
            "cannot read {}: {error}",
            file.display()
        ))),
    }
}

/// Re-reads and fully re-validates `config.toml` — the hot-reload path.
///
/// Differs from [`probe_config`] only in honesty about absence: on reload a
/// vanished file is a configuration error, not a silent first-run.
///
/// # Errors
/// Missing file, unreadable file, invalid TOML, or failed field validation.
pub fn reload_config(paths: &RuntimePaths) -> Result<AppConfig, TuiError> {
    let file = config_file(paths);
    let raw = fs::read_to_string(&file)
        .map_err(|error| TuiError::config(format!("cannot read {}: {error}", file.display())))?;
    parse_config_toml(&raw, None, paths.home_dir.as_deref())
}

/// Persists a confirmed config: creates the config directory and workspace,
/// then writes `config.toml`.
///
/// The file is written with `create_new` — an existing config is never
/// overwritten, because clobbering user config is worse than an error.
///
/// # Errors
/// Directory creation or write failures, or a file that already exists.
pub fn mint_config(file: &Path, config: &AppConfig) -> Result<(), TuiError> {
    // Minting a first-run config IS the create-new-library action; opening an
    // existing library never materializes directories itself.
    fs::create_dir_all(&config.workspace)?;
    if let Some(parent) = file.parent() {
        fs::create_dir_all(parent)?;
    }
    let contents = render_config_toml(config);
    let mut out = OpenOptions::new().write(true).create_new(true).open(file)?;
    out.write_all(contents.as_bytes())?;
    Ok(())
}

/// Atomically replaces `config.toml` with the registry-rendered form.
///
/// Used by Settings saves and post-editor installs — writes go through the
/// same field table as everything else, so the file the app maintains and the
/// file the parser accepts can never diverge.
///
/// # Errors
/// Temp-file write or rename failures; the previous file stays untouched.
pub fn save_config(file: &Path, config: &AppConfig) -> Result<(), TuiError> {
    crate::drafts::atomic_write(file, render_config_toml(config).as_bytes())
}

/// Serializes a config through the field registry — the TOML projection of
/// the same table the Settings screen displays.
#[must_use]
pub fn render_config_toml(config: &AppConfig) -> String {
    let mut out = String::from(
        "# lomo TUI configuration. Every key is validated at load;\n\
         # unknown keys, blank values, and unknown timezones are errors.\n",
    );
    for &field in SETTINGS_FIELDS {
        out.push_str(&field.toml_line(config));
        out.push('\n');
    }
    out.push_str(
        "# editor omitted → $VISUAL then $EDITOR (never vim); media_dir omitted → workspace/media.\n",
    );
    out
}

/// Parses a config document. Used by tests without touching XDG.
///
/// # Errors
/// Invalid TOML, unknown keys, blank values, non-absolute `workspace` or
/// `media_dir` (after `~` expansion against `home_dir`), unknown timezone,
/// unknown date format, or empty editor/player command specifications.
pub fn parse_config_toml(
    raw: &str,
    workspace_override: Option<&Path>,
    home_dir: Option<&Path>,
) -> Result<AppConfig, TuiError> {
    let parsed: FileConfig =
        toml::from_str(raw).map_err(|error| TuiError::config(error.to_string()))?;
    // The file's own workspace is validated even when a CLI override binds
    // this run — a broken value would otherwise rot undetected until the day
    // the flag is dropped. The override is a per-run bind, not a validation
    // waiver.
    let file_workspace = normalize_workspace(
        Path::new(required("workspace", &parsed.workspace)?),
        home_dir,
    )?;
    let workspace = match workspace_override {
        Some(path) => normalize_workspace(path, home_dir)?,
        None => file_workspace,
    };
    let media_dir = match parsed.media_dir.as_deref() {
        // The media staging root defaults to the workspace's `media/`
        // convention, matching the Android/client layout.
        None => workspace.join("media"),
        Some(raw) => normalize_config_dir("media_dir", raw, home_dir)?,
    };
    let time_zone = match parsed.time_zone.as_deref() {
        None => "UTC".to_owned(),
        Some(raw) => {
            let zone = required("time_zone", raw)?;
            validate_zone(zone).map_err(|error| TuiError::config(format!("time_zone: {error}")))?;
            zone.to_owned()
        }
    };
    let date_format = match parsed.date_format.as_deref() {
        None => DateFormat::default(),
        Some(raw) => {
            let pattern = required("date_format", raw)?;
            parse_pattern(pattern)
                .map_err(|error| TuiError::config(format!("date_format: {error}")))?
        }
    };
    let editor = parsed
        .editor
        .map(|spec| spec.resolve("editor"))
        .transpose()?;
    let player = parsed
        .player
        .map(|spec| spec.resolve("player"))
        .transpose()?
        .unwrap_or_else(default_player);
    Ok(AppConfig {
        workspace,
        media_dir,
        time_zone,
        date_format,
        editor,
        player,
    })
}

/// A required string field: rejects blanks and silently-padded values
/// (`time_zone = "UTC "` is a typo, not `"UTC"`) instead of defaulting or
/// trimming behind the user's back.
fn required<'a>(key: &str, value: &'a str) -> Result<&'a str, TuiError> {
    if value.trim().is_empty() {
        Err(TuiError::config(format!("{key} must be non-empty")))
    } else if value != value.trim() {
        Err(TuiError::config(format!(
            "{key} must not have surrounding whitespace, got '{value}'"
        )))
    } else {
        Ok(value)
    }
}

/// Normalizes a configured directory field (`media_dir`) exactly like the
/// workspace: `~` expands, `~other`/relative/`..` are configuration errors.
fn normalize_config_dir(
    key: &str,
    raw: &str,
    home_dir: Option<&Path>,
) -> Result<PathBuf, TuiError> {
    let raw = required(key, raw)?;
    normalize_workspace(Path::new(raw), home_dir)
        .map_err(|error| TuiError::config(format!("{key}: {error}")))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct FileConfig {
    workspace: String,
    #[serde(default)]
    media_dir: Option<String>,
    #[serde(default)]
    time_zone: Option<String>,
    #[serde(default)]
    date_format: Option<String>,
    #[serde(default)]
    editor: Option<TomlCommandSpec>,
    #[serde(default)]
    player: Option<TomlCommandSpec>,
}

/// `key = "prog --flag"` or `key = ["prog", "--flag"]` — a shell-like command
/// line or an explicit argv.
#[derive(Deserialize)]
#[serde(untagged)]
enum TomlCommandSpec {
    Command(String),
    Argv(Vec<String>),
}

impl TomlCommandSpec {
    /// # Errors
    /// Blank command line, unbalanced quoting, empty argv, or blank elements.
    fn resolve(self, key: &str) -> Result<Vec<String>, TuiError> {
        match self {
            Self::Command(line) => parse_command_line(key, &line),
            Self::Argv(argv) => {
                if argv.is_empty() || argv.iter().any(|arg| arg.trim().is_empty()) {
                    return Err(TuiError::config(format!(
                        "{key} must name a program: empty argv or blank argument"
                    )));
                }
                Ok(argv)
            }
        }
    }
}

/// Parses `editor = "hx --wait"`-style strings with real quoting rules.
fn parse_command_line(key: &str, line: &str) -> Result<Vec<String>, TuiError> {
    let line = line.trim();
    let argv = shlex::split(line)
        .ok_or_else(|| TuiError::config(format!("{key}: unbalanced quoting in '{line}'")))?;
    if argv.is_empty() || argv.iter().any(String::is_empty) {
        return Err(TuiError::config(format!(
            "{key} must name a program, got '{line}'"
        )));
    }
    Ok(argv)
}

/// Parses a command field's edit text in either spelling the file itself
/// accepts: an explicit argv array (`["hx", "--wait"]` — what
/// `command_display` emits for values a shell line cannot represent) or a
/// shell-like command line (`hx --wait`, quoting rules apply).
fn parse_command_edit(key: &str, input: &str) -> Result<Vec<String>, TuiError> {
    if input.starts_with('[') {
        // A TOML document is key/value pairs — the bare array needs a key to
        // parse, and unknown trailing keys are refused rather than ignored.
        #[derive(Deserialize)]
        #[serde(deny_unknown_fields)]
        struct ArgvSpec {
            argv: Vec<String>,
        }
        let parsed: ArgvSpec = toml::from_str(&format!("argv = {input}"))
            .map_err(|error| TuiError::config(format!("{key}: {error}")))?;
        return TomlCommandSpec::Argv(parsed.argv).resolve(key);
    }
    parse_command_line(key, input)
}

/// The Settings text form of a command argv — the inverse of
/// [`parse_command_edit`]: `shlex::try_join` quotes exactly the elements that
/// need it, so the shown text always re-parses to the same argv. An argv no
/// shell line can represent (a NUL byte, which quoting refuses) or an empty
/// one renders in the field's TOML array spelling instead — a display can
/// never silently rewrite the command on a no-op Enter.
fn command_display(argv: &[String]) -> String {
    if argv.is_empty() {
        // An empty argv is not a usable command (parse would reject it);
        // render its literal so it round-trips into a refused edit rather
        // than an empty string that would parse as a different value.
        return "[]".to_owned();
    }
    shlex::try_join(argv.iter().map(String::as_str)).unwrap_or_else(|_| argv_toml_literal(argv))
}

/// `["a", "b"]` — the field's TOML spelling, used when no shell line can
/// carry the argv (a NUL byte defeats every quoting strategy).
fn argv_toml_literal(argv: &[String]) -> String {
    let items = argv
        .iter()
        .map(|arg| toml_string(arg))
        .collect::<Vec<_>>()
        .join(", ");
    format!("[{items}]")
}

/// Resolves a configured workspace path: `~`/`~/…` expand against `home_dir`,
/// `~other` forms are rejected, and the result must be absolute. A relative
/// binding can only come from a hand-edited file or CLI flag — both are
/// configuration errors, never silently anchored to the process cwd.
fn normalize_workspace(path: &Path, home_dir: Option<&Path>) -> Result<PathBuf, TuiError> {
    let text = path
        .to_str()
        .ok_or_else(|| TuiError::config("workspace path must be UTF-8"))?;
    let resolved = if text == "~" {
        home_dir
            .ok_or_else(|| TuiError::config("workspace '~' requires a home directory"))?
            .to_path_buf()
    } else if let Some(rest) = text.strip_prefix("~/") {
        home_dir
            .ok_or_else(|| TuiError::config("workspace '~/' requires a home directory"))?
            .join(rest)
    } else if text.starts_with('~') {
        return Err(TuiError::config(format!(
            "workspace '{text}' is unsupported; only '~' and '~/' expand"
        )));
    } else {
        path.to_path_buf()
    };
    if !resolved.is_absolute() {
        return Err(TuiError::config(format!(
            "workspace path must be absolute, got '{text}'"
        )));
    }
    // `..` components silently re-anchor the bound root; reject them rather
    // than normalize a path the user never wrote into a different directory.
    if resolved
        .components()
        .any(|component| matches!(component, std::path::Component::ParentDir))
    {
        return Err(TuiError::config(format!(
            "workspace path must not contain '..', got '{text}'"
        )));
    }
    Ok(resolved)
}

/// Escapes a string as a TOML basic string literal.
fn toml_string(value: &str) -> String {
    let mut out = String::with_capacity(value.len() + 2);
    out.push('"');
    for ch in value.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c.is_control() => {
                // TOML basic strings escape control chars as `\uXXXX`; a
                // nibble is < 16 by construction so `from_digit` always
                // yields a digit (control codes are ≤ 0x9F anyway).
                let code = u32::from(c);
                out.push_str("\\u");
                for shift in [12_u32, 8, 4, 0] {
                    out.push(
                        char::from_digit((code >> shift) & 0xF, 16)
                            .unwrap_or('0')
                            .to_ascii_uppercase(),
                    );
                }
            }
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

/// Platform "open with the default handler" argv: the media path is appended
/// as the final argument by [`crate::media::spawn_player`].
#[must_use]
pub fn default_player() -> Vec<String> {
    #[cfg(target_os = "windows")]
    {
        // `Start-Process` on the file opens its registered handler without a
        // visible console window; cmd's `start` would need shell quoting.
        vec![
            "powershell".to_owned(),
            "-NoProfile".to_owned(),
            "-Command".to_owned(),
            "Start-Process".to_owned(),
        ]
    }
    #[cfg(target_os = "macos")]
    {
        vec!["open".to_owned()]
    }
    #[cfg(not(any(target_os = "windows", target_os = "macos")))]
    {
        vec!["xdg-open".to_owned()]
    }
}
