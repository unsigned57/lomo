use ratatui::Frame;
use ratatui::layout::{Alignment, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{
    Block, Borders, Clear, List, ListItem, ListState, Padding, Paragraph, Wrap,
};

use crate::event::PALETTE_LABELS;
use crate::i18n::UiStrings;
use crate::layout::{LayoutRequest, Pane, layout_mode, split_panes};
use crate::markdown_view::styled_preview;
use crate::media::GraphicsProtocol;
use crate::model::{AppModel, ListRow, Overlay, Screen, SearchSession};
use crate::stats_draw::draw_stats;
use crate::theme::{list_highlight, panel, theme_color, todo_highlight};

/// Draws the responsive shell and any overlay using Think panel chrome.
pub fn draw(frame: &mut Frame, model: &AppModel) {
    let i18n = UiStrings::detect();
    let area = frame.area();
    frame.render_widget(Clear, area);
    let search_open = matches!(model.search, SearchSession::Open { .. });
    if !search_open && model.screen == Screen::Statistics {
        if let Some(stats) = &model.stats {
            draw_stats(frame, area, stats, &i18n);
        } else {
            draw_message(frame, &i18n.header_stats, &i18n.hint_empty, Color::Cyan);
        }
    } else if !search_open && model.screen == Screen::Tasks {
        draw_todo(frame, model, area, &i18n);
    } else {
        draw_shell(frame, model, area, &i18n, search_open);
    }
    draw_overlay(frame, model, &i18n);
}

fn draw_shell(
    frame: &mut Frame,
    model: &AppModel,
    area: Rect,
    i18n: &UiStrings,
    search_open: bool,
) {
    let color = theme_color(model.screen, search_open);
    let panes = split_panes(
        Pane {
            x: area.x,
            y: area.y,
            width: area.width,
            height: area.height,
        },
        LayoutRequest {
            mode: layout_mode(model.width),
            focus: model.focus,
            nav: model.nav,
            search_open,
        },
    );
    if let Some(pane) = panes.navigation {
        draw_nav(frame, model, to_rect(pane), i18n, color);
    }
    if let Some(pane) = panes.list {
        draw_list(frame, model, to_rect(pane), i18n, color, search_open);
    }
    if let Some(pane) = panes.preview {
        draw_preview(frame, model, to_rect(pane), i18n, color);
    }
    if let Some(pane) = panes.search {
        draw_search(frame, model, to_rect(pane), i18n, color);
    }
    draw_status(frame, model, to_rect(panes.status), i18n);
}

fn draw_nav(frame: &mut Frame, model: &AppModel, area: Rect, i18n: &UiStrings, color: Color) {
    let items: Vec<ListItem> = i18n
        .nav_labels
        .into_iter()
        .map(|label| ListItem::new(format!(" {label}")))
        .collect();
    let mut state = ListState::default();
    state.select(Some(model.nav_selected));
    frame.render_stateful_widget(
        List::new(items)
            .block(panel(format!(" [ {} ] ", i18n.title_nav), color))
            .highlight_style(list_highlight())
            .highlight_symbol("> "),
        area,
        &mut state,
    );
}

fn draw_list(
    frame: &mut Frame,
    model: &AppModel,
    area: Rect,
    i18n: &UiStrings,
    color: Color,
    search_open: bool,
) {
    let title = if search_open {
        i18n.search_list_title(model.items.len(), &model.status)
    } else {
        i18n.list_title(model.screen, model.items.len(), &model.status)
    };
    let items: Vec<ListItem> = model
        .items
        .iter()
        .map(|row| list_item(row, color))
        .collect();
    let mut state = ListState::default();
    if !model.items.is_empty() {
        state.select(Some(model.selected));
    }
    frame.render_stateful_widget(
        List::new(items)
            .block(panel(title, color))
            .highlight_style(list_highlight())
            .highlight_symbol("> "),
        area,
        &mut state,
    );
}

fn list_item(row: &ListRow, color: Color) -> ListItem<'static> {
    if let Some(done) = row.done {
        return todo_item(row, done);
    }
    let mut spans = Vec::new();
    if !row.header.is_empty() {
        spans.push(Span::styled(row.header.clone(), Style::default().fg(color)));
    }
    if !row.title.is_empty() {
        spans.push(Span::raw(row.title.clone()));
    }
    if !row.subtitle.is_empty() {
        spans.push(Span::styled(
            format!("  {}", row.subtitle),
            Style::default().fg(Color::DarkGray),
        ));
    }
    if spans.is_empty() {
        ListItem::new(" ")
    } else {
        ListItem::new(Line::from(spans))
    }
}

