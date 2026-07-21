//! Stage-3 dark-build store FFI conversion surface (P3-09).
//!
//! Conversion-only mapping between `BoltFFI` DTOs and `lomo-store`. Business rules stay in
//! `lomo-store`.

use std::path::{Path, PathBuf};
use std::sync::Mutex;

use boltffi::data;
use lomo_core::{ErrorCategory, LomoError, OperationId, PageSize, RetryDisposition};
use lomo_store::{
    self as store, MemoCommand, MemoCommandKind, MemoFilters, MemoQuery, PageCursor,
    ReminderCommand, ReminderQuery, ReminderSessionInput, SnoozeStore, Store, TimeZoneContext,
    ZoneTransition,
};

use crate::EngineError;

/// Opaque page cursor for Kotlin (pipe-encoded store cursor; not SQL).
#[data]
#[derive(Clone, Debug, Default)]
pub struct StorePageCursor {
    pub encoded: String,
}

#[data]
#[derive(Clone, Debug, Default)]
pub struct StoreMemoFilters {
    pub tag: Option<String>,
    pub date_from_ms: Option<i64>,
    pub date_to_ms: Option<i64>,
    pub has_todo: Option<bool>,
    pub has_attachment: Option<bool>,
    pub has_url: Option<bool>,
    pub pinned_only: bool,
    pub include_trash: bool,
    pub trash_only: bool,
}

