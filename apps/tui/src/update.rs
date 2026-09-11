use lomo_application::SearchMode;

use crate::event::{Command, PALETTE_LABELS, nav_screen, palette_command, screen_row};
use crate::i18n::UiStrings;
use crate::layout::{Focus, NavPresence, layout_mode, next_focus};
use crate::model::{AppModel, ConfirmAction, Overlay, Screen, SearchSession};

/// Session-side work requested by a key. The TEA loop performs these, not `apply_command`.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Effect {
    None,
    Quit,
    LoadScreen,
    Search,
    NewMemo,
    EditMemo,
    ToggleTask,
    PinSelected,
    DeleteSelected,
    RestoreSelected,
    ShowHistory,
    ImportClipboard,
    PlayAttachment,
    ConfirmDelete,
    ConfirmRestore,
}

/// Applies a command to the model. Focus survives resize; IO is returned as an effect.
#[must_use]
pub fn apply_command(model: &mut AppModel, command: Command) -> Effect {
    match &model.overlay {
        Overlay::None => apply_idle(model, command),
        Overlay::Help => apply_help(model, command),
        Overlay::Palette { .. } => apply_palette(model, command),
        Overlay::Alert { .. } | Overlay::Overdue { .. } | Overlay::History { .. } => {
            apply_dismiss(model, command)
        }
        Overlay::Confirm { .. } => apply_confirm(model, command),
    }
}

/// Records a new terminal size without changing focus.
pub fn apply_resize(model: &mut AppModel, width: u16, height: u16) {
    model.width = width;
    model.height = height;
    if layout_mode(width) == crate::layout::LayoutMode::Single {
        model.nav = NavPresence::Hidden;
    }
}

fn apply_idle(model: &mut AppModel, command: Command) -> Effect {
    if matches!(model.search, SearchSession::Open { .. }) {
        return apply_search(model, command);
    }
    match command {
        Command::Quit => Effect::Quit,
        Command::HelpToggle => {
            model.overlay = Overlay::Help;
            Effect::None
        }
        Command::PaletteToggle => {
            model.overlay = Overlay::Palette {
                index: screen_row(model.screen),
            };
            Effect::None
        }
        Command::FocusNext => {
            model.focus = next_focus(model.focus);
            Effect::None
        }
        Command::MoveUp => {
            move_list(model, -1);
            Effect::LoadScreen
        }
        Command::MoveDown => {
            move_list(model, 1);
            Effect::LoadScreen
        }
        Command::OpenSearch => {
            model.search_epoch = model.search_epoch.saturating_add(1);
            model.search = SearchSession::Open {
                query: String::new(),
                mode: model.search_mode,
                epoch: model.search_epoch,
            };
            Effect::None
        }
        Command::ToggleSearchMode => {
            model.search_mode = toggle_mode(model.search_mode);
            let i18n = UiStrings::detect();
            let label = match model.search_mode {
                SearchMode::Fulltext => i18n.search_fulltext.as_str(),
                SearchMode::Fuzzy => i18n.search_fuzzy.as_str(),
            };
            model.set_status(&format!("search mode: {label}"));
            Effect::None
        }
        Command::NewMemo => Effect::NewMemo,
        Command::EditMemo => Effect::EditMemo,
        Command::ToggleTask => Effect::ToggleTask,
        Command::Goto(screen) => goto_screen(model, screen),
        Command::ToggleNavDrawer => {
            model.nav = match model.nav {
                NavPresence::Hidden => NavPresence::Shown,
                NavPresence::Shown => NavPresence::Hidden,
            };
            Effect::None
        }
        Command::PlayAttachment => Effect::PlayAttachment,
        Command::ImportClipboard => Effect::ImportClipboard,
        Command::PinSelected => Effect::PinSelected,
        Command::DeleteSelected => Effect::ConfirmDelete,
        Command::RestoreSelected => Effect::ConfirmRestore,
        Command::ShowHistory => Effect::ShowHistory,
        Command::Cancel
        | Command::SearchChar(_)
        | Command::SearchBackspace
        | Command::SearchSubmit
        | Command::SearchCancel
        | Command::ConfirmYes
        | Command::ConfirmNo
        | Command::None => Effect::None,
    }
}

