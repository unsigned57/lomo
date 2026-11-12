//! Bare-link adaptation at the Markdown authority, before Lomo tag recognition.

use lomo_core::LomoError;
use std::collections::BTreeMap;

use super::{RenderInline, map_text_segments};
use crate::limits::validation;
use crate::source::ByteSpan;

/// Only decoded/escaped events need entries; ordinary UTF-8 text maps directly to source.
pub(super) struct LinkTextSpans(BTreeMap<usize, (ByteSpan, usize)>);

impl LinkTextSpans {
    pub(super) const fn new() -> Self {
        Self(BTreeMap::new())
    }

    pub(super) fn record(&mut self, span: ByteSpan, decoded_len: usize) {
        if span.len() != decoded_len {
            self.0.insert(span.start(), (span, decoded_len));
        }
    }

    fn cursor(&self, span: ByteSpan) -> TextSourceCursor<'_> {
        TextSourceCursor {
            entries: self.0.range(span.start()..span.end()).peekable(),
            span,
            source_cursor: span.start(),
            decoded_cursor: 0,
        }
    }
}

/// Link ranges are ordered: consume each encoded event once instead of rescanning every prefix.
struct TextSourceCursor<'a> {
    entries: std::iter::Peekable<std::collections::btree_map::Range<'a, usize, (ByteSpan, usize)>>,
    span: ByteSpan,
    source_cursor: usize,
    decoded_cursor: usize,
}

impl TextSourceCursor<'_> {
    fn source_offset(&mut self, offset: usize) -> Result<usize, LomoError> {
        while let Some((encoded, decoded_len)) = self.entries.peek().map(|(_, entry)| **entry) {
            let plain_len = encoded.start() - self.source_cursor;
            let entity_start = self.decoded_cursor + plain_len;
            if offset <= entity_start {
                return self
                    .source_cursor
                    .checked_add(
                        offset
                            .checked_sub(self.decoded_cursor)
                            .ok_or_else(invalid_link_span)?,
                    )
                    .ok_or_else(invalid_link_span);
            }
            let entity_end = entity_start + decoded_len;
            if offset < entity_end {
                return Err(invalid_link_span());
            }
            self.entries.next().ok_or_else(invalid_link_span)?;
            self.decoded_cursor = entity_end;
            self.source_cursor = encoded.end();
        }
        let result = self.source_cursor
            + offset
                .checked_sub(self.decoded_cursor)
                .ok_or_else(invalid_link_span)?;
        if result > self.span.end() {
            return Err(invalid_link_span());
        }
        Ok(result)
    }

    fn source_span(
        &mut self,
        start: usize,
        end: usize,
        source_len: usize,
    ) -> Result<ByteSpan, LomoError> {
        ByteSpan::try_new(
            self.source_offset(start)?,
            self.source_offset(end)?,
            source_len,
        )
    }
}

pub(super) fn classify_bare_links(
    inlines: Vec<RenderInline>,
    source_len: usize,
    text_spans: &LinkTextSpans,
) -> Result<Vec<RenderInline>, LomoError> {
    map_text_segments(
        inlines,
        |text, span| classify_text(text, span, source_len, text_spans),
        false,
    )
}

