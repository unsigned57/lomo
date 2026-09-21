//! Application-session `BoltFFI` conversion surface.
//!
//! Maps foreign DTOs onto `lomo-application::WorkspaceSession`. Physical I/O is injected through
//! [`PlatformBatchHost`]; this module does not choose document paths or execute POSIX I/O.

use std::sync::Arc;

use boltffi::{data, export};
use lomo_application::{
    CreateMemoRequest, DeleteMemoRequest, FireReminderRequest, PermanentDeleteRequest,
    PinMemoRequest, PinPolicy, RestoreMemoRequest, RestoreRevisionRequest, SearchMode,
    SearchOutcome, SearchRequest, ToggleTaskRequest, UpdateMemoRequest, WorkspaceSession,
    WorkspaceSessionConfig,
    calendar::{CivilDate, DateFormat},
    statistics::StatisticsSnapshot,
};
use lomo_core::{
    self as core, CapabilityToken, LomoError, OperationId, PageSize, PlatformActionExecutor,
    RelativeWorkspacePath,
};
use lomo_store::{SafProjectionCommitResult, list_history_attachment_refs};
use lomo_workspace::{MemoId, WorkspaceRootId};

use crate::{
    ActionOutcome, ActionResult, DocumentKind, DocumentMetadata, EngineError, ExpectedFingerprint,
    LomoEngine, MetadataPage, PlatformAction, PlatformActionBatch, PlatformActionOutput,
    PlatformBatchHost, PlatformBatchResult, StoreHistoryAttachmentRef, StoreMemoCommand,
    StoreMemoCommit, StoreMemoHistoryPage, StoreMemoHistoryRevision, StoreMemoPage, StoreMemoQuery,
    StoreMemoSnapshot, StoreMemoStatisticsRow, StorePageCursor, StorePlannedAlarm,
    StoreRebuildResult, StoreReminderPlan, StoreSafMemoProjection, StoreSidebarDateCount,
    StoreSidebarProjection, StoreSidebarTagCount, VerifiedAbsence, artifact_from_ffi,
    artifact_to_ffi, batch_to_ffi, evidence_from_ffi, evidence_to_ffi, failure_from_core,
    failure_to_core,
    media_ffi::{MediaPromotePlanDto, pending_promotes_from_ffi},
    result_from_ffi,
    store_ffi::{
        decode_cursor, encode_cursor, ffi_query_start, memo_page_to_ffi, memo_query_from_ffi,
        scopes_to_ffi, summary_to_ffi, workspace_document_facts_mutation,
    },
    target_from_ffi, target_to_ffi,
};

struct HostedExecutor {
    host: Box<dyn PlatformBatchHost>,
}

impl PlatformActionExecutor for HostedExecutor {
    fn execute(
        &self,
        batch: &core::PlatformActionBatch,
    ) -> Result<core::PlatformBatchResult, LomoError> {
        let result = self
            .host
            .execute(batch_to_ffi(batch))
            .map_err(lomo_from_engine)?;
        result_from_ffi(result).map_err(lomo_from_engine)
    }
}

#[data]
#[derive(Clone, Debug)]
pub struct SessionCreateMemoRequest {
    pub operation_id: String,
    pub relative_path: Option<String>,
    pub time_token: Option<String>,
    pub content: String,
    pub expected_document_fingerprint: Option<String>,
    pub pinned: bool,
    pub pending_promotes: Vec<MediaPromotePlanDto>,
    pub chronology_epoch_ms: Option<i64>,
}

#[data]
#[derive(Clone, Debug)]
pub struct SessionUpdateMemoRequest {
    pub operation_id: String,
    pub memo_id: String,
    pub content: String,
    pub expected_document_fingerprint: String,
    pub pending_promotes: Vec<MediaPromotePlanDto>,
}

#[data]
#[derive(Clone, Debug)]
pub struct SessionDeleteMemoRequest {
    pub operation_id: String,
    pub memo_id: String,
    pub expected_document_fingerprint: String,
}

#[data]
#[derive(Clone, Debug)]
pub struct SessionPinMemoRequest {
    pub operation_id: String,
    pub memo_id: String,
    pub pinned: bool,
}

#[data]
#[derive(Clone, Debug)]
pub struct SessionMemoView {
    pub memo_id: String,
    pub source_path: String,
    pub file_fingerprint: String,
    pub body: String,
    pub is_pinned: bool,
    pub is_trashed: bool,
    pub created_at_ms: i64,
    pub updated_at_ms: i64,
}

#[data]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SessionSearchMode {
    Fulltext,
    Fuzzy,
}

#[data]
#[derive(Clone, Debug)]
pub struct SessionSearchRequest {
    pub filters: crate::StoreMemoFilters,
    pub query_epoch: u64,
    pub mode: SessionSearchMode,
    pub text: String,
    pub cursor: Option<StorePageCursor>,
    pub page_size: u32,
}

#[data]
#[derive(Clone, Debug)]
pub struct SessionSearchHit {
    pub summary: crate::StoreMemoSummary,
    pub score: i64,
}

#[data]
#[derive(Clone, Debug)]
pub struct SessionSearchPage {
    pub query_epoch: u64,
    pub mode: SessionSearchMode,
    pub items: Vec<SessionSearchHit>,
    pub next_cursor: Option<StorePageCursor>,
}

#[data]
#[derive(Clone, Debug)]
pub enum SessionSearchOutcome {
    Ready { page: SessionSearchPage },
    Discarded { query_epoch: u64, active_epoch: u64 },
}

#[data]
#[derive(Clone, Debug)]
pub struct SessionTaskItem {
    pub memo_id: String,
    pub line_index: u32,
    pub done: bool,
    pub text: String,
    pub source_path: String,
}

#[data]
#[derive(Clone, Debug)]
pub struct SessionToggleTaskRequest {
    pub operation_id: String,
    pub memo_id: String,
    pub line_index: u32,
    pub done: bool,
}

