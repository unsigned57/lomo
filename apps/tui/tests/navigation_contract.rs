//! Behavior Contract
//! Capability: typed auxiliary pages retain application behavior and share a reading-context return.
//! Scenarios: tasks toggle Markdown, all seven screens are reachable, daily review refresh remains a review,
//! overdue reminders remain visible on startup.
//! Observable outcomes: source bytes, selected memo identity, review candidates and reminder messages.
//! TDD proof: old generic-row regressions migrated to typed views; refreshing Review returns the whole timeline.
//! Excludes: terminal pixels, network and Android.

#[cfg(test)]
pub mod support;
#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Test fixtures and application effects must succeed before state assertions"
)]
mod tests {
    use super::support::{RuntimeFixture, command, feed};
    use lomo_tui::{
        event::Command,
        model::{AppModel, InputMode, Screen, View},
        ops::bootstrap_model,
    };

    #[test]
    fn task_toggle_changes_the_markdown_checkbox_through_the_session() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        std::fs::write(
            fixture.runtime.workspace.join("2026_09_11.md"),
            "- 10:00:00\n- [ ] buy milk\n",
        )
        .expect("fixture and operation must succeed");
        fixture
            .runtime
            .session
            .rebuild_projection()
            .expect("fixture and operation must succeed");
        let mut model = bootstrap_model(&fixture.runtime, AppModel::new(80, 24))
            .expect("fixture and operation must succeed");
        command(&fixture.runtime, &mut model, Command::Goto(Screen::Tasks))
            .expect("fixture and operation must succeed");
        let View::Tasks(tasks) = &model.view else {
            panic!("tasks view");
        };
        assert_eq!(
            tasks.items.first().map(|task| task.text.as_str()),
            Some("buy milk")
        );
        command(&fixture.runtime, &mut model, Command::ToggleTask)
            .expect("fixture and operation must succeed");
        assert!(
            std::fs::read_to_string(fixture.runtime.workspace.join("2026_09_11.md"))
                .expect("fixture and operation must succeed")
                .contains("- [x] buy milk")
        );
    }

    #[test]
    fn auxiliary_pages_return_to_the_previous_reading_context() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        fixture
            .seed(20)
            .expect("fixture and operation must succeed");
        let mut model = bootstrap_model(&fixture.runtime, AppModel::new(80, 24))
            .expect("fixture and operation must succeed");
        command(&fixture.runtime, &mut model, Command::Move(12))
            .expect("fixture and operation must succeed");
        let before = feed(&model)
            .expect("fixture and operation must succeed")
            .clone();
        for screen in [
            Screen::Timeline,
            Screen::Tasks,
            Screen::Review,
            Screen::Statistics,
            Screen::Attachments,
            Screen::Trash,
            Screen::Settings,
        ] {
            command(&fixture.runtime, &mut model, Command::Goto(screen))
                .expect("fixture and operation must succeed");
            assert_eq!(model.view.screen(), screen);
            command(&fixture.runtime, &mut model, Command::Back)
                .expect("fixture and operation must succeed");
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
        }
    }

    #[test]
    fn refreshing_review_preserves_its_review_candidate_scope() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        fixture
            .seed(80)
            .expect("fixture and operation must succeed");
        let mut model = bootstrap_model(&fixture.runtime, AppModel::new(80, 24))
            .expect("fixture and operation must succeed");
        command(&fixture.runtime, &mut model, Command::Goto(Screen::Review))
            .expect("fixture and operation must succeed");
        let before: Vec<_> = feed(&model)
            .expect("fixture and operation must succeed")
            .memos
            .iter()
            .map(|memo| memo.id.clone())
            .collect();
        command(&fixture.runtime, &mut model, Command::Refresh)
            .expect("fixture and operation must succeed");
        assert_eq!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .memos
                .iter()
                .map(|memo| memo.id.clone())
                .collect::<Vec<_>>(),
            before
        );
    }

    #[test]
    fn overdue_reminders_remain_visible_at_startup() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        std::fs::write(
            fixture.runtime.workspace.join("2026_07_20.md"),
            "- 10:45:00\npay rent @2026-07-20-10:45\n",
        )
        .expect("fixture and operation must succeed");
        fixture
            .runtime
            .session
            .rebuild_projection()
            .expect("fixture and operation must succeed");
        let model = bootstrap_model(&fixture.runtime, AppModel::new(80, 24))
            .expect("fixture and operation must succeed");
        assert!(matches!(model.input, InputMode::Message { ref lines, .. } if !lines.is_empty()));
    }
}
