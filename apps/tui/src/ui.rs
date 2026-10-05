//! One reading surface; rendering never performs IO.
use crate::event::{Availability, Command};
use crate::feed_layout::feed_window;
use crate::i18n::UiStrings;
use crate::layout::{ReadingLayout, reading_layout};
use crate::model::{AppModel, FeedKind, FeedState, InputMode, LoadStatus, SaveState, View};
use crate::text_layout::{cursor_position, plain_lines, wrap_lines};
use ratatui::{
    Frame,
    layout::Rect,
    style::{Color, Modifier, Style},
    text::{Line, Span},
    widgets::{Block, BorderType, Clear, Paragraph},
};
use ratatui_image::{StatefulImage, protocol::StatefulProtocol};

pub fn draw(frame: &mut Frame, model: &AppModel) {
    let page = crate::reader::page(model);
    draw_with_reader_page(frame, model, page.as_ref());
}

/// Draws one frame reusing an already-computed reader page, so the image
/// surface and the text pass never wrap the same body twice.
pub(crate) fn draw_with_reader_page(
    frame: &mut Frame,
    model: &AppModel,
    page: Option<&crate::reader::ReaderPage>,
) {
    frame.render_widget(Clear, frame.area());
    let s = UiStrings::detect();
    let layout = layout_for(model);
    draw_header(frame, layout.header);
    draw_filters(frame, model, layout.filters, s);
    if matches!(model.input, InputMode::Compose) {
        draw_composer(frame, model, layout.composer, s);
    }
    match &model.view {
        View::Feed(feed) => draw_feed(frame, feed, layout.content, s),
        View::Reader { memo, .. } => draw_reader(frame, memo, page, layout.content, s),
        View::Tasks(list) => draw_rows(
            frame,
            layout.content,
            list.selected,
            &list.items,
            |row| {
                Line::from(vec![
                    Span::raw(format!("{} {}", if row.done { "✓" } else { "□" }, row.text)),
                    Span::styled(
                        format!("  {}", row.date),
                        Style::default().fg(Color::DarkGray),
                    ),
                ])
            },
            s.text(
                "No open todos · write `- [ ]` in a memo",
                "没有待办 · 在记录里写 `- [ ]`",
            ),
        ),
        View::Attachments(list) => draw_rows(
            frame,
            layout.content,
            list.selected,
            &list.items,
            |row| {
                Line::from(vec![
                    Span::raw(row.path.as_str().to_owned()),
                    Span::styled(
                        format!("  {}", row.owners.join(", ")),
                        Style::default().fg(Color::DarkGray),
                    ),
                ])
            },
            s.text("No attachments yet", "还没有附件"),
        ),
        View::Statistics(stats) => crate::stats_draw::draw_stats(frame, layout.content, stats, s),
        View::Settings(settings) => draw_settings(frame, layout.content, settings, s),
        View::Loading { .. } => {
            frame.render_widget(
                Paragraph::new(s.text("Loading…", "加载中…"))
                    .style(Style::default().fg(Color::DarkGray)),
                layout.content,
            );
        }
        View::Failed { diagnostic, .. } => {
            frame.render_widget(
                Paragraph::new(diagnostic.as_str()).style(Style::default().fg(Color::Red)),
                layout.content,
            );
        }
    }
    let hint = input_hint(model, s);
    frame.render_widget(Paragraph::new(status_line(model, &hint)), layout.status);
    crate::overlays::draw(frame, model, s);
}
/// Rows of the filter strip: label and chips, or the bordered search field.
const FILTER_ROWS: u16 = 2;
const SEARCH_ROWS: u16 = 3;

#[must_use]
pub fn layout_for(model: &AppModel) -> ReadingLayout {
    let area = Rect::new(0, 0, model.width, model.height);
    let filter_rows = if matches!(model.input, InputMode::Search { .. }) {
        SEARCH_ROWS
    } else {
        FILTER_ROWS
    };
    let base = reading_layout(area, 0, filter_rows);
    let compose = if matches!(model.input, InputMode::Compose) {
        let width = base.content.width.saturating_sub(4);
        let rows = wrap_lines(&plain_lines(model.draft.text.text()), width).len();
        let cursor_rows = cursor_position(model.draft.text.before_cursor(), width).0 + 1;
        u16::try_from(rows.max(cursor_rows).saturating_add(2).max(4)).unwrap_or(u16::MAX)
    } else {
        0
    };
    reading_layout(area, compose, filter_rows)
}

