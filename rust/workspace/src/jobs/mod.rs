//! Multi-phase workspace scan and document-command job drivers.
//!
//! Drivers own Markdown semantics and patch planning. They emit only platform action plans over
//! exchange tokens — they never ship large file bodies across FFI.

mod document;
mod scan;
mod shared;

pub use document::{
    DOCUMENT_COMMAND_DRIVER_KIND, DocumentCommandDriver, DocumentCommandKind,
    DocumentCommandRequest, DocumentCommandResult,
};
pub use scan::{
    SCAN_DRIVER_KIND, ScanDriver, WorkspaceMemoContentReference, WorkspaceMemoSummary,
    WorkspaceScanCursor, WorkspaceScanPage, WorkspaceScanRequest,
};
pub use shared::{default_workspace_drivers, workspace_driver_registry};
