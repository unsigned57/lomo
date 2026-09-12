//! Behavior Contract
//! Capability: exactly one input receiver and identity-bound actions in the TEA state machine.
//! Scenarios: text modes consume shortcut characters; confirmations retain `MemoId`; search cancel restores context;
//! clickable header controls and searchable menus reach every auxiliary screen.
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
        model::{AppModel, InputMode, Picker, PickerKind, Screen},
        update::apply_command,
    };

    #[test]
    fn character_shortcuts_belong_to_the_active_text_input() {
        let mut model = AppModel::new(80, 24);
        for input in [
            InputMode::Compose,
            InputMode::Picker(Picker {
                kind: PickerKind::Functions,
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
            for ch in ['n', 'e', 'q', '/', '.', 'j', 'k', '中'] {
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
    fn header_mouse_action_matches_the_keyboard_action() {
        let mut model = model_with_memos(2, 120, 24).expect("fixture and operation must succeed");
        let s = lomo_tui::i18n::UiStrings::detect();
        let controls =
            lomo_tui::overlays::header_controls(lomo_tui::ui::layout_for(&model).header, &s);
        let (rect, _, _) = controls
            .iter()
            .find(|(_, _, command)| command == &Command::Compose)
            .ok_or("capture control")
            .expect("fixture and operation must succeed");
        assert_eq!(
            apply_command(&mut model, Command::Click(rect.x, rect.y)),
            Some(Effect::Tags)
        );
        assert_eq!(model.input, InputMode::Compose);
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
    fn cancelling_search_restores_the_reading_context_and_cursor_moves_do_not_query() {
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
    fn searchable_function_menu_retains_all_seven_pages() {
        let model = AppModel::new(80, 24);
        let mut picker = Picker {
            kind: PickerKind::Functions,
            text: TextBuffer::default(),
            selected: 0,
        };
        let entries = lomo_tui::menu::entries(&model.tags, &picker);
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
        assert_eq!(lomo_tui::menu::entries(&model.tags, &picker).len(), 1);
    }

    #[test]
    fn date_opens_a_searchable_picker_and_menu_rows_accept_mouse_selection() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture and operation must succeed");
        assert_eq!(apply_command(&mut model, Command::Date), None);
        assert!(matches!(model.input, InputMode::Picker(_)));
        assert_eq!(apply_command(&mut model, Command::Back), None);
        assert_eq!(apply_command(&mut model, Command::Functions), None);
        let area = lomo_tui::overlays::overlay_area(&model);
        let effect = apply_command(&mut model, Command::Click(area.x + 1, area.y + 3));
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
        let pin = lomo_tui::menu::entries(&model.tags, picker)
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
    fn cancelling_search_opened_from_a_reader_restores_that_reader() {
        let mut model = model_with_memos(2, 80, 24).expect("fixture and operation must succeed");
        assert_eq!(apply_command(&mut model, Command::Accept), None);
        let before = model.view.clone();
        let _effect = apply_command(&mut model, Command::Search);
        let _effect = apply_command(&mut model, Command::Type("term".to_owned()));
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
        let scope = lomo_tui::menu::entries(&model.tags, picker)
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
        let entries = lomo_tui::menu::entries(&model.tags, picker);
        assert!(
            entries
                .iter()
                .any(|entry| entry.command == Command::SelectTag(Some("reading".to_owned())))
        );
    }

    #[test]
    fn menu_selection_is_bounded_before_moving_back_from_the_last_item() {
        let mut model = AppModel::new(80, 24);
        assert_eq!(apply_command(&mut model, Command::Functions), None);
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
}
