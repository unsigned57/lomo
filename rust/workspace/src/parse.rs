//! One-pass workspace document parse over constrained source bytes.
//!
//! Segmentation uses byte-offset line tables (not `String.lines()` as write-back authority).
//! CommonMark/GFM structure and Lomo extensions are projected once via the shared
//! [`crate::render::render_markdown`] pipeline; memo storage tags/attachments use that same
//! node-fact authority (not a second body tokenizer or duplicate tag scanner).

use std::collections::HashMap;

use lomo_core::LomoError;

use crate::document::{
    DocumentBuild, DocumentFormat, MemoBuild, WorkspaceDocument, WorkspaceMemo, memo_from_build,
};
use crate::header::{parse_memo_header_line, validate_filename_stem};
use crate::limits::validation;
use crate::reminder::ReminderRef;
use crate::render::{
    RenderBlock, RenderDocumentV1, RenderInline, SemanticFactKind, render_markdown_core,
};
use crate::source::{ByteSpan, SourceBytes};
use crate::types::MemoIdentity;

const PLAIN_MARKDOWN_FALLBACK_TIME: &str = "00:00:00";

/// Projects body text for one raw memo block (header+body bytes) without Kotlin line authority.
///
/// Uses the owner document parse: Lomo/Thino headers yield the memo content field; plain Markdown
/// falls back to the whole trimmed source. Multiple memos in one block fail closed.
///
/// # Errors
///
/// Returns validation when the source is empty, non-UTF-8, or not a unique single-memo projection.
pub fn extract_memo_body_from_raw(raw: &str) -> Result<String, LomoError> {
    let source = SourceBytes::try_from_str(raw)?;
    // Synthetic stem: body extraction does not depend on identity product rules beyond uniqueness.
    let document = parse_workspace_document(&source, "body_extract")?;
    match document.memos() {
        [memo] => Ok(memo.content().to_owned()),
        [] => Err(validation(
            "memo_body_extract_empty",
            "raw memo source does not project a memo body",
        )),
        _ => Err(validation(
            "memo_body_extract_not_unique",
            "raw memo source projects multiple memos; refuse line-based split",
        )),
    }
}

/// Parses a workspace Markdown document from validated source bytes.
///
/// # Errors
///
/// Returns validation errors for illegal filename stems or span construction failures, and
/// resource-limit errors when the shared render projection exceeds budgets.
pub fn parse_workspace_document(
    source: &SourceBytes,
    filename_stem: &str,
) -> Result<WorkspaceDocument, LomoError> {
    validate_filename_stem(filename_stem)?;
    // Single semantic body projection for the whole source. Workspace render IR is this value —
    // callers must not re-tokenize the same body for RenderDocumentV1.
    let render = render_markdown_core(source)?;
    let gfm = count_gfm_facts(&render);

    let lines = build_line_table(source.as_str());
    let mut memos = Vec::new();
    let mut timestamp_counts: HashMap<String, u32> = HashMap::new();
    let mut saw_header = false;
    let mut current: Option<OpenMemo> = None;
    let mut plain_fallback = String::new();

    for (line_index, line) in lines.iter().enumerate() {
        let line_text = line_content(source.as_str(), line);
        if let Some(header) = parse_memo_header_line(line_text) {
            if let Some(open) = current.take() {
                memos.push(finish_open_memo(
                    source,
                    filename_stem,
                    open,
                    &lines,
                    &mut timestamp_counts,
                )?);
            }
            saw_header = true;
            current = Some(OpenMemo {
                time_part: header.time_part().to_owned(),
                start_line: line_index,
                end_line: line_index,
                content: header.content_part().to_owned(),
                raw: line_text.to_owned(),
            });
        } else if let Some(open) = current.as_mut() {
            if !open.content.is_empty() {
                open.content.push('\n');
            }
            open.content.push_str(line_text);
            open.raw.push('\n');
            open.raw.push_str(line_text);
            open.end_line = line_index;
        } else if !saw_header {
            if !plain_fallback.is_empty() {
                plain_fallback.push('\n');
            }
            plain_fallback.push_str(line_text);
        }
    }

    if let Some(open) = current.take() {
        memos.push(finish_open_memo(
            source,
            filename_stem,
            open,
            &lines,
            &mut timestamp_counts,
        )?);
    }

    let format = if !memos.is_empty() {
        DocumentFormat::LomoThino
    } else if let Some(memo) = build_plain_fallback(source, filename_stem, &plain_fallback, &lines)?
    {
        memos.push(memo);
        DocumentFormat::PlainMarkdown
    } else {
        DocumentFormat::Empty
    };
    project_document_facts(source, &render, &mut memos)?;

    Ok(WorkspaceDocument::from_build(DocumentBuild {
        source: source.clone(),
        format,
        memos,
        render,
        offset_events: gfm.offset,
        heading_events: gfm.heading,
        image_events: gfm.image,
        link_events: gfm.link,
    }))
}

struct OpenMemo {
    time_part: String,
    start_line: usize,
    end_line: usize,
    content: String,
    raw: String,
}

