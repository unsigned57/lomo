use crossterm::event::{KeyCode, KeyEvent, KeyEventKind, KeyModifiers};

use crate::model::{Overlay, Screen, SearchSession};

/// User-level command after key translation. Search never becomes a body editor.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Command {
    Quit,
    HelpToggle,
    PaletteToggle,
    FocusNext,
    MoveUp,
    MoveDown,
    OpenSearch,
    SearchChar(char),
    SearchBackspace,
    SearchSubmit,
    SearchCancel,
    ToggleSearchMode,
    NewMemo,
    EditMemo,
    ToggleTask,
    ConfirmYes,
    ConfirmNo,
    Cancel,
    Goto(Screen),
    ToggleNavDrawer,
    PlayAttachment,
    ImportClipboard,
    PinSelected,
    DeleteSelected,
    RestoreSelected,
    ShowHistory,
    None,
}

/// Overlay class used for key routing without carrying overlay payloads.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum OverlayKind {
    None,
    Help,
    Palette,
    Alert,
    Confirm,
    Overdue,
    History,
}

/// Key-routing context derived from the current model.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct InputContext {
    pub overlay: OverlayKind,
    pub search_open: bool,
}

impl InputContext {
    #[must_use]
    pub const fn from_model(overlay: &Overlay, search: &SearchSession) -> Self {
        Self {
            overlay: overlay_kind(overlay),
            search_open: matches!(search, SearchSession::Open { .. }),
        }
    }
}

/// Maps one crossterm key into a command. Repeat/release events are ignored.
#[must_use]
pub fn command_from_key(key: KeyEvent, ctx: InputContext) -> Command {
    if key.kind != KeyEventKind::Press {
        return Command::None;
    }
    match ctx.overlay {
        OverlayKind::None if ctx.search_open => search_keys(key),
        OverlayKind::None => idle_keys(key),
        OverlayKind::Help => help_keys(key),
        OverlayKind::Palette => palette_keys(key),
        OverlayKind::Confirm => confirm_keys(key),
        OverlayKind::Alert | OverlayKind::Overdue | OverlayKind::History => dismiss_keys(key),
    }
}

/// Palette rows in display order. Indices are the command contract.
pub const PALETTE_LABELS: [&str; 11] = [
    "Timeline",
    "Tasks & reminders",
    "Daily review",
    "Statistics",
    "Attachments",
    "Trash",
    "Settings",
    "New memo",
    "Edit memo",
    "Toggle search mode",
    "Quit",
];

/// Resolves a selected palette row into a command.
#[must_use]
pub const fn palette_command(index: usize) -> Command {
    match index {
        0 => Command::Goto(Screen::Timeline),
        1 => Command::Goto(Screen::Tasks),
        2 => Command::Goto(Screen::Review),
        3 => Command::Goto(Screen::Statistics),
        4 => Command::Goto(Screen::Attachments),
        5 => Command::Goto(Screen::Trash),
        6 => Command::Goto(Screen::Settings),
        7 => Command::NewMemo,
        8 => Command::EditMemo,
        9 => Command::ToggleSearchMode,
        10 => Command::Quit,
        _ => Command::None,
    }
}

#[must_use]
pub const fn nav_screen(focus_row: usize) -> Option<Screen> {
    match focus_row {
        0 => Some(Screen::Timeline),
        1 => Some(Screen::Tasks),
        2 => Some(Screen::Review),
        3 => Some(Screen::Statistics),
        4 => Some(Screen::Attachments),
        5 => Some(Screen::Trash),
        6 => Some(Screen::Settings),
        _ => None,
    }
}

#[must_use]
pub const fn screen_row(screen: Screen) -> usize {
    match screen {
        Screen::Timeline => 0,
        Screen::Tasks => 1,
        Screen::Review => 2,
        Screen::Statistics => 3,
        Screen::Attachments => 4,
        Screen::Trash => 5,
        Screen::Settings => 6,
    }
}

#[must_use]
pub const fn nav_row_count() -> usize {
    7
}

const fn overlay_kind(overlay: &Overlay) -> OverlayKind {
    match overlay {
        Overlay::None => OverlayKind::None,
        Overlay::Help => OverlayKind::Help,
        Overlay::Palette { .. } => OverlayKind::Palette,
        Overlay::Alert { .. } => OverlayKind::Alert,
        Overlay::Confirm { .. } => OverlayKind::Confirm,
        Overlay::Overdue { .. } => OverlayKind::Overdue,
        Overlay::History { .. } => OverlayKind::History,
    }
}