/// The header carries only the name; key hints live in the status bar alone.
fn draw_header(frame: &mut Frame, area: Rect) {
    frame.render_widget(
        Paragraph::new(Span::styled(
            "Lomo",
            Style::default().add_modifier(Modifier::BOLD),
        )),
        area,
    );
}

fn feed_count(feed: &FeedState, s: &UiStrings) -> String {
    use crate::i18n::UiLanguage;
    let loaded = feed.memos.len();
    let mut label = match (feed.total, s.language) {
        (Some(total), UiLanguage::English) if u64::try_from(loaded) == Ok(total) => {
            format!("{total} total")
        }
        (Some(total), UiLanguage::ChineseSimplified) if u64::try_from(loaded) == Ok(total) => {
            format!("共 {total} 条")
        }
        (Some(total), UiLanguage::English) => format!("{loaded} of {total} loaded"),
        (Some(total), UiLanguage::ChineseSimplified) => format!("已加载 {loaded} / {total} 条"),
        (None, UiLanguage::English) => format!("{loaded} loaded"),
        (None, UiLanguage::ChineseSimplified) => format!("已加载 {loaded} 条"),
    };
    // The load verdict rides the count label: a failed refresh leaves its
    // diagnostic beside the stale rows it could not replace, long after the
    // status toast has moved on (I9).
    match &feed.load {
        LoadStatus::Loading if loaded > 0 => {
            label.push_str(s.text(" · loading…", " · 加载中…"));
        }
        LoadStatus::Failed(diagnostic) => {
            label.push_str(s.text(" · failed: ", " · 失败："));
            label.push_str(diagnostic);
        }
        LoadStatus::Ready | LoadStatus::Stale | LoadStatus::Loading => {}
    }
    label
}

/// The status row: persistent badge marks first, severity-coloured, then the
/// current toast or the mode's key hint. Badges are model state, so a later
/// toast cannot erase them (I9).
fn status_line(model: &AppModel, hint: &str) -> Line<'static> {
    let mut spans: Vec<Span<'static>> = Vec::new();
    for (index, badge) in model.badges.iter().enumerate() {
        if index > 0 {
            spans.push(Span::raw("  "));
        }
        let (glyph, color) = match badge.severity {
            crate::model::Severity::Error => ("✗", Color::Red),
            crate::model::Severity::Warn => ("!", Color::Yellow),
            crate::model::Severity::Info => ("i", Color::Cyan),
        };
        spans.push(Span::styled(
            format!("{glyph} {}", badge.text),
            Style::default().fg(color),
        ));
    }
    if !spans.is_empty() {
        spans.push(Span::styled("  ·  ", Style::default().fg(Color::DarkGray)));
    }
    spans.push(Span::styled(
        model.status.clone().unwrap_or_else(|| hint.to_owned()),
        Style::default().fg(Color::DarkGray),
    ));
    Line::from(spans)
}

fn draw_filters(frame: &mut Frame, model: &AppModel, area: Rect, s: &UiStrings) {
    if let InputMode::Search { text } = &model.input {
        draw_search(frame, model, text, area, s);
        return;
    }
    let first = Rect {
        height: area.height.min(1),
        ..area
    };
    let label = match &model.view {
        View::Feed(feed) => {
            let mut labels = vec![s.screen_title(feed.kind.screen()).to_owned()];
            if feed.query.mode == lomo_application::SearchMode::Fuzzy {
                labels.push(s.text("fuzzy / pinyin", "模糊／拼音").to_owned());
            }
            labels.push(feed_count(feed, s));
            if !model.draft.text.text().trim().is_empty()
                && !matches!(model.input, InputMode::Compose)
            {
                labels.push(s.text("draft kept · n", "草稿已保留 · n").to_owned());
            }
            labels.join("  ·  ")
        }
        View::Reader { .. } => s.text("Reading", "阅读全文").to_owned(),
        view @ (View::Tasks(_)
        | View::Statistics(_)
        | View::Attachments(_)
        | View::Settings(_)
        | View::Loading { .. }
        | View::Failed { .. }) => s.screen_title(view.screen()).to_owned(),
    };
    frame.render_widget(
        Paragraph::new(label).style(Style::default().fg(Color::DarkGray)),
        first,
    );
    for (rect, label, _) in crate::filter_controls::controls(model, area) {
        let lines = wrap_lines(&plain_lines(&label), rect.width);
        frame.render_widget(
            Paragraph::new(lines.into_iter().map(|line| line.line).collect::<Vec<_>>())
                .style(Style::default().fg(Color::Cyan)),
            rect,
        );
    }
}

