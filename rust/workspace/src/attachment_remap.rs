//! Owner-side free-content attachment destination remap.
//!
//! Same-parse IR supplies required destination occurrences. Rewrite spans are resolved only from
//! each IR node's verified source span (image/link paren destinations, wiki targets) or from
//! reference-definition lines when a reference-style IR node has no inline destination. Free-buffer
//! `](` / prose scanners are not authority. Kotlin supplies only opaque original→stored name
//! mappings and applies planned bytes.

use std::collections::{BTreeMap, BTreeSet};

use lomo_core::LomoError;

use crate::limits::validation;
use crate::render::{RenderBlock, RenderInline, render_markdown};
use crate::source::{ByteSpan, SourceBytes};

/// Plans a free-content attachment destination remap from one owner parse.
///
/// # Errors
///
/// - validation when a mapped IR destination occurrence has no verifiable destination span
///   (fail closed — never best-effort partial rewrite that leaves stale destinations).
/// - render/resource errors from the shared Markdown owner pipeline.
pub fn remap_attachment_destinations(
    content: &str,
    mappings: &BTreeMap<String, String>,
) -> Result<String, LomoError> {
    if mappings.is_empty() {
        return Ok(content.to_owned());
    }
    let lookup = AttachmentMappingLookup::try_from_mappings(mappings)?;
    let source = SourceBytes::try_from_str(content)?;
    let render = render_markdown(&source)?;

    let mut occurrences = Vec::new();
    collect_required_occurrences(render.blocks(), &lookup, &mut occurrences);
    let code_spans = collect_code_spans(render.blocks());

    let mut rewrites = Vec::new();
    let mut required_destinations: BTreeSet<String> = BTreeSet::new();

    for occurrence in &occurrences {
        if lookup.remap(&occurrence.destination).is_some() {
            required_destinations.insert(occurrence.destination.clone());
        }
        if let Some((start, end, stored)) =
            mapped_destination_rewrite_in_node(content, occurrence, &lookup)
        {
            rewrites.push((start, end, stored));
        }
    }

    // Product destinations that CommonMark does not project as Link/Image (e.g. unbracketed
    // space-bearing paths) still use full `!?[label](dest)` openers — never bare `](dest)` prose.
    collect_full_link_destination_rewrites(content, &lookup, &code_spans, &mut rewrites);

    // Reference definitions host destinations for reference-style IR images/links.
    collect_reference_definition_rewrites(content, &lookup, &code_spans, &mut rewrites);

    // Fail closed per required IR destination string: at least one verified rewrite must cover it.
    for required in &required_destinations {
        let Some(stored) = lookup.remap(required) else {
            continue;
        };
        let covered = rewrites.iter().any(|(start, end, replacement)| {
            if replacement != stored {
                return false;
            }
            content.get(*start..*end).is_some_and(|slice| {
                slice == required.as_str() || lookup.remap(slice) == Some(stored)
            })
        });
        if !covered {
            return Err(validation(
                "attachment_destination_span_unverified",
                "mapped attachment destination has no verified source span",
            ));
        }
    }

    // Per-occurrence: every IR node whose destination maps must have its own span rewritten
    // (or a ref-def rewrite covering that destination for reference-style nodes).
    for occurrence in &occurrences {
        let Some(stored) = lookup.remap(&occurrence.destination) else {
            continue;
        };
        let in_node = mapped_destination_rewrite_in_node(content, occurrence, &lookup);
        if in_node.is_some() {
            // Already added above; span presence is enough for this occurrence.
            continue;
        }
        // Reference-style (or non-inline): covered iff some rewrite targets this destination.
        let covered = rewrites.iter().any(|(start, end, replacement)| {
            if replacement != stored {
                return false;
            }
            content.get(*start..*end).is_some_and(|slice| {
                slice == occurrence.destination.as_str() || lookup.remap(slice) == Some(stored)
            })
        });
        if !covered {
            return Err(validation(
                "attachment_destination_span_unverified",
                "mapped attachment destination occurrence has no verified source span",
            ));
        }
    }

    // Sort + apply. Drop exact duplicate spans.
    rewrites.sort_by(|left, right| right.0.cmp(&left.0).then(right.1.cmp(&left.1)));
    rewrites.dedup_by(|left, right| left.0 == right.0 && left.1 == right.1);
    for window in rewrites.windows(2) {
        let [later, earlier] = window else {
            continue;
        };
        if later.0 < earlier.1 && earlier.0 < later.1 {
            return Err(validation(
                "attachment_destination_span_overlap",
                "mapped attachment destination spans overlap",
            ));
        }
    }
    apply_rewrites(content, rewrites)
}

