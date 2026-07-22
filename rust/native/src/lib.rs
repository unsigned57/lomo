#![deny(unsafe_code)]
// BoltFFI `#[export]` expands through underscore-prefixed helpers for class method dispatch.
#![allow(
    clippy::used_underscore_items,
    reason = "BoltFFI export expansion uses underscore-prefixed dispatch items"
)]
// EngineError embeds EngineFailure by value because BoltFFI cannot encode Box in #[error] yet.
#![allow(
    clippy::result_large_err,
    reason = "BoltFFI #[error] variants cannot box EngineFailure; wire type stays inline"
)]

use std::fmt;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use boltffi::{data, error, export};
use lomo_core as core;
use lomo_sync_core::plan_envelope;
use lomo_workspace::{self as workspace, workspace_driver_registry};

// Public so BoltFFI type resolution can name `crate::media_ffi::*` wire DTOs from store_ffi.
pub mod media_ffi;
mod store_ffi;
pub use media_ffi::{
    ArchiveExportResultDto, ArchiveInspectResultDto, MediaAttachmentRefDto, MediaCommittedEntryDto,
    MediaManifestDto, MediaOrphanSweepResultDto, MediaPromotePlanDto, MediaPromoteResultDto,
    MediaSourceKind, MediaStagedDto, MediaTrashEntryDto, pending_promotes_from_ffi,
};
pub use store_ffi::{
    StoreHandle, StoreHistoryAttachmentRef, StoreMemoCommand, StoreMemoCommandKind,
    StoreMemoCommit, StoreMemoFilters, StoreMemoPage, StoreMemoQuery, StoreMemoSnapshot,
    StoreMemoSummary, StorePageCursor, StorePlannedAlarm, StoreRebuildResult, StoreReminderCommand,
    StoreReminderCommandKind, StoreReminderCommandResult, StoreReminderPlan, StoreReminderQuery,
    StoreReminderSession, StoreTimeZoneContext, StoreZoneTransition,
};

#[data]
#[derive(Clone, Debug)]
pub struct RenderRequest {
    pub content: String,
    pub schema_version: u32,
}

#[data]
#[derive(Clone, Debug)]
pub struct RenderDocument {
    pub schema_version: u32,
    pub plain_text: String,
    pub node_count: u32,
    pub tag_names: Vec<String>,
    pub attachment_destinations: Vec<String>,
    pub nodes: Vec<RenderNode>,
}

#[data]
#[derive(Clone, Copy, Debug)]
pub enum RenderNodeKind {
    Paragraph,
    Heading,
    BlockQuote,
    List,
    ListItem,
    CodeBlock,
    ThematicBreak,
    Table,
    TableHeaderCell,
    TableRow,
    TableCell,
    HtmlBlock,
    Text,
    Strong,
    Emphasis,
    Strikethrough,
    Highlight,
    Code,
    Link,
    Image,
    Tag,
    Reminder,
    WikiReference,
    SoftBreak,
    HardBreak,
    HtmlInline,
}

#[data]
#[derive(Clone, Debug)]
pub struct RenderNode {
    pub kind: RenderNodeKind,
    pub source_start: u64,
    pub source_end: u64,
    pub depth: u32,
    pub text: Option<String>,
    pub destination: Option<String>,
    pub title: Option<String>,
    pub level: Option<u32>,
    pub ordered: Option<bool>,
    pub list_start: Option<u64>,
    pub checked: Option<bool>,
    pub action_start: Option<u64>,
    pub action_end: Option<u64>,
}

#[data]
#[derive(Clone, Debug)]
pub struct WorkspaceScanRequest {
    pub page_size: u32,
    pub cursor: Option<String>,
    pub root_path: Option<String>,
}

#[data]
#[derive(Clone, Debug)]
pub struct WorkspaceMemoContentReference {
    pub exchange_token: String,
    pub length: u64,
    pub digest: String,
}

#[data]
#[derive(Clone, Debug)]
pub struct WorkspaceReminderReference {
    pub opaque_id: String,
    pub revision: String,
    pub memo_identity: String,
    pub source_start: u64,
    pub source_end: u64,
    pub token_fingerprint: String,
    pub token: String,
    pub due_at_local: String,
    pub repeat_count: u32,
    pub fired_count: u32,
    pub done: bool,
    pub interval_minutes: u32,
    pub recurrence_code: String,
}

#[data]
#[derive(Clone, Debug)]
pub struct WorkspaceMemoSummary {
    pub path: String,
    pub identity: String,
    pub time_part: String,
    pub fingerprint: String,
    pub tags: Vec<String>,
    pub attachments: Vec<String>,
    pub reminders: Vec<WorkspaceReminderReference>,
    pub has_todo: bool,
    pub has_url: bool,
    pub content: WorkspaceMemoContentReference,
    pub body_start: u64,
    pub body_end: u64,
    pub start_line: u32,
    pub end_line: u32,
}

#[data]
#[derive(Clone, Debug)]
pub struct WorkspaceScanPage {
    pub items: Vec<WorkspaceMemoSummary>,
    pub next_cursor: Option<String>,
}

#[data]
#[derive(Clone, Debug)]
pub enum WorkspaceDocumentCommandKind {
    Append {
        time_part: String,
        content: String,
    },
    Replace {
        identity: String,
        content: String,
    },
    Remove {
        identity: String,
    },
    ToggleTask {
        source_start: u64,
        source_end: u64,
    },
    RewriteReminder {
        reminder: WorkspaceReminderReference,
        replacement: String,
    },
}

#[data]
#[derive(Clone, Debug)]
pub struct WorkspaceDocumentCommand {
    pub path: String,
    pub expected_fingerprint: String,
    pub command: WorkspaceDocumentCommandKind,
}

#[data]
#[derive(Clone, Debug)]
pub struct WorkspaceDocumentCommandResult {
    pub path: String,
    pub result_fingerprint: String,
    pub bytes_written: u64,
}

#[data]
#[derive(Clone, Debug)]
pub struct EngineConfig {
    pub control_root: String,
    pub exchange_root: String,
    pub workspace: Option<WorkspaceDescriptor>,
    pub bootstrap_deadline_millis: u64,
}

#[data]
#[derive(Clone, Debug)]
pub enum WorkspaceDescriptor {
    Direct { root_path: String },
    Saf { capability_token: String },
}

#[data]
#[derive(Clone, Debug)]
pub struct EngineFailure {
    pub category: String,
    pub code: String,
    pub retry_disposition: String,
    pub operation_id: Option<String>,
    pub job_id: Option<String>,
    pub diagnostic: String,
}

#[data]
#[derive(Clone, Debug)]
pub enum EngineState {
    AwaitingWorkspaceSelection,
    Opening {
        job_id: String,
    },
    Ready {
        core_revision: u64,
        event_sequence: u64,
    },
    ReadOnlyRecovery {
        failure: EngineFailure,
    },
    ShuttingDown,
}

#[data]
#[derive(Clone, Debug)]
pub struct ActionEvidence {
    pub length: u64,
    pub digest: String,
    pub fingerprint: String,
}

#[data]
#[derive(Clone, Debug)]
pub enum ExpectedFingerprint {
    Absent,
    Match { evidence: ActionEvidence },
}

#[data]
#[derive(Clone, Debug)]
pub struct ExchangeArtifact {
    pub token: String,
    pub length: u64,
    pub digest: String,
}

#[data]
#[derive(Clone, Copy, Debug)]
pub enum WriteMode {
    Create,
    Replace,
}

#[data]
#[derive(Clone, Debug)]
pub enum WorkspaceTarget {
    Root,
    Relative { path: String },
}

#[data]
#[derive(Clone, Debug)]
pub enum PlatformAction {
    Stat {
        action_id: String,
        capability_token: String,
        target: WorkspaceTarget,
    },
    ListChildren {
        action_id: String,
        capability_token: String,
        target: WorkspaceTarget,
        cursor: Option<String>,
        page_size: u32,
    },
    EnsureDirectory {
        action_id: String,
        capability_token: String,
        path: String,
    },
    ReadToExchange {
        action_id: String,
        capability_token: String,
        path: String,
        exchange_token: String,
        expected_source: ExpectedFingerprint,
    },
    WriteFromExchange {
        action_id: String,
        capability_token: String,
        artifact: ExchangeArtifact,
        path: String,
        mode: WriteMode,
        expected_target: ExpectedFingerprint,
    },
    Move {
        action_id: String,
        capability_token: String,
        source: String,
        target: String,
        expected_source: ExpectedFingerprint,
        expected_target: ExpectedFingerprint,
    },
    Delete {
        action_id: String,
        capability_token: String,
        path: String,
        expected_target: ExpectedFingerprint,
    },
}