fn todo_item(row: &ListRow, done: bool) -> ListItem<'static> {
    let (symbol, color, modifier) = if done {
        ("✓ ", Color::Green, Modifier::BOLD)
    } else {
        ("» ", Color::Yellow, Modifier::BOLD)
    };
    let content_style = if done {
        Style::default()
            .fg(Color::DarkGray)
            .add_modifier(Modifier::CROSSED_OUT)
    } else {
        Style::default().fg(Color::Reset)
    };
    ListItem::new(Line::from(vec![
        Span::styled(symbol, Style::default().fg(color).add_modifier(modifier)),
        Span::styled(row.title.clone(), content_style),
        Span::styled(
            format!("  ({})", row.subtitle),
            Style::default().fg(Color::DarkGray),
        ),
    ]))
}

fn draw_todo(frame: &mut Frame, model: &AppModel, area: Rect, i18n: &UiStrings) {
    let items: Vec<ListItem> = model
        .items
        .iter()
        .map(|row| todo_item(row, row.done.unwrap_or(false)))
        .collect();
    let mut state = ListState::default();
    if !model.items.is_empty() {
        state.select(Some(model.selected));
    }
    let block = panel(
        i18n.list_title(Screen::Tasks, model.items.len(), &model.status),
        Color::Blue,
    )
    .padding(Padding::new(1, 1, 1, 1));
    frame.render_stateful_widget(
        List::new(items)
            .block(block)
            .highlight_style(todo_highlight())
            .highlight_symbol(">> "),
        area,
        &mut state,
    );
    frame.render_widget(
        Block::default()
            .title(i18n.hint_toggle_todo.as_str())
            .title_alignment(Alignment::Right)
            .borders(Borders::NONE),
        area,
    );
}

fn draw_preview(frame: &mut Frame, model: &AppModel, area: Rect, i18n: &UiStrings, color: Color) {
    let title = i18n.preview_title(model.screen);
    let block = panel(title, color);
    let inner = block.inner(area);
    frame.render_widget(block, area);
    let body = if model.preview.is_empty() {
        i18n.hint_empty.as_str()
    } else {
        model.preview.as_str()
    };
    let lines = styled_preview(body, GraphicsProtocol::None, Color::Reset);
    frame.render_widget(Paragraph::new(lines).wrap(Wrap { trim: true }), inner);
}

fn draw_search(frame: &mut Frame, model: &AppModel, area: Rect, i18n: &UiStrings, color: Color) {
    let (query, mode) = match &model.search {
        SearchSession::Open { query, mode, .. } => (query.as_str(), *mode),
        SearchSession::Closed => ("", model.search_mode),
    };
    let mode_label = match mode {
        lomo_application::SearchMode::Fulltext => i18n.search_fulltext.as_str(),
        lomo_application::SearchMode::Fuzzy => i18n.search_fuzzy.as_str(),
    };
    frame.render_widget(
        Paragraph::new(query)
            .block(panel(
                format!("{} {mode_label}", i18n.search_input.trim()),
                color,
            ))
            .style(Style::default().fg(Color::Reset)),
        area,
    );
    if area.width > 2 {
        let cursor_x = (area.x.saturating_add(1).saturating_add(display_cols(query)))
            .min(area.x.saturating_add(area.width.saturating_sub(1)));
        frame.set_cursor_position((cursor_x, area.y.saturating_add(1)));
    }
}

fn draw_status(frame: &mut Frame, model: &AppModel, area: Rect, i18n: &UiStrings) {
    if area.height == 0 {
        return;
    }
    let text = if model.status.is_empty() {
        i18n.hint_keys.as_str()
    } else {
        model.status.as_str()
    };
    frame.render_widget(
        Paragraph::new(text).style(Style::default().fg(Color::DarkGray)),
        area,
    );
}

fn draw_overlay(frame: &mut Frame, model: &AppModel, i18n: &UiStrings) {
    match &model.overlay {
        Overlay::None => {}
        Overlay::Help => draw_help(frame, i18n),
        Overlay::Palette { index } => draw_palette(frame, *index, i18n),
        Overlay::Alert { title, body } => {
            draw_dialog(frame, title, body, Color::Yellow);
        }
        Overlay::Confirm { title, body, .. } => {
            draw_confirm(frame, title, body, Color::Yellow, i18n);
        }
        Overlay::Overdue { lines } => {
            draw_lines(frame, &i18n.title_overdue, lines, Color::Yellow);
        }
        Overlay::History { lines } => {
            draw_lines(frame, &i18n.title_history, lines, Color::Magenta);
        }
    }
}