struct AttachmentMappingLookup {
    exact: BTreeMap<String, String>,
    unique_basenames: BTreeMap<String, String>,
}

impl AttachmentMappingLookup {
    fn try_from_mappings(mappings: &BTreeMap<String, String>) -> Result<Self, LomoError> {
        let mut exact = BTreeMap::new();
        let mut basename_values: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
        for (original, stored) in mappings {
            let Some(normalized) = normalize_attachment_reference(original) else {
                return Err(validation(
                    "invalid_attachment_mapping",
                    "attachment mapping original name is empty",
                ));
            };
            if stored.is_empty() {
                return Err(validation(
                    "invalid_attachment_mapping",
                    "attachment mapping stored name is empty",
                ));
            }
            exact.insert(normalized.clone(), stored.clone());
            let basename = normalized
                .rsplit('/')
                .next()
                .unwrap_or(normalized.as_str())
                .to_owned();
            basename_values
                .entry(basename)
                .or_default()
                .insert(stored.clone());
        }
        let mut unique_basenames = BTreeMap::new();
        for (basename, stored_names) in basename_values {
            let mut names = stored_names.into_iter();
            if let (Some(only), None) = (names.next(), names.next()) {
                unique_basenames.insert(basename, only);
            }
        }
        Ok(Self {
            exact,
            unique_basenames,
        })
    }

    fn remap<'a>(&'a self, target: &str) -> Option<&'a str> {
        if is_external_target(target) {
            return None;
        }
        let normalized = normalize_attachment_reference(target)?;
        self.exact.get(&normalized).map(String::as_str).or_else(|| {
            let basename = normalized.rsplit('/').next().unwrap_or(normalized.as_str());
            self.unique_basenames.get(basename).map(String::as_str)
        })
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum OccurrenceKind {
    /// Markdown image/link (inline paren or reference-style).
    ImageOrLink,
    /// Plain wiki `[[target]]` (or classified wiki image when projected as Image with wiki form).
    Wiki,
}

struct RequiredOccurrence {
    destination: String,
    source_span: ByteSpan,
    kind: OccurrenceKind,
}

fn normalize_attachment_reference(reference: &str) -> Option<String> {
    let normalized = reference.trim().replace('\\', "/");
    // Keep trimming repeated `./` prefixes without clone churn.
    let mut slice = normalized.as_str();
    while let Some(rest) = slice.strip_prefix("./") {
        slice = rest;
    }
    if slice.is_empty() {
        None
    } else {
        Some(slice.to_owned())
    }
}

fn is_external_target(target: &str) -> bool {
    let trimmed = target.trim();
    if trimmed.starts_with('#') || trimmed.starts_with("//") {
        return true;
    }
    // scheme:
    let bytes = trimmed.as_bytes();
    if bytes.is_empty() || !bytes.first().is_some_and(u8::is_ascii_alphabetic) {
        return false;
    }
    let mut index = 1usize;
    while index < bytes.len()
        && (bytes.get(index).is_some_and(u8::is_ascii_alphanumeric)
            || matches!(bytes.get(index).copied(), Some(b'+' | b'-' | b'.')))
    {
        index += 1;
    }
    bytes.get(index) == Some(&b':')
}