#[data]
#[derive(Clone, Debug)]
pub struct PlatformActionBatch {
    pub schema_version: u32,
    pub job_id: String,
    pub batch_id: String,
    pub attempt: u32,
    pub deadline_epoch_millis: u64,
    pub actions: Vec<PlatformAction>,
}

#[data]
#[derive(Clone, Copy, Debug)]
pub enum DocumentKind {
    File,
    Directory,
}

#[data]
#[derive(Clone, Debug)]
pub struct DocumentMetadata {
    pub target: WorkspaceTarget,
    pub kind: DocumentKind,
    pub mime_type: Option<String>,
    pub evidence: ActionEvidence,
}

#[data]
#[derive(Clone, Debug)]
pub struct MetadataPage {
    pub items: Vec<DocumentMetadata>,
    pub next_cursor: Option<String>,
}

#[data]
#[derive(Clone, Debug)]
pub struct VerifiedAbsence {
    pub target: WorkspaceTarget,
    pub fingerprint: String,
}

#[data]
#[derive(Clone, Debug)]
pub enum PlatformActionOutput {
    Stat {
        metadata: DocumentMetadata,
    },
    Listed {
        page: MetadataPage,
    },
    DirectoryReady {
        metadata: DocumentMetadata,
    },
    ReadToExchange {
        source_metadata: DocumentMetadata,
        artifact: ExchangeArtifact,
    },
    WriteComplete {
        metadata: DocumentMetadata,
    },
    MoveComplete {
        metadata: DocumentMetadata,
    },
    DeleteComplete {
        absence: VerifiedAbsence,
    },
}

#[data]
#[derive(Clone, Debug)]
pub enum ActionOutcome {
    Applied { output: PlatformActionOutput },
    AlreadySatisfied { output: PlatformActionOutput },
    Failed { failure: EngineFailure },
}

#[data]
#[derive(Clone, Debug)]
pub struct ActionResult {
    pub action_id: String,
    pub outcome: ActionOutcome,
}

#[data]
#[derive(Clone, Debug)]
pub struct PlatformBatchResult {
    pub schema_version: u32,
    pub job_id: String,
    pub batch_id: String,
    pub attempt: u32,
    pub action_results: Vec<ActionResult>,
}

#[data]
#[derive(Clone, Debug)]
pub enum JobStep {
    Running,
    NeedsPlatformBatch { batch: PlatformActionBatch },
    BlockedByConflict { failure: EngineFailure },
    Completed,
    Failed { failure: EngineFailure },
}

#[data]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CancelOutcome {
    Accepted,
    AlreadyCancelled,
    AlreadyCompleted,
    UnknownJob,
}

#[data]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ShutdownOutcome {
    Completed,
    DeadlineExceeded,
    AlreadyShutdown,
}

#[data]
#[derive(Clone, Debug)]
pub struct CoreEvent {
    pub event_sequence: u64,
    pub core_revision: u64,
    pub job_id: Option<String>,
    /// Bounded invalidation scopes (`memo_list`, `search`, `reminder`, `full`, …). Empty when the
    /// publisher has not attached scopes (legacy core events); consumers treat empty as full
    /// resnapshot when combined with an event-sequence gap.
    pub scopes: Vec<String>,
}

#[export]
pub trait CoreEventListener: Send + Sync {
    fn on_event(&self, event: CoreEvent);
}

#[error]
#[derive(Debug)]
pub enum EngineError {
    Failure { failure: EngineFailure },
}

impl EngineError {
    #[must_use]
    pub fn category(&self) -> &str {
        match self {
            Self::Failure { failure } => &failure.category,
        }
    }

    #[must_use]
    pub fn code(&self) -> &str {
        match self {
            Self::Failure { failure } => &failure.code,
        }
    }
}

impl fmt::Display for EngineError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Failure { failure } => write!(
                formatter,
                "{}/{}: {}",
                failure.category, failure.code, failure.diagnostic
            ),
        }
    }
}

impl std::error::Error for EngineError {}

#[derive(Debug)]
pub struct LomoEngine {
    core: Arc<core::LomoEngine>,
    /// Dark-build store handle for Direct workspaces (P3-09). Absent for SAF/no-workspace opens.
    store: Option<StoreHandle>,
}

struct ListenerAdapter {
    foreign: Box<dyn CoreEventListener>,
}

impl core::CoreEventListener for ListenerAdapter {
    fn on_event(&self, event: core::CoreEvent) -> Result<(), core::LomoError> {
        self.foreign.on_event(CoreEvent {
            event_sequence: event.event_sequence().get(),
            core_revision: event.core_revision().get(),
            job_id: event.job_id().map(|id| id.as_str().to_owned()),
            scopes: Vec::new(),
        });
        Ok(())
    }
}

pub struct Subscription {
    core: core::Subscription,
}

#[export]
impl Subscription {
    /// Explicitly unregisters the foreign listener before releasing the `BoltFFI` object handle.
    #[must_use]
    pub fn unsubscribe(&self) -> bool {
        self.core.close()
    }
}

#[export]
impl LomoEngine {
    /// Opens the formal application kernel through the FFI boundary.
    ///
    /// # Errors
    ///
    /// Returns structured boundary/core errors without constructing a partial engine.
    pub fn open(config: EngineConfig) -> Result<Self, EngineError> {
        let bootstrap_deadline = Duration::from_millis(config.bootstrap_deadline_millis);
        let control_root = PathBuf::from(&config.control_root);
        let store = match &config.workspace {
            Some(WorkspaceDescriptor::Direct { root_path }) => {
                Some(StoreHandle::new(PathBuf::from(root_path), &control_root)?)
            }
            _ => None,
        };
        let workspace = config.workspace.map(workspace_from_ffi).transpose()?;
        let core_config =
            core::EngineConfig::new(control_root, PathBuf::from(config.exchange_root), workspace)
                .and_then(|config| config.with_bootstrap_deadline(bootstrap_deadline))
                .map(|config| config.with_drivers(workspace_driver_registry()))
                .map_err(EngineError::from)?;
        let core = core::LomoEngine::open(core_config).map_err(EngineError::from)?;
        Ok(Self { core, store })
    }

    #[must_use]
    pub fn state(&self) -> EngineState {
        state_to_ffi(self.core.state())
    }

    /// Polls a durable job snapshot.
    ///
    /// # Errors
    ///
    /// Returns validation or engine lifecycle errors.
    pub fn poll_job(&self, job_id: String) -> Result<JobStep, EngineError> {
        let parsed_job_id = core::JobId::parse(&job_id).map_err(EngineError::from)?;
        drop(job_id);
        self.core
            .poll_job(&parsed_job_id)
            .map(job_step_to_ffi)
            .map_err(EngineError::from)
    }

    /// Registers an explicitly closeable foreign listener.
    ///
    /// # Errors
    ///
    /// Returns a resource-limit error when the bounded listener registry is full.
    pub fn subscribe(
        &self,
        listener: Box<dyn CoreEventListener>,
    ) -> Result<Subscription, EngineError> {
        let adapter: Arc<dyn core::CoreEventListener> =
            Arc::new(ListenerAdapter { foreign: listener });
        let subscription = self.core.subscribe(adapter).map_err(EngineError::from)?;
        Ok(Subscription { core: subscription })
    }

    /// Submits an ordered platform result prefix to the core actor.
    ///
    /// # Errors
    ///
    /// Returns boundary validation, journal, or engine lifecycle errors.
    pub fn submit_platform_result(
        &self,
        job_id: String,
        result: PlatformBatchResult,
    ) -> Result<JobStep, EngineError> {
        let parsed_job_id = core::JobId::parse(&job_id).map_err(EngineError::from)?;
        drop(job_id);
        let result = result_from_ffi(result)?;
        self.core
            .submit_platform_result(&parsed_job_id, result)
            .map(job_step_to_ffi)
            .map_err(EngineError::from)
    }

