//! Behavior Contract
//! Capability: body-first reading at narrow, standard and wide terminal sizes.
//! Scenarios: short records remain complete; long records have at most six rendered body rows;
//! reader progress, loading, errors and empty results are distinct; a status toast outlives
//! unrelated commands and yields only to newer feedback or a top-level Esc acknowledgement;
//! every view carries its own hints; chrome never repeats hints;
//! narrow headers keep the most important controls; overlays are titled; cards open with
//! their own date and time instead of a shared date header; capture and search are bordered
//! panels; stale search results stay on screen until replaced; hints name the Esc target.
//! Observable outcomes: `TestBackend` text, six-line body limit and default foreground color.
//! TDD proof: related `reading_flow_contract` and `markdown_view_contract` tests failed before implementation.
//! Excludes: actual terminal image bytes (covered by `graphics_contract` and the PTY check).

#[cfg(test)]
pub mod support;
#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Test fixtures and application effects must succeed before state assertions"
)]
mod tests {
    use super::support::{feed_mut, memo, model_with_memos};
    use lomo_tui::{
        event::Command,
        input::TextBuffer,
        model::{AppModel, FeedKind, FeedState, InputMode, LoadStatus, SelectionList, View},
        update::apply_command,
    };
    use lomo_workspace::MemoId;
    use ratatui::{Terminal, backend::TestBackend};

    fn render(model: &AppModel) -> Result<String, Box<dyn std::error::Error>> {
        let mut terminal = Terminal::new(TestBackend::new(model.width, model.height))?;
        terminal.draw(|frame| lomo_tui::ui::draw(frame, model))?;
        Ok(terminal
            .backend()
            .buffer()
            .content
            .iter()
            .map(ratatui::buffer::Cell::symbol)
            .collect())
    }

    #[test]
    fn bodies_remain_visible_at_narrow_standard_and_wide_sizes() {
        for (width, height) in [(30, 12), (80, 24), (160, 40)] {
            let model =
                model_with_memos(2, width, height).expect("fixture and operation must succeed");
            let text = render(&model).expect("fixture and operation must succeed");
            assert!(text.contains("Body 0"));
            assert!(text.contains("Body 1"));
            assert!(!text.contains("Preview") && !text.contains("预览"));
        }
    }