fn collect_required_occurrences(
    blocks: &[RenderBlock],
    lookup: &AttachmentMappingLookup,
    out: &mut Vec<RequiredOccurrence>,
) {
    for block in blocks {
        match block {
            RenderBlock::Paragraph { inlines, .. } | RenderBlock::Heading { inlines, .. } => {
                collect_required_inlines(inlines, lookup, out);
            }
            RenderBlock::BlockQuote { blocks, .. } => {
                collect_required_occurrences(blocks, lookup, out);
            }
            RenderBlock::List { items, .. } => {
                for item in items {
                    collect_required_occurrences(&item.blocks, lookup, out);
                }
            }
            RenderBlock::Table { header, rows, .. } => {
                for cell in header {
                    collect_required_inlines(&cell.inlines, lookup, out);
                }
                for row in rows {
                    for cell in row {
                        collect_required_inlines(&cell.inlines, lookup, out);
                    }
                }
            }
            RenderBlock::CodeBlock { .. }
            | RenderBlock::ThematicBreak { .. }
            | RenderBlock::HtmlBlock { .. } => {}
        }
    }
}

fn collect_required_inlines(
    inlines: &[RenderInline],
    lookup: &AttachmentMappingLookup,
    out: &mut Vec<RequiredOccurrence>,
) {
    for inline in inlines {
        match inline {
            RenderInline::Image {
                destination,
                source_span,
                ..
            } => {
                // Always visit image nodes: space-bearing destinations may map by basename even
                // when the IR destination token is CommonMark-truncated.
                out.push(RequiredOccurrence {
                    destination: destination.clone(),
                    source_span: *source_span,
                    kind: OccurrenceKind::ImageOrLink,
                });
            }
            RenderInline::Link {
                destination,
                source_span,
                children,
                ..
            } => {
                out.push(RequiredOccurrence {
                    destination: destination.clone(),
                    source_span: *source_span,
                    kind: OccurrenceKind::ImageOrLink,
                });
                collect_required_inlines(children, lookup, out);
            }
            RenderInline::WikiReference {
                target,
                source_span,
                children,
                ..
            } => {
                if lookup.remap(target).is_some() {
                    out.push(RequiredOccurrence {
                        destination: target.clone(),
                        source_span: *source_span,
                        kind: OccurrenceKind::Wiki,
                    });
                }
                collect_required_inlines(children, lookup, out);
            }
            RenderInline::Strong { children, .. }
            | RenderInline::Emphasis { children, .. }
            | RenderInline::Strikethrough { children, .. }
            | RenderInline::Highlight { children, .. } => {
                collect_required_inlines(children, lookup, out);
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

/// Resolves a mapped destination rewrite strictly inside an IR node's source span.
///
/// Supports wiki image `![[…]]`, plain wiki `[[…]]`, and inline image/link `](…)` forms (including
/// space-bearing product destinations resolved via mapping lookup). Returns `None` for
/// reference-style nodes whose destination lives only in a definition line.
fn mapped_destination_rewrite_in_node(
    content: &str,
    occurrence: &RequiredOccurrence,
    lookup: &AttachmentMappingLookup,
) -> Option<(usize, usize, String)> {
    let node = content.get(occurrence.source_span.start()..occurrence.source_span.end())?;
    let node_start = occurrence.source_span.start();

    // Wiki forms (plain wiki nodes, or wiki-image projected as Image).
    if let Some((start, end, raw)) = wiki_target_in_node(node, node_start)
        && let Some(stored) = lookup.remap(&raw)
    {
        return Some((start, end, stored.to_owned()));
    }
    if occurrence.kind == OccurrenceKind::Wiki {
        return None;
    }
    inline_paren_mapped_destination(node, node_start, lookup)
}

fn wiki_target_in_node(node: &str, node_start: usize) -> Option<(usize, usize, String)> {
    let (prefix_len, inner) = node
        .strip_prefix("![[")
        .map(|rest| (3usize, rest))
        .or_else(|| node.strip_prefix("[[").map(|rest| (2usize, rest)))?;
    let close = inner.find("]]")?;
    let inner = inner.get(..close)?;
    let target_part = inner.split('|').next().unwrap_or(inner);
    let trimmed = target_part.trim();
    if trimmed.is_empty() {
        return None;
    }
    let trim_leading = target_part.len() - target_part.trim_start().len();
    let start = node_start + prefix_len + trim_leading;
    let end = start + trimmed.len();
    Some((start, end, trimmed.to_owned()))
}

fn inline_paren_mapped_destination(
    node: &str,
    node_start: usize,
    lookup: &AttachmentMappingLookup,
) -> Option<(usize, usize, String)> {
    let bytes = node.as_bytes();
    let mut index = 0usize;
    while index + 1 < bytes.len() {
        if bytes.get(index) == Some(&b']') && bytes.get(index + 1) == Some(&b'(') {
            let dest_local = index + 2;
            if let Some((start_local, end_local, stored)) =
                match_mapped_paren_destination_at(node, dest_local, lookup)
            {
                return Some((node_start + start_local, node_start + end_local, stored));
            }
            index = dest_local;
            continue;
        }
        index += 1;
    }
    None
}

/// Matches an inline paren destination at `start` against the attachment mapping.
///
/// Returns the node-local slice of the destination text to rewrite (angle-bracket interiors
/// exclude the brackets) plus the stored name.
fn match_mapped_paren_destination_at(
    node: &str,
    start: usize,
    lookup: &AttachmentMappingLookup,
) -> Option<(usize, usize, String)> {
    let bytes = node.as_bytes();
    if bytes.get(start) == Some(&b'<') {
        let after = node.get(start + 1..)?;
        let close = after.find('>')? + start + 1;
        let raw = node.get(start + 1..close)?;
        let stored = lookup.remap(raw)?;
        return Some((start + 1, close, stored.to_owned()));
    }
    // Prefer longest mapped bare destination ending before a valid title/close (space-bearing
    // product attachment filenames).
    let mut end = start;
    while end < bytes.len() && !matches!(bytes.get(end).copied(), Some(b'\n' | b'\r')) {
        end += 1;
    }
    let mut cursor = end;
    while cursor > start {
        if !bytes.get(cursor - 1).is_some_and(u8::is_ascii_whitespace) {
            let raw = node.get(start..cursor)?;
            if has_valid_optional_title(node, cursor)
                && let Some(stored) = lookup.remap(raw)
            {
                return Some((start, cursor, stored.to_owned()));
            }
        }
        cursor -= 1;
    }
    // CommonMark-ish balanced-paren bare destination without spaces.
    let mut index = start;
    let mut depth = 0i32;
    while index < bytes.len() {
        match bytes.get(index).copied() {
            Some(b'\\') => index = index.saturating_add(2),
            Some(b' ' | b'\t' | b'\n' | b'\r') => break,
            Some(b'(') => {
                depth += 1;
                index += 1;
            }
            Some(b')') => {
                if depth == 0 {
                    break;
                }
                depth -= 1;
                index += 1;
            }
            _ => index += 1,
        }
    }
    if index > start {
        let raw = node.get(start..index)?;
        if let Some(stored) = lookup.remap(raw) {
            return Some((start, index, stored.to_owned()));
        }
    }
    None
}

fn has_valid_optional_title(content: &str, dest_end: usize) -> bool {
    let after = content.get(dest_end..).unwrap_or("");
    if after.starts_with(')') {
        return true;
    }
    let trimmed = after.trim_start_matches([' ', '\t']);
    if trimmed.starts_with(')') {
        return true;
    }
    match trimmed.as_bytes().first().copied() {
        Some(b'"') => title_then_close(trimmed, '"'),
        Some(b'\'') => title_then_close(trimmed, '\''),
        _ => false,
    }
}

fn title_then_close(trimmed: &str, quote: char) -> bool {
    let rest = trimmed.get(1..).unwrap_or("");
    rest.find(quote).is_some_and(|close| {
        rest.get(close + 1..)
            .is_some_and(|after| after.trim_start_matches([' ', '\t']).starts_with(')'))
    })
}

/// Collects destinations from full Markdown image/link openers `![label](dest)` / `[label](dest)`.
///
/// This is intentionally stricter than a free-buffer `](` scan: bare prose/HTML `](name)` without a
/// preceding link/image label is never rewritten. It covers product space-bearing destinations that
/// `CommonMark` does not project as Link/Image nodes.
fn collect_full_link_destination_rewrites(
    content: &str,
    lookup: &AttachmentMappingLookup,
    code_spans: &[ByteSpan],
    rewrites: &mut Vec<(usize, usize, String)>,
) {
    let bytes = content.as_bytes();
    let mut index = 0usize;
    while index + 1 < bytes.len() {
        if bytes.get(index) == Some(&b']') && bytes.get(index + 1) == Some(&b'(') {
            if has_markdown_link_label_before(content, index) {
                let dest_start = index + 2;
                if let Some((start, end, stored)) =
                    match_mapped_paren_destination_at(content, dest_start, lookup)
                {
                    if !in_code_span(code_spans, start, end) {
                        rewrites.push((start, end, stored));
                    }
                    index = end;
                    continue;
                }
            }
            index += 2;
            continue;
        }
        index += 1;
    }
}

/// True when `](` at `close_bracket` closes a Markdown link/image label (`[` … `]`), optionally
/// preceded by `!` for images. Rejects bare prose `](…)` without a label opener.
fn has_markdown_link_label_before(content: &str, close_bracket: usize) -> bool {
    if close_bracket == 0 {
        return false;
    }
    let bytes = content.as_bytes();
    let mut depth = 0i32;
    let mut index = close_bracket;
    while index > 0 {
        index -= 1;
        match bytes.get(index).copied() {
            Some(b']') => depth += 1,
            Some(b'[') => {
                if depth == 0 {
                    // Optional image marker immediately before the opening `[`.
                    return true;
                }
                depth -= 1;
            }
            Some(b'\n' | b'\r') => {
                // Labels do not span line terminators in product attachment forms.
                return false;
            }
            _ => {}
        }
    }
    false
}

/// Collects reference-definition destination rewrites for destinations that map.
///
/// Only line-shaped `[label]: destination` definitions outside code/fence spans are considered —
/// never free `](` prose.
fn collect_reference_definition_rewrites(
    content: &str,
    lookup: &AttachmentMappingLookup,
    code_spans: &[ByteSpan],
    rewrites: &mut Vec<(usize, usize, String)>,
) {
    let mut line_start = 0usize;
    let bytes = content.as_bytes();
    while line_start <= bytes.len() {
        let line_end = content
            .get(line_start..)
            .and_then(|tail| tail.find(['\n', '\r']))
            .map_or(content.len(), |rel| line_start + rel);
        let Some(line) = content.get(line_start..line_end) else {
            break;
        };
        if let Some((dest_start, dest_end, raw)) =
            parse_reference_definition_destination(line, lookup)
        {
            let abs_start = line_start + dest_start;
            let abs_end = line_start + dest_end;
            if !in_code_span(code_spans, abs_start, abs_end)
                && let Some(stored) = lookup.remap(&raw)
            {
                rewrites.push((abs_start, abs_end, stored.to_owned()));
            }
        }
        if line_end >= content.len() {
            break;
        }
        let mut next = line_end;
        if content.as_bytes().get(next) == Some(&b'\r') {
            next += 1;
        }
        if content.as_bytes().get(next) == Some(&b'\n') {
            next += 1;
        } else if next == line_end {
            next = next.saturating_add(1);
        }
        line_start = next;
    }
}

fn collect_code_spans(blocks: &[RenderBlock]) -> Vec<ByteSpan> {
    let mut spans = Vec::new();
    collect_code_spans_blocks(blocks, &mut spans);
    spans
}

fn collect_code_spans_blocks(blocks: &[RenderBlock], spans: &mut Vec<ByteSpan>) {
    for block in blocks {
        match block {
            RenderBlock::CodeBlock { source_span, .. } => spans.push(*source_span),
            RenderBlock::Paragraph { inlines, .. } | RenderBlock::Heading { inlines, .. } => {
                collect_code_spans_inlines(inlines, spans);
            }
            RenderBlock::BlockQuote { blocks, .. } => collect_code_spans_blocks(blocks, spans),
            RenderBlock::List { items, .. } => {
                for item in items {
                    collect_code_spans_blocks(&item.blocks, spans);
                }
            }
            RenderBlock::Table { header, rows, .. } => {
                for cell in header {
                    collect_code_spans_inlines(&cell.inlines, spans);
                }
                for row in rows {
                    for cell in row {
                        collect_code_spans_inlines(&cell.inlines, spans);
                    }
                }
            }
            RenderBlock::ThematicBreak { .. } | RenderBlock::HtmlBlock { .. } => {}
        }
    }
}

fn collect_code_spans_inlines(inlines: &[RenderInline], spans: &mut Vec<ByteSpan>) {
    for inline in inlines {
        match inline {
            RenderInline::Code { source_span, .. } => spans.push(*source_span),
            RenderInline::Strong { children, .. }
            | RenderInline::Emphasis { children, .. }
            | RenderInline::Strikethrough { children, .. }
            | RenderInline::Highlight { children, .. }
            | RenderInline::Link { children, .. }
            | RenderInline::WikiReference { children, .. } => {
                collect_code_spans_inlines(children, spans);
            }
            RenderInline::Text { .. }
            | RenderInline::Image { .. }
            | RenderInline::Tag { .. }
            | RenderInline::Reminder { .. }
            | RenderInline::SoftBreak { .. }
            | RenderInline::HardBreak { .. }
            | RenderInline::HtmlInline { .. } => {}
        }
    }
}

fn in_code_span(code_spans: &[ByteSpan], start: usize, end: usize) -> bool {
    code_spans
        .iter()
        .any(|span| start >= span.start() && end <= span.end())
}

fn parse_reference_definition_destination(
    line: &str,
    lookup: &AttachmentMappingLookup,
) -> Option<(usize, usize, String)> {
    let indent = line
        .chars()
        .take_while(|ch| *ch == ' ' || *ch == '\t')
        .count();
    if indent > 3 {
        return None;
    }
    let after_indent = line.get(indent..)?;
    if !after_indent.starts_with('[') {
        return None;
    }
    let label_close = after_indent.find("]:")?;
    if label_close < 2 {
        return None;
    }
    let after_label = after_indent.get(label_close + 2..)?;
    let ws = after_label
        .chars()
        .take_while(|ch| *ch == ' ' || *ch == '\t')
        .count();
    let dest_local_start = indent + label_close + 2 + ws;
    if dest_local_start >= line.len() {
        return None;
    }
    if line.as_bytes().get(dest_local_start) == Some(&b'<') {
        let after = line.get(dest_local_start + 1..)?;
        let close = after.find('>')? + dest_local_start + 1;
        let raw = line.get(dest_local_start + 1..close)?.to_owned();
        return Some((dest_local_start + 1, close, raw));
    }
    // Prefer longest *mapped* destination ending before optional title/whitespace.
    let mut cursor = line.len();
    while cursor > dest_local_start {
        if !line
            .as_bytes()
            .get(cursor - 1)
            .is_some_and(u8::is_ascii_whitespace)
        {
            let raw = line.get(dest_local_start..cursor)?;
            let after = line.get(cursor..).unwrap_or("");
            let ok = after.is_empty()
                || after.chars().all(|ch| ch == ' ' || ch == '\t')
                || after.trim_start_matches([' ', '\t']).starts_with('"')
                || after.trim_start_matches([' ', '\t']).starts_with('\'');
            if ok && lookup.remap(raw).is_some() {
                return Some((dest_local_start, cursor, raw.to_owned()));
            }
        }
        cursor -= 1;
    }
    // Unmapped bare token (no spaces) — still report so callers can ignore via remap.
    let mut end = dest_local_start;
    let bytes = line.as_bytes();
    while end < bytes.len()
        && !matches!(bytes.get(end).copied(), Some(b' ' | b'\t' | b'\n' | b'\r'))
    {
        end += 1;
    }
    if end == dest_local_start {
        return None;
    }
    Some((
        dest_local_start,
        end,
        line.get(dest_local_start..end)?.to_owned(),
    ))
}

fn apply_rewrites(
    content: &str,
    rewrites: Vec<(usize, usize, String)>,
) -> Result<String, LomoError> {
    let mut result = content.to_owned();
    for (start, end, replacement) in rewrites {
        if end > result.len() || start > end {
            return Err(validation(
                "attachment_destination_span_unverified",
                "rewrite span is outside content bounds",
            ));
        }
        if !result.is_char_boundary(start) || !result.is_char_boundary(end) {
            return Err(validation(
                "attachment_destination_span_unverified",
                "rewrite span is not on a UTF-8 char boundary",
            ));
        }
        result.replace_range(start..end, &replacement);
    }
    Ok(result)
}
