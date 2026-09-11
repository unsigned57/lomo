//! Behavior Contract
//! Capability: TUI chrome matches Think panels; only the home surface is a cross-date timeline.
//! Scenarios: wide timeline list+preview, search strip, task markers, stats overview, Think overlays.
//! Observable outcomes: `TestBackend` buffer contains Think titles, time headers, ✓/», heatmap labels.
//! TDD proof: draw used generic `Nav`/`Timeline`/`Preview` Debug chrome.
//! Excludes: real crossterm TTY and graphics protocol pixels.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "render contract tests fail closed on missing buffer titles"
)]
mod tests {
    use lomo_application::SearchMode;
    use lomo_tui::layout::{Focus, NavPresence};
    use lomo_tui::model::{
        AppModel, ConfirmAction, HeatPoint, ListRow, Overlay, Screen, SearchSession, StatsView,
    };
    use lomo_tui::ui::draw;
    use ratatui::Terminal;
    use ratatui::backend::TestBackend;

    fn visible(terminal: &Terminal<TestBackend>) -> String {
        terminal
            .backend()
            .buffer()
            .content()
            .iter()
            .map(ratatui::buffer::Cell::symbol)
            .collect()
    }

    fn draw_model(model: &AppModel, width: u16, height: u16) -> String {
        let mut terminal = Terminal::new(TestBackend::new(width, height)).expect("backend");
        terminal.draw(|frame| draw(frame, model)).expect("draw");
        visible(&terminal)
    }

    fn contains_any(haystack: &str, needles: &[&str]) -> bool {
        needles.iter().any(|needle| haystack.contains(needle))
    }

    fn with_row(mut model: AppModel) -> AppModel {
        model.items = vec![ListRow {
            id: "m1".to_owned(),
            header: " 09-11 10:00:00 ".to_owned(),
            title: "hello memo".to_owned(),
            subtitle: String::new(),
            done: None,
        }];
        model.preview =
            "# Title\n\n- [x] Done Task\n- [ ] Open\n\n`code` #tag\n\n![img](pic.png)".to_owned();
        model.status = "ready".to_owned();
        model
    }

    #[test]
    fn timeline_uses_think_titles_and_time_headers_without_forced_nav() {
        let mut wide = with_row(AppModel::new(140, 40));
        wide.screen = Screen::Timeline;
        let triple = draw_model(&wide, 140, 40);
        assert!(
            contains_any(&triple, &["Thought Stream", "思维流"]),
            "triple={triple}"
        );
        assert!(
            contains_any(&triple, &["Preview", "预览"]),
            "triple={triple}"
        );
        assert!(triple.contains("09-11"), "triple={triple}");
        assert!(triple.contains("hello memo"), "triple={triple}");
        assert!(
            !triple.contains("Nav"),
            "wide terminals must not force a Nav pane: {triple}"
        );
        let mut dual = with_row(AppModel::new(90, 24));
        dual.nav = NavPresence::Shown;
        dual.search = SearchSession::Open {
            query: "milk".to_owned(),
            mode: SearchMode::Fuzzy,
            epoch: 1,
        };
        let dual_text = draw_model(&dual, 90, 24);
        assert!(
            contains_any(
                &dual_text,
                &["Search Input", "关键词输入", "Search", "搜索"]
            ),
            "dual={dual_text}"
        );
        assert!(dual_text.contains("milk"), "dual={dual_text}");
    }

