//! Small overlays and their shared mouse geometry.
use crate::i18n::UiStrings;
use crate::menu::MenuRow;
use crate::model::{AppModel, Confirmation, InputMode, PaletteItem, PaletteScope, PickerKind};
use ratatui::{
    Frame,
    layout::Rect,
    style::{Color, Modifier, Style},
    text::{Line, Span},
    widgets::{Block, BorderType, Borders, Clear, Paragraph, Wrap},
};
use unicode_width::UnicodeWidthStr;

/// Pickers stay compact; reading overlays (help, history, notices) may use a taller box.
#[must_use]
pub fn overlay_area(model: &AppModel) -> Rect {
    let max_height = match model.input {
        InputMode::Help { .. } | InputMode::Message { .. } => 34,
        InputMode::Browse
        | InputMode::Compose
        | InputMode::Search { .. }
        | InputMode::Picker(_)
        | InputMode::Date { .. }
        | InputMode::Confirm(_) => 16,
    };
    let width = model.width.saturating_sub(4).min(72);
    let height = model.height.saturating_sub(4).min(max_height);
    Rect::new(
        model.width.saturating_sub(width) / 2,
        model.height.saturating_sub(height) / 2,
        width,
        height,
    )
}
pub fn draw(frame: &mut Frame, model: &AppModel, s: &UiStrings) {
    if matches!(
        model.input,
        InputMode::Browse | InputMode::Compose | InputMode::Search { .. }
    ) {
        return;
    }
    let area = overlay_area(model);
    frame.render_widget(Clear, area);
    let block = Block::default()
        .borders(Borders::ALL)
        .border_type(BorderType::Rounded)
        .border_style(Style::default().fg(Color::DarkGray))
        .title(Span::styled(
            format!(" {} ", overlay_title(model, s)),
            Style::default().add_modifier(Modifier::BOLD),
        ));
    let inner = block.inner(area);
    frame.render_widget(block, area);
    match &model.input {
        InputMode::Picker(picker) => draw_picker(frame, model, picker, s),
        InputMode::Date { text, error, .. } => draw_date(frame, inner, text, error.as_deref(), s),
        InputMode::Confirm(confirmation) => draw_confirm(frame, inner, confirmation, s),
        InputMode::Message { lines, scroll, .. } => {
            let lines = lines
                .iter()
                .skip(*scroll)
                .map(|line| Line::raw(line.as_str()))
                .collect::<Vec<_>>();
            frame.render_widget(Paragraph::new(lines).wrap(Wrap { trim: false }), inner);
        }
        InputMode::Help { scroll } => {
            let lines = help(s)
                .into_iter()
                .skip(*scroll)
                .map(Line::raw)
                .collect::<Vec<_>>();
            frame.render_widget(Paragraph::new(lines), inner);
        }
        InputMode::Browse | InputMode::Compose | InputMode::Search { .. } => {}
    }
}

fn draw_date(
    frame: &mut Frame,
    inner: Rect,
    text: &crate::input::TextBuffer,
    error: Option<&str>,
    s: &UiStrings,
) {
    let prompt = s.text(
        "YYYY-MM-DD, or a range YYYY-MM-DD..YYYY-MM-DD\nPresets: today · yesterday · week · month",
        "YYYY-MM-DD，或范围 YYYY-MM-DD..YYYY-MM-DD\n快捷范围：today · yesterday · week · month",
    );
    frame.render_widget(
        Paragraph::new(prompt).style(Style::default().fg(Color::DarkGray)),
        inner,
    );
    crate::ui::draw_field(
        frame,
        Rect::new(
            inner.x,
            inner.y + 3,
            inner.width,
            inner.height.saturating_sub(3).min(1),
        ),
        text,
        None,
    );
    if let Some(error) = error {
        frame.render_widget(
            Paragraph::new(error)
                .style(Style::default().fg(Color::Red))
                .wrap(Wrap { trim: false }),
            Rect::new(
                inner.x,
                inner.y + 5,
                inner.width,
                inner.height.saturating_sub(5),
            ),
        );
    }
}