#[data]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct SessionCivilDate {
    pub year: i32,
    pub month: u8,
    pub day: u8,
}

#[data]
#[derive(Clone, Debug)]
pub struct SessionReviewCandidate {
    pub memo_id: String,
    pub created_at_ms: i64,
    pub body_preview: String,
    pub source_path: String,
}

#[data]
#[derive(Clone, Debug)]
pub struct SessionRestoreRequest {
    pub operation_id: String,
    pub memo_id: String,
}

#[data]
#[derive(Clone, Debug)]
pub struct SessionRestoreRevisionRequest {
    pub operation_id: String,
    pub memo_id: String,
    pub revision: u64,
}

#[data]
#[derive(Clone, Debug)]
pub struct SessionFireReminderRequest {
    pub operation_id: String,
    pub memo_id: String,
    pub opaque_id: String,
}

#[data]
#[derive(Clone, Debug)]
pub struct SessionStatisticsSnapshot {
    pub zone: String,
    pub as_of: SessionCivilDate,
}

#[data]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct SessionDateCount {
    pub year: i32,
    pub month: u8,
    pub day: u8,
    pub count: u64,
}

#[data]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct SessionHourCount {
    pub hour: u8,
    pub count: u64,
}

#[data]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct SessionWeeklyHourCount {
    pub weekday: u8,
    pub hour: u8,
    pub count: u64,
}

#[data]
#[derive(Clone, Debug)]
pub struct SessionTagCount {
    pub name: String,
    pub count: u64,
}

#[data]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct SessionCivilTime {
    pub hour: u8,
    pub minute: u8,
    pub second: u8,
}

#[data]
#[derive(Clone, Debug)]
pub struct SessionStatistics {
    pub as_of: SessionCivilDate,
    pub total_memos: u64,
    pub total_words: u64,
    pub total_characters: u64,
    pub average_words_per_memo: f64,
    pub total_tags: u64,
    pub active_days: u64,
    pub current_streak: u64,
    pub longest_streak: u64,
    pub memo_count_by_date: Vec<SessionDateCount>,
    pub hourly_distribution: Vec<SessionHourCount>,
    pub weekly_hour_distribution: Vec<SessionWeeklyHourCount>,
    pub earliest_daily_memo_time: Option<SessionCivilTime>,
    pub latest_daily_memo_time: Option<SessionCivilTime>,
    pub this_week_count: u64,
    pub last_week_count: u64,
    pub this_month_count: u64,
    pub last_month_count: u64,
    pub this_year_count: u64,
    pub last_year_count: u64,
    pub tag_counts: Vec<SessionTagCount>,
}

#[export]
impl LomoEngine {
    /// Opens the shared application session with a foreign platform-action host.
    ///
    /// # Errors
    ///
    /// Missing workspace, duplicate open, private-directory, or recovery failures.
    pub fn open_workspace_session(
        &self,
        host: Box<dyn PlatformBatchHost>,
        time_zone: String,
    ) -> Result<String, EngineError> {
        let workspace = self.workspace.as_ref().ok_or_else(|| {
            session_err(
                "workspace_not_selected",
                "workspace session requires an active workspace",
            )
        })?;
        let mut guard = self
            .session
            .lock()
            .map_err(|_poison| poisoned("workspace session lock poisoned"))?;
        if guard.is_some() {
            return Err(session_err(
                "workspace_session_already_open",
                "workspace session is already open",
            ));
        }
        let identity = workspace.identity().as_str();
        let workspace_generation = match workspace {
            core::WorkspaceDescriptor::Direct { canonical_root, .. } => {
                lomo_workspace::load_or_mint_workspace_generation(canonical_root)
                    .map_err(EngineError::from)?
            }
            core::WorkspaceDescriptor::Saf { .. } => {
                let state = self
                    .control_root
                    .join("session")
                    .join(identity)
                    .join("state");
                lomo_workspace::load_or_mint_workspace_generation(&state)
                    .map_err(EngineError::from)?
            }
        };
        let base = self.control_root.join("session").join(identity);
        let (capability, root_id) = (workspace.capability().clone(), WorkspaceRootId::Notes);
        let config = WorkspaceSessionConfig {
            capability,
            root_id,
            workspace_generation,
            time_zone,
            date_format: DateFormat::default(),
            state_dir: base.join("state"),
            cache_dir: base.join("cache"),
            runtime_dir: base.join("runtime"),
            exchange_dir: self.core.exchange_root().to_path_buf(),
        };
        let executor: Arc<dyn PlatformActionExecutor> = Arc::new(HostedExecutor { host });
        let session = WorkspaceSession::open(config, executor).map_err(EngineError::from)?;
        let device_id = session.device_id().to_owned();
        *guard = Some(Arc::new(session));
        drop(guard);
        Ok(device_id)
    }

    /// Creates a memo through the shared application write transaction.
    ///
    /// # Errors
    ///
    /// Session, validation, conflict, or platform I/O failures.
    pub fn session_create_memo(
        &self,
        request: SessionCreateMemoRequest,
    ) -> Result<StoreMemoCommit, EngineError> {
        let relative_path = request
            .relative_path
            .as_deref()
            .map(RelativeWorkspacePath::parse)
            .transpose()
            .map_err(EngineError::from)?;
        let inner = CreateMemoRequest {
            operation_id: OperationId::parse(&request.operation_id).map_err(EngineError::from)?,
            relative_path,
            time_token: request.time_token,
            content: request.content,
            expected_document_fingerprint: request.expected_document_fingerprint,
            pinned: request.pinned,
            pending_promotes: pending_promotes_from_ffi(&request.pending_promotes)?,
            chronology_epoch_ms: request.chronology_epoch_ms,
        };
        with_session(self, |session| session.create_memo(inner))
            .map(|result| commit_to_ffi(result.memo_id.as_str(), result.commit_result))
    }

