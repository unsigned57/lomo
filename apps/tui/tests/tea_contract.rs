//! Behavior Contract
//! Capability: key routing and TEA command/effect transitions without session IO.
//! Scenarios: idle/search/help/palette/confirm/dismiss keys; search `n` is not `NewMemo`; resize hides nav only in single mode.
//! Observable outcomes: `Command`, `Effect`, overlay, focus, selection, search session.
//! TDD proof: TEA dispatch existed without exhaustive overlay/key contracts.
//! Excludes: Ratatui drawing and `lomo-application` writes.

#[cfg(test)]
mod tests {
    use crossterm::event::{KeyCode, KeyEvent, KeyEventKind, KeyModifiers};
    use lomo_tui::event::{
        Command, InputContext, OverlayKind, command_from_key, nav_screen, palette_command,
        screen_row,
    };
    use lomo_tui::layout::{Focus, NavPresence};
    use lomo_tui::model::{AppModel, ConfirmAction, ListRow, Overlay, Screen, SearchSession};
    use lomo_tui::update::{Effect, apply_command, apply_resize, request_confirm};

    fn ctx(overlay: OverlayKind, search_open: bool) -> InputContext {
        InputContext {
            overlay,
            search_open,
        }
    }

    fn press(overlay: OverlayKind, search_open: bool, code: KeyCode) -> Command {
        command_from_key(
            KeyEvent::new(code, KeyModifiers::NONE),
            ctx(overlay, search_open),
        )
    }

    fn press_mods(
        overlay: OverlayKind,
        search_open: bool,
        code: KeyCode,
        modifiers: KeyModifiers,
    ) -> Command {
        command_from_key(KeyEvent::new(code, modifiers), ctx(overlay, search_open))
    }

