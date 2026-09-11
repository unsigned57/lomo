use lomo_workspace::{RenderBlock, RenderInline, SourceBytes, render_markdown};

use crate::media::{GraphicsProtocol, MediaKind, media_kind_for_path, preview_media_line};

/// Turns memo Markdown into preview text, replacing images when no graphics protocol exists.
#[must_use]
pub fn preview_body(body: &str, graphics: GraphicsProtocol) -> String {
    let Ok(source) = SourceBytes::try_from_str(body) else {
        return body.to_owned();
    };
    let Ok(document) = render_markdown(&source) else {
        return body.to_owned();
    };
    let mut lines = Vec::new();
    for destination in document.attachment_destinations() {
        let kind = media_kind_for_path(destination);
        lines.push(preview_media_line(destination, kind, graphics));
    }
    if lines.is_empty() {
        flatten_blocks(document.blocks())
    } else {
        let mut text = flatten_blocks(document.blocks());
        text.push('\n');
        text.push_str(&lines.join("\n"));
        text
    }
}

fn flatten_blocks(blocks: &[RenderBlock]) -> String {
    let mut out = String::new();
    for block in blocks {
        if !out.is_empty() {
            out.push('\n');
        }
        flatten_block(block, &mut out);
    }
    if out.is_empty() { String::new() } else { out }
}

fn flatten_block(block: &RenderBlock, out: &mut String) {
    match block {
        RenderBlock::Paragraph { inlines, .. } | RenderBlock::Heading { inlines, .. } => {
            flatten_inlines(inlines, out);
        }
        RenderBlock::BlockQuote { blocks, .. } => {
            for child in blocks {
                flatten_block(child, out);
            }
        }
        RenderBlock::List { items, .. } => {
            for item in items {
                for child in &item.blocks {
                    flatten_block(child, out);
                }
            }
        }
        RenderBlock::CodeBlock { literal, .. } | RenderBlock::HtmlBlock { literal, .. } => {
            out.push_str(literal);
        }
        RenderBlock::ThematicBreak { .. } => out.push_str("---"),
        RenderBlock::Table { header, rows, .. } => {
            for cell in header {
                flatten_inlines(&cell.inlines, out);
            }
            for row in rows {
                for cell in row {
                    flatten_inlines(&cell.inlines, out);
                }
            }
        }
    }
}

fn flatten_inlines(inlines: &[RenderInline], out: &mut String) {
    for inline in inlines {
        match inline {
            RenderInline::Text { text, .. }
            | RenderInline::Code { text, .. }
            | RenderInline::HtmlInline { text, .. } => out.push_str(text),
            RenderInline::Strong { children, .. }
            | RenderInline::Emphasis { children, .. }
            | RenderInline::Strikethrough { children, .. }
            | RenderInline::Highlight { children, .. }
            | RenderInline::Link { children, .. }
            | RenderInline::WikiReference { children, .. } => flatten_inlines(children, out),
            RenderInline::Image { destination, .. } => {
                out.push_str(&preview_media_line(
                    destination,
                    MediaKind::Image,
                    GraphicsProtocol::None,
                ));
            }
            RenderInline::Tag { name, .. } => {
                out.push('#');
                out.push_str(name);
            }
            RenderInline::Reminder { token, .. } => out.push_str(token),
            RenderInline::SoftBreak { .. } | RenderInline::HardBreak { .. } => out.push('\n'),
        }
    }
}