struct GfmEventFacts {
    offset: u32,
    heading: u32,
    image: u32,
    link: u32,
}

/// Logical line with absolute byte offsets into the source string.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct SourceLine {
    /// Inclusive start of line content (first content byte).
    content_start: usize,
    /// Exclusive end of line content (before terminator).
    content_end: usize,
    /// Exclusive end of line including terminator bytes (or `content_end` at EOF).
    term_end: usize,
}

fn build_line_table(text: &str) -> Vec<SourceLine> {
    // Match Kotlin `CharSequence.lines()`: split on \r\n / \n / \r and keep a trailing empty line
    // when the source ends with a line terminator (including the empty-string → one empty line case).
    let bytes = text.as_bytes();
    let mut lines = Vec::new();
    let mut index = 0usize;
    let mut line_start = 0usize;
    while index < bytes.len() {
        match bytes.get(index).copied() {
            Some(b'\n') => {
                lines.push(SourceLine {
                    content_start: line_start,
                    content_end: index,
                    term_end: index + 1,
                });
                index += 1;
                line_start = index;
            }
            Some(b'\r') => {
                let term_end = if bytes.get(index + 1) == Some(&b'\n') {
                    index + 2
                } else {
                    index + 1
                };
                lines.push(SourceLine {
                    content_start: line_start,
                    content_end: index,
                    term_end,
                });
                index = term_end;
                line_start = index;
            }
            _ => index += 1,
        }
    }
    // Final line: always present for empty source; present for content without trailing newline;
    // present as empty line when source ended on a terminator (line_start == len).
    lines.push(SourceLine {
        content_start: line_start,
        content_end: bytes.len(),
        term_end: bytes.len(),
    });
    lines
}

fn line_content<'a>(text: &'a str, line: &SourceLine) -> &'a str {
    text.get(line.content_start..line.content_end).unwrap_or("")
}

fn finish_open_memo(
    source: &SourceBytes,
    filename_stem: &str,
    open: OpenMemo,
    lines: &[SourceLine],
    timestamp_counts: &mut HashMap<String, u32>,
) -> Result<WorkspaceMemo, LomoError> {
    let full_content = open.content.trim().to_owned();
    let ordinal = timestamp_counts.get(&open.time_part).copied().unwrap_or(0);
    timestamp_counts.insert(open.time_part.clone(), ordinal.saturating_add(1));
    let identity = MemoIdentity::try_new(filename_stem, &open.time_part, ordinal)?;
    let (memo_span, header_span, body_span) =
        memo_byte_spans(source.len(), lines, open.start_line, open.end_line)?;
    Ok(memo_from_build(MemoBuild {
        identity,
        time_part: open.time_part,
        content: full_content,
        tags: Vec::new(),
        attachments: Vec::new(),
        reminders: Vec::new(),
        memo_span,
        header_span,
        body_span,
        start_line: u32::try_from(open.start_line).unwrap_or(u32::MAX),
        end_line: u32::try_from(open.end_line).unwrap_or(u32::MAX),
    }))
}

fn build_plain_fallback(
    source: &SourceBytes,
    filename_stem: &str,
    plain_fallback: &str,
    lines: &[SourceLine],
) -> Result<Option<WorkspaceMemo>, LomoError> {
    let normalized = plain_fallback.trim();
    if normalized.is_empty() {
        return Ok(None);
    }
    let identity = MemoIdentity::try_new(filename_stem, PLAIN_MARKDOWN_FALLBACK_TIME, 0)?;
    // Plain fallback is the whole source body — reuse the owned document IR projections.
    let end_line = lines.len().saturating_sub(1);
    let memo_span = ByteSpan::try_new(0, source.len(), source.len())?;
    let empty = ByteSpan::try_new(0, 0, source.len())?;
    Ok(Some(memo_from_build(MemoBuild {
        identity,
        time_part: PLAIN_MARKDOWN_FALLBACK_TIME.to_owned(),
        content: normalized.to_owned(),
        tags: Vec::new(),
        attachments: Vec::new(),
        reminders: Vec::new(),
        memo_span,
        header_span: empty,
        body_span: memo_span,
        start_line: 0,
        end_line: u32::try_from(end_line).unwrap_or(u32::MAX),
    })))
}

fn project_document_facts(
    source: &SourceBytes,
    render: &RenderDocumentV1,
    memos: &mut [WorkspaceMemo],
) -> Result<(), LomoError> {
    for memo in memos {
        let mut tags = Vec::new();
        let mut attachments = Vec::new();
        let mut reminders = Vec::new();
        let mut has_todo = false;
        let mut has_url = false;
        for fact in render.semantic_facts() {
            if !span_contains(memo.memo_span(), fact.source_span()) {
                continue;
            }
            match fact.kind() {
                SemanticFactKind::Tag => push_unique(&mut tags, fact.value()),
                SemanticFactKind::Attachment => push_unique(&mut attachments, fact.value()),
                SemanticFactKind::Reminder => reminders.push(ReminderRef::from_source_fact(
                    source,
                    memo.identity(),
                    fact.source_span(),
                    fact.value(),
                )?),
                SemanticFactKind::TaskItem => has_todo = true,
                SemanticFactKind::Link => {
                    if is_external_url(fact.value()) {
                        has_url = true;
                    }
                }
                SemanticFactKind::WikiReference => {}
            }
        }
        memo.replace_semantic_projections(tags, attachments, reminders, has_todo, has_url);
    }
    Ok(())
}