    /// Replaces one memo body through the shared write transaction.
    ///
    /// # Errors
    ///
    /// Session, identity, conflict, or platform I/O failures.
    pub fn session_update_memo(
        &self,
        request: SessionUpdateMemoRequest,
    ) -> Result<StoreMemoCommit, EngineError> {
        let memo_id = request.memo_id.clone();
        let inner = UpdateMemoRequest {
            operation_id: OperationId::parse(&request.operation_id).map_err(EngineError::from)?,
            memo_id: MemoId::parse(&request.memo_id).map_err(EngineError::from)?,
            content: request.content,
            expected_document_fingerprint: request.expected_document_fingerprint,
            pending_promotes: pending_promotes_from_ffi(&request.pending_promotes)?,
        };
        with_session(self, |session| session.update_memo(inner))
            .map(|result| commit_to_ffi(&memo_id, result.commit_result))
    }

    /// Soft-deletes one memo through the shared write transaction.
    ///
    /// # Errors
    ///
    /// Session, identity, conflict, or platform I/O failures.
    pub fn session_delete_memo(
        &self,
        request: SessionDeleteMemoRequest,
    ) -> Result<StoreMemoCommit, EngineError> {
        let memo_id = request.memo_id.clone();
        let inner = DeleteMemoRequest {
            operation_id: OperationId::parse(&request.operation_id).map_err(EngineError::from)?,
            memo_id: MemoId::parse(&request.memo_id).map_err(EngineError::from)?,
            expected_document_fingerprint: request.expected_document_fingerprint,
            trashed_at_ms: None,
        };
        with_session(self, |session| session.delete_memo(inner))
            .map(|result| commit_to_ffi(&memo_id, result.commit_result))
    }