/// The width the search field's text actually renders at.
///
/// The panel's inner width minus the `/` prefix column and the right margin —
/// vertical cursor moves (`edit_field` Up/Down) wrap by this, never the
/// screen width: the drawn field is the only width that exists (C-17).
#[must_use]
pub fn search_field_width(model: &AppModel) -> u16 {
    let area = layout_for(model).filters;
    if area.height < 3 || area.width < 6 {
        // The bare strip: "/" takes a cell only when a text cell can sit
        // beside it — the field is whatever remains, head-first.
        return area.width.saturating_sub(u16::from(area.width >= 2));
    }
    // The bordered panel's interior: the "/" prompt column and its padding
    // own 4 cells while a field cell fits beside them; a squeezed interior
    // drops to the strip's head-first remainder.
    let inner = area.width.saturating_sub(2);
    if inner >= 5 {
        inner - 4
    } else {
        inner.saturating_sub(2)
    }
}

/// The degraded single-row field the search surface falls back to: the `/`
/// affordance takes a cell only when a text cell can sit beside it, and the
/// query draws head-first in the cells that exist — a zero-cell field is
/// the blind-edit shape this strip exists to close (09-I6-06).
fn draw_search_strip(frame: &mut Frame, area: Rect, text: &crate::input::TextBuffer) {
    if area.is_empty() {
        return;
    }
    let strip = Rect::new(area.x, area.y, area.width, 1);
    let prompt = u16::from(strip.width >= 2);
    if prompt == 1 {
        frame.render_widget(
            Paragraph::new("/").style(Style::default().fg(Color::Cyan)),
            Rect::new(strip.x, strip.y, 1, 1),
        );
    }
    let field = Rect::new(
        strip.x.saturating_add(prompt),
        strip.y,
        strip.width.saturating_sub(prompt),
        1,
    );
    if field.is_empty() {
        return;
    }
    frame.render_widget(Paragraph::new(text.text().to_owned()), field);
    // The strip shows the query head-first; a cursor sitting on a later
    // visual row pins to the field's last cell — never off-frame.
    let (row, col) = cursor_position(text.before_cursor(), field.width);
    let x = if row == 0 {
        field.x.saturating_add(
            u16::try_from(col)
                .unwrap_or(u16::MAX)
                .min(field.width.saturating_sub(1)),
        )
    } else {
        field.right().saturating_sub(1)
    };
    frame.set_cursor_position((x, field.y));
}

/// The search field is a bordered panel: mode on the frame, outcome on its bottom edge.
fn draw_search(
    frame: &mut Frame,
    model: &AppModel,
    text: &crate::input::TextBuffer,
    area: Rect,
    s: &UiStrings,
) {
    if area.height < 3 || area.width < 6 {
        // Below the panel's minimum the keyword keeps a bare `/text` strip —
        // the field owns keystrokes, so it must keep a footprint (09-I6-06).
        draw_search_strip(frame, area, text);
        return;
    }
    let feed = match &model.view {
        View::Feed(feed) => Some(&**feed),
        View::Reader { .. }
        | View::Tasks(_)
        | View::Statistics(_)
        | View::Attachments(_)
        | View::Settings(_)
        | View::Loading { .. }
        | View::Failed { .. } => None,
    };
    let fuzzy = feed.is_some_and(|feed| feed.query.mode == lomo_application::SearchMode::Fuzzy);
    let mode = if fuzzy {
        s.text("Fuzzy / pinyin", "模糊／拼音")
    } else {
        s.text("Fulltext", "全文")
    };
    let outcome = match feed {
        Some(feed) if feed.query.text.is_empty() => {
            s.text("type to search", "输入以搜索").to_owned()
        }
        Some(feed) if matches!(feed.load, LoadStatus::Loading | LoadStatus::Stale) => {
            s.text("searching…", "搜索中…").to_owned()
        }
        Some(feed) => feed_count(feed, s),
        None => String::new(),
    };
    let block = Block::bordered()
        .border_type(BorderType::Rounded)
        .border_style(Style::default().fg(Color::DarkGray))
        .title(Span::styled(
            format!(" {} · {mode} ", s.text("Search", "搜索")),
            Style::default().fg(Color::Cyan),
        ))
        .title_bottom(Span::styled(
            format!(" {outcome} "),
            Style::default().fg(Color::DarkGray),
        ));
    let inner = block.inner(area);
    frame.render_widget(block, area);
    let field = Rect::new(inner.x + 3, inner.y, inner.width.saturating_sub(4), 1);
    if field.is_empty() {
        // The frame fits but not a field cell — the keyword still owns
        // input, so the prompt row degrades to the head-first strip.
        draw_search_strip(
            frame,
            Rect::new(inner.x + 1, inner.y, inner.width.saturating_sub(1), 1),
            text,
        );
        return;
    }
    frame.render_widget(
        Paragraph::new("/").style(Style::default().fg(Color::Cyan)),
        Rect::new(inner.x + 1, inner.y, 1, 1),
    );
    draw_field(
        frame,
        field,
        text,
        Some(s.text("Search memos…", "搜索记录…")),
    );
}

