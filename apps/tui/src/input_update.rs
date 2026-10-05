//! Input transitions cannot invoke browsing actions while text owns focus.
use crate::effects::Effect;
use crate::error::TuiError;
use crate::event::{Availability, Command, Refusal, TextEdit};
use crate::input::TextBuffer;
use crate::model::{AppModel, Confirmation, InputMode, PendingKind, SaveState, View};
use crate::navigation::{move_cursor, shifted};

pub fn apply(model: &mut AppModel, command: &Command) -> Option<Effect> {
    if *command == Command::Back {
        return back(model);
    }
    if *command == Command::Accept {
        return accept(model);
    }
    if *command == Command::DismissPicker {
        // The overlay's own Close row: dismisses the picker only — a real
        // `Back` could pop a history layer or peel a filter (A-02).
        if matches!(model.input, InputMode::Picker(_)) {
            model.input = InputMode::Browse;
        }
        return None;
    }
    if let Command::Click(x, y) = command {
        return click_input(model, *x, *y);
    }
    // The scrollable overlays bound their offset by the rows their content
    // actually shows — framed inner height, or the collapsed strip (C-08) —
    // computed before the input borrow, since `overlay_area` reads the mode.
    let overlay_visible = crate::overlays::content_height(model);
    // Text fields wrap by the column they are actually drawn at — the
    // overlay's inner width (the wizard's value column minus its label) —
    // never the screen width, or the caret parts company with the glyphs
    // (C-17). Computed before the input borrow for the same reason.
    let field_width = crate::overlays::field_width(model);
    match &mut model.input {
        InputMode::Compose => compose(model, command),
        InputMode::Search { .. } => {
            // ↑↓, paging and the wheel navigate the result list while the
            // keyword keeps focus — the field owns only text edits (A-04).
            if matches!(
                command,
                Command::Move(_)
                    | Command::Scroll(_)
                    | Command::Page(_)
                    | Command::First
                    | Command::Last
            ) {
                return crate::update::browse_navigate(model, command);
            }
            // The bordered search panel draws its field narrower than the
            // screen: vertical cursor moves wrap by that inset column.
            let search_width = crate::ui::search_field_width(model);
            let InputMode::Search { text, .. } = &mut model.input else {
                unreachable!("the arm pins the mode");
            };
            let before = text.text().to_owned();
            edit_field(text, command, search_width);
            if text.text() == before && *command != Command::ToggleSearchMode {
                return None;
            }
            if *command == Command::ToggleSearchMode {
                return crate::update::toggle_mode(model);
            }
            crate::update::search_changed(model)
        }
        InputMode::Picker(_) => picker_input(model, command),
        InputMode::Date { text, error, req } => {
            let before = text.text().to_owned();
            edit_field(text, command, field_width);
            if before != text.text() {
                // A real edit retires the stale verdict — the error names
                // the text, not the moment it arrived, so an inert command
                // (movement, scroll, an unreachable action) must not erase
                // evidence for content it never touched. Same rule as the
                // settings and wizard fields.
                *error = None;
                // Editing invalidates an in-flight resolution: its reply
                // degrades instead of binding a filter the user overwrote.
                if let Some(live) = req.take() {
                    model.pending.cancel(live);
                }
            }
            None
        }
        InputMode::Message { scroll, lines, .. } => {
            if let Command::Scroll(delta) = command {
                *scroll = bounded_scroll(*scroll, lines.len(), overlay_visible, *delta);
            }
            None
        }
        InputMode::Help { scroll } => {
            if let Command::Scroll(delta) = command {
                let length = crate::overlays::help(crate::i18n::UiStrings::detect()).len();
                *scroll = bounded_scroll(*scroll, length, overlay_visible, *delta);
            }
            None
        }
        InputMode::Setting(edit) => setting_input(edit, command, field_width),
        InputMode::Setup(setup) => setup_input(setup, command, field_width),
        InputMode::Browse | InputMode::Confirm(_) => None,
    }
}
/// A scroll offset's bound is `lines - visible`, not `lines - 1`: the last
/// page is full only when its final row rests on the panel's bottom edge —
/// clamping past that leaves the tail line alone atop an empty panel.
fn bounded_scroll(current: usize, lines: usize, visible: usize, delta: i32) -> usize {
    shifted(
        current,
        lines.saturating_sub(visible).saturating_add(1),
        delta,
    )
}
/// The picker's own command handling: movement rebinds by identity before
/// shifting so a shrunk list never slides the highlight onto a different
/// command, and only a real filter-text change re-points the selection.
fn picker_input(model: &mut AppModel, command: &Command) -> Option<Effect> {
    // The filter field is drawn across the overlay's inner row: its vertical
    // cursor moves wrap by that column, not the screen width (C-17). Read
    // before the mutable borrow below.
    let field_width = crate::overlays::field_width(model);
    if let Command::Move(delta) | Command::Scroll(delta) = command {
        let entries = {
            let InputMode::Picker(picker) = &model.input else {
                unreachable!("the caller pins the mode");
            };
            crate::menu::entries(model, picker)
        };
        let InputMode::Picker(picker) = &mut model.input else {
            unreachable!("the caller pins the mode");
        };
        picker.rebind(&entries);
        let index = picker.entry_index(&entries).unwrap_or(0);
        picker.select(shifted(index, entries.len(), *delta), &entries);
        return None;
    }
    let changed = {
        let InputMode::Picker(picker) = &mut model.input else {
            unreachable!("the caller pins the mode");
        };
        let before = picker.text.text().to_owned();
        edit_field(&mut picker.text, command, field_width);
        picker.text.text() != before
    };
    if changed {
        let entries = {
            let InputMode::Picker(picker) = &model.input else {
                unreachable!("the caller pins the mode");
            };
            crate::menu::entries(model, picker)
        };
        let InputMode::Picker(picker) = &mut model.input else {
            unreachable!("the caller pins the mode");
        };
        picker.select(0, &entries);
    }
    None
}