fn draw_confirm(frame: &mut Frame, inner: Rect, confirmation: &Confirmation, s: &UiStrings) {
    let prompt = match confirmation {
        Confirmation::Delete { .. } => s.text(
            "Move this memo to the trash? It can be restored from there.",
            "将这条记录移入回收站？之后可以从回收站恢复。",
        ),
        Confirmation::DeleteForever(_) => s.text(
            "Delete this memo permanently? This cannot be undone.",
            "永久删除这条记录？此操作无法撤销。",
        ),
        Confirmation::EmptyTrash => s.text(
            "Delete every memo in the trash permanently? This cannot be undone.",
            "永久删除回收站里的全部记录？此操作无法撤销。",
        ),
        Confirmation::Restore(_) => s.text(
            "Restore this memo to the timeline?",
            "将这条记录恢复到时间线？",
        ),
        Confirmation::RestoreRevision { .. } => s.text(
            "Replace the current body with this revision? The current body stays in history.",
            "用这个版本替换当前正文？当前正文会保留在历史里。",
        ),
        Confirmation::DiscardDraft => s.text(
            "Discard the capture draft? This cannot be undone.",
            "丢弃速记草稿？此操作无法撤销。",
        ),
    };
    let keys = s.text(
        "y / Enter confirm   ·   n / Esc cancel",
        "y / Enter 确认   ·   n / Esc 取消",
    );
    frame.render_widget(
        Paragraph::new(vec![
            Line::raw(prompt),
            Line::default(),
            Line::styled(keys, Style::default().fg(Color::DarkGray)),
        ])
        .wrap(Wrap { trim: false }),
        inner,
    );
}

const fn overlay_title<'a>(model: &'a AppModel, s: &UiStrings) -> &'a str {
    match &model.input {
        InputMode::Picker(picker) => match &picker.kind {
            PickerKind::Palette {
                scope: PaletteScope::All,
                ..
            }
            | PickerKind::Palette {
                item: PaletteItem::None,
                ..
            } => s.text("Commands", "命令"),
            PickerKind::Palette {
                item: PaletteItem::Memo(_),
                ..
            } => s.text("Memo actions", "记录操作"),
            PickerKind::Palette {
                item: PaletteItem::Task(_),
                ..
            } => s.text("Todo actions", "待办操作"),
            PickerKind::Palette {
                item: PaletteItem::Attachment(_),
                ..
            } => s.text("Attachment actions", "附件操作"),
            PickerKind::Tags(_) => s.text("Filter by tag", "按标签筛选"),
            PickerKind::Dates => s.text("Filter by date", "按日期筛选"),
            PickerKind::Attachments(_) => s.text("Attachments", "附件"),
            PickerKind::History { .. } => {
                s.text("History · Enter restores", "版本历史 · Enter 恢复")
            }
        },
        InputMode::Date { .. } => s.text("Custom date range", "自定义日期范围"),
        InputMode::Confirm(_) => s.text("Confirm", "确认"),
        InputMode::Message { title, .. } => title.as_str(),
        InputMode::Help { .. } => s.text("Help", "帮助"),
        InputMode::Browse | InputMode::Compose | InputMode::Search { .. } => "",
    }
}

#[must_use]
pub fn help(s: &UiStrings) -> Vec<String> {
    [
        s.text("Navigate", "导航"),
        s.text(
            "j/k ↑↓    select memo / scroll reader",
            "j/k ↑↓    选择记录／滚动全文",
        ),
        s.text("Ctrl+D/U  page down / up", "Ctrl+D/U  向下／向上翻页"),
        s.text("g / G     first / last", "g / G     开头／末尾"),
        s.text(
            "Enter     read / choose / toggle todo",
            "Enter     阅读／选择／切换待办",
        ),
        s.text(
            "Esc       one layer back; the hint bar names the target",
            "Esc       退回一层；提示行写明目标",
        ),
        "",
        s.text("Write", "输入"),
        s.text("n         quick capture", "n         随手记录"),
        s.text("Ctrl+S    save capture", "Ctrl+S    保存速记"),
        s.text(
            "Tab       complete #tag while capturing",
            "Tab       速记时补全 #标签",
        ),
        s.text(
            "Ctrl+E    capture in external editor",
            "Ctrl+E    在外部编辑器写速记",
        ),
        s.text(
            "/         search; Ctrl+F fulltext / fuzzy + pinyin",
            "/         搜索；Ctrl+F 全文／模糊与拼音",
        ),
        "",
        s.text("Direct actions", "直接动作"),
        s.text(
            "e         edit memo externally",
            "e         外部编辑器编辑记录",
        ),
        s.text("m         pin / unpin", "m         置顶／取消置顶"),
        s.text(
            "d         move to trash / delete permanently in trash",
            "d         移入回收站／在回收站中永久删除",
        ),
        s.text("F5        refresh workspace", "F5        刷新工作区"),
        "",
        s.text("Palette", "面板"),
        s.text(
            ":         commands: this item, filters, pages, everything else",
            ":         命令：当前项、筛选、页面、全局",
        ),
        s.text(
            ".         actions for this item only",
            ".         仅当前项的操作",
        ),
        s.text(
            "          tags, dates, history, attachments, trash, clipboard live here",
            "          标签、日期、历史、附件、回收站、剪贴板都在这里",
        ),
        "",
        s.text(
            "Mouse     click to select, wheel to scroll",
            "鼠标      点击选择，滚轮滚动",
        ),
        s.text(
            "Shift+drag terminal text selection",
            "Shift＋拖动 使用终端选字",
        ),
        s.text("?         this help", "?         本帮助"),
        s.text("q         quit", "q         退出"),
    ]
    .into_iter()
    .map(str::to_owned)
    .collect()
}

