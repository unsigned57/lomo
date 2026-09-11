//! Styles [`lomo_workspace::RenderDocumentV1`] with Think's markdown palette.

use lomo_workspace::{RenderBlock, RenderInline, RenderListItem, SourceBytes, render_markdown};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};

use crate::media::{GraphicsProtocol, MediaKind, preview_media_line};

/// Parses memo Markdown into styled preview lines.
#[must_use]
pub fn styled_preview(body: &str, graphics: GraphicsProtocol, base: Color) -> Vec<Line<'static>> {
    let Ok(source) = SourceBytes::try_from_str(body) else {
        return vec![Line::from(body.to_owned())];
    };
    let Ok(document) = render_markdown(&source) else {
        return vec![Line::from(body.to_owned())];
    };
    let mut lines = Vec::new();
    for block in document.blocks() {
        push_block(block, graphics, base, &mut lines);
    }
    if lines.is_empty() {
        vec![Line::default()]
    } else {
        lines
    }
}

fn push_block(
    block: &RenderBlock,
    graphics: GraphicsProtocol,
    base: Color,
    lines: &mut Vec<Line<'static>>,
) {
    match block {
        RenderBlock::Paragraph { inlines, .. } => {
            lines.push(inlines_line(inlines, graphics, Style::default().fg(base)));
        }
        RenderBlock::Heading { inlines, .. } => {
            lines.push(inlines_line(
                inlines,
                graphics,
                Style::default()
                    .fg(Color::Cyan)
                    .add_modifier(Modifier::BOLD),
            ));
            lines.push(Line::default());
        }
        RenderBlock::BlockQuote { blocks, .. } => {
            for child in blocks {
                push_block(child, graphics, Color::DarkGray, lines);
            }
        }
        RenderBlock::List { items, ordered, .. } => {
            for (index, item) in items.iter().enumerate() {
                push_list_item(item, graphics, base, *ordered, index, lines);
            }
        }
        RenderBlock::CodeBlock { literal, .. } => {
            for line in literal.lines() {
                lines.push(Line::from(Span::styled(
                    line.to_owned(),
                    Style::default()
                        .fg(Color::Yellow)
                        .bg(Color::Rgb(50, 50, 50)),
                )));
            }
        }
        RenderBlock::ThematicBreak { .. } => {
            lines.push(Line::from(Span::styled(
                "---",
                Style::default().fg(Color::DarkGray),
            )));
        }
        RenderBlock::Table { header, rows, .. } => {
            let mut cells = Vec::new();
            for cell in header {
                push_inline_text(&cell.inlines, &mut cells);
            }
            for row in rows {
                for cell in row {
                    push_inline_text(&cell.inlines, &mut cells);
                }
            }
            if !cells.is_empty() {
                lines.push(Line::from(cells.join(" ")));
            }
        }
        RenderBlock::HtmlBlock { literal, .. } => {
            for line in literal.lines() {
                lines.push(Line::from(Span::styled(
                    line.to_owned(),
                    Style::default().fg(Color::DarkGray),
                )));
            }
        }
    }
}

fn push_list_item(
    item: &RenderListItem,
    graphics: GraphicsProtocol,
    base: Color,
    ordered: bool,
    index: usize,
    lines: &mut Vec<Line<'static>>,
) {
    let mut prefix = Vec::new();
    match item.checked {
        Some(true) => prefix.push(Span::styled(
            "✓ ",
            Style::default()
                .fg(Color::Green)
                .add_modifier(Modifier::BOLD),
        )),
        Some(false) => prefix.push(Span::styled(
            "» ",
            Style::default()
                .fg(Color::Yellow)
                .add_modifier(Modifier::BOLD),
        )),
        None if ordered => prefix.push(Span::styled(
            format!("{}. ", index.saturating_add(1)),
            Style::default().fg(Color::Blue),
        )),
        None => prefix.push(Span::styled("• ", Style::default().fg(Color::Blue))),
    }
    let item_style = if item.checked == Some(true) {
        Style::default()
            .fg(Color::DarkGray)
            .add_modifier(Modifier::CROSSED_OUT)
    } else {
        Style::default().fg(base)
    };
    if item.blocks.is_empty() {
        lines.push(Line::from(prefix));
        return;
    }
    for (block_index, block) in item.blocks.iter().enumerate() {
        let mut nested = Vec::new();
        push_block(block, graphics, base, &mut nested);
        for (line_index, line) in nested.into_iter().enumerate() {
            if block_index == 0 && line_index == 0 {
                let mut spans = prefix.clone();
                spans.extend(recolor_spans(line.spans, item_style));
                lines.push(Line::from(spans));
            } else {
                lines.push(line);
            }
        }
    }
}