fn apply_search(model: &mut AppModel, command: Command) -> Effect {
    match command {
        Command::SearchChar(ch) => {
            if let SearchSession::Open { query, .. } = &mut model.search {
                query.push(ch);
            }
            Effect::None
        }
        Command::SearchBackspace => {
            if let SearchSession::Open { query, .. } = &mut model.search {
                query.pop();
            }
            Effect::None
        }
        Command::SearchSubmit => {
            bump_search_epoch(model);
            Effect::Search
        }
        Command::SearchCancel => {
            model.search = SearchSession::Closed;
            Effect::LoadScreen
        }
        Command::ToggleSearchMode => {
            if let SearchSession::Open { mode, .. } = &mut model.search {
                *mode = toggle_mode(*mode);
                model.search_mode = *mode;
            }
            Effect::None
        }
        Command::Quit => Effect::Quit,
        Command::HelpToggle
        | Command::PaletteToggle
        | Command::FocusNext
        | Command::MoveUp
        | Command::MoveDown
        | Command::OpenSearch
        | Command::NewMemo
        | Command::EditMemo
        | Command::ToggleTask
        | Command::ConfirmYes
        | Command::ConfirmNo
        | Command::Cancel
        | Command::Goto(_)
        | Command::ToggleNavDrawer
        | Command::PlayAttachment
        | Command::ImportClipboard
        | Command::PinSelected
        | Command::DeleteSelected
        | Command::RestoreSelected
        | Command::ShowHistory
        | Command::None => Effect::None,
    }
}

fn apply_help(model: &mut AppModel, command: Command) -> Effect {
    match command {
        Command::HelpToggle | Command::Cancel => {
            model.overlay = Overlay::None;
            Effect::None
        }
        Command::Quit => Effect::Quit,
        Command::PaletteToggle
        | Command::FocusNext
        | Command::MoveUp
        | Command::MoveDown
        | Command::OpenSearch
        | Command::SearchChar(_)
        | Command::SearchBackspace
        | Command::SearchSubmit
        | Command::SearchCancel
        | Command::ToggleSearchMode
        | Command::NewMemo
        | Command::EditMemo
        | Command::ToggleTask
        | Command::ConfirmYes
        | Command::ConfirmNo
        | Command::Goto(_)
        | Command::ToggleNavDrawer
        | Command::PlayAttachment
        | Command::ImportClipboard
        | Command::PinSelected
        | Command::DeleteSelected
        | Command::RestoreSelected
        | Command::ShowHistory
        | Command::None => Effect::None,
    }
}

fn apply_palette(model: &mut AppModel, command: Command) -> Effect {
    match command {
        Command::Cancel => {
            model.overlay = Overlay::None;
            Effect::None
        }
        Command::MoveUp => {
            shift_palette(model, -1);
            Effect::None
        }
        Command::MoveDown => {
            shift_palette(model, 1);
            Effect::None
        }
        Command::ConfirmYes => take_palette(model),
        Command::Quit => Effect::Quit,
        Command::HelpToggle
        | Command::PaletteToggle
        | Command::FocusNext
        | Command::OpenSearch
        | Command::SearchChar(_)
        | Command::SearchBackspace
        | Command::SearchSubmit
        | Command::SearchCancel
        | Command::ToggleSearchMode
        | Command::NewMemo
        | Command::EditMemo
        | Command::ToggleTask
        | Command::ConfirmNo
        | Command::Goto(_)
        | Command::ToggleNavDrawer
        | Command::PlayAttachment
        | Command::ImportClipboard
        | Command::PinSelected
        | Command::DeleteSelected
        | Command::RestoreSelected
        | Command::ShowHistory
        | Command::None => Effect::None,
    }
}

fn apply_dismiss(model: &mut AppModel, command: Command) -> Effect {
    match command {
        Command::Cancel | Command::ConfirmYes | Command::ConfirmNo => {
            model.overlay = Overlay::None;
            Effect::None
        }
        Command::Quit => Effect::Quit,
        Command::HelpToggle
        | Command::PaletteToggle
        | Command::FocusNext
        | Command::MoveUp
        | Command::MoveDown
        | Command::OpenSearch
        | Command::SearchChar(_)
        | Command::SearchBackspace
        | Command::SearchSubmit
        | Command::SearchCancel
        | Command::ToggleSearchMode
        | Command::NewMemo
        | Command::EditMemo
        | Command::ToggleTask
        | Command::Goto(_)
        | Command::ToggleNavDrawer
        | Command::PlayAttachment
        | Command::ImportClipboard
        | Command::PinSelected
        | Command::DeleteSelected
        | Command::RestoreSelected
        | Command::ShowHistory
        | Command::None => Effect::None,
    }
}

