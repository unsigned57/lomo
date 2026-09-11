//! Behavior Contract
//! Capability: responsive TUI layout and focus-preserving resize.
//! Scenarios: 140-col list+preview until nav opens; 90-col dual; 75-col single shows the focused pane.
//! Observable outcomes: pane occupancy and `AppModel.focus` after resize.
//! TDD proof: layout helpers and TEA resize were not present.
//! Excludes: Ratatui pixel drawing and terminal backends.

#[cfg(test)]
mod tests {
    use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
    use lomo_tui::event::{Command, InputContext, OverlayKind, command_from_key};
    use lomo_tui::layout::{
        Focus, LayoutMode, LayoutRequest, NavPresence, Pane, layout_mode, split_panes,
    };
    use lomo_tui::model::AppModel;
    use lomo_tui::update::{Effect, apply_command, apply_resize};

    fn area(width: u16, height: u16) -> Pane {
        Pane {
            x: 0,
            y: 0,
            width,
            height,
        }
    }

    #[test]
    fn wide_terminal_is_list_and_preview_until_nav_opens() {
        assert_eq!(layout_mode(140), LayoutMode::Triple);
        let hidden = split_panes(
            area(140, 40),
            LayoutRequest {
                mode: LayoutMode::Triple,
                focus: Focus::List,
                nav: NavPresence::Hidden,
                search_open: false,
            },
        );
        assert!(hidden.navigation.is_none());
        assert!(hidden.list.is_some() && hidden.preview.is_some());
        assert_eq!(hidden.status.height, 1);
        let shown = split_panes(
            area(140, 40),
            LayoutRequest {
                mode: LayoutMode::Triple,
                focus: Focus::List,
                nav: NavPresence::Shown,
                search_open: false,
            },
        );
        assert!(shown.navigation.is_some());
        assert!(shown.list.is_some() && shown.preview.is_some());
    }

    #[test]
    fn mid_width_hides_nav_until_expanded() {
        assert_eq!(layout_mode(90), LayoutMode::Dual);
        let hidden = split_panes(
            area(90, 30),
            LayoutRequest {
                mode: LayoutMode::Dual,
                focus: Focus::List,
                nav: NavPresence::Hidden,
                search_open: false,
            },
        );
        assert!(hidden.navigation.is_none());
        assert!(hidden.list.is_some() && hidden.preview.is_some());
        let shown = split_panes(
            area(90, 30),
            LayoutRequest {
                mode: LayoutMode::Dual,
                focus: Focus::List,
                nav: NavPresence::Shown,
                search_open: false,
            },
        );
        assert!(shown.navigation.is_some());
    }

    #[test]
    fn resize_from_triple_to_single_keeps_preview_focus() {
        let mut model = AppModel::new(140, 40);
        model.focus = Focus::Preview;
        apply_resize(&mut model, 75, 24);
        assert_eq!(model.focus, Focus::Preview);
        assert_eq!(layout_mode(model.width), LayoutMode::Single);
        let map = split_panes(
            area(model.width, model.height),
            LayoutRequest {
                mode: layout_mode(model.width),
                focus: model.focus,
                nav: model.nav,
                search_open: false,
            },
        );
        assert!(map.preview.is_some());
        assert!(map.navigation.is_none());
        assert!(map.list.is_none());
    }

    #[test]
    fn tab_cycles_focus_and_slash_opens_search_not_an_editor() {
        let mut model = AppModel::new(120, 30);
        assert_eq!(model.focus, Focus::List);
        assert_eq!(apply_command(&mut model, Command::FocusNext), Effect::None);
        assert_eq!(model.focus, Focus::Preview);
        assert_eq!(apply_command(&mut model, Command::OpenSearch), Effect::None);
        assert!(matches!(
            model.search,
            lomo_tui::model::SearchSession::Open { .. }
        ));
        let key = KeyEvent::new(KeyCode::Char('n'), KeyModifiers::NONE);
        let command = command_from_key(
            key,
            InputContext {
                overlay: OverlayKind::None,
                search_open: true,
            },
        );
        assert_eq!(command, Command::SearchChar('n'));
    }

    #[test]
    fn zero_area_search_and_single_focus_panes() {
        let empty = split_panes(
            area(0, 0),
            LayoutRequest {
                mode: LayoutMode::Triple,
                focus: Focus::List,
                nav: NavPresence::Hidden,
                search_open: true,
            },
        );
        assert!(empty.navigation.is_none() && empty.list.is_none());
        let searching = split_panes(
            area(140, 40),
            LayoutRequest {
                mode: LayoutMode::Triple,
                focus: Focus::List,
                nav: NavPresence::Hidden,
                search_open: true,
            },
        );
        assert!(searching.search.is_some());
        let nav = split_panes(
            area(75, 20),
            LayoutRequest {
                mode: LayoutMode::Single,
                focus: Focus::Navigation,
                nav: NavPresence::Hidden,
                search_open: false,
            },
        );
        assert!(nav.navigation.is_some());
        assert!(nav.list.is_none());
        let list = split_panes(
            area(75, 20),
            LayoutRequest {
                mode: LayoutMode::Single,
                focus: Focus::List,
                nav: NavPresence::Hidden,
                search_open: false,
            },
        );
        assert!(list.list.is_some());
        assert_eq!(layout_mode(79), LayoutMode::Single);
        assert_eq!(
            lomo_tui::layout::next_focus(Focus::Preview),
            Focus::Navigation
        );
        assert_eq!(Pane::empty().width, 0);
    }
}