    /// Pins or unpins one memo through the shared write transaction.
    ///
    /// # Errors
    ///
    /// Session, identity, or platform I/O failures.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned request wire types"
    )]
    pub fn session_pin_memo(
        &self,
        request: SessionPinMemoRequest,
    ) -> Result<StoreMemoCommit, EngineError> {
        let memo_id = request.memo_id.clone();
        let pin = if request.pinned {
            PinPolicy::Pinned { at_ms: None }
        } else {
            PinPolicy::Unpinned
        };
        let inner = PinMemoRequest::new(
            OperationId::parse(&request.operation_id).map_err(EngineError::from)?,
            MemoId::parse(&request.memo_id).map_err(EngineError::from)?,
            pin,
        )
        .map_err(EngineError::from)?;
        with_session(self, |session| session.pin_memo(inner))
            .map(|result| commit_to_ffi(&memo_id, result.commit_result))
    }

    /// Reads one active memo from the session projection.
    ///
    /// # Errors
    ///
    /// Session or projection failures.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned String wire types"
    )]
    pub fn session_get_memo(
        &self,
        memo_id: String,
    ) -> Result<Option<SessionMemoView>, EngineError> {
        let parsed = MemoId::parse(&memo_id).map_err(EngineError::from)?;
        with_session(self, |session| session.get_memo(&parsed)).map(|view| {
            view.map(|view| SessionMemoView {
                memo_id: view.memo_id,
                source_path: view.source_path,
                file_fingerprint: view.file_fingerprint,
                body: view.body,
                is_pinned: view.is_pinned,
                is_trashed: view.is_trashed,
                created_at_ms: view.created_at_ms,
                updated_at_ms: view.updated_at_ms,
            })
        })
    }

    /// Runs dual-mode retrieval through the application session.
    ///
    /// # Errors
    ///
    /// Session, cursor, or projection failures.
    pub fn session_search(
        &self,
        request: SessionSearchRequest,
    ) -> Result<SessionSearchOutcome, EngineError> {
        let cursor = request
            .cursor
            .as_ref()
            .map(|cursor| decode_cursor(&cursor.encoded))
            .transpose()?;
        let inner = SearchRequest {
            filters: crate::store_ffi::memo_filters_from_ffi(request.filters),
            query_epoch: request.query_epoch,
            mode: match request.mode {
                SessionSearchMode::Fulltext => SearchMode::Fulltext,
                SessionSearchMode::Fuzzy => SearchMode::Fuzzy,
            },
            text: request.text,
            cursor,
            page_size: PageSize::new(request.page_size).map_err(EngineError::from)?,
        };
        with_session(self, |session| session.search(&inner)).map(search_outcome_to_ffi)
    }

    /// Aggregates Markdown task-list items from active memos.
    ///
    /// # Errors
    ///
    /// Session or projection failures.
    pub fn session_list_tasks(&self) -> Result<Vec<SessionTaskItem>, EngineError> {
        with_session(self, WorkspaceSession::list_tasks).map(|items| {
            items
                .into_iter()
                .map(|item| SessionTaskItem {
                    memo_id: item.memo_id,
                    line_index: item.line_index,
                    done: item.done,
                    text: item.text,
                    source_path: item.source_path,
                })
                .collect()
        })
    }

    /// Toggles one task marker through the shared write transaction.
    ///
    /// # Errors
    ///
    /// Session, identity, conflict, or platform I/O failures.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned request wire types"
    )]
    pub fn session_toggle_task(
        &self,
        request: SessionToggleTaskRequest,
    ) -> Result<StoreMemoCommit, EngineError> {
        let memo_id = request.memo_id.clone();
        let inner = ToggleTaskRequest {
            operation_id: OperationId::parse(&request.operation_id).map_err(EngineError::from)?,
            memo_id: MemoId::parse(&request.memo_id).map_err(EngineError::from)?,
            line_index: request.line_index,
            done: request.done,
        };
        with_session(self, |session| session.toggle_task(inner))
            .map(|result| commit_to_ffi(&memo_id, result.commit_result))
    }

    /// Lists timezone-sliced daily review candidates.
    ///
    /// # Errors
    ///
    /// Session, calendar, or projection failures.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned timezone and date wire types"
    )]
    pub fn session_review_candidates(
        &self,
        zone: String,
        date: SessionCivilDate,
    ) -> Result<Vec<SessionReviewCandidate>, EngineError> {
        let civil = civil_date_from_ffi(date)?;
        with_session(self, |session| session.review_candidates(&zone, civil)).map(|items| {
            items
                .into_iter()
                .map(|item| SessionReviewCandidate {
                    memo_id: item.memo_id,
                    created_at_ms: item.created_at_ms,
                    body_preview: item.body_preview,
                    source_path: item.source_path,
                })
                .collect()
        })
    }

    /// Records a local review completion for one memo.
    ///
    /// # Errors
    ///
    /// Session, identity, or private-state I/O failures.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned timezone, date, and identity wire types"
    )]
    pub fn session_complete_review(
        &self,
        zone: String,
        date: SessionCivilDate,
        memo_id: String,
    ) -> Result<(), EngineError> {
        let civil = civil_date_from_ffi(date)?;
        let parsed = MemoId::parse(&memo_id).map_err(EngineError::from)?;
        with_session(self, |session| {
            session.complete_review(&zone, civil, &parsed)
        })
    }

    /// Aggregates heatmap statistics from the session projection.
    ///
    /// # Errors
    ///
    /// Session, calendar, or projection failures.
    pub fn session_statistics(
        &self,
        snapshot: SessionStatisticsSnapshot,
    ) -> Result<SessionStatistics, EngineError> {
        let inner = StatisticsSnapshot::new(snapshot.zone, civil_date_from_ffi(snapshot.as_of)?);
        with_session(self, |session| session.statistics(&inner)).map(statistics_to_ffi)
    }

    /// Lists durable history revisions for one memo.
    ///
    /// # Errors
    ///
    /// Session or history storage failures.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned identity and cursor wire types"
    )]
    pub fn session_list_history(
        &self,
        memo_id: String,
        cursor: Option<String>,
        limit: u32,
    ) -> Result<StoreMemoHistoryPage, EngineError> {
        let parsed = MemoId::parse(&memo_id).map_err(EngineError::from)?;
        let limit = usize::try_from(limit)
            .map_err(|error| session_err("invalid_history_limit", &error.to_string()))?;
        with_session(self, |session| {
            session.list_history(&parsed, cursor.as_deref(), limit)
        })
        .map(|page| StoreMemoHistoryPage {
            items: page
                .items
                .into_iter()
                .map(|item| StoreMemoHistoryRevision {
                    revision: item.revision,
                    created_at_ms: item.created_at_ms,
                    content: item.content,
                    file_fingerprint: item.file_fingerprint,
                })
                .collect(),
            next_cursor: page.next_cursor,
        })
    }

    /// Restores a soft-deleted memo with its original durable id.
    ///
    /// # Errors
    ///
    /// Session, trash, identity, or platform I/O failures.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned request wire types"
    )]
    pub fn session_restore_memo(
        &self,
        request: SessionRestoreRequest,
    ) -> Result<StoreMemoCommit, EngineError> {
        let memo_id = request.memo_id.clone();
        let inner = RestoreMemoRequest {
            operation_id: OperationId::parse(&request.operation_id).map_err(EngineError::from)?,
            memo_id: MemoId::parse(&request.memo_id).map_err(EngineError::from)?,
        };
        with_session(self, |session| session.restore_memo(&inner))
            .map(|result| commit_to_ffi(&memo_id, result.commit_result))
    }

    /// Restores one history revision through the shared write transaction.
    ///
    /// # Errors
    ///
    /// Session, history, conflict, or platform I/O failures.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned request wire types"
    )]
    pub fn session_restore_revision(
        &self,
        request: SessionRestoreRevisionRequest,
    ) -> Result<StoreMemoCommit, EngineError> {
        let memo_id = request.memo_id.clone();
        let inner = RestoreRevisionRequest {
            operation_id: OperationId::parse(&request.operation_id).map_err(EngineError::from)?,
            memo_id: MemoId::parse(&request.memo_id).map_err(EngineError::from)?,
            revision: request.revision,
        };
        with_session(self, |session| session.restore_revision(inner))
            .map(|result| commit_to_ffi(&memo_id, result.commit_result))
    }

    /// Permanently deletes one trashed memo through the shared write transaction.
    ///
    /// # Errors
    ///
    /// Session, trash, or platform I/O failures.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned request wire types"
    )]
    pub fn session_permanently_delete_memo(
        &self,
        request: SessionRestoreRequest,
    ) -> Result<StoreMemoCommit, EngineError> {
        let memo_id = request.memo_id.clone();
        let inner = PermanentDeleteRequest {
            operation_id: OperationId::parse(&request.operation_id).map_err(EngineError::from)?,
            memo_id: MemoId::parse(&request.memo_id).map_err(EngineError::from)?,
        };
        with_session(self, |session| session.permanently_delete_memo(&inner))
            .map(|result| commit_to_ffi(&memo_id, result.commit_result))
    }

    /// Plans reminder alarms from projected Markdown tokens.
    ///
    /// # Errors
    ///
    /// Session, zone, or projection failures.
    pub fn session_reminder_plan(
        &self,
        now_utc_ms: Option<i64>,
    ) -> Result<StoreReminderPlan, EngineError> {
        with_session(self, |session| session.reminder_plan(now_utc_ms)).map(|plan| {
            StoreReminderPlan {
                alarms: plan
                    .alarms
                    .into_iter()
                    .map(|alarm| StorePlannedAlarm {
                        occurrence_id: alarm.occurrence_id,
                        opaque_id: alarm.opaque_id,
                        memo_identity: alarm.memo_identity,
                        trigger_at_utc_ms: alarm.trigger_at_utc_ms,
                        is_catch_up: alarm.is_catch_up,
                    })
                    .collect(),
                dropped_count: plan.dropped_count,
                workspace_generation: plan.workspace_generation,
            }
        })
    }

    /// Advances one reminder token through the shared write transaction.
    ///
    /// # Errors
    ///
    /// Session, token, conflict, or platform I/O failures.
    pub fn session_record_reminder_fired(
        &self,
        request: SessionFireReminderRequest,
    ) -> Result<StoreMemoCommit, EngineError> {
        let memo_id = request.memo_id.clone();
        let inner = FireReminderRequest {
            operation_id: OperationId::parse(&request.operation_id).map_err(EngineError::from)?,
            memo_id: MemoId::parse(&request.memo_id).map_err(EngineError::from)?,
            opaque_id: request.opaque_id,
        };
        with_session(self, |session| session.record_reminder_fired(inner))
            .map(|result| commit_to_ffi(&memo_id, result.commit_result))
    }

    /// Writes a durable app-private snooze binding for one reminder definition. The caller passes
    /// a validated duration; the deadline instant is computed by the owner.
    ///
    /// # Errors
    ///
    /// Session, validation, snooze storage, recovery-pending, or entry-budget failures.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned String for foreign callers"
    )]
    pub fn session_snooze_reminder(
        &self,
        opaque_id: String,
        snooze_duration_ms: i64,
    ) -> Result<(), EngineError> {
        with_session(self, |session| {
            session.snooze_reminder(&opaque_id, snooze_duration_ms)
        })
    }

    /// Clears the durable snooze binding for one reminder definition.
    ///
    /// # Errors
    ///
    /// Session, snooze storage, or recovery-pending failures.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned String for foreign callers"
    )]
    pub fn session_clear_reminder_snooze(&self, opaque_id: String) -> Result<(), EngineError> {
        with_session(self, |session| session.clear_reminder_snooze(&opaque_id))
    }

    /// True when durable snooze state is quarantined and scheduling is paused.
    ///
    /// # Errors
    ///
    /// Session or snooze storage failures.
    pub fn session_reminder_snooze_recovery_pending(&self) -> Result<bool, EngineError> {
        with_session(self, WorkspaceSession::reminder_snooze_recovery_pending)
    }

    /// Explicitly recovers corrupt durable snooze state (quarantine + fresh store).
    ///
    /// # Errors
    ///
    /// Session or snooze storage failures.
    pub fn session_recover_reminder_snooze(&self) -> Result<(), EngineError> {
        with_session(self, WorkspaceSession::recover_reminder_snooze)
    }
}

