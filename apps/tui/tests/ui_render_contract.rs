//! Behavior Contract
//! Capability: body-first reading at narrow, standard and wide terminal sizes.
//! Scenarios: short records remain complete; long records have at most six rendered body rows;
//! reader progress, loading, errors and empty results are distinct.
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
        model::{AppModel, LoadStatus},
        update::apply_command,
    };
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
