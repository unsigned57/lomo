//! Behavior Contract
//! Capability: read memo bodies directly in one centered content stream.
//! Scenarios: multiple bodies; reader return; stale hydration; search evidence retention;
//! scrolling and refresh deletion; clearing filters restores the original reading position.
//! Observable outcomes: visible bodies, memo identity, anchors, preserved search evidence.
//! TDD proof: targeted `reading_flow_contract` runs expose stale body replacement, discarded
//! search evidence, lost filter origins and selection jumping past the closest visible memo.
//! Excludes: terminal image protocols and persistence.

#[cfg(test)]
pub mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Test fixtures and application effects must succeed before state assertions"
)]
mod tests {
    use super::support::{feed, feed_mut, memo, model_with_memos};
    use lomo_tui::effects::RuntimeMessage;
    use lomo_tui::event::Command;
    use lomo_tui::feed_layout::{feed_lines, top_row};
    use lomo_tui::messages::apply_message;
    use lomo_tui::model::{AppModel, BodyState, MemoCard, View};
    use lomo_tui::update::apply_command;
    use lomo_workspace::MemoId;
    use ratatui::{Terminal, backend::TestBackend};
    use std::fmt::Write;

    #[test]
    fn home_reads_multiple_bodies_without_a_preview_pane() {
        let mut model = AppModel::new(120, 30);
        if let View::Feed(feed) = &mut model.view {
            feed.memos = [("one", "First memo body"), ("two", "Second memo body")]
                .into_iter()
                .map(|(id, body)| {
                    Ok::<_, Box<dyn std::error::Error>>(MemoCard {
                        id: MemoId::parse(id).expect("fixture and operation must succeed"),
                        date: "2026-09-11".to_owned(),
                        time: "12:00".to_owned(),
                        summary: body.to_owned(),
                        body: BodyState::Ready(std::sync::Arc::new(
                            lomo_tui::content::MemoBody::parse(body.to_owned())
                                .expect("fixture and operation must succeed"),
                        )),
                        tags: Vec::new(),
                        attachments: Vec::new(),
                        fingerprint: "version".to_owned(),
                        revision: 1,
                        pinned: false,
                        trashed: false,
                        excerpt: None,
                    })
                })
                .collect::<Result<_, _>>()
                .expect("fixture and operation must succeed");
            feed.reconcile();
        }
        let mut terminal =
            Terminal::new(TestBackend::new(120, 30)).expect("fixture and operation must succeed");
        terminal
            .draw(|frame| lomo_tui::ui::draw(frame, &model))
            .expect("fixture and operation must succeed");
        let text: String = terminal
            .backend()
            .buffer()
            .content
            .iter()
            .map(ratatui::buffer::Cell::symbol)
            .collect();
        assert!(text.contains("First memo body") && text.contains("Second memo body"));
        assert!(
            !text.contains("Preview") && !text.contains("预览"),
            "body browsing must not require a separate preview: {text}"
        );
    }