fn inlines_line(
    inlines: &[RenderInline],
    graphics: GraphicsProtocol,
    style: Style,
) -> Line<'static> {
    let mut spans = Vec::new();
    push_inlines(inlines, graphics, style, &mut spans);
    Line::from(spans)
}

fn push_inlines(
    inlines: &[RenderInline],
    graphics: GraphicsProtocol,
    style: Style,
    spans: &mut Vec<Span<'static>>,
) {
    for inline in inlines {
        match inline {
            RenderInline::Text { text, .. } | RenderInline::HtmlInline { text, .. } => {
                spans.push(Span::styled(text.clone(), style));
            }
            RenderInline::Code { text, .. } => {
                spans.push(Span::styled(
                    text.clone(),
                    style.fg(Color::Yellow).bg(Color::Rgb(50, 50, 50)),
                ));
            }
            RenderInline::Strong { children, .. } => {
                push_inlines(
                    children,
                    graphics,
                    style.add_modifier(Modifier::BOLD),
                    spans,
                );
            }
            RenderInline::Emphasis { children, .. } => {
                push_inlines(
                    children,
                    graphics,
                    style.add_modifier(Modifier::ITALIC),
                    spans,
                );
            }
            RenderInline::Strikethrough { children, .. } => {
                push_inlines(
                    children,
                    graphics,
                    style.add_modifier(Modifier::CROSSED_OUT),
                    spans,
                );
            }
            RenderInline::Highlight { children, .. } => {
                push_inlines(children, graphics, style.bg(Color::Rgb(60, 60, 40)), spans);
            }
            RenderInline::Link { children, .. } | RenderInline::WikiReference { children, .. } => {
                push_inlines(
                    children,
                    graphics,
                    style.fg(Color::Blue).add_modifier(Modifier::UNDERLINED),
                    spans,
                );
            }
            RenderInline::Image { destination, .. } => {
                spans.push(Span::styled(
                    preview_media_line(destination, MediaKind::Image, graphics),
                    Style::default().fg(Color::DarkGray),
                ));
            }
            RenderInline::Tag { name, .. } => {
                spans.push(Span::styled(
                    format!("#{name}"),
                    Style::default().fg(Color::Cyan),
                ));
            }
            RenderInline::Reminder { token, .. } => {
                spans.push(Span::styled(
                    token.clone(),
                    Style::default().fg(Color::Magenta),
                ));
            }
            RenderInline::SoftBreak { .. } | RenderInline::HardBreak { .. } => {
                spans.push(Span::raw(" "));
            }
        }
    }
}

fn push_inline_text(inlines: &[RenderInline], out: &mut Vec<String>) {
    for inline in inlines {
        match inline {
            RenderInline::Text { text, .. }
            | RenderInline::Code { text, .. }
            | RenderInline::HtmlInline { text, .. } => out.push(text.clone()),
            RenderInline::Strong { children, .. }
            | RenderInline::Emphasis { children, .. }
            | RenderInline::Strikethrough { children, .. }
            | RenderInline::Highlight { children, .. }
            | RenderInline::Link { children, .. }
            | RenderInline::WikiReference { children, .. } => push_inline_text(children, out),
            RenderInline::Image { destination, .. } => out.push(destination.clone()),
            RenderInline::Tag { name, .. } => out.push(format!("#{name}")),
            RenderInline::Reminder { token, .. } => out.push(token.clone()),
            RenderInline::SoftBreak { .. } | RenderInline::HardBreak { .. } => out.push(" ".into()),
        }
    }
}

fn recolor_spans(spans: Vec<Span<'static>>, style: Style) -> Vec<Span<'static>> {
    if style.add_modifier.contains(Modifier::CROSSED_OUT) {
        spans
            .into_iter()
            .map(|span| {
                Span::styled(
                    span.content.to_string(),
                    style
                        .fg(Color::DarkGray)
                        .add_modifier(Modifier::CROSSED_OUT),
                )
            })
            .collect()
    } else {
        spans
    }
}