fn compose(model: &mut AppModel, command: &Command) -> Option<Effect> {
    if *command == Command::Commit {
        // The same verdict the surface projects decides here: an empty draft
        // or an in-flight save refuses aloud instead of dying silently (A-03).
        match Command::Commit.availability(model) {
            Availability::Ready => {}
            Availability::Refused(reason) => {
                model.set_status(reason.text());
                return None;
            }
            Availability::Hidden => unreachable!("compose() runs only under Compose"),
        }
        // The submission marker and the pending intent carry the same request
        // identity: the receipt can only land while both still name it.
        let revision = model.draft.revision;
        let req = model.request(PendingKind::DraftCommit { revision });
        model.draft.save = SaveState::Submitting { req, revision };
        return Some(Effect::CommitDraft {
            req,
            revision,
            content: model.draft.text.text().to_owned(),
        });
    }
    if model.draft.submitting_revision().is_some() {
        return None;
    }
    if *command == Command::ExternalEdit {
        // Foreground handoff — no reply is expected, so no pending intent.
        let req = model.next_req();
        return Some(Effect::Edit {
            req,
            target: crate::effects::EditTarget::Capture,
        });
    }
    if *command == Command::Edit(TextEdit::Complete) {
        let tag = model
            .draft
            .text
            .tag_prefix()
            .and_then(|(_, prefix)| model.tags().iter().find(|tag| tag.starts_with(prefix)))
            .cloned();
        if let Some(tag) = tag {
            model.draft.text.complete_tag(&tag);
            model.draft.revision += 1;
        }
        return None;
    }
    let before = model.draft.text.text().to_owned();
    let composer = crate::ui::layout_for(model).composer;
    // The bordered panel's field sits behind four cells of chrome; below the
    // panel's minimum the bare strip owns the whole composer row — the
    // cursor wraps by the column the renderer actually drew (C-17).
    let width = if composer.height >= 3 && composer.width >= 6 {
        composer.width.saturating_sub(4)
    } else {
        composer.width
    };
    edit_field(&mut model.draft.text, command, width);
    if before != model.draft.text.text() {
        model.draft.revision += 1;
        model.draft.save = SaveState::Editing;
    }
    None
}
/// One settings row's inline editor — the parse error clears as soon as the
/// text changes; validation itself waits for Enter (`accept`).
fn setting_input(
    edit: &mut crate::settings::SettingsEdit,
    command: &Command,
    width: u16,
) -> Option<Effect> {
    let before = edit.text.text().to_owned();
    edit_field(&mut edit.text, command, width);
    if edit.text.text() != before {
        edit.error = None;
    }
    None
}
/// The wizard's two buffers are one column: Move switches focus, every other
/// key edits the focused field. Nothing here persists — only `accept` mints.
fn setup_input(
    setup: &mut crate::model::SetupState,
    command: &Command,
    width: u16,
) -> Option<Effect> {
    if let Command::Move(delta) = command {
        setup.focus = match setup.focus {
            crate::model::SetupFocus::Workspace if *delta > 0 => crate::model::SetupFocus::TimeZone,
            crate::model::SetupFocus::TimeZone if *delta < 0 => crate::model::SetupFocus::Workspace,
            crate::model::SetupFocus::Workspace | crate::model::SetupFocus::TimeZone => setup.focus,
        };
        return None;
    }
    let buffer = setup.field_mut();
    let before = buffer.text().to_owned();
    edit_field(buffer, command, width);
    if buffer.text() != before {
        setup.error = None;
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
        InputMode::Compose if model.draft.submitting_revision().is_none() => {
            let revision = model.draft.revision;
            let req = model.request(PendingKind::DraftPersist { revision });
            Some(Effect::PersistDraft {
                req,
                revision,
                content: model.draft.text.text().to_owned(),
            })
        }
        // Esc on the date dialog cancels its in-flight resolution — the reply
        // degrades instead of applying a filter the user abandoned.
        InputMode::Date { req: Some(req), .. } => {
            model.pending.cancel(req);
            None
        }
        // Collapsing the search field keeps the keyword as a filter chip.
        InputMode::Search { .. }
        | InputMode::Compose
        | InputMode::Browse
        | InputMode::Picker(_)
        | InputMode::Date { .. }
        | InputMode::Confirm(_)
        | InputMode::Message { .. }
        | InputMode::Setting(_)
        | InputMode::Help { .. } => None,
        // Esc on the first-run wizard abandons setup: nothing was persisted,
        // so leaving is the honest cancel — the normal quit handshake.
        InputMode::Setup(_) => {
            let req = model.request(PendingKind::Quit);
            Some(Effect::Quit { req })
        }
    }
}
fn accept(model: &mut AppModel) -> Option<Effect> {
    let input = std::mem::replace(&mut model.input, InputMode::Browse);
    match input {
        InputMode::Picker(picker) => accept_picker(model, picker),
        InputMode::Date { text, error, .. } => {
            // The dialog stays open while the request resolves — a failure
            // lands back on this dialog as its error, a success applies the
            // filter and closes it.
            let value = text.text().to_owned();
            let req = model.request(PendingKind::Date { dialog: true });
            model.input = InputMode::Date {
                text,
                error,
                req: Some(req),
            };
            Some(Effect::Date { req, text: value })
        }
        InputMode::Confirm(confirmation) => Some(accept_confirm(model, confirmation)),
        InputMode::Compose => {
            model.input = InputMode::Compose;
            None
        }
        InputMode::Setting(mut edit) => {
            let home_dir = match &model.view {
                View::Settings(settings) => settings.home_dir.as_deref(),
                View::Feed(_)
                | View::Reader { .. }
                | View::Tasks(_)
                | View::Statistics(_)
                | View::Attachments(_)
                | View::Loading { .. }
                | View::Failed { .. } => None,
            };
            match edit.field.parse_edit(edit.text.text(), home_dir) {
                Ok(value) => {
                    let field = edit.field;
                    let req = model.request(PendingKind::ConfigReload);
                    Some(Effect::SaveSetting { req, field, value })
                }
                Err(error) => {
                    // Invalid input stays in the editor as a visible error —
                    // it never reaches the config file.
                    edit.error = Some(error.to_string());
                    model.input = InputMode::Setting(edit);
                    None
                }
            }
        }
        InputMode::Setup(mut setup) => {
            if setup.awaiting {
                model.input = InputMode::Setup(setup);
                return None;
            }
            match confirm_setup(&setup) {
                Ok(config) => {
                    let req = setup.req;
                    let file = setup.file.clone();
                    setup.awaiting = true;
                    setup.error = None;
                    model.input = InputMode::Setup(setup);
                    Some(Effect::SetupConfirmed { req, file, config })
                }
                Err(error) => {
                    setup.error = Some(error.to_string());
                    model.input = InputMode::Setup(setup);
                    None
                }
            }
        }
        // Enter leaves the search field with the keyword kept as a filter chip.
        InputMode::Search { .. }
        | InputMode::Browse
        | InputMode::Help { .. }
        | InputMode::Message { .. } => None,
    }
}
/// Confirmed dialogs resolve to their mutation — each `y`/Enter is the one
/// action the prompt already described.
fn accept_confirm(model: &mut AppModel, confirmation: Confirmation) -> Effect {
    match confirmation {
        // The dialog's frozen card answers for its target: the store call
        // pins the exact id+fingerprint the user was shown (I9).
        Confirmation::Delete { memo } => {
            let req = model.request(PendingKind::Mutation);
            Effect::Delete {
                req,
                id: memo.id.clone(),
                fingerprint: memo.fingerprint.clone(),
            }
        }
        Confirmation::DeleteForever(memo) => {
            let req = model.request(PendingKind::Mutation);
            Effect::DeleteForever {
                req,
                id: memo.id.clone(),
            }
        }
        Confirmation::EmptyTrash { .. } => {
            let req = model.request(PendingKind::Mutation);
            Effect::EmptyTrash { req }
        }
        Confirmation::Restore(memo) => {
            let req = model.request(PendingKind::Mutation);
            Effect::Restore {
                req,
                id: memo.id.clone(),
            }
        }
        Confirmation::RestoreRevision { id, revision, .. } => {
            let req = model.request(PendingKind::Mutation);
            Effect::RestoreRevision { req, id, revision }
        }
        Confirmation::DiscardDraft { .. } => {
            // Cancel the in-flight submission's landing intent before the
            // marker retires: its `Saved` receipt then degrades instead of
            // touching the fresh draft (F-01).
            if let SaveState::Submitting { req, .. } = model.draft.save {
                model.pending.cancel(req);
            }
            model.draft.text = TextBuffer::default();
            model.draft.revision += 1;
            // Retire the submission mutex with the discarded revision: a
            // commit already in flight keeps running, but its reply is stale
            // for this new revision and must land without effects. The
            // composer is editable (and leavable) again immediately.
            model.draft.save = SaveState::Editing;
            let revision = model.draft.revision;
            let req = model.request(PendingKind::DraftPersist { revision });
            Effect::PersistDraft {
                req,
                revision,
                content: String::new(),
            }
        }
    }
}