const fn idle_keys(key: KeyEvent) -> Command {
    if key.modifiers.contains(KeyModifiers::CONTROL) {
        return match key.code {
            KeyCode::Char('p') => Command::PaletteToggle,
            KeyCode::Char('f') => Command::ToggleSearchMode,
            KeyCode::Char('c') => Command::Quit,
            KeyCode::Char(_)
            | KeyCode::Backspace
            | KeyCode::Enter
            | KeyCode::Left
            | KeyCode::Right
            | KeyCode::Up
            | KeyCode::Down
            | KeyCode::Home
            | KeyCode::End
            | KeyCode::PageUp
            | KeyCode::PageDown
            | KeyCode::Tab
            | KeyCode::BackTab
            | KeyCode::Delete
            | KeyCode::Insert
            | KeyCode::F(_)
            | KeyCode::Null
            | KeyCode::Esc
            | KeyCode::CapsLock
            | KeyCode::ScrollLock
            | KeyCode::NumLock
            | KeyCode::PrintScreen
            | KeyCode::Pause
            | KeyCode::Menu
            | KeyCode::KeypadBegin
            | KeyCode::Media(_)
            | KeyCode::Modifier(_) => Command::None,
        };
    }
    match key.code {
        KeyCode::Char('q') => Command::Quit,
        KeyCode::Char('?') => Command::HelpToggle,
        KeyCode::Char('/') => Command::OpenSearch,
        KeyCode::Char('n') => Command::NewMemo,
        KeyCode::Char('e') => Command::EditMemo,
        KeyCode::Char(' ' | 'x') => Command::ToggleTask,
        KeyCode::Char('j') | KeyCode::Down => Command::MoveDown,
        KeyCode::Char('k') | KeyCode::Up => Command::MoveUp,
        KeyCode::Char('[') => Command::ToggleNavDrawer,
        KeyCode::Char('p') => Command::ImportClipboard,
        KeyCode::Char('a') => Command::PlayAttachment,
        KeyCode::Char('m') => Command::PinSelected,
        KeyCode::Char('d') => Command::DeleteSelected,
        KeyCode::Char('r') => Command::RestoreSelected,
        KeyCode::Char('h') => Command::ShowHistory,
        KeyCode::Tab => Command::FocusNext,
        KeyCode::Esc => Command::Cancel,
        KeyCode::Char(_)
        | KeyCode::Backspace
        | KeyCode::Enter
        | KeyCode::Left
        | KeyCode::Right
        | KeyCode::Home
        | KeyCode::End
        | KeyCode::PageUp
        | KeyCode::PageDown
        | KeyCode::BackTab
        | KeyCode::Delete
        | KeyCode::Insert
        | KeyCode::F(_)
        | KeyCode::Null
        | KeyCode::CapsLock
        | KeyCode::ScrollLock
        | KeyCode::NumLock
        | KeyCode::PrintScreen
        | KeyCode::Pause
        | KeyCode::Menu
        | KeyCode::KeypadBegin
        | KeyCode::Media(_)
        | KeyCode::Modifier(_) => Command::None,
    }
}

const fn search_keys(key: KeyEvent) -> Command {
    if key.modifiers.contains(KeyModifiers::CONTROL) {
        return match key.code {
            KeyCode::Char('f') => Command::ToggleSearchMode,
            KeyCode::Char('p') => Command::PaletteToggle,
            KeyCode::Char(_)
            | KeyCode::Backspace
            | KeyCode::Enter
            | KeyCode::Left
            | KeyCode::Right
            | KeyCode::Up
            | KeyCode::Down
            | KeyCode::Home
            | KeyCode::End
            | KeyCode::PageUp
            | KeyCode::PageDown
            | KeyCode::Tab
            | KeyCode::BackTab
            | KeyCode::Delete
            | KeyCode::Insert
            | KeyCode::F(_)
            | KeyCode::Null
            | KeyCode::Esc
            | KeyCode::CapsLock
            | KeyCode::ScrollLock
            | KeyCode::NumLock
            | KeyCode::PrintScreen
            | KeyCode::Pause
            | KeyCode::Menu
            | KeyCode::KeypadBegin
            | KeyCode::Media(_)
            | KeyCode::Modifier(_) => Command::None,
        };
    }
    match key.code {
        KeyCode::Esc => Command::SearchCancel,
        KeyCode::Enter => Command::SearchSubmit,
        KeyCode::Backspace => Command::SearchBackspace,
        KeyCode::Char(ch) => Command::SearchChar(ch),
        KeyCode::Left
        | KeyCode::Right
        | KeyCode::Up
        | KeyCode::Down
        | KeyCode::Home
        | KeyCode::End
        | KeyCode::PageUp
        | KeyCode::PageDown
        | KeyCode::Tab
        | KeyCode::BackTab
        | KeyCode::Delete
        | KeyCode::Insert
        | KeyCode::F(_)
        | KeyCode::Null
        | KeyCode::CapsLock
        | KeyCode::ScrollLock
        | KeyCode::NumLock
        | KeyCode::PrintScreen
        | KeyCode::Pause
        | KeyCode::Menu
        | KeyCode::KeypadBegin
        | KeyCode::Media(_)
        | KeyCode::Modifier(_) => Command::None,
    }
}

