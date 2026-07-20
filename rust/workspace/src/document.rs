//! Immutable workspace document model produced by one parse.
//!
//! Owns the original source bytes, memo projections, and the single `RenderDocumentV1` projected
//! from that same source — storage analysis and render IR share one node-fact authority.

use crate::reminder::ReminderRef;
use crate::render::RenderDocumentV1;
use crate::source::{ByteSpan, SourceBytes};
use crate::types::MemoIdentity;

/// File format classification for a parsed workspace document.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum DocumentFormat {
    /// One or more Lomo/Thino time-header memo blocks.
    LomoThino,
    /// No time headers; whole file is one plain Markdown memo (when non-empty after trim).
    PlainMarkdown,
    /// Empty source or whitespace-only plain source with no memo.
    Empty,
}

/// One memo projected from a workspace document parse.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct WorkspaceMemo {
    identity: MemoIdentity,
    time_part: String,
    content: String,
    tags: Vec<String>,
    attachments: Vec<String>,
    reminders: Vec<ReminderRef>,
    has_todo: bool,
    has_url: bool,
    memo_span: ByteSpan,
    header_span: ByteSpan,
    body_span: ByteSpan,
    start_line: u32,
    end_line: u32,
}

impl WorkspaceMemo {
    #[must_use]
    pub const fn identity(&self) -> &MemoIdentity {
        &self.identity
    }

    #[must_use]
    pub fn time_part(&self) -> &str {
        &self.time_part
    }

    #[must_use]
    pub fn content(&self) -> &str {
        &self.content
    }

    #[must_use]
    pub fn tags(&self) -> &[String] {
        &self.tags
    }

    #[must_use]
    pub fn attachments(&self) -> &[String] {
        &self.attachments
    }

    #[must_use]
    pub fn reminders(&self) -> &[ReminderRef] {
        &self.reminders
    }

    #[must_use]
    pub const fn has_todo(&self) -> bool {
        self.has_todo
    }

    #[must_use]
    pub const fn has_url(&self) -> bool {
        self.has_url
    }

    #[must_use]
    pub const fn memo_span(&self) -> ByteSpan {
        self.memo_span
    }

    #[must_use]
    pub const fn header_span(&self) -> ByteSpan {
        self.header_span
    }

    #[must_use]
    pub const fn body_span(&self) -> ByteSpan {
        self.body_span
    }

    #[must_use]
    pub const fn start_line(&self) -> u32 {
        self.start_line
    }

    #[must_use]
    pub const fn end_line(&self) -> u32 {
        self.end_line
    }
}

/// Immutable parse result: original source bytes, memo projections, and owned render IR.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct WorkspaceDocument {
    source: SourceBytes,
    format: DocumentFormat,
    memos: Vec<WorkspaceMemo>,
    render: RenderDocumentV1,
    offset_events: u32,
    heading_events: u32,
    image_events: u32,
    link_events: u32,
}

/// Bundle used only by the parser module to construct a document without arg-count pedantry.
pub struct DocumentBuild {
    pub source: SourceBytes,
    pub format: DocumentFormat,
    pub memos: Vec<WorkspaceMemo>,
    pub render: RenderDocumentV1,
    pub offset_events: u32,
    pub heading_events: u32,
    pub image_events: u32,
    pub link_events: u32,
}

/// Bundle used only by the parser module to construct one memo.
pub struct MemoBuild {
    pub identity: MemoIdentity,
    pub time_part: String,
    pub content: String,
    pub tags: Vec<String>,
    pub attachments: Vec<String>,
    pub reminders: Vec<ReminderRef>,
    pub memo_span: ByteSpan,
    pub header_span: ByteSpan,
    pub body_span: ByteSpan,
    pub start_line: u32,
    pub end_line: u32,
}

impl WorkspaceDocument {
    #[must_use]
    pub const fn source(&self) -> &SourceBytes {
        &self.source
    }

    #[must_use]
    pub const fn format(&self) -> DocumentFormat {
        self.format
    }

    #[must_use]
    pub fn memos(&self) -> &[WorkspaceMemo] {
        &self.memos
    }

    /// Render IR owned by this parse — not a second body re-tokenize.
    #[must_use]
    pub const fn render_document(&self) -> &RenderDocumentV1 {
        &self.render
    }

    /// Unedited serialize is the original source bytes (never AST pretty-print).
    #[must_use]
    pub fn serialize_unedited(&self) -> &[u8] {
        self.source.as_bytes()
    }

    #[must_use]
    pub const fn offset_event_count(&self) -> u32 {
        self.offset_events
    }

    #[must_use]
    pub const fn heading_event_count(&self) -> u32 {
        self.heading_events
    }

    #[must_use]
    pub const fn image_event_count(&self) -> u32 {
        self.image_events
    }

    #[must_use]
    pub const fn link_event_count(&self) -> u32 {
        self.link_events
    }

    #[must_use]
    pub fn from_build(build: DocumentBuild) -> Self {
        Self {
            source: build.source,
            format: build.format,
            memos: build.memos,
            render: build.render,
            offset_events: build.offset_events,
            heading_events: build.heading_events,
            image_events: build.image_events,
            link_events: build.link_events,
        }
    }
}

impl WorkspaceMemo {
    pub(crate) fn replace_semantic_projections(
        &mut self,
        tags: Vec<String>,
        attachments: Vec<String>,
        reminders: Vec<ReminderRef>,
        has_todo: bool,
        has_url: bool,
    ) {
        self.tags = tags;
        self.attachments = attachments;
        self.reminders = reminders;
        self.has_todo = has_todo;
        self.has_url = has_url;
    }
}

#[must_use]
pub fn memo_from_build(build: MemoBuild) -> WorkspaceMemo {
    WorkspaceMemo {
        identity: build.identity,
        time_part: build.time_part,
        content: build.content,
        tags: build.tags,
        attachments: build.attachments,
        reminders: build.reminders,
        has_todo: false,
        has_url: false,
        memo_span: build.memo_span,
        header_span: build.header_span,
        body_span: build.body_span,
        start_line: build.start_line,
        end_line: build.end_line,
    }
}
