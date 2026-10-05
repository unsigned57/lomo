//! Reading navigation uses the same wrapped rows as the renderer.
use crate::effects::{Effect, PageIntent};
use crate::feed_layout::{FeedWindow, ensure_selected_visible, feed_window, scroll_feed};
use crate::model::{
    AppModel, BodyState, CardPosition, FeedState, InputMode, LoadStatus, MemoAnchor, PendingKind,
    TextAnchor, View,
};

pub fn move_selection(model: &mut AppModel, delta: i32) -> Option<Effect> {
    let layout = crate::ui::layout_for(model);
    match &mut model.view {
        View::Feed(feed) => {
            let selected = feed
                .selected
                .as_ref()
                .and_then(|id| feed.geometry.borrow_mut().index_of(&feed.memos, id))
                .unwrap_or(0);
            let next = selected
                .saturating_add_signed(delta as isize)
                .min(feed.memos.len().saturating_sub(1));
            feed.selected = feed.memos.get(next).map(|memo| memo.id.clone());
            ensure_selected_visible(feed, layout.content.width, layout.content.height);
            maybe_next_page(model)
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
        View::Settings(settings) => {
            settings.selected = shifted(settings.selected, settings.rows.len(), delta);
            None
        }
        View::Statistics(_) | View::Loading { .. } | View::Failed { .. } => None,
    }
}
pub fn scroll(model: &mut AppModel, delta: i32) {
    if matches!(model.view, View::Reader { .. }) {
        // The reader resolves the scroll against the same memoized page the
        // draw pass reads — one shared geometry, no second wrap.
        if let Some(next) = crate::reader::scroll_anchor(model, delta)
            && let View::Reader { anchor, .. } = &mut model.view
        {
            *anchor = next;
        }
        return;
    }
    let layout = crate::ui::layout_for(model);
    match &mut model.view {
        View::Feed(feed) => scroll_feed(feed, layout.content.width, layout.content.height, delta),
        View::Tasks(list) => {
            list.selected = shifted(list.selected, list.items.len(), delta);
        }
        View::Attachments(list) => {
            list.selected = shifted(list.selected, list.items.len(), delta);
        }
        View::Settings(settings) => {
            settings.selected = shifted(settings.selected, settings.rows.len(), delta);
        }
        View::Reader { .. } | View::Statistics(_) | View::Loading { .. } | View::Failed { .. } => {}
    }
}
/// Near the end of the loaded window, the feed requests the next page under a
/// fresh request identity — the reply can only land on this feed's slot.
#[must_use]
pub fn maybe_next_page(model: &mut AppModel) -> Option<Effect> {
    let cursor = {
        let View::Feed(feed) = &model.view else {
            return None;
        };
        if feed.load == LoadStatus::Loading {
            return None;
        }
        let selected = feed
            .selected
            .as_ref()
            .and_then(|id| feed.geometry.borrow_mut().index_of(&feed.memos, id))?;
        if selected + 8 < feed.memos.len() {
            return None;
        }
        feed.next_cursor.clone()?
    };
    crate::update::issue_query(model, PageIntent::Append(cursor))
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
        View::Attachments(list) => {
            let path = list.items.get(list.selected).map(|row| row.path.clone())?;
            let req = model.request(PendingKind::Attachment);
            Some(Effect::OpenAttachment { req, path })
        }
        View::Tasks(list) => {
            let task = list.items.get(list.selected).cloned()?;
            let req = model.request(PendingKind::Mutation);
            Some(Effect::ToggleTask { req, task })
        }
        View::Settings(settings) => {
            let row = settings.rows.get(settings.selected)?;
            let field = row.field;
            let current = row.value.clone();
            model.input = InputMode::Setting(crate::settings::SettingsEdit::new(field, &current));
            None
        }
        View::Feed(_)
        | View::Reader { .. }
        | View::Statistics(_)
        | View::Loading { .. }
        | View::Failed { .. } => None,
    }
}
pub fn click(model: &mut AppModel, column: u16, row: u16) -> Option<Effect> {
    let layout = crate::ui::layout_for(model);
    if let Some((_, _, command)) = crate::filter_controls::controls(model, layout.filters)
        .into_iter()
        .find(|(rect, _, _)| rect.contains((column, row).into()))
    {
        return crate::update::apply_command(model, command);
    }
    if !layout.content.contains((column, row).into()) {
        return None;
    }
    // The clicked row maps through the same top the renderer drew — the
    // shared `selection_top` keeps the marked row and the hit-test in
    // agreement even while `selected` outlives its list (09-I6-04).
    if let View::Tasks(list) = &mut model.view {
        let top =
            crate::layout::selection_top(list.selected, list.items.len(), layout.content.height);
        list.selected =
            (top + usize::from(row - layout.content.y)).min(list.items.len().saturating_sub(1));
        return None;
    }
    if let View::Attachments(list) = &mut model.view {
        let top =
            crate::layout::selection_top(list.selected, list.items.len(), layout.content.height);
        list.selected =
            (top + usize::from(row - layout.content.y)).min(list.items.len().saturating_sub(1));
        return None;
    }
    if let View::Settings(settings) = &mut model.view {
        let top = crate::layout::selection_top(
            settings.selected,
            settings.rows.len(),
            layout.content.height,
        );
        settings.selected =
            (top + usize::from(row - layout.content.y)).min(settings.rows.len().saturating_sub(1));
        return None;
    }
    if let View::Feed(feed) = &mut model.view {
        let window = feed_window(feed, layout.content.width, layout.content.height);
        if let Some(line) = window
            .rows
            .get(window.top + usize::from(row.saturating_sub(layout.content.y)))
        {
            feed.selected = Some(line.id.clone());
        }
        return maybe_next_page(model);
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
    // The request identity doubles as the generation marker: a `Loading` body
    // whose request left the registry is loadable again, so nothing needs a
    // shared epoch to invalidate dead markers.
    let req = model.next_req();
    let mut versions = Vec::new();
    match &mut model.view {
        View::Feed(feed) => {
            // Hydration reads the same viewport window the renderer lays out —
            // cards outside it are never touched. A `Pending` card whose exact
            // version still holds a parked body restores it here instead of
            // fetching and re-parsing the same bytes again.
            let window = feed_window(feed, layout.content.width, layout.content.height);
            let mut parked = model.body_cache.borrow_mut();
            for memo in feed.memos.get_mut(window.cards.clone()).unwrap_or(&mut []) {
                if memo.body.needs_load(&model.pending) {
                    if let Some((body, attachments)) = parked.take(&memo.version()) {
                        memo.body = BodyState::Ready(body);
                        memo.attachments = attachments;
                        continue;
                    }
                    memo.body = BodyState::Loading { req };
                    versions.push(memo.version());
                }
            }
            evict_distant_bodies(feed, &window, &mut parked);
        }
        View::Reader { memo, .. } if memo.body.needs_load(&model.pending) => {
            if let Some((body, attachments)) = model.body_cache.borrow_mut().take(&memo.version()) {
                memo.body = BodyState::Ready(body);
                memo.attachments = attachments;
            } else {
                memo.body = BodyState::Loading { req };
                versions.push(memo.version());
            }
        }
        View::Reader { .. }
        | View::Tasks(_)
        | View::Statistics(_)
        | View::Attachments(_)
        | View::Settings(_)
        | View::Loading { .. }
        | View::Failed { .. } => {}
    }
    if versions.is_empty() {
        return None;
    }
    model.pending.register(req, PendingKind::Bodies);
    Some(Effect::Bodies { req, versions })
}

/// Parsed bodies stay resident up to this many cards per feed; beyond it the
/// cards farthest from the live window park into `BodyCache` — the parse is
/// never discarded, only moved off the working set.
const RESIDENT_BODY_CAP: usize = 512;
/// One hydration sweep parks at most this many bodies — converges to the cap
/// without letting a seeded mass-residency stall a single keypress.
const EVICT_BATCH: usize = 128;

fn evict_distant_bodies(
    feed: &mut FeedState,
    window: &FeedWindow,
    parked: &mut crate::model::BodyCache,
) {
    let ready = feed
        .memos
        .iter()
        .filter(|memo| matches!(memo.body, BodyState::Ready(_)))
        .count();
    let excess = ready.saturating_sub(RESIDENT_BODY_CAP).min(EVICT_BATCH);
    if excess == 0 {
        return;
    }
    // Quickselect the `excess` farthest off-window residents — unordered among
    // themselves, which a recency queue does not care about.
    let mut farthest: Vec<usize> = feed
        .memos
        .iter()
        .enumerate()
        .filter(|(index, memo)| {
            !window.cards.contains(index) && matches!(memo.body, BodyState::Ready(_))
        })
        .map(|(index, _)| index)
        .collect();
    if farthest.len() > excess {
        farthest.select_nth_unstable_by_key(excess - 1, |index| {
            std::cmp::Reverse(index.abs_diff(window.anchor_card))
        });
        farthest.truncate(excess);
    }
    for index in farthest {
        let Some(memo) = feed.memos.get_mut(index) else {
            continue;
        };
        if let BodyState::Ready(body) = &memo.body {
            parked.park(
                memo.version(),
                std::sync::Arc::clone(body),
                memo.attachments.clone(),
            );
        }
        memo.body = BodyState::Pending;
    }
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
                position: CardPosition::Time,
            });
        }
        View::Reader { anchor, .. } => *anchor = TextAnchor::default(),
        View::Tasks(list) => list.selected = 0,
        View::Attachments(list) => list.selected = 0,
        View::Settings(settings) => settings.selected = 0,
        View::Statistics(_) | View::Loading { .. } | View::Failed { .. } => {}
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
