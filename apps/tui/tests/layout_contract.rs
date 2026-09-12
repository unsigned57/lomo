//! Behavior Contract
//! Capability: one centered reading column, stable card heights and accurate Unicode input geometry.
//! Scenarios: zero/narrow/80/wide windows; growing capture; newline following a full-width line.
//! Observable outcomes: bounded rectangles, cursor coordinates, unchanged card heights.
//! TDD proof: capture stops at 12 rows and exact-width newline advances the cursor twice.
//! Excludes: platform graphics pixels. Former pane/focus assertions are superseded by the single-column contract.

#[cfg(test)]
pub mod support;
#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Test fixtures and application effects must succeed before state assertions"
)]
mod tests {
    use super::support::model_with_memos;
    use lomo_tui::{
        event::Command,
        input::TextBuffer,
        layout::reading_layout,
        model::{AppModel, InputMode},
        update::apply_command,
    };
    use ratatui::layout::Rect;

    #[test]
    fn reading_area_is_centered_and_never_exceeds_96_columns() {
        for width in [0, 1, 2, 20, 80, 100, 120, 220] {
            let area = Rect::new(0, 0, width, 24);
            let layout = reading_layout(area, 0);
            assert!(layout.content.width <= 96);
            assert!(layout.content.right() <= width);
            assert_eq!(layout.content.x, (width - layout.content.width) / 2);
            assert_eq!(layout.header.x, layout.content.x);
        }
    }

    #[test]
    fn capture_grows_to_half_the_main_region_in_a_tall_terminal() {
        let mut model = AppModel::new(120, 80);
        model.input = InputMode::Compose;
        model.draft.text = TextBuffer::new("line\n".repeat(80));
        let layout = lomo_tui::ui::layout_for(&model);
        assert_eq!(layout.composer.height, 38);
        assert_eq!(layout.content.height, 38);
    }

    #[test]
    fn explicit_newline_after_full_width_does_not_double_advance_the_cursor() {
        assert_eq!(lomo_tui::text_layout::cursor_position("中文\n", 4), (1, 0));
        assert_eq!(
            lomo_tui::text_layout::cursor_position("ab中文\nx", 6),
            (1, 1)
        );
    }

    #[test]
    fn selecting_a_card_never_changes_its_height() {
        let mut model = model_with_memos(3, 80, 24).expect("fixture and operation must succeed");
        let before = lomo_tui::feed_layout::feed_lines(
            super::support::feed(&model).expect("fixture and operation must succeed"),
            76,
        )
        .len();
        assert_eq!(apply_command(&mut model, Command::Move(1)), None);
        assert_eq!(
            lomo_tui::feed_layout::feed_lines(
                super::support::feed(&model).expect("fixture and operation must succeed"),
                76
            )
            .len(),
            before
        );
    }
}