    #[test]
    fn tasks_and_stats_reuse_think_widgets() {
        let mut tasks = AppModel::new(80, 24);
        tasks.screen = Screen::Tasks;
        tasks.items = vec![
            ListRow {
                id: "t1:0".to_owned(),
                header: String::new(),
                title: "buy milk".to_owned(),
                subtitle: "09-11".to_owned(),
                done: Some(false),
            },
            ListRow {
                id: "t1:1".to_owned(),
                header: String::new(),
                title: "done item".to_owned(),
                subtitle: "09-10".to_owned(),
                done: Some(true),
            },
        ];
        let task_text = draw_model(&tasks, 80, 24);
        assert!(
            contains_any(&task_text, &["Todo", "待办"]),
            "tasks={task_text}"
        );
        assert!(
            task_text.contains("»") || task_text.contains("✓"),
            "tasks={task_text}"
        );
        assert!(task_text.contains("buy milk"), "tasks={task_text}");

        let mut stats = AppModel::new(80, 40);
        stats.screen = Screen::Statistics;
        stats.stats = Some(StatsView {
            zone: "UTC".to_owned(),
            as_of_year: 2026,
            as_of_month: 9,
            as_of_day: 11,
            total_memos: 3,
            total_words: 40,
            active_days: 2,
            current_streak: 1,
            longest_streak: 2,
            this_week: 1,
            this_month: 3,
            this_year: 3,
            daily: vec![
                HeatPoint {
                    year: 2026,
                    month: 9,
                    day: 11,
                    count: 2,
                },
                HeatPoint {
                    year: 2026,
                    month: 8,
                    day: 1,
                    count: 1,
                },
                HeatPoint {
                    year: 2026,
                    month: 7,
                    day: 1,
                    count: 5,
                },
                HeatPoint {
                    year: 2026,
                    month: 6,
                    day: 1,
                    count: 9,
                },
            ],
        });
        let stats_text = draw_model(&stats, 80, 40);
        assert!(
            contains_any(&stats_text, &["Overview", "数据概览"]),
            "stats={stats_text}"
        );
        assert!(
            contains_any(&stats_text, &["Activity Heatmap", "活跃度热力图"]),
            "stats={stats_text}"
        );
        stats.stats = None;
        let empty_stats = draw_model(&stats, 80, 20);
        assert!(
            contains_any(&empty_stats, &["Overview", "数据概览", "Write", "写"]),
            "empty={empty_stats}"
        );
    }

    #[test]
    fn single_layout_and_overlays_are_visible() {
        let mut single = with_row(AppModel::new(75, 20));
        single.focus = Focus::Preview;
        let preview = draw_model(&single, 75, 20);
        assert!(
            contains_any(&preview, &["Preview", "预览", "Title", "Done"]),
            "single={preview}"
        );
        single.focus = Focus::Navigation;
        single.nav = NavPresence::Shown;
        let nav = draw_model(&single, 75, 20);
        assert!(
            contains_any(&nav, &["Thought Stream", "思维流", "Todo", "待办"]),
            "nav={nav}"
        );
        single.focus = Focus::List;
        single.screen = Screen::Tasks;
        single.nav = NavPresence::Hidden;
        let list = draw_model(&single, 75, 20);
        assert!(contains_any(&list, &["Todo", "待办"]), "list={list}");
        single.overlay = Overlay::Help;
        let help = draw_model(&single, 75, 20);
        assert!(
            contains_any(&help, &["Keybindings", "键盘快捷键", "Help", "帮助"]),
            "help={help}"
        );
        single.overlay = Overlay::Palette { index: 1 };
        let palette = draw_model(&single, 75, 20);
        assert!(
            contains_any(&palette, &["Commands", "命令", "Todo", "待办"]),
            "palette={palette}"
        );
        single.overlay = Overlay::Confirm {
            title: " [ Confirm Delete ] ".to_owned(),
            body: "Are you sure you want to delete this?".to_owned(),
            action: ConfirmAction::Delete,
        };
        let confirm = draw_model(&single, 75, 20);
        assert!(
            contains_any(&confirm, &["Confirm Delete", "确认删除", "Delete"]),
            "confirm={confirm}"
        );
        single.overlay = Overlay::Overdue {
            lines: vec!["overdue rent".to_owned()],
        };
        let overdue = draw_model(&single, 75, 20);
        assert!(
            overdue.contains("Overdue") || overdue.contains("逾期") || overdue.contains("rent"),
            "overdue={overdue}"
        );
        single.overlay = Overlay::History {
            lines: vec!["r1 1".to_owned()],
        };
        let history = draw_model(&single, 75, 20);
        assert!(
            history.contains("History") || history.contains("历史") || history.contains("r1"),
            "history={history}"
        );
        single.overlay = Overlay::Alert {
            title: "Edit conflict".to_owned(),
            body: "draft kept".to_owned(),
        };
        let alert = draw_model(&single, 75, 20);
        assert!(
            alert.contains("conflict") || alert.contains("draft"),
            "alert={alert}"
        );
        for screen in [
            Screen::Review,
            Screen::Statistics,
            Screen::Attachments,
            Screen::Trash,
            Screen::Settings,
        ] {
            single.overlay = Overlay::None;
            single.screen = screen;
            let text = draw_model(&single, 80, 20);
            assert!(!text.is_empty(), "empty draw for {screen:?}");
        }
        single.screen = Screen::Timeline;
        single.preview = String::new();
        single.items.push(ListRow::new("empty", ""));
        let empty_preview = draw_model(&single, 80, 16);
        assert!(
            contains_any(&empty_preview, &["Write", "写", "Thought Stream", "思维流"]),
            "empty_preview={empty_preview}"
        );
    }
}
