#![deny(unsafe_code)]
// EngineError embeds EngineFailure by value because BoltFFI cannot encode Box in #[error] yet.
#![expect(
    clippy::result_large_err,
    reason = "BoltFFI #[error] variants cannot box EngineFailure; wire type stays inline"
)]

use std::{
    fmt,
    path::PathBuf,
    sync::{Arc, Mutex, MutexGuard},
    time::Duration,
};

use boltffi::{UnexpectedFfiCallbackError, data, error, export};
use lomo_core as core;
use lomo_workspace::{self as workspace, workspace_driver_registry};

// Public so BoltFFI type resolution can name `crate::media_ffi::*` wire DTOs from store_ffi.
pub mod lan_ffi;
mod lan_pump;
pub mod media_ffi;
mod session_ffi;
mod store_ffi;
pub mod sync_ffi;
pub use lan_ffi::{
    LanAttachmentDto, LanBatchPreviewDto, LanBatchRecoveryDto, LanBindCandidateDto,
    LanCommittableItemDto, LanCommittedReceivedItemDto, LanDeviceIdentityDto, LanDiscoveredPeerDto,
    LanDiscoverySnapshotDto, LanFailedReceivedItemDto, LanInboxWaitDto, LanLocalIdentityDto,
    LanNetworkSnapshotDto, LanOutgoingBatchDriveDto, LanOutgoingBatchDto, LanPairingChallengeDto,
    LanPairingTranscriptDto, LanPeerDto, LanPeerPageDto, LanPendingBatchDto,
    LanPendingReceivedItemDto, LanProtocolLimitsDto, LanReceivedBatchDecisionDto,
    LanReceivedBatchDriveDto, LanRuntimeInboxDto, LanSendItemDto, LanServicePhaseDto,
    LanServiceSnapshotDto, LanSessionChallengeDto, LanSessionPhaseDto, LanSessionSnapshotDto,
    LanTransferShapeDto,
};
pub use media_ffi::{
    ArchiveExportResultDto, ArchiveInspectResultDto, MediaCommittedEntryDto, MediaManifestDto,
    MediaPromotePlanDto, MediaSourceKind, MediaStageLeaseDto, MediaStageOwnerKindDto,
    MediaStageRecordDto, MediaStageReleaseDto, MediaStagedDto, MediaTrashEntryDto,
    pending_promotes_from_ffi,
};
pub use session_ffi::{
    SessionCivilDate, SessionCivilTime, SessionCreateMemoRequest, SessionDateCount,
    SessionDeleteMemoRequest, SessionDraftGuardDto, SessionHourCount, SessionMediaFailureDto,
    SessionMediaProtectionDto, SessionMediaSweepReportDto, SessionMemoView, SessionPinMemoRequest,
    SessionRestoreRequest, SessionRestoreRevisionRequest, SessionReviewCandidate, SessionSearchHit,
    SessionSearchMode, SessionSearchOutcome, SessionSearchPage, SessionSearchRequest,
    SessionStatistics, SessionStatisticsSnapshot, SessionTagCount, SessionTaskItem,
    SessionToggleTaskRequest, SessionUpdateMemoRequest, SessionWeeklyHourCount, batch_from_ffi,
    result_to_ffi,
};
pub use store_ffi::{
    StoreInvalidationScope, StoreMemoBatchCommit, StoreMemoBatchDelete, StoreMemoCommand,
    StoreMemoCommandKind, StoreMemoCommit, StoreMemoDeleteTarget, StoreMemoDeletedMemo,
    StoreMemoFilters, StoreMemoHistoryPage, StoreMemoHistoryRevision, StoreMemoPage,
    StoreMemoQuery, StoreMemoQueryBoundary, StoreMemoSnapshot, StoreMemoSort, StoreMemoSortField,
    StoreMemoStatisticsRow, StoreMemoSummary, StorePageCursor, StorePlannedAlarm,
    StoreRebuildResult, StoreReminderPlan, StoreSafHistoryProjectionReference,
    StoreSafMemoCreateBegin, StoreSafMemoCreateBeginResult, StoreSafMemoProjection,
    StoreSafMemoProjectionReference, StoreSafMemoRollbackResult, StoreSafTrashProjectionReference,
    StoreSidebarDateCount, StoreSidebarProjection, StoreSidebarTagCount, StoreSortDirection,
};
pub use sync_ffi::{
    SyncBackendConfigDto, SyncBackendProbeDto, SyncConflictPageDto, SyncConflictPathDto,
    SyncConflictPathStatusDto, SyncConflictResolutionDto, SyncConflictResolveResultDto,
    SyncConflictSessionStateDto, SyncConflictSuggestionDto, SyncCyclePlanSummaryDto,
    SyncCycleStatusDto, SyncRetryDispositionDto, SyncRetryHintDto, SyncSecretLeaseDto,
    looks_like_lease_id, sync_cycle_status, sync_issue_secret_lease, sync_list_conflicts,
    sync_probe_backend, sync_probe_secret_lease, sync_read_conflict_artifact, sync_request_cancel,
    sync_reset_control_tree, sync_resolve_conflicts, sync_revoke_secret_lease, sync_run_cycle,
    sync_suggest_conflict_resolution, sync_workspace_generation,
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
    pub fingerprint_ordinal: u32,
    /// Resolved durable embedded reminder id (`#<hex>` tail); `None` for legacy/ambiguous tokens.
    pub embedded_id: Option<String>,
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
#[expect(
    clippy::large_enum_variant,
    reason = "BoltFFI wire enums must stay flat; the payload is serialized across the boundary"
)]
pub enum WorkspaceDocumentCommandKind {
    Create {
        time_part: String,
        content: String,
    },
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
        identity: String,
        body_start: u64,
        body_end: u64,
    },
    RewriteReminder {
        reminder: WorkspaceReminderReference,
        replacement: String,
    },
}

#[data]
#[derive(Clone, Debug)]
pub enum WorkspaceDocumentExpectedState {
    Absent,
    Match { fingerprint: String },
}

#[data]
#[derive(Clone, Copy, Debug)]
pub struct WorkspaceDocumentHistoryWrite {
    pub revision: u64,
    pub created_at_ms: i64,
}

#[data]
#[derive(Clone, Debug)]
pub struct WorkspaceDocumentCommand {
    pub path: String,
    pub expected_state: WorkspaceDocumentExpectedState,
    pub command: WorkspaceDocumentCommandKind,
    pub history: Option<WorkspaceDocumentHistoryWrite>,
}

#[data]
#[derive(Clone, Debug)]
pub struct WorkspaceDocumentMemoFacts {
    pub path: String,
    pub identity: String,
    pub time_part: String,
    pub fingerprint: String,
    pub tags: Vec<String>,
    pub attachments: Vec<String>,
    pub reminders: Vec<WorkspaceReminderReference>,
    pub has_todo: bool,
    pub has_url: bool,
    /// Post-command memo body when the facts came from a parsed document; trash-record facts
    /// carry no body bytes and stay `None` instead of inventing empty content.
    pub content: Option<String>,
}

#[data]
#[derive(Clone, Debug)]
pub struct WorkspaceDocumentCommandResult {
    pub path: String,
    pub result_fingerprint: String,
    pub bytes_written: u64,
    pub affected_memo: Option<WorkspaceDocumentMemoFacts>,
}

#[data]
#[derive(Clone, Debug)]
pub enum WorkspaceTrashCommandKind {
    Trash {
        identity: String,
        chronology_epoch_ms: i64,
    },
    Restore {
        identity: String,
    },
    PermanentDelete {
        identity: String,
    },
}