/// Columns reserved at the right of the reader title for the progress percentage.
const PROGRESS_COLUMNS: u16 = 6;

fn draw_reader(
    frame: &mut Frame,
    memo: &crate::model::MemoCard,
    page: Option<&crate::reader::ReaderPage>,
    area: Rect,
    s: &UiStrings,
) {
    let mut title = vec![Span::styled(
        format!("{}  {}", memo.date, memo.time),
        Style::default().fg(Color::DarkGray),
    )];
    if memo.pinned {
        title.push(Span::styled(
            format!("  ◆ {}", s.text("Pinned", "置顶")),
            Style::default().fg(Color::DarkGray),
        ));
    }
    if !memo.tags.is_empty() {
        title.push(Span::styled(
            format!(
                "  {}",
                memo.tags
                    .iter()
                    .map(|tag| format!("#{tag}"))
                    .collect::<Vec<_>>()
                    .join("  ")
            ),
            Style::default().fg(Color::Cyan),
        ));
    }
    frame.render_widget(
        Paragraph::new(Line::from(title)),
        Rect {
            width: area.width.saturating_sub(PROGRESS_COLUMNS),
            height: area.height.min(1),
            ..area
        },
    );
    if let Some(page) = page {
        let visible = page
            .rows
            .iter()
            .skip(page.top.saturating_sub(page.origin))
            .take(usize::from(page.area.height))
            .map(|row| row.line.clone())
            .collect::<Vec<_>>();
        frame.render_widget(Paragraph::new(visible), page.area);
        let progress =
            (page.top + usize::from(page.area.height)).min(page.total) * 100 / page.total.max(1);
        let label = format!("{progress}%");
        let width = u16::try_from(label.len())
            .unwrap_or(area.width)
            .min(area.width);
        frame.render_widget(
            Paragraph::new(label).style(Style::default().fg(Color::DarkGray)),
            Rect::new(
                area.right().saturating_sub(width),
                area.y,
                width,
                area.height.min(1),
            ),
        );
        // Images render inside this frame — `StatefulImage` writes the
        // protocol's prepared cells into the buffer at the reader's
        // placement rects, so scrolling and overlay changes are ordinary
        // cell diffs and a kitty payload transmits exactly once (D-07/C-14).
        for placement in &page.pictures {
            let mut protocol = placement.image.lock_protocol();
            frame.render_stateful_widget(
                StatefulImage::<StatefulProtocol>::default(),
                placement.rect,
                &mut protocol,
            );
        }
    }
}

/// A plain selectable list — only the rows the viewport can show are
/// materialized; the item count never enters the frame cost.
fn draw_rows<T>(
    frame: &mut Frame,
    area: Rect,
    selected: usize,
    items: &[T],
    render: impl Fn(&T) -> Line<'static>,
    empty: &str,
) {
    if items.is_empty() {
        frame.render_widget(
            Paragraph::new(empty).style(Style::default().fg(Color::DarkGray)),
            area,
        );
        return;
    }
    // The drawn top is the shared selection-following rule — a stale index
    // outliving its list clamps into the last page, never skips every row
    // onto a blank panel (09-I6-04).
    let top = crate::layout::selection_top(selected, items.len(), area.height);
    let lines = items
        .iter()
        .enumerate()
        .skip(top)
        .take(usize::from(area.height))
        .map(|(index, row)| {
            let active = index == selected;
            let mut spans = vec![Span::styled(
                if active { "▎ " } else { "  " },
                Style::default().fg(Color::Cyan),
            )];
            spans.extend(render(row).spans);
            let line = Line::from(spans);
            if active {
                line.style(Style::default().fg(Color::Cyan))
            } else {
                line
            }
        })
        .collect::<Vec<_>>();
    frame.render_widget(Paragraph::new(lines), area);
}

