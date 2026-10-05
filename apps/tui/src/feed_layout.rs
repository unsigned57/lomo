//! Feed geometry is shared by drawing, semantic anchors and mouse hit testing.
//!
//! Cost discipline (I4): a feed window materializes only the viewport plus a
//! fixed row lookahead, and each card's rows are memoized inside `Geometry`
//! keyed on every input the layout consumes — memo version, display fields,
//! body state, excerpt, width, search mode and locale. A card whose key is
//! unchanged never re-wraps; movement, hydration and drawing all read the same
//! window instead of three full-feed passes.
use lomo_workspace::MemoId;
use ratatui::{
    style::{Color, Style},
    text::{Line, Span},
};
use std::{collections::HashMap, sync::Arc};

use crate::i18n::UiLanguage;
use crate::layout::CARD_BODY_LINES;
use crate::model::{BodyState, CardPosition, FeedState, MemoAnchor, MemoCard};
use crate::text_layout::{anchor_row, plain_lines, wrap_lines, wrap_lines_bounded};

/// Rows materialized beyond the viewport on each side — scrolling, hydration
/// and hit testing share this bound.
const LOOKAHEAD_ROWS: usize = 48;
/// `feed_lines` callers that never measured a viewport see this window.
const DEFAULT_VIEWPORT_ROWS: u16 = 24;

#[derive(Clone, Debug)]
pub struct FeedLine {
    pub id: MemoId,
    pub position: CardPosition,
    pub line: Line<'static>,
}

/// The rows the viewport needs plus lookahead, in card order.
pub struct FeedWindow {
    /// Materialized rows covering `cards`.
    pub rows: Vec<FeedLine>,
    /// Index of the anchor's row inside `rows`.
    pub top: usize,
    /// Card range (indices into `feed.memos`) `rows` was materialized from.
    pub cards: std::ops::Range<usize>,
    /// Index of the card holding the anchor row.
    pub anchor_card: usize,
}

/// Which body phase `card_rows` rendered — `Loading`'s request identity is not
/// a render input (the same summary placeholder draws either way).
#[derive(Clone, PartialEq)]
enum BodyKey {
    Pending,
    Loading,
    /// Identity of the shared body allocation — a re-parsed body is a new
    /// `Arc` even at equal content.
    Ready(usize),
    Failed(String),
}

/// Every input `card_rows` reads. `matches` compares field-wise without
/// allocating; a miss clones the card's current inputs into a fresh key.
#[derive(Clone, PartialEq)]
struct CardKey {
    width: u16,
    searching: bool,
    language: UiLanguage,
    revision: u64,
    fingerprint: String,
    pinned: bool,
    date: String,
    time: String,
    summary: String,
    tags: Vec<String>,
    attachments: usize,
    body: BodyKey,
    excerpt: Option<lomo_application::search_excerpt::SearchExcerpt>,
}

impl CardKey {
    fn of(memo: &MemoCard, width: u16, searching: bool, language: UiLanguage) -> Self {
        Self {
            width,
            searching,
            language,
            revision: memo.revision,
            fingerprint: memo.fingerprint.clone(),
            pinned: memo.pinned,
            date: memo.date.clone(),
            time: memo.time.clone(),
            summary: memo.summary.clone(),
            tags: memo.tags.clone(),
            attachments: memo.attachments.len(),
            body: body_key(&memo.body),
            excerpt: memo.excerpt.clone(),
        }
    }
    fn matches(&self, memo: &MemoCard, width: u16, searching: bool, language: UiLanguage) -> bool {
        self.width == width
            && self.searching == searching
            && self.language == language
            && self.revision == memo.revision
            && self.fingerprint == memo.fingerprint
            && self.pinned == memo.pinned
            && self.date == memo.date
            && self.time == memo.time
            && self.summary == memo.summary
            && self.tags == memo.tags
            && self.attachments == memo.attachments.len()
            && self.body == body_key(&memo.body)
            && self.excerpt == memo.excerpt
    }
}

fn body_key(body: &BodyState) -> BodyKey {
    match body {
        BodyState::Pending => BodyKey::Pending,
        BodyState::Loading { .. } => BodyKey::Loading,
        BodyState::Ready(body) => BodyKey::Ready(Arc::as_ptr(body) as usize),
        BodyState::Failed(error) => BodyKey::Failed(error.clone()),
    }
}

