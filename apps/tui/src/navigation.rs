//! Reading navigation uses the same wrapped rows as the renderer.
use crate::effects::{Effect, FeedRequest, PageIntent};
use crate::feed_layout::{ensure_selected_visible, feed_lines, scroll_feed, top_row};
use crate::model::{
    AppModel, BodyState, CardPosition, FeedState, InputMode, LoadStatus, MemoAnchor, TextAnchor,
    View,
};

pub fn move_selection(model: &mut AppModel, delta: i32) -> Option<Effect> {
    let layout = crate::ui::layout_for(model);
    match &mut model.view {
        View::Feed(feed) => {
            let selected = feed
                .selected
                .as_ref()
                .and_then(|id| feed.memos.iter().position(|memo| &memo.id == id))
                .unwrap_or(0);
            let next = selected
                .saturating_add_signed(delta as isize)
                .min(feed.memos.len().saturating_sub(1));
            feed.selected = feed.memos.get(next).map(|memo| memo.id.clone());
            ensure_selected_visible(feed, layout.content.width, layout.content.height);
            maybe_next_page(feed)
        }
        View::Reader { .. } => {
            scroll(model, delta);
            None
        }
        View::Tasks(list) => {
            list.selected = shifted(list.selected, list.items.len(), delta);
            None
        }
        View::Attachments(list) => {
            list.selected = shifted(list.selected, list.items.len(), delta);
            None
        }
        View::Statistics(_) | View::Settings(_) | View::Loading(_) | View::Failed { .. } => None,
    }
}
pub fn scroll(model: &mut AppModel, delta: i32) {
    let layout = crate::ui::layout_for(model);
    let page = crate::reader::page(model);
    match &mut model.view {
        View::Feed(feed) => scroll_feed(feed, layout.content.width, layout.content.height, delta),
        View::Reader { anchor, .. } => {
            if let Some(page) = page {
                let next = page.top.saturating_add_signed(delta as isize).min(
                    page.rows
                        .len()
                        .saturating_sub(usize::from(page.area.height)),
                );
                if let Some(row) = page.rows.get(next) {
                    *anchor = row.anchor;
                }
            }
        }
        View::Tasks(list) => {
            list.selected = shifted(list.selected, list.items.len(), delta);
        }
        View::Attachments(list) => {
            list.selected = shifted(list.selected, list.items.len(), delta);
        }
        View::Statistics(_) | View::Settings(_) | View::Loading(_) | View::Failed { .. } => {}
    }
}
#[must_use]
pub fn maybe_next_page(feed: &mut FeedState) -> Option<Effect> {
    if feed.load == LoadStatus::Loading {
        return None;
    }
    let selected = feed
        .selected
        .as_ref()
        .and_then(|id| feed.memos.iter().position(|memo| &memo.id == id))?;
    if selected + 8 < feed.memos.len() {
        return None;
    }
    let cursor = feed.next_cursor.clone()?;
    feed.load = LoadStatus::Loading;
    Some(Effect::Query(FeedRequest {
        epoch: feed.epoch,
        kind: feed.kind,
        query: feed.query.clone(),
        intent: PageIntent::Append(cursor),
    }))
}
pub fn open_selected(model: &mut AppModel) -> Option<Effect> {
    if let Some(memo) = model.selected_memo().cloned() {
        if !matches!(model.view, View::Reader { .. }) {
            model.push_view(View::Reader {
                memo,
                anchor: TextAnchor::default(),
            });
        }
        return None;
    }
    match &model.view {
        View::Attachments(list) => list
            .items
            .get(list.selected)
            .map(|row| Effect::OpenAttachment(row.path.clone())),
        View::Tasks(list) => list
            .items
            .get(list.selected)
            .cloned()
            .map(Effect::ToggleTask),
        View::Feed(_)
        | View::Reader { .. }
        | View::Statistics(_)
        | View::Settings(_)
        | View::Loading(_)
        | View::Failed { .. } => None,
    }
}
pub fn click(model: &mut AppModel, column: u16, row: u16) -> Option<Effect> {
    let layout = crate::ui::layout_for(model);
    let controls =
        crate::overlays::header_controls(layout.header, &crate::i18n::UiStrings::detect());
    if let Some((_, _, command)) = controls
        .into_iter()
        .find(|(rect, _, _)| rect.contains((column, row).into()))
    {
        return crate::update::apply_command(model, command);
    }
    if let Some((_, _, command)) = crate::filter_controls::controls(model, layout.filters)
        .into_iter()
        .find(|(rect, _, _)| rect.contains((column, row).into()))
    {
        return crate::update::apply_command(model, command);
    }
    if !layout.content.contains((column, row).into()) {
        return None;
    }
    if let View::Tasks(list) = &mut model.view {
        let top = list
            .selected
            .saturating_sub(usize::from(layout.content.height).saturating_sub(1));
        list.selected =
            (top + usize::from(row - layout.content.y)).min(list.items.len().saturating_sub(1));
        return None;
    }
    if let View::Attachments(list) = &mut model.view {
        let top = list
            .selected
            .saturating_sub(usize::from(layout.content.height).saturating_sub(1));
        list.selected =
            (top + usize::from(row - layout.content.y)).min(list.items.len().saturating_sub(1));
        return None;
    }
    if let View::Feed(feed) = &mut model.view {
        let rows = feed_lines(feed, layout.content.width);
        let top = top_row(&rows, feed.anchor.as_ref());
        if let Some(line) = rows.get(top + usize::from(row.saturating_sub(layout.content.y))) {
            feed.selected = Some(line.id.clone());
        }
        return maybe_next_page(feed);
    }
    None
}
#[must_use]
pub fn hydrate_visible(model: &mut AppModel) -> Option<Effect> {
    if !matches!(
        model.input,
        InputMode::Browse | InputMode::Search { .. } | InputMode::Compose
    ) {
        return None;
    }
    let layout = crate::ui::layout_for(model);
    let mut versions = Vec::new();
    match &mut model.view {
        View::Feed(feed) => {
            let rows = feed_lines(feed, layout.content.width);
            let top = top_row(&rows, feed.anchor.as_ref());
            let visible: Vec<_> = rows
                .iter()
                .skip(top.saturating_sub(usize::from(layout.content.height)))
                .take(usize::from(layout.content.height) * 3 + 20)
                .map(|line| &line.id)
                .collect();
            for memo in &mut feed.memos {
                if !visible.contains(&&memo.id) && matches!(memo.body, BodyState::Ready(_)) {
                    memo.body = BodyState::Pending;
                }
                if memo.body.needs_load(model.epoch) && visible.contains(&&memo.id) {
                    memo.body = BodyState::Loading { epoch: model.epoch };
                    versions.push(memo.version());
                }
            }
        }
        View::Reader { memo, .. } if memo.body.needs_load(model.epoch) => {
            memo.body = BodyState::Loading { epoch: model.epoch };
            versions.push(memo.version());
        }
        View::Reader { .. }
        | View::Tasks(_)
        | View::Statistics(_)
        | View::Attachments(_)
        | View::Settings(_)
        | View::Loading(_)
        | View::Failed { .. } => {}
    }
    (!versions.is_empty()).then_some(Effect::Bodies {
        epoch: model.epoch,
        versions,
    })
}
#[must_use]
pub fn shifted(current: usize, len: usize, delta: i32) -> usize {
    current
        .saturating_add_signed(delta as isize)
        .min(len.saturating_sub(1))
}