/// Single-line text field. The placeholder is dimmed and only shown while the field is empty.
pub fn draw_field(
    frame: &mut Frame,
    area: Rect,
    text: &crate::input::TextBuffer,
    placeholder: Option<&str>,
) {
    if area.width == 0 || area.height == 0 {
        return;
    }
    if text.text().is_empty() {
        if let Some(placeholder) = placeholder {
            frame.render_widget(
                Paragraph::new(placeholder).style(Style::default().fg(Color::DarkGray)),
                area,
            );
        }
        frame.set_cursor_position((area.x, area.y));
        return;
    }
    let (row, col) = cursor_position(text.before_cursor(), area.width);
    let lines = wrap_lines(&plain_lines(text.text()), area.width);
    let line = lines
        .get(row)
        .map_or_else(Line::default, |row| row.line.clone());
    frame.render_widget(Paragraph::new(line), area);
    let col = u16::try_from(col)
        .unwrap_or_else(|_| area.width.saturating_sub(1))
        .min(area.width.saturating_sub(1));
    frame.set_cursor_position((area.x + col, area.y));
}
/// Capture is a bordered panel: save state on the frame, tag candidates on
/// its bottom edge. Below the panel's minimum the draft keeps a bare strip —
/// the field owns keystrokes regardless of chrome, so it must keep a visible
/// footprint (`draw_collapsed` sets the same rule for overlays — 09-I6-06).
fn draw_composer(frame: &mut Frame, model: &AppModel, area: Rect, s: &UiStrings) {
    if area.is_empty() {
        return;
    }
    let inner = if area.height >= 3 && area.width >= 6 {
        // A live submission shows its progress; a marker left behind by a
        // superseded revision describes nothing in flight, so the panel reads
        // as editable again.
        let (title, color) = match &model.draft.save {
            SaveState::Failed { diagnostic } => (
                format!("{} · {diagnostic}", s.text("Save failed", "保存失败")),
                Color::Red,
            ),
            _ if model.draft.submitting_revision().is_some() => {
                (s.text("Saving…", "正在保存…").to_owned(), Color::Cyan)
            }
            SaveState::Editing | SaveState::Submitting { .. } => {
                (s.text("New memo", "新记录").to_owned(), Color::Cyan)
            }
        };
        let mut block = Block::bordered()
            .border_type(BorderType::Rounded)
            .border_style(Style::default().fg(color))
            .title(Span::styled(
                format!(" {title} "),
                Style::default().fg(color),
            ));
        if let Some((_, prefix)) = model.draft.text.tag_prefix() {
            let candidates = model
                .tags()
                .iter()
                .filter(|tag| tag.starts_with(prefix))
                .take(3)
                .map(|tag| format!("#{tag}"))
                .collect::<Vec<_>>();
            if !candidates.is_empty() {
                block = block.title_bottom(Span::styled(
                    format!(" Tab → {} ", candidates.join("  ")),
                    Style::default().fg(Color::Cyan),
                ));
            }
        }
        let frame_inner = block.inner(area);
        frame.render_widget(block, area);
        Rect::new(
            frame_inner.x + 1,
            frame_inner.y,
            frame_inner.width.saturating_sub(2),
            frame_inner.height,
        )
    } else {
        // The degraded strip: the draft text across the whole area — never a
        // blank that keeps accepting typed characters.
        area
    };
    if model.draft.text.text().is_empty() {
        frame.render_widget(
            Paragraph::new(s.text(
                "Write a thought… a #tag completes with Tab",
                "写点什么… 输入 #标签 后按 Tab 补全",
            ))
            .style(Style::default().fg(Color::DarkGray)),
            inner,
        );
    }
    let (cursor_row, cursor_col) = cursor_position(model.draft.text.before_cursor(), inner.width);
    let top = cursor_row.saturating_sub(usize::from(inner.height).saturating_sub(1));
    let lines = wrap_lines(&plain_lines(model.draft.text.text()), inner.width);
    frame.render_widget(
        Paragraph::new(
            lines
                .iter()
                .skip(top)
                .take(usize::from(inner.height))
                .map(|row| row.line.clone())
                .collect::<Vec<_>>(),
        ),
        inner,
    );
    let row = u16::try_from(cursor_row.saturating_sub(top))
        .unwrap_or(0)
        .min(inner.height.saturating_sub(1));
    let col = u16::try_from(cursor_col)
        .unwrap_or(0)
        .min(inner.width.saturating_sub(1));
    if model.draft.submitting_revision().is_none() {
        frame.set_cursor_position((inner.x + col, inner.y + row));
    }
}