/// Converts a platform-action DTO into the core batch the POSIX/SAF host executes.
///
/// # Errors
///
/// Identity, schema, or action conversion failures.
pub fn batch_from_ffi(
    value: PlatformActionBatch,
) -> Result<core::PlatformActionBatch, EngineError> {
    let job_id = core::JobId::parse(&value.job_id).map_err(EngineError::from)?;
    let batch_id = core::BatchId::parse(&value.batch_id).map_err(EngineError::from)?;
    let actions = value
        .actions
        .into_iter()
        .map(action_from_ffi)
        .collect::<Result<Vec<_>, _>>()?;
    core::PlatformActionBatch::new(
        job_id,
        batch_id,
        value.attempt,
        value.deadline_epoch_millis,
        actions,
    )
    .map_err(EngineError::from)
}

/// Converts a verified core batch result into the FFI DTO returned to a foreign host.
#[must_use]
pub fn result_to_ffi(value: &core::PlatformBatchResult) -> PlatformBatchResult {
    PlatformBatchResult {
        schema_version: value.schema_version(),
        job_id: value.job_id().as_str().to_owned(),
        batch_id: value.batch_id().as_str().to_owned(),
        attempt: value.attempt(),
        action_results: value
            .action_results()
            .iter()
            .map(action_result_to_ffi)
            .collect(),
    }
}