    #[test]
    fn only_the_reader_exposes_body_lines_after_the_six_line_limit() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture and operation must succeed");
        *feed_mut(&mut model)
            .expect("fixture and operation must succeed")
            .memos
            .first_mut()
            .ok_or("memo")
            .expect("fixture and operation must succeed") = memo(
            "memo-0",
            "```\nline 1\nline 2\nline 3\nline 4\nline 5\nline 6\nline 7\n```",
        )
        .expect("fixture and operation must succeed");
        let text = render(&model).expect("fixture and operation must succeed");
        assert!(text.contains("line 6"));
        assert!(!text.contains("line 7"));
        assert_eq!(apply_command(&mut model, Command::Accept), None);
        let text = render(&model).expect("fixture and operation must succeed");
        assert!(text.contains("line 7"));
        assert!(text.contains("100%"));
    }

    #[test]
    fn errors_loading_and_no_results_are_distinct() {
        let mut model = AppModel::new(80, 24);
        let loading = render(&model).expect("fixture and operation must succeed");
        feed_mut(&mut model)
            .expect("fixture and operation must succeed")
            .load = LoadStatus::Failed("disk unavailable".to_owned());
        assert!(
            render(&model)
                .expect("fixture and operation must succeed")
                .contains("disk unavailable")
        );
        let state = feed_mut(&mut model).expect("fixture and operation must succeed");
        state.load = LoadStatus::Ready;
        state.query.text = "nothing".to_owned();
        let empty = render(&model).expect("fixture and operation must succeed");
        assert_ne!(empty, loading);
        assert!(!empty.contains("disk unavailable"));
    }

    #[test]
    fn active_filters_show_a_removal_affordance() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture and operation must succeed");
        feed_mut(&mut model)
            .expect("fixture and operation must succeed")
            .query
            .filters
            .tag = Some("reading".to_owned());
        assert!(
            render(&model)
                .expect("fixture and operation must succeed")
                .contains("#reading ×")
        );
    }

    fn screen_rows(model: &AppModel) -> Vec<String> {
        let mut terminal = Terminal::new(TestBackend::new(model.width, model.height))
            .expect("fixture and operation must succeed");
        terminal
            .draw(|frame| lomo_tui::ui::draw(frame, model))
            .expect("fixture and operation must succeed");
        let buffer = terminal.backend().buffer();
        (0..model.height)
            .map(|y| {
                (0..model.width)
                    .map(|x| buffer[(x, y)].symbol())
                    .collect::<String>()
            })
            .collect()
    }

    /// I9: feedback is classified, not disposable — a toast holds the status
    /// line through unrelated commands, yields only to newer feedback, and a
    /// top-level Esc acknowledges what remains.
    #[test]
    fn a_status_toast_survives_until_new_feedback_or_esc_acknowledges() {
        let mut model = model_with_memos(3, 80, 24).expect("fixture and operation must succeed");
        model.set_status("Saved");
        let text = render(&model).expect("fixture and operation must succeed");
        assert!(text.contains("Saved") && !text.contains("Enter read"));
        // An unrelated navigation key must not erase the toast.
        assert_eq!(apply_command(&mut model, Command::Move(1)), None);
        let text = render(&model).expect("fixture and operation must succeed");
        assert!(text.contains("Saved") && !text.contains("Enter read"));
        // Newer feedback replaces it…
        model.set_status("Pinned");
        let text = render(&model).expect("fixture and operation must succeed");
        assert!(text.contains("Pinned") && !text.contains("Saved"));
        // …and the top-level Esc acknowledgement hands the line back to hints.
        assert_eq!(apply_command(&mut model, Command::Back), None);
        let text = render(&model).expect("fixture and operation must succeed");
        assert!(!text.contains("Pinned") && text.contains("Enter read"));
    }

    #[test]
    fn auxiliary_views_carry_their_own_key_hints() {
        let mut model = AppModel::new(80, 24);
        // A seeded row keeps `Enter` dispatchable — an empty list honestly
        // refuses Accept, so the hint bar cannot advertise a dead toggle (I2).
        model.view = View::Tasks(SelectionList::new(vec![lomo_tui::model::TaskRow {
            memo_id: MemoId::parse("m-task").expect("fixture and operation must succeed"),
            line: 0,
            text: "water the plants".to_owned(),
            date: "2026-09-11".to_owned(),
            done: false,
        }]));
        let text = render(&model).expect("fixture and operation must succeed");
        assert!(text.contains("toggle") && !text.contains("Enter read"));
        model.view = View::Settings(lomo_tui::settings::SettingsView {
            file: std::path::PathBuf::from("/cfg/lomo/config.toml"),
            rows: vec![lomo_tui::settings::SettingRow {
                field: lomo_tui::config::SettingsField::Workspace,
                value: "/notes".to_owned(),
                hot: false,
            }],
            selected: 0,
            info: Vec::new(),
            home_dir: None,
        });
        let text = render(&model).expect("fixture and operation must succeed");
        assert!(text.contains("? help") && !text.contains("Enter read"));
        model.view = View::Feed(Box::new(FeedState::new(FeedKind::Trash)));
        if let View::Feed(feed) = &mut model.view {
            feed.load = LoadStatus::Ready;
        }
        let text = render(&model).expect("fixture and operation must succeed");
        assert!(text.contains("Trash is empty") && !text.contains("Write a thought"));
    }

    #[test]
    fn each_card_opens_with_its_date_and_time_and_only_the_selected_card_is_marked() {
        let model = model_with_memos(2, 80, 24).expect("fixture and operation must succeed");
        let rows = screen_rows(&model);
        let stamps = rows
            .iter()
            .filter(|row| row.contains("2026-09-11  12:00"))
            .collect::<Vec<_>>();
        assert_eq!(
            stamps.len(),
            2,
            "one date-time line per card, no group header"
        );
        assert!(stamps.first().is_some_and(|row| row.contains('▎')));
        assert!(stamps.get(1).is_some_and(|row| !row.contains('▎')));
        assert!(!rows.iter().any(|row| row.contains("────")));
    }

    #[test]
    fn chrome_states_counts_and_drafts_without_repeating_the_hint_bar() {
        let mut model = model_with_memos(4, 80, 24).expect("fixture and operation must succeed");
        let text = render(&model).expect("fixture and operation must succeed");
        assert!(text.contains("4 total") && !text.contains("4 loaded"));
        feed_mut(&mut model)
            .expect("fixture and operation must succeed")
            .total = Some(10);
        let text = render(&model).expect("fixture and operation must succeed");
        assert!(text.contains("4 of 10 loaded"));
        model.draft.text = TextBuffer::new("kept thought".to_owned());
        let text = render(&model).expect("fixture and operation must succeed");
        assert!(text.contains("draft"));
        assert!(matches!(
            apply_command(&mut model, Command::Compose),
            None | Some(lomo_tui::effects::Effect::Tags { .. })
        ));
        let rows = screen_rows(&model);
        assert_eq!(
            rows.iter().filter(|row| row.contains("Ctrl+S")).count(),
            1,
            "capture keys belong to the hint bar only"
        );
        assert!(
            rows.iter()
                .any(|row| row.contains('╭') && row.contains("New memo")),
            "capture is a bordered panel titled on its frame"
        );
        assert!(rows.iter().any(|row| row.contains("kept thought")));
        let revision = model.draft.revision;
        assert!(matches!(
            apply_command(&mut model, Command::Back),
            Some(lomo_tui::effects::Effect::PersistDraft {
                revision: persisted,
                content,
                ..
            }) if persisted == revision && content == "kept thought"
        ));
        assert_eq!(apply_command(&mut model, Command::Search), None);
        let rows = screen_rows(&model);
        assert!(
            rows.iter()
                .any(|row| row.contains('╭') && row.contains("Search · Fulltext")),
            "search is a bordered panel with its mode on the frame"
        );
        assert_eq!(
            rows.iter().filter(|row| row.contains("Ctrl+F")).count(),
            1,
            "search keys belong to the hint bar only"
        );
    }

    #[test]
    fn stale_search_results_stay_on_screen_until_replaced() {
        let mut model = model_with_memos(3, 80, 24).expect("fixture and operation must succeed");
        assert_eq!(apply_command(&mut model, Command::Search), None);
        assert!(matches!(
            apply_command(&mut model, Command::Type("term".to_owned())),
            Some(lomo_tui::effects::Effect::Query(_))
        ));
        let text = render(&model).expect("fixture and operation must succeed");
        assert!(text.contains("Body 0") && !text.contains("Loading…"));
        assert!(!text.contains('▎'), "stale results carry no selection mark");
        let req = feed_mut(&mut model)
            .expect("fixture and operation must succeed")
            .pending_page
            .expect("search request in flight");
        assert_eq!(
            lomo_tui::messages::apply_message(
                &mut model,
                lomo_tui::effects::RuntimeMessage::Page {
                    req,
                    append: false,
                    order: vec![MemoId::parse("hit").expect("fixture and operation must succeed")],
                    cards: vec![
                        memo("hit", "term found").expect("fixture and operation must succeed")
                    ],
                    next: None,
                    total: Some(1),
                }
            ),
            None
        );
        assert_eq!(model.status, None, "a requery never reports a lost anchor");
        let text = render(&model).expect("fixture and operation must succeed");
        assert!(text.contains("term found") && !text.contains("Body 0"));
    }

    #[test]
    fn the_strip_names_the_view_and_only_the_status_bar_announces_esc() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture and operation must succeed");
        assert_eq!(apply_command(&mut model, Command::Accept), None);
        let rows = screen_rows(&model);
        let strip = rows.get(1).expect("filter strip row");
        assert!(strip.contains("Reading") && !strip.contains("Esc"));
        assert!(
            rows.last()
                .expect("status row")
                .contains("Esc back to All memos")
        );
        model.push_view(View::Tasks(SelectionList::new(Vec::new())));
        let rows = screen_rows(&model);
        let strip = rows.get(1).expect("filter strip row");
        assert!(strip.contains("Todo") && !strip.contains("Esc"));
        assert!(
            rows.last()
                .expect("status row")
                .contains("Esc back to reading")
        );
    }

    #[test]
    fn the_hint_bar_names_the_esc_target() {
        let mut model = model_with_memos(3, 80, 24).expect("fixture and operation must succeed");
        let text = render(&model).expect("fixture and operation must succeed");
        assert!(
            !text.contains("Esc"),
            "nothing to leave, nothing to announce"
        );
        feed_mut(&mut model)
            .expect("fixture and operation must succeed")
            .query
            .filters
            .tag = Some("reading".to_owned());
        let text = render(&model).expect("fixture and operation must succeed");
        assert!(text.contains("Esc clear filters"));
        model.push_view(View::Tasks(SelectionList::new(Vec::new())));
        let text = render(&model).expect("fixture and operation must succeed");
        assert!(text.contains("Esc back to All memos"));
        assert_eq!(apply_command(&mut model, Command::Palette), None);
        let text = render(&model).expect("fixture and operation must succeed");
        assert!(text.contains("Esc close"));
    }

    #[test]
    fn the_header_carries_no_key_hints_and_panels_share_rounded_frames() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture and operation must succeed");
        let rows = screen_rows(&model);
        let header = rows.first().expect("header row");
        assert!(
            header.contains("Lomo") && !header.contains("Search") && !header.contains("Commands")
        );
        assert!(matches!(
            apply_command(&mut model, Command::Compose),
            Some(lomo_tui::effects::Effect::Tags { .. })
        ));
        assert!(
            render(&model)
                .expect("fixture and operation must succeed")
                .contains('╭')
        );
        let revision = model.draft.revision;
        assert!(matches!(
            apply_command(&mut model, Command::Back),
            Some(lomo_tui::effects::Effect::PersistDraft {
                revision: persisted,
                content,
                ..
            }) if persisted == revision && content.is_empty()
        ));
        assert_eq!(apply_command(&mut model, Command::Search), None);
        assert!(
            render(&model)
                .expect("fixture and operation must succeed")
                .contains('╭')
        );
        assert_eq!(apply_command(&mut model, Command::Back), None);
        assert_eq!(apply_command(&mut model, Command::Palette), None);
        let text = render(&model).expect("fixture and operation must succeed");
        assert!(text.contains('╭') && !text.contains('┌'));
    }

    #[test]
    fn feed_cards_keep_tags_in_the_footer_only_while_the_reader_shows_them_inline() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture and operation must succeed");
        let mut card = memo("memo-0", "hello #work world\n\n#work #home")
            .expect("fixture and operation must succeed");
        card.tags = vec!["work".to_owned(), "home".to_owned()];
        *feed_mut(&mut model)
            .expect("fixture and operation must succeed")
            .memos
            .first_mut()
            .expect("memo") = card;
        let rows = screen_rows(&model);
        assert!(
            rows.iter()
                .any(|row| row.contains("hello") && row.contains("world"))
        );
        assert_eq!(
            rows.iter().filter(|row| row.contains("#work")).count(),
            1,
            "only the footer lists the tag"
        );
        assert_eq!(apply_command(&mut model, Command::Accept), None);
        let rows = screen_rows(&model);
        assert!(rows.iter().any(|row| row.contains("hello #work world")));
    }

    #[test]
    fn overlays_are_titled_and_pickers_announce_an_empty_filter() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture and operation must succeed");
        assert_eq!(apply_command(&mut model, Command::Actions), None);
        let text = render(&model).expect("fixture and operation must succeed");
        assert!(text.contains("Memo actions") && text.contains("Type to filter"));
        assert_eq!(
            apply_command(&mut model, Command::Type("zzz".to_owned())),
            None
        );
        let text = render(&model).expect("fixture and operation must succeed");
        assert!(text.contains("No matches"));
        assert_eq!(apply_command(&mut model, Command::Back), None);
        lomo_tui::update::apply_resize(&mut model, 80, 40);
        assert_eq!(apply_command(&mut model, Command::Help), None);
        let text = render(&model).expect("fixture and operation must succeed");
        assert!(
            text.contains("Help")
                && text.contains("m         pin")
                && text.contains(":         commands")
        );
        model.input = InputMode::Message {
            title: "History".to_owned(),
            lines: vec!["r1".to_owned()],
            scroll: 0,
        };
        let text = render(&model).expect("fixture and operation must succeed");
        assert!(text.contains("History") && text.contains("r1"));
    }

    #[test]
    fn statistics_drop_the_placeholder_panel_and_show_the_legend() {
        let mut model = AppModel::new(80, 24);
        model.view = View::Statistics(lomo_tui::model::StatsView {
            zone: "UTC".to_owned(),
            as_of_year: 2026,
            as_of_month: 9,
            as_of_day: 22,
            total_memos: 12,
            total_words: 300,
            active_days: 4,
            current_streak: 2,
            longest_streak: 5,
            this_week: 1,
            this_month: 3,
            this_year: 12,
            daily: Vec::new(),
        });
        let text = render(&model).expect("fixture and operation must succeed");
        assert!(!text.contains("Flashback") && !text.contains('['));
        assert!(text.contains("Less") && text.contains("More"));
        assert!(text.contains("longest 5"));
    }

    #[test]
    fn narrow_search_cards_keep_the_actual_hit_inside_the_six_visible_lines() {
        let mut model = model_with_memos(1, 22, 24).expect("feed");
        let context = "前".repeat(60);
        let state = feed_mut(&mut model).expect("feed");
        state.query.text = "needle".to_owned();
        state.memos.first_mut().expect("memo").excerpt =
            Some(lomo_application::search_excerpt::SearchExcerpt {
                text: format!("{context}needle"),
                highlights: std::iter::once(context.len()..context.len() + 6).collect(),
                source: lomo_application::search_excerpt::MatchSource::Body,
                body_start: Some(0),
            });
        let rows =
            lomo_tui::feed_layout::feed_lines(super::support::feed(&model).expect("feed"), 18);
        assert!(
            rows.iter()
                .any(|row| row.line.to_string().contains("needle"))
        );
    }
}