fn draw_feed(frame: &mut Frame, feed: &FeedState, area: Rect, s: &UiStrings) {
    // The viewport plus lookahead is already materialized by the shared
    // geometry — drawing never lays out off-window cards.
    let window = feed_window(feed, area.width, area.height);
    let rows = &window.rows;
    let top = window.top;
    // Previous results stay readable but dimmed and unselected while a changed query loads.
    let stale = feed.load == LoadStatus::Loading && feed.selected.is_none();
    if rows
        .iter()
        .skip(top)
        .take(usize::from(area.height))
        .next()
        .is_none()
    {
        let (text, color) = match &feed.load {
            LoadStatus::Loading | LoadStatus::Stale => {
                (s.text("Loading…", "加载中…"), Color::DarkGray)
            }
            LoadStatus::Failed(error) => (error.as_str(), Color::Red),
            LoadStatus::Ready if feed.query.is_filtered() => (
                s.text(
                    "No matching memos · edit the search or clear filters with Esc",
                    "没有匹配记录 · 调整搜索，或按 Esc 清空筛选",
                ),
                Color::DarkGray,
            ),
            LoadStatus::Ready => (
                match feed.kind {
                    FeedKind::Timeline => s.text("Write a thought · n", "记下一个想法 · n"),
                    FeedKind::Review => s.text(
                        "Nothing to review today · memos from this day in earlier years appear here",
                        "今天没有可回顾的内容 · 往年今日的记录会显示在这里",
                    ),
                    FeedKind::Trash => s.text("Trash is empty", "回收站是空的"),
                },
                Color::DarkGray,
            ),
        };
        frame.render_widget(Paragraph::new(text).style(Style::default().fg(color)), area);
        return;
    }
    // Rows write straight into the buffer — the window already produced shared
    // `Line`s, so a frame clones no span and rebuilds no row.
    let buffer = frame.buffer_mut();
    for (offset, row) in rows
        .iter()
        .skip(top)
        .take(usize::from(area.height))
        .enumerate()
    {
        let Ok(dy) = u16::try_from(offset) else {
            break;
        };
        let y = area.y.saturating_add(dy);
        if y >= area.bottom() {
            break;
        }
        // The selected memo keeps its mark on every one of its rows —
        // including the trailing `Gap`, which is still that card's row and
        // may be the only visible part of it. On the gap the mark is the
        // weakened form: same `▎`, dimmed, so a spacer never reads as content
        // (C-07).
        let active = !stale && feed.selected.as_ref() == Some(&row.id);
        let (mark, mark_color) = if !active {
            ("  ", Color::Cyan)
        } else if row.position == crate::model::CardPosition::Gap {
            ("▎ ", Color::DarkGray)
        } else {
            ("▎ ", Color::Cyan)
        };
        buffer.set_stringn(
            area.x,
            y,
            mark,
            usize::from(area.width),
            Style::default().fg(mark_color),
        );
        // `stale` plays the role `line.style` did: the dim patch goes under
        // each span's own style, so already-colored content keeps its paint.
        let line_style = if stale {
            Style::default().fg(Color::DarkGray)
        } else {
            row.line.style
        };
        let mut x = area.x.saturating_add(2);
        let mut remaining = usize::from(area.width).saturating_sub(2);
        for span in &row.line.spans {
            if remaining == 0 {
                break;
            }
            let (end, _) = buffer.set_stringn(
                x,
                y,
                span.content.as_ref(),
                remaining,
                line_style.patch(span.style),
            );
            remaining = remaining.saturating_sub(usize::from(end.saturating_sub(x)));
            x = end;
        }
    }
}