#[data]
#[derive(Clone, Debug, Default)]
pub struct StoreMemoQuery {
    pub search_text: Option<String>,
    pub filters: StoreMemoFilters,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreMemoSummary {
    pub memo_id: String,
    pub source_path: String,
    pub file_fingerprint: String,
    pub updated_at_ms: i64,
    pub created_at_ms: i64,
    pub has_todo: bool,
    pub has_url: bool,
    pub has_attachment: bool,
    pub is_pinned: bool,
    pub is_trashed: bool,
    pub body_preview: String,
    pub content_revision: u64,
    pub rank: Option<f64>,
    pub tags: Vec<String>,
    pub image_urls: Vec<String>,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreMemoPage {
    pub items: Vec<StoreMemoSummary>,
    pub next_cursor: Option<StorePageCursor>,
    pub high_water_revision: u64,
    pub query_fingerprint: String,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreMemoSnapshot {
    pub summary: StoreMemoSummary,
    pub body: String,
}

#[data]
#[derive(Clone, Copy, Debug)]
pub enum StoreMemoCommandKind {
    Create,
    Update,
    Delete,
    Restore,
    Pin,
    Unpin,
    HistoryRestore,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreMemoCommand {
    pub operation_id: String,
    pub kind: StoreMemoCommandKind,
    pub memo_id: String,
    pub expected_revision: u64,
    pub expected_fingerprint: Option<String>,
    pub content: Option<String>,
    pub tags: Vec<String>,
    pub pin: Option<bool>,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreMemoCommit {
    pub operation_id: String,
    pub memo_id: String,
    pub core_revision: u64,
    pub event_sequence: u64,
    pub content_revision: u64,
    pub file_fingerprint: String,
    pub scopes: Vec<String>,
    pub idempotent_replay: bool,
}

#[data]
#[derive(Clone, Copy, Debug)]
pub struct StoreZoneTransition {
    pub transition_utc_ms: i64,
    pub offset_before_secs: i32,
    pub offset_after_secs: i32,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreTimeZoneContext {
    pub zone_id: String,
    pub base_offset_secs: i32,
    pub transitions: Vec<StoreZoneTransition>,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreReminderSession {
    pub opaque_id: String,
    pub memo_identity: String,
    pub memo_revision: String,
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
pub struct StoreReminderQuery {
    pub now_utc_ms: i64,
    pub zone: StoreTimeZoneContext,
    pub sessions: Vec<StoreReminderSession>,
    pub rolling_window: u32,
    pub workspace_generation: u64,
}

#[data]
#[derive(Clone, Debug)]
pub struct StorePlannedAlarm {
    pub opaque_id: String,
    pub memo_identity: String,
    pub trigger_at_utc_ms: i64,
    pub is_catch_up: bool,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreReminderPlan {
    pub alarms: Vec<StorePlannedAlarm>,
    pub workspace_generation: u64,
}

#[data]
#[derive(Clone, Copy, Debug)]
pub enum StoreReminderCommandKind {
    MarkDone,
    RecordFired,
    Snooze,
    ClearSnooze,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreReminderCommand {
    pub kind: StoreReminderCommandKind,
    pub session: Option<StoreReminderSession>,
    pub expected_revision: Option<String>,
    pub opaque_id: Option<String>,
    pub memo_identity: Option<String>,
    pub memo_revision: Option<String>,
    pub workspace_generation: Option<u64>,
    pub snooze_until_utc_ms: Option<i64>,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreReminderCommandResult {
    pub replacement_token: Option<String>,
    pub scopes: Vec<String>,
    pub snooze_only: bool,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreRebuildResult {
    pub memos_indexed: u64,
    pub file_count: u64,
    pub attachment_count: u64,
    pub workspace_digest: String,
    pub store_digest: String,
    pub corrupt_lomo_isolated: u64,
    pub high_water_revision: u64,
}

/// Process-local store handle for one engine (dark-build; not dual production DI).
pub struct StoreHandle {
    workspace_root: PathBuf,
    store: Mutex<Option<Store>>,
    snooze: Mutex<SnoozeStore>,
}

impl std::fmt::Debug for StoreHandle {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let store_open = match self.store.lock() {
            Ok(guard) => guard.is_some(),
            Err(_poison) => false,
        };
        f.debug_struct("StoreHandle")
            .field("workspace_root", &self.workspace_root)
            .field("store_open", &store_open)
            .finish_non_exhaustive()
    }
}

impl StoreHandle {
    /// Opens app-private snooze under `app_private_root` (never workspace `.lomo`).
    ///
    /// # Errors
    ///
    /// Snooze open failures.
    pub fn new(workspace_root: PathBuf, app_private_root: &Path) -> Result<Self, EngineError> {
        let snooze_dir = app_private_root.join("reminder_snooze");
        let snooze = SnoozeStore::open_app_private(&snooze_dir).map_err(EngineError::from)?;
        Ok(Self {
            workspace_root,
            store: Mutex::new(None),
            snooze: Mutex::new(snooze),
        })
    }

    fn lock_store(&self) -> Result<std::sync::MutexGuard<'_, Option<Store>>, EngineError> {
        self.store.lock().map_err(|_e| {
            EngineError::from(boundary_err("store_mutex_poisoned", "store mutex poisoned"))
        })
    }

    fn with_store<R>(
        &self,
        f: impl FnOnce(&Store) -> Result<R, LomoError>,
    ) -> Result<R, EngineError> {
        let mut guard = self.lock_store()?;
        if guard.is_none() {
            *guard = Some(Store::open(&self.workspace_root).map_err(EngineError::from)?);
        }
        let store = guard.as_ref().ok_or_else(|| {
            EngineError::from(boundary_err(
                "store_missing",
                "store handle missing after open",
            ))
        })?;
        let result = f(store).map_err(EngineError::from)?;
        drop(guard);
        Ok(result)
    }

    fn with_store_mut<R>(
        &self,
        f: impl FnOnce(&mut Store) -> Result<R, LomoError>,
    ) -> Result<R, EngineError> {
        let mut guard = self.lock_store()?;
        if guard.is_none() {
            *guard = Some(Store::open(&self.workspace_root).map_err(EngineError::from)?);
        }
        let store = guard.as_mut().ok_or_else(|| {
            EngineError::from(boundary_err(
                "store_missing",
                "store handle missing after open",
            ))
        })?;
        let result = f(store).map_err(EngineError::from)?;
        drop(guard);
        Ok(result)
    }

    /// See plan `query_memos`.
    ///
    /// # Errors
    ///
    /// Store query / cursor validation errors.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned query wire types"
    )]
    pub fn query_memos(
        &self,
        query: StoreMemoQuery,
        cursor: Option<StorePageCursor>,
        page_size: u32,
    ) -> Result<StoreMemoPage, EngineError> {
        let page_size = PageSize::new(page_size).map_err(EngineError::from)?;
        let decoded_cursor = cursor
            .as_ref()
            .map(|c| decode_cursor(&c.encoded))
            .transpose()?;
        let mq = MemoQuery {
            search_text: query.search_text,
            filters: MemoFilters {
                tag: query.filters.tag,
                date_from_ms: query.filters.date_from_ms,
                date_to_ms: query.filters.date_to_ms,
                has_todo: query.filters.has_todo,
                has_attachment: query.filters.has_attachment,
                has_url: query.filters.has_url,
                pinned_only: query.filters.pinned_only,
                include_trash: query.filters.include_trash,
                trash_only: query.filters.trash_only,
            },
        };
        let page =
            self.with_store(|store| store.query_memos(&mq, decoded_cursor.as_ref(), page_size))?;
        Ok(StoreMemoPage {
            items: page.items.into_iter().map(summary_to_ffi).collect(),
            next_cursor: page.next_cursor.map(|c| StorePageCursor {
                encoded: encode_cursor(&c),
            }),
            high_water_revision: page.high_water_revision,
            query_fingerprint: page.query_fingerprint,
        })
    }

    /// See plan `get_memo`.
    ///
    /// # Errors
    ///
    /// Store errors.
    pub fn get_memo(&self, memo_id: &str) -> Result<Option<StoreMemoSnapshot>, EngineError> {
        let snap = self.with_store(|store| store.get_memo(memo_id))?;
        Ok(snap.map(|s| StoreMemoSnapshot {
            summary: summary_to_ffi(s.summary),
            body: s.body,
        }))
    }

    /// See plan `apply_memo_command` (dark-build returns commit facts synchronously).
    ///
    /// # Errors
    ///
    /// Store transaction errors.
    pub fn apply_memo_command(
        &self,
        command: StoreMemoCommand,
    ) -> Result<StoreMemoCommit, EngineError> {
        let kind = match command.kind {
            StoreMemoCommandKind::Create => MemoCommandKind::Create,
            StoreMemoCommandKind::Update => MemoCommandKind::Update,
            StoreMemoCommandKind::Delete => MemoCommandKind::Delete,
            StoreMemoCommandKind::Restore => MemoCommandKind::Restore,
            StoreMemoCommandKind::Pin => MemoCommandKind::Pin,
            StoreMemoCommandKind::Unpin => MemoCommandKind::Unpin,
            StoreMemoCommandKind::HistoryRestore => MemoCommandKind::HistoryRestore,
        };
        let operation_id = OperationId::parse(&command.operation_id).map_err(EngineError::from)?;
        let inner = MemoCommand {
            operation_id,
            kind,
            memo_id: command.memo_id,
            expected_revision: command.expected_revision,
            expected_fingerprint: command.expected_fingerprint,
            content: command.content,
            tags: command.tags,
            pin: command.pin,
        };
        let result = self.with_store_mut(|store| store.apply_memo_command(&inner, None))?;
        Ok(StoreMemoCommit {
            operation_id: result.operation_id,
            memo_id: result.memo_id,
            core_revision: result.core_revision.get(),
            event_sequence: result.event_sequence.get(),
            content_revision: result.content_revision,
            file_fingerprint: result.file_fingerprint,
            scopes: result.scopes.into_iter().map(scope_name).collect(),
            idempotent_replay: result.idempotent_replay,
        })
    }

    /// See plan `query_reminder_plan`.
    ///
    /// # Errors
    ///
    /// Reminder plan errors.
    pub fn query_reminder_plan(
        &self,
        query: StoreReminderQuery,
    ) -> Result<StoreReminderPlan, EngineError> {
        let zone = TimeZoneContext {
            zone_id: query.zone.zone_id,
            base_offset_secs: query.zone.base_offset_secs,
            transitions: query
                .zone
                .transitions
                .into_iter()
                .map(|t| ZoneTransition {
                    transition_utc_ms: t.transition_utc_ms,
                    offset_before_secs: t.offset_before_secs,
                    offset_after_secs: t.offset_after_secs,
                })
                .collect(),
        };
        let sessions = query
            .sessions
            .into_iter()
            .map(|s| ReminderSessionInput {
                opaque_id: s.opaque_id,
                memo_identity: s.memo_identity,
                memo_revision: s.memo_revision,
                token: s.token,
                due_at_local: s.due_at_local,
                repeat_count: s.repeat_count,
                fired_count: s.fired_count,
                done: s.done,
                interval_minutes: s.interval_minutes,
                recurrence_code: s.recurrence_code,
            })
            .collect();
        let rolling_window = usize::try_from(query.rolling_window).unwrap_or(usize::MAX);
        let inner = ReminderQuery {
            now_utc_ms: query.now_utc_ms,
            zone,
            sessions,
            rolling_window,
            workspace_generation: query.workspace_generation,
        };
        let plan = {
            let snooze = self.snooze.lock().map_err(|_e| {
                EngineError::from(boundary_err(
                    "snooze_mutex_poisoned",
                    "snooze mutex poisoned",
                ))
            })?;
            store::query_reminder_plan(&inner, &snooze).map_err(EngineError::from)?
        };
        Ok(StoreReminderPlan {
            alarms: plan
                .alarms
                .into_iter()
                .map(|a| StorePlannedAlarm {
                    opaque_id: a.opaque_id,
                    memo_identity: a.memo_identity,
                    trigger_at_utc_ms: a.trigger_at_utc_ms,
                    is_catch_up: a.is_catch_up,
                })
                .collect(),
            workspace_generation: plan.workspace_generation,
        })
    }

    /// See plan `apply_reminder_command`.
    ///
    /// # Errors
    ///
    /// Reminder command errors.
    pub fn apply_reminder_command(
        &self,
        command: StoreReminderCommand,
    ) -> Result<StoreReminderCommandResult, EngineError> {
        let inner = reminder_command_from_ffi(command)?;
        let result = {
            let mut snooze = self.snooze.lock().map_err(|_e| {
                EngineError::from(boundary_err(
                    "snooze_mutex_poisoned",
                    "snooze mutex poisoned",
                ))
            })?;
            store::apply_reminder_command(&inner, &mut snooze).map_err(EngineError::from)?
        };
        Ok(StoreReminderCommandResult {
            replacement_token: result.replacement_token,
            scopes: result.scopes.into_iter().map(scope_name).collect(),
            snooze_only: result.snooze_only,
        })
    }

    /// See plan `start_rebuild` (dark-build synchronous rebuild).
    ///
    /// # Errors
    ///
    /// Rebuild errors.
    pub fn start_rebuild(&self, batch_size: u32) -> Result<StoreRebuildResult, EngineError> {
        let batch = if batch_size == 0 {
            64
        } else {
            usize::try_from(batch_size).unwrap_or(64)
        };
        {
            let mut guard = self.lock_store()?;
            // Drop live connection so rebuild can replace the file.
            drop(guard.take());
        }
        let opened = Store::open(&self.workspace_root).map_err(EngineError::from)?;
        let (store, result) = opened.rebuild(batch).map_err(EngineError::from)?;
        {
            let mut guard = self.lock_store()?;
            *guard = Some(store);
        }
        Ok(StoreRebuildResult {
            memos_indexed: result.memos_indexed,
            file_count: result.file_count,
            attachment_count: result.attachment_count,
            workspace_digest: result.workspace_digest,
            store_digest: result.store_digest,
            corrupt_lomo_isolated: result.corrupt_lomo_isolated,
            high_water_revision: result.high_water_revision,
        })
    }

    #[must_use]
    pub fn workspace_root(&self) -> &Path {
        &self.workspace_root
    }
}

#[expect(
    clippy::too_many_lines,
    reason = "FFI enum arm mapping is one conversion table for all reminder command kinds"
)]
fn reminder_command_from_ffi(
    command: StoreReminderCommand,
) -> Result<ReminderCommand, EngineError> {
    match command.kind {
        StoreReminderCommandKind::MarkDone => {
            let session = command.session.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "MarkDone requires session",
                ))
            })?;
            let expected = command.expected_revision.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "MarkDone requires expected_revision",
                ))
            })?;
            Ok(ReminderCommand::MarkDone {
                session: session_from_ffi(session),
                expected_revision: expected,
            })
        }
        StoreReminderCommandKind::RecordFired => {
            let session = command.session.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "RecordFired requires session",
                ))
            })?;
            let expected = command.expected_revision.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "RecordFired requires expected_revision",
                ))
            })?;
            Ok(ReminderCommand::RecordFired {
                session: session_from_ffi(session),
                expected_revision: expected,
            })
        }
        StoreReminderCommandKind::Snooze => {
            let opaque_id = command.opaque_id.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "Snooze requires opaque_id",
                ))
            })?;
            let memo_identity = command.memo_identity.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "Snooze requires memo_identity",
                ))
            })?;
            let memo_revision = command.memo_revision.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "Snooze requires memo_revision",
                ))
            })?;
            let workspace_generation = command.workspace_generation.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "Snooze requires workspace_generation",
                ))
            })?;
            let snooze_until_utc_ms = command.snooze_until_utc_ms.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "Snooze requires snooze_until_utc_ms",
                ))
            })?;
            Ok(ReminderCommand::Snooze {
                opaque_id,
                memo_identity,
                memo_revision,
                workspace_generation,
                snooze_until_utc_ms,
            })
        }
        StoreReminderCommandKind::ClearSnooze => {
            let opaque_id = command.opaque_id.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "ClearSnooze requires opaque_id",
                ))
            })?;
            let memo_identity = command.memo_identity.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "ClearSnooze requires memo_identity",
                ))
            })?;
            let memo_revision = command.memo_revision.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "ClearSnooze requires memo_revision",
                ))
            })?;
            let workspace_generation = command.workspace_generation.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "ClearSnooze requires workspace_generation",
                ))
            })?;
            Ok(ReminderCommand::ClearSnooze {
                opaque_id,
                memo_identity,
                memo_revision,
                workspace_generation,
            })
        }
    }
}