#[expect(
    clippy::too_many_lines,
    reason = "one conversion arm per PlatformAction variant; splitting would duplicate identity parsing"
)]
fn action_from_ffi(value: PlatformAction) -> Result<core::PlatformAction, EngineError> {
    let action = match value {
        PlatformAction::Stat {
            action_id,
            capability_token,
            target,
        } => core::PlatformAction::Stat {
            action_id: core::ActionId::parse(&action_id).map_err(EngineError::from)?,
            capability: CapabilityToken::parse(&capability_token).map_err(EngineError::from)?,
            target: target_from_ffi(target)?,
        },
        PlatformAction::ListChildren {
            action_id,
            capability_token,
            target,
            cursor,
            page_size,
        } => core::PlatformAction::ListChildren {
            action_id: core::ActionId::parse(&action_id).map_err(EngineError::from)?,
            capability: CapabilityToken::parse(&capability_token).map_err(EngineError::from)?,
            target: target_from_ffi(target)?,
            cursor,
            page_size: PageSize::new(page_size).map_err(EngineError::from)?,
        },
        PlatformAction::EnsureDirectory {
            action_id,
            capability_token,
            path,
        } => core::PlatformAction::EnsureDirectory {
            action_id: core::ActionId::parse(&action_id).map_err(EngineError::from)?,
            capability: CapabilityToken::parse(&capability_token).map_err(EngineError::from)?,
            path: RelativeWorkspacePath::parse(&path).map_err(EngineError::from)?,
        },
        PlatformAction::ReadToExchange {
            action_id,
            capability_token,
            path,
            document_handle,
            exchange_token,
            expected_source,
        } => {
            let parsed_path = RelativeWorkspacePath::parse(&path).map_err(EngineError::from)?;
            let locator = match document_handle {
                Some(handle) => core::DocumentLocator::Opaque(
                    core::DocumentHandle::parse(&handle).map_err(EngineError::from)?,
                ),
                None => core::DocumentLocator::Path(parsed_path.clone()),
            };
            core::PlatformAction::ReadToExchange {
                action_id: core::ActionId::parse(&action_id).map_err(EngineError::from)?,
                capability: CapabilityToken::parse(&capability_token).map_err(EngineError::from)?,
                path: parsed_path,
                locator,
                exchange_token: core::ExchangeToken::parse(&exchange_token)
                    .map_err(EngineError::from)?,
                expected_source: expected_from_ffi(expected_source)?,
            }
        }
        PlatformAction::WriteFromExchange {
            action_id,
            capability_token,
            artifact,
            path,
            mode,
            expected_target,
        } => core::PlatformAction::WriteFromExchange {
            action_id: core::ActionId::parse(&action_id).map_err(EngineError::from)?,
            capability: CapabilityToken::parse(&capability_token).map_err(EngineError::from)?,
            artifact: artifact_from_ffi(&artifact)?,
            path: RelativeWorkspacePath::parse(&path).map_err(EngineError::from)?,
            mode: match mode {
                crate::WriteMode::Create => core::WriteMode::Create,
                crate::WriteMode::Replace => core::WriteMode::Replace,
            },
            expected_target: expected_from_ffi(expected_target)?,
        },
        PlatformAction::Move {
            action_id,
            capability_token,
            source,
            target,
            expected_source,
            expected_target,
        } => core::PlatformAction::Move {
            action_id: core::ActionId::parse(&action_id).map_err(EngineError::from)?,
            capability: CapabilityToken::parse(&capability_token).map_err(EngineError::from)?,
            source: RelativeWorkspacePath::parse(&source).map_err(EngineError::from)?,
            target: RelativeWorkspacePath::parse(&target).map_err(EngineError::from)?,
            expected_source: expected_from_ffi(expected_source)?,
            expected_target: expected_from_ffi(expected_target)?,
        },
        PlatformAction::Delete {
            action_id,
            capability_token,
            path,
            expected_target,
        } => core::PlatformAction::Delete {
            action_id: core::ActionId::parse(&action_id).map_err(EngineError::from)?,
            capability: CapabilityToken::parse(&capability_token).map_err(EngineError::from)?,
            path: RelativeWorkspacePath::parse(&path).map_err(EngineError::from)?,
            expected_target: expected_from_ffi(expected_target)?,
        },
    };
    Ok(action)
}

fn expected_from_ffi(value: ExpectedFingerprint) -> Result<core::ExpectedFingerprint, EngineError> {
    match value {
        ExpectedFingerprint::Absent => Ok(core::ExpectedFingerprint::absent()),
        ExpectedFingerprint::Match { evidence } => Ok(core::ExpectedFingerprint::matching(
            evidence_from_ffi(&evidence)?,
        )),
    }
}

fn action_result_to_ffi(value: &core::ActionResult) -> ActionResult {
    ActionResult {
        action_id: value.action_id().as_str().to_owned(),
        outcome: match value.outcome() {
            core::ActionOutcome::Applied(output) => ActionOutcome::Applied {
                output: output_to_ffi(output),
            },
            core::ActionOutcome::AlreadySatisfied(output) => ActionOutcome::AlreadySatisfied {
                output: output_to_ffi(output),
            },
            core::ActionOutcome::Failed(failure) => ActionOutcome::Failed {
                failure: failure_from_core(failure),
            },
        },
    }
}

fn output_to_ffi(value: &core::PlatformActionOutput) -> PlatformActionOutput {
    match value {
        core::PlatformActionOutput::Stat { metadata } => PlatformActionOutput::Stat {
            metadata: metadata_to_ffi(metadata),
        },
        core::PlatformActionOutput::Listed { page } => PlatformActionOutput::Listed {
            page: metadata_page_to_ffi(page),
        },
        core::PlatformActionOutput::DirectoryReady { metadata } => {
            PlatformActionOutput::DirectoryReady {
                metadata: metadata_to_ffi(metadata),
            }
        }
        core::PlatformActionOutput::ReadToExchange {
            source_metadata,
            artifact,
        } => PlatformActionOutput::ReadToExchange {
            source_metadata: metadata_to_ffi(source_metadata),
            artifact: artifact_to_ffi(artifact),
        },
        core::PlatformActionOutput::WriteComplete { metadata } => {
            PlatformActionOutput::WriteComplete {
                metadata: metadata_to_ffi(metadata),
            }
        }
        core::PlatformActionOutput::MoveComplete { metadata } => {
            PlatformActionOutput::MoveComplete {
                metadata: metadata_to_ffi(metadata),
            }
        }
        core::PlatformActionOutput::DeleteComplete { absence } => {
            PlatformActionOutput::DeleteComplete {
                absence: VerifiedAbsence {
                    target: target_to_ffi(absence.target()),
                    fingerprint: absence.fingerprint().to_owned(),
                },
            }
        }
    }
}

fn metadata_to_ffi(value: &core::DocumentMetadata) -> DocumentMetadata {
    DocumentMetadata {
        target: target_to_ffi(value.target()),
        document_handle: value.document_handle().as_str().to_owned(),
        kind: match value.kind() {
            core::DocumentKind::File => DocumentKind::File,
            core::DocumentKind::Directory => DocumentKind::Directory,
        },
        mime_type: value.mime_type().map(str::to_owned),
        evidence: evidence_to_ffi(value.evidence()),
    }
}

fn metadata_page_to_ffi(value: &core::MetadataPage) -> MetadataPage {
    MetadataPage {
        items: value.items().iter().map(metadata_to_ffi).collect(),
        next_cursor: value.next_cursor().map(|cursor| cursor.as_str().to_owned()),
    }
}

