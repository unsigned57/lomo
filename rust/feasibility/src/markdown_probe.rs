//! Minimal `pulldown-cmark` feasibility for offset events and stream parse.

use std::fs;
use std::path::Path;

use pulldown_cmark::{Event, Options, Parser};
use thiserror::Error;

use crate::corpus::hex_digest;

/// Failures from the Markdown feasibility probe.
#[derive(Clone, Debug, Eq, Error, PartialEq)]
pub enum MarkdownProbeError {
    #[error("I/O failure: {detail}")]
    Io { detail: String },
    #[error("input is not valid UTF-8")]
    InvalidUtf8,
}

/// UI-neutral semantic summary of one markdown fixture.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MarkdownProbeReport {
    pub byte_length: usize,
    pub event_count: usize,
    pub heading_count: usize,
    pub link_count: usize,
    pub image_count: usize,
    pub content_sha256: String,
    pub first_event_offset: Option<usize>,
}

/// Parse a UTF-8 Markdown fixture with offset events enabled.
///
/// # Errors
///
/// Returns [`MarkdownProbeError`] when the file cannot be read or is not UTF-8.
pub fn probe_markdown_file(path: &Path) -> Result<MarkdownProbeReport, MarkdownProbeError> {
    let bytes = fs::read(path).map_err(|error| MarkdownProbeError::Io {
        detail: error.to_string(),
    })?;
    let text = std::str::from_utf8(&bytes).map_err(|_utf8| MarkdownProbeError::InvalidUtf8)?;
    Ok(probe_markdown_text(text, bytes.len()))
}

/// Parse an in-memory Markdown string.
#[must_use]
pub fn probe_markdown_text(text: &str, byte_length: usize) -> MarkdownProbeReport {
    let mut options = Options::empty();
    options.insert(Options::ENABLE_TABLES);
    options.insert(Options::ENABLE_TASKLISTS);
    options.insert(Options::ENABLE_STRIKETHROUGH);

    let parser = Parser::new_ext(text, options).into_offset_iter();
    let mut event_count = 0_usize;
    let mut heading_count = 0_usize;
    let mut link_count = 0_usize;
    let mut image_count = 0_usize;
    let mut first_event_offset = None;

    for (event, range) in parser {
        event_count = event_count.saturating_add(1);
        if first_event_offset.is_none() {
            first_event_offset = Some(range.start);
        }
        if let Event::Start(tag) = event {
            match tag {
                pulldown_cmark::Tag::Heading { .. } => {
                    heading_count = heading_count.saturating_add(1);
                }
                pulldown_cmark::Tag::Link { .. } => {
                    link_count = link_count.saturating_add(1);
                }
                pulldown_cmark::Tag::Image { .. } => {
                    image_count = image_count.saturating_add(1);
                }
                _ => {}
            }
        }
    }

    MarkdownProbeReport {
        byte_length,
        event_count,
        heading_count,
        link_count,
        image_count,
        content_sha256: hex_digest(text.as_bytes()),
        first_event_offset,
    }
}

/// Round-trip without edits: input bytes must equal output when the probe only reads.
///
/// This is a read-only feasibility check: parsing must not require mutation of the source.
#[must_use]
pub fn bytes_stable_after_parse(bytes: &[u8]) -> bool {
    let Ok(text) = std::str::from_utf8(bytes) else {
        return false;
    };
    let report = probe_markdown_text(text, bytes.len());
    report.byte_length == bytes.len() && report.content_sha256 == hex_digest(bytes)
}
