//! The Settings view: the interactive projection of the `SettingsField`
//! registry in `config.rs`.
//!
//! Rows, labels, edit validation and reload semantics all come from that one
//! table — this module only adds selection state and localized chrome.
use std::path::PathBuf;

use crate::config::{AppConfig, ReloadMode, SETTINGS_FIELDS, SettingsField};
use crate::i18n::UiStrings;
use crate::input::TextBuffer;

/// One visible settings row: the field, its current rendered value, and
/// whether a change applies live or on restart.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SettingRow {
    pub field: SettingsField,
    pub value: String,
    pub hot: bool,
}

/// The structured Settings view state that replaced `Vec<String>`.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SettingsView {
    /// The config.toml this screen edits — displayed and used by `e`.
    pub file: PathBuf,
    pub rows: Vec<SettingRow>,
    pub selected: usize,
    /// Informational tail lines (device id, language) — not editable fields.
    pub info: Vec<String>,
    /// `~` expansion base for field edits, matching parse-time resolution.
    pub home_dir: Option<PathBuf>,
}

impl SettingsView {
    /// Projects `config` through the registry: one row per `SettingsField`, in
    /// canonical TOML order.
    #[must_use]
    pub fn new(
        config: &AppConfig,
        file: PathBuf,
        home_dir: Option<PathBuf>,
        info: Vec<String>,
    ) -> Self {
        Self {
            file,
            rows: SETTINGS_FIELDS
                .iter()
                .map(|field| SettingRow {
                    field: *field,
                    value: field.display_value(config),
                    hot: field.reload() == ReloadMode::Hot,
                })
                .collect(),
            selected: 0,
            info,
            home_dir,
        }
    }

    /// Re-projects values after a config change without losing the cursor.
    pub fn refresh(&mut self, config: &AppConfig) {
        for row in &mut self.rows {
            row.value = row.field.display_value(config);
        }
        self.selected = self.selected.min(self.rows.len().saturating_sub(1));
    }

    /// The field under the cursor, if any row exists.
    #[must_use]
    pub fn selected_field(&self) -> Option<SettingsField> {
        self.rows.get(self.selected).map(|row| row.field)
    }
}

/// An in-progress inline edit of one settings field.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SettingsEdit {
    pub field: SettingsField,
    pub text: TextBuffer,
    pub error: Option<String>,
}

impl SettingsEdit {
    #[must_use]
    pub fn new(field: SettingsField, current: &str) -> Self {
        Self {
            field,
            text: TextBuffer::new(current.to_owned()),
            error: None,
        }
    }
}

/// Localized label for a settings field — the Settings projection of the
/// registry, parallel to `SettingsField::key`.
#[must_use]
pub const fn field_label(field: SettingsField, s: &UiStrings) -> &'static str {
    match field {
        SettingsField::Workspace => s.text("Workspace", "工作区"),
        SettingsField::MediaDir => s.text("Media directory", "媒体目录"),
        SettingsField::TimeZone => s.text("Time zone", "时区"),
        SettingsField::DateFormat => s.text("Date format", "日期格式"),
        SettingsField::Editor => s.text("Editor", "编辑器"),
        SettingsField::Player => s.text("Player", "播放器"),
    }
}

/// Localized editing hint for a field — what the value means and what empty does.
#[must_use]
pub const fn field_hint(field: SettingsField, s: &UiStrings) -> &'static str {
    match field {
        SettingsField::Workspace => s.text(
            "Absolute path to your notes (~/… expands); applies on restart",
            "笔记目录的绝对路径（~/… 会展开）；重启后生效",
        ),
        SettingsField::MediaDir => s.text(
            "Absolute media staging root (~/… expands); applies on restart",
            "媒体暂存根目录的绝对路径（~/… 会展开）；重启后生效",
        ),
        SettingsField::TimeZone => s.text(
            "IANA zone like Asia/Shanghai; applies on restart",
            "IANA 时区，如 Asia/Shanghai；重启后生效",
        ),
        SettingsField::DateFormat => s.text(
            "One of yyyy_MM_dd · yyyy-MM-dd · yyyy.MM.dd · yyyyMMdd · MM-dd-yyyy; restart applies",
            "可选 yyyy_MM_dd · yyyy-MM-dd · yyyy.MM.dd · yyyyMMdd · MM-dd-yyyy；重启后生效",
        ),
        SettingsField::Editor => s.text(
            "Command line like \"hx --wait\"; empty → $VISUAL/$EDITOR",
            "命令行如 \"hx --wait\"；留空则使用 $VISUAL/$EDITOR",
        ),
        SettingsField::Player => s.text(
            "Command that opens media; empty → platform default",
            "打开媒体的命令；留空则使用系统默认",
        ),
    }
}
