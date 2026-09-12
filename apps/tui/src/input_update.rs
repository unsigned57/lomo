//! Input transitions cannot invoke browsing actions while text owns focus.
use crate::effects::Effect;
use crate::event::{Command, TextEdit};
use crate::input::TextBuffer;
use crate::model::{AppModel, Confirmation, InputMode, SaveState, View};
use crate::navigation::{move_cursor, shifted};

pub fn apply(model: &mut AppModel, command: &Command) -> Option<Effect> {
    if *command == Command::Back {
        return back(model);
    }
    if *command == Command::Accept {
        return accept(model);
    }
    if let Command::Click(x, y) = command {
        return click_input(model, *x, *y);
    }
    match &mut model.input {
        InputMode::Compose => compose(model, command),
        InputMode::Search { text, .. } => {
            let before = text.text().to_owned();
            edit_field(text, command, model.width);
            if text.text() == before && *command != Command::ToggleSearchMode {
                return None;
            }
            if *command == Command::ToggleSearchMode {
                return crate::update::toggle_mode(model);
            }
            crate::update::search_changed(model)
        }
        InputMode::Picker(picker) => {
            if let Command::Move(delta) | Command::Scroll(delta) = command {
                let count = crate::menu::entries(&model.tags, picker).len();
                picker.selected = shifted(picker.selected, count, *delta);
            } else {
                edit_field(&mut picker.text, command, model.width);
                picker.selected = 0;
            }
            None
        }
        InputMode::Date { text, error, .. } => {
            let before = text.text().to_owned();
            edit_field(text, command, model.width);
            *error = None;
            if before != text.text() {
                let next = model.next_ticket();
                if let InputMode::Date { ticket, .. } = &mut model.input {
                    *ticket = next;
                }
            }
            None
        }
        InputMode::Message { scroll, lines, .. } => {
            if let Command::Scroll(delta) = command {
                *scroll = shifted(*scroll, lines.len(), *delta);
            }
            None
        }
        InputMode::Help { scroll } => {
            if let Command::Scroll(delta) = command {
                *scroll = shifted(*scroll, 20, *delta);
            }
            None
        }
        InputMode::Browse | InputMode::Confirm(_) => None,
    }
}
fn compose(model: &mut AppModel, command: &Command) -> Option<Effect> {
    if matches!(model.draft.save, SaveState::Submitting { .. }) {
        return None;
    }
    if *command == Command::Commit {
        if model.draft.text.text().trim().is_empty() {
            return None;
        }
        model.draft.save = SaveState::Submitting {
            revision: model.draft.revision,
        };
        return Some(Effect::CommitDraft {
            revision: model.draft.revision,
            content: model.draft.text.text().to_owned(),
        });
    }
    if *command == Command::ExternalEdit {
        return Some(Effect::Edit(crate::effects::EditTarget::Capture));
    }
    if *command == Command::Edit(TextEdit::Complete) {
        let tag = model
            .draft
            .text
            .tag_prefix()
            .and_then(|(_, prefix)| model.tags.iter().find(|tag| tag.starts_with(prefix)))
            .cloned();
        if let Some(tag) = tag {
            model.draft.text.complete_tag(&tag);
            model.draft.revision += 1;
        }
        return None;
    }
    let before = model.draft.text.text().to_owned();
    let width = crate::ui::layout_for(model)
        .composer
        .width
        .saturating_sub(4);
    edit_field(&mut model.draft.text, command, width);
    if before != model.draft.text.text() {
        model.draft.revision += 1;
        model.draft.save = SaveState::Editing;
    }
    None
}
fn edit_field(text: &mut TextBuffer, command: &Command, width: u16) {
    if let Command::Type(value) = command {
        text.insert(value);
    }
    if let Command::Edit(edit) = command {
        match edit {
            TextEdit::Left => text.left(),
            TextEdit::Right => text.right(),
            TextEdit::Home => text.home(),
            TextEdit::End => text.end(),
            TextEdit::Backspace => text.backspace(),
            TextEdit::Delete => text.delete(),
            TextEdit::Undo => text.undo(),
            TextEdit::Redo => text.redo(),
            TextEdit::Newline => text.insert("\n"),
            TextEdit::Up => move_cursor(text, width, -1),
            TextEdit::Down => move_cursor(text, width, 1),
            TextEdit::Complete => {}
        }
    }
}