#[must_use]
pub fn picker_area(model: &AppModel) -> Rect {
    let area = overlay_area(model);
    Rect::new(
        area.x + 1,
        area.y + 3,
        area.width.saturating_sub(2),
        area.height.saturating_sub(4),
    )
}

#[must_use]
pub fn picker_top(selected: usize, length: usize, height: u16) -> usize {
    selected
        .min(length.saturating_sub(1))
        .saturating_sub(usize::from(height).saturating_sub(1))
}

fn draw_picker(frame: &mut Frame, model: &AppModel, picker: &crate::model::Picker, s: &UiStrings) {
    let area = picker_area(model);
    crate::ui::draw_field(
        frame,
        Rect::new(area.x, area.y.saturating_sub(2), area.width, 1),
        &picker.text,
        Some(s.text("Type to filter…", "输入以筛选…")),
    );
    let rows = crate::menu::rows(model, picker);
    if rows.is_empty() {
        let text = match &picker.kind {
            PickerKind::Attachments(_) if picker.text.text().is_empty() => {
                s.text("This memo has no attachments", "这条记录没有附件")
            }
            PickerKind::Tags(_) if picker.text.text().is_empty() => {
                s.text("No tags yet", "还没有标签")
            }
            PickerKind::History { .. } if picker.text.text().is_empty() => {
                s.text("No earlier revisions", "没有更早的版本")
            }
            PickerKind::Palette { .. }
            | PickerKind::Tags(_)
            | PickerKind::Dates
            | PickerKind::Attachments(_)
            | PickerKind::History { .. } => s.text("No matches", "没有匹配项"),
        };
        frame.render_widget(
            Paragraph::new(format!("  {text}")).style(Style::default().fg(Color::DarkGray)),
            area,
        );
        return;
    }
    let selected = crate::menu::selected_row(&rows, picker.selected);
    let top = picker_top(selected, rows.len(), area.height);
    let lines: Vec<_> = rows
        .iter()
        .enumerate()
        .skip(top)
        .take(usize::from(area.height))
        .map(|(index, row)| match row {
            MenuRow::Header(title) => Line::styled(
                format!("  {title}"),
                Style::default()
                    .fg(Color::DarkGray)
                    .add_modifier(Modifier::BOLD),
            ),
            MenuRow::Entry(entry) => {
                let active = index == selected;
                let key = entry.key.unwrap_or("");
                let label_width = UnicodeWidthStr::width(entry.label.as_str());
                let pad = usize::from(area.width)
                    .saturating_sub(2 + label_width + UnicodeWidthStr::width(key) + 1)
                    .max(1);
                Line::from(vec![
                    Span::styled(
                        format!("{} {}", if active { "▎" } else { " " }, entry.label),
                        Style::default().fg(if active { Color::Cyan } else { Color::Reset }),
                    ),
                    Span::styled(
                        format!("{}{key}", " ".repeat(pad)),
                        Style::default().fg(Color::DarkGray),
                    ),
                ])
            }
        })
        .collect();
    frame.render_widget(Paragraph::new(lines), area);
}
