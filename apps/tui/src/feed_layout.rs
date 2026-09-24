//! Feed geometry is shared by drawing, semantic anchors and mouse hit testing.
use lomo_workspace::MemoId;
use ratatui::{
    style::{Color, Style},
    text::{Line, Span},
};

use crate::layout::CARD_BODY_LINES;
use crate::model::{BodyState, CardPosition, FeedState, MemoAnchor, MemoCard};
use crate::text_layout::{plain_lines, wrap_lines};

#[derive(Clone, Debug)]
pub struct FeedLine {
    pub id: MemoId,
    pub position: CardPosition,
    pub line: Line<'static>,
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
    let content = memo.excerpt.as_ref().filter(|_| searching).map_or_else(
        || match &memo.body {
            BodyState::Ready(body) => body.card_lines().to_vec(),
            BodyState::Pending | BodyState::Loading { .. } => plain_lines(&memo.summary),
            BodyState::Failed(error) => plain_lines(error),
        },
        crate::search_view::excerpt_lines,
    );
    let wrapped = wrap_lines(&content, width);
    let start = if searching && memo.excerpt.is_some() {
        wrapped
            .iter()
            .position(|row| {
                row.line
                    .spans
                    .iter()
                    .any(|span| span.style.fg == Some(Color::Cyan))
            })
            .map_or(0, |row| row.saturating_sub(2))
    } else {
        0
    };
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
    if wrapped.len() > CARD_BODY_LINES {
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

#[must_use]
pub fn feed_lines(feed: &FeedState, width: u16) -> Vec<FeedLine> {
    let searching = !feed.query.text.trim().is_empty();
    feed.memos
        .iter()
        .flat_map(|memo| {
            card_rows(memo, width.saturating_sub(2), searching)
                .into_iter()
                .map(|(position, line)| FeedLine {
                    id: memo.id.clone(),
                    position,
                    line,
                })
        })
        .collect()
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

pub fn scroll_feed(feed: &mut FeedState, width: u16, height: u16, delta: i32) {
    let rows = feed_lines(feed, width);
    let old = top_row(&rows, feed.anchor.as_ref());
    let top = old
        .saturating_add_signed(delta as isize)
        .min(rows.len().saturating_sub(usize::from(height)));
    if let Some(row) = rows.get(top) {
        feed.anchor = Some(MemoAnchor {
            id: row.id.clone(),
            position: row.position,
        });
    }
    select_visible(feed, &rows, top, height);
}

pub fn select_visible(feed: &mut FeedState, rows: &[FeedLine], top: usize, height: u16) {
    let mut visible = rows.iter().skip(top).take(usize::from(height));
    if visible
        .clone()
        .any(|row| feed.selected.as_ref() == Some(&row.id))
    {
        return;
    }
    let old = rows
        .iter()
        .position(|row| feed.selected.as_ref() == Some(&row.id));
    let closest = if old.is_some_and(|index| index >= top) {
        visible.next_back()
    } else {
        visible.clone().next()
    };
    if let Some(row) = closest {
        feed.selected = Some(row.id.clone());
    }
}

pub fn ensure_selected_visible(feed: &mut FeedState, width: u16, height: u16) {
    let rows = feed_lines(feed, width);
    let Some(selected) = &feed.selected else {
        return;
    };
    let top = top_row(&rows, feed.anchor.as_ref());
    if !rows
        .iter()
        .skip(top)
        .take(usize::from(height))
        .any(|row| &row.id == selected)
    {
        feed.anchor = Some(MemoAnchor {
            id: selected.clone(),
            position: CardPosition::Time,
        });
    }
}
