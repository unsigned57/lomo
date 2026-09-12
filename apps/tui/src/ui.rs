//! One reading surface; rendering never performs IO.
use crate::feed_layout::{feed_lines, top_row};
use crate::i18n::UiStrings;
use crate::layout::{ReadingLayout, reading_layout};
use crate::model::{AppModel, InputMode, LoadStatus, View};
use crate::text_layout::{cursor_position, plain_lines, wrap_lines};
use ratatui::{
    Frame,
    layout::Rect,
    style::{Color, Modifier, Style},
    text::{Line, Span},
    widgets::{Clear, Paragraph},
};

pub fn draw(frame: &mut Frame, model: &AppModel) {
    frame.render_widget(Clear, frame.area());
    let s = UiStrings::detect();
    let layout = layout_for(model);
    draw_header(frame, layout.header, &s);
    draw_filters(frame, model, layout.filters, &s);
    if matches!(model.input, InputMode::Compose) {
        draw_composer(frame, model, layout.composer, &s);
    }
    match &model.view {
        View::Feed(feed) => draw_feed(frame, feed, layout.content, &s),
        View::Reader { memo, anchor } => {
            draw_reader(frame, model, memo, *anchor, layout.content, &s);
        }
        View::Tasks(list) => draw_rows(
            frame,
            layout.content,
            list.selected,
            list.items
                .iter()
                .map(|row| {
                    format!(
                        "{} {}  {}",
                        if row.done { "✓" } else { "□" },
                        row.text,
                        row.date
                    )
                })
                .collect(),
        ),
        View::Attachments(list) => draw_rows(
            frame,
            layout.content,
            list.selected,
            list.items
                .iter()
                .map(|row| row.path.as_str().to_owned())
                .collect(),
        ),
        View::Statistics(stats) => crate::stats_draw::draw_stats(frame, layout.content, stats, &s),
        View::Settings(lines) => {
            frame.render_widget(Paragraph::new(lines.join("\n")), layout.content);
        }
        View::Loading(_) => {
            frame.render_widget(
                Paragraph::new(s.text("Loading…", "加载中…")),
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
    let hint = input_hint(model, &s);
    frame.render_widget(
        Paragraph::new(model.status.as_deref().map_or(hint, |status| status))
            .style(Style::default().fg(Color::DarkGray)),
        layout.status,
    );
    crate::overlays::draw(frame, model, &s);
}
#[must_use]
pub fn layout_for(model: &AppModel) -> ReadingLayout {
    let area = Rect::new(0, 0, model.width, model.height);
    let base = reading_layout(area, 0);
    let compose = if matches!(model.input, InputMode::Compose) {
        let width = base.content.width.saturating_sub(4);
        let rows = wrap_lines(&plain_lines(model.draft.text.text()), width).len();
        let cursor_rows = cursor_position(model.draft.text.before_cursor(), width).0 + 1;
        u16::try_from(rows.max(cursor_rows).saturating_add(2).max(4)).unwrap_or(u16::MAX)
    } else {
        0
    };
    reading_layout(area, compose)
}

fn draw_header(frame: &mut Frame, area: Rect, s: &UiStrings) {
    frame.render_widget(
        Paragraph::new(Span::styled(
            "Lomo",
            Style::default().add_modifier(Modifier::BOLD),
        )),
        area,
    );
    for (rect, label, _) in crate::overlays::header_controls(area, s) {
        frame.render_widget(
            Paragraph::new(label).style(Style::default().fg(Color::DarkGray)),
            rect,
        );
    }
}
fn draw_filters(frame: &mut Frame, model: &AppModel, area: Rect, s: &UiStrings) {
    if let InputMode::Search { text, .. } = &model.input {
        draw_field(frame, area, text);
        let mode = if matches!(&model.view, View::Feed(feed) if feed.query.mode == lomo_application::SearchMode::Fuzzy)
        {
            s.text("Fuzzy / pinyin", "模糊／拼音")
        } else {
            s.text("Fulltext", "全文")
        };
        let hint = format!(
            "{mode} · {}",
            s.text(
                "Enter browse · Esc cancel · Ctrl+F mode",
                "Enter 浏览结果 · Esc 撤销 · Ctrl+F 切换"
            )
        );
        frame.render_widget(
            Paragraph::new(hint).style(Style::default().fg(Color::DarkGray)),
            Rect::new(
                area.x,
                area.y + 1,
                area.width,
                area.height.saturating_sub(1).min(1),
            ),
        );
        return;
    }
    let label = match &model.view {
        View::Feed(feed) => {
            let mut labels = vec![s.screen_title(feed.kind.screen()).to_owned()];
            if feed.query.mode == lomo_application::SearchMode::Fuzzy {
                labels.push(s.text("fuzzy / pinyin", "模糊／拼音").to_owned());
            }
            let count = feed.total.map_or_else(
                || format!("{} {}", feed.memos.len(), s.text("loaded", "条已加载")),
                |total| {
                    format!(
                        "{} {} / {total} {}",
                        feed.memos.len(),
                        s.text("loaded", "已加载"),
                        s.text("total", "总数")
                    )
                },
            );
            labels.push(count);
            labels.join("  ·  ")
        }
        View::Reader { .. } => s
            .text("Reading · Esc back", "阅读全文 · Esc 返回")
            .to_owned(),
        view @ (View::Tasks(_)
        | View::Statistics(_)
        | View::Attachments(_)
        | View::Settings(_)
        | View::Loading(_)
        | View::Failed { .. }) => format!("{}  ·  Esc", s.screen_title(view.screen())),
    };
    frame.render_widget(
        Paragraph::new(label).style(Style::default().fg(Color::DarkGray)),
        Rect {
            height: area.height.min(1),
            ..area
        },
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

fn draw_reader(
    frame: &mut Frame,
    model: &AppModel,
    memo: &crate::model::MemoCard,
    _anchor: crate::model::TextAnchor,
    area: Rect,
    _s: &UiStrings,
) {
    let title = format!("{}  {}", memo.date, memo.time);
    frame.render_widget(
        Paragraph::new(title).style(Style::default().fg(Color::DarkGray)),
        Rect {
            height: area.height.min(1),
            ..area
        },
    );
    if let Some(page) = crate::reader::page(model) {
        let visible = page
            .rows
            .iter()
            .skip(page.top)
            .take(usize::from(page.area.height))
            .map(|row| row.line.clone())
            .collect::<Vec<_>>();
        frame.render_widget(Paragraph::new(visible), page.area);
        let progress = (page.top + usize::from(page.area.height)).min(page.rows.len()) * 100
            / page.rows.len().max(1);
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
    }
}

fn draw_rows(frame: &mut Frame, area: Rect, selected: usize, rows: Vec<String>) {
    let top = selected.saturating_sub(usize::from(area.height).saturating_sub(1));
    let lines = rows
        .into_iter()
        .enumerate()
        .skip(top)
        .take(usize::from(area.height))
        .map(|(index, row)| {
            Line::styled(
                format!("{} {row}", if index == selected { "▎" } else { " " }),
                Style::default().fg(if index == selected {
                    Color::Cyan
                } else {
                    Color::Reset
                }),
            )
        })
        .collect::<Vec<_>>();
    frame.render_widget(Paragraph::new(lines), area);
}
pub fn draw_field(frame: &mut Frame, area: Rect, text: &crate::input::TextBuffer) {
    if area.width == 0 || area.height == 0 {
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
fn draw_composer(frame: &mut Frame, model: &AppModel, area: Rect, s: &UiStrings) {
    if area.height < 3 || area.width < 4 {
        return;
    }
    let title = match model.draft.save {
        crate::model::SaveState::Editing => s.text(
            "New memo   Ctrl+S save · Ctrl+E editor · Esc keep draft",
            "新记录   Ctrl+S 保存 · Ctrl+E 编辑器 · Esc 保留草稿",
        ),
        crate::model::SaveState::Submitting { .. } => s.text(
            "Saving…   Esc return to reading",
            "正在保存…   Esc 返回阅读",
        ),
        crate::model::SaveState::Failed { .. } => s.text(
            "Save failed   Ctrl+S retry · Esc keep draft",
            "保存失败   Ctrl+S 重试 · Esc 保留草稿",
        ),
    };
    frame.render_widget(
        Paragraph::new(title).style(Style::default().fg(Color::Cyan)),
        Rect { height: 1, ..area },
    );
    let inner = Rect::new(
        area.x + 2,
        area.y + 1,
        area.width.saturating_sub(4),
        area.height.saturating_sub(2),
    );
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
    if !matches!(model.draft.save, crate::model::SaveState::Submitting { .. }) {
        frame.set_cursor_position((inner.x + col, inner.y + row));
    }
    if let Some((_, prefix)) = model.draft.text.tag_prefix() {
        let candidates = model
            .tags
            .iter()
            .filter(|tag| tag.starts_with(prefix))
            .take(3)
            .map(|tag| format!("#{tag}"))
            .collect::<Vec<_>>();
        if !candidates.is_empty() {
            frame.render_widget(
                Paragraph::new(format!("Tab  {}", candidates.join("  ")))
                    .style(Style::default().fg(Color::Cyan)),
                Rect::new(area.x, area.bottom().saturating_sub(1), area.width, 1),
            );
        }
    }
}

fn draw_feed(frame: &mut Frame, feed: &crate::model::FeedState, area: Rect, s: &UiStrings) {
    let rows = feed_lines(feed, area.width);
    let top = top_row(&rows, feed.anchor.as_ref());
    let lines = rows
        .iter()
        .skip(top)
        .take(usize::from(area.height))
        .map(|row| {
            let active = feed.selected.as_ref() == Some(&row.id);
            let mut spans = vec![Span::styled(
                if active { "▎ " } else { "  " },
                Style::default().fg(Color::Cyan),
            )];
            spans.extend(row.line.spans.clone());
            Line::from(spans)
        })
        .collect::<Vec<_>>();
    if lines.is_empty() {
        let text = match &feed.load {
            LoadStatus::Loading | LoadStatus::Stale => s.text("Loading…", "加载中…"),
            LoadStatus::Failed(error) => error,
            LoadStatus::Ready if !feed.query.is_filtered() => {
                s.text("Write a thought · n", "记下一个想法 · n")
            }
            LoadStatus::Ready => s.text(
                "No matching memos · edit search or clear filters",
                "没有匹配记录 · 调整搜索或清空筛选",
            ),
        };
        frame.render_widget(Paragraph::new(text), area);
    } else {
        frame.render_widget(Paragraph::new(lines), area);
    }
}

const fn input_hint<'a>(model: &AppModel, s: &'a UiStrings) -> &'a str {
    match &model.input {
        InputMode::Compose => s.text(
            "Enter newline  Ctrl+S save  Ctrl+E editor  Esc keep draft",
            "Enter 换行  Ctrl+S 保存  Ctrl+E 编辑器  Esc 保留草稿",
        ),
        InputMode::Search { .. } => s.text(
            "Enter browse results  Esc undo search  Ctrl+F mode",
            "Enter 浏览结果  Esc 撤销搜索  Ctrl+F 切换模式",
        ),
        InputMode::Picker(_) => s.text(
            "↑↓ choose  Enter confirm  Esc back",
            "↑↓ 选择  Enter 确认  Esc 返回",
        ),
        InputMode::Date { .. } | InputMode::Confirm(_) => {
            s.text("Enter confirm  Esc cancel", "Enter 确认  Esc 取消")
        }
        InputMode::Message { .. } | InputMode::Help { .. } => {
            s.text("↑↓ scroll  Esc back", "↑↓ 滚动  Esc 返回")
        }
        InputMode::Browse if matches!(model.view, View::Reader { .. }) => s.text(
            "↑↓ scroll  Esc back  e edit  a attachments  . actions",
            "↑↓ 滚动  Esc 返回  e 编辑  a 附件  . 操作",
        ),
        InputMode::Browse => s.text(
            "↑↓ select  Enter read  n capture  / search  ? help",
            "↑↓ 选择  Enter 阅读  n 记录  / 搜索  ? 帮助",
        ),
    }
}