fn session_from_ffi(s: StoreReminderSession) -> ReminderSessionInput {
    ReminderSessionInput {
        opaque_id: s.opaque_id,
        memo_identity: s.memo_identity,
        memo_revision: s.memo_revision,
        token: s.token,
        due_at_local: s.due_at_local,
        repeat_count: s.repeat_count,
        fired_count: s.fired_count,
        done: s.done,
        interval_minutes: s.interval_minutes,
        recurrence_code: s.recurrence_code,
    }
}

fn summary_to_ffi(s: store::MemoSummary) -> StoreMemoSummary {
    StoreMemoSummary {
        memo_id: s.memo_id,
        source_path: s.source_path,
        file_fingerprint: s.file_fingerprint,
        updated_at_ms: s.updated_at_ms,
        created_at_ms: s.created_at_ms,
        has_todo: s.has_todo,
        has_url: s.has_url,
        has_attachment: s.has_attachment,
        is_pinned: s.is_pinned,
        is_trashed: s.is_trashed,
        body_preview: s.body_preview,
        content_revision: s.content_revision,
        rank: s.rank,
        tags: s.tags,
        image_urls: s.image_urls,
    }
}

fn scope_name(scope: lomo_core::InvalidationScope) -> String {
    match scope {
        lomo_core::InvalidationScope::MemoList => "memo_list".to_owned(),
        lomo_core::InvalidationScope::Search => "search".to_owned(),
        lomo_core::InvalidationScope::Trash => "trash".to_owned(),
        lomo_core::InvalidationScope::Pin => "pin".to_owned(),
        lomo_core::InvalidationScope::Tags => "tags".to_owned(),
        lomo_core::InvalidationScope::Stats => "stats".to_owned(),
        lomo_core::InvalidationScope::Reminder => "reminder".to_owned(),
        lomo_core::InvalidationScope::Full => "full".to_owned(),
    }
}