struct CardSlot {
    key: CardKey,
    rows: Arc<[(CardPosition, Line<'static>)]>,
}

/// Per-feed card-row memoization. The `memos` vector bounds the slot map —
/// entries linger only while their key matches, and the epoch fields clear the
/// whole map when the width or search mode changes.
#[derive(Default)]
pub struct Geometry {
    width: u16,
    searching: bool,
    language: Option<UiLanguage>,
    /// Viewport height the last consumer asked for — the bare `feed_lines`
    /// surface materializes exactly this many rows plus lookahead.
    viewport_rows: u16,
    slots: HashMap<MemoId, CardSlot>,
    /// One-slot index memoization, verified before reuse — `memos` mutations
    /// that move a card fall back to a scan.
    index_hint: Option<(MemoId, usize)>,
}

impl Geometry {
    fn sync_epoch(&mut self, width: u16, searching: bool, language: UiLanguage) {
        if self.width != width || self.searching != searching || self.language != Some(language) {
            self.slots.clear();
            self.index_hint = None;
            self.width = width;
            self.searching = searching;
            self.language = Some(language);
        }
    }

    /// Rows of one card — memoized; recomputed only when a render input changed.
    fn card_rows(
        &mut self,
        memo: &MemoCard,
        language: UiLanguage,
    ) -> Arc<[(CardPosition, Line<'static>)]> {
        if let Some(slot) = self.slots.get(&memo.id)
            && slot.key.matches(memo, self.width, self.searching, language)
        {
            return Arc::clone(&slot.rows);
        }
        let key = CardKey::of(memo, self.width, self.searching, language);
        let rows: Arc<[(CardPosition, Line<'static>)]> =
            Arc::from(card_rows(memo, self.width, self.searching));
        self.slots.insert(
            memo.id.clone(),
            CardSlot {
                key,
                rows: Arc::clone(&rows),
            },
        );
        rows
    }

    /// Index of `id` in `memos` — the hint covers the repeated anchor lookup;
    /// a moved card re-scans once.
    pub(crate) fn index_of(&mut self, memos: &[MemoCard], id: &MemoId) -> Option<usize> {
        if let Some((hint_id, index)) = &self.index_hint
            && hint_id == id
            && memos.get(*index).is_some_and(|memo| &memo.id == id)
        {
            return Some(*index);
        }
        let index = memos.iter().position(|memo| &memo.id == id)?;
        self.index_hint = Some((id.clone(), index));
        Some(index)
    }
}

/// Owned storage for the body lines a card borrows during layout — a ready
/// card reuses the memoized `Arc` while every other state materializes a
/// bounded plain-text slice.
enum CardLines {
    Ready(Arc<[Line<'static>]>),
    Plain(Vec<Line<'static>>),
}

impl CardLines {
    fn lines(&self) -> &[Line<'static>] {
        match self {
            Self::Ready(lines) => lines,
            Self::Plain(lines) => lines,
        }
    }
}

#[must_use]
pub fn memo_lines(memo: &MemoCard, width: u16, searching: bool) -> Vec<Line<'static>> {
    card_rows(memo, width, searching)
        .into_iter()
        .map(|(_, line)| line)
        .collect()
}

fn card_rows(memo: &MemoCard, width: u16, searching: bool) -> Vec<(CardPosition, Line<'static>)> {
    let mut time = format!(
        "{}{}  {}",
        if memo.pinned { "◆ " } else { "" },
        memo.date,
        memo.time
    );
    if searching && let Some(excerpt) = &memo.excerpt {
        use lomo_application::search_excerpt::MatchSource;
        let s = crate::i18n::UiStrings::detect();
        match excerpt.source {
            MatchSource::Body => {}
            MatchSource::Path => time.push_str(s.text(" · path match", " · 路径命中")),
            MatchSource::Pinyin => time.push_str(s.text(" · pinyin match", " · 拼音命中")),
        }
    }
    let mut lines = vec![(
        CardPosition::Time,
        Line::styled(time, Style::default().fg(Color::DarkGray)),
    )];
    let excerpt = memo.excerpt.as_ref().filter(|_| searching);
    // Cards render at most CARD_BODY_LINES rows; one extra row proves the "more"
    // footer, so a non-excerpt card never wraps the body past that bound. The
    // excerpt itself is already a bounded window of the body.
    let materialized = excerpt.map_or_else(
        || match &memo.body {
            BodyState::Ready(body) => CardLines::Ready(body.card_lines_arc()),
            BodyState::Pending | BodyState::Loading { .. } => {
                CardLines::Plain(plain_lines(&memo.summary))
            }
            BodyState::Failed(error) => CardLines::Plain(plain_lines(error)),
        },
        |excerpt| CardLines::Plain(crate::search_view::excerpt_lines(excerpt)),
    );
    let content = materialized.lines();
    let wrapped = if excerpt.is_some() {
        wrap_lines(content, width)
    } else {
        wrap_lines_bounded(content, width, CARD_BODY_LINES + 1)
    };
    let start = excerpt
        .and_then(crate::search_view::excerpt_anchor)
        .map_or(0, |anchor| anchor_row(&wrapped, anchor).saturating_sub(2));
    lines.extend(
        wrapped
            .iter()
            .skip(start)
            .take(CARD_BODY_LINES)
            .map(|row| (CardPosition::Body(row.anchor), row.line.clone())),
    );
    let mut footer = Vec::new();
    if !memo.tags.is_empty() {
        footer.push(Span::styled(
            memo.tags
                .iter()
                .map(|tag| format!("#{tag}"))
                .collect::<Vec<_>>()
                .join("  "),
            Style::default().fg(Color::Cyan),
        ));
    }
    if !memo.attachments.is_empty() {
        footer.push(Span::styled(
            format!("  ▧ {}", memo.attachments.len()),
            Style::default().fg(Color::DarkGray),
        ));
    }
    if start > 0 || wrapped.len() > start + CARD_BODY_LINES {
        let s = crate::i18n::UiStrings::detect();
        footer.push(Span::styled(
            format!("  ↵ {}", s.text("more", "更多")),
            Style::default().fg(Color::DarkGray),
        ));
    }
    if !footer.is_empty() {
        lines.extend(
            wrap_lines(&[Line::from(footer)], width)
                .into_iter()
                .enumerate()
                .map(|(index, row)| (CardPosition::Footer(index), row.line)),
        );
    }
    lines.push((CardPosition::Gap, Line::default()));
    lines
}

/// The viewport plus lookahead materialized around the feed's semantic anchor.
///
/// `top` is the row index the anchor resolves to, so consumers slice
/// `rows[top .. top + viewport]` without rescanning the feed.
#[must_use]
pub fn feed_window(feed: &FeedState, width: u16, height: u16) -> FeedWindow {
    let width = width.saturating_sub(2);
    let searching = !feed.query.text.trim().is_empty();
    let language = crate::i18n::UiStrings::detect().language;
    let mut geometry = feed.geometry.borrow_mut();
    geometry.sync_epoch(width, searching, language);
    geometry.viewport_rows = height;

    let memos = &feed.memos;
    let anchor_card = feed
        .anchor
        .as_ref()
        .and_then(|anchor| geometry.index_of(memos, &anchor.id))
        .unwrap_or(0);
    let anchor_card = anchor_card.min(memos.len().saturating_sub(1));

    // The anchor row is the last row of its card at or below the semantic
    // position — the same rule `top_row` encodes over materialized rows.
    let anchor_rows = memos
        .get(anchor_card)
        .map(|memo| geometry.card_rows(memo, language));
    let anchor_row = feed
        .anchor
        .as_ref()
        .zip(anchor_rows.as_ref())
        .map_or(0, |(anchor, rows)| {
            rows.iter()
                .rposition(|(position, _)| *position <= anchor.position)
                .unwrap_or(0)
        });

    // Walk back far enough to cover the lookahead — whole cards, so the window
    // edge lands on a card boundary.
    let mut lo = anchor_card;
    let mut room = LOOKAHEAD_ROWS;
    while lo > 0 && room > 0 {
        lo -= 1;
        let Some(memo) = memos.get(lo) else {
            break;
        };
        let rows = geometry.card_rows(memo, language);
        room = room.saturating_sub(rows.len());
    }
    // Walk forward until the viewport below the anchor row plus the lookahead
    // are covered.
    let mut hi = anchor_card;
    let mut have = anchor_rows.map_or(0, |rows| rows.len().saturating_sub(anchor_row));
    let want = usize::from(height) + LOOKAHEAD_ROWS;
    while have < want {
        let Some(memo) = memos.get(hi + 1) else {
            break;
        };
        hi += 1;
        have += geometry.card_rows(memo, language).len();
    }
    let hi = (hi + 1).min(memos.len());

    let mut rows = Vec::with_capacity(have + LOOKAHEAD_ROWS);
    let mut top = anchor_row;
    for (index, memo) in memos.iter().enumerate().take(hi).skip(lo) {
        if index < anchor_card {
            top += geometry.card_rows(memo, language).len();
            // rows pushed below — count first so `top` stays the anchor row.
        }
        for (position, line) in geometry.card_rows(memo, language).iter() {
            rows.push(FeedLine {
                id: memo.id.clone(),
                position: *position,
                line: line.clone(),
            });
        }
    }
    // The last page owes a full viewport: when fewer than `height` rows sit
    // at or below the anchor the top pulls back — the same `top ≤ len -
    // height` bound the reader applies (09-I6-02/03). Otherwise the anchor
    // stays literal: it is the row the scroll deliberately parked on, even a
    // `Gap` spacer on a short feed — `top_row` resolves identically.
    if rows.len() > usize::from(height) {
        let last_page = rows.len() - usize::from(height);
        if top > last_page {
            top = last_page;
            // The pulled-back page opens on content, never on a `Gap`
            // spacer — walk up to the row the page's content starts on.
            while top > 0
                && rows
                    .get(top)
                    .is_some_and(|row| row.position == CardPosition::Gap)
            {
                top -= 1;
            }
        }
    }
    FeedWindow {
        rows,
        top,
        cards: lo..hi,
        anchor_card,
    }
}

/// The recorded viewport window — the test and hydration surface. Costs are
/// bounded by the viewport plus lookahead, never by the loaded feed.
#[must_use]
pub fn feed_lines(feed: &FeedState, width: u16) -> Vec<FeedLine> {
    let height = feed
        .geometry
        .borrow()
        .viewport_rows
        .max(DEFAULT_VIEWPORT_ROWS);
    feed_window(feed, width, height).rows
}

#[must_use]
pub fn top_row(rows: &[FeedLine], anchor: Option<&MemoAnchor>) -> usize {
    anchor
        .and_then(|anchor| {
            rows.iter()
                .rposition(|row| row.id == anchor.id && row.position <= anchor.position)
                .or_else(|| rows.iter().position(|row| row.id == anchor.id))
        })
        .unwrap_or(0)
}

/// Steps `delta` rendered rows from `(card, row)` over card boundaries —
/// the row-granular walk the old whole-feed materialization provided, bounded
/// by the walked distance. Returns `(card index, row index in card)`.
fn walk_rows(
    geometry: &mut Geometry,
    memos: &[MemoCard],
    language: UiLanguage,
    card: usize,
    row: usize,
    delta: i32,
) -> (usize, usize) {
    let mut card = card.min(memos.len().saturating_sub(1));
    let mut row = row;
    let mut remaining = delta.unsigned_abs() as usize;
    if delta >= 0 {
        while remaining > 0 && card < memos.len() {
            let Some(memo) = memos.get(card) else {
                break;
            };
            let height = geometry.card_rows(memo, language).len();
            if remaining < height.saturating_sub(row) {
                return (card, row + remaining);
            }
            remaining = remaining.saturating_sub(height.saturating_sub(row));
            card += 1;
            row = 0;
        }
        if card < memos.len() {
            // `delta == 0` never leaves the row it started on, and a stride
            // that exhausts its displacement exactly on a card edge lands on
            // the next card's first row — not the feed's tail (09-I6-02).
            return (card, row);
        }
        // A stride that walks off the feed's tail clamps to its last row —
        // the same row the `i32::MAX` walk measures backward from.
        let last = memos.len().saturating_sub(1);
        let Some(last_memo) = memos.get(last) else {
            return (0, 0);
        };
        let last_row = geometry
            .card_rows(last_memo, language)
            .len()
            .saturating_sub(1);
        (last, last_row)
    } else {
        while remaining > 0 {
            if row >= remaining {
                return (card, row - remaining);
            }
            if card == 0 {
                return (0, 0);
            }
            remaining -= row + 1; // this card's row plus the boundary crossing
            card -= 1;
            let Some(memo) = memos.get(card) else {
                break;
            };
            row = geometry.card_rows(memo, language).len().saturating_sub(1);
        }
        (card, row)
    }
}

/// Semantic anchor of row `row` of card `card`.
fn row_anchor(
    geometry: &mut Geometry,
    memos: &[MemoCard],
    language: UiLanguage,
    card: usize,
    row: usize,
) -> Option<MemoAnchor> {
    let memo = memos.get(card)?;
    let rows = geometry.card_rows(memo, language);
    rows.get(row).map(|(position, _)| MemoAnchor {
        id: memo.id.clone(),
        position: *position,
    })
}

/// The anchor's `(card index, row index in card)`; `None` anchor lands on the
/// first card.
fn anchor_position(
    geometry: &mut Geometry,
    memos: &[MemoCard],
    language: UiLanguage,
    anchor: Option<&MemoAnchor>,
) -> (usize, usize) {
    let card = anchor
        .and_then(|anchor| geometry.index_of(memos, &anchor.id))
        .unwrap_or(0)
        .min(memos.len().saturating_sub(1));
    let row = anchor
        .and_then(|anchor| {
            geometry
                .card_rows(memos.get(card)?, language)
                .iter()
                .rposition(|(position, _)| *position <= anchor.position)
        })
        .unwrap_or(0);
    (card, row)
}

/// Scrolls the semantic anchor by `delta` rendered rows — the same unit the
/// old whole-feed `feed_lines` walk used, so page-up/down and wheel deltas
/// keep their feel.
pub fn scroll_feed(feed: &mut FeedState, width: u16, height: u16, delta: i32) {
    let card_width = width.saturating_sub(2);
    let searching = !feed.query.text.trim().is_empty();
    let language = crate::i18n::UiStrings::detect().language;
    let target = {
        let mut geometry = feed.geometry.borrow_mut();
        geometry.sync_epoch(card_width, searching, language);
        geometry.viewport_rows = height;
        let memos = &feed.memos;
        if memos.is_empty() {
            None
        } else {
            let (card, row) = match delta {
                i32::MIN => (0, 0),
                i32::MAX => {
                    // First row of the last viewport: `height - 1` rows above
                    // the feed's last row.
                    let last = memos.len() - 1;
                    memos.last().map_or((0, 0), |last_memo| {
                        let last_row = geometry
                            .card_rows(last_memo, language)
                            .len()
                            .saturating_sub(1);
                        walk_rows(
                            &mut geometry,
                            memos,
                            language,
                            last,
                            last_row,
                            -(i32::from(height.saturating_sub(1))),
                        )
                    })
                }
                _ => {
                    let (card, row) =
                        anchor_position(&mut geometry, memos, language, feed.anchor.as_ref());
                    walk_rows(&mut geometry, memos, language, card, row, delta)
                }
            };
            row_anchor(&mut geometry, memos, language, card, row)
        }
    };
    if let Some(anchor) = target {
        feed.anchor = Some(anchor);
    }
    let window = feed_window(feed, width, height);
    select_visible(feed, &window, height);
}

/// Repairs selection after a scroll: unchanged while the selected card stays
/// inside `rows[top .. top + height]`; otherwise the closest visible card in
/// the direction the selection left. A `Gap` row is a spacer — it renders the
/// selection's mark but it is not the card's content, so it cannot count the
/// card as visibly selected (09-I6-03).
fn select_visible(feed: &mut FeedState, window: &FeedWindow, height: u16) {
    let Some(selected) = feed.selected.clone() else {
        return;
    };
    let height = usize::from(height);
    if window
        .rows
        .iter()
        .skip(window.top)
        .take(height)
        .any(|row| row.id == selected && row.position != CardPosition::Gap)
    {
        return;
    }
    let below = match window.rows.iter().position(|row| row.id == selected) {
        Some(index) => index >= window.top,
        None => feed
            .geometry
            .borrow_mut()
            .index_of(&feed.memos, &selected)
            .is_some_and(|index| index >= window.cards.end),
    };
    // The pick obeys the same no-`Gap` rule as the check above: a spacer row
    // renders the mark but holds no card content, so the repaired selection
    // lands on the first (selection left above) or last (left below) card row
    // the band actually shows — never the gap that merely borders it
    // (11-T-03).
    let chosen = if below {
        window
            .rows
            .iter()
            .skip(window.top)
            .take(height)
            .rfind(|row| row.position != CardPosition::Gap)
    } else {
        window
            .rows
            .iter()
            .skip(window.top)
            .take(height)
            .find(|row| row.position != CardPosition::Gap)
    };
    if let Some(row) = chosen {
        feed.selected = Some(row.id.clone());
    }
}

pub fn ensure_selected_visible(feed: &mut FeedState, width: u16, height: u16) {
    let Some(selected) = feed.selected.as_ref() else {
        return;
    };
    let window = feed_window(feed, width, height);
    if !window
        .rows
        .iter()
        .skip(window.top)
        .take(usize::from(height))
        .any(|row| &row.id == selected && row.position != CardPosition::Gap)
    {
        feed.anchor = Some(MemoAnchor {
            id: selected.clone(),
            position: CardPosition::Time,
        });
    }
}