/// The Settings screen: one row per `SettingsField` registry entry, preceded
/// by the real `config.toml` path and followed by detected-environment notes.
/// The cursor follows `settings.selected`; every row names its field key so
/// the projection reads exactly like the TOML file.
fn draw_settings(
    frame: &mut Frame,
    area: Rect,
    settings: &crate::settings::SettingsView,
    s: &UiStrings,
) {
    let mut lines: Vec<Line<'static>> = vec![
        Line::from(Span::styled(
            format!(
                "{}: {}",
                s.text("Config file", "配置文件"),
                settings.file.display()
            ),
            Style::default().fg(Color::DarkGray),
        )),
        Line::default(),
    ];
    for (index, row) in settings.rows.iter().enumerate() {
        let marker = if row.hot {
            s.text("live", "热生效")
        } else {
            s.text("restart", "需重启")
        };
        let active = index == settings.selected;
        let style = if active {
            Style::default().add_modifier(Modifier::REVERSED)
        } else {
            Style::default()
        };
        lines.push(Line::from(vec![
            Span::styled(format!(" {:<12}", row.field.key()), style),
            Span::styled(format!("  {}", row.value), style),
            Span::styled(format!("  {marker}"), Style::default().fg(Color::DarkGray)),
        ]));
    }
    if !settings.info.is_empty() {
        lines.push(Line::default());
        lines.push(Line::from(Span::styled(
            s.text(
                "Environment (detected — not in config.toml)",
                "环境（检测到——不在 config.toml 中）",
            ),
            Style::default()
                .fg(Color::DarkGray)
                .add_modifier(Modifier::BOLD),
        )));
        for info in &settings.info {
            lines.push(Line::from(Span::styled(
                format!("  {info}"),
                Style::default().fg(Color::DarkGray),
            )));
        }
    }
    lines.push(Line::default());
    lines.push(Line::from(Span::styled(
        s.text(
            "Enter edit value · e edit file in external editor",
            "Enter 修改值 · e 在外部编辑器中编辑文件",
        ),
        Style::default().fg(Color::DarkGray),
    )));
    // C-10: values wrap instead of clipping mid-path — nothing the user typed
    // is silently lost from view. The shared grapheme wrapper materializes
    // every visual row inside the column: `Paragraph`'s own `WordWrapper`
    // emits an unbreakable word (a long CJK path segment) past the right
    // edge (09-I6-01). Wrapping inflates rows, so the viewport math counts
    // *visual* lines: `top` is the earliest logical line whose wrapped span
    // still lets the cursor row fit inside the area.
    let wrapped = wrap_lines(&lines, area.width);
    let mut heights = vec![0usize; lines.len()];
    for row in &wrapped {
        if let Some(height) = heights.get_mut(row.anchor.line) {
            *height += 1;
        }
    }
    let cursor_row = 2 + settings.selected.min(settings.rows.len().saturating_sub(1));
    let height = usize::from(area.height);
    let mut top = cursor_row;
    let mut used = 0usize;
    for (index, row_height) in heights.iter().enumerate().take(cursor_row + 1).rev() {
        if used + row_height > height && index < cursor_row {
            break;
        }
        used += row_height;
        top = index;
    }
    let visible: Vec<Line<'static>> = wrapped
        .into_iter()
        .filter(|row| row.anchor.line >= top)
        .map(|row| row.line)
        .collect();
    frame.render_widget(Paragraph::new(visible), area);
}

/// Key hints for the active input, ending with what Esc will do right now.
fn input_hint(model: &AppModel, s: &UiStrings) -> String {
    let base: String = match &model.input {
        InputMode::Compose => match model.draft.save {
            SaveState::Failed { .. } => {
                s.text("Ctrl+S retry  Ctrl+E editor", "Ctrl+S 重试  Ctrl+E 编辑器")
            }
            _ if model.draft.submitting_revision().is_some() => s.text("Saving…", "正在保存…"),
            SaveState::Editing | SaveState::Submitting { .. } => s.text(
                "Enter newline  Tab #tag  Ctrl+S save  Ctrl+E editor",
                "Enter 换行  Tab 补全标签  Ctrl+S 保存  Ctrl+E 编辑器",
            ),
        }
        .to_owned(),
        InputMode::Search { .. } => s
            .text(
                "Enter browse results  Ctrl+F fulltext/fuzzy",
                "Enter 浏览结果  Ctrl+F 全文／模糊",
            )
            .to_owned(),
        InputMode::Picker(_) => s
            .text(
                "↑↓ choose  Enter confirm  type to filter",
                "↑↓ 选择  Enter 确认  输入以筛选",
            )
            .to_owned(),
        InputMode::Date { .. } | InputMode::Confirm(_) => {
            s.text("Enter confirm", "Enter 确认").to_owned()
        }
        InputMode::Setting(_) => s
            .text("Enter apply  type the new value", "Enter 应用  输入新值")
            .to_owned(),
        InputMode::Setup(_) => s
            .text(
                "Tab switch field  Enter create & start",
                "Tab 切换字段  Enter 创建并启动",
            )
            .to_owned(),
        InputMode::Message { .. } | InputMode::Help { .. } => {
            s.text("↑↓ scroll", "↑↓ 滚动").to_owned()
        }
        InputMode::Browse => view_hint(model, s),
    };
    match esc_target(model, s) {
        Some(target) => format!("{base}  Esc {target}"),
        None => base,
    }
}