    #[test]
    #[expect(
        clippy::too_many_lines,
        clippy::cognitive_complexity,
        reason = "idle key table is the routing contract"
    )]
    fn idle_keys_map_navigation_and_never_treat_repeat_as_press() {
        assert_eq!(
            press(OverlayKind::None, false, KeyCode::Char('q')),
            Command::Quit
        );
        assert_eq!(
            press(OverlayKind::None, false, KeyCode::Char('?')),
            Command::HelpToggle
        );
        assert_eq!(
            press(OverlayKind::None, false, KeyCode::Char('/')),
            Command::OpenSearch
        );
        assert_eq!(
            press(OverlayKind::None, false, KeyCode::Char('n')),
            Command::NewMemo
        );
        assert_eq!(
            press(OverlayKind::None, false, KeyCode::Char('e')),
            Command::EditMemo
        );
        assert_eq!(
            press(OverlayKind::None, false, KeyCode::Char(' ')),
            Command::ToggleTask
        );
        assert_eq!(
            press(OverlayKind::None, false, KeyCode::Char('x')),
            Command::ToggleTask
        );
        assert_eq!(
            press(OverlayKind::None, false, KeyCode::Char('j')),
            Command::MoveDown
        );
        assert_eq!(
            press(OverlayKind::None, false, KeyCode::Down),
            Command::MoveDown
        );
        assert_eq!(
            press(OverlayKind::None, false, KeyCode::Char('k')),
            Command::MoveUp
        );
        assert_eq!(
            press(OverlayKind::None, false, KeyCode::Up),
            Command::MoveUp
        );
        assert_eq!(
            press(OverlayKind::None, false, KeyCode::Char('[')),
            Command::ToggleNavDrawer
        );
        assert_eq!(
            press(OverlayKind::None, false, KeyCode::Char('p')),
            Command::ImportClipboard
        );
        assert_eq!(
            press(OverlayKind::None, false, KeyCode::Char('a')),
            Command::PlayAttachment
        );
        assert_eq!(
            press(OverlayKind::None, false, KeyCode::Char('m')),
            Command::PinSelected
        );
        assert_eq!(
            press(OverlayKind::None, false, KeyCode::Char('d')),
            Command::DeleteSelected
        );
        assert_eq!(
            press(OverlayKind::None, false, KeyCode::Char('r')),
            Command::RestoreSelected
        );
        assert_eq!(
            press(OverlayKind::None, false, KeyCode::Char('h')),
            Command::ShowHistory
        );
        assert_eq!(
            press(OverlayKind::None, false, KeyCode::Tab),
            Command::FocusNext
        );
        assert_eq!(
            press(OverlayKind::None, false, KeyCode::Esc),
            Command::Cancel
        );
        assert_eq!(
            press(OverlayKind::None, false, KeyCode::Char('z')),
            Command::None
        );
        assert_eq!(
            press_mods(
                OverlayKind::None,
                false,
                KeyCode::Char('p'),
                KeyModifiers::CONTROL
            ),
            Command::PaletteToggle
        );
        assert_eq!(
            press_mods(
                OverlayKind::None,
                false,
                KeyCode::Char('f'),
                KeyModifiers::CONTROL
            ),
            Command::ToggleSearchMode
        );
        assert_eq!(
            press_mods(
                OverlayKind::None,
                false,
                KeyCode::Char('c'),
                KeyModifiers::CONTROL
            ),
            Command::Quit
        );
        assert_eq!(
            press_mods(
                OverlayKind::None,
                false,
                KeyCode::Char('q'),
                KeyModifiers::CONTROL
            ),
            Command::None
        );
        let mut repeat = KeyEvent::new(KeyCode::Char('q'), KeyModifiers::NONE);
        repeat.kind = KeyEventKind::Repeat;
        assert_eq!(
            command_from_key(repeat, ctx(OverlayKind::None, false)),
            Command::None
        );
    }

    #[test]
    #[expect(
        clippy::too_many_lines,
        clippy::cognitive_complexity,
        reason = "overlay key table is the routing contract"
    )]
    fn overlay_and_search_keys_are_isolated_from_body_editing() {
        assert_eq!(
            press(OverlayKind::None, true, KeyCode::Char('n')),
            Command::SearchChar('n')
        );
        assert_eq!(
            press(OverlayKind::None, true, KeyCode::Backspace),
            Command::SearchBackspace
        );
        assert_eq!(
            press(OverlayKind::None, true, KeyCode::Enter),
            Command::SearchSubmit
        );
        assert_eq!(
            press(OverlayKind::None, true, KeyCode::Esc),
            Command::SearchCancel
        );
        assert_eq!(
            press_mods(
                OverlayKind::None,
                true,
                KeyCode::Char('f'),
                KeyModifiers::CONTROL
            ),
            Command::ToggleSearchMode
        );
        assert_eq!(
            press_mods(
                OverlayKind::None,
                true,
                KeyCode::Char('p'),
                KeyModifiers::CONTROL
            ),
            Command::PaletteToggle
        );
        assert_eq!(
            press(OverlayKind::Help, false, KeyCode::Char('?')),
            Command::HelpToggle
        );
        assert_eq!(
            press(OverlayKind::Help, false, KeyCode::Esc),
            Command::HelpToggle
        );
        assert_eq!(
            press(OverlayKind::Help, false, KeyCode::Char('q')),
            Command::Quit
        );
        assert_eq!(
            press(OverlayKind::Palette, false, KeyCode::Esc),
            Command::Cancel
        );
        assert_eq!(
            press(OverlayKind::Palette, false, KeyCode::Enter),
            Command::ConfirmYes
        );
        assert_eq!(
            press(OverlayKind::Palette, false, KeyCode::Char('j')),
            Command::MoveDown
        );
        assert_eq!(
            press(OverlayKind::Palette, false, KeyCode::Char('k')),
            Command::MoveUp
        );
        assert_eq!(
            press(OverlayKind::Confirm, false, KeyCode::Char('y')),
            Command::ConfirmYes
        );
        assert_eq!(
            press(OverlayKind::Confirm, false, KeyCode::Char('n')),
            Command::ConfirmNo
        );
        assert_eq!(
            press(OverlayKind::Alert, false, KeyCode::Esc),
            Command::Cancel
        );
        assert_eq!(
            press(OverlayKind::Overdue, false, KeyCode::Enter),
            Command::Cancel
        );
        assert_eq!(
            press(OverlayKind::History, false, KeyCode::Char(' ')),
            Command::Cancel
        );
        assert_eq!(
            press(OverlayKind::History, false, KeyCode::Char('q')),
            Command::Quit
        );
        assert_eq!(palette_command(7), Command::NewMemo);
        assert_eq!(palette_command(11), Command::None);
        assert_eq!(nav_screen(2), Some(Screen::Review));
        assert_eq!(nav_screen(9), None);
        assert_eq!(screen_row(Screen::Trash), 5);
        let search = SearchSession::Closed;
        assert_eq!(
            InputContext::from_model(&Overlay::Help, &search).overlay,
            OverlayKind::Help
        );
        assert_eq!(
            InputContext::from_model(&Overlay::Palette { index: 0 }, &search).overlay,
            OverlayKind::Palette
        );
        assert_eq!(
            InputContext::from_model(
                &Overlay::Alert {
                    title: "t".to_owned(),
                    body: "b".to_owned(),
                },
                &search
            )
            .overlay,
            OverlayKind::Alert
        );
        assert_eq!(
            InputContext::from_model(
                &Overlay::Confirm {
                    title: "t".to_owned(),
                    body: "b".to_owned(),
                    action: ConfirmAction::Delete,
                },
                &search
            )
            .overlay,
            OverlayKind::Confirm
        );
        assert_eq!(
            InputContext::from_model(&Overlay::Overdue { lines: Vec::new() }, &search).overlay,
            OverlayKind::Overdue
        );
        assert_eq!(
            InputContext::from_model(&Overlay::History { lines: Vec::new() }, &search).overlay,
            OverlayKind::History
        );
    }

    fn seeded_model() -> AppModel {
        let mut model = AppModel::new(140, 40);
        model.items = vec![ListRow::new("a", "alpha"), ListRow::new("b", "beta")];
        model
    }

    #[test]
    #[expect(
        clippy::cognitive_complexity,
        reason = "effect table is the idle TEA contract"
    )]
    fn idle_commands_request_session_effects_and_preserve_search_as_filter() {
        let mut model = seeded_model();
        assert_eq!(apply_command(&mut model, Command::Quit), Effect::Quit);
        assert_eq!(apply_command(&mut model, Command::HelpToggle), Effect::None);
        assert_eq!(model.overlay, Overlay::Help);
        model.overlay = Overlay::None;
        assert_eq!(
            apply_command(&mut model, Command::PaletteToggle),
            Effect::None
        );
        assert!(matches!(model.overlay, Overlay::Palette { .. }));
        model.overlay = Overlay::None;
        assert_eq!(
            apply_command(&mut model, Command::MoveDown),
            Effect::LoadScreen
        );
        assert_eq!(model.selected, 1);
        assert_eq!(
            apply_command(&mut model, Command::MoveUp),
            Effect::LoadScreen
        );
        assert_eq!(model.selected, 0);
        assert_eq!(apply_command(&mut model, Command::NewMemo), Effect::NewMemo);
        assert_eq!(
            apply_command(&mut model, Command::EditMemo),
            Effect::EditMemo
        );
        assert_eq!(
            apply_command(&mut model, Command::ToggleTask),
            Effect::ToggleTask
        );
        assert_eq!(
            apply_command(&mut model, Command::PlayAttachment),
            Effect::PlayAttachment
        );
        assert_eq!(
            apply_command(&mut model, Command::ImportClipboard),
            Effect::ImportClipboard
        );
        assert_eq!(
            apply_command(&mut model, Command::PinSelected),
            Effect::PinSelected
        );
        assert_eq!(
            apply_command(&mut model, Command::DeleteSelected),
            Effect::ConfirmDelete
        );
        assert_eq!(
            apply_command(&mut model, Command::RestoreSelected),
            Effect::ConfirmRestore
        );
        assert_eq!(
            apply_command(&mut model, Command::ShowHistory),
            Effect::ShowHistory
        );
        assert_eq!(
            apply_command(&mut model, Command::ToggleNavDrawer),
            Effect::None
        );
        assert_eq!(model.nav, NavPresence::Shown);
        assert_eq!(
            apply_command(&mut model, Command::Goto(Screen::Settings)),
            Effect::LoadScreen
        );
        assert_eq!(model.screen, Screen::Settings);
        assert_eq!(apply_command(&mut model, Command::OpenSearch), Effect::None);
        assert_eq!(
            apply_command(&mut model, Command::SearchChar('q')),
            Effect::None
        );
        assert_eq!(
            apply_command(&mut model, Command::SearchBackspace),
            Effect::None
        );
        assert_eq!(
            apply_command(&mut model, Command::ToggleSearchMode),
            Effect::None
        );
        assert_eq!(
            apply_command(&mut model, Command::SearchSubmit),
            Effect::Search
        );
        assert_eq!(
            apply_command(&mut model, Command::SearchCancel),
            Effect::LoadScreen
        );
        assert_eq!(model.search, SearchSession::Closed);
        assert_eq!(
            apply_command(&mut model, Command::ToggleSearchMode),
            Effect::None
        );
        assert!(
            model.status.contains("fuzzy")
                || model.status.contains("fulltext")
                || model.status.contains("模糊")
                || model.status.contains("全文")
        );
    }

    #[test]
    fn overlays_consume_keys_until_dismissed_and_confirm_emits_delete_or_restore() {
        let mut model = seeded_model();
        model.overlay = Overlay::Help;
        assert_eq!(apply_command(&mut model, Command::Cancel), Effect::None);
        assert_eq!(model.overlay, Overlay::None);
        model.overlay = Overlay::Palette { index: 0 };
        assert_eq!(apply_command(&mut model, Command::MoveDown), Effect::None);
        assert_eq!(
            apply_command(&mut model, Command::ConfirmYes),
            Effect::LoadScreen
        );
        assert_eq!(model.screen, Screen::Tasks);
        model.overlay = Overlay::Alert {
            title: "t".to_owned(),
            body: "b".to_owned(),
        };
        assert_eq!(apply_command(&mut model, Command::Cancel), Effect::None);
        request_confirm(&mut model, ConfirmAction::Delete);
        assert_eq!(
            apply_command(&mut model, Command::ConfirmYes),
            Effect::DeleteSelected
        );
        request_confirm(&mut model, ConfirmAction::Restore);
        assert_eq!(apply_command(&mut model, Command::ConfirmNo), Effect::None);
        request_confirm(&mut model, ConfirmAction::Restore);
        assert_eq!(
            apply_command(&mut model, Command::ConfirmYes),
            Effect::RestoreSelected
        );
        model.overlay = Overlay::History {
            lines: vec!["r1".to_owned()],
        };
        assert_eq!(apply_command(&mut model, Command::Quit), Effect::Quit);
        model.overlay = Overlay::None;
        model.focus = Focus::Navigation;
        model.nav_selected = 0;
        assert_eq!(
            apply_command(&mut model, Command::MoveDown),
            Effect::LoadScreen
        );
        assert_eq!(model.screen, Screen::Tasks);
        apply_resize(&mut model, 100, 24);
        assert_eq!(model.width, 100);
        apply_resize(&mut model, 70, 20);
        assert_eq!(model.nav, NavPresence::Hidden);
        model.items.clear();
        model.clamp_selection();
        assert_eq!(model.selected_id(), None);
        assert_eq!(
            apply_command(&mut model, Command::MoveDown),
            Effect::LoadScreen
        );
    }
}