pub fn with_session<R>(
    engine: &LomoEngine,
    read: impl FnOnce(&WorkspaceSession) -> Result<R, LomoError>,
) -> Result<R, EngineError> {
    let session = session_arc(engine)?;
    read(&session).map_err(EngineError::from)
}

pub fn session_arc(engine: &LomoEngine) -> Result<Arc<WorkspaceSession>, EngineError> {
    let guard = engine
        .session
        .lock()
        .map_err(|_poison| poisoned("workspace session lock poisoned"))?;
    let session = guard.as_ref().ok_or_else(|| {
        session_err(
            "workspace_session_unavailable",
            "workspace session is not open",
        )
    })?;
    let session = Arc::clone(session);
    drop(guard);
    Ok(session)
}

pub fn session_is_open(engine: &LomoEngine) -> Result<bool, EngineError> {
    let guard = engine
        .session
        .lock()
        .map_err(|_poison| poisoned("workspace session lock poisoned"))?;
    Ok(guard.is_some())
}

pub fn session_owns_document_writes() -> EngineError {
    session_err(
        "session_owns_document_writes",
        "document writes belong to the workspace session",
    )
}

pub fn session_list_history_attachment_refs(
    engine: &LomoEngine,
) -> Result<Vec<StoreHistoryAttachmentRef>, EngineError> {
    match &engine.workspace {
        Some(core::WorkspaceDescriptor::Direct { canonical_root, .. }) => {
            let root = canonical_root.clone();
            with_session(engine, |_session| list_history_attachment_refs(&root)).map(|refs| {
                refs.into_iter()
                    .map(|item| StoreHistoryAttachmentRef {
                        memo_id: item.memo_id,
                        revision: item.revision,
                        relative_path: item.relative_path,
                        owner_key: item.owner_key,
                    })
                    .collect()
            })
        }
        Some(core::WorkspaceDescriptor::Saf { .. }) => {
            with_session(engine, |_session| Ok(Vec::new()))
        }
        None => Err(session_err(
            "workspace_session_unavailable",
            "history attachment listing requires an open workspace session",
        )),
    }
}

#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI query DTO is converted once into the store query"
)]
pub fn session_query_memos(
    engine: &LomoEngine,
    query: StoreMemoQuery,
    cursor: Option<StorePageCursor>,
    page_size: u32,
    start_memo_id: Option<String>,
    backward: bool,
) -> Result<StoreMemoPage, EngineError> {
    let page_size = PageSize::new(page_size).map_err(EngineError::from)?;
    let decoded_cursor = cursor
        .as_ref()
        .map(|cursor| decode_cursor(&cursor.encoded))
        .transpose()?;
    let start_memo_id = start_memo_id
        .as_deref()
        .map(str::trim)
        .filter(|id| !id.is_empty());
    let start = ffi_query_start(decoded_cursor.as_ref(), start_memo_id, backward)
        .map_err(EngineError::from)?;
    let (mq, boundary) = memo_query_from_ffi(query);
    with_session(engine, |session| {
        session.query_memos_starting_at(&mq, boundary.as_ref(), start, page_size)
    })
    .map(memo_page_to_ffi)
}

pub fn session_query_count(engine: &LomoEngine, query: StoreMemoQuery) -> Result<u64, EngineError> {
    let (mq, _boundary) = memo_query_from_ffi(query);
    with_session(engine, |session| session.query_count(&mq))
}

pub fn session_sidebar_projection(
    engine: &LomoEngine,
) -> Result<StoreSidebarProjection, EngineError> {
    with_session(engine, WorkspaceSession::sidebar_projection).map(|projection| {
        StoreSidebarProjection {
            schema_version: projection.schema_version,
            memo_count: projection.memo_count,
            date_counts: projection
                .date_counts
                .into_iter()
                .map(|bucket| StoreSidebarDateCount {
                    date: bucket.date,
                    count: bucket.count,
                })
                .collect(),
            tag_counts: projection
                .tag_counts
                .into_iter()
                .map(|tag| StoreSidebarTagCount {
                    name: tag.name,
                    count: tag.count,
                })
                .collect(),
        }
    })
}

pub fn session_projected_memo(
    engine: &LomoEngine,
    memo_id: &str,
) -> Result<Option<StoreMemoSnapshot>, EngineError> {
    with_session(engine, |session| session.projected_memo(memo_id)).map(|snapshot| {
        snapshot.map(|value| StoreMemoSnapshot {
            summary: summary_to_ffi(value.summary),
            body: value.body,
        })
    })
}

pub fn session_source_document_fingerprint(
    engine: &LomoEngine,
    source_path: &str,
) -> Result<Option<String>, EngineError> {
    with_session(engine, |session| {
        session.source_document_fingerprint(source_path)
    })
}

pub fn session_rebuild_projection(engine: &LomoEngine) -> Result<StoreRebuildResult, EngineError> {
    with_session(engine, WorkspaceSession::rebuild_projection).map(|result| StoreRebuildResult {
        memos_indexed: result.memos_indexed,
        file_count: result.file_count,
        attachment_count: result.attachment_count,
        workspace_digest: result.workspace_digest,
        store_digest: result.store_digest,
        corrupt_lomo_isolated: result.corrupt_lomo_isolated,
        high_water_revision: result.high_water_revision,
        rewritten: result.rewritten,
    })
}

pub fn session_memo_statistics_rows(
    engine: &LomoEngine,
) -> Result<Vec<StoreMemoStatisticsRow>, EngineError> {
    with_session(engine, WorkspaceSession::memo_statistics_rows).map(|rows| {
        rows.into_iter()
            .map(|row| StoreMemoStatisticsRow {
                created_at_ms: row.created_at_ms,
                word_count: row.word_count,
                char_count: row.char_count,
            })
            .collect()
    })
}

