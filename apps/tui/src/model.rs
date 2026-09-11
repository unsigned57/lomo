use lomo_application::SearchMode;

use crate::layout::{Focus, NavPresence};

/// Primary navigation destinations from the platform contract.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Screen {
    Timeline,
    Tasks,
    Review,
    Statistics,
    Attachments,
    Trash,
    Settings,
}

/// Modal overlay. Search is not an overlay so it cannot become a body editor.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Overlay {
    None,
    Help,
    Palette {
        index: usize,
    },
    Alert {
        title: String,
        body: String,
    },
    Confirm {
        title: String,
        body: String,
        action: ConfirmAction,
    },
    Overdue {
        lines: Vec<String>,
    },
    History {
        lines: Vec<String>,
    },
}

/// Destructive or restoring action waiting for y/n.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ConfirmAction {
    Delete,
    Restore,
}

/// Single-line search box. Multiline editing is structurally impossible here.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum SearchSession {
    Closed,
    Open {
        query: String,
        mode: SearchMode,
        epoch: u64,
    },
}

/// One row in the active list pane.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ListRow {
    pub id: String,
    /// Colored time/date prefix, Think-style (` MM-DD HH:mm:ss `).
    pub header: String,
    pub title: String,
    pub subtitle: String,
    /// `Some` marks a todo row so the list can use ✓ / » instead of `[x]`.
    pub done: Option<bool>,
}

impl ListRow {
    #[must_use]
    pub fn new(id: impl Into<String>, title: impl Into<String>) -> Self {
        Self {
            id: id.into(),
            header: String::new(),
            title: title.into(),
            subtitle: String::new(),
            done: None,
        }
    }
}

/// One heatmap cell copied out of `MemoStatistics` so the TEA model stays `Eq`.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct HeatPoint {
    pub year: i32,
    pub month: u8,
    pub day: u8,
    pub count: u64,
}

/// Think statistics surface. Counts are projection facts, not a second authority.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct StatsView {
    pub zone: String,
    pub as_of_year: i32,
    pub as_of_month: u8,
    pub as_of_day: u8,
    pub total_memos: u64,
    pub total_words: u64,
    pub active_days: u64,
    pub current_streak: u64,
    pub longest_streak: u64,
    pub this_week: u64,
    pub this_month: u64,
    pub this_year: u64,
    pub daily: Vec<HeatPoint>,
}

/// TEA model. Session IO is expressed as [`crate::update::Effect`], not performed here.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AppModel {
    pub screen: Screen,
    pub focus: Focus,
    pub nav: NavPresence,
    pub width: u16,
    pub height: u16,
    pub overlay: Overlay,
    pub search: SearchSession,
    pub search_mode: SearchMode,
    pub search_epoch: u64,
    pub status: String,
    pub selected: usize,
    pub nav_selected: usize,
    pub items: Vec<ListRow>,
    pub preview: String,
    pub settings_lines: Vec<String>,
    pub stats: Option<StatsView>,
}

impl AppModel {
    #[must_use]
    pub const fn new(width: u16, height: u16) -> Self {
        Self {
            screen: Screen::Timeline,
            focus: Focus::List,
            nav: NavPresence::Hidden,
            width,
            height,
            overlay: Overlay::None,
            search: SearchSession::Closed,
            search_mode: SearchMode::Fulltext,
            search_epoch: 0,
            status: String::new(),
            selected: 0,
            nav_selected: 0,
            items: Vec::new(),
            preview: String::new(),
            settings_lines: Vec::new(),
            stats: None,
        }
    }

    #[must_use]
    pub fn selected_id(&self) -> Option<&str> {
        self.items.get(self.selected).map(|row| row.id.as_str())
    }

    pub fn set_status(&mut self, text: &str) {
        self.status.clear();
        self.status.push_str(text);
    }

    pub const fn clamp_selection(&mut self) {
        if self.items.is_empty() {
            self.selected = 0;
            return;
        }
        let last = self.items.len().saturating_sub(1);
        if self.selected > last {
            self.selected = last;
        }
    }
}
