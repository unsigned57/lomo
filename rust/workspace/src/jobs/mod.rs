//! Multi-phase workspace scan and document-command job drivers.
//!
//! Drivers own Markdown semantics and patch planning. They emit only platform action plans over
//! exchange tokens — they never ship large file bodies across FFI.

mod document;
mod history_scan;
mod scan;
mod shared;
mod trash_command;
mod trash_scan;

pub use document::{
    DOCUMENT_COMMAND_DRIVER_KIND, DocumentCommandDriver, DocumentCommandKind,
    DocumentCommandRequest, DocumentCommandResult, DocumentExpectedState, DocumentHistoryWrite,
    DocumentMemoFacts,
};
pub use history_scan::{
    HISTORY_SCAN_DRIVER_KIND, HistoryRevisionSummary, HistoryScanDriver, HistoryScanPage,
    HistoryScanRequest,
};
pub use scan::{
    SCAN_DRIVER_KIND, ScanDriver, WorkspaceMemoContentReference, WorkspaceMemoSummary,
    WorkspaceScanCursor, WorkspaceScanPage, WorkspaceScanRequest,
};
pub use shared::{default_workspace_drivers, workspace_driver_registry};
pub use trash_command::{
    TRASH_COMMAND_DRIVER_KIND, TrashCommandDriver, TrashCommandKind, TrashCommandRequest,
    TrashCommandResult,
};
pub use trash_scan::{
    TRASH_SCAN_DRIVER_KIND, TrashMemoSummary, TrashScanDriver, TrashScanPage, TrashScanRequest,
};