fn back(model: &mut AppModel) -> Option<Effect> {
    let previous = std::mem::replace(&mut model.input, InputMode::Browse);
    match previous {
        InputMode::Compose if !matches!(model.draft.save, SaveState::Submitting { .. }) => {
            Some(Effect::PersistDraft {
                revision: model.draft.revision,
                content: model.draft.text.text().to_owned(),
            })
        }
        InputMode::Search { before, .. } => {
            model.next_epoch();
            model.view = *before;
            if let View::Feed(feed) = &mut model.view {
                feed.epoch = model.epoch;
                if matches!(
                    feed.load,
                    crate::model::LoadStatus::Loading | crate::model::LoadStatus::Stale
                ) {
                    return crate::update::reload_feed(model);
                }
            }
            None
        }
        InputMode::Compose
        | InputMode::Browse
        | InputMode::Picker(_)
        | InputMode::Date { .. }
        | InputMode::Confirm(_)
        | InputMode::Message { .. }
        | InputMode::Help { .. } => None,
    }
}
fn accept(model: &mut AppModel) -> Option<Effect> {
    let input = std::mem::replace(&mut model.input, InputMode::Browse);
    match input {
        InputMode::Picker(picker) => accept_picker(model, picker),
        InputMode::Search { before, .. } => {
            if !matches!(&*before, View::Feed(feed) if feed.kind == crate::model::FeedKind::Timeline)
            {
                model.history.push(*before);
            }
            None
        }
        InputMode::Date {
            text,
            error,
            ticket,
        } => {
            let value = text.text().to_owned();
            model.input = InputMode::Date {
                text,
                error,
                ticket,
            };
            Some(Effect::Date {
                ticket,
                text: value,
            })
        }
        InputMode::Confirm(confirmation) => match confirmation {
            Confirmation::Delete { id, fingerprint } => Some(Effect::Delete { id, fingerprint }),
            Confirmation::Restore(id) => Some(Effect::Restore(id)),
            Confirmation::DiscardDraft => {
                model.draft.text = TextBuffer::default();
                model.draft.revision += 1;
                Some(Effect::PersistDraft {
                    revision: model.draft.revision,
                    content: String::new(),
                })
            }
        },
        InputMode::Compose => {
            model.input = InputMode::Compose;
            None
        }
        InputMode::Browse | InputMode::Help { .. } | InputMode::Message { .. } => None,
    }
}

fn accept_picker(model: &mut AppModel, mut picker: crate::model::Picker) -> Option<Effect> {
    let rows = crate::menu::entries(&model.tags, &picker);
    let command = rows
        .get(picker.selected.min(rows.len().saturating_sub(1)))?
        .command
        .clone();
    if let crate::model::PickerKind::Tags(scope) = picker.kind {
        if command == Command::ToggleTagScope {
            use lomo_application::TagSelectionMode;
            picker.kind = crate::model::PickerKind::Tags(match scope {
                TagSelectionMode::Exact => TagSelectionMode::Subtree,
                TagSelectionMode::Subtree => TagSelectionMode::Exact,
            });
            model.input = InputMode::Picker(picker);
            return None;
        }
        if let Command::SelectTag(name) = command {
            return crate::update::select_tag(
                model,
                name.map(|name| crate::model::TagConstraint { name, scope }),
            );
        }
    }
    if let crate::model::PickerKind::Actions(memo) = picker.kind {
        return command
            .memo_action()
            .and_then(|action| crate::update::apply_to_memo(model, &memo, action));
    }
    crate::update::apply_command(model, command)
}

fn click_input(model: &mut AppModel, x: u16, y: u16) -> Option<Effect> {
    if let InputMode::Picker(picker) = &model.input {
        let rows = crate::menu::entries(&model.tags, picker);
        let area = crate::overlays::picker_area(model);
        if !area.contains((x, y).into()) {
            return None;
        }
        let top = crate::overlays::picker_top(picker.selected, rows.len(), area.height);
        let index = top + usize::from(y - area.y);
        if index >= rows.len() {
            return None;
        }
        if let InputMode::Picker(picker) = &mut model.input {
            picker.selected = index;
        }
        return accept(model);
    }
    None
}
