//! Contextual key translation. Input modes consume characters before browsing shortcuts.
use crossterm::event::{KeyCode, KeyEvent, KeyEventKind, KeyModifiers};
use lomo_core::RelativeWorkspacePath;

use crate::model::{AppModel, InputMode, Screen};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TextEdit {
    Left,
    Right,
    Up,
    Down,
    Home,
    End,
    Backspace,
    Delete,
    Undo,
    Redo,
    Newline,
    Complete,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Command {
    Quit,
    Help,
    Functions,
    Actions,
    Back,
    Accept,
    Compose,
    Commit,
    ExternalEdit,
    Search,
    Tags,
    Date,
    CustomDate,
    RemoveKeyword,
    RemoveDate,
    ShowNotice,
    ToggleSearchMode,
    ToggleTagScope,
    ClearFilters,
    DiscardDraft,
    Type(String),
    Edit(TextEdit),
    Move(i32),
    Scroll(i32),
    Page(i32),
    First,
    Last,
    Goto(Screen),
    SelectTag(Option<String>),
    SetDate(String),
    Pin,
    Delete,
    Restore,
    History,
    ToggleTask,
    ImportClipboard,
    Attachments,
    OpenAttachment(RelativeWorkspacePath),
    Refresh,
    ShowCreated,
    Click(u16, u16),
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum MemoAction {
    Read,
    Edit,
    Pin,
    Delete,
    Restore,
    History,
    Attachments,
}

impl Command {
    #[must_use]
    pub const fn memo_action(&self) -> Option<MemoAction> {
        match self {
            Self::Accept => Some(MemoAction::Read),
            Self::ExternalEdit => Some(MemoAction::Edit),
            Self::Pin => Some(MemoAction::Pin),
            Self::Delete => Some(MemoAction::Delete),
            Self::Restore => Some(MemoAction::Restore),
            Self::History => Some(MemoAction::History),
            Self::Attachments => Some(MemoAction::Attachments),
            Self::Quit
            | Self::Help
            | Self::Functions
            | Self::Actions
            | Self::Back
            | Self::Compose
            | Self::Commit
            | Self::Search
            | Self::Tags
            | Self::Date
            | Self::CustomDate
            | Self::RemoveKeyword
            | Self::RemoveDate
            | Self::ShowNotice
            | Self::ToggleSearchMode
            | Self::ToggleTagScope
            | Self::ClearFilters
            | Self::DiscardDraft
            | Self::Type(_)
            | Self::Edit(_)
            | Self::Move(_)
            | Self::Scroll(_)
            | Self::Page(_)
            | Self::First
            | Self::Last
            | Self::Goto(_)
            | Self::SelectTag(_)
            | Self::SetDate(_)
            | Self::ToggleTask
            | Self::ImportClipboard
            | Self::OpenAttachment(_)
            | Self::Refresh
            | Self::ShowCreated
            | Self::Click(..) => None,
        }
    }
}

#[must_use]
pub fn command_from_key(key: KeyEvent, model: &AppModel) -> Option<Command> {
    if key.kind == KeyEventKind::Release {
        return None;
    }
    if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('c') {
        return Some(Command::Quit);
    }
    match &model.input {
        InputMode::Browse => browse_key(key),
        InputMode::Compose => compose_key(key),
        InputMode::Search { .. } | InputMode::Date { .. } | InputMode::Picker(_) => field_key(key),
        InputMode::Confirm(_) => confirm_key(key),
        InputMode::Message { .. } | InputMode::Help { .. } => message_key(key),
    }
}
const fn browse_key(key: KeyEvent) -> Option<Command> {
    if key.modifiers.contains(KeyModifiers::CONTROL) {
        return match key.code {
            KeyCode::Char('p') => Some(Command::Functions),
            KeyCode::Char('f') => Some(Command::ToggleSearchMode),
            KeyCode::Char('d') => Some(Command::Page(1)),
            KeyCode::Char('u') => Some(Command::Page(-1)),
            KeyCode::Backspace
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
            | KeyCode::Char(_)
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
            | KeyCode::Modifier(_) => None,
        };
    }
    match (key.code, key.modifiers) {
        (KeyCode::Char('q'), _) => Some(Command::Quit),
        (KeyCode::Char('?'), _) => Some(Command::Help),
        (KeyCode::Char('/'), _) => Some(Command::Search),
        (KeyCode::Char('n'), _) => Some(Command::Compose),
        (KeyCode::Char('e'), _) => Some(Command::ExternalEdit),
        (KeyCode::Char('t'), _) => Some(Command::Tags),
        (KeyCode::Char('c'), _) => Some(Command::Date),
        (KeyCode::Char('.'), _) => Some(Command::Actions),
        (KeyCode::Char('j') | KeyCode::Down, _) => Some(Command::Move(1)),
        (KeyCode::Char('k') | KeyCode::Up, _) => Some(Command::Move(-1)),
        (KeyCode::PageDown, _) => Some(Command::Page(1)),
        (KeyCode::PageUp, _) => Some(Command::Page(-1)),
        (KeyCode::Home | KeyCode::Char('g'), _) => Some(Command::First),
        (KeyCode::End | KeyCode::Char('G'), _) => Some(Command::Last),
        (KeyCode::Enter, _) => Some(Command::Accept),
        (KeyCode::Esc, _) => Some(Command::Back),
        (KeyCode::Char('m'), _) => Some(Command::Pin),
        (KeyCode::Char('d'), _) => Some(Command::Delete),
        (KeyCode::Char('r'), _) => Some(Command::Restore),
        (KeyCode::Char('h'), _) => Some(Command::History),
        (KeyCode::Char('p'), _) => Some(Command::ImportClipboard),
        (KeyCode::Char('a'), _) => Some(Command::Attachments),
        (KeyCode::Char('x' | ' '), _) => Some(Command::ToggleTask),
        (KeyCode::F(5), _) => Some(Command::Refresh),
        _ => None,
    }
}
fn compose_key(key: KeyEvent) -> Option<Command> {
    if key.modifiers.contains(KeyModifiers::CONTROL) {
        return match (key.code, key.modifiers) {
            (KeyCode::Char('s'), _) => Some(Command::Commit),
            (KeyCode::Char('e'), _) => Some(Command::ExternalEdit),
            (KeyCode::Char('z'), _) => Some(Command::Edit(TextEdit::Undo)),
            (KeyCode::Char('y'), _) => Some(Command::Edit(TextEdit::Redo)),
            _ => None,
        };
    }
    if key.code == KeyCode::Enter {
        return Some(Command::Edit(TextEdit::Newline));
    }
    if key.code == KeyCode::Tab {
        return Some(Command::Edit(TextEdit::Complete));
    }
    if key.code == KeyCode::Up {
        return Some(Command::Edit(TextEdit::Up));
    }
    if key.code == KeyCode::Down {
        return Some(Command::Edit(TextEdit::Down));
    }
    field_key(key)
}
fn field_key(key: KeyEvent) -> Option<Command> {
    if key.modifiers.contains(KeyModifiers::CONTROL) {
        return (key.code == KeyCode::Char('f')).then_some(Command::ToggleSearchMode);
    }
    match (key.code, key.modifiers) {
        (KeyCode::Char(ch), _) => Some(Command::Type(ch.to_string())),
        (KeyCode::Esc, _) => Some(Command::Back),
        (KeyCode::Enter, _) => Some(Command::Accept),
        (KeyCode::Up, _) => Some(Command::Move(-1)),
        (KeyCode::Down, _) => Some(Command::Move(1)),
        (KeyCode::Left, _) => Some(Command::Edit(TextEdit::Left)),
        (KeyCode::Right, _) => Some(Command::Edit(TextEdit::Right)),
        (KeyCode::Home, _) => Some(Command::Edit(TextEdit::Home)),
        (KeyCode::End, _) => Some(Command::Edit(TextEdit::End)),
        (KeyCode::Backspace, _) => Some(Command::Edit(TextEdit::Backspace)),
        (KeyCode::Delete, _) => Some(Command::Edit(TextEdit::Delete)),
        _ => None,
    }
}
const fn confirm_key(key: KeyEvent) -> Option<Command> {
    match (key.code, key.modifiers) {
        (KeyCode::Enter | KeyCode::Char('y'), _) => Some(Command::Accept),
        (KeyCode::Esc | KeyCode::Char('n'), _) => Some(Command::Back),
        _ => None,
    }
}
const fn message_key(key: KeyEvent) -> Option<Command> {
    match (key.code, key.modifiers) {
        (KeyCode::Esc | KeyCode::Enter | KeyCode::Char('?'), _) => Some(Command::Back),
        (KeyCode::Up | KeyCode::Char('k'), _) => Some(Command::Scroll(-1)),
        (KeyCode::Down | KeyCode::Char('j'), _) => Some(Command::Scroll(1)),
        (KeyCode::PageDown, _) => Some(Command::Scroll(10)),
        (KeyCode::PageUp, _) => Some(Command::Scroll(-10)),
        _ => None,
    }
}