    /// Durably cancels a job.
    ///
    /// # Errors
    ///
    /// Returns validation, journal, or engine lifecycle errors.
    pub fn cancel_job(&self, job_id: String) -> Result<CancelOutcome, EngineError> {
        let parsed_job_id = core::JobId::parse(&job_id).map_err(EngineError::from)?;
        drop(job_id);
        self.core
            .cancel_job(&parsed_job_id)
            .map(cancel_to_ffi)
            .map_err(EngineError::from)
    }

    /// Renders constrained inline Markdown into a conversion-only `RenderDocument` DTO.
    ///
    /// Facade performs no Markdown rule interpretation beyond calling `lomo-workspace`.
    ///
    /// # Errors
    ///
    /// Returns validation / resource-limit errors from the workspace owner.
    pub fn render_markdown(&self, request: RenderRequest) -> Result<RenderDocument, EngineError> {
        let _: &Arc<core::LomoEngine> = &self.core;
        let RenderRequest {
            content,
            schema_version,
        } = request;
        workspace::RenderDocumentV1::reject_unknown_schema(schema_version)
            .map_err(EngineError::from)?;
        let source = workspace::SourceBytes::try_from_str(&content).map_err(EngineError::from)?;
        let document = workspace::render_markdown(&source).map_err(EngineError::from)?;
        render_document_to_ffi(&document)
    }

    /// Starts a workspace scan job. Platform batches must still be driven by the host.
    ///
    /// # Errors
    ///
    /// Returns structured engine/driver validation errors.
    pub fn start_workspace_scan(
        &self,
        request: WorkspaceScanRequest,
        deadline_millis: u64,
    ) -> Result<String, EngineError> {
        let payload = workspace::WorkspaceScanRequest {
            page_size: request.page_size,
            cursor: request.cursor,
            root_path: request.root_path,
        };
        let request_json = serde_json::to_string(&payload).map_err(|_error| {
            EngineError::from(static_boundary_error(
                core::ErrorCategory::Validation,
                "invalid_workspace_scan_request",
                core::RetryDisposition::Never,
                None,
                "workspace scan request cannot be serialized",
            ))
        })?;
        let job_id = self
            .core
            .start_user_job(
                workspace::SCAN_DRIVER_KIND,
                &request_json,
                Duration::from_millis(deadline_millis),
            )
            .map_err(EngineError::from)?;
        Ok(job_id.as_str().to_owned())
    }

    /// Reads the durable scan page published by a scan job (available on completion / page publish).
    ///
    /// # Errors
    ///
    /// Returns unknown-job or decode errors.
    pub fn read_workspace_scan_page(
        &self,
        job_id: String,
    ) -> Result<WorkspaceScanPage, EngineError> {
        let parsed = core::JobId::parse(&job_id).map_err(EngineError::from)?;
        drop(job_id);
        let payload = self
            .core
            .read_job_result(&parsed)
            .map_err(EngineError::from)?
            .ok_or_else(|| {
                EngineError::from(static_boundary_error(
                    core::ErrorCategory::Validation,
                    "workspace_scan_page_unavailable",
                    core::RetryDisposition::Transient,
                    Some(parsed.as_str()),
                    "workspace scan page has not been published yet",
                ))
            })?;
        let page: workspace::WorkspaceScanPage =
            serde_json::from_str(&payload).map_err(|_error| {
                EngineError::from(static_boundary_error(
                    core::ErrorCategory::Corruption,
                    "workspace_scan_page_corrupt",
                    core::RetryDisposition::AfterUserAction,
                    Some(parsed.as_str()),
                    "workspace scan page payload cannot be decoded",
                ))
            })?;
        Ok(WorkspaceScanPage {
            items: page
                .items
                .into_iter()
                .map(|item| WorkspaceMemoSummary {
                    path: item.path,
                    identity: item.identity,
                    time_part: item.time_part,
                    fingerprint: item.fingerprint,
                    tags: item.tags,
                    attachments: item.attachments,
                    reminders: item
                        .reminders
                        .into_iter()
                        .map(workspace_reminder_to_ffi)
                        .collect(),
                    has_todo: item.has_todo,
                    has_url: item.has_url,
                    content: WorkspaceMemoContentReference {
                        exchange_token: item.content.exchange_token,
                        length: item.content.length,
                        digest: item.content.digest,
                    },
                    body_start: item.body_start,
                    body_end: item.body_end,
                    start_line: item.start_line,
                    end_line: item.end_line,
                })
                .collect(),
            next_cursor: page.next_cursor,
        })
    }

    /// Starts a workspace document command job.
    ///
    /// # Errors
    ///
    /// Returns structured engine/driver validation errors.
    pub fn start_workspace_document_command(
        &self,
        command: WorkspaceDocumentCommand,
        deadline_millis: u64,
    ) -> Result<String, EngineError> {
        let payload = workspace::DocumentCommandRequest {
            path: command.path,
            expected_fingerprint: command.expected_fingerprint,
            command: match command.command {
                WorkspaceDocumentCommandKind::Append { time_part, content } => {
                    workspace::DocumentCommandKind::Append { time_part, content }
                }
                WorkspaceDocumentCommandKind::Replace { identity, content } => {
                    workspace::DocumentCommandKind::Replace { identity, content }
                }
                WorkspaceDocumentCommandKind::Remove { identity } => {
                    workspace::DocumentCommandKind::Remove { identity }
                }
                WorkspaceDocumentCommandKind::ToggleTask {
                    source_start,
                    source_end,
                } => workspace::DocumentCommandKind::ToggleTask {
                    source_start,
                    source_end,
                },
                WorkspaceDocumentCommandKind::RewriteReminder {
                    reminder,
                    replacement,
                } => workspace::DocumentCommandKind::RewriteReminder {
                    reminder: workspace_reminder_from_ffi(reminder),
                    replacement,
                },
            },
        };
        let request_json = serde_json::to_string(&payload).map_err(|_error| {
            EngineError::from(static_boundary_error(
                core::ErrorCategory::Validation,
                "invalid_document_command_request",
                core::RetryDisposition::Never,
                None,
                "document command request cannot be serialized",
            ))
        })?;
        let job_id = self
            .core
            .start_user_job(
                workspace::DOCUMENT_COMMAND_DRIVER_KIND,
                &request_json,
                Duration::from_millis(deadline_millis),
            )
            .map_err(EngineError::from)?;
        Ok(job_id.as_str().to_owned())
    }

    /// Reads the durable document-command result.
    ///
    /// # Errors
    ///
    /// Returns unknown-job or decode errors.
    pub fn read_workspace_document_command_result(
        &self,
        job_id: String,
    ) -> Result<WorkspaceDocumentCommandResult, EngineError> {
        let parsed = core::JobId::parse(&job_id).map_err(EngineError::from)?;
        drop(job_id);
        let payload = self
            .core
            .read_job_result(&parsed)
            .map_err(EngineError::from)?
            .ok_or_else(|| {
                EngineError::from(static_boundary_error(
                    core::ErrorCategory::Validation,
                    "document_command_result_unavailable",
                    core::RetryDisposition::Transient,
                    Some(parsed.as_str()),
                    "document command result has not been published yet",
                ))
            })?;
        let result: workspace::DocumentCommandResult =
            serde_json::from_str(&payload).map_err(|_error| {
                EngineError::from(static_boundary_error(
                    core::ErrorCategory::Corruption,
                    "document_command_result_corrupt",
                    core::RetryDisposition::AfterUserAction,
                    Some(parsed.as_str()),
                    "document command result payload cannot be decoded",
                ))
            })?;
        Ok(WorkspaceDocumentCommandResult {
            path: result.path,
            result_fingerprint: result.result_fingerprint,
            bytes_written: result.bytes_written,
        })
    }

    /// Explicitly shuts down the engine within a bounded deadline.
    ///
    /// # Errors
    ///
    /// Returns validation, journal, or engine lifecycle errors.
    pub fn shutdown(&self, deadline_millis: u64) -> Result<ShutdownOutcome, EngineError> {
        let deadline = core::ShutdownDeadline::new(Duration::from_millis(deadline_millis))
            .map_err(EngineError::from)?;
        self.core
            .shutdown(deadline)
            .map(shutdown_to_ffi)
            .map_err(EngineError::from)
    }

