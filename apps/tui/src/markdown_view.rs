//! Terminal presentation of the workspace's canonical Markdown render tree.
use lomo_workspace::{RenderBlock, RenderDocumentV1, RenderInline, RenderListItem};
use ratatui::{
    style::{Color, Modifier, Style},
    text::{Line, Span},
};

/// Whether `#tag` inlines are drawn inside the body text.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TagDisplay {
    Inline,
    /// Feed cards list tags in their footer, so the body omits them.
    Hidden,
}

/// Semantic position of one `RenderInline::Image`: the styled line its
/// placeholder occupies and the link destination it refers to.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ImageSite {
    pub line: usize,
    pub destination: String,
}

#[must_use]
pub fn styled_document(document: &RenderDocumentV1, tags: TagDisplay) -> Vec<Line<'static>> {
    styled_document_sites(document, tags).0
}

/// Styled lines plus the semantic site of every image inline, in document order.
#[must_use]
pub fn styled_document_sites(
    document: &RenderDocumentV1,
    tags: TagDisplay,
) -> (Vec<Line<'static>>, Vec<ImageSite>) {
    let mut lines = Vec::new();
    let mut sites = Vec::new();
    push_blocks(document.blocks(), tags, &mut lines, &mut sites);
    if tags == TagDisplay::Hidden {
        while lines
            .last()
            .is_some_and(|line| line.spans.iter().all(|span| span.content.trim().is_empty()))
        {
            lines.pop();
        }
        sites.retain(|site| site.line < lines.len());
    }
    (lines, sites)
}

fn push_blocks(
    blocks: &[RenderBlock],
    tags: TagDisplay,
    lines: &mut Vec<Line<'static>>,
    sites: &mut Vec<ImageSite>,
) {
    for (index, block) in blocks.iter().enumerate() {
        if index > 0 {
            lines.push(Line::default());
        }
        push_block(block, tags, lines, sites);
    }
}

