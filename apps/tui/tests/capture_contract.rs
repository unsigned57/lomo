//! Behavior Contract
//! Capability: reliable quick capture with Unicode editing, private recovery and idempotent submission.
//! Scenarios: multi-line paste/undo; collapse/reopen; workspace isolation; save failure; repeated submit.
//! Observable outcomes: draft bytes, file permissions, memo identity, preserved browsing state.
//! TDD proof: repeated commit creates two memos, capture files are world-readable, and draft failures mark the feed failed.
//! Excludes: a physical IME, external editor behavior and OS crash injection.

#[cfg(test)]
pub mod support;
#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Test fixtures and application effects must succeed before state assertions"
)]
mod tests {
    use super::support::{RuntimeFixture, feed, model_with_memos, runtime_at};
    use lomo_tui::{
        drafts::{capture_path, commit_capture, load_capture, persist_capture},
        effects::RuntimeMessage,
        event::{Command, TextEdit},
        input::TextBuffer,
        messages::apply_message,
        model::{InputMode, LoadStatus, SaveState},
        update::apply_command,
    };
    use std::os::unix::fs::PermissionsExt;

    #[test]
    fn mixed_width_multiline_paste_undo_and_grapheme_deletion_preserve_text() {
        let mut text = TextBuffer::default();
        text.insert("中文 A👩\u{200d}💻\r\n第二行 e\u{301}");
        assert_eq!(text.text(), "中文 A👩\u{200d}💻\n第二行 e\u{301}");
        text.backspace();
        assert_eq!(text.text(), "中文 A👩\u{200d}💻\n第二行 ");
        text.undo();
        assert!(text.text().ends_with("e\u{301}"));
        text.undo();
        assert_eq!(text.text(), "");
        text.redo();
        assert!(text.text().contains("👩\u{200d}💻\n"));
    }

    #[test]
    fn collapse_reopens_the_draft_without_losing_the_browsing_anchor() {
        let mut model = model_with_memos(20, 80, 24).expect("fixture and operation must succeed");
        assert_eq!(apply_command(&mut model, Command::Move(12)), None);
        let before = feed(&model)
            .expect("fixture and operation must succeed")
            .clone();
        let _effect = apply_command(&mut model, Command::Compose);
        assert_eq!(
            apply_command(&mut model, Command::Type("记下来".to_owned())),
            None
        );
        assert_eq!(
            apply_command(&mut model, Command::Edit(TextEdit::Newline)),
            None
        );
        assert_eq!(
            apply_command(&mut model, Command::Type("第二行".to_owned())),
            None
        );
        let _effect = apply_command(&mut model, Command::Back);
        assert_eq!(model.input, InputMode::Browse);
        let _effect = apply_command(&mut model, Command::Compose);
        assert_eq!(model.draft.text.text(), "记下来\n第二行");
        assert_eq!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .anchor,
            before.anchor
        );
        assert_eq!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .selected,
            before.selected
        );
    }

    #[test]
    fn capture_recovers_privately_and_is_isolated_by_workspace() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        persist_capture(&fixture.runtime, 7, "private thought")
            .expect("fixture and operation must succeed");
        assert_eq!(
            load_capture(&fixture.runtime)
                .expect("fixture and operation must succeed")
                .composer
                .text
                .text(),
            "private thought"
        );
        assert_eq!(
            std::fs::metadata(
                capture_path(&fixture.runtime).expect("fixture and operation must succeed")
            )
            .expect("fixture and operation must succeed")
            .permissions()
            .mode()
                & 0o777,
            0o600
        );
        let other = runtime_at(fixture.root.path(), "other-notes")
            .expect("fixture and operation must succeed");
        assert_eq!(
            load_capture(&other)
                .expect("fixture and operation must succeed")
                .composer
                .text
                .text(),
            ""
        );
        persist_capture(&other, 1, "other thought").expect("fixture and operation must succeed");
        assert_eq!(
            load_capture(&fixture.runtime)
                .expect("fixture and operation must succeed")
                .composer
                .text
                .text(),
            "private thought"
        );
    }

    #[test]
    fn a_retried_submission_has_one_durable_memo_identity() {
        let fixture = RuntimeFixture::new().expect("fixture and operation must succeed");
        let first = commit_capture(&fixture.runtime, 1, "saved once")
            .expect("fixture and operation must succeed");
        let retried = commit_capture(&fixture.runtime, 1, "saved once")
            .expect("fixture and operation must succeed");
        assert_eq!(first, retried);
        let query = lomo_application::MemoQuery {
            search_text: None,
            filters: lomo_application::MemoFilters::default(),
            sort: lomo_application::MemoSort::default(),
        };
        assert_eq!(
            fixture
                .runtime
                .session
                .query_count(&query)
                .expect("fixture and operation must succeed"),
            1
        );
        assert_eq!(
            load_capture(&fixture.runtime)
                .expect("fixture and operation must succeed")
                .composer
                .text
                .text(),
            ""
        );
    }

    #[test]
    fn failed_capture_retains_text_without_corrupting_the_feed_load_state() {
        let mut model = model_with_memos(2, 80, 24).expect("fixture and operation must succeed");
        let _effect = apply_command(&mut model, Command::Compose);
        assert_eq!(
            apply_command(&mut model, Command::Type("keep me".to_owned())),
            None
        );
        let effect = apply_command(&mut model, Command::Commit);
        let Some(lomo_tui::effects::Effect::CommitDraft { req, .. }) = effect else {
            panic!("commit request");
        };
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Failed {
                    req,
                    diagnostic: "disk full".to_owned()
                }
            ),
            None
        );
        assert_eq!(model.draft.text.text(), "keep me");
        assert!(matches!(model.draft.save, SaveState::Failed { .. }));
        assert_eq!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .load,
            LoadStatus::Ready
        );
        assert!(
            model
                .status
                .as_ref()
                .is_some_and(|status| status.contains("disk full"))
        );
    }

    #[test]
    fn tag_completion_respects_tag_boundaries_and_can_be_undone() {
        let url = TextBuffer::new("https://example.com/#reading".to_owned());
        assert_eq!(url.tag_prefix(), None);
        let mut text = TextBuffer::new("想法\n#read".to_owned());
        text.complete_tag("reading/book");
        assert_eq!(text.text(), "想法\n#reading/book ");
        text.undo();
        assert_eq!(text.text(), "想法\n#read");
    }
    #[test]
    fn undo_history_is_bounded_and_drops_the_oldest_checkpoint() {
        let mut text = TextBuffer::default();
        for index in 0..120 {
            text.insert(&index.to_string());
        }
        let mut steps = 0;
        loop {
            let before = text.text().to_owned();
            text.undo();
            if text.text() == before {
                break;
            }
            steps += 1;
            assert!(steps <= 120);
        }
        assert_eq!(
            steps, 100,
            "undo history must be bounded at the retained checkpoint cap"
        );
        assert_eq!(
            text.text(),
            "012345678910111213141516171819",
            "evicted checkpoints leave the earliest retained state (before insert 20)"
        );
    }
}
