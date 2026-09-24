//! Behavior Contract
//! Capability: exactly one input receiver and identity-bound actions in the TEA state machine.
//! Scenarios: text modes consume shortcut characters; browsing exposes only the retained direct keys;
//! confirmations retain `MemoId`; collapsing search keeps the keyword and a second Esc restores context;
//! one grouped, mouse-selectable command palette reaches every auxiliary screen.
//! Observable outcomes: typed commands, input state, retained selection and memo-targeted effects.
//! TDD proof: header mouse clicks are ignored and moving a search cursor needlessly reloads its results.
//! Excludes: session IO. Old pane focus and generic row assertions are replaced by typed views and inputs.

#[cfg(test)]
pub mod support;
#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Test fixtures and application effects must succeed before state assertions"
)]
mod tests {
    use super::support::{feed, model_with_memos};
    use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
    use lomo_tui::{
        effects::Effect,
        event::{Command, TextEdit, command_from_key},
        input::TextBuffer,
        menu::PaletteGroup,
        model::{AppModel, InputMode, PaletteItem, PaletteScope, Picker, PickerKind, Screen, View},
        update::apply_command,
    };

    #[test]
    fn character_shortcuts_belong_to_the_active_text_input() {
        let mut model = AppModel::new(80, 24);
        for input in [
            InputMode::Compose,
            InputMode::Picker(Picker {
                kind: PickerKind::Palette {
                    item: PaletteItem::None,
                    scope: PaletteScope::All,
                },
                text: TextBuffer::default(),
                selected: 0,
            }),
            InputMode::Date {
                ticket: 0,
                text: TextBuffer::default(),
                error: None,
            },
        ] {
            model.input = input;
            for ch in ['n', 'e', 'q', '/', '.', ':', 'j', 'k', '中'] {
                assert_eq!(
                    command_from_key(KeyEvent::new(KeyCode::Char(ch), KeyModifiers::NONE), &model),
                    Some(Command::Type(ch.to_string()))
                );
            }
        }
        model.input = InputMode::Compose;
        assert_eq!(
            command_from_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE), &model),
            Some(Command::Edit(TextEdit::Newline))
        );
        assert_eq!(
            command_from_key(
                KeyEvent::new(KeyCode::Char('s'), KeyModifiers::CONTROL),
                &model
            ),
            Some(Command::Commit)
        );
    }

    #[test]
    fn browsing_exposes_only_the_retained_direct_keys() {
        let model = model_with_memos(1, 80, 24).expect("fixture and operation must succeed");
        for ch in ['t', 'c', 'h', 'p', 'a', 'r', 'D', 'x', ' '] {
            assert_eq!(
                command_from_key(KeyEvent::new(KeyCode::Char(ch), KeyModifiers::NONE), &model),
                None,
                "{ch:?} must only be reachable through the palette"
            );
        }
        for modifier in ['p', 'f'] {
            assert_eq!(
                command_from_key(
                    KeyEvent::new(KeyCode::Char(modifier), KeyModifiers::CONTROL),
                    &model
                ),
                None
            );
        }
        assert_eq!(
            command_from_key(
                KeyEvent::new(KeyCode::Char(':'), KeyModifiers::NONE),
                &model
            ),
            Some(Command::Palette)
        );
        assert_eq!(
            command_from_key(
                KeyEvent::new(KeyCode::Char('.'), KeyModifiers::NONE),
                &model
            ),
            Some(Command::Actions)
        );
        for (ch, command) in [
            ('e', Command::ExternalEdit),
            ('m', Command::Pin),
            ('d', Command::Delete),
            ('n', Command::Compose),
            ('/', Command::Search),
        ] {
            assert_eq!(
                command_from_key(KeyEvent::new(KeyCode::Char(ch), KeyModifiers::NONE), &model),
                Some(command)
            );
        }
    }

    #[test]
    fn fulltext_toggle_belongs_to_the_search_field_only() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture and operation must succeed");
        assert_eq!(apply_command(&mut model, Command::Search), None);
        let toggle = KeyEvent::new(KeyCode::Char('f'), KeyModifiers::CONTROL);
        assert_eq!(
            command_from_key(toggle, &model),
            Some(Command::ToggleSearchMode)
        );
        assert_eq!(apply_command(&mut model, Command::Back), None);
        assert_eq!(apply_command(&mut model, Command::CustomDate), None);
        assert_eq!(command_from_key(toggle, &model), None);
    }

    #[test]
    fn confirmation_retains_its_memo_even_if_a_refresh_changes_selection() {
        let mut model = model_with_memos(2, 80, 24).expect("fixture and operation must succeed");
        let id = model
            .selected_memo()
            .ok_or("selected")
            .expect("fixture and operation must succeed")
            .id
            .clone();
        assert_eq!(apply_command(&mut model, Command::Delete), None);
        super::support::feed_mut(&mut model)
            .expect("fixture and operation must succeed")
            .selected = Some(
            lomo_workspace::MemoId::parse("memo-1").expect("fixture and operation must succeed"),
        );
        assert_eq!(
            apply_command(&mut model, Command::Accept),
            Some(Effect::Delete {
                id,
                fingerprint: "version-1".to_owned()
            })
        );
    }

    #[test]
    fn collapsing_search_keeps_the_keyword_and_a_second_esc_restores_the_reading_context() {
        let mut model = model_with_memos(20, 80, 24).expect("fixture and operation must succeed");
        assert_eq!(apply_command(&mut model, Command::Move(12)), None);
        let before = feed(&model)
            .expect("fixture and operation must succeed")
            .clone();
        assert_eq!(apply_command(&mut model, Command::Search), None);
        assert!(matches!(
            apply_command(&mut model, Command::Type("term".to_owned())),
            Some(Effect::Query(_))
        ));
        assert_eq!(
            apply_command(&mut model, Command::Edit(TextEdit::Left)),
            None
        );
        assert_eq!(apply_command(&mut model, Command::Back), None);
        assert_eq!(model.input, InputMode::Browse);
        assert_eq!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .query
                .text,
            "term"
        );
        assert_eq!(apply_command(&mut model, Command::Back), None);
        assert!(
            !feed(&model)
                .expect("fixture and operation must succeed")
                .query
                .is_filtered()
        );
        assert_eq!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .selected,
            before.selected
        );
        assert_eq!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .anchor,
            before.anchor
        );
        assert_eq!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .epoch,
            model.epoch
        );
    }

    #[test]
    fn the_command_palette_retains_all_seven_pages() {
        let model = AppModel::new(80, 24);
        let mut picker = Picker {
            kind: PickerKind::Palette {
                item: PaletteItem::None,
                scope: PaletteScope::All,
            },
            text: TextBuffer::default(),
            selected: 0,
        };
        let entries = lomo_tui::menu::entries(&model, &picker);
        for screen in [
            Screen::Timeline,
            Screen::Tasks,
            Screen::Review,
            Screen::Statistics,
            Screen::Attachments,
            Screen::Trash,
            Screen::Settings,
        ] {
            assert!(
                entries
                    .iter()
                    .any(|entry| entry.command == Command::Goto(screen))
            );
        }
        picker.text = TextBuffer::new(
            lomo_tui::i18n::UiStrings::detect()
                .screen_title(Screen::Statistics)
                .to_owned(),
        );
        assert_eq!(lomo_tui::menu::entries(&model, &picker).len(), 1);
    }

    #[test]
    fn the_palette_groups_item_actions_before_pages_and_shows_direct_keys() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture and operation must succeed");
        assert_eq!(apply_command(&mut model, Command::Palette), None);
        let InputMode::Picker(picker) = &model.input else {
            panic!("palette");
        };
        let entries = lomo_tui::menu::entries(&model, picker);
        let first = entries.first().expect("first entry");
        assert_eq!(first.group, PaletteGroup::Item);
        assert_eq!(first.command, Command::Accept);
        assert_eq!(first.key, Some("Enter"));
        let last_item = entries
            .iter()
            .rposition(|entry| entry.group == PaletteGroup::Item)
            .expect("item rows");
        let first_page = entries
            .iter()
            .position(|entry| entry.group == PaletteGroup::Pages)
            .expect("page rows");
        assert!(last_item < first_page);
        assert!(
            entries
                .iter()
                .any(|entry| entry.command == Command::Compose && entry.key == Some("n"))
        );
        assert!(
            entries
                .iter()
                .any(|entry| entry.command == Command::Tags && entry.group == PaletteGroup::Filters)
        );
        assert_eq!(apply_command(&mut model, Command::Back), None);
        assert_eq!(apply_command(&mut model, Command::Actions), None);
        let InputMode::Picker(picker) = &model.input else {
            panic!("item palette");
        };
        assert!(
            lomo_tui::menu::entries(&model, picker)
                .iter()
                .all(|entry| entry.group == PaletteGroup::Item)
        );
    }

    #[test]
    fn date_opens_a_searchable_picker_and_palette_rows_accept_mouse_selection() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture and operation must succeed");
        assert_eq!(apply_command(&mut model, Command::Date), None);
        assert!(matches!(
            model.input,
            InputMode::Picker(Picker {
                kind: PickerKind::Dates,
                ..
            })
        ));
        assert_eq!(apply_command(&mut model, Command::Back), None);
        assert_eq!(apply_command(&mut model, Command::Palette), None);
        let InputMode::Picker(picker) = &model.input else {
            panic!("palette");
        };
        let timeline = lomo_tui::menu::entries(&model, picker)
            .iter()
            .position(|entry| entry.command == Command::Goto(Screen::Timeline))
            .expect("timeline entry");
        assert_eq!(
            apply_command(
                &mut model,
                Command::Move(i32::try_from(timeline).expect("fixture and operation must succeed"))
            ),
            None
        );
        let InputMode::Picker(picker) = &model.input else {
            panic!("palette");
        };
        let rows = lomo_tui::menu::rows(&model, picker);
        let row = lomo_tui::menu::selected_row(&rows, picker.selected);
        let area = lomo_tui::overlays::picker_area(&model);
        let top = lomo_tui::overlays::picker_top(row, rows.len(), area.height);
        assert!(
            matches!(rows.get(row), Some(lomo_tui::menu::MenuRow::Entry(_))),
            "headers are never selected"
        );
        let effect = apply_command(
            &mut model,
            Command::Click(
                area.x + 1,
                area.y + u16::try_from(row - top).expect("fixture and operation must succeed"),
            ),
        );
        assert!(matches!(
            effect,
            Some(Effect::Navigate {
                screen: Screen::Timeline,
                ..
            })
        ));
    }

    #[test]
    fn memo_action_menu_retains_the_memo_it_was_opened_for() {
        let mut model = model_with_memos(2, 80, 24).expect("fixture and operation must succeed");
        let original = model
            .selected_memo()
            .ok_or("memo")
            .expect("fixture and operation must succeed")
            .id
            .clone();
        assert_eq!(apply_command(&mut model, Command::Actions), None);
        let InputMode::Picker(picker) = &model.input else {
            panic!("picker");
        };
        let pin = lomo_tui::menu::entries(&model, picker)
            .iter()
            .position(|entry| entry.command == Command::Pin)
            .ok_or("pin action")
            .expect("fixture and operation must succeed");
        assert_eq!(
            apply_command(
                &mut model,
                Command::Move(i32::try_from(pin).expect("fixture and operation must succeed"))
            ),
            None
        );
        super::support::feed_mut(&mut model)
            .expect("fixture and operation must succeed")
            .selected = Some(
            lomo_workspace::MemoId::parse("memo-1").expect("fixture and operation must succeed"),
        );
        assert_eq!(
            apply_command(&mut model, Command::Accept),
            Some(Effect::Pin {
                id: original,
                pinned: true
            })
        );
    }

    #[test]
    fn escaping_a_search_opened_from_a_reader_collapses_clears_then_returns_to_that_reader() {
        let mut model = model_with_memos(2, 80, 24).expect("fixture and operation must succeed");
        assert_eq!(apply_command(&mut model, Command::Accept), None);
        let before = model.view.clone();
        let _effect = apply_command(&mut model, Command::Search);
        let _effect = apply_command(&mut model, Command::Type("term".to_owned()));
        let _effect = apply_command(&mut model, Command::Back);
        assert!(matches!(&model.view, View::Feed(feed) if feed.query.text == "term"));
        let _effect = apply_command(&mut model, Command::Back);
        assert!(matches!(&model.view, View::Feed(feed) if !feed.query.is_filtered()));
        let _effect = apply_command(&mut model, Command::Back);
        assert_eq!(model.view, before);
    }

    #[test]
    fn tag_scope_is_chosen_in_the_picker_without_losing_the_reading_context() {
        let mut model = model_with_memos(20, 80, 24).expect("feed");
        model.tags = vec!["reading/book".to_owned()];
        assert_eq!(apply_command(&mut model, Command::Move(12)), None);
        let before = feed(&model).expect("feed").clone();
        let _effect = apply_command(&mut model, Command::Tags);
        let InputMode::Picker(picker) = &model.input else {
            panic!("tag picker");
        };
        let scope = lomo_tui::menu::entries(&model, picker)
            .iter()
            .position(|entry| entry.command == Command::ToggleTagScope)
            .expect("scope entry");
        assert_eq!(
            apply_command(
                &mut model,
                Command::Move(i32::try_from(scope).expect("menu index"))
            ),
            None
        );
        assert_eq!(apply_command(&mut model, Command::Accept), None);
        assert!(matches!(model.input, InputMode::Picker(_)));
        assert_eq!(feed(&model).expect("feed").selected, before.selected);
        assert_eq!(feed(&model).expect("feed").query, before.query);
    }

    #[test]
    fn tag_picker_includes_parent_scopes_of_observed_child_tags() {
        let mut model = model_with_memos(1, 80, 24).expect("feed");
        model.tags = vec!["reading/book".to_owned()];
        let _effect = apply_command(&mut model, Command::Tags);
        let InputMode::Picker(picker) = &model.input else {
            panic!("tag picker");
        };
        let entries = lomo_tui::menu::entries(&model, picker);
        assert!(
            entries
                .iter()
                .any(|entry| entry.command == Command::SelectTag(Some("reading".to_owned())))
        );
    }

    #[test]
    fn menu_selection_is_bounded_before_moving_back_from_the_last_item() {
        let mut model = AppModel::new(80, 24);
        assert_eq!(apply_command(&mut model, Command::Palette), None);
        assert_eq!(apply_command(&mut model, Command::Move(i32::MAX)), None);
        assert_eq!(apply_command(&mut model, Command::Move(-1)), None);
        assert_eq!(apply_command(&mut model, Command::Accept), None);
        assert!(matches!(model.input, InputMode::Help { .. }));
    }

    #[test]
    fn removing_the_last_search_character_restores_the_original_reading_context() {
        let mut model = model_with_memos(20, 80, 24).expect("feed");
        assert_eq!(apply_command(&mut model, Command::Move(12)), None);
        let before = feed(&model).expect("feed").clone();
        let _effect = apply_command(&mut model, Command::Search);
        let _effect = apply_command(&mut model, Command::Type("x".to_owned()));
        let _effect = apply_command(&mut model, Command::Edit(TextEdit::Backspace));
        assert_eq!(feed(&model).expect("feed").selected, before.selected);
        assert_eq!(feed(&model).expect("feed").anchor, before.anchor);
    }

    #[test]
    fn changing_search_mode_requeries_the_same_keyword() {
        let mut model = model_with_memos(2, 80, 24).expect("feed");
        let _effect = apply_command(&mut model, Command::Search);
        let _effect = apply_command(&mut model, Command::Type("keyword".to_owned()));
        let effect = apply_command(&mut model, Command::ToggleSearchMode);
        assert!(
            matches!(effect, Some(Effect::Query(ref request)) if request.query.mode == lomo_application::SearchMode::Fuzzy && request.query.text == "keyword")
        );
    }
    #[test]
    fn paste_is_typed_only_where_a_text_field_owns_it() {
        use lomo_tui::event::command_from_paste;
        let mut model = model_with_memos(1, 80, 24).expect("feed");
        assert_eq!(
            command_from_paste("pasted".to_owned(), &model),
            Some(Command::PasteDenied),
            "browsing has no text field; paste must surface as an explicit command, not silent data loss"
        );
        model.input = InputMode::Compose;
        assert_eq!(
            command_from_paste("pasted".to_owned(), &model),
            Some(Command::Type("pasted".to_owned())),
            "an active text field receives the paste"
        );
        model.input = InputMode::Browse;
        assert_eq!(apply_command(&mut model, Command::PasteDenied), None);
        assert!(
            model.status.is_some(),
            "a denied paste explains itself instead of vanishing"
        );
    }

    #[test]
    fn terminal_capabilities_follow_the_reported_terminal() {
        let dumb = std::collections::BTreeMap::from([("TERM".to_owned(), "dumb".to_owned())]);
        let caps = lomo_tui::host::TerminalCapabilities::detect(&dumb);
        assert!(
            !caps.mouse && !caps.paste && !caps.focus,
            "a dumb terminal must not receive capability enables it cannot honor"
        );
        let xterm =
            std::collections::BTreeMap::from([("TERM".to_owned(), "xterm-256color".to_owned())]);
        let caps = lomo_tui::host::TerminalCapabilities::detect(&xterm);
        assert!(
            caps.mouse && caps.paste && caps.focus,
            "a capable terminal enables its reported features"
        );
        let missing = std::collections::BTreeMap::new();
        let caps = lomo_tui::host::TerminalCapabilities::detect(&missing);
        assert!(
            !caps.mouse && !caps.paste && !caps.focus,
            "no TERM means no assumed capabilities"
        );
    }
}