fn encode_cursor(cursor: &PageCursor) -> String {
    format!(
        "{}|{}|{}|{}|{}",
        cursor.query_fingerprint,
        cursor.sort_updated_at_ms,
        cursor.sort_memo_id,
        cursor.high_water_revision,
        cursor.tokenizer_version
    )
}

fn decode_cursor(encoded: &str) -> Result<PageCursor, EngineError> {
    let parts: Vec<&str> = encoded.split('|').collect();
    let (
        Some(query_fingerprint),
        Some(sort_updated),
        Some(sort_memo_id),
        Some(high_water),
        Some(tokenizer_version),
    ) = (
        parts.first().copied(),
        parts.get(1).copied(),
        parts.get(2).copied(),
        parts.get(3).copied(),
        parts.get(4).copied(),
    )
    else {
        return Err(EngineError::from(boundary_err(
            "invalid_page_cursor",
            "store page cursor encoding mismatch",
        )));
    };
    if parts.len() != 5 {
        return Err(EngineError::from(boundary_err(
            "invalid_page_cursor",
            "store page cursor encoding mismatch",
        )));
    }
    let sort_updated_at_ms = sort_updated.parse::<i64>().map_err(|_e| {
        EngineError::from(boundary_err(
            "invalid_page_cursor",
            "store page cursor sort key is not i64",
        ))
    })?;
    let high_water = high_water.parse::<u64>().map_err(|_e| {
        EngineError::from(boundary_err(
            "invalid_page_cursor",
            "store page cursor high_water is not u64",
        ))
    })?;
    let tokenizer_version = tokenizer_version.parse::<u32>().map_err(|_e| {
        EngineError::from(boundary_err(
            "invalid_page_cursor",
            "store page cursor tokenizer_version is not u32",
        ))
    })?;
    Ok(PageCursor {
        query_fingerprint: query_fingerprint.to_owned(),
        sort_updated_at_ms,
        sort_memo_id: sort_memo_id.to_owned(),
        high_water_revision: high_water,
        tokenizer_version,
    })
}

fn boundary_err(code: &str, diagnostic: &str) -> LomoError {
    match LomoError::from_platform_boundary(
        ErrorCategory::Validation,
        code,
        RetryDisposition::Never,
        None,
        None,
        diagnostic,
    ) {
        Ok(error) | Err(error) => error,
    }
}