#[data]
#[derive(Clone, Debug)]
pub struct WorkspaceTrashCommand {
    pub path: String,
    pub expected_fingerprint: String,
    pub command: WorkspaceTrashCommandKind,
}

#[data]
#[derive(Clone, Debug)]
pub struct WorkspaceTrashCommandResult {
    pub path: String,
    pub result_fingerprint: String,
    pub affected_memo: WorkspaceDocumentMemoFacts,
    pub trashed_at_ms: Option<i64>,
}

#[data]
#[derive(Clone, Debug)]
pub struct WorkspaceTrashScanRequest {
    pub page_size: u32,
    pub cursor: Option<String>,
}

#[data]
#[derive(Clone, Debug)]
pub struct WorkspaceTrashMemoSummary {
    pub memo_id: String,
    pub source_path: String,
    pub time_part: String,
    pub source_fingerprint: String,
    pub chronology_epoch_ms: i64,
    pub trashed_at_ms: i64,
    pub tags: Vec<String>,
    pub attachments: Vec<String>,
    pub reminders: Vec<WorkspaceReminderReference>,
    pub has_todo: bool,
    pub has_url: bool,
    pub content: WorkspaceMemoContentReference,
}

#[data]
#[derive(Clone, Debug)]
pub struct WorkspaceTrashScanPage {
    pub items: Vec<WorkspaceTrashMemoSummary>,
    pub next_cursor: Option<String>,
}

#[data]
#[derive(Clone, Debug)]
pub struct WorkspaceHistoryScanRequest {
    pub page_size: u32,
    pub cursor: Option<String>,
}

#[data]
#[derive(Clone, Debug)]
pub struct WorkspaceHistoryRevisionSummary {
    pub memo_id: String,
    pub revision: u64,
    pub created_at_ms: i64,
    pub file_fingerprint: String,
    pub content: WorkspaceMemoContentReference,
}

#[data]
#[derive(Clone, Debug)]
pub struct WorkspaceHistoryScanPage {
    pub items: Vec<WorkspaceHistoryRevisionSummary>,
    pub next_cursor: Option<String>,
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
    Direct {
        root_path: String,
        capability_token: String,
    },
    Saf {
        stable_workspace_id: String,
        capability_token: String,
    },
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
pub enum ContentDigest {
    Unknown,
    Verified { hex: String },
}

#[data]
#[derive(Clone, Debug)]
pub struct ActionEvidence {
    pub length: u64,
    pub digest: ContentDigest,
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

/// A durable staged artifact the host streams without routing its bytes through a plan.
/// `path` names host-private staging; `digest` and `length` are re-verified while streaming.
#[data]
#[derive(Clone, Debug)]
pub struct StagedArtifactSource {
    pub path: String,
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
        document_handle: Option<String>,
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
    /// Streams a retained staged artifact to `path`, re-verifying digest and length during the
    /// transfer. A target already holding the declared digest is satisfaction, not conflict.
    ArtifactWrite {
        action_id: String,
        capability_token: String,
        source: StagedArtifactSource,
        path: String,
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
    pub document_handle: String,
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
    NeedsPlatformBatch {
        batch: PlatformActionBatch,
    },
    /// Actor-external native task is queued or running outside the writer (dispatch fence active).
    RunningNative {
        task_kind: String,
        attempt: u32,
        dispatch_generation: u64,
    },
    BlockedByConflict {
        failure: EngineFailure,
    },
    Completed,
    Failed {
        failure: EngineFailure,
    },
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

/// Synchronous platform-action host injected into the application session.
///
/// Kotlin SAF (and host POSIX tests) execute the batch on the calling thread and return verified
/// results. The native facade performs conversion only.
#[export]
pub trait PlatformBatchHost: Send + Sync {
    /// Executes one verified platform-action batch.
    ///
    /// # Errors
    ///
    /// Returns a structured engine error when the host cannot execute the batch or the result
    /// cannot be represented at the FFI boundary.
    fn execute(&self, batch: PlatformActionBatch) -> Result<PlatformBatchResult, EngineError>;
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

pub struct LomoEngine {
    pub(crate) core: Arc<core::LomoEngine>,
    lan: Arc<Mutex<lomo_lan::LanServiceManager>>,
    /// Reusable outbound session channels; socket waits live here, never under `lan`.
    lan_pool: lomo_lan::LanConnectionPool,
    lan_pump: lan_pump::LanInboxPump,
    pub(crate) control_root: PathBuf,
    pub(crate) workspace: Option<core::WorkspaceDescriptor>,
    pub(crate) session: Mutex<Option<Arc<lomo_application::WorkspaceSession>>>,
}

impl fmt::Debug for LomoEngine {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        let has_session = match self.session.lock() {
            Ok(guard) => guard.is_some(),
            Err(_poison) => false,
        };
        formatter
            .debug_struct("LomoEngine")
            .field("state", &self.core.state())
            .field("has_session", &has_session)
            .finish_non_exhaustive()
    }
}

/// FFI export of the sole event-sequence gap law. Kotlin must not reimplement this predicate.
#[export]
#[must_use]
pub const fn event_sequence_requires_full_invalidate(last_seen: u64, incoming: u64) -> bool {
    core::event_sequence_requires_full_invalidate(
        core::EventSequence::from_raw(last_seen),
        core::EventSequence::from_raw(incoming),
    )
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
        let lan = lomo_lan::LanServiceManager::open(&control_root).map_err(EngineError::from)?;
        let workspace = config.workspace.map(workspace_from_ffi).transpose()?;
        let core_config = core::EngineConfig::new(
            control_root.clone(),
            PathBuf::from(config.exchange_root),
            workspace.clone(),
        )
        .and_then(|config| config.with_bootstrap_deadline(bootstrap_deadline))
        .map(|config| config.with_drivers(workspace_driver_registry()))
        .map_err(EngineError::from)?;
        let lan = Arc::new(Mutex::new(lan));
        let lan_pump = lan_pump::LanInboxPump::new(Arc::clone(&lan));
        let core = core::LomoEngine::open(core_config).map_err(EngineError::from)?;
        Ok(Self {
            core,
            lan,
            lan_pool: lomo_lan::LanConnectionPool::default(),
            lan_pump,
            control_root,
            workspace,
            session: Mutex::new(None),
        })
    }

    #[must_use]
    pub fn state(&self) -> EngineState {
        state_to_ffi(self.core.state())
    }

    /// Returns the Rust-owned payload coordinates used by the thin Android streaming adapter.
    #[must_use]
    pub fn lan_transfer_shape(&self) -> LanTransferShapeDto {
        lan_ffi::transfer_shape_to_ffi()
    }

    /// Returns protocol version and lifetimes owned by `lomo-lan`. Kotlin displays remaining time.
    #[must_use]
    pub fn lan_protocol_limits(&self) -> LanProtocolLimitsDto {
        lan_ffi::protocol_limits_to_ffi()
    }

    /// Publishes bounded, monotonic Android network facts to the Rust LAN owner.
    ///
    /// # Errors
    ///
    /// Validation/resource-limit from the conversion edge or runtime.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned wire DTOs for foreign callers"
    )]
    pub fn update_lan_network_snapshot(
        &self,
        snapshot: LanNetworkSnapshotDto,
    ) -> Result<(), EngineError> {
        let parsed = lan_ffi::network_snapshot_from_ffi(&snapshot).map_err(EngineError::from)?;
        self.lan_manager()?
            .update_network(parsed)
            .map_err(EngineError::from)
    }