fn push_block(
    block: &RenderBlock,
    tags: TagDisplay,
    lines: &mut Vec<Line<'static>>,
    sites: &mut Vec<ImageSite>,
) {
    match block {
        RenderBlock::Paragraph { inlines, .. } => {
            push_inline_lines(inlines, Style::default(), tags, lines, sites);
        }
        RenderBlock::Heading { inlines, level, .. } => {
            let start = lines.len();
            let style = Style::default().add_modifier(Modifier::BOLD);
            push_inline_lines(inlines, style, tags, lines, sites);
            if let Some(line) = lines.get_mut(start) {
                line.spans.insert(
                    0,
                    Span::styled(format!("{} ", "#".repeat(usize::from(*level))), style),
                );
            }
        }
        RenderBlock::BlockQuote { blocks, .. } => {
            let base = sites.len();
            let mut quoted = Vec::new();
            let mut quoted_sites = Vec::new();
            push_blocks(blocks, tags, &mut quoted, &mut quoted_sites);
            for (index, line) in quoted.iter().enumerate() {
                let mut spans = vec![Span::styled("│ ", Style::default().fg(Color::DarkGray))];
                spans.extend(line.spans.clone());
                lines.push(Line::from(spans));
                for site in &quoted_sites {
                    if site.line == index {
                        sites.push(ImageSite {
                            line: lines.len() - 1,
                            destination: site.destination.clone(),
                        });
                    }
                }
            }
            debug_assert_eq!(sites.len() - base, quoted_sites.len());
        }
        RenderBlock::List { items, ordered, .. } => {
            for (index, item) in items.iter().enumerate() {
                push_list_item(item, *ordered, index, tags, lines, sites);
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
    tags: TagDisplay,
    lines: &mut Vec<Line<'static>>,
    sites: &mut Vec<ImageSite>,
) {
    let marker = match item.checked {
        Some(true) => "✓ ".to_owned(),
        Some(false) => "□ ".to_owned(),
        None if ordered => format!("{}. ", index + 1),
        None => "• ".to_owned(),
    };
    let mut nested = Vec::new();
    let mut nested_sites = Vec::new();
    push_blocks(&item.blocks, tags, &mut nested, &mut nested_sites);
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
        for site in &nested_sites {
            if site.line == index {
                sites.push(ImageSite {
                    line: lines.len() - 1,
                    destination: site.destination.clone(),
                });
            }
        }
    }
}

/// Inline content before line splitting: text spans plus image markers that
/// carry their destination so the emitted line index can be recorded.
enum Seg {
    Span(Span<'static>),
    Image {
        destination: String,
        placeholder: Span<'static>,
    },
}

fn push_inline_lines(
    inlines: &[RenderInline],
    style: Style,
    tags: TagDisplay,
    lines: &mut Vec<Line<'static>>,
    sites: &mut Vec<ImageSite>,
) {
    let mut segments = Vec::new();
    push_inlines(inlines, style, tags, &mut segments);
    let mut current = Vec::new();
    let mut row = lines.len();
    for segment in segments {
        match segment {
            Seg::Span(span) => {
                for (index, part) in span.content.split('\n').enumerate() {
                    if index > 0 {
                        lines.push(Line::from(std::mem::take(&mut current)));
                        row += 1;
                    }
                    current.push(Span::styled(part.to_owned(), span.style));
                }
            }
            Seg::Image {
                destination,
                placeholder,
            } => {
                sites.push(ImageSite {
                    line: row,
                    destination,
                });
                current.push(placeholder);
            }
        }
    }
    lines.push(Line::from(current));
}

fn push_inlines(inlines: &[RenderInline], style: Style, tags: TagDisplay, out: &mut Vec<Seg>) {
    for inline in inlines {
        match inline {
            RenderInline::Text { text, .. }
            | RenderInline::Code { text, .. }
            | RenderInline::HtmlInline { text, .. } => {
                out.push(Seg::Span(Span::styled(text.clone(), style)));
            }
            RenderInline::Strong { children, .. } => {
                push_inlines(children, style.add_modifier(Modifier::BOLD), tags, out);
            }
            RenderInline::Emphasis { children, .. } => {
                push_inlines(children, style.add_modifier(Modifier::ITALIC), tags, out);
            }
            RenderInline::Strikethrough { children, .. } => {
                push_inlines(
                    children,
                    style.add_modifier(Modifier::CROSSED_OUT),
                    tags,
                    out,
                );
            }
            RenderInline::Highlight { children, .. } => {
                push_inlines(children, style.add_modifier(Modifier::REVERSED), tags, out);
            }
            RenderInline::Link { children, .. } | RenderInline::WikiReference { children, .. } => {
                push_inlines(
                    children,
                    style.add_modifier(Modifier::UNDERLINED),
                    tags,
                    out,
                );
            }
            RenderInline::Image { destination, .. } => out.push(Seg::Image {
                destination: destination.clone(),
                placeholder: Span::styled(
                    crate::media::image_placeholder(destination),
                    style.fg(Color::DarkGray),
                ),
            }),
            RenderInline::Tag { name, .. } => {
                if tags == TagDisplay::Inline {
                    out.push(Seg::Span(Span::styled(
                        format!("#{name}"),
                        style.fg(Color::Cyan),
                    )));
                }
            }
            RenderInline::Reminder { token, .. } => {
                out.push(Seg::Span(Span::styled(token.clone(), style)));
            }
            RenderInline::SoftBreak { .. } | RenderInline::HardBreak { .. } => {
                out.push(Seg::Span(Span::raw("\n")));
            }
        }
    }
}

fn inline_text(inlines: &[RenderInline]) -> String {
    let mut segments = Vec::new();
    push_inlines(inlines, Style::default(), TagDisplay::Inline, &mut segments);
    segments
        .into_iter()
        .map(|segment| match segment {
            Seg::Span(span)
            | Seg::Image {
                placeholder: span, ..
            } => span.content.into_owned(),
        })
        .collect()
}