    /// Dark-build `query_memos` (bounded page; no full list transfer).
    ///
    /// # Errors
    ///
    /// Missing Direct store handle, or store query errors.
    pub fn query_memos(
        &self,
        query: StoreMemoQuery,
        cursor: Option<StorePageCursor>,
        page_size: u32,
    ) -> Result<StoreMemoPage, EngineError> {
        self.store_handle()?.query_memos(query, cursor, page_size)
    }

    /// Dark-build `get_memo`.
    ///
    /// # Errors
    ///
    /// Missing Direct store handle, or store errors.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned String for foreign callers"
    )]
    pub fn get_memo(&self, memo_id: String) -> Result<Option<StoreMemoSnapshot>, EngineError> {
        self.store_handle()?.get_memo(&memo_id)
    }

    /// Dark-build history attachment paths for D6 orphan keep-set.
    ///
    /// # Errors
    ///
    /// Missing Direct store handle, or store history list errors.
    pub fn list_history_attachment_refs(
        &self,
    ) -> Result<Vec<StoreHistoryAttachmentRef>, EngineError> {
        self.store_handle()?.list_history_attachment_refs()
    }

    /// Dark-build `apply_memo_command` (synchronous commit facts + invalidation scopes).
    ///
    /// # Errors
    ///
    /// Missing Direct store handle, or transaction errors.
    pub fn apply_memo_command(
        &self,
        command: StoreMemoCommand,
    ) -> Result<StoreMemoCommit, EngineError> {
        self.store_handle()?.apply_memo_command(command)
    }

    /// Dark-build `query_reminder_plan`.
    ///
    /// # Errors
    ///
    /// Missing Direct store handle, or plan errors.
    pub fn query_reminder_plan(
        &self,
        query: StoreReminderQuery,
    ) -> Result<StoreReminderPlan, EngineError> {
        self.store_handle()?.query_reminder_plan(query)
    }

    /// Dark-build `apply_reminder_command`.
    ///
    /// # Errors
    ///
    /// Missing Direct store handle, or command errors.
    pub fn apply_reminder_command(
        &self,
        command: StoreReminderCommand,
    ) -> Result<StoreReminderCommandResult, EngineError> {
        self.store_handle()?.apply_reminder_command(command)
    }

    /// Dark-build `start_rebuild` (synchronous rebuild result).
    ///
    /// # Errors
    ///
    /// Missing Direct store handle, or rebuild errors.
    pub fn start_rebuild(&self, batch_size: u32) -> Result<StoreRebuildResult, EngineError> {
        self.store_handle()?.start_rebuild(batch_size)
    }

    /// Dark-build path-only media stage (P4-09). No full media bytes.
    ///
    /// # Errors
    ///
    /// Media validation/storage errors.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned String for foreign callers"
    )]
    pub fn stage_media(
        &self,
        media_root: String,
        source_kind: MediaSourceKind,
        source_path: String,
        human_name_hint: String,
    ) -> Result<MediaStagedDto, EngineError> {
        media_ffi::ffi_stage_media(&media_root, source_kind, &source_path, &human_name_hint)
    }

    /// Dark-build allocate recording target path under stage dir.
    ///
    /// # Errors
    ///
    /// Media validation/storage errors.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned String for foreign callers"
    )]
    pub fn allocate_recording_target(
        &self,
        media_root: String,
        extension: String,
    ) -> Result<String, EngineError> {
        media_ffi::ffi_allocate_recording_target(&media_root, &extension)
    }

    /// Dark-build finalize recording path into staged media.
    ///
    /// # Errors
    ///
    /// Media validation/storage errors.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned String for foreign callers"
    )]
    pub fn finalize_recording(
        &self,
        media_root: String,
        recording_path: String,
        human_name_hint: String,
    ) -> Result<MediaStagedDto, EngineError> {
        media_ffi::ffi_finalize_recording(&media_root, &recording_path, &human_name_hint)
    }

    /// Dark-build promote staged media to final relative path (path-only).
    ///
    /// # Errors
    ///
    /// Media validation/storage errors.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned String for foreign callers"
    )]
    pub fn promote_media(
        &self,
        workspace_root: String,
        plan: MediaPromotePlanDto,
    ) -> Result<MediaPromoteResultDto, EngineError> {
        media_ffi::ffi_promote_media(&workspace_root, plan)
    }

    /// Dark-build media manifest listing (paths + digests only).
    ///
    /// # Errors
    ///
    /// Storage walk errors.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned String for foreign callers"
    )]
    pub fn query_media_manifest(
        &self,
        workspace_root: String,
    ) -> Result<MediaManifestDto, EngineError> {
        media_ffi::ffi_query_media_manifest(&workspace_root)
    }

    /// Dark-build media orphan sweep (path-only host maps).
    ///
    /// # Errors
    ///
    /// Media storage/validation errors.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned String for foreign callers"
    )]
    pub fn media_orphan_sweep(
        &self,
        media_root: String,
        committed: Vec<MediaCommittedEntryDto>,
        refs: Vec<MediaAttachmentRefDto>,
        existing_trash: Vec<MediaTrashEntryDto>,
        now_ms: Option<u64>,
        recovery_window_ms: u64,
    ) -> Result<MediaOrphanSweepResultDto, EngineError> {
        media_ffi::ffi_media_orphan_sweep(
            &media_root,
            committed,
            refs,
            existing_trash,
            now_ms,
            recovery_window_ms,
        )
    }

    /// Dark-build archive v2 export (path-only).
    ///
    /// # Errors
    ///
    /// Archive export errors.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned String for foreign callers"
    )]
    pub fn archive_export(
        &self,
        workspace_root: String,
        archive_path: String,
    ) -> Result<ArchiveExportResultDto, EngineError> {
        media_ffi::ffi_archive_export(&workspace_root, &archive_path)
    }

    /// Dark-build archive inspect into staging (does not touch live).
    ///
    /// # Errors
    ///
    /// Archive inspect errors.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned String for foreign callers"
    )]
    pub fn archive_inspect(
        &self,
        archive_path: String,
        staging_root: String,
    ) -> Result<ArchiveInspectResultDto, EngineError> {
        media_ffi::ffi_archive_inspect(&archive_path, &staging_root)
    }

    /// Dark-build archive import (inspect alias) into staging.
    ///
    /// # Errors
    ///
    /// Archive import errors.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned String for foreign callers"
    )]
    pub fn archive_import(
        &self,
        archive_path: String,
        staging_root: String,
    ) -> Result<ArchiveInspectResultDto, EngineError> {
        media_ffi::ffi_archive_import(&archive_path, &staging_root)
    }

    /// Dark-build atomic archive activate.
    ///
    /// # Errors
    ///
    /// Activate validation/storage errors.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned String for foreign callers"
    )]
    pub fn archive_activate(
        &self,
        staging_root: String,
        live_root: String,
        backup_root: String,
    ) -> Result<(), EngineError> {
        media_ffi::ffi_archive_activate(&staging_root, &live_root, &backup_root)
    }

    /// Dark-build import → activate → rebuild on activated live root.
    ///
    /// # Errors
    ///
    /// Import, activate, or rebuild errors.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned String for foreign callers"
    )]
    pub fn archive_import_activate_rebuild(
        &self,
        archive_path: String,
        staging_root: String,
        live_root: String,
        backup_root: String,
        batch_size: u32,
    ) -> Result<StoreRebuildResult, EngineError> {
        media_ffi::ffi_archive_import_activate_rebuild(
            &archive_path,
            &staging_root,
            &live_root,
            &backup_root,
            batch_size,
        )
    }

    fn store_handle(&self) -> Result<&StoreHandle, EngineError> {
        self.store.as_ref().ok_or_else(|| {
            EngineError::from(
                match core::LomoError::from_platform_boundary(
                    core::ErrorCategory::Validation,
                    "store_unavailable",
                    core::RetryDisposition::Never,
                    None,
                    None,
                    "store dark-build surface requires a Direct workspace",
                ) {
                    Ok(error) | Err(error) => error,
                },
            )
        })
    }
}