fn classify_text(
    text: &str,
    span: ByteSpan,
    source_len: usize,
    text_spans: &LinkTextSpans,
) -> Result<Vec<RenderInline>, LomoError> {
    let mut finder = linkify::LinkFinder::new();
    finder.kinds(&[linkify::LinkKind::Url]);
    let mut ranges: Vec<_> = finder
        .links(text)
        .filter(|link| {
            link.as_str().split_once("://").is_some_and(|(scheme, _)| {
                scheme.eq_ignore_ascii_case("http") || scheme.eq_ignore_ascii_case("https")
            })
        })
        .map(|link| (link.start(), link.end()))
        .collect();
    ranges.extend(geo_ranges(text)?);
    ranges.sort_unstable();
    if ranges.is_empty() {
        return Ok(vec![RenderInline::Text {
            source_span: span,
            text: text.to_owned(),
        }]);
    }
    let mut out = Vec::new();
    let mut source_spans = text_spans.cursor(span);
    let mut cursor = 0;
    for (start, end) in ranges {
        if start < cursor {
            continue;
        }
        if start > cursor {
            out.push(RenderInline::Text {
                source_span: source_spans.source_span(cursor, start, source_len)?,
                text: text
                    .get(cursor..start)
                    .ok_or_else(invalid_link_span)?
                    .to_owned(),
            });
        }
        let source_span = source_spans.source_span(start, end, source_len)?;
        let destination = text
            .get(start..end)
            .ok_or_else(invalid_link_span)?
            .to_owned();
        out.push(RenderInline::Link {
            source_span,
            children: vec![RenderInline::Text {
                source_span,
                text: destination.clone(),
            }],
            destination,
            title: None,
        });
        cursor = end;
    }
    if cursor < text.len() {
        out.push(RenderInline::Text {
            source_span: source_spans.source_span(cursor, text.len(), source_len)?,
            text: text.get(cursor..).ok_or_else(invalid_link_span)?.to_owned(),
        });
    }
    Ok(out)
}

/// Linkify recognizes authority-based URLs; geographic URIs instead carry coordinate tuples.
fn geo_ranges(text: &str) -> Result<Vec<(usize, usize)>, LomoError> {
    let mut ranges = Vec::new();
    let mut scanned_to = 0;
    for (start, _) in text
        .as_bytes()
        .windows(4)
        .enumerate()
        .filter(|(_, prefix)| prefix.eq_ignore_ascii_case(b"geo:"))
    {
        if start < scanned_to {
            continue;
        }
        let (prefix, tail) = text.split_at_checked(start).ok_or_else(invalid_link_span)?;
        if prefix
            .chars()
            .next_back()
            .is_some_and(|c| c.is_alphanumeric() || c == '_')
        {
            continue;
        }
        let mut parentheses = 0usize;
        let uri = tail
            .split(|c: char| match c {
                '(' => {
                    parentheses += 1;
                    false
                }
                ')' if parentheses > 0 => {
                    parentheses -= 1;
                    false
                }
                ')' => true,
                _ => c.is_whitespace() || matches!(c, '<' | '>' | '"' | '\'' | ']' | '，' | '。'),
            })
            .next()
            .ok_or_else(invalid_link_span)?
            .trim_end_matches('.');
        // Each candidate consumes its whole token, including invalid tuples, so repeated
        // malformed `geo:` prefixes cannot rescan a suffix quadratically.
        scanned_to = start + uri.len();
        if valid_geo_coordinates(uri) {
            ranges.push((start, start + uri.len()));
        }
    }
    Ok(ranges)
}

/// An invalid coordinate tuple is ordinary authored text, never a clickable location.
fn valid_geo_coordinates(uri: &str) -> bool {
    let Some(coordinates) = uri
        .split_once(':')
        .filter(|(scheme, _)| scheme.eq_ignore_ascii_case("geo"))
        .and_then(|(_, tail)| tail.split([';', '?']).next())
    else {
        return false;
    };
    let mut parts = coordinates.split(',');
    let (Some(latitude), Some(longitude)) = (parts.next(), parts.next()) else {
        return false;
    };
    let (Ok(latitude), Ok(longitude)) = (latitude.parse::<f64>(), longitude.parse::<f64>()) else {
        return false;
    };
    if !(-90.0..=90.0).contains(&latitude) || !(-180.0..=180.0).contains(&longitude) {
        return false;
    }
    if let Some(altitude) = parts.next() {
        let Ok(altitude) = altitude.parse::<f64>() else {
            return false;
        };
        if !altitude.is_finite() {
            return false;
        }
    }
    parts.next().is_none()
}

fn invalid_link_span() -> LomoError {
    validation(
        "bare_link_span_invalid",
        "bare link must stay within its UTF-8 text span",
    )
}
