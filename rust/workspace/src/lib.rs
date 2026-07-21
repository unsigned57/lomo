//! Workspace document model for stage-2 dark-build.
//!
//! Owns constrained source types (P2-01), the unified Lomo/Thino/plain Markdown document parse
//! (P2-02), `RenderDocumentV1` projection (P2-03), pure document patch planning (P2-04), and
//! multi-phase scan/document engine job drivers (P2-05). Production dual-stack switch remains P2-09.

#![deny(unsafe_code)]

mod attachment_remap;
mod conflict_merge;
mod document;
pub mod header;
pub mod jobs;
mod limits;
mod parse;
mod patch;
mod reminder;
mod render;
mod source;
mod tags;
mod types;

pub use attachment_remap::remap_attachment_destinations;
pub use conflict_merge::merge_memo_shard_by_identity;
pub use document::{DocumentFormat, WorkspaceDocument, WorkspaceMemo};
pub use limits::{
    MAX_EDITABLE_MEMO_UTF8_CHARS, MAX_INLINE_RENDER_UTF8_BYTES, MAX_IR_STRING_UTF8_BYTES,
    MAX_RENDER_DOCUMENT_NODES, MAX_SEMANTIC_NESTING_DEPTH, MAX_WORKSPACE_SCAN_PAGE_SIZE,
    ResourceBudget,
};
pub use parse::{extract_memo_body_from_raw, parse_workspace_document};
pub use patch::{DocumentPatchCommand, DocumentPatchPlan, TaskSourceIdentity, plan_document_patch};
pub use reminder::{ReminderRef, ReminderReference};
pub use render::{
    RENDER_DOCUMENT_SCHEMA_V1, ReminderTokenFacts, ReminderTokenMutation, RenderBlock,
    RenderDocumentV1, RenderInline, RenderListItem, RenderTableCell, SemanticFact,
    SemanticFactKind, build_reminder_token, plan_reminder_token_mutation, reminder_token_facts,
    render_markdown,
};
pub use source::{
    BomKind, ByteSpan, DominantNewline, NewlineKind, SourceBytes, SourceFingerprint,
    SourceTextState, TrailingState,
};
pub use types::{MemoIdentity, WorkspaceRelativePath};

pub use jobs::{
    DOCUMENT_COMMAND_DRIVER_KIND, DocumentCommandDriver, DocumentCommandKind,
    DocumentCommandRequest, DocumentCommandResult, SCAN_DRIVER_KIND, ScanDriver,
    WorkspaceMemoContentReference, WorkspaceMemoSummary, WorkspaceScanPage, WorkspaceScanRequest,
    workspace_driver_registry,
};
