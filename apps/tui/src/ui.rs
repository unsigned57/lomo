//! One reading surface; rendering never performs IO.
use crate::feed_layout::{feed_lines, top_row};
use crate::i18n::UiStrings;
use crate::layout::{ReadingLayout, reading_layout};
use crate::model::{
    AppModel, CardPosition, FeedKind, FeedState, InputMode, LoadStatus, SaveState, View,
};
use crate::text_layout::{cursor_position, plain_lines, wrap_lines};
use ratatui::{
    Frame,
    layout::Rect,
    style::{Color, Modifier, Style},
    text::{Line, Span},
    widgets::{Block, BorderType, Clear, Paragraph},
};

pub fn draw(frame: &mut Frame, model: &AppModel) {
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
        View::Reader { memo, .. } => draw_reader(frame, model, memo, layout.content, s),
        View::Tasks(list) => draw_rows(
            frame,
            layout.content,
            list.selected,
            list.items
                .iter()
                .map(|row| {
                    Line::from(vec![
                        Span::raw(format!("{} {}", if row.done { "✓" } else { "□" }, row.text)),
                        Span::styled(
                            format!("  {}", row.date),
                            Style::default().fg(Color::DarkGray),
                        ),
                    ])
                })
                .collect(),
            s.text(
                "No open todos · write `- [ ]` in a memo",
                "没有待办 · 在记录里写 `- [ ]`",
            ),
        ),
        View::Attachments(list) => draw_rows(
            frame,
            layout.content,
            list.selected,
            list.items
                .iter()
                .map(|row| {
                    Line::from(vec![
                        Span::raw(row.path.as_str().to_owned()),
                        Span::styled(
                            format!("  {}", row.owners.join(", ")),
                            Style::default().fg(Color::DarkGray),
                        ),
                    ])
                })
                .collect(),
            s.text("No attachments yet", "还没有附件"),
        ),
        View::Statistics(stats) => crate::stats_draw::draw_stats(frame, layout.content, stats, s),
        View::Settings(lines) => {
            frame.render_widget(Paragraph::new(lines.join("\n")), layout.content);
        }
        View::Loading(_) => {
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
    frame.render_widget(
        Paragraph::new(
            model
                .status
                .as_deref()
                .map_or(hint.as_str(), |status| status),
        )
        .style(Style::default().fg(Color::DarkGray)),
        layout.status,
    );
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
    if feed.load == LoadStatus::Loading && loaded > 0 {
        label.push_str(s.text(" · loading…", " · 加载中…"));
    }
    label
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
        View::Reader { .. } => s
            .text("Reading  ·  Esc back", "阅读全文  ·  Esc 返回")
            .to_owned(),
        view @ (View::Tasks(_)
        | View::Statistics(_)
        | View::Attachments(_)
        | View::Settings(_)
        | View::Loading(_)
        | View::Failed { .. }) => format!(
            "{}  ·  {}",
            s.screen_title(view.screen()),
            s.text("Esc back", "Esc 返回")
        ),
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

/// The search field is a bordered panel: mode on the frame, outcome on its bottom edge.
fn draw_search(
    frame: &mut Frame,
    model: &AppModel,
    text: &crate::input::TextBuffer,
    area: Rect,
    s: &UiStrings,
) {
    if area.height < 3 || area.width < 6 {
        return;
    }
    let feed = match &model.view {
        View::Feed(feed) => Some(feed),
        View::Reader { .. }
        | View::Tasks(_)
        | View::Statistics(_)
        | View::Attachments(_)
        | View::Settings(_)
        | View::Loading(_)
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
    frame.render_widget(
        Paragraph::new("/").style(Style::default().fg(Color::Cyan)),
        Rect::new(inner.x + 1, inner.y, 1, 1),
    );
    draw_field(
        frame,
        Rect::new(inner.x + 3, inner.y, inner.width.saturating_sub(4), 1),
        text,
        Some(s.text("Search memos…", "搜索记录…")),
    );
}

/// Columns reserved at the right of the reader title for the progress percentage.
const PROGRESS_COLUMNS: u16 = 6;

fn draw_reader(
    frame: &mut Frame,
    model: &AppModel,
    memo: &crate::model::MemoCard,
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

fn draw_rows(
    frame: &mut Frame,
    area: Rect,
    selected: usize,
    rows: Vec<Line<'static>>,
    empty: &str,
) {
    if rows.is_empty() {
        frame.render_widget(
            Paragraph::new(empty).style(Style::default().fg(Color::DarkGray)),
            area,
        );
        return;
    }
    let top = selected.saturating_sub(usize::from(area.height).saturating_sub(1));
    let lines = rows
        .into_iter()
        .enumerate()
        .skip(top)
        .take(usize::from(area.height))
        .map(|(index, row)| {
            let active = index == selected;
            let mut spans = vec![Span::styled(
                if active { "▎ " } else { "  " },
                Style::default().fg(Color::Cyan),
            )];
            spans.extend(row.spans);
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
/// Capture is a bordered panel: save state on the frame, tag candidates on its bottom edge.
fn draw_composer(frame: &mut Frame, model: &AppModel, area: Rect, s: &UiStrings) {
    if area.height < 3 || area.width < 6 {
        return;
    }
    let (title, color) = match &model.draft.save {
        SaveState::Editing => (s.text("New memo", "新记录").to_owned(), Color::Cyan),
        SaveState::Submitting { .. } => (s.text("Saving…", "正在保存…").to_owned(), Color::Cyan),
        SaveState::Failed { diagnostic } => (
            format!("{} · {diagnostic}", s.text("Save failed", "保存失败")),
            Color::Red,
        ),
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
            .tags
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
    let inner = Rect::new(
        frame_inner.x + 1,
        frame_inner.y,
        frame_inner.width.saturating_sub(2),
        frame_inner.height,
    );
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
    if !matches!(model.draft.save, SaveState::Submitting { .. }) {
        frame.set_cursor_position((inner.x + col, inner.y + row));
    }
}

fn draw_feed(frame: &mut Frame, feed: &FeedState, area: Rect, s: &UiStrings) {
    let rows = feed_lines(feed, area.width);
    let top = top_row(&rows, feed.anchor.as_ref());
    // Previous results stay readable but dimmed and unselected while a changed query loads.
    let stale = feed.load == LoadStatus::Loading && feed.selected.is_none();
    let lines = rows
        .iter()
        .skip(top)
        .take(usize::from(area.height))
        .map(|row| {
            let active = !stale
                && row.position != CardPosition::Gap
                && feed.selected.as_ref() == Some(&row.id);
            let mut spans = vec![Span::styled(
                if active { "▎ " } else { "  " },
                Style::default().fg(Color::Cyan),
            )];
            spans.extend(row.line.spans.clone());
            let line = Line::from(spans);
            if stale {
                line.style(Style::default().fg(Color::DarkGray))
            } else {
                line
            }
        })
        .collect::<Vec<_>>();
    if lines.is_empty() {
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
    } else {
        frame.render_widget(Paragraph::new(lines), area);
    }
}

/// Key hints for the active input, ending with what Esc will do right now.
fn input_hint(model: &AppModel, s: &UiStrings) -> String {
    let base = match &model.input {
        InputMode::Compose => match model.draft.save {
            SaveState::Editing => s.text(
                "Enter newline  Tab #tag  Ctrl+S save  Ctrl+E editor",
                "Enter 换行  Tab 补全标签  Ctrl+S 保存  Ctrl+E 编辑器",
            ),
            SaveState::Submitting { .. } => s.text("Saving…", "正在保存…"),
            SaveState::Failed { .. } => {
                s.text("Ctrl+S retry  Ctrl+E editor", "Ctrl+S 重试  Ctrl+E 编辑器")
            }
        },
        InputMode::Search { .. } => s.text(
            "Enter browse results  Ctrl+F fulltext/fuzzy",
            "Enter 浏览结果  Ctrl+F 全文／模糊",
        ),
        InputMode::Picker(_) => s.text(
            "↑↓ choose  Enter confirm  type to filter",
            "↑↓ 选择  Enter 确认  输入以筛选",
        ),
        InputMode::Date { .. } | InputMode::Confirm(_) => s.text("Enter confirm", "Enter 确认"),
        InputMode::Message { .. } | InputMode::Help { .. } => s.text("↑↓ scroll", "↑↓ 滚动"),
        InputMode::Browse => match &model.view {
            View::Feed(feed) if feed.kind == FeedKind::Trash => s.text(
                "Enter read  d delete forever  . actions  : commands",
                "Enter 阅读  d 永久删除  . 操作  : 命令",
            ),
            View::Feed(_) => s.text(
                "Enter read  n new  / search  . actions  : commands",
                "Enter 阅读  n 记录  / 搜索  . 操作  : 命令",
            ),
            View::Reader { .. } => s.text(
                "e edit  m pin  . actions  : commands",
                "e 编辑  m 置顶  . 操作  : 命令",
            ),
            View::Tasks(_) => s.text(
                "Enter toggle  . actions  : commands",
                "Enter 切换完成  . 操作  : 命令",
            ),
            View::Attachments(_) => s.text(
                "Enter open  . actions  : commands",
                "Enter 打开  . 操作  : 命令",
            ),
            View::Statistics(_) | View::Settings(_) | View::Loading(_) => {
                s.text(": commands  ? help", ": 命令  ? 帮助")
            }
            View::Failed { .. } => s.text("F5 retry  : commands", "F5 重试  : 命令"),
        },
    };
    esc_target(model, s).map_or_else(|| base.to_owned(), |target| format!("{base}  Esc {target}"))
}

/// What one Esc does from here; `None` when there is nothing to leave.
fn esc_target(model: &AppModel, s: &UiStrings) -> Option<String> {
    let target = match &model.input {
        InputMode::Compose if matches!(model.draft.save, SaveState::Submitting { .. }) => {
            s.text("back to reading", "返回阅读")
        }
        InputMode::Compose => s.text("keep draft", "保留草稿"),
        InputMode::Search { .. } => s.text("collapse, keep keyword", "收起并保留关键词"),
        InputMode::Date { .. } | InputMode::Confirm(_) => s.text("cancel", "取消"),
        InputMode::Picker(_) | InputMode::Message { .. } | InputMode::Help { .. } => {
            s.text("close", "关闭")
        }
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
                    | View::Loading(_)
                    | View::Failed { .. } => s.screen_title(previous.screen()),
                };
                return Some(format!("{} {title}", s.text("back to", "返回")));
            }
        }
    };
    Some(target.to_owned())
}