fn apply_confirm(model: &mut AppModel, command: Command) -> Effect {
    match command {
        Command::ConfirmNo | Command::Cancel => {
            model.overlay = Overlay::None;
            Effect::None
        }
        Command::ConfirmYes => match model.overlay {
            Overlay::Confirm {
                action: ConfirmAction::Delete,
                ..
            } => {
                model.overlay = Overlay::None;
                Effect::DeleteSelected
            }
            Overlay::Confirm {
                action: ConfirmAction::Restore,
                ..
            } => {
                model.overlay = Overlay::None;
                Effect::RestoreSelected
            }
            Overlay::None
            | Overlay::Help
            | Overlay::Palette { .. }
            | Overlay::Alert { .. }
            | Overlay::Overdue { .. }
            | Overlay::History { .. } => Effect::None,
        },
        Command::Quit => Effect::Quit,
        Command::HelpToggle
        | Command::PaletteToggle
        | Command::FocusNext
        | Command::MoveUp
        | Command::MoveDown
        | Command::OpenSearch
        | Command::SearchChar(_)
        | Command::SearchBackspace
        | Command::SearchSubmit
        | Command::SearchCancel
        | Command::ToggleSearchMode
        | Command::NewMemo
        | Command::EditMemo
        | Command::ToggleTask
        | Command::Goto(_)
        | Command::ToggleNavDrawer
        | Command::PlayAttachment
        | Command::ImportClipboard
        | Command::PinSelected
        | Command::DeleteSelected
        | Command::RestoreSelected
        | Command::ShowHistory
        | Command::None => Effect::None,
    }
}

fn goto_screen(model: &mut AppModel, screen: Screen) -> Effect {
    model.screen = screen;
    model.nav_selected = screen_row(screen);
    model.selected = 0;
    model.focus = Focus::List;
    model.overlay = Overlay::None;
    Effect::LoadScreen
}

fn move_list(model: &mut AppModel, delta: i32) {
    if model.focus == Focus::Navigation {
        let count = crate::event::nav_row_count();
        model.nav_selected = shift_index(model.nav_selected, count, delta);
        if let Some(screen) = nav_screen(model.nav_selected) {
            model.screen = screen;
        }
        return;
    }
    if model.items.is_empty() {
        return;
    }
    model.selected = shift_index(model.selected, model.items.len(), delta);
}

const fn shift_palette(model: &mut AppModel, delta: i32) {
    if let Overlay::Palette { index } = &mut model.overlay {
        *index = shift_index(*index, PALETTE_LABELS.len(), delta);
    }
}

fn take_palette(model: &mut AppModel) -> Effect {
    let Overlay::Palette { index } = model.overlay else {
        return Effect::None;
    };
    model.overlay = Overlay::None;
    apply_idle(model, palette_command(index))
}

const fn bump_search_epoch(model: &mut AppModel) {
    model.search_epoch = model.search_epoch.saturating_add(1);
    if let SearchSession::Open { epoch, mode, .. } = &mut model.search {
        *epoch = model.search_epoch;
        model.search_mode = *mode;
    }
}

const fn toggle_mode(mode: SearchMode) -> SearchMode {
    match mode {
        SearchMode::Fulltext => SearchMode::Fuzzy,
        SearchMode::Fuzzy => SearchMode::Fulltext,
    }
}

const fn shift_index(current: usize, len: usize, delta: i32) -> usize {
    if len == 0 {
        return 0;
    }
    let last = len.saturating_sub(1);
    if delta < 0 {
        current.saturating_sub(1)
    } else if current >= last {
        last
    } else {
        current.saturating_add(1)
    }
}

/// Opens a confirm overlay for delete/restore. Called by the TEA dispatcher.
pub fn request_confirm(model: &mut AppModel, action: ConfirmAction) {
    let i18n = UiStrings::detect();
    let (title, body) = match action {
        ConfirmAction::Delete => (i18n.confirm_delete_title, i18n.confirm_delete_query),
        ConfirmAction::Restore => (i18n.confirm_restore_title, i18n.confirm_restore_query),
    };
    model.overlay = Overlay::Confirm {
        title,
        body,
        action,
    };
}
