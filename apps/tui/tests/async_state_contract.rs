//! Behavior Contract
//! Capability: background IO cannot steal input or leave memo bodies stuck loading.
//! Scenarios: notices during capture; missing body snapshots; opening a saved memo outside current filters.
//! Observable outcomes: preserved text/input, explicit body errors, an identity-bound reader.
//! TDD proof: notices replace Compose, one failed body aborts the batch, and `ShowCreated` opens the timeline.
//! Excludes: thread scheduling and OS process execution.

#[cfg(test)]
pub mod support;
#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Test fixtures and application effects must succeed before state assertions"
)]
mod tests {
    use super::support::{RuntimeFixture, command, feed, model_with_memos, run_effect};
    use lomo_tui::{
        effects::RuntimeMessage,
        event::Command,
        messages::apply_message,
        model::{BodyState, InputMode, View},
        update::apply_command,
    };

    #[test]
    fn background_notice_cannot_take_over_a_capture_input() {
        let mut model = model_with_memos(2, 80, 24).expect("fixture and operation must succeed");
        let _effect = apply_command(&mut model, Command::Compose);
        assert_eq!(
            apply_command(&mut model, Command::Type("正在写".to_owned())),
            None
        );
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Message {
                    title: "attachment".to_owned(),
                    lines: vec!["opened".to_owned()]
                }
            ),
            None
        );
        assert_eq!(model.input, InputMode::Compose);
        assert_eq!(model.draft.text.text(), "正在写");
    }

    #[test]
    fn disappeared_body_snapshots_fail_individually_instead_of_staying_loading() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        let mut model = model_with_memos(2, 80, 24).expect("fixture and operation must succeed");
        for memo in &mut super::support::feed_mut(&mut model)
            .expect("fixture and operation must succeed")
            .memos
        {
            memo.body = BodyState::Pending;
        }
        let effect = lomo_tui::navigation::hydrate_visible(&mut model);
        run_effect(&fixture.runtime, &mut model, effect)
            .expect("fixture and operation must succeed");
        assert!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .memos
                .iter()
                .all(|memo| matches!(memo.body, BodyState::Failed(_)))
        );
    }

    #[test]
    fn viewing_a_saved_memo_works_outside_the_active_filter() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        let id = lomo_tui::drafts::commit_capture(&fixture.runtime, 1, "saved outside filter")
            .expect("fixture and operation must succeed");
        let mut model = model_with_memos(2, 80, 24).expect("fixture and operation must succeed");
        let original = feed(&model)
            .expect("fixture and operation must succeed")
            .clone();
        model.last_created = Some(id.clone());
        command(&fixture.runtime, &mut model, Command::ShowCreated)
            .expect("fixture and operation must succeed");
        assert!(matches!(model.view, View::Reader { .. }));
        assert_eq!(model.selected_id(), Some(id.as_str()));
        command(&fixture.runtime, &mut model, Command::Back)
            .expect("fixture and operation must succeed");
        assert_eq!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .selected,
            original.selected
        );
    }

    #[test]
    fn restoring_a_loading_body_requests_it_for_the_new_view_generation() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture and operation must succeed");
        super::support::feed_mut(&mut model)
            .expect("fixture and operation must succeed")
            .memos
            .first_mut()
            .ok_or("memo")
            .expect("fixture and operation must succeed")
            .body = BodyState::Pending;
        assert!(matches!(
            lomo_tui::navigation::hydrate_visible(&mut model),
            Some(lomo_tui::effects::Effect::Bodies { .. })
        ));
        model.next_epoch();
        assert!(matches!(
            lomo_tui::navigation::hydrate_visible(&mut model),
            Some(lomo_tui::effects::Effect::Bodies { .. })
        ));
    }

    #[test]
    fn a_page_finishing_while_reading_is_retained_for_the_returned_feed() {
        let mut model = model_with_memos(2, 80, 24).expect("fixture and operation must succeed");
        let _effect = lomo_tui::update::reload_feed(&mut model);
        let epoch = model.epoch;
        assert_eq!(apply_command(&mut model, Command::Accept), None);
        let mut updated = super::support::memo("memo-0", "updated snapshot")
            .expect("fixture and operation must succeed");
        updated.revision = 2;
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Page {
                    epoch,
                    append: false,
                    cards: vec![updated],
                    next: None,
                    total: Some(1),
                }
            ),
            None
        );
        assert_eq!(apply_command(&mut model, Command::Back), None);
        assert_eq!(
            model.selected_memo().map(|memo| memo.summary.as_str()),
            Some("updated snapshot")
        );
    }

    #[test]
    fn body_cache_keeps_the_visible_neighborhood_and_releases_distant_versions() {
        let mut model = model_with_memos(320, 80, 24).expect("fixture and operation must succeed");
        let _effect = lomo_tui::navigation::hydrate_visible(&mut model);
        assert!(matches!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .memos
                .first()
                .ok_or("first")
                .expect("fixture and operation must succeed")
                .body,
            BodyState::Ready(_)
        ));
        assert_eq!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .memos
                .last()
                .ok_or("last")
                .expect("fixture and operation must succeed")
                .body,
            BodyState::Pending
        );
    }

    #[test]
    fn a_date_reply_cannot_apply_to_a_reopened_date_input() {
        let mut model = model_with_memos(1, 80, 24).expect("feed");
        let _effect = apply_command(&mut model, Command::CustomDate);
        let _effect = apply_command(&mut model, Command::Type("today".to_owned()));
        let effect = apply_command(&mut model, Command::Accept).expect("date request");
        let lomo_tui::effects::Effect::Date { ticket, .. } = effect else {
            panic!("date request");
        };
        let _effect = apply_command(&mut model, Command::Back);
        let _effect = apply_command(&mut model, Command::CustomDate);
        let _effect = apply_message(
            &mut model,
            RuntimeMessage::Date {
                ticket,
                from: 0,
                until: 86_400_000,
                label: "old date".to_owned(),
            },
        );
        assert!(matches!(model.input, InputMode::Date { .. }));
        assert_eq!(
            feed(&model)
                .expect("feed")
                .query
                .filters
                .date_from_inclusive_ms,
            None
        );
    }
}