    /// Publishes bounded, monotonic NSD facts to the Rust LAN owner.
    ///
    /// # Errors
    ///
    /// Validation/resource-limit from the conversion edge or runtime.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned wire DTOs for foreign callers"
    )]
    pub fn update_lan_discovery_snapshot(
        &self,
        snapshot: LanDiscoverySnapshotDto,
    ) -> Result<(), EngineError> {
        let parsed = lan_ffi::discovery_snapshot_from_ffi(&snapshot).map_err(EngineError::from)?;
        self.lan_manager()?
            .update_discovery(parsed)
            .map_err(EngineError::from)
    }

    /// Starts the sole Rust-owned LAN listener.
    ///
    /// # Errors
    ///
    /// Permission/network/lifecycle errors from `lomo-lan`.
    pub fn start_lan_service(&self) -> Result<LanServiceSnapshotDto, EngineError> {
        let snapshot = self.lan_manager()?.start().map_err(EngineError::from)?;
        if let Err(error) = self.lan_pump.start() {
            let _stopped = self.lan_manager()?.stop();
            return Err(error);
        }
        Ok(lan_ffi::service_snapshot_to_ffi(&snapshot))
    }

    /// Stops and releases the sole Rust-owned LAN listener.
    ///
    /// # Errors
    ///
    /// Internal when the lifecycle lock was poisoned by a prior panic.
    pub fn stop_lan_service(&self) -> Result<LanServiceSnapshotDto, EngineError> {
        self.lan_pump.stop();
        self.lan_pool.close_all();
        let snapshot = self.lan_manager()?.stop();
        Ok(lan_ffi::service_snapshot_to_ffi(&snapshot))
    }

    /// Lists the validated v2 discovery facts currently owned by Rust.
    ///
    /// # Errors
    ///
    /// Internal when the lifecycle lock was poisoned by a prior panic.
    pub fn list_lan_discovered_peers(&self) -> Result<Vec<LanDiscoveredPeerDto>, EngineError> {
        Ok(self
            .lan_manager()?
            .discovered_peers()
            .iter()
            .map(lan_ffi::discovered_peer_to_ffi)
            .collect())
    }

    /// Installs the public half of the Android Keystore identity used by LAN pairing.
    ///
    /// The private key never crosses `BoltFFI`; Rust receives only the validated public key and
    /// display name that it binds into every pairing transcript.
    ///
    /// # Errors
    ///
    /// Validation for malformed identity facts; conflict if identity changes while open.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned wire DTOs for foreign callers"
    )]
    pub fn configure_lan_identity(
        &self,
        identity: LanDeviceIdentityDto,
    ) -> Result<LanLocalIdentityDto, EngineError> {
        let (public_key, display_name) =
            lan_ffi::identity_from_ffi(&identity).map_err(EngineError::from)?;
        let local_identity = LanLocalIdentityDto {
            device_id: lomo_lan::DeviceId::derive(&public_key).as_str().to_owned(),
            display_name: display_name.as_str().to_owned(),
        };
        self.lan_manager()?
            .configure_identity(public_key, display_name)
            .map_err(EngineError::from)?;
        Ok(local_identity)
    }

    /// Starts pairing with a v2 NSD endpoint already accepted into the Rust discovery snapshot.
    ///
    /// # Errors
    ///
    /// Validation for an unknown endpoint or invalid TTL; protocol/network/authentication from
    /// the Rust pairing exchange.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned strings for foreign callers"
    )]
    pub fn begin_lan_pairing(
        &self,
        peer_device_id: String,
        now_ms: i64,
        ttl_ms: i64,
    ) -> Result<LanPairingChallengeDto, EngineError> {
        let parsed_id = lomo_lan::DeviceId::parse(&peer_device_id).map_err(EngineError::from)?;
        let mut manager = self.lan_manager()?;
        let peer = manager
            .discovered_peers()
            .iter()
            .find(|candidate| candidate.device_id() == &parsed_id)
            .cloned()
            .ok_or_else(|| {
                EngineError::from(lomo_lan::lan_validation(
                    "lan_discovered_peer_missing",
                    "pairing requires a v2 endpoint from the current discovery snapshot",
                ))
            })?;
        if ttl_ms != lomo_lan::PAIRING_TTL_MS {
            return Err(EngineError::from(lomo_lan::lan_validation(
                "lan_pairing_ttl_invalid",
                "pairing time-to-live is owned by lomo-lan",
            )));
        }
        let exchange = manager
            .plan_pairing(&peer, now_ms, lomo_lan::PAIRING_TTL_MS)
            .map_err(EngineError::from)?;
        drop(manager);
        // The blocking hello exchange runs off-lock; the answered accept applies under the
        // lock again so no socket wait can stall unrelated control-plane reads.
        let reply = exchange.exchange().map_err(EngineError::from)?;
        self.lan_manager()?
            .apply_pairing_exchange(exchange, &reply)
            .map(|challenge| lan_ffi::pairing_challenge_to_ffi(&challenge))
            .map_err(EngineError::from)
    }

    /// Waits until the listener pump advances the inbox generation or `timeout_ms` elapses.
    ///
    /// # Errors
    ///
    /// Lifecycle when the pump lock is poisoned; network/validation when a pumped frame fails.
    pub fn await_lan_inbox(
        &self,
        last_generation: u64,
        timeout_ms: u64,
    ) -> Result<LanInboxWaitDto, EngineError> {
        let generation = self
            .lan_pump
            .await_generation(last_generation, Duration::from_millis(timeout_ms))?;
        let inbox = if generation > last_generation {
            let inbox = self
                .lan_manager()?
                .inbox(lan_pump::unix_now_ms())
                .map_err(EngineError::from)?;
            Some(lan_ffi::runtime_inbox_to_ffi(&inbox))
        } else {
            None
        };
        let (rejected_connection_count, last_rejection_diagnostic) =
            self.lan_pump.rejection_stats();
        Ok(LanInboxWaitDto {
            generation,
            inbox,
            rejected_connection_count,
            last_rejection_diagnostic,
        })
    }

    /// Returns the challenge that awaits the platform Keystore signature.
    ///
    /// # Errors
    ///
    /// Validation for a malformed or non-pending pairing id.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned strings for foreign callers"
    )]
    pub fn lan_pairing_challenge(
        &self,
        pairing_id: String,
    ) -> Result<LanPairingChallengeDto, EngineError> {
        let parsed = lan_ffi::pairing_id_from_ffi(&pairing_id).map_err(EngineError::from)?;
        self.lan_manager()?
            .pairing_challenge(&parsed)
            .map(|challenge| lan_ffi::pairing_challenge_to_ffi(&challenge))
            .ok_or_else(|| {
                EngineError::from(lomo_lan::lan_validation(
                    "lan_pairing_unknown",
                    "pairing is not pending",
                ))
            })
    }

    /// Submits only the platform signature over the Rust-owned challenge transcript.
    ///
    /// # Errors
    ///
    /// Validation/authentication for a malformed id or signature, permission after expiry,
    /// network on delivery, or storage when completed trust cannot be journaled.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned signature bytes for foreign callers"
    )]
    pub fn confirm_lan_pairing(
        &self,
        pairing_id: String,
        signature: Vec<u8>,
        now_ms: i64,
    ) -> Result<(), EngineError> {
        let parsed = lan_ffi::pairing_id_from_ffi(&pairing_id).map_err(EngineError::from)?;
        let send = self
            .lan_manager()?
            .plan_pairing_confirm(&parsed, &signature, now_ms)
            .map_err(EngineError::from)?;
        send.deliver().map_err(EngineError::from)?;
        self.lan_manager()?
            .apply_pairing_confirmed(&parsed, now_ms)
            .map_err(EngineError::from)?;
        self.lan_pump.bump();
        Ok(())
    }

    /// Discards one pending pairing after the user rejects the short code.
    ///
    /// # Errors
    ///
    /// Validation for a malformed or non-pending pairing identity.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned strings for foreign callers"
    )]
    pub fn decline_lan_pairing(&self, pairing_id: String) -> Result<(), EngineError> {
        let parsed = lan_ffi::pairing_id_from_ffi(&pairing_id).map_err(EngineError::from)?;
        self.lan_manager()?
            .decline_pairing(&parsed)
            .map_err(EngineError::from)?;
        self.lan_pump.bump();
        Ok(())
    }

    /// Opens a fresh mutually authenticated session with a trusted discovered peer.
    ///
    /// # Errors
    ///
    /// Validation for an unknown endpoint/id/TTL; authentication for untrusted or revoked peers;
    /// network/crypto errors from the Rust-owned hello/accept exchange.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned strings for foreign callers"
    )]
    pub fn begin_lan_session(
        &self,
        peer_device_id: String,
        now_ms: i64,
        ttl_ms: i64,
    ) -> Result<LanSessionChallengeDto, EngineError> {
        let parsed_id = lomo_lan::DeviceId::parse(&peer_device_id).map_err(EngineError::from)?;
        let mut manager = self.lan_manager()?;
        let peer = manager
            .discovered_peers()
            .iter()
            .find(|candidate| candidate.device_id() == &parsed_id)
            .cloned()
            .ok_or_else(|| {
                EngineError::from(lomo_lan::lan_validation(
                    "lan_discovered_peer_missing",
                    "session requires a v2 endpoint from the current discovery snapshot",
                ))
            })?;
        if ttl_ms != lomo_lan::SESSION_TTL_MS {
            return Err(EngineError::from(lomo_lan::lan_validation(
                "lan_session_ttl_invalid",
                "session time-to-live is owned by lomo-lan",
            )));
        }
        let exchange = manager
            .plan_session(&peer, now_ms, lomo_lan::SESSION_TTL_MS)
            .map_err(EngineError::from)?;
        drop(manager);
        // The blocking hello exchange runs off-lock; the answered accept applies under the
        // lock again so no socket wait can stall unrelated control-plane reads.
        let reply = exchange.exchange().map_err(EngineError::from)?;
        self.lan_manager()?
            .apply_session_exchange(exchange, &reply)
            .map(|challenge| lan_ffi::session_challenge_to_ffi(&challenge))
            .map_err(EngineError::from)
    }

    /// Submits only the platform signature over the Rust-owned session transcript.
    ///
    /// # Errors
    ///
    /// Validation/authentication for malformed input, permission after expiry, network on
    /// delivery, or storage when the replay identity cannot be journaled.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned strings and signature bytes"
    )]
    pub fn confirm_lan_session(
        &self,
        session_id: String,
        signature: Vec<u8>,
        now_ms: i64,
    ) -> Result<(), EngineError> {
        let parsed = lan_ffi::session_id_from_ffi(&session_id).map_err(EngineError::from)?;
        let send = self
            .lan_manager()?
            .plan_session_confirm(&parsed, &signature, now_ms)
            .map_err(EngineError::from)?;
        send.deliver().map_err(EngineError::from)?;
        self.lan_manager()?
            .apply_session_confirmed(&parsed, now_ms)
            .map_err(EngineError::from)?;
        self.lan_pump.bump();
        Ok(())
    }

    /// Returns public state for an authenticated session; pending state is not invented.
    ///
    /// # Errors
    ///
    /// Validation for a malformed or unauthenticated session id.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned strings for foreign callers"
    )]
    pub fn lan_session_snapshot(
        &self,
        session_id: String,
    ) -> Result<LanSessionSnapshotDto, EngineError> {
        let parsed = lan_ffi::session_id_from_ffi(&session_id).map_err(EngineError::from)?;
        self.lan_manager()?
            .session_snapshot(&parsed)
            .map(lan_ffi::session_snapshot_to_ffi)
            .ok_or_else(|| {
                EngineError::from(lomo_lan::lan_validation(
                    "lan_session_unknown",
                    "session is not authenticated",
                ))
            })
    }

    /// Validates and sends one batch plan through the authenticated Rust-owned session.
    ///
    /// # Errors
    ///
    /// Validation/resource-limit at the DTO edge, authentication for an inactive session, or
    /// network errors while delivering the prepare control frame.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned strings and item DTOs"
    )]
    pub fn prepare_lan_batch(
        &self,
        session_id: String,
        batch_id: String,
        items: Vec<LanSendItemDto>,
    ) -> Result<(), EngineError> {
        self.lan_workspace_root()?;
        let parsed_session =
            lan_ffi::session_id_from_ffi(&session_id).map_err(EngineError::from)?;
        let plan = lan_ffi::batch_plan_from_ffi(&batch_id, &items).map_err(EngineError::from)?;
        let now_ms = lan_pump::unix_now_ms();
        let exchange = self
            .lan_manager()?
            .plan_batch_prepare(&parsed_session, plan, now_ms)
            .map_err(EngineError::from)?;
        // The durable outgoing record committed during planning; only the blocking prepare
        // exchange runs off-lock, then the sealed reply applies under the lock again.
        let reply = exchange.exchange().map_err(EngineError::from)?;
        self.lan_manager()?
            .apply_batch_prepare_reply(&exchange, &reply, now_ms)
            .map_err(EngineError::from)?;
        self.lan_pump.bump();
        Ok(())
    }

    /// Captures the active Rust workspace generation and notifies the sender.
    ///
    /// # Errors
    ///
    /// Validation for malformed input, permission for session/batch mismatch, conflict for a
    /// terminal decision, storage before notification, or network on delivery.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned strings for foreign callers"
    )]
    pub fn approve_lan_batch(
        &self,
        session_id: String,
        batch_id: String,
        now_ms: i64,
        ttl_ms: i64,
    ) -> Result<(), EngineError> {
        let generation = workspace::load_workspace_generation(&self.lan_workspace_root()?)
            .map(|generation| generation.as_str().to_owned())
            .map_err(EngineError::from)?;
        let parsed_session =
            lan_ffi::session_id_from_ffi(&session_id).map_err(EngineError::from)?;
        let parsed_batch = lomo_lan::LanBatchId::parse(&batch_id).map_err(EngineError::from)?;
        let generation =
            lomo_lan::ApprovedGeneration::capture(&generation).map_err(EngineError::from)?;
        if ttl_ms != lomo_lan::APPROVAL_TTL_MS {
            return Err(EngineError::from(lomo_lan::lan_validation(
                "lan_approval_ttl_invalid",
                "approval time-to-live is owned by lomo-lan",
            )));
        }
        let send = self
            .lan_manager()?
            .plan_batch_approve(
                &parsed_session,
                &parsed_batch,
                generation,
                now_ms,
                lomo_lan::APPROVAL_TTL_MS,
            )
            .map_err(EngineError::from)?;
        // The durable approval committed during planning; only delivery touches the socket,
        // off-lock.
        send.deliver().map_err(EngineError::from)?;
        self.lan_pump.bump();
        Ok(())
    }

    /// Persists and sends one terminal batch rejection.
    ///
    /// # Errors
    ///
    /// Validation for malformed input, permission for session/batch mismatch, conflict for a
    /// terminal decision, storage before notification, or network on delivery.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned strings for foreign callers"
    )]
    pub fn reject_lan_batch(
        &self,
        session_id: String,
        batch_id: String,
        rejected_at_ms: i64,
    ) -> Result<(), EngineError> {
        let parsed_session =
            lan_ffi::session_id_from_ffi(&session_id).map_err(EngineError::from)?;
        let parsed_batch = lomo_lan::LanBatchId::parse(&batch_id).map_err(EngineError::from)?;
        let send = self
            .lan_manager()?
            .plan_batch_reject(&parsed_session, &parsed_batch, rejected_at_ms)
            .map_err(EngineError::from)?;
        // The durable rejection committed during planning; only delivery touches the socket,
        // off-lock.
        send.deliver().map_err(EngineError::from)?;
        self.lan_pump.bump();
        Ok(())
    }

    /// Sends up to `lan_transfer_shape().max_inflight_chunks` approved plaintext chunks through
    /// the Rust-owned AEAD/wire/ACK state machine.
    ///
    /// Phases: validate+seal plans under the manager lock, drive the pooled session channel's
    /// sliding window off-lock, then apply every drained acknowledgement under the lock again.
    /// A refusal applies durable outgoing state before the error surfaces; undrained chunks
    /// stay unconfirmed and are retried by the caller.
    ///
    /// # Errors
    ///
    /// Permission when the workspace/session/batch is not writable and approved; validation for
    /// foreign coordinates or an empty batch; crypto/network errors or a mismatched
    /// acknowledgement.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned chunk batch DTOs"
    )]
    pub fn send_lan_batch_chunks(
        &self,
        chunks: Vec<lan_ffi::LanChunkSendDto>,
    ) -> Result<(), EngineError> {
        self.lan_workspace_root()?;
        let plans = {
            let mut manager = self.lan_manager()?;
            chunks
                .iter()
                .map(|chunk| {
                    let binding = lan_chunk_binding(chunk)?;
                    manager
                        .plan_batch_chunk(&binding, &chunk.plaintext)
                        .map_err(EngineError::from)
                })
                .collect::<Result<Vec<_>, _>>()?
        };
        let mut drained = Vec::new();
        let send = self.lan_pool.send_chunks(&plans, &mut drained);
        let now_ms = lan_pump::unix_now_ms();
        let mut manager = self.lan_manager()?;
        // A refusal is a durable fact the receiver already committed: drained receipts apply
        // even when a later window read failed, and the first receipt error wins over the
        // network error so the caller sees the peer's terminal decision.
        let mut first_apply_error = None;
        for (confirmed, response) in &drained {
            if let Err(error) = manager.apply_chunk_receipt(confirmed, response, now_ms) {
                first_apply_error.get_or_insert(error);
            }
        }
        drop(manager);
        if !drained.is_empty() {
            // Receipts advanced durable confirmed state: observers must see the progress fact.
            self.lan_pump.bump();
        }
        if let Some(error) = first_apply_error {
            return Err(EngineError::from(error));
        }
        send.map_err(EngineError::from)
    }

    /// Returns only the durable missing chunk indices for one received payload.
    ///
    /// # Errors
    ///
    /// Validation for malformed or unknown batch/item/slot coordinates.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned strings for foreign callers"
    )]
    pub fn lan_unconfirmed_batch_chunks(
        &self,
        batch_id: String,
        item_index: u32,
        attachment_slot: u32,
    ) -> Result<Vec<u32>, EngineError> {
        let parsed_batch = lomo_lan::LanBatchId::parse(&batch_id).map_err(EngineError::from)?;
        let item_index = u16::try_from(item_index).map_err(|_error| {
            EngineError::from(lomo_lan::lan_validation(
                "lan_ffi_item_index_invalid",
                "batch item index does not fit the wire index width",
            ))
        })?;
        let attachment_slot = u16::try_from(attachment_slot).map_err(|_error| {
            EngineError::from(lomo_lan::lan_validation(
                "lan_ffi_attachment_slot_invalid",
                "attachment slot does not fit the wire slot width",
            ))
        })?;
        self.lan_manager()?
            .unconfirmed_batch_chunks(&parsed_batch, item_index, attachment_slot)
            .map_err(EngineError::from)
    }

    /// Atomically commits one complete received body through the application session and records
    /// the durable per-item outcome. Body bytes never cross the foreign boundary.
    ///
    /// # Errors
    ///
    /// Permission/validation when approval or payload facts are incomplete; conflict when the
    /// workspace generation changed; session/platform failures during the shared write transaction.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires an owned batch identifier"
    )]
    pub fn commit_received_lan_item(
        &self,
        batch_id: String,
        item_index: u32,
        now_ms: i64,
    ) -> Result<StoreMemoCommit, EngineError> {
        let workspace_root = self.lan_workspace_root()?;
        let parsed_batch = lomo_lan::LanBatchId::parse(&batch_id).map_err(EngineError::from)?;
        let item_index = u16::try_from(item_index).map_err(|_error| {
            EngineError::from(lomo_lan::lan_validation(
                "lan_ffi_item_index_invalid",
                "batch item index does not fit the wire index width",
            ))
        })?;
        if self.lan_manager()?.batch_recovery(&parsed_batch).is_none() {
            return Err(EngineError::from(lomo_lan::lan_validation(
                "lan_batch_unknown",
                "batch is not present in durable recovery state",
            )));
        }
        let active_generation =
            workspace::load_workspace_generation(&workspace_root).map_err(EngineError::from)?;
        let command = self
            .lan_manager()?
            .authorize_received_item_create(
                &parsed_batch,
                item_index,
                active_generation.as_str(),
                now_ms,
            )
            .map_err(EngineError::from)?;
        let Some(command) = command else {
            let manager = self.lan_manager()?;
            let batch = manager.batch_recovery(&parsed_batch).ok_or_else(|| {
                EngineError::from(lomo_lan::lan_validation(
                    "lan_batch_unknown",
                    "batch disappeared while resolving committed item",
                ))
            })?;
            let item = batch
                .plan()
                .items()
                .get(usize::from(item_index))
                .ok_or_else(|| {
                    EngineError::from(lomo_lan::lan_validation(
                        "lan_item_not_in_batch",
                        "received item index does not belong to the batch",
                    ))
                })?;
            let operation_id = item.item_id().as_str().to_owned();
            let committed_memo_id = batch
                .snapshot()
                .outcome(item.item_id())
                .and_then(|outcome| match outcome {
                    lomo_lan::LanItemOutcome::Committed { memo_id } => Some(memo_id.clone()),
                    lomo_lan::LanItemOutcome::Pending | lomo_lan::LanItemOutcome::Failed { .. } => {
                        None
                    }
                })
                .ok_or_else(|| {
                    EngineError::from(lomo_lan::lan_validation(
                        "lan_item_commit_outcome_missing",
                        "committed item has no durable memo identity",
                    ))
                })?;
            drop(manager);
            return self.lan_already_committed_item(operation_id, committed_memo_id);
        };
        let (content, pending_promotes) =
            store_ffi::prepare_received_lan_create(&workspace_root, &command)?;
        let operation_id = command.item_id().as_str().to_owned();
        let chronology_epoch_ms = command.timestamp_ms();
        let created = session_ffi::with_session(self, |session| {
            session.create_memo(lomo_application::CreateMemoRequest {
                operation_id: core::OperationId::parse(&operation_id)?,
                relative_path: None,
                time_token: None,
                content,
                expected_document_fingerprint: None,
                pinned: false,
                pending_promotes,
                chronology_epoch_ms: Some(chronology_epoch_ms),
            })
        })?;
        let memo_id = created.memo_id.as_str().to_owned();
        self.lan_manager()?
            .record_received_item_committed(&parsed_batch, command.item_id(), &memo_id)
            .map_err(EngineError::from)?;
        self.lan_pump.bump();
        Ok(session_ffi::commit_to_ffi(&memo_id, created.commit_result))
    }

    /// Durably records a commit failure for one received item so it leaves the automatic
    /// committable queue with an explicit failure disposition.
    ///
    /// # Errors
    ///
    /// Validation for a malformed batch id, out-of-range item index or unknown batch; storage for
    /// journal persistence failures.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned identifiers for foreign callers"
    )]
    pub fn fail_received_lan_item(
        &self,
        batch_id: String,
        item_index: u32,
        code: String,
    ) -> Result<(), EngineError> {
        let parsed_batch = lomo_lan::LanBatchId::parse(&batch_id).map_err(EngineError::from)?;
        let item_index = u16::try_from(item_index).map_err(|_error| {
            EngineError::from(lomo_lan::lan_validation(
                "lan_ffi_item_index_invalid",
                "batch item index does not fit the wire index width",
            ))
        })?;
        let item_id = {
            let manager = self.lan_manager()?;
            let batch = manager.batch_recovery(&parsed_batch).ok_or_else(|| {
                EngineError::from(lomo_lan::lan_validation(
                    "lan_batch_unknown",
                    "batch is not present in durable recovery state",
                ))
            })?;
            let item_id = batch
                .plan()
                .items()
                .get(usize::from(item_index))
                .ok_or_else(|| {
                    EngineError::from(lomo_lan::lan_validation(
                        "lan_item_not_in_batch",
                        "received item index does not belong to the batch",
                    ))
                })?
                .item_id()
                .clone();
            drop(manager);
            item_id
        };
        self.lan_manager()?
            .record_received_item_failed(&parsed_batch, &item_id, &code)
            .map_err(EngineError::from)?;
        self.lan_pump.bump();
        Ok(())
    }

    /// Lists the installation-level trusted peer registry owned by Rust.
    ///
    /// # Errors
    ///
    /// Internal when the lifecycle lock was poisoned by a prior panic.
    pub fn list_lan_peers(&self) -> Result<LanPeerPageDto, EngineError> {
        let manager = self.lan_manager()?;
        Ok(lan_ffi::peer_page_from_manager(&manager))
    }

    /// Revokes one installation-level peer and retains the tombstone in the journal.
    ///
    /// # Errors
    ///
    /// Validation for a malformed/unknown peer, storage on journal failure, or internal lock
    /// poisoning.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned strings for foreign callers"
    )]
    pub fn revoke_lan_peer(
        &self,
        device_id: String,
        revoked_at_ms: i64,
    ) -> Result<LanPeerPageDto, EngineError> {
        let parsed = lomo_lan::DeviceId::parse(&device_id).map_err(EngineError::from)?;
        let mut manager = self.lan_manager()?;
        // Revocation must also release the revoked peer's pooled channels, so a blocked
        // outbound wait cannot outlive the trust it rode on.
        let evicted_sessions: Vec<lomo_lan::LanSessionId> = manager
            .inbox(revoked_at_ms)
            .map_err(EngineError::from)?
            .active_sessions()
            .iter()
            .filter(|session| session.peer_device_id() == &parsed)
            .map(|session| session.session_id().clone())
            .collect();
        manager
            .revoke_peer(&parsed, revoked_at_ms)
            .map_err(EngineError::from)?;
        let page = lan_ffi::peer_page_from_manager(&manager);
        drop(manager);
        for session_id in &evicted_sessions {
            self.lan_pool.evict_session(session_id);
        }
        self.lan_pump.bump();
        Ok(page)
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
            expected_state: match command.expected_state {
                WorkspaceDocumentExpectedState::Absent => workspace::DocumentExpectedState::Absent,
                WorkspaceDocumentExpectedState::Match { fingerprint } => {
                    workspace::DocumentExpectedState::Match { fingerprint }
                }
            },
            command: match command.command {
                WorkspaceDocumentCommandKind::Create { time_part, content } => {
                    workspace::DocumentCommandKind::Create { time_part, content }
                }
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
                    identity,
                    body_start,
                    body_end,
                } => workspace::DocumentCommandKind::ToggleTask {
                    identity,
                    body_start,
                    body_end,
                },
                WorkspaceDocumentCommandKind::RewriteReminder {
                    reminder,
                    replacement,
                } => workspace::DocumentCommandKind::RewriteReminder {
                    reminder: Box::new(workspace_reminder_from_ffi(reminder)),
                    replacement,
                },
            },
            history: command
                .history
                .map(|history| workspace::DocumentHistoryWrite {
                    revision: history.revision,
                    created_at_ms: history.created_at_ms,
                }),
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
            .take_job_result(&parsed)
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
            affected_memo: result.affected_memo.map(|memo| WorkspaceDocumentMemoFacts {
                path: memo.path,
                identity: memo.identity,
                time_part: memo.time_part,
                fingerprint: memo.fingerprint,
                tags: memo.tags,
                attachments: memo.attachments,
                reminders: memo
                    .reminders
                    .into_iter()
                    .map(workspace_reminder_to_ffi)
                    .collect(),
                has_todo: memo.has_todo,
                has_url: memo.has_url,
                content: memo.content,
            }),
        })
    }

    /// Explicitly shuts down the engine within a bounded deadline.
    ///
    /// # Errors
    ///
    /// Returns validation, journal, or engine lifecycle errors.
    pub fn shutdown(&self, deadline_millis: u64) -> Result<ShutdownOutcome, EngineError> {
        self.lan_pump.stop();
        self.lan_pool.close_all();
        // Closing the engine retires the operation epoch behind a durable witness, so a stale
        // retry after reopen fails with `operation_expired` instead of re-executing.
        if session_ffi::session_is_open(self)? {
            session_ffi::with_session(self, lomo_application::WorkspaceSession::seal)?;
        }
        let _stopped = self.lan_manager()?.stop();
        let deadline = core::ShutdownDeadline::new(Duration::from_millis(deadline_millis))
            .map_err(EngineError::from)?;
        self.core
            .shutdown(deadline)
            .map(shutdown_to_ffi)
            .map_err(EngineError::from)
    }

    fn lan_already_committed_item(
        &self,
        operation_id: String,
        memo_id: String,
    ) -> Result<StoreMemoCommit, EngineError> {
        session_ffi::with_session(self, |session| {
            let snapshot = session.projected_memo(&memo_id)?.ok_or_else(|| {
                lomo_lan::lan_validation(
                    "lan_item_commit_outcome_missing",
                    "committed item has no durable projection",
                )
            })?;
            let clock = session.projection_clock()?;
            if clock.core_revision == 0 || clock.event_sequence == 0 {
                return Err(lomo_lan::lan_validation(
                    "lan_item_commit_clock_missing",
                    "already-committed item has no projection clock",
                ));
            }
            Ok(StoreMemoCommit {
                operation_id,
                memo_id,
                core_revision: clock.core_revision,
                event_sequence: clock.event_sequence,
                content_revision: snapshot.summary.content_revision,
                file_fingerprint: snapshot.summary.file_fingerprint,
                scopes: Vec::new(),
                idempotent_replay: true,
            })
        })
    }

    fn lan_manager(&self) -> Result<MutexGuard<'_, lomo_lan::LanServiceManager>, EngineError> {
        self.lan.lock().map_err(|_poisoned| {
            EngineError::from(static_boundary_error(
                core::ErrorCategory::Internal,
                "lan_runtime_lock_poisoned",
                core::RetryDisposition::AfterUserAction,
                None,
                "LAN runtime lock was poisoned by a prior panic",
            ))
        })
    }

    fn lan_workspace_root(&self) -> Result<PathBuf, EngineError> {
        if !matches!(self.core.state(), core::EngineState::Ready { .. }) {
            return Err(EngineError::from(lomo_lan::lan_permission(
                "lan_workspace_not_ready",
                "LAN batch transfer requires a Ready workspace",
            )));
        }
        if !session_ffi::session_is_open(self)? {
            return Err(EngineError::from(lomo_lan::lan_permission(
                "workspace_session_unavailable",
                "LAN batch transfer requires an open workspace session",
            )));
        }
        match &self.workspace {
            Some(core::WorkspaceDescriptor::Direct { canonical_root, .. }) => {
                Ok(canonical_root.clone())
            }
            Some(core::WorkspaceDescriptor::Saf { identity, .. }) => Ok(self
                .control_root
                .join("session")
                .join(identity.as_str())
                .join("state")),
            None => Err(EngineError::from(lomo_lan::lan_permission(
                "lan_workspace_not_ready",
                "LAN batch transfer requires an active writable workspace",
            ))),
        }
    }

    /// Dark-build `query_memos` (bounded page; no full list transfer).
    ///
    /// # Errors
    ///
    /// No active workspace store, or store query errors.
    pub fn query_memos(
        &self,
        query: StoreMemoQuery,
        cursor: Option<StorePageCursor>,
        page_size: u32,
        start_memo_id: Option<String>,
        backward: bool,
    ) -> Result<StoreMemoPage, EngineError> {
        session_ffi::session_query_memos(self, query, cursor, page_size, start_memo_id, backward)
    }

    /// Counts the rows accepted by the same predicate as `query_memos` without transferring them.
    ///
    /// # Errors
    ///
    /// No active workspace store, or store query errors.
    pub fn query_count(&self, query: StoreMemoQuery) -> Result<u64, EngineError> {
        session_ffi::session_query_count(self, query)
    }

    /// Complete active sidebar aggregate without memo pagination.
    ///
    /// # Errors
    ///
    /// No active workspace store, or store projection errors.
    pub fn sidebar_projection(&self) -> Result<StoreSidebarProjection, EngineError> {
        session_ffi::session_sidebar_projection(self)
    }

    /// Dark-build `get_memo`.
    ///
    /// # Errors
    ///
    /// No active workspace store, or store errors.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned String for foreign callers"
    )]
    pub fn get_memo(&self, memo_id: String) -> Result<Option<StoreMemoSnapshot>, EngineError> {
        session_ffi::session_projected_memo(self, &memo_id)
    }

    /// Commits Rust-parsed facts from a completed workspace document command into the projection.
    /// The document write is already durable; this call never rewrites Markdown.
    ///
    /// # Errors
    ///
    /// Returns validation, conflict, corruption, or storage errors when facts do not match the
    /// current projection.
    pub fn commit_workspace_document_facts(
        &self,
        command: StoreMemoCommand,
        projection: StoreSafMemoProjection,
    ) -> Result<StoreMemoCommit, EngineError> {
        session_ffi::session_commit_workspace_document_facts(self, command, projection)
    }

    /// Dark-build `start_rebuild` (synchronous rebuild result).
    ///
    /// # Errors
    ///
    /// No active workspace session, or rebuild errors.
    pub fn start_rebuild(&self, _batch_size: u32) -> Result<StoreRebuildResult, EngineError> {
        session_ffi::session_rebuild_projection(self)
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

    /// Records a staged artifact in the durable stage ledger and acquires one owner lease.
    ///
    /// # Errors
    ///
    /// Media validation/storage errors.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary owns the staged DTO and owner token"
    )]
    pub fn record_stage_lease(
        &self,
        workspace_root: Option<String>,
        staged: MediaStagedDto,
        owner_kind: MediaStageOwnerKindDto,
        owner_id: String,
    ) -> Result<MediaStageRecordDto, EngineError> {
        media_ffi::ffi_record_stage_lease(workspace_root.as_deref(), staged, owner_kind, &owner_id)
    }

    /// Lists durable stage records leased by one exact holder.
    ///
    /// # Errors
    ///
    /// Media storage/corruption errors.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned String for foreign callers"
    )]
    pub fn stage_records_for_owner(
        &self,
        media_root: String,
        owner_kind: MediaStageOwnerKindDto,
        owner_id: String,
    ) -> Result<Vec<MediaStageRecordDto>, EngineError> {
        media_ffi::ffi_stage_records_for_owner(&media_root, owner_kind, &owner_id)
    }

    /// Transfers one stage lease to another holder without deleting staged bytes.
    ///
    /// # Errors
    ///
    /// Media validation/storage errors.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary owns the lease DTOs"
    )]
    pub fn transfer_stage_lease(
        &self,
        media_root: String,
        from: MediaStageLeaseDto,
        to: MediaStageLeaseDto,
    ) -> Result<MediaStageReleaseDto, EngineError> {
        media_ffi::ffi_transfer_stage_lease(&media_root, from, to)
    }

    /// Releases one stage lease; staged bytes are deleted only when no lease remains.
    ///
    /// # Errors
    ///
    /// Media validation/storage errors.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary owns the lease DTO"
    )]
    pub fn release_stage_lease(
        &self,
        media_root: String,
        lease: MediaStageLeaseDto,
    ) -> Result<MediaStageReleaseDto, EngineError> {
        media_ffi::ffi_release_stage_lease(&media_root, lease)
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
        verified_entries: Vec<MediaCommittedEntryDto>,
    ) -> Result<MediaManifestDto, EngineError> {
        media_ffi::ffi_query_media_manifest(&workspace_root, verified_entries)
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

    /// Imports an archive through the session-owned switch: stage, activate, migrate, rebuild.
    ///
    /// # Errors
    ///
    /// No active workspace session, or archive/migration/rebuild errors.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned String for foreign callers"
    )]
    pub fn session_import_archive(
        &self,
        workspace_root: String,
        archive_path: String,
        staging_root: String,
    ) -> Result<StoreRebuildResult, EngineError> {
        session_ffi::session_import_archive(self, &workspace_root, &archive_path, &staging_root)
    }
}