const fn help_keys(key: KeyEvent) -> Command {
    match key.code {
        KeyCode::Esc | KeyCode::Char('?') => Command::HelpToggle,
        KeyCode::Char('q') => Command::Quit,
        KeyCode::Char(_)
        | KeyCode::Backspace
        | KeyCode::Enter
        | KeyCode::Left
        | KeyCode::Right
        | KeyCode::Up
        | KeyCode::Down
        | KeyCode::Home
        | KeyCode::End
        | KeyCode::PageUp
        | KeyCode::PageDown
        | KeyCode::Tab
        | KeyCode::BackTab
        | KeyCode::Delete
        | KeyCode::Insert
        | KeyCode::F(_)
        | KeyCode::Null
        | KeyCode::CapsLock
        | KeyCode::ScrollLock
        | KeyCode::NumLock
        | KeyCode::PrintScreen
        | KeyCode::Pause
        | KeyCode::Menu
        | KeyCode::KeypadBegin
        | KeyCode::Media(_)
        | KeyCode::Modifier(_) => Command::None,
    }
}

const fn palette_keys(key: KeyEvent) -> Command {
    match key.code {
        KeyCode::Esc => Command::Cancel,
        KeyCode::Enter => Command::ConfirmYes,
        KeyCode::Char('j') | KeyCode::Down => Command::MoveDown,
        KeyCode::Char('k') | KeyCode::Up => Command::MoveUp,
        KeyCode::Char('q') => Command::Quit,
        KeyCode::Char(_)
        | KeyCode::Backspace
        | KeyCode::Left
        | KeyCode::Right
        | KeyCode::Home
        | KeyCode::End
        | KeyCode::PageUp
        | KeyCode::PageDown
        | KeyCode::Tab
        | KeyCode::BackTab
        | KeyCode::Delete
        | KeyCode::Insert
        | KeyCode::F(_)
        | KeyCode::Null
        | KeyCode::CapsLock
        | KeyCode::ScrollLock
        | KeyCode::NumLock
        | KeyCode::PrintScreen
        | KeyCode::Pause
        | KeyCode::Menu
        | KeyCode::KeypadBegin
        | KeyCode::Media(_)
        | KeyCode::Modifier(_) => Command::None,
    }
}

const fn confirm_keys(key: KeyEvent) -> Command {
    match key.code {
        KeyCode::Char('y') | KeyCode::Enter => Command::ConfirmYes,
        KeyCode::Char('n') | KeyCode::Esc => Command::ConfirmNo,
        KeyCode::Char(_)
        | KeyCode::Backspace
        | KeyCode::Left
        | KeyCode::Right
        | KeyCode::Up
        | KeyCode::Down
        | KeyCode::Home
        | KeyCode::End
        | KeyCode::PageUp
        | KeyCode::PageDown
        | KeyCode::Tab
        | KeyCode::BackTab
        | KeyCode::Delete
        | KeyCode::Insert
        | KeyCode::F(_)
        | KeyCode::Null
        | KeyCode::CapsLock
        | KeyCode::ScrollLock
        | KeyCode::NumLock
        | KeyCode::PrintScreen
        | KeyCode::Pause
        | KeyCode::Menu
        | KeyCode::KeypadBegin
        | KeyCode::Media(_)
        | KeyCode::Modifier(_) => Command::None,
    }
}

const fn dismiss_keys(key: KeyEvent) -> Command {
    match key.code {
        KeyCode::Esc | KeyCode::Enter | KeyCode::Char(' ') => Command::Cancel,
        KeyCode::Char('q') => Command::Quit,
        KeyCode::Char(_)
        | KeyCode::Backspace
        | KeyCode::Left
        | KeyCode::Right
        | KeyCode::Up
        | KeyCode::Down
        | KeyCode::Home
        | KeyCode::End
        | KeyCode::PageUp
        | KeyCode::PageDown
        | KeyCode::Tab
        | KeyCode::BackTab
        | KeyCode::Delete
        | KeyCode::Insert
        | KeyCode::F(_)
        | KeyCode::Null
        | KeyCode::CapsLock
        | KeyCode::ScrollLock
        | KeyCode::NumLock
        | KeyCode::PrintScreen
        | KeyCode::Pause
        | KeyCode::Menu
        | KeyCode::KeypadBegin
        | KeyCode::Media(_)
        | KeyCode::Modifier(_) => Command::None,
    }
}