fn workspace_reminder_to_ffi(value: workspace::ReminderReference) -> WorkspaceReminderReference {
    WorkspaceReminderReference {
        opaque_id: value.opaque_id,
        revision: value.revision,
        memo_identity: value.memo_identity,
        source_start: value.source_start,
        source_end: value.source_end,
        token_fingerprint: value.token_fingerprint,
        token: value.token,
        due_at_local: value.due_at_local,
        repeat_count: value.repeat_count,
        fired_count: value.fired_count,
        done: value.done,
        interval_minutes: value.interval_minutes,
        recurrence_code: value.recurrence_code,
    }
}

fn workspace_reminder_from_ffi(value: WorkspaceReminderReference) -> workspace::ReminderReference {
    workspace::ReminderReference {
        opaque_id: value.opaque_id,
        revision: value.revision,
        memo_identity: value.memo_identity,
        source_start: value.source_start,
        source_end: value.source_end,
        token_fingerprint: value.token_fingerprint,
        token: value.token,
        due_at_local: value.due_at_local,
        repeat_count: value.repeat_count,
        fired_count: value.fired_count,
        done: value.done,
        interval_minutes: value.interval_minutes,
        recurrence_code: value.recurrence_code,
    }
}

#[doc(hidden)]
pub fn workspace_from_ffi(
    value: WorkspaceDescriptor,
) -> Result<core::WorkspaceDescriptor, EngineError> {
    match value {
        WorkspaceDescriptor::Direct { root_path } => core::WorkspaceDescriptor::direct(root_path),
        WorkspaceDescriptor::Saf { capability_token } => {
            core::CapabilityToken::parse(&capability_token).map(core::WorkspaceDescriptor::saf)
        }
    }
    .map_err(EngineError::from)
}

#[doc(hidden)]
pub fn result_from_ffi(
    value: PlatformBatchResult,
) -> Result<core::PlatformBatchResult, EngineError> {
    let job_id = core::JobId::parse(&value.job_id).map_err(EngineError::from)?;
    let batch_id = core::BatchId::parse(&value.batch_id).map_err(EngineError::from)?;
    let action_results = value
        .action_results
        .into_iter()
        .map(action_result_from_ffi)
        .collect::<Result<Vec<_>, _>>()?;
    Ok(core::PlatformBatchResult::new(
        value.schema_version,
        job_id,
        batch_id,
        value.attempt,
        action_results,
    ))
}

#[doc(hidden)]
pub fn action_result_from_ffi(value: ActionResult) -> Result<core::ActionResult, EngineError> {
    let action_id = core::ActionId::parse(&value.action_id).map_err(EngineError::from)?;
    let outcome = match value.outcome {
        ActionOutcome::Applied { output } => core::ActionOutcome::Applied(output_from_ffi(output)?),
        ActionOutcome::AlreadySatisfied { output } => {
            core::ActionOutcome::AlreadySatisfied(output_from_ffi(output)?)
        }
        ActionOutcome::Failed { failure } => {
            core::ActionOutcome::Failed(failure_to_core(&failure)?)
        }
    };
    Ok(core::ActionResult::new(action_id, outcome))
}

#[doc(hidden)]
pub fn output_from_ffi(
    value: PlatformActionOutput,
) -> Result<core::PlatformActionOutput, EngineError> {
    let output = match value {
        PlatformActionOutput::Stat { metadata } => core::PlatformActionOutput::Stat {
            metadata: metadata_from_ffi(metadata)?,
        },
        PlatformActionOutput::Listed { page } => core::PlatformActionOutput::Listed {
            page: metadata_page_from_ffi(page)?,
        },
        PlatformActionOutput::DirectoryReady { metadata } => {
            core::PlatformActionOutput::DirectoryReady {
                metadata: metadata_from_ffi(metadata)?,
            }
        }
        PlatformActionOutput::ReadToExchange {
            source_metadata,
            artifact,
        } => core::PlatformActionOutput::ReadToExchange {
            source_metadata: metadata_from_ffi(source_metadata)?,
            artifact: artifact_from_ffi(&artifact)?,
        },
        PlatformActionOutput::WriteComplete { metadata } => {
            core::PlatformActionOutput::WriteComplete {
                metadata: metadata_from_ffi(metadata)?,
            }
        }
        PlatformActionOutput::MoveComplete { metadata } => {
            core::PlatformActionOutput::MoveComplete {
                metadata: metadata_from_ffi(metadata)?,
            }
        }
        PlatformActionOutput::DeleteComplete { absence } => {
            core::PlatformActionOutput::DeleteComplete {
                absence: core::VerifiedAbsence::new(
                    target_from_ffi(absence.target)?,
                    &absence.fingerprint,
                )
                .map_err(EngineError::from)?,
            }
        }
    };
    Ok(output)
}

#[doc(hidden)]
pub fn metadata_from_ffi(value: DocumentMetadata) -> Result<core::DocumentMetadata, EngineError> {
    core::DocumentMetadata::new(
        target_from_ffi(value.target)?,
        match value.kind {
            DocumentKind::File => core::DocumentKind::File,
            DocumentKind::Directory => core::DocumentKind::Directory,
        },
        value.mime_type.as_deref(),
        evidence_from_ffi(&value.evidence)?,
    )
    .map_err(EngineError::from)
}

#[doc(hidden)]
pub fn metadata_page_from_ffi(value: MetadataPage) -> Result<core::MetadataPage, EngineError> {
    let items = value
        .items
        .into_iter()
        .map(metadata_from_ffi)
        .collect::<Result<Vec<_>, _>>()?;
    core::MetadataPage::new(items, value.next_cursor.as_deref()).map_err(EngineError::from)
}

#[doc(hidden)]
pub fn artifact_from_ffi(value: &ExchangeArtifact) -> Result<core::ExchangeArtifact, EngineError> {
    core::ExchangeArtifact::new(
        &value.token,
        value.length,
        core::Sha256Digest::parse(&value.digest).map_err(EngineError::from)?,
    )
    .map_err(EngineError::from)
}

#[doc(hidden)]
pub fn target_from_ffi(value: WorkspaceTarget) -> Result<core::WorkspaceTarget, EngineError> {
    match value {
        WorkspaceTarget::Root => Ok(core::WorkspaceTarget::Root),
        WorkspaceTarget::Relative { path } => core::RelativeWorkspacePath::parse(&path)
            .map(core::WorkspaceTarget::Relative)
            .map_err(EngineError::from),
    }
}

#[doc(hidden)]
pub fn evidence_from_ffi(value: &ActionEvidence) -> Result<core::ActionEvidence, EngineError> {
    let digest = core::Sha256Digest::parse(&value.digest).map_err(EngineError::from)?;
    core::ActionEvidence::verified(value.length, digest, &value.fingerprint)
        .map_err(EngineError::from)
}

#[doc(hidden)]
pub fn failure_to_core(value: &EngineFailure) -> Result<core::LomoError, EngineError> {
    core::LomoError::from_platform_boundary(
        category_from_name(&value.category)?,
        &value.code,
        retry_from_name(&value.retry_disposition)?,
        value.operation_id.as_deref(),
        value.job_id.as_deref(),
        &value.diagnostic,
    )
    .map_err(EngineError::from)
}

#[doc(hidden)]
pub fn category_from_name(value: &str) -> Result<core::ErrorCategory, EngineError> {
    let category = match value {
        "validation" => core::ErrorCategory::Validation,
        "permission" => core::ErrorCategory::Permission,
        "corruption" => core::ErrorCategory::Corruption,
        "storage" => core::ErrorCategory::Storage,
        "network" => core::ErrorCategory::Network,
        "authentication" => core::ErrorCategory::Authentication,
        "conflict" => core::ErrorCategory::Conflict,
        "cancelled" => core::ErrorCategory::Cancelled,
        "timeout" => core::ErrorCategory::Timeout,
        "busy" => core::ErrorCategory::Busy,
        "resource_limit" => core::ErrorCategory::ResourceLimit,
        "internal" => core::ErrorCategory::Internal,
        _ => return Err(invalid_platform_failure()),
    };
    Ok(category)
}