pub fn first(model: &mut AppModel) {
    match &mut model.view {
        View::Feed(feed) => {
            feed.selected = feed.memos.first().map(|memo| memo.id.clone());
            feed.anchor = feed.selected.clone().map(|id| MemoAnchor {
                id,
                position: CardPosition::Group(0),
            });
        }
        View::Reader { anchor, .. } => *anchor = TextAnchor::default(),
        View::Tasks(list) => list.selected = 0,
        View::Attachments(list) => list.selected = 0,
        View::Statistics(_) | View::Settings(_) | View::Loading(_) | View::Failed { .. } => {}
    }
}

/// Move an input cursor by visual rows while preserving its display column.
pub fn move_cursor(buffer: &mut crate::input::TextBuffer, width: u16, delta: i32) {
    use unicode_segmentation::UnicodeSegmentation;
    let (row, col) = crate::text_layout::cursor_position(buffer.before_cursor(), width);
    let target = row.saturating_add_signed(delta as isize);
    let positions = buffer
        .text()
        .grapheme_indices(true)
        .map(|(byte, _)| byte)
        .chain(std::iter::once(buffer.text().len()));
    let best = positions
        .filter_map(|byte| {
            let prefix = buffer.text().get(..byte)?;
            let (line, column) = crate::text_layout::cursor_position(prefix, width);
            (line == target).then_some((column.abs_diff(col), byte))
        })
        .min_by_key(|(distance, _)| *distance);
    if let Some((_, byte)) = best {
        buffer.set_cursor(byte);
    }
}
