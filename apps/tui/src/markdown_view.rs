//! Terminal presentation of the workspace's canonical Markdown render tree.
use lomo_workspace::{RenderBlock, RenderDocumentV1, RenderInline, RenderListItem};
use ratatui::{
    style::{Color, Modifier, Style},
    text::{Line, Span},
};

#[must_use]
pub fn styled_document(document: &RenderDocumentV1) -> Vec<Line<'static>> {
    let mut lines = Vec::new();
    push_blocks(document.blocks(), &mut lines);
    lines
}

fn push_blocks(blocks: &[RenderBlock], lines: &mut Vec<Line<'static>>) {
    for (index, block) in blocks.iter().enumerate() {
        if index > 0 {
            lines.push(Line::default());
        }
        push_block(block, lines);
    }
}

fn push_block(block: &RenderBlock, lines: &mut Vec<Line<'static>>) {
    match block {
        RenderBlock::Paragraph { inlines, .. } => {
            push_inline_lines(inlines, Style::default(), lines);
        }
        RenderBlock::Heading { inlines, level, .. } => {
            let start = lines.len();
            let style = Style::default().add_modifier(Modifier::BOLD);
            push_inline_lines(inlines, style, lines);
            if let Some(line) = lines.get_mut(start) {
                line.spans.insert(
                    0,
                    Span::styled(format!("{} ", "#".repeat(usize::from(*level))), style),
                );
            }
        }
        RenderBlock::BlockQuote { blocks, .. } => {
            let mut quoted = Vec::new();
            push_blocks(blocks, &mut quoted);
            for line in quoted {
                let mut spans = vec![Span::styled("│ ", Style::default().fg(Color::DarkGray))];
                spans.extend(line.spans);
                lines.push(Line::from(spans));
            }
        }
        RenderBlock::List { items, ordered, .. } => {
            for (index, item) in items.iter().enumerate() {
                push_list_item(item, *ordered, index, lines);
            }
        }
        RenderBlock::CodeBlock { literal, .. } | RenderBlock::HtmlBlock { literal, .. } => {
            lines.extend(literal.lines().map(|line| Line::raw(line.to_owned())));
        }
        RenderBlock::ThematicBreak { .. } => lines.push(Line::styled(
            "────────────────",
            Style::default().fg(Color::DarkGray),
        )),
        RenderBlock::Table { header, rows, .. } => {
            let cells = header
                .iter()
                .map(|cell| inline_text(&cell.inlines))
                .collect::<Vec<_>>();
            lines.push(Line::styled(
                cells.join(" │ "),
                Style::default().add_modifier(Modifier::BOLD),
            ));
            lines.push(Line::styled(
                "────────────────",
                Style::default().fg(Color::DarkGray),
            ));
            for row in rows {
                lines.push(Line::raw(
                    row.iter()
                        .map(|cell| inline_text(&cell.inlines))
                        .collect::<Vec<_>>()
                        .join(" │ "),
                ));
            }
        }
    }
}

fn push_list_item(
    item: &RenderListItem,
    ordered: bool,
    index: usize,
    lines: &mut Vec<Line<'static>>,
) {
    let marker = match item.checked {
        Some(true) => "✓ ".to_owned(),
        Some(false) => "□ ".to_owned(),
        None if ordered => format!("{}. ", index + 1),
        None => "• ".to_owned(),
    };
    let mut nested = Vec::new();
    push_blocks(&item.blocks, &mut nested);
    for (index, line) in nested.into_iter().enumerate() {
        let mut spans = vec![Span::raw(if index == 0 {
            marker.clone()
        } else {
            "  ".to_owned()
        })];
        spans.extend(line.spans);
        if item.checked == Some(true) {
            for span in &mut spans {
                span.style = span
                    .style
                    .fg(Color::DarkGray)
                    .add_modifier(Modifier::CROSSED_OUT);
            }
        }
        lines.push(Line::from(spans));
    }
}

fn push_inline_lines(inlines: &[RenderInline], style: Style, lines: &mut Vec<Line<'static>>) {
    let mut spans = Vec::new();
    push_inlines(inlines, style, &mut spans);
    let mut current = Vec::new();
    for span in spans {
        for (index, part) in span.content.split('\n').enumerate() {
            if index > 0 {
                lines.push(Line::from(std::mem::take(&mut current)));
            }
            current.push(Span::styled(part.to_owned(), span.style));
        }
    }
    lines.push(Line::from(current));
}

fn push_inlines(inlines: &[RenderInline], style: Style, spans: &mut Vec<Span<'static>>) {
    for inline in inlines {
        match inline {
            RenderInline::Text { text, .. }
            | RenderInline::Code { text, .. }
            | RenderInline::HtmlInline { text, .. } => {
                spans.push(Span::styled(text.clone(), style));
            }
            RenderInline::Strong { children, .. } => {
                push_inlines(children, style.add_modifier(Modifier::BOLD), spans);
            }
            RenderInline::Emphasis { children, .. } => {
                push_inlines(children, style.add_modifier(Modifier::ITALIC), spans);
            }
            RenderInline::Strikethrough { children, .. } => {
                push_inlines(children, style.add_modifier(Modifier::CROSSED_OUT), spans);
            }
            RenderInline::Highlight { children, .. } => {
                push_inlines(children, style.add_modifier(Modifier::REVERSED), spans);
            }
            RenderInline::Link { children, .. } | RenderInline::WikiReference { children, .. } => {
                push_inlines(children, style.add_modifier(Modifier::UNDERLINED), spans);
            }
            RenderInline::Image { destination, .. } => spans.push(Span::styled(
                crate::media::image_placeholder(destination),
                style.fg(Color::DarkGray),
            )),
            RenderInline::Tag { name, .. } => {
                spans.push(Span::styled(format!("#{name}"), style.fg(Color::Cyan)));
            }
            RenderInline::Reminder { token, .. } => spans.push(Span::styled(token.clone(), style)),
            RenderInline::SoftBreak { .. } | RenderInline::HardBreak { .. } => {
                spans.push(Span::raw("\n"));
            }
        }
    }
}

fn inline_text(inlines: &[RenderInline]) -> String {
    let mut spans = Vec::new();
    push_inlines(inlines, Style::default(), &mut spans);
    spans
        .into_iter()
        .map(|span| span.content.into_owned())
        .collect()
}
