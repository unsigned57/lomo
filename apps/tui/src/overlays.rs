//! Small overlays and their shared mouse geometry.
use crate::event::Command;
use crate::i18n::UiStrings;
use crate::model::{AppModel, Confirmation, InputMode};
use ratatui::{
    Frame,
    layout::Rect,
    style::{Color, Style},
    text::Line,
    widgets::{Block, Borders, Clear, Paragraph},
};
use unicode_width::UnicodeWidthStr;

#[must_use]
pub fn header_controls(area: Rect, s: &UiStrings) -> Vec<(Rect, String, Command)> {
    let labels = [
        (s.text("/ Search", "/ 搜索"), Command::Search),
        (s.text("n Capture", "n 记录"), Command::Compose),
        (s.text("t Tags", "t 标签"), Command::Tags),
        (s.text("c Date", "c 日期"), Command::Date),
        ("…", Command::Functions),
    ];
    let mut out = Vec::new();
    let mut right = area.right();
    for (label, command) in labels.into_iter().rev() {
        let width = u16::try_from(UnicodeWidthStr::width(label)).unwrap_or(area.width);
        if right.saturating_sub(area.x) < width + 7 {
            continue;
        }
        right = right.saturating_sub(width);
        out.push((
            Rect::new(right, area.y, width, 1),
            label.to_owned(),
            command,
        ));
        right = right.saturating_sub(2);
    }
    out
}
#[must_use]
pub fn overlay_area(model: &AppModel) -> Rect {
    let width = model.width.saturating_sub(4).min(72);
    let height = model.height.saturating_sub(4).min(16);
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
        .border_style(Style::default().fg(Color::DarkGray));
    let inner = block.inner(area);
    frame.render_widget(block, area);
    match &model.input {
        InputMode::Picker(picker) => draw_picker(frame, model, picker),
        InputMode::Date { text, error, .. } => {
            let prompt = s.text(
                "Date: YYYY-MM-DD or YYYY-MM-DD..YYYY-MM-DD\nPresets: today / week / month",
                "日期：YYYY-MM-DD 或 YYYY-MM-DD..YYYY-MM-DD\n快捷范围：today / week / month",
            );
            frame.render_widget(Paragraph::new(prompt), inner);
            crate::ui::draw_field(
                frame,
                Rect::new(
                    inner.x,
                    inner.y + 3,
                    inner.width,
                    inner.height.saturating_sub(3).min(1),
                ),
                text,
            );
            if let Some(error) = error {
                frame.render_widget(
                    Paragraph::new(error.as_str()).style(Style::default().fg(Color::Red)),
                    Rect::new(
                        inner.x,
                        inner.y + 5,
                        inner.width,
                        inner.height.saturating_sub(5),
                    ),
                );
            }
        }
        InputMode::Confirm(confirmation) => {
            let prompt = match confirmation {
                Confirmation::Delete { .. } => {
                    s.text("Move this memo to trash?", "将这条记录移入回收站？")
                }
                Confirmation::Restore(_) => s.text("Restore this memo?", "恢复这条记录？"),
                Confirmation::DiscardDraft => {
                    s.text("Discard the capture draft?", "丢弃速记草稿？")
                }
            };
            frame.render_widget(
                Paragraph::new(format!("{prompt}\n\ny / Enter   ·   n / Esc")),
                inner,
            );
        }
        InputMode::Message {
            title,
            lines,
            scroll,
        } => {
            let lines = std::iter::once(title.clone())
                .chain(lines.iter().skip(*scroll).cloned())
                .map(Line::raw)
                .collect::<Vec<_>>();
            frame.render_widget(Paragraph::new(lines), inner);
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
#[must_use]
pub fn help(s: &UiStrings) -> Vec<String> {
    [
        s.text("Lomo · capture, find, read", "Lomo · 记录、回找、阅读"),
        "",
        s.text(
            "j/k ↑↓   select memo / scroll reader",
            "j/k ↑↓   选择记录／滚动全文",
        ),
        s.text("Enter     read / choose", "Enter     阅读／选择"),
        s.text("Esc       return / keep draft", "Esc       返回／保留草稿"),
        s.text("n         quick capture", "n         随手记录"),
        s.text("Ctrl+S    save capture", "Ctrl+S    保存速记"),
        s.text(
            "Ctrl+E    capture in external editor",
            "Ctrl+E    在外部编辑器写速记",
        ),
        s.text(
            "e         edit memo externally",
            "e         外部编辑器编辑记录",
        ),
        s.text(
            "/ t c     search / tags / date",
            "/ t c     搜索／标签／日期",
        ),
        s.text(
            "Ctrl+F    fulltext / fuzzy + pinyin",
            "Ctrl+F    全文／模糊与拼音",
        ),
        s.text("Ctrl+P    searchable functions", "Ctrl+P    搜索功能菜单"),
        s.text(".         memo actions", ".         记录操作"),
        s.text("a         attachments", "a         附件"),
        s.text("F5        refresh workspace", "F5        刷新工作区"),
        s.text(
            "Shift+drag terminal text selection",
            "Shift＋拖动 使用终端选字",
        ),
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

fn draw_picker(frame: &mut Frame, model: &AppModel, picker: &crate::model::Picker) {
    let area = picker_area(model);
    crate::ui::draw_field(
        frame,
        Rect::new(area.x, area.y.saturating_sub(2), area.width, 1),
        &picker.text,
    );
    let entries = crate::menu::entries(&model.tags, picker);
    let selected = picker.selected.min(entries.len().saturating_sub(1));
    let top = picker_top(selected, entries.len(), area.height);
    let lines: Vec<_> = entries
        .iter()
        .enumerate()
        .skip(top)
        .take(usize::from(area.height))
        .map(|(index, entry)| {
            Line::styled(
                format!(
                    "{} {}",
                    if index == selected { "▎" } else { " " },
                    entry.label
                ),
                Style::default().fg(if index == selected {
                    Color::Cyan
                } else {
                    Color::Reset
                }),
            )
        })
        .collect();
    frame.render_widget(Paragraph::new(lines), area);
}
