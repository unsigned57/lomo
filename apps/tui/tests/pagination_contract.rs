//! Behavior Contract
//! Capability: browse every matching memo and preserve a deep reading anchor through refresh.
//! Scenarios: 320 memos span multiple pages; refreshing while reading the final page retains context.
//! Observable outcomes: unique `MemoIds`, loaded and total counts, selection and semantic anchors.
//! TDD proof: refreshing the final page returns only 48 memos and loses the selected memo.
//! Excludes: filesystem watcher timing and physical terminal output.

#[cfg(test)]
pub mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Test fixtures and application effects must succeed before state assertions"
)]
mod tests {
    use super::support::{RuntimeFixture, TestResult, command, feed, run_effect};
    use lomo_tui::{event::Command, model::AppModel, ops::bootstrap_model};

    fn load_every_page(fixture: &RuntimeFixture, model: &mut AppModel) -> TestResult {
        while feed(model)?.next_cursor.is_some() {
            command(&fixture.runtime, model, Command::Last)?;
        }
        command(&fixture.runtime, model, Command::Last)?;
        Ok(())
    }

    #[test]
    fn more_than_256_memos_are_reachable_without_duplicate_pages() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        fixture
            .seed(320)
            .expect("fixture and operation must succeed");
        let mut model = bootstrap_model(&fixture.runtime, AppModel::new(80, 24))
            .expect("fixture and operation must succeed");
        assert_eq!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .memos
                .len(),
            48
        );
        assert_eq!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .total,
            Some(320)
        );
        load_every_page(&fixture, &mut model).expect("fixture and operation must succeed");
        let ids = feed(&model)
            .expect("fixture and operation must succeed")
            .memos
            .iter()
            .map(|memo| memo.id.as_str())
            .collect::<std::collections::BTreeSet<_>>();
        assert_eq!(ids.len(), 320);
        assert_eq!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .memos
                .len(),
            320
        );
    }

    #[test]
    fn refresh_retains_a_memo_beyond_the_first_page() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        fixture
            .seed(320)
            .expect("fixture and operation must succeed");
        let mut model = bootstrap_model(&fixture.runtime, AppModel::new(80, 24))
            .expect("fixture and operation must succeed");
        load_every_page(&fixture, &mut model).expect("fixture and operation must succeed");
        let before = feed(&model)
            .expect("fixture and operation must succeed")
            .clone();
        let effect = lomo_tui::update::reload_feed(&mut model);
        run_effect(&fixture.runtime, &mut model, effect)
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
        assert_eq!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .memos
                .len(),
            320
        );
    }

    #[test]
    fn paginated_search_reports_loaded_and_total_separately() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        fixture
            .seed(320)
            .expect("fixture and operation must succeed");
        let mut model = bootstrap_model(&fixture.runtime, AppModel::new(80, 24))
            .expect("fixture and operation must succeed");
        command(&fixture.runtime, &mut model, Command::Search)
            .expect("fixture and operation must succeed");
        command(
            &fixture.runtime,
            &mut model,
            Command::Type("needle".to_owned()),
        )
        .expect("fixture and operation must succeed");
        command(&fixture.runtime, &mut model, Command::Accept)
            .expect("fixture and operation must succeed");
        assert_eq!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .total,
            Some(320)
        );
        assert_eq!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .memos
                .len(),
            48
        );
        load_every_page(&fixture, &mut model).expect("fixture and operation must succeed");
        assert_eq!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .memos
                .len(),
            320
        );
    }

    #[test]
    fn results_highlight_the_actual_match_and_keep_its_evidence_after_hydration() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        fixture.seed(2).expect("fixture and operation must succeed");
        let mut model = bootstrap_model(&fixture.runtime, AppModel::new(80, 24))
            .expect("fixture and operation must succeed");
        command(&fixture.runtime, &mut model, Command::Search)
            .expect("fixture and operation must succeed");
        command(
            &fixture.runtime,
            &mut model,
            Command::Type("needle".to_owned()),
        )
        .expect("fixture and operation must succeed");
        command(&fixture.runtime, &mut model, Command::Accept)
            .expect("fixture and operation must succeed");
        let effect = lomo_tui::navigation::hydrate_visible(&mut model);
        run_effect(&fixture.runtime, &mut model, effect)
            .expect("fixture and operation must succeed");
        let rows = lomo_tui::feed_layout::feed_lines(
            feed(&model).expect("fixture and operation must succeed"),
            76,
        );
        assert!(
            rows.iter().any(|row| row
                .line
                .spans
                .iter()
                .any(|span| span.content.contains("needle")
                    && span.style.fg == Some(ratatui::style::Color::Cyan))),
            "search highlights disappeared after loading the body"
        );
    }

    #[test]
    fn cancelling_date_input_does_not_invalidate_feed_continuation() {
        let fixture = RuntimeFixture::new().expect("runtime");
        fixture.seed(100).expect("memos");
        let mut model = bootstrap_model(&fixture.runtime, AppModel::new(80, 24)).expect("feed");
        command(&fixture.runtime, &mut model, Command::CustomDate).expect("date input");
        command(
            &fixture.runtime,
            &mut model,
            Command::Type("2026-09".to_owned()),
        )
        .expect("type date");
        command(&fixture.runtime, &mut model, Command::Back).expect("cancel");
        command(&fixture.runtime, &mut model, Command::Last).expect("continue feed");
        assert_eq!(feed(&model).expect("feed").memos.len(), 96);
    }
}