pub fn session_commit_workspace_document_facts(
    engine: &LomoEngine,
    command: StoreMemoCommand,
    projection: StoreSafMemoProjection,
) -> Result<StoreMemoCommit, EngineError> {
    let memo_id = command.memo_id.clone();
    let mutation = workspace_document_facts_mutation(command, projection)?;
    with_session(engine, |session| {
        session.commit_workspace_document_facts(&mutation)
    })
    .map(|result| commit_to_ffi(&memo_id, result))
}

pub fn commit_to_ffi(memo_id: &str, result: SafProjectionCommitResult) -> StoreMemoCommit {
    StoreMemoCommit {
        operation_id: result.operation_id,
        memo_id: if result.memo_id.is_empty() {
            memo_id.to_owned()
        } else {
            result.memo_id
        },
        core_revision: result.core_revision,
        event_sequence: result.event_sequence,
        content_revision: result.content_revision,
        file_fingerprint: result.file_fingerprint,
        scopes: scopes_to_ffi(result.scopes),
        idempotent_replay: result.idempotent_replay,
    }
}

fn search_outcome_to_ffi(outcome: SearchOutcome) -> SessionSearchOutcome {
    match outcome {
        SearchOutcome::Ready(page) => SessionSearchOutcome::Ready {
            page: SessionSearchPage {
                query_epoch: page.query_epoch,
                mode: match page.mode {
                    SearchMode::Fulltext => SessionSearchMode::Fulltext,
                    SearchMode::Fuzzy => SessionSearchMode::Fuzzy,
                },
                items: page
                    .items
                    .into_iter()
                    .map(|hit| SessionSearchHit {
                        summary: summary_to_ffi(hit.summary),
                        score: hit.score,
                    })
                    .collect(),
                next_cursor: page.next_cursor.map(|cursor| StorePageCursor {
                    encoded: encode_cursor(&cursor),
                }),
            },
        },
        SearchOutcome::Discarded {
            query_epoch,
            active_epoch,
        } => SessionSearchOutcome::Discarded {
            query_epoch,
            active_epoch,
        },
    }
}

fn statistics_to_ffi(stats: lomo_application::MemoStatistics) -> SessionStatistics {
    SessionStatistics {
        as_of: civil_date_to_ffi(stats.as_of_date),
        total_memos: stats.total_memos,
        total_words: stats.total_words,
        total_characters: stats.total_characters,
        average_words_per_memo: stats.average_words_per_memo,
        total_tags: stats.total_tags,
        active_days: stats.active_days,
        current_streak: stats.current_streak,
        longest_streak: stats.longest_streak,
        memo_count_by_date: stats
            .memo_count_by_date
            .into_iter()
            .map(|(date, count)| SessionDateCount {
                year: date.year(),
                month: date.month(),
                day: date.day(),
                count,
            })
            .collect(),
        hourly_distribution: stats
            .hourly_distribution
            .into_iter()
            .map(|(hour, count)| SessionHourCount { hour, count })
            .collect(),
        weekly_hour_distribution: stats
            .weekly_hour_distribution
            .into_iter()
            .flat_map(|(weekday, hours)| {
                hours
                    .into_iter()
                    .map(move |(hour, count)| SessionWeeklyHourCount {
                        weekday: weekday.iso_weekday(),
                        hour,
                        count,
                    })
            })
            .collect(),
        earliest_daily_memo_time: stats.earliest_daily_memo_time.map(civil_time_to_ffi),
        latest_daily_memo_time: stats.latest_daily_memo_time.map(civil_time_to_ffi),
        this_week_count: stats.this_week_count,
        last_week_count: stats.last_week_count,
        this_month_count: stats.this_month_count,
        last_month_count: stats.last_month_count,
        this_year_count: stats.this_year_count,
        last_year_count: stats.last_year_count,
        tag_counts: stats
            .tag_counts
            .into_iter()
            .map(|tag| SessionTagCount {
                name: tag.name,
                count: tag.count,
            })
            .collect(),
    }
}

fn civil_date_from_ffi(value: SessionCivilDate) -> Result<CivilDate, EngineError> {
    CivilDate::new(value.year, value.month, value.day)
        .map_err(|error| session_err("invalid_civil_date", &error.to_string()))
}

const fn civil_date_to_ffi(value: CivilDate) -> SessionCivilDate {
    SessionCivilDate {
        year: value.year(),
        month: value.month(),
        day: value.day(),
    }
}

const fn civil_time_to_ffi(value: lomo_application::calendar::CivilTime) -> SessionCivilTime {
    SessionCivilTime {
        hour: value.hour(),
        minute: value.minute(),
        second: value.second(),
    }
}

fn lomo_from_engine(error: EngineError) -> LomoError {
    let EngineError::Failure { failure } = error;
    match failure_to_core(&failure) {
        Ok(error) => error,
        Err(EngineError::Failure {
            failure: conversion,
        }) => {
            match LomoError::from_platform_boundary(
                core::ErrorCategory::Internal,
                "platform_host_error_unrepresentable",
                core::RetryDisposition::Never,
                None,
                None,
                &conversion.diagnostic,
            ) {
                Ok(error) | Err(error) => error,
            }
        }
    }
}

fn session_err(code: &str, diagnostic: &str) -> EngineError {
    EngineError::from(
        match LomoError::from_platform_boundary(
            core::ErrorCategory::Validation,
            code,
            core::RetryDisposition::Never,
            None,
            None,
            diagnostic,
        ) {
            Ok(error) | Err(error) => error,
        },
    )
}

fn poisoned(diagnostic: &str) -> EngineError {
    EngineError::from(
        match LomoError::from_platform_boundary(
            core::ErrorCategory::Internal,
            "session_lock_poisoned",
            core::RetryDisposition::Never,
            None,
            None,
            diagnostic,
        ) {
            Ok(error) | Err(error) => error,
        },
    )
}