/// The browsing view's chip list — each view picks the commands it would
/// advertise; `browse_hint` drops every command the capability verdict
/// refuses or hides, so the status bar never advertises a dead key.
fn view_hint(model: &AppModel, s: &UiStrings) -> String {
    match &model.view {
        View::Feed(feed) if feed.kind == FeedKind::Trash => browse_hint(
            model,
            s,
            &[
                (Command::Accept, s.text("read", "阅读")),
                (Command::Delete, s.text("delete forever", "永久删除")),
                (Command::Actions, s.text("actions", "操作")),
                (Command::Palette, s.text("commands", "命令")),
            ],
        ),
        View::Feed(_) => browse_hint(
            model,
            s,
            &[
                (Command::Accept, s.text("read", "阅读")),
                (Command::Compose, s.text("new", "记录")),
                (Command::Search, s.text("search", "搜索")),
                (Command::Actions, s.text("actions", "操作")),
                (Command::Palette, s.text("commands", "命令")),
            ],
        ),
        View::Reader { memo, .. } => browse_hint(
            model,
            s,
            &[
                (Command::ExternalEdit, s.text("edit", "编辑")),
                (
                    Command::Pin,
                    if memo.pinned {
                        s.text("unpin", "取消置顶")
                    } else {
                        s.text("pin", "置顶")
                    },
                ),
                (
                    Command::Delete,
                    if memo.trashed {
                        s.text("delete forever", "永久删除")
                    } else {
                        s.text("delete", "删除")
                    },
                ),
                (Command::Actions, s.text("actions", "操作")),
                (Command::Palette, s.text("commands", "命令")),
            ],
        ),
        View::Tasks(_) => browse_hint(
            model,
            s,
            &[
                (Command::Accept, s.text("toggle", "切换完成")),
                (Command::Actions, s.text("actions", "操作")),
                (Command::Palette, s.text("commands", "命令")),
            ],
        ),
        View::Attachments(_) => browse_hint(
            model,
            s,
            &[
                (Command::Accept, s.text("open", "打开")),
                (Command::Actions, s.text("actions", "操作")),
                (Command::Palette, s.text("commands", "命令")),
            ],
        ),
        View::Statistics(_) | View::Settings(_) | View::Loading { .. } => browse_hint(
            model,
            s,
            &[
                (Command::Palette, s.text("commands", "命令")),
                (Command::Help, s.text("help", "帮助")),
            ],
        ),
        View::Failed { .. } => browse_hint(
            model,
            s,
            &[
                (Command::Refresh, s.text("retry", "重试")),
                (Command::Palette, s.text("commands", "命令")),
            ],
        ),
    }
}

/// The hint bar's command chips — the same projection the dispatcher and the
/// menu consult (I2): only `Ready` commands earn a `{key} {verb}` chip, so a
/// refused action is never advertised and the key named always comes from
/// `KEY_BINDINGS`, never from a literal that could drift.
fn browse_hint(model: &AppModel, s: &UiStrings, commands: &[(Command, &str)]) -> String {
    let chips: Vec<String> = commands
        .iter()
        .filter(|(command, _)| command.availability(model) == Availability::Ready)
        .filter_map(|(command, verb)| {
            command
                .browse_key_label()
                .map(|key| format!("{key} {verb}"))
        })
        .collect();
    if chips.is_empty() {
        s.text("? help", "? 帮助").to_owned()
    } else {
        chips.join("  ")
    }
}

/// What one Esc does from here; `None` when there is nothing to leave.
fn esc_target(model: &AppModel, s: &UiStrings) -> Option<String> {
    let target = match &model.input {
        InputMode::Compose if model.draft.submitting_revision().is_some() => {
            s.text("back to reading", "返回阅读")
        }
        InputMode::Compose => s.text("keep draft", "保留草稿"),
        InputMode::Search { .. } => s.text("collapse, keep keyword", "收起并保留关键词"),
        InputMode::Date { .. } | InputMode::Confirm(_) => s.text("cancel", "取消"),
        InputMode::Picker(_) | InputMode::Message { .. } | InputMode::Help { .. } => {
            s.text("close", "关闭")
        }
        InputMode::Setting(_) => s.text("cancel edit", "取消修改"),
        // Esc on the wizard abandons setup entirely — nothing was persisted.
        InputMode::Setup(_) => s.text("quit without creating", "不创建直接退出"),
        InputMode::Browse => {
            if matches!(&model.view, View::Feed(feed) if feed.query.is_filtered()) {
                s.text("clear filters", "清空筛选")
            } else {
                let previous = model.history.last()?;
                let title = match previous {
                    View::Reader { .. } => s.text("reading", "阅读全文"),
                    View::Feed(_)
                    | View::Tasks(_)
                    | View::Statistics(_)
                    | View::Attachments(_)
                    | View::Settings(_)
                    | View::Loading { .. }
                    | View::Failed { .. } => s.screen_title(previous.screen()),
                };
                return Some(format!("{} {title}", s.text("back to", "返回")));
            }
        }
    };
    Some(target.to_owned())
}