    #[test]
    fn stale_body_reply_cannot_replace_the_current_memo_version() {
        let mut model = model_with_memos(1, 80, 24).expect("fixture and operation must succeed");
        let before = model.selected_memo().cloned();
        let mut incoming =
            memo("memo-0", "obsolete request body").expect("fixture and operation must succeed");
        incoming.revision = 2;
        // A reply whose request was never issued is a dead generation: it
        // degrades instead of landing on whatever bodies happen to be current.
        let stale = model.next_req();
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Bodies {
                    req: stale,
                    bodies: vec![
                        super::support::body_reply(incoming)
                            .expect("fixture and operation must succeed")
                    ],
                }
            ),
            None
        );
        assert_eq!(model.selected_memo(), before.as_ref());
    }

    #[test]
    fn hydration_preserves_the_search_hit_and_its_query_order() {
        let mut model = model_with_memos(2, 80, 24).expect("fixture and operation must succeed");
        let req = model.request(lomo_tui::model::PendingKind::Bodies);
        let state = feed_mut(&mut model).expect("fixture and operation must succeed");
        state.query.text = "needle".to_owned();
        let first = state
            .memos
            .first_mut()
            .ok_or("first memo")
            .expect("fixture and operation must succeed");
        first.excerpt = Some(lomo_application::search_excerpt::SearchExcerpt {
            text: "text around needle".to_owned(),
            highlights: std::iter::once(12..18).collect(),
            source: lomo_application::search_excerpt::MatchSource::Body,
            body_start: Some(0),
        });
        first.body = BodyState::Loading { req };
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Bodies {
                    req,
                    bodies: vec![
                        super::support::body_reply(
                            memo("memo-0", "complete body")
                                .expect("fixture and operation must succeed")
                        )
                        .expect("fixture and operation must succeed")
                    ],
                }
            ),
            None
        );
        assert_eq!(
            model
                .selected_memo()
                .and_then(|card| card.excerpt.as_ref().map(|excerpt| excerpt.text.as_str())),
            Some("text around needle")
        );
        assert_eq!(model.selected_id(), Some("memo-0"));
    }

    #[test]
    fn scroll_up_selects_the_closest_visible_memo() {
        let mut model = model_with_memos(20, 80, 24).expect("fixture and operation must succeed");
        assert_eq!(apply_command(&mut model, Command::Move(12)), None);
        assert_eq!(apply_command(&mut model, Command::Page(-1)), None);
        let state = feed(&model).expect("fixture and operation must succeed");
        let layout = lomo_tui::ui::layout_for(&model);
        let rows = feed_lines(state, layout.content.width);
        let top = top_row(&rows, state.anchor.as_ref());
        let closest = rows
            .iter()
            .skip(top)
            .take(usize::from(layout.content.height))
            .next_back()
            .map(|row| &row.id);
        assert_eq!(state.selected.as_ref(), closest);
    }

    #[test]
    fn clear_filters_restores_the_unfiltered_reading_position() {
        let mut model = model_with_memos(20, 80, 24).expect("fixture and operation must succeed");
        assert_eq!(apply_command(&mut model, Command::Move(12)), None);
        let before = feed(&model)
            .expect("fixture and operation must succeed")
            .clone();
        let effect = apply_command(
            &mut model,
            Command::SelectTag(Some(std::sync::Arc::from("reading"))),
        );
        let Some(lomo_tui::effects::Effect::Query(request)) = effect else {
            panic!("selecting a tag re-queries the feed");
        };
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Page {
                    req: request.req,
                    append: false,
                    order: vec![
                        MemoId::parse("other").expect("fixture and operation must succeed")
                    ],
                    cards: vec![
                        memo("other", "filtered memo").expect("fixture and operation must succeed")
                    ],
                    next: None,
                    total: Some(1),
                }
            ),
            None
        );
        let _effect = apply_command(&mut model, Command::ClearFilters);
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

    #[test]
    fn deleting_an_anchor_selects_a_surviving_neighbor_and_reports_it() {
        let mut model = model_with_memos(8, 80, 24).expect("fixture and operation must succeed");
        assert_eq!(apply_command(&mut model, Command::Move(5)), None);
        let cards: Vec<MemoCard> = feed(&model)
            .expect("fixture and operation must succeed")
            .memos
            .iter()
            .filter(|card| card.id.as_str() != "memo-5")
            .cloned()
            .collect();
        let req = super::support::pending_page(&mut model).expect("page request in flight");
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Page {
                    req,
                    append: false,
                    order: cards.iter().map(|card| card.id.clone()).collect(),
                    cards,
                    next: None,
                    total: Some(7),
                }
            ),
            None
        );
        assert_eq!(model.selected_id(), Some("memo-6"));
        assert!(
            model
                .status
                .as_ref()
                .is_some_and(|status| !status.is_empty())
        );
    }

    #[test]
    fn reader_returns_to_exact_feed_context_after_independent_scrolling() {
        let mut model = model_with_memos(20, 80, 24).expect("fixture and operation must succeed");
        assert_eq!(apply_command(&mut model, Command::Move(12)), None);
        let before = feed(&model)
            .expect("fixture and operation must succeed")
            .clone();
        assert_eq!(apply_command(&mut model, Command::Accept), None);
        assert!(matches!(model.view, View::Reader { .. }));
        assert_eq!(apply_command(&mut model, Command::Scroll(10)), None);
        assert_eq!(apply_command(&mut model, Command::Back), None);
        assert_eq!(
            feed(&model)
                .expect("fixture and operation must succeed")
                .query,
            before.query
        );
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

    #[test]
    fn resizing_preserves_the_logical_body_position_in_the_feed() {
        let mut model = model_with_memos(20, 100, 12).expect("fixture and operation must succeed");
        let mut text = String::new();
        for index in 0..300 {
            write!(text, "{index:03}|").expect("text fixture");
        }
        *feed_mut(&mut model)
            .expect("fixture and operation must succeed")
            .memos
            .first_mut()
            .ok_or("first memo")
            .expect("fixture and operation must succeed") =
            memo("memo-0", &format!("```text\n{text}\n```"))
                .expect("fixture and operation must succeed");
        // Three rows down: past the date-time line and two body rows, so the third body row
        // (starting at token 047) is the top of the viewport.
        assert_eq!(apply_command(&mut model, Command::Scroll(3)), None);
        lomo_tui::update::apply_resize(&mut model, 48, 12);
        let state = feed(&model).expect("fixture and operation must succeed");
        let rows = feed_lines(state, lomo_tui::ui::layout_for(&model).content.width);
        let top = rows
            .get(top_row(&rows, state.anchor.as_ref()))
            .ok_or("visible row")
            .expect("fixture and operation must succeed");
        assert!(
            top.line.to_string().contains("047|"),
            "the reading anchor moved: {}",
            top.line
        );
    }

    #[test]
    fn reader_page_down_does_not_skip_a_line_between_pages() {
        let mut model = model_with_memos(1, 80, 24).expect("feed");
        let mut body = String::from("```\n");
        for line in 0..60 {
            writeln!(body, "line {line}").expect("fixture");
        }
        body.push_str("```\n");
        *feed_mut(&mut model)
            .expect("feed")
            .memos
            .first_mut()
            .expect("memo") = memo("memo-0", &body).expect("body");
        assert_eq!(apply_command(&mut model, Command::Accept), None);
        let before = lomo_tui::reader::page(&model).expect("reader");
        let last_visible = before.top + usize::from(before.area.height) - 1;
        assert_eq!(apply_command(&mut model, Command::Page(1)), None);
        assert_eq!(
            lomo_tui::reader::page(&model).expect("reader").top,
            last_visible
        );
    }

    #[test]
    fn a_deleted_scroll_anchor_follows_its_neighbor_and_reports_the_change() {
        let mut model = model_with_memos(8, 80, 24).expect("feed");
        assert_eq!(apply_command(&mut model, Command::Move(3)), None);
        let cards: Vec<MemoCard> = feed(&model)
            .expect("feed")
            .memos
            .iter()
            .filter(|memo| memo.id.as_str() != "memo-0")
            .cloned()
            .collect();
        let req = super::support::pending_page(&mut model).expect("page request in flight");
        assert_eq!(
            apply_message(
                &mut model,
                RuntimeMessage::Page {
                    req,
                    append: false,
                    order: cards.iter().map(|card| card.id.clone()).collect(),
                    cards,
                    next: None,
                    total: Some(7)
                }
            ),
            None
        );
        assert_eq!(
            feed(&model)
                .expect("feed")
                .anchor
                .as_ref()
                .map(|anchor| anchor.id.as_str()),
            Some("memo-1")
        );
        assert_eq!(model.selected_id(), Some("memo-3"));
        assert!(model.status.is_some());
    }

    #[test]
    fn narrowing_the_window_keeps_the_selected_memo_visible() {
        let mut model = model_with_memos(4, 80, 24).expect("feed");
        for card in &mut feed_mut(&mut model).expect("feed").memos {
            *card = memo(card.id.as_str(), &"word ".repeat(12)).expect("body");
        }
        assert_eq!(apply_command(&mut model, Command::Move(3)), None);
        lomo_tui::update::apply_resize(&mut model, 22, 24);
        let state = feed(&model).expect("feed");
        let area = lomo_tui::ui::layout_for(&model).content;
        let rows = feed_lines(state, area.width);
        let top = top_row(&rows, state.anchor.as_ref());
        assert_eq!(model.selected_id(), Some("memo-3"));
        assert!(
            rows.iter()
                .skip(top)
                .take(usize::from(area.height))
                .any(|row| Some(&row.id) == state.selected.as_ref())
        );
    }
}