fn draw_help(frame: &mut Frame, i18n: &UiStrings) {
    let area = centered(frame.area(), 60, 18);
    frame.render_widget(Clear, area);
    let lines: Vec<Line> = i18n
        .help_lines
        .into_iter()
        .map(|(key, desc)| {
            Line::from(vec![
                Span::styled(format!("  {key:<16}"), Style::default().fg(Color::Green)),
                Span::raw(desc),
            ])
        })
        .collect();
    frame.render_widget(
        Paragraph::new(lines).block(panel(i18n.title_help.clone(), Color::Cyan)),
        area,
    );
}

fn draw_palette(frame: &mut Frame, index: usize, i18n: &UiStrings) {
    let area = centered(frame.area(), 40, 16);
    frame.render_widget(Clear, area);
    let items: Vec<ListItem> = i18n
        .palette_labels
        .into_iter()
        .map(|label| ListItem::new(format!(" {label}")))
        .collect();
    let mut state = ListState::default();
    state.select(Some(index.min(PALETTE_LABELS.len().saturating_sub(1))));
    frame.render_stateful_widget(
        List::new(items)
            .block(panel(format!(" [ {} ] ", i18n.title_commands), Color::Cyan))
            .highlight_style(list_highlight())
            .highlight_symbol("> "),
        area,
        &mut state,
    );
}

fn draw_confirm(frame: &mut Frame, title: &str, body: &str, color: Color, i18n: &UiStrings) {
    let area = centered(frame.area(), 40, 10);
    frame.render_widget(Clear, area);
    let text = vec![
        Line::from(""),
        Line::from(Span::styled(
            body.to_owned(),
            Style::default().fg(color).add_modifier(Modifier::BOLD),
        )),
        Line::from(""),
        Line::from(vec![
            Span::styled(
                i18n.confirm_yes.clone(),
                Style::default().fg(Color::Red).add_modifier(Modifier::BOLD),
            ),
            Span::raw("          "),
            Span::styled(i18n.confirm_no.clone(), Style::default().fg(Color::Green)),
        ]),
    ];
    frame.render_widget(
        Paragraph::new(text)
            .block(panel(title.to_owned(), color))
            .alignment(Alignment::Center),
        area,
    );
}

fn draw_dialog(frame: &mut Frame, title: &str, body: &str, color: Color) {
    let area = centered(frame.area(), 50, 8);
    frame.render_widget(Clear, area);
    frame.render_widget(
        Paragraph::new(body.to_owned()).block(panel(title.to_owned(), color)),
        area,
    );
}

fn draw_message(frame: &mut Frame, title: &str, body: &str, color: Color) {
    frame.render_widget(
        Paragraph::new(body.to_owned()).block(panel(title.to_owned(), color)),
        frame.area(),
    );
}

fn draw_lines(frame: &mut Frame, title: &str, lines: &[String], color: Color) {
    let area = centered(frame.area(), 60, 12);
    frame.render_widget(Clear, area);
    let text: Vec<Line> = lines
        .iter()
        .map(|line| Line::from(Span::raw(line.clone())))
        .collect();
    frame.render_widget(
        Paragraph::new(text).block(panel(title.to_owned(), color)),
        area,
    );
}

fn centered(area: Rect, width_pct: u16, height: u16) -> Rect {
    let width = area.width.saturating_mul(width_pct) / 100;
    let height = height.min(area.height);
    let x = area.x.saturating_add(area.width.saturating_sub(width) / 2);
    let y = area
        .y
        .saturating_add(area.height.saturating_sub(height) / 2);
    Rect {
        x,
        y,
        width,
        height,
    }
}

const fn to_rect(pane: Pane) -> Rect {
    Rect {
        x: pane.x,
        y: pane.y,
        width: pane.width,
        height: pane.height,
    }
}

fn display_cols(text: &str) -> u16 {
    let mut width = 0_u16;
    for ch in text.chars() {
        let cols = if ch <= '\u{007f}' { 1 } else { 2 };
        width = width.saturating_add(cols);
    }
    width
}