/// Validates both wizard fields into a mintable config — the same registry
/// parsers that validate config.toml, so a confirmed setup can never mint a
/// file the loader would reject.
fn confirm_setup(setup: &crate::model::SetupState) -> Result<crate::config::AppConfig, TuiError> {
    use crate::config::{FieldValue, SettingsField};
    let workspace = match SettingsField::Workspace
        .parse_edit(setup.workspace.text(), setup.home_dir.as_deref())
    {
        Ok(FieldValue::Workspace(path)) => path,
        Ok(_) => unreachable!("parse_edit pins the field/value pairing"),
        Err(error) => return Err(error),
    };
    let time_zone = match SettingsField::TimeZone.parse_edit(setup.time_zone.text(), None) {
        Ok(FieldValue::TimeZone(zone)) => zone,
        Ok(_) => unreachable!("parse_edit pins the field/value pairing"),
        Err(error) => return Err(error),
    };
    Ok(crate::config::AppConfig {
        media_dir: workspace.join("media"),
        workspace,
        time_zone,
        date_format: lomo_application::calendar::DateFormat::default(),
        editor: None,
        player: crate::config::default_player(),
    })
}

fn accept_picker(model: &mut AppModel, mut picker: crate::model::Picker) -> Option<Effect> {
    // An entry that cannot be drawn must not execute: when the overlay has no
    // room for the entry list the picker's `selected` points at invisible rows.
    // Keep the picker open so Esc still dismisses it; Enter names the refusal.
    if crate::overlays::picker_area(model).height == 0 {
        model.input = InputMode::Picker(picker);
        model.set_status(Refusal::NoRoom.text());
        return None;
    }
    let entries = crate::menu::entries(model, &picker);
    // One resolution rule with the drawn highlight (`menu::selected_row` and
    // `Picker::entry_index` share the identity anchor): Enter runs exactly
    // the entry the mark sits on — and an empty list refuses aloud instead
    // of silently closing the picker (A-01).
    let Some(index) = picker.entry_index(&entries) else {
        model.input = InputMode::Picker(picker);
        model.set_status(Refusal::NoMatches.text());
        return None;
    };
    let Some(entry) = entries.get(index) else {
        unreachable!("entry_index resolves in bounds");
    };
    if let Availability::Refused(reason) = entry.availability {
        // A greyed row explains itself — Enter names the same reason the row
        // already shows.
        model.input = InputMode::Picker(picker);
        model.set_status(reason.text());
        return None;
    }
    let command = entry.command.clone();
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
                name.as_deref().map(|name| crate::model::TagConstraint {
                    name: name.to_owned(),
                    scope,
                }),
            );
        }
    }
    if let crate::model::PickerKind::Palette { item, .. } = picker.kind {
        return match item {
            crate::model::PaletteItem::Memo(memo) => match command.memo_action() {
                Some(action) => crate::update::apply_to_memo(model, &memo, action),
                None => crate::update::apply_command(model, command),
            },
            crate::model::PaletteItem::Task(task) if command == Command::ToggleTask => {
                let req = model.request(PendingKind::Mutation);
                Some(Effect::ToggleTask { req, task })
            }
            crate::model::PaletteItem::None
            | crate::model::PaletteItem::Task(_)
            | crate::model::PaletteItem::Attachment(_) => {
                crate::update::apply_command(model, command)
            }
        };
    }
    if let crate::model::PickerKind::History { id, revisions } = &picker.kind
        && let Command::RestoreRevision(revision) = command
    {
        // The entry's row names the revision the dialog restores — carry its
        // stamp and preview so the confirm can show exactly what `y` lands.
        let Some(row) = revisions.iter().find(|row| row.revision == revision) else {
            // A restore command for a revision the picker no longer lists is
            // stale: refuse instead of asking a question about nothing.
            model.set_status(Refusal::NoMatches.text());
            return None;
        };
        model.input = InputMode::Confirm(Confirmation::RestoreRevision {
            id: id.clone(),
            revision: row.revision,
            stamp: row.stamp.clone(),
            preview: row.preview.clone(),
        });
        return None;
    }
    if command == Command::DismissPicker {
        // The picker's own Close row leaves the view stack and filters
        // untouched (A-02) — unlike `Back`, which would unwind a layer.
        model.input = InputMode::Browse;
        return None;
    }
    crate::update::apply_command(model, command)
}

fn click_input(model: &mut AppModel, x: u16, y: u16) -> Option<Effect> {
    if let InputMode::Picker(picker) = &model.input {
        let rows = crate::menu::rows(model, picker);
        let area = crate::overlays::picker_area(model);
        if !area.contains((x, y).into()) {
            return None;
        }
        let top = crate::overlays::picker_top(
            crate::menu::selected_row(&rows, picker),
            rows.len(),
            area.height,
        );
        let index = crate::menu::entry_at(&rows, top + usize::from(y - area.y))?;
        let entries = crate::menu::entries(model, picker);
        if let InputMode::Picker(picker) = &mut model.input {
            picker.select(index, &entries);
        }
        return accept(model);
    }
    None
}