pub(crate) fn workspace_reminder_to_ffi(
    value: workspace::ReminderReference,
) -> WorkspaceReminderReference {
    WorkspaceReminderReference {
        opaque_id: value.opaque_id,
        revision: value.revision,
        memo_identity: value.memo_identity,
        source_start: value.source_start,
        source_end: value.source_end,
        token_fingerprint: value.token_fingerprint,
        fingerprint_ordinal: value.fingerprint_ordinal,
        embedded_id: value.embedded_id,
        token: value.token,
        due_at_local: value.due_at_local,
        repeat_count: value.repeat_count,
        fired_count: value.fired_count,
        done: value.done,
        interval_minutes: value.interval_minutes,
        recurrence_code: value.recurrence_code,
    }
}

pub(crate) fn workspace_reminder_from_ffi(
    value: WorkspaceReminderReference,
) -> workspace::ReminderReference {
    workspace::ReminderReference {
        opaque_id: value.opaque_id,
        revision: value.revision,
        memo_identity: value.memo_identity,
        source_start: value.source_start,
        source_end: value.source_end,
        token_fingerprint: value.token_fingerprint,
        fingerprint_ordinal: value.fingerprint_ordinal,
        embedded_id: value.embedded_id,
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
        WorkspaceDescriptor::Direct {
            root_path,
            capability_token,
        } => core::WorkspaceDescriptor::direct(
            root_path,
            core::CapabilityToken::parse(&capability_token)?,
        ),
        WorkspaceDescriptor::Saf {
            stable_workspace_id,
            capability_token,
        } => Ok(core::WorkspaceDescriptor::saf(
            core::WorkspaceId::parse(&stable_workspace_id)?,
            core::CapabilityToken::parse(&capability_token)?,
        )),
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
    core::DocumentMetadata::new_with_handle(
        target_from_ffi(value.target)?,
        core::DocumentHandle::parse(&value.document_handle).map_err(EngineError::from)?,
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
    match &value.digest {
        ContentDigest::Unknown => core::ActionEvidence::unknown(value.length, &value.fingerprint)
            .map_err(EngineError::from),
        ContentDigest::Verified { hex } => {
            let digest = core::Sha256Digest::parse(hex).map_err(EngineError::from)?;
            core::ActionEvidence::verified(value.length, digest, &value.fingerprint)
                .map_err(EngineError::from)
        }
    }
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
        core::JobStep::RunningNative {
            task_kind,
            attempt,
            dispatch_generation,
        } => JobStep::RunningNative {
            task_kind,
            attempt,
            dispatch_generation,
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
            locator,
            exchange_token,
            expected_source,
        } => read_to_exchange_to_ffi(
            action_id,
            capability,
            path,
            locator,
            exchange_token,
            expected_source,
        ),
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
        core::PlatformAction::ArtifactWrite {
            action_id,
            capability,
            source,
            path,
            expected_target,
        } => artifact_write_to_ffi(action_id, capability, source, path, expected_target),
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
pub fn artifact_source_to_ffi(value: &core::StagedArtifactSource) -> StagedArtifactSource {
    StagedArtifactSource {
        path: value.path().to_owned(),
        length: value.length(),
        digest: value.digest().as_str().to_owned(),
    }
}

fn read_to_exchange_to_ffi(
    action_id: &core::ActionId,
    capability: &core::CapabilityToken,
    path: &core::RelativeWorkspacePath,
    locator: &core::DocumentLocator,
    exchange_token: &core::ExchangeToken,
    expected_source: &core::ExpectedFingerprint,
) -> PlatformAction {
    PlatformAction::ReadToExchange {
        action_id: action_id.as_str().to_owned(),
        capability_token: capability.as_str().to_owned(),
        path: path.as_str().to_owned(),
        document_handle: match locator {
            core::DocumentLocator::Path(_) => None,
            core::DocumentLocator::Opaque(handle) => Some(handle.as_str().to_owned()),
        },
        exchange_token: exchange_token.as_str().to_owned(),
        expected_source: expected_to_ffi(expected_source),
    }
}

fn artifact_write_to_ffi(
    action_id: &core::ActionId,
    capability: &core::CapabilityToken,
    source: &core::StagedArtifactSource,
    path: &core::RelativeWorkspacePath,
    expected_target: &core::ExpectedFingerprint,
) -> PlatformAction {
    PlatformAction::ArtifactWrite {
        action_id: action_id.as_str().to_owned(),
        capability_token: capability.as_str().to_owned(),
        source: artifact_source_to_ffi(source),
        path: path.as_str().to_owned(),
        expected_target: expected_to_ffi(expected_target),
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
        digest: match value.content_digest() {
            core::ContentDigest::Unknown => ContentDigest::Unknown,
            core::ContentDigest::Verified(digest) => ContentDigest::Verified {
                hex: digest.as_str().to_owned(),
            },
        },
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

impl From<UnexpectedFfiCallbackError> for EngineError {
    fn from(value: UnexpectedFfiCallbackError) -> Self {
        let diagnostic = bounded_callback_diagnostic(value.0);
        Self::from(
            match core::LomoError::from_platform_boundary(
                core::ErrorCategory::Internal,
                "platform_host_callback_failed",
                core::RetryDisposition::Never,
                None,
                None,
                &diagnostic,
            ) {
                Ok(error) | Err(error) => error,
            },
        )
    }
}

fn bounded_callback_diagnostic(raw: String) -> String {
    if raw.is_empty() {
        return "foreign platform host callback failed".to_owned();
    }
    if raw.len() <= 2_048 {
        return raw;
    }
    let mut end = 2_048;
    while end > 0 && !raw.is_char_boundary(end) {
        end -= 1;
    }
    match raw.get(..end) {
        Some(slice) if !slice.is_empty() => slice.to_owned(),
        Some(_) | None => "foreign platform host callback failed".to_owned(),
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
        attachment_destinations: document
            .attachment_destinations()
            .iter()
            .map(workspace::ImageDest::projected)
            .collect(),
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
            // The authored token, not the canonical key: downstream renderers resolve
            // the destination against the same workspace the document came from.
            node.destination = Some(destination.raw().to_owned());
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

/// Converts one chunk-send DTO into a validated wire binding at the FFI edge.
fn lan_chunk_binding(
    chunk: &lan_ffi::LanChunkSendDto,
) -> Result<lomo_lan::ChunkBinding, EngineError> {
    let parsed_session =
        lan_ffi::session_id_from_ffi(&chunk.session_id).map_err(EngineError::from)?;
    let parsed_batch = lomo_lan::LanBatchId::parse(&chunk.batch_id).map_err(EngineError::from)?;
    let item_index = u16::try_from(chunk.item_index).map_err(|_error| {
        EngineError::from(lomo_lan::lan_validation(
            "lan_ffi_item_index_invalid",
            "batch item index does not fit the wire index width",
        ))
    })?;
    let attachment_slot = u16::try_from(chunk.attachment_slot).map_err(|_error| {
        EngineError::from(lomo_lan::lan_validation(
            "lan_ffi_attachment_slot_invalid",
            "attachment slot does not fit the wire slot width",
        ))
    })?;
    lomo_lan::ChunkBinding::new(
        &parsed_session,
        parsed_batch.as_str(),
        item_index,
        attachment_slot,
        chunk.chunk_index,
    )
    .map_err(EngineError::from)
}

pub(crate) fn lan_pump_boundary_error(code: &'static str, diagnostic: &'static str) -> EngineError {
    EngineError::from(static_boundary_error(
        core::ErrorCategory::Internal,
        code,
        core::RetryDisposition::AfterUserAction,
        None,
        diagnostic,
    ))
}

#[data]
#[derive(Clone, Debug)]
pub struct AttachmentNameMapping {
    pub original: String,
    pub stored: String,
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
    // Inserts always mint a durable embedded id so the definition identity is born stable.
    let embedded_id = workspace::mint_reminder_embedded_id().map_err(EngineError::from)?;
    workspace::build_reminder_token(
        &request.due_at_local,
        request.repeat_count,
        request.fired_count,
        request.done,
        request.interval_minutes,
        &request.recurrence_code,
        Some(&embedded_id),
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