#[doc(hidden)]
pub fn retry_from_name(value: &str) -> Result<core::RetryDisposition, EngineError> {
    let retry = match value {
        "never" => core::RetryDisposition::Never,
        "after_user_action" => core::RetryDisposition::AfterUserAction,
        "transient" => core::RetryDisposition::Transient,
        _ => return Err(invalid_platform_failure()),
    };
    Ok(retry)
}

#[doc(hidden)]
#[must_use]
pub fn invalid_platform_failure() -> EngineError {
    EngineError::from(static_boundary_error(
        core::ErrorCategory::Validation,
        "invalid_platform_error",
        core::RetryDisposition::Never,
        None,
        "platform failure category or retry disposition is unknown",
    ))
}

#[doc(hidden)]
#[must_use]
pub fn state_to_ffi(value: core::EngineState) -> EngineState {
    match value {
        core::EngineState::AwaitingWorkspaceSelection => EngineState::AwaitingWorkspaceSelection,
        core::EngineState::Opening { job_id } => EngineState::Opening {
            job_id: job_id.as_str().to_owned(),
        },
        core::EngineState::Ready {
            core_revision,
            event_sequence,
        } => EngineState::Ready {
            core_revision: core_revision.get(),
            event_sequence: event_sequence.get(),
        },
        core::EngineState::ReadOnlyRecovery { error } => EngineState::ReadOnlyRecovery {
            failure: failure_from_core(&error),
        },
        core::EngineState::ShuttingDown => EngineState::ShuttingDown,
    }
}

#[doc(hidden)]
#[must_use]
pub fn job_step_to_ffi(value: core::JobStep) -> JobStep {
    match value {
        core::JobStep::Running => JobStep::Running,
        core::JobStep::NeedsPlatformBatch { batch } => JobStep::NeedsPlatformBatch {
            batch: batch_to_ffi(&batch),
        },
        core::JobStep::BlockedByConflict { error } => JobStep::BlockedByConflict {
            failure: failure_from_core(&error),
        },
        core::JobStep::Completed => JobStep::Completed,
        core::JobStep::Failed { error } => JobStep::Failed {
            failure: failure_from_core(&error),
        },
    }
}

#[doc(hidden)]
#[must_use]
pub fn batch_to_ffi(value: &core::PlatformActionBatch) -> PlatformActionBatch {
    PlatformActionBatch {
        schema_version: value.schema_version(),
        job_id: value.job_id().as_str().to_owned(),
        batch_id: value.batch_id().as_str().to_owned(),
        attempt: value.attempt(),
        deadline_epoch_millis: value.deadline_epoch_millis(),
        actions: value.actions().iter().map(action_to_ffi).collect(),
    }
}

#[doc(hidden)]
#[must_use]
pub fn action_to_ffi(value: &core::PlatformAction) -> PlatformAction {
    match value {
        core::PlatformAction::Stat {
            action_id,
            capability,
            target,
        } => PlatformAction::Stat {
            action_id: action_id.as_str().to_owned(),
            capability_token: capability.as_str().to_owned(),
            target: target_to_ffi(target),
        },
        core::PlatformAction::ListChildren {
            action_id,
            capability,
            target,
            cursor,
            page_size,
        } => PlatformAction::ListChildren {
            action_id: action_id.as_str().to_owned(),
            capability_token: capability.as_str().to_owned(),
            target: target_to_ffi(target),
            cursor: cursor.clone(),
            page_size: page_size.get(),
        },
        core::PlatformAction::EnsureDirectory {
            action_id,
            capability,
            path,
        } => PlatformAction::EnsureDirectory {
            action_id: action_id.as_str().to_owned(),
            capability_token: capability.as_str().to_owned(),
            path: path.as_str().to_owned(),
        },
        core::PlatformAction::ReadToExchange {
            action_id,
            capability,
            path,
            exchange_token,
            expected_source,
        } => PlatformAction::ReadToExchange {
            action_id: action_id.as_str().to_owned(),
            capability_token: capability.as_str().to_owned(),
            path: path.as_str().to_owned(),
            exchange_token: exchange_token.as_str().to_owned(),
            expected_source: expected_to_ffi(expected_source),
        },
        core::PlatformAction::WriteFromExchange {
            action_id,
            capability,
            artifact,
            path,
            mode,
            expected_target,
        } => PlatformAction::WriteFromExchange {
            action_id: action_id.as_str().to_owned(),
            capability_token: capability.as_str().to_owned(),
            artifact: artifact_to_ffi(artifact),
            path: path.as_str().to_owned(),
            mode: match mode {
                core::WriteMode::Create => WriteMode::Create,
                core::WriteMode::Replace => WriteMode::Replace,
            },
            expected_target: expected_to_ffi(expected_target),
        },
        core::PlatformAction::Move {
            action_id,
            capability,
            source,
            target,
            expected_source,
            expected_target,
        } => PlatformAction::Move {
            action_id: action_id.as_str().to_owned(),
            capability_token: capability.as_str().to_owned(),
            source: source.as_str().to_owned(),
            target: target.as_str().to_owned(),
            expected_source: expected_to_ffi(expected_source),
            expected_target: expected_to_ffi(expected_target),
        },
        core::PlatformAction::Delete {
            action_id,
            capability,
            path,
            expected_target,
        } => PlatformAction::Delete {
            action_id: action_id.as_str().to_owned(),
            capability_token: capability.as_str().to_owned(),
            path: path.as_str().to_owned(),
            expected_target: expected_to_ffi(expected_target),
        },
    }
}

#[doc(hidden)]
#[must_use]
pub fn target_to_ffi(value: &core::WorkspaceTarget) -> WorkspaceTarget {
    match value {
        core::WorkspaceTarget::Root => WorkspaceTarget::Root,
        core::WorkspaceTarget::Relative(path) => WorkspaceTarget::Relative {
            path: path.as_str().to_owned(),
        },
    }
}

#[doc(hidden)]
#[must_use]
pub fn expected_to_ffi(value: &core::ExpectedFingerprint) -> ExpectedFingerprint {
    match value {
        core::ExpectedFingerprint::Absent => ExpectedFingerprint::Absent,
        core::ExpectedFingerprint::Match(evidence) => ExpectedFingerprint::Match {
            evidence: evidence_to_ffi(evidence),
        },
    }
}

#[doc(hidden)]
#[must_use]
pub fn artifact_to_ffi(value: &core::ExchangeArtifact) -> ExchangeArtifact {
    ExchangeArtifact {
        token: value.token().as_str().to_owned(),
        length: value.length(),
        digest: value.digest().as_str().to_owned(),
    }
}

#[doc(hidden)]
#[must_use]
pub fn evidence_to_ffi(value: &core::ActionEvidence) -> ActionEvidence {
    ActionEvidence {
        length: value.length(),
        digest: value.digest().as_str().to_owned(),
        fingerprint: value.fingerprint().to_owned(),
    }
}

#[doc(hidden)]
#[must_use]
pub fn failure_from_core(value: &core::LomoError) -> EngineFailure {
    EngineFailure {
        category: category_name(value.category()).to_owned(),
        code: value.code().to_owned(),
        retry_disposition: retry_name(value.retry_disposition()).to_owned(),
        operation_id: value.operation_id().map(str::to_owned),
        job_id: value.job_id().map(str::to_owned),
        diagnostic: value.diagnostic().to_owned(),
    }
}

#[doc(hidden)]
#[must_use]
pub const fn cancel_to_ffi(value: core::CancelOutcome) -> CancelOutcome {
    match value {
        core::CancelOutcome::Accepted => CancelOutcome::Accepted,
        core::CancelOutcome::AlreadyCancelled => CancelOutcome::AlreadyCancelled,
        core::CancelOutcome::AlreadyCompleted => CancelOutcome::AlreadyCompleted,
        core::CancelOutcome::UnknownJob => CancelOutcome::UnknownJob,
    }
}

#[doc(hidden)]
#[must_use]
pub const fn shutdown_to_ffi(value: core::ShutdownOutcome) -> ShutdownOutcome {
    match value {
        core::ShutdownOutcome::Completed => ShutdownOutcome::Completed,
        core::ShutdownOutcome::DeadlineExceeded => ShutdownOutcome::DeadlineExceeded,
        core::ShutdownOutcome::AlreadyShutdown => ShutdownOutcome::AlreadyShutdown,
    }
}

