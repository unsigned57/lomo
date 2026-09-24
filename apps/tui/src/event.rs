//! Contextual key translation. Input modes consume characters before browsing shortcuts.
use crossterm::event::{KeyCode, KeyEvent, KeyEventKind, KeyModifiers};
use lomo_core::RelativeWorkspacePath;
use lomo_workspace::MemoId;

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
    Palette,
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
    DeleteForever,
    EmptyTrash,
    Restore,
    History,
    RestoreRevision(u64),
    OpenMemo(MemoId),
    ToggleTask,
    ImportClipboard,
    Attachments,
    OpenAttachment(RelativeWorkspacePath),
    Refresh,
    /// Terminal focus returned. Reconcile observed changes only — never an
    /// unconditional projection rebuild.
    FocusReconcile,
    /// A paste arrived while no text field could receive it.
    PasteDenied,
    ShowCreated,
    Click(u16, u16),
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum MemoAction {
    Read,
    Edit,
    Pin,
    Delete,
    DeleteForever,
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
            Self::DeleteForever => Some(MemoAction::DeleteForever),
            Self::Restore => Some(MemoAction::Restore),
            Self::History => Some(MemoAction::History),
            Self::Attachments => Some(MemoAction::Attachments),
            Self::Quit
            | Self::Help
            | Self::Palette
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
            | Self::EmptyTrash
            | Self::RestoreRevision(_)
            | Self::OpenMemo(_)
            | Self::ImportClipboard
            | Self::OpenAttachment(_)
            | Self::Refresh
            | Self::FocusReconcile
            | Self::PasteDenied
            | Self::ShowCreated
            | Self::Click(..) => None,
        }
    }
}

/// Bracketed paste targets the active text field; in Browse it is reported,
/// not silently dropped.
#[must_use]
pub fn command_from_paste(text: String, model: &AppModel) -> Option<Command> {
    if matches!(model.input, InputMode::Browse) {
        Some(Command::PasteDenied)
    } else {
        Some(Command::Type(text))
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
        InputMode::Search { .. } => field_key(key, true),
        InputMode::Date { .. } | InputMode::Picker(_) => field_key(key, false),
        InputMode::Confirm(_) => confirm_key(key),
        InputMode::Message { .. } | InputMode::Help { .. } => message_key(key),
    }
}
/// Browsing keeps a small, semantic set of direct keys; everything else lives in the palette.
const fn browse_key(key: KeyEvent) -> Option<Command> {
    if key.modifiers.contains(KeyModifiers::CONTROL) {
        return match key.code {
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
        (KeyCode::Char(':'), _) => Some(Command::Palette),
        (KeyCode::Char('n'), _) => Some(Command::Compose),
        (KeyCode::Char('e'), _) => Some(Command::ExternalEdit),
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
    field_key(key, false)
}
/// `Ctrl+F` switches fulltext / fuzzy only where a search keyword is being typed.
fn field_key(key: KeyEvent, search: bool) -> Option<Command> {
    if key.modifiers.contains(KeyModifiers::CONTROL) {
        return (search && key.code == KeyCode::Char('f')).then_some(Command::ToggleSearchMode);
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