fn is_external_url(destination: &str) -> bool {
    let lower = destination.as_bytes();
    starts_with_ignore_ascii_case(lower, b"http://")
        || starts_with_ignore_ascii_case(lower, b"https://")
        || starts_with_ignore_ascii_case(lower, b"mailto:")
        || starts_with_ignore_ascii_case(lower, b"geo:")
}

fn starts_with_ignore_ascii_case(haystack: &[u8], prefix: &[u8]) -> bool {
    haystack
        .get(..prefix.len())
        .is_some_and(|head| head.eq_ignore_ascii_case(prefix))
}

const fn span_contains(container: ByteSpan, candidate: ByteSpan) -> bool {
    candidate.start() >= container.start() && candidate.end() <= container.end()
}

fn push_unique(values: &mut Vec<String>, value: &str) {
    if !values.iter().any(|existing| existing == value) {
        values.push(value.to_owned());
    }
}

fn memo_byte_spans(
    source_len: usize,
    lines: &[SourceLine],
    start_line: usize,
    end_line: usize,
) -> Result<(ByteSpan, ByteSpan, ByteSpan), LomoError> {
    if lines.is_empty()
        || start_line >= lines.len()
        || end_line >= lines.len()
        || end_line < start_line
    {
        let empty = ByteSpan::try_new(0, 0, source_len)?;
        return Ok((empty, empty, empty));
    }
    let start = lines.get(start_line).ok_or_else(|| {
        validation(
            "memo_line_index_out_of_range",
            "memo start line is outside the source line table",
        )
    })?;
    let memo_start = start.content_start;
    let memo_end = if end_line + 1 < lines.len() {
        lines
            .get(end_line + 1)
            .map_or(source_len, |line| line.content_start)
    } else {
        source_len
    };
    let memo_span = ByteSpan::try_new(memo_start, memo_end, source_len)?;
    let header_end = if start_line + 1 < lines.len() {
        lines
            .get(start_line + 1)
            .map_or(start.term_end, |line| line.content_start)
    } else {
        start.term_end
    };
    let header_end = header_end.min(memo_end);
    let header_span = ByteSpan::try_new(memo_start, header_end, source_len)?;
    let body_span = ByteSpan::try_new(header_end, memo_end, source_len)?;
    Ok((memo_span, header_span, body_span))
}

/// GFM structural facts projected from the owned render IR (same node stream, not a second pass).
fn count_gfm_facts(render: &RenderDocumentV1) -> GfmEventFacts {
    let mut heading = 0u32;
    let mut image = 0u32;
    let mut link = 0u32;
    count_blocks(render.blocks(), &mut heading, &mut image, &mut link);
    GfmEventFacts {
        // Node count is the IR-side event budget from the single projection.
        offset: render.node_count().max(1),
        heading,
        image,
        link,
    }
}

fn count_blocks(blocks: &[RenderBlock], heading: &mut u32, image: &mut u32, link: &mut u32) {
    for block in blocks {
        match block {
            RenderBlock::Heading { inlines, .. } => {
                *heading = heading.saturating_add(1);
                count_inlines(inlines, image, link);
            }
            RenderBlock::Paragraph { inlines, .. } => count_inlines(inlines, image, link),
            RenderBlock::BlockQuote { blocks, .. } => count_blocks(blocks, heading, image, link),
            RenderBlock::List { items, .. } => {
                for item in items {
                    count_blocks(&item.blocks, heading, image, link);
                }
            }
            RenderBlock::Table { header, rows, .. } => {
                for cell in header.iter().chain(rows.iter().flatten()) {
                    count_inlines(&cell.inlines, image, link);
                }
            }
            RenderBlock::CodeBlock { .. }
            | RenderBlock::ThematicBreak { .. }
            | RenderBlock::HtmlBlock { .. } => {}
        }
    }
}

fn count_inlines(inlines: &[RenderInline], image: &mut u32, link: &mut u32) {
    for inline in inlines {
        match inline {
            RenderInline::Image { .. } => *image = image.saturating_add(1),
            RenderInline::Link { children, .. } => {
                *link = link.saturating_add(1);
                count_inlines(children, image, link);
            }
            RenderInline::Strong { children, .. }
            | RenderInline::Emphasis { children, .. }
            | RenderInline::Strikethrough { children, .. }
            | RenderInline::Highlight { children, .. }
            | RenderInline::WikiReference { children, .. } => {
                count_inlines(children, image, link);
            }
            RenderInline::Text { .. }
            | RenderInline::Code { .. }
            | RenderInline::Tag { .. }
            | RenderInline::Reminder { .. }
            | RenderInline::SoftBreak { .. }
            | RenderInline::HardBreak { .. }
            | RenderInline::HtmlInline { .. } => {}
        }
    }
}