impl From<core::LomoError> for EngineError {
    fn from(value: core::LomoError) -> Self {
        Self::Failure {
            failure: failure_from_core(&value),
        }
    }
}

#[doc(hidden)]
#[must_use]
pub const fn category_name(value: core::ErrorCategory) -> &'static str {
    match value {
        core::ErrorCategory::Validation => "validation",
        core::ErrorCategory::Permission => "permission",
        core::ErrorCategory::Corruption => "corruption",
        core::ErrorCategory::Storage => "storage",
        core::ErrorCategory::Network => "network",
        core::ErrorCategory::Authentication => "authentication",
        core::ErrorCategory::Conflict => "conflict",
        core::ErrorCategory::Cancelled => "cancelled",
        core::ErrorCategory::Timeout => "timeout",
        core::ErrorCategory::Busy => "busy",
        core::ErrorCategory::ResourceLimit => "resource_limit",
        core::ErrorCategory::Internal => "internal",
    }
}

#[doc(hidden)]
#[must_use]
pub const fn retry_name(value: core::RetryDisposition) -> &'static str {
    match value {
        core::RetryDisposition::Never => "never",
        core::RetryDisposition::AfterUserAction => "after_user_action",
        core::RetryDisposition::Transient => "transient",
    }
}

#[error]
#[derive(Debug)]
pub enum SyncPlannerError {
    Rejected { reason: String },
}

impl fmt::Display for SyncPlannerError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Rejected { reason } => {
                write!(
                    formatter,
                    "Rust sync planner rejected the request: {reason}"
                )
            }
        }
    }
}

impl std::error::Error for SyncPlannerError {}

fn render_document_to_ffi(
    document: &workspace::RenderDocumentV1,
) -> Result<RenderDocument, EngineError> {
    let mut nodes = Vec::with_capacity(document.node_count() as usize);
    flatten_render_blocks(document.blocks(), 1, &mut nodes);
    if nodes.len() != document.node_count() as usize {
        return Err(EngineError::from(static_boundary_error(
            core::ErrorCategory::Internal,
            "render_node_count_mismatch",
            core::RetryDisposition::Never,
            None,
            "typed render conversion must preserve the owner node count",
        )));
    }
    Ok(RenderDocument {
        schema_version: document.schema_version(),
        plain_text: document.plain_text().to_owned(),
        node_count: document.node_count(),
        tag_names: document.tag_names().to_vec(),
        attachment_destinations: document.attachment_destinations().to_vec(),
        nodes,
    })
}

fn flatten_render_blocks(
    blocks: &[workspace::RenderBlock],
    depth: u32,
    nodes: &mut Vec<RenderNode>,
) {
    for block in blocks {
        nodes.push(render_block_node(block, depth));
        match block {
            workspace::RenderBlock::Paragraph { inlines, .. }
            | workspace::RenderBlock::Heading { inlines, .. } => {
                flatten_render_inlines(inlines, depth.saturating_add(1), nodes);
            }
            workspace::RenderBlock::BlockQuote { blocks, .. } => {
                flatten_render_blocks(blocks, depth.saturating_add(1), nodes);
            }
            workspace::RenderBlock::List { items, .. } => {
                flatten_render_list_items(items, depth.saturating_add(1), nodes);
            }
            workspace::RenderBlock::Table { header, rows, .. } => {
                flatten_render_table(header, rows, depth.saturating_add(1), nodes);
            }
            workspace::RenderBlock::CodeBlock { .. }
            | workspace::RenderBlock::ThematicBreak { .. }
            | workspace::RenderBlock::HtmlBlock { .. } => {}
        }
    }
}

fn render_block_node(block: &workspace::RenderBlock, depth: u32) -> RenderNode {
    let (kind, span) = match block {
        workspace::RenderBlock::Paragraph { source_span, .. } => {
            (RenderNodeKind::Paragraph, *source_span)
        }
        workspace::RenderBlock::Heading { source_span, .. } => {
            (RenderNodeKind::Heading, *source_span)
        }
        workspace::RenderBlock::BlockQuote { source_span, .. } => {
            (RenderNodeKind::BlockQuote, *source_span)
        }
        workspace::RenderBlock::List { source_span, .. } => (RenderNodeKind::List, *source_span),
        workspace::RenderBlock::CodeBlock { source_span, .. } => {
            (RenderNodeKind::CodeBlock, *source_span)
        }
        workspace::RenderBlock::ThematicBreak { source_span } => {
            (RenderNodeKind::ThematicBreak, *source_span)
        }
        workspace::RenderBlock::Table { source_span, .. } => (RenderNodeKind::Table, *source_span),
        workspace::RenderBlock::HtmlBlock { source_span, .. } => {
            (RenderNodeKind::HtmlBlock, *source_span)
        }
    };
    let mut node = empty_render_node(kind, span, depth);
    match block {
        workspace::RenderBlock::Heading { level, .. } => node.level = Some(u32::from(*level)),
        workspace::RenderBlock::List { ordered, start, .. } => {
            node.ordered = Some(*ordered);
            node.list_start = Some(*start);
        }
        workspace::RenderBlock::CodeBlock {
            language, literal, ..
        } => {
            node.text = Some(literal.clone());
            node.title.clone_from(language);
        }
        workspace::RenderBlock::HtmlBlock { literal, .. } => node.text = Some(literal.clone()),
        workspace::RenderBlock::Paragraph { .. }
        | workspace::RenderBlock::BlockQuote { .. }
        | workspace::RenderBlock::ThematicBreak { .. }
        | workspace::RenderBlock::Table { .. } => {}
    }
    node
}

fn flatten_render_list_items(
    items: &[workspace::RenderListItem],
    depth: u32,
    nodes: &mut Vec<RenderNode>,
) {
    for item in items {
        let mut node = empty_render_node(RenderNodeKind::ListItem, item.source_span, depth);
        node.checked = item.checked;
        if let Some(action_span) = item.task_span {
            node.action_start = Some(action_span.start() as u64);
            node.action_end = Some(action_span.end() as u64);
        }
        nodes.push(node);
        flatten_render_blocks(&item.blocks, depth.saturating_add(1), nodes);
    }
}

fn flatten_render_table(
    header: &[workspace::RenderTableCell],
    rows: &[Vec<workspace::RenderTableCell>],
    depth: u32,
    nodes: &mut Vec<RenderNode>,
) {
    for cell in header {
        nodes.push(empty_render_node(
            RenderNodeKind::TableHeaderCell,
            cell.source_span,
            depth,
        ));
        flatten_render_inlines(&cell.inlines, depth.saturating_add(1), nodes);
    }
    for (row_index, row) in rows.iter().enumerate() {
        let Ok(row_index) = u32::try_from(row_index) else {
            panic!("render table row count was validated below the u32 boundary");
        };
        for cell in row {
            let mut node = empty_render_node(RenderNodeKind::TableCell, cell.source_span, depth);
            node.level = Some(row_index);
            nodes.push(node);
            flatten_render_inlines(&cell.inlines, depth.saturating_add(1), nodes);
        }
    }
}

fn flatten_render_inlines(
    inlines: &[workspace::RenderInline],
    depth: u32,
    nodes: &mut Vec<RenderNode>,
) {
    for inline in inlines {
        nodes.push(render_inline_node(inline, depth));
        match inline {
            workspace::RenderInline::Strong { children, .. }
            | workspace::RenderInline::Emphasis { children, .. }
            | workspace::RenderInline::Strikethrough { children, .. }
            | workspace::RenderInline::Highlight { children, .. }
            | workspace::RenderInline::Link { children, .. }
            | workspace::RenderInline::WikiReference { children, .. } => {
                flatten_render_inlines(children, depth.saturating_add(1), nodes);
            }
            workspace::RenderInline::Text { .. }
            | workspace::RenderInline::Code { .. }
            | workspace::RenderInline::Image { .. }
            | workspace::RenderInline::Tag { .. }
            | workspace::RenderInline::Reminder { .. }
            | workspace::RenderInline::SoftBreak { .. }
            | workspace::RenderInline::HardBreak { .. }
            | workspace::RenderInline::HtmlInline { .. } => {}
        }
    }
}

