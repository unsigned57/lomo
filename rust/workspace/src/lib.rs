//! Workspace document model for stage-2 / stage-5 identity surfaces.
//!
//! Owns constrained source types (P2-01), the unified Lomo/Thino/plain Markdown document parse
//! (P2-02), `RenderDocumentV1` projection (P2-03), pure document patch planning (P2-04),
//! multi-phase scan/document engine job drivers (P2-05), and stage-5 durable identity / codec /
//! history-state v2 / one-shot migration (P5-01). Production dual-stack switch remains P2-09 /
//! P5-13 respectively.

#![deny(unsafe_code)]

mod attachment_remap;
mod conflict_merge;
mod document;
pub mod header;
mod history_v2;
mod identity;
pub mod jobs;
mod limits;
mod lomo_record;
mod migration_v2;
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
pub use history_v2::{
    HISTORY_RETENTION_REVISIONS, HistoryHead, HistoryRevisionV2, HistoryTombstone, RevisionId,
    StateHead, StateRevisionCreate, StateRevisionV2, history_head_path, history_revision_path,
    history_tombstone_path, migrate_memo_history_chain, order_v1_history_for_migration,
    prune_history_with_tombstones, read_history_revision, retention_keep_set, revisions_to_prune,
    state_head_path, state_revision_path, validate_parent_closure, workspace_history_root,
    write_history_head, write_history_revision, write_history_tombstone, write_state_head,
    write_state_revision,
};
pub use identity::{
    RemoteDatasetId, RemoteIdentityDigest, WORKSPACE_GENERATION_ID_BYTES, WorkspaceGenerationId,
    load_or_mint_workspace_generation, load_workspace_generation, mint_new_workspace_generation,
    persist_workspace_generation,
};
pub use limits::{
    MAX_EDITABLE_MEMO_UTF8_CHARS, MAX_INLINE_RENDER_UTF8_BYTES, MAX_IR_STRING_UTF8_BYTES,
    MAX_RENDER_DOCUMENT_NODES, MAX_SEMANTIC_NESTING_DEPTH, MAX_WORKSPACE_SCAN_PAGE_SIZE,
    ResourceBudget,
};
pub use lomo_record::{
    LOMO_CODEC_SCHEMA, LOMO_MAGIC, LOMO_MAX_PAYLOAD_BYTES, LomoLayoutVersion, LomoPaths,
    LomoPayload, LomoRecord, LomoRecordKind, decode_record, encode_record, hex_encode,
    isolate_corrupt_record, read_record, write_layout_head_v2, write_record_atomic,
};
pub use migration_v2::{
    MigrationAction, MigrationCrashPoint, MigrationResult, all_migration_actions,
    migrate_history_state_v1_to_v2, migrate_history_state_v1_to_v2_with_crash,
    write_v1_history_for_test, write_v1_state_for_test,
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
    DocumentCommandRequest, DocumentCommandResult, DocumentExpectedState, SCAN_DRIVER_KIND,
    ScanDriver, WorkspaceMemoContentReference, WorkspaceMemoSummary, WorkspaceScanPage,
    WorkspaceScanRequest, workspace_driver_registry,
};