fn render_inline_node(inline: &workspace::RenderInline, depth: u32) -> RenderNode {
    let (kind, span) = render_inline_kind_and_span(inline);
    let mut node = empty_render_node(kind, span, depth);
    match inline {
        workspace::RenderInline::Text { text, .. }
        | workspace::RenderInline::Code { text, .. }
        | workspace::RenderInline::HtmlInline { text, .. } => node.text = Some(text.clone()),
        workspace::RenderInline::Link {
            destination, title, ..
        } => {
            node.destination = Some(destination.clone());
            node.title.clone_from(title);
        }
        workspace::RenderInline::Image {
            destination,
            title,
            alt,
            ..
        } => {
            node.text = Some(alt.clone());
            node.destination = Some(destination.clone());
            node.title.clone_from(title);
        }
        workspace::RenderInline::Tag { name, .. } => node.text = Some(name.clone()),
        workspace::RenderInline::Reminder { token, .. } => node.text = Some(token.clone()),
        workspace::RenderInline::WikiReference { target, .. } => {
            node.destination = Some(target.clone());
        }
        workspace::RenderInline::Strong { .. }
        | workspace::RenderInline::Emphasis { .. }
        | workspace::RenderInline::Strikethrough { .. }
        | workspace::RenderInline::Highlight { .. }
        | workspace::RenderInline::SoftBreak { .. }
        | workspace::RenderInline::HardBreak { .. } => {}
    }
    node
}

const fn render_inline_kind_and_span(
    inline: &workspace::RenderInline,
) -> (RenderNodeKind, workspace::ByteSpan) {
    match inline {
        workspace::RenderInline::Text { source_span, .. } => (RenderNodeKind::Text, *source_span),
        workspace::RenderInline::Strong { source_span, .. } => {
            (RenderNodeKind::Strong, *source_span)
        }
        workspace::RenderInline::Emphasis { source_span, .. } => {
            (RenderNodeKind::Emphasis, *source_span)
        }
        workspace::RenderInline::Strikethrough { source_span, .. } => {
            (RenderNodeKind::Strikethrough, *source_span)
        }
        workspace::RenderInline::Highlight { source_span, .. } => {
            (RenderNodeKind::Highlight, *source_span)
        }
        workspace::RenderInline::Code { source_span, .. } => (RenderNodeKind::Code, *source_span),
        workspace::RenderInline::Link { source_span, .. } => (RenderNodeKind::Link, *source_span),
        workspace::RenderInline::Image { source_span, .. } => (RenderNodeKind::Image, *source_span),
        workspace::RenderInline::Tag { source_span, .. } => (RenderNodeKind::Tag, *source_span),
        workspace::RenderInline::Reminder { source_span, .. } => {
            (RenderNodeKind::Reminder, *source_span)
        }
        workspace::RenderInline::WikiReference { source_span, .. } => {
            (RenderNodeKind::WikiReference, *source_span)
        }
        workspace::RenderInline::SoftBreak { source_span } => {
            (RenderNodeKind::SoftBreak, *source_span)
        }
        workspace::RenderInline::HardBreak { source_span } => {
            (RenderNodeKind::HardBreak, *source_span)
        }
        workspace::RenderInline::HtmlInline { source_span, .. } => {
            (RenderNodeKind::HtmlInline, *source_span)
        }
    }
}

const fn empty_render_node(
    kind: RenderNodeKind,
    span: workspace::ByteSpan,
    depth: u32,
) -> RenderNode {
    RenderNode {
        kind,
        source_start: span.start() as u64,
        source_end: span.end() as u64,
        depth,
        text: None,
        destination: None,
        title: None,
        level: None,
        ordered: None,
        list_start: None,
        checked: None,
        action_start: None,
        action_end: None,
    }
}

fn static_boundary_error(
    category: core::ErrorCategory,
    code: &'static str,
    retry: core::RetryDisposition,
    job_id: Option<&str>,
    diagnostic: &'static str,
) -> core::LomoError {
    match core::LomoError::from_platform_boundary(category, code, retry, None, job_id, diagnostic) {
        Ok(error) => error,
        Err(error) => panic!("invalid static native boundary error: {error}"),
    }
}

#[export]
/// Plans one sync v1 request through the native facade.
///
/// # Errors
///
/// Returns [`SyncPlannerError::Rejected`] when the core rejects the request envelope.
pub fn plan_sync_envelope(input: Vec<u8>) -> Result<Vec<u8>, SyncPlannerError> {
    let input = input.into_boxed_slice();
    plan_envelope(&input).map_err(|error| SyncPlannerError::Rejected {
        reason: error.to_string(),
    })
}

#[data]
#[derive(Clone, Debug)]
pub struct AttachmentNameMapping {
    pub original: String,
    pub stored: String,
}

#[export]
/// Remaps attachment destinations in free-content Markdown via the workspace owner.
///
/// # Errors
///
/// Returns structured engine validation errors when spans cannot be verified.
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned String / Vec wire types"
)]
pub fn remap_markdown_attachment_destinations(
    content: String,
    mappings: Vec<AttachmentNameMapping>,
) -> Result<String, EngineError> {
    let mut map = std::collections::BTreeMap::new();
    for mapping in mappings {
        map.insert(mapping.original, mapping.stored);
    }
    workspace::remap_attachment_destinations(&content, &map).map_err(EngineError::from)
}

#[data]
#[derive(Clone, Copy, Debug)]
pub enum ReminderTokenMutationKind {
    MarkDone,
    RecordFired,
}

#[data]
#[derive(Clone, Debug)]
pub struct ReminderTokenBuildRequest {
    pub due_at_local: String,
    pub repeat_count: u32,
    pub fired_count: u32,
    pub done: bool,
    pub interval_minutes: u32,
    pub recurrence_code: String,
}

#[export]
/// Constructs one canonical reminder token from typed owner facts.
///
/// # Errors
///
/// Returns validation when the composed token fails the strict stage-2 grammar.
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned request wire types"
)]
pub fn build_reminder_token(request: ReminderTokenBuildRequest) -> Result<String, EngineError> {
    workspace::build_reminder_token(
        &request.due_at_local,
        request.repeat_count,
        request.fired_count,
        request.done,
        request.interval_minutes,
        &request.recurrence_code,
    )
    .map_err(EngineError::from)
}

#[export]
/// Plans a Rust-canonical replacement token for mark-done / record-fired mutations.
///
/// # Errors
///
/// Returns validation when the current token is invalid or the mutation is not applicable.
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned String wire types"
)]
pub fn plan_reminder_token_mutation(
    current_token: String,
    mutation: ReminderTokenMutationKind,
) -> Result<String, EngineError> {
    let kind = match mutation {
        ReminderTokenMutationKind::MarkDone => workspace::ReminderTokenMutation::MarkDone,
        ReminderTokenMutationKind::RecordFired => workspace::ReminderTokenMutation::RecordFired,
    };
    workspace::plan_reminder_token_mutation(&current_token, kind).map_err(EngineError::from)
}

#[export]
/// Projects memo body text from raw header+body bytes via the workspace owner.
///
/// # Errors
///
/// Returns validation when the raw block is empty or not a unique single-memo projection.
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned String wire types"
)]
pub fn extract_memo_body_from_raw(raw: String) -> Result<String, EngineError> {
    workspace::extract_memo_body_from_raw(&raw).map_err(EngineError::from)
}

#[export]
/// Owner identity-keyed merge of two Lomo/Thino memo shards for sync conflict write-back.
///
/// Returns `None` when the owner declines (no shared identities / not `LomoThino` / preamble).
///
/// # Errors
///
/// Returns validation/corruption when either source fails owner parse constraints.
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned String wire types"
)]
pub fn merge_memo_shard_by_identity(
    local_text: String,
    remote_text: String,
    local_last_modified: Option<i64>,
    remote_last_modified: Option<i64>,
) -> Result<Option<String>, EngineError> {
    workspace::merge_memo_shard_by_identity(
        &local_text,
        &remote_text,
        local_last_modified,
        remote_last_modified,
    )
    .map_err(EngineError::from)
}
